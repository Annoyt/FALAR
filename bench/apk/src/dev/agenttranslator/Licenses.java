package dev.agenttranslator;

import java.util.function.Function;

/** Авторы и лицензии частей Falar — экран «Лицензии» в «О приложении».
 *
 *  Лицензии чужих частей требуют показать их тому, кто получил приложение:
 *  CC BY (parakeet, OPUS-MT, Tatoeba) — автора, ссылку на лицензию и что изменено; MIT — строку авторских прав
 *  и текст; Apache-2.0 — текст. Тексты лицензий — дословно, в assets/licenses (из bench/apk/licenses: Apache —
 *  apache.org, MIT — LICENSE ONNX Runtime без строки авторских прав). LicensesTest сверяет таблицу с
 *  models/manifest.json и с библиотеками APK: у каждого файла модели и у каждой .so есть строка здесь.
 *
 *  С 0.38.0 частей под GPL нет: озвучка — синтез речи телефона (SysTts), мост sherpa-onnx собран без синтеза
 *  (tools/sherpa-jni/build-notts.sh) — ни eSpeak NG, ни голосов Piper в APK. */
final class Licenses {
  static final String MIT = "MIT", APACHE = "Apache-2.0", CC_BY_4 = "CC-BY-4.0",
      CC_BY_2_FR = "CC-BY-2.0-FR", QUALCOMM = "Qualcomm AI Stack License", NONE = "none";

  /** Часть приложения. role и changed — надписи (L.k, переводятся при показе); files — пути models/manifest.json
   *  (начало пути) и библиотеки APK («lib:» и начало имени), которые эта часть объясняет. */
  static final class Part {
    final String role, what, who, license, url, changed, copyright;
    final String[] files;
    /** Только в сборке с NPU (библиотеки QNN в APK, модуль npu): в обычной этой части нет и строки тоже. */
    boolean npu;
    /** Только в сборке с NPU MediaTek (build.sh MTK=1: дополнение NeuroPilot в APK, модель encoder-mtk). */
    boolean mtk;
    Part(String role, String what, String who, String license, String url, String changed, String copyright, String... files) {
      this.role = role; this.what = what; this.who = who; this.license = license; this.url = url;
      this.changed = changed; this.copyright = copyright; this.files = files;
    }
    Part npu() { npu = true; return this; }
    Part mtk() { mtk = true; return this; }
    /** Показывать ли в этой сборке. */
    boolean in(boolean qnn, boolean withMtk) { return (!npu && !mtk) || (npu && qnn) || (mtk && withMtk); }
  }

  static final String SHERPA_SRC = "https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8",
      FALAR_PATCH = "https://github.com/Annoyt/FALAR/tree/main/tools/sherpa-jni";

