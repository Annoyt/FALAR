package dev.agenttranslator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

/** Лицензии (Licenses, экран «Лицензии» в «О приложении»).
 *  Л1 — у каждого файла models/manifest.json (кроме своего, license=project) есть строка с автором и лицензией;
 *  Л2 — у каждой библиотеки APK (bench/apk/jni/arm64-v8a, если каталог есть) тоже;
 *  Л3 — тексты лицензий дословны: Apache 2.0, MIT (без чужой строки авторских прав);
 *  Л4 — текст экрана называет каждую часть, автора, адрес, строки авторских прав MIT, исходники моста, лицензии CC,
 *       озвучку телефона — и ни GPL, ни eSpeak, ни Piper (с 0.38.0);
 *  Л5 — build.sh кладёт тексты в APK и берёт мост без синтеза речи (tools/sherpa-jni/build-notts.sh) той версии
 *       sherpa-onnx, что названа на экране; GPL-текста среди лицензий больше нет.
 *  Запуск: bash bench/apk/test.sh. */
public class LicensesTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static String read(File f) {
    try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
  }

  public static void main(String[] a) throws Exception {
    File root = new File(a[0]), apk = new File(root, "bench/apk");
    JSONObject m = new JSONObject(read(new File(root, "models/manifest.json")));
    int files = 0;
    for (String key : new String[]{"files", "archives"}) {
      JSONArray arr = m.getJSONArray(key);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject e = arr.getJSONObject(i);
        String path = e.getString("path"), lic = e.optString("license");
        if (Licenses.own(lic)) continue;
        files++;
        ok(!Licenses.of(path).isEmpty(), "Л1 у файла модели " + path + " (" + lic + ") нет строки в Licenses");
      }
    }
    ok(files > 15, "Л1 манифест прочитан: файлов " + files);

    File jni = a.length > 1 ? new File(a[1]) : null;
    if (jni != null && jni.isDirectory()) {
      File[] so = jni.listFiles((d, n) -> n.endsWith(".so"));
      ok(so != null && so.length > 3, "Л2 библиотеки найдены: " + (so == null ? 0 : so.length));
      if (so != null) for (File f : so) ok(!Licenses.of("lib:" + f.getName()).isEmpty(), "Л2 у библиотеки " + f.getName() + " нет строки в Licenses");
    } else {
      System.out.println("  (Л2 пропущена: нет каталога библиотек " + jni + ")");
    }

    Function<String, String> asset = name -> read(new File(apk, name));
    String apache = asset.apply("licenses/Apache-2.0.txt"), mit = asset.apply("licenses/MIT.txt");
    ok(!new File(apk, "licenses/GPL-3.0.txt").exists(), "Л3 текста GPL среди лицензий нет: частей под GPL в APK нет");
    ok(apache.contains("Apache License") && apache.contains("Version 2.0, January 2004") && apache.contains("END OF TERMS AND CONDITIONS"),
        "Л3 Apache 2.0 — полный текст");
    ok(mit.trim().startsWith("Permission is hereby granted") && mit.contains("THE SOFTWARE IS PROVIDED \"AS IS\"") && !mit.contains("Copyright"),
        "Л3 MIT — текст без чужой строки авторских прав");

    String t = Licenses.text(asset), tq = Licenses.text(asset, true), tm = Licenses.text(asset, false, true);
    for (Licenses.Part p : Licenses.PARTS) {
      String own = p.mtk ? tm : tq;                    // своя сборка части: MediaTek (MTK=1) или Snapdragon (NPU=1)
      ok(own.contains(p.what) && own.contains(p.who) && own.contains(p.url), "Л4 часть в тексте своей сборки: " + p.what);
      if (!p.npu && !p.mtk) ok(t.contains(p.what) && t.contains(p.who) && t.contains(p.url), "Л4 часть в тексте обычной сборки: " + p.what);
      if (Licenses.MIT.equals(p.license)) ok(!p.copyright.isEmpty() && own.contains(p.copyright), "Л4 строка авторских прав MIT: " + p.what);
    }
    ok(!t.contains("Qualcomm") && !t.contains("nemo128") && !t.contains("NeuroPilot"), "Л4 в обычной сборке нет частей NPU");
    ok(!tm.contains("Qualcomm AI Stack License") && tm.contains("NeuroPilot") && !tq.contains("NeuroPilot"),
        "Л4 сборка MediaTek — без Qualcomm, сборка Snapdragon — без NeuroPilot");
    ok(tq.contains("Qualcomm AI Stack License") && tq.contains("licenses/qualcomm") == false, "Л4 в сборке с NPU — лицензия Qualcomm");
    ok(t.contains(Licenses.SHERPA_SRC) && t.contains(Licenses.FALAR_PATCH), "Л4 исходники моста sherpa-onnx и правки Falar названы");
    for (String x : new String[]{t, tq, tm})
      ok(!x.contains("GPL") && !x.contains("eSpeak") && !x.contains("Piper") && !x.contains("CC0"), "Л4 ни GPL, ни eSpeak, ни Piper на экране");
    ok(t.contains("синтез речи телефона"), "Л4 озвучка — синтез телефона, названа");
    ok(t.contains("creativecommons.org/licenses/by/4.0") && t.contains("creativecommons.org/licenses/by/2.0/fr"), "Л4 ссылки на CC BY");
    ok(t.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION") && t.contains("Permission is hereby granted"), "Л4 тексты лицензий в экране");

    String build = read(new File(apk, "build.sh"));
    ok(build.contains("licenses"), "Л5 build.sh кладёт тексты лицензий в APK");
    ok(build.contains("falar-jni/notts/libsherpa-onnx-jni.so") && build.contains("JNI_NOTTS_SHA="), "Л5 build.sh берёт мост без синтеза речи по sha256");
    String jniReadme = read(new File(root, "tools/sherpa-jni/README.md")), notts = read(new File(root, "tools/sherpa-jni/build-notts.sh"));
    ok(jniReadme.contains("--branch v1.13.8") && Licenses.SHERPA_SRC.endsWith("v1.13.8") && notts.contains("sherpa-onnx-1.13.8")
        && notts.contains("SHERPA_ONNX_ENABLE_TTS=OFF"), "Л5 мост JNI собран из той же версии sherpa-onnx, что названа на экране, и без синтеза речи");

    System.out.println(fails == 0 ? "Licenses: " + checks + " проверок, все прошли" : "Licenses: провалов " + fails + " из " + checks);
    System.exit(fails == 0 ? 0 : 1);
  }
}
