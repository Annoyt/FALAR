package dev.agenttranslator;

import java.io.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import org.json.*;

/**
 * Список своих слов: имена, которые распознавание стабильно калечит — отель, улица,
 * имя собеседника, название блюда. Решает три задачи одной записью:
 *   1) находит искажённое имя в выводе ASR и восстанавливает каноническое написание,
 *   2) прячет его от MT, чтобы «Fogo de Chão» не стало «Половым огнём»,
 *   3) подставляет в перевод сторону на нужном языке, чтобы синтез это прочитал.
 *
 * Сопоставление фонетическое: сравниваются согласные скелеты (гласные выброшены,
 * согласные сведены в классы п/б, т/д, к/г, с/з, ш/ж). На настоящих ошибках parakeet
 * это находит «Копакабана» в «Капокапанная» и «Авенида Паулиста» в «Авенедополиста»,
 * где расстояние редактирования по буквам не даёт ничего. Пороги подобраны замером
 * (tools/wordlist_match.py): фонетика >= 0.75 И орфография >= 0.45 — ноль ложных
 * срабатываний на контрольном наборе. Промах безобиден, ложная подстановка имени — нет.
 */
public class WordList {
  public static final double THR_PHON = 0.75, THR_ORTH = 0.45;
  public static final int MIN_SKEL = 3;

  public static class Entry {
    public final String pt, ru;
    public Entry(String pt, String ru) { this.pt = pt; this.ru = ru; }
    public String side(String lang) { return lang.equals("pt") ? pt : ru; }
  }
  public static class Hit {
    public final Entry e; public final String found; public final double phon, orth;
    Hit(Entry e, String found, double p, double o) { this.e = e; this.found = found; phon = p; orth = o; }
  }

  /** Копирующий при записи: список читают потоки перевода, уточнителя и облака, а пишет главный. */
  final File dir; public final List<Entry> entries = new java.util.concurrent.CopyOnWriteArrayList<>();
  /** Обычные слова языка из корпуса Tatoeba — защита от подмены обычного слова именем. */
  final Map<String, Set<String>> common = new HashMap<>(), stems = new HashMap<>();
  public int commonCount = 0;
  static final int STEM = 5, STEM_MIN_FREQ = 10;   // основы берём только у частых слов:
  // редкое слово из корпуса («коперниций») иначе прикроет собой искажённое имя («коперенью»)

  public WordList(File modelsDir) { dir = modelsDir; load(); loadCommon(); }

