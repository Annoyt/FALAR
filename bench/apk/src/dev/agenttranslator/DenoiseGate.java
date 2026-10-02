package dev.agenttranslator;

/** «Только при шуме»: включать ли шумодав нарезки.
 *
 *  Слушание режет поток на фразы детектором речи silero и порогом по энергии. В шумной комнате оно
 *  теряет фразы: фон глушит детектор. Шумодав GTCRN перед детектором возвращает их — распознаватель
 *  при этом слышит исходный кусок, очистка перед распознаванием вредит (results/2026-10-02-vad-denoise.md).
 *  Но шумодав стоит около 21 % ядра, пока работает, а в тишине не даёт ничего. Владелец 02.10: сначала
 *  понять, что есть фон, который можно убрать, и только тогда убирать.
 *
 *  Решает фон комнаты — та же оценка, что у «как слышно» (roomDb: по исходному звуку, в паузах, без
 *  усиления). Не ниже порога ON_FRAMES кадров подряд (2 с) — включить; ниже порога на HYST дБ
 *  OFF_FRAMES кадров подряд (10 с) — выключить: на границе шумодав не мигает, а короткая тишина в
 *  шумном месте его не гасит.
 *
 *  Вторая версия (03.10, results/2026-10-03-noise-detect.md; владелец 02.10: «ловить умеренный шум без
 *  лишних включений»):
 *  - порог −48 вместо −44: умеренный шум (фон −49…−46) шумодав возвращает так же, как громкий;
 *  - музыка не шум. «Гуляющий фон» дальней записи, на котором порог −47 включал шумодав, оказался мелодией
 *    на уровне речи; шумодав там не помогает и рождает куски из мелодии. Музыку выдаёт спектр: у шума и
 *    тишины он ровный (плоскостность в полосе речи 0,1–0,4), у мелодии — отдельные тоны (0,01–0,05).
 *    Средняя логарифма плоскостности кадров без речи ниже ln 0,08 — шумодав не включать, а включённый
 *    выключить через 3 с;
 *  - быстрое включение: фон не ниже порога + 3 дБ полсекунды — включить сразу, иначе первая фраза после
 *    начала слушания в шуме проходит без шумодава.
 *  Первую версию (только порог) оставляет стенд: --es vaddenoise auto1[:T].
 *
 *  Без Android, проверяется на столе (bench/apk/test/DenoiseGateTest.java); та же логика — класс Gate в
 *  tools/vad_denoise_eval.py, которым пороги и выбраны. */
public final class DenoiseGate {
  /** Порог фона по умолчанию, dBFS. */
  public static final double DEFAULT_T = -48;
  /** 2 с и 10 с кадрами нарезки по 32 мс; гистерезис, дБ. */
  public static final int ON_FRAMES = 2000 / 32, OFF_FRAMES = 10000 / 32;
  public static final double HYST = 3;
  /** Быстрое включение: на FAST_DB громче порога FAST_FRAMES кадров (0,5 с). */
  public static final double FAST_DB = 3;
  public static final int FAST_FRAMES = 500 / 32;
  /** Музыка: средняя логарифма плоскостности ниже LOG_MUSIC; выключить через MUSIC_OFF_FRAMES (3 с);
   *  судить не раньше MIN_OBS кадров без речи; ALPHA — шаг скользящей средней (первые 1/ALPHA кадров —
   *  простая средняя, чтобы первый кадр не решал за сотню следующих). Быстрое включение тоже ждёт MIN_OBS
   *  кадров: иначе в музыке с начала слушания шумодав успевал бы включиться до того, как музыка узнана. */
  public static final double LOG_MUSIC = Math.log(0.08), ALPHA = 0.01;
  public static final int MUSIC_OFF_FRAMES = 3000 / 32, MIN_OBS = 8;
  /** Оценки фона ещё нет. */
  public static final double NONE = -999;

  public double t = DEFAULT_T;
  /** false — первая версия: только порог, без музыки и быстрого включения. */
  public boolean v2 = true;
  boolean on;
  int onCnt, offCnt, fastCnt, musicCnt, obs;
  double flat;
  /** Почему выключился в последний раз: true — музыка, false — стало тихо. */
  boolean offByMusic;

