package com.byd.dolphin.autoassistant.manager

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Read-only capability probe based on public BYD Dolphin reverse-engineering references.
 * It never sends vehicle control commands.  The purpose is to identify which integration
 * route actually exists on the user's DiLink 3.0 build before enabling a fallback.
 */
object ReferenceResearchProbe {
    private const val TAG = "REFERENCE_PROBE"
    private const val BODYWORK = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
    private const val SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    private const val SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    private const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"

    private val interestingMethods = linkedMapOf(
        BODYWORK to listOf(
            "getDriverComfortStage", "setDriverComfortStage",
            "getPassengerComfortStage", "setPassengerComfortStage"
        ),
        SETTING to listOf(
            "getSeatHeatingState", "getSeatHeatingState1",
            "setSeatHeatingState", "setSeatHeatingState1",
            "getSteeringWheelHeatingState", "setSteeringWheelHeatingState"
        ),
        SPEED to listOf("getCurrentSpeed", "isSafeForSeatAdjustment"),
        INSTRUMENT to listOf(
            "sendAutoNaviStatus", "sendSimpleGuidanceInfo", "sendNextPathName",
            "sendCameraGuidanceInfo", "sendSafeGuidanceInfo", "sendMusicState", "sendMusicInfo"
        )
    )

    fun snapshot(context: Context): JSONObject {
        val result = JSONObject()
        val classes = JSONArray()
        interestingMethods.forEach { (className, names) ->
            val item = JSONObject().put("class", className)
            runCatching {
                val clazz = Class.forName(className)
                val actualNames = clazz.methods.map { it.name }.toSet()
                item.put("available", true)
                item.put("methods", JSONArray().apply {
                    names.forEach { name ->
                        put(JSONObject().put("name", name).put("present", name in actualNames))
                    }
                })
            }.onFailure { error ->
                item.put("available", false)
                item.put("error", "${error.javaClass.simpleName}:${error.message.orEmpty()}")
            }
            classes.put(item)
        }
        result.put("classes", classes)

        // Maheidem's reference POC documents a localhost Socket.IO bridge on port 8080.
        // Probe TCP reachability only: no Socket.IO handshake/event is transmitted.
        result.put("referenceSocket8080Open", isLoopbackPortOpen(8080))
        result.put("referencePackages", JSONObject().apply {
            put("br.com.rory.reference", isPackageInstalled(context, "br.com.rory.reference"))
            put("br.com.rory.electro", isPackageInstalled(context, "br.com.rory.electro"))
        })
        return result
    }

    fun logSnapshot(context: Context) {
        val snapshot = snapshot(context)
        DolphinLogger.i(TAG, "BYD reference capability snapshot=${snapshot.toString().take(3500)}")
    }

    private fun isLoopbackPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 250)
            true
        }
    }.getOrDefault(false)

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
