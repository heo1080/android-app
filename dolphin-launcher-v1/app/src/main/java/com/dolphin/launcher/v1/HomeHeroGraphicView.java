package com.dolphin.launcher.v1;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Resolution-independent hero graphic for the V1 premium HMI home.
 * Pure Canvas/vector rendering keeps the 10-inch UI sharp without bitmap scaling.
 */
public final class HomeHeroGraphicView extends View {
    public static final int SOURCE_WAITING = 0;
    public static final int SOURCE_LIVE = 1;
    public static final int SOURCE_STALE = 2;
    public static final int SOURCE_BLOCKED = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int mediaState = SOURCE_WAITING;
    private int navState = SOURCE_WAITING;
    private int vehicleState = SOURCE_WAITING;

    public HomeHeroGraphicView(Context context) {
        super(context);
        setWillNotDraw(false);
        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
    }

    public void setSourceStates(int media, int nav, int vehicle) {
        int nextMedia=clampState(media);
        int nextNav=clampState(nav);
        int nextVehicle=clampState(vehicle);
        if(mediaState==nextMedia && navState==nextNav && vehicleState==nextVehicle)return;
        mediaState=nextMedia;
        navState=nextNav;
        vehicleState=nextVehicle;
        invalidate();
    }

    private int clampState(int state) {
        return Math.max(SOURCE_WAITING, Math.min(SOURCE_BLOCKED, state));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        drawAtmosphere(canvas, w, h);
        drawGlassHighlights(canvas, w, h);
        drawPerspectiveDeck(canvas, w, h);
        drawSensorField(canvas, w, h);
        drawVehicle(canvas, w, h);
        drawTelemetry(canvas, w, h);
    }

