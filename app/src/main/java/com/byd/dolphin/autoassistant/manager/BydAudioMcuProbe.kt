package com.byd.dolphin.autoassistant.manager

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.lang.reflect.Method

/**
 * v30.4 read/write bridge for the BYD audio device (device 1002).
 *
 * This deliberately uses reflection so the app keeps compiling without the
 * private BYD framework stubs.  Any mutation is performed only by callers that
 * first captured a readable status value and can restore it.
 */
class BydAudioMcuProbe(context: Context) {

    data class Snapshot(
        val available: Boolean,
        val managerClass: String,
        val navSourceState: Int?,
        val mediaSourceState: Int?,
        val hwL1DirectionState: Int?,
        val dspReady: Int?,
        val detail: String
    )

    private val appContext = context.applicationContext
    @Volatile private var manager: Any? = null
    @Volatile private var getIntMethod: Method? = null
    @Volatile private var setIntMethod: Method? = null
    @Volatile private var initAttempted = false

    private fun ensureManager(): Boolean {
        if (manager != null && getIntMethod != null && setIntMethod != null) return true
        if (initAttempted) return false
        synchronized(this) {
            if (manager != null && getIntMethod != null && setIntMethod != null) return true
            if (initAttempted) return false
            initAttempted = true
            try {
                val wrapped = BydPermissionContext.wrap(appContext)
                val candidate = wrapped.getSystemService("auto") ?: appContext.getSystemService("auto")
                if (candidate == null) {
                    DolphinLogger.e(TAG, "BYD auto system service=null")
                    return false
                }
                val clazz = candidate.javaClass
                val getInt = clazz.methods.firstOrNull {
                    it.name == "getInt" &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: throw NoSuchMethodException("getInt(int,int)")
                val setInt = clazz.methods.firstOrNull {
                    it.name == "setInt" &&
                        it.parameterTypes.size == 3 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[2] == Int::class.javaPrimitiveType
                } ?: throw NoSuchMethodException("setInt(int,int,int)")
                manager = candidate
                getIntMethod = getInt
                setIntMethod = setInt
                DolphinLogger.i(TAG, "BYD audio manager ready class=${clazz.name}")
                return true
            } catch (t: Throwable) {
                DolphinLogger.e(TAG, "BYD audio manager init failed", unwrap(t))
                return false
            }
        }
    }

    fun readInt(featureId: Int): Int? {
        if (!ensureManager()) return null
        return try {
            val value = getIntMethod!!.invoke(manager, DEVICE_AUDIO, featureId) as Int
            DolphinLogger.i(TAG, "getInt dev=$DEVICE_AUDIO fid=${hex(featureId)} -> $value")
            value
        } catch (t: Throwable) {
            DolphinLogger.e(TAG, "getInt failed fid=${hex(featureId)}", unwrap(t))
            null
        }
    }

    fun writeInt(featureId: Int, value: Int): Int? {
        if (!ensureManager()) return null
        return try {
            val result = setIntMethod!!.invoke(manager, DEVICE_AUDIO, featureId, value) as Int
            DolphinLogger.i(TAG, "setInt dev=$DEVICE_AUDIO fid=${hex(featureId)} value=$value -> $result")
            result
        } catch (t: Throwable) {
            DolphinLogger.e(TAG, "setInt failed fid=${hex(featureId)} value=$value", unwrap(t))
            null
        }
    }

    fun snapshot(): Snapshot {
        val ok = ensureManager()
        if (!ok) return Snapshot(false, "unavailable", null, null, null, null, "auto service unavailable")
        val mgrName = manager?.javaClass?.name ?: "unknown"
        val nav = readInt(FID_NAV_SOURCE_STATE)
        val media = readInt(FID_MEDIA_SOURCE_STATE)
        val hw = readInt(FID_HW_L1_DIRECTION_STATUS)
        val dsp = readInt(FID_DSP_READY)
        return Snapshot(true, mgrName, nav, media, hw, dsp, "read-only snapshot")
    }

    fun canSafelySweepHwL1(original: Int?): Boolean = original != null && original in 0..255

    companion object {
        private const val TAG = "MCU_AUDIO_PROBE"
        const val DEVICE_AUDIO = 1002

        // Read-only/status signals from public DiCarServer/CarSetting reverse engineering.
        const val FID_MEDIA_SOURCE_STATE = 0x4C60000C
        const val FID_NAV_SOURCE_STATE = 0x4C60001D
        const val FID_HW_L1_DIRECTION_STATUS = 0x35202020
        const val FID_DSP_READY = 0x99000364.toInt()

        // Restore-backed experimental write. Public name: HW_L1_SOUNDING_DIRECTION_SET.
        const val FID_HW_L1_DIRECTION_SET = 0x32B1C020

        private fun hex(value: Int): String = "0x%08X".format(value)
        private fun unwrap(t: Throwable): Throwable = t.cause ?: t
    }
}
