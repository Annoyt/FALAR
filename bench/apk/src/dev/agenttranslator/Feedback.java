package dev.agenttranslator;

import java.net.URLEncoder;

/** Обратная связь: готовые ссылки на GitHub (шаблоны .github/ISSUE_TEMPLATE) и на бота в Telegram
 *  (bot/ в репозитории). Своего сервера у приложения нет: ссылку открывает браузер или Telegram, а
 *  отправляет человек сам. Отдельно от MainActivity, чтобы сборка ссылок проверялась на столе. */
final class Feedback {
  static final String REPO = "https://github.com/Annoyt/FALAR";
  static final String BOT = "falar_feedback_bot";
  /** Ссылка на GitHub длиннее ~7 КБ не открывается. Замер 02.10.2026 (curl, без входа в аккаунт):
   *  до 7004 байт GitHub уводит на вход, с 7058 отвечает 500, от ~15 КБ — 414. Берём с запасом.
   *  Русская буква в ссылке — 6 байт: это около 900 букв на исходник, перевод и вариант вместе. */
  static final int GITHUB_MAX = 6000;
  /** Сообщение в Telegram — до 4096 знаков; запас — на подпись, которую добавляет бот. */
  static final int TELEGRAM_MAX = 3500;
  /** Метки первой строкой: по ним бот кладёт сообщение в топик (bot/lib/feedback.js). */
  static final String TAG_TRANSLATION = "#перевод", TAG_BUG = "#ошибка", TAG_IDEA = "#идея";

  static String direction(String dir) {
    return dir != null && dir.startsWith("pt") ? "португальский → русский" : "русский → португальский";
  }

  /** Сообщение о переводе для Telegram. */
  static String translationText(String dir, String src, String dst, String mine, String device) {
    final String d = direction(dir);
    return fit(new String[]{src, dst, mine}, TELEGRAM_MAX, p -> TAG_TRANSLATION + " · " + d
        + "\nИсходник: " + p[0]
        + "\nПеревод приложения: " + p[1]
        + "\nКак правильно: " + (p[2].isEmpty() ? "(не указано)" : p[2])
        + "\n\n" + device);
  }

  /** Обращение о переводе на GitHub: шаблон translation.yml, поля — по их id. */
  static String githubTranslation(String dir, String src, String dst, String mine, String device) {
    final String d = direction(dir), title = "перевод: " + cut(src, 60);
    return fit(new String[]{src, dst, mine}, GITHUB_MAX, p -> REPO + "/issues/new?template=translation.yml"
        + "&title=" + enc(title) + "&direction=" + enc(d) + "&source=" + enc(p[0])
        + "&app=" + enc(p[1]) + "&better=" + enc(p[2]) + "&device=" + enc(device));
  }

  /** Сообщение разработчику без реплики: метка и версия первой строкой, дальше пишет человек. */
  static String noteText(boolean bug, String device) { return (bug ? TAG_BUG : TAG_IDEA) + " · " + device + "\n"; }

  /** Обращение на GitHub без реплики: шаблон ошибки или идеи, заполнена только версия. */
  static String githubNote(boolean bug, String device) {
    return REPO + "/issues/new?template=" + (bug ? "bug.yml" : "idea.yml") + "&device=" + enc(device);
  }

  /** Чат с ботом, текст уже в поле ввода. */
  static String telegram(String text) { return "https://t.me/" + BOT + "?text=" + enc(text); }

  interface Build { String apply(String[] parts); }

  /** Укорачивает части, пока build не уложится в max: каждый раз самую длинную — на десятую часть.
   *  Укороченная кончается на «…». Если не уложилось и пустыми частями — что вышло. */
  static String fit(String[] parts, int max, Build build) {
    String[] cur = parts.clone();
    int[] keep = new int[parts.length];
    for (int i = 0; i < parts.length; i++) keep[i] = parts[i].length();
    String r = build.apply(cur);
    while (r.length() > max) {
      int j = 0;
      for (int i = 1; i < keep.length; i++) if (keep[i] > keep[j]) j = i;
      if (keep[j] == 0) break;
      keep[j] -= Math.max(1, keep[j] / 10);
      cur[j] = cut(parts[j], keep[j]);
      r = build.apply(cur);
    }
    return r;
  }

  /** Первые n знаков и «…»; суррогатную пару не разрывает. */
  static String cut(String s, int n) {
    if (s.length() <= n) return s;
    if (n > 0 && Character.isHighSurrogate(s.charAt(n - 1))) n--;
    return s.substring(0, Math.max(0, n)).trim() + "…";
  }

  /** Пробел — %20, а не «+»: «+» в ссылке t.me не везде читается как пробел. */
  static String enc(String s) {
    try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
    catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
  }
}
