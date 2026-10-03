import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Один ONNX-граф на процессоре и на NPU Qualcomm (QNN, HTP) на телефоне: время сборки, время вызова, совпадение выходов.
 * Запуск через app_process из каталога с библиотеками (ORT с QNN из Maven, libQnnHtp*.so):
 *
 *   CLASSPATH=qnnbench.dex LD_LIBRARY_PATH=. ADSP_LIBRARY_PATH="$PWD;…" app_process $PWD QnnBench \
 *     model.onnx имя=N,… прогонов cpu|npu [потоков] [soc_model] [другая_модель.onnx для сверки]
 *
 * npu — всё на HTP или отказ (session.disable_cpu_ep_fallback): так видно, влезает ли граф целиком.
 * Входы — детерминированные (seed 1): маски — единицы, length — длина звука, id — случайные 5…999, float — N(0,1).
 * Последняя модель (необязательно) — эталон: те же входы, сравнение первого выхода (cos, max|Δ|).
 */
public class QnnBench {
  public static void main(String[] a) throws Exception {
    String model = a[0];
    Map<String, Long> dims = new LinkedHashMap<>();
    for (String p : a[1].split(",")) if (p.contains("=")) dims.put(p.split("=")[0], Long.parseLong(p.split("=")[1]));
    int runs = Integer.parseInt(a[2]);
    String mode = a[3];
    int threads = a.length > 4 ? Integer.parseInt(a[4]) : 4;
    String soc = a.length > 5 ? a[5] : "";
    String refModel = a.length > 6 ? a[6] : null;
    String dir = System.getProperty("user.dir");
    if (new java.io.File(dir, "libonnxruntime.so").exists()) System.setProperty("onnxruntime.native.path", dir);  // на телефоне
    OrtEnvironment env = OrtEnvironment.getEnvironment();

    long t0 = System.nanoTime();
    OrtSession s = env.createSession(model, options(dims, mode, threads, soc, dir));
    double buildMs = (System.nanoTime() - t0) / 1e6;
    Map<String, OnnxTensor> feed = inputs(env, s, dims);
    float[] out = null;
    double[] ms = new double[runs];
    for (int r = -2; r < runs; r++) {          // два прогрева
      long t = System.nanoTime();
      try (OrtSession.Result res = s.run(feed)) {
        if (r >= 0) ms[r] = (System.nanoTime() - t) / 1e6;
        if (r == runs - 1) out = flat(res.get(0));
      }
    }
    double[] sorted = ms.clone();
    Arrays.sort(sorted);
    System.out.println(String.format(Locale.ROOT, "RESULT %s mode=%s threads=%d soc=%s build_ms=%.0f min_ms=%.2f p50_ms=%.2f p90_ms=%.2f runs=%d",
        name(model), mode, threads, soc, buildMs, sorted[0], sorted[runs / 2], sorted[Math.min(runs - 1, runs * 9 / 10)], runs));
    if (refModel != null) {
      try (OrtSession rs = env.createSession(refModel, options(dims, "cpu", threads, soc, dir))) {
        Map<String, OnnxTensor> rf = inputs(env, rs, dims);
        try (OrtSession.Result res = rs.run(rf)) { compare(out, flat(res.get(0))); }
      }
    }
    s.close();
  }

  static OrtSession.SessionOptions options(Map<String, Long> dims, String mode, int threads, String soc, String dir) throws Exception {
    OrtSession.SessionOptions so = new OrtSession.SessionOptions();
    for (Map.Entry<String, Long> e : dims.entrySet()) so.setSymbolicDimensionValue(e.getKey(), e.getValue());
    so.setIntraOpNumThreads(threads);
    so.setInterOpNumThreads(1);
    if ("npu".equals(mode)) {
      Map<String, String> q = new HashMap<>();
      q.put("backend_path", dir + "/libQnnHtp.so");
      q.put("htp_performance_mode", "burst");
      q.put("enable_htp_fp16_precision", "1");
      if (!soc.isEmpty()) q.put("soc_model", soc);
      so.addQnn(q);
      so.addConfigEntry("session.disable_cpu_ep_fallback", "1");
    }
    return so;
  }

