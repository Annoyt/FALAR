package dev.agenttranslator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Что ходит между приложением и процессом NPU (NpuService ↔ NpuClient) и разбор причины его падения — без Android,
 *  проверяется на столе (NpuWireTest). */
final class NpuWire {
  private NpuWire() {}

  /** Результат распознавания фразы: те же поля, что NpuAsr.Result. */
  static final class Res {
    final String text; final String[] tokens; final float[] times, durs, logProbs;
    Res(String text, String[] tokens, float[] times, float[] durs, float[] logProbs) {
      this.text = text; this.tokens = tokens; this.times = times; this.durs = durs; this.logProbs = logProbs;
    }
  }

  /** Результат → байты; null — фраза длиннее окна NPU (её распознаёт процессор). */
  static byte[] encode(Res r) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    try (DataOutputStream o = new DataOutputStream(bo)) {
      o.writeBoolean(r != null);
      if (r != null) {
        utf(o, r.text);
        o.writeInt(r.tokens == null ? -1 : r.tokens.length);
        if (r.tokens != null) for (String t : r.tokens) utf(o, t);
        floats(o, r.times); floats(o, r.durs); floats(o, r.logProbs);
      }
    }
    return bo.toByteArray();
  }

  static Res decode(byte[] b) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(b))) {
      if (!in.readBoolean()) return null;
      String text = utf(in);
      int n = in.readInt();
      String[] tokens = n < 0 ? null : new String[n];
      for (int k = 0; k < n; k++) tokens[k] = utf(in);
      return new Res(text, tokens, floats(in), floats(in), floats(in));
    }
  }

  private static void utf(DataOutputStream o, String s) throws IOException {
    byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    o.writeInt(b.length); o.write(b);
  }
  private static String utf(DataInputStream in) throws IOException {
    byte[] b = new byte[in.readInt()]; in.readFully(b); return new String(b, StandardCharsets.UTF_8);
  }
  private static void floats(DataOutputStream o, float[] a) throws IOException {
    o.writeInt(a == null ? -1 : a.length);
    if (a != null) for (float x : a) o.writeFloat(x);
  }
  private static float[] floats(DataInputStream in) throws IOException {
    int n = in.readInt(); if (n < 0) return null;
    float[] a = new float[n]; for (int k = 0; k < n; k++) a[k] = in.readFloat(); return a;
  }

  /** Ошибка словами для журнала и статистики: класс и сообщение одной строкой, не длиннее 300 знаков. В сообщениях
   *  ONNX Runtime и дополнений — названия операций и устройств, текста разговора в них нет. */
  static String why(Throwable e) {
    if (e == null) return "";
    String m = e.getMessage();
    String s = e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    return cut(s.replaceAll("\\s+", " ").trim(), 300);
  }

  static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n - 1) + "…" : s; }

  // ---------------------------------------------------------------------------------------------------------------
  // Причина падения процесса NPU — по tombstone: Android 12+ отдаёт его приложению (ApplicationExitInfo
  // .getTraceInputStream при REASON_CRASH_NATIVE) в protobuf system/core/debuggerd/proto/tombstone.proto:
  //   Tombstone: tid = 6, signal_info = 10 (Signal: name = 2, code_name = 4), abort_message = 14,
  //              threads = 16 (map<uint32, Thread>: key = 1, value = 2);
  //   Thread: current_backtrace = 4 (BacktraceFrame: function_name = 4, file_name = 6).
  // Итог — «SIGSEGV SEGV_MAPERR · #00 ? · #01 libonnxruntime_providers_neuropilot.so · …»: видно, в какой библиотеке
  // и на каком вызове упало. Не разобралось — сигнал и первые библиотеки, найденные в байтах как есть.
  // ---------------------------------------------------------------------------------------------------------------

  /** Сводка падения по tombstone; frames — сколько первых кадров упавшего потока. */
  static String tombstone(byte[] b, int frames) {
    if (b == null || b.length == 0) return "";
    try {
      String s = parseTombstone(b, frames);
      if (!s.isEmpty()) return cut(s, 300);
    } catch (RuntimeException ignore) { }
    return cut(scanTombstone(b), 300);
  }

  /** Читатель protobuf: ключ поля (номер и тип), varint, отрезок длины. */
  static final class Pb {
    final byte[] b; int p; final int end; int field, type;
    Pb(byte[] b, int from, int end) { this.b = b; this.p = from; this.end = end; }
    long varint() {
      long r = 0;
      for (int s = 0; s < 64; s += 7) {
        if (p >= end) throw new IllegalStateException("varint за концом");
        int x = b[p++] & 0xff;
        r |= (long) (x & 0x7f) << s;
        if ((x & 0x80) == 0) return r;
      }
      throw new IllegalStateException("varint длиннее 64 бит");
    }
    boolean next() {
      if (p >= end) return false;
      long k = varint(); field = (int) (k >>> 3); type = (int) (k & 7);
      return true;
    }
    /** Отрезок поля длины (тип 2): [начало, конец). */
    int[] span() {
      if (type != 2) throw new IllegalStateException("не длина");
      long n = varint();
      if (n < 0 || p + n > end) throw new IllegalStateException("длина за концом");
      int s = p; p += (int) n; return new int[]{s, s + (int) n};
    }
    String string() { int[] s = span(); return new String(b, s[0], s[1] - s[0], StandardCharsets.UTF_8); }
    void skip() {
      switch (type) {
        case 0: varint(); break;
        case 1: p += 8; break;
        case 2: span(); break;
        case 5: p += 4; break;
        default: throw new IllegalStateException("тип " + type);
      }
      if (p > end) throw new IllegalStateException("за концом");
    }
  }

  static String parseTombstone(byte[] b, int frames) {
    Pb t = new Pb(b, 0, b.length);
    long tid = -1; String sig = "", code = "", abort = "";
    List<long[]> threads = new ArrayList<>();          // {ключ, начало, конец} — потоки, упавший — по tid
    while (t.next()) {
      if (t.field == 6 && t.type == 0) tid = t.varint();
      else if (t.field == 10 && t.type == 2) {
        int[] s = t.span(); Pb g = new Pb(b, s[0], s[1]);
        while (g.next()) {
          if (g.field == 2 && g.type == 2) sig = g.string();
          else if (g.field == 4 && g.type == 2) code = g.string();
          else g.skip();
        }
      } else if (t.field == 14 && t.type == 2) abort = t.string();
      else if (t.field == 16 && t.type == 2) {
        int[] s = t.span(); Pb e = new Pb(b, s[0], s[1]);
        long key = -1; int[] val = null;
        while (e.next()) {
          if (e.field == 1 && e.type == 0) key = e.varint();
          else if (e.field == 2 && e.type == 2) val = e.span();
          else e.skip();
        }
        if (val != null) threads.add(new long[]{key, val[0], val[1]});
      } else t.skip();
    }
    if (sig.isEmpty() && threads.isEmpty()) return "";
    long[] crashed = null;
    for (long[] th : threads) if (th[0] == tid) { crashed = th; break; }
    if (crashed == null && !threads.isEmpty()) crashed = threads.get(0);
    StringBuilder o = new StringBuilder(sig.isEmpty() ? "сигнал ?" : sig);
    if (!code.isEmpty()) o.append(' ').append(code);
    if (!abort.isEmpty()) o.append(" · abort: ").append(cut(abort.replaceAll("\\s+", " ").trim(), 100));
    if (crashed != null) {
      Pb th = new Pb(b, (int) crashed[1], (int) crashed[2]);
      int n = 0;
      while (th.next() && n < frames) {
        if (th.field == 4 && th.type == 2) {
          int[] s = th.span(); Pb f = new Pb(b, s[0], s[1]);
          String fn = "", file = "";
          while (f.next()) {
            if (f.field == 4 && f.type == 2) fn = f.string();
            else if (f.field == 6 && f.type == 2) file = f.string();
            else f.skip();
          }
          o.append(" · #").append(n < 10 ? "0" : "").append(n).append(' ').append(file.isEmpty() ? "?" : base(file));
          if (!fn.isEmpty()) o.append(' ').append(cut(fn, 48));
          n++;
        } else th.skip();
      }
    }
    return o.toString();
  }

  static String base(String path) { int i = path.lastIndexOf('/'); return i < 0 ? path : path.substring(i + 1); }

  private static final Pattern SIG = Pattern.compile("SIG[A-Z]{3,6}"), SO = Pattern.compile("lib[A-Za-z0-9_+-]*(?:\\.[A-Za-z0-9_+-]+)*?\\.so(?![A-Za-z0-9_])");

  /** Запасной разбор: сигнал и до трёх разных библиотек в порядке появления в первых 64 КБ. */
  static String scanTombstone(byte[] b) {
    String s = new String(b, 0, Math.min(b.length, 1 << 16), StandardCharsets.ISO_8859_1);
    Matcher m = SIG.matcher(s);
    String sig = m.find() ? m.group() : "сигнал ?";
    Set<String> libs = new LinkedHashSet<>();
    Matcher l = SO.matcher(s);
    while (l.find() && libs.size() < 3) libs.add(l.group());
    return sig + (libs.isEmpty() ? "" : " · " + String.join(" · ", libs));
  }
}
