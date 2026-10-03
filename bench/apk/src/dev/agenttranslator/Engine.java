package dev.agenttranslator;

import ai.onnxruntime.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.util.*;

/** ASR (sherpa-onnx) + MT (OPUS-MT int8 via ONNX Runtime, greedy, KV-cache) + TTS (Piper) + VAD (silero). Один процесс.
 *
 *  Перевод — две сессии на направление, если есть encoder_kv_model.onnx (tools/mt_encoder_kv.py):
 *  кодировщик сразу считает K/V перекрёстного внимания, и все шаги, включая первый, делает один
 *  decoder_with_past — первый с пустым прошлым. Прежде было три сессии, и веса декодера (216 МБ на
 *  направление) лежали в памяти дважды. Без нового файла — прежний путь из трёх: он же для сверки.
 *  Перебор вариантов (луч, mtBeam > 1) — только на пути из двух сессий; по умолчанию выключен. */
public class Engine {
  public interface Log { void log(String s); }

  /** Порог VAD и длительность тишины — настраиваемые: при фоне комнаты около −39 dBFS порог 0,5
   *  периодически принимает шум за речь, счётчик тишины сбрасывается, и сегмент не закрывается —
   *  соседние фразы склеиваются в один кусок, а пауза между ними в него вовсе не попадает
   *  (VAD sherpa складывает только речевые кадры). См. results/2026-09-12-air.md. */
  public String vadModelPath;
    /** Тишина у sherpa намеренно почти нулевая: это должен быть чистый покадровый детектор речи.
   *  Выдержку до закрытия сегмента считает TranslatorService. Пока 0,6 с стояло в обоих местах,
   *  правило применялось дважды и сегмент не закрывался раньше чем через 1,2 с тишины — при
   *  паузах в быстром диалоге (0,8 с) он не закрывался вовсе. */
  public volatile float vadThreshold = 0.5f, vadMinSilence = 0.05f;
  public Vad buildVad(float threshold, float minSilence) {
    SileroVadModelConfig sv = SileroVadModelConfig.builder().setModel(vadModelPath).setThreshold(threshold)
        .setMinSilenceDuration(minSilence).setMinSpeechDuration(0.25f).setWindowSize(512).setMaxSpeechDuration(15f).build();
    return new Vad(VadModelConfig.builder().setSileroVadModelConfig(sv).setSampleRate(16000).setNumThreads(1).setDebug(false).build());
  }
  public synchronized void retuneVad(float threshold, float minSilence) {
    vadThreshold = threshold; vadMinSilence = minSilence;
    Vad old = vad; vad = buildVad(threshold, minSilence);
    if (old != null) try { old.release(); } catch (Throwable ignore) {}
  }
  static final int LAYERS = 6, HEADS = 16, HEAD_DIM = 64;
  final File m; final Log log;
  public OfflineRecognizer asrPt, asrMulti; public OnlineRecognizer asrRu; public OfflineTts ttsRu, ttsPt; public Vad vad;
  public OfflineSpeechDenoiser denoiser; public volatile boolean denoiseOn = false;
  public String asrName = "";
  OrtEnvironment env; final Map<String, OrtSession[]> mt = new HashMap<>(); final Map<String, SpmTokenizer> tok = new HashMap<>();
  public long loadAsrMs, loadMtMs, loadTtsMs;
  /** Стенд: "legacy" — прежний путь из трёх сессий, даже если кодировщик с K/V на месте; для сверки
   *  памяти и переводов на телефоне. Замер 28.09: ни отказ от упаковки весов, ни отказ от арены
   *  памяти не экономят, а без упаковки перевод вдвое медленнее (results/2026-09-28-memory.md). */
  public static volatile String mtVariant = "";
  /** Ширина луча перевода. 1 — жадный путь выпущенных версий: на каждом шаге один самый вероятный
   *  кусок слова, без возврата. Больше — перебор вариантов: держится mtBeam лучших черновиков, в конце
   *  берётся лучший по средней логвероятности на кусок (норма длины mtLp). Замер на столе —
   *  results/2026-10-03-mt-beam.md; на телефоне не мерен, поэтому по умолчанию выключен. */
  public static volatile int mtBeam = 1;
  public static volatile double mtLp = 1.0;
  /** Черновики луча — каждый отдельным вызовом декодера, а не одним пакетом. В модели int8 масштаб
   *  квантования активаций один на весь вход (DynamicQuantizeLinear), и в пакете вероятности
   *  черновика зависят от соседей: логиты строки сдвигаются до 0,5 (проверено 03.10). По одному —
   *  вероятности ровно как у жадного пути, но вызовов в mtBeam раз больше. */
  public static volatile boolean mtBeamSeq = false;
  /** Потоков ONNX Runtime у сессий перевода. Телефон — 4; замер на столе «как одно ядро» — 1. */
  public static volatile int mtThreads = 4;
  /** Какие направления поднимать. Приложению нужны оба; замеру одного направления (MtRun, TimeRun) — одно:
   *  на телефоне рядом с работающим приложением это вдвое меньше памяти. */
  public static volatile String[] mtDirs = {"pt2ru", "ru2pt"};
  /** Замер: время в decoder_with_past и число его вызовов (оба пути), время графов отбора и перестановки
   *  луча; оценка последнего луча — для сверки с пересчётом по выбранным кускам (bench/quality/BeamCheck.java). */
  static volatile long mtRunNs, mtSteps, mtSelNs, mtGatNs;
  double lastBeamSum, lastBeamNorm; boolean lastBeamEos;

