package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * 7-1번 요구사항:
 * 기본 탑재 자동화 비상등 제어
 * - 후진 기어 R: 비상등 점멸 즉시 켬
 * - 중립 N 및 전진 D: 30초 동안 비상등 점멸 유지 (속도 30km/h 초과 시 즉시 OFF)
 * - 파킹 P: 비상등 점멸 즉시 끔
 */
class HazardLightManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var isHazardOn = false
    private var currentGear = Gear.P

    enum class Gear {
        P, R, N, D
    }

    private val autoOffRunnable = Runnable {
        DolphinLogger.i("HazardManager", "N/D 전환 후 30초 경과: 비상등 자동 OFF")
        turnOffHazard()
    }

    fun onGearChanged(newGear: Gear, currentSpeedKmH: Float) {
        if (!SettingsManager.isHazardAutoEnabled(context)) {
            if (isHazardOn) turnOffHazard()
            return
        }

        val previousGear = currentGear
        currentGear = newGear
        DolphinLogger.i("HazardManager", "기어 변속: $previousGear -> $newGear (속도: ${currentSpeedKmH}km/h)")

        when (newGear) {
            Gear.R -> {
                handler.removeCallbacks(autoOffRunnable)
                turnOnHazard()
            }
            Gear.N, Gear.D -> {
                if (previousGear == Gear.R && isHazardOn) {
                    if (currentSpeedKmH >= 30.0f) {
                        DolphinLogger.i("HazardManager", "속도 30km/h 이상으로 비상등 즉시 OFF")
                        turnOffHazard()
                    } else {
                        DolphinLogger.i("HazardManager", "R -> $newGear 전환: 30초 타이머 시작")
                        handler.removeCallbacks(autoOffRunnable)
                        handler.postDelayed(autoOffRunnable, 30_000L)
                    }
                }
            }
            Gear.P -> {
                handler.removeCallbacks(autoOffRunnable)
                turnOffHazard()
            }
        }
    }

    fun onSpeedChanged(speedKmH: Float) {
        if (!SettingsManager.isHazardAutoEnabled(context)) return
        if (isHazardOn && (currentGear == Gear.N || currentGear == Gear.D)) {
            if (speedKmH >= 30.0f) {
                DolphinLogger.i("HazardManager", "속도 30km/h 초과로 비상등 즉시 OFF")
                handler.removeCallbacks(autoOffRunnable)
                turnOffHazard()
            }
        }
    }

    fun turnOnHazard() {
        if (!isHazardOn) {
            isHazardOn = true
            sendBydHazardCommand(true)
        }
    }

    fun turnOffHazard() {
        if (isHazardOn) {
            isHazardOn = false
            handler.removeCallbacks(autoOffRunnable)
            sendBydHazardCommand(false)
        }
    }

    fun toggleHazard() {
        if (isHazardOn) turnOffHazard() else turnOnHazard()
    }

    private fun sendBydHazardCommand(enable: Boolean) {
        DolphinLogger.i("HazardManager", "BYD 비상등 제어 브로드캐스트 전송: $enable")
        val intent = Intent("com.byd.auto.action.HAZARD_LIGHT_CONTROL").apply {
            putExtra("state", if (enable) 1 else 0)
        }
        context.sendBroadcast(intent)
    }

    fun cleanup() {
        handler.removeCallbacks(autoOffRunnable)
    }
}
