package dev.agenttranslator;

/** Чувствительность микрофона: цифровое усиление с ограничителем вместо среза, и счёт того,
 *  как слышно.
 *
 *  Раньше усиленный звук срезался по ±0,99: при высокой чувствительности громкая речь теряла
 *  вершины волны, и распознавание это слышит. Ограничитель вместо этого убавляет усиление ровно
 *  там, где сигнал упёрся бы в потолок, и плавно возвращает его: форма волны остаётся, меняется
 *  только громкость. Прежний срез оставлен переключателем стенда — для сравнения на тех же записях.
 *
 *  Счётчики (Stats) — про вход до усиления: пик, перегруз самого микрофона (вход у края шкалы —
 *  тут никакая чувствительность не поможет) и сколько отсчётов придержал ограничитель или срезал
 *  срез. Без Android: проверяется на столе (GainTest). */
final class Gain {
  /** Потолок после усиления (−0,9 dBFS) и край шкалы, у которого вход считается перегруженным. */
  static final float CEIL = 0.9f, OVER = 0.99f;
  /** Возврат усиления после пика: за это время множитель проходит 63 % пути назад к единице. */
  static final double RELEASE_MS = 150;

  final float release;
  /** Чувствительность, дБ. 0 — звук не трогается вовсе, как было. */
  volatile double db = 0;
  /** false — прежний срез по ±0,99 (только стенд). */
  volatile boolean limit = true;
  /** Текущий множитель ограничителя, ≤ 1; живёт между кадрами. */
  float red = 1;

  Gain(int sr) { release = (float) (1 - Math.exp(-1000.0 / (RELEASE_MS * sr))); }

  /** Усилить x[0..n) на месте; счётчики входа — в st, если он есть. */
  void apply(float[] x, int n, Stats st) {
    final double g = Math.pow(10, db / 20);
    final boolean on = db != 0;
    for (int k = 0; k < n; k++) {
      final float v = x[k];
      if (st != null) st.in(v);
      if (!on) continue;
      double y = v * g;
      if (limit) {
        final double a = Math.abs(y);
        if (a * red > CEIL) { red = (float) (CEIL / a); if (st != null) st.limited++; }
        y *= red;
        red += (1 - red) * release;
      } else if (y > 0.99 || y < -0.99) {
        y = y > 0 ? 0.99 : -0.99;
        if (st != null) st.cut++;
      }
      x[k] = (float) y;
    }
  }

  /** Что было на входе за кусок звука. */
  static final class Stats {
    long n, over, limited, cut; float peak;
    void in(float v) { n++; final float a = Math.abs(v); if (a > peak) peak = a; if (a >= OVER) over++; }
    void add(Stats o) { n += o.n; over += o.over; limited += o.limited; cut += o.cut; peak = Math.max(peak, o.peak); }
    void clear() { n = over = limited = cut = 0; peak = 0; }
    /** Доли отсчётов, в процентах. */
    double overPct() { return pct(over); }
    double limitedPct() { return pct(limited); }
    double cutPct() { return pct(cut); }
    double pct(long c) { return n == 0 ? 0 : 100.0 * c / n; }
  }

  /** Речь и фон по самой фразе, dBFS: фон — самые тихие 10 % кадров по 32 мс (в фразу входит
   *  подпор начала и хвост, тишина в ней есть), речь — средняя мощность кадров, что громче фона
   *  на 10 дБ и больше. Одинаково для удержания и для прослушивания. Речи над фоном нет — NaN. */
  static double[] speechFloor(float[] s) {
    final int f = 512, m = s.length / f;
    if (m == 0) return new double[]{Double.NaN, Double.NaN};
    double[] p = new double[m];
    for (int j = 0; j < m; j++) { double a = 0; for (int k = j * f; k < (j + 1) * f; k++) a += s[k] * (double) s[k]; p[j] = a / f; }
    double[] q = p.clone(); java.util.Arrays.sort(q);
    double floor = q[(int) ((m - 1) * 0.1)], sum = 0; int c = 0;
    for (double x : p) if (x >= floor * 10) { sum += x; c++; }
    return new double[]{c == 0 ? Double.NaN : 10 * Math.log10(Math.max(1e-18, sum / c)), 10 * Math.log10(Math.max(1e-18, floor))};
  }

  static double db(double x) { return 20 * Math.log10(Math.max(1e-9, x)); }
}
