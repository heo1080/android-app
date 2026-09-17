package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import com.byd.dolphin.autoassistant.hud.ClusterMirrorManager

/** Dynamic v30.6/v30.6.1 beta UI. */
object IntegratedBetaUi {

    /**
     * v30.6.0 attached the new panels after full-height sub-screen ScrollViews.
     * They were technically in the view hierarchy but outside the visible area.
     * v30.6.1 adds one explicit dashboard card that opens a dedicated scrollable
     * dialog containing every new control, so URL/token/mirror/display labs are
     * always reachable regardless of the legacy sub-screen XML structure.
     */
    fun attachDashboardEntry(activity: AppCompatActivity, dashboardRoot: View, audioManager: VoiceAndSoundManager) {
        val grid = findFirstGridLayout(dashboardRoot) ?: run {
            Toast.makeText(activity, "v30.6.1 설정 메뉴를 붙일 위치를 찾지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        val marker = "v30_6_1_integrated_menu"
        if ((0 until grid.childCount).any { grid.getChildAt(it).tag == marker }) return

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val card = CardView(activity).apply {
            tag = marker
            radius = 16f * density
            cardElevation = 4f * density
            setCardBackgroundColor(Color.parseColor("#0B1E2B"))
            foreground = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
                val drawable = getDrawable(0)
                recycle()
                drawable
            }
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(164)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
        }

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10293A"))
                cornerRadius = 16f * density
                setStroke(dp(1).coerceAtLeast(1), Color.parseColor("#00E5FF"))
            }
            addView(TextView(activity).apply {
                text = "v30.6.1 // INTEGRATED LAB"
                textSize = 10f
                setTextColor(Color.parseColor("#00E5FF"))
            })
            addView(TextView(activity).apply {
                text = "통합 설정 · 실차 LAB"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, dp(3))
            })
            addView(TextView(activity).apply {
                text = "HD 자연음성 · 토큰 입력\n다운미러 · 실내등 · DPI · 계기판 · 앱별 오디오"
                textSize = 11f
                setTextColor(Color.parseColor("#B0BEC5"))
                maxLines = 3
            })
            addView(TextView(activity).apply {
                text = "여기를 눌러 새 기능 설정 열기"
                textSize = 10f
                setTextColor(Color.parseColor("#80D8FF"))
                setPadding(0, dp(10), 0, 0)
            })
        }
        card.addView(body)
        card.setOnClickListener { showIntegratedDialog(activity, audioManager) }
        grid.addView(card)
    }

    private fun showIntegratedDialog(activity: AppCompatActivity, audioManager: VoiceAndSoundManager) {
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, (24 * density).toInt())
            setBackgroundColor(Color.parseColor("#07151F"))
        }

        content.addView(TextView(activity).apply {
            text = "v30.6.1 통합 설정"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(activity).apply {
            text = "아래에서 Cloud Run URL/토큰 입력부터 다운미러·실내등·디스플레이·계기판·앱별 오디오 연구 설정까지 모두 확인할 수 있습니다."
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        })

        attachNaturalVoice(activity, content, audioManager)
        attachComfortLab(activity, content)
        attachAppAudioLab(activity, content)
        attachClusterLab(activity, content)
        attachDisplayLab(activity, content)

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(scroll)
            .setNegativeButton("닫기", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
    }

    fun attachNaturalVoice(activity: AppCompatActivity, rootView: View, audioManager: VoiceAndSoundManager) {
        val root = rootView as? ViewGroup ?: return
        val panel = panel(activity, "자연음성 HD 캐시 · v30.6.1", "Google Chirp 3 HD로 한 번 생성 → 차량 캐시 → 검증된 stream14 운전석 전용 재생. 캐시가 없거나 서버가 끊기면 기존 로컬 TTS로 즉시 fallback 합니다.")
        val config = NaturalVoiceCacheManager.getConfig(activity)

        val enabled = SwitchCompat(activity).apply {
            text = "자연음성 캐시 우선 사용"
            setTextColor(Color.WHITE)
            isChecked = config.enabled
        }
        val gateway = EditText(activity).apply {
            hint = "Cloud Run HTTPS URL"
            setText(config.gatewayUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            singleLine = true
        }
        val token = EditText(activity).apply {
            hint = if (config.tokenPresent) "게이트웨이 토큰 저장됨 · 변경할 때만 입력" else "게이트웨이 공유 토큰"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            singleLine = true
        }
        val voice = Spinner(activity)
        val voices = NaturalVoiceCacheManager.supportedVoices
        voice.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, voices)
        voice.setSelection(voices.indexOf(config.voice).coerceAtLeast(0))

        val testText = EditText(activity).apply {
            setText("전방 차량이 출발했습니다. 안전을 확인해 주세요.")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "미리듣기 문장"
        }
        val status = info(activity, NaturalVoiceCacheManager.statusSummary(activity))

        val save = button(activity, "설정 저장") {
            val ok = NaturalVoiceCacheManager.saveConfig(
                activity,
                enabled.isChecked,
                gateway.text.toString(),
                voice.selectedItem?.toString() ?: voices.first(),
                token.text.toString().takeIf { it.isNotBlank() }
            )
            status.text = if (ok) NaturalVoiceCacheManager.statusSummary(activity) else "저장 실패 · HTTPS URL/음성 선택을 확인하세요."
            toast(activity, if (ok) "자연음성 설정 저장" else "설정 저장 실패")
        }
        val preview = button(activity, "HD 자연음성 생성 + 운전석 미리듣기") {
            status.text = "자연음성 생성/캐시 확인 중…"
            NaturalVoiceCacheManager.prepareAndPlayAsync(activity, testText.text.toString()) { result ->
                activity.runOnUiThread {
                    status.text = result.message + "\n" + NaturalVoiceCacheManager.statusSummary(activity)
                    toast(activity, result.message)
                }
            }
        }
        val prewarm = button(activity, "기본 차량 안내문구 미리 생성") {
            NaturalVoiceCacheManager.prewarmDefaults(activity)
            status.text = "기어/충전/전방차량/BSD/LDP 기본 문구 캐시 생성을 요청했습니다."
        }
        val clear = button(activity, "자연음성 캐시 비우기") {
            val count = NaturalVoiceCacheManager.clearCache(activity)
            status.text = "캐시 ${count}개 삭제 · " + NaturalVoiceCacheManager.statusSummary(activity)
        }

        panel.addView(enabled)
        panel.addView(gateway)
        panel.addView(token)
        panel.addView(voice)
        panel.addView(testText)
        panel.addView(save)
        panel.addView(preview)
        panel.addView(prewarm)
        panel.addView(clear)
        panel.addView(status)
        root.addView(panel)
    }

    fun attachComfortLab(activity: AppCompatActivity, rootView: View) {
        val root = rootView as? ViewGroup ?: return
        val panel = panel(activity, "메모리 · 다운미러 · 실내등 LAB", "다운미러는 실차 getter/setter가 확인되어 현재 각도를 그대로 저장합니다. 숫자 범위는 추측하지 않습니다. 시트 M1~M3 모터 구동은 위치 API 쌍이 확인될 때까지 진단 전용입니다.")
        val mirrorEnabled = SwitchCompat(activity).apply {
            text = "R단 자동 다운미러 사용 (5 km/h 이하만)"
            setTextColor(Color.WHITE)
            isChecked = MirrorMemoryManager.isEnabled(activity)
            setOnCheckedChangeListener { _, checked -> MirrorMemoryManager.setEnabled(activity, checked) }
        }
        val status = info(activity, MirrorMemoryManager.statusSummary(activity))
        val normal = button(activity, "① 현재 미러 위치를 NORMAL로 저장") {
            val result = MirrorMemoryManager.captureNormal(activity)
            status.text = "NORMAL 저장=$result\n${MirrorMemoryManager.statusSummary(activity)}"
        }
        val reverse = button(activity, "② 현재 미러 위치를 R-DOWN으로 저장") {
            val result = MirrorMemoryManager.captureReverse(activity)
            status.text = "R-DOWN 저장=$result\n${MirrorMemoryManager.statusSummary(activity)}"
        }
        val refresh = button(activity, "미러/시트/계기판 API 상태 새로고침") {
            status.text = VehicleFeatureLabManager.summary(activity).lineSequence().take(14).joinToString("\n")
        }

        val lightRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        lightRow.addView(button(activity, "실내등 ON") { InsideLightManager.turnOn(activity); status.text = MirrorMemoryManager.statusSummary(activity) }, weight())
        lightRow.addView(button(activity, "실내등 OFF") { InsideLightManager.turnOff(activity); status.text = MirrorMemoryManager.statusSummary(activity) }, weight())
        val doorLight = button(activity, "도어 연동 실내등 토글") {
            val next = InsideLightManager.toggleDoorInterlock(activity)
            toast(activity, "도어 연동 요청: ${if (next) "ON" else "OFF"}")
        }

        panel.addView(mirrorEnabled)
        panel.addView(normal)
        panel.addView(reverse)
        panel.addView(refresh)
        panel.addView(lightRow)
        panel.addView(doorLight)
        panel.addView(info(activity, "캘리브레이션 순서: P 정차 → 평소 미러 저장 → 손으로 원하는 후진 미러 위치 조정 → R-DOWN 저장 → 미러를 평소 위치로 되돌림 → 자동 토글 ON. R에서 운전자가 미러를 직접 움직이면 자동 복원을 취소합니다."))
        panel.addView(status)
        root.addView(panel)
    }

    fun attachClusterLab(activity: AppCompatActivity, rootView: View) {
        val root = rootView as? ViewGroup ?: return
        val panel = panel(activity, "계기판 출력 LAB · 안전 우선", "기존 TBT는 유지합니다. 전체 앱 미러링/커스텀 테마 쓰기는 순정 ADAS 경고 복원 경로와 display target을 확인하기 전까지 차단합니다.")
        val status = info(activity, ClusterMirrorManager.capabilitySummary(activity))
        val refresh = button(activity, "계기판 API/현재 테마 읽기") {
            status.text = VehicleFeatureLabManager.summary(activity).lineSequence().take(12).joinToString("\n")
        }
        panel.addView(refresh)
        panel.addView(status)
        root.addView(panel)
    }

    fun attachDisplayLab(activity: AppCompatActivity, rootView: View) {
        val root = rootView as? ViewGroup ?: return
        val panel = panel(activity, "디스플레이 프로파일 LAB", "10인치에서 stock UI가 잘리는 문제를 density 하나로 해결하지 않고 logical size / density / fontScale을 분리합니다. 12.8/15.6 순정 프로파일은 실제 값 확보 전까지 자동 적용하지 않습니다.")
        val status = info(activity, DisplayDiagnosticsManager.summary(activity))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(activity, "글자 90%") { DisplayDiagnosticsManager.setFontScale(activity, 0.90f); status.text = DisplayDiagnosticsManager.summary(activity) }, weight())
        row.addView(button(activity, "글자 100%") { DisplayDiagnosticsManager.resetFontScale(activity); status.text = DisplayDiagnosticsManager.summary(activity) }, weight())
        row.addView(button(activity, "글자 110%") { DisplayDiagnosticsManager.setFontScale(activity, 1.10f); status.text = DisplayDiagnosticsManager.summary(activity) }, weight())
        val refresh = button(activity, "현재 디스플레이 상태 읽기") { status.text = DisplayDiagnosticsManager.summary(activity) }
        panel.addView(row)
        panel.addView(refresh)
        panel.addView(info(activity, "원터치 진단 ZIP에 display_api/configuration/display_modes/font_scale/system shell 정보가 자동으로 추가됩니다."))
        panel.addView(status)
        root.addView(panel)
    }

    fun attachAppAudioLab(activity: AppCompatActivity, rootView: View) {
        val root = rootView as? ViewGroup ?: return
        val panel = panel(activity, "앱별 운전석 오디오 LAB", "stream14 운전석 출력은 확인됐지만, 임의 앱 소리를 가로채는 기능은 순정 안전 경고와의 충돌 검증 전까지 활성화하지 않습니다. 목표 앱만 먼저 등록해 UID/재생 로그를 모읍니다.")
        val packages = EditText(activity).apply {
            hint = "예: com.nhn.android.nmap, com.skt.tmap.ku"
            setText(DriverAudioPolicyLab.selectedPackages(activity).sorted().joinToString(", "))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val status = info(activity, DriverAudioPolicyLab.summary(activity))
        val save = button(activity, "연구 대상 앱 저장") {
            val values = packages.text.toString().split(',', '\n', ' ').map { it.trim() }.filter { it.isNotBlank() }.toSet()
            DriverAudioPolicyLab.saveSelectedPackages(activity, values)
            status.text = DriverAudioPolicyLab.summary(activity)
        }
        panel.addView(packages)
        panel.addView(save)
        panel.addView(status)
        root.addView(panel)
    }

    private fun findFirstGridLayout(view: View): GridLayout? {
        if (view is GridLayout) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstGridLayout(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun panel(context: Context, title: String, description: String): LinearLayout {
        val density = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0B1E2B"))
                cornerRadius = 14f * density
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.parseColor("#1C607A"))
            }
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * density).toInt()
                bottomMargin = (10 * density).toInt()
            }
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = description
                textSize = 11f
                setTextColor(Color.parseColor("#B0BEC5"))
                setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
            })
        }
    }

    private fun info(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 11f
        setTextColor(Color.parseColor("#80D8FF"))
        setPadding(0, 6, 0, 6)
        setTextIsSelectable(true)
    }

    private fun button(context: Context, label: String, action: () -> Unit) = Button(context).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
