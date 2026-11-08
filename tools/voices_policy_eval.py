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
    labels  подписи «кто сказал» для голосов, прошедших по B (раздел «Подписи»): taskset -c 0 .venv/bin/python … labels
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


def speakers():
    """Кто говорит фразу: (язык, id) → диктор Tatoeba."""
    inv = json.load(open(INV, encoding='utf-8'))
    spk = {(L, c['id']): c['spk'] for L in inv for c in inv[L] if c['spk']}
    for L in ('pt', 'ru'):                    # свежие фразы шага 9: диктор — в 4-м столбце LICENSES.tsv
        f = os.path.join(MAIN, 'bench', 'air', 'fresh', L, 'LICENSES.tsv')
        for line in open(f, encoding='utf-8') if os.path.exists(f) else []:
            c = line.rstrip('\n').split('\t')
            if len(c) >= 4 and c[3]:
                spk.setdefault((L, c[0]), c[3])
    return spk


def prep(name, spk, norm):
    """Запись: окна фраз, лента кусков (без фраз кнопкой), собеседник и отпечатки его фраз кнопкой. None — не годится."""
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
    who_of = {w: spk.get((lang, base(wins[w][0]))) or f'?{w}' for w in range(len(wins))}
    # собеседник — самый частый диктор среди окон с кусками
    used = sorted(set(w for w in win_of if w is not None))
    cnt = collections.Counter(spk.get((lang, base(wins[w][0]))) for w in used if spk.get((lang, base(wins[w][0]))))
    if not cnt:
        return None
    me = cnt.most_common(1)[0][0]
    mine = [w for w in used if spk.get((lang, base(wins[w][0]))) == me]
    enr = mine[:ENR] if len(mine) > ENR else mine[:max(1, len(mine) - 1)]
    pw = []
    for w in enr:
        ks = [k for k, x in enumerate(win_of) if x == w and np.any(E[k])]
        if ks:
            v = E[ks].mean(0); pw.append(v / np.linalg.norm(v))
    if not pw:
        return None
    p = np.mean(pw, 0); p /= np.linalg.norm(p)
    rows = []
    for k, s in enumerate(segs):
        if win_of[k] in enr:
            continue                           # фразы кнопкой — не слушание
        rows.append(dict(k=k, t0=s[0] / SR, t1=s[1] / SR, dur=len(X[k]) / SR, cos=float(E[k] @ p) if np.any(E[k]) else -1.0,
                         e=E[k] if np.any(E[k]) else None, snr=snr_db(X[k]), words=len(norm(texts[k])), win=win_of[k],
                         who=None if win_of[k] is None else who_of[win_of[k]]))
    meta = os.path.join(MAIN, 'bench', 'air', 'rec', name, 'rec.txt')
    dur_s = float(dict(kv.split('=', 1) for kv in open(meta, encoding='utf-8').read().split() if '=' in kv).get('длительность', 0) or 0) \
        if os.path.exists(meta) else 0.0
    dur_s = dur_s or (wins[-1][2] if wins else 0)
    return dict(rows=rows, wins=wins, who_of=who_of, me=me, pw=pw, thr=hear(len(pw)), lang=lang, texts=texts, dur_s=dur_s)


def new_tot():
    return dict(mine_ok=0, mine_n=0, mine_e=0, mine_w=0, oth_ok=0, oth_n=0, oth_e=0, oth_w=0, junk=0, junk_words=0, sec=0.0)


def text_stats(t, d, acc, wer):
    """Осмысленные фразы, WER и лишние тексты записи d по принятым кускам acc — в счётчики t."""
    t['sec'] += d['dur_s']
    heard = collections.defaultdict(list)
    for r, a in zip(d['rows'], acc):
        if not a:
            continue
        if r['win'] is None:
            t['junk'] += 1; t['junk_words'] += r['words']
        else:
            heard[r['win']].append(d['texts'][r['k']])
    for w in set(r['win'] for r in d['rows'] if r['win'] is not None):
        ref = open(ref_file(d['lang'], d['wins'][w][0]), encoding='utf-8').read().strip()
        e, n = wer(ref, ' '.join(heard.get(w, [])))
        side = 'mine' if d['who_of'][w] == d['me'] else 'oth'
        t[side + '_n'] += 1; t[side + '_e'] += e; t[side + '_w'] += n
        t[side + '_ok'] += int(n > 0 and e / n <= 0.5)


