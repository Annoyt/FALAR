package dev.agenttranslator;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Порядок бесплатных облачных моделей: «быстрее» — надёжные и быстрые вперёд, «точнее» — крупные.
 *  Размер берётся из имени, и ошибиться тут легко: «3.3» — версия, «a55b» — активные параметры,
 *  «e4b» — эффективные. Ключ в файле тестовый, в сеть тест не ходит. Запуск: bash bench/apk/test.sh. */
public class CloudTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": «" + a + "» ≠ «" + b + "»"); }
  static String m(String id, int ok, int fail, long ms, boolean price) {
    return "\"" + id + "\":{\"free\":" + price + ",\"img\":false,\"txt\":true,\"caps\":true,\"ok\":" + ok + ",\"fail\":" + fail + ",\"ms\":" + ms + "}";
  }

  public static int run() throws Exception {
    fails = 0; checks = 0;
    // ---- размер по имени
    eq(Cloud.sizeB("nvidia/nemotron-3-ultra-550b-a55b:free"), 550.0, "S1 полный размер, а не активный «a55b»");
    eq(Cloud.sizeB("qwen/qwen3-235b-a22b:free"), 235.0, "S1 235b");
    eq(Cloud.sizeB("meta-llama/llama-3.3-70b-instruct:free"), 70.0, "S2 версия «3.3» размером не считается");
    eq(Cloud.sizeB("mistralai/mistral-small-3.2-24b-instruct:free"), 24.0, "S2 24b после версии");
    eq(Cloud.sizeB("x/model-1.5b:free"), 1.5, "S3 дробный размер");
    eq(Cloud.sizeB("google/gemma-3n-e4b-it:free"), 0.0, "S4 «e4b» — не полный размер");
    eq(Cloud.sizeB("deepseek/deepseek-chat-v3.1:free"), 0.0, "S4 размера в имени нет");
    eq(Cloud.sizeB("x/v2b-thing:free"), 0.0, "S4 «v2b» — не размер");
    eq(Cloud.sizeB("x/model-8bit:free"), 0.0, "S4 «8bit» — не размер");
    eq(Cloud.sizeB("x/model-70b:free-8b"), 70.0, "S5 после двоеточия не смотрим");
    ok(Cloud.light("mistralai/mistral-small-3.2-24b-instruct:free"), "L1 small — облегчённая");
    ok(Cloud.light("google/gemini-2.0-flash-exp:free"), "L1 flash — облегчённая");
    ok(Cloud.light("z-ai/glm-4.5-air:free"), "L1 air — облегчённая");
    ok(!Cloud.light("minimax/minimax-m1:free"), "L2 «minimax» — не «mini»");
    ok(!Cloud.light("nvidia/nemotron-3-ultra-550b-a55b:free"), "L2 крупная — не облегчённая");
    ok(!Cloud.light("x/model:mini"), "L2 после двоеточия не смотрим");
    ok(Cloud.unusable("nvidia/nemotron-3.5-content-safety:free"), "U1 модель модерации — не переводчик");
    ok(Cloud.unusable("meta-llama/llama-guard-4-12b:free"), "U1 guard — тоже");
    ok(!Cloud.unusable("x/safetyfirst-70b:free"), "U2 «safety» внутри слова — не модерация");
    ok(!Cloud.unusable("x/model:guard"), "U2 после двоеточия не смотрим");
    eq(Cloud.reliability(0, 0), 0.5, "R1 новая модель — половина");
    eq(Cloud.reliability(10, 0), 11.0 / 12, "R1 10 из 10 — не единица: попыток мало");
    ok(Math.abs(Cloud.reliability(34, 6) - 35.0 / 42) < 1e-12, "R2 6 отказов из 40 — 83 %, а не штраф за каждый");
    ok(Cloud.reliability(1, 3) < Cloud.reliability(0, 0), "R3 больше отказов, чем ответов, — ниже новой");

    // ---- порядок
    File d = Files.createTempDirectory("cloud").toFile(); d.deleteOnExit();
    String[] ids = {"x/model-8b:free", "y/big-550b-a55b:free", "z/mid-70b:free", "w/unknown-model:free", "v/flash-lite:free", "u/x-vl:free", "t/cheap-400b:free", "s/nemotron-content-safety:free"};
    String json = "{\"key\":\"test-key\",\"pick\":\"\",\"models\":[\"" + String.join("\",\"", ids) + "\"],\"m\":{"
        + m(ids[0], 10, 0, 2000, true) + "," + m(ids[1], 10, 0, 20000, true) + "," + m(ids[2], 3, 3, 5000, true) + ","
        + m(ids[3], 0, 0, 0, true) + "," + m(ids[4], 5, 0, 1500, true) + "," + m(ids[5], 5, 0, 1000, true) + "," + m(ids[6], 10, 0, 3000, false) + "," + m(ids[7], 10, 0, 500, true) + "}}";
    Files.write(new File(d, "openrouter.json").toPath(), json.getBytes("UTF-8"));
    Cloud c = new Cloud(d);
    ok(c.ready, "O0 файл прочитан");
    List<String> fast = Arrays.asList(c.order());
    eq(fast.get(0), "x/model-8b:free", "O1 «быстрее»: первой — быстрая и надёжная");
    ok(fast.indexOf("x/model-8b:free") < fast.indexOf("y/big-550b-a55b:free"), "O1 «быстрее»: 2 с раньше 20 с при равной надёжности");
    eq(fast.get(fast.size() - 1), "s/nemotron-content-safety:free", "O2 модель модерации — в самый хвост, как бы быстро ни отвечала");
    eq(fast.get(fast.size() - 2), "t/cheap-400b:free", "O2 цена не подтверждена — в хвост при любом размере");
    c.preferQuality = true;
    List<String> q = Arrays.asList(c.order());
    eq(q.get(0), "y/big-550b-a55b:free", "O3 «точнее»: первой — самая крупная из надёжных");
    ok(q.indexOf("x/model-8b:free") < q.indexOf("z/mid-70b:free"), "O4 «точнее»: ненадёжная 70b ниже надёжной 8b");
    ok(q.indexOf("x/model-8b:free") < q.indexOf("v/flash-lite:free"), "O5 «точнее»: облегчённая ветка ниже");
    eq(q.get(q.size() - 1), "s/nemotron-content-safety:free", "O2 и в «точнее» модерация — последней");
    eq(q.get(q.size() - 2), "t/cheap-400b:free", "O2 и в «точнее» неподтверждённая цена — в хвост");
    c.pick = "w/unknown-model:free";
    eq(c.order()[0], "w/unknown-model:free", "O6 закреплённая руками — первой в «точнее»");
    c.preferQuality = false;
    eq(c.order()[0], "w/unknown-model:free", "O6 и в «быстрее»");
    c.pick = "";
    // время в «точнее» почти не весит, но при прочих равных быстрая впереди
    Cloud.M a = c.metaOf("x/model-8b:free");
    c.preferQuality = true;
    double s1 = c.score(a, false); a.ms = 60000; double s2 = c.score(a, false); a.ms = 120000; double s3 = c.score(a, false);
    ok(s1 > s2 && Math.abs((s1 - s2) - 0.4667) < 0.01, "O7 минута ответа стоит в «точнее» меньше полбалла: " + (s1 - s2));
    ok(Math.abs(s2 - s3) < 1e-9, "O7 и больше полбалла не набегает");
    Cloud.M u = c.metaOf("w/unknown-model:free");
    ok(Math.abs(c.score(u, false) - 3.5) < 1e-9, "O8 новая модель без размера: половина надёжности и середина размера: " + c.score(u, false));
    Cloud.M t = c.metaOf("zz/model-0.5b:free");
    t.priceOk = true; t.capsKnown = true;
    ok(Math.abs(c.score(t, false) - 2.0) < 1e-9, "O8 меньше миллиарда — размер не в минус: " + c.score(t, false));
    // быстрая с редкими отказами обгоняет медленную безотказную в «быстрее» (как nex-mini и nemotron-550b)
    Cloud.M fm = c.metaOf("f/quick:free"); fm.priceOk = true; fm.capsKnown = true; fm.ok = 34; fm.fail = 6; fm.ms = 4100;
    Cloud.M sm = c.metaOf("g/slow-550b:free"); sm.priceOk = true; sm.capsKnown = true; sm.ok = 48; sm.fail = 0; sm.ms = 12400;
    c.preferQuality = false;
    ok(c.score(fm, false) > c.score(sm, false), "O9 «быстрее»: 34/40 за 4 с раньше 48/48 за 12 с");
    c.preferQuality = true;
    ok(c.score(sm, false) > c.score(fm, false), "O9 «точнее»: наоборот");

    // имена без «:free» — разбор тот же
    eq(Cloud.sizeB("x/model-70b"), 70.0, "N1 размер без двоеточия");
    ok(Cloud.light("x/model-mini"), "N1 облегчённая без двоеточия");
    ok(Cloud.unusable("x/content-safety"), "N1 модерация без двоеточия");
    ok(Cloud.domainish("x/model-vl"), "N1 отраслевая без двоеточия");
    ok(Cloud.domainish("x/model-vl:free-thing"), "N1 отраслевая с двоеточием — по части до него");
    // не отдаёт текст — последняя в обоих режимах
    Cloud.M img = c.metaOf("i/big-550b-image:free"); img.priceOk = true; img.capsKnown = true; img.textOut = false; img.ok = 50;
    c.preferQuality = false; eq(c.score(img, false), -100.0, "T1 «быстрее»: не отдаёт текст — вне игры");
    c.preferQuality = true;  eq(c.score(img, false), -100.0, "T1 «точнее»: и размер не спасает");
    // возможности не спрошены — «не отдаёт текст» из старого файла не приговор: сначала спросить
    Cloud.M unk = c.metaOf("k/model-70b:free"); unk.priceOk = true; unk.capsKnown = false; unk.textOut = false;
    c.preferQuality = false; ok(c.score(unk, false) > -100, "T1 «быстрее»: возможности не спрошены — не исключаем");
    c.preferQuality = true;  ok(c.score(unk, false) > -100, "T1 «точнее»: тоже");
    // закреплённой нет среди бесплатных — порядок не трогаем и чужую не вставляем
    c.pick = "gone/model:free";
    ok(!Arrays.asList(c.order()).contains("gone/model:free"), "T5 закреплённая пропала — в порядок не попадает");
    c.pick = "";
    // отраслевая ветка ниже ровно на балл
    Cloud.M p1 = c.metaOf("p/model-70b:free"), p2 = c.metaOf("p/model-70b-vl:free");
    for (Cloud.M x : new Cloud.M[]{p1, p2}) { x.priceOk = true; x.capsKnown = true; x.ok = 5; x.ms = 3000; }
    ok(Math.abs(c.score(p1, false) - c.score(p2, false) - 1.0) < 1e-9, "T2 «точнее»: отраслевая ниже на балл");
    c.preferQuality = false;
    ok(Math.abs(c.score(p1, false) - c.score(p2, false) - 1.0) < 1e-9, "T2 «быстрее»: тоже");
    // ничья — в порядке OpenRouter; пустой список — пустой порядок
    File d2 = Files.createTempDirectory("cloud2").toFile(); d2.deleteOnExit();
    Files.write(new File(d2, "openrouter.json").toPath(), ("{\"key\":\"k\",\"models\":[\"b/two:free\",\"a/one:free\"],\"m\":{"
        + m("b/two:free", 0, 0, 0, true) + "," + m("a/one:free", 0, 0, 0, true) + "}}").getBytes("UTF-8"));
    Cloud c2 = new Cloud(d2);
    eq(String.join(",", c2.order()), "b/two:free,a/one:free", "T3 ничья — порядок OpenRouter");
    c2.preferQuality = true;
    eq(String.join(",", c2.order()), "b/two:free,a/one:free", "T3 и в «точнее»");
    File d3 = Files.createTempDirectory("cloud3").toFile(); d3.deleteOnExit();
    Cloud c3 = new Cloud(d3);
    ok(!c3.ready && c3.order().length == 0 && c3.next().isEmpty(), "T4 без файла — моделей нет, порядок пуст");

    System.out.println(fails == 0 ? "Cloud: " + checks + " проверок, все прошли" : "Cloud: провалов " + fails + " из " + checks);
    // ---- несколько ключей: прежний файл с одним ключом, порядок, удаление, переход на следующий
    File kd = Files.createTempDirectory("cloudkeys").toFile(); kd.deleteOnExit();
    File kf = new File(kd, "openrouter.json");
    Files.write(kf.toPath(), "{\"key\":\"sk-or-v1-aaaaaaaaaaaaaaaaaaaa1111\",\"pick\":\"\",\"models\":[\"x/m-8b:free\"]}".getBytes("UTF-8"));
    Cloud k1 = new Cloud(kd);
    eq(k1.keys(), Arrays.asList("sk-or-v1-aaaaaaaaaaaaaaaaaaaa1111"), "K1 файл до списка ключей: один ключ");
    eq(k1.keyId(), "sk-or-v1-aaaa…1111", "K1 ключ опознаётся по началу и хвосту");
    Files.write(kf.toPath(), ("{\"key\":\"kB-bbbbbbbbbbbbbbbbbbbbb\",\"keys\":[\"kA-aaaaaaaaaaaaaaaaaaaaa\",\"kB-bbbbbbbbbbbbbbbbbbbbb\",\"kA-aaaaaaaaaaaaaaaaaaaaa\"],"
        + "\"pick\":\"\",\"models\":[\"x/m-8b:free\"]}").getBytes("UTF-8"));
    Cloud k2 = new Cloud(kd);
    eq(k2.keys(), Arrays.asList("kB-bbbbbbbbbbbbbbbbbbbbb", "kA-aaaaaaaaaaaaaaaaaaaaa"), "K2 «key» первым, повтор в списке — один раз");
    ok(k2.useNextKey(401), "K3 ключ не принят — переход на следующий");
    eq(k2.keys(), Arrays.asList("kA-aaaaaaaaaaaaaaaaaaaaa", "kB-bbbbbbbbbbbbbbbbbbbbb"), "K3 отвергнутый — в конец");
    ok(k2.note("kB-bbbbbbbbbbbbbbbbbbbbb").contains("HTTP 401"), "K3 у отвергнутого — пометка");
    ok(!k2.useNextKey(401), "K4 все помечены — дальше перебора нет");
    String saved = new String(Files.readAllBytes(kf.toPath()), "UTF-8");
    ok(saved.contains("\"key\":\"kA-aaaaaaaaaaaaaaaaaaaaa\""), "K5 в файле «key» — новый первый: его читают прежние сборки");
    eq(new Cloud(kd).keys(), Arrays.asList("kA-aaaaaaaaaaaaaaaaaaaaa", "kB-bbbbbbbbbbbbbbbbbbbbb"), "K5 порядок переживает перезапуск");
    k2.removeKey("kA-aaaaaaaaaaaaaaaaaaaaa");
    eq(k2.keys(), Arrays.asList("kB-bbbbbbbbbbbbbbbbbbbbb"), "K6 убран один ключ");
    ok(k2.ready, "K6 второй ключ остался — облако работает");
    k2.removeKey("kB-bbbbbbbbbbbbbbbbbbbbb");
    ok(!k2.ready && k2.keys().isEmpty(), "K7 ключей не осталось — облако выключено");
    ok(!new Cloud(kd).ready, "K7 и после перезапуска");

    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
