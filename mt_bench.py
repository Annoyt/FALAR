"""Бенчмарк машинного перевода pt-BR <-> ru.

Движки:
  nllb       — NLLB-200-distilled-600M (CTranslate2 int8), офлайн
  opus       — OPUS-MT tc-big pt-zle / zle-pt (CTranslate2 int8, луч 4), офлайн, прямая пара
  app        — та же OPUS-MT ровно как в приложении: ONNX int8 с телефона, жадный декод
  bergamot   — Mozilla/Firefox tiny|base через slimt, офлайн, ПИВОТ через английский
  gemma      — Gemma 3 4B it GGUF Q4_K_M (llama.cpp), офлайн, с глоссарием
  openrouter — OpenRouter :free-модели, онлайн-эталон (платные запрещены)

Наборы (--set, по умолчанию оба):
  tatoeba    — data/mt_test/tatoeba.json: по ~1000 пар на направление из Tatoeba, которых модели не
               видели при обучении (связи после 2022-01-01, эталон — от носителя; tools/mt_testset.py)
  situations — data/test_set.json: прежние 40 фраз на направление из бытовых ситуаций
Оценки (tools/mt_metrics.py): chrF и chrF++ с 95 % интервалом по каждому набору; COMET — если есть
окружение .venv-comet (иначе --no-comet или позже: .venv-comet/bin/python tools/mt_metrics.py ФАЙЛ).
Сравнить два прогона: .venv-comet/bin/python tools/mt_metrics.py --compare A.json B.json

Примеры:
  python mt_bench.py --engine nllb --direction pt2ru
  python mt_bench.py --engine gemma --direction ru2pt
  python mt_bench.py --engine openrouter --model google/gemini-2.0-flash-exp:free --limit 50
"""
import argparse
import re
import json
import os
import subprocess
import sys
import time
import unicodedata
from pathlib import Path

import poco_limits

sys.path.insert(0, str(Path(__file__).parent / "tools"))
import mt_metrics  # noqa: E402

PROFILE = poco_limits.apply()
SETS = {"tatoeba": Path(__file__).parent / "data" / "mt_test" / "tatoeba.json",
        "situations": Path(__file__).parent / "data" / "test_set.json"}
GLOSSARY_PATH = Path(__file__).parent / "data" / "glossary.json"

LANG_NAMES = {"pt": "Brazilian Portuguese", "ru": "Russian"}
NLLB_CODES = {"pt": "por_Latn", "ru": "rus_Cyrl"}


def load_glossary() -> dict:
    if GLOSSARY_PATH.exists():
        return json.loads(GLOSSARY_PATH.read_text())
    return {}


# ---------- NLLB ----------
class NLLBEngine:
    name = "nllb-600m-int8"

    def __init__(self):
        import ctranslate2
        from transformers import AutoTokenizer
        from huggingface_hub import snapshot_download

        path = snapshot_download("entai2965/nllb-200-distilled-600M-ctranslate2")
        self.tokenizer = AutoTokenizer.from_pretrained(path)
        self.translator = ctranslate2.Translator(
            path, device="cpu", compute_type="int8",
            inter_threads=1, intra_threads=poco_limits.cpu_threads())

    def translate(self, text: str, src: str, tgt: str) -> str:
        self.tokenizer.src_lang = NLLB_CODES[src]
        tokens = self.tokenizer.convert_ids_to_tokens(self.tokenizer.encode(text))
        results = self.translator.translate_batch(
            [tokens], target_prefix=[[NLLB_CODES[tgt]]], beam_size=4)
        out = self.tokenizer.decode(
            self.tokenizer.convert_tokens_to_ids(results[0].hypotheses[0][1:]),
            skip_special_tokens=True)
        return out