    private void drawAtmosphere(Canvas canvas, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                0f, 0f, w, h,
                new int[]{Color.rgb(13, 42, 52), Color.rgb(4, 15, 21), Color.rgb(1, 5, 8)},
                new float[]{0f, 0.54f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0f, 0f, w, h, dp(28), dp(28), paint);
        paint.setShader(null);

        // Layered ambient bloom gives the hero depth without encoding vehicle semantics.
        paint.setShader(new RadialGradient(
                w * 0.63f, h * 0.44f, Math.max(w, h) * 0.46f,
                new int[]{Color.argb(76, 55, 255, 220), Color.argb(22, 25, 116, 126), Color.TRANSPARENT},
                new float[]{0f, 0.44f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0f, 0f, w, h, dp(28), dp(28), paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(28, 127, 255, 224));
        for (int i = 1; i <= 5; i++) {
            float y = h * i / 6f;
            canvas.drawLine(w * 0.07f, y, w * 0.95f, y, paint);
        }

        // Subtle inner chrome. Decorative only: no lane/object/ADAS meaning.
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(Color.argb(78, 155, 255, 236));
        canvas.drawRoundRect(dp(1.5f), dp(1.5f), w - dp(1.5f), h - dp(1.5f),
                dp(27), dp(27), paint);
    }

    private void drawGlassHighlights(Canvas canvas, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                0f, 0f, w * 0.72f, h * 0.42f,
                new int[]{Color.argb(44, 255, 255, 255), Color.argb(10, 130, 255, 232), Color.TRANSPARENT},
                new float[]{0f, 0.40f, 1f}, Shader.TileMode.CLAMP));
        path.reset();
        path.moveTo(w * 0.035f, h * 0.05f);
        path.lineTo(w * 0.70f, h * 0.05f);
        path.lineTo(w * 0.52f, h * 0.23f);
        path.lineTo(w * 0.035f, h * 0.19f);
        path.close();
        canvas.drawPath(path, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(Color.argb(54, 94, 255, 224));
        path.reset();
        path.moveTo(w * 0.48f, h * 0.12f);
        path.lineTo(w * 0.93f, h * 0.12f);
        path.lineTo(w * 0.88f, h * 0.17f);
        canvas.drawPath(path, paint);
    }

    private void drawPerspectiveDeck(Canvas canvas, float w, float h) {
        // Decorative depth deck only. This is not a road/lane model and carries
        // no object, lane, drivable-space, or navigation semantics.
        float cx = w * 0.61f;
        float cy = h * 0.70f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 5; i++) {
            float rx = w * (0.13f + i * 0.055f);
            float ry = h * (0.055f + i * 0.026f);
            oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
            paint.setStrokeWidth(dp(i == 0 ? 1.7f : 1.0f));
            paint.setColor(Color.argb(72 - i * 9, 100, 246, 224));
            canvas.drawArc(oval, 12f, 156f, false, paint);
            canvas.drawArc(oval, 192f, 156f, false, paint);
        }

        // Short lateral depth ticks create perspective without forming lane lines.
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(46, 116, 212, 216));
        for (int i = 0; i < 6; i++) {
            float spread = w * (0.12f + i * 0.035f);
            float y = h * (0.48f + i * 0.072f);
            float tick = w * (0.014f + i * 0.003f);
            canvas.drawLine(cx - spread - tick, y, cx - spread, y, paint);
            canvas.drawLine(cx + spread, y, cx + spread + tick, y, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawSensorField(Canvas canvas, float w, float h) {
        float cx = w * 0.61f;
        float cy = h * 0.61f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        for (int i = 0; i < 4; i++) {
            float radius = Math.min(w, h) * (0.20f + i * 0.075f);
            paint.setColor(Color.argb(52 - i * 7, 79, 255, 213));
            oval.set(cx - radius, cy - radius * 0.62f, cx + radius, cy + radius * 0.62f);
            canvas.drawArc(oval, 205f, 130f, false, paint);
        }

        // Decorative sensor-range arcs only. FSD_OBJECT_LANE_MODEL is BLOCKED,
        // so this hero must never render fixed dots/rings that could be mistaken
        // for detected people, vehicles, lanes, or other semantic objects.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(44, 89, 255, 218));
        for (int i = 0; i < 5; i++) {
            float x = w * (0.47f + i * 0.07f);
            float y = h * 0.885f;
            canvas.drawLine(x, y, x, y - dp(4), paint);
        }
    }

    private void drawVehicle(Canvas canvas, float w, float h) {
        float cx = w * 0.61f;
        float top = h * 0.39f;
        float bottom = h * 0.84f;
        float half = w * 0.092f;

        path.reset();
        path.moveTo(cx, top);
        path.cubicTo(cx - half * 0.90f, top + h * 0.025f,
                cx - half, top + h * 0.10f,
                cx - half * 1.08f, top + h * 0.18f);
        path.lineTo(cx - half * 0.92f, bottom - h * 0.035f);
        path.cubicTo(cx - half * 0.55f, bottom,
                cx + half * 0.55f, bottom,
                cx + half * 0.92f, bottom - h * 0.035f);
        path.lineTo(cx + half * 1.08f, top + h * 0.18f);
        path.cubicTo(cx + half, top + h * 0.10f,
                cx + half * 0.90f, top + h * 0.025f, cx, top);
        path.close();

        // Ego-vehicle sculpture only; it is not a detected object.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(78, 36, 255, 220));
        oval.set(cx - half * 1.34f, bottom - h * 0.025f,
                cx + half * 1.34f, bottom + h * 0.045f);
        canvas.drawOval(oval, paint);

        paint.setShader(new LinearGradient(
                cx - half, top, cx + half, bottom,
                new int[]{Color.rgb(39, 82, 94), Color.rgb(10, 27, 34), Color.rgb(30, 67, 77)},
                null, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(Color.argb(190, 134, 248, 231));
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(185, 5, 17, 22));
        float glassTop = top + h * 0.07f;
        path.reset();
        path.moveTo(cx, glassTop);
        path.lineTo(cx - half * 0.66f, glassTop + h * 0.075f);
        path.lineTo(cx - half * 0.58f, glassTop + h * 0.16f);
        path.lineTo(cx + half * 0.58f, glassTop + h * 0.16f);
        path.lineTo(cx + half * 0.66f, glassTop + h * 0.075f);
        path.close();
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(86, 150, 255, 236));
        canvas.drawLine(cx - half * 0.54f, glassTop + h * 0.165f,
                cx + half * 0.54f, glassTop + h * 0.165f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(107, 255, 225));
        canvas.drawRoundRect(
                cx - half * 0.68f, bottom - h * 0.055f,
                cx - half * 0.23f, bottom - h * 0.038f,
                dp(4), dp(4), paint);
        canvas.drawRoundRect(
                cx + half * 0.23f, bottom - h * 0.055f,
                cx + half * 0.68f, bottom - h * 0.038f,
                dp(4), dp(4), paint);
    }

    private void drawTelemetry(Canvas canvas, float w, float h) {
        textPaint.setColor(Color.argb(235, 224, 248, 246));
        textPaint.setTextSize(dp(12));
        canvas.drawText("COCKPIT READY", w * 0.73f, h * 0.18f, textPaint);

        textPaint.setColor(Color.argb(150, 150, 184, 190));
        textPaint.setTextSize(dp(9.5f));
        canvas.drawText("VECTOR HMI  /  SOURCE LAYER", w * 0.73f, h * 0.235f, textPaint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(sourceColor(vehicleState, 230));
        canvas.drawCircle(w * 0.705f, h * 0.165f, dp(3), paint);

        paint.setColor(Color.argb(92, 91, 255, 219));
        for (int i = 0; i < 5; i++) {
            float barH = h * (0.035f + i * 0.012f);
            float left = w * (0.78f + i * 0.025f);
            canvas.drawRoundRect(left, h * 0.82f - barH, left + dp(5), h * 0.82f,
                    dp(2), dp(2), paint);
        }

        drawSourceRail(canvas,w,h);
    }

    private void drawSourceRail(Canvas canvas,float w,float h) {
        String[] labels=new String[]{"MEDIA","NAV","VEH"};
        int[] states=new int[]{mediaState,navState,vehicleState};
        float startX=w*0.71f;
        float gap=w*0.085f;
        float y=h*0.91f;

        textPaint.setTextSize(dp(7.5f));
        for(int i=0;i<labels.length;i++){
            float x=startX+gap*i;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(sourceColor(states[i],220));
            canvas.drawCircle(x,y-dp(2),dp(2.8f),paint);
            textPaint.setColor(sourceColor(states[i],205));
            canvas.drawText(labels[i],x+dp(6),y,textPaint);
        }
    }

    private int sourceColor(int state,int alpha) {
        int r,g,b;
        if(state==SOURCE_LIVE){
            r=111; g=255; b=223;
        }else if(state==SOURCE_STALE){
            r=255; g=209; b=102;
        }else if(state==SOURCE_BLOCKED){
            r=255; g=138; b=128;
        }else{
            r=111; g=151; b=163;
        }
        return Color.argb(Math.max(0,Math.min(255,alpha)),r,g,b);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
