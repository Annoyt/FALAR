package dev.agenttranslator;

import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/**
 * Отпечаток голоса: 3D-Speaker CAM++ через sherpa-onnx. Точность распознавания не меняет —
 * нужен, чтобы понять, КТО говорит, а вместе с профилем хранится и НА КАКОМ ЯЗЫКЕ он говорит.
 * Направление перевода выводится из языка опознанного голоса, а не зашито.
 * Модель опциональна: нет файла — вся функция выключена.
 */
public class Speaker {
  public static final String ME = "я", OTHER = "собеседник";
  // Порог по замеру на живой речи (Tatoeba, 11 записей): один диктор сам с собой 0.83-0.96,
  // разные дикторы 0.13-0.30, серая зона 0.4-0.7. Синтезированная речь для проверки не годится:
  // у одного и того же голоса Piper сходство между фразами падает до 0.34.
  static final float THRESHOLD = 0.60f;
  static final float MIN_SECONDS = 0.6f;

  public static class Profile { public final float[] e; public final String lang;
    Profile(float[] e, String lang) { this.e = e; this.lang = lang; } }

  final File dir; SpeakerEmbeddingExtractor ex;
  final Map<String, Profile> prof = new LinkedHashMap<>();
  public boolean ready = false; public long loadMs = -1;
  public volatile float lastScore = 0;

  public Speaker(File modelsDir) {
    dir = modelsDir;
    File md = new File(modelsDir, "speaker");
    File[] f = md.listFiles((d, n) -> n.endsWith(".onnx"));
    if (f == null || f.length == 0) return;
    try {
      long t = System.currentTimeMillis();
      ex = new SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder()
          .setModel(f[0].getAbsolutePath()).setNumThreads(2).setDebug(false).build());
      loadMs = System.currentTimeMillis() - t; ready = true;
      load();
    } catch (Throwable t) { t.printStackTrace(); ready = false; }
  }

  public float[] embed(float[] samples, int sr) {
    if (!ready || samples.length < MIN_SECONDS * sr) return null;
    OnlineStream s = ex.createStream();
    try {
      s.acceptWaveform(samples, sr);
      s.acceptWaveform(new float[sr / 2], sr);      // хвост тишины, чтобы добрать последний кадр
      s.inputFinished();
      return ex.isReady(s) ? ex.compute(s) : null;
    } finally { s.release(); }
  }

  static float[] unit(float[] v) {
    if (v == null) return null;
    double n = 0; for (float x : v) n += x * x; n = Math.sqrt(n);
    if (n <= 0) return v;
    float[] o = new float[v.length]; for (int i = 0; i < v.length; i++) o[i] = (float) (v[i] / n);
    return o;
  }
  static float cos(float[] a, float[] b) {
    if (a == null || b == null || a.length != b.length) return -1;
    double s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i];
    return (float) s;
  }

  /** Ближайший профиль или null. lastScore — косинус до него. */
  public String identify(float[] samples, int sr) {
    float[] e = unit(embed(samples, sr));
    if (e == null) { lastScore = 0; return null; }
    String best = null; float bs = -1;
    synchronized (this) { for (Map.Entry<String, Profile> p : prof.entrySet()) { float c = cos(e, p.getValue().e); if (c > bs) { bs = c; best = p.getKey(); } } }
    lastScore = bs;
    return bs >= THRESHOLD ? best : null;
  }

  /** Язык профиля; null, если такого профиля нет. */
  public synchronized String langOf(String name) { Profile p = prof.get(name); return p == null ? null : p.lang; }
  /** Язык неопознанного голоса: считаем, что вокруг говорят не на моём языке. */
  public synchronized String fallbackLang() {
    Profile me = prof.get(ME);
    if (me == null) return "pt";
    return me.lang.equals("ru") ? "pt" : "ru";
  }

  public synchronized boolean enroll(String name, String lang, float[] samples, int sr) {
    float[] e = unit(embed(samples, sr));
    if (e == null) return false;
    prof.put(name, new Profile(e, lang));
    save(); return true;
  }

  public boolean has(String name) { return ready && prof.containsKey(name); }
  /** Забыть все голоса. Профиль — слепок конкретного человека, и оставлять его навсегда
   *  без способа стереть нельзя. */
  public synchronized int forget() { int n = prof.size(); prof.clear(); save(); return n; }
  public synchronized String describe() {
    if (prof.isEmpty()) return "профилей нет";
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, Profile> p : prof.entrySet()) { if (b.length() > 0) b.append(", "); b.append(p.getKey()).append('=').append(p.getValue().lang); }
    return b.toString();
  }

  File file() { return new File(dir, "speaker_profiles.json"); }
  void load() {
    try {
      File f = file(); if (!f.exists()) return;
      JSONObject j = new JSONObject(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
      for (Iterator<String> it = j.keys(); it.hasNext();) {
        String k = it.next(); Object v = j.get(k);
        JSONArray a; String lang;
        if (v instanceof JSONArray) { a = (JSONArray) v; lang = k.equals(ME) ? "ru" : "pt"; }   // старый формат без языка
        else { JSONObject o = (JSONObject) v; a = o.getJSONArray("e"); lang = o.optString("lang", "ru"); }
        float[] e = new float[a.length()];
        for (int i = 0; i < e.length; i++) e[i] = (float) a.getDouble(i);
        prof.put(k, new Profile(unit(e), lang));
      }
    } catch (Exception e) { e.printStackTrace(); }
  }
  synchronized void save() {
    try {
      JSONObject j = new JSONObject();
      for (Map.Entry<String, Profile> p : prof.entrySet()) {
        JSONArray a = new JSONArray(); for (float v : p.getValue().e) a.put(v);
        j.put(p.getKey(), new JSONObject().put("lang", p.getValue().lang).put("e", a));
      }
      Files.write(file().toPath(), j.toString().getBytes("UTF-8"));
    } catch (Exception ex2) { ex2.printStackTrace(); }
  }
}
