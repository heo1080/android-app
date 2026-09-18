package com.byd.dolphin.autoassistant.next.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.next.audio.NextAudioEngine
import com.byd.dolphin.autoassistant.next.diagnostics.DiagnosticStatus
import com.byd.dolphin.autoassistant.next.diagnostics.DriveObservationStatus
import com.byd.dolphin.autoassistant.next.diagnostics.DolphinDiagnostics
import com.byd.dolphin.autoassistant.next.launcher.LauncherNavigationBus
import com.byd.dolphin.autoassistant.next.settings.AlertMode
import com.byd.dolphin.autoassistant.next.settings.AlertProfile
import com.byd.dolphin.autoassistant.next.settings.AlertSpec
import com.byd.dolphin.autoassistant.next.settings.NextSettings
import com.byd.dolphin.autoassistant.next.settings.PhraseMode
import com.byd.dolphin.autoassistant.next.update.NextUpdater
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import com.byd.dolphin.autoassistant.next.vehicle.Confidence
import com.byd.dolphin.autoassistant.next.vehicle.SignalValue
import com.byd.dolphin.autoassistant.next.vehicle.VehicleRepository
import com.byd.dolphin.autoassistant.next.vehicle.VehicleState

private val Bg = Color(0xFF050A0F)
private val Rail = Color(0xFF091017)
private val Surface1 = Color(0xFF0F1821)
private val Surface2 = Color(0xFF13202B)
private val Surface3 = Color(0xFF182734)
private val TextMain = Color(0xFFF1F7FA)
private val TextMuted = Color(0xFF8B9EAF)
private val Cyan = Color(0xFF39CFF5)
private val Green = Color(0xFF45D48A)
private val Amber = Color(0xFFFFC857)
private val Red = Color(0xFFFF6B6B)
private val Border = Color(0xFF22313F)

private enum class Page(val title: String, val eyebrow: String) {
    HOME("홈", "HOME"),
    DRIVE("주행", "DRIVE"),
    VEHICLE("차량", "VEHICLE"),
    AUDIO("오디오 · 경고", "AUDIO"),
    SCREEN("화면 · HUD", "DISPLAY"),
    AUTOMATION("자동화", "AUTO"),
    LAB("LAB", "LAB")
}

