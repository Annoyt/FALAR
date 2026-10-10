package dev.agenttranslator;

/** Сборка куска из кадров слушания (Slicer): подпор начала, хвост, выдержка, минимум речи; тишина конца куска —
 *  подпор следующего; разрыв кадров закрывает кусок сейчас; предел длины режет по тихому кадру, остаток —
 *  с подпором. Кадр помечен своим номером в первом отсчёте — по нему видно, какие кадры попали в кусок.
 *  Запуск: bash bench/apk/test.sh. */
public class SlicerTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }

  static float[] frame(int k) { float[] f = new float[512]; f[0] = k; return f; }
  static int first(Slicer.Piece p) { return (int) p.pcm[0]; }
  static int frames(Slicer.Piece p) { return p.pcm.length / 512; }
  /** n кадров подряд: речь или тишина; возвращает первый закрытый кусок. */
  static Slicer.Piece run(Slicer s, int[] k, int n, boolean speech, double rms) {
    Slicer.Piece got = null;
    for (int i = 0; i < n; i++) { Slicer.Piece p = s.feed(frame(k[0]++), new Gain.Stats(), speech, rms); if (p != null && got == null) got = p; }
    return got;
  }

  public static int run() {
    fails = 0; checks = 0;
    // S1: 3 с тишины, 1 с речи, тишина: кусок = подпор 31 кадра (с кадром срабатывания) + речь + хвост 9 кадров.
    Slicer s = new Slicer(); int[] k = {0};
    ok(run(s, k, 94, false, 0.01) == null, "S1 тишина куска не даёт");
    ok(run(s, k, 31, true, 0.1) == null && s.inSpeech, "S1 речь открывает кусок");
    Slicer.Piece p = run(s, k, 19, false, 0.01);
    ok(p != null && frames(p) == 31 + 30 + 9 && first(p) == 94 - 30, "S1 кусок: подпор 31 + речь 30 + хвост 9, с кадра 64: " + (p == null ? "null" : frames(p) + " с " + first(p)));
    ok(p != null && p.voicedMs == 31 * 32 && !p.cut && !s.inSpeech, "S1 речи 31 кадр, не разрез, кусок закрыт");

    // S2: тишина после куска стала подпором: следующая фраза через 3 кадра начинается с кадров той тишины.
    int closeAt = k[0];
    ok(run(s, k, 3, false, 0.01) == null, "S2 три кадра тишины");
    run(s, k, 20, true, 0.1); p = run(s, k, 19, false, 0.01);
    ok(p != null && first(p) == closeAt - 19 && frames(p) == 23 + 19 + 9, "S2 подпор — 19 кадров тишины прошлого куска + 3 новых + кадр срабатывания: кусок с кадра " + (closeAt - 19) + ", " + (p == null ? "null" : first(p) + ", " + frames(p)));

    // S3: короткая речь — 5 кадров (160 мс при минимуме 250) — куска нет, но тишина после неё остаётся подпором.
    s = new Slicer(); k[0] = 0; run(s, k, 40, false, 0.01);
    ok(run(s, k, 5, true, 0.1) == null && run(s, k, 19, false, 0.01) == null && !s.inSpeech, "S3 5 кадров речи — куска нет");
    ok(s.pre.size() == 19, "S3 тишина после короткой речи — подпор: " + s.pre.size());
    s.minSpeechMs = 100;
    run(s, k, 5, true, 0.1); p = run(s, k, 19, false, 0.01);
    ok(p != null && p.voicedMs == 5 * 32, "S3 при минимуме 100 мс те же 5 кадров — кусок: " + (p == null ? "null" : p.voicedMs));

    // S4: разрыв кадров посреди речи — кусок закрывается сейчас, целиком, подпор пуст.
    s = new Slicer(); k[0] = 0; run(s, k, 40, false, 0.01); run(s, k, 12, true, 0.1);
    p = s.gap();
    ok(p != null && frames(p) == 31 + 11 && first(p) == 10 && !s.inSpeech && s.pre.isEmpty(), "S4 разрыв: кусок 31 + 11 кадров с кадра 10, подпор пуст: " + (p == null ? "null" : frames(p) + " с " + first(p)));
    ok(s.gap() == null && run(s, k, 2, false, 0.01) == null && run(s, k, 10, true, 0.1) == null, "S4 после разрыва речь идёт заново");
    p = run(s, k, 19, false, 0.01);
    ok(p != null && frames(p) == 2 + 10 + 9, "S4 следующий кусок — только с кадров после разрыва: " + (p == null ? "null" : frames(p)));
    // разрыв при короткой речи — ничего, и тоже с чистого листа
    s = new Slicer(); k[0] = 0; run(s, k, 40, false, 0.01); run(s, k, 3, true, 0.1);
    ok(s.gap() == null && s.pre.isEmpty() && !s.inSpeech, "S4 разрыв при 3 кадрах речи — куска нет, подпор пуст");

    // S5: предел длины 15 с: разрез по самому тихому кадру из последних 47, остаток — начало следующего куска.
    s = new Slicer(); k[0] = 0; run(s, k, 40, false, 0.01);
    Slicer.Piece c = null;
    for (int i = 0; i < 600 && c == null; i++) { int n = k[0]++; c = s.feed(frame(n), new Gain.Stats(), true, n == 458 ? 0.001 : 0.1); }
    ok(c != null && c.cut && first(c) == 10 && frames(c) == 458 - 10, "S5 разрез перед тихим кадром 458: " + (c == null ? "null" : first(c) + ", " + frames(c)));
    ok(s.inSpeech && s.seg.size() == 469 - (458 - 10) && (int) s.seg.get(0)[0] == 458, "S5 остаток продолжает кусок с тихого кадра: " + s.seg.size());
    p = run(s, k, 19, false, 0.01);
    ok(p != null && first(p) == 458 && !p.cut, "S5 продолжение закрывается обычно и начинается с кадра 458: " + (p == null ? "null" : first(p)));
    // без тихого кадра — разрез всё равно не раньше середины и не позже конца
    s = new Slicer(); k[0] = 0; c = null;
    for (int i = 0; i < 600 && c == null; i++) c = s.feed(frame(k[0]++), new Gain.Stats(), true, 0.1);
    ok(c != null && c.cut && frames(c) >= 469 - 47 && frames(c) < 469, "S5 ровная речь: разрез в последних 47 кадрах: " + (c == null ? "null" : frames(c)));

    // S6: подпор короче секунды в начале слушания — кусок с первого кадра.
    s = new Slicer(); k[0] = 0; run(s, k, 5, false, 0.01); run(s, k, 10, true, 0.1); p = run(s, k, 19, false, 0.01);
    ok(p != null && first(p) == 0 && frames(p) == 5 + 10 + 9, "S6 подпор — сколько есть: " + (p == null ? "null" : first(p) + ", " + frames(p)));

    System.out.println(fails == 0 ? "SlicerTest: " + checks + " проверок, все прошли" : "SlicerTest: провалов " + fails + " из " + checks);
    return fails;
  }

  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
