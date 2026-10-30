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

    // ответ, вобравший фон: длина. Реплика — большее из исходника и перевода; предел 2,5 × 40 + 12 = 112
    String s40 = rep('s', 40), d30 = rep('d', 30);
    eq(Brief.swallowed(rep('o', 112), s40, d30, null), null, "S1 ровно на пределе — годится");
    eq(Brief.swallowed(rep('o', 113), s40, d30, null), "в 2.8 раза длиннее реплики (113 знаков на 40)", "S1 на знак длиннее — отброшен, причина для журнала");
    eq(Brief.swallowed(rep('o', 112), d30, s40, null), null, "S2 перевод длиннее исходника — мерило он: обрезанный черновик не мешает полному");
    ok(Brief.swallowed(rep('o', 113), d30, s40, null) != null, "S2 … и предел тот же");
    eq(Brief.swallowed("Привет", "Oi", "Ой", null), null, "S3 «Oi» → «Привет»: втрое длиннее, но короткой реплике — запас");
    eq(Brief.swallowed(rep('o', 17), "Oi", "Ой", null), null, "S3 короткая: 2,5 × 2 + 12 = 17 — годится");
    ok(Brief.swallowed(rep('o', 18), "Oi", "Ой", null) != null, "S3 короткая: 18 — отброшен");
    eq(Brief.swallowed(null, s40, d30, null), null, "S4 ответа нет — отбрасывать нечего");
    eq(Brief.swallowed("", s40, d30, null), null, "S4 пустой ответ");
    eq(Brief.swallowed(rep('o', 112), s40, null, null), null, "S4 перевода нет — мерило исходник");
    eq(Brief.swallowed(rep('o', 12), null, null, null), null, "S4 ни исходника, ни перевода: только запас — 12 годится");
    eq(Brief.swallowed(rep('o', 13), null, null, null), "в 13.0 раза длиннее реплики (13 знаков на 0)", "S4 … 13 — отброшен, без деления на ноль");
    // ответы Hy-MT2 на короткие реплики с фоном разговора (results/2026-10-27-refine-guard.md)
    ok(Brief.swallowed("Вот перевод на русский язык с учетом предоставленной информации:  **Текст:** Приятно.", "Isso mesmo.", "Именно так.", null) != null,
        "S5 болтовня вместо перевода «Isso mesmo.» — отброшен");
    ok(Brief.swallowed("Это очень полезная информация! Пожалуйста, продолжайте.", "É", "Да", null) != null, "S5 «É» → ответ фону — отброшен");
    eq(Brief.swallowed("Aqui está:", "Вот", "Aqui", null), null, "S5 «Вот» → «Aqui está:» — короткое, годится");
    eq(Brief.swallowed("Ok, tudo bem?", "Ну", "Bem", null), null, "S5 «Ну» → «Ok, tudo bem?» — вчетверо длиннее, но в запасе");

    // кусок другой реплики. Как на живом разговоре: ответ = перевод реплики + перевод соседней
    String src = "Você precisa fazer o exame de sangue amanhã cedo.", draft = "Вам нужно сдать анализ крови завтра рано утром.";
    List<String> near = Arrays.asList("Хорошо, а где находится лаборатория и во сколько она открывается?", "Ok, e onde fica o laboratório e a que horas abre?");
    String leak = draft + " Хорошо, а где находится лаборатория и во сколько она открывается?";
    eq(Brief.swallowed(leak, src, draft, near), "кусок другой реплики «хорошо а где находится лаборатория и во сколько она открывается», ответ в 2.3 раза длиннее реплики",
        "K1 перевод соседней реплики в ответе — отброшен, хотя до предела длины далеко");
    eq(Brief.swallowed(leak, src, draft, null), null, "K1 без чужих текстов то же — годится: по длине одной не отбросить");
    // человек повторил сказанное раньше — совпадение с соседней репликой есть, прибавки нет
    String again = "Да, а где находится лаборатория, пожалуйста?";
    eq(Brief.copied(again, Arrays.asList("Sim, e onde fica o laboratório, por favor?", "Да, а где лаборатория, пожалуйста?"), near), "находится лаборатория",
        "K2 совпадение с соседней репликой находится");
    eq(Brief.swallowed(again, "Sim, e onde fica o laboratório, por favor?", "Да, а где лаборатория, пожалуйста?", near), null,
        "K2 … но ответ не длиннее реплики — это повтор, годится");
    // имена и числа — свои: они есть в исходнике и черновике реплики
    String street = "Он живёт на Rua Doutor Arnaldo 455, рядом с больницей, это совсем недалеко отсюда, минут пять пешком.";
    eq(Brief.swallowed(street, "Ele mora na Rua Doutor Arnaldo 455, perto do hospital.", "Он живёт на Rua Doutor Arnaldo 455, рядом с больницей.",
        Arrays.asList("A farmácia também fica na Rua Doutor Arnaldo 455.", "Аптека тоже на Rua Doutor Arnaldo 455.")), null,
        "K3 адрес из соседней реплики есть и в самой реплике — не чужой кусок");
    // строка фона, списанная как есть, на языке исходника
    eq(Brief.swallowed("Можете повторить, пожалуйста? O médico vai passar às três horas da tarde.", "Pode repetir, por favor?", "Можете повторить, пожалуйста?",
        Arrays.asList("O médico vai passar às três horas da tarde.")),
        "кусок другой реплики «o médico vai passar às três horas da tarde», ответ в 2.5 раза длиннее реплики", "K4 строка фона без перевода — отброшен");
    // память разговора тоже фон
    eq(Brief.swallowed("Кто говорит: по-португальски — женщина. У меня болит голова со вчерашнего дня.", "Estou com dor de cabeça desde ontem.",
        "У меня болит голова со вчерашнего дня.", Arrays.asList("Кто говорит: по-португальски — женщина, по-русски — мужчина.")),
        "кусок другой реплики «кто говорит по португальски женщина», ответ в 2.1 раза длиннее реплики", "K5 строка памяти в ответе — отброшен");

    // границы куска: реплика 40, прибавка — больше 1,5 × 40 + 10 = 70 знаков; кусок — от 20 знаков и от трёх слов
    String c27 = "aaaaaa bbbbbb cccccc dddddd", c20 = "aaaaaa bbbbbb cccccc", c19 = "aaaaaa bbbbbb ccccc", two = "aaaaaaaaaa bbbbbbbbbb";
    eq(Brief.swallowed(c27 + " " + rep('x', 42), s40, d30, Arrays.asList(c27)), null, "K6 70 знаков — прибавки ещё нет, кусок не в счёт");
    eq(Brief.swallowed(c27 + " " + rep('x', 43), s40, d30, Arrays.asList(c27)), "кусок другой реплики «" + c27 + "», ответ в 1.8 раза длиннее реплики", "K6 71 — отброшен");
    ok(Brief.swallowed(c20 + " " + rep('x', 50), s40, d30, Arrays.asList(c20)) != null, "K7 кусок ровно 20 знаков — отброшен");
    eq(Brief.swallowed(c19 + " " + rep('x', 51), s40, d30, Arrays.asList(c19)), null, "K7 19 знаков — совпадение, годится");
    eq(Brief.swallowed(two + " " + rep('x', 49), s40, d30, Arrays.asList(two)), null, "K8 два слова, хоть и 21 знак, — не кусок");

    // сам поиск куска
    eq(Brief.copied("x1 x2 x3 x4 x5 x6", Arrays.asList("x4 x5 x6"), Arrays.asList("x1 x2 x3 x4 x5 x6")), "x1 x2 x3", "C1 своё внутри чужого отрезка вырезается, остаток — чужой");
    eq(Brief.copied("aa bb cc dd", null, Arrays.asList("aa bb", "cc dd")), "", "C2 пары слов из разных текстов — не кусок");
    eq(Brief.copied("Всё, ХОРОШО: идём!", null, Arrays.asList("все хорошо идем")), "все хорошо идем", "C3 без регистра, знаков и разницы «ё» — «е»");
    eq(Brief.copied("a b a b c", null, Arrays.asList("a b c")), "a b c", "C4 повтор слов: отрезок находится там, где он целиком");
    eq(Brief.copied("p1 p2 p3 zz q1 q2 q3", null, Arrays.asList("p1 p2 p3", "q1 q2 q3")), "p1 p2 p3", "C5 из равных кусков — первый");
    eq(Brief.copied("p1 p2 p3 zz q1 q2 q3 q4", null, Arrays.asList("p1 p2 p3", "q1 q2 q3 q4")), "q1 q2 q3 q4", "C5 из разных — длинный");
    eq(Brief.copied("ola\u0301 mundo bonito", null, Arrays.asList("Olá mundo bonito")), "olá mundo bonito", "C6 разложенное ударение — как составное");
    eq(Brief.copied("aa bb cc", Arrays.asList("aa bb cc"), Arrays.asList("aa bb cc")), "", "C7 всё своё — ничего");
    eq(Arrays.asList(Brief.words("Olá,  MUNDO!! Ёлка 12-B")), Arrays.asList("olá", "mundo", "елка", "12", "b"), "C8 слова для сравнения");
    eq(Brief.words(null).length, 0, "C8 null — нет слов");
    eq(Brief.words(" ,, — ").length, 0, "C8 одни знаки — нет слов");

    return fails;
  }

  public static void main(String[] x) {
    BriefTest t = new BriefTest(); int f = t.run();
    System.out.println(f == 0 ? "Brief: " + t.checks + " проверок, все прошли" : "Brief: провалов " + f + " из " + t.checks);
    System.exit(f == 0 ? 0 : 1);
  }
}
