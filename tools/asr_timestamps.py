#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Метки времени распознавателя: можно ли по ним вырезать одно предложение из реплики в два (на компьютере).

Вопрос и критерии (записаны до замера) — results/2026-10-03-asr-timestamps.md. Распознаватель тот же, что
bench/quality/asr_pc.py и Engine.asr: parakeet-tdt-0.6b-v3 int8 из models/asr_multi, nemo_transducer,
greedy_search, 80 признаков, 16 кГц, 4 потока, без добавочной тишины. sherpa-onnx 1.13 отдаёт на каждый токен
время (timestamps, шаг 80 мс — кадр энкодера) и длительность (durations — предсказание TDT).

Сегменты из двух предложений с известной паузой между ними:
  корпус  — две живые записи Tatoeba (bench/air/corpus/pt) подряд: у первой срезана тишина после речи, у
            второй — до речи, между речью пауза 0,15 / 0,3 / 0,6 с из гауссова шума −70 dBFS (не цифровой
            ноль); по 40 на паузу, в половине пар диктор один, в половине — разные (дикторы — из
            data/tatoeba/raw, как в bench/air/air_wer.py). Речь записи — по её же энергии: кадры 20 мс,
            порог — пик −35 дБ, но не ниже фона +10 дБ (у части записей фон −41…−45 dBFS, и «пик −35» лёг
            бы в шум), запас 2 кадра с каждой стороны; записи, где речь упирается в край, не берутся.
  комната — две соседние фразы из bench/air/rec/{near-pt,far-pt}/room.wav с настоящей паузой между ними
            (3,3–5 с). Где фраза в записи — по player.tsv и raw_begin с поправками часов *.offset
            (vad_denoise_eval.load_rec), уточнено взаимной корреляцией с самой записью корпуса в полосе
            0,3–4 кГц; граница речи — граница речи записи корпуса, перенесённая туда же. По краям сегмента —
            0,3 с комнаты. В far-pt с ~396 с играет мелодия (bench/air/rec/README.md) — фразы оттуда не берутся.
  сверх критериев — те же пары комнаты, но тишина между фразами вырезана до 0,15 / 0,3 / 0,6 с (склейка
            посреди фона комнаты, равномощный переход 10 мс): короткая пауза с настоящим фоном, где самое
            тихое окно не подсказано тишиной −70 dBFS.

Разрез — как в отчёте: граница — токен, кончающийся на . ? ! …, за которым есть ещё слова; конец первого
предложения — время этого токена плюс его длительность, начало второго — время первого токена со словом;
разрез — середина между ними, затем к самому тихому окну 20 мс в пределах ±0,4 с (копия Voices.snap — им режет
TranslatorService.splitSeg). Граница «на стыке» решается по тексту, без времени: распознанные слова
выравниваются с эталоном обеих записей, все слова до знака должны прийтись на первую, после — на вторую.

Счёт: (1) знак конца предложения на стыке; (2) разрез внутри настоящей паузы, расширенной на 50 мс с каждой
стороны. Сверх — |разрез − середина паузы| (медиана, p90), то же без притяжения к тишине, лишние границы
внутри предложений, предложения, потерянные распознавателем целиком, и сдвиг меток относительно настоящих границ речи (с запасом 2 кадра): «конец 1» — конец
знака минус конец речи первой записи, «слово 1» — то же для последнего токена со словом, «начало 2» — первый
токен второго предложения минус начало речи второй записи. Случай детерминирован (--seed; пары зависят ещё и
от дикторов из data/tatoeba/raw — без этого файла выйдут другие); всё вместе — около двух минут на 4 потоках.

    .venv/bin/python tools/asr_timestamps.py [--out /tmp/asr_timestamps.json] [--show 10] [--seed 20261003]
