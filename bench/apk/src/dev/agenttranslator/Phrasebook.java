package dev.agenttranslator;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Словарь фраз (§6 ярус 0 / §8): seed + пины пользователя + самообучение. Быстрый путь до MT + кэш звука. */
public class Phrasebook {
  public static class Hit { public final String src, dst, kind; Hit(String s, String d, String k) { src = s; dst = d; kind = k; } }
  static final int PROMOTE_HITS = 3, MAX_WORDS = 12; static final double FUZZY = 0.12;
  /** Ярус из корпуса (Tatoeba): большой и не вычитанный вручную — только точное совпадение. */
  static final Map<String, String> CONTR = new HashMap<>();
  static { String[][] c = {{"vc","você"},{"tá","está"},{"ta","está"},{"pra","para"},{"pro","para o"},{"né","não é"},{"tô","estou"},{"to","estou"},{"cadê","onde está"},{"q","que"},{"tb","também"}}; for (String[] p : c) CONTR.put(p[0], p[1]); }
  final File dir; public final Map<String, Map<String, JSONObject>> seed = new HashMap<>(), user = new HashMap<>(), learned = new HashMap<>();
  final Map<String, Map<String, String>> mined = new HashMap<>();
  final Map<String, float[]> audio = new ConcurrentHashMap<>(); final ExecutorService io = Executors.newSingleThreadExecutor();
  public int seedCount = 0, userCount = 0, learnedCount = 0, fastCount = 0;
  public volatile int minedCount = 0; public volatile long minedLoadMs = -1;
  public int pinsWithDigits = 0;

