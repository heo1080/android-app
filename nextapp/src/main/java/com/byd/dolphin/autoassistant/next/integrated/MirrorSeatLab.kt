package com.byd.dolphin.autoassistant.next.integrated

import android.content.Context
import com.byd.dolphin.autoassistant.next.core.BydPermissionContext
import com.byd.dolphin.autoassistant.next.core.NextLogger

object MirrorSeatLab {
    data class MirrorPosition(val left: Int, val right: Int)

    private const val PREF = "dolphin_next_mirror_lab"
    private const val SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice"

    fun readMirror(context: Context): MirrorPosition? = runCatching {
        val target = setting(context)
        val left = (target.javaClass.getMethod("getLeftViewMirrorFlipAngle").invoke(target) as? Number)?.toInt()
        val right = (target.javaClass.getMethod("getRightViewMirrorFlipAngle").invoke(target) as? Number)?.toInt()
        if (left == null || right == null) null else MirrorPosition(left, right)
    }.onFailure { NextLogger.e("MIRROR_LAB", "read failed", it.cause ?: it) }.getOrNull()

    fun capture(context: Context, reverse: Boolean): MirrorPosition? {
        val pos = readMirror(context) ?: return null
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(if (reverse) "r_left" else "n_left", pos.left)
            .putInt(if (reverse) "r_right" else "n_right", pos.right)
            .putBoolean(if (reverse) "has_r" else "has_n", true)
            .apply()
        NextLogger.i("MIRROR_LAB", "capture reverse=" + reverse + " pos=" + pos)
        return pos
    }

    fun preset(context: Context, reverse: Boolean): MirrorPosition? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val has = p.getBoolean(if (reverse) "has_r" else "has_n", false)
        if (!has) return null
        return MirrorPosition(
            p.getInt(if (reverse) "r_left" else "n_left", 0),
            p.getInt(if (reverse) "r_right" else "n_right", 0)
        )
    }

    fun apply(context: Context, reverse: Boolean): Boolean {
        val pos = preset(context, reverse) ?: return false
        return runCatching {
            val target = setting(context)
            val l = (target.javaClass.getMethod("setLeftViewMirrorFlipAngle", Int::class.javaPrimitiveType)
                .invoke(target, pos.left) as? Number)?.toInt()
            val r = (target.javaClass.getMethod("setRightViewMirrorFlipAngle", Int::class.javaPrimitiveType)
                .invoke(target, pos.right) as? Number)?.toInt()
            Thread.sleep(400)
            val actual = readMirror(context)
            val ok = l == 0 && r == 0 && actual == pos
            NextLogger.i("MIRROR_LAB", "apply reverse=" + reverse + " write=" + l + "/" + r + " expected=" + pos + " actual=" + actual + " verified=" + ok)
            ok
        }.onFailure { NextLogger.e("MIRROR_LAB", "apply failed", it.cause ?: it) }.getOrDefault(false)
    }

    fun scanSeatMemoryApis(context: Context): List<String> {
        val classes = listOf(
            "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            "android.hardware.bydauto.seat.BYDAutoSeatDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
        )
        val keys = listOf("seat", "memory", "position", "driver")
        val out = mutableListOf<String>()
        classes.forEach { name ->
            runCatching {
                val clazz = Class.forName(name)
                clazz.methods
                    .filter { m -> keys.any { m.name.contains(it, true) } }
                    .forEach { m ->
                        out += clazz.simpleName + "." + m.name + "(" +
                            m.parameterTypes.joinToString { it.simpleName } + "):" + m.returnType.simpleName
                    }
            }
        }
        val unique = out.distinct().sorted()
        NextLogger.i("SEAT_MEMORY_LAB", "candidates=" + unique.joinToString(" | "))
        return unique
    }

    private fun setting(context: Context): Any {
        val clazz = Class.forName(SETTING)
        return clazz.getMethod("getInstance", Context::class.java)
            .invoke(null, BydPermissionContext.wrap(context))
            ?: error("setting device null")
    }
}
