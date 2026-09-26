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

/** Compact vector scene for the three primary cockpit panels. */
public final class CockpitPanelGraphicView extends View {
    public static final int MEDIA = 1;
    public static final int SAFETY = 2;
    public static final int VEHICLE = 3;

    public static final int SIGNAL_WAITING = 0;
    public static final int SIGNAL_LIVE = 1;
    public static final int SIGNAL_STALE = 2;
    public static final int SIGNAL_BLOCKED = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final int mode;
    private int signalState = SIGNAL_WAITING;

    public CockpitPanelGraphicView(Context context, int mode) {
        super(context);
        this.mode = mode;
        setWillNotDraw(false);
    }

    public void setSignalState(int state) {
        int next = Math.max(SIGNAL_WAITING, Math.min(SIGNAL_BLOCKED, state));
        if (signalState == next) return;
        signalState = next;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0f || h <= 0f) return;

        drawBackground(canvas, w, h);
        if (mode == MEDIA) drawMedia(canvas, w, h);
        else if (mode == SAFETY) drawSafety(canvas, w, h);
        else drawVehicle(canvas, w, h);
    }

    private void drawBackground(Canvas canvas, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                0f, 0f, w, h,
                new int[]{Color.rgb(17, 48, 58), Color.rgb(7, 20, 26), Color.rgb(3, 9, 13)},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0f, 0f, w, h, dp(22), dp(22), paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(28, 131, 255, 230));
        for (int i = 1; i <= 4; i++) {
            float y = h * i / 5f;
            canvas.drawLine(w * 0.06f, y, w * 0.94f, y, paint);
        }
    }

    private void drawMedia(Canvas canvas, float w, float h) {
        float base = h * 0.68f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent(160));
        for (int i = 0; i < 9; i++) {
            float x = w * (0.55f + i * 0.035f);
            float bh = h * (0.08f + (i % 4) * 0.035f);
            canvas.drawRoundRect(x, base - bh, x + dp(5), base, dp(2), dp(2), paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(accent(180));
        canvas.drawCircle(w * 0.76f, h * 0.32f, h * 0.14f, paint);

        paint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(w * 0.735f, h * 0.255f);
        path.lineTo(w * 0.815f, h * 0.32f);
        path.lineTo(w * 0.735f, h * 0.385f);
        path.close();
        paint.setColor(accent(210));
        canvas.drawPath(path, paint);
    }

    private void drawSafety(Canvas canvas, float w, float h) {
        // Non-semantic verification-boundary graphic only.
        // FSD_OBJECT_LANE_MODEL is BLOCKED, so do not draw road/lane guides,
        // fixed object dots/rings, warning targets, people, or vehicle detections.
        float cx = w * 0.76f;
        float cy = h * 0.50f;
        float radius = Math.min(w, h) * 0.19f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(1.4f));
        for (int i = 0; i < 3; i++) {
            float r = radius * (0.72f + i * 0.18f);
            rect.set(cx - r, cy - r * 0.68f, cx + r, cy + r * 0.68f);
            paint.setColor(accent(118 - i * 22));
            canvas.drawArc(rect, 205f, 130f, false, paint);
            canvas.drawArc(rect, 25f, 130f, false, paint);
        }

        // Shield outline communicates a safety boundary without implying live perception.
        float shieldW = w * 0.105f;
        float shieldTop = h * 0.28f;
        float shieldBottom = h * 0.68f;
        path.reset();
        path.moveTo(cx, shieldTop);
        path.lineTo(cx - shieldW, shieldTop + h * 0.06f);
        path.lineTo(cx - shieldW * 0.86f, h * 0.50f);
        path.quadTo(cx - shieldW * 0.60f, shieldBottom - h * 0.04f, cx, shieldBottom);
        path.quadTo(cx + shieldW * 0.60f, shieldBottom - h * 0.04f, cx + shieldW * 0.86f, h * 0.50f);
        path.lineTo(cx + shieldW, shieldTop + h * 0.06f);
        path.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(26, 116, 190, 204));
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(accent(180));
        canvas.drawPath(path, paint);

        // Padlock motif = semantic layer intentionally locked.
        float lockW = w * 0.060f;
        float lockTop = h * 0.43f;
        float lockBottom = h * 0.58f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.7f));
        paint.setColor(accent(190));
        rect.set(cx - lockW * 0.62f, lockTop - h * 0.075f,
                cx + lockW * 0.62f, lockTop + h * 0.045f);
        canvas.drawArc(rect, 190f, 160f, false, paint);
        rect.set(cx - lockW, lockTop, cx + lockW, lockBottom);
        canvas.drawRoundRect(rect, dp(5), dp(5), paint);

        // Abstract status ticks; no fixed detections or lane/object semantics.
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(accent(105));
        for (int i = 0; i < 5; i++) {
            float x = w * (0.61f + i * 0.075f);
            float y = h * 0.82f;
            float tick = h * (0.035f + (i % 2) * 0.014f);
            canvas.drawLine(x, y, x, y - tick, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawVehicle(Canvas canvas, float w, float h) {
        float cx = w * 0.76f;
        float top = h * 0.24f;
        float bottom = h * 0.78f;
        float half = w * 0.09f;

        path.reset();
        path.moveTo(cx, top);
        path.cubicTo(cx - half * 0.9f, top + h * 0.03f,
                cx - half, top + h * 0.12f,
                cx - half * 1.05f, top + h * 0.22f);
        path.lineTo(cx - half * 0.9f, bottom);
        path.quadTo(cx, bottom + h * 0.05f, cx + half * 0.9f, bottom);
        path.lineTo(cx + half * 1.05f, top + h * 0.22f);
        path.cubicTo(cx + half, top + h * 0.12f,
                cx + half * 0.9f, top + h * 0.03f, cx, top);
        path.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(21, 52, 62));
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.3f));
        paint.setColor(accent(190));
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent(220));
        float[][] wheels = new float[][]{
                {cx-half*0.95f, h*0.47f},
                {cx+half*0.95f, h*0.47f},
                {cx-half*0.95f, h*0.72f},
                {cx+half*0.95f, h*0.72f}
        };
        for (float[] wheel : wheels) {
            canvas.drawCircle(wheel[0], wheel[1], dp(3.2f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(accent(76));
            canvas.drawCircle(wheel[0], wheel[1], dp(8.5f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent(220));
        }
    }

    private int accent(int alpha) {
        int r, g, b;
        if (signalState == SIGNAL_LIVE) {
            r = 111; g = 255; b = 223;
        } else if (signalState == SIGNAL_STALE) {
            r = 255; g = 209; b = 102;
        } else if (signalState == SIGNAL_BLOCKED) {
            r = 255; g = 138; b = 128;
        } else {
            r = 111; g = 151; b = 163;
        }
        return Color.argb(Math.max(0, Math.min(255, alpha)), r, g, b);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