"""
import argparse
import json
import os
import sys
import time

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, "bench", "asr2"))
sys.path.insert(0, os.path.join(R, "bench", "air"))
sys.path.insert(0, os.path.join(R, "tools"))
from wer2 import norm  # noqa: E402  — одна норма текста на все стенды распознавания
from air_wer import speakers  # noqa: E402  — дикторы Tatoeba, как в счёте через воздух
from vad_denoise_eval import load_rec  # noqa: E402  — где какая фраза в записи комнаты

CORPUS = os.path.join(R, "bench", "air", "corpus", "pt")
MODEL = os.path.join(os.environ.get("FALAR_MODELS") or os.path.join(R, "models"), "asr_multi")
SR = 16000
FRAME = 320                    # 20 мс — кадр детектора речи и окно притяжения
PAUSES = (0.15, 0.3, 0.6)
PER_PAUSE = 40
FILL_DB = -70.0                # шум паузы в корпусе
MARGIN = 0.05                  # критерий 2: пауза шире на 50 мс с каждой стороны
SNAP = 0.4                     # притяжение к тишине: ±0,4 с, как splitSeg
EDGE = 0.3                     # комната: столько фона по краям сегмента
XFADE = 160                    # склейка тишины комнаты: равномощный переход 10 мс
ROOMS = ("near-pt", "far-pt")
MELODY = {"far-pt": 395.0}     # с этой секунды записи — мелодия (по тональности 1–1,6 кГц нарастает с ~396 с)
ENDS = ".?!…"


def samples(path):
    import wave
    with wave.open(path) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def speech(x):
    """Речь записи по энергии: (начало, конец, упирается_в_край), секунды, запас 2 кадра."""
    n = len(x) // FRAME
    d = 10 * np.log10(np.mean(x[:n * FRAME].reshape(n, FRAME).astype(np.float64) ** 2, axis=1) + 1e-12)
    live = d[d > -90]                                  # цифровой ноль — не фон
    floor = np.percentile(live, 5) if len(live) else -90.0
    on = np.flatnonzero(d > max(d.max() - 35, floor + 10))
    a, b = int(on[0]), int(on[-1])
    return max(0, a - 2) * FRAME / SR, min(n, b + 3) * FRAME / SR, a < 2 or b > n - 3


def snap(x, cut, radius=SNAP):
    """Voices.snap: центр самого тихого окна 20 мс (шаг 10 мс) в пределах ±radius от cut."""
    f = SR // 50
    lo, hi = max(0, int((cut - radius) * SR)), min(len(x) - f, int((cut + radius) * SR))
    if hi < lo:
        return cut
    c = np.concatenate([[0.0], np.cumsum(x.astype(np.float64) ** 2)])
    o = np.arange(lo, hi + 1, f // 2)
    e = c[o + f] - c[o]
    return (o[int(np.argmin(e))] + f / 2.0) / SR      # argmin берёт первое из равных, как строгое «<» в Java


def recognizer():
    import sherpa_onnx
    m = lambda n: os.path.join(MODEL, n)  # noqa: E731
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=m("encoder.int8.onnx"), decoder=m("decoder.int8.onnx"), joiner=m("joiner.int8.onnx"),
        tokens=m("tokens.txt"), num_threads=4, sample_rate=16000, feature_dim=80,
        decoding_method="greedy_search", model_type="nemo_transducer")


def recognize(rec, x):
    st = rec.create_stream()
    st.accept_waveform(SR, np.ascontiguousarray(x, np.float32))
    rec.decode_stream(st)
    r = st.result
    return r.text.strip(), list(r.tokens), list(r.timestamps), list(getattr(r, "durations", None) or [])


def wordy(t):
    return any(ch.isalnum() for ch in t)


def boundaries(toks):
    """[(i, j)]: i — последний токен со знаком конца предложения, j — первый токен со словом после него."""
    out, i = [], None
    for k, t in enumerate(toks):
        if wordy(t) and i is not None:
            out.append((i, k))
            i = None
        s = t.strip()
        if s and s[-1] in ENDS:
            i = k
    return out


def align(ref, hyp):
    """Для каждого распознанного слова — номер слова эталона (совпало или заменено) либо None (вставка)."""
    n, m = len(ref), len(hyp)
    D = np.zeros((n + 1, m + 1), int)
    D[:, 0], D[0, :] = np.arange(n + 1), np.arange(m + 1)
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            D[i, j] = min(D[i - 1, j] + 1, D[i, j - 1] + 1, D[i - 1, j - 1] + (ref[i - 1] != hyp[j - 1]))
    out, i, j = [None] * m, n, m
    while i > 0 and j > 0:
        if D[i, j] == D[i - 1, j - 1] + (ref[i - 1] != hyp[j - 1]):
            out[j - 1] = i - 1
            i, j = i - 1, j - 1
        elif D[i, j] == D[i, j - 1] + 1:
            j -= 1
        else:
            i -= 1
    return out


def measure(rec, x, ref_a, ref_b, a0, g0, g1, b1):
    """Распознать сегмент, найти границу на стыке, разрезать и сравнить с паузой [g0, g1]."""
    text, toks, ts, du = recognize(rec, x)
    ra, rb = norm(ref_a), norm(ref_b)
    hyp = norm("".join(toks))
    al = align(ra + rb, hyp)
    bs = boundaries(toks)
    junction, extra = None, 0
    for i, j in bs:
        k = len(norm("".join(toks[:i + 1])))
        left = [r for r in al[:k] if r is not None]
        right = [r for r in al[k:] if r is not None]
        if junction is None and left and right and max(left) < len(ra) and min(right) >= len(ra):
            junction = (i, j)
        else:
            extra += 1
    # Предложение потеряно целиком: с ним выровнено меньше половины его слов — делить нечего.
    on_a = sum(r is not None and r < len(ra) for r in al)
    on_b = sum(r is not None and r >= len(ra) for r in al)
    lost = on_a < len(ra) / 2 or on_b < len(rb) / 2
    out = {"text": text, "ref": ref_a + " | " + ref_b, "bounds": len(bs), "extra": extra, "lost": bool(lost),
           "junction": junction is not None, "gap": [round(g0, 3), round(g1, 3)], "dur": round(len(x) / SR, 2)}
    words = [k for k, t in enumerate(toks) if wordy(t)]
    if words:
        out["d_first"] = round(ts[words[0]] - a0, 3)
        last = words[-1]
        out["d_last"] = round(ts[last] + (du[last] if du else 0) - b1, 3)
    if junction is None:
        return out
    i, j = junction
    end1 = ts[i] + du[i] if du else (ts[i + 1] if i + 1 < len(ts) else ts[i])
    w1 = max((k for k in words if k <= i), default=i)
    endw = ts[w1] + du[w1] if du else ts[w1 + 1]
    raw = (end1 + ts[j]) / 2
    cut = snap(x, raw)
    mid = (g0 + g1) / 2
    out.update({
        "raw": round(raw, 3), "cut": round(cut, 3),
        "in_gap": bool(g0 - MARGIN <= cut <= g1 + MARGIN), "in_gap_raw": bool(g0 - MARGIN <= raw <= g1 + MARGIN),
        "err": round(abs(cut - mid), 3), "err_raw": round(abs(raw - mid), 3),
        "d_end": round(end1 - g0, 3), "d_endw": round(endw - g0, 3), "d_start": round(ts[j] - g1, 3)})
    return out


# ---------------- корпус ----------------

def corpus_pairs(rng):
    """120 пар (A, B, пауза, один_диктор), по 40 на паузу, через одну — один диктор и разные."""
    names = sorted(f[:-4] for f in os.listdir(CORPUS) if f.endswith(".wav"))
    clips, bounds = {}, {}
    for n in names:
        x = samples(os.path.join(CORPUS, n + ".wav"))
        a, b, edge = speech(x)
        if not edge:
            clips[n], bounds[n] = x, (a, b)
    ok = sorted(clips)
    spk = speakers("pt")
    if not spk:
        print("внимание: нет data/tatoeba/raw/por_sentences_with_audio.tsv.bz2 — дикторы неизвестны, пары будут другими")
    who = {n: spk.get(n, n) for n in ok}               # диктор неизвестен — считаем каждого отдельным
    by = {}
    for n in ok:
        by.setdefault(who[n], []).append(n)
    groups = sorted(s for s in by if len(by[s]) >= 2)
    weight = np.array([len(by[s]) for s in groups], float)
    used, pairs = set(), []
    for p in PAUSES:
        for q in range(PER_PAUSE):
            same = q % 2 == 0 and bool(groups)
            while True:
                if same:
                    s = groups[rng.choice(len(groups), p=weight / weight.sum())]
                    a, b = (str(v) for v in rng.choice(by[s], 2, replace=False))
                else:
                    a = str(rng.choice(ok))
                    b = str(rng.choice([n for n in ok if who[n] != who[a]]))
                if (a, b) not in used:
                    break
            used.add((a, b))
            pairs.append((a, b, p, same))
    return clips, bounds, pairs, len(names) - len(ok)


def ref(name):
    return open(os.path.join(CORPUS, name + ".txt"), encoding="utf-8").read().strip()


def run_corpus(rec, rng):
    clips, bounds, pairs, dropped = corpus_pairs(rng)
    rows = []
    for a, b, p, same in pairs:
        (a0, a1), (b0, b1) = bounds[a], bounds[b]
        ia, ib = int(round(a1 * SR)), int(round(b0 * SR))
        fill = rng.normal(0.0, 10 ** (FILL_DB / 20), int(round(p * SR))).astype(np.float32)
        x = np.concatenate([clips[a][:ia], fill, clips[b][ib:]])
        g0, g1 = ia / SR, (ia + len(fill)) / SR
        r = measure(rec, x, ref(a), ref(b), a0, g0, g1, g1 + (b1 - b0))
        r.update({"set": "корпус %.2f" % p, "pause": p, "a": a, "b": b, "same": same})
        rows.append(r)
    return rows, dropped


# ---------------- комната ----------------

def bandpass(x):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    X[(f < 300) | (f > 4000)] = 0
    return np.fft.irfft(X, len(x))


def locate(room, clip, start, span):
    """Где в записи комнаты начинается запись корпуса: секунда начала в окне [start, start+span] и корреляция."""
    s0 = max(0, int(start * SR))
    a, b = bandpass(room[s0:s0 + int(span * SR) + len(clip)]), bandpass(clip)
    if len(a) < len(b):
        return start, 0.0
    n = 1 << int(np.ceil(np.log2(len(a) + len(b))))
    cc = np.fft.irfft(np.fft.rfft(a, n) * np.conj(np.fft.rfft(b, n)), n)[:len(a) - len(b) + 1]
    c = np.concatenate([[0.0], np.cumsum(a ** 2)])
    ncc = cc / (np.sqrt(c[len(b):] - c[:-len(b)] + 1e-12) * np.linalg.norm(b) + 1e-12)
    k = int(np.argmax(ncc))
    return (s0 + k) / SR, float(ncc[k])


def room_phrases(name):
    """Фразы записи: имя, речь [начало, конец] в секундах записи; задержка звука к журналу и её разброс."""
    room, plays, _ = load_rec(name)
    clips = {n: samples(os.path.join(CORPUS, n)) for n, _, _ in plays}
    wide = [locate(room, clips[n], at - 1.5, 3.0)[0] - at for n, at, _ in plays]
    lag0 = float(np.median(wide))
    out, lags, nccs = [], [], []
    stop = MELODY.get(name, len(room) / SR)
    for n, at, dur in plays:
        t, c = locate(room, clips[n], at + lag0 - 0.12, 0.24)
        a, b, _ = speech(clips[n])
        if t + dur > stop:
            continue
        lags.append(t - at)
        nccs.append(c)
        out.append((n[:-4], t + a, t + b))
    # Проверка переноса: начало речи по энергии самой комнаты (фон + 10 дБ, кадры 10 мс) против перенесённого
    # начала с запасом 2 кадра — при верном переносе выходит около +40 мс.
    onset = []
    for _, sa, _ in out:
        i0 = int((sa - 0.6) * SR)
        y = room[i0:i0 + int(1.2 * SR)]
        d = 10 * np.log10(np.mean(y[:len(y) // 160 * 160].reshape(-1, 160).astype(np.float64) ** 2, axis=1) + 1e-12)
        up = np.flatnonzero(d > np.median(d[:30]) + 10)
        if len(up):
            onset.append(i0 / SR + up[0] * 0.01 - sa)
    lags = np.array(lags) - lag0
    return room, out, {"lag_ms": round(1000 * lag0), "lag_p5_p95_ms": [round(1000 * v) for v in np.percentile(lags, [5, 95])],
                       "ncc_min": round(min(nccs), 2), "phrases": len(out), "onset_ms": ms(onset, 50)}


def run_room(rec, name):
    room, ph, info = room_phrases(name)
    rows = []
    for k in range(0, len(ph) - 1, 2):
        (na, sa, ea), (nb, sb, eb) = ph[k], ph[k + 1]
        i0, ia, ib, i1 = (int(round(v * SR)) for v in (sa - EDGE, ea, sb, eb + EDGE))
        a0 = (int(round(sa * SR)) - i0) / SR
        r = measure(rec, room[i0:i1], ref(na), ref(nb), a0, (ia - i0) / SR, (ib - i0) / SR, (i1 - i0) / SR - EDGE)
        r.update({"set": name, "pause": round((ib - ia) / SR, 2), "a": na, "b": nb})
        rows.append(r)
        for p in PAUSES:                                # сверх критериев: та же пара, тишина комнаты вырезана до p
            h = int(round(p / 2 * SR))
            c = XFADE // 2
            if ib - ia < 2 * h + XFADE:                 # настоящая пауза и так короче — склеивать нечего
                continue
            p1, p2 = room[i0:ia + h + c].astype(np.float64), room[ib - h - c:i1].astype(np.float64)
            th = np.linspace(0, np.pi / 2, XFADE)
            x = np.concatenate([p1[:-XFADE], p1[-XFADE:] * np.cos(th) + p2[:XFADE] * np.sin(th), p2[XFADE:]]).astype(np.float32)
            g0 = (ia - i0) / SR
            r = measure(rec, x, ref(na), ref(nb), a0, g0, g0 + 2 * h / SR, g0 + 2 * h / SR + (eb - sb))
            r.update({"set": "%s склейка %.2f" % (name, p), "pause": p, "a": na, "b": nb})
            rows.append(r)
    info["gap_s"] = [round(float(np.percentile([r["pause"] for r in rows if r["set"] == name], q)), 2) for q in (0, 50, 100)]
    return rows, info


# ---------------- счёт ----------------

def pct(k, n):
    return round(100.0 * k / n, 1) if n else None


def ms(v, q):
    return round(1000 * float(np.percentile(v, q))) if len(v) else None


def summary(rows):
    n = len(rows)
    sp = [r for r in rows if r["junction"]]
    s = {"n": n, "junction": len(sp), "junction_pct": pct(len(sp), n),
         "no_bounds_pct": pct(sum(r["bounds"] == 0 for r in rows), n),
         "lost_pct": pct(sum(r["lost"] for r in rows), n),
         "elsewhere_pct": pct(sum(r["bounds"] > 0 and not r["junction"] for r in rows), n),
         "extra_pct": pct(sum(r["extra"] > 0 for r in rows), n),
         "in_gap_pct": pct(sum(r["in_gap"] for r in sp), len(sp)),
         "in_gap_all_pct": pct(sum(r["in_gap"] for r in sp), n),
         "in_gap_raw_pct": pct(sum(r["in_gap_raw"] for r in sp), len(sp))}
    for k in ("err", "err_raw"):
        v = [r[k] for r in sp]
        s[k + "_med_ms"], s[k + "_p90_ms"] = ms(v, 50), ms(v, 90)
    for k in ("d_end", "d_endw", "d_start"):
        s[k + "_med_ms"] = ms([r[k] for r in sp], 50)
    for k in ("d_first", "d_last"):
        s[k + "_med_ms"] = ms([r[k] for r in rows if k in r], 50)
    return s


def line(name, s):
    f = lambda v: "—" if v is None else str(v)  # noqa: E731
    return ("%-24s %4d %6s %5s %5s %6s %6s %7s %7s %10s %10s %7s %7s %7s %7s %7s" % (
        name, s["n"], f(s["junction_pct"]), f(s["no_bounds_pct"]), f(s["lost_pct"]), f(s["elsewhere_pct"]), f(s["extra_pct"]),
        f(s["in_gap_pct"]), f(s["in_gap_raw_pct"]),
        "%s/%s" % (f(s["err_med_ms"]), f(s["err_p90_ms"])), "%s/%s" % (f(s["err_raw_med_ms"]), f(s["err_raw_p90_ms"])),
        f(s["d_end_med_ms"]), f(s["d_endw_med_ms"]), f(s["d_start_med_ms"]), f(s["d_first_med_ms"]), f(s["d_last_med_ms"])))


def main():
    ap = argparse.ArgumentParser(description="Метки времени parakeet: разрез реплики по предложениям")
    ap.add_argument("--seed", type=int, default=20261003)
    ap.add_argument("--out", help="JSON с каждым сегментом")
    ap.add_argument("--show", type=int, default=0, help="показать столько худших сегментов каждого набора")
    a = ap.parse_args()
    t0 = time.time()
    rng = np.random.default_rng(a.seed)
    rec = recognizer()
    rows, dropped = run_corpus(rec, rng)
    print("корпус: %d сегментов за %.0f с (записей с речью у края, не взяты: %d)" % (len(rows), time.time() - t0, dropped))
    infos = {}
    for name in ROOMS:
        rr, infos[name] = run_room(rec, name)
        rows += rr
        i = infos[name]
        print("%s: %d фраз до мелодии, звук позже журнала на %d мс (p5…p95 %+d…%+d мс), корреляция не ниже %.2f;"
              " начало речи по энергии комнаты %+d мс от перенесённого; пауза %s…%s с (медиана %s); %.0f с"
              % (name, i["phrases"], i["lag_ms"], *i["lag_p5_p95_ms"], i["ncc_min"], i["onset_ms"],
                 i["gap_s"][0], i["gap_s"][2], i["gap_s"][1], time.time() - t0))

    sets = []
    for p in PAUSES:
        sets.append(("корпус %.2f" % p, [r for r in rows if r["set"] == "корпус %.2f" % p]))
    corpus = [r for r in rows if r["set"].startswith("корпус")]
    sets.append(("корпус: один диктор", [r for r in corpus if r["same"]]))
    sets.append(("корпус: разные", [r for r in corpus if not r["same"]]))
    sets.append(("корпус всего", corpus))
    for name in ROOMS:
        sets.append((name, [r for r in rows if r["set"] == name]))
    for name in ROOMS:
        for p in PAUSES:
            sets.append(("%s склейка %.2f" % (name, p), [r for r in rows if r["set"] == "%s склейка %.2f" % (name, p)]))
    S = {k: summary(v) for k, v in sets}

    print()
    print("стык — знак конца предложения на стыке, %; нет — знаков нет вовсе; потер — одно предложение не распознано")
    print("вовсе (меньше половины слов); мимо — знаки есть, но не на стыке;")
    print("лишн — сегменты со знаком внутри предложения; в паузе — разрез в паузе ±50 мс среди поделившихся, %;")
    print("|Δ| — |разрез − середина паузы|, мс (медиана/p90); «сырой» — без притяжения к тишине;")
    print("сдвиги, мс (медианы): конец1 — конец знака − конец речи 1; слово1 — конец последнего слова − конец речи 1;")
    print("начало2 — первый токен 2-го − начало речи 2; первый — первый токен − начало речи 1; послед — конец последнего − конец речи 2")
    print("%-24s %4s %6s %5s %5s %6s %6s %7s %7s %10s %10s %7s %7s %7s %7s %7s" % (
        "набор", "n", "стык", "нет", "потер", "мимо", "лишн", "в паузе", "сырой", "|Δ|", "|Δ| сыр", "конец1", "слово1", "начало2", "первый", "послед"))
    for k, _ in sets:
        print(line(k, S[k]))

    c, near = S["корпус всего"], S["near-pt"]
    k1 = c["junction_pct"] is not None and c["junction_pct"] >= 90
    k2c = c["in_gap_pct"] is not None and c["in_gap_pct"] >= 90
    k2r = near["in_gap_pct"] is not None and near["in_gap_pct"] >= 80
    print()
    print("критерий 1 (корпус, знак на стыке ≥ 90 %%): %s %% — %s" % (c["junction_pct"], "прошёл" if k1 else "НЕ прошёл"))
    print("критерий 2 (корпус, разрез в паузе ≥ 90 %% поделившихся): %s %% — %s" % (c["in_gap_pct"], "прошёл" if k2c else "НЕ прошёл"))
    print("критерий 2 (near-pt, разрез в паузе ≥ 80 %%): %s %% поделившихся, %s %% всех — %s"
          % (near["in_gap_pct"], near["in_gap_all_pct"], "прошёл" if k2r else "НЕ прошёл"))
    print("итог: %s" % ("предложение режется по меткам" if k1 and k2c and k2r else "▶ играет реплику целиком"))
    print("время: %.0f с" % (time.time() - t0))

    if a.show:
        for k, v in sets:
            if "склейка" in k or k.startswith("корпус:") or k == "корпус всего":
                continue
            bad = sorted(v, key=lambda r: (r["junction"], r.get("in_gap", False), -r.get("err", 0)))[:a.show]
            print("\n== %s: худшие" % k)
            for r in bad:
                print("  %s+%s пауза %s..%s разрез %s (сырой %s) стык=%s лишн=%d\n     эталон: %s\n     слышно: %s" % (
                    r["a"], r["b"], r["gap"][0], r["gap"][1], r.get("cut"), r.get("raw"), r["junction"], r["extra"],
                    r["ref"], r["text"]))
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump({"seed": a.seed, "summary": S, "rooms": infos, "rows": rows}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
