package com.byd.dolphin.autoassistant.next.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.dolphin.autoassistant.next.audio.AppAudioRouteLab
import com.byd.dolphin.autoassistant.next.automation.BootAutomationController
import com.byd.dolphin.autoassistant.next.display.NextMultiWindowController
import com.byd.dolphin.autoassistant.next.display.VerifiedTwoPaneController
import com.byd.dolphin.autoassistant.next.display.WindowLayoutMode
import com.byd.dolphin.autoassistant.next.hud.NextHudBridge
import com.byd.dolphin.autoassistant.next.integrated.BootAppRule
import com.byd.dolphin.autoassistant.next.integrated.InstalledAppItem
import com.byd.dolphin.autoassistant.next.integrated.InstalledApps
import com.byd.dolphin.autoassistant.next.integrated.IntegratedSettings
import com.byd.dolphin.autoassistant.next.integrated.MirrorSeatLab
import com.byd.dolphin.autoassistant.next.integrated.VehicleActionController
import com.byd.dolphin.autoassistant.next.overlay.QuickDockOverlay
import com.byd.dolphin.autoassistant.next.permissions.NotificationAccessBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val IFBg = Color(0xFF0E1821)
private val IFBorder = Color(0xFF253845)
private val IFText = Color(0xFFF1F7FA)
private val IFMuted = Color(0xFF91A3B2)
private val IFCyan = Color(0xFF39CFF5)
private val IFGreen = Color(0xFF45D48A)
private val IFAmber = Color(0xFFFFC857)
private val IFRed = Color(0xFFFF6B6B)

