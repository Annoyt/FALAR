"""Бенчмарк машинного перевода pt-BR <-> ru.

Движки:
  nllb       — NLLB-200-distilled-600M (CTranslate2 int8), офлайн
  opus       — OPUS-MT tc-big pt-zle / zle-pt (CTranslate2 int8), офлайн, прямая пара
  bergamot   — Mozilla/Firefox tiny|base через slimt, офлайн, ПИВОТ через английский
  gemma      — Gemma 3 4B it GGUF Q4_K_M (llama.cpp), офлайн, с глоссарием
  openrouter — OpenRouter :free-модели, онлайн-эталон (платные запрещены)

Примеры:
  python mt_bench.py --engine nllb --direction pt2ru
  python mt_bench.py --engine gemma --direction ru2pt
  python mt_bench.py --engine openrouter --model google/gemini-2.0-flash-exp:free
"""
import argparse
import re
import json
import os
import time
from pathlib import Path

import poco_limits

PROFILE = poco_limits.apply()
DATA = Path(__file__).parent / "data" / "test_set.json"
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

    def __init__(self, direction: str, split: bool = True):
        import ctranslate2
        import sentencepiece as spm
        from huggingface_hub import snapshot_download

        self.direction = direction
        self.split = split
        self.name = f"opus-mt-tc-big-{'pt-zle' if direction == 'pt2ru' else 'zle-pt'}-int8" + ("+split" if split else "")
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
        results = self.translator.translate_batch(batch, beam_size=4)
        return " ".join(self.sp_tgt.decode(r.hypotheses[0]).strip() for r in results)


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
    ap.add_argument("--engine", choices=["nllb", "opus", "bergamot", "gemma", "openrouter"], required=True)
    ap.add_argument("--tier", default="android", help="bergamot: android (base-memory) | tiny (нет для pt)")
    ap.add_argument("--direction", choices=["pt2ru", "ru2pt"], default="pt2ru")
    ap.add_argument("--model", default="google/gemini-2.0-flash-exp:free")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--no-split", action="store_true", help="opus: не резать вход по предложениям")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    src, tgt = args.direction.split("2")
    key = "pt_to_ru" if args.direction == "pt2ru" else "ru_to_pt"
    items = json.loads(DATA.read_text())[key]
    if args.limit:
        items = items[: args.limit]

    glossary = load_glossary()
    engine = {"nllb": lambda: NLLBEngine(),
              "opus": lambda: OpusEngine(args.direction, split=not args.no_split),
              "bergamot": lambda: BergamotEngine(args.direction, tier=args.tier),
              "gemma": lambda: GemmaEngine(glossary),
              "openrouter": lambda: OpenRouterEngine(args.model, glossary)}[args.engine]()

    results, latencies = [], []
    for it in items:
        t0 = time.perf_counter()
        hyp = engine.translate(it[src], src, tgt)
        dt = time.perf_counter() - t0
        latencies.append(dt)
        results.append({"src": it[src], "ref": it[tgt], "hyp": hyp, "sec": round(dt, 2)})
        print(f"[{dt:5.2f}s] {it[src]}\n  -> {hyp}")

    import sacrebleu
    # references — список ПОТОКОВ эталонов (один поток = список по всем предложениям),
    # а не список эталонов по предложению; прежняя форма давала фиктивные 100.0
    chrf = sacrebleu.corpus_chrf([r["hyp"] for r in results],
                                 [[r["ref"] for r in results]])
    lat_sorted = sorted(latencies)
    n = len(latencies)
    summary = {
        "engine": engine.name, "direction": args.direction, "profile": PROFILE,
        "chrf": round(chrf.score, 2),
        "lat_avg": round(sum(latencies) / n, 2),
        "lat_p50": round(lat_sorted[n // 2], 2),
        "lat_p95": round(lat_sorted[int(n * 0.95)], 2),
        "n": n,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    tag = args.engine + (f"-{args.tier}" if args.engine == "bergamot" else "")
    out = Path(args.out) if args.out else Path("results") / f"mt_{tag}_{args.direction}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps({"summary": summary, "results": results},
                              ensure_ascii=False, indent=2))
    print(f"saved -> {out}")


if __name__ == "__main__":
    main()
