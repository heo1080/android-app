package com.byd.dolphin.autoassistant.manager

import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Unified output configuration UI for every vehicle/driving announcement.
 */
object AlertOutputConfigUi {
    fun show(activity: AppCompatActivity, audioManager: VoiceAndSoundManager) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            setBackgroundColor(Color.parseColor("#071016"))
        }

        content.addView(TextView(activity).apply {
            text = "음성 · 경고 출력"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        content.addView(TextView(activity).apply {
            text = "모든 항목을 같은 방식으로 설정합니다. OFF / 비프 / TTS → 비프 종류 또는 문구/음성 선택"
            textSize = 11f
            setTextColor(Color.parseColor("#8FA2B4"))
            setPadding(0, dp(5), 0, dp(14))
        })

        val globalVoice = Spinner(activity)
        val globalLabels = AlertOutputProfileManager.voicePresets.map { it.label }
        globalVoice.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, globalLabels)
        val selectedGlobal = AlertOutputProfileManager.getGlobalVoiceId(activity)
        globalVoice.setSelection(AlertOutputProfileManager.voicePresets.indexOfFirst { it.id == selectedGlobal }.coerceAtLeast(0))

        val previewText = EditText(activity).apply {
            setText("안전 운전을 위해 주변을 확인해 주세요.")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "TTS 미리듣기 문장"
        }

        content.addView(sectionTitle(activity, "기본 TTS 음성"))
        content.addView(globalVoice)
        content.addView(previewText)
        content.addView(Button(activity).apply {
            text = "선택 음성 미리듣기"
            setOnClickListener {
                val voicePreset = AlertOutputProfileManager.voicePresets[globalVoice.selectedItemPosition.coerceIn(0, AlertOutputProfileManager.voicePresets.lastIndex)]
                AlertOutputProfileManager.setGlobalVoiceId(activity, voicePreset.id)
                val ok = InAppSupertonicTtsManager.speak(activity, previewText.text.toString(), voicePreset.sid, voicePreset.speed)
                Toast.makeText(activity, if (ok) voicePreset.label + " 미리듣기" else "TTS 모델 준비 중", Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(TextView(activity).apply {
            text = "‘부드러운/차가운/정직한/힘있는’은 공식 감정 태그가 아니라 여러 Supertonic 화자와 속도를 묶은 체감 프리셋입니다. 미리듣기로 고르세요."
            textSize = 10f
            setTextColor(Color.parseColor("#8FA2B4"))
            setPadding(0, dp(5), 0, dp(12))
        })

        AlertOutputProfileManager.events.forEach { spec ->
            content.addView(buildEventPanel(activity, audioManager, spec))
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(activity)
            .setView(scroll)
            .setNegativeButton("닫기", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                dialog.show()
            }
    }

    private fun buildEventPanel(
        activity: AppCompatActivity,
        audioManager: VoiceAndSoundManager,
        spec: AlertOutputProfileManager.EventSpec
    ): LinearLayout {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val current = AlertOutputProfileManager.getProfile(activity, spec.key)

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#111922"))
        }
        panel.addView(TextView(activity).apply {
            text = spec.title
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })

        val mode = Spinner(activity)
        val modeIds = listOf(AlertOutputProfileManager.MODE_OFF, AlertOutputProfileManager.MODE_BEEP, AlertOutputProfileManager.MODE_TTS)
        mode.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("OFF", "비프음", "TTS"))
        mode.setSelection(modeIds.indexOf(current.mode).coerceAtLeast(0))

        val beep = Spinner(activity)
        beep.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, AlertOutputProfileManager.beepPresets.map { it.label })
        beep.setSelection(AlertOutputProfileManager.beepPresets.indexOfFirst { it.id == current.beepId }.coerceAtLeast(0))

        val phrase = Spinner(activity)
        val phraseIds = listOf(
            AlertOutputProfileManager.PHRASE_DEFAULT,
            AlertOutputProfileManager.PHRASE_RECOMMENDED,
            AlertOutputProfileManager.PHRASE_CUSTOM
        )
        phrase.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("기본 문구", "추천 문구", "커스텀 문구"))
        phrase.setSelection(phraseIds.indexOf(current.phraseMode).coerceAtLeast(0))

        val custom = EditText(activity).apply {
            hint = spec.customHint
            setText(current.customText)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val voiceSpinner = Spinner(activity)
        val voiceOptions = listOf("기본 TTS 음성 사용") + AlertOutputProfileManager.voicePresets.map { it.label }
        voiceSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, voiceOptions)
        voiceSpinner.setSelection(
            if (current.voiceId == AlertOutputProfileManager.VOICE_GLOBAL) 0
            else AlertOutputProfileManager.voicePresets.indexOfFirst { it.id == current.voiceId }.let { if (it < 0) 0 else it + 1 }
        )

        panel.addView(label(activity, "출력 방식"))
        panel.addView(mode)
        panel.addView(label(activity, "비프음 종류"))
        panel.addView(beep)
        panel.addView(label(activity, "TTS 문구"))
        panel.addView(phrase)
        panel.addView(custom)
        panel.addView(label(activity, "TTS 음성"))
        panel.addView(voiceSpinner)

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(activity).apply {
            text = "저장"
            setOnClickListener {
                val profile = readProfile(mode, beep, phrase, custom, voiceSpinner)
                AlertOutputProfileManager.saveProfile(activity, spec.key, profile)
                Toast.makeText(activity, spec.title + " 설정 저장", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(activity).apply {
            text = "미리듣기"
            setOnClickListener {
                val profile = readProfile(mode, beep, phrase, custom, voiceSpinner)
                AlertOutputProfileManager.saveProfile(activity, spec.key, profile)
                audioManager.previewConfiguredAlert(spec.key)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)

        return panel.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun readProfile(
        mode: Spinner,
        beep: Spinner,
        phrase: Spinner,
        custom: EditText,
        voice: Spinner
    ): AlertOutputProfileManager.Profile {
        val modes = listOf(AlertOutputProfileManager.MODE_OFF, AlertOutputProfileManager.MODE_BEEP, AlertOutputProfileManager.MODE_TTS)
        val phrases = listOf(
            AlertOutputProfileManager.PHRASE_DEFAULT,
            AlertOutputProfileManager.PHRASE_RECOMMENDED,
            AlertOutputProfileManager.PHRASE_CUSTOM
        )
        val voiceId = if (voice.selectedItemPosition <= 0) {
            AlertOutputProfileManager.VOICE_GLOBAL
        } else {
            AlertOutputProfileManager.voicePresets[(voice.selectedItemPosition - 1).coerceIn(0, AlertOutputProfileManager.voicePresets.lastIndex)].id
        }
        return AlertOutputProfileManager.Profile(
            mode = modes[mode.selectedItemPosition.coerceIn(0, modes.lastIndex)],
            beepId = AlertOutputProfileManager.beepPresets[beep.selectedItemPosition.coerceIn(0, AlertOutputProfileManager.beepPresets.lastIndex)].id,
            phraseMode = phrases[phrase.selectedItemPosition.coerceIn(0, phrases.lastIndex)],
            customText = custom.text.toString(),
            voiceId = voiceId
        )
    }

    private fun sectionTitle(activity: AppCompatActivity, textValue: String) = TextView(activity).apply {
        text = textValue
        textSize = 14f
        setTextColor(Color.WHITE)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun label(activity: AppCompatActivity, textValue: String) = TextView(activity).apply {
        text = textValue
        textSize = 10f
        setTextColor(Color.parseColor("#8FA2B4"))
        setPadding(0, 6, 0, 2)
    }
}
