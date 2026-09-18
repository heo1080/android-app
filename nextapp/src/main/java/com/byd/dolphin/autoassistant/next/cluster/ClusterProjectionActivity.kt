package com.byd.dolphin.autoassistant.next.cluster

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.dolphin.autoassistant.next.NextRuntime
import com.byd.dolphin.autoassistant.next.core.NextLogger

class ClusterProjectionActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NextRuntime.start(this)

        val state = NextRuntime.repository.state.value
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 24, 40, 24)
            setBackgroundColor(Color.rgb(4, 10, 16))
        }

        fun label(text: String, size: Float, color: Int) {
            root.addView(TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 8)
            })
        }

        label("DOLPHIN · CLUSTER LAB", 24f, Color.rgb(62, 215, 255))
        label("CUSTOM UI PROJECTION TEST", 13f, Color.LTGRAY)
        label(state.gear.value ?: "—", 58f, Color.WHITE)
        label(
            state.speedKph.value?.let { String.format("%.1f km/h", it) } ?: "— km/h",
            34f,
            Color.WHITE
        )
        label(
            (state.driveMode.value ?: "—") + "  ·  REGEN " + (state.regenMode.value ?: "—"),
            18f,
            Color.rgb(72, 217, 145)
        )
        label(
            "LAB · 자동 종료 20초 · 순정 안전 경고 레이어는 수정하지 않음",
            11f,
            Color.rgb(255, 200, 87)
        )

        setContentView(root)
        NextLogger.i(
            "CLUSTER_CUSTOM_UI",
            "activity created displayId=" + display?.displayId +
                " name=" + display?.name
        )

        handler.postDelayed({
            if (!isFinishing) finish()
        }, 20_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        NextLogger.i("CLUSTER_CUSTOM_UI", "activity destroyed displayId=" + display?.displayId)
        super.onDestroy()
    }
}
