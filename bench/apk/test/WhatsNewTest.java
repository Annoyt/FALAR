package dev.agenttranslator;

import java.util.*;

/** «Что нового» после обновления (WhatsNew): разбор whatsnew.txt, с какой версии пришли, какие
 *  пункты показать — сначала новое, потом исправления, по всем пропущенным версиям, — и строка
 *  журнала. Плюс сам bench/apk/whatsnew.txt: разбирается, пункты короткие, запись для versionCode
 *  манифеста есть; и сайт docs/index.html показывает versionName манифеста под кнопкой «Скачать».
 *  Запуск: bash bench/apk/test.sh. */
public class WhatsNewTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  /** Разбор должен упасть, и сообщение должно содержать подстроку (номер строки, причину). */
  static void bad(String text, String has, String what) {
    try { WhatsNew.parse(text); ok(false, what + " — разобралось, а не должно"); }
    catch (IllegalArgumentException e) { ok(e.getMessage().contains(has), what + " — сообщение «" + e.getMessage() + "» без «" + has + "»"); }
    catch (RuntimeException e) { ok(false, what + " — не то исключение: " + e); }
  }
  static List<String> texts(List<WhatsNew.Item> l) { List<String> r = new ArrayList<>(); for (WhatsNew.Item i : l) r.add(i.text); return r; }
  static List<String> vers(List<WhatsNew.Item> l) { List<String> r = new ArrayList<>(); for (WhatsNew.Item i : l) r.add(i.ver); return r; }

  static final String SAMPLE = "# заметка\n"
      + "== следующая\n"
      + "+ новое в работе\n"
      + "\n"
      + "== 0.26.1 (32)\n"
      + "* исправление 32 а\n"
      + "*   исправление 32 б  \n"
      + "\n"
      + "== 0.26.0 (31)\n"
      + "+ новое 31 а\n"
      + "* исправление 31\n"
      + "  + новое 31 б\n"
      + "== 0.25.0 (30)\n"
      + "+ новое 30\n";

  public static int run(String file, String manifest) throws Exception {
    fails = 0; checks = 0;

    // W1: разбор
    List<WhatsNew.Entry> all = WhatsNew.parse(SAMPLE);
    eq(all.size(), 4, "W1 четыре версии");
    eq(all.get(0).code, WhatsNew.NEXT, "W1 «следующая» — без номера, выше всех");
    eq(all.get(0).name, "", "W1 у «следующей» нет имени");
    eq(all.get(1).code, 32, "W1 номер сборки"); eq(all.get(1).name, "0.26.1", "W1 имя версии");
    eq(all.get(1).fixed, List.of("исправление 32 а", "исправление 32 б"), "W1 исправления по порядку, пробелы по краям сняты");
    eq(all.get(1).added, List.of(), "W1 нового в 0.26.1 нет");
    eq(all.get(2).added, List.of("новое 31 а", "новое 31 б"), "W1 «+» — новое, и с отступом");
    eq(all.get(2).fixed, List.of("исправление 31"), "W1 «*» — исправлено");
    eq(all.get(3).code, 30, "W1 последняя версия");
    eq(WhatsNew.parse(SAMPLE.replace("\n", "\r\n")).get(1).fixed, all.get(1).fixed, "W1 переводы строк Windows");
    eq(WhatsNew.parse("").size(), 0, "W1 пустой файл — версий нет");
    eq(WhatsNew.parse("# только заметка\n\n").size(), 0, "W1 одни заметки — версий нет");

    // W2: ошибки — с номером строки и причиной
    bad("+ пункт\n== 0.26.1 (32)\n", "строка 1", "W2 пункт до первой версии");
    bad("+ пункт\n", "до первой версии", "W2 причина — пункт до версии");
    bad("== 0.26.1 (32)\n+ а\n== 0.26 (31)\n+ б\n", "строка 3", "W2 версия без третьего числа");
    bad("== 0.26.1 32\n+ а\n", "== 0.26.1 (32)", "W2 номер сборки без скобок");
    bad("== следующее\n+ а\n", "строка 1", "W2 опечатка в «следующая»");
    bad("== 0.26.1 (32)\n- а\n", "строка 2", "W2 незнакомая строка");
    bad("== 0.26.1 (32)\n+\n", "«+ текст»", "W2 пункт без текста");
    bad("== 0.26.1 (32)\n+а\n", "«+ текст»", "W2 пункт без пробела после «+»");
    bad("== 0.26.1 (32)\n+пункт\n", "«+ текст»", "W2 длинный пункт без пробела после «+»");
    bad("== 0.26.1 (32)\n*\n", "строка 2", "W2 «*» без текста");
    bad("== 0.25.0 (30)\n+ а\n== 0.26.0 (31)\n+ б\n", "убывают", "W2 старая версия выше новой");
    bad("== 0.26.0 (31)\n+ а\n== 0.26.0 (31)\n+ б\n", "строка 3", "W2 одна версия дважды");
    bad("== 0.26.0 (31)\n+ а\n== следующая\n+ б\n", "«следующая» — первой", "W2 «следующая» не первой");
    bad("== 0.26.0 (31)\n== 0.25.0 (30)\n+ а\n", "«0.26.0» нет пунктов", "W2 версия без пунктов");
    bad("== следующая\n== 0.25.0 (30)\n+ а\n", "«следующая» нет пунктов", "W2 пустая «следующая»");
    bad("== 0.26.0 (31)\n+ а\n== 0.25.0 (30)\n", "«0.25.0» нет пунктов", "W2 пустая версия в конце файла");

    // W3: с какой версии обновились
    eq(WhatsNew.previous(28, false, 33), 28, "W3 записанный номер прошлого запуска");
    eq(WhatsNew.previous(28, true, 33), 28, "W3 запись важнее времени установки");
    eq(WhatsNew.previous(0, true, 33), 33, "W3 поставили с нуля — показывать нечего");
    eq(WhatsNew.previous(0, false, 33), WhatsNew.BEFORE, "W3 записи нет, но обновлялись — с версии без окна");
    eq(WhatsNew.BEFORE, 32, "W3 последняя версия без окна — 0.26.1");

    // W4: какие пункты показать
    WhatsNew.Notes n = WhatsNew.notes(all, 30, 32, "0.26.1");
    eq(texts(n.added), List.of("новое в работе", "новое 31 а", "новое 31 б"), "W4 новое: «следующая», потом пропущенные версии, новые сверху");
    eq(texts(n.fixed), List.of("исправление 32 а", "исправление 32 б", "исправление 31"), "W4 исправления — отдельно, тем же порядком");
    eq(vers(n.added), List.of("0.26.1", "0.26.0", "0.26.0"), "W4 у пункта — его версия, «следующая» — именем этой сборки");
    eq(vers(n.fixed), List.of("0.26.1", "0.26.1", "0.26.0"), "W4 версии исправлений");
    ok(!n.isEmpty(), "W4 показывать есть что");
    ok(!texts(n.added).contains("новое 30"), "W4 версия, что стояла, не показывается");
    WhatsNew.Notes one = WhatsNew.notes(all, 31, 32, "0.26.1");
    eq(texts(one.added), List.of("новое в работе"), "W4 с прошлой версии — её новое");
    eq(texts(one.fixed), List.of("исправление 32 а", "исправление 32 б"), "W4 с прошлой версии — её исправления");
    ok(WhatsNew.notes(all, 32, 32, "0.26.1").isEmpty(), "W4 та же версия — пусто, даже со «следующей»");
    ok(WhatsNew.notes(all, 33, 32, "0.26.1").isEmpty(), "W4 откат на старую версию — пусто");
    eq(texts(WhatsNew.notes(all, 21, 32, "0.26.1").added), List.of("новое в работе", "новое 31 а", "новое 31 б", "новое 30"),
        "W4 версия старше всех записей — всё, что есть");
    WhatsNew.Notes low = WhatsNew.notes(all, 30, 31, "0.26.0");
    eq(texts(low.fixed), List.of("исправление 31"), "W4 записи новее этой сборки не показываются");
    eq(texts(low.added), List.of("новое в работе", "новое 31 а", "новое 31 б"), "W4 «следующая» — в этой сборке при любом номере");
    List<WhatsNew.Entry> rel = WhatsNew.parse(SAMPLE.replace("== следующая\n+ новое в работе\n", ""));
    eq(texts(WhatsNew.notes(rel, 30, 32, "0.26.1").added), List.of("новое 31 а", "новое 31 б"), "W4 без «следующей» — только выпуски");
    WhatsNew.Notes fx = WhatsNew.notes(rel, 31, 32, "0.26.1");
    ok(fx.added.isEmpty() && fx.fixed.size() == 2, "W4 выпуск с одними исправлениями");

    // W5: окно из «Настроек» без обновления — пункты этой версии
    WhatsNew.Notes own = WhatsNew.own(all, 32, "0.26.1");
    eq(texts(own.added), List.of("новое в работе"), "W5 своя версия: новое");
    eq(texts(own.fixed), List.of("исправление 32 а", "исправление 32 б"), "W5 своя версия: исправления");
    eq(texts(WhatsNew.own(rel, 31, "0.26.0").fixed), List.of("исправление 31"), "W5 только своя версия, без более ранних и поздних");

    // W6: строка под заголовком
    eq(WhatsNew.since("0.23.1"), "Вы обновились с версии 0.23.1.", "W6 с какой версии — простой фразой");
    eq(WhatsNew.since(""), "", "W6 неизвестно — пусто"); eq(WhatsNew.since(null), "", "W6 нет имени — пусто");

    // W7: запись в журнал
    eq(WhatsNew.plain(n, "0.25.0", "0.26.1"), "🆕 Обновлено 0.25.0 → 0.26.1\nНовое:\n• новое в работе\n• новое 31 а\n• новое 31 б"
        + "\nИсправлено:\n• исправление 32 а\n• исправление 32 б\n• исправление 31", "W7 сводка: сначала новое, потом исправления, без номеров версий");
    eq(WhatsNew.plain(fx, "", "0.26.1"), "🆕 Обновлено до 0.26.1\nИсправлено:\n• исправление 32 а\n• исправление 32 б", "W7 прежняя неизвестна, одна версия, только исправления");
    eq(WhatsNew.plain(WhatsNew.notes(rel, 30, 31, "0.26.0"), null, "0.26.0"), "🆕 Обновлено до 0.26.0\nНовое:\n• новое 31 а\n• новое 31 б\nИсправлено:\n• исправление 31",
        "W7 одна версия — без подписей");

    // W8: настоящий whatsnew.txt
    if (file != null && new java.io.File(file).exists()) {
      List<WhatsNew.Entry> real = WhatsNew.parse(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file)), "UTF-8"));
      ok(real.size() > 5, "W8 whatsnew.txt разобран: версий " + real.size());
      Set<String> seen = new HashSet<>();
      for (WhatsNew.Entry e : real) for (List<String> l : List.of(e.added, e.fixed)) for (String t : l) {
        int len = t.codePointCount(0, t.length());
        ok(len <= WhatsNew.MAX_ITEM, "W8 пункт длиннее " + WhatsNew.MAX_ITEM + " знаков (" + len + "): " + t);
        ok(seen.add(t), "W8 пункт повторяется: " + t);
      }
      boolean before = false;
      for (WhatsNew.Entry e : real) before |= e.code == WhatsNew.BEFORE;
      ok(before, "W8 есть запись для BEFORE = " + WhatsNew.BEFORE);
      int[] last = null;
      for (WhatsNew.Entry e : real) {
        if (e.code == WhatsNew.NEXT) continue;
        String[] p = e.name.split("\\."); int[] v = {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        if (last != null) ok(Arrays.compare(last, v) > 0, "W8 имена версий убывают вместе с номерами: " + e.name);
        last = v;
      }
      if (manifest != null && new java.io.File(manifest).exists()) {
        String m = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(manifest)), "UTF-8");
        java.util.regex.Matcher c = java.util.regex.Pattern.compile("versionCode=\"(\\d+)\"").matcher(m), nm = java.util.regex.Pattern.compile("versionName=\"([^\"]+)\"").matcher(m);
        ok(c.find() && nm.find(), "W8 в манифесте есть versionCode и versionName");
        int code = Integer.parseInt(c.group(1)); String name = nm.group(1); String got = null;
        for (WhatsNew.Entry e : real) if (e.code == code) got = e.name;
        // Поднятие версии без записи: окно после обновления на неё было бы пустым или чужим.
        eq(got, name, "W8 запись «== " + name + " (" + code + ")» для версии манифеста — «следующая» получает номер при поднятии версии");
        // Сайт показывает версию под кнопкой «Скачать»: без сверки она отстала бы на первом же выпуске.
        java.io.File site = new java.io.File(new java.io.File(manifest).getAbsoluteFile().getParentFile().getParentFile().getParentFile(), "docs/index.html");
        if (site.exists()) {
          String h = new String(java.nio.file.Files.readAllBytes(site.toPath()), "UTF-8");
          ok(h.contains("Версия " + name + " ·"), "W9 docs/index.html: под кнопкой «Версия " + name + " · … МБ» — при поднятии версии поправьте строку (и размер APK, если изменился)");
        }
      }
    } else ok(file == null, "W8 нет файла " + file);

    System.out.println(fails == 0 ? "WhatsNew: " + checks + " проверок, все прошли" : "WhatsNew: провалов " + fails + " из " + checks);
    return fails;
  }

  public static void main(String[] a) throws Exception {
    System.exit(run(a.length > 0 ? a[0] : null, a.length > 1 ? a[1] : null) == 0 ? 0 : 1);
  }
}