  static Map<String, OnnxTensor> inputs(OrtEnvironment env, OrtSession s, Map<String, Long> dims) throws Exception {
    Random rnd = new Random(1);
    Map<String, OnnxTensor> feed = new LinkedHashMap<>();
    long audio = 1;
    for (Map.Entry<String, Long> e : dims.entrySet()) if (e.getKey().contains("audio_signal") && e.getKey().endsWith("_2")) audio = e.getValue();
    for (NodeInfo ni : s.getInputInfo().values()) {
      TensorInfo ti = (TensorInfo) ni.getInfo();
      long[] shape = ti.getShape().clone();
      String[] names = ti.getDimensionNames() == null ? new String[shape.length] : ti.getDimensionNames();
      for (int k = 0; k < shape.length; k++) if (shape[k] < 0) shape[k] = names[k] != null && dims.containsKey(names[k]) ? dims.get(names[k]) : 1;
      int n = 1;
      for (long d : shape) n *= (int) d;
      String in = ni.getName();
      if (ti.type == ai.onnxruntime.OnnxJavaType.INT64) {
        long[] v = new long[n];
        for (int k = 0; k < n; k++) v[k] = in.contains("mask") ? 1 : in.equals("length") ? audio : 5 + rnd.nextInt(995);
        feed.put(in, OnnxTensor.createTensor(env, LongBuffer.wrap(v), shape));
      } else if (ti.type == ai.onnxruntime.OnnxJavaType.FLOAT16) {   // модели с fp16 на входах (декодер fp16io)
        short[] v = new short[n];
        for (int k = 0; k < n; k++) v[k] = toHalf((float) rnd.nextGaussian() * 0.5f);
        feed.put(in, OnnxTensor.createTensor(env, ShortBuffer.wrap(v), shape, ai.onnxruntime.OnnxJavaType.FLOAT16));
      } else {
        float[] v = new float[n];
        for (int k = 0; k < n; k++) v[k] = (float) rnd.nextGaussian() * 0.5f;
        feed.put(in, OnnxTensor.createTensor(env, FloatBuffer.wrap(v), shape));
      }
    }
    return feed;
  }

  static float[] flat(OnnxValue v) throws Exception {
    OnnxTensor t = (OnnxTensor) v;
    if (t.getInfo().type == ai.onnxruntime.OnnxJavaType.FLOAT16) {
      ShortBuffer h = t.getShortBuffer();
      float[] f = new float[h.remaining()];
      for (int i = 0; i < f.length; i++) f[i] = fromHalf(h.get());
      return f;
    }
    FloatBuffer b = t.getFloatBuffer();
    float[] f = new float[b.remaining()];
    b.get(f);
    return f;
  }

  static void compare(float[] x, float[] y) {
    if (x == null || y == null || x.length != y.length) { System.out.println("COMPARE размеры не совпали"); return; }
    double dot = 0, nx = 0, ny = 0, maxd = 0, maxx = 0;
    for (int i = 0; i < x.length; i++) {
      dot += (double) x[i] * y[i]; nx += (double) x[i] * x[i]; ny += (double) y[i] * y[i];
      maxd = Math.max(maxd, Math.abs(x[i] - y[i])); maxx = Math.max(maxx, Math.abs(y[i]));
    }
    System.out.println(String.format(Locale.ROOT, "COMPARE cos=%.6f max_abs=%.4g rel=%.3g n=%d", dot / Math.sqrt(nx * ny + 1e-30), maxd, maxd / (maxx + 1e-12), x.length));
  }

  static short toHalf(float f) {
    int x = Float.floatToIntBits(f), sign = (x >>> 16) & 0x8000, e = ((x >>> 23) & 0xff) - 127 + 15, m = x & 0x7fffff;
    if (e <= 0) return (short) sign;                     // ноль и субнормальные — в ноль (входы стенда случайные)
    if (e >= 31) return (short) (sign | 0x7c00);
    return (short) (sign | (e << 10) | (m >>> 13));
  }

  static float fromHalf(short h) {
    int s = (h & 0x8000) << 16, e = (h >>> 10) & 0x1f, m = h & 0x3ff;
    if (e == 0) return Float.intBitsToFloat(s) + (s != 0 ? -1 : 1) * m * (float) Math.pow(2, -24);
    if (e == 31) return Float.intBitsToFloat(s | 0x7f800000 | (m << 13));
    return Float.intBitsToFloat(s | ((e - 15 + 127) << 23) | (m << 13));
  }

  static String name(String p) { return p.substring(p.lastIndexOf('/') + 1); }
}
