package dev.agenttranslator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Правка слов снимка (OcrWords): что чинится — ударения, пропущенная буква, сдвоенная гласная,
 *  слипшиеся слова, — и что остаётся как есть: имена, английский, слова с заменой буквы. Набор
 *  появился после этикетки соуса, где переводчик получил «vnagre», «camim», «aicionados» и
 *  выдал «камыш» и «смузи». Части: A — маленький словарь здесь же; B — настоящий словарь
 *  (data/ocr_words_pt.txt.gz); C — сверка с эталоном tools/ocr_words.py строка в строку
 *  (bench/ocr/runs/golden/words.tsv, собирается tools/ocr_words.py golden). Запуск: bash bench/apk/test.sh. */
public class OcrWordsTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": «" + a + "» ≠ «" + b + "»"); }

  /** Словарь из файла; .gz — сжатый, как в APK. */
  static OcrWords load(File f) throws IOException {
    try (InputStream in = f.getName().endsWith(".gz") ? new java.util.zip.GZIPInputStream(new FileInputStream(f)) : new FileInputStream(f)) { return OcrWords.load(in); }
  }
  static OcrWords of(String... lines) throws IOException {
    return OcrWords.load(new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
  }

  public static int run(File words, File golden) throws Exception {
    fails = 0; checks = 0;
    // --- A: маленький словарь; строки нарочно не по порядку — загрузка сортирует сама
    OcrWords w = of("vinagre 26", "água 2406", "carmim 2", "caim 25 N", "máximo 180", "dias 1987", "açúcares 4",
        "saturadas 2", "não 79890", "contém 175", "glúten 10", "feira 56", "das 4526", "estudantes 100", "carteira 50",
        "salima 3 N", "nomes 186", "cervo 23", "lobos 20", "baralho 30", "elo 20", "sesta 10", "paulo 320 N",
        "esta 5116", "está 9000", "referi 1", "santos 30", "adicionados 6", "tampa 42", "de 30000", "ingredientes 0");
    eq(w.fix("maximo", null), "máximo", "A1 ударение по частой форме");
    eq(w.fix("MAXIMO Maximo", null), "MÁXIMO Máximo", "A1 регистр сохраняется; «Maximo» с заглавной — частое слово, годится");
    eq(w.fix("esta", null), "esta", "A2 известная форма не трогается, хотя «está» чаще");
    eq(w.fix("vnagre camim", null), "vinagre carmim", "A3 пропущенная буква; «caim» — имя, и до него не пропуск, а лишняя буква");
    eq(w.fix("Áagua açúucares", null), "Água açúcares", "A4 сдвоенная гласная");
    eq(w.fix("CERVA Gomes nomez", null), "CERVA Gomes nomez", "A5 замены буквы нет («cervo», «nomes»)");
    eq(w.fix("LOMBOS baralhos", null), "LOMBOS baralhos", "A6 лишняя согласная не выкидывается: «lobos», «baralho» — другие слова");
    eq(w.fix("Salim", null), "Salim", "A7 слово с заглавной — скорее имя: редкое «salima» не подставляется");
    eq(w.fix("pauo PAUO", null), "pauo PAULO", "A8 имя корпуса не подставляется в строчное слово, в прописное — да");
    eq(w.fix("refri", null), "refri", "A9 слово, встреченное в корпусе однажды, в замену не идёт");
    eq(w.fix("nao dis", null), "não dis", "A10 ударения — с трёх букв, пропущенная буква — с четырёх");
    eq(w.fix("NÃOCONTÉMGLÚTEN.", null), "NÃO CONTÉM GLÚTEN.", "A11 прописные слова, слипшиеся без пробела");
    eq(w.fix("feiradas", null), "feira das", "A12 слипшееся со служебным словом");
    eq(w.fix("EstudantesCarteira", null), "Estudantes Carteira", "A13 смена регистра внутри слова");
    eq(w.fix("ELOSESTA", null), "ELOSESTA", "A14 части редкие (elo·sesta = 200 < 30²) — не разбивается");
    eq(w.fix("Feiradas", null), "Feiradas", "A15 слово с заглавной разбивается только по смене регистра");
    eq(w.fix("CONTÉMGLÚTEN NAOCONTEM", null), "CONTÉM GLÚTEN NÃO CONTÉM", "A21 две части (175·10 ≥ 30²); ударения частей — по словарю");
    OcrWords e = of("lince 30", "line 0 E", "the 22 E", "herde 5", "linha 400");
    ok(e.fix("line here the", null).equals("line herde the") && Math.abs(e.share("the line linha") - 1 / 3.0) < 1e-9 && !e.common("the"),
        "A22 английское служебное слово известно (не правится в «lince»), но в долю португальских не идёт");
    eq(w.fix("Ingredientes: Áagua, vnagre (5°C) — 30 dias", null), "Ingredientes: Água, vinagre (5°C) — 30 dias", "A16 знаки и числа вокруг — как были");
    ok(Math.abs(w.share("vinagre água xyzzy") - 2 / 3.0) < 1e-9 && w.share("a de") == -2 && w.share("Valongo Train Station") == 0,
        "A17 доля известных слов; меньше двух слов — не судим");
    List<String[]> ch = new ArrayList<>(); w.fix("vnagre e tampa, Áagua", ch);
    ok(ch.size() == 2 && ch.get(0)[0].equals("vnagre") && ch.get(0)[1].equals("vinagre") && ch.get(1)[1].equals("Água"), "A18 список правок «было → стало»");
    ok(w.common("VINAGRE") && !w.common("PAULO") && !w.common("xyz") && !w.common("ingredientes"),
        "A19 обычное слово — известное, не имя и не только из словаря переводчика");
    ok(w.fix("saturadas satuadas", null).equals("saturadas saturadas") && w.fix("aicionados", null).equals("adicionados"), "A20 пропуск буквы в середине слова");

    // --- границы порогов на маленьких словарях (мутационное тестирование нашло их непроверенными)
    OcrWords s = of("está 9000", "esta 5116", "saúde 3", "maçã 2", "pé 630", "grato 20", "gato 20", "manto 10", "matos 10",
        "vinagre 26", "cozinha 40", "na 5000", "casa 30", "velha 30", "cava 29", "leira 29", "máximo 50", "água 2406", "linha 12 E");
    eq(s.fix("ésta", null), "está", "T1 из форм с теми же буквами берётся самая частая (строки вразнобой — пересортировка)");
    eq(of("está 9000", "esta 5116", "vinagre 26").fix("ésta esta", null), "está esta", "T1 строки уже по порядку — без пересортировки");
    eq(of("esta 5116", "está 9000").fix("ésta", null), "está", "T1 одна форма без ударений, частоты вразнобой — пересортировка");
    eq(s.fix("saude maca pe", null), "saúde maca pe", "T2 ударения: частота 3 — да, 2 — нет; слово из двух букв не правится");
    eq(s.fix("Maximo", null), "Máximo", "T3 слово с заглавной: частота ровно 50 — годится");
    eq(of("máximo 49").fix("Maximo", null), "Maximo", "T3 49 — уже нет");
    eq(s.fix("gto", null), "gto", "T4 пропущенную букву у слова из трёх букв не ищем");
    eq(of("grato 20").fix("gato", null), "grato", "T4 у слова из четырёх — ищем");
    eq(s.fix("mato", null), "manto", "T5 два кандидата одной частоты — первый по алфавиту («manto» < «matos»)");
    eq(of("manto 5", "matos 10").fix("mato", null), "matos", "T5 разной частоты — более частый");
    eq(s.fix("vinnagre viinagre", null), "vinnagre vinagre", "T6 сдвоенная согласная не выкидывается, гласная — да");
    eq(s.fix("nacozinha cozinhana", null), "na cozinha cozinha na", "T7 служебное слово из двух букв — и первой частью, и последней");
    eq(s.fix("CASAVELHA CAVALEIRA", null), "CASA VELHA CAVALEIRA", "T8 части 30·30 = 30² — разбивается, 29·29 — нет");
    eq(of("casa 30", "velha 9").fix("casavelha", null), "casavelha", "T8 часть реже 10 раз — не часть");
    eq(of("linha 30", "casa 30", "line 100 E").fix("linecasa", null), "linecasa", "T8 английское слово — не часть разбивки");
    ok(s.common("AGUA") && !s.common("LINHA") && s.share("vinagre xyz") == 0.5 && s.share("vinagre") == -2 && s.share("vinagre ab") == -2,
        "T9 обычное слово — и без ударений; доля — от двух слов из трёх букв и больше");
    eq(of("smato 10", "matoa 5").fix("mato", null), "smato", "T10 более частый кандидат, найденный первым, не вытесняется редким, хоть тот и раньше по алфавиту");
    eq(of("there 100 E", "fazer 40").fix("thre faer", null), "thre fazer", "T11 английское слово в замену не идёт; буква «z» тоже вставляется");
    eq(of("vinagre 26", "", "sem-частоты", "casa x", "água 2406").fix("vnagre agua", null), "vinagre água", "T12 битые строки словаря пропускаются");
    eq(of("casa 30", "da 3000", "costa 40", "dacosta 20").fix("CASADACOSTA", null), "CASA DA COSTA", "T13 три части вероятнее двух (30·3000·40/150 > 30·20)");
    eq(of("casa 30", "da 3000", "costa 40", "dacosta 2000").fix("CASADACOSTA", null), "CASA DACOSTA", "T13 две части вероятнее трёх (30·2000 > 24 000)");

    // --- B: настоящий словарь
    if (words != null && words.exists()) {
      OcrWords r;
      r = load(words);
      ok(r.size() > 70000, "B0 словарь загружен: " + r.size() + " форм");
      eq(r.fix("Ingredientes: Áagua, pimenta, vnagre, espessante goma xantana e corante natural camim.", null),
          "Ingredientes: Água, pimenta, vinagre, espessante goma xantana e corante natural carmim.",
          "B1 состав: пропущенные буквы вернулись, редкие настоящие слова («xantana», «espessante») не тронуты");
      eq(r.fix("açúucares totais, açúucares aicionados, gorduras satuadas", null), "açúcares totais, açúcares adicionados, gorduras saturadas", "B2 таблица пищевой ценности");
      eq(r.fix("NÃOCONTÉMGLÚTEN.", null), "NÃO CONTÉM GLÚTEN.", "B3 слипшаяся строка прописными");
      eq(r.fix("conservar em geladera e consumir no maximo em 30 días", null), "conservar em geladeira e consumir no máximo em 30 dias", "B4 ударения и пропуск");
      for (String keep : new String[]{"CERVA", "Valongo Train Station", "Salim Feres Sobrinho", "CARROS DE BOI E LOMBOS DE BURRO.",
          "Orestes Quércia", "Nilton Gomes Monteiro", "Canetas baralhos carimbos", "CARLINDO", "REFRI", "SOS"})
        eq(r.fix(keep, null), keep, "B5 как на вывеске (имена, английский, сокращения)");
      eq(r.fix("INFORMACAO NUTRICIONAL · VOCE · NAO ESTACIONE", null), "INFORMAÇÃO NUTRICIONAL · VOCÊ · NÃO ESTACIONE",
          "B7 ударения у частых слов: опечатка корпуса «nao» (1 раз против 79 932) словом не считается");
      ok(r.share("the port, the gateway to Brazil, most of the people passed through here") < OcrWords.LANG_MIN
          && r.share("Ingredientes: Áagua, pimenta abanera vnagre, espessante goma xantana e corante natural camim.") >= OcrWords.LANG_MIN,
          "B6 английский абзац — ниже порога языка, искажённый состав — выше");
    } else System.out.println("  (часть B пропущена: нет data/ocr_words_pt.txt.gz — .venv/bin/python tools/ocr_words.py build)");

    // --- C: сверка с эталоном на столе
    File g = golden == null ? null : new File(golden, "words.tsv");
    if (g != null && g.exists() && words != null && words.exists()) {
      OcrWords r;
      r = load(words);
      int n = 0, bad = 0;
      for (String line : Files.readAllLines(g.toPath(), StandardCharsets.UTF_8)) {
        String[] p = line.split("\t", -1); if (p.length < 3) continue; n++;
        String got = r.fix(p[0], null); double sh = r.share(p[0]);
        if (!got.equals(p[1]) || Math.abs(sh - Double.parseDouble(p[2])) > 1e-6) {
          if (bad++ < 5) System.out.println("    «" + p[0] + "»: " + got + " / " + sh + " — эталон: " + p[1] + " / " + p[2]);
        }
      }
      ok(n > 500 && bad == 0, "C1 правка и доля слов совпали с tools/ocr_words.py: " + (n - bad) + " из " + n + " строк");
    } else System.out.println("  (часть C пропущена: нет bench/ocr/runs/golden/words.tsv — .venv/bin/python tools/ocr_words.py golden)");

    // --- D: марка (TextRules.brandLike и transliterated) на словах с ответами переводчика с телефона
    File bw = words == null ? null : new File(words.getAbsoluteFile().getParentFile().getParentFile(), "bench/ocr/brand_words.tsv");
    if (bw != null && bw.exists() && words.exists()) {
      OcrWords r = load(words); Map<String, int[]> n = new TreeMap<>(); List<String> kept = new ArrayList<>();
      for (String line : Files.readAllLines(bw.toPath(), StandardCharsets.UTF_8)) {
        String[] p = line.split("\t"); if (line.startsWith("#") || p.length < 3) continue;
        boolean keep = TextRules.brandLike(p[1], r::known) && TextRules.transliterated(p[1], p[2]);
        n.computeIfAbsent(p[0], k -> new int[2])[keep ? 0 : 1]++;
        if (keep && p[0].equals("shop")) kept.add(p[1]);
      }
      ok(n.containsKey("brand") && n.get("brand")[0] == 27 && n.get("brand")[1] == 6, "D1 марки: 27 из 33 — как на снимке");
      ok(kept.equals(Arrays.asList("Hortifruti", "Habanero", "Ardidômetro", "Petshop", "Marmitex")),
          "D2 из слов вывесок как на снимке — только те, что переводчик лишь переписал кириллицей: " + kept);
    } else System.out.println("  (часть D пропущена: нет bench/ocr/brand_words.tsv)");

    System.out.println(fails == 0 ? "OcrWords: " + checks + " проверок, все прошли" : "OcrWords: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception {
    System.exit(run(a.length > 0 ? new File(a[0]) : null, a.length > 1 ? new File(a[1]) : null) == 0 ? 0 : 1);
  }
}
