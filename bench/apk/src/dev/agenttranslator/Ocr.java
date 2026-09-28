package dev.agenttranslator;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.*;

/** Офлайн-распознавание текста на снимке: вывеска, меню, скрин переписки.
 *
 *  Зачем своё, если бесплатная модель OpenRouter картинки принимает: вывеска на улице — ровно то
 *  место, где сети нет, а скрин чужой переписки не должен уходить третьей стороне. Облако
 *  отвечало на один и тот же снимок за 10 и за 49 с. Облако остаётся запасным путём (долгое
 *  нажатие на 📷), офлайн — основным.
 *
 *  Модели (models/ocr, ONNX Runtime уже в сборке):
 *    det.onnx — детектор строк PP-OCRv6 small (DB), выход — карта вероятностей;
 *    rec.onnx — латинский распознаватель PP-OCRv5 mobile (CTC), словарь вшит в метаданные.
 *  Выбраны замером на 32 снимках вывесок (bench/ocr): 92–93 % слов буква в букву против 91 %
 *  у каждой модели в паре со «своим» распознавателем/детектором. Всё, что не модели, — в OcrCore
 *  и проверяется на столе против эталона tools/ocr_ref.py.
 *
 *  Сессии поднимаются на время чтения и закрываются: снимок — редкость, а память телефона
 *  занята речью, переводом и уточнителем.
 */
public class Ocr {
  final File dir;

  public Ocr(File modelsDir) { dir = new File(modelsDir, "ocr"); }

  /** Модели на месте. Проверяется каждый раз, а не при создании: ярус auto докачивает их в фоне
   *  уже после запуска. Файл появляется только после сверки хэша (загрузка идёт в .part). */
  public boolean ready() { return new File(dir, "det.onnx").isFile() && new File(dir, "rec.onnx").isFile(); }

  /** Прочитанный снимок: абзацы для перевода с прямоугольниками, строки как на вывеске, время. */
  public static final class Page {
    public final List<OcrCore.Para> paras = new ArrayList<>();
    public String lines = "";
    public int boxes;
    public long loadMs, detMs, recMs, layoutMs;
  }

  /** Текст снимка argb w×h. Бросает исключение, если модели не поднялись. */
  public Page read(int[] argb, int w, int h) throws Exception {
    Page pg = new Page();
    long t0 = System.nanoTime();
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    try (OrtSession.SessionOptions so = new OrtSession.SessionOptions()) {
      so.setIntraOpNumThreads(4); so.setInterOpNumThreads(1);
      try (OrtSession det = env.createSession(new File(dir, "det.onnx").getAbsolutePath(), so);
           OrtSession rec = env.createSession(new File(dir, "rec.onnx").getAbsolutePath(), so)) {
        long t1 = System.nanoTime(); pg.loadMs = (t1 - t0) / 1_000_000;
        // детектор
        int[] ds = OcrCore.detSize(w, h); int pw = ds[0], ph = ds[1];
        float[] prob;
        try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(OcrCore.detInput(argb, w, h, pw, ph)), new long[]{1, 3, ph, pw});
             OrtSession.Result r = det.run(Collections.singletonMap("x", x))) {
          FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer(); prob = new float[fb.remaining()]; fb.get(prob);
        }
        List<double[]> boxes = OcrCore.boxes(prob, pw, ph, w, h); pg.boxes = boxes.size();
        long t2 = System.nanoTime(); pg.detMs = (t2 - t1) / 1_000_000;
        // распознаватель: строки пачками по отношению сторон
        String meta = rec.getMetadata().getCustomMetadata().get("character");
        List<int[]> crops = new ArrayList<>(), shapes = new ArrayList<>();
        for (double[] b : boxes) { int[] s = new int[2]; crops.add(OcrCore.crop(argb, w, h, b, s)); shapes.add(s); }
        Integer[] order = OcrCore.recOrder(shapes);
        String[] texts = new String[boxes.size()]; float[] scores = new float[boxes.size()]; String[] chars = null;
        for (int b0 = 0; b0 < order.length; b0 += OcrCore.REC_BATCH) {
          int n = Math.min(OcrCore.REC_BATCH, order.length - b0);
          List<int[]> sh = new ArrayList<>(); for (int j = 0; j < n; j++) sh.add(shapes.get(order[b0 + j]));
          int W = OcrCore.recWidth(sh);
          float[] xr = new float[n * 3 * OcrCore.REC_H * W];
          for (int j = 0; j < n; j++) { int k = order[b0 + j]; OcrCore.recInput(crops.get(k), shapes.get(k)[0], shapes.get(k)[1], xr, j, W); }
          try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(xr), new long[]{n, 3, OcrCore.REC_H, W});
               OrtSession.Result r = rec.run(Collections.singletonMap("x", x))) {
            OnnxTensor out = (OnnxTensor) r.get(0); long[] shp = out.getInfo().getShape();
            int T = (int) shp[1], C = (int) shp[2];
            if (chars == null) chars = OcrCore.dict(meta == null ? "" : meta, C);
            FloatBuffer fb = out.getFloatBuffer(); float[] p = new float[fb.remaining()]; fb.get(p);
            float[] sc = new float[1];
            for (int j = 0; j < n; j++) { int k = order[b0 + j]; texts[k] = OcrCore.ctc(p, j * T * C, T, C, chars, sc); scores[k] = sc[0]; }
          }
        }
        long t3 = System.nanoTime(); pg.recMs = (t3 - t2) / 1_000_000;
        List<OcrCore.Item> items = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++)
          if (scores[i] >= OcrCore.MIN_SCORE && texts[i] != null && !texts[i].trim().isEmpty()) items.add(new OcrCore.Item(boxes.get(i), texts[i]));
        List<List<OcrCore.Row>> blocks = OcrCore.layout(items);
        for (List<OcrCore.Row> bl : blocks) pg.paras.addAll(OcrCore.paragraphs(bl));
        pg.lines = OcrCore.lines(blocks);
        pg.layoutMs = (System.nanoTime() - t3) / 1_000_000;
      }
    }
    return pg;
  }
}
