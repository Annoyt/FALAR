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


class Wiener:
    """Дешёвый шумодав без нейросети — проверить, нужен ли нарезке GTCRN (21–22 % ядра на телефоне). Кадр 512,
    шаг 256, окно sqrt-Hann, сложение с перекрытием (задержка 256 отсчётов). Шум по каждой полосе — непрерывное
    слежение за минимумом: вниз быстро, вверх медленно. Усиление — Винер с априорным SNR «по решению»
    (Ephraim–Malah, α 0,98), не ниже −20 дБ. Тот же интерфейс, что у OnlineSpeechDenoiser: run(кадр).samples."""
    N, H, ALPHA, GMIN, DOWN, UP, SMOOTH, BIAS = 512, 256, 0.98, 0.1, 0.3, 0.002, 0.3, 2.0

    def __init__(self):
        self.w = np.sqrt(np.hanning(self.N + 1)[:-1])
        self.inbuf = np.zeros(self.N); self.outbuf = np.zeros(self.N)
        self.noise = None; self.prev = None; self.sm = None

    def _frame(self, fr):
        X = np.fft.rfft(fr * self.w); P = np.abs(X) ** 2
        if self.noise is None: self.noise = P.copy(); self.sm = P.copy(); self.prev = np.ones_like(P)
        self.sm += self.SMOOTH * (P - self.sm)                  # сглаженный спектр: минимум по нему смещён меньше
        k = np.where(self.sm < self.noise, self.DOWN, self.UP)
        self.noise += k * (self.sm - self.noise)
        post = P / (self.BIAS * self.noise + 1e-12)              # минимум ниже среднего шума — поправка
        xi = self.ALPHA * self.prev + (1 - self.ALPHA) * np.maximum(post - 1, 0)
        g = np.maximum(xi / (1 + xi), self.GMIN)
        self.prev = g * g * post
        return np.fft.irfft(X * g, self.N) * self.w

    class _R:
        def __init__(self, s): self.samples = s

    def run(self, win, sr):
        out = []
        for j in range(0, len(win), self.H):
            self.inbuf = np.concatenate([self.inbuf[self.H:], win[j:j + self.H]])
            y = self._frame(self.inbuf)
            self.outbuf = np.concatenate([self.outbuf[self.H:], np.zeros(self.H)]) + y
            out.append(self.outbuf[:self.H].copy())
        return self._R(np.concatenate(out).astype(np.float32))


def denoiser(model, att):
    if model == 'wiener':
        return Wiener()
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


VAD = {'kind': 'silero', 'model': os.path.join(MODELS, 'silero_vad.onnx'), 'thr': 0.5, 'model2': None, 'thr2': 0.5, 'music2': False, 'gate2': 0.0}


def vad_config(model=None, thr=None):
    """Детектор речи, как Engine.buildVad: тишина у sherpa почти нулевая (0,05 с), речь от 0,25 с, кусок до 15 с.
    --vad меняет модель (silero v4 — та, что в приложении; silero v5; TEN VAD), --thr — её порог."""
    model = model or VAD['model']; thr = VAD['thr'] if thr is None else thr
    if 'ten' in os.path.basename(model):
        return so.VadModelConfig(ten_vad=so.TenVadModelConfig(model=model, threshold=thr, min_silence_duration=0.05,
                                 min_speech_duration=0.25, window_size=256, max_speech_duration=15), sample_rate=SR, num_threads=1)
    return so.VadModelConfig(silero_vad=so.SileroVadModelConfig(model=model, threshold=thr, min_silence_duration=0.05,
                             min_speech_duration=0.25, window_size=FRAME, max_speech_duration=15), sample_rate=SR, num_threads=1)


class Vads:
    """Один детектор или объединение двух (--vad2): кадр — речь, если речь сказал хоть один. Второй (TEN VAD)
    принимает мелодию за речь (far-pt, конец: куски вне фраз), поэтому при фоне-музыке он молчит, если music."""
    def __init__(self):
        self.v = [so.VoiceActivityDetector(vad_config(), buffer_size_in_seconds=60)]
        if VAD['model2']:
            self.v.append(so.VoiceActivityDetector(vad_config(VAD['model2'], VAD['thr2']), buffer_size_in_seconds=60))

    def accept_waveform(self, w):
        for v in self.v: v.accept_waveform(w)

    def is_speech_detected(self, music=False, loud2=True):
        """loud2 — кадр достаточно громкий для второго детектора (--gate2: на столько дБ над фоном)."""
        return self.v[0].is_speech_detected() or (len(self.v) > 1 and not music and loud2 and self.v[1].is_speech_detected())

    def empty(self):
        for v in self.v:
            while not v.empty(): v.pop()
        return True

    def pop(self):
        pass


