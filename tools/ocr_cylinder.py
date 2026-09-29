#!/usr/bin/env python3
"""Набор изогнутых надписей: снимки bench/ocr, натянутые на цилиндр, как этикетка на бутылке.

Настоящих снимков этикеток с расшифровкой мало, а изгиб строки — главная беда чтения этикеток:
рамка строки — прямоугольник, текст в нём гуляет вверх-вниз, и в вырез залезают соседние строки.
Здесь каждый снимок набора сворачивается на вертикальный цилиндр и снимается камерой с близкого
расстояния: строки выгибаются к линии горизонта и сжимаются к краям. Расшифровка та же
(bench/ocr/gt.txt), поэтому изгиб меряется на тех же 831 слове.

  .venv/bin/python tools/ocr_cylinder.py            # -> bench/ocr/runs/cyl-up, cyl-down (не в git)

Геометрия: ширина снимка — дуга 2·θ цилиндра радиуса R, камера на расстоянии D от ближней точки,
горизонт — на высоте yh (доля высоты снимка). Строка выше или ниже горизонта на краю цилиндра
дальше от камеры и потому ближе к горизонту: «cyl-up» — горизонт над снимком, строки дугой ∩,
«cyl-down» — под снимком, дугой ∪; «cyl-near» — камера вдвое ближе, цилиндр виден на ±75°.
"""
import math, os, sys
import numpy as np
from PIL import Image

R0 = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(R0, 'bench', 'ocr', 'photos')
VARIANTS = {'cyl-up': dict(theta=55, dist=4.0, yh=-0.35), 'cyl-down': dict(theta=55, dist=4.0, yh=1.35),
            'cyl-near': dict(theta=75, dist=2.0, yh=-0.6)}


def warp(img, theta=55, dist=4.0, yh=-0.35, bg=128):
    H, W = img.shape[:2]
    tm = math.radians(theta); R = W / (2 * tm); D = dist * R; f = D
    th = np.linspace(-tm, tm, 4096)
    depth = D + R * (1 - np.cos(th))
    u = W / 2 + f * R * np.sin(th) / depth               # монотонна по θ при D ≥ R
    uu, vv = np.meshgrid(np.arange(W) + 0.5, np.arange(H) + 0.5)
    t = np.interp(uu, u, th, left=np.nan, right=np.nan)
    dep = D + R * (1 - np.cos(t))
    hy = yh * H
    y = hy + (vv - hy) * dep / f                         # обратно: точка снимка -> точка этикетки
    x = W / 2 + R * t
    ok = ~np.isnan(t) & (y >= 0) & (y <= H - 1) & (x >= 0) & (x <= W - 1)
    xs = np.where(ok, x - 0.5, 0); ys = np.where(ok, y - 0.5, 0)
    x0 = np.clip(np.floor(xs).astype(int), 0, W - 1); y0 = np.clip(np.floor(ys).astype(int), 0, H - 1)
    x1 = np.minimum(x0 + 1, W - 1); y1 = np.minimum(y0 + 1, H - 1)
    fx = (xs - x0)[..., None]; fy = (ys - y0)[..., None]; f32 = img.astype(np.float32)
    out = (f32[y0, x0] * (1 - fx) + f32[y0, x1] * fx) * (1 - fy) + (f32[y1, x0] * (1 - fx) + f32[y1, x1] * fx) * fy
    out[~ok] = bg
    return np.clip(out + 0.5, 0, 255).astype(np.uint8)


if __name__ == '__main__':
    sys.path.insert(0, os.path.join(R0, 'tools'))
    import ocr_ref
    ids = sorted(p[:-4] for p in os.listdir(SRC) if p.endswith('.jpg'))
    for name, v in VARIANTS.items():
        out = os.path.join(R0, 'bench', 'ocr', 'runs', name); os.makedirs(out, exist_ok=True)
        for i in ids:
            img = ocr_ref.load_rgb(os.path.join(SRC, i + '.jpg'))
            Image.fromarray(warp(img, **v)).save(os.path.join(out, i + '.png'))
        print(f'{out}: {len(ids)} снимков, θ={v["theta"]}°, D={v["dist"]}R, горизонт {v["yh"]:+.2f} высоты')
