package dev.agenttranslator;

import ai.onnxruntime.*;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.util.*;

/** ASR (sherpa-onnx) + MT (OPUS-MT int8 via ONNX Runtime, greedy, KV-cache) + TTS (Piper) + VAD (silero). Один процесс. */
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
  static final int LAYERS = 6;
  final File m; final Log log;
  public OfflineRecognizer asrPt, asrMulti; public OnlineRecognizer asrRu; public OfflineTts ttsRu, ttsPt; public Vad vad;
  public OfflineSpeechDenoiser denoiser; public volatile boolean denoiseOn = false;
  public String asrName = "";
  OrtEnvironment env; final Map<String, OrtSession[]> mt = new HashMap<>(); final Map<String, SpmTokenizer> tok = new HashMap<>();
  public long loadAsrMs, loadMtMs, loadTtsMs;

  public Engine(File modelsDir, Log log) throws Exception {
    this.m = modelsDir; this.log = log;
    System.loadLibrary("onnxruntime_sherpa"); System.loadLibrary("sherpa-onnx-jni");
    long t = System.nanoTime();
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
    loadAsrMs = (System.nanoTime() - t) / 1000000; log.log("ASR+VAD загружены за " + loadAsrMs + " мс (" + asrName + (denoiser != null ? ", шумоподавитель есть" : "") + ")");
    t = System.nanoTime();
    env = OrtEnvironment.getEnvironment();
    for (String d : new String[]{"pt2ru", "ru2pt"}) {
      File md = new File(m, "mt/" + d);
      OrtSession.SessionOptions so = new OrtSession.SessionOptions(); so.setIntraOpNumThreads(4); so.setInterOpNumThreads(1);
      mt.put(d, new OrtSession[]{env.createSession(p(md, "encoder_model.onnx"), so), env.createSession(p(md, "decoder_model.onnx"), so), env.createSession(p(md, "decoder_with_past_model.onnx"), so)});
      tok.put(d, new SpmTokenizer(p(md, d + "_source_pieces.tsv"), p(md, d + "_vocab.json")));
    }
    loadMtMs = (System.nanoTime() - t) / 1000000; log.log("MT загружен за " + loadMtMs + " мс");
    t = System.nanoTime();
    ttsRu = tts(new File(m, "tts_ru"), "ru_RU-dmitri-medium.onnx"); ttsPt = tts(new File(m, "tts_pt"), "pt_BR-faber-medium.onnx");
    loadTtsMs = (System.nanoTime() - t) / 1000000; log.log("TTS загружен за " + loadTtsMs + " мс");
  }
  static String p(File d, String n) { return new File(d, n).getAbsolutePath(); }
  static OfflineRecognizer offline(File d, int threads) {
    OfflineTransducerModelConfig tr = OfflineTransducerModelConfig.builder().setEncoder(p(d, "encoder.int8.onnx")).setDecoder(p(d, "decoder.int8.onnx")).setJoiner(p(d, "joiner.int8.onnx")).build();
    OfflineModelConfig mc = OfflineModelConfig.builder().setTransducer(tr).setTokens(p(d, "tokens.txt")).setNumThreads(threads).setModelType("nemo_transducer").setDebug(false).build();
    return new OfflineRecognizer(OfflineRecognizerConfig.builder().setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build()).setOfflineModelConfig(mc).setDecodingMethod("greedy_search").build());
  }
  static OfflineTts tts(File d, String model) {
    OfflineTtsVitsModelConfig v = OfflineTtsVitsModelConfig.builder().setModel(p(d, model)).setTokens(p(d, "tokens.txt")).setDataDir(p(d, "espeak-ng-data")).build();
    return new OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder().setVits(v).setNumThreads(4).setDebug(false).build()).build());
  }

  /** Шумоподавитель GTCRN: работает на 16 кГц, возвращает очищенный сигнал. */
  public float[] denoise(float[] s, int sr) {
    if (denoiser == null) return s;
    DenoisedAudio d = denoiser.run(s, sr);
    return d.getSamples();
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
      List<Long> o = greedy(s[0], s[1], s[2], tk.encode(sent, lt), start, eos, 64);
      if (out.length() > 0) out.append(' '); out.append(tk.decode(o));
    }
    return out.toString();
  }

  List<Long> greedy(OrtSession enc, OrtSession dec, OrtSession decP, List<Long> ids, long start, long eos, int maxNew) throws Exception {
    long[][] in = new long[1][ids.size()], mask = new long[1][ids.size()];
    for (int k = 0; k < ids.size(); k++) { in[0][k] = ids.get(k); mask[0][k] = 1; }
    OnnxTensor tIn = OnnxTensor.createTensor(env, in), tMask = OnnxTensor.createTensor(env, mask);
    Map<String, OnnxTensor> ei = new HashMap<>(); ei.put("input_ids", tIn); ei.put("attention_mask", tMask);
    OrtSession.Result er = enc.run(ei); OnnxTensor hidden = (OnnxTensor) er.get(0);
    Map<String, OnnxTensor> di = new HashMap<>(); di.put("encoder_attention_mask", tMask); di.put("encoder_hidden_states", hidden); di.put("input_ids", OnnxTensor.createTensor(env, new long[][]{{start}}));
    OrtSession.Result r = dec.run(di); Map<String, OnnxTensor> past = new HashMap<>();
    for (int l = 0; l < LAYERS; l++) for (String kv : new String[]{"key", "value"}) {
      past.put("past_key_values." + l + ".decoder." + kv, (OnnxTensor) r.get("present." + l + ".decoder." + kv).get());
      past.put("past_key_values." + l + ".encoder." + kv, (OnnxTensor) r.get("present." + l + ".encoder." + kv).get());
    }
    long next = argmax((OnnxTensor) r.get(0)); List<Long> outIds = new ArrayList<>();
    while (next != eos && outIds.size() < maxNew) {
      outIds.add(next);
      Map<String, OnnxTensor> pi = new HashMap<>(past); pi.put("encoder_attention_mask", tMask); pi.put("input_ids", OnnxTensor.createTensor(env, new long[][]{{next}}));
      OrtSession.Result pr = decP.run(pi);
      for (int l = 0; l < LAYERS; l++) for (String kv : new String[]{"key", "value"}) { String k = "past_key_values." + l + ".decoder." + kv; past.get(k).close(); past.put(k, (OnnxTensor) pr.get("present." + l + ".decoder." + kv).get()); }
      next = argmax((OnnxTensor) pr.get(0));
    }
    for (OnnxTensor pt : past.values()) pt.close(); tIn.close(); tMask.close(); er.close(); return outIds;
  }
  static long argmax(OnnxTensor logits) throws OrtException { float[][][] v = (float[][][]) logits.getValue(); float[] row = v[0][v[0].length - 1]; int b = 0; for (int i = 1; i < row.length; i++) if (row[i] > row[b]) b = i; return b; }

  public GeneratedAudio speak(String lang, String text, OfflineTtsCallback cb) { return (lang.equals("ru") ? ttsRu : ttsPt).generateWithCallback(text, 0, 1.0f, cb); }
  public int ttsSampleRate(String lang) { return (lang.equals("ru") ? ttsRu : ttsPt).getSampleRate(); }
}