def text_table(rows_tot, sec):
    print('| вариант | свои: осмысленных | свои WER | другие люди: осмысленных | другие WER | лишних текстов / 10 мин | лишних слов / 10 мин |')
    print('|---|---|---|---|---|---|---|')
    per10 = 600 / max(1, sec)
    for opt, t in rows_tot:
        print(f"| {opt} | {t['mine_ok']} из {t['mine_n']} | {100 * t['mine_e'] / max(1, t['mine_w']):.1f} % | "
              f"{t['oth_ok']} из {t['oth_n']} | {100 * t['oth_e'] / max(1, t['oth_w']):.1f} % | "
              f"{t['junk'] * per10:.1f} | {t['junk_words'] * per10:.1f} |", flush=True)


def score():
    sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
    from wer2 import norm, wer
    spk = speakers()
    tot = {}
    for group, recs in (('тихо', QUIET), ('шум', NOISY)):
        for name in recs:
            d = prep(name, spk, norm)
            if d is None:
                continue
            dec = decide(d['rows'], d['thr'])
            for opt in OPTS:
                text_stats(tot.setdefault((group, opt), new_tot()), d, dec[opt], wer)
    for g in ('тихо', 'шум'):
        sec = tot[(g, OPTS[0])]['sec']
        print(f'\n{g} ({sec / 60:.0f} мин записей)')
        text_table([(opt, tot[(g, opt)]) for opt in OPTS], sec)


# ---- подписи «кто сказал» для голосов, прошедших по B (раздел «Подписи» отчёта)

K = 8                                      # Voices.K: сколько фраз весит слепок
CLEAR = 12                                 # B: речь на 12+ дБ громче фона
RULES = ('П1 новый номер', 'П2 слепок растёт', 'П3 растут все')
JOINS = (None, 0.32, 0.26)                 # порог «тот же человек» для чёткого непохожего; None — как порог слушания
STARTS = ('кнопкой говорил собеседник', 'кнопку никто не нажимал')


class Voice:
    def __init__(self, n, e, src, k=1):
        self.n, self.e, self.src, self.k = n, e / np.linalg.norm(e), src, k

    def fold(self, e):                     # Voices.fold: новый кусок с весом 1/(k+1)
        o = self.e * self.k + e
        self.e = o / np.linalg.norm(o); self.k = min(K, self.k + 1)


def label_run(rows, pw, rule, join):
    """Лента кусков через B с номерами голосов. pw — отпечатки фраз кнопкой (пусто — кнопку никто не нажимал).
    Номер на кусок: None — не принят, 0 — принят без номера (короче 0,6 с), n — голос n."""
    vs = [Voice(1, np.mean(pw, 0), 'falar', len(pw))] if pw else []
    out = []
    for r in rows:
        if not r['words']:                     # пусто — переводить нечего, голос не решается
            out.append(None); continue
        e = r['e']
        hit = None
        if e is not None and vs:
            mg, v = max(((float(e @ v.e) - hear(v.k), v) for v in vs), key=lambda x: x[0])
            hit = v if mg >= 0 else None
        if hit is None and r['snr'] < CLEAR:
            out.append(None); continue          # чужой и нечёткий — как сейчас
        if e is None:
            out.append(0); continue             # чёткий, но короткий: перевод без номера
        v = hit
        if v is None and join is not None and vs:
            c, near = max(((float(e @ x.e), x) for x in vs), key=lambda x: x[0])
            v = near if c >= join else None
        if v is None:
            v = Voice(max((x.n for x in vs), default=0) + 1, e, 'listen'); vs.append(v)
        elif rule == RULES[2] or rule == RULES[1] and v.src == 'listen':
            v.fold(e)
        out.append(v.n)
    return out, vs


def label_counts(c, rows, labs, vs):
    """Доли «верно / лишний номер / номер другого / без номера» по кускам фраз; номера и фон — в счётчик c."""
    first, by_spk, by_lab = {}, collections.defaultdict(collections.Counter), collections.defaultdict(collections.Counter)
    for i, (r, l) in enumerate(zip(rows, labs)):
        if not l:
            continue
        s = r['who'] or 'фон'
        by_spk[s][l] += 1; by_lab[l][s] += 1; first.setdefault((l, s), i)
    main = {s: min(cn, key=lambda l: (-cn[l], first[(l, s)])) for s, cn in by_spk.items() if s != 'фон'}
    major = {l: min(cn, key=lambda s: (-cn[s], first[(l, s)])) for l, cn in by_lab.items()}
    for r, l in zip(rows, labs):
        if l is None or r['who'] is None:
            continue
        key = 'none' if l == 0 else 'other' if major[l] != r['who'] else 'ok' if main[r['who']] == l else 'extra'
        c[key] += 1
    c['voices'] += len(vs)
    c['people'] += len(main)
    c['bg_voices'] += sum(1 for l, s in major.items() if s == 'фон')
    c['recs'] += 1


