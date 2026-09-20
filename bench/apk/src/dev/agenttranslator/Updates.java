package dev.agenttranslator;

import org.json.JSONException;
import org.json.JSONObject;

/** Что считать новой версией и что для неё качать.
 *
 *  Приложение ставится мимо магазина, значит обновлять его некому: человек, поставивший сборку
 *  однажды, о следующей не узнает никогда. Поэтому приложение само раз в сутки спрашивает у
 *  GitHub описание последнего релиза и, если оно новее, показывает уведомление.
 *
 *  Описание — файл `latest.json` среди файлов релиза, по постоянному адресу
 *  `releases/latest/download/latest.json` (тот же приём, что у кнопки «Скачать APK»: адрес не
 *  меняется от версии к версии). Его кладёт `tools/release.sh` из того, что реально собрано.
 *  Почему не тег релиза: в теге нет ни номера сборки, ни размера, ни sha256 — а скачанный файл
 *  надо проверить до установки, иначе обновление становится способом подсунуть что угодно.
 *
 *  Сравнивается versionCode, а не versionName: он целый и только растущий, это требование Android
 *  и единственное, на что можно опереться без разбора строк.
 *
 *  `minCode` — та версия, ниже которой пользоваться приложением нельзя (сломанный формат данных,
 *  протухший источник моделей). Приложение из этого делает не запрет, а настойчивость: такое
 *  обновление показывается само и не прячется до перезапуска.
 *
 *  Без Android, поэтому проверяется на столе (bench/apk/test/UpdatesTest.java).
 */
public class Updates {
  /** Постоянный адрес описания последнего релиза. */
  public static final String LATEST = "https://github.com/Annoyt/FALAR/releases/latest/download/latest.json";

  public static class Info {
    public int code, minCode; public String name = "", apk = "", sha256 = "", notes = ""; public long size;
    /** Адрес файла — рядом с описанием, по тому же постоянному пути. */
    public String url(String base) { return base.substring(0, base.lastIndexOf('/') + 1) + apk; }
    @Override public String toString() { return name + " (" + code + ") " + apk + " " + size; }
  }

  /** Разбор описания релиза. slim — у поставленной сборки нет llama.cpp, значит и качать надо
   *  облегчённую: иначе обновление молча превратило бы её в полную и утянуло лишние 60 МБ.
   *  Бросает IllegalArgumentException на мусоре: молча считать «обновлений нет» нельзя, иначе
   *  сломанный файл выглядит как «всё хорошо» и приложение перестаёт обновляться навсегда. */
  public static Info parse(String json, boolean slim) {
    try {
      JSONObject o = new JSONObject(json);
      JSONObject b = slim && o.has("slim") ? o.getJSONObject("slim") : o;
      Info i = new Info();
      i.code = o.getInt("versionCode");
      i.name = o.optString("versionName", "");
      i.minCode = o.optInt("minVersionCode", 0);
      i.notes = o.optString("notes", "");
      i.apk = b.getString("apk");
      i.size = b.getLong("size");
      i.sha256 = b.getString("sha256");
      if (i.code <= 0 || i.apk.isEmpty() || i.sha256.length() != 64 || i.size <= 0)
        throw new IllegalArgumentException("описание релиза неполное: " + i);
      return i;
    } catch (JSONException e) {
      throw new IllegalArgumentException("описание релиза не разобрано: " + e.getMessage());
    }
  }

  /** Есть ли что ставить поверх установленного. */
  public static boolean newer(int installed, Info i) { return i != null && i.code > installed; }
  /** Обновление, которое нельзя откладывать. */
  public static boolean required(int installed, Info i) { return i != null && installed < i.minCode; }

  /** Строка для человека. Короткая: она же уходит в уведомление. */
  public static String describe(int installed, Info i) {
    if (i == null) return "";
    if (!newer(installed, i)) return "Установлена последняя версия";
    return "Вышла версия " + (i.name.isEmpty() ? String.valueOf(i.code) : i.name)
         + " · " + String.format(java.util.Locale.ROOT, "%.0f", i.size / 1e6) + " МБ"
         + (required(installed, i) ? " · обновление обязательное" : "");
  }
}
