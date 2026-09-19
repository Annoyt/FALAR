package dev.agenttranslator;

import java.io.*;
import java.util.*;
import org.json.*;

/** Разбор слов для заучивания по накопленным разговорам.
 *
 *  Считаем **португальскую** сторону: это язык, на котором надо заговорить. Слово попадает
 *  в список, только если оно встретилось в разговорах не меньше заданного числа раз — редкое
 *  слово учить рано, его ещё не потребовалось.
 *
 *  Служебные слова («o», «de», «que») выбрасываются по частотному словарю корпуса, а не по
 *  вручную составленному списку: рукописный список пришлось бы держать в голове и он врал бы
 *  на краях. Порог — попадание в верхушку корпусной частотности.
 *
 *  Показываем не голое слово, а пример фразы из собственного разговора: слово в отрыве
 *  от контекста не учится, а фраза уже произносилась и переведена. */
public class Learn {
  public static final int STOP_TOP = 150;      // столько самых частых слов языка считаем служебными

  final Chats chats;
  final File knownFile;
  final Set<String> stop = new HashSet<>();
  /** Слова, помеченные как известные. Отдельный словарь, а не пометка внутри списка изучения:
   *  его надо переживать между запусками и по нему же проводить повторение. */
  public final Set<String> known = new LinkedHashSet<>();
  /** Память о повторениях: сколько раз вспомнил, сколько забыл, когда спрашивали в последний раз.
   *  Без неё повторение спрашивало бы случайное слово, а спрашивать надо давно не встречавшееся
   *  и то, на котором уже спотыкались. */
  public final Map<String, long[]> stat = new HashMap<>();   // слово -> {помню, забыл, когда}
  /** Перевод отдельного слова. Перевод фразы для заучивания не годится: учат «motorhome — дом
   *  на колёсах», а не «мы решили использовать наклейку». Считается один раз и хранится. */
  public final Map<String, String> wordRu = new HashMap<>();
  final File wordsFile;

  public Learn(Chats chats, File modelsDir, File filesDir) {
    this.chats = chats;
    knownFile = new File(filesDir, "known_words.json");
    wordsFile = new File(filesDir, "word_ru.json");
    loadStop(new File(modelsDir, "common_words.txt"));
    loadKnown(); loadWordRu();
  }

