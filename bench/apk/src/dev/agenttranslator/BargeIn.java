package dev.agenttranslator;

/** Перебивание: человек заговорил поверх озвучки. Пока телефон говорит в динамик, микрофон глух, и всё
 *  сказанное поверх терялось (владелец 01.10: «перестаёт записывать диалог»). Здесь — заметить человека по
 *  микрофону и по тому, что в тот же миг играло, приглушить озвучку, убедиться и замолчать.
 *
 *  Кадр 32 мс, шаг 16 мс, 20 мел-полос 150–6000 Гц. Ожидаемое эхо в полосе — мощность сыгранного в ней,
 *  «размазанная» по отражениям (−DECAY дБ на шаг, TAIL шагов) и по неточности совмещения (±JIT шагов),
 *  умноженная на усиление тракта «динамик → микрофон» (учится по ходу, пока человека не видно), плюс доля
 *  всего эха (искажения динамика) и фон. Полоса «за человеком», если микрофон громче ожидаемого на THETA дБ
 *  и громче фона на NOISE_DB.
 *  Ступень 1 — подозрение: таких полос не меньше SHARE1 в K1 кадрах из M1 → DUCK (озвучка тише на 20 дБ).
 *  Ступень 2 — проверка на приглушённом эхе: человек теперь громче эха в большинстве полос → STOP; за WIN2
 *  шагов не подтвердилось → UNDUCK (вместо оборванной фразы — провал громкости на полсекунды).
 *
 *  Свой линейный подавитель эха и встроенный в Android не дают распознать человека поверх озвучки вовсе
 *  (results/2026-10-01-voices.md §12, results/2026-10-02-barge.md); перебивание не очищает звук, а только
 *  замечает человека — для этого хватает сравнения мощностей по полосам. Пороги — по записям телефона
 *  (tools/barge_eval.py — тот же счёт на Python; BargeInTest сверяет шаг в шаг).
 *
 *  Без Android: проверяется на столе (bench/apk/test/BargeInTest.java). */
final class BargeIn {
  static final int SR = 16000, N = 512, HOP = 256, NB = 20;
  static final double F_LO = 150, F_HI = 6000;
  static final double THETA1 = 10, SHARE1 = 0.10; static final int K1 = 2, M1 = 2;
  static final double THETA2 = 8, SHARE2 = 0.25; static final int K2 = 3, M2 = 4;
  static final double DECAY = 4, NOISE_DB = 6, SPREAD_DB = -25, DUCK_DB = -20;
  /** Насколько тише эхо в комнате при приглушении на DUCK_DB — сначала считаем, что мало: на громкости 15 у Redmi
   *  −20 дБ в цифре дают −10…−11 дБ у микрофона (усилитель на пределе сжимает). Дальше учится по отбоям. */
  static final double ATT0 = -10;
  static final int TAIL = 10, JIT = 1, GRACE = 10;
  /** После решения звук ещё идёт: буферы вывода после метки времени, ~80 мс на Redmi. */
  static final int STOP_MS = 100;
  /** Через столько шагов после подозрения приглушение уже слышно микрофону. */
  static final int EFF = (N + STOP_MS * 16 + HOP - 1) / HOP;
  static final int WIN2 = 25;
  /** Шагов с заметным эхом на прогрев новой громкости: с нулевого усиления громкое эхо само выглядело бы человеком. */
  static final int WARM = 150;
  static final int NONE = 0, DUCK = 1, STOP = 2, UNDUCK = 3;

