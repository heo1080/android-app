package com.byd.dolphin.autoassistant.core.display

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class SplitGhostActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val finishTask = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(finishTask)
        handler.postDelayed(finishTask, 4_500L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishTask)
        super.onDestroy()
    }
}
