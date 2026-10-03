package dev.agenttranslator;

import java.util.*;

/** Память разговора: что уточнитель знает о разговоре сверх последних реплик.
 *
 *  Задача: уточнителю подкладывается свежий кусок разговора (Brief.FRESH_CHARS, три-четыре реплики)
 *  и тема в несколько слов. Всё, что было раньше, выпадало целиком: кто говорит, мужчина это или
 *  женщина, о чём договорились. А сырой перевод без уточнителя понятен редко — итог почти целиком
 *  зависит от того, что уточнитель знает о разговоре.
 *
 *  Память из двух частей:
 *  — «кто говорит» считается здесь, без модели и без сети, по грамматике исходных реплик: женщина
 *    говорит о себе «obrigada», «estou cansada», «я поняла». Именно по исходникам, а не по
 *    переводам: сырой перевод сам ошибается с родом — в реальном разговоре он написал собеседнице
 *    «я увидел», «я забыл», «я проверил» в 8 из 10 её реплик о себе, потому что португальское прошедшее время рода
 *    не различает, а русское различает;
 *  — «ключевые детали» (роли, место, договорённости, имена, числа) пишет облачный пересмотр: он
 *    получает прежнюю память и дополняет её, поэтому старое не теряется и после 6000 знаков. Или
 *    их пишет сам человек — тогда автоматика их не трогает. Локальный Hy-MT2 для этого не годится:
 *    на телефоне он путал, кто что сказал, и не заполнял анкету (results/2026-09-28-memo.md).
 *
 *  Промах здесь дешёвый — уточнитель остаётся с тем, что видит в свежих репликах; ложный признак
 *  дорогой — он подтолкнёт не к тому роду во всех репликах. Поэтому считаются только явные формы
 *  первого лица, а спорные места («você não é obrigada», «я на вокзал») пропускаются.
 *
 *  Без Android, проверяется на столе (bench/apk/test/MemoTest.java).
 */
public class Memo {
  /** Потолок всей памяти в фоне уточнителя, в знаках: каждый знак — время разбора на процессоре. */
  public static final int CAP = 420;
  /** Потолок ключевых деталей от модели: просим 300, лишнее обрезаем по слову. */
  public static final int MAX = 320;
  /** Пример из подсказки облаку. Одна строка на оба места, чтобы отсев эха примера не разошёлся
   *  с самим примером. */
  public static final String EXAMPLE = "Механик (мужчина) и владелец машины (мужчина) в автосервисе. Меняют ремень ГРМ на неоригинальный, "
      + "гарантия полгода; оплата через пикс со скидкой 5 %.";

  public static final int F = 1, M = 2, BOTH = 3;

  // ---- португальский: как говорящий говорит о себе

