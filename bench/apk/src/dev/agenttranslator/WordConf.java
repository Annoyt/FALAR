package dev.agenttranslator;

import java.util.*;

/** Сомнительные слова распознанного — там, где распознаватель сам не уверен (results/2026-10-27-asr-confidence.md,
 *  шаг 1). Слово — токены parakeet от токена с пробелом («▁») в начале; уверенность слова — наименьшая вероятность
 *  его токенов с буквами или цифрами (знаки препинания не в счёт), как в tools/asr_confidence.words. Замер на 14
 *  записях комнаты: слово с уверенностью ниже 0,7 ошибочно в 54 % случаев; так помечается половина ошибок
 *  распознавания и 3,8 % верных слов.
 *
 *  Вероятности токенов отдаёт только пересобранная библиотека JNI (tools/sherpa-jni): с прежней их нет —
 *  и пометок нет. */
final class WordConf {
  /** Порог уверенности слова: ниже — сомнительное. */
  static final double DOUBT = 0.7;

  /** Слово распознанного и уверенность в нём. */
  static final class Word {
    final String text; final double conf;
    Word(String text, double conf) { this.text = text; this.conf = conf; }
    @Override public String toString() { return text + " " + conf; }
  }

  /** Слова распознанного с уверенностью, по токенам и их вероятностям (не логарифмам). Нет вероятностей — пусто. */
  static List<Word> words(String[] tokens, float[] probs) {
    List<Word> out = new ArrayList<>();
    if (tokens == null || probs == null || probs.length != tokens.length) return out;
    StringBuilder cur = new StringBuilder(); double conf = 1.0;
    for (int k = 0; k < tokens.length; k++) {
      String t = tokens[k];
      if (t.startsWith(" ") || t.startsWith("▁")) {
        add(out, cur, conf);
        cur.setLength(0); cur.append(stripStart(t)); conf = probs[k];
      } else {
        cur.append(t);
        if (wordy(t)) conf = Math.min(conf, probs[k]);
      }
    }
    add(out, cur, conf);
    return out;
  }

  /** Сомнительные слова: уверенность ниже порога. */
  static List<Word> doubtful(String[] tokens, float[] probs, double thr) {
    List<Word> out = new ArrayList<>();
    for (Word w : words(tokens, probs)) if (w.conf < thr) out.add(w);
    return out;
  }

  /** Где в тексте реплики сомнительные слова (уверенность ниже thr): пары [начало, конец) знаков подряд. По тексту
   *  идут все слова распознанного по порядку — и уверенные тоже, иначе второе «não» в «não, não sei» легло бы на
   *  первое. Поиск — без учёта регистра, по границам слов, от конца прежнего найденного. Слово, которого в тексте
   *  нет (текст после распознавания правят — числа, свои слова), пропускается. */
  static int[] spans(String text, List<Word> words, double thr) {
    if (text == null || words == null || words.isEmpty()) return new int[0];
    String low = text.toLowerCase(Locale.ROOT);
    List<Integer> out = new ArrayList<>(); int from = 0;
    for (Word w : words) {
      String q = w.text.toLowerCase(Locale.ROOT);
      int at = find(low, q, from);
      if (at < 0) continue;
      if (w.conf < thr) { out.add(at); out.add(at + q.length()); }
      from = at + q.length();
    }
    int[] a = new int[out.size()]; for (int k = 0; k < a.length; k++) a[k] = out.get(k);
    return a;
  }

  /** Отрезки — в строку разговора: «5-9,17-23». */
  static String pack(int[] spans) {
    StringBuilder b = new StringBuilder();
    for (int k = 0; k + 1 < spans.length; k += 2) { if (b.length() > 0) b.append(','); b.append(spans[k]).append('-').append(spans[k + 1]); }
    return b.toString();
  }

  /** Строка разговора — в отрезки; испорченная часть пропускается. */
  static int[] unpack(String s) {
    if (s == null || s.isEmpty()) return new int[0];
    List<Integer> out = new ArrayList<>();
    for (String p : s.split(",")) {
      int d = p.indexOf('-');
      if (d <= 0) continue;
      try {
        int a = Integer.parseInt(p.substring(0, d).trim()), b = Integer.parseInt(p.substring(d + 1).trim());
        if (a >= 0 && b > a) { out.add(a); out.add(b); }
      } catch (NumberFormatException e) { /* пропуск */ }
    }
    int[] a = new int[out.size()]; for (int k = 0; k < a.length; k++) a[k] = out.get(k);
    return a;
  }

  /** Отрезки, сдвинутые на shift знаков (подпись «🎙 » перед текстом). */
  static int[] shift(int[] spans, int shift) {
    int[] a = spans.clone(); for (int k = 0; k < a.length; k++) a[k] += shift;
    return a;
  }

  /** Задевает ли отрезок [a, b) какое-нибудь сомнительное слово. */
  static boolean touches(int[] spans, int a, int b) {
    for (int k = 0; k + 1 < spans.length; k += 2) if (spans[k] < b && a < spans[k + 1]) return true;
    return false;
  }

  /** Слово без знаков по краям: «dia,» → «dia». Пустое (одни знаки) не добавляется. */
  static void add(List<Word> out, StringBuilder cur, double conf) {
    String s = cur.toString(); int a = 0, b = s.length();
    while (a < b && !Character.isLetterOrDigit(s.charAt(a))) a++;
    while (b > a && !Character.isLetterOrDigit(s.charAt(b - 1))) b--;
    if (b > a) out.add(new Word(s.substring(a, b), conf));
  }

  static String stripStart(String t) {
    int a = 0; while (a < t.length() && (t.charAt(a) == ' ' || t.charAt(a) == '▁')) a++;
    return t.substring(a);
  }

  static boolean wordy(String t) {
    for (int k = 0; k < t.length(); k++) if (Character.isLetterOrDigit(t.charAt(k))) return true;
    return false;
  }

  /** Вхождение q в low не раньше from, по границам слов (до и после — не буква и не цифра). */
  static int find(String low, String q, int from) {
    if (q.isEmpty()) return -1;
    for (int at = low.indexOf(q, from); at >= 0; at = low.indexOf(q, at + 1)) {
      boolean left = at == 0 || !Character.isLetterOrDigit(low.charAt(at - 1));
      int e = at + q.length();
      boolean right = e >= low.length() || !Character.isLetterOrDigit(low.charAt(e));
      if (left && right) return at;
    }
    return -1;
  }

  private WordConf() {}
}
