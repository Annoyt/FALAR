package dev.agenttranslator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Настольные тесты загрузчика моделей: тот же ModelStore, что в APK, против локального сервера
 *  с обрывами, битыми файлами, 404, 503, отменой и архивом с путём наружу. Запуск: bash bench/apk/test.sh.
 *  Без JUnit: одна main, счётчик провалов, ненулевой код выхода. */
public class ModelStoreTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }

  /** Сервер: файлы из памяти, Range, и неисправности по имени файла. */
  static class Srv {
    final HttpServer hs; final Map<String, byte[]> files = new HashMap<>();
    final Map<String, Integer> dropAfter = new HashMap<>(), dropTimes = new HashMap<>(), status = new HashMap<>(), statusTimes = new HashMap<>(), throttle = new HashMap<>();
    final Set<String> ignoreRange = new HashSet<>();
    final List<String[]> requests = Collections.synchronizedList(new ArrayList<>());   // {имя, Range}
    long served = 0;
    Srv() throws IOException {
      hs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      hs.createContext("/", this::handle); hs.start();
    }
    String base() { return "http://127.0.0.1:" + hs.getAddress().getPort(); }
    int count(String name) { int n = 0; synchronized (requests) { for (String[] r : requests) if (r[0].equals(name)) n++; } return n; }
    List<String> ranges(String name) { List<String> l = new ArrayList<>(); synchronized (requests) { for (String[] r : requests) if (r[0].equals(name)) l.add(r[1]); } return l; }
    void handle(HttpExchange x) throws IOException {
      String name = x.getRequestURI().getPath().substring(1);
      String range = x.getRequestHeaders().getFirst("Range");
      requests.add(new String[]{name, range});
      byte[] data = files.get(name);
      Integer st = status.get(name);
      if (st != null && statusTimes.getOrDefault(name, 1) > 0) { statusTimes.put(name, statusTimes.getOrDefault(name, 1) - 1); x.sendResponseHeaders(st, -1); x.close(); return; }
      if (data == null) { x.sendResponseHeaders(404, -1); x.close(); return; }
      int from = 0, code = 200;
      if (range != null && range.startsWith("bytes=") && !ignoreRange.contains(name)) {
        from = Integer.parseInt(range.substring(6, range.indexOf('-')));
        if (from >= data.length) { x.sendResponseHeaders(416, -1); x.close(); return; }
        code = 206; x.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + (data.length - 1) + "/" + data.length);
      }
      int len = data.length - from;
      int drop = dropAfter.getOrDefault(name, -1);
      boolean doDrop = drop >= 0 && dropTimes.getOrDefault(name, 0) > 0;
      if (doDrop) dropTimes.put(name, dropTimes.get(name) - 1);
      x.sendResponseHeaders(code, len);
      OutputStream o = x.getResponseBody();
      int sent = 0, chunk = 1 << 16, th = throttle.getOrDefault(name, 0);
      try {
        while (sent < len) {
          int n = Math.min(chunk, len - sent);
          if (doDrop && sent + n > drop) n = Math.max(0, drop - sent);
          if (n == 0) break;
          o.write(data, from + sent, n); sent += n; served += n;
          if (th > 0) try { Thread.sleep(th); } catch (InterruptedException e) { break; }
        }
        if (!doDrop) o.close();
        else x.close();                                  // недописанный ответ: сервер рвёт соединение
      } catch (IOException e) { /* так и задумано */ }
    }
  }

  static byte[] rnd(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }
  static String sha(byte[] b) throws Exception { return ModelStore.hex(MessageDigest.getInstance("SHA-256").digest(b)); }
  static String shaFile(File f) throws Exception { return sha(Files.readAllBytes(f.toPath())); }
  static byte[] zip(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(bo)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); if (e.getValue() != null) z.write(e.getValue()); z.closeEntry(); }
    }
    return bo.toByteArray();
  }
  static String file(String path, byte[] data, String tier, String src) throws Exception {
    return "{\"path\":\"" + path + "\",\"size\":" + data.length + ",\"sha256\":\"" + sha(data) + "\",\"tier\":\"" + tier + "\",\"source\":" + src + "}";
  }
  static String hf(String name) { return "{\"type\":\"hf\",\"repo\":\"you/repo\",\"file\":\"" + name + "\",\"revision\":\"main\"}"; }
  static void del(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) del(c); f.delete(); }
  static File tmpDir(String n) throws IOException { File d = Files.createTempDirectory("mst-" + n).toFile(); d.delete(); return d; }
  static ModelStore.State waitDone(ModelStore s) throws Exception { s.join(20000); return s.state(); }
  static final List<String> logs = new ArrayList<>();
  static ModelStore store(File d, String man, Srv srv, ModelStore.Net net) {
    ModelStore s = new ModelStore(d, man, net, st -> {}, l -> { synchronized (logs) { logs.add(l); } });
    s.baseOverride = srv.base(); s.backoffMs = 20; s.maxBackoffMs = 100; s.waitMs = 50;
    return s;
  }
  static ModelStore store(File d, String man, Srv srv) { return store(d, man, srv, () -> true); }

  /** Один прогон: счётчики обнуляются, потому что PIT гоняет набор много раз в одной JVM. */
  public static int run() throws Exception {
    fails = 0; checks = 0;
    synchronized (logs) { logs.clear(); }
    Srv srv = new Srv();
    byte[] big = rnd((3 << 20) + 123, 1), mid = rnd((1 << 20) + 7, 2), small = rnd(10_000, 3), opt1 = rnd(200_000, 4), opt2 = rnd(50_000, 5);
    byte[] onnx = rnd(700_000, 6), tokens = "a 1\nb 2\n".getBytes(StandardCharsets.UTF_8), esp = rnd(3000, 7);
    Map<String, byte[]> ents = new LinkedHashMap<>(); ents.put("tts_x/", null); ents.put("tts_x/a.onnx", onnx); ents.put("tts_x/tokens.txt", tokens); ents.put("tts_x/espeak/x.txt", esp);
    byte[] zipData = zip(ents);
    srv.files.put("big.onnx", big); srv.files.put("mt/x/mid.onnx", mid); srv.files.put("small.txt", small); srv.files.put("opt1.bin", opt1); srv.files.put("opt2.bin", opt2); srv.files.put("tts_x.zip", zipData);
    String man = "{\"manifest_version\":1,\"app\":\"0.21.0\",\"files\":["
      + file("asr/big.onnx", big, "core", hf("big.onnx")) + "," + file("mt/x/mid.onnx", mid, "core", hf("mt/x/mid.onnx")) + ","
      + file("small.txt", small, "core", "{\"type\":\"url\",\"url\":\"https://example.org/dl/small.txt\"}") + ","
      + file("llm/opt1.bin", opt1, "optional", hf("opt1.bin")) + "," + file("speaker/opt2.bin", opt2, "optional", hf("opt2.bin")) + "],"
      + "\"archives\":[{\"path\":\"tts_x\",\"kind\":\"zip\",\"size\":" + zipData.length + ",\"sha256\":\"" + sha(zipData) + "\",\"tier\":\"core\",\"source\":" + hf("tts_x.zip")
      + ",\"unpack_to\":\".\",\"check\":[{\"path\":\"tts_x/a.onnx\",\"sha256\":\"" + sha(onnx) + "\"},{\"path\":\"tts_x/tokens.txt\",\"sha256\":\"" + sha(tokens) + "\"}]}]}";
    long coreBytes = big.length + mid.length + small.length + zipData.length;

    // T1 разбор манифеста
    File d = tmpDir("t1");
    ModelStore s = new ModelStore(d, man, () -> true, st -> {}, l -> {});
    eq(s.items.size(), 6, "T1 элементов");
    eq(s.tier("core").size(), 4, "T1 core"); eq(s.tier("optional").size(), 2, "T1 optional"); eq(s.tier("all").size(), 6, "T1 all");
    eq(s.byPath("asr/big.onnx").url, "https://huggingface.co/you/repo/resolve/main/big.onnx", "T1 адрес hf");
    eq(s.byPath("small.txt").url, "https://example.org/dl/small.txt", "T1 адрес url");
    ModelStore.Item ar = s.byPath("tts_x"); ok(ar != null && ar.archive && ar.check.size() == 2 && ".".equals(ar.unpackTo), "T1 архив");
    eq(s.app, "0.21.0", "T1 версия");
    s.baseOverride = srv.base() + "/";
    eq(s.url(s.byPath("small.txt")), srv.base() + "/small.txt", "T15 подмена адреса (url)");
    eq(s.url(s.byPath("asr/big.onnx")), srv.base() + "/big.onnx", "T15 подмена адреса (hf)");
    eq(s.url(s.byPath("mt/x/mid.onnx")), srv.base() + "/mt/x/mid.onnx", "T15 подмена адреса (hf с путём — одинаковые имена в разных каталогах)");

    // T2 пустой каталог
    ModelStore.Plan p = s.quick("core"); eq(p.need.size(), 4, "T2 quick: нет всего core"); eq(p.bytes, coreBytes, "T2 quick: байты"); eq(p.have, 0, "T2 quick: есть 0");
    ok(!s.state().checked, "T2 до проверки состояние помечено как непроверенное");
    p = s.check("all"); eq(p.need.size(), 6, "T2 check all"); eq(s.hashedBytes, 0L, "T2 без файлов ничего не хэшируется");
    ok(s.state().checked, "T2 после проверки — проверенное");
    ModelStore.State st = s.state(); eq(st.coreMissing, 4, "T2 сводка core"); eq(st.optMissing, 2, "T2 сводка optional"); eq(st.coreBytes, coreBytes, "T2 сводка байты");

    // T3 загрузка core целиком
    s = store(d, man, srv);
    ok(s.start("core"), "T3 старт");
    ok(!s.start("core"), "T3 второй старт отклонён, пока идёт первый");
    st = waitDone(s);
    eq(st.phase, ModelStore.DONE, "T3 фаза done: " + st.message + " " + st.errors);
    eq(st.errors.size(), 0, "T3 без ошибок");
    eq(st.done, coreBytes, "T3 done = всё"); eq(st.total, coreBytes, "T3 total");
    eq(shaFile(new File(d, "asr/big.onnx")), sha(big), "T3 big на месте и верный");
    eq(shaFile(new File(d, "mt/x/mid.onnx")), sha(mid), "T3 mid"); eq(shaFile(new File(d, "small.txt")), sha(small), "T3 small");
    eq(shaFile(new File(d, "tts_x/a.onnx")), sha(onnx), "T3 архив распакован: a.onnx");
    eq(shaFile(new File(d, "tts_x/espeak/x.txt")), sha(esp), "T3 архив: вложенный каталог");
    ok(!new File(d, "tts_x.zip").exists() && !new File(d, "tts_x.zip.part").exists(), "T3 zip удалён после распаковки");
    ok(!new File(d, "tts_x/a.onnx.tmp").exists(), "T3 временных файлов распаковки нет");
    ok(new File(d, ".verified.json").isFile(), "T3 кэш проверок записан");
    eq(s.hashedBytes, (long) onnx.length + tokens.length, "T3 хэшировались только check-файлы архива (скачанное хэшируется в потоке)");
    eq(s.check("core").need.size(), 0, "T3 после загрузки core полон");
    eq(s.state().coreMissing, 0, "T3 сводка core 0"); eq(s.state().optMissing, 2, "T3 сводка optional 2");
    ok(!new File(d, "llm/opt1.bin").exists(), "T3 необязательное не трогалось");
    int reqBefore = srv.requests.size();

    // T4 повторная проверка новым экземпляром — по кэшу, без чтения файлов
    s = store(d, man, srv);
    eq(s.check("all").need.size(), 2, "T4 нет только optional");
    eq(s.hashedBytes, 0L, "T4 повторная проверка не хэширует");
    eq(srv.requests.size(), reqBefore, "T4 без запросов в сеть");

    // T5 обрыв на первой попытке — докачка с Range
    d = tmpDir("t5"); srv.requests.clear(); srv.served = 0;
    srv.dropAfter.put("big.onnx", (1 << 20) + 300_000); srv.dropTimes.put("big.onnx", 1);
    s = store(d, man, srv); s.start(Collections.singletonList(s.byPath("asr/big.onnx")), "core");
    st = waitDone(s);
    eq(st.phase, ModelStore.DONE, "T5 done: " + st.message);
    eq(shaFile(new File(d, "asr/big.onnx")), sha(big), "T5 файл верный");
    List<String> rg = srv.ranges("big.onnx");
    eq(rg.size(), 2, "T5 два запроса");
    ok(rg.get(0) == null && rg.get(1) != null && rg.get(1).startsWith("bytes="), "T5 второй запрос с Range: " + rg);
    ok(srv.served < 2L * big.length, "T5 докачка, а не сначала: отдано " + srv.served + " из " + big.length);
    ok(!new File(d, "asr/big.onnx.part").exists(), "T5 .part убран");
    srv.dropAfter.clear(); srv.dropTimes.clear();

    // T6 отмена и продолжение
    d = tmpDir("t6"); srv.requests.clear(); srv.throttle.put("big.onnx", 4);
    s = store(d, man, srv); s.start(Collections.singletonList(s.byPath("asr/big.onnx")), "core");
    long t0 = System.currentTimeMillis();
    while (System.currentTimeMillis() - t0 < 5000 && !(s.state().phase.equals(ModelStore.DOWN) && s.state().done > (1 << 20))) Thread.sleep(5);
    s.cancel(); st = waitDone(s);
    eq(st.phase, ModelStore.PAUSED, "T6 фаза paused");
    File part = new File(d, "asr/big.onnx.part");
    ok(part.isFile() && part.length() > 0 && part.length() < big.length, "T6 .part остался: " + part.length());
    long partLen = part.length();
    ok(st.message.contains("продолжится"), "T6 сообщение об остановке: " + st.message);
    srv.throttle.clear();
    s = store(d, man, srv); s.start(Collections.singletonList(s.byPath("asr/big.onnx")), "core"); st = waitDone(s);
    eq(st.phase, ModelStore.DONE, "T6 продолжение done");
    rg = srv.ranges("big.onnx");
    eq(rg.get(rg.size() - 1), "bytes=" + partLen + "-", "T6 продолжение ровно с длины .part");
    eq(shaFile(new File(d, "asr/big.onnx")), sha(big), "T6 файл верный");

    // T7 сервер не понимает Range (200 на докачку) — начинаем с нуля, файл всё равно верный
    d = tmpDir("t7"); srv.requests.clear(); srv.served = 0;
    srv.ignoreRange.add("big.onnx"); srv.dropAfter.put("big.onnx", 1 << 20); srv.dropTimes.put("big.onnx", 1);
    s = store(d, man, srv); s.start(Collections.singletonList(s.byPath("asr/big.onnx")), "core"); st = waitDone(s);
    eq(st.phase, ModelStore.DONE, "T7 done: " + st.message);
    eq(shaFile(new File(d, "asr/big.onnx")), sha(big), "T7 файл верный после 200 вместо 206");
    ok(srv.served > big.length, "T7 действительно качалось заново: " + srv.served);
    srv.ignoreRange.clear(); srv.dropAfter.clear(); srv.dropTimes.clear();

    // T8 битый файл: хэш не сошёлся, файла нет, остальное скачано
    d = tmpDir("t8"); byte[] midGood = srv.files.get("mt/x/mid.onnx"); srv.files.put("mt/x/mid.onnx", rnd(mid.length, 99));
    s = store(d, man, srv); s.start("core"); st = waitDone(s);
    eq(st.phase, ModelStore.ERROR, "T8 фаза error");
    eq(st.errors.size(), 1, "T8 одна ошибка: " + st.errors);
    ok(st.errors.get(0).contains("mt/x/mid.onnx") && st.errors.get(0).contains("хэш"), "T8 ошибка про хэш: " + st.errors);
    ok(!new File(d, "mt/x/mid.onnx").exists() && !new File(d, "mt/x/mid.onnx.part").exists(), "T8 битый файл выброшен");
    ok(new File(d, "asr/big.onnx").isFile() && new File(d, "tts_x/a.onnx").isFile(), "T8 остальное скачано");
    eq(s.check("core").need.size(), 1, "T8 не хватает одного");
    srv.files.put("mt/x/mid.onnx", midGood);

    // T9 404
    d = tmpDir("t9"); srv.files.remove("small.txt"); srv.requests.clear();
    s = store(d, man, srv); s.start("core"); st = waitDone(s);
    eq(st.phase, ModelStore.ERROR, "T9 фаза error");
    ok(st.errors.size() == 1 && st.errors.get(0).contains("HTTP 404"), "T9 ошибка 404: " + st.errors);
    ok(st.message.contains("Не скачалось: 1 из 4"), "T9 сообщение: " + st.message);
    eq(srv.count("small.txt"), 1, "T9 404 не повторяется");
    srv.files.put("small.txt", small);

    // T10 новый манифест: изменился один файл — докачивается только он
    d = tmpDir("t10"); s = store(d, man, srv); s.start("core"); waitDone(s);
    byte[] small2 = rnd(10_000, 33); srv.files.put("small.txt", small2); srv.requests.clear();
    String man2 = man.replace(sha(small), sha(small2));
    s = store(d, man2, srv); p = s.check("core");
    eq(p.need.size(), 1, "T10 нужен один"); eq(p.need.get(0).path, "small.txt", "T10 именно изменённый");
    eq(s.hashedBytes, 0L, "T10 решение по кэшу, без хэширования");
    s.start("core"); st = waitDone(s);
    eq(st.phase, ModelStore.DONE, "T10 done"); eq(srv.requests.size(), 1, "T10 один запрос");
    eq(shaFile(new File(d, "small.txt")), sha(small2), "T10 файл заменён");
    srv.files.put("small.txt", small);

    // T11 подменённый файл: тот же размер, другое содержимое
    d = tmpDir("t11"); s = store(d, man, srv); s.start("core"); waitDone(s);
    File sf = new File(d, "small.txt"); long mt = sf.lastModified();
    Files.write(sf.toPath(), rnd(10_000, 44)); sf.setLastModified(mt);
    s = store(d, man, srv);
    // размер и mtime как в кэше — считается тем же (принятое допущение, см. заголовок ModelStore)
    eq(s.check("core").need.size(), 0, "T11 тот же размер и mtime — по кэшу верен"); eq(s.hashedBytes, 0L, "T11 без хэширования");
    eq(s.verify("core").need.get(0).path, "small.txt", "T18 «проверить файлы» хэширует заново и ловит подмену");
    eq(s.hashedBytes, (long) big.length + mid.length + 10_000 + onnx.length + tokens.length, "T18 verify прочитал все файлы яруса");
    eq(s.check("core").need.size(), 1, "T18 после verify кэш помнит настоящий хэш");
    d = tmpDir("t11b"); s = store(d, man, srv); s.start("core"); waitDone(s);
    sf = new File(d, "small.txt"); mt = sf.lastModified(); Files.write(sf.toPath(), rnd(10_000, 44)); sf.setLastModified(mt + 5000);
    s = store(d, man, srv); eq(s.check("core").need.get(0).path, "small.txt", "T11 другой mtime — перехэширован и отвергнут");
    eq(s.hashedBytes, 10_000L, "T11 хэшировался только он");

    // T12 архив с путём наружу
    d = tmpDir("t12"); Map<String, byte[]> bad = new LinkedHashMap<>(); bad.put("tts_x/a.onnx", onnx); bad.put("../evil.txt", esp);
    byte[] badZip = zip(bad); srv.files.put("tts_x.zip", badZip);
    String man3 = man.replace(sha(zipData), sha(badZip)).replace("\"size\":" + zipData.length, "\"size\":" + badZip.length);
    s = store(d, man3, srv); s.start(Collections.singletonList(s.byPath("tts_x")), "core"); st = waitDone(s);
    eq(st.phase, ModelStore.ERROR, "T12 error");
    ok(st.errors.size() == 1 && st.errors.get(0).contains("небезопасный"), "T12 причина: " + st.errors);
    ok(!new File(d.getParentFile(), "evil.txt").exists(), "T12 наружу ничего не записано");
    ok(!new File(d, "tts_x.zip").exists(), "T12 архив выброшен");
    srv.files.put("tts_x.zip", zipData);

    // T13 нет подходящей сети — ждём, потом качаем
    d = tmpDir("t13"); AtomicBoolean allow = new AtomicBoolean(false);
    s = store(d, man, srv, allow::get); s.start(Collections.singletonList(s.byPath("small.txt")), "core");
    Thread.sleep(200); eq(s.state().phase, ModelStore.WAIT, "T13 фаза wait");
    ok(ModelStore.describe(s.state()).startsWith("Ждём сеть"), "T13 описание: " + ModelStore.describe(s.state()));
    allow.set(true); st = waitDone(s); eq(st.phase, ModelStore.DONE, "T13 после появления сети done");

    // T14 мало места — ни одного запроса
    d = tmpDir("t14"); srv.requests.clear();
    s = new ModelStore(d, man, () -> true, x -> {}, l -> { synchronized (logs) { logs.add(l); } }) { @Override protected long usable() { return 1000; } };
    s.baseOverride = srv.base(); s.start("core"); st = waitDone(s);
    eq(st.phase, ModelStore.ERROR, "T14 error"); ok(st.message.startsWith("Мало места"), "T14 сообщение: " + st.message);
    eq(srv.requests.size(), 0, "T14 без запросов");
    ok(String.join("\n", logs).contains("Мало места"), "T14 причина попала в журнал, а не только в состояние");

    // T16 часть необязательного
    d = tmpDir("t16"); s = store(d, man, srv); s.start("core"); waitDone(s);
    s.start(Collections.singletonList(s.byPath("speaker/opt2.bin")), "optional"); st = waitDone(s);
    eq(st.phase, ModelStore.DONE, "T16 done"); ok(new File(d, "speaker/opt2.bin").isFile() && !new File(d, "llm/opt1.bin").exists(), "T16 только выбранное");
    eq(s.check("optional").need.size(), 1, "T16 остался один необязательный");
    eq(s.state().optMissing, 1, "T16 сводка"); eq(s.state().optBytes, (long) opt1.length, "T16 сводка байты");

    // T17 503 дважды, потом ок: повтор с растущей паузой
    d = tmpDir("t17"); srv.status.put("small.txt", 503); srv.statusTimes.put("small.txt", 2); srv.requests.clear();
    s = store(d, man, srv); t0 = System.currentTimeMillis(); s.start(Collections.singletonList(s.byPath("small.txt")), "core"); st = waitDone(s);
    eq(st.phase, ModelStore.DONE, "T17 done после 503"); eq(srv.count("small.txt"), 3, "T17 три запроса");
    ok(System.currentTimeMillis() - t0 >= 60, "T17 паузы 20+40 мс выдержаны");
    srv.status.clear(); srv.statusTimes.clear();

    // T19 после падения остался скачанный zip: не качаем заново, распаковываем
    d = tmpDir("t19"); d.mkdirs(); Files.write(new File(d, "tts_x.zip").toPath(), zipData); srv.requests.clear();
    s = store(d, man, srv); s.start(Collections.singletonList(s.byPath("tts_x")), "core"); st = waitDone(s);
    eq(st.phase, ModelStore.DONE, "T19 done"); eq(srv.count("tts_x.zip"), 0, "T19 без запроса");
    eq(shaFile(new File(d, "tts_x/tokens.txt")), sha(tokens), "T19 распаковано");

    // T21 «проверить файлы»: сообщение до и после, и оно не пустое ни в одном исходе
    d = tmpDir("t21"); final List<String> seen = new ArrayList<>();
    ModelStore vs = new ModelStore(d, man, () -> true, x -> { synchronized (seen) { seen.add(x.phase + "|" + x.message); } }, l -> {});
    vs.baseOverride = srv.base(); vs.start("core"); waitDone(vs);
    synchronized (seen) { seen.clear(); }
    p = vs.verifyNow("core");
    eq(p.need.size(), 0, "T21 всё на месте");
    ok(seen.size() >= 2, "T21 состояние обновилось дважды: " + seen.size());
    ok(seen.get(0).startsWith(ModelStore.CHECK + "|Проверяю"), "T21 сообщение до: " + seen.get(0));
    ok(seen.get(seen.size() - 1).contains("все файлы на месте"), "T21 сообщение после: " + seen.get(seen.size() - 1));
    Files.write(new File(d, "small.txt").toPath(), rnd(10_000, 77));
    p = vs.verifyNow("core");
    eq(p.need.size(), 1, "T21 подмена найдена");
    ok(vs.state().message.contains("не сошлось или нет 1") && vs.state().message.contains("small.txt"), "T21 сообщение о несовпавшем: " + vs.state().message);
    eq(vs.state().phase, ModelStore.IDLE, "T21 фаза после проверки — не занято");

    // T20 строки прогресса
    ModelStore.State ds = new ModelStore.State(); ds.phase = ModelStore.DOWN; ds.done = 812_000_000; ds.total = 1_981_000_000; ds.bps = 6_200_000; ds.file = "asr_multi/encoder.int8.onnx";
    eq(ModelStore.describe(ds), "Загрузка 40 % · 812.0 из 1981.0 МБ · 6.2 МБ/с · asr_multi/encoder.int8.onnx", "T20 строка загрузки");

    srv.hs.stop(0);
    // R: замена файлов — новый файл вместо двух прежних (перевод с трёх сессий на две, 0.23.0)
    byte[] enc = rnd(300_000, 21), dec = rnd(400_000, 22), kv = rnd(350_000, 23), past = rnd(250_000, 24);
    srv = new Srv();                                                                   // прежний уже остановлен выше
    srv.files.put("mt/y/kv.onnx", kv); srv.files.put("mt/y/past.onnx", past);          // прежних на сервере нет вовсе
    String rman = "{\"manifest_version\":1,\"app\":\"0.23.0\",\"files\":["
      + file("mt/y/kv.onnx", kv, "core", hf("mt/y/kv.onnx")).replaceFirst("\\}$", ",\"replaces\":[{\"path\":\"mt/y/enc.onnx\",\"size\":" + enc.length
        + ",\"sha256\":\"" + sha(enc) + "\"},{\"path\":\"mt/y/dec.onnx\",\"size\":" + dec.length + ",\"sha256\":\"" + sha(dec) + "\"}]}") + ","
      + file("mt/y/past.onnx", past, "core", hf("mt/y/past.onnx")) + "]}";
    File rd = tmpDir("r1");
    ModelStore r = store(rd, rman, srv);
    eq(r.byPath("mt/y/kv.onnx").replaces.size(), 2, "R0 замена разобрана из манифеста");
    ModelStore.Plan rp = r.check("core");
    eq(rp.need.size(), 2, "R1 новая установка: нужны новый файл и декодер");
    ok(rp.need.contains(r.byPath("mt/y/kv.onnx")), "R1 среди нужных — новый файл, прежних в плане нет");
    eq(r.state().upgrade, 0, "R1 облегчать нечего");
    ok(r.start("core"), "R1 старт");
    ModelStore.State rs = waitDone(r);
    eq(rs.phase, ModelStore.DONE, "R1 скачано: " + rs.message + " " + rs.errors);
    ok(!new File(rd, "mt/y/enc.onnx").exists() && !new File(rd, "mt/y/dec.onnx").exists(), "R1 прежние файлы не качались");
    eq(r.cleanObsolete(), 0L, "R1 удалять нечего");

    // R2 обновление: прежние на месте, нового нет — работает прежний путь, никто не заставляет качать
    File ud = tmpDir("r2"); new File(ud, "mt/y").mkdirs();
    Files.write(new File(ud, "mt/y/enc.onnx").toPath(), enc); Files.write(new File(ud, "mt/y/dec.onnx").toPath(), dec);
    Files.write(new File(ud, "mt/y/past.onnx").toPath(), past);
    r = store(ud, rman, srv);
    rp = r.check("core");
    ok(rp.complete(), "R2 обязательное считается на месте: прежние файлы заменяют новый");
    rs = r.state();
    eq(rs.coreMissing, 0, "R2 экрана первого запуска не будет");
    eq(rs.upgrade, 1, "R2 но можно облегчить");
    eq(rs.upgradeBytes, (long) kv.length, "R2 и сколько качать");
    eq(r.upgrades().size(), 1, "R2 список замен");
    eq(r.cleanObsolete(), 0L, "R2 пока нового нет, прежние не трогаются");
    ok(new File(ud, "mt/y/enc.onnx").exists() && new File(ud, "mt/y/dec.onnx").exists(), "R2 прежние на месте");

    // R3 скачали замену — прежние удалены, из кэша проверок тоже
    synchronized (logs) { logs.clear(); }
    ok(r.start(r.upgrades(), "upgrade"), "R3 старт замены");
    rs = waitDone(r);
    eq(rs.phase, ModelStore.DONE, "R3 скачано: " + rs.message + " " + rs.errors);
    eq(shaFile(new File(ud, "mt/y/kv.onnx")), sha(kv), "R3 новый файл на месте и верный");
    ok(!new File(ud, "mt/y/enc.onnx").exists() && !new File(ud, "mt/y/dec.onnx").exists(), "R3 прежние удалены");
    ok(!r.verified.containsKey("mt/y/enc.onnx") && !r.verified.containsKey("mt/y/dec.onnx"), "R3 и забыты в кэше проверок");
    boolean said; synchronized (logs) { said = logs.stream().anyMatch(l -> l.contains("удалены заменённые файлы: 2") && l.contains(ModelStore.mb(enc.length + dec.length))); }
    ok(said, "R3 в журнале — сколько удалено и освобождено");
    rs = r.state(); eq(rs.upgrade + rs.coreMissing, 0, "R3 после замены — всё на месте, облегчать нечего");
    ok(!new ModelStore(ud, rman, () -> true, st2 -> {}, l -> {}).verified.containsKey("mt/y/dec.onnx"), "R3 кэш проверок на диске тоже без прежних");

    // R4 прежние битые — это не замена, новый обязателен
    File bd = tmpDir("r4"); new File(bd, "mt/y").mkdirs();
    Files.write(new File(bd, "mt/y/enc.onnx").toPath(), enc); Files.write(new File(bd, "mt/y/dec.onnx").toPath(), rnd(dec.length, 99));
    Files.write(new File(bd, "mt/y/past.onnx").toPath(), past);
    r = store(bd, rman, srv);
    rp = r.check("core");
    eq(rp.need.size(), 1, "R4 битый прежний — новый файл нужен");
    rs = r.state(); eq(rs.coreMissing, 1, "R4 и считается нехваткой"); eq(rs.upgrade, 0, "R4 а не облегчением");

    // R5 новый битый, прежние верные — прежние НЕ удаляются: иначе переводить было бы нечем
    File cd = tmpDir("r5"); new File(cd, "mt/y").mkdirs();
    Files.write(new File(cd, "mt/y/enc.onnx").toPath(), enc); Files.write(new File(cd, "mt/y/dec.onnx").toPath(), dec);
    Files.write(new File(cd, "mt/y/kv.onnx").toPath(), rnd(kv.length, 98)); Files.write(new File(cd, "mt/y/past.onnx").toPath(), past);
    r = store(cd, rman, srv);
    eq(r.cleanObsolete(), 0L, "R5 новый не сверился — удалять нельзя");
    ok(new File(cd, "mt/y/enc.onnx").exists() && new File(cd, "mt/y/dec.onnx").exists(), "R5 прежние целы");
    r.check("core"); eq(r.state().upgrade, 1, "R5 битый новый — снова предлагается скачать");

    // R6 новый положен рядом с прежними (руками или прошлой загрузкой) — прежние убираются при проверке
    File md = tmpDir("r6"); new File(md, "mt/y").mkdirs();
    Files.write(new File(md, "mt/y/enc.onnx").toPath(), enc); Files.write(new File(md, "mt/y/dec.onnx").toPath(), dec);
    Files.write(new File(md, "mt/y/kv.onnx").toPath(), kv); Files.write(new File(md, "mt/y/past.onnx").toPath(), past);
    r = store(md, rman, srv);
    eq(r.cleanObsolete(), (long) (enc.length + dec.length), "R6 освобождено ровно по размеру прежних");
    ok(!new File(md, "mt/y/enc.onnx").exists() && !new File(md, "mt/y/dec.onnx").exists(), "R6 прежние удалены");
    ok(r.check("core").complete(), "R6 всё обязательное на месте");

    // R7 из прежних есть только один — замены нет
    File hd = tmpDir("r7"); new File(hd, "mt/y").mkdirs();
    Files.write(new File(hd, "mt/y/enc.onnx").toPath(), enc); Files.write(new File(hd, "mt/y/past.onnx").toPath(), past);
    r = store(hd, rman, srv);
    eq(r.check("core").need.size(), 1, "R7 половина прежних — не замена");
    for (File f : new File[]{rd, ud, bd, cd, md, hd}) del(f);
    srv.hs.stop(0);

    // U: модули (0.24) — обязательное держит запуск, файлы включённых модулей докачиваются сами,
    // выключенных не качаются; удаляются только отдельным действием
    byte[] udet = rnd(120_000, 31), urec = rnd(90_000, 32), ucore = rnd(50_000, 33), uwords = rnd(8_000, 34), ullm = rnd(300_000, 35);
    byte[] uonnx = rnd(60_000, 36), utok = "x 1\n".getBytes(StandardCharsets.UTF_8);
    Map<String, byte[]> uz = new LinkedHashMap<>(); uz.put("tts_y/", null); uz.put("tts_y/v.onnx", uonnx); uz.put("tts_y/tokens.txt", utok);
    byte[] uzip = zip(uz);
    Srv us = new Srv();
    us.files.put("ocr/det.onnx", udet); us.files.put("ocr/rec.onnx", urec); us.files.put("core.bin", ucore); us.files.put("words.txt", uwords);
    us.files.put("llm.gguf", ullm); us.files.put("tts_y.zip", uzip);
    String uman = "{\"manifest_version\":1,\"app\":\"0.24.0\",\"files\":["
        + file("core.bin", ucore, "core", hf("core.bin")) + ","
        + file("words.txt", uwords, "optional", hf("words.txt")).replaceFirst("\\}$", ",\"module\":\"base\"}") + ","
        + file("ocr/det.onnx", udet, "optional", hf("ocr/det.onnx")).replaceFirst("\\}$", ",\"module\":\"ocr\"}") + ","
        + file("ocr/rec.onnx", urec, "optional", hf("ocr/rec.onnx")).replaceFirst("\\}$", ",\"module\":\"ocr\"}") + ","
        + file("llm/m.gguf", ullm, "optional", hf("llm.gguf")).replaceFirst("\\}$", ",\"module\":\"llm\"}") + "],"
        + "\"archives\":[{\"path\":\"tts_y\",\"kind\":\"zip\",\"size\":" + uzip.length + ",\"sha256\":\"" + sha(uzip) + "\",\"tier\":\"optional\",\"module\":\"tts\",\"source\":" + hf("tts_y.zip")
        + ",\"unpack_to\":\".\",\"check\":[{\"path\":\"tts_y/v.onnx\",\"sha256\":\"" + sha(uonnx) + "\"},{\"path\":\"tts_y/tokens.txt\",\"sha256\":\"" + sha(utok) + "\"}]}]}";
    File ad = tmpDir("u1"); ad.mkdirs(); Files.write(new File(ad, "core.bin").toPath(), ucore);
    ModelStore u = store(ad, uman, us);
    eq(u.byPath("core.bin").module + "|" + u.byPath("words.txt").module + "|" + u.byPath("ocr/det.onnx").module + "|" + u.byPath("tts_y").module, "base|base|ocr|tts", "U0 модуль из манифеста; обязательное без поля — base");
    eq(ModelStore.module(new org.json.JSONObject("{\"tier\":\"optional\"}")), "", "U0 необязательное без поля — ничей модуль");
    ok(u.check("core").complete(), "U1 обязательное на месте — запуск не держится ничем, кроме него");
    ModelStore.State ust = u.state();
    eq(ust.autoMissing + ":" + ust.autoBytes, "1:" + uwords.length, "U1 модули выключены — докачивать только базовое необязательное");
    eq(ust.optMissing, 4, "U1 файлы выключенных модулей — не нехватка и не докачка");
    eq(u.autos().size(), 1, "U1 список докачки — только base");
    eq(u.need(new HashSet<>(Arrays.asList("ocr", "tts"))).size(), 4, "U2 первый запуск со снимками и озвучкой: base, два файла снимков, архив голоса");
    eq(u.needBytes(new HashSet<>(Arrays.asList("ocr", "tts"))), (long) (uwords.length + udet.length + urec.length + uzip.length), "U2 и сколько это байт — без хэшей");
    eq(u.needBytes(Collections.emptySet()), (long) uwords.length, "U2 ничего не отмечено — только перевод речи");
    u.modules = new HashSet<>(Arrays.asList("ocr", "tts")); u.summarize();
    ust = u.state();
    eq(ust.autoMissing, 4, "U3 модули включили — их файлы в докачке");
    eq(ust.optMissing, 1, "U3 уточнитель выключен — его не качаем");
    ok(u.start(u.autos(), ModelStore.AUTO), "U3 старт докачки");
    ust = waitDone(u);
    eq(ust.phase + ":" + ust.tier, ModelStore.DONE + ":" + ModelStore.AUTO, "U3 докачано: " + ust.message + " " + ust.errors);
    ok(u.onPhone("ocr") && u.onPhone("tts") && !u.onPhone("llm"), "U3 снимки и голос на телефоне, уточнителя нет");
    ok(u.installed("ocr") && u.installed("tts") && !u.installed("llm"), "U3 и сверены");
    ok(u.installed("cloud") && !u.onPhone("cloud"), "U3 модуль без файлов (облако) — «установлен», но «на телефоне» у него ничего");
    eq(u.state().autoMissing, 0, "U3 докачивать больше нечего");
    eq(u.bytes("ocr"), (long) (udet.length + urec.length), "U4 размер модуля по манифесту");
    eq(u.remove("base"), 0L, "U5 перевод речи не удаляется никогда");
    ok(new File(ad, "core.bin").exists(), "U5 файл на месте");
    long freed = u.remove("tts");
    eq(freed, (long) (uonnx.length + utok.length), "U6 голос удалён распакованным — освобождено по размеру его файлов");
    ok(!new File(ad, "tts_y").exists(), "U6 опустевший каталог голоса убран");
    ok(!u.verified.containsKey("tts_y/v.onnx"), "U6 и забыт в кэше сверки");
    eq(u.remove("ocr"), (long) (udet.length + urec.length), "U7 снимки удалены");
    ok(!new File(ad, "ocr/det.onnx").exists() && !u.onPhone("ocr"), "U7 файлов нет");
    eq(u.state().autoMissing, 3, "U7 модули ещё включены — удалённое снова в докачке (удаляют выключенные, это решает сервис)");
    ModelStore.State fs = new ModelStore.State();
    eq(ModelStore.autoFetchBlock(null, false, false, true, 1L << 40, 0), "файлы ещё не проверены", "U8 нет состояния");
    eq(ModelStore.autoFetchBlock(fs, false, false, true, 1L << 40, 0), "файлы ещё не проверены", "U8 до проверки не решаем");
    fs.checked = true;
    eq(ModelStore.autoFetchBlock(fs, false, false, true, 1L << 40, 0), "докачивать нечего", "U8 нечего");
    fs.autoMissing = 2; fs.autoBytes = 1000;
    eq(ModelStore.autoFetchBlock(fs, true, false, true, 1L << 40, 0), "уже идёт загрузка", "U9 не вмешиваемся в идущую загрузку");
    eq(ModelStore.autoFetchBlock(fs, false, true, true, 1L << 40, 0), "в этот запуск уже пробовали", "U9 одна попытка на запуск");
    eq(ModelStore.autoFetchBlock(fs, false, false, false, 1L << 40, 0), "нет подходящей сети", "U9 без разрешённой сети — ждём");
    eq(ModelStore.autoFetchBlock(fs, false, false, true, 1499, 500), "мало места: нужно ещё 0.0 МБ", "U10 места на байт меньше — нет");
    eq(ModelStore.autoFetchBlock(fs, false, false, true, 1500, 500), null, "U10 ровно хватает — качаем");
    eq(ModelStore.autoFetchBlock(fs, false, false, true, -1, 500), null, "U10 место неизвестно — не мешаем");
    us.hs.stop(0); del(ad);

    // A: облегчать ли само — без кнопки, по разрешённой сети и с запасом места
    ModelStore.State as = new ModelStore.State();
    eq(ModelStore.autoUpgradeBlock(null, false, false, true, 1L << 40, 0), "файлы ещё не проверены", "A1 нет состояния");
    eq(ModelStore.autoUpgradeBlock(as, false, false, true, 1L << 40, 0), "файлы ещё не проверены", "A1 до проверки не решаем");
    as.checked = true;
    eq(ModelStore.autoUpgradeBlock(as, false, false, true, 1L << 40, 0), "облегчать нечего", "A2 замен нет");
    as.upgrade = 1; as.upgradeBytes = 1000;
    eq(ModelStore.autoUpgradeBlock(as, true, false, true, 1L << 40, 0), "уже идёт загрузка", "A3 не вмешиваемся в идущую загрузку");
    eq(ModelStore.autoUpgradeBlock(as, false, true, true, 1L << 40, 0), "в этот запуск уже пробовали", "A4 одна попытка на запуск");
    eq(ModelStore.autoUpgradeBlock(as, false, false, false, 1L << 40, 0), "нет подходящей сети", "A5 без разрешённой сети — ждём");
    eq(ModelStore.autoUpgradeBlock(as, false, false, true, 1499, 500), "мало места: нужно ещё 0.0 МБ", "A6 места на байт меньше нужного — нет");
    eq(ModelStore.autoUpgradeBlock(as, false, false, true, 1500, 500), null, "A6 ровно хватает — качаем");
    eq(ModelStore.autoUpgradeBlock(as, false, false, true, -1, 500), null, "A7 место неизвестно — не мешаем");

    System.out.println(fails == 0 ? "ModelStore: " + checks + " проверок, все прошли" : "ModelStore: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
