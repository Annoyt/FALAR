package dev.agenttranslator;

import java.util.Locale;

/** Как слышно фразу — для строки на экране: перегруз, тихо, громко, шумно или хорошо, с цифрой.
 *
 *  Считается по самой фразе (Gain.speechFloor): речь и фон в dBFS после чувствительности, и доля
 *  отсчётов, на которых вход упёрся в край шкалы ещё до усиления. Пороги — по записям комнаты на
 *  Redmi, results/2026-09-29-mic-gain.md:
 *  - шумно: запас речи над фоном меньше 20 дБ. В тихой записи near-pt (WER чистых фраз 5 %) ниже
 *    20 дБ — 4 фразы из 73, в шумной noisy-pt (20 %) — 64 из 80;
 *  - тихо и громко: речь после чувствительности тише −32 или громче −17 dBFS. Лучше всего вышло при
 *    −24 (WER 15 %), при −35,5 — 28 %, при −14,9 — 21 %; пороги стоят между измеренными точками. */
final class Hearing {
  static final int GOOD = 0, NOISY = 1, QUIET = 2, OVER = 3, NONE = 4, LOUD = 5;
  /** Перегруз входа: столько процентов отсчётов у края шкалы до усиления. */
  static final double OVER_PCT = 0.1;
  /** Речь после чувствительности тише этого — «тихо», громче — «громко», dBFS. */
  static final double QUIET_DB = -32, LOUD_DB = -17;
  /** Речь громче фона меньше чем на столько — «шумно», дБ. */
  static final double NOISY_SNR = 20;

  final int kind; final String text; final double speechDb, snrDb;

  Hearing(int kind, String text, double speechDb, double snrDb) { this.kind = kind; this.text = text; this.speechDb = speechDb; this.snrDb = snrDb; }

  /** gainDb — чувствительность, с которой шла фраза; auto — подбиралась ли она сама: от этого
   *  зависит совет — крутить ручку или менять расстояние. */
  static Hearing of(double speechDb, double floorDb, double overPct, double gainDb, boolean auto) {
    double snr = speechDb - floorDb;
    if (Double.isNaN(speechDb)) return new Hearing(NONE, "речи не слышно", speechDb, snr);
    if (overPct > OVER_PCT) return new Hearing(OVER, "перегруз: микрофон на пределе — отодвиньте телефон", speechDb, snr);
    if (speechDb < QUIET_DB) return new Hearing(QUIET, String.format(Locale.ROOT, "тихо: речь %.0f дБ — %s", speechDb,
        auto || gainDb >= 24 ? "ближе к микрофону" : "прибавьте чувствительность или ближе"), speechDb, snr);
    if (speechDb > LOUD_DB) return new Hearing(LOUD, String.format(Locale.ROOT, "громко: речь %.0f дБ — %s", speechDb,
        !auto && gainDb > 0 ? "убавьте чувствительность" : "дальше от микрофона"), speechDb, snr);
    if (snr < NOISY_SNR) return new Hearing(NOISY, String.format(Locale.ROOT, "шумно: речь громче фона на %.0f дБ", snr), speechDb, snr);
    return new Hearing(GOOD, String.format(Locale.ROOT, "слышно хорошо: речь громче фона на %.0f дБ", snr), speechDb, snr);
  }

  /** Насколько хорошо слышно прямо сейчас, 0…1 — для цвета на экране: 1 — зелёный (громкости
   *  хватает), 0 — красный (тихо, возможны ошибки). Меньшее из двух: уровень речи, который дойдёт
   *  до распознавания (−48 dBFS → 0, −26 → 1: при −47,8 WER был 56 %, при −23,7 — 15 %), и запас
   *  над фоном (10 дБ → 0, 25 → 1: шумная запись с медианой 17,8 дБ — жёлто-оранжевая, тихая с
   *  25,8 — зелёная). Шум делает речь «тише» так же, как расстояние, — и ошибки от него те же. */
  static double quality(double levelDb, double snrDb) {
    if (Double.isNaN(levelDb) || Double.isNaN(snrDb)) return 0;
    double lv = (levelDb + 48) / 22, sn = (snrDb - 10) / 15;
    return Math.max(0, Math.min(1, Math.min(lv, sn)));
  }

