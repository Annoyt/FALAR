package dev.agenttranslator;

import java.util.*;
import java.util.regex.Pattern;

/** Офлайн-распознавание текста на снимке — всё, что не требует Android и ONNX Runtime.
 *
 *  Повторяет эталон tools/ocr_ref.py шаг в шаг, чтобы вывод телефона сверялся с ним:
 *  пересчёт размера треугольным фильтром (как PIL BILINEAR), разбор карты детектора DB
 *  (связные области → прямоугольник наименьшей площади → расширение), вырез строки
 *  с выпрямлением, жадная расшифровка CTC, сборка рамок в строки, блоки и абзацы для перевода,
 *  цвета фона и текста для наложения перевода поверх снимка.
 *
 *  Модели выбраны замером на 32 снимках вывесок (bench/ocr, tools/ocr_eval.py): детектор
 *  PP-OCRv6 small и латинский распознаватель PP-OCRv5 mobile — 92–93 % слов буква в букву.
 *  Проверяется на столе: bench/apk/test/OcrCoreTest.java.
 */
public final class OcrCore {
  private OcrCore() {}

  /** Параметры разбора карты детектора — из inference.yml PP-OCRv6 small det. */
  public static final float THRESH = 0.2f, BOX_THRESH = 0.45f, UNCLIP = 1.4f;
  /** Длинная сторона входа детектора и высота строки распознавателя. */
  public static final int DET_MAX = 960, REC_H = 48, REC_MIN_W = 320, REC_MAX_W = 3200, REC_BATCH = 6;
  /** Строки с уверенностью ниже — шум с фона. */
  public static final float MIN_SCORE = 0.5f;
  /** Абзац с уверенностью ниже не переводится: на наборе вывесок строки ниже 0,82 — почти только
   *  мусор («VINTID», «ARMREO NMERBEREG»), первая верная — «Presidencial» с 0,825. */
  public static final double CONF_MIN = 0.80;
  static final float[] MEAN = {0.485f, 0.456f, 0.406f}, STD = {0.229f, 0.224f, 0.225f};

  // ---------- пересчёт размера ----------

  /** Веса одномерного пересчёта n → m треугольным фильтром (PIL BILINEAR): при уменьшении
   *  фильтр шире в масштаб раз, то есть сглаживает по площади. Простая билинейная выборка
   *  (cv2.INTER_LINEAR) на наборе вывесок дала 92,4 % слов против 93,4 %. */
  static final class Taps {
    final int[] start; final float[][] w;
    Taps(int n, int m) {
      start = new int[m]; w = new float[m][];
      double scale = (double) n / m, fs = Math.max(scale, 1.0);
      for (int i = 0; i < m; i++) {
        double c = (i + 0.5) * scale;
        int x0 = Math.max(0, (int) (c - fs + 0.5)), x1 = Math.min(n, (int) (c + fs + 0.5));
        if (x1 <= x0) x1 = Math.min(n, x0 + 1);
        double[] k = new double[x1 - x0]; double t = 0;
        for (int x = x0; x < x1; x++) { k[x - x0] = Math.max(0, 1 - Math.abs((x - c + 0.5) / fs)); t += k[x - x0]; }
        float[] f = new float[k.length];
        for (int j = 0; j < k.length; j++) f[j] = (float) (t > 0 ? k[j] / t : k[j]);
        start[i] = x0; w[i] = f;
      }
    }
  }

  /** Область src (ARGB, ширина sw) [x, y, cw×ch] → rgb[m×n×3] размера dw×dh, float 0..255. */
  static float[] resize(int[] src, int sw, int cx, int cy, int cw, int ch, int dw, int dh) {
    Taps tx = new Taps(cw, dw), ty = new Taps(ch, dh);
    float[] tmp = new float[ch * dw * 3];
    for (int y = 0; y < ch; y++) {
      int row = (cy + y) * sw + cx;
      for (int j = 0; j < dw; j++) {
        int s = tx.start[j]; float[] k = tx.w[j]; double r = 0, g = 0, b = 0;
        for (int q = 0; q < k.length; q++) { int p = src[row + s + q]; r += k[q] * ((p >> 16) & 255); g += k[q] * ((p >> 8) & 255); b += k[q] * (p & 255); }
        int o = (y * dw + j) * 3; tmp[o] = (float) r; tmp[o + 1] = (float) g; tmp[o + 2] = (float) b;
      }
    }
    float[] out = new float[dh * dw * 3];
    for (int i = 0; i < dh; i++) {
      int s = ty.start[i]; float[] k = ty.w[i];
      for (int j = 0; j < dw; j++) {
        double r = 0, g = 0, b = 0;
        for (int q = 0; q < k.length; q++) { int o = ((s + q) * dw + j) * 3; r += k[q] * tmp[o]; g += k[q] * tmp[o + 1]; b += k[q] * tmp[o + 2]; }
        int o = (i * dw + j) * 3; out[o] = (float) r; out[o + 1] = (float) g; out[o + 2] = (float) b;
      }
    }
    return out;
  }

  /** Размер входа детектора: длинная сторона не больше DET_MAX, обе кратны 32. Округление
   *  половины к чётному — как round() в Python у эталона. */
  public static int[] detSize(int w, int h) {
    double r = Math.min(1.0, (double) DET_MAX / Math.max(w, h));
    int rw = Math.max(32, (int) Math.rint(w * r / 32) * 32), rh = Math.max(32, (int) Math.rint(h * r / 32) * 32);
    return new int[]{rw, rh};
  }

  /** Вход детектора [1,3,rh,rw]: каналы в порядке BGR (PP-OCR учили на cv2), нормировка ImageNet. */
  public static float[] detInput(int[] argb, int w, int h, int rw, int rh) {
    float[] rgb = resize(argb, w, 0, 0, w, h, rw, rh);
    float[] x = new float[3 * rh * rw]; int plane = rh * rw;
    for (int i = 0; i < plane; i++) {
      for (int c = 0; c < 3; c++) {                         // c — канал BGR: 0 = синий
        float v = rgb[i * 3 + (2 - c)] / 255f;
        x[c * plane + i] = (v - MEAN[c]) / STD[c];
      }
    }
    return x;
  }

  // ---------- разбор карты детектора ----------

  /** Рамки строк на карте вероятностей pw×ph, в координатах снимка w×h. Рамка — 8 чисел:
   *  левая верхняя, правая верхняя, правая нижняя, левая нижняя (x, y). */
  public static List<double[]> boxes(float[] prob, int pw, int ph, int w, int h) { return boxes(prob, pw, ph, w, h, null); }

