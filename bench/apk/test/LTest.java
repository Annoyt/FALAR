package dev.agenttranslator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Надписи по-португальски (L, bench/apk/ui_pt.txt).
 *  L1 — сам L: выбор языка (как в системе, вручную, чужой язык телефона — русский), числа со словом,
 *       вставки, разбор файла словаря.
 *  L2 — словарь против кода: у каждого ключа L.t/L.f/L.n/L.k из src/ есть перевод, у каждого пункта
 *       whatsnew.txt — тоже; вставки (%s, %d, %.1f…) те же; лишних строк и повторов нет; склейки
 *       с переменной внутри L.t нет (её словарь не узнает).
 *  L3 — в экранах (MainActivity, Screen) нет русских строк мимо L, кроме журнала (log, onLog,
 *       journal, tsv — или строка с пометкой «// журнал»), сравнений (equals, startsWith…) и строк,
 *       русских нарочно (пометка «// по-русски»: название языка, строка разговора в образце).
 *  Запуск: bash bench/apk/test.sh. */
public class LTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }

  public static int run(String apkDir) throws Exception {
    fails = 0; checks = 0;
    String wasLang = L.lang; Map<String, String> wasDict = L.pt;
    try {
      self();
      File a = new File(apkDir);
      dictionary(new File(a, "src/dev/agenttranslator"), new File(a, "ui_pt.txt"), new File(a, "whatsnew.txt"));
    } finally { L.lang = wasLang; L.use(wasDict); }
    System.out.println(fails == 0 ? "L: " + checks + " проверок, все прошли" : "L: провалов " + fails + " из " + checks);
    return fails;
  }

  // ---- L1 ---------------------------------------------------------------------------------

  static void self() throws Exception {
    eq(L.resolve("", "pt"), L.PT, "L1 как в системе: телефон по-португальски");
    eq(L.resolve("", "ru"), L.RU, "L1 как в системе: телефон по-русски");
    eq(L.resolve("", "en"), L.RU, "L1 телефон по-английски — русский (владелец 04.10)");
    eq(L.resolve("", "es"), L.RU, "L1 телефон по-испански — русский");
    eq(L.resolve("", null), L.RU, "L1 язык телефона неизвестен — русский");
    eq(L.resolve("ru", "pt"), L.RU, "L1 выбран русский на португальском телефоне");
    eq(L.resolve("pt", "ru"), L.PT, "L1 выбран португальский на русском телефоне");
    eq(L.resolve("xx", "PT"), L.PT, "L1 непонятный выбор — как в системе");

    L.use(L.parse(new java.io.StringReader("# заметка\n\nСлушать\tOuvir\nСкачать %s МБ\tBaixar %s MB\nфраза|фразы|фраз\tfrase|frases\nДве\\nстроки\tDuas\\nlinhas\n")));
    L.lang = L.RU;
    eq(L.t("Слушать"), "Слушать", "L1 по-русски строка — сама себе");
    eq(L.f("Скачать %s МБ", "1,5"), "Скачать 1,5 МБ", "L1 вставка по-русски");
    String[] ru = {L.n(1, "фраза", "фразы", "фраз"), L.n(2, "фраза", "фразы", "фраз"), L.n(5, "фраза", "фразы", "фраз"),
        L.n(11, "фраза", "фразы", "фраз"), L.n(21, "фраза", "фразы", "фраз"), L.n(22, "фраза", "фразы", "фраз"), L.n(112, "фраза", "фразы", "фраз")};
    eq(String.join(",", ru), "фраза,фразы,фраз,фраз,фраза,фразы,фраз", "L1 русские формы 1, 2, 5, 11, 21, 22, 112");
    L.lang = L.PT;
    eq(L.t("Слушать"), "Ouvir", "L1 по-португальски — из словаря");
    eq(L.f("Скачать %s МБ", "1,5"), "Baixar 1,5 MB", "L1 вставка по-португальски");
    eq(L.t("Две\nстроки"), "Duas\nlinhas", "L1 \\n в словаре — перевод строки");
    eq(L.n(1, "фраза", "фразы", "фраз") + "," + L.n(0, "фраза", "фразы", "фраз") + "," + L.n(21, "фраза", "фразы", "фраз"),
        "frase,frases,frases", "L1 португальские формы: одна только при 1");
    L.missing.clear();
    eq(L.t("Нет в словаре"), "Нет в словаре", "L1 перевода нет — остаётся русская строка, не пусто");
    ok(L.missing.contains("Нет в словаре"), "L1 ключ без перевода запомнен для стенда");
    eq(String.join("|", L.t(new String[]{"Слушать", "RU"})), "Ouvir|RU", "L1 подписи списком");
    boolean thrown = false;
    try { L.parse(new java.io.StringReader("без табуляции\n")); } catch (java.io.IOException e) { thrown = e.getMessage().contains(":1:"); }
    ok(thrown, "L1 строка словаря без табуляции — ошибка с номером строки");
  }

  // ---- L2, L3 -----------------------------------------------------------------------------

  static void dictionary(File src, File dictFile, File whatsnew) throws Exception {
    List<String[]> entries;
    try (java.io.Reader r = Files.newBufferedReader(dictFile.toPath(), StandardCharsets.UTF_8)) { entries = L.entries(r); }
    Map<String, String[]> dict = new LinkedHashMap<>();
    for (String[] e : entries) {
      ok(!dict.containsKey(e[0]), "L2 ui_pt.txt:" + e[2] + ": ключ повторяется — «" + cut(e[0]) + "» (первый — строка " + (dict.containsKey(e[0]) ? dict.get(e[0])[2] : "") + ")");
      ok(!e[1].trim().isEmpty(), "L2 ui_pt.txt:" + e[2] + ": пустой перевод «" + cut(e[0]) + "»");
      dict.putIfAbsent(e[0], e);
    }
    // ключи из кода
    Map<String, String> keys = new LinkedHashMap<>();
    File[] fs = src.listFiles((d, n) -> n.endsWith(".java"));
    Arrays.sort(fs);
    for (File f : fs) {
      Scan s = new Scan(f.getName(), new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      s.run();
      for (String e : s.errors) ok(false, "L2 " + e);
      for (Map.Entry<String, String> k : s.keys.entrySet()) if (cyr(k.getKey())) keys.putIfAbsent(k.getKey(), k.getValue());
      if (f.getName().equals("MainActivity.java") || f.getName().equals("Screen.java"))
        for (String b : s.bare) ok(false, "L3 русская строка мимо L — " + b + ": оберните в L.t(…)/L.f(…) и добавьте перевод в ui_pt.txt"
            + " (журнал — log/onLog/journal или пометка «// журнал»; нарочно по-русски — «// по-русски»)");
    }
    // пункты «Что нового»
    for (String line : Files.readAllLines(whatsnew.toPath(), StandardCharsets.UTF_8))
      if (line.startsWith("+ ") || line.startsWith("* ")) keys.putIfAbsent(line.substring(2).trim(), "whatsnew.txt");
    ok(keys.size() > 300, "L2 ключей в коде найдено " + keys.size() + " — разбор кода работает");
    for (Map.Entry<String, String> k : keys.entrySet()) {
      String[] e = dict.get(k.getKey());
      if (e == null) { ok(false, "L2 нет перевода (" + k.getValue() + "): «" + L.escape(k.getKey()) + "» — добавьте строку в bench/apk/ui_pt.txt"); continue; }
      checks++;
      if (k.getKey().indexOf('|') > 0 && k.getKey().split("\\|", -1).length == 3)
        ok(e[1].split("\\|", -1).length == 2, "L2 ui_pt.txt:" + e[2] + ": число со словом — две формы через «|»: «" + e[1] + "»");
      else eq(specs(e[1]), specs(k.getKey()), "L2 ui_pt.txt:" + e[2] + ": вставки в переводе не те, что в «" + cut(k.getKey()) + "»");
    }
    for (Map.Entry<String, String[]> e : dict.entrySet())
      ok(keys.containsKey(e.getKey()), "L2 ui_pt.txt:" + e.getValue()[2] + ": лишний перевод — в коде и whatsnew.txt нет «" + cut(e.getKey()) + "» (строку поменяли? поправьте ключ)");
  }

  static boolean cyr(String s) { for (char c : s.toCharArray()) if ((c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё') return true; return false; }
  static String cut(String s) { s = L.escape(s); return s.length() > 70 ? s.substring(0, 70) + "…" : s; }

  /** Вставки строки формата в порядке: %s, %d, %.1f, %+.0f, %1$s; %% — не вставка. Позиционные — по номеру. */
  static List<String> specs(String s) {
    List<String> out = new ArrayList<>();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("%(\\d+\\$)?([-#+ 0,(]*)(\\d+)?(\\.\\d+)?([a-zA-Z%])").matcher(s);
    int auto = 0;
    while (m.find()) {
      if (m.group(5).equals("%")) continue;
      String pos = m.group(1) != null ? m.group(1).replace("$", "") : String.valueOf(++auto);
      out.add(pos + ":" + m.group(5).toLowerCase(Locale.ROOT));
    }
    Collections.sort(out);
    // повтор одной позиционной вставки допустим («%1$d … %1$d»): сравниваются различные
    return new ArrayList<>(new LinkedHashSet<>(out));
  }

  /** Разбор Java без полного парсера: строки, комментарии, скобки вызовов. Ключи — строковые
   *  константы внутри L.t(…), L.k(…), первого аргумента L.f(…), форм L.n(…); соседние строки через «+»
   *  — один ключ. Голые русские строки вне L и вне журнала — в bare. */
  static final class Scan {
    final String file, src; int i = 0, line = 1;
    final Map<String, String> keys = new LinkedHashMap<>(); final List<String> errors = new ArrayList<>(), bare = new ArrayList<>();
    static final Set<String> JOURNAL = new HashSet<>(Arrays.asList("log", "onLog", "journal", "tsv", "e", "w", "d", "i", "v"));
    static final Set<String> COMPARE = new HashSet<>(Arrays.asList("equals", "equalsIgnoreCase", "startsWith", "endsWith", "contains", "matches", "indexOf", "lastIndexOf"));
    static final class Frame { String kind, name; int arg; List<Object[]> seq = new ArrayList<>(); List<List<Object[]>> args = new ArrayList<>(); int line; }
    final Deque<Frame> frames = new ArrayDeque<>();

    Scan(String file, String src) { this.file = file; this.src = src; }

    void run() {
      int n = src.length();
      String lastIdent = null, lastQual = null;      // предыдущий значимый токен — имя; перед ним «L.»
      String identBeforeDot = null; boolean afterDot = false;
      while (i < n) {
        char c = src.charAt(i);
        if (c == '\n') { line++; i++; continue; }
        if (c == ' ' || c == '\t' || c == '\r') { i++; continue; }
        if (src.startsWith("//", i)) { int j = src.indexOf('\n', i); i = j < 0 ? n : j; continue; }
        if (src.startsWith("/*", i)) { int j = src.indexOf("*/", i + 2); for (int k = i; k < j; k++) if (src.charAt(k) == '\n') line++; i = j + 2; continue; }
        if (c == '"') {
          int j = i + 1; StringBuilder b = new StringBuilder();
          while (src.charAt(j) != '"') {
            char x = src.charAt(j);
            if (x == '\\') {
              char y = src.charAt(j + 1);
              if (y == 'u') { b.append((char) Integer.parseInt(src.substring(j + 2, j + 6), 16)); j += 6; continue; }
              b.append(y == 'n' ? '\n' : y == 't' ? '\t' : y == 'r' ? '\r' : y == 'b' ? '\b' : y == 'f' ? '\f' : y == '0' ? '\0' : y); j += 2; continue;
            }
            b.append(x); j++;
          }
          token("lit", b.toString());
          i = j + 1; lastIdent = null; afterDot = false; continue;
        }
        if (c == '\'') { int j = i + 1; while (src.charAt(j) != '\'') j += src.charAt(j) == '\\' ? 2 : 1; i = j + 1; token("other", "'"); lastIdent = null; afterDot = false; continue; }
        if (Character.isJavaIdentifierStart(c)) {
          int j = i; while (j < n && Character.isJavaIdentifierPart(src.charAt(j))) j++;
          String id = src.substring(i, j); i = j;
          lastQual = afterDot ? identBeforeDot : null; lastIdent = id; afterDot = false;
          token("id", id); continue;
        }
        if (Character.isDigit(c)) {
          int j = i; while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '.' || src.charAt(j) == '_')) j++;
          i = j; token("other", "0"); lastIdent = null; afterDot = false; continue;
        }
        if (c == '.') {
          i++;
          if (lastIdent != null) { identBeforeDot = lastIdent; afterDot = true; } else { afterDot = false; token("other", "."); }
          lastIdent = null; continue;
        }
        if (c == '+') {
          if (i + 1 < n && (src.charAt(i + 1) == '+' || src.charAt(i + 1) == '=')) { i += 2; token("other", "+"); }
          else { i++; token("plus", "+"); }
          lastIdent = null; afterDot = false; continue;
        }
        if (c == '(') {
          Frame f = new Frame(); f.line = line; f.name = lastIdent;
          if ("L".equals(lastQual) && lastIdent != null && Arrays.asList("t", "f", "n", "k").contains(lastIdent)) f.kind = "L." + lastIdent;
          else if (lastIdent != null && !Arrays.asList("if", "for", "while", "switch", "catch", "synchronized", "return").contains(lastIdent)) f.kind = "call";
          else f.kind = "group";
          frames.push(f); i++; lastIdent = null; afterDot = false; continue;
        }
        if (c == ')') {
          i++; lastIdent = null; afterDot = false;
          if (frames.isEmpty()) continue;
          Frame f = frames.pop();
          if (f.kind.startsWith("L.")) { f.args.add(f.seq); finish(f); }
          continue;
        }
        if (c == ',' && !frames.isEmpty() && !frames.peek().kind.equals("group")) {
          Frame f = frames.peek(); f.args.add(f.seq); f.seq = new ArrayList<>(); f.arg++; i++; lastIdent = null; afterDot = false; continue;
        }
        i++; token("other", String.valueOf(c)); lastIdent = null; afterDot = false;
      }
    }

    /** Токен идёт в последовательность ближайшего не-группового кадра, если это L-кадр; русская
     *  строка вне всех L-кадров — голая, если её не держит журнал или сравнение. */
    void token(String type, String val) {
      Frame owner = null;
      for (Frame f : frames) if (!f.kind.equals("group")) { owner = f; break; }
      if (owner != null && owner.kind.startsWith("L.")) owner.seq.add(new Object[]{type, val, line});
      if (type.equals("lit") && cyr(val)) {
        boolean inL = false, journal = false, compare = false;
        for (Frame f : frames) {
          if (f.kind.startsWith("L.")) inL = true;
          if (f.kind.equals("call") && JOURNAL.contains(f.name)) journal = true;
        }
        Frame top = null; for (Frame f : frames) if (!f.kind.equals("group")) { top = f; break; }
        if (top != null && top.kind.equals("call") && COMPARE.contains(top.name)) compare = true;
        int ls = src.lastIndexOf('\n', Math.max(0, i - 1)) + 1, le = src.indexOf('\n', i); if (le < 0) le = src.length();
        String ln = src.substring(ls, le);
        boolean marked = ln.contains("// журнал") || ln.contains("· журнал") || ln.contains("// по-русски");
        if (!inL && !journal && !compare && !marked) bare.add(file + ":" + line + " «" + cut(val) + "»");
      }
    }

    void finish(Frame f) {
      if (f.kind.equals("L.n")) {
        List<String> forms = new ArrayList<>();
        for (int a = 1; a <= 3 && a < f.args.size(); a++) {
          List<Object[]> s = f.args.get(a);
          if (s.size() == 1 && s.get(0)[0].equals("lit")) forms.add((String) s.get(0)[1]);
        }
        if (forms.size() == 3) keys.putIfAbsent(String.join("|", forms), file + ":" + f.line);
        else errors.add(file + ":" + f.line + ": L.n — три формы должны быть строками в самом вызове");
        return;
      }
      List<Object[]> seq = f.args.isEmpty() ? f.seq : f.args.get(0);
      StringBuilder group = null; Object[] prev = null; int gl = 0;
      for (Object[] t : seq) {
        String ty = (String) t[0];
        if (ty.equals("lit")) {
          if (prev != null && prev[0].equals("plus")) {
            if (group == null) errors.add(file + ":" + t[2] + ": склейка с переменной внутри " + f.kind + "(…) — «" + cut((String) t[1]) + "»: ключ должен быть постоянной строкой, вставки — через L.f");
            else group.append((String) t[1]);
          } else {
            if (group != null) keys.putIfAbsent(group.toString(), file + ":" + gl);
            group = new StringBuilder((String) t[1]); gl = (int) t[2];
          }
        } else if (!ty.equals("plus")) {
          if (prev != null && prev[0].equals("plus") && group != null)
            errors.add(file + ":" + t[2] + ": склейка с переменной внутри " + f.kind + "(…) после «" + cut(group.toString()) + "»: ключ должен быть постоянной строкой, вставки — через L.f");
          if (group != null) { keys.putIfAbsent(group.toString(), file + ":" + gl); group = null; }
        }
        prev = t;
      }
      if (group != null) keys.putIfAbsent(group.toString(), file + ":" + gl);
    }
  }

  public static void main(String[] a) throws Exception {
    System.exit(run(a.length > 0 ? a[0] : "bench/apk") == 0 ? 0 : 1);
  }
}
