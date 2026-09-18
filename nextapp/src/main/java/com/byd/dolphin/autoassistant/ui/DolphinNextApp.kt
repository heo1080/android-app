package com.byd.dolphin.autoassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.core.audio.AlertCatalog
import com.byd.dolphin.autoassistant.core.audio.AlertEngine
import com.byd.dolphin.autoassistant.core.audio.AlertProfile
import com.byd.dolphin.autoassistant.core.audio.AlertSpec
import com.byd.dolphin.autoassistant.core.audio.OutputMode
import com.byd.dolphin.autoassistant.core.audio.PhraseMode
import com.byd.dolphin.autoassistant.core.display.LaunchableApp
import com.byd.dolphin.autoassistant.core.display.SplitConfig
import com.byd.dolphin.autoassistant.core.display.SplitController
import com.byd.dolphin.autoassistant.core.model.VehicleState
import com.byd.dolphin.autoassistant.core.state.VehicleStateStore
import com.byd.dolphin.autoassistant.core.update.UpdateClient
import kotlinx.coroutines.launch

private val Bg = Color(0xFF050A0F)
private val Rail = Color(0xFF091017)
private val Surface1 = Color(0xFF0F1821)
private val Surface2 = Color(0xFF13202B)
private val Surface3 = Color(0xFF182734)
private val Stroke = Color(0xFF263746)
private val TextMain = Color(0xFFF1F7FA)
private val TextMuted = Color(0xFF8B9EAF)
private val Cyan = Color(0xFF3BCFF5)
private val Green = Color(0xFF45D48A)
private val Amber = Color(0xFFFFC857)
private val Red = Color(0xFFFF6B6B)

private val NextColors = darkColorScheme(
    background = Bg,
    surface = Surface1,
    primary = Cyan,
    secondary = Green,
    onBackground = TextMain,
    onSurface = TextMain,
    onPrimary = Bg
)

private enum class Page(val ko: String, val en: String) {
    HOME("홈", "HOME"),
    DRIVE("주행", "DRIVE"),
    VEHICLE("차량", "VEHICLE"),
    AUDIO("오디오 · 경고", "AUDIO"),
    DISPLAY("내비 · 화면", "DISPLAY"),
    AUTOMATION("자동화", "AUTO"),
    LAB("LAB · 진단", "LAB")
}

