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
    System.out.println(fails == 0 ? "Hearing: " + checks + " проверок, все прошли" : "Hearing: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
