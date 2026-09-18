package com.byd.dolphin.autoassistant.manager

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.BuildConfig
import com.byd.dolphin.autoassistant.hud.ClusterMirrorManager
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

object V31RootUi {
    private const val ROOT_TAG = "v31_full_root"
    private const val HOME = "home"
    private const val DRIVE = "drive"
    private const val VEHICLE = "vehicle"
    private const val AUDIO = "audio"
    private const val SCREEN = "screen"
    private const val AUTO = "automation"
    private const val LAB = "lab"

    private val BG = Color.rgb(5, 10, 15)
    private val RAIL = Color.rgb(9, 16, 23)
    private val SURFACE = Color.rgb(15, 24, 33)
    private val SURFACE2 = Color.rgb(19, 31, 42)
    private val SURFACE3 = Color.rgb(24, 39, 52)
    private val TEXT = Color.rgb(241, 247, 250)
    private val MUTED = Color.rgb(139, 158, 175)
    private val CYAN = Color.rgb(59, 207, 245)
    private val GREEN = Color.rgb(69, 212, 138)
    private val AMBER = Color.rgb(255, 200, 87)
    private val RED = Color.rgb(255, 107, 107)
    private val DIVIDER = Color.rgb(34, 49, 63)

    private var page = HOME
    private var activityRef: WeakReference<AppCompatActivity>? = null
    private var audioRef: WeakReference<VoiceAndSoundManager>? = null
    private var hostRef: WeakReference<FrameLayout>? = null
    private val nav = linkedMapOf<String, Pair<TextView, TextView>>()

    fun attach(activity: AppCompatActivity, audioManager: VoiceAndSoundManager) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing = content.findViewWithTag<View>(ROOT_TAG)
        if (existing != null) {
            activityRef = WeakReference(activity)
            audioRef = WeakReference(audioManager)
            render()
            return
        }

        activityRef = WeakReference(activity)
        audioRef = WeakReference(audioManager)

        // Proven legacy controllers keep their view references, but the old UI is never visible.
        for (i in 0 until content.childCount) content.getChildAt(i).visibility = View.GONE

        val root = LinearLayout(activity).apply {
            tag = ROOT_TAG
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }
        val host = FrameLayout(activity).apply { setBackgroundColor(BG) }
        hostRef = WeakReference(host)

        root.addView(buildRail(activity),
            LinearLayout.LayoutParams(dp(activity, 188), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(host,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        content.addView(root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        page = HOME
        render()
        DolphinLogger.i("V31_UI", "full root attached " + BuildConfig.VERSION_NAME)
    }

    fun refresh(activity: AppCompatActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.findViewWithTag<View>(ROOT_TAG) ?: return
        root.visibility = View.VISIBLE
        render()
    }

    fun handleBack(activity: AppCompatActivity): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (content.findViewWithTag<View>(ROOT_TAG) == null) return false
        if (page == HOME) return false
        navigate(HOME)
        return true
    }

    private fun buildRail(activity: AppCompatActivity): View {
        nav.clear()
        val rail = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 16), dp(activity, 14), dp(activity, 14))
            setBackgroundColor(RAIL)
        }

        rail.addView(TextView(activity).apply {
            text = "DA"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(BG)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = round(CYAN, 15f)
        }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        rail.addView(TextView(activity).apply {
            text = "DolphinAssistant"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(activity, 8), 0, 0)
        })
        rail.addView(TextView(activity).apply {
            text = "v31 · FULL REBUILD"
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(CYAN)
            setPadding(0, dp(activity, 2), 0, dp(activity, 18))
        })

        addNav(activity, rail, HOME, "홈", "HOME")
        addNav(activity, rail, DRIVE, "주행", "DRIVE")
        addNav(activity, rail, VEHICLE, "차량", "VEHICLE")
        addNav(activity, rail, AUDIO, "오디오 · 경고", "AUDIO")
        addNav(activity, rail, SCREEN, "내비 · 화면", "DISPLAY")
        addNav(activity, rail, AUTO, "자동화", "AUTO")
        addNav(activity, rail, LAB, "LAB · 진단", "LAB")

