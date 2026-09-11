package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.byd.dolphin.autoassistant.util.DolphinLogger

/** Verified DiLink 3 comfort calls for the hardware fitted to this Dolphin. */
object VehicleComfortManager {
    const val SEAT_DRIVER = 1
    const val SEAT_PASSENGER = 2
    const val HEAT_OFF = 0
    const val HEAT_LOW = 1
    const val HEAT_HIGH = 2

    private const val TAG = "COMFORT"
    private const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    private const val AC_CLASS = "android.hardware.bydauto.ac.BYDAutoAcDevice"
    private val verifyHandler = Handler(Looper.getMainLooper())

    fun getSeatHeatingLevel(context: Context, seat: Int): Int? {
        if (seat != SEAT_DRIVER && seat != SEAT_PASSENGER) return null
        val raw = invokeIntFirst(context, SETTING_CLASS, listOf("getSeatHeatingState", "getSeatHeatingState1"), seat) ?: return null
        return when (raw) {
            1 -> HEAT_OFF
            2 -> HEAT_LOW
            3 -> HEAT_HIGH
            else -> null
        }
    }

    fun setSeatHeatingLevel(context: Context, seat: Int, level: Int): Boolean {
        if (seat != SEAT_DRIVER && seat != SEAT_PASSENGER) return false
        val safeLevel = level.coerceIn(HEAT_OFF, HEAT_HIGH)
        val raw = safeLevel + 1
        val accepted = invokeCommandFirst(context, SETTING_CLASS, listOf("setSeatHeatingState", "setSeatHeatingState1"), seat, raw)
        if (accepted) verifySeatHeating(context.applicationContext, seat, safeLevel, raw, retry = true)
        return accepted
    }

    fun cycleSeatHeating(context: Context, seat: Int): Int {
        val next = when (getSeatHeatingLevel(context, seat)) {
            HEAT_OFF, null -> HEAT_LOW
            HEAT_LOW -> HEAT_HIGH
            else -> HEAT_OFF
        }
        setSeatHeatingLevel(context, seat, next)
        return next
    }

    fun isSteeringWheelHeatingOn(context: Context): Boolean? {
        return when (invokeInt(context, SETTING_CLASS, "getSteeringWheelHeatingState")) {
            1 -> false
            2 -> true
            else -> null
        }
    }

    fun setSteeringWheelHeating(context: Context, enabled: Boolean): Boolean {
        val raw = if (enabled) 2 else 1
        val accepted = invokeCommand(context, SETTING_CLASS, "setSteeringWheelHeatingState", raw)
        if (accepted) verifySteeringHeating(context.applicationContext, enabled, raw, retry = true)
        return accepted
    }

    fun toggleSteeringWheelHeating(context: Context): Boolean {
        val next = !(isSteeringWheelHeatingOn(context) ?: false)
        setSteeringWheelHeating(context, next)
        return next
    }

