package dev.agenttranslator;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;

/** Обычный путь приложения на процессоре — для сравнения с NPU на том же телефоне и корпусе: sherpa-onnx, parakeet
 *  int8, настройки ровно как Engine.offline(asr_multi, 4) (жадный поиск, признаки 16 кГц). Через app_process с мостом
 *  JNI из APK (bench/npu/mtk_asr_device.sh CPU=1). Выход — как у NpuAsrPc: «имя<TAB>гипотеза» и время на фразу.
 *    SherpaAsrPc ASR_DIR CORPUS OUT.tsv [КАЖДАЯ_N] [ПОТОКИ=4] */
public class SherpaAsrPc {
  public static void main(String[] a) throws Exception {
    File d = new File(a[0]), corpus = new File(a[1]), out = new File(a[2]);
    int every = a.length > 3 ? Integer.parseInt(a[3]) : 1, threads = a.length > 4 ? Integer.parseInt(a[4]) : 4;
    long t00 = System.nanoTime();
    // как Engine.offline
    OfflineTransducerModelConfig tr = OfflineTransducerModelConfig.builder().setEncoder(new File(d, "encoder.int8.onnx").getPath())
        .setDecoder(new File(d, "decoder.int8.onnx").getPath()).setJoiner(new File(d, "joiner.int8.onnx").getPath()).build();
    OfflineModelConfig mc = OfflineModelConfig.builder().setTransducer(tr).setTokens(new File(d, "tokens.txt").getPath())
        .setNumThreads(threads).setModelType("nemo_transducer").setDebug(false).build();
    OfflineRecognizer r = new OfflineRecognizer(OfflineRecognizerConfig.builder()
        .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
        .setOfflineModelConfig(mc).setDecodingMethod("greedy_search").build());
    System.out.println("RESULT загрузка " + (System.nanoTime() - t00) / 1_000_000 + " мс; sherpa int8, потоков " + threads);
    int n = 0, done = 0; long ms = 0, first = -1;
    try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
      for (String lang : new String[]{"pt", "ru"}) {
        File[] wavs = new File(corpus, lang).listFiles((x, s) -> s.endsWith(".wav"));
        Arrays.sort(wavs);
        for (File f : wavs) {
          if (n++ % every != 0) continue;
          float[] s = NpuAsrPc.wav(f);
          long t0 = System.nanoTime();
          OfflineStream st = r.createStream();
          st.acceptWaveform(s, 16000);
          r.decode(st);
          String text = r.getResult(st).getText();
          st.release();
          long dt = (System.nanoTime() - t0) / 1_000_000;
          if (first < 0) first = dt; else ms += dt;
          w.println(lang + "_" + f.getName().replace(".wav", "") + "\t" + text);
          done++;
        }
      }
    }
    r.release();
    System.out.printf("RESULT фраз %d, первая %d мс, дальше %.1f мс на фразу (признаки + кодировщик + жадный поиск) → %s%n",
        done, first, ms / (double) Math.max(1, done - 1), out);
  }
}
