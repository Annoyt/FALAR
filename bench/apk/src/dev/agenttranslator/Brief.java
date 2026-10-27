package dev.agenttranslator;

import java.util.*;

/** Сколько разговора можно показать уточнителю, как это ужать и когда его ответ вобрал этот фон.
 *
 *  Задача: у локального llama-server окно 2048 токенов. Уточнитель подкладывал до восьми последних
 *  реплик целиком, без счёта длины, и на длинных монологах запрос перерастал окно. Сервер отвечал
 *  ошибкой, весь проход обрывался, а метка «разобрано до» не сдвигалась — следующий проход брал
 *  те же реплики и падал снова. Снаружи это выглядело так, будто после пары тысяч слов уточнитель
 *  «тихо умер».
 *
 *  Решение — бюджет в знаках, выведенный из окна: новые реплики берутся целиком, пока влезают,
 *  самая близкая, если не влезает, обрезается с начала (важен её конец — он ближе к переводимой
 *  фразе), всё более старое сжимается в одну строку темы, которую приложение уже умеет считать.
 *  Так в памяти модели всегда небольшой свежий кусок разговора, и запрос не растёт с разговором.
 *
 *  Знаков на токен меряется на настоящем токенизаторе при запуске уточнителя (/tokenize), а до
 *  замера берётся осторожное значение: ошибка в сторону «меньше влезет» стоит чуть худшего
 *  уточнения, в сторону «больше» — отказа сервера.
 *
 *  Обратная сторона фона — ответ, который его вобрал (swallowed). Hy-MT2 иногда переводит не только
 *  реплику, но и строки фона. На живых разговорах владельца из 510 правок уточнителя 16 были длиннее
 *  черновика вдвое, 8 — втрое, и минимум в четырёх из 16 нашёлся кусок соседних реплик от 20 знаков.
 *  Такая правка заменяла перевод реплики и ложилась в выученное. Теперь ответ, который длиннее реплики
 *  в 2,5 раза (с запасом на короткие), или с куском чужого текста при прибавке длины в полтора раза,
 *  отбрасывается с причиной в журнале. Пороги — по прогону на открытом наборе
 *  (tools/refine_guard_eval.py, bench/apk/refine_guard_sim.sh; results/2026-10-27-refine-guard.md).
 *
 *  Без Android, проверяется на столе (bench/apk/test/BriefTest.java).
 */
public class Brief {
  /** Окно сервера (-c у llama-server) и сколько оставить на ответ. */
  public static final int CTX_TOKENS = 2048, OUT_TOKENS = 200;
  /** Обвязка запроса: служебные строки подсказки, шаблон чата модели. Считается в токенах. */
  public static final int FRAME_TOKENS = 120;
  /** До замера на токенизаторе: осторожно, русский дробится мельче португальского. */
  public static final double DEFAULT_CHARS_PER_TOKEN = 2.2;
  /** Запас на расхождение между замером и конкретным текстом. */
  public static final double SAFETY = 0.85;
  /** Потолок «свежего куска» разговора независимо от окна. Разбор идёт на процессоре, и его время
   *  растёт с длиной запроса: на Redmi при восьми репликах по 350 знаков — 25–42 секунды на реплику,
   *  так что реплики приходили быстрее, чем разбирались. Три-четыре последние реплики дают почти
   *  весь выигрыш по смыслу, а остальное сжато в строку темы. */
  public static final int FRESH_CHARS = 1200;

  /** Сколько знаков фона можно подложить при таком исходнике и таких парах из глоссария.
   *  0 — исходник сам не оставляет места; отрицательное — исходник не влезает вовсе. */
  public static int budget(double charsPerToken, int sourceChars, int termsChars, int topicChars) {
    double cpt = charsPerToken > 0 ? charsPerToken : DEFAULT_CHARS_PER_TOKEN;
    int tokens = CTX_TOKENS - OUT_TOKENS - FRAME_TOKENS;
    int chars = (int) Math.floor(tokens * cpt * SAFETY);
    return chars - sourceChars - termsChars - topicChars;
  }

  /** Влезет ли исходник вообще — с пустым фоном, но с ответом. */
  public static boolean fits(double charsPerToken, int sourceChars, int termsChars) {
    return budget(charsPerToken, sourceChars, termsChars, 0) >= 0;
  }

  /** Фон в пределах бюджета. lines — от старых к новым; берём с конца. Самая новая, если сама
   *  не влезает, обрезается с начала по границе слова и помечается «…». Возвращает строки в
   *  прежнем порядке, от старых к новым, чтобы модель читала разговор, как он шёл. */
  public static List<String> fit(List<String> lines, int budgetChars) {
    List<String> out = new ArrayList<>();
    if (lines == null) lines = Collections.emptyList(); // нулевой бюджет отсекается в цикле: ни одна строка не влезет
    int left = budgetChars;
    for (int i = lines.size() - 1; i >= 0; i--) {
      String l = lines.get(i);
      if (l == null) continue;
      l = l.trim();
      if (l.isEmpty()) continue;
      int cost = l.length() + 1;                       // перевод строки
      if (cost <= left) { out.add(l); left -= cost; continue; }
      // Не влезает. Если это самая близкая реплика — берём её хвост, остальные дальше не смотрим.
      if (out.isEmpty() && left > 12) out.add(tail(l, left - 1));
      break;
    }
    Collections.reverse(out);
    return out;
  }