def labels():
    sys.path.insert(0, os.path.join(R, 'bench', 'asr2'))
    from wer2 import norm, wer
    spk = speakers()
    data = {g: [d for d in (prep(n, spk, norm) for n in recs) if d is not None] for g, recs in (('тихо', QUIET), ('шум', NOISY))}
    variants = [(rule, join) for rule in RULES for join in JOINS]
    C = {}
    for g, ds in data.items():
        for st in STARTS:
            for rule, join in variants:
                c = C.setdefault((g, st, rule, join), collections.Counter())
                for d in ds:
                    labs, vs = label_run(d['rows'], d['pw'] if st == STARTS[0] else [], rule, join)
                    label_counts(c, d['rows'], labs, vs)

    def pct(c, k):
        n = c['ok'] + c['extra'] + c['other'] + c['none']
        return 100 * c[k] / max(1, n)
    jn = lambda j: 'как порог слушания' if j is None else f'{j:.2f}'.replace('.', ',')
    for st in STARTS:
        for g in data:
            print(f'\n{st} · {g}')
            print('| вариант | порог «тот же» | верно | лишний номер | номер другого | без номера | номеров / людей | номеров от фона |')
            print('|---|---|---|---|---|---|---|---|')
            for rule, join in variants:
                c = C[(g, st, rule, join)]
                print(f"| {rule} | {jn(join)} | {pct(c, 'ok'):.1f} % | {pct(c, 'extra'):.1f} % | {pct(c, 'other'):.1f} % | "
                      f"{pct(c, 'none'):.1f} % | {c['voices']} / {c['people']} | {c['bg_voices']} |")
    # выбор: «верно» по всему вместе; «номер другого» ≤ 5 % в каждой группе (оба начала вместе)
    best = []
    for rule, join in variants:
        tot = collections.Counter()
        for g in data:
            for st in STARTS:
                tot.update(C[(g, st, rule, join)])
        grp = {}
        for g in data:
            cg = collections.Counter()
            for st in STARTS:
                cg.update(C[(g, st, rule, join)])
            grp[g] = pct(cg, 'other')
        best.append((pct(tot, 'ok'), max(grp.values()), rule, join, grp))
    print('\nвыбор: «верно» вместе; «номер другого» по группам')
    for ok, worst, rule, join, grp in sorted(best, key=lambda x: -x[0]):
        print(f"  {rule} · {jn(join)}: верно {ok:.1f} % · номер другого " + ', '.join(f'{g} {v:.1f} %' for g, v in grp.items()))
    fit = [b for b in best if b[1] <= 5.0]
    pick = max(fit, key=lambda b: b[0]) if fit else min(best, key=lambda b: b[1])
    print(f"выбран: {pick[2]} · {jn(pick[3])}" + ('' if fit else ' (ни один не уложился в 5 % — взят с наименьшим «номером другого»)'))
    # осмысленные и лишние тексты выбранного варианта — заново: номера влияют на приём куска
    for g, ds in data.items():
        rows_tot = []
        tb = new_tot()
        for d in ds:
            text_stats(tb, d, decide(d['rows'], d['thr'])['B чёткий'], wer)
        rows_tot.append(('B чёткий (первая таблица)', tb))
        for st in STARTS:
            t = new_tot()
            for d in ds:
                labs, _ = label_run(d['rows'], d['pw'] if st == STARTS[0] else [], pick[2], pick[3])
                text_stats(t, d, [l is not None for l in labs], wer)
            rows_tot.append((f'B с номерами · {st}', t))
        print(f'\n{g} ({tb["sec"] / 60:.0f} мин записей), выбранный вариант')
        text_table(rows_tot, tb['sec'])
    after(data)


def tentative(labs, keep_one):
    """«Номер со 2-й фразы»: номер голоса, заведённого слушанием, виден, когда под ним вторая фраза (первой он
    ставится задним числом); голос с одной фразой — без номера. keep_one — голос 1 с кнопки виден сразу."""
    cnt = collections.Counter(l for l in labs if l)
    keep = {l for l, n in cnt.items() if n >= 2 or l == 1 and keep_one}
    return [l if l is None or l in keep else 0 for l in labs], keep


