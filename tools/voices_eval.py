#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Голоса разговора: насколько надёжно отпечаток CAM++ различает людей на живой речи (E1–E6).

  .venv/bin/python tools/voices_eval.py all              # все этапы по порядку
  .venv/bin/python tools/voices_eval.py e2 e3            # отдельные этапы
  .venv/bin/python tools/voices_eval.py --redo e2        # пересчитать таблицы этапа (отпечатки — из кэша)
  .venv/bin/python tools/voices_eval.py status           # сколько отпечатков в кэше

Этапы:
  data — корпус (кто говорит в каждой записи) и привязка записей комнаты к проигранным файлам;
  e1   — косинусы «тот же человек» и «другой» по условиям записи, EER, FRR/FAR по порогам;
         в начале — сверка с путём sherpa-onnx, которым приложение считало отпечаток до правки;
  pad  — как отпечаток зависит от тишины и тихого шума по краям сегмента;
  e2   — разговор: слепки 2–3 человек из ближних фраз, опознание дальних сегментов и чужих голосов;
  e3   — то же при длине сегмента 0,8…3 с;
  e4   — двое подряд в одном сегменте: целиком и скользящим окном;
  e5   — одновременная речь двоих;
  e6   — время счёта отпечатка на ПК и что есть в sherpa-onnx для диаризации.

Отпечаток — эталон tools/voiceprint_ref.py (признаки по рецепту 3D-Speaker, модель через ONNX
Runtime, 2 потока): на него переходит приложение. Без нулевого хвоста; единичная длина,
сравнение — скалярное произведение (косинус). Сегмент короче 0,6 с не считается — как в приложении.
Путь приложения до правки (SpeakerEmbeddingExtractor sherpa-onnx, сегмент + 0,5 с нулей) считается
только для сверочной таблицы в E1 и для времени в E6: людей он почти не различает.

Кэш — каталог voices-cache рядом с деревом git (по умолчанию ../voices-cache от корня дерева,
переопределяется VOICES_CACHE): отпечатки в emb.sqlite (ключ — точное описание звука, включая
положение в записи), корпус и привязка в inventory.json и align.json, итоги этапов в sections/.
Готовое не пересчитывается; прерванный прогон продолжается с места остановки. Записи комнаты
читаются кусками и целиком в память не грузятся; счёт идёт в один процесс.

Отчёт results/2026-10-01-voices.md: таблицы этапа вписываются между метками
<!-- auto:имя --> … <!-- /auto:имя -->, текст вне меток не трогается.
Звук корпуса в отчёт не попадает — только числа (часть записей под CC BY-NC, только для стенда).
"""
import os
# Машина общая: numpy (BLAS, OpenMP) по умолчанию берёт все ядра на каждое умножение матриц —
# в первом прогоне E4 это дало ~10 ядер вместо двух. Один поток на BLAS, два — на ONNX Runtime.
for _v in ('OMP_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'MKL_NUM_THREADS', 'NUMEXPR_NUM_THREADS'):
    os.environ.setdefault(_v, '1')
import argparse
import bz2
import collections
import functools
import glob
import hashlib
import json
import math
import os
import random
import sqlite3
import sys
import time
import wave

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.environ.get('VOICES_CACHE') or os.path.join(os.path.dirname(R), 'voices-cache')
REPORT = os.path.join(R, 'results', '2026-10-01-voices.md')
MODEL = os.path.join(R, 'models', 'speaker', '3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx')
CORPUS = os.path.join(R, 'bench', 'air', 'corpus')
REC = os.path.join(R, 'bench', 'air', 'rec')
TAT = os.path.join(R, 'data', 'tatoeba', 'raw')

SR = 16000
MIN_SEC = 0.6                     # Speaker.MIN_SECONDS
SEED = 20261001
TS = [round(0.30 + 0.05 * i, 2) for i in range(10)]          # пороги 0,30…0,75 (E1)
T2 = [round(0.20 + 0.05 * i, 2) for i in range(9)]           # 0,20…0,60: у эталона рабочая область ниже 0,30
RECS = ['near-pt', 'far-pt', 'noisy-pt', 'near-ru', 'far-ru']  # fast-pt не берём: README записей
LANGS = ('pt', 'ru')
ENR_MAX = 3        # фраз FALAR на слепок (E2 сравнивает 1, 2 и 3)
NEED = ENR_MAX + 1  # диктор годится в участники разговора, если записей хотя бы столько
CAP = 6            # проверочных фраз одного диктора в одном разговоре (Inego — 53 записи из 80)
TRIALS = 300       # разговоров на каждую расстановку
CONFIGS = [('2 pt + 1 ru', 2, 1), ('1 pt + 1 ru', 1, 1), ('2 ru + 1 pt', 1, 2)]
DURS = [0.8, 1.0, 1.5, 2.0, 3.0]
WINS = [1.0, 1.5, 2.0]
HOPS = [0.25, 0.5]
E4_N = {'AB': 120, 'AC': 80, 'AA': 80}   # образцов на каждый вариант паузы; AA — один участник, две фразы (контроль)
E5_N = {'AB': 150, 'AC': 100, 'part': 100}
SIRS = [0, 6, 12]
T_PART = 0.35     # порог окна в E5 (частичное наложение): окна 1–1,5 с, по E3 рабочая область 0,35…0,40
T_W5 = 0.40       # порог целого сегмента в E5: по E2 рабочая точка слушания около 0,40–0,43


# ---------------------------------------------------------------- общее

def cpath(*p):
    return os.path.join(CACHE, *p)


def load_json(p, default=None):
    if not os.path.exists(p):
        return default
    with open(p, encoding='utf-8') as f:
        return json.load(f)


def save_json(p, obj):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    tmp = p + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    os.replace(tmp, p)


def log(*a):
    msg = ' '.join(str(x) for x in a)
    print(msg, flush=True)
    try:
        with open(cpath('run.log'), 'a', encoding='utf-8') as f:
            f.write(time.strftime('%H:%M:%S ') + msg + '\n')
    except OSError:
        pass


def fnum(x, nd=2):
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return '—'
    s = '%.*f' % (nd, x)
    if float(s) == 0:
        s = s.lstrip('-')                     # не «−0»
    return s.replace('-', '−').replace('.', ',')


def pct(x, nd=1):
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return '—'
    return fnum(100.0 * x, nd) + '%'


def ipct(x):
    """Доля целым процентом; меньше половины процента, но не ноль — «<1»."""
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return '—'
    if 0 < x < 0.005:
        return '<1'
    return '%d' % round(100.0 * x)


def sci(x):
    """5e-07 → 5·10⁻⁷"""
    m, e = ('%.0e' % x).split('e')
    return '%s·10%s' % (m, str(int(e)).translate(str.maketrans('-0123456789', '⁻⁰¹²³⁴⁵⁶⁷⁸⁹')))


def unit(v):
    v = np.asarray(v, dtype=np.float32)
    n = float(np.linalg.norm(v))
    return v / n if n > 0 else v


def q(a, p):
    return float(np.percentile(a, p)) if len(a) else float('nan')


def db(x):
    r = float(np.sqrt(np.mean(np.square(x, dtype=np.float64)))) if len(x) else 0.0
    return 20 * math.log10(r) if r > 0 else -120.0


def table(head, rows):
    out = ['| ' + ' | '.join(head) + ' |', '|' + '|'.join('---' for _ in head) + '|']
    out += ['| ' + ' | '.join(str(c) for c in r) + ' |' for r in rows]
    return '\n'.join(out)


# ---------------------------------------------------------------- звук

def read_wav(path, s0=None, s1=None):
    """Кусок wav [s0, s1) в отсчётах; за краями файла — нули. Файл целиком не читается."""
    with wave.open(path) as w:
        if w.getframerate() != SR or w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise SystemExit('ожидался 16 кГц моно 16 бит: ' + path)
        n = w.getnframes()
        if s0 is None:
            s0, s1 = 0, n
        x = np.zeros(max(0, s1 - s0), np.float32)
        a, b = max(0, s0), min(n, s1)
        if b > a:
            w.setpos(a)
            x[a - s0:b - s0] = np.frombuffer(w.readframes(b - a), '<i2').astype(np.float32) / 32768.0
    return x


@functools.lru_cache(maxsize=64)
def clean(lang, cid):
    return read_wav(os.path.join(CORPUS, lang, cid + '.wav'))


def room(rec, s0, s1):
    return read_wav(os.path.join(REC, rec, 'room.wav'), s0, s1)


def frames_db(x, fr, hop):
    if len(x) < fr:
        return np.array([db(x)])
    nf = 1 + (len(x) - fr) // hop
    out = np.empty(nf)
    for i in range(nf):
        seg = x[i * hop:i * hop + fr]
        out[i] = 10 * math.log10(float(np.mean(np.square(seg, dtype=np.float64))) + 1e-10)
    return out


def bg_character(x):
    """Чем звучит фон: спектральная плоскость среднего спектра (1 — белый шум, около 0 — тон),
    доля энергии в полосах 150–300 Гц и 1,2–2,5 кГц, разброс громкости по кадрам 50 мс."""
    x = x.astype(np.float64)
    n, hop = 512, 256
    win = np.hanning(n)
    S = np.zeros(n // 2 + 1)
    k = 0
    for i in range(0, len(x) - n + 1, hop):
        S += np.abs(np.fft.rfft(x[i:i + n] * win)) ** 2
        k += 1
    S = S[2:] / max(k, 1)
    f = np.fft.rfftfreq(n, 1.0 / SR)[2:]
    flat = float(np.exp(np.mean(np.log(S + 1e-20))) / np.mean(S))
    tot = float(S.sum()) + 1e-20
    b1 = float(S[(f >= 150) & (f < 300)].sum()) / tot
    b2 = float(S[(f >= 1200) & (f < 2500)].sum()) / tot
    sd = float(np.std(frames_db(x.astype(np.float32), 800, 800)))
    return flat, b1, b2, sd


def speech_bounds(x):
    """Начало и конец речи в чистой записи: кадры 25 мс не тише (p95 − 30 дБ) и (p10 + 10 дБ)."""
    fr, hop = 400, 160
    e = frames_db(x, fr, hop)
    thr = max(np.percentile(e, 95) - 30.0, np.percentile(e, 10) + 10.0)
    on = np.nonzero(e >= thr)[0]
    if len(on) == 0:
        return 0, len(x)
    return int(on[0] * hop), int(min(len(x), on[-1] * hop + fr))


# ---------------------------------------------------------------- отпечатки

def embed_sherpa(ex, x):
    """Путь приложения до правки (Speaker.embed): sherpa-onnx, сегмент + 0,5 с нулей, compute."""
    if x is None or len(x) < int(MIN_SEC * SR):
        return None
    s = ex.create_stream()
    s.accept_waveform(SR, np.ascontiguousarray(x, dtype=np.float32))
    s.accept_waveform(SR, np.zeros(SR // 2, np.float32))
    s.input_finished()
    if not ex.is_ready(s):
        return None
    return unit(np.asarray(ex.compute(s), dtype=np.float32))


def embed_ref(x):
    """Эталон tools/voiceprint_ref.py (на него переходит приложение); без нулевого хвоста."""
    if x is None or len(x) < int(MIN_SEC * SR):
        return None
    import voiceprint_ref
    e = voiceprint_ref.embed(np.asarray(x, dtype=np.float32))
    return None if e is None else e.astype(np.float32)


# Ключ кэша начинается с имени пути: отпечатки sherpa (ключи без приставки — так их считал первый
# прогон) и эталона не смешиваются.
KEY_PREFIX = {'ref': 'ref1|', 'sherpa': ''}


class Emb:
    """Кэш отпечатков в sqlite: ключ — sha1 пути и описания звука. Пишется каждые 25 новых."""

    def __init__(self):
        os.makedirs(CACHE, exist_ok=True)
        self.db = sqlite3.connect(cpath('emb.sqlite'))
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('CREATE TABLE IF NOT EXISTS emb (k TEXT PRIMARY KEY, v BLOB)')
        self.ex = None
        self.new = 0
        self.t_new = 0.0

    def extractor(self):
        if self.ex is None:
            import sherpa_onnx
            self.ex = sherpa_onnx.SpeakerEmbeddingExtractor(
                sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=MODEL, num_threads=2))
        return self.ex

    def get(self, spec, audio, backend='ref'):
        k = hashlib.sha1((KEY_PREFIX[backend] + spec).encode('utf-8')).hexdigest()
        r = self.db.execute('SELECT v FROM emb WHERE k=?', (k,)).fetchone()
        if r is not None:
            return None if len(r[0]) == 0 else np.frombuffer(r[0], dtype=np.float32)
        x = audio() if callable(audio) else audio
        t = time.time()
        e = embed_ref(x) if backend == 'ref' else embed_sherpa(self.extractor(), x)
        self.t_new += time.time() - t
        self.db.execute('INSERT OR REPLACE INTO emb VALUES (?, ?)',
                        (k, b'' if e is None else e.astype(np.float32).tobytes()))
        self.new += 1
        if self.new % 25 == 0:
            self.db.commit()
        if self.new % 500 == 0:
            log('  … новых отпечатков %d (%.0f с счёта)' % (self.new, self.t_new))
        return e

    def count(self):
        return self.db.execute('SELECT COUNT(*) FROM emb').fetchone()[0]

    def close(self):
        self.db.commit()
        self.db.close()


# ---------------------------------------------------------------- отчёт

HEADER = """# Голоса разговора: отпечаток голоса на живой речи (01.10.2026)

