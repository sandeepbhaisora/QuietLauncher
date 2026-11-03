package dev.quiet.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Lightweight native aurora orb shared by Londe's launcher and voice console.
 * No GIF, video, network fetch, or model inference required for the animation.
 */
public final class LondeOrbView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean moving = true;
    private String state = "ready";
    private int mint = Color.rgb(154, 245, 191);
    private int background = Color.rgb(5, 19, 17);

    public LondeOrbView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Londe voice assistant orb");
    }
    public void setState(String value) {
        state = value == null ? "ready" : value;
        invalidate();
    }
    public void setPalette(int bg, int accent) {
        background = bg;
        mint = accent;
        invalidate();
    }
    public void pauseAnimation() { moving = false; invalidate(); }
    public void resumeAnimation() { moving = true; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final float cx = w / 2f, cy = h / 2f;
        final float r = Math.min(w,h) * .32f;
        final float now = SystemClock.uptimeMillis() / 1000f;
        final float active = state.equals("listening") || state.equals("thinking")
                || state.equals("speaking") ? 1f : .48f;
        final float oscillation = moving ? (float)Math.sin(now * 2.5f) : 0f;

        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, r * 1.58f,
                new int[]{0x5944F2AA, 0x1428AD87, Color.TRANSPARENT},
                new float[]{0f,.54f,1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx,cy,r*1.6f,p);
        p.setShader(null);

        p.setStyle(Paint.Style.STROKE);
        for(int i=0;i<3;i++) {
            p.setColor(mint);
            p.setAlpha((int)((70-i*17)*active));
            p.setStrokeWidth(Math.max(1f, r*.009f));
            canvas.drawCircle(cx,cy,r*(1.07f+i*.085f)+oscillation*r*.012f,p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xff0a2922);
        canvas.drawCircle(cx,cy,r,p);

        p.setShader(new RadialGradient(cx-r*.26f,cy-r*.45f,r*1.75f,
                new int[]{0xff22483d,0xff0b251f,0xff05150f},
                new float[]{0f,.68f,1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx,cy,r*.98f,p);
        p.setShader(null);

        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xcca4ffcd);
        p.setStrokeWidth(r*.028f);
        canvas.drawCircle(cx,cy,r*.98f,p);
        p.setStyle(Paint.Style.FILL);

        Path wave = new Path();
        wave.moveTo(cx-r*.92f,cy+r*.16f);
        wave.cubicTo(cx-r*.48f,cy-r*.24f+oscillation*r*.06f,
                cx-r*.10f,cy+r*.52f, cx+r*.26f,cy+r*.25f);
        wave.cubicTo(cx+r*.60f,cy-r*.02f, cx+r*.74f,cy+r*.05f,
                cx+r*.92f,cy+r*.16f);
        wave.lineTo(cx+r*.84f,cy+r*.47f);
        wave.cubicTo(cx+r*.40f,cy+r*.72f, cx-r*.22f,cy+r*.70f,
                cx-r*.84f,cy+r*.48f);
        wave.close();
        p.setShader(new LinearGradient(cx-r,cy, cx+r,cy+r,
                new int[]{0x3042EEA4,0xff62E6A0,0xffb9ffe0},
                new float[]{0f,.55f,1f},Shader.TileMode.CLAMP));
        canvas.drawPath(wave,p);
        p.setShader(null);

        // Small four-point sparkle at the orb's center.
        float star = r*.13f;
        Path sparkle = new Path();
        sparkle.moveTo(cx,cy-star*1.65f);
        sparkle.quadTo(cx+star*.24f,cy-star*.20f,cx+star*1.45f,cy);
        sparkle.quadTo(cx+star*.24f,cy+star*.20f,cx,cy+star*1.65f);
        sparkle.quadTo(cx-star*.24f,cy+star*.20f,cx-star*1.45f,cy);
        sparkle.quadTo(cx-star*.24f,cy-star*.20f,cx,cy-star*1.65f);
        p.setColor(0xffc8ffe0);
        p.setAlpha(242);
        canvas.drawPath(sparkle,p);
        p.setAlpha(255);

        if (moving && isAttachedToWindow()) postInvalidateDelayed(46);
    }
}
