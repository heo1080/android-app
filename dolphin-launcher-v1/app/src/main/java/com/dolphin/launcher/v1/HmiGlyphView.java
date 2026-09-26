package com.dolphin.launcher.v1;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Lightweight vector glyph set for the premium HMI. */
public final class HmiGlyphView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final String glyph;
    private int accentColor = Color.rgb(140, 255, 232);

    public HmiGlyphView(Context context, String glyph) {
        super(context);
        this.glyph = glyph == null ? "" : glyph;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        fallbackPaint.setTextAlign(Paint.Align.CENTER);
        fallbackPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
    }

    public void setAccentColor(int color) {
        accentColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float s = Math.min(w, h);
        float cx = w * 0.5f;
        float cy = h * 0.5f;

        paint.setShader(null);
        paint.setColor(accentColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.7f), s * 0.055f));

        switch (glyph) {
            case "⌂":
                drawHome(canvas, cx, cy, s);
                break;
            case "▦":
                drawApps(canvas, cx, cy, s);
                break;
            case "◫":
                drawSplit(canvas, cx, cy, s);
                break;
            case "▶":
                drawPlay(canvas, cx, cy, s);
                break;
            case "⚙":
                drawSettings(canvas, cx, cy, s);
                break;
            case "✓":
                drawVerify(canvas, cx, cy, s);
                break;
            case "≋":
                drawEq(canvas, cx, cy, s);
                break;
            case "◎":
                drawTarget(canvas, cx, cy, s);
                break;
            case "◉":
                drawTyre(canvas, cx, cy, s);
                break;
            case "MAP":
                drawMapPin(canvas, cx, cy, s);
                break;
            case "VOL":
                drawVolume(canvas, cx, cy, s);
                break;
            case "SUN":
                drawSun(canvas, cx, cy, s);
                break;
            case "DSP":
                drawDisplay(canvas, cx, cy, s);
                break;
            case "MOON":
                drawMoon(canvas, cx, cy, s);
                break;
            default:
                drawFallback(canvas, cx, cy, s);
                break;
        }
    }

    private void drawHome(Canvas canvas, float cx, float cy, float s) {
        float r = s * 0.29f;
        path.reset();
        path.moveTo(cx - r, cy - s * 0.02f);
        path.lineTo(cx, cy - r);
        path.lineTo(cx + r, cy - s * 0.02f);
        path.lineTo(cx + r * 0.78f, cy + r);
        path.lineTo(cx + r * 0.18f, cy + r);
        path.lineTo(cx + r * 0.18f, cy + r * 0.36f);
        path.lineTo(cx - r * 0.18f, cy + r * 0.36f);
        path.lineTo(cx - r * 0.18f, cy + r);
        path.lineTo(cx - r * 0.78f, cy + r);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawApps(Canvas canvas, float cx, float cy, float s) {
        float box = s * 0.18f;
        float gap = s * 0.10f;
        for (int row = -1; row <= 1; row += 2) {
            for (int col = -1; col <= 1; col += 2) {
                float x = cx + col * (box * 0.5f + gap * 0.5f);
                float y = cy + row * (box * 0.5f + gap * 0.5f);
                rect.set(x - box * 0.5f, y - box * 0.5f, x + box * 0.5f, y + box * 0.5f);
                canvas.drawRoundRect(rect, s * 0.045f, s * 0.045f, paint);
            }
        }
    }

    private void drawSplit(Canvas canvas, float cx, float cy, float s) {
        float w = s * 0.62f;
        float h = s * 0.48f;
        rect.set(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);
        canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint);
        canvas.drawLine(cx, cy - h * 0.5f, cx, cy + h * 0.5f, paint);
        canvas.drawCircle(cx - w * 0.25f, cy, s * 0.035f, paint);
        canvas.drawCircle(cx + w * 0.25f, cy, s * 0.035f, paint);
    }

    private void drawPlay(Canvas canvas, float cx, float cy, float s) {
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(cx - s * 0.20f, cy - s * 0.27f);
        path.lineTo(cx + s * 0.29f, cy);
        path.lineTo(cx - s * 0.20f, cy + s * 0.27f);
        path.close();
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private void drawSettings(Canvas canvas, float cx, float cy, float s) {
        float inner = s * 0.13f;
        float outer = s * 0.30f;
        canvas.drawCircle(cx, cy, inner, paint);
        canvas.drawCircle(cx, cy, s * 0.22f, paint);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * i / 4.0;
            float x1 = cx + (float)Math.cos(angle) * s * 0.22f;
            float y1 = cy + (float)Math.sin(angle) * s * 0.22f;
            float x2 = cx + (float)Math.cos(angle) * outer;
            float y2 = cy + (float)Math.sin(angle) * outer;
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
    }

    private void drawVerify(Canvas canvas, float cx, float cy, float s) {
        float r = s * 0.29f;
        path.reset();
        path.moveTo(cx, cy - r);
        path.lineTo(cx + r * 0.78f, cy - r * 0.56f);
        path.lineTo(cx + r * 0.64f, cy + r * 0.48f);
        path.quadTo(cx, cy + r, cx - r * 0.64f, cy + r * 0.48f);
        path.lineTo(cx - r * 0.78f, cy - r * 0.56f);
        path.close();
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(cx - s * 0.14f, cy);
        path.lineTo(cx - s * 0.03f, cy + s * 0.11f);
        path.lineTo(cx + s * 0.18f, cy - s * 0.14f);
        canvas.drawPath(path, paint);
    }

    private void drawEq(Canvas canvas, float cx, float cy, float s) {
        float span = s * 0.46f;
        float[] xs = new float[]{cx - span * 0.5f, cx, cx + span * 0.5f};
        float[] knobs = new float[]{cy + s * 0.11f, cy - s * 0.10f, cy + s * 0.02f};
        for (int i = 0; i < xs.length; i++) {
            canvas.drawLine(xs[i], cy - s * 0.27f, xs[i], cy + s * 0.27f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(xs[i], knobs[i], s * 0.075f, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
    }

    private void drawTarget(Canvas canvas, float cx, float cy, float s) {
        canvas.drawCircle(cx, cy, s * 0.28f, paint);
        canvas.drawCircle(cx, cy, s * 0.13f, paint);
        canvas.drawLine(cx - s * 0.34f, cy, cx - s * 0.20f, cy, paint);
        canvas.drawLine(cx + s * 0.20f, cy, cx + s * 0.34f, cy, paint);
        canvas.drawLine(cx, cy - s * 0.34f, cx, cy - s * 0.20f, paint);
        canvas.drawLine(cx, cy + s * 0.20f, cx, cy + s * 0.34f, paint);
    }

    private void drawTyre(Canvas canvas, float cx, float cy, float s) {
        canvas.drawCircle(cx, cy, s * 0.29f, paint);
        canvas.drawCircle(cx, cy, s * 0.17f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, s * 0.055f, paint);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * i / 2.0;
            float x1 = cx + (float)Math.cos(angle) * s * 0.18f;
            float y1 = cy + (float)Math.sin(angle) * s * 0.18f;
            float x2 = cx + (float)Math.cos(angle) * s * 0.27f;
            float y2 = cy + (float)Math.sin(angle) * s * 0.27f;
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
    }

    private void drawMapPin(Canvas canvas, float cx, float cy, float s) {
        float r=s*0.20f;
        canvas.drawCircle(cx,cy-s*0.08f,r,paint);
        canvas.drawCircle(cx,cy-s*0.08f,s*0.055f,paint);
        path.reset();
        path.moveTo(cx-r*0.78f,cy+s*0.02f);
        path.quadTo(cx,cy+s*0.34f,cx+r*0.78f,cy+s*0.02f);
        canvas.drawPath(path,paint);
    }

    private void drawVolume(Canvas canvas, float cx, float cy, float s) {
        path.reset();
        path.moveTo(cx-s*0.28f,cy-s*0.10f);
        path.lineTo(cx-s*0.12f,cy-s*0.10f);
        path.lineTo(cx+s*0.03f,cy-s*0.25f);
        path.lineTo(cx+s*0.03f,cy+s*0.25f);
        path.lineTo(cx-s*0.12f,cy+s*0.10f);
        path.lineTo(cx-s*0.28f,cy+s*0.10f);
        path.close();
        canvas.drawPath(path,paint);
        rect.set(cx-s*0.02f,cy-s*0.25f,cx+s*0.34f,cy+s*0.25f);
        canvas.drawArc(rect,-48f,96f,false,paint);
    }

    private void drawSun(Canvas canvas, float cx, float cy, float s) {
        canvas.drawCircle(cx,cy,s*0.13f,paint);
        for(int i=0;i<8;i++){
            double a=Math.PI*i/4.0;
            float x1=cx+(float)Math.cos(a)*s*0.20f;
            float y1=cy+(float)Math.sin(a)*s*0.20f;
            float x2=cx+(float)Math.cos(a)*s*0.31f;
            float y2=cy+(float)Math.sin(a)*s*0.31f;
            canvas.drawLine(x1,y1,x2,y2,paint);
        }
    }

    private void drawDisplay(Canvas canvas, float cx, float cy, float s) {
        float w=s*0.62f,h=s*0.40f;
        rect.set(cx-w*0.5f,cy-h*0.55f,cx+w*0.5f,cy+h*0.45f);
        canvas.drawRoundRect(rect,s*0.06f,s*0.06f,paint);
        canvas.drawLine(cx,cy+h*0.45f,cx,cy+h*0.65f,paint);
        canvas.drawLine(cx-s*0.16f,cy+h*0.65f,cx+s*0.16f,cy+h*0.65f,paint);
    }

    private void drawMoon(Canvas canvas, float cx, float cy, float s) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accentColor);
        canvas.drawCircle(cx-s*0.03f,cy,s*0.27f,paint);
        paint.setColor(Color.argb(255,7,18,24));
        canvas.drawCircle(cx+s*0.09f,cy-s*0.06f,s*0.25f,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(accentColor);
    }

    private void drawFallback(Canvas canvas, float cx, float cy, float s) {
        fallbackPaint.setColor(accentColor);
        fallbackPaint.setTextSize(s * 0.48f);
        Paint.FontMetrics fm = fallbackPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(glyph, cx, baseline, fallbackPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
