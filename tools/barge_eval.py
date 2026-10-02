#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Перебивание: человек заговорил поверх озвучки — заметить это по микрофону и по тому, что в тот же миг
играло, приглушить озвучку, убедиться и замолчать. Разбор записей стенда эха (bench/apk/test_aec_device.sh:
vr_e<k> — только эхо фразы k, vr_d<k> — человек из колонок ПК поверх неё, vr_q<k> — только человек).

  .venv/bin/python tools/barge_eval.py <каталог записей> [<каталог> …]   # ~/.cache/falar-stand/aec-<время>
  .venv/bin/python tools/barge_eval.py golden                            # эталон для BargeInTest.java

Датчик (класс Barge; тот же счёт — в BargeIn.java). Кадр 32 мс, шаг 16 мс, 20 мел-полос. Ожидаемое эхо в
полосе — мощность сыгранного в ней, «размазанная» по отражениям (−DECAY дБ на шаг, TAIL шагов) и по
неточности совмещения (±JIT шагов), умноженная на усиление тракта «динамик → микрофон» (учится по ходу,
пока человека не видно), плюс доля всего эха (искажения динамика) и фон. Полоса «за человеком», если
микрофон громче ожидаемого на THETA дБ и громче фона на NOISE_DB.
  Ступень 1 — подозрение: таких полос не меньше SHARE1 в K1 кадрах из M1 → озвучка на паузе.
  Ступень 2 — проверка на паузе (эха почти нет — остаток выучен): человек громче остатка в большинстве
  полос; SHARE2 в K2 из M2 за WIN2 шагов → озвучка замолкает совсем, слушание получает звук с подпором;
  не подтвердилось → озвучка продолжается с того же места (задержка на полсекунды, ничего не теряется).

Что считается:
  0. Совмещение по меткам времени Android (кадр N снят/прозвучал в момент T) против взаимной корреляции.
  1. Только эхо: подозрения (паузы в озвучке) и ложные остановки.
  2. Живой человек поверх озвучки: через сколько заподозрен (вторую ступень на готовой записи не
     проверить — эхо в ней не приглушишь).
  3. Смеси с телефона (эхо из записи «только эхо» + человек из записи «только человек»; обе записаны
     телефоном в комнате, сложены цифрово, приглушение и остановка — по действиям датчика): доля
     остановленных, задержки, и главное — сколько слов человека распознаётся: как сейчас (микрофон глух до
     конца озвучки), с перебиванием, и человек без озвучки — потолок.
