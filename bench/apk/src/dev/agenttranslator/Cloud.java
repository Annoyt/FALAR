package dev.agenttranslator;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/**
 * Ручное уточнение одной фразы через OpenRouter, название разговора и чтение текста со снимка.
 * Только по действию человека, никогда автоматически: замер
 * (results/device/2026-09-10-openrouter-free-latency.md) показал 1,9–4,1 с и частые 429 —
 * для непрерывного режима это непригодно, для «переспросить» годится.
 *
 * Деньги потратить нельзя по построению, и условий здесь два, а не одно: суффикс «:free»
 * И подтверждённая нулевая цена. Второе — свойство модели, а не файла: оно запрашивается
 * у OpenRouter, хранится рядом с моделью (`free`) и проверяется перед каждой отправкой.
 * Раньше цена проверялась только в момент сохранения ключа, а при обычном запуске список
 * принимался с диска по одному суффиксу — то есть по имени на чужом сервисе. Имя ничего
 * не гарантирует: модель могли переоценить, а файл сюда можно положить руками.
 * По той же причине здесь нет «openrouter/auto»: чужая маршрутизация уводит на платную модель.
 *
 * Формат models/openrouter.json:
 *   {"key": "sk-or-...", "models": ["...:free"], "pick": "",
 *    "m": {"id": {"free":true,"img":false,"txt":true,"caps":true,"ok":3,"fail":0,"ms":1800}}}
 * Старый файл без "m"/"pick" читается как раньше; цены в нём не подтверждены, поэтому первое
 * же обращение к облаку их запрашивает, а до подтверждения ни один запрос не уходит.
 */
public class Cloud {
  public volatile boolean ready; volatile String key; public volatile String[] models;
  final File dir;
  /** Замок на состояние и файл. Сеть под ним не держим: configure() ждёт ответа до 20 с,
   *  а choose() вызывается с UI-потока — под общим замком это был бы ANR. */
  final Object io = new Object();
  public volatile String lastError = "";
  /** Закреплённая вручную модель. Пусто — маршрутизация автоматическая; это обычный режим. */
  public volatile String pick = "";
  /** Кто ответил в последний раз: без этого нельзя узнать, чья это работа. */
  public volatile String lastUsed = "";
  /** Что известно про каждую модель: что она о себе сообщила и как вела себя у нас. */
  public final Map<String, M> meta = Collections.synchronizedMap(new LinkedHashMap<String, M>());
  volatile long capsTriedAt = 0;
  /** До какого момента перебору разрешено идти: иначе первая попытка съедала 49 с, вторая
   *  начиналась на 49-й секунде и общий предел в 60 с превращался в сотню. */
  volatile long callDeadline = 0;

  static final int F_NONE = 0, F_MODEL = 1, F_NET = 2, F_AUTH = 3;
  /** Чем кончилась последняя попытка: отказ модели, обрыв связи или отказ по ключу/кредиту.
   *  Различать обязательно — иначе поход без сети запишет отказ всем моделям подряд и испортит
   *  накопленную статистику, по которой считается маршрут. */
  volatile int lastFail = F_NONE;
  /** Был ли в последнем переборе ответ 429: только он и обрыв связи дают право удвоить интервал
   *  автоматического пересмотра — отказ модели по формату этого не заслуживает. */
  public volatile boolean rateLimited = false;
  /** След последнего перебора: модель, время, чем кончилось. Без него из журнала не понять, кто из
   *  бесплатных моделей справляется с разметкой, а кто отвечает «null» или рассуждает вслух. */
  public final List<String> trail = Collections.synchronizedList(new ArrayList<String>());
  volatile int maxTries = 5;

  /** Одна бесплатная модель: возможности из ответа OpenRouter и накопленное поведение. */
  public static class M {
    public final String id;
    public volatile boolean priceOk;        // цена подтверждена нулём — без этого не отправляем
    public volatile boolean image;          // принимает картинки
    public volatile boolean textOut = true; // отдаёт текст; иначе нам не годится вовсе
    public volatile boolean capsKnown;      // возможности спрошены, а не предположены
    // Текст и снимок считаются отдельно: на устройстве `nex-n2.5-mini` отвечает на текст за 3,5 с,
    // но отказала на картинке — и общий счётчик отправил её текстовые запросы в хвост, отдав их
    // модели, которая отвечает 16,6 с. Разные умения — разная статистика.
    public volatile int ok, fail; public volatile long ms;
    public volatile int iok, ifail; public volatile long ims;
    M(String id) { this.id = id; }
    int ok(boolean img) { return img ? iok : ok; }
    int fail(boolean img) { return img ? ifail : fail; }
    long ms(boolean img) { return img ? ims : ms; }
  }

