package com.byd.dolphin.autoassistant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import com.byd.dolphin.autoassistant.hud.ClusterTbtBridge
import com.byd.dolphin.autoassistant.hud.T900BluetoothManager
import com.byd.dolphin.autoassistant.hud.T900Protocol
import com.byd.dolphin.autoassistant.manager.AppRoutingManager
import com.byd.dolphin.autoassistant.manager.DpiManager
import com.byd.dolphin.autoassistant.manager.DefrostManager
import com.byd.dolphin.autoassistant.floating.FloatingItemManager
import com.byd.dolphin.autoassistant.manager.FloatingOverlayManager
import com.byd.dolphin.autoassistant.rule.engine.RuleEngine
import com.byd.dolphin.autoassistant.rule.engine.RuleStorage
import com.byd.dolphin.autoassistant.rule.model.*
import com.byd.dolphin.autoassistant.manager.InsideLightManager
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.service.DolphinService
import com.byd.dolphin.autoassistant.split.SplitConfig
import com.byd.dolphin.autoassistant.split.SplitMode
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvCurrentDpi: TextView
    private lateinit var chipGroupRoutedApps: ChipGroup
    private lateinit var tvHudStatus: TextView
    private lateinit var tvLogPathInfo: TextView

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

        DolphinLogger.init(this)
        DolphinLogger.i("UI", "MainActivity onCreate")

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
                DolphinLogger.i("SPLIT", "Launched from Home Screen shortcut: ${it.title}")
                SplitScreenManager.launchSplitScreen(this, it)
                finish()
                return
            }
        }

        setupDiagnosticLogSection()
        setupSplitScreenUI()
        setupToggleSwitches()
        setupInsideLightSection()
        setupDefrostSection()
        setupFloatingOverlaySection()
        setupAutomationSyncSection()
        setupCustomRuleSection()
        setupClusterAndHudSection()
        setupAppRoutingUI()
        setupDpiControls()
        setupTestButtons()
    }

    private fun setupDiagnosticLogSection() {
        tvLogPathInfo = findViewById(R.id.tvLogPathInfo)

        findViewById<Button>(R.id.btnExportDebugLog).setOnClickListener {
            val file = DolphinLogger.exportDiagnosticReport(this)
            tvLogPathInfo.text = "저장 완료: ${file.absolutePath} (${file.length() / 1024} KB)"
            Toast.makeText(this, "진단 로그 파일이 생성되었습니다.\n${file.name}", Toast.LENGTH_LONG).show()

            AlertDialog.Builder(this)
                .setTitle("📋 진단 로그 생성 완료")
                .setMessage("로그 파일이 아래 경로에 저장되었습니다.\n\n경로: ${file.absolutePath}\n\n이 파일을 복사하거나 공유하여 AI에게 전달하시면 문제 원인을 즉시 진단할 수 있습니다.")
                .setPositiveButton("확인", null)
                .setNeutralButton("공유하기") { _, _ -> shareLogFile(file) }
                .show()
        }

        findViewById<Button>(R.id.btnShareDebugLog).setOnClickListener {
            val file = DolphinLogger.exportDiagnosticReport(this)
            shareLogFile(file)
        }

        findViewById<Button>(R.id.btnClearDebugLog).setOnClickListener {
            DolphinLogger.clearLogs()
            tvLogPathInfo.text = "로그가 초기화되었습니다."
            Toast.makeText(this, "로그가 초기화되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLogFile(file: File) {
        try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "BYD Dolphin Assistant Diagnostic Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "진단 로그 공유"))
        } catch (e: Exception) {
            DolphinLogger.e("EXPORT", "Failed to share log file", e)
            Toast.makeText(this, "로그 파일 경로: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
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
                    tvRatio1.text = "좌/우 분할 비율: ${sbRatio1.progress}% : ${100 - sbRatio1.progress}%"
                }
                R.id.rbThreeLeftStack -> {
                    selectedSplitMode = SplitMode.THREE_APPS_LEFT_STACKED
                    layoutRatio2Container.visibility = LinearLayout.VISIBLE
                    btnSelectApp3.visibility = Button.VISIBLE
                    tvRatio1.text = "좌/우 분할 비율: ${sbRatio1.progress}% : ${100 - sbRatio1.progress}%"
                    tvRatio2.text = "좌측 상/하 분할 비율: ${sbRatio2.progress}% : ${100 - sbRatio2.progress}%"
                }
                R.id.rbThreeRightStack -> {
                    selectedSplitMode = SplitMode.THREE_APPS_RIGHT_STACKED
                    layoutRatio2Container.visibility = LinearLayout.VISIBLE
                    btnSelectApp3.visibility = Button.VISIBLE
                    tvRatio1.text = "좌/우 분할 비율: ${sbRatio1.progress}% : ${100 - sbRatio1.progress}%"
                    tvRatio2.text = "우측 상/하 분할 비율: ${sbRatio2.progress}% : ${100 - sbRatio2.progress}%"
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

    private fun setupInsideLightSection() {
        findViewById<Button>(R.id.btnInsideLightOn).setOnClickListener {
            InsideLightManager.turnOn(this, showToast = true)
        }
        findViewById<Button>(R.id.btnInsideLightOff).setOnClickListener {
            InsideLightManager.turnOff(this, showToast = true)
        }
        findViewById<Button>(R.id.btnInsideLightToggle).setOnClickListener {
            InsideLightManager.toggle(this, showToast = true)
        }

        val swDoorLight = findViewById<SwitchCompat>(R.id.swDoorLightInterlock)
        swDoorLight.isChecked = SettingsManager.isDoorLightEnabled(this)
        swDoorLight.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setDoorLightEnabled(this, isChecked)
            InsideLightManager.setDoorInterlock(this, isChecked)
            Toast.makeText(this, if (isChecked) "도어 연동 실내등 켜짐" else "도어 연동 실내등 꺼짐", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCreateLightShortcut).setOnClickListener {
            InsideLightManager.createHomeScreenShortcut(this)
        }
    }

    private fun setupDefrostSection() {
        findViewById<Button>(R.id.btnDefrostOn).setOnClickListener {
            DefrostManager.turnOn(this, showToast = true)
        }
        findViewById<Button>(R.id.btnDefrostOff).setOnClickListener {
            DefrostManager.turnOff(this, showToast = true)
        }
        findViewById<Button>(R.id.btnDefrostToggle).setOnClickListener {
            DefrostManager.toggle(this, showToast = true)
        }
    }

    private fun setupFloatingOverlaySection() {
        val swFloating = findViewById<SwitchCompat>(R.id.swFloatingOverlay)
        swFloating.isChecked = SettingsManager.isFloatingOverlayEnabled(this)
        swFloating.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setFloatingOverlayEnabled(this, isChecked)
            if (isChecked) {
                FloatingOverlayManager.show(this)
            } else {
                FloatingOverlayManager.hide()
            }
            Toast.makeText(this, if (isChecked) "플로팅 버튼 활성화" else "플로팅 버튼 비활성화", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnResetFloatingPosition).setOnClickListener {
            FloatingOverlayManager.resetToDefaultPosition(this)
        }

        findViewById<Button>(R.id.btnSelectFloatingItems).setOnClickListener {
            showFloatingItemPickerDialog()
        }
    }

    private fun setupAutomationSyncSection() {
        val swAutoDefrost = findViewById<SwitchCompat>(R.id.swAutoDefrostSync)
        swAutoDefrost.isChecked = SettingsManager.isAutoDefrostSyncEnabled(this)
        swAutoDefrost.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setAutoDefrostSyncEnabled(this, isChecked)
            Toast.makeText(this, if (isChecked) "앞/뒷유리 성에제거 동시 연동 켜짐" else "연동 꺼짐", Toast.LENGTH_SHORT).show()
        }

        val swAutoLightPark = findViewById<SwitchCompat>(R.id.swAutoLightPark)
        swAutoLightPark.isChecked = SettingsManager.isAutoLightParkEnabled(this)
        swAutoLightPark.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setAutoLightParkEnabled(this, isChecked)
            Toast.makeText(this, if (isChecked) "P단 주차 실내등 자동 점등 켜짐" else "자동 점등 꺼짐", Toast.LENGTH_SHORT).show()
        }
    }


    private fun showFloatingItemPickerDialog() {
        val allItems = FloatingItemManager.getAllAvailableItems(context = this)
        val selectedIds = FloatingItemManager.getSelectedIds(this).toMutableSet()

        val itemTitles = allItems.map { it.title }.toTypedArray()
        val checkedItems = allItems.map { selectedIds.contains(it.id) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("플로팅 독에 표시할 기능 & 앱 선택")
            .setMultiChoiceItems(itemTitles, checkedItems) { _, which, isChecked ->
                val item = allItems[which]
                if (isChecked) {
                    selectedIds.add(item.id)
                } else {
                    selectedIds.remove(item.id)
                }
            }
            .setPositiveButton("적용") { _, _ ->
                FloatingItemManager.setSelectedIds(this, selectedIds.toList())
                FloatingOverlayManager.refresh(this)
                Toast.makeText(this, "플로팅 독에 " + selectedIds.size + "개 항목이 적용되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun setupCustomRuleSection() {
        findViewById<Button>(R.id.btnAddNewRule).setOnClickListener {
            showRuleCreationDialog()
        }
        refreshRulesList()
    }

    private fun refreshRulesList() {
        val container = findViewById<LinearLayout>(R.id.layoutRulesContainer)
        container.removeAllViews()

        val rules = RuleStorage.loadRules(this)
        if (rules.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "등록된 사용자 자동화 규칙이 없습니다. 위의 [+] 버튼을 눌러 규칙을 추가하세요."
                setTextColor(android.graphics.Color.parseColor("#888888"))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            container.addView(emptyTv)
            return
        }

        rules.forEach { rule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#252538"))
                    cornerRadius = 16f
                    setStroke(2, android.graphics.Color.parseColor("#444466"))
                }
                background = bg
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 16
                }
                layoutParams = lp
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(this).apply {
                text = rule.name
                textSize = 15f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            header.addView(tvTitle)

            val sw = SwitchCompat(this).apply {
                isChecked = rule.isEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    rule.isEnabled = isChecked
                    RuleStorage.updateRule(this@MainActivity, rule)
                }
            }
            header.addView(sw)

            val btnDelete = Button(this).apply {
                text = "삭제"
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#FF5252"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                }
                setOnClickListener {
                    RuleStorage.deleteRule(this@MainActivity, rule.id)
                    refreshRulesList()
                    Toast.makeText(this@MainActivity, "규칙 삭제됨", Toast.LENGTH_SHORT).show()
                }
            }
            header.addView(btnDelete)
            card.addView(header)

            val trigDesc = when (rule.trigger.type) {
                TriggerType.ON_READY_POWER -> "조건: 시동(READY) 감지 시"
                TriggerType.TEMPERATURE -> "조건: 외부 온도 " + rule.trigger.tempOperator + " " + rule.trigger.tempValue + "℃"
                TriggerType.GEAR_CHANGED -> "조건: 기어 " + rule.trigger.gearTarget + "단 변속 시"
                TriggerType.AC_DEFROST_TRIGGERED -> "조건: 성에제거 버튼 작동 시"
                TriggerType.AC_HEATER_TRIGGERED -> "조건: 히터(난방) 작동 시"
            }
            val tvTrig = TextView(this).apply {
                text = "📌 " + trigDesc
                setTextColor(android.graphics.Color.parseColor("#80D8FF"))
                textSize = 13f
                setPadding(0, 4, 0, 4)
            }
            card.addView(tvTrig)

            val actionsDesc = rule.actions.mapIndexed { idx, act ->
                when (act.type) {
                    ActionType.DELAY -> "" + (idx + 1) + ". [" + act.delaySeconds + "초 대기]"
                    ActionType.LAUNCH_APP -> "" + (idx + 1) + ". [앱 실행: " + act.appName + "]"
                    ActionType.MEDIA_CONTROL -> "" + (idx + 1) + ". [미디어: " + act.mediaCmd + "]"
                    ActionType.AC_CONTROL -> "" + (idx + 1) + ". [에어컨: " + act.acTemp + "℃ / " + act.acWind + "단]"
                    ActionType.HEAT_CONTROL -> "" + (idx + 1) + ". [열선: 핸들=" + act.heatSteering + ", 시트=" + act.heatDriverSeat + "]"
                    ActionType.INSIDE_LIGHT -> "" + (idx + 1) + ". [실내등: " + (if (act.lightOn) "ON" else "OFF") + "]"
                    ActionType.DEFROST_CONTROL -> "" + (idx + 1) + ". [성에제거: " + (if (act.defrostOn) "ON" else "OFF") + "]"
                }
            }.joinToString(" ➔ ")

            val tvActs = TextView(this).apply {
                text = "⚡ 실행 단계:" + actionsDesc
                setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                textSize = 12f
            }
            card.addView(tvActs)

            container.addView(card)
        }
    }

    private fun showRuleCreationDialog() {
        val etName = EditText(this).apply {
            hint = "규칙 이름을 입력하세요 (예: 시동 시 유튜브 뮤직 실행)"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }

        val triggerOptions = arrayOf("시동(READY) 시", "외부 온도 30도 이상 시", "기어 P단 변속 시", "히터 작동 시")
        val spTrig = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, triggerOptions)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("➕ 새 자동화 규칙 생성 (0.x초 정밀 단계)")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
                addView(etName)

                val tvTrigLabel = TextView(this@MainActivity).apply {
                    text = "[트리거(발동 조건) 선택]:"
                    setTextColor(android.graphics.Color.parseColor("#80D8FF"))
                }
                addView(tvTrigLabel)
                addView(spTrig)
            })
            .setPositiveButton("다음: 실행 액션 설정") { _, _ ->
                val ruleName = etName.text.toString().ifEmpty { "스마트 자동화 규칙" }
                val trig = when (spTrig.selectedItemPosition) {
                    0 -> Trigger(TriggerType.ON_READY_POWER)
                    1 -> Trigger(TriggerType.TEMPERATURE, tempOperator = ">=", tempValue = 30f)
                    2 -> Trigger(TriggerType.GEAR_CHANGED, gearTarget = "P")
                    else -> Trigger(TriggerType.AC_HEATER_TRIGGERED)
                }
                showActionBuilderDialog(RoutineRule(name = ruleName, trigger = trig))
            }
            .setNegativeButton("취소", null)

        builder.show()
    }

    private fun showActionBuilderDialog(rule: RoutineRule) {
        val actionTypes = arrayOf("0.x초 정밀 딜레이 추가", "설치된 앱 실행", "미디어 자동 재생(Play)", "에어컨 온도/풍량 제어", "전체 실내등 제어", "통합 성에제거 제어")

        AlertDialog.Builder(this)
    .setTitle("규칙: ${rule.name}\n(현재 추가된 단계: ${rule.actions.size}개)")
    .setItems(actionTypes) { _, which ->
                when (which) {
                    0 -> {
                        val etDelay = EditText(this).apply {
                            hint = "소수점 초 단위 입력 (예: 0.5, 2.0, 3.5)"
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                            setText("0.5")
                        }
                        AlertDialog.Builder(this)
                            .setTitle("0.x초 정밀 지연 시간 설정")
                            .setView(etDelay)
                            .setPositiveButton("추가") { _, _ ->
                                val sec = etDelay.text.toString().toDoubleOrNull() ?: 0.5
                                rule.actions.add(ActionStep(ActionType.DELAY, delaySeconds = sec))
                                showActionBuilderDialog(rule)
                            }.show()
                    }
                    1 -> {
                        val apps = AppRoutingManager.getInstalledApps(this)
                        val names = apps.map { it.name }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("실행할 앱 선택")
                            .setItems(names) { _, appIdx ->
                                val app = apps[appIdx]
                                rule.actions.add(ActionStep(ActionType.LAUNCH_APP, appPackage = app.packageName, appName = app.name))
                                showActionBuilderDialog(rule)
                            }.show()
                    }
                    2 -> {
                        rule.actions.add(ActionStep(ActionType.MEDIA_CONTROL, mediaCmd = "PLAY"))
                        Toast.makeText(this, "미디어 자동 재생(Play) 액션 추가됨", Toast.LENGTH_SHORT).show()
                        showActionBuilderDialog(rule)
                    }
                    3 -> {
                        rule.actions.add(ActionStep(ActionType.AC_CONTROL, acTemp = 22, acWind = 3))
                        Toast.makeText(this, "에어컨 22도 3단 액션 추가됨", Toast.LENGTH_SHORT).show()
                        showActionBuilderDialog(rule)
                    }
                    4 -> {
                        rule.actions.add(ActionStep(ActionType.INSIDE_LIGHT, lightOn = true))
                        Toast.makeText(this, "실내등 켜기 액션 추가됨", Toast.LENGTH_SHORT).show()
                        showActionBuilderDialog(rule)
                    }
                    5 -> {
                        rule.actions.add(ActionStep(ActionType.DEFROST_CONTROL, defrostOn = true))
                        Toast.makeText(this, "성에제거 켜기 액션 추가됨", Toast.LENGTH_SHORT).show()
                        showActionBuilderDialog(rule)
                    }
                }
            }
            .setPositiveButton("✅ 규칙 저장 완료") { _, _ ->
                if (rule.actions.isEmpty()) {
                    Toast.makeText(this, "액션이 최소 1개 이상 필요합니다.", Toast.LENGTH_SHORT).show()
                } else {
                    RuleStorage.addRule(this, rule)
                    refreshRulesList()
                    Toast.makeText(this, "규칙이 저장되었습니다!", Toast.LENGTH_SHORT).show()
                }
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
