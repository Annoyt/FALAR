package dev.agenttranslator;

import java.util.*;

/** Настольные тесты сравнения версий и разбора описания релиза. Запуск: bash bench/apk/test.sh. */
public class UpdatesTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static String sha(char c) { StringBuilder s = new StringBuilder(); for (int i = 0; i < 64; i++) s.append(c); return s.toString(); }
  static void bad(String json, boolean slim, String what) {
    try { Updates.parse(json, slim); fails++; checks++; System.out.println("  ПРОВАЛ: " + what + " — разобралось, а не должно"); }
    catch (IllegalArgumentException e) { checks++; }
    catch (RuntimeException e) { fails++; checks++; System.out.println("  ПРОВАЛ: " + what + " — не то исключение: " + e); }
  }

  public static void main(String[] a) {
    String full = "{\"versionCode\":23,\"versionName\":\"0.22.0\",\"minVersionCode\":0,\"notes\":\"обновления из приложения\","
        + "\"apk\":\"Falar.apk\",\"size\":86830694,\"sha256\":\"" + sha('a') + "\","
        + "\"slim\":{\"apk\":\"Falar-slim.apk\",\"size\":22456703,\"sha256\":\"" + sha('b') + "\"}}";

    // разбор
    Updates.Info i = Updates.parse(full, false);
    eq(i.code, 23, "U1 номер сборки"); eq(i.name, "0.22.0", "U1 имя версии");
    eq(i.apk, "Falar.apk", "U1 файл"); eq(i.size, 86830694L, "U1 размер"); eq(i.sha256, sha('a'), "U1 сумма");
    Updates.Info s = Updates.parse(full, true);
    eq(s.apk, "Falar-slim.apk", "U2 облегчённой сборке — облегчённый файл");
    eq(s.size, 22456703L, "U2 её размер"); eq(s.sha256, sha('b'), "U2 её сумма");
    eq(s.code, 23, "U2 номер сборки общий");

    // адрес файла лежит рядом с описанием
    eq(i.url(Updates.LATEST), "https://github.com/Annoyt/FALAR/releases/latest/download/Falar.apk", "U3 адрес полной");
    eq(s.url(Updates.LATEST), "https://github.com/Annoyt/FALAR/releases/latest/download/Falar-slim.apk", "U3 адрес облегчённой");
    eq(i.url("http://127.0.0.1:8765/latest.json"), "http://127.0.0.1:8765/Falar.apk", "U3 стендовый источник");

    // сравнение
    ok(Updates.newer(22, i), "U4 22 < 23 — есть обновление");
    ok(!Updates.newer(23, i), "U4 та же сборка — обновления нет");
    ok(!Updates.newer(24, i), "U4 установлена новее — обновления нет");
    ok(!Updates.newer(22, null), "U4 описания нет — обновления нет");

    // обязательное обновление
    String must = full.replace("\"minVersionCode\":0", "\"minVersionCode\":23");
    Updates.Info m = Updates.parse(must, false);
    ok(Updates.required(22, m), "U5 ниже минимальной — обязательное");
    ok(!Updates.required(23, m), "U5 на минимальной — не обязательное");
    ok(!Updates.required(22, i), "U5 без минимальной — не обязательное");

    // строки для человека
    eq(Updates.describe(22, i), "Вышла версия 0.22.0 · 87 МБ", "U6 строка об обновлении");
    eq(Updates.describe(22, s), "Вышла версия 0.22.0 · 22 МБ", "U6 строка для облегчённой");
    eq(Updates.describe(23, i), "Установлена последняя версия", "U6 строка без обновления");
    eq(Updates.describe(22, m), "Вышла версия 0.22.0 · 87 МБ · обновление обязательное", "U6 строка об обязательном");
    eq(Updates.describe(22, null), "", "U6 нет описания — нет строки");

    // мусор не должен выглядеть как «обновлений нет»
    bad("", false, "U7 пустая строка");
    bad("не json вовсе", false, "U7 не json");
    bad("{}", false, "U7 пустой объект");
    bad("{\"versionCode\":23}", false, "U7 без файла");
    bad("{\"versionCode\":23,\"apk\":\"x.apk\",\"size\":10,\"sha256\":\"коротко\"}", false, "U7 сумма не той длины");
    bad("{\"versionCode\":0,\"apk\":\"x.apk\",\"size\":10,\"sha256\":\"" + sha('c') + "\"}", false, "U7 нулевой номер сборки");
    bad("{\"versionCode\":23,\"apk\":\"x.apk\",\"size\":0,\"sha256\":\"" + sha('c') + "\"}", false, "U7 нулевой размер");
    bad("<html>404</html>", false, "U7 страница ошибки вместо файла");

    // облегчённой сборке без своего раздела достаётся общий файл, а не ошибка
    String noSlim = "{\"versionCode\":23,\"versionName\":\"0.22.0\",\"apk\":\"Falar.apk\",\"size\":10,\"sha256\":\"" + sha('d') + "\"}";
    eq(Updates.parse(noSlim, true).apk, "Falar.apk", "U8 нет раздела slim — берётся общий");

    System.out.println(fails == 0 ? "Updates: " + checks + " проверок, все прошли" : "Updates: провалов " + fails + " из " + checks);
    System.exit(fails == 0 ? 0 : 1);
  }
}