# ---------- OPUS-MT tc-big (прямая пара pt<->ru, CTranslate2 int8) ----------
class OpusEngine:
    """Helsinki-NLP/opus-mt-tc-big-pt-zle (pt->ru) и opus-mt-tc-big-zle-pt (ru->pt).

    Marian transformer-big, прямой перевод без пивота через английский, CC-BY-4.0.
    Первый запуск конвертирует HF-чекпойнт в CTranslate2 int8 (для этого нужен torch);
    дальше работает только на ctranslate2 + sentencepiece, без torch.
    """
    REPOS = {"pt2ru": "Helsinki-NLP/opus-mt-tc-big-pt-zle",
             "ru2pt": "Helsinki-NLP/opus-mt-tc-big-zle-pt"}
    # pt-zle многоцелевая (rus/ukr/bel) — нужен токен цели; zle-pt одноцелевая (por) — не нужен
    TGT_TOKEN = {"pt2ru": ">>rus<<", "ru2pt": None}
    HF_FILES = ["config.json", "generation_config.json", "model.safetensors", "source.spm",
                "target.spm", "vocab.json", "tokenizer_config.json", "special_tokens_map.json",
                "README.md", "benchmark_results.txt"]

    # Marian обучен на одиночных предложениях: при двух предложениях во входе первое
    # систематически выпадает (замер 2026-09-10: chrF 56.7 против 62.8 на однофразных).
    # Поэтому режем по предложениям и переводим батчем за один вызов.
    SPLIT_RE = re.compile(r"(?<=[.!?…])\s+(?=\S)")

    def __init__(self, direction: str, split: bool = True, beam: int = 4):
        import ctranslate2
        import sentencepiece as spm
        from huggingface_hub import snapshot_download

        self.direction = direction
        self.split = split
        self.beam = beam
        self.name = f"opus-mt-tc-big-{'pt-zle' if direction == 'pt2ru' else 'zle-pt'}-int8" + ("+split" if split else "") \
            + (f"-beam{beam}" if beam != 4 else "")
        local = Path(__file__).parent / "models" / "opus-hf" / direction
        if (local / "model.safetensors").exists():
            hf_path = local  # скачано параллельными range-запросами (HF отдаёт ~200 KB/s на соединение)
        else:
            hf_path = Path(snapshot_download(self.REPOS[direction], allow_patterns=self.HF_FILES))
        ct2_dir = Path(__file__).parent / "models" / "opus-ct2" / direction
        if not (ct2_dir / "model.bin").exists():
            from ctranslate2.converters import TransformersConverter
            print(f"[opus] конвертирую {self.REPOS[direction]} -> {ct2_dir} (int8) ...")
            TransformersConverter(str(hf_path)).convert(str(ct2_dir), quantization="int8", force=True)
        self.sp_src = spm.SentencePieceProcessor(model_file=str(hf_path / "source.spm"))
        self.sp_tgt = spm.SentencePieceProcessor(model_file=str(hf_path / "target.spm"))
        self.translator = ctranslate2.Translator(
            str(ct2_dir), device="cpu", compute_type="int8",
            inter_threads=1, intra_threads=poco_limits.cpu_threads())

    def translate(self, text: str, src: str, tgt: str) -> str:
        sents = [x for x in self.SPLIT_RE.split(text.strip()) if x] if self.split else [text]
        tok = self.TGT_TOKEN[self.direction]
        batch = []
        for sent in sents:
            # Marian обучен с </s> в конце источника; HF-токенизатор добавляет его сам, CT2 — нет
            # (add_source_eos=false). Без него декодер не находит конец и зацикливается.
            tokens = self.sp_src.encode(sent, out_type=str) + ["</s>"]
            batch.append([tok] + tokens if tok else tokens)
        results = self.translator.translate_batch(batch, beam_size=self.beam)
        return " ".join(self.sp_tgt.decode(r.hypotheses[0]).strip() for r in results)


