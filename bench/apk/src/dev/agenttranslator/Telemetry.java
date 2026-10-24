package dev.agenttranslator;

import android.media.AudioDeviceInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Телеметрия по согласию человека (владелец 15.10: «1–4 со сбором базы в телеграм»): раз в сутки — пакет
 *  статистики, по кнопке в меню реплики — её звук. Пакет: устройство, версия и модули, счётчики распознавания
 *  за период, сбои (тип исключения и место в коде, без текста ошибки — в нём бывает текст разговора). Звука,
 *  текстов разговора и номера установки в пакете нет. Принимает бот (bot/lib/telemetry.js — те же поля и
 *  ступени фона) и кладёт в топик «Телеметрия» группы разработчика. Здесь — то, что не зависит от Android
 *  и проверяется на столе: счётчики, ступени, доля правки, сбой одной записью, сборка и отправка. */
final class Telemetry {
  /** Согласие на статистику: не спрашивали, да, нет. Без «да» ничего не уходит; звук — своё согласие на каждую запись. */
  static final String ASK = "", YES = "yes", NO = "no";
  /** Бот обратной связи (Cloudflare Worker): POST /t — пакет, POST /audio — звук. Стенд меняет: --es telemetrybase. */
  static final String BASE = "https://falar-feedback-bot.falar-feedback-bot.workers.dev";
  /** Пакет — не чаще раза в сутки, как проверка обновлений: служба живёт, пока приложением пользуются. */
  static final long EVERY = 24L * 3600 * 1000;
  /** Ступени фона комнаты, dBFS: < −60, −60…−54, −54…−48, −48…−42, ≥ −42 (−48 — порог шумодава нарезки). */
  static final int[] NOISE_EDGES = {-60, -54, -48, -42};
  /** Клип: фраза, где ограничитель срезал не меньше стольких процентов выборок. */
  static final double CLIP_PCT = 0.1;
  /** Самый большой звук, который бот примет (4 МБ в base64): минута WAV 16 кГц — 1,9 МБ. */
  static final int MAX_AUDIO = 2_800_000;
  /** Бот принимает до 10 сбоев в пакете: уходят последние. */
  static final int MAX_CRASHES = 10;

  static int noiseBucket(double dbfs) {
    int i = 0;
    while (i < NOISE_EDGES.length && dbfs >= NOISE_EDGES[i]) i++;
    return i;
  }

  /** Микрофон по типу AudioDeviceInfo: встроенный, проводной, Bluetooth (SCO и LE Audio — узкая полоса или
   *  сжатие, распознаётся хуже), USB, другой. */
  static String micKind(int type) {
    switch (type) {
      case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "builtin";
      case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired";
      case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: case AudioDeviceInfo.TYPE_BLE_HEADSET: return "bt";
      case AudioDeviceInfo.TYPE_USB_DEVICE: case AudioDeviceInfo.TYPE_USB_HEADSET: case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "usb";
      default: return "other";
    }
  }

