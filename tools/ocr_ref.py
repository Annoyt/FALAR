#!/usr/bin/env python3
"""Эталонный конвейер офлайн-OCR (PP-OCR: детектор строк DB + распознаватель CTC) на numpy.

Зачем свой, а не paddleocr/rapidocr: приложение повторит его на Java без OpenCV, и сверять
Java-вариант надо с тем, что написано здесь, построчно, а не с чужим пакетом со своими
умолчаниями. Поэтому здесь нет cv2 и pyclipper: связные области — scipy.ndimage, прямоугольник
наименьшей площади — выпуклая оболочка и перебор её рёбер, расширение рамки — формулой для
прямоугольника (для него offset-полигон pyclipper даёт ровно стороны + 2d).

  .venv/bin/python tools/ocr_ref.py models/ocr-cand/v5m снимок.jpg [--det-max 960]

Каталог модели: det.onnx, rec.onnx и rec.yml (словарь из inference.yml PaddlePaddle).
"""
import math, re, sys, time, argparse
import numpy as np
from PIL import Image, ImageOps

# Параметры разбора карты детектора — из inference.yml каждой модели.
DET_PARAMS = {
    'v5': dict(thresh=0.3, box_thresh=0.6, unclip=1.5),
    'v6': dict(thresh=0.2, box_thresh=0.45, unclip=1.4),
}
MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)


def session(path):
    """Сессия ONNX Runtime на OCR_THREADS потоках (по умолчанию 4): прогон набора не должен
    занимать весь компьютер."""
    import os, onnxruntime as ort
    o = ort.SessionOptions(); o.intra_op_num_threads = int(os.environ.get('OCR_THREADS', '4'))
    return ort.InferenceSession(path, o, providers=['CPUExecutionProvider'])


def load_rgb(path):
    """Снимок с учётом EXIF-поворота: камера пишет портрет как пейзаж с пометкой."""
    im = ImageOps.exif_transpose(Image.open(path)).convert('RGB')
    return np.asarray(im)


def read_dict(yml):
    import yaml
    d = yaml.safe_load(open(yml, encoding='utf-8'))
    chars = d['PostProcess']['character_dict']
    return ['<blank>'] + [str(c) for c in chars] + [' ']


def taps(n_src, n_dst):
    """Веса одномерного пересчёта n_src -> n_dst треугольным фильтром, как Image.BILINEAR в PIL:
    при уменьшении ширина фильтра растёт вместе с масштабом (сглаживание по площади), при
    увеличении это обычная билинейная интерполяция. Возвращает (начало, веса) на каждую точку."""
    scale = n_src / n_dst
    fs = max(scale, 1.0)
    support = 1.0 * fs
    out = []
    for i in range(n_dst):
        c = (i + 0.5) * scale
        x0 = max(0, int(c - support + 0.5)); x1 = min(n_src, int(c + support + 0.5))
        xs = np.arange(x0, x1)
        w = np.maximum(0.0, 1.0 - np.abs((xs - c + 0.5) / fs))
        t = w.sum()
        out.append((x0, (w / t if t > 0 else w).astype(np.float32)))
    return out


def resize(img, w, h):
    """Пересчёт размера треугольным фильтром (как PIL BILINEAR): по строкам, затем по столбцам,
    в float32 без округления между проходами — так же сделано в Java (OcrCore.resize).
    Сглаживание при уменьшении важно: простая билинейная выборка (cv2.INTER_LINEAR) на наборе
    вывесок дала 92,4 % слов против 93,4 %. Возвращает float32 (h, w, 3)."""
    H, W = img.shape[:2]
    f = img.astype(np.float32)
    tx = taps(W, w); ty = taps(H, h)
    tmp = np.empty((H, w, f.shape[2]), np.float32)
    for j, (x0, k) in enumerate(tx):
        tmp[:, j] = np.tensordot(f[:, x0:x0 + len(k)], k, axes=([1], [0]))
    out = np.empty((h, w, f.shape[2]), np.float32)
    for i, (y0, k) in enumerate(ty):
        out[i] = np.tensordot(tmp[y0:y0 + len(k)], k, axes=([0], [0]))
    return out