  /** Разобранная запись из ответа /models. */
  static class Found { String id; boolean img, txtOut = true, price; }

  public Cloud(File modelsDir) {
    String k = null; String[] m = null; String p = "";
    dir = modelsDir;
    try {
      File f = new File(modelsDir, "openrouter.json");
      if (f.exists()) {
        JSONObject j = new JSONObject(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
        k = j.optString("key", "");
        p = j.optString("pick", "");
        JSONArray a = j.optJSONArray("models");
        if (a != null) {
          List<String> ok = new ArrayList<>();
          for (int i = 0; i < a.length(); i++) {
            String id = a.getString(i);
            if (id.endsWith(":free")) ok.add(id);          // первое условие; второе — цена, ниже
          }
          m = ok.toArray(new String[0]);
        }
        JSONObject mm = j.optJSONObject("m");
        for (Iterator<String> it = mm == null ? Collections.<String>emptyIterator() : mm.keys(); it.hasNext();) {
          String id = it.next(); JSONObject o = mm.optJSONObject(id); if (o == null) continue;
          M x = new M(id);
          x.priceOk = o.optBoolean("free", false);
          x.image = o.optBoolean("img", false); x.textOut = o.optBoolean("txt", true);
          x.capsKnown = o.optBoolean("caps", false);
          x.ok = o.optInt("ok", 0); x.fail = o.optInt("fail", 0); x.ms = o.optLong("ms", 0);
          x.iok = o.optInt("iok", 0); x.ifail = o.optInt("ifail", 0); x.ims = o.optLong("ims", 0);
          meta.put(id, x);
        }
      }
    } catch (Exception e) { e.printStackTrace(); }
    key = k; models = m; pick = p == null ? "" : p;
    if (!pick.isEmpty() && !has(pick)) pick = "";     // модель исчезла из бесплатных — выбор недействителен
    ready = key != null && !key.isEmpty() && models != null && models.length > 0;
  }

  public String keyTail() { return key == null || key.length() < 6 ? "" : "…" + key.substring(key.length() - 6); }
  /** Начало и хвост ключа: по ним свой ключ узнаётся, а целиком он на экране не нужен. */
  public String keyId() {
    if (key == null || key.isEmpty()) return "";
    return key.length() <= 18 ? key.substring(0, Math.min(6, key.length())) + "…"
                              : key.substring(0, 13) + "…" + key.substring(key.length() - 4);
  }
  public int modelCount() { return models == null ? 0 : models.length; }
  /** Сколько моделей с подтверждённой ценой: остальные не отправляются вовсе. */
  public int verifiedCount() {
    String[] ms = models; if (ms == null) return 0;
    int n = 0; for (String id : ms) if (metaOf(id).priceOk) n++;
    return n;
  }
  boolean has(String id) { if (models == null) return false; for (String m : models) if (m.equals(id)) return true; return false; }
  public M metaOf(String id) { synchronized (meta) { M m = meta.get(id); if (m == null) { m = new M(id); meta.put(id, m); } return m; } }

  /** Подогнанные под чужую область ветки семейства. Отличать их по имени — правило, а не замер:
   *  OpenRouter не сообщает специализацию отдельным полем, а разница между «flash» и «flash-fin»
   *  видна только в названии. Правило мягкое — такая модель не выбрасывается, а уходит вниз. */
  static final String[] DOMAIN = {"-vl", "-vision", "-sante", "-med", "-medical", "-fin", "-law", "-legal",
                                  "-code", "-coder", "-math", "-sql", "-audio", "-image", "-embed", "-rerank"};
  static boolean domainish(String id) {
    String s = id.toLowerCase(Locale.ROOT);
    int c = s.indexOf(':'); if (c > 0) s = s.substring(0, c);
    for (String d : DOMAIN) if (s.endsWith(d) || s.contains(d + "-")) return true;
    return false;
  }

  /** Оценка модели для нашей задачи: коротко назвать разговор и переписать одну фразу.
   *
   *  Измерено здесь только поведение — сколько раз модель ответила, сколько отказала и за сколько.
   *  Остальное — правило: отраслевые ветки уводим вниз. Порядок, в котором список приходит
   *  от OpenRouter, не значит ничего: на устройстве первой стояла `ling-3.0-flash-vl`,
   *  за ней `-sante` и `-fin`.
   *
   *  Вход с картинками намеренно не штрафуется: по ответу OpenRouter картинки принимает и
   *  `ling-3.0-flash-vl`, и рядовая `nex-n2.5-mini` — признак стал общим и ничего не различает. */
  double score(M m, boolean img) {
    double s = 0;
    if (m.capsKnown && !m.textOut) return -100;        // не отдаёт текст — не наш случай вовсе
    if (!m.priceOk) s -= 10;                           // цена не подтверждена: в хвост, отправлять всё равно нельзя
    if (domainish(m.id)) s -= 1.0;
    int ok = m.ok(img), fail = m.fail(img), n = ok + fail;
    if (n > 0) {
      s += 4.0 * ok / n;                               // доля успехов — уже наблюдение, а не догадка
      s -= Math.min(2.0, fail * 0.5);
    }
    // Время весит заметно: человек ждёт ответа. Замер на устройстве — 3,5 с у одной модели против
    // 16,6 с у другой; при прежнем слабом штрафе побеждала медленная, и это было видно на глаз.
    long ms = m.ms(img);
    if (ms > 0) s -= Math.min(3.0, ms / 5000.0);
    return s;
  }

  /** Порядок обхода. Закреплённая руками идёт первой; дальше — по оценке. Остальные остаются
   *  запасными: бесплатные модели регулярно отвечают 429, и один отказ не значит «не вышло». */
  public String[] order() { return order(false); }

  public String[] order(final boolean img) {
    final String[] ms = models;                        // снимок: поле могут обнулить параллельно
    if (ms == null || ms.length == 0) return new String[0];
    final List<String> l = new ArrayList<>(Arrays.asList(ms));
    final Map<String, Integer> api = new HashMap<>();
    for (int i = 0; i < ms.length; i++) api.put(ms[i], i);
    Collections.sort(l, (a, b) -> {
      double d = score(metaOf(b), img) - score(metaOf(a), img);
      if (Math.abs(d) > 1e-9) return d > 0 ? 1 : -1;
      return Integer.compare(api.get(a), api.get(b));   // при равенстве — порядок OpenRouter, устойчиво
    });
    String pk = pick;
    if (!pk.isEmpty() && l.remove(pk)) l.add(0, pk);
    return l.toArray(new String[0]);
  }

  public void choose(String id) { synchronized (io) { pick = id == null || !has(id) ? "" : id; save(); } }
  public boolean auto() { return pick.isEmpty(); }
  /** Куда пойдёт следующий запрос. */
  public String next() { String[] o = order(); return o.length == 0 ? "" : o[0]; }

  /** Ключ вводится в настройках, список бесплатных моделей приложение спрашивает у OpenRouter само:
   *  зашивать имена нельзя, они меняются, а ошибиться тут — значит молча потратить деньги.
   *
   *  Пока новый ключ не принят, на диске не меняется ничего. Раньше он записывался до проверки —
   *  и опечатка затирала рабочий ключ, оставляя рядом список моделей, полученный под прежним. */
  public String configure(String newKey) {
    if (newKey == null || newKey.trim().isEmpty()) {
      synchronized (io) { key = null; models = null; ready = false; save(); }
      return "ключ убран";
    }
    String k = newKey.trim();
    List<Found> found = fetchAll(k);                   // сеть — без замка
    if (found == null) return "ключ не проверен: " + lastError + " — на устройстве ничего не изменилось";
    List<Found> free = new ArrayList<>();
    for (Found f : found) if (f.id.endsWith(":free") && f.price) free.add(f);
    if (free.isEmpty()) return "ключ принят, но бесплатных моделей не нашлось — прежние настройки оставлены";
    String was;
    boolean saved;
    synchronized (io) {
      key = k;
      String[] ids = new String[free.size()];
      for (int i = 0; i < free.size(); i++) { ids[i] = free.get(i).id; apply(free.get(i)); }
      models = ids;
      was = pick;
      if (!pick.isEmpty() && !has(pick)) pick = "";
      ready = true;
      saved = save();
    }
    return "ключ сохранён, бесплатных моделей: " + free.size() + ", первой пойдёт " + next()
         + (saved ? "" : " — ВНИМАНИЕ: записать файл не вышло: " + lastError)
         + (was.isEmpty() || !pick.isEmpty() ? "" : "; закреплённая «" + was + "» из бесплатных пропала, закрепление снято");
  }

  /** Цена и возможности каждой модели. Раньше их спрашивали только при сохранении ключа, а ключ
   *  вводят один раз: на устройстве из-за этого у всех стояло «картинок не принимает», хотя про
   *  `ling-3.0-flash-vl` OpenRouter сообщает `text+image+video->text`. Теперь недостающее
   *  добирается при первом обращении к облаку — не при запуске: без сети приложение должно
   *  подниматься молча и мгновенно. */
  public boolean refreshCaps() {
    String[] ms = models;
    if (ms == null || ms.length == 0) return false;
    boolean need = false;
    for (String id : ms) { M m = metaOf(id); if (!m.capsKnown || !m.priceOk) { need = true; break; } }
    if (!need) return false;
    long now = System.currentTimeMillis();
    if (now - capsTriedAt < 60000) return false;       // не ходить в сеть перед каждым запросом
    capsTriedAt = now;
    List<Found> found = fetchAll(key);
    if (found == null) return false;
    Set<String> seen = new HashSet<>();
    synchronized (io) {
      for (Found f : found) if (has(f.id)) { apply(f); seen.add(f.id); }
      // Модель, исчезнувшая из ответа, больше не подтверждена: отправлять в неё нельзя.
      for (String id : ms) if (!seen.contains(id)) { M m = metaOf(id); m.priceOk = false; m.capsKnown = true; }
      save();
    }
    return true;
  }

  void apply(Found f) {
    M m = metaOf(f.id);
    m.priceOk = f.price; m.image = f.img; m.textOut = f.txtOut; m.capsKnown = true;
  }

  /** Весь каталог разобранным; null — не дошли до сервиса (причина в lastError). */
  List<Found> fetchAll(String withKey) {
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/models").openConnection();
      if (withKey != null && !withKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + withKey);
      c.setConnectTimeout(5000); c.setReadTimeout(15000);
      if (c.getResponseCode() != 200) { lastError = "HTTP " + c.getResponseCode(); return null; }
      StringBuilder b = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
        String l; while ((l = r.readLine()) != null) b.append(l);
      }
      JSONArray a = new JSONObject(b.toString()).optJSONArray("data");
      List<Found> out = new ArrayList<>();
      for (int i = 0; a != null && i < a.length(); i++) {
        JSONObject m = a.optJSONObject(i); if (m == null) continue;
        Found f = new Found();
        f.id = m.optString("id", "");
        if (f.id.isEmpty()) continue;
        JSONObject pr = m.optJSONObject("pricing");
        f.price = pr != null && "0".equals(pr.optString("prompt", "x")) && "0".equals(pr.optString("completion", "x"));
        caps(f, m.optJSONObject("architecture"));
        out.add(f);
      }
      return out;
    } catch (Exception e) { lastError = String.valueOf(e); return null; }
    finally { if (c != null) c.disconnect(); }
  }

  /** Что модель о себе сообщает. Поля разнятся между записями, поэтому читаем и списки модальностей,
   *  и строку вида «text+image->text». Записи без раздела считаем текстовыми — и всё равно
   *  помечаем как спрошенные, иначе каталог качался бы перед каждым запросом вечно. */
  static void caps(Found f, JSONObject arch) {
    if (arch == null) return;
    JSONArray in = arch.optJSONArray("input_modalities"), outM = arch.optJSONArray("output_modalities");
    String mod = arch.optString("modality", "");
    if (in != null) { for (int i = 0; i < in.length(); i++) if ("image".equals(in.optString(i))) f.img = true; }
    else if (mod.contains("image->") || mod.startsWith("image")) f.img = true;
    if (outM != null) { f.txtOut = false; for (int i = 0; i < outM.length(); i++) if ("text".equals(outM.optString(i))) f.txtOut = true; }
    else if (mod.contains("->")) f.txtOut = mod.substring(mod.indexOf("->") + 2).contains("text");
  }

  /** Запись через временный файл: здесь лежит ключ и накопленная статистика, а пишется он
   *  после каждого запроса. Прямая перезапись означала бы, что снятие с задачи в неудачный
   *  момент оставляет обрезанный файл — то есть теряет ключ. Вызывать под {@link #io}. */
  boolean save() {
    File tmp = new File(dir, "openrouter.json.tmp"), dst = new File(dir, "openrouter.json");
    try {
      JSONObject j = new JSONObject();
      if (key != null) j.put("key", key);
      j.put("pick", pick);
      JSONArray a = new JSONArray();
      String[] ms = models;
      if (ms != null) for (String m : ms) a.put(m);
      j.put("models", a);
      JSONObject mm = new JSONObject();
      synchronized (meta) {
        for (M m : meta.values())
          mm.put(m.id, new JSONObject().put("free", m.priceOk).put("img", m.image).put("txt", m.textOut)
                                       .put("caps", m.capsKnown).put("ok", m.ok).put("fail", m.fail).put("ms", m.ms)
                                       .put("iok", m.iok).put("ifail", m.ifail).put("ims", m.ims));
      }
      j.put("m", mm);
      try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8")) { w.write(j.toString()); }
      if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) { lastError = "не переименовать " + tmp; return false; } }
      return true;
    } catch (Exception e) { lastError = String.valueOf(e); tmp.delete(); return false; }
  }

  /** Короткое название разговора по его содержанию: с кем и о чём. Нужно, чтобы список разговоров
   *  был читаемым — по первой фразе человека не узнать. */
  public String title(String dialogue) {
    if (!ready) { lastError = "нет ключа OpenRouter"; return null; }
    String sys = "You name chat logs. Reply with a title written in RUSSIAN (Cyrillic letters), 2-5 words, "
        + "no quotes, no final period. It must be a name for the conversation, not a sentence from it and not "
        + "a translation of one. If a person's name appears, use it (for example «Разговор с Марией»). "
        + "Otherwise name the topic, for example «Заказ в кафе» or «Дорога до отеля».";
    // Ответ проверяем: на устройстве модель вернула «A entrada é gratuita.» — фразу из разговора
    // и по-португальски. Название, которое не по-русски, названием не считаем и идём дальше.
    String r = sweep(sys, "Dialogue:\n" + dialogue, null, false, out -> {
      String t = out.replaceAll("[\"«»\n]", "").trim();
      return t.length() >= 3 && t.length() <= 60 && t.matches(".*\\p{IsCyrillic}.*") && t.split("\\s+").length <= 6;
    });
    return r == null ? null : r.replaceAll("[\"«»\n]", "").trim();
  }

  static String langName(String code) { return code.equals("ru") ? "Russian" : "Brazilian Portuguese"; }

  /** Перевод одной фразы с контекстом диалога. null — не получилось, причина в lastError. */
  public String better(String srcLang, String tgtLang, String text, String context, String terms) {
    if (!ready) { lastError = "нет ключа OpenRouter"; return null; }
    String sys = "You are an interpreter between " + langName(srcLang) + " and " + langName(tgtLang)
        + ". The input is a speech-recognition transcript and may contain recognition errors; fix obvious ones"
        + " from context. Reply with the translation only, no explanations, no quotes.";
    StringBuilder u = new StringBuilder();
    if (terms != null && !terms.isEmpty()) u.append("Use these translations:\n").append(terms).append('\n');
    if (context != null && !context.isEmpty()) u.append("Dialogue so far:\n").append(context).append('\n');
    u.append("Translate into ").append(langName(tgtLang)).append(":\n").append(text);
    return sweep(sys, u.toString(), null, false);
  }

  /** Разметка ответа общая для облака и для локальной инструктивной модели: одна и та же просьба,
   *  один и тот же разбор {@link Review#parse}. */
  public static final String REVIEW_SYS = "You are a professional interpreter between Brazilian Portuguese and Russian. You get a conversation "
        + "transcript produced by speech recognition; it may contain recognition errors. Turns are numbered. Each turn "
        + "takes two lines: first the transcript with its language (PT: Portuguese, RU: Russian, sometimes with the "
        + "speaker's name), then the current draft translation labelled \"PT draft:\" or \"RU draft:\". The same person "
        + "may speak several turns in a row; turns do NOT strictly alternate; never invent a dialogue structure that "
        + "is not there.\n"
        + "Reply in exactly this plain-text format, no markdown, no reasoning, no other lines. Example of a complete "
        + "reply for a DIFFERENT conversation (illustrative only, never copy its values):\n"
        + "TOPIC: Ремонт машины в мастерской\n"
        + "FIX 3: Сколько стоит замена масла?\n"
        + "FIX 7: Quando o carro fica pronto?\n"
        + "TERMS: oficina=мастерская; orçamento=смета\n"
        + "NAMES: Auto Center Silva=Ауто Сентер Силва\n"
        + "Rules: TOPIC is what this conversation is about, 3-7 words in Russian. FIX lines: one per turn whose draft "
        + "is wrong, unnatural or misses context, giving a better DRAFT — a \"PT draft\" is replaced by Brazilian "
        + "Portuguese, a \"RU draft\" by Russian; never rewrite the transcript line, only the draft; use the context "
        + "to resolve recognition errors; always include a FIX line for the last turn; never explain. TERMS: up to 8 "
        + "domain words or short expressions that matter here, each as "
        + "Portuguese=Russian translation (a Russian word after the equals sign, never a Portuguese synonym); omit the "
        + "line if none. NAMES: proper names of people, places, businesses, dishes, each as Portuguese=Russian "
        + "transcription; omit the line if none.";

  /** Разбор целого разговора: тема, правки нескольких реплик, пары терминов и имена.
   *  Возвращает сырой текст ответа (разбирается {@link Review#parse}) или null с причиной в lastError.
   *  Ответ без единой строки TOPIC или FIX считается отказом модели, и перебор идёт дальше:
   *  бесплатные модели отвечают и словом «null», и переводом вместо разметки. */
  public String review(String transcript, String topic, String glossary) {
    if (!ready) { lastError = "нет ключа OpenRouter"; return null; }
    String sys = REVIEW_SYS;
    StringBuilder u = new StringBuilder();
    if (topic != null && !topic.isEmpty()) u.append("Topic so far: ").append(topic).append('\n');
    if (glossary != null && !glossary.isEmpty()) u.append("Known terms: ").append(glossary).append('\n');
    u.append("Transcript:\n").append(transcript);
    // Годный ответ — хотя бы одна строка FIX: последнюю реплику модель обязана поправить. Тема одна
    // не считается — на устройстве модель рассуждала вслух до предела токенов и успевала только тему.
    // Дольше и шире, чем одна фраза: на устройстве первые по рейтингу модели отвечали без FIX или «null»,
    // и 45 с кончались раньше, чем очередь доходила до тех, кто держит формат.
    maxTries = 8;
    try { return sweep(sys, u.toString(), null, false, out -> !Review.parse(out).fixes.isEmpty(), 1200, 120000); }
    finally { maxTries = 5; }
  }

  /** Разобранный ответ на review(): тема, правки по номерам реплик снимка, пары, имена. */
  public static class Review {
    public String topic = "";
    public final Map<Integer, String> fixes = new LinkedHashMap<>();
    public final List<String[]> terms = new ArrayList<>(), names = new ArrayList<>();
    static final java.util.regex.Pattern FIX = java.util.regex.Pattern.compile("(?i)^\\W*FIX\\s*#?\\s*(\\d+)\\s*[:\\-–—]\\s*(.+)$");
    public static Review parse(String out) {
      Review r = new Review();
      if (out == null) return r;
      for (String raw : out.split("\n")) {
        // Звёздочки и обратные кавычки — разметка, которую модели лепят на метки («**FIX 2**:»);
        // в переводе они не нужны, поэтому снимаем их со всей строки, а не только с краёв.
        String l = raw.replace("*", "").replace("`", "").trim().replaceAll("^[_#>\\-\\s]+", "").trim();
        if (l.isEmpty()) continue;
        String up = l.toUpperCase(Locale.ROOT);
        if (up.startsWith("TOPIC")) { String t = after(l).replaceAll("[\"«»]", "").trim(); if (usable(t) && t.matches(".*\\p{IsCyrillic}.*") && !t.equalsIgnoreCase("Ремонт машины в мастерской")) r.topic = t; continue; }   // тема по-русски и не из примера
        java.util.regex.Matcher m = FIX.matcher(l);
        if (m.find()) { String t = m.group(2).trim().replaceAll("^[\"«]|[\"»]$", ""); if (usable(t)) r.fixes.put(Integer.parseInt(m.group(1)), t); continue; }
        if (up.startsWith("TERMS")) { pairs(after(l), r.terms); continue; }
        if (up.startsWith("NAMES")) { pairs(after(l), r.names); continue; }
      }
      return r;
    }
    static String after(String l) { int c = l.indexOf(':'); return c < 0 ? "" : l.substring(c + 1).trim(); }
    /** «null», «none» и эхо шаблона («<better translation of turn n>») — не ответ. */
    static boolean usable(String t) {
      if (t == null || t.isEmpty()) return false;
      String low = t.toLowerCase(Locale.ROOT);
      if (low.equals("null") || low.equals("none") || low.equals("n/a") || low.equals("нет") || low.equals("—")) return false;
      if (t.indexOf('<') >= 0 && t.indexOf('>') > t.indexOf('<')) return false;
      return t.matches("(?s).*\\p{L}.*");
    }
    /** «orçamento=смета; obra=ремонт» — разделители терпимые: модели пишут и «=», и «—», и «->». */
    static void pairs(String s, List<String[]> into) {
      if (s == null) return;
      for (String p : s.split("\\s*;\\s*|\\s*\\n\\s*")) {
        String[] kv = p.split("\\s*(?:=|→|->|—|–)\\s*", 2);
        if (kv.length < 2) continue;
        String a = kv[0].trim().replaceAll("^[\"«]|[\"»]$", ""), b = kv[1].trim().replaceAll("^[\"«]|[\"»]$", "");
        if (a.isEmpty() || b.isEmpty() || a.length() > 60 || b.length() > 60) continue;
        if (!usable(a) || !usable(b)) continue;                    // «none», «нет», эхо шаблона
        // Слева португальское, справа русское: модели присылали синонимы «bilhete=passagem» —
        // это не перевод, и в подсказку уточнителю такое не идёт.
        if (a.matches("(?s).*\\p{IsCyrillic}.*") || !b.matches("(?s).*\\p{IsCyrillic}.*")) continue;
        into.add(new String[]{a, b});
      }
    }
  }

  /** Текст со снимка. Просим только текст, без перевода и пояснений: переводит у нас свой
   *  офлайновый узел, со словарём и своими словами. Идём лишь по тем моделям, которые сами
   *  сообщили, что принимают изображения. */
  public String image(byte[] jpeg) {
    if (!ready) { lastError = "нет ключа OpenRouter"; return null; }
    String sys = "You read text from images. Return every piece of text visible in the image as plain text, "
        + "preserving line order. Do not translate, do not describe the image, do not add commentary, "
        + "do not answer with the word null or with an explanation. If you cannot read the image, reply "
        + "with exactly: CANNOT_READ";
    String url = "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
    // На устройстве бесплатные модели трижды ответили буквально словом «null» — и потратили
    // на это 123, 56 и 13 с. Такой ответ считаем отказом модели и идём к следующей, а не выдаём
    // «null» за текст со снимка.
    return sweep(sys, "Read the text in this image.", url, true, out -> {
      String t = out.trim().replaceAll("^[\"'`]+|[\"'`]+$", "");
      String low = t.toLowerCase(Locale.ROOT);
      if (low.equals("null") || low.equals("none") || low.equals("n/a") || low.startsWith("cannot_read")
          || low.startsWith("i cannot") || low.startsWith("i'm unable") || low.startsWith("i am unable")) return false;
      return t.matches("(?s).*\\p{L}.*");                       // хоть одна буква
    });
  }

  /** Перебор моделей с ограничениями: не больше пяти попыток, выход при отказе по ключу или
   *  кредиту (перебор тут не поможет) и при втором подряд обрыве связи. Без этого один поход
   *  без сети занимал единственный рабочий поток на девятнадцать таймаутов подряд. */
  String sweep(String sys, String user, String imageUrl, boolean needImage) { return sweep(sys, user, imageUrl, needImage, null); }
  String sweep(String sys, String user, String imageUrl, boolean needImage, Check check) {
    return sweep(sys, user, imageUrl, needImage, check, imageUrl == null ? 300 : 800, needImage ? 60000 : 30000);
  }

  /** ok — необязательная проверка ответа: ответ, не прошедший её, считается неудачей модели,
   *  и перебор идёт дальше. Нужна там, где «что-то ответили» и «ответили по делу» — разное. */
  interface Check { boolean ok(String out); }

  /** maxTokens и общий предел времени задаются вызовом: разбор целого разговора возвращает
   *  несколько строк и читает длинный вход, ему нужно больше и того, и другого, чем одной фразе. */
  String sweep(String sys, String user, String imageUrl, boolean needImage, Check check, int maxTokens, long totalMs) {
    refreshCaps(); rateLimited = false; trail.clear();
    // Предел по времени, а не только по числу попыток: при таймауте 45 с пять попыток — это
    // почти четыре минуты. На устройстве человек уже ждал 123 с и решил, что ничего не работает.
    final long deadline = System.currentTimeMillis() + totalMs;
    int tried = 0, net = 0, skipped = 0;
    for (String model : order(needImage)) {
      long left = deadline - System.currentTimeMillis();
      if (tried > 0 && left < 8000) { lastError = "не успели за отведённое время: " + lastError; break; }
      callDeadline = deadline;        // чтение не должно висеть дольше, чем осталось у перебора
      M m = metaOf(model);
      if (!m.priceOk) { skipped++; continue; }          // цена не подтверждена — не отправляем
      if (needImage && !m.image) continue;
      long ta = System.currentTimeMillis();
      String r = call(model, sys, user, imageUrl, maxTokens);
      if (r != null && check != null && !check.ok(r)) {
        lastError = model + ": ответ не годится («" + r.replace('\n', ' ').trim() + "»)";
        fail(m, imageUrl != null); r = null;
      }
      String took = (System.currentTimeMillis() - ta) / 1000.0 + " с";
      trail.add(model.replace(":free", "") + (r != null ? " ✓ " + took : " ✗ " + took + " (" + lastError.replaceFirst("^" + java.util.regex.Pattern.quote(model) + ": ?", "").replaceAll("\\s+", " ") + ")"));
      if (r != null) return r;
      if (lastFail == F_AUTH) break;
      if (lastFail == F_NET && ++net >= 2) break;
      if (++tried >= maxTries) break;
    }
    if (tried == 0 && skipped > 0 && lastFail != F_AUTH)
      lastError = "цена моделей не подтверждена (" + skipped + " шт.) — нужна связь с OpenRouter";
    else if (tried == 0 && needImage) lastError = "среди бесплатных нет моделей, принимающих изображения";
    return null;
  }

  String call(String model, String sys, String user, String imageUrl, int maxTokens) {
    HttpURLConnection c = null;
    long t0 = System.nanoTime();
    M m = metaOf(model);
    if (!m.priceOk) { lastError = model + ": цена не подтверждена"; lastFail = F_MODEL; return null; }
    try {
      c = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
      c.setRequestMethod("POST");
      c.setRequestProperty("Authorization", "Bearer " + key);
      c.setRequestProperty("Content-Type", "application/json");
      int want = imageUrl == null ? (maxTokens > 300 ? 30000 : 20000) : 45000;
      long left = callDeadline == 0 ? want : Math.max(8000, callDeadline - System.currentTimeMillis());
      c.setConnectTimeout(4000); c.setReadTimeout((int) Math.min(want, left)); c.setDoOutput(true);
      Object userContent;
      if (imageUrl == null) userContent = user;
      else {
        JSONArray parts = new JSONArray();
        if (user != null && !user.isEmpty()) parts.put(new JSONObject().put("type", "text").put("text", user));
        parts.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", imageUrl)));
        userContent = parts;
      }
      JSONObject body = new JSONObject()
          .put("model", model).put("temperature", 0).put("max_tokens", maxTokens)
          // Рассуждение вслух не нужно ни одному нашему запросу, а токены оно съедает до ответа;
          // модели без рассуждения параметр игнорируют.
          .put("reasoning", new JSONObject().put("enabled", false))
          .put("messages", new JSONArray()
              .put(new JSONObject().put("role", "system").put("content", sys))
              .put(new JSONObject().put("role", "user").put("content", userContent)));
      try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
      int code = c.getResponseCode();
      InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
      StringBuilder sb = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String l; while ((l = r.readLine()) != null) sb.append(l);
      }
      // 403 — не ключ: OpenRouter отвечает им за одну модель (политика данных, модерация), и на
      // устройстве такой ответ inkling-small обрывал весь перебор. Ключ и кредит — это 401 и 402.
      if (code == 401 || code == 402) { lastError = "ключ или кредит: HTTP " + code; lastFail = F_AUTH; return null; }
      if (code == 429) rateLimited = true;
      if (code >= 400) { lastError = model + ": HTTP " + code; return fail(m, imageUrl != null); }
      JSONArray ch = new JSONObject(sb.toString()).optJSONArray("choices");
      if (ch == null || ch.length() == 0) { lastError = model + ": пустой ответ"; return fail(m, imageUrl != null); }
      JSONObject choice = ch.getJSONObject(0), msg = choice.getJSONObject("message");
      // content: null у рассуждающих моделей значит «все токены ушли на рассуждение» — optString вернул бы
      // строку «null», и на устройстве она выдавалась за ответ. Различаем и пишем причину.
      String out = msg.isNull("content") ? "" : msg.optString("content", "").trim();
      if (out.isEmpty()) {
        String why = choice.optString("finish_reason", "?"); int rs = msg.isNull("reasoning") ? 0 : msg.optString("reasoning", "").length();
        lastError = model + ": пустой текст (finish=" + why + (rs > 0 ? ", рассуждений " + rs + " зн." : "") + ")"; return fail(m, imageUrl != null);
      }
      long took = (System.nanoTime() - t0) / 1000000;
      if (imageUrl == null) { m.ok++; m.ms = m.ms == 0 ? took : (m.ms * 3 + took) / 4; }
      else { m.iok++; m.ims = m.ims == 0 ? took : (m.ims * 3 + took) / 4; }
      lastUsed = model; lastFail = F_NONE;
      synchronized (io) { save(); }
      return out;
    } catch (IOException e) {
      // Обрыв связи — не вина модели: записывать это ей в отказы значит испортить маршрут
      // после первого же похода без сети.
      lastError = model + ": " + e; lastFail = F_NET; return null;
    } catch (Exception e) {
      lastError = model + ": " + e; return fail(m, imageUrl != null);
    } finally { if (c != null) c.disconnect(); }
  }

  String fail(M m, boolean img) { if (img) m.ifail++; else m.fail++; lastFail = F_MODEL; synchronized (io) { save(); } return null; }
}
