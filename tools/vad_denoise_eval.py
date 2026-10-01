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


def stream_denoise(x, model, att):
    """Очищенный поток кадрами нарезки: на каждый входной кадр — 512 отсчётов из накопленного выхода
    (пока его нет — нули). Возвращает поток той же длины, задержку шумодава в отсчётах и время."""
    dn = denoiser(model, att)
    assert dn.sample_rate == SR, dn.sample_rate
    fifo, out, t0, lagged = deque(), np.zeros_like(x), time.time(), 0
    buf = np.zeros(0, np.float32)
    for i in range(len(x) // FRAME):
        a = dn.run(np.ascontiguousarray(x[i * FRAME:(i + 1) * FRAME]), SR)
        buf = np.concatenate([buf, np.asarray(a.samples, np.float32)])
        if len(buf) >= FRAME:
            out[i * FRAME:(i + 1) * FRAME] = buf[:FRAME]; buf = buf[FRAME:]
        else:
            lagged += 1
    return out, lagged * FRAME, time.time() - t0


def segment(sil_in, gate_in):
    """TranslatorService.startVad при 0 дБ: silero по sil_in, порог по энергии и фон по gate_in.
    Куски — (начало, конец, место нарезки) в отсчётах."""
    cfg = so.VadModelConfig(silero_vad=so.SileroVadModelConfig(model=os.path.join(MODELS, 'silero_vad.onnx'), threshold=0.5,
                            min_silence_duration=0.05, min_speech_duration=0.25, window_size=FRAME, max_speech_duration=15),
                            sample_rate=SR, num_threads=1)
    vad = so.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
    F, PRE, TAIL, HANG, MINSP, MAXSP, GATE = 32, 1000 // 32, 300 // 32, 600, 250, 15000, 6.0
    pre, seg, out = deque(), [], []
    in_sp, silent, voiced, noise = False, 0, 0, 0.0
    for i in range(len(sil_in) // FRAME):
        win = sil_in[i * FRAME:(i + 1) * FRAME]
        g = gate_in[i * FRAME:(i + 1) * FRAME]
        fr = float(np.sqrt(np.mean(g.astype(np.float64) ** 2)))
        vad.accept_waveform(win)
        sp = vad.is_speech_detected()
        while not vad.empty():
            vad.pop()
        if not sp:
            if noise == 0: noise = fr
            elif fr < noise: noise = 0.9 * noise + 0.1 * fr
            else: noise = 0.999 * noise + 0.001 * min(fr, 2 * noise)
        speech = sp and (noise == 0 or fr >= noise * 10 ** (GATE / 20))
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
    return out


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
    err = tot = nothing = merged = 0; clean = []
    for name, _, _, heard_l in wins:
        ref = open(os.path.join(R, 'bench', 'air', 'corpus', lang, name[:-4] + '.txt'), encoding='utf-8').read().strip()
        heard = ' '.join(t for t in heard_l if t)
        e, n = wer(ref, heard); err += e; tot += n
        if not heard: nothing += 1
        elif len(norm(heard)) > len(norm(ref)) * 1.6: merged += 1
        else: clean.append((e, n))
    ce, ct = sum(c[0] for c in clean), sum(c[1] for c in clean)
    return dict(wer=round(100 * err / tot, 1), lost=nothing, merged=merged, clean=round(100 * len(clean) / len(wins), 1),
                wer_clean=round(100 * ce / max(1, ct), 1), segments=len(segs), outside=outside, phrases=len(wins), words=tot)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('recs', nargs='+')
    ap.add_argument('--model', default='gtcrn', help='gtcrn | dpdfnet_baseline | dpdfnet2 …')
    ap.add_argument('--att', type=float, default=0.0, help='предел подавления DPDFNet, дБ (0 — без предела)')
    ap.add_argument('--variants', default='raw,dn,dn_sil')
    ap.add_argument('--out', default=os.path.join(R, 'results', 'vad_denoise'))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    asr = Asr()
    tag = a.model + ('' if not a.att else '-att%g' % a.att)
    for name in a.recs:
        lang = name.split('-')[-1]
        x, plays, gap = load_rec(name)
        plays = [p for p in plays if os.path.exists(os.path.join(R, 'bench', 'air', 'corpus', lang, p[0][:-4] + '.txt'))]
        y, lag, sec = stream_denoise(x, a.model, a.att)
        res = {'rec': name, 'model': a.model, 'att': a.att, 'lag_samples': lag,
               'rtf': round(sec / (len(x) / SR), 4), 'variants': {}}
        for v in a.variants.split(','):
            sil_in, gate_in = {'raw': (x, x), 'dn': (y, y), 'dn_sil': (y, x)}[v]
            segs = segment(sil_in, gate_in)
            texts = [asr(x, s[0], s[1]) for s in segs]
            res['variants'][v] = score(segs, texts, plays, gap, lang)
            r = res['variants'][v]
            print('%-9s %-7s WER %5.1f %% · потеряно %2d из %d · склеено %d · кусков %3d (вне окон %d) · WER чистых %5.1f %%'
                  % (name, v, r['wer'], r['lost'], r['phrases'], r['merged'], r['segments'], r['outside'], r['wer_clean']), flush=True)
        print('%-9s шумодав %s: задержка %d отсчётов, RTF %.3f на ПК (один поток)' % (name, tag, lag, res['rtf']), flush=True)
        json.dump(res, open(os.path.join(a.out, '%s-%s.json' % (name, tag)), 'w'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
