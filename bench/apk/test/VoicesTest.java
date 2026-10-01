package dev.agenttranslator;

import java.util.*;
import org.json.*;

/** Голоса разговора (Voices): фраза кнопкой FALAR — голос знакомый или новый, слепок подстраивается,
 *  номера не переиспользуются, имя человека автоматика не трогает, файл переживает битые записи.
 *  Отпечатки здесь — короткие векторы единичной длины: проверяется счёт, а не модель (её качество —
 *  results/2026-10-01-voices.md). Запуск: bash bench/apk/test.sh. */
public class VoicesTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": «" + a + "» ≠ «" + b + "»"); }
  static float[] v(double... x) { float[] f = new float[x.length]; for (int i = 0; i < x.length; i++) f[i] = (float) x[i]; return Voices.unit(f); }
  static double len(float[] e) { double s = 0; for (float x : e) s += x * x; return Math.sqrt(s); }

  public static int run() throws Exception {
    fails = 0; checks = 0;
    float[] a = v(1, 0, 0, 0), a2 = v(0.95, 0.31, 0, 0), b = v(0, 1, 0, 0), c = v(0, 0, 1, 0), ab = v(0.6, 0.8, 0, 0);
    ok(Voices.cos(a, a2) >= Voices.SAME && Voices.cos(a, b) < Voices.SAME, "V0 векторы теста: a2 похож на a, b — нет");

    Voices vs = new Voices();
    Voices.Match m0 = vs.best(a);
    ok(m0.v == null && !m0.hit(0f), "V1 голосов нет — никто не опознан, даже при пороге 0");

    Voices.Voice v1 = vs.enroll(a, "ru", 100, 1);
    eq(v1.n + " " + v1.lang + " " + v1.k + " " + v1.ru + "/" + v1.pt, "1 ru 1 1/0", "V2 первая фраза — собеседник 1, русский");
    Voices.Voice same = vs.enroll(a2, "ru", 200, 2);
    ok(same == v1 && vs.size() == 1, "V3 похожая фраза — тот же голос, нового нет");
    eq(v1.k, 2, "V3 в слепке две фразы");
    ok(Math.abs(len(v1.print()) - 1) < 1e-5, "V3 слепок остаётся единичной длины");
    ok(Voices.cos(v1.print(), a2) > Voices.cos(a, a2), "V3 слепок сдвинулся к новой фразе");
    Voices.Voice v2 = vs.enroll(b, "pt", 300, 2);
    eq(v2.n + " " + v2.lang + " " + vs.size(), "2 pt 2", "V4 непохожий голос — собеседник 2, португальский");
    Voices.Voice v5 = vs.enroll(c, "pt", 400, 5);
    eq(v5.n, 5, "V5 номер — не меньше переданного: номера забытых голосов не переиспользуются");
    Voices.Voice v6 = new Voices().enroll(c, "pt", 0, 0);
    eq(v6.n, 1, "V5 номер не меньше 1");

    // Ближайший и второй: по разнице видно, уверенно ли опознание.
    Voices.Match m = vs.best(ab);
    ok(m.v == v2 && m.next == v1 && m.score > m.nextScore, "V6 ближайший — 2, за ним 1");
    ok(Math.abs(m.score - Voices.cos(ab, v2.print())) < 1e-6, "V6 косинус ближайшего — к его слепку");
    ok(m.hit(0.7f) && !m.hit(0.9f), "V6 hit — по порогу");

    // Язык — большинство фраз кнопкой; поровну — прежний.
    Voices lv = new Voices(); Voices.Voice l = lv.enroll(a, "pt", 0, 1);
    lv.enroll(a, "ru", 0, 2); eq(l.lang + " " + l.ru + "/" + l.pt, "pt 1/1", "V7 поровну — язык прежний");
    lv.enroll(a, "ru", 0, 2); eq(l.lang, "ru", "V7 русских фраз больше — русский");
    for (int i = 0; i < 20; i++) lv.enroll(a2, "ru", 0, 2);
    eq(l.k, Voices.K, "V8 в слепке не больше K фраз: новая всё ещё что-то весит");

    // Имя: человек главнее автоматики.
    ok(vs.name(2, "Ана", "auto"), "V9 имя нашлось в разговоре");
    eq(Voices.label(v2), "Ана", "V9 подпись — имя");
    ok(vs.name(2, "Анна", "user") && "user".equals(v2.nameBy), "V9 человек поправил имя");
    ok(!vs.name(2, "Мария", "auto") && v2.name.equals("Анна"), "V10 автоматика имя человека не трогает");
    ok(vs.name(2, "  ", "user") && v2.name.isEmpty() && v2.nameBy.isEmpty(), "V10 пустое имя от человека — снова номер");
    ok(!vs.name(9, "X", "user"), "V10 голоса 9 нет");
    eq(vs.label("2"), "собеседник 2", "V11 подпись без имени — номер");
    eq(vs.label(" 1 "), "собеседник 1", "V11 номер с пробелами");
    eq(vs.label("7"), "собеседник 7", "V11 забытый голос — номер из реплики остаётся подписью");
    eq(vs.label(Voices.OWNER), "владелец телефона", "V11 снимок и набранное — владелец");
    eq(vs.label("я") + "|" + vs.label("") + "|" + vs.label((String) null), "||", "V11 старые и пустые метки — без подписи");
    ok(vs.get("x") == null && vs.get("2") == v2, "V11 голос по полю реплики");
    ok(vs.says(1, " спрашивает цену ") && v1.says.equals("спрашивает цену"), "V12 о чём говорит");

    // Файл: всё на месте, вектор с точностью пятого знака.
    vs.name(1, "Игорь", "user");
    JSONArray j = vs.toJson();
    Voices back = Voices.fromJson(new JSONArray(j.toString()));
    eq(back.size(), vs.size(), "V13 голоса пережили файл");
    Voices.Voice b1 = back.get(1), b2 = back.get(2);
    eq(b1.name + " " + b1.nameBy + " " + b1.says + " " + b1.lang + " " + b1.k + " " + b1.ru + "/" + b1.pt + " " + b1.at,
       "Игорь user спрашивает цену ru 2 2/0 100", "V13 поля голоса 1");
    ok(Voices.cos(b1.print(), v1.print()) > 0.99999, "V13 слепок тот же");
    ok(b2.name.isEmpty() && !j.getJSONObject(1).has("name"), "V13 пустое имя в файл не пишется");
    // Битые записи пропускаются, а не роняют разговор.
    JSONArray bad = new JSONArray(j.toString());
    bad.put(new JSONObject().put("n", 8).put("lang", "pt"));                                  // без слепка
    bad.put(new JSONObject().put("n", 0).put("e", new JSONArray("[1,0,0,0]")));              // номер 0
    bad.put(new JSONObject().put("n", 9).put("e", new JSONArray("[1,0,0]")));                // другая длина
    bad.put(new JSONObject().put("n", 1).put("e", new JSONArray("[0,0,0,1]")));              // повтор номера
    bad.put("мусор");
    Voices bb = Voices.fromJson(bad);
    eq(bb.size(), vs.size(), "V14 битые записи пропущены");
    ok(Voices.cos(bb.get(1).print(), v1.print()) > 0.99999, "V14 повтор номера не затёр первый");
    eq(Voices.fromJson(null).size(), 0, "V14 поля нет — голосов нет");
    eq(vs.clear(), 3, "V15 забыть — сколько было"); ok(vs.isEmpty(), "V15 пусто");

    System.out.println(fails == 0 ? "Voices: " + checks + " проверок, все прошли" : "Voices: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
