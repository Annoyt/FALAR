package dev.agenttranslator;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Хранение разговоров: правка человека неприкосновенна для автоматики. Именно здесь была ошибка
 *  «двух переводов подряд»: правка текста стирала пометку «человек», и уточнитель через несколько
 *  секунд переводил исправленную реплику заново поверх исправления. Запуск: bash bench/apk/test.sh. */
public class ChatsTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static File tmp() throws Exception { File d = Files.createTempDirectory("chats").toFile(); d.deleteOnExit(); return d; }

  public static int run() throws Exception {
    fails = 0; checks = 0;
    Chats c = new Chats(tmp());
    c.add("ru2pt", "Могу предложить виноград и Тиаблоко", "Posso sugerir uvas Tiabloko", null, 1000);
    c.add("pt2ru", "Estou satisfeita, agradeço", "Я довольна, спасибо", null, 2000);
    eq(c.size(), 2, "C1 две реплики");

    // автоматика может поправить машинную реплику
    ok(c.fixByAt(2000, "Я сыта, благодарю", Chats.BY_LLM), "C2 уточнитель правит машинную реплику");
    ok(!c.humanAt(2000), "C2 машинная реплика — не человеческая");

    // правка текста: новая метка, пометка «человек», автоматика больше не трогает
    long at = c.replaceTurn(0, "Могу предложить виноград и яблоко", "Posso oferecer uvas e maçãs");
    ok(at > 2000, "C3 правка текста получила новую метку");
    ok(c.humanAt(at), "C3 правка текста помечена как человеческая");
    ok(!c.fixByAt(at, "Posso sugerir uvas e maçãs", Chats.BY_LLM), "C4 уточнитель не перезаписывает правку текста");
    ok(!c.fixByAt(at, "Posso sugerir uvas e maçãs", Chats.BY_CLOUD), "C4 облако тоже не перезаписывает");
    eq(c.turn(0)[2], "Posso oferecer uvas e maçãs", "C5 перевод остался тем, что после правки");
    ok(c.fixByAt(at, "Posso te oferecer uvas e maçãs", Chats.BY_USER), "C6 сам человек может поправить снова");
    eq(c.turn(0)[2], "Posso te oferecer uvas e maçãs", "C6 его вторая правка легла");

    // неизвестные метки и выход за границы
    ok(!c.humanAt(12345), "C7 нет реплики с такой меткой — не человеческая");
    ok(!c.fixByAt(12345, "x", Chats.BY_LLM), "C7 правка несуществующей реплики не проходит");
    eq(c.replaceTurn(5, "a", "b"), 0L, "C8 правка за пределами — ноль");
    eq(c.replaceTurn(-1, "a", "b"), 0L, "C8 отрицательный номер — ноль");

    // сохранено на диск: после перечитывания пометка на месте
    Chats c2 = new Chats(c.dir.getParentFile());
    c2.open(c.current);
    ok(c2.humanAt(at), "C9 пометка пережила перечитывание с диска");

    System.out.println(fails == 0 ? "Chats: " + checks + " проверок, все прошли" : "Chats: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
