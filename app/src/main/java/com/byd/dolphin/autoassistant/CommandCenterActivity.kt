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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import com.byd.dolphin.autoassistant.manager.NowPlayingManager
import com.byd.dolphin.autoassistant.manager.NowPlayingSnapshot
import com.byd.dolphin.autoassistant.manager.VoiceAndSoundManager
import com.byd.dolphin.autoassistant.ui.NowPlayingCardView
import com.byd.dolphin.autoassistant.ui.VoicePhraseCatalog
import com.byd.dolphin.autoassistant.ui.VoicePhraseSpec
import com.byd.dolphin.autoassistant.service.DolphinService
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v32.2 command center matching the approved dark-navy/cyan dashboard concept.
 * Existing mature v30.x panels remain the implementation hosts behind each card.
 */
class CommandCenterActivity : AppCompatActivity() {

    private enum class Section(val label: String, val sub: String) {
        HOME("홈", "HOME"),
        QUICK("빠른 제어", "QUICK CONTROL"),
        FLOATING("플로팅 바", "FLOATING BAR"),
        MULTI("멀티 윈도우", "MULTI WINDOW"),
        AUDIO("오디오 라우팅", "AUDIO ROUTING"),
        AUTOMATION("자동화", "AUTOMATION"),
        ALERTS("주행 알림", "DRIVE ALERTS"),
        HUD("HUD / 클러스터", "DISPLAY LINK"),
        APPS("앱", "APP & SHORTCUT"),
        LAB("실험실", "LAB"),
        LOG("로그", "DIAGNOSTICS"),
        SETTINGS("설정", "SETTINGS")
    }

    private data class Action(val label: String, val onClick: () -> Unit)

    private lateinit var content: LinearLayout
    private lateinit var headerStatus: TextView
    private val railButtons = linkedMapOf<Section, TextView>()
    private var selected = Section.HOME

    private lateinit var driveReader: DriveSignalReader
    private lateinit var nowPlayingManager: NowPlayingManager
    private lateinit var voicePreviewManager: VoiceAndSoundManager
    private var nowPlaying = NowPlayingSnapshot()
    private var nowPlayingCard: NowPlayingCardView? = null
    private var frame = DriveFrame()
    private var speedValue: TextView? = null
    private var gearValue: TextView? = null
    private var turnValue: TextView? = null

    private val bg = Color.rgb(2, 8, 17)
    private val railBg = Color.rgb(4, 15, 27)
    private val cardBg = Color.rgb(6, 22, 36)
    private val cardBg2 = Color.rgb(7, 29, 43)
    private val cyan = Color.rgb(0, 229, 255)
    private val cyanSoft = Color.rgb(93, 205, 224)
    private val green = Color.rgb(0, 230, 180)
    private val muted = Color.rgb(128, 160, 175)
    private val line = Color.rgb(17, 72, 88)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        DolphinLogger.init(this)
        DiagnosticCaptureManager.recoverInterruptedSession(this)
        driveReader = DriveSignalReader(this)
        nowPlayingManager = NowPlayingManager(this)
        voicePreviewManager = VoiceAndSoundManager(this)