  /** Как слышно по ходу записи — цвет кнопки удержания и полоски под кнопками слушания. Тем же
   *  счётом, что фразу целиком (Gain.speechFloor), только на лету: речь — кадры на 10 дБ и больше
   *  над фоном, её уровень — средняя мощность таких кадров с памятью около двух секунд речи, запас —
   *  от него до фона. Паузы оценку не трогают: в паузе оценивать нечего, и цвет держится.
   *
   *  Раньше цвет шёл за каждым кадром, а в тишине за секунду сходил к красному: между словами и
   *  фразами кнопка мигала оранжевым, будто слышно стало хуже, хотя фраза распознавалась так же
   *  (владелец, 29.09). Замер — results/2026-09-29-mic-live.md.
   *
   *  Время считается кадрами по 512 отсчётов (32 мс), а не часами: подача записи быстрее реального
   *  времени идёт той же дорогой, и оценка от скорости подачи не зависит. */
  static final class Live {
    /** Речь — кадр громче фона на столько, дБ (как в Gain.speechFloor). */
    static final double SPEECH_DB = 10;
    /** Первая оценка — после стольких речевых кадров (0,26 с): начало слога тише его середины, и
     *  по первым кадрам цвет выходил бы краснее итога фразы. С 5 кадрами цвет появлялся на 70–130 мс
     *  раньше, но оранжевым на зелёной по итогу фразе стоял вдвое дольше (замер). */
    static final int FIRST = 8;
    /** Память — столько речевых кадров (около двух секунд речи): сменились условия — цвет
     *  догоняет за пару секунд речи, а не за один слог. */
    static final int MEMORY = 60;
    /** Речи нет столько кадров с начала записи (1,5 с) — «не слышу»: красный. */
    static final int NO_VOICE = 47;
    /** «Речь сейчас» — речевой кадр был за последние столько кадров (1 с). */
    static final int RECENT = 31;
    /** Свой фон (у удержания) — нижняя огибающая уровня кадров: вниз сразу, вверх на FLOOR_UP дБ за
     *  кадр (~1 дБ/с), и только вне речи, как фон у нарезки: иначе за три секунды сплошной речи он
     *  подрастал на 3 дБ и цвет краснел к концу фразы (замер: ниже итога 4,9 % времени против 3,3 %).
     *  Цифровая тишина (ниже DEAD_DB — микрофон ещё не проснулся) в него не идёт. */
    static final double FLOOR_UP = 0.03, DEAD_DB = -100;

    double floorDb = Double.NaN, pow = 0, snrFloorDb = Double.NaN;
    int frames = 0, speech = 0, quiet = 0;

    void reset() { floorDb = snrFloorDb = Double.NaN; pow = 0; frames = speech = 0; quiet = 0; }

    /** Начать свой фон с известного фона комнаты (слушание мерило его только что): заговорят с
     *  первого кадра — речь всё равно видна как речь, а не принимается за фон. NaN — не трогать. */
    void seed(double db) { if (!Double.isNaN(db) && db > DEAD_DB) floorDb = db; }

    /** Кадр с уровнем db, dBFS, и своим фоном. Возвращает, речь ли это. */
    boolean frame(double db) {
      if (db > DEAD_DB) {
        if (Double.isNaN(floorDb)) floorDb = db;
        else if (db < floorDb + SPEECH_DB) floorDb = Math.min(db, floorDb + FLOOR_UP);
      }
      return frame(db, floorDb);
    }

    /** Кадр с уровнем db и внешней оценкой фона в той же шкале (NaN — фона ещё нет, кадр не речь). */
    boolean frame(double db, double floor) {
      frames++;
      if (Double.isNaN(floor) || db < floor + SPEECH_DB) { quiet++; return false; }
      speech++; quiet = 0;
      pow += Math.max(1.0 / MEMORY, 1.0 / speech) * (Math.pow(10, db / 10) - pow);
      snrFloorDb = floor;
      return true;
    }

    /** Уровень речи, dBFS, или NaN — пока речи меньше FIRST кадров. */
    double speechDb() { return speech < FIRST ? Double.NaN : 10 * Math.log10(pow); }

    /** Запас речи над фоном, дБ: фон — тот, что был на последнем речевом кадре. */
    double snrDb() { return speechDb() - snrFloorDb; }

    /** Была ли речь за последнюю секунду — и есть ли уже оценка: до неё полоске показывать нечего. */
    boolean voiced() { return speech >= FIRST && quiet < RECENT; }

    /** Усиление сменилось на db (авто подстроилось после фразы): оценки в шкале после усиления
     *  сдвигаются вместе с ним, как фон у нарезки. */
    void shift(double db) {
      pow *= Math.pow(10, db / 10);
      if (!Double.isNaN(floorDb)) floorDb += db;
      if (!Double.isNaN(snrFloorDb)) snrFloorDb += db;
    }

    /** Цвет, 0…1 (quality), по уровню effDb, с которым речь дойдёт до распознавания, — его
     *  считает вызывающий из speechDb(), по своему усилению. −1 — оценивать пока нечего (цвета
     *  нет), 0 — речи нет с начала дольше NO_VOICE. */
    double q(double effDb) {
      if (speech < FIRST) return frames > NO_VOICE ? 0 : -1;
      return quality(effDb, snrDb());
    }
  }

  /** Цвет строки: хорошо — зелёный, шумно и тихо — янтарный, перегруз — красный, пусто — серый. */
  int color() {
    switch (kind) {
      case GOOD: return 0xFF2E7D4F;
      case NOISY: case QUIET: case LOUD: return 0xFFB26A00;
      case OVER: return 0xFFC62828;
      default: return 0xFF808080;
    }
  }
}
