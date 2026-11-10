package dev.agenttranslator;

/** «Уточнять до озвучки» (RefineVoice) — на столе.
 *  R1 — ждёт только при «Точнее», включённом разборе контекста и выбранном языке; «Оба» — оба языка;
 *  R2 — ключи из настроек и стенда: чужое и пустое — PT, регистр и пробелы не важны; порядок сегмента;
 *  R3 — сколько ещё ждать до предела 3 с.
 *  Запуск: bash bench/apk/test.sh. */
public class RefineVoiceTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }

  public static void main(String[] a) {
    ok(RefineVoice.waits("pt", true, true, "pt") && !RefineVoice.waits("pt", true, true, "ru"), "R1 PT: ждёт португальский, русский — сразу");
    ok(RefineVoice.waits("ru", true, true, "ru") && !RefineVoice.waits("ru", true, true, "pt"), "R1 RU: ждёт русский");
    ok(RefineVoice.waits("both", true, true, "ru") && RefineVoice.waits("both", true, true, "pt"), "R1 «Оба» — оба языка");
    ok(!RefineVoice.waits("both", false, true, "pt"), "R1 «Быстрее» — голос сразу при любом выборе");
    ok(!RefineVoice.waits("both", true, false, "pt"), "R1 без разбора контекста (уточнителя) — сразу");
    ok(!RefineVoice.waits("pt", true, true, null), "R1 язык неизвестен — сразу");

    ok("pt".equals(RefineVoice.parse(null)) && "pt".equals(RefineVoice.parse("")) && "pt".equals(RefineVoice.parse("es")), "R2 чужое — PT");
    ok("both".equals(RefineVoice.parse(" BOTH ")) && "ru".equals(RefineVoice.parse("Ru")), "R2 регистр и пробелы не важны");
    ok(RefineVoice.index("pt") == 0 && RefineVoice.index("ru") == 1 && RefineVoice.index("both") == 2 && RefineVoice.index("x") == 0,
        "R2 сегмент: PT · RU · Оба");
    ok(RefineVoice.KEYS[RefineVoice.index("ru")].equals("ru"), "R2 позиция сегмента → ключ");
    ok("оба языка".equals(RefineVoice.label("both")) && "португальский".equals(RefineVoice.label("?")), "R2 надпись для журнала");

    ok(RefineVoice.left(1000, 1000) == 3000 && RefineVoice.left(1000, 2500) == 1500 && RefineVoice.left(1000, 9000) == 0,
        "R3 ожидание — до 3 с от готового перевода, не меньше нуля");

    System.out.println(fails == 0 ? "RefineVoice: " + checks + " проверок, все прошли" : "RefineVoice: провалов " + fails + " из " + checks);
    System.exit(fails == 0 ? 0 : 1);
  }
}