# ---------- OPUS-MT как в приложении (ONNX int8, жадный декод) ----------
class AppEngine:
    """Перевод ровно как в приложении (Engine.translate): та же модель, что opus выше, но файлы
    models/mt/<dir> — ONNX int8, которые едут на телефон; кодировщик сразу отдаёт K/V перекрёстного
    внимания (encoder_kv_model.onnx), все шаги делает decoder_with_past, декод жадный, до 64 токенов на
    предложение. Токенизатор — Viterbi по <dir>_source_pieces.tsv, как SpmTokenizer.java, вплоть до
    float32 в оценках кусков и ASCII-пробелов Java в регулярках. Отличие от opus выше: там CTranslate2
    и поиск лучом шириной 4, здесь — то, что слышит собеседник. До разговорника и TextRules дело не
    доходит: меряется только модель."""
    LAYERS, HEADS, HEAD_DIM, MAX_NEW = 6, 16, 64, 64
    WS = " \t\n\x0b\f\r"   # \s в Java — только ASCII
    SPLIT_RE = re.compile(r"(?<=[.!?…])[ \t\n\x0b\f\r]+(?=[^ \t\n\x0b\f\r])")
    JAVA_TRIM = "".join(chr(c) for c in range(0x21))

    def __init__(self, direction: str):
        import numpy as np
        import onnxruntime as ort

        self.np = np
        self.name = "opus-onnx-int8-greedy-app"
        d = Path(__file__).parent / "models" / "mt" / direction
        self.score = {}
        for line in (d / f"{direction}_source_pieces.tsv").read_text(encoding="utf-8").split("\n"):
            t = line.rfind("\t")
            if t >= 0:
                self.score[line[:t]] = float(np.float32(line[t + 1:]))   # Float.parseFloat
        self.max_piece = max(map(len, self.score))
        self.vocab = json.loads((d / f"{direction}_vocab.json").read_text(encoding="utf-8"))
        self.inv = {v: k for k, v in self.vocab.items()}
        self.lang = ">>rus<<" if direction == "pt2ru" else None
        so = ort.SessionOptions()
        so.intra_op_num_threads, so.inter_op_num_threads = poco_limits.cpu_threads(), 1
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        cpu = ["CPUExecutionProvider"]
        self.enc = ort.InferenceSession(str(d / "encoder_kv_model.onnx"), so, providers=cpu)
        self.dec = ort.InferenceSession(str(d / "decoder_with_past_model.onnx"), so, providers=cpu)
        self.enc_out = [o.name for o in self.enc.get_outputs()]
        self.dec_out = [o.name for o in self.dec.get_outputs()]

    def pieces(self, text: str) -> list:
        s = re.sub(f"[{self.WS}]+", " ", unicodedata.normalize("NFKC", text).strip(self.JAVA_TRIM))
        s = "▁" + s.replace(" ", "▁")
        n, neg = len(s), float("-inf")
        best, back = [neg] * (n + 1), [0] * (n + 1)
        best[0] = 0.0
        for i in range(n):
            if best[i] == neg:
                continue
            for j in range(i + 1, min(n, i + self.max_piece) + 1):
                sc = self.score.get(s[i:j])
                cand = best[i] + sc if sc is not None else (best[i] - 100.0 if j == i + 1 else neg)
                if cand > best[j]:
                    best[j], back[j] = cand, i
        out, j = [], n
        while j > 0:
            out.append(s[back[j]:j])
            j = back[j]
        return out[::-1]

    def step(self, past: dict, mask, token: int) -> int:
        np = self.np
        r = dict(zip(self.dec_out, self.dec.run(None, {**past, "encoder_attention_mask": mask,
                                                       "input_ids": np.array([[token]], dtype=np.int64)})))
        for l in range(self.LAYERS):
            for kv in ("key", "value"):
                past[f"past_key_values.{l}.decoder.{kv}"] = r[f"present.{l}.decoder.{kv}"]
        return int(np.argmax(r["logits"][0, -1]))   # первый максимум, как argmax в Engine

    def translate(self, text: str, src: str, tgt: str) -> str:
        np = self.np
        start, eos, unk = self.vocab["<pad>"], self.vocab["</s>"], self.vocab["<unk>"]
        out = []
        for sent in self.SPLIT_RE.split(text):
            if not sent.strip(self.JAVA_TRIM):
                continue
            ids = ([self.vocab[self.lang]] if self.lang else []) + \
                  [self.vocab.get(p, unk) for p in self.pieces(sent)] + [eos]
            inp = np.array([ids], dtype=np.int64)
            mask = np.ones_like(inp)
            e = dict(zip(self.enc_out, self.enc.run(None, {"input_ids": inp, "attention_mask": mask})))
            past = {}
            for l in range(self.LAYERS):
                for kv in ("key", "value"):
                    past[f"past_key_values.{l}.encoder.{kv}"] = e[f"present.{l}.encoder.{kv}"]
                    past[f"past_key_values.{l}.decoder.{kv}"] = np.zeros((1, self.HEADS, 0, self.HEAD_DIM), np.float32)
            got, nxt = [], self.step(past, mask, start)
            while nxt != eos and len(got) < self.MAX_NEW:
                got.append(nxt)
                nxt = self.step(past, mask, nxt)
            piece = "".join(p for p in (self.inv.get(i) for i in got) if p and p not in ("</s>", "<pad>"))
            out.append(piece.replace("▁", " ").strip(self.JAVA_TRIM))
        return " ".join(out)


