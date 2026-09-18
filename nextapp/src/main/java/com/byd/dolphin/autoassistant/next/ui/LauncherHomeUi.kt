package com.byd.dolphin.autoassistant.next.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.dolphin.autoassistant.next.NextRuntime
import com.byd.dolphin.autoassistant.next.diagnostics.DolphinDiagnostics
import com.byd.dolphin.autoassistant.next.integrated.InstalledAppItem
import com.byd.dolphin.autoassistant.next.integrated.InstalledApps
import com.byd.dolphin.autoassistant.next.integrated.VehicleActionController
import com.byd.dolphin.autoassistant.next.launcher.LauncherMediaState
import com.byd.dolphin.autoassistant.next.launcher.LauncherVehicleInfo
import com.byd.dolphin.autoassistant.next.launcher.TireValue
import com.byd.dolphin.autoassistant.next.vehicle.BydGateway
import com.byd.dolphin.autoassistant.next.vehicle.VehicleRepository
import com.byd.dolphin.autoassistant.next.vehicle.VehicleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private val LBg = Color(0xFF050A0F)
private val LPanel = Color(0xFF0C151D)
private val LPanel2 = Color(0xFF101D27)
private val LBorder = Color(0xFF263A47)
private val LText = Color(0xFFF4F8FA)
private val LMuted = Color(0xFF8EA2B1)
private val LCyan = Color(0xFF36D7FF)
private val LBlue = Color(0xFF2A76FF)
private val LGreen = Color(0xFF47D895)
private val LAmber = Color(0xFFFFC857)
private val LRed = Color(0xFFFF6671)

@Composable
fun DolphinLauncherHome(
    state: VehicleState,
    repository: VehicleRepository,
    diagnostics: DolphinDiagnostics,
    activity: ComponentActivity,
    onDrive: () -> Unit,
    onVehicle: () -> Unit,
    onAudio: () -> Unit,
    onScreen: () -> Unit,
    onAutomation: () -> Unit,
    onLab: () -> Unit,
    onUpdate: () -> Unit
) {
    val media by NextRuntime.launcherMedia.state.collectAsState()
    val vehicleInfo by NextRuntime.launcherVehicleInfo.state.collectAsState()
    val diag by diagnostics.state.collectAsState()
    var appDrawer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(repository) {
        repository.setSurroundingVisionActive(true)
        onDispose { repository.setSurroundingVisionActive(false) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.60f),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LauncherMediaCard(
                media = media,
                modifier = Modifier
                    .weight(0.27f)
                    .fillMaxHeight()
            )

            LauncherFsdCard(
                state = state,
                modifier = Modifier
                    .weight(0.46f)
                    .fillMaxHeight(),
                onOpen = onDrive
            )

            LauncherVehicleCard(
                state = state,
                info = vehicleInfo,
                modifier = Modifier
                    .weight(0.27f)
                    .fillMaxHeight(),
                onOpen = onVehicle
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.40f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LauncherMenuTile("◉", "진단", healthLabel(diag.passed, diag.total, diag.failures), LCyan) {
                    diagnostics.runFullDiagnostics()
                    Toast.makeText(context, "DOLPHIN DIAGNOSTICS 시작", Toast.LENGTH_SHORT).show()
                }
                LauncherMenuTile("♫", "오디오", "TTS · 비프 · 경고", LBlue, onAudio)
                LauncherMenuTile("▣", "화면 · HUD", "분할 · T90P · CLUSTER", LCyan, onScreen)
                LauncherMenuTile("⚡", "자동화", "시동 · 미디어", LGreen, onAutomation)
                LauncherMenuTile("◎", "CLUSTER", "계기판 HUB", LAmber, onScreen)
                LauncherMenuTile("◇", "차량 · LAB", "미러 · 실내등 · 시트", LRed, onLab)
                LauncherMenuTile("↻", "업데이트", "최신 Release 확인", LMuted, onUpdate)
                LauncherMenuTile("▦", "앱 서랍", "설치 앱", LCyan) { appDrawer = true }
            }

            LauncherQuickBar(
                state = state,
                activity = activity,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp),
                onApps = { appDrawer = true }
            )
        }
    }

    if (appDrawer) {
        LauncherAppDrawer(
            context = context,
            onDismiss = { appDrawer = false }
        )
    }
}

