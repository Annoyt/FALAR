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
    ok(b.slots.size() == 1 && b.slots.get(0)[1].equals("Rua do Triumpho") && TextRules.unmask(b.text, b, "ru").equals("улица Триумфу"),
        "N2 вывеска: адрес в слоте целиком, родовое слово переводит подстановка, «ph» — «ф»: " + TextRules.unmask(b.text, b, "ru"));
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
    ok(TextRules.unmask(addr.text, addr, "ru").equals("Монти-Алту (Сан-Паулу) e Сан-Паулу"), "A4 город с кодом штата: " + TextRules.unmask(addr.text, addr, "ru"));
    ok(TextRules.mask("SP /CNPJ: 62", "pt", new ArrayList<>(), false).text.contains("SP /CNPJ"), "A5 речь: сокращения не трогаются");
    ok(TextRules.hasWords("WhatsApp: XQ1") && !TextRules.hasWords("L:XQ1 XQ2") && !TextRules.hasWords("XQ1 - XQ2"), "A6 что переводить: слово от трёх букв помимо плейсхолдеров");
    TextRules.Masked un = TextRules.mask("1 UN 5,49 5,49", "pt", new ArrayList<>(), true);
    TextRules.Masked kg = TextRules.mask("0,850 KG 7,99 6,79", "pt", new ArrayList<>(), true);
    ok(!TextRules.hasWords(un.text) && !TextRules.hasWords(kg.text) && TextRules.hasWords(TextRules.mask("ARROZ TIPO 1 5kg", "pt", new ArrayList<>(), true).text),
        "A6 строка количества и цен чека — единица при числах не слово, переводчику не отдаётся («1 ООН», «Модель:»): " + un.text + " · " + kg.text);
    TextRules.Masked ie = TextRules.mask("CNPJ: 39.508.023/0002-28 IE: 85.743.031", "pt", new ArrayList<>(), true);
    ok(!TextRules.hasWords(ie.text) && TextRules.unmask(ie.text, ie, "ru").startsWith("ИНН: 39.508.023/0002-28 IE:"), "A6 «IE:» без переводчика — не «Модель:»: " + TextRules.unmask(ie.text, ie, "ru"));
    TextRules.Masked cep2 = TextRules.mask("CEP 01035-100 · SAC 0800 770 3480", "pt", new ArrayList<>(), true);
    ok(TextRules.unmask(cep2.text, cep2, "ru").equals("индекс 01035-100 · служба поддержки 0800 770 3480"), "A7 CEP и SAC: " + TextRules.unmask(cep2.text, cep2, "ru"));

    // Адрес этикетки: переводчик выдумывал «Руа Аугуста Коста, 1.001 - Гора Альто»
    TextRules.Masked ad = TextRules.mask("ICPA CEPÊRA LTDA. Av. Lindolpho Augusto da Costa, 1.001 - Monte Alto-", "pt", new ArrayList<>(), true);
    eq(TextRules.unmask(ad.text, ad, "ru"), "ООО «ICPA CEPÊRA». проспект Линдолфу Аугусту да Коста, 1.001 - Монти-Алту,",
        "D1 адрес целиком своей подстановкой: проспект, имена кириллицей, город через дефис, штат на следующей строке — после запятой (" + ad.text + ")");
    ok(!TextRules.hasWords(ad.text), "D1 от строки не осталось слов — переводчику она не отдаётся: " + ad.text);
    TextRules.Masked ct = TextRules.mask("Fábrica em Monte Alto-SP e loja em São Paulo / SP", "pt", new ArrayList<>(), true);
    eq(TextRules.unmask(ct.text, ct, "ru"), "Fábrica em Монти-Алту (Сан-Паулу) e loja em Сан-Паулу", "D2 город со штатом — штат в скобках, одноимённый — один раз");
    TextRules.Masked rj = TextRules.mask("Rio de Janeiro/RJ · Brasília - DF · São José dos Campos-SP", "pt", new ArrayList<>(), true);
    eq(TextRules.unmask(rj.text, rj, "ru"), "Рио-де-Жанейро · Бразилиа (Федеральный округ) · Сан-Жозе-дус-Кампус (Сан-Паулу)",
        "D5 устоявшееся название города; «m» перед «p» — «м»");
    TextRules.Masked sg = TextRules.mask("Aberto de Segunda-", "pt", new ArrayList<>(), true);
    ok(sg.slots.isEmpty(), "D3 «Segunda-» в конце строки без «- » или «, » перед ним — не город");
    TextRules.Masked sp3 = TextRules.mask("Onde fica a Av. Paulista?", "pt", new ArrayList<>(), false);
    ok(sp3.slots.size() == 1 && sp3.slots.get(0)[1].equals("Paulista") && sp3.text.startsWith("Onde fica a Av. XQ1"), "D4 речь: родовое слово видно переводчику, как раньше: " + sp3.text);

    // Название фирмы: переводчик писал «ICPA CEPÊRA» то «Икпа Сепера», то «Икпа Цепера» — после
    // unshoutSign он видел «Icpa Cepêra» и читал как слова
    Set<String> common2 = new HashSet<>(Arrays.asList("produzido", "por", "fabricado", "distribuído", "e", "indústria", "brasileira", "alimentos"));
    java.util.function.Predicate<String> c2 = common2::contains;
    eq(TextRules.unshoutSign("ICPA CEPÊRA LTDA. Av. Lindolpho", c2), "ICPA CEPÊRA LTDA. Av. Lindolpho", "F1 название фирмы — как на этикетке");
    eq(TextRules.unshoutSign("PRODUZIDO POR: ICPA CEPÊRA LTDA.", c2), "Produzido por: ICPA CEPÊRA LTDA.", "F2 вокруг фирмы — как раньше");
    String caps = TextRules.unshoutSign("FABRICADO E DISTRIBUÍDO POR CEPÊRA ALIMENTOS LTDA", c2);
    eq(caps, "Fabricado e distribuído por CEPÊRA ALIMENTOS LTDA", "F3 «POR» — не часть названия");
    TextRules.Masked fc = TextRules.mask(caps, "pt", new ArrayList<>(), true);
    eq(TextRules.unmask(fc.text, fc, "ru"), "Fabricado e distribuído por ООО «CEPÊRA ALIMENTOS»", "F3 фирма — подстановкой: " + fc.text);
    TextRules.Masked fn = TextRules.mask("Fabricado por Nestlé Brasil Ltda. e 3M do Brasil Ltda", "pt", new ArrayList<>(), true);
    eq(TextRules.unmask(fn.text, fn, "ru"), "Fabricado por ООО «Nestlé Brasil». e ООО «3M do Brasil»", "F4 имя с ударением, цифрой и связкой: " + fn.text);
    TextRules.Masked fs = TextRules.mask("BRF S.A. · JBS S/A · Silva EIRELI", "pt", new ArrayList<>(), true);
    eq(TextRules.unmask(fs.text, fs, "ru"), "АО «BRF» · АО «JBS» · Silva EIRELI", "F5 S.A. и S/A — АО, EIRELI — как есть: " + fs.text);
    TextRules.Masked fl = TextRules.mask("LTDA. Av. Paulista, 1000", "pt", new ArrayList<>(), true);
    ok(TextRules.unmask(fl.text, fl, "ru").startsWith("ООО. проспект Паулиста"), "F6 LTDA без названия — сокращением, как раньше: " + TextRules.unmask(fl.text, fl, "ru"));
    ok(TextRules.mask("Fabricado por Nestlé Brasil Ltda.", "pt", new ArrayList<>(), false).slots.isEmpty(), "F7 речь: фирмы не маскируются");
    ok(TextRules.mask("PIMENTA CARIBENHA · Tampa Dosadora", "pt", new ArrayList<>(), true).slots.isEmpty(), "F8 без формы собственности — не фирма");

    // Марка: переводчик писал её кириллицей по-разному или «переводил» («Сепера», «Ромманель»)
    Set<String> dict = new HashSet<>(Arrays.asList("molho", "de", "pimenta", "picante", "garoto", "com", "contato", "sauce"));
    java.util.function.Predicate<String> kn = dict::contains;
    Set<String> br = TextRules.brands(Arrays.asList("ICPA CEPÊRA LTDA. Av. Lindolpho", "0800-7703480 /dac@cepera.com.br", "www.cepera.com.br",
        "Contato: fulano@gmail.com", "Tabasco® Pepper Sauce", "Molho CEPÊRA"), kn);
    eq(new TreeSet<>(br), new TreeSet<>(Arrays.asList("cepera", "icpa", "tabasco")), "B1 марки снимка: сайт и почта (не gmail), ®, незнакомые слова названия фирмы");
    TextRules.Masked bm = TextRules.mask("Molho de pimenta CEPÊRA picante", "pt", new ArrayList<>(), true, br);
    ok(bm.slots.size() == 1 && bm.slots.get(0)[1].equals("CEPÊRA") && TextRules.unmask(bm.text, bm, "ru").equals("Molho de pimenta CEPÊRA picante"),
        "B2 марка в строке — как на снимке, переводчику не показывается: " + bm.text);
    eq(TextRules.unshoutSign("MOLHO CEPÊRA", w -> dict.contains(w), br), "Molho CEPÊRA", "B3 регистр марки не меняется");
    TextRules.Masked bw = TextRules.mask("SAC: sac@cepera.com.br · www.cepera.com.br · CEPÊRA.", "pt", new ArrayList<>(), true, br);
    ok(bw.text.contains("sac@cepera.com.br") && bw.text.contains("www.cepera.com.br") && !bw.text.contains("CEPÊRA"),
        "B2 в адресе сайта и почты марка не маскируется, в конце фразы перед точкой — да: " + bw.text);
    eq(TextRules.unmask(bw.text, bw, "ru"), "служба поддержки: sac@cepera.com.br · www.cepera.com.br · CEPÊRA.", "B2 и «sac» в адресе почты — не сокращение");
    eq(TextRules.oneWord("Rommanel"), "Rommanel", "B4 одно слово");
    eq(TextRules.oneWord("CEPÊRA 21°"), "CEPÊRA", "B4 одно слово и число");
    ok(TextRules.oneWord("Lava-jato") == null && TextRules.oneWord("Tampa Dosadora") == null && TextRules.oneWord("8") == null, "B4 два слова или ни одного — не одно");
    ok(TextRules.brandLike("Rommanel", kn) && TextRules.brandLike("CEPÊRA", kn), "B5 похоже на марку");
    ok(!TextRules.brandLike("Borracharia", kn) && !TextRules.brandLike("Plastificação", kn) && !TextRules.brandLike("Armarinho", kn)
        && !TextRules.brandLike("Dosadora", kn) && !TextRules.brandLike("Garoto", kn) && !TextRules.brandLike("Pet", kn) && !TextRules.brandLike("rommanel", kn),
        "B5 обычное слово вывески (суффикс), знакомое, короткое, строчное — не марка");
    // пары — ответы переводчика на телефоне (пачка mtbatch, 29.09.2026)
    ok(TextRules.transliterated("Rommanel", "Ромманель") && TextRules.transliterated("Cepêra", "Сепера") && TextRules.transliterated("Knorr", "Норр"),
        "B6 переводчик лишь переписал кириллицей");
    ok(!TextRules.transliterated("Bomboniere", "Бомбонье") && !TextRules.transliterated("Pancakes", "Блинчики") && !TextRules.transliterated("Lava-jato", "Автомойка")
        && !TextRules.transliterated("Autopeças", "Автозапчасти") && !TextRules.transliterated("Drogasil", "Дрожжевой"),
        "B6 настоящий перевод (или выдумка) — не транскрипция");

    System.out.println(fails == 0 ? "TextRules: " + checks + " проверок, все прошли" : "TextRules: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
