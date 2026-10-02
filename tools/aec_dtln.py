#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Нейросетевой подавитель эха DTLN-aec в ONNX Runtime: потоковый, окно 512 отсчётов со сдвигом 128 (8 мс), 16 кГц.

  .venv/bin/python tools/aec_dtln.py run <микрофон.wav> <опора.wav> <выход.wav> [128|256|512]
  .venv/bin/python tools/aec_dtln.py check                  # свой конвейер против *_processed.wav авторов
  .venv/bin/python tools/aec_dtln.py speed [секунд]         # время на блок 8 мс на этом ПК, ORT в один поток
  ASR_DIR=<parakeet> .venv/bin/python tools/aec_dtln.py eval <каталог итогов> <aec-…> [<aec-…> …]

Модель — N. L. Westhausen, B. T. Meyer, «Acoustic Echo Cancellation with the Dual-Signal Transformation LSTM
Network», ICASSP 2021; github.com/breizhn/DTLN-aec, лицензия MIT, коммит 9d24e12. Рецепт — их run_aec.py
без изменений:
  ступень 1: модули спектра окна 512 (rfft) микрофона и опоры (что играли) → маска на спектр микрофона;
  ступень 2: окно после маски (irfft) и окно опоры во времени → окно выхода; окна складываются внахлёст.
Состояния LSTM — явные входы и выходы (states_in → states_out), их несёт вызывающий от блока к блоку.
Выход отстаёт от входа на 384 отсчёта (24 мс): столько окно ждёт последнего из четырёх перекрытий.
Модели: models/aec/dtln/onnx/dtln_aec_{128,256,512}_{1,2}.onnx — TFLite авторов, переведённые tf2onnx (opset 17).

eval — замеры на записях стенда bench/apk/test_aec_device.sh (разбор — функциями tools/aec_eval.py), отчёт
results/2026-10-02-aec-neural.md. Первый каталог — запись «только эхо» с конфигурацией vr (в неё подмешиваются
фразы корпуса). В каждом каталоге берутся записи vr… без встроенного подавителя: только эхо, человек из колонок
ПК поверх озвучки (есть в near.tsv), только человек (vr_q…). Подавители: без подавителя, свой NLMS 2048 (± подавление
остатка), DTLN 128/256/512; опора — на 50 мс раньше эха (задержка — взаимной корреляцией). Распознанное — тремя
способами: вся запись, нарезка VAD как в приложении, окно фразы человека. Разделы:
  1) только эхо: ERLE, слова озвучки, реплики VAD из остатка;   2) человек поверх озвучки: его слова и слова озвучки;
  3) только человек и чистые фразы корпуса: не портит ли речь;   4) сдвиг опоры;
  5) фразы корпуса в настоящую запись эха, −10…+5 дБ;           6) записи «только человек» в запись эха той же сессии;
  7) во время озвучки: на что срабатывает нарезка (к перебиванию).
