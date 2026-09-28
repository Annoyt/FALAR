#!/usr/bin/env python3
"""Макет «перевод поверх снимка»: распознавание эталонным конвейером (ocr_ref), перевод локальной
OPUS-MT (CTranslate2, жадно — как на телефоне), отрисовка русского текста на месте португальского.

  .venv/bin/python tools/ocr_overlay.py bench/ocr/photos/p03.jpg [...] --out bench/ocr/runs/overlay

Это макет для обсуждения вида, не код приложения: на телефоне то же самое рисует Canvas.
Как рисуется: каждый абзац (строки, продолжающие друг друга, см. ocr_ref.paragraphs) —
прямоугольник по направлению строк; он закрашивается цветом фона, взятым с каймы вокруг
текста, а перевод пишется цветом исходного текста, под тем же углом, самым крупным кеглем,
при котором он помещается в прямоугольник.
"""
import os, re, sys, math, argparse
import numpy as np
from PIL import Image, ImageDraw, ImageFont

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(R, 'tools')); sys.path.insert(0, R)
import ocr_ref as O

FONT = '/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf'


def unshout(s):
    """Как TranslatorService.unshout: слово из прописных длиннее трёх букв — к обычному виду."""
    def fix(m):
        w = m.group(0)
        return w.capitalize() if len(re.sub(r'[^\w]', '', w)) > 3 and w.isupper() else w
    out = re.sub(r'\S+', fix, s)
    return out[:1].upper() + out[1:]


class MT:
    def __init__(self):
        import ctranslate2, sentencepiece as spm
        hf = os.path.join(R, 'models', 'opus-hf', 'pt2ru')
        self.src = spm.SentencePieceProcessor(model_file=os.path.join(hf, 'source.spm'))
        self.tgt = spm.SentencePieceProcessor(model_file=os.path.join(hf, 'target.spm'))
        self.t = ctranslate2.Translator(os.path.join(R, 'models', 'opus-ct2', 'pt2ru'), device='cpu', compute_type='int8')

    def __call__(self, texts):
        batch = [['>>rus<<'] + self.src.encode(t, out_type=str) + ['</s>'] for t in texts]
        res = self.t.translate_batch(batch, beam_size=1, max_decoding_length=200)
        return [self.tgt.decode(r.hypotheses[0]).strip() for r in res]


def frame(rows):
    """Прямоугольник абзаца по направлению его первой строки: (центр, u, n, ширина, высота)."""
    u = rows[0]['u'] / np.linalg.norm(rows[0]['u']); n = np.array([-u[1], u[0]])
    pts = np.concatenate([np.concatenate(r['boxes']) for r in rows])
    pu, pn = pts @ u, pts @ n
    c = u * (pu.min() + pu.max()) / 2 + n * (pn.min() + pn.max()) / 2
    return c, u, n, pu.max() - pu.min(), pn.max() - pn.min()


def corners(c, u, n, w, h, pad=0):
    w, h = w / 2 + pad, h / 2 + pad
    return [tuple(c - u * w - n * h), tuple(c + u * w - n * h), tuple(c + u * w + n * h), tuple(c - u * w + n * h)]


def colors(img, c, u, n, w, h):
    """Фон — медианы каймы (шириной 15 % высоты) по четырём четвертям: заливка идёт градиентом
    между ними, иначе на вывеске с неровным светом заплатка видна прямоугольником. Текст —
    медиана пикселей внутри, сильнее всего отличающихся от фона (самая «чернильная» четверть)."""
    H, W = img.shape[:2]
    ring = max(2.0, 0.15 * h)
    ys, xs = np.mgrid[0:H:2, 0:W:2]
    P = np.stack([xs.ravel(), ys.ravel()], 1).astype(np.float64) - c
    a, b = P @ u, P @ n
    inside = (np.abs(a) <= w / 2) & (np.abs(b) <= h / 2)
    band = (np.abs(a) <= w / 2 + ring) & (np.abs(b) <= h / 2 + ring) & ~inside
    px = img[ys.ravel(), xs.ravel()].astype(np.float64)
    bg = np.median(px[band], 0) if band.any() else np.array([255.0, 255, 255])
    quad = []
    for sa in (-1, 1):
        for sb in (-1, 1):
            m = band & (np.sign(a) == sa) & (np.sign(b) == sb)
            quad.append(np.median(px[m], 0) if m.sum() > 3 else bg)
    ink = px[inside]
    if len(ink) > 10:
        d = np.linalg.norm(ink - bg, axis=1)
        fg = np.median(ink[d >= np.quantile(d, 0.75)], 0)
    else:
        fg = np.zeros(3)
    lum = lambda v: 0.299 * v[0] + 0.587 * v[1] + 0.114 * v[2]
    if abs(lum(fg) - lum(bg)) < 90:                       # слабый контраст — чёрный или белый
        fg = np.zeros(3) if lum(bg) > 128 else np.full(3, 255.0)
    return [tuple(int(v) for v in q) for q in quad], tuple(int(v) for v in fg)


