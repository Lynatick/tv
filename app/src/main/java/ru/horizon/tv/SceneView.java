package ru.horizon.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** A lightweight, static landscape: no bitmap allocation, videos or continuous animation. */
final class SceneView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final String theme;

  SceneView(Context context, String theme) {
    super(context);
    this.theme = theme;
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  SceneView(Context context) {
    this(context, "forest");
  }

  @android.annotation.SuppressLint("DrawAllocation")
  @Override
  protected void onDraw(Canvas canvas) {
    float w = getWidth(), h = getHeight();
    boolean dusk = theme.equals("dusk"), plain = theme.equals("plain");
    int top = Color.rgb(9, 23, 30), bottom = dusk ? Color.rgb(56, 31, 43) : Color.rgb(14, 48, 53);
    paint.setShader(
        new LinearGradient(0, 0, w, h, top, plain ? top : bottom, Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, w, h, paint);
    if (plain) {
      paint.setShader(null);
      return;
    }
    paint.setShader(
        new RadialGradient(
            w * .76f,
            h * .36f,
            w * .6f,
            dusk ? 0x485C546A : 0x405A9C8F,
            0x000C2026,
            Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, w, h, paint);
    paint.setShader(null);
    paint.setColor(dusk ? 0xFF273039 : 0xFF17383E);
    Path far = new Path();
    far.moveTo(0, h * .84f);
    far.cubicTo(w * .15f, h * .63f, w * .21f, h * .88f, w * .37f, h * .63f);
    far.cubicTo(w * .54f, h * .36f, w * .62f, h * .72f, w * .78f, h * .51f);
    far.cubicTo(w * .90f, h * .34f, w * .94f, h * .57f, w, h * .52f);
    far.lineTo(w, h);
    far.lineTo(0, h);
    far.close();
    canvas.drawPath(far, paint);
    paint.setColor(dusk ? 0xFF18232C : 0xFF0D282F);
    Path near = new Path();
    near.moveTo(0, h * .89f);
    near.cubicTo(w * .22f, h * .65f, w * .29f, h * .99f, w * .53f, h * .75f);
    near.cubicTo(w * .73f, h * .54f, w * .77f, h * .88f, w, h * .65f);
    near.lineTo(w, h);
    near.lineTo(0, h);
    near.close();
    canvas.drawPath(near, paint);
    paint.setShader(
        new LinearGradient(0, h * .5f, 0, h, 0x00061219, 0xBD061219, Shader.TileMode.CLAMP));
    canvas.drawRect(0, h * .5f, w, h, paint);
    paint.setShader(null);
  }
}
