package dev.agenttranslator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Перевод проверочных наборов на столе тем же кодом, что на телефоне: Engine.translate, модели
 *  models/mt — те, что едут на телефон. Оценки и сравнение с эталонным прогоном — tools/mt_metrics.py,
 *  всё вместе — bench/quality/gate.sh.
 *
 *    java … dev.agenttranslator.MtRun <каталог models> <pt2ru|ru2pt> <выход.json> <набор.json>=<имя> …
 *
 *  Наборы — как data/test_set.json и data/mt_test/tatoeba.json (ключи pt_to_ru / ru_to_pt). Выход — как
 *  у mt_bench.py: {"summary": {...}, "results": [{set, src, ref, hyp, sec[, id, added]}]}. */
public class MtRun {
  public static void main(String[] a) throws Exception {
    if (a.length < 4) { System.err.println("MtRun <models> <pt2ru|ru2pt> <out.json> <set.json>=<name> …"); System.exit(2); }
    String dir = a[1], src = dir.substring(0, 2), tgt = dir.substring(3), key = src + "_to_" + tgt;
    Engine e = Engine.mtOnly(new File(a[0]), s -> System.err.println(s));
    JSONArray res = new JSONArray(); List<Double> secs = new ArrayList<>();
    for (int k = 3; k < a.length; k++) {
      int eq = a[k].lastIndexOf('='); String path = a[k].substring(0, eq), name = a[k].substring(eq + 1);
      JSONArray items = new JSONObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).getJSONArray(key);
      for (int i = 0; i < items.length(); i++) {
        JSONObject it = items.getJSONObject(i);
        long t0 = System.nanoTime(); String hyp = e.translate(dir, it.getString(src)); double sec = (System.nanoTime() - t0) / 1e9;
        JSONObject r = new JSONObject().put("set", name).put("src", it.getString(src)).put("ref", it.getString(tgt))
            .put("hyp", hyp).put("sec", Math.round(sec * 100) / 100.0);
        if (it.has("pid")) r.put("id", it.get("pid") + "-" + it.get("rid")).put("added", it.getString("added"));
        res.put(r); secs.add(sec);
        if (res.length() % 200 == 0) System.err.println("  " + dir + ": " + res.length());
      }
    }
    Collections.sort(secs); int n = secs.size(); double sum = 0; for (double s : secs) sum += s;
    JSONObject summary = new JSONObject().put("engine", "app-java").put("direction", dir).put("n", n)
        .put("lat_avg", Math.round(sum / n * 100) / 100.0).put("lat_p50", Math.round(secs.get(n / 2) * 100) / 100.0)
        .put("lat_p95", Math.round(secs.get((int) (n * 0.95)) * 100) / 100.0);
    Files.write(Paths.get(a[2]), new JSONObject().put("summary", summary).put("results", res).toString(1)
        .getBytes(StandardCharsets.UTF_8));
    System.err.println("MtRun " + dir + ": " + n + " фраз → " + a[2]);
  }
}
