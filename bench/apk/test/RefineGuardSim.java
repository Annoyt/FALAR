package dev.agenttranslator;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Правило Brief.swallowed на ответах Hy-MT2 с открытого набора (tools/refine_guard_eval.py): сколько ответов
 *  с фоном разговора раздуто и сколько из них правило отбрасывает; сколько отбрасывает зря. Мерило «раздуто» —
 *  перевод той же фразы из набора, сделанный человеком: ответ длиннее его больше чем в полтора раза и на 10
 *  знаков — фон дописал лишнее (или модель многословна: примеры печатаются, чтобы видеть, что именно). Ответы
 *  с пустым фоном, если есть, — для сравнения: что правило отбросило там, отброшено зря, вбирать было нечего.
 *
 *  Запуск: bash bench/apk/refine_guard_sim.sh [gen.jsonl] [примеров] [признаки.tsv] */
public class RefineGuardSim {
  static int ref(JSONObject x) { return Math.max(x.getString("src").length(), x.getString("draft").length()); }
  static List<String> others(JSONObject x) {
    List<String> r = new ArrayList<>(); JSONArray a = x.getJSONArray("others");
    for (int k = 0; k < a.length(); k++) { JSONArray p = a.getJSONArray(k); for (int j = 0; j < p.length(); j++) r.add(p.getString(j)); }
    return r;
  }
  static String why(JSONObject x) { return Brief.swallowed(x.getString("out"), x.getString("src"), x.getString("draft"), others(x)); }
  static boolean swollen(JSONObject x) { return x.getString("out").length() > 1.5 * x.getString("ref").length() + 10; }
  static String pct(int a, int b) { return b == 0 ? "—" : String.format(Locale.ROOT, "%d из %d (%.1f %%)", a, b, 100.0 * a / b); }
  static String q(double[] v, double p) { return String.format(Locale.ROOT, "%.2f", v[Math.min(v.length - 1, (int) Math.floor(p * v.length))]); }
  static String cut(String s, int n) { s = s.replace('\n', ' '); return s.length() > n ? s.substring(0, n) + "…" : s; }