Замер к «голосам разговора»: человек, сказавший фразу кнопкой FALAR, получает в разговоре слепок
голоса; при слушании сегмент переводится, только если совпал с голосом этого разговора.
Модель — та же, что в приложении: 3D-Speaker CAM++ (`models/speaker/…campplus…onnx`), отпечаток
считается ровно как в `Speaker.embed`. Стенд и пересчёт: `tools/voices_eval.py`, кэш вне дерева
(`voices-cache/`). Всё посчитано на ПК по записям; телефон в этом замере не участвовал.
"""


def put_section(name, md):
    os.makedirs(cpath('sections'), exist_ok=True)
    with open(cpath('sections', name + '.md'), 'w', encoding='utf-8') as f:
        f.write(md)
    txt = open(REPORT, encoding='utf-8').read() if os.path.exists(REPORT) else HEADER
    b, e = '<!-- auto:%s -->' % name, '<!-- /auto:%s -->' % name
    block = '%s\n%s\n%s' % (b, md.strip('\n'), e)
    if b in txt and e in txt:
        i, j = txt.index(b), txt.index(e) + len(e)
        txt = txt[:i] + block + txt[j:]
    else:
        txt = txt.rstrip('\n') + '\n\n' + block + '\n'
    with open(REPORT, 'w', encoding='utf-8') as f:
        f.write(txt)
    log('  отчёт: раздел %s записан' % name)


def section_done(name, redo):
    p = cpath('sections', name + '.md')
    if redo or not os.path.exists(p):
        return False
    put_section(name, open(p, encoding='utf-8').read())     # отчёт могли пересоздать — вписать заново
    log('%s: уже посчитан (sections/%s.md), пропускаю; --redo пересчитает' % (name, name))
    return True


# ---------------------------------------------------------------- корпус

def inventory():
    p = cpath('inventory.json')
    inv = load_json(p)
    if inv is not None:
        return inv
    inv = {}
    for lang, code in (('pt', 'por'), ('ru', 'rus')):
        lic = {}
        for line in open(os.path.join(CORPUS, lang, 'LICENSES.tsv'), encoding='utf-8'):
            c = line.rstrip('\n').split('\t')
            if len(c) >= 3:
                lic[c[0]] = (c[1], c[2])
        by_audio, by_sent = {}, collections.defaultdict(list)
        with bz2.open(os.path.join(TAT, '%s_sentences_with_audio.tsv.bz2' % code), 'rt', encoding='utf-8') as f:
            for line in f:
                c = line.rstrip('\n').split('\t')
                if len(c) >= 3:
                    by_audio[c[1]] = c[2]
                    by_sent[c[0]].append((c[1], c[2]))
        clips = []
        for path in sorted(glob.glob(os.path.join(CORPUS, lang, '*.wav'))):
            cid = os.path.basename(path)[:-4]
            if cid in lic:
                spk, how, li = by_audio.get(lic[cid][0]), 'LICENSES.tsv', lic[cid][1]
            else:
                users = sorted(set(u for _, u in by_sent.get(cid[4:], [])))
                spk, how, li = (users[0] if len(users) == 1 else None), 'единственная запись фразы', '?'
            x = clean(lang, cid)
            on, off = speech_bounds(x)
            clips.append(dict(id=cid, spk=spk, how=how, lic=li, n=len(x), on=on, off=off))
        inv[lang] = clips
    save_json(p, inv)
    return inv


def index(inv):
    return {(lang, c['id']): c for lang in LANGS for c in inv[lang] if c['spk']}


def speakers(inv):
    """{язык: {диктор: [записи по порядку]}}"""
    out = {}
    for lang in LANGS:
        d = collections.OrderedDict()
        for c in inv[lang]:
            if c['spk']:
                d.setdefault(c['spk'], []).append(c['id'])
        out[lang] = d
    return out


# ---------------------------------------------------------------- привязка записей комнаты

def ncc(x, y):
    """Нормированная взаимная корреляция шаблона x со всеми положениями в y (длина m-n+1)."""
    n, m = len(x), len(y)
    N = 1 << int(math.ceil(math.log2(n + m)))
    c = np.fft.irfft(np.fft.rfft(y, N) * np.conj(np.fft.rfft(x, N)), N)[:m - n + 1]
    cs = np.concatenate([[0.0], np.cumsum(np.square(y, dtype=np.float64))])
    ey = cs[n:] - cs[:m - n + 1]
    return c / np.sqrt(float(np.dot(x.astype(np.float64), x)) * np.maximum(ey, 1e-12))


def pearson_slide(a, b):
    """Корреляция Пирсона огибающей a с каждым положением в b."""
    n = len(a)
    az = (a - a.mean()) / (a.std() + 1e-9)
    c = np.correlate(b, az, mode='valid')
    cs = np.concatenate([[0.0], np.cumsum(b)])
    cs2 = np.concatenate([[0.0], np.cumsum(b * b)])
    mu = (cs[n:] - cs[:-n]) / n
    var = (cs2[n:] - cs2[:-n]) / n - mu * mu
    return c / (n * np.sqrt(np.maximum(var, 1e-9)))


def preemph(x):
    return np.append(x[:1], x[1:] - 0.97 * x[:-1]).astype(np.float32)


def robust_line(t, r):
    keep = np.ones(len(t), bool)
    coef = np.array([float(np.median(r)), 0.0])
    if len(t) < 3:
        return coef, r - coef[0], keep
    for _ in range(8):
        A = np.vstack([np.ones(int(keep.sum())), t[keep]]).T
        coef = np.linalg.lstsq(A, r[keep], rcond=None)[0]
        res = r - (coef[0] + coef[1] * t)
        mad = float(np.median(np.abs(res[keep] - np.median(res[keep]))))
        new = np.abs(res) <= max(4 * 1.4826 * mad, 10.0)
        if (new == keep).all() or new.sum() < 3:
            break
        keep = new
    return coef, r - (coef[0] + coef[1] * t), keep


# Дальние записи: фон вокруг файла громче этого — файл звучит под посторонним ровным звуком (−17…−29 dBFS,
# громче самой дальней речи). Так в конце far-pt (с ≈398-й с) и в far-ru до ≈373-й с — одно событие в комнате
# около 10 минут. В noisy-pt шум прерывистый и громкий по замыслу записи — там флаг не ставится.
LOUD_DB = -30.0


def align_rec(rec, inv):
    lang = rec.split('-')[1]
    d = os.path.join(REC, rec)
    poff = int(open(os.path.join(d, 'player.offset')).read().strip())
    loff = int(open(os.path.join(d, 'listener.offset')).read().strip())
    raw0 = None
    for line in open(os.path.join(d, 'listener.tsv'), encoding='utf-8', errors='replace'):
        p = line.rstrip('\n').split('\t')
        if len(p) > 1 and p[1] == 'raw_begin':
            raw0 = int(p[0])
            break
    if raw0 is None:
        raise SystemExit('нет raw_begin в %s/listener.tsv' % rec)
    plays = []
    for line in open(os.path.join(d, 'player.tsv'), encoding='utf-8', errors='replace'):
        p = line.rstrip('\n').split('\t')
        if len(p) > 3 and p[1] == 'play':
            plays.append((int(p[0]), p[2][:-4], float(p[3])))
    with wave.open(os.path.join(d, 'room.wav')) as w:
        total = w.getnframes()
    known = {c['id']: c for c in inv[lang]}
    rows = {}
    for ts, cid, dur in plays:
        if cid not in known:
            continue
        # часы обоих телефонов сведены к часам компьютера поправками *.offset (устройство − ПК)
        nominal = int(round(((ts - poff) - (raw0 - loff)) * SR / 1000.0))
        x = clean(lang, cid)
        a0 = nominal - 2 * SR
        y = room(rec, a0, nominal + len(x) + 2 * SR)
        c = ncc(preemph(x), preemph(y))
        k = int(np.argmax(c))
        # второй пик — дальше ±25 мс: соседние пики через период основного тона (4–10 мс) почти
        # равны главному и неоднозначности в нужном нам масштабе не означают
        far = np.abs(np.arange(len(c)) - k) > 400
        side = float(np.max(c[far])) if far.any() else 0.0
        ex_, ey_ = frames_db(x, 320, 160), frames_db(y, 320, 160)
        pe = pearson_slide(ex_, ey_)
        ke = int(np.argmax(pe))
        # фон вокруг файла: секунда до (без последних 125 мс) и секунда после (через 0,5 с от конца файла)
        bg = max(db(room(rec, nominal - SR, nominal - SR // 8)),
                 db(room(rec, nominal + len(x) + SR // 2, nominal + len(x) + 3 * SR // 2)))
        flat, b1, b2, sd = bg_character(room(rec, nominal - SR, nominal - SR // 8))
        rows[cid] = dict(t=nominal / SR, nominal=nominal, wave=a0 + k, peak=float(c[k]), side=side,
                         env=a0 + ke * 160, env_r=float(pe[ke]), dur_ms=dur, bg_db=bg,
                         bg_flat=flat, bg_b150=b1, bg_b1200=b2, bg_sd=sd)
    v_all = list(rows.values())
    for v in v_all:
        v['env_dev_ms'] = (v['env'] - v['wave']) * 1000.0 / SR
        v['resid_ms'] = (v['wave'] - v['nominal']) * 1000.0 / SR
    ok = [v for v in v_all if abs(v['env_dev_ms']) <= 20.0]     # два независимых способа сошлись
    t = np.array([v['t'] for v in ok])
    r = np.array([v['resid_ms'] for v in ok])
    coef, res, keep = robust_line(t, r)       # уход часов — только для отчёта
    # Поправка к журналу — постоянная (медиана по подтверждённым файлам): уход в чистых записях
    # не больше 25 мс за всю запись, меньше разброса самого журнала; а в far-ru подтверждённые
    # файлы есть только в конце записи, и прямая по ним ничего не говорит о её начале.
    med = float(np.median(r)) if len(r) else 0.0
    mad = float(np.median(np.abs(r - med))) if len(r) else float('nan')
    band = max(60.0, 4 * 1.4826 * mad)        # разброс журнала: строка play пишется не в миг выхода звука
    for v in v_all:
        v['line_dev_ms'] = v['resid_ms'] - med
        if abs(v['env_dev_ms']) <= 20.0:
            v['how'], v['pos'] = 'both', v['wave']
        elif abs(v['line_dev_ms']) <= band:
            v['how'], v['pos'] = 'wave', v['wave']
        else:
            v['how'], v['pos'] = 'log', v['nominal'] + int(round(med * SR / 1000.0))
        v['loud'] = bool(rec.startswith('far-') and v['bg_db'] >= LOUD_DB)
    for cid, v in rows.items():
        c = known[cid]
        sp = room(rec, v['pos'] + c['on'], v['pos'] + c['off'])
        nz = room(rec, v['pos'] + c['n'] + SR, v['pos'] + c['n'] + 2 * SR)   # пауза между файлами — 3 с
        v['speech_db'], v['noise_db'] = db(sp), db(nz)
        v['clip_frac'] = float(np.mean(np.abs(sp) >= 0.999)) if len(sp) else 0.0
    rv = np.array([v['resid_ms'] for v in ok]) if ok else np.array([float('nan')])
    return dict(rec=rec, lang=lang, total=total, raw0=raw0, poff=poff, loff=loff,
                offset_ms=med, b_ms_per_s=float(coef[1]), t_span=float(t.max() - t.min()) if len(t) else 0.0,
                mad_ms=mad, band_ms=band, resid_p5=q(rv, 5), resid_p95=q(rv, 95), n_both=len(ok), clips=rows)


def alignment(inv):
    p = cpath('align.json')
    al = load_json(p, {})
    for rec in RECS:
        if rec in al:
            continue
        log('привязка %s…' % rec)
        al[rec] = align_rec(rec, inv)
        save_json(p, al)
    return al


# ---------------------------------------------------------------- отрезки и их отпечатки

def rec_of(cond, lang):
    """farloud — те файлы дальней записи, что звучат под громким посторонним звуком (конец far-pt, far-ru до ≈373-й с);
    в far они не входят."""
    return '%s-%s' % ('far' if cond == 'farloud' else cond, lang)


def has(al, cond, lang):
    if cond == 'clean':
        return True
    a = al.get(rec_of(cond, lang))
    if a is None:
        return False
    return any(v['loud'] == (cond == 'farloud') for v in a['clips'].values())


def span(al, ix, cond, lang, cid, kind='full', X=None, pre=0.0, post=0.0):
    """Отрезок записи (источник, s0, s1): full — весь файл в комнате (то же, что чистая запись),
    speech — речь от начала до конца с полями pre/post, crop — X с от начала речи."""
    c = ix[(lang, cid)]
    if cond == 'clean':
        src, base = 'clean-' + lang, 0
    else:
        a = al.get(rec_of(cond, lang), {}).get('clips', {}).get(cid)
        if a is None or a['loud'] != (cond == 'farloud'):
            return None
        src, base = rec_of(cond, lang), a['pos']
    if kind == 'full':
        return (src, base, base + c['n'])
    if kind == 'speech':
        return (src, base + c['on'] - int(round(pre * SR)), base + c['off'] + int(round(post * SR)))
    if kind == 'crop':
        if c['off'] - c['on'] < int(round(X * SR)):
            return None
        return (src, base + c['on'], base + c['on'] + int(round(X * SR)))
    raise ValueError(kind)


def clean_span(lang, cid, s0, s1):
    x = clean(lang, cid)
    out = np.zeros(s1 - s0, np.float32)
    a, b = max(0, s0), min(len(x), s1)
    if b > a:
        out[a - s0:b - s0] = x[a:b]
    return out


def spec_of(sp, lang=None, cid=None):
    src, s0, s1 = sp
    if src.startswith('clean-'):
        return 'clean|%s|%s|%d|%d' % (src[6:], cid, s0, s1)
    return 'room|%s|%d|%d' % (src, s0, s1)


def emb_span(emb, sp, lang, cid, backend='ref'):
    if sp is None:
        return None
    src, s0, s1 = sp
    if src.startswith('clean-'):
        return emb.get(spec_of(sp, lang, cid), lambda: clean_span(lang, cid, s0, s1), backend)
    return emb.get(spec_of(sp), lambda: room(src, s0, s1), backend)


def all_embs(emb, al, inv, cond, kind='full', X=None, pre=0.0, post=0.0, backend='ref'):
    """{(язык, запись): отпечаток} для всех записей условия; чего нет в кэше — считается."""
    ix = index(inv)
    out = {}
    for lang in LANGS:
        if not has(al, cond, lang):
            continue
        for c in inv[lang]:
            if not c['spk']:
                continue
            sp = span(al, ix, cond, lang, c['id'], kind, X, pre, post)
            e = emb_span(emb, sp, lang, c['id'], backend)
            if e is not None:
                out[(lang, c['id'])] = e
    emb.db.commit()
    return out


# ---------------------------------------------------------------- этап data

COND_RU = {'clean': 'чистая', 'near': 'near (20–30 см)', 'far': 'far (дальняя)', 'noisy': 'noisy (шумнее)',
           'farloud': 'far под громким звуком'}


def quiet_share(rec, thr=-40.0):
    """Доля кадров по 100 мс тише thr dBFS во всей записи (читается кусками по 10 с)."""
    with wave.open(os.path.join(REC, rec, 'room.wav')) as w:
        n = w.getnframes()
    q_, tot = 0, 0
    for s0 in range(0, n, 10 * SR):
        x = room(rec, s0, min(n, s0 + 10 * SR))
        for i in range(0, len(x) - 1600 + 1, 1600):
            tot += 1
            q_ += db(x[i:i + 1600]) < thr
    return q_ / max(tot, 1)


def stage_data(emb, redo):
    inv = inventory()
    al = alignment(inv)
    if section_done('data', redo):
        return
    ix = index(inv)
    spk = speakers(inv)
    lines = ['## 1. Данные', '']
    nolabel = [(l, c['id']) for l in LANGS for c in inv[l] if not c['spk']]
    lines.append('Живые записи людей из Tatoeba, корпус замера через воздух `bench/air/corpus/` (82 pt и 80 ru). '
                 'Диктор записи — по `audio_id` из `LICENSES.tsv` и выгрузке Tatoeba `*_sentences_with_audio`; '
                 'у 12 pt и 10 ru записей (бывший `bench/asr2/ref`) строки в `LICENSES.tsv` нет, но у их фраз '
                 'ровно одна озвучка, и диктор однозначен. Без диктора: %d.' % len(nolabel))
    lines.append('')
    rows = []
    for lang in LANGS:
        for s, cl in sorted(spk[lang].items(), key=lambda kv: -len(kv[1])):
            sec = sum((ix[(lang, c)]['off'] - ix[(lang, c)]['on']) for c in cl) / SR
            rows.append([s, lang, len(cl), fnum(sec / len(cl), 1), 'да' if len(cl) >= NEED else '—'])
    lines.append(table(['диктор', 'язык', 'записей', 'речи на запись, с', 'участник разговора в E2–E5'], rows))
    lines.append('')
    lines.append('«Участник разговора» — у диктора не меньше %d записей: до %d на слепок и хотя бы одна на проверку. '
                 'Остальные бывают только чужими голосами. Перекос корпуса: у Inego 53 записи из 80 русских, '
                 'поэтому в разговоре от каждого диктора берётся не больше %d проверочных фраз.' % (NEED, ENR_MAX, CAP))
    lines.append('')
    # условия
    rows = []
    for cond in ('clean', 'near', 'far', 'noisy', 'farloud'):
        for lang in LANGS:
            if not has(al, cond, lang):
                continue
            ids = [c['id'] for c in inv[lang] if c['spk']]
            if cond != 'clean':
                a = al[rec_of(cond, lang)]['clips']
                ids = [c for c in ids if c in a and a[c]['loud'] == (cond == 'farloud')]
            ns = len(set(ix[(lang, c)]['spk'] for c in ids))
            sec = sum(ix[(lang, c)]['off'] - ix[(lang, c)]['on'] for c in ids) / SR
            if cond == 'clean':
                rows.append([COND_RU[cond], lang, len(ids), ns, fnum(sec / 60, 1), '—', '—'])
            else:
                sp = np.median([a[c]['speech_db'] for c in ids])
                nz = np.median([a[c]['noise_db'] for c in ids])
                snr = np.median([a[c]['speech_db'] - a[c]['noise_db'] for c in ids])
                rows.append(['%s · `%s`' % (COND_RU[cond], rec_of(cond, lang)), lang, len(ids), ns,
                             fnum(sec / 60, 1), '%s / %s' % (fnum(sp, 0), fnum(nz, 0)), fnum(snr, 0)])
    lines.append(table(['условие', 'язык', 'записей', 'дикторов', 'речи, мин', 'речь / фон, dBFS (медиана)', 'SNR, дБ (медиана)'], rows))
    lines.append('')
    lines.append('Записи комнаты — проигрывание корпуса с динамика второго телефона в комнате, микрофон POCO X7 Pro '
                 '(`bench/air/rec/README.md`). near — 20–30 см (ближайшее к «держит телефон и жмёт FALAR»), '
                 'far — дальняя расстановка (телефон на столе при слушании), noisy — та же близкая расстановка '
                 'в комнате шумнее. `fast-pt` не взята (README: в ней поменялись сразу плотность речи и шум). '
                 'Уровни — по моим отрезкам: речь — RMS от начала до конца речи, фон — секунда паузы после файла.')
    lines.append('')
    lp = [x for x in al['far-pt']['clips'].values() if x['loud']]
    lr = [x for x in al['far-ru']['clips'].values() if x['loud']]
    qr = [x for x in al['far-ru']['clips'].values() if not x['loud']]
    if lp or lr:
        allb = [x['bg_db'] for x in lp + lr]
        qq = [x for r_ in ('far-pt', 'far-ru') for x in al[r_]['clips'].values() if not x['loud']]
        md = lambda xs, k: float(np.median([x[k] for x in xs]))   # noqa: E731
        lines.append('**Дальние записи частично звучат под посторонним звуком.** В конце `far-pt` (с %s-й секунды, %d файлов) '
                     'и в `far-ru` до %s-й секунды (%d файлов из %d) фон вокруг файлов %s…%s dBFS — громче самой дальней речи. '
                     'Это ровный тональный звук, не речь: в секунде перед файлом спектральная плоскость %s против %s у тихого '
                     'фона тех же записей, на полосы 150–300 Гц и 1,2–2,5 кГц приходится %s и %s энергии (у тихого фона %s и %s), '
                     'разброс громкости по кадрам 50 мс %s дБ (%s у тихого; медианы по файлам). '
                     'Записи шли одна за другой, так что это одно событие в комнате минут на десять. '
                     'В условие far такие файлы не входят; они собраны в отдельное условие «far под громким звуком» '
                     '(только в E1, как крайний случай). В `far-ru` для far остаются лишь последние %d файлов. '
                     'Попутно это, похоже, объясняет «отказ нарезки на хорошем звуке» из README записей: фон там оценён '
                     'как медиана тихой трети записи, то есть по шестой части кадров, а тише −40 dBFS в `far-ru` %s кадров '
                     'по 100 мс — вот откуда «тихий фон» при громком.' % (
                         fnum(min(x['t'] for x in lp), 0) if lp else '—', len(lp),
                         fnum(min(x['t'] for x in qr), 0) if qr else '?', len(lr), len(lr) + len(qr),
                         fnum(min(allb), 0), fnum(max(allb), 0),
                         fnum(md(lp + lr, 'bg_flat'), 2), fnum(md(qq, 'bg_flat'), 2),
                         pct(md(lp + lr, 'bg_b150'), 0), pct(md(lp + lr, 'bg_b1200'), 0),
                         pct(md(qq, 'bg_b150'), 0), pct(md(qq, 'bg_b1200'), 0),
                         fnum(md(lp + lr, 'bg_sd'), 1), fnum(md(qq, 'bg_sd'), 1), len(qr), pct(quiet_share('far-ru'), 0)))
        lines.append('')
    # привязка
    lines.append('### Привязка записей комнаты')
    lines.append('')
    lines.append('Начало файла в `room.wav` по журналам: время строки `play` у проигрывателя минус `raw_begin` у '
                 'слушающего, оба приведены к часам ПК поправками `*.offset` (как в `air_wer.py`). Точное место — '
                 'взаимной корреляцией чистой записи с комнатой (предыскажение 0,97, поиск ±2 с вокруг журнального). '
                 'Проверка — независимым вторым способом: корреляцией огибающих громкости (кадр 20 мс, шаг 10 мс). '
                 'Сошлись в пределах ±20 мс — место подтверждено. Не сошлись — берётся место по корреляции, если '
                 'оно не дальше ±%s мс от медианной поправки (строка `play` пишется не в миг выхода звука: у '
                 'подтверждённых файлов поправка гуляет на %s…%s мс между p5 и p95), иначе — по журналу с медианной '
                 'поправкой.' % (fnum(min(al[r]['band_ms'] for r in RECS), 0),
                                 fnum(min(al[r]['resid_p95'] - al[r]['resid_p5'] for r in RECS), 0),
                                 fnum(max(al[r]['resid_p95'] - al[r]['resid_p5'] for r in RECS), 0)))
    lines.append('')
    rows = []
    for rec in RECS:
        a = al[rec]
        v = list(a['clips'].values())
        rows.append(['`%s`' % rec, len(v), fnum(a['offset_ms'], 0), '%s…%s' % (fnum(a['resid_p5'], 0), fnum(a['resid_p95'], 0)),
                     fnum(a['b_ms_per_s'] * a['t_span'], 0) if a['t_span'] >= 200 else '—',
                     sum(1 for x in v if x['how'] == 'both'), sum(1 for x in v if x['how'] == 'wave'),
                     sum(1 for x in v if x['how'] == 'log'), sum(1 for x in v if x['loud']),
                     fnum(np.median([x['peak'] for x in v if not x['loud']]), 2) if any(not x['loud'] for x in v) else '—',
                     fnum(np.median([x['peak'] / max(x['side'], 1e-9) for x in v if not x['loud']]), 1) if any(not x['loud'] for x in v) else '—',
                     pct(max(x['clip_frac'] for x in v), 2)])
    lines.append(table(['запись', 'файлов', 'поправка к журналу, мс', 'разброс поправки p5…p95, мс', 'уход часов за запись, мс',
                        'подтверждено огибающей', 'только корреляция', 'по журналу', 'под громким звуком',
                        'пик корреляции (медиана)', 'пик / второй пик дальше ±25 мс', 'перегруз (доля отсчётов)'], rows))
    lines.append('')
    lines.append('Пик и отношение пиков — по файлам без громкого постороннего звука. Отношение главного пика ко второму, '
                 'отстоящему больше чем на 25 мс, у тихих записей — в разы: место однозначно. Ближе 25 мс соседние пики '
                 'идут через период основного тона и почти равны главному — это неоднозначность в 4–10 мс, для отпечатка '
                 'сегмента в 1–3 с она ничего не значит.')
    lines.append('')
    # согласие чистого и комнатного отпечатка одной и той же фразы
    rows = []
    clean_e = all_embs(emb, al, inv, 'clean')
    cmp_ = [('near-pt', 'near', 'pt'), ('far-pt', 'far', 'pt'), ('noisy-pt', 'noisy', 'pt'), ('near-ru', 'near', 'ru'),
            ('far-ru', 'far', 'ru'), ('far-pt, под громким звуком', 'farloud', 'pt'), ('far-ru, под громким звуком', 'farloud', 'ru')]
    for name, cond, lang in cmp_:
        ce = all_embs(emb, al, inv, cond)
        ks = [k for k in ce if k[0] == lang and k in clean_e]
        s = np.array([float(np.dot(clean_e[k], ce[k])) for k in ks])
        d = np.array([(ix[k]['off'] - ix[k]['on']) / SR for k in ks])
        if len(s):
            sh, lo = s[d < 1.0], s[d >= 1.5]
            ver = np.array([al[rec_of(cond, lang)]['clips'][k[1]]['how'] == 'both' for k in ks])
            rows.append(['`%s`' % name if ',' not in name else name, len(s), fnum(float(s.min()), 2),
                         fnum(float(s[ver].min()), 2) if ver.any() else '—', fnum(q(s, 5), 2),
                         fnum(float(np.median(s)), 2),
                         '%s (%d)' % (fnum(float(np.median(sh)), 2), len(sh)) if len(sh) else '—',
                         '%s (%d)' % (fnum(float(np.median(lo)), 2), len(lo)) if len(lo) else '—'])
    lines.append('Ещё одна проверка — косинус между отпечатком чистой записи и той же фразы в комнате '
                 '(та же речь, другой канал). Отрезок не на месте или под чужим звуком дал бы здесь провалы. '
                 'Низкие значения дают короткие фразы и шум, а не привязка: минимум среди файлов, чьё место подтверждено '
                 'огибающей, почти такой же, а у фраз с речью короче секунды медиана ниже, чем у длинных.')
    lines.append('')
    lines.append(table(['запись', 'фраз', 'мин', 'мин среди подтверждённых', 'p5', 'медиана', 'речи < 1 с: медиана (фраз)',
                        'речи ≥ 1,5 с: медиана (фраз)'], rows))
    put_section('data', '\n'.join(lines))


# ---------------------------------------------------------------- E1

def eer(same, diff):
    s, d = np.sort(same), np.sort(diff)
    ts = np.unique(np.concatenate([s, d]))
    frr = np.searchsorted(s, ts, side='left') / len(s)
    far = 1.0 - np.searchsorted(d, ts, side='left') / len(d)
    i = int(np.argmin(np.abs(frr - far)))
    return float((frr[i] + far[i]) / 2), float(ts[i])


def fbank_indep(x):
    """Независимая запись признаков Kaldi (кадр за кадром, свои мел-полосы) — сверка voiceprint_ref.fbank."""
    x = np.asarray(x, dtype=np.float64)
    mel = lambda f: 1127.0 * np.log(1.0 + f / 700.0)   # noqa: E731
    lo, hi = mel(20.0), mel(8000.0)
    d = (hi - lo) / 81
    fm = mel(np.arange(256) * 16000.0 / 512)
    Wm = np.zeros((80, 257))
    for b in range(80):
        l, c, r = lo + b * d, lo + (b + 1) * d, lo + (b + 2) * d
        Wm[b, :256] = np.maximum(0.0, np.minimum((fm - l) / (c - l), (r - fm) / (r - c)))
    win = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(400) / 399)) ** 0.85
    out = []
    for i in range(1 + (len(x) - 400) // 160):
        f = x[i * 160:i * 160 + 400].copy()
        f -= f.mean()
        f[1:] = f[1:] - 0.97 * f[:-1]
        f[0] -= 0.97 * f[0]
        out.append(np.log(np.maximum(Wm @ (np.abs(np.fft.rfft(f * win, 512)) ** 2), np.finfo(np.float32).eps)))
    F = np.array(out)
    return F - F.mean(0)


def auc(same, diff):
    """Доля пар (тот же, другой), где у «того же» косинус выше; равные — пополам."""
    d = np.sort(np.asarray(diff))
    s = np.asarray(same)
    lo = np.searchsorted(d, s, side='left')
    hi = np.searchsorted(d, s, side='right')
    return float(np.mean((lo + 0.5 * (hi - lo)) / len(d)))


def pairs(EA, EB, spk_of, same_cond):
    ka, kb = sorted(EA), sorted(EB)
    if not ka or not kb:
        return None
    A = np.stack([EA[k] for k in ka])
    B = np.stack([EB[k] for k in kb])
    S = A @ B.T
    sa = np.array([spk_of[k] for k in ka])
    sb = np.array([spk_of[k] for k in kb])
    same = sa[:, None] == sb[None, :]
    ida = np.array(['%s/%s' % k for k in ka])
    idb = np.array(['%s/%s' % k for k in kb])
    valid = ida[:, None] != idb[None, :]                  # одну и ту же фразу с собой не сравниваем
    if same_cond:
        valid &= np.triu(np.ones_like(valid), 1).astype(bool)
    return dict(same=S[valid & same], diff=S[valid & ~same], S=S, ka=ka, kb=kb, valid=valid, same_m=same)


E1_GROUPS = [('чистая ↔ чистая', 'clean', 'clean'), ('near ↔ near', 'near', 'near'),
             ('near ↔ far', 'near', 'far'), ('чистая ↔ far', 'clean', 'far'),
             ('near ↔ noisy', 'near', 'noisy')]


def stage_e1(emb, redo):
    if section_done('e1', redo):
        return
    inv = inventory()
    al = alignment(inv)
    ix = index(inv)
    spk_of = {k: v['spk'] for k, v in ix.items()}
    # сверка: путь приложения до правки (sherpa-onnx + 0,5 с нулей) против эталона
    cmp_groups = [('чистая ↔ чистая', 'clean', 'clean', {}), ('near ↔ far', 'near', 'far', {}),
                  ('near ↔ far, отрезок как у слушания', 'near', 'far', dict(kind='speech', pre=1.0, post=0.3))]
    cmp_rows = []
    for name, ca, cb, kw in cmp_groups:
        for backend, bname in (('sherpa', 'sherpa-onnx (приложение до правки)'), ('ref', 'эталон voiceprint_ref')):
            EA = all_embs(emb, al, inv, ca, backend=backend)
            EB = all_embs(emb, al, inv, cb, backend=backend, **kw)
            pr = pairs(EA, EB, spk_of, ca == cb and not kw)
            s, d = pr['same'], pr['diff']
            e, _ = eer(s, d)
            cmp_rows.append([name, bname, '%d / %d' % (len(s), len(d)), pct(e), fnum(auc(s, d), 3),
                             fnum(float(np.median(s)), 2), fnum(float(np.median(d)), 2), fnum(q(d, 95), 2)])
    # одна и та же чистая запись двумя путями; и независимая сверка признаков эталона
    import voiceprint_ref
    es, er = all_embs(emb, al, inv, 'clean', backend='sherpa'), all_embs(emb, al, inv, 'clean')
    cross = [float(np.dot(es[k], er[k])) for k in sorted(er) if k in es]
    fdiff, fcos = [], []
    if voiceprint_ref._sess is None:
        voiceprint_ref.embed(np.zeros(SR, np.float32))         # поднять сессию, если всё взято из кэша
    for k in sorted(ix)[::8]:
        x = clean(*k)
        F1, F2 = voiceprint_ref.fbank(x), fbank_indep(x)
        fdiff.append(float(np.abs(F1 - F2).max()))
        e2 = voiceprint_ref._sess.run(None, {'x': F2[None].astype(np.float32)})[0][0]
        fcos.append(float(np.dot(er[k], e2 / np.linalg.norm(e2))))
    E = {c: all_embs(emb, al, inv, c) for c in ('clean', 'near', 'far', 'noisy', 'farloud')}
    E['far-app'] = all_embs(emb, al, inv, 'far', 'speech', pre=1.0, post=0.3)
    groups = E1_GROUPS + [('near ↔ far, отрезок как у слушания', 'near', 'far-app'),
                          ('near ↔ far под громким звуком', 'near', 'farloud')]
    res = []
    for name, ca, cb in groups:
        for langs in (('pt',), ('ru',), ('pt', 'ru')):
            EA = {k: v for k, v in E[ca].items() if k[0] in langs}
            EB = {k: v for k, v in E[cb].items() if k[0] in langs}
            pr = pairs(EA, EB, spk_of, ca == cb)
            if pr is None or len(pr['same']) == 0:
                continue
            s, d = pr['same'], pr['diff']
            e, et = eer(s, d)
            res.append(dict(name=name, langs='+'.join(langs), n_same=len(s), n_diff=len(d),
                            ns=len(set(spk_of[k] for k in list(EA) + list(EB))),
                            same=[float(s.min()), q(s, 5), float(np.median(s)), q(s, 95), float(s.max())],
                            diff=[float(d.min()), q(d, 5), float(np.median(d)), q(d, 95), float(d.max())],
                            eer=e, eer_t=et, auc=auc(s, d),
                            frr=[float(np.mean(s < t)) for t in TS], far=[float(np.mean(d >= t)) for t in TS]))
    save_json(cpath('sections', 'e1.json'), dict(res=res, cmp=cmp_rows))
    # по дикторам: near ↔ far, оба языка
    pr = pairs(E['near'], E['far'], spk_of, False)
    per = []
    for sname in sorted(set(spk_of[k] for k in pr['kb'])):
        cols = np.array([spk_of[k] == sname for k in pr['kb']])
        rows_s = np.array([spk_of[k] == sname for k in pr['ka']])
        m_same = pr['valid'][np.ix_(rows_s, cols)]
        ss = pr['S'][np.ix_(rows_s, cols)][m_same]
        dd = pr['S'][np.ix_(~rows_s, cols)]
        lang = [k for k in pr['kb'] if spk_of[k] == sname][0][0]
        per.append([sname, lang, int(cols.sum()),
                    fnum(float(np.median(ss)), 2) if len(ss) else '—', fnum(float(ss.min()), 2) if len(ss) else '—',
                    fnum(q(dd.ravel(), 95), 2), fnum(float(dd.max()), 2)])
    per.sort(key=lambda r: -r[2])
    L = ['## 2. E1. Косинусы «тот же человек» и «другой»', '']
    sh_auc = [float(r[4].replace(',', '.')) for r in cmp_rows if r[1].startswith('sherpa')]
    L.append('**Сначала — чем считать.** Путь, которым приложение считало отпечаток до правки (`SpeakerEmbeddingExtractor` '
             'sherpa-onnx 1.13.7 на ПК, сегмент + 0,5 с нулей), людей не различает: AUC %s…%s, то есть «тот же человек» '
             'выше «другого» почти так же часто, как наоборот. Та же модель через ONNX Runtime с признаками по рецепту '
             '3D-Speaker (`tools/voiceprint_ref.py`) различает. Отпечатки одной и той же чистой записи у двух путей почти не '
             'похожи: косинус между ними — медиана %s (p90 %s; записей — %d), так что дело в признаках на входе модели, а не '
             'в пороге. Признаки эталона сверены с независимой записью рецепта Kaldi в этом скрипте (`fbank_indep`): '
             'расхождение не больше %s, отпечатки — косинус не ниже %s (записей — %d). В приложении — sherpa-onnx 1.13.8 '
             '(строка версии в `libsherpa-onnx-jni.so`); что он ведёт себя так же, на телефоне не проверялось. '
             'Оба языка вместе:' % (fnum(min(sh_auc), 2), fnum(max(sh_auc), 2), fnum(float(np.median(cross)), 2),
                                    fnum(q(cross, 90), 2), len(cross), sci(max(fdiff)), fnum(min(fcos), 6), len(fcos)))
    L.append('')
    L.append(table(['условия', 'путь', 'пар: тот же / другой', 'EER', 'AUC', 'тот же: медиана', 'другой: медиана',
                    'другой: p95'], cmp_rows))
    L.append('')
    L.append('Дальше везде — эталон. Каждая запись против каждой: одна сторона из первого условия, другая из второго; '
             'одна и та же фраза сама с собой не сравнивается (в разных условиях это та же речь — нечестно легко). '
             '«pt+ru» — все дикторы вместе, «другой» тогда включает и пары разных языков. '
             'Отрезок — ровно файл корпуса в записи комнаты (с его собственной тишиной по краям, медиана 0,25 с с каждой стороны). '
             'Две последние группы: far-отрезок, как его режет слушание (1 с звука комнаты до речи и 0,3 с после — '
             'подпор и хвост из настроек нарезки), и far под громким посторонним звуком (§1; крайний случай, не для порога).')
    L.append('')
    rows = []
    for r in res:
        rows.append([r['name'], r['langs'], r['ns'], '%d / %d' % (r['n_same'], r['n_diff']),
                     ' · '.join(fnum(x, 2) for x in r['same']), ' · '.join(fnum(x, 2) for x in r['diff']),
                     '%s (%s)' % (pct(r['eer']), fnum(r['eer_t'], 2)), fnum(r['auc'], 3)])
    L.append(table(['условия', 'языки', 'дикторов', 'пар: тот же / другой',
                    'тот же: мин · p5 · медиана · p95 · макс', 'другой: мин · p5 · медиана · p95 · макс',
                    'EER (порог)', 'AUC'], rows))
    L.append('')
    L.append('FRR — доля пар «тот же человек» ниже порога (свой не узнан), FAR — доля пар «другой» на пороге и выше '
             '(чужой принят за своего). В клетке FRR / FAR, в процентах:')
    L.append('')
    rows = []
    for r in res:
        rows.append([r['name'], r['langs']] + ['%s / %s' % (ipct(a), ipct(b)) for a, b in zip(r['frr'], r['far'])])
    L.append(table(['условия', 'языки'] + [fnum(t, 2) for t in TS], rows))
    L.append('')
    L.append('По дикторам, near ↔ far, оба языка: «тот же» — ближние записи диктора против его же дальних (другие фразы), '
             '«чужие против его дальних» — ближние записи всех остальных против его дальних.')
    L.append('')
    L.append(table(['диктор', 'язык', 'записей', 'тот же: медиана', 'тот же: мин', 'чужие против его дальних: p95', 'макс'], per))
    put_section('e1', '\n'.join(L))


# ---------------------------------------------------------------- края сегмента

PADS = [('нули 0,5 с до', 'zero', 0.5, 0.0), ('нули 0,5 с после', 'zero', 0.0, 0.5),
        ('нули 0,5 с с обеих сторон', 'zero', 0.5, 0.5), ('шум −70 dBFS 0,5 с до', 'noise', 0.5, 0.0),
        ('шум −70 dBFS 0,5 с после', 'noise', 0.0, 0.5), ('шум −70 dBFS с обеих сторон', 'noise', 0.5, 0.5),
        ('нули 1 с до + 0,3 с после', 'zero', 1.0, 0.3), ('шум −70 dBFS 1 с до + 0,3 с после', 'noise', 1.0, 0.3)]


def padded(x, how, pre, post, seed):
    """Сегмент с полями: цифровые нули или белый шум с RMS −70 dBFS (свой посев на каждую запись)."""
    a, b = int(round(pre * SR)), int(round(post * SR))
    if how == 'zero':
        pa, pb = np.zeros(a, np.float32), np.zeros(b, np.float32)
    else:
        rng = np.random.default_rng(seed)
        amp = 10 ** (-70 / 20.0)
        pa = (rng.standard_normal(a) * amp).astype(np.float32)
        pb = (rng.standard_normal(b) * amp).astype(np.float32)
    return np.concatenate([pa, x.astype(np.float32), pb])


def stage_pad(emb, redo):
    if section_done('pad', redo):
        return
    inv = inventory()
    al = alignment(inv)
    ix = index(inv)
    spk_of = {k: v['spk'] for k, v in ix.items()}
    out, sep = [], []
    En = all_embs(emb, al, inv, 'near')
    for cond in ('clean', 'far'):
        base = all_embs(emb, al, inv, cond)
        for name, how, pre, post in PADS:
            cs, Ep = [], {}
            for k in sorted(base):
                sp = span(al, ix, cond, k[0], k[1])
                seed = int(hashlib.sha1(('%s/%s' % k).encode()).hexdigest()[:8], 16)
                spec = 'pad|%s|%s|%.2f|%.2f|%d' % (spec_of(sp, *k), how, pre, post, seed)
                x = clean_span(k[0], k[1], sp[1], sp[2]) if cond == 'clean' else room(*sp)
                e = emb.get(spec, lambda: padded(x, how, pre, post, seed))
                if e is None:
                    continue
                Ep[k] = e
                cs.append(float(np.dot(e, base[k])))
            cs = np.array(cs)
            out.append([cond, name, len(cs), fnum(float(cs.min()), 2), fnum(q(cs, 5), 2), fnum(float(np.median(cs)), 2)])
            if cond == 'far' and pre >= 1.0:
                pr = pairs(En, Ep, spk_of, False)
                e_, t_ = eer(pr['same'], pr['diff'])
                sep.append(['far, ' + name, pct(e_), fnum(auc(pr['same'], pr['diff']), 3),
                            fnum(float(np.median(pr['same'])), 2), fnum(float(np.median(pr['diff'])), 2)])
        emb.db.commit()
    # для сравнения: настоящий звук комнаты вокруг дальней фразы, как режет слушание, и без полей
    base = all_embs(emb, al, inv, 'far')
    app = all_embs(emb, al, inv, 'far', 'speech', pre=1.0, post=0.3)
    cs = np.array([float(np.dot(app[k], base[k])) for k in sorted(base) if k in app])
    out.append(['far', 'звук комнаты 1 с до речи + 0,3 с после (как у слушания)', len(cs), fnum(float(cs.min()), 2),
                fnum(q(cs, 5), 2), fnum(float(np.median(cs)), 2)])
    for name, E_ in (('far, файл как есть (без полей)', base), ('far, звук комнаты 1 с + 0,3 с (как у слушания)', app)):
        pr = pairs(En, E_, spk_of, False)
        e_, _ = eer(pr['same'], pr['diff'])
        sep.append([name, pct(e_), fnum(auc(pr['same'], pr['diff']), 3), fnum(float(np.median(pr['same'])), 2),
                    fnum(float(np.median(pr['diff'])), 2)])
    save_json(cpath('sections', 'pad.json'), dict(out=out, sep=sep))
    L = ['## 3. Края сегмента: тишина и тихий шум', '']
    L.append('Сегмент слушания несёт около 1 с звука до речи и 0,3 с после (подпор и хвост нарезки). Насколько отпечаток '
             'зависит от того, что лежит по краям? Та же запись (чистая — весь файл корпуса; дальняя — файл в записи '
             'комнаты) с полями из цифровых нулей или белого шума с RMS −70 dBFS; в клетках — косинус к отпечатку той же '
             'записи без полей. Собственная тишина файла (около 0,25 с с каждой стороны) остаётся внутри.')
    L.append('')
    L.append(table(['запись', 'поля', 'записей', 'мин', 'p5', 'медиана'], out))
    L.append('')
    L.append('Различение (слепок — одна ближняя фраза, проверка — дальняя с полями; оба языка, как в E1):')
    L.append('')
    L.append(table(['дальний сегмент', 'EER', 'AUC', 'тот же: медиана', 'другой: медиана'], sep))
    put_section('pad', '\n'.join(L))


# ---------------------------------------------------------------- E2 / E3: разговор

def make_trials(inv, cfg, n, seed):
    """Разговоры: участники (диктор: записи на слепок, проверочные) и чужие голоса (все остальные)."""
    rng = random.Random(seed)
    spk = speakers(inv)
    elig = {l: sorted(s for s, cl in spk[l].items() if len(cl) >= NEED) for l in LANGS}
    _, npt, nru = cfg
    out = []
    for _ in range(n):
        members = [('pt', s) for s in rng.sample(elig['pt'], npt)] + [('ru', s) for s in rng.sample(elig['ru'], nru)]
        enr = {}
        for (l, s) in members:
            cl = spk[l][s][:]
            rng.shuffle(cl)
            enr['%s/%s' % (l, s)] = dict(lang=l, enr=cl[:ENR_MAX], test=cl[ENR_MAX:ENR_MAX + CAP])
        bys = {}
        for l in LANGS:
            for s, cl in spk[l].items():
                if (l, s) in members:
                    continue
                cl = cl[:]
                rng.shuffle(cl)
                bys['%s/%s' % (l, s)] = dict(lang=l, test=cl[:CAP])
        out.append(dict(members=enr, bys=bys))
    return out


def centroid(es):
    return unit(np.mean(np.stack(es), axis=0))


def app_fold(es):
    """Как Voices.fold: v = unit(v·k + e), k растёт на 1."""
    v, k = es[0], 1
    for e in es[1:]:
        v = unit(v * k + e)
        k += 1
    return v


def run_chats(trials, enr_E, test_E, ks=(1, 2, 3)):
    """Итоги по каждой проверочной фразе: (лучший косинус, верный ли голос, второй косинус)."""
    out = {k: dict(enr=[], bys=[]) for k in ks}
    for tr in trials:
        names = sorted(tr['members'])
        for k in ks:
            C = []
            for nm in names:
                m = tr['members'][nm]
                es = [enr_E.get((m['lang'], c)) for c in m['enr'][:k]]
                es = [e for e in es if e is not None]
                C.append(centroid(es))
            C = np.stack(C)
            for i, nm in enumerate(names):
                m = tr['members'][nm]
                for c in m['test']:
                    e = test_E.get((m['lang'], c))
                    if e is None:
                        continue
                    s = C @ e
                    o = np.argsort(-s)
                    out[k]['enr'].append((float(s[o[0]]), bool(o[0] == i), float(s[o[1]]) if len(o) > 1 else -1.0))
            for nm, b in tr['bys'].items():
                for c in b['test']:
                    e = test_E.get((b['lang'], c))
                    if e is None:
                        continue
                    s = C @ e
                    out[k]['bys'].append(float(s.max()))
    return out


def rates(rec, T):
    en = np.array([r[0] for r in rec['enr']])
    ok = np.array([r[1] for r in rec['enr']])
    by = np.array(rec['bys'])
    if len(en) == 0:
        return None
    return dict(n_enr=len(en), n_bys=len(by),
                correct=float(np.mean((en >= T) & ok)), wrong=float(np.mean((en >= T) & ~ok)),
                rejected=float(np.mean(en < T)), bys_acc=float(np.mean(by >= T)) if len(by) else float('nan'))


FINE = [round(0.10 + 0.005 * i, 3) for i in range(121)]       # 0,100…0,700
# Предлагаемые пороги (по рабочим точкам E2 «чужих ≤ 5 %», округлено по трём составам): растут с числом фраз
# в слепке — среднее нескольких фраз ближе ко всем голосам сразу. И запасной вариант — один порог на всё.
PROP = {'near': {1: 0.40, 2: 0.43, 3: 0.44}, 'far': {1: 0.38, 2: 0.40, 3: 0.42}, 'noisy': {1: 0.38, 2: 0.40, 3: 0.42}}
PROP_ONE = {'near': 0.43, 'far': 0.40, 'noisy': 0.40}


def op_point(rec, target):
    """Самый низкий порог, при котором чужих принято не больше target, и что тогда со своими."""
    for T in FINE:
        r = rates(rec, T)
        if r is not None and not math.isnan(r['bys_acc']) and r['bys_acc'] <= target:
            r = dict(r)
            r['T'] = T
            return r
    return None


def cell(r):
    if r is None:
        return '—'
    return '%s / %s / %s / %s' % (ipct(r['correct']), ipct(r['wrong']), ipct(r['rejected']), ipct(r['bys_acc']))


def stage_e2(emb, redo):
    if section_done('e2', redo):
        return
    inv = inventory()
    al = alignment(inv)
    E = {c: all_embs(emb, al, inv, c) for c in ('near', 'far', 'noisy')}
    res = {}
    fold_cos = []
    for ci, cfg in enumerate(CONFIGS):
        trials = make_trials(inv, cfg, TRIALS, SEED + ci)
        for tr in trials[:50]:
            for m in tr['members'].values():
                es = [E['near'][(m['lang'], c)] for c in m['enr']]
                fold_cos.append(float(np.dot(app_fold(es), centroid(es))))
        for cond in ('near', 'far', 'noisy'):
            rc = run_chats(trials, E['near'], E[cond])
            for k, rec in rc.items():
                for T in T2:
                    res['%s|%s|%d|%.2f' % (cfg[0], cond, k, T)] = rates(rec, T)
                for target in (0.05, 0.02):
                    res['op|%s|%s|%d|%.2f' % (cfg[0], cond, k, target)] = op_point(rec, target)
                res['prop|%s|%s|%d' % (cfg[0], cond, k)] = rates(rec, PROP[cond][k])
                res['one|%s|%s|%d' % (cfg[0], cond, k)] = rates(rec, PROP_ONE[cond])
            # запас между лучшим и вторым голосом у верно опознанных и у ошибок «не тот»
            if cfg[0] == CONFIGS[0][0]:
                for k, rec in rc.items():
                    res['margin|%s|%d' % (cond, k)] = dict(
                        ok=[r[0] - r[2] for r in rec['enr'] if r[1]], bad=[r[0] - r[2] for r in rec['enr'] if not r[1]],
                        bad_best=[r[0] for r in rec['enr'] if not r[1]])
    save_json(cpath('sections', 'e2.json'), res)
    L = ['## 4. E2. Разговор: опознание среди голосов этого разговора', '']
    L.append('Разговор — %d случайных расстановок (зерно %d) на каждый состав. Участники — дикторы, у которых хватает записей '
             '(5 pt и 3 ru, см. §1); слепок — 1, 2 или 3 ближние (near) фразы, единичная длина среднего. Проверка — '
             'другие фразы участников (до %d на человека) и все фразы всех остальных дикторов (до %d на человека) '
             'как чужие голоса. Правило: ближайший слепок, если косинус ≥ T, иначе «чужой». '
             'В клетке, в процентах: **верно / не тот участник / свой отвергнут / чужой принят**; первые три — от фраз '
             'участников, последнее — от фраз чужих.' % (TRIALS, SEED, CAP, CAP))
    L.append('')
    for cfg in CONFIGS:
        for cond, title in (('near', 'фраза FALAR против слепков из FALAR (near ↔ near)'),
                            ('far', 'слушание: дальний сегмент против слепков из FALAR (near → far)'),
                            ('noisy', 'шумная комната, близко (near → noisy; только pt)')):
            if cfg[0] != CONFIGS[0][0]:
                continue        # остальные составы — в таблицах рабочих точек ниже; вся сетка — в sections/e2.json
            L.append('**%s — %s**' % (cfg[0], title))
            L.append('')
            rows = []
            for T in T2:
                rows.append([fnum(T, 2)] + [cell(res.get('%s|%s|%d|%.2f' % (cfg[0], cond, k, T))) for k in (1, 2, 3)])
            n = res.get('%s|%s|1|0.60' % (cfg[0], cond))
            L.append(table(['порог', 'слепок из 1 фразы', 'из 2', 'из 3'], rows))
            if n:
                L.append('')
                L.append('Проверочных фраз: участников %d, чужих %d.' % (n['n_enr'], n['n_bys']))
            L.append('')
    # рабочие точки: порог, при котором чужих принято не больше 5 % и 2 %
    L.append('**Рабочие точки.** Сравнивать слепки при одном пороге нечестно: среднее нескольких фраз ближе ко всем '
             'голосам сразу, и чужих при том же пороге принимается больше. Поэтому ниже — самый низкий порог (шаг 0,005), '
             'при котором чужих принято не больше 5 % (и 2 %), и что при нём со своими: верно / не тот / отвергнут, %.')
    L.append('')
    rows = []
    for cfg in CONFIGS:
        for cond in ('near', 'far', 'noisy'):
            for k in (1, 2, 3):
                cells = []
                for target in (0.05, 0.02):
                    r = res.get('op|%s|%s|%d|%.2f' % (cfg[0], cond, k, target))
                    cells.append('—' if r is None else '%s: %s / %s / %s' % (
                        fnum(r['T'], 3), ipct(r['correct']), ipct(r['wrong']), ipct(r['rejected'])))
                if res.get('%s|%s|%d|0.30' % (cfg[0], cond, k)) is None:
                    continue
                rows.append([cfg[0], {'near': 'near ↔ near', 'far': 'near → far', 'noisy': 'near → noisy'}[cond], k] + cells)
    L.append(table(['состав', 'проверка', 'фраз в слепке', 'чужих ≤ 5 %: порог: верно / не тот / отвергнут',
                    'чужих ≤ 2 %: порог: верно / не тот / отвергнут'], rows))
    L.append('')
    # предлагаемые пороги
    L.append('**Предлагаемые пороги и что они дают** (верно / не тот / свой отвергнут / чужой принят, %). Порог растёт с '
             'числом фраз в слепке: для FALAR (near ↔ near) 0,40 / 0,43 / 0,44 при 1 / 2 / 3 фразах, для слушания '
             '(near → far, noisy) 0,38 / 0,40 / 0,42. Рядом — один порог на всё: 0,43 для FALAR и 0,40 для слушания. '
             'Пороги подобраны на тех же записях, на которых проверены, — это оценка, а не гарантия.')
    L.append('')
    rows = []
    for cfg in CONFIGS:
        for cond in ('near', 'far', 'noisy'):
            for k in (1, 2, 3):
                r1, r2 = res.get('prop|%s|%s|%d' % (cfg[0], cond, k)), res.get('one|%s|%s|%d' % (cfg[0], cond, k))
                if r1 is None:
                    continue
                rows.append([cfg[0], {'near': 'near ↔ near', 'far': 'near → far', 'noisy': 'near → noisy'}[cond], k,
                             '%s: %s' % (fnum(PROP[cond][k], 2), cell(r1)), '%s: %s' % (fnum(PROP_ONE[cond], 2), cell(r2))])
    L.append(table(['состав', 'проверка', 'фраз в слепке', 'порог по числу фраз', 'один порог'], rows))
    L.append('')
    # запас
    rows = []
    for cond in ('near', 'far', 'noisy'):
        for k in (1, 3):
            m = res.get('margin|%s|%d' % (cond, k))
            if not m:
                continue
            rows.append([cond, k, len(m['ok']), fnum(q(m['ok'], 5), 2), fnum(float(np.median(m['ok'])) if m['ok'] else float('nan'), 2),
                         len(m['bad']), fnum(float(np.median(m['bad'])) if m['bad'] else float('nan'), 2),
                         fnum(max(m['bad_best']) if m['bad_best'] else float('nan'), 2)])
    L.append('Запас между лучшим и вторым голосом (%s): у верных опознаний и у ошибок «не тот участник» '
             '(без порога — сколько раз ближе оказался чужой участник вообще):' % CONFIGS[0][0])
    L.append('')
    L.append(table(['проверка', 'фраз в слепке', 'верных', 'запас p5', 'запас медиана', 'ошибок «не тот»',
                    'их запас медиана', 'их лучший косинус, макс'], rows))
    L.append('')
    L.append('Слепок в приложении копится иначе (`Voices.fold`: v = unit(v·k + e)), а не средним сразу; '
             'косинус между двумя способами на %d слепках из 3 фраз — не ниже %s.' % (len(fold_cos), fnum(min(fold_cos), 4)))
    put_section('e2', '\n'.join(L))


def stage_e3(emb, redo):
    if section_done('e3', redo):
        return
    inv = inventory()
    al = alignment(inv)
    ix = index(inv)
    spk_of = {k: v['spk'] for k, v in ix.items()}
    En = all_embs(emb, al, inv, 'near')
    full = {c: all_embs(emb, al, inv, c) for c in ('far', 'noisy', 'near')}
    sp_dur = {k: (v['off'] - v['on']) / SR for k, v in ix.items()}
    log('e3: длина речи в записях: p10 %.2f, медиана %.2f, p90 %.2f с' % (
        q(list(sp_dur.values()), 10), float(np.median(list(sp_dur.values()))), q(list(sp_dur.values()), 90)))
    crops = {}
    for cond in ('far', 'noisy', 'near'):
        for X in DURS:
            crops[(cond, X)] = all_embs(emb, al, inv, cond, 'crop', X=X)
            log('e3: %s %.1f с — %d отрезков' % (cond, X, len(crops[(cond, X)])))
    trials = make_trials(inv, CONFIGS[0], TRIALS, SEED)
    res = {}
    for cond in ('far', 'noisy', 'near'):
        for X in DURS + ['full']:
            T_E = full[cond] if X == 'full' else crops[(cond, X)]
            for subset in ('all', 'long'):
                # «long» — только фразы, где речи не меньше 2 с: одни и те же фразы, обрезанные короче
                if subset == 'long':
                    T_E2 = {k: v for k, v in T_E.items() if sp_dur[k] >= 2.0}
                else:
                    T_E2 = T_E
                rc = run_chats(trials, En, T_E2, ks=(1, 3))
                for k in (1, 3):
                    for T in T2:
                        res['%s|%s|%s|%d|%.2f' % (cond, X, subset, k, T)] = rates(rc[k], T)
                    res['op|%s|%s|%s|%d' % (cond, X, subset, k)] = op_point(rc[k], 0.05)
                pr = pairs(En, T_E2, spk_of, False)
                if pr is not None and len(pr['same']):
                    e, et = eer(pr['same'], pr['diff'])
                    res['pairs|%s|%s|%s' % (cond, X, subset)] = dict(
                        n=len(T_E2), same_med=float(np.median(pr['same'])), same_p5=q(pr['same'], 5),
                        diff_p95=q(pr['diff'], 95), diff_max=float(pr['diff'].max()), eer=e, eer_t=et,
                        auc=auc(pr['same'], pr['diff']))
    save_json(cpath('sections', 'e3.json'), res)
    L = ['## 5. E3. Длина сегмента', '']
    L.append('Те же разговоры «%s», что в E2; слепок — из целых ближних фраз. Проверочный отрезок — первые X секунд '
             'речи фразы (от начала речи, без тишины перед ней); фраза, где речи меньше X, в этой строке не участвует. '
             'Поэтому строки «все» сравнивают немного разные наборы фраз, а строки «≥2 с» — одни и те же фразы '
             '(речи не меньше 2 с), обрезанные всё короче.' % CONFIGS[0][0])
    L.append('')
    L.append('Косинусы пар (near целиком → отрезок X), как в E1, плюс EER:')
    L.append('')
    rows = []
    for cond in ('near', 'far', 'noisy'):
        for subset in ('all', 'long'):
            for X in DURS + ['full']:
                p = res.get('pairs|%s|%s|%s' % (cond, X, subset))
                if not p:
                    continue
                rows.append([cond, 'все' if subset == 'all' else '≥2 с', 'целиком' if X == 'full' else fnum(X, 1),
                             p['n'], fnum(p['same_p5'], 2), fnum(p['same_med'], 2), fnum(p['diff_p95'], 2),
                             fnum(p['diff_max'], 2), '%s (%s)' % (pct(p['eer']), fnum(p['eer_t'], 2)), fnum(p['auc'], 3)])
    L.append(table(['проверка', 'фразы', 'X, с', 'отрезков', 'тот же p5', 'тот же медиана', 'другой p95', 'другой макс',
                    'EER (порог)', 'AUC'], rows))
    L.append('')
    L.append('Разговор, слепок из 3 фраз: **верно / не тот / свой отвергнут / чужой принят**, %, при порогах 0,30…0,45, '
             'и рабочая точка — самый низкий порог, при котором чужих принято не больше 5 %, и что при нём со своими '
             '(верно / не тот / отвергнут):')
    L.append('')
    for cond in ('far', 'noisy', 'near'):
        rows = []
        for X in DURS + ['full']:
            for subset in ('all', 'long'):
                r0 = res.get('%s|%s|%s|3|0.30' % (cond, X, subset))
                if r0 is None:
                    continue
                op = res.get('op|%s|%s|%s|3' % (cond, X, subset))
                rows.append(['целиком' if X == 'full' else fnum(X, 1), 'все' if subset == 'all' else '≥2 с',
                             '%d / %d' % (r0['n_enr'], r0['n_bys'])] +
                            [cell(res.get('%s|%s|%s|3|%.2f' % (cond, X, subset, T))) for T in (0.30, 0.35, 0.40, 0.45)] +
                            ['—' if op is None else '%s: %s / %s / %s' % (fnum(op['T'], 3), ipct(op['correct']),
                                                                          ipct(op['wrong']), ipct(op['rejected']))])
        L.append('**%s**' % {'far': 'near → far', 'noisy': 'near → noisy', 'near': 'near → near'}[cond])
        L.append('')
        L.append(table(['X, с', 'фразы', 'фраз: своих / чужих', 'T 0,30', 'T 0,35', 'T 0,40', 'T 0,45',
                        'чужих ≤ 5 %: порог: верно / не тот / отвергнут'], rows))
        L.append('')
    put_section('e3', '\n'.join(L))


# ---------------------------------------------------------------- E4 / E5: составные сегменты

def eligible(inv):
    spk = speakers(inv)
    return [(l, s) for l in LANGS for s, cl in spk[l].items() if len(cl) >= NEED]


def far_avail(al, ix):
    """Записи, у которых есть годный дальний отрезок (не под громким посторонним звуком)."""
    return set(k for k in ix if span(al, ix, 'far', k[0], k[1]) is not None)


def pick_member(rng, spk, who, avail):
    """Слепок — из 3 случайных записей диктора; проверочные — из остальных, у которых есть дальний отрезок."""
    l, s = who
    cl = spk[l][s][:]
    rng.shuffle(cl)
    return dict(lang=l, spk=s, enr=cl[:ENR_MAX], rest=[c for c in cl[ENR_MAX:] if (l, c) in avail])


def build(parts):
    """parts: [(источник, s0, s1, усиление)] подряд → звук и описание для ключа кэша."""
    xs = [room(src, s0, s1) * g for src, s0, s1, g in parts]
    spec = '+'.join('%s:%d:%d:%.6f' % p for p in parts)
    return np.concatenate(xs), spec


def win_starts(L, W, hop):
    w, h = int(round(W * SR)), int(round(hop * SR))
    if L <= w:
        return [0], L
    return list(range(0, L - w + 1, h)), w


def smooth3(labels):
    out = list(labels)
    for i in range(1, len(labels) - 1):
        trio = labels[i - 1:i + 2]
        for lab in set(trio):
            if trio.count(lab) >= 2:
                out[i] = lab
    return out


def timeline(starts, w, L, labels):
    """Каждому окну — отрезок вокруг его центра (до середин между соседними центрами)."""
    c = [s + w / 2.0 for s in starts]
    edges = [0.0] + [(c[i] + c[i + 1]) / 2.0 for i in range(len(c) - 1)] + [float(L)]
    return [(edges[i], edges[i + 1], labels[i]) for i in range(len(c))]


def overlap(a0, a1, b0, b1):
    return max(0.0, min(a1, b1) - max(a0, b0))


def stage_e4(emb, redo, k_enr=ENR_MAX):
    if section_done('e4', redo):
        return
    inv = inventory()
    al = alignment(inv)
    ix = index(inv)
    spk = speakers(inv)
    En = all_embs(emb, al, inv, 'near')
    elig = eligible(inv)
    avail = far_avail(al, ix)
    rng = random.Random(SEED + 4)
    everyone = [(l, s) for l in LANGS for s in spk[l] if any((l, c) in avail for c in spk[l][s])]
    samples = []
    for kind in ('AB', 'AC', 'AA'):           # AA — в конце: выборка AB и AC от этого не меняется
        for pause in (0.0, 0.3):
            got, tries = 0, 0
            while got < E4_N[kind]:
                tries += 1
                if tries > 200 * E4_N[kind]:
                    raise SystemExit('e4: не набрать образцов %s' % kind)
                A, B = rng.sample(elig, 2)
                mA, mB = pick_member(rng, spk, A, avail), pick_member(rng, spk, B, avail)
                if not mA['rest'] or (kind == 'AB' and not mB['rest']) or (kind == 'AA' and len(mA['rest']) < 2):
                    continue
                if kind == 'AA':
                    a, a2 = rng.sample(mA['rest'], 2)
                    second = (A[0], a2)
                else:
                    a = rng.choice(mA['rest'])
                if kind == 'AB':
                    second = (B[0], rng.choice(mB['rest']))
                elif kind == 'AC':
                    C = rng.choice([p for p in everyone if p not in (A, B)])
                    second = (C[0], rng.choice([c for c in spk[C[0]][C[1]] if (C[0], c) in avail]))
                da = (ix[(A[0], a)]['off'] - ix[(A[0], a)]['on']) / SR
                db_ = (ix[second]['off'] - ix[second]['on']) / SR
                if da < 0.8 or db_ < 0.8:
                    continue
                samples.append(dict(kind=kind, pause=pause, A=mA, B=mB, a=(A[0], a), second=second,
                                    C='%s/%s' % (C[0], C[1]) if kind == 'AC' else None))
                got += 1
    log('e4: образцов %d' % len(samples))
    out = []
    t0 = time.time()
    for i, smp in enumerate(samples):
        la, ca = smp['a']
        lb, cb = smp['second']
        pa = span(al, ix, 'far', la, ca, 'speech', pre=0.3, post=smp['pause'])
        pb = span(al, ix, 'far', lb, cb, 'speech', pre=0.0, post=0.3)
        x, spec = build([pa + (1.0,), pb + (1.0,)])
        LA = pa[2] - pa[1]
        a_on, a_off = int(0.3 * SR), int(0.3 * SR) + (ix[(la, ca)]['off'] - ix[(la, ca)]['on'])
        b_on, b_off = LA, LA + (ix[(lb, cb)]['off'] - ix[(lb, cb)]['on'])
        whole = emb.get('cat|' + spec, x)
        wins = {}
        for W in WINS:
            starts, w = win_starts(len(x), W, HOPS[0])
            es = [emb.get('cat|%s|w%d:%d' % (spec, s, s + w), x[s:s + w]) for s in starts]
            wins[str(W)] = dict(starts=starts, w=w, e=es)
        out.append(dict(smp=smp, L=len(x), a_on=a_on, a_off=a_off, b_on=b_on, b_off=b_off, whole=whole, wins=wins))
        if (i + 1) % 20 == 0:
            emb.db.commit()
            log('e4: %d из %d (%.0f с)' % (i + 1, len(samples), time.time() - t0))
    emb.db.commit()
    # счёт
    res = {}
    for T in (0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50):
        for kind in ('AB', 'AC', 'AA'):
            for pause in (0.0, 0.3):
                sub = [o for o in out if o['smp']['kind'] == kind and o['smp']['pause'] == pause]
                # целиком
                whole_rows = []
                for o in sub:
                    m = o['smp']
                    CA = centroid([En[(m['A']['lang'], c)] for c in m['A']['enr'][:k_enr]])
                    CB = centroid([En[(m['B']['lang'], c)] for c in m['B']['enr'][:k_enr]])
                    if o['whole'] is None:
                        continue
                    sa, sb = float(np.dot(o['whole'], CA)), float(np.dot(o['whole'], CB))
                    whole_rows.append((sa, sb))
                wr = np.array(whole_rows)
                res['whole|%s|%.1f|%.2f' % (kind, pause, T)] = dict(
                    n=len(wr), sA_med=float(np.median(wr[:, 0])), sB_med=float(np.median(wr[:, 1])),
                    toA=float(np.mean((wr[:, 0] >= T) & (wr[:, 0] >= wr[:, 1]))),
                    toB=float(np.mean((wr[:, 1] >= T) & (wr[:, 1] > wr[:, 0]))),
                    none=float(np.mean(np.maximum(wr[:, 0], wr[:, 1]) < T)),
                    bothT=float(np.mean((wr[:, 0] >= T) & (wr[:, 1] >= T))))
                for W in WINS:
                    for hop in HOPS:
                        step = int(round(hop / HOPS[0]))
                        found, errs, wrong_t, none_t, sp_t, leak_t, c_t, a_ok_t, clean_ac, fsplit = 0, [], 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0
                        for o in sub:
                            m = o['smp']
                            CA = centroid([En[(m['A']['lang'], c)] for c in m['A']['enr'][:k_enr]])
                            CB = centroid([En[(m['B']['lang'], c)] for c in m['B']['enr'][:k_enr]])
                            wv = o['wins'][str(W)]
                            idx = list(range(0, len(wv['starts']), step))
                            starts = [wv['starts'][j] for j in idx]
                            labs = []
                            for j in idx:
                                e = wv['e'][j]
                                if e is None:
                                    labs.append(None)
                                    continue
                                sa, sb = float(np.dot(e, CA)), float(np.dot(e, CB))
                                best, bs = ('A', sa) if sa >= sb else ('B', sb)
                                labs.append(best if bs >= T else None)
                            labs = smooth3(labs)
                            tl = timeline(starts, wv['w'], o['L'], labs)
                            seq = []
                            for lab in labs:
                                if lab is not None and (not seq or seq[-1] != lab):
                                    seq.append(lab)
                            trueA = (o['a_on'], o['a_off'])
                            trueB = (o['b_on'], o['b_off'])
                            for t0_, t1_, lab in tl:
                                ina = overlap(t0_, t1_, *trueA)
                                inb = overlap(t0_, t1_, *trueB)
                                sp_t += ina + inb
                                if lab is None:
                                    none_t += ina + inb
                                if kind == 'AB':
                                    if lab == 'B':
                                        wrong_t += ina
                                    if lab == 'A':
                                        wrong_t += inb
                                elif kind == 'AA':
                                    if lab == 'B':
                                        wrong_t += ina + inb
                                else:
                                    c_t += inb
                                    if lab is not None:
                                        leak_t += inb
                                    if lab == 'A':
                                        a_ok_t += ina
                                    if lab == 'B':
                                        wrong_t += ina
                            if kind == 'AB' and seq == ['A', 'B']:
                                found += 1
                                lastA = max(j for j, lab in enumerate(labs) if lab == 'A')
                                firstB = min(j for j, lab in enumerate(labs) if lab == 'B' and j > lastA)
                                est = ((starts[lastA] + starts[firstB]) / 2.0 + wv['w'] / 2.0)
                                errs.append(abs(est - (o['a_off'] + o['b_on']) / 2.0) / SR)
                            if kind in ('AC', 'AA') and seq == ['A']:
                                clean_ac += 1
                            if kind == 'AA' and 'B' in seq:
                                fsplit += 1
                        key = '%s|%.1f|%.1f|%.2f|%.2f' % (kind, pause, W, hop, T)
                        res[key] = dict(n=len(sub), found=found / len(sub) if kind == 'AB' else clean_ac / len(sub),
                                        fsplit=fsplit / len(sub),
                                        err_med=float(np.median(errs)) if errs else float('nan'),
                                        err_p90=q(errs, 90) if errs else float('nan'),
                                        err_le05=float(np.mean(np.array(errs) <= 0.5)) if errs else float('nan'),
                                        wrong=wrong_t / sp_t if sp_t else float('nan'), none=none_t / sp_t if sp_t else float('nan'),
                                        leak=leak_t / c_t if c_t else float('nan'))
    save_json(cpath('sections', 'e4.json'), res)
    L = ['## 6. E4. Двое подряд в одном сегменте', '']
    L.append('Сегмент слушания склеен из двух дальних (far) фраз: участник A, затем участник B (оба со слепками из %d '
             'ближних фраз), или A, затем чужой C (в разговоре A и B, C — нет), или — для контроля — A, затем другая фраза '
             'того же A (A+A′: один человек, разрезать нечего). Перед первой фразой — 0,3 с комнаты, после второй — 0,3 с. '
             '«Без паузы» — край речи первой фразы вплотную к началу второй; «пауза 0,3 с» — после первой ещё 0,3 с её же '
             'комнаты (с хвостом реверберации). Фразы с речью короче 0,8 с не брались. Образцов: A+B — %d, A+C — %d, '
             'A+A′ — %d на каждый вариант паузы (зерно %d).' % (k_enr, E4_N['AB'], E4_N['AC'], E4_N['AA'], SEED + 4))
    L.append('')
    L.append('**Весь сегмент одним отпечатком.** Доли, %: отнесён к A / к B / ни к кому (оба ниже порога) · оба слепка '
             'на пороге и выше; медианы косинусов к слепкам A и B:')
    L.append('')
    rows = []
    KN = {'AB': 'A+B', 'AC': 'A+C', 'AA': 'A+A′'}
    for kind in ('AB', 'AC', 'AA'):
        for pause in (0.0, 0.3):
            r0 = res['whole|%s|%.1f|0.50' % (kind, pause)]
            row = [KN[kind], 'нет' if pause == 0 else '0,3 с', fnum(r0['sA_med'], 2), fnum(r0['sB_med'], 2)]
            for T in (0.35, 0.40, 0.45):
                r = res['whole|%s|%.1f|%.2f' % (kind, pause, T)]
                row.append('%s / %s / %s · %s' % (ipct(r['toA']), ipct(r['toB']), ipct(r['none']), ipct(r['bothT'])))
            rows.append(row)
    L.append(table(['сегмент', 'пауза', 'косинус к A, медиана', 'к B, медиана', 'T 0,35: A / B / никто · оба', 'T 0,40', 'T 0,45'], rows))
    L.append('')
    L.append('**Скользящее окно.** Окно W с шагом hop; окно отдаётся ближайшему слепку, если косинус ≥ T, иначе «никому»; '
             'сглаживание — большинство из трёх соседних окон. Разрез найден, если после сглаживания метки идут ровно '
             '«A…B» (пропуски «никому» допустимы); место разреза — середина между центрами последнего окна A и первого '
             'окна B, ошибка — от середины промежутка между речью A и речью B. «Не тому» — доля времени речи, '
             'отнесённая к другому участнику; «никому» — доля речи без метки. «Ложный разрез» у A+A′ — в метках '
             'одного человека появилась метка B.')
    L.append('')
    for T in (0.30, 0.35, 0.40):
        rows = []
        for W in WINS:
            for hop in HOPS:
                r0 = res['AB|0.0|%.1f|%.2f|%.2f' % (W, hop, T)]
                r1 = res['AB|0.3|%.1f|%.2f|%.2f' % (W, hop, T)]
                r2 = res['AC|0.0|%.1f|%.2f|%.2f' % (W, hop, T)]
                r3 = res['AA|0.0|%.1f|%.2f|%.2f' % (W, hop, T)]
                rows.append([fnum(W, 1), fnum(hop, 2),
                             '%s · %s · %s' % (pct(r0['found'], 0), fnum(r0['err_med'], 2), fnum(r0['err_p90'], 2)),
                             '%s · %s' % (pct(r0['wrong'], 0), pct(r0['none'], 0)),
                             '%s · %s · %s' % (pct(r1['found'], 0), fnum(r1['err_med'], 2), fnum(r1['err_p90'], 2)),
                             '%s · %s' % (pct(r2['found'], 0), pct(r2['leak'], 0)),
                             '%s · %s · %s' % (pct(r3['fsplit'], 0), pct(r3['wrong'], 0), pct(r3['none'], 0))])
        L.append('Порог окна T = %s:' % fnum(T, 2))
        L.append('')
        L.append(table(['W, с', 'hop, с', 'A+B без паузы: разрез найден · ошибка медиана · p90, с',
                        'A+B без паузы: не тому · никому', 'A+B пауза 0,3: найден · медиана · p90',
                        'A+C: только «A» · речь C принята', 'A+A′: ложный разрез · не тому · никому'], rows))
        L.append('')
    L.append('Цена (на секунду входа): вызовов отпечатка 1/hop; звука через модель W/hop секунд (признаки соседних окон '
             'можно не пересчитывать, но сама модель считает каждое окно заново). Время — **на ПК, не на телефоне** '
             '(медиана 15 вызовов эталона на окно W из дальней записи, 2 потока), только для соотношений:')
    L.append('')
    a = al['far-pt']['clips']
    first = sorted(a.values(), key=lambda v: v['pos'])[0]['pos']
    src = room('far-pt', first, first + 3 * SR)
    t_w = {}
    for W in WINS:
        xw = src[:int(W * SR)]
        for _ in range(3):
            embed_ref(xw)
        ts_ = []
        for _ in range(15):
            t = time.perf_counter()
            embed_ref(xw)
            ts_.append((time.perf_counter() - t) * 1000)
        t_w[W] = float(np.median(ts_))
    rows = []
    for W in WINS:
        for hop in HOPS:
            rows.append([fnum(W, 1), fnum(hop, 2), fnum(1 / hop, 0), fnum(W / hop, 1), fnum(t_w[W], 0), fnum(t_w[W] / hop, 0)])
    L.append(table(['W, с', 'hop, с', 'вызовов на 1 с входа', 'секунд звука через модель на 1 с входа',
                    'одно окно, мс (ПК)', 'мс счёта на 1 с входа (ПК)'], rows))
    put_section('e4', '\n'.join(L))


def mix_parts(pa, pb, g):
    """Сумма двух отрезков равной длины (B с усилением g)."""
    xa, xb = room(*pa), room(*pb)
    return np.clip(xa + g * xb, -1.0, 1.0)


def stage_e5(emb, redo, k_enr=ENR_MAX):
    if section_done('e5', redo):
        return
    inv = inventory()
    al = alignment(inv)
    ix = index(inv)
    spk = speakers(inv)
    En = all_embs(emb, al, inv, 'near')
    elig = eligible(inv)
    avail = far_avail(al, ix)
    everyone = [(l, s) for l in LANGS for s in spk[l] if any((l, c) in avail for c in spk[l][s])]
    rng = random.Random(SEED + 5)
    dur = lambda k: (ix[k]['off'] - ix[k]['on']) / SR   # noqa: E731
    res = {}
    # полное наложение: A + B (участники), A + C (чужой)
    for kind in ('AB', 'AC'):
        got, rows, tries = 0, [], 0
        while got < E5_N[kind]:
            tries += 1
            if tries > 200 * E5_N[kind]:
                raise SystemExit('e5: не набрать образцов %s' % kind)
            A, B = rng.sample(elig, 2)
            mA, mB = pick_member(rng, spk, A, avail), pick_member(rng, spk, B, avail)
            if not mA['rest'] or (kind == 'AB' and not mB['rest']):
                continue
            a = (A[0], rng.choice(mA['rest']))
            if kind == 'AB':
                o = (B[0], rng.choice(mB['rest']))
            else:
                C = rng.choice([p for p in everyone if p not in (A, B)])
                o = (C[0], rng.choice([c for c in spk[C[0]][C[1]] if (C[0], c) in avail]))
            Lm = min(dur(a), dur(o))
            if Lm < 1.0:
                continue
            n = int(round(Lm * SR))
            pa = span(al, ix, 'far', a[0], a[1], 'speech')
            pb = span(al, ix, 'far', o[0], o[1], 'speech')
            pa = (pa[0], pa[1], pa[1] + n)
            pb = (pb[0], pb[1], pb[1] + n)
            xa, xb = room(*pa), room(*pb)
            pwa, pwb = float(np.mean(xa.astype(np.float64) ** 2)), float(np.mean(xb.astype(np.float64) ** 2))
            CA = centroid([En[(mA['lang'], c)] for c in mA['enr'][:k_enr]])
            CB = centroid([En[(mB['lang'], c)] for c in mB['enr'][:k_enr]])
            alone = emb.get(spec_of(pa), xa)
            row = dict(alone=float(np.dot(alone, CA)) if alone is not None else None, sir={})
            for sir in SIRS:
                g = math.sqrt(pwa / (pwb * 10 ** (sir / 10.0)))
                spec = 'mix|%s|%s|%.6f' % (spec_of(pa), spec_of(pb), g)
                e = emb.get(spec, lambda: mix_parts(pa, pb, g))
                if e is None:
                    continue
                row['sir'][sir] = (float(np.dot(e, CA)), float(np.dot(e, CB)))
            rows.append(row)
            got += 1
            if got % 25 == 0:
                emb.db.commit()
                log('e5: %s %d из %d' % (kind, got, E5_N[kind]))
        res[kind] = rows
    # частичное наложение: A говорит две фразы подряд (длинных одиночных фраз в корпусе мало),
    # B (1 с) вступает поверх через 0,75 с от начала речи A, 0 дБ на участке наложения
    part = []
    got, tries = 0, 0
    while got < E5_N['part']:
        tries += 1
        if tries > 500 * E5_N['part']:
            raise SystemExit('e5: не набрать образцов с частичным наложением')
        A, B = rng.sample(elig, 2)
        mA, mB = pick_member(rng, spk, A, avail), pick_member(rng, spk, B, avail)
        candb = [c for c in mB['rest'] if dur((B[0], c)) >= 1.0]
        if len(mA['rest']) < 2 or not candb:
            continue
        a1, a2 = rng.sample(mA['rest'], 2)
        if dur((A[0], a1)) + dur((A[0], a2)) < 2.5:
            continue
        o = (B[0], rng.choice(candb))
        p1 = span(al, ix, 'far', A[0], a1, 'speech', pre=0.3, post=0.15)
        p2 = span(al, ix, 'far', A[0], a2, 'speech', pre=0.0, post=0.3)
        pb = span(al, ix, 'far', o[0], o[1], 'speech')
        pb = (pb[0], pb[1], pb[1] + SR)
        xa, specA = build([p1 + (1.0,), p2 + (1.0,)])
        xb = room(*pb)
        off = int(round((0.3 + 0.75) * SR))
        seg_a = xa[off:off + SR].astype(np.float64)
        g = math.sqrt(float(np.mean(seg_a ** 2)) / float(np.mean(xb.astype(np.float64) ** 2)))
        x = xa.copy()
        x[off:off + SR] = np.clip(x[off:off + SR] + g * xb, -1.0, 1.0)
        spec = 'part|%s|%s|%.6f|%d' % (specA, spec_of(pb), g, off)
        CA = centroid([En[(mA['lang'], c)] for c in mA['enr'][:k_enr]])
        CB = centroid([En[(mB['lang'], c)] for c in mB['enr'][:k_enr]])
        whole = emb.get(spec, x)
        wl = []
        for W in (1.0, 1.5):
            starts, w = win_starts(len(x), W, 0.25)
            for s in starts:
                e = emb.get('%s|w%d:%d' % (spec, s, s + w), x[s:s + w])
                if e is None:
                    continue
                f = overlap(s, s + w, off, off + SR) / w
                wl.append((W, f, float(np.dot(e, CA)), float(np.dot(e, CB))))
        part.append(dict(whole=(float(np.dot(whole, CA)), float(np.dot(whole, CB))), wins=wl))
        got += 1
        if got % 25 == 0:
            emb.db.commit()
            log('e5: part %d из %d' % (got, E5_N['part']))
    emb.db.commit()
    res['part'] = part
    save_json(cpath('sections', 'e5.json'), res)
    L = ['## 7. E5. Одновременная речь', '']
    L.append('Две дальние (far) фразы, обрезанные до одной длины (по более короткой речи, не меньше 1 с), сложены; '
             'B ослаблен так, чтобы A был громче на SIR дБ (мощность по всему отрезку). Слепки A и B — из %d ближних фраз. '
             '«A один» — тот же отрезок A без наложения. A+C — второй голос чужой (в разговоре A и B). '
             'Пары: A+B — %d, A+C — %d (зерно %d).' % (k_enr, E5_N['AB'], E5_N['AC'], SEED + 5))
    L.append('')
    rows = []
    for kind in ('AB', 'AC'):
        rr = res[kind]
        al_ = np.array([r['alone'] for r in rr if r['alone'] is not None])
        for T in (0.35, 0.40, 0.45):
            row = ['A+B' if kind == 'AB' else 'A+C', fnum(T, 2), '%s (%s)' % (fnum(float(np.median(al_)), 2), ipct(float(np.mean(al_ >= T))))]
            for sir in SIRS:
                v = np.array([r['sir'][sir] for r in rr if sir in r['sir']])
                toA = np.mean((v[:, 0] >= T) & (v[:, 0] >= v[:, 1]))
                toB = np.mean((v[:, 1] >= T) & (v[:, 1] > v[:, 0]))
                both = np.mean((v[:, 0] >= T) & (v[:, 1] >= T))
                row.append('%s · %s → %s / %s / %s · %s' % (fnum(float(np.median(v[:, 0])), 2), fnum(float(np.median(v[:, 1])), 2),
                                                          ipct(toA), ipct(toB), ipct(1 - toA - toB), ipct(both)))
            rows.append(row)
    L.append('В клетке: медиана косинуса к A · к B → доля: к A / к B / никому · оба выше порога, %. '
             'Для A+C «к B» — ошибка «не тот участник», а косинус «к B» — к слепку молчащего B.')
    L.append('')
    L.append(table(['наложение', 'порог', 'A один: медиана (≥T, %)'] + ['SIR %+d дБ' % s for s in SIRS], rows))
    L.append('')
    L.append('**Частичное наложение**: A говорит две свои дальние фразы подряд (речи вместе ≥ 2,5 с; длинных одиночных '
             'фраз в корпусе мало), B вступает поверх на 1 с через 0,75 с от начала речи A, 0 дБ на участке наложения; '
             '%d образцов. Окна W с шагом 0,25 с; метка окна при T = %s по доле окна под наложением:' % (len(part), fnum(T_PART, 2)))
    L.append('')
    rows = []
    allw = [x for p in part for x in p['wins']]
    T = T_PART
    for W in (1.0, 1.5):
        for name, test in (('0 (только A)', lambda f: f == 0), ('до половины', lambda f: 0 < f < 0.5),
                           ('половина и больше', lambda f: f >= 0.5)):
            v = np.array([(sa, sb) for (w, f, sa, sb) in allw if w == W and test(f)])
            if len(v) == 0:
                continue
            toA = np.mean((v[:, 0] >= T) & (v[:, 0] >= v[:, 1]))
            toB = np.mean((v[:, 1] >= T) & (v[:, 1] > v[:, 0]))
            rows.append([fnum(W, 1), name, len(v), fnum(float(np.median(v[:, 0])), 2), fnum(float(np.median(v[:, 1])), 2),
                         ipct(toA), ipct(toB), ipct(1 - toA - toB)])
    L.append(table(['W, с', 'под наложением', 'окон', 'к A, медиана', 'к B, медиана', 'A, %', 'B, %', 'никому, %'], rows))
    wh = np.array([p['whole'] for p in part])
    L.append('')
    L.append('Весь такой сегмент одним отпечатком: медиана к A %s, к B %s; при T = %s к A — %s%%, к B — %s%%.' % (
        fnum(float(np.median(wh[:, 0])), 2), fnum(float(np.median(wh[:, 1])), 2), fnum(T_W5, 2),
        ipct(float(np.mean((wh[:, 0] >= T_W5) & (wh[:, 0] >= wh[:, 1])))), ipct(float(np.mean((wh[:, 1] >= T_W5) & (wh[:, 1] > wh[:, 0]))))))
    put_section('e5', '\n'.join(L))


# ---------------------------------------------------------------- E6

def stage_e6(emb, redo):
    if section_done('e6', redo):
        return
    import sherpa_onnx
    inv = inventory()
    al = alignment(inv)
    cpu = '?'
    for line in open('/proc/cpuinfo'):
        if line.startswith('model name'):
            cpu = line.split(':', 1)[1].strip()
            break
    import onnxruntime as ort
    import voiceprint_ref
    loads = []
    for _ in range(3):
        t = time.time()
        so_ = ort.SessionOptions()
        so_.intra_op_num_threads = 2
        sess = ort.InferenceSession(MODEL, so_, providers=['CPUExecutionProvider'])
        loads.append((time.time() - t) * 1000)
    ex = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=MODEL, num_threads=2))
    # звук: подряд из дальней записи; время от содержания не зависит
    a = al['far-pt']['clips']
    first = sorted(a.values(), key=lambda v: v['pos'])[0]['pos']
    src = room('far-pt', first, first + 12 * SR)

    def timed(fn, n=15):
        for _ in range(3):
            fn()
        ts_ = []
        for _ in range(n):
            t = time.perf_counter()
            fn()
            ts_.append((time.perf_counter() - t) * 1000)
        return float(np.median(ts_)), q(ts_, 90)

    rows, res = [], {}
    for d in (0.6, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0):
        x = src[:int(d * SR)]
        F = voiceprint_ref.fbank(x)
        tf, _ = timed(lambda: voiceprint_ref.fbank(x))
        tm, _ = timed(lambda: sess.run(None, {'x': F[None]}))
        tt, tt90 = timed(lambda: embed_ref(x))
        tsh, _ = timed(lambda: embed_sherpa(ex, x))
        res[str(d)] = dict(fbank=tf, model=tm, total=tt, total_p90=tt90, sherpa=tsh)
        rows.append([fnum(d, 1), fnum(tf, 1), fnum(tm, 0), fnum(tt, 0), fnum(tt / d, 0), fnum(tt90, 0), fnum(tsh, 0)])
    save_json(cpath('sections', 'e6.json'), dict(cpu=cpu, load_ms=loads, embed_ms=res))
    # диаризация в sherpa-onnx
    names = [n for n in dir(sherpa_onnx) if 'Diariz' in n or 'Segmentation' in n or 'Clustering' in n]
    hdr = os.path.join(os.path.dirname(sherpa_onnx.__file__), 'include', 'sherpa-onnx', 'c-api', 'c-api.h')
    hint = []
    if os.path.exists(hdr):
        inside = False
        for line in open(hdr, encoding='utf-8', errors='replace'):
            if 'Example based on' in line and 'diarization' in line:   # пример конфигурации диаризации в C API
                inside = True
            elif inside and '@endcode' in line:
                break
            elif inside and '.onnx' in line:
                hint.append(line.strip().strip('*').strip().rstrip(';').strip('"').lstrip('./'))
    jni = [p for p in glob.glob(os.path.join(R, 'bench', 'apk', 'jni', '*', '*sherpa*.so'))]
    jni_has = []
    for p in jni:
        found = False
        with open(p, 'rb') as f:
            tail = b''
            while True:
                b = f.read(1 << 20)
                if not b:
                    break
                if b'OfflineSpeakerDiarization' in tail + b:
                    found = True
                    break
                tail = b[-64:]
        jni_has.append((os.path.basename(p), found))
    local = []
    for base in (os.path.join(R, 'models'),):
        for p in glob.glob(os.path.join(base, '**', '*.onnx'), recursive=True):
            if 'pyannote' in p.lower() or 'segmentation' in p.lower():
                local.append(p)
    L = ['## 8. E6. Время счёта и диаризация', '']
    L.append('**ПК, не телефон** (%s, ONNX Runtime %s, 2 потока; признаки — numpy). Числа годятся только для соотношений: '
             'сколько стоит окно против целого сегмента и что дороже — признаки или модель. Задержка на телефоне здесь '
             'не измерялась. Загрузка модели (сессия ONNX Runtime): %s мс (три раза).' % (
                 cpu, ort.__version__, ', '.join(fnum(x, 0) for x in loads)))
    L.append('')
    L.append(table(['звук, с', 'признаки, мс', 'модель, мс', 'эталон целиком, мс (медиана из 15)', 'мс на секунду звука',
                    'p90, мс', 'для сравнения: sherpa-onnx с 0,5 с нулей, мс'], rows))
    L.append('')
    L.append('**Диаризация в sherpa-onnx.** В Python-пакете есть: %s. Ей нужны три части: модель сегментации pyannote '
             '(`OfflineSpeakerSegmentationPyannoteModelConfig(model=…, window_shift_ratio=0.1)`), модель отпечатка '
             '(`SpeakerEmbeddingExtractorConfig` — **тот самый путь, который по E1 людей не различает**, так что и '
             'диаризация унаследует ту же беду) и кластеризация (`FastClusteringConfig(num_clusters=-1, threshold=0.5)`; '
             'число говорящих можно задать заранее), плюс `min_duration_on=0.3`, `min_duration_off=0.5`. Пример в '
             'заголовке C API называет файлы: %s. Файла сегментации на этой машине нет (%s), размер его по локальным '
             'документам не установить; ничего не скачивалось. Java-обёртки `OfflineSpeakerDiarization*` лежат в '
             '`bench/apk/sherpa-java-api/`; строка `OfflineSpeakerDiarization` в нативных библиотеках приложения: %s. '
             'Это офлайн-диаризация готовой записи («кто когда говорил»), а не потоковая — для сегмента слушания её '
             'пришлось бы звать на каждый сегмент целиком.' % (
                 ', '.join('`%s`' % n for n in names),
                 '; '.join('`%s`' % h for h in hint) if hint else 'нет',
                 'в models/ — ничего с pyannote/segmentation' if not local else ', '.join(local),
                 ', '.join('`%s` — %s' % (n, 'есть' if h else 'нет') for n, h in jni_has) if jni_has else 'не проверено (нет .so)'))
    put_section('e6', '\n'.join(L))


# ---------------------------------------------------------------- запуск

STAGES = [('data', stage_data), ('e1', stage_e1), ('pad', stage_pad), ('e2', stage_e2), ('e3', stage_e3),
          ('e4', stage_e4), ('e5', stage_e5), ('e6', stage_e6)]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('stages', nargs='+', help='all | status | ' + ' | '.join(n for n, _ in STAGES))
    ap.add_argument('--redo', action='store_true', help='пересчитать таблицы этапа (отпечатки остаются из кэша)')
    a = ap.parse_args()
    os.makedirs(CACHE, exist_ok=True)
    if not os.path.exists(MODEL):
        raise SystemExit('нет модели: ' + MODEL)
    emb = Emb()
    try:
        if a.stages == ['status']:
            print('кэш %s: отпечатков %d' % (CACHE, emb.count()))
            for n, _ in STAGES:
                print('  %-5s %s' % (n, 'готов' if os.path.exists(cpath('sections', n + '.md')) else '—'))
            return
        todo = [n for n, _ in STAGES] if a.stages == ['all'] else a.stages
        for n in todo:
            fn = dict(STAGES).get(n)
            if fn is None:
                raise SystemExit('нет такого этапа: ' + n)
            t = time.time()
            log('== %s' % n)
            fn(emb, a.redo)
            emb.db.commit()
            log('== %s готов за %.0f с (новых отпечатков всего %d, счёт %.0f с)' % (n, time.time() - t, emb.new, emb.t_new))
    finally:
        emb.close()


if __name__ == '__main__':
    main()
