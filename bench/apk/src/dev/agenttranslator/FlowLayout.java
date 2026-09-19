package dev.agenttranslator;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Перенос детей по строкам, как слов в абзаце. Нужен для транскрипции под каждым словом:
 *  пара «слово + подсказка» — один ребёнок, и строка рвётся между парами, а не внутри пары.
 *  Без AndroidX готового такого контейнера нет. Высота строки — по самому высокому ребёнку. */
public class FlowLayout extends ViewGroup {
  int hGap = 18, vGap = 6;
  public FlowLayout(Context c) { super(c); }

  @Override protected void onMeasure(int wSpec, int hSpec) {
    int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
    int x = 0, y = 0, rowH = 0, usedW = 0;
    for (int i = 0; i < getChildCount(); i++) {
      View ch = getChildAt(i); if (ch.getVisibility() == GONE) continue;
      ch.measure(MeasureSpec.makeMeasureSpec(Math.max(0, maxW), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
      if (x > 0 && x + w > maxW) { y += rowH + vGap; x = 0; rowH = 0; }
      x += w + hGap; rowH = Math.max(rowH, h); usedW = Math.max(usedW, x - hGap);
    }
    int totalH = y + rowH + getPaddingTop() + getPaddingBottom();
    setMeasuredDimension(resolveSize(usedW + getPaddingLeft() + getPaddingRight(), wSpec), resolveSize(totalH, hSpec));
  }

  @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
    int maxW = r - l - getPaddingLeft() - getPaddingRight();
    int x = 0, y = 0, rowH = 0;
    for (int i = 0; i < getChildCount(); i++) {
      View ch = getChildAt(i); if (ch.getVisibility() == GONE) continue;
      int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
      if (x > 0 && x + w > maxW) { y += rowH + vGap; x = 0; rowH = 0; }
      ch.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
      x += w + hGap; rowH = Math.max(rowH, h);
    }
  }
}
