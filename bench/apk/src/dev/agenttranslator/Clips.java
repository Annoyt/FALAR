package dev.agenttranslator;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Живой звук реплик: имя файла, формат, громкость и частота при проигрывании. Чтение и запись —
 *  ClipCodec; здесь то, что проверяется на столе без Android (bench/apk/test/ClipsTest.java).
 *
 *  Что пишется и зачем — Chats (поле `audio`) и PLAN §8: португальская речь собеседника, пока жив
 *  разговор, только если человек включил «Хранить звук собеседников». */
final class Clips {
  /** Пишется несжатый WAV. Замер на Redmi 03.10 (results/2026-10-03-clip-codec.md): сжатие системным
   *  кодеком стоило 55 (AAC) и 180 (Opus) мс процессора на секунду звука, а AAC ещё и удлинял кусочек на
   *  3,7 %; запись WAV — 0,4 мс. Сжатие, если будет, — отдельным шагом и только в затишье. */
  static final String EXT = "wav";
  /** Под какими расширениями может лежать звук реплики: реплика хранит имя, а файл находится по нему без
   *  расширения — сжатие заменяет файл, не трогая разговоры. */
  static final String[] EXTS = {"wav", "ogg", "m4a"};
  /** WAV живёт только до затишья: тогда он сжимается в Opus и удаляется (владелец 03.10: «вав файлы
   *  сильно большие, их нужно пережимать перед хранением»). Скорость и сложность — по второму замеру
   *  (results/2026-10-03-clip-codec.md). */
  static final String ZIP_EXT = "ogg";
  static final int ZIP_BITRATE = 24000, ZIP_COMPLEXITY = 10;
  /** Кодер Opus и контейнер Ogg есть с Android 10 (API 29); на Android 9 звук остаётся WAV. */
  static boolean canZip(int sdk) { return sdk >= 29; }
  /** Целевая громкость речи при проигрывании (−20 дБ полной шкалы) и потолок усиления (+18 дБ). */
  static final double TARGET = 0.1, MAX_GAIN = 8;

  private Clips() {}

  /** Имя без «.part» и без расширения звука: «17_1000.wav.part» → «17_1000». */
  static String base(String file) {
    String n = file.endsWith(".part") ? file.substring(0, file.length() - 5) : file;
    for (String e : EXTS) if (n.endsWith("." + e)) return n.substring(0, n.length() - e.length() - 1);
    return n;
  }

  /** Файл звука по имени из реплики — в каком бы из форматов он ни лежал; null — нет ни одного. */
  static File find(File dir, String name) {
    if (name == null || name.isEmpty() || name.contains("/")) return null;
    File f = new File(dir, name);
    if (f.isFile()) return f;
    String b = base(name);
    for (String e : EXTS) { File g = new File(dir, b + "." + e); if (g.isFile()) return g; }
    return null;
  }

  /** Имя файла: разговор и метка реплики. Совпадение возможно у кусков одного сегмента двоих,
   *  распознанных в одну миллисекунду, — тогда с номером; занятым считается имя в любом формате и
   *  недописанное. */
  static String name(long chat, long at, File dir) {
    String base = chat + "_" + at, b = base;
    for (int k = 2; dir != null && taken(dir, b); k++) b = base + "_" + k;
    return b + "." + EXT;
  }
  static boolean taken(File dir, String b) {
    for (String e : EXTS) if (new File(dir, b + "." + e).exists() || new File(dir, b + "." + e + ".part").exists()) return true;
    return false;
  }

  /** Усиление при проигрывании. Дальний голос записан тихо (на стенде −30 дБ против −18 у ближнего),
   *  а слушают его рядом с озвучкой нормальной громкости. Громкость речи — 90-й процентиль кадров по
   *  20 мс (паузы и тихие хвосты её не занижают), ведётся к −20 дБ; не больше +18 дБ и без перегруза
   *  пиков. Тише не делаем никогда: громкий голос остаётся как есть. */
  static float gain(float[] x, int rate) {
    int fr = Math.max(1, rate / 50), n = x.length / fr;
    if (n == 0) return 1f;
    double[] rms = new double[n]; double peak = 0;
    for (int k = 0; k < n; k++) {
      double s = 0;
      for (int j = k * fr; j < (k + 1) * fr; j++) { s += x[j] * x[j]; peak = Math.max(peak, Math.abs(x[j])); }
      rms[k] = Math.sqrt(s / fr);
    }
    Arrays.sort(rms);
    double lvl = rms[Math.min(n - 1, (int) (n * 0.9))];
    if (lvl <= 1e-6) return 1f;
    double g = Math.min(Math.min(MAX_GAIN, TARGET / lvl), 0.95 / peak);   // уровень не ноль — значит, и пик не ноль
    return (float) Math.max(1, g);
  }

