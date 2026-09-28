package dev.agenttranslator;

import java.util.*;

/** Правила текста для вывески: регистр прописного текста и название улицы только с заглавной.
 *  «RODOVIA ESTREITA E EXTREMAMENTE SINUOSA» на телефоне переводилось как «Естрейта и чрезвычайно
 *  извилистая дорога»: прилагательное маскировалось как название дороги. Запуск: bash bench/apk/test.sh. */
public class TextRulesTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": «" + a + "» ≠ «" + b + "»"); }

  public static int run() {
    fails = 0; checks = 0;
    Set<String> common = new HashSet<>(Arrays.asList("rodovia", "estreita", "extremamente", "sinuosa", "rua", "flores", "parque", "nacional", "proibido", "fumar", "saída"));
    java.util.function.Predicate<String> c = common::contains;
    eq(TextRules.unshoutSign("RODOVIA ESTREITA E EXTREMAMENTE SINUOSA", c), "Rodovia estreita e extremamente sinuosa", "S1 обычные слова вывески — строчными, первое — с заглавной, «E» — строчная");
    eq(TextRules.unshoutSign("RUA DO TRIUMPHO", c), "Rua do Triumpho", "S2 необычное слово — с заглавной, «DO» — строчными");
    eq(TextRules.unshoutSign("PARQUE NACIONAL DE BRASÍLIA", c), "Parque nacional de Brasília", "S3 имя с диакритикой — с заглавной");
    eq(TextRules.unshoutSign("CEP 01035-100", c), "CEP 01035-100", "S4 короткая аббревиатура не трогается");
    eq(TextRules.unshoutSign("SAÍDA RUA BR", c), "Saída rua BR", "S4 обычное трёхбуквенное — строчными, аббревиатура — как есть");
    eq(TextRules.unshoutSign("Horário de Funcionamento", c), "Horário de Funcionamento", "S5 текст не прописными не трогается");
    eq(TextRules.unshoutSign("'PROIBIDO FUMAR!", c), "'Proibido fumar!", "S6 знаки вокруг слова сохраняются");
    eq(TextRules.unshoutSign("12 PROIBIDO", c), "12 Proibido", "S7 первое слово — первое со буквами, число не в счёт");

    TextRules.Masked a = TextRules.mask("Rodovia estreita e extremamente sinuosa", "pt", new ArrayList<>(), true);
    ok(a.slots.isEmpty() && a.text.contains("estreita"), "N1 вывеска: «Rodovia estreita» — не название дороги: " + a.text);
    TextRules.Masked b = TextRules.mask("Rua do Triumpho", "pt", new ArrayList<>(), true);
    ok(b.slots.size() == 1 && b.slots.get(0)[1].equals("do Triumpho"), "N2 вывеска: «Rua do Triumpho» — название маскируется вместе со связкой");
    TextRules.Masked d = TextRules.mask("Onde fica a rua das flores?", "pt", new ArrayList<>(), false);
    ok(d.slots.size() == 1 && d.slots.get(0)[1].equals("das flores"), "N3 речь: «rua das flores» строчными маскируется, как раньше");
    TextRules.Masked e = TextRules.mask("Onde fica a rua das flores?", "pt", new ArrayList<>(), true);
    ok(e.slots.isEmpty(), "N4 вывеска: строчное «flores» — не название");
    TextRules.Masked f = TextRules.mask("Rua sem saída", "pt", new ArrayList<>(), false);
    ok(f.slots.isEmpty(), "N5 «Rua sem saída» — не адрес и в речи (стоп-слово «sem»)");
    ok(TextRules.properName("das Flores") && !TextRules.properName("das flores") && !TextRules.properName("do"), "N6 заглавная — у первого слова после «das/do»");

    System.out.println(fails == 0 ? "TextRules: " + checks + " проверок, все прошли" : "TextRules: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
