package dev.agenttranslator;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** «Скажите сами» (шаг 2, разбор 03.10): человек говорит фразу карточки, распознаватель пишет, что услышал,
 *  и каждое слово фразы — зелёное (распознано) или красное (нет). Распознаватель на фразе с контекстом
 *  прощает акцент, поэтому «распознано» почти значит «бразилец понял бы»; на одиночном слове вердикт
 *  случаен — поэтому говорят фразу, а из длинного предложения — кусок в ±3 слова вокруг слова карточки.
 *
 *  Без Android: сравнение проверяется на столе (PracticeTest). Попытка не хранится и репликой не становится. */
final class Practice {
  /** Сколько слов вокруг слова карточки говорить из длинного предложения. */
  static final int AROUND = 3, LONG = 10;

  private Practice() {}

  /** Что сказать: предложение примера целиком, если оно не длиннее LONG слов; иначе кусок ±AROUND слов
   *  вокруг слова или связки карточки (key — нормализованный, как в Cards). */
  static String target(String sentence, String key) {
    List<int[]> w = words(sentence);
    if (w.size() <= LONG) return sentence.trim();
    int[] h = key == null ? null : Screen.highlight(sentence, key);
    int at = 0;
    if (h != null) for (int k = 0; k < w.size(); k++) if (w.get(k)[0] <= h[0] && h[0] < w.get(k)[1]) { at = k; break; }
    int last = at + (key == null ? 0 : key.split(" ").length - 1);
    int a = Math.max(0, at - AROUND), b = Math.min(w.size() - 1, last + AROUND);
    return sentence.substring(w.get(a)[0], w.get(b)[1]).replaceAll("^[^\\p{L}\\p{N}]+|[,;:—–-]+$", "");
  }

  /** Повторение вслух (решение владельца 03.10): показан русский, а из португальской фразы спрятано слово
   *  карточки — «Espera …, já volto.»; сказать надо всю фразу, вердикт — по слову (Result.keyOk). Слово в
   *  отрыве распознаватель слышит случайно, во фразе — нет. null — слова во фразе нет. */
  static String gap(String target, String key) {
    int[] h = key == null || key.isEmpty() ? null : Screen.highlight(target, key);
    return h == null ? null : target.substring(0, h[0]) + "…" + target.substring(h[1]);
  }

  static List<int[]> words(String s) {
    List<int[]> out = new ArrayList<>();
    Matcher m = Pattern.compile("\\S+").matcher(s);
    while (m.find()) out.add(new int[]{m.start(), m.end()});
    return out;
  }

  /** Сверка: для каждого слова того, что надо было сказать, — распознано ли (выравнивание по наибольшей общей
   *  подпоследовательности нормализованных слов: регистр, знаки, «tá» = «está» не мешают; лишние слова
   *  распознанного не штрафуют). */
  static final class Result {
    final String target, heard; final List<int[]> spans; final boolean[] ok; final int hits, total;
    Result(String target, String heard, List<int[]> spans, boolean[] ok) {
      this.target = target; this.heard = heard; this.spans = spans; this.ok = ok;
      int h = 0; for (boolean b : ok) if (b) h++; hits = h; total = ok.length;
    }
    /** Доля распознанных слов фразы. */
    double share() { return total == 0 ? 0 : hits / (double) total; }
    /** Слово карточки распознано — главный вердикт (оно и учится). */
    boolean keyOk(String key) {
      if (key == null || key.isEmpty()) return hits == total && total > 0;
      int[] h = Screen.highlight(target, key);
      if (h == null) return hits == total && total > 0;
      boolean any = false;
      for (int k = 0; k < spans.size(); k++) if (spans.get(k)[0] < h[1] && spans.get(k)[1] > h[0]) { if (!ok[k]) return false; any = true; }
      return any;
    }
  }

  static Result check(String target, String heard) {
    List<int[]> sp = new ArrayList<>(); List<String> t = new ArrayList<>();
    for (int[] w : words(target)) {
      String n = Phrasebook.norm(target.substring(w[0], w[1]));
      if (n.isEmpty()) continue;                         // одиночный знак — не слово
      sp.add(w); t.add(n);
    }
    List<String> h = new ArrayList<>();
    for (String x : Phrasebook.norm(heard == null ? "" : heard).split(" ")) if (!x.isEmpty()) h.add(x);
    // нормализация может развернуть слово в два («pro» → «para o»): сверяем по первому
    String[] a = new String[t.size()]; for (int k = 0; k < a.length; k++) a[k] = t.get(k).split(" ")[0];
    int n = a.length, m = h.size(); int[][] L = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
      L[i][j] = a[i].equals(h.get(j)) ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
    boolean[] ok = new boolean[n];
    for (int i = 0, j = 0; i < n && j < m; ) {
      if (a[i].equals(h.get(j))) { ok[i] = true; i++; j++; }
      else if (L[i + 1][j] >= L[i][j + 1]) i++; else j++;
    }
    return new Result(target, heard == null ? "" : heard, sp, ok);
  }
}