  static final Part[] PARTS = {
    new Part(L.k("Распознавание речи"), "parakeet-tdt-0.6b-v3", "NVIDIA", CC_BY_4,
        "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3", L.k("переведено в ONNX и сжато до int8 (sherpa-onnx)"), "",
        "asr_multi/"),
    new Part(L.k("Перевод"), "opus-mt-tc-big-pt-zle, opus-mt-tc-big-zle-pt", "Helsinki-NLP, University of Helsinki", CC_BY_4,
        "https://huggingface.co/Helsinki-NLP", L.k("переведено в ONNX"), "",
        "mt/"),
    new Part(L.k("Поиск речи"), "Silero VAD", "Silero Team", MIT,
        "https://github.com/snakers4/silero-vad", "", "Copyright (c) 2020-present Silero Team",
        "silero_vad.onnx"),
    new Part(L.k("Шумоподавление"), "GTCRN", "Rong Xiaobin", MIT,
        "https://github.com/Xiaobin-Rong/gtcrn", L.k("переведено в ONNX (sherpa-onnx)"), "Copyright (c) 2024 Rong Xiaobin",
        "denoiser/"),
    new Part(L.k("Отпечаток голоса"), "CAM++ (speech_campplus_sv_en_voxceleb_16k)", "3D-Speaker, Alibaba", APACHE,
        "https://github.com/modelscope/3D-Speaker", L.k("переведено в ONNX (sherpa-onnx)"), "",
        "speaker/"),
    new Part(L.k("Уточнение перевода"), "Hy-MT2-1.8B", "Tencent", APACHE,
        "https://huggingface.co/tencent/Hy-MT2-1.8B", "", "",
        "llm/"),
    new Part(L.k("Чтение снимков"), "PP-OCRv6 det, PP-OCRv5 latin rec", "PaddlePaddle", APACHE,
        "https://github.com/PaddlePaddle/PaddleOCR", "", "",
        "ocr/"),
    new Part(L.k("Чтение снимков"), "RapidOCR", "RapidAI", APACHE,
        "https://github.com/RapidAI/RapidOCR", L.k("переведено в ONNX"), "",
        "ocr/rec"),
    new Part(L.k("Фразы и словарь снимков"), "Tatoeba", "Tatoeba contributors", CC_BY_2_FR,
        "https://tatoeba.org", L.k("отобраны фразы и слова"), "",
        "phrasebook_tatoeba", "common_words"),
    new Part(L.k("Запуск моделей"), "ONNX Runtime", "Microsoft", MIT,
        "https://github.com/microsoft/onnxruntime", "", "Copyright (c) Microsoft Corporation",
        "lib:libonnxruntime"),
    new Part(L.k("Распознавание и отпечаток голоса"), "sherpa-onnx v1.13.8 (OpenFst, kaldi-native-fbank …)",
        "Xiaomi Corporation, k2-fsa", APACHE,
        SHERPA_SRC, L.k("добавлена уверенность распознавателя, собрано без синтеза речи"), "",
        "lib:libsherpa-onnx-jni"),
    new Part(L.k("Уточнение перевода"), "llama.cpp, ggml", "The ggml authors", MIT,
        "https://github.com/ggml-org/llama.cpp", "", "Copyright (c) 2023-2026 The ggml authors",
        "lib:libllama", "lib:libggml", "lib:libmtmd"),
    new Part(L.k("Распознавание на NPU"), "parakeet-tdt-0.6b-v3 (NPU, w8a16)", "NVIDIA", CC_BY_4,
        "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3", L.k("квантовано для NPU (Qualcomm AI Hub, правки Falar)"), "",
        "npu/encoder-npu").npu(),
    new Part(L.k("Распознавание на NPU"), "nemo128.onnx", "Ilya Stupakov (onnx-asr)", MIT,
        "https://github.com/istupakov/onnx-asr", "", "Copyright (c) 2025 Ilya Stupakov",
        "npu/nemo128").npu(),
    new Part(L.k("Распознавание на NPU"), "Qualcomm AI Runtime (QNN) 2.42", "Qualcomm Technologies, Inc.", QUALCOMM,
        "https://softwarecenter.qualcomm.com/catalog/item/Qualcomm_AI_Runtime_Community", "", "",
        "lib:libQnn").npu(),
    // MediaTek (ветка mtk-npu): своя модель A16W8 и своя сборка дополнения ORT для NeuroPilot с правками для int16
    new Part(L.k("Распознавание на NPU"), "parakeet-tdt-0.6b-v3 (NPU MediaTek, A16W8)", "NVIDIA", CC_BY_4,
        "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3", L.k("квантовано для NPU MediaTek (правки Falar)"), "",
        "npu/encoder-mtk").mtk(),
    new Part(L.k("Распознавание на NPU"), "nemo128.onnx", "Ilya Stupakov (onnx-asr)", MIT,
        "https://github.com/istupakov/onnx-asr", "", "Copyright (c) 2025 Ilya Stupakov").mtk(),
    new Part(L.k("Распознавание на NPU"), "ONNX Runtime NeuroPilot EP v0.3.0", "Microsoft", MIT,
        "https://github.com/onnxruntime/onnxruntime-ep-neuropilot", L.k("правки Falar: 16-битные активации"),
        "Copyright (c) Microsoft Corporation", "lib:libonnxruntime_providers_neuropilot").mtk(),
    new Part(L.k("Распознавание на NPU"), "libneuron_op_capability.so (из выпуска ONNX Runtime NeuroPilot EP)", "MediaTek / Microsoft", NONE,
        "https://github.com/onnxruntime/onnxruntime-ep-neuropilot/releases/tag/v0.3.0", "", "",
        "lib:libneuron_op_capability").mtk(),
  };

