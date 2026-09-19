package dev.agenttranslator;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/**
 * Практическая транскрипция имён собственных в обе стороны.
 * Нужна там, где имя нельзя переводить: «Fogo de Chão» должно стать «Фогу ди Шан»,
 * а не «Половой огонь», и не остаться латиницей посреди русской фразы.
 * Правила проверены на наборе настоящих названий, см. tools/translit_pt_ru.py.
 */
public class Translit {
  static final String VOWELS = "aeiouáéíóúâêôãõàäëïöü";
  static final String RU_VOWELS = "аеёиоуыэюя";

  // Порядок важен: диграфы проверяются раньше одиночных букв.
  static final String[][] DI = {
    {"lh", "ль"}, {"nh", "нь"}, {"ch", "ш"},
    {"ss", "с"}, {"rr", "рр"}, {"sc", "с"}, {"sç", "с"}, {"xc", "с"},
    {"ães", "айнс"}, {"ões", "ойнс"}, {"ão", "ан"}, {"ãe", "айн"}, {"õe", "ойн"},
    {"ai", "ай"}, {"ei", "ей"}, {"oi", "ой"}, {"ui", "уй"}, {"ái", "ай"}, {"éi", "ей"}, {"ói", "ой"},
    {"qu", "к"}, {"gu", "г"},
  };
  static final Map<Character, String> ONE = new HashMap<>();
  static {
    String[] p = {"a","а","á","а","à","а","â","а","b","б","c","к","ç","с","d","д","e","е","é","э","ê","е",
      "f","ф","g","г","h","","i","и","í","и","j","ж","k","к","l","л","m","м","n","н","o","о","ó","о","ô","о",
      "õ","он","p","п","q","к","r","р","s","с","t","т","u","у","ú","у","ü","у","v","в","w","в","x","ш","y","и","z","з"};
    for (int i = 0; i < p.length; i += 2) ONE.put(p[i].charAt(0), p[i + 1]);
  }
  static final Map<String, String> EXC = new HashMap<>();
  static {
    String[] p = {"rio","Рио","de","ди","do","ду","da","да","dos","дус","das","дас","e","и",
      "santo","Санту","santa","Санта","são","Сан","rua","Руа","avenida","Авенида","praça","Праса",
      "largo","Ларгу","palace","Палас","hotel","Отель","sé","Сэ"};
    for (int i = 0; i < p.length; i += 2) EXC.put(p[i], p[i + 1]);
  }

  static boolean isVowel(char c) { return VOWELS.indexOf(c) >= 0; }