def min_area_rect(pts):
    """pts: (N,2) x,y. Прямоугольник наименьшей площади: (4 точки по часовой от левой верхней)."""
    from scipy.spatial import ConvexHull
    pts = np.asarray(pts, np.float64)
    if len(pts) < 3 or np.linalg.matrix_rank(pts - pts[0]) < 2:
        # на одной прямой — отрезок нулевой ширины (как cv2.minAreaRect), как в OcrCore.minAreaRect
        o = sorted(map(tuple, pts)); a, b = np.array(o[0]), np.array(o[-1])
        return order_box(np.array([a, b, b, a]))
    hull = pts[ConvexHull(pts).vertices]
    best = None
    for i in range(len(hull)):
        e = hull[(i + 1) % len(hull)] - hull[i]
        a = math.atan2(e[1], e[0])
        c, s = math.cos(a), math.sin(a)
        R = np.array([[c, s], [-s, c]])
        r = hull @ R.T
        mn, mx = r.min(0), r.max(0)
        area = (mx[0] - mn[0]) * (mx[1] - mn[1])
        if best is None or area < best[0]:
            best = (area, mn, mx, R)
    _, mn, mx, R = best
    box = np.array([[mn[0], mn[1]], [mx[0], mn[1]], [mx[0], mx[1]], [mn[0], mx[1]]]) @ R
    return order_box(box)


def order_box(box):
    """Как get_mini_boxes в PaddleOCR: две левые по x, из них верхняя — первая; далее по часовой."""
    b = sorted(box.tolist(), key=lambda p: p[0])
    l = sorted(b[:2], key=lambda p: p[1]); r = sorted(b[2:], key=lambda p: p[1])
    return np.array([l[0], r[0], r[1], l[1]], np.float64)


def sides(box):
    w = np.linalg.norm(box[0] - box[1]); h = np.linalg.norm(box[1] - box[2])
    return w, h


def poly_mean(prob, box):
    """Средняя вероятность внутри четырёхугольника (box_score_fast): растр по центрам пикселей."""
    H, W = prob.shape
    x0 = max(0, int(math.floor(box[:, 0].min()))); x1 = min(W - 1, int(math.ceil(box[:, 0].max())))
    y0 = max(0, int(math.floor(box[:, 1].min()))); y1 = min(H - 1, int(math.ceil(box[:, 1].max())))
    if x1 < x0 or y1 < y0:
        return 0.0
    ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1]
    inside = np.ones(xs.shape, bool)
    for i in range(4):
        p, q = box[i], box[(i + 1) % 4]
        cross = (q[0] - p[0]) * (ys - p[1]) - (q[1] - p[1]) * (xs - p[0])
        inside &= cross >= -1e-6
    if not inside.any():
        return 0.0
    return float(prob[y0:y1 + 1, x0:x1 + 1][inside].mean())


def expand(box, d):
    """Расширение прямоугольника на d со всех сторон (unclip для прямоугольника)."""
    c = box.mean(0)
    u = (box[1] - box[0]); u /= max(np.linalg.norm(u), 1e-9)
    v = (box[3] - box[0]); v /= max(np.linalg.norm(v), 1e-9)
    w, h = sides(box)
    hw, hh = w / 2 + d, h / 2 + d
    return order_box(np.array([c - u * hw - v * hh, c + u * hw - v * hh, c + u * hw + v * hh, c - u * hw + v * hh]))


