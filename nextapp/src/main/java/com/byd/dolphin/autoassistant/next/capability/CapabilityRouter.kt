package com.byd.dolphin.autoassistant.next.capability

import android.content.Context
import com.byd.dolphin.autoassistant.next.core.BydPermissionContext
import com.byd.dolphin.autoassistant.next.core.NextLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

enum class CapabilityAccess {
    READ_WRITE_CANDIDATE,
    READ_ONLY,
    BLOCKED,
    MISSING
}

data class CapabilityEntry(
    val key: String,
    val className: String,
    val access: CapabilityAccess,
    val readProbe: String,
    val setterCount: Int,
    val detail: String,
    val timestampMs: Long
)

data class CapabilitySnapshot(
    val entries: Map<String, CapabilityEntry> = emptyMap(),
    val completedAtMs: Long = 0L
) {
    fun access(key: String): CapabilityAccess? = entries[key]?.access
    fun entry(key: String): CapabilityEntry? = entries[key]
}

object CapabilityRouter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(CapabilitySnapshot())
    val snapshot: StateFlow<CapabilitySnapshot> = _snapshot

    @Volatile private var probing = false
    private val instances = ConcurrentHashMap<String, Any>()

    fun start(context: Context) {
        if (probing || _snapshot.value.completedAtMs > 0L) return
        probing = true
        scope.launch {
            try {
                _snapshot.value = probeAll(context.applicationContext)
            } finally {
                probing = false
            }
        }
    }

    fun refresh(context: Context) {
        if (probing) return
        probing = true
        scope.launch {
            try {
                _snapshot.value = probeAll(context.applicationContext)
            } finally {
                probing = false
            }
        }
    }

    fun canRead(key: String): Boolean =
        when (_snapshot.value.access(key)) {
            CapabilityAccess.READ_ONLY,
            CapabilityAccess.READ_WRITE_CANDIDATE -> true
            else -> false
        }

    fun canAttemptWrite(key: String): Boolean =
        _snapshot.value.access(key) == CapabilityAccess.READ_WRITE_CANDIDATE

    private fun probeAll(context: Context): CapabilitySnapshot {
        val now = System.currentTimeMillis()
        val result = linkedMapOf<String, CapabilityEntry>()

        SPECS.forEach { spec ->
            result[spec.key] = probeSpec(context, spec, now)
        }

        val summary = result.values.joinToString(" | ") {
            it.key + "=" + it.access.name +
                " read=" + it.readProbe +
                " setters=" + it.setterCount +
                " detail=" + it.detail.take(180)
        }
        NextLogger.i("CAPABILITY_ROUTER", summary)

        return CapabilitySnapshot(
            entries = result,
            completedAtMs = System.currentTimeMillis()
        )
    }

    private fun probeSpec(
        context: Context,
        spec: Spec,
        now: Long
    ): CapabilityEntry {
        val clazz = try {
            Class.forName(spec.className)
        } catch (t: Throwable) {
            return CapabilityEntry(
                spec.key,
                spec.className,
                CapabilityAccess.MISSING,
                "CLASS_MISSING",
                0,
                t.javaClass.simpleName + ":" + t.message,
                now
            )
        }

        val target = try {
            instance(context, clazz, spec.className)
        } catch (t: Throwable) {
            val cause = root(t)
            return CapabilityEntry(
                spec.key,
                spec.className,
                if (cause is SecurityException) CapabilityAccess.BLOCKED else CapabilityAccess.MISSING,
                "INSTANCE_FAIL",
                0,
                cause.javaClass.simpleName + ":" + cause.message,
                now
            )
        }

        val setters = target.javaClass.methods.filter { method ->
            method.name.startsWith("set") ||
                method.name.startsWith("start") ||
                method.name.startsWith("stop") ||
                method.name.startsWith("turn")
        }

        var readProbe = "NO_SAFE_GETTER"
        var readOk = false
        var blocked = false
        var detail = ""

        val candidates = target.javaClass.methods
            .filter { it.parameterTypes.isEmpty() }
            .filter { method ->
                spec.safeGetters.any { name -> method.name == name } ||
                    (
                        spec.safeGetters.isEmpty() &&
                            (method.name.startsWith("get") || method.name.startsWith("is"))
                    )
            }
            .take(12)

        for (method in candidates) {
            try {
                val value = method.invoke(target)
                readProbe = method.name + "=" + value.toString().take(100)
                readOk = true
                detail = "read success"
                break
            } catch (t: Throwable) {
                val cause = root(t)
                if (cause is SecurityException) {
                    readProbe = method.name + "=SECURITY_EXCEPTION"
                    blocked = true
                    detail = cause.message.orEmpty()
                    break
                } else {
                    readProbe = method.name + "=" + cause.javaClass.simpleName
                    detail = cause.message.orEmpty()
                }
            }
        }

        val access = when {
            blocked -> CapabilityAccess.BLOCKED
            readOk && setters.isNotEmpty() -> CapabilityAccess.READ_WRITE_CANDIDATE
            readOk -> CapabilityAccess.READ_ONLY
            setters.isNotEmpty() -> CapabilityAccess.READ_WRITE_CANDIDATE
            else -> CapabilityAccess.READ_ONLY
        }

        return CapabilityEntry(
            key = spec.key,
            className = spec.className,
            access = access,
            readProbe = readProbe,
            setterCount = setters.size,
            detail = detail.ifBlank { "methods=" + target.javaClass.methods.size },
            timestampMs = now
        )
    }

    private fun instance(context: Context, clazz: Class<*>, key: String): Any {
        instances[key]?.let { return it }
        val method: Method = clazz.getMethod("getInstance", Context::class.java)
        val target = method.invoke(null, BydPermissionContext.wrap(context))
            ?: error("getInstance returned null")
        instances[key] = target
        return target
    }

    private fun root(t: Throwable): Throwable {
        var current = t
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private data class Spec(
        val key: String,
        val className: String,
        val safeGetters: List<String>
    )

    private val SPECS = listOf(
        Spec(
            "AC",
            "android.hardware.bydauto.ac.BYDAutoAcDevice",
            listOf("getAcStartState", "getAcWindLevel")
        ),
        Spec(
            "BODYWORK",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            listOf("getPowerLevel")
        ),
        Spec(
            "SETTING",
            "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            listOf("getEnergyFeedback", "getAVHState")
        ),
        Spec(
            "LIGHT",
            "android.hardware.bydauto.light.BYDAutoLightDevice",
            listOf("getLowBeamState", "getHighBeamState")
        ),
        Spec(
            "GEARBOX",
            "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            listOf("getGearboxAutoModeType", "getEPBState")
        ),
        Spec(
            "SPEED",
            "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            listOf("getCurrentSpeed")
        ),
        Spec(
            "ENERGY",
            "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            listOf("getOperationMode")
        ),
        Spec(
            "PANORAMA",
            "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice",
            listOf("getPanoramaWorkState", "getPanoramaOutputState")
        ),
        Spec(
            "INSTRUMENT",
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            emptyList()
        ),
        Spec(
            "REAR_MIRROR",
            "android.hardware.bydauto.rearviewmirror.BYDAutoRearViewMirrorDevice",
            emptyList()
        ),
        Spec(
            "SEAT",
            "android.hardware.bydauto.seat.BYDAutoSeatDevice",
            emptyList()
        )
    )
}
