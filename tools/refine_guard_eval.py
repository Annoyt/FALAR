#!/usr/bin/env python3
"""Ответ уточнителя, вобравший фон разговора: воспроизведение на открытом наборе (ПК, видеокарта).

Локальный уточнитель приложения — Hy-MT2-1.8B с подсказкой TranslatorService.hyPrompt: в «[Background
Information]» — другие реплики разговора на языке исходника, затем сама реплика. На живых разговорах владельца
ответ иногда длиннее реплики в разы и несёт перевод соседних реплик (данные телефона — только локально, сюда не
попадают). Здесь то же на открытом наборе: Tatoeba и «ситуации» из results/mt_app-beam4_*.json, черновик —
перевод приложения (OPUS-MT, луч 4). Реплики чередуются pt/ru, как в разговоре; проход — раз в три реплики, как
refineEvery по умолчанию: три новые реплики, у каждой в фоне все прочие реплики истории (не больше 30, как
history в службе), и ближние, и следующие за ней, через Brief.fit (1200 знаков, ближние первыми). Свои реплики —
как распознаны, чужие — черновым переводом (без правки облака, без памяти разговора и терминов). Для сравнения —
та же подсказка с пустым фоном. Жадный вывод (temperature 0) и предел 200 токенов, как llm.chat(…, 200).

Ответы пишутся построчно в --cache (продолжение после сбоя ПК — тем же вызовом). Разбор — правилом приложения
(Brief.swallowed): bench/apk/test/RefineGuardSim.java.

  PYTHONPATH=~/tools/llama-cuda taskset -c 2 .venv/bin/python tools/refine_guard_eval.py \
      [--cache .stand/refine-guard/gen.jsonl] [--limit N] [--cond ctx|empty|both] [--short | --split]
      [--recheck N] [--cpu]
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stand_paths  # noqa: E402

R = Path(__file__).resolve().parent.parent
APP_LANG = {"ru": "Russian", "pt": "Portuguese (Brazil)"}  # TranslatorService.langName
FRESH_CHARS = 1200                                         # Brief.FRESH_CHARS
HISTORY = 30                                               # history в TranslatorService: не больше 30 реплик
EVERY = 3                                                  # refineEvery по умолчанию


def brief_fit(lines, budget=FRESH_CHARS):
    """Brief.fit: ближние строки первыми, пока влезают; самая близкая, если не влезает, — хвостом с «…»."""
    out, left = [], budget
    for line in reversed(lines):
        line = (line or "").strip()
        if not line:
            continue
        if len(line) + 1 <= left:
            out.append(line)
            left -= len(line) + 1
            continue
        if not out and left > 12:
            out.append(brief_tail(line, left - 1))
        break
    return out[::-1]


def brief_tail(s, n):
    """Brief.tail."""
    if len(s) <= n:
        return s
    if n <= 1:
        return "…"
    start = len(s) - (n - 1)
    if not s[start - 1].isspace():
        sp = s.find(" ", start)
        if sp != -1 and sp < len(s) - 1:
            start = sp + 1
    return "…" + s[start:].strip()


def hy_prompt(bg, src, tgt):
    """TranslatorService.hyPrompt без терминов."""
    return ("[Background Information]\n" + bg + "\nPlease translate the following text into " + APP_LANG[tgt]
            + ", taking the provided background information into consideration\n[Source Text]\n" + src)


def dialogues():
    """Реплики по наборам: pt и ru по очереди, как в разговоре; хвост длинного списка — подряд."""
    by = {}
    for d in ("pt2ru", "ru2pt"):
        for x in json.load(open(R / "results" / f"mt_app-beam4_{d}.json", encoding="utf-8"))["results"]:
            by.setdefault(x["set"], {}).setdefault(d, []).append(
                {"dir": d, "id": x["id"] if "id" in x else x["src"][:40], "src": x["src"], "draft": x["hyp"], "ref": x["ref"]})
    out = {}
    for s, dd in sorted(by.items()):
        pt, ru, turns = dd.get("pt2ru", []), dd.get("ru2pt", []), []
        for i in range(max(len(pt), len(ru))):
            if i < len(pt):
                turns.append(pt[i])
            if i < len(ru):
                turns.append(ru[i])
        out[s] = turns
    return out


def items():
    """Что спрашиваем у уточнителя: по проходу на каждые EVERY реплик, фон — как backgroundLines + Brief.fit."""
    for s, turns in dialogues().items():
        start = 0
        while start < len(turns):
            end = min(start + EVERY, len(turns))
            lo = max(0, end - HISTORY)
            for i in range(start, end):
                t, sl = turns[i], turns[i]["dir"][:2]
                window = [j for j in range(lo, end) if j != i]
                lines = [turns[j]["src"] if turns[j]["dir"][:2] == sl else turns[j]["draft"] for j in window]
                yield {"set": s, "i": i, "dir": t["dir"], "id": t["id"], "src": t["src"], "draft": t["draft"],
                       "ref": t["ref"], "bg": brief_fit(lines),
                       "others": [[turns[j]["src"], turns[j]["draft"]] for j in window]}
            start = end


# Короткие реплики и обрывки распознавания — в живом разговоре их много, а в Tatoeba почти нет: (направление,
# исходник, перевод). Перевод — вместо черновика приложения (его длина и слова — всё, что нужно правилу).
SHORT = [
    ("pt2ru", "Sim.", "Да."), ("pt2ru", "Não.", "Нет."), ("pt2ru", "Tá bom.", "Хорошо."), ("pt2ru", "Claro.", "Конечно."),
    ("pt2ru", "Obrigada.", "Спасибо."), ("pt2ru", "Entendi.", "Понятно."), ("pt2ru", "Não sei.", "Не знаю."),
    ("pt2ru", "Pode ser.", "Может быть."), ("pt2ru", "Isso mesmo.", "Именно так."), ("pt2ru", "Tudo bem.", "Всё хорошо."),
    ("pt2ru", "Como?", "Как?"), ("pt2ru", "O quê?", "Что?"), ("pt2ru", "Quanto?", "Сколько?"), ("pt2ru", "Onde?", "Где?"),
    ("pt2ru", "Agora?", "Сейчас?"), ("pt2ru", "Amanhã.", "Завтра."), ("pt2ru", "Talvez.", "Возможно."),
    ("pt2ru", "De nada.", "Не за что."), ("pt2ru", "Por favor.", "Пожалуйста."), ("pt2ru", "Então", "Итак"),
    ("pt2ru", "É", "Да"), ("pt2ru", "E aí", "Ну что"), ("pt2ru", "Né?", "Правда?"), ("pt2ru", "Mais ou menos.", "Так себе."),
    ("pt2ru", "Com certeza.", "Обязательно."),
    ("ru2pt", "Да.", "Sim."), ("ru2pt", "Нет.", "Não."), ("ru2pt", "Хорошо.", "Tá bom."), ("ru2pt", "Конечно.", "Claro."),
    ("ru2pt", "Спасибо.", "Obrigado."), ("ru2pt", "Понятно.", "Entendi."), ("ru2pt", "Не знаю.", "Não sei."),
    ("ru2pt", "Может быть.", "Talvez."), ("ru2pt", "Ладно.", "Tudo bem."), ("ru2pt", "Что?", "O quê?"),
    ("ru2pt", "Сколько?", "Quanto?"), ("ru2pt", "Где?", "Onde?"), ("ru2pt", "Сейчас?", "Agora?"), ("ru2pt", "Завтра.", "Amanhã."),
    ("ru2pt", "Пожалуйста.", "Por favor."), ("ru2pt", "Извините.", "Desculpe."), ("ru2pt", "Ну", "Bem"), ("ru2pt", "Вот", "Aqui"),
    ("ru2pt", "Ага.", "Uhum."), ("ru2pt", "Так себе.", "Mais ou menos."), ("ru2pt", "Точно.", "Exatamente."),
]


def short_items():
    """Короткие реплики на фоне «ситуаций» (связный разговор): фон длиннее реплики в десятки раз, как у
    «Sim» посреди живого разговора. Каждая — в четырёх местах разговора, фон — до 29 прошлых реплик."""
    turns, k = dialogues()["situations"], 0
    for d, src, tr in SHORT:
        sl = d[:2]
        for p in (20, 40, 60, 80):
            window = list(range(max(0, p - HISTORY + 1), min(p, len(turns))))
            lines = [turns[j]["src"] if turns[j]["dir"][:2] == sl else turns[j]["draft"] for j in window]
            yield {"set": "short", "i": k, "dir": d, "id": src, "src": src, "draft": tr, "ref": tr, "bg": brief_fit(lines),
                   "others": [[turns[j]["src"], turns[j]["draft"]] for j in window]}
            k += 1


def split_frags(per_dir=60):
    """Фраза, которую нарезка разрезала паузой надвое: прошлая реплика — её начало, эта — конец. Длинные фразы
    Tatoeba (от восьми слов) режутся по слову у середины; фон — до 28 прошлых реплик разговора и начало фразы."""
    turns, out, n = dialogues()["tatoeba"], [], {"pt2ru": 0, "ru2pt": 0}
    for i, t in enumerate(turns):
        w = t["src"].split()
        if len(w) < 8 or n[t["dir"]] >= per_dir or i < HISTORY:
            continue
        n[t["dir"]] += 1
        out.append({"set": "split", "i": len(out), "dir": t["dir"], "id": t["id"], "at": i,
                    "head": " ".join(w[:len(w) // 2]), "tail": " ".join(w[len(w) // 2:])})
    return out


def split_items(frags, trans):
    """Запросы для конца фразы: фон — прошлые реплики и начало фразы (как распознано), черновик и мерило —
    перевод конца без фона (trans: ответы той же модели, условие «frag»)."""
    turns = dialogues()["tatoeba"]
    for fr in frags:
        sl, window = fr["dir"][:2], list(range(fr["at"] - HISTORY + 2, fr["at"]))
        lines = [turns[j]["src"] if turns[j]["dir"][:2] == sl else turns[j]["draft"] for j in window] + [fr["head"]]
        head_tr, tail_tr = trans[("head", fr["i"])], trans[("tail", fr["i"])]
        yield {"set": "split", "i": fr["i"], "dir": fr["dir"], "id": fr["id"], "src": fr["tail"], "draft": tail_tr,
               "ref": tail_tr, "bg": brief_fit(lines),
               "others": [[turns[j]["src"], turns[j]["draft"]] for j in window] + [[fr["head"], head_tr]]}


def model_path():
    p = R / "models" / "llm" / "Hy-MT2-1.8B-Q4_K_M.gguf"
    if p.exists():
        return p
    # рабочее дерево git без моделей — берём из основной копии, как bench/quality/gate.sh
    common = subprocess.run(["git", "-C", str(R), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                            capture_output=True, text=True).stdout.strip()
    return Path(common).parent / "models" / "llm" / p.name


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=stand_paths.stand("refine-guard", "gen.jsonl"))
    ap.add_argument("--limit", type=int, default=0, help="только первые N запросов каждого условия (проба)")
    ap.add_argument("--cpu", action="store_true", help="без видеокарты (медленно; ПК не любит долгую нагрузку)")
    ap.add_argument("--short", action="store_true", help="короткие реплики и обрывки (SHORT) на фоне «ситуаций»")
    ap.add_argument("--split", action="store_true", help="фраза, разрезанная надвое: конец с началом в фоне")
    ap.add_argument("--recheck", type=int, default=0, metavar="N",
                    help="перепроверка: N случайных ответов из --cache и все длиннее реплики вдвое — заново, сверка один в один. "
                         "Порядок другой, а llama.cpp переиспользует начало подсказки — на развилках ответы расходятся; "
                         "исправность ПК проверяет повтор тем же порядком в новый --cache")
    ap.add_argument("--cond", choices=["ctx", "empty", "both"], default="both",
                    help="ctx — только с фоном (мерило раздутого — перевод из набора), empty — только с пустым фоном")
    a = ap.parse_args()
    cache = Path(a.cache)
    cache.parent.mkdir(parents=True, exist_ok=True)
    done = set()
    if cache.exists():
        for line in open(cache, encoding="utf-8"):
            try:
                x = json.loads(line)
                done.add((x["cond"], x["set"], x["i"]))
            except (ValueError, KeyError):
                pass                                   # недописанная строка после сбоя — посчитается заново
    llm = []                                           # модель грузится, только когда есть что считать

    def ask(bg, src, tgt):
        if not llm:
            from llama_cpp import Llama
            if not a.cpu and not __import__("llama_cpp").llama_supports_gpu_offload():
                sys.exit("llama_cpp собран без видеокарты: PYTHONPATH=~/tools/llama-cuda или --cpu")
            llm.append(Llama(model_path=str(model_path()), n_ctx=2048, n_threads=1, n_gpu_layers=0 if a.cpu else -1,
                             seed=0, verbose=False))
        t = time.time()
        r = llm[0].create_chat_completion(messages=[{"role": "user", "content": hy_prompt(bg, src, tgt)}],
                                          temperature=0.0, max_tokens=200)
        return r["choices"][0]["message"]["content"].strip(), round(1000 * (time.time() - t)), r["choices"][0].get("finish_reason")

    def put(f, rec):
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        f.flush()
        os.fsync(f.fileno())

    if a.recheck:
        # ПК с андервольтом ошибается тихо: после долгого прогона — выборка заново, ответы должны совпасть
        import random
        recs = {}
        for line in open(cache, encoding="utf-8"):
            try:
                x = json.loads(line)
            except ValueError:
                continue
            if x.get("cond") in ("ctx", "empty") and "bg" in x:
                recs[(x["cond"], x["set"], x["i"])] = x
        long_ = [k for k, x in recs.items() if len(x["out"]) > 2 * max(len(x["src"]), len(x["draft"]))]
        pick = sorted(set(random.Random(0).sample(sorted(recs), min(a.recheck, len(recs)))) | set(long_))
        same = 0
        for k in pick:
            x = recs[k]
            out = ask("\n".join(x["bg"]) if x["cond"] == "ctx" else "", x["src"], x["dir"][3:])[0]
            same += out == x["out"]
            if out != x["out"]:
                print(f"не совпало {k}: было {x['out'][:80]!r}, стало {out[:80]!r}", flush=True)
        print(f"перепроверка: совпало {same} из {len(pick)} (длинных среди них {len(long_)})", flush=True)
        return

    with open(cache, "a", encoding="utf-8") as f:
        if a.split:
            # сначала переводы кусков без фона — черновики и мерило для конца фразы
            frags, trans = split_frags(), {}
            for line in open(cache, encoding="utf-8"):
                try:
                    x = json.loads(line)
                except ValueError:
                    continue
                if x.get("cond") == "frag":
                    trans[(x["part"], x["i"])] = x["out"]
            for fr in frags:
                for part in ("head", "tail"):
                    if (part, fr["i"]) not in trans:
                        out, ms, stop = ask("", fr[part], fr["dir"][3:])
                        trans[(part, fr["i"])] = out
                        put(f, {"cond": "frag", "set": "split-" + part, "i": fr["i"], "part": part, "dir": fr["dir"],
                                "src": fr[part], "out": out, "ms": ms, "stop": stop})
            todo = list(split_items(frags, trans))
        else:
            todo = list(short_items() if a.short else items())
        if a.limit:
            todo = todo[:a.limit]
        conds = ("ctx", "empty") if a.cond == "both" else (a.cond,)
        left = [(c, x) for c in conds for x in todo if (c, x["set"], x["i"]) not in done]
        print(f"запросов {len(conds) * len(todo)}, уже есть {len(conds) * len(todo) - len(left)}, осталось {len(left)}", flush=True)
        t0 = time.time()
        for k, (c, x) in enumerate(left):
            out, ms, stop = ask("\n".join(x["bg"]) if c == "ctx" else "", x["src"], x["dir"][3:])
            put(f, dict(x, cond=c, out=out, ms=ms, stop=stop))
            if (k + 1) % 100 == 0:
                print(f"{k + 1}/{len(left)} · {(time.time() - t0) / (k + 1):.2f} с на запрос", flush=True)
    print(f"готово: {len(left)} за {time.time() - t0:.0f} с", flush=True)


if __name__ == "__main__":
    main()