class Det:
    def __init__(self, path, kind, det_max=960):
        self.s = session(path)
        self.p = DET_PARAMS[kind]
        self.det_max = det_max

    def run(self, img):
        from scipy import ndimage
        h, w = img.shape[:2]
        r = min(1.0, self.det_max / max(h, w)) if self.det_max else 1.0
        rh = max(32, int(round(h * r / 32)) * 32); rw = max(32, int(round(w * r / 32)) * 32)
        x = resize(img, rw, rh)[:, :, ::-1] / 255.0    # BGR, как cv2 при обучении
        x = ((x - MEAN) / STD).transpose(2, 0, 1)[None]
        prob = self.s.run(None, {'x': np.ascontiguousarray(x, np.float32)})[0][0, 0]
        lab, n = ndimage.label(prob > self.p['thresh'], structure=np.ones((3, 3), int))
        boxes, self.strips = [], []
        for sl, k in zip(ndimage.find_objects(lab), range(1, n + 1)):
            ys, xs = np.nonzero(lab[sl] == k)
            if len(xs) < 4:
                continue
            pts = np.stack([xs + sl[1].start, ys + sl[0].start], 1)
            box = min_area_rect(pts)
            if min(sides(box)) < 3:
                continue
            # Сильно изогнутая строка занимает свой прямоугольник меньше чем наполовину, и средняя по
            # нему уверенность ниже порога — строка пропадала целиком. Тогда судим по самой области
            # (как score_mode «slow» у PaddleOCR) и оставляем, только если она и правда дуга.
            weak = poly_mean(prob, box) < self.p['box_thresh']
            if weak and float(prob[pts[:, 1], pts[:, 0]].astype(np.float64).mean()) < self.p['box_thresh']:
                continue
            bw, bh = sides(box)
            d = bw * bh * self.p['unclip'] / (2 * (bw + bh))
            ebox = expand(box, d)                 # сторона после расширения ≥ 5,1 сама (см. OcrCore.boxes)
            st = strip_of(pts, box, ebox, lab, k, self.p['unclip'], w / rw, h / rh)
            if weak and (st is None or st['sag'] < SAG_MIN * st['T']):
                continue
            self.strips.append(st)
            ebox[:, 0] = np.clip(ebox[:, 0] * w / rw, 0, w); ebox[:, 1] = np.clip(ebox[:, 1] * h / rh, 0, h)
            boxes.append(ebox)
        return boxes, (rw, rh)


def crop(img, box):
    """Вырез строки по четырёхугольнику с выпрямлением (get_rotate_crop_image): аффинно по трём точкам."""
    w = int(max(np.linalg.norm(box[0] - box[1]), np.linalg.norm(box[2] - box[3])))
    h = int(max(np.linalg.norm(box[0] - box[3]), np.linalg.norm(box[1] - box[2])))
    w, h = max(w, 1), max(h, 1)
    # точка выреза (u,v) -> точка снимка: box0 + u/w*(box1-box0) + v/h*(box3-box0)
    ex = (box[1] - box[0]) / w; ey = (box[3] - box[0]) / h
    us, vs = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    X = box[0][0] + us * ex[0] + vs * ey[0] - 0.5
    Y = box[0][1] + us * ex[1] + vs * ey[1] - 0.5
    out = bilinear(img, X, Y)
    if h / w >= 1.5:
        out = np.rot90(out)
    return out


# Изогнутые и тесные строки. Рамка строки — прямоугольник: у изогнутой строки (этикетка на
# бутылке) текст в нём гуляет вверх-вниз, а у тесных строк в вырез залезают соседние сверху и
# снизу. Таким строкам вырез — полоса вдоль средней линии области детектора, толщиной строки.
# На двух снимках этикетки это 71 → 86 % слов; на наборе вывесок с просторными строками полоса
# вместо прямоугольника у всех строк теряла 0,9 пункта, поэтому только там, где она нужна.
SAG_MIN = 0.5       # прогиб дуги от хорды — от половины толщины строки: строка изогнута
INTRUDE = 0.02      # чужих пикселей области в расширенном прямоугольнике — от 2 % своих: строки тесные
LONG = 3.0          # полоса — только строке длиннее трёх её высот
ARC_N = 2048        # точек на таблицу длины дуги


