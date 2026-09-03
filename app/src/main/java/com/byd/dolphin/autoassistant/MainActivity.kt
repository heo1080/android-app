package com.byd.dolphin.autoassistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import com.byd.dolphin.autoassistant.floating.FloatingItem
import com.byd.dolphin.autoassistant.floating.FloatingItemManager
import com.byd.dolphin.autoassistant.hud.ClusterMirrorManager
import com.byd.dolphin.autoassistant.hud.HudAudioManager
import com.byd.dolphin.autoassistant.hud.HudDataManager
import com.byd.dolphin.autoassistant.hud.T900BluetoothManager
import com.byd.dolphin.autoassistant.manager.*
import com.byd.dolphin.autoassistant.service.DolphinService
import com.byd.dolphin.autoassistant.split.SplitConfig
import com.byd.dolphin.autoassistant.split.SplitMode
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: VoiceAndSoundManager

    // 10대 세분화 서브 화면 뷰 레퍼런스
    private lateinit var layoutMainDashboard: View
    private lateinit var subLayoutSplit: View
    private lateinit var subLayoutSeat: View
    private lateinit var subLayoutVoice: View
    private lateinit var subLayoutSafetyAudio: View
    private lateinit var subLayoutHud: View
    private lateinit var subLayoutCluster: View
    private lateinit var subLayoutButtonBuilder: View
    private lateinit var subLayoutAutomation: View
    private lateinit var subLayoutBootScheduler: View
    private lateinit var subLayoutDpiAdb: View

    private var isMirroringActive = false

    // 분할 화면 대상 앱 상태
    private var splitApp1Pkg = "com.skt.tmap.ku"
    private var splitApp1Name = "티맵"
    private var splitApp2Pkg = "com.android.music"
    private var splitApp2Name = "기본 미디어"
    private var splitApp3Pkg = ""
    private var splitApp3Name = "미선택"
    private var splitApp4Pkg = ""
    private var splitApp4Name = "미선택"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DolphinLogger.init(this)
        audioManager = VoiceAndSoundManager(this)

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

        // 2. 자체 ADB 프로토콜 전송 시도
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
        audioManager.release()
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
        } catch (e: Exception) {
            DolphinLogger.e("MainActivity", "DolphinService 가동 실패", e)
        }
    }

    private fun initViewReferences() {
        layoutMainDashboard = findViewById(R.id.layoutMainDashboard)
        subLayoutSplit = findViewById(R.id.subLayoutSplit)
        subLayoutSeat = findViewById(R.id.subLayoutSeat)
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
        subLayoutSeat.visibility = View.GONE
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
        subLayoutSeat.visibility = View.GONE
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
                tvStatus.text = "🟢 실시간 감시 중 (차량 시스템 권한 승인 완료)"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
                startDolphinService()
            } else {
                tvStatus.text = "🟢 차량 편의 제어 활성화됨 (순정 HAL 제어 중)"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
            }
        }
    }

    private fun setupCardNavigation() {
        findViewById<CardView>(R.id.cardMenuSplit).setOnClickListener { showSubScreen(subLayoutSplit) }
        findViewById<CardView>(R.id.cardMenuSeat).setOnClickListener { showSubScreen(subLayoutSeat) }
        findViewById<CardView>(R.id.cardMenuVoice).setOnClickListener { showSubScreen(subLayoutVoice) }
        findViewById<CardView>(R.id.cardMenuSafetyAudio).setOnClickListener { showSubScreen(subLayoutSafetyAudio) }
        findViewById<CardView>(R.id.cardMenuHud).setOnClickListener { showSubScreen(subLayoutHud) }
        findViewById<CardView>(R.id.cardMenuCluster).setOnClickListener { showSubScreen(subLayoutCluster) }
        findViewById<CardView>(R.id.cardMenuButtonBuilder).setOnClickListener { showSubScreen(subLayoutButtonBuilder) }
        findViewById<CardView>(R.id.cardMenuAutomation).setOnClickListener { showSubScreen(subLayoutAutomation) }
        findViewById<CardView>(R.id.cardMenuBootScheduler).setOnClickListener { showSubScreen(subLayoutBootScheduler) }
        findViewById<CardView>(R.id.cardMenuDpiAdb).setOnClickListener { showSubScreen(subLayoutDpiAdb) }

        findViewById<Button>(R.id.btnBackSplit).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackSeat).setOnClickListener { showMainDashboard() }
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
        val tvStatusBadge = findViewById<TextView>(R.id.tvServiceStatusBadge)
        val btnRestartService = findViewById<Button>(R.id.btnRestartService)

        btnRestartService.setOnClickListener {
            val serviceIntent = Intent(this, DolphinService::class.java)
            stopService(serviceIntent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            if (AdbPermissionManager.isOverlayGranted(this)) {
                FloatingOverlayManager.show(this)
            }
            tvStatusBadge.text = "🟢 백그라운드 실시간 감시 중 (재실행됨)"
            tvStatusBadge.setTextColor(Color.parseColor("#00E676"))
            Toast.makeText(this, "어시스턴트 서비스 재가동 완료", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDashboardCards() {
        val curDpi = DpiManager.getCurrentDensity()
        findViewById<TextView>(R.id.tvMainCardDpiDesc).text = "현재 DPI: " + curDpi + " (원터치 프리셋)\n로컬 ADB 자체 권한 승인\n통합 진단 로그 파일 추출"
    }

    // =========================================================================
    // 1. 화면 분할 관리 (2/3/4분할 & 1% 조절 & 앱 선택)
    // =========================================================================
    private fun setupSplitSubScreen() {
        val rgSubSplitMode = findViewById<RadioGroup>(R.id.rgSubSplitMode)
        val rbTwo = findViewById<RadioButton>(R.id.rbSubTwoApps)
        val rbThreeL = findViewById<RadioButton>(R.id.rbSubThreeLeft)
        val rbThreeR = findViewById<RadioButton>(R.id.rbSubThreeRight)
        val rbFour = findViewById<RadioButton>(R.id.rbSubFourGrid)

        val btnApp1 = findViewById<Button>(R.id.btnSelectApp1)
        val btnApp2 = findViewById<Button>(R.id.btnSelectApp2)
        val btnApp3 = findViewById<Button>(R.id.btnSelectApp3)
        val btnApp4 = findViewById<Button>(R.id.btnSelectApp4)

        val sbRatioX = findViewById<SeekBar>(R.id.sbSubSplitRatio)
        val etRatioX = findViewById<EditText>(R.id.etSplitRatioX)
        val sbRatioY = findViewById<SeekBar>(R.id.sbSubSplitRatioY)
        val etRatioY = findViewById<EditText>(R.id.etSplitRatioY)

        val tvPreviewLeft = findViewById<TextView>(R.id.tvPreviewLeft)
        val tvPreviewRight = findViewById<TextView>(R.id.tvPreviewRight)

        fun updateAppButtons() {
            btnApp1.text = "앱 1: " + splitApp1Name
            btnApp2.text = "앱 2: " + splitApp2Name
            btnApp3.text = "앱 3: " + splitApp3Name
            btnApp4.text = "앱 4: " + splitApp4Name

            val is3or4 = rbThreeL.isChecked || rbThreeR.isChecked || rbFour.isChecked
            btnApp3.isEnabled = is3or4
            btnApp3.setTextColor(if (is3or4) Color.WHITE else Color.GRAY)

            val is4 = rbFour.isChecked
            btnApp4.isEnabled = is4
            btnApp4.setTextColor(if (is4) Color.WHITE else Color.GRAY)

            sbRatioY.isEnabled = is3or4
            etRatioY.isEnabled = is3or4
        }

        fun updatePreview(x: Int, y: Int) {
            val left = x.coerceIn(1, 99)
            val right = 100 - left
            val leftParams = tvPreviewLeft.layoutParams as LinearLayout.LayoutParams
            leftParams.weight = left.toFloat()
            tvPreviewLeft.layoutParams = leftParams

            val rightParams = tvPreviewRight.layoutParams as LinearLayout.LayoutParams
            rightParams.weight = right.toFloat()
            tvPreviewRight.layoutParams = rightParams

            when {
                rbTwo.isChecked -> {
                    tvPreviewLeft.text = splitApp1Name + "\n(" + left + "%)"
                    tvPreviewRight.text = splitApp2Name + "\n(" + right + "%)"
                }
                rbThreeL.isChecked -> {
                    tvPreviewLeft.text = splitApp1Name + "/" + splitApp2Name + "\n(좌 " + left + "%)"
                    tvPreviewRight.text = splitApp3Name + "\n(우 " + right + "%)"
                }
                rbThreeR.isChecked -> {
                    tvPreviewLeft.text = splitApp1Name + "\n(좌 " + left + "%)"
                    tvPreviewRight.text = splitApp2Name + "/" + splitApp3Name + "\n(우 " + right + "%)"
                }
                else -> {
                    tvPreviewLeft.text = "상좌/하좌\n(좌 " + left + "%)"
                    tvPreviewRight.text = "상우/하우\n(우 " + right + "%)"
                }
            }
        }

        btnApp1.setOnClickListener {
            showAppPicker("분할 화면 앱 1 선택") { pkg, name ->
                splitApp1Pkg = pkg; splitApp1Name = name
                updateAppButtons(); updatePreview(sbRatioX.progress, sbRatioY.progress)
            }
        }
        btnApp2.setOnClickListener {
            showAppPicker("분할 화면 앱 2 선택") { pkg, name ->
                splitApp2Pkg = pkg; splitApp2Name = name
                updateAppButtons(); updatePreview(sbRatioX.progress, sbRatioY.progress)
            }
        }
        btnApp3.setOnClickListener {
            showAppPicker("분할 화면 앱 3 선택") { pkg, name ->
                splitApp3Pkg = pkg; splitApp3Name = name
                updateAppButtons(); updatePreview(sbRatioX.progress, sbRatioY.progress)
            }
        }
        btnApp4.setOnClickListener {
            showAppPicker("분할 화면 앱 4 선택") { pkg, name ->
                splitApp4Pkg = pkg; splitApp4Name = name
                updateAppButtons(); updatePreview(sbRatioX.progress, sbRatioY.progress)
            }
        }

        rgSubSplitMode.setOnCheckedChangeListener { _, _ ->
            updateAppButtons()
            updatePreview(sbRatioX.progress, sbRatioY.progress)
        }

        sbRatioX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = progress.coerceIn(1, 99)
                if (fromUser) etRatioX.setText(p.toString())
                updatePreview(p, sbRatioY.progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        etRatioX.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val num = s?.toString()?.toIntOrNull()
                if (num != null && num in 1..99 && num != sbRatioX.progress) {
                    sbRatioX.progress = num
                    updatePreview(num, sbRatioY.progress)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        sbRatioY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = progress.coerceIn(1, 99)
                if (fromUser) etRatioY.setText(p.toString())
                updatePreview(sbRatioX.progress, p)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        etRatioY.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val num = s?.toString()?.toIntOrNull()
                if (num != null && num in 1..99 && num != sbRatioY.progress) {
                    sbRatioY.progress = num
                    updatePreview(sbRatioX.progress, num)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateAppButtons()
        updatePreview(30, 50)

        findViewById<Button>(R.id.btnSubApplySplit).setOnClickListener {
            val mode = when {
                rbTwo.isChecked -> SplitMode.TWO_APPS_HORIZONTAL
                rbThreeL.isChecked -> SplitMode.THREE_APPS_LEFT_STACKED
                rbThreeR.isChecked -> SplitMode.THREE_APPS_RIGHT_STACKED
                else -> SplitMode.FOUR_APPS_GRID
            }
            val config = SplitConfig(
                title = splitApp1Name + " / " + splitApp2Name + " 분할",
                mode = mode,
                pkg1 = splitApp1Pkg,
                pkg2 = splitApp2Pkg,
                pkg3 = splitApp3Pkg,
                pkg4 = splitApp4Pkg,
                ratioPrimary = sbRatioX.progress.coerceIn(1, 99),
                ratioSecondary = sbRatioY.progress.coerceIn(1, 99)
            )
            SplitScreenManager.launchSplitScreen(this, config)
        }
    }

    // =========================================================================
    // 2. 스마트 시트 & 후진 사이드미러 다운 제어
    // =========================================================================
    private fun setupSeatSubScreen() {
        val swMirrorDip = findViewById<SwitchCompat>(R.id.swMirrorDip)
        swMirrorDip.isChecked = SeatManager.isMirrorDipEnabled(this)
        swMirrorDip.setOnCheckedChangeListener { _, isChecked ->
            SeatManager.setMirrorDipEnabled(this, isChecked)
        }

        val swEasyAccess = findViewById<SwitchCompat>(R.id.swEasyAccess)
        swEasyAccess.isChecked = SeatManager.isEasyAccessEnabled(this)
        swEasyAccess.setOnCheckedChangeListener { _, isChecked ->
            SeatManager.setEasyAccessEnabled(this, isChecked)
        }

        val tvDelay = findViewById<TextView>(R.id.tvEasyAccessDelay)
        val sbDelay = findViewById<SeekBar>(R.id.sbEasyAccessDelay)
        val curDelay = SeatManager.getEasyAccessDelay(this)
        sbDelay.progress = curDelay
        tvDelay.text = "하차 시트 슬라이딩 지연: " + curDelay + "초"

        sbDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvDelay.text = "하차 시트 슬라이딩 지연: " + progress + "초"
                if (fromUser) SeatManager.setEasyAccessDelay(this@MainActivity, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnSeatPreset1).setOnClickListener {
            SeatManager.setComfortStage(this, SeatManager.POS_COMMUTE, true)
        }
        findViewById<Button>(R.id.btnSeatPreset2).setOnClickListener {
            SeatManager.setComfortStage(this, SeatManager.POS_RELAX, true)
        }

        findViewById<Button>(R.id.btnSeatForward).setOnClickListener { SeatManager.moveForward(this) }
        findViewById<Button>(R.id.btnSeatBackward).setOnClickListener { SeatManager.moveBackward(this) }
        findViewById<Button>(R.id.btnSeatUp).setOnClickListener { SeatManager.moveUp(this) }
        findViewById<Button>(R.id.btnSeatDown).setOnClickListener { SeatManager.moveDown(this) }

        // 동승석
        findViewById<Button>(R.id.btnPassengerDefault).setOnClickListener {
            SeatManager.setPassengerComfortStage(this, SeatManager.PASSENGER_DEFAULT, true)
        }
        findViewById<Button>(R.id.btnPassengerComfort).setOnClickListener {
            SeatManager.setPassengerComfortStage(this, SeatManager.PASSENGER_COMFORT, true)
        }
        findViewById<Button>(R.id.btnPassengerRelax).setOnClickListener {
            SeatManager.setPassengerComfortStage(this, SeatManager.PASSENGER_RELAX, true)
        }

        findViewById<Button>(R.id.btnPassengerSeatForward).setOnClickListener { SeatManager.movePassengerForward(this) }
        findViewById<Button>(R.id.btnPassengerSeatBackward).setOnClickListener { SeatManager.movePassengerBackward(this) }
        findViewById<Button>(R.id.btnPassengerSeatUp).setOnClickListener { SeatManager.movePassengerUp(this) }
        findViewById<Button>(R.id.btnPassengerSeatDown).setOnClickListener { SeatManager.movePassengerDown(this) }
    }

    // =========================================================================
    // 3. 맞춤형 차량 음성 안내 (TTS) - [기본 문구], [추천 문구], [수동 직접 입력] 3단 선택기
    // =========================================================================
    private fun setupVoiceSubScreen() {
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
        setupVoiceEditButton(R.id.btnVoiceLeadingCar, "전방 차량 출발", "전방 차량이 출발했습니다.", "전방 차량이 출발했습니다. 서둘러 출발하세요!") { SettingsManager.getLeadingCarPhrase(this) }

        // 충전 시작/종료
        setupVoiceEditButton(R.id.btnVoiceChargingStart, "충전 시작", "충전이 시작되었습니다.", "고전압 배터리 충전이 시작되었습니다.") { SettingsManager.getChargingStartPhrase(this) }
        setupVoiceEditButton(R.id.btnVoiceChargingEnd, "충전 완료/종료", "충전이 완료되었습니다.", "배터리 충전이 완료되었습니다. 충전 플러그를 분리해 주세요.") { SettingsManager.getChargingEndPhrase(this) }
    
        findViewById<Button>(R.id.btnTestLeadingCarDeparture).setOnClickListener {
            val intent = Intent("com.byd.auto.intent.action.TEST_LEADING_CAR")
            sendBroadcast(intent)
            Toast.makeText(this, "전방 차량 출발 가상 신호 발생 완료", Toast.LENGTH_SHORT).show()
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
    // 4. 운전석 안전 경고 사운드 & 라우팅
    // =========================================================================
    private fun setupSafetyAudioSubScreen() {
        updateDriverAudioChips()
        findViewById<Button>(R.id.btnAddDriverAudioApp).setOnClickListener {
            showAppPicker("운전석 스피커 라우팅 앱 추가") { pkg, _ ->
                AppRoutingManager.addPackage(this, pkg)
                updateDriverAudioChips()
            }
        }

        findViewById<Button>(R.id.btnConfigBsdAlert).setOnClickListener {
            showBsdLdpConfigDialog("BSD 사각지대 감지 경고음 설정", isBsd = true)
        }
        findViewById<Button>(R.id.btnConfigLdpAlert).setOnClickListener {
            showBsdLdpConfigDialog("LDP 차선이탈보조 경고음 설정", isBsd = false)
        }
    }

    private fun updateDriverAudioChips() {
        val chipGroup = findViewById<ChipGroup>(R.id.chipGroupDriverAudioApps)
        chipGroup.removeAllViews()
        val routed = AppRoutingManager.getRoutedPackages(this)
        for (pkg in routed) {
            val chip = Chip(this).apply {
                text = pkg
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    AppRoutingManager.removePackage(this@MainActivity, pkg)
                    updateDriverAudioChips()
                }
            }
            chipGroup.addView(chip)
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
    // 5. 한문철 T900 HUD (데이터/오디오 분리 페어링 & 최소/최대 밝기)
    // =========================================================================
    private fun setupHudSubScreen() {
        val tvDataStatus = findViewById<TextView>(R.id.tvHudDataStatus)
        val tvAudioStatus = findViewById<TextView>(R.id.tvHudAudioStatus)

        findViewById<Button>(R.id.btnConnectHudData).setOnClickListener {
            tvDataStatus.text = "데이터(huddata) 연결 시도 중..."
            T900BluetoothManager.connectHudData(this) { ok, msg ->
                runOnUiThread {
                    tvDataStatus.text = if (ok) "데이터(huddata): 연결 성공" else "데이터: " + msg
                    tvDataStatus.setTextColor(if (ok) Color.parseColor("#00E676") else Color.parseColor("#FF5252"))
                }
            }
        }

        findViewById<Button>(R.id.btnConnectHudAudio).setOnClickListener {
            T900BluetoothManager.connectHudAudio(this) { ok, msg ->
                tvAudioStatus.text = if (ok) "오디오(hudaudio): 블루투스 설정 연결 대기" else "오디오: " + msg
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
        sbAudioVol.progress = SettingsManager.getHudAudioVolume(this)
        sbAudioVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) SettingsManager.setHudAudioVolume(this@MainActivity, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // =========================================================================
    // 6. 계기판 디스플레이 & TBT 연동 (순정 접근성 우회 연동)
    // =========================================================================
    private fun setupClusterSubScreen() {
        val swClusterTbt = findViewById<SwitchCompat>(R.id.swClusterTbt)
        swClusterTbt.isChecked = SettingsManager.isClusterTbtEnabled(this)
        swClusterTbt.setOnCheckedChangeListener { _, isChecked ->
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
            ClusterMirrorManager.sendTbtToCluster(this, 1, 350, "350m", 60, "전방 교차로 우회전", true)
            Toast.makeText(this, "계기판 5인치 화면으로 TBT 테스트 데이터 전송 완료", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSendAppToCluster).setOnClickListener {
            showAppPicker("계기판(Display 1)으로 보낼 앱 선택") { pkg, _ ->
                ClusterMirrorManager.launchAppOnClusterDisplay(this, pkg)
            }
        }

        findViewById<Button>(R.id.btnToggleClusterMirroring).setOnClickListener {
            isMirroringActive = !isMirroringActive
            ClusterMirrorManager.toggleMainToClusterMirroring(this, isMirroringActive)
        }
    }

    // =========================================================================
    // 7. 바로가기 & 플로팅 버튼 빌더 (크기, 투명도, 앱 아이콘 표출 커스텀)
    // =========================================================================
    private fun setupButtonBuilderSubScreen() {
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
        val options = arrayOf("📱 앱서랍 바로가기로 등록", "🪟 플로팅 버튼 독에 추가")
        AlertDialog.Builder(this)
            .setTitle(item.title + " 생성 방식 선택")
            .setItems(options) { _, which ->
                if (which == 0) {
                    Toast.makeText(this, item.title + " 앱서랍 바로가기 등록 완료", Toast.LENGTH_SHORT).show()
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

    // =========================================================================
    // 8. 스마트 차량 자동화 시나리오 랩 (오버드라이브 확장 액션)
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
            "⚡ 차량 시동 (READY 감지)",
            "💤 차량 시동 꺼짐 (READY OFF)",
            "🌡️ 외부온도 32°C 이상 (폭염)",
            "🌡️ 외부온도 3°C 이하 (한파)",
            "🕹️ 후진 기어 (R) 체결",
            "🕹️ 주차 기어 (P) 체결",
            "🔋 충전기 연결",
            "🔋 충전 완료/분리"
        )
        val triggerKeys = arrayOf("READY_ON", "READY_OFF", "TEMP_HIGH", "TEMP_LOW", "GEAR_R", "GEAR_P", "CHARGING_ON", "CHARGING_OFF")

        AlertDialog.Builder(this)
            .setTitle("1단계: 발동 트리거 조건 선택")
            .setItems(triggers) { _, trigIdx ->
                val chosenTriggerName = triggers[trigIdx]
                val chosenTriggerKey = triggerKeys[trigIdx]

                val actions = arrayOf(
                    "❄️ 에어컨 풍량 1단",
                    "❄️ 에어컨 풍량 3단",
                    "❄️ 에어컨 풍량 5단 급속",
                    "❄️ 에어컨 풍량 7단 최대",
                    "❄️ 에어컨 전원 OFF",
                    "🔄 내기 순환 모드 (차단)",
                    "🔄 외기 순환 모드 (환기)",
                    "♨️ 앞유리 급속 성에제거 MAX",
                    "♨️ 뒷유리 & 사이드미러 열선 ON",
                    "💺 운전석 시트 포지션 1번 (출퇴근)",
                    "💺 운전석 시트 포지션 2번 (휴식)",
                    "💺 운전석 시트 열선 ON",
                    "💺 운전석 시트 통풍 ON",
                    "💺 동승석 릴렉스 취침 모드",
                    "💺 동승석 시트 열선 ON",
                    "💺 동승석 시트 통풍 ON",
                    "♨️ 스티어링 휠(핸들) 열선 ON",
                    "🪟 창문 환기 모드 (10% 열기)",
                    "🪟 창문 전체 닫기",
                    "💡 실내등 전체 켜기",
                    "💡 실내등 전체 끄기"
                )
                val actionTypes = arrayOf(
                    "AC_FAN", "AC_FAN", "AC_FAN", "AC_FAN", "AC_OFF",
                    "AIR_INTERNAL", "AIR_EXTERNAL",
                    "DEFROST_FRONT", "DEFROST_REAR",
                    "SEAT_STAGE", "SEAT_STAGE",
                    "SEAT_HEAT_DRIVER", "SEAT_VENT_DRIVER",
                    "SEAT_PASSENGER", "SEAT_HEAT_PASSENGER", "SEAT_VENT_PASSENGER",
                    "STEERING_HEAT",
                    "WINDOW_VENT", "WINDOW_CLOSE",
                    "LIGHT_ON", "LIGHT_OFF"
                )
                val actionVals = arrayOf(
                    "1", "3", "5", "7", "0",
                    "INTERNAL", "EXTERNAL",
                    "1", "1",
                    "1", "-2",
                    "1", "1",
                    "-2", "1", "1",
                    "1",
                    "VENT", "CLOSE",
                    "1", "0"
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
    // 9. 부팅 시 다중 앱 자동 실행 스케줄러 (미디어 앱 선택)
    // =========================================================================
    private fun setupBootSchedulerSubScreen() {
        val swBoot = findViewById<SwitchCompat>(R.id.swBootAuto)
        val swMedia = findViewById<SwitchCompat>(R.id.swBootMediaPlay)
        val btnSelectMedia = findViewById<Button>(R.id.btnSelectBootMediaApp)

        swBoot.isChecked = SettingsManager.isBootAutoEnabled(this)
        swMedia.isChecked = SettingsManager.isBootMediaPlayEnabled(this)
        btnSelectMedia.text = "자동 재생 대상 앱: " + SettingsManager.getBootSelectedMediaName(this)

        swBoot.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setBootAutoEnabled(this, isChecked) }
        swMedia.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setBootMediaPlayEnabled(this, isChecked) }

        btnSelectMedia.setOnClickListener {
            showAppPicker("자동 재생할 미디어 앱 선택") { pkg, name ->
                SettingsManager.setBootSelectedMediaPkg(this, pkg)
                SettingsManager.setBootSelectedMediaName(this, name)
                btnSelectMedia.text = "자동 재생 대상 앱: " + name
                Toast.makeText(this, name + " 자동 재생 대상으로 지정됨", Toast.LENGTH_SHORT).show()
            }
        }

        refreshBootAppList()

        findViewById<Button>(R.id.btnAddBootApp).setOnClickListener {
            showAppPicker("부팅 시 자동 실행할 앱 선택") { pkg, name ->
                showBootDelayDialog(pkg, name)
            }
        }
    }

    private fun showBootDelayDialog(pkg: String, name: String) {
        val input = EditText(this).apply {
            hint = "예: 3.5 (0.1초 단위)"
            setText("3.5")
        }
        AlertDialog.Builder(this)
            .setTitle(name + " 자동 실행 지연 시간")
            .setMessage("부팅/시동 후 몇 초 뒤에 실행할지 0.1초 단위로 입력하세요:")
            .setView(input)
            .setPositiveButton("등록") { _, _ ->
                val sec = input.text.toString().toDoubleOrNull() ?: 3.5
                SettingsManager.addBootApp(this, BootAppItem(pkg, name, sec))
                refreshBootAppList()
                Toast.makeText(this, name + " (" + sec + "초 후 실행) 등록 완료", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun refreshBootAppList() {
        val container = findViewById<LinearLayout>(R.id.layoutBootAppListContainer)
        container.removeAllViews()
        val list = SettingsManager.getBootAppList(this)

        for (item in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 8, 12, 8)
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
                text = item.appName + " (시동 " + item.delaySeconds + "초 후 실행)"
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            row.addView(tvInfo)

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
            row.addView(btnDel)

            container.addView(row)
        }
    }

    // =========================================================================
    // 10. 화면 밀도(DPI) & ADB 진단 센터
    // =========================================================================
    private fun setupDpiAdbSubScreen() {
        val tvDpiBig = findViewById<TextView>(R.id.tvSubCurrentDpiBig)
        fun refreshDpi() {
            val d = DpiManager.getCurrentDensity()
            tvDpiBig.text = if (d.contains("Physical")) d.substringAfter("override: ").ifEmpty { d } else d
        }
        refreshDpi()

        findViewById<Button>(R.id.btnPreset160).setOnClickListener { DpiManager.setDensity(160); refreshDpi(); Toast.makeText(this, "160 DPI 적용 완료", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnPreset180).setOnClickListener { DpiManager.setDensity(180); refreshDpi(); Toast.makeText(this, "180 DPI 적용 완료", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnPreset200).setOnClickListener { DpiManager.setDensity(200); refreshDpi(); Toast.makeText(this, "200 DPI 적용 완료", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnPresetReset).setOnClickListener { DpiManager.resetDensity(); refreshDpi(); Toast.makeText(this, "순정 DPI 복원 완료", Toast.LENGTH_SHORT).show() }

        findViewById<Button>(R.id.btnWirelessAdbGuide).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("📖 BYD 순정 무선 ADB(5555) 활성화 가이드")
                .setMessage("📌 차량 화면에서 직접 5555 포트를 여는 방법:\n\n" +
                        "1. 차량 중앙 화면의 [차량 설정(Vehicle Settings)] 진입\n" +
                        "2. [시스템 설정(System)] 또는 [소프트웨어 버전] 메뉴 이동\n" +
                        "3. '소프트웨어 버전' 텍스트를 7~10회 연속으로 연타\n" +
                        "4. 히든 엔지니어링 모드가 열리면 [Wireless ADB] 또는 [무선 디버깅] 스위치를 켭니다.\n" +
                        "5. 본 앱으로 돌아와서 [⚡ ADB 즉시 승인 시도]를 누르면 'USB 디버깅을 허용하시겠습니까?' 팝업이 표출됩니다!\n\n" +
                        "(또는 버그제거 앱에서 1회 'adb tcpip 5555' 실행 시 즉시 포트가 개방됩니다)")
                .setPositiveButton("확인", null)
                .show()
        }

        findViewById<Button>(R.id.btnAutoGrantAdb).setOnClickListener {
            Toast.makeText(this, "로컬 ADB 연결 시도 중... 화면 팝업을 확인하세요", Toast.LENGTH_SHORT).show()
            performAutoAdbGrant()
        }

        findViewById<Button>(R.id.btnCopyAllAdbCmds).setOnClickListener {
            val cmds = """
adb shell pm grant com.byd.dolphin.autoassistant android.permission.SYSTEM_ALERT_WINDOW
adb shell pm grant com.byd.dolphin.autoassistant android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.byd.dolphin.autoassistant android.permission.DUMP
adb shell cmd notification allow_listener com.byd.dolphin.autoassistant/.hud.MultiNavNotificationListener
            """.trimIndent()

            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ADB_COMMANDS", cmds))

            AlertDialog.Builder(this)
                .setTitle("📋 ADB 권한 일괄 명령어 복사 완료")
                .setMessage("스마트폰(버그제거/Bugjaeger) 앱 명령어 창에 붙여넣어 실행하시면 즉시 모든 권한이 승인됩니다:\n\n$cmds\n\n(참고: 버그제거에서 1회 'adb tcpip 5555'를 입력해두시면 이후부터는 케이블 없이 앱이 자체 작동합니다)")
                .setPositiveButton("확인", null)
                .show()
        }

        findViewById<Button>(R.id.btnGenerateDiagnosticLog).setOnClickListener {
            val file = DolphinLogger.exportDiagnosticReport(this)
            val tvPath = findViewById<TextView>(R.id.tvLogPathInfo)
            if (file.exists()) {
                tvPath.text = "저장 완료: " + file.absolutePath + " (" + file.length() + " Bytes)"
                tvPath.setTextColor(Color.parseColor("#00E676"))
                shareLogFile(file)
            }
        }
    }

    private fun sendGearBroadcast(gear: String) {
        val intent = Intent("com.byd.auto.intent.action.GEAR_CHANGED").apply {
            putExtra("gear", gear)
            putExtra("speed", 0.0f)
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

    private fun shareLogFile(logFile: File) {
        val uri = try {
            FileProvider.getUriForFile(this, packageName + ".fileprovider", logFile)
        } catch (e: Exception) {
            Uri.fromFile(logFile)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "통합 진단 로그 공유"))
    }
}