  /** Своё (models/manifest.json: license=project) — без строки. */
  static boolean own(String license) { return "project".equals(license); }

  /** Чья строка объясняет файл манифеста или библиотеку APK (lib:имя.so). */
  static java.util.List<Part> of(String file) {
    java.util.List<Part> out = new java.util.ArrayList<>();
    for (Part p : PARTS) for (String f : p.files) if (file.startsWith(f)) { out.add(p); break; }
    return out;
  }

  static String licenseName(String id) {
    switch (id) {
      case CC_BY_4: return "CC BY 4.0";
      case CC_BY_2_FR: return "CC BY 2.0 FR";
      case APACHE: return "Apache 2.0";
      case NONE: return L.t("лицензия не указана");
      default: return id;
    }
  }

  /** Весь текст экрана: части, озвучка телефона, ссылки на лицензии CC и исходники моста, затем тексты MIT и Apache 2.0.
   *  asset — чтение файла из assets (в тесте — из bench/apk). */
  static String text(Function<String, String> asset) { return text(asset, false); }

  /** qnn — сборка с библиотеками Qualcomm (NPU): тогда ещё части NPU, лицензия Qualcomm AI Stack и их NOTICE
   *  (assets/licenses/qualcomm — build.sh NPU=1 кладёт их из скачанного QAIRT SDK, в репозиторий они не идут). */
  static String text(Function<String, String> asset, boolean qnn) { return text(asset, qnn, false); }

  /** mtk — сборка с дополнением NeuroPilot (NPU MediaTek): ещё его части. */
  static String text(Function<String, String> asset, boolean qnn, boolean mtk) {
    StringBuilder b = new StringBuilder();
    b.append(L.t("Чужие модели и библиотеки в Falar и их лицензии:"));
    for (Part p : PARTS) {
      if (!p.in(qnn, mtk)) continue;
      b.append("\n\n• ").append(L.t(p.role)).append(": ").append(p.what).append(" — ").append(p.who).append(". ")
          .append(licenseName(p.license)).append('.');
      if (!p.changed.isEmpty()) b.append(' ').append(L.f("Изменено: %s.", L.t(p.changed)));
      b.append('\n').append(p.url);
    }
    b.append("\n\n• ").append(L.t("Озвучка: синтез речи телефона (Android). Движок и голоса ставят производитель телефона или Google, в Falar они не входят."));
    b.append("\n\n").append(L.t("Лицензии Creative Commons:"))
        .append("\nCC BY 4.0 — https://creativecommons.org/licenses/by/4.0/")
        .append("\nCC BY 2.0 FR — https://creativecommons.org/licenses/by/2.0/fr/");
    b.append("\n\n").append(L.t("sherpa-onnx и правка Falar:")).append('\n').append(SHERPA_SRC).append('\n').append(FALAR_PATCH);
    b.append("\n\n———— MIT License ————\n");
    for (Part p : PARTS) if (MIT.equals(p.license) && p.in(qnn, mtk)) b.append('\n').append(p.copyright).append(" — ").append(p.what);
    b.append("\n\n").append(asset.apply("licenses/MIT.txt").trim());
    b.append("\n\n———— Apache License 2.0 ————\n\n").append(asset.apply("licenses/Apache-2.0.txt").trim());
    if (qnn) {
      b.append("\n\n———— Qualcomm AI Stack License ————\n\n").append(asset.apply("licenses/qualcomm/AI-Stack-License.txt").trim());
      b.append("\n\n———— Qualcomm AI Runtime: NOTICE ————\n\n").append(asset.apply("licenses/qualcomm/NOTICE.txt").trim());
    }
    return b.toString();
  }
}
