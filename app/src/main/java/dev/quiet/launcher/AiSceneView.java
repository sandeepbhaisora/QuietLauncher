package dev.quiet.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** Original, native, animated AI OS scene. No external assets or paid services. */
public final class AiSceneView extends View {
    public enum Scene { STANDBY, HUB, LISTENING, RESPONSE }
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Scene scene;
    private boolean running = true;
    private final long created = android.os.SystemClock.uptimeMillis();
    public AiSceneView(Context context, Scene scene) { super(context); this.scene=scene; }
    public void pause() { running=false; }
    @Override protected void onDetachedFromWindow(){running=false;super.onDetachedFromWindow();}
    private void stroke(int color,float width){p.setColor(color);p.setStrokeWidth(width);p.setStyle(Paint.Style.STROKE);}
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w=getWidth(),h=getHeight(),cx=w*.5f,cy=h*.49f;
        float scale=getResources().getDisplayMetrics().density;
        long ms=android.os.SystemClock.uptimeMillis()-created;
        float t=ms/1000f;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0,0,0,h,Color.rgb(4,17,32),Color.rgb(5,35,52),Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,p); p.setShader(null);
        // Fine technical grid and scene markers.
        stroke(Color.argb(22,38,196,232),scale*.65f);
        for(int i=1;i<12;i++){float x=w*i/12f;c.drawLine(x,0,x,h,p);}
        for(int i=1;i<15;i++){float y=h*i/15f;c.drawLine(0,y,w,y,p);}
        if(scene==Scene.STANDBY){
            Path mountains=new Path();mountains.moveTo(0,h);
            mountains.lineTo(0,h*.84f);mountains.lineTo(w*.19f,h*.71f);
            mountains.lineTo(w*.33f,h*.81f);mountains.lineTo(w*.62f,h*.66f);
            mountains.lineTo(w*.83f,h*.8f);mountains.lineTo(w,h*.72f);
            mountains.lineTo(w,h);mountains.close();
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(8,31,51));c.drawPath(mountains,p);
            stroke(Color.argb(130,43,157,201),scale);c.drawPath(mountains,p);
        }
        if(scene==Scene.HUB){
            float ry=cy+h*.22f;
            stroke(Color.argb(135,41,216,253),scale*1.5f);
            for(int i=0;i<5;i++)c.drawOval(cx-w*.35f+i*scale*5,ry-i*scale*8,
                    cx+w*.35f-i*scale*5,ry+i*scale*8,p);
            stroke(Color.argb(55,70,200,240),scale);
            c.drawCircle(cx,cy,w*.31f,p);
        }
        float r=Math.min(w*.35f,h*.36f);
        int glow=Color.rgb(48,218,255);
        p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(20,28,194,249));
        c.drawCircle(cx,cy,r*1.07f+(float)Math.sin(t*2)*scale*3,p);
        stroke(Color.argb(105,34,189,238),scale*2);
        c.drawCircle(cx,cy,r*1.1f,p);
        stroke(Color.argb(225,55,218,255),scale*2.5f);
        c.drawCircle(cx,cy,r*.91f,p);
        stroke(Color.argb(95,120,216,250),scale);
        c.drawCircle(cx,cy,r*.76f,p);
        for(int i=0;i<64;i++){
            double a=Math.toRadians(i*360.0/64+(scene==Scene.LISTENING?t*15:t*4));
            float inner=r*(i%8==0?1.14f:1.2f),outer=r*1.29f;
            stroke(Color.argb(i%8==0?190:70,69,211,253),i%8==0?scale*1.6f:scale);
            c.drawLine(cx+(float)Math.cos(a)*inner,cy+(float)Math.sin(a)*inner,
                    cx+(float)Math.cos(a)*outer,cy+(float)Math.sin(a)*outer,p);
        }
        for(int i=0;i<4;i++){
            stroke(i==3?Color.rgb(243,148,89):glow,scale*(i%2==0?4:2));
            float ar=r*(.95f+i*.11f);
            c.drawArc(cx-ar,cy-ar,cx+ar,cy+ar,t*(i%2==0?17:-22)+i*85,
                    36+i*11,false,p);
        }
        // Two expressive glowing eyes.
        stroke(Color.rgb(103,241,255),scale*4.2f);
        p.setStrokeCap(Paint.Cap.ROUND);
        for(int eye=-1;eye<=1;eye+=2){
            float ex=cx+eye*r*.29f;
            Path e=new Path();e.moveTo(ex-r*.13f,cy);
            e.quadTo(ex,cy+r*(scene==Scene.RESPONSE?-.10f:.10f),ex+r*.13f,cy);
            c.drawPath(e,p);
        }
        if(scene==Scene.LISTENING||scene==Scene.RESPONSE){
            float waveY=h*.89f;
            for(int i=0;i<29;i++){
                float x=cx+(i-14)*w*.019f;
                float envelope=1-Math.abs(i-14)/15f;
                float amp=scale*(8+28*envelope*Math.abs((float)Math.sin(t*5+i*.7f)));
                stroke(Color.argb(220,69,232,255),scale*2.5f);
                c.drawLine(x,waveY-amp,x,waveY+amp,p);
            }
        }
        if(running&&getWindowToken()!=null)postInvalidateDelayed(40);
    }
}