  /** Резидентная память процесса, МБ: по ней видно, сколько стоит каждая часть при загрузке. */
  public static long rssMb() {
    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/statm"))) {
      String[] f = r.readLine().trim().split("\\s+");
      return Long.parseLong(f[1]) * android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) >> 20;
    } catch (Throwable e) { return -1; }
  }

  /** Этап загрузки — экрану, чтобы было видно, что вот-вот включится: «asr», «mt», «tts». Статическое
   *  поле, как mtVariant: сервис ставит его до конструктора. */
  public interface Stage { void at(String what); }
  public static volatile Stage stage;
  static void stage(String what) { Stage s = stage; if (s != null) try { s.at(what); } catch (Throwable ignore) {} }
  /** Распознавание и нарезка готовы, перевод ещё грузится: сервис уже режет и распознаёт то, что сказали
   *  во время загрузки (владелец 03.10). Отдаётся сам движок — из него берут только распознавание, VAD и
   *  шумодав, они уже на месте. Статическое поле, как stage: сервис ставит его до конструктора. */
  public interface Up { void at(Engine e); }
  public static volatile Up asrUp;
  /** Голоса грузит сам сервис, после остального (TranslatorService.loadTtsLate): перевод того, что сказали
   *  во время загрузки, не ждёт озвучки. Конструктор тогда голоса не трогает. */
  public static volatile boolean ttsLater = false;

  public Engine(File modelsDir, Log log) throws Exception {
    this.m = modelsDir; this.log = log;
    stage("asr");
    System.loadLibrary("onnxruntime_sherpa"); System.loadLibrary("sherpa-onnx-jni");
    long t = System.nanoTime(), r0 = rssMb();
    File multi = new File(m, "asr_multi");
    if (new File(multi, "encoder.int8.onnx").exists()) {      // parakeet: одна модель на оба языка, вдвое меньше ошибок
      asrMulti = offline(multi, 4); asrName = "parakeet";
    } else {
      File pt = new File(m, "asr_pt");
      asrPt = offline(pt, 4);
      File ru = new File(m, "asr_ru");
      OnlineTransducerModelConfig otr = OnlineTransducerModelConfig.builder().setEncoder(p(ru, "encoder.int8.onnx")).setDecoder(p(ru, "decoder.onnx")).setJoiner(p(ru, "joiner.int8.onnx")).build();
      OnlineModelConfig omc = OnlineModelConfig.builder().setTransducer(otr).setTokens(p(ru, "tokens.txt")).setNumThreads(2).setModelType("zipformer2").setDebug(false).build();
      asrRu = new OnlineRecognizer(OnlineRecognizerConfig.builder().setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build()).setOnlineModelConfig(omc).setDecodingMethod("greedy_search").setEnableEndpoint(false).build());
      asrName = "fastconformer+zipformer";
    }
    vadModelPath = p(m, "silero_vad.onnx");
    vad = buildVad(vadThreshold, vadMinSilence);
    File dn = new File(m, "denoiser");
    File[] dnf = dn.listFiles((d, n) -> n.endsWith(".onnx"));
    if (dnf != null && dnf.length > 0) {
      denoiser = new OfflineSpeechDenoiser(OfflineSpeechDenoiserConfig.builder().setModel(
          OfflineSpeechDenoiserModelConfig.builder().setGtcrn(
              OfflineSpeechDenoiserGtcrnModelConfig.builder().setModel(dnf[0].getAbsolutePath()).build())
          .setNumThreads(2).setDebug(false).build()).build());
    }
    loadAsrMs = (System.nanoTime() - t) / 1000000; log.log("ASR+VAD загружены за " + loadAsrMs + " мс (" + asrName + (denoiser != null ? ", шумоподавитель есть" : "") + ") · +" + (rssMb() - r0) + " МБ резидентно");
    Up u = asrUp; if (u != null) try { u.at(this); } catch (Throwable ignore) {}
    stage("mt");
    loadMt();
    if (ttsLater) return;
    if (withTts) { stage("tts"); loadTts(); } else log.log("TTS не загружается: модуль «Озвучка» выключен");
  }

  /** Только перевод — без распознавания, VAD и голосов: замер качества перевода на столе
   *  (bench/quality/MtRun.java) тем же кодом, что работает на телефоне. */
  static Engine mtOnly(File modelsDir, Log log) throws Exception { return new Engine(modelsDir, log, true); }
  private Engine(File modelsDir, Log log, boolean mtOnly) throws Exception { this.m = modelsDir; this.log = log; loadMt(); }

  void loadMt() throws Exception {
    long t = System.nanoTime(), rss0 = rssMb();
    env = OrtEnvironment.getEnvironment();
    boolean legacy = "legacy".equals(mtVariant); int kvDirs = 0;
    for (String d : mtDirs) {
      File md = new File(m, "mt/" + d), kv = new File(md, "encoder_kv_model.onnx");
      OrtSession.SessionOptions so = new OrtSession.SessionOptions(); so.setIntraOpNumThreads(mtThreads); so.setInterOpNumThreads(1);
      if (kv.exists() && !legacy) { kvDirs++; mt.put(d, new OrtSession[]{env.createSession(kv.getAbsolutePath(), so), env.createSession(p(md, "decoder_with_past_model.onnx"), so)}); }
      else mt.put(d, new OrtSession[]{env.createSession(p(md, "encoder_model.onnx"), so), env.createSession(p(md, "decoder_model.onnx"), so), env.createSession(p(md, "decoder_with_past_model.onnx"), so)});
      tok.put(d, new SpmTokenizer(p(md, d + "_source_pieces.tsv"), p(md, d + "_vocab.json")));
    }
    loadMtMs = (System.nanoTime() - t) / 1000000; long rss1 = rssMb();
    log.log("MT загружен за " + loadMtMs + " мс · +" + (rss1 - rss0) + " МБ резидентно, всего " + rss1 + " МБ · "
        + (kvDirs == mtDirs.length ? "две сессии на направление" : kvDirs == 0 ? "три сессии на направление" : "две сессии в одном направлении, три в другом")
        + (legacy ? " (стенд: прежний путь)" : ""));
  }

  /** Поднимать ли голоса при создании движка: модуль «Озвучка» (Modules.TTS). Статическое поле,
   *  как mtVariant: сервис ставит его до конструктора. */
  public static volatile boolean withTts = true;

  /** Голоса — отдельно от остального: модуль «Озвучка» включают и выключают на ходу. Нет файлов —
   *  не ошибка запуска, а приложение без голоса: перевод виден на экране. */
  public synchronized boolean loadTts() {
    if (ttsRu != null && ttsPt != null) return true;
    File ru = new File(m, "tts_ru"), pt = new File(m, "tts_pt");
    if (!new File(ru, "ru_RU-dmitri-medium.onnx").isFile() || !new File(pt, "pt_BR-faber-medium.onnx").isFile()) {
      log.log("TTS: файлов голоса нет — перевод только на экране"); return false;
    }
    long t = System.nanoTime(), r2 = rssMb();
    ttsRu = tts(ru, "ru_RU-dmitri-medium.onnx"); ttsPt = tts(pt, "pt_BR-faber-medium.onnx");
    loadTtsMs = (System.nanoTime() - t) / 1000000; log.log("TTS загружен за " + loadTtsMs + " мс · +" + (rssMb() - r2) + " МБ резидентно");
    return true;
  }
  /** Отдать голоса: модуль выключили. Звать, когда очередь озвучки остановлена. */
  public synchronized void releaseTts() {
    try { if (ttsRu != null) ttsRu.release(); } catch (Throwable ignore) {}
    try { if (ttsPt != null) ttsPt.release(); } catch (Throwable ignore) {}
    ttsRu = ttsPt = null;
  }
  public boolean hasTts() { return ttsRu != null && ttsPt != null; }
  static String p(File d, String n) { return new File(d, n).getAbsolutePath(); }

  /** Отдать модели. Без этого нативная память (распознавание, синтез, VAD, шесть сессий перевода —
   *  около трёх гигабайт) не возвращалась никогда: сервис останавливался, а процесс оставался в
   *  памяти кэшированным со всеми моделями, пока его не выгонит система или «очистка памяти».
   *  Звать только после остановки рабочих потоков: освобождение посреди распознавания — это
   *  обращение к уже освобождённой памяти и падение в нативном коде. */
  public volatile boolean released = false;
  public synchronized void release() {
    if (released) return;
    released = true;
    // Прямые вызовы, а не через отражение: отражение молча проглотило бы метод, которого нет,
    // и память снова не отдавалась бы — а компилятор такое ловит.
    try { if (asrPt != null) asrPt.release(); } catch (Throwable ignore) {}
    try { if (asrMulti != null) asrMulti.release(); } catch (Throwable ignore) {}
    try { if (asrRu != null) asrRu.release(); } catch (Throwable ignore) {}
    try { if (ttsRu != null) ttsRu.release(); } catch (Throwable ignore) {}
    try { if (ttsPt != null) ttsPt.release(); } catch (Throwable ignore) {}
    try { if (vad != null) vad.release(); } catch (Throwable ignore) {}
    try { if (denoiser != null) denoiser.release(); } catch (Throwable ignore) {}
    asrPt = asrMulti = null; asrRu = null; ttsRu = ttsPt = null; vad = null; denoiser = null;
    for (OrtSession[] ss : mt.values()) for (OrtSession s : ss) try { s.close(); } catch (Throwable ignore) {}
    mt.clear(); tok.clear();
  }
  static OfflineRecognizer offline(File d, int threads) {
    OfflineTransducerModelConfig tr = OfflineTransducerModelConfig.builder().setEncoder(p(d, "encoder.int8.onnx")).setDecoder(p(d, "decoder.int8.onnx")).setJoiner(p(d, "joiner.int8.onnx")).build();
    OfflineModelConfig mc = OfflineModelConfig.builder().setTransducer(tr).setTokens(p(d, "tokens.txt")).setNumThreads(threads).setModelType("nemo_transducer").setDebug(false).build();
    return new OfflineRecognizer(OfflineRecognizerConfig.builder().setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build()).setOfflineModelConfig(mc).setDecodingMethod("greedy_search").build());
  }
  static OfflineTts tts(File d, String model) {
    OfflineTtsVitsModelConfig v = OfflineTtsVitsModelConfig.builder().setModel(p(d, model)).setTokens(p(d, "tokens.txt")).setDataDir(p(d, "espeak-ng-data")).build();
    return new OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder().setVits(v).setNumThreads(4).setDebug(false).build()).build());
  }

  /** Потоковый шумодав для нарезки (DenoiseGate): очищенный звук получает только детектор речи,
   *  распознаватель слышит исходный кусок — перед распознаванием очистка вредит
   *  (results/2026-10-02-vad-denoise.md). По требованию: живёт в потоке нарезки, ему же и освобождать.
   *  Нет модели — null. */
  public OnlineSpeechDenoiser onlineDenoiser(File f) {
    if (f == null || !f.exists()) return null;
    return new OnlineSpeechDenoiser(OnlineSpeechDenoiserConfig.builder().setModel(
        OfflineSpeechDenoiserModelConfig.builder().setGtcrn(OfflineSpeechDenoiserGtcrnModelConfig.builder().setModel(f.getAbsolutePath()).build())
            .setNumThreads(1).setDebug(false).build()).build());
  }
  /** Шумоподавитель GTCRN: работает на 16 кГц, возвращает очищенный сигнал. */
  public float[] denoise(float[] s, int sr) {
    if (denoiser == null) return s;
    DenoisedAudio d = denoiser.run(s, sr);
    return d.getSamples();
  }
  /** Распознанное и время токенов: у Parakeet TDT — начало и длительность каждого (шаг 80 мс). По ним реплика
   *  режется на предложения (Clips.cuts; замер results/2026-10-03-asr-timestamps.md). Без времени (потоковая
   *  русская модель, шумодав перед распознаванием) — tokens == null. */
  public static final class Asr {
    public final String text; public final String[] tokens; public final float[] times, durs;
    Asr(String text, String[] tokens, float[] times, float[] durs) { this.text = text; this.tokens = tokens; this.times = times; this.durs = durs; }
  }
  public Asr asrFull(String lang, float[] s, int sr) {
    OfflineRecognizer off = asrMulti != null ? asrMulti : (lang.equals("pt") ? asrPt : null);
    if (off == null || denoiseOn && denoiser != null) return new Asr(asr(lang, s, sr), null, null, null);
    OfflineStream st = off.createStream(); st.acceptWaveform(s, sr); off.decode(st);
    OfflineRecognizerResult r = off.getResult(st); st.release();
    return new Asr(r.getText().trim(), r.getTokens(), r.getTimestamps(), r.getDurations());
  }
  public String asr(String lang, float[] s, int sr) {
    if (denoiseOn && denoiser != null) { float[] c = denoise(s, sr); if (c != null && c.length > 0) { s = c; sr = denoiser.getSampleRate(); } }
    OfflineRecognizer off = asrMulti != null ? asrMulti : (lang.equals("pt") ? asrPt : null);
    if (off != null) { OfflineStream st = off.createStream(); st.acceptWaveform(s, sr); off.decode(st); String r = off.getResult(st).getText().trim(); st.release(); return r; }
    OnlineStream st = asrRu.createStream(); st.acceptWaveform(s, sr); st.acceptWaveform(new float[sr / 2], sr); st.inputFinished();
    while (asrRu.isReady(st)) asrRu.decode(st);
    String r = asrRu.getResult(st).getText().trim(); st.release(); return r;
  }

  public String translate(String dir, String text) throws Exception {
    SpmTokenizer tk = tok.get(dir); OrtSession[] s = mt.get(dir);
    long start = tk.vocab.get("<pad>"), eos = tk.vocab.get("</s>"); String lt = dir.equals("pt2ru") ? ">>rus<<" : null;
    StringBuilder out = new StringBuilder();
    for (String sent : text.split("(?<=[.!?…])\\s+(?=\\S)")) {
      if (sent.trim().isEmpty()) continue;
      List<Long> ids = tk.encode(sent, lt);
      int beam = mtBeam;
      List<Long> o = s.length == 3 ? greedy(s[0], s[1], s[2], ids, start, eos, 64)
          : beam > 1 ? beamKv(s[0], s[1], ids, start, eos, 64, beam) : greedyKv(s[0], s[1], ids, start, eos, 64);
      if (out.length() > 0) out.append(' '); out.append(tk.decode(o));
    }
    return out.toString();
  }

  /* Тензоры ORT в Java сами не освобождаются: ни у тензора, ни у результата нет очистки при сборке
   * мусора. Раньше логиты каждого шага (словарь 61 тыс. × 4 байта = 240 КБ) и номер токена на входе
   * оставались в нативной памяти навсегда — 6–8 МБ на реплику, на телефоне +197 МБ за 30 реплик, —
   * пока длинный разговор не загонял телефон в нехватку памяти. Поэтому каждый тензор, который здесь
   * создаётся или приходит в результате, закрывается явно, и в том числе при ошибке. */

  /** Прежний путь: первый шаг — decoder_model (он же считает K/V перекрёстного внимания), дальше —
   *  decoder_with_past. Прошлое первого шага принадлежит его результату, который не закрывается
   *  целиком: закрываются сами тензоры — логиты сразу, прошлое по мере замены и в конце. */
  List<Long> greedy(OrtSession enc, OrtSession dec, OrtSession decP, List<Long> ids, long start, long eos, int maxNew) throws Exception {
    long[][] in = new long[1][ids.size()], mask = new long[1][ids.size()];
    for (int k = 0; k < ids.size(); k++) { in[0][k] = ids.get(k); mask[0][k] = 1; }
    OnnxTensor tIn = OnnxTensor.createTensor(env, in), tMask = OnnxTensor.createTensor(env, mask);
    Map<String, OnnxTensor> past = new HashMap<>(); OrtSession.Result er = null;
    try {
      Map<String, OnnxTensor> ei = new HashMap<>(); ei.put("input_ids", tIn); ei.put("attention_mask", tMask);
      er = enc.run(ei); OnnxTensor hidden = (OnnxTensor) er.get(0);
      OnnxTensor first = OnnxTensor.createTensor(env, new long[][]{{start}});
      OrtSession.Result r;
      try {
        Map<String, OnnxTensor> di = new HashMap<>(); di.put("encoder_attention_mask", tMask); di.put("encoder_hidden_states", hidden); di.put("input_ids", first);
        r = dec.run(di);
      } finally { first.close(); }
      for (int l = 0; l < LAYERS; l++) for (String kv : new String[]{"key", "value"}) {
        past.put("past_key_values." + l + ".decoder." + kv, (OnnxTensor) r.get("present." + l + ".decoder." + kv).get());
        past.put("past_key_values." + l + ".encoder." + kv, (OnnxTensor) r.get("present." + l + ".encoder." + kv).get());
      }
      long next = argmaxClose((OnnxTensor) r.get(0)); List<Long> outIds = new ArrayList<>();
      while (next != eos && outIds.size() < maxNew) {
        outIds.add(next);
        next = step(decP, past, tMask, next);
      }
      return outIds;
    } finally {
      for (OnnxTensor pt : past.values()) pt.close();
      tIn.close(); tMask.close(); if (er != null) er.close();
    }
  }

  /** Новый путь: кодировщик сразу отдаёт K/V перекрёстного внимания, все шаги делает
   *  decoder_with_past, первый — с пустым прошлым декодера [1, 16, 0, 64]. На столе сверено с
   *  прежним путём на 600 фразах: те же токены, логиты первого шага совпадают до бита. */
  List<Long> greedyKv(OrtSession enc, OrtSession decP, List<Long> ids, long start, long eos, int maxNew) throws Exception {
    long[][] in = new long[1][ids.size()], mask = new long[1][ids.size()];
    for (int k = 0; k < ids.size(); k++) { in[0][k] = ids.get(k); mask[0][k] = 1; }
    OnnxTensor tIn = OnnxTensor.createTensor(env, in), tMask = OnnxTensor.createTensor(env, mask);
    Map<String, OnnxTensor> past = new HashMap<>(); OrtSession.Result er = null;
    try {
      Map<String, OnnxTensor> ei = new HashMap<>(); ei.put("input_ids", tIn); ei.put("attention_mask", tMask);
      er = enc.run(ei);
      for (int l = 0; l < LAYERS; l++) for (String kv : new String[]{"key", "value"}) {
        past.put("past_key_values." + l + ".encoder." + kv, (OnnxTensor) er.get("present." + l + ".encoder." + kv).get());   // принадлежат er
        past.put("past_key_values." + l + ".decoder." + kv, OnnxTensor.createTensor(env, java.nio.FloatBuffer.allocate(0), new long[]{1, HEADS, 0, HEAD_DIM}));
      }
      List<Long> outIds = new ArrayList<>();
      long next = step(decP, past, tMask, start);
      while (next != eos && outIds.size() < maxNew) {
        outIds.add(next);
        next = step(decP, past, tMask, next);
      }
      return outIds;
    } finally {
      for (Map.Entry<String, OnnxTensor> e : past.entrySet()) if (e.getKey().contains(".decoder.")) e.getValue().close();
      tIn.close(); tMask.close(); if (er != null) er.close();   // K/V кодировщика закрывает свой результат
    }
  }

  /** Шаг декодера с кэшем: прошлое декодера заменяется новым (старое закрывается), логиты и номер
   *  токена на входе закрываются сразу. Возвращает следующий токен. */
  long step(OrtSession decP, Map<String, OnnxTensor> past, OnnxTensor tMask, long token) throws Exception {
    OnnxTensor tok = OnnxTensor.createTensor(env, new long[][]{{token}});
    OrtSession.Result pr; long t0 = System.nanoTime();
    try {
      Map<String, OnnxTensor> pi = new HashMap<>(past); pi.put("encoder_attention_mask", tMask); pi.put("input_ids", tok);
      pr = decP.run(pi);
    } finally { tok.close(); }
    mtRunNs += System.nanoTime() - t0; mtSteps++;
    for (int l = 0; l < LAYERS; l++) for (String kv : new String[]{"key", "value"}) {
      String k = "past_key_values." + l + ".decoder." + kv;
      past.get(k).close(); past.put(k, (OnnxTensor) pr.get("present." + l + ".decoder." + kv).get());
    }
    return argmaxClose((OnnxTensor) pr.get(0));
  }
  static long argmaxClose(OnnxTensor logits) throws OrtException { try { return argmax(logits); } finally { logits.close(); } }
  static long argmax(OnnxTensor logits) throws OrtException { float[][][] v = (float[][][]) logits.getValue(); float[] row = v[0][v[0].length - 1]; int b = 0; for (int i = 1; i < row.length; i++) if (row[i] > row[b]) b = i; return b; }

  /** Перебор вариантов (луч) на пути из двух сессий. Черновики идут одним пакетом через
   *  decoder_with_past или, при mtBeamSeq, каждый своим вызовом с прошлым из своей строки пакета (та
   *  же перестановка прошлого, поэтому сверка по одному проверяет и её). Отбор и перестановка — графами
   *  BeamOps в ONNX Runtime: на шаге у каждого черновика 2·beam лучших кусков с логвероятностями
   *  (<pad> — начало декодера, его не выбираем), из них по сумме — 2·beam лучших продолжений. Конец
   *  фразы среди первых beam — готовый вариант с оценкой «сумма / длина^mtLp» (конец фразы входит в
   *  длину); остальные по порядку — черновики следующего шага, пока их не наберётся beam. Готовых beam —
   *  стоп (как у CTranslate2 с patience 1 и у HF с early_stopping); на maxNew готовыми становятся и
   *  недописанные. Ширина 1 — ровно жадный путь: сверка в bench/quality/BeamCheck.java. */
  List<Long> beamKv(OrtSession enc, OrtSession decP, List<Long> ids, long start, long eos, int maxNew, int beam) throws Exception {
    beamOps();
    final int S = ids.size(), K = 2 * beam; final boolean seq = mtBeamSeq;
    long[][] in = new long[1][S], mask = new long[1][S];
    for (int k = 0; k < S; k++) { in[0][k] = ids.get(k); mask[0][k] = 1; }
    OnnxTensor tIn = OnnxTensor.createTensor(env, in), tMask = OnnxTensor.createTensor(env, mask), tMaskB = null;
    OnnxTensor tK = OnnxTensor.createTensor(env, new long[]{K});
    Map<Integer, OnnxTensor[]> ban = new HashMap<>();   // по размеру пакета: какой кусок запретить (<pad>) и чем (−∞)
    Map<String, OnnxTensor> enc1 = new HashMap<>(), encB = new HashMap<>(), dec = new HashMap<>(); OrtSession.Result er = null;
    try {
      Map<String, OnnxTensor> ei = new HashMap<>(); ei.put("input_ids", tIn); ei.put("attention_mask", tMask);
      er = enc.run(ei);
      for (int i = 0; i < DEC_KEYS.length; i++) {
        enc1.put(ENC_KEYS[i], (OnnxTensor) er.get(ENC_KEYS[i].replace("past_key_values.", "present.")).get());   // принадлежат er
        dec.put(DEC_KEYS[i], OnnxTensor.createTensor(env, java.nio.FloatBuffer.allocate(0), new long[]{1, HEADS, 0, HEAD_DIM}));
      }
      List<long[]> seqs = new ArrayList<>(); seqs.add(new long[0]); double[] sc = {0};
      List<long[]> done = new ArrayList<>(); List<double[]> doneSc = new ArrayList<>();   // {сумма, оценка, 1 — с концом фразы}
      double[] cs = new double[K]; int[] cr = new int[K], ct = new int[K];
      for (int t = 0; ; t++) {
        int b = seqs.size();
        if (t == maxNew) {   // недописанные — тоже варианты
          for (int r = 0; r < b; r++) { done.add(seqs.get(r)); doneSc.add(new double[]{sc[r], sc[r] / Math.pow(Math.max(1, t), mtLp), 0}); }
          break;
        }
        long[] last = new long[b];
        for (int r = 0; r < b; r++) { long[] q = seqs.get(r); last[r] = q.length == 0 ? start : q[q.length - 1]; }
        if (b > 1 && !seq && encB.isEmpty()) {
          encB = gatherOrt(enc1, ENC_KEYS, new long[b]);   // индексы — нули: b одинаковых строк
          long[][] mb = new long[b][S]; for (long[] row : mb) Arrays.fill(row, 1);
          tMaskB = OnnxTensor.createTensor(env, mb);
        }
        OnnxTensor lg;
        if (b > 1 && seq) { float[] a = stepRows(decP, dec, enc1, tMask, last); lg = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(a), new long[]{b, 1, a.length / b}); }
        else lg = stepBatchT(decP, dec, b == 1 ? enc1 : encB, b == 1 ? tMask : tMaskB, last);
        OnnxTensor[] bn = ban.get(b);
        if (bn == null) {
          long[][] pad = new long[b][1]; float[][] neg = new float[b][1];
          for (int r = 0; r < b; r++) { pad[r][0] = start; neg[r][0] = Float.NEGATIVE_INFINITY; }
          bn = new OnnxTensor[]{OnnxTensor.createTensor(env, pad), OnnxTensor.createTensor(env, neg)}; ban.put(b, bn);
        }
        long[][] ix; float[][] lp; long t1 = System.nanoTime();
        try {
          Map<String, OnnxTensor> si = new HashMap<>(); si.put("logits", lg); si.put("pad", bn[0]); si.put("neg", bn[1]); si.put("k", tK);
          try (OrtSession.Result sr = beamSel.run(si)) { ix = (long[][]) sr.get("ix").get().getValue(); lp = (float[][]) sr.get("lp").get().getValue(); }
        } finally { lg.close(); }
        mtSelNs += System.nanoTime() - t1;
        int n = 0;
        for (int r = 0; r < b; r++) for (int j = 0; j < K; j++) {   // строка — по убыванию: дальше не лучше
          double s = sc[r] + lp[r][j];
          if (n == K && s <= cs[K - 1]) break;   // при равенстве — раньше в словаре, как argmax
          int i = n < K ? n++ : K - 1;
          for (; i > 0 && cs[i - 1] < s; i--) { cs[i] = cs[i - 1]; cr[i] = cr[i - 1]; ct[i] = ct[i - 1]; }
          cs[i] = s; cr[i] = r; ct[i] = (int) ix[r][j];
        }
        List<long[]> ns = new ArrayList<>(); double[] nsc = new double[beam]; int[] par = new int[beam];
        for (int i = 0; i < n && ns.size() < beam; i++) {
          long[] q = seqs.get(cr[i]);
          if (ct[i] == eos) { if (i < beam) { done.add(q); doneSc.add(new double[]{cs[i], cs[i] / Math.pow(q.length + 1, mtLp), 1}); } continue; }
          long[] q2 = Arrays.copyOf(q, q.length + 1); q2[q.length] = ct[i];
          par[ns.size()] = cr[i]; nsc[ns.size()] = cs[i]; ns.add(q2);
        }
        if (done.size() >= beam || ns.isEmpty()) break;
        reorder(dec, par, ns.size());
        seqs = ns; sc = Arrays.copyOf(nsc, ns.size());
      }
      int best = 0;
      for (int i = 1; i < done.size(); i++) if (doneSc.get(i)[1] > doneSc.get(best)[1]) best = i;
      double[] bs = doneSc.get(best); lastBeamSum = bs[0]; lastBeamNorm = bs[1]; lastBeamEos = bs[2] == 1;
      List<Long> out = new ArrayList<>(); for (long x : done.get(best)) out.add(x);
      return out;
    } finally {
      for (OnnxTensor x : dec.values()) x.close();
      for (OnnxTensor x : encB.values()) x.close();
      for (OnnxTensor[] x : ban.values()) { x[0].close(); x[1].close(); }
      if (tMaskB != null) tMaskB.close();
      tK.close(); tIn.close(); tMask.close(); if (er != null) er.close();   // K/V кодировщика на одну строку закрывает свой результат
    }
  }

  /** Сессии графов BeamOps — при первом луче: жадному пути они не нужны. Один поток: графы крошечные, а
   *  свои пулы по mtThreads на телефоне толкались с пулом декодера на тех же ядрах (ширина 4 с четырьмя
   *  потоками — ×1,7 к жадному, с одним — ×1,3; Redmi, 03.10). */
  OrtSession beamSel, beamGat;
  synchronized void beamOps() throws OrtException {
    if (beamSel != null) return;
    OrtSession.SessionOptions so = new OrtSession.SessionOptions(); so.setIntraOpNumThreads(1); so.setInterOpNumThreads(1);
    beamGat = env.createSession(BeamOps.bytes(BeamOps.GATHER), so);
    beamSel = env.createSession(BeamOps.bytes(BeamOps.SELECT), so);
  }
  static final String[] DEC_KEYS = new String[2 * LAYERS], ENC_KEYS = new String[2 * LAYERS];
  static {
    for (int l = 0; l < LAYERS; l++) for (int i = 0; i < 2; i++) {
      String kv = i == 0 ? "key" : "value";
      DEC_KEYS[2 * l + i] = "past_key_values." + l + ".decoder." + kv; ENC_KEYS[2 * l + i] = "past_key_values." + l + ".encoder." + kv;
    }
  }

  /** Шаг пакета черновиков: прошлое декодера заменяется новым (старое закрывается); логиты [b, 1, V] —
   *  тензором, его закрывает вызывающий. */
  OnnxTensor stepBatchT(OrtSession decP, Map<String, OnnxTensor> dec, Map<String, OnnxTensor> encKv, OnnxTensor mask, long[] last) throws Exception {
    long[][] t = new long[last.length][1];
    for (int i = 0; i < last.length; i++) t[i][0] = last[i];
    OnnxTensor tok = OnnxTensor.createTensor(env, t);
    OrtSession.Result pr; long t0 = System.nanoTime();
    try {
      Map<String, OnnxTensor> pi = new HashMap<>(dec); pi.putAll(encKv); pi.put("encoder_attention_mask", mask); pi.put("input_ids", tok);
      pr = decP.run(pi);
    } finally { tok.close(); }
    mtRunNs += System.nanoTime() - t0; mtSteps++;
    for (String k : DEC_KEYS) { dec.get(k).close(); dec.put(k, (OnnxTensor) pr.get(k.replace("past_key_values.", "present.")).get()); }
    return (OnnxTensor) pr.get(0);
  }
  /** То же, логиты — плоским массивом. */
  float[] stepBatch(OrtSession decP, Map<String, OnnxTensor> dec, Map<String, OnnxTensor> encKv, OnnxTensor mask, long[] last) throws Exception {
    OnnxTensor lg = stepBatchT(decP, dec, encKv, mask, last);
    try { java.nio.FloatBuffer fb = lg.getFloatBuffer(); float[] a = new float[fb.remaining()]; fb.get(a); return a; }
    finally { lg.close(); }
  }

  /** Прошлое декодера под новых родителей: строка j нового пакета — строка par[j] прежнего. */
  void reorder(Map<String, OnnxTensor> dec, int[] par, int nb) throws OrtException {
    long b0 = dec.get(DEC_KEYS[0]).getInfo().getShape()[0];
    boolean same = nb == b0;
    for (int j = 0; same && j < nb; j++) same = par[j] == j;
    if (same) return;
    long[] idx = new long[nb]; for (int j = 0; j < nb; j++) idx[j] = par[j];
    Map<String, OnnxTensor> g = gatherOrt(dec, DEC_KEYS, idx);
    for (String k : DEC_KEYS) { dec.get(k).close(); dec.put(k, g.get(k)); }
  }

  /** Строки idx каждого из 12 тензоров keys — графом BeamOps.GATHER; новые тензоры отдаются вызывающему
   *  и закрываются по одному, как прошлое из декодера. */
  Map<String, OnnxTensor> gatherOrt(Map<String, OnnxTensor> src, String[] keys, long[] idx) throws OrtException {
    OnnxTensor ti = OnnxTensor.createTensor(env, idx);
    try {
      Map<String, OnnxTensor> gi = new HashMap<>(); gi.put("idx", ti);
      for (int i = 0; i < keys.length; i++) gi.put("p" + i, src.get(keys[i]));
      long t0 = System.nanoTime();
      OrtSession.Result gr = beamGat.run(gi);
      mtGatNs += System.nanoTime() - t0;
      Map<String, OnnxTensor> out = new HashMap<>();
      for (int i = 0; i < keys.length; i++) out.put(keys[i], (OnnxTensor) gr.get("g" + i).get());
      return out;
    } finally { ti.close(); }
  }

  /** Новые тензоры из строк par[0..nb) каждого тензора пакета [b, H, T, D] — на Java; исходные не трогаются.
   *  Только для черновиков по одному (stepRows): строка в отдельный вызов. */
  Map<String, OnnxTensor> gather(Map<String, OnnxTensor> dec, int[] par, int nb) throws OrtException {
    Map<String, OnnxTensor> out = new HashMap<>();
    try {
      for (Map.Entry<String, OnnxTensor> e : dec.entrySet()) {
        OnnxTensor o = e.getValue(); long[] sh = o.getInfo().getShape();
        int row = (int) (sh[1] * sh[2] * sh[3]);
        float[] a = new float[(int) sh[0] * row]; o.getFloatBuffer().get(a);
        float[] z = new float[nb * row];
        for (int j = 0; j < nb; j++) System.arraycopy(a, par[j] * row, z, j * row, row);
        out.put(e.getKey(), OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(z), new long[]{nb, sh[1], sh[2], sh[3]}));
      }
    } catch (OrtException ex) { for (OnnxTensor x : out.values()) x.close(); throw ex; }
    return out;
  }

  /** Шаг пакета черновиков по одному (mtBeamSeq): строка r прошлого — отдельным вызовом с K/V
   *  кодировщика на одну строку, новое прошлое собирается обратно в пакет. Логиты — как у stepBatch. */
  float[] stepRows(OrtSession decP, Map<String, OnnxTensor> dec, Map<String, OnnxTensor> enc1, OnnxTensor mask1, long[] last) throws Exception {
    int b = last.length; float[][] rows = new float[b][]; List<Map<String, OnnxTensor>> one = new ArrayList<>();
    try {
      for (int r = 0; r < b; r++) {
        Map<String, OnnxTensor> p = gather(dec, new int[]{r}, 1); one.add(p);
        rows[r] = stepBatch(decP, p, enc1, mask1, new long[]{last[r]});   // в p теперь новое прошлое строки
      }
      for (Map.Entry<String, OnnxTensor> e : dec.entrySet()) {
        long[] sh = one.get(0).get(e.getKey()).getInfo().getShape(); int row = (int) (sh[1] * sh[2] * sh[3]);
        float[] z = new float[b * row];
        for (int r = 0; r < b; r++) one.get(r).get(e.getKey()).getFloatBuffer().get(z, r * row, row);
        OnnxTensor nt = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(z), new long[]{b, sh[1], sh[2], sh[3]});
        e.getValue().close(); e.setValue(nt);
      }
    } finally { for (Map<String, OnnxTensor> p : one) for (OnnxTensor x : p.values()) x.close(); }
    int V = rows[0].length; float[] lg = new float[b * V];
    for (int r = 0; r < b; r++) System.arraycopy(rows[r], 0, lg, r * V, V);
    return lg;
  }

  /** Сумма логвероятностей кусков out (и конца фразы, если eosToo) при исходнике ids: тот же
   *  decoder_with_past по одному куску, полный log-softmax. Только для сверки луча (BeamCheck). */
  double scoreKv(OrtSession enc, OrtSession decP, List<Long> ids, List<Long> out, long start, long eos, boolean eosToo) throws Exception {
    int S = ids.size();
    long[][] in = new long[1][S], mask = new long[1][S];
    for (int k = 0; k < S; k++) { in[0][k] = ids.get(k); mask[0][k] = 1; }
    OnnxTensor tIn = OnnxTensor.createTensor(env, in), tMask = OnnxTensor.createTensor(env, mask);
    Map<String, OnnxTensor> enc1 = new HashMap<>(), dec = new HashMap<>(); OrtSession.Result er = null;
    try {
      Map<String, OnnxTensor> ei = new HashMap<>(); ei.put("input_ids", tIn); ei.put("attention_mask", tMask);
      er = enc.run(ei);
      for (int l = 0; l < LAYERS; l++) for (String kv : new String[]{"key", "value"}) {
        enc1.put("past_key_values." + l + ".encoder." + kv, (OnnxTensor) er.get("present." + l + ".encoder." + kv).get());
        dec.put("past_key_values." + l + ".decoder." + kv, OnnxTensor.createTensor(env, java.nio.FloatBuffer.allocate(0), new long[]{1, HEADS, 0, HEAD_DIM}));
      }
      double sum = 0; long prev = start;
      for (int i = 0; i < out.size() + (eosToo ? 1 : 0); i++) {
        long want = i < out.size() ? out.get(i) : eos;
        float[] lg = stepBatch(decP, dec, enc1, tMask, new long[]{prev});
        float mx = Float.NEGATIVE_INFINITY; for (float x : lg) if (x > mx) mx = x;
        double z = 0; for (float x : lg) z += Math.exp(x - mx);
        sum += lg[(int) want] - mx - Math.log(z); prev = want;
      }
      return sum;
    } finally {
      for (OnnxTensor x : dec.values()) x.close();
      tIn.close(); tMask.close(); if (er != null) er.close();
    }
  }

  public GeneratedAudio speak(String lang, String text, OfflineTtsCallback cb) { return (lang.equals("ru") ? ttsRu : ttsPt).generateWithCallback(text, 0, 1.0f, cb); }
  public int ttsSampleRate(String lang) { return (lang.equals("ru") ? ttsRu : ttsPt).getSampleRate(); }
}