@Composable
fun IntegratedDisplayPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember { InstalledApps.launcherApps(context) }
    var selected by remember {
        mutableStateOf(apps.take(4).map { it.packageName }.toMutableList())
    }
    while (selected.size < 4) selected.add("")
    var pickerSlot by remember { mutableIntStateOf(-1) }
    var status by remember { mutableStateOf("대기") }
    var dockAppsDialog by remember { mutableStateOf(false) }
    var floatingAppsDialog by remember { mutableStateOf(false) }
    var floatingEnabled by remember { mutableStateOf(IntegratedSettings.floatingEnabled(context)) }
    var dockTimeout by remember { mutableIntStateOf(IntegratedSettings.quickDockTimeoutSeconds(context)) }
    var clusterEnabled by remember { mutableStateOf(IntegratedSettings.clusterTbtEnabled(context)) }
    var hudAuto by remember { mutableStateOf(IntegratedSettings.hudAutoForward(context)) }
    var splitRatio by remember { mutableIntStateOf(IntegratedSettings.splitPrimaryRatio(context)) }
    var floatingCollapse by remember { mutableIntStateOf(IntegratedSettings.floatingCollapseDelaySeconds(context)) }
    var floatingScale by remember { mutableIntStateOf(IntegratedSettings.floatingScalePercent(context)) }
    var floatingOpacity by remember { mutableIntStateOf(IntegratedSettings.floatingOpacityPercent(context)) }

    FeatureTitle("멀티 윈도우", "2분할 VERIFIED 경로 + 3/4분할·팝업 freeform LAB")
    FeatureCard {
        for (i in 0 until 4) {
            val item = apps.firstOrNull { it.packageName == selected[i] }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SLOT " + (i + 1), color = IFMuted, fontSize = 9.sp, modifier = Modifier.width(54.dp))
                SmallButton(item?.label ?: "앱 선택", IFCyan) { pickerSlot = i }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SmallButton("2분할", IFGreen) {
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        NextMultiWindowController.launch(
                            context,
                            selected,
                            WindowLayoutMode.TWO,
                            splitRatio
                        ).detail
                    }
                }
            }
            SmallButton("3분할 LAB", IFAmber) {
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        NextMultiWindowController.launch(context, selected, WindowLayoutMode.THREE).detail
                    }
                }
            }
            SmallButton("4분할 LAB", IFAmber) {
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        NextMultiWindowController.launch(context, selected, WindowLayoutMode.FOUR).detail
                    }
                }
            }
            SmallButton("팝업 LAB", IFAmber) {
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        NextMultiWindowController.launch(context, selected, WindowLayoutMode.POPUP).detail
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "2분할 비율 · " + splitRatio + "% / " + (100 - splitRatio) + "% · 1% 단위",
            color = IFText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            SmallButton("−1", IFCyan) {
                splitRatio = (splitRatio - 1).coerceIn(20, 80)
                IntegratedSettings.setSplitPrimaryRatio(context, splitRatio)
            }
            Slider(
                value = splitRatio.toFloat(),
                onValueChange = {
                    splitRatio = it.roundToInt().coerceIn(20, 80)
                },
                onValueChangeFinished = {
                    IntegratedSettings.setSplitPrimaryRatio(context, splitRatio)
                },
                valueRange = 20f..80f,
                steps = 59,
                modifier = Modifier.weight(1f)
            )
            SmallButton("+1", IFCyan) {
                splitRatio = (splitRatio + 1).coerceIn(20, 80)
                IntegratedSettings.setSplitPrimaryRatio(context, splitRatio)
            }
            SmallButton("비율 적용", IFGreen) {
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        VerifiedTwoPaneController.applyRatio(context, splitRatio).detail
                    }
                }
            }
        }

        StatusText(status)
    }

    FeatureTitle("플로팅바 · 하단 퀵독", "v32.3 동작 복원 · 8개 표시 / 9개+ 가로스크롤 · 이동 · idle 최소화")
    FeatureCard {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton("퀵독 지금 열기", IFCyan) { QuickDockOverlay.showQuickDock(context) }
            SmallButton(if (floatingEnabled) "플로팅 OFF" else "플로팅 ON", if (floatingEnabled) IFGreen else IFCyan) {
                floatingEnabled = !floatingEnabled
                IntegratedSettings.setFloatingEnabled(context, floatingEnabled)
                QuickDockOverlay.refreshFloating(context)
            }
            SmallButton("퀵독 앱 선택", IFCyan) { dockAppsDialog = true }
            SmallButton("플로팅 앱 선택", IFCyan) { floatingAppsDialog = true }
        }
        Spacer(Modifier.height(8.dp))
        Text("퀵독 자동 닫힘", color = IFMuted, fontSize = 9.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 15, 20, 30, 60).forEach { sec ->
                SmallButton(sec.toString() + "초", if (dockTimeout == sec) IFGreen else IFMuted) {
                    dockTimeout = sec
                    IntegratedSettings.setQuickDockTimeoutSeconds(context, sec)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("플로팅 최소화 시간", color = IFMuted, fontSize = 9.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 15, 30, 60).forEach { sec ->
                SmallButton(sec.toString() + "초", if (floatingCollapse == sec) IFGreen else IFMuted) {
                    floatingCollapse = sec
                    IntegratedSettings.setFloatingCollapseDelaySeconds(context, sec)
                    QuickDockOverlay.refreshFloating(context)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("플로팅 크기 · " + floatingScale + "%", color = IFMuted, fontSize = 9.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            SmallButton("−10", IFCyan) {
                floatingScale = (floatingScale - 10).coerceIn(50, 150)
                IntegratedSettings.setFloatingScalePercent(context, floatingScale)
                QuickDockOverlay.refreshFloating(context)
            }
            Slider(
                value = floatingScale.toFloat(),
                onValueChange = { floatingScale = it.roundToInt().coerceIn(50, 150) },
                onValueChangeFinished = {
                    IntegratedSettings.setFloatingScalePercent(context, floatingScale)
                    QuickDockOverlay.refreshFloating(context)
                },
                valueRange = 50f..150f,
                steps = 9,
                modifier = Modifier.weight(1f)
            )
            SmallButton("+10", IFCyan) {
                floatingScale = (floatingScale + 10).coerceIn(50, 150)
                IntegratedSettings.setFloatingScalePercent(context, floatingScale)
                QuickDockOverlay.refreshFloating(context)
            }
        }

        Text("투명도 · " + floatingOpacity + "%", color = IFMuted, fontSize = 9.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Slider(
                value = floatingOpacity.toFloat(),
                onValueChange = { floatingOpacity = it.roundToInt().coerceIn(30, 100) },
                onValueChangeFinished = {
                    IntegratedSettings.setFloatingOpacityPercent(context, floatingOpacity)
                    QuickDockOverlay.refreshFloating(context)
                },
                valueRange = 30f..100f,
                steps = 13,
                modifier = Modifier.weight(1f)
            )
            SmallButton("위치 초기화", IFAmber) {
                QuickDockOverlay.resetFloatingPosition(context)
            }
        }
        StatusText("플로팅은 15초 기본 idle 후 작은 ◆ 아이콘으로 최소화 · ◆ 탭하면 복원 · 손잡이 드래그 이동 · 앱은 실제 앱 아이콘만 표시")
    }

    FeatureTitle("TMAP T90P HUD · 계기판 TBT", "TBT 파싱은 실사용 / T90P 데이터 프레임·밝기·HUDAudio는 LAB")
    FeatureCard {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SmallButton("페어링 기기 스캔", IFCyan) {
                status = NextHudBridge.scanPaired(context).joinToString(" | ").take(600)
            }
            SmallButton("HUDDATA 연결", IFAmber) {
                NextHudBridge.connectData(context) { ok, msg ->
                    status = msg
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
            SmallButton("HUDAudio 확인", IFAmber) {
                NextHudBridge.probeAudioProfile(context) { ok, msg ->
                    status = msg
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
            SmallButton("밝기 8 LAB", IFRed) {
                status = "brightness packet sent=" + NextHudBridge.sendBrightnessLab(context, 8)
            }
            SmallButton("HUD TBT TEST", IFRed) {
                status = "TBT test packet sent=" + NextHudBridge.sendTestNavigation(context)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SmallButton(if (clusterEnabled) "계기판 TBT ON" else "계기판 TBT OFF", if (clusterEnabled) IFGreen else IFMuted) {
                clusterEnabled = !clusterEnabled
                IntegratedSettings.setClusterTbtEnabled(context, clusterEnabled)
                status = NextHudBridge.clusterCapability(context)
            }
            SmallButton(if (hudAuto) "HUD 자동전송 ON" else "HUD 자동전송 OFF", if (hudAuto) IFAmber else IFMuted) {
                hudAuto = !hudAuto
                IntegratedSettings.setHudAutoForward(context, hudAuto)
            }
            SmallButton("알림/TBT 권한 확인", IFCyan) {
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        val result = NotificationAccessBridge.ensure(context)
                        "알림/TBT 권한=" + result.enabled + " · " + result.detail
                    }
                }
            }
        }
        StatusText(status)
    }

    FeatureTitle(
        "계기판 CLUSTER HUB",
        "Native TBT · 앱 투사 · 커스텀 UI · 테마/Qt/RCC · Display/Surface · 순정 복구"
    )
    ClusterHubPanel()

    if (pickerSlot >= 0) {
        SingleAppPicker(
            apps = apps,
            title = "SLOT " + (pickerSlot + 1) + " 앱 선택",
            onDismiss = { pickerSlot = -1 },
            onSelect = { app ->
                val next = selected.toMutableList()
                next[pickerSlot] = app.packageName
                selected = next
                pickerSlot = -1
            }
        )
    }
    if (dockAppsDialog) {
        MultiAppPicker(
            apps, "퀵독 앱 선택",
            IntegratedSettings.selectedDockApps(context),
            { dockAppsDialog = false }
        ) {
            IntegratedSettings.setSelectedDockApps(context, it)
            dockAppsDialog = false
        }
    }
    if (floatingAppsDialog) {
        MultiAppPicker(
            apps, "플로팅바 앱 선택",
            IntegratedSettings.selectedFloatingApps(context),
            { floatingAppsDialog = false }
        ) {
            IntegratedSettings.setSelectedFloatingApps(context, it)
            floatingAppsDialog = false
            QuickDockOverlay.refreshFloating(context)
        }
    }
}

@Composable
fun IntegratedAutomationPanel() {
    val context = LocalContext.current
    val apps = remember { InstalledApps.launcherApps(context) }
    var picker by remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf(IntegratedSettings.bootRules(context)) }
    var status by remember { mutableStateOf("일반 앱은 전면 실행 · 미디어 앱은 화면을 띄우지 않고 백그라운드 세션 준비 후 재생") }

    FeatureTitle("시동 시 앱 자동 실행", "일반 앱은 전면 실행 · 미디어 앱은 백그라운드 MediaSession/MediaBrowser 재생 · 앱별 0.x초 지연")
    FeatureCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("자동실행 앱 선택", IFCyan) { picker = true }
            SmallButton("지금 테스트", IFAmber) {
                BootAutomationController(context).testNow()
                status = "현재 규칙으로 즉시 테스트 시작 · 미디어 앱은 전면 실행 없이 MEDIA_BG 경로 사용"
            }
            SmallButton("미디어 세션 권한 확인", IFCyan) {
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        val result = NotificationAccessBridge.ensure(context)
                        "미디어 세션 권한=" + result.enabled + " · " + result.detail
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        rules.forEachIndexed { index, rule ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(rule.label + " · " + rule.packageName, color = IFText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("실행", color = IFMuted, fontSize = 9.sp)
                    listOf(0.0,0.2,0.5,1.0,2.0,5.0).forEach { d ->
                        SmallButton(d.toString() + "s", if (rule.delaySeconds == d) IFGreen else IFMuted) {
                            rules = rules.toMutableList().also { it[index] = rule.copy(delaySeconds = d) }
                            IntegratedSettings.setBootRules(context, rules)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallButton(if (rule.mediaPlay) "미디어 백그라운드 ON" else "미디어 백그라운드 OFF", if (rule.mediaPlay) IFGreen else IFMuted) {
                        rules = rules.toMutableList().also { it[index] = rule.copy(mediaPlay = !rule.mediaPlay) }
                        IntegratedSettings.setBootRules(context, rules)
                    }
                    if (rule.mediaPlay) {
                        Text("재생 지연", color = IFMuted, fontSize = 9.sp)
                        listOf(0.2,0.5,1.0,2.0).forEach { d ->
                            SmallButton("+" + d + "s", if (rule.mediaDelaySeconds == d) IFCyan else IFMuted) {
                                rules = rules.toMutableList().also { it[index] = rule.copy(mediaDelaySeconds = d) }
                                IntegratedSettings.setBootRules(context, rules)
                            }
                        }
                    }
                }
            }
        }
        StatusText(status)
    }

    if (picker) {
        MultiAppPicker(
            apps,
            "시동 자동실행 앱 선택",
            rules.map { it.packageName }.toSet(),
            { picker = false }
        ) { selected ->
            val old = rules.associateBy { it.packageName }
            rules = apps.filter { selected.contains(it.packageName) }.mapIndexed { index, app ->
                old[app.packageName] ?: BootAppRule(
                    app.packageName,
                    app.label,
                    enabled = true,
                    delaySeconds = index * 0.5
                )
            }
            IntegratedSettings.setBootRules(context, rules)
            picker = false
        }
    }
}

@Composable
fun IntegratedVehicleLabPanel() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("LAB 기능은 정차/P 상태에서 테스트 권장") }

    FeatureTitle("다운미러 · 메모리시트 · 차체 LAB", "capture/read-back 우선 · 미확인 actuator는 로그로 판별")
    FeatureCard {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SmallButton("미러 NORMAL 저장", IFCyan) {
                status = "NORMAL=" + MirrorSeatLab.capture(context, false)
            }
            SmallButton("미러 DOWN 저장", IFCyan) {
                status = "DOWN=" + MirrorSeatLab.capture(context, true)
            }
            SmallButton("NORMAL 적용", IFAmber) {
                status = "NORMAL apply verified=" + MirrorSeatLab.apply(context, false)
            }
            SmallButton("DOWN 적용", IFAmber) {
                status = "DOWN apply verified=" + MirrorSeatLab.apply(context, true)
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SmallButton("메모리시트 API 스캔", IFRed) {
                val hits = MirrorSeatLab.scanSeatMemoryApis(context)
                status = "seat/memory 후보 " + hits.size + "개 · 로그 저장"
            }
            SmallButton("실내등 전체 ON LAB", IFRed) {
                status = VehicleActionController.toggleInsideLightLab(context, true).detail
            }
            SmallButton("실내등 전체 OFF LAB", IFRed) {
                status = VehicleActionController.toggleInsideLightLab(context, false).detail
            }
            SmallButton("트렁크 API 스캔", IFRed) {
                status = "trunk 후보 " + VehicleActionController.discoverTrunk(context).size + "개 · 로그 저장"
            }
            SmallButton("선쉐이드 API 스캔", IFRed) {
                status = "sunshade 후보 " + VehicleActionController.discoverSunshade(context).size + "개 · 로그 저장"
            }
        }
        StatusText(status)
    }
}

@Composable
fun IntegratedAudioRoutePanel() {
    val context = LocalContext.current
    val apps = remember { InstalledApps.launcherApps(context) }
    var picker by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(IntegratedSettings.selectedDriverAudioApps(context)) }
    var status by remember { mutableStateOf("선택 앱의 UID → 운전석 전용 출력 가능 조건을 진단") }

    FeatureTitle("설치 앱 → 운전석 오디오", "stream14는 앱 자체 출력만 확인됨 · 타 앱은 UID routing 권한/디바이스 식별 필요")
    FeatureCard {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SmallButton("앱 선택 (" + selected.size + ")", IFCyan) { picker = true }
            SmallButton("라우팅 진단", IFAmber) {
                val p = AppAudioRouteLab.probe(context)
                status = p.detail + " · outputs=" + p.outputDevices.size + " · affinityMethods=" + p.affinityMethods.size
            }
        }
        StatusText(status)
    }

    if (picker) {
        MultiAppPicker(apps, "운전석 출력 앱 선택", selected, { picker = false }) {
            selected = it
            IntegratedSettings.setSelectedDriverAudioApps(context, it)
            picker = false
        }
    }
}