        setContentView(buildUi())
        ensureNotificationPermission()
        startAutomaticPermissionRecovery()
        startAssistantService()
        render(Section.HOME)
        startTelemetry()
    }

    override fun onDestroy() {
        driveReader.close()
        if (::voicePreviewManager.isInitialized) voicePreviewManager.release()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
        }

        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(12))
            background = gradient(railBg, Color.rgb(3, 23, 37), 0f)
        }

        rail.addView(text("돌핀", 18f, Color.WHITE, true))
        rail.addView(text("어시스트 랩", 11f, cyan, true).apply {
            setPadding(0, 0, 0, dp(2))
        })
        rail.addView(text("차량 맞춤형 어시스트 플랫폼", 8.5f, Color.rgb(96, 137, 151), false).apply {
            setPadding(0, 0, 0, dp(14))
        })

        Section.values().forEach { section ->
            val item = TextView(this).apply {
                text = section.label
                textSize = 10.5f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(8), dp(9))
                setOnClickListener { render(section) }
            }
            railButtons[section] = item
            rail.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(3)) })
        }

        rail.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        rail.addView(pill("실험 빌드", cyan))
        rail.addView(text(BuildConfig.VERSION_NAME, 8f, Color.rgb(94, 125, 138), false).apply {
            setPadding(0, dp(7), 0, 0)
        })

        root.addView(rail, LinearLayout.LayoutParams(dp(184), LinearLayout.LayoutParams.MATCH_PARENT))

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleWrap.addView(text("돌핀 어시스트 랩", 19f, Color.WHITE, true))
        titleWrap.addView(text("더 스마트한 드라이브 · 더 특별한 일상을 위해", 9.5f, Color.rgb(102, 145, 161), false))
        header.addView(titleWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(pill("활성 모듈", cyan).apply { setPadding(dp(10), dp(5), dp(10), dp(5)) })
        header.addView(pill("안전 샌드박스", green).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(7), 0, 0, 0) })
        main.addView(header)

        headerStatus = text("차량 신호 연결 확인 중", 9.5f, muted, false).apply {
            gravity = Gravity.END
            setPadding(0, dp(3), 0, dp(7))
        }
        main.addView(headerStatus)

        main.addView(View(this).apply { setBackgroundColor(line) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(22))
        }
        scroll.addView(content)
        main.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(main, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    private fun render(section: Section) {
        selected = section
        railButtons.forEach { (key, view) ->
            val active = key == section
            view.setTextColor(if (active) Color.WHITE else Color.rgb(117, 148, 160))
            view.setTypeface(view.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            view.background = if (active) {
                gradient(Color.rgb(8, 46, 63), Color.rgb(5, 27, 43), dp(11).toFloat(), cyan)
            } else {
                solid(Color.TRANSPARENT, dp(11).toFloat())
            }
        }

        content.removeAllViews()
        speedValue = null
        gearValue = null
        turnValue = null
        nowPlayingCard = null

        content.addView(text(section.sub, 9f, cyan, true))
        content.addView(text(section.label, 25f, Color.WHITE, true).apply {
            setPadding(0, dp(1), 0, dp(10))
        })

        when (section) {
            Section.HOME -> renderHome()
            Section.QUICK -> renderQuick()
            Section.FLOATING -> renderFloating()
            Section.MULTI -> renderMulti()
            Section.AUDIO -> renderAudio()
            Section.AUTOMATION -> renderAutomation()
            Section.ALERTS -> renderAlerts()
            Section.HUD -> renderHud()
            Section.APPS -> renderApps()
            Section.LAB -> renderLab()
            Section.LOG -> renderLog()
            Section.SETTINGS -> renderSettings()
        }
        refreshTelemetryLabels()
    }

    private fun renderHome() {
        addHero()
        nowPlayingCard = NowPlayingCardView(this, nowPlayingManager).also { card ->
            card.bind(nowPlaying)
            content.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) })
        }

        val live = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedValue = statusTile(live, "SPEED", "--", 0)
        gearValue = statusTile(live, "GEAR", "--", 1)
        turnValue = statusTile(live, "TURN", "--", 2)
        content.addView(live, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(78)
        ).apply { setMargins(0, 0, 0, dp(10)) })

        addCardRow(
            featureCard(
                "FLOATING BAR", "플로팅 바",
                "최대 8개 표시 · 초과 시 가로 스크롤 · 크기/투명도/자동 접기",
                listOf(
                    Action("설정") { openLegacy("button") },
                    Action("바로 표시") { showFloatingNow() }
                )
            ),
            featureCard(
                "QUICK CONTROLS", "빠른 제어",
                "차량 편의 제어와 퀵패널/하단바 기능을 한 곳에서 연결",
                listOf(
                    Action("차량 제어") { openLegacy("comfort") },
                    Action("버튼 만들기") { openLegacy("button") }
                )
            ),
            featureCard(
                "AUDIO ROUTING", "오디오 라우팅",
                "지정 앱 선택 · 운전석 전용 경로 연구 · 순정 안전 경고 우선 유지",
                listOf(
                    Action("앱 추가") { openLegacy("audio_lab") },
                    Action("경로 테스트") { openLegacy("safety") }
                )
            )
        )

        addCardRow(
            featureCard(
                "MULTI WINDOW", "멀티 윈도우",
                "앱 2개 선택 · 20~80% 비율 제어 · DiLink 분할 경로 복구",
                listOf(Action("2분할 설정") { openLegacy("split") })
            ),
            featureCard(
                "STARTUP AUTOMATION", "시동 자동화",
                "여러 앱 등록 · 앱별 0.x초 실행 지연 · 미디어 자동재생 예약",
                listOf(
                    Action("+ 앱 추가") { openLegacy("boot") },
                    Action("자동화") { openLegacy("automation") }
                )
            ),
            featureCard(
                "NAV / HUD / CLUSTER", "내비 · HUD · 클러스터",
                "지원 내비 TBT와 T900 HUD, 계기판 표시 연구를 통합 관리",
                listOf(
                    Action("HUD") { openLegacy("hud") },
                    Action("클러스터") { openLegacy("cluster") }
                )
            )
        )

        addCardRow(
            featureCard(
                "DRIVE ALERTS", "주행 알림",
                "기어 · 주행모드 · 회생제동 · 오토홀드 · ICC · BSD · 전방차 출발",
                listOf(
                    Action("음성 안내") { openLegacy("voice") },
                    Action("경고음") { openLegacy("safety") }
                )
            ),
            featureCard(
                "DIAGNOSTICS / RESEARCH", "진단 · 연구",
                "차량 RAW 신호 · 권한 · 오디오 출력 · DPI · 진단 세션",
                listOf(
                    Action("진단") { openLegacy("dpi") },
                    Action("주행 시각화") { openDriveView() }
                )
            ),
            featureCard(
                "APP & SHORTCUT", "앱 / 앱서랍 바로가기",
                "설치 앱과 차량 동작을 앱서랍 16개 슬롯 또는 플로팅 독에 추가",
                listOf(Action("바로가기 만들기") { openLegacy("button") })
            )
        )
    }

    private fun renderQuick() {
        addWideCard(
            "QUICK CONTROL", "빠른 제어",
            "편의 기능, 퀵패널 동작, 하단바 동작을 기존 검증 패널에서 실행합니다.",
            listOf(
                Action("차량 편의 제어") { openLegacy("comfort") },
                Action("퀵 버튼 빌더") { openLegacy("button") },
                Action("음성/상태 설정") { openLegacy("voice") }
            )
        )
    }

    private fun renderFloating() {
        addWideCard(
            "FLOATING BAR", "플로팅 바 설정",
            "전체 ON/OFF, 최소화, 크기, 투명도, 자동 접기, 설치 앱/차량 버튼 추가를 관리합니다.",
            listOf(
                Action("플로팅 설정 열기") { openLegacy("button") },
                Action("지금 표시") { showFloatingNow() }
            )
        )
    }

    private fun renderMulti() {
        addWideCard(
            "MULTI WINDOW", "분할 화면",
            "현재 실차 검증 경로는 2분할입니다. 3/4분할은 확인되지 않은 성공 표시를 하지 않고 LAB로 유지합니다.",
            listOf(Action("앱 선택 / 2분할 실행") { openLegacy("split") })
        )
    }

    private fun renderAudio() {
        addWideCard(
            "DRIVER AUDIO", "지정 앱 → 운전석 전용 오디오",
            "앱/UID 선택과 출력 장치/권한 상태를 함께 확인합니다. 순정 안전 경고의 우선권은 건드리지 않습니다.",
            listOf(
                Action("앱 추가 / 연구 설정") { openLegacy("audio_lab") },
                Action("운전석 경로 비교 테스트") { openLegacy("safety") }
            )
        )
        addInfo(
            "현재 원칙",
            "DolphinAssistant 자체 TTS의 NAV/STREAM14 경로와 다른 앱의 전체 오디오를 UID 단위로 재라우팅하는 것은 별개의 단계입니다. 실제 권한과 출력 장치가 확인된 경로만 활성화합니다."
        )
    }

    private fun renderAutomation() {
        addWideCard(
            "AUTO STARTER", "시동 시 다중 앱 자동 실행",
            "앱을 여러 개 추가하고 각 앱마다 실행 지연, 미디어 앱 여부, 자동재생 지연을 설정합니다.",
            listOf(Action("+ 앱 추가 / 목록 관리") { openLegacy("boot") })
        )
        addWideCard(
            "SMART SCENARIO", "차량 자동화 규칙",
            "차량 상태를 조건으로 실행되는 사용자 시나리오를 관리합니다.",
            listOf(Action("자동화 규칙 열기") { openLegacy("automation") })
        )
    }

    private fun renderAlerts() {
        addWideCard(
            "VOICE & ALERT", "맞춤형 음성 안내",
            "각 차량 상태별 현재 설정 문구를 직접 확인하고 기본/추천/사용자 문구를 선택·수정·미리듣기합니다.",
            listOf(
                Action("기존 상세 설정") { openLegacy("voice") },
                Action("안전 경고음") { openLegacy("safety") }
            )
        )

        var lastGroup = ""
        VoicePhraseCatalog.all(this).forEach { spec ->
            if (spec.group != lastGroup) {
                lastGroup = spec.group
                content.addView(text(lastGroup, 11f, cyanSoft, true).apply {
                    setPadding(dp(2), dp(7), 0, dp(5))
                })
            }
            addVoicePhraseRow(spec)
        }
    }

    private fun addVoicePhraseRow(spec: VoicePhraseSpec) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = gradient(cardBg2, cardBg, dp(13).toFloat(), Color.rgb(11, 68, 84))
        }

        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(spec.label, 12.5f, Color.WHITE, true))
        copy.addView(text("현재  ·  " + spec.current(), 10f, cyan, true).apply {
            setPadding(0, dp(2), 0, 0)
        })
        copy.addView(text("기본  ·  " + spec.defaultPhrase, 8.8f, Color.rgb(151, 180, 190), false).apply {
            setPadding(0, dp(2), 0, 0)
        })
        copy.addView(text("추천  ·  " + spec.recommendedPhrase, 8.8f, Color.rgb(184, 154, 227), false).apply {
            setPadding(0, dp(1), 0, 0)
        })
        card.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val preview = actionButton("미리듣기") { voicePreviewManager.speak(spec.current()) }
        card.addView(preview, LinearLayout.LayoutParams(dp(82), dp(34)).apply {
            setMargins(dp(6), 0, dp(5), 0)
        })

        val edit = actionButton("수정") { showVoicePhraseEditor(spec) }
        card.addView(edit, LinearLayout.LayoutParams(dp(68), dp(34)))

        content.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(6)) })
    }

    private fun showVoicePhraseEditor(spec: VoicePhraseSpec) {
        val current = spec.current()
        val input = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            backgroundTintList = android.content.res.ColorStateList.valueOf(cyan)
            minLines = 2
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        body.addView(text("현재 설정", 9f, cyanSoft, true))
        body.addView(text(current, 12f, Color.WHITE, true).apply {
            setPadding(0, dp(2), 0, dp(7))
        })

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val defaultButton = actionButton("기본 문구") {
            input.setText(spec.defaultPhrase)
            input.setSelection(input.text.length)
            voicePreviewManager.speak(spec.defaultPhrase)
        }
        val recommendedButton = actionButton("추천 문구") {
            input.setText(spec.recommendedPhrase)
            input.setSelection(input.text.length)
            voicePreviewManager.speak(spec.recommendedPhrase)
        }
        presets.addView(defaultButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            setMargins(0, 0, dp(4), 0)
        })
        presets.addView(recommendedButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            setMargins(dp(4), 0, 0, 0)
        })
        body.addView(presets)

        body.addView(text("기본 · " + spec.defaultPhrase, 9f, Color.rgb(151, 180, 190), false).apply {
            setPadding(0, dp(8), 0, 0)
        })
        body.addView(text("추천 · " + spec.recommendedPhrase, 9f, Color.rgb(184, 154, 227), false).apply {
            setPadding(0, dp(2), 0, dp(8))
        })
        body.addView(text("사용자 문구", 9f, cyanSoft, true))
        body.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle(spec.group + " · " + spec.label)
            .setView(body)
            .setNeutralButton("미리듣기", null)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(voicePreviewManager::speak)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newPhrase = input.text.toString().trim()
                if (newPhrase.isEmpty()) return@setOnClickListener
                spec.save(newPhrase)
                dialog.dismiss()
                render(Section.ALERTS)
            }
        }
        dialog.show()
    }

    private fun renderHud() {
        addCardRow(
            featureCard(
                "T900 HUD", "HUD",
                "지원 내비 길안내와 HUD 송신 상태",
                listOf(Action("HUD 설정") { openLegacy("hud") })
            ),
            featureCard(
                "CLUSTER TBT", "계기판",
                "지원 내비 TBT와 계기판 출력 연구",
                listOf(Action("클러스터 설정") { openLegacy("cluster") })
            )
        )
    }

    private fun renderApps() {
        addWideCard(
            "APP DRAWER SHORTCUT", "앱서랍 바로가기 만들기",
            "BYD 런처의 pin shortcut 대신 실제 MAIN+LAUNCHER 슬롯 16개를 사용합니다. 설치 앱 또는 차량 동작을 선택해 앱서랍에 노출합니다.",
            listOf(Action("바로가기 만들기") { openLegacy("button") })
        )
        addWideCard(
            "AUTO START APP", "시동 실행 앱 목록",
            "여러 앱을 등록하고 실행 순서와 0.x초 지연을 각각 설정합니다.",
            listOf(Action("+ 앱 추가") { openLegacy("boot") })
        )
    }

    private fun renderLab() {
        addWideCard(
            "SURROUNDING VISION", "주행 시각화 LAB",
            "속도/기어/방향지시등 LIVE + 확인된 안전 정보만 표시합니다.",
            listOf(Action("DRIVE VIEW") { openDriveView() })
        )
        addWideCard(
            "INTEGRATED LAB", "통합 연구 설정",
            "다운미러 · 실내등 · 디스플레이 · 계기판 · 앱별 오디오 연구 항목을 엽니다.",
            listOf(Action("통합 LAB 열기") { openLegacy("audio_lab") })
        )
    }

    private fun renderLog() {
        addWideCard(
            "DIAGNOSTICS", "로그 / 진단",
            "15분 진단 캡처, 권한, DPI, RAW 차량 신호와 ZIP 추출을 관리합니다.",
            listOf(Action("진단 화면 열기") { openLegacy("dpi") })
        )
    }

    private fun renderSettings() {
        addWideCard(
            "DISPLAY", "화면 / DPI",
            "DPI, 글자 크기, 시스템 권한과 표시 관련 설정을 엽니다.",
            listOf(Action("디스플레이 설정") { openLegacy("dpi") })
        )
        addWideCard(
            "ASSISTANT", "기능 설정",
            "플로팅, 음성, 차량 편의, 자동화 설정으로 이동합니다.",
            listOf(
                Action("플로팅") { openLegacy("button") },
                Action("음성") { openLegacy("voice") },
                Action("자동화") { openLegacy("automation") }
            )
        )
    }

    private fun addHero() {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(13), dp(17), dp(13))
            background = gradient(Color.rgb(5, 36, 52), Color.rgb(5, 22, 39), dp(18).toFloat(), cyan)
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text("좋은 아침입니다.", 17f, Color.WHITE, true))
        copy.addView(text("차량 연결 상태와 자주 쓰는 기능을 한 화면에서 확인하세요.", 10f, Color.rgb(141, 177, 189), false).apply {
            setPadding(0, dp(2), 0, 0)
        })
        hero.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(actionButton("DRIVE VIEW") { openDriveView() })
        content.addView(hero, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(10)) })
    }

    private fun statusTile(parent: LinearLayout, title: String, initial: String, index: Int): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = solid(Color.rgb(5, 21, 33), dp(13).toFloat(), Color.rgb(13, 54, 68))
        }
        box.addView(text(title, 8.5f, Color.rgb(86, 154, 175), true))
        val value = text(initial, 18f, Color.WHITE, true).apply { setPadding(0, dp(2), 0, 0) }
        box.addView(value)
        parent.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            val gap = dp(4)
            setMargins(if (index == 0) 0 else gap, 0, if (index == 2) 0 else gap, 0)
        })
        return value
    }

    private fun featureCard(eyebrow: String, title: String, description: String, actions: List<Action>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = gradient(cardBg2, cardBg, dp(16).toFloat(), Color.rgb(11, 68, 84))
        }
        card.addView(text(eyebrow, 8.5f, cyan, true))
        card.addView(text(title, 15f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, 0) })
        card.addView(text(description, 9.5f, Color.rgb(143, 171, 181), false).apply {
            setPadding(0, dp(4), 0, dp(8))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEachIndexed { i, action ->
            row.addView(actionButton(action.label, action.onClick), LinearLayout.LayoutParams(
                0, dp(34), 1f
            ).apply { setMargins(if (i == 0) 0 else dp(3), 0, if (i == actions.lastIndex) 0 else dp(3), 0) })
        }
        card.addView(row)
        return card
    }

    private fun addCardRow(vararg cards: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cards.forEachIndexed { index, card ->
            row.addView(card, LinearLayout.LayoutParams(0, dp(155), 1f).apply {
                val gap = dp(5)
                setMargins(if (index == 0) 0 else gap, 0, if (index == cards.lastIndex) 0 else gap, 0)
            })
        }
        content.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(10)) })
    }

    private fun addWideCard(eyebrow: String, title: String, description: String, actions: List<Action>) {
        val card = featureCard(eyebrow, title, description, actions)
        content.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(164)
        ).apply { setMargins(0, 0, 0, dp(10)) })
    }

    private fun addInfo(title: String, value: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = solid(Color.rgb(5, 18, 29), dp(13).toFloat(), Color.rgb(15, 58, 72))
        }
        box.addView(text(title, 8.5f, cyanSoft, true))
        box.addView(text(value, 9.5f, Color.rgb(142, 168, 178), false).apply {
            setPadding(0, dp(3), 0, 0)
        })
        content.addView(box)
    }

    private fun actionButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(219, 252, 255))
            setTypeface(typeface, Typeface.BOLD)
            background = gradient(Color.rgb(5, 60, 76), Color.rgb(4, 37, 52), dp(10).toFloat(), Color.rgb(0, 153, 183))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            setPadding(dp(9), dp(6), dp(9), dp(6))
        }

    private fun pill(label: String, color: Int): TextView =
        text(label, 8.5f, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = solid(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), dp(14).toFloat(), color)
        }

    private fun showFloatingNow() {
        if (AdbPermissionManager.isOverlayGranted(this)) {
            FloatingOverlayManager.show(this)
        } else {
            AdbPermissionManager.openOverlaySettings(this)
        }
    }

    private fun openLegacy(panel: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("open_panel", panel)
        })
    }

    private fun openDriveView() {
        startActivity(Intent(this, DriveVisionActivity::class.java))
    }

    private fun startAssistantService() {
        runCatching {
            val serviceIntent = Intent(this, DolphinService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            if (AdbPermissionManager.isOverlayGranted(this)) {
                FloatingOverlayManager.show(this)
            }
        }.onFailure { DolphinLogger.e("COMMAND_CENTER", "service start failed", it) }
    }

    private fun startTelemetry() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                frame = runCatching { driveReader.read() }.getOrDefault(DriveFrame())
                nowPlaying = runCatching { nowPlayingManager.snapshot() }.getOrDefault(NowPlayingSnapshot())
                withContext(Dispatchers.Main) {
                    headerStatus.text =
                        "LIVE  ·  ${frame.speedKmh?.toInt()?.toString()?.plus(" km/h") ?: "NO SPEED"}" +
                            "  ·  GEAR ${frame.gear ?: "--"}  ·  ${BuildConfig.VERSION_NAME}"
                    refreshTelemetryLabels()
                    nowPlayingCard?.bind(nowPlaying)
                }
                delay(1_000L)
            }
        }
    }

    private fun refreshTelemetryLabels() {
        speedValue?.text = frame.speedKmh?.toInt()?.toString()?.plus(" km/h") ?: "--"
        gearValue?.text = frame.gear ?: "--"
        turnValue?.text = frame.turn
    }

    private fun startAutomaticPermissionRecovery() {
        AdbPermissionManager.autoGrantPermissionsOnLaunch(this) { success, message ->
            DolphinLogger.i("COMMAND_CENTER", "automatic permission recovery success=$success message=$message")
            if (!success) {
                headerStatus.text = "권한 자동 복구 일부 실패 · " + message
            }
        }
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
            setLineSpacing(dp(1).toFloat(), 1f)
        }

    private fun solid(color: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun gradient(start: Int, end: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radius
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
