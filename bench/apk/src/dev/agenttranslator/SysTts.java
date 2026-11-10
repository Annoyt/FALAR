package dev.agenttranslator;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Голос перевода — синтез речи телефона (Android TextToSpeech), с 0.38.0 вместо Piper.
 *
 *  Почему (results/2026-11-10-tts-system.md, владелец 10.11 — «Да, переходим»):
 *  - в Piper статически собран eSpeak NG под GPL, который не может лежать в одном APK с закрытыми библиотеками
 *    Qualcomm для NPU;
 *  - голоса dmitri и faber дообучены от lessac, а его записи — только для исследований;
 *  - на Redmi синтез Google без сети разборчивее (ru 0–2 % ошибок распознавания синтеза против 12,5 %) и даёт
 *    первый звук за 40–70 мс против 270.
 *
 *  Свой TextToSpeech у каждого языка (Redmi 10.11): с одним на два языка смена голоса между фразами стоила Google
 *  1,3–2,0 с до первого звука (в разговоре языки чередуются), без смены — 40–80 мс.
 *
 *  Звук играет сам Falar: синтез идёт в файл (synthesizeToFile), а его куски приходят сюда (onAudioAvailable) и
 *  уходят в ту же дорожку, что раньше у Piper. Поэтому разводка по выходам, перебивание, «Что озвучивать» и
 *  подавление эха остаются прежними. Голоса берутся только те, что работают без сети. Голоса языка нет — перевод
 *  только на экране, а в «Модули и файлы» — кнопка скачать голос (installIntent). Движок — Google, если он стоит,
 *  иначе движок телефона по умолчанию. */
final class SysTts {
  static final String GOOGLE = "com.google.android.tts";
  static final String[] LANGS = {"ru", "pt"};
  interface Log { void log(String s); }
  /** Кусок звука: 1 — дальше, 0 — остановить синтез (перебили). */
  interface Chunk { int on(float[] samples); }

  /** Синтезированное целиком (в частоте дорожки rate; src — частота самого движка). Имена методов — как у прежнего
   *  GeneratedAudio sherpa-onnx. */
  static final class Audio {
    final float[] samples; final int rate, src;
    Audio(float[] samples, int rate, int src) { this.samples = samples; this.rate = rate; this.src = src; }
    float[] getSamples() { return samples; }
    int getSampleRate() { return rate; }
  }

  final Context ctx; final Log log; final String engine;
  /** Свой синтез у каждого языка: голос ставится один раз и не меняется между фразами. */
  final Map<String, TextToSpeech> tts = new java.util.concurrent.ConcurrentHashMap<>();
  /** Голос, стоящий сейчас в синтезе языка (setVoice — только при смене). */
  final Map<String, String> set = new java.util.concurrent.ConcurrentHashMap<>();
  /** Голос по языку (ru, pt) — только без сети; нет ключа — нет голоса, причина в missing. */
  final Map<String, Voice> voice = new java.util.concurrent.ConcurrentHashMap<>();
  final Map<String, String> missing = new java.util.concurrent.ConcurrentHashMap<>();
  /** Частота звука голоса (по пробной фразе): по ней открывается дорожка до синтеза. */
  final Map<String, Integer> rate = new java.util.concurrent.ConcurrentHashMap<>();
  /** Все голоса движка для ru-RU и pt-BR без сети — выбор в настройках. */
  final Map<String, List<TtsVoices.V>> choices = new java.util.concurrent.ConcurrentHashMap<>();
  /** Скорость речи по языку («Настройки» → «Скорость речи», 0,5–2×, TtsVoices.speed); нет ключа — 1×. Читается
   *  при каждой фразе, поэтому новая скорость — с ближайшей фразы. */
  final Map<String, Float> speed = new java.util.concurrent.ConcurrentHashMap<>();
  private volatile Job cur;
  private int seq;
  private final File out;

  private SysTts(Context ctx, Log log, String engine) {
    this.ctx = ctx; this.log = log; this.engine = engine;
    this.out = new File(ctx.getCacheDir(), "tts-out.wav");
  }

  /** Синтез на движке pkg (null — по умолчанию), ждёт до 15 с; не поднялся — null. */
  static TextToSpeech bind(Context app, String pkg) throws InterruptedException {
    CountDownLatch l = new CountDownLatch(1); final int[] st = {TextToSpeech.ERROR};
    TextToSpeech.OnInitListener on = s -> { st[0] = s; l.countDown(); };
    TextToSpeech t = pkg == null ? new TextToSpeech(app, on) : new TextToSpeech(app, on, pkg);
    if (l.await(15, TimeUnit.SECONDS) && st[0] == TextToSpeech.SUCCESS) return t;
    t.shutdown(); return null;
  }

