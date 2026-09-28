package dev.agenttranslator;

/** Когда выгружать уточнитель по сигналу памяти от системы.
 *
 *  Замер на Redmi (results/2026-09-28-memo.md): подъём уточнителя — чтение файла на 1,1 ГБ — сам
 *  вызывает у системы кратковременный критический сигнал (уровень 15) через 3 с после подъёма, а
 *  после него свободно ещё 1,2 ГБ. Приложение выгружало уточнитель на первом же таком сигнале, и в
 *  разговоре он не работал вовсе — а сырой перевод без него понятен редко.
 *
 *  Теперь: 30 с после подъёма критический сигнал пережидаем; позже выгружаем, только если давление
 *  держится — второй критический сигнал не дальше 15 с от предыдущего. Ушли в фон — выгружаем
 *  сразу: там уточнитель не нужен, а память нужна другим. Цена ожидания: пока Falar открыт и
 *  разбирает, система чаще закрывает фоновые приложения — это выбор владельца от 28.09.
 *
 *  Без Android, проверяется на столе (bench/apk/test/PressureTest.java).
 */
public class Pressure {
  public static final long GRACE_MS = 30_000, REPEAT_MS = 15_000;
  /** Уровни ComponentCallbacks2: RUNNING_CRITICAL и BACKGROUND (всё выше — тоже фон). */
  public static final int RUNNING_CRITICAL = 15, BACKGROUND = 40;

  /** Выгрузить ли уточнитель. level — уровень сигнала; now — время сигнала; loadedAt — когда
   *  уточнитель поднят (0 — не поднят); prevCritical — время прошлого критического сигнала (0 — не было).
   *  Время — монотонное, в миллисекундах. */
  public static boolean unload(int level, long now, long loadedAt, long prevCritical) {
    if (loadedAt == 0) return false;
    if (level >= BACKGROUND) return true;
    if (level != RUNNING_CRITICAL) return false;
    if (now - loadedAt < GRACE_MS) return false;               // пик от самого подъёма
    return prevCritical != 0 && now - prevCritical <= REPEAT_MS;
  }
}
