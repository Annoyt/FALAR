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
 *    какой ближе к источнику, по величине — разница путей (343 м/с, 0,1 мс ≈ 3,4 см). */
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
