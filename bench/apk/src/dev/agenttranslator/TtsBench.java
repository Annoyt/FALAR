package dev.agenttranslator;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import com.k2fsa.sherpa.onnx.DenoisedAudio;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Стенд озвучки (владелец 10.11: «проверяй озвучки» — замена Piper с eSpeak NG под GPL; до 0.38.0 здесь был и
 *  Piper — results/2026-11-10-tts-system.md). Те же фразы — путём приложения (Engine.speak, SysTts) и напрямую
 *  системным синтезом Android (TextToSpeech: голос языка без сети, лучшего качества или каждый). По каждой фразе: время до первого звука, всё время синтеза,
 *  длина звука; звук — в WAV: разборчивость считает ПК (bench/tts/tts_wer.py — parakeet по синтезу, WER против текста).
 *
 *  Только стенд: --es ttsbench ФАЙЛ (в каталоге приложения: «язык<TAB>текст» в строке) [--es ttsengines app,system]
 *  [--es ttsrate 1.15]. Движок system-all — каждый голос языка без сети, а не только первый лучший (выбор голоса).
 *  Итог — tts_bench/results.tsv и WAV там же; голоса системы — tts_bench/voices.tsv. */
final class TtsBench {
  private TtsBench() {}

  static final String DIR = "tts_bench";

  /** Фраза стенда: язык (ru, pt) и текст. */
  static final class Phrase { final String lang, text; Phrase(String l, String t) { lang = l; text = t; } }

