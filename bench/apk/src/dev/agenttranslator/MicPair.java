package dev.agenttranslator;

/** Два канала записи: копия одного микрофона или два разных, и как звук из одной точки приходит на каждый —
 *  заготовка автоопределения микрофонов (владелец 15.10: «можно ли использовать второй микрофон для улучшения
 *  качества звука», «микрофон надо как-то автоопределять»). Телефоны разные: какой канал какой микрофон, у
 *  каждого свой порядок, и Android сообщает о нём не везде — поэтому проверяем и по звуку.
 *
 *  Чистая Java: на вход — отсчёты 16 бит каналами через один (L, R, L, R…), как их отдаёт AudioRecord в стерео.
 *  Проверка на столе — bench/apk/test/MicPairTest.java; на телефоне — стенд --es mictest
 *  (TranslatorService.micStand); тот же счёт на ПК по записям стенда — tools/mic_probe.py.
 *
 *  На отрезке [from, to) кадров считается:
 *  - уровень каждого канала, dBFS;
 *  - доля кадров, где каналы совпадают до отсчёта: 1 — это копия одного микрофона;
 *  - задержка правого канала относительно левого (взаимная корреляция без постоянной составляющей, в пределах
 *    ±maxLag кадров) и высота её пика. Звук из одной точки приходит на дальний микрофон позже: по знаку видно,
 *    какой ближе к источнику, по величине — разница путей (343 м/с, 0,1 мс ≈ 3,4 см).
 *
 *  Слушание двумя микрофонами (results/2026-10-15-dual-mic.md, шаг 3: на шести записях комнаты WER 12,9 → 8,5 %):
 *  телефон отдаёт приложению моно как (L + R) / 2 — голос с торца приходит на микрофоны с разницей до 0,44 мс, и
 *  простое сложение гасит часть звука. Нарезка по-прежнему идёт по среднему, а распознаватель получает сумму,
 *  выровненную по задержке своего куска (das): gccPhat по самому громкому отрезку куска (до DAS_WIN_S), aligned.
 *  Side — L и R кадров слушания до нарезки; Detect — два ли это микрофона, по первым секундам фона: у копии одного
 *  микрофона каналы совпадают. Близнец на ПК — tools/dualmic_eval.py (вариант das). */
public final class MicPair {
  private MicPair() {}

  public static final class Stats {
    /** Уровни каналов, dBFS. */
    public double dbL, dbR;
    /** Доля кадров с одинаковыми отсчётами в обоих каналах. */
    public double same;
    /** Задержка R относительно L в кадрах: > 0 — правый слышит позже; пик — нормированная корреляция на ней. */
    public int lag;
    public double peak;
  }

  static final double FS = 32768.0 * 32768.0;

  public static Stats stats(short[] lr, int from, int to, int maxLag) {
    Stats s = new Stats();
    int n = Math.max(0, to - from);
    if (n == 0) { s.dbL = s.dbR = -120; return s; }
    double mL = 0, mR = 0; int same = 0;
    for (int t = from; t < to; t++) { short l = lr[2 * t], r = lr[2 * t + 1]; mL += l; mR += r; if (l == r) same++; }
    mL /= n; mR /= n;
    double eL = 0, eR = 0;
    for (int t = from; t < to; t++) { double l = lr[2 * t] - mL, r = lr[2 * t + 1] - mR; eL += l * l; eR += r * r; }
    s.dbL = db(eL, n); s.dbR = db(eR, n); s.same = same / (double) n;
    double norm = Math.sqrt(eL * eR);
    double best = -2; int bestK = 0;
    for (int k = -maxLag; k <= maxLag; k++) {
      int a = Math.max(from, from - k), b = Math.min(to, to - k);
      double c = 0;
      for (int t = a; t < b; t++) c += (lr[2 * t] - mL) * (lr[2 * (t + k) + 1] - mR);
      c = norm > 0 ? c / norm : 0;
      if (c > best) { best = c; bestK = k; }
    }
    s.lag = bestK; s.peak = best;
    return s;
  }

  /** Уровень одного канала ch из chans на [from, to), dBFS. */
  public static double db(short[] x, int chans, int ch, int from, int to) {
    int n = Math.max(0, to - from);
    if (n == 0) return -120;
    double m = 0;
    for (int t = from; t < to; t++) m += x[chans * t + ch];
    m /= n;
    double e = 0;
    for (int t = from; t < to; t++) { double v = x[chans * t + ch] - m; e += v * v; }
    return db(e, n);
  }

  static double db(double sumSq, int n) { return 10 * Math.log10(Math.max(sumSq / n / FS, 1e-12)); }

