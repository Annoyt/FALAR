package dev.agenttranslator;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Разбор слов для «Слов»: реплики стенда (поле `stand`) не считаются. На 02.10 треть реплик в
 *  разговорах владельца была корпусом прогонов, и «Слова» учили слова замеров. Запуск: bash bench/apk/test.sh. */
public class LearnTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static File tmp() throws Exception { File d = Files.createTempDirectory("learn").toFile(); d.deleteOnExit(); return d; }

  public static int run() throws Exception {
    fails = 0; checks = 0;
    File files = tmp(), models = tmp();
    Chats c = new Chats(files);
    c.add("pt2ru", "Espera um pouquinho, já volto", "Подождите немножко, сейчас вернусь", null, 1000);
    c.add("ru2pt", "Говорите чуть медленнее", "Fala um pouquinho mais devagar", null, 2000);
    // корпус прогона: и с повтором слова внутри, и в другом разговоре
    c.addTurn(c.current, Chats.turn("pt2ru", "Sou um turista, turista mesmo", "Я турист, правда турист", null, 3000).put("stand", 1));
    c.newChat("");
    c.addTurn(c.current, Chats.turn("pt2ru", "Seu relógio está certo", "Ваши часы идут верно", null, 4000).put("stand", 1));
    c.add("pt2ru", "Um pouquinho de água", "Немножко воды", null, 5000);

    Learn l = new Learn(c, models, files);
    Map<String, Learn.Word> top = new HashMap<>();
    for (Learn.Word w : l.top(1, 100)) top.put(w.w, w);
    ok(top.containsKey("pouquinho"), "L1 живое слово в списке");
    eq(top.containsKey("pouquinho") ? top.get("pouquinho").n : -1, 3, "L1 счёт по живым репликам обоих разговоров");
    ok(!top.containsKey("turista") && !top.containsKey("relógio"), "L2 слова стендовых реплик не считаются");
    ok(!l.inCorpus("turista"), "L2 и для перевода слов их нет");
    String ex = top.containsKey("pouquinho") ? top.get("pouquinho").pt : "";
    ok(!ex.isEmpty() && !ex.contains("turista"), "L3 пример — из живой реплики: " + ex);

    // L4 лесенка повторения (решение владельца 03.10): «знаю» — завтра; удача 1 → 3 → 7 → 21 день, дальше раз в
    // 21 день; промах — завтра и сначала. Дни календарные: ответ в 23:00 и в 08:00 дают один и тот же срок.
    TimeZone was = TimeZone.getDefault();
    try {
      TimeZone tz = TimeZone.getTimeZone("America/Sao_Paulo"); TimeZone.setDefault(tz);
      Calendar cal = Calendar.getInstance(tz); cal.clear(); cal.set(2026, Calendar.OCTOBER, 3, 23, 0); long eve = cal.getTimeInMillis();
      cal.set(2026, Calendar.OCTOBER, 3, 8, 0); long morn = cal.getTimeInMillis();
      cal.set(2026, Calendar.OCTOBER, 4, 0, 0); long d4 = cal.getTimeInMillis();
      eq(Learn.dueIn(eve, 1, tz), d4, "L4 вечером — спросим с полуночи");
      eq(Learn.dueIn(morn, 1, tz), d4, "L4 утром — тот же срок");
      long now = eve;
      l.setKnown("pouquinho", true, now);
      ok(l.due(now).isEmpty(), "L4 отмеченное «знаю» сегодня не спрашивается");
      eq(Learn.daysUntil(l.nextDue(now), now, tz), 1, "L4 первый раз — завтра");
      now = d4 + 9 * 3600_000L;
      eq(l.due(now), Arrays.asList("pouquinho"), "L4 назавтра пора");
      int[] want = {3, 7, 21, 21};
      for (int k = 0; k < want.length; k++) {
        l.review("pouquinho", true, now);
        eq(Learn.daysUntil(l.dueOf("pouquinho"), now, tz), want[k], "L4 удача " + (k + 1) + " — через " + want[k]);
        ok(l.due(now).isEmpty(), "L4 после ответа сегодня не спрашивается");
        now = l.dueOf("pouquinho") + 10 * 3600_000L;
      }
      l.review("pouquinho", false, now);
      eq(Learn.daysUntil(l.dueOf("pouquinho"), now, tz), 1, "L4 промах — завтра");
      ok(l.known.contains("pouquinho"), "L4 промах оставляет в «Знаю»");
      now = l.dueOf("pouquinho") + 3600_000L;
      l.review("pouquinho", true, now);
      eq(Learn.daysUntil(l.dueOf("pouquinho"), now, tz), 3, "L4 после промаха лесенка сначала");
      eq(l.statOf("pouquinho"), "помню 5 · забыл 1", "L4 история «помню / забыл» сохраняется");
      // L5 сохраняется между запусками; старые форматы — спросить сразу
      Learn l2 = new Learn(c, models, files);
      eq(l2.dueOf("pouquinho"), l.dueOf("pouquinho"), "L5 срок пережил перезапуск");
      eq(l2.statOf("pouquinho"), "помню 5 · забыл 1", "L5 история пережила перезапуск");
      Files.write(new File(files, "known_words.json").toPath(), "[\"água\"]".getBytes("UTF-8"));
      Learn l3 = new Learn(c, models, files);
      eq(l3.due(now), Arrays.asList("água"), "L5 старый список — спросить сразу");
      Files.write(new File(files, "known_words.json").toPath(), "{\"turista\":{\"ok\":2,\"fail\":1,\"last\":5},\"água\":{\"ok\":0,\"fail\":0,\"last\":0,\"step\":1,\"due\":1}}".getBytes("UTF-8"));
      Learn l4 = new Learn(c, models, files);
      eq(l4.due(now), Arrays.asList("turista", "água"), "L5 без срока — сразу; при равных — где спотыкались");
      eq(l4.statOf("turista"), "помню 2 · забыл 1", "L5 прежняя история на месте");
      l4.setKnown("turista", true, now);
      eq(l4.due(now).size(), 2, "L5 повторное «знаю» срок не сбрасывает");
      l4.setKnown("turista", false, now); l4.setKnown("turista", true, now);
      eq(l4.due(now), Arrays.asList("água"), "L5 «снова учить» и «знаю» — снова завтра");

      // L6 метрика: первая попытка по ключу за календарный день, «сказано самим», окно — неделя
      cal.set(2026, Calendar.OCTOBER, 3, 20, 0); long t0 = cal.getTimeInMillis();
      cal.set(2026, Calendar.OCTOBER, 4, 19, 0); long t1 = cal.getTimeInMillis();
      ok(l.recordPractice("um pouquinho", true, t0), "L6 первая попытка за день");
      ok(!l.recordPractice("um pouquinho", false, t0 + 60_000), "L6 вторая за день — не первая");
      ok(l.recordPractice("mandar", false, t0 + 120_000), "L6 другой ключ — первая");
      ok(l.recordPractice("um pouquinho", false, t1), "L6 назавтра в 19:00 (меньше суток спустя) — снова первая");
      l.recordSaid(t0 + 5000);
      eq(Arrays.toString(l.week(t1 + 1)), "[3, 1, 1]", "L6 неделя: 3 первых, 1 распознана, 1 сказано самим");
      eq(Arrays.toString(l.week(t0 + Learn.WEEK + 3600_000L)), "[1, 0, 0]", "L6 старше недели — не считается");
      Learn l5 = new Learn(c, models, files);
      eq(Arrays.toString(l5.week(t1 + 1)), "[3, 1, 1]", "L6 события пережили перезапуск");
    } finally { TimeZone.setDefault(was); }

    System.out.println(fails == 0 ? "Learn: " + checks + " проверок, все прошли" : "Learn: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
