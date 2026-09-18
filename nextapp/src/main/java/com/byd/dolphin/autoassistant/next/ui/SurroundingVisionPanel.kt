package com.byd.dolphin.autoassistant.next.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.dolphin.autoassistant.next.vehicle.ParkingSensorSample
import com.byd.dolphin.autoassistant.next.vehicle.VehicleState
import kotlin.math.max

private val VisionBg = Color(0xFF060C12)
private val VisionSurface = Color(0xFF0D1821)
private val VisionBorder = Color(0xFF243847)
private val VisionText = Color(0xFFF2F8FA)
private val VisionMuted = Color(0xFF8296A7)
private val VisionCyan = Color(0xFF3AD7FF)
private val VisionGreen = Color(0xFF48D991)
private val VisionAmber = Color(0xFFFFC857)
private val VisionRed = Color(0xFFFF6B6B)

@Composable
fun SurroundingVisionSection(state: VehicleState) {
    var expanded by remember { mutableStateOf(false) }
    val liveSensors = state.parkingSensors.count { !it.stale && (it.probeStateRaw != null || it.distanceCm != null) }
    val avmLive = !state.panoramaWork.stale && state.panoramaWork.value != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = VisionSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, VisionBorder)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "FSD / SURROUNDING VISION",
                        color = VisionText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "read-only world model · 실제 신호만 시각화",
                        color = VisionMuted,
                        fontSize = 10.sp
                    )
                }
                VisionBadge("BETA LIVE", VisionAmber)
                Spacer(Modifier.width(6.dp))
                VisionBadge("OBJECTS · LAB", VisionRed)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VisionStat("GEAR", state.gear.value ?: "—", Modifier.weight(1f))
                VisionStat(
                    "SPEED",
                    state.speedKph.value?.let { String.format("%.1f km/h", it) } ?: "—",
                    Modifier.weight(1f)
                )
                VisionStat(
                    "ULTRASONIC",
                    if (liveSensors > 0) liveSensors.toString() + "/8 LIVE" else "NO SIGNAL",
                    Modifier.weight(1f)
                )
                VisionStat(
                    "AVM",
                    if (avmLive) "RAW " + state.panoramaWork.value else "NO SIGNAL",
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = VisionBg,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, VisionBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (expanded) 420.dp else 285.dp)
                        .padding(10.dp)
                ) {
                    SurroundingCanvas(state)

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        VisionBadge(
                            if (state.turn.value == null) "TURN · NO SIGNAL" else "TURN · " + state.turn.value,
                            if (state.turn.value == null) VisionMuted else VisionGreen
                        )
                        Spacer(Modifier.height(5.dp))
                        VisionBadge(
                            state.bsdRaw.value?.let { "BSD RAW · $it" } ?: "BSD · NO SIGNAL",
                            if (state.bsdRaw.value == null) VisionMuted else VisionAmber
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        VisionBadge("LANE MODEL · NO SIGNAL", VisionRed)
                        Spacer(Modifier.height(5.dp))
                        VisionBadge("OBJECT TRACKS · NO SIGNAL", VisionRed)
                    }

                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        color = VisionCyan.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, VisionCyan.copy(alpha = 0.45f)),
                        onClick = { expanded = !expanded }
                    ) {
                        Text(
                            if (expanded) "COLLAPSE" else "EXPAND",
                            color = VisionCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "PARKING SENSOR RING",
                color = VisionMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                val sensors = if (state.parkingSensors.isEmpty()) {
                    (1..8).map { ParkingSensorSample(it, "S$it") }
                } else state.parkingSensors
                sensors.forEach { SensorChip(it) }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VisionStatusBox(
                    "PARKING / ULTRASONIC",
                    if (liveSensors > 0) "LIVE READ-ONLY" else "NO SIGNAL",
                    if (liveSensors > 0) VisionGreen else VisionMuted,
                    Modifier.weight(1f)
                )
                VisionStatusBox(
                    "AVM / 360 CAMERA",
                    if (avmLive) "HAL STATE LIVE" else "NO SIGNAL",
                    if (avmLive) VisionGreen else VisionMuted,
                    Modifier.weight(1f)
                )
                VisionStatusBox(
                    "RADAR OBJECT TRACKS",
                    "LAB · NOT CONNECTED",
                    VisionRed,
                    Modifier.weight(1f)
                )
                VisionStatusBox(
                    "LANE / ROAD MODEL",
                    "LAB · NOT CONNECTED",
                    VisionRed,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SurroundingCanvas(state: VehicleState) {
    Canvas(modifier = Modifier.fillMaxWidth()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * 0.57f

        val leftLane = Path().apply {
            moveTo(w * 0.13f, h)
            cubicTo(w * 0.26f, h * 0.72f, w * 0.34f, h * 0.30f, w * 0.42f, 0f)
        }
        val rightLane = Path().apply {
            moveTo(w * 0.87f, h)
            cubicTo(w * 0.74f, h * 0.72f, w * 0.66f, h * 0.30f, w * 0.58f, 0f)
        }
        drawPath(leftLane, VisionCyan.copy(alpha = 0.10f), style = Stroke(width = 2f))
        drawPath(rightLane, VisionCyan.copy(alpha = 0.10f), style = Stroke(width = 2f))

        drawLine(
            color = VisionBorder.copy(alpha = 0.55f),
            start = Offset(w * 0.18f, h * 0.22f),
            end = Offset(w * 0.82f, h * 0.22f),
            strokeWidth = 1.5f
        )

        val carW = max(68f, w * 0.095f)
        val carH = carW * 2.0f
        val carRect = Rect(
            left = cx - carW / 2f,
            top = cy - carH / 2f,
            right = cx + carW / 2f,
            bottom = cy + carH / 2f
        )

        drawRoundRect(
            color = Color(0xFFCAD9E0),
            topLeft = carRect.topLeft,
            size = carRect.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(carW * 0.25f, carW * 0.25f)
        )
        drawRoundRect(
            color = Color(0xFF14232E),
            topLeft = Offset(carRect.left + carW * 0.16f, carRect.top + carH * 0.18f),
            size = Size(carW * 0.68f, carH * 0.28f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(carW * 0.12f, carW * 0.12f)
        )
        drawRoundRect(
            color = Color(0xFF14232E),
            topLeft = Offset(carRect.left + carW * 0.16f, carRect.top + carH * 0.54f),
            size = Size(carW * 0.68f, carH * 0.20f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(carW * 0.10f, carW * 0.10f)
        )

        if (state.turn.value == "LEFT" || state.turn.value == "HAZARD") {
            drawCircle(VisionAmber, radius = carW * 0.10f, center = Offset(carRect.left - 10f, cy - carH * 0.26f))
        }
        if (state.turn.value == "RIGHT" || state.turn.value == "HAZARD") {
            drawCircle(VisionAmber, radius = carW * 0.10f, center = Offset(carRect.right + 10f, cy - carH * 0.26f))
        }

        val positions = listOf(
            Offset(carRect.left - carW * 0.52f, carRect.top + carH * 0.18f),
            Offset(carRect.right + carW * 0.52f, carRect.top + carH * 0.18f),
            Offset(carRect.left - carW * 0.52f, carRect.bottom - carH * 0.12f),
            Offset(carRect.right + carW * 0.52f, carRect.bottom - carH * 0.12f),
            Offset(carRect.left - carW * 0.70f, cy),
            Offset(carRect.right + carW * 0.70f, cy),
            Offset(cx - carW * 0.30f, carRect.top - carW * 0.70f),
            Offset(cx + carW * 0.30f, carRect.top - carW * 0.70f)
        )

        state.parkingSensors.take(8).forEachIndexed { index, sensor ->
            val point = positions.getOrNull(index) ?: return@forEachIndexed
            val hasData = !sensor.stale && (sensor.probeStateRaw != null || sensor.distanceCm != null)
            val proximity = sensor.distanceCm?.let { 1f - (it.coerceIn(0, 155) / 155f) } ?: 0.25f
            val radius = if (hasData) 7f + proximity * 12f else 6f
            val color = if (hasData) VisionCyan.copy(alpha = 0.45f + proximity * 0.5f) else VisionMuted.copy(alpha = 0.28f)
            drawCircle(color = color, radius = radius + 7f, center = point, style = Stroke(width = 2f))
            drawCircle(color = color, radius = radius, center = point)
            if (hasData) {
                drawLine(
                    color = color.copy(alpha = 0.45f),
                    start = point,
                    end = Offset(
                        x = point.x + (cx - point.x) * 0.26f,
                        y = point.y + (cy - point.y) * 0.26f
                    ),
                    strokeWidth = 1.5f
                )
            }
        }
    }
}

@Composable
private fun SensorChip(sensor: ParkingSensorSample) {
    val live = !sensor.stale && (sensor.probeStateRaw != null || sensor.distanceCm != null)
    val value = buildString {
        append(sensor.label)
        sensor.distanceCm?.let { append(" · " + it + "cm") }
        if (sensor.distanceCm == null && sensor.probeStateRaw != null) append(" · raw " + sensor.probeStateRaw)
        if (!live) append(" · —")
    }
    Surface(
        color = if (live) VisionCyan.copy(alpha = 0.10f) else VisionSurface,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, if (live) VisionCyan.copy(alpha = 0.45f) else VisionBorder)
    ) {
        Text(
            value,
            color = if (live) VisionCyan else VisionMuted,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun VisionStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = VisionBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, VisionBorder)
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(label, color = VisionMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(value, color = VisionText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VisionStatusBox(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = VisionBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(title, color = VisionMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(value, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VisionBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f))
    ) {
        Text(
            text,
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}
