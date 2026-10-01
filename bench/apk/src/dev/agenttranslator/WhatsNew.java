package dev.agenttranslator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** «Что нового»: что показать человеку при первом запуске после обновления.
 *
 *  Обновление приходит само (Updates): человек нажал «Обновить» и не знает, что изменилось, а
 *  версии он мог и пропускать — раз в сутки предлагается только последняя. Поэтому после
 *  обновления Falar показывает пункты всех версий новее той, что стояла: сначала новое, потом
 *  исправления. Та же сводка уходит в журнал.
 *
 *  Текст — bench/apk/whatsnew.txt, вшит в APK (assets/whatsnew.txt): окно показывается без сети.
 *  Сравнивается versionCode, как и в Updates: он целый и только растёт.
 *
 *  Прежние версии своего номера нигде не запоминали. Поэтому при первом переходе с них
 *  (записи нет, а приложение обновлялось) неизвестно, с какой версии пришли, и показывается то,
 *  что новее BEFORE — последней версии без этого окна. Дальше номер хранится, и пропущенные
 *  версии собираются все.
 *
 *  Без Android, проверяется на столе (bench/apk/test/WhatsNewTest.java) — вместе с самим whatsnew.txt. */
public final class WhatsNew {
  /** Код раздела «следующая»: сделанное, но ещё не выпущенное. Оно уже в этой сборке. */
  public static final int NEXT = Integer.MAX_VALUE;
  /** Последняя версия без окна «Что нового» (0.26.1): она не запоминала свой номер. */
  public static final int BEFORE = 32;
  /** Пункт длиннее — уже не «кратко»: в окне это три строки. Проверяет настольный тест. */
  public static final int MAX_ITEM = 100;

  /** Версия из файла: имя, versionCode и пункты «новое» и «исправлено» в порядке файла. */
  public static final class Entry {
    public final String name; public final int code;
    public final List<String> added = new ArrayList<>(), fixed = new ArrayList<>();
    Entry(String name, int code) { this.name = name; this.code = code; }
  }
  /** Пункт окна и версия, в которой он появился. */
  public static final class Item {
    public final String text, ver;
    Item(String text, String ver) { this.text = text; this.ver = ver; }
  }
  /** Что показать: пункты по разделам, новые версии сверху; multi — версий больше одной, и тогда у
   *  пунктов видна версия. */
  public static final class Notes {
    public final List<Item> added = new ArrayList<>(), fixed = new ArrayList<>();
    public boolean multi;
    public boolean isEmpty() { return added.isEmpty() && fixed.isEmpty(); }
  }

  static final Pattern HEAD = Pattern.compile("== (\\d+\\.\\d+\\.\\d+) \\((\\d+)\\)");

  /** Разбор файла. Бросает IllegalArgumentException с номером строки: показать половину молча
   *  нельзя, а настольный тест разбирает настоящий файл, так что до выпуска ошибка не доходит. */
  public static List<Entry> parse(String text) {
    List<Entry> all = new ArrayList<>();
    Entry cur = null; int n = 0;
    for (String raw : text.split("\n", -1)) {
      n++;
      String s = raw.trim();
      if (s.isEmpty() || s.startsWith("#")) continue;
      if (s.startsWith("==")) {
        Entry e;
        if (s.equals("== следующая")) e = new Entry("", NEXT);
        else {
          Matcher m = HEAD.matcher(s);
          if (!m.matches()) throw bad(n, "версия пишется «== 0.26.1 (32)» или «== следующая»");
          e = new Entry(m.group(1), Integer.parseInt(m.group(2)));
        }
        if (cur != null && cur.code <= e.code) throw bad(n, "версии — новые сверху, номера сборок убывают, «следующая» — первой");
        all.add(e); cur = e;
      } else if (s.charAt(0) == '+' || s.charAt(0) == '*') {
        if (cur == null) throw bad(n, "пункт до первой версии");
        if (s.length() < 3 || s.charAt(1) != ' ') throw bad(n, "пункт пишется «+ текст» или «* текст»");
        (s.charAt(0) == '+' ? cur.added : cur.fixed).add(s.substring(2).trim());
      } else throw bad(n, "строка — «+ новое», «* исправлено», «== версия» или «# заметка»");
    }
    for (Entry e : all)
      if (e.added.isEmpty() && e.fixed.isEmpty()) throw new IllegalArgumentException("whatsnew: у версии «" + (e.code == NEXT ? "следующая" : e.name) + "» нет пунктов");
    return all;
  }
  static IllegalArgumentException bad(int line, String why) { return new IllegalArgumentException("whatsnew, строка " + line + ": " + why); }

  /** С какой версии обновились. stored — номер, записанный прошлым запуском (0 — записи нет);
   *  fresh — приложение поставлено с нуля, а не обновлено (время установки равно времени
   *  обновления). Записи нет, а приложение обновлялось — пришли с версии без этого окна. */
  public static int previous(int stored, boolean fresh, int cur) {
    if (stored > 0) return stored;
    return fresh ? cur : BEFORE;
  }

  /** Пункты для того, у кого стояла from, а теперь to: версии from < code ≤ to и «следующая» — она
   *  уже в этой сборке и подписывается её именем toName. Ничего не обновлялось (from ≥ to) — пусто. */
  public static Notes notes(List<Entry> all, int from, int to, String toName) {
    Notes r = new Notes();
    if (from >= to) return r;
    String first = null;
    for (Entry e : all) {
      if (e.code != NEXT && (e.code <= from || e.code > to)) continue;
      String v = e.code == NEXT ? toName : e.name;
      if (first == null) first = v;
      else if (!first.equals(v)) r.multi = true;
      for (String t : e.added) r.added.add(new Item(t, v));
      for (String t : e.fixed) r.fixed.add(new Item(t, v));
    }
    return r;
  }
  /** Пункты самой версии to — когда окно открывают из «Настроек», а обновления не было
   *  (поставили с нуля). */
  public static Notes own(List<Entry> all, int to, String toName) { return notes(all, to - 1, to, toName); }

  /** Строка под заголовком окна: с какой версии обновились. Неизвестно — пусто. */
  public static String since(String fromName) { return fromName == null || fromName.isEmpty() ? "" : "Обновлено с " + fromName; }

  /** Та же сводка одной записью для журнала: «🆕 Обновлено 0.23.1 → 0.27.0», «Новое», «Исправлено»;
   *  у пунктов — версия, если версий больше одной. */
  public static String plain(Notes n, String fromName, String toName) {
    StringBuilder b = new StringBuilder("🆕 Обновлено ");
    b.append(fromName == null || fromName.isEmpty() ? "до " + toName : fromName + " → " + toName);
    section(b, "Новое", n.added, n.multi);
    section(b, "Исправлено", n.fixed, n.multi);
    return b.toString();
  }
  static void section(StringBuilder b, String head, List<Item> items, boolean multi) {
    if (items.isEmpty()) return;
    b.append('\n').append(head).append(':');
    for (Item i : items) b.append("\n• ").append(i.text).append(multi ? " · " + i.ver : "");
  }
}