HANN = np.hanning(FRAME)


def log_flatness(win):
    """Логарифм спектральной плоскостности кадра в полосе речи 300–4000 Гц (бины 10–128 из 512 при 16 кГц):
    у шума и тишины спектр ровный (0,1–0,4), у музыки — отдельные тоны (около 0,01–0,05)."""
    p = np.abs(np.fft.rfft(win.astype(np.float64) * HANN)) ** 2 + 1e-12
    p = p[10:129]
    return float(np.mean(np.log(p)) - np.log(np.mean(p)))


class Gate:
    """Близнец DenoiseGate: включать ли шумодав нарезки. v1 — только по фону: не ниже t две секунды — включить,
    ниже t − 3 дБ десять секунд — выключить. v2 (music=True) добавляет:
      - музыку: средняя логарифма плоскостности кадров без речи (первые сто кадров — простая средняя, дальше
        скользящая с шагом 0,01) ниже ln 0,08 — фон тональный, шумодав там не помогает и рождает куски из мелодии
        (far-pt, конец), — не включать, а включённый выключить через 3 с;
      - быстрое включение: фон не ниже t + 3 дБ полсекунды — включить сразу, чтобы не пропадала первая фраза;
        только когда о спектре фона уже есть MIN_OBS кадров — иначе в музыке с начала слушания шумодав
        успевал бы включиться до того, как музыка узнана."""
    ON_FRAMES, OFF_FRAMES, HYST = 2000 // 32, 10000 // 32, 3.0
    FAST_DB, FAST_FRAMES = 3.0, 500 // 32
    LOG_MUSIC, MUSIC_OFF_FRAMES, MIN_OBS, ALPHA = float(np.log(0.08)), 3000 // 32, 8, 0.01

    def __init__(self, t, music=False):
        self.t, self.music_on = t, music
        self.on = False; self.on_cnt = self.off_cnt = self.fast_cnt = self.music_cnt = 0
        self.flat, self.obs = 0.0, 0

    def observe(self, logflat):
        """Кадр, который детектор речи речью не признал."""
        self.flat += max(self.ALPHA, 1.0 / (self.obs + 1)) * (logflat - self.flat)
        self.obs += 1

    def music(self):
        return self.music_on and self.obs >= self.MIN_OBS and self.flat < self.LOG_MUSIC

    def step(self, room):
        m = self.music()
        if not self.on:
            if m:
                self.on_cnt = self.fast_cnt = 0
                return 0
            self.on_cnt = self.on_cnt + 1 if room >= self.t else 0
            self.fast_cnt = self.fast_cnt + 1 if self.music_on and self.obs >= self.MIN_OBS and room >= self.t + self.FAST_DB else 0
            if self.on_cnt < self.ON_FRAMES and (not self.music_on or self.fast_cnt < self.FAST_FRAMES):
                return 0
            self.on, self.off_cnt, self.music_cnt = True, 0, 0
            return 1
        self.music_cnt = self.music_cnt + 1 if m else 0
        self.off_cnt = self.off_cnt + 1 if room < self.t - self.HYST else 0
        if self.off_cnt < self.OFF_FRAMES and self.music_cnt < self.MUSIC_OFF_FRAMES:
            return 0
        self.on, self.on_cnt, self.fast_cnt = False, 0, 0
        return -1


