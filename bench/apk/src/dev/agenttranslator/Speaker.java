package dev.agenttranslator;

import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.nio.file.Files;
import org.json.*;

/**
 * Отпечаток голоса: 3D-Speaker CAM++ через sherpa-onnx. Точность распознавания не меняет —
 * нужен, чтобы понять, КТО говорит. Чьи голоса сравнивать, решает разговор (Voices): слепки
 * живут в нём, а здесь — только модель.
 * Модель опциональна: нет файла — вся функция выключена.
 */
public class Speaker {
  // Порог по замеру на живой речи (Tatoeba, 11 записей): один диктор сам с собой 0.83-0.96,
  // разные дикторы 0.13-0.30, серая зона 0.4-0.7. Синтезированная речь для проверки не годится:
  // у одного и того же голоса Piper сходство между фразами падает до 0.34.
  static final float MIN_SECONDS = 0.6f;

  final File dir; SpeakerEmbeddingExtractor ex;
  public boolean ready = false; public long loadMs = -1;

  public Speaker(File modelsDir) { this(modelsDir, true); }
  /** on — модуль «Отпечаток голоса» (Modules.SPEAKER): выключен — модель не поднимается, ready = false. */
  public Speaker(File modelsDir, boolean on) {
    dir = modelsDir;
    if (!on) return;
    File md = new File(modelsDir, "speaker");
    File[] f = md.listFiles((d, n) -> n.endsWith(".onnx"));
    if (f == null || f.length == 0) return;
    try {
      long t = System.currentTimeMillis();
      ex = new SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder()
          .setModel(f[0].getAbsolutePath()).setNumThreads(2).setDebug(false).build());
      loadMs = System.currentTimeMillis() - t; ready = true;
    } catch (Throwable t) { t.printStackTrace(); ready = false; }
  }

  /** Отпечаток единичной длины; null — модели нет или звука меньше MIN_SECONDS. */
  public synchronized float[] embed(float[] samples, int sr) {
    if (!ready || samples == null || samples.length < MIN_SECONDS * sr) return null;
    OnlineStream s = ex.createStream();
    try {
      s.acceptWaveform(samples, sr);
      s.acceptWaveform(new float[sr / 2], sr);      // хвост тишины, чтобы добрать последний кадр
      s.inputFinished();
      return ex.isReady(s) ? Voices.unit(ex.compute(s)) : null;
    } finally { s.release(); }
  }

  static float[] unit(float[] v) { return Voices.unit(v); }
  static float cos(float[] a, float[] b) { return Voices.cos(a, b); }

  /** Отдать модель отпечатка: без этого она оставалась в нативной памяти после остановки сервиса. */
  public synchronized void release() { ready = false; if (ex != null) try { ex.release(); } catch (Throwable ignore) {} ex = null; }

  /** До 0.27 голоса «я» и «собеседник» хранились одни на все разговоры (models/speaker_profiles.json,
   *  записывались на отдельном экране настроек). Теперь голос запоминается в разговоре кнопкой
   *  FALAR, и общий файл — чужая биометрия без дела: удаляем. Возвращает, сколько голосов в нём было
   *  (-1 — файла нет), чтобы сказать об этом числом, а не молча. */
  public static int retire(File modelsDir) {
    File f = new File(modelsDir, "speaker_profiles.json");
    if (!f.exists()) return -1;
    int n = 0;
    try { n = new JSONObject(new String(Files.readAllBytes(f.toPath()), "UTF-8")).length(); } catch (Exception ignore) {}
    return f.delete() ? n : -1;
  }
}
