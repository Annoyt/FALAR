package dev.agenttranslator;

import java.util.*;

/** Wiener — дешёвый шумодав для детектора речи в тишине: тот же счёт, что у близнеца в
 *  tools/vad_denoise_eval.py (числа W1 посчитаны им), длина и задержка, что делает с шумом и тоном.
 *  Запуск: bash bench/apk/test.sh. */
public class WienerTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void near(double a, double b, double tol, String what) { ok(Math.abs(a - b) <= tol, what + ": " + a + " ≠ " + b); }
  static float[] runAll(Wiener w, float[] x, int chunk) {
    float[] y = new float[x.length];
    for (int i = 0; i + chunk <= x.length; i += chunk) System.arraycopy(w.run(Arrays.copyOfRange(x, i, i + chunk)), 0, y, i, chunk);
    return y;
  }
  static double rms(float[] x, int from) { double s = 0; for (int i = from; i < x.length; i++) s += x[i] * (double) x[i]; return Math.sqrt(s / (x.length - from)); }

  public static int run() {
    fails = 0; checks = 0;

    // W1: сверка с Python — синус 440 Гц и пила, середина втрое громче, кадры по 512
    float[] x = new float[4096];
    for (int n = 0; n < 4096; n++) {
      double v = 0.1 * Math.sin(2 * Math.PI * 440 * n / 16000.0) + 0.02 * (((n * 7919L + 13) % 1000) / 1000.0 - 0.5);
      if (n >= 2048 && n < 3072) v *= 3;
      x[n] = (float) v;
    }
    float[] y = runAll(new Wiener(), x, 512);
    int[] at = {300, 1000, 2047, 2500, 3100, 4000};
    double[] py = {0.045052751898765564, 0.0029801959171891212, 0.010881958529353142, -0.07013707607984543, 0.19633060693740845, -0.008945330046117306};
    for (int i = 0; i < at.length; i++) near(y[at[i]], py[i], 1e-6, "W1 отсчёт " + at[i] + " как в Python");
    double e = 0; for (float v : y) e += v * (double) v;
    near(e, 14.256346558357377, 1e-4, "W1 энергия выхода как в Python");

    // W2: кусками по 256 — то же самое, что по 512 (состояние держится между вызовами)
    float[] y2 = runAll(new Wiener(), x, 256);
    double d = 0; for (int i = 0; i < y.length; i++) d = Math.max(d, Math.abs(y[i] - y2[i]));
    ok(d < 1e-7, "W2 куски по 256 = куски по 512 (разница " + d + ")");

    // W3: выход той же длины; первые 256 отсчётов — задержка, там почти тишина
    ok(new Wiener().run(new float[512]).length == 512, "W3 длина выхода = длина входа");
    float[] imp = new float[1024]; imp[100] = 1;
    float[] yi = new Wiener().run(imp);
    int peak = 0; for (int i = 1; i < yi.length; i++) if (Math.abs(yi[i]) > Math.abs(yi[peak])) peak = i;
    ok(peak >= 256, "W3 задержка 256 отсчётов: пик на " + peak);

    // W4: белый шум — после разгона глушится на 15 дБ и больше; стационарный тон тоже («шум» для слежения)
    Random rnd = new Random(7); float[] wn = new float[16000 * 4];
    for (int i = 0; i < wn.length; i++) wn[i] = (float) (0.01 * rnd.nextGaussian());
    double att = 20 * Math.log10(rms(runAll(new Wiener(), wn, 512), 32000) / rms(wn, 32000));
    ok(att < -15, "W4 белый шум глушится: " + String.format(Locale.ROOT, "%.1f дБ", att));

    // W5: речь поверх тихого шума проходит: громкий всплеск после двух секунд шума теряет не больше 6 дБ
    float[] sp = new float[16000 * 3];
    for (int i = 0; i < sp.length; i++) sp[i] = (float) (0.002 * rnd.nextGaussian() + (i >= 32000 && i < 40000 ? 0.2 * Math.sin(2 * Math.PI * 300 * i / 16000.0) * Math.sin(Math.PI * (i - 32000) / 8000.0) : 0));
    float[] ys = runAll(new Wiener(), sp, 512);
    double in2 = 0, out2 = 0; for (int i = 32256; i < 40256; i++) { out2 += ys[i] * (double) ys[i]; in2 += sp[i - 256] * (double) sp[i - 256]; }
    ok(10 * Math.log10(out2 / in2) > -6, "W5 всплеск поверх шума проходит: " + String.format(Locale.ROOT, "%.1f дБ", 10 * Math.log10(out2 / in2)));

    // W6: сброс — как новый
    Wiener w = new Wiener(); runAll(w, wn, 512); w.reset();
    float[] y3 = runAll(w, x, 512);
    d = 0; for (int i = 0; i < y.length; i++) d = Math.max(d, Math.abs(y[i] - y3[i]));
    ok(d < 1e-7, "W6 после сброса — как новый (разница " + d + ")");

    System.out.println(fails == 0 ? "Wiener: " + checks + " проверок, все прошли" : "Wiener: провалов " + fails + " из " + checks);
    return fails;
  }

  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