  public static void main(String[] a) throws Exception {
    Path f = a.length > 0 ? Paths.get(a[0]) : Paths.get(System.getenv().getOrDefault("FALAR_STAND", ".stand"), "refine-guard", "gen.jsonl");
    int show = a.length > 1 ? Integer.parseInt(a[1]) : 15;
    Map<String, JSONObject> ctx = new LinkedHashMap<>(), empty = new LinkedHashMap<>();
    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
      if (line.trim().isEmpty()) continue;
      JSONObject x; try { x = new JSONObject(line); } catch (Exception e) { continue; }   // недописанная строка после сбоя
      String key = x.getString("set") + "#" + x.getInt("i");
      String cond = x.getString("cond");
      if (cond.equals("ctx")) ctx.put(key, x); else if (cond.equals("empty")) empty.put(key, x);   // «frag» — переводы кусков фразы, не ответы
    }
    System.out.println("ответов: с фоном " + ctx.size() + ", с пустым фоном " + empty.size()
        + " · правило: длиннее реплики в " + Brief.SWELL + " раза + " + Brief.SWELL_SLACK + " зн.; кусок другой реплики от "
        + Brief.LEAK_CHARS + " зн. при длине больше " + Brief.LEAK_SWELL + " раза + " + Brief.LEAK_SLACK + " зн.");
    for (String dir : new String[]{"pt2ru", "ru2pt", ""}) {
      for (Map.Entry<String, Map<String, JSONObject>> c : Arrays.asList(new AbstractMap.SimpleEntry<>("с фоном", ctx), new AbstractMap.SimpleEntry<>("без фона", empty))) {
        List<Double> r = new ArrayList<>(); int n = 0, x2 = 0, x3 = 0, rej = 0, rejLen = 0, lang = 0;
        for (JSONObject x : c.getValue().values()) {
          if (!dir.isEmpty() && !x.getString("dir").equals(dir)) continue;
          n++; double k = x.getString("out").length() / (double) Math.max(1, ref(x)); r.add(k);
          if (k > 2) x2++; if (k > 3) x3++;
          String w = why(x); if (w != null) { rej++; if (w.startsWith("в ")) rejLen++; }
          // не тот язык ответа — правило его не ловит; считается, чтобы видеть, нужна ли такая проверка
          if (x.getString("out").matches("(?s).*\\p{IsCyrillic}.*") != x.getString("dir").endsWith("ru")) lang++;
        }
        if (n == 0) continue;
        double[] v = r.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        System.out.println((dir.isEmpty() ? "всё" : dir) + " · " + c.getKey() + ": " + n + " · длина ответа к реплике p50 " + q(v, .5) + ", p99 " + q(v, .99)
            + ", p99.9 " + q(v, .999) + ", max " + q(v, 1) + " · больше 2× " + pct(x2, n) + ", больше 3× " + pct(x3, n)
            + " · отброшено " + pct(rej, n) + " (по длине " + rejLen + ", по куску " + (rej - rejLen) + ") · не на том языке " + lang);
      }
    }
    // раздутые против отброшенного
    int sw = 0, swRej = 0, okRej = 0, ok = 0; List<String[]> missed = new ArrayList<>(), extra = new ArrayList<>(), caught = new ArrayList<>();
    for (Map.Entry<String, JSONObject> e : ctx.entrySet()) {
      JSONObject x = e.getValue(), y = empty.get(e.getKey());
      String w = why(x); boolean s = swollen(x);
      String[] row = {e.getKey() + " " + x.getString("dir"), x.getString("src"), x.getString("draft"), x.getString("ref"), y == null ? "" : y.getString("out"), x.getString("out"), w == null ? "" : w};
      if (s) { sw++; if (w != null) { swRej++; caught.add(row); } else missed.add(row); }
      else { ok++; if (w != null) { okRej++; extra.add(row); } }
    }
    System.out.println("с фоном раздуто против перевода из набора: " + pct(sw, sw + ok) + " · из раздутых отброшено " + pct(swRej, sw)
        + " · из нераздутых отброшено " + pct(okRej, ok));
    // признаки каждого ответа — для подбора порогов вне правила (третий аргумент: куда писать TSV)
    if (a.length > 2) try (java.io.PrintWriter w = new java.io.PrintWriter(Files.newBufferedWriter(Paths.get(a[2]), StandardCharsets.UTF_8))) {
      w.println("key\tcond\tdir\tsrc\tdraft\tref\tout\tempty_out\tcopied");
      for (Map<String, JSONObject> m : Arrays.asList(ctx, empty))
        for (Map.Entry<String, JSONObject> e : m.entrySet()) {
          JSONObject x = e.getValue(), y = empty.get(e.getKey());
          String k = Brief.copied(x.getString("out"), Arrays.asList(x.getString("src"), x.getString("draft")), others(x));
          w.println(e.getKey() + "\t" + x.getString("cond") + "\t" + x.getString("dir") + "\t" + x.getString("src").length() + "\t" + x.getString("draft").length()
              + "\t" + x.getString("ref").length() + "\t" + x.getString("out").length() + "\t" + (y == null ? -1 : y.getString("out").length()) + "\t" + k.length());
        }
    }
    for (Object[] g : new Object[][]{{"отброшено из раздутых", caught}, {"раздуто, но пропущено", missed}, {"отброшено, хотя не раздуто", extra}}) {
      @SuppressWarnings("unchecked") List<String[]> rows = (List<String[]>) g[1];
      System.out.println("\n== " + g[0] + ": " + rows.size());
      for (int k = 0; k < Math.min(show, rows.size()); k++) {
        String[] r = rows.get(k);
        System.out.println("  " + r[0] + (r[6].isEmpty() ? "" : " · " + r[6]) + "\n    исходник: " + cut(r[1], 160) + "\n    черновик: " + cut(r[2], 160)
            + "\n    в наборе: " + cut(r[3], 160) + (r[4].isEmpty() ? "" : "\n    без фона: " + cut(r[4], 160)) + "\n    с фоном:  " + cut(r[5], 400));
      }
    }
  }
}
