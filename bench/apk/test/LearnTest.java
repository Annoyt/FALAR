package dev.agenttranslator;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Разбор слов для «Слов»: реплики стенда (поле `stand`) не считаются. На 02.10 треть реплик в
 *  разговорах владельца была корпусом прогонов, и «Слова» учили слова замеров. Запуск: bash bench/apk/test.sh. */
public class LearnTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static File tmp() throws Exception { File d = Files.createTempDirectory("learn").toFile(); d.deleteOnExit(); return d; }

  public static int run() throws Exception {
    fails = 0; checks = 0;
    File files = tmp(), models = tmp();
    Chats c = new Chats(files);
    c.add("pt2ru", "Espera um pouquinho, já volto", "Подождите немножко, сейчас вернусь", null, 1000);
    c.add("ru2pt", "Говорите чуть медленнее", "Fala um pouquinho mais devagar", null, 2000);
    // корпус прогона: и с повтором слова внутри, и в другом разговоре
    c.addTurn(c.current, Chats.turn("pt2ru", "Sou um turista, turista mesmo", "Я турист, правда турист", null, 3000).put("stand", 1));
    c.newChat("");
    c.addTurn(c.current, Chats.turn("pt2ru", "Seu relógio está certo", "Ваши часы идут верно", null, 4000).put("stand", 1));
    c.add("pt2ru", "Um pouquinho de água", "Немножко воды", null, 5000);

    Learn l = new Learn(c, models, files);
    Map<String, Learn.Word> top = new HashMap<>();
    for (Learn.Word w : l.top(1, 100)) top.put(w.w, w);
    ok(top.containsKey("pouquinho"), "L1 живое слово в списке");
    eq(top.containsKey("pouquinho") ? top.get("pouquinho").n : -1, 3, "L1 счёт по живым репликам обоих разговоров");
    ok(!top.containsKey("turista") && !top.containsKey("relógio"), "L2 слова стендовых реплик не считаются");
    ok(!l.inCorpus("turista"), "L2 и для перевода слов их нет");
    String ex = top.containsKey("pouquinho") ? top.get("pouquinho").pt : "";
    ok(!ex.isEmpty() && !ex.contains("turista"), "L3 пример — из живой реплики: " + ex);
    System.out.println(fails == 0 ? "Learn: " + checks + " проверок, все прошли" : "Learn: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
