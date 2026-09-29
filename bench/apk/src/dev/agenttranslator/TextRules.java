package dev.agenttranslator;

import java.util.*;
import java.util.regex.*;

/** §6 ярус 3: маскирование чисел/цен/времени/адресов до MT и восстановление после; нормализация pt-PT → pt-BR.
 *   — без него \b и \w в Java не понимают кириллицу и диакритику. */
public class TextRules {
  /** Java: \\b/\\w с юникодом только с UNICODE_CHARACTER_CLASS; ICU на Android юникодный по умолчанию и этот флаг не знает. */
  static Pattern P(String re) { try { return Pattern.compile(re, Pattern.UNICODE_CHARACTER_CLASS); } catch (Throwable t) { return Pattern.compile(re); } }
  public static class Masked { public final String text; public final List<String[]> slots = new ArrayList<>(); Masked(String t) { text = t; } }
  /** Тот же набор слотов с новым текстом — нужен, когда поверх маскирования проходит список своих слов. */
  public static Masked remask(Masked m, String text) { Masked o = new Masked(text); o.slots.addAll(m.slots); return o; }
  /** У parakeet в словаре нет буквы «ё», она выходит как <unk>; в русском это почти всегда «е». */
  public static String fixAsr(String t, String lang) {
    if (t.indexOf('<') < 0) return t;
    return t.replace("<unk>", lang.equals("ru") ? "е" : "").replaceAll("\\s{2,}", " ").trim();
  }
  static final Pattern[] PT = {
    // Имя улицы в группе 1: маскируется только оно, родовое слово остаётся видимым для MT,
    // иначе MT выдумывает для плейсхолдера существительное («по трассе проспект Базил»).
    // Заглавная не требуется: распознавание часто пишет «rua das flores» со строчной,
    // и без этого улица уезжает в перевод дословно («цветочная улица»).
    // Пробелы здесь только горизонтальные: на вывеске «Entrada pela rua» и «Proibido fumar» —
    // разные строки, а «\s» перешагивал перенос и забирал следующую строку в название улицы.
    // Она уходила в плейсхолдер и возвращалась транслитерацией: «Пройбиду Фумар».
    P("\\b(?i:Rua|Av\\.|Av|Avenida|Travessa|Alameda|Praça|Praca|Estrada|Rodovia|Largo)[^\\S\\n]+((?:d[aeo]s?[^\\S\\n]+)?\\p{L}{3,}(?:[^\\S\\n]+(?:d[aeo]s?[^\\S\\n]+)?\\p{L}{3,}){0,2})"),
    P("R\\$\\s?\\d+(?:[.,]\\d+)?|\\b\\d+(?:[.,]\\d+)?\\s*(?:reais|real|centavos)\\b"),
    P("\\b\\d{1,2}[h:]\\d{2}\\b|\\b\\d{5}-\\d{3}\\b"),
    P("\\b\\d+(?:[.,]\\d+)?\\b") };
  static final Pattern[] RU = {
    P("\\b(?i:улиц[ауые]|ул\\.|проспект[еа]?|пр\\.|переул(?:ок|ке)|площад[ьи]|шоссе|бульвар[е]?)[^\\S\\n]+([\\p{L}-]{3,}(?:[^\\S\\n]+[\\p{L}-]{3,}){0,2})"),
    P("\\b\\d+(?:[.,]\\d+)?\\s*(?:рубл(?:ей|я|ь)|реал(?:ов|а)?|евро|доллар(?:ов|а)?)\\b"),
    P("\\b\\d{1,2}:\\d{2}\\b"),
    P("\\b\\d+(?:[.,]\\d+)?\\b") };
  static final String[] KIND = {"addr", "money", "time", "num"};
  /** Только текст вывески (снимок): номер дома «nº 123» — переводчик читал «Nº» как «Нет»;
   *  температура «5°C» — знака градуса нет в словаре переводчика, выходило «5 ⁇ C»; цифры через
   *  пробел, дефис или дробь — одним куском («7 896025 804067», «3242-3300», «10/05/2026»): два
   *  плейсхолдера подряд переводчик переписывал кириллицей («СК3 СК4») или выдумывал вокруг них
   *  «Модель:». Речь это не трогает: там цифры через пробел — разные числа. */
  /** Сокращения этикеток и вывесок — своим переводом, переводчику их не показываем: он их не знает
   *  и выдумывает вокруг («SP /CNPJ: 62.162.243/0003-45» → «Модель: 62.162.243/0003-45»). */
  static final Map<String, String> ABBR_RU = new HashMap<>();
  /** Коды штатов — только рядом с дефисом или дробью, как в адресе («Monte Alto-SP», «SP /CNPJ»):
   *  отдельное «PE», «MA», «AL» на вывеске прописными может быть португальским словом. */
  static final Map<String, String> STATE_RU = new LinkedHashMap<>();
  static {
    String[] a = {"CNPJ", "ИНН", "CPF", "ИНН", "CEP", "индекс", "SAC", "служба поддержки", "LTDA", "ООО"};
    for (int i = 0; i < a.length; i += 2) ABBR_RU.put(a[i], a[i + 1]);
    String[] st = {"AC", "Акри", "AL", "Алагоас", "AP", "Амапа", "AM", "Амазонас", "BA", "Баия", "CE", "Сеара",
        "DF", "Федеральный округ", "ES", "Эспириту-Санту", "GO", "Гояс", "MA", "Мараньян", "MT", "Мату-Гросу",
        "MS", "Мату-Гросу-ду-Сул", "MG", "Минас-Жерайс", "PA", "Пара", "PB", "Параиба", "PR", "Парана",
        "PE", "Пернамбуку", "PI", "Пиауи", "RJ", "Рио-де-Жанейро", "RN", "Риу-Гранди-ду-Норти",
        "RS", "Риу-Гранди-ду-Сул", "RO", "Рондония", "RR", "Рорайма", "SC", "Санта-Катарина", "SP", "Сан-Паулу",
        "SE", "Сержипи", "TO", "Токантинс"};
    for (int i = 0; i < st.length; i += 2) STATE_RU.put(st[i], st[i + 1]);
  }
  static final String STATES = String.join("|", STATE_RU.keySet());
  /** Название фирмы — слова с заглавной (или прописными) перед формой собственности: «ICPA CEPÊRA LTDA»,
   *  «Nestlé Brasil Ltda», «3M do Brasil Ltda», «BRF S.A.», «JBS S/A». Переводчик писал его то «Икпа
   *  Сепера», то «Икпа Цепера»; по-русски иностранную фирму оставляют латиницей: «ООО «ICPA CEPÊRA»».
   *  «por», «para», «pela», «pelo» перед названием в него не входят («FABRICADO POR CEPÊRA LTDA»),
   *  плейсхолдер своих слов — тоже. */
  static final String FIRM_WORD = "(?!(?i:por|para|pela|pelo)(?![\\p{L}\\p{N}]))(?!XQ\\d)\\p{N}*\\p{Lu}[\\p{L}\\p{N}]*(?:[-'&][\\p{L}\\p{N}]+)*";
  static final Pattern FIRM = P("(?<![\\p{L}\\p{N}])" + FIRM_WORD + "(?:[^\\S\\n]+(?:(?:(?i:d[aeo]s?|e)|&)[^\\S\\n]+)?" + FIRM_WORD + "){0,6}"
      + "[^\\S\\n]+(?:(?i:ltda)(?![\\p{L}\\p{N}])|S\\.[^\\S\\n]?A\\.|S/A(?![\\p{L}\\p{N}])|(?i:eireli)(?![\\p{L}\\p{N}]))"),
      FIRM_FORM = P("[^\\S\\n]+((?i:ltda)|S\\.[^\\S\\n]?A\\.|S/A|(?i:eireli))$");
  /** Марка снимка по прямым признакам: имя сайта или почты («www.cepera.com.br», «sac@cepera.com.br»
   *  → cepera), слово перед ® или ™, незнакомое словарю слово в названии фирмы («ICPA CEPÊRA LTDA» →
   *  icpa, cepera). Такое слово в других строках того же снимка переводчику не показывается. */
  static final Pattern DOMAIN = P("(?i)(?:www\\.|https?://(?:www\\.)?|@)([a-z0-9][a-z0-9-]{2,})\\.(?:com|net|org|ind|art|br)(?![a-z0-9])"),
      MARKED = P("(\\p{L}[\\p{L}\\p{N}'-]*)[^\\S\\n]?(?:®|™|\\((?:R|TM)\\))"),
      WORD_ANY = P("(?<![\\p{L}\\p{N}])\\p{L}[\\p{L}\\p{N}]*(?![\\p{L}\\p{N}])"),
      // слово марки, но не часть адреса сайта или почты: «www.XQ1.com.br» переводчик превращал в
      // «2019 cepera.com.ua. Все права защищены.»
      BRAND_WORD = P("(?<![\\p{L}\\p{N}.@/])\\p{L}[\\p{L}\\p{N}]*(?![\\p{L}\\p{N}@]|\\.[\\p{L}\\p{N}])");
  static final Set<String> MAIL = new HashSet<>(Arrays.asList("gmail", "hotmail", "outlook", "yahoo", "uol", "bol", "terra",
      "live", "icloud", "msn", "globo", "instagram", "facebook", "whatsapp", "gov", "mail"));
  public static Set<String> brands(List<String> texts, java.util.function.Predicate<String> known) {
    Set<String> out = new HashSet<>();
    for (String t : texts) {
      Matcher m = DOMAIN.matcher(t);
      while (m.find()) { String d = m.group(1).toLowerCase(Locale.ROOT); if (!MAIL.contains(d)) out.add(d); }
      m = MARKED.matcher(t);
      while (m.find()) out.add(OcrWords.plain(m.group(1)));
      m = FIRM.matcher(t);
      while (m.find()) {
        Matcher f = FIRM_FORM.matcher(m.group()); String name = f.find() ? m.group().substring(0, f.start()) : m.group();
        for (String w : name.split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3 && !known.test(w.toLowerCase(Locale.ROOT))) out.add(OcrWords.plain(w));
      }
    }
    return out;
  }
  /** Единственное слово строки (от двух букв) или null: абзац-вывеска из одного слова («Rommanel», «CEPÊRA 21°»). */
  public static String oneWord(String t) {
    Matcher m = WORD_ANY.matcher(t); String one = null;
    while (m.find()) { if (m.group().length() < 2) continue; if (one != null) return null; one = m.group(); }
    return one;
  }
  /** Обычные слова вывесок узнаются по суффиксу: Borracharia, Hamburgueria, Plastificação, Armarinho,
   *  Dosadora — словарь их не знает, а переводчик знает. У марок таких окончаний почти нет. */
  static final Pattern WORDISH = P("(?i)(?:aria|eria|ção|ções|inho|inha|inhos|inhas|eiro|eira|eiros|eiras|dor|dora|dores|doras"
      + "|mento|mentos|agem|agens|ista|istas|ável|ível|dade|dades|ense|ico|ica|icos|icas)$");
  /** Похоже на марку: от четырёх букв, с заглавной, словарю незнакомо, без суффикса обычного слова. */
  public static boolean brandLike(String w, java.util.function.Predicate<String> known) {
    return w.length() >= 4 && Character.isUpperCase(w.charAt(0)) && !known.test(w.toLowerCase(Locale.ROOT)) && !WORDISH.matcher(w).find();
  }
  /** Перевод — только транскрипция слова: переводчик его не знает и переписал кириллицей («Rommanel» →
   *  «Ромманель», «Cepêra» → «Сепера»). Порог — замер на телефоне: у 28 марок из 33 сходство с
   *  Translit 0,75 и выше, у настоящих переводов незнакомых слов вывесок — не выше 0,70 («Бомбонье»),
   *  у «Автомойка», «Блинчики», «Автозапчасти» — 0,11–0,42 (results/2026-09-28-ocr.md). */
  static final double BRAND_SIM = 0.75;
  public static boolean transliterated(String w, String ru) {
    String a = cyr(Translit.ptToRu(w)), b = cyr(ru); int m = Math.max(a.length(), b.length());
    return m > 0 && !b.isEmpty() && 1.0 - Phrasebook.lev(a, b) / (double) m >= BRAND_SIM;
  }
  /** Кириллица для сравнения: строчные, е/э, и/й, без мягкого знака, сдвоенные — одной. */
  static String cyr(String s) {
    String t = s.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('э', 'е').replace('й', 'и').replaceAll("[ьъ]", "").replaceAll("[^а-я]", "");
    return t.replaceAll("(.)\\1+", "$1");
  }
  static final Pattern[] PT_SIGN = { PT[0], PT[1], PT[2],
    P("(?i)\\bn\\.?[º°]\\s?\\d+"),
    FIRM,
    // не внутри адреса почты и сайта: «sac@cepera.com.br» не «служба поддержки@cepera.com.br»
    P("(?i)(?<![\\p{L}\\p{N}.@])(?:CNPJ|CPF|CEP|SAC|LTDA)(?![\\p{L}\\p{N}@]|\\.[\\p{L}\\p{N}])"),
    // город вместе с кодом штата через дефис или дробь («Monte Alto-SP», «São Paulo/SP») или, после
    // «- » и «, » адреса, с дефисом в конце строки, когда код штата ушёл на следующую («…, 1.001 -
    // Monte Alto-»); просто «Segunda-» в конце строки — не город. Как пишется — cityRu
    P("\\b\\p{Lu}\\p{Ll}+(?:[^\\S\\n]+(?:d[aeo]s?[^\\S\\n]+)?\\p{Lu}\\p{Ll}+){0,3}[^\\S\\n]?[-/][^\\S\\n]?(?:" + STATES + ")\\b"
      + "|(?<=[,–-][^\\S\\n])\\p{Lu}\\p{Ll}+(?:[^\\S\\n]+(?:d[aeo]s?[^\\S\\n]+)?\\p{Lu}\\p{Ll}+){0,3}[^\\S\\n]?-(?=[^\\S\\n]*$)"),
    P("(?<=[-/][^\\S\\n]?)(?:" + STATES + ")\\b|\\b(?:" + STATES + ")(?=[^\\S\\n]?/)"),
    P("\\b\\d+(?:[.,]\\d+)?\\s?[º°]\\s?[CF]?(?!\\p{L})"),
    // цифры одним куском, в том числе с точками тысяч: «62.162.243/0003-45»
    P("\\b\\d+(?:[.,]\\d+)*(?:[ /-]\\d+(?:[.,]\\d+)*)+\\b"),
    PT[3] };
  static final String[] KIND_SIGN = {"addr", "money", "time", "no", "firm", "abbr", "city", "abbr", "num", "num", "num"};
  static final Pattern WORD2 = P("\\p{L}{2,}");
  /** Плейсхолдер слота. «N1» не годится: замер на самой модели показал, что в ru→pt она съедает
   *  букву и оставляет «1» (выживает 1 раз из 6), из-за чего слот теряется и уезжает в конец фразы.
   *  «XQ1» выживает 6 из 6 в обе стороны. Проверка: tools/placeholder_probe.py */
  static String ph(int i) { return "XQ" + i; }
  // Чистим и старый формат «N1»: он остался внутри записей learned.json, накопленных до смены.
  // И «СК1» — плейсхолдер, который переводчик переписал кириллицей (см. lookalike).
  static final Pattern PH_LEFT = P("\\b(?:XQ|N|[XХ][QК]|СК)\\d+\\b");
  /** Плейсхолдер, переписанный переводчиком кириллицей: рядом с другим плейсхолдером или знаком
   *  градуса «XQ3» выходит как «СК3» («feche a XQ3 XQ4 tampa» → «закройте СК3 СК4 крышка»), и
   *  число уезжало в конец фразы, а «СК3» оставалось в переводе. */
  static Pattern lookalike(String ph) { return P("(?<![\\p{L}\\p{N}])(?:[XХ][QК]|[СC][КK])" + ph.substring(2) + "(?!\\p{N})"); }
  /** Плейсхолдер только старого формата «N1» — признак записи, устаревшей после смены формата.
   *  Раньше проверка ловила и нынешний «XQ1», и выученные записи с числом в переводе выбрасывались
   *  как устаревшие при каждом запуске: `record` пишет маскированный текст, то есть с плейсхолдером,
   *  и это нормальное состояние записи, а не порча. `PH_LEFT` выше остаётся для очистки перевода. */
  static final Pattern PH_OLD = P("\\bN\\d+\\b");
  public static boolean hasPlaceholder(String s) { return s != null && PH_OLD.matcher(s).find(); }
  /** После родового слова адреса это не имя улицы, а продолжение фразы — на таком слове имя обрывается. */
  static final Set<String> ADDR_STOP = new HashSet<>(Arrays.asList(
    "está", "esta", "essa", "é", "fica", "ficam", "tem", "era", "foi", "não", "para", "com", "sem",
    "muito", "muita", "toda", "todo", "onde", "aqui", "ali", "aquela", "aquele", "por", "que",
    "até", "ate", "então", "entao", "depois", "antes", "perto", "longe", "agora", "hoje", "amanhã",
    "ontem", "também", "tambem", "mas", "ainda", "apenas", "número", "numero", "fica", "vai", "pode",
    "это", "эта", "этот", "была", "был", "было", "есть", "очень", "здесь", "там", "где", "куда",
    "какая", "какой", "рядом", "около", "потом", "затем", "тоже", "уже", "надо", "нужно",
    "сразу", "скоро", "сейчас", "сегодня", "завтра", "вчера", "возле", "напротив", "дом", "номер"));
  /** Обрезает имя улицы на первом служебном слове; пустой результат — адреса нет. */
  /** Первое значимое слово названия — с заглавной («das Flores» → Flores). */
  static boolean properName(String name) {
    for (String w : name.trim().split("\\s+")) {
      if (w.matches("(?i)d[aeo]s?")) continue;
      return !w.isEmpty() && Character.isUpperCase(w.codePointAt(0));
    }
    return false;
  }

