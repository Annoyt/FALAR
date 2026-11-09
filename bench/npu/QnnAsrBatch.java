import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Кодировщик parakeet на настоящих признаках речи — на NPU (QNN HTP, fp16) или процессоре; выходы — в файлы,
 * их декодирует ПК (bench/npu/asr_wer.py). app_process, как QnnBench.
 *
 *   QnnAsrBatch model.onnx list.tsv feat_dir out_dir F cpu|npu [потоков]
 * list.tsv: «имя\tкадров»; признаки — feat_dir/имя.f16, float16 [128][кадров] без хвоста (дополняется нулями до F здесь:
 * туннель Remote Test Lab бывает 0,1 МБ/с). Выход — out_dir/имя.f16: float16 [1024][длина выхода] и out_dir/имя.len.
 */
public class QnnAsrBatch {
  public static void main(String[] a) throws Exception {
    String model = a[0], list = a[1], feat = a[2], out = a[3];
    int F = Integer.parseInt(a[4]);
    String mode = a[5];
    int threads = a.length > 6 ? Integer.parseInt(a[6]) : 4;
    String dir = System.getProperty("user.dir");
    if (new File(dir, "libonnxruntime.so").exists()) System.setProperty("onnxruntime.native.path", dir);
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    OrtSession.SessionOptions so = new OrtSession.SessionOptions();
    so.setSymbolicDimensionValue("audio_signal_dynamic_axes_1", 1);
    so.setSymbolicDimensionValue("audio_signal_dynamic_axes_2", F);
    so.setSymbolicDimensionValue("length_dynamic_axes_1", 1);
    so.setIntraOpNumThreads(threads);
    if ("npu".equals(mode)) {
      Map<String, String> q = new HashMap<>();
      q.put("backend_path", dir + "/libQnnHtp.so");
      q.put("htp_performance_mode", "burst");
      q.put("enable_htp_fp16_precision", "1");
      so.addQnn(q);
      so.addConfigEntry("session.disable_cpu_ep_fallback", "1");
    }
    long t0 = System.nanoTime();
    OrtSession s = env.createSession(model, so);
    System.out.println(String.format(Locale.ROOT, "BUILD %s %.0f ms", mode, (System.nanoTime() - t0) / 1e6));
    new File(out).mkdirs();
    List<String> lines = Files.readAllLines(new File(list).toPath());
    double sum = 0;
    int n = 0;
    for (String line : lines) {
      String[] p = line.split("\t");
      if (p.length < 2) continue;
      String name = p[0];
      long frames = Long.parseLong(p[1].trim());
      byte[] raw = Files.readAllBytes(new File(feat, name + ".f16").toPath());
      java.nio.ShortBuffer hb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
      float[] pad = new float[128 * F];
      for (int c = 0; c < 128; c++) for (int k = 0; k < frames; k++) pad[c * F + k] = QnnBench.fromHalf(hb.get(c * (int) frames + k));
      FloatBuffer fb = FloatBuffer.wrap(pad);
      try (OnnxTensor x = OnnxTensor.createTensor(env, fb, new long[] {1, 128, F});
           OnnxTensor len = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] {frames}), new long[] {1})) {
        Map<String, OnnxTensor> feed = new HashMap<>();
        feed.put("audio_signal", x);
        feed.put("length", len);
        long t = System.nanoTime();
        try (OrtSession.Result r = s.run(feed)) {
          double ms = (System.nanoTime() - t) / 1e6;
          sum += ms; n++;
          FloatBuffer o = ((OnnxTensor) r.get(0)).getFloatBuffer();
          long ol = ((OnnxTensor) r.get(1)).getLongBuffer().get(0);
          int To = o.remaining() / 1024, L = (int) Math.min(ol, To);
          ByteBuffer bb = ByteBuffer.allocate(1024 * L * 2).order(ByteOrder.LITTLE_ENDIAN);
          for (int c = 0; c < 1024; c++) for (int k = 0; k < L; k++) bb.putShort(QnnBench.toHalf(o.get(c * To + k)));
          try (FileOutputStream fo = new FileOutputStream(new File(out, name + ".f16"))) { fo.write(bb.array()); }
          Files.write(new File(out, name + ".len").toPath(), Long.toString(ol).getBytes());
        }
      }
    }
    System.out.println(String.format(Locale.ROOT, "DONE %s n=%d avg_ms=%.2f", mode, n, sum / Math.max(1, n)));
  }
}