def intrusion(lab, k, box):
    """Сколько пикселей других областей внутри четырёхугольника (растр по центрам, как poly_mean)."""
    H, W = lab.shape
    x0 = max(0, int(math.floor(box[:, 0].min()))); x1 = min(W - 1, int(math.ceil(box[:, 0].max())))
    y0 = max(0, int(math.floor(box[:, 1].min()))); y1 = min(H - 1, int(math.ceil(box[:, 1].max())))
    if x1 < x0 or y1 < y0:
        return 0
    ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1]
    inside = np.ones(xs.shape, bool)
    for i in range(4):
        p, q = box[i], box[(i + 1) % 4]
        inside &= (q[0] - p[0]) * (ys - p[1]) - (q[1] - p[1]) * (xs - p[0]) >= -1e-6
    sub = lab[y0:y1 + 1, x0:x1 + 1]
    return int(((sub != 0) & (sub != k) & inside).sum())


def solve3(A, b):
    """3×3 методом Гаусса с выбором главного — тем же порядком действий, что в OcrCore.solve3."""
    M = [list(map(float, A[i])) + [float(b[i])] for i in range(3)]
    for c in range(3):
        piv = max(range(c, 3), key=lambda r: abs(M[r][c]))
        M[c], M[piv] = M[piv], M[c]
        if abs(M[c][c]) < 1e-12:
            return None
        for r in range(c + 1, 3):
            f = M[r][c] / M[c][c]
            for j in range(c, 4):
                M[r][j] -= f * M[c][j]
    x = [0.0] * 3
    for r in (2, 1, 0):
        acc = M[r][3]
        for j in range(r + 1, 3):
            acc -= M[r][j] * x[j]
        x[r] = acc / M[r][r]
    return x


def strip_of(pts, box, ebox, lab, k, unclip, sx, sy):
    """Полоса для изогнутой или тесной строки (None — вырезать прямоугольником, как раньше).
    Средняя линия — парабола по серединам области поперёк строки (шаг — пиксель карты), толщина —
    медиана её высоты; всё в координатах карты детектора, sx, sy — пересчёт в снимок."""
    w, h = sides(box)
    if w < LONG * h:
        return None
    u = (box[1] - box[0]) / w; nv = (box[3] - box[0]) / h; c0 = box.mean(0)
    rx = pts[:, 0] - c0[0]; ry = pts[:, 1] - c0[1]
    s = rx * u[0] + ry * u[1]; t = rx * nv[0] + ry * nv[1]
    si = np.rint(s).astype(int)
    lo = si.min(); nb = si.max() - lo + 1
    tmin = np.full(nb, np.inf); tmax = np.full(nb, -np.inf); cnt = np.zeros(nb)
    np.minimum.at(tmin, si - lo, t); np.maximum.at(tmax, si - lo, t); np.add.at(cnt, si - lo, 1)
    ok = cnt > 0
    if ok.sum() < 3:
        return None
    ks = np.nonzero(ok)[0] + lo
    th = tmax[ok] - tmin[ok] + 1; mid = (tmin[ok] + tmax[ok]) / 2; wt = cnt[ok]
    T = float(np.median(th))
    smin, smax = float(s.min()), float(s.max())
    S = max(abs(smin), abs(smax), 1.0)
    A = [[0.0] * 3 for _ in range(3)]; b = [0.0] * 3
    for kk, c, ww in zip(ks, mid, wt):
        z = kk / S; v = (1.0, z, z * z)
        for i in range(3):
            b[i] += ww * c * v[i]
            for j in range(3):
                A[i][j] += ww * v[i] * v[j]
    q = solve3(A, b)
    if q is None:
        return None
    dz = (smax - smin) / S
    sag = abs(q[2]) * dz * dz / 4
    crowded = intrusion(lab, k, ebox) >= INTRUDE * len(pts)
    if sag < SAG_MIN * T and not crowded:
        return None
    L = smax - smin + 1
    d = L * T * unclip / (2 * (L + T))
    return dict(u=u, n=nv, c0=c0, q=q, S=S, T=T, d=d, s0=smin - d, s1=smax + d, sx=sx, sy=sy,
                sag=sag, crowded=crowded)


