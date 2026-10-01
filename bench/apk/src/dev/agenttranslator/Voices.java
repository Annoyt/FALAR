package dev.agenttranslator;

import java.util.*;
import org.json.*;

/** Голоса разговора: слепок каждого, кто сказал фразу кнопкой FALAR в этом разговоре.
 *
 *  Зачем. Слушание в людном месте ловит обрывки чужих фраз — соседей за столиком, прохожих — и
 *  переводило их как реплики разговора. Теперь каждый участник хотя бы раз говорит кнопкой
 *  удержания, его голос запоминается в этом разговоре, и слушание переводит только голоса
 *  разговора. Чужой голос не переводится и в разговор не пишется (решение владельца 01.10).
 *
 *  Слепок — среднее отпечатков (Speaker.embed, CAM++, единичной длины) фраз, сказанных кнопкой.
 *  Номер голоса — порядок появления в разговоре: «собеседник 1, 2, 3». Язык — тот, на котором
 *  человек говорил кнопкой (большинство его фраз): он задаёт направление перевода при слушании,
 *  если текст сам не говорит иного.
 *
 *  Хранится в файле разговора (поле `voices`) и удаляется вместе с ним: слепок — биометрия
 *  человека, который мог и не знать, что его записывают. Общего хранилища голосов больше нет.
 *
 *  Без Android, проверяется на столе (bench/apk/test/VoicesTest.java). */
public class Voices {
  /** Фраза кнопкой — голос уже известного, если косинус к его слепку не ниже; иначе голос новый.
   *  Замер на живых записях Tatoeba (results/2026-10-01-voices.md, E2 near ↔ near, 2 pt + 1 ru):
   *  при 0,40 и слепке из одной фразы тот же человек узнан в 76 % (иначе — лишний номер), чужой
   *  слит с участником в 4 %; из трёх фраз — 93 % и 12 %. Слить двоих хуже, чем завести лишний
   *  номер: оба всё равно участники, но «Говорят двое» станет неправдой. */
  public static final float SAME = 0.40f;
  /** Слушание: сегмент — голос разговора, если косинус к его слепку не ниже. Тот же замер, E2 near →
   *  far (слепок с фраз кнопкой вблизи, сегмент издалека): при 0,40 чужих принято 3–9 %, своих —
   *  55–85 % (слепок из 1–3 фраз); на кусках речи от 1,5 с — до 86–97 %. Владельцу важнее не писать
   *  чужие обрывки, поэтому порог — у «чужих ≤ 5 %». При 0,60 (порог до замера) своих издалека
   *  принималось 5–21 %. */
  public static final float HEAR = 0.40f;
  /** Сколько фраз весит слепок: дальше новая фраза входит с весом 1/(K+1) — голос подстраивается,
   *  но одна неудачная фраза его не уводит. */
  static final int K = 8;
  /** Реплика владельца телефона в поле `who`: снимок с камеры или из галереи, набранная фраза — их вводит
   *  тот, кто держит телефон, а не голос разговора (владелец 01.10). */
  public static final String OWNER = "owner";
  /** Знаков после запятой в файле: отпечаток единичной длины, 512 чисел; пятый знак косинус уже не меняет. */
  static final double ROUND = 1e5;

  public static class Voice {
    /** Номер в разговоре: «собеседник n». */
    public final int n;
    /** Язык фраз кнопкой: ru или pt; ru, pt — сколько их было на каждом. */
    public String lang; public int ru, pt;
    /** Сколько фраз в слепке (не больше K). */
    public int k;
    float[] e;
    public long at;
    /** Имя — вписал человек (nameBy = user) или нашлось в разговоре (auto); автоматика имя человека не трогает. */
    public String name = "", nameBy = "";
    /** О чём говорит — коротко, по-русски. */
    public String says = "";
    Voice(int n, String lang, float[] e, long at) { this.n = n; this.lang = lang; this.e = e; this.at = at; k = 1; count(lang); }
    void count(String l) {
      if ("ru".equals(l)) ru++; else if ("pt".equals(l)) pt++;
      lang = ru > pt ? "ru" : pt > ru ? "pt" : lang;        // поровну — остаётся прежний
    }
    public float[] print() { return e; }
  }

  final List<Voice> list = new ArrayList<>();

  public synchronized int size() { return list.size(); }
  public synchronized boolean isEmpty() { return list.isEmpty(); }
  public synchronized List<Voice> all() { return new ArrayList<>(list); }
  public synchronized Voice get(int n) { for (Voice v : list) if (v.n == n) return v; return null; }
  /** Голос по полю реплики `who` («2»); null — у реплики голоса нет или он забыт. */
  public synchronized Voice get(String who) {
    if (who == null || who.isEmpty()) return null;
    try { return get(Integer.parseInt(who.trim())); } catch (NumberFormatException e) { return null; }
  }
  public synchronized int maxN() { int m = 0; for (Voice v : list) m = Math.max(m, v.n); return m; }

  /** Ближайший голос и второй за ним: по разнице видно, уверенно ли опознание. */
  public static class Match {
    public final Voice v, next; public final float score, nextScore;
    Match(Voice v, float score, Voice next, float nextScore) { this.v = v; this.score = score; this.next = next; this.nextScore = nextScore; }
    public boolean hit(float t) { return v != null && score >= t; }
  }
  public synchronized Match best(float[] e) {
    Voice b = null, s = null; float bs = -2, ss = -2;
    if (e != null) for (Voice v : list) {
      float c = cos(e, v.e);
      if (c > bs) { s = b; ss = bs; b = v; bs = c; } else if (c > ss) { s = v; ss = c; }
    }
    return new Match(b, b == null ? 0 : bs, s, s == null ? 0 : ss);
  }

