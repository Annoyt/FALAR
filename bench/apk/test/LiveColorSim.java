package dev.agenttranslator;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Цвет кнопки удержания по ходу записи — прежний счёт против Hearing.Live, на записях комнаты
 *  (bench/air/rec). Удержания строятся из самих записей: где и сколько играла фраза, известно из
 *  player.tsv. Цепочка показа — как на телефоне: кадр 32 мс → служба (liveQ) → опрос экрана раз в
 *  100 мс (MainActivity.meterTick) → шаг кнопки раз в 33 мс со сглаживанием 0,4 и оттенком шагами
 *  по 1/24 (MicButton). Мерило — итог фразы, который служба пишет в журнал после отпускания: тот
 *  же Hearing.quality по Gain.speechFloor всей записи удержания, с авто-чувствительностью.
 *
 *  Запуск: bash bench/air/live_color.sh [записи…] */
public class LiveColorSim {
  static final int SR = 16000, F = 512;
  static final double TARGET = -24, AUTO_MIN = -12, AUTO_MAX = 24;
  static double clampAuto(double db) { return Math.max(AUTO_MIN, Math.min(AUTO_MAX, db)); }
  static double db(double x) { return 20 * Math.log10(Math.max(1e-9, x)); }
  static double rms(float[] s, int o, int n) { double a = 0; for (int k = o; k < o + n; k++) a += s[k] * (double) s[k]; return Math.sqrt(a / n); }

  static float[] wav(Path p) throws IOException {
    byte[] b = Files.readAllBytes(p); int o = 12, data = -1, len = 0;
    while (o + 8 <= b.length) {
      String id = new String(b, o, 4, "US-ASCII"); int n = (b[o + 4] & 255) | (b[o + 5] & 255) << 8 | (b[o + 6] & 255) << 16 | (b[o + 7] & 255) << 24;
      if (id.equals("data")) { data = o + 8; len = Math.min(n, b.length - data); break; }
      o += 8 + n + (n & 1);
    }
    float[] s = new float[len / 2];
    for (int k = 0; k < s.length; k++) s[k] = (short) ((b[data + 2 * k] & 255) | (b[data + 2 * k + 1] << 8)) / 32768f;
    return s;
  }

  /** Фразы записи: [начало, конец] в отсчётах — по звуку, внутри окна, где она играла. */
  static List<int[]> phrases(Path dir, float[] s) throws IOException {
    long pOff = Long.parseLong(Files.readString(dir.resolve("player.offset")).trim());
    long lOff = Long.parseLong(Files.readString(dir.resolve("listener.offset")).trim());
    long raw = -1;
    for (String l : Files.readAllLines(dir.resolve("listener.tsv"))) { String[] p = l.split("\t"); if (p.length > 1 && p[1].equals("raw_begin")) { raw = Long.parseLong(p[0]) - lOff; break; } }
    List<int[]> out = new ArrayList<>();
    for (String l : Files.readAllLines(dir.resolve("player.tsv"))) {
      String[] p = l.split("\t"); if (p.length < 4 || !p[1].equals("play")) continue;
      long at = Long.parseLong(p[0]) - pOff - raw; double dur = Double.parseDouble(p[3]);
      int w0 = (int) Math.max(0, (at - 300) * 16), w1 = (int) Math.min(s.length - F, (at + dur + 900) * 16);
      if (w1 - w0 < 20 * F) continue;
      // Фон окна — 10-й процентиль кадров от 1,5 с до начала до конца окна; речь — кадры на 10 дБ выше.
      int b0 = Math.max(0, w0 - 24000);
      double[] lv = new double[(w1 - b0) / F]; for (int j = 0; j < lv.length; j++) lv[j] = db(rms(s, b0 + j * F, F));
      double[] so = lv.clone(); Arrays.sort(so); double floor = so[(int) (so.length * 0.1)];
      int first = -1, last = -1;
      for (int j = (w0 - b0) / F; j < lv.length; j++) if (lv[j] >= floor + 10) { if (first < 0) first = j; last = j; }
      if (first < 0) continue;
      out.add(new int[]{b0 + first * F, b0 + (last + 1) * F});
    }
    return out;
  }

  /** Прежний счёт: TranslatorService.level() + live() до 29.09 — пик с плавным спадом и мгновенный запас. */
  static final class Old {
    float floor = Float.NaN, peak = -120, q = 0;
    double frame(float[] c, int o) {
      float r = (float) db(rms(c, o, F));
      floor = Float.isNaN(floor) ? r : Math.min(r, floor + 0.03f);
      peak = Math.max(r, peak - 1.5f);
      double eff = peak + clampAuto(TARGET - peak);
      if (!Float.isNaN(floor) && peak - floor >= 10) q += 0.3f * ((float) Hearing.quality(eff, peak - floor) - q);
      else q = Math.max(0, q - 0.03f);
      return q;
    }
  }

