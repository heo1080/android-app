package com.byd.dolphin.autoassistant.next.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.byd.dolphin.autoassistant.next.core.BydPermissionContext
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.integrated.IntegratedSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.UUID

data class NavCue(
    val sourcePackage: String,
    val turnType: Int,
    val turnDistanceMeters: Int,
    val nextRoad: String,
    val speedLimit: Int,
    val rawText: String
)

object NextHudBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val observed = UUID.fromString("fe010000-1234-5678-abcd-00805f9b34fb")
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    @Volatile var dataConnected = false
        private set
    @Volatile var audioProfileConnected = false
        private set

    @SuppressLint("MissingPermission")
    fun scanPaired(context: Context): List<String> =
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return@runCatching listOf("BluetoothAdapter unavailable")
            val items = adapter.bondedDevices.orEmpty().map { d ->
                "name=" + d.name + " addr=" + mask(d.address) + " type=" + d.type +
                    " uuids=" + d.uuids?.joinToString { it.uuid.toString() }.orEmpty()
            }
            NextLogger.i("HUD_BT", "paired=" + items.joinToString(" | "))
            items
        }.getOrElse {
            NextLogger.e("HUD_BT", "paired scan failed", it)
            listOf("HUD Bluetooth scan failed: " + it.javaClass.simpleName + ":" + it.message)
        }

    @SuppressLint("MissingPermission")
    fun connectData(context: Context, callback: (Boolean, String) -> Unit) {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) {
            deliver(callback, false, "Bluetooth OFF")
            return
        }
        scope.launch {
            try {
                val target = runCatching {
                    adapter.bondedDevices.orEmpty().firstOrNull(::looksLikeHud)
                }.getOrElse {
                    NextLogger.e("HUD_BT", "bondedDevices failed", it)
                    deliver(callback, false, "HUD Bluetooth permission/API error: " + it.javaClass.simpleName)
                    return@launch
                }
            if (target == null) {
                deliver(callback, false, "T90P/HUDDATA/HUDAUDIO bonded device not found")
                return@launch
            }
            close()
            val advertised = target.uuids?.map { it.uuid }.orEmpty()
            val candidates = linkedSetOf<UUID>().apply {
                addAll(advertised.filter { it == observed || it == spp })
                add(observed)
                add(spp)
            }
            var last = ""
            for (uuid in candidates) {
                try {
                    val s = target.createRfcommSocketToServiceRecord(uuid)
                    s.connect()
                    socket = s
                    output = s.outputStream
                    dataConnected = true
                    val msg = "HUDDATA connected name=" + target.name + " uuid=" + uuid
                    NextLogger.i("HUD_BT", msg)
                    deliver(callback, true, msg)
                    return@launch
                } catch (t: Throwable) {
                    last = t.javaClass.simpleName + ":" + t.message
                    NextLogger.w("HUD_BT", "RFCOMM " + uuid + " failed " + last)
                    close()
                }
            }
            deliver(callback, false, "HUDDATA connect failed " + last)
            } catch (t: Throwable) {
                NextLogger.e("HUD_BT", "connectData fatal guard", t)
                close()
                deliver(
                    callback,
                    false,
                    "HUDDATA error " + t.javaClass.simpleName + ":" + t.message
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun probeAudioProfile(context: Context, callback: (Boolean, String) -> Unit) {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) {
            deliver(callback, false, "Bluetooth OFF")
            return
        }
        val target = runCatching {
            adapter.bondedDevices.orEmpty().firstOrNull(::looksLikeHud)
        }.getOrElse {
            NextLogger.e("HUD_AUDIO", "bondedDevices failed", it)
            deliver(callback, false, "HUDAudio permission/API error: " + it.javaClass.simpleName)
            return
        }
        if (target == null) {
            deliver(callback, false, "HUDAudio/T90P bonded device not found")
            return
        }
        var any = false
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val hit = proxy.connectedDevices.any { it.address == target.address }
                any = any || hit
                audioProfileConnected = any
                val msg = "profile=" + profile + " targetConnected=" + hit +
                    " connected=" + proxy.connectedDevices.map { it.name }
                NextLogger.i("HUD_AUDIO", msg)
                adapter.closeProfileProxy(profile, proxy)
                deliver(callback, any, msg)
            }
            override fun onServiceDisconnected(profile: Int) {
                NextLogger.i("HUD_AUDIO", "profile disconnected=" + profile)
            }
        }
        val profileRequest = runCatching {
            val a2dp = adapter.getProfileProxy(
                context.applicationContext,
                listener,
                BluetoothProfile.A2DP
            )
            val headset = adapter.getProfileProxy(
                context.applicationContext,
                listener,
                BluetoothProfile.HEADSET
            )
            a2dp || headset
        }
        if (profileRequest.isFailure) {
            val error = profileRequest.exceptionOrNull()
            NextLogger.e("HUD_AUDIO", "getProfileProxy failed", error ?: IllegalStateException("unknown"))
            deliver(
                callback,
                false,
                "HUDAudio profile query error: " +
                    (error?.javaClass?.simpleName ?: "unknown")
            )
        } else if (profileRequest.getOrDefault(false).not()) {
            deliver(callback, false, "cannot query A2DP/HEADSET")
        }
    }

    fun sendTestNavigation(context: Context): Boolean =
        sendHudPacket(context, frameNav(50, 60, 350, 7, 300), "TEST_NAV")

    fun sendBrightnessLab(context: Context, level: Int): Boolean {
        val packet = ByteArray(16)
        packet[0] = 0xAA.toByte(); packet[1] = 0x55.toByte(); packet[2] = 0x03
        packet[3] = 0
        packet[4] = level.coerceIn(0, 15).toByte()
        finish(packet)
        return sendHudPacket(context, packet, "BRIGHTNESS_LAB")
    }

    fun forwardCue(context: Context, cue: NavCue) {
        NextLogger.i(
            "NAV_CUE",
            "pkg=" + cue.sourcePackage + " turn=" + cue.turnType + " dist=" + cue.turnDistanceMeters +
                " road=" + cue.nextRoad + " limit=" + cue.speedLimit + " raw=" + cue.rawText.take(500)
        )
        if (IntegratedSettings.clusterTbtEnabled(context)) {
            sendCluster(context, cue)
        }
        if (IntegratedSettings.hudAutoForward(context) && dataConnected) {
            sendHudPacket(
                context,
                frameNav(0, cue.speedLimit, 0, cue.turnType, cue.turnDistanceMeters),
                "NAV_AUTO_LAB"
            )
        }
    }

    fun clearCluster(context: Context) {
        runCatching {
            val device = instrument(context)
            val result = invokeInt(device, "sendAutoNaviStatus", 4)
            NextLogger.i("CLUSTER_TBT", "clear result=" + result)
        }.onFailure { NextLogger.e("CLUSTER_TBT", "clear failed", it.cause ?: it) }
    }

    fun sendClusterTest(context: Context): Pair<Boolean, String> = runCatching {
        val device = instrument(context)
        val status = invokeInt(device, "sendAutoNaviStatus", 2)
        val guidance = invokeInt(device, "sendSimpleGuidanceInfo", 7, 300)
        val road = invokeString(device, "sendNextPathName", "Dolphin Cluster Test")
        val ok = listOf(status, guidance, road).any { it != Int.MIN_VALUE }
        val detail = "status=" + status + " guidance=" + guidance + " road=" + road
        NextLogger.i("CLUSTER_TBT", "manual test " + detail)
        ok to detail
    }.getOrElse {
        val detail = (it.cause ?: it).javaClass.simpleName + ":" + (it.cause ?: it).message
        NextLogger.e("CLUSTER_TBT", "manual test failed", it.cause ?: it)
        false to detail
    }

    fun clusterCapability(context: Context): String = runCatching {
        val names = instrument(context).javaClass.methods.map { it.name }.toSet()
        listOf(
            "sendAutoNaviStatus",
            "sendSimpleGuidanceInfo",
            "sendNextPathName",
            "sendCameraGuidanceInfo",
            "sendSafeGuidanceInfo",
            "sendRestRouteInfo",
            "sendAddressInfo"
        ).joinToString(" · ") { it + "=" + names.contains(it) }
    }.getOrElse { "UNAVAILABLE " + (it.cause ?: it).message }

    private fun sendCluster(context: Context, cue: NavCue) {
        runCatching {
            val device = instrument(context)
            val a = invokeInt(device, "sendAutoNaviStatus", 2)
            val b = invokeInt(device, "sendSimpleGuidanceInfo", cue.turnType, cue.turnDistanceMeters.coerceIn(0, 16_777_214))
            val c = if (cue.nextRoad.isNotBlank()) invokeString(device, "sendNextPathName", cue.nextRoad.take(100)) else 0
            NextLogger.i("CLUSTER_TBT", "status=" + a + " guidance=" + b + " road=" + c)
        }.onFailure { NextLogger.e("CLUSTER_TBT", "send failed", it.cause ?: it) }
    }

    @Synchronized
    private fun sendHudPacket(context: Context, packet: ByteArray, label: String): Boolean {
        if (!dataConnected || output == null) {
            NextLogger.w("HUD_TX", label + " blocked: HUDDATA not connected")
            return false
        }
        return try {
            output!!.write(packet)
            output!!.flush()
            NextLogger.i("HUD_TX", label + " " + packet.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
            true
        } catch (t: Throwable) {
            NextLogger.e("HUD_TX", label + " failed", t)
            close()
            false
        }
    }

    private fun frameNav(speed: Int, limit: Int, cameraDistance: Int, turn: Int, turnDistance: Int): ByteArray {
        val p = ByteArray(16)
        p[0] = 0xAA.toByte(); p[1] = 0x55.toByte(); p[2] = 0x01
        p[3] = speed.coerceIn(0, 255).toByte()
        p[4] = limit.coerceIn(0, 255).toByte()
        putU16(p, 5, cameraDistance)
        p[7] = turn.coerceIn(0, 255).toByte()
        putU16(p, 8, turnDistance)
        finish(p)
        return p
    }

    private fun putU16(p: ByteArray, o: Int, value: Int) {
        val v = value.coerceIn(0, 65535)
        p[o] = ((v ushr 8) and 0xFF).toByte()
        p[o+1] = (v and 0xFF).toByte()
    }

    private fun finish(p: ByteArray) {
        var checksum = 0
        for (i in 0..14) checksum = checksum xor (p[i].toInt() and 0xFF)
        p[15] = checksum.toByte()
    }

    @SuppressLint("MissingPermission")
    private fun looksLikeHud(d: BluetoothDevice): Boolean {
        val n = d.name.orEmpty()
        return listOf("Hudaudio","HUDDATA","T90","T900","T800","TMAP","HUD","TMHP","JARVIS","INFORA")
            .any { n.contains(it, true) }
    }

    private fun mask(address: String?): String {
        val p = address.orEmpty().split(':')
        return if (p.size == 6) "XX:XX:XX:" + p.takeLast(3).joinToString(":") else "masked"
    }

    private fun instrument(context: Context): Any {
        val clazz = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        return clazz.getMethod("getInstance", Context::class.java)
            .invoke(null, BydPermissionContext.wrap(context))
            ?: error("instrument null")
    }

    private fun invokeInt(target: Any, name: String, vararg values: Int): Int {
        val types = Array(values.size) { Int::class.javaPrimitiveType!! }
        return (target.javaClass.getMethod(name, *types).invoke(target, *values.toTypedArray()) as? Number)?.toInt()
            ?: Int.MIN_VALUE
    }

    private fun invokeString(target: Any, name: String, value: String): Int =
        (target.javaClass.getMethod(name, String::class.java).invoke(target, value) as? Number)?.toInt()
            ?: Int.MIN_VALUE

    private fun deliver(
        callback: (Boolean, String) -> Unit,
        ok: Boolean,
        message: String
    ) {
        main.post {
            runCatching { callback(ok, message) }
                .onFailure { NextLogger.e("HUD_CALLBACK", "UI callback failed", it) }
        }
    }

    @Synchronized
    fun close() {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
        dataConnected = false
    }
}
