package dev.agenttranslator;

import android.media.AudioDeviceInfo;

/** Что озвучивать: русский перевод (владельцу), португальский (собеседнику) или оба.
 *  Куда звучит — решает не приложение: пока подключены наушники, Android отправляет в них весь
 *  медиазвук, и вывести португальский отдельно в динамик нельзя ни одним штатным способом;
 *  развести языки по ушам с проверенной парой тоже не вышло (results/2026-09-13-headphones.md).
 *  Поэтому выбор — какой язык звучит, а не где. «Авто»: в наушниках — только русский
 *  (португальский в ваших наушниках собеседник не услышит, он читает крупный текст на экране),
 *  через динамик — оба. Отдельно от службы, чтобы правило проверялось на столе. */
final class VoiceOut {
  static final int AUTO = 0, RU = 1, PT = 2, BOTH = 3;
  /** Подписи сегмента в настройках — коротко, как у «Слушать PT | RU». */
  static final String[] LABELS = {"Авто", "RU", "PT", "Оба"};
  /** Значение в настройках и в стенде (--es voicewhat auto|ru|pt|both). */
  static final String[] KEYS = {"auto", "ru", "pt", "both"};

  /** Озвучивать ли перевод на язык tgt («ru» / «pt») при этом выборе и этом выводе. */
  static boolean voice(int mode, String tgt, boolean headphones) {
    switch (mode) {
      case RU: return "ru".equals(tgt);
      case PT: return "pt".equals(tgt);
      case BOTH: return true;
      default: return !headphones || "ru".equals(tgt);
    }
  }

  /** Вывод в наушники, куда Android уводит весь медиазвук: Bluetooth A2DP и LE Audio, провод, USB.
   *  Гарнитура только по SCO сюда не входит: медиа по ней не идёт, звук остаётся в динамике. */
  static boolean headphones(int deviceType) {
    switch (deviceType) {
      case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: case AudioDeviceInfo.TYPE_BLE_HEADSET:
      case AudioDeviceInfo.TYPE_WIRED_HEADSET: case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
      case AudioDeviceInfo.TYPE_USB_HEADSET:
        return true;
      default: return false;
    }
  }

  /** «ru» → RU; неизвестное — «Авто». */
  static int parse(String key) {
    for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
    return AUTO;
  }

  /** Строка журнала о выборе — словами. */
  static String describe(int mode) {
    switch (mode) {
      case RU: return "🔈 озвучиваю только русский перевод";
      case PT: return "🔈 озвучиваю только португальский перевод";
      case BOTH: return "🔈 озвучиваю оба языка";
      default: return "🔈 озвучка — авто: в наушниках только русский, через динамик оба языка";
    }
  }

  /** Почему перевод не прозвучал — приписка к строке реплики в журнале. */
  static String silentWhy(int mode, String tgt, boolean headphones) {
    if (mode == AUTO) return "без озвучки: португальский в ваших наушниках собеседник не услышит";
    return "без озвучки: выбрано озвучивать только " + (mode == RU ? "русский" : "португальский");
  }
}
