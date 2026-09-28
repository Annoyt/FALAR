package dev.agenttranslator;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/** Кнопка удержания: микрофон в сливовом круге, по кольцу — FALAR сверху и ГОВОРИ снизу, обе
 *  надписи стоят прямо. Кнопка принимает оба языка, поэтому и зовёт на обоих. Цвета — из иконки
 *  приложения: слива, золотая линия, мятный «звук».
 *
 *  Две позиции. Отпущена: всё неподвижно, микрофон и надписи золотые. Нажата (идёт запись):
 *  микрофон и надписи мятные, от края круга расходится и гаснет мятное кольцо. Середину в это
 *  время закрывает палец, поэтому запись видна снаружи круга, а не по значку.
 *
 *  Кнопка лежит поверх реплик, а не в своём ряду, поэтому круг полупрозрачный — текст за ним
 *  читается, — а касание мимо круга достаётся списку под ним.
 *
 *  Пульс, а не вращение кольца, и 30 кадров в секунду, а не частота экрана, — по замеру на
 *  телефоне (results/2026-09-29-mic-button.md): любое движение перерисовывает весь экран на каждом
 *  кадре, и дороже всего именно это, а не вид движения. Пульс дешевле вращения, вращение
 *  закэшированного слоя — дороже и неустойчивее всего. Рисуется кодом, а не картинкой: текст по
 *  окружности, кириллица, любая плотность экрана и ни байта в APK. */
public class MicButton extends FrameLayout {
  static final int PLUM = 0xFF802244, DEEP = 0xFF3D0C2A, GOLD = 0xFFE0B878, MINT = 0xFF6FE0BC;
  static final String PT = "FALAR", RU = "ГОВОРИ";
  /** Доли половины стороны: край круга, середина строки кольца, разделитель, микрофон. */
  static final float DISC = 0.86f, TEXT = 0.68f, INNER = 0.53f, ICON = 0.40f;
  /** Кольцо пульса: толщина и во сколько раз вырастает. Выходит за край вида на 0.15 половины
   *  стороны — поэтому ни кнопка, ни её родитель не режут детей по своим границам. */
  static final float HALO_W = 0.08f, HALO_GROW = 1.22f;
  /** Непрозрачность круга: за ним видны реплики. */
  static final int GLASS = 0xB8;
  static final long PULSE_MS = 1200, SPIN_MS = 4000, STEP_NS = 30_000_000L;

  final Halo halo; final Face face; final Ring ring;
  boolean active = false;
  /** Как двигаться в нажатой позиции. pulse30 — рабочий. Остальные — для замера на другом
   *  телефоне (measure_mic_anim.sh): pulse — тот же пульс на каждом кадре экрана, spin — кольцо
   *  надписей вращается, none — без движения. */
  String kind = "pulse30";
  ValueAnimator anim;
  final TimeInterpolator pulseIn = new DecelerateInterpolator();

