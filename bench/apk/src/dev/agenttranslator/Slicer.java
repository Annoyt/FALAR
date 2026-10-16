package dev.agenttranslator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Сборка куска речи из кадров слушания — то, что раньше жило прямо в потоке нарезки (TranslatorService.startVad).
 *  На вход — кадр после усиления, признак «речь» (silero И порог по энергии) и уровень кадра; на выход — закрытый
 *  кусок, когда он готов. Чистая Java: проверяется на столе (bench/apk/test/SlicerTest.java); близнец для записей
 *  комнаты — tools/vad_denoise_eval.segment.
 *
 *  Правила (results/2026-09-12-air.md): подпор начала preRollMs из кольца кадров до первой речи — модели нужен
 *  настоящий звук комнаты перед фразой; выдержка hangMs тишины после последней речи закрывает кусок, из неё
 *  остаётся хвост tailMs; кусок короче minSpeechMs речи после срабатывания детектора выбрасывается; длиннее
 *  maxSpeechMs — режется.
 *
 *  Поправлено 08.10 (results/2026-10-08-asr-review.md):
 *  - тишина, отрезанная при закрытии, становится подпором следующего куска. Раньше подпор после куска был пуст,
 *    и фраза, начатая меньше чем через секунду, шла почти без звука комнаты перед ней — первое слово резалось
 *    (пары фраз с паузой 450 мс: WER второй 8 % → 0, её кусок начинался на 85–160 мс позже речи);
 *  - кадры кончились (удержание, слушание выключено, озвучка глушит микрофон) — gap(): открытый кусок закрывается
 *    сейчас, а не когда кадры пойдут снова (иначе реплика собеседника переводилась после ответа ей), подпор
 *    сбрасывается — звук за разрывом не продолжение;
 *  - предел длины режет не на том кадре, где он наступил, а на самом тихом из последних CUT_MS, и остаток идёт
 *    началом следующего куска (раньше продолжение начиналось без подпора и посреди слова). */
final class Slicer {
  static final int FRAME_MS = 32;
  /** Окно, в котором ищется тихий кадр для разреза по пределу длины, и минимум, который остаётся продолжению. */
  static final int CUT_MS = 1500;
  volatile int preRollMs = 1000, tailMs = 300, minSpeechMs = 250, maxSpeechMs = 15000, hangMs = 600;

  /** Закрытый кусок: звук, счётчики входа, сколько в нём кадров речи после срабатывания детектора (мс) и был ли он
   *  отрезан по пределу длины (тогда silero стоит сбросить: у него свой счёт длины). */
  static final class Piece {
    final float[] pcm; final Gain.Stats st; final int voicedMs; final boolean cut;
    /** Сами кадры куска, по порядку (те же объекты, что пришли в feed): по ним находятся L и R кадров
     *  (MicPair.Side), когда слушание пишет два микрофона. */
    final float[][] src;
    Piece(float[] pcm, Gain.Stats st, int voicedMs, boolean cut, float[][] src) { this.pcm = pcm; this.st = st; this.voicedMs = voicedMs; this.cut = cut; this.src = src; }
  }

  final ArrayDeque<float[]> pre = new ArrayDeque<>(); final ArrayDeque<Gain.Stats> preSt = new ArrayDeque<>(); final ArrayDeque<Double> preLvl = new ArrayDeque<>();
  final List<float[]> seg = new ArrayList<>(); final List<Gain.Stats> segSt = new ArrayList<>(); final List<Double> segLvl = new ArrayList<>();
  /** Идёт речь (кусок открыт). Читается другим потоком — загрузкой голосов (TranslatorService.loadTtsLate). */
  volatile boolean inSpeech;
  int silent, voiced;

  int preFrames() { return Math.max(1, preRollMs / FRAME_MS); }

