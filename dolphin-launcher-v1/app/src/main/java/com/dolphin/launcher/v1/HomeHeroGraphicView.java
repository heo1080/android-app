package com.dolphin.launcher.v1;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Resolution-independent hero graphic for the V1 premium HMI home.
 * Pure Canvas/vector rendering keeps the 10-inch UI sharp without bitmap scaling.
 */
public final class HomeHeroGraphicView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    public HomeHeroGraphicView(Context context) {
        super(context);
        setWillNotDraw(false);
        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        drawAtmosphere(canvas, w, h);
        drawRoad(canvas, w, h);
        drawSensorField(canvas, w, h);
        drawVehicle(canvas, w, h);
        drawTelemetry(canvas, w, h);
    }

    private void drawAtmosphere(Canvas canvas, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                0f, 0f, w, h,
                new int[]{Color.rgb(10, 34, 43), Color.rgb(3, 12, 17), Color.rgb(1, 6, 9)},
                new float[]{0f, 0.58f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0f, 0f, w, h, dp(28), dp(28), paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(26, 127, 255, 224));
        for (int i = 1; i <= 5; i++) {
            float y = h * i / 6f;
            canvas.drawLine(w * 0.07f, y, w * 0.95f, y, paint);
        }
    }

    private void drawRoad(Canvas canvas, float w, float h) {
        float horizonY = h * 0.23f;
        float centerX = w * 0.61f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.argb(105, 99, 255, 221));

        path.reset();
        path.moveTo(centerX - w * 0.055f, horizonY);
        path.lineTo(centerX - w * 0.30f, h * 0.98f);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(centerX + w * 0.055f, horizonY);
        path.lineTo(centerX + w * 0.30f, h * 0.98f);
        canvas.drawPath(path, paint);

        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(Color.argb(72, 136, 205, 219));
        for (int i = 0; i < 7; i++) {
            float t0 = 0.18f + i * 0.11f;
            float t1 = Math.min(0.96f, t0 + 0.05f);
            float y0 = horizonY + (h - horizonY) * t0;
            float y1 = horizonY + (h - horizonY) * t1;
            float x0 = centerX;
            canvas.drawLine(x0, y0, x0, y1, paint);
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

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(165, 89, 255, 218));
        float[][] points = new float[][]{
                {0.38f,0.50f},{0.83f,0.43f},{0.89f,0.66f},{0.33f,0.72f}
        };
        for (float[] p : points) {
            canvas.drawCircle(w * p[0], h * p[1], dp(3.2f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(70, 89, 255, 218));
            canvas.drawCircle(w * p[0], h * p[1], dp(8.5f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(165, 89, 255, 218));
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

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                cx - half, top, cx + half, bottom,
                new int[]{Color.rgb(31, 68, 80), Color.rgb(11, 28, 35), Color.rgb(26, 55, 65)},
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
        canvas.drawText("DRIVE READY", w * 0.73f, h * 0.18f, textPaint);

        textPaint.setColor(Color.argb(150, 150, 184, 190));
        textPaint.setTextSize(dp(9.5f));
        canvas.drawText("VECTOR HMI  /  LIVE LAYER", w * 0.73f, h * 0.235f, textPaint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(96, 255, 217));
        canvas.drawCircle(w * 0.705f, h * 0.165f, dp(3), paint);

        paint.setColor(Color.argb(92, 91, 255, 219));
        for (int i = 0; i < 5; i++) {
            float barH = h * (0.035f + i * 0.012f);
            float left = w * (0.78f + i * 0.025f);
            canvas.drawRoundRect(left, h * 0.82f - barH, left + dp(5), h * 0.82f,
                    dp(2), dp(2), paint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
