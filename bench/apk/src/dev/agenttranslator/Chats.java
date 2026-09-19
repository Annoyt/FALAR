package dev.agenttranslator;

import java.io.*;
import java.util.*;
import org.json.*;

/** Разговоры — по одному на собеседника, а не «сессия до сброса». Встретили того же человека
 *  снова — открыли его разговор и продолжили с того места, вместе с накопленным контекстом.
 *
 *  Отсюда два следствия в устройстве:
 *  — разговор пишется на диск после каждой реплики, а не при закрытии: переключение должно быть
 *    мгновенным и безопасным, а приложение может умереть в любой момент;
 *  — открытие разговора возвращает его реплики в работу, чтобы уточнитель видел прежний контекст.
 *
 *  Улучшение переводов задним числом делается для разговора, из которого ушли: первая реплика
 *  переводилась вслепую, а когда разговор дочитан целиком, видно, о чём шла речь.
 *
 *  Кроме реплик разговор держит **тему** (`topic`, короткая строка от облака) и **глоссарий**
 *  (`terms`, пары слов от облака или инструктивной модели): они нужны уточнителю и следующему
 *  облачному запросу, а не OPUS-MT, до которого подсказки не доходят. Оба поля необязательные —
 *  старый файл без них читается как раньше.
 *
 *  Реплика: `dir`, `src`, `dst`, `at`, необязательно `fixed` (улучшенный перевод), `by` (кто
 *  улучшил: user/cloud/llm) и `who` (кто говорил, если голос опознан). Правка человека (`by=user`)
 *  для автоматики неприкосновенна.
 *
 *  Всё лежит на устройстве: это транскрипты приватных разговоров людей, которые не знают,
 *  что их записывают (§7 плана). */
public class Chats {
  public static final int KEEP = 500;
  public static final String BY_USER = "user", BY_CLOUD = "cloud", BY_LLM = "llm";
  final File dir;
  public long current;
  public String name = "";
  public volatile String topic = "";
  JSONArray turns = new JSONArray();          // в Android у JSONArray нет clear(), пересоздаём
  JSONArray terms = new JSONArray();

  public Chats(File filesDir) {
    dir = new File(filesDir, "chats");
    dir.mkdirs();                             // каталог создаёт приложение: от adb он был бы недоступен
    List<String[]> l = list();
    if (l.isEmpty()) { current = System.currentTimeMillis(); save(); }
    else { open(Long.parseLong(l.get(0)[0])); }   // продолжаем последний, а не начинаем пустой
  }

  /** Пустой разговор, из которого уходят, не нужен: он не про человека, а про случайное нажатие.
   *  Удаляем при уходе — это плата за то, что новый пишется на диск сразу. */
  synchronized void dropIfEmpty() { if (turns.length() == 0 && name.isEmpty()) file(current).delete(); }

  /** Достигнут ли потолок на прошлой записи — сервису, чтобы сказать об этом вслух. */
  public volatile boolean rolled = false;

  public synchronized void add(String dirn, String src, String dst) { add(dirn, src, dst, null, System.currentTimeMillis()); }

  /** `at` приходит снаружи: служба создаёт рабочую реплику с той же меткой, по ней потом сшиваются правки. */
  public synchronized void add(String dirn, String src, String dst, String who, long at) {
    // Потолок был тихим: на 500-й реплике добавление просто переставало работать, и всё
    // сказанное дальше исчезало без единого слова. Теперь разговор продолжается в новом,
    // с тем же именем и пометкой, — данные не теряются, а файл не растёт без предела.
    if (turns.length() >= KEEP) {
      String was = name;
      newChat(was.isEmpty() ? "" : was + " · продолжение");
      rolled = true;
    }
    try {
      turns.put(turn(dirn, src, dst, who, at));
      save();
    } catch (JSONException ignore) {}
  }

  static JSONObject turn(String dirn, String src, String dst, String who, long at) throws JSONException {
    JSONObject x = new JSONObject().put("dir", dirn).put("src", src).put("dst", dst).put("at", at);
    if (who != null && !who.isEmpty()) x.put("who", who);
    return x;
  }

  /** Реплика, сказанная в разговоре `id`, — туда и пишется, даже если за время распознавания
   *  человек уже переключился на другой. Раньше она попадала в новый разговор: окно около двух
   *  секунд, и чужая фраза портила контекст обоим. */
  public synchronized boolean addTo(long id, String dirn, String src, String dst, String who, long at) {
    if (id == current) { add(dirn, src, dst, who, at); return true; }
    try {
      JSONObject o = load(id);
      if (o == null) return false;                    // разговор удалили — реплике некуда лечь
      JSONArray t = o.optJSONArray("turns"); if (t == null) t = new JSONArray();
      t.put(turn(dirn, src, dst, who, at));
      o.put("turns", t).put("saved", System.currentTimeMillis());
      write(file(id), o.toString());
      return true;
    } catch (Exception e) { return false; }
  }

