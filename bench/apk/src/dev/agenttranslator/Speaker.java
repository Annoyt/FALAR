package dev.agenttranslator;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.Collections;
import org.json.*;

/**
 * Отпечаток голоса: 3D-Speaker CAM++ через ONNX Runtime, признаки — свои (Fbank, рецепт 3D-Speaker).
 * Точность распознавания не меняет — нужен, чтобы понять, КТО говорит. Чьи голоса сравнивать,
 * решает разговор (Voices): слепки живут в нём, а здесь — только модель.
 * Модель опциональна: нет файла — вся функция выключена.
 *
 * До 0.27 отпечаток считал SpeakerEmbeddingExtractor из sherpa-onnx. Замер 01.10 на живых записях
 * Tatoeba: так модель людей почти не различала (AUC 0,56 на фразах около 2 с; хвост из 0,5 с нулей
 * менял отпечаток сильнее, чем голос), а с признаками 3D-Speaker — AUC 0,999 на тех же фразах
 * (results/2026-10-01-voices.md, эталон tools/voiceprint_ref.py). Прежняя калибровка порога (один
 * диктор 0,83–0,96, разные 0,13–0,30 на 11 записях) этого не поймала.
 */
public class Speaker {
  static final float MIN_SECONDS = 0.6f;

  final File dir; OrtSession sess; OrtEnvironment env;
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
      env = OrtEnvironment.getEnvironment();
      try (OrtSession.SessionOptions so = new OrtSession.SessionOptions()) {
        so.setIntraOpNumThreads(2); so.setInterOpNumThreads(1);
        sess = env.createSession(f[0].getAbsolutePath(), so);
      }
      loadMs = System.currentTimeMillis() - t; ready = true;
    } catch (Throwable t) { t.printStackTrace(); ready = false; }
  }

  /** Отпечаток единичной длины; null — модели нет, звук не 16 кГц или его меньше MIN_SECONDS.
   *  Хвоста тишины нет: нули в признаках с вычитанием среднего сдвигают отпечаток всей фразы. */
  public synchronized float[] embed(float[] samples, int sr) {
    if (!ready || sess == null || samples == null || sr != Fbank.SR || samples.length < MIN_SECONDS * sr) return null;
    float[][] f = Fbank.compute(samples);
    if (f.length == 0) return null;
    float[] flat = new float[f.length * Fbank.NMEL];
    for (int t = 0; t < f.length; t++) System.arraycopy(f[t], 0, flat, t * Fbank.NMEL, Fbank.NMEL);
    try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), new long[]{1, f.length, Fbank.NMEL});
         OrtSession.Result r = sess.run(Collections.singletonMap("x", x))) {
      FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer(); float[] e = new float[fb.remaining()]; fb.get(e);
      return Voices.unit(e);
    } catch (Throwable t) { t.printStackTrace(); return null; }
  }

  static float[] unit(float[] v) { return Voices.unit(v); }
  static float cos(float[] a, float[] b) { return Voices.cos(a, b); }

  /** Отдать модель отпечатка: без этого она оставалась в нативной памяти после остановки сервиса. */
  public synchronized void release() { ready = false; if (sess != null) try { sess.close(); } catch (Throwable ignore) {} sess = null; }

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