  static String ptWord(String w) {
    String low = Normalizer.normalize(w, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    String e = EXC.get(low); if (e != null) return e;
    boolean accent = low.matches(".*[áéíóúâêô].*");
    StringBuilder out = new StringBuilder();
    int i = 0, n = low.length();
    outer:
    while (i < n) {
      for (String[] d : DI) {
        if (low.startsWith(d[0], i)) {
          if (d[0].equals("qu") || d[0].equals("gu")) {
            char nx = i + 2 < n ? low.charAt(i + 2) : ' ';
            out.append(nx == 'e' || nx == 'i' ? d[1] : d[1] + "у");
          } else out.append(d[1]);
          i += d[0].length(); continue outer;
        }
      }
      char c = low.charAt(i), prev = i > 0 ? low.charAt(i - 1) : ' ', nx = i + 1 < n ? low.charAt(i + 1) : ' ';
      if (c == 'c') out.append(nx == 'e' || nx == 'i' || nx == 'é' || nx == 'í' ? "с" : "к");
      else if (c == 'g') out.append(nx == 'e' || nx == 'i' || nx == 'é' || nx == 'í' ? "ж" : "г");
      else if (c == 's') out.append(isVowel(prev) && isVowel(nx) ? "з" : "с");
      else if (c == 'x') out.append("ш");
      else if (c == 'o' && i == n - 1 && !accent) out.append("у");
      else if (c == 'o' && i == n - 2 && nx == 's' && !accent) out.append("у");
      else if (c == 'e' && i == n - 1 && !accent && n > 2) out.append("и");
      else if (c == 'm' && !isVowel(nx)) out.append("н");
      else if (c == 'ã') out.append(i == n - 1 ? "а" : "ан");
      else out.append(ONE.containsKey(c) ? ONE.get(c) : String.valueOf(c));
      i++;
    }
    String r = out.toString()
      .replace("ьа", "ья").replace("ьу", "ью").replace("ьэ", "ье")
      .replaceAll("нн+", "нн");
    return Character.isUpperCase(w.charAt(0)) ? Character.toUpperCase(r.charAt(0)) + r.substring(1) : r;
  }

  static final String[][] RU_DI = {{"щ","schtch"},{"ш","ch"},{"ч","tch"},{"ж","j"},{"х","kh"},{"ц","ts"},
    {"я","ia"},{"ю","iu"},{"ё","io"},{"й","i"},{"ы","i"},{"э","e"},{"ъ",""},{"ь",""}};
  static final Map<Character, String> RU_ONE = new HashMap<>();
  static {
    String[] p = {"а","a","б","b","в","v","г","g","д","d","е","e","з","z","и","i","к","k","л","l","м","m",
      "н","n","о","o","п","p","р","r","с","s","т","t","у","u","ф","f"};
    for (int i = 0; i < p.length; i += 2) RU_ONE.put(p[i].charAt(0), p[i + 1]);
  }

  static String ruWord(String w) {
    String low = Normalizer.normalize(w, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder();
    int i = 0, n = low.length();
    outer:
    while (i < n) {
      for (String[] d : RU_DI) if (low.startsWith(d[0], i)) { out.append(d[1]); i += d[0].length(); continue outer; }
      char c = low.charAt(i), prev = i > 0 ? low.charAt(i - 1) : ' ', nx = i + 1 < n ? low.charAt(i + 1) : ' ';
      if (c == 'с' && RU_VOWELS.indexOf(prev) >= 0 && RU_VOWELS.indexOf(nx) >= 0) out.append("ss");
      else if (c == 'е' && (i == 0 || RU_VOWELS.indexOf(prev) >= 0)) out.append("ie");
      else out.append(RU_ONE.containsKey(c) ? RU_ONE.get(c) : String.valueOf(c));
      i++;
    }
    String r = out.toString();
    if (r.isEmpty()) return r;
    return Character.isUpperCase(w.charAt(0)) ? Character.toUpperCase(r.charAt(0)) + r.substring(1) : r;
  }

  static final Pattern SPLIT = Pattern.compile("(\\p{L}+)");

  static String apply(String text, boolean toRu) {
    Matcher m = SPLIT.matcher(Normalizer.normalize(text, Normalizer.Form.NFC));
    StringBuffer sb = new StringBuffer();
    while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(toRu ? ptWord(m.group(1)) : ruWord(m.group(1))));
    m.appendTail(sb);
    return sb.toString();
  }
  /** Португальское имя кириллицей. */
  public static String ptToRu(String s) { return apply(s, true); }
  /** Русское имя латиницей, чтобы португальский синтез его прочитал. */
  public static String ruToPt(String s) { return apply(s, false); }
  /** Автоматически: определяем сторону по алфавиту. */
  public static String auto(String s) { return s.matches(".*[А-Яа-яЁё].*") ? ruToPt(s) : ptToRu(s); }

  // Родовое слово адреса переводится, а не транскрибируется: «Rua Augusta» -> «улица Аугуста».
  static final String[][] HEAD_PT_RU = {{"rua", "улица"}, {"avenida", "проспект"}, {"av.", "проспект"},
    {"av", "проспект"}, {"praça", "площадь"}, {"praca", "площадь"}, {"travessa", "переулок"},
    {"alameda", "аллея"}, {"estrada", "шоссе"}, {"rodovia", "шоссе"}, {"largo", "площадь"}};
  static final String[][] HEAD_RU_PT = {{"улица", "Rua"}, {"улице", "Rua"}, {"улицу", "Rua"}, {"ул.", "Rua"},
    {"проспект", "Avenida"}, {"проспекте", "Avenida"}, {"пр.", "Avenida"}, {"площадь", "Praça"},
    {"площади", "Praça"}, {"переулок", "Travessa"}, {"шоссе", "Estrada"}, {"аллея", "Alameda"}};

  /** Адрес целиком: родовое слово переводим, имя транскрибируем; алфавит — как у целевого языка. */
  public static String address(String s, String tgt) {
    boolean cyr = s.matches(".*[А-Яа-яЁё].*");
    if (tgt.equals("ru") == cyr) return s;                      // алфавит уже нужный — не трогаем
    String[] w = s.trim().split("\\s+");
    if (w.length == 0) return s;
    String[][] heads = cyr ? HEAD_RU_PT : HEAD_PT_RU;
    StringBuilder b = new StringBuilder();
    int start = 0;
    String first = w[0].toLowerCase(Locale.ROOT).replaceAll("[,;:]$", "");
    for (String[] h : heads) if (first.equals(h[0])) { b.append(h[1]); start = 1; break; }
    // связку после переведённой головы выбрасываем: «Rua da Consolação» -> «улица Консоласан»,
    // а не «улица да Консоласан»
    if (start == 1 && w.length > 1 && w[1].toLowerCase(Locale.ROOT).matches("(de|da|do|das|dos|e)")) start = 2;
    for (int i = start; i < w.length; i++) {
      if (b.length() > 0) b.append(' ');
      String t = cyr ? ruWord(w[i]) : ptWord(w[i]);
      // распознавание пишет имя улицы строчными — в адресе его принято писать с заглавной,
      // кроме служебных «да/ди/ду», которые в названиях остаются строчными
      boolean particle = t.length() <= 3 && t.matches("(?i)(да|ди|ду|дас|дус|de|da|do|das|dos)");
      if (!particle && !t.isEmpty()) t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
      b.append(t);
    }
    return b.toString();
  }

  // ---- Подсказка для чтения вслух (0.20). Не транскрипция имени, а то, как слово ЗВУЧИТ в Бразилии:
  // «Rua» здесь «хуа», а не «Руа», «de» — «джи», «Brasil» — «бразиу», и над ударной гласной стоит знак.
  // ptWord выше остаётся для имён и адресов; правила проверены на фразах затравки разговорника
  // (tools/translit_say.py, results/2026-09-17-translit-say.md), а не на именах.
  //
  // Примеры (выход кода):  Obrigado -> обрига́ду · Onde fica? -> о́нджи фи́ка? · Brasil -> брази́у ·
  // Rua -> ху́а · gente -> же́нчи · Não entendi -> нау энтенджи́ · Quanto custa? -> куа́нту ку́ста? ·
  // Talvez -> тауве́с · Aceita cartão? -> асе́йта карта́у? · Táxi -> та́кси
  static final String SAY_V = "aeiouáéíóúâêôãõà";
  static final char ACUTE = '́';
  /** Служебные односложные слова: знак ударения им не нужен, а некоторые звучат не по правилу. */
  static final Set<String> SAY_NOMARK = new HashSet<>(Arrays.asList("de", "do", "da", "dos", "das", "e", "o", "a",
      "os", "as", "um", "uns", "em", "com", "que", "se", "me", "te", "lhe", "nos", "por", "ao", "aos", "no", "na", "nas",
      "sem", "ou", "mas", "à", "às"));
  /** Слова, где x читается не как «ш»: правило не угадать, поэтому список. */
  static final Map<String, String> SAY_EXC = new HashMap<>();
  static {
    String[] p = {"táxi", "та́кси", "fixo", "фи́ксу", "sexo", "се́ксу", "tóxico", "то́ксику",
      "próximo", "про́симу", "máximo", "ма́симу", "auxílio", "аузи́лиу", "sintaxe", "синта́кси",
      "texto", "те́сту", "exato", "иза́ту", "wifi", "уай-фай"};
    for (int i = 0; i < p.length; i += 2) SAY_EXC.put(p[i], p[i + 1]);
  }
  static boolean sayV(char c) { return SAY_V.indexOf(c) >= 0; }
  static boolean sayAcc(char c) { return "áéíóúâêô".indexOf(c) >= 0; }
  static char sayBase(char c) {
    switch (c) { case 'á': case 'à': case 'â': case 'ã': return 'a'; case 'é': case 'ê': return 'e'; case 'í': return 'i';
      case 'ó': case 'ô': case 'õ': return 'o'; case 'ú': return 'u'; default: return c; }
  }
  /** Второй гласный сливается с первым в дифтонг: ai, ei, oi, ui, au, eu, ou, iu, ão, ãe, õe. Ударный второй — зияние. */
  static boolean sayDiph(char prev, char c) {
    if (sayAcc(c)) return false;
    char bp = sayBase(prev), bc = sayBase(c);
    if (prev == 'ã' && (c == 'o' || c == 'e')) return true;
    if (prev == 'õ' && c == 'e') return true;
    if (c == 'ã' || c == 'õ') return false;
    return (bc == 'i' || bc == 'u') && bp != bc && "aeiou".indexOf(bp) >= 0;
  }
  /** Начала гласных ядер (слогов): дифтонг — одно ядро; u после q/g перед гласной — скольжение, не ядро. */
  static List<Integer> sayNuclei(String w) {
    List<Integer> n = new ArrayList<>(); int lastV = -2;
    for (int i = 0; i < w.length(); i++) {
      char c = w.charAt(i); if (!sayV(c)) continue;
      char prev = i > 0 ? w.charAt(i - 1) : ' ';
      if (c == 'u' && (prev == 'q' || prev == 'g') && i + 1 < w.length() && sayV(w.charAt(i + 1))) continue;
      if (!(lastV == i - 1 && sayDiph(prev, c))) n.add(i);
      lastV = i;
    }
    return n;
  }
  static int sayNucleusOf(List<Integer> n, int i) { int k = -1; for (int j = 0; j < n.size(); j++) if (n.get(j) <= i) k = j; return k; }
  /** Ударное ядро: письменный акцент; иначе последнее, если слово кончается на i, u, r, l, z, x, n, ã, õ,
   *  ão, ãe, õe, im, um, om (и то же с -s); иначе предпоследнее. Одно ядро — знак не нужен. */
  static int sayStress(String w, List<Integer> n) {
    if (n.size() <= 1 || SAY_NOMARK.contains(w)) return -1;
    for (int i = 0; i < w.length(); i++) if (sayAcc(w.charAt(i))) return sayNucleusOf(n, i);
    String e = w.endsWith("s") && w.length() > 1 ? w.substring(0, w.length() - 1) : w;
    char last = e.charAt(e.length() - 1);
    boolean oxy = "iurlzxnãõ".indexOf(last) >= 0 || e.endsWith("ão") || e.endsWith("ãe") || e.endsWith("õe")
        || e.endsWith("im") || e.endsWith("um") || e.endsWith("om");
    return oxy ? n.size() - 1 : n.size() - 2;
  }
  /** Одно слово (без пунктуации, строчными). */
  static String sayCore(String w) {
    String exc = SAY_EXC.get(w); if (exc != null) return exc;
    List<Integer> nuc = sayNuclei(w); int stress = sayStress(w, nuc);
    StringBuilder out = new StringBuilder(); int n = w.length();
    for (int i = 0; i < n; i++) {
      char c = w.charAt(i), prev = i > 0 ? w.charAt(i - 1) : ' ', nx = i + 1 < n ? w.charAt(i + 1) : ' ', nx2 = i + 2 < n ? w.charAt(i + 2) : ' ';
      boolean finalE = (c == 'e') && (i == n - 1 || (nx == 's' && i == n - 2));
      boolean nucleus = nuc.contains(i);
      int mark = nucleus && sayNucleusOf(nuc, i) == stress ? out.length() : -1;   // куда ставить знак: после первой гласной буквы
      String piece;
      if (sayV(c)) {
        boolean glide = c == 'u' && (prev == 'q' || prev == 'g') && sayV(nx);
        boolean second = !nucleus && !glide;                       // второй элемент дифтонга
        if (glide) piece = (sayBase(nx) == 'e' || sayBase(nx) == 'i') ? "" : "у";
        else if (second) piece = sayBase(c) == 'i' ? "й" : (prev == 'o' ? "" : "у");          // ou -> «о»
        else if (c == 'ã') { if (nx == 'o') { piece = "ау"; i++; } else if (nx == 'e') { piece = "айн"; i++; } else piece = i == n - 1 ? "а" : "ан"; }
        else if (c == 'õ') { if (nx == 'e') { piece = "ойн"; i++; } else piece = "он"; }
        else switch (sayBase(c)) {
          case 'a': piece = (nx == 'm' && i == n - 2) ? "ау" : "а"; if (piece.equals("ау")) i++; break;   // -am: falam -> фа́лау
          case 'e': {
            // после скольжения u (que, gue) — как после согласной: «ке́ру», не «кэ́ру»; «ex» + гласная
            // в начале слова звучит «из» (exame -> иза́ми)
            boolean afterGlide = prev == 'u' && i >= 2 && (w.charAt(i - 2) == 'q' || w.charAt(i - 2) == 'g');
            if (finalE && !sayAcc(c)) piece = "и";
            else if (i == 0 && nx == 'x' && sayV(nx2)) piece = "и";
            else piece = (i == 0 || (sayV(prev) && !afterGlide)) ? "э" : "е";
            break; }
          case 'i': piece = "и"; break;
          case 'o': piece = !sayAcc(c) && (i == n - 1 || (nx == 's' && i == n - 2)) ? "у" : "о"; break;
          default: piece = "у";
        }
      } else switch (c) {
        case 'b': piece = "б"; break;
        case 'c': if (nx == 'h') { piece = "ш"; i++; } else piece = "eiéêí".indexOf(nx) >= 0 ? "с" : "к"; break;
        case 'ç': piece = "с"; break;
        case 'd': piece = (sayBase(nx) == 'i' || (nx == 'e' && (i + 1 == n - 1 || (nx2 == 's' && i + 1 == n - 2)))) ? "дж" : "д"; break;
        case 'f': piece = "ф"; break;
        case 'g': if (nx == 'u' && (sayBase(nx2) == 'e' || sayBase(nx2) == 'i')) { piece = "г"; i++; } else piece = "eiéêí".indexOf(nx) >= 0 ? "ж" : "г"; break;
        case 'h': piece = ""; break;
        case 'j': piece = "ж"; break;
        case 'k': piece = "к"; break;
        case 'l': if (nx == 'h') { piece = "ль"; i++; } else piece = sayV(nx) ? "л" : "у"; break;
        case 'm': piece = (i == n - 1 || (nx == 's' && i == n - 2)) ? "н" : "м"; break;
        case 'n': if (nx == 'h') { piece = "нь"; i++; } else piece = "н"; break;
        case 'p': piece = "п"; break;
        case 'q': piece = "к"; break;
        case 'r': if (nx == 'r') { piece = "х"; i++; } else piece = (i == 0 || prev == 'n' || prev == 'l' || prev == 's') ? "х" : "р"; break;
        case 's': if (nx == 's') { piece = "с"; i++; } else piece = (sayV(prev) && sayV(nx)) || "bdglmnrv".indexOf(nx) >= 0 ? "з" : "с"; break;
        case 't': if (nx == 'c' && nx2 == 'h') { piece = "ч"; i += 2; }
                  else piece = (sayBase(nx) == 'i' || (nx == 'e' && (i + 1 == n - 1 || (nx2 == 's' && i + 1 == n - 2)))) ? "ч" : "т"; break;
        case 'v': case 'w': piece = "в"; break;
        case 'x': piece = (i == 1 && prev == 'e' && sayV(nx)) ? "з" : "ш"; break;
        case 'y': piece = "и"; break;
        case 'z': piece = i == n - 1 ? "с" : "з"; break;
        default: piece = String.valueOf(c);
      }
      out.append(piece);
      if (mark >= 0 && piece.length() > 0) out.insert(mark + 1, ACUTE);
    }
    // «ь» + гласная: нья, нью, нье — иначе «аманьа́» не читается
    return out.toString().replace("ьа", "ья").replace("ьу", "ью").replace("ьэ", "ье").replace("ьо", "ьё");
  }
  /** Слово как его прочитать вслух; знаки препинания по краям сохраняются, дефис делит слово. */
  public static String say(String word) {
    if (word == null || word.isEmpty()) return word;
    int a = 0, b = word.length();
    while (a < b && !Character.isLetter(word.charAt(a))) a++;
    while (b > a && !Character.isLetter(word.charAt(b - 1))) b--;
    if (a >= b) return word;
    String core = Normalizer.normalize(word.substring(a, b), Normalizer.Form.NFC);
    StringBuilder out = new StringBuilder(word.substring(0, a));
    String[] parts = core.split("-", -1);
    for (int k = 0; k < parts.length; k++) {
      if (k > 0) out.append('-');
      String p = parts[k]; if (p.isEmpty()) continue;
      String r = sayCore(p.toLowerCase(Locale.ROOT));
      if (!r.isEmpty() && Character.isUpperCase(p.charAt(0))) r = Character.toUpperCase(r.charAt(0)) + r.substring(1);
      out.append(r);
    }
    return out.append(word.substring(b)).toString();
  }
}
