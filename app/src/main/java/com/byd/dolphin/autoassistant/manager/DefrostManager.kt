package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.byd.dolphin.autoassistant.util.DolphinLogger

/** Front and rear defrost control using the exact DiLink 3 SDK signature. */
object DefrostManager {
    private const val TAG = "DEFROST"
    private const val AC_CLASS = "android.hardware.bydauto.ac.BYDAutoAcDevice"
    private const val AREA_FRONT = 1
    private const val AREA_REAR = 2
    private const val SOURCE_UI = 0
    private const val STATE_OFF = 0
    private const val STATE_ON = 1
    private val handler = Handler(Looper.getMainLooper())

    fun turnOn(context: Context, showToast: Boolean = true): Boolean =
        setCombined(context, true, showToast)

    fun turnOff(context: Context, showToast: Boolean = true): Boolean =
        setCombined(context, false, showToast)

    fun toggle(context: Context, showToast: Boolean = true): Boolean {
        val next = !isDefrostOn(context)
        setCombined(context, next, showToast)
        return next
    }

    fun onFrontDefrostDetected(context: Context, isFrontDefrostActive: Boolean) {
        if (SettingsManager.isAutoDefrostSyncEnabled(context)) {
            triggerRearDefrost(context, isFrontDefrostActive)
        }
    }

    fun triggerRearDefrost(context: Context, enable: Boolean): Boolean =
        setArea(context, AREA_REAR, enable, verify = true)

    fun isFrontDefrostOn(context: Context): Boolean? = getArea(context, AREA_FRONT)?.let { it == STATE_ON }

    fun isRearDefrostOn(context: Context): Boolean? = getArea(context, AREA_REAR)?.let { it == STATE_ON }

    fun toggleRear(context: Context, showToast: Boolean = true): Boolean {
        val next = !(isRearDefrostOn(context) ?: false)
        val accepted = setArea(context, AREA_REAR, next, verify = true)
        if (showToast) {
            Toast.makeText(
                context,
                if (accepted) "뒷유리 열선 ${if (next) "켜짐" else "꺼짐"}" else "뒷유리 열선 명령 실패",
                Toast.LENGTH_SHORT
            ).show()
        }
        return if (accepted) next else !next
    }

    fun isDefrostOn(context: Context): Boolean {
        val front = getArea(context, AREA_FRONT)
        val rear = getArea(context, AREA_REAR)
        if (front != null || rear != null) return front == STATE_ON || rear == STATE_ON
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LAST_STATE, false)
    }

    private fun setCombined(context: Context, enable: Boolean, showToast: Boolean): Boolean {
        val front = setArea(context, AREA_FRONT, enable, verify = true)
        val rear = setArea(context, AREA_REAR, enable, verify = true)
        val success = front && rear
        if (success) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LAST_STATE, enable).apply()
        }
        DolphinLogger.i(TAG, "통합 성에 제거 ${if (enable) "ON" else "OFF"}: front=$front rear=$rear")
        if (showToast) {
            val message = if (success) {
                if (enable) "앞·뒤 유리 성에 제거 켜짐" else "앞·뒤 유리 성에 제거 꺼짐"
            } else {
                "성에 제거 명령을 확인하지 못했습니다. 진단 로그를 확인하세요."
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        return success
    }

    private fun setArea(context: Context, area: Int, enable: Boolean, verify: Boolean): Boolean {
        val expected = if (enable) STATE_ON else STATE_OFF
        val result = invoke(context, "setAcDefrostState", SOURCE_UI, area, expected)
        val accepted = result == 0
        if (accepted && verify) {
            handler.postDelayed({
                val actual = getArea(context.applicationContext, area)
                if (actual != expected) {
                    DolphinLogger.w(TAG, "성에 제거 read-back 불일치: area=$area expected=$expected actual=$actual, 1회 재시도")
                    setArea(context.applicationContext, area, enable, verify = false)
                } else {
                    DolphinLogger.i(TAG, "성에 제거 read-back 확인: area=$area state=$actual")
                }
            }, 350L)
        } else if (!accepted) {
            DolphinLogger.w(TAG, "setAcDefrostState 거부/실패: area=$area result=$result")
        }
        return accepted
    }

    private fun getArea(context: Context, area: Int): Int? = invoke(context, "getAcDefrostState", area)

    private fun invoke(context: Context, methodName: String, vararg args: Int): Int? {
        return try {
            val clazz = Class.forName(AC_CLASS)
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

    private const val PREF_NAME = "dolphin_defrost_prefs"
    private const val KEY_LAST_STATE = "key_is_defrost_on"
}
