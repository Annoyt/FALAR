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
  /** Плейсхолдер слота. «N1» не годится: замер на самой модели показал, что в ru→pt она съедает
   *  букву и оставляет «1» (выживает 1 раз из 6), из-за чего слот теряется и уезжает в конец фразы.
   *  «XQ1» выживает 6 из 6 в обе стороны. Проверка: tools/placeholder_probe.py */
  static String ph(int i) { return "XQ" + i; }
  // Чистим и старый формат «N1»: он остался внутри записей learned.json, накопленных до смены.
  static final Pattern PH_LEFT = P("\\b(?:XQ|N)\\d+\\b");
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
  public static Masked mask(String text, String lang, List<String[]> existing) {
    Pattern[] ps = lang.equals("pt") ? PT : RU; String t = numWordsToDigits(text, lang); List<String[]> slots = new ArrayList<>(existing);
    for (int k = 0; k < ps.length; k++) {
      Matcher m = ps[k].matcher(t); StringBuffer sb = new StringBuffer();
      while (m.find()) {
        String ph = ph(slots.size() + 1);
        if (KIND[k].equals("addr") && m.groupCount() >= 1 && m.group(1) != null) {
          String name = addrName(m.group(1));
          if (name.isEmpty()) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
          String head = m.group().substring(0, m.start(1) - m.start());
          String tail = m.group(1).substring(name.length());          // то, что отрезали, возвращаем в текст
          slots.add(new String[]{ph, name, KIND[k]});
          m.appendReplacement(sb, Matcher.quoteReplacement(head + ph + tail));
        } else {
          slots.add(new String[]{ph, m.group(), KIND[k]});
          m.appendReplacement(sb, Matcher.quoteReplacement(ph));
        }
      }
      m.appendTail(sb); t = sb.toString();
    }
    Masked out = new Masked(t); out.slots.addAll(slots); return out;
  }
  /** Плейсхолдеры обратно; деньги — в валютную форму целевого языка; потерянные MT слоты дописываются в конец. */
  public static String unmask(String translated, Masked m, String tgt) {
    String t = translated; List<String> missing = new ArrayList<>();
    for (String[] s : m.slots) {
      String r = render(s[1], s[2], tgt); Pattern p = P("\\b" + s[0] + "\\b");
      if (p.matcher(t).find()) t = p.matcher(t).replaceFirst(Matcher.quoteReplacement(r)); else missing.add(r);
    }
    t = PH_LEFT.matcher(t).replaceAll("").replaceAll("\\s{2,}", " ").trim();
    for (String r : missing) t = t + " " + r;
    return t.trim();
  }
  static String render(String orig, String kind, String tgt) {
    if (kind.startsWith("name:")) return kind.substring(5);   // имя из списка своих слов — уже на нужном языке
    if (kind.equals("addr")) return Translit.address(orig, tgt);  // адрес не переводим, но пишем алфавитом цели
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
