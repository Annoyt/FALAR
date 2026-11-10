package dev.agenttranslator;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Приложение ↔ процесс NPU (NpuService, app.falar:npu). Всё, что может кончиться плохо, кончается здесь словами:
 *  ошибка дополнения — исключением Remote с её текстом; падение процесса — причиной из ApplicationExitInfo (сигнал и
 *  кадры по tombstone, нехватка памяти, зависание); остановка сторожем — его причиной. Сообщает об этом один раз
 *  (Listener) — решает служба (TranslatorService.npuEnded): дальше процессор, отказ до новой версии или прошивки.
 *  Не на главном потоке: связь с процессом ждёт ответа. */
final class NpuClient implements AutoCloseable {
  /** Ошибка в процессе NPU без его падения (ORT или дополнение бросили исключение): текст — NpuWire.why. */
  static final class Remote extends Exception { Remote(String m) { super(m); } }

  /** Конец работы NPU: на какой стадии (start — подъём графа, probe — пробный прогон, check — сверка с процессором,
   *  run — фразы), почему и из-за памяти ли (тогда NPU не виноват — посреди работы это не отказ). */
  interface Listener { void ended(NpuClient c, String stage, String why, boolean memory); }

  /** Фраза длиннее окна NPU (832 кадра признаков по 10 мс) — не отправляется: распознаёт процессор. И сделка Binder
   *  не больше 1 МБ: 8,3 с звука — 0,53 МБ. */
  static final int MAX_SAMPLES = NpuAsr.F * 160;

  final Context ctx;
  final ActivityManager am;
  final int pid;
  private final ServiceConnection conn;
  private final IBinder b;
  private final IBinder.DeathRecipient dr = this::onDeath;
  private final AtomicBoolean reported = new AtomicBoolean();
  private volatile boolean closed;
  private volatile String killedWhy;
  private volatile boolean killedMemory;
  volatile String how = "", stage = "start";
  volatile Listener listener;
  /** Меньше всего свободной памяти телефона, пока жив процесс NPU (сторож), байт. */
  volatile long minFree = Long.MAX_VALUE;

  private NpuClient(Context ctx, ServiceConnection conn, IBinder b, int pid) {
    this.ctx = ctx; this.conn = conn; this.b = b; this.pid = pid;
    this.am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
  }

  /** Поднять процесс NPU и узнать его pid. BIND_ALLOW_OOM_MANAGEMENT: при нехватке памяти система закрывает сначала
   *  его, а не приложение (и он не «спящий» — не замораживается, как фоновые). */
  static NpuClient connect(Context c, long waitMs) throws Exception {
    final CountDownLatch l = new CountDownLatch(1);
    final IBinder[] got = {null};
    ServiceConnection conn = new ServiceConnection() {
      @Override public void onServiceConnected(ComponentName n, IBinder s) { got[0] = s; l.countDown(); }
      @Override public void onServiceDisconnected(ComponentName n) { }
    };
    if (!c.bindService(new Intent(c, NpuService.class), conn, Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_OOM_MANAGEMENT))
      throw new IllegalStateException("служба NPU не запускается");
    if (!l.await(waitMs, TimeUnit.MILLISECONDS)) {
      try { c.unbindService(conn); } catch (Throwable ignore) { }
      throw new IllegalStateException("процесс NPU не ответил за " + waitMs / 1000 + " с");
    }
    IBinder b = got[0];
    Parcel in = Parcel.obtain(), out = Parcel.obtain();
    int pid;
    try {
      in.writeInterfaceToken(NpuService.DESC);
      b.transact(NpuService.HELLO, in, out, 0);
      status(out);
      pid = out.readInt();
    } catch (Exception e) {
      try { c.unbindService(conn); } catch (Throwable ignore) { }
      throw e;
    } finally { in.recycle(); out.recycle(); }
    NpuClient nc = new NpuClient(c, conn, b, pid);
    b.linkToDeath(nc.dr, 0);
    return nc;
  }

  private static void status(Parcel out) throws Remote {
    if (out.readInt() != 0) throw new Remote(out.readString());
  }

  /** Поднять граф: из сохранённого ctx или собрать (до минут). Возвращает мс; how — как поднялся. */
  long start(String kind, File asrDir, File npuDir, String model, File ctxFile, File lib, Map<String, String> opts) throws Exception {
    stage = "start";
    Parcel in = Parcel.obtain(), out = Parcel.obtain();
    try {
      in.writeInterfaceToken(NpuService.DESC);
      in.writeString(kind); in.writeString(asrDir.getPath()); in.writeString(npuDir.getPath()); in.writeString(model);
      in.writeString(ctxFile == null ? "" : ctxFile.getPath()); in.writeString(lib.getPath());
      Bundle o = new Bundle();
      for (Map.Entry<String, String> e : opts.entrySet()) o.putString(e.getKey(), e.getValue());
      in.writeBundle(o);
      b.transact(NpuService.START, in, out, 0);
      status(out);
      how = out.readString();
      return out.readLong();
    } finally { in.recycle(); out.recycle(); }
  }

  /** Пробный прогон кодировщика на пустом окне, мс. */
  long probe() throws Exception {
    stage = "probe";
    Parcel in = Parcel.obtain(), out = Parcel.obtain();
    try {
      in.writeInterfaceToken(NpuService.DESC);
      b.transact(NpuService.PROBE, in, out, 0);
      status(out);
      return out.readLong();
    } finally { in.recycle(); out.recycle(); }
  }