def patch(w, h, quad):
    """Заплатка w×h: билинейный градиент между цветами четвертей (лево-верх, лево-низ, право-верх, право-низ)."""
    q = np.array(quad, np.float64).reshape(2, 2, 3)          # [лево/право][верх/низ]
    tx = np.linspace(0, 1, max(w, 1))[None, :, None]; ty = np.linspace(0, 1, max(h, 1))[:, None, None]
    top = q[0, 0] * (1 - tx) + q[1, 0] * tx; bot = q[0, 1] * (1 - tx) + q[1, 1] * tx
    return Image.fromarray(np.clip(top * (1 - ty) + bot * ty, 0, 255).astype(np.uint8)).convert('RGBA')


def fit(text, w, h, draw):
    """Самый крупный кегль, при котором перенесённый по словам текст влезает в w×h."""
    words = text.split()
    for size in range(int(h * 0.9), 7, -1):
        f = ImageFont.truetype(FONT, size)
        lines, cur = [], ''
        for wd in words:
            t = (cur + ' ' + wd).strip()
            if draw.textlength(t, font=f) <= w or not cur:
                cur = t
            else:
                lines.append(cur); cur = wd
        lines.append(cur)
        lh = size * 1.12
        if lh * len(lines) <= h * 1.05 and all(draw.textlength(l, font=f) <= w * 1.02 for l in lines):
            return f, lines, lh
    f = ImageFont.truetype(FONT, 8)
    return f, [text], 9


def render(img, paras):
    """Сначала все заплатки, потом весь текст: иначе заплатка следующего абзаца срезала
    перевод предыдущего там, где рамки перекрываются."""
    out = Image.fromarray(img).convert('RGBA')
    probe = ImageDraw.Draw(Image.new('RGBA', (8, 8)))
    texts = []
    for (src, rows), ru in paras:
        c, u, n, w, h = frame(rows)
        quad, fg = colors(img, c, u, n, w, h)
        ang = math.degrees(math.atan2(u[1], u[0]))
        pw, ph = int(math.ceil(w)) + 2, int(math.ceil(h)) + 2
        pt = patch(pw, ph, quad).rotate(-ang, resample=Image.BICUBIC, expand=True)
        out.alpha_composite(pt, (int(round(c[0] - pt.width / 2)), int(round(c[1] - pt.height / 2))))
        texts.append((c, w, h, ang, fg, ru))
    for c, w, h, ang, fg, ru in texts:
        # текст пишется на горизонтальной плашке и поворачивается на угол строки
        f, lines, lh = fit(ru, w * 0.96, h * 0.96, probe)
        tw, th = int(math.ceil(w)), int(math.ceil(h))
        tile = Image.new('RGBA', (max(tw, 1), max(th, 1)), (0, 0, 0, 0)); td = ImageDraw.Draw(tile)
        y = (th - lh * len(lines)) / 2
        for l in lines:
            td.text(((tw - td.textlength(l, font=f)) / 2, y), l, font=f, fill=fg + (255,)); y += lh
        rt = tile.rotate(-ang, resample=Image.BICUBIC, expand=True)
        out.alpha_composite(rt, (int(round(c[0] - rt.width / 2)), int(round(c[1] - rt.height / 2))))
    return out.convert('RGB')


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('images', nargs='+'); ap.add_argument('--out', default=os.path.join(R, 'bench', 'ocr', 'runs', 'overlay'))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    M = os.path.join(R, 'models', 'ocr-cand')
    ocr = O.Ocr(f'{M}/v6s', f'{M}/v5m', 960); mt = MT()
    for p in a.images:
        img = O.load_rgb(p)
        ocr.read(img)
        paras = [pr for bl in O.layout(*ocr.last) for pr in O.paragraphs(bl)]
        ru = mt([unshout(t) for t, _ in paras])
        for (t, _), r in zip(paras, ru):
            print(f'  {t}  →  {r}')
        res = render(img, list(zip(paras, ru)))
        both = Image.new('RGB', (img.shape[1] * 2 + 16, img.shape[0]), 'white')
        both.paste(Image.fromarray(img), (0, 0)); both.paste(res, (img.shape[1] + 16, 0))
        name = os.path.splitext(os.path.basename(p))[0]
        both.save(os.path.join(a.out, name + '.jpg'), quality=88)
        print(os.path.join(a.out, name + '.jpg'))
