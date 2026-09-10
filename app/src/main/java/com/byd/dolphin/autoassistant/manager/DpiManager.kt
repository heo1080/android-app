package com.byd.dolphin.autoassistant.manager

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger

object DpiManager {

    fun getCurrentDensity(context: Context): String =
        "${context.resources.displayMetrics.densityDpi} DPI"

    fun setDensity(context: Context, dpi: Int): Boolean {
        if (dpi !in 120..640) return false
        val result = NativeAdbClient.executeShell(context.applicationContext, "wm density $dpi")
        DolphinLogger.i("DPI", "set $dpi exit=${result.exitCode} message=${result.message}")
        return result.success
    }

    fun resetDensity(context: Context): Boolean {
        val result = NativeAdbClient.executeShell(context.applicationContext, "wm density reset")
        DolphinLogger.i("DPI", "reset exit=${result.exitCode} message=${result.message}")
        return result.success
    }
}
