package dev.agenttranslator;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Распознавание parakeet-tdt-0.6b-v3 с кодировщиком на NPU Snapdragon (ONNX Runtime с QNN).
 *
 *  Путь тот же, что прошёл восемь телефонов AI Hub (results/2026-11-08-npu-aihub.md): признаки nemo128.onnx на
 *  процессоре; окно 832 кадра (8,3 с), после речи нули; кодировщик QDQ w8a16 (bench/npu/app_model.py) на NPU;
 *  затем жадный TDT на процессоре теми же decoder.int8 и joiner.int8, что у sherpa-onnx, и по его правилам
 *  (v1.13.8, offline-transducer-greedy-search-nemo-decoder.cc: до 5 токенов на кадр, шаг — argmax хвоста
 *  выхода, кадр 0,08 с; токены — tokens.txt, «▁» в начале — пробел). Фраза длиннее окна — null: её распознаёт
 *  sherpa на процессоре, как раньше.
 *
 *  Граф QNN собирается при первом запуске (десятки секунд) и сохраняется в ctx (ONNX с готовым контекстом,
 *  ep.context_*): дальше сессия поднимается из него. Контекст привязан к поколению NPU и версии QNN — если он
 *  не грузится (перенесли на другой телефон, обновили библиотеки), он удаляется и граф собирается заново.
 *  Без Android: на ПК тот же класс работает на процессоре (qnn = null) — bench/npu/npu_asr_pc.sh. */
final class NpuAsr implements AutoCloseable {
  static final int F = 832, MEL = 128, ENC = 1024, MAX_PER_FRAME = 5;
  static final float FRAME_S = 0.08f;

  static final class Result {
    final String text; final String[] tokens; final float[] times, durs, logProbs;
    Result(String text, String[] tokens, float[] times, float[] durs, float[] logProbs) {
      this.text = text; this.tokens = tokens; this.times = times; this.durs = durs; this.logProbs = logProbs;
    }
  }

  final OrtEnvironment env = OrtEnvironment.getEnvironment();
  final OrtSession pre, enc, dec, join;
  final String[] sym;
  final int blank;
  /** Как поднят кодировщик: «qnn», «qnn-ctx» (из сохранённого контекста) или «cpu». */
  final String how;

  /** asrDir — модели parakeet приложения (decoder.int8.onnx, joiner.int8.onnx, tokens.txt); npuDir — nemo128.onnx и
   *  encoder-npu.onnx; qnn — параметры QNN (null — процессор); ctx — куда сохранить собранный граф (null — не сохранять). */
  NpuAsr(File asrDir, File npuDir, Map<String, String> qnn, File ctx) throws OrtException, IOException {
    OrtSession.SessionOptions cpu = new OrtSession.SessionOptions();
    cpu.setIntraOpNumThreads(1);
    pre = env.createSession(new File(npuDir, "nemo128.onnx").getPath(), cpu);
    dec = env.createSession(new File(asrDir, "decoder.int8.onnx").getPath(), cpu);
    join = env.createSession(new File(asrDir, "joiner.int8.onnx").getPath(), cpu);
    sym = tokens(new File(asrDir, "tokens.txt"));
    blank = sym.length - 1;
    String model = new File(npuDir, "encoder-npu.onnx").getPath();
    OrtSession e = null; String h = "cpu";
    if (qnn == null) {
      e = env.createSession(model, cpu);
    } else {
      if (ctx != null && ctx.exists()) {
        try { e = env.createSession(ctx.getPath(), qnnOptions(qnn, null)); h = "qnn-ctx"; }
        catch (OrtException x) { ctx.delete(); }
      }
      if (e == null) { e = env.createSession(model, qnnOptions(qnn, ctx)); h = "qnn"; }
    }
    enc = e; how = h;
  }

