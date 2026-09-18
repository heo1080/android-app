package com.byd.dolphin.autoassistant.drive

import android.content.res.Configuration
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
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DriveVisionActivity : AppCompatActivity() {
    private lateinit var vision: SurroundingVisionView
    private lateinit var reader: DriveSignalReader

    private lateinit var tvSpeedLimit: TextView
    private lateinit var tvSafetyEvent: TextView
    private lateinit var tvCameraDistance: TextView
    private lateinit var tvAverageSpeed: TextView
    private lateinit var tvNavSource: TextView
    private lateinit var tvSignalState: TextView
    private lateinit var tvOverspeed: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(3, 8, 16)
        window.navigationBarColor = Color.rgb(3, 8, 16)
        reader = DriveSignalReader(this)
        setContentView(buildUi())
        startSampling()
        DolphinLogger.i("DRIVE_VISION", "Surrounding Vision opened")
    }

    override fun onDestroy() {
        reader.close()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(3, 8, 16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = panelBackground(Color.rgb(7, 20, 30), 0f)
        }

        val back = Button(this).apply {
            text = "‹"
            textSize = 24f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(15, 48, 61))
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(54), dp(46)))

        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titleWrap.addView(label("DRIVE // SURROUNDING VISION", 20f, Color.WHITE, true))
        titleWrap.addView(label("실차 신호 + 내비 안전운전 통합 · 확인되지 않은 데이터는 LAB로 분리", 11f, Color.rgb(145, 174, 184), false))
        header.addView(titleWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val live = label("LIVE / READ ONLY", 11f, Color.rgb(0, 230, 180), true).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = panelBackground(Color.rgb(7, 48, 45), dp(18).toFloat())
        }
        header.addView(live)
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val body = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        vision = SurroundingVisionView(this).apply {
            background = panelBackground(Color.rgb(3, 8, 16), dp(18).toFloat())
        }
        val visionParams = if (landscape) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.15f).apply {
                setMargins(0, 0, dp(10), 0)
            }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(420)).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }
        body.addView(vision, visionParams)

        val sideScroll = ScrollView(this)
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(16))
            background = panelBackground(Color.rgb(7, 18, 28), dp(18).toFloat())
        }
        side.addView(label("SAFETY DRIVE", 12f, Color.rgb(0, 229, 255), true))
        side.addView(label("내비 알림에서 실제 수신된 안전정보만 표시", 10f, Color.rgb(126, 153, 164), false).apply {
            setPadding(0, dp(3), 0, dp(10))
        })

        tvSpeedLimit = valueCard(side, "LIMIT", "-- km/h")
        tvOverspeed = valueCard(side, "SPEED STATUS", "대기")
        tvSafetyEvent = valueCard(side, "SAFETY EVENT", "NO NAV DATA")
        tvCameraDistance = valueCard(side, "DISTANCE", "--")
        tvAverageSpeed = valueCard(side, "SECTION AVG", "--")
        tvNavSource = valueCard(side, "SOURCE", "--")
        tvSignalState = valueCard(side, "SIGNAL LAYERS", "차량 LIVE / ADAS LAB / 객체 NO SIGNAL")

        side.addView(label(
            "표시 원칙\n• 속도·기어: 검증 getter → LIVE\n• BSD·차선·TJA·8 radar: 원시값 → LAB\n• 카메라/제한속도: 지원 내비 알림 → LIVE\n• 실제 객체 track/센서 차선: 미확인 → NO SIGNAL",
            10f,
            Color.rgb(145, 174, 184),
            false
        ).apply { setPadding(0, dp(12), 0, 0) })

        sideScroll.addView(side)
        val sideParams = if (landscape) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.95f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        body.addView(sideScroll, sideParams)

        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun startSampling() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val frame = runCatching { reader.read() }.getOrElse {
                    DolphinLogger.w("DRIVE_VISION", "signal sample failed: " + it.message)
                    DriveFrame()
                }
                val safety = DriveSafetyBus.current()
                withContext(Dispatchers.Main) {
                    render(frame, safety)
                }
                delay(350L)
            }
        }
    }

    private fun render(frame: DriveFrame, safety: SafetySnapshot) {
        vision.update(frame, safety)
        val fresh = safety.isFresh()

        tvSpeedLimit.text = if (fresh && safety.speedLimitKmh > 0) {
            safety.speedLimitKmh.toString() + " km/h"
        } else {
            "-- km/h"
        }

        val currentSpeed = frame.speedKmh
        val limit = safety.speedLimitKmh
        val over = fresh && currentSpeed != null && limit > 0 && currentSpeed > limit
        tvOverspeed.text = when {
            currentSpeed == null -> "차량 속도 NO SIGNAL"
            over -> "OVER  +" + (currentSpeed.toInt() - limit) + " km/h"
            limit > 0 && fresh -> "OK  " + currentSpeed.toInt() + " / " + limit
            else -> "LIVE  " + currentSpeed.toInt() + " km/h"
        }
        tvOverspeed.setTextColor(if (over) Color.rgb(255, 82, 82) else Color.rgb(0, 230, 180))

        tvSafetyEvent.text = if (fresh) safety.eventType else "NO NAV DATA"
        tvCameraDistance.text = if (fresh && safety.distanceMeters > 0) {
            formatDistance(safety.distanceMeters)
        } else {
            "--"
        }
        tvAverageSpeed.text = if (fresh && safety.averageSpeedKmh != null) {
            safety.averageSpeedKmh.toString() + " km/h"
        } else {
            "--"
        }
        tvNavSource.text = if (fresh && safety.sourcePackage.isNotBlank()) {
            safety.sourcePackage
        } else {
            "--"
        }

        val liveParts = mutableListOf<String>()
        if (frame.speedKmh != null) liveParts += "SPEED"
        if (frame.gear != null) liveParts += "GEAR"
        if (frame.turn != "UNKNOWN") liveParts += "TURN"
        val radarCount = frame.radarRaw.count { it != null }
        tvSignalState.text =
            "LIVE " + (if (liveParts.isEmpty()) "NONE" else liveParts.joinToString("/")) +
                "\nLAB BSD=" + (frame.bsdRaw ?: "--") +
                " LANE=" + (frame.laneRaw ?: "--") +
                " TJA=" + (frame.tjaRaw ?: "--") +
                " RADAR=" + radarCount + "/8"
    }

    private fun valueCard(parent: LinearLayout, title: String, initial: String): TextView {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panelBackground(Color.rgb(10, 30, 42), dp(14).toFloat())
        }
        wrap.addView(label(title, 9f, Color.rgb(100, 175, 195), true))
        val value = label(initial, 15f, Color.WHITE, true).apply {
            setPadding(0, dp(3), 0, 0)
        }
        wrap.addView(value)
        parent.addView(wrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(7)) })
        return value
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun panelBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), Color.rgb(21, 63, 76))
        }

    private fun formatDistance(meters: Int): String =
        if (meters >= 1000) String.format(Locale.US, "%.1f km", meters / 1000f) else meters.toString() + " m"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