        rail.addView(View(activity).apply { setBackgroundColor(DIVIDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1)).apply {
                topMargin = dp(activity, 10)
                bottomMargin = dp(activity, 12)
            })

        rail.addView(TextView(activity).apply {
            text = "KOREA DOLPHIN\nDiLink 3.0"
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        rail.addView(TextView(activity).apply {
            text = "RESEARCH SYNC"
            textSize = 8f
            gravity = Gravity.CENTER
            setTextColor(GREEN)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        return rail
    }

    private fun addNav(
        activity: AppCompatActivity,
        rail: LinearLayout,
        key: String,
        title: String,
        eyebrow: String
    ) {
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), 0, dp(activity, 8), 0)
            background = navBg(key == page)
            setOnClickListener { navigate(key) }
        }
        val small = TextView(activity).apply {
            text = eyebrow
            textSize = 7f
            setTextColor(if (key == page) CYAN else MUTED)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        val label = TextView(activity).apply {
            text = title
            textSize = 13f
            setTextColor(if (key == page) TEXT else MUTED)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        wrap.addView(small)
        wrap.addView(label)
        nav[key] = label to small
        rail.addView(wrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52)).apply {
                bottomMargin = dp(activity, 6)
            })
    }

    private fun navigate(target: String) {
        page = target
        nav.forEach { (key, pair) ->
            val selected = key == page
            pair.first.setTextColor(if (selected) TEXT else MUTED)
            pair.second.setTextColor(if (selected) CYAN else MUTED)
            (pair.first.parent as? View)?.background = navBg(selected)
        }
        render()
    }

