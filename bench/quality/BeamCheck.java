package dev.agenttranslator;

import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Сверка перебора вариантов (Engine.beamKv) на моделях приложения, на столе:
 *
 *   1. ширина 1 — те же куски, что у жадного пути (greedyKv), на каждой фразе, в обоих способах;
 *   2. черновики по одному (Engine.mtBeamSeq): оценка выбранного варианта совпадает с пересчётом по его
 *      кускам (scoreKv, double) до 2·10⁻³: логвероятности луча — из графа отбора во float, и сумма exp по
 *      61 тыс. кусков во float даёт до 6·10⁻⁴ на фразу. Если прошлое декодера переставлено не тем
 *      черновикам, оценка поедет на десятые. Перестановка у способов общая;
 *   3. для справки: черновики пакетом — насколько оценка в пакете расходится с пересчётом (int8-квантование
 *      активаций одно на пакет), и как часто луч нашёл вариант не хуже жадного по своей же оценке.
 *
 *    java … dev.agenttranslator.BeamCheck <каталог models> <pt2ru|ru2pt> <набор.json> <сколько фраз> <ширина> …
 *
 *  Код выхода 1 — расхождение в 1 или 2. */
public class BeamCheck {
  public static void main(String[] a) throws Exception {
    String dir = a[1], src = dir.substring(0, 2), key = src + "_to_" + dir.substring(3);
    int limit = Integer.parseInt(a[3]);
    Engine.mtThreads = Integer.getInteger("falar.threads", 4);
    Engine e = Engine.mtOnly(new File(a[0]), s -> {});
    SpmTokenizer tk = e.tok.get(dir); OrtSession[] s = e.mt.get(dir);
    long start = tk.vocab.get("<pad>"), eos = tk.vocab.get("</s>"); String lt = dir.equals("pt2ru") ? ">>rus<<" : null;
    JSONArray items = new JSONObject(new String(Files.readAllBytes(Paths.get(a[2])), StandardCharsets.UTF_8)).getJSONArray(key);
    int n = Math.min(limit, items.length()), bad = 0;
    for (int k = 4; k < a.length; k++) for (boolean seq : new boolean[]{true, false}) {
      Engine.mtBeamSeq = seq;
      int beam = Integer.parseInt(a[k]), same = 0, tfOk = 0, notWorse = 0, sents = 0; double maxDiff = 0, tol = seq ? 2e-3 : 1e-3;
      for (int i = 0; i < n; i++) {
        for (String sent : items.getJSONObject(i).getString(src).split("(?<=[.!?…])\\s+(?=\\S)")) {
          if (sent.trim().isEmpty()) continue;
          sents++;
          List<Long> ids = tk.encode(sent, lt);
          List<Long> g = e.greedyKv(s[0], s[1], ids, start, eos, 64);
          List<Long> b = e.beamKv(s[0], s[1], ids, start, eos, 64, beam);
          double sum = e.lastBeamSum, norm = e.lastBeamNorm; boolean fin = e.lastBeamEos;
          if (b.equals(g)) same++;
          double tf = e.scoreKv(s[0], s[1], ids, b, start, eos, fin), d = Math.abs(tf - sum);
          maxDiff = Math.max(maxDiff, d);
          if (d < tol) tfOk++; else if (seq) System.out.println("  РАСХОЖДЕНИЕ оценки, ширина " + beam + ": " + sent + " · луч " + sum + " ≠ пересчёт " + tf);
          boolean gFin = g.size() < 64;
          double gNorm = e.scoreKv(s[0], s[1], ids, g, start, eos, gFin) / Math.pow(Math.max(1, g.size() + (gFin ? 1 : 0)), Engine.mtLp);
          if (norm >= gNorm - 1e-6) notWorse++;
          if (beam == 1 && !b.equals(g)) System.out.println("  РАСХОЖДЕНИЕ с жадным: " + sent + " · жадный " + tk.decode(g) + " · луч " + tk.decode(b));
        }
      }
      boolean ok = (!seq || tfOk == sents) && (beam != 1 || same == sents);
      if (!ok) bad++;
      System.out.printf(Locale.ROOT, "ширина %d, %s: предложений %d · как у жадного %d · оценка = пересчёту %d (наибольшая разница %.2e) · не хуже жадного по оценке %d%s%n",
          beam, seq ? "по одному" : "пакетом", sents, same, tfOk, maxDiff, notWorse, ok ? "" : " — ПРОВАЛ");
    }
    System.exit(bad == 0 ? 0 : 1);
  }
}
