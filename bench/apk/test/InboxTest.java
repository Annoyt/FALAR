package dev.agenttranslator;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;

/** Недоразобранное на диске (Inbox) без Android: имена, звук туда и обратно, поток до нарезки и его уборка,
 *  что берётся после выгрузки посреди загрузки и что выбрасывается. Запуск: bash bench/apk/test.sh. */
public class InboxTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static File tmp() throws Exception { File d = Files.createTempDirectory("inbox").toFile(); d.deleteOnExit(); return d; }
  static float[] tone(int n, double amp) { float[] x = new float[n]; for (int k = 0; k < n; k++) x[k] = (float) (amp * Math.sin(2 * Math.PI * 440 * k / 16000.0)); return x; }
  static double maxErr(float[] a, float[] b) { double m = 0; for (int k = 0; k < Math.min(a.length, b.length); k++) m = Math.max(m, Math.abs(a[k] - b[k])); return m; }

  public static int run() throws Exception {
    fails = 0; checks = 0;
    final long now = 1_790_000_000_000L;

    // I1 имена: вид, время, направление; чужое и оборванное — не запись
    Inbox.Item it = Inbox.parse(new File("seg-1790000000000-pt2ru.pcm"));
    ok(it != null && it.kind.equals("seg") && it.at == 1790000000000L && "pt2ru".equals(it.dir), "I1 seg: вид, время, направление");
    it = Inbox.parse(new File("ptt-5-ru2pt.pcm")); ok(it != null && it.kind.equals("ptt") && "ru2pt".equals(it.dir), "I1 ptt");
    it = Inbox.parse(new File("raw-7.pcm")); ok(it != null && it.kind.equals("raw") && it.dir == null, "I1 raw — без направления");
    it = Inbox.parse(new File("text-9.txt")); ok(it != null && it.kind.equals("text"), "I1 text");
    ok(Inbox.parse(new File("seg-1-pt2ru.pcm.part")) == null, "I1 .part — не запись");
    ok(Inbox.parse(new File("seg-1-xx.pcm")) == null, "I1 фраза без понятного направления — не запись");
    ok(Inbox.parse(new File("seg-abc-pt2ru.pcm")) == null, "I1 время не число — не запись");
    ok(Inbox.parse(new File("text-1.pcm")) == null && Inbox.parse(new File("seg-1-pt2ru.txt")) == null, "I1 вид и расширение не сходятся — не запись");
    ok(Inbox.parse(new File("readme.md")) == null, "I1 чужой файл");

    // I2 звук туда и обратно: 16 бит, ошибка не больше шага; громче полной шкалы — по краю
    File d = tmp(); Inbox in = new Inbox(d);
    float[] x = tone(16000, 0.5);
    File f = in.put("seg", now, "pt2ru", x);
    ok(f != null && f.isFile() && f.getName().equals("seg-" + now + "-pt2ru.pcm"), "I2 фраза записана под своим именем");
    eq(f.length(), 32000L, "I2 16 бит: два байта на отсчёт");
    float[] y = Inbox.readPcm(f);
    eq(y.length, x.length, "I2 длина");
    ok(maxErr(x, y) <= 1.0 / 32767 + 1e-6, "I2 ошибка не больше шага 16 бит: " + maxErr(x, y));
    float[] loud = {1.5f, -1.5f, 0f}; float[] l2 = Inbox.readPcm(in.put("ptt", now + 1, "ru2pt", loud));
    ok(Math.abs(l2[0] - 32767 / 32768f) < 1e-6 && Math.abs(l2[1] + 32767 / 32768f) < 1e-6 && l2[2] == 0f, "I2 перегруз — по краю шкалы, без переворота знака");
    ok(!new File(d, "seg-" + now + "-pt2ru.pcm.part").exists(), "I2 .part после записи не остаётся");
    File t = in.putText(now + 2, "Bom dia — где вокзал?");
    eq(Inbox.readText(t), "Bom dia — где вокзал?", "I2 набранное: UTF-8 туда и обратно");

    // I3 что осталось от прошлого запуска — по времени; несвежее и оборванное выброшено
    File d3 = tmp(); Inbox i3 = new Inbox(d3);
    i3.put("ptt", now - 2000, "ru2pt", tone(800, 0.1));
    i3.put("seg", now - 5000, "pt2ru", tone(800, 0.1));
    i3.putText(now - 1000, "olá");
    i3.put("seg", now - Inbox.MAX_AGE_MS - 1, "pt2ru", tone(800, 0.1));          // старше 10 мин
    i3.put("seg", now + 10 * 60_000L, "pt2ru", tone(800, 0.1));                  // из будущего — часы сбиты
    Files.write(new File(d3, "seg-" + (now - 3000) + "-pt2ru.pcm.part").toPath(), new byte[64]);   // оборвалась запись
    Files.write(new File(d3, "seg-" + (now - 4000) + "-pt2ru.pcm").toPath(), new byte[0]);         // пустая
    Files.write(new File(d3, "notes.txt").toPath(), new byte[3]);                                   // чужое — не трогаем
    int[] stale = {0};
    List<Inbox.Item> left = i3.leftovers(now, stale);
    eq(left.size(), 3, "I3 берутся три свежие записи");
    ok(left.size() == 3 && left.get(0).kind.equals("seg") && left.get(1).kind.equals("ptt") && left.get(2).kind.equals("text"), "I3 по времени: сначала сказанное раньше");
    eq(stale[0], 2, "I3 несвежее и из будущего — удалено и посчитано");
    ok(!new File(d3, "seg-" + (now - 3000) + "-pt2ru.pcm.part").exists(), "I3 оборванная запись удалена");
    ok(!new File(d3, "seg-" + (now - 4000) + "-pt2ru.pcm").exists(), "I3 пустая запись удалена");
    ok(new File(d3, "notes.txt").exists(), "I3 чужой файл не тронут");
    eq(i3.leftovers(now, null).size(), 3, "I3 повторный разбор — то же самое (ничего не удаляет из свежего)");

    // I4 поток до нарезки: кадры дописываются по одному, после закрытия больше не пишется; нарезка дочитала — файлы прочь
    File d4 = tmp(); Inbox i4 = new Inbox(d4);
    ok(!i4.rawOpen(), "I4 поток не открыт, пока не пришёл кадр");
    float[] fr = tone(512, 0.3);
    i4.raw(fr, now); i4.raw(fr, now + 32);
    ok(i4.rawOpen(), "I4 поток открыт");
    File rf = new File(d4, "raw-" + now + ".pcm");
    eq(rf.length(), 2048L, "I4 два кадра по 512 отсчётов — сразу на диске");
    i4.closeRaw(); i4.raw(fr, now + 64);
    eq(rf.length(), 2048L, "I4 после закрытия кадры не дописываются");
    ok(!i4.rawOpen(), "I4 закрыт");
    List<Inbox.Item> l4 = i4.leftovers(now + 100, null);
    ok(l4.size() == 1 && l4.get(0).kind.equals("raw"), "I4 поток прошлого запуска находится среди оставшегося");
    eq(Inbox.readPcm(rf).length, 1024, "I4 поток читается целиком");
    Files.write(new File(d4, "raw-" + (now - 9000) + ".pcm").toPath(), new byte[4]);   // поток позапрошлого запуска
    i4.put("seg", now, "pt2ru", fr);
    eq(i4.dropRaw(), 2, "I4 нарезка дочитала поток — удалены оба файла потока");
    ok(!rf.exists() && new File(d4, "seg-" + now + "-pt2ru.pcm").exists(), "I4 фраза остаётся: её уборка — после перевода");
    i4.raw(fr, now + 200);
    ok(new File(d4, "raw-" + (now + 200) + ".pcm").isFile(), "I4 после уборки поток может начаться снова (новый запуск загрузки)");

    // I5 оборванный последний отсчёт отбрасывается
    File odd = new File(d4, "raw-" + (now + 300) + ".pcm"); Files.write(odd.toPath(), new byte[]{0, 64, 7});
    float[] o = Inbox.readPcm(odd);
    ok(o.length == 1 && Math.abs(o[0] - 0.5f) < 1e-6, "I5 нечётный хвост отброшен, первый отсчёт цел");

    System.out.println("Inbox: " + checks + " проверок, " + (fails == 0 ? "все прошли" : fails + " провалено"));
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
