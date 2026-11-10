package dev.agenttranslator;

/** «Уточнять до озвучки» (владелец 10.11): при «Перевод фраз: Точнее» голос выбранного языка ждёт уточнитель
 *  (локальный Hy-MT, если он стоит и поднят), но не дольше CAP_MS; не успел — звучит перевод фразы, а уточнённый
 *  текст ляжет на экран позже. «Быстрее» — голос сразу, уточнитель фоном раз в N реплик (разбор контекста).
 *
 *  Замер на репликах владельца (правки облака как образец, 27.10; private/work/real_*): уточнитель против перевода
 *  фразы — ru→pt COMET +0,027 (95 % [+0,015; +0,040]), pt→ru −0,004 (без разницы), поэтому по умолчанию — PT. Владелец:
 *  уточнитель на живых записях иногда спасает трудные случаи, которых замер не видит, — выбор языка за человеком. */
final class RefineVoice {
  private RefineVoice() {}

  static final String PT = "pt", RU = "ru", BOTH = "both";
  /** Порядок сегмента в настройках: PT · RU · Оба. */
  static final String[] KEYS = {PT, RU, BOTH};
  /** Дольше голос не ждёт: на Redmi уточнитель тратит на реплику 2,6–5 с (замеры 17.09 и 10.11). */
  static final long CAP_MS = 3000;

  /** Ключ из настроек или стенда; чужое — PT. */
  static String parse(String s) {
    if (s != null) for (String k : KEYS) if (k.equals(s.trim().toLowerCase(java.util.Locale.ROOT))) return k;
    return PT;
  }

  static int index(String choice) {
    String c = parse(choice);
    for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(c)) return i;
    return 0;
  }

  /** Ждёт ли голос реплики на языке tgt уточнитель: «Точнее» (quality), разбор контекста включён и модуль
   *  уточнителя есть (context), язык — выбранный. */
  static boolean waits(String choice, boolean quality, boolean context, String tgt) {
    if (!quality || !context || tgt == null) return false;
    String c = parse(choice);
    return BOTH.equals(c) || c.equals(tgt);
  }

  /** Для журнала: «португальский», «русский», «оба языка». */
  static String label(String choice) {
    String c = parse(choice);
    return BOTH.equals(c) ? "оба языка" : RU.equals(c) ? "русский" : "португальский";
  }

  /** Сколько ещё ждать до предела: t0 — когда перевод фразы был готов, now — сейчас. */
  static long left(long t0, long now) { return Math.max(0, CAP_MS - (now - t0)); }
}
