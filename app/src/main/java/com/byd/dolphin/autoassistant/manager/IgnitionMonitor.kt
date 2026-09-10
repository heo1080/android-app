package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DiLink 3 ignition monitor backed by BYDAutoBodyworkDevice.getPowerLevel().
 * Vehicle audit evidence confirms 0=ACC OFF and 2=ACC ON for this Dolphin.
 */
class IgnitionMonitor(
    context: Context,
    private val onPowerOn: () -> Unit,
    private val onPowerOff: () -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollingJob: Job? = null
    private var bodyworkDevice: Any? = null
    private var getPowerLevelMethod: java.lang.reflect.Method? = null
    private var lastRawLevel: Int? = null
    private var candidateOnReads = 0
    private var lastInitError: String? = null

    @Volatile
    var isPowerOn: Boolean = false
        private set

    fun start() {
        if (pollingJob != null) return
        pollingJob = scope.launch {
            while (isActive) {
                sample()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun probeNow() {
        scope.launch { sample() }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        scope.cancel()
    }

    private fun sample() {
        val level = readPowerLevel() ?: return
        if (level != lastRawLevel) {
            DolphinLogger.i(TAG, "DiLink power level: ${lastRawLevel ?: "unknown"} -> $level")
            lastRawLevel = level
        }

        when (level) {
            POWER_OFF -> {
                candidateOnReads = 0
                if (isPowerOn || isCycleConsumed(appContext)) {
                    isPowerOn = false
                    setCycleConsumed(appContext, false)
                    mainHandler.post { onPowerOff() }
                }
            }
            POWER_ON -> {
                candidateOnReads++
                if (!isPowerOn && candidateOnReads >= REQUIRED_ON_READS) {
                    isPowerOn = true
                    if (!isCycleConsumed(appContext)) {
                        setCycleConsumed(appContext, true)
                        mainHandler.post { onPowerOn() }
                    } else {
                        DolphinLogger.i(TAG, "이미 처리한 시동 주기이므로 자동 실행을 건너뜀")
                    }
                }
            }
            else -> candidateOnReads = 0
        }
    }

    private fun readPowerLevel(): Int? {
        return try {
            if (bodyworkDevice == null || getPowerLevelMethod == null) {
                val clazz = Class.forName(BODYWORK_CLASS)
                val getInstance = clazz.getMethod("getInstance", Context::class.java)
                bodyworkDevice = getInstance.invoke(null, appContext)
                getPowerLevelMethod = clazz.getMethod("getPowerLevel")
                DolphinLogger.i(TAG, "BYDAutoBodyworkDevice 연결 완료")
            }
            (getPowerLevelMethod?.invoke(bodyworkDevice) as? Number)?.toInt()
        } catch (e: Exception) {
            val message = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            if (message != lastInitError) {
                lastInitError = message
                DolphinLogger.w(TAG, "시동 상태 읽기 실패: $message")
            }
            bodyworkDevice = null
            getPowerLevelMethod = null
            null
        }
    }

    companion object {
        private const val TAG = "IGNITION"
        private const val BODYWORK_CLASS = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
        private const val POWER_OFF = 0
        private const val POWER_ON = 2
        private const val REQUIRED_ON_READS = 2
        private const val POLL_INTERVAL_MS = 1_000L
        private const val PREF_NAME = "dolphin_ignition_state_v30"
        private const val KEY_CYCLE_CONSUMED = "cycle_consumed"

        /** Called only for a real boot/quick-boot wake, never for a normal Activity open. */
        fun markPotentialNewCycle(context: Context) {
            setCycleConsumed(context.applicationContext, false)
            DolphinLogger.i(TAG, "부팅/퀵부팅 이벤트로 새 시동 주기 대기")
        }

        private fun isCycleConsumed(context: Context): Boolean =
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_CYCLE_CONSUMED, false)

        private fun setCycleConsumed(context: Context, consumed: Boolean) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CYCLE_CONSUMED, consumed)
                .apply()
        }
    }
}