def segment(x, variant, model='gtcrn', att=0.0, gate=None, music=False, quiet=None):
    """TranslatorService.startVad при 0 дБ. variant: raw — silero и порог по исходному; dn — silero, порог и
    его фон по очищенному; dn_sil — silero по очищенному, порог по исходному. Шумодав — потоком, кадр за
    кадром, как в приложении: его выход копится и отдаётся по 512 отсчётов, пока выхода нет — тишина.

    gate — «только при шуме», порог фона в dBFS: оценка фона (по исходному звуку, как roomDb в приложении)
    держится не ниже порога 2 с — шумодав создаётся заново и нарезка идёт как dn_sil; 10 с ниже порога
    на 3 дБ — шумодав выключается, нарезка как raw. В тишине шумодав не работает вовсе.

    Куски — (начало, конец, место нарезки) в отсчётах; плюс счёт: кадров со включённым шумодавом,
    включений, медиана и 90-й процентиль оценки фона."""
    vad = Vads()
    F, PRE, TAIL, HANG, MINSP, MAXSP, GATE = 32, 1000 // 32, 300 // 32, 600, 250, 15000, 6.0
    pre, seg, out = deque(), [], []
    in_sp, silent, voiced, noise, dnoise = False, 0, 0, 0.0, 0.0
    on = gate is None and variant != 'raw'
    dn = denoiser(model, att) if on else None
    buf = np.zeros(0, np.float32)
    on_frames = switches = 0; rooms = []
    # Gate следит и за спектром фона: он нужен шумодаву (auto2) и второму детектору (музыка — молчит).
    track = music or (VAD['model2'] and VAD['music2'])
    g = Gate(gate if gate is not None else 0.0, True) if gate is not None or track else None
    if g is not None and gate is not None: g.music_on = music
    k = 10 ** (GATE / 20)
    for i in range(len(x) // FRAME):
        win = x[i * FRAME:(i + 1) * FRAME]
        fr = float(np.sqrt(np.mean(win.astype(np.float64) ** 2)))
        room = 20 * np.log10(noise) if noise > 0 else -999.0
        if noise > 0: rooms.append(room)
        if g is not None and gate is not None:
            ev = g.step(room)
            if ev > 0: on, dn, buf, switches = True, denoiser(model, att), np.zeros(0, np.float32), switches + 1
            elif ev < 0: on, dn = False, None
        dwin = win
        if on:
            buf = np.concatenate([buf, np.asarray(dn.run(np.ascontiguousarray(win), SR).samples, np.float32)])
            if len(buf) >= FRAME: dwin, buf = buf[:FRAME], buf[FRAME:]
            else: dwin = np.zeros(FRAME, np.float32)
            on_frames += 1
        mode = variant if gate is None else ('dn_sil' if on else 'raw')
        vin = dwin if mode in ('dn', 'dn_sil') else win
        if quiet is not None:                             # auto3: в тишине детектор слышит дешёвый шумодав (Винер)
            qw = quiet.run(np.ascontiguousarray(win), SR).samples
            if not on and not (g is not None and g.music()): vin = qw
        vad.accept_waveform(np.ascontiguousarray(vin))
        sp = vad.is_speech_detected(VAD['music2'] and g is not None and g.obs >= g.MIN_OBS and g.flat < g.LOG_MUSIC,
                                    noise == 0 or fr >= noise * 10 ** (VAD['gate2'] / 20))
        while not vad.empty():
            vad.pop()
        if g is not None and track and not sp: g.observe(log_flatness(win))
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
    ap.add_argument('--variants', default='raw,dn,dn_sil', help='raw, dn, dn_sil, auto<порог dBFS> — например auto-47; '
                    'auto2<порог> — с проверкой на музыку и быстрым включением; auto3<порог> — то же, а в тишине детектор слышит '
                    'дешёвый шумодав (Винер); wiener — Винер всегда')
    ap.add_argument('--out', default=os.path.join(R, 'results', 'vad_denoise'))
    ap.add_argument('--vad', default='models/silero_vad.onnx', help='модель детектора речи: silero v4 (в приложении), v5 или ten-vad*.onnx')
    ap.add_argument('--thr', type=float, default=0.5, help='порог детектора речи (в приложении 0,5)')
    ap.add_argument('--vad2', help='второй детектор: речь — если её сказал хоть один из двух')
    ap.add_argument('--thr2', type=float, default=0.5)
    ap.add_argument('--music2', action='store_true', help='при фоне-музыке второй детектор молчит')
    ap.add_argument('--gate2', type=float, default=0.0, help='второй детектор считается, только если кадр на столько дБ громче фона')
    ap.add_argument('--mix', help='запись, чей фон подмешивать')
    ap.add_argument('--mixdb', default='', help='уровни фона, dBFS, через запятую')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    VAD.update(model=os.path.join(R, a.vad), thr=a.thr, model2=os.path.join(R, a.vad2) if a.vad2 else None, thr2=a.thr2, music2=a.music2, gate2=a.gate2)
    vtag = '' if a.vad == 'models/silero_vad.onnx' and a.thr == 0.5 else '-%s-thr%g' % (os.path.basename(a.vad)[:-5], a.thr)
    if a.vad2: vtag += '+%s-thr%g%s%s' % (os.path.basename(a.vad2)[:-5], a.thr2, '-m' if a.music2 else '', '-g%g' % a.gate2 if a.gate2 else '')
    tag = a.model + ('' if not a.att else '-att%g' % a.att) + vtag
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
            music = v.startswith('auto2') or v.startswith('auto3')
            gate = float(v[5:] if v[4:5] in '123' else v[4:]) if v.startswith('auto') else None
            quiet = Wiener() if v.startswith('auto3') or v == 'wiener' else None
            t0 = time.time()
            if v == 'wiener':                                 # дешёвый шумодав для детектора всегда, без GTCRN
                segs, stat = segment(x, 'dn_sil', 'wiener', a.att, None, False)
            else:
                segs, stat = segment(x, 'dn_sil' if gate is not None else v, a.model, a.att, gate, music, quiet)
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