  /** Новый: Hearing.Live, как в TranslatorService.level(); room — фон комнаты от слушания (NaN — не было). */
  static final class New {
    final Hearing.Live h = new Hearing.Live();
    New(double room) { h.seed(room); }
    New() { this(Double.NaN); }
    double frame(float[] c, int o) {
      h.frame(db(rms(c, o, F)));
      double sp = h.speechDb();
      return h.q(Double.isNaN(sp) ? sp : sp + clampAuto(TARGET - sp));
    }
  }

  /** Показ: опрос службы раз в 100 мс, шаг кнопки раз в 33,3 мс (сглаживание 0,4, оттенок по 1/24). */
  static double[] shown(float[] s, int from, int to, boolean old) { return shown(s, from, to, old, Double.NaN); }
  static double[] shown(float[] s, int from, int to, boolean old, double room) {
    Old a = new Old(); New b = new New(room);
    int nf = (to - from) / F; double[] fq = new double[nf];
    for (int j = 0; j < nf; j++) fq[j] = old ? a.frame(s, from + j * F) : b.frame(s, from + j * F);
    double ms = nf * 32.0; int steps = (int) (ms / 33.333);
    double[] out = new double[steps]; double level = -1, sh = -1; double polled = -1e9;
    for (int k = 0; k < steps; k++) {
      double t = k * 33.333;
      if (t - polled >= 100) { polled = t; int j = Math.min(nf - 1, (int) (t / 32)); level = fq[j]; }
      sh = level < 0 ? -1 : sh < 0 ? level : sh + (level - sh) * 0.4;
      out[k] = sh < 0 ? -1 : Math.round(sh * 24) / 24.0;
    }
    return out;
  }

  /** Итог фразы — как в журнале после отпускания (pttStop + hear): авто-чувствительность по речи. */
  static double verdict(float[] s, int from, int to) { return verdict(s, from, to, Double.NaN); }
  static double verdict(float[] s, int from, int to, double room) {
    double[] sf = Gain.speechFloor(Arrays.copyOfRange(s, from, to), room);
    if (Double.isNaN(sf[0])) return 0;
    return Hearing.quality(sf[0] + clampAuto(TARGET - sf[0]), sf[0] - sf[1]);
  }
  /** Итог по прежнему счёту фона (10 % самых тихих кадров, до 30.09) — для сравнения. */
  static double verdictP10(float[] s, int from, int to) {
    float[] h = Arrays.copyOfRange(s, from, to); int m = h.length / F; if (m == 0) return 0;
    double[] p = new double[m]; for (int j = 0; j < m; j++) { double a = rms(h, j * F, F); p[j] = a * a; }
    double[] q = p.clone(); Arrays.sort(q); double fl = q[(int) ((m - 1) * 0.1)], sum = 0; int c = 0;
    for (double x : p) if (x >= fl * 10) { sum += x; c++; }
    if (c == 0) return 0;
    double sp = 10 * Math.log10(sum / c), f = 10 * Math.log10(fl);
    return Hearing.quality(sp + clampAuto(TARGET - sp), sp - f);
  }

  static double last(double[] q) { return q.length == 0 ? -1 : q[q.length - 1]; }