# ---------- Bergamot / Mozilla (пивот через английский) ----------
class BergamotEngine:
    """Модели Firefox Translations (Mozilla) — пивот pt->en->ru / ru->en->pt (service.pivot).

    Прямой пары pt<->ru у Mozilla нет. Уровни в реестре: tiny (17 МБ; для pt НЕ существует),
    android = base-memory (31.5 МБ, то, что Firefox ставит на телефоны), desktop = base (43 МБ).
    Движок: bergamot-translator 0.4.5 (эталонный Marian; wheel только для Python <=3.10) —
    .venv/bin/python mt_bench.py --engine bergamot ...   (bergamot требует py3.10, см. README)
    Запасной: slimt (Python <=3.11), но он умеет ТОЛЬКО tiny и падает на base-memory.
    Файлы: models/bergamot/<pair>/<tier>/{model,vocab,lex,config.yml}.
    """
    ROOT = Path(__file__).parent / "models" / "bergamot"

    def __init__(self, direction: str, tier: str = "android"):
        src, tgt = direction.split("2")
        self.pairs = [f"{src}en", f"en{tgt}"]
        self.tier = tier
        self.name = f"bergamot-{tier}-pivot-{src}-en-{tgt}"
        try:
            import bergamot
            self.backend = "bergamot"
            self.service = bergamot.Service(
                bergamot.ServiceConfig(numWorkers=poco_limits.cpu_threads(), logLevel="off"))
            self.models = [self.service.modelFromConfigPath(str(self.ROOT / p / tier / "config.yml"))
                           for p in self.pairs]
            self.opts, self.vs = bergamot.ResponseOptions(), bergamot.VectorString
        except ImportError:
            from slimt import Config, Model, Package, Service
            self.backend = "slimt"
            self.service = Service(workers=poco_limits.cpu_threads())
            self.models = []
            for p in self.pairs:
                d = self.ROOT / p / tier
                pick = lambda pref: str(next(f for f in d.iterdir() if f.name.startswith(pref)))
                self.models.append(Model(Config(), Package(
                    model=pick("model."), vocabulary=pick("vocab."), shortlist=pick("lex."))))

    def translate(self, text: str, src: str, tgt: str) -> str:
        first, second = self.models
        if self.backend == "bergamot":
            responses = self.service.pivot(first, second, self.vs([text]), self.opts)
        else:
            responses = self.service.pivot(first, second, [text], html=False)
        return responses[0].target.text.strip()

