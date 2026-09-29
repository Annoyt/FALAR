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
