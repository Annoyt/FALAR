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
 *    java [-Dfalar.beam=4] [-Dfalar.seq=true] [-Dfalar.threads=1] [-Dfalar.lp=1.0] … dev.agenttranslator.MtRun
 *         <каталог models> <pt2ru|ru2pt> <выход.json> <набор.json>=<имя> …
 *
 *  falar.beam — ширина луча (Engine.mtBeam, по умолчанию 1 — как в приложении), falar.seq — черновики по
 *  одному вызову (Engine.mtBeamSeq), falar.threads — потоков
 *  ONNX Runtime (по умолчанию 4, как на телефоне; «как одно ядро» — 1 вместе с taskset -c N).
 *
 *  Наборы — как data/test_set.json и data/mt_test/tatoeba.json (ключи pt_to_ru / ru_to_pt). Выход — как
 *  у mt_bench.py: {"summary": {...}, "results": [{set, src, ref, hyp, sec, ms, steps, run_ms[, id, added]}]};
 *  steps — вызовы decoder_with_past, run_ms — время в них; sel_ms и gather_ms в summary — графы луча на
 *  фразу. Время в summary — без первых WARM фраз (разогрев JIT и памяти). */
public class MtRun {
  static final int WARM = 20;

  public static void main(String[] a) throws Exception {
    if (a.length < 4) { System.err.println("MtRun <models> <pt2ru|ru2pt> <out.json> <set.json>=<name> …"); System.exit(2); }
    String dir = a[1], src = dir.substring(0, 2), tgt = dir.substring(3), key = src + "_to_" + tgt;
    Engine.mtBeam = Integer.getInteger("falar.beam", 1);
    Engine.mtThreads = Integer.getInteger("falar.threads", 4);
    Engine.mtLp = Double.parseDouble(System.getProperty("falar.lp", "1.0"));
    Engine.mtBeamSeq = Boolean.getBoolean("falar.seq");
    Engine e = Engine.mtOnly(new File(a[0]), s -> System.err.println(s));
    JSONArray res = new JSONArray(); List<Double> ms = new ArrayList<>(); long steps = 0, runNs = 0, selNs = 0, gatNs = 0;
    for (int k = 3; k < a.length; k++) {
      int eq = a[k].lastIndexOf('='); String path = a[k].substring(0, eq), name = a[k].substring(eq + 1);
      JSONArray items = new JSONObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).getJSONArray(key);
      for (int i = 0; i < items.length(); i++) {
        JSONObject it = items.getJSONObject(i);
        long s0 = Engine.mtSteps, r0 = Engine.mtRunNs, q0 = Engine.mtSelNs, g0 = Engine.mtGatNs, t0 = System.nanoTime();
        String hyp = e.translate(dir, it.getString(src));
        double sec = (System.nanoTime() - t0) / 1e9; long st = Engine.mtSteps - s0, rn = Engine.mtRunNs - r0;
        JSONObject r = new JSONObject().put("set", name).put("src", it.getString(src)).put("ref", it.getString(tgt))
            .put("hyp", hyp).put("sec", Math.round(sec * 100) / 100.0).put("ms", Math.round(sec * 1e4) / 10.0)
            .put("steps", st).put("run_ms", Math.round(rn / 1e5) / 10.0);
        if (it.has("pid")) r.put("id", it.get("pid") + "-" + it.get("rid")).put("added", it.getString("added"));
        res.put(r);
        if (res.length() > WARM) { ms.add(sec * 1000); steps += st; runNs += rn; selNs += Engine.mtSelNs - q0; gatNs += Engine.mtGatNs - g0; }
        if (res.length() % 200 == 0) System.err.println("  " + dir + ": " + res.length());
      }
    }
    int n = ms.size(); double sum = 0; for (double x : ms) sum += x;
    List<Double> sorted = new ArrayList<>(ms); Collections.sort(sorted);
    JSONObject summary = new JSONObject().put("engine", "app-java" + (Engine.mtBeam > 1 ? "-beam" + Engine.mtBeam + (Engine.mtBeamSeq ? "-seq" : "") : ""))
        .put("direction", dir).put("n", res.length()).put("beam", Engine.mtBeam).put("threads", Engine.mtThreads).put("lp", Engine.mtLp).put("seq", Engine.mtBeamSeq)
        .put("timed", n).put("ms_avg", Math.round(sum / n * 10) / 10.0).put("ms_p50", Math.round(sorted.get(n / 2) * 10) / 10.0)
        .put("ms_p95", Math.round(sorted.get((int) (n * 0.95)) * 10) / 10.0)
        .put("steps_avg", Math.round(steps * 10.0 / n) / 10.0).put("run_share", Math.round(runNs / 1e4 / sum) / 100.0)
        .put("sel_ms", Math.round(selNs / 1e5 / n) / 10.0).put("gather_ms", Math.round(gatNs / 1e5 / n) / 10.0)
        .put("lat_avg", Math.round(sum / n / 10) / 100.0).put("lat_p50", Math.round(sorted.get(n / 2) / 10) / 100.0)
        .put("lat_p95", Math.round(sorted.get((int) (n * 0.95)) / 10) / 100.0);
    Files.write(Paths.get(a[2]), new JSONObject().put("summary", summary).put("results", res).toString(1)
        .getBytes(StandardCharsets.UTF_8));
    System.err.println("MtRun " + dir + ": " + res.length() + " фраз → " + a[2] + " · " + summary);
  }
}
