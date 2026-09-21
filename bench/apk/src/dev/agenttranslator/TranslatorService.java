package dev.agenttranslator;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.*;
import android.net.*;
import android.os.*;
import android.util.Log;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Foreground-сервис (§5): держит движок, микрофон, VAD и воспроизведение; живёт с погашенным экраном. */
public class TranslatorService extends Service {
  static final String TAG = "AT", CH = "at_pipeline", ACT_STOP = "dev.agenttranslator.STOP"; static final int NOTIF = 1;
  public interface Listener { void onLog(String s); void onStatus(String s); void onReady();
    /** Реплика разобранной, а не строкой журнала: экрану нужно показать стороны по отдельности —
     *  португальскую крупно (её читает собеседник), русскую мелко. */
    void onTurn(String dir, String src, String dst, boolean refined);
    /** Строка-подсказка на экране разговора: причина отказа, итог пересмотра. null — только обновить кнопку «получше». */
    void onHint(String s);
    /** Список реплик изменился не через новую реплику: правка, пересмотр, удаление. */
    void onHistory();
    /** Имена собственные из облачного ответа — кандидаты в свои слова, добавляет человек. */
    void onNames(java.util.List<String[]> names, boolean manual);
    /** Модели по манифесту: чего не хватает, ход загрузки. Первый вызов — итог проверки при старте. */
    void onModels(ModelStore.State s);
    /** Состояние проверки и установки обновления приложения; пустая строка — сказать нечего. */
    void onUpdate(String state); }
  public class LocalBinder extends Binder { public TranslatorService get() { return TranslatorService.this; } }
  final IBinder binder = new LocalBinder(); final Handler main = new Handler(Looper.getMainLooper());
  final ExecutorService worker = Executors.newSingleThreadExecutor(); volatile Listener listener;
  /** Граница фразы: знак конца предложения — либо перевод строки. Строка вывески знаков препинания
   *  не имеет, и без второго условия «FARMÁCIA / Aberto das 8h às 20h / Proibido fumar» уезжало
   *  в перевод одним куском: получалось «АФРАКТИКА … Пройбиду Фумар». */
  static final String SENT = "(?<=[.!?…])\\s+(?=\\S)|\\s*\\n+\\s*";
  /** Порог «похоже на ожидаемый язык». Замер на настоящих выводах parakeet: свои фразы 0.50–1.00,
   *  чужой язык и шум 0.00–0.27, между ними разрыв. См. results/2026-09-12-langgate.md. */
  static final double LANG_MIN = 0.40;
  public volatile Engine eng; public Phrasebook pb; public Speaker spk; public WordList words; public Cloud cloud; public Chats chats; public Learn learn; public volatile boolean autoDir = false; volatile boolean recording = false, vadMode = false, running = true, capturing = false; volatile long muteUntil = 0;
  /** «Читаю вслух»: человек держит крупный текст и произносит португальскую фразу сам, по
   *  транскрипции. Микрофон в это время глух — иначе приложение слышит владельца, считает его
   *  собеседником и переводит ему же его фразу обратно. */
  public volatile boolean readingAloud = false;
  /** 0 — слушать всегда, 1 — молчать, пока держат текст, 2 — молчать, пока показана транскрипция.
   *  При 1 и 2 работают ещё два тихих фильтра: своя фраза с экрана и свой голос по-португальски. */
  public volatile int readGuard = 1;
  /** Найденное обновление и строка о нём для экрана. Магазина нет, и без этой проверки человек,
   *  поставивший сборку однажды, о следующей не узнает никогда. */
  public volatile Updates.Info update; public volatile String updateState = ""; public volatile boolean updateBusy = false;
  /** Стенд: описание релиза берётся отсюда вместо GitHub. Не сохраняется. */
  public volatile String updateBase;
  /** Стендовое (bench/air): «молчать» — переводить, но не озвучивать, иначе собственный голос
   *  лезет в воздух между фразами и портит замер; уровень фона для SNR каждого сегмента. */
  volatile boolean silent = false; volatile double noiseRms = 0; volatile double segNoiseDb = Double.NaN, segDb = Double.NaN; volatile long segAt = 0; volatile long segPos = 0; volatile String fixedDir = null; volatile String dumpSegs = null; volatile int rawSec = 0; volatile boolean feeding = false; volatile long vadSamples = 0; volatile boolean duplex = true; volatile boolean autoLang = true; volatile String micSource = "builtin";
  /** Что слушаем. Микрофон открыт только пока включена хотя бы одна кнопка или идёт удержание:
   *  фоновое прослушивание без спроса — это и лишний расход, и запись чужих разговоров. */
  public volatile boolean listenPt = false, listenRu = false; volatile boolean probing = false; volatile boolean keepWarm = true; volatile int padMs = 0; volatile double outGainDb = 0, micGainDb = 0; volatile String split = "off"; int trackCh = 1; AudioTrack spkTrack; int spkRate = 0; volatile AudioDeviceInfo capRouted = null; final List<float[]> rawBuf = new ArrayList<>();
  final List<float[]> pttBuf = new ArrayList<>(); String pttDir = "pt2ru"; AudioTrack track; int trackRate = 0; Thread capThread;
  PowerManager.WakeLock wl; volatile String lastDir, lastSrc, lastDst, lastLine = "";
  public static class Turn { public final int n; public final String dir, asr, mt; public final long at; public volatile String refined;
    /** Реплику поправили или удалили, пока она ждала озвучки: поток озвучки её пропускает. */
    public volatile boolean dropped;
    Turn(int n, String d, String a, String m, long at) { this.n = n; dir = d; asr = a; mt = m; this.at = at; } }
  public final List<Turn> history = Collections.synchronizedList(new ArrayList<>()); int turnNo = 0;
  public volatile Llm llm; public volatile boolean contextMode = false; final ExecutorService llmWorker = Executors.newSingleThreadExecutor(); volatile boolean refinePending = false, refineRunning = false;
  /** Интервалы разбора контекста, в репликах: 0 — только по кнопке. Локальный — уточнитель 🧠,
   *  облачный — пересмотр всего разговора. Счётчики идут с последнего разбора и сбрасываются
   *  ручным нажатием; cloudBackoff удваивает облачный интервал после отказа 429 или обрыва —
   *  бесплатные модели лимитируют по частоте, и автоматика не должна выжигать попытки к моменту,
   *  когда человек нажмёт кнопку сам. */
  public volatile int refineEvery = 3, cloudEvery = 0; volatile int sinceLocal = 0, sinceCloud = 0, cloudBackoff = 1;
  public volatile boolean cloudBusy = false; volatile boolean cloudSkipLogged = false;
  /** Облако — на своём исполнителе: перебор моделей длится до 45 с, и всё это время речь
   *  (worker) и локальный уточнитель (llmWorker) стоять не должны. */
  final ExecutorService cloudWorker = Executors.newSingleThreadExecutor();
  /** Последняя реплика в маскированном виде — для пина: ключ и перевод со слотами вместо чисел
   *  и имён, как их ищет lookup. */
  volatile String lastMaskedSrc, lastMaskedDst; volatile List<String[]> lastSlots = new ArrayList<>(); volatile long lastAt = 0;
  /** Метка последней реплики, разобранной локальным проходом: следующий берёт всё, что позже. */
  volatile long lastLocalAt = 0;
  /** Имена из облачных ответов, ещё не разобранные человеком. */
  public final List<String[]> pendingNames = Collections.synchronizedList(new ArrayList<>());
  /** Модели по манифесту, вшитому в APK: проверка при старте, загрузка с Hugging Face, необязательное по кнопке. */
  public ModelStore store; File modelsDir; volatile long lastModelNotif; volatile String lastModelPhase = "";

