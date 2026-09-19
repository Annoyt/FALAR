import java.io.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import org.json.*;

/** SentencePiece unigram (Viterbi) на словаре из TSV (piece \t score) + vocab.json Marian для id. */
public class SpmTokenizer {
  final HashMap<String, Float> score = new HashMap<>();
  final HashMap<String, Long> vocab = new HashMap<>();
  final HashMap<Long, String> inv = new HashMap<>();
  final long unk; int maxPiece = 1;
  public SpmTokenizer(String piecesTsv, String vocabJson) throws Exception {
    for (String l : Files.readAllLines(Paths.get(piecesTsv))) {
      int t = l.lastIndexOf('\t'); if (t < 0) continue;
      String p = l.substring(0, t); score.put(p, Float.parseFloat(l.substring(t + 1))); maxPiece = Math.max(maxPiece, p.length());
    }
    JSONObject v = new JSONObject(new String(Files.readAllBytes(Paths.get(vocabJson)), "UTF-8"));
    Iterator<String> it = v.keys(); while (it.hasNext()) { String k = it.next(); long id = v.getLong(k); vocab.put(k, id); inv.put(id, k); }
    unk = vocab.get("<unk>");
  }
  /** pieces -> строка (для детокенизации выхода MT) */
  public String decode(List<Long> ids) {
    StringBuilder sb = new StringBuilder();
    for (long id : ids) { String p = inv.get(id); if (p == null || p.equals("</s>") || p.equals("<pad>")) continue; sb.append(p); }
    return sb.toString().replace('▁', ' ').trim();
  }
  public List<String> pieces(String text) {
    String s = Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    s = "▁" + s.replace(' ', '▁');
    int n = s.length(); double[] best = new double[n + 1]; int[] back = new int[n + 1];
    Arrays.fill(best, Double.NEGATIVE_INFINITY); best[0] = 0;
    for (int i = 0; i < n; i++) {
      if (best[i] == Double.NEGATIVE_INFINITY) continue;
      for (int j = i + 1; j <= Math.min(n, i + maxPiece); j++) {
        Float sc = score.get(s.substring(i, j));
        double cand = (sc != null) ? best[i] + sc : (j == i + 1 ? best[i] - 100.0 : Double.NEGATIVE_INFINITY); // unk-символ: штраф
        if (cand > best[j]) { best[j] = cand; back[j] = i; }
      }
    }
    LinkedList<String> out = new LinkedList<>();
    for (int j = n; j > 0; j = back[j]) out.addFirst(s.substring(back[j], j));
    return out;
  }
  public List<Long> encode(String text, String langToken) {
    List<Long> ids = new ArrayList<>();
    if (langToken != null) ids.add(vocab.get(langToken));
    for (String p : pieces(text)) { Long id = vocab.get(p); ids.add(id != null ? id : unk); }
    ids.add(vocab.get("</s>"));
    return ids;
  }
}
