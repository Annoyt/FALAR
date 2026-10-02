package dev.agenttranslator;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Перебивание (BargeIn) — тот же счёт, что у tools/barge_eval.py: на эталонной смеси (фраза корпуса как
 *  озвучка, её эхо, стук по столу, потом человек) шаг в шаг те же доли полос, те же действия на тех же шагах
 *  и то же усиление тракта в конце; подпор с погашенным эхом совпадает с эталоном. Плюс проверки без эталона:
 *  полосы покрывают 150–6000 Гц без дыр, тишина и одно эхо не дают действий, человек в паузе озвучки — даёт.
 *  Эталон: .venv/bin/python tools/barge_eval.py golden. Запуск: bash bench/apk/test.sh. */
public class BargeInTest {
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
  static int hops(float[] x) { return Math.max(0, (x.length - BargeIn.N) / BargeIn.HOP + 1); }

  public static int run(File dir) throws Exception {
    fails = 0; checks = 0;
    // полосы: подряд, без дыр и перекрытий, от 150 до 6000 Гц
    boolean contig = true;
    for (int b = 1; b < BargeIn.NB; b++) contig &= BargeIn.B0[b] == BargeIn.B1[b - 1] && BargeIn.B1[b] > BargeIn.B0[b];
    ok(contig && BargeIn.B0[0] * 31.25 >= 150 && (BargeIn.B0[0] - 1) * 31.25 < 150 && BargeIn.B1[BargeIn.NB - 1] * 31.25 >= 6000, "B1 полосы подряд, 150–6000 Гц");

    // тишина и одно эхо: никаких действий; человек в паузе озвучки — подозрение, проверка, остановка
    Random rnd = new Random(7);
    int n = 16000 * 4; float[] ref = new float[n], mic = new float[n], quiet = new float[n];
    for (int i = 0; i < n; i++) {
      double v = 0.2 * Math.sin(2 * Math.PI * 220 * i / 16000.0) * (1 + Math.sin(2 * Math.PI * 3 * i / 16000.0)) / 2;
      ref[i] = (float) v; quiet[i] = (float) (rnd.nextGaussian() * 1e-3);
      mic[i] = (float) ((i >= 40 ? 0.5 * ref[i - 40] : 0) + quiet[i]);
    }
    ok(acts(quiet, new float[n], null).isEmpty(), "E1 тишина без озвучки — без действий");
    BargeIn warm = new BargeIn();
    List<int[]> echoOnly = acts(mic, ref, warm);
    ok(echoOnly.isEmpty(), "E2 одно эхо — без действий: " + str(echoOnly));
    // тот же звук, человек (шум 300–3000 Гц) с 2,5 с, эхо приглушается по действиям датчика
    float[] human = new float[n];
    for (int i = 40000; i < n; i++) human[i] = (float) (rnd.nextGaussian() * 0.05);
    List<int[]> a = closed(mic, ref, human, warm.g);
    ok(a.size() >= 2 && a.get(0)[1] == BargeIn.DUCK && a.get(1)[1] == BargeIn.STOP && a.get(0)[0] * BargeIn.HOP >= 40000 - BargeIn.N,
        "E3 человек поверх эха — приглушить, потом замолчать, не раньше человека: " + str(a));

    // эталон tools/barge_eval.py
    File gj = new File(dir, "barge_golden.json");
    if (!gj.isFile()) System.out.println("  (эталона нет — сверка пропущена)");
    else {
      JSONObject g = new JSONObject(new String(Files.readAllBytes(gj.toPath()), "UTF-8"));
      float[] m = wav(new File(dir, "barge_mic.wav")), r = wav(new File(dir, "barge_ref.wav"));
      ok(hops(m) - 1 == g.getInt("hops"), "G1 шагов " + (hops(m) - 1) + " ≠ " + g.getInt("hops"));
      JSONArray head = g.getJSONArray("band_power_head"); double worst = 0;
      for (int k = 0; k < head.length(); k++) { double[] p = BargeIn.power(m, (40 + k) * BargeIn.HOP);
        for (int b = 0; b < BargeIn.NB; b++) worst = Math.max(worst, Math.abs(p[b] - head.getJSONArray(k).getDouble(b)) / head.getJSONArray(k).getDouble(b)); }
      ok(worst < 1e-9, "G2 мощности полос совпали с эталоном (наибольшее отн. расхождение " + worst + ")");
      JSONArray g0 = g.getJSONArray("g0"); double[] gd = new double[BargeIn.NB]; for (int b = 0; b < gd.length; b++) gd[b] = g0.getDouble(b);
      BargeIn d = new BargeIn(gd, null);
      JSONArray sc = g.getJSONArray("score"); int bad = -1; List<int[]> got = new ArrayList<>();
      int hn = g.getInt("hops");
      for (int t = 0; t < hn; t++) {
        int act = d.frame(BargeIn.power(m, t * BargeIn.HOP), BargeIn.power(r, (t + 1) * BargeIn.HOP));
        if (act != BargeIn.NONE) got.add(new int[]{t, act});
        if (bad < 0 && Math.abs(d.score - sc.getDouble(t)) > 1e-6) bad = t;
      }
      ok(bad < 0, "G3 доли полос шаг в шаг" + (bad < 0 ? "" : ": расходятся с шага " + bad));
      JSONArray ea = g.getJSONArray("actions"); List<int[]> want = new ArrayList<>();
      for (int k = 0; k < ea.length(); k++) want.add(new int[]{ea.getJSONArray(k).getInt(0), ea.getJSONArray(k).getInt(1)});
      ok(str(got).equals(str(want)), "G4 действия " + str(got) + " = " + str(want));
      boolean undo = false, stop = false; for (int[] x : want) { undo |= x[1] == BargeIn.UNDUCK; stop |= x[1] == BargeIn.STOP; }
      ok(undo && stop, "G5 в эталоне есть и отбой (стук), и остановка (человек)");
      JSONArray ge = g.getJSONArray("gain_db"); double gw = 0; for (int b = 0; b < BargeIn.NB; b++) gw = Math.max(gw, Math.abs(d.g[b] - ge.getDouble(b)));
      ok(gw < 1e-6, "G6 усиление тракта в конце совпало (" + gw + " дБ)");
      if (g.has("att_db")) { JSONArray ga = g.getJSONArray("att_db"); double aw = 0, moved = 0;
        for (int b = 0; b < BargeIn.NB; b++) { aw = Math.max(aw, Math.abs(d.att[b] - ga.getDouble(b))); moved = Math.max(moved, Math.abs(d.att[b] - BargeIn.ATT0)); }
        ok(aw < 1e-6 && moved > 1, "G6 ослабление эха приглушением выучено на отбое и совпало (" + aw + " дБ, сдвинулось на " + moved + " дБ)"); }
      if (g.has("gate")) {
        JSONObject gt = g.getJSONObject("gate"); int a0 = gt.getInt("from"), a1 = gt.getInt("to"), upto = gt.getInt("upto");
        float[] y = BargeIn.gate(Arrays.copyOfRange(m, a0, a1), Arrays.copyOfRange(r, a0, a1), gd, upto);
        JSONArray ys = gt.getJSONArray("samples"); double yw = 0;
        for (int k = 0; k < ys.length(); k++) { int i = ys.getJSONArray(k).getInt(0); yw = Math.max(yw, Math.abs(y[i] - ys.getJSONArray(k).getDouble(1))); }
        ok(yw < 1e-5, "G7 подпор с погашенным эхом совпал с эталоном (" + yw + ")");
      }
    }
    System.out.println(fails == 0 ? "BargeIn: " + checks + " проверок, все прошли" : "BargeIn: провалов " + fails + " из " + checks);
    return fails;
  }

