package com.byd.dolphin.autoassistant.manager

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object DpiManager {

    fun getCurrentDensity(): String {
        return try {
            val process = Runtime.getRuntime().exec("wm density")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().trim()
            process.waitFor()
            if (output.isNotEmpty()) output else "확인 불가"
        } catch (e: Exception) {
            Log.e("DpiManager", "Failed to get density", e)
            "조회 오류"
        }
    }

    fun setDensity(dpi: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "wm density $dpi"))
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e("DpiManager", "Failed to set density $dpi", e)
            false
        }
    }

    fun resetDensity(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "wm density reset"))
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e("DpiManager", "Failed to reset density", e)
            false
        }
    }
}
