#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Шаг 13: частичный шумодав перед распознавателем — «полярная проекция» маски GTCRN (Huo, Zhang & Zhang 2026,
arXiv:2607.11157, из документа владельца «Методы улучшения распознавания речи»). Маска шумодава M = A·e^{jφ}
заменяется на A^α, фаза остаётся зашумлённой (γ = 0): α = 0 — звук как есть, α = 1 — полная сила маски.

Шумодав — тот же gtcrn_simple.onnx, что у нарезки, потоковый, кадр за кадром с начала куска; STFT — как у sherpa-onnx
(kaldi-native-fbank: 512/256, окно — корень из периодического Ханна, center с отражением). Куски — те же, что у
tools/asr_confidence.py (кэш results/asr_conf/), распознавание жадное, как в приложении; счёт по фразам
(dualmic_eval.per_phrase), сравнение с сырым звуком — парный бутстрэп, два плеча — интервалы 97,5 %.

    taskset -c 0 .venv/bin/python tools/asr_polar_eval.py near-pt … [--alpha 0.25,0.5]
    taskset -c 0 .venv/bin/python tools/asr_polar_eval.py --selftest near-pt

Шаг 14 — DPDFNet с пределом ослабления (sherpa-onnx, attenuation_limit_db: выход = a·исходный + (1 − a)·очищенный,
a = 10^(−дБ/20)), те же куски и счёт:

    taskset -c 0 .venv/bin/python tools/asr_polar_eval.py near-pt … --dpdfnet models/dpdfnet/dpdfnet2.onnx --att 6,12
    taskset -c 0 .venv/bin/python tools/asr_polar_eval.py --selftest near-pt --dpdfnet models/dpdfnet/dpdfnet2.onnx

