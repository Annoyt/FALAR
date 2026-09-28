package dev.agenttranslator;

import java.util.*;

/** Сколько разговора можно показать уточнителю и как это ужать.
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
}
