package dev.agenttranslator;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

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
    r.enroll(new float[]{1, 0}, "pt", 1);
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
    eq(r.voices.size(), 1, "C17 голоса перешли: те же люди");
    Chats r2 = new Chats(r.dir.getParentFile());
    r2.open(r.current);
    eq(r2.memo + "|" + r2.topic + "|" + r2.terms().size(), "Хозяйка (женщина)|Аренда|1", "C18 в файле продолжения тоже");
    r2.open(was);
    eq(r2.size(), Chats.KEEP, "C18 прежний разговор остался целым");

    // реплика со снимком: в разговоре и на экране есть, в диалоге для уточнителя и облака — нет
    Chats ph = new Chats(tmp());
    File pd = ph.photos(); pd.mkdirs();
    File jpg = new File(pd, "p1.jpg"); Files.write(jpg.toPath(), new byte[]{1, 2, 3});
    ph.add("pt2ru", "Obrigada, eu mesma", "Спасибо, я сама", null, 1_000);
    JSONObject photo = new JSONObject().put("file", "p1.jpg").put("w", 100).put("h", 80)
        .put("blocks", new JSONArray().put(new JSONObject().put("f", new JSONArray("[50,40,1,0,80,20]")).put("src", "PERIGO").put("dst", "Опасно")));
    ok(ph.addTurn(ph.current, Chats.turn("pt2ru", "PERIGO", "Опасно", null, 2_000).put("photo", photo)), "C19 реплика со снимком легла в разговор");
    ph.add("ru2pt", "Я понял", "Entendi", null, 3_000);
    eq(ph.all().size(), 3, "C19 на экране все три реплики");
    eq(ph.all().get(1)[8], Chats.PHOTO, "C19 у реплики со снимком метка 📷");
    eq(ph.all().get(0)[8], "", "C19 у обычной — нет");
    eq(ph.dialog().size(), 2, "C20 в диалоге для уточнителя и облака снимка нет");
    List<String[]> tl = ph.tail(2);
    eq(tl.size() + ":" + tl.get(0)[1] + ":" + tl.get(1)[1], "2:Obrigada, eu mesma:Я понял", "C20 рабочая история — две последние реплики диалога, снимок пропущен");
    JSONObject back = ph.photo(1);
    eq(back == null ? null : back.optString("file"), "p1.jpg", "C21 снимок читается по номеру реплики");
    back.put("file", "hack.jpg");
    eq(ph.photo(1).optString("file"), "p1.jpg", "C21 наружу отдаётся копия, разговор не портится");
    ok(ph.photo(0) == null && ph.photo(9) == null, "C21 у обычной реплики и за концом — null");
    Chats ph2 = new Chats(ph.dir.getParentFile()); ph2.open(ph.current);
    eq(ph2.photo(1) == null ? null : ph2.photo(1).optJSONArray("blocks").length(), 1, "C22 снимок с абзацами пережил запись на диск");
    ok(ph.deleteTurn(1) && !jpg.exists(), "C23 удалили реплику — удалён и файл снимка");
    File jpg2 = new File(pd, "p2.jpg"); Files.write(jpg2.toPath(), new byte[]{4});
    ph.addTurn(ph.current, Chats.turn("pt2ru", "SAÍDA", "Выход", null, 4_000).put("photo", new JSONObject().put("file", "p2.jpg")));
    long keep = ph.current; ph.newChat("Другой");
    ok(ph.delete(keep) && !jpg2.exists(), "C24 удалили разговор (не текущий) — удалены и его снимки");
    File jpg3 = new File(pd, "p3.jpg"); Files.write(jpg3.toPath(), new byte[]{5});
    ph.addTurn(ph.current, Chats.turn("pt2ru", "ENTRADA", "Вход", null, 5_000).put("photo", new JSONObject().put("file", "../p3.jpg")));
    ok(ph.delete(ph.current) && jpg3.exists(), "C25 имя файла с путём не удаляет ничего вне каталога снимков");

    // голоса разговора: в его файле, у каждого разговора свои, номера не переиспользуются
    Chats g = new Chats(tmp());
    ok(g.voices.isEmpty() && !g.load(g.current).has("voices"), "C26 голосов нет — и поля в файле нет");
    Voices.Voice gv = g.enroll(new float[]{1, 0, 0}, "ru", 10);
    g.add("ru2pt", "Я понял", "Entendi", String.valueOf(gv.n), 1_000);
    Voices.Voice gw = g.enroll(new float[]{0, 1, 0}, "pt", 20);
    g.add("pt2ru", "Obrigada", "Спасибо", String.valueOf(gw.n), 2_000);
    eq(gv.n + "," + gw.n, "1,2", "C26 двое — собеседники 1 и 2");
    ok(g.nameVoice(2, "Ана", "auto"), "C26 имя голоса");
    Chats g2 = new Chats(g.dir.getParentFile()); g2.open(g.current);
    eq(g2.voices.size() + " " + g2.voices.label("2") + " " + g2.voices.get(1).lang, "2 Ана ru", "C26 голоса и имя пережили запись на диск");
    eq(g2.all().get(1)[7], "2", "C26 у реплики — номер голоса");
    long gid = g.current; g.newChat("Другой");
    ok(g.voices.isEmpty(), "C27 новый разговор — без голосов");
    g.open(gid);
    eq(g.voices.size(), 2, "C27 вернулись в прежний — голоса его");
    eq(g.clearVoices(), 2, "C28 забыли голоса — сколько было");
    ok(!g.load(gid).has("voices"), "C28 из файла они ушли");
    eq(g.all().get(1)[7], "2", "C28 подписи реплик остались");
    eq(g.enroll(new float[]{0, 0, 1}, "pt", 30).n, 3, "C28 новый голос — номер после всех, что были в репликах");
    g.addTurn(g.current, Chats.turn("pt2ru", "SAÍDA", "Выход", Voices.OWNER, 3_000));
    g.newChat("Третий"); long other = g.current; g.open(gid);   // newChat возвращает тот, из которого ушли
    ok(g.moveTurn(1, other) && g.moveTurn(1, other), "C29 две реплики перенесены");
    JSONArray mt = g.load(other).getJSONArray("turns");
    ok(!mt.getJSONObject(0).has("who"), "C29 номер голоса в чужой разговор не переносится");
    eq(mt.getJSONObject(1).optString("who"), Voices.OWNER, "C29 владелец телефона — везде владелец");
    // старый файл без поля voices читается как раньше
    Chats o = new Chats(tmp());
    JSONObject old = new JSONObject().put("id", 42).put("name", "Старый").put("turns", new JSONArray().put(Chats.turn("pt2ru", "Oi", "Привет", "собеседник", 1)));
    Files.write(new File(o.dir, "42.json").toPath(), old.toString().getBytes("UTF-8"));
    o.open(42);
    ok(o.voices.isEmpty() && o.size() == 1, "C30 файл до 0.27 открывается, голосов нет");
    eq(o.voices.label(o.all().get(0)[7]), "", "C30 метка «собеседник» прежних версий подписью не становится");

    System.out.println(fails == 0 ? "Chats: " + checks + " проверок, все прошли" : "Chats: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
