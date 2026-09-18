package com.byd.dolphin.autoassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.drive.DriveFrame
import com.byd.dolphin.autoassistant.drive.DriveSignalReader
import com.byd.dolphin.autoassistant.drive.DriveVisionActivity
import com.byd.dolphin.autoassistant.manager.AdbPermissionManager
import com.byd.dolphin.autoassistant.manager.DiagnosticCaptureManager
import com.byd.dolphin.autoassistant.manager.FloatingOverlayManager
import com.byd.dolphin.autoassistant.service.DolphinService
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v32 command-center shell.
 *
 * The legacy 10-card dashboard is intentionally kept behind MainActivity as a
 * compatibility host so mature feature code is not rewritten all at once.
 * User-facing navigation is reduced to six non-duplicated areas.
 */
class CommandCenterActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var headerSubtitle: TextView
    private var selectedSection = Section.HOME
    private val railButtons = linkedMapOf<Section, TextView>()
    private lateinit var reader: DriveSignalReader
    private var lastFrame = DriveFrame()

    enum class Section(val label: String, val eyebrow: String) {
        HOME("HOME", "OVERVIEW"),
        VEHICLE("VEHICLE", "COMFORT"),
        DRIVE("DRIVE & VOICE", "ASSIST"),
        DISPLAY("DISPLAY & LINK", "CONNECT"),
        AUTOMATION("AUTOMATION", "ROUTINES"),
        LAB("LAB / DIAGNOSTICS", "SERVICE")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(3, 8, 16)
        window.navigationBarColor = Color.rgb(3, 8, 16)
        DolphinLogger.init(this)
        DiagnosticCaptureManager.recoverInterruptedSession(this)
        reader = DriveSignalReader(this)

        setContentView(buildUi())
        ensureNotificationPermission()
        startAssistantService()
        render(Section.HOME)
        startHeaderTelemetry()
    }

    override fun onDestroy() {
        reader.close()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(3, 8, 16))
        }

        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(16), dp(10), dp(12))
            background = solid(Color.rgb(5, 15, 24), 0f)
        }
        rail.addView(text("DOLPHIN", 17f, Color.WHITE, true))
        rail.addView(text("COMMAND\nCENTER", 10f, Color.rgb(0, 229, 255), true).apply {
            setPadding(0, dp(2), 0, dp(18))
        })

        Section.values().forEach { section ->
            val button = TextView(this).apply {
                text = section.label
                textSize = 11f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(12), dp(8), dp(12))
                setOnClickListener { render(section) }
            }
            railButtons[section] = button
            rail.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(5)) })
        }

        rail.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        rail.addView(text("v32 UI\nREAD-ONLY FIRST", 9f, Color.rgb(100, 135, 150), false))

        root.addView(rail, LinearLayout.LayoutParams(dp(152), LinearLayout.LayoutParams.MATCH_PARENT))

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleWrap.addView(text("DOLPHIN // COMMAND CENTER", 21f, Color.WHITE, true))
        headerSubtitle = text("차량 신호 연결 확인 중", 11f, Color.rgb(128, 164, 177), false)
        titleWrap.addView(headerSubtitle)
        header.addView(titleWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val version = text(BuildConfig.VERSION_NAME, 9f, Color.rgb(0, 230, 180), true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = solid(Color.rgb(7, 46, 45), dp(14).toFloat())
        }
        header.addView(version)
        main.addView(header)

        main.addView(View(this).apply { setBackgroundColor(Color.rgb(18, 61, 72)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(0, dp(10), 0, dp(10))
            })

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(content)
        main.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(main, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    private fun render(section: Section) {
        selectedSection = section
        railButtons.forEach { (key, view) ->
            val selected = key == section
            view.setTextColor(if (selected) Color.WHITE else Color.rgb(115, 145, 156))
            view.background = solid(
                if (selected) Color.rgb(10, 55, 69) else Color.TRANSPARENT,
                dp(12).toFloat()
            )
            view.setTypeface(view.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        content.removeAllViews()
        content.addView(text(section.eyebrow, 10f, Color.rgb(0, 229, 255), true))
        content.addView(text(section.label, 27f, Color.WHITE, true).apply {
            setPadding(0, dp(1), 0, dp(12))
        })

        when (section) {
            Section.HOME -> renderHome()
            Section.VEHICLE -> {
                addFeatureCard(
                    "FRONT COMFORT",
                    "앞좌석·핸들 열선",
                    "운전석/동승석 2단 열선과 핸들 열선 제어",
                    "VEHICLE",
                    "comfort"
                )
            }
            Section.DRIVE -> {
                addFeatureCard(
                    "VOICE AI",
                    "맞춤형 차량 음성 안내",
                    "기어·회생제동·주행모드·오토홀드·ICC·전방차량 안내",
                    "VOICE",
                    "voice"
                )
                addFeatureCard(
                    "SAFETY AUDIO",
                    "안전 경고 & 운전석 오디오",
                    "BSD·경고음·TTS·운전석 전용 오디오 경로 테스트",
                    "AUDIO",
                    "safety"
                )
            }
            Section.DISPLAY -> {
                addFeatureCard(
                    "MULTI WINDOW",
                    "화면 분할 관리",
                    "DiLink 3 검증 2분할 · 앱 선택 · 1% 비율 제어",
                    "DISPLAY",
                    "split"
                )
                addFeatureCard(
                    "T900 HUD",
                    "HUD 연동",
                    "TMAP Plus HUD / T900 경로와 안전정보 연동",
                    "LINK",
                    "hud"
                )
                addFeatureCard(
                    "CLUSTER TBT",
                    "계기판 길안내",
                    "지원 내비 TBT를 BYD 계기판 경로로 전달",
                    "LINK",
                    "cluster"
                )
                addFeatureCard(
                    "FLOATING DOCK",
                    "플로팅 독 & 버튼 빌더",
                    "퀵패널/설치 앱 바로가기와 플로팅 도크 구성",
                    "DISPLAY",
                    "button"
                )
            }
            Section.AUTOMATION -> {
                addFeatureCard(
                    "SMART SCENARIO",
                    "차량 자동화",
                    "차량 상태를 조건으로 실행하는 사용자 규칙",
                    "ROUTINE",
                    "automation"
                )
                addFeatureCard(
                    "AUTO STARTER",
                    "시동 앱 자동 실행",
                    "앱별 실행 지연과 미디어 재생 예약",
                    "ROUTINE",
                    "boot"
                )
            }
            Section.LAB -> {
                addFeatureCard(
                    "DOLPHIN DIAGNOSTICS",
                    "시스템 · DPI · 통합 진단",
                    "DPI/권한/15분 캡처/진단 ZIP을 한 진입점에서 관리",
                    "LAB",
                    "dpi"
                )
                addInfoStrip(
                    "원칙",
                    "검증되지 않은 차량 신호는 기능으로 승격하지 않고 LAB/RAW로 유지합니다."
                )
            }
        }
    }

    private fun renderHome() {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = solid(Color.rgb(7, 29, 41), dp(20).toFloat(), Color.rgb(0, 145, 170))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@CommandCenterActivity, DriveVisionActivity::class.java))
            }
        }
        hero.addView(text("DRIVE // SURROUNDING VISION", 20f, Color.WHITE, true))
        hero.addView(text(
            "FSD 스타일 주변 시각화 + 안전운전 통합",
            13f,
            Color.rgb(0, 229, 255),
            true
        ).apply { setPadding(0, dp(4), 0, 0) })
        hero.addView(text(
            "속도·기어·방향지시등 LIVE · BSD/레이더/차선 RAW/LAB · 과속/신호/구간/주정차 내비 정보",
            11f,
            Color.rgb(155, 183, 192),
            false
        ).apply { setPadding(0, dp(5), 0, dp(12)) })
        hero.addView(text("OPEN DRIVE VIEW  →", 11f, Color.rgb(0, 230, 180), true))
        content.addView(hero, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(12)) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(statusTile("SPEED", lastFrame.speedKmh?.toInt()?.toString()?.plus(" km/h") ?: "--"),
            LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(0, 0, dp(6), 0) })
        row.addView(statusTile("GEAR", lastFrame.gear ?: "--"),
            LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        row.addView(statusTile("TURN", lastFrame.turn),
            LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        content.addView(row)

        addInfoStrip(
            "DATA STATUS",
            "LIVE: 속도/기어/방향지시등 · LAB: BSD/차선/TJA/레이더 · OBJECT TRACK: NO SIGNAL"
        )
    }

    private fun statusTile(title: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = solid(Color.rgb(8, 25, 36), dp(16).toFloat())
            addView(text(title, 9f, Color.rgb(92, 160, 180), true))
            addView(text(value, 20f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) })
        }

    private fun addFeatureCard(
        eyebrow: String,
        title: String,
        description: String,
        badge: String,
        panel: String
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = solid(Color.rgb(8, 25, 36), dp(17).toFloat())
            isClickable = true
            setOnClickListener { openLegacy(panel) }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(eyebrow, 10f, Color.rgb(0, 229, 255), true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text(badge, 9f, Color.rgb(0, 230, 180), true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = solid(Color.rgb(7, 45, 43), dp(12).toFloat())
        })
        card.addView(top)
        card.addView(text(title, 16f, Color.WHITE, true).apply { setPadding(0, dp(6), 0, 0) })
        card.addView(text(description, 11f, Color.rgb(146, 173, 183), false).apply {
            setPadding(0, dp(4), 0, dp(3))
        })
        card.addView(text("OPEN  →", 10f, Color.rgb(85, 205, 225), true).apply {
            setPadding(0, dp(5), 0, 0)
        })

        content.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(9)) })
    }

    private fun addInfoStrip(title: String, value: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = solid(Color.rgb(6, 18, 27), dp(14).toFloat())
        }
        box.addView(text(title, 9f, Color.rgb(100, 160, 178), true))
        box.addView(text(value, 11f, Color.rgb(154, 177, 185), false).apply { setPadding(0, dp(3), 0, 0) })
        content.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(12), 0, 0) })
    }

    private fun openLegacy(panel: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("open_panel", panel)
        })
    }

    private fun startAssistantService() {
        runCatching {
            val intent = Intent(this, DolphinService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            if (AdbPermissionManager.isOverlayGranted(this)) {
                FloatingOverlayManager.show(this)
            }
        }.onFailure {
            DolphinLogger.e("COMMAND_CENTER", "service start failed", it)
        }
    }

    private fun startHeaderTelemetry() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val frame = runCatching { reader.read() }.getOrDefault(DriveFrame())
                lastFrame = frame
                withContext(Dispatchers.Main) {
                    val speed = frame.speedKmh?.toInt()?.toString()?.plus(" km/h") ?: "NO SPEED"
                    headerSubtitle.text =
                        "READ-ONLY TELEMETRY · " + speed + " · GEAR " + (frame.gear ?: "--") +
                            " · " + BuildConfig.VERSION_NAME
                    if (selectedSection == Section.HOME) renderHomeRefreshOnly()
                }
                delay(1_000L)
            }
        }
    }

    private fun renderHomeRefreshOnly() {
        if (selectedSection != Section.HOME) return
        // The HOME section is intentionally small; rebuilding once per second is cheap
        // and keeps the three vehicle status tiles synchronized without duplicated state.
        render(Section.HOME)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 903)
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun solid(color: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