  /** Первое лицо глагола-связки: после них прилагательное согласуется с говорящим. */
  static final Set<String> PT_COPULA = set("estou", "tô", "to", "tou", "sou", "fiquei", "fico", "estive", "ando", "andei", "sinto");
  /** Связки, по которым лицо не понять («ela estava cansada»): считаем, только если перед ними «eu». */
  static final Set<String> PT_COPULA_EU = set("estava", "tava", "era", "ficava");
  /** Связки второго и третьего лица перед «obrigada»: «você não é obrigada a…» — не благодарность и не о себе. */
  static final Set<String> PT_NOT_ME = set("é", "são", "está", "esta", "foi", "ser", "seja", "fica", "ficou", "estar", "sendo",
      "estava", "tava", "era", "és", "estás");
  /** Слова между связкой и прилагательным: «tô muito cansada», «sou a dona». */
  static final Set<String> PT_SKIP = set("muito", "tão", "tao", "meio", "bem", "super", "mais", "um", "uma", "pouco", "pouquinho",
      "bastante", "toda", "todo", "tipo", "já", "ja", "não", "nao", "também", "tambem", "sempre", "ainda", "assim", "mega", "a", "o");
  /** Основы прилагательных и причастий, которые говорящий применяет к себе: основа + a — женщина, + o — мужчина. */
  static final Set<String> PT_ADJ = set("cansad", "acostumad", "satisfeit", "preocupad", "ocupad", "animad", "nervos", "sozinh",
      "pront", "confus", "perdid", "atrasad", "casad", "encantad", "apaixonad", "assustad", "surpres", "tranquil", "sentad", "deitad",
      "grat", "interessad", "cert", "errad", "decidid", "esgotad", "mort", "acordad", "empolgad", "emocionad", "chatead", "irritad",
      "envergonhad", "machucad", "curios", "ansios", "orgulhos", "sortud", "obrigad", "enjoad", "estressad", "agradecid", "aliviad",
      "apavorad", "chei", "molhad", "tont", "doid", "louc", "nov", "velh", "brasileir", "russ", "solteir", "divorciad", "viúv",
      "formad", "aposentad", "vacinad", "internad", "operad", "grávid", "gravid", "acompanhad", "convencid", "dispost", "exaust",
      "focad", "habituad", "impressionad", "magoad", "parad", "quiet", "resfriad", "sossegad");
  /** «eu mesma», «eu própria», «eu sozinha». */
  static final Set<String> PT_SELF = set("mesm", "própri", "propri", "sozinh");
  /** Существительные, которыми говорящий называет себя: «sou enfermeira», «sou a dona». */
  static final Map<String, Integer> PT_NOUN = new HashMap<>();
  static {
    for (String w : new String[]{"mãe", "mae", "mulher", "esposa", "avó", "doula", "parteira", "enfermeira", "médica", "medica",
        "professora", "vendedora", "moça", "moca", "senhora", "filha", "dona", "gestante", "cozinheira", "cabeleireira", "costureira",
        "faxineira", "garçonete", "secretária", "advogada", "psicóloga", "corretora", "vizinha", "namorada", "noiva", "amiga", "irmã"})
      PT_NOUN.put(w, F);
    for (String w : new String[]{"pai", "homem", "esposo", "marido", "avô", "parteiro", "enfermeiro", "médico", "medico", "professor",
        "vendedor", "rapaz", "moço", "moco", "senhor", "filho", "dono", "cozinheiro", "cabeleireiro", "garçom", "garcom", "secretário",
        "advogado", "psicólogo", "corretor", "vizinho", "namorado", "noivo", "amigo", "irmão", "irmao", "mecânico", "mecanico"})
      PT_NOUN.put(w, M);
  }

  // ---- русский: «я» и то, что сразу после него

  /** Служебные слова между «я» и глаголом: «я уже устала», «я бы хотела», «я ему сказал». Только
   *  известные слова: свободное окно захватило бы «я на вокзал» и «я из села» как глаголы. */
  static final Set<String> RU_SKIP = set("не", "уже", "тоже", "так", "очень", "вполне", "совсем", "просто", "сейчас", "еще", "бы",
      "же", "ж", "вот", "только", "все", "сегодня", "вчера", "давно", "недавно", "тут", "там", "здесь", "туда", "сюда", "и", "ведь",
      "раньше", "потом", "теперь", "тогда", "никогда", "всегда", "правда", "конечно", "реально", "честно", "точно", "сразу", "чуть",
      "немного", "почти", "случайно", "специально", "как", "ни", "даже", "лично", "вообще", "то", "опять", "снова", "еле", "лишь",
      "ему", "ей", "его", "ее", "им", "их", "вам", "вас", "тебе", "тебя", "нам", "нас", "себе", "себя", "это", "этого", "этим",
      "последний", "первый", "раз", "когда-то", "где-то", "что-то");
  static final Set<String> RU_F = set("рада", "готова", "уверена", "должна", "согласна", "занята", "довольна", "счастлива", "больна",
      "беременна", "свободна", "благодарна", "замужем", "сама", "права", "виновата", "обязана", "знакома", "влюблена", "удивлена",
      "голодна", "сыта", "спокойна", "расстроена", "напугана", "поражена", "тронута", "признательна", "способна", "намерена",
      "вынуждена", "уставшая", "беременная", "готовая", "больная", "замужняя");
  static final Set<String> RU_M = set("рад", "готов", "уверен", "должен", "согласен", "занят", "доволен", "счастлив", "болен",
      "свободен", "благодарен", "женат", "сам", "прав", "виноват", "обязан", "знаком", "влюблен", "удивлен", "голоден", "сыт",
      "спокоен", "расстроен", "напуган", "поражен", "тронут", "признателен", "способен", "намерен", "вынужден", "уставший",
      "готовый", "больной", "женатый",
      // прошедшее время мужского рода без «л» на конце
      "пришел", "ушел", "нашел", "шел", "зашел", "вышел", "дошел", "перешел", "подошел", "прошел", "пошел", "вошел", "обошел",
      "мог", "смог", "помог", "лег", "привез", "принес", "увез", "отвез", "повез", "довез", "вез", "нес", "отнес", "унес", "испек",
      "берег", "замерз", "промок", "привык", "отвык", "достиг", "запер", "стер", "вытер");

