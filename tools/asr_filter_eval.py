#!/usr/bin/env python3
"""Фильтры перед распознаванием: замер на компьютере, одно ядро (владелец 03.10: сначала ПК, потом телефон).

Вопрос владельца 08.10: можно ли ещё немного улучшить распознавание входящего звука фильтрами. Нарезка здесь —
та, что в приложении по умолчанию (tools/vad_denoise_eval.segment: шумодав при шуме, фильтр Винера для
детектора в тишине, распознавание по исходному куску), и она одна на все варианты: куски режутся один раз и
кэшируются, а фильтр применяется только к звуку куска перед parakeet. Так виден эффект фильтра на само
распознавание, без эффекта на нарезку. Счёт — как в vad_denoise_eval (WER по всем фразам, потерянные — целиком).

Два режима:
  записи комнаты (bench/air/rec):   .venv/bin/python tools/asr_filter_eval.py near-pt noisy-pt far-pt near-ru nearbg40-pt
      [--filters none,dc,hp60,...] [--out results/asr_filters]
  корпус (bench/air/corpus, чистые файлы): --corpus — проверка «не вредит» на чистой речи и опыт для кнопки удержания:
      у удержания нет подпора начала, и первые слова идут без звука комнаты перед ними; здесь у файлов отрезается
      тишина в начале (и в конце), а перед ними подставляются разные варианты «подпора».

Запуск — одним ядром: taskset -c N и один поток у parakeet. Итоги дописываются по одному файлу на (запись, фильтр),
повторный запуск продолжает с места обрыва (ПК с андервольтом, см. память проекта).
"""
import argparse
import glob
import json
import os
import sys
import time
import wave

import numpy as np
from scipy.signal import butter, sosfilt

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
import vad_denoise_eval as vde  # noqa: E402
from wer2 import wer  # noqa: E402

SR = 16000
FRAME = 512


# ---- фильтры: звук куска → звук для распознавания --------------------------------------------------------------
def speech_floor(x):
    """Gain.speechFloor: фон — 2 % самых тихих кадров по 32 мс, речь — средняя мощность кадров громче фона на 10 дБ.
    Возвращает (речь dBFS, фон dBFS) или (nan, nan)."""
    n = len(x) // FRAME
    if n == 0:
        return float('nan'), float('nan')
    p = np.mean(x[:n * FRAME].astype(np.float64).reshape(n, FRAME) ** 2, axis=1)
    live = p[p >= 1e-10]
    if len(live) == 0:
        return float('nan'), float('nan')
    floor = np.sort(live)[int((len(live) - 1) * 0.02)]
    sp = p[p >= floor * 10]
    return (10 * np.log10(max(1e-18, sp.mean())) if len(sp) else float('nan')), 10 * np.log10(max(1e-18, floor))


def limiter(x, db):
    """Gain.apply с ограничителем: усиление db, потолок 0,9, возврат 150 мс."""
    if db == 0:
        return x
    g = 10 ** (db / 20); rel = 1 - np.exp(-1000.0 / (150 * SR)); red = 1.0
    y = np.empty_like(x)
    for k in range(len(x)):
        v = x[k] * g; a = abs(v)
        if a * red > 0.9:
            red = 0.9 / a
        y[k] = v * red
        red += (1 - red) * rel
    return y.astype(np.float32)


def norm_to(target):
    """Речь куска — к target dBFS (как авто-чувствительность приложения, но ровно по этому куску), в −12…+24 дБ."""
    def f(x):
        sp, _ = speech_floor(x)
        if np.isnan(sp):
            return x
        return limiter(x, float(np.clip(target - sp, -12, 24)))
    return f


def sos_filter(kind, fc, order):
    sos = butter(order, fc, kind, fs=SR, output='sos')
    return lambda x: sosfilt(sos, x.astype(np.float64)).astype(np.float32)


def preemph(x, a=0.97):
    y = x.astype(np.float64).copy(); y[1:] -= a * y[:-1]
    return y.astype(np.float32)


