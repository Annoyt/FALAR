package dev.agenttranslator;

import java.util.List;
import java.util.Objects;

/** Португальские числа словами (Numbers): числительные, род, «e» между группами, деньги, время, проценты, десятичные;
 *  куски для транскрипции. Запуск: bash bench/apk/test.sh. */
public class NumbersTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": «" + a + "» ≠ «" + b + "»"); }

  public static int run() {
    fails = 0; checks = 0;
    // N1: числительные
    String[][] c = {{"0", "zero"}, {"1", "um"}, {"2", "dois"}, {"16", "dezesseis"}, {"21", "vinte e um"}, {"100", "cem"}, {"101", "cento e um"},
        {"200", "duzentos"}, {"999", "novecentos e noventa e nove"}, {"1000", "mil"}, {"1001", "mil e um"}, {"1200", "mil e duzentos"},
        {"1250", "mil duzentos e cinquenta"}, {"2000", "dois mil"}, {"2100", "dois mil e cem"}, {"10000", "dez mil"}, {"1000000", "um milhão"},
        {"1200000", "um milhão e duzentos mil"}, {"1250000", "um milhão duzentos e cinquenta mil"}, {"2000001", "dois milhões e um"},
        {"1250500", "um milhão duzentos e cinquenta mil e quinhentos"}};
    for (String[] x : c) eq(Numbers.cardinal(Long.parseLong(x[0]), false), x[1], "N1 " + x[0]);
    // N2: женский род
    eq(Numbers.cardinal(1, true), "uma", "N2 1"); eq(Numbers.cardinal(2, true), "duas", "N2 2"); eq(Numbers.cardinal(21, true), "vinte e uma", "N2 21");
    eq(Numbers.cardinal(200, true), "duzentas", "N2 200"); eq(Numbers.cardinal(1200, true), "mil e duzentas", "N2 1200"); eq(Numbers.cardinal(100, true), "cem", "N2 100");
    // N3: деньги
    eq(Numbers.ptWords("Custa 20 reais."), "Custa vinte reais.", "N3 reais словом");
    eq(Numbers.ptWords("R$ 20,50"), "vinte reais e cinquenta centavos", "N3 R$ с центами");
    eq(Numbers.ptWords("R$ 1,00"), "um real", "N3 um real");
    eq(Numbers.ptWords("R$1.250,00"), "mil duzentos e cinquenta reais", "N3 тысячи и ,00");
    eq(Numbers.ptWords("R$ 0,50"), "cinquenta centavos", "N3 только центы");
    eq(Numbers.ptWords("1 real e 50 centavos"), "um real e cinquenta centavos", "N3 словами");
    eq(Numbers.ptWords("3 reais"), "três reais", "N3 três reais");
    // N4: время
    eq(Numbers.ptWords("São 8:30."), "São oito e trinta.", "N4 8:30");
    eq(Numbers.ptWords("às 13:00"), "às treze horas", "N4 13:00");
    eq(Numbers.ptWords("à 1:05"), "à uma e cinco", "N4 1:05");
    eq(Numbers.ptWords("às 2:00"), "às duas horas", "N4 2:00");
    eq(Numbers.ptWords("às 8h"), "às oito horas", "N4 8h");
    eq(Numbers.ptWords("às 1h"), "às uma hora", "N4 1h");
    // N5: род по следующему слову
    eq(Numbers.ptWords("2 pessoas"), "duas pessoas", "N5 pessoas");
    eq(Numbers.ptWords("2 cervejas, por favor"), "duas cervejas, por favor", "N5 cervejas");
    eq(Numbers.ptWords("2 dias"), "dois dias", "N5 dias — мужской");
    eq(Numbers.ptWords("2 problemas"), "dois problemas", "N5 -ma — мужской");
    eq(Numbers.ptWords("21 horas"), "vinte e uma horas", "N5 21 horas");
    eq(Numbers.ptWords("100 metros"), "cem metros", "N5 100 metros");
    eq(Numbers.ptWords("Tenho 2."), "Tenho dois.", "N5 без слова — мужской");
    // N6: десятичные, проценты, индекс, длинные ряды
    eq(Numbers.ptWords("2,5 km"), "dois vírgula cinco km", "N6 2,5");
    eq(Numbers.ptWords("2,05"), "dois vírgula zero cinco", "N6 2,05");
    eq(Numbers.ptWords("10%"), "dez por cento", "N6 10%");
    eq(Numbers.ptWords("12,5 %"), "doze vírgula cinco por cento", "N6 12,5 %");
    eq(Numbers.ptWords("CEP 01310-100"), "CEP zero um três um zero um zero zero", "N6 индекс по цифрам");
    eq(Numbers.ptWords("Tel. 11987654321"), "Tel. um um nove oito sete seis cinco quatro três dois um", "N6 длинный ряд — по цифрам");
    eq(Numbers.ptWords("1.250 pessoas"), "mil duzentas e cinquenta pessoas", "N6 тысячи женского рода");
    // N7: не трогать
    eq(Numbers.ptWords("Sem números aqui."), "Sem números aqui.", "N7 без цифр");
    eq(Numbers.ptWords("A1 e B2"), "A1 e B2", "N7 буква вплотную — не число");
    eq(Numbers.ptWords(null), null, "N7 null");
    eq(Numbers.ptWords("20."), "vinte.", "N7 точка после числа — не десятичная");
    // N8: куски для транскрипции
    List<String[]> t = Numbers.tokens("Custa R$ 20,50, tá?");
    ok(t.size() == 3 && t.get(0)[0].equals("Custa") && t.get(1)[0].equals("R$ 20,50,") && t.get(1)[1].equals("vinte reais e cinquenta centavos") && t.get(2)[0].equals("tá?") && t.get(2)[1].equals("tá?"),
        "N8 деньги одним куском, знаки при куске: " + show(t));
    t = Numbers.tokens("Às 8:30 em ponto.");
    ok(t.size() == 4 && t.get(1)[0].equals("8:30") && t.get(1)[1].equals("oito e trinta") && t.get(3)[0].equals("ponto."), "N8 время: " + show(t));
    t = Numbers.tokens("Bom dia!");
    ok(t.size() == 2 && t.get(0)[0].equals("Bom") && t.get(1)[0].equals("dia!") && t.get(1)[1].equals("dia!"), "N8 без чисел — по слову: " + show(t));
    t = Numbers.tokens("2 pessoas");
    ok(t.size() == 2 && t.get(0)[1].equals("duas"), "N8 род в куске: " + show(t));
    System.out.println(fails == 0 ? "NumbersTest: " + checks + " проверок, все прошли" : "NumbersTest: провалов " + fails + " из " + checks);
    return fails;
  }
  static String show(List<String[]> t) { StringBuilder b = new StringBuilder(); for (String[] x : t) b.append('[').append(x[0]).append('|').append(x[1]).append(']'); return b.toString(); }

  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
