package dev.agenttranslator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Португальские числа словами — для озвучки и транскрипции. Распознаватель пишет сказанное число цифрами («20 reais»,
 *  «8:30», «R$ 20,50»), переводчик переносит их как есть (TextRules: плейсхолдеры), на экране они остаются цифрами —
 *  владелец 08.10: писать можно цифрами, а проговаривать и давать транскрипцию буквами, чтобы числительные можно было
 *  выучить. Piper сам читает «20» как «vinte», но «R$ 20,50» — не как «vinte reais e cinquenta centavos», «8:30» — как
 *  восемьсот тридцать, «2 pessoas» — «dois» вместо «duas» (проверено parakeet'ом по синтезу,
 *  results/2026-10-08-asr-review.md), а транскрипция (Translit.say) цифры не читала вовсе.
 *
 *  Правила pt-BR: до 19 — своими словами, десятки и единицы через «e» (vinte e um), сотни — cento/duzentos… и «e»
 *  перед десятками (cento e vinte e um), ровно сто — cem; «e» перед последней группой, если она меньше ста или
 *  круглые сотни (mil e duzentos, mil e vinte), иначе без (mil duzentos e cinquenta); um milhão / dois milhões.
 *  Род — по следующему слову: на -a/-as женский (duas pessoas, vinte e uma horas), кроме dia(s) и слов на -ma
 *  (problema, sistema). Деньги: R$ 20,50 → vinte reais e cinquenta centavos, R$ 1,00 → um real. Время: 8:30 → oito e
 *  trinta, 8h → oito horas, 13:00 → treze horas. Десятичная запятая — vírgula (2,5 → dois vírgula cinco), точка между
 *  группами по три — разделитель тысяч (1.250). Проценты — por cento. Длинные ряды цифр (индекс 01310-100, телефон) —
 *  по одной цифре. Без Android, проверяется на столе (bench/apk/test/NumbersTest.java). */