  public synchronized int size() { return turns.length(); }
  /** Метка последней реплики текущего разговора; 0, если реплик нет. Служба сверяет по ней,
   *  какую правку показать крупно и произнести — а не по памяти о последней озвучке, которая
   *  после открытия разговора пуста. */
  public synchronized long lastAt() { JSONObject x = turns.length() == 0 ? null : turns.optJSONObject(turns.length() - 1); return x == null ? 0 : x.optLong("at", 0); }

  /** Начать разговор с новым человеком. Прежний остаётся на диске и открывается обратно. */
  public synchronized long newChat(String who) {
    long left = turns.length() > 0 ? current : 0;
    if (left == 0) dropIfEmpty(); else save();
    current = System.currentTimeMillis();
    name = who == null ? "" : who;
    turns = new JSONArray(); terms = new JSONArray(); topic = "";
    save();
    return left;
  }

  /** Открыть прежний разговор и продолжить его. Возвращает номер того, из которого ушли. */
  public synchronized long open(long id) {
    long left = current != id && turns.length() > 0 ? current : 0;
    if (current != id) { if (turns.length() == 0) dropIfEmpty(); else save(); }
    JSONObject o = load(id);
    current = id;
    turns = o == null ? new JSONArray() : o.optJSONArray("turns");
    if (turns == null) turns = new JSONArray();
    name = o == null ? "" : o.optString("name", "");
    topic = o == null ? "" : o.optString("topic", "");
    terms = o == null ? null : o.optJSONArray("terms");
    if (terms == null) terms = new JSONArray();
    return left;
  }

  /** Переименование вручную. Помечаем «named»: после этого модель название не перезаписывает —
   *  человек уже сказал, как правильно. */
  public synchronized boolean rename(long id, String who) {
    try {
      JSONObject o = load(id);
      if (o == null) return false;
      o.put("name", who == null ? "" : who).put("named", true);
      write(file(id), o.toString());
      if (id == current) name = who == null ? "" : who;
      return true;
    } catch (Exception e) { return false; }
  }

  /** Все реплики текущего разговора для показа и правки: направление, исходник, перевод, номер,
   *  признак правки, кто правил, метка времени, кто говорил. Номер строки — это и есть индекс в разговоре,
   *  по нему потом удаляют, переносят и правят. */
  public synchronized List<String[]> all() {
    List<String[]> out = new ArrayList<>();
    for (int k = 0; k < turns.length(); k++) {
      JSONObject x = turns.optJSONObject(k);
      if (x == null) continue;
      out.add(row(x, k));
    }
    return out;
  }
  static String[] row(JSONObject x, int k) {
    return new String[]{x.optString("dir", "pt2ru"), x.optString("src", ""),
                        x.optString("fixed", x.optString("dst", "")), String.valueOf(k),
                        x.optString("fixed", "").isEmpty() ? "" : "1", x.optString("by", ""),
                        String.valueOf(x.optLong("at", 0)), x.optString("who", "")};
  }
  /** Одна реплика тем же рядом, что и в all(); null, если такой нет. */
  public synchronized String[] turn(int idx) {
    if (idx < 0 || idx >= turns.length()) return null;
    JSONObject x = turns.optJSONObject(idx);
    return x == null ? null : row(x, idx);
  }

  /** Выбросить одну реплику. Нужно не для порядка, а ради контекста: случайная фраза из комнаты
   *  или чужой разговор, попавший в запись, потом уходит и в уточнение перевода, и в разбор слов. */
  public synchronized boolean deleteTurn(int idx) {
    if (idx < 0 || idx >= turns.length()) return false;
    turns.remove(idx);
    save(); return true;
  }

  /** Заменить реплику на месте после правки исходника человеком. Метка `at` **новая** намеренно:
   *  уточнитель, ушедший считать до правки, вернётся с переводом прежнего текста и по старой метке
   *  пришил бы его к новому исходнику (saveRefined сшивает по `at`); свежая метка такую склейку
   *  отбрасывает сама. Прежняя правка `fixed` относится к старому тексту и снимается. */
  public synchronized long replaceTurn(int idx, String src, String dst) {
    if (idx < 0 || idx >= turns.length()) return 0;
    JSONObject x = turns.optJSONObject(idx);
    if (x == null) return 0;
    try {
      long at = System.currentTimeMillis();
      x.put("src", src).put("dst", dst).put("at", at); x.remove("fixed"); x.remove("by");
      save(); return at;
    } catch (JSONException e) { return 0; }
  }