  /** Сколько знаков человек поменял в распознанном: расстояние Левенштейна; длинное — по первым 1000 знаков. */
  static int changed(String a, String b) {
    a = a == null ? "" : a.length() > 1000 ? a.substring(0, 1000) : a;
    b = b == null ? "" : b.length() > 1000 ? b.substring(0, 1000) : b;
    int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
      }
      int[] t = prev; prev = cur; cur = t;
    }
    return prev[b.length()];
  }

  /** Счётчики распознавания за период: копятся в службе, уходят пакетом и обнуляются; не ушло — возвращаются. */
  static final class Counters {
    long since;                                         // начало периода, мс
    int pt, ru, empty, foreign, hold, listen, denoise, wiener, music, clip, edits, editChars, editTotal, gainN;
    /** Фразы прогонов стенда (TranslatorService.standNow): в счётчики не идут, их число — отдельно. На Redmi
     *  стенда 16–24.10 они дали тысячи фраз и закрыли собой живые разговоры владельца. */
    int stand;
    long asrMs, speechMs;
    double gainSum;
    final int[] noise = new int[NOISE_EDGES.length + 1];
    final TreeMap<String, Integer> mic = new TreeMap<>(), skips = new TreeMap<>();

    Counters(long since) { this.since = since; }

    /** Фраза — по строке журнала tsvSeg: вид (asr, silence, skip_*, voice_*), направление, длина, время
     *  распознавания, фон и усиление фразы, срезано ограничителем (%), тип микрофона (−1 — не знаю). Фон,
     *  скорость, усиление, клип и микрофон — только у распознававшихся фраз (asr и silence). */
    synchronized void seg(String kind, String dir, double durMs, long asrMs, double floorDb, double gainDb, double cutPct, int micType) {
      if (kind == null) return;
      boolean speech = kind.equals("asr") || kind.equals("silence");
      if (kind.equals("asr")) { if (dir != null && dir.startsWith("ru")) ru++; else pt++; }
      else if (kind.equals("silence")) empty++;
      else if (kind.equals("skip_voice")) foreign++;
      else if (kind.startsWith("skip_")) skips.merge(kind.substring(5), 1, Integer::sum);
      else if (kind.startsWith("voice_")) skips.merge("late", 1, Integer::sum);
      if (!speech) return;
      if (!Double.isNaN(floorDb)) noise[noiseBucket(floorDb)]++;
      if (asrMs > 0 && durMs > 0) { this.asrMs += asrMs; speechMs += Math.round(durMs); }
      if (!Double.isNaN(gainDb)) { gainSum += gainDb; gainN++; }
      if (cutPct >= CLIP_PCT) clip++;
      if (micType >= 0) mic.merge(micKind(micType), 1, Integer::sum);
    }

    /** Кусок слушания в момент нарезки: включён ли шумодав, слушал ли детектор фильтр Винера, музыка ли в фоне. */
    synchronized void listen(boolean dnOn, boolean wienerOn, boolean musicOn) {
      listen++;
      if (dnOn) denoise++;
      if (wienerOn) wiener++;
      if (musicOn) music++;
    }

    synchronized void hold() { hold++; }

    /** Фраза прогона стенда — только посчитать, что была. */
    synchronized void stand() { stand++; }

    /** Человек поправил распознанный текст: сколько знаков поменял из скольких. */
    synchronized void edit(String was, String now) {
      edits++;
      editChars += changed(was, now);
      editTotal += was == null ? 0 : Math.min(1000, was.length());
    }

    /** Объект asr пакета (поля — как в bot/lib/telemetry.js). loadMs — последняя загрузка моделей, 0 — не знаю. */
    synchronized JSONObject json(long now, long loadMs) throws JSONException {
      JSONObject o = new JSONObject();
      o.put("hours", Math.round((now - since) / 360000.0) / 10.0);
      o.put("turns", new JSONObject().put("pt", pt).put("ru", ru));
      o.put("empty", empty).put("hold", hold).put("listen", listen);
      JSONArray nz = new JSONArray();
      for (int x : noise) nz.put(x);
      o.put("noise", nz);
      o.put("denoise", denoise).put("wiener", wiener).put("music", music);
      if (!mic.isEmpty()) o.put("mic", new JSONObject((Map<?, ?>) mic));
      if (speechMs > 0) o.put("rtf_ms", Math.round(1000.0 * asrMs / speechMs));
      if (gainN > 0) o.put("gain_db", Math.round(10 * gainSum / gainN) / 10.0);
      o.put("clip", clip);
      if (loadMs > 0) o.put("load_ms", loadMs);
      o.put("edits", edits);
      if (edits > 0) o.put("edit_chars", editChars).put("edit_total", editTotal);
      o.put("foreign", foreign);
      if (!skips.isEmpty()) o.put("skips", new JSONObject((Map<?, ?>) skips));
      if (stand > 0) o.put("stand", stand);
      return o;
    }

    /** Всё как есть — для настроек службы (переживает перезапуск). */
    synchronized String save() {
      try {
        JSONObject o = new JSONObject().put("since", since).put("pt", pt).put("ru", ru).put("empty", empty).put("foreign", foreign)
            .put("hold", hold).put("listen", listen).put("denoise", denoise).put("wiener", wiener).put("music", music).put("clip", clip)
            .put("edits", edits).put("editChars", editChars).put("editTotal", editTotal).put("gainN", gainN).put("asrMs", asrMs)
            .put("speechMs", speechMs).put("gainSum", gainSum).put("stand", stand).put("mic", new JSONObject((Map<?, ?>) mic)).put("skips", new JSONObject((Map<?, ?>) skips));
        JSONArray nz = new JSONArray();
        for (int x : noise) nz.put(x);
        return o.put("noise", nz).toString();
      } catch (JSONException e) { return "{}"; }
    }

    /** Из save(); испорчено или пусто — новый период с now. */
    static Counters load(String s, long now) {
      Counters c = new Counters(now);
      if (s == null || s.isEmpty()) return c;
      try {
        JSONObject o = new JSONObject(s);
        c.since = o.optLong("since", now); c.pt = o.optInt("pt"); c.ru = o.optInt("ru"); c.empty = o.optInt("empty");
        c.foreign = o.optInt("foreign"); c.hold = o.optInt("hold"); c.listen = o.optInt("listen"); c.denoise = o.optInt("denoise");
        c.wiener = o.optInt("wiener"); c.music = o.optInt("music"); c.clip = o.optInt("clip"); c.edits = o.optInt("edits");
        c.editChars = o.optInt("editChars"); c.editTotal = o.optInt("editTotal"); c.gainN = o.optInt("gainN");
        c.asrMs = o.optLong("asrMs"); c.speechMs = o.optLong("speechMs"); c.gainSum = o.optDouble("gainSum", 0); c.stand = o.optInt("stand");
        JSONArray nz = o.optJSONArray("noise");
        for (int i = 0; nz != null && i < Math.min(nz.length(), c.noise.length); i++) c.noise[i] = nz.optInt(i);
        for (String m : new String[]{"mic", "skips"}) {
          JSONObject x = o.optJSONObject(m);
          if (x == null) continue;
          for (java.util.Iterator<String> it = x.keys(); it.hasNext(); ) { String k = it.next(); (m.equals("mic") ? c.mic : c.skips).put(k, x.optInt(k)); }
        }
      } catch (JSONException e) { return new Counters(now); }
      return c;
    }

    /** Забрать счётчики для пакета: копия, а здесь — новый период с now. */
    synchronized Counters take(long now) {
      Counters c = load(save(), now);
      since = now; pt = ru = empty = foreign = hold = listen = denoise = wiener = music = clip = edits = editChars = editTotal = gainN = stand = 0;
      asrMs = speechMs = 0; gainSum = 0; Arrays.fill(noise, 0); mic.clear(); skips.clear();
      return c;
    }

    /** Пакет не ушёл: забранное — обратно, период — с его начала. */
    synchronized void merge(Counters o) {
      since = Math.min(since, o.since);
      pt += o.pt; ru += o.ru; empty += o.empty; foreign += o.foreign; hold += o.hold; listen += o.listen; denoise += o.denoise;
      wiener += o.wiener; music += o.music; clip += o.clip; edits += o.edits; editChars += o.editChars; editTotal += o.editTotal;
      gainN += o.gainN; asrMs += o.asrMs; speechMs += o.speechMs; gainSum += o.gainSum; stand += o.stand;
      for (int i = 0; i < noise.length; i++) noise[i] += o.noise[i];
      o.mic.forEach((k, v) -> mic.merge(k, v, Integer::sum));
      o.skips.forEach((k, v) -> skips.merge(k, v, Integer::sum));
    }
  }

  static String simple(String cls) { int i = cls.lastIndexOf('.'); return i < 0 ? cls : cls.substring(i + 1); }
  static String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }

  /** Где было приложение: экран Falar на виду («screen»), видимая служба слушает без экрана («fgs»), в фоне
   *  («background»). По телеметрии 19–22.10 нельзя было сказать, выгружали Falar посреди разговора или в фоне. */
  static final String SCREEN = "screen", FGS = "fgs", BACKGROUND = "background";
  /** Экран Falar на виду: MainActivity.onResume / onPause. */
  static volatile boolean onScreen;
  /** Важность процесса из ApplicationExitInfo.getImportance (RunningAppProcessInfo.IMPORTANCE_*) → то же. */
  static String importanceState(int imp) {
    if (imp <= 100 || imp == 200) return SCREEN;            // FOREGROUND, VISIBLE
    if (imp == 125 || imp == 230) return FGS;               // FOREGROUND_SERVICE, PERCEPTIBLE
    return BACKGROUND;                                      // SERVICE, TOP_SLEEPING, CACHED, GONE…
  }

  /** Сбой одной записью: тип исключения и его причин; место — до четырёх своих кадров (dev.agenttranslator)
   *  у корневой причины, иначе её первый кадр; state — где было приложение. Текста ошибки нет: в нём бывает
   *  текст разговора. */
  static JSONObject crash(Throwable e, long at, String version, String state) throws JSONException {
    StringBuilder what = new StringBuilder(e.getClass().getName());
    Throwable root = e;
    for (int d = 0; root.getCause() != null && root.getCause() != root && d < 4; d++) {
      root = root.getCause();
      what.append(" ← ").append(root.getClass().getName());
    }
    List<String> fr = new ArrayList<>();
    StackTraceElement[] st = root.getStackTrace();
    for (StackTraceElement s : st) {
      if (!s.getClassName().startsWith("dev.agenttranslator.")) continue;
      fr.add(simple(s.getClassName()) + "." + s.getMethodName() + ":" + s.getLineNumber());
      if (fr.size() == 4) break;
    }
    if (fr.isEmpty() && st.length > 0) fr.add(simple(st[0].getClassName()) + "." + st[0].getMethodName() + ":" + st[0].getLineNumber());
    boolean oom = e instanceof OutOfMemoryError || root instanceof OutOfMemoryError;
    return new JSONObject().put("at", at / 1000).put("kind", oom ? "oom" : "crash").put("what", cut(what.toString(), 300))
        .put("where", cut(String.join(" ← ", fr), 300)).put("v", version).put("state", state);
  }

  /** Причина прошлого выхода приложения (ApplicationExitInfo.REASON_*, Android 11+) → вид для пакета; null —
   *  не сбой (сам вышел, человек закрыл…) или уже пойман своим обработчиком (REASON_CRASH — Java). */
  static String exitKind(int reason) {
    switch (reason) {
      case 3: return "oom";        // REASON_LOW_MEMORY
      case 5: return "native";     // REASON_CRASH_NATIVE
      case 6: return "anr";        // REASON_ANR
      case 7: return "crash";      // REASON_INITIALIZATION_FAILURE
      case 9: return "killed";     // REASON_EXCESSIVE_RESOURCE_USAGE
      default: return null;
    }
  }

  /** Записывать ли сбои (только с согласием на статистику): ставит служба по настройке. */
  static volatile boolean collect;
  static boolean crashHook;

  /** Свой обработчик необработанных исключений поверх прежнего: сбой — файлом crash-<время>.json в dir (если
   *  согласие есть), дальше как обычно — прежний обработчик закрывает приложение. Один раз на процесс. */
  static synchronized void catchCrashes(final File dir, final String version) {
    if (crashHook) return;
    crashHook = true;
    final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
      try {
        if (collect) {
          long now = System.currentTimeMillis();
          dir.mkdirs();
          try (OutputStream o = new FileOutputStream(new File(dir, "crash-" + now + ".json"))) {
            o.write(crash(e, now, version, onScreen ? SCREEN : BACKGROUND).toString().getBytes(StandardCharsets.UTF_8));
          }
        }
      } catch (Throwable ignore) { }
      if (prev != null) prev.uncaughtException(t, e);
    });
  }

  /** Файлы сбоев в dir, старые первыми; уходят последние MAX_CRASHES. */
  static List<File> crashFiles(File dir) {
    File[] fs = dir.listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".json"));
    List<File> out = new ArrayList<>();                 // не List.of: его нет до Android 11, а Falar — с 9-го
    if (fs != null) out.addAll(Arrays.asList(fs));
    out.sort((a, b) -> a.getName().compareTo(b.getName()));
    return out.size() > MAX_CRASHES ? out.subList(out.size() - MAX_CRASHES, out.size()) : out;
  }

  static JSONArray crashes(List<File> files) {
    JSONArray a = new JSONArray();
    for (File f : files) {
      try { a.put(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))); } catch (Throwable ignore) { }
    }
    return a;
  }

  /** Пакет статистики (пустые части не кладутся). */
  static String packet(JSONObject app, JSONObject device, JSONObject asr, JSONArray crashes) throws JSONException {
    JSONObject p = new JSONObject().put("v", 1).put("app", app).put("device", device);
    if (asr != null) p.put("asr", asr);
    if (crashes != null && crashes.length() > 0) p.put("crashes", crashes);
    return p.toString();
  }

  static String mimeOf(String name) {
    String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    return n.endsWith(".ogg") ? "audio/ogg" : n.endsWith(".wav") ? "audio/wav" : n.endsWith(".m4a") ? "audio/mp4" : null;
  }

  /** Звук реплики для разработчика — только по кнопке и с отметкой согласия: язык, длина, распознанный и
   *  верный текст, звук в base64. */
  static String audio(JSONObject app, JSONObject device, String lang, long durMs, String heard, String said, String mime, byte[] bytes)
      throws JSONException {
    return new JSONObject().put("v", 1).put("consent", true).put("lang", lang).put("dur_ms", durMs).put("mime", mime)
        .put("heard", heard == null ? "" : heard).put("said", said == null ? "" : said).put("app", app).put("device", device)
        .put("audio", Base64.getEncoder().encodeToString(bytes)).toString();
  }

  /** POST JSON боту: при 200 — тело ответа, иначе IOException «HTTP код: причина из ответа бота». */
  static String post(String url, String json, String ua) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setDoOutput(true); c.setRequestMethod("POST");
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    c.setRequestProperty("X-Falar", "1");
    c.setRequestProperty("User-Agent", ua);
    byte[] b = json.getBytes(StandardCharsets.UTF_8);
    c.setFixedLengthStreamingMode(b.length);
    try {
      try (OutputStream o = c.getOutputStream()) { o.write(b); }
      int code = c.getResponseCode();
      String body = read(code < 400 ? c.getInputStream() : c.getErrorStream());
      if (code != 200) {
        String why = "";
        try { why = new JSONObject(body).optString("error", ""); } catch (JSONException ignore) { }
        throw new IOException("HTTP " + code + (why.isEmpty() ? "" : ": " + why));
      }
      return body;
    } finally { c.disconnect(); }
  }

  static String read(InputStream in) throws IOException {
    if (in == null) return "";
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    try (InputStream i = in) { byte[] buf = new byte[8192]; int n; while ((n = i.read(buf)) > 0) bo.write(buf, 0, n); }
    return new String(bo.toByteArray(), StandardCharsets.UTF_8);
  }

  /** Номер записи звука из ответа бота: {"ok":true,"n":17} → 17; нет — 0. */
  static long number(String body) {
    try { return new JSONObject(body).optLong("n", 0); } catch (JSONException e) { return 0; }
  }
}
