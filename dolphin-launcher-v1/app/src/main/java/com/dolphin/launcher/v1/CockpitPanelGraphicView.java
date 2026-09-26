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
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final int mode;
    private int signalState = SIGNAL_WAITING;

    public CockpitPanelGraphicView(Context context, int mode) {
        super(context);
        this.mode = mode;
        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
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
        drawSourceLegend(canvas, w, h);
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

        paint.setColor(Color.argb(32, 156, 255, 236));
        path.reset();
        path.moveTo(w * 0.50f, h * 0.10f);
        path.lineTo(w * 0.94f, h * 0.10f);
        path.lineTo(w * 0.86f, h * 0.16f);
        canvas.drawPath(path, paint);
    }

    private void drawMedia(Canvas canvas, float w, float h) {
        // Target-media identity graphic only. A fixed PLAY symbol is intentionally
        // avoided so REVERIFY_REQUIRED does not look like audible playback success.
        float cx = w * 0.76f;
        float cy = h * 0.34f;
        float outer = h * 0.145f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(accent(180));
        canvas.drawCircle(cx, cy, outer, paint);

        paint.setStrokeWidth(dp(3.2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        rect.set(cx - outer * 0.70f, cy - outer * 0.70f,
                cx + outer * 0.70f, cy + outer * 0.70f);
        paint.setColor(accent(160));
        canvas.drawArc(rect, 210f, 76f, false, paint);
        canvas.drawArc(rect, 30f, 76f, false, paint);

        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(accent(92));
        rect.set(cx - outer * 0.44f, cy - outer * 0.44f,
                cx + outer * 0.44f, cy + outer * 0.44f);
        canvas.drawArc(rect, 195f, 150f, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        float base = h * 0.73f;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 9; i++) {
            float x = w * (0.55f + i * 0.035f);
            float bh = h * (0.055f + ((i * 3) % 5) * 0.026f);
            paint.setColor(accent(118 + (i % 3) * 18));
            canvas.drawRoundRect(x, base - bh, x + dp(5), base, dp(2), dp(2), paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(accent(86));
        canvas.drawLine(w * 0.55f, h * 0.81f, w * 0.86f, h * 0.81f, paint);
        paint.setStrokeWidth(dp(2));
        paint.setColor(accent(190));
        canvas.drawLine(w * 0.55f, h * 0.81f, w * 0.66f, h * 0.81f, paint);
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
        // Read-only ego-vehicle sculpture. Accent reflects source state only;
        // geometry never infers gear, speed, turn state, or vehicle health.
        float cx = w * 0.76f;
        float top = h * 0.22f;
        float bottom = h * 0.77f;
        float half = w * 0.092f;

        path.reset();
        path.moveTo(cx, top);
        path.cubicTo(cx - half * 0.90f, top + h * 0.03f,
                cx - half, top + h * 0.12f,
                cx - half * 1.06f, top + h * 0.22f);
        path.lineTo(cx - half * 0.92f, bottom);
        path.quadTo(cx, bottom + h * 0.052f, cx + half * 0.92f, bottom);
        path.lineTo(cx + half * 1.06f, top + h * 0.22f);
        path.cubicTo(cx + half, top + h * 0.12f,
                cx + half * 0.90f, top + h * 0.03f, cx, top);
        path.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent(54));
        rect.set(cx - half * 1.36f, bottom - h * 0.015f,
                cx + half * 1.36f, bottom + h * 0.065f);
        canvas.drawOval(rect, paint);

        paint.setShader(new LinearGradient(
                cx - half, top, cx + half, bottom,
                new int[]{Color.rgb(38, 80, 91), Color.rgb(9, 26, 33), Color.rgb(28, 62, 73)},
                null, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.3f));
        paint.setColor(accent(190));
        canvas.drawPath(path, paint);

        // Cabin glass stays decorative and carries no telemetry.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(178, 4, 16, 21));
        path.reset();
        path.moveTo(cx, top + h * 0.07f);
        path.lineTo(cx - half * 0.65f, top + h * 0.145f);
        path.lineTo(cx - half * 0.52f, top + h * 0.245f);
        path.lineTo(cx + half * 0.52f, top + h * 0.245f);
        path.lineTo(cx + half * 0.65f, top + h * 0.145f);
        path.close();
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(accent(92));
        canvas.drawLine(cx - half * 0.50f, top + h * 0.255f,
                cx + half * 0.50f, top + h * 0.255f, paint);

        float[][] wheels = new float[][]{
                {cx-half*0.96f, h*0.47f},
                {cx+half*0.96f, h*0.47f},
                {cx-half*0.96f, h*0.71f},
                {cx+half*0.96f, h*0.71f}
        };
        for (float[] wheel : wheels) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(accent(76));
            canvas.drawCircle(wheel[0], wheel[1], dp(8.5f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent(220));
            canvas.drawCircle(wheel[0], wheel[1], dp(3.0f), paint);
        }

        // Side brackets read as a source frame, not a normalized value.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(accent(105));
        float bx = w * 0.58f;
        float by = h * 0.34f;
        canvas.drawLine(bx, by, bx + w * 0.035f, by, paint);
        canvas.drawLine(bx, by, bx, by + h * 0.10f, paint);
        bx = w * 0.94f;
        canvas.drawLine(bx - w * 0.035f, by, bx, by, paint);
        canvas.drawLine(bx, by, bx, by + h * 0.10f, paint);
    }

    private void drawSourceLegend(Canvas canvas, float w, float h) {
        // Truthful source-scope legend only. SOURCE LIVE means source availability;
        // it never upgrades the Registry feature state or proves audible playback,
        // semantic perception, or normalized telemetry.
        String scope = mode == MEDIA ? "TARGET SESSION"
                : mode == SAFETY ? "SEMANTICS LOCKED" : "RAW SOURCE";
        String state = signalState == SIGNAL_LIVE ? "LIVE"
                : signalState == SIGNAL_STALE ? "STALE"
                : signalState == SIGNAL_BLOCKED ? "BLOCKED" : "WAITING";

        float left = w * 0.54f;
        float right = w * 0.94f;
        float top = h * 0.075f;
        float bottom = top + dp(24);
        float centerY = (top + bottom) * 0.5f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(154, 3, 13, 18));
        canvas.drawRoundRect(left, top, right, bottom, dp(8), dp(8), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(accent(90));
        canvas.drawRoundRect(left, top, right, bottom, dp(8), dp(8), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent(220));
        canvas.drawCircle(left + dp(8), centerY, dp(2.5f), paint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(dp(7.2f));
        textPaint.setColor(Color.argb(210, 204, 226, 228));
        canvas.drawText(scope, left + dp(14), centerY + dp(2.4f), textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(dp(7.4f));
        textPaint.setColor(accent(230));
        canvas.drawText("SOURCE " + state, right - dp(7), centerY + dp(2.5f), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
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
