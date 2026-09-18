package com.byd.dolphin.autoassistant.manager

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.lang.reflect.Field
import java.lang.reflect.Method

data class TpmsWheel(
    val pressureRaw: Double? = null,
    val pressureStateRaw: Int? = null,
    val signalStateRaw: Int? = null
)

data class TpmsSnapshot(
    val available: Boolean = false,
    val source: String = "NO_SIGNAL",
    val systemStateRaw: Int? = null,
    val frontLeft: TpmsWheel = TpmsWheel(),
    val frontRight: TpmsWheel = TpmsWheel(),
    val rearLeft: TpmsWheel = TpmsWheel(),
    val rearRight: TpmsWheel = TpmsWheel(),
    val unitLabel: String = "RAW"
)

/**
 * Read-only DiLink TPMS reader.
 * No pressure unit conversion is guessed until the vehicle reports a verified unit.
 */
class TpmsReader(context: Context) {
    private val app = context.applicationContext
    private var device: Any? = null
    private var clazz: Class<*>? = null
    private val reported = mutableSetOf<String>()

    fun read(): TpmsSnapshot {
        val dev = getDevice() ?: return TpmsSnapshot()
        val c = clazz ?: dev.javaClass

        val lfArea = staticInt(c, "TYRE_COMMAND_AREA_LEFT_FRONT") ?: 1
        val rfArea = staticInt(c, "TYRE_COMMAND_AREA_RIGHT_FRONT") ?: 2
        val lrArea = staticInt(c, "TYRE_COMMAND_AREA_LEFT_REAR") ?: 3
        val rrArea = staticInt(c, "TYRE_COMMAND_AREA_RIGHT_REAR") ?: 4

        val hasAreaGetter = hasMethod(dev, "getTyrePressureValue", Int::class.javaPrimitiveType!!)
        val hasByTypeGetter = hasMethod(dev, "getTyrePressureValueByType", Int::class.javaPrimitiveType!!)

        fun wheel(area: Int, fallbackGetter: String): TpmsWheel {
            val pressure = when {
                hasAreaGetter -> callNumber(dev, "getTyrePressureValue", area)?.toDouble()
                hasByTypeGetter -> callNumber(dev, "getTyrePressureValueByType", area)?.toDouble()
                else -> callNumber(dev, fallbackGetter)?.toDouble()
            }?.takeIf { it.isFinite() && it >= 0.0 }

            return TpmsWheel(
                pressureRaw = pressure,
                pressureStateRaw = callNumber(dev, "getTyrePressureState", area)?.toInt(),
                signalStateRaw = callNumber(dev, "getTyreSignalState", area)?.toInt()
            )
        }

        val fl = wheel(lfArea, "getTyrePressureLeftFront")
        val fr = wheel(rfArea, "getTyrePressureRightFront")
        val rl = wheel(lrArea, "getTyrePressureLeftRear")
        val rr = wheel(rrArea, "getTyrePressureRightRear")
        val available = listOf(fl, fr, rl, rr).any { it.pressureRaw != null }

        return TpmsSnapshot(
            available = available,
            source = when {
                hasAreaGetter -> "DILINK3_TYRE"
                hasByTypeGetter -> "TYRE_BY_TYPE"
                available -> "PER_WHEEL_GETTER"
                else -> "NO_PRESSURE_VALUE"
            },
            systemStateRaw = callNumber(dev, "getTyreSystemState")?.toInt(),
            frontLeft = fl,
            frontRight = fr,
            rearLeft = rl,
            rearRight = rr,
            unitLabel = "RAW"
        )
    }

    fun close() {
        device = null
        clazz = null
    }

    private fun getDevice(): Any? {
        device?.let { return it }
        return runCatching {
            val c = Class.forName(TYRE_CLASS)
            clazz = c
            c.getMethod("getInstance", Context::class.java)
                .invoke(null, BydPermissionContext.wrap(app))
                ?.also { device = it }
        }.onFailure { report("bind", it.cause ?: it) }.getOrNull()
    }

    private fun hasMethod(target: Any, name: String, vararg types: Class<*>): Boolean =
        runCatching { target.javaClass.getMethod(name, *types) }.isSuccess ||
            runCatching { clazz?.getMethod(name, *types) }.getOrNull() != null

    private fun callNumber(target: Any, name: String, vararg args: Int): Number? {
        val types = Array(args.size) { Int::class.javaPrimitiveType!! }
        val method: Method = runCatching {
            target.javaClass.getMethod(name, *types)
        }.recoverCatching {
            clazz?.getMethod(name, *types) ?: throw it
        }.getOrElse { return null }
        return runCatching {
            method.invoke(target, *args.toTypedArray()) as? Number
        }.onFailure { report(name, it.cause ?: it) }.getOrNull()
    }

    private fun staticInt(c: Class<*>, name: String): Int? {
        val field: Field = runCatching { c.getField(name) }.getOrNull() ?: return null
        return runCatching { field.getInt(null) }.getOrNull()
    }

    private fun report(key: String, error: Throwable) {
        val sig = "${key}:${error.javaClass.simpleName}:${error.message}"
        if (reported.add(sig)) {
            DolphinLogger.w(TAG, "${key} unavailable: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    companion object {
        private const val TAG = "TPMS"
        private const val TYRE_CLASS = "android.hardware.bydauto.tyre.BYDAutoTyreDevice"
    }
}
