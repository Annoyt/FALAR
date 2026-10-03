package dev.agenttranslator;

/** Признаки для отпечатка голоса: Kaldi fbank по рецепту 3D-Speaker — ровно как tools/voiceprint_ref.py.
 *
 *  Зачем своё: через sherpa-onnx (SpeakerEmbeddingExtractor) та же модель CAM++ людей почти не
 *  различала — AUC 0,56 на фразах около 2 с, отпечаток одной записи отличался от эталонного
 *  (косинус 0,01). С этими признаками и ONNX Runtime — AUC 0,999 (замер 01.10, results/2026-10-01-voices.md).
 *
 *  Кадр 25 мс (400 отсчётов 16 кГц), шаг 10 мс, только целые кадры; из кадра вычитается среднее;
 *  предыскажение 0,97; окно Пови; БПФ 512, мощность; 80 треугольных мел-полос 20 Гц – 8 кГц
 *  (мел = 1127·ln(1 + f/700), бин Найквиста не участвует); натуральный логарифм с полом float eps;
 *  из каждой полосы вычитается её среднее по кадрам. Вход — float в [-1, 1].
 *
 *  Без Android, проверяется на столе против эталона (bench/apk/test/FbankTest.java). */
public final class Fbank {
  public static final int SR = 16000, FL = 400, FS = 160, NFFT = 512, NMEL = 80;
  static final double LOW = 20, PRE = 0.97, EPS = Math.ulp(1.0f);
  static final double[] WIN = new double[FL];
  /** Полоса m: первый бин и веса подряд. */
  static final int[] BIN0 = new int[NMEL];
  static final double[][] WT = new double[NMEL][];
  static final double[] COS = new double[NFFT / 2], SIN = new double[NFFT / 2];
  static final int[] REV = new int[NFFT];
  static {
    for (int i = 0; i < FL; i++) WIN[i] = Math.pow(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FL - 1)), 0.85);
    double lo = mel(LOW), hi = mel(SR / 2.0);
    for (int m = 0; m < NMEL; m++) {
      double l = lo + (hi - lo) * m / (NMEL + 1), c = lo + (hi - lo) * (m + 1) / (NMEL + 1), r = lo + (hi - lo) * (m + 2) / (NMEL + 1);
      int first = -1, last = -1; double[] w = new double[NFFT / 2];
      for (int k = 0; k < NFFT / 2; k++) {                     // бин Найквиста (NFFT/2) не участвует
        double fm = mel((double) k * SR / NFFT);
        if (fm > l && fm < r) { w[k] = Math.min((fm - l) / (c - l), (r - fm) / (r - c)); if (first < 0) first = k; last = k; }
      }
      BIN0[m] = Math.max(first, 0);
      WT[m] = first < 0 ? new double[0] : java.util.Arrays.copyOfRange(w, first, last + 1);
    }
    for (int i = 0; i < NFFT / 2; i++) { COS[i] = Math.cos(-2 * Math.PI * i / NFFT); SIN[i] = Math.sin(-2 * Math.PI * i / NFFT); }
    int bits = Integer.numberOfTrailingZeros(NFFT);
    for (int i = 0; i < NFFT; i++) REV[i] = Integer.reverse(i) >>> (32 - bits);
  }
  static double mel(double f) { return 1127.0 * Math.log(1.0 + f / 700.0); }

  /** Сколько целых кадров в n отсчётах. */
  public static int frames(int n) { return n < FL ? 0 : 1 + (n - FL) / FS; }

  /** Признаки [кадр][полоса] с вычтенным средним полос; меньше кадра — пустой массив. */
  public static float[][] compute(float[] x) {
    int n = frames(x.length);
    float[][] out = new float[n][NMEL];
    double[] re = new double[NFFT], im = new double[NFFT], fr = new double[FL], mean = new double[NMEL];
    for (int t = 0; t < n; t++) {
      int o = t * FS; double dc = 0;
      for (int i = 0; i < FL; i++) { fr[i] = x[o + i]; dc += fr[i]; }
      dc /= FL;
      for (int i = 0; i < FL; i++) fr[i] -= dc;
      for (int i = FL - 1; i > 0; i--) fr[i] -= PRE * fr[i - 1];
      fr[0] -= PRE * fr[0];
      java.util.Arrays.fill(re, 0); java.util.Arrays.fill(im, 0);
      for (int i = 0; i < FL; i++) re[REV[i]] = fr[i] * WIN[i];   // сразу в бит-реверсном порядке
      fft(re, im);
      for (int m = 0; m < NMEL; m++) {
        double s = 0; double[] w = WT[m]; int b = BIN0[m];
        for (int k = 0; k < w.length; k++) { int j = b + k; s += w[k] * (re[j] * re[j] + im[j] * im[j]); }
        double v = Math.log(Math.max(s, EPS));
        out[t][m] = (float) v; mean[m] += v;
      }
    }
    if (n > 0) for (int m = 0; m < NMEL; m++) { float mu = (float) (mean[m] / n); for (int t = 0; t < n; t++) out[t][m] -= mu; }
    return out;
  }

  /** БПФ на месте; вход уже переставлен в бит-реверсном порядке. */
  static void fft(double[] re, double[] im) {
    for (int len = 2; len <= NFFT; len <<= 1) {
      int half = len >> 1, step = NFFT / len;
      for (int i = 0; i < NFFT; i += len)
        for (int k = 0; k < half; k++) {
          double c = COS[k * step], s = SIN[k * step];
          int a = i + k, b = a + half;
          double tr = re[b] * c - im[b] * s, ti = re[b] * s + im[b] * c;
          re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
        }
    }
  }
}