  /** Поднять синтез: Google, если стоит, иначе движок по умолчанию; ждёт до 15 с. Не вышло — null, и перевод
   *  только на экране. chosen — голоса, выбранные человеком (имя по языку, может не быть). */
  static SysTts open(Context ctx, Map<String, String> chosen, Map<String, Float> speeds, Log log) throws InterruptedException {
    Context app = ctx.getApplicationContext();
    for (String pkg : new String[]{GOOGLE, null}) {
      long t0 = System.nanoTime();
      TextToSpeech t = bind(app, pkg);
      if (t != null) {
        String eng = pkg != null ? pkg : t.getDefaultEngine();
        if (eng == null) eng = "";
        SysTts s = new SysTts(app, log, eng);
        s.tts.put(LANGS[0], t); t.setOnUtteranceProgressListener(s.listener);
        for (int k = 1; k < LANGS.length; k++) {            // второй язык — свой синтез того же движка
          TextToSpeech t2 = bind(app, eng.isEmpty() ? null : eng);
          if (t2 != null) { t2.setOnUtteranceProgressListener(s.listener); s.tts.put(LANGS[k], t2); }
        }
        long t1 = System.nanoTime();
        if (speeds != null) for (Map.Entry<String, Float> e : speeds.entrySet()) s.setSpeed(e.getKey(), e.getValue());
        s.pick(chosen);
        s.warm();
        log.log(String.format(Locale.ROOT, "🔊 синтез телефона: подключение %d мс, пробные фразы %d мс", (t1 - t0) / 1000000, (System.nanoTime() - t1) / 1000000));
        return s;
      }
      if (pkg != null) log.log("🔊 синтез Google не поднялся — пробую движок телефона по умолчанию");
    }
    log.log("🔊 синтез речи телефона не поднялся — перевод только на экране");
    return null;
  }