  static List<Phrase> read(File f) throws Exception {
    List<Phrase> out = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
      for (String s; (s = r.readLine()) != null; ) {
        int t = s.indexOf('\t');
        if (t > 0 && !s.startsWith("#")) out.add(new Phrase(s.substring(0, t).trim(), s.substring(t + 1).trim()));
      }
    }
    return out;
  }

  /** Весь прогон: движки по очереди, фразы по порядку; первая фраза каждого голоса — прогрев, в счёт не идёт. */
  static void run(Context ctx, Engine eng, File phrases, String engines, float rate, Consumer<String> log) throws Exception {
    File dir = new File(ctx.getExternalFilesDir(null), DIR); dir.mkdirs();
    for (File old : dir.listFiles()) old.delete();
    List<Phrase> ps = read(phrases);
    log.accept("🔊 стенд озвучки: " + ps.size() + " фраз, движки " + engines);
    try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "results.tsv")), StandardCharsets.UTF_8))) {
      out.println("engine\tvoice\tlang\tn\tchars\tfirst_ms\ttotal_ms\taudio_ms\trate\tfile\ttext");
      for (String e : engines.split(",")) {
        e = e.trim();
        if (e.equals("app")) app(eng, ps, dir, out, log);
        else if (e.equals("system")) system(ctx, ps, dir, out, false, rate, log);
        else if (e.equals("system-all")) system(ctx, ps, dir, out, true, rate, log);
        else log.accept("🔊 стенд озвучки: не знаю движка " + e);
        out.flush();
      }
    }
    log.accept("🔊 стенд озвучки: готово — " + dir);
  }

  /** Путь приложения: Engine.speak — синтез телефона (SysTts) с выбранными голосами, срезом тихого хвоста и той же
   *  частотой, что у дорожки. Языки вперемешку (ru, pt, ru…), как в разговоре: видно, стоит ли смена голоса между
   *  фразами времени. Первые две фразы (по одной на язык) — прогрев. */
  static void app(Engine eng, List<Phrase> ps, File dir, PrintWriter out, Consumer<String> log) {
    if (eng == null || !eng.hasTts()) { log.accept("🔊 приложение: озвучки нет"); return; }
    List<Phrase> ru = new ArrayList<>(), pt = new ArrayList<>();
    for (Phrase p : ps) (p.lang.equals("ru") ? ru : pt).add(p);
    eng.speak("ru", "Да.", c -> 1); eng.speak("pt", "Sim.", c -> 1);
    java.util.Map<String, List<long[]>> stat = new java.util.HashMap<>();
    for (int k = 0; k < Math.max(ru.size(), pt.size()); k++) {
      for (List<Phrase> l : java.util.Arrays.asList(ru, pt)) {
        if (k >= l.size()) continue;
        Phrase p = l.get(k);
        long t0 = System.nanoTime(); final long[] first = {0};
        SysTts.Audio a = eng.speak(p.lang, p.text, c -> { if (first[0] == 0) first[0] = System.nanoTime(); return 1; });
        long t1 = System.nanoTime();
        int rate = a.getSampleRate(); long audioMs = a.getSamples().length * 1000L / rate;
        String name = String.format(Locale.ROOT, "app_%s_%02d.wav", p.lang, k);
        new DenoisedAudio(a.getSamples(), rate).save(new File(dir, name).getAbsolutePath());
        long fm = first[0] == 0 ? -1 : (first[0] - t0) / 1_000_000, tm = (t1 - t0) / 1_000_000;
        String voice = eng.sys == null ? "?" : eng.sys.engine + ":" + eng.sys.voiceName(p.lang);
        out.println("app\t" + voice + "\t" + p.lang + "\t" + k + "\t" + p.text.length() + "\t" + fm + "\t" + tm + "\t" + audioMs + "\t" + rate + "\t" + name + "\t" + p.text);
        stat.computeIfAbsent(p.lang, x -> new ArrayList<>()).add(new long[]{fm, tm, audioMs});
      }
    }
    for (String lang : new String[]{"ru", "pt"}) if (stat.containsKey(lang)) log.accept("🔊 приложение " + lang + ": " + summary(stat.get(lang)));
  }

  /** Системный синтез: голос языка без сети (лучший по качеству, потом по задержке); звук — через synthesizeToFile,
   *  куски — onAudioAvailable (так приложение могло бы играть его своим AudioTrack, как Piper). */
  static void system(Context ctx, List<Phrase> ps, File dir, PrintWriter out, boolean all, float speechRate, Consumer<String> log) throws Exception {
    final CountDownLatch init = new CountDownLatch(1); final int[] st = {-1};
    final TextToSpeech tts = new TextToSpeech(ctx, s -> { st[0] = s; init.countDown(); });
    try {
      if (!init.await(20, TimeUnit.SECONDS) || st[0] != TextToSpeech.SUCCESS) { log.accept("🔊 система: синтез не поднялся (" + st[0] + ")"); return; }
      String engine = tts.getDefaultEngine();
      List<Voice> voices = all_(tts);
      try (PrintWriter vw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, "voices.tsv")), StandardCharsets.UTF_8))) {
        vw.println("engine\tname\tlocale\tquality\tlatency\tnetwork\tnot_installed\tfeatures");
        for (Voice v : voices) {
          String l = v.getLocale().getLanguage();
          if (!l.equals("ru") && !l.equals("pt")) continue;
          vw.println(engine + "\t" + v.getName() + "\t" + v.getLocale() + "\t" + v.getQuality() + "\t" + v.getLatency() + "\t" + v.isNetworkConnectionRequired()
              + "\t" + v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) + "\t" + v.getFeatures());
        }
      }
      for (String lang : new String[]{"ru", "pt"}) {
        List<Phrase> mine = new ArrayList<>(); for (Phrase p : ps) if (p.lang.equals(lang)) mine.add(p);
        if (mine.isEmpty()) continue;
        List<Voice> cand = new ArrayList<>();
        for (Voice v : all_(tts)) {
          Locale lo = v.getLocale();
          if (!lo.getLanguage().equals(lang) || !lo.getCountry().equals(lang.equals("ru") ? "RU" : "BR")) continue;
          if (v.isNetworkConnectionRequired() || v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
          if (v.getFeatures().contains("legacySetLanguageVoice")) continue;      // «ru-RU-language» — тот же голос под другим именем
          cand.add(v);
        }
        cand.sort((a, b) -> a.getQuality() != b.getQuality() ? b.getQuality() - a.getQuality() : a.getLatency() != b.getLatency()
            ? a.getLatency() - b.getLatency() : a.getName().compareTo(b.getName()));
        if (cand.isEmpty()) { log.accept("🔊 система (" + engine + "): для " + lang + " нет голоса без сети — смотрите voices.tsv"); continue; }
        for (Voice best : all ? cand : cand.subList(0, 1)) {
          int set = tts.setVoice(best); tts.setSpeechRate(speechRate);
          log.accept("🔊 система: " + engine + " · " + best.getName() + " (качество " + best.getQuality() + ", задержка " + best.getLatency()
              + (speechRate != 1f ? ", темп " + speechRate : "") + ")" + (set == TextToSpeech.SUCCESS ? "" : " · setVoice=" + set));
          one(tts, mine.get(0).text, new File(dir, "warm.wav"));         // прогрев
          List<long[]> stat = new ArrayList<>();
          String tag = all ? best.getName().replaceAll("[^A-Za-z0-9]+", "-") : "system";
          for (int k = 0; k < mine.size(); k++) {
            Phrase p = mine.get(k);
            String name = String.format(Locale.ROOT, "%s_%s_%02d.wav", tag, lang, k);
            long[] r = one(tts, p.text, new File(dir, name));
            if (r == null) { log.accept("🔊 система: фраза " + k + " не синтезировалась"); continue; }
            out.println((all ? "sys-" + best.getName() : "system") + "\t" + engine + ":" + best.getName() + "\t" + lang + "\t" + k + "\t" + p.text.length() + "\t" + r[0] + "\t" + r[1] + "\t" + r[2] + "\t" + r[3] + "\t" + name + "\t" + p.text);
            stat.add(new long[]{r[0], r[1], r[2]});
          }
          log.accept("🔊 система " + best.getName() + ": " + summary(stat));
        }
      }
      new File(dir, "warm.wav").delete();
    } finally { tts.shutdown(); }
  }

  static List<Voice> all_(TextToSpeech tts) {
    return new ArrayList<>(tts.getVoices() == null ? Collections.<Voice>emptySet() : tts.getVoices());
  }

  /** Одна фраза в файл: {до первого куска звука, всё время, длина звука мс, частота} или null. */
  static long[] one(TextToSpeech tts, String text, File f) throws Exception {
    final CountDownLatch done = new CountDownLatch(1);
    final long[] first = {0}, bytes = {0}; final int[] fmt = {0, 0, 0}; final boolean[] err = {false};
    String id = "b" + System.nanoTime();
    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
      @Override public void onStart(String u) {}
      @Override public void onBeginSynthesis(String u, int rate, int format, int channels) { fmt[0] = rate; fmt[1] = format; fmt[2] = channels; }
      @Override public void onAudioAvailable(String u, byte[] a) { if (first[0] == 0) first[0] = System.nanoTime(); bytes[0] += a.length; }
      @Override public void onDone(String u) { done.countDown(); }
      @Override public void onError(String u) { err[0] = true; done.countDown(); }
      @Override public void onError(String u, int code) { err[0] = true; done.countDown(); }
    });
    long t0 = System.nanoTime();
    if (tts.synthesizeToFile(text, new Bundle(), f, id) != TextToSpeech.SUCCESS) return null;
    if (!done.await(60, TimeUnit.SECONDS) || err[0]) return null;
    long t1 = System.nanoTime();
    // байт на отсчёт: AudioFormat.ENCODING_PCM_16BIT = 2, PCM_FLOAT = 4, PCM_8BIT = 3
    int bps = fmt[1] == 4 ? 4 : fmt[1] == 3 ? 1 : 2, ch = Math.max(1, fmt[2]);
    long audioMs = fmt[0] > 0 ? bytes[0] * 1000L / ((long) fmt[0] * bps * ch) : 0;
    return new long[]{first[0] == 0 ? -1 : (first[0] - t0) / 1_000_000, (t1 - t0) / 1_000_000, audioMs, fmt[0]};
  }

  /** «до звука медиана N мс, синтез M мс на секунду звука (RTF), фраз K». */
  static String summary(List<long[]> s) {
    if (s.isEmpty()) return "нет фраз";
    List<Long> f = new ArrayList<>(); long tot = 0, aud = 0;
    for (long[] x : s) { f.add(x[0]); tot += x[1]; aud += x[2]; }
    Collections.sort(f);
    return String.format(Locale.ROOT, "до звука медиана %d мс (наибольшее %d), синтез %.2f с на 1 с звука, фраз %d",
        f.get(f.size() / 2), f.get(f.size() - 1), aud > 0 ? tot / (double) aud : 0, s.size());
  }
}
