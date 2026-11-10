package dev.agenttranslator;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Процесс NPU: передача результата фразы, ошибка словами, причина падения по tombstone (NpuWire), проверка NPU по
 *  первым фразам (NpuCheck). Запуск: bash bench/apk/test.sh. */
public class NpuWireTest {
  int fails = 0, checks = 0;
  void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }

  // --- писатель protobuf для поддельного tombstone ---
  static void varint(ByteArrayOutputStream o, long v) { while ((v & ~0x7fL) != 0) { o.write((int) ((v & 0x7f) | 0x80)); v >>>= 7; } o.write((int) v); }
  static void key(ByteArrayOutputStream o, int f, int t) { varint(o, ((long) f << 3) | t); }
  static void vfield(ByteArrayOutputStream o, int f, long v) { key(o, f, 0); varint(o, v); }
  static void bytes(ByteArrayOutputStream o, int f, byte[] b) { key(o, f, 2); varint(o, b.length); o.write(b, 0, b.length); }
  static void str(ByteArrayOutputStream o, int f, String s) { bytes(o, f, s.getBytes(StandardCharsets.UTF_8)); }
  static byte[] frame(String fn, String file) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    vfield(o, 1, 0x116fb4); vfield(o, 2, 0x7a00116fb4L);
    if (!fn.isEmpty()) str(o, 4, fn);
    if (!file.isEmpty()) str(o, 6, file);
    str(o, 8, "b4fcf14a9fb422a");
    return o.toByteArray();
  }
  static byte[] thread(int id, String name, byte[]... frames) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    vfield(o, 1, id); str(o, 2, name);
    for (byte[] f : frames) bytes(o, 4, f);
    return o.toByteArray();
  }
  static byte[] entry(int key, byte[] thread) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    vfield(o, 1, key); bytes(o, 2, thread);
    return o.toByteArray();
  }

  public int run() throws Exception {
    // --- результат фразы туда и обратно ---
    NpuWire.Res r = new NpuWire.Res(" Bom dia", new String[]{" Bom", " di", "a"}, new float[]{0.08f, 0.32f, 0.4f},
        new float[]{0.16f, 0.08f, 0.08f}, new float[]{-0.1f, -0.7f, -0.05f});
    NpuWire.Res b = NpuWire.decode(NpuWire.encode(r));
    eq(b.text, " Bom dia", "W1 текст");
    eq(Arrays.asList(b.tokens), Arrays.asList(" Bom", " di", "a"), "W1 токены");
    ok(Arrays.equals(b.times, r.times) && Arrays.equals(b.durs, r.durs) && Arrays.equals(b.logProbs, r.logProbs), "W1 времена, длины, вероятности");
    ok(NpuWire.decode(NpuWire.encode(null)) == null, "W2 фраза длиннее окна — null туда и обратно");
    NpuWire.Res e = NpuWire.decode(NpuWire.encode(new NpuWire.Res("", null, null, new float[0], null)));
    ok(e.text.isEmpty() && e.tokens == null && e.times == null && e.durs.length == 0 && e.logProbs == null, "W3 пустые и отсутствующие части");
    eq(NpuWire.decode(NpuWire.encode(new NpuWire.Res("Привет, ação", new String[]{"▁При", "вет"}, null, null, null))).text,
        "Привет, ação", "W4 кириллица и диакритика");

    // --- ошибка словами ---
    eq(NpuWire.why(new IllegalStateException("Error code - ORT_EP_FAIL - message: Some ops\nunsupported by devices")),
        "IllegalStateException: Error code - ORT_EP_FAIL - message: Some ops unsupported by devices", "E1 одной строкой");
    ok(NpuWire.why(new RuntimeException(String.join("", Collections.nCopies(500, "x")))).length() == 300, "E2 не длиннее 300");
    eq(NpuWire.why(new NullPointerException()), "NullPointerException", "E3 без сообщения");

    // --- tombstone: сбой на Redmi 10.11 (вызов пустого указателя из дополнения NeuroPilot) ---
    ByteArrayOutputStream t = new ByteArrayOutputStream();
    vfield(t, 1, 3); str(t, 2, "Xiaomi/chopin/chopin:13/TP1A.220624.014/V14.0.6.0.TKPCNXM:user/release-keys");
    vfield(t, 5, 5538); vfield(t, 6, 5657); vfield(t, 7, 10208);
    ByteArrayOutputStream sig = new ByteArrayOutputStream();
    vfield(sig, 1, 11); str(sig, 2, "SIGSEGV"); vfield(sig, 3, 1); str(sig, 4, "SEGV_MAPERR"); vfield(sig, 8, 1); vfield(sig, 9, 0);
    bytes(t, 10, sig.toByteArray());
    String lib = "/data/app/~~JUNRGrCJ6KxGp-0aRysKpQ==/app.falar-db8b/lib/arm64/";
    bytes(t, 16, entry(5538, thread(5538, "app.falar:npu", frame("__epoll_pwait", "/apex/com.android.runtime/lib64/bionic/libc.so"))));
    bytes(t, 16, entry(5657, thread(5657, "binder:5538_2", frame("", ""),
        frame("", lib + "libonnxruntime_providers_neuropilot.so"), frame("", lib + "libonnxruntime_providers_neuropilot.so"),
        frame("OrtApis::CreateSessionFromArray(OrtEnv const*, void const*, unsigned long, OrtSessionOptions const*, OrtSession**)", lib + "libonnxruntime.so"),
        frame("Java_ai_onnxruntime_OrtSession_createSession", lib + "libonnxruntime4j_jni.so"))));
    ByteArrayOutputStream map = new ByteArrayOutputStream(); str(map, 1, "/system/lib64/libbinder.so"); bytes(t, 17, map.toByteArray());
    String s = NpuWire.tombstone(t.toByteArray(), 4);
    eq(s, "SIGSEGV SEGV_MAPERR · #00 ? · #01 libonnxruntime_providers_neuropilot.so · #02 libonnxruntime_providers_neuropilot.so"
        + " · #03 libonnxruntime.so OrtApis::CreateSessionFromArray(OrtEnv const*, …", "T1 сигнал и кадры упавшего потока, не главного");
    ok(!s.contains("libbinder") && !s.contains("libc.so"), "T2 библиотеки из карты памяти и других потоков — не в сводке");

    ByteArrayOutputStream ab = new ByteArrayOutputStream();
    vfield(ab, 6, 9); ByteArrayOutputStream sa = new ByteArrayOutputStream(); str(sa, 2, "SIGABRT"); bytes(ab, 10, sa.toByteArray());
    str(ab, 14, "Check failed: status == NEURON_NO_ERROR\n(compilation)");
    bytes(ab, 16, entry(9, thread(9, "npu", frame("abort", "/apex/com.android.runtime/lib64/bionic/libc.so"), frame("", "/system_ext/lib64/libneuronusdk_adapter.mtk.so"))));
    eq(NpuWire.tombstone(ab.toByteArray(), 2), "SIGABRT · abort: Check failed: status == NEURON_NO_ERROR (compilation) · #00 libc.so abort · #01 libneuronusdk_adapter.mtk.so",
        "T3 сообщение abort одной строкой");

    byte[] junk = ("\u0001ÿSIGBUS..../vendor/lib64/libapusys.so..libneuron_runtime.8.so..libapusys.so.." + "x").getBytes(StandardCharsets.ISO_8859_1);
    eq(NpuWire.tombstone(junk, 4), "SIGBUS · libapusys.so · libneuron_runtime.8.so", "T4 не разобралось — сигнал и библиотеки как есть");
    eq(NpuWire.tombstone(new byte[0], 4), "", "T5 пусто");
    byte[] cut = Arrays.copyOf(t.toByteArray(), 40);
    ok(!NpuWire.tombstone(cut, 4).isEmpty(), "T6 оборванный — не падает, что-то говорит");

    // --- проверка NPU по первым фразам ---
    NpuCheck c = new NpuCheck();
    eq(c.add("Bom dia, tudo bem?", "bom dia tudo bem"), NpuCheck.Verdict.PENDING, "C1 одна совпала — ещё рано");
    eq(c.add("Ага.", ""), NpuCheck.Verdict.PENDING, "C2 короткая — не судится");
    eq(c.add("Onde fica a estação de trem?", "Onde fica a estação do trem?"), NpuCheck.Verdict.OK, "C3 две совпали (слово другое) — принят");
    eq(c.summary(), "совпало 2 из 2 фраз, сходство в среднем 98 %", "C3 итог числами, без текстов");
    NpuCheck d = new NpuCheck();
    d.add("Quanto custa este bilhete?", "");
    eq(d.add("Где здесь аптека?", "кхм ыы ааа"), NpuCheck.Verdict.FAIL, "C4 пусто и каша — отказ");
    ok(d.summary().contains("у NPU пусто — 1"), "C4 в итоге — сколько пустых");
    NpuCheck f = new NpuCheck();
    f.add("Bom dia", "bom dia");
    f.add("Tudo bem com você?", "");
    eq(f.add("Eu quero um café", "eu quero um café"), NpuCheck.Verdict.OK, "C5 две из трёх — принят");
    NpuCheck g = new NpuCheck();
    eq(g.add("Bom dia", null), NpuCheck.Verdict.PENDING, "C6 NPU фразу не взял (длиннее окна) — не судится");
    eq(g.judged, 0, "C6 не посчитана");
    eq(NpuCheck.norm("  Olá,  MUNDO!! "), "olá mundo", "C7 без регистра и знаков");
    ok(Math.abs(NpuCheck.similarity("abc", "abd") - 2 / 3.0) < 1e-9, "C8 сходство — по Левенштейну");
    eq(NpuCheck.similarity("", ""), 1.0, "C8 оба пусты");

    System.out.println("NpuWire: " + checks + " проверок, " + (fails == 0 ? "все прошли" : fails + " не прошли"));
    return fails;
  }

  public static void main(String[] a) throws Exception { System.exit(new NpuWireTest().run() == 0 ? 0 : 1); }
}
