package dev.agenttranslator;

import java.util.List;
import java.util.Locale;

/** Голосовое из мессенджера (владелец 15.10: «подхватывать из мессенджеров голосовые сообщения и распознавать их,
 *  и после написания ответа скопировать текст и отправить обратно»). Решения владельца: голосовое ложится в
 *  открытый разговор репликой собеседника с пометкой 🎙; «Скопировать перевод» и «Отправить перевод…» — только в
 *  меню реплики.
 *
 *  Здесь — то, что не зависит от Android и проверяется на столе (bench/apk/test/VoiceMsgTest.java): склейка
 *  распознанных кусков, длительность словами, расширение временного файла, кто прислал (referrer), куда
 *  отправлять ответ. Звук, нарезка и распознавание — в TranslatorService.voiceMessage, приём — ShareActivity. */
final class VoiceMsg {
  private VoiceMsg() {}

  /** Длиннее — не берём: распознавание идёт кусками по 15 с, и четверть часа — уже не голосовое, а запись. */
  static final int MAX_SECONDS = 15 * 60;
  /** Нарезка голосового: подпор и выдержка короче, чем у слушания комнаты, — запись близкая и чистая, а паузы
   *  между предложениями в голосовом короткие; предел куска — как у слушания (15 с, столько держит распознаватель). */
  static final int PRE_ROLL_MS = 300, HANG_MS = 700, TAIL_MS = 250, MIN_SPEECH_MS = 200, MAX_SPEECH_MS = 15000;
  /** Мессенджеры, из которых приходят голосовые: им ответ уходит сразу, без общего списка приложений. */
  static final String[] MESSENGERS = {"com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
      "org.thunderdog.challegram", "com.viber.voip", "com.facebook.orca", "com.instagram.android", "com.vkontakte.android", "ru.oneme.app"};

  /** Распознанные куски — одним текстом: пустые выкидываются, между кусками — пробел; кусок без знака конца
   *  предложения перед следующим с прописной получает точку (распознаватель ставит знаки внутри куска, а стык
   *  кусков — граница паузы, то есть почти всегда граница фразы). */
  static String join(List<String> parts) {
    StringBuilder b = new StringBuilder();
    for (String p0 : parts) {
      String p = p0 == null ? "" : p0.trim();
      if (p.isEmpty()) continue;
      if (b.length() > 0) {
        char last = b.charAt(b.length() - 1);
        if (Character.isLetterOrDigit(last) && Character.isUpperCase(p.codePointAt(0))) b.append('.');
        b.append(' ');
      }
      b.append(p);
    }
    return b.toString();
  }

  /** «0:42», «3:05», «1:02:09». */
  static String duration(double seconds) {
    long s = Math.max(0, Math.round(seconds));
    return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
  }

  /** Расширение временной копии — по типу или имени: кодек узнаёт формат по содержимому, а WAV читается своим
   *  разбором только с «.wav» (ClipCodec.decode). */
  static String ext(String mime, String name) {
    String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
    int dot = n.lastIndexOf('.');
    if (dot >= 0 && n.length() - dot <= 5 && n.length() - dot >= 3) return n.substring(dot);
    String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
    if (m.contains("wav")) return ".wav";
    if (m.contains("mpeg") || m.contains("mp3")) return ".mp3";
    if (m.startsWith("video/")) return m.contains("webm") ? ".webm" : m.contains("3gp") ? ".3gp" : ".mp4";   // «кружочек» — его звук
    if (m.contains("mp4") || m.contains("m4a") || m.contains("aac")) return ".m4a";
    if (m.contains("amr")) return ".amr";
    if (m.contains("webm")) return ".webm";
    return ".ogg";                                   // WhatsApp и Telegram: Opus в Ogg
  }

  /** Пакет из referrer активности: «android-app://com.whatsapp» → «com.whatsapp»; иначе null. */
  static String fromReferrer(String referrer) {
    if (referrer == null || !referrer.startsWith("android-app://")) return null;
    String p = referrer.substring("android-app://".length());
    int slash = p.indexOf('/'); if (slash >= 0) p = p.substring(0, slash);
    return p.matches("[A-Za-z][\\w]*(\\.[A-Za-z][\\w]*)+") ? p : null;
  }

  /** Пакет по хозяину content-URI присланного файла: «com.whatsapp.provider.media» → «com.whatsapp»,
   *  «org.telegram.messenger.provider» → «org.telegram.messenger». Нужен, когда referrer пуст: «Поделиться» через
   *  системный список referrer не передаёт (Redmi, 15.10 — пять голосовых из мессенджера пришли без отправителя).
   *  Длинные имена — первыми: «com.whatsapp.w4b…» — это WhatsApp Business, а не WhatsApp. */
  static String fromAuthority(String authority) {
    if (authority == null) return null;
    String best = null;
    for (String m : MESSENGERS)
      if ((authority.equals(m) || authority.startsWith(m + ".")) && (best == null || m.length() > best.length())) best = m;
    return best;
  }

  static boolean messenger(String pkg) {
    if (pkg == null) return false;
    for (String m : MESSENGERS) if (m.equals(pkg)) return true;
    return false;
  }

  /** Куда «Отправить перевод…»: мессенджер, из которого пришло голосовое этого разговора, — сразу ему;
   *  иначе null — общий список приложений. srcChat — разговор, куда легло последнее голосовое; chat — открытый. */
  static String replyTo(String srcPkg, long srcChat, long chat) {
    return messenger(srcPkg) && srcChat == chat ? srcPkg : null;
  }
}
