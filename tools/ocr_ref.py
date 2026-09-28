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
        boxes = []
        for sl, k in zip(ndimage.find_objects(lab), range(1, n + 1)):
            ys, xs = np.nonzero(lab[sl] == k)
            if len(xs) < 4:
                continue
            pts = np.stack([xs + sl[1].start, ys + sl[0].start], 1)
            box = min_area_rect(pts)
            if min(sides(box)) < 3:
                continue
            if poly_mean(prob, box) < self.p['box_thresh']:
                continue
            bw, bh = sides(box)
            d = bw * bh * self.p['unclip'] / (2 * (bw + bh))
            box = expand(box, d)                  # сторона после расширения ≥ 5,1 сама (см. OcrCore.boxes)
            box[:, 0] = np.clip(box[:, 0] * w / rw, 0, w); box[:, 1] = np.clip(box[:, 1] * h / rh, 0, h)
            boxes.append(box)
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
    Блоки — сверху вниз, а стоящие рядом по вертикали — слева направо (колонки)."""
    items = []
    for b, (t, s) in zip(boxes, texts):
        if s < min_score or not t.strip():
            continue
        L, R, u, h = geom(b)
        items.append(dict(b=b, t=t.strip(), L=L, R=R, u=u, h=h, c=(L + R) / 2))
    items.sort(key=lambda it: it['L'][0])
    rows = []
    for it in items:
        best, bd = None, None
        for row in rows:
            a = row[-1]
            if abs(float(np.dot(a['u'], it['u']))) < 0.966:
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
                       c=c, u=u, h=h, boxes=[it['b'] for it in row]))
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


def paragraphs(block):
    """Строки блока -> фразы для перевода. Склеиваем, только когда обрыв очевиден: перенос со
    знаком «-», запятая в конце, строка кончается служебным словом («… ESTREITA E») или
    следующая начинается со строчной. Иначе строка — отдельная фраза: у вывески строки чаще
    самостоятельны (часы работы, цены, список направлений), и склейка их портила бы."""
    out = []                                   # [(текст, [строки блока])]
    for r in block:
        t = r['text']
        if out:
            prev, rows = out[-1]
            last = re.sub(r'[^\w]', '', prev.split()[-1].lower()) if prev.split() else ''
            if prev.endswith('-') and len(prev) > 1 and prev[-2].isalpha():
                out[-1] = (prev[:-1] + t, rows + [r]); continue
            if not re.search(r'[.!?:;]$', prev) and (prev.endswith(',') or last in CONT or t[:1].islower()):
                out[-1] = (prev + ' ' + t, rows + [r]); continue
        out.append((t, [r]))
    return out


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
        texts = self.rec.run([crop(img, b) for b in boxes]) if boxes else []
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