  /** pulse30: свойства ореола ставятся по кадрам экрана, но не чаще раза в 30 мс — на 60 Гц это
   *  каждый второй кадр, на 120 Гц каждый четвёртый. Время берётся из кадра, а не из часов:
   *  шаг ровно кратен кадру и не дрожит. */
  boolean ticking = false; long tickFrom = -1, tickLast = 0;
  final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
    @Override public void doFrame(long ns) {
      if (!ticking) return;
      if (tickFrom < 0) tickFrom = ns;
      if (ns - tickLast >= STEP_NS) {
        tickLast = ns;
        pulse(pulseIn.getInterpolation((ns - tickFrom) / 1_000_000L % PULSE_MS / (float) PULSE_MS));
      }
      Choreographer.getInstance().postFrameCallback(this);
    }
  };

  public MicButton(Context c) {
    super(c);
    addView(halo = new Halo(c)); addView(face = new Face(c)); addView(ring = new Ring(c));
    setClickable(true); setClipChildren(false);
  }

  /** Касание мимо круга — не кнопке: вокруг круга видны реплики, их надо уметь листать и нажимать. */
  @Override public boolean dispatchTouchEvent(MotionEvent e) {
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      float dx = e.getX() - getWidth() / 2f, dy = e.getY() - getHeight() / 2f, r = Math.min(getWidth(), getHeight()) / 2f * DISC;
      if (dx * dx + dy * dy > r * r) return false;
    }
    return super.dispatchTouchEvent(e);
  }

  /** Спрятанная кнопка (другой режим, приложение ушло с экрана) не двигается впустую. */
  @Override public void onVisibilityAggregated(boolean visible) {
    super.onVisibilityAggregated(visible);
    if (!visible && active) setActive(false);
  }

  @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }

  @Override public void setEnabled(boolean on) {
    super.setEnabled(on);
    setAlpha(on ? 1f : 0.4f);
    if (!on) setActive(false);
  }

  /** Нажата или отпущена. Движение идёт только в нажатой позиции: запись длится секунды, а
   *  распознаванию после отпускания ничто не должно мешать. */
  public void setActive(boolean on) {
    if (on == active) return;
    active = on;
    face.invalidate(); ring.invalidate();
    stop();
    if (!on) return;
    switch (kind) {
      case "pulse30":
        halo.setVisibility(VISIBLE); ticking = true; tickFrom = -1; tickLast = 0;
        Choreographer.getInstance().postFrameCallback(tick);
        break;
      case "pulse":
        halo.setVisibility(VISIBLE);
        anim = ObjectAnimator.ofPropertyValuesHolder(halo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, HALO_GROW),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, HALO_GROW),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f));
        loop(PULSE_MS, pulseIn);
        break;
      case "spin":
        anim = ValueAnimator.ofFloat(0, 360);
        anim.addUpdateListener(x -> { ring.angle = (float) x.getAnimatedValue(); ring.invalidate(); });
        loop(SPIN_MS, new LinearInterpolator());
        break;
    }
  }

  void loop(long ms, TimeInterpolator in) {
    anim.setDuration(ms); anim.setInterpolator(in); anim.setRepeatCount(ValueAnimator.INFINITE); anim.start();
  }

  /** Шаг пульса: k от 0 до 1 — кольцо от края круга наружу, от яркого к пустому. */
  void pulse(float k) {
    float g = 1 + (HALO_GROW - 1) * k;
    halo.setScaleX(g); halo.setScaleY(g); halo.setAlpha(1 - k);
  }

  /** Остановить движение и вернуть отпущенный вид: ореол спрятан, надписи стоят прямо. */
  void stop() {
    if (anim != null) { anim.cancel(); anim = null; }
    if (ticking) { ticking = false; Choreographer.getInstance().removeFrameCallback(tick); }
    halo.setVisibility(INVISIBLE); pulse(0);
    if (ring.angle != 0) { ring.angle = 0; ring.invalidate(); }
  }

  /** Стенд: нажатая позиция на ms миллисекунд без записи — замер движения (measure_mic_anim.sh);
   *  idle — только показать кнопку: её можно нажать по-настоящему (test_mic_device.sh). */
  public void demo(String k, long ms, Runnable done) {
    final String was = kind; final boolean show = !"idle".equals(k);
    if (show) { setActive(false); kind = k; setActive(true); }
    postDelayed(() -> { if (show) { setActive(false); kind = was; } if (done != null) done.run(); }, ms);
  }

  /** Сливовый круг, тонкий разделитель и микрофон. Перерисовывается только при смене позиции. */
  final class Face extends View {
    final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF body = new RectF(9, 2, 15, 15); final Path yoke = new Path();
    Face(Context c) {
      super(c);
      ink.setStyle(Paint.Style.STROKE); ink.setStrokeCap(Paint.Cap.ROUND); ink.setStrokeJoin(Paint.Join.ROUND);
      fill.setColor(MINT);
      // Микрофон в сетке 24×24, как системные значки: тело 6×13, дужка радиусом 7, ножка.
      yoke.moveTo(19, 10); yoke.lineTo(19, 12); yoke.arcTo(new RectF(5, 5, 19, 19), 0, 180, false); yoke.lineTo(5, 10);
    }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
      float cx = w / 2f, cy = h / 2f, r = Math.min(cx, cy);
      disc.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, PLUM, DEEP, Shader.TileMode.CLAMP));
      disc.setAlpha(GLASS);
    }
    @Override protected void onDraw(Canvas cv) {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
      cv.drawCircle(cx, cy, r * DISC, disc);
      ink.setColor(active ? MINT : GOLD); ink.setAlpha(0x59); ink.setStrokeWidth(r * 0.012f);
      cv.drawCircle(cx, cy, r * INNER, ink);
      float s = r * ICON / 11f;                       // от середины сетки до края значка — 11 клеток
      cv.save(); cv.translate(cx - 12 * s, cy - 12 * s); cv.scale(s, s);
      ink.setAlpha(0xFF); ink.setStrokeWidth(2f);
      if (active) cv.drawRoundRect(body, 3, 3, fill);
      cv.drawRoundRect(body, 3, 3, ink); cv.drawPath(yoke, ink); cv.drawLine(12, 19, 12, 22, ink);
      cv.restore();
    }
  }

  /** Кольцо надписей. Верхняя идёт по часовой — буквы головой наружу, нижняя против — головой
   *  к центру: так обе читаются слева направо, и ни одна не стоит вверх ногами. */
  final class Ring extends View {
    final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG); final Path top = new Path(), bottom = new Path();
    float topAt, bottomAt, angle = 0;
    Ring(Context c) {
      super(c);
      ink.setTypeface(Typeface.DEFAULT_BOLD); ink.setLetterSpacing(0.18f);
    }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
      float cx = w / 2f, cy = h / 2f, r = Math.min(cx, cy);
      ink.setTextSize(r * 0.17f);
      Rect b = new Rect(); ink.getTextBounds(PT + RU, 0, PT.length() + RU.length(), b);
      float cap = -b.top, rt = r * TEXT - cap / 2, rb = r * TEXT + cap / 2;
      top.reset(); top.addArc(new RectF(cx - rt, cy - rt, cx + rt, cy + rt), 180, 180);
      bottom.reset(); bottom.addArc(new RectF(cx - rb, cy - rb, cx + rb, cy + rb), 180, -180);
      topAt = (float) (Math.PI * rt - ink.measureText(PT)) / 2;
      bottomAt = (float) (Math.PI * rb - ink.measureText(RU)) / 2;
    }
    @Override protected void onDraw(Canvas cv) {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
      cv.save(); cv.rotate(angle, cx, cy);
      ink.setColor(active ? MINT : GOLD);
      cv.drawTextOnPath(PT, top, topAt, 0, ink);
      cv.drawTextOnPath(RU, bottom, bottomAt, 0, ink);
      cv.drawCircle(cx - r * TEXT, cy, r * 0.03f, ink); cv.drawCircle(cx + r * TEXT, cy, r * 0.03f, ink);
      cv.restore();
    }
  }

  /** Мятное кольцо вплотную к кругу снаружи; в нажатой позиции расходится и гаснет (scale и
   *  alpha вида — содержимое не перерисовывается). Кольцо, а не диск: сквозь полупрозрачный круг
   *  диск красил бы его мятой. */
  final class Halo extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    Halo(Context c) { super(c); p.setColor(MINT); p.setAlpha(0xC0); p.setStyle(Paint.Style.STROKE); setVisibility(INVISIBLE); }
    @Override public boolean hasOverlappingRendering() { return false; }
    @Override protected void onDraw(Canvas cv) {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
      p.setStrokeWidth(r * HALO_W);
      cv.drawCircle(cx, cy, r * (DISC + HALO_W / 2), p);
    }
  }
}
