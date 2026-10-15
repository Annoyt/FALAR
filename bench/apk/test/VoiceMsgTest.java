package dev.agenttranslator;

import java.util.*;

/** Голосовое из мессенджера (VoiceMsg): склейка кусков, длительность, расширение копии, отправитель по referrer,
 *  куда «Отправить перевод…»; пометка реплики 🎙 в разговоре (Chats.VOICE). Запуск: bash bench/apk/test.sh. */
public class VoiceMsgTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }

  public static int run() throws Exception {
    fails = 0; checks = 0;
    // V1 склейка: пустые куски выпадают, стык без знака перед прописной — точка, перед строчной — пробел
    eq(VoiceMsg.join(Arrays.asList("Oi, tudo bem?", "", " Amanhã eu posso  ", null, "às dez")), "Oi, tudo bem? Amanhã eu posso às dez", "V1 знаки внутри кусков сохраняются");
    eq(VoiceMsg.join(Arrays.asList("Oi tudo bem", "Amanhã eu posso")), "Oi tudo bem. Amanhã eu posso", "V1 стык перед прописной — точка");
    eq(VoiceMsg.join(Arrays.asList("Привет", "как дела")), "Привет как дела", "V1 стык перед строчной — без точки");
    eq(VoiceMsg.join(Arrays.asList("", "  ")), "", "V1 речи нет — пусто");
    eq(VoiceMsg.join(Arrays.asList("Custa R$ 20", "Ok")), "Custa R$ 20. Ok", "V1 число в конце куска — точка перед прописной");
    // V2 длительность
    eq(VoiceMsg.duration(0), "0:00", "V2 0 с"); eq(VoiceMsg.duration(42.4), "0:42", "V2 42 с");
    eq(VoiceMsg.duration(185), "3:05", "V2 3:05"); eq(VoiceMsg.duration(3729), "1:02:09", "V2 час и больше");
    // V3 расширение временной копии
    eq(VoiceMsg.ext("audio/ogg", "PTT-20261015-WA0003.opus"), ".opus", "V3 имя WhatsApp — его расширение");
    eq(VoiceMsg.ext("audio/ogg; codecs=opus", null), ".ogg", "V3 Ogg без имени");
    eq(VoiceMsg.ext("audio/mpeg", ""), ".mp3", "V3 mp3"); eq(VoiceMsg.ext("audio/mp4", null), ".m4a", "V3 m4a");
    eq(VoiceMsg.ext("audio/x-wav", "запись"), ".wav", "V3 wav по типу: имя без расширения");
    eq(VoiceMsg.ext("audio/amr", null), ".amr", "V3 amr");
    eq(VoiceMsg.ext("video/mp4", null), ".mp4", "V3 «кружочек» Telegram — mp4, из него берётся звук");
    eq(VoiceMsg.ext(null, null), ".ogg", "V3 ничего не известно — Ogg (кодек всё равно смотрит в содержимое)");
    eq(VoiceMsg.ext("audio/ogg", "voice.message.from.friend"), ".ogg", "V3 «расширение» длиннее 4 знаков — не расширение");
    // V4 отправитель
    eq(VoiceMsg.fromReferrer("android-app://com.whatsapp"), "com.whatsapp", "V4 WhatsApp");
    eq(VoiceMsg.fromReferrer("android-app://org.telegram.messenger/"), "org.telegram.messenger", "V4 Telegram, косая черта в конце");
    eq(VoiceMsg.fromReferrer("https://example.com"), null, "V4 не приложение");
    eq(VoiceMsg.fromReferrer(null), null, "V4 нет referrer");
    eq(VoiceMsg.fromReferrer("android-app://evil;rm -rf"), null, "V4 мусор вместо имени пакета");
    // V4б отправитель по хозяину файла — когда referrer пуст
    eq(VoiceMsg.fromAuthority("com.whatsapp.provider.media"), "com.whatsapp", "V4б WhatsApp по хозяину файла");
    eq(VoiceMsg.fromAuthority("com.whatsapp.w4b.provider.media"), "com.whatsapp.w4b", "V4б WhatsApp Business — не WhatsApp");
    eq(VoiceMsg.fromAuthority("org.telegram.messenger.provider"), "org.telegram.messenger", "V4б Telegram");
    eq(VoiceMsg.fromAuthority("org.telegram.messenger.web.provider"), "org.telegram.messenger.web", "V4б Telegram с сайта — свой пакет");
    eq(VoiceMsg.fromAuthority("com.android.providers.media.documents"), null, "V4б «Файлы» — не мессенджер");
    eq(VoiceMsg.fromAuthority("com.whatsappx.provider"), null, "V4б похожее имя — не WhatsApp");
    eq(VoiceMsg.fromAuthority(null), null, "V4б хозяина нет");
    // V5 куда отправить перевод
    eq(VoiceMsg.replyTo("com.whatsapp", 7, 7), "com.whatsapp", "V5 голосовое этого разговора пришло из WhatsApp — туда");
    eq(VoiceMsg.replyTo("com.whatsapp", 7, 8), null, "V5 другой разговор — общий список");
    eq(VoiceMsg.replyTo("com.google.android.documentsui", 7, 7), null, "V5 файл из «Файлов» — не мессенджер, общий список");
    eq(VoiceMsg.replyTo(null, 7, 7), null, "V5 отправитель неизвестен — общий список");
    // V6 пометка реплики: "voice" в JSON — Chats.VOICE в поле 8; снимок — по-прежнему PHOTO
    org.json.JSONObject v = Chats.turn("pt2ru", "Oi", "Привет", "", 1L).put("voice", 42);
    eq(Chats.row(v, 0)[8], Chats.VOICE, "V6 голосовое — 🎙");
    eq(Chats.row(Chats.turn("pt2ru", "Placa", "Табличка", "owner", 2L).put("photo", "x.jpg"), 1)[8], Chats.PHOTO, "V6 снимок — 📷");
    eq(Chats.row(Chats.turn("pt2ru", "Oi", "Привет", "", 3L), 2)[8], "", "V6 обычная реплика — без пометки");
    System.out.println(fails == 0 ? "VoiceMsg: " + checks + " проверок, все прошли" : "VoiceMsg: провалов " + fails + " из " + checks);
    return fails;
  }

  public static void main(String[] a) throws Exception { System.exit(run() == 0 ? 0 : 1); }
}
