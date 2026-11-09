package dev.agenttranslator;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Проба операций NPU MediaTek (bench/npu/mtk_ops.py): каждая модель каталога — сессия с дополнением ORT (NeuroPilot)
 *  и на процессоре, прогон на одном и том же входе, сравнение. FAIL — дополнение или рантайм Neuron модель не взяли
 *  (текст ошибки). Взял ли NPU часть графа — строки дополнения «GetCapability: partitions=… supported_nodes=…» в том же
 *  выводе (части без conv/matmul оно отдаёт процессору). Маски слоя (mtk_matt, mtk_bias, mtk_vconv) — как у фразы
 *  в 70 кадров из 104.
 *    [PROBE_ONLY=регулярка] MtkOpProbe КАТАЛОГ БИБЛИОТЕКА_EP.so [ключ=значение,…] */
public class MtkOpProbe {
  static final int VALID = 70;

  public static void main(String[] a) throws Exception {
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    Map<String, String> opts = new HashMap<>();
    if (a.length > 2) for (String kv : a[2].split(",")) { String[] p = kv.split("=", 2); if (p.length == 2) opts.put(p[0].trim(), p[1].trim()); }
    File[] fs = new File(a[0]).listFiles((d, s) -> s.endsWith(".onnx"));
    Arrays.sort(fs);
    String only = System.getenv("PROBE_ONLY");        // регулярное выражение имён файлов — гонять не все
    for (File f : fs) {
      if (only != null && !only.isEmpty() && !f.getName().matches(only)) continue;
      System.out.println("== " + f.getName());
      System.out.flush();
      try (OrtSession cpu = env.createSession(f.getPath(), new OrtSession.SessionOptions())) {
        Map<String, OnnxTensor> in = inputs(env, cpu);
        float[] ref = first(cpu.run(in));
        long t0 = System.nanoTime();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.addExecutionProvider(NpuAsr.pluginDevices(env, a[1]), opts);
        try (OrtSession npu = env.createSession(f.getPath(), so)) {
          long build = (System.nanoTime() - t0) / 1_000_000;
          float[] got = null; long best = Long.MAX_VALUE;
          for (int k = 0; k < 3; k++) {
            long t = System.nanoTime();
            got = first(npu.run(in));
            best = Math.min(best, (System.nanoTime() - t) / 1000);
          }
          long tc = System.nanoTime(); first(cpu.run(in)); long cpuUs = (System.nanoTime() - tc) / 1000;
          double d = 0, r = 0, dv = 0;
          boolean masked = in.containsKey("mtk_vconv") || in.containsKey("mtk_vcol") || in.containsKey("mtk_matt");   // выход [1, T, D], с VALID — хвост
          int row = ref.length / 104;
          for (int i = 0; i < ref.length; i++) {
            double e = Math.abs(ref[i] - got[i]);
            d = Math.max(d, e); r = Math.max(r, Math.abs(ref[i]));
            if (masked && i / row < VALID) dv = Math.max(dv, e);
          }
          System.out.printf("OK %s: сборка %d мс, прогон %.2f мс (процессор %.2f мс), разница %.3g при размахе %.3g%s%n",
              f.getName(), build, best / 1000.0, cpuUs / 1000.0, d, r,
              masked ? String.format(" (в %d кадрах фразы — %.3g)", VALID, dv) : "");
        }
        for (OnnxTensor t : in.values()) t.close();
      } catch (Exception e) {
        System.out.println("FAIL " + f.getName() + ": " + e.getMessage());
      }
      System.out.flush();
    }
  }

  static Map<String, OnnxTensor> inputs(OrtEnvironment env, OrtSession s) throws Exception {
    Random rnd = new Random(1);
    Map<String, OnnxTensor> in = new HashMap<>();
    for (Map.Entry<String, NodeInfo> e : s.getInputInfo().entrySet()) {
      long[] shape = ((TensorInfo) e.getValue().getInfo()).getShape();
      int n = 1;
      for (long d : shape) n *= (int) d;
      float[] x = new float[n];
      String k = e.getKey();
      int T = (int) shape[shape.length - 1];
      for (int i = 0; i < n; i++) {
        int j = i % T, row = (i / T) % T;
        if (k.equals("mtk_vconv")) x[i] = j < VALID ? 1 : 0;
        else if (k.equals("mtk_vcol")) x[i] = i < VALID ? 1 : 0;           // [1, T, 1]
        else if (k.equals("mtk_matt")) x[i] = j < VALID && row < VALID ? 1 : 0;
        else if (k.equals("mtk_bias")) x[i] = j < VALID && row < VALID ? 0 : -10000;
        else if (k.equals("Z") || k.equals("mtk_zero_in")) x[i] = 0;
        else x[i] = (float) rnd.nextGaussian();
      }
      in.put(k, OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape));
    }
    return in;
  }

  static float[] first(OrtSession.Result r) throws Exception {
    try (r) {
      OnnxValue v = r.get(0);
      FloatBuffer b = ((OnnxTensor) v).getFloatBuffer();
      float[] x = new float[b.remaining()];
      b.get(x);
      return x;
    }
  }
}