  /** Окно для задержки куска, с: дольше — берётся самый громкий отрезок такой длины (говорящий за фразу не
   *  перемещается, а память и время FFT растут с длиной). Шаг поиска — DAS_STEP_S. */
  public static final double DAS_WIN_S = 2.0, DAS_STEP_S = 0.25;
  /** Предел задержки, отсчётов при 16 кГц: 1 мс — 34 см, у телефона между микрофонами 15 см (0,44 мс). */
  public static final int DAS_MAX_LAG = 16;

  /** Задержка R относительно L в отсчётах (> 0 — правый слышит позже) по GCC-PHAT на отрезке [from, to), в пределах
   *  ±maxLag: взаимный спектр, нормированный на модуль, — все частоты с одним весом, иначе низкий гул (вентилятор,
   *  200–500 Гц) размывает пик. */
  public static int gccPhat(float[] l, float[] r, int from, int to, int maxLag) {
    int len = to - from;
    if (len < 64) return 0;
    int n = Integer.highestOneBit(Math.max(1, 2 * len - 1)) << 1;
    double[] ar = new double[n], ai = new double[n], br = new double[n], bi = new double[n];
    double ml = 0, mr = 0;
    for (int t = from; t < to; t++) { ml += l[t]; mr += r[t]; }
    ml /= len; mr /= len;
    for (int t = 0; t < len; t++) { ar[t] = l[from + t] - ml; br[t] = r[from + t] - mr; }
    fft(ar, ai, false); fft(br, bi, false);
    for (int k = 0; k < n; k++) {                       // conj(L)·R / |conj(L)·R|
      double gr = ar[k] * br[k] + ai[k] * bi[k], gi = ar[k] * bi[k] - ai[k] * br[k];
      double m = Math.sqrt(gr * gr + gi * gi);
      if (m < 1e-20) { ar[k] = 0; ai[k] = 0; } else { ar[k] = gr / m; ai[k] = gi / m; }
    }
    fft(ar, ai, true);
    int best = 0; double bv = Double.NEGATIVE_INFINITY;
    for (int k = -maxLag; k <= maxLag; k++) { double v = ar[(k + n) % n]; if (v > bv) { bv = v; best = k; } }
    return best;
  }

  /** Задержка куска: по самому громкому отрезку DAS_WIN_S суммы каналов (весь кусок, если он короче полутора окон). */
  public static int delay(float[] l, float[] r, int rate) {
    int win = (int) (DAS_WIN_S * rate), n = Math.min(l.length, r.length), from = 0;
    if (n > win * 3 / 2) {
      int step = Math.max(1, (int) (DAS_STEP_S * rate)); double bestE = -1;
      for (int s = 0; s + win <= n; s += step) {
        double e = 0;
        for (int t = s; t < s + win; t++) { double v = l[t] + r[t]; e += v * v; }
        if (e > bestE) { bestE = e; from = s; }
      }
      return gccPhat(l, r, from, from + win, DAS_MAX_LAG * rate / 16000);
    }
    return gccPhat(l, r, 0, n, DAS_MAX_LAG * rate / 16000);
  }

  /** (L[t] + R[t + k]) / 2 — сумма, выровненная по задержке k (R позже L на k отсчётов); за краем R — ноль. */
  public static float[] aligned(float[] l, float[] r, int k) {
    float[] a = new float[l.length];
    for (int t = 0; t < l.length; t++) { int u = t + k; a[t] = (l[t] + (u >= 0 && u < r.length ? r[u] : 0f)) * 0.5f; }
    return a;
  }

  /** Быстрое преобразование Фурье на месте, длина — степень двойки; inv — обратное (без деления на n: нужен только
   *  максимум). */
  static void fft(double[] re, double[] im, boolean inv) {
    int n = re.length;
    for (int i = 1, j = 0; i < n; i++) {
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
    }
    double[] cs = new double[n / 2], sn = new double[n / 2];
    for (int k = 0; k < n / 2; k++) { double a = (inv ? 2 : -2) * Math.PI * k / n; cs[k] = Math.cos(a); sn[k] = Math.sin(a); }
    for (int len = 2; len <= n; len <<= 1) {
      int step = n / len, half = len / 2;
      for (int i = 0; i < n; i += len)
        for (int k = 0; k < half; k++) {
          double wr = cs[k * step], wi = sn[k * step];
          int a = i + k, b = a + half;
          double xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
        }
    }
  }