    fun isAcOn(context: Context): Boolean? {
        return when (invokeInt(context, AC_CLASS, "getAcStartState")) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    fun setAcPower(context: Context, enabled: Boolean): Boolean {
        val method = if (enabled) "start" else "stop"
        val accepted = invokeCommand(context, AC_CLASS, method, 0)
        DolphinLogger.i(TAG, "공조 전원 ${if (enabled) "ON" else "OFF"}: accepted=$accepted")
        return accepted
    }

    fun toggleAcPower(context: Context): Boolean {
        val next = !(isAcOn(context) ?: false)
        setAcPower(context, next)
        return next
    }

    fun setAcFanLevel(context: Context, level: Int): Boolean {
        val safeLevel = level.coerceIn(0, 7)
        val accepted = invokeCommand(context, AC_CLASS, "setAcWindLevel", 0, safeLevel)
        DolphinLogger.i(TAG, "공조 풍량 설정: level=$safeLevel accepted=$accepted")
        return accepted
    }

    private fun verifySeatHeating(context: Context, seat: Int, expected: Int, raw: Int, retry: Boolean) {
        verifyHandler.postDelayed({
            val actual = getSeatHeatingLevel(context, seat)
            if (actual == expected) {
                DolphinLogger.i(TAG, "시트 열선 확인 완료: seat=$seat level=$expected")
            } else if (retry) {
                DolphinLogger.w(TAG, "시트 열선 확인 불일치: seat=$seat expected=$expected actual=$actual, 1회 재시도")
                invokeCommandFirst(context, SETTING_CLASS, listOf("setSeatHeatingState", "setSeatHeatingState1"), seat, raw)
                verifySeatHeating(context, seat, expected, raw, retry = false)
            } else {
                DolphinLogger.w(TAG, "시트 열선 적용 확인 실패: seat=$seat expected=$expected actual=$actual")
            }
        }, 350L)
    }

    private fun verifySteeringHeating(context: Context, expected: Boolean, raw: Int, retry: Boolean) {
        verifyHandler.postDelayed({
            val actual = isSteeringWheelHeatingOn(context)
            if (actual == expected) {
                DolphinLogger.i(TAG, "핸들 열선 확인 완료: enabled=$expected")
            } else if (retry) {
                DolphinLogger.w(TAG, "핸들 열선 확인 불일치: expected=$expected actual=$actual, 1회 재시도")
                invokeCommand(context, SETTING_CLASS, "setSteeringWheelHeatingState", raw)
                verifySteeringHeating(context, expected, raw, retry = false)
            } else {
                DolphinLogger.w(TAG, "핸들 열선 적용 확인 실패: expected=$expected actual=$actual")
            }
        }, 350L)
    }


    private fun invokeCommandFirst(
        context: Context,
        className: String,
        methodNames: List<String>,
        vararg args: Int
    ): Boolean {
        methodNames.forEach { methodName ->
            val result = invokeRaw(context, className, methodName, *args)
            if (result.methodMissing) return@forEach
            val success = result.error == null && when (val value = result.value) {
                null -> result.returnedVoid
                is Number -> value.toInt() == 0
                is Boolean -> value
                else -> false
            }
            if (success) {
                DolphinLogger.i(TAG, "$methodName 명령 전송 성공")
                return true
            }
            DolphinLogger.w(TAG, "$methodName 명령 거부/실패: value=${result.value} error=${result.error?.message}")
        }
        return false
    }

    private fun invokeIntFirst(
        context: Context,
        className: String,
        methodNames: List<String>,
        vararg args: Int
    ): Int? {
        methodNames.forEach { methodName ->
            val result = invokeRaw(context, className, methodName, *args)
            if (result.methodMissing) return@forEach
            if (result.error == null) return (result.value as? Number)?.toInt()
        }
        return null
    }

    private data class InvokeResult(
        val value: Any? = null,
        val returnedVoid: Boolean = false,
        val methodMissing: Boolean = false,
        val error: Throwable? = null
    )

    private fun invokeRaw(
        context: Context,
        className: String,
        methodName: String,
        vararg args: Int
    ): InvokeResult {
        return try {
            val clazz = Class.forName(className)
            val instance = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            val types = Array(args.size) { Int::class.javaPrimitiveType!! }
            val method = try { clazz.getMethod(methodName, *types) } catch (_: NoSuchMethodException) {
                return InvokeResult(methodMissing = true)
            }
            val value = method.invoke(instance, *args.toTypedArray())
            InvokeResult(value = value, returnedVoid = method.returnType == Void.TYPE)
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            DolphinLogger.e(TAG, "$methodName 호출 실패", cause)
            InvokeResult(error = cause)
        }
    }
    private fun invokeCommand(context: Context, className: String, methodName: String, vararg args: Int): Boolean {
        val result = invokeInt(context, className, methodName, *args)
        val success = result == 0
        if (!success) DolphinLogger.w(TAG, "$methodName 명령 거부/실패: result=$result")
        return success
    }

    private fun invokeInt(context: Context, className: String, methodName: String, vararg args: Int): Int? {
        return try {
            val clazz = Class.forName(className)
            val instance = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            val types = Array(args.size) { Int::class.javaPrimitiveType!! }
            val method = clazz.getMethod(methodName, *types)
            (method.invoke(instance, *args.toTypedArray()) as? Number)?.toInt()
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "$methodName 호출 실패", e.cause ?: e)
            null
        }
    }
}
