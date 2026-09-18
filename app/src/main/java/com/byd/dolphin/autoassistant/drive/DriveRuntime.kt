package com.byd.dolphin.autoassistant.drive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.byd.dolphin.autoassistant.manager.BydPermissionContext
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.lang.reflect.Method
import kotlin.math.min

data class DriveFrame(
    val speedKmh: Float? = null,
    val gear: String? = null,
    val turn: String = "UNKNOWN",
    val bsdRaw: Int? = null,
    val laneRaw: Int? = null,
    val tjaRaw: Int? = null,
    val radarRaw: List<Int?> = List(8) { null }
)

data class SafetySnapshot(
    val sourcePackage: String = "",
    val eventType: String = "NONE",
    val speedLimitKmh: Int = 0,
    val distanceMeters: Int = 0,
    val averageSpeedKmh: Int? = null,
    val updatedAtElapsed: Long = 0L
) {
    fun isFresh(maxAgeMs: Long = 30_000L): Boolean =
        updatedAtElapsed > 0L && SystemClock.elapsedRealtime() - updatedAtElapsed <= maxAgeMs
}

/**
 * Read-only snapshot reader for Surrounding Vision.
 * Confirmed speed/gear are LIVE. ADAS/radar stay RAW/LAB until calibrated.
 */
class DriveSignalReader(context: Context) {
    private val appContext = context.applicationContext
    private val devices = mutableMapOf<String, Any>()
    private val methods = mutableMapOf<String, Method>()
    private val reportedErrors = mutableSetOf<String>()

    fun read(): DriveFrame {
        val speed = readNumber(SPEED_CLASS, "getCurrentSpeed")?.toFloat()
            ?.takeIf { it.isFinite() && it in 0f..260f }
        val gear = readInt(GEARBOX_CLASS, "getCurrentGear")?.let(::decodeGear)
        val turn = readInt(LIGHT_CLASS, "getTurnLightState")?.let(::decodeTurn) ?: "UNKNOWN"
        val bsd = readInt(ADAS_CLASS, "getBSDState")
        val lane = readInt(ADAS_CLASS, "getLaneOffsetState")
        val tja = readInt(ADAS_CLASS, "getTJAState")
        val radar = (1..8).map { area ->
            readInt(RADAR_CLASS, "getRadarObstacleDistance", area)?.takeIf { it in 0..255 }
        }
        return DriveFrame(speed, gear, turn, bsd, lane, tja, radar)
    }

    fun close() {
        devices.clear()
        methods.clear()
    }

    private fun readInt(className: String, methodName: String, vararg args: Int): Int? =
        readNumber(className, methodName, *args)?.toInt()

    private fun readNumber(className: String, methodName: String, vararg args: Int): Number? {
        val key = className + "#" + methodName + "/" + args.size
        return try {
            val instance = devices.getOrPut(className) {
                val clazz = Class.forName(className)
                clazz.getMethod("getInstance", Context::class.java)
                    .invoke(null, BydPermissionContext.wrap(appContext))
                    ?: error("getInstance returned null")
            }
            val method = methods.getOrPut(key) {
                val types = Array(args.size) { Int::class.javaPrimitiveType!! }
                instance.javaClass.getMethod(methodName, *types)
            }
            method.invoke(instance, *args.toTypedArray()) as? Number
        } catch (t: Throwable) {
            devices.remove(className)
            methods.remove(key)
            val cause = t.cause ?: t
            val signature = key + ":" + cause.javaClass.simpleName + ":" + cause.message
            if (reportedErrors.add(signature)) {
                DolphinLogger.w(TAG, key + " unavailable: " + cause.javaClass.simpleName + ": " + cause.message)
            }
            null
        }
    }

    private fun decodeGear(raw: Int): String? = when (raw) {
        0 -> "N"
        1 -> "R"
        2 -> "D"
        3 -> "P"
        else -> null
    }

    private fun decodeTurn(raw: Int): String = when (raw) {
        1 -> "OFF"
        2, 3 -> "LEFT"
        4, 5 -> "RIGHT"
        6, 7, 8 -> "HAZARD"
        else -> "UNKNOWN(" + raw + ")"
    }

