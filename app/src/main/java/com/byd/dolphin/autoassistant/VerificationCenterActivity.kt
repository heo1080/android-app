package com.byd.dolphin.autoassistant

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.byd.dolphin.autoassistant.manager.DiagnosticCaptureManager
import com.byd.dolphin.autoassistant.manager.VerificationEvidenceLogger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registry-first real-car verification console.
 *
 * Results are recorded per Test ID, never per feature. BLOCKED/UNSUPPORTED tests
 * cannot be marked PASS/FAIL here; they only capture NEED_MORE_DATA evidence.
 */
class VerificationCenterActivity : AppCompatActivity() {
    private lateinit var registry: JSONObject
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("verification_center_v2", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = JSONObject(assets.open("verification_registry.json").bufferedReader().use { it.readText() })
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(3, 10, 18)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(root)

        text("VERIFICATION CENTER", 22f, Color.WHITE, true)
        text(
            "Test ID별 실차 검증 · 결과는 APK/차량상태/진단세션 fingerprint와 함께 append-only evidence로 기록됩니다.",
            12f, Color.rgb(155, 190, 202), false
        )

        val capture = DiagnosticCaptureManager.getStatus()
        text(
            if (capture.active) "진단 세션 ACTIVE · ${capture.sessionId}" else "진단 세션 OFF · PASS는 자동평가에서 증거부족 처리될 수 있습니다.",
            12f,
            if (capture.active) Color.rgb(76, 230, 170) else Color.rgb(255, 193, 7),
            true
        )

        val features = registry.getJSONArray("features")
        val counts = linkedMapOf("VERIFIED" to 0, "BETA" to 0, "REVERIFY_REQUIRED" to 0, "BLOCKED" to 0, "UNSUPPORTED" to 0)
        var testCount = 0
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val state = feature.optString("state")
            counts[state] = (counts[state] ?: 0) + 1
            testCount += feature.optJSONArray("test_ids")?.length() ?: 0
        }
        text(
            "Tests $testCount  ·  " + counts.entries.joinToString("  ·  ") { "${it.key} ${it.value}" } +
                "  ·  Known-Bad ${registry.optJSONArray("known_bad_library")?.length() ?: 0}",
            11f, Color.rgb(115, 255, 218), true
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(button("진단 수집 화면") {
            startActivity(Intent(this, MainActivity::class.java).putExtra("open_panel", "dpi"))
        }, weight())
        actions.addView(button("지금 문제 발생") {
            VerificationEvidenceLogger.markProblem(this, "Verification Center manual marker")
            Toast.makeText(this, "문제 순간과 현재 차량/빌드 상태를 기록했습니다.", Toast.LENGTH_SHORT).show()
        }, weight())
        actions.addView(button("화면 최근결과 초기화") {
            prefs.edit().clear().apply()
            recreate()
        }, weight())
        root.addView(actions)

        val knownBadByFeature = mutableMapOf<String, MutableList<String>>()
        val kb = registry.optJSONArray("known_bad_library") ?: JSONArray()
        for (i in 0 until kb.length()) {
            val item = kb.getJSONObject(i)
            if (item.optString("status", "OPEN") == "CLOSED") continue
            val label = "${item.optString("id")} ${item.optString("severity")} · ${item.optString("symptom")}"
            val linked = item.optJSONArray("linked") ?: JSONArray()
            for (j in 0 until linked.length()) {
                knownBadByFeature.getOrPut(linked.getString(j)) { mutableListOf() }.add(label)
            }
        }

        val ordered = (0 until features.length()).map { features.getJSONObject(it) }.sortedWith(
            compareBy<JSONObject>(
                { if (knownBadByFeature.containsKey(it.optString("feature_id"))) 0 else 1 },
                { stateRank(it.optString("state")) },
                { it.optString("feature_id") }
            )
        )
        ordered.forEach { feature ->
            val featureId = feature.optString("feature_id")
            val knownBad = knownBadByFeature[featureId].orEmpty()
            val tests = feature.optJSONArray("test_ids") ?: JSONArray()
            for (i in 0 until tests.length()) {
                addTestCard(feature, tests.getString(i), knownBad)
            }
        }
        return scroll
    }