  public boolean on() { return on; }

  final double[] re = new double[N], im = new double[N];
  /** Кадр, который детектор речи речью не признал: посчитать его плоскостность и учесть. */
  public void observeFrame(float[] win) { observe(logFlatness(win, re, im)); }

  /** То же по готовой плоскостности (logFlatness). */
  public void observe(double logFlat) {
    flat += Math.max(ALPHA, 1.0 / (obs + 1)) * (logFlat - flat);
    obs++;
  }

  /** Фон сейчас — музыка. */
  public boolean music() { return v2 && obs >= MIN_OBS && flat < LOG_MUSIC; }

  /** Очередной кадр; room — фон до этого кадра (NONE — оценки ещё нет). +1 — пора включить, −1 —
   *  выключить, 0 — без перемен. */
  public int step(double room) {
    boolean m = music();
    if (!on) {
      if (m) { onCnt = 0; fastCnt = 0; return 0; }
      onCnt = room >= t ? onCnt + 1 : 0;
      fastCnt = v2 && obs >= MIN_OBS && room >= t + FAST_DB ? fastCnt + 1 : 0;
      if (onCnt < ON_FRAMES && fastCnt < FAST_FRAMES) return 0;
      on = true; offCnt = 0; musicCnt = 0;
      return 1;
    }
    musicCnt = m ? musicCnt + 1 : 0;
    offCnt = room < t - HYST ? offCnt + 1 : 0;
    if (offCnt < OFF_FRAMES && musicCnt < MUSIC_OFF_FRAMES) return 0;
    offByMusic = musicCnt >= MUSIC_OFF_FRAMES;
    on = false; onCnt = 0; fastCnt = 0;
    return -1;
  }

  /** С чистого листа — выключен, о фоне ничего не известно: новая подача записи, смена режима. */
  public void reset() { on = false; onCnt = 0; offCnt = 0; fastCnt = 0; musicCnt = 0; obs = 0; flat = 0; offByMusic = false; }

  static final int N = 512, LO = 10, HI = 128;          // полоса 300–4000 Гц при 16 кГц
  static final double[] HANN = new double[N], COS = new double[N / 2], SIN = new double[N / 2];
  static {
    for (int i = 0; i < N; i++) HANN[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));   // как numpy.hanning
    for (int i = 0; i < N / 2; i++) { COS[i] = Math.cos(2 * Math.PI * i / N); SIN[i] = -Math.sin(2 * Math.PI * i / N); }
  }

  /** Логарифм спектральной плоскостности кадра из 512 отсчётов в полосе речи (бины 10–128): среднее
   *  логарифмов мощности минус логарифм средней. 0 — ровный спектр, сильно отрицательный — отдельные тоны. */
  public static double logFlatness(float[] win) { return logFlatness(win, new double[N], new double[N]); }

  /** Со своими рабочими массивами: поток нарезки зовёт это на каждом кадре без речи, мусора не оставляем. */
  static double logFlatness(float[] win, double[] re, double[] im) {
    for (int i = 0; i < N; i++) { re[i] = i < win.length ? win[i] * HANN[i] : 0; im[i] = 0; }
    fft(re, im);
    double sumLog = 0, sum = 0; int n = 0;
    for (int k = LO; k <= HI; k++) {
      double p = re[k] * re[k] + im[k] * im[k] + 1e-12;
      sumLog += Math.log(p); sum += p; n++;
    }
    return sumLog / n - Math.log(sum / n);
  }

  /** Быстрое преобразование Фурье на месте, N = 512 (основание 2, прореживание по времени). */
  static void fft(double[] re, double[] im) {
    for (int i = 1, j = 0; i < N; i++) {
      int bit = N >> 1;
      for (; (j & bit) != 0; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
    }
    for (int len = 2; len <= N; len <<= 1) {
      int step = N / len;
      for (int i = 0; i < N; i += len)
        for (int k = 0; k < len / 2; k++) {
          double wr = COS[k * step], wi = SIN[k * step];
          int a = i + k, b = a + len / 2;
          double xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
        }
    }
  }
}
