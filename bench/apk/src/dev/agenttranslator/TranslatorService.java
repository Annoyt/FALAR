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
    /** Строка-подсказка на экране разговора: причина отказа, итог пересмотра. null — только обновить кнопку «Улучшить». */
    void onHint(String s);
    /** Список реплик изменился не через новую реплику: правка, пересмотр, удаление. */
    void onHistory();
    /** Имена собственные из облачного ответа — кандидаты в свои слова, добавляет человек. */
    void onNames(java.util.List<String[]> names, boolean manual);
    /** Модели по манифесту: чего не хватает, ход загрузки. Первый вызов — итог проверки при старте. */
    void onModels(ModelStore.State s);
    /** Состояние проверки и установки обновления приложения; пустая строка — сказать нечего. */
    void onUpdate(String state);
    /** Что приложение делает прямо сейчас: «перевожу…», «уточняю перевод» — для полосы на экране
     *  разговора. kind — чья работа (BUSY_ORDER): живой перевод и уточнитель экран показывает в
     *  реплике, облако и загрузки — в шапке. what == null — ничего; total > 0 — сделано done из
     *  total, иначе без хода. */
    void onBusy(String kind, String what, int done, int total);
    /** Снимок прочитан и переведён: реплика «📷» с меткой at легла в разговор chatId — показать
     *  перевод поверх снимка. */
    void onPhoto(long chatId, long at);
    /** Включённые модули изменились: спрятать или показать их кнопки и пункты. */
    void onModules(); }
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
  public volatile Engine eng; public Phrasebook pb; public Speaker spk; public WordList words; public Cloud cloud; public Chats chats; public Learn learn; volatile boolean recording = false, vadMode = false, running = true, capturing = false; volatile long muteUntil = 0;
  /** «Читаю вслух»: человек держит крупный текст и произносит португальскую фразу сам, по
   *  транскрипции. Микрофон в это время глух — иначе приложение слышит владельца, считает его
   *  собеседником и переводит ему же его фразу обратно. */
  public volatile boolean readingAloud = false;
  /** Последняя проговорённая вслух португальская фраза и когда закончилась её озвучка. */
  volatile String spokenPt; volatile long spokenPtEnd;
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
  /** Чувствительность прослушивания. Ставится в потоке нарезки, а не при захвате: там она
   *  одинаково ложится на живой микрофон и на подачу записи (feedwav), и её можно проверять на
   *  записях комнаты. Удержанию — своя, на всю фразу при отпускании (pttStop). */
  final Gain gainListen = new Gain(16000);
  /** Стенд: false — прежний срез по ±0,99 вместо ограничителя (--es limiter 0); gainTest —
   *  чувствительность на прогон (--es micgaintest), главнее настройки и в неё не пишется. Отдельным
   *  полем, потому что после подъёма движков micGainDb перечитывается из настроек и затёр бы её. */
  volatile boolean limiterOn = true; volatile double gainTest = Double.NaN;
  /** Автоматическая чувствительность — по умолчанию. По замеру на записях комнаты
   *  (results/2026-09-29-mic-gain.md) и нарезка, и распознавание лучше всего при речи около
   *  −24 dBFS; громче и тише — хуже в обе стороны, и одна ручка не подходит разом тихому и обычному
   *  собеседнику: +24 дБ спасают тихого (WER 56 → 15 %) и портят обычного (16 → 28 %).
   *  Прослушивание: после каждой фразы усиление сдвигается к нужному на AUTO_STEP пути и
   *  запоминается: очень тихую речь (−48 dBFS) VAD не слышит вовсе, и сессия, начатая с нуля,
   *  теряла первые восемь фраз, пока не поймала одну. Удержание: фраза есть целиком, и усиление
   *  считается по ней самой. */
  static final double TARGET_DB = -24, AUTO_MIN = -12, AUTO_MAX = 24, AUTO_STEP = 0.7;
  volatile boolean micAuto = true; volatile double autoDb = 0;
  double micGain() { double t = gainTest; return !Double.isNaN(t) ? t : micAuto ? autoDb : micGainDb; }
  boolean autoOn() { return micAuto && Double.isNaN(gainTest); }
  static double clampAuto(double db) { return Math.max(AUTO_MIN, Math.min(AUTO_MAX, db)); }
  void saveAuto() { getSharedPreferences("at", MODE_PRIVATE).edit().putFloat("micautodb", (float) autoDb).apply(); }
  /** Уровень входа для полосы на экране, dBFS после усиления: пик кадров с плавным спадом
   *  (1,5 дБ за кадр 32 мс), чтобы опрос 5–10 раз в секунду видел всплески, а не случайный кадр.
   *  levelOver — вход в кадре упёрся в край шкалы ещё до усиления. */
  volatile float levelDb = -120; volatile boolean levelOver = false; volatile long levelAt = 0;
  /** Как слышно, 0…1 (Hearing.Live) — цвет кнопки удержания и полоски под кнопками слушания:
   *  1 — зелёный, 0 — красный, −1 — цвета ещё нет (речи пока не было). Считается по речи с начала
   *  удержания или слушания; паузы его не трогают, и в паузе цвет держится. Держат кнопку и молчат
   *  полторы секунды — 0, «не слышу». liveSpeech — речь была в последнюю секунду (полоска слушания
   *  в паузе серая). */
  volatile float liveQ = -1; volatile boolean liveSpeech = false;
  /** Стенд: звук вместо микрофона у удержания (--es micfile), 16 кГц; micFilePos — где мы в нём
   *  (с нуля на каждом нажатии). */
  volatile float[] micFile = null; int micFilePos = 0;
  /** Оценки для liveQ — по удержанию целиком и по каждой нарезанной фразе слушания, как их итоги в
   *  журнале. У удержания — в шкале до усиления, фон свой; у слушания — после усиления, фон нарезки
   *  (noiseRms). Каждую трогает только свой поток — удержание поток захвата, слушание поток нарезки;
   *  сброс слушания извне — флагом, его делает сам поток нарезки. */
  final Hearing.Live pttLive = new Hearing.Live(), listenLive = new Hearing.Live();
  volatile boolean listenLiveReset = true;
  /** Сброс VAD извне — тоже флагом: Silero живёт в потоке нарезки, и reset() из другого потока
   *  посреди acceptWaveform() ронял приложение («Vad_acceptWaveform: vector», поток vad) — так
   *  выключали «Слушать», пока шёл звук (test_hearing_device.sh на Redmi 01.10: два прогона из трёх,
   *  в том числе на 0.26.0 как вышла). */
  volatile boolean vadReset = false;
  /** Фон комнаты по слушанию, dBFS до усиления, и когда он мерился (uptime). Удержанию он нужен,
   *  когда в самой записи тишины нет — заговорили сразу, отпустили сразу. Годен ROOM_MS: комната
   *  за пару минут меняется редко, а устаревший фон занизил бы шум. */
  volatile double roomDb = Double.NaN; volatile long roomAt = 0; double pttRoomDb = Double.NaN;
  static final long ROOM_MS = 120_000;
  double room() { return android.os.SystemClock.uptimeMillis() - roomAt <= ROOM_MS ? roomDb : Double.NaN; }
  /** Как слышно последнюю фразу, для журнала at.tsv: речь и фон по самой фразе, доли отсчётов —
   *  перегруз входа, работа ограничителя, срез. */
  volatile double segGainDb = 0, segSpeechDb = Double.NaN, segFloorDb = Double.NaN, segOverPct = 0, segLimPct = 0, segCutPct = 0;
  final List<float[]> pttBuf = new ArrayList<>(); String pttDir = "pt2ru"; AudioTrack track; int trackRate = 0; Thread capThread;
  PowerManager.WakeLock wl; volatile String lastDir, lastSrc, lastDst, lastLine = "";
  public static class Turn { public final int n; public final String dir, asr, mt; public final long at; public volatile String refined;
    /** Реплику поправили или удалили, пока она ждала озвучки: поток озвучки её пропускает. */
    public volatile boolean dropped;
    Turn(int n, String d, String a, String m, long at) { this.n = n; dir = d; asr = a; mt = m; this.at = at; } }
  public final List<Turn> history = Collections.synchronizedList(new ArrayList<>()); int turnNo = 0;
  /** Знаков на токен у модели уточнителя — замер при её запуске (Brief). */
  volatile double llmCpt = Brief.DEFAULT_CHARS_PER_TOKEN;
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
  /** До какой реплики (её at) и в каком разговоре облако уже пересмотрело разговор — для «Улучшить».
   *  Отметка нужна отдельно от правок: облако могло прочесть фразу и ничего в ней не менять. */
  volatile long lastCloudAt = 0, lastCloudChat = 0;
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
    // Модули: выбор человека; до выбора (новая установка) — по умолчанию под этот телефон.
    String saved = getSharedPreferences("at", MODE_PRIVATE).getString(PREF_MODULES, null);
    modules = saved != null ? Modules.parse(saved) : Modules.defaults(totalRam());
    modulesChosen = saved != null; store.modules = modules;
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
      // Обновились с версии без модулей: обязательное на месте, выбора не было — включаем то, что
      // уже скачано (облако — если есть ключ), экрана выбора не показываем (решение владельца 28.09).
      if (!modulesChosen && p.need.isEmpty()) {
        Set<String> inst = new LinkedHashSet<>();
        for (String m : Modules.CHOICE) if (store.bytes(m) > 0 && store.installed(m)) inst.add(m);
        setModules(Modules.upgraded(inst, new File(modelsDir, "openrouter.json").exists()), "как было до модулей");
        store.summarize();                             // мы на рабочем потоке: состояние ниже — уже с модулями
      }
      // Заменённое уже скачанным и сверенным — убрать до загрузки движков: они возьмут новый путь.
      store.cleanObsolete();
      ModelStore.State st = store.state(); Listener l = listener; if (l != null) main.post(() -> l.onModels(st));
      if (st.upgrade > 0) log("📦 можно облегчить перевод: скачать " + ModelStore.mb(st.upgradeBytes) + " МБ — «Система» → «облегчить перевод»; до тех пор работает прежний путь");
      if (st.autoMissing > 0) log("📦 модулям не хватает " + ModelStore.mb(st.autoBytes) + " МБ: " + whatFetch(store.autos()) + " — докачается само по разрешённой сети");
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
      Engine.mtVariant = getSharedPreferences("at", MODE_PRIVATE).getString("mt_variant", "");
      Engine.withTts = mod(Modules.TTS);
      // Ход загрузки — экрану: этапы «распознавание · перевод · озвучка · словарь и разговоры»,
      // сколько каждый шёл в прошлый раз экран берёт из load_ms (владелец 01.10: «не хватает
      // индикации загрузки моделей, чтобы было понятно, что вот-вот скоро включится»).
      final int nStages = Engine.withTts ? 4 : 3;
      Engine.stage = w -> busy("load", "asr".equals(w) ? "распознавание" : "mt".equals(w) ? "перевод" : "озвучка",
          "asr".equals(w) ? 0 : "mt".equals(w) ? 1 : 2, nStages);
      eng = new Engine(models, this::log);
      Engine.stage = null;
      final long restFrom = System.nanoTime();
      busy("load", "словарь и разговоры", nStages - 1, nStages);
      pb = new Phrasebook(models, mod(Modules.CORPUS));
      spk = new Speaker(models, mod(Modules.SPEAKER)); words = new WordList(models); cloud = new Cloud(models); ocr = new Ocr(models);
      Object[] old = Voices.retireOld(models);
      if ((int) old[0] >= 0) log("🎤 общие голоса прежних версий удалены: " + old[0] + " — теперь голос запоминается в каждом разговоре фразой кнопкой FALAR"
          + (((String) old[1]).isEmpty() ? "" : " (файл не прочёлся: " + old[1] + ")"));
      log("🧩 модули: " + modulesLine());
      // По умолчанию «точнее»: сырой перевод понятен редко, и от облака ждут прежде всего качества.
      cloud.preferQuality = getSharedPreferences("at", MODE_PRIVATE).getBoolean("cloud_quality", true);
      chats = new Chats(getExternalFilesDir(null)); learn = new Learn(chats, models, getExternalFilesDir(null));
      // Рабочая история — из текущего разговора. Раньше после запуска она была пустой, и уточнитель
      // видел только реплики, сказанные после перезапуска, — а процесс теперь завершается через
      // минуту в свёрнутом виде, и каждое возвращение было бы разговором с чистого листа.
      // Восстановленные реплики уже разобраны в прошлый раз: отметку разбора ставим на последнюю.
      rebuildHistory();
      synchronized (history) { if (!history.isEmpty()) { lastLocalAt = history.get(history.size() - 1).at; log("↩ продолжаем разговор: восстановлено реплик " + history.size()); } }
      log("📝 " + words.stats());
      if (pb.pinsWithDigits > 0) log("📌 пинов с числом без маски: " + pb.pinsWithDigits + " — они не срабатывают, перезакрепите их кнопкой «запомнить»");
      if (mod(Modules.CLOUD)) log(cloud.ready ? "☁ «Улучшить» облаком доступно: " + cloud.models.length + " бесплатных моделей"
                                               : "☁ облако включено, но ключа нет (models/openrouter.json) — «Улучшить» только уточнителем");
      if (mod(Modules.SPEAKER)) log(spk.ready ? "🎤 отпечаток голоса готов за " + spk.loadMs + " мс · голосов в разговоре: " + chats.voices.size()
                                              : "🎤 модели отпечатка голоса ещё нет — докачается, до тех пор слушание переводит все голоса");
      status("Готово. ASR " + eng.loadAsrMs + " · MT " + eng.loadMtMs + " · TTS " + eng.loadTtsMs + " мс · " + pb.stats());
      android.content.SharedPreferences pr = getSharedPreferences("at", MODE_PRIVATE);
      micGainDb = pr.getFloat("micgain", 0); outGainDb = pr.getFloat("gain", 0); micAuto = pr.getBoolean("micauto", true);
      autoDb = clampAuto(pr.getFloat("micautodb", 0));
      log("🎚 чувствительность: " + (micAuto ? String.format(Locale.ROOT, "авто, сейчас %+.1f дБ", autoDb) : "вручную, " + micGainDb + " дБ"));
      micSource = pr.getString("micsrc", "builtin");
      holdMs = pr.getInt("hold", 1500);
      refineEvery = pr.getInt("refine_every", 3); cloudEvery = pr.getInt("cloud_every", 0);
      readGuard = pr.getInt("read_guard", 1);
      restoreContext();
      maybeCheckUpdates();
      heartbeat(); startWarm(); startSay(); watchNetwork();
      boolean lp = pr.getBoolean("lpt", false), lr = pr.getBoolean("lru", false);
      if (lp || lr) setListen(lp, lr); else { log("🎚 микрофон выключен: включите «Слушать PT» или «Слушать RU»"); status("Микрофон выключен"); }
      // Сколько шёл каждый этап — для хода загрузки в следующий раз: распознавание, перевод, озвучка, остальное.
      long rest = (System.nanoTime() - restFrom) / 1000000;
      getSharedPreferences("at", MODE_PRIVATE).edit().putString("load_ms", eng.loadAsrMs + "," + eng.loadMtMs + "," + eng.loadTtsMs + "," + rest).apply();
      busy("load", null, 0, 0);
      notify("Готов. " + pb.stats()); Listener l = listener; if (l != null) main.post(l::onReady);
      maybeAutoUpgrade("");
    } catch (Throwable t) { Log.e(TAG, "init", t); status("Ошибка: " + t); Engine.stage = null; busy("load", null, 0, 0); }
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
    // Стенд: голос в текущий разговор, как фраза кнопкой FALAR, только без распознавания
    // (--es voicewav <wav> --es lang ru|pt), и чей это голос по голосам разговора (--es voiceid <wav>).
    if (i != null && i.hasExtra("voicewav")) { final String wav = i.getStringExtra("voicewav"), lang = "ru".equals(i.getStringExtra("lang")) ? "ru" : "pt";
      worker.submit(() -> { try { WaveReader wr = new WaveReader(wav); long t = System.nanoTime();
        float[] e = spk == null ? null : spk.embed(wr.getSamples(), wr.getSampleRate());
        if (e == null || chats == null) { log("🎤 стенд: голос не записан — " + (spk == null || !spk.ready ? "модели отпечатка нет" : "запись короче " + Speaker.MIN_SECONDS + " с")); return; }
        Voices.Match m = chats.voices.best(e, false); int before = chats.voices.size();
        Voices.Voice v = chats.enroll(e, lang, System.currentTimeMillis());
        log("🎤 стенд: " + new File(wav).getName() + " → " + voiceNote(v, m, chats.voices.size() > before) + " (" + (System.nanoTime() - t) / 1000000 + " мс)");
        Listener l = listener; if (l != null) main.post(l::onHistory); } catch (Throwable t) { log("🎤 стенд: ошибка записи голоса — " + t); } }); }
    // Стенд: отпечаток на телефоне против эталона со стола (tools/voiceprint_ref.py golden):
    // --es voicegold <json>, записи — рядом с json по имени файла. Java-признаки и ORT на телефоне
    // должны дать тот же отпечаток (косинус ≈ 1); заодно — сколько он стоит на фразу.
    if (i != null && i.hasExtra("voicegold")) { final File gp = new File(i.getStringExtra("voicegold"));
      worker.submit(() -> { try {
        org.json.JSONArray g = new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(gp.toPath()), "UTF-8"));
        StringBuilder b = new StringBuilder("🎤 эталон отпечатка:"); float worst = 2;
        for (int k = 0; k < g.length(); k++) {
          org.json.JSONObject o = g.getJSONObject(k); File w = new File(gp.getParentFile(), new File(o.getString("wav")).getName());
          WaveReader wr = new WaveReader(w.getAbsolutePath()); long t = System.nanoTime();
          float[] e = spk == null ? null : spk.embed(wr.getSamples(), wr.getSampleRate()); long ms = (System.nanoTime() - t) / 1000000;
          org.json.JSONArray ge = o.getJSONArray("emb"); float c = -2;
          if (e != null && e.length == ge.length()) { c = 0; for (int j = 0; j < e.length; j++) c += e[j] * (float) ge.getDouble(j); }
          worst = Math.min(worst, c);
          b.append(String.format(Locale.ROOT, " %s %.4f (%d мс, %.1f с)", w.getName(), c, ms, wr.getSamples().length / 16000.0));
        }
        log(b.append(String.format(Locale.ROOT, " · худший %.4f", worst)).toString());
      } catch (Throwable t) { log("🎤 эталон отпечатка: ошибка — " + t); } }); }
    // Стенд: кусок слушания как есть — прямо в решение по голосу (route), минуя нарезку VAD: её разрез
    // от прогона к прогону разный, а проверить надо голос и разрез двоих (--es segwav <wav 16 кГц>).
    if (i != null && i.hasExtra("segwav")) { final String wav = i.getStringExtra("segwav");
      worker.submit(() -> { try { WaveReader wr = new WaveReader(wav); log("▷ стенд: кусок слушания " + new File(wav).getName()
          + String.format(Locale.ROOT, ", %.1f с", wr.getSamples().length / 16000.0)); route(wr.getSamples()); }
        catch (Throwable t) { log("▷ стенд: кусок не прочёлся — " + t); } }); }
    if (i != null && i.hasExtra("voiceid")) { final String wav = i.getStringExtra("voiceid");
      worker.submit(() -> { try { WaveReader wr = new WaveReader(wav); long t = System.nanoTime();
        float[] e = spk == null ? null : spk.embed(wr.getSamples(), wr.getSampleRate()); long ms = (System.nanoTime() - t) / 1000000;
        if (e == null || chats == null) { log("🎤 стенд: " + new File(wav).getName() + " — отпечатка нет"); return; }
        StringBuilder b = new StringBuilder();
        for (Voices.Voice v : chats.voices.all()) b.append(String.format(Locale.ROOT, " %d=%.3f", v.n, Voices.cos(e, v.print())));
        Voices.Match m = chats.voices.best(e, true);
        log("🎤 стенд: " + new File(wav).getName() + " →" + (b.length() == 0 ? " голосов нет" : b.toString()) + " · "
            + (m.hit() ? "голос " + m.v.n : "чужой") + " (" + ms + " мс)"); } catch (Throwable t) { log("🎤 стенд: ошибка опознания — " + t); } }); }
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
    // Стенд: поднять уточнитель так же, как перед проходом разбора (проверка памяти, замер
    // токенизатора), и дать ему уйти по простою — без разбора и без касания разговоров.
    // Стенд: слушание, как кнопками «Слушать PT/RU», без касаний экрана владельца.
    if (i != null && i.hasExtra("listen")) { String w = i.getStringExtra("listen");
      setListen("pt".equals(w) || "both".equals(w), "ru".equals(w) || "both".equals(w));
      Listener l = listener; if (l != null) main.post(l::onReady); }
    if (i != null && i.hasExtra("llmprobe")) llmWorker.submit(() -> {
      boolean ok = ensureLlm();
      log("🧠 проба уточнителя: " + (ok ? "поднят" : "не поднят"));
      if (ok) { main.removeCallbacks(llmIdleUnload); main.postDelayed(llmIdleUnload, LLM_IDLE_MS); }
    });
    // Стенд: способ загрузки перевода для замера памяти («off» — как всегда); вступает при следующем запуске.
    if (i != null && i.hasExtra("mtvariant")) { String v = i.getStringExtra("mtvariant"); v = v == null || "off".equals(v) ? "" : v;
      getSharedPreferences("at", MODE_PRIVATE).edit().putString("mt_variant", v).apply(); log("🧪 стенд: перевод грузится как «" + (v.isEmpty() ? "обычно" : v) + "» со следующего запуска"); }
    // Стенд: сырой перевод пачки фраз самим движком — без правил, словаря, выученного, озвучки и без
    // записи в разговор. Нужен, чтобы сравнивать способы перевода на телефоне по эталонам: одна и та
    // же фраза на столе и на телефоне переводится по-разному. Строки файла: «pt2ru|ru2pt \t исходник»;
    // ответ — рядом, в «<файл>.out»: «направление \t исходник \t перевод \t мс».
    if (i != null && i.hasExtra("mtbatch")) { final String path = i.getStringExtra("mtbatch");
      worker.submit(() -> {
        if (eng == null) { log("🧪 пачка: движок ещё загружается"); return; }
        int n = 0; long t0 = System.nanoTime();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(path), "UTF-8"));
             java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(path + ".out"), "UTF-8")) {
          for (String line; (line = r.readLine()) != null; ) {
            String[] f = line.split("\t", 2); if (f.length < 2 || !(f[0].equals("pt2ru") || f[0].equals("ru2pt"))) continue;
            long t = System.nanoTime(); String out = eng.translate(f[0], f[1]); long ms = (System.nanoTime() - t) / 1000000;
            w.write(f[0] + "\t" + f[1] + "\t" + out.replace('\t', ' ').replace('\n', ' ') + "\t" + ms + "\n"); n++;
          }
          log("🧪 пачка: " + n + " фраз за " + (System.nanoTime() - t0) / 1000000 + " мс · резидентно " + Engine.rssMb() + " МБ → " + path + ".out");
        } catch (Throwable e) { log("🧪 пачка оборвалась на " + n + ": " + e); }
      }); }
    // Стенд: включённые модули — «--es modules tts,ocr» (пусто — только перевод речи);
    // «--es modules show» — записать в журнал; «--es modules unused-delete» — удалить файлы выключенных.
    if (i != null && i.hasExtra("modules") && store != null) {
      String v = i.getStringExtra("modules");
      if ("show".equals(v)) log("🧩 модули сейчас: [" + Modules.join(modules) + "] · неиспользуемые файлы " + ModelStore.mb(unusedBytes()) + " МБ");
      else if ("unused-delete".equals(v)) removeUnusedModels();
      else { Set<String> want = Modules.parse(v); for (String m : Modules.CHOICE) setModule(m, want.contains(m)); }
    }
    // Стенд: офлайн-чтение набора снимков (bench/ocr) — каталог на телефоне с pNN.jpg. На каждый
    // pNN.txt (строки как на вывеске) и pNN.para (абзацы для перевода), время — в журнал и at.tsv.
    // Сверка с расшифровкой — на столе: tools/ocr_eval.py --got <каталог>.
    if (i != null && i.hasExtra("ocrbench")) { final String path = i.getStringExtra("ocrbench");
      new Thread(() -> {
        if (ocr == null || !ocr.ready()) { log("🧪 снимки: моделей чтения нет (models/ocr)"); return; }
        // .png — те же пиксели, что у эталона на столе (без разницы декодеров JPEG)
        File[] fs = new File(path).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jpg") || n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (fs == null || fs.length == 0) { log("🧪 снимки: в " + path + " нет .jpg и .png"); return; }
        Arrays.sort(fs); List<Long> all = new ArrayList<>(), det = new ArrayList<>(), rec = new ArrayList<>(), load = new ArrayList<>();
        long rss0 = Engine.rssMb(), rssMax = rss0;
        for (File f : fs) {
          try {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bm == null) { log("🧪 снимки: не прочитался " + f.getName()); continue; }
            int w = bm.getWidth(), h = bm.getHeight(); int[] px = new int[w * h]; bm.getPixels(px, 0, w, 0, 0, w, h); bm.recycle();
            long t = System.nanoTime(); Ocr.Page pg = ocr.read(px, w, h); long ms = (System.nanoTime() - t) / 1_000_000;
            rssMax = Math.max(rssMax, Engine.rssMb());
            String base = f.getName().substring(0, f.getName().length() - 4);
            StringBuilder pr = new StringBuilder(); for (OcrCore.Para p : pg.paras) pr.append(pr.length() > 0 ? "\n" : "").append(p.text);
            write(new File(f.getParentFile(), base + ".txt"), pg.lines); write(new File(f.getParentFile(), base + ".para"), pr.toString());
            OcrWords ow = ocrWords();                                     // .fixed — строки после правки слов (сверка с tools/ocr_words.py)
            if (ow != null) { StringBuilder fx = new StringBuilder(); for (String l : pg.lines.split("\n", -1)) fx.append(fx.length() > 0 ? "\n" : "").append(ow.fix(l, null));
              write(new File(f.getParentFile(), base + ".fixed"), fx.toString()); }
            all.add(ms); det.add(pg.detMs); rec.add(pg.recMs); load.add(pg.loadMs);
            tsv("ocrbench", base, "" + w, "" + h, "" + pg.boxes, "" + pg.loadMs, "" + pg.detMs, "" + pg.recMs, "" + pg.layoutMs, "" + ms);
          } catch (Throwable e) { log("🧪 снимки: " + f.getName() + " — " + e); }
        }
        log("🧪 снимки: " + all.size() + " · медиана " + med(all) + " мс (модели " + med(load) + ", детектор " + med(det) + ", распознаватель " + med(rec)
            + ") · наибольшее " + (all.isEmpty() ? 0 : Collections.max(all)) + " мс · резидентно " + rss0 + " → до " + rssMax + " МБ → " + path);
      }, "ocrbench").start(); }
    // Стенд: снимок, лежащий на телефоне, — чтение и перевод без реплики: абзацы «было → стало» в
    // журнал и <файл>.json рядом со снимком. Для сравнения перевода до и после правки, не трогая разговоры.
    if (i != null && i.hasExtra("photodry")) { final File f = new File(i.getStringExtra("photodry"));
      new Thread(() -> {
        if (ocr == null || !ocr.ready()) { log("🧪 снимок: моделей чтения нет (models/ocr)"); return; }
        long t0 = System.nanoTime();
        try {
          PhotoDone d = photoTranslate(f);
          if (d == null) return;
          long ms = (System.nanoTime() - t0) / 1_000_000; photoLog(d, ms);
          for (int k = 0; k < d.blocks.length(); k++) { org.json.JSONObject b = d.blocks.getJSONObject(k);
            log("🧪 " + b.getString("src") + " ⇒ " + b.optString("dst", "(как есть)")); }
          write(new File(f.getPath() + ".json"), new org.json.JSONObject().put("w", d.w).put("h", d.h).put("ms", ms).put("blocks", d.blocks).toString(1));
        } catch (Throwable e) { log("🧪 снимок: " + e); }
        finally { busy("live", null, 0, 0); }
      }, "photodry").start(); }
    // Стенд: снимок, лежащий на телефоне, — весь путь, как после камеры: чтение, перевод, наложение.
    if (i != null && i.hasExtra("photofile") && chats != null && !mod(Modules.OCR)) log("📷 стенд: модуль «Чтение снимков» выключен — снимок не читается");
    else if (i != null && i.hasExtra("photofile") && chats != null) {
      try {
        File src = new File(i.getStringExtra("photofile")), dst = new File(chats.photos(), "p" + System.currentTimeMillis() + ".jpg");
        chats.photos().mkdirs(); java.nio.file.Files.copy(src.toPath(), dst.toPath());
        photoRead(dst);
      } catch (Throwable e) { log("📷 стенд: " + e); }
    }
    if (i != null && i.hasExtra("rss")) {
      android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(mi);
      log("🧪 память: резидентно " + Engine.rssMb() + " МБ · PSS " + (mi.getTotalPss() >> 10) + " МБ · нативная куча " + (android.os.Debug.getNativeHeapAllocatedSize() >> 20) + " МБ");
    }
    if (i != null && i.hasExtra("llmneed")) {
      try { llmNeed = Long.parseLong(i.getStringExtra("llmneed").trim()) << 20; log("🧠 стенд: уточнителю нужно " + (llmNeed >> 20) + " МБ сверх порога системы (до перезапуска)"); }
      catch (RuntimeException e) { log("🧠 стенд: llmneed — число мегабайт, а пришло «" + i.getStringExtra("llmneed") + "»"); }
    }
    if (i != null && i.hasExtra("llmload")) {   // стенд: как сервер держит веса (mmap|none), вступает при следующем запуске уточнителя
      String m = i.getStringExtra("llmload"); getSharedPreferences("at", MODE_PRIVATE).edit().putString("llm_load", m).apply();
      log("🧠 режим загрузки уточнителя: " + m + " — перезапускаю его");
      unloadLlm("сменён режим загрузки");
    }
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
      else if ("upgrade".equals(m)) worker.submit(this::downloadUpgrade);
      else { ModelStore.Item it = store.byPath(m); if (it != null) downloadModels(Collections.singletonList(it)); else log("⬇ нет такого элемента в манифесте: " + m); }
    }
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
    // Стенд: удержание слышит запись вместо микрофона (--es micfile <wav 16 кГц>, пусто — снова
    // микрофон), с начала записи на каждом нажатии. Удержание идёт целиком по-настоящему — касание,
    // кольцо, цвет, распознавание, — только звук известный (test_mic_device.sh M6,
    // measure_live_cost.sh). Слушание при этом слышит комнату, как обычно. feedwav для этого не
    // годится: он подаёт в нарезку слушания, а удержание берёт звук прямо из потока захвата.
    if (i != null && i.hasExtra("micfile")) { String f = i.getStringExtra("micfile");
      try { micFile = f == null || f.isEmpty() ? null : new WaveReader(f).getSamples(); log("🎙 стенд: " + (micFile == null ? "снова микрофон" : "вместо микрофона будет " + new File(f).getName())); }
      catch (Throwable t) { micFile = null; log("🎙 стенд: запись не прочиталась — " + t); } }
    // Подача записанного потока комнаты вместо микрофона. Нужна потому, что комната между
    // прогонами меняется сильнее, чем настройки нарезки (SNR гулял 14–21 дБ), и сравнивать
    // параметры последовательными прогонами бессмысленно. Здесь же путь ровно тот, что у живого
    // микрофона: те же кадры, та же очередь, тот же VAD.
    if (i != null && i.hasExtra("feedwav")) {
      final String wav = i.getStringExtra("feedwav");
      final int speed = i.getStringExtra("speed") == null ? 1 : Integer.parseInt(i.getStringExtra("speed"));
      new Thread(() -> { try {
        WaveReader wr = new WaveReader(wav); float[] s2 = wr.getSamples();
        feeding = true; capQ.clear(); noiseRms = 0; vadSamples = 0; listenLiveReset = true;
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
    if (i != null && i.hasExtra("devtest") && !voice()) log("🔇 devtest: озвучка выключена или голосов нет");
    else if (i != null && i.hasExtra("devtest")) {
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
    if (i != null && i.hasExtra("eartest") && !voice()) log("🔇 eartest: озвучка выключена или голосов нет");
    else if (i != null && i.hasExtra("eartest")) {
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
    if (i != null && i.hasExtra("bttest") && !voice()) log("🔇 bttest: озвучка выключена или голосов нет");
    else if (i != null && i.hasExtra("bttest")) {
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
    // Стенд: чувствительность на время прогона — в настройки не пишется и живёт до перезапуска;
    // limiter=0 — прежний срез по ±0,99. Нужны для перебора на записях комнаты.
    if (i != null && i.hasExtra("micauto")) {
      micAuto = "1".equals(i.getStringExtra("micauto"));
      getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("micauto", micAuto).apply();
      log("🎚 чувствительность: " + (micAuto ? "авто, речь к " + (int) TARGET_DB + " dBFS" : "вручную, " + micGainDb + " дБ"));
    }
    if (i != null && i.hasExtra("micautodb")) { autoDb = clampAuto(Double.parseDouble(i.getStringExtra("micautodb"))); saveAuto(); log(String.format(Locale.ROOT, "🎚 авто-чувствительность: %+.1f дБ", autoDb)); }
    if (i != null && i.hasExtra("micgaintest")) { gainTest = Double.parseDouble(i.getStringExtra("micgaintest")); log("🎚 чувствительность на прогон: " + gainTest + " дБ"); }
    if (i != null && i.hasExtra("limiter")) { limiterOn = !"0".equals(i.getStringExtra("limiter")); log("🎚 " + (limiterOn ? "ограничитель" : "прежний срез по ±0,99")); }
    if (i != null && (i.hasExtra("micgaintest") || i.hasExtra("limiter"))) tsv("gaintest", "" + micGain(), limiterOn ? "limit" : "cut");
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
    if (i != null && i.hasExtra("fixdir")) { String d = i.getStringExtra("fixdir"); fixedDir = d == null || d.isEmpty() || "off".equals(d) ? null : d; log("направление " + (fixedDir == null ? "по реплике" : "закреплено: " + fixedDir)); }
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
    // Стенд: можно ли сейчас «Улучшить» (как решает экран, без кнопки на экране) — test_better_device.sh.
    if (i != null && i.hasExtra("betterstate")) log("🧪 «Улучшить»: " + (eng == null ? "движок не готов" : (cloudBusy ? "занята — облако работает" : lastImproved() ? "погашена" : "можно") + " · способ: " + improveMode()));
    // Стенд: сохранённые настройки, к которым скрипт вернёт приложение после проверки (test_*_device.sh).
    // Из настроек, а не из полей: до загрузки движков поля ещё не прочитаны.
    if (i != null && i.hasExtra("settings")) { android.content.SharedPreferences p = getSharedPreferences("at", MODE_PRIVATE);
      log("🧪 настройки: разбор " + p.getInt("refine_every", 3) + " · облако " + p.getInt("cloud_every", 0) + " · чтение вслух " + p.getInt("read_guard", 1)
          + " · слушаю " + (p.getBoolean("lpt", false) ? "pt" : "") + (p.getBoolean("lru", false) ? "ru" : "") + " · облако точнее " + (p.getBoolean("cloud_quality", true) ? 1 : 0)); }
    if (i != null && i.hasExtra("refineevery")) setRefineEvery(Integer.parseInt(i.getStringExtra("refineevery")));
    if (i != null && i.hasExtra("cloudevery")) setCloudEvery(Integer.parseInt(i.getStringExtra("cloudevery")));
    if (i != null && i.hasExtra("consent")) { getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("cloud_consent", "1".equals(i.getStringExtra("consent"))).apply(); log("☁ согласие на отправку разговора: " + cloudConsent()); }
    // Стендовая правка реплики без экрана: «<индекс>|<текст>» — исходник, «<индекс>|<текст>|pin» — перевод.
    if (i != null && i.hasExtra("edittext")) { String[] p = i.getStringExtra("edittext").split("\\|", 2); if (p.length == 2) reTranslate(Integer.parseInt(p[0].trim()), p[1]); }
    if (i != null && i.hasExtra("edittrans")) { final String[] p = i.getStringExtra("edittrans").split("\\|", 3); if (p.length >= 2) worker.submit(() -> fixTranslation(Integer.parseInt(p[0].trim()), p[1], p.length > 2 && p[2].contains("pin"))); }
    // Стенд: память разговора, как будто вписанная человеком; «off» — вернуть её автоматике.
    if (i != null && i.hasExtra("memo")) { String m = i.getStringExtra("memo"); setMemoByUser(m == null || "off".equals(m) ? "" : m.replace("\\n", " ")); }
    // Стенд: режим облака и порядок моделей в обоих режимах — с подсчётами, без ключа.
    if (i != null && i.hasExtra("cloudprefer")) setCloudQuality("quality".equals(i.getStringExtra("cloudprefer")));
    if (i != null && i.hasExtra("cloudroute") && cloud != null && cloud.ready) {
      boolean was = cloud.preferQuality;
      for (boolean q : new boolean[]{false, true}) {
        cloud.preferQuality = q; String[] o = cloud.order(); StringBuilder b = new StringBuilder();
        for (int k = 0; k < Math.min(8, o.length); k++) { Cloud.M m = cloud.metaOf(o[k]);
          b.append("\n   ").append(k + 1).append(". ").append(o[k].replace(":free", "")).append(" · ответов ").append(m.ok).append(", отказов ").append(m.fail)
           .append(m.ms > 0 ? String.format(Locale.ROOT, ", %.1f с", m.ms / 1000.0) : "").append(Cloud.sizeB(o[k]) > 0 ? String.format(Locale.ROOT, " · %.0f млрд", Cloud.sizeB(o[k])) : ""); }
        log("☁ порядок «" + (q ? "точнее" : "быстрее") + "» из " + o.length + ":" + b);
      }
      cloud.preferQuality = was;
    }
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
    // --es ptt 1 — как фраза кнопкой FALAR: язык по сказанному и голос в голоса разговора (Voices),
    // без касания экрана; иначе — как сегмент слушания с закреплённым направлением.
    if (i != null && i.hasExtra("testwav")) { final String wav = i.getStringExtra("testwav"), dir = i.getStringExtra("dir") == null ? "pt2ru" : i.getStringExtra("dir");
      final boolean asPtt = "1".equals(i.getStringExtra("ptt"));
      worker.submit(() -> { try { WaveReader wr = new WaveReader(wav); final float[] smp = wr.getSamples(); final int sr = wr.getSampleRate();
        if (asPtt) process(dir, smp, sr, true, voicesOn() ? new Who(spkExec.submit(() -> spk.embed(smp, sr)), false) : null);
        else process(dir, smp, sr); } catch (Throwable t) { log("Ошибка теста: " + t); } }); }
    return START_STICKY;
  }
  @Override public IBinder onBind(Intent i) { return binder; }
  /** Экран закрыли, микрофон не слушает — через минуту сервис останавливается и отдаёт модели.
   *  Минута, а не сразу: загрузка обратно занимает около 15 секунд, и вернуться в приложение
   *  спустя полминуты не должно стоить этого ожидания. Если слушает — работает дальше: это
   *  переводчик в кармане, и остановить его можно кнопкой «Стоп» в уведомлении. */
  static final long IDLE_STOP_MS = 60_000, IDLE_STOP_SCREEN_OFF_MS = 600_000;
  /** Виден ли экран приложения. Держится по onStart/onStop экрана, а не по его уничтожению: с
   *  Android 12 «назад» на главном экране приложение не закрывает, а сворачивает, и экран не
   *  уничтожается почти никогда — выгрузка, завязанная на это, не наступала. */
  volatile boolean uiVisible = false;
  public void setUiVisible(boolean v) { uiVisible = v; if (v) main.removeCallbacks(idleStop); else scheduleIdleStop(); }
  final Runnable idleStop = () -> {
    if (uiVisible || vadMode || (store != null && store.running()) || updateBusy || cloudBusy) return;
    log("💤 приложение свёрнуто, микрофон выключен — останавливаюсь и отдаю память");
    tsv("idle_stop");
    exitAfterStop = true;
    stopForeground(true); stopSelf();
  };
  /** Завершить процесс после остановки. Освобождённые модели распределитель памяти системе не
   *  возвращает: на Redmi после выгрузки оставалось 186 МБ в памяти и ещё 543 МБ нативной кучи в
   *  сжатой подкачке, а попросить распределитель отдать их из Java нельзя. Всё состояние уже на
   *  диске, экрана нет — процессу незачем жить. Только для ухода по бездействию и смахивания, не
   *  для кнопки «Стоп»: экран в этот момент может быть открыт. */
  volatile boolean exitAfterStop = false;
  /** Свернули — минута; погас экран — десять минут: пауза между фразами с погасшим экраном не
   *  должна стоить пятнадцати секунд перезагрузки моделей. Слушает — не выгружаемся вовсе. */
  void scheduleIdleStop() {
    main.removeCallbacks(idleStop);
    if (uiVisible || vadMode) return;
    boolean screenOn = true;
    try { screenOn = getSystemService(PowerManager.class).isInteractive(); } catch (Throwable ignore) {}
    main.postDelayed(idleStop, screenOn ? IDLE_STOP_MS : IDLE_STOP_SCREEN_OFF_MS);
  }
  public void setListener(Listener l) {
    listener = l; if (l != null && eng != null) main.post(l::onReady);
    setUiVisible(l != null);
    // Экран мог подключиться после проверки моделей при старте — отдаём ему итог сразу.
    if (l != null && store != null) { ModelStore.State st = store.state(); main.post(() -> l.onModels(st)); }
    if (l != null) { String u = updateState; main.post(() -> l.onUpdate(u)); }
    if (l != null && lastStatus != null) { String st = lastStatus; main.post(() -> l.onStatus(st)); }
    pushBusy();
  }

  // ---- что приложение делает прямо сейчас: полоса на экране разговора
  /** Виды работы по важности: живой перевод, уточнитель, облако, модели. Экран показывает
   *  главную из идущих — живой перевод важнее фонового уточнения, уточнение важнее облака.
   *  Раньше снаружи не было видно ничего: сырой перевод появлялся, а потом через десяток секунд
   *  молча менялся на уточнённый — или не менялся, и было не понять, ждать ли. */
  static final String[] BUSY_ORDER = {"load", "live", "refine", "cloud", "models"};
  final Map<String, Object[]> busyNow = new java.util.concurrent.ConcurrentHashMap<>();   // вид → {подпись, сделано, всего}
  void busy(String kind, String what, int done, int total) {
    if (what == null) { if (busyNow.remove(kind) == null) return; }
    else busyNow.put(kind, new Object[]{what, done, total});
    // В машинный журнал: по нему видно, сколько длится каждая стадия, и стенд сверяет порядок.
    if (!"models".equals(kind)) tsv("busy", kind, what == null ? "-" : what, "" + done, "" + total);
    pushBusy();
  }
  void pushBusy() {
    final Listener l = listener; if (l == null) return;
    Object[] top = null; String kind = null;
    for (String k : BUSY_ORDER) { top = busyNow.get(k); if (top != null) { kind = k; break; } }
    final Object[] t = top; final String kd = kind;
    main.post(() -> { if (t == null) l.onBusy(null, null, 0, 0); else l.onBusy(kd, (String) t[0], (Integer) t[1], (Integer) t[2]); });
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
  public void setVad(boolean on) { vadMode = on; if (!on) scheduleIdleStop();
    getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("vad", on).apply();   // START_STICKY поднимает сервис с vadMode=false
    vadReset = true;                                    // сделает поток нарезки перед следующим кадром
    if (on) listenLiveReset = true;
    if (on && !capturing) { capturing = true; startCapture(); }
    else if (!on) capturing = false;                       // поток сам выйдет и отпустит микрофон
    notify(on ? "Слушаю…" : "Микрофон выключен"); }
  public void pttStart(String dir) {
    synchronized (pttBuf) { pttBuf.clear(); }
    // Фон комнаты от слушания — начало своего фона удержания; у записи стенда фон свой, не комнаты.
    micFilePos = 0; pttRoomDb = micFile != null ? Double.NaN : room();
    pttLive.reset(); pttLive.seed(pttRoomDb); liveQ = -1; liveSpeech = false; levelDb = -120;
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
    // Чувствительность с ограничителем — на всю фразу разом, тем же Gain, что у прослушивания;
    // в авто — ровно столько, чтобы речь этой фразы легла на TARGET_DB.
    final Gain g = new Gain(16000); g.limit = limiterOn; final double bg = pttRoomDb;
    if (autoOn()) { double sp = Gain.speechFloor(all, bg)[0]; g.db = Double.isNaN(sp) ? 0 : clampAuto(TARGET_DB - sp); } else g.db = micGain();
    final Gain.Stats st = new Gain.Stats(); g.apply(all, all.length, st);
    // Фраза кнопкой — голос того, кто её сказал (Voices): отпечаток считается рядом с распознаванием,
    // а в голоса разговора ложится, когда реплика принята. Тот же звук после усиления, что слышит и
    // слушание: слепок и сегменты сравниваются в одном тракте.
    final Who who = voicesOn() ? new Who(spkExec.submit(() -> spk.embed(all, 16000)), false) : null;
    // Удержание всегда определяет язык по сказанному. Раньше здесь вызывался вариант, берущий
    // режим из полей: при выключенных кнопках слушания autoLang=false, направление оставалось
    // ru2pt, и сказанное по-португальски отбивал языковой фильтр — «не похоже на русский».
    worker.submit(() -> { hear(Gain.speechFloor(all, bg + g.db), st, g.db); segDb = db(rms(all, all.length)); segNoiseDb = segFloorDb; process(d, all, 16000, true, who); });
  }

  /** Удержание, каждый кадр: уровень для кольца (с учётом ручного усиления) и как слышно (liveQ) —
   *  по речи с начала удержания: её уровню, с которым она дойдёт до распознавания (в авто — после
   *  подстройки к TARGET_DB), и запасу над фоном. */
  void level(float[] c, int n, double gainDb) {
    float peak = 0; for (int k = 0; k < n; k++) peak = Math.max(peak, Math.abs(c[k]));
    double r = db(rms(c, n));
    levelDb = (float) Math.max(Math.min(0, r + gainDb), levelDb - 1.5); levelOver = peak >= Gain.OVER; levelAt = System.currentTimeMillis();
    pttLive.frame(r);
    double s = pttLive.speechDb();
    liveQ = (float) pttLive.q(autoOn() ? s + clampAuto(TARGET_DB - s) : s + gainDb);
    liveSpeech = pttLive.voiced();
  }

  /** Как слышно фразу: речь и фон по ней самой (sf — Gain.speechFloor), перегруз входа и работа
   *  ограничителя — в машинный журнал at.tsv и строкой в журнал. На экране слов нет: там цвет. */
  void hear(double[] sf, Gain.Stats st, double gainDb) {
    segGainDb = gainDb; segSpeechDb = sf[0]; segFloorDb = sf[1];
    segOverPct = st.overPct(); segLimPct = st.limitedPct(); segCutPct = st.cutPct();
    Hearing h = Hearing.of(sf[0], sf[1], segOverPct, gainDb, autoOn());
    log("🎚 " + h.text + (Double.isNaN(sf[0]) ? "" : String.format(Locale.ROOT, " · речь %.0f, фон %.0f dBFS, усиление %+.0f дБ%s",
        sf[0], sf[1], gainDb, segLimPct > 0 ? String.format(Locale.ROOT, ", ограничитель %.2f %%", segLimPct) : "")));
  }
  volatile String pttFixed = null; volatile boolean pttAuto = true;
  /** Отпечатки голоса — своим потоком с фоновым приоритетом и одним ядром ORT (Speaker), рядом с
   *  распознаванием: перевод главнее (владелец 01.10: «флоу скорости не должен нас покидать»). Журнал
   *  испытания владельца 01.10 (два человека, 26 реплик, 5 облачных пересмотров): слушание ждало
   *  отпечаток 0 мс на каждой фразе, отпечаток — 0,3–1,4 с. Поток один, задачи — по очереди: голос фразы
   *  кнопкой (enrollLater) ждёт свой отпечаток, поставленный в очередь раньше, и дождётся его. */
  final ExecutorService spkExec = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignore) {}
    r.run(); }, "voiceprint"));
  /** Сколько слушание ждёт отпечаток после распознавания. Обычно он готов раньше (на стенде ждал 0 мс);
   *  не успел — фраза переводится без номера голоса, а не теряется: разговор важнее фильтра. */
  static final long PRINT_WAIT_MS = 15_000;
  /** Отпечаток фразы, который считается рядом с распознаванием. listen — сегмент слушания: решение
   *  «голос разговора или чужой» принимается после распознавания и до перевода (processText);
   *  иначе — фраза кнопкой FALAR: её голос ложится в разговор уже после того, как перевод на экране и
   *  звучит (enrollLater), — шум, отбитый проверкой языка, голосом не становится. */
  static final class Who {
    final java.util.concurrent.Future<float[]> print; final boolean listen; final long t0 = System.nanoTime();
    /** Звук сегмента слушания — для окон, если в нём двое подряд. */
    final float[] seg;
    /** Голос уже решён: кусок сегмента, разрезанного по голосам (splitSeg). */
    final String n;
    Who(java.util.concurrent.Future<float[]> print, boolean listen) { this(print, listen, null); }
    Who(java.util.concurrent.Future<float[]> print, boolean listen, float[] seg) { this.print = print; this.listen = listen; this.seg = seg; n = null; }
    Who(String n) { print = null; listen = true; seg = null; this.n = n; }
  }
  /** Голоса разговора работают: модуль включён, модель поднята, разговоры открыты. */
  boolean voicesOn() { Speaker s = spk; return s != null && s.ready && chats != null; }
  /** Строка журнала о голосе фразы: новый он или узнан, и с каким косинусом. */
  static String voiceNote(Voices.Voice v, Voices.Match m, boolean isNew) {
    if (isNew) return "новый голос: " + Voices.label(v) + " (" + v.lang + ")"
        + (m.v == null ? "" : String.format(Locale.ROOT, " · ближе всех «%s» — %.2f", Voices.label(m.v), m.score));
    return String.format(Locale.ROOT, "голос узнан: %s · %.2f · фраз в слепке %d", Voices.label(v), m.score, v.k);
  }
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
  void resetPassCounters() { sinceLocal = 0; sinceCloud = 0; cloudBackoff = 1; cloudSkipLogged = false; lastLocalAt = 0; lastAt = 0; lastCloudAt = 0; }

  /** Забыть голоса текущего разговора: слепок — биометрия человека, и стереть его можно всегда. */
  public int forgetVoices() {
    int n = chats == null ? 0 : chats.clearVoices();
    log("🎤 голоса разговора забыты: " + n + " — слушание снова ждёт фразу кнопкой FALAR");
    Listener l = listener; if (l != null) main.post(l::onHistory);
    return n;
  }
  /** Имя голоса от человека: автоматика его больше не меняет; пустое — снова «собеседник N». */
  public boolean nameVoice(int n, String name) {
    if (chats == null || !chats.nameVoice(n, name, "user")) return false;
    log("🎤 собеседник " + n + (name == null || name.trim().isEmpty() ? " — снова без имени" : " — «" + name.trim() + "»"));
    Listener l = listener; if (l != null) main.post(l::onHistory);
    return true;
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
  /** Знаков на токен — на образцах обоих языков, берётся меньшее: русский дробится мельче.
   *  Бюджет контекста считается от этого числа, а не от догадки. */
  static final String CPT_PT = "Olha, o carro chegou ontem com um barulho estranho na frente, e quando a gente levantou vimos que a correia dentada estava muito gasta. Se ela arrebentar com o motor ligado, o conserto fica muito mais caro, entao a recomendacao e trocar agora mesmo.";
  static final String CPT_RU = "Хорошо, я понял про ремень. Скажите, а насколько это срочно? У меня в субботу поездка за город, почти четыреста километров в одну сторону, и мне совсем не хочется, чтобы машина встала посреди трассы.";
  void measureCpt(Llm l) {
    try {
      double pt = Brief.measured(CPT_PT.length(), l.tokens(CPT_PT)), ru = Brief.measured(CPT_RU.length(), l.tokens(CPT_RU));
      llmCpt = Math.min(pt, ru);
      log(String.format(Locale.ROOT, "🧠 токенизатор: %.2f знака на токен по-португальски, %.2f по-русски — фон до %d знаков",
          pt, ru, Math.min(Brief.FRESH_CHARS, Brief.budget(llmCpt, 0, 0, 0))));
    } catch (Throwable e) { log("🧠 токенизатор не ответил, считаю с запасом: " + e); }
  }
  /** Фон для уточнителя: реплики из рабочей истории, кроме разбираемой, на языке исходника,
   *  от старых к новым. Отбор по бюджету — в Brief. */
  List<String> backgroundLines(Turn[] h, int idx) {
    String src = h[idx].dir.substring(0, 2); List<String> r = new ArrayList<>();
    for (int i = 0; i < h.length; i++) { if (i == idx) continue; String text = h[i].dir.substring(0, 2).equals(src) ? h[i].asr : (h[i].refined != null ? h[i].refined : h[i].mt); if (text != null && !text.isEmpty()) r.add(text); }
    return r;
  }

  // ---------- модули ----------

  static final String PREF_MODULES = "modules";
  /** Включённые модули (Modules). Выбор человека при установке или в «Системе» → «Модули». */
  public volatile Set<String> modules = new LinkedHashSet<>();
  /** Выбор уже сделан (или взят «как было» у обновившегося); до него — умолчания под телефон. */
  public volatile boolean modulesChosen = false;
  public boolean mod(String m) { return modules.contains(m); }
  /** Облако пригодно: модуль включён и ключ есть. */
  public boolean cloudReady() { return mod(Modules.CLOUD) && cloud != null && cloud.ready; }
  /** Голос есть: модуль «Озвучка» включён и голоса подняты. */
  public boolean voice() { Engine e = eng; return mod(Modules.TTS) && e != null && e.hasTts(); }
  long totalRam() {
    try { ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); getSystemService(ActivityManager.class).getMemoryInfo(mi); return mi.totalMem; }
    catch (Throwable t) { return 0; }
  }
  String modulesLine() {
    StringBuilder b = new StringBuilder();
    for (String m : Modules.CHOICE) if (mod(m)) b.append(b.length() > 0 ? ", " : "").append(Modules.title(m));
    return b.length() == 0 ? "только перевод речи" : b.toString();
  }
  /** Что докачивается — по модулям: «📷 Чтение снимков 17,8 МБ, 🔊 Озвучка перевода 134,8 МБ». */
  String whatFetch(List<ModelStore.Item> items) {
    Map<String, Long> by = new LinkedHashMap<>();
    for (ModelStore.Item it : items) by.merge(it.module, it.size, Long::sum);
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, Long> e : by.entrySet()) b.append(b.length() > 0 ? ", " : "").append(Modules.title(e.getKey())).append(' ').append(ModelStore.mb(e.getValue())).append(" МБ");
    return b.toString();
  }
  void setModules(Set<String> s, String why) {
    modules = new LinkedHashSet<>(s); store.modules = modules; modulesChosen = true; autoFetchTried = false;
    getSharedPreferences("at", MODE_PRIVATE).edit().putString(PREF_MODULES, Modules.join(modules)).apply();
    log("🧩 модули (" + why + "): " + modulesLine());
    worker.submit(store::summarize);                   // сверка может читать файлы — не на экранном потоке
  }

  /** Экран первого запуска: выбранные модули — и одной загрузкой обязательное и их файлы. */
  public void setupModules(Set<String> chosen) {
    setModules(chosen, "выбор при установке");
    worker.submit(() -> {                              // план сверяет лежащее — не на экранном потоке
      List<ModelStore.Item> need = store.need(modules);
      if (need.isEmpty()) { loadAll(); return; }
      boolean ok = store.start(need, "core");
      log(ok ? "⬇ загрузка при установке: " + ModelStore.mb(sizeOf(need)) + " МБ — " + need.size() + " файлов" : "⬇ загрузка уже идёт");
    });
  }
  static long sizeOf(List<ModelStore.Item> l) { long b = 0; for (ModelStore.Item it : l) b += it.size; return b; }

  /** Включить или выключить модуль. Выключение только прячет модуль и отдаёт его память — файлы
   *  остаются (удаляются отдельно: removeUnusedModels). Включение подключает то, что уже скачано,
   *  и сразу докачивает недостающее. */
  public void setModule(String m, boolean on) {
    if (!Modules.CHOICE.contains(m) || mod(m) == on) return;
    Set<String> s = new LinkedHashSet<>(modules); if (on) s.add(m); else s.remove(m);
    setModules(s, (on ? "включён " : "выключен ") + Modules.title(m));
    worker.submit(() -> {
      try {
        if (on) {
          activate(m);
          List<ModelStore.Item> a = store.autos();
          if (!a.isEmpty() && !store.running()) { autoFetchTried = true; log("📦 докачиваю: " + whatFetch(a)); downloadModels(a, ModelStore.AUTO); }
        } else deactivate(m);
      } catch (Throwable t) { log("🧩 " + Modules.title(m) + ": " + t); }
      ModelStore.State st = store.state(); Listener l = listener;
      if (l != null) main.post(() -> { l.onModels(st); l.onModules(); });
    });
  }
  /** Подключить модуль, чьи файлы уже на месте. Облако и снимки — только проверка mod() на месте. */
  void activate(String m) {
    if (eng == null) return;
    switch (m) {
      case Modules.TTS: if (!eng.hasTts() && eng.loadTts()) log("🔊 озвучка подключена"); break;
      case Modules.SPEAKER: if (spk == null || !spk.ready) { spk = new Speaker(modelsDir, true); if (spk.ready) log("🎤 отпечаток голоса подключён · голосов в разговоре: " + (chats == null ? 0 : chats.voices.size())); } break;
      case Modules.CORPUS: if (pb != null && pb.minedCount == 0 && new File(modelsDir, "phrasebook_tatoeba.tsv").exists()) pb.loadMined(new File(modelsDir, "phrasebook_tatoeba.tsv")); break;
      case Modules.LLM:   // уточнитель на месте — контекст включается, если человек не выключал его сам
        if (contextMode || !hasLlm()) break;           // уже включён — второй строки в журнале не нужно
        if (!getSharedPreferences("at", MODE_PRIVATE).contains("ctx")) { log("🧠 уточнитель на месте — включаю контекст"); setContext(true, false); }
        else if (getSharedPreferences("at", MODE_PRIVATE).getBoolean("ctx", false)) setContext(true, false);
        break;
      default: break;
    }
  }
  /** Отдать память модуля: голоса, уточнитель, отпечаток голоса, корпус. Выбор человека
   *  о контексте («ctx») не трогается: включат модуль обратно — вернётся как был. */
  void deactivate(String m) {
    switch (m) {
      case Modules.TTS: sayQ.clear(); synchronized (tts) { if (eng != null) eng.releaseTts(); } log("🔇 озвучка выключена: перевод только на экране"); break;
      case Modules.LLM: contextMode = false; unloadLlm("модуль выключен"); break;
      case Modules.SPEAKER: { Speaker o = spk; spk = new Speaker(modelsDir, false); if (o != null) spkExec.submit(o::release); break; }
      case Modules.CORPUS: if (pb != null) pb.dropMined(); break;
      default: break;
    }
  }
  /** Сколько занимают на телефоне файлы выключенных модулей, байт. */
  public long unusedBytes() {
    long b = 0;
    for (String m : Modules.CHOICE) if (!mod(m)) for (ModelStore.Item it : store.items) if (m.equals(it.module)) b += onDisk(it);
    return b;
  }
  long onDisk(ModelStore.Item it) {
    if (!it.archive) { File f = new File(modelsDir, it.path); return f.isFile() ? f.length() : 0; }
    long b = 0; for (String[] c : it.check) { File f = new File(modelsDir, c[0]); if (f.isFile()) b += f.length(); } return b;
  }
  /** «Удалить неиспользуемые модели»: файлы выключенных модулей. Отдельной кнопкой, а не при
   *  выключении — решение владельца 28.09: выключенный модуль включается обратно без загрузки. */
  public void removeUnusedModels() {
    worker.submit(() -> {
      if (store.running()) { log("🧩 идёт загрузка моделей — удаление после неё"); return; }
      long freed = 0;
      for (String m : Modules.CHOICE) if (!mod(m)) freed += store.remove(m);
      log("🧩 удалены модели выключенных модулей: освобождено " + ModelStore.mb(freed) + " МБ");
      ModelStore.State st = store.state(); Listener l = listener;
      if (l != null) main.post(() -> { l.onModels(st); l.onModules(); });
    });
  }

  /** Файл уточнителя на месте. */
  public boolean hasLlm() {
    if (!mod(Modules.LLM)) return false;                  // модуль выключен — уточнителя для приложения нет
    File[] gg = new File(getExternalFilesDir(null), "models/llm").listFiles((d, n) -> n.endsWith(".gguf"));
    return gg != null && gg.length > 0;
  }
  public void setContext(boolean on) { setContext(on, true); }
  /** remember=false — включение по умолчанию, а не выбор человека: в настройках ничего не пишем,
   *  чтобы «само включилось» не превратилось в «человек включил» и выключение осталось за ним. */
  public void setContext(boolean on, boolean remember) {
    if (remember) getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("ctx", on).apply();
    if (on && !hasLlm()) { contextMode = false; log(mod(Modules.LLM) ? "🧠 контекст выключен: файла уточнителя ещё нет — он докачивается сам" : "🧠 контекст выключен: модуль «Уточнитель» выключен («Система» → «Модули»)"); return; }
    contextMode = on;
    if (!on) { unloadLlm("контекст выключен"); log("🧠 контекст выключен"); return; }
    log("🧠 контекст включён — уточнитель поднимается на время разбора и уходит через " + LLM_IDLE_MS / 1000 + " с простоя");
  }

  /** Уточнитель по требованию. Держать его постоянно на телефоне с 8 ГБ нельзя: приложение с ним
   *  занимает 3,6 ГБ, и через 5–35 с после загрузки система каждый раз присылала критический сигнал
   *  памяти — при загрузке и обычной, и отображением файла (results/2026-09-28-memory.md). Так
   *  «тихо падали»: система убивала процессы без всякой ошибки. Теперь сервер поднимается перед
   *  проходом разбора, серия проходов подряд пользуется им же, а через 45 с простоя он выгружается. */
  static final long LLM_IDLE_MS = 45_000;
  /** Сколько памяти нужно сверх системного порога, чтобы поднять сервер: 1,25–1,5 ГБ по замеру. */
  static final long LLM_NEED = 1500L << 20;
  /** Порог в работе. Меняется только стендом (--es llmneed <МБ>) и живёт до перезапуска: чтобы
   *  проверить разбор на телефоне, где ядро и уточнитель вместе не помещаются. */
  volatile long llmNeed = LLM_NEED;
  final Runnable llmIdleUnload = () -> { if (!refineRunning) unloadLlm("простой " + LLM_IDLE_MS / 1000 + " с"); };
  void unloadLlm(String why) {
    main.removeCallbacks(llmIdleUnload);
    Llm l = llm; llm = null;
    if (l != null) { llmWorker.submit(l::stop); log("🧠 уточнитель выгружен: " + why); tsv("llm_unload", why); }
  }
  /** Хватит ли памяти поднять сервер, не загнав телефон в критическое состояние. Цифры системы
   *  пишутся в журнал при каждом решении: порог выведен из замера, и его нужно видеть. */
  boolean roomForLlm() {
    try {
      ActivityManager am = getSystemService(ActivityManager.class);
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
      long spare = mi.availMem - mi.threshold;
      boolean ok = !mi.lowMemory && spare > llmNeed;
      tsv("llm_room", "" + (mi.availMem >> 20), "" + (mi.threshold >> 20), mi.lowMemory ? "low" : "", ok ? "ok" : "no");
      if (!ok) log("🧠 разбор отложен: свободно " + (mi.availMem >> 20) + " МБ при пороге системы " + (mi.threshold >> 20) + " МБ — уточнителю нужно ещё " + (llmNeed >> 20) + " МБ сверху");
      return ok;
    } catch (Throwable e) { return true; }
  }
  /** Поднять сервер, если его нет. Только на llmWorker. false — не поднят (нет памяти, файла, ошибка). */
  boolean ensureLlm() {
    if (!mod(Modules.LLM)) return false;
    if (llm != null && llm.ready) return true;
    if (!roomForLlm()) return false;
    try {
      File ld = new File(getExternalFilesDir(null), "models/llm"); File[] gg = ld.listFiles((d, n) -> n.endsWith(".gguf")); if (gg == null || gg.length == 0) { log("🧠 уточнителя нет — скачайте его в «Системе»"); return false; }
      File pick = gg[0]; for (File f : gg) if (f.getName().toLowerCase().contains("hy-mt")) pick = f;
      List<Integer> stray = Llm.strays();
      if (!stray.isEmpty()) {
        for (int pid : stray) android.os.Process.killProcess(pid);
        log("🧠 убил оставшийся от прошлого запуска llama-server (" + stray.size() + ") — он держал память и порт");
        Thread.sleep(500);
      }
      log("🧠 поднимаю уточнитель: " + pick.getName() + " …"); long t = System.nanoTime();
      Llm l = new Llm(getApplicationInfo().nativeLibraryDir, pick.getAbsolutePath(), new File(getExternalFilesDir(null), "llama-server.log").getAbsolutePath());
      l.loadMode = getSharedPreferences("at", MODE_PRIVATE).getString("llm_load", "mmap");
      if (!l.start(4)) { log("🧠 уточнитель не поднялся — подробности в llama-server.log"); return false; }
      llm = l; llmLoadedAt = android.os.SystemClock.elapsedRealtime(); log("🧠 LLM готов за " + (System.nanoTime() - t) / 1000000 + " мс");
      measureCpt(l);
      return true;
    } catch (Throwable e) { Log.e(TAG, "llm", e); log("🧠 ошибка LLM: " + e); return false; }
  }
  /** Состояние контекста при запуске. Раньше оно нигде не сохранялось, и переключатель каждый
   *  раз начинался выключенным — снаружи это выглядело как «само отключается». Выбор человека
   *  запоминается; если выбора не было, контекст включён, когда уточнитель скачан. */
  void restoreContext() {
    android.content.SharedPreferences pr = getSharedPreferences("at", MODE_PRIVATE);
    boolean has = hasLlm();
    if (!pr.contains("ctx")) {
      if (has) { log("🧠 уточнитель на месте — контекст включён"); setContext(true, false); }
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
    if (contextMode && refineEvery > 0 && sinceLocal >= refineEvery) { sinceLocal = 0; kickLocal(); }
    if (cloudEvery > 0 && sinceCloud >= cloudEvery * cloudBackoff) {
      if (!cloudReady() || !cloudConsent()) {
        if (!cloudSkipLogged) { cloudSkipLogged = true; log("☁ автоматический пересмотр пропущен: " + (!mod(Modules.CLOUD) ? "модуль «Облако» выключен" : !cloudReady() ? "нет ключа" : "нет согласия на отправку разговора")); }
      } else if (!online()) {
        if (!cloudSkipLogged) { cloudSkipLogged = true; log("☁ автоматический пересмотр пропущен: нет сети — догонит, когда сеть вернётся"); }
      } else { cloudSkipLogged = false; sinceCloud = 0; cloudReview(false); }
    }
  }
  void kickLocal() {
    if (!contextMode) return;
    refinePending = true; if (!refineRunning) llmWorker.submit(this::refineLoop);   // сервер поднимет сам проход
  }
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
    vadReset = true;                                    // сделает поток нарезки перед следующим кадром
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
    vadReset = true;                                    // сделает поток нарезки перед следующим кадром
    log("↩ разговор " + id + " продолжен, восстановлено реплик: " + history.size()
        + (chats.name.isEmpty() ? "" : " · «" + chats.name + "»"));
    if (left != 0) llmWorker.submit(() -> refineSession(left));
  }

  /** Назвать разговор по содержанию. Список без названий бесполезен: по первой фразе человека
   *  не узнать. Считает бесплатная модель OpenRouter — локальная для этого не нужна, а сеть
   *  здесь необязательна: не вышло, останется имя, введённое руками. */
  public void describeChat(long id) {
    if (chats == null || !cloudReady()) return;
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
      if (x == null || x.has("photo")) continue;        // снимок — не диалог, и согласия на его отправку не было
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

  /** Добавить ключ OpenRouter: он проверяется у OpenRouter и встаёт запасным, если рабочий ключ уже
   *  есть. Пустая строка убирает все ключи. */
  public String setCloudKey(String k) {
    if (cloud == null) return "движок не готов";
    String r = cloud.configure(k);
    log("☁ " + r); return r;
  }
  /** Убрать один ключ — кнопкой у самого ключа в «Облаке». */
  public String removeCloudKey(String k) {
    if (cloud == null) return "движок не готов";
    String r = cloud.removeKey(k);
    log("☁ " + r); return r;
  }

  /** Проход улучшения по разговору, из которого ушли. Смысл: первая реплика переводилась вслепую,
   *  а теперь разговор дочитан целиком — видно, о чём шла речь. Идёт в фоне и никуда не спешит;
   *  результат подхватится, когда к этому человеку вернутся. */
  void refineSession(long id) {
    if (!contextMode || !ensureLlm()) { log("🧠 улучшение разговора " + id + " пропущено: уточнитель не поднят"); describeChat(id); return; }
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
    // Всё, что старше последних 1200 знаков, уточнитель знает только из памяти разговора.
    List<String[]> rows = new ArrayList<>();
    for (int k = 0; k < t.length(); k++) { org.json.JSONObject x = t.optJSONObject(k); if (x != null) rows.add(Chats.row(x, k)); }
    String mb = Memo.block(Memo.who(rows), o.optString("memo", ""), o.optString("topic", ""), Memo.CAP);
    if (!mb.isEmpty()) ctx = mb + "\n" + ctx;
    for (int k = 0; k < t.length() && running; k++) {
      busy("refine", "улучшаю прошлый разговор", k, t.length());
      org.json.JSONObject x = t.optJSONObject(k);
      if (x == null) continue;
      String src = x.optString("src", ""), was = x.optString("dst", ""), dirn = x.optString("dir", "pt2ru");
      if (src.isEmpty()) continue;
      try {
        String out = llm.chat(null, hyPrompt(ctx, src, dirn.substring(3), terms(dirn, src)), 200).trim();
        if (!out.isEmpty() && !out.equals(was)) { x.put("fixed", out); changed++; }
      } catch (Throwable e) { log("🧠 улучшение оборвалось на реплике " + k + ": " + e); break; }
    }
    busy("refine", null, 0, 0);
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
    return learn == null ? "" : learn.keywords(chats.dialog(), 6);
  }
  /** Память разговора для фона уточнителя: кто говорит (по грамматике исходных реплик), ключевые
   *  детали (от облака или человека) и тема. Всё, что старше свежего куска, уточнитель знает
   *  только отсюда. Считается раз на проход разбора. */
  volatile String lastMemoLogged = "";
  String memoBlock(String topic) {
    if (chats == null) return topic.isEmpty() ? "" : "Tema: " + topic;
    return Memo.block(Memo.who(chats.dialog()), chats.memo, topic, Memo.CAP);
  }
  public String whoLine() { return chats == null ? "" : Memo.who(chats.dialog()); }
  /** Кто говорил — для облака: имя голоса и его номер или «speaker N»; без голоса — пусто. */
  String cloudWho(String w) {
    if (Voices.OWNER.equals(w)) return "phone owner";
    if (w == null || !w.matches("\\d{1,4}")) return "";
    Voices.Voice v = chats == null ? null : chats.voices.get(w);
    return v != null && !v.name.isEmpty() ? v.name + ", speaker " + w : "speaker " + w;
  }
  /** Память, вписанная человеком: автоматика её больше не перезаписывает. Пустая — вернуть автоматике. */
  public boolean setMemoByUser(String text) {
    if (chats == null) return false;
    boolean ok = chats.setMemo(text, Chats.BY_USER);
    String t = text == null ? "" : text.trim();
    log(t.isEmpty() ? "🧠 память разговора возвращена автоматике" : "🧠 память разговора записана вами (" + t.length() + " зн.) — облако и уточнитель её не перезапишут");
    return ok;
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
    main.removeCallbacks(llmIdleUnload);
    try {
      // Памяти нет — проход отложен до следующей реплики. Флаг ожидания снимаем, иначе finally
      // перезапускал бы проход по кругу, пока память не освободится.
      if (llm == null || !llm.ready) busy("refine", "поднимаю уточнитель…", 0, 0);
      if (!ensureLlm()) { refinePending = false; return; }
      while (refinePending) { refinePending = false;
      Turn[] h; synchronized (history) { h = history.toArray(new Turn[0]); } if (h.length == 0) return;
      final long chatId = chats == null ? 0 : chats.current;   // тема и пары должны лечь в тот разговор, который разбирали
      List<Turn> todo = new ArrayList<>();
      for (Turn t : h) if (t.at > lastLocalAt) todo.add(t);
      if (lastLocalAt == 0 && todo.size() > 2) todo = new ArrayList<>(todo.subList(todo.size() - 2, todo.size()));   // первый проход: как раньше, две последние
      if (todo.size() > 5) todo = new ArrayList<>(todo.subList(todo.size() - 5, todo.size()));
      // Правленное человеком не разбираем вовсе: менять его всё равно нельзя, а разбор — это
      // 10–20 секунд процессора и поднятый уточнитель.
      if (chats != null) todo.removeIf(t -> chats.humanAt(t.at));
      if (todo.isEmpty()) continue;
      boolean hy = llm.model.toLowerCase().contains("hy-mt");
      String topic = topicLine(), memo = memoBlock(topic);
      // Что уточнитель знает о разговоре сверх свежих реплик — в журнал, когда это меняется: иначе
      // не понять, почему перевод вышел таким, а смотреть «Память разговора» посреди разговора некогда.
      if (!memo.equals(lastMemoLogged)) { lastMemoLogged = memo; log("🧠 память для уточнителя: " + (memo.isEmpty() ? "пусто" : memo.replace('\n', ' ')) + " (" + memo.length() + " зн.)"); }
      int changed = 0, pairs = 0; long t0 = System.nanoTime(); int from = Math.max(0, h.length - 8);
      if (hy) {
        int k = 0;
        for (Turn L : todo) {
          busy("refine", "уточняю перевод", k++, todo.size());
          int idx = indexOf(h, L); if (idx < 0) continue;
          String tgt = L.dir.substring(3);
          String tm = terms(L.dir, L.asr), tp = memo.isEmpty() ? "" : memo + "\n";
          // Бюджет из окна модели: без него длинные реплики переполняли окно, сервер отказывал,
          // и уточнитель замолкал навсегда. Старое — в памяти разговора, свежее — в пределах бюджета.
          int budget = Brief.budget(llmCpt, L.asr.length(), tm.length(), tp.length());
          if (budget < 0) { log("🔁 #" + L.n + " длиннее окна уточнителя (" + L.asr.length() + " знаков) — оставляю перевод как есть"); continue; }
          List<String> fresh = Brief.fit(backgroundLines(h, idx), Math.min(budget, Brief.FRESH_CHARS));
          String bg = tp + String.join("\n", fresh);
          long t = System.nanoTime();
          try {
            String out = llm.chat(null, hyPrompt(bg, L.asr, tgt, tm), 200).trim();
            long ms = (System.nanoTime() - t) / 1000000;
            if (tgt.equals("pt")) out = TextRules.toBrazilian(out);
            if (applyFix(L, out, Chats.BY_LLM)) { changed++; log("🔁 #" + L.n + " по контексту (" + ms + " мс, фон " + bg.length() + " зн.): " + out); }
            else log("🔁 #" + L.n + " контекст не изменил перевод (" + ms + " мс, фон " + bg.length() + " зн.)");
          } catch (Exception e) {
            // Одна неудачная реплика не обрывает проход: раньше исключение выбрасывало весь цикл,
            // метка «разобрано до» не сдвигалась, и следующий проход падал на том же месте.
            log("🔁 #" + L.n + " уточнитель не ответил: " + e.getMessage());
          }
        }
      } else {
        busy("refine", "уточняю перевод…", 0, 0);
        StringBuilder u = new StringBuilder(); Map<Integer, Turn> byN = new HashMap<>();
        // Та же защита окна для общей модели: реплики с конца, пока влезают в бюджет.
        String had = chats == null ? "" : chats.memo;
        int budget = Math.min(Brief.budget(llmCpt, 0, 0, topic.length() + had.length() + Cloud.REVIEW_SYS.length()), Brief.FRESH_CHARS * 2);
        int start = h.length, used = 0;
        while (start > 0) { Turn x = h[start - 1]; int len = x.asr.length() + (x.refined != null ? x.refined : x.mt).length() + 20; if (used + len > budget && start < h.length) break; used += len; start--; }
        from = Math.max(from, start);
        for (int i = from; i < h.length; i++) { int n = i - from + 1; byN.put(n, h[i]); u.append(line(n, h[i].dir, null, h[i].asr, h[i].refined != null ? h[i].refined : h[i].mt)); }
        long t = System.nanoTime();
        String out = llm.chat(Cloud.REVIEW_SYS, (topic.isEmpty() ? "" : "Topic so far: " + topic + "\n") + (had.isEmpty() ? "" : "Memo so far: " + had + "\n") + "Transcript:\n" + u, 500);
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
          if (!r.memo.isEmpty() && chats.setMemo(r.memo, Chats.BY_LLM)) log("🧠 память разговора обновлена моделью (" + r.memo.length() + " зн.)");
        } else if (!r.terms.isEmpty() || !r.topic.isEmpty() || !r.memo.isEmpty()) log("🧠 разговор сменился, пока шёл разбор — тема, память и пары отброшены");
        if (r.fixes.isEmpty() && r.topic.isEmpty()) log("🧠 ответ модели не разобран (" + ms + " мс): " + (out == null ? "" : out.replace('\n', ' ')));
      }
      if (chats == null || chats.current == chatId) lastLocalAt = todo.get(todo.size() - 1).at;
      long ms = (System.nanoTime() - t0) / 1000000;
      String tp = chats == null || chats.topic.isEmpty() ? (topic.isEmpty() ? "нет" : "по словам: " + topic) : chats.topic;
      log("🧠 разбор контекста: реплик " + todo.size() + ", правок " + changed + (hy ? ", пары не извлекаются: переводная модель" : ", пар " + pairs) + " · тема: " + tp
          + " · память " + memo.length() + " зн." + (chats == null || chats.memo.isEmpty() ? "" : Chats.BY_USER.equals(chats.memoBy) ? " (ваша)" : " (" + chats.memoBy + ")") + " (" + ms + " мс)");
      tsv("local_pass", "" + todo.size(), "" + changed, "" + pairs, "" + ms);
      // Итог разбора — в журнале (строка ниже); на экране его видно по самим репликам (✓), шапку не трогаем.
    } } catch (Throwable e) { Log.e(TAG, "refine", e); log("🔁 ошибка уточнения: " + e); }
    finally {
      refineRunning = false; busy("refine", null, 0, 0);
      if (refinePending) llmWorker.submit(this::refineLoop);   // запрос, пришедший между проверкой и выходом, не теряется
      else if (llm != null) { main.removeCallbacks(llmIdleUnload); main.postDelayed(llmIdleUnload, LLM_IDLE_MS); }
    }
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

  /** Что сделает кнопка «Улучшить»: "cloud", "local" или причина, почему ничего. Одна кнопка значит
   *  «улучшить сейчас»: есть ключ и сеть — облако; иначе локальный проход при включённом контексте. */
  public String improveMode() {
    if (eng == null) return "движок ещё загружается";        // интент со стенда приходит раньше, чем поднялись модели
    if (cloudReady() && online()) return "cloud";
    if (contextMode && hasLlm()) return "local";
    if (!mod(Modules.CLOUD) && !mod(Modules.LLM)) return "модули «Облако» и «Уточнитель» выключены";
    if (!cloudReady()) return mod(Modules.CLOUD) ? "нет ключа OpenRouter, а контекст 🧠 выключен" : "контекст 🧠 выключен, а облако — выключенный модуль";
    return "нет сети, а контекст 🧠 выключен";
  }
  /** Последняя реплика уже обработана тем, чем «Улучшить» обработала бы её сейчас, — тогда кнопка
   *  гаснет (владелец 01.10: «надо тушить кнопку улучшить, если данная фраза уже была обработана»).
   *  Облако: оно уже пересматривало разговор до этой реплики или правило её. Уточнитель: он её уже
   *  разбирал или она уже улучшена. Само правило — Screen.improved (настольный тест ScreenTest);
   *  здесь — откуда что взять. Способ спрашивается последним: в нём проверка сети. */
  public boolean lastImproved() {
    Chats c = chats; if (c == null || c.size() == 0) return true;
    String[] t = c.turn(c.size() - 1);
    if (t == null || Chats.PHOTO.equals(t[8])) return true;
    long at; try { at = Long.parseLong(t[6]); } catch (Exception e) { return false; }
    boolean human = c.humanAt(at);
    return Screen.improved(t, human, human ? "" : improveMode(), lastCloudChat == c.current, lastCloudAt, lastLocalAt);
  }
  public boolean cloudConsent() { return getSharedPreferences("at", MODE_PRIVATE).getBoolean("cloud_consent", false); }
  public void setRefineEvery(int n) {
    refineEvery = Math.max(0, n); getSharedPreferences("at", MODE_PRIVATE).edit().putInt("refine_every", refineEvery).apply();
    log("🧠 разбор контекста: " + (refineEvery == 0 ? "только по кнопке" : "каждые " + refineEvery + " реплик"));
  }
  public boolean cloudQuality() { return cloud != null && cloud.preferQuality; }
  /** «Облако: быстрее / точнее» — порядок, в котором перебираются бесплатные модели (Cloud.score). */
  public void setCloudQuality(boolean on) {
    getSharedPreferences("at", MODE_PRIVATE).edit().putBoolean("cloud_quality", on).apply();
    if (cloud == null) return;
    cloud.preferQuality = on;
    log("☁ облако: " + (on ? "точнее — сначала крупные модели" : "быстрее — сначала быстрые") + (cloud.ready ? ", первой пойдёт " + cloud.next().replace(":free", "") : ""));
  }
  public void setCloudEvery(int n) {
    cloudEvery = Math.max(0, n); cloudBackoff = 1; cloudSkipLogged = false; getSharedPreferences("at", MODE_PRIVATE).edit().putInt("cloud_every", cloudEvery).apply();
    log("☁ пересмотр разговора в облаке: " + (cloudEvery == 0 ? "только по кнопке" : "каждые " + cloudEvery + " реплик"));
  }
  public void improveNow() {
    String m = improveMode();
    if (m.equals("cloud")) { sinceCloud = 0; cloudBackoff = 1; cloudReview(true); }
    else if (m.equals("local")) { sinceLocal = 0; kickLocal(); }
    else { log("улучшить нельзя: " + m); hint("улучшить нельзя: " + m); }
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
    if (st.busy() && st.total > 0) busy("models", ("upgrade".equals(st.tier) ? "облегчаю перевод" : ModelStore.AUTO.equals(st.tier) ? "докачиваю модули" : "качаю модели") + " · " + (int) (st.done * 100 / st.total) + " %",
        (int) (st.done * 100 / st.total), 100);
    else if (!st.busy()) busy("models", null, 0, 0);
    long now = System.currentTimeMillis();
    boolean phaseChanged = !st.phase.equals(lastModelPhase);
    if (st.busy() && (phaseChanged || now - lastModelNotif >= 1000)) { lastModelNotif = now; notify(ModelStore.describe(st)); }
    if (phaseChanged && (ModelStore.DONE.equals(st.phase) || ModelStore.ERROR.equals(st.phase) || ModelStore.PAUSED.equals(st.phase))) {
      notify(ModelStore.describe(st));
      if (eng == null) worker.submit(() -> { if (store.check("core").complete()) { status("Модели скачаны, загружаю движки…"); loadAll(); } else status(st.message); });
      else if (ModelStore.DONE.equals(st.phase) && "upgrade".equals(st.tier)) log("📦 перевод станет легче со следующего запуска приложения: память освобождается только вместе с процессом");
      else if (ModelStore.DONE.equals(st.phase) && ModelStore.AUTO.equals(st.tier)) {
        log("📦 модули докачаны: " + modulesLine());
        // подключить пришедшее; облегчение перевода могло ждать, пока шла эта загрузка
        worker.submit(() -> { reloadOptional(); maybeAutoUpgrade(""); Listener l2 = listener; if (l2 != null) main.post(l2::onModules); });
      }
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
  public boolean downloadModels(List<ModelStore.Item> items) { return downloadModels(items, "optional"); }
  public boolean downloadModels(List<ModelStore.Item> items, String tier) {
    if (store == null || items.isEmpty()) return false;
    boolean ok = store.start(items, tier);
    log(ok ? "⬇ загрузка: " + items : "⬇ загрузка уже идёт");
    return ok;
  }
  /** Облегчение перевода — само, без кнопки: после загрузки движков и при появлении подходящей сети
   *  (по умолчанию — Wi-Fi), если места хватает. Одна попытка на запуск; не вышло — кнопка остаётся. */
  volatile boolean autoUpgradeTried = false;
  synchronized void maybeAutoUpgrade(String why) {
    if (store == null) return;
    maybeAutoFetch();
    ModelStore.State st = store.state();
    String no = ModelStore.autoUpgradeBlock(st, store.running(), autoUpgradeTried, netAllowed(), store.usable(), store.spareBytes);
    if (no != null) {
      // В журнал — только то, что человеку стоит знать: нехватку места. «Нет сети» — обычное дело, ждём её.
      if (no.startsWith("мало места") && !autoUpgradeTried) { autoUpgradeTried = true; log("📦 облегчение перевода отложено: " + no); }
      return;
    }
    autoUpgradeTried = true;
    log("📦 облегчаю перевод в фоне: " + ModelStore.mb(st.upgradeBytes) + " МБ" + why + " — заработает со следующего запуска");
    downloadUpgrade();
  }
  volatile boolean autoFetchTried = false;
  /** Файлы включённых модулей, которых нет (обновились — снимки; включили модуль, а сети не было):
   *  докачать самому, как облегчение перевода, — по разрешённой сети, при запасе места, раз за
   *  запуск (смена модулей даёт новую попытку). Идёт раньше облегчения: это возможность, а не экономия. */
  synchronized void maybeAutoFetch() {
    ModelStore.State st = store.state();
    String no = ModelStore.autoFetchBlock(st, store.running(), autoFetchTried, netAllowed(), store.usable(), store.spareBytes);
    if (no != null) {
      if (no.startsWith("мало места") && !autoFetchTried) { autoFetchTried = true; log("📦 чтение снимков отложено: " + no); }
      return;
    }
    autoFetchTried = true;
    List<ModelStore.Item> a = store.autos();
    log("📦 докачиваю в фоне: " + whatFetch(a));
    downloadModels(a, ModelStore.AUTO);
  }
  /** «Облегчить перевод»: скачать новые файлы, которые заменяют прежние; прежние удалятся после сверки. */
  public boolean downloadUpgrade() {
    if (store == null) return false;
    List<ModelStore.Item> up = store.upgrades();
    if (up.isEmpty()) { log("📦 облегчать нечего: новые файлы уже на месте"); return false; }
    return downloadModels(up, "upgrade");
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
      for (String m : modules) activate(m);
      if (words != null && new File(modelsDir, "common_words.txt").exists()) { words.loadCommon(); log("📝 " + words.stats()); }
      if (chats != null) learn = new Learn(chats, modelsDir, getExternalFilesDir(null));
      if (new File(modelsDir, "denoiser").isDirectory() && eng.denoiser == null) log("🔇 шумоподавитель скачан — подключится после перезапуска приложения");
      status("Готово. " + pb.stats());
    } catch (Throwable t) { log("подключение скачанного: " + t); }
  }
  /** Сеть пришла или ушла — кнопка «Улучшить» и причина в подсказке обновляются сразу, а не со следующей
   *  реплики: на устройстве после выхода из режима полёта кнопка оставалась серой до нового события. */
  void watchNetwork() {
    try {
      ConnectivityManager cm = getSystemService(ConnectivityManager.class);
      cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { hint(null); }
        @Override public void onLost(Network n) { hint(null); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { hint(null); if (eng != null) maybeAutoUpgrade(""); }
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
    if (!cloudReady()) { log(mod(Modules.CLOUD) ? "☁ выключено: нет ключа OpenRouter (models/openrouter.json)" : "☁ модуль «Облако» выключен"); return; }
    if (chats == null || chats.size() == 0) { log("☁ нечего уточнять"); hint("нечего уточнять"); return; }
    if (!cloudConsent()) { log("☁ нет согласия на отправку разговора"); hint("нужно согласие на отправку разговора в облако"); return; }
    if (cloudBusy) { log("☁ запрос уже в работе"); return; }
    cloudBusy = true; busy("cloud", "пересматриваю разговор в облаке…", 0, 0);   // ход — на экране в реплике, подсказка в шапке его повторяла бы
    final long chatId = chats.current;
    cloudWorker.submit(() -> {
      try {
        List<String[]> all = chats.dialog();             // снимки — не диалог и без согласия на их отправку
        List<String[]> rows = new ArrayList<>(); int chars = 0;
        for (int k = all.size() - 1; k >= 0; k--) {
          String[] r = all.get(k); String ln = line(0, r[0], cloudWho(r[7]), r[1], r[2]);
          if (chars + ln.length() > CLOUD_BUDGET && !rows.isEmpty()) break;
          chars += ln.length(); rows.add(0, r);
        }
        StringBuilder tr = new StringBuilder(); Map<Integer, String[]> byN = new HashMap<>();
        for (int n = 1; n <= rows.size(); n++) { String[] r = rows.get(n - 1); byN.put(n, r); tr.append(line(n, r[0], cloudWho(r[7]), r[1], r[2])); }
        String topic = topicLine();
        StringBuilder gl = new StringBuilder();
        for (String[] t : chats.terms()) { if (gl.length() > 0) gl.append("; "); gl.append(t[0]).append('=').append(t[1]); }
        long t0 = System.nanoTime();
        String out = cloud.review(tr.toString(), topic, gl.toString(), chats.memo);
        long ms = (System.nanoTime() - t0) / 1000000;
        String trail = String.join(" · ", new ArrayList<>(cloud.trail));
        if (!trail.isEmpty()) { log("☁ след перебора: " + trail); tsv("cloud_trail", trail); }
        if (out == null) {
          log("☁ не вышло за " + ms + " мс: " + cloud.lastError);
          hint("улучшить не вышло" + (cloud.rateLimited ? " — лимит запросов на сегодня" : cloud.lastFail == Cloud.F_NET ? " — нет связи" : ""));
          tsv("cloud_review_fail", "" + rows.size(), "" + chars, "" + ms, cloud.lastError);
          if (!manual && (cloud.rateLimited || cloud.lastFail == Cloud.F_NET)) { cloudBackoff = Math.min(8, cloudBackoff * 2); log("☁ интервал пересмотра удвоен: " + (cloudEvery * cloudBackoff) + " реплик до следующего ручного нажатия"); }
          return;
        }
        if (chats.current != chatId) { log("☁ разговор сменился, пока шёл пересмотр — ответ отброшен"); return; }
        if (!rows.isEmpty()) { lastCloudAt = Long.parseLong(rows.get(rows.size() - 1)[6]); lastCloudChat = chatId; }
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
        // Память переписывается целиком: облако получило прежнюю и дополнило её новыми репликами.
        // Вписанную человеком не трогаем — только говорим, что ответ был.
        String memoNote = r.memo.isEmpty() ? "" : chats.setMemo(r.memo, Chats.BY_CLOUD) ? " · память обновлена (" + r.memo.length() + " зн.)"
            : Chats.BY_USER.equals(chats.memoBy) ? " · память ваша, не тронута" : "";
        int pairs = r.terms.isEmpty() ? 0 : chats.addTerms(r.terms, Chats.BY_CLOUD);
        // Собеседники: что каждый говорит и имя, если назвался. Имя, вписанное человеком, не трогаем.
        int spkN = 0;
        if (!r.speakers.isEmpty()) {
          Map<Integer, String> says = new LinkedHashMap<>();
          for (Map.Entry<Integer, String[]> e : r.speakers.entrySet()) {
            if (chats.voices.get(e.getKey()) == null) continue;              // номер, которого в разговоре нет
            if (!e.getValue()[0].isEmpty()) chats.nameVoice(e.getKey(), e.getValue()[0], "auto");
            says.put(e.getKey(), e.getValue()[1]);
          }
          spkN = chats.voiceSays(says);
        }
        // Пара из глоссария — перевод слова для изучения: облачная пара знает контекст, одиночный MT нет.
        if (learn != null) for (String[] t : r.terms) { String w = t[0].trim().toLowerCase(Locale.ROOT); if (w.length() >= 3 && w.matches("\\p{L}+") && learn.inCorpus(w)) learn.putWordRu(w, t[1]); }
        if (!r.names.isEmpty()) synchronized (pendingNames) {
          for (String[] nm : r.names) { boolean dup = false; for (String[] pn : pendingNames) if (pn[0].equalsIgnoreCase(nm[0])) dup = true; if (!dup) pendingNames.add(nm); }
        }
        if (fixed == 0 && pairs == 0) log("☁ ответ без применимых правок, первые 300 знаков: " + out.replace('\n', ' ').substring(0, Math.min(300, out.length())));
        String sum = "☁ ушло " + rows.size() + " реплик, " + chars + " знаков · " + cloud.lastUsed + " за " + ms + " мс · правок " + fixed
            + (learned > 0 ? " (в выученное " + learned + ")" : "") + (masked > 0 ? " (с масками мимо " + masked + ")" : "")
            + (wrongLang.length() > 0 ? " · не на языке цели: " + wrongLang : "") + ", пар " + pairs
            + (r.names.isEmpty() ? "" : ", имён " + r.names.size()) + (spkN > 0 ? ", собеседников " + spkN : "") + (r.topic.isEmpty() ? "" : " · тема: " + r.topic) + (named ? " (стала названием)" : "") + memoNote;
        log(sum); tsv("cloud_review", "" + rows.size(), "" + chars, cloud.lastUsed, "" + ms, "" + fixed, "" + pairs, "" + r.names.size(), r.topic, "" + r.memo.length());
        // На экране — только итог для человека: модель, знаки, пары и память — в журнале (строка выше),
        // а не в шапке, которую видит и собеседник (владелец 01.10).
        hint(fixed > 0 ? "улучшено · исправлено фраз: " + fixed : "улучшено · исправлять нечего");
        final String fLastFix = lastFix; final String[] lastRow = all.isEmpty() ? null : all.get(all.size() - 1);
        final Listener l = listener;
        if (l != null) main.post(() -> { l.onHistory(); if (fLastFix != null && lastRow != null) l.onTurn(lastRow[0], lastRow[1], fLastFix, true); if (!r.names.isEmpty()) l.onNames(new ArrayList<>(r.names), manual); });
        if (manual && fLastFix != null && lastRow != null && !silent) { try { speakOut(lastRow[0].substring(3), fLastFix); } catch (Throwable e) { log("☁ озвучить не вышло: " + e); } }
      } catch (Throwable e) { Log.e(TAG, "cloud", e); log("☁ ошибка пересмотра: " + e); }
      finally { cloudBusy = false; hint(null); busy("cloud", null, 0, 0); }
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
  /** Произнести готовый текст (для «Улучшить»: перевод уже есть, нужен только звук). */
  void speakOut(String tgt, String text) throws Exception {
    if (!voice()) { hint("🔇 озвучка выключена — «Система» → «Модули»"); return; }
    synchronized (tts) {
    if (!voice()) return;
    int rate = eng.ttsSampleRate(tgt); ensureTrack(rate);
    final boolean dup = btDuplex(); if (!dup) muteUntil = Long.MAX_VALUE;
    double sec = 0; final long[] w0 = {0};
    try { GeneratedAudio ga = eng.speak(tgt, text, chunk -> { if (w0[0] == 0) w0[0] = System.currentTimeMillis(); writeOut(chunk, chunk.length, tgt); return 1; });
      sec = ga.getSamples().length / (double) rate;
    } finally { long until = muteAfter(w0[0] == 0 ? System.currentTimeMillis() : w0[0], sec); if (!dup) muteUntil = until; }
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
        // Удержание копит звук как есть: чувствительность с ограничителем ставится на всю фразу
        // при отпускании (pttStop). Полосе на экране — уровень кадра с учётом усиления.
        if (recording) { float[] mf = micFile;
          if (mf != null) {                              // стенд: удержание слышит запись, темп — микрофона; после конца — тишина
            if (micFilePos == 0) { log("🎙 стенд: удержание слышит запись, " + String.format(Locale.ROOT, "%.1f с", mf.length / 16000.0));
              tsv("micfile_begin", "" + mf.length); }    // якорь времени: снимки экрана стенда — от него
            for (int k = 0; k < n; k++) win[k] = micFilePos + k < mf.length ? mf[micFilePos + k] : 0;
            micFilePos += n;
          }
          float[] c = Arrays.copyOf(win, n); level(c, n, autoOn() ? 0 : micGain());
          synchronized (pttBuf) { pttBuf.add(c); } continue; }
        // Здесь только копия и очередь: всё тяжёлое — в отдельном потоке, иначе кольцевой буфер
        // микрофона переполняется и звук теряется молча.
        // Во время подачи записи микрофон в очередь не пускаем: иначе в замер подмешивается
        // живая комната и повтор перестаёт быть повтором.
        // Чувствительность — в потоке нарезки (startVad), здесь звук идёт как есть.
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
      // Счётчики входа по кадрам — рядом с самими кадрами: после усиления исходного звука уже нет.
      ArrayDeque<Gain.Stats> preSt = new ArrayDeque<>(); List<Gain.Stats> segSt = new ArrayList<>();
      boolean inSpeech = false; int silent = 0, voiced = 0;
      while (running) {
        if (probing) { try { Thread.sleep(20); } catch (InterruptedException e) { return; } continue; }
        float[] win;
        try { win = capQ.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return; }
        if (win == null || eng == null) continue;
        vadSamples += win.length;
        Gain.Stats fs = new Gain.Stats();
        // Оценка фона — после усиления. Сменилось усиление (авто после фразы) — сдвигаем и её:
        // вверх она ползёт нарочно медленно, и после прибавки на 10 дБ порог по энергии минуту
        // считался бы от фона, заниженного на те же 10 дБ.
        double gNow = micGain();
        if (listenLiveReset) { listenLiveReset = false; listenLive.reset(); }
        if (gNow != gainListen.db) {
          if (noiseRms > 0) noiseRms *= Math.pow(10, (gNow - gainListen.db) / 20);
          listenLive.shift(gNow - gainListen.db);
        }
        gainListen.db = gNow; gainListen.limit = limiterOn; gainListen.apply(win, win.length, fs);
        double frame = rms(win, win.length);
        levelDb = (float) Math.max(db(frame), levelDb - 1.5); levelOver = fs.over > 0; levelAt = System.currentTimeMillis();
        listenLive.frame(db(frame), noiseRms > 0 ? db(noiseRms) : Double.NaN);
        liveQ = (float) listenLive.q(listenLive.speechDb()); liveSpeech = listenLive.voiced();
        if (vadReset) { vadReset = false; eng.vad.reset(); }
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
        if (noiseRms > 0) { roomDb = db(noiseRms) - gainListen.db; roomAt = android.os.SystemClock.uptimeMillis(); }
        boolean loud = noiseRms == 0 || frame >= noiseRms * Math.pow(10, gateDb / 20);
        boolean speech = sp && loud;
        if (speech) lastSpeechAt = System.currentTimeMillis();   // отсюда отсчитывается пауза до озвучки

        if (!inSpeech) {
          pre.addLast(win); preSt.addLast(fs);
          while (pre.size() > Math.max(1, preRollMs / FRAME_MS)) { pre.removeFirst(); preSt.removeFirst(); }
          if (speech) { inSpeech = true; seg.clear(); seg.addAll(pre); pre.clear(); segSt.clear(); segSt.addAll(preSt); preSt.clear(); voiced = 1; silent = 0; }
          continue;
        }
        seg.add(win); segSt.add(fs);
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
          final long pos = vadSamples; final Gain.Stats st = new Gain.Stats(); final double gdb = gainListen.db;
          for (int k = 0; k < keep; k++) st.add(segSt.get(k));
          // Авто сдвигается здесь, в момент нарезки, а не в очереди распознавания: иначе следующая
          // фраза шла бы ещё со старым усилением, а при подаче записи ×4 — несколько фраз.
          final double[] sf = Gain.speechFloor(out);
          if (autoOn() && !Double.isNaN(sf[0])) { autoDb += AUTO_STEP * (clampAuto(TARGET_DB - (sf[0] - gdb)) - autoDb); saveAuto(); }
          worker.submit(() -> { segNoiseDb = nz == 0 ? Double.NaN : db(nz); segDb = db(rms(out, out.length)); segAt = at; segPos = pos; hear(sf, st, gdb); route(out); });
        }
        inSpeech = false; seg.clear(); segSt.clear(); silent = 0; voiced = 0;
        listenLive.reset();                               // цвет полоски — по каждой фразе, как её итог
      }
    }, "vad"); vadThread.start();
  }

  /** Слушание: чей это голос. С отпечатком голоса переводятся только голоса разговора — те, кто
   *  хоть раз сказал фразу кнопкой FALAR (Voices). Обрывки чужих фраз вокруг не переводятся и в
   *  разговор не пишутся: ради этого слушание в людном месте и включают (владелец 01.10). Голосов в
   *  разговоре ещё нет — ждём первой фразы кнопкой. Направление — из языка голоса, если оно не
   *  закреплено кнопками; ясный по тексту язык его поправит (translateOnce).
   *  Без отпечатка (модуль выключен или модели нет) — как раньше: переводится всё, что слышно. */
  void route(float[] seg) {
    String dir = fixedDir != null ? fixedDir : "pt2ru";
    Who w = null;
    if (voicesOn()) {
      double ms = seg.length / 16.0;
      if (chats.voices.isEmpty()) { skipVoice("skip_novoice", "🎤 в разговоре ещё нет голосов — пусть каждый скажет фразу кнопкой FALAR", ms, Float.NaN, true); return; }
      if (seg.length < Speaker.MIN_SECONDS * 16000) { skipVoice("skip_short", "🎤 обрывок короче " + Speaker.MIN_SECONDS + " с — по голосу не узнать, не перевожу", ms, Float.NaN, false); return; }
      // Отпечаток — рядом с распознаванием, своим потоком; решение — до перевода (processText).
      final Speaker sp = spk;
      w = new Who(spkExec.submit(() -> sp.embed(seg, 16000)), true, seg);
    }
    process(dir, seg, 16000, autoLang && fixedDir == null, w);
  }
  /** Сегмент не переведён из-за голоса: в журнал и в at.tsv; на экран — только «голосов нет»
   *  (иначе слушание в новом разговоре выглядит сломанным), а не каждый чужой обрывок. */
  volatile long noVoiceHintAt = 0;
  void skipVoice(String kind, String m, double durMs, float score, boolean show) {
    log(m); tsvSeg(kind, "", "", "", Float.isNaN(score) ? "" : String.format(Locale.ROOT, "%.2f", score), durMs, 0, 0);
    long now = System.currentTimeMillis();
    if (show && now - noVoiceHintAt > 20_000) { noVoiceHintAt = now; hint("скажите фразу кнопкой FALAR — голос запомнится"); }
  }

  /** Сегмент двоих по голосам: отпечатки окон (Voices.WIN/HOP) своим потоком, метки, сглаживание, куски;
   *  границы — в тишину рядом (Voices.snap). Меньше четырёх окон — не режем: на голос нужно хотя бы два. */
  List<double[]> splitSeg(final float[] seg, Voices vs) {
    final double total = seg.length / 16000.0; final int n = Voices.windows(total);
    if (n < 4) return new ArrayList<>();
    final Speaker sp = spk;
    List<float[]> win;
    try {
      win = spkExec.submit(() -> {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
          int a = (int) Math.round(i * Voices.HOP * 16000), b = Math.min(seg.length, a + (int) Math.round(Voices.WIN * 16000));
          out.add(sp.embed(Arrays.copyOfRange(seg, a, b), 16000));
        }
        return out;
      }).get(15, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) { log("🎤 разрез по голосам не посчитался: " + e); return new ArrayList<>(); }
    List<double[]> parts = Voices.parts(Voices.smooth(vs.labels(win)), total);
    for (int i = 1; i < parts.size(); i++) { double c = Voices.snap(seg, 16000, parts.get(i)[0], 0.4); parts.get(i - 1)[1] = c; parts.get(i)[0] = c; }
    return parts;
  }
  /** Голос фразы кнопкой FALAR — в голоса разговора, когда реплика уже на экране и звучит: перевод
   *  отпечатка не ждёт. Новый человек получает номер, знакомый подстраивает слепок; номер ложится в
   *  реплику по её метке, подпись на экране дорисовывается. Только в разговоре, где фраза сказана.
   *  Задача идёт в том же потоке, что и отпечаток (spkExec), и стоит в очереди после него. */
  void enrollLater(Who w, String lang, String asr, long chatId, long at) {
    spkExec.submit(() -> {
      float[] e = null;
      try { e = w.print.get(60, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignore) {}   // мимо живого пути: подождать не страшно
      long total = (System.nanoTime() - w.t0) / 1000000;
      if (e == null) { log("🎤 голос не записан: фраза короче " + Speaker.MIN_SECONDS + " с или отпечаток не посчитался"); return; }
      Chats c = chats;
      if (c == null || c.current != chatId) { log("🎤 разговор сменился — голос фразы не записан"); return; }
      Voices.Match m = c.voices.best(e, false); int before = c.voices.size();
      Voices.Voice v = c.enroll(e, lang, at);
      boolean placed = c.setWho(at, String.valueOf(v.n));
      String nm = v.name.isEmpty() ? Memo.intro(asr, lang) : null;
      boolean named = nm != null && c.nameVoice(v.n, nm, "auto");
      log("🎤 " + voiceNote(v, m, c.voices.size() > before) + String.format(Locale.ROOT, " · отпечаток готов через %d мс после фразы", total)
          + (named ? " · представился: " + nm : "") + (placed ? "" : " · реплики с этой меткой уже нет"));
      Listener l = listener; if (l != null) main.post(l::onHistory);
    });
  }
  /** Португальская речь, которая на самом деле не речь собеседника: владелец читает вслух
   *  фразу с экрана по транскрипции. Два признака, оба без настройки и без сети.
   *  Первый: сказанное почти целиком состоит из слов фразы, которая сейчас на экране — значит
   *  её прочли, а не произнесли заново. Второй: при слушании узнан голос разговора, который
   *  кнопкой говорил только по-русски, а речь португальская — он читает, а не говорит.
   *  Отпечаток голоса языка не различает — он опознаёт человека; язык берём из самого текста.
   *  Возвращает причину для показа или null. Молча не выбрасываем ничего: сегодня уже видели,
   *  как молчаливое поведение выглядит поломкой. */
  String readSkip(String dir, String asr, String who, String kind) {
    if (!"pt2ru".equals(dir) || !"asr".equals(kind)) return null;
    // Эхо проверяется при любом положении настройки: это не чтение человеком, а собственный
    // голос приложения, и переводить его обратно не нужно никогда.
    if (Heard.echo(asr, spokenPt, System.currentTimeMillis(), spokenPtEnd))
      return "🔇 пропущено: это эхо моей же озвучки — «" + (spokenPt.length() > 40 ? spokenPt.substring(0, 40) + "…" : spokenPt) + "»";
    if (readGuard == 0) return null;
    Voices.Voice v = who == null || chats == null ? null : chats.voices.get(who);
    if (v != null && v.ru > 0 && v.pt == 0)
      return "🔇 пропущено: «" + Voices.label(v) + "» говорит по-русски, а речь португальская — похоже, читает вслух";
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

  /** Снимок с текстом — офлайн: распознать (Ocr), поправить слова (OcrWords), перевести абзацами
   *  тем же путём, что речь (словарь, свои слова, маски), сохранить реплику «📷» со снимком и
   *  прямоугольниками абзацев и показать перевод поверх фото (PhotoView). Без озвучки: снимок читают
   *  глазами — у меню или таблички это десятки строк (решение владельца 28.09). В выученное не
   *  копится (learn=false). jpg — уже повёрнутый по EXIF и уменьшенный до 2048 px снимок в каталоге
   *  снимков разговоров. */
  public void photoRead(final File jpg) {
    if (jpg == null || !jpg.exists() || ocr == null || !ocr.ready() || !mod(Modules.OCR)) return;
    final long chatId = chats == null ? 0 : chats.current;
    new Thread(() -> {
      long t0 = System.nanoTime();
      busy("live", "читаю снимок…", 0, 0);
      try {
        PhotoDone d = photoTranslate(jpg);
        if (d == null) { jpg.delete(); return; }
        long at = System.currentTimeMillis();
        org.json.JSONObject photo = new org.json.JSONObject().put("file", jpg.getName()).put("w", d.w).put("h", d.h).put("blocks", d.blocks);
        boolean saved = chats != null && chats.addTurn(chatId, Chats.turn("pt2ru", d.src.toString(), d.dst.toString(), Voices.OWNER, at).put("photo", photo));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        photoLog(d, ms);
        tsv("ocr", "" + d.w, "" + d.h, "" + d.pg.boxes, "" + d.pg.paras.size(), "" + d.done, "" + d.pg.loadMs, "" + d.pg.detMs, "" + d.pg.recMs, "" + ms);
        if (!saved) { log("📷 разговор удалили, пока читался снимок — реплике некуда лечь"); jpg.delete(); return; }
        Listener l = listener;
        if (l != null) main.post(() -> { l.onHistory(); l.onPhoto(chatId, at); });
      } catch (Throwable t) { Log.e(TAG, "photo", t); log("📷 не вышло прочитать снимок: " + t); jpg.delete(); }
      finally { busy("live", null, 0, 0); }
    }, "photo").start();
  }

  /** Прочитанный и переведённый снимок: абзацы с рамками для наложения и то, что идёт в журнал. */
  static class PhotoDone {
    final org.json.JSONArray blocks = new org.json.JSONArray(); final StringBuilder src = new StringBuilder(), dst = new StringBuilder();
    final List<String[]> fixes = new ArrayList<>(); int w, h, done, skipped; long ocrMs; Ocr.Page pg;
  }

  /** Чтение и перевод снимка — общее у реплики (photoRead) и стенда (photodry). null — текста нет
   *  (в журнал уже сказано). Абзац сначала правится по словарю (OcrWords): распознаватель теряет
   *  буквы, а переводчик на искажённом слове выдумывает («камыш» вместо «кармин»); в реплику
   *  уходит исправленный текст — его и переводили, а подлинник остаётся на самом снимке. */
  PhotoDone photoTranslate(File jpg) throws Exception {
    long t0 = System.nanoTime();
    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
    o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(jpg.getAbsolutePath(), o);
    if (bm == null) { log("📷 снимок не прочитался"); return null; }
    PhotoDone d = new PhotoDone();
    d.w = bm.getWidth(); d.h = bm.getHeight(); int[] px = new int[d.w * d.h];
    bm.getPixels(px, 0, d.w, 0, 0, d.w, d.h); bm.recycle();
    Ocr.Page pg = ocr.read(px, d.w, d.h); px = null; d.pg = pg;
    d.ocrMs = (System.nanoTime() - t0) / 1_000_000;
    if (pg.paras.isEmpty()) {
      log("📷 текста не нашлось за " + d.ocrMs + " мс (" + d.w + "×" + d.h + "; " + photoHow(pg) + ")"); hint("📷 на снимке не нашлось текста");
      tsv("ocr", "" + d.w, "" + d.h, "" + pg.boxes, "0", "0", "" + pg.loadMs, "" + pg.detMs, "" + pg.recMs, "" + d.ocrMs);
      return null;
    }
    OcrWords ow = ocrWords();
    java.util.function.Predicate<String> known = wd -> ow != null && ow.known(wd);
    List<String> all = new ArrayList<>(); for (OcrCore.Para p : pg.paras) all.add(p.text);
    Set<String> brands = TextRules.brands(all, known);                  // марки по сайту, почте, ®, названию фирмы
    for (int i = 0; i < pg.paras.size(); i++) {
      busy("live", "перевожу снимок…", i, pg.paras.size());
      OcrCore.Para p = pg.paras.get(i);
      List<String[]> fx = new ArrayList<>();
      String text = ow == null ? p.text : ow.fix(p.text, fx, p.table && OcrWords.bare(p.text));   // чек — без знаков над буквами
      // Абзац, который распознаватель сам прочитал неуверенно, — каша («Porções por ombalad m •
      // or:o: 1lm»): её перевод закрыл бы собой настоящий текст на снимке. Такой остаётся как есть.
      String ru = null, why = p.score() < OcrCore.CONF_MIN ? "плохо прочитано" : photoSkip(text, p.table);
      if (why != null) text = p.text;                  // не переводится — показываем как прочитано, без правки
      else d.fixes.addAll(fx);
      if (why == null) {
        String sign = TextRules.unshoutSign(text, wd -> (ow != null && ow.common(wd)) || (words != null && words.isCommon(wd, "pt")), brands);
        // Вывеска из одного слова — марка, если оно из марок снимка или похоже на марку, а переводчик
        // его лишь переписал кириллицей («Rommanel» → «Ромманель»): тогда оно остаётся как на снимке.
        // Своё слово с переводом из списка важнее.
        String one = TextRules.oneWord(sign);
        boolean mine = one != null && words != null && !words.apply(sign, "pt", "ru", new ArrayList<>(), new ArrayList<>(), true).masked.equals(sign);
        if (one != null && !mine && brands.contains(OcrWords.plain(one))) why = "марка";
        else {
          Once r = translateOnce("pt2ru", sign, false, false, true, brands);
          if (r.skip == null && r.mt != null && !r.mt.trim().isEmpty()) ru = r.mt.trim();
          if (ru != null && ru.equals(text.trim())) ru = null;        // перевод тот же, что снимок («L:117126 16:26») — рисовать нечего
          if (ru != null && one != null && r.whits.isEmpty() && TextRules.brandLike(one, known) && TextRules.transliterated(one, ru)) { ru = null; why = "марка"; }
        }
      }
      if (why != null) d.skipped++;
      double[] f = p.frame(); org.json.JSONArray fa = new org.json.JSONArray();
      for (double v : f) fa.put(Math.round(v * 1000) / 1000.0);
      org.json.JSONObject b = new org.json.JSONObject().put("f", fa).put("src", text);
      if (ru != null) { b.put("dst", ru); d.done++; d.dst.append(d.dst.length() > 0 ? "\n" : "").append(ru); }
      d.blocks.put(b); d.src.append(d.src.length() > 0 ? "\n" : "").append(text);
    }
    return d;
  }
  static String photoHow(Ocr.Page pg) { return "детектор " + pg.detMs + " мс, распознаватель " + pg.recMs + " мс, модели " + pg.loadMs + " мс, рамок " + pg.boxes
      + (pg.strips > 0 ? " (из них полосой " + pg.strips + ")" : ""); }
  void photoLog(PhotoDone d, long ms) {
    StringBuilder fx = new StringBuilder();
    for (String[] c : d.fixes) fx.append(fx.length() > 0 ? ", " : "").append(c[0]).append("→").append(c[1]);
    log("📷 OCR за " + d.ocrMs + " мс, с переводом " + ms + " мс (" + d.w + "×" + d.h + "; " + photoHow(d.pg) + "): абзацев " + d.pg.paras.size()
        + ", переведено " + d.done + (d.skipped > 0 ? ", оставлено как есть " + d.skipped : "")
        + (d.fixes.isEmpty() ? "" : " · ✏ " + fx) + " · " + d.pg.lines.replace('\n', ' '));
  }

  /** Словарь правки слов снимка (assets/ocr_words_pt.txt.gz, сжат gzip): грузится один раз, при первом снимке. */
  private volatile OcrWords ocrWords; private volatile boolean ocrWordsTried;
  OcrWords ocrWords() {
    if (ocrWords != null || ocrWordsTried) return ocrWords;
    synchronized (this) {
      if (ocrWords == null && !ocrWordsTried) {
        ocrWordsTried = true; long t = System.nanoTime();
        try (java.io.InputStream in = new java.util.zip.GZIPInputStream(getAssets().open("ocr_words_pt.txt.gz"))) {
          ocrWords = OcrWords.load(in);
          log("📷 словарь правки слов: " + ocrWords.size() + " форм за " + (System.nanoTime() - t) / 1_000_000 + " мс");
        } catch (Throwable e) { log("📷 словарь правки слов не загрузился — снимки без правки: " + e); }
      }
    }
    return ocrWords;
  }

  static long med(List<Long> v) { if (v.isEmpty()) return 0; List<Long> c = new ArrayList<>(v); Collections.sort(c); return c.get(c.size() / 2); }
  static void write(File f, String text) throws java.io.IOException {
    try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8")) { w.write(text); }
  }

  /** Почему абзац снимка не переводить (null — переводить). Текст не на португальском остаётся
   *  как есть: английский абзац на табличке переводчик pt→ru превращал в мусор («⁇ 2019 ⁇ Все
   *  права защищены»). Одиночные служебные слова («DO») и обрывки без букв — тоже: переводчик
   *  на них выдумывает. Короткое название («SAÍDA», «Guapíara») переводится — это и нужно. */
  String photoSkip(String t) { return photoSkip(t, false); }
  /** table — строка таблицы (чек, прейскурант): язык по доле знакомых слов не судится. Позиция чека
   *  «002 7890000000017 ARROZ PRATINHO BCO 5KG» — марка и сокращение, знакомо одно слово из трёх,
   *  и она уходила в «не португальский». Везде проверку не снять: на наборе вывесок тогда в перевод
   *  пошли бы список фамилий на табличке (5 строк) и мусор распознавания (3). */
  String photoSkip(String t, boolean table) {
    String letters = t.replaceAll("[^\\p{L}]", "");
    if (letters.length() < 2) return "без букв";
    // адрес сайта или почты переводчик дополнял выдумкой: «2019 Cepera.com. Все права защищены.»
    if (OcrCore.webby(t)) return "адрес сайта или почты";
    boolean func = true;
    for (String w : t.trim().split("\\s+")) if (!OcrCore.CONT.contains(w.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT))) { func = false; break; }
    if (func) return "служебные слова";
    // Доля знакомых слов — по словарю снимков (72 тыс. форм), а не по словарю речи (17 тыс.): на
    // этикетке «espessante», «carboidratos», «corante» — обычные слова, которых в речи не бывает,
    // и абзац состава уходил в «не португальский». Английский абзац таблички набирает 0,2–0,33.
    if (table) return null;
    OcrWords ow = ocrWords();
    double lk = ow != null ? ow.share(t) : words != null ? words.looksLike(t, "pt") : -1;
    if (lk >= 0 && lk < (ow != null ? OcrWords.LANG_MIN : LANG_MIN)) return "не португальский";
    return null;
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
      if (allowCloud && cloudReady()) {
        text = cloud.image(jpeg); how = "облако " + cloud.lastUsed;
      }
      if ((text == null || text.trim().isEmpty()) && !allowCloud) {
        log("📷 офлайн-распознавание не справилось. Долгое нажатие на 📷 — прочитать в облаке.");
        return;
      }
      long ms = (System.nanoTime() - t0) / 1000000;
      if (text == null || text.trim().isEmpty()) {
        log("📷 текста не нашлось за " + ms + " мс (снимок " + (jpeg.length / 1024) + " КБ)"
            + (!cloudReady() ? ": офлайн-чтения нет, облако недоступно" : ": " + cloud.lastError));
        return;
      }
      log("📷 " + how + " за " + ms + " мс (снимок " + (jpeg.length / 1024) + " КБ): " + text.replace('\n', ' '));
      processText("pt2ru", unshout(text.trim()), true, false, 0, ms, "фото");
    }, "photo").start();
  }
  /** Офлайн-распознавание текста на снимке (models/ocr). Без моделей снимок читает облако — по
   *  согласию на каждый снимок. */
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
  void process(String dirIn, float[] samples, int sr, boolean auto) { process(dirIn, samples, sr, auto, null); }
  /** who — чей голос (route) или отпечаток фразы кнопкой (pttStop); null — голоса не при деле. */
  void process(String dirIn, float[] samples, int sr, boolean auto, Who who) {
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
      busy("live", "распознаю речь…", 0, 0);
      long t0 = System.nanoTime(); String asr = eng.asr(src, fed, sr); long t1 = System.nanoTime();   // sherpa ресемплирует сам
      if (asr.isEmpty()) { log("(тишина / не распознано, " + String.format("%.1f", samples.length / (double) sr) + " с)"); tsvSeg("silence", dir, "", "", "", durMs, (t1 - t0) / 1000000, 0); return; }
      processText(dir, asr, auto, true, durMs, (t1 - t0) / 1000000, "asr", chatId, who);
    } catch (Throwable t) { Log.e(TAG, "process", t); log("Ошибка: " + t); tsv("error", dirIn, String.valueOf(t)); }
    finally { busy("live", null, 0, 0); }
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
  Once translateOnce(String dirIn, String asrIn, boolean auto, boolean gate) throws Exception { return translateOnce(dirIn, asrIn, auto, gate, false); }
  /** sign — текст вывески (снимок): в выученное не копится — он не сказан в разговоре, и его
   *  повторы портили бы и быстрый путь, и частоты; название улицы — только с заглавной
   *  (TextRules.mask strictNames: регистр у вывески осмыслен после unshoutSign). */
  Once translateOnce(String dirIn, String asrIn, boolean auto, boolean gate, boolean sign) throws Exception { return translateOnce(dirIn, asrIn, auto, gate, sign, null); }
  /** brands — марки снимка (TextRules.brands): у вывески их слова переводчику не показываются. */
  Once translateOnce(String dirIn, String asrIn, boolean auto, boolean gate, boolean sign, Set<String> brands) throws Exception {
    boolean learn = !sign;
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
    WordList.Result wr = words == null ? null : words.apply(asr, src, tgt, r.slots, r.whits, sign);   // снимок — только точно
    String pre = wr == null ? asr : wr.masked;
    if (wr != null) asr = wr.readable;                                   // дальше везде — исправленный текст // свои слова — раньше адресного шаблона
    TextRules.Masked mk = TextRules.mask(pre, src, r.slots, sign, brands); // §6 ярус 3: числа/цены/адреса в плейсхолдеры
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
    // Вывеска, где после масок не осталось слов, — только сокращения, числа и знаки («SP /CNPJ:
    // 62.162.243/0003-45»). Переводчику тут нечего переводить, и он выдумывал «Модель:». Слоты
    // подставляются сами, сокращения — своим переводом: «Сан-Паулу /ИНН: 62.162.243/0003-45».
    if (sign && !TextRules.hasWords(mk.text)) {
      r.mt = TextRules.unmask(mk.text, mk, tgt); r.maskedMt = mk.text; r.tag = "без переводчика: сокращения и числа";
      return r;
    }
    Phrasebook.Hit hit = pb.lookup(dir, mk.text); String mt, tag, maskedMt; int hits = 0;
    if (hit != null) { maskedMt = hit.dst; mt = TextRules.unmask(hit.dst, mk, tgt); tag = hit.kind; }
    else {
      String[] sents = mk.text.split(SENT);                               // parakeet пунктуирует обе стороны
      if (sents.length > 1) {                                             // по предложениям: словарь там, где попал
        StringBuilder sb = new StringBuilder(); int pbHits = 0;
        for (String sn : sents) {
          // у вывески предложение из одних сокращений и чисел — как есть, как и строка без слов выше:
          // «ICPA CEPÊRA XQ1. XQ2, XQ3 - XQ4» переводчик превращал в «… Модель: XQ2 XQ4, XQ3»
          if (sign && !TextRules.hasWords(sn)) { if (sb.length() > 0) sb.append(' '); sb.append(sn); continue; }
          Phrasebook.Hit h = pb.lookup(dir, sn);
          String part; if (h != null) { part = h.dst; pbHits++; } else { part = eng.translate(dir, sn); if (learn) pb.record(dir, sn, part); }
          if (sb.length() > 0) sb.append(' '); sb.append(part);
        }
        maskedMt = sb.toString(); mt = TextRules.unmask(maskedMt, mk, tgt);
        tag = pbHits > 0 ? "словарь+MT (" + pbHits + "/" + sents.length + ")" : "MT (" + sents.length + " фразы)";
      } else {
        String mtM = eng.translate(dir, mk.text); hits = learn ? pb.record(dir, mk.text, mtM) : 0;
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
    processText(dirIn, asrIn, auto, gate, durMs, srcMs, kind, chatId, null);
  }
  void processText(String dirIn, String asrIn, boolean auto, boolean gate, double durMs, long srcMs, String kind, final long chatId, Who whoIn) {
    busy("live", "перевожу…", 0, 0);
    try {
      long t1 = System.nanoTime();
      String who = null, spkTag = null;
      Voices.Voice alt = null;                 // второй голос сегмента, похожего на двоих, если разрез не нашёлся
      // Слушание: голос разговора или чужой — после распознавания и до перевода. Отпечаток считался
      // рядом с распознаванием; обычно он готов, и ждать не приходится (ждал — в журнале).
      if (whoIn != null && whoIn.n != null) {                    // кусок разрезанного сегмента: голос уже решён
        who = whoIn.n; spkTag = " · 🎤 голос " + who + " (кусок сегмента двоих)";
        Voices.Voice v = chats == null ? null : chats.voices.get(who);
        if (auto && v != null) dirIn = "ru".equals(v.lang) ? "ru2pt" : "pt2ru";
      } else if (whoIn != null && whoIn.listen) {
        float[] e = null; long tw = System.nanoTime();
        boolean late = false;
        try { e = whoIn.print.get(PRINT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException x) { late = true; } catch (Exception ignore) {}
        long waited = (System.nanoTime() - tw) / 1000000, total = (tw - whoIn.t0) / 1000000 + waited;
        Voices vs = chats == null ? new Voices() : chats.voices;
        Voices.Match m = vs.best(e, true);
        if (e == null) {
          // Отпечаток не успел или не посчитался: фразу переводим без номера — потерять реплику
          // участника хуже, чем пропустить обрывок чужого (владелец 01.10).
          log("🎤 " + (late ? "отпечаток не успел за " + PRINT_WAIT_MS / 1000 + " с" : "отпечаток не посчитался") + " — перевожу без номера голоса");
          tsvSeg(late ? "voice_late" : "voice_fail", "", "", "", "", durMs, waited, 0);
        } else if (!m.hit()) {
          skipVoice("skip_voice", String.format(Locale.ROOT, "🎤 чужой голос: ближе всех «%s» — %.2f, нужно %.2f · не перевожу (отпечаток %d мс, ждал %d мс)",
                  Voices.label(m.v), m.score, m.thr, total, waited), durMs, m.score, false);
          return;
        }
        if (e != null) {
          // Двое подряд: сегмент похож сразу на двоих — режем по окнам и распознаём куски отдельно.
          // Распознавание по одному куску на язык: смешанный сегмент оно пишет на языке начала
          // («Кто эта девочка? Это Кейко» после португальской фразы вышло «É Keiko.»).
          if (whoIn.seg != null && m.next != null && m.nextScore >= Voices.SPLIT && whoIn.seg.length >= 2 * 16000) {
            long ts = System.nanoTime();
            List<double[]> parts = splitSeg(whoIn.seg, vs);
            if (parts.size() >= 2) {
              StringBuilder pl = new StringBuilder();
              for (double[] pt : parts) pl.append(String.format(Locale.ROOT, " · %s %.1f–%.1f с", Voices.label(vs.get((int) pt[2])), pt[0], pt[1]));
              log(String.format(Locale.ROOT, "🎤 двое в одном сегменте (%.2f и %.2f к «%s» и «%s»), разрез за %d мс:%s",
                  m.score, m.nextScore, Voices.label(m.v), Voices.label(m.next), (System.nanoTime() - ts) / 1000000, pl));
              busy("live", null, 0, 0);
              for (double[] pt : parts) {
                int a = (int) Math.round(pt[0] * 16000), b = Math.min(whoIn.seg.length, (int) Math.round(pt[1] * 16000));
                if (b - a < 0.4 * 16000) continue;                 // меньше 0,4 с — распознавать нечего
                Voices.Voice v = vs.get((int) pt[2]);
                process(v != null && "ru".equals(v.lang) ? "ru2pt" : "pt2ru", Arrays.copyOfRange(whoIn.seg, a, b), 16000, auto, new Who(String.valueOf((int) pt[2])));
              }
              return;
            }
            log(String.format(Locale.ROOT, "🎤 сегмент похож на двоих (%.2f и %.2f), но окна разреза не нашли — одной репликой", m.score, m.nextScore));
            alt = m.next;
          }
          who = String.valueOf(m.v.n);
          spkTag = String.format(Locale.ROOT, " · 🎤 голос %s %.2f (отпечаток %d мс, ждал %d мс)", who, m.score, total, waited);
          if (auto) dirIn = "ru".equals(m.v.lang) ? "ru2pt" : "pt2ru";   // язык голоса — по умолчанию; ясный текст поправит
        }
      }
      Once r = translateOnce(dirIn, asrIn, auto, gate);
      busy("live", null, 0, 0);                    // перевод готов; озвучка слышна сама
      if (r.skip != null) { log(r.skip); tsvSeg(r.skipKind, r.dir, r.asr, "", r.lkTag, durMs, srcMs, 0); return; }
      // Двое без разреза: номер — тому из двоих, чей язык совпал с текстом; «читает вслух» по голосу такой
      // сегмент не судим — в нём говорили оба (стенд 01.10: остаток фразы A с фразой B ушёл голосу B и
      // был отброшен как «русский голос читает португальскую фразу»).
      if (alt != null && chats != null) {
        Voices.Voice cur = chats.voices.get(who);
        if (cur != null && !r.src.equals(cur.lang) && r.src.equals(alt.lang)) { who = String.valueOf(alt.n); spkTag += " · номер по языку текста"; }
      }
      String guard = readSkip(r.dir, r.asr, alt != null ? null : who, kind);
      if (guard != null) { log(guard); hint(guard); status(guard); tsvSeg("skip_read", r.dir, r.asr, "", "", durMs, srcMs, 0); return; }
      // Снимок и набранная фраза — реплики владельца телефона: их вводит тот, кто держит телефон.
      if (who == null && (kind.equals("фото") || kind.equals("набрано"))) who = Voices.OWNER;
      // Человек назвался — «меня зовут Анна», «meu nome é Ana»: имя у его голоса (вписанное
      // человеком не трогаем). Из исходника: перевод имя искажает.
      if (who != null && chats != null && chats.current == chatId) {
        Voices.Voice v = chats.voices.get(who); String nm = v == null || !v.name.isEmpty() ? null : Memo.intro(r.asr, r.src);
        if (nm != null && chats.nameVoice(v.n, nm, "auto")) { spkTag = (spkTag == null ? "" : spkTag) + " · представился: " + nm; }
      }
      String dir = r.dir, asr = r.asr, mt = r.mt, tag = r.tag + (spkTag == null ? "" : spkTag);
      long t2 = System.nanoTime();
      final long at = System.currentTimeMillis();
      final boolean here = chats == null || chats.current == chatId;   // разговор не сменился, пока считали
      final Turn turn = new Turn(++turnNo, dir, asr, mt, at);
      if (here) { history.add(turn); if (history.size() > 30) history.remove(0); }
      final String fTgt = r.tgt;                 // направление могло смениться по языку реплики
      final long[] first = {0}; double audioS = 0;
      final boolean cacheable = r.cacheable;
      // Снимок не озвучивается: его читают глазами (решение владельца 28.09).
      if (silent || !here || kind.equals("фото") || !voice()) { first[0] = t2; tag += silent ? " · без озвучки" : !here ? " · разговор сменился, без озвучки" : kind.equals("фото") ? " · снимок, без озвучки" : " · озвучка выключена"; }
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
      if (whoIn != null && !whoIn.listen) enrollLater(whoIn, r.src, r.asr, chatId, at);
    } catch (Throwable t) { Log.e(TAG, "process", t); log("Ошибка: " + t); tsv("error", dirIn, String.valueOf(t)); }
    finally { busy("live", null, 0, 0); }
  }
  /** Произнести готовый перевод. Вынесено из конвейера, потому что озвучка может ждать паузы
   *  в речи, а перевод ждать не должен. */
  /** Один голос за раз. Очередь озвучки, набранная фраза, снимок и «Улучшить» синтезируют
   *  в разных потоках, а AudioTrack один: без этого замка две озвучки писали в него вперемешку,
   *  и первая же закончившаяся снимала заглушку с микрофона под второй. */
  final Object tts = new Object();
  /** Когда, по оценке, доиграет всё, что уже отдано на вывод (System.currentTimeMillis). */
  volatile long playEndMs = 0;
  /** Заглушка микрофона — до конца звучания плюс 400 мс на хвост и отражения. Раньше конец считался
   *  от момента, когда синтез отдал последний кусок: запись в буфер на 3 с возвращается заранее, и
   *  к оставшимся секундам прибавлялась ещё вся длина фразы — фраза в 6 с глушила микрофон на 9,4 с,
   *  а очередь из нескольких — на десятки секунд (владелец 01.10: «перестаёт записывать диалог»).
   *  Теперь звучание начинается с первого отданного куска, но не раньше, чем доиграет предыдущее. */
  long muteAfter(long firstWriteMs, double audioS) {
    long start = Math.max(firstWriteMs, playEndMs);
    playEndMs = start + (long) (audioS * 1000);
    return playEndMs + 400;
  }

  double speakTurn(String dir, String tgt, String mt, boolean cacheable, long[] first) throws Exception {
    if (!voice()) return 0;                            // модуль «Озвучка» выключен или голосов ещё нет: перевод на экране
    synchronized (tts) {
    if (!voice()) return 0;                            // выключили, пока ждали очередь
    int rate = eng.ttsSampleRate(tgt); ensureTrack(rate);
    // В наушник — значит озвучка не попадает в комнату и микрофон можно не глушить:
    // собеседник продолжает говорить, пока в ухе идёт перевод. Замер протечки: −13…+0,1 дБ,
    // то есть микрофон не слышит наушник вовсе (results/2026-09-13-headphones.md).
    final boolean dup = btDuplex();
    if (!dup) muteUntil = Long.MAX_VALUE;
    double audioS = 0;
    final long[] w0 = {0};                 // когда отдан первый кусок — с него фраза и звучит
    float[] cached = pb.audio(dir, mt);
    try {                                  // без finally одно исключение в синтезе делало приложение глухим навсегда
      if (cached != null) { if (first != null && first[0] == 0) first[0] = System.nanoTime(); w0[0] = System.currentTimeMillis(); writeOut(cached, cached.length, tgt); audioS = cached.length / (double) rate; }
      else { GeneratedAudio ga = eng.speak(tgt, mt, chunk -> { if (first != null && first[0] == 0) first[0] = System.nanoTime(); if (w0[0] == 0) w0[0] = System.currentTimeMillis(); writeOut(chunk, chunk.length, tgt); return 1; });
        audioS = ga.getSamples().length / (double) rate; if (cacheable) pb.putAudio(dir, mt, ga.getSamples(), rate); }
    } finally {
      long now = System.currentTimeMillis(), until = muteAfter(w0[0] == 0 ? now : w0[0], audioS);
      if (!dup) muteUntil = until;
      if (audioS >= 3) log(String.format(Locale.ROOT, "🔊 %.1f с звука · микрофон глух до %+.1f с от отдачи последнего куска (прежний расчёт: %+.1f с)",
          audioS, (until - now) / 1000.0, audioS + 0.4));
      // Что и когда проговорено по-португальски — для отсева эха: хвост озвучки и отражение от стен
      // доходят до микрофона и после этих 400 мс.
      if ("pt".equals(tgt)) { spokenPt = mt; spokenPtEnd = System.currentTimeMillis() + (long) (audioS * 1000); }
    }
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
    tsv(kind, dir, asr, mt, tag, f1(durMs), f1(segDb), f1(segNoiseDb), f1(segDb - segNoiseDb), "" + segAt, "" + asrMs, "" + mtMs, "" + segPos,
        f1(segGainDb), f1(segSpeechDb), f1(segFloorDb), f3(segOverPct), f3(segLimPct), f3(segCutPct));
  }
  static double rms(float[] s, int n) { double a = 0; for (int k = 0; k < n; k++) a += s[k] * (double) s[k]; return Math.sqrt(a / Math.max(1, n)); }
  static double db(double x) { return 20 * Math.log10(Math.max(1e-9, x)); }
  static String f1(double x) { return Double.isNaN(x) ? "" : String.format(Locale.ROOT, "%.1f", x); }
  static String f3(double x) { return Double.isNaN(x) ? "" : String.format(Locale.ROOT, "%.3f", x); }

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
  /** Последняя строка состояния — для экрана, подключившегося позже: пересозданный экран (тема системы,
   *  возврат после камеры) иначе показывал «Запуск сервиса…» при работающей службе (test_ui_device.sh U9). */
  volatile String lastStatus;
  void status(String s) { lastStatus = s; Listener l = listener; if (l != null) main.post(() -> l.onStatus(s)); }
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
  /** Приложение смахнули из недавних — человек его закрыл. Переводчик, который после этого
   *  продолжает слушать комнату и держать три гигабайта, ведёт себя не так, как от него ждут. */
  @Override public void onTaskRemoved(Intent rootIntent) {
    log("✖ приложение закрыто — останавливаюсь и отдаю память");
    exitAfterStop = true;
    stopSelf();
    super.onTaskRemoved(rootIntent);
  }
  /** Система просит памяти. Уточнитель — самое крупное и необязательное: 1,1 ГБ отдельным
   *  процессом. Выгружаем его; включённый контекст поднимет его снова при следующем разборе. */
  @Override public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    // Каждый сигнал — в машинный журнал: если приложение потом «упадёт втихую», там будет видно,
    // что система к этому вела. На Redmi сигнал «памяти мало» пришёл в ту же секунду, как
    // поднялся уточнитель: приложение вместе с ним занимает около четырёх гигабайт.
    tsv("trim", "" + level);
    // Выгружаем, когда давление держится или мы в фоне (Pressure): критический сигнал сразу после
    // подъёма уточнителя вызывает сам подъём, и выгрузка на нём означала, что уточнитель в
    // разговоре не работал вовсе. «Памяти маловато» и UI_HIDDEN — не повод.
    long now = android.os.SystemClock.elapsedRealtime();
    boolean go = Pressure.unload(level, now, llm != null ? llmLoadedAt : 0, lastCritical);
    if (level == TRIM_MEMORY_RUNNING_CRITICAL) {
      if (llm != null && !go) log("🧠 памяти критически мало (уровень 15) — " + (now - llmLoadedAt < Pressure.GRACE_MS
          ? "пик после подъёма уточнителя, пережидаю" : "пережидаю: выгружу, если повторится в ближайшие " + Pressure.REPEAT_MS / 1000 + " с"));
      lastCritical = now;
    }
    if (go) unloadLlm("памяти критически мало (уровень " + level + (level >= TRIM_MEMORY_BACKGROUND ? ", приложение в фоне" : ", давление держится") + "), подниму при следующем разборе");
  }
  /** Когда поднят уточнитель и когда был прошлый критический сигнал — монотонное время, мс. */
  volatile long llmLoadedAt = 0, lastCritical = 0;
  @Override public void onDestroy() {
    running = false; main.removeCallbacks(idleStop);
    if (store != null) store.cancel();
    if (spkTrack != null) spkTrack.release();
    worker.shutdownNow(); llmWorker.shutdownNow(); cloudWorker.shutdownNow();
    // Модели освобождаем после того, как рабочие потоки вышли: иначе распознавание, идущее в эту
    // секунду, обратится к уже освобождённой нативной памяти.
    try { worker.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
    if (llm != null) llm.stop();
    for (int pid : Llm.strays()) android.os.Process.killProcess(pid);
    if (eng != null) eng.release();
    if (spk != null) spk.release();
    if (wl != null && wl.isHeld()) wl.release();
    if (track != null) track.release();
    fileLog.shutdown();   // не shutdownNow: последние строки журнала («останавливаюсь…») должны успеть записаться
    try { fileLog.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
    super.onDestroy();
    if (exitAfterStop) android.os.Process.killProcess(android.os.Process.myPid());
  }
}