@Composable
fun DolphinNextApp(
    stateStore: VehicleStateStore,
    alerts: AlertEngine,
    updater: UpdateClient,
    splitController: SplitController,
    onShareDiagnostic: () -> Unit
) {
    MaterialTheme(colorScheme = NextColors) {
        val vehicle by stateStore.state.collectAsStateWithLifecycle()
        var page by remember { mutableStateOf(Page.HOME) }
        var audioRevision by remember { mutableStateOf(0) }
        var updateResult by remember { mutableStateOf<UpdateClient.CheckResult?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var updateBusy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Box(Modifier.fillMaxSize().background(Bg)) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    current = page,
                    onSelect = { page = it },
                    modifier = Modifier.width(188.dp).fillMaxHeight()
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 20.dp)
                ) {
                    Header(
                        page = page,
                        updateBusy = updateBusy,
                        onUpdate = {
                            if (!updateBusy) {
                                updateBusy = true
                                scope.launch {
                                    updateResult = updater.check()
                                    updateBusy = false
                                }
                            }
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    when (page) {
                        Page.HOME -> HomePage(vehicle, onNavigate = { page = it })
                        Page.DRIVE -> DrivePage(vehicle, onAudio = { page = Page.AUDIO })
                        Page.VEHICLE -> VehiclePage(vehicle, stateStore)
                        Page.AUDIO -> AudioPage(
                            alerts = alerts,
                            revision = audioRevision,
                            onChanged = { audioRevision++ }
                        )
                        Page.DISPLAY -> DisplayPage(splitController)
                        Page.AUTOMATION -> AutomationPage()
                        Page.LAB -> LabPage(
                            vehicle = vehicle,
                            alerts = alerts,
                            onShareDiagnostic = onShareDiagnostic
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }

            if (updateBusy) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                    color = Surface2,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Stroke)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("업데이트 처리 중", color = TextMain, fontSize = 11.sp)
                    }
                }
            }
        }

        when (val result = updateResult) {
            is UpdateClient.CheckResult.Available -> {
                AlertDialog(
                    onDismissRequest = { updateResult = null },
                    title = { Text("새 버전 " + result.release.tag) },
                    text = {
                        Text(
                            "새 DolphinAssistant를 다운로드하고 SHA-256과 앱 서명을 검증한 뒤 설치 화면을 엽니다."
                        )
                    },
                    dismissButton = {
                        TextButton(onClick = { updateResult = null }) { Text("나중에") }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (!updater.canInstallPackages()) {
                                updater.openInstallPermission()
                                notice = "설정 화면에서 DolphinAssistant의 ‘이 출처 허용’을 켠 뒤 다시 업데이트를 누르세요."
                                updateResult = null
                            } else {
                                updateBusy = true
                                val release = result.release
                                updateResult = null
                                scope.launch {
                                    runCatching { updater.downloadAndVerify(release) }
                                        .onSuccess { updater.openInstaller(it) }
                                        .onFailure { notice = "업데이트 실패: " + (it.message ?: it.javaClass.simpleName) }
                                    updateBusy = false
                                }
                            }
                        }) { Text("업데이트") }
                    }
                )
            }
            UpdateClient.CheckResult.Latest -> {
                AlertDialog(
                    onDismissRequest = { updateResult = null },
                    title = { Text("최신 버전입니다") },
                    text = { Text("현재 " + BuildConfig.VERSION_NAME) },
                    confirmButton = {
                        TextButton(onClick = { updateResult = null }) { Text("확인") }
                    }
                )
            }
            is UpdateClient.CheckResult.Error -> {
                AlertDialog(
                    onDismissRequest = { updateResult = null },
                    title = { Text("업데이트 확인 실패") },
                    text = { Text(result.message) },
                    confirmButton = {
                        TextButton(onClick = { updateResult = null }) { Text("확인") }
                    }
                )
            }
            null -> Unit
        }

        notice?.let { message ->
            AlertDialog(
                onDismissRequest = { notice = null },
                title = { Text("안내") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { notice = null }) { Text("확인") }
                }
            )
        }
    }
}

