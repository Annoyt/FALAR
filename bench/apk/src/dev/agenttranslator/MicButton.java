package dev.agenttranslator;

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
 *  Две позиции. Отпущена: всё неподвижно, круг сливовый, микрофон и надписи золотые. Нажата (идёт
 *  запись): круг окрашен тем, как слышно (Hearing.Live): зелёный — громкости хватает, чем
 *  краснее — тем тише и тем вероятнее ошибки. Цвет — по речи с начала записи, в паузах между
 *  словами и фразами он держится. Пока речи не было, цвета нет: круг сливовый, свечение и
 *  надписи мятные; молчат полторы секунды — краснеет: «не слышу». Вокруг — облако
 *  того же цвета: чем громче голос, тем дальше оно расплывается — при обычной речи до 2,7 радиуса
 *  круга, при громкой до трёх (владелец 29.09: при обычной речи — «хотя бы на 50 % больше»). Слов на
 *  экране нет — как слышно словами уходит в журнал. Середину закрывает палец, поэтому главное —
 *  цвет края и свечение.
 *
 *  Кнопка лежит поверх реплик, а не в своём ряду, поэтому круг полупрозрачный — текст за ним
 *  читается, — а касание мимо круга достаётся списку под ним.
 *
 *  Шаг — не чаще раза в 30 мс, и только когда цвет или размер сдвинулись: любое движение
 *  перерисовывает весь экран (results/2026-09-29-mic-button.md), дороже всего именно это.
 *  Рисуется кодом, а не картинкой: текст по окружности, кириллица, любая плотность экрана. */
public class MicButton extends FrameLayout {
  static final int PLUM = 0xFF802244, DEEP = 0xFF3D0C2A, GOLD = 0xFFE0B878, MINT = 0xFF6FE0BC;
  static final String PT = "FALAR", RU = "ГОВОРИ";
  /** Доли половины стороны: край круга, середина строки кольца, разделитель, микрофон. */
  static final float DISC = 0.86f, TEXT = 0.68f, INNER = 0.53f, ICON = 0.40f;
  /** Облако: от края круга наружу до (1 + GLOW) его радиуса при громком голосе (уровень −15 dBFS),
   *  при обычной речи (пик около −22) — до 2,7 радиуса, в тишине — кайма GLOW_MIN. Выходит далеко за
   *  край вида и за край списка — ни кнопка, ни её родители детей не режут. */
  static final float GLOW = 2.0f, GLOW_MIN = 0.08f;
  /** Непрозрачность круга: за ним видны реплики. */
  static final int GLASS = 0xB8;
  static final long PULSE_MS = 1200, SPIN_MS = 4000, STEP_NS = 30_000_000L;

  final Halo halo; final Face face; final Ring ring;
  boolean active = false;
  /** Как двигаться в нажатой позиции. pulse30 — рабочий: свечение по уровню входа, а без уровня
   *  (стенд) — ровный пульс. spin — кольцо надписей вращается, none — без движения; оба только
   *  для замера на другом телефоне (measure_mic_anim.sh). */
  String kind = "pulse30";
  ValueAnimator anim;
  final TimeInterpolator pulseIn = new DecelerateInterpolator();

