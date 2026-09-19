package dev.agenttranslator;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Модели по манифесту: что лежит, чего нет, докачка с продолжением, sha256 каждого файла,
 *  распаковка голосов. Класс без Android: сеть, журнал и прогресс — через интерфейсы, поэтому
 *  тот же код гоняется на столе (bench/apk/test/ModelStoreTest.java) против локального сервера
 *  с обрывами, битыми файлами и 404, а на телефоне — против Hugging Face.
 *
 *  Манифест — models/manifest.json, вшитый в APK ресурсом (build.sh сверяет его версию с
 *  версией приложения и хэши файлов из репозитория). Пути — относительно каталога моделей.
 *
 *  Проверка без хэширования при каждом запуске: .verified.json помнит размер, mtime и sha256
 *  уже сверенных файлов; файл с теми же размером и mtime считается тем же. Файлы, положенные
 *  снаружи (adb), хэшируются один раз при первом старте. «Проверить файлы» хэширует всё заново.
 *
 *  Докачка: <путь>.part, при обрыве — повтор с Range: bytes=<есть>-, с удвоением паузы до 60 с.
 *  Сервер, ответивший 200 вместо 206, обнуляет .part. Хэш не сошёлся — файл выбрасывается,
 *  ошибка запоминается, остальные качаются дальше. Архив после проверки распаковывается
 *  по одной записи через временный файл, пути с «..» отвергаются, потом сверяются check-файлы. */
public class ModelStore {
  public interface Net { boolean allowed(); }
  public interface Log { void log(String s); }
  public interface Watch { void onState(State s); }

  public static class Item {
    public final String path, sha256, tier, url, name; public final long size;
    public final boolean archive; public final String unpackTo; public final List<String[]> check;
    Item(String path, long size, String sha256, String tier, String[] src, boolean archive, String unpackTo, List<String[]> check) {
      this.path = path; this.size = size; this.sha256 = sha256; this.tier = tier; this.url = src[0]; this.name = src[1];
      this.archive = archive; this.unpackTo = unpackTo; this.check = check;
    }
    public boolean core() { return "core".equals(tier); }
    /** Что качается: файл по пути или zip рядом с каталогом. */
    File target(File dir) { return new File(dir, archive ? path + ".zip" : path); }
    @Override public String toString() { return path; }
  }

  /** Снимок состояния для экрана и уведомления. Меняется только под замком, наружу — копией. */
  public static class State {
    public String phase = IDLE, tier = "", file = "", message = "";
    public long done, total, bps; public int retryIn;
    public List<String> errors = new ArrayList<>();
    /** Итог последней проверки: чего не хватает по ярусам; checked — проверка уже была (до неё
     *  нули ничего не значат, и экран первого запуска по ним прятаться не должен). */
    public boolean checked; public int coreMissing, optMissing; public long coreBytes, optBytes;
    public boolean busy() { return DOWN.equals(phase) || WAIT.equals(phase) || RETRY.equals(phase) || UNPACK.equals(phase) || CHECK.equals(phase); }
    public State copy() {
      State s = new State();
      s.phase = phase; s.tier = tier; s.file = file; s.message = message; s.done = done; s.total = total; s.bps = bps; s.retryIn = retryIn;
      s.errors = new ArrayList<>(errors); s.checked = checked; s.coreMissing = coreMissing; s.optMissing = optMissing; s.coreBytes = coreBytes; s.optBytes = optBytes;
      return s;
    }
  }
  public static final String IDLE = "idle", CHECK = "check", WAIT = "wait", DOWN = "down", RETRY = "retry", UNPACK = "unpack",
                             DONE = "done", PAUSED = "paused", ERROR = "error";

  public static class Plan {
    public final List<Item> need = new ArrayList<>(); public long bytes; public int have;
    public boolean complete() { return need.isEmpty(); }
  }

  public final File dir; public final String app; public final List<Item> items = new ArrayList<>();
  final Net net; final Watch watch; final Log log;
  /** Стенд: все адреса заменяются на base/<имя файла> — локальный сервер вместо Hugging Face. Не сохраняется. */
  public volatile String baseOverride;
  /** Сколько байт прохэшировано за всё время — тесты проверяют, что повторная проверка не читает файлы. */
  public volatile long hashedBytes;
  /** Первая пауза перед повтором, мс; тесты уменьшают. */
  public volatile int backoffMs = 2000, maxBackoffMs = 60000, connectMs = 15000, readMs = 30000, waitMs = 2000;
  /** Запас места сверх размера загрузки: распаковка голосов и журнал. */
  public volatile long spareBytes = 150L << 20;
  final State st = new State();
  final Map<String, JSONObject> verified = new HashMap<>();
  volatile boolean cancelled; volatile Thread runner; volatile HttpURLConnection cur; long lastWatch;

