#!/usr/bin/env python3
"""Эталонные данные для OcrCoreTest: что выдаёт tools/ocr_ref.py на снимках набора bench/ocr.

  .venv/bin/python tools/ocr_golden.py [p03 p01 p10]    # -> bench/ocr/runs/golden/<id>/

На каждый снимок: rgb.u8 (декодированные пиксели), prob.f32 (выход детектора), rec0.f32 (вход
распознавателя для первой пачки), meta.json (размеры, рамки, тексты с уверенностью, строки и
абзацы эталона). Java-тест сверяет разбор карты, вырез и вход распознавателя, сборку строк и
абзацев — всё, кроме самих моделей. Каталог не в git: данные пересобираются этой командой
(для cyl-down/p10 сначала — tools/ocr_cylinder.py).
"""
import os, sys, json
import numpy as np
R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools'))
import ocr_ref as O

M = os.path.join(R, 'models', 'ocr-cand')
# p08: тесные строки плаката; cyl-down/p10 — снимок, натянутый на цилиндр (tools/ocr_cylinder.py):
# изогнутые строки, половину которых раньше отбрасывала оценка по прямоугольнику
ids = sys.argv[1:] or ['p03', 'p01', 'p10', 'p24', 'p08', 'cyl-down/p10']
det = O.Det(f'{M}/v6s/det.onnx', 'v6', 960)
rec = O.Rec(f'{M}/v5m/rec.onnx', f'{M}/v5m/rec.yml')
for i in ids:
    out = os.path.join(R, 'bench', 'ocr', 'runs', 'golden', i.replace('/', '-')); os.makedirs(out, exist_ok=True)
    src = os.path.join(R, 'bench', 'ocr', 'runs', i + '.png') if '/' in i else os.path.join(R, 'bench', 'ocr', 'photos', i + '.jpg')
    img = O.load_rgb(src)
    h, w = img.shape[:2]
    img.astype(np.uint8).tofile(os.path.join(out, 'rgb.u8'))
    # выход детектора — тем же путём, что в Det.run, но с сохранением карты
    r = min(1.0, det.det_max / max(h, w))
    rh = max(32, int(round(h * r / 32)) * 32); rw = max(32, int(round(w * r / 32)) * 32)
    x = O.resize(img, rw, rh)[:, :, ::-1] / 255.0
    x = ((x - O.MEAN) / O.STD).transpose(2, 0, 1)[None]
    x.astype(np.float32).tofile(os.path.join(out, 'det_in.f32'))
    prob = det.s.run(None, {'x': np.ascontiguousarray(x, np.float32)})[0][0, 0]
    prob.astype(np.float32).tofile(os.path.join(out, 'prob.f32'))
    boxes, _ = det.run(img)
    crops = [O.crop_line(img, b, st) for b, st in zip(boxes, det.strips)]
    strips = [None if st is None else dict(u=st['u'].tolist(), n=st['n'].tolist(), c0=st['c0'].tolist(), q=list(st['q']),
                                           S=st['S'], T=st['T'], d=st['d'], s0=st['s0'], s1=st['s1'], sag=st['sag'],
                                           crowded=bool(st['crowded'])) for st in det.strips]
    k0 = next((k for k, st in enumerate(det.strips) if st is not None), None)
    texts = rec.run(crops)
    # вход распознавателя для первой пачки — так же, как в Rec.run
    order = np.argsort([c.shape[1] / c.shape[0] for c in crops], kind='stable')
    idx = order[:6]
    ratio = max(max(crops[k].shape[1] / crops[k].shape[0] for k in idx), 320 / 48)
    W = min(rec.max_w, int(np.ceil(48 * ratio)))
    xr = np.zeros((len(idx), 3, 48, W), np.float32)
    for j, k in enumerate(idx):
        c = crops[k]; cw = min(W, int(np.ceil(48 * c.shape[1] / c.shape[0])))
        rr = O.resize(c, max(cw, 1), 48)[:, :, ::-1] / 255.0
        xr[j, :, :, :cw] = ((rr - 0.5) / 0.5).transpose(2, 0, 1)
    xr.tofile(os.path.join(out, 'rec0.f32'))
    blocks = O.layout(boxes, texts)
    meta = dict(w=w, h=h, pw=rw, ph=rh, boxes=[b.reshape(-1).tolist() for b in boxes],
                crops=[[int(c.shape[1]), int(c.shape[0])] for c in crops], rec0=dict(n=len(idx), W=W, order=[int(k) for k in idx]),
                texts=[[t, s] for t, s in texts], lines=O.lines_of(boxes, texts), text=O.text_of(boxes, texts),
                crop0=np.asarray(crops[0]).reshape(-1).tolist() if crops else [], strips=strips,
                strip0=dict(k=k0, w=int(crops[k0].shape[1]), h=int(crops[k0].shape[0]),
                            px=np.asarray(crops[k0]).reshape(-1).tolist()) if k0 is not None else None,
                conf=[O.para_conf(rows) for bl in blocks for _, rows in O.paragraphs(bl)])
    json.dump(meta, open(os.path.join(out, 'meta.json'), 'w'), ensure_ascii=False)
    print(i, w, 'x', h, 'det', rw, 'x', rh, 'рамок', len(boxes))
