package dev.agenttranslator;

/** Чувствительность микрофона: ограничитель держит потолок и не ломает форму волны, прежний
 *  срез — ломает; 0 дБ не трогает звук; счётчики перегруза; речь и фон по фразе.
 *  Запуск: bash bench/apk/test.sh. */
public class GainTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }

  /** Синус f Гц, амплитуда a, n отсчётов при 16 кГц. */
  static float[] sine(double f, double a, int n) {
    float[] x = new float[n];
    for (int k = 0; k < n; k++) x[k] = (float) (a * Math.sin(2 * Math.PI * f * k / 16000));
    return x;
  }
  /** Мощность составляющей частоты f (корреляция с синусом и косинусом). */
  static double tone(float[] x, int from, double f) {
    double s = 0, c = 0;
    for (int k = from; k < x.length; k++) { double w = 2 * Math.PI * f * k / 16000; s += x[k] * Math.sin(w); c += x[k] * Math.cos(w); }
    int n = x.length - from; return (s * s + c * c) / ((double) n * n);
  }
  static float peak(float[] x, int from) { float p = 0; for (int k = from; k < x.length; k++) p = Math.max(p, Math.abs(x[k])); return p; }

  public static int run() {
    fails = 0; checks = 0;
    // G1: 0 дБ — звук не трогается, даже громкий.
    float[] a = sine(440, 0.97, 16000), a0 = a.clone();
    Gain g = new Gain(16000); Gain.Stats st = new Gain.Stats(); g.apply(a, a.length, st);
    ok(java.util.Arrays.equals(a, a0), "G1 при 0 дБ отсчёты те же, бит в бит");
    ok(st.n == 16000 && st.limited == 0 && st.cut == 0, "G1 и ни ограничителя, ни среза");

    // G2: +24 дБ на речи уровня −20 dBFS: ограничитель держит потолок.
    float[] b = sine(300, 0.1, 32000);
    g = new Gain(16000); g.db = 24; st = new Gain.Stats(); g.apply(b, b.length, st);
    ok(peak(b, 0) <= Gain.CEIL + 1e-6, "G2 пик после ограничителя не выше потолка: " + peak(b, 0));
    ok(st.limited > 0 && st.cut == 0, "G2 работал ограничитель, среза не было");

    // G3: прежний срез на тех же +24 дБ — вершины срезаны, форма волны сломана: третья гармоника
    // срезанного синуса в десятки раз сильнее, чем у ограниченного.
    float[] c = sine(300, 0.1, 32000);
    Gain cut = new Gain(16000); cut.db = 24; cut.limit = false; Gain.Stats sc = new Gain.Stats(); cut.apply(c, c.length, sc);
    ok(sc.cut > 0 && sc.limited == 0, "G3 прежний срез считает срезанные отсчёты");
    ok(Math.abs(peak(c, 0) - 0.99f) < 1e-6, "G3 срез упирается в 0,99");
    double hLim = tone(b, 8000, 900) / tone(b, 8000, 300), hCut = tone(c, 8000, 900) / tone(c, 8000, 300);
    ok(hCut > 0.01, "G3 у среза третья гармоника заметна: " + hCut);
    ok(hLim < hCut / 30, "G3 у ограничителя она в 30+ раз слабее: " + hLim + " против " + hCut);

    // G4: после громкого куска усиление возвращается — тихая речь после крика не остаётся приглушённой.
    Gain r = new Gain(16000); r.db = 12;
    float[] loud = sine(300, 0.5, 8000); r.apply(loud, loud.length, null);
    float after = r.red;
    float[] quiet = sine(300, 0.01, 16000); r.apply(quiet, quiet.length, null);
    ok(after < 0.5f, "G4 на громком ограничитель убавлял: множитель " + after);
    ok(r.red > 0.99f, "G4 через секунду тихого — снова почти полное усиление: " + r.red);

    // G5: перегруз входа — отсчёты у края шкалы до усиления.
    float[] o = sine(200, 1.0, 16000); Gain.Stats so = new Gain.Stats(); new Gain(16000).apply(o, o.length, so);
    ok(so.over > 0 && so.overPct() > 1, "G5 синус во всю шкалу — перегруз входа: " + so.overPct() + " %");
    float[] n2 = sine(200, 0.5, 16000); Gain.Stats sn = new Gain.Stats(); new Gain(16000).apply(n2, n2.length, sn);
    ok(sn.over == 0, "G5 половина шкалы — не перегруз");

    // G6: речь и фон по фразе: секунда шума −50 dBFS, секунда тона −20 dBFS (RMS).
    java.util.Random rnd = new java.util.Random(1);
    float[] ph = new float[32000];
    double nz = Math.pow(10, -50 / 20.0), tn = Math.pow(10, -20 / 20.0) * Math.sqrt(2);
    for (int k = 0; k < 16000; k++) ph[k] = (float) (nz * rnd.nextGaussian());
    for (int k = 16000; k < 32000; k++) ph[k] = (float) (tn * Math.sin(2 * Math.PI * 300 * k / 16000) + nz * rnd.nextGaussian());
    double[] sf = Gain.speechFloor(ph);
    ok(Math.abs(sf[1] + 50) < 1.5, "G6 фон около −50 dBFS: " + sf[1]);
    ok(Math.abs(sf[0] + 20) < 0.5, "G6 речь около −20 dBFS: " + sf[0]);
    float[] sil = new float[16000]; for (int k = 0; k < sil.length; k++) sil[k] = (float) (nz * rnd.nextGaussian());
    ok(Double.isNaN(Gain.speechFloor(sil)[0]), "G6 одна тишина — речи нет (NaN)");
    ok(Double.isNaN(Gain.speechFloor(new float[100])[0]), "G6 короче кадра — NaN, без падения");

    // G7: счётчики складываются по кускам так же, как по целому.
    Gain.Stats s1 = new Gain.Stats(), s2 = new Gain.Stats(), all = new Gain.Stats();
    float[] p1 = sine(200, 1.0, 5000), p2 = sine(200, 0.3, 5000);
    new Gain(16000).apply(p1.clone(), p1.length, s1); new Gain(16000).apply(p2.clone(), p2.length, s2);
    s1.add(s2); float[] both = new float[10000]; System.arraycopy(p1, 0, both, 0, 5000); System.arraycopy(p2, 0, both, 5000, 5000);
    new Gain(16000).apply(both, both.length, all);
    ok(s1.n == all.n && s1.over == all.over && s1.peak == all.peak, "G7 сумма по кускам равна счёту по целому");

    System.out.println(fails == 0 ? "Gain: " + checks + " проверок, все прошли" : "Gain: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
