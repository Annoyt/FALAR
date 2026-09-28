package dev.agenttranslator;

import android.content.Context;
import android.graphics.*;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Перевод поверх снимка: каждый абзац вывески закрашен цветом фона, взятым с самого снимка
 *  вокруг текста (градиентом по четвертям — свет на вывеске неровный), и поверх написан перевод
 *  цветом исходного текста, под тем же углом, самым крупным кеглем, какой помещается.
 *
 *  Жесты: щипок — увеличить, палец — сдвинуть, касание абзаца — его оригинал и перевод текстом
 *  внизу (onPick), удержание — снимок без перевода, пока палец на экране. Звука нет: снимок
 *  читают глазами (так решил владелец 28.09 — «упор на чтение»).
 *
 *  Абзацы без перевода (не португальский текст, одиночные служебные слова) остаются как есть.
 *  Макет того же рисования на столе — tools/ocr_overlay.py. */
public class PhotoView extends View {
  public interface OnPick { void pick(String src, String dst); }

  static final class Block {
    final double cx, cy, ux, uy, w, h; final String src, dst; final float deg;
    Bitmap patch; int fg = Color.BLACK; StaticLayout layout; float lw;
    Block(JSONObject b) {
      JSONArray f = b.optJSONArray("f");
      cx = f.optDouble(0); cy = f.optDouble(1); ux = f.optDouble(2, 1); uy = f.optDouble(3, 0); w = f.optDouble(4); h = f.optDouble(5);
      src = b.optString("src", ""); dst = b.optString("dst", "");
      deg = (float) Math.toDegrees(Math.atan2(uy, ux));
    }
    boolean hit(float x, float y) {
      double dx = x - cx, dy = y - cy, a = dx * ux + dy * uy, n = -dx * uy + dy * ux;
      return Math.abs(a) <= w / 2 + 6 && Math.abs(n) <= h / 2 + 6;
    }
  }

  final Bitmap bm; final List<Block> blocks = new ArrayList<>();
  final Matrix m = new Matrix(); final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
  boolean original = false, fitted = false; OnPick onPick;
  final ScaleGestureDetector scale; final GestureDetector gest;