def wiener_asr(gmin):
    """Фильтр Винера из нарезки, но перед распознаванием, с мягким нижним пределом усиления; состояние — с нуля на
    каждый кусок (шум оценивается по подпору начала, он в куске есть)."""
    def f(x):
        w = vde.Wiener(); w.GMIN = gmin
        n = len(x) // w.H * w.H
        y = w.run(np.ascontiguousarray(x[:n]), SR).samples
        return np.concatenate([y[w.H:], x[n - w.H:]]).astype(np.float32)   # задержка 256 — сдвигаем назад, хвост исходный
    return f


FILTERS = {
    'none': lambda x: x,
    'dc': lambda x: (x - x.mean()).astype(np.float32),
    'hp60': sos_filter('highpass', 60, 2),
    'hp100': sos_filter('highpass', 100, 2),
    'hp150': sos_filter('highpass', 150, 2),
    'lp5k': sos_filter('lowpass', 5000, 4),
    'lp6k': sos_filter('lowpass', 6000, 4),
    'norm24': norm_to(-24),
    'norm18': norm_to(-18),
    'preemph': preemph,
    'wiener10': wiener_asr(10 ** (-10 / 20)),
    'wiener6': wiener_asr(10 ** (-6 / 20)),
    'wiener20': wiener_asr(0.1),
}


class Asr:
    def __init__(self, threads=1):
        m = os.path.join(R, 'models', 'asr_multi')
        self.rec = vde.so.OfflineRecognizer.from_transducer(
            encoder=f'{m}/encoder.int8.onnx', decoder=f'{m}/decoder.int8.onnx', joiner=f'{m}/joiner.int8.onnx',
            tokens=f'{m}/tokens.txt', num_threads=threads, model_type='nemo_transducer', decoding_method='greedy_search')

    def __call__(self, x):
        s = self.rec.create_stream()
        s.accept_waveform(SR, np.ascontiguousarray(x, np.float32))
        self.rec.decode_stream(s)
        return s.result.text.strip()


def segments_for(name, out):
    """Куски нарезки приложения по умолчанию (auto3: шумодав с −48 dBFS, музыка, Винер в тишине), с кэшем."""
    cache = os.path.join(out, f'segs_{name}.json')
    x, plays, gap = vde.load_rec(name)
    if os.path.exists(cache):
        d = json.load(open(cache)); return x, plays, gap, [tuple(s) for s in d['segs']]
    t0 = time.time()
    segs, stat = vde.segment(x, 'dn_sil', 'gtcrn', 0.0, -48.0, True, vde.Wiener())
    json.dump({'segs': segs, 'stat': stat, 'sec': round(time.time() - t0, 1)}, open(cache, 'w'))
    print(f'  {name}: кусков {len(segs)}, нарезка {time.time() - t0:.0f} с, {stat}', flush=True)
    return x, plays, gap, segs


def run_rec(name, filters, asr, out):
    x, plays, gap, segs = segments_for(name, out)
    lang = 'ru' if name.endswith('-ru') else 'pt'
    rows = {}
    for fn in filters:
        path = os.path.join(out, f'{name}_{fn}.json')
        if os.path.exists(path):
            rows[fn] = json.load(open(path)); continue
        f = FILTERS[fn]; texts = []; t0 = time.time(); audio = 0.0
        for a, b, _ in segs:
            seg = x[a:b]; audio += (b - a) / SR
            texts.append(asr(f(seg)))
        sc = vde.score(segs, texts, plays, gap, lang)
        sc.update(filter=fn, rec=name, sec=round(time.time() - t0, 1), rtf=round((time.time() - t0) / max(1e-9, audio), 3), texts=texts)
        json.dump(sc, open(path, 'w'), ensure_ascii=False, indent=1)
        print(f'  {name} {fn:9s} WER {sc["wer"]:5.1f}  потеряно {sc["lost"]:2d}  чисто {sc["clean"]:5.1f} %  WER чистых {sc["wer_clean"]:5.1f}  '
              f'({sc["sec"]:.0f} с, RTF {sc["rtf"]})', flush=True)
        rows[fn] = sc
    return rows


