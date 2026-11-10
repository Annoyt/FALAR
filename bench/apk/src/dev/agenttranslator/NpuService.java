package dev.agenttranslator;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Распознавание на NPU в своём процессе (app.falar:npu, AndroidManifest). Владелец 10.11: «если не работает — чтобы
 *  процесс шёл на процессоре». Дополнения NPU (QNN Qualcomm, NeuroPilot MediaTek) — код на C++: их сбой — сигнал, и
 *  он убивает процесс целиком; Java его не ловит (Redmi Note 10 Pro, 10.11: SIGSEGV при создании сессии уронил весь
 *  Falar). Здесь падает только этот процесс — приложение узнаёт об этом (NpuClient, linkToDeath), читает причину и
 *  распознаёт процессором дальше. Память NPU (граф, веса) — тоже здесь: сторож приложения может остановить процесс,
 *  когда её мало, не трогая остального.
 *
 *  Протокол — Binder.transact без AIDL (сборка bench/apk/build.sh без aidl): ответ начинается с 0 (дальше результат)
 *  или 1 (дальше ошибка словами, NpuWire.why). HELLO — pid процесса (сторожу); START — поднять NpuAsr (граф из
 *  сохранённого или собрать: минуты); PROBE — пробный прогон кодировщика, мс; RUN — распознать фразу (16 кГц). */
public final class NpuService extends Service {
  static final String DESC = "app.falar.npu";
  static final int HELLO = 1, START = 2, PROBE = 3, RUN = 4;

  private volatile NpuAsr asr;

  private final Binder binder = new Binder() {
    @Override protected boolean onTransact(int code, Parcel in, Parcel out, int flags) throws android.os.RemoteException {
      if (code < HELLO || code > RUN) return super.onTransact(code, in, out, flags);
      in.enforceInterface(DESC);
      try {
        switch (code) {
          case HELLO:
            out.writeInt(0); out.writeInt(android.os.Process.myPid());
            return true;
          case START: {
            String kind = in.readString(), asrDir = in.readString(), npuDir = in.readString(), model = in.readString(),
                ctx = in.readString(), lib = in.readString();
            Bundle b = in.readBundle(NpuService.class.getClassLoader());
            Map<String, String> o = new HashMap<>();
            if (b != null) for (String k : b.keySet()) o.put(k, b.getString(k));
            if ("qnn".equals(kind)) Npu.adspPath(new File(lib));    // переменная окружения — своя у каждого процесса
            long t = System.nanoTime();
            NpuAsr a = new NpuAsr(new File(asrDir), new File(npuDir), model, o, ctx.isEmpty() ? null : new File(ctx));
            NpuAsr old = asr; asr = a;
            if (old != null) try { old.close(); } catch (Throwable ignore) { }
            out.writeInt(0); out.writeString(a.how); out.writeLong((System.nanoTime() - t) / 1_000_000);
            return true;
          }
          case PROBE: {
            long ms = need().probeMs();
            out.writeInt(0); out.writeLong(ms);
            return true;
          }
          case RUN: {
            float[] s = in.createFloatArray();
            NpuAsr.Result r = need().run(s);
            byte[] w = NpuWire.encode(r == null ? null : new NpuWire.Res(r.text, r.tokens, r.times, r.durs, r.logProbs));
            out.writeInt(0); out.writeByteArray(w);
            return true;
          }
          default:
            return false;
        }
      } catch (Throwable e) {                       // ошибка ORT или дополнения без падения: словами — приложению
        out.writeInt(1); out.writeString(NpuWire.why(e));
        return true;
      }
    }
  };

  private NpuAsr need() {
    NpuAsr a = asr;
    if (a == null) throw new IllegalStateException("NPU ещё не поднят");
    return a;
  }

  @Override public IBinder onBind(Intent i) { return binder; }

  @Override public void onDestroy() {
    NpuAsr a = asr; asr = null;
    if (a != null) try { a.close(); } catch (Throwable ignore) { }
    super.onDestroy();
  }
}