  void loadCommon() {
    File f = new File(dir, "common_words.txt");
    if (!f.exists()) return;
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 16)) {
      String line;
      while ((line = r.readLine()) != null) {
        int sp = line.indexOf(' '); if (sp < 0) continue;
        int sp2 = line.indexOf(' ', sp + 1); if (sp2 < 0) sp2 = line.length();
        String lang = line.substring(0, sp), w = line.substring(sp + 1, sp2);
        int freq = 0; try { if (sp2 < line.length()) freq = Integer.parseInt(line.substring(sp2 + 1).trim()); } catch (Exception ignore) {}
        common.computeIfAbsent(lang, k -> new HashSet<>(1 << 15)).add(w);
        if (w.length() >= STEM && freq >= STEM_MIN_FREQ)
          stems.computeIfAbsent(lang, k -> new HashSet<>(1 << 15)).add(w.substring(0, STEM));
        commonCount++;
      }
    } catch (Exception e) { e.printStackTrace(); }
  }
  /** Обычное ли это слово языка: само в списке или делит с ним основу (русский флективен). */
  boolean isCommon(String w, String lang) {
    Set<String> c = common.get(lang); if (c == null) return false;
    String p = plain(w).replaceAll("[^\\p{L}]", "");
    if (p.length() < 3) return true;                       // слишком коротко, чтобы судить
    if (c.contains(p)) return true;
    Set<String> st = stems.get(lang);
    return st != null && p.length() >= STEM && st.contains(p.substring(0, STEM));
  }

  // ---- фонетика
  static final Map<Character, Character> CLS = new HashMap<>();
  static {
    String[] g = {"P", "pbпб", "T", "tdтд", "K", "kgcqкгх", "F", "fvфв", "S", "szçсзц",
                  "X", "xjшжщч", "M", "mм", "N", "nnñнň", "L", "lл", "R", "rр"};
    for (int i = 0; i < g.length; i += 2) for (char c : g[i + 1].toCharArray()) CLS.put(c, g[i].charAt(0));
  }
  static String plain(String s) {
    s = Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFD);
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) if (Character.getType(c) != Character.NON_SPACING_MARK) b.append(c);
    return b.toString();
  }
  static String skeleton(String s) {
    String t = plain(s);
    String[][] di = {{"lh", "L"}, {"nh", "N"}, {"ch", "X"}, {"ss", "S"}, {"rr", "R"},
                     {"qu", "K"}, {"sc", "S"}, {"ph", "F"}, {"th", "T"}};
    for (String[] d : di) t = t.replace(d[0], d[1]);
    StringBuilder b = new StringBuilder();
    for (char c : t.toCharArray()) {
      Character k = CLS.get(c);
      if (k == null) k = (c >= 'A' && c <= 'Z') ? c : null;          // уже свёрнутый диграф
      if (k != null && (b.length() == 0 || b.charAt(b.length() - 1) != k)) b.append((char) k);
    }
    return b.toString();
  }
  static double sim(String a, String b) {
    int m = Math.max(a.length(), b.length());
    return m == 0 ? 0 : 1.0 - Phrasebook.lev(a, b) / (double) m;
  }
  static String letters(String s) { return plain(s).replaceAll("[^\\p{L}\\p{N}]", ""); }

  /** Ищет искажённое имя в тексте; возвращает {начало, конец} по словам или null. */
  int[] locate(String[] words, Entry e, String lang) {
    String target = e.side(lang);
    if (target == null || target.isEmpty()) return null;
    String tp = skeleton(target), to = letters(target);
    if (tp.length() < MIN_SKEL) return null;
    int n = target.split("\\s+").length;
    int[] best = null; double bestScore = 0;
    for (int k = Math.max(1, n - 1); k <= n + 2; k++) {
      for (int i = 0; i + k <= words.length; i++) {
        StringBuilder w = new StringBuilder();
        for (int j = i; j < i + k; j++) { if (w.length() > 0) w.append(' '); w.append(words[j]); }
        String win = w.toString();
        double sp = sim(skeleton(win), tp), so = sim(letters(win), to);
        if (sp >= THR_PHON && so >= THR_ORTH && sp > bestScore) { bestScore = sp; best = new int[]{i, i + k}; }
      }
    }
    return best;
  }

  /**
   * Заменяет найденные имена плейсхолдерами. Проходит ДО обычного маскирования, иначе адресный
   * шаблон перехватит «Rua Augusta» и она останется латиницей посреди русской фразы.
   * Слот получает вид {N_k, каноническое имя, "name:перевод"} — unmask подставит перевод.
   */
  public static class Result {
    public final String masked;     // то, что уходит в словарь фраз и в MT: имена заменены плейсхолдерами
    public final String readable;   // то, что видит человек и получает LLM: имена исправлены, но на месте
    Result(String m, String r) { masked = m; readable = r; }
  }
  public Result apply(String text, String src, String tgt, List<String[]> slots, List<Hit> out) {
    if (entries.isEmpty()) return new Result(text, text);
    for (Entry e : entries) {
      String[] words = text.split("\\s+");
      int[] span = locate(words, e, src);
      if (span == null) continue;
      StringBuilder found = new StringBuilder();
      for (int j = span[0]; j < span[1]; j++) { if (found.length() > 0) found.append(' '); found.append(words[j]); }
      // Точное совпадение принимаем всегда — это и есть имя, его надо спрятать от MT.
      // Приблизительное принимаем, только если хоть одно слово окна необычно для языка:
      // иначе «пятого августа» подменяется улицей, а «fechada» — блюдом.
      boolean exact = letters(found.toString()).equals(letters(e.side(src)));
      if (!exact && commonCount > 0) {
        boolean unusual = false;
        for (int j = span[0]; j < span[1] && !unusual; j++) if (!isCommon(words[j], src)) unusual = true;
        if (!unusual) continue;
      }
      String tail = found.toString().replaceAll("^.*?([.,!?;:]*)$", "$1");   // хвостовая пунктуация остаётся
      String ph = TextRules.ph(slots.size() + 1);
      StringBuilder nb = new StringBuilder();
      for (int j = 0; j < words.length; j++) {
        if (j == span[0]) nb.append(nb.length() > 0 ? " " : "").append(ph).append(tail);
        else if (j > span[0] && j < span[1]) continue;
        else nb.append(nb.length() > 0 ? " " : "").append(words[j]);
      }
      text = nb.toString();
      String rendered = e.side(tgt);
      if (rendered == null || rendered.isEmpty()) rendered = Translit.auto(e.side(src));
      slots.add(new String[]{ph, e.side(src), "name:" + rendered});
      out.add(new Hit(e, found.toString(), 0, 0));
    }
    // Читаемый вариант: те же плейсхолдеры, но заменённые на каноническое имя — в историю и в LLM
    // должен уходить исправленный текст, иначе уточнитель работает по искалеченному распознаванию.
    String readable = text;
    for (String[] sl : slots)
      if (sl[2].startsWith("name:"))
        readable = readable.replaceAll("\\b" + sl[0] + "\\b", Matcher.quoteReplacement(sl[1]));
    return new Result(text, readable);
  }

  // ---- хранение
  File file() { return new File(dir, "wordlist.json"); }
  void load() {
    try {
      File f = file(); if (!f.exists()) return;
      JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
      entries.clear();
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.getJSONObject(i);
        String pt = o.optString("pt", ""), ru = o.optString("ru", "");
        if (pt.isEmpty() && ru.isEmpty()) continue;
        if (pt.isEmpty()) pt = Translit.ruToPt(ru);
        if (ru.isEmpty()) ru = Translit.ptToRu(pt);
        entries.add(new Entry(pt, ru));
      }
    } catch (Exception e) { e.printStackTrace(); }
  }
  public synchronized void add(String pt, String ru) {
    if (pt == null || pt.isEmpty()) pt = Translit.ruToPt(ru);
    if (ru == null || ru.isEmpty()) ru = Translit.ptToRu(pt);
    entries.add(new Entry(pt, ru));
    save();
  }
  synchronized void save() {
    try {
      JSONArray a = new JSONArray();
      for (Entry e : entries) a.put(new JSONObject().put("pt", e.pt).put("ru", e.ru));
      Files.write(file().toPath(), a.toString(1).getBytes("UTF-8"));
    } catch (Exception e) { e.printStackTrace(); }
  }
  /**
   * Похож ли текст на язык lang: доля слов, известных языку. Нужно потому, что parakeet
   * многоязычный и сам определяет язык — фоновая английская речь распознаётся как английская
   * и уходит в португальскую модель перевода, которая уверенно порождает бессмыслицу.
   * Плейсхолдеры и короткие слова не считаются. Возвращает -1, если словаря нет (тогда не судим).
   */
  public double looksLike(String text, String lang) {
    Set<String> c = common.get(lang);
    if (c == null || c.isEmpty()) return -1;
    int known = 0, total = 0;
    for (String w : text.split("[^\\p{L}\\p{N}]+")) {
      if (w.length() < 3 || w.matches("XQ\\d+")) continue;
      total++;
      // без снятия диакритики: словарь построен с ней («não», «português»)
      if (c.contains(w.toLowerCase(Locale.ROOT).replace('ё', 'е'))) known++;
    }
    return total < 2 ? -2 : known / (double) total;      // -2: слов слишком мало, чтобы судить
  }

  /** Короткая реплика, по которой доля не считается. Раньше такие выбрасывались целиком, и вместе
   *  с шумом («Mm», «Yeah») терялись самые частые реплики живого разговора — «Sim», «Да»,
   *  «Obrigado», «Сколько?». Вместо длины судим по составу: все слова должны быть словами языка.
   *  Слова от двух букв, потому что «Oi», «Да», «no» — полноценные слова, а не обрывки. */
  public boolean shortOk(String text, String lang) {
    Set<String> c = common.get(lang);
    if (c == null || c.isEmpty()) return true;           // без словаря не судим, как и looksLike
    int n = 0;
    for (String w : text.split("[^\\p{L}\\p{N}]+")) {
      if (w.length() < 2 || w.matches("XQ\\d+")) continue;
      n++;
      if (!c.contains(w.toLowerCase(Locale.ROOT).replace('ё', 'е'))) return false;
    }
    return n > 0;
  }

  public String stats() { return (entries.isEmpty() ? "свои слова: нет" : "свои слова: " + entries.size())
      + (commonCount > 0 ? " · обычных слов " + commonCount : " · БЕЗ защиты от подмены обычных слов"); }
}
