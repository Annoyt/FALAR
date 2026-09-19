import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public class AsrTts {
  static long tFirst;
  public static void main(String[] a) throws Exception {
    String dir = a[0], asrTag = a[1]; int asrT = Integer.parseInt(a[2]), ttsT = Integer.parseInt(a[3]); String q = a[4], out = a[5];
    List<String> wavs = Arrays.asList(a).subList(6, a.length); String tgt = dir.substring(3); String SH = "/data/local/tmp/sh";
    System.setProperty("sherpa_onnx.native.path", "/data/local/tmp/e2e/lib");
    long t0 = System.nanoTime();
    String am = asrTag.equals("fast") ? SH + "/m/sherpa-onnx-nemo-transducer-stt_pt_fastconformer_hybrid_large_pc-int8" : SH + "/m/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8";
    OfflineTransducerModelConfig tr = OfflineTransducerModelConfig.builder().setEncoder(am + "/encoder.int8.onnx").setDecoder(am + "/decoder.int8.onnx").setJoiner(am + "/joiner.int8.onnx").build();
    OfflineModelConfig mc = OfflineModelConfig.builder().setTransducer(tr).setTokens(am + "/tokens.txt").setNumThreads(asrT).setModelType("nemo_transducer").setDebug(false).build();
    OfflineRecognizerConfig rc = OfflineRecognizerConfig.builder().setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build()).setOfflineModelConfig(mc).setDecodingMethod("greedy_search").build();
    OfflineRecognizer rec = new OfflineRecognizer(rc); long tAsrLoad = (System.nanoTime() - t0) / 1000000;
    t0 = System.nanoTime();
    String vd = tgt.equals("ru") ? SH + "/tts/v/vits-piper-ru_RU-dmitri-medium" : SH + "/tts/v/vits-piper-pt_BR-faber-medium";
    String vm = tgt.equals("ru") ? vd + "/ru_RU-dmitri-medium.onnx" : vd + "/pt_BR-faber-medium.onnx";
    OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder().setModel(vm).setTokens(vd + "/tokens.txt").setDataDir(vd + "/espeak-ng-data").build();
    OfflineTts tts = new OfflineTts(OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder().setVits(vits).setNumThreads(ttsT).setDebug(false).build()).build());
    long tTtsLoad = (System.nanoTime() - t0) / 1000000;
    while (!Files.exists(Paths.get(q, "mt_ready"))) Thread.sleep(50);
    System.out.println("LOAD asr_ms=" + tAsrLoad + " tts_ms=" + tTtsLoad);
    PrintWriter pw = new PrintWriter(new FileWriter(out)); int seq = 0; boolean warm = false;
    for (String wav : wavs) {
      WaveReader wr = new WaveReader(wav); float[] samples = wr.getSamples(); int sr = wr.getSampleRate();
      for (int pass = 0; pass < (warm ? 1 : 2); pass++) {
        long tStart = System.nanoTime();
        OfflineStream st = rec.createStream(); st.acceptWaveform(samples, sr); rec.decode(st); String asrText = rec.getResult(st).getText().trim(); st.release();
        long tAsr = System.nanoTime();
        String id = String.format("%06d", seq++); Path tmp = Paths.get(q, "tmpr_" + id); Files.write(tmp, asrText.getBytes("UTF-8")); Files.move(tmp, Paths.get(q, "req_" + id + ".txt"), StandardCopyOption.ATOMIC_MOVE);
        Path resp = Paths.get(q, "resp_" + id + ".txt"); while (!Files.exists(resp)) Thread.sleep(1);
        String[] parts = new String(Files.readAllBytes(resp), "UTF-8").split("\t", 3); Files.delete(resp);
        long tMt = System.nanoTime(); String mtText = parts.length > 2 ? parts[2] : "";
        tFirst = 0;
        GeneratedAudio ga = tts.generateWithCallback(mtText, 0, 1.0f, chunk -> { if (tFirst == 0) tFirst = System.nanoTime(); return 1; });
        long tTts = System.nanoTime();
        if (warm) {
          String name = new File(wav).getName().replace(".wav", ""); ga.save("/data/local/tmp/e2e/out/" + dir + "_" + name + ".wav");
          JSONObject j = new JSONObject(); j.put("wav", name); j.put("audio_s", samples.length / (double) sr); j.put("asr", asrText); j.put("mt", mtText); j.put("mt_tokens", Integer.parseInt(parts[1])); j.put("mt_compute_ms", Long.parseLong(parts[0]));
          j.put("asr_ms", (tAsr - tStart) / 1e6); j.put("mt_ms", (tMt - tAsr) / 1e6); j.put("tts_first_ms", (tFirst - tMt) / 1e6); j.put("tts_ms", (tTts - tMt) / 1e6); j.put("e2e_first_ms", (tFirst - tStart) / 1e6); j.put("e2e_ms", (tTts - tStart) / 1e6); j.put("tts_audio_s", ga.getSamples().length / (double) ga.getSampleRate());
          pw.println(j.toString()); pw.flush();
          System.out.println(String.format("%-14s asr=%5.0f mt=%5.0f(ipc %+d) tts_first=%5.0f tts=%5.0f | E2E_first=%5.0f E2E=%5.0f ms | %s -> %s", name, (tAsr - tStart) / 1e6, (tMt - tAsr) / 1e6, (long) ((tMt - tAsr) / 1e6) - Long.parseLong(parts[0]), (tFirst - tMt) / 1e6, (tTts - tMt) / 1e6, (tFirst - tStart) / 1e6, (tTts - tStart) / 1e6, asrText, mtText));
        } else System.out.println("WARMUP done");
        warm = true;
      }
    }
    Files.write(Paths.get(q, "req_zzzzzz.txt"), "QUIT".getBytes()); pw.close(); System.out.println("DONE");
  }
}
