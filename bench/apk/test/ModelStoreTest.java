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

  public static void main(String[] a) throws Exception {
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
    System.out.println(fails == 0 ? "ModelStore: " + checks + " проверок, все прошли" : "ModelStore: провалов " + fails + " из " + checks);
    System.exit(fails == 0 ? 0 : 1);
  }
}