  public ModelStore(File dir, String manifestJson, Net net, Watch watch, Log log) {
    this.dir = dir; this.net = net; this.watch = watch; this.log = log;
    // На Android JSONException проверяемое, в org.json с Maven — нет: ловим, чтобы код был один.
    try {
      JSONObject m = new JSONObject(manifestJson);
      app = m.optString("app", "");
      JSONArray fs = m.optJSONArray("files");
      for (int i = 0; fs != null && i < fs.length(); i++) {
        JSONObject f = fs.getJSONObject(i);
        items.add(new Item(f.getString("path"), f.getLong("size"), f.getString("sha256"), f.optString("tier", "core"),
                           srcUrl(f.getJSONObject("source")), false, null, null));
      }
      JSONArray as = m.optJSONArray("archives");
      for (int i = 0; as != null && i < as.length(); i++) {
        JSONObject a = as.getJSONObject(i);
        List<String[]> check = new ArrayList<>();
        JSONArray cs = a.optJSONArray("check");
        for (int j = 0; cs != null && j < cs.length(); j++) check.add(new String[]{cs.getJSONObject(j).getString("path"), cs.getJSONObject(j).getString("sha256")});
        items.add(new Item(a.getString("path"), a.getLong("size"), a.getString("sha256"), a.optString("tier", "core"),
                           srcUrl(a.getJSONObject("source")), true, a.optString("unpack_to", "."), check));
      }
    } catch (JSONException e) { throw new IllegalArgumentException("манифест моделей: " + e.getMessage()); }
    loadVerified();
  }
  /** {адрес, имя на стенде}. Имя для стенда — путь файла в репозитории HF (у OPUS-MT одинаковые
   *  имена в mt/pt2ru/ и mt/ru2pt/, одно имя файла их не различает), у прямых адресов — имя файла. */
  static String[] srcUrl(JSONObject s) throws JSONException {
    if ("hf".equals(s.optString("type")))
      return new String[]{"https://huggingface.co/" + s.getString("repo") + "/resolve/" + s.optString("revision", "main") + "/" + s.getString("file"), s.getString("file")};
    String u = s.getString("url");
    return new String[]{u, u.substring(u.lastIndexOf('/') + 1)};
  }
  String url(Item it) {
    String b = baseOverride; if (b == null || b.isEmpty()) return it.url;
    return b + (b.endsWith("/") ? "" : "/") + it.name;
  }
  public List<Item> tier(String tier) {
    List<Item> r = new ArrayList<>();
    for (Item it : items) if ("all".equals(tier) || it.tier.equals(tier)) r.add(it);
    return r;
  }
  public Item byPath(String path) { for (Item it : items) if (it.path.equals(path)) return it; return null; }

  // ---- проверка ----------------------------------------------------------------------------

  /** Только наличие и размер, без хэшей: экрану при запуске решить, показывать ли первый экран. */
  public Plan quick(String tier) { return plan(tier(tier), 0); }
  /** Наличие, размер и sha256; хэшируются только файлы, которых нет в .verified.json. */
  public Plan check(String tier) { return plan(tier(tier), 1); }
  /** Всё заново, кэш проверок не в счёт: кнопка «проверить файлы моделей». */
  public Plan verify(String tier) { return plan(tier(tier), 2); }