  /** Шаг по кадрам экрана, но не чаще раза в 30 мс: на 60 Гц — каждый второй кадр, на 120 Гц —
   *  каждый четвёртый. Время из кадра, а не из часов: шаг ровно кратен кадру и не дрожит. */
  boolean ticking = false; long tickFrom = -1, tickLast = 0;
  /** Уровень (размер свечения) и как слышно (цвет), 0…1 (−1 — цвета ещё нет), из setLevel.
   *  Сглажены и округлены: в ровной тишине ничего не меняется, и кадров нет. */
  volatile float levelK = 0, levelQ = 0; volatile long levelAt = 0; float shownK = -1, shownQ = -1;
  final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
    @Override public void doFrame(long ns) {
      if (!ticking) return;
      if (tickFrom < 0) tickFrom = ns;
      if (ns - tickLast >= STEP_NS) {
        tickLast = ns;
        if (android.os.SystemClock.uptimeMillis() - levelAt < 500) {
          shownK = shownK < 0 ? levelK : shownK + (levelK - shownK) * 0.4f;
          shownQ = levelQ < 0 ? -1 : shownQ < 0 ? levelQ : shownQ + (levelQ - shownQ) * 0.4f;
          voice(Math.round(shownK * 48) / 48f, shownQ < 0 ? -1 : Math.round(shownQ * 24) / 24f);
        } else pulse(pulseIn.getInterpolation((ns - tickFrom) / 1_000_000L % PULSE_MS / (float) PULSE_MS));
      }
      Choreographer.getInstance().postFrameCallback(this);
    }
  };

  /** Уровень входа, dBFS (−55 — свечение у края круга, −15 и громче — во всю ширь), и как
   *  слышно, 0…1 (Hearing.Live): 1 — зелёный, 0 — красный, меньше нуля — цвета ещё нет (мятный). */
  public void setLevel(float db, float q) {
    levelK = Math.max(0, Math.min(1, (db + 55) / 40)); levelQ = q < 0 ? -1 : Math.min(1, q);
    levelAt = android.os.SystemClock.uptimeMillis();
  }

  /** Цвет «как слышно»: оттенок от красного (0) через жёлтый к зелёному (1). */
  static int qColor(float q, float s, float v) { return Color.HSVToColor(new float[]{120 * q, s, v}); }

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
      case "spin":
        anim = ValueAnimator.ofFloat(0, 360);
        anim.addUpdateListener(x -> { ring.angle = (float) x.getAnimatedValue(); ring.invalidate(); });
        anim.setDuration(SPIN_MS); anim.setInterpolator(new LinearInterpolator()); anim.setRepeatCount(ValueAnimator.INFINITE); anim.start();
        break;
    }
  }

  /** Шаг голоса: k — насколько далеко свечение, q — его цвет и цвет круга (−1 — мятное свечение,
   *  сливовый круг: речи ещё не было). */
  void voice(float k, float q) {
    int c = q < 0 ? MINT : qColor(q, 0.75f, 0.80f);
    halo.set(GLOW_MIN + (GLOW - GLOW_MIN) * k, c, 1);
    face.setQ(q); ring.setQ(q);
  }

  /** Стенд, без уровня: мятное свечение расходится и гаснет. */
  void pulse(float k) { halo.set(GLOW_MIN + (GLOW - GLOW_MIN) * k, MINT, 1 - k); }

  /** Остановить движение и вернуть отпущенный вид: свечение спрятано, круг сливовый, надписи прямо. */
  void stop() {
    if (anim != null) { anim.cancel(); anim = null; }
    if (ticking) { ticking = false; Choreographer.getInstance().removeFrameCallback(tick); }
    halo.setVisibility(INVISIBLE); halo.set(0, MINT, 0);
    shownK = shownQ = -1; levelAt = 0; face.setQ(-1); ring.setQ(-1);
    if (ring.angle != 0) { ring.angle = 0; ring.invalidate(); }
  }

  /** Стенд: нажатая позиция на ms миллисекунд без записи — замер движения (measure_mic_anim.sh);
   *  idle — только показать кнопку: её можно нажать по-настоящему (test_mic_device.sh). */
  public void demo(String k, long ms, Runnable done) {
    final String was = kind; final boolean show = !"idle".equals(k);
    if (show) { setActive(false); kind = k; setActive(true); }
    postDelayed(() -> { if (show) { setActive(false); kind = was; } if (done != null) done.run(); }, ms);
  }

  /** Круг, тонкий разделитель и микрофон. Отпущена — сливовый круг и золото; нажата — круг цвета
   *  «как слышно» и белые линии (на любом цвете читаются), пока цвета нет — прежние мятные. */
  final class Face extends View {
    final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF body = new RectF(9, 2, 15, 15); final Path yoke = new Path();
    Shader plum; float q = -1;
    Face(Context c) {
      super(c);
      ink.setStyle(Paint.Style.STROKE); ink.setStrokeCap(Paint.Cap.ROUND); ink.setStrokeJoin(Paint.Join.ROUND);
      // Микрофон в сетке 24×24, как системные значки: тело 6×13, дужка радиусом 7, ножка.
      yoke.moveTo(19, 10); yoke.lineTo(19, 12); yoke.arcTo(new RectF(5, 5, 19, 19), 0, 180, false); yoke.lineTo(5, 10);
    }
    /** Цвет «как слышно», 0…1; −1 — нет. Перекраска — только когда он сдвинулся. */
    void setQ(float v) { if (v != q) { q = v; shade(); invalidate(); } }
    void shade() {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
      disc.setShader(q < 0 ? plum : new LinearGradient(cx - r, cy - r, cx + r, cy + r,
          qColor(q, 0.62f, 0.86f), qColor(q, 0.85f, 0.50f), Shader.TileMode.CLAMP));
      disc.setAlpha(GLASS);
    }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
      float cx = w / 2f, cy = h / 2f, r = Math.min(cx, cy);
      plum = new LinearGradient(cx - r, cy - r, cx + r, cy + r, PLUM, DEEP, Shader.TileMode.CLAMP);
      shade();
    }
    @Override protected void onDraw(Canvas cv) {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
      cv.drawCircle(cx, cy, r * DISC, disc);
      int line = !active ? GOLD : q < 0 ? MINT : Color.WHITE;
      ink.setColor(line); ink.setAlpha(0x59); ink.setStrokeWidth(r * 0.012f);
      cv.drawCircle(cx, cy, r * INNER, ink);
      float s = r * ICON / 11f;                       // от середины сетки до края значка — 11 клеток
      cv.save(); cv.translate(cx - 12 * s, cy - 12 * s); cv.scale(s, s);
      ink.setAlpha(0xFF); ink.setStrokeWidth(2f);
      if (active) { fill.setColor(line); cv.drawRoundRect(body, 3, 3, fill); }
      cv.drawRoundRect(body, 3, 3, ink); cv.drawPath(yoke, ink); cv.drawLine(12, 19, 12, 22, ink);
      cv.restore();
    }
  }

  /** Кольцо надписей. Верхняя идёт по часовой — буквы головой наружу, нижняя против — головой
   *  к центру: так обе читаются слева направо, и ни одна не стоит вверх ногами. */
  final class Ring extends View {
    final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG); final Path top = new Path(), bottom = new Path();
    float topAt, bottomAt, angle = 0, q = -1;
    Ring(Context c) {
      super(c);
      ink.setTypeface(Typeface.DEFAULT_BOLD); ink.setLetterSpacing(0.18f);
    }
    void setQ(float v) { boolean was = q < 0, now = v < 0; q = v; if (was != now) invalidate(); }
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
      ink.setColor(!active ? GOLD : q < 0 ? MINT : Color.WHITE);
      cv.drawTextOnPath(PT, top, topAt, 0, ink);
      cv.drawTextOnPath(RU, bottom, bottomAt, 0, ink);
      cv.drawCircle(cx - r * TEXT, cy, r * 0.03f, ink); cv.drawCircle(cx + r * TEXT, cy, r * 0.03f, ink);
      cv.restore();
    }
  }

  /** Свечение вокруг круга: от его края наружу, радиальной растушёвкой в прозрачность. Начинается
   *  ровно у края — под полупрозрачный круг не заходит и не красит его. Перерисовывается, только
   *  когда сдвинулись размер, цвет или прозрачность. */
  final class Halo extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    float ext = 0, alpha = 0; int color = MINT;
    Halo(Context c) { super(c); setVisibility(INVISIBLE); }
    void set(float e, int c, float a) {
      if (e == ext && c == color && a == alpha) return;
      ext = e; color = c; alpha = a; invalidate();
    }
    @Override public boolean hasOverlappingRendering() { return false; }
    @Override protected void onDraw(Canvas cv) {
      if (ext <= 0 || alpha <= 0) return;
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r0 = Math.min(cx, cy) * DISC, r1 = r0 * (1 + ext);
      int a0 = Math.round(0xA6 * alpha), c0 = (color & 0xFFFFFF) | (a0 << 24), cz = color & 0xFFFFFF;
      p.setShader(new RadialGradient(cx, cy, r1, new int[]{cz, cz, c0, cz},
          new float[]{0, r0 / r1 - 0.001f, r0 / r1, 1}, Shader.TileMode.CLAMP));
      cv.drawCircle(cx, cy, r1, p);
    }
  }
}