@Composable
fun NextApp(repository: VehicleRepository, audio: NextAudioEngine, diagnostics: DolphinDiagnostics) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val state by repository.state.collectAsState()
    val launcherHomeEpoch by LauncherNavigationBus.homeEpoch.collectAsState()
    var page by remember { mutableStateOf(Page.HOME) }
    var settingsEpoch by remember { mutableIntStateOf(0) }

    LaunchedEffect(launcherHomeEpoch) {
        if (launcherHomeEpoch > 0L) page = Page.HOME
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Surface1,
            primary = Cyan,
            secondary = Green,
            error = Red,
            onBackground = TextMain,
            onSurface = TextMain
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            if (page == Page.HOME) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 12.dp, bottom = 74.dp)
                ) {
                    DolphinLauncherHome(
                        state = state,
                        repository = repository,
                        diagnostics = diagnostics,
                        activity = activity,
                        onDrive = { page = Page.DRIVE },
                        onVehicle = { page = Page.VEHICLE },
                        onAudio = { page = Page.AUDIO },
                        onScreen = { page = Page.SCREEN },
                        onAutomation = { page = Page.AUTOMATION },
                        onLab = { page = Page.LAB },
                        onUpdate = { NextUpdater.check(activity, true) }
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxSize().padding(top = 12.dp, bottom = 74.dp)) {
                    RailMenu(current = page, onSelect = { page = it })
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 26.dp, vertical = 20.dp)
                    ) {
                        Header(
                            page = page,
                            onUpdate = { NextUpdater.check(activity, true) }
                        )
                        Spacer(Modifier.height(14.dp))
                        when (page) {
                            Page.HOME -> Unit
                            Page.DRIVE -> DrivePage(state = state, repository = repository, onAudio = { page = Page.AUDIO })
                            Page.VEHICLE -> VehiclePage(state = state, repository = repository)
                            Page.AUDIO -> key(settingsEpoch) {
                                AudioPage(
                                    audio = audio,
                                    onSettingsChanged = { settingsEpoch++ }
                                )
                            }
                            Page.SCREEN -> ScreenPage()
                            Page.AUTOMATION -> AutomationPage()
                            Page.LAB -> LabPage()
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RailMenu(current: Page, onSelect: (Page) -> Unit) {
    Column(
        modifier = Modifier
            .width(196.dp)
            .fillMaxHeight()
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = Cyan
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("DA", color = Bg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("DolphinAssistant", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("NEXT · v34", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))

        Page.entries.forEach { item ->
            val selected = item == current
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                color = if (selected) Color(0xFF112835) else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
                border = if (selected) BorderStroke(1.dp, Color(0xFF215367)) else null,
                onClick = { onSelect(item) }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        item.eyebrow,
                        color = if (selected) Cyan else TextMuted,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        item.title,
                        color = if (selected) TextMain else TextMuted,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text("KOREA DOLPHIN", color = TextMuted, fontSize = 9.sp)
        Text("DiLink 3.0", color = TextMuted, fontSize = 9.sp)
        Spacer(Modifier.height(8.dp))
        Text("CLEAN CODEBASE", color = Green, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Header(page: Page, onUpdate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(page.title, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                when (page) {
                    Page.HOME -> "기존 v30/v31 UI와 코드 경로를 사용하지 않는 새 기반"
                    Page.DRIVE -> "VehicleState 기반 실차 상태 · 소리 설정은 오디오 한 곳에서"
                    Page.VEHICLE -> "실차 검증 기능만 직접 제어"
                    Page.AUDIO -> "OFF / 비프 / TTS · 비프 패턴 · 문구 · 음성 프리셋"
                    Page.SCREEN -> "디스플레이·HUD·분할 기능을 새 엔진으로 단계 이관"
                    Page.AUTOMATION -> "시동/미디어/차량 자동화를 새 규칙 엔진으로 재구성"
                    Page.LAB -> "미확인 기능은 일반 메뉴에서 완전히 분리"
                },
                color = TextMuted,
                fontSize = 11.sp
            )
        }
        ActionButton("업데이트 확인", Cyan, onUpdate)
    }
    Text(
        BuildConfig.VERSION_NAME,
        color = TextMuted,
        fontSize = 9.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun HomePage(
    state: VehicleState,
    diagnostics: DolphinDiagnostics,
    activity: ComponentActivity,
    onGo: (Page) -> Unit
) {
    HeroCard(
        title = "완전히 새로 시작한 DolphinAssistant",
        body = "화면만 바꾼 것이 아니라 BYD 신호, 상태, 이벤트, 오디오, UI를 각각 분리했습니다. 기존 패치 체인은 Next 빌드에 사용하지 않습니다."
    )

    Section("DOLPHIN DIAGNOSTICS")
    DiagnosticsPanel(diagnostics = diagnostics, activity = activity)
    Section("현재 연결 상태")
    Grid2(
        { SignalCard("기어", state.gear) },
        { SignalCard("주행모드", state.driveMode) }
    )
    Grid2(
        { SignalCard("회생제동", state.regenMode) },
        { SignalCard("속도", state.speedKph, suffix = " km/h") }
    )

    Section("빠른 이동")
    Grid2(
        { LinkCard("차량 편의", "앞좌석 열선 · 핸들 열선", "VERIFIED", Green) { onGo(Page.VEHICLE) } },
        { LinkCard("오디오 · 경고", "공통 출력 엔진", "ACTIVE", Cyan) { onGo(Page.AUDIO) } }
    )
    Grid2(
        { LinkCard("FSD / Surrounding Vision", "주변센서 + 실차 상태 기반 read-only world model", "BETA", Cyan) { onGo(Page.DRIVE) } },
        { LinkCard("연구 기능", "실내등 · 다운미러 · 계기판 · radar", "LAB", Red) { onGo(Page.LAB) } }
    )

    Section("Next 설계 원칙")
    InfoCard("SignalSource → SignalResolver → VehicleState → Event Engine → Audio / UI", Green)
    InfoCard("모든 신호는 timestamp · stale · confidence를 함께 가짐", Cyan)
    InfoCard("미확인 기능은 일반 메뉴에 넣지 않고 LAB에서만 연구", Red)
}

@Composable
private fun DrivePage(state: VehicleState, repository: VehicleRepository, onAudio: () -> Unit) {
    DisposableEffect(repository) {
        repository.setSurroundingVisionActive(true)
        onDispose { repository.setSurroundingVisionActive(false) }
    }

    Banner("이 화면은 감지 상태만 보여줍니다. 비프/TTS 종류와 문구는 오디오 · 경고에서만 설정합니다.")

    Section("FSD / Surrounding Vision")
    SurroundingVisionSection(state)

    Section("핵심 주행 상태")
    Grid2({ SignalCard("기어", state.gear) }, { SignalCard("주행모드", state.driveMode) })
    Grid2({ SignalCard("회생제동", state.regenMode) }, { SignalCard("스노우", state.snowMode) })
    Grid2({ SignalCard("오토홀드 raw", state.autoHoldRaw) }, { SignalCard("ICC", state.iccActive) })
    Grid2({ SignalCard("BSD raw", state.bsdRaw) }, { SignalCard("방향지시등", state.turn) })
    Grid2({ SignalCard("브레이크", state.brakeDepth) }, { SignalCard("가속", state.acceleratorDepth) })

    Spacer(Modifier.height(12.dp))
    ActionButton("오디오 · 경고 설정 열기", Cyan, onAudio)

    Section("현재 판정")
    InfoCard("ECO / SPORT는 확인된 경로 우선, NORMAL은 두 raw source 상관관계로 BETA 판정", Amber)
    InfoCard("오토홀드 체결/해제는 raw=2 명시 상태만 우선 사용해 이전의 동시 출력 버그를 피함", Amber)
    InfoCard("전방차량출발은 확정 ADAS/radar 신호가 없어 기본 OFF 유지", Red)
}

@Composable
private fun VehiclePage(state: VehicleState, repository: VehicleRepository) {
    Banner("한국 정식 Dolphin 실제 장착 사양 기준. 통풍시트와 뒷좌석 열선은 메뉴에 만들지 않습니다.")

    Section("운전석 열선")
    HeatControl(state.driverHeat.value) { repository.setSeatHeat(1, it) }

    Section("동승석 열선")
    HeatControl(state.passengerHeat.value) { repository.setSeatHeat(2, it) }

    Section("핸들 열선")
    ChoiceStrip(
        options = listOf("OFF", "ON"),
        selected = if (state.steeringHeat.value == true) "ON" else "OFF",
        onSelect = { repository.setSteeringHeat(it == "ON") }
    )

    Section("일반 메뉴에서 제외")
    InfoCard("실내등: 기존 actuator 경로 실차 미동작 → LAB", Red)
    InfoCard("다운미러: 기존 angle setter 실차 미동작 → LAB", Red)
    InfoCard("메모리 시트: 절대 좌표/위치 setter 미확정 → LAB", Red)

    IntegratedVehicleLabPanel()
}

@Composable
private fun AudioPage(
    audio: NextAudioEngine,
    onSettingsChanged: () -> Unit
) {
    val context = LocalContext.current
    Banner("모든 음성/경고 설정의 유일한 소유 화면입니다. 다른 메뉴에는 중복 설정을 만들지 않습니다.")

    Section("기본 TTS 음성")
    val globalVoice = NextSettings.getGlobalVoice(context)
    ChoiceStrip(
        options = NextSettings.voicePresets.map { it.id + "|" + it.label },
        selected = NextSettings.voicePresets.firstOrNull { it.id == globalVoice }?.let { it.id + "|" + it.label } ?: "",
        onSelect = { packed ->
            val id = packed.substringBefore("|")
            NextSettings.setGlobalVoice(context, id)
            audio.previewVoice(id)
            onSettingsChanged()
        },
        display = { it.substringAfter("|") }
    )

    Section("항목별 출력")
    NextSettings.alertSpecs.forEach { spec ->
        AlertCard(spec, audio, onSettingsChanged)
        Spacer(Modifier.height(10.dp))
    }

    IntegratedAudioRoutePanel()
}

@Composable
private fun AlertCard(
    spec: AlertSpec,
    audio: NextAudioEngine,
    onSettingsChanged: () -> Unit
) {
    val context = LocalContext.current
    val profile = NextSettings.getAlertProfile(context, spec.key)

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Badge(profile.mode.name, when (profile.mode) {
                AlertMode.OFF -> TextMuted
                AlertMode.BEEP -> Amber
                AlertMode.TTS -> Cyan
            })
        }

        Label("출력 방식")
        ChoiceStrip(
            options = AlertMode.entries.map { it.name },
            selected = profile.mode.name,
            onSelect = {
                saveProfile(context, spec.key, profile.copy(mode = AlertMode.valueOf(it)))
                onSettingsChanged()
            },
            display = {
                when (it) {
                    "BEEP" -> "비프"
                    "TTS" -> "TTS"
                    else -> "OFF"
                }
            }
        )

        if (profile.mode == AlertMode.BEEP) {
            Label("비프 패턴")
            ChoiceStrip(
                options = NextSettings.beepPresets.map { it.id + "|" + it.label },
                selected = NextSettings.beepPresets.firstOrNull { it.id == profile.beepId }?.let { it.id + "|" + it.label } ?: "",
                onSelect = { packed ->
                    val id = packed.substringBefore("|")
                    saveProfile(context, spec.key, profile.copy(beepId = id))
                    onSettingsChanged()
                    audio.preview(spec.key)
                },
                display = { it.substringAfter("|") }
            )
        }

        if (profile.mode == AlertMode.TTS) {
            Label("문구")
            ChoiceStrip(
                options = PhraseMode.entries.map { it.name },
                selected = profile.phraseMode.name,
                onSelect = {
                    saveProfile(context, spec.key, profile.copy(phraseMode = PhraseMode.valueOf(it)))
                    onSettingsChanged()
                },
                display = {
                    when (it) {
                        "DEFAULT" -> "기본"
                        "RECOMMENDED" -> "추천"
                        else -> "커스텀"
                    }
                }
            )

            if (profile.phraseMode == PhraseMode.CUSTOM) {
                var custom by remember(spec.key, profile.customText) { mutableStateOf(profile.customText) }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.take(180) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(spec.customHint, color = TextMuted) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextMain)
                )
                Spacer(Modifier.height(8.dp))
                ActionButton("문구 저장", Cyan) {
                    saveProfile(context, spec.key, profile.copy(customText = custom))
                    onSettingsChanged()
                }
            }

        }

        if (profile.mode != AlertMode.OFF) {
            Spacer(Modifier.height(10.dp))
            ActionButton("미리듣기", Green) { audio.preview(spec.key) }
        }
    }
}

private fun saveProfile(
    context: android.content.Context,
    key: String,
    profile: AlertProfile
) {
    NextSettings.saveAlertProfile(context, key, profile)
}

@Composable
private fun ScreenPage() {
    Banner("2분할은 검증 경로를 유지하고 3/4분할·팝업·HUD/TBT·플로팅/퀵독을 같은 화면에서 실차 테스트합니다.")
    IntegratedDisplayPanel()
}

@Composable
private fun AutomationPage() {
    Banner("시동 전원 상태를 백그라운드에서 감지하고 앱별 실행 지연/미디어 재생 지연을 적용합니다.")
    IntegratedAutomationPanel()
}

@Composable
private fun LabPage() {
    Banner("LAB은 연구 후보만 표시합니다. 진단·로그·내보내기는 홈의 DOLPHIN DIAGNOSTICS 하나로 통합했습니다.")

    Section("차량 연구")
    InfoCard("실내등: device 1023 / FID 1330643002 · ambient 1069547536 후보", Red)
    InfoCard("다운미러: 기존 angle API 실차 미동작 · actuator 재탐색", Red)
    InfoCard("전방 radar track: 0x280–0x289 후보 · read-only correlation", Red)
    InfoCard("계기판: 순정 TBT broadcast → AmapService → CAN 경로 correlation", Red)
    InfoCard("운전석 오디오: 앱 자체 stream14는 사용, 타 앱 UID routing은 LAB", Red)
}

@Composable
private fun DiagnosticsPanel(
    diagnostics: DolphinDiagnostics,
    activity: ComponentActivity
) {
    val diag by diagnostics.state.collectAsState()
    val scope = rememberCoroutineScope()

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "DOLPHIN HEALTH · " + diag.passed + "/" + diag.total,
                    color = TextMain,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (diag.running) "원터치 진단 실행 중"
                    else if (diag.total == 0) "최근 주행 자동 기록 중 · TEST DRIVE = 최근 주행 분석 + 핵심 상태/오디오 검사"
                    else "마지막 진단 · 실패/NO SIGNAL " + diag.failures + "건",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
            Badge(
                if (diag.running) "RUNNING"
                else if (diag.total == 0) "READY"
                else if (diag.failures == 0) "PASS" else "CHECK",
                if (diag.running) Cyan
                else if (diag.total == 0) TextMuted
                else if (diag.failures == 0) Green else Amber
            )
        }

        if (diag.results.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            diag.results.forEach { result ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        result.title,
                        color = TextMain,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(112.dp)
                    )
                    Badge(
                        when (result.status) {
                            DiagnosticStatus.PASS -> "PASS"
                            DiagnosticStatus.FAIL -> "FAIL"
                            DiagnosticStatus.NO_SIGNAL -> "NO SIGNAL"
                            DiagnosticStatus.RUNNING -> "RUNNING"
                        },
                        when (result.status) {
                            DiagnosticStatus.PASS -> Green
                            DiagnosticStatus.FAIL -> Red
                            DiagnosticStatus.NO_SIGNAL -> Amber
                            DiagnosticStatus.RUNNING -> Cyan
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        result.detail,
                        color = TextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        result.confidence.name,
                        color = confidenceColor(result.confidence),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        val recent = diag.recentDrive
        if (recent != null) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RECENT DRIVE",
                    color = TextMain,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                val minutes = (recent.durationMs / 60_000L).coerceAtLeast(0L)
                Text(
                    minutes.toString() + "분 기록 · MAX " +
                        (recent.maxSpeedKph?.let { String.format("%.1f km/h", it) } ?: "—"),
                    color = TextMuted,
                    fontSize = 9.sp
                )
            }
            Spacer(Modifier.height(6.dp))

            recent.observations.forEach { observation ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        observation.title,
                        color = TextMain,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(132.dp)
                    )
                    Badge(
                        when (observation.status) {
                            DriveObservationStatus.OBSERVED -> "OBSERVED"
                            DriveObservationStatus.NOT_USED -> "NOT USED"
                            DriveObservationStatus.NO_SIGNAL -> "NO SIGNAL"
                            DriveObservationStatus.ISSUE -> "ISSUE"
                        },
                        when (observation.status) {
                            DriveObservationStatus.OBSERVED -> Green
                            DriveObservationStatus.NOT_USED -> TextMuted
                            DriveObservationStatus.NO_SIGNAL -> Amber
                            DriveObservationStatus.ISSUE -> Red
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        observation.detail,
                        color = TextMuted,
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        observation.confidence.name,
                        color = confidenceColor(observation.confidence),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActionButton(
                if (diag.running) "TEST DRIVE RUNNING" else "RUN TEST DRIVE",
                Cyan
            ) {
                if (!diag.running) diagnostics.runFullDiagnostics()
            }

            ActionButton("UPLOAD FULL TEST SESSION", Amber) {
                if (!diag.running) {
                    scope.launch {
                        val result = diagnostics.uploadFullTestSession()
                        Toast.makeText(
                            activity,
                            if (result.success) "통합 테스트 로그 GitHub 업로드 완료"
                            else result.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            if (
                !diag.running &&
                (diag.failures > 0 || diag.recentDrive?.hasIssue == true) &&
                diag.autoUploadSuccess != true
            ) {
                ActionButton("EXPORT ONLY FAILURES", Green) {
                    scope.launch {
                        val file = diagnostics.createFailuresZip()
                        if (file == null) {
                            Toast.makeText(activity, "내보낼 실패 항목이 없습니다.", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val uri = FileProvider.getUriForFile(
                            activity,
                            activity.packageName + ".fileprovider",
                            file
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(
                            Intent.createChooser(send, "DOLPHIN DIAGNOSTICS")
                        )
                    }
                }
            }
        }

        val fullUploadMessage = diag.fullUploadMessage
        if (fullUploadMessage != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(
                    "FULL TEST GITHUB",
                    if (diag.fullUploadSuccess == true) Green else Amber
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    fullUploadMessage,
                    color = if (diag.fullUploadSuccess == true) Green else TextMuted,
                    fontSize = 9.sp
                )
            }
        }

        val uploadMessage = diag.autoUploadMessage
        if (uploadMessage != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(
                    if (diag.autoUploadSuccess == true) "GITHUB AUTO UPLOAD"
                    else "GITHUB UPLOAD",
                    if (diag.autoUploadSuccess == true) Green else Amber
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    uploadMessage,
                    color = if (diag.autoUploadSuccess == true) Green else TextMuted,
                    fontSize = 9.sp
                )
            }
        }

        if (diag.lastExportName != null && diag.autoUploadSuccess != true) {
            Text(
                "로컬 실패 ZIP · " + diag.lastExportName,
                color = TextMuted,
                fontSize = 8.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun HeatControl(level: Int?, onSet: (Int) -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (level) {
                    0 -> "현재 OFF"
                    1 -> "현재 1단"
                    2 -> "현재 2단"
                    else -> "현재 상태 확인 중"
                },
                color = TextMain,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Badge("VERIFIED", Green)
        }
        Spacer(Modifier.height(8.dp))
        ChoiceStrip(
            options = listOf("0", "1", "2"),
            selected = level?.toString() ?: "",
            onSelect = { onSet(it.toInt()) },
            display = {
                when (it) {
                    "0" -> "OFF"
                    "1" -> "1단"
                    else -> "2단"
                }
            }
        )
    }
}

@Composable
private fun <T> SignalCard(
    title: String,
    signal: SignalValue<T>,
    suffix: String = ""
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Badge(signal.confidence.name, confidenceColor(signal.confidence))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (signal.value == null) "—" else signal.value.toString() + suffix,
            color = if (signal.stale) TextMuted else TextMain,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        if (signal.raw != null) {
            Text("raw " + signal.raw, color = TextMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun LinkCard(
    title: String,
    body: String,
    status: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface1,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Border),
        onClick = onClick
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextMain, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Badge(status, color)
            }
            Spacer(Modifier.height(8.dp))
            Text(body, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HeroCard(title: String, body: String) {
    AppCard {
        Text(title, color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = TextMuted, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun InfoCard(text: String, color: Color) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.width(4.dp).height(34.dp),
                color = color,
                shape = RoundedCornerShape(2.dp)
            ) {}
            Spacer(Modifier.width(10.dp))
            Text(text, color = TextMuted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Banner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface2,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Border)
    ) {
        Text(text, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Border),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun Grid2(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Section(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(9.dp))
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(9.dp))
    Text(text, color = TextMuted, fontSize = 9.sp)
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f))
    ) {
        Text(
            text,
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ActionButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.16f), contentColor = TextMain),
        border = BorderStroke(1.dp, color.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChoiceStrip(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    display: (String) -> String = { it }
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        options.forEach { option ->
            val active = option == selected
            Surface(
                color = if (active) Cyan else Surface3,
                contentColor = if (active) Bg else TextMain,
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, if (active) Cyan else Border),
                onClick = { onSelect(option) }
            ) {
                Text(
                    display(option),
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)
                )
            }
        }
    }
}

private fun confidenceColor(confidence: Confidence): Color =
    when (confidence) {
        Confidence.VERIFIED -> Green
        Confidence.BETA -> Amber
        Confidence.LAB -> Red
        Confidence.UNKNOWN -> TextMuted
    }