  static OrtSession.SessionOptions qnnOptions(Map<String, String> qnn, File saveCtx) throws OrtException {
    OrtSession.SessionOptions so = new OrtSession.SessionOptions();
    so.addQnn(qnn);
    if (saveCtx != null) {
      saveCtx.getParentFile().mkdirs();
      so.addConfigEntry("ep.context_enable", "1");
      so.addConfigEntry("ep.context_file_path", saveCtx.getPath());
      so.addConfigEntry("ep.context_embed_mode", "1");
    }
    return so;
  }

  /** tokens.txt sherpa-onnx: «символ номер» в строке; «▁» в начале символа — пробел (SymbolTable::operator[]). */
  static String[] tokens(File f) throws IOException {
    List<String> out = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
      for (String line; (line = r.readLine()) != null; ) {
        if (line.isEmpty()) continue;
        int sp = line.lastIndexOf(' ');
        String s = line.substring(0, sp); int id = Integer.parseInt(line.substring(sp + 1).trim());
        while (out.size() <= id) out.add("");
        out.set(id, s.startsWith("▁") ? " " + s.substring(1) : s);
      }
    }
    return out.toArray(new String[0]);
  }

  /** Распознать фразу (16 кГц). null — длиннее окна NPU (832 кадра признаков). */
  synchronized Result run(float[] s) throws OrtException {
    float[] x = new float[MEL * F]; long T;
    try (OnnxTensor w = OnnxTensor.createTensor(env, FloatBuffer.wrap(s), new long[]{1, s.length});
         OnnxTensor wl = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{s.length}), new long[]{1});
         OrtSession.Result r = pre.run(map("waveforms", w, "waveforms_lens", wl))) {
      T = ((long[]) r.get(1).getValue())[0];
      if (T > F) return null;
      FloatBuffer f = ((OnnxTensor) r.get(0)).getFloatBuffer();
      int t0 = (int) (f.remaining() / MEL);          // [1][128][T]
      for (int c = 0; c < MEL; c++) for (int t = 0; t < T; t++) x[c * F + t] = f.get(c * t0 + t);
    }
    float[] e; int frames, cols;
    try (OnnxTensor a = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[]{1, MEL, F});
         OnnxTensor l = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{T}), new long[]{1});
         OrtSession.Result r = enc.run(map("audio_signal", a, "length", l))) {
      FloatBuffer o = ((OnnxTensor) r.get(0)).getFloatBuffer();
      e = new float[o.remaining()]; o.get(e);
      cols = e.length / ENC;                          // [1][1024][104]
      frames = Math.min(cols, Math.round(((float[]) r.get(1).getValue())[0]));
    }
    return decode(e, cols, frames);
  }

  /** Жадный TDT sherpa-onnx (DecodeOneTDT) по кадрам кодировщика e[c * cols + t]. */
  Result decode(float[] e, int cols, int frames) throws OrtException {
    List<Integer> toks = new ArrayList<>(); List<Integer> ts = new ArrayList<>(), ds = new ArrayList<>(); List<Float> lp = new ArrayList<>();
    float[][] state = {new float[2 * 640], new float[2 * 640]};
    float[] dout = runDecoder(blank, state);
    float[] frame = new float[ENC];
    int perFrame = 0, skip;
    for (int t = 0; t < frames; t += skip) {
      for (int c = 0; c < ENC; c++) frame[c] = e[c * cols + t];
      float[] logit = runJoiner(frame, dout);
      int vocab = sym.length, nd = logit.length - vocab;
      int y = argmax(logit, 0, vocab);
      float lse = logSumExp(logit, vocab);
      skip = argmax(logit, vocab, nd) - vocab;
      if (y != blank) {
        toks.add(y); ts.add(t); ds.add(skip); lp.add(logit[y] - lse);
        dout = runDecoder(y, state);
        perFrame++;
      }
      if (skip > 0) perFrame = 0;
      if (perFrame >= MAX_PER_FRAME) { perFrame = 0; skip = 1; }
      if (y == blank && skip == 0) { perFrame = 0; skip = 1; }
    }
    StringBuilder text = new StringBuilder();
    String[] tk = new String[toks.size()]; float[] times = new float[tk.length], durs = new float[tk.length], probs = new float[tk.length];
    for (int i = 0; i < tk.length; i++) {
      tk[i] = sym[toks.get(i)]; text.append(tk[i]);
      times[i] = FRAME_S * ts.get(i); durs[i] = FRAME_S * ds.get(i); probs[i] = lp.get(i);
    }
    String s = text.toString();
    if (s.startsWith(" ")) s = s.substring(1);
    return new Result(s, tk, times, durs, probs);
  }

  /** Декодер (предсказатель) sherpa-onnx: targets [1][1], target_length [1], два состояния LSTM [2][1][640];
   *  выходы по порядку: outputs [1][640][1], prednet_lengths, новые состояния. */
  float[] runDecoder(int token, float[][] state) throws OrtException {
    List<String> in = new ArrayList<>(dec.getInputNames());
    try (OnnxTensor tg = OnnxTensor.createTensor(env, IntBuffer.wrap(new int[]{token}), new long[]{1, 1});
         OnnxTensor tl = OnnxTensor.createTensor(env, IntBuffer.wrap(new int[]{1}), new long[]{1});
         OnnxTensor s1 = OnnxTensor.createTensor(env, FloatBuffer.wrap(state[0]), new long[]{2, 1, 640});
         OnnxTensor s2 = OnnxTensor.createTensor(env, FloatBuffer.wrap(state[1]), new long[]{2, 1, 640})) {
      Map<String, OnnxTensor> m = new HashMap<>();
      m.put(in.get(0), tg); m.put(in.get(1), tl); m.put(in.get(2), s1); m.put(in.get(3), s2);
      try (OrtSession.Result r = dec.run(m)) {
        FloatBuffer o = ((OnnxTensor) r.get(0)).getFloatBuffer(); float[] out = new float[o.remaining()]; o.get(out);
        ((OnnxTensor) r.get(2)).getFloatBuffer().get(state[0]);
        ((OnnxTensor) r.get(3)).getFloatBuffer().get(state[1]);
        return out;
      }
    }
  }

  /** Совместная сеть: encoder_outputs [1][1024][1], decoder_outputs [1][640][1] → [1][1][1][словарь + 5 длительностей]. */
  float[] runJoiner(float[] frame, float[] dout) throws OrtException {
    List<String> in = new ArrayList<>(join.getInputNames());
    try (OnnxTensor a = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), new long[]{1, ENC, 1});
         OnnxTensor b = OnnxTensor.createTensor(env, FloatBuffer.wrap(dout), new long[]{1, dout.length, 1});
         OrtSession.Result r = join.run(map(in.get(0), a, in.get(1), b))) {
      FloatBuffer o = ((OnnxTensor) r.get(0)).getFloatBuffer(); float[] out = new float[o.remaining()]; o.get(out);
      return out;
    }
  }

  static int argmax(float[] v, int from, int n) {
    int best = from;
    for (int i = from + 1; i < from + n; i++) if (v[i] > v[best]) best = i;
    return best;
  }

  static float logSumExp(float[] v, int n) {
    float mx = v[0]; for (int i = 1; i < n; i++) mx = Math.max(mx, v[i]);
    double s = 0; for (int i = 0; i < n; i++) s += Math.exp(v[i] - mx);
    return (float) (mx + Math.log(s));
  }

  static Map<String, OnnxTensor> map(String k1, OnnxTensor v1, String k2, OnnxTensor v2) {
    Map<String, OnnxTensor> m = new HashMap<>(); m.put(k1, v1); m.put(k2, v2); return m;
  }

  @Override public void close() {
    for (OrtSession s : new OrtSession[]{pre, enc, dec, join}) try { if (s != null) s.close(); } catch (OrtException ignored) {}
  }
}