@Composable
private fun LauncherMediaCard(
    media: LauncherMediaState,
    modifier: Modifier
) {
    Surface(
        modifier = modifier,
        color = LPanel,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, LBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "MEDIA",
                    color = LCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    media.appLabel,
                    color = LMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = Color(0xFF071017),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LBorder)
            ) {
                val art = media.artwork
                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = "앨범 커버",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "♫",
                            color = LCyan.copy(alpha = 0.65f),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Text(
                media.title,
                color = LText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                media.artist.ifBlank { if (media.connected) media.album else "미디어 세션 대기" },
                color = LMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val progress = if (media.durationMs > 0L) {
                (media.positionMs.toFloat() / media.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                color = LCyan,
                trackColor = LBorder
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MediaControl("◀◀") { NextRuntime.launcherMedia.previous() }
                MediaControl(if (media.isPlaying) "Ⅱ" else "▶") { NextRuntime.launcherMedia.togglePlay() }
                MediaControl("▶▶") { NextRuntime.launcherMedia.next() }
            }
        }
    }
}

@Composable
private fun MediaControl(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(42.dp),
        color = LPanel2,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, LBorder),
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = LText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LauncherFsdCard(
    state: VehicleState,
    modifier: Modifier,
    onOpen: () -> Unit
) {
    Surface(
        modifier = modifier,
        color = LPanel,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, LBorder),
        onClick = onOpen
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FSD · SAFE DRIVE", color = LText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("SURROUNDING VISION · 실제 차량 신호", color = LMuted, fontSize = 8.sp)
                }
                LauncherBadge(
                    state.iccActive.value?.let { if (it) "ICC ACTIVE" else "ICC OFF" } ?: "ICC —",
                    if (state.iccActive.value == true) LGreen else LMuted
                )
            }

            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = Color(0xFF061018),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LBorder)
            ) {
                Box(Modifier.fillMaxSize()) {
                    LauncherFsdCanvas(state)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(9.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        LauncherBadge(
                            state.bsdRaw.value?.let { "BSD $it" } ?: "BSD —",
                            if (state.bsdRaw.value != null) LAmber else LMuted
                        )
                        LauncherBadge(
                            state.turn.value ?: "TURN —",
                            if (state.turn.value != null) LGreen else LMuted
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(9.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        LauncherBadge("안전운전 · TBT", LCyan)
                        Spacer(Modifier.height(4.dp))
                        LauncherBadge("카메라/제한속도 · BETA", LAmber)
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            state.speedKph.value?.let { String.format("%.0f", it) } ?: "—",
                            color = LText,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("km/h", color = LMuted, fontSize = 8.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MiniStatus("GEAR", state.gear.value ?: "—", Modifier.weight(1f))
                MiniStatus("REGEN", state.regenMode.value ?: "—", Modifier.weight(1f))
                MiniStatus("MODE", state.driveMode.value ?: "—", Modifier.weight(1f))
                MiniStatus(
                    "SENSORS",
                    state.parkingSensors.count { !it.stale }.toString() + "/8",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LauncherFsdCanvas(state: VehicleState) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * 0.64f

        val left = Path().apply {
            moveTo(w * 0.10f, h)
            cubicTo(w * 0.24f, h * 0.68f, w * 0.36f, h * 0.24f, w * 0.44f, 0f)
        }
        val right = Path().apply {
            moveTo(w * 0.90f, h)
            cubicTo(w * 0.76f, h * 0.68f, w * 0.64f, h * 0.24f, w * 0.56f, 0f)
        }
        drawPath(left, LCyan.copy(alpha = 0.20f), style = Stroke(width = 2.5f))
        drawPath(right, LCyan.copy(alpha = 0.20f), style = Stroke(width = 2.5f))

        val carW = max(56f, w * 0.10f)
        val carH = carW * 1.75f
        val car = Rect(cx - carW / 2f, cy - carH / 2f, cx + carW / 2f, cy + carH / 2f)
        drawRoundRect(
            color = Color(0xFFD7E7EC),
            topLeft = car.topLeft,
            size = car.size,
            cornerRadius = CornerRadius(carW * 0.26f, carW * 0.26f)
        )
        drawRoundRect(
            color = Color(0xFF122733),
            topLeft = Offset(car.left + carW * 0.15f, car.top + carH * 0.20f),
            size = Size(carW * 0.70f, carH * 0.25f),
            cornerRadius = CornerRadius(carW * 0.10f, carW * 0.10f)
        )

        val points = listOf(
            Offset(car.left - carW * 0.55f, car.top + carH * 0.15f),
            Offset(car.right + carW * 0.55f, car.top + carH * 0.15f),
            Offset(car.left - carW * 0.55f, car.bottom - carH * 0.10f),
            Offset(car.right + carW * 0.55f, car.bottom - carH * 0.10f),
            Offset(car.left - carW * 0.72f, cy),
            Offset(car.right + carW * 0.72f, cy),
            Offset(cx - carW * 0.26f, car.top - carW * 0.62f),
            Offset(cx + carW * 0.26f, car.top - carW * 0.62f)
        )
        state.parkingSensors.take(8).forEachIndexed { index, sensor ->
            val p = points.getOrNull(index) ?: return@forEachIndexed
            val live = !sensor.stale && (sensor.distanceCm != null || sensor.probeStateRaw != null)
            val color = if (live) LCyan else LMuted.copy(alpha = 0.26f)
            drawCircle(color.copy(alpha = if (live) 0.25f else 0.14f), radius = if (live) 16f else 9f, center = p)
            drawCircle(color, radius = if (live) 5.5f else 3.5f, center = p)
        }
    }
}

@Composable
private fun LauncherVehicleCard(
    state: VehicleState,
    info: LauncherVehicleInfo,
    modifier: Modifier,
    onOpen: () -> Unit
) {
    Surface(
        modifier = modifier,
        color = LPanel,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, LBorder),
        onClick = onOpen
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VEHICLE", color = LText, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                LauncherBadge(state.gear.value ?: "—", LCyan)
            }

            TireStrip(info)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VehicleMetric("속도", state.speedKph.value?.let { String.format("%.0f", it) + " km/h" } ?: "—", Modifier.weight(1f))
                VehicleMetric("주행가능", info.remainingRangeKm?.let { String.format("%.0f km", it) } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VehicleMetric("충전 후", info.sinceChargeKm?.let { String.format("%.1f km", it) } ?: "—", Modifier.weight(1f))
                VehicleMetric("이전 주행", info.previousChargeTripKm?.let { String.format("%.1f km", it) } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VehicleMetric("회생", state.regenMode.value ?: "—", Modifier.weight(1f))
                VehicleMetric("모드", state.driveMode.value ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VehicleMetric("라이트", info.lightSummary, Modifier.weight(1f))
                VehicleMetric("오토홀드", autoHoldLabel(state.autoHoldRaw.value), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VehicleMetric("사이드", state.epbApplied.value?.let { if (it) "ON" else "OFF" } ?: "—", Modifier.weight(1f))
                VehicleMetric("ICC", state.iccActive.value?.let { if (it) "ON" else "OFF" } ?: "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TireStrip(info: LauncherVehicleInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF071017),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, LBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TireCell("FL", info.tireFl)
            TireCell("FR", info.tireFr)
            TireCell("RL", info.tireRl)
            TireCell("RR", info.tireRr)
        }
    }
}

@Composable
private fun TireCell(label: String, value: TireValue) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = LMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        Text(formatTire(value.raw), color = if (value.raw == null) LMuted else LText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VehicleMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = LPanel2,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, LBorder)
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(title, color = LMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            Text(
                value,
                color = LText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LauncherMenuTile(
    icon: String,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(154.dp)
            .fillMaxHeight(),
        color = LPanel,
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(icon, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(title, color = LText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = LMuted,
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LauncherQuickBar(
    state: VehicleState,
    activity: ComponentActivity,
    modifier: Modifier,
    onApps: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = modifier,
        color = Color(0xFF0A141C),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, LBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickIcon("♨", "핸들 열선") {
                val on = state.steeringHeat.value == true
                runQuick(scope, context, "핸들 열선") {
                    VehicleActionController.setSteeringHeat(context, !on)
                }
            }
            QuickIcon("▤", "운전석 열선") {
                val current = state.driverHeat.value ?: 0
                runQuick(scope, context, "운전석 열선") {
                    VehicleActionController.setSeatHeat(context, 1, (current + 1) % 3)
                }
            }
            QuickIcon("◫", "앞유리 성에") {
                runQuick(scope, context, "앞유리 성에") {
                    VehicleActionController.setDefrost(context, true, true)
                }
            }
            QuickIcon("▧", "뒷유리 성에") {
                runQuick(scope, context, "뒷유리 성에") {
                    VehicleActionController.setDefrost(context, false, true)
                }
            }
            QuickIcon("−", "풍량 감소") {
                runQuick(scope, context, "풍량") {
                    val g = BydGateway(context)
                    val current = g.readNumber(BydGateway.AC, "getAcWindLevel")?.toInt() ?: 3
                    VehicleActionController.setFanLevel(context, current - 1)
                }
            }
            QuickIcon("+", "풍량 증가") {
                runQuick(scope, context, "풍량") {
                    val g = BydGateway(context)
                    val current = g.readNumber(BydGateway.AC, "getAcWindLevel")?.toInt() ?: 3
                    VehicleActionController.setFanLevel(context, current + 1)
                }
            }
            QuickIcon("−°", "온도 감소") {
                runQuick(scope, context, "온도") {
                    val g = BydGateway(context)
                    val current = g.readNumber(BydGateway.AC, "getAcTemperature", 1)?.toInt() ?: 24
                    VehicleActionController.setTemperature(context, 1, current - 1)
                }
            }
            QuickIcon("+°", "온도 증가") {
                runQuick(scope, context, "온도") {
                    val g = BydGateway(context)
                    val current = g.readNumber(BydGateway.AC, "getAcTemperature", 1)?.toInt() ?: 24
                    VehicleActionController.setTemperature(context, 1, current + 1)
                }
            }
            QuickIcon("AC", "공조 ON/OFF") {
                runQuick(scope, context, "공조") {
                    val g = BydGateway(context)
                    val on = g.readNumber(BydGateway.AC, "getAcStartState")?.toInt() == 1
                    VehicleActionController.setAcPower(context, !on)
                }
            }
            QuickIcon("☼", "실내등 전체 LAB") {
                runQuick(scope, context, "실내등 LAB") {
                    VehicleActionController.toggleInsideLightLab(context, true)
                }
            }
            QuickIcon("▱", "트렁크 LAB") {
                scope.launch(Dispatchers.IO) {
                    val hits = VehicleActionController.discoverTrunk(context)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "트렁크 후보 " + hits.size + "개 · 로그 저장", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            QuickIcon("▰", "선쉐이드 LAB") {
                scope.launch(Dispatchers.IO) {
                    val hits = VehicleActionController.discoverSunshade(context)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "선쉐이드 후보 " + hits.size + "개 · 로그 저장", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            QuickIcon("▦", "앱 서랍", onApps)
        }
    }
}

@Composable
private fun QuickIcon(glyph: String, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(55.dp),
        color = LPanel2,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, LBorder),
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                glyph,
                color = LText,
                fontSize = if (glyph.length <= 1) 22.sp else 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MiniStatus(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = LPanel2,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, LBorder)
    ) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 6.dp)) {
            Text(title, color = LMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold)
            Text(value, color = LText, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun LauncherBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.50f))
    ) {
        Text(
            text,
            color = color,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LauncherAppDrawer(
    context: Context,
    onDismiss: () -> Unit
) {
    val apps = remember { InstalledApps.launcherApps(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("앱 서랍", color = LText, fontWeight = FontWeight.Bold)
        },
        text = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(470.dp),
                color = LBg
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(10.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        AppIconOnly(app) {
                            context.packageManager.getLaunchIntentForPackage(app.packageName)
                                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                ?.let(context::startActivity)
                            onDismiss()
                        }
                    }
                }
            }
        },
        confirmButton = {
            Surface(
                color = LCyan.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, LCyan.copy(alpha = 0.55f)),
                onClick = onDismiss
            ) {
                Text("닫기", color = LText, fontSize = 9.sp, modifier = Modifier.padding(12.dp))
            }
        },
        containerColor = LPanel
    )
}

@Composable
private fun AppIconOnly(app: InstalledAppItem, onClick: () -> Unit) {
    val bitmap = remember(app.packageName) { drawableToImageBitmap(app.icon) }
    Surface(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        color = LPanel2,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, LBorder)
    ) {
        Box(
            modifier = Modifier.padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    app.label.take(1),
                    color = LCyan,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun drawableToImageBitmap(drawable: Drawable?): ImageBitmap? {
    drawable ?: return null
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
    return runCatching {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}

private fun autoHoldLabel(raw: Int?): String = when (raw) {
    0 -> "OFF"
    1 -> "ON"
    2 -> "HOLD"
    else -> "—"
}

private fun formatTire(raw: Double?): String {
    raw ?: return "—"
    return when {
        raw in 1.0..5.0 -> String.format("%.2f", raw)
        raw in 100.0..500.0 -> String.format("%.0f", raw)
        else -> String.format("%.1f", raw)
    }
}

private fun healthLabel(passed: Int, total: Int, failures: Int): String =
    if (total == 0) "READY"
    else if (failures == 0) "PASS " + passed + "/" + total
    else "CHECK " + passed + "/" + total

private fun runQuick(
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    title: String,
    block: () -> VehicleActionController.ActionResult
) {
    scope.launch(Dispatchers.IO) {
        val result = block()
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                title + " · " + if (result.accepted) "요청됨" else "실패/미확인",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
