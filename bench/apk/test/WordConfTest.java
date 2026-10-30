package dev.agenttranslator;

import java.util.*;

/** Сомнительные слова распознанного (WordConf): слова и уверенность — как tools/asr_confidence.words, поиск в
 *  тексте реплики, хранение в разговоре. Запуск: bash bench/apk/test.sh. */
public class WordConfTest {
  int fails = 0, checks = 0;
  void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static String[] tk(String... t) { return t; }
  static float[] pr(double... p) { float[] a = new float[p.length]; for (int k = 0; k < p.length; k++) a[k] = (float) p[k]; return a; }
  static List<String> texts(List<WordConf.Word> ws) { List<String> o = new ArrayList<>(); for (WordConf.Word w : ws) o.add(w.text); return o; }

  public int run() {
    // слова и уверенность: начало слова — токен с пробелом; знаки препинания в уверенность не идут
    List<WordConf.Word> w = WordConf.words(tk(" Bom", " di", "a", ",", " tudo", " bem", "?"), pr(0.9, 0.4, 0.95, 0.1, 0.8, 0.99, 0.2));
    eq(texts(w), Arrays.asList("Bom", "dia", "tudo", "bem"), "W1 слова без знаков по краям");
    eq(w.get(1).conf, (double) 0.4f, "W2 уверенность слова — наименьшая у его токенов");
    eq(w.get(3).conf, (double) 0.99f, "W3 знак препинания (0,2) в уверенность не идёт");
    eq(texts(WordConf.doubtful(tk(" Bom", " di", "a", ",", " tudo", " bem", "?"), pr(0.9, 0.4, 0.95, 0.1, 0.8, 0.99, 0.2), WordConf.DOUBT)),
        Arrays.asList("dia"), "W4 сомнительное — ниже 0,7");
    eq(texts(WordConf.words(tk("▁Oi", "▁gen", "te"), pr(0.9, 0.5, 0.6))), Arrays.asList("Oi", "gente"), "W5 начало слова и знаком ▁");
    eq(WordConf.words(tk(" Oi"), pr()).size(), 0, "W6 нет вероятностей (прежняя библиотека JNI) — нет и слов");
    eq(WordConf.words(tk(" Oi", " gente"), pr(0.9)).size(), 0, "W6 вероятностей не столько, сколько токенов — пусто");
    eq(WordConf.words(null, null).size(), 0, "W6 ничего не распознано");
    eq(texts(WordConf.words(tk(" guarda", "-", "chuva"), pr(0.9, 0.95, 0.5))), Arrays.asList("guarda-chuva"), "W7 дефис внутри слова — одно слово");
    eq(WordConf.words(tk(" guarda", "-", "chuva"), pr(0.9, 0.1, 0.8)).get(0).conf, (double) 0.8f, "W7 дефис — не буква: в уверенность не идёт");

    // где в тексте: регистр, границы слов, порядок, правка текста после распознавания
    List<WordConf.Word> d = Arrays.asList(new WordConf.Word("Bom", 0.9), new WordConf.Word("dia", 0.4));
    eq(Arrays.toString(WordConf.spans("Bom dia, tudo bem?", d, WordConf.DOUBT)), "[4, 7]", "S1 сомнительное «dia» — знаки 4–7");
    eq(Arrays.toString(WordConf.spans("bom DIA", d, WordConf.DOUBT)), "[4, 7]", "S2 без учёта регистра");
    List<WordConf.Word> nao = Arrays.asList(new WordConf.Word("não", 0.95), new WordConf.Word("não", 0.3), new WordConf.Word("sei", 0.9));
    eq(Arrays.toString(WordConf.spans("Não, não sei.", nao, WordConf.DOUBT)), "[5, 8]", "S3 сомнительное второе «não» — на втором, не на первом");
    List<WordConf.Word> di = Arrays.asList(new WordConf.Word("di", 0.3));
    eq(Arrays.toString(WordConf.spans("Bom dia", di, WordConf.DOUBT)), "[]", "S4 только по границам слов: «di» — не часть «dia»");
    List<WordConf.Word> num = Arrays.asList(new WordConf.Word("vinte", 0.4), new WordConf.Word("reais", 0.5));
    eq(Arrays.toString(WordConf.spans("R$ 20 reais", num, WordConf.DOUBT)), "[6, 11]", "S5 слова, которого нет (число цифрами), пропускается");
    eq(WordConf.spans("", d, WordConf.DOUBT).length, 0, "S6 пустой текст");
    eq(WordConf.spans("Bom dia", new ArrayList<>(), WordConf.DOUBT).length, 0, "S6 нет слов");
    List<WordConf.Word> ok2 = Arrays.asList(new WordConf.Word("Bom", 0.69), new WordConf.Word("dia", 0.7));
    eq(Arrays.toString(WordConf.spans("Bom dia", ok2, WordConf.DOUBT)), "[0, 3]", "S7 порог: 0,69 сомнительно, 0,7 — уже нет");
    List<WordConf.Word> tr = Arrays.asList(new WordConf.Word("i̇stanbul", 0.9), new WordConf.Word("dia", 0.3));
    eq(Arrays.toString(WordConf.spans("İstanbul dia", tr, WordConf.DOUBT)), "[9, 12]", "S8 буква, у которой строчная длиннее («İ»), не сдвигает отрезки");

    // хранение в разговоре
    eq(WordConf.pack(new int[]{4, 7, 12, 15}), "4-7,12-15", "P1 отрезки — в строку");
    eq(Arrays.toString(WordConf.unpack("4-7,12-15")), "[4, 7, 12, 15]", "P2 строка — в отрезки");
    eq(Arrays.toString(WordConf.unpack("")), "[]", "P3 пусто");
    eq(Arrays.toString(WordConf.unpack("4-7,x-9,9-3,,12-15")), "[4, 7, 12, 15]", "P4 испорченное пропускается");
    eq(Arrays.toString(WordConf.shift(new int[]{4, 7}, 2)), "[6, 9]", "P5 сдвиг на подпись «🎙 »");
    ok(WordConf.touches(new int[]{4, 7}, 6, 10) && !WordConf.touches(new int[]{4, 7}, 7, 10) && !WordConf.touches(new int[]{4, 7}, 0, 4),
        "P6 задевает ли кусок текста сомнительное слово");
    return fails;
  }

  public static void main(String[] x) {
    WordConfTest t = new WordConfTest(); int f = t.run();
    System.out.println(f == 0 ? "WordConf: " + t.checks + " проверок, все прошли" : "WordConf: провалов " + f + " из " + t.checks);
    System.exit(f == 0 ? 0 : 1);
  }
}