  /** Конец строки не длиннее n знаков, с «…» впереди. Если отрез пришёлся посреди слова, обрывок
   *  слова выбрасывается; если ровно на начало слова — слово остаётся целиком. Раньше в этом случае
   *  выбрасывалось и целое слово: из «three four» оставалось «…four», хотя «three» помещалось. */
  static String tail(String s, int n) {
    if (s.length() <= n) return s;
    if (n <= 1) return "…";
    int start = s.length() - (n - 1);                  // первый знак, который помещается
    if (!Character.isWhitespace(s.charAt(start - 1))) { // режем посреди слова — его обрывок не нужен
      int sp = s.indexOf(' ', start);                   // start ≥ 2, так что найденный пробел не бывает в начале
      if (sp != -1 && sp < s.length() - 1) start = sp + 1;  // пробел последним — резать нечего, иначе останется одно «…»
    }
    return "…" + s.substring(start).trim();
  }

  /** Знаков на токен по замеру: образец и сколько токенов из него вышло. Нелепые ответы
   *  (ноль токенов, больше токенов, чем знаков) отбрасываются, остаётся значение по умолчанию. */
  public static double measured(int chars, int tokens) {
    if (tokens <= 0 || tokens > chars) return DEFAULT_CHARS_PER_TOKEN;   // при chars ≤ 0 второе условие уже истинно
    return (double) chars / tokens;
  }

  // ---------- ответ, вобравший фон ----------

  /** Во сколько раз ответ может быть длиннее реплики и запас в знаках для коротких («Oi» → «Привет»).
   *  Длина реплики — большее из исходника и её нынешнего перевода: обрезанный черновик не делает
   *  верный полный перевод «раздутым». */
  public static final double SWELL = 2.5;
  public static final int SWELL_SLACK = 12;
  /** Кусок чужого текста в ответе: столько знаков подряд — уже списано, а не совпало. */
  public static final int LEAK_CHARS = 20;
  /** С таким куском ответ отбрасывается, только если и сам заметно длиннее реплики: без прибавки
   *  это скорее повтор — человек сказал то же, что раньше, и перевод совпал с прежним. */
  public static final double LEAK_SWELL = 1.5;
  public static final int LEAK_SLACK = 10;
  /** Сколько слов подряд должно совпасть, чтобы слова считались куском чужого текста. */
  static final int RUN = 3;

  /** Ответ уточнителя, вобравший фон: null — годится, иначе — причина для журнала. src — исходник
   *  реплики, draft — её нынешний перевод, others — что уточнитель видел рядом: другие реплики (и
   *  исходники, и переводы) и память разговора. */
  public static String swallowed(String out, String src, String draft, List<String> others) {
    if (out == null || out.isEmpty()) return null;
    int n = out.length(), ref = Math.max(src == null ? 0 : src.length(), draft == null ? 0 : draft.length());
    String times = String.format(Locale.ROOT, "%.1f", n / (double) Math.max(1, ref));
    if (n > SWELL * ref + SWELL_SLACK) return "в " + times + " раза длиннее реплики (" + n + " знаков на " + ref + ")";
    if (n > LEAK_SWELL * ref + LEAK_SLACK) {
      String k = copied(out, Arrays.asList(src, draft), others);
      if (k.length() >= LEAK_CHARS) return "кусок другой реплики «" + k + "», ответ в " + times + " раза длиннее реплики";
    }
    return null;
  }

  /** Самый длинный кусок ответа, списанный из others: слова, которые стоят так же подряд, по RUN и
   *  больше, хотя бы в одном из чужих текстов и не стоят так в своих (own). Имена, числа и то, что
   *  реплика сама повторяет за соседней, — свои. Без регистра и знаков. Пусто — ничего не списано. */
  static String copied(String out, List<String> own, List<String> others) {
    String[] w = words(out);
    boolean[] alien = cover(w, others), mine = cover(w, own);
    String best = ""; StringBuilder run = new StringBuilder();
    for (int i = 0; i <= w.length; i++) {
      if (i < w.length && alien[i] && !mine[i]) { run.append(run.length() == 0 ? "" : " ").append(w[i]); continue; }
      if (run.length() > best.length()) best = run.toString();
      run.setLength(0);
    }
    return best;
  }

  /** Какие из слов w стоят внутри общего с каким-нибудь из texts отрезка в RUN слов и больше.
   *  Общий отрезок — как наибольшая общая подстрока, только по словам: d[j] — длина общего
   *  отрезка, который кончается на w[i] и на j-м слове текста. */
  static boolean[] cover(String[] w, List<String> texts) {
    boolean[] c = new boolean[w.length];
    if (texts != null) for (String t : texts) {
      String[] v = words(t);
      int[] d = new int[v.length + 1];
      for (int i = 0; i < w.length; i++)
        for (int j = v.length; j >= 1; j--) {           // с конца: d[j - 1] ещё от прошлого слова w
          d[j] = w[i].equals(v[j - 1]) ? d[j - 1] + 1 : 0;
          if (d[j] >= RUN) for (int k = i - d[j] + 1; k <= i; k++) c[k] = true;
        }
    }
    return c;
  }

  static final java.util.regex.Pattern NOT_WORD = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");

  /** Слова текста для сравнения: нижний регистр, «ё» как «е», всё, кроме букв и цифр, — граница. */
  static String[] words(String s) {
    if (s == null) return new String[0];
    String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace('ё', 'е');
    t = NOT_WORD.matcher(t).replaceAll(" ").trim();
    return t.isEmpty() ? new String[0] : t.split(" ");
  }
}
