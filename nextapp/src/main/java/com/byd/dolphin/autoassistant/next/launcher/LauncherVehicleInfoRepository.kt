package com.byd.dolphin.autoassistant.next.launcher

import android.content.Context
import com.byd.dolphin.autoassistant.next.core.BydPermissionContext
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.vehicle.BydGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

data class TireValue(
    val raw: Double? = null,
    val source: String = ""
)

data class LauncherVehicleInfo(
    val tireFl: TireValue = TireValue(),
    val tireFr: TireValue = TireValue(),
    val tireRl: TireValue = TireValue(),
    val tireRr: TireValue = TireValue(),
    val remainingRangeKm: Double? = null,
    val odometerKm: Double? = null,
    val sinceChargeKm: Double? = null,
    val previousChargeTripKm: Double? = null,
    val lightSummary: String = "—",
    val chargerWorkState: Int? = null,
    val updatedAtMs: Long = 0L
)

class LauncherVehicleInfoRepository(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = BydGateway(app)
    private val _state = MutableStateFlow(LauncherVehicleInfo())
    val state: StateFlow<LauncherVehicleInfo> = _state
    private val devices = ConcurrentHashMap<String, Any>()
    private val methods = ConcurrentHashMap<String, Method>()
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val prefs = app.getSharedPreferences("dolphin_launcher_vehicle_v1", Context.MODE_PRIVATE)
    private var job: Job? = null
    private var lastChargeState: Int? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            discoverOnce()
            while (isActive) {
                sample()
                delay(1800L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val range = readFirstNumber(
            RANGE_CLASSES,
            RANGE_METHODS
        )?.second?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }

        val odometer = readFirstNumber(
            ODOMETER_CLASSES,
            ODOMETER_METHODS
        )?.second?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }

        val chargeState = readFirstNumber(
            listOf("android.hardware.bydauto.charging.BYDAutoChargingDevice"),
            listOf("getChargerWorkState")
        )?.second?.toInt()

        updateChargeTrips(odometer, chargeState)

        val baseline = prefs.getFloat("charge_end_odo", Float.NaN)
            .takeIf { !it.isNaN() }?.toDouble()
        val previousTrip = prefs.getFloat("previous_charge_trip", Float.NaN)
            .takeIf { !it.isNaN() }?.toDouble()
        val sinceCharge = if (odometer != null && baseline != null && odometer >= baseline) {
            odometer - baseline
        } else null

        val tires = readTireValues()
        val lightSummary = readLightSummary()

        _state.value = LauncherVehicleInfo(
            tireFl = tires["FL"] ?: TireValue(),
            tireFr = tires["FR"] ?: TireValue(),
            tireRl = tires["RL"] ?: TireValue(),
            tireRr = tires["RR"] ?: TireValue(),
            remainingRangeKm = range,
            odometerKm = odometer,
            sinceChargeKm = sinceCharge,
            previousChargeTripKm = previousTrip,
            lightSummary = lightSummary,
            chargerWorkState = chargeState,
            updatedAtMs = now
        )
    }

    private fun updateChargeTrips(odometer: Double?, chargeState: Int?) {
        if (chargeState == null) return
        val previousState = lastChargeState
        lastChargeState = chargeState
        if (previousState == chargeState) return

        val baseline = prefs.getFloat("charge_end_odo", Float.NaN)
            .takeIf { !it.isNaN() }?.toDouble()

        if (chargeState == 2 && odometer != null && baseline != null && odometer >= baseline) {
            val completedTrip = odometer - baseline
            prefs.edit().putFloat("previous_charge_trip", completedTrip.toFloat()).apply()
            NextLogger.i(
                "LAUNCHER_TRIP",
                "charge start · previous cycle km=" + completedTrip + " odo=" + odometer
            )
        }

        if (chargeState == 3 && odometer != null) {
            prefs.edit().putFloat("charge_end_odo", odometer.toFloat()).apply()
            NextLogger.i("LAUNCHER_TRIP", "charge finish baseline odo=" + odometer)
        }
    }

    private fun readLightSummary(): String {
        val low = gateway.readNumber(BydGateway.LIGHT, "getLowBeamState")?.toInt()
        val high = gateway.readNumber(BydGateway.LIGHT, "getHighBeamState")?.toInt()
        val position = gateway.readNumber(BydGateway.LIGHT, "getPositionLightState")?.toInt()
        val auto = gateway.readNumber(BydGateway.LIGHT, "getAutoLightState")?.toInt()

        return when {
            high != null && high != 0 -> "상향등"
            low != null && low != 0 -> "하향등"
            position != null && position != 0 -> "미등"
            auto != null && auto != 0 -> "AUTO"
            low != null || high != null || position != null || auto != null -> "OFF"
            else -> "—"
        }
    }

    private fun readTireValues(): Map<String, TireValue> {
        val out = mutableMapOf<String, TireValue>()
        val classes = TIRE_CLASSES
        classes.forEach { className ->
            val target = instance(className) ?: return@forEach
            target.javaClass.methods
                .filter { it.parameterTypes.isEmpty() && Number::class.java.isAssignableFrom(box(it.returnType)) }
                .filter { method -> method.name.contains("press", true) || method.name.contains("tire", true) || method.name.contains("tyre", true) }
                .forEach { method ->
                    val position = tirePosition(method.name) ?: return@forEach
                    val value = runCatching { (method.invoke(target) as? Number)?.toDouble() }.getOrNull()
                    if (value != null && value.isFinite() && value > 0.0 && position !in out) {
                        out[position] = TireValue(value, className.substringAfterLast('.') + "." + method.name)
                    }
                }
        }
        if (out.isNotEmpty()) {
            NextLogger.i(
                "LAUNCHER_TPMS",
                out.entries.joinToString { it.key + "=" + it.value.raw + "@" + it.value.source }
            )
        }
        return out
    }

    private fun tirePosition(name: String): String? {
        val n = name.lowercase()
        return when {
            listOf("frontleft", "leftfront", "fl").any { n.contains(it) } -> "FL"
            listOf("frontright", "rightfront", "fr").any { n.contains(it) } -> "FR"
            listOf("rearleft", "leftrear", "rl").any { n.contains(it) } -> "RL"
            listOf("rearright", "rightrear", "rr").any { n.contains(it) } -> "RR"
            else -> null
        }
    }

    private fun readFirstNumber(
        classNames: List<String>,
        methodNames: List<String>
    ): Pair<String, Number>? {
        classNames.forEach { className ->
            val target = instance(className) ?: return@forEach
            methodNames.forEach { methodName ->
                val key = className + "#" + methodName
                val method = methods[key] ?: runCatching {
                    target.javaClass.getMethod(methodName).also { methods[key] = it }
                }.getOrNull() ?: return@forEach
                val value = runCatching { method.invoke(target) as? Number }.getOrNull()
                if (value != null) {
                    NextLogger.i(
                        "LAUNCHER_VEHICLE",
                        methodName + "=" + value + " source=" + className.substringAfterLast('.')
                    )
                    return methodName to value
                }
            }
        }
        return null
    }

    private fun instance(className: String): Any? {
        devices[className]?.let { return it }
        return runCatching {
            val clazz = Class.forName(className)
            clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, BydPermissionContext.wrap(app))
                ?.also { devices[className] = it }
        }.onFailure {
            reportOnce(className, it.cause ?: it)
        }.getOrNull()
    }

    private fun discoverOnce() {
        val lines = mutableListOf<String>()
        (RANGE_CLASSES + ODOMETER_CLASSES + TIRE_CLASSES).distinct().forEach { className ->
            val target = instance(className) ?: return@forEach
            target.javaClass.methods
                .filter { method ->
                    listOf("range", "mileage", "trip", "odo", "endurance", "remain", "tire", "tyre", "press")
                        .any { method.name.contains(it, true) }
                }
                .take(120)
                .forEach { method ->
                    lines += target.javaClass.simpleName + "." + method.name + "(" +
                        method.parameterTypes.joinToString { it.simpleName } + "):" + method.returnType.simpleName
                }
        }
        NextLogger.i("LAUNCHER_CAPABILITY", lines.distinct().joinToString(" | "))
    }

    private fun reportOnce(key: String, error: Throwable) {
        val signature = key + ":" + error.javaClass.simpleName + ":" + error.message
        if (reported.add(signature)) {
            NextLogger.w("LAUNCHER_VEHICLE", signature)
        }
    }

    private fun box(clazz: Class<*>): Class<*> = when (clazz) {
        java.lang.Integer.TYPE -> Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        else -> clazz
    }

    companion object {
        private val RANGE_CLASSES = listOf(
            BydGateway.ENERGY,
            BydGateway.INSTRUMENT,
            "android.hardware.bydauto.battery.BYDAutoBatteryDevice"
        )
        private val RANGE_METHODS = listOf(
            "getEnduranceMileage",
            "getRemainMileage",
            "getRemainingMileage",
            "getSurplusMileage",
            "getDrivingRange",
            "getCruisingRange"
        )
        private val ODOMETER_CLASSES = listOf(
            BydGateway.INSTRUMENT,
            BydGateway.SPEED,
            BydGateway.ENERGY
        )
        private val ODOMETER_METHODS = listOf(
            "getTotalMileage",
            "getOdometer",
            "getMileage",
            "getVehicleMileage",
            "getAccumulatedMileage"
        )
        private val TIRE_CLASSES = listOf(
            "android.hardware.bydauto.tpms.BYDAutoTPMSDevice",
            "android.hardware.bydauto.tirepressure.BYDAutoTirePressureDevice",
            "android.hardware.bydauto.tyrepressure.BYDAutoTyrePressureDevice",
            BydGateway.INSTRUMENT
        )
    }
}
