package dev.agenttranslator;

/** Дешёвый шумодав для детектора речи в тишине — фильтр Винера, без нейросети.
 *
 *  silero v4 после динамика и комнаты не признаёт речью голоса отдельных людей: на near-pt не доходит
 *  10 фраз из 82, и это одни и те же фразы на ближней и дальней записи; вероятность у них 0,44–0,70 при
 *  пороге 0,5. Если детектор слышит звук после этого фильтра (распознаватель — по-прежнему исходный),
 *  на ПК near-pt теряет 2 фразы вместо 10, WER 7,2 % вместо 16,2; far-pt — 6 вместо 13
 *  (results/2026-10-03-noise-detect.md). Стоит он около процента ядра: два БПФ на 512 на каждые 16 мс,
 *  против 21–22 % у GTCRN. В шуме слабее GTCRN — там работает GTCRN (DenoiseGate), а при музыке детектор
 *  слышит исходный звук.
 *
 *  Кадр 512, шаг 256, окно sqrt-Hann, сложение с перекрытием (задержка 256 отсчётов). Шум по каждой
 *  полосе — непрерывное слежение за минимумом сглаженного спектра: вниз быстро, вверх медленно, с
 *  поправкой BIAS (минимум ниже среднего шума). Усиление — Винер с априорным SNR «по решению»
 *  (Ephraim–Malah, α 0,98), не ниже GMIN (−20 дБ).
 *
 *  Близнец — класс Wiener в tools/vad_denoise_eval.py; сверка — bench/apk/test/WienerTest.java. */
public final class Wiener {
  static final int N = 512, H = 256, K = N / 2 + 1;
  static final double ALPHA = 0.98, GMIN = 0.1, DOWN = 0.3, UP = 0.002, SMOOTH = 0.3, BIAS = 2.0;
  static final double[] W = new double[N];
  static { for (int i = 0; i < N; i++) W[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N)); }   // sqrt(hanning(N+1)[:-1])

  final double[] in = new double[N], out = new double[N], re = new double[N], im = new double[N];
  final double[] noise = new double[K], sm = new double[K], prev = new double[K];
  boolean started;

  /** Очередные отсчёты (кратно 256) — столько же очищенных, с задержкой на 256. */
  public float[] run(float[] x) {
    float[] y = new float[x.length];
    for (int j = 0; j + H <= x.length; j += H) {
      System.arraycopy(in, H, in, 0, N - H);
      for (int k = 0; k < H; k++) in[N - H + k] = x[j + k];
      frame();
      System.arraycopy(out, H, out, 0, N - H);
      for (int k = N - H; k < N; k++) out[k] = 0;
      for (int k = 0; k < N; k++) out[k] += re[k];
      for (int k = 0; k < H; k++) y[j + k] = (float) out[k];
    }
    return y;
  }

  /** С чистого листа: новая подача записи, новое слушание. */
  public void reset() {
    java.util.Arrays.fill(in, 0); java.util.Arrays.fill(out, 0); started = false;
  }

  /** Окно in → очищенный кадр во временной области (с окном синтеза) в re. */
  void frame() {
    for (int i = 0; i < N; i++) { re[i] = in[i] * W[i]; im[i] = 0; }
    DenoiseGate.fft(re, im);
    for (int k = 0; k < K; k++) {
      double p = re[k] * re[k] + im[k] * im[k];
      if (!started) { noise[k] = p; sm[k] = p; prev[k] = 1; }
      sm[k] += SMOOTH * (p - sm[k]);
      noise[k] += (sm[k] < noise[k] ? DOWN : UP) * (sm[k] - noise[k]);
      double post = p / (BIAS * noise[k] + 1e-12);
      double xi = ALPHA * prev[k] + (1 - ALPHA) * Math.max(post - 1, 0);
      double g = Math.max(xi / (1 + xi), GMIN);
      prev[k] = g * g * post;
      re[k] *= g; im[k] *= g;
    }
    started = true;
    // Обратное БПФ вещественного сигнала: верхняя половина — сопряжённая нижней, затем conj(FFT(conj(Y)))/N.
    for (int k = K; k < N; k++) { re[k] = re[N - k]; im[k] = -im[N - k]; }
    im[0] = 0; im[N / 2] = 0;
    for (int k = 0; k < N; k++) im[k] = -im[k];
    DenoiseGate.fft(re, im);
    for (int i = 0; i < N; i++) re[i] = re[i] / N * W[i];
  }
}
