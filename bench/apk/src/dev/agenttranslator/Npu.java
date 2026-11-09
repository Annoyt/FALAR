package dev.agenttranslator;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Распознавание на NPU Snapdragon — где оно возможно и с какими параметрами QNN (NpuAsr — само распознавание).
 *
 *  Владелец 09.11: переключатель в «Экспериментальном», по умолчанию включён там, где NPU есть. Нужны три вещи:
 *  чип Qualcomm, библиотеки QNN в APK (сборка NPU=1 bench/apk/build.sh — лицензия Qualcomm AI Stack, Licenses) и
 *  модель модуля npu (encoder-npu.onnx, nemo128.onnx). Чего-то нет — причина словами для «Экспериментального», и
 *  распознаёт процессор, как раньше. Графа QNN под поколение NPU заранее нет: собирается на телефоне при первом
 *  запуске и сохраняется (NpuAsr, ctx). */
final class Npu {
  static final String PREF = "npu";
  static final String MODEL = "npu/encoder-npu.onnx", PRE = "npu/nemo128.onnx";

  /** Чип Qualcomm: с Android 12 — Build.SOC_MANUFACTURER, раньше — по Build.HARDWARE / BOARD («qcom», «sm8350»…). */
  static boolean snapdragon(int sdk, String socManufacturer, String hardware, String board) {
    if (sdk >= 31 && socManufacturer != null && !socManufacturer.isEmpty()) return socManufacturer.equalsIgnoreCase("QTI");
    String h = (hardware == null ? "" : hardware) + " " + (board == null ? "" : board);
    return h.toLowerCase(java.util.Locale.ROOT).matches(".*\\b(qcom|sm\\d{4}|sdm\\d{3,4}|lahaina|taro|kalama|pineapple|sun|canoe)\\b.*");
  }

  static boolean snapdragon() {
    String soc = android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MANUFACTURER : "";
    return snapdragon(android.os.Build.VERSION.SDK_INT, soc, android.os.Build.HARDWARE, android.os.Build.BOARD);
  }

  /** Почему NPU сейчас нельзя (надпись L.k), или null. */
  static String unavailable(boolean snapdragon, File libDir, File models) {
    if (!snapdragon) return L.k("не Snapdragon");
    if (!new File(libDir, "libQnnHtp.so").exists()) return L.k("в этой сборке нет библиотек NPU");
    if (!new File(models, MODEL).exists() || !new File(models, PRE).exists()) return L.k("нет модели NPU");
    return null;
  }

  /** Параметры QNN для ONNX Runtime: HTP из каталога библиотек приложения, «рывок» скорости на время распознавания
   *  фразы (как в замерах AI Hub), сборка графа с полной оптимизацией. */
  static Map<String, String> qnnOptions(File libDir) {
    Map<String, String> o = new HashMap<>();
    o.put("backend_path", new File(libDir, "libQnnHtp.so").getPath());
    o.put("htp_performance_mode", "burst");
    o.put("htp_graph_finalization_optimization_mode", "3");
    return o;
  }

  /** Части HTP (skel) грузит DSP по ADSP_LIBRARY_PATH — каталог библиотек приложения (extractNativeLibs=true). */
  static void adspPath(File libDir) {
    try { android.system.Os.setenv("ADSP_LIBRARY_PATH", libDir.getPath(), true); } catch (Exception ignored) {}
  }
}