  /** Голоса для ru и pt — по TtsVoices.usable; выбор человека (chosen) — первым, если он без сети. */
  synchronized void pick(Map<String, String> chosen) {
    List<TtsVoices.V> all = new ArrayList<>(); Map<String, Voice> byName = new HashMap<>();
    java.util.Set<Voice> vs = null;
    TextToSpeech any = tts.get(LANGS[0]);
    try { if (any != null) vs = any.getVoices(); } catch (Throwable ignore) {}
    if (vs != null) for (Voice v : vs) {
      Locale lo = v.getLocale(); if (lo == null) continue;
      java.util.Set<String> f = v.getFeatures();
      all.add(new TtsVoices.V(v.getName(), lo.getLanguage(), lo.getCountry(), v.getQuality(), v.getLatency(), v.isNetworkConnectionRequired(),
          f == null || !f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED), f != null && f.contains("legacySetLanguageVoice")));
      byName.put(v.getName(), v);
    }
    voice.clear(); missing.clear(); choices.clear();
    for (String lang : LANGS) {
      List<TtsVoices.V> u = TtsVoices.usable(all, lang, chosen == null ? null : chosen.get(lang));
      // для «Голосов» — порядок без учёта выбора: «голос 3» остаётся третьим и после того, как его выбрали
      choices.put(lang, TtsVoices.usable(all, lang, null));
      if (u.isEmpty()) missing.put(lang, TtsVoices.missing(all, lang));
      else if (!tts.containsKey(lang)) missing.put(lang, L.k("синтез речи телефона не поднялся"));
      else voice.put(lang, byName.get(u.get(0).name));
    }
  }

  /** Пробные фразы голоса — обычные предложения: после «Да.» первая настоящая фраза ждала первого звука 640–780 мс
   *  (Redmi 10.11), остальные — 50–70 мс: короткое слово поднимает не всё. Заодно частота звука — дорожка
   *  открывается до синтеза. */
  static final String[] WARM_RU = {"Здравствуйте! Как у вас дела сегодня?"}, WARM_PT = {"Olá! Como vai você hoje?"};
  void warm() { for (String lang : LANGS) warm(lang); }
  void warm(String lang) {
    if (!voice.containsKey(lang)) return;
    for (String t : "ru".equals(lang) ? WARM_RU : WARM_PT) {
      Audio a = speak(lang, t, x -> 1);
      if (a.src > 0) rate.put(lang, a.src);
    }
  }

  boolean has(String lang) { return voice.containsKey(lang); }
  boolean any() { return !voice.isEmpty(); }
  int rate(String lang) { Integer r = rate.get(lang); return r != null ? r : 24000; }
  String voiceName(String lang) { Voice v = voice.get(lang); return v == null ? "" : v.getName(); }
  float speed(String lang) { Float x = speed.get(lang); return x != null ? x : 1f; }
  void setSpeed(String lang, float x) { speed.put(lang, TtsVoices.speed(x)); }

  /** Строка для журнала: «com.google.android.tts · ru ru-ru-x-dfc-local · pt — голос не скачан». */
  String describe() {
    StringBuilder b = new StringBuilder(engine);
    for (String lang : LANGS) b.append(" · ").append(lang).append(' ').append(has(lang) ? voiceName(lang) + " " + rate(lang) + " Гц" : "— " + missing.get(lang));
    return b.toString();
  }

  /** Окно движка «скачать голос» (Google: голоса без сети в настройках синтеза). */
  Intent installIntent() {
    Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (!engine.isEmpty()) i.setPackage(engine);
    return i;
  }

  /** Сменить голос языка (выбор в настройках). */
  synchronized boolean setVoice(String lang, String name) {
    Map<String, String> ch = new HashMap<>();
    for (String l : LANGS) ch.put(l, l.equals(lang) ? name : voiceName(l));
    pick(ch);
    rate.remove(lang);
    warm(lang);
    return name.equals(voiceName(lang));
  }

  /** Синтез фразы: куски — в cb по мере готовности, на этом же потоке (как generateWithCallback у Piper); cb вернул
   *  0 — синтез останавливается. Голоса языка нет или синтез не удался — пустой звук. Тихий хвост фразы срезается
   *  до 60 мс (TrimTail). */
  synchronized Audio speak(String lang, String text, Chunk cb) {
    Voice v = voice.get(lang); int want = rate(lang); TextToSpeech tts = this.tts.get(lang);
    if (v == null || tts == null || text == null || text.trim().isEmpty()) return new Audio(new float[0], want, 0);
    Job j = new Job("f" + (++seq)); cur = j;
    List<float[]> parts = new ArrayList<>(); int n = 0; boolean stop = false;
    try {
      if (!v.getName().equals(set.get(lang))) { tts.setVoice(v); set.put(lang, v.getName()); }
      tts.setSpeechRate(speed(lang));
      if (tts.synthesizeToFile(text, new Bundle(), out, j.id) != TextToSpeech.SUCCESS) { log.log("🔊 синтез не принял фразу"); return new Audio(new float[0], want, 0); }
      TtsVoices.TrimTail trim = new TtsVoices.TrimTail(0.003f, want * 60 / 1000);
      TtsVoices.Resampler rs = null;
      while (true) {
        Object o = j.q.poll(10, TimeUnit.SECONDS);
        if (o == null) { log.log("🔊 синтез молчит 10 с — останавливаю"); tts.stop(); break; }
        if (!(o instanceof byte[])) break;                       // готово, ошибка или остановлен
        if (stop) continue;                                      // остановили: дочитываем до конца без звука
        float[] s = TtsVoices.pcm((byte[]) o, j.format, j.channels);
        if (j.rate > 0 && j.rate != want) { if (rs == null) rs = new TtsVoices.Resampler(j.rate, want); s = rs.push(s); }
        s = trim.push(s);
        if (s.length == 0) continue;
        parts.add(s); n += s.length;
        if (cb.on(s) == 0) { stop = true; tts.stop(); }
      }
      if (!stop) { float[] t = trim.end(); if (t.length > 0) { cb.on(t); parts.add(t); n += t.length; } }
    } catch (InterruptedException e) {
      tts.stop(); Thread.currentThread().interrupt();
    } finally { cur = null; out.delete(); }
    float[] all = new float[n]; int o = 0;
    for (float[] p : parts) { System.arraycopy(p, 0, all, o, p.length); o += p.length; }
    return new Audio(all, want, j.rate);
  }

  void close() { for (TextToSpeech t : tts.values()) try { t.stop(); t.shutdown(); } catch (Throwable ignore) {} tts.clear(); }

  /** Фраза в синтезе: куски звука и конец — очередью к потоку, который её произносит. */
  static final class Job {
    final String id; final BlockingQueue<Object> q = new LinkedBlockingQueue<>();
    volatile int rate, format = 2, channels = 1;
    Job(String id) { this.id = id; }
  }
  static final Object END = new Object();

  private final UtteranceProgressListener listener = new UtteranceProgressListener() {
    Job job(String id) { Job j = cur; return j != null && j.id.equals(id) ? j : null; }
    @Override public void onStart(String id) {}
    @Override public void onBeginSynthesis(String id, int r, int format, int channels) {
      Job j = job(id); if (j != null) { j.rate = r; j.format = format; j.channels = channels; }
    }
    @Override public void onAudioAvailable(String id, byte[] audio) { Job j = job(id); if (j != null) j.q.add(audio); }
    @Override public void onDone(String id) { Job j = job(id); if (j != null) j.q.add(END); }
    @Override public void onError(String id) { Job j = job(id); if (j != null) j.q.add(END); }
    @Override public void onError(String id, int code) { Job j = job(id); if (j != null) j.q.add(END); }
    @Override public void onStop(String id, boolean interrupted) { Job j = job(id); if (j != null) j.q.add(END); }
  };
}