  static final double[] WIN = new double[N];
  /** Полоса b — бины [B0[b], B1[b]). */
  static final int[] B0 = new int[NB], B1 = new int[NB];
  static {
    for (int i = 0; i < N; i++) WIN[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);
    double lo = mel(F_LO), hi = mel(F_HI);
    double[] e = new double[NB + 1];
    for (int i = 0; i <= NB; i++) e[i] = imel(i == NB ? hi : lo + (hi - lo) / NB * i);
    for (int b = 0; b < NB; b++) {
      int k0 = -1, k1 = -1;
      for (int k = 0; k <= N / 2; k++) { double f = k * (double) SR / N; if (f >= e[b] && f < e[b + 1]) { if (k0 < 0) k0 = k; k1 = k + 1; } }
      B0[b] = Math.max(0, k0); B1[b] = Math.max(B0[b], k1);
    }
  }
  static double mel(double f) { return 2595.0 * Math.log10(1.0 + f / 700.0); }
  static double imel(double m) { return 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0); }

  /** Мощность кадра x[off..off+N) по полосам (окно Ханна, БПФ). */
  static double[] power(float[] x, int off) {
    double[] re = new double[N], im = new double[N];
    for (int i = 0; i < N; i++) { int j = off + i; re[Fbank.REV[i]] = (j >= 0 && j < x.length ? x[j] : 0) * WIN[i]; }
    Fbank.fft(re, im);
    double[] p = new double[NB];
    for (int b = 0; b < NB; b++) { double s = 0; for (int k = B0[b]; k < B1[b]; k++) s += re[k] * re[k] + im[k] * im[k]; p[b] = s + 1e-12; }
    return p;
  }

  double[] g = new double[NB];          // усиление тракта по полосам, дБ — хранится между фразами
  double[] att = new double[NB];        // ослабление эха приглушением по полосам, дБ — тоже хранится
  final double[] obs = new double[NB], nobs = new double[NB];
  double[] noise;                       // фон по полосам
  final double[][] hist = new double[TAIL + 3][];
  int nh = 0;
  final double[] smax = new double[NB];
  int since = -1, t = 0, fireT = -1;
  final boolean[] hits = new boolean[M1], hits2 = new boolean[M2];
  int nHits = 0, nHits2 = 0;
  String state = "listen";
  double score, lev = -200;

  BargeIn() { java.util.Arrays.fill(smax, 1e-12); java.util.Arrays.fill(att, ATT0); }
  BargeIn(double[] gDb, double[] noise) { this(); if (gDb != null) g = gDb.clone(); if (noise != null) this.noise = noise.clone(); }
  BargeIn(double[] gDb, double[] noise, double[] attDb) { this(gDb, noise); if (attDb != null) att = attDb.clone(); }

  /** Новая фраза озвучки: всё, кроме усиления тракта и фона. */
  void reset() { nh = 0; nHits = 0; nHits2 = 0; since = -1; state = "listen"; fireT = -1; }

  double[] smear() {
    double[] s = new double[NB];
    for (int j = -JIT; j <= TAIL; j++) {
      int i = nh - 2 - j;
      if (i < 0 || i >= nh) continue;
      double w = Math.pow(10, -Math.max(0, j) * DECAY / 10);
      for (int b = 0; b < NB; b++) s[b] = Math.max(s[b], hist[i][b] * w);
    }
    return s;
  }

  /** Полосы «за человеком»: маска и ожидаемое эхо (без фона и искажений) — в e. */
  boolean[] cells(double[] m, double[] s, double[] gDb, double add, double theta, double[] e) {
    double sum = 0;
    for (int b = 0; b < NB; b++) { e[b] = s[b] * Math.pow(10, (gDb[b] + add) / 10); sum += e[b]; }
    double spread = sum * Math.pow(10, SPREAD_DB / 10), floor = Math.pow(10, NOISE_DB / 10);
    boolean[] c = new boolean[NB];
    for (int b = 0; b < NB; b++) c[b] = 10 * Math.log10(m[b] / (e[b] + spread + noise[b])) > theta && m[b] > noise[b] * floor;
    return c;
  }
  static double[] add(double[] a, double[] b) { double[] r = new double[a.length]; for (int i = 0; i < a.length; i++) r[i] = a[i] + b[i]; return r; }
  static double share(boolean[] c) { int k = 0; for (boolean x : c) if (x) k++; return k / (double) c.length; }

  /** Шаг: m — мощности кадра микрофона, rNext — сыгранного на шаг вперёд (оно известно заранее). */
  int frame(double[] m, double[] rNext, boolean learn) {
    int now = t++;
    if (nh == hist.length) { System.arraycopy(hist, 1, hist, 0, nh - 1); nh--; }
    hist[nh++] = rNext.clone();
    double[] s = smear();
    if (noise == null) noise = m.clone();
    if (state.equals("stopped")) return NONE;
    double[] e = new double[NB];
    if (state.equals("ducked")) {
      if (now < fireT + EFF) return NONE;
      if (now >= fireT + EFF + WIN2) {
        state = "listen"; nHits = 0;
        // отбой — человека не было: как на самом деле приглушение ослабило эхо, учим по полосам
        for (int b = 0; b < NB; b++) if (nobs[b] >= 5) att[b] = Math.max(DUCK_DB, Math.min(0, att[b] + 0.3 * (obs[b] / nobs[b] - att[b])));
        return UNDUCK;
      }
      double seenAt = Math.pow(10, 15 / 10.0);
      for (int b = 0; b < NB; b++) { double e0 = s[b] * Math.pow(10, g[b] / 10); if (e0 > noise[b] * seenAt) { obs[b] += 10 * Math.log10(m[b] / (e0 + 1e-12)); nobs[b]++; } }
      boolean[] c = cells(m, s, add(g, att), 0, THETA2, e);
      score = share(c);
      push(hits2, score >= SHARE2, true);
      if (count(hits2, nHits2) >= K2) {
        state = "stopped";
        double v = 0; for (int b = 0; b < NB; b++) if (c[b]) v += m[b];
        lev = 10 * Math.log10(v + 1e-12);
        return STOP;
      }
      return NONE;
    }
    // фон — где сыгранного нет вовсе: вниз быстро, вверх медленно
    for (int b = 0; b < NB; b++) if (s[b] < 1e-9) noise[b] += (m[b] < noise[b] ? 0.3 : 0.01) * (m[b] - noise[b]);
    boolean[] c = cells(m, s, g, 0, THETA1, e);
    score = share(c);
    double es = 0, ns = 0; for (int b = 0; b < NB; b++) { es += e[b]; ns += noise[b]; }
    if (since < 0 && es > ns) since = 0; else if (since >= 0) since++;
    boolean hit = score >= SHARE1 && since > GRACE;
    push(hits, hit, false);
    // усиление тракта: учится, пока человека не видно, по полосам с заметным эхом (в пределах 15 дБ от недавнего
    // максимума) — следит за верхней четвертью отношения микрофон/сыгранное
    double dec = Math.pow(10, -0.05);
    for (int b = 0; b < NB; b++) smax[b] = Math.max(smax[b] * dec, s[b]);
    if (learn && count(hits, nHits) == 0)
      for (int b = 0; b < NB; b++) if (teach(b, s, e)) g[b] += 10 * Math.log10(m[b] / (s[b] + 1e-12)) > g[b] ? 0.375 : -0.125;
    if (count(hits, nHits) >= K1) { state = "ducked"; fireT = now; nHits2 = 0; java.util.Arrays.fill(obs, 0); java.util.Arrays.fill(nobs, 0); return DUCK; }
    return NONE;
  }
  int frame(double[] m, double[] rNext) { return frame(m, rNext, true); }

  /** Прогрев: только учиться (фон, усиление тракта), без решений. Пока усиление для этой громкости не выучено,
   *  решения по нему дали бы ложные подозрения на каждой фразе. Возвращает, училось ли усиление на этом шаге. */
  boolean learn(double[] m, double[] rNext) {
    t++;
    if (nh == hist.length) { System.arraycopy(hist, 1, hist, 0, nh - 1); nh--; }
    hist[nh++] = rNext.clone();
    double[] s = smear();
    if (noise == null) noise = m.clone();
    for (int b = 0; b < NB; b++) if (s[b] < 1e-9) noise[b] += (m[b] < noise[b] ? 0.3 : 0.01) * (m[b] - noise[b]);
    double dec = Math.pow(10, -0.05); boolean any = false;
    for (int b = 0; b < NB; b++) smax[b] = Math.max(smax[b] * dec, s[b]);
    double[] e = new double[NB]; for (int b = 0; b < NB; b++) e[b] = s[b] * Math.pow(10, g[b] / 10);
    for (int b = 0; b < NB; b++) if (teach(b, s, e)) { g[b] += 10 * Math.log10(m[b] / (s[b] + 1e-12)) > g[b] ? 0.375 : -0.125; any = true; }
    return any;
  }
  /** Учиться в полосе можно, где эхо заметное: не ниже 15 дБ от недавнего максимума сыгранного и громче фона
   *  (между фразами и в паузах усиление по шуму не уползает). */
  boolean teach(int b, double[] s, double[] e) { return s[b] > smax[b] * Math.pow(10, -1.5) && e[b] > noise[b]; }

  /** На сколько шагов огибающая микрофона отстаёт от огибающей сыгранного (mic[k] ≈ ref[k − lag]), дробно, и
   *  сходство при этом сдвиге. Метки времени Android не видят части буферов вывода и ввода — на Redmi это
   *  постоянные 78 мс; здесь они находятся по самому эху, без записи и без стенда. */
  static double[] lag(double[] mic, double[] ref, int n, int maxLag) {
    double[] a = new double[n], r = new double[n];
    for (int k = 0; k < n; k++) { a[k] = 10 * Math.log10(mic[k] + 1e-12); r[k] = 10 * Math.log10(ref[k] + 1e-12); }
    double[] c = new double[maxLag + 1]; int best = -1;
    for (int L = 0; L <= maxLag; L++) {
      double ma = 0, mr = 0; int cnt = n - maxLag; if (cnt < 8) return new double[]{Double.NaN, 0};
      for (int k = maxLag; k < n; k++) { ma += a[k]; mr += r[k - L]; }
      ma /= cnt; mr /= cnt;
      double sab = 0, saa = 0, srr = 0;
      for (int k = maxLag; k < n; k++) { double x = a[k] - ma, y = r[k - L] - mr; sab += x * y; saa += x * x; srr += y * y; }
      c[L] = sab / Math.sqrt(saa * srr + 1e-12);
      if (best < 0 || c[L] > c[best]) best = L;
    }
    double d = 0;
    if (best > 0 && best < maxLag) { double y0 = c[best - 1], y1 = c[best], y2 = c[best + 1], den = y0 - 2 * y1 + y2; if (den < 0) d = 0.5 * (y0 - y2) / den; }
    return new double[]{best + d, c[best]};
  }

  /** Кольцо последних решений: hits — M1, hits2 — M2. */
  void push(boolean[] ring, boolean v, boolean second) {
    int n = second ? nHits2 : nHits;
    if (n == ring.length) { System.arraycopy(ring, 1, ring, 0, n - 1); n--; }
    ring[n++] = v;
    if (second) nHits2 = n; else nHits = n;
  }
  static int count(boolean[] ring, int n) { int k = 0; for (int i = 0; i < n; i++) if (ring[i]) k++; return k; }

  /** Подпор с погашенным эхом: в кадрах до upto полосы, где ожидаемое эхо сравнимо с микрофоном, глушатся (как
   *  подавление остатка, но только на подпоре — дальше озвучка уже приглушена). ref — сыгранное, совмещённое с x. */
  static float[] gate(float[] x, float[] ref, double[] gDb, int upto) {
    int n = x.length; double[] out = new double[n + N], nrm = new double[n + N];
    int lim = Math.min(n, upto);
    for (int t0 = 0; t0 < lim - N; t0 += HOP) {
      double[] re = new double[N], im = new double[N], rr = new double[N], ri = new double[N];
      for (int i = 0; i < N; i++) { re[Fbank.REV[i]] = x[t0 + i] * WIN[i]; rr[Fbank.REV[i]] = (t0 + i < ref.length ? ref[t0 + i] : 0) * WIN[i]; }
      Fbank.fft(re, im); Fbank.fft(rr, ri);
      double[] eb = new double[N / 2 + 1];
      for (int b = 0; b < NB; b++) {
        double s = 0; for (int k = B0[b]; k < B1[b]; k++) s += rr[k] * rr[k] + ri[k] * ri[k];
        double v = s * Math.pow(10, gDb[b] / 10) / Math.max(1, B1[b] - B0[b]);
        for (int k = B0[b]; k < B1[b]; k++) eb[k] = v;
      }
      // спектр Эрмита: множитель на бины 0..N/2, зеркальные — тем же
      for (int k = 0; k <= N / 2; k++) {
        double gk = Math.max(0.05, 1 - 2.0 * eb[k] / (re[k] * re[k] + im[k] * im[k] + 1e-12));
        re[k] *= gk; im[k] *= gk;
        if (k > 0 && k < N / 2) { re[N - k] *= gk; im[N - k] *= gk; }
      }
      // обратное БПФ: сопряжение, прямое, сопряжение и деление на N
      double[] br = new double[N], bi = new double[N];
      for (int i = 0; i < N; i++) { br[Fbank.REV[i]] = re[i]; bi[Fbank.REV[i]] = -im[i]; }
      Fbank.fft(br, bi);
      for (int i = 0; i < N; i++) { out[t0 + i] += br[i] / N * WIN[i]; nrm[t0 + i] += WIN[i] * WIN[i]; }
    }
    float[] y = new float[n];
    for (int i = 0; i < n; i++) y[i] = i < lim ? (float) (out[i] / Math.max(nrm[i], 1e-6)) : x[i];
    return y;
  }
}
