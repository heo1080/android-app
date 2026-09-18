package com.byd.dolphin.autoassistant.manager

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.byd.dolphin.autoassistant.R

/**
 * v30.7 UI Architecture Reset.
 *
 * The existing v30.6.3 feature screens remain intact and keep their proven logic.
 * This shell reorganises them into an automotive landscape information architecture:
 * Home / Vehicle / Drive & Voice / Screen & Integration / Automation / LAB.
 *
 * Research status is explicit: VERIFIED, BETA, LAB.  Unverified research never looks
 * like a production-ready vehicle control.
 */
object V307UiShell {
    private const val TAG = "V307UiShell"
    private const val SHELL_TAG = "v30_7_ui_shell"

    private val BG = Color.parseColor("#070B10")
    private val RAIL = Color.parseColor("#0C1219")
    private val SURFACE = Color.parseColor("#111922")
    private val SURFACE_2 = Color.parseColor("#16212C")
    private val TEXT = Color.parseColor("#F4F8FB")
    private val MUTED = Color.parseColor("#8FA2B4")
    private val ACCENT = Color.parseColor("#39CFF5")
    private val GREEN = Color.parseColor("#45D48A")
    private val AMBER = Color.parseColor("#FFC857")
    private val RED = Color.parseColor("#FF6B6B")
    private val DIVIDER = Color.parseColor("#22303D")

    private data class LegacyLinks(
        val split: View?,
        val comfort: View?,
        val voice: View?,
        val safetyAudio: View?,
        val hud: View?,
        val cluster: View?,
        val shortcuts: View?,
        val automation: View?,
        val boot: View?,
        val display: View?
    )

    private var links: LegacyLinks? = null
    private var currentPage = "home"
    private val navViews = linkedMapOf<String, TextView>()