  /** Распознать фразу (16 кГц); null — длиннее окна NPU. */
  NpuAsr.Result run(float[] s) throws Exception {
    if (s.length > MAX_SAMPLES) return null;
    Parcel in = Parcel.obtain(), out = Parcel.obtain();
    try {
      in.writeInterfaceToken(NpuService.DESC);
      in.writeFloatArray(s);
      b.transact(NpuService.RUN, in, out, 0);
      status(out);
      NpuWire.Res r = NpuWire.decode(out.createByteArray());
      return r == null ? null : new NpuAsr.Result(r.text, r.tokens, r.times, r.durs, r.logProbs);
    } finally { in.recycle(); out.recycle(); }
  }

  /** Сторож: свободной памяти телефона меньше guardBytes — процесс NPU останавливается, пока ядро цело (POCO 09.11
   *  дважды перезагрузился, когда граф не влез: kernel_panic … oom); подъём графа дольше buildMs — тоже. Пока граф
   *  поднимается — раз в 0,5 с, потом — раз в 5 с. */
  void guard(long guardBytes, long buildMs) {
    Thread t = new Thread(() -> {
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      long t0 = SystemClock.elapsedRealtime();
      while (!closed) {
        am.getMemoryInfo(mi);
        minFree = Math.min(minFree, mi.availMem);
        boolean building = "start".equals(stage);
        if (mi.availMem < guardBytes) {
          kill("мало памяти: свободно " + (mi.availMem >> 20) + " МБ" + (building ? " при подъёме графа" : ""), true);
          return;
        }
        if (building && SystemClock.elapsedRealtime() - t0 > buildMs) {
          kill("граф не поднялся за " + buildMs / 60_000 + " мин", false);
          return;
        }
        SystemClock.sleep(building ? 500 : 5000);
      }
    }, "npu-guard");
    t.setDaemon(true);
    t.start();
  }

  /** Остановить процесс NPU с причиной (сторож): её и получит Listener. */
  void kill(String why, boolean memory) {
    killedWhy = why; killedMemory = memory;
    android.os.Process.killProcess(pid);
  }

  /** Сообщить о конце один раз (кто первый: служба по ошибке или смерть процесса) и закрыть. */
  boolean report(String why, boolean memory) {
    if (!reported.compareAndSet(false, true)) return false;
    Listener l = listener;
    if (l != null) l.ended(this, stage, why, memory);
    close();
    return true;
  }

  /** Процесс умер (сигнал, нехватка памяти, сторож): причина — своим потоком (её запись появляется не сразу). */
  private void onDeath() {
    if (closed) return;
    new Thread(() -> {
      if (killedWhy != null) { report(killedWhy, killedMemory); return; }
      Exit x = exit();
      report(x.why, x.memory);
    }, "npu-death").start();
  }

  /** Причина смерти процесса NPU (уже умер). */
  Exit exit() {
    if (killedWhy != null) return new Exit(killedWhy, killedMemory);
    if (Build.VERSION.SDK_INT < 30) return new Exit("процесс NPU завершился (до Android 11 причина не сообщается)", false);
    for (int k = 0; k < 20; k++) {
      try {
        List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(ctx.getPackageName(), pid, 1);
        if (!l.isEmpty()) return describe(l.get(0));
      } catch (Throwable e) { return new Exit("причина не прочиталась: " + NpuWire.why(e), false); }
      SystemClock.sleep(250);
    }
    return new Exit("процесс NPU завершился, причина не записана", false);
  }

  static final class Exit {
    final String why; final boolean memory;
    Exit(String why, boolean memory) { this.why = why; this.memory = memory; }
  }

  static Exit describe(ApplicationExitInfo x) {
    String d = x.getDescription() == null ? "" : NpuWire.cut(x.getDescription().replaceAll("\\s+", " ").trim(), 120);
    switch (x.getReason()) {
      case ApplicationExitInfo.REASON_CRASH_NATIVE: {
        String t = "";
        if (Build.VERSION.SDK_INT >= 31) {
          try (InputStream in = x.getTraceInputStream()) { if (in != null) t = NpuWire.tombstone(readAll(in, 8 << 20), 6); }
          catch (Throwable ignore) { }
        }
        return new Exit("сбой в библиотеке: " + (!t.isEmpty() ? t : !d.isEmpty() ? d : "сигнал " + x.getStatus()), false);
      }
      case ApplicationExitInfo.REASON_CRASH:
        return new Exit("сбой Java в процессе NPU" + (d.isEmpty() ? "" : ": " + d), false);
      case ApplicationExitInfo.REASON_LOW_MEMORY:
        return new Exit("система закрыла процесс NPU: мало памяти", true);
      case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
        return new Exit("система закрыла процесс NPU: слишком много ресурсов", true);
      case ApplicationExitInfo.REASON_ANR:
        return new Exit("процесс NPU завис", false);
      case ApplicationExitInfo.REASON_SIGNALED:      // SIGKILL без записи о памяти — так закрывает и lmkd без отчёта
        return new Exit("процесс NPU получил сигнал " + x.getStatus() + (d.isEmpty() ? "" : " (" + d + ")"), x.getStatus() == 9);
      default:
        return new Exit("процесс NPU завершился (причина " + x.getReason() + (d.isEmpty() ? "" : ": " + d) + ")", false);
    }
  }

  static byte[] readAll(InputStream in, int max) throws java.io.IOException {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    byte[] buf = new byte[1 << 16]; int n;
    while ((n = in.read(buf)) > 0 && o.size() < max) o.write(buf, 0, n);
    return o.toByteArray();
  }

  boolean closed() { return closed; }

  /** Отвязать и остановить процесс NPU: его память (граф, веса) освобождается сразу. */
  @Override public void close() {
    if (closed) return;
    closed = true;
    try { b.unlinkToDeath(dr, 0); } catch (Throwable ignore) { }
    try { ctx.unbindService(conn); } catch (Throwable ignore) { }
    android.os.Process.killProcess(pid);
  }
}
