package com.byd.dolphin.autoassistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.byd.dolphin.autoassistant.hud.ClusterTbtBridge
import com.byd.dolphin.autoassistant.hud.T900BluetoothManager
import com.byd.dolphin.autoassistant.hud.T900Protocol
import com.byd.dolphin.autoassistant.manager.AppRoutingManager
import com.byd.dolphin.autoassistant.manager.DpiManager
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.service.DolphinService
import com.byd.dolphin.autoassistant.split.SplitConfig
import com.byd.dolphin.autoassistant.split.SplitMode
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class MainActivity : AppCompatActivity() {

    private lateinit var tvCurrentDpi: TextView
    private lateinit var chipGroupRoutedApps: ChipGroup
    private lateinit var tvHudStatus: TextView

    private var selectedSplitMode = SplitMode.TWO_APPS_HORIZONTAL
    private var splitPkg1 = "com.skt.tmap.ku"
    private var splitPkg2 = "com.spotify.music"
    private var splitPkg3 = "com.google.android.youtube"

    private lateinit var tvRatio1: TextView
    private lateinit var tvRatio2: TextView
    private lateinit var sbRatio1: SeekBar
    private lateinit var sbRatio2: SeekBar
    private lateinit var layoutRatio2Container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val serviceIntent = Intent(this, DolphinService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 바탕화면 바로가기 아이콘 클릭 시 실행
        if (intent?.action == "ACTION_LAUNCH_CUSTOM_SPLIT") {
            val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra("EXTRA_SPLIT_CONFIG", SplitConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra("EXTRA_SPLIT_CONFIG") as? SplitConfig
            }
            config?.let {
                SplitScreenManager.launchSplitScreen(this, it)
                finish()
                return
            }
        }

        setupSplitScreenUI()
        setupToggleSwitches()
        setupClusterAndHudSection()
        setupAppRoutingUI()
        setupDpiControls()
        setupTestButtons()
    }

    private fun setupSplitScreenUI() {
        val radioGroupMode = findViewById<RadioGroup>(R.id.rgSplitMode)
        tvRatio1 = findViewById(R.id.tvRatio1)
        tvRatio2 = findViewById(R.id.tvRatio2)
        sbRatio1 = findViewById(R.id.sbRatio1)
        sbRatio2 = findViewById(R.id.sbRatio2)
        layoutRatio2Container = findViewById(R.id.layoutRatio2Container)

        val btnSelectApp1 = findViewById<Button>(R.id.btnSelectApp1)
        val btnSelectApp2 = findViewById<Button>(R.id.btnSelectApp2)
        val btnSelectApp3 = findViewById<Button>(R.id.btnSelectApp3)
        val etShortcutName = findViewById<EditText>(R.id.etShortcutName)

        radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbTwoApps -> {
                    selectedSplitMode = SplitMode.TWO_APPS_HORIZONTAL
                    layoutRatio2Container.visibility = LinearLayout.GONE
                    btnSelectApp3.visibility = Button.GONE
                    tvRatio1.text = "좌/우 비율: ${sbRatio1.progress}% : ${100 - sbRatio1.progress}%"
                }
                R.id.rbThreeLeftStack -> {
                    selectedSplitMode = SplitMode.THREE_APPS_LEFT_STACKED
                    layoutRatio2Container.visibility = LinearLayout.VISIBLE
                    btnSelectApp3.visibility = Button.VISIBLE
                    tvRatio1.text = "좌/우 비율: ${sbRatio1.progress}% : ${100 - sbRatio1.progress}%"
                    tvRatio2.text = "좌측 상/하 비율: ${sbRatio2.progress}% : ${100 - sbRatio2.progress}%"
                }
                R.id.rbThreeRightStack -> {
                    selectedSplitMode = SplitMode.THREE_APPS_RIGHT_STACKED
                    layoutRatio2Container.visibility = LinearLayout.VISIBLE
                    btnSelectApp3.visibility = Button.VISIBLE
                    tvRatio1.text = "좌/우 비율: ${sbRatio1.progress}% : ${100 - sbRatio1.progress}%"
                    tvRatio2.text = "우측 상/하 비율: ${sbRatio2.progress}% : ${100 - sbRatio2.progress}%"
                }
            }
        }

        sbRatio1.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(10, 90)
                tvRatio1.text = "좌/우 분할 비율: ${clamped}% : ${100 - clamped}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbRatio2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(10, 90)
                tvRatio2.text = "상/하 분할 비율: ${clamped}% : ${100 - clamped}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSelectApp1.setOnClickListener {
            showAppPicker { pkg, label ->
                splitPkg1 = pkg
                btnSelectApp1.text = "앱 1: $label"
            }
        }
        btnSelectApp2.setOnClickListener {
            showAppPicker { pkg, label ->
                splitPkg2 = pkg
                btnSelectApp2.text = "앱 2: $label"
            }
        }
        btnSelectApp3.setOnClickListener {
            showAppPicker { pkg, label ->
                splitPkg3 = pkg
                btnSelectApp3.text = "앱 3: $label"
            }
        }

        findViewById<Button>(R.id.btnLaunchSplitNow).setOnClickListener {
            val title = etShortcutName.text.toString().ifEmpty { "내비+미디어" }
            val config = SplitConfig(
                title = title,
                mode = selectedSplitMode,
                pkg1 = splitPkg1,
                pkg2 = splitPkg2,
                pkg3 = splitPkg3,
                ratioPrimary = sbRatio1.progress.coerceIn(10, 90),
                ratioSecondary = sbRatio2.progress.coerceIn(10, 90)
            )
            SplitScreenManager.launchSplitScreen(this, config)
        }

        findViewById<Button>(R.id.btnCreateShortcut).setOnClickListener {
            val title = etShortcutName.text.toString().ifEmpty { "내비+미디어" }
            val config = SplitConfig(
                title = title,
                mode = selectedSplitMode,
                pkg1 = splitPkg1,
                pkg2 = splitPkg2,
                pkg3 = splitPkg3,
                ratioPrimary = sbRatio1.progress.coerceIn(10, 90),
                ratioSecondary = sbRatio2.progress.coerceIn(10, 90)
            )
            SplitScreenManager.createHomeScreenShortcut(this, config)
        }
    }

    private fun showAppPicker(onSelected: (String, String) -> Unit) {
        val apps = AppRoutingManager.getInstalledApps(this)
        val names = apps.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("분할할 앱 선택")
            .setItems(names) { _, which ->
                val app = apps[which]
                onSelected(app.packageName, app.name)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun setupClusterAndHudSection() {
        tvHudStatus = findViewById(R.id.tvHudStatus)

        val swClusterTbt = findViewById<SwitchCompat>(R.id.swClusterTbt)
        swClusterTbt.isChecked = SettingsManager.isClusterTbtEnabled(this)
        swClusterTbt.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setClusterTbtEnabled(this, isChecked)
        }

        val swHudBridge = findViewById<SwitchCompat>(R.id.swHudBridge)
        swHudBridge.isChecked = SettingsManager.isHudBridgeEnabled(this)
        swHudBridge.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setHudBridgeEnabled(this, isChecked)
        }

        findViewById<Button>(R.id.btnConnectHud).setOnClickListener {
            tvHudStatus.text = "HUD 연결 시도 중..."
            T900BluetoothManager.connectToT900(this) { success, msg ->
                runOnUiThread {
                    tvHudStatus.text = "HUD 상태: $msg"
                }
            }
        }

        findViewById<Button>(R.id.btnGrantNotifPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnTestClusterTurnLeft).setOnClickListener {
            ClusterTbtBridge.sendTbtToCluster(
                context = this,
                turnType = T900Protocol.TURN_LEFT,
                turnDistanceMeters = 300,
                distanceStr = "300m",
                speedLimit = 60,
                nextRoadName = "좌회전 300m 앞"
            )
            Toast.makeText(this, "순정 계기판에 [300m 좌회전·60km] 전송됨", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAppRoutingUI() {
        chipGroupRoutedApps = findViewById(R.id.chipGroupRoutedApps)
        updateRoutedAppChips()

        findViewById<Button>(R.id.btnAddApp).setOnClickListener {
            showAppPicker { pkg, label ->
                AppRoutingManager.addPackage(this, pkg)
                updateRoutedAppChips()
                Toast.makeText(this, "$label 추가됨 (운전석 전용)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateRoutedAppChips() {
        chipGroupRoutedApps.removeAllViews()
        val routedPackages = AppRoutingManager.getRoutedPackages(this)
        val pm = packageManager

        for (pkg in routedPackages) {
            val chip = Chip(this).apply {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    text = pm.getApplicationLabel(appInfo)
                    chipIcon = pm.getApplicationIcon(appInfo)
                } catch (e: Exception) {
                    text = if (pkg.contains("tmap")) "순정 TMAP" else pkg
                }
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    AppRoutingManager.removePackage(this@MainActivity, pkg)
                    updateRoutedAppChips()
                }
            }
            chipGroupRoutedApps.addView(chip)
        }
    }

    private fun setupToggleSwitches() {
        val swGear = findViewById<SwitchCompat>(R.id.swGearVoice)
        val swAutoHold = findViewById<SwitchCompat>(R.id.swAutoHoldVoice)
        val swEpb = findViewById<SwitchCompat>(R.id.swEpbVoice)
        val swIcc = findViewById<SwitchCompat>(R.id.swIccVoice)
        val swDriveMode = findViewById<SwitchCompat>(R.id.swDriveModeVoice)
        val swRegenMode = findViewById<SwitchCompat>(R.id.swRegenModeVoice)
        val swHazard = findViewById<SwitchCompat>(R.id.swHazardAuto)
        val swCharging = findViewById<SwitchCompat>(R.id.swChargingVoice)
        val swSafety = findViewById<SwitchCompat>(R.id.swSafetyAlert)

        swGear.isChecked = SettingsManager.isGearVoiceEnabled(this)
        swGear.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setGearVoiceEnabled(this, isChecked) }

        swAutoHold.isChecked = SettingsManager.isAutoHoldVoiceEnabled(this)
        swAutoHold.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setAutoHoldVoiceEnabled(this, isChecked) }

        swEpb.isChecked = SettingsManager.isEpbVoiceEnabled(this)
        swEpb.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setEpbVoiceEnabled(this, isChecked) }

        swIcc.isChecked = SettingsManager.isIccVoiceEnabled(this)
        swIcc.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setIccVoiceEnabled(this, isChecked) }

        swDriveMode.isChecked = SettingsManager.isDriveModeVoiceEnabled(this)
        swDriveMode.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setDriveModeVoiceEnabled(this, isChecked) }

        swRegenMode.isChecked = SettingsManager.isRegenModeVoiceEnabled(this)
        swRegenMode.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setRegenModeVoiceEnabled(this, isChecked) }

        swHazard.isChecked = SettingsManager.isHazardAutoEnabled(this)
        swHazard.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setHazardAutoEnabled(this, isChecked) }

        swCharging.isChecked = SettingsManager.isChargingVoiceEnabled(this)
        swCharging.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setChargingVoiceEnabled(this, isChecked) }

        swSafety.isChecked = SettingsManager.isSafetyAlertEnabled(this)
        swSafety.setOnCheckedChangeListener { _, isChecked -> SettingsManager.setSafetyAlertEnabled(this, isChecked) }
    }

    private fun setupDpiControls() {
        tvCurrentDpi = findViewById(R.id.tvCurrentDpi)
        updateDpiDisplay()

        findViewById<Button>(R.id.btnDpi180).setOnClickListener { applyDpi(180) }
        findViewById<Button>(R.id.btnDpi160).setOnClickListener { applyDpi(160) }
        findViewById<Button>(R.id.btnDpi200).setOnClickListener { applyDpi(200) }
        findViewById<Button>(R.id.btnDpiReset).setOnClickListener {
            DpiManager.resetDensity()
            Toast.makeText(this, "DPI 기본값 복구됨", Toast.LENGTH_SHORT).show()
            updateDpiDisplay()
        }

        val etCustomDpi = findViewById<EditText>(R.id.etCustomDpi)
        findViewById<Button>(R.id.btnApplyCustomDpi).setOnClickListener {
            val dpiVal = etCustomDpi.text.toString().toIntOrNull()
            if (dpiVal != null && dpiVal in 120..480) {
                applyDpi(dpiVal)
            } else {
                Toast.makeText(this, "120~480 사이 숫자를 입력하세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTestButtons() {
        findViewById<Button>(R.id.btnTestGearD).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.GEAR_CHANGED").apply { putExtra("gear", "D") })
        }
        findViewById<Button>(R.id.btnTestGearR).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.GEAR_CHANGED").apply { putExtra("gear", "R") })
        }
        findViewById<Button>(R.id.btnTestGearN).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.GEAR_CHANGED").apply { putExtra("gear", "N") })
        }
        findViewById<Button>(R.id.btnTestGearP).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.GEAR_CHANGED").apply { putExtra("gear", "P") })
        }
        findViewById<Button>(R.id.btnTestAutoHoldOn).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.AUTOHOLD_SWITCH_CHANGED").apply { putExtra("is_enabled", true) })
        }
        findViewById<Button>(R.id.btnTestAutoHoldOff).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.AUTOHOLD_SWITCH_CHANGED").apply { putExtra("is_enabled", false) })
        }
        findViewById<Button>(R.id.btnTestEpbOn).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.EPB_STATUS").apply { putExtra("is_epb_active", true) })
        }
        findViewById<Button>(R.id.btnTestEpbOff).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.EPB_STATUS").apply { putExtra("is_epb_active", false) })
        }
        findViewById<Button>(R.id.btnTestIccOn).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.ICC_STATUS").apply { putExtra("is_icc_active", true) })
        }
        findViewById<Button>(R.id.btnTestIccOff).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.ICC_STATUS").apply { putExtra("is_icc_active", false) })
        }
        findViewById<Button>(R.id.btnTestModeEco).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.DRIVE_MODE_CHANGED").apply { putExtra("mode", "ECO") })
        }
        findViewById<Button>(R.id.btnTestModeNormal).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.DRIVE_MODE_CHANGED").apply { putExtra("mode", "NORMAL") })
        }
        findViewById<Button>(R.id.btnTestModeSport).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.DRIVE_MODE_CHANGED").apply { putExtra("mode", "SPORT") })
        }
        findViewById<Button>(R.id.btnTestRegenEco).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.REGEN_MODE_CHANGED").apply { putExtra("regen", "ECO") })
        }
        findViewById<Button>(R.id.btnTestRegenHigh).setOnClickListener {
            sendBroadcast(Intent("com.byd.auto.intent.action.REGEN_MODE_CHANGED").apply { putExtra("regen", "HIGH") })
        }
    }

    private fun applyDpi(dpi: Int) {
        DpiManager.setDensity(dpi)
        Toast.makeText(this, "${dpi} DPI 적용됨", Toast.LENGTH_SHORT).show()
        updateDpiDisplay()
    }

    private fun updateDpiDisplay() {
        tvCurrentDpi.text = "현재 DPI: " + DpiManager.getCurrentDensity()
    }
}
