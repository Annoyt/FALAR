package dev.agenttranslator;

import java.util.Arrays;
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

    // M9: GCC-PHAT — задержка правого по шуму с гулом: гул на 300 Гц в обоих каналах одинаков (как вентилятор), голос —
    // шум, на правом на 7 отсчётов позже; простая корреляция тянет к нулю, PHAT — нет.
    int m = 32000; double[] v = noise(m, 4), hum = new double[m];
    for (int i = 0; i < m; i++) hum[i] = 3 * Math.sin(2 * Math.PI * 300 * i / 16000.0);
    float[] fl = new float[m], fr = new float[m];
    for (int i = 0; i < m; i++) { fl[i] = (float) (0.05 * (v[i] + hum[i])); int j = i - 7; fr[i] = (float) (0.05 * ((j >= 0 ? v[j] : 0) + hum[i])); }
    ok(MicPair.gccPhat(fl, fr, 0, m, 16) == 7, "M9 GCC-PHAT: +7 под гулом: " + MicPair.gccPhat(fl, fr, 0, m, 16));
    ok(MicPair.gccPhat(fr, fl, 0, m, 16) == -7, "M9 каналы наоборот — −7: " + MicPair.gccPhat(fr, fl, 0, m, 16));
    ok(MicPair.gccPhat(fl, fr, 0, 30, 16) == 0, "M9 отрезок короче 64 — ноль");

    // M10: задержка куска — по громкому отрезку: тихий шум с задержкой −3, громкий голос с задержкой +5 посередине.
    int q2 = 16000 * 6; double[] w1 = noise(q2, 5), w2 = noise(q2, 6);
    float[] gl = new float[q2], gr = new float[q2];
    for (int i = 0; i < q2; i++) {
      boolean loud = i >= 16000 * 2 && i < 16000 * 4;
      int j = i - (loud ? 5 : -3);
      double src = loud ? 0.3 * w1[i] : 0.01 * w2[i], srcR = j >= 0 && j < q2 ? (loud ? 0.3 * w1[j] : 0.01 * w2[j]) : 0;
      gl[i] = (float) src; gr[i] = (float) srcR;
    }
    ok(MicPair.delay(gl, gr, 16000) == 5, "M10 задержка куска — по громкому отрезку: " + MicPair.delay(gl, gr, 16000));
    float[] sl = Arrays.copyOf(gl, 16000), sr = Arrays.copyOf(gr, 16000);
    ok(MicPair.delay(sl, sr, 16000) == -3, "M10 короткий кусок — целиком: " + MicPair.delay(sl, sr, 16000));

    // M11: выровненная сумма — правый сдвинут на k: сумма совпадает с удвоенным левым, за краем — половина.
    float[] al = {1, 2, 3, 4, 5}, ar2 = {0, 0, 1, 2, 3};
    float[] sum = MicPair.aligned(al, ar2, 2);
    ok(sum[0] == 1 && sum[1] == 2 && sum[2] == 3 && sum[3] == 2 && sum[4] == 2.5f, "M11 (L[t] + R[t+2]) / 2: " + Arrays.toString(sum));
    ok(MicPair.aligned(al, ar2, 0)[2] == 2f && MicPair.aligned(al, ar2, -1)[0] == 0.5f, "M11 без сдвига и с отрицательным");

    // M12: Side — кадры по самому объекту, вытеснение старых, нет кадра — null.
    MicPair.Side side = new MicPair.Side(3);
    float[] a1 = {1, 1}, a2 = {2, 2}, a3 = {3, 3}, a4 = {4, 4};
    side.put(a1, new float[]{1, 0}, new float[]{0, 1}); side.put(a2, new float[]{2, 0}, new float[]{0, 2}); side.put(a3, new float[]{3, 0}, new float[]{0, 3});
    float[][] got = side.piece(new float[][]{a2, a3});
    ok(got != null && Arrays.equals(got[0], new float[]{2, 0, 3, 0}) && Arrays.equals(got[1], new float[]{0, 2, 0, 3}), "M12 L и R куска по кадрам");
    ok(side.piece(new float[][]{new float[]{2, 2}}) == null, "M12 равный по содержимому, но другой кадр — не найден");
    side.put(a4, new float[]{4, 0}, new float[]{0, 4});
    ok(side.size() == 3 && side.piece(new float[][]{a1}) == null && side.piece(new float[][]{a4}) != null, "M12 старый кадр вытеснен");
    ok(side.piece(new float[][]{a3, new float[]{9}}) == null && side.piece(null) == null, "M12 хоть одного кадра нет — null");

    // M13: Detect — копия одного микрофона, два разных, мало отсчётов; гул в обоих не делает их «одним».
    MicPair.Detect d1 = new MicPair.Detect(16000); float[] cp = new float[512];
    for (int k = 0; k < 40; k++) { for (int i = 0; i < 512; i++) cp[i] = (float) (0.01 * v[k * 512 + i]); d1.add(cp, cp, 512); }
    ok(Boolean.FALSE.equals(d1.verdict()) && d1.sameShare() == 1.0, "M13 копия: " + d1.verdict() + ", " + d1.sameShare());
    MicPair.Detect d2 = new MicPair.Detect(16000); float[] x1 = new float[512], x2 = new float[512];
    for (int k = 0; k < 40; k++) { for (int i = 0; i < 512; i++) { int t = k * 512 + i; x1[i] = (float) (0.01 * (v[t] + hum[t])); x2[i] = (float) (0.01 * (w2[t] + hum[t])); } d2.add(x1, x2, 512); }
    ok(Boolean.TRUE.equals(d2.verdict()), "M13 два микрофона под общим гулом: корр " + d2.corr());
    MicPair.Detect d3 = new MicPair.Detect(16000); d3.add(x1, x2, 512);
    ok(d3.verdict() == null, "M13 мало отсчётов — решения нет");

    System.out.println(fails == 0 ? "MicPairTest: " + checks + " проверок, все прошли" : "MicPairTest: провалов " + fails + " из " + checks);
    return fails;
  }

  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
