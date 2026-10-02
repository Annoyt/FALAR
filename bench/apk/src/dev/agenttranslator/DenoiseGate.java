package dev.agenttranslator;

/** «Только при шуме»: включать ли шумодав нарезки.
 *
 *  Слушание режет поток на фразы детектором речи silero и порогом по энергии. В шумной комнате оно
 *  теряет фразы: фон глушит детектор. Шумодав GTCRN перед детектором возвращает их — распознаватель
 *  при этом слышит исходный кусок, очистка перед распознаванием вредит (results/2026-10-02-vad-denoise.md).
 *  Но шумодав стоит около 21 % ядра, пока работает, а в тишине не даёт ничего. Владелец 02.10: сначала
 *  понять, что есть фон, который можно убрать, и только тогда убирать; порог −44 dBFS — «включай, если
 *  даёт прирост распознавания». На телефоне: в шумной комнате потеряно 19 фраз из 82 вместо 25, WER
 *  39–40 % вместо 50,5 %; в тихих записях шумодав не включился ни разу.
 *
 *  Решает фон комнаты — та же оценка, что у «как слышно» (roomDb: по исходному звуку, в паузах, без
 *  усиления). Не ниже порога ON_FRAMES кадров подряд (2 с) — включить; ниже порога на HYST дБ
 *  OFF_FRAMES кадров подряд (10 с) — выключить: на границе шумодав не мигает, а короткая тишина в
 *  шумном месте его не гасит.
 *
 *  Без Android, проверяется на столе (bench/apk/test/DenoiseGateTest.java); та же логика — в
 *  tools/vad_denoise_eval.py, которым порог и выбран. */
public final class DenoiseGate {
  /** Порог фона по умолчанию, dBFS. */
  public static final double DEFAULT_T = -44;
  /** 2 с и 10 с кадрами нарезки по 32 мс; гистерезис, дБ. */
  public static final int ON_FRAMES = 2000 / 32, OFF_FRAMES = 10000 / 32;
  public static final double HYST = 3;
  /** Оценки фона ещё нет. */
  public static final double NONE = -999;

  public double t = DEFAULT_T;
  boolean on;
  int onCnt, offCnt;

  public boolean on() { return on; }

  /** Очередной кадр; room — фон до этого кадра (NONE — оценки ещё нет). +1 — пора включить, −1 —
   *  выключить, 0 — без перемен. */
  public int step(double room) {
    if (!on) {
      onCnt = room >= t ? onCnt + 1 : 0;
      if (onCnt < ON_FRAMES) return 0;
      on = true; offCnt = 0;
      return 1;
    }
    offCnt = room < t - HYST ? offCnt + 1 : 0;
    if (offCnt < OFF_FRAMES) return 0;
    on = false; onCnt = 0;
    return -1;
  }

  /** С чистого листа — выключен: новая подача записи, смена режима. */
  public void reset() { on = false; onCnt = 0; offCnt = 0; }
}
