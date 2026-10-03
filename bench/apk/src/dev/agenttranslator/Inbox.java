package dev.agenttranslator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Сказанное, удержанное и набранное, пока грузятся модели, — на диске, пока не переведено. Загрузка идёт
 *  около 10 с, и это пик памяти процесса (1,8 ГБ): выгруженный посреди неё процесс терял бы очередь в
 *  памяти (владелец 03.10: «при недостаточном количестве памяти приложение может выгружаться»). То, что
 *  осталось здесь, следующий запуск переводит как сказанное только что; старше MAX_AGE_MS — уже не к
 *  разговору и удаляется без перевода.
 *
 *  Файлы: raw-<время>.pcm — поток микрофона, пока нарезки ещё нет (кадры не разрезаны на фразы);
 *  seg-<время>-<направление>.pcm — фраза, нарезанная слушанием; ptt-<время>-<направление>.pcm — фраза
 *  кнопкой удержания; text-<время>.txt — набранная фраза. Звук — 16 бит, 16 кГц, моно, без заголовка.
 *  Целые записи пишутся в .part и переименовываются: оборванная запись не примется за целую. Поток
 *  пишется сразу в свой файл кадр за кадром — при выгрузке посреди него теряется не больше кадра.
 *  Каталог — files/inbox рядом с разговорами: в облачную копию Google не уходит, как и они (backup_rules). */
final class Inbox {
  static final long MAX_AGE_MS = 10 * 60_000L;
  final File dir;
  Inbox(File dir) { this.dir = dir; dir.mkdirs(); }

  /** Запись очереди: файл, вид (raw, seg, ptt, text), когда сказано, направление (у звука фраз). */
  static final class Item {
    final File f; final String kind, dir; final long at;
    Item(File f, String kind, long at, String dir) { this.f = f; this.kind = kind; this.at = at; this.dir = dir; }
  }

  /** Имя записи → запись; чужое имя — null. */
  static Item parse(File f) {
    String n = f.getName();
    if (n.endsWith(".part")) return null;
    String base; if (n.endsWith(".pcm")) base = n.substring(0, n.length() - 4); else if (n.endsWith(".txt")) base = n.substring(0, n.length() - 4); else return null;
    String[] p = base.split("-");
    if (p.length < 2) return null;
    String kind = p[0];
    boolean pcm = n.endsWith(".pcm");
    if (pcm ? !(kind.equals("raw") || kind.equals("seg") || kind.equals("ptt")) : !kind.equals("text")) return null;
    long at; try { at = Long.parseLong(p[1]); } catch (NumberFormatException e) { return null; }
    String d = p.length > 2 ? p[2] : null;
    if ((kind.equals("seg") || kind.equals("ptt")) && !("pt2ru".equals(d) || "ru2pt".equals(d))) return null;
    return new Item(f, kind, at, d);
  }

  // ---- целые записи ---------------------------------------------------------------------------------
  /** Фраза звуком: seg или ptt. null — не записалась (тогда она живёт только в памяти, как раньше). */
  File put(String kind, long at, String dir, float[] pcm) {
    return write(new File(this.dir, kind + "-" + at + "-" + dir + ".pcm"), pcm16(pcm, pcm.length));
  }
  File putText(long at, String text) {
    return write(new File(dir, "text-" + at + ".txt"), text.getBytes(StandardCharsets.UTF_8));
  }
  File write(File f, byte[] b) {
    File p = new File(f.getPath() + ".part");
    try (FileOutputStream o = new FileOutputStream(p)) { o.write(b); }
    catch (IOException e) { p.delete(); return null; }
    return p.renameTo(f) ? f : null;
  }

  // ---- поток до нарезки -----------------------------------------------------------------------------
  FileOutputStream raw; File rawFile;
  /** Кадр потока: дописывается сразу, без буфера в памяти. Не записалось — дальше поток не пишется. */
  synchronized void raw(float[] frame, long now) {
    try {
      if (raw == null) { if (rawFile != null) return; rawFile = new File(dir, "raw-" + now + ".pcm"); raw = new FileOutputStream(rawFile, true); }
      raw.write(pcm16(frame, frame.length));
    } catch (IOException e) { closeRaw(); }
  }
  synchronized boolean rawOpen() { return raw != null; }
  /** Поток кончился (поднялась нарезка): файл остаётся, пока нарезка не дочитает его кадры (dropRaw). */
  synchronized void closeRaw() { if (raw != null) try { raw.close(); } catch (IOException ignore) {} raw = null; }
  /** Нарезка дочитала кадры потока — свои и оставшиеся от прошлого запуска: файлы потока больше не нужны. */
  synchronized int dropRaw() {
    closeRaw(); rawFile = null; int n = 0;
    File[] fs = dir.listFiles();
    if (fs != null) for (File f : fs) { Item it = parse(f); if (it != null && it.kind.equals("raw") && f.delete()) n++; }
    return n;
  }

  /** Что осталось от прошлого запуска — по времени. Несвежее и оборванное (.part) удаляется сразу;
   *  в stale — сколько удалено несвежего. Звать до того, как этот запуск начнёт писать свой поток. */
  List<Item> leftovers(long now, int[] stale) {
    List<Item> out = new ArrayList<>();
    File[] fs = dir.listFiles();
    if (fs != null) for (File f : fs) {
      Item it = parse(f);
      if (it == null) { if (f.getName().endsWith(".part")) f.delete(); continue; }
      if (now - it.at > MAX_AGE_MS || it.at > now + 60_000L) { if (f.delete() && stale != null) stale[0]++; continue; }
      if (f.length() == 0) { f.delete(); continue; }
      out.add(it);
    }
    out.sort((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : a.f.getName().compareTo(b.f.getName()));
    return out;
  }

  // ---- звук ----------------------------------------------------------------------------------------
  static byte[] pcm16(float[] x, int n) {
    byte[] b = new byte[2 * n];
    for (int i = 0; i < n; i++) {
      float v = Math.max(-1f, Math.min(1f, x[i]));
      int s = Math.round(v * 32767f);
      b[2 * i] = (byte) s; b[2 * i + 1] = (byte) (s >> 8);
    }
    return b;
  }
  /** Звук записи; оборванный последний отсчёт отбрасывается. */
  static float[] readPcm(File f) throws IOException {
    byte[] b = readAll(f);
    int n = b.length / 2; float[] x = new float[n];
    for (int i = 0; i < n; i++) x[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8)) / 32768f;
    return x;
  }
  static String readText(File f) throws IOException { return new String(readAll(f), StandardCharsets.UTF_8); }
  static byte[] readAll(File f) throws IOException {
    try (FileInputStream in = new FileInputStream(f)) {
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[1 << 14]; int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      return bo.toByteArray();
    }
  }
}
