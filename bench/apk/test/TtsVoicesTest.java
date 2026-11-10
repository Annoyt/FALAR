package dev.agenttranslator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Голоса синтеза телефона и обработка его звука (TtsVoices) — на столе, без Android.
 *  T1 — выбор голоса: только без сети и скачанные, без псевдонимов; выбор человека, затем самый разборчивый, затем
 *       качество, задержка, имя; чужая страна (pt-PT) не годится;
 *  T2 — почему голоса нет: «не скачан» (язык движок знает) и «нет голоса»;
 *  T3 — звук движка в float: 16 бит, 8 бит, float, два канала;
 *  T4 — передискретизация кусками = целиком, длина по частотам;
 *  T5 — тихий хвост фразы срезается до keep, тишина в середине остаётся;
 *  T6 — скорость речи: 0,5–2× с шагом 0,1, позиция ползунка туда и обратно, надпись «1,0×».
 *  Запуск: bash bench/apk/test.sh. */
public class TtsVoicesTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static TtsVoices.V v(String name, String lang, String country, int q, int lat, boolean net, boolean inst, boolean alias) {
    return new TtsVoices.V(name, lang, country, q, lat, net, inst, alias);
  }

  public static void main(String[] a) {
    // Голоса Google на Redmi 10.11 (results/2026-11-10-tts-system.md) — без сети, по сети, псевдоним языка, pt-PT
    List<TtsVoices.V> g = new ArrayList<>(Arrays.asList(
        v("ru-ru-x-ruf-local", "ru", "RU", 400, 200, false, true, false),
        v("ru-ru-x-ruf-network", "ru", "RU", 400, 200, true, true, false),
        v("ru-RU-language", "ru", "RU", 400, 200, false, true, true),
        v("ru-ru-x-dfc-local", "ru", "RU", 400, 200, false, true, false),
        v("ru-ru-x-rud-local", "ru", "RU", 400, 200, false, true, false),
        v("pt-pt-x-jfb-local", "pt", "PT", 400, 200, false, true, false),
        v("pt-br-x-pte-local", "pt", "BR", 400, 200, false, true, false),
        v("pt-br-x-afs-local", "pt", "BR", 400, 200, false, true, false),
        v("pt-br-x-ptd-network", "pt", "BR", 400, 200, true, true, false)));
    List<TtsVoices.V> ru = TtsVoices.usable(g, "ru", null), pt = TtsVoices.usable(g, "pt", null);
    ok(ru.size() == 3 && ru.get(0).name.equals("ru-ru-x-dfc-local"), "T1 ru: три голоса без сети, первым — самый разборчивый dfc: " + ru);
    ok(ru.get(1).name.equals("ru-ru-x-rud-local") && ru.get(2).name.equals("ru-ru-x-ruf-local"), "T1 ru: остальные по имени: " + ru);
    ok(pt.size() == 2 && pt.get(0).name.equals("pt-br-x-afs-local"), "T1 pt: только Бразилия, первым afs: " + pt);
    ok(TtsVoices.usable(g, "ru", "ru-ru-x-ruf-local").get(0).name.equals("ru-ru-x-ruf-local"), "T1 выбор человека — первым");
    ok(TtsVoices.usable(g, "ru", "ru-ru-x-ruf-network").get(0).name.equals("ru-ru-x-dfc-local"), "T1 выбор с сетью не берётся — работаем без сети");
    ok(TtsVoices.usable(g, "ru", "нет-такого").get(0).name.equals("ru-ru-x-dfc-local"), "T1 выбранного голоса больше нет — самый разборчивый");
    List<TtsVoices.V> other = Arrays.asList(v("b", "ru", "RU", 300, 300, false, true, false), v("a", "ru", "RU", 400, 400, false, true, false),
        v("c", "ru", "RU", 400, 200, false, true, false));
    ok(TtsVoices.usable(other, "ru", null).toString().equals("[c, a, b]"), "T1 чужой движок: качество, потом задержка: " + TtsVoices.usable(other, "ru", null));
    List<TtsVoices.V> notYet = Arrays.asList(v("ru-ru-x-ruf-local", "ru", "RU", 400, 200, false, false, false));
    ok(TtsVoices.usable(notYet, "ru", null).isEmpty(), "T1 не скачанный голос не годится");

    ok(TtsVoices.missing(g, "ru") == null && TtsVoices.missing(g, "pt") == null, "T2 голоса есть — причины нет");
    ok("голос не скачан".equals(TtsVoices.missing(notYet, "ru")), "T2 голос есть, но не скачан");
    List<TtsVoices.V> netOnly = Arrays.asList(v("pt-br-x-afs-network", "pt", "BR", 400, 200, true, true, false));
    ok("голос не скачан".equals(TtsVoices.missing(netOnly, "pt")), "T2 только по сети — «не скачан» (можно поставить)");
    ok("нет голоса".equals(TtsVoices.missing(netOnly, "ru")), "T2 язык движку неизвестен — «нет голоса»");
    ok("нет голоса".equals(TtsVoices.missing(Arrays.asList(v("pt-pt-x-jfb-local", "pt", "PT", 400, 200, false, true, false)), "pt")),
        "T2 только pt-PT — для бразильского голоса нет");

    float[] p16 = TtsVoices.pcm(new byte[]{0, 0, (byte) 0xff, 0x7f, 0x00, (byte) 0x80, (byte) 0xff, (byte) 0xff}, 2, 1);
    ok(p16.length == 4 && p16[0] == 0 && Math.abs(p16[1] - 32767 / 32768f) < 1e-6 && p16[2] == -1f && Math.abs(p16[3] + 1 / 32768f) < 1e-9,
        "T3 16 бит: 0, макс, мин, −1 отсчёт: " + Arrays.toString(p16));
    float[] p8 = TtsVoices.pcm(new byte[]{(byte) 128, (byte) 255, 0}, 3, 1);
    ok(p8.length == 3 && p8[0] == 0 && Math.abs(p8[1] - 127 / 128f) < 1e-6 && p8[2] == -1f, "T3 8 бит без знака");
    int bits = Float.floatToIntBits(0.5f);
    float[] pf = TtsVoices.pcm(new byte[]{(byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)}, 4, 1);
    ok(pf.length == 1 && pf[0] == 0.5f, "T3 float");
    float[] st = TtsVoices.pcm(new byte[]{0, 0x40, 0, (byte) 0xc0, 0, 0x20, 0, 0x20}, 2, 2);
    ok(st.length == 2 && st[0] == 0 && Math.abs(st[1] - 0.25f) < 1e-6, "T3 два канала — среднее: " + Arrays.toString(st));
    ok(TtsVoices.pcm(new byte[]{1, 2, 3}, 2, 1).length == 1, "T3 нечётный хвост байтов отброшен");

    float[] sine = new float[24000];
    for (int i = 0; i < sine.length; i++) sine[i] = (float) Math.sin(2 * Math.PI * 300 * i / 24000.0);
    float[] whole = new TtsVoices.Resampler(24000, 22050).push(sine);
    TtsVoices.Resampler r = new TtsVoices.Resampler(24000, 22050); List<Float> parts = new ArrayList<>();
    for (int o = 0; o < sine.length; o += 1000) { float[] x = r.push(Arrays.copyOfRange(sine, o, Math.min(sine.length, o + 1000))); for (float f : x) parts.add(f); }
    double maxd = 0; int n = Math.min(whole.length, parts.size());
    for (int i = 0; i < n; i++) maxd = Math.max(maxd, Math.abs(whole[i] - parts.get(i)));
    ok(Math.abs(whole.length - 22050) <= 2 && Math.abs(parts.size() - whole.length) <= 1 && maxd < 1e-5,
        "T4 24 кГц → 22,05 кГц кусками = целиком: " + whole.length + " / " + parts.size() + ", расхождение " + maxd);
    float[] up = new TtsVoices.Resampler(16000, 24000).push(sine);
    ok(Math.abs(up.length - 36000) <= 2, "T4 вверх 16 → 24 кГц: длина " + up.length);
    double err = 0;
    for (int i = 0; i < whole.length; i++) err = Math.max(err, Math.abs(whole[i] - Math.sin(2 * Math.PI * 300 * i / 22050.0)));
    ok(err < 0.01, "T4 синус после передискретизации тот же (ошибка " + err + ")");

    TtsVoices.TrimTail t = new TtsVoices.TrimTail(0.003f, 3);
    float[] o1 = t.push(new float[]{0.5f, 0.2f, 0, 0, 0});
    float[] o2 = t.push(new float[]{0, 0, 0.4f, 0, 0});
    float[] o3 = t.push(new float[]{0, 0.001f, 0, 0, 0, 0});
    float[] o4 = t.end();
    ok(o1.length == 2 && o2.length == 3 + 3, "T5 тишина в середине отдаётся вместе со следующим звуком: " + o1.length + ", " + o2.length);
    ok(o3.length == 0 && o4.length == 3, "T5 хвост из 8 тихих отсчётов урезан до keep=3: " + o3.length + " + " + o4.length);
    TtsVoices.TrimTail t2 = new TtsVoices.TrimTail(0.003f, 100);
    ok(t2.push(new float[]{0.3f, 0}).length == 1 && t2.end().length == 1, "T5 короткий хвост остаётся целиком");

    ok(TtsVoices.speed(1f) == 1f && TtsVoices.speed(0.1f) == 0.5f && TtsVoices.speed(3f) == 2f, "T6 скорость — в пределах 0,5–2×");
    ok(TtsVoices.speed(Float.NaN) == 1f && TtsVoices.speed(Float.POSITIVE_INFINITY) == 1f, "T6 не число — 1×");
    ok(TtsVoices.speed(1.26f) == 1.3f && TtsVoices.speed(1.24f) == 1.2f, "T6 до десятых: " + TtsVoices.speed(1.26f) + ", " + TtsVoices.speed(1.24f));
    ok(TtsVoices.speedAt(0) == 0.5f && TtsVoices.speedAt(5) == 1f && TtsVoices.speedAt(TtsVoices.SPEED_STEPS) == 2f, "T6 ползунок: 0 — 0,5×, 5 — 1×, конец — 2×");
    boolean round = true;
    for (int i = 0; i <= TtsVoices.SPEED_STEPS; i++) round &= TtsVoices.speedPos(TtsVoices.speedAt(i)) == i;
    ok(round, "T6 позиция → скорость → позиция — та же на всех 16 шагах");
    ok(TtsVoices.speedPos(9f) == TtsVoices.SPEED_STEPS && TtsVoices.speedPos(0f) == 0, "T6 чужое значение в настройках — край ползунка");
    ok("1,0×".equals(TtsVoices.speedLabel(1f)) && "0,5×".equals(TtsVoices.speedLabel(0.5f)) && "2,0×".equals(TtsVoices.speedLabel(2f))
        && "1,3×".equals(TtsVoices.speedLabel(1.3f)), "T6 надпись: " + TtsVoices.speedLabel(1f) + " " + TtsVoices.speedLabel(1.3f));

    System.out.println(fails == 0 ? "TtsVoices: " + checks + " проверок, все прошли" : "TtsVoices: провалов " + fails + " из " + checks);
    System.exit(fails == 0 ? 0 : 1);
  }
}
