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
  /** Память о повторениях: сколько раз вспомнил, сколько забыл, когда спрашивали в последний раз, ступень
   *  лесенки интервалов и когда спросить снова (DAYS). История «помню / забыл» не стирается ни промахом, ни
   *  лесенкой: это накопленное знание о слове. */
  public final Map<String, long[]> stat = new HashMap<>();   // ключ -> {помню, забыл, когда, ступень, срок}
  static final int OK = 0, FAIL = 1, LAST = 2, STEP = 3, DUE = 4;
  static long[] fresh() { return new long[]{0, 0, 0, 0, 0}; }
  /** Перевод отдельного слова. Перевод фразы для заучивания не годится: учат «motorhome — дом
   *  на колёсах», а не «мы решили использовать наклейку». Считается один раз и хранится. */
  public final Map<String, String> wordRu = new HashMap<>();
  final File wordsFile;

  public Learn(Chats chats, File modelsDir, File filesDir) {
    this.chats = chats;
    knownFile = new File(filesDir, "known_words.json");
    wordsFile = new File(filesDir, "word_ru.json");
    sentFile = new File(filesDir, "sent_ru.json");
    practiceFile = new File(filesDir, "practice.json");
    loadStop(new File(modelsDir, "common_words.txt"));
    loadKnown(); loadWordRu(); loadSentRu();
  }

  // ---- карточки шага 2 (Cards) --------------------------------------------------------------------

  /** Последние посчитанные карточки: считает их фоновый поток в затишье (buildCards из
   *  TranslatorService.learnIo), экран берёт готовые — даже посреди разговора ничего не пересчитывается. */
  public volatile Cards.Result cards;
  long cardsKey = Long.MIN_VALUE; Set<String> cardsKeep;
  /** Перевод предложения-примера, если перевод реплики не делится на предложения так же
   *  (Cards.Example.ru == null): переводит фон, ключ — нормализованное предложение. Отдельный файл,
   *  чтобы не переводить одно и то же при каждом разборе. */
  public final Map<String, String> sentRu = new HashMap<>();
  final File sentFile;
  final Set<String> triedSent = new HashSet<>();

  /** Своя реплика идёт в учёбу только с улучшенным переводом (решение владельца 03.10): сырой машинный
   *  перевод как образец учит ошибке. */
  static boolean improved(JSONObject x) {
    String by = x.optString("by", "");
    return !x.optString("fixed", "").isEmpty() && (Chats.BY_CLOUD.equals(by) || Chats.BY_LLM.equals(by) || Chats.BY_USER.equals(by));
  }

  /** Реплики всех разговоров для разбора: без снимков, без стенда, свои — только улучшенные. */
  List<Cards.Turn> turnsForCards() {
    List<Cards.Turn> out = new ArrayList<>();
    for (String[] c : chats.list()) {
      long id = Long.parseLong(c[0]);
      JSONObject o = chats.load(id);
      JSONArray t = o == null ? null : o.optJSONArray("turns");
      String name = o == null ? "" : o.optString("name", "");
      for (int k = 0; t != null && k < t.length(); k++) {
        JSONObject x = t.optJSONObject(k);
        if (x == null || x.has("photo") || x.optInt("stand", 0) == 1) continue;
        boolean heard = x.optString("dir", "").startsWith("pt");
        if (!heard && !improved(x)) continue;
        String fixed = x.optString("fixed", "");
        String pt = heard ? x.optString("src", "") : fixed, ru = heard ? (fixed.isEmpty() ? x.optString("dst", "") : fixed) : x.optString("src", "");
        Cards.Turn ct = new Cards.Turn(id, name, x.optLong("at", 0), heard, pt, ru, x.optString("who", ""), x.optString("audio", ""), x.optString("by", ""));
        JSONArray cu = x.optJSONArray("cuts");
        if (cu != null) { ct.cuts = new int[cu.length()]; for (int j = 0; j < cu.length(); j++) ct.cuts[j] = cu.optInt(j); }
        out.add(ct);
      }
    }
    return out;
  }

  /** Посчитать карточки, если разговоры или «Знаю» изменились. Тяжело (читает все разговоры: на Redmi 0,5–0,7 с
   *  на 740 реплик) — только из фонового потока в затишье и без замка Learn: под замком экран, отметивший «знаю»
   *  или ответивший в повторении, ждал бы конца разбора. */
  public Cards.Result buildCards() {
    long fp = chats.fingerprint(); Set<String> keep;
    synchronized (this) {
      if (fp == cardsKey && known.equals(cardsKeep) && cards != null) return cards;
      keep = new HashSet<>(known);
    }
    Cards.Result r = Cards.build(turnsForCards(), stop, 2, keep);
    synchronized (this) { cards = r; cardsKey = fp; cardsKeep = keep; }
    return r;
  }

  /** Перевод примера: свой (предложение в предложение) или фоновый; null — ещё не переведён. */
  public String exampleRu(Cards.Example e) { return e.ru != null ? e.ru : sentRu.get(Phrasebook.norm(e.pt)); }

  /** Перевод карточки: слова и связки — переводом самого слова (word_ru); фразы собеседника — переводом
   *  примера; свои — тем, что вы сказали по-русски. */
  public String cardRu(Cards.Card c) {
    if (c.kind != Cards.PHRASE) return wordRu.get(c.key);
    for (Cards.Example e : c.ex) { String r = exampleRu(e); if (r != null) return r; }
    return null;
  }

  /** Предложения примеров без перевода — фон переведёт их по одному в затишье; не больше limit за раз. */
  public synchronized List<String> missingSentRu(List<Cards.Card> l, int limit) {
    List<String> out = new ArrayList<>();
    for (Cards.Card c : l) for (Cards.Example e : c.ex) {
      if (out.size() >= limit) return out;
      String k = Phrasebook.norm(e.pt);
      if (e.ru == null && !sentRu.containsKey(k) && !triedSent.contains(k) && !out.contains(e.pt)) out.add(e.pt);
    }
    return out;
  }
  public synchronized void putSentRu(String pt, String ru) {
    sentRu.put(Phrasebook.norm(pt), ru);
    try { Chats.write(sentFile, new JSONObject(sentRu).toString()); } catch (Exception ignore) {}
  }
  public synchronized void markTriedSent(String pt) { triedSent.add(Phrasebook.norm(pt)); }
  void loadSentRu() {
    try {
      if (!sentFile.exists()) return;
      JSONObject o = new JSONObject(Chats.read(sentFile));
      for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); sentRu.put(k, o.optString(k, "")); }
    } catch (Exception ignore) {}
  }

  // ---- метрика (решение владельца 03.10, придумана до экрана) --------------------------------------

  /** Помогает ли учёба заговорить — две цифры за неделю: доля слов карточек, распознанных в «Скажите сами»
   *  с первой попытки за день, и сколько фраз человек сказал по-португальски сам в живом разговоре (свой
   *  голос, не прочитано с экрана). Хранится только итог: время, ключ, получилось ли — без звука и без
   *  распознанного текста. Не больше KEEP_EVENTS событий. */
  static final int KEEP_EVENTS = 2000;
  static final long DAY = 86_400_000L, WEEK = 7 * DAY;
  File practiceFile;
  JSONArray events;
  synchronized JSONArray events() {
    if (events != null) return events;
    events = new JSONArray();
    try { if (practiceFile != null && practiceFile.exists()) events = new JSONArray(Chats.read(practiceFile)); } catch (Exception ignore) {}
    return events;
  }
  synchronized void addEvent(JSONObject e) {
    JSONArray a = events(); a.put(e);
    if (a.length() > KEEP_EVENTS) { JSONArray b = new JSONArray(); for (int k = a.length() - KEEP_EVENTS; k < a.length(); k++) b.put(a.opt(k)); events = a = b; }
    try { if (practiceFile != null) Chats.write(practiceFile, a.toString()); } catch (Exception ignore) {}
  }
  /** Попытка «Скажите сами»; возвращает, первая ли она по этому ключу за день — календарный, по часам телефона,
   *  как у повторения: при скользящих сутках вчерашняя попытка в 20:00 отнимала у сегодняшней в 19:00 «первую». */
  public synchronized boolean recordPractice(String key, boolean ok, long now) {
    long day = dueIn(now, 0, TimeZone.getDefault());
    boolean first = true; JSONArray a = events();
    for (int k = 0; k < a.length(); k++) { JSONObject x = a.optJSONObject(k);
      if (x != null && "try".equals(x.optString("kind")) && key.equals(x.optString("key")) && x.optLong("at") >= day && x.optLong("at") <= now) { first = false; break; } }
    try { addEvent(new JSONObject().put("kind", "try").put("at", now).put("key", key).put("ok", ok).put("first", first)); } catch (JSONException ignore) {}
    return first;
  }
  /** Сказал по-португальски сам в живом разговоре. */
  public synchronized void recordSaid(long now) {
    try { addEvent(new JSONObject().put("kind", "said").put("at", now)); } catch (JSONException ignore) {}
  }
  /** За неделю: {первых попыток, из них распознано, сказано самим}. */
  public synchronized int[] week(long now) {
    int tries = 0, ok = 0, said = 0; JSONArray a = events();
    for (int k = 0; k < a.length(); k++) { JSONObject x = a.optJSONObject(k);
      if (x == null || now - x.optLong("at") > WEEK) continue;
      if ("said".equals(x.optString("kind"))) said++;
      else if (x.optBoolean("first")) { tries++; if (x.optBoolean("ok")) ok++; } }
    return new int[]{tries, ok, said};
  }

  /** Ключи карточек без перевода (слова и связки) — для фонового перевода (TranslatorService.translateWords). */
  public synchronized List<String> missingCardRu(List<Cards.Card> l) {
    List<String> out = new ArrayList<>();
    for (Cards.Card c : l) if (c.kind != Cards.PHRASE && !wordRu.containsKey(c.key) && !triedRu.contains(c.key)) out.add(c.key);
    return out;
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
        for (int k = 0; k < a.length(); k++) { known.add(a.getString(k)); stat.put(a.getString(k), fresh()); }   // срок 0 — спросить сразу
        saveKnown();
        return;
      }
      JSONObject o = new JSONObject(raw);
      for (Iterator<String> it = o.keys(); it.hasNext(); ) {
        String w = it.next(); JSONObject x = o.optJSONObject(w);
        known.add(w);
        // step и due — с шага 2 (03.10); у отмеченного раньше их нет: срок 0 — спросить сразу
        stat.put(w, x == null ? fresh() : new long[]{x.optLong("ok"), x.optLong("fail"), x.optLong("last"), x.optLong("step"), x.optLong("due")});
      }
    } catch (Exception ignore) {}
  }
  synchronized void saveKnown() {
    try {
      JSONObject o = new JSONObject();
      for (String w : known) {
        long[] st = stat.get(w); if (st == null) st = fresh();
        o.put(w, new JSONObject().put("ok", st[OK]).put("fail", st[FAIL]).put("last", st[LAST]).put("step", st[STEP]).put("due", st[DUE]));
      }
      Chats.write(knownFile, o.toString());
    } catch (Exception ignore) {}
  }
  public synchronized void setKnown(String w, boolean yes) { setKnown(w, yes, System.currentTimeMillis()); }
  /** «Знаю» ставит на лесенку повторения с первой ступени: первый раз спросим завтра. */
  public synchronized void setKnown(String w, boolean yes, long now) {
    if (yes) {
      if (known.add(w) || !stat.containsKey(w)) { long[] st = stat.get(w); if (st == null) st = fresh(); st[STEP] = 0; st[DUE] = dueIn(now, DAYS[0], TimeZone.getDefault()); stat.put(w, st); }
    } else { known.remove(w); stat.remove(w); }
    saveKnown();
  }

  // ---- повторение с интервалами (решение владельца 03.10) ------------------------------------------

  /** Лесенка: удача откладывает на 1 → 3 → 7 → 21 день, дальше — раз в 21 день; промах — на завтра и
   *  лесенка сначала. Дни календарные, по часам телефона: повторили вечером — спросим снова с утра, а не
   *  ровно через сутки. Отмеченное «знаю» стоит на первой ступени: первый раз — завтра. */
  static final int[] DAYS = {1, 3, 7, 21};
  static long dueIn(long now, int days, TimeZone tz) {
    Calendar c = Calendar.getInstance(tz); c.setTimeInMillis(now);
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
    c.add(Calendar.DAY_OF_MONTH, days);
    return c.getTimeInMillis();
  }
  /** Через сколько календарных дней срок: 0 — сегодня или раньше. */
  static int daysUntil(long due, long now, TimeZone tz) {
    long today = dueIn(now, 0, tz);
    return due <= today ? 0 : (int) Math.round((dueIn(due, 0, tz) - today) / (double) DAY);
  }
  /** Ступень и срок после ответа: {ступень, срок}. */
  static long[] schedule(long step, boolean ok, long now, TimeZone tz) {
    int s = ok ? (int) Math.min(step + 1, DAYS.length - 1) : 0;
    return new long[]{s, dueIn(now, DAYS[s], tz)};
  }

  /** Что пора повторить: известное со сроком не позже now — сначала давно просроченное, при равных — то, на
   *  чём спотыкались. */
  public synchronized List<String> due(long now) {
    List<String> out = new ArrayList<>();
    for (String w : known) { long[] st = stat.get(w); if (st == null || st[DUE] <= now) out.add(w); }
    out.sort((a, b) -> {
      long[] x = stat.get(a), y = stat.get(b);
      long dx = x == null ? 0 : x[DUE], dy = y == null ? 0 : y[DUE];
      if (dx != dy) return Long.compare(dx, dy);
      return Long.compare(y == null ? 0 : y[FAIL], x == null ? 0 : x[FAIL]);
    });
    return out;
  }
  /** Ближайший срок среди известного, которое ещё не пора повторять; 0 — такого нет. */
  public synchronized long nextDue(long now) {
    long best = 0;
    for (String w : known) { long[] st = stat.get(w); if (st != null && st[DUE] > now && (best == 0 || st[DUE] < best)) best = st[DUE]; }
    return best;
  }

  /** Ответ на повторение: удача — следующая ступень, промах — завтра и лесенка сначала. Ключ остаётся в
   *  «Знаю» (решение владельца: «промах возвращает на завтра»; прежде «забыл» убирал слово из известного). */
  public synchronized void review(String w, boolean ok, long now) {
    long[] st = stat.get(w); if (st == null) st = fresh();
    if (ok) st[OK]++; else st[FAIL]++;
    st[LAST] = now;
    long[] sc = schedule(st[STEP], ok, now, TimeZone.getDefault());
    st[STEP] = sc[0]; st[DUE] = sc[1];
    stat.put(w, st);
    saveKnown();
  }
  /** Копия памяти о повторениях ключа (OK, FAIL, LAST, STEP, DUE) или null — для экрана. */
  public synchronized long[] statCopy(String w) { long[] st = stat.get(w); return st == null ? null : st.clone(); }
  public synchronized long dueOf(String w) { long[] st = stat.get(w); return st == null ? 0 : st[DUE]; }
  public synchronized String statOf(String w) {
    long[] st = stat.get(w);
    return st == null ? "" : "помню " + st[OK] + " · забыл " + st[FAIL];
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
        // Реплики стенда — корпус прогонов, а не речь человека: на 02.10 их была треть, и «Слова» учили
        // «relógio» и «biscoitos» из замеров (Chats, поле `stand`).
        if (x == null || x.optInt("stand", 0) == 1) continue;
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