Каждый посчитанный результат сразу дописывается в <каталог итогов>/cache.jsonl: прерванный прогон продолжается с места.
"""
import hashlib
import json
import os
import sys
import time

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MD = os.path.join(R, 'models', 'aec', 'dtln', 'onnx')
BLOCK, SHIFT = 512, 128
LAG = BLOCK - SHIFT          # 384 отсчёта: на столько выход отстаёт от входа
SIZES = (128, 256, 512)


class OrtStage:
    """Одна ступень в ONNX Runtime: (сигнал, состояния, опора) → (выход, новые состояния)."""

    def __init__(self, path, threads=1):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self.s = ort.InferenceSession(path, so, providers=['CPUExecutionProvider'])
        self.names = [i.name for i in self.s.get_inputs()]          # сигнал, states_in, опора — как в TFLite
        self.shape = tuple(self.s.get_inputs()[1].shape)

    def __call__(self, x, st, ref):
        y, st = self.s.run(None, {self.names[0]: x, self.names[1]: st, self.names[2]: ref})
        return y, st


class DTLN:
    """Потоковый DTLN-aec. block(128 отсчётов микрофона, 128 отсчётов опоры) → 128 отсчётов выхода (на 384 позже).
    stages — пара ступеней с тем же вызовом, что у OrtStage (для сверки с TFLite); по умолчанию — ONNX размера size."""

    def __init__(self, size=128, threads=1, stages=None):
        self.size = size
        if stages is None:
            stages = tuple(OrtStage(os.path.join(MD, f'dtln_aec_{size}_{k}.onnx'), threads) for k in (1, 2))
        self.m1, self.m2 = stages
        self.trace = None            # список: сюда block() складывает входы и выходы ступеней (сверка)
        self.reset()

    def reset(self):
        self.st1 = np.zeros(self.m1.shape, np.float32)
        self.st2 = np.zeros(self.m2.shape, np.float32)
        self.xin = np.zeros(BLOCK, np.float32)
        self.rin = np.zeros(BLOCK, np.float32)
        self.out = np.zeros(BLOCK, np.float32)

    def block(self, x, r):
        self.xin[:-SHIFT] = self.xin[SHIFT:]; self.xin[-SHIFT:] = x
        self.rin[:-SHIFT] = self.rin[SHIFT:]; self.rin[-SHIFT:] = r
        # БПФ — в float64 с переводом в complex64, как у авторов (numpy 1.x считал так при любом входе);
        # numpy 2 считает complex64 в одинарной точности, и без явного float64 расходился бы сам рецепт.
        X = np.fft.rfft(self.xin.astype(np.float64)).astype(np.complex64)
        mag = np.abs(X).reshape(1, 1, -1).astype(np.float32)
        rmag = np.abs(np.fft.rfft(self.rin.astype(np.float64)).astype(np.complex64)).reshape(1, 1, -1).astype(np.float32)
        st1 = self.st1
        mask, self.st1 = self.m1(mag, st1, rmag)
        est = np.fft.irfft((X * mask.reshape(-1)).astype(np.complex128)).astype(np.float32).reshape(1, 1, -1)
        rin = self.rin.reshape(1, 1, -1).copy()
        st2 = self.st2
        y, self.st2 = self.m2(est, st2, rin)
        if self.trace is not None:
            self.trace.append((mag, st1, rmag, mask, self.st1, est, st2, rin, y, self.st2))
        self.out[:-SHIFT] = self.out[SHIFT:]; self.out[-SHIFT:] = 0
        self.out += y.reshape(-1)
        return self.out[:SHIFT].copy()

    def run(self, mic, ref, aligned=True):
        """Целый сигнал, как run_aec.py: 384 нуля в начале и в конце, выход той же длины, что вход.
        aligned=False — ровно как у авторов (выход позже входа на 384 отсчёта); aligned=True — тот же выход,
        сдвинутый на 384 раньше, чтобы отсчёт выхода стоял против своего отсчёта микрофона (для ERLE по отрезкам).
        Нормировку «если громче 1 — поделить на максимум» из run_aec.py не делаем: она про запись в файл."""
        n = min(len(mic), len(ref))
        tail = 2 * LAG if aligned else LAG
        x = np.concatenate([np.zeros(LAG), mic[:n], np.zeros(tail)]).astype(np.float32)
        r = np.concatenate([np.zeros(LAG), ref[:n], np.zeros(tail)]).astype(np.float32)
        self.reset()
        out = np.zeros(len(x))
        for i in range((len(x) - LAG) // SHIFT):
            j = i * SHIFT
            out[j:j + SHIFT] = self.block(x[j:j + SHIFT], r[j:j + SHIFT])
        s = 2 * LAG if aligned else LAG
        return out[s:s + n]


def place(sig, n, start):
    """Сигнал во времени микрофона длиной n: sig начинается с отсчёта start (start может быть < 0)."""
    out = np.zeros(n, np.float32)
    a, b = max(0, start), min(n, start + len(sig))
    if b > a:
        out[a:b] = sig[a - start:b - start]
    return out


def write(p, x, rate=16000):
    import wave
    y = np.clip(np.round(np.asarray(x, np.float64) * 32768), -32768, 32767).astype('<i2')
    with wave.open(p, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(y.tobytes())


def _ev():
    sys.path.insert(0, os.path.join(R, 'tools'))
    import aec_eval
    return aec_eval


# ---------------------------------------------------------------------------------------------------- check
def check():
    """Конвейер этого файла против *_processed.wav из репозитория (их делал dtln_aec_512 через run_aec.py)."""
    ev = _ev()
    d = os.path.join(R, 'models', 'aec', 'dtln', 'repo', 'audio_samples')
    m = DTLN(512)
    for f in sorted(os.listdir(d)):
        if not f.endswith('_mic.wav'):
            continue
        b = f[:-len('_mic.wav')]
        mic, _ = ev.read(f'{d}/{b}_mic.wav'); lpb, _ = ev.read(f'{d}/{b}_lpb.wav'); ref, _ = ev.read(f'{d}/{b}_processed.wav')
        y = m.run(mic, lpb, aligned=False)
        y16 = np.clip(np.round(y * 32768), -32768, 32767) / 32768    # их файл — PCM 16 бит
        n = min(len(y16), len(ref))
        dif = np.abs(y16[:n] - ref[:n])
        print(f'{b}: отсчётов {n}, макс. разница {dif.max() * 32768:.0f} МЗР (1 МЗР = 1/32768), '
              f'совпало отсчётов {np.mean(dif == 0) * 100:.2f} %, разница к сигналу {ev.db(y16[:n] - ref[:n]) - ev.db(ref[:n]):.1f} дБ')
        # где выход стоит против микрофона: взаимная корреляция выхода и микрофона
        k = int(np.argmax(np.abs(np.correlate(mic[8000:40000], y[8000 + 1000:40000 - 1000], 'valid')))) - 1000
        print(f'   выход против микрофона: сдвиг {-k} отсчётов')


# ---------------------------------------------------------------------------------------------------- speed
def speed(seconds=30.0, sizes=SIZES, threads=1):
    """Время на блок 8 мс (128 отсчётов) на этом ПК: целиком (два БПФ, обе ступени, обратное БПФ) и по ступеням."""
    rng = np.random.default_rng(1)
    n = int(seconds * 16000) // SHIFT
    x = (rng.standard_normal(n * SHIFT) * 0.05).astype(np.float32)
    r = (rng.standard_normal(n * SHIFT) * 0.05).astype(np.float32)
    rows = []
    for s in sizes:
        m = DTLN(s, threads)
        for i in range(300):
            m.block(x[i * SHIFT:(i + 1) * SHIFT], r[i * SHIFT:(i + 1) * SHIFT])
        m.reset()
        t = np.zeros(n)
        for i in range(n):
            t0 = time.perf_counter()
            m.block(x[i * SHIFT:(i + 1) * SHIFT], r[i * SHIFT:(i + 1) * SHIFT])
            t[i] = time.perf_counter() - t0
        a = np.zeros((1, 1, 257), np.float32) + 0.1; e = np.zeros((1, 1, 512), np.float32) + 0.01
        t1 = np.zeros(2000); t2 = np.zeros(2000)
        st1 = np.zeros(m.m1.shape, np.float32); st2 = np.zeros(m.m2.shape, np.float32)
        for i in range(2000):
            t0 = time.perf_counter(); _, st1 = m.m1(a, st1, a); t1[i] = time.perf_counter() - t0
            t0 = time.perf_counter(); _, st2 = m.m2(e, st2, e); t2[i] = time.perf_counter() - t0
        row = dict(size=s, block_us_median=float(np.median(t) * 1e6), block_us_mean=float(np.mean(t) * 1e6),
                   block_us_p99=float(np.percentile(t, 99) * 1e6), stage1_us=float(np.median(t1) * 1e6),
                   stage2_us=float(np.median(t2) * 1e6), rtf=float(np.mean(t) / (SHIFT / 16000)), blocks=n)
        rows.append(row)
        print(f'| {s} | {row["block_us_median"]:.0f} | {row["block_us_mean"]:.0f} | {row["block_us_p99"]:.0f} | '
              f'{row["stage1_us"]:.0f} | {row["stage2_us"]:.0f} | {row["rtf"]:.3f} |', flush=True)
    return rows


# ---------------------------------------------------------------------------------------------------- eval
class Cache:
    """Результаты по ключам в JSONL; каждая строка дописывается сразу, оборванная последняя — пропускается."""

    def __init__(self, path):
        self.path, self.d = path, {}
        if os.path.exists(path):
            raw = open(path, 'rb').read()
            for line in raw.split(b'\n'):
                try:
                    k, v = json.loads(line.decode('utf-8'))
                    self.d[k] = v
                except ValueError:
                    pass
            if raw and not raw.endswith(b'\n'):     # хвост, оборванный перезагрузкой (бывает и нулями) — отрезать
                open(path, 'wb').write(raw[:raw.rfind(b'\n') + 1])

    def get(self, k, f):
        if k not in self.d:
            self.d[k] = f()
            with open(self.path, 'a', encoding='utf-8') as fh:
                fh.write(json.dumps([k, self.d[k]], ensure_ascii=False) + '\n')
        return self.d[k]


def load(d, cfg):
    """Запись стенда: микрофон 16 кГц, что играли (передискретизовано в 16 кГц) и play_sample.
    Опора — своя у конфигурации (aec.json[cfg].ref — прогоны по фразам) или общая aec_ref.wav."""
    from scipy.signal import resample_poly
    ev = _ev()
    meta = json.load(open(os.path.join(d, 'aec.json'), encoding='utf-8'))
    m = meta[cfg]
    ref, rr = ev.read(os.path.join(d, m.get('ref', 'aec_ref.wav')))
    assert rr == m.get('ref_rate', meta.get('ref_rate', rr))
    mic, sr = ev.read(os.path.join(d, f'aec_{cfg}.wav'))
    assert sr == 16000
    return mic, resample_poly(ref, 16000, rr).astype(np.float32), int(m['play_sample'])


def stand(d):
    """Записи стенда по видам: e — только эхо, d — человек из колонок ПК поверх озвучки, q — только человек.
    Берётся только источник VOICE_RECOGNITION без встроенного подавителя (vr…, не vr_aec). Фраза человека — near.tsv."""
    meta = json.load(open(os.path.join(d, 'aec.json'), encoding='utf-8'))
    near = {}
    if os.path.exists(os.path.join(d, 'near.tsv')):
        for line in open(os.path.join(d, 'near.tsv'), encoding='utf-8'):
            c = line.rstrip('\n').split('\t')
            if len(c) >= 2:
                near[c[0]] = c[1]
    out = []
    for cfg, m in meta.items():
        if isinstance(m, dict) and 'play_sample' in m and cfg.startswith('vr') and 'aec' not in cfg:
            out.append((cfg, 'q' if cfg.startswith('vr_q') else 'd' if cfg in near else 'e', near.get(cfg)))
    return out


def clip_at(mic, clip):
    """Где в записи фраза человека: взаимная корреляция с исходным wav корпуса. (начало, длина, сходство)."""
    from scipy.signal import resample_poly
    ev = _ev()
    h, hr = ev.read(clip + '.wav')
    h = h if hr == 16000 else resample_poly(h, 16000, hr).astype(np.float32)
    p, rho = ev.delay(mic, h, 0, 0.0, (len(mic) - len(h)) / 16000 - 0.01)
    return p, len(h), rho


def clip_onset(clip):
    """Где в wav корпуса начинается речь: первый кадр 20 мс не тише громкого (макс − 30 дБ), в отсчётах."""
    ev = _ev()
    h, hr = ev.read(clip + '.wav')
    assert hr == 16000
    e = np.array([np.mean(np.asarray(h[i:i + 320], np.float64) ** 2) for i in range(0, len(h) - 320, 320)])
    return int(np.argmax(e > e.max() * 1e-3) * 320)


def silero_path():
    asr_dir = os.environ.get('ASR_DIR', os.path.join(R, 'models', 'asr_multi'))
    return os.environ.get('SILERO_VAD') or os.path.join(os.path.dirname(os.path.abspath(asr_dir)), 'silero_vad.onnx')


def vad_segments(x, frames=False):
    """Нарезка на реплики, как в приложении (TranslatorService.startVad): кадр 512 отсчётов — речь, если Silero
    (порог 0,5, тишина 0,05 с, речь от 0,25 с) сказал «речь» И кадр громче оценки фона на 6 дБ; в реплику — до 1 с
    до начала речи; конец — после 600 мс без речи, хвост 300 мс; меньше 250 мс речи — выброс. Усиление микрофона
    не моделируется (0 дБ). Конец записи — как тишина после неё. Возвращает [(начало, конец)] в отсчётах;
    frames=True — ещё и [(отсчёт начала кадра, речь ли кадр)] по кадрам записи."""
    import sherpa_onnx
    c = sherpa_onnx.VadModelConfig()
    c.silero_vad.model = silero_path(); c.silero_vad.threshold = 0.5; c.silero_vad.min_silence_duration = 0.05
    c.silero_vad.min_speech_duration = 0.25; c.silero_vad.window_size = 512; c.silero_vad.max_speech_duration = 15
    c.sample_rate = 16000; c.num_threads = 1
    vad = sherpa_onnx.VoiceActivityDetector(c, buffer_size_in_seconds=60)
    F, FMS = 512, 32
    n0 = len(x)
    x = np.concatenate([np.asarray(x, np.float32), np.zeros(int(0.7 * 16000), np.float32)])
    pre, seg, segs, flags = [], [], [], []
    ins, silent, voiced, noise = False, 0, 0, 0.0
    for i in range(0, len(x) - F + 1, F):
        win = x[i:i + F]
        frame = float(np.sqrt(np.mean(win.astype(np.float64) ** 2)))
        vad.accept_waveform(win)
        sp = vad.is_speech_detected()
        while not vad.empty():
            vad.pop()
        if not sp:                                   # фон — только в тишине, вверх медленно (как в приложении)
            if noise == 0:
                noise = frame
            elif frame < noise:
                noise = 0.9 * noise + 0.1 * frame
            else:
                noise = 0.999 * noise + 0.001 * min(frame, 2 * noise)
        speech = sp and (noise == 0 or frame >= noise * 10 ** (6 / 20))
        if i < n0:
            flags.append((i, speech))
        if not ins:
            pre.append(i); del pre[:-max(1, 1000 // FMS)]
            if speech:
                ins, seg, pre, voiced, silent = True, pre, [], 1, 0
            continue
        seg.append(i)
        if speech:
            voiced += 1; silent = 0
        else:
            silent += 1
        if silent * FMS < 600 and len(seg) * FMS < 15000:
            continue
        keep = len(seg) - max(0, silent - 300 // FMS)
        if voiced * FMS >= 250 and keep > 0:
            a, b = seg[0], min(n0, seg[keep - 1] + F)
            if b > a:
                segs.append((int(a), int(b)))
        ins, seg, silent, voiced = False, [], 0, 0
    return (segs, flags) if frames else segs


# Опора для DTLN и NLMS: озвучка ставится на 50 мс раньше эха (задержка эха — взаимной корреляцией по записи;
# приложению её даст отметка времени записи и дорожки). Сдвиг опоры отдельно — в таблице сдвигов.
PRE = 800


def evaluate(out, dirs, sizes=SIZES):
    ev = _ev()
    from scipy.signal import resample_poly
    os.makedirs(out, exist_ok=True)
    C = Cache(os.path.join(out, 'cache.jsonl'))
    res = {}

    def save():
        json.dump(res, open(os.path.join(out, 'eval.json'), 'w'), ensure_ascii=False, indent=1)

    def asr(x):
        x = np.asarray(x, np.float32)
        return C.get('asr:' + hashlib.sha1(x.tobytes()).hexdigest(), lambda: ev.asr(x))

    def asr_vad(y):
        segs = vad_segments(y)
        return segs, ' '.join(t for t in (asr(y[a:b]) for a, b in segs) if t)

    models = {}

    def dtln(s, mic, lpb):
        if s not in models:
            models[s] = DTLN(s)
        return models[s].run(mic, lpb, aligned=True)

    def nlms_res(mic, refal):
        e, yh = ev.nlms(refal, np.asarray(mic, np.float64), 2048, 0.5)
        return e, ev.res(e, yh)

    NAMES = ['без подавителя', 'NLMS 2048', 'NLMS 2048 + подавление остатка'] + [f'DTLN {s}' for s in sizes]

    def outputs(mic, ref16, start, kind):
        """Все подавители на одной записи; опора для NLMS и DTLN — с отсчёта start; у q опора — тишина."""
        n = len(mic)
        lpb = place(ref16, n, start) if kind != 'q' else np.zeros(n, np.float32)
        v = {'без подавителя': mic}
        if kind != 'q':
            e, r = nlms_res(mic, lpb)
            v['NLMS 2048'] = e; v['NLMS 2048 + подавление остатка'] = r
        for s in sizes:
            v[f'DTLN {s}'] = dtln(s, mic, lpb)
        return v

    # ---------- 1. по записям стенда: только эхо, человек поверх озвучки, только человек
    recs = {'e': [], 'd': [], 'q': []}
    for d in dirs:
        for cfg, kind, clip in stand(d):
            recs[kind].append((d, cfg, clip))
    skipped = []
    rows = {'e': {}, 'd': {}, 'q': {}}
    for kind in ('e', 'd', 'q'):
        for d, cfg, clip in recs[kind]:
            key = f'r:{os.path.basename(d)}:{cfg}'
            mic, ref16, at = load(d, cfg)
            n = len(mic)
            k, rho = ev.delay(mic, ref16, at) if kind != 'q' else (0, 0.0)
            seg = slice(at + k, min(n, at + k + len(ref16)))
            if kind != 'q' and (rho < 0.1 or ev.db(mic[seg]) < ev.db(mic[max(0, at - 8000):at]) + 6):
                skipped.append(f'{os.path.basename(d)}/{cfg}: озвучки в записи нет (сходство {rho:.2f}, '
                               f'{ev.db(mic[seg]):.1f} dBFS при фоне {ev.db(mic[max(0, at - 8000):at]):.1f})')
                continue
            names = NAMES if kind != 'q' else ['без подавителя'] + [f'DTLN {s}' for s in sizes]
            if all(f'{key}:{nm}' in C.d for nm in names):
                got = {nm: C.d[f'{key}:{nm}'] for nm in names}
            else:
                vs = outputs(mic, ref16, at + k - PRE, kind)
                tts = asr(ref16) if kind != 'q' else ''
                txt = open(clip + '.txt', encoding='utf-8').read().strip() if clip else ''
                p, L, prho = clip_at(mic, clip) if clip else (0, 0, 0.0)
                got = {}
                for nm in names:
                    def g(y=vs[nm]):
                        r = dict(delay_ms=k / 16, rho=rho, echo_db=ev.db(mic[seg]) if kind != 'q' else None)
                        segs, hv = asr_vad(y)
                        r.update(vad_n=len(segs), vad_s=sum(b - a for a, b in segs) / 16000, vad_hyp=hv)
                        if kind == 'e':
                            tail = slice(max(seg.start, seg.stop - 32000), seg.stop)
                            hw = asr(y[seg.start - 1600:seg.stop + 3200])
                            r.update(erle=[ev.db(mic[seg]) - ev.db(y[seg]), ev.db(mic[tail]) - ev.db(y[tail])],
                                     hyp=hw, lk=ev.recall(tts, hw), vad_lk=ev.recall(tts, hv))
                        else:
                            hw = asr(y[max(0, at - 1600):])          # как near() в aec_eval
                            hp = asr(y[max(0, p - 4800):p + L + 4800])
                            r.update(hyp=hw, rc=ev.recall(txt, hw), vad_rc=ev.recall(txt, hv), p_hyp=hp,
                                     p_rc=ev.recall(txt, hp), clip_s=[p / 16000, L / 16000, prho],
                                     person_db=ev.db(mic[p:p + L]))
                            if kind == 'd':
                                r.update(lk=ev.recall(tts, hw), vad_lk=ev.recall(tts, hv), p_lk=ev.recall(tts, hp))
                        return r
                    got[nm] = C.get(f'{key}:{nm}', g)
            rows[kind][f'{os.path.basename(d)}/{cfg}'] = got
            print(f'   {kind} {os.path.basename(d)}/{cfg}: ' + ' · '.join(
                f'{nm}: ' + (f'ERLE {got[nm]["erle"][0]:.1f}' if kind == 'e' else f'{got[nm]["rc"] * 100:.0f}/{got[nm]["vad_rc"] * 100:.0f}/{got[nm]["p_rc"] * 100:.0f}')
                for nm in names), flush=True)
    res['rows'] = rows; res['skipped'] = skipped
    save()
    vol = {}
    for d in dirs:
        m = json.load(open(os.path.join(d, 'aec.json'), encoding='utf-8'))
        vol[os.path.basename(d)] = m.get('volume', '?')            # старые прогоны громкость не записывали

    print('\n## 1. Только эхо: ERLE и что осталось от озвучки\n')
    for s in skipped:
        print('Пропущено:', s)
    print(f'\nЗаписей: {len(rows["e"])}; громкость: ' + ', '.join(f'{k} — {v}/15' for k, v in vol.items()) + '\n')
    print('| подавитель | ERLE по фразе, дБ: медиана (мин–макс) | ERLE за последние 2 с, медиана | слов озвучки в распознанном, среднее | VAD нарезал реплик из остатка эха (записей с репликой) |')
    print('|---|---|---|---|---|')
    for nm in NAMES:
        v = [r[nm] for r in rows['e'].values()]
        e1 = [x['erle'][0] for x in v]; e2 = [x['erle'][1] for x in v]
        print(f'| {nm} | {np.median(e1):.1f} ({min(e1):.1f}–{max(e1):.1f}) | {np.median(e2):.1f} | {np.mean([x["lk"] for x in v]) * 100:.0f} % | '
              f'{sum(x["vad_n"] for x in v)} ({sum(x["vad_n"] > 0 for x in v)} из {len(v)}) |')

    print('\n## 2. Человек из колонок ПК поверх озвучки (настоящий двойной разговор)\n')
    print(f'Записей: {len(rows["d"])}. Его слов в распознанном — три способа подать звук распознаванию: '
          f'вся запись (как near() в aec_eval) · нарезка VAD как в приложении · только окно его фразы (±0,3 с, предел)\n')
    print('| подавитель | его слов: вся запись | нарезка VAD | окно фразы | записей, где узнано хоть слово (вся / VAD / окно) | слов озвучки: вся / VAD |')
    print('|---|---|---|---|---|---|')
    for nm in NAMES:
        v = list(rows['d'][r][nm] for r in rows['d'])
        f = lambda key: np.mean([x[key] for x in v]) * 100
        cnt = '/'.join(str(sum(x[key] > 0 for x in v)) for key in ('rc', 'vad_rc', 'p_rc'))
        print(f'| {nm} | {f("rc"):.0f} % | {f("vad_rc"):.0f} % | {f("p_rc"):.0f} % | {cnt} из {len(v)} | {f("lk"):.0f} % / {f("vad_lk"):.0f} % |')
    print('\nПо записям (его слов: вся / VAD / окно):\n')
    print('| запись | ' + ' | '.join(NAMES) + ' |')       # человек к эху по записям — в разделе 7
    print('|---|' + '---|' * len(NAMES))
    for r, got in rows['d'].items():
        print(f'| {r} | ' +
              ' | '.join(f'{got[nm]["rc"] * 100:.0f}/{got[nm]["vad_rc"] * 100:.0f}/{got[nm]["p_rc"] * 100:.0f}' for nm in NAMES) + ' |')

    clips = []
    for lang in ('ru', 'pt'):
        for line in open(f'{R}/bench/air/corpus/{lang}/LICENSES.tsv', encoding='utf-8'):
            clips.append(f'{R}/bench/air/corpus/{lang}/{line.split(chr(9))[0]}')
    rng = np.random.default_rng(20261002)     # тот же выбор, что в разделе 3 aec_eval
    pick = [clips[i] for i in rng.choice(len(clips), 12, replace=False)]

    def clip16(c):
        h, hr = ev.read(c + '.wav')
        return h if hr == 16000 else resample_poly(h, 16000, hr).astype(np.float32)
    clean = {}                                # те же 12 фраз корпуса — чистые, по 0,5 с тишины с боков, опора — тишина
    for nm in ['без подавителя'] + [f'DTLN {s}' for s in sizes]:
        v = []
        for c in pick:
            def g(c=c, nm=nm):
                h = np.concatenate([np.zeros(8000, np.float32), clip16(c), np.zeros(8000, np.float32)])
                y = h if nm == 'без подавителя' else dtln(int(nm.split()[1]), h, np.zeros(len(h), np.float32))
                hyp = asr(y)
                return dict(rc=ev.recall(open(c + '.txt', encoding='utf-8').read(), hyp), hyp=hyp)
            v.append(C.get(f'c:clean:{os.path.basename(c)}:{nm}', g)['rc'])
        clean[nm] = v
    res['clean'] = clean
    print('\n## 3. Человек без озвучки: портит ли подавитель чистую речь (опора — тишина)\n')
    qn = ['без подавителя'] + [f'DTLN {s}' for s in sizes]
    print(f'| подавитель | записи телефона ({len(rows["q"])}): его слов, вся запись | нарезка VAD | окно фразы | '
          f'{len(pick)} чистых фраз корпуса: медиана · среднее |')
    print('|---|---|---|---|---|')
    for nm in qn:
        v = [rows['q'][r][nm] for r in rows['q']]
        print(f'| {nm} | ' + ' | '.join(f'{np.mean([x[key] for x in v]) * 100:.0f} %' for key in ('rc', 'vad_rc', 'p_rc')) +
              f' | {np.median(clean[nm]) * 100:.0f} % · {np.mean(clean[nm]) * 100:.0f} % |')
    print('\n| запись | ' + ' | '.join(qn) + ' |')
    print('|---|' + '---|' * len(qn))
    for r, got in rows['q'].items():
        print(f'| {r} | ' + ' | '.join(f'{got[nm]["rc"] * 100:.0f}/{got[nm]["vad_rc"] * 100:.0f}/{got[nm]["p_rc"] * 100:.0f}' for nm in qn) + ' |')

    # ---------- 4. сдвиг опоры на всех записях «только эхо»
    leads = (-16, 0, 16, 50, 100, 150, 200, 250, 'ps')     # ps — опора по play_sample, без учёта задержки
    sw = {}
    for r in rows['e']:
        d = next(x for x in dirs if os.path.basename(x) == r.split('/')[0]); cfg = r.split('/')[1]
        mic, ref16, at = load(d, cfg)
        k, _ = ev.delay(mic, ref16, at)
        seg = slice(at + k, min(len(mic), at + k + len(ref16)))
        for s in sizes:
            for lead in leads:
                def f(s=s, lead=lead, mic=mic, ref16=ref16, at=at, k=k, seg=seg):
                    y = dtln(s, mic, place(ref16, len(mic), at if lead == 'ps' else at + k - lead * 16))
                    return ev.db(mic[seg]) - ev.db(y[seg])
                sw[(r, s, lead)] = C.get(f'sw:{r}:{s}:{lead}', f)
    res['sweep'] = {f'{r}|{s}|{l}': v for (r, s, l), v in sw.items()}
    save()
    print(f'\n## 4. Сдвиг опоры: на сколько мс опора раньше эха — ERLE по фразе, медиана (мин–макс) по {len(rows["e"])} записям\n')
    print('| опора раньше эха, мс | ' + ' | '.join(f'DTLN {s}' for s in sizes) + ' |')
    print('|---|' + '---|' * len(sizes))
    for lead in leads:
        cells = []
        for s in sizes:
            v = [sw[(r, s, lead)] for r in rows['e']]
            cells.append(f'{np.median(v):.1f} ({min(v):.1f}–{max(v):.1f})')
        print(f'| {"по play_sample (задержка эха целиком)" if lead == "ps" else lead} | ' + ' | '.join(cells) + ' |')

    # ---------- 5. смеси: фразы корпуса в настоящую запись эха (как раздел 3 aec_eval)
    base = dirs[0]
    mic, ref16, at = load(base, 'vr')
    n = len(mic)
    k, _ = ev.delay(mic, ref16, at)
    seg = slice(at + k, at + k + len(ref16))
    echo_rms = 10 ** (ev.db(mic[seg]) / 20)
    tts_words = asr(mic[seg])                 # как раздел 3 aec_eval: слова озвучки — распознанное эхо

    def mix_score(key, x, win, pwin, txt):
        if all(f'{key}:{nm}' in C.d for nm in NAMES):
            return {nm: C.d[f'{key}:{nm}'] for nm in NAMES}
        vs = outputs(x, ref16, at + k - PRE, 'd')
        got = {}
        for nm in NAMES:
            def g(y=vs[nm]):
                hw = asr(y[win]); hp = asr(y[pwin]); segs, hv = asr_vad(y)
                return dict(rc=ev.recall(txt, hw), lk=ev.recall(tts_words, hw), hyp=hw, vad_rc=ev.recall(txt, hv),
                            vad_lk=ev.recall(tts_words, hv), vad_n=len(segs), p_rc=ev.recall(txt, hp), p_lk=ev.recall(tts_words, hp))
            got[nm] = C.get(f'{key}:{nm}', g)
        return got

    rows_m = {}
    for r in (-10, -5, 0, 5):
        acc = []
        for c in pick:
            h = clip16(c)
            h = h * (echo_rms * 10 ** (r / 20) / (10 ** (ev.db(h) / 20)))
            x = mic.copy(); s1 = seg.start + 16000
            L = min(len(h), len(x) - s1); x[s1:s1 + L] += h[:L]
            win = slice(seg.start, max(seg.stop, s1 + L) + 3200)
            acc.append(mix_score(f'm:{r}:{os.path.basename(c)}', x, win, slice(s1 - 4800, s1 + L + 4800),
                                 open(c + '.txt', encoding='utf-8').read()))
        rows_m[r] = acc
        print(f'   смеси {r:+d} дБ посчитаны', flush=True)
    res['mix'] = rows_m
    save()
    print(f'\n## 5. Смеси: {len(pick)} фраз корпуса цифрово подмешаны в настоящую запись эха '
          f'({os.path.basename(base)}/aec_vr.wav) через 1 с после его начала\n')
    print('Его слов: медиана (среднее) по фразам. Вся запись — окно раздела 3 aec_eval; VAD — нарезка как в приложении.\n')
    print('| подавитель | ' + ' | '.join(f'{r:+d} дБ: вся · VAD' for r in rows_m) + ' | слов озвучки при 0 дБ: вся · VAD |')
    print('|---|' + '---|' * len(rows_m) + '---|')
    for nm in NAMES:
        cells = []
        for r, acc in rows_m.items():
            a = [x[nm]['rc'] for x in acc]; b = [x[nm]['vad_rc'] for x in acc]
            cells.append(f'{np.median(a) * 100:.0f} ({np.mean(a) * 100:.0f}) · {np.median(b) * 100:.0f} ({np.mean(b) * 100:.0f})')
        z = rows_m[0]
        print(f'| {nm} | ' + ' | '.join(cells) + f' | {np.mean([x[nm]["lk"] for x in z]) * 100:.0f} · {np.mean([x[nm]["vad_lk"] for x in z]) * 100:.0f} % |')

    # ---------- 6. настоящие записи человека без озвучки — в настоящую запись эха той же сессии, уровень как записан
    rows_q = {}
    for d in dirs:
        qs = [(cfg, clip) for cfg, kind, clip in stand(d) if kind == 'q']
        if not qs:
            continue
        es = [cfg for cfg, kind, _ in stand(d) if kind == 'e' and cfg in ('vr_e0',)]
        bd, bc = (d, es[0]) if es else (base, 'vr')
        emic, eref, eat = load(bd, bc)
        ek, _ = ev.delay(emic, eref, eat)
        eseg = slice(eat + ek, eat + ek + len(eref))
        tw = asr(emic[eseg])
        for cfg, clip in qs:
            pm, _, _ = load(d, cfg)
            p, L, _ = clip_at(pm, clip)
            cut = pm[max(0, p - 4800):p + L + 4800]
            ratio = ev.db(pm[p:p + L]) - ev.db(emic[eseg])
            txt = open(clip + '.txt', encoding='utf-8').read().strip()
            for off in (0.5, 1.5, 3.0):
                x = emic.copy(); s1 = eseg.start + int(off * 16000)
                Lc = min(len(cut), len(x) - s1); x[s1:s1 + Lc] += cut[:Lc]
                win = slice(eseg.start, max(eseg.stop, s1 + Lc) + 3200)
                key = f'q2:{os.path.basename(d)}:{cfg}:{off}'
                if all(f'{key}:{nm}' in C.d for nm in NAMES):
                    got = {nm: C.d[f'{key}:{nm}'] for nm in NAMES}
                else:
                    vs = outputs(x, eref, eat + ek - PRE, 'd')
                    got = {}
                    for nm in NAMES:
                        def g(y=vs[nm]):
                            hw = asr(y[win]); hp = asr(y[s1:s1 + Lc]); segs, hv = asr_vad(y)
                            return dict(rc=ev.recall(txt, hw), lk=ev.recall(tw, hw), vad_rc=ev.recall(txt, hv),
                                        vad_lk=ev.recall(tw, hv), p_rc=ev.recall(txt, hp), hyp=hw, vad_hyp=hv)
                        got[nm] = C.get(f'{key}:{nm}', g)
                rows_q[f'{os.path.basename(d)}/{cfg}@{off}'] = dict(ratio_db=ratio, echo=f'{os.path.basename(bd)}/{bc}', **got)
    res['qmix'] = rows_q
    save()
    print(f'\n## 6. Записи «только человек» (телефон) цифрово подмешаны в запись эха той же сессии: '
          f'{len(rows_q)} смесей, уровень как записан\n')
    rs = [v['ratio_db'] for v in rows_q.values()]
    print(f'Человек к эху: {min(rs):+.1f}…{max(rs):+.1f} дБ\n')
    print('| подавитель | его слов: вся запись | нарезка VAD | окно фразы | слов озвучки: вся · VAD |')
    print('|---|---|---|---|---|')
    for nm in NAMES:
        v = [x[nm] for x in rows_q.values()]
        print(f'| {nm} | ' + ' | '.join(f'{np.mean([x[key] for x in v]) * 100:.0f} %' for key in ('rc', 'vad_rc', 'p_rc')) +
              f' | {np.mean([x["lk"] for x in v]) * 100:.0f} · {np.mean([x["vad_lk"] for x in v]) * 100:.0f} % |')
    # ---------- 7. Во время озвучки: на что срабатывает нарезка (для перебивания)
    gain = {}                                 # насколько громче своего wav человек слышен телефону, по записям «только человек»
    for d in dirs:
        g = []
        for cfg, kind, clip in stand(d):
            if kind == 'q':
                pm, _, at = load(d, cfg); p, L, _ = clip_at(pm, clip)
                h, _ = ev.read(clip + '.wav')
                pw = np.mean(np.asarray(pm[p:p + L], np.float64) ** 2) - np.mean(np.asarray(pm[:at], np.float64) ** 2)
                g.append(10 * np.log10(max(pw, 1e-12)) - ev.db(h))
        if g:
            gain[os.path.basename(d)] = float(np.mean(g))
    res['person_gain_db'] = gain
    bar = {}
    for kind in ('e', 'd'):
        for r in rows[kind]:
            d = next(x for x in dirs if os.path.basename(x) == r.split('/')[0]); cfg = r.split('/')[1]
            clip = next((c for cf, kd, c in stand(d) if cf == cfg), None)

            def f(d=d, cfg=cfg, kind=kind, clip=clip):
                mic, ref16, at = load(d, cfg)
                k, _ = ev.delay(mic, ref16, at)
                e0, e1 = at + k, min(len(mic), at + k + len(ref16))         # где в записи эхо озвучки
                vs = outputs(mic, ref16, at + k - PRE, kind)
                o = dict(echo=[e0 / 16000, e1 / 16000])
                if kind == 'd':
                    p, L, _ = clip_at(mic, clip)
                    on = p + clip_onset(clip)
                    h, _ = ev.read(clip + '.wav')
                    pp = 10 ** ((ev.db(h) + gain.get(os.path.basename(d), np.nan)) / 10)
                    ov = max(0, min(e1, p + L) - max(e0, p)) / max(1, e1 - e0)
                    epow = max(1e-12, np.mean(np.asarray(mic[e0:e1], np.float64) ** 2) - pp * ov)
                    o.update(onset=on / 16000, end=(p + L) / 16000, person_db=10 * np.log10(pp), echo_db=10 * np.log10(epow))
                for nm in NAMES:
                    segs, fl = vad_segments(vs[nm], frames=True)
                    sp = [i for i, s in fl if s and e0 <= i < e1]              # кадры «речь» во время эха
                    v = dict(speech_s=len(sp) * 512 / 16000)
                    if kind == 'e':
                        v['first'] = (sp[0] - e0) / 16000 if sp else None
                    else:
                        before = [i for i in sp if i < on]
                        after = [i for i, s in fl if s and i >= on]
                        v.update(false_s=len(before) * 512 / 16000, detect=(after[0] - on) / 16000 if after else None,
                                 during_tts=bool(after and after[0] < e1))
                    o[nm] = v
                return o
            bar[r] = C.get(f'b7:{r}', f)
    res['barge'] = bar
    save()
    print('\n## 7. Во время озвучки: срабатывает ли нарезка VAD на остаток эха и на человека (к перебиванию)\n')
    ne = [r for r in bar if r in rows['e']]; nd = [r for r in bar if r in rows['d']]
    print(f'Только эхо — {len(ne)} записей, человек поверх озвучки — {len(nd)}. «Речь» — кадр, который приложение '
          f'сочло бы речью (Silero И громче фона на 6 дБ), только пока звучит эхо озвучки.\n')
    print('| подавитель | только эхо: записей с «речью» · всего «речи», с | человек поверх: «речь» до его начала (записей · с) | '
          'его начало замечено (записей) · медиана задержки, с | замечено, пока озвучка ещё звучала |')
    print('|---|---|---|---|---|')
    for nm in NAMES:
        e = [bar[r][nm] for r in ne]; dd = [bar[r][nm] for r in nd]
        det = [x['detect'] for x in dd if x['detect'] is not None]
        print(f'| {nm} | {sum(x["speech_s"] > 0 for x in e)} из {len(e)} · {sum(x["speech_s"] for x in e):.1f} | '
              f'{sum(x["false_s"] > 0 for x in dd)} из {len(dd)} · {sum(x["false_s"] for x in dd):.1f} | '
              f'{len(det)} из {len(dd)} · {np.median(det) if det else float("nan"):.2f} | {sum(x["during_tts"] for x in dd)} из {len(dd)} |')
    print('\nЧеловек к эху в записях «человек поверх озвучки» (оценка: уровень его wav + усиление колонки→телефон по записям '
          '«только человек» той же сессии; эхо — запись за вычетом этой оценки):\n')
    print('| сессия | колонка→телефон, дБ к wav | человек к эху, дБ: по записям |')
    print('|---|---|---|')
    for sname in sorted({r.split('/')[0] for r in nd}):
        rr = [bar[r] for r in nd if r.startswith(sname)]
        print(f'| {sname} | {gain.get(sname, float("nan")):+.1f} | ' + ', '.join(f'{x["person_db"] - x["echo_db"]:+.0f}' for x in rr) + ' |')
    return res


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__); sys.exit(1)
    if a[0] == 'run':
        ev = _ev()
        mic, r1 = ev.read(a[1]); ref, r2 = ev.read(a[2])
        assert r1 == r2 == 16000, 'нужно 16 кГц'
        y = DTLN(int(a[4]) if len(a) > 4 else 128).run(mic, ref, aligned=False)
        write(a[3], y)
    elif a[0] == 'check':
        check()
    elif a[0] == 'speed':
        print('| размер | блок, мкс: медиана | среднее | 99-й перцентиль | ступень 1 | ступень 2 | доля реального времени |')
        print('|---|---|---|---|---|---|---|')
        speed(float(a[1]) if len(a) > 1 else 30.0)
    elif a[0] == 'eval':
        evaluate(a[1], a[2:])
    else:
        print(__doc__); sys.exit(1)


if __name__ == '__main__':
    main()