# ---------- Gemma (llama.cpp) ----------
class GemmaEngine:
    name = "gemma-3-4b-q4km"

    def __init__(self, glossary: dict):
        from llama_cpp import Llama
        from huggingface_hub import hf_hub_download

        path = hf_hub_download(
            "bartowski/google_gemma-3-4b-it-GGUF",
            "google_gemma-3-4b-it-Q4_K_M.gguf")
        self.llm = Llama(model_path=path, n_ctx=2048,
                         n_threads=poco_limits.cpu_threads(), verbose=False)
        self.glossary = glossary

    def _prompt(self, text: str, src: str, tgt: str) -> str:
        gloss = ""
        pairs = [(k, v) for k, v in self.glossary.get(f"{src}2{tgt}", {}).items()
                 if k.lower() in text.lower()]
        if pairs:
            gloss = "\nUse these term translations: " + "; ".join(f"{k} = {v}" for k, v in pairs)
        return (
            f"Translate the following text from {LANG_NAMES[src]} to {LANG_NAMES[tgt]}. "
            f"Output ONLY the translation, nothing else.{gloss}\n\n{text}")

    def translate(self, text: str, src: str, tgt: str) -> str:
        resp = self.llm.create_chat_completion(
            messages=[{"role": "user", "content": self._prompt(text, src, tgt)}],
            temperature=0.1, max_tokens=256)
        return resp["choices"][0]["message"]["content"].strip().strip('"')