  /** То же и полосы изогнутых и тесных строк (strips, по одной на рамку, null — вырез рамкой).
   *  Области размечаются целиком до разбора: тесноту строки считают по соседним областям, в том
   *  числе ещё не разобранным (как ndimage.label в эталоне). */
  public static List<double[]> boxes(float[] prob, int pw, int ph, int w, int h, List<Strip> strips) {
    List<double[]> out = new ArrayList<>();
    int[] lab = new int[pw * ph], pix = new int[pw * ph], stack = new int[pw * ph]; int n = 0, np = 0;
    List<int[]> comp = new ArrayList<>();                                   // [начало в pix, число пикселей]
    for (int start = 0; start < pw * ph; start++) {
      if (lab[start] != 0 || !(prob[start] > THRESH)) continue;
      n++; int first = np, sp = 0; stack[sp++] = start; lab[start] = n;
      while (sp > 0) {
        int p = stack[--sp]; int px = p % pw, py = p / pw; pix[np++] = p;
        for (int dy = -1; dy <= 1; dy++) {
          int qy = py + dy; if (qy < 0 || qy >= ph) continue;
          for (int dx = -1; dx <= 1; dx++) {
            int qx = px + dx; if ((dx == 0 && dy == 0) || qx < 0 || qx >= pw) continue;
            int q = qy * pw + qx;
            if (lab[q] == 0 && prob[q] > THRESH) { lab[q] = n; stack[sp++] = q; }
          }
        }
      }
      comp.add(new int[]{first, np - first});
    }
    int[] mn = new int[ph], mx = new int[ph]; Arrays.fill(mn, Integer.MAX_VALUE); Arrays.fill(mx, -1);
    for (int k = 1; k <= n; k++) {
      int first = comp.get(k - 1)[0], count = comp.get(k - 1)[1];
      if (count < 4) continue;
      // по строкам: крайние левая и правая точки — выпуклой оболочке остальные не нужны
      int y0 = ph, y1 = -1;
      for (int i = first; i < first + count; i++) {
        int px = pix[i] % pw, py = pix[i] / pw;
        if (px < mn[py]) mn[py] = px; if (px > mx[py]) mx[py] = px; if (py < y0) y0 = py; if (py > y1) y1 = py;
      }
      List<double[]> pts = new ArrayList<>();
      for (int y = y0; y <= y1; y++) if (mx[y] >= 0) { pts.add(new double[]{mn[y], y}); if (mx[y] != mn[y]) pts.add(new double[]{mx[y], y}); }
      for (int y = y0; y <= y1; y++) { mn[y] = Integer.MAX_VALUE; mx[y] = -1; }
      double[] box = minAreaRect(pts);
      double[] sd = sides(box);
      if (Math.min(sd[0], sd[1]) < 3) continue;
      // Сильно изогнутая строка занимает свой прямоугольник меньше чем наполовину, и средняя по нему
      // уверенность проходила ниже порога — строка пропадала целиком (этикетка на бутылке, снятая
      // вплотную). Тогда судим по самой области (как score_mode «slow» у PaddleOCR), а оставляем,
      // только если она и правда дуга, — вырез ей тогда полосой.
      boolean weak = polyMean(prob, pw, ph, box) < BOX_THRESH;
      if (weak) { double sum = 0; for (int i = first; i < first + count; i++) sum += prob[pix[i]]; if (sum / count < BOX_THRESH) continue; }
      // Расширение на d (unclip для прямоугольника). Проверку «сторона после расширения < 5» из
      // PaddleOCR здесь не ставим: при стороне от 3 и UNCLIP 1,4 расширенная сторона не меньше 5,1.
      double[] eb = expand(box, sd[0] * sd[1] * UNCLIP / (2 * (sd[0] + sd[1])));
      Strip st = strips != null || weak ? stripOf(pix, first, count, pw, box, eb, lab, k, (double) w / pw, (double) h / ph) : null;
      if (weak && (st == null || st.sag < SAG_MIN * st.T)) continue;
      if (strips != null) strips.add(st);
      for (int j = 0; j < 4; j++) {
        eb[2 * j] = clamp(eb[2 * j] * w / pw, 0, w);
        eb[2 * j + 1] = clamp(eb[2 * j + 1] * h / ph, 0, h);
      }
      out.add(eb);
    }
    return out;
  }

  // ---------- изогнутые и тесные строки ----------

  /** Рамка строки — прямоугольник: у изогнутой строки (этикетка на бутылке) текст в нём гуляет
   *  вверх-вниз, а у тесных строк в вырез залезают соседние сверху и снизу. Таким строкам вырез —
   *  полоса вдоль средней линии области детектора, толщиной строки (tools/ocr_ref.py: strip_of).
   *  На двух снимках этикетки это 71 → 86 % слов; на наборе вывесок с просторными строками полоса
   *  у всех строк теряла 0,9 пункта, поэтому только там, где она нужна. */
  static final double SAG_MIN = 0.5, INTRUDE = 0.02, LONG = 3.0; static final int ARC_N = 2048;

  /** Полоса: средняя линия в координатах карты — c0 + s·u + c(s)·n, c(s) — парабола от s/S; T —
   *  толщина строки, d — расширение (unclip), s0..s1 — длина вместе с расширением; sx, sy — пересчёт
   *  карты в снимок. */
  public static final class Strip {
    final double ux, uy, nx, ny, cx, cy, q0, q1, q2, S, T, d, s0, s1, sx, sy; public final double sag; public final boolean crowded;
    Strip(double ux, double uy, double nx, double ny, double cx, double cy, double[] q, double S, double T, double d,
          double s0, double s1, double sx, double sy, double sag, boolean crowded) {
      this.ux = ux; this.uy = uy; this.nx = nx; this.ny = ny; this.cx = cx; this.cy = cy; q0 = q[0]; q1 = q[1]; q2 = q[2];
      this.S = S; this.T = T; this.d = d; this.s0 = s0; this.s1 = s1; this.sx = sx; this.sy = sy; this.sag = sag; this.crowded = crowded;
    }
    double cv(double s) { double z = s / S; return (q2 * z + q1) * z + q0; }
    double sl(double s) { return (2 * q2 * (s / S) + q1) / S; }
  }

