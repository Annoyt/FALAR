package dev.agenttranslator;

import java.util.*;

/** «Только при шуме» (DenoiseGate): когда шумодав нарезки включается и выключается по фону комнаты, что
 *  считается музыкой и когда включение быстрое. Числа сценариев G8–G10 посчитаны близнецом — классом Gate в
 *  tools/vad_denoise_eval.py: разойдутся — значит, на компьютере мерили не то, что работает в телефоне.
 *  Запуск: bash bench/apk/test.sh. */
public class DenoiseGateTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static void near(double a, double b, double tol, String what) { ok(Math.abs(a - b) <= tol, what + ": " + a + " ≠ " + b); }
  /** n кадров с одним и тем же фоном; итог — сумма событий и номер кадра (с 1), на котором было событие. */
  static int[] run(DenoiseGate g, double room, int n) {
    int sum = 0, at = 0;
    for (int i = 1; i <= n; i++) { int e = g.step(room); if (e != 0) { sum += e; if (at == 0) at = i; } }
    return new int[]{sum, at};
  }
  /** Первая версия — только порог −44, как до 03.10 (стенд --es vaddenoise auto1). */
  static DenoiseGate v1() { DenoiseGate g = new DenoiseGate(); g.v2 = false; g.t = -44; return g; }
  /** Сценарий: кадры (фон, плоскостность кадра без речи или NaN — кадр речевой); события как «кадр:знак». */
  static String scenario(DenoiseGate g, double[][] parts) {
    StringBuilder sb = new StringBuilder(); int i = 0;
    for (double[] p : parts)
      for (int k = 0; k < (int) p[2]; k++) {
        i++;
        int e = g.step(p[0]);
        if (e != 0) sb.append(sb.length() == 0 ? "" : " ").append(i).append(e > 0 ? ":+" : ":-");
        if (!Double.isNaN(p[1])) g.observe(p[1]);
      }
    return sb.toString();
  }

  public static int run() {
    fails = 0; checks = 0;

    // G1: значения по умолчанию — то, что выбрано замером (results/2026-10-03-noise-detect.md)
    eq(DenoiseGate.DEFAULT_T, -48.0, "G1 порог по умолчанию −48 dBFS");
    eq(DenoiseGate.ON_FRAMES, 62, "G1 включение — 2 с кадрами по 32 мс");
    eq(DenoiseGate.OFF_FRAMES, 312, "G1 выключение — 10 с");
    eq(DenoiseGate.HYST, 3.0, "G1 гистерезис 3 дБ");
    eq(DenoiseGate.FAST_FRAMES, 15, "G1 быстрое включение — 0,5 с");
    eq(DenoiseGate.MUSIC_OFF_FRAMES, 93, "G1 музыка выключает за 3 с");
    near(Math.exp(DenoiseGate.LOG_MUSIC), 0.08, 1e-12, "G1 граница музыки — плоскостность 0,08");
    DenoiseGate g = new DenoiseGate();
    ok(!g.on(), "G1 сначала выключен"); ok(g.v2, "G1 по умолчанию вторая версия");

    // G2: первая версия — тишина и нет оценки — не включается никогда
    g = v1();
    eq(run(g, -50, 10000)[0], 0, "G2 тихая комната (−50) — ни одного включения");
    eq(run(g, DenoiseGate.NONE, 1000)[0], 0, "G2 оценки фона нет — не включается");
    eq(run(g, -44.01, 1000)[0], 0, "G2 чуть тише порога — не включается");

    // G3: шум — включается ровно на 62-м кадре подряд, не раньше (быстрого включения у первой версии нет)
    g = v1();
    int[] r = run(g, -30, 61);
    eq(r[0], 0, "G3 61 кадр громкого фона — ещё нет"); ok(!g.on(), "G3 ещё выключен");
    eq(g.step(-30), 1, "G3 62-й кадр подряд — включить"); ok(g.on(), "G3 включён");
    eq(g.step(-30), 0, "G3 включённый в шуме — без перемен");

    // G4: прерванный шум начинает счёт заново
    g = v1();
    run(g, -40, 61); g.step(-60);
    eq(run(g, -40, 61)[0], 0, "G4 тихий кадр сбивает счёт: снова 61 — ещё нет");
    eq(g.step(-40), 1, "G4 и на 62-м после сбоя — включить");

    // G5: выключение — 312 кадров подряд ниже порога на 3 дБ; на границе гистерезиса — держится
    g = v1(); run(g, -40, 62); ok(g.on(), "G5 включён");
    eq(run(g, -46.9, 5000)[0], 0, "G5 фон между порогом и порогом −3 дБ — остаётся включённым");
    eq(run(g, -47, 5000)[0], 0, "G5 ровно порог −3 дБ — ещё держится (строго ниже)");
    r = run(g, -47.01, 312);
    eq(r[0], -1, "G5 312 кадров ниже −47 — выключить"); eq(r[1], 312, "G5 ровно на 312-м кадре");
    ok(!g.on(), "G5 выключен");
    g = v1(); run(g, -40, 62);
    run(g, -60, 311); g.step(-40);
    eq(run(g, -60, 311)[0], 0, "G5 шумный кадр сбивает счёт выключения");
    eq(g.step(-60), -1, "G5 и снова 312 подряд — выключить");

    // G6: после выключения включается снова по тем же правилам
    eq(run(g, -40, 61)[0], 0, "G6 снова шум: 61 кадр — ещё нет");
    eq(g.step(-40), 1, "G6 62-й — включить снова");

    // G7: свой порог и сброс
    g = v1(); g.t = -47;
    eq(run(g, -46, 62)[0], 1, "G7 порог −47: фон −46 включает");
    g.reset(); ok(!g.on(), "G7 сброс — выключен");
    eq(run(g, -46, 61)[0], 0, "G7 после сброса счёт с нуля");
    g.reset(); run(g, -40, 62); g.reset();
    eq(run(g, -60, 400)[0], 0, "G7 сброшенный не выключается второй раз");

    // G8: вторая версия, порог −48. Умеренный шум (−46,5: громче порога, тише порога + 3) — через 2 с;
    // громкий (−40) — быстро, как только о спектре фона известно 8 кадров (8 + 15 = 23-й кадр);
    // кадры без наблюдений спектра (всё — речь) быстро не включают: только через 2 с.
    double noise = Math.log(0.3), tone = Math.log(0.02), tone2 = Math.log(0.01), NaN = Double.NaN;
    eq(scenario(new DenoiseGate(), new double[][]{{-46.5, noise, 200}}), "62:+", "G8 умеренный шум −46,5 — на 62-м кадре");
    eq(scenario(new DenoiseGate(), new double[][]{{-40, noise, 200}}), "23:+", "G8 громкий шум — быстро, на 23-м");
    eq(scenario(new DenoiseGate(), new double[][]{{-40, NaN, 100}}), "62:+", "G8 спектра фона нет — только через 2 с");
    eq(scenario(new DenoiseGate(), new double[][]{{-48.5, noise, 2000}}), "", "G8 чуть тише порога — никогда");
    eq(scenario(new DenoiseGate(), new double[][]{{-50.5, noise, 5000}}), "", "G8 тихая комната near-pt (−50,5) — никогда");

    // G9: музыка. С начала слушания — не включается вовсе, хотя громко; кончилась — включается, когда средняя
    // плоскостности поднимется (387-й кадр). Музыка началась при включённом — выключить (242-й).
    eq(scenario(new DenoiseGate(), new double[][]{{-30, tone, 300}, {-30, noise, 300}}), "387:+", "G9 музыка, потом шум");
    eq(scenario(new DenoiseGate(), new double[][]{{-40, noise, 100}, {-40, tone2, 400}}), "23:+ 242:-", "G9 шум, потом музыка — выключить");
    g = new DenoiseGate(); scenario(g, new double[][]{{-40, noise, 100}, {-40, tone2, 400}});
    ok(g.offByMusic, "G9 выключился из-за музыки");
    g = new DenoiseGate(); scenario(g, new double[][]{{-40, noise, 100}, {-60, noise, 400}});
    ok(!g.on() && !g.offByMusic, "G9 выключился из-за тишины, не музыки");
    g = new DenoiseGate(); scenario(g, new double[][]{{-30, tone, 50}}); ok(g.music(), "G9 тональный фон — музыка");
    g.reset(); ok(!g.music(), "G9 сброс забывает спектр");
    // первая версия музыки не знает
    g = v1(); eq(scenario(g, new double[][]{{-30, tone, 100}}), "62:+", "G9 первая версия включается и в музыке");

    // G11: границы — те же числа, что у близнеца (Gate в tools/vad_denoise_eval.py)
    eq(scenario(new DenoiseGate(), new double[][]{{-45, noise, 100}}), "23:+", "G11 фон ровно порог + 3 дБ — быстро");
    eq(scenario(new DenoiseGate(), new double[][]{{-45.01, noise, 100}}), "62:+", "G11 чуть тише порога + 3 — только через 2 с");
    eq(scenario(new DenoiseGate(), new double[][]{{-40, NaN, 3}, {-40, noise, 100}}), "26:+", "G11 быстрое — когда наблюдений спектра 8");
    eq(scenario(new DenoiseGate(), new double[][]{{-48, noise, 100}}), "62:+", "G11 фон ровно на пороге −48 — включает");
    eq(scenario(new DenoiseGate(), new double[][]{{-48.01, noise, 1000}}), "", "G11 чуть тише порога — никогда");
    g = new DenoiseGate();
    for (int k = 0; k < 7; k++) g.observe(tone);
    ok(!g.music(), "G11 семь тональных кадров — о музыке судить рано");
    g.observe(tone); ok(g.music(), "G11 восьмой — музыка");
    g = new DenoiseGate(); for (int k = 0; k < 20; k++) g.observe(DenoiseGate.LOG_MUSIC);
    ok(!g.music(), "G11 ровно на границе — не музыка (строго ниже)");
    g = new DenoiseGate(); for (int k = 0; k < 20; k++) g.observe(DenoiseGate.LOG_MUSIC - 1e-9);
    ok(g.music(), "G11 чуть ниже границы — музыка");

    // G10: плоскостность кадра — тот же счёт, что в Python (log_flatness), до 1e-9
    float[][] frames = new float[4][512];
    for (int n = 0; n < 512; n++) {
      double saw = ((n * 7919L + 13) % 1000) / 1000.0 - 0.5, saw2 = ((n * 104729L + 7) % 997) / 997.0 - 0.5;
      frames[0][n] = (float) (0.1 * Math.sin(2 * Math.PI * 1000 * n / 16000.0));
      frames[1][n] = (float) (0.01 * saw);
      frames[2][n] = (float) (0.1 * Math.sin(2 * Math.PI * 1000 * n / 16000.0) + 0.01 * saw);
      frames[3][n] = (float) (0.05 * Math.sin(2 * Math.PI * 1568 * n / 16000.0) + 0.04 * Math.sin(2 * Math.PI * 1175 * n / 16000.0) + 0.002 * saw2);
    }
    double[] py = {-21.3024531203, -3.8187689797, -9.9593466989, -12.2030655316};
    for (int c = 0; c < 4; c++) near(DenoiseGate.logFlatness(frames[c]), py[c], 1e-9, "G10 плоскостность кадра " + c + " как в Python");
    float[] white = new float[512]; Random rnd = new Random(1);
    for (int n = 0; n < 512; n++) white[n] = (float) (0.01 * rnd.nextGaussian());
    double wf = Math.exp(DenoiseGate.logFlatness(white));
    ok(wf > 0.3 && wf < 0.8, "G10 белый шум — ровный спектр (" + String.format(Locale.ROOT, "%.2f", wf) + ")");
    g = new DenoiseGate(); g.observeFrame(frames[3]); near(g.flat, py[3], 1e-9, "G10 observeFrame считает то же");
    float[] half = Arrays.copyOf(frames[3], 256), padded = Arrays.copyOf(half, 512);
    near(DenoiseGate.logFlatness(half), DenoiseGate.logFlatness(padded), 1e-12, "G10 короткий кадр дополняется нулями");

    System.out.println(fails == 0 ? "DenoiseGate: " + checks + " проверок, все прошли" : "DenoiseGate: провалов " + fails + " из " + checks);
    return fails;
  }

  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
