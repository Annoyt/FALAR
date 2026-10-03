package dev.agenttranslator;

import java.io.*;
import java.util.*;

/** Замер формата хранения кусочков речи (ClipCodec) на телефоне, без установки приложения:
 *  bench/audio/clip_bench.sh собирает этот класс вместе с ClipCodec в dex и запускает через app_process.
 *
 *  Кусочки — подряд по clipSec секунд из комнатной записи (bench/air/rec/<набор>/room.wav, 16 кГц),
 *  каждый сжимается отдельным файлом, как будет в приложении (кодек создаётся на каждый кусочек).
 *  Мерится: время на кусочек; процессорное время процесса кодеков media.swcodec (там работают
 *  программные кодеки Android) и своего потока; размер; время раскрытия; длина после раскрытия;
 *  искажение спектра (LSD, дБ) против исходника на кадрах речи.
 *
 *  Аргументы: wav outDir clipSec clips swcodecPid конфиги («ogg:24000,ogg:24000:5,m4a:32000,wav:0»;
 *  третье поле — сложность Opus 0…10).
 *  Вывод: по строке JSON на конфиг. */
public class ClipBench {
  public static void main(String[] a) throws Exception {
    File wav = new File(a[0]), dir = new File(a[1]); dir.mkdirs();
    double clipSec = Double.parseDouble(a[2]); int clips = Integer.parseInt(a[3]); int pid = Integer.parseInt(a[4]);
    int[] r = new int[1]; float[] all = ClipCodec.readWav(wav, r); int rate = r[0];
    int len = (int) (clipSec * rate);
    List<float[]> parts = new ArrayList<>();
    for (int o = 0; o + len <= all.length && parts.size() < clips; o += len) parts.add(Arrays.copyOfRange(all, o, o + len));
    for (String cfg : a[5].split(",")) {
      String[] cp = cfg.split(":"); String ext = cp[0]; int br = Integer.parseInt(cp[1]);
      int cx = cp.length > 2 ? Integer.parseInt(cp[2]) : -1;   // сложность Opus 0…10; −1 — как у кодека
      // Прогрев: первая загрузка кодека — не цена кусочка, её платит запуск.
      ClipCodec.encode(parts.get(0), rate, new File(dir, "warm." + ext), br, cx, null);
      long cpu0 = ticks(pid), thr0 = android.os.SystemClock.currentThreadTimeMillis(), bytes = 0;
      double[] wall = new double[parts.size()];
      for (int k = 0; k < parts.size(); k++) {
        long t = System.nanoTime();
        ClipCodec.encode(parts.get(k), rate, new File(dir, k + "." + ext), br, cx, null);
        wall[k] = (System.nanoTime() - t) / 1e6;
        bytes += new File(dir, k + "." + ext).length();
      }
      long cpu1 = ticks(pid), thr1 = android.os.SystemClock.currentThreadTimeMillis();
      double dec = 0, lsd = 0, durErr = 0; int lsdN = 0;
      for (int k = 0; k < parts.size(); k++) {
        long t = System.nanoTime();
        int[] dr = new int[1]; float[] back = ClipCodec.decode(new File(dir, k + "." + ext), dr);
        dec += (System.nanoTime() - t) / 1e6;
        float[] b16 = dr[0] == rate ? back : down(back, dr[0] / rate);
        durErr = Math.max(durErr, Math.abs(b16.length - (double) len) / len * 100);
        double d = lsd(parts.get(k), b16);
        if (!Double.isNaN(d)) { lsd += d; lsdN++; }
      }
      double audio = parts.size() * clipSec;
      Arrays.sort(wall);
      System.out.println(String.format(Locale.ROOT,
          "{\"cfg\":\"%s\",\"clips\":%d,\"audio_s\":%.1f,\"bytes\":%d,\"kbps\":%.1f,\"wall_ms_mean\":%.1f,\"wall_ms_p95\":%.1f,"
          + "\"swcodec_cpu_ms\":%d,\"thread_cpu_ms\":%d,\"cpu_ms_per_audio_s\":%.2f,\"decode_ms_mean\":%.1f,\"lsd_db\":%.2f,\"dur_err_pct_max\":%.2f}",
          cfg, parts.size(), audio, bytes, bytes * 8 / audio / 1000, mean(wall), wall[(int) Math.min(wall.length - 1, Math.round(wall.length * 0.95))],
          (cpu1 - cpu0) * 10, thr1 - thr0, ((cpu1 - cpu0) * 10 + (thr1 - thr0)) / audio, dec / parts.size(), lsdN == 0 ? Double.NaN : lsd / lsdN, durErr));
    }
  }