def interp1(x, xp, fp):
    """np.interp для одной точки (так же в OcrCore.interp)."""
    if x <= xp[0]:
        return fp[0]
    if x >= xp[-1]:
        return fp[-1]
    j = int(np.searchsorted(xp, x, side='right')) - 1
    return (fp[j + 1] - fp[j]) / (xp[j + 1] - xp[j]) * (x - xp[j]) + fp[j]


def crop_strip(img, st):
    """Вырез полосы: по длине дуги средней линии, поперёк — по её нормали, высота T + 2d."""
    q, S = st['q'], st['S']
    def cv(s):
        z = s / S
        return (q[2] * z + q[1]) * z + q[0]
    def sl(s):
        return (2 * q[2] * (s / S) + q[1]) / S
    step = (st['s1'] - st['s0']) / (ARC_N - 1)
    ss = [st['s0'] + i * step for i in range(ARC_N - 1)] + [st['s1']]
    arc = [0.0]
    g0 = sl(ss[0]); prev = math.sqrt(1 + g0 * g0)
    for i in range(1, ARC_N):
        gi = sl(ss[i]); cur = math.sqrt(1 + gi * gi)
        arc.append(arc[-1] + (cur + prev) / 2 * (ss[i] - ss[i - 1])); prev = cur
    sig = (st['sx'] + st['sy']) / 2
    Wc = max(1, int(arc[-1] * sig)); Hc = max(1, int((st['T'] + 2 * st['d']) * sig))
    half = st['T'] / 2 + st['d']
    u, nv, c0 = st['u'], st['n'], st['c0']
    X = np.empty((Hc, Wc)); Y = np.empty((Hc, Wc))
    off = (np.arange(Hc) + 0.5) / sig - half            # поэлементно — те же действия, что в OcrCore
    for x in range(Wc):
        s = interp1((x + 0.5) / sig, arc, ss)
        c = cv(s); g = sl(s); nrm = math.sqrt(1 + g * g); Nu = -g / nrm; Nn = 1 / nrm
        a = s + off * Nu; b = c + off * Nn
        X[:, x] = (c0[0] + a * u[0] + b * nv[0]) * st['sx'] - 0.5
        Y[:, x] = (c0[1] + a * u[1] + b * nv[1]) * st['sy'] - 0.5
    return bilinear(img, X, Y)


def crop_line(img, box, st):
    return crop_strip(img, st) if st is not None else crop(img, box)


def bilinear(img, X, Y):
    H, W = img.shape[:2]
    X = np.clip(X, 0, W - 1); Y = np.clip(Y, 0, H - 1)
    x0 = np.floor(X).astype(int); y0 = np.floor(Y).astype(int)
    x1 = np.minimum(x0 + 1, W - 1); y1 = np.minimum(y0 + 1, H - 1)
    fx = (X - x0)[..., None]; fy = (Y - y0)[..., None]
    f = img.astype(np.float32)
    top = f[y0, x0] * (1 - fx) + f[y0, x1] * fx
    bot = f[y1, x0] * (1 - fx) + f[y1, x1] * fx
    return np.clip(top * (1 - fy) + bot * fy + 0.5, 0, 255).astype(np.uint8)


