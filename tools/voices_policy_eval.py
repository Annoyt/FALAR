#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Голоса собеседников в людном месте: что дают правки фильтра «чужой голос» (results/2026-11-05-voices-public.md).
Записи комнаты шагов 15–16 (куски и тексты распознавания путём приложения — каталог results/asr_conf ветки
asr-confidence): собеседник — самый частый диктор записи, его первые 3 фразы — как кнопкой FALAR; остальные куски
идут лентой через варианты фильтра (сейчас, A сторож, B чёткий, C всё, D короткий после своего).

    embed   отпечатки кусков на видеокарте (владелец 01.11: «запускай только на гпу») — в .stand/voices-policy/
        V=~/tools/venv-comet-gpu/lib/python3.12/site-packages/nvidia
        LD_LIBRARY_PATH=$V/cu13/lib:$V/cudnn/lib PYTHONPATH=~/tools/ort-gpu-pkg ~/tools/venv-comet-gpu/bin/python tools/voices_policy_eval.py embed
    score   варианты и итог: taskset -c 0 .venv/bin/python tools/voices_policy_eval.py score
"""
import collections
import json
import os
import sys

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.dirname(os.path.dirname(R)) if os.path.basename(os.path.dirname(R)) == '.worktrees' else R
DATA = os.environ.get('ASR_CONF', os.path.join(MAIN, '.worktrees', 'asrconf', 'results', 'asr_conf'))
INV = os.path.join(MAIN, '.worktrees', 'voices-cache', 'inventory.json')
OUT = os.path.join(MAIN, '.stand', 'voices-policy')
MODEL = os.path.join(MAIN, 'models', 'speaker', '3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx')
SR = 16000
QUIET = ['near-pt', 'near-ru', 'nearbg40-pt', 'nearbg40-ru', 'nearbg45-pt', 'far-pt', 'far-ru', 's10-quiet-pt',
         'st-laptop-bottom-pt', 'st-laptop-bottom-ru', 'st-laptop-end-pt', 'st-laptop-end-ru', 'st-laptop-near-pt',
         'st-laptop-near-ru']
NOISY = ['noisy-pt', 'pc-cafe-pt', 'pc-cafe-ru', 's10-cafe-pt', 's10-cafe-ru', 'pc-street-pt', 's10-street-pt']
ENR, MIN_S = 3, 0.6                       # фраз кнопкой на слепок; короче — отпечаток не считается (Speaker.MIN_SECONDS)
OPTS = ('сейчас', 'A сторож', 'B чёткий', 'C всё', 'D короткий после своего')


def hear(k):                               # Voices.hear
    return 0.38 if k <= 1 else 0.40 if k == 2 else 0.42


def chunks(name):
    d = np.load(os.path.join(DATA, f'{name}.chunks.npz'))
    edges = np.concatenate([[0], np.cumsum(d['lens'])])
    return [d['audio'][edges[k]:edges[k + 1]].astype(np.float32) / 32768 for k in range(len(d['lens']))]


def embed():
    sys.path.insert(0, os.path.join(R, 'tools'))
    import onnxruntime as ort
    import voiceprint_ref as vp
    so = ort.SessionOptions(); so.log_severity_level = 3
    sess = ort.InferenceSession(MODEL, so, providers=['CUDAExecutionProvider', 'CPUExecutionProvider'])
    print('отпечаток на', sess.get_providers()[0], flush=True)
    os.makedirs(OUT, exist_ok=True)
    for name in QUIET + NOISY:
        path = os.path.join(OUT, f'{name}.emb.npy')
        if os.path.exists(path):
            continue
        E = []
        for x in chunks(name):
            f = vp.fbank(x) if len(x) >= MIN_S * SR else np.zeros((0, 80), np.float32)
            if len(f) == 0:
                E.append(np.zeros(512)); continue
            e = sess.run(None, {'x': f[None]})[0][0].astype(np.float64)
            E.append(e / np.linalg.norm(e))
        np.save(path, np.array(E))
        print(f'{name}: кусков {len(E)}', flush=True)


def snr_db(x):
    """Речь над фоном внутри куска: 90-й процентиль уровня окон 20 мс против 10-го (у куска есть поля тишины)."""
    n = 320
    fr = x[:len(x) // n * n].reshape(-1, n)
    if len(fr) < 5:
        return 0.0
    db = 20 * np.log10(np.sqrt((fr.astype(np.float64) ** 2).mean(1)) + 1e-9)
    return float(np.percentile(db, 90) - np.percentile(db, 10))


def load_rec(name):
    sys.path.insert(0, os.path.join(R, 'tools')); sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
    import dualmic_eval as de
    import vad_denoise_eval as vde
    lang = name.split('-')[-1]
    if name.startswith('st-'):
        _, plays, gap, lang = de.load(name)
    else:
        _, plays, gap = vde.load_rec(name)
        plays = [p for p in plays if os.path.exists(ref_file(lang, p[0]))]
    return plays, gap, lang, vde


def base(name):
    return name[:-4] if name.endswith('.wav') else name          # в записи комнаты фраза — с «.wav»


def ref_file(lang, name):
    """Эталон фразы: корпус bench/air/corpus; новые фразы шага 9 — bench/air/fresh (как vad_denoise_eval.ref_file
    ветки asr-confidence; в main его ещё нет)."""
    for d in ('corpus', 'fresh'):
        p = os.path.join(MAIN, 'bench', 'air', d, lang, base(name) + '.txt')
        if os.path.exists(p):
            return p
    return os.path.join(MAIN, 'bench', 'air', 'corpus', lang, base(name) + '.txt')


def decide(rows, thr):
    """rows по времени: dict(t0, t1, dur, cos, snr, text_words, kind). Возвращает {вариант: [принят?]}."""
    out = {}
    for opt in OPTS:
        acc, last_end, trig = [], -1e9, False
        rej_t, ok_t = collections.deque(), collections.deque()
        for r in rows:
            if not r['words']:                 # пусто — переводить нечего, голос не решается
                acc.append(False); continue
            base = r['dur'] >= MIN_S and r['cos'] >= thr
            if opt == 'сейчас':
                a = base
            elif opt == 'A сторож':
                a = base or trig
                if not a:
                    rej_t.append(r['t0'])
                    while rej_t and rej_t[0] < r['t0'] - 60: rej_t.popleft()
                    while ok_t and ok_t[0] < r['t0'] - 60: ok_t.popleft()
                    if len(rej_t) >= 4 and not ok_t:
                        trig = True            # вопрос на экране; человек согласился — дальше переводятся все
            elif opt == 'B чёткий':
                a = base or r['snr'] >= 12
            elif opt == 'C всё':
                a = True
            else:
                a = base or (r['dur'] < 1.5 and r['t0'] - last_end < 8)
            if a:
                last_end = r['t1']; ok_t.append(r['t0'])
            acc.append(a)
        out[opt] = acc
    return out


def score():
    sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
    from wer2 import norm, wer
    inv = json.load(open(INV, encoding='utf-8'))
    spk = {(L, c['id']): c['spk'] for L in inv for c in inv[L] if c['spk']}
    for L in ('pt', 'ru'):                    # свежие фразы шага 9: диктор — в 4-м столбце LICENSES.tsv
        f = os.path.join(MAIN, 'bench', 'air', 'fresh', L, 'LICENSES.tsv')
        for line in open(f, encoding='utf-8') if os.path.exists(f) else []:
            c = line.rstrip('\n').split('\t')
            if len(c) >= 4 and c[3]:
                spk.setdefault((L, c[0]), c[3])
    tot = {}
    for group, recs in (('тихо', QUIET), ('шум', NOISY)):
        for name in recs:
            cache = json.load(open(os.path.join(DATA, f'{name}.json'), encoding='utf-8'))
            segs = [tuple(s) for s in cache['segs']]
            texts = json.load(open(os.path.join(DATA, f'{name}.ft16-base.json'), encoding='utf-8'))['texts']
            E = np.load(os.path.join(OUT, f'{name}.emb.npy'))
            X = chunks(name)
            plays, gap, lang, vde = load_rec(name)
            wins = []
            for k, (pn, at, dur) in enumerate(plays):
                end = at + dur + gap / 1000.0
                if k + 1 < len(plays):
                    end = min(end, plays[k + 1][1])
                wins.append((pn, at, end))
            win_of = []
            for s in segs:
                w = next((i for i, w in enumerate(wins) if w[1] <= s[2] / SR <= w[2]), None)
                win_of.append(w)
            # собеседник — самый частый диктор среди окон с кусками
            used = sorted(set(w for w in win_of if w is not None))
            cnt = collections.Counter(spk.get((lang, base(wins[w][0]))) for w in used if spk.get((lang, base(wins[w][0]))))
            if not cnt:
                continue
            me = cnt.most_common(1)[0][0]
            mine = [w for w in used if spk.get((lang, base(wins[w][0]))) == me]
            enr = mine[:ENR] if len(mine) > ENR else mine[:max(1, len(mine) - 1)]
            pw = []
            for w in enr:
                ks = [k for k, x in enumerate(win_of) if x == w and np.any(E[k])]
                if ks:
                    v = E[ks].mean(0); pw.append(v / np.linalg.norm(v))
            if not pw:
                continue
            p = np.mean(pw, 0); p /= np.linalg.norm(p)
            thr = hear(len(pw))
            rows = []
            for k, s in enumerate(segs):
                if win_of[k] in enr:
                    continue                   # фразы кнопкой — не слушание
                rows.append(dict(k=k, t0=s[0] / SR, t1=s[1] / SR, dur=len(X[k]) / SR, cos=float(E[k] @ p) if np.any(E[k]) else -1.0,
                                 snr=snr_db(X[k]), words=len(norm(texts[k])), win=win_of[k]))
            dec = decide(rows, thr)
            meta = os.path.join(MAIN, 'bench', 'air', 'rec', name, 'rec.txt')
            dur_s = float(dict(kv.split('=', 1) for kv in open(meta, encoding='utf-8').read().split() if '=' in kv).get('длительность', 0) or 0) \
                if os.path.exists(meta) else 0.0
            dur_s = dur_s or (wins[-1][2] if wins else 0)
            for opt in OPTS:
                t = tot.setdefault((group, opt), dict(mine_ok=0, mine_n=0, mine_e=0, mine_w=0, oth_ok=0, oth_n=0, oth_e=0, oth_w=0,
                                                      junk=0, junk_words=0, sec=0.0))
                if opt == OPTS[0]:
                    t['sec'] += dur_s
                heard = collections.defaultdict(list)
                for r, a in zip(rows, dec[opt]):
                    if not a:
                        continue
                    if r['win'] is None:
                        t['junk'] += 1; t['junk_words'] += r['words']
                    else:
                        heard[r['win']].append(texts[r['k']])
                for w in set(r['win'] for r in rows if r['win'] is not None):
                    ref = open(ref_file(lang, wins[w][0]), encoding='utf-8').read().strip()
                    e, n = wer(ref, ' '.join(heard.get(w, [])))
                    side = 'mine' if spk.get((lang, base(wins[w][0]))) == me else 'oth'
                    t[side + '_n'] += 1; t[side + '_e'] += e; t[side + '_w'] += n
                    t[side + '_ok'] += int(n > 0 and e / n <= 0.5)
    secs = {g: tot[(g, OPTS[0])]['sec'] for g in ('тихо', 'шум')}
    for g in ('тихо', 'шум'):
        print(f'\n{g} ({secs[g] / 60:.0f} мин записей)')
        print('| вариант | свои: осмысленных | свои WER | другие люди: осмысленных | другие WER | лишних текстов / 10 мин | лишних слов / 10 мин |')
        print('|---|---|---|---|---|---|---|')
        for opt in OPTS:
            t = tot[(g, opt)]
            per10 = 600 / max(1, secs[g])
            print(f"| {opt} | {t['mine_ok']} из {t['mine_n']} | {100 * t['mine_e'] / max(1, t['mine_w']):.1f} % | "
                  f"{t['oth_ok']} из {t['oth_n']} | {100 * t['oth_e'] / max(1, t['oth_w']):.1f} % | "
                  f"{t['junk'] * per10:.1f} | {t['junk_words'] * per10:.1f} |", flush=True)


if __name__ == '__main__':
    {'embed': embed, 'score': score}[sys.argv[1]]()