  /** utime+stime процесса в тиках по 10 мс (/proc/<pid>/stat, поля 14 и 15). */
  static long ticks(int pid) {
    try (BufferedReader b = new BufferedReader(new FileReader("/proc/" + pid + "/stat"))) {
      String s = b.readLine(); String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
      return Long.parseLong(f[11]) + Long.parseLong(f[12]);
    } catch (Exception e) { return -1; }
  }

  static double mean(double[] x) { double s = 0; for (double v : x) s += v; return s / x.length; }

  /** Понижение частоты в q раз: окно-синус (63 отвода) как фильтр перед прореживанием. */
  static float[] down(float[] x, int q) {
    int taps = 63, h = taps / 2; double[] w = new double[taps]; double fc = 0.45 / q, sum = 0;
    for (int k = 0; k < taps; k++) { int m = k - h; double s = m == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m);
      w[k] = s * (0.54 - 0.46 * Math.cos(2 * Math.PI * k / (taps - 1))); sum += w[k]; }
    float[] y = new float[x.length / q];
    for (int i = 0; i < y.length; i++) { double s = 0; int c = i * q;
      for (int k = 0; k < taps; k++) { int j = c + k - h; if (j >= 0 && j < x.length) s += x[j] * w[k]; }
      y[i] = (float) (s / sum); }
    return y;
  }

  /** Искажение спектра: кадры 512 (32 мс) с шагом 256, полоса до 7 кГц, только кадры исходника
   *  громче −50 дБ полной шкалы; сдвиг кодека находится по взаимной корреляции в пределах ±100 мс. */
  static double lsd(float[] x, float[] y) {
    int best = 0; double bc = -1;
    int n = Math.min(Math.min(x.length, y.length) - 1600, 32000);
    for (int lag = -1600; lag <= 1600; lag += 2) {
      double c = 0; for (int i = 1600; i < n; i += 4) { int j = i + lag; if (j >= 0 && j < y.length) c += x[i] * y[j]; }
      if (c > bc) { bc = c; best = lag; }
    }
    double tot = 0; int frames = 0; double[] re = new double[512], im = new double[512], px = new double[257];
    for (int o = 0; o + 512 <= x.length && o + best + 512 <= y.length; o += 256) {
      if (o + best < 0) continue;
      double e = 0; for (int k = 0; k < 512; k++) e += x[o + k] * x[o + k];
      if (10 * Math.log10(e / 512 + 1e-12) < -50) continue;
      spec(x, o, re, im, px); double[] py = new double[257]; spec(y, o + best, re, im, py);
      double s = 0; int bins = 0;
      for (int b = 1; b <= 224; b++) { double d = 10 * Math.log10(px[b] + 1e-10) - 10 * Math.log10(py[b] + 1e-10); s += d * d; bins++; }
      tot += Math.sqrt(s / bins); frames++;
    }
    return frames == 0 ? Double.NaN : tot / frames;
  }

  static void spec(float[] x, int o, double[] re, double[] im, double[] p) {
    for (int k = 0; k < 512; k++) { re[k] = x[o + k] * (0.5 - 0.5 * Math.cos(2 * Math.PI * k / 511)); im[k] = 0; }
    fft(re, im);
    for (int b = 0; b <= 256; b++) p[b] = re[b] * re[b] + im[b] * im[b];
  }

  static void fft(double[] re, double[] im) {
    int n = re.length;
    for (int i = 1, j = 0; i < n; i++) { int bit = n >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit;
      if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
    for (int len = 2; len <= n; len <<= 1) {
      double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
      for (int i = 0; i < n; i += len) { double cr = 1, ci = 0;
        for (int k = 0; k < len / 2; k++) {
          int u = i + k, v = i + k + len / 2;
          double tr = re[v] * cr - im[v] * ci, ti = re[v] * ci + im[v] * cr;
          re[v] = re[u] - tr; im[v] = im[u] - ti; re[u] += tr; im[u] += ti;
          double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
        } }
    }
  }
}