  static Set<String> set(String... a) { return new HashSet<>(Arrays.asList(a)); }

  /** Слова строки строчными, «ё» как «е»: буквы и цифры, дефис — только внутри слова. */
  static List<String> tokens(String s) {
    List<String> r = new ArrayList<>();
    if (s == null) return r;
    String low = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    StringBuilder w = new StringBuilder();
    for (int i = 0; i <= low.length(); i++) {
      char c = i < low.length() ? low.charAt(i) : ' ';
      boolean inner = c == '-' && w.length() > 0 && i + 1 < low.length() && Character.isLetter(low.charAt(i + 1));
      if (Character.isLetterOrDigit(c) || inner) w.append(c);
      else if (w.length() > 0) { r.add(w.toString()); w.setLength(0); }
    }
    return r;
  }

  /** Сколько раз португальская реплика называет говорящего женщиной и мужчиной: {ж, м}. */
  public static int[] ptSelf(String text) {
    int[] n = new int[2];
    List<String> t = tokens(text);
    for (int i = 0; i < t.size(); i++) {
      String w = t.get(i), prev = i > 0 ? t.get(i - 1) : "";
      // Благодарность: «obrigada» говорит женщина. Но «você não é obrigada a internar» — про собеседницу.
      if (w.equals("obrigada") || w.equals("obrigado")) { if (!PT_NOT_ME.contains(prev)) n[w.endsWith("a") ? 0 : 1]++; continue; }
      if (w.equals("eu") && i + 1 < t.size()) {
        int g = ptEnding(t.get(i + 1), PT_SELF);
        if (g != 0) { n[g == F ? 0 : 1]++; continue; }
      }
      boolean cop = PT_COPULA.contains(w) || (PT_COPULA_EU.contains(w) && prev.equals("eu"));
      if (w.equals("sinto") && !prev.equals("me")) cop = false;     // «sinto muito» — сожаление, а не «чувствую себя»
      if (!cop) continue;
      int j = i + 1, skipped = 0;
      while (j < t.size() && skipped < 3 && PT_SKIP.contains(t.get(j))) { j++; skipped++; }
      if (j >= t.size()) continue;
      String a = t.get(j);
      Integer g = PT_NOUN.get(a);
      int e = g != null ? g : ptEnding(a, PT_ADJ);
      if (e != 0) n[e == F ? 0 : 1]++;
    }
    return n;
  }
  /** Основа из списка + «a» — женщина, + «o» — мужчина; иначе 0. */
  static int ptEnding(String w, Set<String> stems) {
    if (w.isEmpty()) return 0;
    char last = w.charAt(w.length() - 1);
    if (last != 'a' && last != 'o') return 0;
    return stems.contains(w.substring(0, w.length() - 1)) ? (last == 'a' ? F : M) : 0;
  }

  /** Сколько раз русская реплика называет говорящего женщиной и мужчиной: {ж, м}. Только «я …»:
   *  «поняла» без подлежащего бывает и про третье лицо. */
  public static int[] ruSelf(String text) {
    int[] n = new int[2];
    List<String> t = tokens(text);
    for (int i = 0; i < t.size(); i++) {
      if (!t.get(i).equals("я")) continue;
      int j = i + 1, skipped = 0;
      while (j < t.size() && skipped < 3 && RU_SKIP.contains(t.get(j))) { j++; skipped++; }
      if (j >= t.size()) continue;
      int g = ruGender(t.get(j));
      if (g != 0) n[g == F ? 0 : 1]++;
    }
    return n;
  }
  /** Род слова сразу после «я»: краткое прилагательное из списка или глагол прошедшего времени. */
  static int ruGender(String w) {
    if (RU_F.contains(w)) return F;
    if (RU_M.contains(w)) return M;
    if (w.endsWith("лась") && w.length() >= 5) return F;           // вернулась, боялась
    if (w.endsWith("лся") && w.length() >= 4) return M;            // вернулся, боялся
    if (w.endsWith("ла") && w.length() >= 3) return F;             // была, поняла, шла
    if (w.endsWith("л") && w.length() >= 2) return M;              // был, понял, ел
    return 0;
  }

  /** Итог по одной стороне разговора из голосов реплик: F, M, BOTH или 0 — не ясно.
   *  Реплика голосует, если признаков одного рода в ней больше. Перевес втрое — один говорящий,
   *  а редкие голоса против — ошибки распознавания; иначе говорят двое. */
  public static int verdict(int female, int male) {
    if (female == 0 && male == 0) return 0;
    if (female >= 3 * male) return F;                              // в том числе мужских нет вовсе
    if (male >= 3 * female) return M;
    return BOTH;
  }

