package dev.agenttranslator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Голоса синтеза телефона (SysTts) и обработка его звука — без Android: проверяется на столе (TtsVoicesTest).
 *
 *  Выбор голоса: только те, что работают без сети и уже скачаны (offline-first); первым — выбранный человеком в
 *  настройках, затем самый разборчивый по замеру (BEST), затем по качеству, задержке и имени. Голос-псевдоним
 *  («ru-RU-language» у Google — голос языка по умолчанию под другим именем) в выбор не идёт. */
final class TtsVoices {
  private TtsVoices() {}

  /** Голос движка: имя, язык и страна (ru RU, pt BR), качество и задержка (Voice.QUALITY_*, LATENCY_*), нужна ли
   *  сеть, скачан ли, псевдоним ли. */
  static final class V {
    final String name, lang, country; final int quality, latency; final boolean network, installed, alias;
    V(String name, String lang, String country, int quality, int latency, boolean network, boolean installed, boolean alias) {
      this.name = name; this.lang = lang; this.country = country; this.quality = quality; this.latency = latency;
      this.network = network; this.installed = installed; this.alias = alias;
    }
    @Override public String toString() { return name; }
  }

  /** Самые разборчивые голоса Google без сети по замеру на Redmi 10.11.2026 (results/2026-11-10-tts-system.md): синтез
   *  30 фраз Tatoeba распознаёт parakeet — ru dfc 0 ошибок на 184 слова, pt afs 2 на 186 (у остальных до 4). */
  static final Map<String, String> BEST = new HashMap<>();
  static { BEST.put("ru", "ru-ru-x-dfc-local"); BEST.put("pt", "pt-br-x-afs-local"); }

  /** Скорость речи — своя у каждого языка, 0,5–2× с шагом 0,1 (владелец 10.11: «от 0.5 до 2х»). Google на 1×
   *  говорит на 14–17 % медленнее прежнего Piper (замер 10.11), но по умолчанию 1× — темп самого движка. Ползунок:
   *  позиция 0 — 0,5×, 5 — 1×, 15 — 2×. */
  static final float SPEED_MIN = 0.5f, SPEED_MAX = 2f;
  static final int SPEED_STEPS = 15;
  /** В пределах 0,5–2×, до десятых; не число — 1×. */
  static float speed(float x) {
    if (Float.isNaN(x) || Float.isInfinite(x)) return 1f;
    return Math.round(Math.max(SPEED_MIN, Math.min(SPEED_MAX, x)) * 10) / 10f;
  }
  static float speedAt(int pos) { return speed(SPEED_MIN + pos / 10f); }
  static int speedPos(float x) { return Math.round((speed(x) - SPEED_MIN) * 10); }
  /** «1,0×» — с запятой, как пишут и по-русски, и по-бразильски. */
  static String speedLabel(float x) { return String.format(java.util.Locale.ROOT, "%.1f×", speed(x)).replace('.', ','); }

  static final String GOOGLE = "com.google.android.tts";
  /** Скорость речи → значение для TextToSpeech.setSpeechRate. Google быстрее 1× говорит как просили (1,5 → 1,49, 2 →
   *  1,99, 3 → 2,97), а медленнее 1× замедляет слабее: на деле 1 − 0,62·(1 − r), и не медленнее, чем при r = 0,2
   *  (0,504×). Замер на Redmi 10.11, все 8 голосов без сети одинаково: 0,5 → 0,69, 0,25 → 0,54, 0,1–0,2 → 0,504
   *  (.stand/tts/rate-curve-*). Поэтому медленное для Google пересчитывается: 0,5× → 0,2, 0,7× → 0,52, 0,8× → 0,68. У
   *  других движков замеров нет — как просили (так в документации Android: 0,5 — вдвое медленнее). Android передаёт
   *  движку целые проценты, отбрасывая дробь, — значение берётся чуть выше нужного процента. */
  static float engineRate(String engine, float x) {
    float t = speed(x), r = t >= 1f || !GOOGLE.equals(engine) ? t : Math.max(0.2f, (t - 0.38f) / 0.62f);
    return (Math.round(r * 100) + 0.5f) / 100f;
  }

  /** Страна языка Falar: русский — Россия, португальский — Бразилия. */
  static String country(String lang) { return "ru".equals(lang) ? "RU" : "pt".equals(lang) ? "BR" : ""; }

  static boolean mine(V v, String lang) {
    return v.lang.equalsIgnoreCase(lang) && v.country.equalsIgnoreCase(country(lang));
  }

