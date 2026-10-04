package dev.agenttranslator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Язык надписей: русский или португальский (владелец 04.10: «нужна версия интерфейса и на
 *  португальском… самому задать или по умолчанию язык системы»).
 *
 *  Русская строка в коде — она же ключ словаря bench/apk/ui_pt.txt: L.t("Слушать") по-русски
 *  вернёт себя, по-португальски — «Ouvir». Перевода нет — остаётся русская (не пустота). Числа и
 *  прочие вставки — через L.f с %s/%d, а не склейкой: склеенную строку словарь не узнает.
 *  Переводятся только надписи; журнал, подсказки моделям и разбор речи остаются русскими.
 *  Полноту словаря проверяет настольный тест (LTest): каждый ключ из L.t/L.f/L.n в коде есть
 *  в ui_pt.txt, вставки совпадают. Без Android — проверяется на столе. */
public final class L {
  public static final String RU = "ru", PT = "pt";
  /** Язык надписей сейчас: выбор человека или язык телефона (resolve). */
  public static volatile String lang = RU;
  static volatile Map<String, String> pt = Collections.emptyMap();
  /** Ключи, для которых перевода не нашлось, — для стенда и журнала. */
  static final java.util.Set<String> missing = Collections.synchronizedSet(new java.util.LinkedHashSet<>());

  private L() {}

  public static boolean pt() { return PT.equals(lang); }

  /** Пометка ключа в константе: static final String X = L.k("…") — сама строка не меняется, а тест
   *  словаря видит её как ключ; переводит её потом L.t(X) там, где она показывается. */
  public static String k(String ru) { return ru; }

  /** Надпись на языке интерфейса. */
  public static String t(String ru) {
    if (ru == null || !PT.equals(lang)) return ru;
    String v = pt.get(ru);
    if (v == null) { missing.add(ru); return ru; }
    return v;
  }

  /** Подписи сегмента и списка разом. */
  public static String[] t(String[] ru) {
    String[] out = new String[ru.length];
    for (int i = 0; i < ru.length; i++) out[i] = t(ru[i]);
    return out;
  }

  /** Надпись со вставками: L.f("Скачать %s МБ", mb). Вставки — как в String.format; если
   *  в переводе их порядок другой — %1$s, %2$s. */
  public static String f(String ru, Object... a) {
    return String.format(Locale.ROOT, t(ru), a);
  }

  /** Число со словом: русские формы для 1, 2–4 и 5–20 («фраза», «фразы», «фраз»); по-португальски
   *  ключ «фраза|фразы|фраз» → «frase|frases»: одна — при 1, иначе вторая. */
  public static String n(long n, String one, String few, String many) {
    if (PT.equals(lang)) {
      String key = one + "|" + few + "|" + many, v = pt.get(key);
      if (v != null) { int bar = v.indexOf('|'); return bar < 0 ? v : Math.abs(n) == 1 ? v.substring(0, bar) : v.substring(bar + 1); }
      missing.add(key);
    }
    long m = Math.abs(n) % 100, d = m % 10;
    if (m >= 11 && m <= 14) return many;
    return d == 1 ? one : d >= 2 && d <= 4 ? few : many;
  }

  /** Какой язык показывать. choice — выбор человека: "ru", "pt" или "" — как в системе;
   *  sysLang — язык телефона («pt», «ru», «en»…). Телефон не на русском и не на португальском —
   *  русский (владелец 04.10). */
  public static String resolve(String choice, String sysLang) {
    if (RU.equals(choice) || PT.equals(choice)) return choice;
    return sysLang != null && sysLang.toLowerCase(Locale.ROOT).startsWith(PT) ? PT : RU;
  }

  /** Подключить словарь (разобранный ui_pt.txt). */
  public static void use(Map<String, String> dict) { pt = dict == null ? Collections.emptyMap() : dict; }

  /** Строки ui_pt.txt: «русский<TAB>português»; # — примечание; \n, \t и \\ — экранированные. */
  public static List<String[]> entries(java.io.Reader in) throws java.io.IOException {
    List<String[]> out = new ArrayList<>();
    java.io.BufferedReader r = new java.io.BufferedReader(in);
    String line; int no = 0;
    while ((line = r.readLine()) != null) {
      no++;
      if (line.isEmpty() || line.startsWith("#")) continue;
      int tab = line.indexOf('\t');
      if (tab <= 0) throw new java.io.IOException("ui_pt.txt:" + no + ": нет табуляции между русским и португальским");
      out.add(new String[]{unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)), String.valueOf(no)});
    }
    return out;
  }

  public static Map<String, String> parse(java.io.Reader in) throws java.io.IOException {
    Map<String, String> m = new LinkedHashMap<>();
    for (String[] e : entries(in)) m.putIfAbsent(e[0], e[1]);
    return m;
  }

  static String unescape(String s) {
    if (s.indexOf('\\') < 0) return s;
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char x = s.charAt(++i);
        b.append(x == 'n' ? '\n' : x == 't' ? '\t' : x);
      } else b.append(c);
    }
    return b.toString();
  }

  /** Обратное unescape — для инструментов, которые дописывают словарь. */
  static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t");
  }
}
