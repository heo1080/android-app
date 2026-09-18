package com.byd.dolphin.autoassistant.core.vehicle

import android.content.Context
import com.byd.dolphin.autoassistant.core.NextLog
import com.byd.dolphin.autoassistant.core.model.VehicleState
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class BydVehicleGateway(context: Context) {
    private val app = context.applicationContext
    private val devices = ConcurrentHashMap<String, Any>()
    private val methods = ConcurrentHashMap<String, Method>()
    private val errors = ConcurrentHashMap.newKeySet<String>()

    fun readState(previous: VehicleState = VehicleState()): VehicleState {
        val speed = readNumber(SPEED, "getCurrentSpeed")?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..300.0 }
        val gearRaw = readInt(GEARBOX, "getCurrentGear")
        val epbRaw = readInt(GEARBOX, "getEPBState")
        val driveRaw = readInt(INSTRUMENT, "getCurrentDriveInterFace")
        val regenRaw = readInt(SETTING, "getEnergyFeedback")
        val snowRaw = readInt(ENERGY, "getRoadSurfaceMode")
        val avhRaw = readInt(ADAS, "getAVHState")
        val brake = readInt(SPEED, "getBrakeDeepness")
        val accel = readInt(SPEED, "getAccelerateDeepness")
        val tjaRaw = readInt(ADAS, "getTJAState")
        val bsdRaw = readInt(ADAS, "getBSDState")
        val turnRaw = readInt(LIGHT, "getTurnLightState")
        val frontLeft = readInt(RADAR, "getRadarObstacleDistance", 7)
        val frontRight = readInt(RADAR, "getRadarObstacleDistance", 8)

        val explicitHold = when (avhRaw) {
            2 -> true
            0 -> false
            else -> null
        }
        val fallbackHold = if (avhRaw == 1 && gearRaw == 2 && speed != null) {
            when {
                speed > 0.5 || (accel ?: 0) > 0 -> false
                previous.autoHoldHolding == true && speed <= 0.3 -> true
                (brake ?: 0) > 0 -> previous.autoHoldHolding
                else -> previous.autoHoldHolding
            }
        } else null

        val seatDriver = getSeatHeat(1)
        val seatPassenger = getSeatHeat(2)
        val steering = when (readInt(SETTING, "getSteeringWheelHeatingState")) {
            1 -> false
            2 -> true
            else -> null
        }
        val ac = when (readInt(AC, "getAcStartState")) {
            0 -> false
            1 -> true
            else -> null
        }

        val any = listOf(speed, gearRaw, driveRaw, regenRaw, avhRaw, bsdRaw).any { it != null }

        return VehicleState(
            connected = any,
            sampledAtMs = System.currentTimeMillis(),
            speedKmh = speed,
            gear = decodeGear(gearRaw),
            gearRaw = gearRaw,
            epbApplied = when (epbRaw) { 1 -> false; 3 -> true; else -> null },
            epbRaw = epbRaw,
            driveMode = decodeDriveMode(driveRaw),
            driveModeRaw = driveRaw,
            regenMode = when (regenRaw) { 1 -> "STANDARD"; 2 -> "HIGH"; else -> null },
            regenRaw = regenRaw,
            snowMode = snowRaw?.let { it == 2 },
            snowRaw = snowRaw,
            avhRaw = avhRaw,
            autoHoldHolding = explicitHold ?: fallbackHold,
            brakeDepth = brake,
            acceleratorDepth = accel,
            iccActive = when (tjaRaw) { 0, 1 -> false; 2, 3 -> true; else -> null },
            tjaRaw = tjaRaw,
            bsdRaw = bsdRaw,
            turnDirection = decodeTurn(turnRaw),
            turnRaw = turnRaw,
            frontLeftCm = frontLeft?.takeIf { it in 0..155 },
            frontRightCm = frontRight?.takeIf { it in 0..155 },
            driverSeatHeat = seatDriver,
            passengerSeatHeat = seatPassenger,
            steeringHeat = steering,
            acOn = ac
        )
    }

    fun setSeatHeat(seat: Int, level: Int): Boolean {
        if (seat !in 1..2 || level !in 0..2) return false
        val raw = level + 1
        return commandFirst(SETTING, listOf("setSeatHeatingState", "setSeatHeatingState1"), seat, raw)
    }

    fun setSteeringHeat(enabled: Boolean): Boolean =
        command(SETTING, "setSteeringWheelHeatingState", if (enabled) 2 else 1)

    fun setAcPower(enabled: Boolean): Boolean =
        command(AC, if (enabled) "start" else "stop", 0)

    private fun getSeatHeat(seat: Int): Int? {
        val raw = readIntFirst(SETTING, listOf("getSeatHeatingState", "getSeatHeatingState1"), seat)
        return when (raw) { 1 -> 0; 2 -> 1; 3 -> 2; else -> null }
    }

    private fun decodeGear(raw: Int?): String? = when (raw) {
        0 -> "N"
        1 -> "R"
        2 -> "D"
        3 -> "P"
        else -> null
    }

    private fun decodeDriveMode(raw: Int?): String? = when (raw) {
        1 -> "ECO"
        2 -> "NORMAL"
        3 -> "SPORT"
        4 -> "SNOW"
        else -> null
    }

    private fun decodeTurn(raw: Int?): String? = when (raw) {
        1 -> "OFF"
        2, 3 -> "LEFT"
        4, 5 -> "RIGHT"
        6, 7, 8 -> "HAZARD"
        null -> null
        else -> "UNKNOWN"
    }

    private fun commandFirst(className: String, names: List<String>, vararg args: Int): Boolean {
        names.forEach { name ->
            val result = invoke(className, name, *args)
            if (result.available && result.error == null) {
                val accepted = when (val value = result.value) {
                    null -> result.returnsVoid
                    is Number -> value.toInt() == 0
                    is Boolean -> value
                    else -> false
                }
                if (accepted) return true
            }
        }
        return false
    }

    private fun command(className: String, method: String, vararg args: Int): Boolean {
        val result = invoke(className, method, *args)
        return result.error == null && when (val value = result.value) {
            null -> result.returnsVoid
            is Number -> value.toInt() == 0
            is Boolean -> value
            else -> false
        }
    }

    private fun readIntFirst(className: String, names: List<String>, vararg args: Int): Int? {
        names.forEach { name ->
            val result = invoke(className, name, *args)
            if (result.available && result.error == null) {
                return (result.value as? Number)?.toInt()
            }
        }
        return null
    }

    private fun readInt(className: String, method: String, vararg args: Int): Int? =
        readNumber(className, method, *args)?.toInt()

    private fun readNumber(className: String, method: String, vararg args: Int): Number? {
        val result = invoke(className, method, *args)
        return if (result.error == null) result.value as? Number else null
    }

    private data class InvokeResult(
        val value: Any? = null,
        val returnsVoid: Boolean = false,
        val available: Boolean = true,
        val error: Throwable? = null
    )

    private fun invoke(className: String, methodName: String, vararg args: Int): InvokeResult {
        val key = className + "#" + methodName + "/" + args.size
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
            InvokeResult(
                value = method.invoke(device, *args.toTypedArray()),
                returnsVoid = method.returnType == Void.TYPE
            )
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            if (errors.add(key + ":" + cause.javaClass.simpleName + ":" + cause.message)) {
                NextLog.w("BYD_GATEWAY", key + " unavailable: " + cause.javaClass.simpleName + ": " + cause.message)
            }
            devices.remove(className)
            methods.remove(key)
            InvokeResult(
                available = cause !is NoSuchMethodException,
                error = cause
            )
        }
    }

    companion object {
        private const val SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
        private const val GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
        private const val SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
        private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        private const val ENERGY = "android.hardware.bydauto.energy.BYDAutoEnergyDevice"
        private const val ADAS = "android.hardware.bydauto.adas.BYDAutoADASDevice"
        private const val LIGHT = "android.hardware.bydauto.light.BYDAutoLightDevice"
        private const val RADAR = "android.hardware.bydauto.radar.BYDAutoRadarDevice"
        private const val AC = "android.hardware.bydauto.ac.BYDAutoAcDevice"
    }
}
