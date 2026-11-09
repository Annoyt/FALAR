package dev.agenttranslator;

import java.io.File;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Стенд для NpuAsr: тот же класс, что в приложении, по корпусу bench/air/corpus → «имя<TAB>гипотеза» и время на фразу.
 *  На ПК кодировщик на процессоре (QDQ во float — bench/npu/npu_asr_pc.sh); на телефоне со Snapdragon через app_process
 *  — на NPU, с теми же библиотеками QNN, что в APK (bench/npu/npu_asr_device.sh). WER — bench/npu/asr_wer.py --hyps.
 *    NpuAsrPc ASR_DIR NPU_DIR CORPUS OUT.tsv [КАЖДАЯ_N] [КАТАЛОГ_QNN [ФАЙЛ_ГРАФА]] */
public class NpuAsrPc {
  public static void main(String[] a) throws Exception {
    File asr = new File(a[0]), npu = new File(a[1]), corpus = new File(a[2]), out = new File(a[3]);
    int every = a.length > 4 ? Integer.parseInt(a[4]) : 1;
    java.util.Map<String, String> qnn = null;
    if (a.length > 5) {
      qnn = new java.util.HashMap<>();
      qnn.put("backend_path", new File(a[5], "libQnnHtp.so").getPath());
      qnn.put("htp_performance_mode", "burst");
      qnn.put("htp_graph_finalization_optimization_mode", "3");
      // NPU_QNN="ключ=значение,…" — подобрать параметры QNN на стенде (напр. htp_arch=68, soc_model=35)
      String extra = System.getenv("NPU_QNN");
      if (extra != null) for (String kv : extra.split(",")) { String[] p = kv.split("=", 2); if (p.length == 2) qnn.put(p[0].trim(), p[1].trim()); }
      System.out.println("RESULT параметры QNN: " + qnn);
    }
    File ctx = a.length > 6 ? new File(a[6]) : null;
    int n = 0, done = 0, longer = 0; long ms = 0, first = -1;
    long t00 = System.nanoTime();
    try (NpuAsr r = new NpuAsr(asr, npu, qnn, ctx);
         PrintWriter w = new PrintWriter(out, "UTF-8")) {
      System.out.println("RESULT загрузка " + (System.nanoTime() - t00) / 1_000_000 + " мс; кодировщик: " + r.how
          + ", токенов " + r.sym.length + ", пустой " + r.blank);
      for (String lang : new String[]{"pt", "ru"}) {
        File[] wavs = new File(corpus, lang).listFiles((d, s) -> s.endsWith(".wav"));
        Arrays.sort(wavs);
        for (File f : wavs) {
          if (n++ % every != 0) continue;
          float[] s = wav(f);
          long t0 = System.nanoTime();
          NpuAsr.Result res = r.run(s);
          long dt = (System.nanoTime() - t0) / 1_000_000;
          if (first < 0) first = dt; else ms += dt;
          String name = lang + "_" + f.getName().replace(".wav", "");
          if (res == null) { longer++; continue; }
          w.println(name + "\t" + res.text);
          done++;
        }
      }
    }
    System.out.printf("RESULT фраз %d (длиннее окна %d), первая %d мс, дальше %.1f мс на фразу (признаки + кодировщик + TDT) → %s%n",
        done, longer, first, ms / (double) Math.max(1, done + longer - 1), out);
  }

  /** WAV PCM 16 бит или float32, моно, 16 кГц. */
  static float[] wav(File f) throws Exception {
    ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
    int fmt = 1, bits = 16, rate = 0, ch = 1;
    b.position(12);
    while (b.remaining() >= 8) {
      byte[] id = new byte[4]; b.get(id); int len = b.getInt();
      String s = new String(id, StandardCharsets.US_ASCII);
      if (s.equals("fmt ")) {
        int p = b.position(); fmt = b.getShort(); ch = b.getShort(); rate = b.getInt(); b.getInt(); b.getShort(); bits = b.getShort();
        b.position(p + len);
      } else if (s.equals("data")) {
        if (rate != 16000 || ch != 1) throw new IllegalArgumentException(f + ": нужен моно 16 кГц, а здесь " + ch + " × " + rate);
        int k = len / (bits / 8); float[] x = new float[k];
        for (int i = 0; i < k; i++) x[i] = (fmt == 3 && bits == 32) ? b.getFloat() : b.getShort() / 32768f;
        return x;
      } else {
        b.position(b.position() + len + (len & 1));
      }
    }
    throw new IllegalArgumentException(f + ": нет данных");
  }
}
