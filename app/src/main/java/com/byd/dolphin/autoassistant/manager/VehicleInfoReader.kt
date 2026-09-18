package com.byd.dolphin.autoassistant.manager

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.lang.reflect.Method

/**
 * Exact vehicle information exposed by the supplied DiLink 3 framework.
 *
 * Important: the current framework exposes battery %, trip mileage and total
 * mileage, but no confirmed native "remaining driving range" getter. Native
 * range therefore stays null instead of being fabricated.
 */
data class VehicleInfoSnapshot(
    val batteryPercent: Int? = null,
    val currentJourneyKm: Double? = null,
    val totalMileageKm: Int? = null,
    val mileageUnitRaw: Int? = null,
    val mileageValidFlag: Int? = null,
    val chargingWorkState: Int? = null,
    val chargingState: Int? = null,
    val chargingPowerKw: Double? = null,
    val chargingCapacityKwh: Double? = null,
    val chargingRestTimeRaw: IntArray? = null,
    val nativeRemainingRangeKm: Double? = null,
    val chargeStartBatteryPercent: Int? = null,
    val chargeEndBatteryPercent: Int? = null,
    val chargeStartTotalMileageKm: Int? = null,
    val chargeEndTotalMileageKm: Int? = null
)

class VehicleInfoReader(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val devices = mutableMapOf<String, Any>()
    private val methods = mutableMapOf<String, Method>()
    private val reported = mutableSetOf<String>()
    private var previousChargingWorkState: Int? = null

    fun read(): VehicleInfoSnapshot {
        val battery = readNumber(INSTRUMENT, "getBatteryPercent")?.toInt()
            ?.takeIf { it in 0..100 }
        val journey = readNumber(INSTRUMENT, "getCurrentJourneyDriveMileage")?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }
        val totalMileage = readNumber(INSTRUMENT, "getTotalMileage")?.toInt()
            ?.takeIf { it >= 0 }
        val unit = readNumber(INSTRUMENT, "getMileageUnit")?.toInt()
        val valid = readNumber(INSTRUMENT, "getMileageValidFlag")?.toInt()

        val work = readNumber(CHARGING, "getChargerWorkState")?.toInt()
        val chargeState = readNumber(CHARGING, "getChargingState")?.toInt()
        val chargePower = readNumber(CHARGING, "getChargingPower")?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }
        val chargedCapacity = readNumber(CHARGING, "getChargingCapacity")?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }
        val rest = readIntArray(CHARGING, "getChargingRestTime")

        updateChargeSnapshots(work, battery, totalMileage)

        return VehicleInfoSnapshot(
            batteryPercent = battery,
            currentJourneyKm = journey,
            totalMileageKm = totalMileage,
            mileageUnitRaw = unit,
            mileageValidFlag = valid,
            chargingWorkState = work,
            chargingState = chargeState,
            chargingPowerKw = chargePower,
            chargingCapacityKwh = chargedCapacity,
            chargingRestTimeRaw = rest,
            nativeRemainingRangeKm = null,
            chargeStartBatteryPercent = intPref(KEY_START_SOC),
            chargeEndBatteryPercent = intPref(KEY_END_SOC),
            chargeStartTotalMileageKm = intPref(KEY_START_ODO),
            chargeEndTotalMileageKm = intPref(KEY_END_ODO)
        )
    }

    fun nativeRangeStatus(): String =
        "NO_CONFIRMED_GETTER: supplied DiLink3 framework exposes battery/trip/odometer/charging data but no native remaining-range method"

    private fun updateChargeSnapshots(work: Int?, battery: Int?, totalMileage: Int?) {
        val previous = previousChargingWorkState
        previousChargingWorkState = work
        if (work == null) return

        if (work == CHARGING_WORK_START && previous != CHARGING_WORK_START) {
            prefs.edit().apply {
                battery?.let { putInt(KEY_START_SOC, it) }
                totalMileage?.let { putInt(KEY_START_ODO, it) }
                putLong(KEY_START_AT, System.currentTimeMillis())
            }.apply()
            DolphinLogger.i(TAG, "charge-start snapshot soc=$battery odo=$totalMileage")
        }

        if (previous == CHARGING_WORK_START &&
            (work == CHARGING_WORK_FINISH || work == CHARGING_WORK_TERMINATE)
        ) {
            prefs.edit().apply {
                battery?.let { putInt(KEY_END_SOC, it) }
                totalMileage?.let { putInt(KEY_END_ODO, it) }
                putLong(KEY_END_AT, System.currentTimeMillis())
            }.apply()
            DolphinLogger.i(TAG, "charge-end snapshot soc=$battery odo=$totalMileage work=$work")
        }
    }

    private fun intPref(key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    private fun readNumber(className: String, methodName: String, vararg args: Int): Number? {
        val key = "$className#$methodName/${args.size}"
        return try {
            val device = devices.getOrPut(className) {
                val clazz = Class.forName(className)
                clazz.getMethod("getInstance", Context::class.java)
                    .invoke(null, BydPermissionContext.wrap(app))
                    ?: error("getInstance returned null")
            }
            val method = methods.getOrPut(key) {
                val types = Array(args.size) { Int::class.javaPrimitiveType!! }
                device.javaClass.getMethod(methodName, *types)
            }
            method.invoke(device, *args.toTypedArray()) as? Number
        } catch (t: Throwable) {
            devices.remove(className)
            methods.remove(key)
            report(key, t.cause ?: t)
            null
        }
    }

    private fun readIntArray(className: String, methodName: String): IntArray? {
        val key = "$className#$methodName/0"
        return try {
            val device = devices.getOrPut(className) {
                val clazz = Class.forName(className)
                clazz.getMethod("getInstance", Context::class.java)
                    .invoke(null, BydPermissionContext.wrap(app))
                    ?: error("getInstance returned null")
            }
            val method = methods.getOrPut(key) { device.javaClass.getMethod(methodName) }
            method.invoke(device) as? IntArray
        } catch (t: Throwable) {
            devices.remove(className)
            methods.remove(key)
            report(key, t.cause ?: t)
            null
        }
    }

    private fun report(key: String, t: Throwable) {
        val signature = "$key:${t.javaClass.simpleName}:${t.message}"
        if (reported.add(signature)) {
            DolphinLogger.w(TAG, "$key unavailable: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "VEHICLE_INFO"
        private const val PREFS = "vehicle_charge_snapshots_v1"
        private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        private const val CHARGING = "android.hardware.bydauto.charging.BYDAutoChargingDevice"

        private const val CHARGING_WORK_START = 2
        private const val CHARGING_WORK_FINISH = 3
        private const val CHARGING_WORK_TERMINATE = 4

        private const val KEY_START_SOC = "charge_start_soc"
        private const val KEY_END_SOC = "charge_end_soc"
        private const val KEY_START_ODO = "charge_start_odo"
        private const val KEY_END_ODO = "charge_end_odo"
        private const val KEY_START_AT = "charge_start_at"
        private const val KEY_END_AT = "charge_end_at"
    }
}