  /** Вывеска прописными → текст, на котором учили переводчик: обычное слово языка — строчными,
   *  необычное (имя, название) — с заглавной, короткое служебное («DE», «E») — строчными, первое
   *  слово — с заглавной. Прежний unshout делал с заглавной каждое слово длиннее трёх букв, и
   *  переводчик и правило адресов принимали прилагательные за имена. Слова не целиком прописные
   *  и короткие не служебные («CEP», «RG») не трогаются. common — «обычное ли слово» (WordList). */
  public static String unshoutSign(String s, java.util.function.Predicate<String> common) { return unshoutSign(s, common, null); }
  /** keep — марки снимка (brands): их слова остаются как на снимке, «CEPÊRA» не становится «Cepêra». */
  public static String unshoutSign(String s, java.util.function.Predicate<String> common, Set<String> keep) {
    StringBuilder b = new StringBuilder(s.length()); boolean first = true;
    // название фирмы остаётся как на этикетке: «ICPA CEPÊRA LTDA» иначе стало бы «Icpa Cepêra Ltda»
    List<int[]> firms = new ArrayList<>(); Matcher fm = FIRM.matcher(s);
    while (fm.find()) firms.add(new int[]{fm.start(), fm.end()});
    int at = 0;
    for (String w : s.split("(?<=\\s)|(?=\\s)")) {
      int from = at; at += w.length();
      boolean firm = false; for (int[] f : firms) if (from < f[1] && at > f[0]) firm = true;   // «LTDA.» — со знаком
      String core = w.replaceAll("[^\\p{L}]", "");
      if (firm || (keep != null && !core.isEmpty() && keep.contains(OcrWords.plain(core)))) { b.append(w); if (!core.isEmpty()) first = false; continue; }
      boolean caps = !core.isEmpty() && core.equals(core.toUpperCase(Locale.ROOT)) && !core.equals(core.toLowerCase(Locale.ROOT));
      String low = core.toLowerCase(Locale.ROOT);
      // короткое: служебное («DE», «E») или обычное трёхбуквенное («RUA») — строчными;
      // остальное короткое («CEP», «RG», «BR») — аббревиатура, как есть
      if (caps && (core.length() > 3 || OcrCore.CONT.contains(low) || (core.length() == 3 && common.test(low)))) {
        int i = 0; while (i < w.length() && !Character.isLetter(w.charAt(i))) i++;
        String rest = w.substring(i).toLowerCase(Locale.ROOT);
        boolean cap = first || (core.length() > 3 && !common.test(low));
        b.append(w, 0, i).append(cap && !rest.isEmpty() ? Character.toUpperCase(rest.charAt(0)) + rest.substring(1) : rest);
      } else b.append(w);
      if (!core.isEmpty()) first = false;
    }
    return b.toString();
  }

