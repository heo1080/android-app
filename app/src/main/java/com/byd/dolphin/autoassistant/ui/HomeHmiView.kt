package com.byd.dolphin.autoassistant.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.byd.dolphin.autoassistant.drive.DriveFrame
import com.byd.dolphin.autoassistant.drive.SafetySnapshot
import com.byd.dolphin.autoassistant.manager.NowPlayingSnapshot
import com.byd.dolphin.autoassistant.manager.TpmsSnapshot
import com.byd.dolphin.autoassistant.manager.TpmsWheel
import com.byd.dolphin.autoassistant.manager.VehicleInfoSnapshot
import java.util.Locale
import kotlin.math.min

/**
 * Home HMI renderer based on the approved navy/cyan concept.
 *
 * The renderer deliberately separates visual HMI chrome from vehicle truth:
 * values with no confirmed source render as "--" / "신호 확인 중" instead of
 * mock numbers. The road scene is presentation graphics; only labelled LIVE
 * values come from DriveFrame / VehicleInfo / TPMS snapshots.
 */
class HomeHmiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onDriveView: (() -> Unit)? = null
    var onSeatMemory: (() -> Unit)? = null
    var onQuickControl: (() -> Unit)? = null
    var onMediaPrevious: (() -> Unit)? = null
    var onMediaPlayPause: (() -> Unit)? = null
    var onMediaNext: (() -> Unit)? = null

    private var frame = DriveFrame()
    private var vehicle = VehicleInfoSnapshot()
    private var tpms = TpmsSnapshot()
    private var media = NowPlayingSnapshot()
    private var safety = SafetySnapshot()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val cyan = Color.rgb(0, 229, 255)
    private val cyanSoft = Color.rgb(71, 191, 222)
    private val green = Color.rgb(0, 230, 180)
    private val white = Color.rgb(238, 249, 255)
    private val muted = Color.rgb(125, 161, 177)
    private val dim = Color.rgb(72, 109, 126)
    private val panel = Color.rgb(5, 20, 33)
    private val panel2 = Color.rgb(7, 29, 45)
    private val line = Color.rgb(16, 85, 111)
    private val bg = Color.rgb(2, 8, 17)
    private val red = Color.rgb(255, 84, 91)
    private val amber = Color.rgb(255, 191, 74)

    fun bind(
        frame: DriveFrame,
        vehicle: VehicleInfoSnapshot,
        tpms: TpmsSnapshot,
        media: NowPlayingSnapshot,
        safety: SafetySnapshot
    ) {
        this.frame = frame
        this.vehicle = vehicle
        this.tpms = tpms
        this.media = media
        this.safety = safety
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bg)

        val sx = width / BASE_W
        val sy = height / BASE_H
        val scale = min(sx, sy)
        val ox = (width - BASE_W * scale) / 2f
        val oy = (height - BASE_H * scale) / 2f

        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(scale, scale)

        drawDrivePanel(canvas, RectF(0f, 0f, 1110f, 570f))
        drawVehicleInfo(canvas, RectF(1122f, 0f, 1500f, 462f))
        drawTpms(canvas, RectF(1122f, 474f, 1500f, 820f))
        drawMedia(canvas, RectF(0f, 582f, 360f, 820f))
        drawQuick(canvas, RectF(372f, 582f, 790f, 820f))
        drawSeat(canvas, RectF(802f, 582f, 1110f, 820f))

        canvas.restore()
    }

    private fun drawDrivePanel(c: Canvas, r: RectF) {
        roundedGradient(c, r, Color.rgb(4, 19, 33), Color.rgb(2, 10, 20), 20f, cyanSoft)
        c.save()
        path.reset()
        path.addRoundRect(r, 20f, 20f, Path.Direction.CW)
        c.clipPath(path)

        // night horizon / skyline
        p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, Color.rgb(4, 22, 40), Color.rgb(2, 9, 17), Shader.TileMode.CLAMP)
        c.drawRect(r, p)
        p.shader = null

        val horizon = r.top + 142f
        for (i in 0..20) {
            val bw = 26f + (i % 5) * 5f
            val bh = 28f + (i % 7) * 11f
            val x = r.left + 20f + i * 54f
            p.color = if (i % 3 == 0) Color.rgb(8, 39, 58) else Color.rgb(6, 29, 46)
            c.drawRect(x, horizon - bh, x + bw, horizon, p)
            if (i % 2 == 0) {
                p.color = Color.rgb(39, 96, 113)
                c.drawRect(x + 7f, horizon - bh + 9f, x + 11f, horizon - bh + 13f, p)
            }
        }

        // perspective road
        val roadBottom = r.bottom - 36f
        path.reset()
        path.moveTo(r.centerX() - 165f, horizon)
        path.lineTo(r.left + 70f, roadBottom)
        path.lineTo(r.right - 70f, roadBottom)
        path.lineTo(r.centerX() + 165f, horizon)
        path.close()
        p.color = Color.rgb(10, 22, 34)
        p.style = Paint.Style.FILL
        c.drawPath(path, p)

        // road edge glow
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.rgb(12, 93, 126)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL

        // lane perspective with blue live lane emphasis
        for (lane in -2..2) {
            val topX = r.centerX() + lane * 68f
            val bottomX = r.centerX() + lane * 185f
            p.strokeWidth = if (lane == -1 || lane == 1) 4f else 2f
            p.color = if (lane == -1 || lane == 1) Color.rgb(31, 174, 238) else Color.rgb(73, 108, 124)
            p.alpha = if (lane == -1 || lane == 1) 230 else 115
            c.drawLine(topX, horizon + 10f, bottomX, roadBottom - 6f, p)
        }
        p.alpha = 255

        // center lane glow
        path.reset()
        path.moveTo(r.centerX() - 44f, horizon + 6f)
        path.lineTo(r.centerX() - 104f, roadBottom)
        path.lineTo(r.centerX() + 104f, roadBottom)
        path.lineTo(r.centerX() + 44f, horizon + 6f)
        path.close()
        p.shader = LinearGradient(r.centerX(), horizon, r.centerX(), roadBottom,
            Color.argb(25, 0, 229, 255), Color.argb(130, 0, 141, 255), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null

        drawOwnVehicle(c, r.centerX(), r.bottom - 158f)

        // radar rings around own car: graphics only, never object classification.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        for (i in 1..3) {
            p.color = Color.argb(80 - i * 12, 0, 229, 255)
            c.drawOval(RectF(r.centerX() - 85f * i, r.bottom - 137f - 26f * i, r.centerX() + 85f * i, r.bottom - 92f + 26f * i), p)
        }
        p.style = Paint.Style.FILL

        drawRadarMarkers(c, r)

        // top chips
        pill(c, r.left + 24f, r.top + 22f, 122f, 42f, "FSD VISUAL", cyan, Color.rgb(5, 45, 65))
        pill(c, r.left + 157f, r.top + 22f, 142f, 42f,
            if (frame.gear != null) "차량 LIVE" else "차량 신호 대기",
            if (frame.gear != null) green else amber, Color.rgb(5, 43, 45))

        val speed = frame.speedKmh?.let { it.toInt().toString() } ?: "--"
        text(c, speed, r.centerX(), r.top + 72f, 52f, white, true, Paint.Align.CENTER)
        text(c, "km/h", r.centerX(), r.top + 101f, 15f, muted, false, Paint.Align.CENTER)

        val gearText = "GEAR " + (frame.gear ?: "--")
        pill(c, r.centerX() - 195f, r.bottom - 68f, 135f, 40f, gearText, green, Color.rgb(5, 43, 45))
        pill(c, r.centerX() + 60f, r.bottom - 68f, 180f, 40f,
            when {
                frame.turn == "LEFT" -> "◀  LEFT"
                frame.turn == "RIGHT" -> "RIGHT  ▶"
                frame.turn == "HAZARD" -> "◀  HAZARD  ▶"
                else -> "TURN " + frame.turn
            }, cyan, Color.rgb(5, 39, 57))

        // safety/navigation card: only facts currently present on bus.
        val nav = RectF(r.right - 315f, r.top + 22f, r.right - 24f, r.top + 125f)
        panel(c, nav, 15f, Color.rgb(5, 31, 49), Color.rgb(20, 111, 145))
        text(c, "NAV / SAFETY", nav.left + 16f, nav.top + 24f, 11f, cyan, true)
        if (safety.isFresh()) {
            text(c, safety.eventType, nav.left + 16f, nav.top + 55f, 18f, white, true)
            val details = buildString {
                if (safety.speedLimitKmh > 0) append("제한 ").append(safety.speedLimitKmh).append(" km/h  ")
                if (safety.distanceMeters > 0) append(formatDistance(safety.distanceMeters))
            }.ifBlank { "지원 내비 알림 수신" }
            text(c, details, nav.left + 16f, nav.top + 82f, 12f, muted, false)
        } else {
            text(c, "TBT / 안전정보 대기", nav.left + 16f, nav.top + 58f, 17f, white, true)
            text(c, "확인된 데이터만 표시", nav.left + 16f, nav.top + 84f, 11f, muted, false)
        }

        text(c, "도로/차량 그래픽 = HMI 시각화 · 주변 객체 종류는 센서 분류 미확인", r.left + 24f, r.bottom - 17f, 10f, dim, false)
        c.restore()
    }

    private fun drawRadarMarkers(c: Canvas, r: RectF) {
        val valid = frame.radarRaw.mapIndexedNotNull { i, raw -> raw?.let { i to it } }
        if (valid.isEmpty()) {
            text(c, "RADAR · NO CLASSIFIED OBJECT SIGNAL", r.left + 24f, r.top + 152f, 10f, dim, true)
            return
        }

        val positions = listOf(
            r.centerX() - 250f to r.bottom - 250f,
            r.centerX() - 105f to r.bottom - 305f,
            r.centerX() + 105f to r.bottom - 305f,
            r.centerX() + 250f to r.bottom - 250f,
            r.centerX() - 250f to r.bottom - 105f,
            r.centerX() - 105f to r.bottom - 75f,
            r.centerX() + 105f to r.bottom - 75f,
            r.centerX() + 250f to r.bottom - 105f
        )

        valid.forEach { (idx, raw) ->
            val pos = positions.getOrNull(idx) ?: return@forEach
            val close = raw <= 25
            p.color = if (close) Color.argb(50, 255, 84, 91) else Color.argb(45, 0, 229, 255)
            c.drawRoundRect(RectF(pos.first - 43f, pos.second - 25f, pos.first + 43f, pos.second + 25f), 10f, 10f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = if (close) red else cyan
            c.drawRoundRect(RectF(pos.first - 43f, pos.second - 25f, pos.first + 43f, pos.second + 25f), 10f, 10f, p)
            p.style = Paint.Style.FILL
            text(c, "RADAR " + raw, pos.first, pos.second + 5f, 10f, if (close) red else cyan, true, Paint.Align.CENTER)
        }
    }

    private fun drawOwnVehicle(c: Canvas, cx: Float, cy: Float) {
        val body = RectF(cx - 48f, cy - 88f, cx + 48f, cy + 88f)
        p.shader = LinearGradient(body.left, body.top, body.right, body.bottom,
            Color.rgb(227, 239, 244), Color.rgb(105, 128, 140), Shader.TileMode.CLAMP)
        c.drawRoundRect(body, 30f, 30f, p)
        p.shader = null
        p.color = Color.rgb(7, 25, 36)
        c.drawRoundRect(RectF(cx - 33f, cy - 52f, cx + 33f, cy + 12f), 15f, 15f, p)
        p.color = Color.rgb(12, 61, 77)
        c.drawRoundRect(RectF(cx - 28f, cy + 28f, cx + 28f, cy + 55f), 11f, 11f, p)
        p.color = red
        c.drawRoundRect(RectF(cx - 37f, cy + 62f, cx - 14f, cy + 68f), 3f, 3f, p)
        c.drawRoundRect(RectF(cx + 14f, cy + 62f, cx + 37f, cy + 68f), 3f, 3f, p)
        text(c, "DOLPHIN", cx, cy + 84f, 8f, Color.rgb(28, 51, 62), true, Paint.Align.CENTER)
    }

    private fun drawVehicleInfo(c: Canvas, r: RectF) {
        panel(c, r, 18f, panel, Color.rgb(13, 94, 121))
        text(c, "차량 정보", r.left + 18f, r.top + 31f, 18f, white, true)
        text(c, "DOLPHIN", r.right - 18f, r.top + 30f, 10f, cyanSoft, true, Paint.Align.RIGHT)

        val soc = vehicle.batteryPercent
        text(c, "배터리 잔량", r.left + 18f, r.top + 70f, 11f, muted, true)
        text(c, soc?.let { "${it}%" } ?: "--%", r.left + 18f, r.top + 105f, 29f, white, true)
        val bar = RectF(r.left + 18f, r.top + 118f, r.right - 18f, r.top + 132f)
        p.color = Color.rgb(13, 44, 59)
        c.drawRoundRect(bar, 7f, 7f, p)
        if (soc != null) {
            p.color = green
            c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * (soc.coerceIn(0,100) / 100f), bar.bottom), 7f, 7f, p)
        }

        val y1 = r.top + 154f
        metric(c, RectF(r.left + 18f, y1, r.centerX() - 6f, y1 + 79f), "현재 주행거리", vehicle.currentJourneyKm?.let { f1(it) + " km" } ?: "--")
        metric(c, RectF(r.centerX() + 6f, y1, r.right - 18f, y1 + 79f), "누적 주행거리", vehicle.totalMileageKm?.let { "%,d km".format(Locale.US, it) } ?: "--")

        val y2 = y1 + 91f
        metric(c, RectF(r.left + 18f, y2, r.centerX() - 6f, y2 + 79f), "주행 가능 거리", "-- km", "원신호 미확인")
        val chargeText = when (vehicle.chargingWorkState) {
            2 -> "충전 중"
            3 -> "충전 완료"
            4 -> "충전 종료"
            else -> "충전 대기 / 미확인"
        }
        metric(c, RectF(r.centerX() + 6f, y2, r.right - 18f, y2 + 79f), "충전 상태", chargeText,
            vehicle.chargingPowerKw?.let { f1(it) + " kW" } ?: "-- kW")

        val y3 = y2 + 91f
        metric(c, RectF(r.left + 18f, y3, r.centerX() - 6f, y3 + 69f), "충전 시작 SOC", vehicle.chargeStartBatteryPercent?.let { "${it}%" } ?: "--")
        metric(c, RectF(r.centerX() + 6f, y3, r.right - 18f, y3 + 69f), "충전 종료 SOC", vehicle.chargeEndBatteryPercent?.let { "${it}%" } ?: "--")
    }

    private fun drawTpms(c: Canvas, r: RectF) {
        panel(c, r, 18f, panel, Color.rgb(13, 94, 121))
        text(c, "TPMS  타이어 공기압", r.left + 18f, r.top + 31f, 17f, white, true)
        val status = when {
            !tpms.available -> "신호 확인 중"
            tpms.systemStateRaw == 0 -> "정상"
            else -> "RAW " + (tpms.systemStateRaw ?: "--")
        }
        pill(c, r.right - 116f, r.top + 13f, 98f, 31f, status,
            if (tpms.available && tpms.systemStateRaw == 0) green else amber,
            Color.rgb(7, 40, 48))

        val top = r.top + 58f
        tyreCell(c, RectF(r.left + 14f, top, r.centerX() - 5f, top + 92f), "FL · 앞좌측", tpms.frontLeft)
        tyreCell(c, RectF(r.centerX() + 5f, top, r.right - 14f, top + 92f), "FR · 앞우측", tpms.frontRight)
        tyreCell(c, RectF(r.left + 14f, top + 103f, r.centerX() - 5f, top + 195f), "RL · 뒤좌측", tpms.rearLeft)
        tyreCell(c, RectF(r.centerX() + 5f, top + 103f, r.right - 14f, top + 195f), "RR · 뒤우측", tpms.rearRight)

        // simple top-view vehicle silhouette between rows
        val cx = r.centerX()
        p.color = Color.rgb(19, 67, 83)
        c.drawRoundRect(RectF(cx - 28f, top + 64f, cx + 28f, top + 151f), 18f, 18f, p)
        p.color = Color.rgb(5, 22, 32)
        c.drawRoundRect(RectF(cx - 18f, top + 78f, cx + 18f, top + 111f), 9f, 9f, p)

        text(c, "SOURCE · " + tpms.source + "  /  UNIT · " + tpms.unitLabel,
            r.left + 18f, r.bottom - 19f, 9f, dim, false)
    }

    private fun tyreCell(c: Canvas, r: RectF, label: String, wheel: TpmsWheel) {
        panel(c, r, 12f, Color.rgb(5, 25, 39), Color.rgb(12, 67, 87))
        val ok = wheel.signalStateRaw == null || wheel.signalStateRaw == 0
        p.color = if (wheel.pressureRaw != null && ok) green else dim
        c.drawCircle(r.left + 14f, r.top + 18f, 5f, p)
        text(c, label, r.left + 27f, r.top + 22f, 10f, muted, true)
        text(c, formatTyre(wheel), r.left + 14f, r.top + 57f, 19f, white, true)
        val state = wheel.pressureStateRaw?.let { "상태 RAW ${it}" } ?: if (wheel.pressureRaw == null) "값 없음" else "상태 신호 대기"
        text(c, state, r.left + 14f, r.top + 78f, 9f, dim, false)
    }

    private fun formatTyre(w: TpmsWheel): String {
        val raw = w.pressureRaw ?: return "--"
        return when (tpms.unitRaw) {
            1 -> String.format(Locale.US, "%.1f bar", raw / 10.0)
            2 -> String.format(Locale.US, "%.1f psi", raw / 10.0)
            3 -> String.format(Locale.US, "%.0f kPa", raw)
            else -> String.format(Locale.US, "%.0f RAW", raw)
        }
    }

    private fun drawMedia(c: Canvas, r: RectF) {
        panel(c, r, 18f, panel2, Color.rgb(9, 111, 144))
        text(c, "지금, 이 음악과 함께", r.left + 18f, r.top + 29f, 14f, cyan, true)

        val art = RectF(r.left + 18f, r.top + 47f, r.left + 125f, r.top + 154f)
        if (media.artwork != null) {
            c.drawBitmap(media.artwork!!, null, art, p)
        } else {
            p.shader = LinearGradient(art.left, art.top, art.right, art.bottom, Color.rgb(25, 84, 126), Color.rgb(31, 22, 75), Shader.TileMode.CLAMP)
            c.drawRoundRect(art, 13f, 13f, p)
            p.shader = null
            p.color = Color.rgb(229, 237, 221)
            c.drawCircle(art.centerX() + 13f, art.centerY() - 13f, 24f, p)
        }

        val title = media.title.ifBlank { "재생 중인 미디어 없음" }
        val artist = media.artist.ifBlank { if (media.notificationAccess) "미디어 재생 대기" else "권한 확인 중" }
        text(c, title.take(26), r.left + 142f, r.top + 72f, 17f, white, true)
        text(c, artist.take(30), r.left + 142f, r.top + 98f, 11f, muted, false)
        text(c, media.album.take(30), r.left + 142f, r.top + 119f, 10f, dim, false)

        val prog = RectF(r.left + 18f, r.top + 172f, r.right - 18f, r.top + 178f)
        p.color = Color.rgb(28, 63, 80)
        c.drawRoundRect(prog, 3f, 3f, p)
        if (media.durationMs > 0) {
            val ratio = (media.positionMs.coerceAtMost(media.durationMs).toFloat() / media.durationMs).coerceIn(0f,1f)
            p.color = cyan
            c.drawRoundRect(RectF(prog.left, prog.top, prog.left + prog.width() * ratio, prog.bottom), 3f, 3f, p)
        }

        text(c, "⏮", r.left + 88f, r.bottom - 24f, 24f, white, true, Paint.Align.CENTER)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = cyanSoft
        c.drawCircle(r.centerX(), r.bottom - 31f, 27f, p)
        p.style = Paint.Style.FILL
        text(c, if (media.playing) "Ⅱ" else "▶", r.centerX(), r.bottom - 23f, 22f, white, true, Paint.Align.CENTER)
        text(c, "⏭", r.right - 88f, r.bottom - 24f, 24f, white, true, Paint.Align.CENTER)
    }

    private fun drawQuick(c: Canvas, r: RectF) {
        panel(c, r, 18f, panel2, Color.rgb(9, 111, 144))
        text(c, "빠른 제어", r.left + 18f, r.top + 29f, 15f, cyan, true)
        text(c, "더보기 ›", r.right - 18f, r.top + 29f, 10f, muted, true, Paint.Align.RIGHT)

        val labels = listOf(
            "주행 모드\nLIVE 연동", "회생 제동\nLIVE 연동", "차량 조명\n편의 제어",
            "스티어링 히팅\n지원", "전면 성에 제거\n지원", "플로팅 바\n바로 표시"
        )
        var idx = 0
        for (row in 0..1) {
            for (col in 0..2) {
                val x = r.left + 18f + col * 126f
                val y = r.top + 48f + row * 83f
                val cell = RectF(x, y, x + 116f, y + 73f)
                panel(c, cell, 11f, Color.rgb(5, 30, 46), Color.rgb(11, 75, 96))
                val parts = labels[idx++].split("\n")
                text(c, parts[0], cell.left + 12f, cell.top + 29f, 11f, white, true)
                text(c, parts[1], cell.left + 12f, cell.top + 51f, 9f, green, false)
            }
        }
    }

    private fun drawSeat(c: Canvas, r: RectF) {
        panel(c, r, 18f, panel2, Color.rgb(9, 111, 144))
        text(c, "메모리 시트", r.left + 18f, r.top + 29f, 15f, cyan, true)
        text(c, "나만의 드라이빙 포지션", r.left + 18f, r.top + 51f, 10f, muted, false)

        val capY = r.top + 72f
        val w = 82f
        listOf("M1","M2","M3").forEachIndexed { i, label ->
            val cell = RectF(r.left + 18f + i * 91f, capY, r.left + 18f + i * 91f + w, capY + 69f)
            panel(c, cell, 11f, if (i == 0) Color.rgb(7, 47, 67) else Color.rgb(5, 28, 43), if (i == 0) cyan else Color.rgb(11, 67, 86))
            text(c, label, cell.centerX(), cell.top + 29f, 16f, white, true, Paint.Align.CENTER)
            text(c, if (i == 0) "일상" else if (i == 1) "가족" else "장거리", cell.centerX(), cell.top + 52f, 9f, muted, false, Paint.Align.CENTER)
        }

        val state = RectF(r.left + 18f, r.bottom - 66f, r.right - 18f, r.bottom - 18f)
        panel(c, state, 11f, Color.rgb(5, 27, 42), Color.rgb(10, 65, 83))
        text(c, "API 검증 상태 보기", state.left + 15f, state.top + 29f, 11f, white, true)
        text(c, "›", state.right - 16f, state.top + 31f, 21f, cyan, true, Paint.Align.RIGHT)
    }

    private fun metric(c: Canvas, r: RectF, label: String, value: String, sub: String? = null) {
        panel(c, r, 11f, Color.rgb(5, 26, 40), Color.rgb(11, 70, 90))
        text(c, label, r.left + 12f, r.top + 21f, 9.5f, muted, true)
        text(c, value, r.left + 12f, r.top + 49f, 17f, white, true)
        sub?.let { text(c, it, r.left + 12f, r.top + 67f, 8.5f, dim, false) }
    }

    private fun panel(c: Canvas, r: RectF, radius: Float, fill: Int, stroke: Int) {
        p.style = Paint.Style.FILL
        p.color = fill
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = stroke
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.FILL
    }

    private fun roundedGradient(c: Canvas, r: RectF, a: Int, b: Int, radius: Float, stroke: Int) {
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, a, b, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = stroke
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.FILL
    }

    private fun pill(c: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, color: Int, fill: Int) {
        val r = RectF(x, y, x + w, y + h)
        panel(c, r, h / 2f, fill, Color.argb(180, Color.red(color), Color.green(color), Color.blue(color)))
        text(c, label, r.centerX(), r.centerY() + 5f, 10f, color, true, Paint.Align.CENTER)
    }

    private fun text(
        c: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = color
        p.textSize = size
        p.textAlign = align
        p.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        c.drawText(value, x, y, p)
    }

    private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun formatDistance(m: Int): String = if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000.0) else "${m} m"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val sx = width / BASE_W
        val sy = height / BASE_H
        val scale = min(sx, sy)
        val ox = (width - BASE_W * scale) / 2f
        val oy = (height - BASE_H * scale) / 2f
        val x = (event.x - ox) / scale
        val y = (event.y - oy) / scale

        when {
            x in 0f..1110f && y in 0f..570f -> onDriveView?.invoke()
            x in 0f..360f && y in 735f..820f -> {
                when {
                    x < 130f -> onMediaPrevious?.invoke()
                    x < 230f -> onMediaPlayPause?.invoke()
                    else -> onMediaNext?.invoke()
                }
            }
            x in 372f..790f && y in 582f..820f -> onQuickControl?.invoke()
            x in 802f..1110f && y in 582f..820f -> onSeatMemory?.invoke()
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val BASE_W = 1500f
        private const val BASE_H = 820f
    }
}