    private fun addTestCard(feature: JSONObject, testId: String, knownBad: List<String>) {
        val featureId = feature.optString("feature_id")
        val state = feature.optString("state")
        val blocked = state == "BLOCKED" || state == "UNSUPPORTED"

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(9, 24, 36))
                setStroke(dp(1), stateColor(state))
                cornerRadius = dp(12).toFloat()
            }
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        card.addView(textView("$testId  ·  $featureId", 15f, stateColor(state), true))
        card.addView(textView("상태 $state", 11f, stateColor(state), true))
        card.addView(textView(feature.optString("requirement"), 12f, Color.WHITE, false))

        if (knownBad.isNotEmpty()) {
            card.addView(textView("KNOWN-BAD\n" + knownBad.joinToString("\n"), 11f, Color.rgb(255, 153, 102), false))
        }

        val steps = feature.optJSONArray("real_vehicle_test") ?: JSONArray()
        if (steps.length() > 0) {
            val body = (0 until steps.length()).joinToString("\n") { "• " + steps.getString(it) }
            card.addView(textView("실차 절차\n$body", 11f, Color.rgb(190, 205, 213), false))
        }

        val note = EditText(this).apply {
            hint = "현장 메모: 늦음 / 잘못 읽음 / 3번 중 1번 실패 / 실제 램프 무반응 등"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(100, 130, 145))
            textSize = 11f
            setSingleLine(false)
            minLines = 2
        }
        card.addView(note)

        val saved = prefs.getString("result_$testId", null)
        val resultText = textView(saved?.let { "최근 화면 결과  $it" } ?: "최근 화면 결과  미기록", 11f, Color.rgb(150, 165, 175), false)
        card.addView(resultText)

        if (blocked) {
            card.addView(textView("PASS/FAIL 승격 금지 · capability/evidence 수집만 가능합니다.", 11f, Color.rgb(255, 193, 7), true))
            card.addView(button("증거수집 · NEED_MORE_DATA") {
                record(testId, featureId, state, "NEED_MORE_DATA", note.text.toString(), resultText)
            })
        } else {
            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(button("PASS") { record(testId, featureId, state, "PASS", note.text.toString(), resultText) }, weight())
            row1.addView(button("FAIL") { record(testId, featureId, state, "FAIL", note.text.toString(), resultText) }, weight())
            card.addView(row1)

            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(button("간헐") { record(testId, featureId, state, "INTERMITTENT", note.text.toString(), resultText) }, weight())
            row2.addView(button("지연") { record(testId, featureId, state, "DELAYED", note.text.toString(), resultText) }, weight())
            row2.addView(button("증거부족") { record(testId, featureId, state, "NEED_MORE_DATA", note.text.toString(), resultText) }, weight())
            card.addView(row2)
        }
    }

    private fun record(
        testId: String,
        featureId: String,
        state: String,
        outcome: String,
        note: String,
        target: TextView
    ) {
        val correlation = VerificationEvidenceLogger.recordObservation(
            context = this,
            testId = testId,
            featureId = featureId,
            featureState = state,
            requestedOutcome = outcome,
            note = note
        )
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        val saved = "$outcome · $whenText · ${correlation.take(8)}"
        prefs.edit().putString("result_$testId", saved).apply()
        target.text = "최근 화면 결과  $saved"
        Toast.makeText(this, "$testId $outcome 기록", Toast.LENGTH_SHORT).show()
    }

    private fun stateRank(state: String): Int = when (state) {
        "BETA" -> 0
        "REVERIFY_REQUIRED" -> 1
        "BLOCKED" -> 2
        "UNSUPPORTED" -> 3
        "VERIFIED" -> 4
        else -> 5
    }

    private fun stateColor(state: String): Int = when (state) {
        "VERIFIED" -> Color.rgb(76, 230, 170)
        "BETA" -> Color.rgb(0, 220, 255)
        "REVERIFY_REQUIRED" -> Color.rgb(255, 193, 7)
        "BLOCKED" -> Color.rgb(255, 112, 112)
        "UNSUPPORTED" -> Color.rgb(160, 160, 160)
        else -> Color.WHITE
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) {
        root.addView(textView(value, size, color, bold))
    }

    private fun textView(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(5), 0, dp(5))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
        topMargin = dp(4)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