  static String addrName(String name) {
    StringBuilder b = new StringBuilder();
    for (String w : name.trim().split("\\s+")) {
      String k = w.toLowerCase(Locale.ROOT).replaceAll("[.,!?;:]+$", "");
      if (ADDR_STOP.contains(k)) break;
      if (b.length() > 0) b.append(' ');
      b.append(w);
    }
    return b.toString();
  }


  // ---- числительные словами -> цифры (pt-BR, ru), до 999 999; применяется до маскирования
  static final Map<String, Integer> PTN = new HashMap<>(), RUN = new HashMap<>();
  static {
    Object[][] pt = {{"zero",0},{"um",1},{"uma",1},{"dois",2},{"duas",2},{"três",3},{"tres",3},{"quatro",4},{"cinco",5},{"seis",6},{"sete",7},{"oito",8},{"nove",9},{"dez",10},{"onze",11},{"doze",12},{"treze",13},{"catorze",14},{"quatorze",14},{"quinze",15},{"dezesseis",16},{"dezassete",17},{"dezessete",17},{"dezoito",18},{"dezenove",19},{"dezanove",19},{"vinte",20},{"trinta",30},{"quarenta",40},{"cinquenta",50},{"sessenta",60},{"setenta",70},{"oitenta",80},{"noventa",90},{"cem",100},{"cento",100},{"duzentos",200},{"duzentas",200},{"trezentos",300},{"trezentas",300},{"quatrocentos",400},{"quatrocentas",400},{"quinhentos",500},{"quinhentas",500},{"seiscentos",600},{"seiscentas",600},{"setecentos",700},{"setecentas",700},{"oitocentos",800},{"oitocentas",800},{"novecentos",900},{"novecentas",900},{"mil",1000}};
    Object[][] ru = {{"ноль",0},{"один",1},{"одна",1},{"одно",1},{"два",2},{"две",2},{"три",3},{"четыре",4},{"пять",5},{"шесть",6},{"семь",7},{"восемь",8},{"девять",9},{"десять",10},{"одиннадцать",11},{"двенадцать",12},{"тринадцать",13},{"четырнадцать",14},{"пятнадцать",15},{"шестнадцать",16},{"семнадцать",17},{"восемнадцать",18},{"девятнадцать",19},{"двадцать",20},{"тридцать",30},{"сорок",40},{"пятьдесят",50},{"шестьдесят",60},{"семьдесят",70},{"восемьдесят",80},{"девяносто",90},{"сто",100},{"двести",200},{"триста",300},{"четыреста",400},{"пятьсот",500},{"шестьсот",600},{"семьсот",700},{"восемьсот",800},{"девятьсот",900},{"тысяча",1000},{"тысячи",1000},{"тысяч",1000}};
    for (Object[] x : pt) PTN.put((String) x[0], (Integer) x[1]);
    for (Object[] x : ru) RUN.put((String) x[0], (Integer) x[1]);
  }
  static int order(int v) { return v >= 1000 ? 3 : v >= 100 ? 2 : v >= 20 ? 1 : 0; }
  /** «trinta e sete reais e cinquenta centavos» -> «R$ 37,50»; «quinze e trinta» -> «15:30»; «триста рублей» -> «300 рублей».
   *  Внутри группы разряды должны убывать (cento e vinte e cinco), иначе группа закрывается (quinze | trinta). Одиночные um/uma/один не трогаем. */
  public static String numWordsToDigits(String text, String lang) {
    Map<String, Integer> N = lang.equals("pt") ? PTN : RUN; boolean pt = lang.equals("pt");
    String[] w = text.split("(?<=\\s)|(?=\\s)");
    StringBuilder out = new StringBuilder(); int i = 0;
    while (i < w.length) {
      if (w[i].trim().isEmpty() || key(w[i]) == null || !N.containsKey(key(w[i]))) { out.append(w[i]); i++; continue; }
      long total = 0, cur = 0; int lastVal = -1, words = 0, jEnd = i, j = i; boolean afterMil = false;
      while (j < w.length) {
        String tok = w[j]; if (tok.trim().isEmpty()) { j++; continue; }
        String k = key(tok); Integer v = k == null ? null : N.get(k);
        if (v == null) { if (pt && k != null && k.equals("e") && words > 0 && j + 2 < w.length && key(w[j + 2]) != null && N.containsKey(key(w[j + 2]))) { j++; continue; } break; }
        boolean ok = words == 0 || v == 1000 || afterMil || (lastVal != 1000 && order(v) < order(lastVal) && !(lastVal < 20 && lastVal > 9));
        if (!ok) break;
        if (v == 1000) { cur = (cur == 0 ? 1 : cur) * 1000; total += cur; cur = 0; afterMil = true; } else { cur += v; afterMil = false; }
        words++; lastVal = v; jEnd = j + 1; j++;
        if (!tok.substring(k.length()).isEmpty()) break;   // число с пунктуацией завершает группу
      }
      total += cur;
      String first = w[i].trim().toLowerCase(Locale.ROOT);
      boolean lone = words == 1 && (first.equals("um") || first.equals("uma") || first.equals("один") || first.equals("одна") || first.equals("одно"));
      if (words > 0 && !lone) { String last = w[jEnd - 1]; String punct = last.substring(key(last).length()); out.append(total).append(punct); i = jEnd; }
      else { out.append(w[i]); i++; }
    }
    String r = out.toString();
    if (pt) r = r.replaceAll("(?i)\\b(\\d+) reais? e (\\d{1,2})(?: centavos?)?\\b(?! mil)", "R\\$ $1,$2").replaceAll("(?i)\\b(\\d{1,2}) e (\\d{2})\\b(?! (?:reais|real|centavos|mil))", "$1:$2");
    else r = r.replaceAll("\\b(\\d{1,2}) (\\d{2})\\b(?= |$|[.,!?])", "$1:$2");
    return r;
  }
  static String key(String tok) { String t = tok.trim(); if (t.isEmpty()) return null; String k = t.toLowerCase(Locale.ROOT).replaceAll("[.,!?;:]+$", ""); return k.isEmpty() ? null : k; }
  public static Masked mask(String text, String lang) { return mask(text, lang, new ArrayList<String[]>()); }
  /** Нумерация слотов продолжается с уже занятых — список своих слов проходит раньше и занимает первые. */
  public static Masked mask(String text, String lang, List<String[]> existing) { return mask(text, lang, existing, false); }
  /** sign — текст вывески (снимок). Название улицы только с заглавной: у вывески регистр осмыслен
   *  после unshoutSign (обычные слова строчные, имена с заглавной), и без этого «RODOVIA ESTREITA E
   *  EXTREMAMENTE SINUOSA» уходило в перевод как «Естрейта и чрезвычайно извилистая дорога» — прилагательное
   *  маскировалось как название дороги. Для речи заглавная не требуется (см. PT[0]). И маски вывески
   *  (PT_SIGN): номер дома, температура, цифры одним куском. */
  public static Masked mask(String text, String lang, List<String[]> existing, boolean sign) { return mask(text, lang, existing, sign, null); }
  /** brands — марки снимка (TextRules.brands): их слова в тексте вывески — как на снимке, переводчику не показываются. */
  public static Masked mask(String text, String lang, List<String[]> existing, boolean sign, Set<String> brands) {
    Pattern[] ps = lang.equals("pt") ? (sign ? PT_SIGN : PT) : RU; String[] kinds = ps == PT_SIGN ? KIND_SIGN : KIND;
    String t = numWordsToDigits(text, lang); List<String[]> slots = new ArrayList<>(existing);
    for (int k = 0; k < ps.length; k++) {
      Matcher m = ps[k].matcher(t); StringBuffer sb = new StringBuffer();
      while (m.find()) {
        String ph = ph(slots.size() + 1);
        if (kinds[k].equals("addr") && m.groupCount() >= 1 && m.group(1) != null) {
          String name = addrName(m.group(1));
          if (name.isEmpty() || (sign && !properName(name))) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
          String head = m.group().substring(0, m.start(1) - m.start());
          String tail = m.group(1).substring(name.length());          // то, что отрезали, возвращаем в текст
          // У вывески и родовое слово — в слоте: переводчик на адресе выдумывал («Av. Lindolpho /
          // Augusto da Costa» → «Руа Аугуста Коста»), а родовое слово переводит сама подстановка
          // (Translit.address: «проспект Линдолфу Аугусту да Коста»). У речи — как было.
          slots.add(new String[]{ph, sign ? head.trim() + " " + name : name, kinds[k]});
          m.appendReplacement(sb, Matcher.quoteReplacement((sign ? "" : head) + ph + tail));
        } else {
          slots.add(new String[]{ph, m.group(), kinds[k]});
          m.appendReplacement(sb, Matcher.quoteReplacement(ph));
        }
      }
      m.appendTail(sb); t = sb.toString();
    }
    if (sign && brands != null && !brands.isEmpty()) {               // марка снимка — как на снимке
      Matcher m = BRAND_WORD.matcher(t); StringBuffer sb = new StringBuffer();
      while (m.find()) {
        if (!brands.contains(OcrWords.plain(m.group()))) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
        String ph = ph(slots.size() + 1); slots.add(new String[]{ph, m.group(), "brand"});
        m.appendReplacement(sb, Matcher.quoteReplacement(ph));
      }
      m.appendTail(sb); t = sb.toString();
    }
    Masked out = new Masked(t); out.slots.addAll(slots); return out;
  }
  /** Есть ли что переводить в тексте с плейсхолдерами: слово от двух букв помимо самих плейсхолдеров.
   *  «XQ1 /XQ2: XQ3» (строка «SP /CNPJ: 62.162.243/0003-45») — нечего, и переводчик, получив её,
   *  выдумывал «Модель:». */
  public static boolean hasWords(String masked) { return WORD2.matcher(PH_LEFT.matcher(masked).replaceAll(" ")).find(); }
  /** Плейсхолдеры обратно; деньги — в валютную форму целевого языка; потерянные MT слоты дописываются в конец. */
  public static String unmask(String translated, Masked m, String tgt) {
    String t = translated; List<String> missing = new ArrayList<>();
    for (String[] s : m.slots) {
      String r = render(s[1], s[2], tgt); Pattern p = P("\\b" + s[0] + "\\b");
      if (p.matcher(t).find()) { t = p.matcher(t).replaceFirst(Matcher.quoteReplacement(r)); continue; }
      Matcher q = s[0].startsWith("XQ") ? lookalike(s[0]).matcher(t) : null;
      if (q != null && q.find()) t = q.replaceFirst(Matcher.quoteReplacement(r)); else missing.add(r);
    }
    t = PH_LEFT.matcher(t).replaceAll("").replaceAll("\\s{2,}", " ").trim();
    for (String r : missing) t = t + " " + r;
    return t.trim();
  }
  /** Города с устоявшимся русским названием, которое транскрипция не даёт («Рио-ди-Жанейру»). */
  static final Map<String, String> CITY_RU = new HashMap<>();
  static {
    String[] c = {"Rio de Janeiro", "Рио-де-Жанейро", "Goiânia", "Гояния", "João Pessoa", "Жуан-Песоа", "Cuiabá", "Куяба"};
    for (int i = 0; i < c.length; i += 2) CITY_RU.put(c[i], c[i + 1]);
  }
  static final Pattern CITY_UF = P("^(.+?)[^\\S\\n]?[-/][^\\S\\n]?(" + STATES + ")?$");
  /** Город со штатом — «Монти-Алту (Сан-Паулу)»: через дефис, как в оригинале, выходило одно длинное
   *  имя «Монти-Алту-Сан-Паулу». Город, названный как штат, — один раз («São Paulo/SP» — «Сан-Паулу»).
   *  Дефис в конце строки, когда штат на следующей, — запятой: «Монти-Алту,» / «Сан-Паулу /ИНН: …». */
  static String cityRu(String s) {
    Matcher m = CITY_UF.matcher(s.trim());
    if (!m.matches()) return cityName(s.trim());
    String c = cityName(m.group(1)), st = m.group(2) == null ? null : STATE_RU.get(m.group(2));
    return st == null ? c + "," : st.equals(c) ? c : c + " (" + st + ")";
  }
  /** Фирма по-русски: форма собственности впереди, название латиницей в кавычках, как на этикетке.
   *  LTDA — «ООО», S.A. и S/A — «АО»; у EIRELI русского соответствия нет — как есть. */
  static String firmRu(String s) {
    Matcher m = FIRM_FORM.matcher(s);
    if (!m.find()) return s;
    String name = s.substring(0, m.start()), f = m.group(1).toUpperCase(Locale.ROOT);
    if (f.equals("LTDA")) return "ООО «" + name + "»";
    if (f.startsWith("S")) return "АО «" + name + "»";
    return s;
  }
  static String cityName(String s) { return CITY_RU.getOrDefault(s, Translit.ptToRu(s).replaceAll("\\s+", "-")); }

