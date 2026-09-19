import ai.onnxruntime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MtServer {
  static final int LAYERS = 6;
  public static void main(String[] a) throws Exception {
    String dir = a[0]; int threads = Integer.parseInt(a[1]); String q = a[2];
    System.setProperty("onnxruntime.native.path", "/data/local/tmp/ort");
    SpmTokenizer tok = new SpmTokenizer("/data/local/tmp/e2e/tok/" + dir + "_source_pieces.tsv", "/data/local/tmp/e2e/tok/" + dir + "_vocab.json");
    long start = tok.vocab.get("<pad>"), eos = tok.vocab.get("</s>"); String langTok = dir.equals("pt2ru") ? ">>rus<<" : null;
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    OrtSession.SessionOptions so = new OrtSession.SessionOptions(); so.setIntraOpNumThreads(threads); so.setInterOpNumThreads(1);
    String m = "/data/local/tmp/ort/" + dir;
    OrtSession enc = env.createSession(m + "/encoder_model.onnx", so), dec = env.createSession(m + "/decoder_model.onnx", so), decP = env.createSession(m + "/decoder_with_past_model.onnx", so);
    Files.write(Paths.get(q, "mt_ready"), new byte[0]); System.out.println("MT_READY " + dir);
    while (true) {
      File[] reqs = new File(q).listFiles((d, n) -> n.startsWith("req_") && n.endsWith(".txt"));
      if (reqs == null || reqs.length == 0) { Thread.sleep(2); continue; }
      Arrays.sort(reqs);
      for (File f : reqs) {
        String text = new String(Files.readAllBytes(f.toPath()), "UTF-8").trim(); f.delete();
        if (text.equals("QUIT")) { System.out.println("MT_QUIT"); return; }
        long t0 = System.nanoTime(); StringBuilder out = new StringBuilder(); int nTok = 0;
        for (String sent : text.split("(?<=[.!?…])\\s+(?=\\S)")) {
          if (sent.isEmpty()) continue;
          List<Long> ids = tok.encode(sent, langTok); List<Long> o = greedy(env, enc, dec, decP, ids, start, eos, 64); nTok += o.size();
          if (out.length() > 0) out.append(' '); out.append(tok.decode(o));
        }
        long ms = (System.nanoTime() - t0) / 1000000;
        String id = f.getName().substring(4, f.getName().length() - 4);
        Path tmp = Paths.get(q, "tmp_" + id); Files.write(tmp, (ms + "\t" + nTok + "\t" + out).getBytes("UTF-8")); Files.move(tmp, Paths.get(q, "resp_" + id + ".txt"), StandardCopyOption.ATOMIC_MOVE);
      }
    }
  }
  static List<Long> greedy(OrtEnvironment env, OrtSession enc, OrtSession dec, OrtSession decP, List<Long> ids, long start, long eos, int maxNew) throws Exception {
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
    for (OnnxTensor p : past.values()) p.close(); tIn.close(); tMask.close(); er.close(); return outIds;
  }
  static long argmax(OnnxTensor logits) throws OrtException { float[][][] v = (float[][][]) logits.getValue(); float[] row = v[0][v[0].length - 1]; int b = 0; for (int i = 1; i < row.length; i++) if (row[i] > row[b]) b = i; return b; }
}