@Composable
private fun FeatureTitle(title: String, subtitle: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, color = IFText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(subtitle, color = IFMuted, fontSize = 9.sp)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun FeatureCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = IFBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, IFBorder)
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun SmallButton(text: String, color: Color, action: () -> Unit) {
    Button(
        onClick = action,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.13f),
            contentColor = IFText
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusText(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, color = IFMuted, fontSize = 9.sp, lineHeight = 13.sp)
}

@Composable
private fun SingleAppPicker(
    apps: List<InstalledAppItem>,
    title: String,
    onDismiss: () -> Unit,
    onSelect: (InstalledAppItem) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                apps.forEach { app ->
                    Text(
                        app.label + "\n" + app.packageName,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(app) }.padding(10.dp),
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = { SmallButton("닫기", IFCyan, onDismiss) }
    )
}

@Composable
private fun MultiAppPicker(
    apps: List<InstalledAppItem>,
    title: String,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    var selected by remember(title, initial) { mutableStateOf(initial.toMutableSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.height(430.dp).verticalScroll(rememberScrollState())) {
                apps.forEach { app ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = selected.toMutableSet().also {
                                if (!it.add(app.packageName)) it.remove(app.packageName)
                            }
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected.contains(app.packageName),
                            onCheckedChange = {
                                selected = selected.toMutableSet().also { set ->
                                    if (it) set.add(app.packageName) else set.remove(app.packageName)
                                }
                            }
                        )
                        Column {
                            Text(app.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(app.packageName, fontSize = 8.sp, color = IFMuted)
                        }
                    }
                }
            }
        },
        confirmButton = { SmallButton("저장", IFGreen) { onSave(selected.toSet()) } },
        dismissButton = { SmallButton("취소", IFMuted, onDismiss) }
    )
}