--stop-on-harm: досчёт прекращается, как только каждый вариант хоть на одной записи хуже на 1 п.п. и больше с
интервалом выше нуля (второе условие шагов 2, 13, 14 уже не выполнить).
"""
import argparse
import json
import os
import sys
import time

import numpy as np

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
import asr_confidence as ac  # noqa: E402
import dualmic_eval as de  # noqa: E402

GTCRN = os.path.join(R, 'models', 'denoiser', 'gtcrn_simple.onnx')


class Polar:
    """GTCRN кадр за кадром; маска — отношение выхода к входу в каждой полосе (в модели так и есть: комплексная маска
    с tanh, |M| ≤ √2)."""

    def __init__(self, path=GTCRN):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = so.inter_op_num_threads = 1
        self.s = ort.InferenceSession(path, so, providers=['CPUExecutionProvider'])
        meta = self.s.get_modelmeta().custom_metadata_map
        assert meta['window_type'] == 'hann_sqrt' and int(meta['sample_rate']) == ac.SR, meta
        self.n, self.hop = int(meta['n_fft']), int(meta['hop_length'])
        self.cache = {k: [int(v) for v in meta[k + '_shape'].split(',')] for k in ('conv_cache', 'tra_cache', 'inter_cache')}
        self.win = np.sqrt(0.5 - 0.5 * np.cos(2 * np.pi * np.arange(self.n) / self.n))   # knf «hann» — периодическое

    def stft(self, x):
        p = self.n // 2
        y = np.pad(np.asarray(x, np.float64), p, mode='reflect')
        k = 1 + (len(y) - self.n) // self.hop
        fr = np.lib.stride_tricks.as_strided(y, (k, self.n), (y.strides[0] * self.hop, y.strides[0]))
        return np.fft.rfft(fr * self.win, axis=1)

    def istft(self, X, length):
        k = len(X)
        fr = np.fft.irfft(X, n=self.n, axis=1) * self.win
        y, w = np.zeros(self.n + self.hop * (k - 1)), np.zeros(self.n + self.hop * (k - 1))
        for i in range(k):
            y[i * self.hop:i * self.hop + self.n] += fr[i]
            w[i * self.hop:i * self.hop + self.n] += self.win ** 2
        y = np.divide(y, w, out=np.zeros_like(y), where=w > 1e-10)
        p = self.n // 2
        return y[p:p + length].astype(np.float32)

    def enhance(self, X):
        st = {k: np.zeros(v, np.float32) for k, v in self.cache.items()}
        E = np.zeros_like(X)
        for i in range(len(X)):
            mix = np.stack([X[i].real, X[i].imag], -1).astype(np.float32)[None, :, None, :]
            out = self.s.run(None, {'mix': mix, **st})
            E[i] = out[0][0, :, 0, 0] + 1j * out[0][0, :, 0, 1]
            st = {'conv_cache': out[1], 'tra_cache': out[2], 'inter_cache': out[3]}
        return E

    def run(self, x, alphas):
        """{α: звук с маской A^α и зашумлённой фазой}; ключ 'full' — выход модели как есть (A·e^{jφ})."""
        X = self.stft(x)
        E = self.enhance(X)
        mag = np.abs(X)
        A = np.divide(np.abs(E), mag, out=np.ones_like(mag), where=mag > 1e-9)
        out = {a: self.istft(X * A ** a, len(x)) for a in alphas}
        out['full'] = self.istft(E, len(x))
        return out


class Dpdf:
    """DPDFNet sherpa-onnx (весь кусок сразу) с пределом ослабления; варианты — 'dpdf<дБ>'."""

    def __init__(self, path, atts):
        so = ac.vde.so
        self.dn = {f'dpdf{a:g}': so.OfflineSpeechDenoiser(so.OfflineSpeechDenoiserConfig(
            model=so.OfflineSpeechDenoiserModelConfig(
                dpdfnet=so.OfflineSpeechDenoiserDpdfNetModelConfig(model=path, attenuation_limit_db=a), num_threads=1)))
            for a in atts}

    def run(self, x, arms):
        out = {}
        for k in arms:
            r = self.dn[k].run(np.ascontiguousarray(x, np.float32), ac.SR)
            assert r.sample_rate == ac.SR, r.sample_rate
            y = np.zeros(len(x), np.float32)
            v = np.array(r.samples, np.float32)[:len(x)]
            y[:len(v)] = v
            out[k] = y
        return out


def selftest_dpdf(name, path):
    """До замера: при пределе 0,1 дБ (почти весь исходный звук) выход совпадает с входом — значит, исходный и очищенный
    звук сложены без сдвига; длина выхода — как у входа."""
    x, piece, plays, gap, lang = ac.load(name)
    seg = json.load(open(os.path.join(R, 'results', 'asr_conf', name + '.json'), encoding='utf-8'))['segs'][0]
    p = piece(seg[0], seg[1])
    so = ac.vde.so
    dn = so.OfflineSpeechDenoiser(so.OfflineSpeechDenoiserConfig(model=so.OfflineSpeechDenoiserModelConfig(
        dpdfnet=so.OfflineSpeechDenoiserDpdfNetModelConfig(model=path, attenuation_limit_db=0.1), num_threads=1)))
    y = np.array(dn.run(np.ascontiguousarray(p, np.float32), ac.SR).samples, np.float32)
    m = min(len(y), len(p))
    c = float(np.corrcoef(y[:m], p[:m])[0, 1])
    print(f'самопроверка DPDFNet на {name}: длина {len(y)} при входе {len(p)}; предел 0,1 дБ — корреляция с входом {c:.4f}')
    ok = abs(len(y) - len(p)) <= 256 and c > 0.99
    print('самопроверка ' + ('прошла' if ok else 'НЕ прошла'))
    return ok


def selftest(name):
    """До замера: α = 0 — исходный звук; полная маска — как шумодав sherpa-onnx (OfflineSpeechDenoiser)."""
    x, piece, plays, gap, lang = ac.load(name)
    seg = json.load(open(os.path.join(R, 'results', 'asr_conf', name + '.json'), encoding='utf-8'))['segs'][0]
    p = piece(seg[0], seg[1])
    out = Polar().run(p, [0.0])
    err = float(np.max(np.abs(out[0.0] - p)))
    so = ac.vde.so
    dn = so.OfflineSpeechDenoiser(so.OfflineSpeechDenoiserConfig(model=so.OfflineSpeechDenoiserModelConfig(
        gtcrn=so.OfflineSpeechDenoiserGtcrnModelConfig(model=GTCRN), num_threads=1)))
    ref = np.array(dn.run(p, ac.SR).samples, np.float32)
    m = min(len(ref), len(out['full']))
    c = float(np.corrcoef(ref[:m], out['full'][:m])[0, 1])
    rel = float(np.sqrt(np.mean((ref[:m] - out['full'][:m]) ** 2) / np.mean(ref[:m] ** 2)))
    print(f'самопроверка на {name}, кусок {(seg[1] - seg[0]) / ac.SR:.1f} с: α = 0 — расхождение с исходным {err:.1e}; '
          f'полная маска против sherpa-onnx — корреляция {c:.5f}, остаток {rel:.1e} (длины {len(ref)} / {len(out["full"])})')
    ok = err < 1e-4 and c > 0.999
    print('самопроверка ' + ('прошла' if ok else 'НЕ прошла'))
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('recs', nargs='+')
    ap.add_argument('--alpha', default='0.25,0.5')
    ap.add_argument('--out', default=os.path.join(R, 'results', 'asr_conf'))
    ap.add_argument('--selftest', action='store_true')
    ap.add_argument('--dpdfnet', help='шаг 14: модель DPDFNet вместо маски GTCRN')
    ap.add_argument('--att', default='6,12', help='шаг 14: пределы ослабления, дБ')
    ap.add_argument('--stop-on-harm', action='store_true')
    a = ap.parse_args()
    if a.selftest:
        sys.exit(0 if (selftest_dpdf(a.recs[0], a.dpdfnet) if a.dpdfnet else selftest(a.recs[0])) else 1)
    if a.dpdfnet:
        arms, tag = [f'dpdf{float(v):g}' for v in a.att.split(',')], 'dpdf'
        dn = Dpdf(a.dpdfnet, [float(v) for v in a.att.split(',')])
        transform = dn.run
    else:
        alphas = [float(v) for v in a.alpha.split(',')]
        arms, tag = [str(al) for al in alphas], 'polar'
        pol = Polar()
        transform = lambda x, ks: {str(k): v for k, v in pol.run(x, alphas).items() if k != 'full'}   # noqa: E731
    rec = ac.recognizer()
    level = 1 - 0.05 / len(arms)                         # поправка Бонферрони на число плеч
    allg, allv, tdn, harmed = [], {k: [] for k in arms}, 0.0, set()
    for name in a.recs:
        cache = json.load(open(os.path.join(a.out, name + '.json'), encoding='utf-8'))
        x, piece, plays, gap, lang = ac.load(name)
        segs = [tuple(s) for s in cache['segs']]
        path = os.path.join(a.out, f'{name}.{tag}.json')
        done = json.load(open(path, encoding='utf-8')) if os.path.exists(path) else {}
        for k in arms:
            done.setdefault(k, [])
        done.setdefault('sec_dn', 0.0)
        for k, s in enumerate(segs):
            if all(len(done[arm]) > k for arm in arms):
                continue
            t0 = time.time()
            outs = transform(piece(s[0], s[1]), arms)
            done['sec_dn'] += time.time() - t0
            for arm in arms:
                if len(done[arm]) == k:
                    done[arm].append(ac.recognize(rec, outs[arm])[0])
            if k % 20 == 19:   # можно прервать и продолжить
                json.dump(done, open(path + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(path + '.tmp', path)
        json.dump(done, open(path + '.tmp', 'w', encoding='utf-8'), ensure_ascii=False); os.replace(path + '.tmp', path)
        greedy = [r[0] for r in cache['asr']]
        g = de.per_phrase(segs, greedy, plays, gap, lang)
        allg += g; tdn += done['sec_dn']
        e0, n = sum(r[1] for r in g), sum(r[2] for r in g)
        line = f'{name:20s} сырой {100 * e0 / n:5.1f} %'
        for arm in arms:
            v = de.per_phrase(segs, done[arm], plays, gap, lang)
            allv[arm] += v
            d, lo, hi = de.boot(g, v, level=level)
            if d >= 1 and lo > 0:
                harmed.add(arm)
            line += f' · {arm}: {100 * sum(r[1] for r in v) / n:5.1f} % ({d:+.1f} [{lo:+.1f}; {hi:+.1f}])'
        print(line, flush=True)
        if a.stop_on_harm and harmed == set(arms):
            print(f'остановлено после {name}: каждый вариант уже хуже на 1 п.п. и больше хоть на одной записи', flush=True)
            break
    n = sum(r[2] for r in allg)
    print(f'все вместе: сырой {100 * sum(r[1] for r in allg) / n:.2f} %', flush=True)
    for arm in arms:
        d, lo, hi = de.boot(allg, allv[arm], level=level)
        print(f'   {arm}: {100 * sum(r[1] for r in allv[arm]) / n:.2f} % · {d:+.2f} п.п. [{lo:+.2f}; {hi:+.2f}] '
              f'(интервал {100 * level:.1f} %)', flush=True)
    print(f'время шумодава на ПК: {tdn:.0f} с на все куски', flush=True)


if __name__ == '__main__':
    main()