class Rec:
    def __init__(self, path, yml, max_w=3200):
        self.s = session(path)
        # Словарь, вшитый в ONNX, главнее yml: латинская v5 от RapidAI собрана из более ранней
        # версии модели (503 знака), а inference.yml у PaddlePaddle сейчас на 836 — со сдвигом
        # весь вывод превращается в шифр подстановки («WpajĆĂk» вместо «ATENÇÃO»).
        emb = self.s.get_modelmeta().custom_metadata_map.get('character')
        # В конце вшитого списка — перевод строки, split даёт пустой последний знак, и пробел
        # декодировался бы в пустоту: слова слипались («Segunda-QuintaSexta-feiradas12:30»).
        self.chars = ['<blank>'] + [c for c in emb.split('\n') if c] if emb else read_dict(yml)
        classes = self.s.get_outputs()[0].shape[-1]
        if isinstance(classes, int):
            if len(self.chars) == classes - 1:
                self.chars.append(' ')
            assert len(self.chars) == classes, f'словарь {len(self.chars)} против {classes} классов'
        self.max_w = max_w

    def run(self, crops, batch=6):
        # устойчиво, как OcrCore.recOrder: от состава пачки зависит ширина с добивкой
        order = np.argsort([c.shape[1] / c.shape[0] for c in crops], kind='stable')
        out = [None] * len(crops)
        for b in range(0, len(crops), batch):
            idx = order[b:b + batch]
            ratio = max(max(crops[i].shape[1] / crops[i].shape[0] for i in idx), 320 / 48)
            W = min(self.max_w, int(math.ceil(48 * ratio)))
            x = np.zeros((len(idx), 3, 48, W), np.float32)
            for j, i in enumerate(idx):
                c = crops[i]; rw = min(W, int(math.ceil(48 * c.shape[1] / c.shape[0])))
                r = resize(c, max(rw, 1), 48)[:, :, ::-1] / 255.0
                x[j, :, :, :rw] = ((r - 0.5) / 0.5).transpose(2, 0, 1)
            y = self.s.run(None, {'x': x})[0]
            for j, i in enumerate(idx):
                out[i] = self.decode(y[j])
        return out

    def decode(self, p):
        k = p.argmax(1); m = p.max(1)
        txt, sc, prev = [], [], -1
        for t in range(len(k)):
            if k[t] != prev and k[t] != 0:
                txt.append(self.chars[k[t]] if k[t] < len(self.chars) else ''); sc.append(m[t])
            prev = k[t]
        return ''.join(txt), (float(np.mean(sc)) if sc else 0.0)


def geom(b):
    """Рамка как отрезок средней линии: левая и правая середины, направление, высота."""
    tl, tr, br, bl = b
    L = (tl + bl) / 2; R = (tr + br) / 2
    d = R - L; n = float(np.linalg.norm(d))
    u = d / n if n > 1e-6 else np.array([1.0, 0.0])
    h = (np.linalg.norm(tl - bl) + np.linalg.norm(tr - br)) / 2
    return L, R, u, float(h)


# Слова, на которых фраза не кончается: строка, оборванная на них, продолжается следующей.
CONT = set('e de da do das dos a o as os com em no na nos nas num numa para por pelo pela pelos pelas '
           'que ao aos à às um uma ou se sem sob sobre entre até seu sua seus suas é '
           'está estão sendo foi ser são não mais como quando onde '
           # начала составных имён: «construir São / Paulo» иначе переводилось «Сан-Франциско» и «Павел»
           'santa santo dom dona rio porto belo nova novo'.split())


