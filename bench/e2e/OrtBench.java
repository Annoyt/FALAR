import ai.onnxruntime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Жадный декод Marian (OPUS-MT) через ONNX Runtime с KV-кэшем. Запуск через app_process.
 *  args: modelDir idsJson directionKey threads maxNew outFile [decoderStart eos] */
public class OrtBench {
  static final int LAYERS = 6;
  public static void main(String[] a) throws Exception {
    String modelDir = a[0], idsJson = a[1], dir = a[2]; int threads = Integer.parseInt(a[3]);
    int maxNew = Integer.parseInt(a[4]); String outFile = a[5];
    long decStart = Long.parseLong(a[6]), eos = Long.parseLong(a[7]);
    System.setProperty("onnxruntime.native.path", "/data/local/tmp/ort");
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    OrtSession.SessionOptions so = new OrtSession.SessionOptions();
    so.setIntraOpNumThreads(threads); so.setInterOpNumThreads(1);
    so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
    long t0 = System.nanoTime();
    OrtSession enc = env.createSession(modelDir + "/encoder_model.onnx", so);
    OrtSession dec = env.createSession(modelDir + "/decoder_model.onnx", so);
    OrtSession decPast = env.createSession(modelDir + "/decoder_with_past_model.onnx", so);
    System.out.println("LOAD_MS " + (System.nanoTime() - t0) / 1e6);

    JSONArray items = new JSONObject(new String(Files.readAllBytes(Paths.get(idsJson)), "UTF-8")).getJSONArray(dir);
    // прогрев: первая фраза без учёта времени
    translateItem(env, enc, dec, decPast, items.getJSONObject(0), decStart, eos, maxNew);
    PrintWriter out = new PrintWriter(new FileWriter(outFile));
    double sumMs = 0; int n = 0;
    for (int i = 0; i < items.length(); i++) {
      JSONObject it = items.getJSONObject(i);
      long ts = System.nanoTime();
      JSONObject res = translateItem(env, enc, dec, decPast, it, decStart, eos, maxNew);
      double ms = (System.nanoTime() - ts) / 1e6; sumMs += ms; n++;
      res.put("i", i); res.put("ms", ms);
      out.println(res.toString()); out.flush();
      System.out.println("PHRASE " + i + " ms=" + String.format("%.1f", ms) + " enc_ms=" + res.getDouble("enc_ms") + " first_ms=" + res.getDouble("first_ms") + " tokens=" + res.getInt("tokens") + " tok_ms=" + String.format("%.1f", res.getDouble("tok_ms")));
    }
    out.close();
    System.out.println("DONE n=" + n + " avg_ms=" + String.format("%.1f", sumMs / n));
  }

  static JSONObject translateItem(OrtEnvironment env, OrtSession enc, OrtSession dec, OrtSession decPast,
                                  JSONObject it, long decStart, long eos, int maxNew) throws Exception {
    JSONArray sents = it.getJSONArray("sent_ids");
    JSONArray outIds = new JSONArray(); double encMs = 0, firstMs = 0, tokMs = 0; int tokens = 0;
    for (int s = 0; s < sents.length(); s++) {
      JSONArray ids = sents.getJSONArray(s);
      long[][] in = new long[1][ids.length()]; long[][] mask = new long[1][ids.length()];
      for (int k = 0; k < ids.length(); k++) { in[0][k] = ids.getLong(k); mask[0][k] = 1; }
      long t = System.nanoTime();
      OnnxTensor tIn = OnnxTensor.createTensor(env, in), tMask = OnnxTensor.createTensor(env, mask);
      Map<String, OnnxTensor> ei = new HashMap<>(); ei.put("input_ids", tIn); ei.put("attention_mask", tMask);
      OrtSession.Result er = enc.run(ei);
      OnnxTensor hidden = (OnnxTensor) er.get(0);
      encMs += (System.nanoTime() - t) / 1e6;

      // первый шаг: decoder_model -> logits + present.* (decoder и encoder KV)
      t = System.nanoTime();
      Map<String, OnnxTensor> di = new HashMap<>();
      di.put("encoder_attention_mask", tMask); di.put("encoder_hidden_states", hidden);
      di.put("input_ids", OnnxTensor.createTensor(env, new long[][]{{decStart}}));
      OrtSession.Result r = dec.run(di);
      Map<String, OnnxTensor> past = new HashMap<>();
      for (int l = 0; l < LAYERS; l++) for (String kv : new String[]{"key", "value"}) {
        past.put("past_key_values." + l + ".decoder." + kv, (OnnxTensor) r.get("present." + l + ".decoder." + kv).get());
        past.put("past_key_values." + l + ".encoder." + kv, (OnnxTensor) r.get("present." + l + ".encoder." + kv).get());
      }
      long next = argmaxLast((OnnxTensor) r.get(0));
      firstMs += (System.nanoTime() - t) / 1e6;
      JSONArray sentOut = new JSONArray();
      int steps = 0;
      while (next != eos && steps < maxNew) {
        sentOut.put(next); steps++;
        t = System.nanoTime();
        Map<String, OnnxTensor> pi = new HashMap<>(past);
        pi.put("encoder_attention_mask", tMask);
        pi.put("input_ids", OnnxTensor.createTensor(env, new long[][]{{next}}));
        OrtSession.Result pr = decPast.run(pi);
        for (int l = 0; l < LAYERS; l++) for (String kv : new String[]{"key", "value"}) {
          String k = "past_key_values." + l + ".decoder." + kv;
          past.get(k).close();
          past.put(k, (OnnxTensor) pr.get("present." + l + ".decoder." + kv).get());
        }
        next = argmaxLast((OnnxTensor) pr.get(0));
        tokMs += (System.nanoTime() - t) / 1e6;
      }
      tokens += steps;
      outIds.put(sentOut);
      for (OnnxTensor p : past.values()) p.close();
      tIn.close(); tMask.close(); er.close();
    }
    JSONObject res = new JSONObject();
    res.put("out_ids", outIds); res.put("enc_ms", encMs); res.put("first_ms", firstMs);
    res.put("tokens", tokens); res.put("tok_ms", tokens > 0 ? tokMs / tokens : 0);
    return res;
  }

  static long argmaxLast(OnnxTensor logits) throws OrtException {
    float[][][] v = (float[][][]) logits.getValue();
    float[] row = v[0][v[0].length - 1];
    int best = 0; for (int i = 1; i < row.length; i++) if (row[i] > row[best]) best = i;
    return best;
  }
}
