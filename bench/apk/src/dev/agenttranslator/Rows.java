package dev.agenttranslator;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.function.IntConsumer;

/** Строки экранов настроек: группа — заголовок и карточка; строка — значок, название, подпись и
 *  справа переключатель, значение или «›»; под строкой — место для ползунка или ряда сегментов.
 *  Выбор из нескольких значений — сегментами, где видны все значения, а не кнопкой, которая меняет
 *  свой текст по кругу. Макет — design/mockups/index.html (решения владельца 01.10). */
final class Rows {
  final Context c; final Look look; final float d;

  Rows(Context c, Look look) { this.c = c; this.look = look; d = c.getResources().getDisplayMetrics().density; }
  int dp(float v) { return Math.round(v * d); }

  GradientDrawable round(int color, float radiusDp, int stroke) {
    GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp));
    if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
    return g;
  }
  android.graphics.drawable.Drawable icon(int res, int color, int sizeDp) {
    android.graphics.drawable.Drawable x = c.getDrawable(res).mutate(); x.setTint(color); x.setBounds(0, 0, dp(sizeDp), dp(sizeDp)); return x;
  }
  int ink() { return look.night ? Look.GOLD : Look.PLUM; }

  /** Страница настроек: фон чуть темнее карточек, поля по краям. */
  LinearLayout page() {
    LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL);
    v.setBackgroundColor(look.page); v.setPadding(dp(12), dp(4), dp(12), dp(20));
    return v;
  }

  /** Группа: заголовок и карточка под ним; возвращает карточку. Обёртка группы — в tag карточки:
   *  её прячут целиком, когда в группе нечего показать. */
  LinearLayout group(LinearLayout page, String title) {
    LinearLayout g = new LinearLayout(c); g.setOrientation(LinearLayout.VERTICAL);
    TextView h = new TextView(c); h.setText(title); h.setTextSize(13); h.setTypeface(null, Typeface.BOLD); h.setTextColor(ink());
    h.setPadding(dp(8), dp(16), dp(8), dp(7));
    g.addView(h);
    LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(round(look.bg, 16, look.line)); card.setClipToOutline(true);
    g.addView(card);
    page.addView(g);
    card.setTag(g);
    return card;
  }

  /** Часть карточки, которую прячут вместе (строки модуля: озвучки, облака, уточнителя). */
  LinearLayout part(LinearLayout card) {
    LinearLayout p = new LinearLayout(c); p.setOrientation(LinearLayout.VERTICAL); card.addView(p); return p;
  }

  static final class Row { LinearLayout v; TextView title, sub; }

  /** Строка: значок, название, подпись (null — без неё), справа right (null — ничего). Между
   *  строками — тонкая черта; у первой строки карточки её нет. */
  Row row(LinearLayout parent, int icon, String title, String sub, View right) {
    if (!(parent.getChildCount() == 0 && parent.getTag() instanceof LinearLayout)) divider(parent);
    Row r = new Row();
    LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL);
    v.setPadding(dp(14), dp(11), dp(12), dp(11)); v.setMinimumHeight(dp(58));
    ImageView ic = new ImageView(c); ic.setImageDrawable(icon(icon, ink(), 24));
    v.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
    LinearLayout t = new LinearLayout(c); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(14), 0, dp(8), 0);
    r.title = new TextView(c); r.title.setText(title); r.title.setTextSize(15.5f); r.title.setTextColor(look.fg);
    t.addView(r.title);
    r.sub = new TextView(c); r.sub.setTextSize(12.5f); r.sub.setTextColor(look.dim); r.sub.setLineSpacing(0, 1.1f);
    if (sub != null) r.sub.setText(sub); else r.sub.setVisibility(View.GONE);
    t.addView(r.sub);
    v.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
    if (right != null) v.addView(right);
    parent.addView(v);
    r.v = v;
    return r;
  }

  /** Строка, которая открывает экран: «›» справа и отклик на касание. */
  Row nav(LinearLayout parent, int icon, String title, String sub, View.OnClickListener go) {
    ImageView ch = new ImageView(c); ch.setImageDrawable(icon(app.falar.R.drawable.ic_chev, look.soft, 22));
    Row r = row(parent, icon, title, sub, ch);
    r.v.setBackground(press(0));
    r.v.setClickable(true); r.v.setOnClickListener(go);
    return r;
  }

  android.graphics.drawable.StateListDrawable press(int up) {
    android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
    sl.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.ColorDrawable(look.tint));
    sl.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(up)); return sl;
  }

  void divider(LinearLayout parent) {
    View l = new View(c); l.setBackgroundColor(look.hair);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
    parent.addView(l, lp);
  }

  /** Место под строкой — для ползунка, сегментов, подписи. indent — с отступом под значок. */
  LinearLayout sub(LinearLayout parent, boolean indent) {
    LinearLayout s = new LinearLayout(c); s.setOrientation(LinearLayout.VERTICAL);
    s.setPadding(indent ? dp(52) : dp(14), 0, dp(14), dp(14));
    parent.addView(s);
    return s;
  }

  TextView caption(LinearLayout parent, String text) {
    TextView t = new TextView(c); t.setText(text); t.setTextSize(12); t.setTextColor(look.soft); t.setPadding(0, dp(8), 0, 0);
    t.setLineSpacing(0, 1.1f); parent.addView(t); return t;
  }

  TextView value(String text) {
    TextView t = new TextView(c); t.setText(text); t.setTextSize(14); t.setTypeface(null, Typeface.BOLD); t.setTextColor(ink());
    return t;
  }

  Switch sw() { Switch s = new Switch(c); s.setShowText(false); return s; }

  /** Ряд сегментов: все значения видны, выбранное — сливой. */
  static final class Seg {
    LinearLayout v; TextView[] o; int sel = -1; IntConsumer on; Look look; Rows r;
    void sel(int i) {
      sel = i;
      for (int k = 0; k < o.length; k++) {
        boolean on = k == i;
        o[k].setTextColor(on ? 0xFFFFFFFF : look.dim);
        o[k].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        o[k].setBackground(on ? r.round(Look.PLUM, 9, 0) : null);
      }
    }
  }
  Seg seg(LinearLayout parent, String[] labels, IntConsumer on) {
    Seg s = new Seg(); s.look = look; s.r = this; s.on = on;
    s.v = new LinearLayout(c); s.v.setOrientation(LinearLayout.HORIZONTAL);
    s.v.setBackground(round(look.segBg, 12, 0)); s.v.setPadding(dp(3), dp(3), dp(3), dp(3));
    s.o = new TextView[labels.length];
    for (int k = 0; k < labels.length; k++) {
      final int kk = k;
      TextView t = new TextView(c); t.setText(labels[k]); t.setTextSize(13); t.setGravity(Gravity.CENTER);
      t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); t.setPadding(dp(4), dp(8), dp(4), dp(8));
      t.setOnClickListener(x -> { if (s.sel != kk) { s.sel(kk); if (s.on != null) s.on.accept(kk); } });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
      if (k > 0) lp.leftMargin = dp(3);
      s.v.addView(t, lp); s.o[k] = t;
    }
    parent.addView(s.v, new LinearLayout.LayoutParams(-1, -2));
    return s;
  }

  /** Метка состояния: скруглённый ярлык. kind: ok · wait · no · go. */
  void pill(TextView t, String text, String kind) {
    int bg = "ok".equals(kind) ? look.okBg : "wait".equals(kind) ? look.waitBg : "go".equals(kind) ? look.goBg : look.noBg;
    int fg = "ok".equals(kind) ? look.okFg : "wait".equals(kind) ? look.waitFg : "go".equals(kind) ? look.goFg : look.noFg;
    t.setText(text); t.setTextColor(fg); t.setBackground(round(bg, 10, 0));
  }
  TextView pill() {
    TextView t = new TextView(c); t.setTextSize(11.5f); t.setTypeface(null, Typeface.BOLD); t.setPadding(dp(8), dp(2), dp(8), dp(2));
    t.setSingleLine(true); return t;
  }

  /** Кнопка-рамка («Проверить файлы») и сливовая («Сохранить», «Обновить»). */
  Button button(String text, boolean primary) {
    Button b = new Button(c); b.setAllCaps(false); b.setText(text); b.setTextSize(14); b.setTypeface(null, Typeface.BOLD);
    b.setStateListAnimator(null); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(16), 0, dp(16), 0);
    android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
    sl.addState(new int[]{-android.R.attr.state_enabled}, round(look.offBg, 20, look.offLine));
    sl.addState(new int[]{android.R.attr.state_pressed}, round(primary ? Look.DEEP : look.tint, 20, primary ? 0 : look.line));
    sl.addState(new int[]{}, round(primary ? Look.PLUM : look.bg, 20, primary ? 0 : look.line));
    b.setBackground(sl);
    b.setTextColor(new android.content.res.ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
        new int[]{look.offText, primary ? 0xFFFFFFFF : ink()}));
    return b;
  }
}
