package dev.agenttranslator;

import java.util.*;

/** Модули: выбор по умолчанию под телефон, «как было» у обновившегося, запись выбора,
 *  когда есть кнопки 📷 и «Улучшить». Запуск: bash bench/apk/test.sh. */
public class ModulesTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static Set<String> set(String... m) { return new LinkedHashSet<>(Arrays.asList(m)); }

  public static int run() {
    fails = 0; checks = 0;
    // D: по умолчанию при установке — под телефон (решение владельца 28.09)
    eq(Modules.defaults(7_400_000_000L), set(Modules.TTS, Modules.OCR, Modules.CORPUS, Modules.LLM), "D1 «8 ГБ» (7,4 ГБ системе) — с уточнителем");
    eq(Modules.defaults(5_600_000_000L), set(Modules.TTS, Modules.OCR, Modules.CORPUS), "D1 «6 ГБ» — без уточнителя");
    ok(Modules.defaults(Modules.LLM_RAM).contains(Modules.LLM), "D2 ровно на пороге — с уточнителем");
    ok(!Modules.defaults(Modules.LLM_RAM - 1).contains(Modules.LLM), "D2 на байт меньше — без");
    ok(!Modules.defaults(0).contains(Modules.LLM), "D2 память неизвестна (0) — без уточнителя");
    ok(!Modules.defaults(1L << 40).contains(Modules.CLOUD) && !Modules.defaults(1L << 40).contains(Modules.SPEAKER), "D3 облако и отпечаток голоса по умолчанию выключены при любой памяти");

    // U: обновились с версии без модулей — как было
    eq(Modules.upgraded(set(Modules.TTS, Modules.LLM, Modules.CORPUS), false), set(Modules.TTS, Modules.OCR, Modules.LLM, Modules.CORPUS),
        "U1 включено скачанное; снимки — новые и маленькие — включены сразу");
    eq(Modules.upgraded(set(Modules.TTS), true), set(Modules.TTS, Modules.OCR, Modules.CLOUD), "U2 есть ключ — облако включено");
    ok(!Modules.upgraded(set(Modules.TTS, Modules.CLOUD), false).contains(Modules.CLOUD), "U3 без ключа облако не включается, даже если «установлено»");
    ok(!Modules.upgraded(set(), false).contains(Modules.TTS), "U4 голоса не было — озвучка не включается сама");
    ok(Modules.upgraded(set(Modules.SPEAKER, "мусор"), false).contains(Modules.SPEAKER) && !Modules.upgraded(set("мусор"), false).contains("мусор"),
        "U5 чужие имена не проходят");

    // P: запись выбора
    Set<String> all = new LinkedHashSet<>(Modules.CHOICE);
    eq(Modules.parse(Modules.join(all)), all, "P1 все модули туда и обратно");
    eq(Modules.join(set(Modules.CORPUS, Modules.TTS)), "tts,corpus", "P2 порядок записи — как на экране");
    eq(Modules.parse(" tts , ocr,мусор,,base"), set(Modules.TTS, Modules.OCR), "P3 пробелы, мусор и обязательное base выбрасываются");
    eq(Modules.parse(null), set(), "P4 ничего не записано — пусто");
    eq(Modules.join(set()), "", "P5 пусто — пустая строка (только перевод речи)");

    // K: какие кнопки есть
    ok(Modules.photo(set(Modules.OCR)) && Modules.photo(set(Modules.CLOUD)) && !Modules.photo(set(Modules.TTS, Modules.LLM)), "K1 📷 — если снимок есть чем прочитать");
    ok(Modules.better(set(Modules.LLM)) && Modules.better(set(Modules.CLOUD)) && !Modules.better(set(Modules.TTS, Modules.OCR)), "K2 «Улучшить» — если есть чем уточнять");
    boolean named = true;
    for (String m : Modules.CHOICE) named &= !Modules.title(m).equals(m) && !Modules.what(m).isEmpty();
    ok(named && !Modules.what(Modules.BASE).isEmpty(), "K3 у каждого модуля название и строка «что даёт»");
    eq(Modules.title("нет такого"), "нет такого", "K3 незнакомый модуль — как есть");

    System.out.println(fails == 0 ? "Modules: " + checks + " проверок, все прошли" : "Modules: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