  void loadWordRu() {
    try {
      if (!wordsFile.exists()) return;
      JSONObject o = new JSONObject(Chats.read(wordsFile));
      for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); wordRu.put(k, o.optString(k, "")); }
    } catch (Exception ignore) {}
  }
  public synchronized void putWordRu(String w, String ru) {
    wordRu.put(w, ru);
    try { Chats.write(wordsFile, new JSONObject(wordRu).toString()); } catch (Exception ignore) {}
  }
  /** Слова, которые пробовали перевести и не смогли. Без этого экран изучения зацикливался:
   *  перевод не сохранялся, слово снова попадало в «недостающие», и запрос уходил бесконечно. */
  final Set<String> triedRu = new HashSet<>();
  public synchronized void markTriedRu(String w) { triedRu.add(w); }

  public synchronized List<String> missingRu(List<Word> l) {
    List<String> out = new ArrayList<>();
    for (Word w : l) if (!wordRu.containsKey(w.w) && !triedRu.contains(w.w)) out.add(w.w);
    return out;
  }

  void loadKnown() {
    try {
      if (!knownFile.exists()) return;
      String raw = Chats.read(knownFile);
      if (raw.trim().startsWith("[")) {                     // старый формат: просто список слов
        JSONArray a = new JSONArray(raw);
        for (int k = 0; k < a.length(); k++) { known.add(a.getString(k)); stat.put(a.getString(k), new long[]{0, 0, 0}); }
        saveKnown();
        return;
      }
      JSONObject o = new JSONObject(raw);
      for (Iterator<String> it = o.keys(); it.hasNext(); ) {
        String w = it.next(); JSONObject x = o.optJSONObject(w);
        known.add(w);
        stat.put(w, new long[]{x == null ? 0 : x.optLong("ok"), x == null ? 0 : x.optLong("fail"), x == null ? 0 : x.optLong("last")});
      }
    } catch (Exception ignore) {}
  }
  synchronized void saveKnown() {
    try {
      JSONObject o = new JSONObject();
      for (String w : known) {
        long[] st = stat.get(w); if (st == null) st = new long[]{0, 0, 0};
        o.put(w, new JSONObject().put("ok", st[0]).put("fail", st[1]).put("last", st[2]));
      }
      Chats.write(knownFile, o.toString());
    } catch (Exception ignore) {}
  }
  public synchronized void setKnown(String w, boolean yes) {
    if (yes) { known.add(w); if (!stat.containsKey(w)) stat.put(w, new long[]{0, 0, 0}); }
    else { known.remove(w); stat.remove(w); }
    saveKnown();
  }

  /** Что спросить следующим: сначала то, на чём спотыкались, при равенстве — давно не спрошенное.
   *  Слово, которое только что спрашивали, не повторяем сразу. */
  public synchronized String nextReview(String skip) {
    String best = null; long bestScore = Long.MIN_VALUE;
    for (String w : known) {
      if (w.equals(skip) && known.size() > 1) continue;
      long[] st = stat.get(w); if (st == null) st = new long[]{0, 0, 0};
      long age = (System.currentTimeMillis() - st[2]) / 60000;          // минут с прошлого раза
      long score = st[1] * 2000 - st[0] * 500 + Math.min(age, 100000);  // забытое вперёд, знакомое назад
      if (score > bestScore) { bestScore = score; best = w; }
    }
    return best;
  }

  /** Ответ на повторение. «Забыл» возвращает слово в изучение: оно ещё не выучено. */
  public synchronized void review(String w, boolean ok) {
    long[] st = stat.get(w); if (st == null) st = new long[]{0, 0, 0};
    if (ok) st[0]++; else st[1]++;
    st[2] = System.currentTimeMillis();
    stat.put(w, st);
    // Историю повторов не стираем: «забыл» возвращает слово в изучение, но сколько раз его
    // помнили и забывали — это накопленное знание о слове, и оно нужно при следующем заходе.
    if (!ok) known.remove(w);
    saveKnown();
  }
  public synchronized String statOf(String w) {
    long[] st = stat.get(w);
    return st == null ? "" : "помню " + st[0] + " · забыл " + st[1];
  }

  /** Служебные слова — верхушка частотности корпуса. Файл уже лежит рядом: он же используется
   *  для проверки языка, второй копии не заводим. */
  void loadStop(File f) {
    if (!f.exists()) return;
    List<int[]> idx = new ArrayList<>();          // {частота, позиция в списке слов}
    List<String> words = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 16)) {
      String line;
      while ((line = r.readLine()) != null) {
        int sp = line.indexOf(' '); if (sp < 0) continue;
        if (!"pt".equals(line.substring(0, sp))) continue;
        int sp2 = line.indexOf(' ', sp + 1); if (sp2 < 0) continue;
        int freq;
        try { freq = Integer.parseInt(line.substring(sp2 + 1).trim()); } catch (Exception e) { continue; }
        words.add(line.substring(sp + 1, sp2));
        idx.add(new int[]{freq, words.size() - 1});
      }
    } catch (Exception ignore) { return; }
    Collections.sort(idx, (a, b) -> b[0] - a[0]);
    for (int k = 0; k < Math.min(STOP_TOP, idx.size()); k++) stop.add(words.get(idx.get(k)[1]));
  }

  public static class Word {
    public final String w; public final int n; public final String pt, ru;
    Word(String w, int n, String pt, String ru) { this.w = w; this.n = n; this.pt = pt; this.ru = ru; }
  }

  /** Кусок фразы вокруг слова. Реплика бывает монологом на десять строк, и целиком она
   *  не пример, а стена текста: учить надо слово в коротком окружении. */
  static String around(String phrase, String word, int words) {
    String[] p = phrase.split("\\s+");
    int at = -1;
    for (int k = 0; k < p.length && at < 0; k++)
      if (p[k].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "").equals(word)) at = k;
    if (at < 0) return phrase.length() > 90 ? phrase.substring(0, 90) + "…" : phrase;
    int a = Math.max(0, at - words), b = Math.min(p.length, at + words + 1);
    StringBuilder s2 = new StringBuilder();
    if (a > 0) s2.append("… ");
    for (int k = a; k < b; k++) { if (k > a) s2.append(' '); s2.append(p[k]); }
    if (b < p.length) s2.append(" …");
    return s2.toString();
  }

  /** Обрезка по границе предложения, а не по символу: оборванное на полуслове не читается. */
  static String shorten(String s, int max) {
    if (s.length() <= max) return s;
    String cut = s.substring(0, max);
    int dot = Math.max(cut.lastIndexOf('.'), Math.max(cut.lastIndexOf('!'), cut.lastIndexOf('?')));
    if (dot > max / 3) return cut.substring(0, dot + 1);
    int sp = cut.lastIndexOf(' ');
    return (sp > max / 3 ? cut.substring(0, sp) : cut) + "…";
  }

  /** Кэш разбора: отпечаток набора файлов → сырые счётчики и примеры по **всем** словам, без
   *  фильтра известных. Раньше каждый тик ползунка частот перечитывал и разбирал все разговоры
   *  с диска в потоке интерфейса; теперь диск читается один раз на изменение, а фильтры «знаю»
   *  и порог повторов применяются при показе — иначе пометка «знаю» сбрасывала бы кэш. */
  long cacheKey = Long.MIN_VALUE;
  Map<String, int[]> cCount = new HashMap<>();
  Map<String, String[]> cExample = new HashMap<>();

  synchronized void ensureCache() {
    long fp = chats.fingerprint();
    if (fp == cacheKey) return;
    Map<String, int[]> count = new HashMap<>();            // слово -> {сколько раз}
    Map<String, String[]> example = new HashMap<>();       // слово -> {фраза pt, фраза ru}
    for (String[] c : chats.list()) {
      JSONObject o = chats.load(Long.parseLong(c[0]));
      if (o == null) continue;
      JSONArray t = o.optJSONArray("turns");
      for (int k = 0; t != null && k < t.length(); k++) {
        JSONObject x = t.optJSONObject(k);
        if (x == null) continue;
        boolean srcPt = x.optString("dir", "").startsWith("pt");
        String fixed = x.optString("fixed", "");
        String dst = fixed.isEmpty() ? x.optString("dst", "") : fixed;
        String pt = srcPt ? x.optString("src", "") : dst;
        String ru = srcPt ? dst : x.optString("src", "");
        if (pt.isEmpty()) continue;
        for (String w : pt.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
          if (w.length() < 3 || stop.contains(w)) continue;
          int[] c2 = count.get(w);
          if (c2 == null) { count.put(w, new int[]{1}); example.put(w, new String[]{pt, ru}); }
          else {
            c2[0]++;
            String[] ex = example.get(w);                  // держим самый короткий пример: он понятнее
            if (ex != null && pt.length() < ex[0].length() && pt.length() > w.length() + 3) example.put(w, new String[]{pt, ru});
          }
        }
      }
    }
    cCount = count; cExample = example; cacheKey = fp;
  }

  Word word(String w, int n) {
    String[] ex = cExample.get(w);
    // Русскую сторону режем по предложениям соразмерно португальскому куску: целиком она
    // относится ко всей реплике, а показать надо перевод того же места.
    String exPt = ex == null ? "" : around(ex[0], w, 4);
    String exRu = ex == null ? "" : shorten(ex[1], 90);
    return new Word(w, n, exPt, exRu);
  }

  /** Частотные слова по всем разговорам. minCount — сколько раз слово должно встретиться,
   *  чтобы считаться нужным. Известное не учим — фильтр при показе. */
  public synchronized List<Word> top(int minCount, int limit) {
    ensureCache();
    List<Word> out = new ArrayList<>();
    for (Map.Entry<String, int[]> e : cCount.entrySet()) {
      if (e.getValue()[0] < minCount || known.contains(e.getKey())) continue;
      out.add(word(e.getKey(), e.getValue()[0]));
    }
    Collections.sort(out, (a, b) -> b.n - a.n);
    return out.size() > limit ? out.subList(0, limit) : out;
  }

  /** Известные слова как строки списка — из того же кэша, без второго прохода по разговорам. */
  public synchronized List<Word> knownWords() {
    ensureCache();
    List<Word> out = new ArrayList<>();
    for (String w : known) { int[] c = cCount.get(w); out.add(c == null ? new Word(w, 0, "", "") : word(w, c[0])); }
    return out;
  }

  /** Встречалось ли слово в разговорах: перевод слова из облачной пары кладём только таким —
   *  остальное для экрана изучения не существует. */
  public synchronized boolean inCorpus(String w) { ensureCache(); return cCount.containsKey(w); }

  /** Механическая тема: самые частые значимые слова разговора минус служебные. Нужна, когда
   *  облака нет: для [Background Information] уточнителя набора ключевых слов достаточно, и это
   *  не требует ни сети, ни инструктивной модели. Считается по португальской стороне реплик. */
  public synchronized String keywords(List<String[]> turns, int n) {
    Map<String, int[]> c = new HashMap<>();
    for (String[] t : turns) {
      String pt = t[0].startsWith("pt") ? t[1] : t[2];
      for (String w : pt.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
        if (w.length() < 4 || stop.contains(w) || w.matches("xq\\d+")) continue;
        int[] x = c.get(w); if (x == null) c.put(w, new int[]{1}); else x[0]++;
      }
    }
    List<Map.Entry<String, int[]>> l = new ArrayList<>(c.entrySet());
    Collections.sort(l, (a, b) -> b.getValue()[0] - a.getValue()[0]);
    StringBuilder b = new StringBuilder();
    for (int k = 0; k < Math.min(n, l.size()); k++) { if (b.length() > 0) b.append(", "); b.append(l.get(k).getKey()); }
    return b.toString();
  }

  /** Известные слова — для повторения. Порядок сохранения, чтобы свежеотмеченные были в конце. */
  public synchronized List<String> knownList() { return new ArrayList<>(known); }
}
