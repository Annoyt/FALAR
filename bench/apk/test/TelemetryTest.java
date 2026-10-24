package dev.agenttranslator;

import android.media.AudioDeviceInfo;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Настольные тесты телеметрии по согласию: счётчики распознавания, ступени фона, доля правки, сбой одной
 *  записью, пакет и звук, отправка на локальный сервер, перехват сбоя. Запуск: bash bench/apk/test.sh. */
public class TelemetryTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }

  static void boom() { throw new IllegalStateException("текст разговора: bom dia, João"); }
  static void deep() { try { boom(); } catch (RuntimeException e) { throw new RuntimeException("обёртка с текстом", e); } }

  public static int run() throws Exception {
    fails = 0; checks = 0;
    // ступени фона — те же, что у бота (bot/lib/telemetry.js NOISE_EDGES)
    eq(Arrays.toString(Telemetry.NOISE_EDGES), "[-60, -54, -48, -42]", "T1 ступени фона как у бота");
    eq(Telemetry.noiseBucket(-70), 0, "T1 тише −60 — нулевая");
    eq(Telemetry.noiseBucket(-60), 1, "T1 ровно −60 — первая");
    eq(Telemetry.noiseBucket(-48.1), 2, "T1 −48,1 — до порога шумодава");
    eq(Telemetry.noiseBucket(-48), 3, "T1 −48 — порог шумодава");
    eq(Telemetry.noiseBucket(-30), 4, "T1 громче −42 — последняя");
    eq(Telemetry.micKind(AudioDeviceInfo.TYPE_BUILTIN_MIC), "builtin", "T2 встроенный");
    eq(Telemetry.micKind(AudioDeviceInfo.TYPE_BLUETOOTH_SCO), "bt", "T2 SCO — Bluetooth");
    eq(Telemetry.micKind(AudioDeviceInfo.TYPE_BLE_HEADSET), "bt", "T2 LE Audio — Bluetooth");
    eq(Telemetry.micKind(AudioDeviceInfo.TYPE_WIRED_HEADSET), "wired", "T2 гарнитура на проводе");
    eq(Telemetry.micKind(AudioDeviceInfo.TYPE_USB_HEADSET), "usb", "T2 USB");
    eq(Telemetry.micKind(12345), "other", "T2 незнакомый тип");
    eq(Telemetry.changed("кот", "кит"), 1, "T3 одна буква");
    eq(Telemetry.changed("", "abc"), 3, "T3 из пустого");
    eq(Telemetry.changed("bom dia", "bom dia"), 0, "T3 без правки");
    eq(Telemetry.changed(null, "ab"), 2, "T3 null — как пустое");
    eq(Telemetry.changed("a".repeat(5000), "b".repeat(5000)), 1000, "T3 длинное — по первым 1000 знаков");

    // счётчики по строкам tsvSeg
    Telemetry.Counters c = new Telemetry.Counters(1_000_000);
    c.seg("asr", "pt2ru", 2000, 800, -55, 6.0, 0, AudioDeviceInfo.TYPE_BUILTIN_MIC);
    c.seg("asr", "pt2ru", 3000, 900, -45, 4.0, 0.5, AudioDeviceInfo.TYPE_BUILTIN_MIC);
    c.seg("asr", "ru2pt", 1000, 300, Double.NaN, Double.NaN, 0, AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
    c.seg("silence", "pt2ru", 1000, 200, -62, 2.0, 0, -1);
    c.seg("skip_voice", "", 1500, 0, -50, 3, 9, AudioDeviceInfo.TYPE_BUILTIN_MIC);
    c.seg("skip_lang", "pt2ru", 1200, 400, -50, 3, 0, AudioDeviceInfo.TYPE_BUILTIN_MIC);
    c.seg("skip_short", "", 300, 0, -50, 3, 0, -1);
    c.seg("voice_late", "", 800, 0, -50, 3, 0, -1);
    c.seg("voice_fail", "", 800, 0, -50, 3, 0, -1);
    c.seg(null, null, 0, 0, 0, 0, 0, -1);
    eq(Arrays.asList(c.pt, c.ru, c.empty, c.foreign), Arrays.asList(2, 1, 1, 1), "T4 фразы pt/ru, не распознано, чужой голос");
    eq(c.skips.toString(), "{lang=1, late=2, short=1}", "T4 пропуски по видам; voice_* — «опоздал»");
    eq(Arrays.toString(c.noise), "[1, 1, 0, 1, 0]", "T4 фон по ступеням (−62, −55, −45) — только у распознававшихся фраз, NaN не в счёт");
    eq(Arrays.asList(c.asrMs, c.speechMs), Arrays.asList(2200L, 7000L), "T4 время распознавания и длина речи");
    eq(Arrays.asList(c.gainN, c.clip), Arrays.asList(3, 1), "T4 усиление без NaN, клип от 0,1 %");
    eq(c.mic.toString(), "{bt=1, builtin=2}", "T4 микрофон — у распознававшихся, −1 не в счёт");
    c.listen(true, false, false); c.listen(false, true, false); c.listen(false, false, true); c.hold(); c.hold();
    c.edit("bom dia tudo bem", "bom dia, tudo bem?");
    eq(Arrays.asList(c.listen, c.denoise, c.wiener, c.music, c.hold), Arrays.asList(3, 1, 1, 1, 2), "T5 слушание: шумодав, Винер, музыка; кнопка");
    eq(Arrays.asList(c.edits, c.editChars, c.editTotal), Arrays.asList(1, 2, 16), "T5 правка: 2 знака из 16");
    JSONObject j = c.json(1_000_000 + 30 * 3600_000L, 9800);
    eq(j.getDouble("hours"), 30.0, "T6 период в часах");
    eq(Arrays.asList(j.getJSONObject("turns").getInt("pt"), j.getJSONObject("turns").getInt("ru")), Arrays.asList(2, 1), "T6 фразы по языкам");
    eq(j.getJSONArray("noise").toString(), "[1,1,0,1,0]", "T6 ступени фона");
    eq(j.getLong("rtf_ms"), 314L, "T6 секунда речи — 314 мс распознавания");
    eq(j.getDouble("gain_db"), 4.0, "T6 среднее усиление");
    eq(Arrays.asList(j.getInt("clip"), j.getLong("load_ms"), j.getInt("edits"), j.getInt("edit_chars"), j.getInt("edit_total"), j.getInt("foreign")),
        Arrays.asList(1, 9800L, 1, 2, 16, 1), "T6 клип, загрузка, правки, чужие голоса");
    eq(j.getJSONObject("mic").getInt("builtin"), 2, "T6 микрофон");
    eq(j.getJSONObject("skips").getInt("late"), 2, "T6 пропуски");
    eq(j.has("stand"), false, "T6 стендовых фраз не было — поля нет");
    Telemetry.Counters cs = new Telemetry.Counters(0); cs.stand(); cs.stand(); cs.seg("asr", "pt2ru", 1000, 100, -50, 1, 0, -1);
    eq(Arrays.asList(cs.json(3600_000, 0).getInt("stand"), cs.pt), Arrays.asList(2, 1), "T6 фразы стенда — отдельным числом, в счётчики не идут");
    eq(Telemetry.Counters.load(cs.save(), 0).stand, 2, "T7 стендовые — переживают сохранение");
    Telemetry.Counters ct = cs.take(5); cs.merge(ct); eq(cs.stand, 2, "T8 стендовые — забираются и возвращаются");
    JSONObject e0 = new Telemetry.Counters(0).json(3600_000, 0);
    ok(!e0.has("mic") && !e0.has("skips") && !e0.has("rtf_ms") && !e0.has("gain_db") && !e0.has("load_ms") && !e0.has("edit_chars"),
        "T6 пустой период — без пустых частей");

    // сохранение, забор в пакет и возврат при неудаче
    Telemetry.Counters c2 = Telemetry.Counters.load(c.save(), 0);
    eq(c2.save(), c.save(), "T7 сохранение — туда и обратно без потерь");
    eq(Telemetry.Counters.load("{не json", 77).since, 77L, "T7 испорчено — новый период");
    eq(Telemetry.Counters.load(null, 5).pt, 0, "T7 пусто — новый период");
    String before = c.save();
    Telemetry.Counters taken = c.take(2_000_000);
    eq(taken.save(), before, "T8 забранное — всё, что было");
    ok(c.pt == 0 && c.since == 2_000_000 && c.mic.isEmpty() && c.noise[1] == 0, "T8 здесь — новый период");
    c.seg("asr", "pt2ru", 1000, 100, -50, 1, 0, AudioDeviceInfo.TYPE_BUILTIN_MIC);
    c.merge(taken);
    eq(Arrays.asList(c.pt, c.since, c.mic.get("builtin")), Arrays.asList(3, 1_000_000L, 3), "T8 не ушло — вернулось, период с прежнего начала");

    // сбой одной записью: тип, причины, свои кадры — без текста ошибки
    JSONObject cr = null;
    try { deep(); } catch (RuntimeException e) { cr = Telemetry.crash(e, 1_791_029_124_000L, "0.33.0", Telemetry.SCREEN); }
    eq(cr.getString("what"), "java.lang.RuntimeException ← java.lang.IllegalStateException", "T9 тип и причина");
    ok(cr.getString("where").startsWith("TelemetryTest.boom:") && cr.getString("where").contains(" ← TelemetryTest.deep:"), "T9 место — свои кадры корневой причины");
    eq(Arrays.asList(cr.getLong("at"), cr.getString("kind"), cr.getString("v")), Arrays.asList(1_791_029_124L, "crash", "0.33.0"), "T9 время в секундах, вид, версия");
    eq(cr.getString("state"), "screen", "T9 где было приложение — в записи");
    eq(Arrays.asList(Telemetry.importanceState(100), Telemetry.importanceState(200), Telemetry.importanceState(125), Telemetry.importanceState(230),
        Telemetry.importanceState(300), Telemetry.importanceState(325), Telemetry.importanceState(400), Telemetry.importanceState(1000)),
        Arrays.asList("screen", "screen", "fgs", "fgs", "background", "background", "background", "background"), "T10 важность процесса → на экране / служба на виду / в фоне");
    ok(!cr.toString().contains("bom dia") && !cr.toString().contains("обёртка"), "T9 текста ошибки нет");
    eq(Telemetry.crash(new OutOfMemoryError("x"), 0, "1", Telemetry.BACKGROUND).getString("kind"), "oom", "T9 нехватка памяти — oom");
    eq(Telemetry.crash(new RuntimeException(new OutOfMemoryError()), 0, "1", Telemetry.BACKGROUND).getString("kind"), "oom", "T9 OOM в причине — oom");
    eq(Arrays.asList(Telemetry.exitKind(3), Telemetry.exitKind(5), Telemetry.exitKind(6), Telemetry.exitKind(9), Telemetry.exitKind(4), Telemetry.exitKind(10)),
        Arrays.asList("oom", "native", "anr", "killed", null, null), "T10 причины выхода: Java-сбой и «закрыл человек» — не в счёт");

    // пакет и звук
    JSONObject app = new JSONObject().put("name", "0.33.0").put("code", 39);
    JSONObject dev = new JSONObject().put("maker", "Xiaomi").put("model", "Redmi Note 12");
    JSONObject p = new JSONObject(Telemetry.packet(app, dev, j, new JSONArray().put(cr)));
    eq(Arrays.asList(p.getInt("v"), p.getJSONObject("app").getInt("code"), p.getJSONObject("device").getString("model"), p.getJSONArray("crashes").length()),
        Arrays.asList(1, 39, "Redmi Note 12", 1), "T11 пакет: v, приложение, устройство, сбои");
    JSONObject p0 = new JSONObject(Telemetry.packet(app, dev, null, new JSONArray()));
    ok(!p0.has("asr") && !p0.has("crashes"), "T11 пустые части не кладутся");
    eq(Arrays.asList(Telemetry.mimeOf("a.ogg"), Telemetry.mimeOf("B.WAV"), Telemetry.mimeOf("c.m4a"), Telemetry.mimeOf("d.mp3")),
        Arrays.asList("audio/ogg", "audio/wav", "audio/mp4", null), "T12 формат по имени файла");
    byte[] snd = "OggS opus".getBytes(StandardCharsets.UTF_8);
    JSONObject a = new JSONObject(Telemetry.audio(app, dev, "pt", 3240, "bom dia", "bom dia!", "audio/ogg", snd));
    ok(a.getBoolean("consent") && a.getString("lang").equals("pt") && a.getLong("dur_ms") == 3240 && a.getString("said").equals("bom dia!")
        && Arrays.equals(Base64.getDecoder().decode(a.getString("audio")), snd), "T12 звук: согласие, язык, длина, тексты, base64");

    // отправка — на локальный сервер, как бот
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final List<String> got = new ArrayList<>();
    final int[] status = {200};
    srv.createContext("/", x -> {
      got.add(x.getRequestMethod() + " " + x.getRequestURI() + " " + x.getRequestHeaders().getFirst("X-Falar") + " "
          + x.getRequestHeaders().getFirst("Content-Type") + " " + x.getRequestHeaders().getFirst("User-Agent") + " "
          + new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] b = (status[0] == 200 ? "{\"ok\":true,\"n\":17}" : "{\"ok\":false,\"error\":\"на сегодня хватит\"}").getBytes(StandardCharsets.UTF_8);
      x.sendResponseHeaders(status[0], b.length);
      try (OutputStream o = x.getResponseBody()) { o.write(b); }
    });
    srv.start();
    String base = "http://127.0.0.1:" + srv.getAddress().getPort();
    try {
      String r = Telemetry.post(base + "/audio", "{\"v\":1,\"said\":\"ação\"}", "Falar/0.33.0");
      eq(got.get(0), "POST /audio 1 application/json; charset=utf-8 Falar/0.33.0 {\"v\":1,\"said\":\"ação\"}", "T13 запрос: метод, адрес, X-Falar, тип, UA, тело в UTF-8");
      eq(Telemetry.number(r), 17L, "T13 номер записи из ответа");
      eq(Telemetry.number("не json"), 0L, "T13 ответ не JSON — номера нет");
      status[0] = 429;
      String err = null;
      try { Telemetry.post(base + "/audio", "{}", "Falar/x"); } catch (IOException e) { err = e.getMessage(); }
      eq(err, "HTTP 429: на сегодня хватит", "T13 отказ — код и причина из ответа бота");
    } finally { srv.stop(0); }

    // перехват сбоя: файл — только с согласием, прежний обработчик зовётся всегда
    File dir = Files.createTempDirectory("falar-tele").toFile();
    final List<String> prev = new ArrayList<>();
    Thread.UncaughtExceptionHandler orig = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((t, e) -> prev.add(e.getClass().getSimpleName()));
    try {
      Telemetry.catchCrashes(dir, "0.33.0");
      Telemetry.catchCrashes(new File(dir, "второй"), "x");        // второй раз — не ставится
      Telemetry.collect = true;
      Thread th = new Thread(TelemetryTest::boom); th.start(); th.join();
      List<File> fs = Telemetry.crashFiles(dir);
      eq(fs.size(), 1, "T14 сбой — файлом");
      JSONArray arr = Telemetry.crashes(fs);
      ok(arr.length() == 1 && arr.getJSONObject(0).getString("what").equals("java.lang.IllegalStateException")
          && arr.getJSONObject(0).getString("v").equals("0.33.0"), "T14 в файле — сбой одной записью");
      eq(arr.getJSONObject(0).getString("state"), "background", "T14 экрана не было (onScreen = false) — «в фоне»");
      eq(prev, Arrays.asList("IllegalStateException"), "T14 прежний обработчик позван");
      Telemetry.collect = false;
      th = new Thread(TelemetryTest::boom); th.start(); th.join();
      eq(Telemetry.crashFiles(dir).size(), 1, "T14 без согласия — не пишется");
      eq(prev.size(), 2, "T14 но прежний обработчик зовётся и тогда");
      ok(!new File(dir, "второй").exists(), "T14 обработчик один на процесс");
      for (File f : Objects.requireNonNull(dir.listFiles())) f.delete();
      for (int i = 0; i < 12; i++) Files.write(new File(dir, "crash-" + (1_700_000_000_000L + i) + ".json").toPath(), "{}".getBytes());
      List<File> last = Telemetry.crashFiles(dir);
      eq(Arrays.asList(last.size(), last.get(0).getName(), last.get(last.size() - 1).getName()),
          Arrays.asList(10, "crash-1700000000002.json", "crash-1700000000011.json"), "T15 уходят последние 10 по времени");
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(orig);
      for (File f : Objects.requireNonNull(dir.listFiles())) f.delete();
      dir.delete();
    }
    System.out.println(fails == 0 ? "Telemetry: " + checks + " проверок, все прошли" : "Telemetry: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
