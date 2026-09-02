package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

class HazardLightManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var isHazardOn = false
    private var currentGear = Gear.P

    enum class Gear { P, R, N, D }

    private val autoOffRunnable = Runnable {
        Log.d("HazardManager", "30초 경과로 인한 비상등 자동 OFF")
        turnOffHazard()
    }

    fun onGearChanged(newGear: Gear, currentSpeedKmH: Float) {
        if (!SettingsManager.isHazardAutoEnabled(context)) {
            if (isHazardOn) turnOffHazard()
            return
        }

        val previousGear = currentGear
        currentGear = newGear

        when (newGear) {
            Gear.R -> {
                handler.removeCallbacks(autoOffRunnable)
                turnOnHazard()
            }
            Gear.N, Gear.D -> {
                if (previousGear == Gear.R && isHazardOn) {
                    if (currentSpeedKmH >= 30.0f) {
                        turnOffHazard()
                    } else {
                        handler.removeCallbacks(autoOffRunnable)
                        handler.postDelayed(autoOffRunnable, 30_000L)
                    }
                }
            }
            Gear.P -> {
                handler.removeCallbacks(autoOffRunnable)
                if (previousGear == Gear.R) {
                    turnOffHazard()
                }
            }
        }
    }

    fun onSpeedChanged(speedKmH: Float) {
        if (!SettingsManager.isHazardAutoEnabled(context)) return

        if (isHazardOn && (currentGear == Gear.N || currentGear == Gear.D)) {
            if (speedKmH >= 30.0f) {
                Log.d("HazardManager", "속도 30km/h 초과로 비상등 즉시 OFF")
                handler.removeCallbacks(autoOffRunnable)
                turnOffHazard()
            }
        }
    }

    private fun turnOnHazard() {
        if (!isHazardOn) {
            isHazardOn = true
            sendBydHazardCommand(true)
        }
    }

    private fun turnOffHazard() {
        if (isHazardOn) {
            isHazardOn = false
            handler.removeCallbacks(autoOffRunnable)
            sendBydHazardCommand(false)
        }
    }

    private fun sendBydHazardCommand(enable: Boolean) {
        Log.i("HazardManager", "비상등 제어: $enable")
        val intent = Intent("com.byd.auto.action.HAZARD_LIGHT_CONTROL").apply {
            putExtra("state", if (enable) 1 else 0)
        }
        context.sendBroadcast(intent)
    }

    fun cleanup() {
        handler.removeCallbacks(autoOffRunnable)
    }
}
