package com.byd.dolphin.autoassistant

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.byd.dolphin.autoassistant.util.DolphinLogger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registry-first real-car verification console.
 *
 * It never changes vehicle state. Operator observations are written to DolphinLogger
 * using a stable VERIFY_RESULT contract so DiagnosticSession ZIPs can be evaluated
 * by verification/evaluate_diagnostic.py.
 */
class VerificationCenterActivity : AppCompatActivity() {
    private lateinit var registry: JSONObject
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("verification_center_v1", MODE_PRIVATE) }

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
        text("Master Verification Registry 기반 실차 시험 · 이 화면 자체는 차량 제어를 수행하지 않습니다.", 12f, Color.rgb(155, 190, 202), false)

        val features = registry.getJSONArray("features")
        val counts = linkedMapOf("VERIFIED" to 0, "BETA" to 0, "REVERIFY_REQUIRED" to 0, "BLOCKED" to 0, "UNSUPPORTED" to 0)
        for (i in 0 until features.length()) {
            val state = features.getJSONObject(i).optString("state")
            counts[state] = (counts[state] ?: 0) + 1
        }
        text(
            counts.entries.joinToString("  ·  ") { "${it.key} ${it.value}" } +
                "  ·  Known-Bad ${registry.optJSONArray("known_bad_library")?.length() ?: 0}",
            12f, Color.rgb(115, 255, 218), true
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(button("진단 수집 화면") {
            startActivity(Intent(this, MainActivity::class.java).putExtra("open_panel", "dpi"))
        }, weight())
        actions.addView(button("결과 초기화") {
            prefs.edit().clear().apply()
            recreate()
        }, weight())
        root.addView(actions)

        val knownBadByFeature = mutableMapOf<String, MutableList<String>>()
        val kb = registry.optJSONArray("known_bad_library") ?: JSONArray()
        for (i in 0 until kb.length()) {
            val item = kb.getJSONObject(i)
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
        ordered.forEach { addFeatureCard(it, knownBadByFeature[it.optString("feature_id")].orEmpty()) }
        return scroll
    }

    private fun addFeatureCard(feature: JSONObject, knownBad: List<String>) {
        val featureId = feature.optString("feature_id")
        val state = feature.optString("state")
        val tests = feature.optJSONArray("test_ids") ?: JSONArray()
        val testIds = (0 until tests.length()).map { tests.getString(it) }

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

        card.addView(textView("$featureId  ·  $state", 15f, stateColor(state), true))
        card.addView(textView(feature.optString("requirement"), 12f, Color.WHITE, false))
        card.addView(textView("Test ID  " + testIds.joinToString(" · "), 11f, Color.rgb(145, 190, 210), true))

        if (knownBad.isNotEmpty()) {
            card.addView(textView("KNOWN-BAD\n" + knownBad.joinToString("\n"), 11f, Color.rgb(255, 153, 102), false))
        }

        val steps = feature.optJSONArray("real_vehicle_test") ?: JSONArray()
        if (steps.length() > 0) {
            val body = (0 until steps.length()).joinToString("\n") { "• " + steps.getString(it) }
            card.addView(textView("실차 절차\n$body", 11f, Color.rgb(190, 205, 213), false))
        }

        val saved = prefs.getString("result_$featureId", null)
        val resultText = textView(saved?.let { "최근 결과  $it" } ?: "최근 결과  미기록", 11f, Color.rgb(150, 165, 175), false)
        card.addView(resultText)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("PASS") { record(featureId, testIds, "PASS", resultText) }, weight())
        row1.addView(button("FAIL") { record(featureId, testIds, "FAIL", resultText) }, weight())
        card.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("간헐") { record(featureId, testIds, "INTERMITTENT", resultText) }, weight())
        row2.addView(button("지연") { record(featureId, testIds, "DELAYED", resultText) }, weight())
        card.addView(row2)
    }

    private fun record(featureId: String, testIds: List<String>, outcome: String, target: TextView) {
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        val saved = "$outcome · $whenText"
        prefs.edit().putString("result_$featureId", saved).apply()
        target.text = "최근 결과  $saved"
        testIds.forEach { testId ->
            DolphinLogger.i(
                "VERIFY_RESULT",
                "test=$testId feature=$featureId outcome=$outcome source=operator"
            )
        }
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
        textSize = 11f
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
