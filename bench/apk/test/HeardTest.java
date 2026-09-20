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

  public static void main(String[] a) {
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

    // возвращается именно та фраза, которую прочли
    eq(Heard.fromScreen("Preciso da conta por favor", screen), p3, "H22 вернулась прочитанная фраза");

    System.out.println(fails == 0 ? "Heard: " + checks + " проверок, все прошли" : "Heard: провалов " + fails + " из " + checks);
    System.exit(fails == 0 ? 0 : 1);
  }
}