  /** Строка «кто говорит» по репликам разговора. rows — как Chats.all(): {направление, исходник, …}.
   *  Пустая строка — ни по одной стороне признаков нет. */
  public static String who(List<String[]> rows) {
    int pf = 0, pm = 0, rf = 0, rm = 0;
    if (rows != null) for (String[] r : rows) {
      if (r == null || r.length < 2 || r[0] == null) continue;
      boolean pt = r[0].startsWith("pt");
      int[] n = pt ? ptSelf(r[1]) : ruSelf(r[1]);
      if (n[0] > n[1]) { if (pt) pf++; else rf++; }
      else if (n[1] > n[0]) { if (pt) pm++; else rm++; }
    }
    String p = word(verdict(pf, pm)), r = word(verdict(rf, rm));
    if (p.isEmpty() && r.isEmpty()) return "";
    StringBuilder b = new StringBuilder("Кто говорит: ");
    if (!p.isEmpty()) b.append("по-португальски — ").append(p);
    if (!r.isEmpty()) b.append(p.isEmpty() ? "" : ", ").append("по-русски — ").append(r);
    return b.append('.').toString();
  }
  static String word(int v) { return v == F ? "женщина" : v == M ? "мужчина" : v == BOTH ? "мужчина и женщина" : ""; }

  /** Память для фона уточнителя: кто говорит, ключевые детали, тема — строками, не длиннее cap.
   *  Урезаются детали: «кто говорит» короткое и полезнее всего, тема — одна строка. */
  public static String block(String who, String details, String topic, int cap) {
    String w = who == null ? "" : who.trim(), tp = topic == null || topic.trim().isEmpty() ? "" : "Tema: " + topic.trim();
    String d = details == null ? "" : details.trim().replaceAll("\\s+", " ");
    // Место под детали: всё, кроме «кто говорит», темы и переводов строки между частями.
    int room = cap - w.length() - tp.length() - (w.isEmpty() ? 0 : 1) - (tp.isEmpty() ? 0 : 1);
    if (d.length() > room) d = room < 20 ? "" : cut(d, room);
    StringBuilder b = new StringBuilder();
    for (String s : new String[]{w, d, tp}) if (!s.isEmpty()) b.append(b.length() == 0 ? "" : "\n").append(s);
    return cut(b.toString(), cap);
  }

  /** Не длиннее n знаков вместе с «…», по границе слова, если она не дальше середины. */
  static String cut(String s, int n) {
    if (s.length() <= n) return s;
    int end = Math.max(0, n - 1), sp = s.lastIndexOf(' ', end);
    if (sp > n / 2) end = sp;
    return s.substring(0, end).trim() + "…";
  }

  /** Отказ ответить по-русски: «нет», «нет данных». «null», «none» и пустое отсеются и так — в них нет кириллицы. */
  static final Set<String> NO_INFO = set("нет", "нет данных", "нет информации", "нет деталей", "не указано", "неизвестно", "пока нет", "пусто");

  /** Ключевые детали из ответа модели. null — ответа нет: не по-русски, пустышка, эхо шаблона
   *  или пример из подсказки. Длинное урезается до MAX по слову. */
  public static String clean(String raw) {
    if (raw == null) return null;
    String t = raw.replace("*", "").replace("`", "").replaceAll("[\"«»]", "").replaceAll("\\s+", " ").trim();
    if (NO_INFO.contains(t.toLowerCase(Locale.ROOT).replaceAll("[.!]+$", ""))) return null;
    if (t.startsWith("<") && t.endsWith(">")) return null;       // «<ключевые факты>» — эхо шаблона
    boolean cyr = false;
    for (int i = 0; i < t.length() && !cyr; i++) cyr = Character.UnicodeBlock.of(t.charAt(i)) == Character.UnicodeBlock.CYRILLIC;
    if (!cyr || echo(t)) return null;
    return cut(t, MAX);
  }
  /** Модель переписала пример из подсказки: в ответе почти все значимые слова примера, и сам ответ
   *  не намного длиннее. Настоящая память о похожем разговоре совпадёт частично — её не трогаем. */
  static boolean echo(String a) {
    Set<String> x = new HashSet<>(), y = new HashSet<>();
    for (String w : tokens(a)) if (w.length() >= 3) x.add(w);
    for (String w : tokens(EXAMPLE)) if (w.length() >= 3) y.add(w);
    int common = 0;
    for (String w : y) if (x.contains(w)) common++;
    return common * 100 >= y.size() * 80 && x.size() <= y.size() * 3 / 2;
  }