    private fun render() {
        val activity = activityRef?.get() ?: return
        val audio = audioRef?.get() ?: return
        val host = hostRef?.get() ?: return
        host.removeAllViews()

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(BG)
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 28), dp(activity, 20), dp(activity, 28), dp(activity, 32))
        }
        scroll.addView(body,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        host.addView(scroll,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        header(activity, body)
        when (page) {
            DRIVE -> drivePage(activity, body)
            VEHICLE -> vehiclePage(activity, body)
            AUDIO -> audioPage(activity, body, audio)
            SCREEN -> screenPage(activity, body)
            AUTO -> automationPage(activity, body)
            LAB -> labPage(activity, body)
            else -> homePage(activity, body)
        }
    }

    private fun header(activity: AppCompatActivity, body: LinearLayout) {
        val title = when (page) {
            DRIVE -> "주행"
            VEHICLE -> "차량"
            AUDIO -> "오디오 · 경고"
            SCREEN -> "내비 · 화면"
            AUTO -> "자동화"
            LAB -> "LAB · 진단"
            else -> "홈"
        }
        val sub = when (page) {
            AUDIO -> "모든 비프와 TTS 설정은 이 화면에서만 관리"
            DRIVE -> "주행 감지 상태 · 소리 설정은 중복하지 않음"
            VEHICLE -> "검증된 차량 편의 기능만 직접 제어"
            SCREEN -> "분할 · 플로팅 · HUD · 계기판"
            AUTO -> "시동 앱 · 미디어 · 차량 자동화"
            LAB -> "미확인 기능과 실차 진단을 일반 메뉴와 분리"
            else -> "한 기능은 한 화면에서만 설정"
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 27f
                setTextColor(TEXT)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = sub
                textSize = 11f
                setTextColor(MUTED)
                setPadding(0, dp(activity, 3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(action(activity, "업데이트 확인", CYAN) {
            AppUpdateManager.checkForUpdates(activity, true)
        })
        body.addView(row)
        body.addView(TextView(activity).apply {
            text = BuildConfig.VERSION_NAME
            textSize = 9f
            gravity = Gravity.END
            setTextColor(MUTED)
            setPadding(0, dp(activity, 6), 0, dp(activity, 14))
        })
    }

    private fun homePage(activity: AppCompatActivity, body: LinearLayout) {
        body.addView(panel(activity).apply {
            addView(TextView(activity).apply {
                text = "운전 중에는 빠르게.\n설정할 때는 한 곳에서."
                textSize = 23f
                setTextColor(TEXT)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(bodyText(activity,
                "기존 v30.x UI는 완전히 숨기고 새 루트만 표시합니다. 차량 제어와 서비스 로직은 뒤에서 그대로 유지합니다.").apply {
                setPadding(0, dp(activity, 8), 0, 0)
            })
        })

        section(activity, body, "빠른 상태")
        grid2(activity, body,
            statusCard(activity, "차량 편의", "앞좌석 열선 · 핸들 열선", "VERIFIED", GREEN, VEHICLE),
            statusCard(activity, "BSD", "현 실차 반응 경로 보존", "BETA", AMBER, DRIVE))
        grid2(activity, body,
            statusCard(activity, "오디오 · 경고", "OFF / 비프 / TTS · 음성 10종", "ACTIVE", CYAN, AUDIO),
            statusCard(activity, "2분할", "DiLink 3 · 20–80%", "VERIFIED", GREEN, SCREEN))

        section(activity, body, "구성 원칙")
        body.addView(info(activity, "일반 메뉴",
            "실차에서 반복 확인되었거나 가역적으로 안전한 기능만 표시", GREEN))
        body.addView(info(activity, "BETA",
            "BSD · 전방차량출발 · NORMAL/AutoHold 보정처럼 추가 실차 확인이 필요한 기능", AMBER))
        body.addView(info(activity, "LAB",
            "실내등 · 다운미러 · 계기판 CAN/TBT · radar track · 타 앱 운전석 오디오", RED))
        body.addView(panel(activity).apply {
            addView(bodyText(activity,
                "• 같은 설정을 여러 메뉴에 반복하지 않음\n" +
                "• Android 기본 Spinner를 사용하지 않음\n" +
                "• LAB 기능은 일반 차량 제어 화면에 노출하지 않음\n" +
                "• 새 버전은 앱 안의 업데이트 확인으로 설치"))
        })
    }

    private fun drivePage(activity: AppCompatActivity, body: LinearLayout) {
        body.addView(banner(activity,
            "이 화면은 감지/동작 상태만 담당합니다. 소리 종류·문구·목소리는 ‘오디오 · 경고’ 한 곳에서만 설정합니다."))

        section(activity, body, "주행 상태")
        grid2(activity, body,
            statusCard(activity, "기어 · 회생제동", "P/R/N/D · STANDARD/HIGH", "ACTIVE", CYAN, AUDIO),
            statusCard(activity, "주행모드", "ECO/SPORT 실차 반응 · NORMAL 0/3 보정", "BETA", AMBER, AUDIO))
        grid2(activity, body,
            statusCard(activity, "오토홀드", "AVH + 속도/브레이크/가속 상태머신", "BETA", AMBER, AUDIO),
            statusCard(activity, "BSD", "방향 깜박이 + BSD 상태 변화 경고", "BETA", AMBER, AUDIO))
        grid2(activity, body,
            statusCard(activity, "전방차량출발", "현재 전방 주차센서 기반 보조 감지", "BETA", AMBER, AUDIO),
            statusCard(activity, "ICC", "TJA/ICC 후보 상태 추적", "BETA", AMBER, AUDIO))

        section(activity, body, "경고 정책")
        body.addView(info(activity, "차선이탈",
            "순정 경고 사용 · DolphinAssistant 중복 경고 없음", GREEN))
        body.addView(info(activity, "전방 radar track",
            "0x280–0x289 후보는 한국 Dolphin 미확인 · LAB에서 read-only correlation만", RED))
    }

    private fun vehiclePage(activity: AppCompatActivity, body: LinearLayout) {
        body.addView(banner(activity,
            "한국형 Dolphin 실제 장착 사양 기준: 앞좌석 열선 + 핸들 열선. 통풍/뒷좌석 열선 메뉴는 만들지 않습니다."))

        section(activity, body, "운전석 열선")
        body.addView(heatControl(activity, "운전석",
            VehicleComfortManager.getSeatHeatingLevel(activity, VehicleComfortManager.SEAT_DRIVER)) {
            VehicleComfortManager.setSeatHeatingLevel(activity, VehicleComfortManager.SEAT_DRIVER, it)
            delayedRender(activity)
        })

        section(activity, body, "동승석 열선")
        body.addView(heatControl(activity, "동승석",
            VehicleComfortManager.getSeatHeatingLevel(activity, VehicleComfortManager.SEAT_PASSENGER)) {
            VehicleComfortManager.setSeatHeatingLevel(activity, VehicleComfortManager.SEAT_PASSENGER, it)
            delayedRender(activity)
        })

        section(activity, body, "핸들 · 공조")
        grid2(activity, body,
            toggleCard(activity, "핸들 열선",
                VehicleComfortManager.isSteeringWheelHeatingOn(activity) == true, "VERIFIED", GREEN) {
                VehicleComfortManager.setSteeringWheelHeating(activity, it)
            },
            toggleCard(activity, "공조 전원",
                VehicleComfortManager.isAcOn(activity) == true, "VERIFIED", GREEN) {
                VehicleComfortManager.setAcPower(activity, it)
            })

        section(activity, body, "연구 중인 차량 기능")
        grid2(activity, body,
            statusCard(activity, "실내등", "현재 ON/OFF actuator 경로 실차 미동작", "LAB", RED, LAB),
            statusCard(activity, "다운미러", "현재 mirror angle setter 실차 미동작", "LAB", RED, LAB))
        body.addView(statusCard(activity, "메모리 시트",
            "정확한 위치 getter/setter 확정 전 제어 금지", "LAB", RED, LAB))
    }

    private fun audioPage(activity: AppCompatActivity, body: LinearLayout, audio: VoiceAndSoundManager) {
        body.addView(banner(activity,
            "여기가 유일한 소리 설정 화면입니다. OFF / 비프 / TTS를 선택하면 필요한 옵션만 펼쳐집니다."))

        section(activity, body, "기본 TTS 음성")
        val globalVoice = AlertOutputProfileManager.getGlobalVoiceId(activity)
        body.addView(panel(activity).apply {
            addView(bodyText(activity,
                "개별 항목에서 ‘기본 음성’을 선택하면 이 음성을 사용합니다. 선택 즉시 미리듣기합니다."))
            addView(options(activity,
                AlertOutputProfileManager.voicePresets.map { it.id to it.label },
                globalVoice) { id ->
                AlertOutputProfileManager.setGlobalVoiceId(activity, id)
                val voice = AlertOutputProfileManager.getVoice(id)
                InAppSupertonicTtsManager.speak(activity,
                    "DolphinAssistant 음성 미리듣기입니다.", voice.sid, voice.speed)
                render()
            })
        })

        section(activity, body, "항목별 출력")
        AlertOutputProfileManager.events.forEach {
            body.addView(audioCard(activity, audio, it))
        }
    }

    private fun audioCard(
        activity: AppCompatActivity,
        audio: VoiceAndSoundManager,
        spec: AlertOutputProfileManager.EventSpec
    ): View {
        val profile = AlertOutputProfileManager.getProfile(activity, spec.key)
        return panel(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(activity, 12)
            }

            val head = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(title(activity, spec.title),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(chip(activity, profile.mode,
                when (profile.mode) {
                    AlertOutputProfileManager.MODE_OFF -> MUTED
                    AlertOutputProfileManager.MODE_BEEP -> AMBER
                    else -> CYAN
                }))
            addView(head)

            addView(caption(activity, "출력 방식"))
            addView(options(activity,
                listOf(
                    AlertOutputProfileManager.MODE_OFF to "OFF",
                    AlertOutputProfileManager.MODE_BEEP to "비프",
                    AlertOutputProfileManager.MODE_TTS to "TTS"),
                profile.mode) { mode ->
                AlertOutputProfileManager.saveProfile(activity, spec.key, profile.copy(mode = mode))
                render()
            })

            if (profile.mode == AlertOutputProfileManager.MODE_BEEP) {
                addView(caption(activity, "비프 패턴"))
                addView(options(activity,
                    AlertOutputProfileManager.beepPresets.map { it.id to it.label },
                    profile.beepId) { beepId ->
                    AlertOutputProfileManager.saveProfile(activity, spec.key,
                        profile.copy(beepId = beepId))
                    audio.previewConfiguredAlert(spec.key)
                    render()
                })
            }

            if (profile.mode == AlertOutputProfileManager.MODE_TTS) {
                addView(caption(activity, "문구"))
                addView(options(activity,
                    listOf(
                        AlertOutputProfileManager.PHRASE_DEFAULT to "기본",
                        AlertOutputProfileManager.PHRASE_RECOMMENDED to "추천",
                        AlertOutputProfileManager.PHRASE_CUSTOM to "커스텀"),
                    profile.phraseMode) { phraseMode ->
                    AlertOutputProfileManager.saveProfile(activity, spec.key,
                        profile.copy(phraseMode = phraseMode))
                    render()
                })

                if (profile.phraseMode == AlertOutputProfileManager.PHRASE_CUSTOM) {
                    val edit = EditText(activity).apply {
                        setText(profile.customText)
                        hint = spec.customHint
                        setTextColor(TEXT)
                        setHintTextColor(MUTED)
                        textSize = 12f
                        minLines = 2
                        setPadding(dp(activity, 12), dp(activity, 9),
                            dp(activity, 12), dp(activity, 9))
                        background = round(SURFACE3, 12f, DIVIDER)
                    }
                    addView(edit, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(activity, 8)
                    })
                    addView(action(activity, "커스텀 문구 저장", CYAN) {
                        AlertOutputProfileManager.saveProfile(activity, spec.key,
                            profile.copy(customText = edit.text.toString()))
                        Toast.makeText(activity, "문구 저장 완료", Toast.LENGTH_SHORT).show()
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 42)).apply {
                        topMargin = dp(activity, 8)
                    })
                }

                addView(caption(activity, "TTS 음성"))
                val voiceList = listOf(AlertOutputProfileManager.VOICE_GLOBAL to "기본 음성") +
                    AlertOutputProfileManager.voicePresets.map { it.id to it.label }
                addView(options(activity, voiceList, profile.voiceId) { voiceId ->
                    AlertOutputProfileManager.saveProfile(activity, spec.key,
                        profile.copy(voiceId = voiceId))
                    audio.previewConfiguredAlert(spec.key)
                    render()
                })
            }

            if (profile.mode != AlertOutputProfileManager.MODE_OFF) {
                addView(action(activity, "미리듣기", GREEN) {
                    audio.previewConfiguredAlert(spec.key)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 42)).apply {
                    topMargin = dp(activity, 10)
                })
            }
        }
    }

    private fun screenPage(activity: AppCompatActivity, body: LinearLayout) {
        section(activity, body, "2분할")
        body.addView(panel(activity).apply {
            addView(title(activity, "DiLink 3 실차 2분할"))
            addView(bodyText(activity,
                "마지막으로 저장된 2분할 구성을 다시 실행합니다. 3·4분할은 LAB로 분리합니다."))
            addView(action(activity, "최근 2분할 복원", GREEN) {
                SplitScreenManager.restoreLastSplitScreen(activity)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 44)).apply {
                topMargin = dp(activity, 10)
            })
        })

        section(activity, body, "플로팅 독")
        body.addView(toggleCard(activity, "플로팅 독",
            SettingsManager.isFloatingOverlayEnabled(activity), "ACTIVE", CYAN) { enabled ->
            SettingsManager.setFloatingOverlayEnabled(activity, enabled)
            if (enabled && AdbPermissionManager.isOverlayGranted(activity)) {
                FloatingOverlayManager.show(activity)
            } else if (!enabled) {
                FloatingOverlayManager.hide()
            }
        })
        body.addView(slider(activity, "크기",
            SettingsManager.getFloatingScale(activity), 60, 140, "%") {
            SettingsManager.setFloatingScale(activity, it)
            if (SettingsManager.isFloatingOverlayEnabled(activity)) FloatingOverlayManager.show(activity)
        })
        body.addView(slider(activity, "투명도",
            SettingsManager.getFloatingOpacity(activity), 35, 100, "%") {
            SettingsManager.setFloatingOpacity(activity, it)
            if (SettingsManager.isFloatingOverlayEnabled(activity)) FloatingOverlayManager.show(activity)
        })

        section(activity, body, "HUD · 계기판")
        grid2(activity, body,
            statusCard(activity, "HUD / T900", "내비 파싱과 브리지 경로", "BETA", AMBER, LAB),
            toggleCard(activity, "계기판 TBT",
                SettingsManager.isClusterTbtEnabled(activity), "LAB", RED) {
                if (!it) ClusterMirrorManager.clearClusterTbt(activity)
                SettingsManager.setClusterTbtEnabled(activity, it)
            })
        body.addView(info(activity, "계기판 CAN/TBT",
            "0xAA00020F 경로와 순정 TBT correlation은 LAB에서만 연구", RED))

        section(activity, body, "디스플레이")
        body.addView(info(activity, "화면 프로파일",
            "logical size · density · fontScale 분리. DPI 단독 변경은 순정 UI clipping 위험", AMBER))
        body.addView(info(activity, "3·4분할",
            "VirtualDisplay 후보는 DiLink 3 실차 미확인", RED))
    }

    private fun automationPage(activity: AppCompatActivity, body: LinearLayout) {
        body.addView(banner(activity,
            "자동화 설정은 이 화면에서만 관리합니다. 주행/차량 화면에는 같은 토글을 다시 만들지 않습니다."))

        section(activity, body, "시동 자동 실행")
        body.addView(toggleCard(activity, "시동 앱 자동 실행",
            SettingsManager.isBootAutoEnabled(activity), "ACTIVE", CYAN) {
            SettingsManager.setBootAutoEnabled(activity, it)
        })

        val apps = SettingsManager.getBootAppList(activity)
        body.addView(panel(activity).apply {
            addView(title(activity, "등록 앱 " + apps.size + "개"))
            if (apps.isEmpty()) {
                addView(bodyText(activity, "등록된 앱이 없습니다."))
            } else {
                apps.forEach { app ->
                    var detail = app.delaySeconds.toString() + "s 후 실행"
                    if (app.mediaPlayEnabled) {
                        detail += " · 미디어 자동재생 " + app.mediaDelaySeconds + "s"
                    }
                    addView(infoRow(activity,
                        if (app.appName.isBlank()) app.packageName else app.appName,
                        detail,
                        if (app.enabled) "ON" else "OFF",
                        if (app.enabled) GREEN else MUTED))
                }
            }
        })

        section(activity, body, "차량 자동화")
        val scenarios = SettingsManager.getCustomScenarios(activity)
        body.addView(panel(activity).apply {
            addView(title(activity, "시나리오 " + scenarios.size + "개"))
            scenarios.forEach { sc ->
                addView(infoRow(activity, sc.name,
                    sc.triggerName + " → " + sc.actionName,
                    if (sc.isEnabled) "ON" else "OFF",
                    if (sc.isEnabled) GREEN else MUTED))
            }
            addView(caption(activity,
                "저장된 규칙 실행 로직은 유지됩니다. 새 규칙 편집기는 v31 구조 안에서만 확장합니다."))
        })
    }

    private fun labPage(activity: AppCompatActivity, body: LinearLayout) {
        body.addView(banner(activity,
            "LAB은 읽기/진단 중심입니다. 실차 검증 전 기능을 일반 메뉴처럼 켜지 않습니다."))

        section(activity, body, "차량 Feature 연구")
        body.addView(info(activity, "실내등",
            "device 1023 / FID 1330643002 · ambient 1069547536 후보 · 한국 Dolphin 미확인", RED))
        body.addView(info(activity, "다운미러",
            "기존 angle getter/setter는 실차 미동작 · actuator 재탐색", RED))
        body.addView(info(activity, "전방 radar track",
            "ATTO 3 계열 0x280–0x289 거리/상대속도 후보 · read-only correlation만", RED))
        body.addView(info(activity, "계기판",
            "ClusterDebug → BYDAutoTestDevice → 0xAA00020F 경로 · Dolphin 5인치 검증 중", RED))
        body.addView(info(activity, "운전석 오디오",
            "DolphinAssistant 자체 stream14 사용 · 타 앱 UID routing은 LAB", RED))

        section(activity, body, "원터치 진단")
        val status = DiagnosticCaptureManager.getStatus()
        val diag = panel(activity)
        diag.addView(title(activity, "진단 세션"))
        val statusText = when {
            status.active -> "수집 중 · " + status.elapsedSeconds + "초 경과"
            status.readyToExport -> "수집 종료 · ZIP 내보내기 대기"
            else -> "대기 중"
        }
        diag.addView(bodyText(activity, statusText))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(action(activity, "수집 시작", CYAN) {
            Toast.makeText(activity, DiagnosticCaptureManager.start(activity), Toast.LENGTH_LONG).show()
            render()
        }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f).apply {
            rightMargin = dp(activity, 5)
        })
        row.addView(action(activity, "문제 시점 표시", AMBER) {
            val ok = DiagnosticCaptureManager.addProblemMarker()
            Toast.makeText(activity,
                if (ok) "문제 시점 기록" else "진단 수집 중이 아닙니다",
                Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f).apply {
            leftMargin = dp(activity, 5)
            rightMargin = dp(activity, 5)
        })
        row.addView(action(activity, "ZIP 내보내기", GREEN) {
            activity.lifecycleScope.launch {
                val file = DiagnosticCaptureManager.stopAndCreateBundle(activity)
                if (file == null) {
                    Toast.makeText(activity, "내보낼 진단 세션이 없습니다.", Toast.LENGTH_LONG).show()
                } else {
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            activity, activity.packageName + ".fileprovider", file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(Intent.createChooser(send, "진단 ZIP 공유"))
                    }.onFailure {
                        Toast.makeText(activity,
                            "ZIP 저장 완료: " + file.name, Toast.LENGTH_LONG).show()
                    }
                }
                render()
            }
        }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f).apply {
            leftMargin = dp(activity, 5)
        })
        diag.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 10)
        })
        body.addView(diag)
    }

    private fun heatControl(
        activity: AppCompatActivity,
        label: String,
        current: Int?,
        onSet: (Int) -> Unit
    ): View = panel(activity).apply {
        val state = when (current) {
            0 -> "OFF"
            1 -> "1단"
            2 -> "2단"
            else -> "확인 중"
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(title(activity, label + " · " + state),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(chip(activity, "VERIFIED", GREEN))
        addView(head)
        addView(options(activity,
            listOf("0" to "OFF", "1" to "1단", "2" to "2단"),
            if (current == null) "" else current.toString()) {
            onSet(it.toInt())
        })
    }

    private fun toggleCard(
        activity: AppCompatActivity,
        label: String,
        checked: Boolean,
        status: String,
        color: Int,
        onChange: (Boolean) -> Unit
    ): View = panel(activity).apply {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(activity, label))
            addView(caption(activity, if (checked) "현재 켜짐" else "현재 꺼짐"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip(activity, status, color))
        row.addView(SwitchCompat(activity).apply {
            isChecked = checked
            setPadding(dp(activity, 10), 0, 0, 0)
            setOnCheckedChangeListener { _, value ->
                onChange(value)
                delayedRender(activity)
            }
        })
        addView(row)
    }

    private fun slider(
        activity: AppCompatActivity,
        label: String,
        initial: Int,
        min: Int,
        max: Int,
        suffix: String,
        onChange: (Int) -> Unit
    ): View = panel(activity).apply {
        val valueText = TextView(activity).apply {
            text = initial.toString() + suffix
            textSize = 12f
            setTextColor(CYAN)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        head.addView(title(activity, label),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(valueText)
        addView(head)
        addView(SeekBar(activity).apply {
            this.max = max - min
            progress = initial.coerceIn(min, max) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actual = min + progress
                    valueText.text = actual.toString() + suffix
                    if (fromUser) onChange(actual)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
    }

    private fun statusCard(
        activity: AppCompatActivity,
        label: String,
        desc: String,
        status: String,
        color: Int,
        destination: String?
    ): View = panel(activity).apply {
        if (destination != null) setOnClickListener { navigate(destination) }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(title(activity, label),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(chip(activity, status, color))
        addView(head)
        addView(bodyText(activity, desc).apply {
            setPadding(0, dp(activity, 8), 0, 0)
        })
    }

    private fun info(
        activity: AppCompatActivity,
        label: String,
        desc: String,
        color: Int
    ): View = panel(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(activity, 8)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(View(activity).apply { setBackgroundColor(color) },
            LinearLayout.LayoutParams(dp(activity, 3), dp(activity, 34)).apply {
                rightMargin = dp(activity, 10)
            })
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(activity, label))
            addView(caption(activity, desc))
        })
        addView(row)
    }

    private fun infoRow(
        activity: AppCompatActivity,
        label: String,
        desc: String,
        status: String,
        color: Int
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(activity, label))
            addView(caption(activity, desc))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(chip(activity, status, color))
    }

    private fun options(
        activity: AppCompatActivity,
        list: List<Pair<String, String>>,
        selected: String,
        onSelect: (String) -> Unit
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(activity, 6), 0, dp(activity, 2))
        }
        list.forEach { item ->
            val isSelected = item.first == selected
            row.addView(TextView(activity).apply {
                text = item.second
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (isSelected) BG else TEXT)
                setTypeface(Typeface.DEFAULT,
                    if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(activity, 13), 0, dp(activity, 13), 0)
                background = round(
                    if (isSelected) CYAN else SURFACE3,
                    13f,
                    if (isSelected) CYAN else DIVIDER)
                setOnClickListener { onSelect(item.first) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40)).apply {
                rightMargin = dp(activity, 7)
            })
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun grid2(activity: AppCompatActivity, body: LinearLayout, a: View, b: View) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(activity, 6)
        })
        row.addView(b, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(activity, 6)
        })
        body.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(activity, 12)
        })
    }

    private fun section(activity: AppCompatActivity, body: LinearLayout, textValue: String) {
        body.addView(TextView(activity).apply {
            text = textValue
            textSize = 14f
            setTextColor(TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(activity, 20), 0, dp(activity, 9))
        })
    }

    private fun panel(activity: AppCompatActivity): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 15), dp(activity, 16), dp(activity, 15))
            background = round(SURFACE, 16f, DIVIDER)
        }

    private fun banner(activity: AppCompatActivity, textValue: String): View =
        TextView(activity).apply {
            text = textValue
            textSize = 11f
            setTextColor(MUTED)
            setPadding(dp(activity, 14), dp(activity, 11), dp(activity, 14), dp(activity, 11))
            background = round(SURFACE2, 12f, DIVIDER)
        }

    private fun title(activity: AppCompatActivity, textValue: String): TextView =
        TextView(activity).apply {
            text = textValue
            textSize = 14f
            setTextColor(TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun bodyText(activity: AppCompatActivity, textValue: String): TextView =
        TextView(activity).apply {
            text = textValue
            textSize = 11f
            setTextColor(MUTED)
            setLineSpacing(0f, 1.18f)
        }

    private fun caption(activity: AppCompatActivity, textValue: String): TextView =
        TextView(activity).apply {
            text = textValue
            textSize = 9f
            setTextColor(MUTED)
            setPadding(0, dp(activity, 7), 0, 0)
        }

    private fun chip(activity: AppCompatActivity, textValue: String, color: Int): TextView =
        TextView(activity).apply {
            text = textValue
            textSize = 8f
            gravity = Gravity.CENTER
            setTextColor(color)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4))
            background = round(
                Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)),
                11f, color)
        }

    private fun action(
        activity: AppCompatActivity,
        textValue: String,
        color: Int,
        callback: () -> Unit
    ): TextView = TextView(activity).apply {
        text = textValue
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(activity, 13), 0, dp(activity, 13), 0)
        background = round(
            Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)),
            12f, color)
        setOnClickListener { callback() }
    }

    private fun round(color: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != null && stroke != Color.TRANSPARENT) setStroke(1, stroke)
        }

    private fun navBg(selected: Boolean): GradientDrawable =
        round(
            if (selected) Color.rgb(17, 40, 53) else Color.TRANSPARENT,
            14f,
            if (selected) Color.rgb(33, 83, 103) else Color.TRANSPARENT)

    private fun delayedRender(activity: AppCompatActivity) {
        activity.window.decorView.postDelayed({ render() }, 450L)
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
