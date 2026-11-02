package dev.quiet.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Animated, battery-conscious HUD ornament; paused when detached from the window. */
public final class AiHudView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long start = android.os.SystemClock.uptimeMillis();
    private boolean animate = true;
    public AiHudView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
    public void setAnimationEnabled(boolean enabled) { animate = enabled; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w=getWidth(), h=getHeight(), cx=w/2f, cy=h/2f;
        float r=Math.min(w,h)*.32f;
        float t=animate ? (android.os.SystemClock.uptimeMillis()-start)/1000f : 0;
        canvas.drawColor(Color.TRANSPARENT);
        paint.setStyle(Paint.Style.STROKE);
        for(int i=0;i<3;i++){
            float rr=r*(.8f+i*.22f);
            paint.setStrokeWidth(i==0?3f:1.6f);
            paint.setColor(Color.argb(i==0?220:95,36,211,240));
            canvas.drawCircle(cx,cy,rr,paint);
        }
        for(int i=0;i<3;i++){
            paint.setColor(i==0?Color.rgb(77,231,255):Color.argb(150,79,189,241));
            paint.setStrokeWidth(i==0?4f:2f);
            float turn=(i%2==0?1:-1)*t*(14+i*10);
            canvas.drawArc(cx-r*(1+i*.22f),cy-r*(1+i*.22f),
                    cx+r*(1+i*.22f),cy+r*(1+i*.22f),turn,70+i*18,false,paint);
        }
        float pulse=(float)Math.sin(t*1.9f)*.07f+1f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(33,25,175,239));
        canvas.drawCircle(cx,cy,r*.72f*pulse,paint);
        paint.setColor(Color.rgb(96,238,255));
        canvas.drawCircle(cx-r*.19f,cy,r*.07f,paint);
        canvas.drawCircle(cx+r*.19f,cy,r*.07f,paint);
        paint.setStrokeWidth(2.7f);paint.setStyle(Paint.Style.STROKE);
        canvas.drawArc(cx-r*.23f,cy-r*.12f,cx+r*.23f,cy+r*.27f,20,140,false,paint);
        if(animate && getWindowToken()!=null)postInvalidateDelayed(33);
    }
}