  @Override public void onCreate() {
    super.onCreate();
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel(CH, "Переводчик", NotificationManager.IMPORTANCE_LOW));
    Notification n = notif("Загрузка моделей…");
    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); else startForeground(NOTIF, n);
    wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AT:pipeline"); wl.acquire();
    modelsDir = new File(getExternalFilesDir(null), "models");
    // Хранилище создаём здесь, а не в фоне: стендовые интенты (models/modelsbase) приходят сразу за onCreate.
    try { store = new ModelStore(modelsDir, readAsset("models_manifest.json"), this::netAllowed, this::onStoreState, this::log); }
    catch (Throwable t) { Log.e(TAG, "manifest", t); status("Ошибка манифеста моделей: " + t); return; }
    worker.submit(this::boot);
  }
  String readAsset(String name) throws java.io.IOException {
    try (java.io.InputStream in = getAssets().open(name)) {
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[1 << 14]; int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      return new String(bo.toByteArray(), "UTF-8");
    }
  }
  /** Старт: сверка того, что лежит, с манифестом. Всё обязательное на месте — грузим движки;
   *  нет — экран первого запуска, загрузка по кнопке, и loadAll() придёт из onStoreState. */
  void boot() {
    try {
      long t = System.nanoTime();
      ModelStore.Plan p = store.check("core");
      long ms = (System.nanoTime() - t) / 1000000;
      log("📦 модели " + store.app + ": обязательных на месте " + p.have + "/" + (p.have + p.need.size())
          + (p.need.isEmpty() ? "" : ", не хватает " + ModelStore.mb(p.bytes) + " МБ") + " · проверка " + ms + " мс"
          + (store.hashedBytes > 0 ? ", прохэшировано " + ModelStore.mb(store.hashedBytes) + " МБ" : ", по кэшу"));
      tsv("models_check", "" + p.have, "" + p.need.size(), "" + p.bytes, "" + ms, "" + store.hashedBytes);
      ModelStore.State st = store.state(); Listener l = listener; if (l != null) main.post(() -> l.onModels(st));
      if (!p.need.isEmpty()) {
        status("Нужно скачать модели: " + p.need.size() + " файлов, " + ModelStore.mb(p.bytes) + " МБ");
        notify("Нужно скачать модели (" + ModelStore.mb(p.bytes) + " МБ)"); return;
      }
      loadAll();
    } catch (Throwable t) { Log.e(TAG, "boot", t); status("Ошибка: " + t); }
  }
  void loadAll() {
    final File models = modelsDir;
    if (eng != null) return;
    try {
      if (!new File(models, "silero_vad.onnx").exists()) { status("Нет моделей. Залейте их в\n" + models.getAbsolutePath());
        log("❌ моделей не видно в " + models.getAbsolutePath() + " (каталог есть: " + models.isDirectory() + ", читается: " + models.canRead() + ")"); return; }
      eng = new Engine(models, this::log); pb = new Phrasebook(models);
      spk = new Speaker(models); words = new WordList(models); cloud = new Cloud(models); ocr = new Ocr(models);
      chats = new Chats(getExternalFilesDir(null)); learn = new Learn(chats, models, getExternalFilesDir(null));
      log("📝 " + words.stats());
      if (pb.pinsWithDigits > 0) log("📌 пинов с числом без маски: " + pb.pinsWithDigits + " — они не срабатывают, перезакрепите их кнопкой «запомнить»");
      log(cloud.ready ? "☁ «получше» доступно: " + cloud.models.length + " бесплатных моделей"
                      : "☁ «получше» выключено (нет models/openrouter.json)");
      log(spk.ready ? "🎤 отпечаток голоса готов за " + spk.loadMs + " мс, профили: " + spk.describe()
                    : "🎤 модели отпечатка голоса нет (models/speaker/*.onnx) — разделение говорящих выключено");
      status("Готово. ASR " + eng.loadAsrMs + " · MT " + eng.loadMtMs + " · TTS " + eng.loadTtsMs + " мс · " + pb.stats());
      android.content.SharedPreferences pr = getSharedPreferences("at", MODE_PRIVATE);
      micGainDb = pr.getFloat("micgain", 0); outGainDb = pr.getFloat("gain", 0);
      micSource = pr.getString("micsrc", "builtin");
      holdMs = pr.getInt("hold", 1500);
      refineEvery = pr.getInt("refine_every", 3); cloudEvery = pr.getInt("cloud_every", 0);
      readGuard = pr.getInt("read_guard", 1);
      restoreContext();
      maybeCheckUpdates();
      heartbeat(); startWarm(); startSay(); watchNetwork();
      boolean lp = pr.getBoolean("lpt", false), lr = pr.getBoolean("lru", false);
      if (lp || lr) setListen(lp, lr); else { log("🎚 микрофон выключен: включите «Слушать PT» или «Слушать RU»"); status("Микрофон выключен"); }
      notify("Готов. " + pb.stats()); Listener l = listener; if (l != null) main.post(l::onReady);
    } catch (Throwable t) { Log.e(TAG, "init", t); status("Ошибка: " + t); }
  }
  Notification notif(String text) {
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TranslatorService.class).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, CH).setSmallIcon(app.falar.R.drawable.ic_stat_falar).setContentTitle("Falar" + (vadMode ? " · слушаю" : "")).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
      .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Стоп", stop).build()).build();
  }
  void notify(String text) { getSystemService(NotificationManager.class).notify(NOTIF, notif(text)); }

  @Override public int onStartCommand(Intent i, int flags, int id) {
    if (i != null && ACT_STOP.equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
    // Ответ системного установщика. Первый — просьба показать человеку окно подтверждения:
    // без неё сессия висит, а снаружи выглядит, будто обновление молча не поставилось.
    if (i != null && ACT_INSTALLED.equals(i.getAction())) {
      int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1);
      if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
        Intent ui = i.getParcelableExtra(Intent.EXTRA_INTENT);
        if (ui != null) { ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(ui); } catch (Throwable e) { log("⬆ окно установки не открылось: " + e); } }
      } else if (st == android.content.pm.PackageInstaller.STATUS_SUCCESS) { upd("Обновление установлено"); log("⬆ обновление установлено"); }
      else { String m = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
        upd("установка не прошла — " + installWhy(m)); log("⬆ установка не прошла (" + st + "): " + m); }
      return START_STICKY;
    }
    if (i != null && i.hasExtra("enrollwav")) { final String wav = i.getStringExtra("enrollwav"), who = i.getStringExtra("who") == null ? Speaker.ME : i.getStringExtra("who");
      worker.submit(() -> { try { WaveReader wr = new WaveReader(wav); long t = System.nanoTime();
        String lang = i.getStringExtra("lang") == null ? "ru" : i.getStringExtra("lang");
        boolean ok = spk != null && spk.enroll(who, lang, wr.getSamples(), wr.getSampleRate());
        log(ok ? "🎤 профиль «" + who + "» записан за " + (System.nanoTime() - t) / 1000000 + " мс · " + spk.describe() : "🎤 профиль не записан"); } catch (Throwable t) { log("Ошибка записи профиля: " + t); } }); }
    if (i != null && i.hasExtra("whowav")) { final String wav = i.getStringExtra("whowav");
      worker.submit(() -> { try { WaveReader wr = new WaveReader(wav); long t = System.nanoTime();
        String who = spk == null ? null : spk.identify(wr.getSamples(), wr.getSampleRate());
        log("🎤 " + new java.io.File(wav).getName() + " → " + (who == null ? "не свой" : who) + " · косинус " + String.format("%.3f", spk == null ? 0 : spk.lastScore) + " (" + (System.nanoTime() - t) / 1000000 + " мс)"); } catch (Throwable t) { log("Ошибка опознания: " + t); } }); }
    // Стендовая подача текста ровно тем же путём, что у снимка: перевод строки пишется как «\n».
    // Нужна потому, что облако на одном и том же снимке отвечало и за 10 с, и за 49 с — проверять
    // на нём разбор строк и маски нельзя, воспроизводимости нет.
    if (i != null && i.hasExtra("feedtext")) {
      final String t = i.getStringExtra("feedtext").replace("\\n", "\n");
      worker.submit(() -> processText("pt2ru", unshout(t), true, false, 0, 0, "текст"));
    }
    // То же, но как будто это распознанная речь, а не набранный текст: фильтры чтения вслух
    // работают только на микрофонном пути, а проверять эвристику на живом голосе нельзя —
    // распознавание одной и той же фразы отличается от раза к разу, воспроизводимости нет.
    if (i != null && i.hasExtra("feedasr")) {
      final String t = i.getStringExtra("feedasr").replace("\\n", "\n");
      worker.submit(() -> processText("pt2ru", unshout(t), true, false, 0, 0, "asr"));
    }
    if (i != null && i.hasExtra("hold")) {
      holdMs = Math.max(0, Math.min(6000, Integer.parseInt(i.getStringExtra("hold"))));
      getSharedPreferences("at", MODE_PRIVATE).edit().putInt("hold", holdMs).apply();
      log(holdMs == 0 ? "🔊 озвучка сразу, без ожидания паузы"
                      : "🔊 озвучка ждёт " + String.format(Locale.ROOT, "%.1f", holdMs / 1000.0) + " с тишины");
    }
    // Стенд: модели по манифесту. modelsbase — локальный сервер вместо Hugging Face (adb reverse), не сохраняется.
    if (i != null && i.hasExtra("modelsbase") && store != null) { String b = i.getStringExtra("modelsbase"); store.baseOverride = b == null || b.isEmpty() || "off".equals(b) ? null : b; log("⬇ стенд: источник моделей " + (store.baseOverride == null ? "Hugging Face" : store.baseOverride)); }
    if (i != null && i.hasExtra("anynet")) setAnyNet("1".equals(i.getStringExtra("anynet")));
    if (i != null && i.hasExtra("updatebase")) { String b = i.getStringExtra("updatebase"); updateBase = b == null || b.isEmpty() || "off".equals(b) ? null : b; log("⬆ стенд: описание релиза из " + (updateBase == null ? "GitHub" : updateBase)); }
    if (i != null && i.hasExtra("update")) { String u = i.getStringExtra("update");
      if ("check".equals(u)) { getSharedPreferences("at", MODE_PRIVATE).edit().remove("update_check").apply(); checkUpdates(true); }
      else if ("install".equals(u)) installUpdate(); }
    if (i != null && i.hasExtra("readguard")) setReadGuard(Integer.parseInt(i.getStringExtra("readguard")));
    if (i != null && i.hasExtra("reading")) setReadingAloud("1".equals(i.getStringExtra("reading")));
    if (i != null && i.hasExtra("models") && store != null) {
      String m = i.getStringExtra("models");
      if ("check".equals(m)) worker.submit(() -> { long t = System.nanoTime(); ModelStore.Plan p = store.check("all"); ModelStore.State st = store.state();
        log("📦 проверка: на месте " + p.have + ", нет " + p.need.size() + (p.need.isEmpty() ? "" : " " + p.need) + " · обязательных нет " + st.coreMissing + " (" + ModelStore.mb(st.coreBytes) + " МБ), необязательных нет " + st.optMissing + " (" + ModelStore.mb(st.optBytes) + " МБ) · " + (System.nanoTime() - t) / 1000000 + " мс");
        tsv("models_check", "" + p.have, "" + p.need.size(), "" + p.bytes, "" + (System.nanoTime() - t) / 1000000, "" + store.hashedBytes);
        Listener l = listener; if (l != null) main.post(() -> l.onModels(st)); });
      else if ("verify".equals(m)) verifyModels();
      else if ("stop".equals(m)) cancelModels();
      else if ("core".equals(m) || "optional".equals(m) || "all".equals(m)) downloadModels(m);
      else { ModelStore.Item it = store.byPath(m); if (it != null) downloadModels(Collections.singletonList(it)); else log("⬇ нет такого элемента в манифесте: " + m); }
    }
    if (i != null && i.hasExtra("auto")) setAutoDir("1".equals(i.getStringExtra("auto")));
    if (i != null && i.hasExtra("denoise")) setDenoise("1".equals(i.getStringExtra("denoise")));
    if (i != null && i.hasExtra("word")) addWord(i.getStringExtra("word"));
    if (i != null && i.hasExtra("vad")) setVad("1".equals(i.getStringExtra("vad")));
    // Каталоги под модели создаёт приложение, а не adb: на Android 16 каталог, созданный shell,
    // остаётся shell:ext_data_rw с правами 0770, и приложение внутрь не входит — «моделей нет».
    // Файлы внутри при этом ложатся 0666 и читаются, поэтому чинить надо только каталоги.
    if (i != null && i.hasExtra("mkdirs")) new Thread(() -> {
      File base = getExternalFilesDir(null); int ok = 0, bad = 0;
      try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(base, "dirs.txt")))) {
        String s;
        while ((s = r.readLine()) != null) { s = s.trim(); if (s.isEmpty() || s.contains("..")) continue;
          File d = new File(base, s); if (d.isDirectory() || d.mkdirs()) ok++; else bad++; }
      } catch (Exception e) { log("📁 список каталогов не прочитан: " + e); }
      log("📁 каталогов готово " + ok + (bad > 0 ? ", не вышло " + bad : "")); tsv("mkdirs", "" + ok, "" + bad);
    }, "mkdirs").start();
    // Сохранение сегментов на диск: без этого спор «что услышал микрофон» решается догадками.
    if (i != null && i.hasExtra("dumpsegs")) { String d = i.getStringExtra("dumpsegs");
      dumpSegs = d == null || d.isEmpty() || "off".equals(d) ? null : d;
      if (dumpSegs != null) new File(dumpSegs).mkdirs();
      log("💾 сегменты " + (dumpSegs == null ? "не сохраняются" : "пишутся в " + dumpSegs)); }
    if (i != null && (i.hasExtra("preroll") || i.hasExtra("tail") || i.hasExtra("gate") || i.hasExtra("minspeech") || i.hasExtra("hang"))) {
      if (i.hasExtra("preroll")) preRollMs = Integer.parseInt(i.getStringExtra("preroll"));
      if (i.hasExtra("tail")) tailMs = Integer.parseInt(i.getStringExtra("tail"));
      if (i.hasExtra("gate")) gateDb = Double.parseDouble(i.getStringExtra("gate"));
      if (i.hasExtra("minspeech")) minSpeechMs = Integer.parseInt(i.getStringExtra("minspeech"));
      if (i.hasExtra("hang")) hangMs = Integer.parseInt(i.getStringExtra("hang"));
      log("🎚 нарезка: подпор " + preRollMs + " мс · хвост " + tailMs + " мс · порог +" + gateDb + " дБ над фоном · минимум речи " + minSpeechMs + " мс · выдержка " + hangMs + " мс");
      tsv("segcfg", "" + preRollMs, "" + tailMs, "" + gateDb, "" + minSpeechMs, "" + hangMs);
    }
    if (i != null && (i.hasExtra("vadthr") || i.hasExtra("vadsil"))) {
      float thr = i.getStringExtra("vadthr") == null ? eng.vadThreshold : Float.parseFloat(i.getStringExtra("vadthr"));
      float sil = i.getStringExtra("vadsil") == null ? eng.vadMinSilence : Float.parseFloat(i.getStringExtra("vadsil"));
      if (eng != null) { eng.retuneVad(thr, sil); log("🎚 VAD: порог " + thr + ", тишина " + sil + " с"); }
    }
    // Подача записанного потока комнаты вместо микрофона. Нужна потому, что комната между
    // прогонами меняется сильнее, чем настройки нарезки (SNR гулял 14–21 дБ), и сравнивать
    // параметры последовательными прогонами бессмысленно. Здесь же путь ровно тот, что у живого
    // микрофона: те же кадры, та же очередь, тот же VAD.
    if (i != null && i.hasExtra("feedwav")) {
      final String wav = i.getStringExtra("feedwav");
      final int speed = i.getStringExtra("speed") == null ? 1 : Integer.parseInt(i.getStringExtra("speed"));
      new Thread(() -> { try {
        WaveReader wr = new WaveReader(wav); float[] s2 = wr.getSamples();
        feeding = true; capQ.clear(); noiseRms = 0; vadSamples = 0;
        log("▷ подаю " + new File(wav).getName() + ": " + String.format(Locale.ROOT, "%.1f", s2.length / 16000.0) + " с, скорость ×" + speed);
        tsv("feed_begin", new File(wav).getName(), "" + s2.length, "" + speed);
        for (int o = 0; o + 512 <= s2.length && running; o += 512) {
          capQ.put(Arrays.copyOfRange(s2, o, o + 512));
          if (speed > 0) Thread.sleep(32 / speed);
        }
        while (!capQ.isEmpty() && running) Thread.sleep(50);
        Thread.sleep(1500);
        feeding = false;
        log("▷ подача закончена"); tsv("feed_end", new File(wav).getName());
      } catch (Throwable t) { feeding = false; log("▷ ошибка подачи: " + t); } }, "feed").start();
    }
    // Проверка наушников: куда уходит вывод, какова задержка, и слышит ли микрофон озвучку.
    // Последнее решает главный вопрос — можно ли не глушить вход во время озвучки, то есть
    // вести разговор одновременно, а не по очереди.
    // Задержка вывода акустически: приложение даёт щелчок и ловит его собственным микрофоном.
    // Системные метки предъявления на A2DP с аппаратным обходом врут (1393/1190/595/ничего),
    // поэтому меряем по звуку. Прогон через динамик служит опорой: разница между ним и наушником
    // и есть добавка Bluetooth, входной тракт в ней сокращается.
    if (i != null && i.hasExtra("echolat")) {
      final int reps = i.getStringExtra("reps") == null ? 12 : Integer.parseInt(i.getStringExtra("reps"));
      new Thread(() -> { try {
        final int rate = 16000, period = rate;                // щелчок раз в секунду
        ensureTrack(rate);
        log("⏱ задержка по звуку, выход: " + outName() + ", " + reps + " щелчков подряд");
        probing = true; capQ.clear();
        // Непрерывный ряд: дорожка не простаивает, поэтому меряется установившаяся задержка,
        // а не разовое пробуждение тракта (оно давало разброс 599–1661 мс).
        float[] train = new float[reps * period];
        for (int r = 0; r < reps; r++)
          for (int k = 0; k < rate / 20; k++)
            train[r * period + k] = (float) (0.8 * Math.sin(2 * Math.PI * 1000 * k / rate));
        final List<long[]> heard = Collections.synchronizedList(new ArrayList<>());   // {время, уровень×1e6}
        Thread ear = new Thread(() -> {
          try { while (!Thread.currentThread().isInterrupted()) {
            float[] w = capQ.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (w == null) continue;
            heard.add(new long[]{System.currentTimeMillis(), (long) (tone(w, w.length, 1000, 16000) * 1e6)});
          } } catch (InterruptedException e) {}
        }, "ear"); ear.start();
        Thread.sleep(300);
        long t0 = System.currentTimeMillis();
        for (int o = 0; o < train.length; o += 1600) track.write(train, o, Math.min(1600, train.length - o), AudioTrack.WRITE_BLOCKING);
        Thread.sleep(1500); ear.interrupt(); probing = false;
        // порог: вчетверо выше медианы уровня
        long[] lv = new long[heard.size()]; for (int k = 0; k < lv.length; k++) lv[k] = heard.get(k)[1];
        Arrays.sort(lv); long med = lv.length == 0 ? 0 : lv[lv.length / 2];
        StringBuilder got = new StringBuilder(); int ok = 0; long sum = 0; long last = -9999;
        for (long[] h : heard) {
          if (h[1] < med * 4 || h[0] - last < 500) continue;
          last = h[0];
          long k = Math.round((h[0] - t0) / 1000.0);          // какой это по счёту щелчок
          long lat = h[0] - t0 - k * 1000;
          if (k < 0 || k >= reps || lat < -400 || lat > 900) continue;
          got.append(lat).append(" "); sum += lat; ok++;
        }
        long mx = 0; for (long[] h : heard) mx = Math.max(mx, h[1]);
        log("⏱ " + outName() + ": " + (ok == 0 ? "не поймано" : got + "мс · среднее " + (sum / ok) + " мс по " + ok + " из " + reps)
            + " · тон 1 кГц: медиана " + med + ", пик " + mx + ", кадров " + heard.size());
        tsv("echolat", outName(), got.toString().trim(), ok > 0 ? "" + (sum / ok) : "", "" + ok);
      } catch (Throwable t) { probing = false; log("⏱ ошибка: " + t); } }, "echolat").start();
    }
    // Проверка разведения по ушам: русская фраза, потом португальская. Слышны они должны быть
    // в разных наушниках. Оговорка, которую надо проверить ухом: многие TWS сводят каналы в моно,
    // когда надет один вкладыш, — тогда разведение не сработает.
    // Длинный тон в один канал: короткие фразы на слух не локализуются, а три секунды — да.
    if (i != null && i.hasExtra("devtest")) {
      new Thread(() -> { try {
        if (eng == null) { log("🔉 движок не готов"); return; }
        split = "device";
        log("🔉 раздельный вывод: португальский в динамик, русский в наушник");
        for (int r = 0; r < 2; r++) {
          for (String lang : new String[]{"ru", "pt"}) {
            int rate = eng.ttsSampleRate(lang); ensureTrack(rate);
            String txt = "ru".equals(lang) ? "Русский. Это должно звучать в наушнике."
                                           : "Português. Isto deve sair no alto-falante.";
            log("🔉 " + lang + " → " + ("pt".equals(lang) ? "динамик" : "наушник"));
            GeneratedAudio ga = eng.speak(lang, txt, chunk -> { writeOut(chunk, chunk.length, lang); return 1; });
            Thread.sleep((long) (ga.getSamples().length * 1000.0 / rate) + 1500);
          }
        }
        log("🔉 проверка раздельного вывода закончена");
      } catch (Throwable t) { log("🔉 ошибка: " + t); } }, "devtest").start();
    }
    if (i != null && i.hasExtra("pantest")) {
      new Thread(() -> { try {
        int rate = 16000; ensureTrack(rate, 2);
        log("🔈 дорожка: каналов " + track.getChannelCount() + " (просили 2), частота " + track.getSampleRate());
        for (int r = 0; r < 3; r++) {
          for (int side = 0; side < 2; side++) {
            log("🔈 тон только в " + (side == 0 ? "ЛЕВОЕ" : "ПРАВОЕ") + " ухо, 3 с");
            int n = rate * 3; float[] out = new float[n * 2];
            for (int k = 0; k < n; k++) {
              double env = Math.min(1, Math.min(k, n - k) / (double) (rate / 10));
              out[2 * k + side] = (float) (0.6 * env * Math.sin(2 * Math.PI * (side == 0 ? 600 : 900) * k / rate));
            }
            synchronized (warmLock) { track.write(out, 0, out.length, AudioTrack.WRITE_BLOCKING); }
            Thread.sleep(3400);
          }
        }
        log("🔈 проверка каналов закончена");
      } catch (Throwable t) { log("🔈 ошибка: " + t); } }, "pantest").start();
    }
    if (i != null && i.hasExtra("eartest")) {
      new Thread(() -> { try {
        if (eng == null) { log("👂 движок не готов"); return; }
        log("👂 проверка ушей, раскладка: " + split + ", усиление выхода " + outGainDb + " дБ");
        String[][] say = {{"ru", "Русский. Это должно звучать в одном наушнике."},
                          {"pt", "Português. Isto deve soar no outro fone."}};
        for (String[] x : say) {
          int rate = eng.ttsSampleRate(x[0]); ensureTrack(rate);
          log("👂 " + x[0] + " → " + ("off".equals(split) ? "оба уха" : ("pt".equals(x[0]) == "pt-left".equals(split) ? "левое" : "правое")));
          GeneratedAudio ga = eng.speak(x[0], x[1], chunk -> { writeOut(chunk, chunk.length, x[0]); return 1; });
          Thread.sleep((long) (ga.getSamples().length * 1000.0 / rate) + 1200);
        }
        log("👂 проверка закончена");
      } catch (Throwable t) { log("👂 ошибка: " + t); } }, "eartest").start();
    }
    if (i != null && i.hasExtra("bttest")) {
      final String phrase = i.getStringExtra("say") == null
          ? "Это проверка наушников. Слышно ли меня в наушнике, пока микрофон продолжает слушать комнату?"
          : i.getStringExtra("say");
      new Thread(() -> { try {
        if (eng == null) { log("🎧 движок не готов"); return; }
        int rate = eng.ttsSampleRate("ru"); ensureTrack(rate);
        AudioDeviceInfo rd = null;
        log("🎧 вывод: " + outName() + " · вход: " + micName());
        // тишина до озвучки — точка отсчёта
        Thread.sleep(1200); double before = probeRms(1000);
        final long[] frames = {0}; final long[] firstWrite = {0};
        long t0 = System.nanoTime();
        GeneratedAudio ga = eng.speak("ru", phrase, chunk -> {
          if (firstWrite[0] == 0) firstWrite[0] = System.nanoTime();
          track.write(chunk, 0, chunk.length, AudioTrack.WRITE_BLOCKING);
          frames[0] += chunk.length;
          return 1; });
        long[] lat = {-1};
        for (int k = 0; k < 60 && lat[0] < 0; k++) { Thread.sleep(50); lat[0] = outLatencyMs(firstWrite[0]); }
        // Вторая фраза сразу за первой: если она звучит заметно раньше, значит первая платила
        // за пробуждение Bluetooth-канала, а не за передачу.
        Thread.sleep((long) (ga.getSamples().length / (double) rate * 1000) + 200);
        final long[] w2 = {0};
        long warmStart = System.nanoTime();
        eng.speak("ru", "Вторая фраза сразу следом.", chunk -> {
          if (w2[0] == 0) w2[0] = System.nanoTime();
          track.write(chunk, 0, chunk.length, AudioTrack.WRITE_BLOCKING); return 1; });
        long[] lat2 = {-1};
        for (int k = 0; k < 60 && lat2[0] < 0; k++) { Thread.sleep(50); lat2[0] = outLatencyMs(w2[0]); }
        log("🎧 задержка вывода: первая фраза " + (lat[0] < 0 ? "?" : lat[0] + " мс")
            + " · вторая сразу следом " + (lat2[0] < 0 ? "?" : lat2[0] + " мс"));
        tsv("btlat", "" + lat[0], "" + lat2[0], outName());
        double sec = ga.getSamples().length / (double) rate;
        long during = System.nanoTime();
        double mid = probeRms((long) (sec * 300));            // слушаем, пока звук ещё идёт
        Thread.sleep((long) (sec * 1000) + 400);
        double after = probeRms(1000);
        log(String.format(Locale.ROOT,
            "🎧 синтез %.1f с за %d мс · задержка вывода %s · микрофон: тишина %.1f dBFS, во время озвучки %.1f, после %.1f → протечка %.1f дБ",
            sec, (during - t0) / 1000000, lat[0] < 0 ? "неизвестна" : lat[0] + " мс",
            db(before), db(mid), db(after), db(mid) - db(before)));
        tsv("bttest", outName(), micName(), "" + lat[0], f1(db(before)), f1(db(mid)), f1(db(after)));
      } catch (Throwable t) { log("🎧 ошибка: " + t); } }, "bttest").start();
    }
    if (i != null && i.hasExtra("rawsec")) { rawSec = Integer.parseInt(i.getStringExtra("rawsec"));
      synchronized (rawBuf) { rawBuf.clear(); }
      log("💾 пишу сырой поток микрофона " + rawSec + " с");
      // Метка начала записи: без неё запись не привязать к тому, что в это время играл
      // второй телефон, и повторный прогон нечем будет считать.
      tsv("raw_begin", "" + rawSec); }
    if (i != null && i.hasExtra("micsrc")) {
      micSource = i.getStringExtra("micsrc");
      getSharedPreferences("at", MODE_PRIVATE).edit().putString("micsrc", micSource).apply();
      log("🎙 источник входа: " + micSource + " — перезапускаю захват");
      restartCapture();
    }
    if (i != null && i.hasExtra("autolang")) { autoLang = "1".equals(i.getStringExtra("autolang")); log("🌐 направление " + (autoLang ? "по языку реплики" : "закреплено")); }
    if (i != null && (i.hasExtra("gain") || i.hasExtra("micgain") || i.hasExtra("split"))) {
      if (i.hasExtra("gain")) outGainDb = Double.parseDouble(i.getStringExtra("gain"));
      if (i.hasExtra("micgain")) micGainDb = Double.parseDouble(i.getStringExtra("micgain"));
      if (i.hasExtra("split")) { split = i.getStringExtra("split"); if (track != null) { track.stop(); track.release(); track = null; trackRate = 0; } }
      getSharedPreferences("at", MODE_PRIVATE).edit().putFloat("micgain", (float) micGainDb).putFloat("gain", (float) outGainDb).apply();
      log("🔊 усиление: выход " + outGainDb + " дБ · микрофон " + micGainDb + " дБ · уши: " + split);
      tsv("gaincfg", "" + outGainDb, "" + micGainDb, split);
    }
    if (i != null && i.hasExtra("warm")) { keepWarm = "1".equals(i.getStringExtra("warm")); log(keepWarm ? "🔥 подогрев вывода включён" : "🔥 подогрев выключен"); }
    if (i != null && i.hasExtra("duplex")) { duplex = "1".equals(i.getStringExtra("duplex")); log(duplex ? "🎧 дуплекс: микрофон не глушим при выводе в наушник" : "🎧 дуплекс выключен"); }
    if (i != null && i.hasExtra("pad")) { padMs = Integer.parseInt(i.getStringExtra("pad")); log("🔇 тишина перед распознаванием: " + padMs + " мс"); }
    if (i != null && i.hasExtra("newchat")) newChat(i.getStringExtra("newchat"));
    if (i != null && i.hasExtra("openchat")) openChat(Long.parseLong(i.getStringExtra("openchat")));
    // Обычный запуск из лаунчера сбрасывает стендовые режимы. Иначе они доживают до настоящего
    // использования: молчаливый режим от replay_air.sh дожил до похода в магазин, и 237 переводов
    // из 307 не прозвучали.
    if (i != null && i.getBooleanExtra("fromUi", false) && !i.hasExtra("silent") && !i.hasExtra("fixdir")) {
      if (silent) log("↺ стендовый молчаливый режим сброшен: озвучка включена");
      silent = false;
      // Направление не обнуляем, а пересчитываем из кнопок слушания. Раньше здесь стояло
      // fixedDir = null: приложение, открытое нажатием на собственное уведомление, молча
      // превращало «Слушать RU» в «слушаю оба языка» — кнопка при этом продолжала гореть одна.
      fixedDir = (listenPt && listenRu) || (!listenPt && !listenRu) ? null : (listenPt ? "pt2ru" : "ru2pt");
      autoLang = listenPt && listenRu;
    }
    if (i != null && i.hasExtra("silent")) { silent = "1".equals(i.getStringExtra("silent")); log(silent ? "🔈 молчаливый режим: перевод без озвучки" : "🔈 озвучка включена"); }
    if (i != null && i.hasExtra("fixdir")) { String d = i.getStringExtra("fixdir"); fixedDir = d == null || d.isEmpty() || "off".equals(d) ? null : d; log("направление " + (fixedDir == null ? "по голосу" : "закреплено: " + fixedDir)); }
    // Роль «говорящего» в замере через воздух: проигрывает эталоны в динамик, сам ничего не слушает.
    // Моделей не требует — только APK, поэтому вторым устройством годится любой телефон.
    if (i != null && i.hasExtra("playdir")) {
      final String d = i.getStringExtra("playdir");
      final int gap = i.getStringExtra("gap") == null ? 4000 : Integer.parseInt(i.getStringExtra("gap"));
      final int rounds = i.getStringExtra("rounds") == null ? 1 : Integer.parseInt(i.getStringExtra("rounds"));
      final double norm = i.getStringExtra("norm") == null ? -20 : Double.parseDouble(i.getStringExtra("norm"));
      new Thread(() -> playDir(d, gap, rounds, norm), "player").start();
    }
    // Прогон изнутри приложения: снаружи его не запустить, потому что «am start» будит экран
    // и выводит активность вперёд, разрушая само проверяемое условие.
    if (i != null && i.hasExtra("better")) improveNow();
    if (i != null && i.hasExtra("refineevery")) setRefineEvery(Integer.parseInt(i.getStringExtra("refineevery")));
    if (i != null && i.hasExtra("cloudevery")) setCloudEvery(Integer.parseInt(i.getStringExtra("cloudevery")));
    if (i != null && i.hasExtra("consent")) { getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("cloud_consent", "1".equals(i.getStringExtra("consent"))).apply(); log("☁ согласие на отправку разговора: " + cloudConsent()); }
    // Стендовая правка реплики без экрана: «<индекс>|<текст>» — исходник, «<индекс>|<текст>|pin» — перевод.
    if (i != null && i.hasExtra("edittext")) { String[] p = i.getStringExtra("edittext").split("\\|", 2); if (p.length == 2) reTranslate(Integer.parseInt(p[0].trim()), p[1]); }
    if (i != null && i.hasExtra("edittrans")) { final String[] p = i.getStringExtra("edittrans").split("\\|", 3); if (p.length >= 2) worker.submit(() -> fixTranslation(Integer.parseInt(p[0].trim()), p[1], p.length > 2 && p[2].contains("pin"))); }
    if (i != null && i.hasExtra("clearterms")) { int n = chats == null ? 0 : chats.clearTerms(); log("🗑 подсказки разговора убраны: " + n); }
    if (i != null && i.hasExtra("translit")) { StringBuilder b = new StringBuilder(); for (String w : i.getStringExtra("translit").split("\\s+")) b.append(w).append('=').append(Translit.say(w)).append(' '); log("🔤 " + b.toString().trim()); }
    if (i != null && i.hasExtra("soak")) {
      final String d = i.getStringExtra("soak");
      final String dir = i.getStringExtra("dir") == null ? "pt2ru" : i.getStringExtra("dir");
      final int gap = i.getStringExtra("gap") == null ? 9000 : Integer.parseInt(i.getStringExtra("gap"));
      final int rounds = i.getStringExtra("rounds") == null ? 1 : Integer.parseInt(i.getStringExtra("rounds"));
      worker.submit(() -> {
        File[] fs = new File(d).listFiles((x, n) -> n.endsWith(".wav"));
        if (fs == null) { log("соак: нет файлов в " + d); return; }
        java.util.Arrays.sort(fs);
        log("▶ соак: " + fs.length + " файлов × " + rounds + " кругов, пауза " + gap + " мс");
        for (int r = 0; r < rounds; r++) for (File f : fs) {
          try { WaveReader wr = new WaveReader(f.getAbsolutePath()); tsv("soak", f.getName()); segDb = db(rms(wr.getSamples(), wr.getSamples().length)); segNoiseDb = Double.NaN; segAt = System.currentTimeMillis();
            process(dir, wr.getSamples(), wr.getSampleRate()); } catch (Throwable t) { log("соак: " + t); }
          try { Thread.sleep(gap); } catch (InterruptedException e) { return; }
        }
        log("■ соак завершён");
      });
    }
    if (i != null && i.hasExtra("denoisewav")) { final String in = i.getStringExtra("denoisewav"), out = i.getStringExtra("out");
      worker.submit(() -> { try {
        java.io.File f = new java.io.File(in);
        java.io.File[] list = f.isDirectory() ? f.listFiles((d, n) -> n.endsWith(".wav")) : new java.io.File[]{f};
        new java.io.File(out).mkdirs();
        long t = System.nanoTime(); int n = 0; double sec = 0;
        for (java.io.File x : list) { WaveReader wr = new WaveReader(x.getAbsolutePath());
          sec += wr.getSamples().length / (double) wr.getSampleRate();
          float[] c = eng.denoise(wr.getSamples(), wr.getSampleRate());
          if (new DenoisedAudio(c, eng.denoiser == null ? wr.getSampleRate() : eng.denoiser.getSampleRate()).save(new java.io.File(out, x.getName()).getAbsolutePath())) n++; }
        long ms = (System.nanoTime() - t) / 1000000;
        log("🔇 очищено " + n + "/" + list.length + " за " + ms + " мс (RTF " + String.format("%.3f", ms / 1000.0 / sec) + ") → " + out);
      } catch (Throwable t) { log("🔇 ошибка: " + t); } }); }
    if (i != null && i.hasExtra("spkmatrix")) { final String d = i.getStringExtra("spkmatrix");
      worker.submit(() -> { try {
        java.io.File[] fs = new java.io.File(d).listFiles((x, n) -> n.endsWith(".wav"));
        if (fs == null || spk == null || !spk.ready) { log("🎤 матрица: нет файлов или модели"); return; }
        java.util.Arrays.sort(fs);
        float[][] e = new float[fs.length][];
        StringBuilder b = new StringBuilder("🎤 косинусная матрица:\n");
        for (int k = 0; k < fs.length; k++) { WaveReader w = new WaveReader(fs[k].getAbsolutePath());
          e[k] = Speaker.unit(spk.embed(w.getSamples(), w.getSampleRate()));
          b.append(String.format("  %d=%s (%.1f с, %d Гц)%n", k, fs[k].getName(), w.getSamples().length / (double) w.getSampleRate(), w.getSampleRate())); }
        for (int x = 0; x < fs.length; x++) { b.append("  ").append(x).append(':');
          for (int y = 0; y < fs.length; y++) b.append(String.format(" %.2f", Speaker.cos(e[x], e[y]))); b.append('\n'); }
        log(b.toString()); } catch (Throwable t) { log("🎤 матрица: " + t); } }); }
    if (i != null && i.hasExtra("testwav")) { final String wav = i.getStringExtra("testwav"), dir = i.getStringExtra("dir") == null ? "pt2ru" : i.getStringExtra("dir");
      worker.submit(() -> { try { WaveReader wr = new WaveReader(wav); process(dir, wr.getSamples(), wr.getSampleRate()); } catch (Throwable t) { log("Ошибка теста: " + t); } }); }
    return START_STICKY;
  }
  @Override public IBinder onBind(Intent i) { return binder; }
  public void setListener(Listener l) {
    listener = l; if (l != null && eng != null) main.post(l::onReady);
    // Экран мог подключиться после проверки моделей при старте — отдаём ему итог сразу.
    if (l != null && store != null) { ModelStore.State st = store.state(); main.post(() -> l.onModels(st)); }
    if (l != null) { String u = updateState; main.post(() -> l.onUpdate(u)); }
  }
  /** Что слушаем. Ни одна кнопка не нажата — микрофон отпускается совсем: приложение не должно
   *  держать вход и гореть точкой записи, когда его не просили слушать.
   *  Обе нажаты — направление выбирается по языку каждой реплики. Одна — только её язык,
   *  остальное отбивается проверкой языка, как и раньше. */
  public void setListen(boolean pt, boolean ru) {
    listenPt = pt; listenRu = ru;
    autoLang = pt && ru;
    fixedDir = (pt && ru) || (!pt && !ru) ? null : (pt ? "pt2ru" : "ru2pt");
    boolean on = pt || ru;
    getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("lpt", pt).putBoolean("lru", ru).apply();
    setVad(on);
    log(!on ? "⏹ не слушаю, микрофон отпущен"
            : (pt && ru ? "▶ слушаю оба языка, направление по реплике"
                        : "▶ слушаю только " + (pt ? "португальский" : "русский")));
  }
  public void setVad(boolean on) { vadMode = on;
    getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("vad", on).apply();   // START_STICKY поднимает сервис с vadMode=false
    if (eng != null) eng.vad.reset();
    if (on && !capturing) { capturing = true; startCapture(); }
    else if (!on) capturing = false;                       // поток сам выйдет и отпустит микрофон
    notify(on ? "Слушаю…" : "Микрофон выключен"); }
  public void pttStart(String dir) {
    synchronized (pttBuf) { pttBuf.clear(); }
    pttDir = dir; recording = true;
    // Удержание принимает любой язык: на время записи снимаем закрепление от кнопок.
    pttFixed = fixedDir; pttAuto = autoLang; fixedDir = null; autoLang = true;
    ensureCapture();                                  // удержание само открывает микрофон
    log("● запись " + dir);
  }
  public void pttStop() {
    recording = false;
    fixedDir = pttFixed; autoLang = pttAuto;          // вернуть режим, заданный кнопками
    if (!micWanted()) stopCapture();                  // отпустили — микрофон снова закрыт
    final float[] all; synchronized (pttBuf) { int n = 0; for (float[] c : pttBuf) n += c.length; all = new float[n]; int o = 0; for (float[] c : pttBuf) { System.arraycopy(c, 0, all, o, c.length); o += c.length; } }
    final String d = pttDir;
    if (ENROLL.equals(d)) { final String who = enrollWho, lang = enrollLang; worker.submit(() -> {
      long t = System.nanoTime(); boolean ok = spk != null && spk.enroll(who, lang, all, 16000);
      log(ok ? "🎤 профиль «" + who + "» (" + lang + ") записан: " + String.format("%.1f", all.length / 16000.0) + " с за " + (System.nanoTime() - t) / 1000000 + " мс · " + spk.describe()
             : "🎤 не записалось: нужно хотя бы секунду речи" + (spk == null || !spk.ready ? " и модель в models/speaker/" : "")); }); return; }
    // Удержание всегда определяет язык по сказанному. Раньше здесь вызывался вариант, берущий
    // режим из полей: при выключенных кнопках слушания autoLang=false, направление оставалось
    // ru2pt, и сказанное по-португальски отбивал языковой фильтр — «не похоже на русский».
    worker.submit(() -> process(d, all, 16000, true));
  }
  volatile String pttFixed = null; volatile boolean pttAuto = true;
  static final String ENROLL = "enroll";
  volatile String enrollWho = Speaker.ME, enrollLang = "ru";
  public void enrollStart(String who, String lang) { enrollWho = who; enrollLang = lang; pttStart(ENROLL); }
  /** Правка разговора по одной реплике. После неё рабочая история пересобирается из разговора:
   *  иначе выброшенная фраза осталась бы в контексте уточнителя и в подсказках — то есть ровно
   *  там, ради чего её и выбрасывали. */
  public boolean dropTurn(int idx) {
    if (chats == null) return false;
    boolean wasLast = idx == chats.size() - 1;
    if (!chats.deleteTurn(idx)) return false;
    if (wasLast) forgetLast();         // «запомнить» и повтор озвучки не должны цепляться за удалённую фразу
    sayQ.clear();                      // иначе удалённая реплика всё равно прозвучит из очереди
    rebuildHistory(); log("✂ реплика " + (idx + 1) + " удалена из разговора");
    return true;
  }
  /** Удаление разговора — через службу, потому что удалить файл мало. Рабочая история, очередь
   *  озвучки и «последняя реплика» для пина живут в памяти: без их сброса удалённые реплики
   *  продолжали уходить в контекстный уточнитель, в облако и в закрепление — при том что диалог
   *  удаления обещает «Удаление окончательное». */
  public boolean dropChat(long id) {
    if (chats == null || !chats.delete(id)) return false;
    if (chats.current != id) { /* удалили не тот, в котором сидим — память трогать незачем */ }
    sayQ.clear(); rebuildHistory();
    lastSrc = null; lastDst = null; lastDir = null;
    return true;
  }
  /** Последняя реплика ушла из разговора: указатели для пина и очереди озвучки больше не её. */
  void forgetLast() { lastSrc = null; lastDst = null; lastMaskedSrc = null; lastMaskedDst = null; lastSlots = new ArrayList<>(); lastAt = 0; }
  public boolean moveTurn(int idx, long to) {
    if (chats == null) return false;
    boolean wasLast = idx == chats.size() - 1;
    if (!chats.moveTurn(idx, to)) return false;
    if (wasLast) forgetLast();
    rebuildHistory(); log("↗ реплика " + (idx + 1) + " перенесена в разговор " + to);
    return true;
  }
  void rebuildHistory() {
    synchronized (history) {
      history.clear(); turnNo = 0;
      for (String[] t : chats.tail(12)) history.add(new Turn(++turnNo, t[0], t[1], t[2], Long.parseLong(t[3])));
    }
  }
  /** Смена разговора: счётчики интервалов и отметка локального разбора относятся к разговору. */
  void resetPassCounters() { sinceLocal = 0; sinceCloud = 0; cloudBackoff = 1; cloudSkipLogged = false; lastLocalAt = 0; lastAt = 0; }

  public int clearVoices() {
    int n = spk == null ? 0 : spk.forget();
    autoDir = false;
    log("🎤 профилей удалено: " + n + " · авто-направление выключено");
    return n;
  }
  /** Добавить своё слово: «Copacabana Palace» или «Copacabana Palace = Копакабана Палас». */
  public String addWord(String raw) {
    if (words == null || raw == null || raw.trim().isEmpty()) return "пусто";
    String[] p = raw.split("\\s*=\\s*", 2);
    String a = p[0].trim(), b = p.length > 1 ? p[1].trim() : "";
    boolean aRu = a.matches(".*[А-Яа-яЁё].*");
    String pt = aRu ? b : a, ru = aRu ? a : b;
    words.add(pt, ru);
    WordList.Entry e = words.entries.get(words.entries.size() - 1);
    String m = "📝 добавлено: " + e.pt + " ↔ " + e.ru + " · " + words.stats();
    log(m); return m;
  }
  public void setDenoise(boolean on) { if (eng != null) { eng.denoiseOn = on && eng.denoiser != null; log("🔇 шумоподавитель " + (eng.denoiseOn ? "включён" : "выключен")); } }
  public void setAutoDir(boolean on) {
    autoDir = on;
    if (on && (spk == null || !spk.has(Speaker.ME))) { log("🎤 сначала запишите свой голос кнопкой «мой голос»"); autoDir = false; return; }
    log(on ? "↔ авто-направление по языку профиля: " + (spk == null ? "" : spk.describe()) + ", неопознанный голос → " + (spk == null ? "pt" : spk.fallbackLang())
           : "↔ авто-направление выключено");
  }
  /** Файл уточнителя на месте. */
  public boolean hasLlm() {
    File[] gg = new File(getExternalFilesDir(null), "models/llm").listFiles((d, n) -> n.endsWith(".gguf"));
    return gg != null && gg.length > 0;
  }
  public void setContext(boolean on) { setContext(on, true); }
  /** remember=false — включение по умолчанию, а не выбор человека: в настройках ничего не пишем,
   *  чтобы «само включилось» не превратилось в «человек включил» и выключение осталось за ним. */
  public void setContext(boolean on, boolean remember) {
    if (remember) getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("ctx", on).apply();
    contextMode = on; if (!on) { log("🧠 контекст выключен"); return; }
    if (llm != null && llm.ready) { log("🧠 контекст включён"); return; }
    llmWorker.submit(() -> { try {
      File ld = new File(getExternalFilesDir(null), "models/llm"); File[] gg = ld.listFiles((d, n) -> n.endsWith(".gguf")); if (gg == null || gg.length == 0) { log("🧠 контекст выключен: уточнителя нет, скачайте его в «Системе» кнопкой необязательного"); contextMode = false; return; }
      File pick = gg[0]; for (File f : gg) if (f.getName().toLowerCase().contains("hy-mt")) pick = f;
      log("🧠 запускаю LLM: " + pick.getName() + " …"); long t = System.nanoTime();
      Llm l = new Llm(getApplicationInfo().nativeLibraryDir, pick.getAbsolutePath(), new File(getExternalFilesDir(null), "llama-server.log").getAbsolutePath());
      if (l.start(4)) { llm = l; log("🧠 LLM готов за " + (System.nanoTime() - t) / 1000000 + " мс — уточняю переводы по контексту в фоне"); if (refineEvery > 0) kickLocal(); }
      // Переключатель не должен оставаться включённым при мёртвом сервере: снаружи это выглядит
      // как «контекст работает», а на деле не уточняется ничего.
      else { contextMode = false; log("🧠 LLM не поднялся — контекст выключен, подробности в llama-server.log"); }
    } catch (Throwable e) { Log.e(TAG, "llm", e); log("🧠 ошибка LLM: " + e); contextMode = false; } });
  }
  /** Состояние контекста при запуске. Раньше оно нигде не сохранялось, и переключатель каждый
   *  раз начинался выключенным — снаружи это выглядело как «само отключается». Выбор человека
   *  запоминается; если выбора не было, контекст включён, когда уточнитель скачан. */
  void restoreContext() {
    android.content.SharedPreferences pr = getSharedPreferences("at", MODE_PRIVATE);
    boolean has = hasLlm();
    if (!pr.contains("ctx")) {
      if (has) { log("🧠 уточнитель на месте — включаю контекст"); setContext(true, false); }
      return;
    }
    boolean want = pr.getBoolean("ctx", false);
    if (want && !has) { log("🧠 контекст был включён, но уточнителя нет — выключен"); contextMode = false; return; }
    if (want) setContext(true, false);
  }
  static final String SYS = "You are a professional interpreter for a conversation between Brazilian Portuguese and Russian speakers. Each numbered turn shows the speaker's language in brackets, the raw speech-recognition transcript (it may contain recognition errors) and a quick draft translation. Using the whole dialogue as context, produce the best translation of the LAST turn (Portuguese turns into Russian, Russian turns into Brazilian Portuguese), correcting obvious recognition errors from context. Also re-translate the PREVIOUS turn if the later context changes its meaning; otherwise keep it. Answer with exactly two lines and nothing else:\nPREV: <translation of the previous turn>\nLAST: <translation of the last turn>";
  /** После каждой реплики: считаем реплики с последнего разбора и запускаем локальный или облачный
   *  проход по интервалам из настроек. 0 — только по кнопке. Облако без сети пропускается без
   *  штрафа и догонит разговор, когда сеть вернётся: счётчик не сбрасывается. */
  void scheduleRefine() {
    sinceLocal++; sinceCloud++;
    if (contextMode && llm != null && llm.ready && refineEvery > 0 && sinceLocal >= refineEvery) { sinceLocal = 0; kickLocal(); }
    if (cloudEvery > 0 && sinceCloud >= cloudEvery * cloudBackoff) {
      if (cloud == null || !cloud.ready || !cloudConsent()) {
        if (!cloudSkipLogged) { cloudSkipLogged = true; log("☁ автоматический пересмотр пропущен: " + (cloud == null || !cloud.ready ? "нет ключа" : "нет согласия на отправку разговора")); }
      } else if (!online()) {
        if (!cloudSkipLogged) { cloudSkipLogged = true; log("☁ автоматический пересмотр пропущен: нет сети — догонит, когда сеть вернётся"); }
      } else { cloudSkipLogged = false; sinceCloud = 0; cloudReview(false); }
    }
  }
  void kickLocal() { if (llm == null || !llm.ready) return; refinePending = true; if (!refineRunning) llmWorker.submit(this::refineLoop); }
  static String langName(String code) { return code.equals("ru") ? "Russian" : "Portuguese (Brazil)"; }
  /** Контекст на языке исходника реплики t: pt-реплики как есть, ru-реплики — их перевод (и наоборот). */
  String background(Turn[] h, int idx, int from, int to) {
    String src = h[idx].dir.substring(0, 2); StringBuilder b = new StringBuilder();
    for (int i = from; i < to; i++) { if (i == idx) continue; String text = h[i].dir.substring(0, 2).equals(src) ? h[i].asr : (h[i].refined != null ? h[i].refined : h[i].mt); if (text != null && !text.isEmpty()) b.append(text).append('\n'); }
    return b.toString().trim();
  }
  String hyPrompt(String bg, String source, String tgt, String terms) {
    return (terms.isEmpty() ? "" : "Reference the following translations:\n" + terms + "\n") + "[Background Information]\n" + bg + "\nPlease translate the following text into " + langName(tgt) + ", taking the provided background information into consideration\n[Source Text]\n" + source;
  }
  /** Пары для terminology intervention: сначала свои слова, затем закреплённые пользователем.
   *  Сравнение нормализованное и по границам слов: пин хранится с сырым текстом, а parakeet теперь
   *  ставит русскому финальную точку, и простая подстрока перестаёт находиться. */
  /** Новый разговор — с другим человеком. Прежний остаётся на диске и открывается обратно;
   *  из него уходим, поэтому его переводы улучшаем задним числом. */
  public void newChat(String who) {
    if (chats == null) return;
    long left = chats.newChat(who);
    // Отложенная озвучка принадлежит прежнему разговору: произнести её новому собеседнику —
    // это отдать ему чужую реплику.
    int dropped = sayQ.size(); sayQ.clear();
    if (dropped > 0) log("🔊 отброшено из очереди озвучки: " + dropped);
    history.clear(); turnNo = 0; resetPassCounters();
    if (eng != null) eng.vad.reset();
    log("＋ новый разговор" + (who == null || who.isEmpty() ? "" : " с «" + who + "»"));
    status("Новый разговор");
    if (left != 0) llmWorker.submit(() -> refineSession(left));
  }

  /** Открыть прежний разговор и продолжить его: возвращаем реплики в рабочую историю, чтобы
   *  уточнитель и подсказки видели прежний контекст, а не начинали с чистого листа. */
  public void openChat(long id) {
    if (chats == null) return;
    long left = chats.open(id);
    sayQ.clear();
    rebuildHistory(); resetPassCounters();
    if (eng != null) eng.vad.reset();
    log("↩ разговор " + id + " продолжен, восстановлено реплик: " + history.size()
        + (chats.name.isEmpty() ? "" : " · «" + chats.name + "»"));
    if (left != 0) llmWorker.submit(() -> refineSession(left));
  }

  /** Назвать разговор по содержанию. Список без названий бесполезен: по первой фразе человека
   *  не узнать. Считает бесплатная модель OpenRouter — локальная для этого не нужна, а сеть
   *  здесь необязательна: не вышло, останется имя, введённое руками. */
  public void describeChat(long id) {
    if (chats == null || cloud == null || !cloud.ready) return;
    org.json.JSONObject o = chats.load(id);
    if (o == null) return;
    if (!o.optString("name", "").isEmpty() && o.optBoolean("named", false)) return;   // имя уже задано человеком
    if (!o.optString("topic", "").isEmpty()) return;   // тема от пересмотра уже стала названием (или не имела права стать)
    org.json.JSONArray t = o.optJSONArray("turns");
    if (t == null || t.length() < 2) return;
    // Называем один раз и переименовываем, только если разговор с тех пор вырос вдвое. Иначе
    // каждый выход перезаписывает название: на устройстве «Преобразование автодома» сменилось
    // на «Разговор о проекте» — то же содержание, название хуже.
    if (!o.optString("name", "").isEmpty() && t.length() < 2 * o.optInt("nameTurns", 0)) return;
    StringBuilder b = new StringBuilder();
    for (int k = 0; k < Math.min(t.length(), 14); k++) {
      org.json.JSONObject x = t.optJSONObject(k);
      if (x == null) continue;
      b.append(x.optString("src", "")).append(" / ").append(x.optString("dst", "")).append('\n');
      if (b.length() > 1500) break;
    }
    String name = cloud.title(b.toString());
    if (name == null || name.isEmpty()) { log("☁ название разговора не вышло: " + cloud.lastError); return; }
    try {
      o.put("name", name); o.put("nameTurns", t.length());
      if (!chats.saveRefined(id, o, false)) { log("☁ разговор " + id + " исчез, пока его называли — название отброшено"); return; }
    } catch (Throwable e) { return; }
    log("☁ разговор " + id + " назван: «" + name + "» · " + cloud.lastUsed + " · реплик " + t.length());
  }

  public String setCloudKey(String k) {
    if (cloud == null) return "движок не готов";
    String r = cloud.configure(k);
    log("☁ " + r); return r;
  }

  /** Проход улучшения по разговору, из которого ушли. Смысл: первая реплика переводилась вслепую,
   *  а теперь разговор дочитан целиком — видно, о чём шла речь. Идёт в фоне и никуда не спешит;
   *  результат подхватится, когда к этому человеку вернутся. */
  void refineSession(long id) {
    if (llm == null || !llm.ready) { log("🧠 улучшение разговора " + id + " пропущено: LLM не поднята"); describeChat(id); return; }
    org.json.JSONObject o = chats.load(id);
    if (o == null) return;
    org.json.JSONArray t = o.optJSONArray("turns");
    if (t == null || t.length() == 0) return;
    long t0 = System.nanoTime(); int changed = 0;
    StringBuilder whole = new StringBuilder();
    for (int k = 0; k < t.length(); k++) {
      org.json.JSONObject x = t.optJSONObject(k);
      if (x != null) whole.append(x.optString("src", "")).append('\n');
    }
    String ctx = whole.length() > 1200 ? whole.substring(whole.length() - 1200) : whole.toString();
    String tp = o.optString("topic", ""); if (!tp.isEmpty()) ctx = "Tema: " + tp + "\n" + ctx;
    for (int k = 0; k < t.length() && running; k++) {
      org.json.JSONObject x = t.optJSONObject(k);
      if (x == null) continue;
      String src = x.optString("src", ""), was = x.optString("dst", ""), dirn = x.optString("dir", "pt2ru");
      if (src.isEmpty()) continue;
      try {
        String out = llm.chat(null, hyPrompt(ctx, src, dirn.substring(3), terms(dirn, src)), 200).trim();
        if (!out.isEmpty() && !out.equals(was)) { x.put("fixed", out); changed++; }
      } catch (Throwable e) { log("🧠 улучшение оборвалось на реплике " + k + ": " + e); break; }
    }
    if (!chats.saveRefined(id, o, changed > 0)) {
      log("🧠 разговор " + id + " удалён, пока шло улучшение — результат отброшен"); return;
    }
    describeChat(id);
    log("🧠 разговор " + id + " улучшен: правок " + changed + " из " + t.length()
        + " за " + (System.nanoTime() - t0) / 1000000000 + " с");
  }

  String terms(String dir, String text) {
    StringBuilder b = new StringBuilder(); int n = 0;
    String src = dir.substring(0, 2), tgt = dir.substring(3);
    String low = " " + Phrasebook.norm(text) + " ";
    if (words != null) for (WordList.Entry e : words.entries) {
      String a = e.side(src), c = e.side(tgt);
      if (a == null || a.isEmpty() || c == null || c.isEmpty()) continue;
      if (low.contains(" " + Phrasebook.norm(a) + " ") && n++ < 5) b.append(a).append(" translates to ").append(c).append('\n');
    }
    Map<String, JSONObject> m = pb.user.get(dir);
    if (m != null) for (JSONObject o : m.values()) {
      String a = o.optString("src");
      if (!a.isEmpty() && low.contains(" " + Phrasebook.norm(a) + " ") && n++ < 5)
        b.append(a).append(" translates to ").append(o.optString("dst")).append('\n');
    }
    // Глоссарий разговора (пары от облака или инструктивной модели): единственный их потребитель —
    // подсказка LLM; до OPUS-MT они не доходят, а через список своих слов подставлялись бы без склонения.
    if (chats != null) for (String[] t : chats.terms()) {
      String a = src.equals("pt") ? t[0] : t[1], c = src.equals("pt") ? t[1] : t[0];
      if (!a.isEmpty() && !c.isEmpty() && low.contains(" " + Phrasebook.norm(a) + " ") && n++ < 8)
        b.append(a).append(" translates to ").append(c).append('\n');
    }
    return b.toString();
  }
  /** Тема для подсказки: от облака, а без неё — механическая выжимка из частых слов разговора. */
  String topicLine() {
    if (chats == null) return "";
    if (!chats.topic.isEmpty()) return chats.topic;
    return learn == null ? "" : learn.keywords(chats.all(), 6);
  }
  /** Строка снимка: направление целиком ([ru→pt]), чтобы модель правила черновик после «=>», а не исходник —
   *  с пометкой одного языка она чинила распознавание вместо перевода. */
  static String line(int n, String dir, String who, String src, String draft) {
    String sl = dir.substring(0, 2).toUpperCase(Locale.ROOT), tl = dir.substring(3).toUpperCase(Locale.ROOT);
    return n + ". " + sl + (who == null || who.isEmpty() ? "" : " (" + who + ")") + ": " + src + "\n   " + tl + " draft: " + draft + "\n";
  }
  static int indexOf(Turn[] h, Turn t) { for (int i = 0; i < h.length; i++) if (h[i] == t) return i; return -1; }
  /** Локальный проход по контексту: реплики с прошлого разбора, не больше пяти, каждая с контекстом
   *  остальных — позже сказанное уточняет раньше сказанное, а вызовов вдвое меньше, чем при
   *  разборе каждой реплики отдельно. Hy-MT — переводная модель: ей по реплике на запрос с
   *  [Background Information]; инструктивная модель получает ту же разметку, что облако
   *  (TOPIC/FIX/TERMS), и тогда пары терминов извлекаются офлайн. */
  void refineLoop() {
    refineRunning = true;
    try { while (refinePending) { refinePending = false;
      Turn[] h; synchronized (history) { h = history.toArray(new Turn[0]); } if (h.length == 0) return;
      final long chatId = chats == null ? 0 : chats.current;   // тема и пары должны лечь в тот разговор, который разбирали
      List<Turn> todo = new ArrayList<>();
      for (Turn t : h) if (t.at > lastLocalAt) todo.add(t);
      if (lastLocalAt == 0 && todo.size() > 2) todo = new ArrayList<>(todo.subList(todo.size() - 2, todo.size()));   // первый проход: как раньше, две последние
      if (todo.size() > 5) todo = new ArrayList<>(todo.subList(todo.size() - 5, todo.size()));
      if (todo.isEmpty()) continue;
      boolean hy = llm.model.toLowerCase().contains("hy-mt");
      String topic = topicLine();
      int changed = 0, pairs = 0; long t0 = System.nanoTime(); int from = Math.max(0, h.length - 8);
      if (hy) {
        for (Turn L : todo) {
          int idx = indexOf(h, L); if (idx < 0) continue;
          String tgt = L.dir.substring(3);
          String bg = (topic.isEmpty() ? "" : "Tema: " + topic + "\n") + background(h, idx, from, h.length);
          long t = System.nanoTime();
          String out = llm.chat(null, hyPrompt(bg, L.asr, tgt, terms(L.dir, L.asr)), 200).trim();
          long ms = (System.nanoTime() - t) / 1000000;
          if (tgt.equals("pt")) out = TextRules.toBrazilian(out);
          if (applyFix(L, out, Chats.BY_LLM)) { changed++; log("🔁 #" + L.n + " по контексту (" + ms + " мс): " + out); }
          else log("🔁 #" + L.n + " контекст не изменил перевод (" + ms + " мс)");
        }
      } else {
        StringBuilder u = new StringBuilder(); Map<Integer, Turn> byN = new HashMap<>();
        for (int i = from; i < h.length; i++) { int n = i - from + 1; byN.put(n, h[i]); u.append(line(n, h[i].dir, null, h[i].asr, h[i].refined != null ? h[i].refined : h[i].mt)); }
        long t = System.nanoTime();
        String out = llm.chat(Cloud.REVIEW_SYS, (topic.isEmpty() ? "" : "Topic so far: " + topic + "\n") + "Transcript:\n" + u, 400);
        long ms = (System.nanoTime() - t) / 1000000;
        Cloud.Review r = Cloud.Review.parse(out);
        for (Map.Entry<Integer, String> e : r.fixes.entrySet()) {
          Turn L = byN.get(e.getKey()); if (L == null || !todo.contains(L)) continue;
          String fx = e.getValue(); if (L.dir.endsWith("pt")) fx = TextRules.toBrazilian(fx);
          if (applyFix(L, fx, Chats.BY_LLM)) { changed++; log("🔁 #" + L.n + " по контексту: " + fx); }
        }
        if (chats != null && chats.current == chatId) {
          if (!r.terms.isEmpty()) pairs = chats.addTerms(r.terms, Chats.BY_LLM);
          if (!r.topic.isEmpty() && chats.topic.isEmpty()) { chats.setTopic(r.topic); chats.nameFromTopic(r.topic); }
        } else if (!r.terms.isEmpty() || !r.topic.isEmpty()) log("🧠 разговор сменился, пока шёл разбор — тема и пары отброшены");
        if (r.fixes.isEmpty() && r.topic.isEmpty()) log("🧠 ответ модели не разобран (" + ms + " мс): " + (out == null ? "" : out.replace('\n', ' ')));
      }
      if (chats == null || chats.current == chatId) lastLocalAt = todo.get(todo.size() - 1).at;
      long ms = (System.nanoTime() - t0) / 1000000;
      String tp = chats == null || chats.topic.isEmpty() ? (topic.isEmpty() ? "нет" : "по словам: " + topic) : chats.topic;
      log("🧠 разбор контекста: реплик " + todo.size() + ", правок " + changed + (hy ? ", пары не извлекаются: переводная модель" : ", пар " + pairs) + " · тема: " + tp + " (" + ms + " мс)");
      tsv("local_pass", "" + todo.size(), "" + changed, "" + pairs, "" + ms);
      hint("🧠 разбор: реплик " + todo.size() + ", правок " + changed + (hy ? "" : ", пар " + pairs));
    } } catch (Throwable e) { Log.e(TAG, "refine", e); log("🔁 ошибка уточнения: " + e); }
    finally { refineRunning = false; if (refinePending) llmWorker.submit(this::refineLoop); }   // запрос, пришедший между проверкой и выходом, не теряется
  }

  /** Правка в реплику (по метке), в рабочую историю и в ярус выученного. false — перевод тот же,
   *  реплика удалена или поправлена человеком. */
  boolean applyFix(final Turn L, final String out, String by) {
    if (out == null || out.isEmpty()) return false;
    String cur = L.refined != null ? L.refined : L.mt;
    if (Phrasebook.norm(out).equals(Phrasebook.norm(cur))) return false;
    if (chats != null && !chats.fixByAt(L.at, out, by)) return false;
    L.refined = out;
    int lf = learnFix(L.dir, L.asr, out, by);
    if (lf == 1) log("📖 выученное: перевод заменён (" + by + ")"); else if (lf < 0) log("📖 в выученное не пошла: маска не нашлась в правке");
    final Listener l = listener;
    boolean last = chats != null && L.at == chats.lastAt();
    if (last) { if (L.at == lastAt) lastDst = out; if (l != null) main.post(() -> l.onTurn(L.dir, L.asr, out, true)); }
    else if (l != null) main.post(l::onHistory);
    return true;
  }
  /** Исправленный перевод — в выученную запись той же фразы. Ключ — маскированный текст; правка
   *  с числами и именами кладётся, только если их значения нашлись в ней и вернулись в плейсхолдеры. */
  /** 1 — легла, 0 — записи нет или перевод тот же, -1 — маска не нашлась в правке. Журнал — у вызывающего, сводкой. */
  int learnFix(String dir, String src, String fixed, String by) {
    if (pb == null) return 0;
    TextRules.Masked mk = maskOf(dir, src);
    String f = fixed;
    if (!mk.slots.isEmpty()) { f = remask(fixed, mk.slots, dir.substring(3)); if (f == null) return -1; }
    return pb.fix(dir, mk.text, f, by) == 1 ? 1 : 0;
  }
  /** Маскирование реплики тем же путём, что в translateOnce: свои слова, потом числа и адреса. */
  TextRules.Masked maskOf(String dir, String src) {
    String sl = dir.substring(0, 2), tl = dir.substring(3);
    List<String[]> slots = new ArrayList<>(); List<WordList.Hit> wh = new ArrayList<>();
    String pre = TextRules.fixAsr(src, sl);
    if (words != null) pre = words.apply(pre, sl, tl, slots, wh).masked;
    return TextRules.mask(pre, sl, slots);
  }
  /** Обратное маскирование: подставленные значения слотов возвращаются в плейсхолдеры.
   *  null — хотя бы одно значение в тексте не нашлось. */
  static String remask(String text, List<String[]> slots, String tgt) {
    String t = text;
    // Длинные значения раньше коротких («15» раньше «5»), только по границам слов и только если
    // значение в тексте одно: иначе «5» нашлось бы внутри «15» или внутри уже вставленного «XQ1».
    List<String[]> order = new ArrayList<>(slots);
    Collections.sort(order, (a, b) -> TextRules.render(b[1], b[2], tgt).length() - TextRules.render(a[1], a[2], tgt).length());
    for (String[] sl : order) {
      String r = TextRules.render(sl[1], sl[2], tgt);
      if (r.isEmpty()) return null;
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(r) + "(?![\\p{L}\\p{N}])").matcher(t);
      if (!m.find()) return null;
      int at = m.start(); if (m.find()) return null;               // значение встречается дважды — куда ставить слот, неизвестно
      t = t.substring(0, at) + sl[0] + t.substring(at + r.length());
    }
    return t;
  }

  /** Что сделает кнопка «получше»: "cloud", "local" или причина, почему ничего. Одна кнопка значит
   *  «улучшить сейчас»: есть ключ и сеть — облако; иначе локальный проход при включённом контексте. */
  public String improveMode() {
    if (eng == null) return "движок ещё загружается";        // интент со стенда приходит раньше, чем поднялись модели
    if (cloud != null && cloud.ready && online()) return "cloud";
    if (contextMode && llm != null && llm.ready) return "local";
    if (cloud == null || !cloud.ready) return "нет ключа OpenRouter, а контекст 🧠 выключен";
    return "нет сети, а контекст 🧠 выключен";
  }
  public boolean cloudConsent() { return getSharedPreferences("at", MODE_PRIVATE).getBoolean("cloud_consent", false); }
  public void setRefineEvery(int n) {
    refineEvery = Math.max(0, n); getSharedPreferences("at", MODE_PRIVATE).edit().putInt("refine_every", refineEvery).apply();
    log("🧠 разбор контекста: " + (refineEvery == 0 ? "только по кнопке" : "каждые " + refineEvery + " реплик"));
  }
  public void setCloudEvery(int n) {
    cloudEvery = Math.max(0, n); cloudBackoff = 1; cloudSkipLogged = false; getSharedPreferences("at", MODE_PRIVATE).edit().putInt("cloud_every", cloudEvery).apply();
    log("☁ пересмотр разговора в облаке: " + (cloudEvery == 0 ? "только по кнопке" : "каждые " + cloudEvery + " реплик"));
  }
  public void improveNow() {
    String m = improveMode();
    if (m.equals("cloud")) { sinceCloud = 0; cloudBackoff = 1; cloudReview(true); }
    else if (m.equals("local")) { sinceLocal = 0; hint("🧠 разбираю контекст…"); kickLocal(); }
    else { log("получше недоступно: " + m); hint("получше недоступно: " + m); }
  }
  /** Сеть проверяется здесь, а не неудачным запросом: иначе каждое нажатие в самолёте ждёт таймаут. */
  public boolean online() {
    try {
      ConnectivityManager cm = getSystemService(ConnectivityManager.class);
      Network n = cm.getActiveNetwork(); if (n == null) return false;
      NetworkCapabilities c = cm.getNetworkCapabilities(n);
      return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    } catch (Throwable e) { return true; }
  }
  void hint(String s) { Listener l = listener; if (l != null) main.post(() -> l.onHint(s)); }

  // ---- модели по манифесту ----------------------------------------------------------------
  /** Сеть годится для загрузки: есть интернет и либо она без учёта трафика (Wi-Fi), либо
   *  человек разрешил «и по мобильной сети». Учёт трафика берём у системы: платный Wi-Fi
   *  или раздача с телефона тоже считаются лимитными. */
  boolean netAllowed() {
    try {
      ConnectivityManager cm = getSystemService(ConnectivityManager.class);
      Network n = cm.getActiveNetwork(); if (n == null) return false;
      NetworkCapabilities c = cm.getNetworkCapabilities(n);
      if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false;
      return anyNet() || c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    } catch (Throwable e) { return false; }
  }
  public boolean anyNet() { return getSharedPreferences("at", MODE_PRIVATE).getBoolean("models_any_net", false); }
  public void setAnyNet(boolean on) {
    getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("models_any_net", on).apply();
    log(on ? "⬇ загрузка моделей разрешена и по мобильной сети" : "⬇ загрузка моделей только по Wi-Fi");
  }
  /** Ход загрузки из потока хранилища: экрану, в уведомление (не чаще раза в секунду), и по
   *  завершении — движки, если теперь есть всё обязательное, или подключение необязательного. */
  void onStoreState(ModelStore.State st) {
    Listener l = listener; if (l != null) main.post(() -> l.onModels(st));
    long now = System.currentTimeMillis();
    boolean phaseChanged = !st.phase.equals(lastModelPhase);
    if (st.busy() && (phaseChanged || now - lastModelNotif >= 1000)) { lastModelNotif = now; notify(ModelStore.describe(st)); }
    if (phaseChanged && (ModelStore.DONE.equals(st.phase) || ModelStore.ERROR.equals(st.phase) || ModelStore.PAUSED.equals(st.phase))) {
      notify(ModelStore.describe(st));
      if (eng == null) worker.submit(() -> { if (store.check("core").complete()) { status("Модели скачаны, загружаю движки…"); loadAll(); } else status(st.message); });
      else if (ModelStore.DONE.equals(st.phase)) worker.submit(this::reloadOptional);
    }
    lastModelPhase = st.phase;
  }
  public boolean downloadModels(String tier) {
    if (store == null) return false;
    boolean ok = store.start(tier);
    log(ok ? "⬇ загрузка моделей: " + tier + (store.baseOverride != null ? " (стенд: " + store.baseOverride + ")" : "") : "⬇ загрузка уже идёт");
    return ok;
  }
  public boolean downloadModels(List<ModelStore.Item> items) {
    if (store == null || items.isEmpty()) return false;
    boolean ok = store.start(items, "optional");
    log(ok ? "⬇ загрузка: " + items : "⬇ загрузка уже идёт");
    return ok;
  }
  public void cancelModels() { if (store != null && store.running()) { store.cancel(); log("⬇ остановка загрузки"); } }
  /** Кнопка «проверить файлы моделей»: всё хэшируется заново, итог в журнал и на экран. */
  public void verifyModels() {
    if (store == null || store.running()) return;
    new Thread(() -> {
      long t = System.nanoTime();
      ModelStore.Plan p = store.verifyNow("all");
      long ms = (System.nanoTime() - t) / 1000000;
      log("📦 проверка файлов: на месте " + p.have + ", не сошлось или нет " + p.need.size() + (p.need.isEmpty() ? "" : " " + p.need) + " · " + ms + " мс, " + ModelStore.mb(store.hashedBytes) + " МБ прочитано");
      tsv("models_verify", "" + p.have, "" + p.need.size(), "" + ms);
    }, "models-verify").start();
  }
  /** Необязательное докачано при работающих движках: подключаем то, что грузится из файлов при
   *  старте. Уточнитель (LLM) поднимается сам по тумблеру, шумоподавитель — только с перезапуском. */
  void reloadOptional() {
    if (eng == null) return;
    try {
      if (spk == null || !spk.ready) { spk = new Speaker(modelsDir); if (spk.ready) log("🎤 отпечаток голоса подключён: " + spk.describe()); }
      if (pb != null && new File(modelsDir, "phrasebook_tatoeba.tsv").exists()) pb.loadMined(new File(modelsDir, "phrasebook_tatoeba.tsv"));
      if (words != null && new File(modelsDir, "common_words.txt").exists()) { words.loadCommon(); log("📝 " + words.stats()); }
      if (chats != null) learn = new Learn(chats, modelsDir, getExternalFilesDir(null));
      if (new File(modelsDir, "denoiser").isDirectory() && eng.denoiser == null) log("🔇 шумоподавитель скачан — подключится после перезапуска приложения");
      // Уточнитель только что скачали — включаем, если человек не выключал его сам.
      if (hasLlm() && !contextMode && !getSharedPreferences("at", MODE_PRIVATE).contains("ctx")) { log("🧠 уточнитель скачан — включаю контекст"); setContext(true, false); }
      status("Готово. " + pb.stats());
    } catch (Throwable t) { log("подключение скачанного: " + t); }
  }
  /** Сеть пришла или ушла — кнопка «получше» и причина в подсказке обновляются сразу, а не со следующей
   *  реплики: на устройстве после выхода из режима полёта кнопка оставалась серой до нового события. */
  void watchNetwork() {
    try {
      ConnectivityManager cm = getSystemService(ConnectivityManager.class);
      cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { hint(null); }
        @Override public void onLost(Network n) { hint(null); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { hint(null); }
      });
    } catch (Throwable e) { log("сеть: слежение не включилось: " + e); }
  }

  // ---- обновление приложения --------------------------------------------------------------
  static final long UPDATE_EVERY = 24L * 3600 * 1000;
  static final String ACT_INSTALLED = "dev.agenttranslator.INSTALLED";

  /** Облегчённая сборка — та, в которой нет llama.cpp. Обновлять её полной нельзя: человек
   *  выбрал 22 МБ вместо 83 осознанно, и молча утянуть остальное было бы подменой выбора. */
  boolean slimBuild() { return !new File(getApplicationInfo().nativeLibraryDir, "libllama-server.so").exists(); }
  public int myCode() {
    try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
  }
  public String myName() {
    try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
  }
  void upd(String s) { updateState = s; Listener l = listener; if (l != null) main.post(() -> l.onUpdate(s)); }
  /** Конец работы: сначала снять занятость, потом повторить состояние. Иначе последнее сообщение
   *  уходит на экран, пока признак ещё стоит, и кнопка проверки остаётся серой навсегда —
   *  ровно это и случилось после первой же неудачной проверки. */
  void updDone() { updateBusy = false; upd(updateState); }
  /** Причина словами. На экран не должен попадать текст исключения: «java.io.IOException: HTTP 404»
   *  человеку ничего не говорит и выглядит поломкой приложения, а не отсутствием файла. Подробность
   *  остаётся в журнале. */
  /** Отказ установщика словами. Его собственные тексты английские и не для человека, а самый
   *  частый из них — про подпись — означает тупик: поверх сборки, подписанной другим ключом,
   *  обновление не встанет никогда, сколько ни нажимай. Об этом надо сказать прямо. */
  static String installWhy(String m) {
    if (m == null) return "причина не названа";
    if (m.contains("signatures do not match")) return "это приложение подписано другим ключом, чем выпуск. Поверх него обновление не встанет: снимите приложение и поставьте заново со страницы (разговоры и настройки при этом пропадут)";
    if (m.contains("INSUFFICIENT_STORAGE")) return "не хватает места на телефоне";
    if (m.contains("ABORTED")) return "установка отменена";
    return m;
  }
  static String why(Throwable t) {
    String m = t.getMessage() == null ? "" : t.getMessage();
    if (t instanceof IllegalArgumentException) return m;                 // это уже наш русский текст
    if (m.contains("HTTP 404")) return "в последнем выпуске нет описания версии";
    if (m.startsWith("HTTP")) return "GitHub ответил: " + m;
    if (t instanceof java.net.UnknownHostException) return "нет связи с github.com";
    if (t instanceof java.net.SocketTimeoutException) return "GitHub не ответил вовремя";
    if (t instanceof java.io.IOException) return "связь прервалась";
    return "не получилось";
  }
  void maybeCheckUpdates() {
    long last = getSharedPreferences("at", MODE_PRIVATE).getLong("update_check", 0);
    // Найденное обновление переживает перезапуск сервиса: иначе строка состояния обнулялась,
    // а кнопка «обновить» оставалась — экран говорил две разные вещи одновременно.
    if (System.currentTimeMillis() - last < UPDATE_EVERY) { upd(update == null ? "" : Updates.describe(myCode(), update)); return; }
    checkUpdates(false);
  }
  /** Проверка новой версии. Раз в сутки сама, по кнопке — когда попросят. Ошибку не прячем:
   *  «обновлений нет» и «проверить не вышло» — разные вещи, и молчание вместо второго означало
   *  бы, что приложение навсегда перестало обновляться, а снаружи это незаметно. */
  public void checkUpdates(final boolean manual) {
    if (updateBusy) return;
    updateBusy = true; upd("Проверяю…");
    new Thread(() -> {
      try {
        if (!online()) { upd("нет сети — проверю позже"); return; }
        String base = updateBase == null || updateBase.isEmpty() ? Updates.LATEST : updateBase;
        Updates.Info i = Updates.parse(get(base), slimBuild());
        getSharedPreferences("at", MODE_PRIVATE).edit().putLong("update_check", System.currentTimeMillis()).apply();
        int my = myCode();
        if (Updates.newer(my, i)) {
          update = i;
          String m = Updates.describe(my, i);
          upd(m + (i.notes.isEmpty() ? "" : "\n" + i.notes));
          log("⬆ " + m + (i.notes.isEmpty() ? "" : " — " + i.notes));
          notify(m + " — «Система», кнопка обновления");
        } else {
          update = null; upd("установлена последняя версия");
          if (manual) log("⬆ обновлений нет, установлена " + myName());
        }
      } catch (Throwable t) {
        upd("проверить не вышло — " + why(t));
        log("⬆ проверка обновления не вышла: " + t);
      } finally { updDone(); }
    }, "update-check").start();
  }
  String get(String url) throws java.io.IOException {
    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
    c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", "Falar/" + myName());
    try {
      int code = c.getResponseCode();
      if (code != 200) throw new java.io.IOException("HTTP " + code);
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      try (java.io.InputStream in = c.getInputStream()) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); }
      return new String(bo.toByteArray(), "UTF-8");
    } finally { c.disconnect(); }
  }
  /** Скачать и отдать системному установщику. Сумма считается на лету и сверяется до установки:
   *  обновление — самый прямой способ подсунуть человеку чужое приложение, и полагаться на то,
   *  что по дороге ничего не подменили, тут нельзя. */
  public void installUpdate() {
    final Updates.Info i = update;
    if (i == null || updateBusy) return;
    updateBusy = true;
    new Thread(() -> {
      File dir = new File(getExternalFilesDir(null), "update"); dir.mkdirs();
      File[] old = dir.listFiles(); if (old != null) for (File x : old) x.delete();   // недокачанное с прошлого раза
      File f = new File(dir, i.apk);
      try {
        String base = updateBase == null || updateBase.isEmpty() ? Updates.LATEST : updateBase;
        upd("Скачиваю " + i.name + "…");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(i.url(base)).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Falar/" + myName());
        long done = 0;
        try {
          if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
          try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f), 1 << 18)) {
            byte[] b = new byte[1 << 16]; int n; long tick = 0;
            while ((n = in.read(b)) > 0) {
              o.write(b, 0, n); md.update(b, 0, n); done += n;
              if (done - tick > (1 << 21)) { tick = done; upd("Скачиваю " + i.name + ": " + (i.size > 0 ? done * 100 / i.size : 0) + " %"); }
            }
          }
        } finally { c.disconnect(); }
        if (done != i.size) throw new java.io.IOException("размер не сошёлся: " + done + " вместо " + i.size);
        String got = ModelStore.hex(md.digest());
        if (!got.equalsIgnoreCase(i.sha256)) { f.delete(); upd("Файл не сошёлся по контрольной сумме — не ставлю"); log("⬆ контрольная сумма обновления не сошлась, файл выброшен"); return; }
        upd("Устанавливаю " + i.name + "…"); log("⬆ обновление скачано и сверено, отдаю установщику");
        install(f);
        f.delete();          // установщик уже скопировал файл в свою сессию: 83 МБ незачем держать
      } catch (Throwable t) {
        f.delete(); upd("обновление не скачалось — " + why(t)); log("⬆ обновление не скачалось: " + t);
      } finally { updDone(); }
    }, "update-install").start();
  }
  void install(File apk) throws java.io.IOException {
    android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
    android.content.pm.PackageInstaller.SessionParams p =
        new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    p.setAppPackageName(getPackageName());
    int id = pi.createSession(p);
    android.content.pm.PackageInstaller.Session ses = pi.openSession(id);
    try {
      try (java.io.OutputStream o = ses.openWrite("falar", 0, apk.length()); java.io.InputStream in = new java.io.FileInputStream(apk)) {
        byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
        ses.fsync(o);
      }
      PendingIntent pe = PendingIntent.getService(this, 7, new Intent(this, TranslatorService.class).setAction(ACT_INSTALLED),
          PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
      ses.commit(pe.getIntentSender());
    } finally { ses.close(); }
  }

  static final int CLOUD_BUDGET = 6000;
  /** Пересмотр всего разговора бесплатной облачной моделью: по кнопке или по интервалу. В снимок
   *  идёт разговор с конца в пределах бюджета знаков; номера реплик — по порядку в снимке, а
   *  применяются правки по меткам `at`, потому что за время запроса реплики могли удалиться. */
  void cloudReview(final boolean manual) {
    if (cloud == null || !cloud.ready) { log("☁ выключено: положите models/openrouter.json с ключом и списком :free-моделей"); return; }
    if (chats == null || chats.size() == 0) { log("☁ нечего уточнять"); hint("нечего уточнять"); return; }
    if (!cloudConsent()) { log("☁ нет согласия на отправку разговора"); hint("нужно согласие на отправку разговора в облако"); return; }
    if (cloudBusy) { log("☁ запрос уже в работе"); return; }
    cloudBusy = true; hint("☁ пересматриваю разговор…");
    final long chatId = chats.current;
    cloudWorker.submit(() -> {
      try {
        List<String[]> all = chats.all();
        List<String[]> rows = new ArrayList<>(); int chars = 0;
        for (int k = all.size() - 1; k >= 0; k--) {
          String[] r = all.get(k); String ln = line(0, r[0], r[7], r[1], r[2]);
          if (chars + ln.length() > CLOUD_BUDGET && !rows.isEmpty()) break;
          chars += ln.length(); rows.add(0, r);
        }
        StringBuilder tr = new StringBuilder(); Map<Integer, String[]> byN = new HashMap<>();
        for (int n = 1; n <= rows.size(); n++) { String[] r = rows.get(n - 1); byN.put(n, r); tr.append(line(n, r[0], r[7], r[1], r[2])); }
        String topic = topicLine();
        StringBuilder gl = new StringBuilder();
        for (String[] t : chats.terms()) { if (gl.length() > 0) gl.append("; "); gl.append(t[0]).append('=').append(t[1]); }
        long t0 = System.nanoTime();
        String out = cloud.review(tr.toString(), topic, gl.toString());
        long ms = (System.nanoTime() - t0) / 1000000;
        String trail = String.join(" · ", new ArrayList<>(cloud.trail));
        if (!trail.isEmpty()) { log("☁ след перебора: " + trail); tsv("cloud_trail", trail); }
        if (out == null) {
          log("☁ не вышло за " + ms + " мс: " + cloud.lastError); hint("☁ не вышло: " + cloud.lastError);
          tsv("cloud_review_fail", "" + rows.size(), "" + chars, "" + ms, cloud.lastError);
          if (!manual && (cloud.rateLimited || cloud.lastFail == Cloud.F_NET)) { cloudBackoff = Math.min(8, cloudBackoff * 2); log("☁ интервал пересмотра удвоен: " + (cloudEvery * cloudBackoff) + " реплик до следующего ручного нажатия"); }
          return;
        }
        if (chats.current != chatId) { log("☁ разговор сменился, пока шёл пересмотр — ответ отброшен"); return; }
        final Cloud.Review r = Cloud.Review.parse(out);
        int fixed = 0, learned = 0, masked = 0; String lastFix = null; final long chatLast = chats.lastAt(); StringBuilder wrongLang = new StringBuilder();
        Turn[] h; synchronized (history) { h = history.toArray(new Turn[0]); }
        for (Map.Entry<Integer, String> e : r.fixes.entrySet()) {
          String[] row = byN.get(e.getKey()); if (row == null) continue;
          long at = Long.parseLong(row[6]); String dir = row[0]; String fx = e.getValue();
          // Правка должна быть на языке цели реплики: кириллица для pt-реплик, латиница для ru-реплик.
          // Иначе это перевод не в ту сторону или эхо инструкции — на устройстве модели присылали и то, и другое.
          boolean cyr = fx.matches("(?s).*\\p{IsCyrillic}.*");
          if (dir.endsWith("ru") != cyr) { wrongLang.append(wrongLang.length() > 0 ? ", " : "").append(e.getKey()); continue; }
          if (dir.endsWith("pt")) fx = TextRules.toBrazilian(fx);
          if (Phrasebook.norm(fx).equals(Phrasebook.norm(row[2]))) continue;
          if (!chats.fixByAt(at, fx, Chats.BY_CLOUD)) continue;
          fixed++;
          for (Turn t : h) if (t.at == at) t.refined = fx;
          int lf = learnFix(dir, row[1], fx, Chats.BY_CLOUD); if (lf == 1) learned++; else if (lf < 0) masked++;
          if (at == chatLast) { lastFix = fx; if (at == lastAt) lastDst = fx; }
        }
        boolean named = false;
        if (!r.topic.isEmpty()) { chats.setTopic(r.topic); named = chats.nameFromTopic(r.topic); }
        int pairs = r.terms.isEmpty() ? 0 : chats.addTerms(r.terms, Chats.BY_CLOUD);
        // Пара из глоссария — перевод слова для изучения: облачная пара знает контекст, одиночный MT нет.
        if (learn != null) for (String[] t : r.terms) { String w = t[0].trim().toLowerCase(Locale.ROOT); if (w.length() >= 3 && w.matches("\\p{L}+") && learn.inCorpus(w)) learn.putWordRu(w, t[1]); }
        if (!r.names.isEmpty()) synchronized (pendingNames) {
          for (String[] nm : r.names) { boolean dup = false; for (String[] pn : pendingNames) if (pn[0].equalsIgnoreCase(nm[0])) dup = true; if (!dup) pendingNames.add(nm); }
        }
        if (fixed == 0 && pairs == 0) log("☁ ответ без применимых правок, первые 300 знаков: " + out.replace('\n', ' ').substring(0, Math.min(300, out.length())));
        String sum = "☁ ушло " + rows.size() + " реплик, " + chars + " знаков · " + cloud.lastUsed + " за " + ms + " мс · правок " + fixed
            + (learned > 0 ? " (в выученное " + learned + ")" : "") + (masked > 0 ? " (с масками мимо " + masked + ")" : "")
            + (wrongLang.length() > 0 ? " · не на языке цели: " + wrongLang : "") + ", пар " + pairs
            + (r.names.isEmpty() ? "" : ", имён " + r.names.size()) + (r.topic.isEmpty() ? "" : " · тема: " + r.topic) + (named ? " (стала названием)" : "");
        log(sum); tsv("cloud_review", "" + rows.size(), "" + chars, cloud.lastUsed, "" + ms, "" + fixed, "" + pairs, "" + r.names.size(), r.topic);
        // На экране — коротко: список отброшенных номеров нужен стенду, а не собеседнику, который читает этот экран.
        hint("☁ " + rows.size() + " реплик, " + chars + " зн. · " + cloud.lastUsed.replace(":free", "") + " · " + (ms / 1000) + " с · правок " + fixed + ", пар " + pairs
            + (r.names.isEmpty() ? "" : ", имён " + r.names.size()) + (r.topic.isEmpty() ? "" : " · " + r.topic));
        final String fLastFix = lastFix; final String[] lastRow = all.isEmpty() ? null : all.get(all.size() - 1);
        final Listener l = listener;
        if (l != null) main.post(() -> { l.onHistory(); if (fLastFix != null && lastRow != null) l.onTurn(lastRow[0], lastRow[1], fLastFix, true); if (!r.names.isEmpty()) l.onNames(new ArrayList<>(r.names), manual); });
        if (manual && fLastFix != null && lastRow != null && !silent) { try { speakOut(lastRow[0].substring(3), fLastFix); } catch (Throwable e) { log("☁ озвучить не вышло: " + e); } }
      } catch (Throwable e) { Log.e(TAG, "cloud", e); log("☁ ошибка пересмотра: " + e); }
      finally { cloudBusy = false; hint(null); }
    });
  }

  /** Правка распознанного исходника реплики владельца (ru2pt): перевод заново, реплика заменяется
   *  на месте с новой меткой. Направление закреплено — правится русская фраза владельца. */
  public void reTranslate(final int idx, final String newSrc) {
    if (chats == null || eng == null || newSrc == null || newSrc.trim().isEmpty()) return;
    worker.submit(() -> { try {
      String[] t = chats.turn(idx); if (t == null) { log("✎ реплики " + (idx + 1) + " нет"); return; }
      final long oldAt = Long.parseLong(t[6]);
      final Once r = translateOnce("ru2pt", newSrc.trim(), false, false);
      long at = chats.replaceTurn(idx, r.asr, r.mt);
      if (at == 0) { log("✎ не удалось заменить реплику " + (idx + 1)); return; }
      final boolean last = idx == chats.size() - 1;
      synchronized (history) { for (Turn h : history) if (h.at == oldAt) h.dropped = true; }   // даже если поток озвучки уже взял её из очереди
      if (last || oldAt == lastAt) {
        sayQ.removeIf(it -> it.length > 4 && it[4] instanceof Turn && ((Turn) it[4]).at == oldAt);   // старый перевод не должен прозвучать
        lastDir = r.dir; lastSrc = r.asr; lastDst = r.mt; lastMaskedSrc = r.maskedSrc; lastMaskedDst = r.maskedMt; lastSlots = r.slots; lastAt = at;
      }
      rebuildHistory();
      log("✎ реплика " + (idx + 1) + " исправлена: " + r.asr + " → " + r.mt + " · " + r.tag);
      final Listener l = listener;
      if (l != null) main.post(() -> { l.onHistory(); if (last) l.onTurn(r.dir, r.asr, r.mt, false); });
      if (last && !silent) speakTurn(r.dir, r.tgt, r.mt, r.cacheable, null);
    } catch (Throwable e) { log("✎ ошибка правки: " + e); } });
  }
  /** Правка перевода человеком: в реплику как fixed/by=user, по желанию — пин. Пин ложится
   *  маскированным; правка с числами и именами закрепляется, только если их значения нашлись
   *  в ней и вернулись в плейсхолдеры. Возвращает, что вышло. */
  public String fixTranslation(int idx, String fixed, boolean pin) {
    if (chats == null || fixed == null || fixed.trim().isEmpty()) return "пусто";
    String[] t = chats.turn(idx); if (t == null) return "нет реплики";
    final String dir = t[0], src = t[1];
    String fx0 = fixed.trim(); if (dir.endsWith("pt")) fx0 = TextRules.toBrazilian(fx0);
    final String fx = fx0;
    if (!chats.fixTurn(idx, fx, Chats.BY_USER)) return "перевод не изменился";
    final long at = Long.parseLong(t[6]); final boolean last = idx == chats.size() - 1;
    synchronized (history) { for (Turn h : history) if (h.at == at) { h.refined = fx; if (last) h.dropped = true; } }   // произносим сами ниже, а не из очереди
    String msg = "✎ перевод реплики " + (idx + 1) + " исправлен: " + fx;
    if (pin && pb != null) {
      TextRules.Masked mk = maskOf(dir, src);
      String pf = mk.slots.isEmpty() ? fx : remask(fx, mk.slots, dir.substring(3));
      if (pf == null) msg += " · пин не поставлен: числа или имена в правке не совпали с исходником";
      else { pb.pin(dir, mk.text, pf); msg += " · 📌 закреплён · " + pb.stats(); }
    }
    if (last) { lastDst = fx; sayQ.removeIf(it -> it.length > 4 && it[4] instanceof Turn && ((Turn) it[4]).at == at); }
    log(msg);
    final Listener l = listener;
    if (l != null) main.post(() -> { l.onHistory(); if (last) l.onTurn(dir, src, fx, true); });
    if (last && !silent) worker.submit(() -> { try { speakOut(dir.substring(3), fx); } catch (Throwable e) { log("🔊 " + e); } });
    return msg;
  }
  /** Произнести реплику ещё раз: улучшенный перевод, если он есть. */
  public void sayTurn(int idx) {
    if (chats == null || eng == null) return;
    String[] t = chats.turn(idx); if (t == null) return;
    final String tgt = t[0].substring(3), text = t[2];
    worker.submit(() -> { try { speakOut(tgt, text); } catch (Throwable e) { log("🔊 " + e); } });
  }
  /** Произнести готовый текст (для «получше»: перевод уже есть, нужен только звук). */
  void speakOut(String tgt, String text) throws Exception {
    synchronized (tts) {
    int rate = eng.ttsSampleRate(tgt); ensureTrack(rate);
    final boolean dup = btDuplex(); if (!dup) muteUntil = Long.MAX_VALUE;
    double sec = 0;
    try { GeneratedAudio ga = eng.speak(tgt, text, chunk -> { writeOut(chunk, chunk.length, tgt); return 1; });
      sec = ga.getSamples().length / (double) rate;
    } finally { if (!dup) muteUntil = System.currentTimeMillis() + (long) (sec * 1000) + 400; }
    }
  }
  /** Очистка выученного. Ярус копится сам и молча, поэтому убрать его должно быть можно
   *  одним действием — иначе в разбор слов для заучивания пойдёт мусор замеров. */
  /** Произнести одно слово. Для заучивания нужно именно слово вслух, а не фраза целиком. */
  public void sayWord(String w, String lang) {
    if (eng == null || w == null || w.isEmpty()) return;
    worker.submit(() -> { try { speakOut(lang, w); } catch (Throwable t) { log("🔊 " + t); } });
  }

  /** Перевод отдельных слов для вкладки изучения. Идёт в фоне: на список в двести слов
   *  это секунды, а держать экран нельзя. */
  public void translateWords(java.util.List<String> words, Runnable done) {
    if (eng == null || learn == null) { if (done != null) main.post(done); return; }
    worker.submit(() -> {
      int n = 0;
      for (String w : words) {
        if (!running) break;
        learn.markTriedRu(w);                       // пробовали — второй раз в «недостающие» не попадёт
        try { String ru = eng.translate("pt2ru", w);
          // Одиночное слово модель иногда отдаёт с техническим токеном («<unk> Люди») — он
          // ничего не значит для человека и в карточке только мешает.
          if (ru != null) ru = ru.replaceAll("<unk>", " ").replaceAll("\\s+", " ").trim();
          if (ru != null && !ru.isEmpty()) { learn.putWordRu(w, ru); n++; } }
        catch (Throwable t) { break; }
      }
      log("📗 переведено слов: " + n);
      if (done != null) main.post(done);
    });
  }

  public int clearLearned() {
    if (pb == null) return 0;
    int n = pb.clearLearned();
    log("🧹 выученное очищено: было " + n + " фраз · " + pb.stats());
    status(pb.stats());
    return n;
  }

  /** Пин последней реплики — в маскированном виде, как её ищет lookup. Без масок читаемый и
   *  маскированный текст совпадают, и берётся улучшенный перевод, если он есть; с масками
   *  улучшенный перевод возвращается в плейсхолдеры, а не выйдет — закрепляется черновик MT. */
  public boolean pinLast() {
    if (lastSrc == null || pb == null) return false;
    String key = lastMaskedSrc == null ? lastSrc : lastMaskedSrc, dst;
    List<String[]> sl = lastSlots;
    if (sl == null || sl.isEmpty()) dst = lastDst;
    else { dst = remask(lastDst, sl, lastDir.substring(3)); if (dst == null) dst = lastMaskedDst == null ? lastDst : lastMaskedDst; }
    pb.pin(lastDir, key, dst); log("📌 запомнено: " + key + " → " + dst + " · " + pb.stats()); return true;
  }

  /** Очередь между чтением микрофона и VAD. Раньше VAD считался прямо в цикле чтения, и при
   *  непрерывной речи поток захвата не успевал: распознавание занимало ядра, кольцевой буфер
   *  на одну секунду переполнялся, и звук выбрасывался вместе с паузами между фразами — VAD
   *  видел склеенную речь. Замер через воздух: проиграно 175 с, микрофон отдал 131 с (потеря 25%),
   *  см. results/2026-09-12-air.md. */
  final java.util.concurrent.BlockingQueue<float[]> capQ = new java.util.concurrent.ArrayBlockingQueue<>(256);
  volatile long samplesRead = 0, captureStart = 0, framesDropped = 0;

  /** Нужен ли микрофон сейчас: включено прослушивание или идёт удержание. */
  public boolean micWanted() { return listenPt || listenRu || recording; }
  void ensureCapture() { if (!capturing) { capturing = true; startCapture(); } }
  void stopCapture() { capturing = false; log("🎚 микрофон отпущен"); }
  void restartCapture() { if (!micWanted()) return; capturing = false;
    try { Thread.sleep(350); } catch (InterruptedException e) {}
    capturing = true; startCapture(); }

  void startCapture() {
    capThread = new Thread(() -> {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
      int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
      AudioRecord.Builder rb = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
        .setBufferSizeInBytes(Math.max(min, 16000 * 4 * 8));   // 8 с вместо 1 с
      if (Build.VERSION.SDK_INT >= 30) try { rb.setPrivacySensitive(true); } catch (Throwable e) {}   // §7: вход не попадает другим приложениям
      AudioRecord rec = rb.build();
      // Источник входа выбирается в настройках. Встроенный по умолчанию не случайно: микрофон
      // наушников работает по SCO, и тогда вывод в них падает до телефонного качества (§7).
      int want = "headset".equals(micSource) ? AudioDeviceInfo.TYPE_BLUETOOTH_SCO : AudioDeviceInfo.TYPE_BUILTIN_MIC;
      for (AudioDeviceInfo d : getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS))
        if (d.getType() == want) { rec.setPreferredDevice(d); break; }
      if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
        // Без сброса флага повторный ensureCapture() считал, что захват уже идёт, и удержание
        // молча записывало тишину до конца жизни процесса.
        capturing = false;
        log("❌ микрофон не инициализировался — захват не запущен"); status("Микрофон недоступен"); return;
      }
      rec.startRecording(); AudioDeviceInfo routed = rec.getRoutedDevice(); capRouted = routed; log("микрофон: " + (routed == null ? "?" : routed.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "встроенный" : "тип " + routed.getType()));
      final AudioRecord fr = rec;
      try { getSystemService(AudioManager.class).registerAudioRecordingCallback(new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(java.util.List<AudioRecordingConfiguration> cfgs) {
          boolean sil = false;
          for (AudioRecordingConfiguration c : cfgs) if (c.getClientAudioSessionId() == fr.getAudioSessionId()) sil = c.isClientSilenced();
          if (sil != micSilenced) { micSilenced = sil; String m = sil ? "🔇 микрофон заглушён системой (другое приложение перехватило вход)" : "🎙 микрофон снова наш"; log(m); status(m); TranslatorService.this.notify(m); }
        }
      }, main); } catch (Throwable e) {}
      float[] win = new float[512];
      capQ.clear(); samplesRead = 0; captureStart = System.currentTimeMillis(); framesDropped = 0;
      startVad();
      while (running && capturing) {
        int n = rec.read(win, 0, 512, AudioRecord.READ_BLOCKING);
        if (n < 0) {                                   // иначе вечный busy-loop на ядре при ERROR_DEAD_OBJECT
          log("❌ чтение микрофона вернуло " + n + " — пересоздаю AudioRecord");
          try { rec.stop(); rec.release(); } catch (Throwable e) {}
          try { Thread.sleep(500); } catch (InterruptedException e) { return; }
          startCapture(); return;
        }
        if (n == 0) continue;
        samplesRead += n;
        if (rawSec > 0) synchronized (rawBuf) {          // сырой поток как есть, до VAD
          rawBuf.add(Arrays.copyOf(win, n));
          int have = 0; for (float[] c : rawBuf) have += c.length;
          if (have >= rawSec * 16000) { float[] all = new float[have]; int o = 0;
            for (float[] c : rawBuf) { System.arraycopy(c, 0, all, o, c.length); o += c.length; }
            rawBuf.clear(); rawSec = 0;
            String dst = getExternalFilesDir(null).getAbsolutePath() + "/raw.wav";   // только files/: подкаталог от adb приложению не читается
            try { new DenoisedAudio(all, 16000).save(dst); log("💾 сырой поток сохранён: " + dst); tsv("raw_end", dst, "" + all.length); } catch (Throwable e) { log("💾 " + e); } }
        }
        // Удержание: чувствительность из настроек действует и здесь, иначе поднятый вход работал
        // бы только для прослушивания, а на кнопку «говорить» не влиял. Запись голосового
        // профиля — исключение: слепок снимается с голоса как он есть, без усиления.
        if (recording) { float[] c = Arrays.copyOf(win, n);
          if (micGainDb != 0 && !ENROLL.equals(pttDir)) { double mg = Math.pow(10, micGainDb / 20.0); for (int k = 0; k < n; k++) c[k] = clip(c[k] * mg); }
          synchronized (pttBuf) { pttBuf.add(c); } continue; }
        // Здесь только копия и очередь: всё тяжёлое — в отдельном потоке, иначе кольцевой буфер
        // микрофона переполняется и звук теряется молча.
        // Во время подачи записи микрофон в очередь не пускаем: иначе в замер подмешивается
        // живая комната и повтор перестаёт быть повтором.
        if (micGainDb != 0) { double mg = Math.pow(10, micGainDb / 20.0); for (int k = 0; k < n; k++) win[k] = clip(win[k] * mg); }
        if (vadMode && !feeding && eng != null && !readingAloud && System.currentTimeMillis() > muteUntil)
          if (!capQ.offer(n == win.length ? win.clone() : Arrays.copyOf(win, n))) framesDropped++;
      }
      rec.stop(); rec.release(); capRouted = null;
      log("🎙 микрофон отпущен");
    }, "capture"); capThread.start();
  }

  /** Подогрев вывода: Bluetooth-канал, простояв без данных, засыпает, и первая фраза после паузы
   *  ждёт его пробуждения. Пишем тишину, пока ничего не играем. Только для наушника — динамику
   *  это не нужно, а батарею тратит. */
  Thread warmThread;
  void startWarm() {
    if (warmThread != null && warmThread.isAlive()) return;
    warmThread = new Thread(() -> {
      float[] quiet = new float[3200];
      while (running) {
        try { Thread.sleep(80); } catch (InterruptedException e) { return; }
        if (!keepWarm || track == null || !btDuplex()) continue;
        if (System.currentTimeMillis() < muteUntil) continue;      // идёт настоящая озвучка
        try { synchronized (warmLock) { track.write(quiet, 0, quiet.length, AudioTrack.WRITE_NON_BLOCKING); } } catch (Throwable e) {}
      }
    }, "warm"); warmThread.start();
  }
  final Object warmLock = new Object();

  Thread vadThread;
  /** Нарезка на реплики. Сборку куска делаем сами, у sherpa берём только покадровый признак
   *  «речь/не речь». Причина — замер через воздух (results/2026-09-12-air.md): собственная сборка
   *  sherpa складывает в сегмент только речевые кадры, поэтому начало фразы срезается
   *  («Quanto custa a entrada?» → «Entrada.»), а паузы между репликами исчезают и соседние фразы
   *  склеиваются. Здесь: подпор начала из кольцевого буфера, хвост после конца речи и
   *  энергетический порог над измеренным фоном — кадр считается речью, только если Silero сказал
   *  «речь» И он громче фона на gateDb. */
  static final int FRAME_MS = 32;                       // 512 отсчётов при 16 кГц
  volatile int preRollMs = 1000, tailMs = 300, minSpeechMs = 250, maxSpeechMs = 15000, hangMs = 600;
  volatile double gateDb = 6;

  void startVad() {
    if (vadThread != null && vadThread.isAlive()) return;
    vadThread = new Thread(() -> {
      ArrayDeque<float[]> pre = new ArrayDeque<>();
      List<float[]> seg = new ArrayList<>();
      boolean inSpeech = false; int silent = 0, voiced = 0;
      while (running) {
        if (probing) { try { Thread.sleep(20); } catch (InterruptedException e) { return; } continue; }
        float[] win;
        try { win = capQ.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return; }
        if (win == null || eng == null) continue;
        vadSamples += win.length;
        double frame = rms(win, win.length);
        eng.vad.acceptWaveform(win);
        boolean sp = eng.vad.isSpeechDetected();
        while (!eng.vad.empty()) eng.vad.pop();          // внутренняя сборка sherpa не используется
        // Фон копим только в тишине: без этого «SNR» мерил бы речь относительно самой себя.
        // Вклад кадра ограничен сверху: речь, которую VAD не признал речью, иначе поднимает
        // «фон» разом на десяток децибел и портит SNR следующих сегментов.
        // Несимметрично: вниз быстро, вверх еле-еле. Симметричное усреднение ползло только вверх —
        // при плотном диалоге пауз почти нет, оценка фона набирала саму речь (−44 → −31 dBFS),
        // порог считался от неё и начинал резать речь: до распознавания доходило 20% фраз.
        if (!sp) {
          if (noiseRms == 0) noiseRms = frame;
          else if (frame < noiseRms) noiseRms = 0.9 * noiseRms + 0.1 * frame;
          else noiseRms = 0.999 * noiseRms + 0.001 * Math.min(frame, 2 * noiseRms);
        }
        boolean loud = noiseRms == 0 || frame >= noiseRms * Math.pow(10, gateDb / 20);
        boolean speech = sp && loud;
        if (speech) lastSpeechAt = System.currentTimeMillis();   // отсюда отсчитывается пауза до озвучки

        if (!inSpeech) {
          pre.addLast(win);
          while (pre.size() > Math.max(1, preRollMs / FRAME_MS)) pre.removeFirst();
          if (speech) { inSpeech = true; seg.clear(); seg.addAll(pre); pre.clear(); voiced = 1; silent = 0; }
          continue;
        }
        seg.add(win);
        if (speech) { voiced++; silent = 0; } else silent++;
        boolean quiet = silent * FRAME_MS >= hangMs;      // выдержка только здесь, у sherpa её нет
        boolean tooLong = seg.size() * FRAME_MS >= maxSpeechMs;
        if (!quiet && !tooLong) continue;

        int keep = seg.size() - Math.max(0, silent - tailMs / FRAME_MS);   // хвост оставляем, лишнюю тишину режем
        if (voiced * FRAME_MS >= minSpeechMs && keep > 0) {
          int n = 0; for (int k = 0; k < keep; k++) n += seg.get(k).length;
          final float[] out = new float[n]; int o = 0;
          for (int k = 0; k < keep; k++) { float[] c = seg.get(k); System.arraycopy(c, 0, out, o, c.length); o += c.length; }
          final double nz = noiseRms; final long at = System.currentTimeMillis();
          if (dumpSegs != null) try { new DenoisedAudio(out, 16000).save(new File(dumpSegs, "seg_" + at + ".wav").getAbsolutePath()); } catch (Throwable e) {}
          final long pos = vadSamples;
          worker.submit(() -> { segNoiseDb = nz == 0 ? Double.NaN : db(nz); segDb = db(rms(out, out.length)); segAt = at; segPos = pos; route(out); });
        }
        inSpeech = false; seg.clear(); silent = 0; voiced = 0;
      }
    }, "vad"); vadThread.start();
  }

  /** Направление берём из языка опознанного профиля; неопознанный голос — язык «не мой». */
  void route(float[] seg) {
    String dir = fixedDir != null ? fixedDir : "pt2ru";
    // Опознаём говорящего и при заданном направлении тоже. Раньше «Слушать PT» задавало
    // направление жёстко и выходило отсюда сразу, поэтому отпечаток голоса в этом режиме не
    // работал вовсе: прочитанная владельцем вслух португальская фраза шла как речь собеседника.
    if (spk != null && spk.ready && spk.has(Speaker.ME)) {
      long t = System.nanoTime(); String who = spk.identify(seg, 16000); long ms = (System.nanoTime() - t) / 1000000;
      String lang = who != null ? spk.langOf(who) : spk.fallbackLang();
      lastSpkMs = ms; lastSpkWho = (who == null ? "?" : who) + " " + lang + " " + String.format("%.2f", spk.lastScore);
      if (fixedDir == null) {
        if (Speaker.ME.equals(who) && !autoDir) {                     // фильтр своего голоса: молчаливый пропуск выглядит как поломка, поэтому показываем
          String m = "🎤 пропущен свой голос (" + String.format("%.2f", spk.lastScore) + ") — включите «авто-направление», чтобы переводить и его";
          log(m); status(m); notify(m); tsvSeg("skip_self", "", "", "", String.format(Locale.ROOT, "%.2f", spk.lastScore), seg.length / 16.0, 0, 0); lastSpkWho = null; return;
        }
        dir = "ru".equals(lang) ? "ru2pt" : "pt2ru";
      }
    }
    process(dir, seg, 16000);
  }
  volatile long lastSpkMs = 0; volatile String lastSpkWho = null;

  /** Португальская речь, которая на самом деле не речь собеседника: владелец читает вслух
   *  фразу с экрана по транскрипции. Два признака, оба без настройки и без сети.
   *  Первый: сказанное почти целиком состоит из слов фразы, которая сейчас на экране — значит
   *  её прочли, а не произнесли заново. Второй: голос опознан как голос владельца, а речь
   *  португальская; вход от владельца всегда русский, поэтому это не вход.
   *  Отпечаток голоса языка не различает — он опознаёт человека; язык берём из самого текста.
   *  Возвращает причину для показа или null. Молча не выбрасываем ничего: сегодня уже видели,
   *  как молчаливое поведение выглядит поломкой. */
  String readSkip(String dir, String asr, String who, String kind) {
    if (readGuard == 0 || !"pt2ru".equals(dir) || !"asr".equals(kind)) return null;
    if (Speaker.ME.equals(who))
      return "🔇 пропущено: это ваш голос, а речь португальская — вход от вас всегда русский";
    String shown = fromScreen(asr);
    if (shown != null)
      return "🔇 пропущено: вы прочли вслух фразу с экрана — «" + (shown.length() > 40 ? shown.substring(0, 40) + "…" : shown) + "»";
    return null;
  }
  /** Португальские стороны последних реплик — то, что было на экране крупно. */
  String fromScreen(String asr) {
    List<String> shown = new ArrayList<>();
    synchronized (history) {
      for (Turn t : history.subList(Math.max(0, history.size() - Heard.DEPTH), history.size()))
        shown.add(t.dir.startsWith("pt") ? t.asr : (t.refined != null ? t.refined : t.mt));
    }
    return Heard.fromScreen(asr, shown);
  }

  /** Экран сообщает, что человек держит крупный текст и читает его вслух. */
  public void setReadingAloud(boolean on) {
    if (readingAloud == on) return;
    readingAloud = on;
    log(on ? "🔇 читаете вслух — микрофон не слушает" : "🎙 слушаю снова");
    if (!on) muteUntil = Math.max(muteUntil, System.currentTimeMillis() + 250);   // хвост своего голоса в буфере
  }
  public void setReadGuard(int v) {
    readGuard = Math.max(0, Math.min(2, v));
    getSharedPreferences("at", MODE_PRIVATE).edit().putInt("read_guard", readGuard).apply();
    log("🔇 пока читаю вслух: " + (readGuard == 0 ? "слушать всегда"
        : readGuard == 1 ? "молчать, пока держу текст" : "молчать, пока показана транскрипция"));
  }

  /** Набранная фраза. Отбой по языку здесь выключен: это не подслушанная комната, а явная просьба
   *  перевести — направление всё равно определяется по самому тексту. */
  public void typedText(String s) {
    if (s == null || s.trim().isEmpty()) return;
    final String t = s.trim();
    worker.submit(() -> processText("pt2ru", t, true, false, 0, 0, "набрано"));
  }

  /** Снимок: сначала текст, потом обычный путь перевода. Разделение намеренное — распознаёт
   *  снимок один узел, а переводит тот же самый, что и речь, со словарём, своими словами и
   *  накоплением слов для изучения. Поэтому облачная модель просится вернуть только текст,
   *  а не перевод: перевод у нас свой и офлайновый. */
  public void photoText(byte[] jpeg, boolean allowCloud) {
    if (jpeg == null || jpeg.length == 0) return;
    // Снимок читается в отдельном потоке, а не в общем рабочем: облачное чтение занимало
    // до двух минут, и всё это время распознавание речи стояло в очереди за ним.
    new Thread(() -> {
      long t0 = System.nanoTime();
      String text = null, how = null;
      if (ocr != null && ocr.ready) { text = ocr.read(jpeg); how = "OCR"; }
      if ((text == null || text.trim().isEmpty()) && allowCloud && cloud != null && cloud.ready) {
        text = cloud.image(jpeg); how = "облако " + cloud.lastUsed;
      }
      if ((text == null || text.trim().isEmpty()) && !allowCloud) {
        log("📷 офлайн-распознавание не справилось. Долгое нажатие на 📷 — прочитать в облаке.");
        return;
      }
      long ms = (System.nanoTime() - t0) / 1000000;
      if (text == null || text.trim().isEmpty()) {
        log("📷 текста не нашлось за " + ms + " мс (снимок " + (jpeg.length / 1024) + " КБ)"
            + (cloud == null || !cloud.ready ? ": офлайн-распознавания нет, ключ OpenRouter не задан" : ": " + cloud.lastError));
        return;
      }
      log("📷 " + how + " за " + ms + " мс (снимок " + (jpeg.length / 1024) + " КБ): " + text.replace('\n', ' '));
      processText("pt2ru", unshout(text.trim()), true, false, 0, ms, "фото");
    }, "photo").start();
  }
  /** Офлайн-распознавание текста на снимке. Пока не подключено — поле есть, чтобы облачный путь
   *  был запасным, а не единственным. */
  public volatile Ocr ocr;

  /** Вывески пишут прописными, а модель перевода на таком входе ломается: «FARMÁCIA» она перевела
   *  как «АФРАКТИКА». Слово целиком из прописных приводим к обычному виду — смысл тот же,
   *  а токенизация становится той, на которой модель обучалась. Аббревиатуры до трёх букв
   *  (CEP, RG) не трогаем: там прописные значащие. */
  static String unshout(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (String w : s.split("(?<=\\s)|(?=\\s)")) {
      String core = w.replaceAll("[^\\p{L}]", "");
      if (core.length() > 3 && core.equals(core.toUpperCase(Locale.ROOT)) && !core.equals(core.toLowerCase(Locale.ROOT))) {
        int i = 0; while (i < w.length() && !Character.isLetter(w.charAt(i))) i++;
        b.append(w, 0, i);
        if (i < w.length()) b.append(Character.toUpperCase(w.charAt(i))).append(w.substring(i + 1).toLowerCase(Locale.ROOT));
      } else b.append(w);
    }
    return b.toString();
  }

  void process(String dirIn, float[] samples, int sr) { process(dirIn, samples, sr, autoLang && fixedDir == null); }
  /** auto — определять язык по тексту. Для кнопки удержания это всегда так: она принимает тот
   *  язык, который в неё сказали, независимо от того, что слушается постоянно. */
  void process(String dirIn, float[] samples, int sr, boolean auto) {
    try {
      final long chatId = chats == null ? 0 : chats.current;   // до распознавания: окно ~2 с, за которое разговор успевают сменить
      String dir = dirIn;
      String src = dir.substring(0, 2), tgt = dir.substring(3);
      final double durMs = samples.length * 1000.0 / sr;
      // Тишина перед речью: модель обучена на высказываниях с паузой в начале, и резкий старт
      // с нулевой отметки сбивает ей первые токены («A que» → «RKA4JL»). В микрофонном пути
      // это закрывает подпор начала, а при подаче файлом взять тишину неоткуда — дорисовываем.
      float[] fed = samples;
      if (padMs > 0) { int pad = padMs * sr / 1000; fed = new float[pad + samples.length];
        System.arraycopy(samples, 0, fed, pad, samples.length); }
      long t0 = System.nanoTime(); String asr = eng.asr(src, fed, sr); long t1 = System.nanoTime();   // sherpa ресемплирует сам
      if (asr.isEmpty()) { log("(тишина / не распознано, " + String.format("%.1f", samples.length / (double) sr) + " с)"); tsvSeg("silence", dir, "", "", "", durMs, (t1 - t0) / 1000000, 0); return; }
      processText(dir, asr, auto, true, durMs, (t1 - t0) / 1000000, "asr", chatId);
    } catch (Throwable t) { Log.e(TAG, "process", t); log("Ошибка: " + t); tsv("error", dirIn, String.valueOf(t)); }
  }

  /** Результат чистого перевода — без озвучки, журнала и записи в разговор. */
  static class Once {
    String dir, src, tgt, asr, mt, tag, maskedSrc, maskedMt, skip, skipKind, lkTag = "";
    List<String[]> slots = new ArrayList<>(); List<WordList.Hit> whits = new ArrayList<>();
    Phrasebook.Hit hit; int hits = 0; boolean cacheable;
  }

  /** Всё после распознавания и до озвучки: направление по языку, свои слова, маски, словарь, MT,
   *  снятие масок, pt-BR, удаление <unk>. Выделено из processText, потому что правка реплики
   *  требует только перевода. skip != null — реплика отбита фильтром языка, причина там.
   *
   *  gate — отбивать ли реплику, не похожую на ожидаемый язык. Для микрофона да: туда попадает
   *  чужая речь из комнаты. Для набранного и снятого — нет: это попросили перевести явно. */
  Once translateOnce(String dirIn, String asrIn, boolean auto, boolean gate) throws Exception {
    Once r = new Once();
    String dir = dirIn, asr = asrIn;
    String src = dir.substring(0, 2), tgt = dir.substring(3);
    // Направление берём из самого текста. Parakeet многоязычный и пишет на том языке, который
    // услышал, — значит язык реплики уже известен, и закреплять направление заранее незачем.
    // В магазине жёсткое pt→ru выбросило 59% сегментов: это была русская речь владельца.
    if (auto && words != null) {
      double pp = words.looksLike(asr, "pt"), rr = words.looksLike(asr, "ru");
      if (pp >= 0 && rr >= 0 && Math.abs(pp - rr) > 0.15) {
        String want = pp > rr ? "pt2ru" : "ru2pt";
        if (!want.equals(dir)) { dir = want; src = dir.substring(0, 2); tgt = dir.substring(3); }
      }
    }
    r.dir = dir; r.src = src; r.tgt = tgt;
    asr = TextRules.fixAsr(asr, src);                                     // у parakeet нет «ё», она выходит как <unk>
    WordList.Result wr = words == null ? null : words.apply(asr, src, tgt, r.slots, r.whits);
    String pre = wr == null ? asr : wr.masked;
    if (wr != null) asr = wr.readable;                                   // дальше везде — исправленный текст // свои слова — раньше адресного шаблона
    TextRules.Masked mk = TextRules.mask(pre, src, r.slots);              // §6 ярус 3: числа/цены/адреса в плейсхолдеры
    r.slots = mk.slots; r.asr = asr; r.maskedSrc = mk.text;
    if (words != null) {                                                  // parakeet многоязычный и сам решает, что услышал
      double lk = words.looksLike(mk.text, src);
      // Короткую реплику судим по составу, а не по длине: «Sim», «Да», «Сколько?» — это
      // самые частые реплики разговора, и они же копятся в словарь для обучения.
      if (gate && lk == -2 && !words.shortOk(mk.text, src)) {
        r.skip = "(короткое и не похоже на " + (src.equals("pt") ? "португальский" : "русский") + ", пропускаю: «" + asr + "»)"; r.skipKind = "skip_short"; return r; }
      if (gate && lk >= 0 && lk < LANG_MIN) {
        r.skip = "(не похоже на " + (src.equals("pt") ? "португальский" : "русский") + ", доля своих слов " + String.format("%.2f", lk) + " — пропускаю: «" + asr + "»)";
        r.skipKind = "skip_lang"; r.lkTag = String.format(Locale.ROOT, "%.2f", lk); return r; }
    }
    Phrasebook.Hit hit = pb.lookup(dir, mk.text); String mt, tag, maskedMt; int hits = 0;
    if (hit != null) { maskedMt = hit.dst; mt = TextRules.unmask(hit.dst, mk, tgt); tag = hit.kind; }
    else {
      String[] sents = mk.text.split(SENT);                               // parakeet пунктуирует обе стороны
      if (sents.length > 1) {                                             // по предложениям: словарь там, где попал
        StringBuilder sb = new StringBuilder(); int pbHits = 0;
        for (String sn : sents) {
          Phrasebook.Hit h = pb.lookup(dir, sn);
          String part; if (h != null) { part = h.dst; pbHits++; } else { part = eng.translate(dir, sn); pb.record(dir, sn, part); }
          if (sb.length() > 0) sb.append(' '); sb.append(part);
        }
        maskedMt = sb.toString(); mt = TextRules.unmask(maskedMt, mk, tgt);
        tag = pbHits > 0 ? "словарь+MT (" + pbHits + "/" + sents.length + ")" : "MT (" + sents.length + " фразы)";
      } else {
        String mtM = eng.translate(dir, mk.text); hits = pb.record(dir, mk.text, mtM);
        // На третьем повторе фраза стала быстрой — и её выученный перевод (в том числе правка от
        // облака) должен прозвучать уже сейчас, а не со следующего раза.
        if (hits >= Phrasebook.PROMOTE_HITS) { Phrasebook.Hit fh = pb.lookup(dir, mk.text); if (fh != null) mtM = fh.dst; }
        maskedMt = mtM; mt = TextRules.unmask(mtM, mk, tgt); tag = hits >= Phrasebook.PROMOTE_HITS ? "выучено (" + hits + "×)" : "MT" + (hits > 1 ? " (" + hits + "×)" : "");
      }
    }
    // Неизвестный модели токен выходит как «<unk>» и доезжал до экрана и до озвучки.
    if (mt.indexOf('<') >= 0) mt = mt.replace("<unk>", "").replaceAll("\\s{2,}", " ").trim();
    if (maskedMt.indexOf('<') >= 0) maskedMt = maskedMt.replace("<unk>", "").replaceAll("\\s{2,}", " ").trim();
    if (tgt.equals("pt")) { String br = TextRules.toBrazilian(mt); if (!br.equals(mt)) { mt = br; tag += " · pt-BR"; } maskedMt = TextRules.toBrazilian(maskedMt); }
    if (!r.whits.isEmpty()) { StringBuilder w = new StringBuilder();
      for (WordList.Hit x : r.whits) w.append(w.length() > 0 ? ", " : "").append(x.found.replaceAll("[.,!?;:]+$", "")).append("→").append(x.e.side(tgt));
      tag += " · 📝 " + w; }
    if (mk.slots.size() > r.whits.size()) tag += " · маска×" + (mk.slots.size() - r.whits.size());
    r.mt = mt; r.tag = tag; r.maskedMt = maskedMt; r.hit = hit; r.hits = hits; r.cacheable = hit != null || hits >= Phrasebook.PROMOTE_HITS;
    return r;
  }

  /** Всё после распознавания: перевод (translateOnce), озвучка, журнал, запись в разговор.
   *  Вынесено из звукового пути потому, что текст приходит не только с микрофона: снимок вывески
   *  и набранная фраза должны пройти ровно тот же маршрут, иначе у них будет свой перевод мимо
   *  словаря и мимо изучения слов. Разговор снимается в начале: реплика ляжет в тот, где была
   *  сказана, даже если за время распознавания человек переключился на другой. */
  void processText(String dirIn, String asrIn, boolean auto, boolean gate, double durMs, long srcMs, String kind) {
    processText(dirIn, asrIn, auto, gate, durMs, srcMs, kind, chats == null ? 0 : chats.current);
  }
  void processText(String dirIn, String asrIn, boolean auto, boolean gate, double durMs, long srcMs, String kind, final long chatId) {
    try {
      long t1 = System.nanoTime();
      String who = null, spkTag = null;
      if (lastSpkWho != null) { spkTag = " · 🎤" + lastSpkWho + " (" + lastSpkMs + " мс)"; String w = lastSpkWho.split(" ")[0]; if (!"?".equals(w)) who = w; lastSpkWho = null; }
      Once r = translateOnce(dirIn, asrIn, auto, gate);
      if (r.skip != null) { log(r.skip); tsvSeg(r.skipKind, r.dir, r.asr, "", r.lkTag, durMs, srcMs, 0); return; }
      String guard = readSkip(r.dir, r.asr, who, kind);
      if (guard != null) { log(guard); hint(guard); status(guard); tsvSeg("skip_read", r.dir, r.asr, "", "", durMs, srcMs, 0); return; }
      String dir = r.dir, asr = r.asr, mt = r.mt, tag = r.tag + (spkTag == null ? "" : spkTag);
      long t2 = System.nanoTime();
      final long at = System.currentTimeMillis();
      final boolean here = chats == null || chats.current == chatId;   // разговор не сменился, пока считали
      final Turn turn = new Turn(++turnNo, dir, asr, mt, at);
      if (here) { history.add(turn); if (history.size() > 30) history.remove(0); }
      final String fTgt = r.tgt;                 // направление могло смениться по языку реплики
      final long[] first = {0}; double audioS = 0;
      final boolean cacheable = r.cacheable;
      if (silent || !here) { first[0] = t2; tag += silent ? " · без озвучки" : " · разговор сменился, без озвучки"; }
      // Пауза до озвучки. Перевод уже готов и уже на экране — ждёт только голос, и ждёт он
      // не таймера, а тишины: пока человек говорит, переводы копятся в очереди и произносятся
      // после того, как он замолчал. Иначе на длинном монологе перевод начинает звучать
      // собеседнику в лицо посреди фразы.
      else if (holdMs > 0 && kind.equals("asr")) {
        sayQ.add(new Object[]{dir, fTgt, mt, cacheable, turn});
        first[0] = t2; tag += " · озвучка ждёт паузы";
      }
      else { first[0] = 0; audioS = speakTurn(dir, fTgt, mt, cacheable, first); if (first[0] == 0) first[0] = System.nanoTime(); }
      if (here) { lastDir = dir; lastSrc = asr; lastDst = mt; lastMaskedSrc = r.maskedSrc; lastMaskedDst = r.maskedMt; lastSlots = r.slots; lastAt = at; }
      String line = String.format("#%d %s | %s\n   → %s\n   %s · %s %d · mt %d · tts→звук %d · до звука %d мс%s", turn.n, dir, asr, mt, tag,
          kind, srcMs, (t2 - t1) / 1000000, (first[0] - t2) / 1000000, srcMs + (first[0] - t1) / 1000000,
          durMs > 0 ? String.format(Locale.ROOT, " (аудио %.1f с)", durMs / 1000.0) : "");
      log(line); tsvSeg(kind, dir, asr, mt, tag, durMs, srcMs, (t2 - t1) / 1000000);
      if (chats != null) {
        // Всегда по номеру, снятому до распознавания: разговор могли сменить и во время озвучки выше.
        boolean ok = chats.addTo(chatId, dir, asr, mt, who, at);
        if (!ok) {                                  // прежний разговор был пуст и удалён при уходе — реплике негде лечь, кроме текущего
          chats.add(dir, asr, mt, who, at);
          log("↩ прежний разговор " + chatId + " был пуст и удалён — реплика записана в текущий");
        } else if (chats.current != chatId) {
          log("↩ реплика записана в прежний разговор " + chatId);
          return;                                   // для нового разговора она чужая: ни в историю, ни на экран
        }
        if (chats.rolled) { chats.rolled = false; log("📄 в разговоре набралось " + Chats.KEEP + " реплик — продолжаю в новом"); }
      }
      final String fSrc = asr, fDst = mt, fDir = dir;
      Listener lt = listener; if (lt != null) main.post(() -> lt.onTurn(fDir, fSrc, fDst, false));
      notify(asr + " → " + mt); scheduleRefine();
    } catch (Throwable t) { Log.e(TAG, "process", t); log("Ошибка: " + t); tsv("error", dirIn, String.valueOf(t)); }
  }
  /** Произнести готовый перевод. Вынесено из конвейера, потому что озвучка может ждать паузы
   *  в речи, а перевод ждать не должен. */
  /** Один голос за раз. Очередь озвучки, набранная фраза, снимок и «получше» синтезируют
   *  в разных потоках, а AudioTrack один: без этого замка две озвучки писали в него вперемешку,
   *  и первая же закончившаяся снимала заглушку с микрофона под второй. */
  final Object tts = new Object();

  double speakTurn(String dir, String tgt, String mt, boolean cacheable, long[] first) throws Exception {
    synchronized (tts) {
    int rate = eng.ttsSampleRate(tgt); ensureTrack(rate);
    // В наушник — значит озвучка не попадает в комнату и микрофон можно не глушить:
    // собеседник продолжает говорить, пока в ухе идёт перевод. Замер протечки: −13…+0,1 дБ,
    // то есть микрофон не слышит наушник вовсе (results/2026-09-13-headphones.md).
    final boolean dup = btDuplex();
    if (!dup) muteUntil = Long.MAX_VALUE;
    double audioS = 0;
    float[] cached = pb.audio(dir, mt);
    try {                                  // без finally одно исключение в синтезе делало приложение глухим навсегда
      if (cached != null) { if (first != null && first[0] == 0) first[0] = System.nanoTime(); writeOut(cached, cached.length, tgt); audioS = cached.length / (double) rate; }
      else { GeneratedAudio ga = eng.speak(tgt, mt, chunk -> { if (first != null && first[0] == 0) first[0] = System.nanoTime(); writeOut(chunk, chunk.length, tgt); return 1; });
        audioS = ga.getSamples().length / (double) rate; if (cacheable) pb.putAudio(dir, mt, ga.getSamples(), rate); }
    } finally { if (!dup) muteUntil = System.currentTimeMillis() + (long) (audioS * 1000) + 400; }
    return audioS;
    }
  }

  /** Очередь отложенной озвучки и поток, который её опустошает. */
  final java.util.concurrent.BlockingQueue<Object[]> sayQ = new java.util.concurrent.LinkedBlockingQueue<>();
  volatile long lastSpeechAt = 0;
  /** Сколько тишины ждать, прежде чем заговорить. Одинаково для обоих языков: длинная фраза
   *  бывает и по-русски, и по-португальски. 0 — говорить сразу, как было раньше. */
  public volatile int holdMs = 1500;
  Thread sayThread;

  void startSay() {
    if (sayThread != null && sayThread.isAlive()) return;
    sayThread = new Thread(() -> {
      while (running) {
        try {
          Object[] it = sayQ.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
          if (it == null) continue;
          long t0 = System.currentTimeMillis();
          // Ждём тишины, а не таймера: пока человек говорит, ждём дальше. Предел на всякий
          // случай есть — иначе при непрерывном шуме перевод не прозвучит никогда.
          while (running && holdMs > 0 && System.currentTimeMillis() - lastSpeechAt < holdMs
                 && System.currentTimeMillis() - t0 < 20000) Thread.sleep(80);
          long waited = System.currentTimeMillis() - t0;
          // Пока реплика ждала тишины, уточнитель мог её поправить — произносим свежий вариант.
          Turn tn = it.length > 4 ? (Turn) it[4] : null;
          if (tn != null && tn.dropped) continue;   // реплику поправили или удалили, пока она ждала
          String say = tn != null && tn.refined != null ? tn.refined : (String) it[2];
          double sec = speakTurn((String) it[0], (String) it[1], say, (Boolean) it[3], null);
          if (waited > 150) log(String.format(Locale.ROOT, "🔊 озвучено после паузы %.1f с (%.1f с звука, в очереди ещё %d)", waited / 1000.0, sec, sayQ.size()));
        } catch (InterruptedException e) { return; }
        catch (Throwable t) { log("🔊 озвучить не вышло: " + t); }
      }
    }, "say");
    sayThread.start();
  }

  /** Одна строка на сегмент: текст, уровень, фон, SNR — чтобы прогон через воздух считался по числам, а не по логу. */
  void tsvSeg(String kind, String dir, String asr, String mt, String tag, double durMs, long asrMs, long mtMs) {
    tsv(kind, dir, asr, mt, tag, f1(durMs), f1(segDb), f1(segNoiseDb), f1(segDb - segNoiseDb), "" + segAt, "" + asrMs, "" + mtMs, "" + segPos);
  }
  static double rms(float[] s, int n) { double a = 0; for (int k = 0; k < n; k++) a += s[k] * (double) s[k]; return Math.sqrt(a / Math.max(1, n)); }
  static double db(double x) { return 20 * Math.log10(Math.max(1e-9, x)); }
  static String f1(double x) { return Double.isNaN(x) ? "" : String.format(Locale.ROOT, "%.1f", x); }

  /** Роль «говорящего»: гонит эталоны в динамик с нормировкой по громкости и паузами.
   *  Уровень выравнивается здесь, а не в исходных файлах, чтобы корпус остался тем же,
   *  на котором мерили WER по файлу — иначе сравнивать будет не с чем. */
  void playDir(String d, int gap, int rounds, double normDb) {
    File f = new File(d);
    File[] fs = f.isDirectory() ? f.listFiles((x, n) -> n.endsWith(".wav")) : new File[]{f};
    if (fs == null || fs.length == 0) { log("▶ нет файлов в " + d); return; }
    Arrays.sort(fs);
    AudioManager am = getSystemService(AudioManager.class);
    String vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    log("▶ " + fs.length + " файлов × " + rounds + ", пауза " + gap + " мс, уровень " + f1(normDb) + " dBFS, громкость " + vol);
    tsv("play_begin", d, "" + fs.length, "" + rounds, "" + gap, f1(normDb), vol);
    for (int r = 0; r < rounds && running; r++) for (File x : fs) {
      if (!running) break;
      try {
        WaveReader wr = new WaveReader(x.getAbsolutePath());
        float[] s = wr.getSamples().clone(); int sr = wr.getSampleRate();
        double gain = Math.pow(10, normDb / 20.0) / Math.max(1e-9, rms(s, s.length)), peak = 0;
        for (float v : s) peak = Math.max(peak, Math.abs(v));
        if (peak * gain > 0.99) gain = 0.99 / peak;          // клиппинг искажает сильнее, чем недостаток громкости
        for (int k = 0; k < s.length; k++) s[k] *= gain;
        double sec = s.length / (double) sr;
        // Статический режим, своя дорожка на файл. В потоковом getPlaybackHeadPosition не
        // продвигалась, ожидание падало по таймауту, звук выходил позже — и соседние файлы
        // звучали слитно, с паузой 0,4 с вместо заданных пяти. Снаружи это выглядело как
        // «VAD склеивает фразы»: см. results/2026-09-12-air.md.
        AudioTrack t = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(s.length * 4).setTransferMode(AudioTrack.MODE_STATIC).build();
        t.write(s, 0, s.length, AudioTrack.WRITE_BLOCKING);
        tsv("play", x.getName(), f1(sec * 1000), f1(db(rms(s, s.length))), f1(20 * Math.log10(gain)), "" + sr);
        t.play();
        Thread.sleep((long) (sec * 1000) + 150);
        try { t.stop(); } catch (Throwable e) {}
        t.release();
        Thread.sleep(gap);
      } catch (Throwable e) { log("▶ " + x.getName() + ": " + e); }
    }
    log("■ проигрывание закончено"); tsv("play_end", d);
  }

  void ensureTrack(int rate) { ensureTrack(rate, "pt-left".equals(split) || "pt-right".equals(split) ? 2 : 1); }
  /** Стерео нужно, чтобы развести языки по ушам: один наушник собеседнику, другой владельцу,
   *  и каждый слышит только свой язык. В моно оба слышали бы всё. */
  void ensureTrack(int rate, int ch) {
    if (track != null && trackRate == rate && trackCh == ch) return; if (track != null) { track.stop(); track.release(); }
    int mask = ch == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
    int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT);
    track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(mask).build()).setBufferSizeInBytes(Math.max(min, rate * ch * 4 * 3)).setTransferMode(AudioTrack.MODE_STREAM).build();
    track.play(); trackRate = rate; trackCh = ch;
    log("выход: " + outName() + (btDuplex() ? " · дуплекс (микрофон не глушим)" : ""));
  }
  String micName() {
    try {
      AudioDeviceInfo d = capRouted;
      if (d == null) return "?";
      switch (d.getType()) {
        case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "встроенный микрофон";
        case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "микрофон наушников (SCO)";
        case AudioDeviceInfo.TYPE_BLE_HEADSET: return "микрофон LE Audio";
        case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "микрофон гарнитуры";
        default: return "тип " + d.getType();
      }
    } catch (Throwable e) { return "?"; }
  }
  /** Мощность кадра на заданной частоте (Гёрцель). Щелчок тонален, шум комнаты широкополосен,
   *  поэтому по общей громкости тон терялся, а по своей частоте виден. */
  static double tone(float[] x, int n, double freq, int rate) {
    double w = 2 * Math.PI * freq / rate, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
    for (int k = 0; k < n; k++) { double s0 = x[k] + c * s1 - s2; s2 = s1; s1 = s0; }
    return Math.sqrt(Math.max(0, s1 * s1 + s2 * s2 - c * s1 * s2)) / n;
  }
  /** Средний уровень входа за окно — им меряется протечка озвучки обратно в микрофон. */
  double probeRms(long ms) {
    long end = System.currentTimeMillis() + ms; double sum = 0; int n = 0;
    while (System.currentTimeMillis() < end) {
      float[] w = null;
      try { w = capQ.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { break; }
      if (w == null) continue;
      double r = rms(w, w.length); sum += r * r; n++;
    }
    return n == 0 ? 0 : Math.sqrt(sum / n);
  }
  /** Дорожка, принудительно направленная в динамик телефона. Нужна для раздельного вывода:
   *  собеседнику португальский вслух, владельцу русский в наушник — одновременно и с одного
   *  телефона. Разведение по ушам TWS не дало (сводят каналы), а это работает через маршрутизацию. */
  void ensureSpk(int rate) {
    if (spkTrack != null && spkRate == rate) return;
    if (spkTrack != null) { spkTrack.stop(); spkTrack.release(); }
    int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
    spkTrack = new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(Math.max(min, rate * 4 * 3)).setTransferMode(AudioTrack.MODE_STREAM).build();
    for (AudioDeviceInfo d : getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))
      if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { spkTrack.setPreferredDevice(d); break; }
    spkTrack.play(); spkRate = rate;
    AudioDeviceInfo r = spkTrack.getRoutedDevice();
    log("🔉 вторая дорожка в динамик: " + (r == null ? "?" : "тип " + r.getType()) + (r != null && r.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? " — динамик" : " — НЕ динамик"));
  }

  /** Единственное место, где звук уходит в дорожку: здесь же усиление и раскладка по ушам.
   *  Ограничитель обязателен — без него усиление просто клиппирует речь и делает её хуже,
   *  а не громче. lang определяет ухо: ru — владельцу, pt — собеседнику. */
  void writeOut(float[] mono, int n, String lang) {
    double g = Math.pow(10, outGainDb / 20.0);
    if ("device".equals(split) && "pt".equals(lang)) {          // португальский вслух собеседнику
      ensureSpk(trackRate > 0 ? trackRate : 22050);
      float[] out = new float[n];
      for (int k = 0; k < n; k++) out[k] = clip(mono[k] * g);
      spkTrack.write(out, 0, n, AudioTrack.WRITE_BLOCKING);
      return;
    }
    if (trackCh == 1) {
      float[] out = new float[n];
      for (int k = 0; k < n; k++) out[k] = clip(mono[k] * g);
      synchronized (warmLock) { track.write(out, 0, n, AudioTrack.WRITE_BLOCKING); }
      return;
    }
    boolean left = "pt".equals(lang) == "pt-left".equals(split);   // pt-left: pt влево, ru вправо
    if (trackCh == 1) { float[] o = new float[n]; for (int k = 0; k < n; k++) o[k] = clip(mono[k] * g);
      synchronized (warmLock) { track.write(o, 0, n, AudioTrack.WRITE_BLOCKING); } return; }
    float[] out = new float[n * 2];
    for (int k = 0; k < n; k++) { float v = clip(mono[k] * g); out[2 * k + (left ? 0 : 1)] = v; }
    synchronized (warmLock) { track.write(out, 0, n * 2, AudioTrack.WRITE_BLOCKING); }
  }
  static float clip(double v) { return (float) (v > 0.99 ? 0.99 : v < -0.99 ? -0.99 : v); }

  /** Имя устройства вывода. Наушник против динамика решает, можно ли слушать во время озвучки. */
  String outName() {
    try {
      AudioDeviceInfo d = track == null ? null : track.getRoutedDevice();
      if (d == null) return "?";
      switch (d.getType()) {
        case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
        case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth SCO (гарнитура)";
        case AudioDeviceInfo.TYPE_BLE_HEADSET: return "LE Audio";
        case AudioDeviceInfo.TYPE_WIRED_HEADSET: case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "проводной наушник";
        case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "динамик";
        default: return "тип " + d.getType();
      }
    } catch (Throwable e) { return "?"; }
  }
  /** Вывод в наушник — значит собственная озвучка не попадает в комнату и глушить микрофон незачем. */
  boolean btDuplex() {
    if (!duplex) return false;
    try {
      AudioDeviceInfo d = track == null ? null : track.getRoutedDevice();
      if (d == null) return false;
      int t = d.getType();
      return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET
          || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
    } catch (Throwable e) { return false; }
  }
  /** Задержка тракта вывода: от записи первого отсчёта до момента, когда он прозвучал.
   *  Считаем по метке предъявления самой дорожки — «кадр N прозвучал в момент T», значит
   *  кадр 0 прозвучал в T − N/частота. Это и есть задержка, включая передачу по Bluetooth;
   *  наполнение буфера сюда не входит (мерить по остатку буфера — мерить скорость синтеза). */
  long outLatencyMs(long headWriteNanos) {
    try {
      android.media.AudioTimestamp ts = new android.media.AudioTimestamp();
      if (track == null || !track.getTimestamp(ts) || ts.framePosition <= 0) return -1;
      long frameZeroAt = ts.nanoTime - ts.framePosition * 1000000000L / Math.max(1, trackRate);
      return (frameZeroAt - headWriteNanos) / 1000000;
    } catch (Throwable e) { return -1; }
  }
  void status(String s) { Listener l = listener; if (l != null) main.post(() -> l.onStatus(s)); }
  final java.util.concurrent.ExecutorService fileLog = java.util.concurrent.Executors.newSingleThreadExecutor();
  final java.text.SimpleDateFormat stamp = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT);
  void log(String s) {
    Log.i(TAG, s.replace('\n', ' '));
    final String line = stamp.format(new java.util.Date()) + " " + s.replace('\n', ' ') + "\n";
    fileLog.submit(() -> { try (java.io.FileWriter w = new java.io.FileWriter(new File(getExternalFilesDir(null), "at.log"), true)) { w.write(line); } catch (Exception e) {} });
    Listener l = listener; if (l != null) main.post(() -> l.onLog(s));
  }
  /** Машинный журнал для стенда: разбирать человеческий лог регулярками — способ намерить чушь. */
  void tsv(String kind, String... cols) {
    StringBuilder b = new StringBuilder().append(System.currentTimeMillis()).append('\t').append(kind);
    for (String c : cols) b.append('\t').append(c == null ? "" : c.replace('\t', ' ').replace('\n', ' '));
    final String line = b.append('\n').toString();
    fileLog.submit(() -> { try (java.io.FileWriter w = new java.io.FileWriter(new File(getExternalFilesDir(null), "at.tsv"), true)) { w.write(line); } catch (Exception e) {} });
  }
  /** Сердцебиение: без него «замолчал в 18:41» неотличимо от «никто не говорил». */
  void heartbeat() {
    new Thread(() -> {
      int min = 0;
      while (running) {
        try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
        if (!vadMode) { min = 0; continue; }
        long cpu = 0;
        try { String[] f = new String(java.nio.file.Files.readAllBytes(new File("/proc/self/stat").toPath()), "UTF-8").split(" ");
          cpu = (Long.parseLong(f[13]) + Long.parseLong(f[14])) / 100; } catch (Exception e) {}
        int th = -1;
        try { th = getSystemService(PowerManager.class).getCurrentThermalStatus(); } catch (Throwable e) {}
        boolean muted = System.currentTimeMillis() < muteUntil;
        // Потеря звука: сколько секунд микрофон должен был отдать по часам и сколько отдал.
        // Без этой строки четверть потерянной речи выглядела как «плохо распознаёт».
        double wall = (System.currentTimeMillis() - captureStart) / 1000.0, got = samplesRead / 16000.0;
        String loss = captureStart == 0 || wall <= 0 ? "" : String.format(Locale.ROOT, " · звук %.1f%% (очередь %d, выброшено %d)",
            100 * got / wall, capQ.size(), framesDropped);
        log("♥ минута " + (++min) + " · тепловой " + th + " · CPU " + cpu + " с · " + (muted ? "заглушён" : "слушаю")
            + loss + (micSilenced ? " · МИКРОФОН ЗАГЛУШЕН СИСТЕМОЙ" : ""));
      }
    }, "heartbeat").start();
  }
  volatile boolean micSilenced = false;
  @Override public void onDestroy() { running = false; if (store != null) store.cancel(); if (spkTrack != null) spkTrack.release(); worker.shutdownNow(); llmWorker.shutdownNow(); cloudWorker.shutdownNow(); fileLog.shutdownNow(); if (llm != null) llm.stop(); if (wl != null && wl.isHeld()) wl.release(); if (track != null) track.release(); super.onDestroy(); }
}
