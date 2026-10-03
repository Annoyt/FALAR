package dev.agenttranslator;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Признаки отпечатка голоса (Fbank) — те же, что у эталона tools/voiceprint_ref.py: число кадров,
 *  первые кадры и средний модуль на живых записях; плюс проверки без эталона — БПФ против прямого
 *  ДПФ, тон попадает в свою мел-полосу. Отпечаток целиком (с моделью) сверяется на телефоне:
 *  --es voicegold (test_voices_device.sh). Запуск: bash bench/apk/test.sh. */
public class FbankTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }

  static float[] wav(File f) throws IOException {
    byte[] b = Files.readAllBytes(f.toPath());
    int p = 12, data = -1, len = 0;
    while (p + 8 <= b.length) {
      String id = new String(b, p, 4, "US-ASCII"); int sz = (b[p + 4] & 255) | (b[p + 5] & 255) << 8 | (b[p + 6] & 255) << 16 | (b[p + 7] & 255) << 24;
      if (id.equals("data")) { data = p + 8; len = Math.min(sz, b.length - data); break; }
      p += 8 + sz + (sz & 1);
    }
    float[] x = new float[len / 2];
    for (int i = 0; i < x.length; i++) x[i] = (short) ((b[data + 2 * i] & 255) | (b[data + 2 * i + 1] << 8)) / 32768f;
    return x;
  }

  public static int run(String golden, File root) throws Exception {
    fails = 0; checks = 0;
    ok(Fbank.frames(399) == 0 && Fbank.frames(400) == 1 && Fbank.frames(559) == 1 && Fbank.frames(560) == 2, "F1 только целые кадры");
    ok(Fbank.compute(new float[399]).length == 0, "F1 меньше кадра — пусто");

    // БПФ против прямого ДПФ
    Random rnd = new Random(1);
    double[] x = new double[Fbank.NFFT]; for (int i = 0; i < x.length; i++) x[i] = rnd.nextGaussian();
    double[] re = new double[Fbank.NFFT], im = new double[Fbank.NFFT];
    for (int i = 0; i < x.length; i++) re[Fbank.REV[i]] = x[i];
    Fbank.fft(re, im);
    double worst = 0;
    for (int k = 0; k <= Fbank.NFFT / 2; k += 7) {
      double r = 0, m = 0;
      for (int i = 0; i < x.length; i++) { r += x[i] * Math.cos(-2 * Math.PI * k * i / Fbank.NFFT); m += x[i] * Math.sin(-2 * Math.PI * k * i / Fbank.NFFT); }
      worst = Math.max(worst, Math.max(Math.abs(r - re[k]), Math.abs(m - im[k])));
    }
    ok(worst < 1e-9, "F2 БПФ совпадает с ДПФ: " + worst);

    // Тон 500 Гц, потом 3 кГц: в каждой половине выше всего полоса со своим центром
    float[] s = new float[32000];
    for (int i = 0; i < s.length; i++) s[i] = (float) (0.3 * Math.sin(2 * Math.PI * (i < 16000 ? 500 : 3000) * i / 16000.0));
    float[][] f = Fbank.compute(s);
    ok(argmax(f[30]) == band(500) && argmax(f[f.length - 30]) == band(3000), "F3 тон — в своей полосе: " + argmax(f[30]) + "/" + band(500) + ", " + argmax(f[f.length - 30]) + "/" + band(3000));
    double mu = 0; for (float[] r : f) mu += r[band(500)]; ok(Math.abs(mu / f.length) < 1e-4, "F3 среднее полосы вычтено");

    // Эталон tools/voiceprint_ref.py на живых записях
    if (golden == null || !new File(golden).isFile()) { System.out.println("  (эталона нет — сверка пропущена)"); }
    else {
      JSONArray g = new JSONArray(new String(Files.readAllBytes(new File(golden).toPath()), "UTF-8"));
      for (int k = 0; k < g.length(); k++) {
        JSONObject o = g.getJSONObject(k); File w = new File(root, o.getString("wav"));
        if (!w.isFile()) { ok(false, "G0 нет записи " + w); continue; }
        float[][] got = Fbank.compute(wav(w));
        ok(got.length == o.getInt("frames"), "G1 кадров " + got.length + " ≠ " + o.getInt("frames") + " · " + w.getName());
        JSONArray head = o.getJSONArray("fbank_head"); double d = 0;
        for (int t = 0; t < head.length(); t++) for (int m = 0; m < Fbank.NMEL; m++) d = Math.max(d, Math.abs(got[t][m] - head.getJSONArray(t).getDouble(m)));
        ok(d < 2e-3, "G2 первые кадры совпали с эталоном (наибольшее расхождение " + d + ") · " + w.getName());
        double ma = 0; for (float[] r : got) for (float v : r) ma += Math.abs(v); ma /= Math.max(1, got.length * Fbank.NMEL);
        ok(Math.abs(ma - o.getDouble("fbank_mean_abs")) < 1e-3, "G3 средний модуль " + ma + " ≈ " + o.getDouble("fbank_mean_abs") + " · " + w.getName());
      }
    }
    System.out.println(fails == 0 ? "Fbank: " + checks + " проверок, все прошли" : "Fbank: провалов " + fails + " из " + checks);
    return fails;
  }
  static int argmax(float[] v) { int b = 0; for (int i = 1; i < v.length; i++) if (v[i] > v[b]) b = i; return b; }
  /** Полоса, чей центр ближе всего к частоте f. */
  static int band(double f) {
    double lo = Fbank.mel(20), hi = Fbank.mel(8000), fm = Fbank.mel(f); int b = 0; double best = 1e9;
    for (int m = 0; m < Fbank.NMEL; m++) { double c = lo + (hi - lo) * (m + 1) / (Fbank.NMEL + 1); if (Math.abs(c - fm) < best) { best = Math.abs(c - fm); b = m; } }
    return b;
  }
  public static void main(String[] a) throws Exception {
    System.exit(run(a.length > 0 ? a[0] : null, new File(a.length > 1 ? a[1] : ".")) == 0 ? 0 : 1);
  }
}