  static String render(String orig, String kind, String tgt) {
    if (kind.startsWith("name:")) return kind.substring(5);   // имя из списка своих слов — уже на нужном языке
    if (kind.equals("addr")) return Translit.address(orig, tgt);  // адрес не переводим, но пишем алфавитом цели
    if (kind.equals("no")) { Matcher d = P("\\d+").matcher(orig); return tgt.equals("ru") && d.find() ? "№ " + d.group() : orig; }
    if (kind.equals("city")) return tgt.equals("ru") ? cityRu(orig) : orig;
    if (kind.equals("firm")) return tgt.equals("ru") ? firmRu(orig) : orig;
    if (kind.equals("abbr")) {
      if (!tgt.equals("ru")) return orig;
      String k = orig.toUpperCase(Locale.ROOT), r = ABBR_RU.get(k);
      return r != null ? r : STATE_RU.getOrDefault(k, orig);
    }
    if (!kind.equals("money")) return orig;
    Matcher n = P("\\d+(?:[.,]\\d+)?").matcher(orig); if (!n.find()) return orig; String num = n.group();
    if (tgt.equals("ru")) { if (orig.contains("R$") || orig.matches(".*rea(l|is).*")) return num + " реалов"; if (orig.contains("centavos")) return num + " сентаво"; return orig; }
    if (orig.matches(".*рубл.*")) return num + " rublos"; if (orig.matches(".*реал.*")) return "R$ " + num; if (orig.contains("евро")) return num + " euros"; if (orig.contains("доллар")) return num + " dólares"; return orig;
  }

