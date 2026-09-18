package com.byd.dolphin.autoassistant.next.integrated

import android.content.Context
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.system.NextAdb
import com.byd.dolphin.autoassistant.next.vehicle.BydGateway

object VehicleActionController {
    data class ActionResult(val accepted: Boolean, val detail: String)

    fun setSeatHeat(context: Context, seat: Int, level: Int): ActionResult {
        val raw = level.coerceIn(0, 2) + 1
        val ok = BydGateway(context).command(
            BydGateway.SETTING,
            listOf("setSeatHeatingState", "setSeatHeatingState1"),
            seat, raw
        )
        return log("seatHeat", ok, "seat=" + seat + " level=" + level + " raw=" + raw)
    }

    fun setSteeringHeat(context: Context, enabled: Boolean): ActionResult {
        val ok = BydGateway(context).command(
            BydGateway.SETTING,
            listOf("setSteeringWheelHeatingState"),
            if (enabled) 2 else 1
        )
        return log("steeringHeat", ok, "enabled=" + enabled)
    }

    fun setAcPower(context: Context, enabled: Boolean): ActionResult {
        val ok = BydGateway(context).command(
            BydGateway.AC,
            listOf(if (enabled) "start" else "stop"),
            0
        )
        return log("acPower", ok, "enabled=" + enabled)
    }

    fun setFanLevel(context: Context, level: Int): ActionResult {
        val safe = level.coerceIn(0, 7)
        val ok = BydGateway(context).command(
            BydGateway.AC,
            listOf("setAcWindLevel"),
            0, safe
        )
        return log("fan", ok, "level=" + safe)
    }

    fun setTemperature(context: Context, area: Int, temperatureC: Int): ActionResult {
        val safe = temperatureC.coerceIn(17, 32)
        val ok = BydGateway(context).command(
            BydGateway.AC,
            listOf("setAcTemperature"),
            area.coerceIn(1, 2), safe, 0, 0
        )
        return log("temperature", ok, "area=" + area + " temp=" + safe)
    }

    fun setDefrost(context: Context, front: Boolean, enabled: Boolean): ActionResult {
        val area = if (front) 1 else 2
        val ok = BydGateway(context).command(
            BydGateway.AC,
            listOf("setAcDefrostState"),
            0, area, if (enabled) 1 else 0
        )
        return log("defrost", ok, "area=" + area + " enabled=" + enabled)
    }

    fun toggleInsideLightLab(context: Context, on: Boolean): ActionResult {
        val sdk = BydGateway(context).command(
            BydGateway.SETTING,
            listOf("turnOffInsideLight"),
            if (on) 2 else 1
        )
        if (sdk) {
            val detail = "SDK accepted · physical result must be observed"
            NextLogger.i("LAB_LIGHT", detail)
            return ActionResult(true, detail)
        }

        val value = if (on) 1 else 0
        val shell = if (NextAdb.isPortOpen()) {
            NextAdb.shell(
                context,
                "service call autoservice 6 i32 1023 i32 1330643002 i32 " + value
            )
        } else null
        val detail = if (shell == null) {
            "SDK rejected · local ADB unavailable for FID 1330643002"
        } else {
            "SDK rejected · LAB FID write exit=" + shell.exitCode +
                " out=" + shell.output.replace("\n", " ").take(160)
        }
        NextLogger.i("LAB_LIGHT", detail)
        return ActionResult(shell?.success == true, detail)
    }

    fun discoverBodyMethods(context: Context, keyword: String): List<String> {
        val classes = listOf(
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            "android.hardware.bydauto.rearviewmirror.BYDAutoRearViewMirrorDevice"
        )
        val matches = mutableListOf<String>()
        classes.forEach { className ->
            runCatching {
                val clazz = Class.forName(className)
                clazz.methods
                    .filter { it.name.contains(keyword, true) }
                    .forEach { method ->
                        matches += clazz.simpleName + "." + method.name +
                            "(" + method.parameterTypes.joinToString { it.simpleName } + ")"
                    }
            }
        }
        NextLogger.i("CAPABILITY", keyword + "=" + matches.joinToString(" | "))
        return matches.distinct().sorted()
    }

    fun discoverTrunk(context: Context): List<String> =
        listOf("trunk", "luggage", "backDoor", "rearDoor")
            .flatMap { discoverBodyMethods(context, it) }.distinct()

    fun discoverSunshade(context: Context): List<String> =
        listOf("sunshade", "shade", "moonRoof", "sunRoof")
            .flatMap { discoverBodyMethods(context, it) }.distinct()

    private fun log(name: String, ok: Boolean, detail: String): ActionResult {
        NextLogger.i("VEHICLE_ACTION", name + " accepted=" + ok + " " + detail)
        return ActionResult(ok, detail)
    }
}
