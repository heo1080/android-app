package com.byd.dolphin.autoassistant.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface VehicleGateway {
    suspend fun readSnapshot(): VehicleState
    suspend fun setSeatHeat(seat: Int, level: Int): Boolean
    suspend fun setSteeringHeat(enabled: Boolean): Boolean
    suspend fun setAcPower(enabled: Boolean): Boolean
}

private class BydPermissionContext(base: Context) : ContextWrapper(base.applicationContext) {
    private fun byd(permission: String?) = permission?.startsWith("android.permission.BYDAUTO_") == true

    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (byd(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkCallingOrSelfPermission(permission)

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (byd(permission)) PackageManager.PERMISSION_GRANTED
        else super.checkPermission(permission, pid, uid)

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (!byd(permission)) super.enforceCallingOrSelfPermission(permission, message)
    }

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (!byd(permission)) super.enforcePermission(permission, pid, uid, message)
    }
}

class BydReflectionGateway(context: Context) : VehicleGateway {
    private val app = context.applicationContext
    private val wrapped: Context = BydPermissionContext(app)
    private val devices = mutableMapOf<String, Any>()

    companion object {
        private const val SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
        private const val AC = "android.hardware.bydauto.ac.BYDAutoAcDevice"
        private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        private const val ADAS = "android.hardware.bydauto.adas.BYDAutoADASDevice"
        private const val LIGHT = "android.hardware.bydauto.light.BYDAutoLightDevice"
        private const val SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    }

    override suspend fun readSnapshot(): VehicleState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val driverRaw = int(SETTING, "getSeatHeatingState", 1)
            ?: int(SETTING, "getSeatHeatingState1", 1)
        val passengerRaw = int(SETTING, "getSeatHeatingState", 2)
            ?: int(SETTING, "getSeatHeatingState1", 2)
        val steeringRaw = int(SETTING, "getSteeringWheelHeatingState")
        val acRaw = int(AC, "getAcStartState")
        val driveRaw = int(INSTRUMENT, "getCurrentDriveInterFace")
        val regenRaw = int(SETTING, "getEnergyFeedback")
        val avhRaw = int(ADAS, "getAVHState")
        val bsdRaw = int(ADAS, "getBSDState")
        val turnRaw = int(LIGHT, "getTurnLightState")
        val speed = number(SPEED, "getCurrentSpeed")?.toDouble()

        fun <T> timed(value: T?, source: String, confidence: Float = 1f) =
            value?.let { TimedValue(it, source, now, confidence) }

        VehicleState(
            driverHeat = timed(driverRaw?.let(::decodeSeatHeat), "SETTING.seat.driver"),
            passengerHeat = timed(passengerRaw?.let(::decodeSeatHeat), "SETTING.seat.passenger"),
            steeringHeat = timed(steeringRaw?.let { it == 2 }, "SETTING.steering"),
            acOn = timed(acRaw?.let { it == 1 }, "AC.power"),
            driveRaw = timed(driveRaw, "INSTRUMENT.driveInterface", .75f),
            driveLabel = timed(driveRaw?.let(::decodeDrive), "INSTRUMENT.driveInterface", .70f),
            regenRaw = timed(regenRaw, "SETTING.energyFeedback", .85f),
            autoHoldRaw = timed(avhRaw, "ADAS.AVH", .55f),
            bsdRaw = timed(bsdRaw, "ADAS.BSD", .55f),
            turnRaw = timed(turnRaw, "LIGHT.turn", .85f),
            speedKph = timed(speed, "SPEED.current", .9f),
            lastProbeMs = now
        )
    }

    override suspend fun setSeatHeat(seat: Int, level: Int): Boolean = withContext(Dispatchers.IO) {
        val raw = level.coerceIn(0, 2) + 1
        commandFirst(SETTING, listOf("setSeatHeatingState", "setSeatHeatingState1"), seat, raw)
    }

    override suspend fun setSteeringHeat(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        command(SETTING, "setSteeringWheelHeatingState", if (enabled) 2 else 1)
    }

    override suspend fun setAcPower(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        command(AC, if (enabled) "start" else "stop", 0)
    }

    private fun decodeSeatHeat(raw: Int): Int? = when (raw) {
        1 -> 0; 2 -> 1; 3 -> 2; else -> null
    }

    private fun decodeDrive(raw: Int): String? = when (raw) {
        1 -> "ECO"
        2 -> "NORMAL"
        3 -> "SPORT"
        4 -> "SNOW?"
        else -> null
    }

    private fun device(className: String): Any =
        devices.getOrPut(className) {
            val clazz = Class.forName(className)
            clazz.getMethod("getInstance", Context::class.java).invoke(null, wrapped)
                ?: error("getInstance returned null for $className")
        }

    private fun int(className: String, method: String, vararg args: Int): Int? =
        runCatching { number(className, method, *args)?.toInt() }.getOrNull()

    private fun number(className: String, method: String, vararg args: Int): Number? =
        runCatching {
            val d = device(className)
            val types = Array(args.size) { Int::class.javaPrimitiveType!! }
            val m = d.javaClass.getMethod(method, *types)
            m.invoke(d, *args.toTypedArray()) as? Number
        }.getOrNull()

    private fun commandFirst(className: String, methods: List<String>, vararg args: Int): Boolean =
        methods.any { command(className, it, *args) }

    private fun command(className: String, method: String, vararg args: Int): Boolean =
        runCatching {
            val d = device(className)
            val types = Array(args.size) { Int::class.javaPrimitiveType!! }
            val m = d.javaClass.getMethod(method, *types)
            val result = m.invoke(d, *args.toTypedArray())
            when (result) {
                null -> true
                is Number -> result.toInt() == 0
                is Boolean -> result
                else -> false
            }
        }.getOrDefault(false)
}
