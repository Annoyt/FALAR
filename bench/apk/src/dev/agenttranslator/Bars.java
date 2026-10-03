package dev.agenttranslator;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Окно под системными полосами (edge-to-edge). С targetSdk 35 Android 15+ сам кладёт окно под строку
 *  состояния и полосу навигации, а их цвета из темы (statusBarColor, navigationBarColor) не действуют:
 *  без отступов шапка ушла бы под часы, а док — под полосу жестов. На Android 11–14 окно кладём под
 *  полосы сами: путь у всех один, и проверяется он на стенде — Redmi на Android 13. Отступы раздаёт
 *  MainActivity.insets(). Android 9–10 — по-старому: окно между полосами, полосы красит система по теме. */
final class Bars {
  private Bars() {}

  /** Положить окно под полосы; false — Android 9–10, окно остаётся между ними. */
  static boolean edgeToEdge(Window w) {
    if (Build.VERSION.SDK_INT < 30) return false;
    w.setDecorFitsSystemWindows(false);
    w.setStatusBarColor(Color.TRANSPARENT); w.setNavigationBarColor(Color.TRANSPARENT);
    // Без полупрозрачной подложки под кнопками навигации: под ними фон экрана, а цвет значков задаёт
    // тема (windowLightNavigationBar).
    w.setStatusBarContrastEnforced(false); w.setNavigationBarContrastEnforced(false);
    // Так Android 15+ делает и сам: в альбомной вырез камеры — сбоку, поле от него приходит в отступах.
    WindowManager.LayoutParams a = w.getAttributes();
    a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    w.setAttributes(a);
    return true;
  }

  /** Поля окна, px: слева, сверху, справа, снизу — полосы и вырез камеры; пятое — клавиатура от низа окна. */
  static int[] of(WindowInsets in) {
    android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
    return new int[]{s.left, s.top, s.right, s.bottom, in.getInsets(WindowInsets.Type.ime()).bottom};
  }

  /** Поля приходят виду v при каждом изменении (поворот, клавиатура) и дальше не идут. */
  static void listen(View v, java.util.function.Consumer<int[]> apply) {
    v.setOnApplyWindowInsetsListener((x, in) -> { apply.accept(of(in)); return WindowInsets.CONSUMED; });
  }

  /** Фон корня: цвет экрана, а под строкой состояния — её цвет из темы. Виден там, где шапки нет
   *  (экран первого запуска): белые значки строки иначе легли бы на белый фон. */
  static final class Bg extends Drawable {
    final Paint p = new Paint();
    final int base, bar;
    /** Высота строки состояния, px. */
    int top;
    Bg(int base, int bar) { this.base = base; this.bar = bar; }
    @Override public void draw(Canvas c) {
      Rect b = getBounds();
      p.setColor(base); c.drawRect(b, p);
      if (top > 0) { p.setColor(bar); c.drawRect(b.left, b.top, b.right, b.top + top, p); }
    }
    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(ColorFilter f) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
  }
}
