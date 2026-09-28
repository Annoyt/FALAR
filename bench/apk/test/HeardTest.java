package dev.agenttranslator;

import java.util.*;

/** Настольные тесты фильтра «это прочли с экрана». Проверяется и то, что он ловит чтение вслух,
 *  и то, что он НЕ съедает настоящие ответы собеседника — вторая ошибка дороже первой.
 *  Запуск: bash bench/apk/test.sh. */
public class HeardTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static void caught(String said, List<String> shown, String what) { ok(Heard.fromScreen(said, shown) != null, what + " — должно было отсеяться: «" + said + "»"); }
  static void passed(String said, List<String> shown, String what) { ok(Heard.fromScreen(said, shown) == null, what + " — не должно было отсеяться: «" + said + "»"); }

  /** Один прогон: счётчики обнуляются, потому что PIT гоняет набор много раз в одной JVM. */
  public static int run() throws Exception {
    fails = 0; checks = 0;
    String p1 = "Você precisa trocar a correia dentada, senão o motor pode quebrar.";
    String p2 = "Bom dia, posso ajudar?";
    String p3 = "Preciso da conta, por favor.";
    List<String> screen = Arrays.asList(p2, p1, p3);

    // разбор слов
    eq(Heard.words("Você precisa, senão!").toString(), "[voce, precisa, senao]", "W1 регистр, диакритика и знаки убраны");
    eq(Heard.words("").size(), 0, "W2 пустая строка");
    eq(Heard.words(null).size(), 0, "W3 null");
    eq(Heard.words("São Paulo 55").toString(), "[sao, paulo, 55]", "W4 числа сохраняются");
    eq(Heard.words("  a   b  ").toString(), "[a, b]", "W5 лишние пробелы");

    // чтение вслух ловится
    caught(p1, screen, "H1 фраза прочитана дословно");
    caught(p1.toUpperCase(Locale.ROOT), screen, "H2 распознано другим регистром");
    caught("voce precisa trocar a correia dentada senao o motor pode quebrar", screen, "H3 без диакритики и знаков");
    caught("Você precisa trocar a correia dentada", screen, "H4 прочитана первая половина");
    caught("precisa trocar a correia", screen, "H5 прочитан кусок из середины");
    caught("Preciso da conta por favor", screen, "H6 фраза не последняя, а третья с конца");
    caught("Bom dia posso ajudar", screen, "H7 самая старая из показанных");

    // настоящие ответы собеседника не трогаем
    passed("Quanto custa trocar a correia?", screen, "H8 вопрос про ту же деталь");
    passed("Sim, preciso disso hoje de manhã", screen, "H9 ответ со словом из фразы");
    passed("O senhor pode voltar amanhã depois do almoço", screen, "H10 совсем другой ответ");
    passed("Não", screen, "H11 односложный ответ");
    passed("Bom dia", screen, "H12 два слова — слишком мало, чтобы судить");
    passed("Tudo bem obrigado", screen, "H13 три чужих слова");

    // границы
    passed("qualquer coisa aqui", null, "H14 нет показанных фраз");
    passed("qualquer coisa aqui", Collections.<String>emptyList(), "H15 пустой список");
    passed(null, screen, "H16 null вместо сказанного");
    passed("", screen, "H17 пустая строка");
    ok(Heard.fromScreen("...!!! ???", screen) == null, "H18 одни знаки препинания");
    List<String> withEmpty = Arrays.asList("", null, p1);
    caught(p1, withEmpty, "H19 пустые и null среди показанных не мешают");

    // глубина: фраза дальше шести реплик назад уже не считается «с экрана»
    List<String> deep = new ArrayList<>(Collections.nCopies(Heard.DEPTH, "outra frase qualquer aqui"));
    deep.add(0, p1);
    passed(p1, deep, "H20 фраза за пределами глубины просмотра");
    List<String> edge = new ArrayList<>(Collections.nCopies(Heard.DEPTH - 1, "outra frase qualquer aqui"));
    edge.add(0, p1);
    caught(p1, edge, "H21 фраза на самой границе глубины");

    // границы порогов: ровно три слова и ровно 80 %
    caught("trocar a correia", screen, "H23 ровно три слова из фразы — уже чтение");
    caught("trocar a correia dentada hoje", screen, "H24 ровно 80 % (4 из 5) — чтение");
    passed("trocar a correia hoje amanha", screen, "H25 60 % (3 из 5) — не чтение");

    // пустые строки не съедают глубину просмотра
    List<String> blanks = new ArrayList<>(); blanks.add(p1);
    for (int k = 0; k < Heard.DEPTH + 3; k++) blanks.add("   ");
    caught(p1, blanks, "H26 пустые строки новее фразы не считаются в глубину");
    caught(p1, Arrays.asList(p1, null, ""), "H27 null и пустая новее фразы — пропускаются, а не роняют");

    // эхо собственной озвучки: одно слово тоже эхо, если оно только что прозвучало
    long end = 1_000_000;
    ok(Heard.echo("Obrigado", "Obrigado.", end + 1000, end), "E1 одно слово сразу после озвучки — эхо");
    ok(Heard.echo("preciso da conta", "Preciso da conta, por favor.", end + 3999, end), "E2 кусок фразы в пределах 4 с — эхо");
    ok(Heard.echo("preciso da conta", "Preciso da conta, por favor.", end + 4000, end), "E3 ровно 4 с — ещё эхо");
    ok(!Heard.echo("preciso da conta", "Preciso da conta, por favor.", end + 4001, end), "E3 позже 4 с — уже нет");
    ok(!Heard.echo("quanto custa", "Preciso da conta, por favor.", end + 500, end), "E4 другие слова — не эхо");
    ok(!Heard.echo("preciso de agua gelada", "Preciso da conta.", end + 500, end), "E5 одно общее слово из четырёх — не эхо");
    ok(!Heard.echo("preciso da conta agora", "Preciso da conta.", end + 500, end), "E6 три из четырёх, 75 % — не эхо");
    ok(Heard.echo("preciso da conta agora sim", "Preciso da conta agora.", end + 500, end), "E6 четыре из пяти, 80 % — эхо");
    ok(!Heard.echo("", "Preciso da conta.", end + 500, end), "E7 пусто — не эхо");
    ok(!Heard.echo("preciso", null, end + 500, end), "E8 ничего не звучало — не эхо");
    ok(!Heard.echo("preciso", "preciso", end - 70_000, end), "E9 часы ушли назад больше чем на минуту — не эхо");
    ok(Heard.echo("preciso", "preciso", end - 60_000, end), "E9 ровно минута до конца озвучки — ещё сверяем");

    // возвращается именно та фраза, которую прочли
    eq(Heard.fromScreen("Preciso da conta por favor", screen), p3, "H22 вернулась прочитанная фраза");

    System.out.println(fails == 0 ? "Heard: " + checks + " проверок, все прошли" : "Heard: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