@Composable
private fun NavigationRail(
    current: Page,
    onSelect: (Page) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(Rail).padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            color = Cyan,
            shape = RoundedCornerShape(15.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("DA", color = Bg, fontWeight = FontWeight.Black, fontSize = 19.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("DolphinAssistant", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("NEXT · CLEAN CORE", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 8.sp)
        Spacer(Modifier.height(18.dp))

        Page.entries.forEach { page ->
            val selected = page == current
            Surface(
                onClick = { onSelect(page) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                color = if (selected) Color(0xFF112835) else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
                border = if (selected) BorderStroke(1.dp, Color(0xFF215367)) else null
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        page.en,
                        color = if (selected) Cyan else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 7.sp
                    )
                    Text(
                        page.ko,
                        color = if (selected) TextMain else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.weight(1f))
        Text("KOREA DOLPHIN", color = TextMuted, fontSize = 8.sp)
        Text("DiLink 3.0", color = TextMuted, fontSize = 8.sp)
        Spacer(Modifier.height(6.dp))
        Text("RESEARCH SYNC", color = Green, fontWeight = FontWeight.Bold, fontSize = 8.sp)
    }
}

@Composable
private fun Header(page: Page, updateBusy: Boolean, onUpdate: () -> Unit) {
    val subtitle = when (page) {
        Page.HOME -> "완전히 새 코드베이스 · 한 기능은 한 곳에서만 설정"
        Page.DRIVE -> "Signal → VehicleState → Event Engine"
        Page.VEHICLE -> "실차 검증된 제어만 일반 기능으로 노출"
        Page.AUDIO -> "모든 비프와 TTS 설정의 유일한 소유 화면"
        Page.DISPLAY -> "분할 · 플로팅 · HUD · 계기판을 단계적으로 새 코어로 이식"
        Page.AUTOMATION -> "시동 앱 · 미디어 · 차량 자동화 전용 영역"
        Page.LAB -> "미확인 Feature ID와 실차 진단은 일반 기능과 분리"
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(page.ko, color = TextMain, fontWeight = FontWeight.Black, fontSize = 27.sp)
            Text(subtitle, color = TextMuted, fontSize = 10.sp)
        }
        ActionButton(
            text = if (updateBusy) "확인 중…" else "업데이트 확인",
            accent = Cyan,
            enabled = !updateBusy,
            onClick = onUpdate
        )
    }
    Text(
        BuildConfig.VERSION_NAME + " · code " + BuildConfig.VERSION_CODE,
        color = TextMuted,
        fontSize = 8.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
    )
}

@Composable
private fun HomePage(vehicle: VehicleState, onNavigate: (Page) -> Unit) {
    Panel {
        Text(
            "처음부터 다시 만든 DolphinAssistant",
            color = TextMain,
            fontWeight = FontWeight.Black,
            fontSize = 23.sp
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "기존 app/의 화면·패치 체인·SettingsManager를 사용하지 않습니다. " +
                "새 nextapp은 차량 신호, 상태, 이벤트, 오디오, UI를 독립 계층으로 구성합니다.",
            color = TextMuted,
            fontSize = 10.sp,
            lineHeight = 16.sp
        )
    }

    Section("현재 상태")
    Grid(
        left = {
            StatusCard(
                "차량 연결",
                if (vehicle.connected) "BYD HAL 샘플 수신 중" else "차량 API 대기 중",
                if (vehicle.connected) "CONNECTED" else "WAIT",
                if (vehicle.connected) Green else Amber
            )
        },
        right = {
            StatusCard(
                "주행",
                "기어 " + (vehicle.gear ?: "—") +
                    " · " + (vehicle.driveMode ?: "—") +
                    " · " + (vehicle.regenMode ?: "—"),
                "LIVE",
                Cyan,
                onClick = { onNavigate(Page.DRIVE) }
            )
        }
    )
    Grid(
        left = {
            StatusCard(
                "오디오 · 경고",
                "stream14 · Supertonic · 비프/TTS 공통 프로필",
                "NEW CORE",
                Cyan,
                onClick = { onNavigate(Page.AUDIO) }
            )
        },
        right = {
            StatusCard(
                "차량 편의",
                "앞좌석 열선 · 핸들 열선 · 공조",
                "VERIFIED PATHS",
                Green,
                onClick = { onNavigate(Page.VEHICLE) }
            )
        }
    )

    Section("연구 분리")
    ResearchRow("VERIFIED", "반복 실차 확인 경로만 일반 제어에 사용", Green)
    ResearchRow("BETA", "BSD · AutoHold · NORMAL · 전방차량출발은 추가 로그로 확정", Amber)
    ResearchRow("LAB", "실내등 · 다운미러 · Cluster/CAN · radar track · 타 앱 운전석 라우팅", Red)
}

@Composable
private fun DrivePage(vehicle: VehicleState, onAudio: () -> Unit) {
    Banner("이 화면은 감지 상태만 보여줍니다. 경고음·문구·목소리는 ‘오디오 · 경고’에서만 설정합니다.")

    Section("주행 신호")
    Grid(
        left = { LiveCard("기어", vehicle.gear, "raw=" + value(vehicle.gearRaw), Green) },
        right = { LiveCard("속도", vehicle.speedKmh?.let { "%.1f km/h".format(it) }, "double getter", Green) }
    )
    Grid(
        left = { LiveCard("주행모드", vehicle.driveMode, "raw=" + value(vehicle.driveModeRaw), Amber) },
        right = { LiveCard("회생제동", vehicle.regenMode, "raw=" + value(vehicle.regenRaw), Green) }
    )
    Grid(
        left = {
            LiveCard(
                "AutoHold",
                when (vehicle.autoHoldHolding) { true -> "체결"; false -> "해제"; null -> "판정 중" },
                "AVH raw=" + value(vehicle.avhRaw) +
                    " · brake=" + value(vehicle.brakeDepth) +
                    " · accel=" + value(vehicle.acceleratorDepth),
                Amber
            )
        },
        right = {
            LiveCard(
                "ICC/TJA",
                when (vehicle.iccActive) { true -> "ACTIVE"; false -> "OFF"; null -> "—" },
                "raw=" + value(vehicle.tjaRaw),
                Amber
            )
        }
    )
    Grid(
        left = {
            LiveCard(
                "BSD",
                "raw=" + value(vehicle.bsdRaw),
                "깜박이 " + (vehicle.turnDirection ?: "—") + " · raw=" + value(vehicle.turnRaw),
                Amber
            )
        },
        right = {
            LiveCard(
                "전방 7 / 8 센서",
                value(vehicle.frontLeftCm) + " / " + value(vehicle.frontRightCm) + " cm",
                "현재는 BETA 출발 감지 보조 경로",
                Amber
            )
        }
    )

    Section("경고 설정")
    Panel {
        Text("소리 설정은 중복하지 않습니다.", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "BSD, AutoHold, 주행모드, 전방차량출발의 출력 방식을 바꾸려면 오디오 · 경고로 이동하세요.",
            color = TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(10.dp))
        ActionButton("오디오 · 경고 열기", Cyan, onClick = onAudio)
    }
}

@Composable
private fun VehiclePage(vehicle: VehicleState, stateStore: VehicleStateStore) {
    Banner("한국형 Dolphin 장착 사양 기준으로 검증된 제어만 둡니다. 통풍시트·뒷좌석 열선은 만들지 않습니다.")

    Section("앞좌석 열선")
    SeatControl(
        title = "운전석",
        current = vehicle.driverSeatHeat,
        onSet = { stateStore.setSeatHeat(1, it) }
    )
    Spacer(Modifier.height(10.dp))
    SeatControl(
        title = "동승석",
        current = vehicle.passengerSeatHeat,
        onSet = { stateStore.setSeatHeat(2, it) }
    )

    Section("핸들 · 공조")
    Grid(
        left = {
            ToggleCard(
                title = "핸들 열선",
                checked = vehicle.steeringHeat == true,
                available = vehicle.steeringHeat != null,
                status = "VERIFIED",
                onChanged = { stateStore.setSteeringHeat(it) }
            )
        },
        right = {
            ToggleCard(
                title = "공조 전원",
                checked = vehicle.acOn == true,
                available = vehicle.acOn != null,
                status = "VERIFIED",
                onChanged = { stateStore.setAcPower(it) }
            )
        }
    )

    Section("일반 메뉴에서 제외")
    ResearchRow("실내등", "기존 actuator 실차 미동작 · 새 코어에 미이식", Red)
    ResearchRow("다운미러", "기존 mirror angle setter 실차 미동작 · LAB에서 재탐색", Red)
    ResearchRow("메모리 시트", "정확한 위치 getter/setter 확인 전 제어 금지", Red)
}

@Composable
private fun AudioPage(alerts: AlertEngine, revision: Int, onChanged: () -> Unit) {
    val repository = alerts.repository
    @Suppress("UNUSED_VARIABLE")
    val refresh = revision

    Banner("모든 음성/경고는 여기 한 곳에서만 관리합니다. OFF / 비프 / TTS를 선택하면 필요한 옵션만 나타납니다.")

    Section("TTS 엔진")
    Panel {
        val ready = alerts.ttsReady()
        Text(
            if (ready) "Supertonic 3 · 준비 완료" else if (alerts.ttsDownloading()) "Supertonic 3 · 모델 준비 중" else "Supertonic 3 · 준비 필요",
            color = if (ready) Green else Amber,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Text(
            "Android 시스템 TTS를 사용하지 않고 앱 내부에서 생성한 음성을 BYD legacy stream 14로 재생합니다.",
            color = TextMuted,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
        alerts.ttsLastError()?.let {
            Text("최근 오류: " + it, color = Red, fontSize = 8.sp, modifier = Modifier.padding(top = 5.dp))
        }
        if (!ready && !alerts.ttsDownloading()) {
            Spacer(Modifier.height(8.dp))
            ActionButton("TTS 모델 준비", Cyan, onClick = { alerts.ensureTtsModel() })
        }
    }

    Section("기본 음성")
    val globalVoice = repository.globalVoice()
    OptionRow(
        items = AlertCatalog.voices.map { it.id to it.label },
        selected = globalVoice,
        onSelect = { id ->
            repository.setGlobalVoice(id)
            alerts.previewVoice(id)
            onChanged()
        }
    )

    Section("항목별 출력")
    AlertCatalog.specs.forEach { spec ->
        val profile = repository.getProfile(spec.id)
        AlertCard(
            spec = spec,
            profile = profile,
            alerts = alerts,
            onSave = {
                repository.saveProfile(spec.id, it)
                onChanged()
            }
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun AlertCard(
    spec: AlertSpec,
    profile: AlertProfile,
    alerts: AlertEngine,
    onSave: (AlertProfile) -> Unit
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                spec.title,
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Badge(profile.mode.name, when (profile.mode) {
                OutputMode.OFF -> TextMuted
                OutputMode.BEEP -> Amber
                OutputMode.TTS -> Cyan
            })
        }

        SmallLabel("출력 방식")
        OptionRow(
            items = listOf(
                OutputMode.OFF.name to "OFF",
                OutputMode.BEEP.name to "비프",
                OutputMode.TTS.name to "TTS"
            ),
            selected = profile.mode.name,
            onSelect = { onSave(profile.copy(mode = OutputMode.valueOf(it))) }
        )

        if (profile.mode == OutputMode.BEEP) {
            SmallLabel("비프 패턴")
            OptionRow(
                items = AlertCatalog.beeps.map { it.id to it.label },
                selected = profile.beepId,
                onSelect = {
                    onSave(profile.copy(beepId = it))
                    alerts.preview(spec.id)
                }
            )
        }

        if (profile.mode == OutputMode.TTS) {
            SmallLabel("문구")
            OptionRow(
                items = listOf(
                    PhraseMode.DEFAULT.name to "기본",
                    PhraseMode.RECOMMENDED.name to "추천",
                    PhraseMode.CUSTOM.name to "커스텀"
                ),
                selected = profile.phraseMode.name,
                onSelect = { onSave(profile.copy(phraseMode = PhraseMode.valueOf(it))) }
            )

            if (profile.phraseMode == PhraseMode.CUSTOM) {
                var custom by remember(spec.id, profile.customText) { mutableStateOf(profile.customText) }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.take(180) },
                    label = { Text("커스텀 문구 · {state} 사용 가능") },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    minLines = 2
                )
                Spacer(Modifier.height(7.dp))
                ActionButton("문구 저장", Cyan, onClick = {
                    onSave(profile.copy(customText = custom))
                })
            }

            SmallLabel("TTS 음성")
            val voices = listOf("GLOBAL" to "기본 음성") + AlertCatalog.voices.map { it.id to it.label }
            OptionRow(
                items = voices,
                selected = profile.voiceId,
                onSelect = {
                    onSave(profile.copy(voiceId = it))
                    alerts.preview(spec.id)
                }
            )
        }

        if (profile.mode != OutputMode.OFF) {
            Spacer(Modifier.height(9.dp))
            ActionButton("미리듣기", Green, onClick = { alerts.preview(spec.id) })
        }
    }
}

@Composable
private fun DisplayPage(splitController: SplitController) {
    val scope = rememberCoroutineScope()
    val apps = remember { splitController.installedApps() }
    var config by remember { mutableStateOf(splitController.getConfig()) }
    var pickerTarget by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val primaryName = apps.firstOrNull { it.packageName == config.primaryPackage }?.label
        ?: config.primaryPackage.ifBlank { "첫 번째 앱 선택" }
    val secondaryName = apps.firstOrNull { it.packageName == config.secondaryPackage }?.label
        ?: config.secondaryPackage.ifBlank { "두 번째 앱 선택" }

    Banner("DiLink 3에서 실차 검증된 2앱 분할만 일반 기능으로 이식했습니다. 3·4분할은 LAB에 유지합니다.")

    Section("2분할")
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("첫 번째 앱", color = TextMuted, fontSize = 8.sp)
                Text(primaryName, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                ActionButton("앱 변경", Cyan, onClick = { pickerTarget = 1 })
            }
            Column(Modifier.weight(1f)) {
                Text("두 번째 앱", color = TextMuted, fontSize = 8.sp)
                Text(secondaryName, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                ActionButton("앱 변경", Cyan, onClick = { pickerTarget = 2 })
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("분할 비율", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(
                config.ratioPrimary.toString() + " : " + (100 - config.ratioPrimary),
                color = Cyan,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
        Slider(
            value = config.ratioPrimary.toFloat(),
            onValueChange = { config = config.copy(ratioPrimary = it.toInt().coerceIn(20, 80)) },
            valueRange = 20f..80f,
            steps = 59
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("2분할 실행", Green, onClick = {
                scope.launch {
                    status = "2분할 구성 중…"
                    status = splitController.launch(config)
                }
            })
            ActionButton("최근 구성 복원", Cyan, onClick = {
                scope.launch {
                    status = "최근 2분할 복원 중…"
                    status = splitController.restore()
                    config = splitController.getConfig()
                }
            })
        }
        status?.let {
            Text(it, color = if (it.contains("완료")) Green else Amber, fontSize = 9.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Text(
            "로컬 ADB: " + if (splitController.isAdbReady()) "127.0.0.1:5555 READY" else "대기/권한 확인 필요",
            color = if (splitController.isAdbReady()) Green else Amber,
            fontSize = 8.sp,
            modifier = Modifier.padding(top = 7.dp)
        )
    }

    Section("다음 이식")
    ResearchRow("플로팅 독", "Compose와 분리된 WindowManager 전용 모듈로 새로 구현", Amber)
    ResearchRow("HUD / T900", "내비 파서와 실제 payload 전송을 분리해 재구축", Amber)
    ResearchRow("계기판 TBT", "순정 CAN/TBT correlation 확인 전 LAB", Red)
    ResearchRow("3·4분할", "VirtualDisplay 후보 실차 미확인 · LAB 유지", Red)
    ResearchRow("DPI / 화면 프로파일", "logical size · density · fontScale을 독립 관리하도록 재설계", Amber)

    pickerTarget?.let { target ->
        AppPickerDialog(
            title = if (target == 1) "첫 번째 앱 선택" else "두 번째 앱 선택",
            apps = apps,
            onDismiss = { pickerTarget = null },
            onSelect = { selected ->
                config = if (target == 1) {
                    config.copy(primaryPackage = selected.packageName)
                } else {
                    config.copy(secondaryPackage = selected.packageName)
                }
                pickerTarget = null
            }
        )
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    apps: List<LaunchableApp>,
    onDismiss: () -> Unit,
    onSelect: (LaunchableApp) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                apps.forEach { app ->
                    Surface(
                        onClick = { onSelect(app) },
                        color = Surface2,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Stroke),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                            Text(app.label, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(app.packageName, color = TextMuted, fontSize = 7.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

@Composable
private fun AutomationPage() {
    Banner("예전 시나리오 JSON을 그대로 가져오지 않고 새 Rule Engine으로 다시 만듭니다.")
    Section("새 자동화 구조")
    ResearchRow("시동 앱 실행", "앱별 0.1초 단위 지연 · 다중 앱 목록", Green)
    ResearchRow("미디어", "앱별 미디어 인식 · 백그라운드 실행 · 자동재생 지연", Amber)
    ResearchRow("기어/차량 조건", "READY · P/R/N/D · 안전 상태를 Trigger로 분리", Amber)
    ResearchRow("차량 액션", "검증된 열선/공조 등만 Action으로 허용", Green)
    ResearchRow("실험 액션", "실내등/미러 등 LAB 기능은 자동화에서 선택 불가", Red)
}

@Composable
private fun LabPage(
    vehicle: VehicleState,
    alerts: AlertEngine,
    onShareDiagnostic: () -> Unit
) {
    Banner("LAB은 읽기·상관관계·진단 중심입니다. 확인되지 않은 명령을 일반 기능처럼 실행하지 않습니다.")

    Section("최신 통합 연구 반영")
    ResearchRow("계기판 TBT", "순정 AUTONAVI → Instrument → CAN 경로와 0xAA00020F correlation 연구", Red)
    ResearchRow("전방 radar track", "0x280–0x289 후보는 한국 Dolphin 미확인 · read-only 비교만", Red)
    ResearchRow("운전석 오디오", "앱 자체 stream14는 사용 · 타 앱 UID routing은 미확정", Red)
    ResearchRow("실내등", "새 Feature ID 후보는 실차 검증 전 쓰기 금지", Red)
    ResearchRow("다운미러", "기존 angle setter 미동작 · actuator 재탐색", Red)

    Section("현재 raw")
    Panel {
        RawLine("gear", vehicle.gearRaw)
        RawLine("driveInterface", vehicle.driveModeRaw)
        RawLine("energyFeedback", vehicle.regenRaw)
        RawLine("AVH", vehicle.avhRaw)
        RawLine("BSD", vehicle.bsdRaw)
        RawLine("turn", vehicle.turnRaw)
        RawLine("TJA/ICC", vehicle.tjaRaw)
        RawLine("front7", vehicle.frontLeftCm)
        RawLine("front8", vehicle.frontRightCm)
    }

    Section("진단")
    Panel {
        Text(
            "새 Next는 자체 로그 파일에 Gateway 오류와 이벤트 전이를 기록합니다.",
            color = TextMuted,
            fontSize = 10.sp
        )
        Text(
            "TTS: " + if (alerts.ttsReady()) "READY" else if (alerts.ttsDownloading()) "DOWNLOADING" else "NOT READY",
            color = if (alerts.ttsReady()) Green else Amber,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(10.dp))
        ActionButton("진단 TXT 공유", Cyan, onClick = onShareDiagnostic)
    }
}

@Composable
private fun SeatControl(title: String, current: Int?, onSet: (Int) -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title + " · " + when (current) { 0 -> "OFF"; 1 -> "1단"; 2 -> "2단"; else -> "확인 중" },
                color = TextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Badge("VERIFIED", Green)
        }
        OptionRow(
            items = listOf("0" to "OFF", "1" to "1단", "2" to "2단"),
            selected = current?.toString() ?: "",
            onSelect = { onSet(it.toInt()) }
        )
    }
}

@Composable
private fun ToggleCard(
    title: String,
    checked: Boolean,
    available: Boolean,
    status: String,
    onChanged: (Boolean) -> Unit
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    if (!available) "상태 확인 중" else if (checked) "켜짐" else "꺼짐",
                    color = TextMuted,
                    fontSize = 9.sp
                )
            }
            Badge(status, Green)
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked,
                enabled = available,
                onCheckedChange = onChanged
            )
        }
    }
}

@Composable
private fun LiveCard(title: String, value: String?, detail: String, color: Color) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Badge(if (color == Green) "VERIFIED" else "BETA", color)
        }
        Text(value ?: "—", color = TextMain, fontWeight = FontWeight.Black, fontSize = 19.sp, modifier = Modifier.padding(top = 8.dp))
        Text(detail, color = TextMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun StatusCard(
    title: String,
    detail: String,
    status: String,
    color: Color,
    onClick: (() -> Unit)? = null
) {
    Card(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stroke),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Badge(status, color)
            }
            Text(detail, color = TextMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ResearchRow(title: String, detail: String, color: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Stroke),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(3.dp).height(34.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(detail, color = TextMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun RawLine(name: String, raw: Any?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(name, color = TextMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text(value(raw), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    }
}

@Composable
private fun Grid(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        color = TextMain,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 9.dp)
    )
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface1),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Stroke),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun Banner(text: String) {
    Surface(
        color = Surface2,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Stroke),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, color = TextMuted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.65f))
    ) {
        Text(
            text,
            color = color,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SmallLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun OptionRow(
    items: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items.forEach { (id, label) ->
            val active = id == selected
            Surface(
                onClick = { onSelect(id) },
                color = if (active) Cyan else Surface3,
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, if (active) Cyan else Stroke)
            ) {
                Text(
                    label,
                    color = if (active) Bg else TextMain,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.18f),
            contentColor = TextMain,
            disabledContainerColor = Surface3,
            disabledContentColor = TextMuted
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    }
}

private fun value(v: Any?): String = v?.toString() ?: "—"