  static final class Stat {
    int holds = 0, green = 0, dips = 0, greenDips = 0, redStart = 0, firstLow = 0; double span = 0, below = 0, orange = 0, greenSpan = 0, firstMs = 0, firstN = 0, endGap = 0;
    void add(double[] q, int speechAt, double ref) {
      holds++; boolean g = ref >= 0.75; if (g) green++;
      int k0 = (int) (speechAt * 32 / 33.333);
      boolean in = false; int first = -1;
      for (int k = 0; k < q.length; k++) {
        if (q[k] >= 0 && first < 0) first = k;
        if (k < k0) { if (q[k] >= 0 && q[k] < 0.5 && k < 45) redStart++; continue; }
        span++; if (g) greenSpan++;
        double v = q[k] < 0 ? ref : q[k];            // нейтральный (без цвета) — не провал
        if (v < ref - 0.2) { below++; if (!in) { dips++; if (g) greenDips++; in = true; } } else if (v >= ref - 0.1) in = false;
        if (g && v < 0.5) orange++;
      }
      if (first >= 0) { firstMs += first * 33.333; firstN++; if (q[first] < ref - 0.2) firstLow++; }
      endGap += Math.abs((q.length > 0 && q[q.length - 1] >= 0 ? q[q.length - 1] : 0) - ref);
    }
    String row(String name) {
      return String.format(Locale.ROOT, "%-5s удержаний %3d (зелёных по итогу %3d) | ниже итога на 0,2+: %5.1f%% времени речи, провалов %4d (на зелёных %3d) | оранжевее 0,5 на зелёных: %5.1f%% | цвет с %4.0f мс, первый ниже итога: %2d | к отпусканию ±%.2f | красный в первые 1,5 с: %d шагов",
          name, holds, green, 100 * below / Math.max(1, span), dips, greenDips, 100 * orange / Math.max(1, greenSpan), firstMs / Math.max(1, firstN), firstLow, endGap / Math.max(1, holds), redStart);
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    List<String> recs = args.length > 1 ? Arrays.asList(args).subList(1, args.length) : List.of("near-pt", "noisy-pt", "far-pt", "near-ru", "far-ru");
    for (String r : recs) {
      Path d = root.resolve(r); float[] s = wav(d.resolve("room.wav")); List<int[]> ph = phrases(d, s);
      // Три расстановки удержания: (1) нажал за 0,7 с до фразы, отпустил через 0,4 с после;
      // (2) заговорил сразу — нажал за 50 мс; (3) три фразы подряд одним удержанием с паузами по 0,6 с.
      Stat[][] st = new Stat[3][2]; for (Stat[] x : st) { x[0] = new Stat(); x[1] = new Stat(); }
      for (int i = 0; i < ph.size(); i++) {
        int on = ph.get(i)[0], off = ph.get(i)[1];
        int[][] win = {{on - 700 * 16, off + 400 * 16}, {on - 50 * 16, off + 400 * 16}};
        for (int w = 0; w < 2; w++) {
          int a = Math.max(0, win[w][0]), b = Math.min(s.length, win[w][1]);
          double ref = verdict(s, a, b);
          for (int m = 0; m < 2; m++) st[w][m].add(shown(s, a, b, m == 0), (on - a) / F, ref);
        }
      }
      for (int i = 0; i + 2 < ph.size(); i += 3) {
        // Склейка: 0,7 с тишины перед первой, фраза, 0,6 с тишины комнаты перед следующей, …, 0,4 с хвоста.
        List<float[]> parts = new ArrayList<>();
        int on0 = ph.get(i)[0];
        parts.add(Arrays.copyOfRange(s, Math.max(0, on0 - 700 * 16), on0));
        for (int j = i; j < i + 3; j++) {
          int on = ph.get(j)[0], off = ph.get(j)[1];
          if (j > i) parts.add(Arrays.copyOfRange(s, Math.max(0, on - 600 * 16 - 50 * 16), on - 50 * 16));
          parts.add(Arrays.copyOfRange(s, on - 50 * 16, Math.min(s.length, off + 150 * 16)));
        }
        int offL = ph.get(i + 2)[1] + 150 * 16; parts.add(Arrays.copyOfRange(s, Math.min(s.length, offL), Math.min(s.length, offL + 400 * 16)));
        int n = 0; for (float[] p : parts) n += p.length; float[] h = new float[n]; int o = 0; for (float[] p : parts) { System.arraycopy(p, 0, h, o, p.length); o += p.length; }
        double ref = verdict(h, 0, h.length);
        for (int m = 0; m < 2; m++) st[2][m].add(shown(h, 0, h.length, m == 0), 700 * 16 / F, ref);
      }
      String[] names = {"нажал за 0,7 с до речи", "заговорил сразу (за 50 мс)", "три фразы одним удержанием, паузы 0,6 с"};
      System.out.println("== " + r + ": фраз " + ph.size());
      for (int w = 0; w < 3; w++) { System.out.println("  " + names[w]); System.out.println("    " + st[w][0].row("было")); System.out.println("    " + st[w][1].row("стало")); }
      // Вплотную: заговорил сразу и отпустил сразу — тишины в удержании нет (владелец 29.09 говорил
      // так, близко и громко). Мерило здесь — не итог, а истинный запас: фон — тишина комнаты за
      // 1,5 с до фразы (её бы и намерило слушание). Цвет к отпусканию и итог — против истинного.
      double[] sum = new double[7]; int[] off = new int[7]; int n = 0, good = 0;
      for (int[] p1 : ph) {
        int on = p1[0], end = p1[1];
        double room = Gain.speechFloor(Arrays.copyOfRange(s, Math.max(0, on - 24000), on - 1600))[1];
        double tq = verdict(s, on, end, room);            // истинный: фон комнаты заведомо ниже тихих мест речи
        double[] v = {tq, verdictP10(s, on, end), verdict(s, on, end), verdict(s, on, end, room),
            last(shown(s, on, end, true)), last(shown(s, on, end, false)), last(shown(s, on, end, false, room))};
        n++; boolean g = tq >= 0.75; if (g) good++;
        for (int k = 0; k < 7; k++) { sum[k] += Math.max(0, v[k]); if (g && v[k] < 0.5) off[k]++; }
      }
      System.out.printf(Locale.ROOT, "  вплотную, без тишины: удержаний %d, зелёных по истинному запасу %d; средний цвет (0…1) и сколько зелёных показано оранжевым/красным (< 0,5)%n", n, good);
      String[] vn = {"истинный", "итог прежний (10 %)", "итог новый (2 %)", "итог новый + фон слушания", "цвет прежний", "цвет новый", "цвет новый + фон слушания"};
      for (int k = 0; k < 7; k++) System.out.printf(Locale.ROOT, "    %-26s %.2f  оранжевым/красным %d%n", vn[k], sum[k] / n, off[k]);
    }
  }
}