  /** Улучшенный перевод в реплику по индексу. Правка человека (`by=user`) неприкосновенна:
   *  облако и уточнитель её не перезаписывают, иначе следующий проход стирал бы то, что поправили
   *  руками. Возвращает false и в этом случае, и если перевод не изменился. */
  public synchronized boolean fixTurn(int idx, String fixed, String by) {
    if (idx < 0 || idx >= turns.length()) return false;
    return fix(turns.optJSONObject(idx), fixed, by);
  }
  /** То же по метке времени: облачный ответ нумерует реплики снимка, а за время запроса они могли
   *  удалиться или переехать, поэтому применяем по `at`, а не по индексу. */
  public synchronized boolean fixByAt(long at, String fixed, String by) {
    for (int k = 0; k < turns.length(); k++) {
      JSONObject x = turns.optJSONObject(k);
      if (x != null && x.optLong("at", -1) == at) return fix(x, fixed, by);
    }
    return false;
  }
  boolean fix(JSONObject x, String fixed, String by) {
    if (x == null || fixed == null || fixed.trim().isEmpty()) return false;
    if (BY_USER.equals(x.optString("by", "")) && !BY_USER.equals(by)) return false;
    String cur = x.optString("fixed", x.optString("dst", ""));
    if (Phrasebook.norm(fixed).equals(Phrasebook.norm(cur))) return false;   // «правка» без изменений — не правка и не галочка
    try { x.put("fixed", fixed.trim()).put("by", by == null ? "" : by); } catch (JSONException e) { return false; }
    save(); return true;
  }

  /** Перенести реплику в другой разговор — тем же порядком, что и была: разговоры разделены
   *  по людям, и реплика, сказанная не тем человеком, портит контекст обоим. */
  public synchronized boolean moveTurn(int idx, long to) {
    if (idx < 0 || idx >= turns.length() || to == current) return false;
    JSONObject x = turns.optJSONObject(idx);
    if (x == null) return false;
    try {
      JSONObject o = load(to);
      if (o == null) { o = new JSONObject().put("id", to).put("name", ""); }
      JSONArray t = o.optJSONArray("turns");
      if (t == null) t = new JSONArray();
      t.put(x);
      o.put("turns", t).put("saved", System.currentTimeMillis());
      write(file(to), o.toString());
    } catch (Exception e) { return false; }
    turns.remove(idx);
    save(); return true;
  }

  /** Записать улучшенный перевод в последнюю реплику с таким исходником. */
  public synchronized boolean fixLast(String src, String fixed, String by) {
    if (src == null || fixed == null) return false;
    for (int k = turns.length() - 1; k >= 0; k--) {
      JSONObject x = turns.optJSONObject(k);
      if (x == null || !src.equals(x.optString("src", ""))) continue;
      return fix(x, fixed, by);
    }
    return false;
  }

  // ---- тема и глоссарий разговора