  // ---- собеседники по голосам (Voices): сколько их, как зовут, о чём говорят

  /** Человек назвался: «меня зовут Анна», «meu nome é Ana», «me chamo João Pedro». Только явные
   *  обороты и имя с заглавной: «sou a dona», «aqui é o Brasil» именем не становятся — ложное имя
   *  хуже его отсутствия. Регистр первой буквы оборота — классом, а не флагом: (?i) в Java не
   *  касается кириллицы, а флаги Unicode на Android (ICU) не все. lang — pt или ru; null — не назвался. */
  static final java.util.regex.Pattern INTRO_PT = java.util.regex.Pattern.compile(
      "(?:^|[^\\p{L}])(?:[Mm]eu nome [ée]|[Ee]u me chamo|[Mm]e chamo|[Pp]odem? me chamar de)\\s+(\\p{Lu}[\\p{Ll}'’\\-]+(?:\\s+\\p{Lu}[\\p{Ll}'’\\-]+)?)");
  static final java.util.regex.Pattern INTRO_RU = java.util.regex.Pattern.compile(
      "(?:^|[^\\p{L}])(?:[Мм]еня зовут|[Мм]о[её] имя|[Зз]овите меня)\\s+(\\p{Lu}[\\p{Ll}\\-]+(?:\\s+\\p{Lu}[\\p{Ll}\\-]+)?)");
  public static String intro(String text, String lang) {
    if (text == null) return null;
    java.util.regex.Matcher m = ("ru".equals(lang) ? INTRO_RU : INTRO_PT).matcher(text);
    return m.find() ? m.group(1) : null;
  }

  /** «Говорят двое» — сколько людей в разговоре по голосам. */
  public static String count(int n) {
    switch (n) {
      case 0: return "";
      case 1: return "Говорит один человек";
      case 2: return "Говорят двое";
      case 3: return "Говорят трое";
      case 4: return "Говорят четверо";
      case 5: return "Говорят пятеро";
      case 6: return "Говорят шестеро";
      default: return "Говорят " + n + " человек";
    }
  }
  /** Самое длинное, что голос сказал, — по-русски (у португальской реплики — её перевод), до max знаков. */
  static String longest(List<String[]> rows, String who, int max) {
    String best = "";
    for (String[] r : rows) {
      if (r == null || r.length < 8 || !who.equals(r[7])) continue;
      String ru = r[0].startsWith("pt") ? r[2] : r[1];
      if (ru != null && ru.trim().length() > best.length()) best = ru.trim();
    }
    return best.isEmpty() ? "" : cut(best, max);
  }
  /** Собеседники разговора для «Памяти разговора»: сколько их и строка на каждого — имя или
   *  «собеседник N», язык, мужчина или женщина (по грамматике его же реплик), о чём говорит. О чём —
   *  пересказ от пересмотра разговора (Voice.says), а пока его нет — самое длинное, что человек
   *  сказал, по-русски и в кавычках: его собственные слова, не догадка. Пусто — голосов нет.
   *  rows — Chats.dialog(): направление, исходник, перевод, …, номер голоса в поле 7. */
  public static String speakers(List<String[]> rows, Voices vs) {
    if (vs == null || vs.isEmpty()) return "";
    List<String[]> rs = rows == null ? new ArrayList<>() : rows;
    StringBuilder b = new StringBuilder(count(vs.size())).append('.');
    for (Voices.Voice v : vs.all()) {
      String w = String.valueOf(v.n);
      int f = 0, m = 0;
      for (String[] r : rs) {
        if (r == null || r.length < 8 || !w.equals(r[7])) continue;
        int[] c = r[0].startsWith("pt") ? ptSelf(r[1]) : ruSelf(r[1]);
        if (c[0] > c[1]) f++; else if (c[1] > c[0]) m++;
      }
      String g = word(verdict(f, m));
      b.append("\n• ").append(v.name.isEmpty() ? "Собеседник " + v.n : v.name + " (собеседник " + v.n + ")")
       .append(" — ").append("ru".equals(v.lang) ? "по-русски" : "по-португальски").append(g.isEmpty() ? "" : ", " + g);
      String about = !v.says.isEmpty() ? v.says : longest(rs, w, 90);
      if (!about.isEmpty()) b.append(": ").append(v.says.isEmpty() ? "«" + about + "»" : about);
    }
    return b.toString();
  }
}
