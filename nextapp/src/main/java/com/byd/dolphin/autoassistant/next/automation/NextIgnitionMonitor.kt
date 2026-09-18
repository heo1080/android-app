package com.byd.dolphin.autoassistant.next.automation

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.byd.dolphin.autoassistant.next.core.BydPermissionContext
import com.byd.dolphin.autoassistant.next.core.NextLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NextIgnitionMonitor(
    context: Context,
    private val onPowerOn: () -> Unit,
    private val onPowerOff: () -> Unit
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var lastRaw: Int? = null
    private var onReads = 0
    private var isOn = false

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                sample()
                delay(1000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun sample() {
        val raw = readPowerLevel() ?: return
        if (raw != lastRaw) {
            NextLogger.i("IGNITION", "power " + lastRaw + " -> " + raw)
            lastRaw = raw
        }
        when (raw) {
            0 -> {
                onReads = 0
                if (isOn) {
                    isOn = false
                    main.post { onPowerOff() }
                }
            }
            2, 3, 4 -> {
                onReads++
                if (!isOn && onReads >= 2) {
                    isOn = true
                    main.post { onPowerOn() }
                }
            }
            else -> onReads = 0
        }
    }

    private fun readPowerLevel(): Int? = runCatching {
        val clazz = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
        val instance = clazz.getMethod("getInstance", Context::class.java)
            .invoke(null, BydPermissionContext.wrap(app))
        (clazz.getMethod("getPowerLevel").invoke(instance) as? Number)?.toInt()
    }.onFailure {
        NextLogger.e("IGNITION", "getPowerLevel failed", it.cause ?: it)
    }.getOrNull()
}
