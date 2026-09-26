package com.dolphin.launcher.v1;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Decorative TPMS wheel gauge. It does not infer safe/unsafe pressure thresholds. */
public final class TyreGaugeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private boolean live;

    public TyreGaugeView(Context context, boolean live) {
        super(context);
        this.live = live;
        setWillNotDraw(false);
    }

    public void setLive(boolean live) {
        this.live = live;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w=getWidth(), h=getHeight();
        if(w<=0f||h<=0f)return;
        float s=Math.min(w,h);
        float cx=w*0.5f, cy=h*0.5f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(120, 8, 21, 27));
        canvas.drawCircle(cx,cy,s*0.39f,paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.2f));
        paint.setColor(Color.argb(65, 113, 255, 224));
        canvas.drawCircle(cx,cy,s*0.32f,paint);

        paint.setStrokeWidth(dp(4f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(live
                ? Color.rgb(111,255,223)
                : Color.argb(115,126,158,169));
        arc.set(cx-s*0.32f,cy-s*0.32f,cx+s*0.32f,cy+s*0.32f);
        canvas.drawArc(arc,215f,live?250f:92f,false,paint);

        paint.setStrokeWidth(dp(2f));
        paint.setColor(Color.argb(190, 166, 221, 228));
        canvas.drawCircle(cx,cy,s*0.17f,paint);

        for(int i=0;i<4;i++){
            double a=Math.PI*i/2.0;
            float x1=cx+(float)Math.cos(a)*s*0.19f;
            float y1=cy+(float)Math.sin(a)*s*0.19f;
            float x2=cx+(float)Math.cos(a)*s*0.27f;
            float y2=cy+(float)Math.sin(a)*s*0.27f;
            canvas.drawLine(x1,y1,x2,y2,paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(live?Color.rgb(111,255,223):Color.rgb(108,131,140));
        canvas.drawCircle(cx,cy,s*0.055f,paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private float dp(float value){
        return value*getResources().getDisplayMetrics().density;
    }
}