  /** Голоса языка, которыми можно говорить без сети, по порядку предпочтения; chosen — имя, выбранное человеком (или
   *  null). Пусто — голоса нет (missing скажет почему). */
  static List<V> usable(List<V> all, String lang, String chosen) {
    List<V> out = new ArrayList<>();
    for (V v : all) if (mine(v, lang) && !v.network && v.installed && !v.alias) out.add(v);
    String best = BEST.get(lang);
    out.sort((a, b) -> {
      int ra = rank(a, chosen, best), rb = rank(b, chosen, best);
      if (ra != rb) return ra - rb;
      if (a.quality != b.quality) return b.quality - a.quality;
      if (a.latency != b.latency) return a.latency - b.latency;
      return a.name.compareTo(b.name);
    });
    return out;
  }

  static int rank(V v, String chosen, String best) {
    if (chosen != null && v.name.equals(chosen)) return 0;
    if (best != null && v.name.equals(best)) return 1;
    return 2;
  }

  /** Почему по-этому-языку голоса без сети нет (надпись L.k), или null — есть. «не скачан» — движок язык знает, но
   *  голос только по сети или ещё не загружен: его можно поставить (TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA). */
  static String missing(List<V> all, String lang) {
    if (!usable(all, lang, null).isEmpty()) return null;
    for (V v : all) if (mine(v, lang)) return L.k("голос не скачан");
    return L.k("нет голоса");
  }

  /** Звук движка — в float: AudioFormat.ENCODING_PCM_16BIT (2), PCM_8BIT (3), PCM_FLOAT (4); каналы — среднее. */
  static float[] pcm(byte[] b, int format, int channels) {
    int ch = Math.max(1, channels), bps = format == 4 ? 4 : format == 3 ? 1 : 2, n = b.length / (bps * ch);
    float[] out = new float[n];
    for (int i = 0; i < n; i++) {
      float sum = 0;
      for (int c = 0; c < ch; c++) {
        int o = (i * ch + c) * bps;
        if (bps == 2) sum += (short) ((b[o] & 0xff) | (b[o + 1] << 8)) / 32768f;
        else if (bps == 1) sum += ((b[o] & 0xff) - 128) / 128f;
        else sum += Float.intBitsToFloat((b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24);
      }
      out[i] = sum / ch;
    }
    return out;
  }

  /** Линейная передискретизация потоком (куски подряд без щелчков на стыках): звук движка в частоту дорожки, если
   *  они разошлись (дорожку открывают до синтеза по частоте пробной фразы). */
  static final class Resampler {
    final double step; double pos; float last; boolean started;
    Resampler(int from, int to) { step = from / (double) to; }
    float[] push(float[] x) {
      if (x.length == 0) return x;
      // позиции выхода — в координатах «прежний последний отсчёт = −1, x[0] = 0»
      List<Float> out = new ArrayList<>();
      if (!started) { started = true; pos = 0; last = x[0]; }
      while (pos < x.length - 1 + 1e-9) {
        int i = (int) Math.floor(pos); double f = pos - i;
        float a = i < 0 ? last : x[i], b = i + 1 < 0 ? last : (i + 1 < x.length ? x[i + 1] : x[x.length - 1]);
        out.add((float) (a + (b - a) * f));
        pos += step;
      }
      pos -= x.length; last = x[x.length - 1];
      float[] r = new float[out.size()]; for (int k = 0; k < r.length; k++) r[k] = out.get(k);
      return r;
    }
  }

  /** Тишина в конце фразы: у Google 257–294 мс против 130 у Piper (замер 10.11) — микрофон глух и очередь ждёт её
   *  зря. Тихие отсчёты в конце куска придерживаются; пришёл звук — отдаются перед ним; конец фразы — от них остаётся
   *  не больше keep (мягкий конец). */
  static final class TrimTail {
    final float level; final int keep; float[] held = new float[0];
    TrimTail(float level, int keep) { this.level = level; this.keep = keep; }
    float[] push(float[] x) {
      int last = -1;
      for (int i = x.length - 1; i >= 0; i--) if (Math.abs(x[i]) > level) { last = i; break; }
      if (last < 0) { held = cat(held, x, x.length); return new float[0]; }
      float[] out = cat(held, x, last + 1);
      held = new float[x.length - last - 1]; System.arraycopy(x, last + 1, held, 0, held.length);
      return out;
    }
    /** Конец фразы: хвост тишины — не длиннее keep. */
    float[] end() { int n = Math.min(keep, held.length); float[] r = new float[n]; System.arraycopy(held, 0, r, 0, n); held = new float[0]; return r; }
    static float[] cat(float[] a, float[] b, int nb) {
      float[] r = new float[a.length + nb]; System.arraycopy(a, 0, r, 0, a.length); System.arraycopy(b, 0, r, a.length, nb); return r;
    }
  }
}
