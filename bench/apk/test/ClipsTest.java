package dev.agenttranslator;

import java.io.File;
import java.nio.file.Files;
import java.util.Objects;

/** Живой звук реплик без Android: формат, имя без затирания соседнего и в любом формате, усиление
 *  тихой записи и частота под дорожку озвучки при проигрывании. Запуск: bash bench/apk/test.sh. */
public class ClipsTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }

  /** Речь тоном 220 Гц на уровне rms (в долях полной шкалы) — доля speech от длины, остальное тишина. */
  static float[] speech(double rms, double speech, int sec) {
    float[] x = new float[16000 * sec]; int n = (int) (x.length * speech);
    for (int k = 0; k < n; k++) x[k] = (float) (rms * Math.sqrt(2) * Math.sin(2 * Math.PI * 220 * k / 16000.0));
    return x;
  }
  static double db(double g) { return 20 * Math.log10(g); }

  public static int run() throws Exception {
    fails = 0; checks = 0;
    // K1 формат — WAV по замеру; имя без расширения и без «.part»
    eq(Clips.EXT, "wav", "K1 пишется WAV (замер 03.10: сжатие дорого)");
    eq(Clips.base("17_1000.wav"), "17_1000", "K1 base: без расширения");
    eq(Clips.base("17_1000.ogg.part"), "17_1000", "K1 base: без «.part» и расширения");
    eq(Clips.base("17_1000_2.m4a"), "17_1000_2", "K1 base: с номером");
    eq(Clips.base("readme.txt"), "readme.txt", "K1 base: чужое расширение не трогается");
    // K6 сжатие в затишье: WAV → Opus в Ogg, только с Android 10 (кодер Opus и контейнер Ogg — API 29)
    ok(!Clips.canZip(28) && Clips.canZip(29) && Clips.canZip(36), "K6 сжимается с Android 10, на 9 остаётся WAV");
    eq(Clips.ZIP_EXT, "ogg", "K6 сжатый — .ogg");
    ok(java.util.Arrays.asList(Clips.EXTS).contains(Clips.ZIP_EXT), "K6 сжатый находится по имени реплики (EXTS)");

    // K2 имя: разговор_метка; занято в любом формате и недописанным — с номером
    File d = Files.createTempDirectory("clips").toFile(); d.deleteOnExit();
    eq(Clips.name(17, 1000, d), "17_1000.wav", "K2 обычное имя");
    eq(Clips.name(17, 1000, null), "17_1000.wav", "K2 без каталога — без проверки");
    Files.write(new File(d, "17_1000.ogg").toPath(), new byte[1]);
    eq(Clips.name(17, 1000, d), "17_1000_2.wav", "K2 занято сжатым — второй");
    Files.write(new File(d, "17_1000_2.wav.part").toPath(), new byte[1]);
    eq(Clips.name(17, 1000, d), "17_1000_3.wav", "K2 второй ещё пишется — третий");
    eq(Clips.name(17, 1001, d), "17_1001.wav", "K2 другая метка — своё имя");

    // K4 звук находится по имени из реплики в любом формате: будущее сжатие не трогает разговоры
    eq(Clips.find(d, "17_1000.wav"), new File(d, "17_1000.ogg"), "K4 в реплике .wav, на диске .ogg — найден");
    eq(Clips.find(d, "17_1000.ogg"), new File(d, "17_1000.ogg"), "K4 точное имя");
    eq(Clips.find(d, "17_1001.wav"), null, "K4 нет ни в одном формате — null");
    eq(Clips.find(d, "17_1000_2.wav"), null, "K4 недописанное — не звук");
    eq(Clips.find(d, "../x.wav"), null, "K4 путь в имени не принимается");
    eq(Clips.find(d, ""), null, "K4 пустое имя — null");

    // K3 усиление: тихая речь доводится до −20 дБ, паузы уровень не занижают, громкая не тише
    double g30 = db(Clips.gain(speech(0.0316, 1.0, 2), 16000));
    ok(Math.abs(g30 - 10) < 0.5, "K3 речь −30 дБ → +10 дБ: " + g30);
    double gPause = db(Clips.gain(speech(0.0316, 0.3, 4), 16000));
    ok(Math.abs(gPause - 10) < 0.5, "K3 70 % пауз — то же усиление: " + gPause);
    eq(Clips.gain(speech(0.3, 1.0, 1), 16000), 1f, "K3 громкая речь (−10 дБ) — без изменений, тише не делаем");
    double gMax = db(Clips.gain(speech(0.001, 1.0, 1), 16000));
    ok(Math.abs(gMax - db(Clips.MAX_GAIN)) < 0.1, "K3 совсем тихая — не больше +18 дБ: " + gMax);
    float[] spike = speech(0.0316, 1.0, 1); spike[500] = 0.5f;
    float gs = Clips.gain(spike, 16000);
    ok(gs * 0.5f <= 0.951f && gs > 1f, "K3 пик не перегружается: усиление " + gs);
    // уровень — 90-й процентиль кадров, а не самый громкий: 46 кадров по −40 дБ и 4 громких по −20 дБ
    float[] mix = speech(0.01, 1.0, 1);
    for (int k = 46 * 320; k < 50 * 320; k++) mix[k] *= 10;
    float gm = Clips.gain(mix, 16000);
    ok(Math.abs(gm - 0.95 / (0.1 * Math.sqrt(2))) < 0.05, "K3 уровень по 90-му процентилю, предел — пик громких кадров: " + gm);
    eq(Clips.gain(new float[16000], 16000), 1f, "K3 тишина — без изменений");
    eq(Clips.gain(new float[100], 16000), 1f, "K3 короче кадра — без изменений");

    // K5 частота под дорожку: длительность сохраняется, тон тот же, та же частота — тот же массив
    float[] tone = speech(0.1, 1.0, 1);
    float[] up = Clips.resample(tone, 16000, 22050);
    eq(up.length, 22050, "K5 16 → 22,05 кГц: секунда осталась секундой");
    int zc = 0; for (int k = 1; k < up.length; k++) if ((up[k - 1] < 0) != (up[k] < 0)) zc++;
    ok(Math.abs(zc - 440) <= 2, "K5 тон 220 Гц остался 220 Гц (переходов через ноль " + zc + ")");
    float[] down = Clips.resample(new float[48000], 48000, 22050);
    eq(down.length, 22050, "K5 48 → 22,05 кГц: секунда осталась секундой");
    ok(Clips.resample(tone, 16000, 16000) == tone, "K5 та же частота — без копии");
    eq(Clips.resample(new float[0], 16000, 22050).length, 0, "K5 пусто — пусто");

    System.out.println(fails == 0 ? "Clips: " + checks + " проверок, все прошли" : "Clips: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
