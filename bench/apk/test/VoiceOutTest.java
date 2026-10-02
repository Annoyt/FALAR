package dev.agenttranslator;

import android.media.AudioDeviceInfo;
import java.util.*;

/** Настольные тесты выбора «Что озвучивать». Запуск: bash bench/apk/test.sh. */
public class VoiceOutTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }

  public static int run() {
    fails = 0; checks = 0;
    // «Авто»: в наушниках только русский — португальский в ушах владельца собеседнику бесполезен
    ok(VoiceOut.voice(VoiceOut.AUTO, "ru", true), "V1 авто, наушники: русский звучит");
    ok(!VoiceOut.voice(VoiceOut.AUTO, "pt", true), "V1 авто, наушники: португальский молчит");
    ok(VoiceOut.voice(VoiceOut.AUTO, "ru", false), "V1 авто, динамик: русский звучит");
    ok(VoiceOut.voice(VoiceOut.AUTO, "pt", false), "V1 авто, динамик: португальский звучит");
    // ручной выбор — от вывода не зависит
    for (boolean hp : new boolean[]{true, false}) {
      String w = hp ? "наушники" : "динамик";
      ok(VoiceOut.voice(VoiceOut.RU, "ru", hp) && !VoiceOut.voice(VoiceOut.RU, "pt", hp), "V2 RU, " + w + ": только русский");
      ok(VoiceOut.voice(VoiceOut.PT, "pt", hp) && !VoiceOut.voice(VoiceOut.PT, "ru", hp), "V2 PT, " + w + ": только португальский");
      ok(VoiceOut.voice(VoiceOut.BOTH, "pt", hp) && VoiceOut.voice(VoiceOut.BOTH, "ru", hp), "V2 оба, " + w + ": оба");
    }
    ok(!VoiceOut.voice(VoiceOut.RU, "xx", false) && !VoiceOut.voice(VoiceOut.PT, null, false), "V2 непонятный язык при ручном выборе — молчит");
    // наушники — куда Android уводит медиазвук; SCO и динамик — нет
    for (int t : new int[]{AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET}) ok(VoiceOut.headphones(t), "V3 наушники: тип " + t);
    for (int t : new int[]{AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_UNKNOWN}) ok(!VoiceOut.headphones(t), "V3 не наушники: тип " + t);
    // настройка и стенд
    for (int i = 0; i < VoiceOut.KEYS.length; i++) eq(VoiceOut.parse(VoiceOut.KEYS[i]), i, "V4 ключ " + VoiceOut.KEYS[i]);
    eq(VoiceOut.parse("громко"), VoiceOut.AUTO, "V4 неизвестное — авто");
    eq(VoiceOut.parse(null), VoiceOut.AUTO, "V4 пусто — авто");
    eq(VoiceOut.LABELS.length, VoiceOut.KEYS.length, "V4 подписей столько же, сколько значений");
    eq(Arrays.asList(VoiceOut.LABELS), Arrays.asList("Авто", "RU", "PT", "Оба"), "V4 подписи сегмента");
    // слова в журнале
    ok(VoiceOut.describe(VoiceOut.AUTO).contains("в наушниках только русский"), "V5 авто — словами");
    ok(VoiceOut.describe(VoiceOut.RU).contains("только русский") && VoiceOut.describe(VoiceOut.PT).contains("только португальский")
        && VoiceOut.describe(VoiceOut.BOTH).contains("оба"), "V5 ручной выбор — словами");
    ok(VoiceOut.silentWhy(VoiceOut.AUTO, "pt", true).contains("собеседник не услышит"), "V6 авто: почему молчит");
    ok(VoiceOut.silentWhy(VoiceOut.RU, "pt", false).contains("только русский"), "V6 RU: почему молчит португальский");
    ok(VoiceOut.silentWhy(VoiceOut.PT, "ru", false).contains("только португальский"), "V6 PT: почему молчит русский");
    System.out.println(fails == 0 ? "VoiceOut: " + checks + " проверок, все прошли" : "VoiceOut: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
