package dev.agenttranslator;

import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Правка слов снимка перед переводом по словарю португальских форм (ocr_words_pt.txt в APK).
 *  Распознаватель теряет узкие буквы и ударения («vnagre», «camim», «aicionados», «días»), а
 *  переводчик на таком слове выдумывает: «краситель камыш» вместо «кармин», «смузи» вместо
 *  «добавленных сахаров». Правится только слово, которого нет в словаре, и только так, как
 *  ошибается распознаватель: ударения, пропущенная буква, сдвоенная гласная, слипшиеся слова.
 *  Замены буквы на другую нет — на вывесках она портила больше, чем чинила («CERVA» → «CERVO»).
 *  Эталон и замеры — tools/ocr_words.py; OcrWordsTest сверяет с ним строку в строку. */
public class OcrWords {
  static final int MIN_FREQ = 2, ACCENT_MIN = 3, TITLE_MIN = 50, MIN_LEN = 4, SPLIT_LEN = 7, SPLIT_PART = 10, SPLIT_GEO = 30;
  static final double SPLIT3 = 150.0;
  /** Абзац снимка с долей знакомых слов ниже этой — не португальский и не переводится: английский
   *  абзац таблички набирает 0,2–0,33, список фамилий — 0–0,33, искажённый состав этикетки — 0,45. */
  public static final double LANG_MIN = 0.40;
  static final Set<String> FUNC2 = new HashSet<>(Arrays.asList("de", "da", "do", "as", "os", "em", "no", "na", "um", "ao"));
  // Буквы — как [^\W\d_] в Python: буквы и числовые знаки не из цифр («²»).
  static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{Nl}\\p{No}]+"),
      CAMEL = Pattern.compile("([\\p{L}\\p{Nl}\\p{No}]*[a-zà-öø-ÿ])([A-ZÀ-ÖØ-Þ][a-zà-öø-ÿ]+)");

  /** Формы по возрастанию формы без ударений, внутри — по убыванию частоты (так и лежат в файле).
   *  name — имя (N), english — английское служебное слово (E): известно, но не португальское. */
  final String[] plains, forms; final int[] freq; final boolean[] name, english;

  OcrWords(List<String> fs, List<Integer> fq, List<Boolean> nm, List<Boolean> en) {
    int n = fs.size(); Integer[] ord = new Integer[n]; String[] pl = new String[n];
    for (int i = 0; i < n; i++) { ord[i] = i; String p = plain(fs.get(i)); pl[i] = p.equals(fs.get(i)) ? fs.get(i) : p; }
    boolean sorted = true;
    for (int i = 1; i < n && sorted; i++) {
      int c = pl[i - 1].compareTo(pl[i]);
      sorted = c < 0 || (c == 0 && fq.get(i - 1) >= fq.get(i));
    }
    if (!sorted) Arrays.sort(ord, (a, b) -> { int c = pl[a].compareTo(pl[b]); if (c != 0) return c;
      c = Integer.compare(fq.get(b), fq.get(a)); return c != 0 ? c : fs.get(a).compareTo(fs.get(b)); });
    plains = new String[n]; forms = new String[n]; freq = new int[n]; name = new boolean[n]; english = new boolean[n];
    for (int i = 0; i < n; i++) { int k = ord[i]; plains[i] = pl[k]; forms[i] = fs.get(k); freq[i] = fq.get(k); name[i] = nm.get(k); english[i] = en.get(k); }
  }

  /** Строки «форма частота [N][E]»; N — имя (в корпусе чаще с заглавной не в начале предложения),
   *  E — английское служебное слово. */
  public static OcrWords load(InputStream in) throws IOException {
    List<String> fs = new ArrayList<>(80_000); List<Integer> fq = new ArrayList<>(80_000);
    List<Boolean> nm = new ArrayList<>(80_000), en = new ArrayList<>(80_000);
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16)) {
      String line;
      while ((line = r.readLine()) != null) {
        String[] p = line.trim().split(" ");
        if (p.length < 2) continue;
        try { fq.add(Integer.parseInt(p[1])); } catch (NumberFormatException e) { continue; }
        String fl = p.length > 2 ? p[2] : "";
        fs.add(p[0]); nm.add(fl.indexOf('N') >= 0); en.add(fl.indexOf('E') >= 0);
      }
    }
    return new OcrWords(fs, fq, nm, en);
  }
  public int size() { return forms.length; }

  static String plain(String s) {
    String d = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
    StringBuilder b = new StringBuilder(d.length());
    for (int i = 0; i < d.length(); i++) { char c = d.charAt(i); if (Character.getType(c) != Character.NON_SPACING_MARK) b.append(c); }
    return b.toString();
  }
  static boolean isUpper(String s) { return s.equals(s.toUpperCase(Locale.ROOT)) && !s.equals(s.toLowerCase(Locale.ROOT)); }

  /** Первая запись с этой формой без ударений (самая частая) или -1. */
  int find(String p) {
    int lo = 0, hi = plains.length;
    while (lo < hi) { int m = (lo + hi) >>> 1; if (plains[m].compareTo(p) < 0) lo = m + 1; else hi = m; }
    return lo < plains.length && plains[lo].equals(p) ? lo : -1;
  }
  int exact(String w) {
    String p = plain(w);
    for (int i = find(p); i >= 0 && i < plains.length && plains[i].equals(p); i++) if (forms[i].equals(w)) return i;
    return -1;
  }
  public boolean known(String w) { return find(plain(w)) >= 0; }
  /** Частота слова или, если его нет, самой частой формы с теми же буквами без ударений. */
  int count(String w) { int i = exact(w); if (i < 0) i = find(plain(w)); return i < 0 ? 0 : freq[i]; }
  /** Обычное слово языка (не имя) — для регистра вывески (TextRules.unshoutSign). */
  public boolean common(String w) {
    String l = w.toLowerCase(Locale.ROOT); int i = exact(l); if (i < 0) i = find(plain(l));
    return i >= 0 && freq[i] > 0 && !name[i] && !english[i];
  }

  boolean target(int i, boolean capital, int fmin) { return freq[i] >= fmin && (capital || !name[i]) && !english[i]; }

  /** Замена для слова w (строчными) или null. */
  String best(String w, boolean title, boolean capital) {
    if (exact(w) >= 0) return null;
    String p = plain(w); int fmin = title ? TITLE_MIN : MIN_FREQ;
    int a = p.length() >= 3 ? find(p) : -1;
    if (a >= 0) return target(a, capital, Math.max(ACCENT_MIN, fmin)) ? forms[a] : null;
    if (p.length() < MIN_LEN) return null;
    int bc = -1; String bf = null;
    for (String e : edits(p))
      for (int i = find(e); i >= 0 && i < plains.length && plains[i].equals(e); i++)
        if (target(i, capital, fmin) && (bf == null || freq[i] > bc || (freq[i] == bc && forms[i].compareTo(bf) < 0))) { bc = freq[i]; bf = forms[i]; }
    return bf;
  }

  /** Чем слово могло быть до распознавания: плюс буква где угодно, минус сдвоенная гласная. */
  static List<String> edits(String p) {
    List<String> out = new ArrayList<>(27 * (p.length() + 1));
    for (int i = 0; i <= p.length(); i++) {
      String a = p.substring(0, i), b = p.substring(i);
      if (!b.isEmpty() && "aeiou".indexOf(b.charAt(0)) >= 0
          && ((!a.isEmpty() && a.charAt(a.length() - 1) == b.charAt(0)) || (b.length() > 1 && b.charAt(1) == b.charAt(0))))
        out.add(a + b.substring(1));
      for (char ch = 'a'; ch <= 'z'; ch++) out.add(a + ch + b);
    }
    return out;
  }

  /** Часть разбивки с ударениями по словарю: «NAOCONTEM» → «NÃO CONTÉM». */
  String accent(String x) {
    String lx = x.toLowerCase(Locale.ROOT);
    if (exact(lx) >= 0) return x;
    int i = find(plain(lx));
    return i >= 0 ? caseAs(x, forms[i]) : x;
  }

  int part(String s) {
    if (s.length() < 2) return 0;
    if (s.length() == 2) return FUNC2.contains(s) ? count(s) : 0;
    int i = exact(s);
    if (i >= 0 && (name[i] || english[i])) return 0;
    int c = count(s);
    return c >= SPLIT_PART ? c : 0;
  }

  /** Слипшиеся слова: «EstudantesCarteira» по смене регистра; прописное или строчное слово от семи
   *  букв — на две-три части из частых слов (служебные из двух букв — тоже часть). */
  List<String> split(String t) {
    Matcher m = CAMEL.matcher(t);
    if (m.matches() && count(m.group(1).toLowerCase(Locale.ROOT)) >= 3 && count(m.group(2).toLowerCase(Locale.ROOT)) >= 3)
      return Arrays.asList(m.group(1), m.group(2));
    String p = t.toLowerCase(Locale.ROOT); int n = p.length();
    if ((Character.isUpperCase(t.charAt(0)) && !isUpper(t)) || n < SPLIT_LEN || known(p)) return null;
    double bs = -1; List<String> bp = null;
    for (int i = 2; i < n - 1; i++) {
      int fa = part(p.substring(0, i));
      if (fa == 0) continue;
      int fb = part(p.substring(i));
      if (fb != 0 && (bp == null || (double) fa * fb > bs)) { bs = (double) fa * fb; bp = Arrays.asList(t.substring(0, i), t.substring(i)); }
      for (int j = i + 2; j < n - 1; j++) {
        int f2 = part(p.substring(i, j)), f3 = part(p.substring(j));
        if (f2 != 0 && f3 != 0 && (bp == null || (double) fa * f2 * f3 / SPLIT3 > bs)) {
          bs = (double) fa * f2 * f3 / SPLIT3; bp = Arrays.asList(t.substring(0, i), t.substring(i, j), t.substring(j));
        }
      }
    }
    if (bp == null) return null;
    double prod = 1;
    for (String x : bp) prod *= count(x.toLowerCase(Locale.ROOT));
    return prod >= Math.pow(SPLIT_GEO, bp.size()) ? bp : null;
  }

  static String caseAs(String src, String dst) {
    if (isUpper(src) && src.length() > 1) return dst.toUpperCase(Locale.ROOT);
    if (Character.isUpperCase(src.charAt(0))) return dst.substring(0, 1).toUpperCase(Locale.ROOT) + dst.substring(1);
    return dst;
  }

  /** Текст с исправленными словами; changes (если не null) — пары «было, стало». */
  public String fix(String text, List<String[]> changes) {
    Matcher m = TOKEN.matcher(text); StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String t = m.group(), lw = t.toLowerCase(Locale.ROOT), r = null;
      if (lw.length() >= 3) {
        boolean capital = Character.isUpperCase(t.charAt(0)), title = capital && !isUpper(t);
        String b = best(lw, title, capital);
        if (b != null && !b.equals(lw)) r = caseAs(t, b);
        else if (!known(lw)) {
          List<String> s = split(t);
          if (s != null) { StringBuilder j = new StringBuilder(); for (String x : s) j.append(j.length() > 0 ? " " : "").append(accent(x)); r = j.toString(); }
        }
      }
      if (r != null && changes != null) changes.add(new String[]{t, r});
      m.appendReplacement(sb, Matcher.quoteReplacement(r != null ? r : t));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** Доля известных слов от трёх букв; -2 — слов меньше двух, судить не по чему. */
  public double share(String text) {
    int n = 0, k = 0; Matcher m = TOKEN.matcher(text);
    while (m.find()) {
      String w = m.group(); if (w.length() < 3) continue; n++;
      String l = w.toLowerCase(Locale.ROOT); int e = exact(l);
      if (known(l) && !(e >= 0 && english[e])) k++;
    }
    return n < 2 ? -2 : k / (double) n;
  }
}
