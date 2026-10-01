#!/usr/bin/env python3
"""Шумодав только для нарезки: замер на записях комнаты (bench/air/rec) на компьютере.

Вопрос владельца 02.10: «voice isolate», как в DAW, — вычесть фон из речи. Перед распознаванием
очистка уже отклонена дважды (results/2026-09-12-asr-upgrade.md §4, results/2026-10-02-vad-denoise.md):
parakeet на очищенном звуке ошибается в разы чаще. Здесь проверяется другое: очищенный звук получает
только нарезка — детектор речи silero и порог по энергии, — а распознаватель слышит исходный кусок.

Нарезка — копия TranslatorService.startVad при усилении 0 дБ: окно 512 отсчётов (32 мс), речь — silero
«речь» И кадр громче фона на 6 дБ; фон копится в тишине (вниз быстро, вверх медленно); подпор 1000 мс,
хвост 300, выдержка 600, речь не короче 250 мс, кусок не длиннее 15 с. Сверка с телефоном (подагент,
01.10): near-pt — 73 куска и WER 16,2 % (на телефоне 73 и 15,7 %), far-pt — 29,4 % (30,6 %).

Шумодав — потоковый (OnlineSpeechDenoiser), как он будет работать в приложении: кадр за кадром, выход
копится и отдаётся нарезке по 512 отсчётов; пока выхода нет — тишина.

Варианты нарезки (распознавание везде по исходному звуку):
  raw     — как сейчас: silero и порог по исходному;
  dn      — silero и порог (и оценка фона) по очищенному;
  dn_sil  — silero по очищенному, порог и фон по исходному.

Счёт — как bench/air/air_wer.py в режиме replay: кусок относится к фразе, в чьё окно проигрывания
попал его конец; фраза без кусков — полный пропуск; «чисто» — фраза получила свой кусок, услышанное
не длиннее эталона в 1,6 раза.

  .venv/bin/python tools/vad_denoise_eval.py near-pt noisy-pt far-pt near-ru [--model gtcrn] [--att 0]
      [--variants raw,dn,dn_sil] [--out results/vad_denoise]

С какого фона очистка начинает помогать: --mix noisy-pt --mixdb -50,-45,-40 — к записи подмешивается фон
другой записи (её отрезки вне окон фраз, по кругу) с таким уровнем RMS, dBFS. Речь та же, меняется только фон.
"""
import argparse
import json
import os
import sys
import time
import wave
from collections import deque

import numpy as np
import sherpa_onnx as so

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
from wer2 import norm, wer  # noqa: E402

SR = 16000
FRAME = 512
MODELS = os.path.join(R, 'models')


def load_rec(name):
    """Запись, что и когда играло (имя, начало в секундах от начала записи, длительность) и пауза."""
    d = os.path.join(R, 'bench', 'air', 'rec', name)
    w = wave.open(os.path.join(d, 'room.wav'))
    assert w.getframerate() == SR and w.getsampwidth() == 2 and w.getnchannels() == 1
    x = np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float32) / 32768
    p_off = int(open(os.path.join(d, 'player.offset')).read())
    l_off = int(open(os.path.join(d, 'listener.offset')).read())
    raw = next(int(l.split('\t')[0]) - l_off for l in open(os.path.join(d, 'listener.tsv'), encoding='utf-8')
               if l.split('\t')[1:2] == ['raw_begin'])
    plays, gap = [], 3000
    for l in open(os.path.join(d, 'player.tsv'), encoding='utf-8'):
        p = l.rstrip('\n').split('\t')
        if len(p) > 5 and p[1] == 'play_begin':
            gap = int(float(p[5]))
        if len(p) > 3 and p[1] == 'play':
            plays.append((p[2], (int(p[0]) - p_off - raw) / 1000.0, float(p[3]) / 1000.0))
    return x, plays, gap


class Asr:
    """parakeet (asr_multi), как в приложении; одинаковые куски распознаются один раз."""
    def __init__(self):
        m = os.path.join(MODELS, 'asr_multi')
        self.rec = so.OfflineRecognizer.from_transducer(
            encoder=f'{m}/encoder.int8.onnx', decoder=f'{m}/decoder.int8.onnx', joiner=f'{m}/joiner.int8.onnx',
            tokens=f'{m}/tokens.txt', num_threads=4, model_type='nemo_transducer', decoding_method='greedy_search')
        self.cache = {}

    def __call__(self, x, a, b):
        if (a, b) not in self.cache:
            s = self.rec.create_stream()
            s.accept_waveform(SR, np.ascontiguousarray(x[a:b], np.float32))
            self.rec.decode_stream(s)
            self.cache[(a, b)] = s.result.text.strip()
        return self.cache[(a, b)]


def denoiser(model, att):
    if model == 'gtcrn':
        mc = so.OfflineSpeechDenoiserModelConfig(
            gtcrn=so.OfflineSpeechDenoiserGtcrnModelConfig(model=os.path.join(MODELS, 'denoiser', 'gtcrn_simple.onnx')), num_threads=1)
    else:
        mc = so.OfflineSpeechDenoiserModelConfig(
            dpdfnet=so.OfflineSpeechDenoiserDpdfNetModelConfig(model=os.path.join(MODELS, 'denoiser', model + '.onnx'),
                                                               attenuation_limit_db=att), num_threads=1)
    return so.OnlineSpeechDenoiser(so.OnlineSpeechDenoiserConfig(model=mc))