    companion object {
        private const val TAG = "DRIVE_SIGNAL"
        private const val SPEED_CLASS = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
        private const val GEARBOX_CLASS = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
        private const val LIGHT_CLASS = "android.hardware.bydauto.light.BYDAutoLightDevice"
        private const val ADAS_CLASS = "android.hardware.bydauto.adas.BYDAutoADASDevice"
        private const val RADAR_CLASS = "android.hardware.bydauto.radar.BYDAutoRadarDevice"
    }
}

/**
 * In-process bus fed only from supported navigation notifications.
 * It never invents camera/safety data from the network.
 */
object DriveSafetyBus {
    @Volatile private var snapshot = SafetySnapshot()

    fun current(): SafetySnapshot = snapshot

    @Synchronized
    fun updateFromNavigation(pkg: String, title: String, text: String, subText: String) {
        val combined = (title + " " + text + " " + subText).trim()
        if (combined.isBlank()) return

        val speedLimit = Regex(
            "(?:제한(?:속도)?|과속|단속)\\s*[:：]?\\s*([0-9]{2,3})",
            RegexOption.IGNORE_CASE
        ).find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        val distance = Regex(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(km|m)(?!\\s*/?\\s*h)",
            RegexOption.IGNORE_CASE
        ).find(combined)?.let { match ->
            val number = match.groupValues[1].toDoubleOrNull() ?: 0.0
            if (match.groupValues[2].equals("km", true)) (number * 1000.0).toInt() else number.toInt()
        } ?: 0

        val average = Regex(
            "(?:평균|구간평균)\\s*[:：]?\\s*([0-9]{1,3})",
            RegexOption.IGNORE_CASE
        ).find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val type = when {
            combined.contains("구간단속") -> "SECTION CONTROL"
            combined.contains("신호위반") || combined.contains("신호 단속") -> "SIGNAL CAMERA"
            combined.contains("주정차") -> "PARKING ENFORCEMENT"
            combined.contains("스쿨존") || combined.contains("어린이보호") -> "SCHOOL ZONE"
            combined.contains("과속") || combined.contains("단속카메라") -> "SPEED CAMERA"
            speedLimit > 0 -> "SPEED LIMIT"
            else -> "NAV GUIDANCE"
        }

        snapshot = SafetySnapshot(
            sourcePackage = pkg,
            eventType = type,
            speedLimitKmh = speedLimit,
            distanceMeters = distance.coerceIn(0, 200_000),
            averageSpeedKmh = average,
            updatedAtElapsed = SystemClock.elapsedRealtime()
        )
    }

    @Synchronized
    fun clear() {
        snapshot = SafetySnapshot()
    }
}

class SurroundingVisionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var frame = DriveFrame()
    private var safety = SafetySnapshot()

    fun update(frame: DriveFrame, safety: SafetySnapshot) {
        this.frame = frame
        this.safety = safety
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        canvas.drawColor(Color.rgb(3, 8, 16))
        drawRoad(canvas, w, h)
        drawRadar(canvas, w, h)
        drawVehicle(canvas, w, h)
        drawTelemetry(canvas, w, h)
        drawSafetyMarker(canvas, w, h)
    }

    private fun drawRoad(canvas: Canvas, w: Float, h: Float) {
        val horizonY = h * 0.18f
        val roadBottomY = h * 0.96f
        path.reset()
        path.moveTo(w * 0.40f, horizonY)
        path.lineTo(w * 0.13f, roadBottomY)
        path.lineTo(w * 0.87f, roadBottomY)
        path.lineTo(w * 0.60f, horizonY)
        path.close()

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(12, 21, 32)
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.rgb(36, 76, 92)
        canvas.drawPath(path, paint)

        paint.strokeWidth = 2f
        paint.color = Color.rgb(56, 101, 115)
        for (i in 1..4) {
            val t = i / 5f
            val y = horizonY + (roadBottomY - horizonY) * t
            val half = (w * 0.10f) + (w * 0.27f) * t
            canvas.drawLine(w / 2f - half, y, w / 2f + half, y, paint)
        }

        paint.color = Color.rgb(110, 150, 160)
        paint.strokeWidth = 2f
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 14f), 0f)
        canvas.drawLine(w * 0.47f, horizonY, w * 0.38f, roadBottomY, paint)
        canvas.drawLine(w * 0.53f, horizonY, w * 0.62f, roadBottomY, paint)
        paint.pathEffect = null

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(115, 150, 160)
        paint.textSize = min(w, h) * 0.028f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("GUIDE GRID · SENSOR LANE = NO SIGNAL", w / 2f, h * 0.08f, paint)
    }

    private fun drawVehicle(canvas: Canvas, w: Float, h: Float) {
        val carW = w * 0.12f
        val carH = h * 0.23f
        val left = w / 2f - carW / 2f
        val top = h * 0.64f
        val rect = RectF(left, top, left + carW, top + carH)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(20, 220, 224)
        canvas.drawRoundRect(rect, carW * 0.24f, carW * 0.24f, paint)

        paint.color = Color.rgb(3, 31, 39)
        canvas.drawRoundRect(
            RectF(left + carW * 0.20f, top + carH * 0.17f, left + carW * 0.80f, top + carH * 0.48f),
            carW * 0.10f, carW * 0.10f, paint
        )

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(w, h) * 0.032f
        paint.color = Color.WHITE
        when (frame.turn) {
            "LEFT" -> canvas.drawText("◀", left - carW * 0.45f, top + carH * 0.50f, paint)
            "RIGHT" -> canvas.drawText("▶", left + carW * 1.45f, top + carH * 0.50f, paint)
            "HAZARD" -> {
                canvas.drawText("◀", left - carW * 0.45f, top + carH * 0.50f, paint)
                canvas.drawText("▶", left + carW * 1.45f, top + carH * 0.50f, paint)
            }
        }
    }

    private fun drawRadar(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h * 0.755f
        val rx = w * 0.12f
        val ry = h * 0.20f
        val points = listOf(
            cx - rx to cy - ry,
            cx - rx * 0.35f to cy - ry * 1.10f,
            cx + rx * 0.35f to cy - ry * 1.10f,
            cx + rx to cy - ry,
            cx - rx to cy + ry,
            cx - rx * 0.35f to cy + ry * 0.92f,
            cx + rx * 0.35f to cy + ry * 0.92f,
            cx + rx to cy + ry
        )
        val radius = min(w, h) * 0.011f
        frame.radarRaw.forEachIndexed { index, raw ->
            val point = points[index]
            paint.style = Paint.Style.FILL
            paint.color = when {
                raw == null -> Color.rgb(55, 65, 72)
                raw <= 25 -> Color.rgb(255, 82, 82)
                raw <= 70 -> Color.rgb(255, 193, 7)
                else -> Color.rgb(0, 230, 180)
            }
            canvas.drawCircle(point.first, point.second, radius, paint)
        }

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(w, h) * 0.022f
        paint.color = Color.rgb(100, 165, 180)
        canvas.drawText("RADAR RAW 1..8 · LAB MAP", cx, h * 0.985f, paint)
    }

    private fun drawTelemetry(canvas: Canvas, w: Float, h: Float) {
        paint.textAlign = Paint.Align.LEFT
        paint.color = Color.WHITE
        paint.textSize = min(w, h) * 0.055f
        val speedText = frame.speedKmh?.let { it.toInt().toString() + " km/h" } ?: "-- km/h"
        canvas.drawText(speedText, w * 0.035f, h * 0.16f, paint)

        paint.textSize = min(w, h) * 0.028f
        paint.color = Color.rgb(0, 230, 180)
        canvas.drawText(
            "LIVE · GEAR " + (frame.gear ?: "--") + " · TURN " + frame.turn,
            w * 0.035f, h * 0.215f, paint
        )

        paint.color = Color.rgb(255, 193, 7)
        canvas.drawText(
            "LAB · BSD " + (frame.bsdRaw ?: "--") + " · LANE " + (frame.laneRaw ?: "--") +
                " · TJA " + (frame.tjaRaw ?: "--"),
            w * 0.035f, h * 0.255f, paint
        )
    }

    private fun drawSafetyMarker(canvas: Canvas, w: Float, h: Float) {
        if (!safety.isFresh()) return
        val label = buildString {
            append(safety.eventType)
            if (safety.speedLimitKmh > 0) append("  ").append(safety.speedLimitKmh)
            if (safety.distanceMeters > 0) append("  ").append(formatDistance(safety.distanceMeters))
        }
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(w, h) * 0.030f
        paint.color = Color.rgb(255, 210, 64)
        canvas.drawText(label, w / 2f, h * 0.135f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(w / 2f, h * 0.25f, min(w, h) * 0.03f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun formatDistance(meters: Int): String =
        if (meters >= 1000) String.format(java.util.Locale.US, "%.1f km", meters / 1000f) else meters.toString() + " m"
}
