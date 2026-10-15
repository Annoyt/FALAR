package dev.agenttranslator;

import java.util.Random;

/** Два канала записи (MicPair): копия одного микрофона или два разных, уровни, задержка правого канала относительно
 *  левого. Сигналы — шум с известным сдвигом и ослаблением, как звук динамика на двух микрофонах.
 *  Запуск: bash bench/apk/test.sh. */
public class MicPairTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }

  static double[] noise(int n, long seed) { Random r = new Random(seed); double[] x = new double[n]; for (int i = 0; i < n; i++) x[i] = r.nextGaussian(); return x; }
  /** Стерео из двух рядов: L = a, R = b, амплитуда amp (доля полной шкалы), плюс постоянная составляющая dc. */
  static short[] lr(double[] a, double[] b, double ampL, double ampR, int dc) {
    short[] x = new short[2 * a.length];
    for (int i = 0; i < a.length; i++) {
      x[2 * i] = (short) Math.max(-32768, Math.min(32767, Math.round(a[i] * ampL * 32767) + dc));
      x[2 * i + 1] = (short) Math.max(-32768, Math.min(32767, Math.round(b[i] * ampR * 32767) + dc));
    }
    return x;
  }
  /** b[i] = a[i - d]: правый канал слышит тот же звук на d кадров позже (d < 0 — раньше). */
  static double[] shift(double[] a, int d) { double[] b = new double[a.length]; for (int i = 0; i < a.length; i++) { int j = i - d; b[i] = j >= 0 && j < a.length ? a[j] : 0; } return b; }

  public static int run() {
    fails = 0; checks = 0;
    int n = 9600;
    double[] a = noise(n, 1);

    // M1: правый — тот же шум на 5 кадров позже и на 6 дБ тише: два разных микрофона, дальний — правый.
    MicPair.Stats s = MicPair.stats(lr(a, shift(a, 5), 0.1, 0.05, 0), 100, n - 100, 16);
    ok(s.lag == 5 && s.peak > 0.95, "M1 задержка +5 кадров, пик высокий: " + s.lag + ", " + s.peak);
    ok(Math.abs((s.dbL - s.dbR) - 6.02) < 0.1, "M1 разница уровней 6 дБ: " + (s.dbL - s.dbR));
    ok(Math.abs(s.dbL - 20 * Math.log10(0.1)) < 0.2, "M1 уровень левого −20 dBFS: " + s.dbL);
    ok(s.same < 0.05, "M1 каналы не совпадают: " + s.same);

    // M2: правый раньше левого на 7 кадров — знак отрицательный.
    s = MicPair.stats(lr(a, shift(a, -7), 0.1, 0.1, 0), 100, n - 100, 16);
    ok(s.lag == -7 && s.peak > 0.95, "M2 задержка −7 кадров: " + s.lag + ", " + s.peak);

    // M3: оба канала — одна и та же копия (моно, разложенное на два канала): совпадение 1, задержка 0.
    s = MicPair.stats(lr(a, a, 0.1, 0.1, 0), 0, n, 16);
    ok(s.same == 1.0 && s.lag == 0 && s.peak > 0.999, "M3 копия одного микрофона: " + s.same + ", " + s.lag + ", " + s.peak);

    // M4: независимый шум — пик корреляции низкий, совпадений почти нет.
    s = MicPair.stats(lr(a, noise(n, 2), 0.1, 0.1, 0), 0, n, 16);
    ok(s.peak < 0.1 && s.same < 0.05, "M4 разные звуки: пик " + s.peak + ", совпадений " + s.same);

    // M5: постоянная составляющая не мешает ни уровню, ни задержке.
    s = MicPair.stats(lr(a, shift(a, 3), 0.05, 0.05, 2000), 100, n - 100, 16);
    ok(s.lag == 3 && Math.abs(s.dbL - 20 * Math.log10(0.05)) < 0.2, "M5 со сдвигом нуля: задержка " + s.lag + ", уровень " + s.dbL);

    // M6: задержка за пределом поиска — берётся лучшая в пределе, пик ниже.
    s = MicPair.stats(lr(a, shift(a, 30), 0.1, 0.1, 0), 100, n - 100, 16);
    ok(Math.abs(s.lag) <= 16 && s.peak < 0.5, "M6 задержка 30 при пределе 16 — не выдумывается: " + s.lag + ", " + s.peak);

    // M7: самое громкое окно — где вставка шума; уровень одного канала из стерео.
    double[] q = new double[n]; double[] burst = noise(1600, 3);
    for (int i = 0; i < 1600; i++) q[4000 + i] = burst[i];
    for (int i = 0; i < n; i++) q[i] += 0.01 * a[i];
    short[] x = lr(q, q, 0.2, 0.2, 0);
    int at = MicPair.loudest(x, 2, 0, n, 1200, 40);
    ok(at >= 4000 && at + 1200 <= 5600, "M7 громкое окно внутри вставки 4000–5600: " + at);
    ok(Math.abs(MicPair.db(x, 2, 1, 4000, 5600) - 20 * Math.log10(0.2)) < 0.3, "M7 уровень правого во вставке −14 dBFS: " + MicPair.db(x, 2, 1, 4000, 5600));
    ok(MicPair.db(x, 2, 0, 0, 0) == -120 && MicPair.stats(x, 5, 5, 4).dbL == -120, "M7 пустой отрезок — −120 dBFS");

    // M8: моно — уровень канала 0 при одном канале.
    short[] mono = new short[n]; for (int i = 0; i < n; i++) mono[i] = (short) Math.round(a[i] * 0.1 * 32767);
    ok(Math.abs(MicPair.db(mono, 1, 0, 0, n) - 20 * Math.log10(0.1)) < 0.2, "M8 уровень моно −20 dBFS: " + MicPair.db(mono, 1, 0, 0, n));

    System.out.println(fails == 0 ? "MicPairTest: " + checks + " проверок, все прошли" : "MicPairTest: провалов " + fails + " из " + checks);
    return fails;
  }

  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