  /** Кадр win с его счётчиками входа, признаком речи и уровнем (RMS). Вернёт закрытый кусок или null. */
  Piece feed(float[] win, Gain.Stats fs, boolean speech, double rms) {
    if (!inSpeech) {
      pre.addLast(win); preSt.addLast(fs); preLvl.addLast(rms);
      while (pre.size() > preFrames()) { pre.removeFirst(); preSt.removeFirst(); preLvl.removeFirst(); }
      if (!speech) return null;
      seg.clear(); segSt.clear(); segLvl.clear();
      seg.addAll(pre); segSt.addAll(preSt); segLvl.addAll(preLvl);
      pre.clear(); preSt.clear(); preLvl.clear();
      inSpeech = true; voiced = 1; silent = 0;
      return null;
    }
    seg.add(win); segSt.add(fs); segLvl.add(rms);
    if (speech) { voiced++; silent = 0; } else silent++;
    boolean quiet = silent * FRAME_MS >= hangMs;                 // выдержка только здесь, у sherpa её нет
    if (quiet) return close();
    return seg.size() * FRAME_MS >= maxSpeechMs ? cut() : null;
  }

  /** Кадры кончились: открытый кусок закрывается тем, что есть (хвост — как при выдержке), подпор сбрасывается. */
  Piece gap() {
    Piece p = inSpeech ? piece(keep(), false) : null;
    reset();
    return p;
  }

  void reset() {
    pre.clear(); preSt.clear(); preLvl.clear(); seg.clear(); segSt.clear(); segLvl.clear();
    inSpeech = false; silent = 0; voiced = 0;
  }

  /** Сколько кадров куска оставить: хвост tailMs после последней речи, лишняя тишина режется. */
  int keep() { return seg.size() - Math.max(0, silent - tailMs / FRAME_MS); }

  /** Закрытие по выдержке: отрезанная тишина (не больше подпора) — в подпор следующего куска. */
  Piece close() {
    Piece p = piece(keep(), false);
    int carry = Math.min(silent, preFrames());
    pre.clear(); preSt.clear(); preLvl.clear();
    for (int k = seg.size() - carry; k < seg.size(); k++) { pre.addLast(seg.get(k)); preSt.addLast(segSt.get(k)); preLvl.addLast(segLvl.get(k)); }
    seg.clear(); segSt.clear(); segLvl.clear();
    inSpeech = false; silent = 0; voiced = 0;
    return p;
  }

  /** Предел длины: разрез перед самым тихим кадром из последних CUT_MS (не раньше середины куска), остаток —
   *  начало следующего куска вместе с тем тихим кадром. */
  Piece cut() {
    int n = seg.size(), from = Math.max(n / 2, n - CUT_MS / FRAME_MS), at = n - 1;
    for (int k = from; k < n; k++) if (segLvl.get(k) < segLvl.get(at)) at = k;
    Piece p = piece(at, true);
    List<float[]> rest = new ArrayList<>(seg.subList(at, n)); List<Gain.Stats> restSt = new ArrayList<>(segSt.subList(at, n)); List<Double> restLvl = new ArrayList<>(segLvl.subList(at, n));
    seg.clear(); segSt.clear(); segLvl.clear(); seg.addAll(rest); segSt.addAll(restSt); segLvl.addAll(restLvl);
    silent = Math.min(silent, rest.size()); voiced = Math.max(1, rest.size() - silent);
    return p;
  }

  /** Кусок из первых n кадров — если речи в нём хватает; иначе null (кадры всё равно отработаны). */
  Piece piece(int n, boolean cut) {
    if (voiced * FRAME_MS < minSpeechMs || n <= 0) return null;
    int len = 0; for (int k = 0; k < n; k++) len += seg.get(k).length;
    float[] out = new float[len]; int o = 0; Gain.Stats st = new Gain.Stats(); float[][] src = new float[n][];
    for (int k = 0; k < n; k++) { float[] c = seg.get(k); System.arraycopy(c, 0, out, o, c.length); o += c.length; st.add(segSt.get(k)); src[k] = c; }
    return new Piece(out, st, voiced * FRAME_MS, cut, src);
  }
}