  public Phrasebook(File modelsDir) {
    dir = modelsDir; new File(dir, "cache").mkdirs();
    load(new File(dir, "phrasebook.json"), seed); load(new File(dir, "phrasebook_user.json"), user); load(new File(dir, "learned.json"), learned);
    seedCount = count(seed); userCount = count(user); learnedCount = count(learned);
    // Пины, закреплённые до 0.20, хранят читаемый текст с цифрами, а поиск идёт по маскированному
    // тексту с плейсхолдером — такие пины не срабатывали никогда. Выбрасывать их молча нельзя
    // (правило версионирования), поэтому считаем и сообщаем: перезакрепить их — дело человека.
    for (Map<String, JSONObject> m : user.values()) for (String k : m.keySet()) if (k.replaceAll("xq\\d+", "").matches(".*\\d.*")) pinsWithDigits++;
    for (Map<String, JSONObject> m : learned.values()) for (JSONObject o : m.values()) if (o.optBoolean("fast")) fastCount++;
    io.submit(() -> loadMined(new File(dir, "phrasebook_tatoeba.tsv")));
  }
  /** Корпусный ярус: TSV «направление \t ключ \t исходник \t перевод \t ярус», грузится в фоне. */
  void loadMined(File f) {
    if (!f.exists()) return; long t0 = System.currentTimeMillis();
    Map<String, Map<String, String>> m = new HashMap<>(); int n = 0;
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 16)) {
      String line;
      while ((line = r.readLine()) != null) {
        int a = line.indexOf('\t'); if (a < 0) continue;
        int b = line.indexOf('\t', a + 1); if (b < 0) continue;
        int c = line.indexOf('\t', b + 1); if (c < 0) continue;
        int d = line.indexOf('\t', c + 1); if (d < 0) d = line.length();
        m.computeIfAbsent(line.substring(0, a), k -> new HashMap<>(1 << 15))
         .put(line.substring(a + 1, b), line.substring(c + 1, d));   // исходник не храним: наружу отдаём распознанный текст
        n++;
      }
    } catch (Exception e) { e.printStackTrace(); return; }
    synchronized (this) { mined.putAll(m); minedCount = n; }
    minedLoadMs = System.currentTimeMillis() - t0;
    System.out.println("корпус: " + n + " фраз за " + minedLoadMs + " мс");
  }
  static Hit minedHit(String v, String fallback, String kind) { return new Hit(fallback, v, kind); }
  static int count(Map<String, Map<String, JSONObject>> m) { int n = 0; for (Map<String, JSONObject> x : m.values()) n += x.size(); return n; }
  public static int stale = 0;
  static void load(File f, Map<String, Map<String, JSONObject>> into) {
    try { if (!f.exists()) return; JSONObject j = new JSONObject(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
      for (Iterator<String> it = j.keys(); it.hasNext();) { String d = it.next(); JSONObject m = j.getJSONObject(d); Map<String, JSONObject> mm = into.computeIfAbsent(d, k -> new LinkedHashMap<>());
        for (Iterator<String> k = m.keys(); k.hasNext();) { String key = k.next(); JSONObject o = m.getJSONObject(key);
          // Записи со старым плейсхолдером выбрасываем: после смены формата unmask их не восстановит,
          // и в перевод попадёт «на N1 … Руа Аугуста» вместо подстановки.
          if (TextRules.hasPlaceholder(key) || TextRules.hasPlaceholder(o.optString("dst"))) { stale++; continue; }
          mm.put(key, o); } }
    } catch (Exception e) { e.printStackTrace(); }
  }
  /** Очистить выученное. Нужно, потому что ярус накапливается молча и в него попадает всё —
   *  в том числе прогон замеров, который к живому разговору отношения не имеет, а частоты
   *  и быстрый путь портит. Затравку и пины не трогаем: они заданы человеком. */
  public synchronized int clearLearned() {
    int was = learnedCount;
    learned.clear(); learnedCount = 0; fastCount = 0;
    new File(dir, "learned.json").delete();
    File cache = new File(dir, "cache");
    File[] fs = cache.listFiles((d, n) -> n.endsWith(".wav"));
    if (fs != null) for (File f : fs) f.delete();      // кэш звука выученных фраз тоже осиротел
    return was;
  }

  void save(File f, Map<String, Map<String, JSONObject>> m) {
    io.submit(() -> { try { JSONObject j = new JSONObject(); for (Map.Entry<String, Map<String, JSONObject>> e : m.entrySet()) { JSONObject mm = new JSONObject(); for (Map.Entry<String, JSONObject> x : e.getValue().entrySet()) mm.put(x.getKey(), x.getValue()); j.put(e.getKey(), mm); }
      File tmp = new File(f.getPath() + ".tmp"); Files.write(tmp.toPath(), j.toString(1).getBytes("UTF-8")); Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (Exception e) { e.printStackTrace(); } });
  }

  public static String norm(String s) {
    s = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace('ё', 'е');
    s = s.replaceAll("[^\\p{L}\\p{N}\\s'-]", " ").replaceAll("\\s+", " ").trim();
    StringBuilder b = new StringBuilder(); for (String w : s.split(" ")) { if (w.isEmpty()) continue; if (b.length() > 0) b.append(' '); b.append(CONTR.getOrDefault(w, w)); }
    return b.toString();
  }
  static int lev(String a, String b) { int[] d = new int[b.length() + 1]; for (int j = 0; j <= b.length(); j++) d[j] = j;
    for (int i = 1; i <= a.length(); i++) { int prev = d[0]; d[0] = i; for (int j = 1; j <= b.length(); j++) { int t = d[j]; d[j] = Math.min(Math.min(d[j] + 1, d[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1)); prev = t; } } return d[b.length()]; }

  /** Точное совпадение по нормализованному ключу, затем нечёткое (≤12% правок) — в user, seed, и быстрых learned. */
  public synchronized Hit lookup(String direction, String text) {
    String key = norm(text); if (key.isEmpty()) return null;
    for (Object[] src : new Object[][]{{user, "📖 пин"}, {seed, "📖 словарь"}, {learned, "📖 выучено"}}) {
      Map<String, JSONObject> m = ((Map<String, Map<String, JSONObject>>) src[0]).get(direction); if (m == null) continue;
      JSONObject o = m.get(key); if (o != null && (src[0] != learned || o.optBoolean("fast"))) return new Hit(o.optString("src", text), o.optString("dst"), (String) src[1]);
    }
    Map<String, String> mm = mined.get(direction);
    if (mm != null) { String v = mm.get(key); if (v != null) return minedHit(v, text, "\uD83D\uDCD6 корпус"); }
    if (key.length() < 8) return null;
    Hit best = null; double bestD = FUZZY;
    for (Object[] src : new Object[][]{{user, "📖 пин≈"}, {seed, "📖 словарь≈"}, {learned, "📖 выучено≈"}}) {
      Map<String, JSONObject> m = ((Map<String, Map<String, JSONObject>>) src[0]).get(direction); if (m == null) continue;
      for (Map.Entry<String, JSONObject> e : m.entrySet()) {
        if (src[0] == learned && !e.getValue().optBoolean("fast")) continue;
        if (Math.abs(e.getKey().length() - key.length()) > 3) continue;
        double d = lev(key, e.getKey()) / (double) Math.max(key.length(), e.getKey().length());
        if (d < bestD) { bestD = d; best = new Hit(e.getValue().optString("src", text), e.getValue().optString("dst"), (String) src[1]); }
      }
    }
    // Нечёткий поиск по корпусу отключён намеренно: на замере (tools/tatoeba_eval.py) одна правка
    // в пределах 8% переворачивает лицо глагола ("não tens" -> "não tenho") и отрицание, а выигрыш —
    // лишь ~300 мс на редком совпадении. Промах уходит в MT, который такие фразы переводит верно.
    return best;
  }
  /** Учёт перевода из MT; возвращает число повторов; на PROMOTE_HITS фраза становится быстрой. */
  public synchronized int record(String direction, String src, String dst) {
    String key = norm(src); if (key.isEmpty() || key.split(" ").length > MAX_WORDS) return 0;
    Map<String, JSONObject> m = learned.computeIfAbsent(direction, k -> new LinkedHashMap<>());
    JSONObject o = m.get(key); try { if (o == null) { o = new JSONObject(); o.put("src", src); o.put("dst", dst); o.put("hits", 0); m.put(key, o); learnedCount++; }
      int h = o.getInt("hits") + 1; o.put("hits", h); if (h >= PROMOTE_HITS && !o.optBoolean("fast")) { o.put("fast", true); fastCount++; }
      save(new File(dir, "learned.json"), learned); return h; } catch (JSONException e) { return 0; }
  }
  /** Пин — только ручной. Ключ и перевод приходят в **маскированном** виде (числа, адреса и имена
   *  плейсхолдерами), потому что и поиск идёт по маскированному тексту: пин «Quanto custa 5 reais»
   *  с читаемой пятёркой не находился никогда, а с плейсхолдером срабатывает и на «7 reais». */
  public synchronized void pin(String direction, String src, String dst) {
    Map<String, JSONObject> m = user.computeIfAbsent(direction, k -> new LinkedHashMap<>());
    try { JSONObject o = new JSONObject(); o.put("src", src); o.put("dst", dst); if (m.put(norm(src), o) == null) userCount++; save(new File(dir, "phrasebook_user.json"), user); } catch (JSONException e) {}
  }
  /** Правка перевода от облака или уточнителя — только в ярус выученного, пины не трогаем: §8 плана
   *  требует, чтобы автоматика их никогда не перезаписывала. Запись должна уже существовать — её
   *  создаёт record при переводе через MT; фраза из затравки, пинов или корпуса правке не подлежит.
   *  Счётчик повторов не меняется: быстрой фраза станет после трёх повторов, как и раньше, а
   *  одноразовая контекстная правка («Ключ лежит на столе») повторов не наберёт и никому не навредит.
   *  Возвращает 1 — легла, 0 — записи нет, -1 — перевод и так такой. */
  public synchronized int fix(String direction, String maskedSrc, String fixed, String by) {
    if (fixed == null || fixed.trim().isEmpty()) return 0;
    String key = norm(maskedSrc); if (key.isEmpty()) return 0;
    Map<String, JSONObject> m = learned.get(direction); if (m == null) return 0;
    JSONObject o = m.get(key); if (o == null) return 0;
    try {
      if (norm(fixed).equals(norm(o.optString("dst")))) return -1;
      o.put("dst", fixed.trim()); o.put("by", by == null ? "" : by);
    } catch (JSONException e) { return 0; }
    save(new File(dir, "learned.json"), learned);
    return 1;
  }
  // ---- кэш синтезированного звука (память + wav на диске)
  String akey(String direction, String dst) { return direction + "|" + norm(dst); }
  public float[] audio(String direction, String dst) {
    String k = akey(direction, dst); float[] a = audio.get(k); if (a != null) return a;
    File f = new File(dir, "cache/" + Integer.toHexString(k.hashCode()) + ".wav"); if (!f.exists()) return null;
    try { byte[] b = Files.readAllBytes(f.toPath()); ByteBuffer bb = ByteBuffer.wrap(b, 44, b.length - 44).order(ByteOrder.LITTLE_ENDIAN); float[] s = new float[(b.length - 44) / 2]; for (int i = 0; i < s.length; i++) s[i] = bb.getShort() / 32768f; audio.put(k, s); return s; } catch (Exception e) { return null; }
  }
  public void putAudio(String direction, String dst, float[] s, int rate) {
    String k = akey(direction, dst); audio.put(k, s);
    io.submit(() -> { try { File f = new File(dir, "cache/" + Integer.toHexString(k.hashCode()) + ".wav"); ByteBuffer bb = ByteBuffer.allocate(44 + s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
      bb.put("RIFF".getBytes()).putInt(36 + s.length * 2).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(s.length * 2);
      for (float v : s) bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v * 32767)))); Files.write(f.toPath(), bb.array()); } catch (Exception e) { e.printStackTrace(); } });
  }
  public String stats() { return (stale > 0 ? "устаревших выброшено " + stale + " · " : "") + "словарь " + seedCount + (minedCount > 0 ? " + корпус " + minedCount : "") + " · пины " + userCount + " · выучено " + learnedCount + " (быстрых " + fastCount + ")"; }
}
