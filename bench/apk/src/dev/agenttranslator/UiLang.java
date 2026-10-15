package dev.agenttranslator;

import android.content.Context;
import android.os.Build;

/** Язык надписей на телефоне: выбор человека, язык системы и словарь из assets. Сам выбор и
 *  подстановка — в L (без Android, проверяется на столе).
 *
 *  С Android 13 выбор хранит система — «язык приложения»: он же виден в настройках Android
 *  (Приложения → Falar → Язык), и смена там пересоздаёт экран так же, как смена в Falar.
 *  До 13 — своя настройка ui_lang, экран пересоздаёт вызывающий. */
final class UiLang {
  private UiLang() {}
  private static volatile boolean loaded;

  /** При создании экрана и сервиса: словарь (один раз) и язык надписей. */
  static void init(Context c) {
    if (!loaded) synchronized (UiLang.class) {
      if (!loaded) {
        try (java.io.Reader r = new java.io.InputStreamReader(c.getAssets().open("ui_pt.txt"), "UTF-8")) { L.use(L.parse(r)); }
        catch (Exception e) { android.util.Log.e("falar", "ui_pt.txt", e); }
        loaded = true;
      }
    }
    L.lang = L.resolve(choice(c), system(c));
  }

  /** Выбор человека: "ru", "pt" или "" — как в системе. */
  static String choice(Context c) {
    if (Build.VERSION.SDK_INT >= 33) {
      android.app.LocaleManager lm = c.getSystemService(android.app.LocaleManager.class);
      if (lm != null) {
        android.os.LocaleList l = lm.getApplicationLocales();
        if (l.isEmpty()) return "";
        String g = l.get(0).getLanguage();
        return L.PT.equals(g) || L.RU.equals(g) ? g : "";
      }
    }
    return c.getSharedPreferences("at", Context.MODE_PRIVATE).getString("ui_lang", "");
  }

  /** Язык телефона — первый в списке языков системы. */
  static String system(Context c) {
    if (Build.VERSION.SDK_INT >= 33) {
      android.app.LocaleManager lm = c.getSystemService(android.app.LocaleManager.class);
      if (lm != null) { android.os.LocaleList l = lm.getSystemLocales(); if (!l.isEmpty()) return l.get(0).getLanguage(); }
    }
    android.os.LocaleList l = android.content.res.Resources.getSystem().getConfiguration().getLocales();
    return l.isEmpty() ? "" : l.get(0).getLanguage();
  }

  /** Сменить выбор ("", "ru", "pt"). true — экран пересоздаст система (Android 13+), false —
   *  язык уже сменён здесь, пересоздать экран должен вызывающий. */
  static boolean set(Context c, String choice) {
    c.getSharedPreferences("at", Context.MODE_PRIVATE).edit().putString("ui_lang", choice).apply();
    L.lang = L.resolve(choice, system(c));
    if (Build.VERSION.SDK_INT >= 33) {
      android.app.LocaleManager lm = c.getSystemService(android.app.LocaleManager.class);
      if (lm != null) {
        lm.setApplicationLocales(choice.isEmpty() ? android.os.LocaleList.getEmptyLocaleList()
            : android.os.LocaleList.forLanguageTags(L.PT.equals(choice) ? "pt-BR" : "ru"));
        return true;
      }
    }
    return false;
  }
}