def room_noise(name, margin=0.5):
    """Фон записи без речи: отрезки вне окон проигрывания (с запасом margin с по краям), подряд."""
    x, plays, gap = load_rec(name)
    keep = np.ones(len(x), bool)
    for _, at, dur in plays:
        keep[max(0, int((at - margin) * SR)):min(len(x), int((at + dur + margin) * SR))] = False
    return x[keep]


def mix(x, noise, level_db):
    """x плюс фон по кругу, приведённый к RMS level_db."""
    n = np.resize(noise, len(x)).astype(np.float64)
    n *= 10 ** (level_db / 20) / (np.sqrt(np.mean(n ** 2)) + 1e-12)
    return np.clip(x + n, -1, 1).astype(np.float32)


def segment(x, variant, model='gtcrn', att=0.0, gate=None):
    """TranslatorService.startVad при 0 дБ. variant: raw — silero и порог по исходному; dn — silero, порог и
    его фон по очищенному; dn_sil — silero по очищенному, порог по исходному. Шумодав — потоком, кадр за
    кадром, как в приложении: его выход копится и отдаётся по 512 отсчётов, пока выхода нет — тишина.

    gate — «только при шуме», порог фона в dBFS: оценка фона (по исходному звуку, как roomDb в приложении)
    держится не ниже порога 2 с — шумодав создаётся заново и нарезка идёт как dn_sil; 10 с ниже порога
    на 3 дБ — шумодав выключается, нарезка как raw. В тишине шумодав не работает вовсе.

    Куски — (начало, конец, место нарезки) в отсчётах; плюс счёт: кадров со включённым шумодавом,
    включений, медиана и 90-й процентиль оценки фона."""
    cfg = so.VadModelConfig(silero_vad=so.SileroVadModelConfig(model=os.path.join(MODELS, 'silero_vad.onnx'), threshold=0.5,
                            min_silence_duration=0.05, min_speech_duration=0.25, window_size=FRAME, max_speech_duration=15),
                            sample_rate=SR, num_threads=1)
    vad = so.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
    F, PRE, TAIL, HANG, MINSP, MAXSP, GATE = 32, 1000 // 32, 300 // 32, 600, 250, 15000, 6.0
    ON_FR, OFF_FR, HYST = 2000 // 32, 10000 // 32, 3.0
    pre, seg, out = deque(), [], []
    in_sp, silent, voiced, noise, dnoise = False, 0, 0, 0.0, 0.0
    on = gate is None and variant != 'raw'
    dn = denoiser(model, att) if on else None
    buf = np.zeros(0, np.float32)
    on_cnt = off_cnt = on_frames = switches = 0; rooms = []
    k = 10 ** (GATE / 20)
    for i in range(len(x) // FRAME):
        win = x[i * FRAME:(i + 1) * FRAME]
        fr = float(np.sqrt(np.mean(win.astype(np.float64) ** 2)))
        room = 20 * np.log10(noise) if noise > 0 else -999.0
        if noise > 0: rooms.append(room)
        if gate is not None:
            if not on:
                on_cnt = on_cnt + 1 if room >= gate else 0
                if on_cnt >= ON_FR:
                    on, dn, buf, off_cnt, switches = True, denoiser(model, att), np.zeros(0, np.float32), 0, switches + 1
            else:
                off_cnt = off_cnt + 1 if room < gate - HYST else 0
                if off_cnt >= OFF_FR:
                    on, dn, on_cnt = False, None, 0
        dwin = win
        if on:
            buf = np.concatenate([buf, np.asarray(dn.run(np.ascontiguousarray(win), SR).samples, np.float32)])
            if len(buf) >= FRAME: dwin, buf = buf[:FRAME], buf[FRAME:]
            else: dwin = np.zeros(FRAME, np.float32)
            on_frames += 1
        mode = variant if gate is None else ('dn_sil' if on else 'raw')
        vad.accept_waveform(np.ascontiguousarray(dwin if mode in ('dn', 'dn_sil') else win))
        sp = vad.is_speech_detected()
        while not vad.empty():
            vad.pop()
        if not sp:
            if noise == 0: noise = fr
            elif fr < noise: noise = 0.9 * noise + 0.1 * fr
            else: noise = 0.999 * noise + 0.001 * min(fr, 2 * noise)
        if mode == 'dn':
            df = float(np.sqrt(np.mean(dwin.astype(np.float64) ** 2)))
            if not sp:
                if dnoise == 0: dnoise = df
                elif df < dnoise: dnoise = 0.9 * dnoise + 0.1 * df
                else: dnoise = 0.999 * dnoise + 0.001 * min(df, 2 * dnoise)
            speech = sp and (dnoise == 0 or df >= dnoise * k)
        else:
            speech = sp and (noise == 0 or fr >= noise * k)
        if not in_sp:
            pre.append(i)
            while len(pre) > max(1, PRE): pre.popleft()
            if speech:
                in_sp, seg, voiced, silent = True, list(pre), 1, 0; pre.clear()
            continue
        seg.append(i)
        if speech: voiced += 1; silent = 0
        else: silent += 1
        if silent * F < HANG and len(seg) * F < MAXSP:
            continue
        keep = len(seg) - max(0, silent - TAIL)
        if voiced * F >= MINSP and keep > 0:
            out.append((seg[0] * FRAME, (seg[keep - 1] + 1) * FRAME, (i + 1) * FRAME))
        in_sp, seg, silent, voiced = False, [], 0, 0
    n = len(x) // FRAME
    stat = dict(on_share=round(on_frames / max(1, n), 3), switches=switches,
                room_med=round(float(np.median(rooms)), 1) if rooms else None,
                room_p90=round(float(np.percentile(rooms, 90)), 1) if rooms else None)
    return out, stat


def score(segs, texts, plays, gap, lang):
    """Как air_wer.py: окно фразы — от начала проигрывания до начала следующей (не дальше паузы)."""
    wins = []
    for k, (name, at, dur) in enumerate(plays):
        end = at + dur + gap / 1000.0
        if k + 1 < len(plays): end = min(end, plays[k + 1][1])
        wins.append([name, at, end, []])
    outside = 0
    for s, t in zip(segs, texts):
        hit = next((w for w in wins if w[1] <= s[2] / SR <= w[2]), None)
        if hit is None: outside += 1
        else: hit[3].append(t)
    err = tot = nothing = merged = split = 0; clean = []
    for name, _, _, heard_l in wins:
        split += len(heard_l) > 1
        ref = open(os.path.join(R, 'bench', 'air', 'corpus', lang, name[:-4] + '.txt'), encoding='utf-8').read().strip()
        heard = ' '.join(t for t in heard_l if t)
        e, n = wer(ref, heard); err += e; tot += n
        if not heard: nothing += 1
        elif len(norm(heard)) > len(norm(ref)) * 1.6: merged += 1
        else: clean.append((e, n))
    ce, ct = sum(c[0] for c in clean), sum(c[1] for c in clean)
    return dict(wer=round(100 * err / tot, 1), lost=nothing, merged=merged, split=split, clean=round(100 * len(clean) / len(wins), 1),
                wer_clean=round(100 * ce / max(1, ct), 1), segments=len(segs), outside=outside, phrases=len(wins), words=tot)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('recs', nargs='+')
    ap.add_argument('--model', default='gtcrn', help='gtcrn | dpdfnet_baseline | dpdfnet2 …')
    ap.add_argument('--att', type=float, default=0.0, help='предел подавления DPDFNet, дБ (0 — без предела)')
    ap.add_argument('--variants', default='raw,dn,dn_sil', help='raw, dn, dn_sil, auto<порог dBFS> — например auto-47')
    ap.add_argument('--out', default=os.path.join(R, 'results', 'vad_denoise'))
    ap.add_argument('--mix', help='запись, чей фон подмешивать')
    ap.add_argument('--mixdb', default='', help='уровни фона, dBFS, через запятую')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    tag = a.model + ('' if not a.att else '-att%g' % a.att)
    jobs = [(n, None) for n in a.recs] if not a.mix else [(n, float(d)) for n in a.recs for d in a.mixdb.split(',')]
    noise = room_noise(a.mix) if a.mix else None
    for name, level in jobs:
        asr = Asr()                                         # кэш кусков — свой у каждого звука
        lang = name.split('-')[-1]
        x, plays, gap = load_rec(name)
        if level is not None:
            x = mix(x, noise, level); name = '%s+%s%g' % (name, a.mix, level)
        plays = [p for p in plays if os.path.exists(os.path.join(R, 'bench', 'air', 'corpus', lang, p[0][:-4] + '.txt'))]
        res = {'rec': name, 'model': a.model, 'att': a.att, 'variants': {}}
        for v in a.variants.split(','):
            gate = float(v[4:]) if v.startswith('auto') else None
            t0 = time.time()
            segs, stat = segment(x, 'dn_sil' if gate is not None else v, a.model, a.att, gate)
            texts = [asr(x, s_[0], s_[1]) for s_ in segs]
            r = score(segs, texts, plays, gap, lang); r.update(stat); r['seconds'] = round(time.time() - t0)
            res['variants'][v] = r
            print('%-22s %-9s WER %5.1f %% · потеряно %2d из %d · склеено %d · разрезано %2d · кусков %3d (вне окон %2d) · '
                  'WER чистых %5.1f %% · шумодав %3.0f %% времени, включений %d · фон %s / %s dBFS'
                  % (name, v, r['wer'], r['lost'], r['phrases'], r['merged'], r['split'], r['segments'], r['outside'],
                     r['wer_clean'], 100 * r['on_share'], r['switches'], r['room_med'], r['room_p90']), flush=True)
        json.dump(res, open(os.path.join(a.out, '%s-%s.json' % (name, tag)), 'w'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