# ---- корпус: чистые файлы и опыт для удержания ------------------------------------------------------------------
def corpus_files():
    for lang in ('pt', 'ru'):
        for w in sorted(glob.glob(os.path.join(R, 'bench', 'air', 'corpus', lang, '*.wav'))):
            yield lang, w


def read_wav(path):
    with wave.open(path) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float32) / 32768


def extent(x):
    """Начало и конец речи, отсчётами: кадры громче фона (2 % самых тихих) на 10 дБ."""
    n = len(x) // FRAME
    p = np.mean(x[:n * FRAME].astype(np.float64).reshape(n, FRAME) ** 2, axis=1) + 1e-12
    fl = np.sort(p)[int(0.02 * (n - 1))]
    sp = np.where(p >= fl * 10)[0]
    if len(sp) == 0:
        return 0, len(x)
    return int(sp[0] * FRAME), int(min(len(x), (sp[-1] + 1) * FRAME))


def own_floor(x, sec):
    """«Свой фон»: самые тихие 4 кадра файла по кругу, с перекрытием 64 отсчёта, на sec секунд."""
    n = len(x) // FRAME
    p = np.mean(x[:n * FRAME].astype(np.float64).reshape(n, FRAME) ** 2, axis=1)
    k = int(np.argmin(np.convolve(p, np.ones(4) / 4, 'valid')))
    q = x[k * FRAME:(k + 4) * FRAME]
    need = int(sec * SR); out = np.zeros(need + len(q), np.float32); pos = 0; ov = 64
    ramp = np.linspace(0, 1, ov, dtype=np.float32)
    while pos < need:
        piece = q.copy()
        if pos > 0:
            piece[:ov] *= ramp; out[pos:pos + ov] *= 1 - ramp
        out[pos:pos + len(piece)] += piece; pos += len(piece) - ov
    return out[:need]


def quietest(x, sec):
    """Самый тихий отрезок sec секунд подряд (по кадрам 32 мс), края по 8 мс."""
    n = len(x) // FRAME; k = max(1, int(sec * SR) // FRAME)
    p = np.mean(x[:n * FRAME].astype(np.float64).reshape(n, FRAME) ** 2, axis=1)
    i = int(np.argmin(np.convolve(p, np.ones(k) / k, 'valid'))) if n >= k else 0
    y = x[i * FRAME:(i + k) * FRAME].copy(); f = int(0.008 * SR)
    if len(y) > 2 * f: y[:f] *= np.linspace(0, 1, f, dtype=np.float32); y[-f:] *= np.linspace(1, 0, f, dtype=np.float32)
    return y