  public PhotoView(Context c, Bitmap bm, JSONArray bl) {
    super(c);
    this.bm = bm;
    int w = bm.getWidth(), h = bm.getHeight(); int[] px = new int[w * h]; bm.getPixels(px, 0, w, 0, 0, w, h);
    for (int i = 0; bl != null && i < bl.length(); i++) {
      JSONObject o = bl.optJSONObject(i); if (o == null || o.optJSONArray("f") == null) continue;
      Block b = new Block(o);
      if (!b.dst.isEmpty()) {
        int[] col = OcrCore.colors(px, w, h, new double[]{b.cx, b.cy, b.ux, b.uy, b.w, b.h});
        b.patch = gradient(col); b.fg = col[4];
        fit(b);
      }
      blocks.add(b);
    }
    scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
      @Override public boolean onScale(ScaleGestureDetector d) {
        float s = d.getScaleFactor(), cur = cur();
        float lo = fitScale(), next = Math.max(lo, Math.min(lo * 8, cur * s));
        m.postScale(next / cur, next / cur, d.getFocusX(), d.getFocusY()); clampPan(); invalidate(); return true;
      }
    });
    gest = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
      @Override public boolean onDown(MotionEvent e) { return true; }
      @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) { m.postTranslate(-dx, -dy); clampPan(); invalidate(); return true; }
      @Override public boolean onSingleTapUp(MotionEvent e) {
        float[] p = {e.getX(), e.getY()}; Matrix inv = new Matrix(); m.invert(inv); inv.mapPoints(p);
        Block got = null; for (Block b : blocks) if (b.hit(p[0], p[1])) { got = b; break; }
        if (onPick != null) onPick.pick(got == null ? null : got.src, got == null ? null : got.dst);
        return true;
      }
      @Override public void onLongPress(MotionEvent e) { original = true; invalidate(); }
      @Override public boolean onDoubleTap(MotionEvent e) { fitted = false; invalidate(); return true; }
    });
  }

  /** Сетка 16×16 билинейного градиента между четырьмя цветами каймы: при растяжении с
   *  фильтрацией это и есть плавная заливка от края до края. */
  static Bitmap gradient(int[] c) {
    int n = 16; int[] px = new int[n * n];
    for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
      double tx = x / (n - 1.0), ty = y / (n - 1.0); int v = 0xFF000000;
      for (int sh = 16; sh >= 0; sh -= 8) {
        double top = ((c[0] >> sh) & 255) * (1 - tx) + ((c[2] >> sh) & 255) * tx;   // лево-верх → право-верх
        double bot = ((c[1] >> sh) & 255) * (1 - tx) + ((c[3] >> sh) & 255) * tx;   // лево-низ → право-низ
        v |= ((int) Math.round(top * (1 - ty) + bot * ty) & 255) << sh;
      }
      px[y * n + x] = v;
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
  }

  /** Самый крупный кегль, при котором перевод, перенесённый по словам, влезает в прямоугольник. */
  static void fit(Block b) {
    TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG); tp.setColor(b.fg); tp.setTypeface(Typeface.DEFAULT_BOLD);
    int width = Math.max(1, (int) (b.w * 0.96)); double maxH = b.h * 0.96;
    float lo = 4f, hi = (float) Math.max(5, b.h * 0.9); StaticLayout best = null;
    for (int it = 0; it < 14; it++) {
      float s = (lo + hi) / 2; tp.setTextSize(s);
      StaticLayout l = StaticLayout.Builder.obtain(b.dst, 0, b.dst.length(), tp, width)
          .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1f).setIncludePad(false).build();
      boolean fits = l.getHeight() <= maxH;
      for (int k = 0; fits && k < l.getLineCount(); k++) fits = l.getLineWidth(k) <= width + 0.5f;
      if (fits) { lo = s; best = l; } else hi = s;
    }
    if (best == null) { tp.setTextSize(lo); best = StaticLayout.Builder.obtain(b.dst, 0, b.dst.length(), tp, width).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build(); }
    b.layout = best; b.lw = width;
  }

  float cur() { float[] v = new float[9]; m.getValues(v); return v[Matrix.MSCALE_X]; }
  float fitScale() { return Math.min((float) getWidth() / bm.getWidth(), (float) getHeight() / bm.getHeight()); }

  /** Снимок не уходит за край дальше, чем нужно: меньше экрана — по центру, больше — до края. */
  void clampPan() {
    RectF r = new RectF(0, 0, bm.getWidth(), bm.getHeight()); m.mapRect(r);
    float dx = 0, dy = 0;
    if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2 - r.left; else if (r.left > 0) dx = -r.left; else if (r.right < getWidth()) dx = getWidth() - r.right;
    if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2 - r.top; else if (r.top > 0) dy = -r.top; else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
    m.postTranslate(dx, dy);
  }

  public void setOriginal(boolean on) { original = on; invalidate(); }

  @Override public boolean onTouchEvent(MotionEvent e) {
    scale.onTouchEvent(e); gest.onTouchEvent(e);
    int a = e.getActionMasked();
    if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && original) { original = false; invalidate(); }
    return true;
  }

  @Override protected void onSizeChanged(int w, int h, int ow, int oh) { fitted = false; }

  @Override protected void onDraw(Canvas c) {
    if (!fitted && getWidth() > 0) {
      float s = fitScale(); m.reset(); m.postScale(s, s); clampPan(); fitted = true;
    }
    c.save(); c.concat(m);
    c.drawBitmap(bm, 0, 0, bmp);
    if (!original) {
      for (Block b : blocks) if (b.patch != null) {                  // сначала все заплатки…
        Matrix pm = new Matrix();
        pm.postScale((float) (b.w + 2) / b.patch.getWidth(), (float) (b.h + 2) / b.patch.getHeight());
        pm.postTranslate((float) (-(b.w + 2) / 2), (float) (-(b.h + 2) / 2));
        pm.postRotate(b.deg); pm.postTranslate((float) b.cx, (float) b.cy);
        c.drawBitmap(b.patch, pm, bmp);
      }
      for (Block b : blocks) if (b.layout != null) {                 // …потом весь текст: заплатка соседа его не срежет
        c.save(); c.translate((float) b.cx, (float) b.cy); c.rotate(b.deg);
        c.translate(-b.lw / 2, -b.layout.getHeight() / 2f);
        b.layout.draw(c); c.restore();
      }
    }
    c.restore();
  }
}
