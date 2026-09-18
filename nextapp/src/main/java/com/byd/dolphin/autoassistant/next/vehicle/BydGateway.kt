package com.byd.dolphin.autoassistant.next.vehicle

import android.content.Context
import com.byd.dolphin.autoassistant.next.core.BydPermissionContext
import com.byd.dolphin.autoassistant.next.core.NextLogger
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class BydGateway(context: Context) {
    private val app = context.applicationContext
    private val devices = ConcurrentHashMap<String, Any>()
    private val methods = ConcurrentHashMap<String, Method>()
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun readNumber(className: String, methodName: String, vararg args: Int): Number? {
        val key = className + "#" + methodName + "/" + args.size
        return try {
            val instance = devices.getOrPut(className) {
                val clazz = Class.forName(className)
                clazz.getMethod("getInstance", Context::class.java)
                    .invoke(null, BydPermissionContext.wrap(app))
                    ?: error("getInstance returned null")
            }
            val method = methods.getOrPut(key) {
                val types = Array(args.size) { Int::class.javaPrimitiveType!! }
                instance.javaClass.getMethod(methodName, *types)
            }
            method.invoke(instance, *args.toTypedArray()) as? Number
        } catch (t: Throwable) {
            devices.remove(className)
            methods.remove(key)
            reportOnce(key, t.cause ?: t)
            null
        }
    }

    fun readIntArray(className: String, methodName: String): IntArray? {
        val key = className + "#" + methodName + "/array"
        return try {
            val instance = devices.getOrPut(className) {
                val clazz = Class.forName(className)
                clazz.getMethod("getInstance", Context::class.java)
                    .invoke(null, BydPermissionContext.wrap(app))
                    ?: error("getInstance returned null")
            }
            val method = methods.getOrPut(key) {
                instance.javaClass.getMethod(methodName)
            }
            when (val value = method.invoke(instance)) {
                is IntArray -> value
                is Array<*> -> value.mapNotNull { (it as? Number)?.toInt() }.toIntArray()
                else -> null
            }
        } catch (t: Throwable) {
            devices.remove(className)
            methods.remove(key)
            reportOnce(key, t.cause ?: t)
            null
        }
    }

    fun command(className: String, methodNames: List<String>, vararg args: Int): Boolean {
        for (name in methodNames) {
            val result = invokeRaw(className, name, *args)
            if (result.missing) continue
            val ok = result.error == null && when (val v = result.value) {
                null -> result.returnedVoid
                is Number -> v.toInt() == 0
                is Boolean -> v
                else -> false
            }
            if (ok) {
                NextLogger.i("BYD_CMD", name + " accepted args=" + args.joinToString())
                return true
            }
        }
        return false
    }

    fun readIntFirst(className: String, methodNames: List<String>, vararg args: Int): Int? {
        for (name in methodNames) {
            val result = invokeRaw(className, name, *args)
            if (result.missing) continue
            if (result.error == null) return (result.value as? Number)?.toInt()
        }
        return null
    }

    private data class RawResult(
        val value: Any? = null,
        val returnedVoid: Boolean = false,
        val missing: Boolean = false,
        val error: Throwable? = null
    )

    private fun invokeRaw(className: String, methodName: String, vararg args: Int): RawResult {
        val key = className + "#" + methodName + "/" + args.size
        return try {
            val instance = devices.getOrPut(className) {
                val clazz = Class.forName(className)
                clazz.getMethod("getInstance", Context::class.java)
                    .invoke(null, BydPermissionContext.wrap(app))
                    ?: error("getInstance returned null")
            }
            val types = Array(args.size) { Int::class.javaPrimitiveType!! }
            val method = try {
                instance.javaClass.getMethod(methodName, *types)
            } catch (_: NoSuchMethodException) {
                return RawResult(missing = true)
            }
            methods[key] = method
            RawResult(value = method.invoke(instance, *args.toTypedArray()), returnedVoid = method.returnType == Void.TYPE)
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            reportOnce(key, cause)
            RawResult(error = cause)
        }
    }

    private fun reportOnce(key: String, t: Throwable) {
        val signature = key + ":" + t.javaClass.simpleName + ":" + t.message
        if (reported.add(signature)) {
            NextLogger.w("BYD_API", key + " unavailable: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    companion object {
        const val SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
        const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        const val ENERGY = "android.hardware.bydauto.energy.BYDAutoEnergyDevice"
        const val ADAS = "android.hardware.bydauto.adas.BYDAutoADASDevice"
        const val LIGHT = "android.hardware.bydauto.light.BYDAutoLightDevice"
        const val SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
        const val GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
        const val AC = "android.hardware.bydauto.ac.BYDAutoAcDevice"
        const val RADAR = "android.hardware.bydauto.radar.BYDAutoRadarDevice"
        const val PANORAMA = "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice"
    }
}
