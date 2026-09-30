package dev.agenttranslator;

import android.content.Context;
import android.graphics.*;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** Строка под кнопками слушания и под кнопкой удержания: слева — полоска уровня входа с отметкой
 *  фона, пока слушаем, справа — обычная подсказка. Полоска окрашена тем, как слышно
 *  (Hearing.Live — по каждой фразе, паузы его не трогают): зелёная — громкости хватает, чем
 *  краснее — тем тише и вероятнее ошибки; в паузе — серая. Слов о том, как слышно, здесь нет —
 *  они в журнале.
 *
 *  Полоска перерисовывается, только когда её длина меняется на экранный пиксель: любая
 *  перерисовка — это перерисовка всего экрана (results/2026-09-29-mic-button.md), поэтому в
 *  ровной тишине кадров нет вовсе, а опрос уровня идёт 5 раз в секунду и только пока слушаем. */
public class HearingView extends View {
  /** Шкала полоски, dBFS: левый край и правый. */
  static final float LO = -60, HI = 0;
  final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), tick = new Paint(Paint.ANTI_ALIAS_FLAG);
  final float dp; final RectF r = new RectF();
  boolean bar = false; float level = LO, floor = Float.NaN; int shownPx = -1, shownFloorPx = -1, shownColor = 0;
  String line = ""; int lineColor = 0xFF808080;

  public HearingView(Context c) {
    super(c);
    dp = c.getResources().getDisplayMetrics().density;
    text.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
    track.setColor(0x22000000); tick.setColor(0xFF606060);
  }

  /** Полоска видна, только пока слушаем; иначе строка — обычная подсказка во всю ширину. */
  public void setBar(boolean on) { if (on != bar) { bar = on; shownPx = -1; invalidate(); } }

  public void setLine(String s, int color) {
    if (s.equals(line) && color == lineColor) return;
    line = s; lineColor = color; setContentDescription(s); invalidate();   // строку читает TalkBack и стенд (uiautomator)
  }

  /** Уровень и фон в dBFS, как слышно 0…1 и была ли сейчас речь. Перерисовка — только если
   *  сдвинулись длина полоски, отметка фона или цвет (оттенок шагами по 5°). */
  public void setLevel(float db, float floorDb, float q, boolean speech) {
    level = db; floor = floorDb;
    int px = px(db), fpx = Float.isNaN(floorDb) ? -1 : px(floorDb);
    int col = speech && q >= 0 ? MicButton.qColor(Math.round(q * 24) / 24f, 0.75f, 0.70f) : 0xFF9E9E9E;
    if (px != shownPx || fpx != shownFloorPx || col != shownColor) { shownPx = px; shownFloorPx = fpx; shownColor = col; invalidate(); }
  }

  int barW() { return Math.round(64 * dp); }
  int px(float db) { return Math.round(barW() * Math.max(0, Math.min(1, (db - LO) / (HI - LO)))); }

  @Override protected void onMeasure(int w, int h) {
    Paint.FontMetrics fm = text.getFontMetrics();
    setMeasuredDimension(MeasureSpec.getSize(w), Math.round(fm.descent - fm.ascent + 8 * dp));
  }

  @Override protected void onDraw(Canvas cv) {
    float x = 0, cy = getHeight() / 2f;
    if (bar) {
      float bw = barW(), bh = 6 * dp;
      r.set(0, cy - bh / 2, bw, cy + bh / 2); cv.drawRoundRect(r, bh / 2, bh / 2, track);
      fill.setColor(shownColor);
      if (shownPx > 0) { r.set(0, cy - bh / 2, shownPx, cy + bh / 2); cv.drawRoundRect(r, bh / 2, bh / 2, fill); }
      if (shownFloorPx >= 0) cv.drawRect(shownFloorPx - dp, cy - bh, shownFloorPx + dp, cy + bh, tick);
      x = bw + 8 * dp;
    }
    text.setColor(lineColor);
    CharSequence t = TextUtils.ellipsize(line, text, getWidth() - x, TextUtils.TruncateAt.END);
    Paint.FontMetrics fm = text.getFontMetrics();
    cv.drawText(t, 0, t.length(), x, cy - (fm.ascent + fm.descent) / 2, text);
  }
}