def agglo(E, t):
    """Потолок: все принятые куски записи разом, средняя связь — сливать группы, пока средний косинус ≥ t."""
    S = E @ E.T; np.fill_diagonal(S, -9)
    size, alive, groups = np.ones(len(E)), np.ones(len(E), bool), [[i] for i in range(len(E))]
    while len(E) > 1:
        Sa = np.where(alive[:, None] & alive[None, :], S, -9)
        i, j = np.unravel_index(np.argmax(Sa), Sa.shape)
        if Sa[i, j] < t:
            break
        S[i, :] = (S[i, :] * size[i] + S[j, :] * size[j]) / (size[i] + size[j]); S[:, i] = S[i, :]; S[i, i] = -9
        size[i] += size[j]; alive[j] = False; groups[i] += groups[j]; groups[j] = []
    lab = np.zeros(len(E), int)
    for g, mem in enumerate(groups):
        lab[mem] = g + 1
    return lab


def after(data):
    """После замера (не входило в выбор): номер со 2-й фразы, потолок пакетного разбора, похожесть кусков."""
    def line(c):
        n = max(1, c['ok'] + c['extra'] + c['other'] + c['none'])
        return (f"{100 * c['ok'] / n:.1f} % | {100 * c['extra'] / n:.1f} % | {100 * c['other'] / n:.1f} % | "
                f"{100 * c['none'] / n:.1f} % | {c['voices']} / {c['people']}")
    print('\nпосле замера: номер со 2-й фразы (порог «тот же» — как порог слушания)')
    print('| начало · группа | вариант | верно | лишний номер | номер другого | без номера | номеров / людей с номером |')
    print('|---|---|---|---|---|---|---|')
    for st in STARTS:
        for g, ds in data.items():
            for rule in RULES:
                c = collections.Counter()
                for d in ds:
                    labs, vs = label_run(d['rows'], d['pw'] if st == STARTS[0] else [], rule, None)
                    labs, keep = tentative(labs, st == STARTS[0])
                    label_counts(c, d['rows'], labs, [x for x in vs if x.n in keep])
                print(f'| {st} · {g} | {rule} | {line(c)} |')
    print('\nпосле замера: потолок — все принятые куски разом (кнопку никто не нажимал)')
    print('| группа | порог слияния | верно | лишний номер | номер другого | без номера | номеров / людей |')
    print('|---|---|---|---|---|---|---|')
    for g, ds in data.items():
        for t in (0.25, 0.30, 0.35):
            c = collections.Counter()
            for d in ds:
                labs0, _ = label_run(d['rows'], [], RULES[0], None)
                idx = [i for i, l in enumerate(labs0) if l]
                lab = agglo(np.array([d['rows'][i]['e'] for i in idx]), t) if idx else []
                labs = [None] * len(d['rows'])
                for i, l in zip(idx, lab):
                    labs[i] = int(l)
                label_counts(c, d['rows'], labs, sorted(set(int(x) for x in lab)))
            print(f"| {g} | {t:.2f} | {line(c)} |".replace('.', ','))
    print('\nпосле замера: похожесть двух кусков разных фраз')
    for g, ds in data.items():
        same, diff = [], []
        for d in ds:
            rs = [r for r in d['rows'] if r['words'] and r['e'] is not None and r['who']]
            if len(rs) < 2:
                continue
            E = np.array([r['e'] for r in rs]); C = E @ E.T
            for i in range(len(rs)):
                for j in range(i + 1, len(rs)):
                    if rs[i]['win'] != rs[j]['win']:
                        (same if rs[i]['who'] == rs[j]['who'] else diff).append(C[i, j])
        same, diff = np.array(same), np.array(diff)
        rk = np.concatenate([same, diff]).argsort().argsort() + 1
        auc = (rk[:len(same)].sum() - len(same) * (len(same) + 1) / 2) / (len(same) * len(diff))
        print(f'  {g}: AUC {auc:.3f}; один человек — медиана {np.median(same):.2f}, проходит 0,38 в {100 * np.mean(same >= 0.38):.0f} %; '
              f'разные — медиана {np.median(diff):.2f}, проходят 0,38 в {100 * np.mean(diff >= 0.38):.1f} %')


if __name__ == '__main__':
    {'embed': embed, 'score': score, 'labels': labels}[sys.argv[1]]()
