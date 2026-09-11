package com.byd.dolphin.autoassistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.floating.FloatingItem
import com.byd.dolphin.autoassistant.floating.FloatingItemManager
import com.byd.dolphin.autoassistant.activity.ShortcutActionActivity
import com.byd.dolphin.autoassistant.hud.ClusterMirrorManager
import com.byd.dolphin.autoassistant.hud.HudAudioManager
import com.byd.dolphin.autoassistant.hud.HudDataManager
import com.byd.dolphin.autoassistant.hud.NavGuidanceParser
import com.byd.dolphin.autoassistant.hud.HudSemanticValues
import com.byd.dolphin.autoassistant.hud.TmapPlusHudBluetoothManager
import com.byd.dolphin.autoassistant.manager.*
import com.byd.dolphin.autoassistant.service.DolphinService
import com.byd.dolphin.autoassistant.split.SplitConfig
import com.byd.dolphin.autoassistant.split.SplitMode
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: VoiceAndSoundManager

    // 10대 세분화 서브 화면 뷰 레퍼런스
    private lateinit var layoutMainDashboard: View
    private lateinit var subLayoutSplit: View
    private lateinit var subLayoutComfort: View
    private lateinit var subLayoutVoice: View
    private lateinit var subLayoutSafetyAudio: View
    private lateinit var subLayoutHud: View
    private lateinit var subLayoutCluster: View
    private lateinit var subLayoutButtonBuilder: View
    private lateinit var subLayoutAutomation: View
    private lateinit var subLayoutBootScheduler: View
    private lateinit var subLayoutDpiAdb: View

    // 분할 화면 대상 앱 상태
    private var splitApp1Pkg = "com.skt.tmap.ku"
    private var splitApp1Name = "티맵"
    private var splitApp2Pkg = "com.android.music"
    private var splitApp2Name = "기본 미디어"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DolphinLogger.init(this)
        DiagnosticCaptureManager.recoverInterruptedSession(this)
        audioManager = VoiceAndSoundManager(this)
        ensureNotificationPermission()

        initViewReferences()
        setupCardNavigation()
        setupDashboardHeader()
        setupSplitSubScreen()
        setupSeatSubScreen()
        setupVoiceSubScreen()
        setupSafetyAudioSubScreen()
        setupHudSubScreen()
        setupClusterSubScreen()
        setupButtonBuilderSubScreen()
        setupAutomationSubScreen()
        setupBootSchedulerSubScreen()
        setupDpiAdbSubScreen()

        // 1. 앱 실행 즉시 백그라운드 서비스 및 플로팅 독 가동
        startDolphinService()

        // 2. 차량 자체 loopback ADB에 한정해 필요한 앱 권한 승인 시도
        performAutoAdbGrant()
    }

    override fun onResume() {
        super.onResume()
        updateDashboardCards()
        if (AdbPermissionManager.isOverlayGranted(this) && SettingsManager.isFloatingOverlayEnabled(this)) {
            FloatingOverlayManager.show(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::audioManager.isInitialized) audioManager.release()
    }

    override fun onBackPressed() {
        if (layoutMainDashboard.visibility != View.VISIBLE) {
            showMainDashboard()
        } else {
            super.onBackPressed()
        }
    }

    private fun startDolphinService() {
        try {
            val serviceIntent = Intent(this, DolphinService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            if (AdbPermissionManager.isOverlayGranted(this) && SettingsManager.isFloatingOverlayEnabled(this)) {
                FloatingOverlayManager.show(this)
            } else if (!AdbPermissionManager.isOverlayGranted(this)) {
                // 플로팅 권한 안내
                Toast.makeText(this, "플로팅 독을 위해 '다른 앱 위에 표시' 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
            DolphinLogger.i("MainActivity", "DolphinService 및 플로팅 독 자동 가동 완료")
            findViewById<TextView>(R.id.tvServiceStatusBadge).apply {
                text = "🟢 서비스 실행 · 차량 API 권한 확인 중"
                setTextColor(Color.parseColor("#00E676"))
            }
        } catch (e: Exception) {
            DolphinLogger.e("MainActivity", "DolphinService 가동 실패", e)
            findViewById<TextView>(R.id.tvServiceStatusBadge).apply {
                text = "🔴 서비스 시작 실패 · 진단 로그 확인"
                setTextColor(Color.parseColor("#FF5252"))
            }
        }
    }

    private fun initViewReferences() {
        layoutMainDashboard = findViewById(R.id.layoutMainDashboard)
        subLayoutSplit = findViewById(R.id.subLayoutSplit)
        subLayoutComfort = findViewById(R.id.subLayoutComfortV30)
        subLayoutVoice = findViewById(R.id.subLayoutVoice)
        subLayoutSafetyAudio = findViewById(R.id.subLayoutSafetyAudio)
        subLayoutHud = findViewById(R.id.subLayoutHud)
        subLayoutCluster = findViewById(R.id.subLayoutCluster)
        subLayoutButtonBuilder = findViewById(R.id.subLayoutButtonBuilder)
        subLayoutAutomation = findViewById(R.id.subLayoutAutomation)
        subLayoutBootScheduler = findViewById(R.id.subLayoutBootScheduler)
        subLayoutDpiAdb = findViewById(R.id.subLayoutDpiAdb)
    }

    private fun showMainDashboard() {
        layoutMainDashboard.visibility = View.VISIBLE
        subLayoutSplit.visibility = View.GONE
        subLayoutComfort.visibility = View.GONE
        subLayoutVoice.visibility = View.GONE
        subLayoutSafetyAudio.visibility = View.GONE
        subLayoutHud.visibility = View.GONE
        subLayoutCluster.visibility = View.GONE
        subLayoutButtonBuilder.visibility = View.GONE
        subLayoutAutomation.visibility = View.GONE
        subLayoutBootScheduler.visibility = View.GONE
        subLayoutDpiAdb.visibility = View.GONE
        updateDashboardCards()
    }

    private fun showSubScreen(targetSubLayout: View) {
        layoutMainDashboard.visibility = View.GONE
        subLayoutSplit.visibility = View.GONE
        subLayoutComfort.visibility = View.GONE
        subLayoutVoice.visibility = View.GONE
        subLayoutSafetyAudio.visibility = View.GONE
        subLayoutHud.visibility = View.GONE
        subLayoutCluster.visibility = View.GONE
        subLayoutButtonBuilder.visibility = View.GONE
        subLayoutAutomation.visibility = View.GONE
        subLayoutBootScheduler.visibility = View.GONE
        subLayoutDpiAdb.visibility = View.GONE
        targetSubLayout.visibility = View.VISIBLE
    }

    private fun performAutoAdbGrant() {
        val tvStatus = findViewById<TextView>(R.id.tvServiceStatusBadge)
        AdbPermissionManager.autoGrantPermissionsOnLaunch(this) { success, msg ->
            if (success) {
                tvStatus.text = "🟢 서비스 실행 · 앱/알림 권한 확인 완료"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
            } else {
                tvStatus.text = "🟡 서비스 실행 · 일부 시스템 권한 미승인 (진단 확인)"
                tvStatus.setTextColor(Color.parseColor("#FFD54F"))
                DolphinLogger.w("MainActivity", "자동 권한 확인 미완료: $msg")
            }
        }
    }

    private fun setupCardNavigation() {
        findViewById<CardView>(R.id.cardMenuSplit).setOnClickListener { showSubScreen(subLayoutSplit) }
        findViewById<CardView>(R.id.cardMenuSeat).setOnClickListener { showSubScreen(subLayoutComfort) }
        findViewById<CardView>(R.id.cardMenuVoice).setOnClickListener { showSubScreen(subLayoutVoice) }
        findViewById<CardView>(R.id.cardMenuSafetyAudio).setOnClickListener { showSubScreen(subLayoutSafetyAudio) }
        findViewById<CardView>(R.id.cardMenuHud).setOnClickListener { showSubScreen(subLayoutHud) }
        findViewById<CardView>(R.id.cardMenuCluster).setOnClickListener { showSubScreen(subLayoutCluster) }
        findViewById<CardView>(R.id.cardMenuButtonBuilder).setOnClickListener { showSubScreen(subLayoutButtonBuilder) }
        findViewById<CardView>(R.id.cardMenuAutomation).setOnClickListener { showSubScreen(subLayoutAutomation) }
        findViewById<CardView>(R.id.cardMenuBootScheduler).setOnClickListener { showSubScreen(subLayoutBootScheduler) }
        findViewById<CardView>(R.id.cardMenuDpiAdb).setOnClickListener { showSubScreen(subLayoutDpiAdb) }

        findViewById<Button>(R.id.btnBackSplit).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackSeatV30).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackVoice).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackSafetyAudio).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackHud).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackCluster).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackButtonBuilder).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackAutomation).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackBootScheduler).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackDpiAdb).setOnClickListener { showMainDashboard() }
    }

    private fun setupDashboardHeader() {
        val btnRestartService = findViewById<Button>(R.id.btnRestartService)

        btnRestartService.setOnClickListener {
            val serviceIntent = Intent(this, DolphinService::class.java)
            stopService(serviceIntent)
            startDolphinService()
            if (AdbPermissionManager.isOverlayGranted(this)) {
                FloatingOverlayManager.show(this)
            }
            Toast.makeText(this, "어시스턴트 서비스 재시작을 요청했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDashboardCards() {
        val curDpi = DpiManager.getCurrentDensity(this)
        findViewById<TextView>(R.id.tvMainCardDpiDesc).text =
            "현재 밀도: $curDpi · 원터치 프리셋\n15분 자동 수집 · 진단 ZIP 추출"
    }

    // =========================================================================
    // 1. DiLink 3에서 실차 검증된 2분할 (20~80%, 1% 단위)
    // =========================================================================
    private fun setupSplitSubScreen() {
        val btnApp1 = findViewById<Button>(R.id.btnSelectApp1)
        val btnApp2 = findViewById<Button>(R.id.btnSelectApp2)

        val sbRatioX = findViewById<SeekBar>(R.id.sbSubSplitRatio)
        val etRatioX = findViewById<EditText>(R.id.etSplitRatioX)

        val tvPreviewLeft = findViewById<TextView>(R.id.tvPreviewLeft)
        val tvPreviewRight = findViewById<TextView>(R.id.tvPreviewRight)

        sbRatioX.min = 20
        sbRatioX.max = 80

        fun updateAppButtons() {
            btnApp1.text = "앱 1: " + splitApp1Name
            btnApp2.text = "앱 2: " + splitApp2Name
        }

        fun updatePreview(x: Int) {
            val left = x.coerceIn(20, 80)
            val right = 100 - left
            val leftParams = tvPreviewLeft.layoutParams as LinearLayout.LayoutParams
            leftParams.weight = left.toFloat()
            tvPreviewLeft.layoutParams = leftParams

            val rightParams = tvPreviewRight.layoutParams as LinearLayout.LayoutParams
            rightParams.weight = right.toFloat()
            tvPreviewRight.layoutParams = rightParams

            tvPreviewLeft.text = splitApp1Name + "\n(" + left + "%)"
            tvPreviewRight.text = splitApp2Name + "\n(" + right + "%)"
        }

        btnApp1.setOnClickListener {
            showAppPicker("분할 화면 앱 1 선택") { pkg, name ->
                splitApp1Pkg = pkg; splitApp1Name = name
                updateAppButtons(); updatePreview(sbRatioX.progress)
            }
        }
        btnApp2.setOnClickListener {
            showAppPicker("분할 화면 앱 2 선택") { pkg, name ->
                splitApp2Pkg = pkg; splitApp2Name = name
                updateAppButtons(); updatePreview(sbRatioX.progress)
            }
        }

        sbRatioX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = progress.coerceIn(20, 80)
                if (fromUser) etRatioX.setText(p.toString())
                updatePreview(p)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        etRatioX.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val num = s?.toString()?.toIntOrNull()
                if (num != null && num in 20..80 && num != sbRatioX.progress) {
                    sbRatioX.progress = num
                    updatePreview(num)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateAppButtons()
        updatePreview(30)

        findViewById<Button>(R.id.btnSubApplySplit).setOnClickListener {
            val config = SplitConfig(
                title = splitApp1Name + " / " + splitApp2Name + " 분할",
                mode = SplitMode.TWO_APPS_HORIZONTAL,
                pkg1 = splitApp1Pkg,
                pkg2 = splitApp2Pkg,
                ratioPrimary = sbRatioX.progress.coerceIn(20, 80)
            )
            SplitScreenManager.launchSplitScreen(this, config)
        }
    }

    // =========================================================================
    // 2. 이 차량에 실제 장착된 앞좌석/핸들 열선 제어
    // =========================================================================
    private fun setupSeatSubScreen() {
        fun levelName(level: Int?): String = when (level) {
            VehicleComfortManager.HEAT_OFF -> "꺼짐"
            VehicleComfortManager.HEAT_LOW -> "1단"
            VehicleComfortManager.HEAT_HIGH -> "2단"
            else -> "확인 불가"
        }

        fun refresh() {
            findViewById<TextView>(R.id.tvDriverHeatStatus).text =
                "운전석 열선: ${levelName(VehicleComfortManager.getSeatHeatingLevel(this, VehicleComfortManager.SEAT_DRIVER))}"
            findViewById<TextView>(R.id.tvPassengerHeatStatus).text =
                "동승석 열선: ${levelName(VehicleComfortManager.getSeatHeatingLevel(this, VehicleComfortManager.SEAT_PASSENGER))}"
            val steering = VehicleComfortManager.isSteeringWheelHeatingOn(this)
            findViewById<TextView>(R.id.tvSteeringHeatStatus).text =
                "핸들 열선: ${if (steering == true) "켜짐" else if (steering == false) "꺼짐" else "확인 불가"}"
        }

        fun bindSeat(buttonId: Int, seat: Int, level: Int) {
            findViewById<Button>(buttonId).setOnClickListener {
                val ok = VehicleComfortManager.setSeatHeatingLevel(this, seat, level)
                Toast.makeText(this, if (ok) "열선 명령 전송 완료" else "열선 명령 실패 — 진단 로그를 확인하세요", Toast.LENGTH_SHORT).show()
                it.postDelayed({ refresh() }, 500L)
            }
        }

        bindSeat(R.id.btnDriverHeatOff, VehicleComfortManager.SEAT_DRIVER, VehicleComfortManager.HEAT_OFF)
        bindSeat(R.id.btnDriverHeatLow, VehicleComfortManager.SEAT_DRIVER, VehicleComfortManager.HEAT_LOW)
        bindSeat(R.id.btnDriverHeatHigh, VehicleComfortManager.SEAT_DRIVER, VehicleComfortManager.HEAT_HIGH)
        bindSeat(R.id.btnPassengerHeatOff, VehicleComfortManager.SEAT_PASSENGER, VehicleComfortManager.HEAT_OFF)
        bindSeat(R.id.btnPassengerHeatLow, VehicleComfortManager.SEAT_PASSENGER, VehicleComfortManager.HEAT_LOW)
        bindSeat(R.id.btnPassengerHeatHigh, VehicleComfortManager.SEAT_PASSENGER, VehicleComfortManager.HEAT_HIGH)

        findViewById<Button>(R.id.btnSteeringHeatToggle).setOnClickListener { view ->
            val enabled = VehicleComfortManager.toggleSteeringWheelHeating(this)
            Toast.makeText(this, "핸들 열선 ${if (enabled) "켜기" else "끄기"} 명령 전송", Toast.LENGTH_SHORT).show()
            view.postDelayed({ refresh() }, 500L)
        }
        refresh()
    }

    // =========================================================================
    // 3. 맞춤형 차량 음성 안내 (TTS) - [기본 문구], [추천 문구], [수동 직접 입력] 3단 선택기
    // =========================================================================
    private fun setupVoiceSubScreen() {
        findViewById<SwitchCompat>(R.id.swExperimentalLvda).apply {
            isChecked = SettingsManager.isExperimentalLvdaEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                SettingsManager.setExperimentalLvdaEnabled(this@MainActivity, enabled)
                Toast.makeText(
                    this@MainActivity,
                    if (enabled) "실험적 주차센서 기반 출발 후보 감지를 켰습니다."
                    else "실험적 주차센서 기반 출발 후보 감지를 껐습니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        setupVoiceEditButton(R.id.btnVoiceGearP, "P단 (파킹)", "파킹", "주차 기어가 체결되었습니다. 안전 운행을 마칩니다.") { SettingsManager.getGearPhrase(this, "P") }
        setupVoiceEditButton(R.id.btnVoiceGearR, "R단 (후진)", "후진", "후진 기어가 체결되었습니다. 후방 시야를 확인하세요.") { SettingsManager.getGearPhrase(this, "R") }
        setupVoiceEditButton(R.id.btnVoiceGearN, "N단 (중립)", "중립", "중립 기어 상태입니다. 브레이크 페달을 유지하세요.") { SettingsManager.getGearPhrase(this, "N") }
        setupVoiceEditButton(R.id.btnVoiceGearD, "D단 (전진)", "전진", "전진 기어가 체결되었습니다. 안전 운전하십시오.") { SettingsManager.getGearPhrase(this, "D") }
        setupVoiceEditButton(R.id.btnVoiceDriveMode, "드라이브 모드", "노멀 모드", "드라이브 모드가 정상적으로 전환되었습니다.") { SettingsManager.getDriveModePhrase(this, "NORMAL") }
        setupVoiceEditButton(R.id.btnVoiceRegenMode, "회생 제동", "회생제동 에코", "회생제동 감속 제어가 적용되었습니다.") { SettingsManager.getRegenModePhrase(this, "ECO") }

        // 스노우모드 기본 문구는 사용자 요청에 따라 "스노우 모드"로 고정!
        setupVoiceEditButton(R.id.btnVoiceSnowMode, "스노우 모드", "스노우 모드", "노면 미끄럼 방지 스노우 모드가 작동합니다.") { SettingsManager.getSnowModePhrase(this) }

        // 오토홀드 버튼 켬/끔
        setupVoiceEditButton(R.id.btnVoiceAutoHoldSwitchOn, "오토홀드 버튼 켬", "오토홀드가 켜졌습니다.", "오토홀드 대기 모드가 활성화되었습니다.") { SettingsManager.getAutoHoldSwitchPhrase(this, true) }
        setupVoiceEditButton(R.id.btnVoiceAutoHoldSwitchOff, "오토홀드 버튼 끔", "오토홀드가 꺼졌습니다.", "오토홀드 대기 모드가 해제되었습니다.") { SettingsManager.getAutoHoldSwitchPhrase(this, false) }

        // 오토홀드 정차 체결/해제
        setupVoiceEditButton(R.id.btnVoiceAutoHoldBrakeEngaged, "오토홀드 정차 체결", "오토홀드가 체결되었습니다.", "차량이 정차되었습니다. 오토홀드가 유지됩니다.") { SettingsManager.getAutoHoldBrakePhrase(this, true) }
        setupVoiceEditButton(R.id.btnVoiceAutoHoldBrakeReleased, "오토홀드 출발 해제", "오토홀드가 해제되었습니다.", "오토홀드가 해제되었습니다. 서서히 출발합니다.") { SettingsManager.getAutoHoldBrakePhrase(this, false) }

        setupVoiceEditButton(R.id.btnVoiceEpb, "사이드브레이크", "사이드브레이크가 체결되었습니다.", "전자식 주차 브레이크가 안전하게 체결되었습니다.") { SettingsManager.getEpbPhrase(this, true) }
        setupVoiceEditButton(R.id.btnVoiceIcc, "ICC 자율주행", "자율주행이 켜졌습니다.", "스마트 크루즈 어시스트가 주행을 보조합니다.") { SettingsManager.getIccPhrase(this) }
        setupVoiceEditButton(R.id.btnVoiceLeadingCar, "전방 차량 출발", "전방 차량이 출발했습니다.", "전방 차량이 출발했습니다. 주변을 확인한 뒤 안전하게 출발하세요.") { SettingsManager.getLeadingCarPhrase(this) }

        // 충전 시작/종료
        setupVoiceEditButton(R.id.btnVoiceChargingStart, "충전 시작", "충전이 시작되었습니다.", "고전압 배터리 충전이 시작되었습니다.") { SettingsManager.getChargingStartPhrase(this) }
        setupVoiceEditButton(R.id.btnVoiceChargingEnd, "충전 정상 완료", "충전이 완료되었습니다.", "배터리 충전이 정상 완료되었습니다. 안전하게 충전 플러그를 분리해 주세요.") { SettingsManager.getChargingEndPhrase(this) }
    
        findViewById<Button>(R.id.btnTestLeadingCarDeparture).setOnClickListener {
            sendBroadcast(
                Intent(NavGuidanceParser.ACTION_INTERNAL_LEADING_CAR).setPackage(packageName)
            )
            Toast.makeText(this, "앱 내부 전방 출발 음성 테스트 이벤트 전송", Toast.LENGTH_SHORT).show()
        }

    }

    private fun setupVoiceEditButton(
        buttonId: Int,
        title: String,
        defaultPhrase: String,
        recommendedPhrase: String,
        currentPhraseProvider: () -> String
    ) {
        val btn = findViewById<Button>(buttonId)
        val initialPhrase = currentPhraseProvider()
        btn.text = "$title: \"$initialPhrase\""

        btn.setOnClickListener {
            val input = EditText(this).apply {
                setText(currentPhraseProvider())
                setSelection(text.length)
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 10)
            }

            // [기본 문구] & [추천 문구] 원터치 버튼 배치
            val rowPresets = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
            }

            val btnDefault = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 8, 0)
                }
                text = "🔹 기본 문구:\n\"$defaultPhrase\""
                textSize = 11f
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2C2C38"))
                setTextColor(Color.parseColor("#80D8FF"))
                setOnClickListener {
                    input.setText(defaultPhrase)
                    input.setSelection(defaultPhrase.length)
                    audioManager.speak(defaultPhrase)
                }
            }
            rowPresets.addView(btnDefault)

            val btnRecommended = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(8, 0, 0, 0)
                }
                text = "✨ 추천 문구:\n\"$recommendedPhrase\""
                textSize = 11f
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#37284F"))
                setTextColor(Color.parseColor("#B388FF"))
                setOnClickListener {
                    input.setText(recommendedPhrase)
                    input.setSelection(recommendedPhrase.length)
                    audioManager.speak(recommendedPhrase)
                }
            }
            rowPresets.addView(btnRecommended)

            container.addView(rowPresets)

            val tvInputLabel = TextView(this).apply {
                text = "✏️ 수동 직접 입력 (터치하여 자유롭게 수정):"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 12f
            }
            container.addView(tvInputLabel)
            container.addView(input)

            AlertDialog.Builder(this)
                .setTitle(title + " 안내 음성 설정")
                .setView(container)
                .setNeutralButton("미리듣기") { _, _ -> }
                .setPositiveButton("저장") { _, _ ->
                    val newText = input.text.toString().trim()
                    if (newText.isNotEmpty()) {
                        saveCustomPhrase(buttonId, newText)
                        btn.text = "$title: \"$newText\""
                        Toast.makeText(this, "멘트가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("취소", null)
                .create().apply {
                    show()
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val previewText = input.text.toString().trim()
                        if (previewText.isNotEmpty()) {
                            audioManager.speak(previewText)
                        }
                    }
                }
        }
    }

    private fun saveCustomPhrase(buttonId: Int, phrase: String) {
        when (buttonId) {
            R.id.btnVoiceGearP -> SettingsManager.setGearPhrase(this, "P", phrase)
            R.id.btnVoiceGearR -> SettingsManager.setGearPhrase(this, "R", phrase)
            R.id.btnVoiceGearN -> SettingsManager.setGearPhrase(this, "N", phrase)
            R.id.btnVoiceGearD -> SettingsManager.setGearPhrase(this, "D", phrase)
            R.id.btnVoiceDriveMode -> SettingsManager.setDriveModePhrase(this, "NORMAL", phrase)
            R.id.btnVoiceRegenMode -> SettingsManager.setRegenModePhrase(this, "ECO", phrase)
            R.id.btnVoiceSnowMode -> SettingsManager.setSnowModePhrase(this, phrase)
            R.id.btnVoiceAutoHoldSwitchOn -> SettingsManager.setAutoHoldSwitchPhrase(this, true, phrase)
            R.id.btnVoiceAutoHoldSwitchOff -> SettingsManager.setAutoHoldSwitchPhrase(this, false, phrase)
            R.id.btnVoiceAutoHoldBrakeEngaged -> SettingsManager.setAutoHoldBrakePhrase(this, true, phrase)
            R.id.btnVoiceAutoHoldBrakeReleased -> SettingsManager.setAutoHoldBrakePhrase(this, false, phrase)
            R.id.btnVoiceEpb -> SettingsManager.setEpbPhrase(this, true, phrase)
            R.id.btnVoiceIcc -> SettingsManager.setIccPhrase(this, phrase)
            R.id.btnVoiceLeadingCar -> SettingsManager.setLeadingCarPhrase(this, phrase)
            R.id.btnVoiceChargingStart -> SettingsManager.setChargingStartPhrase(this, phrase)
            R.id.btnVoiceChargingEnd -> SettingsManager.setChargingEndPhrase(this, phrase)
        }
    }

    // =========================================================================
    // 4. 안전 경고 원시값 진단 및 안내음 미리듣기
    // =========================================================================
    private fun setupSafetyAudioSubScreen() {
        findViewById<Button>(R.id.btnConfigBsdAlert).setOnClickListener {
            showBsdLdpConfigDialog("BSD 안내음 미리듣기 설정", isBsd = true)
        }
        findViewById<Button>(R.id.btnConfigLdpAlert).setOnClickListener {
            showBsdLdpConfigDialog("차선 안내음 미리듣기 설정", isBsd = false)
        }
    }

    private fun showBsdLdpConfigDialog(title: String, isBsd: Boolean) {
        val options = arrayOf("1. 현대/기아 스타일 경고음 (비프)", "2. 추천 안내 음성 (TTS)", "3. 사용자 수동 직접 입력 (TTS)")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (isBsd) SettingsManager.setBsdAlertMode(this, "BEEP")
                        else SettingsManager.setLdpAlertMode(this, "BEEP")
                        Toast.makeText(this, "현대/기아 스타일 경고음 적용", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val recText = if (isBsd) "후측방에 차량이 접근 중입니다." else "차선을 이탈했습니다."
                        if (isBsd) {
                            SettingsManager.setBsdAlertMode(this, "VOICE_RECOMMENDED")
                            SettingsManager.setBsdCustomText(this, recText)
                        } else {
                            SettingsManager.setLdpAlertMode(this, "VOICE_RECOMMENDED")
                            SettingsManager.setLdpCustomText(this, recText)
                        }
                        audioManager.speak(recText)
                        Toast.makeText(this, "추천 안내 음성 적용", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val currentText = if (isBsd) SettingsManager.getBsdCustomText(this) else SettingsManager.getLdpCustomText(this)
                        val input = EditText(this).apply { setText(currentText) }
                        AlertDialog.Builder(this)
                            .setTitle("수동 멘트 입력")
                            .setView(input)
                            .setNeutralButton("미리듣기") { _, _ -> }
                            .setPositiveButton("저장") { _, _ ->
                                val text = input.text.toString().trim()
                                if (text.isNotEmpty()) {
                                    if (isBsd) {
                                        SettingsManager.setBsdAlertMode(this, "VOICE_CUSTOM")
                                        SettingsManager.setBsdCustomText(this, text)
                                    } else {
                                        SettingsManager.setLdpAlertMode(this, "VOICE_CUSTOM")
                                        SettingsManager.setLdpCustomText(this, text)
                                    }
                                    Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("취소", null)
                            .create().apply {
                                show()
                                getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                                    audioManager.speak(input.text.toString().trim())
                                }
                            }
                    }
                }
            }
            .show()
    }

    // =========================================================================
    // 5. 티맵 Plus HUD (Bluetooth 연결 진단; 패킷 송신 잠금)
    // =========================================================================
    private fun setupHudSubScreen() {
        val tvDataStatus = findViewById<TextView>(R.id.tvHudDataStatus)
        val tvAudioStatus = findViewById<TextView>(R.id.tvHudAudioStatus)
        val protocolConfirmed = SettingsManager.isHudProtocolConfirmed(this)
        tvDataStatus.text = if (protocolConfirmed) "TMAP Plus HUD / T900 프로토콜 확인됨" else "T900 확인 · 패킷 형식 미확인으로 송신 잠금"
        tvAudioStatus.text = "오디오 연결 상태는 Bluetooth 설정에서 확인"

        findViewById<Button>(R.id.btnConnectHudData).setOnClickListener {
            if (!ensureBluetoothPermission()) return@setOnClickListener
            tvDataStatus.text = "페어링 기기/UUID 및 SPP 연결 진단 중..."
            TmapPlusHudBluetoothManager.connectHudData(this) { ok, msg ->
                runOnUiThread {
                    tvDataStatus.text = if (ok) "데이터(huddata): 연결 성공" else "데이터: " + msg
                    tvDataStatus.setTextColor(if (ok) Color.parseColor("#00E676") else Color.parseColor("#FF5252"))
                }
            }
        }

        findViewById<Button>(R.id.btnConnectHudAudio).setOnClickListener {
            if (!ensureBluetoothPermission()) return@setOnClickListener
            TmapPlusHudBluetoothManager.connectHudAudio(this) { ok, msg ->
                tvAudioStatus.text = msg
                tvAudioStatus.setTextColor(Color.parseColor("#FFD54F"))
            }
        }

        val swAuto = findViewById<SwitchCompat>(R.id.swHudBrightnessAuto)
        val sbMin = findViewById<SeekBar>(R.id.sbHudBrightMin)
        val tvMin = findViewById<TextView>(R.id.tvHudBrightMinVal)
        val sbMax = findViewById<SeekBar>(R.id.sbHudBrightMax)
        val tvMax = findViewById<TextView>(R.id.tvHudBrightMaxVal)
        val sbManual = findViewById<SeekBar>(R.id.sbHudBrightness)
        val tvManual = findViewById<TextView>(R.id.tvHudBrightnessValue)

        swAuto.isEnabled = protocolConfirmed
        sbMin.isEnabled = protocolConfirmed
        sbMax.isEnabled = protocolConfirmed
        sbManual.isEnabled = protocolConfirmed

        swAuto.isChecked = SettingsManager.isHudBrightnessAuto(this)
        sbMin.progress = SettingsManager.getHudBrightnessMin(this)
        sbMax.progress = SettingsManager.getHudBrightnessMax(this)
        sbManual.progress = SettingsManager.getHudBrightnessManual(this)

        tvMin.text = "자동 조절 최소 밝기: " + sbMin.progress + " 단계"
        tvMax.text = "자동 조절 최대 밝기: " + sbMax.progress + " 단계"
        tvManual.text = "수동 고정 밝기: " + sbManual.progress + " 단계"

        fun syncBrightness() {
            SettingsManager.setHudBrightnessAuto(this, swAuto.isChecked)
            SettingsManager.setHudBrightnessMin(this, sbMin.progress)
            SettingsManager.setHudBrightnessMax(this, sbMax.progress)
            SettingsManager.setHudBrightnessManual(this, sbManual.progress)
            HudDataManager.applyBrightness(this, swAuto.isChecked, sbManual.progress, sbMin.progress, sbMax.progress)
        }

        swAuto.setOnCheckedChangeListener { _, _ -> syncBrightness() }

        sbMin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMin.text = "자동 조절 최소 밝기: " + progress + " 단계"
                if (fromUser) syncBrightness()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMax.text = "자동 조절 최대 밝기: " + progress + " 단계"
                if (fromUser) syncBrightness()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbManual.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvManual.text = "수동 고정 밝기: " + progress + " 단계"
                if (fromUser) syncBrightness()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val sbAudioVol = findViewById<SeekBar>(R.id.sbSubHudAudioVolume)
        sbAudioVol.isEnabled = protocolConfirmed
        sbAudioVol.progress = SettingsManager.getHudAudioVolume(this)
        sbAudioVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) SettingsManager.setHudAudioVolume(this@MainActivity, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun ensureBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val required = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), 900)
        Toast.makeText(this, "근처 기기 권한을 허용한 뒤 버튼을 다시 눌러주세요.", Toast.LENGTH_LONG).show()
        return false
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                901
            )
        }
    }

    // =========================================================================
    // 6. 계기판 TBT 연동 및 선택형 보조 접근성 입력
    // =========================================================================
    private fun setupClusterSubScreen() {
        val swClusterTbt = findViewById<SwitchCompat>(R.id.swClusterTbt)
        swClusterTbt.isChecked = SettingsManager.isClusterTbtEnabled(this)
        swClusterTbt.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) ClusterMirrorManager.clearClusterTbt(this)
            SettingsManager.setClusterTbtEnabled(this, isChecked)
        }

        findViewById<Button>(R.id.btnOpenAccessibilitySettings).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                Toast.makeText(this, "'돌핀 내비 TBT 알림 수신 브릿지'를 켜주세요.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "설정 진입 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnTestClusterTbt).setOnClickListener {
            val success = ClusterMirrorManager.sendTbtToCluster(
                this,
                HudSemanticValues.TURN_RIGHT,
                350,
                "350m",
                60,
                "전방 교차로",
                true
            )
            Toast.makeText(
                this,
                if (success) {
                    "BYD 계기판 TBT API가 성공 코드를 반환했습니다. 표시 여부를 확인해 주세요."
                } else {
                    "TBT API가 실패했거나 기능이 꺼져 있습니다. 진단 로그를 확인해 주세요."
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =========================================================================
    // 7. 바로가기 & 플로팅 버튼 빌더 (크기, 투명도, 앱 아이콘 표출 커스텀)
    // =========================================================================
    private fun setupButtonBuilderSubScreen() {
        setupAppDrawerShortcutSwitch(R.id.swDrawerDefrost, ".shortcut.DefrostLauncher")
        setupAppDrawerShortcutSwitch(R.id.swDrawerLightToggle, ".shortcut.LightToggleLauncher")
        setupAppDrawerShortcutSwitch(R.id.swDrawerLightOn, ".shortcut.LightOnLauncher")
        setupAppDrawerShortcutSwitch(R.id.swDrawerLightOff, ".shortcut.LightOffLauncher")
        findViewById<SwitchCompat>(R.id.swFloatingOverlay).apply {
            isChecked = SettingsManager.isFloatingOverlayEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                SettingsManager.setFloatingOverlayEnabled(this@MainActivity, enabled)
                if (!enabled) {
                    FloatingOverlayManager.hide()
                } else if (AdbPermissionManager.isOverlayGranted(this@MainActivity)) {
                    FloatingOverlayManager.show(this@MainActivity)
                } else {
                    AdbPermissionManager.openOverlaySettings(this@MainActivity)
                }
            }
        }
        findViewById<Button>(R.id.btnCreateQpButton).setOnClickListener {
            showActionAndFloatingPicker("상단 퀵 컨트롤 패널 항목", FloatingItemManager.QUICK_PANEL_ITEMS)
        }
        findViewById<Button>(R.id.btnCreateBottomBarButton).setOnClickListener {
            showActionAndFloatingPicker("하단 도크 및 편의 공조 항목", FloatingItemManager.BOTTOM_BAR_ITEMS)
        }
        findViewById<Button>(R.id.btnCreateLightButton).setOnClickListener {
            showActionAndFloatingPicker("실내 조명 원터치 제어 항목", FloatingItemManager.LIGHT_ITEMS)
        }
        findViewById<Button>(R.id.btnCreateAppFloatingButton).setOnClickListener {
            showAppPicker("바로가기 또는 플로팅 생성할 앱 선택") { pkg, name ->
                showDualCreationOptionDialog(FloatingItem("APP_" + pkg, "📱 " + name, isApp = true, packageName = pkg))
            }
        }

        // 플로팅 독 크기(Scale) 조절 슬라이더 (50% ~ 150%)
        val sbScale = findViewById<SeekBar>(R.id.sbFloatingScale)
        val tvScale = findViewById<TextView>(R.id.tvFloatingScaleVal)
        val curScale = SettingsManager.getFloatingScale(this)
        sbScale.progress = curScale
        tvScale.text = "플로팅 버튼 크기: " + curScale + "%"

        sbScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = progress.coerceIn(50, 150)
                tvScale.text = "플로팅 버튼 크기: " + scale + "%"
                if (fromUser) {
                    SettingsManager.setFloatingScale(this@MainActivity, scale)
                    FloatingOverlayManager.refresh(this@MainActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 플로팅 독 투명도(Opacity) 조절 슬라이더 (30% ~ 100%)
        val sbOpacity = findViewById<SeekBar>(R.id.sbFloatingOpacity)
        val tvOpacity = findViewById<TextView>(R.id.tvFloatingOpacityVal)
        val curOpacity = SettingsManager.getFloatingOpacity(this)
        sbOpacity.progress = curOpacity
        tvOpacity.text = "플로팅 투명도 (불투명도): " + curOpacity + "%"

        sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val opacity = progress.coerceIn(30, 100)
                tvOpacity.text = "플로팅 투명도 (불투명도): " + opacity + "%"
                if (fromUser) {
                    SettingsManager.setFloatingOpacity(this@MainActivity, opacity)
                    FloatingOverlayManager.refresh(this@MainActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val sbCollapse = findViewById<SeekBar>(R.id.sbFloatingCollapseDelay)
        val tvCollapse = findViewById<TextView>(R.id.tvFloatingCollapseDelayVal)
        val collapseDelay = SettingsManager.getFloatingCollapseDelaySeconds(this)
        sbCollapse.progress = collapseDelay
        tvCollapse.text = "자동 접기: ${collapseDelay}초"
        sbCollapse.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress.coerceIn(3, 60)
                tvCollapse.text = "자동 접기: ${seconds}초"
                if (fromUser) {
                    SettingsManager.setFloatingCollapseDelaySeconds(this@MainActivity, seconds)
                    FloatingOverlayManager.refresh(this@MainActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupAppDrawerShortcutSwitch(switchId: Int, classSuffix: String) {
        val component = ComponentName(packageName, packageName + classSuffix)
        val sw = findViewById<SwitchCompat>(switchId)
        sw.isChecked = packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        sw.setOnCheckedChangeListener { _, enabled ->
            packageManager.setComponentEnabledSetting(component, if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            Toast.makeText(this, if (enabled) "앱서랍 바로가기를 표시했습니다." else "앱서랍 바로가기를 숨겼습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActionAndFloatingPicker(categoryTitle: String, items: List<FloatingItem>) {
        val names = items.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(categoryTitle)
            .setItems(names) { _, which ->
                val chosen = items[which]
                showDualCreationOptionDialog(chosen)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDualCreationOptionDialog(item: FloatingItem) {
        val options = arrayOf("홈 화면 바로가기 요청", "플로팅 버튼 독에 추가")
        AlertDialog.Builder(this)
            .setTitle(item.title + " 생성 방식 선택")
            .setItems(options) { _, which ->
                if (which == 0) {
                    requestPinnedShortcut(item)
                } else {
                    FloatingItemManager.addItem(this, item)
                    if (AdbPermissionManager.isOverlayGranted(this)) {
                        FloatingOverlayManager.refresh(this)
                    } else {
                        AdbPermissionManager.openOverlaySettings(this)
                    }
                    Toast.makeText(this, item.title + " 플로팅 독에 추가되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun requestPinnedShortcut(item: FloatingItem) {
        val manager = getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            Toast.makeText(this, "현재 BYD 런처는 홈 바로가기 추가를 지원하지 않습니다.", Toast.LENGTH_LONG).show()
            return
        }
        val launchIntent = Intent(this, ShortcutActionActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(ShortcutActionActivity.EXTRA_ID, item.id)
            putExtra(ShortcutActionActivity.EXTRA_TITLE, item.title)
            putExtra(ShortcutActionActivity.EXTRA_PACKAGE, if (item.isApp) item.packageName else "")
            putExtra(
                ShortcutActionActivity.EXTRA_TOKEN,
                ShortcutActionActivity.getOrCreateToken(this@MainActivity)
            )
        }
        val shortcutIcon = if (item.isApp) {
            runCatching {
                Icon.createWithBitmap(packageManager.getApplicationIcon(item.packageName).toBitmap(96, 96))
            }.getOrElse { Icon.createWithResource(this, R.drawable.ic_byd_dolphin) }
        } else {
            val iconRes = when (item.id) {
                FloatingItemManager.ID_DEFROST,
                FloatingItemManager.ID_REAR_DEFROST -> R.drawable.ic_defrost_toggle
                FloatingItemManager.ID_INSIDE_LIGHT -> R.drawable.ic_light_toggle
                FloatingItemManager.ID_LIGHT_ON -> R.drawable.ic_light_on
                FloatingItemManager.ID_LIGHT_OFF -> R.drawable.ic_light_off
                else -> R.drawable.ic_byd_dolphin
            }
            Icon.createWithResource(this, iconRes)
        }
        val shortcut = ShortcutInfo.Builder(this, "dolphin_${item.id.hashCode().toUInt().toString(16)}")
            .setShortLabel(item.title.take(18))
            .setLongLabel(item.title)
            .setIcon(shortcutIcon)
            .setIntent(launchIntent)
            .build()
        val accepted = runCatching { manager.requestPinShortcut(shortcut, null) }.getOrDefault(false)
        Toast.makeText(
            this,
            if (accepted) "런처에 바로가기 추가 요청을 보냈습니다." else "런처가 바로가기 요청을 거부했습니다.",
            Toast.LENGTH_LONG
        ).show()
    }

    // =========================================================================
    // 8. 검증된 차량 API만 허용하는 자동화 시나리오
    // =========================================================================
    private fun setupAutomationSubScreen() {
        refreshCustomScenarioList()

        findViewById<Button>(R.id.btnAddCustomScenario).setOnClickListener {
            showAddScenarioDialog()
        }

        findViewById<Button>(R.id.btnTestGearR).setOnClickListener { sendGearBroadcast("R") }
        findViewById<Button>(R.id.btnTestGearD).setOnClickListener { sendGearBroadcast("D") }
        findViewById<Button>(R.id.btnTestGearP).setOnClickListener { sendGearBroadcast("P") }
    }

    private fun showAddScenarioDialog() {
        val triggers = arrayOf(
            "차량 시동 ON (전원 레벨 2)",
            "차량 시동 OFF (전원 레벨 0)",
            "후진 기어 (R) 체결",
            "주차 기어 (P) 체결"
        )
        val triggerKeys = arrayOf("READY_ON", "READY_OFF", "GEAR_R", "GEAR_P")

        AlertDialog.Builder(this)
            .setTitle("1단계: 발동 트리거 조건 선택")
            .setItems(triggers) { _, trigIdx ->
                val chosenTriggerName = triggers[trigIdx]
                val chosenTriggerKey = triggerKeys[trigIdx]

                val actions = arrayOf(
                    "에어컨 풍량 1단", "에어컨 풍량 3단", "에어컨 풍량 5단", "에어컨 풍량 7단", "에어컨 전원 OFF",
                    "앞·뒤 성에 제거 ON", "뒷유리 열선 ON",
                    "운전석 열선 OFF", "운전석 열선 1단", "운전석 열선 2단",
                    "동승석 열선 OFF", "동승석 열선 1단", "동승석 열선 2단",
                    "핸들 열선 ON", "핸들 열선 OFF",
                    "실내등 전체 켜기", "실내등 전체 끄기",
                    "도어 연동등 켜기", "도어 연동등 끄기"
                )
                val actionTypes = arrayOf(
                    "AC_FAN", "AC_FAN", "AC_FAN", "AC_FAN", "AC_OFF",
                    "DEFROST_ALL", "DEFROST_REAR",
                    "DRIVER_SEAT_HEAT", "DRIVER_SEAT_HEAT", "DRIVER_SEAT_HEAT",
                    "PASSENGER_SEAT_HEAT", "PASSENGER_SEAT_HEAT", "PASSENGER_SEAT_HEAT",
                    "STEERING_HEAT", "STEERING_HEAT",
                    "LIGHT_ON", "LIGHT_OFF", "LIGHT_DOOR", "LIGHT_DOOR"
                )
                val actionVals = arrayOf(
                    "1", "3", "5", "7", "0",
                    "1", "1",
                    "0", "1", "2",
                    "0", "1", "2",
                    "1", "0",
                    "1", "0", "1", "0"
                )

                AlertDialog.Builder(this)
                    .setTitle("2단계: 실행할 차량 액션 선택")
                    .setItems(actions) { _, actIdx ->
                        val chosenActionName = actions[actIdx]
                        val chosenActionType = actionTypes[actIdx]
                        val chosenActionVal = actionVals[actIdx]

                        val inputName = EditText(this).apply {
                            setText(chosenTriggerName.substringAfter(" ") + " 시 " + chosenActionName.substringAfter(" "))
                        }

                        AlertDialog.Builder(this)
                            .setTitle("3단계: 시나리오 이름 확인 및 저장")
                            .setView(inputName)
                            .setPositiveButton("규칙 생성") { _, _ ->
                                val ruleName = inputName.text.toString().trim()
                                val scenario = CustomScenario(
                                    id = System.currentTimeMillis().toString(),
                                    name = if (ruleName.isNotEmpty()) ruleName else "자동화 시나리오",
                                    triggerType = chosenTriggerKey,
                                    triggerName = chosenTriggerName,
                                    actionType = chosenActionType,
                                    actionName = chosenActionName,
                                    actionValue = chosenActionVal,
                                    isEnabled = true
                                )
                                SettingsManager.addCustomScenario(this, scenario)
                                refreshCustomScenarioList()
                                Toast.makeText(this, "자동화 규칙이 등록되었습니다.", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun refreshCustomScenarioList() {
        val container = findViewById<LinearLayout>(R.id.layoutCustomScenarioListContainer)
        container.removeAllViews()
        val list = SettingsManager.getCustomScenarios(this)

        for (item in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 10, 12, 10)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1C1C28"))
                    cornerRadius = 12f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
            }

            val tvInfo = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = item.name + "\n[" + item.triggerName + " → " + item.actionName + "]"
                setTextColor(Color.WHITE)
                textSize = 12f
                setLineSpacing(2f, 1f)
            }
            row.addView(tvInfo)

            val swToggle = SwitchCompat(this).apply {
                isChecked = item.isEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    item.isEnabled = isChecked
                    SettingsManager.saveCustomScenarios(this@MainActivity, list)
                }
            }
            row.addView(swToggle)

            val btnDel = Button(this).apply {
                text = "삭제"
                textSize = 11f
                setTextColor(Color.parseColor("#FF5252"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2C2C38"))
                setOnClickListener {
                    SettingsManager.removeCustomScenario(this@MainActivity, item.id)
                    refreshCustomScenarioList()
                }
            }
            row.addView(btnDel)

            container.addView(row)
        }
    }

    // =========================================================================
    // 9. 시동 1회당 앱별 실행/재생 예약
    // =========================================================================
    private fun setupBootSchedulerSubScreen() {
        val swBoot = findViewById<SwitchCompat>(R.id.swBootAuto)

        swBoot.isChecked = SettingsManager.isBootAutoEnabled(this)

        swBoot.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setBootAutoEnabled(this, isChecked) }

        refreshBootAppList()

        findViewById<Button>(R.id.btnAddBootApp).setOnClickListener {
            showAppPicker("부팅 시 자동 실행할 앱 선택") { pkg, name ->
                showBootDelayDialog(pkg, name)
            }
        }
    }

    private fun showBootDelayDialog(pkg: String, name: String, existing: BootAppItem? = null) {
        val launchDelay = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "예: 3.5"
            setText((existing?.delaySeconds ?: 3.5).toString())
        }
        val mediaEnabled = SwitchCompat(this).apply {
            text = "이 앱 실행 뒤 재생(PLAY) 명령 전송"
            isChecked = existing?.mediaPlayEnabled ?: false
            setTextColor(Color.WHITE)
        }
        val mediaDelay = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "예: 2.0"
            setText((existing?.mediaDelaySeconds ?: 2.0).toString())
            isEnabled = mediaEnabled.isChecked
        }
        mediaEnabled.setOnCheckedChangeListener { _, checked -> mediaDelay.isEnabled = checked }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 8)
            addView(TextView(this@MainActivity).apply {
                text = "시동 ON 뒤 앱 실행 지연(초, 0.1 단위)"
                setTextColor(Color.parseColor("#80D8FF"))
            })
            addView(launchDelay)
            addView(mediaEnabled)
            addView(TextView(this@MainActivity).apply {
                text = "앱 실행 뒤 재생 지연(초)"
                setTextColor(Color.parseColor("#80D8FF"))
            })
            addView(mediaDelay)
        }

        AlertDialog.Builder(this)
            .setTitle("$name 앱별 시동 예약")
            .setMessage("실행과 미디어 재생 시간을 이 앱에만 적용합니다.")
            .setView(form)
            .setPositiveButton(if (existing == null) "등록" else "저장") { _, _ ->
                val launchSec = launchDelay.text.toString().toDoubleOrNull()?.coerceIn(0.0, 600.0) ?: 3.5
                val mediaSec = mediaDelay.text.toString().toDoubleOrNull()?.coerceIn(0.0, 120.0) ?: 2.0
                SettingsManager.addBootApp(
                    this,
                    BootAppItem(
                        packageName = pkg,
                        appName = name,
                        delaySeconds = launchSec,
                        enabled = existing?.enabled ?: true,
                        mediaPlayEnabled = mediaEnabled.isChecked,
                        mediaDelaySeconds = mediaSec
                    )
                )
                refreshBootAppList()
                Toast.makeText(this, "$name 예약 저장 완료", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun refreshBootAppList() {
        val container = findViewById<LinearLayout>(R.id.layoutBootAppListContainer)
        container.removeAllViews()
        val list = SettingsManager.getBootAppList(this)

        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "등록된 앱이 없습니다. 아래 버튼에서 앱을 추가하세요."
                setTextColor(Color.GRAY)
                setPadding(12, 18, 12, 18)
            })
            return
        }

        for (item in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 10, 16, 10)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1C1C28"))
                    cornerRadius = 12f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvInfo = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = buildString {
                    append(item.appName).append("\n시동 +").append(item.delaySeconds).append("초 실행")
                    if (item.mediaPlayEnabled) append(" · 실행 +").append(item.mediaDelaySeconds).append("초 재생")
                }
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            header.addView(tvInfo)

            val enabledSwitch = SwitchCompat(this).apply {
                isChecked = item.enabled
                contentDescription = "${item.appName} 자동 실행 사용"
                setOnCheckedChangeListener { _, checked ->
                    SettingsManager.addBootApp(this@MainActivity, item.copy(enabled = checked))
                }
            }
            header.addView(enabledSwitch)
            row.addView(header)

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val btnEdit = Button(this).apply {
                text = "편집"
                textSize = 11f
                setTextColor(Color.parseColor("#80D8FF"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2C2C38"))
                setOnClickListener { showBootDelayDialog(item.packageName, item.appName, item) }
            }
            actions.addView(btnEdit)

            val btnDel = Button(this).apply {
                text = "삭제"
                textSize = 11f
                setTextColor(Color.parseColor("#FF5252"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2C2C38"))
                setOnClickListener {
                    SettingsManager.removeBootApp(this@MainActivity, item.packageName)
                    refreshBootAppList()
                }
            }
            actions.addView(btnDel)
            row.addView(actions)

            container.addView(row)
        }
    }

    // =========================================================================
    // 10. 화면 밀도(DPI) & 원터치 진단 센터
    // =========================================================================
    private fun setupDpiAdbSubScreen() {
        val tvDpiBig = findViewById<TextView>(R.id.tvSubCurrentDpiBig)
        fun refreshDpi() {
            tvDpiBig.text = DpiManager.getCurrentDensity(this)
        }
        refreshDpi()

        fun applyDpi(value: Int?) {
            Toast.makeText(this, "로컬 ADB로 DPI 적용 중…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = if (value == null) DpiManager.resetDensity(this@MainActivity)
                else DpiManager.setDensity(this@MainActivity, value)
                withContext(Dispatchers.Main) {
                    refreshDpi()
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) (value?.let { "$it DPI 적용 완료" } ?: "순정 DPI 복원 완료") else "DPI 적용 실패 — 로컬 ADB 로그를 확인하세요.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        findViewById<Button>(R.id.btnPreset160).setOnClickListener { applyDpi(160) }
        findViewById<Button>(R.id.btnPreset180).setOnClickListener { applyDpi(180) }
        findViewById<Button>(R.id.btnPreset200).setOnClickListener { applyDpi(200) }
        findViewById<Button>(R.id.btnPresetReset).setOnClickListener { applyDpi(null) }

        findViewById<Button>(R.id.btnWirelessAdbGuide).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("로컬 ADB(5555) 연결 안내")
                .setMessage("원터치 진단 수집에는 스마트폰 버그제거 앱이나 ADB가 필요하지 않습니다. 아래 과정은 DPI·특수 권한처럼 ADB 전용 기능을 처음 준비할 때만 필요합니다.\n\n" +
                        "이 앱은 이미 열려 있는 차량의 5555 포트에만 접속할 수 있으며 포트 자체를 우회해 열 수는 없습니다.\n\n" +
                        "1. 스마트폰 버그제거 앱으로 차량 ADB에 연결합니다.\n" +
                        "2. 셸에서 adb tcpip 5555를 1회 실행합니다.\n" +
                        "3. 차량에 RSA 허용 창이 나오면 이 컴퓨터에서 항상 허용을 선택합니다.\n" +
                        "4. 이 화면의 ADB 승인 시도를 누릅니다.\n\n" +
                        "창이 나오지 않거나 실패하면 원터치 진단 ZIP을 생성해 보내주세요.")
                .setPositiveButton("확인", null)
                .show()
        }

        findViewById<Button>(R.id.btnAutoGrantAdb).setOnClickListener {
            Toast.makeText(this, "로컬 ADB 연결 시도 중... 화면 팝업을 확인하세요", Toast.LENGTH_SHORT).show()
            performAutoAdbGrant()
        }

        findViewById<Button>(R.id.btnCopyAllAdbCmds).setOnClickListener {
            val cmds = """
adb shell pm grant com.byd.dolphin.autoassistant android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.byd.dolphin.autoassistant SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener com.byd.dolphin.autoassistant/.hud.MultiNavNotificationListener
            """.trimIndent()

            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ADB_COMMANDS", cmds))

            AlertDialog.Builder(this)
                .setTitle("ADB 권한 명령어 복사 완료")
                .setMessage("이 명령은 초기 ADB 권한 준비용이며 일반 진단 수집에는 필요하지 않습니다. ADB 셸에서 한 줄씩 실행하세요:\n\n$cmds\n\nBYD 서명 권한은 일반 pm grant로 우회할 수 없으며, 진단 ZIP에서 실제 승인 여부를 확인합니다.")
                .setPositiveButton("확인", null)
                .show()
        }

        val tvCaptureStatus = findViewById<TextView>(R.id.tvDiagnosticCaptureStatus)
        val swRawNavText = findViewById<SwitchCompat>(R.id.swDiagnosticIncludeNavText)
        val btnStartCapture = findViewById<Button>(R.id.btnStartDiagnosticCapture)
        val btnProblemMarker = findViewById<Button>(R.id.btnDiagnosticProblemMarker)
        val btnStopAndExport = findViewById<Button>(R.id.btnStopExportDiagnosticBundle)

        fun refreshCaptureStatus() {
            val status = DiagnosticCaptureManager.getStatus()
            when {
                status.active -> {
                    val elapsedMinutes = status.elapsedSeconds / 60
                    val elapsedSeconds = status.elapsedSeconds % 60
                    val remainMinutes = status.remainingSeconds / 60
                    val remainSeconds = status.remainingSeconds % 60
                    tvCaptureStatus.text = String.format(
                        Locale.KOREA,
                        "● 수집 중 %02d:%02d · 남은 시간 %02d:%02d · 내비 원문 %s",
                        elapsedMinutes,
                        elapsedSeconds,
                        remainMinutes,
                        remainSeconds,
                        if (status.includeRawNavText) "포함" else "숨김"
                    )
                    tvCaptureStatus.setTextColor(Color.parseColor("#00E676"))
                }
                status.readyToExport -> {
                    tvCaptureStatus.text = "● 수집 완료 · 아래 버튼으로 진단 ZIP을 내보내세요."
                    tvCaptureStatus.setTextColor(Color.parseColor("#FFD54F"))
                }
                else -> {
                    tvCaptureStatus.text = "● 대기 중 · 시작 후 문제 상황을 재현하세요."
                    tvCaptureStatus.setTextColor(Color.parseColor("#80D8FF"))
                }
            }
            swRawNavText.isEnabled = !status.exists
            btnStartCapture.isEnabled = !status.exists
            btnProblemMarker.isEnabled = status.active
            btnStopAndExport.isEnabled = status.exists
            btnStopAndExport.text = if (status.active) {
                "■ 수집 종료 + ZIP 내보내기"
            } else {
                "📦 완료된 진단 ZIP 내보내기"
            }
        }

        swRawNavText.isChecked = SettingsManager.isDiagnosticNavTextEnabled(this)
        swRawNavText.setOnCheckedChangeListener { _, enabled ->
            SettingsManager.setDiagnosticNavTextEnabled(this, enabled)
            Toast.makeText(
                this,
                if (enabled) "이 진단 세션에는 내비 알림 문구가 포함됩니다." else "내비 알림 원문을 숨깁니다.",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnStartCapture.setOnClickListener {
            if (!ensureBluetoothPermission()) return@setOnClickListener
            val message = DiagnosticCaptureManager.start(this)
            refreshCaptureStatus()
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        btnProblemMarker.setOnClickListener {
            val recorded = DiagnosticCaptureManager.addProblemMarker()
            Toast.makeText(
                this,
                if (recorded) "문제 발생 시점을 기록했습니다." else "먼저 진단 수집을 시작하세요.",
                Toast.LENGTH_SHORT
            ).show()
            refreshCaptureStatus()
        }

        btnStopAndExport.setOnClickListener {
            btnStopAndExport.isEnabled = false
            tvCaptureStatus.text = "진단 파일 정리 및 ZIP 생성 중…"
            Toast.makeText(this, "진단 ZIP을 만드는 중입니다.", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val file = DiagnosticCaptureManager.stopAndCreateBundle(this@MainActivity)
                if (file != null) {
                    findViewById<TextView>(R.id.tvLogPathInfo).apply {
                        text = "진단 ZIP 저장 완료: ${file.absolutePath} (${file.length()} Bytes)"
                        setTextColor(Color.parseColor("#00E676"))
                    }
                    shareDiagnosticFile(file, "application/zip", "원터치 진단 ZIP 공유")
                } else {
                    Toast.makeText(this@MainActivity, "내보낼 진단 세션이 없습니다.", Toast.LENGTH_LONG).show()
                }
                refreshCaptureStatus()
            }
        }

        refreshCaptureStatus()
        lifecycleScope.launch {
            while (true) {
                delay(1_000L)
                refreshCaptureStatus()
            }
        }

        findViewById<Button>(R.id.btnGenerateDiagnosticLog).setOnClickListener {
            val file = DolphinLogger.exportDiagnosticReport(this)
            val tvPath = findViewById<TextView>(R.id.tvLogPathInfo)
            if (file.exists()) {
                tvPath.text = "저장 완료: " + file.absolutePath + " (" + file.length() + " Bytes)"
                tvPath.setTextColor(Color.parseColor("#00E676"))
                shareDiagnosticFile(file, "text/plain", "통합 진단 로그 공유")
            }
        }
    }

    private fun sendGearBroadcast(gear: String) {
        val intent = Intent(DolphinService.ACTION_INTERNAL_TEST_GEAR).apply {
            setPackage(this@MainActivity.packageName)
            putExtra(DolphinService.EXTRA_TEST_GEAR, gear)
        }
        sendBroadcast(intent)
        Toast.makeText(this, gear + "단 가상 신호 발생", Toast.LENGTH_SHORT).show()
    }

    private fun showAppPicker(title: String, onSelected: (String, String) -> Unit) {
        val apps = AppRoutingManager.getInstalledApps(this)
        val names = apps.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, which ->
                val chosen = apps[which]
                onSelected(chosen.packageName, chosen.name)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun shareDiagnosticFile(file: File, mimeType: String, chooserTitle: String) {
        val uri = try {
            FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        } catch (e: Exception) {
            DolphinLogger.e("LOG_SHARE", "FileProvider URI 생성 실패", e)
            Toast.makeText(this, "로그 공유 URI를 만들지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