  /** L и R кадров слушания до усиления: ключ — сам кадр моно (тот же объект идёт в очередь захвата и в нарезку,
   *  усиление правит его на месте). Последние cap кадров; кусок, хоть один кадр которого не нашёлся (подача моно,
   *  поток с диска, вытеснен), — null, и распознаётся моно, как раньше. */
  static final class Side {
    final int cap;
    final java.util.IdentityHashMap<float[], float[][]> map = new java.util.IdentityHashMap<>();
    final java.util.ArrayDeque<float[]> order = new java.util.ArrayDeque<>();
    Side(int cap) { this.cap = cap; }
    synchronized void put(float[] mono, float[] l, float[] r) {
      if (map.put(mono, new float[][]{l, r}) == null) order.addLast(mono);
      while (order.size() > cap) map.remove(order.removeFirst());
    }
    synchronized void clear() { map.clear(); order.clear(); }
    synchronized int size() { return map.size(); }
    /** L и R куска из его кадров, по порядку; null — если хоть одного нет. */
    synchronized float[][] piece(float[][] frames) {
      if (frames == null || frames.length == 0) return null;
      int len = 0;
      for (float[] f : frames) { float[][] lr = map.get(f); if (lr == null || lr[0].length != f.length) return null; len += f.length; }
      float[] l = new float[len], r = new float[len]; int o = 0;
      for (float[] f : frames) { float[][] lr = map.get(f); System.arraycopy(lr[0], 0, l, o, f.length); System.arraycopy(lr[1], 0, r, o, f.length); o += f.length; }
      return new float[][]{l, r};
    }
  }

  /** Два ли это микрофона: по первым need отсчётам — доля одинаковых и корреляция первых разностей (они глушат гул, в
   *  котором и два разных микрофона похожи). У копии одного микрофона (UNPROCESSED на Redmi) — 100 % одинаковых; у
   *  двух разных фон на 1–4 кГц почти не связан (0,16–0,20, шаг 1). Решение — один раз, verdict(): null — пока мало. */
  static final class Detect {
    final int need; int n, same; double sll, srr, slr; float pl, pr; boolean first = true;
    Detect(int need) { this.need = need; }
    void add(float[] l, float[] r, int len) {
      for (int k = 0; k < len && n < need; k++, n++) {
        if (l[k] == r[k]) same++;
        if (!first) { double dl = l[k] - pl, dr = r[k] - pr; sll += dl * dl; srr += dr * dr; slr += dl * dr; }
        first = false; pl = l[k]; pr = r[k];
      }
    }
    boolean done() { return n >= need; }
    double sameShare() { return n == 0 ? 0 : same / (double) n; }
    double corr() { return sll > 0 && srr > 0 ? slr / Math.sqrt(sll * srr) : 1; }
    /** true — два микрофона, false — копия одного (или каналы почти одинаковы), null — отсчётов ещё мало. */
    Boolean verdict() { return !done() ? null : sameShare() < 0.5 && corr() < 0.9; }
  }

  /** Каналы WAV-файла (PCM 16 бит или float 32) в долях полной шкалы и частота: {каналы…}; rate[0] — частота.
   *  null — не WAV или такой формат не разбираем. Для подачи стереозаписей комнаты стенду (--es feedwav). */
  public static float[][] readWav(java.io.File f, int[] rate) throws java.io.IOException {
    byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    if (b.length < 12 || bb.getInt(0) != 0x46464952 || bb.getInt(8) != 0x45564157) return null;   // «RIFF», «WAVE»
    int fmt = -1, ch = 0, bits = 0, pos = 12, data = -1, dlen = 0;
    while (pos + 8 <= b.length) {
      int id = bb.getInt(pos), len = bb.getInt(pos + 4);
      if (id == 0x20746d66) { fmt = bb.getShort(pos + 8) & 0xffff; ch = bb.getShort(pos + 10); if (rate != null) rate[0] = bb.getInt(pos + 12); bits = bb.getShort(pos + 22); }
      else if (id == 0x61746164) { data = pos + 8; dlen = Math.min(len, b.length - data); break; }
      pos += 8 + len + (len & 1);
    }
    if (data < 0 || ch < 1) return null;
    int bps = bits / 8, n = dlen / (bps * ch);
    if (!(fmt == 1 && bits == 16) && !(fmt == 3 && bits == 32)) return null;
    float[][] out = new float[ch][n];
    for (int i = 0; i < n; i++)
      for (int c = 0; c < ch; c++) {
        int at = data + (i * ch + c) * bps;
        out[c][i] = fmt == 1 ? bb.getShort(at) / 32768f : bb.getFloat(at);
      }
    return out;
  }

  /** Начало самого громкого окна win кадров на [from, to) с шагом step, по всем каналам вместе: где в записи
   *  звук динамика (задержку вывода заранее не знаем). Окно не выходит за to. */
  public static int loudest(short[] x, int chans, int from, int to, int win, int step) {
    int best = from; double bestE = -1;
    for (int s = from; s + win <= to; s += step) {
      double e = 0;
      for (int t = s; t < s + win; t++) for (int c = 0; c < chans; c++) { double v = x[chans * t + c]; e += v * v; }
      if (e > bestE) { bestE = e; best = s; }
    }
    return best;
  }
}