  /** Фраза кнопкой FALAR: чей это голос. Совпал с известным — он, и его слепок подстраивается под
   *  фразу; не совпал ни с кем — новый голос с номером next. e — отпечаток единичной длины. */
  public synchronized Voice enroll(float[] e, String lang, long at, int next) {
    if (e == null) return null;
    Match m = best(e);
    if (m.hit(SAME)) { fold(m.v, e); m.v.count(lang); return m.v; }
    Voice v = new Voice(Math.max(next, maxN() + 1), lang, e.clone(), at);
    list.add(v);
    return v;
  }
  static void fold(Voice v, float[] e) {
    float[] o = new float[e.length];
    for (int i = 0; i < e.length; i++) o[i] = v.e[i] * v.k + e[i];
    v.e = unit(o); v.k = Math.min(K, v.k + 1);
  }

  /** Забыть все голоса разговора; сколько было. */
  public synchronized int clear() { int n = list.size(); list.clear(); return n; }

  /** Имя вписал человек — автоматика его больше не меняет; пустое имя возвращает номер. */
  public synchronized boolean name(int n, String name, String by) {
    Voice v = get(n);
    if (v == null) return false;
    String t = name == null ? "" : name.trim();
    if (!"user".equals(by) && "user".equals(v.nameBy)) return false;
    v.name = t; v.nameBy = t.isEmpty() ? "" : by;
    return true;
  }
  /** О чём говорит, коротко — от пересмотра разговора. */
  public synchronized boolean says(int n, String says) {
    Voice v = get(n);
    if (v == null || says == null) return false;
    v.says = says.trim(); return true;
  }

  /** Как голос называть на экране: имя или «собеседник n». */
  public static String label(Voice v) { return v == null ? "" : !v.name.isEmpty() ? v.name : "собеседник " + v.n; }
  /** То же по полю реплики, когда голос уже забыт: номер в реплике остаётся. Владелец — «владелец телефона». */
  public synchronized String label(String who) {
    if (OWNER.equals(who)) return "владелец телефона";
    Voice v = get(who);
    if (v != null) return label(v);
    return who == null || !who.trim().matches("\\d+") ? "" : "собеседник " + who.trim();
  }

  /** До 0.27 голоса «я» и «собеседник» хранились одни на все разговоры (models/speaker_profiles.json,
   *  записывались на отдельном экране настроек). Теперь голос запоминается в разговоре кнопкой FALAR,
   *  и общий файл — чужая биометрия без дела: удаляем. Сколько голосов в нём было — чтобы сказать
   *  числом, а не молча: {число или -1, если файла нет; почему не прочёлся, или ""}. Не прочёлся —
   *  всё равно удаляем. */
  public static Object[] retireOld(java.io.File modelsDir) {
    java.io.File f = new java.io.File(modelsDir, "speaker_profiles.json");
    if (!f.exists()) return new Object[]{-1, ""};
    int n = 0; String why = "";
    try { n = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")).length(); }
    catch (Exception e) { why = String.valueOf(e); }
    return new Object[]{f.delete() ? n : -1, f.exists() ? "не удалился" : why};
  }

  public static float[] unit(float[] v) {
    if (v == null) return null;
    double n = 0; for (float x : v) n += x * x; n = Math.sqrt(n);
    if (n <= 0) return v;
    float[] o = new float[v.length]; for (int i = 0; i < v.length; i++) o[i] = (float) (v[i] / n);
    return o;
  }
  public static float cos(float[] a, float[] b) {
    if (a == null || b == null || a.length != b.length) return -1;
    double s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i];
    return (float) s;
  }

  public synchronized JSONArray toJson() throws JSONException {
    JSONArray a = new JSONArray();
    for (Voice v : list) {
      JSONArray e = new JSONArray(); for (float x : v.e) e.put(Math.round(x * ROUND) / ROUND);
      JSONObject o = new JSONObject().put("n", v.n).put("lang", v.lang).put("ru", v.ru).put("pt", v.pt).put("k", v.k).put("at", v.at).put("e", e);
      if (!v.name.isEmpty()) o.put("name", v.name).put("nameBy", v.nameBy);
      if (!v.says.isEmpty()) o.put("says", v.says);
      a.put(o);
    }
    return a;
  }
  /** Из поля `voices` файла разговора. Битая запись пропускается, а не роняет весь разговор. */
  public static Voices fromJson(JSONArray a) {
    Voices vs = new Voices();
    if (a == null) return vs;
    int dim = -1;
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i); JSONArray e = o == null ? null : o.optJSONArray("e");
      if (e == null || e.length() == 0 || o.optInt("n", 0) <= 0) continue;
      if (dim < 0) dim = e.length(); else if (e.length() != dim) continue;
      float[] f = new float[e.length()];
      for (int k = 0; k < f.length; k++) f[k] = (float) e.optDouble(k, 0);
      Voice v = new Voice(o.optInt("n"), o.optString("lang", "pt"), unit(f), o.optLong("at", 0));
      v.ru = o.optInt("ru", 0); v.pt = o.optInt("pt", 0); v.k = Math.max(1, Math.min(K, o.optInt("k", 1)));
      v.lang = o.optString("lang", v.lang);
      v.name = o.optString("name", ""); v.nameBy = v.name.isEmpty() ? "" : o.optString("nameBy", "auto");
      v.says = o.optString("says", "");
      if (vs.get(v.n) == null) vs.list.add(v);
    }
    return vs;
  }
}
