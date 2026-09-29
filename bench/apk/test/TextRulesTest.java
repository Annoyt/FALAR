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

    // Маски вывески (этикетка соуса): переводчик видел «XQ3 XQ4» и писал «СК3 СК4», «°» не знал
    TextRules.Masked g = TextRules.mask("Após o uso, feche a 7 896025 804067 tampa. Tel. 3242-3300", "pt", new ArrayList<>(), true);
    ok(g.slots.size() == 2 && g.slots.get(0)[1].equals("7 896025 804067") && g.slots.get(1)[1].equals("3242-3300"),
        "M1 вывеска: цифры через пробел и дефис — одним куском: " + g.text);
    TextRules.Masked g2 = TextRules.mask("Tenho 1 2 3", "pt", new ArrayList<>(), false);
    ok(g2.slots.size() == 3, "M2 речь: цифры через пробел — разные числа, как раньше: " + g2.text);
    TextRules.Masked t = TextRules.mask("conservar em geladeira (5°C - 10 °C)", "pt", new ArrayList<>(), true);
    ok(t.text.equals("conservar em geladeira (XQ1 - XQ2)") && t.slots.get(0)[1].equals("5°C") && t.slots.get(1)[1].equals("10 °C"),
        "M3 температура — вместе со знаком градуса: " + t.text);
    TextRules.Masked no = TextRules.mask("Rua das Flores, Nº 120", "pt", new ArrayList<>(), true);
    ok(no.slots.size() == 2 && no.slots.get(1)[2].equals("no") && TextRules.unmask("Улица XQ1, XQ2", no, "ru").endsWith(", № 120"),
        "M4 номер дома: «Nº» переводчик читал как «Нет» — в переводе «№ 120»: " + no.text + " → " + TextRules.unmask("Улица XQ1, XQ2", no, "ru"));
    TextRules.Masked cep = TextRules.mask("CEP 01035-100", "pt", new ArrayList<>(), true);
    ok(cep.slots.size() == 2 && cep.slots.get(0)[2].equals("time") && cep.slots.get(0)[1].equals("01035-100") && cep.slots.get(1)[2].equals("abbr"),
        "M5 номер CEP — своей маской, как раньше; само слово CEP — сокращение (переводится «индекс»)");

    // Плейсхолдер, переписанный переводчиком кириллицей
    eq(TextRules.unmask("После использования закройте СК1 крышка.", g, "ru"), "После использования закройте 7 896025 804067 крышка. 3242-3300",
        "U1 «СК1» — это XQ1; потерянный XQ2 — в конец, как раньше");
    eq(TextRules.unmask("Телефон ХQ2, код XК1", g, "ru"), "Телефон 3242-3300, код 7 896025 804067", "U2 смешанные буквы («ХQ», «XК») — тоже плейсхолдер");
    eq(TextRules.unmask("Цена СК7 сегодня", TextRules.mask("hoje", "pt"), "ru"), "Цена сегодня", "U3 лишний «СК7» без слота вычищается, как «XQ7»");
    eq(TextRules.unmask("СК12 и СК1", TextRules.mask("custa 5", "pt", new ArrayList<>(), true), "ru"), "и 5",
        "U4 «СК12» не принимается за слот 1 (и вычищается как лишний)");

    // Сокращения этикеток: «SP /CNPJ: 62.162.243/0003-45» переводчик превращал в «Модель: …»
    TextRules.Masked sp = TextRules.mask("SP /CNPJ: 62.162.243/0003-45", "pt", new ArrayList<>(), true);
    ok(!TextRules.hasWords(sp.text) && TextRules.unmask(sp.text, sp, "ru").equals("Сан-Паулу /ИНН: 62.162.243/0003-45"),
        "A1 строка из сокращений и чисел — без слов, сокращения своим переводом: " + sp.text + " → " + TextRules.unmask(sp.text, sp, "ru"));
    TextRules.Masked sp2 = TextRules.mask("SP /Cnpj: 62.162.243/0003-45", "pt", new ArrayList<>(), true);
    ok(TextRules.unmask(sp2.text, sp2, "ru").equals("Сан-Паулу /ИНН: 62.162.243/0003-45"), "A1 и после unshoutSign («Cnpj»)");
    ok(sp.slots.size() == 3 && sp.slots.get(2)[1].equals("62.162.243/0003-45"), "A2 номер с точками тысяч и дробью — одним куском: " + sp.text);
    TextRules.Masked se = TextRules.mask("SE BEBER NÃO DIRIJA. MA SORTE", "pt", new ArrayList<>(), true);
    ok(se.slots.isEmpty(), "A3 «SE», «MA» без дефиса и дроби — слова, не коды штатов");
    TextRules.Masked addr = TextRules.mask("Monte Alto-SP e São Paulo/SP", "pt", new ArrayList<>(), true);
    ok(TextRules.unmask(addr.text, addr, "ru").equals("Monte Alto-Сан-Паулу e São Paulo/Сан-Паулу"), "A4 код штата в адресе: " + TextRules.unmask(addr.text, addr, "ru"));
    ok(TextRules.mask("SP /CNPJ: 62", "pt", new ArrayList<>(), false).text.contains("SP /CNPJ"), "A5 речь: сокращения не трогаются");
    ok(TextRules.hasWords("WhatsApp: XQ1") && !TextRules.hasWords("L:XQ1 XQ2") && !TextRules.hasWords("XQ1 - XQ2"), "A6 что переводить: слово от двух букв помимо плейсхолдеров");
    TextRules.Masked cep2 = TextRules.mask("CEP 01035-100 · SAC 0800 770 3480", "pt", new ArrayList<>(), true);
    ok(TextRules.unmask(cep2.text, cep2, "ru").equals("индекс 01035-100 · служба поддержки 0800 770 3480"), "A7 CEP и SAC: " + TextRules.unmask(cep2.text, cep2, "ru"));

    System.out.println(fails == 0 ? "TextRules: " + checks + " проверок, все прошли" : "TextRules: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
