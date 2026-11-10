package dev.agenttranslator;

import java.util.Locale;

/** Проверка NPU на первых фразах — раз на версию Falar и прошивку (владелец 10.11: «при каждом выпуске как минимум раз
 *  выбирать»). Пока NPU не проверен, фразу распознаёт процессор, как всегда, а NPU в фоне — ту же; тексты сравниваются.
 *  NPU может не упасть, но считать неверно: на POCO до правки 04 дополнения он выдавал пустые фразы без ошибки.
 *
 *  Правило «двух из трёх»: две совпавшие фразы — NPU принят; две не совпавшие — отказ до следующей версии. Совпала —
 *  сходство знаков (без регистра и знаков препинания) не ниже MATCH: NPU и процессор считают одну модель по-разному
 *  (A16W8 и int8), и на 162 фразах корпуса тексты расходились на слово-два, а сбой даёт пустое или кашу. Короткие
 *  фразы (у обоих меньше MIN_CHARS знаков — «ага», шум) не судятся. В журнал и статистику — только числа, без
 *  текстов. Без Android — проверяется на столе (NpuCheckTest). */
final class NpuCheck {
  static final double MATCH = 0.6;
  static final int MIN_CHARS = 4, AGREE = 2, DISAGREE = 2;
  enum Verdict { PENDING, OK, FAIL }

  int judged, matched, empty;
  double simSum;

  /** Ещё одна фраза: текст процессора и NPU (null — NPU её не взял: длиннее окна). */
  synchronized Verdict add(String cpu, String npu) {
    if (npu == null) return verdict();
    String c = norm(cpu), n = norm(npu);
    if (c.length() < MIN_CHARS && n.length() < MIN_CHARS) return verdict();
    judged++;
    double s = similarity(c, n);
    simSum += s;
    if (s >= MATCH) matched++;
    if (n.isEmpty()) empty++;
    return verdict();
  }

  synchronized Verdict verdict() {
    if (matched >= AGREE) return Verdict.OK;
    if (judged - matched >= DISAGREE) return Verdict.FAIL;
    return Verdict.PENDING;
  }

  /** Числами, для журнала и статистики: «совпало 1 из 3 фраз, сходство в среднем 34 %, у NPU пусто — 2». */
  synchronized String summary() {
    return String.format(Locale.ROOT, "совпало %d из %d фраз, сходство в среднем %d %%%s", matched, judged,
        judged == 0 ? 0 : Math.round(100 * simSum / judged), empty > 0 ? ", у NPU пусто — " + empty : "");
  }

  /** Без регистра; буквы и цифры, остальное — пробел; пробелы схлопнуты. */
  static String norm(String s) {
    if (s == null) return "";
    StringBuilder b = new StringBuilder(s.length());
    boolean sp = true;
    for (int i = 0; i < s.length(); ) {
      int c = s.codePointAt(i); i += Character.charCount(c);
      if (Character.isLetterOrDigit(c)) { b.appendCodePoint(Character.toLowerCase(c)); sp = false; }
      else if (!sp) { b.append(' '); sp = true; }
    }
    int n = b.length(); if (n > 0 && b.charAt(n - 1) == ' ') b.setLength(n - 1);
    return b.toString();
  }

  /** 1 − расстояние Левенштейна / длину большей строки (оба пусты — 1); длинные — по первым 1000 знаков. */
  static double similarity(String a, String b) {
    a = a.length() > 1000 ? a.substring(0, 1000) : a;
    b = b.length() > 1000 ? b.substring(0, 1000) : b;
    int m = Math.max(a.length(), b.length());
    if (m == 0) return 1;
    int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++)
        cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
      int[] t = prev; prev = cur; cur = t;
    }
    return 1.0 - prev[b.length()] / (double) m;
  }
}
