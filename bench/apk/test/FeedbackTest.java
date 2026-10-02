package dev.agenttranslator;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Настольные тесты ссылок обратной связи: GitHub по шаблонам и бот в Telegram.
 *  Запуск: bash bench/apk/test.sh (аргумент — корень репозитория: сверка с шаблонами и ботом). */
public class FeedbackTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }

  /** Поля ссылки: имя → значение, раскодированное. */
  static Map<String, String> query(String url) {
    Map<String, String> m = new LinkedHashMap<>();
    for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
      int i = kv.indexOf('=');
      m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
    }
    return m;
  }
  static String rep(String s, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(s); return b.toString(); }

  public static int run(String root) throws Exception {
    fails = 0; checks = 0;
    String dev = "Falar 0.26.1 · Xiaomi Redmi Note 10 Pro · Android 13";

    // короткое — как есть, по шаблону, без labels= (её ставит шаблон, у любого автора)
    String g = Feedback.githubTranslation("pt2ru", "Bom dia, tudo bem?", "Добрый день, всё хорошо?", "Доброе утро, как дела?", dev);
    ok(g.startsWith("https://github.com/Annoyt/FALAR/issues/new?template=translation.yml&"), "G1 шаблон перевода: " + g.substring(0, 70));
    ok(!g.contains("labels="), "G1 без labels=");
    Map<String, String> q = query(g);
    eq(q.get("title"), "перевод: Bom dia, tudo bem?", "G1 заголовок");
    eq(q.get("direction"), "португальский → русский", "G1 направление");
    eq(q.get("source"), "Bom dia, tudo bem?", "G1 исходник");
    eq(q.get("app"), "Добрый день, всё хорошо?", "G1 перевод приложения");
    eq(q.get("better"), "Доброе утро, как дела?", "G1 вариант человека");
    eq(q.get("device"), dev, "G1 версия и телефон");
    eq(query(Feedback.githubTranslation("ru2pt", "Да", "Sim", "", dev)).get("direction"), "русский → португальский", "G2 обратное направление");
    eq(query(Feedback.githubTranslation("ru2pt", "Да", "Sim", "", dev)).get("better"), "", "G2 без варианта — пусто");

    // длинное (чек со снимка): ссылка не длиннее предела, обрезано с «…», остальное цело
    String srcL = rep("Arroz tipo 1 5kg R$ 25,90 ", 120), dstL = rep("Рис первого сорта 5 кг 25,90 реала ", 120);
    g = Feedback.githubTranslation("pt2ru", srcL, dstL, "мой вариант", dev);
    ok(g.length() <= Feedback.GITHUB_MAX, "G3 длинное — ссылка " + g.length() + " ≤ " + Feedback.GITHUB_MAX);
    ok(Feedback.GITHUB_MAX < 7004, "G3 предел ниже замеренных 7004 байт");
    q = query(g);
    ok(q.get("source").endsWith("…") && srcL.startsWith(q.get("source").substring(0, q.get("source").length() - 1)), "G3 исходник — начало и «…»");
    ok(q.get("app").endsWith("…") && dstL.startsWith(q.get("app").substring(0, q.get("app").length() - 1)), "G3 перевод — начало и «…»");
    eq(q.get("better"), "мой вариант", "G3 короткий вариант не тронут");
    eq(q.get("device"), dev, "G3 версия цела");
    ok(q.get("title").length() <= "перевод: ".length() + 61, "G3 заголовок короткий");
    ok(g.length() > Feedback.GITHUB_MAX - 700, "G3 обрезано не лишнее: " + g.length());

    // Telegram: метка первой строкой — по ней бот выбирает топик
    String t = Feedback.translationText("pt2ru", "Bom dia", "Добрый день", "", dev);
    eq(t, "#перевод · португальский → русский\nИсходник: Bom dia\nПеревод приложения: Добрый день\nКак правильно: (не указано)\n\n" + dev, "T1 текст сообщения");
    ok(Feedback.translationText("ru2pt", "Да", "Sim", "Sim, claro", dev).contains("\nКак правильно: Sim, claro\n"), "T1 вариант человека — в тексте");
    t = Feedback.translationText("pt2ru", srcL, dstL, rep("я", 3000), dev);
    ok(t.length() <= Feedback.TELEGRAM_MAX, "T2 длинное — " + t.length() + " ≤ " + Feedback.TELEGRAM_MAX);
    ok(t.startsWith("#перевод · ") && t.endsWith(dev), "T2 метка и версия целы");
    String u = Feedback.telegram(t);
    ok(u.startsWith("https://t.me/" + Feedback.BOT + "?text="), "T3 ссылка на бота");
    eq(query(u).get("text"), t, "T3 текст раскодируется обратно");
    ok(!u.contains("+"), "T4 пробел — %20, а не «+»");
    eq(Feedback.enc("a b+c"), "a%20b%2Bc", "T4 плюс — %2B");

    // без реплики
    eq(Feedback.noteText(true, dev), "#ошибка · " + dev + "\n", "N1 ошибка в Telegram");
    eq(Feedback.noteText(false, dev), "#идея · " + dev + "\n", "N1 идея в Telegram");
    q = query(Feedback.githubNote(true, dev));
    ok(Feedback.githubNote(true, dev).contains("template=bug.yml"), "N2 шаблон ошибки");
    ok(Feedback.githubNote(false, dev).contains("template=idea.yml"), "N2 шаблон идеи");
    eq(q.get("device"), dev, "N2 версия");

    // обрезка
    eq(Feedback.cut("абв", 5), "абв", "C1 короче предела — как есть");
    eq(Feedback.cut("абв где", 4), "абв…", "C1 пробел перед «…» убран");
    String emo = "ab😀cd";
    eq(Feedback.cut(emo, 3), "ab…", "C2 смайлик пополам не режется");
    ok(Feedback.fit(new String[]{"abc"}, 1, p -> "длинная постоянная часть " + p[0]).startsWith("длинная"), "C3 не уложилось и пустым — не зацикливается");
    eq(Feedback.cut("абв", 3), "абв", "C4 ровно по пределу — без «…»");
    eq(Feedback.cut("abcdef", 3), "abc…", "C4 обычная буква перед разрезом остаётся");
    eq(Feedback.fit(new String[]{"abcd"}, 4, p -> p[0]), "abcd", "C5 ровно по пределу — не режется");
    eq(Feedback.fit(new String[]{"aaaa", "bbbb"}, 7, p -> p[0] + p[1]), "aa…bbb…", "C5 при равной длине режется первая часть");
    eq(Feedback.direction(null), "русский → португальский", "C6 направление неизвестно — не падает");

    // сверка с ботом и шаблонами: метки и поля должны совпадать с тем, что в репозитории
    if (root != null && Files.isDirectory(Paths.get(root, "bot"))) {
      String bot = new String(Files.readAllBytes(Paths.get(root, "bot/lib/feedback.js")), StandardCharsets.UTF_8);
      for (String tag : new String[]{Feedback.TAG_TRANSLATION, Feedback.TAG_BUG, Feedback.TAG_IDEA})
        ok(bot.contains("tag: '" + tag + "'"), "R1 бот знает метку " + tag);
      String[][] forms = {
          {"translation.yml", Feedback.githubTranslation("pt2ru", "a", "b", "c", dev)},
          {"bug.yml", Feedback.githubNote(true, dev)},
          {"idea.yml", Feedback.githubNote(false, dev)}};
      for (String[] f : forms) {
        String yml = new String(Files.readAllBytes(Paths.get(root, ".github/ISSUE_TEMPLATE", f[0])), StandardCharsets.UTF_8);
        for (String k : query(f[1]).keySet()) {
          if (k.equals("template") || k.equals("title")) continue;
          ok(yml.contains("\n    id: " + k + "\n"), "R2 поле " + k + " есть в " + f[0]);
        }
      }
      String cfg = new String(Files.readAllBytes(Paths.get(root, ".github/ISSUE_TEMPLATE/config.yml")), StandardCharsets.UTF_8);
      ok(cfg.contains("https://t.me/" + Feedback.BOT + "\n"), "R3 ссылка на бота на GitHub — тот же бот");
      String site = new String(Files.readAllBytes(Paths.get(root, "docs/index.html")), StandardCharsets.UTF_8);
      ok(site.contains("https://t.me/" + Feedback.BOT + "\""), "R3 ссылка на бота на сайте — тот же бот");
    } else { fails++; System.out.println("  ПРОВАЛ: нет корня репозитория для сверки: " + root); }

    System.out.println(fails == 0 ? "Feedback: " + checks + " проверок, все прошли" : "Feedback: провалов " + fails + " из " + checks);
    return fails;
  }

  public static void main(String[] a) throws Exception { System.exit(run(a.length > 0 ? a[0] : "../..") == 0 ? 0 : 1); }
}
