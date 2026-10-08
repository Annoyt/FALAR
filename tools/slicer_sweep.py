#!/usr/bin/env python3
"""Минимум речи нарезки: перебор на компьютере, одно ядро.

Минимум речи применялся дважды: silero (Engine.buildVad, min_speech_duration 0,25 с — признак «речь» появляется
только после 0,25 с речи подряд) и нарезка (TranslatorService.startVad, minSpeechMs 250 — ещё столько речи после
срабатывания). Вместе около полусекунды: однословный ответ («Sim», «Да», «Пять») куска не давал. Здесь перебираются
минимум silero × минимум нарезки (с передачей тишины конца куска в подпор следующего, carry) на записях комнаты —
WER, потерянные, разрезанные и куски вне фраз — и на однословных ответах: голосами Piper и словами, вырезанными из
фраз корпуса (tools/short_reply_eval). Вариант adapt — то, что стало в приложении: в тишине silero 0,064 с и нарезка
150 мс, при включённом шумодаве (DenoiseGate) 0,25 с и 250 мс. Итоги кэшируются по (запись, вариант).

  taskset -c N .venv/bin/python tools/slicer_sweep.py [--recs near-pt,far-pt,noisy-pt,near-ru,nearbg40-pt,nearbg40-ru] [--variants ...]
"""
import argparse
import json
import os
import sys
import time

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
import vad_denoise_eval as ev  # noqa: E402
import short_reply_eval as sre  # noqa: E402
from wer2 import norm  # noqa: E402

SR = 16000
VARIANTS = [('base', dict(vad_min=0.25, minsp=250, carry=False))]
VARIANTS += [(f's{int(s * 1000)}_m{m}', dict(vad_min=s, minsp=m, carry=True)) for s in (0.25, 0.064, 0.032) for m in (250, 150, 100)]
VARIANTS += [('adapt', dict(vad_min=0.064, vad_min_noise=0.25, minsp=150, minsp_noise=250, carry=True))]
WORDS = {'pt': ['Sim.', 'Não.', 'Tá.', 'Oi.', 'Claro.', 'Obrigado.', 'Cinco.', 'Vinte.'], 'ru': ['Да.', 'Нет.', 'Ага.', 'Хорошо.', 'Спасибо.', 'Пять.']}

TTS = {}


def tts(lang, text, speed):
    so = ev.so
    if lang not in TTS:
        d = os.path.join(R, 'models', f'tts_{lang}'); model = 'pt_BR-faber-medium.onnx' if lang == 'pt' else 'ru_RU-dmitri-medium.onnx'
        TTS[lang] = so.OfflineTts(so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(vits=so.OfflineTtsVitsModelConfig(
            model=f'{d}/{model}', tokens=f'{d}/tokens.txt', data_dir=f'{d}/espeak-ng-data'), num_threads=1)))
    a = TTS[lang].generate(text, sid=0, speed=speed); x = np.array(a.samples, np.float32); sr = a.sample_rate
    if sr != SR: x = np.interp(np.arange(0, len(x), sr / SR), np.arange(len(x)), x).astype(np.float32)
    e = np.sqrt(np.convolve(x.astype(np.float64) ** 2, np.ones(160) / 160, 'same')); k = np.where(e > e.max() * 10 ** (-40 / 20))[0]
    x = x[k[0]:k[-1] + 1]
    return (x / (np.sqrt(np.mean(x ** 2)) + 1e-12) * 10 ** (-22 / 20)).astype(np.float32)


