package com.byd.dolphin.autoassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.core.*
import kotlinx.coroutines.launch
import java.util.Locale

private val Bg = Color(0xFF050A0F)
private val Rail = Color(0xFF091017)
private val Surface = Color(0xFF0F1821)
private val Surface2 = Color(0xFF142331)
private val TextMain = Color(0xFFF1F7FA)
private val TextMuted = Color(0xFF8B9EAF)
private val Cyan = Color(0xFF3BCFF5)
private val Green = Color(0xFF45D48A)
private val Amber = Color(0xFFFFC857)
private val Red = Color(0xFFFF6B6B)

private enum class Section(val ko: String, val en: String) {
    Home("홈", "HOME"),
    Drive("주행", "DRIVE"),
    Vehicle("차량", "VEHICLE"),
    Audio("오디오 · 경고", "AUDIO"),
    Display("내비 · 화면", "DISPLAY"),
    Automation("자동화", "AUTO"),
    Lab("LAB · 진단", "LAB")
}

@Composable
fun NextApp(repository: VehicleRepository, onCheckUpdate: () -> Unit) {
    val scheme = darkColorScheme(
        background = Bg,
        surface = Surface,
        primary = Cyan,
        secondary = Green,
        onBackground = TextMain,
        onSurface = TextMain
    )
    MaterialTheme(colorScheme = scheme) {
        var section by rememberSaveable { mutableStateOf(Section.Home) }
        val state by repository.state.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxSize().background(Bg)) {
            Sidebar(section) { section = it }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Page(section, state, repository, onCheckUpdate)
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Section, onSelect: (Section) -> Unit) {
    Column(
        Modifier.width(188.dp).fillMaxHeight().background(Rail).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(48.dp).background(Cyan, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("DA", color = Bg, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("DolphinAssistant", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("NEXT", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        Spacer(Modifier.height(18.dp))

        Section.entries.forEach { item ->
            val active = item == selected
            Column(
                Modifier.fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .background(if (active) Surface2 else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(item.en, color = if (active) Cyan else TextMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                Text(item.ko, color = if (active) TextMain else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.weight(1f))
        Text("KOREA DOLPHIN", color = TextMuted, fontSize = 8.sp)
        Text("DiLink 3.0", color = TextMuted, fontSize = 8.sp)
        Spacer(Modifier.height(8.dp))
        Text("RESEARCH SYNC", color = Green, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Page(
    section: Section,
    state: VehicleState,
    repository: VehicleRepository,
    onCheckUpdate: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(section.ko, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(subtitle(section), color = TextMuted, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onCheckUpdate) { Text("업데이트 확인") }
        }
        Text(BuildConfig.VERSION_NAME, color = TextMuted, fontSize = 9.sp, modifier = Modifier.align(Alignment.End))
        Spacer(Modifier.height(16.dp))

        when (section) {
            Section.Home -> HomePage(state)
            Section.Drive -> DrivePage(state)
            Section.Vehicle -> VehiclePage(state, repository)
            Section.Audio -> AudioPage()
            Section.Display -> DisplayPage()
            Section.Automation -> AutomationPage()
            Section.Lab -> LabPage(state)
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun subtitle(section: Section) = when (section) {
    Section.Home -> "완전히 새 코드베이스 · 한 기능은 한 화면에서만"
    Section.Drive -> "VehicleState를 읽기 전용으로 확인"
    Section.Vehicle -> "실차 검증된 편의 제어만 직접 실행"
    Section.Audio -> "새 Audio Engine의 단일 설정 소유 화면"
    Section.Display -> "분할 · HUD · 계기판 · 디스플레이"
    Section.Automation -> "시동 앱 · 미디어 · 차량 자동화"
    Section.Lab -> "미확인 신호와 기능은 일반 메뉴에서 격리"
}

@Composable
private fun HomePage(state: VehicleState) {
    Hero("DolphinAssistant Next", "기존 MainActivity/XML/패치 체인을 사용하지 않는 새 프로젝트입니다.")
    SectionTitle("현재 기반")
    TwoCards(
        { InfoCard("VehicleState", "500ms snapshot · timestamp · stale 처리", "ACTIVE", Cyan) },
        { InfoCard("BYD Gateway", "reflection 단일 진입점", "ACTIVE", Cyan) }
    )
    TwoCards(
        { InfoCard("앞좌석/핸들 열선", "검증 경로만 이식", "VERIFIED", Green) },
        { InfoCard("업데이트", "GitHub Release → 앱 내부 설치", "ACTIVE", Green) }
    )
    SectionTitle("실시간 연결")
    SignalCard("마지막 probe", if (state.lastProbeMs == 0L) "대기 중" else "BYD snapshot 수신", state.lastProbeMs != 0L)
    SectionTitle("설계 원칙")
    BodyCard("SignalSource → SignalResolver → VehicleState → Event Engine → Audio/UI\n\nUI는 BYD HAL을 직접 호출하지 않습니다. 신규 후보는 capability cache와 LAB에서 먼저 검증합니다.")
}

@Composable
private fun DrivePage(state: VehicleState) {
    Notice("이 화면은 상태만 보여줍니다. 소리 종류·문구·목소리는 ‘오디오 · 경고’ 한 곳에서만 설정합니다.")
    SectionTitle("주행 신호")
    val driveRaw = state.driveRaw?.value?.toString() ?: "?"
    val regenRaw = state.regenRaw?.value?.toString() ?: "UNKNOWN"
    val avhRaw = state.autoHoldRaw?.value?.toString() ?: "UNKNOWN"
    val bsdRaw = state.bsdRaw?.value?.toString() ?: "UNKNOWN"
    val turnRaw = state.turnRaw?.value?.toString() ?: "?"
    val speed = state.speedKph?.value?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "UNKNOWN"
    TwoCards(
        { SignalInfo("주행모드", state.driveLabel?.value ?: "UNKNOWN", "raw=" + driveRaw, CapabilityLevel.BETA) },
        { SignalInfo("회생제동", regenRaw, "raw signal", CapabilityLevel.BETA) }
    )
    TwoCards(
        { SignalInfo("AutoHold", avhRaw, "AVH raw · 체결 이벤트는 아직 미연결", CapabilityLevel.BETA) },
        { SignalInfo("BSD", bsdRaw, "turn raw=" + turnRaw, CapabilityLevel.BETA) }
    )
    TwoCards(
        { SignalInfo("속도", speed, state.speedKph?.source ?: "SPEED", CapabilityLevel.BETA) },
        { InfoCard("전방차량출발", "확정 ADAS/radar 신호 전까지 일반 기능화하지 않음", "BETA", Amber) }
    )
}

@Composable
private fun VehiclePage(state: VehicleState, repository: VehicleRepository) {
    val scope = rememberCoroutineScope()
    Notice("실차에서 반복 확인된 편의 기능만 직접 제어합니다. 실내등·다운미러·메모리시트는 여기에 없습니다.")
    SectionTitle("운전석 열선")
    HeatControl("운전석", state.driverHeat?.value) { level ->
        scope.launch { repository.setSeatHeat(1, level) }
    }
    SectionTitle("동승석 열선")
    HeatControl("동승석", state.passengerHeat?.value) { level ->
        scope.launch { repository.setSeatHeat(2, level) }
    }
    SectionTitle("핸들 · 공조")
    TwoCards(
        {
            ToggleCard("핸들 열선", state.steeringHeat?.value == true, "VERIFIED") { enabled ->
                scope.launch { repository.setSteeringHeat(enabled) }
            }
        },
        {
            ToggleCard("공조 전원", state.acOn?.value == true, "VERIFIED") { enabled ->
                scope.launch { repository.setAcPower(enabled) }
            }
        }
    )
}

@Composable
private fun AudioPage() {
    Notice("Next에서는 오디오 설정을 이 화면 하나가 소유합니다. 이전 설정값은 자동 이관하지 않습니다.")
    SectionTitle("Audio Engine Next")
    InfoCard("출력 프로필", "OFF / 비프 / TTS · 항목별 단일 Profile 모델", "FOUNDATION", Cyan)
    Spacer(Modifier.height(10.dp))
    InfoCard("Supertonic 3", "남/녀 화자·음색 프리셋은 다음 이식 단계에서 새 엔진으로 연결", "MIGRATING", Amber)
    Spacer(Modifier.height(10.dp))
    InfoCard("운전석 전용 출력", "DolphinAssistant 자체 stream14 경로만 이식 대상 · 타 앱 routing은 LAB", "LAB", Red)
    SectionTitle("이식 대상 이벤트")
    BodyCard("기어 · 회생제동 · ECO/NORMAL/SPORT · Snow · AutoHold 스위치 · AutoHold 체결/해제 · EPB · ICC · BSD · 전방차량출발")
}

@Composable
private fun DisplayPage() {
    Notice("계기판은 Android 화면으로 가정하지 않습니다. Qt/CAN 안전 계층을 일반 IVI UI와 분리합니다.")
    SectionTitle("확인된 구조")
    InfoCard("2분할", "검증된 DiLink 3 primary/secondary 경로를 다음 단계에서 깨끗하게 이식", "VERIFIED", Green)
    Spacer(Modifier.height(10.dp))
    InfoCard("계기판 TBT", "AUTONAVI_STANDARD_BROADCAST_SEND → AmapService → CAN 경로 연구", "LAB", Red)
    Spacer(Modifier.height(10.dp))
    InfoCard("계기판 테마", "Qt/QML · AutoContainer/theme projection 제약. 임의 theme write 금지", "LAB", Red)
    Spacer(Modifier.height(10.dp))
    InfoCard("DPI/화면", "logical size · density · fontScale을 분리 설계", "LAB", Red)
}

@Composable
private fun AutomationPage() {
    Notice("기존 BootAutomation 코드를 그대로 복사하지 않고 VehicleState 기반 규칙 엔진으로 다시 만듭니다.")
    SectionTitle("Next 규칙 구조")
    BodyCard("Trigger → Guard → Action\n\n예: READY_ON → gear=P 확인 → 앱 실행\n미디어 앱은 실행 지연과 재생 지연을 별도 저장")
    SectionTitle("이식 예정")
    TwoCards(
        { InfoCard("다중 앱 자동실행", "앱별 0.1초 지연", "MIGRATING", Amber) },
        { InfoCard("미디어 자동재생", "앱별 media flag + 재생 지연", "MIGRATING", Amber) }
    )
}

@Composable
private fun LabPage(state: VehicleState) {
    Notice("LAB은 read-only discovery가 기본입니다. 검증 전 setter 자동 순회는 하지 않습니다.")
    SectionTitle("Capability 분류")
    CapabilityCatalog.entries.forEach { cap ->
        val color = when (cap.level) {
            CapabilityLevel.VERIFIED -> Green
            CapabilityLevel.BETA -> Amber
            CapabilityLevel.LAB -> Red
            CapabilityLevel.UNKNOWN -> TextMuted
        }
        InfoCard(cap.title, cap.note, cap.level.name, color)
        Spacer(Modifier.height(8.dp))
    }
    SectionTitle("Signal Health")
    val health = listOf(
        "drive=" + (state.driveRaw?.value?.toString() ?: "?"),
        "regen=" + (state.regenRaw?.value?.toString() ?: "?"),
        "avh=" + (state.autoHoldRaw?.value?.toString() ?: "?"),
        "bsd=" + (state.bsdRaw?.value?.toString() ?: "?"),
        "turn=" + (state.turnRaw?.value?.toString() ?: "?"),
        "speed=" + (state.speedKph?.value?.toString() ?: "?")
    ).joinToString(" · ")
    BodyCard(health)
}

@Composable
private fun HeatControl(label: String, current: Int?, onSet: (Int) -> Unit) {
    FoundationCard {
        val stateText = when (current) { 0 -> "OFF"; 1 -> "1단"; 2 -> "2단"; else -> "확인 중" }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label + " · " + stateText, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Badge("VERIFIED", Green)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "OFF", 1 to "1단", 2 to "2단").forEach { pair ->
                FilterChip(
                    selected = current == pair.first,
                    onClick = { onSet(pair.first) },
                    label = { Text(pair.second) }
                )
            }
        }
    }
}

@Composable
private fun ToggleCard(title: String, checked: Boolean, status: String, onToggle: (Boolean) -> Unit) {
    FoundationCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(if (checked) "현재 켜짐" else "현재 꺼짐", color = TextMuted, fontSize = 9.sp)
            }
            Badge(status, Green)
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SignalInfo(title: String, value: String, desc: String, level: CapabilityLevel) {
    val color = when (level) {
        CapabilityLevel.VERIFIED -> Green
        CapabilityLevel.BETA -> Amber
        CapabilityLevel.LAB -> Red
        CapabilityLevel.UNKNOWN -> TextMuted
    }
    InfoCard(title, value + " · " + desc, level.name, color)
}

@Composable
private fun Hero(title: String, desc: String) {
    FoundationCard {
        Text(title, color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Text(desc, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = Surface2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 9.dp))
}

@Composable
private fun TwoCards(a: @Composable () -> Unit, b: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { a() }
        Box(Modifier.weight(1f)) { b() }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun InfoCard(title: String, desc: String, badge: String, color: Color) {
    FoundationCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Badge(badge, color)
        }
        Spacer(Modifier.height(8.dp))
        Text(desc, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun SignalCard(title: String, desc: String, active: Boolean) {
    InfoCard(title, desc, if (active) "CONNECTED" else "WAIT", if (active) Green else Amber)
}

@Composable
private fun BodyCard(text: String) {
    FoundationCard { Text(text, color = TextMuted, fontSize = 11.sp, lineHeight = 17.sp) }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(10.dp)) {
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun FoundationCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