  /** Конец предложения в токене распознавателя. */
  static final String ENDS = ".?!…";
  /** Где резать реплику на предложения, мс от начала звука реплики. Граница — токен, кончающийся знаком конца
   *  предложения, за которым идёт слово; разрез — середина между концом этого знака (начало + длительность) и
   *  началом слова, затем — к самому тихому окну 20 мс в пределах ±0,4 с (Voices.snap). Замер на компьютере
   *  (results/2026-10-03-asr-timestamps.md, tools/asr_timestamps.py — тот же расчёт): там, где текст поделён,
   *  разрез внутри настоящей паузы в 100 % (107 из 107, комната near-pt — 40 из 40). padSec — тишина, подставленная
   *  перед распознаванием (TranslatorService.padMs): метки — от начала подставленного. null — меток нет. */
  static int[] cuts(String[] tok, float[] ts, float[] du, float[] x, int sr, double padSec) {
    if (tok == null || ts == null || ts.length < tok.length || x == null) return null;
    List<Integer> out = new ArrayList<>(); int end = -1;
    for (int k = 0; k < tok.length; k++) {
      String t = tok[k];
      boolean wordy = false; for (int c = 0; c < t.length() && !wordy; c++) wordy = Character.isLetterOrDigit(t.charAt(c));
      if (wordy && end >= 0) {
        double e1 = ts[end] + (du != null && du.length > end ? du[end] : (end + 1 < ts.length ? ts[end + 1] - ts[end] : 0));
        double raw = (e1 + ts[k]) / 2 - padSec;
        double cut = Voices.snap(x, sr, raw, 0.4);
        out.add((int) Math.round(cut * 1000));
        end = -1;
      }
      String s = t.trim();
      if (!s.isEmpty() && ENDS.indexOf(s.charAt(s.length() - 1)) >= 0) end = k;
    }
    int[] a = new int[out.size()]; for (int k = 0; k < a.length; k++) a[k] = out.get(k);
    return a;
  }

  /** Отрезок предложения sent из sents по точкам разреза реплики, в отсчётах rate; null — разреза нет (точек не
   *  столько, сколько стыков предложений) — тогда играет реплика целиком. */
  static int[] range(int[] cutsMs, int sent, int sents, int rate, int len) {
    if (cutsMs == null || sents < 2 || cutsMs.length != sents - 1 || sent < 0 || sent >= sents) return null;
    int a = sent == 0 ? 0 : (int) ((long) cutsMs[sent - 1] * rate / 1000), b = sent == sents - 1 ? len : (int) ((long) cutsMs[sent] * rate / 1000);
    a = Math.max(0, Math.min(len, a)); b = Math.max(a, Math.min(len, b));
    return b - a < rate / 5 ? null : new int[]{a, b};       // короче 0,2 с — что-то не так, играем целиком
  }

  /** Частота кусочка — под дорожку озвучки. Дорожка другой частоты пересоздаётся, и недоигранный хвост
   *  прежнего звука (до 3 с) пропадал: конец перевода или конец кусочка (разбор кода 03.10). Линейная
   *  интерполяция: речь кусочка — до 8 кГц (записан на 16 кГц), а дорожка — от 16 кГц, так что сверху
   *  ничего не заворачивается. */
  static float[] resample(float[] x, int from, int to) {
    if (from == to || x.length == 0 || from <= 0 || to <= 0) return x;
    int n = (int) ((long) x.length * to / from);
    float[] y = new float[n];
    double step = from / (double) to;
    for (int i = 0; i < n; i++) {
      double p = i * step; int a = (int) p; double f = p - a;
      float u = x[Math.min(a, x.length - 1)], v = x[Math.min(a + 1, x.length - 1)];
      y[i] = (float) (u + (v - u) * f);
    }
    return y;
  }
}
