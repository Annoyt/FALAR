package dev.agenttranslator;

/** Как слышно фразу: порядок причин (перегруз главнее тишины и шума), пороги, цвет и цифра в
 *  строке. Запуск: bash bench/apk/test.sh. */
public class HearingTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static Hearing h(double speech, double floor, double over) { return Hearing.of(speech, floor, over, 0, true); }

  public static int run() {
    fails = 0; checks = 0;
    final double q = Hearing.QUIET_DB, n = Hearing.NOISY_SNR, o = Hearing.OVER_PCT;
    ok(h(Double.NaN, -50, 0).kind == Hearing.NONE, "H1 речи над фоном нет — «не слышно»");
    ok(h(Double.NaN, -50, 5).kind == Hearing.NONE, "H1 и при перегрузе без речи — тоже «не слышно», не «перегруз»");
    ok(h(-22, -50, o * 2).kind == Hearing.OVER, "H2 вход у края шкалы — перегруз");
    ok(h(q - 5, q - 5 - n - 10, o * 2).kind == Hearing.OVER, "H2 перегруз главнее «тихо»");
    ok(h(-22, -22 + n - 5, o * 2).kind == Hearing.OVER, "H2 перегруз главнее «шумно»");
    ok(h(-22, -50, o / 2).kind == Hearing.GOOD, "H2 редкие отсчёты у края — ещё не перегруз");
    ok(h(q - 1, q - 1 - n - 10, 0).kind == Hearing.QUIET, "H3 речь тише порога — «тихо»");
    ok(h(q + 1, q + 1 - n - 10, 0).kind == Hearing.GOOD, "H3 чуть громче порога при хорошем запасе — «хорошо»");
    ok(h(q - 1, q - 1 - n + 5, 0).kind == Hearing.QUIET, "H3 тихо и шумно разом — сначала «тихо»: это чинится чувствительностью или расстоянием");
    ok(h(-22, -22 - n + 1, 0).kind == Hearing.NOISY, "H4 запас меньше порога — «шумно»");
    ok(h(-22, -22 - n - 1, 0).kind == Hearing.GOOD, "H4 запас больше порога — «хорошо»");
    final double l = Hearing.LOUD_DB;
    ok(h(l + 1, l + 1 - n - 10, 0).kind == Hearing.LOUD, "H7 речь громче порога — «громко»");
    ok(h(l - 1, l - 1 - n - 10, 0).kind == Hearing.GOOD, "H7 чуть тише порога — «хорошо»");
    ok(h(l + 1, l + 1 - n - 10, o * 2).kind == Hearing.OVER, "H7 перегруз главнее «громко»");
    ok(Hearing.of(l + 3, -60, 0, 12, false).text.contains("убавьте"), "H8 вручную и с прибавкой — совет убавить чувствительность");
    ok(Hearing.of(l + 3, -60, 0, 0, false).text.contains("дальше"), "H8 вручную без прибавки — дальше от микрофона");
    ok(Hearing.of(l + 3, -60, 0, -12, true).text.contains("дальше"), "H8 авто — ручку не советуем, только расстояние");
    ok(Hearing.of(q - 3, -80, 0, 6, false).text.contains("прибавьте"), "H8 тихо вручную — прибавить чувствительность");
    ok(Hearing.of(q - 3, -80, 0, 24, false).text.contains("ближе к микрофону"), "H8 тихо на +24 — прибавлять уже некуда");
    ok(Hearing.of(q - 3, -80, 0, 24, true).text.contains("ближе к микрофону"), "H8 тихо в авто — ближе");
    // H9: как слышно сейчас, 0…1 — цвет кнопки и полоски.
    ok(Hearing.quality(-24, 26) == 1, "H9 речь −24 dBFS на 26 дБ над фоном — зелёный (1)");
    ok(Hearing.quality(-48, 30) == 0, "H9 речь −48 dBFS — красный (0): тут WER был 56 %");
    ok(Hearing.quality(-24, 10) == 0, "H9 запас 10 дБ — красный (0)");
    ok(Math.abs(Hearing.quality(-37, 30) - 0.5) < 1e-9, "H9 −37 dBFS — середина шкалы");
    ok(Math.abs(Hearing.quality(-24, 17.5) - 0.5) < 1e-9, "H9 запас 17,5 дБ (медиана шумной записи) — середина шкалы");
    ok(Hearing.quality(-30, 12) == Math.min((-30 + 48) / 22.0, (12 - 10) / 15.0), "H9 берётся худшее из двух");
    ok(Hearing.quality(Double.NaN, 20) == 0 && Hearing.quality(-24, Double.NaN) == 0, "H9 нет речи — красный, без исключения");
    boolean mono = true;
    for (double lv = -60; lv <= 0; lv += 1) for (double sn = 0; sn <= 40; sn += 1)
      if (Hearing.quality(lv + 1, sn) < Hearing.quality(lv, sn) || Hearing.quality(lv, sn + 1) < Hearing.quality(lv, sn)) mono = false;
    ok(mono, "H9 громче и чище — никогда не краснее");
    Hearing g = h(-22, -48, 0), s = h(-22, -30, 0);
    ok(g.text.contains("26"), "H5 в строке — запас в децибелах: " + g.text);
    ok(s.text.contains("8"), "H5 и у «шумно» тоже: " + s.text);
    ok(h(-50, -80, 0).text.contains("-50"), "H5 у «тихо» — уровень речи: " + h(-50, -80, 0).text);
    ok(g.color() != s.color() && s.color() != h(-22, -50, 1).color(), "H6 хорошо, шумно и перегруз — разными цветами");
    ok(h(Double.NaN, -50, 0).color() == 0xFF808080, "H6 «не слышно» — серым");
    live();
    System.out.println(fails == 0 ? "Hearing: " + checks + " проверок, все прошли" : "Hearing: провалов " + fails + " из " + checks);
    return fails;
  }
  /** Речь «слогами»: громче и тише на 3 дБ вокруг db — так кадры речи и идут. */
  static double syl(int k, double db) { return db + (k % 4 < 2 ? 3 : -3); }
  static double q(Hearing.Live l) { double s = l.speechDb(); return l.q(Double.isNaN(s) ? s : s + Math.max(-12, Math.min(24, -24 - s))); }
  static Hearing.Live feed(Hearing.Live l, int n, double db) { for (int k = 0; k < n; k++) l.frame(db); return l; }
  static Hearing.Live talk(Hearing.Live l, int n, double db) { for (int k = 0; k < n; k++) l.frame(syl(k, db)); return l; }

  /** L: как слышно по ходу записи — цвет держится в паузах, пока речи не было — цвета нет. */
  static void live() {
    final int first = Hearing.Live.FIRST, none = Hearing.Live.NO_VOICE;
    Hearing.Live l = feed(new Hearing.Live(), none, -52);
    ok(q(l) == -1, "L1 молчат меньше 1,5 с — цвета ещё нет (−1), а не красный");
    feed(l, 1, -52);
    ok(q(l) == 0, "L1 молчат дольше 1,5 с — красный (0): «не слышу»");
    l = feed(new Hearing.Live(), 20, -52); talk(l, first - 1, -22);
    ok(q(l) == -1 && !l.voiced(), "L2 речи меньше " + first + " кадров — оценки ещё нет");
    talk(l, 1, -22);
    ok(q(l) == 1 && l.voiced(), "L2 речь −22 dBFS на 30 дБ над фоном — сразу зелёный, без разгона от красного");
    talk(l, 60, -22); double before = q(l), snr = l.snrDb();
    feed(l, 62, -52);
    ok(q(l) == before && l.snrDb() == snr, "L3 пауза 2 с — цвет тот же, что до неё");
    ok(!l.voiced(), "L3 в паузе «речи сейчас» нет — полоске слушания серый");
    Hearing.Live n = talk(feed(new Hearing.Live(), 20, -40), 90, -24); double qn = q(n);
    ok(qn > 0.1 && qn < 0.75, "L4 речь на 16 дБ над фоном — не зелёный: " + qn);
    feed(n, 40, -40); talk(n, 3, -24);
    ok(Math.abs(q(n) - qn) < 0.05, "L4 и после паузы — тот же, а не краснее: " + q(n) + " против " + qn);
    Hearing.Live c = talk(feed(new Hearing.Live(), 20, -52), 90, -22); double good = q(c);
    talk(c, 1, -40);
    ok(good - q(c) < 0.1, "L5 один тихий слог погоду не делает: " + good + " → " + q(c));
    talk(c, 250, -38);
    ok(q(c) < 0.6, "L5 заговорили тише (запас 14 дБ) — за 8 с речи цвет догоняет: " + q(c));
    Hearing.Live u = talk(feed(new Hearing.Live(), 20, -52), 90, -38); double low = q(u);
    talk(u, Hearing.Live.MEMORY, -22);
    ok(low < 0.6 && q(u) > 0.9, "L5 заговорили громче — за 2 с речи зелёный: " + low + " → " + q(u));
    Hearing.Live z = feed(new Hearing.Live(), 5, -180); feed(z, 15, -52); talk(z, 20, -22);
    ok(Math.abs(z.snrDb() - 30) < 3, "L6 цифровая тишина на старте микрофона фон не занижает: запас " + z.snrDb());
    Hearing.Live x = new Hearing.Live(); for (int k = 0; k < 30; k++) x.frame(syl(k, -22), Double.NaN);
    ok(q(x) == -1 || q(x) == 0, "L7 внешнего фона ещё нет — речь не считается");
    for (int k = 0; k < 30; k++) x.frame(syl(k, -22), -52);
    ok(Math.abs(x.snrDb() - 30) < 3, "L7 фон появился — считается от него: " + x.snrDb());
    double sp = x.speechDb(), sn = x.snrDb(); x.shift(10);
    ok(Math.abs(x.speechDb() - sp - 10) < 1e-9 && Math.abs(x.snrDb() - sn) < 1e-9, "L8 прибавка усиления сдвигает речь и фон вместе, запас прежний");
    Hearing.Live r = talk(feed(new Hearing.Live(), 20, -52), 30, -22); feed(r, Hearing.Live.RECENT - 1, -52);
    ok(r.voiced(), "L9 «речь сейчас» держится секунду");
    feed(r, 1, -52);
    ok(!r.voiced(), "L9 и гаснет после секунды тишины");
    r.reset();
    ok(q(r) == -1 && !r.voiced() && Double.isNaN(r.floorDb), "L10 сброс — как новое удержание");
    // L11: заговорили с первого кадра. Свой фон тогда начинается с речи, и видна она только после
    // первого провала; с фоном комнаты от слушания — сразу, с верным запасом.
    Hearing.Live cold = talk(new Hearing.Live(), 40, -22), warm = new Hearing.Live(); warm.seed(-52); talk(warm, 40, -22);
    ok(q(warm) == 1 && Math.abs(warm.snrDb() - 31) < 1.5, "L11 с фоном слушания — зелёный сразу, запас " + warm.snrDb());
    ok(q(cold) < q(warm), "L11 без него — не лучше: " + q(cold));
    Hearing.Live z2 = new Hearing.Live(); z2.seed(Double.NaN); z2.seed(-180);
    ok(Double.isNaN(z2.floorDb), "L11 фона нет или он цифровая тишина — не трогаем");
  }

  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
