package dev.agenttranslator;

import java.util.*;

/** Когда выгружать уточнитель по сигналу памяти: пик от самого подъёма пережидаем, держащееся
 *  давление и уход в фон — выгружаем. Запуск: bash bench/apk/test.sh. */
public class PressureTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static final int CRIT = Pressure.RUNNING_CRITICAL;
  static boolean u(int level, long now, long loaded, long prev) { return Pressure.unload(level, now, loaded, prev); }

  public static int run() {
    fails = 0; checks = 0;
    long t = 1_000_000;
    ok(!u(CRIT, t, 0, 0), "D1 уточнитель не поднят — выгружать нечего");
    ok(!u(Pressure.BACKGROUND, t, 0, t - 1), "D1 и в фоне тоже");
    ok(u(Pressure.BACKGROUND, t, t - 1, 0), "D2 ушли в фон — выгружаем сразу, даже в первую секунду");
    ok(u(80, t, t - 1, 0), "D2 уровни выше фона — тоже");
    ok(!u(10, t + 60_000, t, t + 59_000), "D3 «памяти маловато» — не повод");
    ok(!u(20, t + 60_000, t, t + 59_000), "D3 свёрнутый экран — не повод");
    ok(!u(CRIT, t + 3_000, t, 0), "D4 пик через 3 с после подъёма — пережидаем");
    ok(!u(CRIT, t + 29_999, t, t + 29_000), "D4 и два подряд внутри 30 с — пережидаем");
    ok(!u(CRIT, t + 30_000, t, 0), "D5 после 30 с первый сигнал — ещё ждём повтора");
    ok(u(CRIT, t + 30_000, t, t + 15_000), "D6 повтор ровно через 15 с — давление держится");
    ok(!u(CRIT, t + 30_001, t, t + 15_000), "D6 через 15 с и 1 мс — уже не повтор");
    ok(u(CRIT, t + 40_000, t, t + 39_000), "D6 повтор через секунду — выгружаем");
    ok(!u(CRIT, t + 100_000, t, t + 3_000), "D7 давний сигнал пика повтором не считается");
    System.out.println(fails == 0 ? "Pressure: " + checks + " проверок, все прошли" : "Pressure: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