# ---------- OpenRouter (только :free) ----------
class OpenRouterEngine:
    ALLOW_PAID = False  # хардкод: платные модели запрещены

    def __init__(self, model: str, glossary: dict):
        if not model.endswith(":free") and not self.ALLOW_PAID:
            raise ValueError(f"Модель {model} не бесплатная — запрещено политикой ALLOW_PAID=False")
        from openai import OpenAI
        self.client = OpenAI(
            base_url="https://openrouter.ai/api/v1",
            api_key=os.environ["OPENROUTER_API_KEY"])
        self.model = model
        self.glossary = glossary
        self.name = f"openrouter:{model}"

    def translate(self, text: str, src: str, tgt: str) -> str:
        prompt = (f"Translate from {LANG_NAMES[src]} to {LANG_NAMES[tgt]}. "
                  f"Output ONLY the translation.\n\n{text}")
        last_err = None
        for attempt in range(6):
            try:
                resp = self.client.chat.completions.create(
                    model=self.model,
                    messages=[{"role": "user", "content": prompt}],
                    temperature=0.1)
                if not resp.choices:
                    raise RuntimeError("пустой ответ провайдера (choices=None)")
                return resp.choices[0].message.content.strip()
            except Exception as e:
                last_err = e
                if "429" in str(e):
                    time.sleep(min(5 * (attempt + 1), 30))
                else:
                    raise
        raise last_err


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", choices=["nllb", "opus", "app", "bergamot", "gemma", "openrouter"], required=True)
    ap.add_argument("--tier", default="android", help="bergamot: android (base-memory) | tiny (нет для pt)")
    ap.add_argument("--direction", choices=["pt2ru", "ru2pt"], default="pt2ru")
    ap.add_argument("--model", default="google/gemini-2.0-flash-exp:free")
    ap.add_argument("--set", choices=["all", *SETS], default="all", help="какой набор фраз (по умолчанию оба)")
    ap.add_argument("--limit", type=int, default=0, help="не больше K фраз из каждого набора")
    ap.add_argument("--no-split", action="store_true", help="opus: не резать вход по предложениям")
    ap.add_argument("--beam", type=int, default=4, help="opus: ширина луча (1 — жадный, как в приложении)")
    ap.add_argument("--no-comet", action="store_true", help="без COMET (только chrF и chrF++)")
    ap.add_argument("--resume", action="store_true", help="продолжить прерванный прогон с .partial.json")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    src, tgt = args.direction.split("2")
    key = "pt_to_ru" if args.direction == "pt2ru" else "ru_to_pt"
    items = []
    for name, path in SETS.items():
        if args.set not in ("all", name):
            continue
        part = json.loads(path.read_text())[key]
        items += [dict(it, set=name) for it in (part[: args.limit] if args.limit else part)]

    glossary = load_glossary()
    engine = {"nllb": lambda: NLLBEngine(),
              "opus": lambda: OpusEngine(args.direction, split=not args.no_split, beam=args.beam),
              "app": lambda: AppEngine(args.direction),
              "bergamot": lambda: BergamotEngine(args.direction, tier=args.tier),
              "gemma": lambda: GemmaEngine(glossary),
              "openrouter": lambda: OpenRouterEngine(args.model, glossary)}[args.engine]()

    tag = args.engine + (f"-{args.tier}" if args.engine == "bergamot" else "") \
        + (f"-beam{args.beam}" if args.engine == "opus" and args.beam != 4 else "")
    out = Path(args.out) if args.out else Path("results") / f"mt_{tag}_{args.direction}.json"
    out.parent.mkdir(exist_ok=True)
    # Длинный прогон (Gemma — час, OpenRouter — суточный лимит) пишет готовое в .partial.json каждые
    # 25 фраз; --resume продолжает с него, а прежний полный файл не трогается до конца прогона.
    # Если прогон дошёл до конца и упал только COMET, --resume берёт готовые переводы из полного файла
    # того же движка и переводит заново лишь то, чего там нет.
    partial = out.with_suffix(".partial.json")
    done = {}
    if args.resume:
        prev = partial if partial.exists() else out if out.exists() else None
        if prev is not None:
            d = json.loads(prev.read_text())
            if prev == partial or d.get("summary", {}).get("engine") == engine.name:
                done = {(mt_metrics.set_of(r), r["src"]): r for r in d["results"]}
        print(f"продолжаю: готово {len(done)} из {len(items)} ({prev})")

    results = []
    for it in items:
        if (it["set"], it[src]) in done:
            results.append(done[(it["set"], it[src])])
            continue
        t0 = time.perf_counter()
        hyp = engine.translate(it[src], src, tgt)
        dt = time.perf_counter() - t0
        r = {"set": it["set"], "src": it[src], "ref": it[tgt], "hyp": hyp, "sec": round(dt, 2)}
        if "pid" in it:
            r["id"] = f"{it['pid']}-{it['rid']}"   # предложения Tatoeba: por-rus
            r["added"] = it["added"]
        results.append(r)
        print(f"[{dt:5.2f}s] {it[src]}\n  -> {hyp}", flush=True)
        if len(results) % 25 == 0:
            partial.write_text(json.dumps({"results": results}, ensure_ascii=False))

    latencies = [r["sec"] for r in results]
    lat_sorted = sorted(latencies)
    n = len(latencies)
    summary = {
        "engine": engine.name, "direction": args.direction, "profile": PROFILE,
        "lat_avg": round(sum(latencies) / n, 2),
        "lat_p50": round(lat_sorted[n // 2], 2),
        "lat_p95": round(lat_sorted[int(n * 0.95)], 2),
        "n": n,
    }
    out.write_text(json.dumps({"summary": summary, "results": results},
                              ensure_ascii=False, indent=2))
    partial.unlink(missing_ok=True)
    print(f"saved -> {out}")
    # chrF и chrF++ — здесь; COMET — в своём окружении (torch и старые пины comet не для .venv)
    print(mt_metrics.line(out.name, mt_metrics.rescore(out)))
    if args.no_comet:
        return
    if not mt_metrics.COMET_PY.exists():
        print(f"COMET пропущен: нет {mt_metrics.COMET_PY} (как поставить — в начале tools/mt_metrics.py)")
        return
    sys.stdout.flush()   # иначе строка COMET из дочернего процесса окажется посреди журнала
    rc = subprocess.run([str(mt_metrics.COMET_PY), str(Path(mt_metrics.__file__)), str(out)]).returncode
    if rc:
        print(f"COMET не досчитан (код {rc}); перевод сохранён в {out}. Досчитать: "
              f"{mt_metrics.COMET_PY} {mt_metrics.__file__} {out}")
        sys.exit(3)


if __name__ == "__main__":
    main()