def corpus_variants(x, room):
    """Что подаётся распознавателю вместо файла целиком."""
    a, b = extent(x)
    lead = x[a:]                                  # без тишины в начале
    both = x[a:b]                                 # без тишины в начале и в конце
    zeros = np.zeros(SR, np.float32)
    rng = np.random.default_rng(0)
    dither = (rng.standard_normal(SR) * 10 ** (-70 / 20)).astype(np.float32)

    def colored(db, slope):
        """Шум с наклоном спектра slope (1 — розовый, 2 — коричневый) уровнем db dBFS, 1 с: подпор без записи комнаты."""
        n = SR; s = np.fft.rfft(rng.standard_normal(n)); f = np.arange(len(s)); f[0] = 1
        y = np.fft.irfft(s / f ** (slope / 2.0), n); y *= 10 ** (db / 20) / np.sqrt(np.mean(y ** 2))
        return y.astype(np.float32)

    _, fl = speech_floor(x)
    room_own = (room[:SR] * 10 ** ((fl - (-50.5)) / 20)).astype(np.float32) if not np.isnan(fl) else room[:SR]   # фон комнаты на уровне своего фона файла
    ownc = quietest(x, 0.3)                                     # самые тихие 300 мс файла подряд — то, что есть у удержания
    return {
        'lead0_ownc300': np.concatenate([ownc, lead]),
        'lead0_ownc300x2': np.concatenate([ownc, ownc, lead]),
        'lead0_ownc300_pink45': np.concatenate([colored(-45, 1)[:SR * 7 // 10], ownc, lead]),
        'lead0_pink50': np.concatenate([colored(-50, 1), lead]),
        'lead0_pink45': np.concatenate([colored(-45, 1), lead]),
        'lead0_brown50': np.concatenate([colored(-50, 2), lead]),
        'lead0_white50': np.concatenate([colored(-50, 0), lead]),
        'lead0_roomown': np.concatenate([room_own, lead]),
        'lead0_room300': np.concatenate([room[:SR * 3 // 10], lead]),
        'lead0_room2s': np.concatenate([room[:SR], room[:SR], lead]),
        'asis': x,
        'lead0': lead,
        'lead0_zeros1s': np.concatenate([zeros, lead]),
        'lead0_dither1s': np.concatenate([dither, lead]),
        'lead0_room1s': np.concatenate([room[:SR], lead]),
        'lead0_own1s': np.concatenate([own_floor(x, 1.0), lead]),
        'lead0_own300': np.concatenate([own_floor(x, 0.3), lead]),
        'both0': both,
        'both0_own1s_tail300': np.concatenate([own_floor(x, 1.0), both, own_floor(x, 0.3)]),
        'both0_room1s_tail300': np.concatenate([room[:SR], both, room[SR:SR + SR * 3 // 10]]),
    }


def run_corpus(asr, out, variants=None):
    path = os.path.join(out, 'corpus.json')
    done = json.load(open(path)) if os.path.exists(path) else {}
    room = vde.room_noise('near-pt')[SR * 10:SR * 12].astype(np.float32)      # 2 с фона тихой комнаты
    t0 = time.time()
    for lang, w in corpus_files():
        key = f'{lang}/{os.path.basename(w)}'
        ref = open(w[:-4] + '.txt', encoding='utf-8').read().strip()
        x = read_wav(w); vs = corpus_variants(x, room)
        d = done.setdefault(key, {'ref': ref})
        for v, y in vs.items():
            if variants and v not in variants or v in d:
                continue
            d[v] = asr(y)
        json.dump(done, open(path, 'w'), ensure_ascii=False, indent=1)
    print(f'  корпус: {time.time() - t0:.0f} с', flush=True)
    names = list(corpus_variants(read_wav(next(corpus_files())[1]), room))
    rows = []
    for v in names:
        cells = []
        for lang in ('pt', 'ru'):
            e = n = 0
            for key, d in done.items():
                if key.startswith(lang + '/') and v in d:
                    ee, nn = wer(d['ref'], d[v]); e += ee; n += nn
            cells.append((100.0 * e / n if n else float('nan'), n))
        rows.append((v, cells))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('recs', nargs='*')
    ap.add_argument('--filters', default=','.join(FILTERS))
    ap.add_argument('--corpus', action='store_true')
    ap.add_argument('--variants', default='', help='корпус: какие варианты (через запятую), по умолчанию все')
    ap.add_argument('--threads', type=int, default=1)
    ap.add_argument('--out', default=os.path.join(R, 'results', 'asr_filters'))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    asr = Asr(a.threads)
    if a.corpus:
        rows = run_corpus(asr, a.out, [v for v in a.variants.split(',') if v])
        print('| вариант | pt WER | ru WER |'); print('|---|---|---|')
        for v, cells in rows:
            print(f'| {v} | {cells[0][0]:.1f} % ({cells[0][1]} слов) | {cells[1][0]:.1f} % ({cells[1][1]}) |')
    filters = [f for f in a.filters.split(',') if f]
    table = {}
    for name in a.recs:
        table[name] = run_rec(name, filters, asr, a.out)
    if a.recs:
        print('\n| запись | ' + ' | '.join(filters) + ' |'); print('|---|' + '---|' * len(filters))
        for name, rows in table.items():
            print(f'| {name} | ' + ' | '.join(f'{rows[f]["wer"]:.1f} / {rows[f]["lost"]}' if f in rows else '—' for f in filters) + ' |')


if __name__ == '__main__':
    main()