  /** Действия датчика по готовому звуку (без обратной связи). */
  static List<int[]> acts(float[] mic, float[] ref, BargeIn d) {
    if (d == null) d = new BargeIn();
    List<int[]> out = new ArrayList<>();
    for (int t = 0; t < hops(mic) - 1; t++) { int a = d.frame(BargeIn.power(mic, t * BargeIn.HOP), BargeIn.power(ref, (t + 1) * BargeIn.HOP)); if (a != BargeIn.NONE) out.add(new int[]{t, a}); }
    return out;
  }
  /** С обратной связью: приглушение и остановка слышны микрофону через STOP_MS после решения. */
  static List<int[]> closed(float[] echo, float[] ref, float[] human, double[] g) {
    BargeIn d = new BargeIn(g, null); List<int[]> out = new ArrayList<>();
    double[] scale = new double[echo.length]; Arrays.fill(scale, 1);
    float[] x = new float[echo.length];
    for (int t = 0; t < hops(echo) - 1; t++) {
      for (int i = t * BargeIn.HOP; i < Math.min(echo.length, t * BargeIn.HOP + BargeIn.N); i++) x[i] = (float) (echo[i] * scale[i] + human[i]);
      int a = d.frame(BargeIn.power(x, t * BargeIn.HOP), BargeIn.power(ref, (t + 1) * BargeIn.HOP));
      if (a == BargeIn.NONE) continue;
      out.add(new int[]{t, a});
      int at = t * BargeIn.HOP + BargeIn.N + BargeIn.STOP_MS * 16;
      double v = a == BargeIn.DUCK ? Math.pow(10, BargeIn.DUCK_DB / 20) : a == BargeIn.STOP ? 0 : 1;
      for (int i = at; i < echo.length; i++) scale[i] = a == BargeIn.UNDUCK ? 1 : Math.min(scale[i], v);
    }
    return out;
  }
  static String str(List<int[]> l) { StringBuilder b = new StringBuilder("["); for (int[] x : l) b.append(b.length() > 1 ? ", " : "").append(x[0]).append(':').append(x[1]); return b.append(']').toString(); }

  public static void main(String[] a) throws Exception {
    System.exit(run(new File(a.length > 0 ? a[0] : "bench/apk/test")) == 0 ? 0 : 1);
  }
}