def clips_tts(room):
    out = []
    for lang, ws in WORDS.items():
        for w in ws:
            for speed in (1.0, 1.3):
                y = tts(lang, w, speed); k = len(out); off = (k * 7919) % (len(room) - 4 * SR)
                out.append((w, np.concatenate([room[off:off + 3 * SR // 2], y, room[off + 3 * SR // 2:off + 3 * SR]]), 3 * SR // 2, 3 * SR // 2 + len(y)))
    return out


def clips_cut(asr, room, per_lang):
    out = []
    picked = sre.pick_words(asr, per_lang, 0.2, 0.45)
    for lang in ('pt', 'ru'):
        for k, (word, x, wa, wb, fn) in enumerate(picked[lang]):
            clip, c0, c1 = sre.clip_of(x, wa, wb, room, k)
            out.append((word, clip, c0, c1))
    return out


def slice_app(x, kw):
    """Нарезка приложения по умолчанию (auto3) с параметрами варианта."""
    return ev.segment(x, 'dn_sil', 'gtcrn', 0.0, -48.0, True, ev.Wiener(), **kw)


def replies(clips, asr, kw):
    got = ok = 0
    for w, clip, c0, c1 in clips:
        segs, _ = slice_app(clip, kw)
        hit = [s for s in segs if s[0] <= c1 and s[1] >= c0]
        if not hit: continue
        got += 1; h = asr.run(clip[hit[0][0]:hit[0][1]]).text.strip()
        ok += bool(norm(h)) and norm(h)[0] == norm(w)[0]
    return got, ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--recs', default='near-pt,far-pt,noisy-pt,near-ru,nearbg40-pt,nearbg40-ru')
    ap.add_argument('--variants', default='', help='какие варианты (через запятую); пусто — все')
    ap.add_argument('--out', default=os.path.join(R, 'results', 'asr_filters', 'sweep'))
    a = ap.parse_args(); os.makedirs(a.out, exist_ok=True)
    want = [v for v in a.variants.split(',') if v]
    variants = [(n, kw) for n, kw in VARIANTS if not want or n in want]
    asr = sre.Asr(); room = ev.room_noise('near-pt').astype(np.float32)
    t0 = time.time(); ctts = clips_tts(room); ccut = clips_cut(asr, room, 20)
    print(f'ответы: Piper {len(ctts)}, вырезанные слова {len(ccut)} ({time.time() - t0:.0f} с)', flush=True)
    table = {}
    for name, kw in variants:
        p = os.path.join(a.out, f'replies_{name}.json')
        if os.path.exists(p): table[name] = json.load(open(p))
        else:
            g1, o1 = replies(ctts, asr, kw); g2, o2 = replies(ccut, asr, kw)
            table[name] = {'tts': [g1, o1, len(ctts)], 'cut': [g2, o2, len(ccut)]}; json.dump(table[name], open(p, 'w'))
        print(f'{name:10s} ответы Piper: кусок {table[name]["tts"][0]} из {table[name]["tts"][2]}, узнано {table[name]["tts"][1]}; '
              f'вырезанные: кусок {table[name]["cut"][0]} из {table[name]["cut"][2]}, узнано {table[name]["cut"][1]}', flush=True)
    recs = [r for r in a.recs.split(',') if r]
    for rec in recs:
        x, plays, gap = ev.load_rec(rec); lang = 'ru' if rec.endswith('-ru') else 'pt'
        for name, kw in variants:
            p = os.path.join(a.out, f'{rec}_{name}.json')
            if os.path.exists(p): sc = json.load(open(p))
            else:
                t1 = time.time()
                segs, stat = slice_app(x, kw)
                sc = ev.score(segs, [asr.run(x[s0:s1]).text.strip() for s0, s1, _ in segs], plays, gap, lang); sc['sec'] = round(time.time() - t1)
                json.dump(sc, open(p, 'w'))
            table.setdefault(rec, {})[name] = sc
            print(f'{rec:12s} {name:10s} WER {sc["wer"]:5.1f} потеряно {sc["lost"]:2d} кусков {sc["segments"]:3d} вне окон {sc["outside"]:2d} разрезано {sc["split"]:2d}', flush=True)
    print('\n| вариант | ' + ' | '.join(recs) + ' | Piper | слова |'); print('|---|' + '---|' * (len(recs) + 2))
    for name, _ in variants:
        cells = [f'{table[r][name]["wer"]:.1f} / {table[r][name]["lost"]} / {table[r][name]["split"]} / {table[r][name]["outside"]}' for r in recs]
        print(f'| {name} | ' + ' | '.join(cells) + f' | {table[name]["tts"][0]} ({table[name]["tts"][1]}) | {table[name]["cut"][0]} ({table[name]["cut"][1]}) |')


if __name__ == '__main__':
    main()