def layout(boxes, texts, min_score=0.5):
    """Рамки -> блоки -> строки. Возвращает список блоков, блок — список строк (текст, как на вывеске).

    Строка: рамки с тем же наклоном (до 15°), середина следующей не дальше полувысоты от средней
    линии предыдущей, промежуток не больше 1,2 высоты. Слова одной строки детектор и так отдаёт
    одной рамкой; отдельные рамки на одной высоте — это колонки («Guapíara … 4 km», две колонки
    цен), и при 2,5 высоты они склеивались в одну строку через всю вывеску.
    Сравнение по средней линии, а не по вертикальному охвату: на снятой под углом вывеске охват
    длинной строки задевает соседние, и строки склеивались («Segunda-Quinta … Domingos … Sabados»).
    Блок: строки одной высоты (в 1,5 раза), перекрытые по горизонтали, с шагом до 1,8 высоты.
    Блоки — сверху вниз, а стоящие рядом по вертикали — слева направо (колонки).
    Цифры штрихкода (barcode) в строку с текстом не встают: на этикетке соуса «7 896025 804067»
    стояли вровень с последней строкой колонки справа и уезжали в перевод посреди фразы
    («feche a 7 896025804067 tampa»)."""
    items = []
    for b, (t, s) in zip(boxes, texts):
        if s < min_score or not t.strip():
            continue
        L, R, u, h = geom(b)
        items.append(dict(b=b, t=t.strip(), s=float(s), L=L, R=R, u=u, h=h, c=(L + R) / 2))
    items.sort(key=lambda it: it['L'][0])
    rows = []
    for it in items:
        best, bd = None, None
        for row in rows:
            a = row[-1]
            if abs(float(np.dot(a['u'], it['u']))) < 0.966 or barcode(a['t']) != barcode(it['t']):
                continue
            hmax, hmin = max(a['h'], it['h']), min(a['h'], it['h'])
            if hmax > 2 * hmin:
                continue
            v = it['c'] - a['c']
            perp = abs(a['u'][0] * v[1] - a['u'][1] * v[0])
            gap = float(np.dot(a['u'], it['L'] - a['R']))
            if perp < 0.5 * hmax and -0.5 * hmax < gap < 1.2 * hmax and (bd is None or perp < bd):
                best, bd = row, perp
        if best is not None:
            best.append(it)
        else:
            rows.append([it])
    rs = []
    for row in rows:
        pts = np.concatenate([it['b'] for it in row])
        h = float(np.mean([it['h'] for it in row]))
        c = np.mean([it['c'] for it in row], 0); u = row[0]['u']
        rs.append(dict(text=' '.join(it['t'] for it in row), x0=float(pts[:, 0].min()), x1=float(pts[:, 0].max()),
                       c=c, u=u, h=h, boxes=[it['b'] for it in row], scores=[(len(it['t']), it['s']) for it in row]))
    rs.sort(key=lambda r: r['c'][1])

    def y_at(r, x):
        u = r['u']
        return r['c'][1] + (x - r['c'][0]) * (u[1] / u[0] if abs(u[0]) > 1e-6 else 0.0)

    blocks = []
    for r in rs:
        placed = False
        for bl in reversed(blocks):
            p = bl[-1]
            ov = min(p['x1'], r['x1']) - max(p['x0'], r['x0'])
            # строка, оборванная на служебном слове, продолжается и строкой другого кегля:
            # «HORÁRIO DE» мелко, «FUNCIONAMENTO» крупно
            words = p['text'].split()
            cont = bool(words) and re.sub(r'[^\w]', '', words[-1].lower()) in CONT
            if ov <= 0 or max(p['h'], r['h']) > (2.5 if cont else 1.5) * min(p['h'], r['h']):
                continue
            xm = (max(p['x0'], r['x0']) + min(p['x1'], r['x1'])) / 2
            dy = y_at(r, xm) - y_at(p, xm)
            if 0 < dy < 1.8 * max(p['h'], r['h']):
                bl.append(r); placed = True
                break
        if not placed:
            blocks.append([r])

    def ext(bl):
        return (min(r['x0'] for r in bl), max(r['x1'] for r in bl),
                min(r['c'][1] - r['h'] / 2 for r in bl), max(r['c'][1] + r['h'] / 2 for r in bl))
    blocks.sort(key=lambda bl: ext(bl)[2])
    for _ in range(len(blocks)):
        swapped = False
        for i in range(len(blocks) - 1):
            ax0, ax1, ay0, ay1 = ext(blocks[i]); bx0, bx1, by0, by1 = ext(blocks[i + 1])
            ov = min(ay1, by1) - max(ay0, by0)
            if ov > 0.3 * min(ay1 - ay0, by1 - by0) and bx1 <= ax0 + 1:
                blocks[i], blocks[i + 1] = blocks[i + 1], blocks[i]; swapped = True
        if not swapped:
            break
    return blocks