  Plan plan(List<Item> list, int depth) {
    Plan p = new Plan();
    for (Item it : list) {
      boolean ok = it.archive ? archiveOk(it, depth) : fileOk(new File(dir, it.path), it.size, it.sha256, depth);
      if (ok) p.have++; else { p.need.add(it); p.bytes += it.size; }
    }
    if (depth > 0) summarize();
    return p;
  }
  boolean archiveOk(Item it, int depth) {
    for (String[] c : it.check) if (!fileOk(new File(dir, c[0]), -1, c[1], depth)) return false;
    return !it.check.isEmpty();
  }
  boolean fileOk(File f, long size, String sha, int depth) {
    if (!f.isFile()) return false;
    long len = f.length(), mt = f.lastModified();
    if (size >= 0 && len != size) return false;
    if (depth == 0) return true;
    String key = rel(f);
    JSONObject v = depth < 2 ? verified.get(key) : null;
    if (v != null && v.optLong("size", -1) == len && v.optLong("mtime", -1) == mt) return sha.equalsIgnoreCase(v.optString("sha256"));
    String got;
    try { got = sha256(f); } catch (IOException e) { log.log("модели: не прочитался " + key + ": " + e); return false; }
    remember(key, len, mt, got);
    return sha.equalsIgnoreCase(got);
  }
  String rel(File f) {
    String a = f.getAbsolutePath(), d = dir.getAbsolutePath();
    return a.startsWith(d + File.separator) ? a.substring(d.length() + 1).replace(File.separatorChar, '/') : a;
  }
  String sha256(File f) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 20)) {
        byte[] b = new byte[1 << 20]; int n;
        while ((n = in.read(b)) > 0) { md.update(b, 0, n); hashedBytes += n; }
      }
      return hex(md.digest());
    } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
  }
  static String hex(byte[] d) { StringBuilder sb = new StringBuilder(); for (byte x : d) sb.append(String.format("%02x", x)); return sb.toString(); }

  void loadVerified() {
    File f = new File(dir, ".verified.json");
    if (!f.isFile()) return;
    try {
      JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      for (Iterator<String> k = o.keys(); k.hasNext();) { String key = k.next(); verified.put(key, o.getJSONObject(key)); }
    } catch (Exception e) { log.log("модели: кэш проверок не прочитан, хэширую заново: " + e); }
  }
  synchronized void remember(String key, long size, long mtime, String sha) {
    try {
      JSONObject v = new JSONObject(); v.put("size", size); v.put("mtime", mtime); v.put("sha256", sha);
      verified.put(key, v);
      dir.mkdirs();
      File f = new File(dir, ".verified.json"), tmp = new File(dir, ".verified.json.tmp");
      Files.write(tmp.toPath(), new JSONObject(verified).toString(1).getBytes(StandardCharsets.UTF_8));
      Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) { log.log("модели: кэш проверок не записан: " + e); }
  }
  /** Сводка «чего не хватает» в состоянии — по кэшу, без хэширования. */
  void summarize() {
    int cm = 0, om = 0; long cb = 0, ob = 0;
    for (Item it : items) {
      boolean ok = it.archive ? archiveOk(it, 1) : fileOk(new File(dir, it.path), it.size, it.sha256, 1);
      if (ok) continue;
      if (it.core()) { cm++; cb += it.size; } else { om++; ob += it.size; }
    }
    synchronized (st) { st.checked = true; st.coreMissing = cm; st.coreBytes = cb; st.optMissing = om; st.optBytes = ob; }
  }

  // ---- загрузка ----------------------------------------------------------------------------

  public State state() { synchronized (st) { return st.copy(); } }
  public boolean running() { Thread t = runner; return t != null && t.isAlive(); }
  /** Скачать ярус («core», «optional», «all») — то из него, чего нет. */
  public boolean start(String tier) { return start(check(tier).need, tier); }
  public boolean start(List<Item> list, String tier) {
    synchronized (this) {
      if (running()) return false;
      cancelled = false;
      final List<Item> todo = new ArrayList<>(list);
      runner = new Thread(() -> run(todo, tier), "models");
      runner.start();
      return true;
    }
  }
  /** Остановить: .part остаётся, следующий запуск продолжит с того же места. */
  public void cancel() {
    cancelled = true; Thread t = runner; if (t != null) t.interrupt();
    HttpURLConnection c = cur; if (c != null) try { c.disconnect(); } catch (Throwable e) {}   // чтение сокета не прерывается interrupt'ом
  }
  public void join(long ms) throws InterruptedException { Thread t = runner; if (t != null) t.join(ms); }

  void run(List<Item> todo, String tier) {
    long total = 0; for (Item it : todo) total += it.size;
    synchronized (st) { st.phase = CHECK; st.tier = tier; st.done = 0; st.total = total; st.bps = 0; st.file = ""; st.errors.clear(); st.message = "Проверяю место и файлы"; }
    push(true);
    long have = 0; for (Item it : todo) { File t = it.target(dir); File p = part(t); if (p.isFile()) have += Math.min(p.length(), it.size); }
    long needBytes = total - have; for (Item it : todo) if (it.archive) needBytes += it.size;   // распаковка ≈ ещё столько же
    long free = usable();
    if (free >= 0 && free < needBytes + spareBytes) {
      synchronized (st) { st.phase = ERROR; st.message = "Мало места: нужно ещё " + mb(needBytes + spareBytes - free) + " МБ"; st.errors.add("место: " + st.message); }
      log.log("⬇ модели (" + tier + "): " + state().message + " (свободно " + mb(free) + " МБ)");   // на телефоне с 1,9 ГБ свободного это единственный след
      push(true); return;
    }
    int ok = 0;
    for (Item it : todo) {
      if (cancelled) break;
      if (fetch(it)) ok++;
    }
    summarize();
    synchronized (st) {
      if (cancelled) { st.phase = PAUSED; st.message = "Остановлено: скачано " + mb(st.done) + " из " + mb(st.total) + " МБ, продолжится с этого места"; }
      else if (st.errors.isEmpty()) { st.phase = DONE; st.message = "Готово: " + ok + " из " + todo.size(); }
      else { st.phase = ERROR; st.message = "Не скачалось: " + st.errors.size() + " из " + todo.size() + " — " + st.errors.get(0); }
      st.file = ""; st.bps = 0;
    }
    log.log("⬇ модели (" + tier + "): " + state().message);
    push(true);
  }
  /** Свободное место в каталоге; -1 — неизвестно. Тесты подменяют. */
  protected long usable() { dir.mkdirs(); long u = dir.getUsableSpace(); return u > 0 ? u : -1; }
  static File part(File t) { return new File(t.getPath() + ".part"); }

  /** Один элемент до конца: докачка с повторами, хэш, для архива — распаковка и проверка.
   *  false — отменено или ошибка (уже в errors). */
  boolean fetch(Item it) {
    File dst = it.target(dir); File pt = part(dst);
    dst.getParentFile().mkdirs();
    long base; synchronized (st) { base = st.done; st.file = it.path; }
    // Полный и верный файл уже есть (архив не распакован после падения) — сразу к распаковке.
    boolean got = dst.isFile() && dst.length() == it.size && fileOk(dst, it.size, it.sha256, 1);
    if (!got && dst.isFile()) dst.delete();
    int backoff = backoffMs;
    while (!got) {
      if (cancelled) return false;
      if (!net.allowed()) { synchronized (st) { st.phase = WAIT; st.message = "Нет подходящей сети"; st.bps = 0; } push(false); if (!nap(waitMs)) return false; continue; }
      String why;
      try {
        int r = once(it, dst, pt, base);
        if (r == R_OK) { got = true; break; }
        if (r == R_FATAL || r == R_CANCEL) return false;
        why = lastWhy;
      } catch (IOException e) { why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
      synchronized (st) { st.phase = RETRY; st.retryIn = backoff / 1000; st.message = "Связь прервалась (" + why + ") · повтор через " + Math.max(1, backoff / 1000) + " с"; st.bps = 0; }
      push(true);
      if (!nap(backoff)) return false;
      backoff = Math.min(maxBackoffMs, backoff * 2);
    }
    synchronized (st) { st.done = base + it.size; }
    if (!it.archive) { push(true); return true; }
    synchronized (st) { st.phase = UNPACK; st.message = "Распаковка " + it.path; }
    push(true);
    try { unpack(it, dst); }
    catch (IOException e) { fail(it, "распаковка: " + e.getMessage()); dst.delete(); return false; }
    for (String[] c : it.check)
      if (!fileOk(new File(dir, c[0]), -1, c[1], 2)) { fail(it, "после распаковки не сошёлся " + c[0]); return false; }
    dst.delete();
    push(true);
    return true;
  }
  static final int R_OK = 0, R_RETRY = 1, R_FATAL = 2, R_CANCEL = 3; volatile String lastWhy = "";

  /** Одна попытка: с Range, если .part уже есть. */
  int once(Item it, File dst, File pt, long base) throws IOException {
    long have = pt.isFile() ? pt.length() : 0;
    if (have > it.size) { pt.delete(); have = 0; }
    MessageDigest md;
    try { md = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    if (have > 0) {                       // хэш уже скачанного куска — продолжаем поток, а не читаем файл дважды
      try (InputStream in = new BufferedInputStream(new FileInputStream(pt), 1 << 20)) { byte[] b = new byte[1 << 20]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
    }
    synchronized (st) { st.phase = DOWN; st.done = base + have; st.message = ""; }
    push(true);
    if (have < it.size) {
      HttpURLConnection c = (HttpURLConnection) new URL(url(it)).openConnection();
      cur = c;
      c.setConnectTimeout(connectMs); c.setReadTimeout(readMs); c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent", "Falar/" + app);
      c.setRequestProperty("Accept-Encoding", "identity");
      if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
      int code;
      try { code = c.getResponseCode(); } catch (IOException e) { if (cancelled) return R_CANCEL; throw e; }
      boolean append = have > 0;
      if (code == 200 && have > 0) { append = false; have = 0; md.reset(); synchronized (st) { st.done = base; } }   // Range не понят — с нуля
      else if (code == 416) { c.disconnect(); pt.delete(); lastWhy = "HTTP 416"; return R_RETRY; }
      else if (code != 200 && code != 206) { c.disconnect(); lastWhy = "HTTP " + code; if (code == 408 || code == 429 || code >= 500) return R_RETRY; fail(it, "HTTP " + code); return R_FATAL; }
      long t0 = System.nanoTime(), b0 = have, since = 0;
      try (InputStream in = c.getInputStream(); OutputStream out = new BufferedOutputStream(new FileOutputStream(pt, append), 1 << 18)) {
        byte[] b = new byte[1 << 16]; int n;
        while ((n = in.read(b)) > 0) {
          out.write(b, 0, n); md.update(b, 0, n); have += n; since += n;
          if (have > it.size) { lastWhy = "сервер прислал больше, чем в манифесте"; pt.delete(); return R_RETRY; }
          if (since >= (1 << 20)) {
            since = 0;
            long now = System.nanoTime();
            synchronized (st) { st.done = base + have; if (now - t0 >= 2_000_000_000L) { st.bps = (have - b0) * 1_000_000_000L / (now - t0); t0 = now; b0 = have; } }
            push(false);
            if (cancelled) return R_CANCEL;
            if (!net.allowed()) { lastWhy = "сеть пропала"; return R_RETRY; }
          }
        }
      } catch (IOException e) { if (cancelled) return R_CANCEL; throw e; }
      finally { c.disconnect(); cur = null; }
      if (have < it.size) { lastWhy = "обрыв на " + mb(have) + " МБ"; return R_RETRY; }
    }
    String got = hex(md.digest());
    if (!got.equalsIgnoreCase(it.sha256)) { pt.delete(); fail(it, "хэш не сошёлся"); return R_FATAL; }
    Files.move(pt.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    remember(rel(dst), dst.length(), dst.lastModified(), got);
    return R_OK;
  }
  void fail(Item it, String why) {
    synchronized (st) { st.errors.add(it.path + ": " + why); }
    log.log("⬇ " + it.path + ": " + why);
  }
  /** Распаковка по одной записи через временный файл; путь наружу каталога — ошибка всего архива. */
  void unpack(Item it, File zip) throws IOException {
    File root = new File(dir, it.unpackTo == null ? "." : it.unpackTo).getCanonicalFile();
    try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip), 1 << 20))) {
      ZipEntry e;
      byte[] b = new byte[1 << 16];
      while ((e = z.getNextEntry()) != null) {
        File out = new File(root, e.getName()).getCanonicalFile();
        if (!out.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("небезопасный путь в архиве: " + e.getName());
        if (e.isDirectory()) { out.mkdirs(); continue; }
        out.getParentFile().mkdirs();
        File tmp = new File(out.getPath() + ".tmp");
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 18)) { int n; while ((n = z.read(b)) > 0) o.write(b, 0, n); }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (cancelled) throw new IOException("остановлено");
      }
    }
  }
  boolean nap(int ms) {
    long end = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < end) {
      if (cancelled) return false;
      try { Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis()))); } catch (InterruptedException e) { return !cancelled; }
    }
    return !cancelled;
  }
  void push(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && now - lastWatch < 250) return;
    lastWatch = now;
    if (watch != null) watch.onState(state());
  }
  public static String mb(long b) { return String.format(Locale.ROOT, "%.1f", b / 1e6); }
  /** Строка прогресса для экрана и уведомления. */
  public static String describe(State s) {
    switch (s.phase) {
      case DOWN: {
        int pct = s.total > 0 ? (int) (s.done * 100 / s.total) : 0;
        return "Загрузка " + pct + " % · " + mb(s.done) + " из " + mb(s.total) + " МБ" + (s.bps > 0 ? " · " + mb(s.bps) + " МБ/с" : "") + (s.file.isEmpty() ? "" : " · " + s.file);
      }
      case WAIT: return "Ждём сеть · скачано " + mb(s.done) + " из " + mb(s.total) + " МБ";
      default: return s.message;
    }
  }
}
