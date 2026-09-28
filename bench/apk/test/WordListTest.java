package dev.agenttranslator;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Свои слова: искажённое распознаванием имя находится и прячется от перевода, а обычное слово
 *  языка именем не подменяется. Ошибка, из-за которой набор появился: список обычных слов
 *  построен с диакритикой, а проверка искала слово без неё, — и «sábado» подменялось именем
 *  «São Pedro» из своих слов (суббота пропадала из перевода, в конце появлялось «Сау-Педру»).
 *  Обычные слова — настоящий список из data/common_words.txt. Запуск: bash bench/apk/test.sh. */
public class WordListTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": «" + a + "» ≠ «" + b + "»"); }

  static String masked(WordList w, String text, String src, String tgt) {
    return w.apply(text, src, tgt, new ArrayList<>(), new ArrayList<>()).masked;
  }

  public static int run(String commonWords) throws Exception {
    fails = 0; checks = 0;
    File d = Files.createTempDirectory("words").toFile(); d.deleteOnExit();
    Files.copy(Paths.get(commonWords), new File(d, "common_words.txt").toPath());
    WordList w = new WordList(d);
    ok(w.commonCount > 40000, "W0 список обычных слов загружен: " + w.commonCount);
    w.add("São Pedro", "Сау-Педру"); w.add("Copacabana Palace", "Копакабана Палас"); w.add("Rua Augusta", "Руа Аугуста");

    // обычные слова с диакритикой — обычные
    ok(w.isCommon("sábado", "pt"), "W1 «sábado» — обычное слово");
    ok(w.isCommon("você", "pt"), "W1 «você» — обычное слово");
    ok(w.isCommon("também", "pt"), "W1 «também» — обычное слово");
    ok(w.isCommon("sabado", "pt"), "W1 без диакритики тоже находится");
    ok(w.isCommon("Sábado,", "pt"), "W1 заглавная и знак препинания не мешают");
    ok(w.isCommon("sábados", "pt"), "W1 форма слова — по основе");
    ok(!w.isCommon("Capocapana", "pt"), "W1 искажённое имя — не обычное слово");

    // суббота остаётся субботой
    List<String[]> slots = new ArrayList<>(); List<WordList.Hit> hits = new ArrayList<>();
    WordList.Result r = w.apply("Vamos no sábado de manhã", "pt", "ru", slots, hits);
    eq(r.masked, "Vamos no sábado de manhã", "W2 «sábado» не подменяется именем");
    ok(hits.isEmpty() && slots.isEmpty(), "W2 и в слоты ничего не ушло");
    eq(masked(w, "No sábado a gente vai para São Pedro", "pt", "ru").contains("sábado"), true, "W2 рядом с настоящим «São Pedro» суббота тоже цела");
    eq(masked(w, "Vamos no sabado de manhã", "pt", "ru"), "Vamos no sabado de manhã", "W2 и без ударения суббота цела");

    // само имя — по-прежнему находится
    slots.clear(); hits.clear();
    r = w.apply("Amanhã vou para São Pedro", "pt", "ru", slots, hits);
    ok(!r.masked.contains("São Pedro") && slots.size() == 1, "W3 точное имя спрятано от перевода: " + r.masked);
    eq(slots.isEmpty() ? null : slots.get(0)[2], "name:Сау-Педру", "W3 и переведётся как в своих словах");
    eq(r.readable, "Amanhã vou para São Pedro", "W3 в читаемом виде имя на месте");
    ok(!masked(w, "Amanhã vou para Sao Pedro", "pt", "ru").contains("Pedro"), "W4 имя без диакритики — тоже находится");
    ok(!masked(w, "Fica perto do Capocapana Palace", "pt", "ru").contains("Palace"), "W5 искажённое распознаванием имя находится");
    eq(masked(w, "Você também vai?", "pt", "ru"), "Você também vai?", "W6 обычная фраза не трогается");
    // прежние ложные подмены, ради которых проверка обычных слов появилась
    w.add("feijoada", "фейжоада");
    eq(masked(w, "A loja está fechada", "pt", "ru"), "A loja está fechada", "W7 «fechada» не становится блюдом");
    eq(masked(w, "Приезжайте пятого августа", "ru", "pt"), "Приезжайте пятого августа", "W7 «пятого августа» не становится улицей");
    ok(!masked(w, "Quero uma feijoada", "pt", "ru").contains("feijoada"), "W7 а само блюдо находится");

    System.out.println(fails == 0 ? "WordList: " + checks + " проверок, все прошли" : "WordList: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run(a.length > 0 ? a[0] : "data/common_words.txt") == 0 ? 0 : 1); }
}