def barcode(t):
    """Цифры под штрихкодом: восемь и больше цифр подряд и ни одной буквы. Между группами цифр
    распознаватель ставит пробел или кавычку («"896025'804067"» на смазанном снимке). Телефон
    «3242-3300» и цены под это не подходят."""
    return not re.search(r'[^\W\d_]', t) and bool(re.search(r'\d(?:[ \'’"]?\d){7,}', t))


def paragraphs(block):
    """Строки блока -> фразы для перевода. Склеиваем, только когда обрыв очевиден: перенос со
    знаком «-», запятая в конце, строка кончается служебным словом («… ESTREITA E»), открытой
    скобкой или тире («(5°C - / 10°C)») или следующая начинается со строчной. Иначе строка —
    отдельная фраза: у вывески строки чаще самостоятельны (часы работы, цены, список
    направлений), и склейка их портила бы. Цифры штрихкода не склеиваются ни с чем."""
    out = []                                   # [(текст, [строки блока])]
    for r in block:
        t = r['text']
        if out:
            prev, rows = out[-1]
            last = re.sub(r'[^\w]', '', prev.split()[-1].lower()) if prev.split() else ''
            if prev.endswith('-') and len(prev) > 1 and prev[-2].isalpha():
                out[-1] = (prev[:-1] + t, rows + [r]); continue
            if barcode(prev) or barcode(t):
                pass
            elif not re.search(r'[.!?:;]$', prev) and (prev.endswith(',') or last in CONT or t[:1].islower()
                                                     or prev.count('(') > prev.count(')')
                                                     or re.search(r'\s[-–—]$', prev)):
                out[-1] = (prev + ' ' + t, rows + [r]); continue
        out.append((t, [r]))
    return out


CONF_MIN = 0.80    # абзац с уверенностью ниже не переводится: ниже 0,82 на наборе — почти только мусор


def para_conf(rows):
    """Уверенность абзаца: средняя уверенность распознавателя по его рамкам, с весом длины текста."""
    n = sum(l for r in rows for l, _ in r['scores'])
    return sum(l * sc for r in rows for l, sc in r['scores']) / n if n else 0.0


def lines_of(boxes, texts, min_score=0.5):
    """Строки как на вывеске, по порядку чтения (для сверки с расшифровкой)."""
    return '\n'.join(r['text'] for bl in layout(boxes, texts, min_score) for r in bl)


def text_of(boxes, texts, min_score=0.5):
    """Текст для перевода: строки блока склеены по paragraphs()."""
    return '\n'.join(t for bl in layout(boxes, texts, min_score) for t, _ in paragraphs(bl))


class Ocr:
    def __init__(self, det_dir, rec_dir, det_max=960, kind=None):
        import os
        kind = kind or ('v6' if 'v6' in os.path.basename(det_dir.rstrip('/')) else 'v5')
        self.det = Det(f'{det_dir}/det.onnx', kind, det_max)
        self.rec = Rec(f'{rec_dir}/rec.onnx', f'{rec_dir}/rec.yml')

    def read(self, img):
        t0 = time.time()
        boxes, _ = self.det.run(img)
        t1 = time.time()
        texts = self.rec.run([crop_line(img, b, st) for b, st in zip(boxes, self.det.strips)]) if boxes else []
        t2 = time.time()
        self.last = (boxes, texts)
        return lines_of(boxes, texts), {'det_ms': (t1 - t0) * 1000, 'rec_ms': (t2 - t1) * 1000, 'boxes': len(boxes)}


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('model'); ap.add_argument('image'); ap.add_argument('--det', default=None)
    ap.add_argument('--det-max', type=int, default=960)
    a = ap.parse_args()
    o = Ocr(a.det or a.model, a.model, a.det_max)
    txt, st = o.read(load_rgb(a.image))
    print(txt); print(st, file=sys.stderr)
