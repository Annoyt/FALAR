package dev.agenttranslator;

import java.util.*;

/** Бюджет контекста уточнителя. Границы проверяются явно: мутационное тестирование ловит тест,
 *  который проходит и с «<», и с «<=». Запуск: bash bench/apk/test.sh. */
public class BriefTest {
  int fails = 0, checks = 0;
  void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static String rep(char c, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(c); return b.toString(); }

  public int run() {
    // бюджет: (2048 − 200 − 120) × 2,2 × 0,85 = 3231,36 → 3231 знак целиком
    eq(Brief.budget(Brief.DEFAULT_CHARS_PER_TOKEN, 0, 0, 0), 3231, "B1 бюджет пустого запроса");
    eq(Brief.budget(Brief.DEFAULT_CHARS_PER_TOKEN, 500, 100, 50), 3231 - 650, "B2 исходник, пары и тема вычитаются");
    eq(Brief.budget(0, 0, 0, 0), 3231, "B3 нулевой замер — значение по умолчанию");
    eq(Brief.budget(-1, 0, 0, 0), 3231, "B3 отрицательный замер — значение по умолчанию");
    eq(Brief.budget(4.0, 0, 0, 0), (int) Math.floor(1728 * 4.0 * 0.85), "B4 замер меняет бюджет");
    ok(Brief.budget(3.0, 0, 0, 0) > Brief.budget(2.0, 0, 0, 0), "B4 больше знаков на токен — больше влезает");

    // влезает ли исходник
    ok(Brief.fits(Brief.DEFAULT_CHARS_PER_TOKEN, 3231, 0), "B5 ровно на границе — влезает");
    ok(!Brief.fits(Brief.DEFAULT_CHARS_PER_TOKEN, 3232, 0), "B5 на знак больше — не влезает");
    ok(!Brief.fits(Brief.DEFAULT_CHARS_PER_TOKEN, 3000, 232), "B5 пары тоже занимают место");

    // замер знаков на токен
    eq(Brief.measured(1000, 400), 2.5, "B6 обычный замер");
    eq(Brief.measured(0, 5), Brief.DEFAULT_CHARS_PER_TOKEN, "B6 пустой образец");
    eq(Brief.measured(5, 0), Brief.DEFAULT_CHARS_PER_TOKEN, "B6 ноль токенов");
    eq(Brief.measured(5, 5), 1.0, "B6 токен на знак — ещё правдоподобно");
    eq(Brief.measured(5, 6), Brief.DEFAULT_CHARS_PER_TOKEN, "B6 токенов больше знаков — нелепо");
    eq(Brief.measured(-3, 2), Brief.DEFAULT_CHARS_PER_TOKEN, "B6 отрицательная длина");

    // отбор фона
    String a = rep('a', 100), b = rep('b', 100), c = rep('c', 100);
    eq(Brief.fit(null, 500).size(), 0, "F1 нет строк");
    eq(Brief.fit(Arrays.asList(a, b), 0).size(), 0, "F1 нулевой бюджет");
    eq(Brief.fit(Arrays.asList(a, b), -5).size(), 0, "F1 отрицательный бюджет");
    eq(Brief.fit(Arrays.asList(a, b, c), 303), Arrays.asList(a, b, c), "F2 всё влезает ровно — порядок от старых к новым");
    eq(Brief.fit(Arrays.asList(a, b, c), 302), Arrays.asList(b, c), "F3 на знак меньше — старейшая выпадает");
    eq(Brief.fit(Arrays.asList(a, b, c), 202), Arrays.asList(b, c), "F3 две новые ровно");
    eq(Brief.fit(Arrays.asList(a, b, c), 201), Arrays.asList(c), "F4 новейшая целиком, следующая не влезает — хвост старой не берётся");
    eq(Brief.fit(Arrays.asList(a, null, "  ", b), 500), Arrays.asList(a, b), "F5 пустые и null пропускаются");
    eq(Brief.fit(Arrays.asList(" " + a + " "), 500), Arrays.asList(a), "F5 края строк обрезаются");

    // самая новая не влезает — берётся её конец
    String words = "um dois tres quatro cinco seis sete oito nove dez onze doze treze catorze quinze";
    List<String> t = Brief.fit(Arrays.asList(a, words), 40);
    eq(t.size(), 1, "F6 от длинной новейшей — одна строка");
    ok(t.get(0).startsWith("…"), "F6 обрезанное помечено: " + t.get(0));
    ok(t.get(0).length() <= 39, "F6 хвост не длиннее бюджета без перевода строки: " + t.get(0).length());
    ok(words.endsWith(t.get(0).substring(1)), "F6 это именно конец реплики: " + t.get(0));
    ok(!t.get(0).substring(1).startsWith(" ") && words.contains(" " + t.get(0).substring(1)), "F6 обрезано по границе слова: " + t.get(0));
    eq(Brief.fit(Arrays.asList(words), 12).size(), 0, "F7 при 12 знаках места хвост не берётся — бессмысленный обрывок");
    eq(Brief.fit(Arrays.asList(words), 13).size(), 1, "F7 при 13 уже берётся");

    // хвост строки
    eq(Brief.tail("abc", 5), "abc", "T1 короткая строка не меняется");
    eq(Brief.tail("abc", 3), "abc", "T1 ровно по длине не меняется");
    eq(Brief.tail("abcdef", 1), "…", "T2 на один знак — только многоточие");
    eq(Brief.tail("abcdef", 0), "…", "T2 на ноль знаков — только многоточие");
    eq(Brief.tail("one two three four", 11), "…three four", "T3 отрез ровно на начале слова — слово целиком");
    eq(Brief.tail("one two three four", 9), "…four", "T3 отрез посреди слова — обрывок выброшен");
    eq(Brief.tail("abc def", 5), "…def", "T3 отрез на пробеле — начинаем со слова");
    eq(Brief.tail("abcdefghij", 5), "…ghij", "T4 без пробелов — просто хвост");
    eq(Brief.tail("abcdef ghij", 3), "…ij", "T4 пробел только в конце обрывка — режем как есть");
    ok(Brief.tail("hello world again", 8).length() <= 8, "T5 хвост не длиннее n: " + Brief.tail("hello world again", 8));
    eq(Brief.tail("hello world again", 8), "…again", "T5 обрывок «ld» выброшен");
    eq(Brief.tail("abcdefg ", 4), "…fg", "T6 пробел последним — хвост остаётся, а не одно многоточие");

    return fails;
  }

  public static void main(String[] x) {
    BriefTest t = new BriefTest(); int f = t.run();
    System.out.println(f == 0 ? "Brief: " + t.checks + " проверок, все прошли" : "Brief: провалов " + f + " из " + t.checks);
    System.exit(f == 0 ? 0 : 1);
  }
}
