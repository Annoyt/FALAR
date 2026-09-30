package dev.agenttranslator;

import android.content.Context;
import android.content.res.Configuration;

/** Цвета экранов Falar. Взяты у иконки и кнопки удержания (слива, глубокая слива, золото, мята) и
 *  у страницы docs/index.html, а не у системы. Без своей темы Android красил фон и полосы по обоям
 *  телефона: на стенде 30.09 — розоватый #FBEEEC и тёмная полоса «Falar». На каждом телефоне Falar
 *  выходил своего цвета, а выделение — синим, не из палитры. Макеты — design/mockups/index.html.
 *
 *  Ночь — вслед за системой (решение владельца 01.10): тот же выбор делает тема из
 *  res/values-night, а здесь — цвета, которые код ставит сам. */
final class Look {
  static final int PLUM = 0xFF802244, DEEP = 0xFF3D0C2A, GOLD = 0xFFE0B878, MINT = 0xFF6FE0BC;
  final boolean night;
  /** Фон, основной текст, второстепенный, подсказки, карточки, рамки, текст состояния, подложка. */
  final int bg, fg, dim, soft, card, line, accent, tint;
  /** Кнопки: обычная и её рамка; выключенная — фон, рамка, текст. */
  final int btn, btnLine, offBg, offLine, offText;

  Look(Context c) {
    night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    if (!night) {
      bg = 0xFFFFFFFF; fg = 0xFF1A1420; dim = 0xFF5D5566; soft = 0xFF8A8092; card = 0xFFFAF7F9; line = 0xFFE6DFE6;
      accent = PLUM; tint = 0xFFF6EAF0;
      btn = 0xFFF6F2F5; btnLine = 0xFFE0D7E0; offBg = 0xFFF7F4F7; offLine = 0xFFEEE8EE; offText = 0xFFB4AAB9;
    } else {
      // Слива на тёмном фоне почти не видна — текст состояния золотой, как ночная ссылка на странице Falar.
      bg = 0xFF15101A; fg = 0xFFF2ECF2; dim = 0xFFA79DB0; soft = 0xFF857B8F; card = 0xFF1E1725; line = 0xFF352B3D;
      accent = GOLD; tint = 0xFF2C1526;
      btn = 0xFF221A29; btnLine = 0xFF3A2F43; offBg = 0xFF1A141F; offLine = 0xFF2A2230; offText = 0xFF5E5566;
    }
  }
}