  public synchronized void setTopic(String t) {
    topic = t == null ? "" : t.trim();
    save();
  }
  /** Тема становится названием по тем же правилам, что у автоматического названия: разговор,
   *  названный человеком, не трогаем никогда; автоматическое имя меняем, только если разговор
   *  с тех пор вырос вдвое — иначе модель меняла хорошее название на худшее каждые пять реплик. */
  public synchronized boolean nameFromTopic(String t) {
    if (t == null || t.trim().isEmpty()) return false;
    try {
      JSONObject o = load(current);
      if (o == null) return false;
      if (o.optBoolean("named", false)) return false;
      String was = o.optString("name", "");
      // Имя без nameTurns дано при создании (продолжение после потолка, разговор с указанным
      // человеком) — оно не автоматическое, тема его не трогает.
      if (!was.isEmpty() && o.optInt("nameTurns", 0) == 0) return false;
      if (!was.isEmpty() && turns.length() < 2 * o.optInt("nameTurns", 0)) return false;
      if (was.equals(t.trim())) return false;
      name = t.trim();
      o.put("name", name).put("nameTurns", turns.length());
      write(file(current), o.toString());
      return true;
    } catch (Exception e) { return false; }
  }
  /** Пары глоссария: {pt, ru, источник}. */
  public synchronized List<String[]> terms() {
    List<String[]> out = new ArrayList<>();
    for (int k = 0; k < terms.length(); k++) {
      JSONObject x = terms.optJSONObject(k);
      if (x != null) out.add(new String[]{x.optString("pt", ""), x.optString("ru", ""), x.optString("src", "")});
    }
    return out;
  }
  /** Добавить пары; повтор по португальской стороне заменяет старую пару. Возвращает, сколько новых. */
  public synchronized int addTerms(List<String[]> pairs, String src) {
    int added = 0;
    try {
      for (String[] p : pairs) {
        if (p == null || p.length < 2 || p[0].trim().isEmpty() || p[1].trim().isEmpty()) continue;
        String pt = p[0].trim(), ru = p[1].trim();
        boolean found = false;
        for (int k = 0; k < terms.length(); k++) {
          JSONObject x = terms.optJSONObject(k);
          if (x != null && x.optString("pt", "").equalsIgnoreCase(pt)) { x.put("ru", ru).put("src", src); found = true; break; }
        }
        if (!found) { terms.put(new JSONObject().put("pt", pt).put("ru", ru).put("src", src)); added++; }
      }
    } catch (JSONException ignore) {}
    if (added > 0 || !pairs.isEmpty()) save();
    return added;
  }
  public synchronized int clearTerms() { int n = terms.length(); terms = new JSONArray(); save(); return n; }

  /** Последние реплики текущего разговора — их сервис возвращает в рабочую историю при открытии:
   *  направление, исходник, перевод (улучшенный, если есть), метка времени. */
  public synchronized List<String[]> tail(int n) {
    List<String[]> out = new ArrayList<>();
    for (int k = Math.max(0, turns.length() - n); k < turns.length(); k++) {
      JSONObject x = turns.optJSONObject(k);
      if (x == null) continue;
      out.add(new String[]{x.optString("dir", "pt2ru"), x.optString("src", ""),
                           x.optString("fixed", x.optString("dst", "")), String.valueOf(x.optLong("at", 0))});
    }
    return out;
  }

  /** Пишем и пустой разговор. Раньше здесь стоял отбой «нет реплик — нет файла», а боковой список
   *  строится по файлам: только что созданный разговор в нём не появлялся, и нажатие «＋ новый»
   *  выглядело так, будто ничего не произошло. Пустые убираются при уходе, см. dropIfEmpty. */
  synchronized void save() {
    try {
      JSONObject o = load(current);
      if (o == null) o = new JSONObject();
      o.put("id", current).put("name", name).put("saved", System.currentTimeMillis()).put("turns", turns);
      if (topic.isEmpty()) o.remove("topic"); else o.put("topic", topic);
      if (terms.length() == 0) o.remove("terms"); else o.put("terms", terms);
      write(file(current), o.toString());
    } catch (Exception ignore) {}
  }

  File file(long id) { return new File(dir, id + ".json"); }

  /** Кэш строк списка по имени файла: {время изменения, размер, строка}. Панель ☰ и ползунок частот
   *  читали и разбирали каждый файл целиком в потоке интерфейса; теперь разбирается только то,
   *  что изменилось с прошлого раза. */
  final Map<String, Object[]> listCache = new HashMap<>();