  static final String[][] LEX = {{"comboio","trem"},{"autocarro","ônibus"},{"telemóvel","celular"},{"casa de banho","banheiro"},{"casa-de-banho","banheiro"},{"ecrã","tela"},{"pequeno-almoço","café da manhã"},{"pequeno almoço","café da manhã"},{"sumo","suco"},{"rapariga","garota"},{"raparigas","garotas"},{"bicha","fila"},{"multibanco","caixa eletrônico"},{"levantar dinheiro","sacar dinheiro"},{"retirar dinheiro","sacar dinheiro"},{"bilhete de ônibus","passagem de ônibus"},{"bilhete de autocarro","passagem de ônibus"},{"bilhete","passagem"},{"frigorífico","geladeira"},{"travão","freio"},{"peão","pedestre"},{"ténis","tênis"},{"menu","cardápio"},{"ementa","cardápio"}};
  static final Pattern PROG = P("(?i)\\b(est(?:ou|ás|á|amos|ão|ava|avam|ive))\\s+a\\s+(\\p{L}+?)(ar|er|ir)\\b");
  static final Pattern ENCL = P("\\b(\\p{L}{2,})-(me|te|se|nos|lhe|lhes)\\b");
  /** «ter de» -> «ter que» только перед инфинитивом: «Tem de outra cor?» и «Não tem de quê» трогать нельзя. */
  static final Pattern TERDE = P("(?i)\\b(tenho|tens|tem|temos|têm)\\s+de\\s+(?=(\\p{L}*(ar|er|ir)|pôr)\\b)");
  /** pt-PT → pt-BR: лексикон, «estar a + inf» → герундий, энклиза → проклиза, согласование артикля у passagem. */
  public static String toBrazilian(String pt) {
    String t = pt;
    for (String[] p : LEX) { Matcher m = P("(?i)\\b" + Pattern.quote(p[0]) + "\\b").matcher(t); StringBuffer sb = new StringBuffer();
      while (m.find()) { String r = p[1]; if (Character.isUpperCase(m.group().charAt(0))) r = Character.toUpperCase(r.charAt(0)) + r.substring(1); m.appendReplacement(sb, Matcher.quoteReplacement(r)); } m.appendTail(sb); t = sb.toString(); }
    Matcher d = TERDE.matcher(t); StringBuffer sb = new StringBuffer();
    while (d.find()) { String v = d.group(1); if (v.equalsIgnoreCase("tens")) v = v.charAt(0) == 'T' ? "Tem" : "tem"; d.appendReplacement(sb, Matcher.quoteReplacement(v + " que ")); } d.appendTail(sb); t = sb.toString();
    Matcher g = PROG.matcher(t); sb = new StringBuffer();
    while (g.find()) { String suf = g.group(3).equalsIgnoreCase("ar") ? "ando" : g.group(3).equalsIgnoreCase("er") ? "endo" : "indo"; g.appendReplacement(sb, Matcher.quoteReplacement(g.group(1) + " " + g.group(2) + suf)); } g.appendTail(sb); t = sb.toString();
    Matcher e = ENCL.matcher(t); sb = new StringBuffer();
    while (e.find()) {                                                  // в начале предложения глагол с большой буквы — перенос клитики сломал бы порядок слов
      boolean sentenceStart = Character.isUpperCase(e.group(1).charAt(0));
      e.appendReplacement(sb, Matcher.quoteReplacement(sentenceStart ? e.group() : e.group(2) + " " + e.group(1)));
    } e.appendTail(sb); t = sb.toString();
    return agree(t);
  }
  static final String[] MASC_W = {"trem","trens","ônibus","celular","celulares","banheiro","banheiros","suco","sucos","cardápio","cardápios","freio","freios","pedestre","pedestres","tênis"};
  static final String[] FEM_W = {"tela","telas","garota","garotas","fila","filas","geladeira","geladeiras","passagem","passagens"};
  static final String[][] DET = {{"a","o"},{"as","os"},{"uma","um"},{"umas","uns"},{"da","do"},{"das","dos"},{"na","no"},{"nas","nos"},{"à","ao"},{"às","aos"},{"pela","pelo"},{"pelas","pelos"},{"esta","este"},{"estas","estes"},{"essa","esse"},{"essas","esses"},{"aquela","aquele"},{"aquelas","aqueles"},{"minha","meu"},{"minhas","meus"},{"tua","teu"},{"tuas","teus"},{"sua","seu"},{"suas","seus"},{"nossa","nosso"},{"nossas","nossos"},{"outra","outro"},{"outras","outros"},{"alguma","algum"},{"nenhuma","nenhum"},{"toda","todo"},{"todas","todos"},{"primeira","primeiro"},{"mesma","mesmo"}};
  /** Замена из словаря может поменять род (casa de banho ж. -> banheiro м.) — согласуем артикль перед словом. */
  static String agree(String t) {
    for (String w : MASC_W) t = swapDet(t, w, true);
    for (String w : FEM_W) t = swapDet(t, w, false);
    return t;
  }
  static String swapDet(String t, String word, boolean toMasc) {
    for (String[] p : DET) {
      String from = toMasc ? p[0] : p[1], to = toMasc ? p[1] : p[0];
      Matcher m = P("(?i)\\b" + from + "(\\s+" + word + ")\\b").matcher(t); StringBuffer sb = new StringBuffer();
      while (m.find()) { String r = to; if (Character.isUpperCase(m.group().charAt(0))) r = Character.toUpperCase(r.charAt(0)) + r.substring(1); m.appendReplacement(sb, Matcher.quoteReplacement(r + m.group(1))); }
      m.appendTail(sb); t = sb.toString();
    }
    return t;
  }
}
