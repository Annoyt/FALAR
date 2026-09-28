package dev.agenttranslator;

import java.util.*;

/** Память разговора: кто говорит — по грамматике исходников, ключевые детали — из ответа модели,
 *  всё вместе — в пределах потолка. Главное здесь — не ошибиться в сторону ложного признака:
 *  «você não é obrigada» — про собеседницу, «я на вокзал» — не глагол, перевод не считается.
 *  Запуск: bash bench/apk/test.sh. */
public class MemoTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": «" + a + "» ≠ «" + b + "»"); }
  static void g(int[] n, int f, int m, String what) { ok(n[0] == f && n[1] == m, what + ": ж" + n[0] + " м" + n[1] + " вместо ж" + f + " м" + m); }
  static String[] row(String dir, String src) { return new String[]{dir, src, "перевод", "0", "", "", "0", ""}; }
  static List<String[]> rows(String[]... r) { return Arrays.asList(r); }

  public static int run() {
    fails = 0; checks = 0;

    // ---- португальский: говорящая женщина
    g(Memo.ptSelf("Muito obrigada pela ajuda."), 1, 0, "P1 благодарность женщины");
    g(Memo.ptSelf("Não quero, obrigada."), 1, 0, "P1 благодарность в конце");
    g(Memo.ptSelf("Tô cansada, viu?"), 1, 0, "P2 tô + прилагательное");
    g(Memo.ptSelf("Eu estou acostumada, eu fiz isso também."), 1, 0, "P2 estou + причастие");
    g(Memo.ptSelf("Fiquei muito satisfeita com isso."), 1, 0, "P2 через «muito»");
    g(Memo.ptSelf("Eu estava preocupada com você."), 1, 0, "P2 estava только после eu");
    g(Memo.ptSelf("Me sinto tranquila agora."), 1, 0, "P2 me sinto");
    g(Memo.ptSelf("Eu mesma fiz o bolo."), 1, 0, "P3 eu mesma");
    g(Memo.ptSelf("Sou enfermeira aqui no posto."), 1, 0, "P4 sou + профессия");
    g(Memo.ptSelf("Eu sou a dona da loja."), 1, 0, "P4 sou a + существительное");
    g(Memo.ptSelf("Obrigada! Eu estou cansada."), 2, 0, "P5 два признака в одной реплике");
    // ---- мужчина
    g(Memo.ptSelf("Obrigado, tchau."), 0, 1, "P6 благодарность мужчины");
    g(Memo.ptSelf("Estou cansado da viagem."), 0, 1, "P6 estou cansado");
    g(Memo.ptSelf("Sou médico."), 0, 1, "P6 sou médico");
    g(Memo.ptSelf("Eu mesmo vi."), 0, 1, "P6 eu mesmo");
    g(Memo.ptSelf("Sou o mecânico da oficina."), 0, 1, "P6 sou o mecânico");
    // ---- не о себе: ложный признак дороже промаха
    g(Memo.ptSelf("Você não é obrigada a internar."), 0, 0, "P7 «é obrigada» — про собеседницу");
    g(Memo.ptSelf("Ela foi obrigada a sair."), 0, 0, "P7 третье лицо");
    g(Memo.ptSelf("Ela estava cansada."), 0, 0, "P7 estava без eu — лицо не понять");
    g(Memo.ptSelf("Está cansada?"), 0, 0, "P7 вопрос собеседнице");
    g(Memo.ptSelf("A enfermeira chegou."), 0, 0, "P7 профессия без связки");
    g(Memo.ptSelf("Sinto muito."), 0, 0, "P7 «sinto muito» — сожаление");
    g(Memo.ptSelf("Sou a favor."), 0, 0, "P7 «sou a favor» — не о роде");
    g(Memo.ptSelf("Eu vi o carro ontem."), 0, 0, "P7 прошедшее время рода не различает");
    g(Memo.ptSelf("Estou."), 0, 0, "P7 связка в конце");
    g(Memo.ptSelf("Estou muito muito muito cansada."), 1, 0, "P7 три слова между связкой и прилагательным — ещё считается");
    g(Memo.ptSelf("Estou muito muito muito muito cansada."), 0, 0, "P7 четыре — уже нет");
    g(Memo.ptSelf("Eu"), 0, 0, "P7 одно «eu»");
    g(Memo.ptSelf(""), 0, 0, "P7 пусто");
    g(Memo.ptSelf(null), 0, 0, "P7 null");
    g(Memo.ptSelf("Ele mesmo fez."), 0, 0, "P7 «ele mesmo» — не о себе");
    g(Memo.ptSelf("Eu comprei a nova."), 0, 0, "P7 после «eu» не связка — прилагательное не про говорящего");
    g(Memo.ptSelf("Sinto muito, obrigada."), 1, 0, "P7 «sinto muito» не связка, «obrigada» считается один раз");
    eq(Memo.ptEnding("", Memo.PT_ADJ), 0, "P8 пустое слово");
    eq(Memo.ptEnding("oa", Memo.PT_ADJ), 0, "P8 слово короче трёх букв");
    eq(Memo.ptEnding("cansade", Memo.PT_ADJ), 0, "P8 окончание не a и не o");
    eq(Memo.ptEnding("bonita", Memo.PT_ADJ), 0, "P8 основы нет в списке");

    // ---- русский: «я …»
    g(Memo.ruSelf("Я поняла."), 1, 0, "R1 поняла");
    g(Memo.ruSelf("Я не видела этого."), 1, 0, "R1 через «не»");
    g(Memo.ruSelf("Я бы хотела посмотреть."), 1, 0, "R1 через «бы»");
    g(Memo.ruSelf("Я уже устала."), 1, 0, "R1 через «уже»");
    g(Memo.ruSelf("Спасибо, я очень благодарна."), 1, 0, "R1 краткое прилагательное");
    g(Memo.ruSelf("Я сама решу."), 1, 0, "R1 сама");
    g(Memo.ruSelf("Я вернулась вчера."), 1, 0, "R1 -лась");
    g(Memo.ruSelf("Я ему сказала."), 1, 0, "R1 через местоимение");
    g(Memo.ruSelf("Я, конечно, поняла."), 1, 0, "R1 знаки препинания между словами не мешают");
    g(Memo.ruSelf("Я когда-то жила там."), 1, 0, "R1 слово через дефис пропускается целиком");
    g(Memo.ruSelf("Я понял."), 0, 1, "R2 понял");
    g(Memo.ruSelf("Я пришёл."), 0, 1, "R2 пришёл: ё как е, без «л»");
    g(Memo.ruSelf("Я не мог."), 0, 1, "R2 мог");
    g(Memo.ruSelf("Я рад."), 0, 1, "R2 рад");
    g(Memo.ruSelf("Я ещё не решил."), 0, 1, "R2 через два служебных");
    g(Memo.ruSelf("Я вернулся."), 0, 1, "R2 -лся");
    g(Memo.ruSelf("Я ел."), 0, 1, "R2 двухбуквенный глагол");
    g(Memo.ruSelf("Я последний раз видел это в Таиланде."), 0, 1, "R2 через «последний раз»");
    g(Memo.ruSelf("Я поняла, а он понял."), 1, 0, "R3 «он понял» — не о себе");
    g(Memo.ruSelf("Мы поняли."), 0, 0, "R4 «мы» рода не даёт");
    g(Memo.ruSelf("Ты поняла?"), 0, 0, "R4 «ты» — про собеседника");
    g(Memo.ruSelf("Она пришла."), 0, 0, "R4 третье лицо");
    g(Memo.ruSelf("Я дома."), 0, 0, "R4 «дома» не глагол");
    g(Memo.ruSelf("Я на вокзал."), 0, 0, "R4 предлог не пропускается — «вокзал» не глагол");
    g(Memo.ruSelf("Я из села."), 0, 0, "R4 «из села» не глагол");
    g(Memo.ruSelf("Я знаю, что он пришёл."), 0, 0, "R4 свободного окна нет");
    g(Memo.ruSelf("Я Maria."), 0, 0, "R4 латиница");
    g(Memo.ruSelf("Я не не не поняла."), 1, 0, "R4 три служебных подряд — ещё считается");
    g(Memo.ruSelf("Я не не не не поняла."), 0, 0, "R4 четыре — уже нет");
    g(Memo.ruSelf("Я"), 0, 0, "R4 одно «я»");
    g(Memo.ruSelf(null), 0, 0, "R4 null");
    eq(Memo.ruGender("алась"), Memo.F, "R5 -лась с пяти букв");
    eq(Memo.ruGender("лась"), 0, "R5 четыре буквы -лась — не слово");
    eq(Memo.ruGender("ался"), Memo.M, "R5 -лся с четырёх букв");
    eq(Memo.ruGender("лся"), 0, "R5 три буквы -лся — не слово");
    eq(Memo.ruGender("ала"), Memo.F, "R5 -ла с трёх букв");
    eq(Memo.ruGender("ла"), 0, "R5 две буквы -ла — не слово");
    eq(Memo.ruGender("ал"), Memo.M, "R5 -л с двух букв");
    eq(Memo.ruGender("л"), 0, "R5 одна буква");
    eq(Memo.ruGender("дома"), 0, "R5 другое окончание");

    // ---- слова
    eq(Memo.tokens("Когда-то я, Ёлка!"), Arrays.asList("когда-то", "я", "елка"), "T1 дефис внутри слова, «ё» как «е», знаки — разделители");
    eq(Memo.tokens("-abc abc- a - b"), Arrays.asList("abc", "abc", "a", "b"), "T2 дефис в начале, в конце и отдельно — не часть слова");
    eq(Memo.tokens("abc-1 x2"), Arrays.asList("abc", "1", "x2"), "T3 дефис перед цифрой разделяет");
    eq(Memo.tokens("abc-"), Arrays.asList("abc"), "T3 дефис последним знаком строки");

    // ---- итог по стороне
    eq(Memo.verdict(0, 0), 0, "V1 нет голосов");
    eq(Memo.verdict(1, 0), Memo.F, "V2 только женские");
    eq(Memo.verdict(0, 1), Memo.M, "V2 только мужские");
    eq(Memo.verdict(3, 1), Memo.F, "V3 перевес втрое — один говорящий");
    eq(Memo.verdict(1, 3), Memo.M, "V3 перевес втрое в другую сторону");
    eq(Memo.verdict(2, 1), Memo.BOTH, "V4 меньше чем втрое — двое");
    eq(Memo.verdict(1, 2), Memo.BOTH, "V4 и в другую сторону");
    eq(Memo.verdict(1, 1), Memo.BOTH, "V4 поровну");

    // ---- строка «кто говорит»
    List<String[]> rows = new ArrayList<>();
    rows.add(row("pt2ru", "Obrigada, até logo.")); rows.add(row("ru2pt", "Я понял, спасибо.")); rows.add(row("pt2ru", "Tô cansada hoje."));
    eq(Memo.who(rows), "Кто говорит: по-португальски — женщина, по-русски — мужчина.", "W1 обе стороны");
    eq(Memo.who(rows(row("pt2ru", "Obrigada."))), "Кто говорит: по-португальски — женщина.", "W2 только португальская");
    eq(Memo.who(rows(row("ru2pt", "Я понял."))), "Кто говорит: по-русски — мужчина.", "W2 только русская");
    eq(Memo.who(rows(row("ru2pt", "Я понял."), row("ru2pt", "А я поняла."))), "Кто говорит: по-русски — мужчина и женщина.", "W3 по-русски двое");
    eq(Memo.who(rows(row("pt2ru", "Obrigada, obrigado."))), "", "W4 ничья в реплике — реплика не голосует");
    eq(Memo.who(rows(row("pt2ru", "Olá, tudo bem?"))), "", "W5 признаков нет");
    // перевод не считается: сырой перевод сам ошибается с родом
    eq(Memo.who(Collections.singletonList(new String[]{"pt2ru", "Eu vi o carro.", "Я поняла, я увидела"})), "", "W6 перевод не считается");
    eq(Memo.who(Arrays.asList(null, new String[]{"pt2ru"}, new String[]{null, "Obrigada"})), "", "W7 битые строки пропускаются");
    eq(Memo.who(null), "", "W7 null");
    eq(Memo.who(rows(new String[]{"pt2ru", "Obrigada"})), "Кто говорит: по-португальски — женщина.", "W8 строки из двух полей годятся");
    eq(Memo.who(rows(row("pt2ru", "Obrigado."), row("ru2pt", "Я поняла."))), "Кто говорит: по-португальски — мужчина, по-русски — женщина.", "W9 наоборот");

    // ---- память для фона
    eq(Memo.block("Кто говорит: по-русски — мужчина.", "Покупка билета.", "Вокзал", 420),
       "Кто говорит: по-русски — мужчина.\nПокупка билета.\nTema: Вокзал", "B1 три строки по порядку");
    eq(Memo.block("", "  Детали   с   пробелами ", " ", 420), "Детали с пробелами", "B2 пустые части пропускаются, пробелы сжаты");
    eq(Memo.block(null, null, null, 420), "", "B2 всё пусто");
    String who = "Кто говорит: по-португальски — женщина.", longD = String.join(" ", Collections.nCopies(80, "слово"));
    String b = Memo.block(who, longD, "Тема", 200);
    ok(b.length() <= 200, "B3 не длиннее потолка: " + b.length());
    ok(b.startsWith(who + "\n") && b.endsWith("\nTema: Тема"), "B3 «кто говорит» и тема целиком: " + b);
    ok(b.contains("слово…"), "B3 урезаны детали, с многоточием: " + b);
    // «к» + перевод строки + 20 знаков деталей + перевод строки + «Tema: т» (7) = 30
    String d20 = "абвгдежзийклмнопрсту";
    eq(Memo.block("к", d20, "т", 30), "к\n" + d20 + "\nTema: т", "B4 детали ровно на остаток — без обрезки");
    eq(Memo.block("к", d20 + "ф", "т", 30), "к\nабвгдежзийклмнопрст…\nTema: т", "B4 на знак длиннее — урезаны до остатка");
    eq(Memo.block("к", d20, "т", 29), "к\nTema: т", "B4 остаток меньше 20 знаков — детали выпадают");
    eq(Memo.block("", d20 + " хцчш", "", 20), "абвгдежзийклмнопрст…", "B4 без соседей переводы строки не вычитаются");
    eq(Memo.block("к", d20, "", 22), "к\n" + d20, "B4 без темы — один перевод строки");
    eq(Memo.block("", d20, "т", 28), d20 + "\nTema: т", "B4 без «кто говорит» — один перевод строки");
    ok(Memo.block(longD, "", "", 50).length() <= 50, "B5 даже «кто говорит» не длиннее потолка");
    eq(Memo.block("к", "абв", "т", 13), "к\nабв\nTema: т", "B6 детали ровно на малый остаток — остаются целиком");

    // ---- обрезка
    eq(Memo.cut("abc", 5), "abc", "K1 короче — как есть");
    eq(Memo.cut("abcde", 5), "abcde", "K1 ровно — как есть");
    eq(Memo.cut("hello world foo", 12), "hello world…", "K2 по границе слова");
    eq(Memo.cut("abcdefghij", 5), "abcd…", "K3 без пробела — по знаку");
    eq(Memo.cut("ab cdefghijkl", 10), "ab cdefgh…", "K4 пробел ближе середины — режем по знаку");
    eq(Memo.cut("abcd efgh ij", 10), "abcd efgh…", "K4 пробел дальше середины — по слову");
    eq(Memo.cut("abcde fghijk", 10), "abcde fgh…", "K4 пробел ровно на середине — ещё по знаку");
    eq(Memo.cut("xy", 1), "…", "K5 потолок в один знак");
    eq(Memo.cut("xyz", 0), "…", "K5 нулевой потолок");

    // ---- ключевые детали из ответа модели
    eq(Memo.clean("**Хозяйка (женщина) показывает квартиру паре**"), "Хозяйка (женщина) показывает квартиру паре", "L1 разметка снята");
    eq(Memo.clean("«Хозяйка» Мария   сдаёт квартиру"), "Хозяйка Мария сдаёт квартиру", "L1 кавычки и пробелы");
    eq(Memo.clean(null), null, "L2 null");
    eq(Memo.clean("   "), null, "L2 пусто");
    eq(Memo.clean("null"), null, "L2 null словом");
    eq(Memo.clean("Нет."), null, "L2 «нет»");
    eq(Memo.clean("NONE"), null, "L2 none");
    eq(Memo.clean("Нет данных!"), null, "L2 «нет данных»");
    eq(Memo.clean("Неизвестно."), null, "L2 «неизвестно»");
    eq(Memo.clean("Нет договорённости о цене, мастер (мужчина) перезвонит."), "Нет договорённости о цене, мастер (мужчина) перезвонит.", "L2 «нет» в начале настоящей памяти — не отказ");
    eq(Memo.clean("n/a"), null, "L2 n/a");
    eq(Memo.clean("—"), null, "L2 тире");
    eq(Memo.clean("-"), null, "L2 дефис");
    eq(Memo.clean("<ключевые факты по-русски>"), null, "L3 эхо шаблона");
    eq(Memo.clean("Цена < 100 реалов и > 50"), "Цена < 100 реалов и > 50", "L3 знаки сравнения внутри — не шаблон");
    eq(Memo.clean("Итого: 100 реалов >"), "Итого: 100 реалов >", "L3 «>» в конце без «<» в начале — не шаблон");
    eq(Memo.clean("<начало и дальше текст"), "<начало и дальше текст", "L3 «<» в начале без «>» в конце — не шаблон");
    eq(Memo.clean("The landlady shows the flat"), null, "L4 не по-русски");
    eq(Memo.clean(Memo.EXAMPLE), null, "L5 пример из подсказки");
    eq(Memo.clean(Memo.EXAMPLE.replace("5 %", "10 %")), null, "L5 пример с мелкой правкой");
    String real = "Механик (мужчина) чинит тормоза, оплата картой.";
    eq(Memo.clean(real), real, "L6 похожий разговор, но не пример — остаётся");
    String longMemo = String.join(" ", Collections.nCopies(90, "факт"));
    String c = Memo.clean(longMemo);
    ok(c != null && c.length() <= Memo.MAX && c.endsWith("…"), "L7 длинное урезано до потолка: " + (c == null ? null : c.length()));
    // Эхо: в ответе не меньше 80 % значимых слов примера (их 15), и ответ не длиннее примера в полтора
    // раза (22 слова). Память о похожем разговоре совпадёт частично или окажется длиннее.
    ok(Memo.echo(Memo.EXAMPLE), "L8 сам пример — эхо");
    String w12 = "механик мужчина владелец машины автосервисе меняют ремень грм неоригинальный гарантия полгода оплата";
    ok(Memo.echo(w12), "L8 12 слов примера из 15 — эхо");
    ok(!Memo.echo(w12.replace(" оплата", "")), "L8 11 слов из 15 — не эхо");
    ok(Memo.echo(Memo.EXAMPLE + " альфа бета гамма дельта эпсилон дзета тета"), "L8 22 слова — ещё эхо");
    ok(!Memo.echo(Memo.EXAMPLE + " альфа бета гамма дельта эпсилон дзета тета йота"), "L8 23 слова — уже не эхо");
    ok(Memo.clean(Memo.EXAMPLE + " Кроме того обсудили замену тормозных колодок передних дисков задних барабанов проверку подвески") != null,
       "L8 пример с длинным продолжением — память, а не эхо");

    // ---- разбор ответа облака (Cloud.Review) с памятью
    Cloud.Review r = Cloud.Review.parse("**TOPIC**: Аренда квартиры\nMEMO: Хозяйка (женщина) показывает квартиру паре.\nFIX 2: Я поняла.\n"
        + "TERMS: aluguel=аренда\nNAMES: Mariana=Мариана");
    eq(r.topic, "Аренда квартиры", "C1 тема");
    eq(r.memo, "Хозяйка (женщина) показывает квартиру паре.", "C1 память");
    eq(r.fixes.get(2), "Я поняла.", "C1 правка");
    eq(r.terms.size(), 1, "C1 пара");
    eq(r.names.size(), 1, "C1 имя");
    eq(Cloud.Review.parse("MEMO: " + Memo.EXAMPLE + "\nFIX 1: Olá").memo, "", "C2 пример из подсказки отброшен");
    eq(Cloud.Review.parse("MEMO: The landlady explains\nFIX 1: Olá").memo, "", "C2 память не по-русски отброшена");
    eq(Cloud.Review.parse("- **MEMO:** Мастер (мужчина) чинит кран: 200 реалов\nFIX 1: Olá").memo, "Мастер (мужчина) чинит кран: 200 реалов", "C3 разметка и двоеточие внутри");
    eq(Cloud.Review.parse("FIX 1: Olá").memo, "", "C4 без строки MEMO — пусто");

    System.out.println(fails == 0 ? "Memo: " + checks + " проверок, все прошли" : "Memo: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
