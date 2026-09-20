package dev.agenttranslator;

import java.text.Normalizer;
import java.util.*;

/** Отличить прочитанное вслух от сказанного собеседником.
 *
 *  Задача: включена транскрипция и слушается португальский. Владелец читает фразу с экрана вслух,
 *  микрофон его слышит, приложение считает это речью собеседника и переводит ему его же фразу
 *  обратно на русский. Признак, который это ловит без настроек, без записи голоса и без сети:
 *  прочитанное состоит из слов фразы, которая уже есть на экране.
 *
 *  Порог осознанно осторожный. Дешёвая ошибка — не заметить чтение: появится лишняя реплика.
 *  Дорогая — выбросить настоящий ответ собеседника: он пропадёт, и человек не узнает почему.
 *  Поэтому требуем не меньше трёх слов и чтобы 80 % сказанного нашлось в одной показанной фразе.
 *  Дословный повтор фразы собеседником тоже отсеется — это принятая плата: повтор не несёт
 *  нового смысла, а защита работает у всех с первой секунды.
 *
 *  Без Android, поэтому проверяется на столе (bench/apk/test/HeardTest.java).
 */
public class Heard {
  /** Сколько последних реплик разговора считаем «тем, что на экране». */
  public static final int DEPTH = 6;
  static final int MIN_WORDS = 3, NEED_PERCENT = 80;

  /** Слова без регистра, диакритики и знаков: распознавание читающего расходится с исходником
   *  в мелочах — «senão» слышится как «senao», запятые теряются. */
  public static List<String> words(String s) {
    String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    List<String> r = new ArrayList<>();
    if (!n.isEmpty()) for (String w : n.split(" ")) if (!w.isEmpty()) r.add(w);
    return r;
  }

  /** Фраза из показанных, из слов которой почти целиком состоит сказанное; null — не чтение.
   *  Список идёт от старых к новым, просматривается с конца. */
  public static String fromScreen(String said, List<String> shown) {
    List<String> a = words(said);
    if (a.size() < MIN_WORDS || shown == null) return null;      // на одном-двух словах совпадение случайно
    int seen = 0;
    for (int i = shown.size() - 1; i >= 0 && seen < DEPTH; i--) {
      String phrase = shown.get(i);
      if (phrase == null || phrase.trim().isEmpty()) continue;
      seen++;
      Set<String> w = new HashSet<>(words(phrase));
      if (w.isEmpty()) continue;
      int common = 0;
      for (String x : a) if (w.contains(x)) common++;
      if (common * 100 >= a.size() * NEED_PERCENT) return phrase;
    }
    return null;
  }
}
