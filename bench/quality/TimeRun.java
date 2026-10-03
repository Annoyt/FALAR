package dev.agenttranslator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Время перевода по вариантам вперемешку: каждая фраза переводится всеми вариантами подряд, порядок
 *  вариантов сдвигается от фразы к фразе. Так нагрузка ПК от других задач делится между вариантами
 *  поровну, и отношение «вариант / жадный» честнее, чем у отдельных прогонов. Переводы пишутся тоже —
 *  для сверки с прогонами качества (одни и те же фразы должны переводиться одинаково).
 *
 *    taskset -c N java -XX:+UseSerialGC -Dfalar.threads=1 … dev.agenttranslator.TimeRun
 *         <каталог models> <pt2ru|ru2pt> <выход.json> <варианты> <фраз из набора> <набор.json>=<имя> …
 *
 *  Варианты — через запятую: ширина луча, «s» — черновики по одному (Engine.mtBeamSeq): «1,2,4,2s,4s».
 *  Фразы — случайная выборка (зерно 7) не больше заданного числа из каждого набора; первые WARM фраз —
 *  разогрев, в счёт не идут. */
public class TimeRun {
  static final int WARM = 20;

  public static void main(String[] a) throws Exception {
    String dir = a[1], src = dir.substring(0, 2), tgt = dir.substring(3), key = src + "_to_" + tgt;
    String[] vs = a[3].split(","); int limit = Integer.parseInt(a[4]);
    Engine.mtThreads = Integer.getInteger("falar.threads", 4);
    Engine e = Engine.mtOnly(new File(a[0]), s -> System.err.println(s));
    List<String[]> items = new ArrayList<>();   // {набор, исходник, эталон}
    for (int k = 5; k < a.length; k++) {
      int eq = a[k].lastIndexOf('='); String path = a[k].substring(0, eq), name = a[k].substring(eq + 1);
      JSONArray arr = new JSONObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).getJSONArray(key);
      List<Integer> idx = new ArrayList<>(); for (int i = 0; i < arr.length(); i++) idx.add(i);
      Collections.shuffle(idx, new Random(7));
      List<Integer> pick = new ArrayList<>(idx.subList(0, Math.min(limit, idx.size()))); Collections.sort(pick);
      for (int i : pick) items.add(new String[]{name, arr.getJSONObject(i).getString(src), arr.getJSONObject(i).getString(tgt)});
    }
    int V = vs.length; double[][] ms = new double[V][items.size()]; long[] steps = new long[V];
    JSONArray rows = new JSONArray();
    for (int i = -WARM; i < items.size(); i++) {
      String[] it = items.get(Math.floorMod(i, items.size()));
      JSONObject row = new JSONObject().put("set", it[0]).put("src", it[1]).put("ref", it[2]);
      for (int j = 0; j < V; j++) {
        int v = Math.floorMod(i + j, V);
        Engine.mtBeam = Integer.parseInt(vs[v].replace("s", "")); Engine.mtBeamSeq = vs[v].endsWith("s");
        long s0 = Engine.mtSteps, t0 = System.nanoTime();
        String hyp = e.translate(dir, it[1]);
        double t = (System.nanoTime() - t0) / 1e6;
        if (i >= 0) { ms[v][i] = t; steps[v] += Engine.mtSteps - s0; row.put("hyp_" + vs[v], hyp).put("ms_" + vs[v], Math.round(t * 10) / 10.0); }
      }
      if (i >= 0) rows.put(row);
      if (i > 0 && i % 50 == 0) System.err.println("  " + dir + ": " + i + " из " + items.size());
    }
    int n = items.size(); JSONObject sum = new JSONObject();
    for (int v = 0; v < V; v++) {
      double[] s = ms[v].clone(); Arrays.sort(s); double tot = 0; for (double x : s) tot += x;
      double[] rat = new double[n]; for (int i = 0; i < n; i++) rat[i] = ms[v][i] / ms[0][i]; Arrays.sort(rat);
      double tot0 = 0; for (double x : ms[0]) tot0 += x;
      sum.put(vs[v], new JSONObject().put("ms_avg", r1(tot / n)).put("ms_p50", r1(s[n / 2])).put("ms_p95", r1(s[(int) (n * 0.95)]))
          .put("steps_avg", r1((double) steps[v] / n)).put("ratio_avg", Math.round(tot / tot0 * 100) / 100.0)
          .put("ratio_p50", Math.round(rat[n / 2] * 100) / 100.0));
    }
    JSONObject out = new JSONObject().put("summary", new JSONObject().put("direction", dir).put("n", n).put("threads", Engine.mtThreads)
        .put("variants", sum).put("base", vs[0])).put("results", rows);
    Files.write(Paths.get(a[2]), out.toString(1).getBytes(StandardCharsets.UTF_8));
    System.err.println("TimeRun " + dir + ": " + n + " фраз → " + a[2] + "\n" + sum.toString(1));
  }

  static double r1(double x) { return Math.round(x * 10) / 10.0; }
}