  /** Список: номер, сколько реплик, заголовок (имя либо первая португальская фраза), улучшен ли. */
  public synchronized List<String[]> list() {
    List<String[]> out = new ArrayList<>();
    File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));   // .json.tmp под условие не подходит
    if (fs == null) return out;
    Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
    Set<String> seen = new HashSet<>();
    for (File f : fs) {
      seen.add(f.getName());
      Object[] c = listCache.get(f.getName());
      if (c != null && (Long) c[0] == f.lastModified() && (Long) c[1] == f.length()) { out.add((String[]) c[2]); continue; }
      try {
        JSONObject o = new JSONObject(read(f));
        JSONArray t = o.optJSONArray("turns");
        String title = o.optString("name", "");
        for (int k = 0; t != null && k < t.length() && title.isEmpty(); k++) {
          JSONObject x = t.getJSONObject(k);
          title = x.optString("dir", "").startsWith("pt") ? x.optString("src", "") : x.optString("dst", "");
        }
        String[] row = {o.optString("id"), String.valueOf(t == null ? 0 : t.length()), title,
                        o.optBoolean("refined", false) ? "1" : "0"};
        listCache.put(f.getName(), new Object[]{f.lastModified(), f.length(), row});
        out.add(row);
      } catch (Exception ignore) {}
    }
    listCache.keySet().retainAll(seen);
    return out;
  }

  /** Отпечаток набора файлов: по нему кэшируется разбор слов для изучения. */
  public synchronized long fingerprint() {
    File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
    if (fs == null) return 0;
    long h = fs.length;
    for (File f : fs) h = h * 31 + f.lastModified() + f.length();
    return h;
  }

  /** Удалить разговор. Нужно по-настоящему, а не пометкой: по этим разговорам потом идёт разбор
   *  слов для заучивания, и мусорные диалоги портили бы частоты. */
  public synchronized boolean delete(long id) {
    boolean ok = file(id).delete();
    if (ok && id == current) {                    // удалили тот, в котором сидим — начинаем чистый
      current = System.currentTimeMillis(); name = ""; turns = new JSONArray(); terms = new JSONArray(); topic = "";
      save();                                     // иначе замена существует только в памяти и в списке её нет
    }
    return ok;
  }

  public synchronized JSONObject load(long id) {
    try { return new JSONObject(read(file(id))); } catch (Exception e) { return null; }
  }

  /** Сохранить результат улучшения — **слиянием, а не заменой файла**.
   *
   *  Уточнитель читает разговор, уходит считать на минуты и возвращается со своей копией. Если
   *  за это время человек удалил реплику или перенёс её в другой разговор, запись копии целиком
   *  вернула бы удалённое обратно: у нас это тот самый конфликт «удаление против редактирования».
   *  Поэтому берём с диска свежее состояние и переносим в него только то, чем владеет уточнитель:
   *  улучшенный перевод каждой реплики (по метке времени) и название разговора. Реплики,
   *  поправленные человеком, не трогаем. */
  public synchronized boolean saveRefined(long id, JSONObject o, boolean markRefined) {
    try {
      JSONObject cur = load(id);
      // Разговор удалили (или файл нечитаем), пока уточнитель считал — писать некуда. Раньше
      // здесь его копия записывалась как новый файл: удалённый разговор воскресал со всеми
      // репликами, возвращался в список с галочкой и снова шёл в разбор слов. Диалог удаления
      // при этом обещает «Удаление окончательное».
      if (cur == null) return false;
      JSONArray mine = o.optJSONArray("turns"), now = cur.optJSONArray("turns");
      if (mine != null && now != null) {
        Map<Long, String[]> fixed = new HashMap<>();          // метка -> {перевод, источник}
        for (int k = 0; k < mine.length(); k++) {
          JSONObject x = mine.optJSONObject(k);
          if (x == null) continue;
          String f = x.optString("fixed", ""), by = x.optString("by", "");
          if (!f.isEmpty() && !BY_USER.equals(by)) fixed.put(x.optLong("at", -k), new String[]{f, by.isEmpty() ? BY_LLM : by});
        }
        for (int k = 0; k < now.length(); k++) {
          JSONObject x = now.optJSONObject(k);
          if (x == null || BY_USER.equals(x.optString("by", ""))) continue;
          String[] f = fixed.get(x.optLong("at", Long.MIN_VALUE));
          if (f != null) x.put("fixed", f[0]).put("by", f[1]);   // источник сохраняем: правка облака не становится «от уточнителя»
        }
      }
      // Название берём из своей копии, только если на диске его до сих пор нет и его не задавал
      // человек. Иначе переименование, сделанное во время счёта, откатывалось обратно —
      // а «named» оставался, и разговор тут же переименовывала модель.
      String diskName = cur.optString("name", "");
      if (o.has("name") && diskName.isEmpty() && !cur.optBoolean("named", false)) {
        cur.put("name", o.optString("name", ""));
        if (o.has("nameTurns")) cur.put("nameTurns", o.optInt("nameTurns", 0));
      }
      if (markRefined) cur.put("refined", true);      // галочка означает улучшенный перевод, а не «его назвали»
      write(file(id), cur.toString());
      if (id == current) { JSONArray t = cur.optJSONArray("turns"); if (t != null) turns = t; name = cur.optString("name", ""); }
      return true;
    } catch (Exception ignore) { return false; }
  }

  static String read(File f) throws IOException {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
      StringBuilder b = new StringBuilder(); String s;
      while ((s = r.readLine()) != null) b.append(s);
      return b.toString();
    }
  }
  /** Через временный файл и переименование: разговор переписывается целиком после каждой реплики,
   *  и обрыв посреди записи оставлял бы обрезанный JSON — то есть терял весь разговор. */
  static void write(File f, String s) throws IOException {
    File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
    try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8")) { w.write(s); }
    if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); throw new IOException("не переименовать " + tmp); } }
  }
}
