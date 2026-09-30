package dev.agenttranslator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Полоса хода без анимации: перерисовывается, только когда сдвинулся ход. Бегущая полоса
 *  перерисовывала весь экран на каждом кадре — около 0,5 ядра в приложении и 0,4 в SurfaceFlinger
 *  на Redmi (results/2026-09-29-mic-button.md), а ход здесь меняется от силы раз в секунду.
 *
 *  Два вида. Сплошная доля — под шапкой: облако и загрузки. Отрезки этапов — в реплике:
 *  «распознаю · перевожу · уточняю»: пройденные — сливой, текущий — мятой, и на нём доля «k из N»,
 *  следующие — рамочным цветом. Решение владельца 01.10: ход перевода — в самой реплике. */
public class ProgressLine extends View {
  final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final RectF r = new RectF();
  final float gap;
  int segs = 1, cur = 0; float frac = 0;
  int done, now, fill, track;

  public ProgressLine(Context c) {
    super(c);
    gap = 4 * c.getResources().getDisplayMetrics().density;
  }

  /** Цвета: пройденный отрезок, текущий, доля на текущем, ещё не начатый. */
  public ProgressLine colors(int done, int now, int fill, int track) {
    this.done = done; this.now = now; this.fill = fill; this.track = track; invalidate(); return this;
  }

  /** segs отрезков, текущий — cur (до него пройдены), на нём пройдена доля frac 0…1. */
  public void set(int segs, int cur, float frac) {
    frac = Math.max(0, Math.min(1, Math.round(frac * 100) / 100f));   // до процента: без лишних кадров
    if (segs == this.segs && cur == this.cur && frac == this.frac) return;
    this.segs = Math.max(1, segs); this.cur = cur; this.frac = frac; invalidate();
  }

  @Override protected void onDraw(Canvas cv) {
    float h = getHeight(), rad = h / 2, w = (getWidth() - gap * (segs - 1)) / segs;
    for (int i = 0; i < segs; i++) {
      float x = i * (w + gap);
      r.set(x, 0, x + w, h);
      p.setColor(i < cur ? done : i == cur ? now : track);
      cv.drawRoundRect(r, rad, rad, p);
      if (i == cur && frac > 0) { r.set(x, 0, x + w * frac, h); p.setColor(fill); cv.drawRoundRect(r, rad, rad, p); }
    }
  }
}
