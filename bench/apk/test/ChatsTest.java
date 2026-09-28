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

    // память разговора: облако пишет, человек правит, автоматика правку человека не трогает
    ok(c.setMemo("Хозяйка (женщина) показывает квартиру", Chats.BY_CLOUD), "C10 облако записало память");
    eq(c.memoBy, Chats.BY_CLOUD, "C10 пометка «облако»");
    ok(!c.setMemo("Хозяйка (женщина) показывает квартиру", Chats.BY_CLOUD), "C10 та же память от того же — не запись");
    ok(c.setMemo("Хозяйка Мария (женщина) сдаёт квартиру мне", Chats.BY_USER), "C11 человек поправил память");
    ok(!c.setMemo("Другое от облака", Chats.BY_CLOUD), "C11 облако не перезаписывает память человека");
    ok(!c.setMemo("Другое от модели", Chats.BY_LLM), "C11 модель тоже");
    ok(!c.setMemo("", Chats.BY_CLOUD), "C11 и стереть её автоматика не может");
    eq(c.memo, "Хозяйка Мария (женщина) сдаёт квартиру мне", "C11 память человека на месте");
    Chats c3 = new Chats(c.dir.getParentFile());
    c3.open(c.current);
    eq(c3.memo, "Хозяйка Мария (женщина) сдаёт квартиру мне", "C12 память пережила перечитывание с диска");
    eq(c3.memoBy, Chats.BY_USER, "C12 и пометка «человек» тоже");
    ok(c.setMemo("  ", Chats.BY_USER), "C13 человек стёр память");
    eq(c.memo + "|" + c.memoBy, "|", "C13 память пуста и снова за автоматикой");
    ok(!c.setMemo("", Chats.BY_USER), "C13 стереть пустую — не запись");
    ok(c.setMemo("Снова от облака", Chats.BY_CLOUD), "C13 облако снова пишет");
    ok(c.setMemo("Снова от облака", Chats.BY_USER), "C14 человек подтвердил тот же текст — запись");
    eq(c.memoBy, Chats.BY_USER, "C14 и теперь память его");
    Chats c5 = new Chats(c.dir.getParentFile());
    c5.open(c.current);
    ok(c5.setMemo("", Chats.BY_USER) && new Chats(c.dir.getParentFile()).memo.isEmpty(), "C14 стёртая память не остаётся в файле");

    // новый разговор — без памяти; прежний свою сохранил
    c.setMemo("Память первого", Chats.BY_USER);
    long first = c.current;
    c.newChat("Другой");
    eq(c.memo + "|" + c.memoBy, "|", "C15 новый разговор без памяти");
    c.open(first);
    eq(c.memo, "Память первого", "C15 прежний вернул свою память");

    // потолок в 500 реплик: продолжение — тот же разговор, память, тема и глоссарий переходят
    Chats r = new Chats(tmp());
    r.setTopic("Аренда"); r.setMemo("Хозяйка (женщина)", Chats.BY_CLOUD);
    r.addTerms(Collections.singletonList(new String[]{"aluguel", "аренда"}), Chats.BY_CLOUD);
    long was = r.current;
    for (int k = 0; k < Chats.KEEP; k++) r.add("pt2ru", "frase " + k, "фраза " + k, null, 10_000 + k);
    eq(r.size(), Chats.KEEP, "C16 ровно потолок");
    ok(!r.rolled, "C16 до потолка продолжения нет");
    r.add("pt2ru", "mais uma", "ещё одна", null, 99_999);
    ok(r.rolled && r.current != was, "C16 после потолка начато продолжение");
    eq(r.size(), 1, "C16 в продолжении одна реплика");
    eq(r.memo + "|" + r.memoBy, "Хозяйка (женщина)|" + Chats.BY_CLOUD, "C17 память перешла в продолжение");
    eq(r.topic, "Аренда", "C17 тема перешла");
    eq(r.terms().size(), 1, "C17 глоссарий перешёл");
    Chats r2 = new Chats(r.dir.getParentFile());
    r2.open(r.current);
    eq(r2.memo + "|" + r2.topic + "|" + r2.terms().size(), "Хозяйка (женщина)|Аренда|1", "C18 в файле продолжения тоже");
    r2.open(was);
    eq(r2.size(), Chats.KEEP, "C18 прежний разговор остался целым");

    System.out.println(fails == 0 ? "Chats: " + checks + " проверок, все прошли" : "Chats: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