"""
import json
import os
import re
import sys
import wave

import numpy as np
from scipy.signal import resample_poly

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SR, N, HOP = 16000, 512, 256
NB, F_LO, F_HI = 20, 150.0, 6000.0
THETA1, SHARE1, K1, M1 = 10.0, 0.10, 2, 2   # ступень 1: дБ над ожидаемым эхом; доля полос; K кадров из M
THETA2, SHARE2, K2, M2 = 8.0, 0.15, 3, 4    # ступень 2 — на паузе
SNR2 = 10.0                                 # и полосы «за человеком» вместе громче фона в них на столько: колебания
                                            # фона после конца фразы проходили порог полосы (прогон 02.10, громкость 10)

DECAY, TAIL, JIT = 4.0, 10, 1               # затухание отражений, дБ на шаг; сколько шагов; ±шагов совмещения
NOISE_DB = 6.0                              # полоса считается, только если громче фона на столько
SPREAD_DB = -25.0                           # искажения динамика: доля всего эха, что ложится в любую полосу
GRACE = 10                                  # шагов после начала звука не решаем: щелчок усилителя и начало эха
HOLD_DB = -40.0                             # на подозрении озвучка на паузе: эха нет, остаётся хвост в комнате и фон
ATT0 = HOLD_DB                              # сколько эха ждать на паузе — нисколько (было: учиться по отбоям — но на
                                            # телефоне в «отбоях» звучал и сам человек, и остаток дорастал до −20 дБ).
                                            # Не приглушение: на громкости 15 у Redmi −20/−30/−40 дБ в цифре гасили
                                            # эхо у микрофона лишь на 5–11 дБ — обработка звука подтягивает тихое
SIM_ATT = {}                                # для смесей: громкость → сколько эха остаётся на паузе, дБ (по умолчанию HOLD_DB)
STOP_MS = 250                               # после решения звук ещё идёт: пауза/приглушение доходят до микрофона через 110–190 мс
                                            # (стенд vr_k/m/n, 02.10), и эхо ещё гаснет в комнате — проверка ждёт с запасом
LAT_MS = 150                                # а в смесях действие доходит до микрофона через столько (медиана замера)
EFF = -(-(N + STOP_MS * 16) // HOP)         # через столько шагов после решения приглушение уже слышно
WIN2 = 15                                   # шагов на проверку, потом — отбой
OPEN_MS = 120                               # после остановки эхо гаснет в комнате (−20 дБ за ~80 мс, по концам фраз)
PRE_MS = 500                                # подпор: столько звука до подозрения отдаётся распознаванию
MUTE_TAIL_MS = 400                          # как сейчас: микрофон глух до конца озвучки и ещё столько
NONE, HOLD, STOP, RESUME = 0, 1, 2, 3


def mel(f):
    return 2595.0 * np.log10(1.0 + f / 700.0)


def imel(m):
    return 700.0 * (10.0 ** (m / 2595.0) - 1.0)


EDGES = imel(np.linspace(mel(F_LO), mel(F_HI), NB + 1))
BINS = np.arange(N // 2 + 1) * SR / N
BAND = [np.where((BINS >= EDGES[i]) & (BINS < EDGES[i + 1]))[0] for i in range(NB)]
WIN = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N) / N)).astype(np.float64)   # Ханн, периодический — как в Java


def power(frame):
    p = np.abs(np.fft.rfft(frame * WIN)) ** 2
    return np.array([p[b].sum() for b in BAND]) + 1e-12


def bands(x):
    """Мощность по полосам для каждого шага: кадр N отсчётов, шаг HOP."""
    n = max(0, (len(x) - N) // HOP + 1)
    return np.array([power(x[t * HOP:t * HOP + N]) for t in range(n)]).reshape(n, NB)


class Barge:
    """Потоковый датчик: frame(m, r_next) на каждом шаге — мощности кадра микрофона и сыгранного на шаг
    вперёд (сыгранное известно заранее). Возвращает действие: NONE, HOLD, STOP, RESUME."""

    def __init__(self, g_db=None, noise=None, att=None):
        self.g = np.zeros(NB) if g_db is None else np.array(g_db, float)     # усиление тракта, дБ
        self.att = np.full(NB, ATT0) if att is None else np.array(att, float)  # ослабление эха приглушением, дБ
        self.obs, self.nobs = np.zeros(NB), np.zeros(NB)
        self.noise = None if noise is None else np.array(noise, float)
        self.hist = []
        self.smax = np.full(NB, 1e-12)
        self.since = -1                  # шагов с начала звука у микрофона
        self.elev = np.zeros(NB)         # недавняя громкость эха по полосам, медленно спадает
        self.hits = []
        self.hits2 = []
        self.state = 'listen'
        self.t = 0
        self.fire_t = None
        self.score = 0.0
        self.lev = -200.0

    def track_noise(self, m):
        """Фон: вниз быстро, вверх не быстрее 0,03 дБ за шаг (2 дБ/с). Вверх по 0,01 линейно он за пару секунд речи
        между фразами дорастал до самой речи, и на паузе человек был «не громче фона»; а «не больше чем вдвое за
        шаг по 0,002» не поднимался неделями с цифрового нуля начала записи — и тишина сходила за человека
        (прогоны 02.10). Цифровой ноль фоном не считается."""
        n = self.noise
        d = 10 * np.log10(np.maximum(m, 1e-30) / np.maximum(n, 1e-30))     # дБ
        # вверх ≤ 0,03 дБ за шаг; вниз — 0,3 разницы, но не больше 1 дБ за шаг: несколько почти тихих кадров на
        # стыке фраз (телефон глушит вход, включая динамик) роняли фон на 10 дБ, и тишина после фразы сходила
        # за человека
        new = n * 10 ** (np.where(d > 0, np.minimum(0.03, d), np.maximum(-1.0, 0.3 * d)) / 10)
        new = np.where(n < 1e-9, m, new)
        return np.where(m < 1e-9, n, new)

    def smear(self):
        h = self.hist                    # h[-1] — шаг t+1, h[-2] — шаг t
        s = np.zeros(NB)
        for j in range(-JIT, TAIL + 1):
            i = len(h) - 2 - j
            if 0 <= i < len(h):
                s = np.maximum(s, h[i] * 10 ** (-max(0, j) * DECAY / 10))
        return s

    def cells(self, m, s, g, theta):
        e = s * 10 ** (g / 10)
        p = e + e.sum() * 10 ** (SPREAD_DB / 10) + self.noise
        c = (10 * np.log10(m / p) > theta) & (m > self.noise * 10 ** (NOISE_DB / 10))
        return c, e

    def frame(self, m, r_next, learn=True):
        t = self.t
        self.t += 1
        self.hist.append(np.asarray(r_next, float))
        if len(self.hist) > TAIL + 3:
            self.hist.pop(0)
        s = self.smear()
        if self.noise is None:
            self.noise = np.array(m, float)
        if self.state == 'stopped':
            return NONE
        if self.state == 'ducked':
            if t < self.fire_t + EFF:
                return NONE
            if t >= self.fire_t + EFF + WIN2:
                self.state = 'listen'
                self.hits = []
                self.since = 0                      # после паузы — снова не решаем GRACE шагов: метки времени догоняют
                return RESUME
            e0 = s * 10 ** (self.g / 10)
            seen = e0 > self.noise * 10 ** (15 / 10)
            self.obs += np.where(seen, 10 * np.log10(m / (e0 + 1e-12)), 0); self.nobs += seen
            c, _ = self.cells(m, s, self.g + self.att, THETA2)
            self.score = c.mean()
            snr = 10 * np.log10((m * c).sum() / ((self.noise * c).sum() + 1e-12) + 1e-12)
            self.hits2 = (self.hits2 + [self.score >= SHARE2 and snr >= SNR2])[-M2:]
            if sum(self.hits2) >= K2:
                self.state = 'stopped'
                self.lev = 10 * np.log10((m * c).sum() + 1e-12)
                return STOP
            return NONE
        # фон — где сыгранного нет вовсе: вниз быстро, вверх медленно
        quiet = s < 1e-9
        self.noise = np.where(quiet, self.track_noise(m), self.noise)
        c, e = self.cells(m, s, self.g, THETA1)
        self.score = c.mean()
        self.elev = np.maximum(self.elev * 10 ** (-0.02), e)
        if self.since < 0 and e.sum() > self.noise.sum():
            self.since = 0
        elif self.since >= 0:
            self.since += 1
        hit = self.score >= SHARE1 and self.since > GRACE
        self.hits = (self.hits + [hit])[-M1:]
        # усиление тракта: учится, пока человека не видно, по полосам с заметным эхом (в пределах 15 дБ от
        # недавнего максимума) — следит за верхней четвертью отношения микрофон/сыгранное
        self.smax = np.maximum(self.smax * 10 ** (-0.05), s)
        if learn and not any(self.hits):
            ok = self.teach(s, e)
            rat = 10 * np.log10(m / (s + 1e-12))
            self.g = np.where(ok, self.g + np.where(rat > self.g, 0.375, -0.125), self.g)
        if sum(self.hits) >= K1:
            self.state = 'ducked'
            self.fire_t = t
            self.hits2 = []
            self.obs, self.nobs = np.zeros(NB), np.zeros(NB)
            return HOLD
        return NONE

    def learn(self, m, r_next):
        """Прогрев: только учиться (фон, усиление тракта), без решений — как BargeIn.learn."""
        self.t += 1
        self.hist.append(np.asarray(r_next, float))
        if len(self.hist) > TAIL + 3:
            self.hist.pop(0)
        s = self.smear()
        if self.noise is None:
            self.noise = np.array(m, float)
        quiet = s < 1e-9
        self.noise = np.where(quiet, self.track_noise(m), self.noise)
        self.smax = np.maximum(self.smax * 10 ** (-0.05), s)
        ok = self.teach(s, s * 10 ** (self.g / 10))
        rat = 10 * np.log10(m / (s + 1e-12))
        self.g = np.where(ok, self.g + np.where(rat > self.g, 0.375, -0.125), self.g)
        return bool(ok.any())

    def teach(self, s, e):
        """Учиться в полосе можно, где эхо заметное: не ниже 15 дБ от недавнего максимума сыгранного и громче
        фона (между фразами и в паузах усиление по шуму не уползает)."""
        return (s > self.smax * 10 ** (-1.5)) & (e > self.noise)

    def reset(self):
        """Новая фраза озвучки: всё, кроме усиления тракта и фона."""
        self.hist, self.hits, self.hits2 = [], [], []
        self.since, self.state, self.fire_t = -1, 'listen', None


def closed_loop(echo, human, ref, bg, start, g0, noise0, sim_db=None, att0=None):
    """Датчик на смеси с обратной связью: приглушение и остановка меняют эхо, которое слышит микрофон дальше
    (действие на шаге t слышно с отсчёта t·HOP + N + STOP_MS). Возвращает (действия [(шаг, действие)],
    звук как его слышал микрофон, детектор)."""
    n = len(echo)
    scale = np.ones(n)
    Rb = bands(ref)
    b = Barge(g0, noise0, att0)
    floor = 10 ** ((HOLD_DB if sim_db is None else sim_db) / 20)     # что остаётся от эха на паузе
    acts = []
    for t in range(max(0, start - 2), min(len(Rb) - 1, (n - N) // HOP)):
        seg = slice(t * HOP, t * HOP + N)
        x = echo[seg] * scale[seg] + bg[seg] * (1 - scale[seg]) + human[seg]
        b.t = t
        a = b.frame(power(x), Rb[t + 1])
        if a == NONE:
            continue
        acts.append((t, a))
        at = t * HOP + N + LAT_MS * 16
        if a == HOLD:                                                    # пауза: эхо гаснет, как после остановки
            tt = np.arange(n - at)
            scale[at:] = np.minimum(scale[at:], np.maximum(floor, 10 ** (-0.25 * tt / 16 / 20)))   # −0,25 дБ/мс
        elif a == RESUME:
            scale[at:] = 1.0
        elif a == STOP:
            tt = np.arange(n - at)
            scale[at:] = np.minimum(scale[at:], 10 ** (-0.25 * tt / 16 / 20))
    heard = echo * scale + bg * (1 - scale) + human
    return acts, heard, b


def gate(x, ref, g_db, upto):
    """Подпор с погашенным эхом: в кадрах до upto полосы, где ожидаемое эхо сравнимо с микрофоном, глушатся
    (как подавление остатка, но только на подпоре — дальше озвучка уже приглушена)."""
    out = np.zeros(len(x) + N); nrm = np.zeros(len(x) + N)
    for t in range(0, max(0, min(len(x), upto) - N), HOP):
        X = np.fft.rfft(x[t:t + N] * WIN); Rr = np.abs(np.fft.rfft(ref[t:t + N] * WIN)) ** 2
        eb = np.zeros(len(X))
        for b, idx in enumerate(BAND):
            eb[idx] = Rr[idx].sum() * 10 ** (g_db[b] / 10) / max(1, len(idx))
        gain = np.maximum(0.05, 1 - 2.0 * eb / (np.abs(X) ** 2 + 1e-12))
        out[t:t + N] += np.fft.irfft(X * gain, N) * WIN; nrm[t:t + N] += WIN ** 2
    y = out[:len(x)] / np.maximum(nrm[:len(x)], 1e-6)
    y[min(len(x), upto):] = x[min(len(x), upto):]
    return y


# ---------- записи стенда ----------

def read(p):
    with wave.open(p) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float64) / 32768
        return x, w.getframerate()


def write(p, x):
    with wave.open(p, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 32767 / 32768) * 32768).astype('<i2').tobytes())


def db(x):
    return 20 * np.log10(np.sqrt(np.mean(np.asarray(x, np.float64) ** 2)) + 1e-12)


def xcorr(a, b):
    n = 1 << int(np.ceil(np.log2(len(a) + len(b))))
    return np.fft.irfft(np.fft.rfft(a, n) * np.conj(np.fft.rfft(b, n)), n)[:len(a) - len(b) + 1]


def delay(mic, ref, at, lo=0.0, hi=0.8):
    """Задержка эха в отсчётах от at — по максимуму взаимной корреляции (как в aec_eval.py)."""
    a = at + int(lo * SR)
    seg = mic[a:at + int(hi * SR) + len(ref)]
    c = xcorr(seg, ref)
    k = int(np.argmax(np.abs(c)))
    return int(lo * SR) + k, float(np.abs(c[k]) / (np.linalg.norm(ref) * np.linalg.norm(seg[k:k + len(ref)]) + 1e-12))


def ts_delay(c, rate):
    """Задержка эха по меткам времени: в какой отсчёт записи попал нулевой отсчёт фразы, минус play_sample.
    Берутся только метки дорожки, пока она играет (позиция растёт): до начала звука и после конца фразы
    позиция стоит, и время при ней ничего не значит."""
    tr, tt = c.get('ts_rec') or [], c.get('ts_track') or []
    head0 = c.get('track_head0')
    if not tr or not tt or head0 is None:
        return None
    est = []
    for (f0, _), (ft, nt) in zip(tt, tt[1:]):
        if ft <= f0 or ft <= head0:
            continue
        t0 = nt + (head0 - ft) * 1e9 / rate          # нулевой кадр фразы — кадр дорожки head0
        fr, nr, _ = min(tr, key=lambda p: abs(p[1] - t0))
        est.append(fr + (t0 - nr) * SR / 1e9)
    if len(est) < 3:
        return None
    return float(np.median(est)) - c['play_sample'], float(np.percentile(est, 90) - np.percentile(est, 10))


def load(d):
    meta = json.load(open(os.path.join(d, 'aec.json'), encoding='utf-8'))
    near = {}
    p = os.path.join(d, 'near.tsv')
    if os.path.exists(p):
        for l in open(p, encoding='utf-8'):
            c = l.rstrip('\n').split('\t')
            if len(c) >= 2:
                near[c[0]] = c[1]
    recs = []
    for cfg, c in meta.items():
        if not isinstance(c, dict) or 'play_sample' not in c:
            continue
        w = os.path.join(d, f'aec_{cfg}.wav')
        if not os.path.exists(w):
            continue
        mic, _ = read(w)
        ref, rr = read(os.path.join(d, c.get('ref', 'aec_ref.wav')))
        mode = c.get('mode') or ('q' if '_q' in cfg else 'd')
        recs.append(dict(dir=d, cfg=cfg, c=c, mic=mic, ref=resample_poly(ref, SR, rr), rate=rr, at=int(c['play_sample']),
                         mode=mode, phrase=c.get('phrase', 0), vol=meta.get('volume'), clip=near.get(cfg)))
    return recs


def aligned(rec, d, n=None):
    r = np.zeros(len(rec['mic']) if n is None else n)
    s0 = rec['at'] + d
    k = min(len(rec['ref']), len(r) - s0)
    r[s0:s0 + k] = rec['ref'][:k]
    return r


def onset(x, clip):
    """Где в записи начинается фраза человека: взаимная корреляция с чистой фразой корпуса."""
    h, hr = read(clip + '.wav')
    h = h if hr == SR else resample_poly(h, SR, hr)
    c = np.abs(xcorr(x, h))
    k = int(np.argmax(c))
    return k, len(h), float(c[k] / (np.linalg.norm(h) * np.linalg.norm(x[k:k + len(h)]) + 1e-12))


def noise_of(x, start):
    b = bands(x[:start * HOP + N])
    return np.median(b[max(0, len(b) - 42):max(1, len(b) - 2)], axis=0)


# ---------- распознавание ----------

_asr = None


def asr(x):
    global _asr
    import sherpa_onnx
    if _asr is None:
        d = os.environ.get('ASR_DIR', os.path.join(R, 'models', 'asr_multi'))
        _asr = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=f'{d}/encoder.int8.onnx', decoder=f'{d}/decoder.int8.onnx', joiner=f'{d}/joiner.int8.onnx',
            tokens=f'{d}/tokens.txt', num_threads=2, model_type='nemo_transducer')
    if len(x) < SR // 4:
        return ''
    s = _asr.create_stream()
    s.accept_waveform(SR, np.asarray(x, np.float32))
    _asr.decode_stream(s)
    return s.result.text


def words(t):
    return [w for w in re.sub(r"[^\w' -]", ' ', t.lower()).split() if len(w) > 1]


def recall(ref, hyp):
    r, h = words(ref), set(words(hyp))
    return sum(w in h for w in r) / max(1, len(r))


# ---------- разбор ----------

def section0(recs):
    print('## 0. Совмещение: метки времени Android против взаимной корреляции\n')
    print('| запись | громкость | по корреляции, мс | сходство | по меткам, мс | разброс меток (p10–p90), мс | расхождение, мс |')
    print('|---|---|---|---|---|---|---|')
    for r in recs:
        if r['mode'] == 'q':
            continue
        r['delay'], r['rho'] = delay(r['mic'], r['ref'], r['at'])
        r['ts'] = t = ts_delay(r['c'], r['rate'])
        by_ts = '—' if t is None else f'{t[0] / 16:.1f}'
        spread = '—' if t is None else f'{t[1] / 16:.1f}'
        diff = '—' if t is None else f'{(t[0] - r["delay"]) / 16:+.1f}'
        print(f'| {r["cfg"]} | {r["vol"]} | {r["delay"] / 16:.1f} | {r["rho"]:.2f} | {by_ts} | {spread} | {diff} |')
    # совмещение, как у приложения: метки времени + постоянная поправка устройства (медиана по записям «только
    # эхо» с надёжной корреляцией); без меток — по корреляции
    offs = [r['delay'] - r['ts'][0] for r in recs if r['mode'] == 'e' and r.get('ts') and r['rho'] >= 0.2]
    off = float(np.median(offs)) if offs else 0.0
    print(f'\nПоправка меток времени этого телефона: {off / 16:+.1f} мс (по {len(offs)} записям «только эхо»; '
          f'разброс {np.ptp(offs) / 16 if offs else 0:.1f} мс)')
    for r in recs:
        if r['mode'] != 'q':
            r['al'] = int(round(r['ts'][0] + off)) if r.get('ts') else r['delay']
    # поправку приложение находит само — по огибающим эха (BargeIn.lag) на совмещении только по меткам
    est = []
    for r in recs:
        if r['mode'] == 'e' and r.get('ts') and r['vol'] is not None:
            s0 = r['at'] + int(round(r['ts'][0]))
            Mb, Rb = bands(r['mic']).sum(1), bands(aligned(r, int(round(r['ts'][0])))).sum(1)
            k0, k1 = s0 // HOP, (s0 + len(r['ref'])) // HOP + 8
            if k1 - k0 > 60:
                L, c = lag(Mb[k0:k1], Rb[k0:k1], 24)
                est.append((r['cfg'], r['vol'], L * HOP / 16, c))
    if est:
        print('Поправка по огибающим эха (как найдёт приложение), мс · сходство: ' + '; '.join(f'{c} ({v}) {L:.0f} · {s_:.2f}' for c, v, L, s_ in est))
    return off


def echoes(recs):
    """Записи «только эхо», где озвучка действительно звучала (короткие фразы без поправки порога старта
    дорожки молчали: эхо там на уровне фона)."""
    return [r for r in recs if r['mode'] == 'e' and r['vol'] is not None
            and db(r['mic'][r['at'] + r['al']:r['at'] + r['al'] + len(r['ref'])]) > -30]


def learn_gain(r):
    """Усиление тракта, выученное прогревом (без решений, как BargeIn.learn) на всей записи «только эхо»."""
    s0 = (r['at'] + r['al']) // HOP
    b = Barge(None, noise_of(r['mic'], s0))
    Mb, Rb = bands(r['mic']), bands(aligned(r, r['al']))
    for t in range(max(0, s0 - 2), len(Mb) - 1):
        b.learn(Mb[t], Rb[t + 1])
    return b.g


def lag(mic, ref, maxlag):
    """Как BargeIn.lag: на сколько шагов огибающая микрофона отстаёт от сыгранного, и сходство."""
    a, r = 10 * np.log10(mic + 1e-12), 10 * np.log10(ref + 1e-12)
    n = len(a); c = np.zeros(maxlag + 1)
    for L in range(maxlag + 1):
        x = a[maxlag:] - a[maxlag:].mean(); y = r[maxlag - L:n - L] - r[maxlag - L:n - L].mean()
        c[L] = (x * y).sum() / np.sqrt((x * x).sum() * (y * y).sum() + 1e-12)
    b = int(np.argmax(c)); d = 0.0
    if 0 < b < maxlag:
        den = c[b - 1] - 2 * c[b] + c[b + 1]
        if den < 0:
            d = 0.5 * (c[b - 1] - c[b + 1]) / den
    return b + d, float(c[b])


def section1(recs):
    ech = echoes(recs)
    learned = {id(r): learn_gain(r) for r in ech}
    prior = {v: np.mean([learned[id(r)] for r in ech if r['vol'] == v], axis=0) for v in {r['vol'] for r in ech}}
    print('\n## 1. Только эхо: подозрения (пауза в озвучке) и ложные остановки\n')
    print('Усиление тракта для каждой фразы — выученное на остальных фразах той же громкости (в приложении оно '
          'хранится между фразами и дообучается по ходу).\n')
    print('| запись | громкость | эхо, dBFS | подозрений | ложных остановок |')
    print('|---|---|---|---|---|')
    sus = stops = 0; sec = 0.0
    for r in ech:
        g0 = np.mean([learned[id(q)] for q in ech if q['vol'] == r['vol'] and q is not r], axis=0)
        s0 = (r['at'] + r['al']) // HOP
        bg = np.tile(r['mic'][max(0, r['at'] - 8000):r['at']], 60)[:len(r['mic'])]
        acts, _, _ = closed_loop(r['mic'], np.zeros(len(r['mic'])), aligned(r, r['al']), bg, s0, g0, noise_of(r['mic'], s0), SIM_ATT.get(r['vol']))
        d = sum(a == HOLD for _, a in acts); s = sum(a == STOP for _, a in acts)
        sus += d; stops += s; sec += len(r['ref']) / SR
        e = r['mic'][r['at'] + r['al']:r['at'] + r['al'] + len(r['ref'])]
        print(f'| {r["cfg"]} | {r["vol"]} | {db(e):.1f} | {d} | {s} |')
    print(f'\nОзвучки {sec:.0f} с: подозрений {sus} ({sus / max(sec, 1) * 60:.1f} в минуту), ложных остановок {stops}.')
    for v in sorted(prior):
        print(f'Усиление тракта при громкости {v}, дБ по полосам: ' + ' '.join(f'{x:.0f}' for x in prior[v]))
    return prior


def section2(recs, prior):
    print('\n## 2. Человек из колонок ПК поверх озвучки (живая запись): когда заподозрен\n')
    print('| запись | громкость | человек вступил, с от начала озвучки | сходство с фразой | заподозрен через, мс | подозрение раньше человека |')
    print('|---|---|---|---|---|---|')
    lat = []
    for r in [r for r in recs if r['mode'] == 'd' and r['clip'] and r['vol'] is not None]:
        s0 = (r['at'] + r['al']) // HOP
        k, hl, rho = onset(r['mic'], r['clip'])
        b = Barge(prior.get(r['vol']), noise_of(r['mic'], s0))
        Mb, Rb = bands(r['mic']), bands(aligned(r, r['al']))
        first = early = None
        for t in range(max(0, s0 - 2), len(Mb) - 1):
            b.t = t
            if b.frame(Mb[t], Rb[t + 1]) == HOLD:
                if t * HOP + N < k:
                    early = early or t; b.state = 'listen'; b.hits = []      # мимо: ищем дальше
                else:
                    first = t; break
        end = r['at'] + r['al'] + len(r['ref'])
        txt = '—' if first is None else (f'{(first * HOP + N - k) / 16:.0f}' if first * HOP < end else f'после конца озвучки')
        if first is not None and first * HOP < end:
            lat.append((first * HOP + N - k) / 16)
        print(f'| {r["cfg"]} | {r["vol"]} | {(k - r["at"] - r["al"]) / SR:.2f} | {rho:.2f} | {txt} | {"ДА" if early else "нет"} |')
    if lat:
        print(f'\nЗаподозрен {len(lat)} раз; задержка: медиана {np.median(lat):.0f} мс, наибольшая {max(lat):.0f} мс.')


def section3(recs, prior, with_asr=True):
    ech = echoes(recs)
    hum = []
    for r in recs:
        if r['mode'] == 'q' and r['clip']:
            k, hl, rho = onset(r['mic'], r['clip'])
            if rho >= 0.3:
                hum.append((r, k, hl, open(r['clip'] + '.txt', encoding='utf-8').read().strip()))
    if not ech or not hum:
        print('\n(для смесей нужны записи «только эхо» и «только человек»)'); return
    print('\n## 3. Смеси: эхо телефона + человек, оба записаны телефоном в комнате, сложены цифрово\n')
    print(f'Фраз эха {len(ech)}, фраз человека {len(hum)}; человек вступает через 0,3 / 1,0 / 2,0 с после начала '
          f'озвучки. Действия датчика меняют эхо дальше по записи: пауза и остановка '
          f'слышны через {STOP_MS} мс после решения. Распознаётся звук с подпором {PRE_MS} мс до подозрения (эхо в '
          f'подпоре погашено по полосам). Как сейчас: микрофон глух до конца озвучки + {MUTE_TAIL_MS} мс.\n')
    rows = {}
    tts_txt = {}
    for e in ech:
        s_e = e['at'] + e['al']; end = s_e + len(e['ref'])
        noise_seg = e['mic'][max(0, e['at'] - 8000):e['at']]
        if with_asr and id(e) not in tts_txt:
            tts_txt[id(e)] = asr(e['ref'])
        for (q, k, hl, txt) in hum:
            hseg = q['mic'][max(0, k - 1600):k + hl + 4800]
            for off_s in (0.3, 1.0, 2.0):
                h_at = s_e + int(off_s * SR)
                if h_at + int(0.8 * SR) > end:
                    continue
                n = max(len(e['mic']), h_at - 1600 + len(hseg) + SR // 2)
                bg = np.tile(noise_seg, int(np.ceil(n / len(noise_seg))))[:n]
                echo = np.concatenate([e['mic'], bg[len(e['mic']):]])
                ref = aligned(e, e['al'], n)
                for level in (0, -12):
                    human = np.zeros(n); human[h_at - 1600:h_at - 1600 + len(hseg)] = hseg * 10 ** (level / 20)
                    acts, heard, b = closed_loop(echo, human, ref, bg, s_e // HOP, prior[e['vol']], noise_of(echo, s_e // HOP), SIM_ATT.get(e['vol']))
                    st = rows.setdefault((e['vol'], level), dict(n=0, stop=0, early=0, dips=0, lat_duck=[], lat_stop=[], levs=[],
                                                                 rec_now=[], rec=[], top=[], leak=[]))
                    st['n'] += 1
                    h = (h_at - 1600) // HOP
                    st['dips'] += sum(1 for t, a in acts if a == HOLD and t < h)
                    stop = next((t for t, a in acts if a == STOP and t * HOP < end), None)
                    duck = max((t for t, a in acts if a == HOLD and (stop is None or t < stop)), default=None)
                    if stop is not None and stop < h:
                        st['early'] += 1
                    elif stop is not None:
                        st['stop'] += 1
                        st['lat_duck'].append((duck * HOP + N - h_at) / 16); st['lat_stop'].append((stop * HOP + N - h_at) / 16)
                        st['levs'].append(b.lev)
                    if not with_asr or level < 0:
                        continue
                    h_end = h_at + hl + 3200
                    t_now = end + MUTE_TAIL_MS * 16
                    hy_now = asr(heard[t_now:h_end]) if t_now < h_end else ''
                    st['rec_now'].append(recall(txt, hy_now))
                    if stop is not None and stop >= h:
                        t_pre = max(0, duck * HOP + N - PRE_MS * 16)
                        upto = duck * HOP + N + STOP_MS * 16 + OPEN_MS * 16 - t_pre
                        hy = asr(gate(heard[t_pre:h_end], ref[t_pre:h_end], b.g, upto))
                    else:
                        hy = hy_now
                    st['rec'].append(recall(txt, hy)); st['leak'].append(recall(tts_txt[id(e)], hy))
                    st['top'].append(recall(txt, asr(hseg[:hl + 4800])))
    print('| громкость | смесей | остановлено | остановлено раньше человека | пауз до человека | подозрение через, мс (медиана · p90) | остановка через, мс | слов человека: сейчас | с перебиванием | человек без озвучки | слов озвучки протекло |')
    print('|---|---|---|---|---|---|---|---|---|---|---|')
    pct = lambda a: f'{np.mean(a) * 100:.0f} %' if a else '—'
    for (v, lv), st in sorted(rows.items()):
        if lv < 0:
            continue
        ld, ls = st['lat_duck'], st['lat_stop']
        print(f'| {v} | {st["n"]} | {st["stop"]} ({st["stop"] / st["n"] * 100:.0f} %) | {st["early"]} | {st["dips"]} | '
              f'{np.median(ld):.0f} · {np.percentile(ld, 90):.0f} | {np.median(ls):.0f} | {pct(st["rec_now"])} | {pct(st["rec"])} | {pct(st["top"])} | {pct(st["leak"])} |'
              if ld else f'| {v} | {st["n"]} | 0 | {st["early"]} | {st["dips"]} | — | — | {pct(st["rec_now"])} | {pct(st["rec"])} | {pct(st["top"])} | {pct(st["leak"])} |')
    print('\nДальний голос (тот же человек на 12 дБ тише — как посторонний в нескольких метрах): остановил озвучку ' + '; '.join(
        f'громкость {v}: {st["stop"]} из {st["n"]}' for (v, lv), st in sorted(rows.items()) if lv < 0))
    for (v, lv), st in sorted(rows.items()):
        if st['levs']:
            print(f'Громкость полос человека при остановке, дБ (громкость {v}, человек {lv:+d} дБ): p10 {np.percentile(st["levs"], 10):.1f} · '
                  f'медиана {np.median(st["levs"]):.1f} · p90 {np.percentile(st["levs"], 90):.1f}')


def gate_ref(mic, ref, g0, act):
    """Подпор вокруг остановки в эталоне: отрезок от PRE_MS до подозрения, погашен до момента, когда
    приглушение уже слышно, + OPEN_MS — как в приложении."""
    duck = max(t for t, a in act if a == HOLD)
    a0 = max(0, duck * HOP + N - PRE_MS * 16); a1 = min(len(mic), a0 + 2 * SR)
    upto = duck * HOP + N + STOP_MS * 16 + OPEN_MS * 16 - a0
    y = gate(mic[a0:a1], ref[a0:a1], g0, upto)
    idx = list(range(0, a1 - a0, 997))
    return {'from': int(a0), 'to': int(a1), 'upto': int(upto), 'samples': [[i, float(y[i])] for i in idx]}


def golden():
    """Эталон для BargeInTest.java: синтетическая «озвучка» (фраза корпуса), её эхо (задержки и отражения),
    щелчок посреди фразы (подозрение без человека → отбой) и человек (другая фраза корпуса) → остановка.
    Звук — как его слышал бы микрофон при действиях датчика (с приглушением), чтобы Java на тех же кадрах
    дала те же действия на тех же шагах."""
    c = f'{R}/bench/air/corpus'
    lst = sorted(f[:-4] for f in os.listdir(f'{c}/pt') if f.endswith('.wav'))
    a, ar = read(f'{c}/pt/{lst[3]}.wav'); b_, br = read(f'{c}/pt/{lst[11]}.wav')
    ref = np.concatenate([resample_poly(a, SR, ar), np.zeros(SR // 5), resample_poly(b_, SR, br)])
    hl = sorted(f[:-4] for f in os.listdir(f'{c}/ru') if f.endswith('.wav'))
    h, hr = read(f'{c}/ru/{hl[5]}.wav'); h = resample_poly(h, SR, hr)
    lead = SR // 2
    n = lead + len(ref) + SR
    r = np.zeros(n); r[lead:lead + len(ref)] = ref
    echo = np.zeros(n)
    for d, g in ((24, 0.5), (190, 0.22), (700, 0.08), (1900, 0.03)):   # прямой путь и отражения
        echo[d:] += g * r[:n - d]
    rng = np.random.default_rng(20261002)
    bg = rng.normal(0, 10 ** (-58 / 20), n)
    echo = echo + bg
    human = np.zeros(n)
    click = lead + int(1.2 * SR); human[click:click + 1200] = rng.normal(0, 0.12, 1200) * np.hanning(1200)   # стук по столу
    h_at = click + 2 * SR                                          # после отбоя щелчка
    h = h[:max(SR, lead + len(ref) - SR // 3 - h_at)]
    human[h_at:h_at + len(h)] += h * 10 ** ((db(echo[h_at:h_at + len(h)]) - db(h)) / 20)     # вровень с эхом
    # усиление тракта — как у приложения, хранится между фразами: выучено на той же озвучке без человека
    _, _, b0 = closed_loop(echo, np.zeros(n), r, bg, 2, None, None)
    g0 = np.round(b0.g, 3)
    acts, heard, b = closed_loop(echo, human, r, bg, 2, g0, None)                            # с нулевого шага, как в Java
    out = os.path.join(R, 'bench/apk/test')
    write(f'{out}/barge_mic.wav', heard); write(f'{out}/barge_ref.wav', r)
    # Java читает записанные 16-битные файлы: эталон считаем по ним же
    mic16, _ = read(f'{out}/barge_mic.wav'); ref16, _ = read(f'{out}/barge_ref.wav')
    Mb, Rb = bands(mic16), bands(ref16)
    b2 = Barge(g0, None)
    sc, act = [], []
    for t in range(len(Mb) - 1):
        a_ = b2.frame(Mb[t], Rb[t + 1])
        sc.append(round(float(b2.score), 6))
        if a_:
            act.append([t, a_])
    json.dump({'clips': [f'pt/{lst[3]}', f'pt/{lst[11]}', f'ru/{hl[5]}'], 'hops': len(Mb) - 1, 'g0': [float(x) for x in g0],
               'click_hop': click // HOP, 'human_hop': h_at // HOP, 'closed_loop': [[int(t), int(a_)] for t, a_ in acts], 'score': sc, 'actions': act,
               'gain_db': [round(float(x), 6) for x in b2.g], 'att_db': [round(float(x), 6) for x in b2.att],
               'band_power_head': [[float(v) for v in Mb[t]] for t in range(40, 44)],
               'gate': gate_ref(mic16, ref16, g0, act)},
              open(f'{out}/barge_golden.json', 'w'), ensure_ascii=False)
    print('действия:', act, '(в замкнутом контуре:', acts, ') · щелчок на шаге', click // HOP, '· человек с шага', h_at // HOP, '· шагов', len(Mb) - 1)


def main():
    if sys.argv[1:] == ['golden']:
        golden(); return
    dirs = [a for a in sys.argv[1:] if not a.startswith('--')]
    recs = [r for d in dirs for r in load(d)]
    if not recs:
        print('записей нет'); return
    print(f'Записей: {len(recs)} из {len(dirs)} прогонов\n')
    section0(recs)
    prior = section1(recs)
    section2(recs, prior)
    section3(recs, prior, with_asr='--no-asr' not in sys.argv)


if __name__ == '__main__':
    main()