public final class Numbers {
  private Numbers() {}
  static final String[] ONES = {"zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze", "doze",
      "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove"};
  static final String[] TENS = {"", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa"};
  static final String[] HUNDREDS = {"", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos"};

  /** Целое словами; fem — женский род (uma, duas, duzentas). */
  public static String cardinal(long n, boolean fem) {
    if (n < 0) return "menos " + cardinal(-n, fem);
    if (n < 1000) return below1000(n, fem);
    if (n < 1_000_000) { long k = n / 1000, r = n % 1000; String head = k == 1 ? "mil" : below1000(k, false) + " mil"; return r == 0 ? head : head + join(r) + below1000(r, fem); }
    if (n < 1_000_000_000L) { long m = n / 1_000_000, r = n % 1_000_000; String head = m == 1 ? "um milhão" : below1000(m, false) + " milhões"; return r == 0 ? head : head + join(r) + cardinal(r, fem); }
    long b = n / 1_000_000_000L, r = n % 1_000_000_000L; String head = b == 1 ? "um bilhão" : below1000(b, false) + " bilhões";
    return r == 0 ? head : head + join(r) + cardinal(r, fem);
  }
  /** «e» перед последней группой, если она меньше ста или круглые сотни (и то же для круглых тысяч после миллиона). */
  static String join(long rest) {
    if (rest < 1000) return rest < 100 || rest % 100 == 0 ? " e " : " ";
    if (rest % 1000 != 0) return " ";
    long k = rest / 1000; return k < 100 || k % 100 == 0 ? " e " : " ";
  }
  static String below1000(long n, boolean fem) {
    if (n == 100) return "cem";
    long h = n / 100, r = n % 100; StringBuilder b = new StringBuilder();
    if (h > 0) { String hs = HUNDREDS[(int) h]; if (fem && h >= 2) hs = hs.substring(0, hs.length() - 2) + "as"; b.append(hs); if (r > 0) b.append(" e "); }
    if (r > 0 || n == 0) b.append(below100(r, fem));
    return b.toString();
  }
  static String below100(long n, boolean fem) {
    if (n < 20) return fem && n == 1 ? "uma" : fem && n == 2 ? "duas" : ONES[(int) n];
    long t = n / 10, u = n % 10; return TENS[(int) t] + (u > 0 ? " e " + below100(u, fem) : "");
  }

  /** Число записью («1.250», «2,5», «20») словами: точка между группами по три — разделитель тысяч, запятая — vírgula. */
  static String number(String s, boolean fem) {
    String[] p = s.split(",", -1);
    String whole = p[0].replace(".", "");
    if (whole.length() > 9) return digits(whole);        // телефон, код: по одной цифре; числа до 999 миллионов — числом
    String w = whole.isEmpty() ? "zero" : cardinal(Long.parseLong(whole), fem);
    if (p.length < 2 || p[1].isEmpty()) return w;
    String frac = p[1];
    return w + " vírgula " + (frac.charAt(0) == '0' ? digits(frac) : cardinal(Long.parseLong(frac), false));
  }
  /** По одной цифре. */
  static String digits(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) if (c >= '0' && c <= '9') { if (b.length() > 0) b.append(' '); b.append(ONES[c - '0']); }
    return b.toString();
  }
  /** Деньги: целое — reais, дробь — centavos. */
  static String money(String whole, String cents) {
    long r = Long.parseLong(whole.replace(".", ""));
    long c = cents == null || cents.isEmpty() ? 0 : Long.parseLong(cents.length() == 1 ? cents + "0" : cents);
    String rs = r == 0 ? null : cardinal(r, false) + (r == 1 ? " real" : " reais");
    String cs = c == 0 ? null : cardinal(c, false) + (c == 1 ? " centavo" : " centavos");
    return rs == null ? (cs == null ? "zero reais" : cs) : cs == null ? rs : rs + " e " + cs;
  }
  /** Время: 8:30 → oito e trinta, 8:00 → oito horas, 8h → oito horas, 1:05 → uma e cinco. */
  static String time(String h, String m) {
    long hh = Long.parseLong(h), mm = m == null || m.isEmpty() ? 0 : Long.parseLong(m);
    String hs = cardinal(hh, true);
    return mm == 0 ? hs + (hh == 1 ? " hora" : " horas") : hs + " e " + cardinal(mm, false);
  }

  /** Женский ли род у слова, которое следует за числом: на -a/-as, кроме dia(s) и слов на -ma (problema, sistema). */
  static boolean feminine(String next) {
    if (next == null) return false;
    String w = next.toLowerCase(Locale.ROOT);
    if (w.endsWith("s") && w.length() > 1) w = w.substring(0, w.length() - 1);
    return w.endsWith("a") && !w.endsWith("ma") && !w.equals("dia");
  }

  /** Целая часть — цифры с точками-разделителями тысяч; буква вплотную перед числом («A1») — не число. */
  static final String INT = "(?<![\\p{L}\\d])(\\d+(?:\\.\\d{3})*)";
  static final Pattern NUM = Pattern.compile(
      "R\\$\\s?(\\d+(?:\\.\\d{3})*)(?:,(\\d{1,2}))?"                           // 1,2: деньги знаком
      + "|" + INT + "(?:,(\\d{1,2}))?\\s*(reais|real|centavos|centavo)\\b"     // 3,4,5: деньги словом
      + "|(?<![\\p{L}\\d])(\\d{1,2})[h:](\\d{2})\\b"                           // 6,7: время
      + "|(?<![\\p{L}\\d])(\\d{1,2})h\\b"                                       // 8: часы
      + "|(?<![\\p{L}\\d])(\\d{5})-(\\d{3})\\b"                                 // 9,10: индекс
      + "|" + INT.replace("(\\d+", "(\\d+").replaceFirst("\\)$", "(?:,\\\\d+)?)") + "\\s?%"   // 11: проценты
      + "|" + INT.replaceFirst("\\)$", "(?:,\\\\d+)?)"));                       // 12: число
  static final Pattern NEXT_WORD = Pattern.compile("^[\\s,.;:!?)]*(\\p{L}+)");

  /** Как произносится найденное число; next — слово за ним (род). */
  static String spoken(Matcher m, String next) {
    if (m.group(1) != null) return money(m.group(1), m.group(2));
    if (m.group(3) != null) {
      String unit = m.group(5).toLowerCase(Locale.ROOT);
      if (unit.startsWith("centavo")) return cardinal(Long.parseLong(m.group(3).replace(".", "")), false) + " " + unit;
      return money(m.group(3), m.group(4));
    }
    if (m.group(6) != null) return time(m.group(6), m.group(7));
    if (m.group(8) != null) return time(m.group(8), null);
    if (m.group(9) != null) return digits(m.group(9)) + " " + digits(m.group(10));
    if (m.group(11) != null) return number(m.group(11), false) + " por cento";
    return number(m.group(12), feminine(next));
  }

  /** Текст с числами словами — для озвучки. */
  public static String ptWords(String text) {
    if (text == null || text.indexOf('%') < 0 && !hasDigit(text)) return text;
    Matcher m = NUM.matcher(text); StringBuilder b = new StringBuilder(); int at = 0;
    while (m.find()) {
      Matcher nw = NEXT_WORD.matcher(text.substring(m.end()));
      b.append(text, at, m.start()).append(spoken(m, nw.find() ? nw.group(1) : null)); at = m.end();
    }
    return b.append(text.substring(at)).toString();
  }
  static boolean hasDigit(String s) { for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true; return false; }

  /** Куски для экрана с транскрипцией: {как написано, как сказать}. Число со своим знаком денег, временем или
   *  единицей — один кусок («R$ 20,50» → «vinte reais e cinquenta centavos»), остальные слова — по одному, как есть;
   *  знаки препинания остаются при своём куске. */
  public static List<String[]> tokens(String text) {
    List<String[]> out = new ArrayList<>();
    if (text == null) return out;
    Matcher m = NUM.matcher(text); int at = 0;
    while (m.find()) {
      plain(text.substring(at, m.start()), out);
      Matcher nw = NEXT_WORD.matcher(text.substring(m.end()));
      out.add(new String[]{text.substring(m.start(), m.end()), spoken(m, nw.find() ? nw.group(1) : null)});
      at = m.end();
    }
    plain(text.substring(at), out);
    return out;
  }
  /** Обычные слова — по одному; знаки, прилипшие к предыдущему куску (без пробела), — к нему. */
  static void plain(String s, List<String[]> out) {
    if (s.isEmpty()) return;
    int i = 0;
    if (!out.isEmpty() && !Character.isWhitespace(s.charAt(0))) {
      while (i < s.length() && !Character.isWhitespace(s.charAt(i)) && !Character.isLetterOrDigit(s.charAt(i))) i++;
      if (i > 0) { String[] last = out.get(out.size() - 1); last[0] += s.substring(0, i); }
    }
    for (String w : s.substring(i).trim().split("\\s+")) if (!w.isEmpty()) out.add(new String[]{w, w});
  }
}
