package dev.agenttranslator;

import java.io.File;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Стенд на ПК для NpuAsr: тот же класс, что в приложении, но кодировщик на процессоре (QDQ считается во float, выход
 *  тот же, что у NPU с точностью квантования). По корпусу bench/air/corpus → «имя<TAB>гипотеза» и время на фразу.
 *  WER — bench/npu/asr_wer.py --hyps. Запуск — bench/npu/npu_asr_pc.sh.
 *    NpuAsrPc ASR_DIR NPU_DIR CORPUS OUT.tsv [КАЖДАЯ_N] */
public class NpuAsrPc {
  public static void main(String[] a) throws Exception {
    File asr = new File(a[0]), npu = new File(a[1]), corpus = new File(a[2]), out = new File(a[3]);
    int every = a.length > 4 ? Integer.parseInt(a[4]) : 1;
    int n = 0, done = 0, longer = 0; long ms = 0;
    try (NpuAsr r = new NpuAsr(asr, npu, null, null);
         PrintWriter w = new PrintWriter(out, "UTF-8")) {
      System.out.println("кодировщик: " + r.how + ", токенов " + r.sym.length + ", пустой " + r.blank);
      for (String lang : new String[]{"pt", "ru"}) {
        File[] wavs = new File(corpus, lang).listFiles((d, s) -> s.endsWith(".wav"));
        Arrays.sort(wavs);
        for (File f : wavs) {
          if (n++ % every != 0) continue;
          float[] s = wav(f);
          long t0 = System.nanoTime();
          NpuAsr.Result res = r.run(s);
          ms += (System.nanoTime() - t0) / 1_000_000;
          String name = lang + "_" + f.getName().replace(".wav", "");
          if (res == null) { longer++; continue; }
          w.println(name + "\t" + res.text);
          done++;
        }
      }
    }
    System.out.printf("фраз %d (длиннее окна %d), %.0f мс на фразу → %s%n", done, longer, ms / (double) Math.max(1, done + longer), out);
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
