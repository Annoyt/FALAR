package dev.agenttranslator;

import java.io.File;
import java.util.*;
import org.json.*;

/** «Слова» и «Фразы» на разговорах телефона — только числа (данные владельца в чат не идут: правило 27.10).
 *  Сколько реплик берётся в разбор и почему остальные — нет; сколько карточек даёт нынешний разбор (Cards) и
 *  сколько вышло бы на экран; сколько словосочетаний из 2–5 слов набралось бы по предложенному правилу.
 *  Сами сочетания — в файл рядом с данными (private/…), не в вывод.
 *    bash bench/apk/cards_sim.sh <каталог files телефона> [файл сочетаний]
 *  Выборка для проверки перевода (results/2026-11-05-learn-cards.md): -Dsample=N -Dseed=5 -Dsampleout=<набор.json> —
 *  N случайных сочетаний (от 2 раз) с предложением-примером, набор в виде data/test_set.json для MtRun. */
public class CardsSim {
  public static void main(String[] a) throws Exception {
    File files = new File(a[0]);
    File models = new File(a.length > 2 ? a[2] : "data");
    // Фразы стендового корпуса (bench/air/corpus, fresh): до пометки стенда (03.10) прогоны писали их в разговоры
    // владельца — повторами они раздули бы «Фразы».
    Set<String> corpus = new HashSet<>();
    for (int k = 3; k < a.length; k++) {
      File[] fs = new File(a[k]).listFiles((d, n) -> n.endsWith(".txt"));
      if (fs != null) for (File f : fs) for (String line : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
        String key = Phrasebook.norm(line); if (!key.isEmpty()) corpus.add(key);
      }
    }
    Chats chats = new Chats(files);
    Learn learn = new Learn(chats, models, files);
    int total = 0, photo = 0, stand = 0, heard = 0, own = 0, ownImp = 0, nchats = 0;
    for (String[] c : chats.list()) {
      JSONObject o = chats.load(Long.parseLong(c[0]));
      JSONArray t = o == null ? null : o.optJSONArray("turns");
      if (t == null) continue;
      nchats++;
      for (int k = 0; k < t.length(); k++) {
        JSONObject x = t.optJSONObject(k); if (x == null) continue;
        total++;
        if (x.has("photo")) { photo++; continue; }
        if (x.optInt("stand", 0) == 1) { stand++; continue; }
        if (x.optString("dir", "").startsWith("pt")) heard++; else { own++; if (Learn.improved(x)) ownImp++; }
      }
    }
    System.out.printf("разговоров %d, реплик %d: снимков %d, стенда %d; собеседник %d, свои %d (с улучшенным переводом %d)%n",
        nchats, total, photo, stand, heard, own, ownImp);
    List<Cards.Turn> turns = new ArrayList<>();
    int corp = 0;
    for (Cards.Turn t : learn.turnsForCards()) {
      boolean all = true; String[] ss = Cards.split(t.pt);
      for (String x : ss) all &= corpus.contains(Phrasebook.norm(x));
      if (ss.length > 0 && all) corp++; else turns.add(t);
    }
    System.out.printf("фраз корпуса для сверки %d; реплик целиком из корпуса (прогоны стенда) — %d, не в счёт%n", corpus.size(), corp);
    long bt = System.nanoTime();
    Cards.Result r = Cards.build(turns, learn.stop, 2, new HashSet<>());
    System.out.printf("разбор на столе — %d мс%n", (System.nanoTime() - bt) / 1000000);
    int w3 = 0, w2 = 0, ch = 0, ch3 = 0;
    for (Cards.Card c : r.words) { if (c.n >= 3) w3++; if (c.n >= 2) w2++; if (c.kind == Cards.CHUNK) { ch++; if (c.n >= 3) ch3++; } }
    System.out.printf("нынешний разбор: реплик %d, предложений %d; слов и связок %d (потолок %d), из них от 3 раз %d, от 2 раз %d; связок %d (от 3 раз %d); фраз %d%n",
        r.turns, r.sentences, r.words.size(), Cards.MAX_CARDS, w3, w2, ch, ch3, r.phrases.size());

    // Всего значимых слов в разговорах — до потолка карточек
    Map<String, Integer> wc = new HashMap<>();
    for (Cards.Turn t : turns) for (String x : Cards.split(t.pt)) for (String w : Phrasebook.norm(x).split(" ")) if (Cards.content(w, learn.stop)) wc.merge(w, 1, Integer::sum);
    int v3 = 0, v2 = 0; for (int n : wc.values()) { if (n >= 3) v3++; if (n >= 2) v2++; }
    System.out.printf("значимых слов в разговорах %d, от 2 раз %d, от 3 раз %d%n", wc.size(), v2, v3);

    // Словосочетания 2–5 слов: по предложениям реплик собеседника (pt — его речь) и своим с улучшенным переводом (pt —
    // улучшенный перевод). Раз на предложение. Правило: хотя бы одно значимое слово, на служебном не кончается;
    // встречалось в двух разговорах и больше. Вложенное (короче) убирается, если почти всегда (≥ 60 %) стоит в длинном.
    Map<String, Integer> cnt = new HashMap<>(); Map<String, Set<Long>> inChats = new HashMap<>(); Map<String, Set<String>> who = new HashMap<>();
    Map<String, String[]> example = new HashMap<>();              // сочетание → {предложение, его перевод из разговора}
    int sents = 0;
    for (Cards.Turn t : turns) { String[] ps = Cards.split(t.pt), rs = Cards.split(t.ru); for (int si = 0; si < ps.length; si++) {
      String s = ps[si], sru = ps.length == rs.length ? rs[si] : t.ru;
      String key = Phrasebook.norm(s); if (key.isEmpty()) continue;
      String[] tok = key.split(" "); sents++;
      Set<String> seen = new HashSet<>();
      for (int n = 2; n <= 5; n++) for (int i = 0; i + n <= tok.length; i++) {
        boolean any = false; for (int j = i; j < i + n; j++) any |= Cards.content(tok[j], learn.stop);
        if (!any || learn.stop.contains(tok[i + n - 1])) continue;
        String g = String.join(" ", Arrays.copyOfRange(tok, i, i + n));
        if (!seen.add(g)) continue;
        cnt.merge(g, 1, Integer::sum);
        inChats.computeIfAbsent(g, x -> new HashSet<>()).add(t.chat);
        who.computeIfAbsent(g, x -> new HashSet<>()).add(t.heard ? "h" : "o");
        // пример — предложение собеседника, где перевод предложения известен; иначе какое есть
        String[] ex = example.get(g);
        boolean better = ex == null || "o".equals(ex[2]) && t.heard;
        if (better) example.put(g, new String[]{s, sru, t.heard ? "h" : "o"});
      }
    } }
    int[] minN = {2, 3};
    StringBuilder dump = new StringBuilder();
    for (int mn : minN) {
      Map<String, Integer> keep = new HashMap<>();
      for (Map.Entry<String, Integer> e : cnt.entrySet())
        if (e.getValue() >= mn && inChats.get(e.getKey()).size() >= 2) keep.put(e.getKey(), e.getValue());
      // вложенное в длинное почти всегда — не отдельной карточкой
      Set<String> drop = new HashSet<>();
      for (String g : keep.keySet()) {
        String[] t = g.split(" ");
        for (int n = 2; n < t.length; n++) for (int i = 0; i + n <= t.length; i++) {
          String sub = String.join(" ", Arrays.copyOfRange(t, i, i + n));
          Integer c = keep.get(sub);
          if (c != null && keep.get(g) >= Cards.ABSORB * c) drop.add(sub);
        }
      }
      int[] byLen = new int[6]; int heardOnly = 0, both = 0;
      for (String g : keep.keySet()) if (!drop.contains(g)) {
        byLen[g.split(" ").length]++;
        Set<String> w = who.get(g); if (w.size() == 2) both++; else if (w.contains("h")) heardOnly++;
        if (mn == 2) dump.append(keep.get(g)).append('\t').append(inChats.get(g).size()).append('\t').append(g).append('\n');
      }
      int tot = byLen[2] + byLen[3] + byLen[4] + byLen[5];
      String out = System.getProperty("sampleout");
      if (mn == 2 && out != null) {
        List<String> pool = new ArrayList<>();
        for (String g : keep.keySet()) if (!drop.contains(g)) pool.add(g);
        Collections.sort(pool);
        Collections.shuffle(pool, new Random(Long.getLong("seed", 5)));
        JSONArray set = new JSONArray();
        for (String g : pool.subList(0, Math.min(Integer.getInteger("sample", 100), pool.size()))) {
          String[] ex = example.get(g);
          set.put(new JSONObject().put("pt", g).put("ru", ex[1]).put("n", keep.get(g)).put("chats", inChats.get(g).size()).put("ex", ex[0]).put("heard", "h".equals(ex[2])));
        }
        java.nio.file.Files.write(new File(out).toPath(), new JSONObject().put("pt_to_ru", set).toString(1).getBytes("UTF-8"));
        System.out.println("выборка " + set.length() + " из " + pool.size() + " — в " + out);
      }
      System.out.printf("сочетания от %d раз в 2+ разговорах: %d — из 2 слов %d, 3 — %d, 4 — %d, 5 — %d; только у собеседника %d, и у вас, и у него %d (предложений %d)%n",
          mn, tot, byLen[2], byLen[3], byLen[4], byLen[5], heardOnly, both, sents);
    }
    if (a.length > 1 && !a[1].isEmpty()) {
      java.nio.file.Files.write(new File(a[1]).toPath(), dump.toString().getBytes("UTF-8"));
      System.out.println("сочетания (от 2 раз) — в " + a[1] + " (число, разговоров, сочетание)");
    }
  }
}
