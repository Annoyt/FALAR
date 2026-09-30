package dev.agenttranslator;

import android.content.Context;
import android.graphics.*;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** «Слушать»: две половинки, PT и RU. Включается любая одна или обе сразу — владелец 30.09: «должно
 *  иметь возможность выбора одного из двух или оба языка разом». Это те же два переключателя, что
 *  прежние кнопки «Слушать PT» и «Слушать RU», только в одном месте дока.
 *
 *  Включённая половинка — мятная. Пока слушаем, вокруг кнопки кольцо цвета «как слышно»
 *  (Hearing.Live, как у кнопки удержания и прежней полоски уровня): зелёное — громкости хватает,
 *  чем краснее — тем тише; в паузе — серое. Толщина кольца — уровень входа сейчас. Слов о том, как
 *  слышно, здесь нет — они в журнале. Перерисовка — только когда цвет или толщина сдвинулись. */
public class ListenButton extends View {
  public interface OnHalf { void tap(boolean pt); }
  OnHalf onHalf;
  boolean pt, ru;
  final float dp; final Look look;
  final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
  final RectF pill = new RectF(), ring = new RectF(); final Path half = new Path();
  int ringColor = 0; float ringPx = 0;

  public ListenButton(Context c) {
    super(c);
    dp = c.getResources().getDisplayMetrics().density; look = new Look(c);
    stroke.setStyle(Paint.Style.STROKE);
    text.setTypeface(Typeface.DEFAULT_BOLD); text.setTextAlign(Paint.Align.CENTER); text.setLetterSpacing(0.05f);
    setClickable(true); setHapticFeedbackEnabled(true);
  }

  public void setState(boolean pt, boolean ru) {
    if (pt == this.pt && ru == this.ru) return;
    this.pt = pt; this.ru = ru;
    if (!pt && !ru) { ringColor = 0; ringPx = 0; }
    invalidate();
  }

  /** Уровень входа, dBFS, как слышно 0…1 и была ли сейчас речь — те же, что у прежней полоски. */
  public void setLevel(float db, float q, boolean speech) {
    if (!pt && !ru) return;
    int col = speech && q >= 0 ? MicButton.qColor(Math.round(q * 24) / 24f, 0.75f, 0.72f) : (look.night ? 0xFF6E6477 : 0xFFB9B0BD);
    float k = Math.max(0, Math.min(1, (db + 55) / 40));
    float px = Math.round((2 + 3 * k) * dp);
    if (col != ringColor || px != ringPx) { ringColor = col; ringPx = px; invalidate(); }
  }

  @Override public boolean onTouchEvent(MotionEvent e) {
    if (!isEnabled()) return false;
    if (e.getAction() == MotionEvent.ACTION_DOWN) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING); return true; }
    if (e.getAction() == MotionEvent.ACTION_UP) {
      boolean inside = e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight();
      if (inside) { performClick(); if (onHalf != null) onHalf.tap(e.getX() < getWidth() / 2f); }
      return true;
    }
    return e.getAction() == MotionEvent.ACTION_MOVE || super.onTouchEvent(e);
  }

  @Override protected void onDraw(Canvas cv) {
    float m = 7 * dp, w = getWidth(), h = getHeight();
    pill.set(m, m, w - m, h - m);
    float r = pill.height() / 2, mid = pill.centerX();
    if ((pt || ru) && ringColor != 0) {
      float o = 3 * dp + ringPx / 2;                  // зазор цвета фона между кнопкой и кольцом
      ring.set(pill.left - o, pill.top - o, pill.right + o, pill.bottom + o);
      stroke.setColor(ringColor); stroke.setStrokeWidth(ringPx);
      cv.drawRoundRect(ring, r + o, r + o, stroke);
    }
    for (int i = 0; i < 2; i++) {
      boolean on = i == 0 ? pt : ru;
      half.reset(); half.addRoundRect(pill, r, r, Path.Direction.CW);
      cv.save(); cv.clipRect(i == 0 ? pill.left : mid, pill.top, i == 0 ? mid : pill.right, pill.bottom);
      fill.setColor(on ? Look.MINT : look.card); cv.drawPath(half, fill);
      cv.restore();
    }
    stroke.setColor(look.line); stroke.setStrokeWidth(dp);
    if (!pt && !ru) cv.drawRoundRect(pill, r, r, stroke);
    cv.drawLine(mid, pill.top, mid, pill.bottom, stroke);
    text.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
    float ty = pill.centerY() - (text.ascent() + text.descent()) / 2;
    int off = look.night ? Look.GOLD : Look.PLUM;
    text.setColor(pt ? Look.DEEP : off); cv.drawText("PT", (pill.left + mid) / 2, ty, text);
    text.setColor(ru ? Look.DEEP : off); cv.drawText("RU", (mid + pill.right) / 2, ty, text);
  }
}