    fun attach(activity: AppCompatActivity, audioManager: VoiceAndSoundManager) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(SHELL_TAG) != null) return
        val originalRoot = content.getChildAt(0) ?: return
        val dashboard = activity.findViewById<View>(R.id.layoutMainDashboard) as? ViewGroup ?: return

        // Capture existing navigation targets before the old dashboard cards are removed.
        links = LegacyLinks(
            split = activity.findViewById(R.id.cardMenuSplit),
            comfort = activity.findViewById(R.id.cardMenuSeat),
            voice = activity.findViewById(R.id.cardMenuVoice),
            safetyAudio = activity.findViewById(R.id.cardMenuSafetyAudio),
            hud = activity.findViewById(R.id.cardMenuHud),
            cluster = activity.findViewById(R.id.cardMenuCluster),
            shortcuts = activity.findViewById(R.id.cardMenuButtonBuilder),
            automation = activity.findViewById(R.id.cardMenuAutomation),
            boot = activity.findViewById(R.id.cardMenuBootScheduler),
            display = activity.findViewById(R.id.cardMenuDpiAdb)
        )

        val shell = LinearLayout(activity).apply {
            tag = SHELL_TAG
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }

        content.removeView(originalRoot)
        shell.addView(buildRail(activity, dashboard, audioManager), LinearLayout.LayoutParams(dp(activity, 176), ViewGroup.LayoutParams.MATCH_PARENT))
        shell.addView(originalRoot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        content.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        currentPage = "home"
        renderPage(activity, dashboard, audioManager, currentPage)
        DolphinLogger.i(TAG, "v30.7 automotive shell attached")
    }

    private fun buildRail(activity: AppCompatActivity, dashboard: ViewGroup, audioManager: VoiceAndSoundManager): View {
        navViews.clear()
        val rail = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 16), dp(activity, 14), dp(activity, 14))
            setBackgroundColor(RAIL)
        }

        val monogram = TextView(activity).apply {
            text = "DA"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(BG)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = rounded(ACCENT, dp(activity, 14).toFloat())
        }
        rail.addView(monogram, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        rail.addView(TextView(activity).apply {
            text = "DolphinAssistant"
            textSize = 14f
            setTextColor(TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(activity, 9), 0, 0)
        })
        rail.addView(TextView(activity).apply {
            text = "v30.7 UI RESET"
            textSize = 9f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(activity, 2), 0, dp(activity, 18))
        })

        addNav(activity, rail, dashboard, audioManager, "home", "홈")
        addNav(activity, rail, dashboard, audioManager, "vehicle", "차량")
        addNav(activity, rail, dashboard, audioManager, "drive", "주행 & 음성")
        addNav(activity, rail, dashboard, audioManager, "screen", "화면 & 연동")
        addNav(activity, rail, dashboard, audioManager, "automation", "자동화")
        addNav(activity, rail, dashboard, audioManager, "lab", "LAB / 진단")

        rail.addView(View(activity).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1)).apply {
            topMargin = dp(activity, 14)
            bottomMargin = dp(activity, 14)
        })
        rail.addView(TextView(activity).apply {
            text = "DiLink 3.0\nKOREA DOLPHIN"
            textSize = 10f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rail.addView(TextView(activity).apply {
            text = "RESEARCH SYNC"
            textSize = 9f
            setTextColor(GREEN)
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 8), 0, 0)
        })
        return rail
    }

    private fun addNav(
        activity: AppCompatActivity,
        rail: LinearLayout,
        dashboard: ViewGroup,
        audioManager: VoiceAndSoundManager,
        key: String,
        label: String
    ) {
        val item = TextView(activity).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(if (key == currentPage) TEXT else MUTED)
            setPadding(dp(activity, 14), 0, dp(activity, 8), 0)
            background = navBackground(key == currentPage)
            setOnClickListener {
                currentPage = key
                showDashboardRoot(activity)
                renderPage(activity, dashboard, audioManager, key)
                refreshNavState()
            }
        }
        navViews[key] = item
        rail.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 50)).apply {
            bottomMargin = dp(activity, 7)
        })
    }

    private fun refreshNavState() {
        navViews.forEach { (key, view) ->
            val selected = key == currentPage
            view.setTextColor(if (selected) TEXT else MUTED)
            view.background = navBackground(selected)
        }
    }

    private fun showDashboardRoot(activity: AppCompatActivity) {
        val dashboard = activity.findViewById<View>(R.id.layoutMainDashboard)
        val subIds = intArrayOf(
            R.id.subLayoutSplit,
            R.id.subLayoutComfortV30,
            R.id.subLayoutVoice,
            R.id.subLayoutSafetyAudio,
            R.id.subLayoutHud,
            R.id.subLayoutCluster,
            R.id.subLayoutButtonBuilder,
            R.id.subLayoutAutomation,
            R.id.subLayoutBootScheduler,
            R.id.subLayoutDpiAdb
        )
        dashboard?.visibility = View.VISIBLE
        subIds.forEach { id -> activity.findViewById<View>(id)?.visibility = View.GONE }
    }

    private fun renderPage(activity: AppCompatActivity, dashboard: ViewGroup, audioManager: VoiceAndSoundManager, page: String) {
        dashboard.removeAllViews()
        dashboard.setBackgroundColor(BG)

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(BG)
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 26), dp(activity, 20), dp(activity, 26), dp(activity, 28))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dashboard.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        addTopStatus(activity, body, page)
        when (page) {
            "vehicle" -> renderVehicle(activity, body, audioManager)
            "drive" -> renderDrive(activity, body, audioManager)
            "screen" -> renderScreen(activity, body, audioManager)
            "automation" -> renderAutomation(activity, body)
            "lab" -> renderLab(activity, body, audioManager)
            else -> renderHome(activity, body, audioManager)
        }
    }

    private fun addTopStatus(activity: AppCompatActivity, body: LinearLayout, page: String) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = when (page) {
            "vehicle" -> "차량"
            "drive" -> "주행 & 음성"
            "screen" -> "화면 & 연동"
            "automation" -> "자동화"
            "lab" -> "LAB / 진단"
            else -> "홈"
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 26f
                setTextColor(TEXT)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = "DolphinAssistant · DiLink 3.0"
                textSize = 11f
                setTextColor(MUTED)
                setPadding(0, dp(activity, 2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip(activity, "통합 연구 반영", GREEN))
        body.addView(row)

        // Keep the original IDs alive because MainActivity updates them asynchronously.
        body.addView(TextView(activity).apply {
            id = R.id.tvServiceStatusBadge
            text = "서비스 상태 확인 중"
            textSize = 11f
            setTextColor(GREEN)
            setPadding(0, dp(activity, 13), 0, 0)
        })
        body.addView(TextView(activity).apply {
            id = R.id.tvMainCardDpiDesc
            text = "디스플레이 상태 확인 중"
            textSize = 10f
            setTextColor(MUTED)
            setPadding(0, dp(activity, 3), 0, dp(activity, 14))
        })
    }

    private fun renderHome(activity: AppCompatActivity, body: LinearLayout, audioManager: VoiceAndSoundManager) {
        val hero = panel(activity)
        hero.addView(TextView(activity).apply {
            text = "운전에 필요한 기능만 빠르게.\n연구 기능은 안전하게 분리."
            textSize = 22f
            setTextColor(TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        hero.addView(TextView(activity).apply {
            text = "v30.6.3의 실차 기능은 그대로 유지하고, 매시간 BYD 통합 연구 결과를 VERIFIED · BETA · LAB 상태로 분리해 반영합니다."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(activity, 8), 0, 0)
        })
        body.addView(hero)

        section(activity, body, "빠른 상태")
        addTwoCards(activity, body,
            actionCard(activity, "2분할", "DiLink 3 검증 · 20–80% · 1% 조절", "VERIFIED", GREEN) { openLegacy(activity, links?.split) },
            actionCard(activity, "차량 편의", "앞좌석 열선 · 핸들 열선", "VERIFIED", GREEN) { openLegacy(activity, links?.comfort) }
        )
        addTwoCards(activity, body,
            actionCard(activity, "음성 & 경고", "기어 · EPB · 스노우 + 실차 추적 항목", "MIXED", AMBER) { openLegacy(activity, links?.voice) },
            actionCard(activity, "플로팅 & 바로가기", "8개 표시 · 9개부터 가로 스크롤", "ACTIVE", ACCENT) { openLegacy(activity, links?.shortcuts) }
        )

        section(activity, body, "통합 연구 상태")
        val research = panel(activity)
        research.addView(statusLine(activity, "운전석 전용 오디오", "앱 자체 stream14는 사용 · 타 앱 강제 라우팅은 추가 검증", "LAB", AMBER))
        research.addView(statusLine(activity, "다운미러", "R 연동 + raw 0..8 캘리브레이션 경로", "BETA", AMBER))
        research.addView(statusLine(activity, "계기판 / TBT", "VirtualDisplay · TBT 데이터 경로 연구", "LAB", AMBER))
        research.addView(statusLine(activity, "실내등", "도어연동과 raw 0..4 추적 · 개별 램프 매핑 연구", "LAB", AMBER))
        research.addView(statusLine(activity, "디스플레이 프로파일", "logical size + density + fontScale 분리", "BETA", AMBER))
        body.addView(research)

        section(activity, body, "바로 실행")
        addTwoCards(activity, body,
            actionCard(activity, "맞춤 음성", "항목별 안내 설정과 미리듣기", "OPEN", ACCENT) { openLegacy(activity, links?.voice) },
            actionCard(activity, "실차 LAB", "미러 · 실내등 · 오디오 · 계기판 · 디스플레이", "LAB", AMBER) { IntegratedBetaUi.openLab(activity, audioManager) }
        )
    }

    private fun renderVehicle(activity: AppCompatActivity, body: LinearLayout, audioManager: VoiceAndSoundManager) {
        body.addView(infoBanner(activity, "검증된 차량 제어는 일반 메뉴에, 실차 캘리브레이션이 필요한 기능은 BETA/LAB에 둡니다."))
        section(activity, body, "편의 기능")
        addTwoCards(activity, body,
            actionCard(activity, "앞좌석 · 핸들 열선", "운전석 / 조수석 2단 · 핸들 열선", "VERIFIED", GREEN) { openLegacy(activity, links?.comfort) },
            actionCard(activity, "다운미러", "후진 연동 · normal / reverse 위치 학습", "BETA", AMBER) { IntegratedBetaUi.openLab(activity, audioManager) }
        )
        addTwoCards(activity, body,
            actionCard(activity, "실내등", "도어 연동 · raw 캘리브레이션", "LAB", AMBER) { IntegratedBetaUi.openLab(activity, audioManager) },
            actionCard(activity, "메모리 시트", "정밀 모터 좌표 API 확인 전까지 진단 전용", "LAB", AMBER) { IntegratedBetaUi.openLab(activity, audioManager) }
        )
    }

    private fun renderDrive(activity: AppCompatActivity, body: LinearLayout, audioManager: VoiceAndSoundManager) {
        body.addView(infoBanner(activity, "차선이탈 추가 경고는 제거하고 순정 경고를 유지합니다. 맞춤 안내는 사용자가 필요한 항목만 켤 수 있게 유지합니다."))
        section(activity, body, "음성 안내")
        body.addView(actionCard(activity, "음성 · 경고 출력 스튜디오", "모든 항목 공통: OFF / 비프 / TTS · 비프 종류 · 기본/추천/커스텀 문구 · 남/녀 음성 프리셋", "NEW", GREEN) { AlertOutputConfigUi.show(activity, audioManager) })
        addTwoCards(activity, body,
            actionCard(activity, "주행 음성", "P/R/N/D · 회생 · ECO/NORMAL/SPORT · Snow · AutoHold · EPB · ICC", "ACTIVE", ACCENT) { AlertOutputConfigUi.show(activity, audioManager) },
            actionCard(activity, "안전 경고", "BSD 방향 경고 · 전방차량출발", "BETA", AMBER) { AlertOutputConfigUi.show(activity, audioManager) }
        )
        section(activity, body, "오디오 출력")
        body.addView(actionCard(activity, "운전석 전용 경로", "DolphinAssistant 자체 안내는 stream14 기반. 다른 앱의 UID 오디오 라우팅은 MODIFY_AUDIO_ROUTING 및 실제 BYD output-device 확인이 필요합니다.", "LAB", AMBER) {
            openLegacy(activity, links?.safetyAudio)
        })
    }

    private fun renderScreen(activity: AppCompatActivity, body: LinearLayout, audioManager: VoiceAndSoundManager) {
        section(activity, body, "화면")
        addTwoCards(activity, body,
            actionCard(activity, "2분할", "실차 검증 2앱 · 20–80% · 1% 조절", "VERIFIED", GREEN) { openLegacy(activity, links?.split) },
            actionCard(activity, "플로팅 독 · 바로가기", "앱 · 퀵 기능 · 차량 기능 바로가기", "ACTIVE", ACCENT) { openLegacy(activity, links?.shortcuts) }
        )
        addTwoCards(activity, body,
            actionCard(activity, "HUD", "TMAP 계열 + 타 내비 안내 파서/출력 경로", "BETA", AMBER) { openLegacy(activity, links?.hud) },
            actionCard(activity, "계기판 / TBT", "순정 유지 + TBT · 미러링 연구", "LAB", AMBER) { openLegacy(activity, links?.cluster) }
        )
        section(activity, body, "디스플레이")
        addTwoCards(activity, body,
            actionCard(activity, "화면 프로파일", "DPI 단독이 아닌 size · density · fontScale 분리", "BETA", AMBER) { openLegacy(activity, links?.display) },
            actionCard(activity, "3 · 4 분할 / VirtualDisplay", "DiLink 3 기본 2분할 밖의 경로는 연구 기능", "LAB", AMBER) { IntegratedBetaUi.openLab(activity, audioManager) }
        )
    }

    private fun renderAutomation(activity: AppCompatActivity, body: LinearLayout) {
        body.addView(infoBanner(activity, "시동/READY 이후 앱 실행, 미디어 복원, 차량 상태 기반 동작을 한 곳에서 관리하는 방향으로 통합합니다."))
        section(activity, body, "시동 자동화")
        addTwoCards(activity, body,
            actionCard(activity, "앱 자동 실행", "여러 앱 순차 실행 · 앱별 지연", "ACTIVE", ACCENT) { openLegacy(activity, links?.boot) },
            actionCard(activity, "미디어 자동 재생", "미디어 앱 인식 · 재생 지연 · 세션 복원", "BETA", AMBER) { openLegacy(activity, links?.boot) }
        )
        section(activity, body, "주행 시나리오")
        body.addView(actionCard(activity, "차량 자동화", "후진/기어/상태 기반 자동 동작과 사용자 시나리오", "ACTIVE", ACCENT) { openLegacy(activity, links?.automation) })
    }

    private fun renderLab(activity: AppCompatActivity, body: LinearLayout, audioManager: VoiceAndSoundManager) {
        body.addView(infoBanner(activity, "LAB은 차량 상태 확인과 가역적인 실차 테스트만 제공합니다. 검증 전 기능을 일반 설정처럼 표시하지 않습니다."))
        section(activity, body, "통합 LAB")
        body.addView(actionCard(activity, "실차 통합 LAB 열기", "다운미러 · 실내등 · 앱별 오디오 · 계기판 · 디스플레이 프로브", "LAB", AMBER) {
            IntegratedBetaUi.openLab(activity, audioManager)
        })
        addTwoCards(activity, body,
            actionCard(activity, "진단 & DPI", "디스플레이 metrics · 권한 · 진단 ZIP", "DIAG", ACCENT) { openLegacy(activity, links?.display) },
            actionCard(activity, "오디오 라우팅 연구", "0xAA000221 후보 · NAVI/Media 상태 + output-device 추적", "LAB", AMBER) { openLegacy(activity, links?.safetyAudio) }
        )
        addTwoCards(activity, body,
            actionCard(activity, "계기판 연구", "VirtualDisplay / TBT / 안전 복귀 경로", "LAB", AMBER) { openLegacy(activity, links?.cluster) },
            actionCard(activity, "차량 Feature 연구", "미러 · 실내등 · BSD · 전방차 상태 raw 비교", "LAB", AMBER) { IntegratedBetaUi.openLab(activity, audioManager) }
        )
    }

    private fun openLegacy(activity: AppCompatActivity, target: View?) {
        if (target == null || !target.performClick()) {
            Toast.makeText(activity, "기존 기능 화면 연결을 찾지 못했습니다. LAB 진단을 확인하세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun section(activity: AppCompatActivity, body: LinearLayout, title: String) {
        body.addView(TextView(activity).apply {
            text = title
            textSize = 14f
            setTextColor(TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(activity, 20), 0, dp(activity, 9))
        })
    }

    private fun addTwoCards(activity: AppCompatActivity, body: LinearLayout, first: View, second: View) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(activity, 6) })
        row.addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(activity, 6) })
        body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(activity, 12) })
    }

    private fun actionCard(
        activity: AppCompatActivity,
        title: String,
        description: String,
        status: String,
        statusColor: Int,
        onClick: () -> Unit
    ): CardView {
        val card = CardView(activity).apply {
            radius = dp(activity, 16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(SURFACE)
            foreground = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
                val d = getDrawable(0); recycle(); d
            }
            setOnClickListener { onClick() }
        }
        val inner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 15), dp(activity, 16), dp(activity, 15))
        }
        inner.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 15f
                setTextColor(TEXT)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(activity, status, statusColor))
        })
        inner.addView(TextView(activity).apply {
            text = description
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(activity, 9), 0, 0)
            setLineSpacing(0f, 1.15f)
        })
        card.addView(inner)
        return card
    }

    private fun panel(activity: AppCompatActivity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 18), dp(activity, 17), dp(activity, 18), dp(activity, 17))
        background = rounded(SURFACE_2, dp(activity, 18).toFloat(), DIVIDER)
    }

    private fun infoBanner(activity: AppCompatActivity, text: String): View = TextView(activity).apply {
        this.text = text
        textSize = 11f
        setTextColor(MUTED)
        setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
        background = rounded(SURFACE_2, dp(activity, 12).toFloat(), DIVIDER)
    }

    private fun statusLine(activity: AppCompatActivity, title: String, desc: String, status: String, color: Int): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = title
                    textSize = 12f
                    setTextColor(TEXT)
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    text = desc
                    textSize = 10f
                    setTextColor(MUTED)
                    setPadding(0, dp(activity, 2), dp(activity, 8), 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(activity, status, color))
        }
    }

    private fun chip(activity: AppCompatActivity, text: String, color: Int): TextView = TextView(activity).apply {
        this.text = text
        textSize = 9f
        setTextColor(color)
        gravity = Gravity.CENTER
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(activity, 9), dp(activity, 5), dp(activity, 9), dp(activity, 5))
        background = rounded(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)), dp(activity, 12).toFloat(), color)
    }

    private fun navBackground(selected: Boolean): GradientDrawable {
        return rounded(if (selected) Color.parseColor("#142633") else Color.TRANSPARENT, 14f, if (selected) Color.parseColor("#23566A") else Color.TRANSPARENT)
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        stroke?.takeIf { it != Color.TRANSPARENT }?.let { setStroke(1, it) }
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
