package dev.agenttranslator;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Распознавание на NPU — где оно возможно и с какими параметрами (NpuAsr — само распознавание): Snapdragon — QNN,
 *  MediaTek — дополнение ONNX Runtime для NeuroPilot (своя сборка с int16, bench/npu/neuropilot-ep; ветка mtk-npu).
 *
 *  Владелец 09.11: переключатель в «Экспериментальном», по умолчанию включён там, где NPU есть. Нужны три вещи:
 *  чип Qualcomm, библиотеки QNN в APK (сборка NPU=1 bench/apk/build.sh — лицензия Qualcomm AI Stack, Licenses) и
 *  модель модуля npu (encoder-npu.onnx, nemo128.onnx). Чего-то нет — причина словами для «Экспериментального», и
 *  распознаёт процессор, как раньше. Графа QNN под поколение NPU заранее нет: собирается на телефоне при первом
 *  запуске и сохраняется (NpuAsr, ctx). */
final class Npu {
  static final String PREF = "npu";
  static final String MODEL = "npu/encoder-npu.onnx", PRE = "npu/nemo128.onnx";
  /** MediaTek: своя модель (A16W8 — веса int8, активации int16 симметрично, bench/npu/mtk_quant.py) и дополнение ORT. */
  static final String MODEL_MTK = "npu/encoder-mtk.onnx", MTK_EP = "libonnxruntime_providers_neuropilot.so",
      MTK_CAP = "libneuron_op_capability.so";
  enum Chip { SNAPDRAGON, MEDIATEK }
  /** Проверенные чипы MediaTek (Build.SOC_MODEL). На непроверенных дополнение может уронить весь процесс: Redmi Note 10
   *  Pro (MT6891, Neuron 5, 10.11) — SIGSEGV в libonnxruntime_providers_neuropilot.so при создании сессии (вызов
   *  функции, которой нет в старом адаптере). Попробовать на другом — стенд, --es npuany 1 (MTK_ANY). */
  static final java.util.Set<String> MTK_OK = new java.util.HashSet<>(java.util.Arrays.asList("MT6899"));
  static volatile boolean MTK_ANY = false;

  static boolean mtkVerified(String socModel) {
    return MTK_ANY || (socModel != null && MTK_OK.contains(socModel.toUpperCase(java.util.Locale.ROOT)));
  }

  /** Чип MediaTek: с Android 12 — Build.SOC_MANUFACTURER («Mediatek»), раньше — Build.HARDWARE / BOARD («mt6893»…). */
  static boolean mediatek(int sdk, String socManufacturer, String hardware, String board) {
    if (sdk >= 31 && socManufacturer != null && !socManufacturer.isEmpty()) return socManufacturer.equalsIgnoreCase("Mediatek");
    String h = (hardware == null ? "" : hardware) + " " + (board == null ? "" : board);
    return h.toLowerCase(java.util.Locale.ROOT).matches(".*\\bmt\\d{4}\\b.*");
  }

  static boolean mediatek() {
    String soc = android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MANUFACTURER : "";
    return mediatek(android.os.Build.VERSION.SDK_INT, soc, android.os.Build.HARDWARE, android.os.Build.BOARD);
  }

  /** Чей NPU в телефоне, или null — ни тот, ни другой. */
  static Chip chip() { return snapdragon() ? Chip.SNAPDRAGON : mediatek() ? Chip.MEDIATEK : null; }

  /** Модель кодировщика под чип (путь в каталоге моделей). */
  static String model(Chip c) { return c == Chip.MEDIATEK ? MODEL_MTK : MODEL; }

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
    return unavailable(snapdragon ? Chip.SNAPDRAGON : null, libDir, models);
  }

  static String unavailable(Chip c, File libDir, File models) {
    if (c == null) return L.k("не Snapdragon и не MediaTek");
    if (c == Chip.MEDIATEK && !mtkVerified(android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MODEL : ""))
      return L.k("этот чип MediaTek не проверен");
    boolean libs = c == Chip.MEDIATEK ? new File(libDir, MTK_EP).exists() && new File(libDir, MTK_CAP).exists()
        : new File(libDir, "libQnnHtp.so").exists();
    if (!libs) return L.k("в этой сборке нет библиотек NPU");
    if (!new File(models, model(c)).exists() || !new File(models, PRE).exists()) return L.k("нет модели NPU");
    return null;
  }

  /** Параметры дополнения NeuroPilot (POCO X7 Pro 10.11): расчёт fp32-частей в fp16; без пробной компиляции и частями
   *  графа по 160 МБ — иначе сборка одной частью 0,6 ГБ съедала память до 0,8 ГБ свободных; граф DLA — отдельными
   *  файлами рядом с ctx (ep.context_embed_mode=0: без лишней копии при загрузке). */
  static Map<String, String> neuronOptions(File libDir) {
    Map<String, String> o = new HashMap<>();
    o.put(NpuAsr.EP_LIBRARY, new File(libDir, MTK_EP).getPath());
    o.put("NEURON_FLAG_USE_FP16", "1");
    o.put("NEURON_FLAG_SKIP_CAPABILITY_PROBE", "1");
    o.put("NEURON_FLAG_MAX_PARTITION_BYTES", String.valueOf(160L << 20));
    o.put("ep.context_embed_mode", "0");
    return o;
  }

  /** Поколение HTP по модели чипа (Build.SOC_MODEL). На Galaxy A52s (778G, v68) без него QNN 2.42 не создаёт
   *  устройство (QNN_DEVICE_ERROR_INVALID_CONFIG, RTL 09.11), и ONNX Runtime молча считает граф процессором. "" —
   *  не знаем: тогда поколение определяет сам QNN (на S26 v81 так работало). */
  static String htpArch(String soc) {
    if (soc == null) return "";
    switch (soc.toUpperCase(java.util.Locale.ROOT)) {
      case "SM8350": case "SM7325": case "SM7350": return "68";
      case "SM8450": case "SM8475": case "SM7450": case "SM7475": return "69";
      case "SM8550": case "SM8635": case "SM7675": case "SM7550": case "SM7435": case "SM6450": case "SM7750": return "73";
      case "SM8650": return "75";
      case "SM8750": return "79";
      case "SM8850": case "SM8845": return "81";
      default: return "";
    }
  }

  /** Параметры QNN для ONNX Runtime: HTP из каталога библиотек приложения, «рывок» скорости на время распознавания
   *  фразы (как в замерах AI Hub), сборка графа с полной оптимизацией; поколение HTP — htpArch. */
  static Map<String, String> qnnOptions(File libDir) {
    Map<String, String> o = new HashMap<>();
    o.put("backend_path", new File(libDir, "libQnnHtp.so").getPath());
    String arch = htpArch(android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MODEL : "");
    if (!arch.isEmpty()) o.put("htp_arch", arch);
    o.put("htp_performance_mode", "burst");
    o.put("htp_graph_finalization_optimization_mode", "3");
    return o;
  }

  /** Части HTP (skel) грузит DSP по ADSP_LIBRARY_PATH — каталог библиотек приложения (extractNativeLibs=true). */
  static void adspPath(File libDir) {
    try { android.system.Os.setenv("ADSP_LIBRARY_PATH", libDir.getPath(), true); } catch (Exception ignored) {}
  }
}
