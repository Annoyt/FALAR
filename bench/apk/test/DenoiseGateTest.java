package dev.agenttranslator;

import java.util.*;

/** «Только при шуме» (DenoiseGate): когда шумодав нарезки включается и выключается по фону комнаты.
 *  Запуск: bash bench/apk/test.sh. */
public class DenoiseGateTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  /** n кадров с одним и тем же фоном; итог — сумма событий и номер кадра (с 1), на котором было событие. */
  static int[] run(DenoiseGate g, double room, int n) {
    int sum = 0, at = 0;
    for (int i = 1; i <= n; i++) { int e = g.step(room); if (e != 0) { sum += e; if (at == 0) at = i; } }
    return new int[]{sum, at};
  }

  public static int run() {
    fails = 0; checks = 0;

    // G1: значения по умолчанию — то, что выбрано замером
    eq(DenoiseGate.DEFAULT_T, -44.0, "G1 порог по умолчанию −44 dBFS");
    eq(DenoiseGate.ON_FRAMES, 62, "G1 включение — 2 с кадрами по 32 мс");
    eq(DenoiseGate.OFF_FRAMES, 312, "G1 выключение — 10 с");
    eq(DenoiseGate.HYST, 3.0, "G1 гистерезис 3 дБ");
    DenoiseGate g = new DenoiseGate();
    ok(!g.on(), "G1 сначала выключен");

    // G2: тишина и нет оценки — не включается никогда
    eq(run(g, -50, 10000)[0], 0, "G2 тихая комната (−50) — ни одного включения");
    eq(run(g, DenoiseGate.NONE, 1000)[0], 0, "G2 оценки фона нет — не включается");
    eq(run(g, -44.01, 1000)[0], 0, "G2 чуть тише порога — не включается");

    // G3: шум — включается ровно на 62-м кадре подряд, не раньше
    g = new DenoiseGate();
    int[] r = run(g, -44, 61);
    eq(r[0], 0, "G3 61 кадр на пороге — ещё нет"); ok(!g.on(), "G3 ещё выключен");
    eq(g.step(-44), 1, "G3 62-й кадр подряд — включить"); ok(g.on(), "G3 включён");
    eq(g.step(-30), 0, "G3 включённый в шуме — без перемен");

    // G4: прерванный шум начинает счёт заново
    g = new DenoiseGate();
    run(g, -40, 61); g.step(-60);
    eq(run(g, -40, 61)[0], 0, "G4 тихий кадр сбивает счёт: снова 61 — ещё нет");
    eq(g.step(-40), 1, "G4 и на 62-м после сбоя — включить");

    // G5: выключение — 312 кадров подряд ниже порога на 3 дБ; на границе гистерезиса — держится
    g = new DenoiseGate(); run(g, -40, 62); ok(g.on(), "G5 включён");
    eq(run(g, -46.9, 5000)[0], 0, "G5 фон между порогом и порогом −3 дБ — остаётся включённым");
    eq(run(g, -47, 5000)[0], 0, "G5 ровно порог −3 дБ — ещё держится (строго ниже)");
    r = run(g, -47.01, 312);
    eq(r[0], -1, "G5 312 кадров ниже −47 — выключить"); eq(r[1], 312, "G5 ровно на 312-м кадре");
    ok(!g.on(), "G5 выключен");
    g = new DenoiseGate(); run(g, -40, 62);
    run(g, -60, 311); g.step(-40);
    eq(run(g, -60, 311)[0], 0, "G5 шумный кадр сбивает счёт выключения");
    eq(g.step(-60), -1, "G5 и снова 312 подряд — выключить");

    // G6: после выключения включается снова по тем же правилам
    eq(run(g, -40, 61)[0], 0, "G6 снова шум: 61 кадр — ещё нет");
    eq(g.step(-40), 1, "G6 62-й — включить снова");

    // G7: свой порог и сброс
    g = new DenoiseGate(); g.t = -47;
    eq(run(g, -46, 62)[0], 1, "G7 порог −47: фон −46 включает");
    g.reset(); ok(!g.on(), "G7 сброс — выключен");
    eq(run(g, -46, 61)[0], 0, "G7 после сброса счёт с нуля");
    g.reset(); run(g, -40, 62); g.reset();
    eq(run(g, -60, 400)[0], 0, "G7 сброшенный не выключается второй раз");

    System.out.println(fails == 0 ? "DenoiseGate: " + checks + " проверок, все прошли" : "DenoiseGate: провалов " + fails + " из " + checks);
    return fails;
  }

  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