  /** Сколько пикселей других областей внутри четырёхугольника (растр по центрам, как polyMean). */
  static int intrusion(int[] lab, int pw, int ph, int k, double[] b) {
    double mnx = Math.min(Math.min(b[0], b[2]), Math.min(b[4], b[6])), mxx = Math.max(Math.max(b[0], b[2]), Math.max(b[4], b[6]));
    double mny = Math.min(Math.min(b[1], b[3]), Math.min(b[5], b[7])), mxy = Math.max(Math.max(b[1], b[3]), Math.max(b[5], b[7]));
    int x0 = Math.max(0, (int) Math.floor(mnx)), x1 = Math.min(pw - 1, (int) Math.ceil(mxx));
    int y0 = Math.max(0, (int) Math.floor(mny)), y1 = Math.min(ph - 1, (int) Math.ceil(mxy));
    int cnt = 0;
    for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
      int l = lab[y * pw + x]; if (l == 0 || l == k) continue;
      boolean in = true;
      for (int j = 0; j < 4 && in; j++) {
        double px = b[2 * j], py = b[2 * j + 1], qx = b[2 * ((j + 1) % 4)], qy = b[2 * ((j + 1) % 4) + 1];
        if ((qx - px) * (y - py) - (qy - py) * (x - px) < -1e-6) in = false;
      }
      if (in) cnt++;
    }
    return cnt;
  }

  /** 3×3 методом Гаусса с выбором главного — тем же порядком действий, что solve3 в эталоне. */
  static double[] solve3(double[][] A, double[] b) {
    double[][] M = new double[3][4];
    for (int i = 0; i < 3; i++) { System.arraycopy(A[i], 0, M[i], 0, 3); M[i][3] = b[i]; }
    for (int c = 0; c < 3; c++) {
      int piv = c; for (int r = c + 1; r < 3; r++) if (Math.abs(M[r][c]) > Math.abs(M[piv][c])) piv = r;
      double[] t = M[c]; M[c] = M[piv]; M[piv] = t;
      if (Math.abs(M[c][c]) < 1e-12) return null;
      for (int r = c + 1; r < 3; r++) { double f = M[r][c] / M[c][c]; for (int j = c; j < 4; j++) M[r][j] -= f * M[c][j]; }
    }
    double[] x = new double[3];
    for (int r = 2; r >= 0; r--) { double acc = M[r][3]; for (int j = r + 1; j < 3; j++) acc -= M[r][j] * x[j]; x[r] = acc / M[r][r]; }
    return x;
  }

  /** Полоса для изогнутой или тесной строки, null — вырезать рамкой. box — прямоугольник
   *  области до расширения, eb — после (в координатах карты). */
  static Strip stripOf(int[] pix, int first, int count, int pw, double[] box, double[] eb, int[] lab, int k, double sx, double sy) {
    double[] sd = sides(box); double w = sd[0], h = sd[1];
    if (w < LONG * h) return null;
    double ux = (box[2] - box[0]) / w, uy = (box[3] - box[1]) / w, nx = (box[6] - box[0]) / h, ny = (box[7] - box[1]) / h;
    double cx = (box[0] + box[2] + box[4] + box[6]) / 4, cy = (box[1] + box[3] + box[5] + box[7]) / 4;
    double[] s = new double[count], t = new double[count]; double smin = Double.MAX_VALUE, smax = -Double.MAX_VALUE;
    int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE; int[] si = new int[count];
    for (int i = 0; i < count; i++) {
      double rx = pix[first + i] % pw - cx, ry = pix[first + i] / pw - cy;
      s[i] = rx * ux + ry * uy; t[i] = rx * nx + ry * ny;
      si[i] = (int) Math.rint(s[i]); lo = Math.min(lo, si[i]); hi = Math.max(hi, si[i]);
      smin = Math.min(smin, s[i]); smax = Math.max(smax, s[i]);
    }
    int nb = hi - lo + 1; double[] tmin = new double[nb], tmax = new double[nb]; int[] cnt = new int[nb];
    Arrays.fill(tmin, Double.POSITIVE_INFINITY); Arrays.fill(tmax, Double.NEGATIVE_INFINITY);
    for (int i = 0; i < count; i++) { int j = si[i] - lo; tmin[j] = Math.min(tmin[j], t[i]); tmax[j] = Math.max(tmax[j], t[i]); cnt[j]++; }
    int used = 0; for (int c : cnt) if (c > 0) used++;
    if (used < 3) return null;
    double[] th = new double[used]; int m = 0;
    for (int j = 0; j < nb; j++) if (cnt[j] > 0) th[m++] = tmax[j] - tmin[j] + 1;
    Arrays.sort(th);
    double T = used % 2 == 1 ? th[used / 2] : (th[used / 2 - 1] + th[used / 2]) / 2;
    double S = Math.max(Math.max(Math.abs(smin), Math.abs(smax)), 1.0);
    double[][] A = new double[3][3]; double[] b = new double[3];
    for (int j = 0; j < nb; j++) {
      if (cnt[j] == 0) continue;
      double z = (j + lo) / S, c = (tmin[j] + tmax[j]) / 2, ww = cnt[j]; double[] v = {1.0, z, z * z};
      for (int a = 0; a < 3; a++) { b[a] += ww * c * v[a]; for (int e = 0; e < 3; e++) A[a][e] += ww * v[a] * v[e]; }
    }
    double[] q = solve3(A, b);
    if (q == null) return null;
    double dz = (smax - smin) / S, sag = Math.abs(q[2]) * dz * dz / 4;
    boolean crowded = intrusion(lab, pw, lab.length / pw, k, eb) >= INTRUDE * count;
    if (sag < SAG_MIN * T && !crowded) return null;
    double L = smax - smin + 1, d = L * T * UNCLIP / (2 * (L + T));
    return new Strip(ux, uy, nx, ny, cx, cy, q, S, T, d, smin - d, smax + d, sx, sy, sag, crowded);
  }

  /** Ось цилиндра на строке (в длине дуги): вершина параболы, если она внутри строки, иначе середина. */
  static double axis(Strip st, double[] ss, double[] arc) {
    if (st.q2 != 0) { double sv = -st.q1 / (2 * st.q2) * st.S; if (ss[0] < sv && sv < ss[ss.length - 1]) return interp(sv, ss, arc); }
    return arc[arc.length - 1] / 2;
  }

  /** np.interp для одной точки. */
  static double interp(double x, double[] xp, double[] fp) {
    int last = xp.length - 1;
    if (x <= xp[0]) return fp[0];
    if (x >= xp[last]) return fp[last];
    int lo = 0, hi = last;                                  // последний j с xp[j] <= x
    while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (xp[mid] <= x) lo = mid; else hi = mid; }
    return (fp[lo + 1] - fp[lo]) / (xp[lo + 1] - xp[lo]) * (x - xp[lo]) + fp[lo];
  }

  /** Растяжение полосы к краям, как у надписи на цилиндре: буквы у краёв бутылки сжаты по ширине.
   *  Ось — вершина параболы строки (или середина), радиус — STRETCH от наибольшего расстояния до
   *  оси: край строки на 34°, там растяжение в 1,2 раза (tools/ocr_ref.py: STRETCH — там и замер). */
  public static final double STRETCH = 1.8;

  /** Вырез полосы: по длине дуги средней линии, поперёк — по её нормали, высота T + 2d, с растяжением. */
  public static int[] cropStrip(int[] argb, int w, int h, Strip st, int[] outWH) { return cropStrip(argb, w, h, st, STRETCH, outWH); }
  /** rho ≤ 0 — без растяжения. */
  public static int[] cropStrip(int[] argb, int w, int h, Strip st, double rho, int[] outWH) {
    double step = (st.s1 - st.s0) / (ARC_N - 1); double[] ss = new double[ARC_N], arc = new double[ARC_N];
    for (int i = 0; i < ARC_N - 1; i++) ss[i] = st.s0 + i * step;
    ss[ARC_N - 1] = st.s1;
    double prev = Math.sqrt(1 + st.sl(ss[0]) * st.sl(ss[0]));
    for (int i = 1; i < ARC_N; i++) { double cur = Math.sqrt(1 + st.sl(ss[i]) * st.sl(ss[i])); arc[i] = arc[i - 1] + (cur + prev) / 2 * (ss[i] - ss[i - 1]); prev = cur; }
    double sig = (st.sx + st.sy) / 2, half = st.T / 2 + st.d, end = arc[ARC_N - 1], a0 = 0, R = 0, l0 = 0;
    int cw, ch = Math.max(1, (int) ((st.T + 2 * st.d) * sig));
    if (rho > 0) {                                           // столбец выреза — равный шаг по окружности цилиндра
      a0 = axis(st, ss, arc); R = rho * Math.max(a0, end - a0);
      l0 = R * Math.asin(-a0 / R); double l1 = R * Math.asin((end - a0) / R);
      cw = Math.max(1, (int) ((l1 - l0) * sig));
    } else cw = Math.max(1, (int) (end * sig));
    int[] out = new int[cw * ch];
    for (int x = 0; x < cw; x++) {
      double p = rho > 0 ? a0 + R * Math.sin((l0 + (x + 0.5) / sig) / R) : (x + 0.5) / sig;
      double s = interp(p, arc, ss), c = st.cv(s), g = st.sl(s), nrm = Math.sqrt(1 + g * g), nu = -g / nrm, nn = 1 / nrm;
      for (int y = 0; y < ch; y++) {
        double o = (y + 0.5) / sig - half, a = s + o * nu, bb = c + o * nn;
        double X = (st.cx + a * st.ux + bb * st.nx) * st.sx - 0.5, Y = (st.cy + a * st.uy + bb * st.ny) * st.sy - 0.5;
        out[y * cw + x] = sample(argb, w, h, X, Y);
      }
    }
    outWH[0] = cw; outWH[1] = ch; return out;
  }

  static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }

  /** Прямоугольник наименьшей площади вокруг точек: выпуклая оболочка и перебор её рёбер. Точки
   *  на одной прямой — отрезок нулевой ширины (как cv2.minAreaRect): такая область не текст, и
   *  по размеру её отбросит разбор. Прежний охват по осям делал из диагонали в четыре точки
   *  квадрат 3×3, и он проходил как строка. */
  static double[] minAreaRect(List<double[]> pts) {
    List<double[]> hull = hull(pts);
    if (hull.size() < 3) {
      double[] a = hull.get(0), b = hull.get(hull.size() - 1);
      return orderBox(new double[]{a[0], a[1], b[0], b[1], b[0], b[1], a[0], a[1]});
    }
    double best = Double.MAX_VALUE, bc = 1, bs = 0, bx0 = 0, by0 = 0, bx1 = 0, by1 = 0;
    for (int i = 0; i < hull.size(); i++) {
      double[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
      double ang = Math.atan2(b[1] - a[1], b[0] - a[0]), c = Math.cos(ang), s = Math.sin(ang);
      double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
      for (double[] p : hull) {
        double rx = c * p[0] + s * p[1], ry = -s * p[0] + c * p[1];
        x0 = Math.min(x0, rx); x1 = Math.max(x1, rx); y0 = Math.min(y0, ry); y1 = Math.max(y1, ry);
      }
      double area = (x1 - x0) * (y1 - y0);
      if (area < best) { best = area; bc = c; bs = s; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1; }
    }
    double[][] r = {{bx0, by0}, {bx1, by0}, {bx1, by1}, {bx0, by1}};
    double[] box = new double[8];
    for (int k = 0; k < 4; k++) { box[2 * k] = bc * r[k][0] - bs * r[k][1]; box[2 * k + 1] = bs * r[k][0] + bc * r[k][1]; }
    return orderBox(box);
  }

  /** Выпуклая оболочка (монотонная цепь Эндрю); коллинеарные точки выбрасываются. */
  static List<double[]> hull(List<double[]> pts) {
    List<double[]> p = new ArrayList<>(pts);
    p.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
    List<double[]> u = new ArrayList<>();
    if (p.size() < 3) return p;                     // крайние по порядку — первая и последняя
    double[][] h = new double[2 * p.size()][]; int k = 0;
    for (double[] q : p) { while (k >= 2 && cross(h[k - 2], h[k - 1], q) <= 0) k--; h[k++] = q; }
    for (int i = p.size() - 2, t = k + 1; i >= 0; i--) { double[] q = p.get(i); while (k >= t && cross(h[k - 2], h[k - 1], q) <= 0) k--; h[k++] = q; }
    for (int i = 0; i < k - 1; i++) u.add(h[i]);
    return u;
  }

  static double cross(double[] o, double[] a, double[] b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }

  /** Как get_mini_boxes в PaddleOCR: две левые по x, из них верхняя — первая; дальше по часовой. */
  static double[] orderBox(double[] b) {
    Integer[] i = {0, 1, 2, 3};
    Arrays.sort(i, (a, c) -> Double.compare(b[2 * a], b[2 * c]));          // устойчивая: как sorted() в Python
    int l0 = i[0], l1 = i[1], r0 = i[2], r1 = i[3];
    if (b[2 * l1 + 1] < b[2 * l0 + 1]) { int t = l0; l0 = l1; l1 = t; }
    if (b[2 * r1 + 1] < b[2 * r0 + 1]) { int t = r0; r0 = r1; r1 = t; }
    return new double[]{b[2 * l0], b[2 * l0 + 1], b[2 * r0], b[2 * r0 + 1], b[2 * r1], b[2 * r1 + 1], b[2 * l1], b[2 * l1 + 1]};
  }

  /** Ширина (верхняя сторона) и высота (правая сторона) рамки. */
  static double[] sides(double[] b) {
    return new double[]{Math.hypot(b[0] - b[2], b[1] - b[3]), Math.hypot(b[2] - b[4], b[3] - b[5])};
  }

  /** Средняя вероятность внутри четырёхугольника по центрам пикселей (box_score_fast). */
  static double polyMean(float[] prob, int pw, int ph, double[] b) {
    double mnx = Math.min(Math.min(b[0], b[2]), Math.min(b[4], b[6])), mxx = Math.max(Math.max(b[0], b[2]), Math.max(b[4], b[6]));
    double mny = Math.min(Math.min(b[1], b[3]), Math.min(b[5], b[7])), mxy = Math.max(Math.max(b[1], b[3]), Math.max(b[5], b[7]));
    int x0 = Math.max(0, (int) Math.floor(mnx)), x1 = Math.min(pw - 1, (int) Math.ceil(mxx));
    int y0 = Math.max(0, (int) Math.floor(mny)), y1 = Math.min(ph - 1, (int) Math.ceil(mxy));
    if (x1 < x0 || y1 < y0) return 0;
    double sum = 0; int cnt = 0;
    for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
      boolean in = true;
      for (int k = 0; k < 4 && in; k++) {
        double px = b[2 * k], py = b[2 * k + 1], qx = b[2 * ((k + 1) % 4)], qy = b[2 * ((k + 1) % 4) + 1];
        if ((qx - px) * (y - py) - (qy - py) * (x - px) < -1e-6) in = false;
      }
      if (in) { sum += prob[y * pw + x]; cnt++; }
    }
    return cnt == 0 ? 0 : sum / cnt;
  }

  /** Расширение прямоугольника на d со всех сторон — unclip для прямоугольника. */
  static double[] expand(double[] b, double d) {
    double cx = (b[0] + b[2] + b[4] + b[6]) / 4, cy = (b[1] + b[3] + b[5] + b[7]) / 4;
    double ux = b[2] - b[0], uy = b[3] - b[1], un = Math.max(Math.hypot(ux, uy), 1e-9); ux /= un; uy /= un;
    double vx = b[6] - b[0], vy = b[7] - b[1], vn = Math.max(Math.hypot(vx, vy), 1e-9); vx /= vn; vy /= vn;
    double[] sd = sides(b); double hw = sd[0] / 2 + d, hh = sd[1] / 2 + d;
    return orderBox(new double[]{
        cx - ux * hw - vx * hh, cy - uy * hw - vy * hh, cx + ux * hw - vx * hh, cy + uy * hw - vy * hh,
        cx + ux * hw + vx * hh, cy + uy * hw + vy * hh, cx - ux * hw + vx * hh, cy - uy * hw + vy * hh});
  }

  // ---------- вырез строки и вход распознавателя ----------

  /** Строка снимка по рамке, выпрямленная билинейной выборкой; out[0] — ширина, out[1] — высота.
   *  Высокая узкая рамка (h/w ≥ 1,5) — вертикальная надпись: поворот на 90° против часовой. */
  public static int[] crop(int[] argb, int w, int h, double[] b, int[] outWH) {
    int cw = Math.max(1, (int) Math.max(Math.hypot(b[0] - b[2], b[1] - b[3]), Math.hypot(b[4] - b[6], b[5] - b[7])));
    int ch = Math.max(1, (int) Math.max(Math.hypot(b[0] - b[6], b[1] - b[7]), Math.hypot(b[2] - b[4], b[3] - b[5])));
    double exx = (b[2] - b[0]) / cw, exy = (b[3] - b[1]) / cw, eyx = (b[6] - b[0]) / ch, eyy = (b[7] - b[1]) / ch;
    int[] out = new int[cw * ch];
    for (int v = 0; v < ch; v++) for (int u = 0; u < cw; u++) {
      double X = b[0] + (u + 0.5) * exx + (v + 0.5) * eyx - 0.5, Y = b[1] + (u + 0.5) * exy + (v + 0.5) * eyy - 0.5;
      out[v * cw + u] = sample(argb, w, h, X, Y);
    }
    if ((double) ch / cw >= 1.5) {                            // np.rot90: out'[i][j] = out[j][cw-1-i]
      int[] r = new int[cw * ch];
      for (int i = 0; i < cw; i++) for (int j = 0; j < ch; j++) r[i * ch + j] = out[j * cw + (cw - 1 - i)];
      outWH[0] = ch; outWH[1] = cw; return r;
    }
    outWH[0] = cw; outWH[1] = ch; return out;
  }

  static int sample(int[] a, int w, int h, double X, double Y) {
    X = clamp(X, 0, w - 1); Y = clamp(Y, 0, h - 1);
    int x0 = (int) Math.floor(X), y0 = (int) Math.floor(Y), x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
    double fx = X - x0, fy = Y - y0; int res = 0xFF000000;
    for (int sh = 16; sh >= 0; sh -= 8) {
      double top = ((a[y0 * w + x0] >> sh) & 255) * (1 - fx) + ((a[y0 * w + x1] >> sh) & 255) * fx;
      double bot = ((a[y1 * w + x0] >> sh) & 255) * (1 - fx) + ((a[y1 * w + x1] >> sh) & 255) * fx;
      int v = (int) (top * (1 - fy) + bot * fy + 0.5); res |= Math.max(0, Math.min(255, v)) << sh;
    }
    return res;
  }

  /** Порядок подачи строк распознавателю: по отношению сторон, устойчиво (как в эталоне) —
   *  от состава пачки зависит ширина с добивкой, а с ней, изредка, и прочтение. */
  public static Integer[] recOrder(List<int[]> wh) {
    Integer[] o = new Integer[wh.size()]; for (int i = 0; i < o.length; i++) o[i] = i;
    Arrays.sort(o, (a, b) -> Double.compare((double) wh.get(a)[0] / wh.get(a)[1], (double) wh.get(b)[0] / wh.get(b)[1]));
    return o;
  }

  /** Ширина входа распознавателя для пачки строк (w, h). */
  public static int recWidth(List<int[]> wh) {
    double ratio = (double) REC_MIN_W / REC_H;
    for (int[] s : wh) ratio = Math.max(ratio, (double) s[0] / s[1]);
    return Math.min(REC_MAX_W, (int) Math.ceil(REC_H * ratio));
  }

  /** Строка cw×ch → x[item] в пачке [n,3,48,W]: высота 48, ширина по отношению сторон,
   *  справа нули; каналы BGR, (v/255 − 0,5)/0,5. */
  public static void recInput(int[] crop, int cw, int ch, float[] x, int item, int W) {
    int rw = Math.max(1, Math.min(W, (int) Math.ceil(REC_H * (double) cw / ch)));
    float[] rgb = resize(crop, cw, 0, 0, cw, ch, rw, REC_H);
    int plane = REC_H * W, base = item * 3 * plane;
    for (int y = 0; y < REC_H; y++) for (int xx = 0; xx < rw; xx++) {
      int o = (y * rw + xx) * 3;
      for (int c = 0; c < 3; c++) x[base + c * plane + y * W + xx] = (rgb[o + (2 - c)] / 255f - 0.5f) / 0.5f;
    }
  }

  /** Жадная расшифровка CTC строки: argmax по классам, повторы схлопываются, 0 — пусто.
   *  chars[0] — пустой знак. score[0] — средняя вероятность оставленных знаков. */
  public static String ctc(float[] p, int off, int T, int C, String[] chars, float[] score) {
    StringBuilder sb = new StringBuilder(); double s = 0; int n = 0, prev = -1;
    for (int t = 0; t < T; t++) {
      int base = off + t * C, k = 0; float m = p[base];
      for (int c = 1; c < C; c++) if (p[base + c] > m) { m = p[base + c]; k = c; }
      if (k != prev && k != 0) { if (k < chars.length) sb.append(chars[k]); s += m; n++; }
      prev = k;
    }
    if (score != null) score[0] = n == 0 ? 0f : (float) (s / n);
    return sb.toString();
  }

  /** Словарь распознавателя: знаки из метаданных ONNX (по строке), впереди пустой, в конце
   *  пробел, если классов на один больше. Пустые строки выбрасываются: последний перевод строки
   *  давал пустой «знак», и пробел декодировался в пустоту — слова слипались. */
  public static String[] dict(String meta, int classes) {
    List<String> c = new ArrayList<>(); c.add("");
    for (String s : meta.split("\n", -1)) if (!s.isEmpty()) c.add(s);
    if (c.size() == classes - 1) c.add(" ");
    return c.toArray(new String[0]);
  }

  // ---------- строки, блоки, абзацы ----------

  /** Рамка с распознанным текстом и уверенностью распознавателя. */
  public static final class Item {
    public final double[] b; public final String t; public final double score; final double lx, ly, rx, ry, ux, uy, h, cx, cy;
    public Item(double[] b, String t) { this(b, t, 1.0); }
    public Item(double[] b, String t, double score) {
      this.b = b; this.t = t.trim(); this.score = score;
      lx = (b[0] + b[6]) / 2; ly = (b[1] + b[7]) / 2; rx = (b[2] + b[4]) / 2; ry = (b[3] + b[5]) / 2;
      double dx = rx - lx, dy = ry - ly, n = Math.hypot(dx, dy);
      ux = n > 1e-6 ? dx / n : 1; uy = n > 1e-6 ? dy / n : 0;
      h = (Math.hypot(b[0] - b[6], b[1] - b[7]) + Math.hypot(b[2] - b[4], b[3] - b[5])) / 2;
      cx = (lx + rx) / 2; cy = (ly + ry) / 2;
    }
  }

  /** Строка: рамки одной линии текста слева направо. */
  public static final class Row {
    public final List<Item> items = new ArrayList<>(); public String text; double x0, x1, cx, cy, ux, uy, h;
    void close() {
      StringBuilder sb = new StringBuilder(); x0 = Double.MAX_VALUE; x1 = -Double.MAX_VALUE; double sh = 0, sx = 0, sy = 0;
      for (Item it : items) {
        if (sb.length() > 0) sb.append(' '); sb.append(it.t);
        for (int k = 0; k < 4; k++) { x0 = Math.min(x0, it.b[2 * k]); x1 = Math.max(x1, it.b[2 * k]); }
        sh += it.h; sx += it.cx; sy += it.cy;
      }
      text = sb.toString(); h = sh / items.size(); cx = sx / items.size(); cy = sy / items.size();
      ux = items.get(0).ux; uy = items.get(0).uy;
    }
    double yAt(double x) { return cy + (x - cx) * (Math.abs(ux) > 1e-6 ? uy / ux : 0); }
  }

  /** Абзац для перевода: текст и строки, которые он занимает. frame — прямоугольник по
   *  направлению первой строки: центр (x, y), направление (ux, uy), ширина, высота. */
  public static final class Para {
    public final String text; public final List<Row> rows;
    Para(String t, List<Row> r) { text = t; rows = r; }
    /** Уверенность абзаца: средняя уверенность распознавателя по его рамкам с весом длины текста
     *  (tools/ocr_ref.py: para_conf). Ниже CONF_MIN абзац не переводится. */
    public double score() {
      long n = 0; double acc = 0;
      for (Row r : rows) for (Item it : r.items) { n += it.t.length(); acc += it.t.length() * it.score; }
      return n == 0 ? 0 : acc / n;
    }
    public double[] frame() {
      double ux = rows.get(0).ux, uy = rows.get(0).uy, nx = -uy, ny = ux;
      double a0 = Double.MAX_VALUE, a1 = -Double.MAX_VALUE, b0 = Double.MAX_VALUE, b1 = -Double.MAX_VALUE;
      for (Row r : rows) for (Item it : r.items) for (int k = 0; k < 4; k++) {
        double x = it.b[2 * k], y = it.b[2 * k + 1], a = x * ux + y * uy, bb = x * nx + y * ny;
        a0 = Math.min(a0, a); a1 = Math.max(a1, a); b0 = Math.min(b0, bb); b1 = Math.max(b1, bb);
      }
      double am = (a0 + a1) / 2, bm = (b0 + b1) / 2;
      return new double[]{ux * am + nx * bm, uy * am + ny * bm, ux, uy, a1 - a0, b1 - b0};
    }
  }

  /** Слова, на которых фраза не кончается: строка, оборванная на них, продолжается следующей.
   *  Последние — начала составных имён: «construir São / Paulo» иначе переводилось как
   *  «Сан-Франциско» и «Павел». */
  static final Set<String> CONT = new HashSet<>(Arrays.asList((
      "e de da do das dos a o as os com em no na nos nas num numa para por pelo pela pelos pelas " +
      "que ao aos à às um uma ou se sem sob sobre entre até seu sua seus suas é " +
      "está estão sendo foi ser são não mais como quando onde " +
      "santa santo dom dona rio porto belo nova novo").split(" ")));
  static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}_]");
  static final Pattern END = Pattern.compile("[.!?:;]$");

  static String lastWord(String s) {
    String[] w = s.trim().split("\\s+");                  // всегда хотя бы один элемент
    return NON_WORD.matcher(w[w.length - 1].toLowerCase(Locale.ROOT)).replaceAll("");
  }

  /** Рамки → блоки строк (tools/ocr_ref.py: layout — там же объяснение порогов).
   *  Строка: наклон до 15°, середина следующей рамки не дальше полувысоты от средней линии
   *  предыдущей, промежуток до 1,2 высоты (дальше — соседняя колонка). Блок: строки одной
   *  высоты (в 1,5 раза; в 2,5 — после служебного слова), перекрытые по горизонтали, с шагом до
   *  1,8 высоты. Блоки сверху вниз, стоящие рядом по вертикали — слева направо. Цифры штрихкода
   *  в строку с текстом не встают: на этикетке они стояли вровень с последней строкой соседней
   *  колонки и уезжали в перевод посреди фразы («feche a 7 896025804067 tampa»). */
  public static List<List<Row>> layout(List<Item> in) {
    List<Item> items = new ArrayList<>();
    for (Item it : in) if (!it.t.isEmpty()) items.add(it);
    items.sort(Comparator.comparingDouble(it -> it.lx));
    List<Row> rows = new ArrayList<>();
    for (Item it : items) {
      Row best = null; double bd = 0;
      for (Row row : rows) {
        Item a = row.items.get(row.items.size() - 1);
        if (Math.abs(a.ux * it.ux + a.uy * it.uy) < 0.966 || barcode(a.t) != barcode(it.t)) continue;
        double hmax = Math.max(a.h, it.h), hmin = Math.min(a.h, it.h);
        if (hmax > 2 * hmin) continue;
        double vx = it.cx - a.cx, vy = it.cy - a.cy, perp = Math.abs(a.ux * vy - a.uy * vx);
        double gap = a.ux * (it.lx - a.rx) + a.uy * (it.ly - a.ry);
        if (perp < 0.5 * hmax && -0.5 * hmax < gap && gap < 1.2 * hmax && (best == null || perp < bd)) { best = row; bd = perp; }
      }
      if (best == null) { best = new Row(); rows.add(best); }
      best.items.add(it);
    }
    for (Row r : rows) r.close();
    rows.sort(Comparator.comparingDouble(r -> r.cy));
    List<List<Row>> blocks = new ArrayList<>();
    for (Row r : rows) {
      boolean placed = false;
      for (int i = blocks.size() - 1; i >= 0 && !placed; i--) {
        List<Row> bl = blocks.get(i); Row p = bl.get(bl.size() - 1);
        double ov = Math.min(p.x1, r.x1) - Math.max(p.x0, r.x0);
        boolean cont = CONT.contains(lastWord(p.text));
        if (ov <= 0 || Math.max(p.h, r.h) > (cont ? 2.5 : 1.5) * Math.min(p.h, r.h)) continue;
        double xm = (Math.max(p.x0, r.x0) + Math.min(p.x1, r.x1)) / 2, dy = r.yAt(xm) - p.yAt(xm);
        if (0 < dy && dy < 1.8 * Math.max(p.h, r.h)) { bl.add(r); placed = true; }
      }
      if (!placed) { List<Row> bl = new ArrayList<>(); bl.add(r); blocks.add(bl); }
    }
    blocks.sort(Comparator.comparingDouble(bl -> ext(bl)[2]));
    for (int pass = 0; pass < blocks.size(); pass++) {
      boolean swapped = false;
      for (int i = 0; i + 1 < blocks.size(); i++) {
        double[] a = ext(blocks.get(i)), b = ext(blocks.get(i + 1));
        double ov = Math.min(a[3], b[3]) - Math.max(a[2], b[2]);
        if (ov > 0.3 * Math.min(a[3] - a[2], b[3] - b[2]) && b[1] <= a[0] + 1) { Collections.swap(blocks, i, i + 1); swapped = true; }
      }
      if (!swapped) break;
    }
    return blocks;
  }

  /** Охват блока: x0, x1, верх, низ (верх и низ — по середине строк ± полвысоты). */
  static double[] ext(List<Row> bl) {
    double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
    for (Row r : bl) { x0 = Math.min(x0, r.x0); x1 = Math.max(x1, r.x1); y0 = Math.min(y0, r.cy - r.h / 2); y1 = Math.max(y1, r.cy + r.h / 2); }
    return new double[]{x0, x1, y0, y1};
  }

  static final Pattern LETTER = Pattern.compile("\\p{L}"), DIGITS8 = Pattern.compile("\\d(?:[ '’\"]?\\d){7,}"),
      DASH_END = Pattern.compile("\\s[-–—]$");
  /** Цифры под штрихкодом: восемь и больше цифр подряд и ни одной буквы. Между группами цифр
   *  распознаватель ставит пробел или кавычку («"896025'804067"» на смазанном снимке). Телефон
   *  «3242-3300» и цены под это не подходят. */
  static boolean barcode(String t) { return !LETTER.matcher(t).find() && DIGITS8.matcher(t).find(); }
  static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }

  /** Строки блока → фразы для перевода. Склеиваем, только когда обрыв очевиден: перенос со
   *  знаком «-», запятая в конце, строка кончается служебным словом («… ESTREITA E»), открытой
   *  скобкой или тире («(5°C - / 10°C)») или следующая начинается со строчной. Иначе строка —
   *  отдельная фраза: у вывески строки чаще самостоятельны (часы работы, цены, список
   *  направлений), и склейка их портила бы. Цифры штрихкода не склеиваются ни с чем. */
  public static List<Para> paragraphs(List<Row> block) {
    List<String> texts = new ArrayList<>(); List<List<Row>> rows = new ArrayList<>();
    for (Row r : block) {
      String t = r.text;
      if (!texts.isEmpty()) {
        int k = texts.size() - 1; String prev = texts.get(k);
        if (prev.endsWith("-") && prev.length() > 1 && Character.isLetter(prev.charAt(prev.length() - 2))) {
          texts.set(k, prev.substring(0, prev.length() - 1) + t); rows.get(k).add(r); continue;
        }
        if (!barcode(prev) && !barcode(t) && !END.matcher(prev).find() && (prev.endsWith(",") || CONT.contains(lastWord(prev))
            || Character.isLowerCase(t.charAt(0))            // текст строки не пустой: пустые рамки отсеяны в layout
            || count(prev, '(') > count(prev, ')') || DASH_END.matcher(prev).find())) {
          texts.set(k, prev + " " + t); rows.get(k).add(r); continue;
        }
      }
      texts.add(t); List<Row> l = new ArrayList<>(); l.add(r); rows.add(l);
    }
    List<Para> out = new ArrayList<>();
    for (int i = 0; i < texts.size(); i++) out.add(new Para(texts.get(i), rows.get(i)));
    return out;
  }

  /** Строки как на вывеске, по порядку чтения (сверка с расшифровкой в bench/ocr). */
  public static String lines(List<List<Row>> blocks) {
    StringBuilder sb = new StringBuilder();
    for (List<Row> bl : blocks) for (Row r : bl) { if (sb.length() > 0) sb.append('\n'); sb.append(r.text); }
    return sb.toString();
  }

  // ---------- цвета для наложения ----------

  /** Цвета заплатки под перевод: [лево-верх, лево-низ, право-верх, право-низ, текст], ARGB.
   *  Фон — медианы каймы шириной 15 % высоты вокруг рамки по четвертям: заливка идёт градиентом
   *  между ними, иначе на вывеске с неровным светом заплатка видна прямоугольником. Текст —
   *  медиана самой «чернильной» четверти пикселей внутри; при слабом контрасте — чёрный или белый.
   *  frame — как Para.frame(); выборка через пиксель, как в макете tools/ocr_overlay.py. */
  public static int[] colors(int[] argb, int w, int h, double[] f) {
    double cx = f[0], cy = f[1], ux = f[2], uy = f[3], fw = f[4], fh = f[5], nx = -uy, ny = ux;
    double ring = Math.max(2.0, 0.15 * fh), ea = fw / 2 + ring, eb = fh / 2 + ring;
    double rx = Math.abs(ux) * ea + Math.abs(nx) * eb, ry = Math.abs(uy) * ea + Math.abs(ny) * eb;
    int x0 = Math.max(0, (int) Math.floor(cx - rx)), x1 = Math.min(w - 1, (int) Math.ceil(cx + rx));
    int y0 = Math.max(0, (int) Math.floor(cy - ry)), y1 = Math.min(h - 1, (int) Math.ceil(cy + ry));
    int[][] band = new int[3][768], quad = new int[4 * 3][256]; int[] qn = new int[4]; int bn = 0;
    List<Integer> ink = new ArrayList<>();
    for (int y = y0 - (y0 & 1); y <= y1; y += 2) {
      if (y < 0) continue;
      for (int x = x0 - (x0 & 1); x <= x1; x += 2) {
        if (x < 0) continue;
        double dx = x - cx, dy = y - cy, a = dx * ux + dy * uy, b = dx * nx + dy * ny;
        boolean in = Math.abs(a) <= fw / 2 && Math.abs(b) <= fh / 2;
        if (in) { ink.add(argb[y * w + x]); continue; }
        if (Math.abs(a) > ea || Math.abs(b) > eb) continue;
        int p = argb[y * w + x]; bn++;
        for (int c = 0; c < 3; c++) band[c][(p >> (16 - 8 * c)) & 255]++;
        if (a == 0 || b == 0) continue;                         // np.sign(0) — ни в одну четверть
        int q = (a < 0 ? 0 : 2) + (b < 0 ? 0 : 1); qn[q]++;
        for (int c = 0; c < 3; c++) quad[q * 3 + c][(p >> (16 - 8 * c)) & 255]++;
      }
    }
    int[] bg = bn == 0 ? new int[]{255, 255, 255} : new int[]{median(band[0], bn), median(band[1], bn), median(band[2], bn)};
    int[] out = new int[5];
    for (int q = 0; q < 4; q++) {
      int[] c = qn[q] > 3 ? new int[]{median(quad[q * 3], qn[q]), median(quad[q * 3 + 1], qn[q]), median(quad[q * 3 + 2], qn[q])} : bg;
      out[q] = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2];
    }
    int[] fg = {0, 0, 0};
    if (ink.size() > 10) {
      double[] d = new double[ink.size()];
      for (int i = 0; i < d.length; i++) { int p = ink.get(i); d[i] = Math.sqrt(sq(((p >> 16) & 255) - bg[0]) + sq(((p >> 8) & 255) - bg[1]) + sq((p & 255) - bg[2])); }
      double[] s = d.clone(); Arrays.sort(s);
      double q75 = quantile(s, 0.75);
      int[][] hc = new int[3][256]; int hn = 0;
      for (int i = 0; i < d.length; i++) if (d[i] >= q75) { int p = ink.get(i); hn++; for (int c = 0; c < 3; c++) hc[c][(p >> (16 - 8 * c)) & 255]++; }
      fg = new int[]{median(hc[0], hn), median(hc[1], hn), median(hc[2], hn)};
    }
    if (Math.abs(lum(fg) - lum(bg)) < 90) fg = lum(bg) > 128 ? new int[]{0, 0, 0} : new int[]{255, 255, 255};
    out[4] = 0xFF000000 | (fg[0] << 16) | (fg[1] << 8) | fg[2];
    return out;
  }

  static double sq(double v) { return v * v; }
  static double lum(int[] c) { return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]; }
  /** Медиана по гистограмме 0..255 (для чётного числа — среднее двух средних, округление вниз). */
  static int median(int[] hist, int n) {
    int lo = (n - 1) / 2, hi = n / 2, acc = 0, a = -1, b = -1;
    for (int v = 0; v < hist.length && b < 0; v++) {
      acc += hist[v];
      if (a < 0 && acc > lo) a = v;
      if (acc > hi) b = v;
    }
    return (a + b) / 2;
  }
  /** Квантиль как np.quantile (линейная интерполяция) по отсортированному массиву. */
  static double quantile(double[] s, double q) {
    double pos = q * (s.length - 1); int i = (int) Math.floor(pos); double f = pos - i;
    return i + 1 < s.length ? s[i] * (1 - f) + s[i + 1] * f : s[i];
  }
}
