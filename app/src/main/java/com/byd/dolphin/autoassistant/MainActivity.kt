package com.byd.dolphin.autoassistant

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    // 6대 서브 화면 뷰 레퍼런스
    private lateinit var layoutMainDashboard: View
    private lateinit var subLayoutSplit: View
    private lateinit var subLayoutSeat: View
    private lateinit var subLayoutVoice: View
    private lateinit var subLayoutHud: View
    private lateinit var subLayoutDpiAdb: View
    private lateinit var subLayoutTestLab: View

    private var isMirroringActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = VoiceAndSoundManager(this)

        initViewReferences()
        setupCardNavigation()
        setupDashboardHeader()
        setupVoiceSubScreen()
        setupSeatSubScreen()
        setupSplitSubScreen()
        setupDpiAdbSubScreen()
        setupHudSubScreen()
        setupTestLabSubScreen()

        // 0번 요구사항: 실행 시 수동 버그제거 앱 없이 자체 ADB 권한 자동 실행
        performAutoAdbGrant()
    }

    override fun onResume() {
        super.onResume()
        updateDashboardCards()
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

    private fun initViewReferences() {
        layoutMainDashboard = findViewById(R.id.layoutMainDashboard)
        subLayoutSplit = findViewById(R.id.subLayoutSplit)
        subLayoutSeat = findViewById(R.id.subLayoutSeat)
        subLayoutVoice = findViewById(R.id.subLayoutVoice)
        subLayoutHud = findViewById(R.id.subLayoutHud)
        subLayoutDpiAdb = findViewById(R.id.subLayoutDpiAdb)
        subLayoutTestLab = findViewById(R.id.subLayoutTestLab)
    }

    private fun showMainDashboard() {
        layoutMainDashboard.visibility = View.VISIBLE
        subLayoutSplit.visibility = View.GONE
        subLayoutSeat.visibility = View.GONE
        subLayoutVoice.visibility = View.GONE
        subLayoutHud.visibility = View.GONE
        subLayoutDpiAdb.visibility = View.GONE
        subLayoutTestLab.visibility = View.GONE
        updateDashboardCards()
    }

    private fun showSubScreen(targetSubLayout: View) {
        layoutMainDashboard.visibility = View.GONE
        subLayoutSplit.visibility = View.GONE
        subLayoutSeat.visibility = View.GONE
        subLayoutVoice.visibility = View.GONE
        subLayoutHud.visibility = View.GONE
        subLayoutDpiAdb.visibility = View.GONE
        subLayoutTestLab.visibility = View.GONE
        targetSubLayout.visibility = View.VISIBLE
    }

    // =========================================================================
    // 0번 요구사항: 실행 시 자체 ADB 권한 획득 처리
    // =========================================================================
    private fun performAutoAdbGrant() {
        val tvStatus = findViewById<TextView>(R.id.tvServiceStatusBadge)
        AdbPermissionManager.autoGrantPermissionsOnLaunch(this) { success, msg ->
            if (success) {
                tvStatus.text = "🟢 실시간 감시 중 (ADB 권한 자체 자동 승인 완료)"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
            } else {
                tvStatus.text = "⚠️ ADB 권한 확인 필요 (USB 디버깅 항상 허용 확인)"
                tvStatus.setTextColor(Color.parseColor("#FFB74D"))
            }
        }
    }

    // =========================================================================
    // 1번 요구사항: 메인 화면 6대 카드 터치 네비게이션
    // =========================================================================
    private fun setupCardNavigation() {
        findViewById<CardView>(R.id.cardMenuSplit).setOnClickListener { showSubScreen(subLayoutSplit) }
        findViewById<CardView>(R.id.cardMenuSeat).setOnClickListener { showSubScreen(subLayoutSeat) }
        findViewById<CardView>(R.id.cardMenuVoice).setOnClickListener { showSubScreen(subLayoutVoice) }
        findViewById<CardView>(R.id.cardMenuHud).setOnClickListener { showSubScreen(subLayoutHud) }
        findViewById<CardView>(R.id.cardMenuDpiAdb).setOnClickListener { showSubScreen(subLayoutDpiAdb) }
        findViewById<CardView>(R.id.cardMenuTestLab).setOnClickListener { showSubScreen(subLayoutTestLab) }

        findViewById<Button>(R.id.btnBackSplit).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackSeat).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackVoice).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackHud).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackDpiAdb).setOnClickListener { showMainDashboard() }
        findViewById<Button>(R.id.btnBackTestLab).setOnClickListener { showMainDashboard() }
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
            tvStatusBadge.text = "🟢 백그라운드 실시간 감시 중 (재실행됨)"
            tvStatusBadge.setTextColor(Color.parseColor("#00E676"))
            Toast.makeText(this, "돌핀 어시스턴트 서비스 재시작 완료", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDashboardCards() {
        val curDpi = DpiManager.getCurrentDensity()
        findViewById<TextView>(R.id.tvMainCardDpiDesc).text = "현재 DPI: " + curDpi + " (원터치 프리셋)\n미디어 N초 후 자동 재생\n자체 자동 ADB 권한"
    }

    // =========================================================================
    // 5번 요구사항: 맞춤 음성(TTS) & 운전석 전용 오디오 서브 화면
    // =========================================================================
    private fun setupVoiceSubScreen() {
        // 5-1. 운전석 전용 스피커 출력 대상 앱 관리
        updateDriverAudioChips()
        findViewById<Button>(R.id.btnAddDriverAudioApp).setOnClickListener {
            showAppPicker("운전석 스피커 출력 앱 추가") { pkg, _ ->
                AppRoutingManager.addPackage(this, pkg)
                updateDriverAudioChips()
            }
        }

        // 5-2 & 5-3. BSD / LDP 현대·기아 스타일 경고음 설정
        findViewById<Button>(R.id.btnConfigBsdAlert).setOnClickListener {
            showBsdLdpConfigDialog("BSD 사각지대 감지 경고음 설정", isBsd = true)
        }
        findViewById<Button>(R.id.btnConfigLdpAlert).setOnClickListener {
            showBsdLdpConfigDialog("LDP 차선이탈보조 경고음 설정", isBsd = false)
        }

        // 5-4. 10대 차량 상태 멘트 수정 & 미리듣기
        setupVoiceEditButton(R.id.btnVoiceGearP, "P단 (파킹)") { SettingsManager.getGearPhrase(this, "P") }
        setupVoiceEditButton(R.id.btnVoiceGearR, "R단 (후진)") { SettingsManager.getGearPhrase(this, "R") }
        setupVoiceEditButton(R.id.btnVoiceGearN, "N단 (중립)") { SettingsManager.getGearPhrase(this, "N") }
        setupVoiceEditButton(R.id.btnVoiceGearD, "D단 (전진)") { SettingsManager.getGearPhrase(this, "D") }
        setupVoiceEditButton(R.id.btnVoiceDriveMode, "드라이브 모드") { SettingsManager.getDriveModePhrase(this, "NORMAL") }
        setupVoiceEditButton(R.id.btnVoiceRegenMode, "회생 제동") { SettingsManager.getRegenModePhrase(this, "ECO") }
        setupVoiceEditButton(R.id.btnVoiceSnowMode, "스노우 모드") { SettingsManager.getSnowModePhrase(this) }
        setupVoiceEditButton(R.id.btnVoiceAutoHold, "오토홀드 체결/해제") { SettingsManager.getAutoHoldPhrase(this, true) }
        setupVoiceEditButton(R.id.btnVoiceEpb, "사이드브레이크") { SettingsManager.getEpbPhrase(this, true) }
        setupVoiceEditButton(R.id.btnVoiceIcc, "ICC 자율주행") { SettingsManager.getIccPhrase(this) }
        setupVoiceEditButton(R.id.btnVoiceLeadingCar, "전방 차량 출발") { SettingsManager.getLeadingCarPhrase(this) }
        setupVoiceEditButton(R.id.btnVoiceCharging, "충전 연결") { SettingsManager.getChargingPhrase(this) }
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

    private fun setupVoiceEditButton(buttonId: Int, title: String, currentPhraseProvider: () -> String) {
        val btn = findViewById<Button>(buttonId)
        val initialPhrase = currentPhraseProvider()
        btn.text = title + ": \"" + initialPhrase + "\""

        btn.setOnClickListener {
            val input = EditText(this).apply {
                setText(currentPhraseProvider())
                setSelection(text.length)
            }

            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("안내 시 출력할 음성 문구를 입력하세요:")
                .setView(input)
                .setNeutralButton("미리듣기") { _, _ -> }
                .setPositiveButton("저장") { _, _ ->
                    val newText = input.text.toString().trim()
                    if (newText.isNotEmpty()) {
                        saveCustomPhrase(buttonId, newText)
                        btn.text = title + ": \"" + newText + "\""
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
            R.id.btnVoiceAutoHold -> SettingsManager.setAutoHoldPhrase(this, true, phrase)
            R.id.btnVoiceEpb -> SettingsManager.setEpbPhrase(this, true, phrase)
            R.id.btnVoiceIcc -> SettingsManager.setIccPhrase(this, phrase)
            R.id.btnVoiceLeadingCar -> SettingsManager.setLeadingCarPhrase(this, phrase)
            R.id.btnVoiceCharging -> SettingsManager.setChargingPhrase(this, phrase)
        }
    }

    // =========================================================================
    // 시트 & 미러 편의 제어 서브 화면
    // =========================================================================
    private fun setupSeatSubScreen() {
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
                if (fromUser) {
                    SeatManager.setEasyAccessDelay(this@MainActivity, progress)
                }
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
    }

    // =========================================================================
    // 4번 요구사항: 2/3/4분할 & 크기 조절 & AVM 복구 방어 서브 화면
    // =========================================================================
    private fun setupSplitSubScreen() {
        val sbRatio = findViewById<SeekBar>(R.id.sbSubSplitRatio)
        val tvRatio = findViewById<TextView>(R.id.tvSubSplitRatio)
        val tvLeft = findViewById<TextView>(R.id.tvPreviewLeft)
        val tvRight = findViewById<TextView>(R.id.tvPreviewRight)
        val rbTwoApps = findViewById<RadioButton>(R.id.rbSubTwoApps)
        val rbThreeLeft = findViewById<RadioButton>(R.id.rbSubThreeLeft)

        sbRatio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val left = progress.coerceIn(1, 99)
                val right = 100 - left
                tvRatio.text = "좌/우 크기 조절: " + left + "% : " + right + "%"

                val leftParams = tvLeft.layoutParams as LinearLayout.LayoutParams
                leftParams.weight = left.toFloat()
                tvLeft.layoutParams = leftParams
                tvLeft.text = "앱 1 (" + left + "%)"

                val rightParams = tvRight.layoutParams as LinearLayout.LayoutParams
                rightParams.weight = right.toFloat()
                tvRight.layoutParams = rightParams
                tvRight.text = "앱 2 (" + right + "%)"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnSubApplySplit).setOnClickListener {
            val mode = when {
                rbTwoApps.isChecked -> SplitMode.TWO_APPS_HORIZONTAL
                rbThreeLeft.isChecked -> SplitMode.THREE_APPS_LEFT_STACKED
                else -> SplitMode.FOUR_APPS_GRID
            }
            val config = SplitConfig(
                title = "30:70 커스텀 분할",
                mode = mode,
                pkg1 = "com.skt.tmap.ku",
                pkg2 = "com.android.music",
                ratioPrimary = sbRatio.progress.coerceIn(1, 99)
            )
            SplitScreenManager.launchSplitScreen(this, config)
        }
    }

    // =========================================================================
    // 8번 & 9번 요구사항: DPI 설정 & 부팅 자동실행 서브 화면
    // =========================================================================
    private fun setupDpiAdbSubScreen() {
        val tvDpiBig = findViewById<TextView>(R.id.tvSubCurrentDpiBig)
        fun refreshDpi() {
            val d = DpiManager.getCurrentDensity()
            tvDpiBig.text = if (d.contains("Physical")) d.substringAfter("override: ").ifEmpty { d } else d
        }
        refreshDpi()

        findViewById<Button>(R.id.btnPreset160).setOnClickListener {
            DpiManager.setDensity(160)
            refreshDpi()
            Toast.makeText(this, "160 DPI 적용 완료", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPreset180).setOnClickListener {
            DpiManager.setDensity(180)
            refreshDpi()
            Toast.makeText(this, "180 DPI 적용 완료", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPreset200).setOnClickListener {
            DpiManager.setDensity(200)
            refreshDpi()
            Toast.makeText(this, "200 DPI 적용 완료", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPresetReset).setOnClickListener {
            DpiManager.resetDensity()
            refreshDpi()
            Toast.makeText(this, "순정 DPI 복원 완료", Toast.LENGTH_SHORT).show()
        }

        // 8번 부팅 자동 실행 설정
        val swBoot = findViewById<SwitchCompat>(R.id.swBootAuto)
        swBoot.isChecked = SettingsManager.isBootAutoEnabled(this)
        swBoot.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setBootAutoEnabled(this, isChecked) }

        findViewById<Button>(R.id.btnConfigBootMedia).setOnClickListener {
            showAppPicker("부팅 시 자동 재생할 미디어 앱 선택") { pkg, _ ->
                SettingsManager.setBootMediaPkg(this, pkg)
                Toast.makeText(this, "미디어 앱 설정 완료: " + pkg, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnConfigBootCustom).setOnClickListener {
            showAppPicker("부팅 N초 후 자동 실행할 앱 선택") { pkg, _ ->
                SettingsManager.setBootCustomPkg(this, pkg)
                Toast.makeText(this, "커스텀 자동실행 앱 설정 완료: " + pkg, Toast.LENGTH_SHORT).show()
            }
        }

        // 0번 자체 자동 ADB 권한 실행
        findViewById<Button>(R.id.btnAutoGrantAdb).setOnClickListener {
            performAutoAdbGrant()
            Toast.makeText(this, "자체 로컬 ADB 권한 획득 시도 중...", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // 2번 & 3번 요구사항: HUD (T900) & 계기판 디스플레이 서브 화면
    // =========================================================================
    private fun setupHudSubScreen() {
        val tvStatus = findViewById<TextView>(R.id.tvSubHudConnStatus)
        findViewById<Button>(R.id.btnSubConnectHud).setOnClickListener {
            tvStatus.text = "HUD 연결 시도 중..."
            T900BluetoothManager.connectToT900(this) { ok, msg ->
                runOnUiThread {
                    tvStatus.text = "T900 HUD: " + msg
                    tvStatus.setTextColor(if (ok) Color.parseColor("#00E676") else Color.parseColor("#FF5252"))
                }
            }
        }

        // 3-2. HUD 화면 밝기 (수동 / 자동 조절)
        val swAutoBright = findViewById<SwitchCompat>(R.id.swHudBrightnessAuto)
        val sbBrightness = findViewById<SeekBar>(R.id.sbHudBrightness)
        val tvBrightVal = findViewById<TextView>(R.id.tvHudBrightnessValue)

        swAutoBright.isChecked = SettingsManager.isHudBrightnessAuto(this)
        sbBrightness.progress = SettingsManager.getHudBrightnessManual(this)

        swAutoBright.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setHudBrightnessAuto(this, isChecked)
            HudDataManager.applyBrightness(this, isChecked, sbBrightness.progress)
        }

        sbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBrightVal.text = "수동 밝기: " + progress + " / 15 단계"
                if (fromUser) {
                    SettingsManager.setHudBrightnessManual(this@MainActivity, progress)
                    HudDataManager.applyBrightness(this@MainActivity, swAutoBright.isChecked, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3-3. hudaudio 음량 조절
        val sbAudioVol = findViewById<SeekBar>(R.id.sbSubHudAudioVolume)
        sbAudioVol.progress = SettingsManager.getHudAudioVolume(this)
        sbAudioVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    SettingsManager.setHudAudioVolume(this@MainActivity, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 2-2. 선택한 앱만 계기판 디스플레이(Display 1)로 화면 보내기
        findViewById<Button>(R.id.btnSendAppToCluster).setOnClickListener {
            showAppPicker("계기판으로 보낼 앱 선택") { pkg, name ->
                ClusterMirrorManager.launchAppOnClusterDisplay(this, pkg)
            }
        }

        // 2-3. 메인 화면 계기판 미러링 토글
        findViewById<Button>(R.id.btnToggleClusterMirroring).setOnClickListener {
            isMirroringActive = !isMirroringActive
            ClusterMirrorManager.toggleMainToClusterMirroring(this, isMirroringActive)
        }
    }

    // =========================================================================
    // 6번 & 7번 & 9-2 요구사항: 신호 랩 & 바로가기 생성 & 진단 로그
    // =========================================================================
    private fun setupTestLabSubScreen() {
        // 6번: 생성 버튼을 눌러야 만들어지는 바로가기/플로팅 메뉴
        findViewById<Button>(R.id.btnCreateQpButton).setOnClickListener {
            showCreateItemDialog("상단바 퀵패널 버튼 생성", FloatingItemManager.QUICK_PANEL_ITEMS)
        }
        findViewById<Button>(R.id.btnCreateBottomBarButton).setOnClickListener {
            showCreateItemDialog("하단바 버튼 생성", FloatingItemManager.BOTTOM_BAR_ITEMS)
        }
        findViewById<Button>(R.id.btnCreateLightButton).setOnClickListener {
            showCreateItemDialog("실내등 버튼 생성", FloatingItemManager.LIGHT_ITEMS)
        }
        findViewById<Button>(R.id.btnCreateAppFloatingButton).setOnClickListener {
            showAppPicker("플로팅 버튼으로 생성할 앱 선택") { pkg, name ->
                FloatingItemManager.addItem(this, FloatingItem("APP_" + pkg, "📱 " + name, isApp = true, packageName = pkg))
                Toast.makeText(this, name + " 플로팅 버튼이 생성되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 7-1. R/N/D/P 비상등 자동화 신호 테스트
        findViewById<Button>(R.id.btnTestGearR).setOnClickListener { sendGearBroadcast("R") }
        findViewById<Button>(R.id.btnTestGearD).setOnClickListener { sendGearBroadcast("D") }
        findViewById<Button>(R.id.btnTestGearP).setOnClickListener { sendGearBroadcast("P") }

        // 9-2. 모든 기능 상태 통합 진단 로그 추출
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

    private fun showCreateItemDialog(title: String, items: List<FloatingItem>) {
        val names = items.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, which ->
                val chosen = items[which]
                FloatingItemManager.addItem(this, chosen)
                Toast.makeText(this, chosen.title + " 버튼이 생성되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
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
