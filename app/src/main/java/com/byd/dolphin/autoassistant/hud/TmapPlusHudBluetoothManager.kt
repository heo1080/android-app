package com.byd.dolphin.autoassistant.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID

object TmapPlusHudBluetoothManager {
    private const val TAG = "TMAP_PLUS_HUD"
    private val standardSpp: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val observedHudSpp: UUID = UUID.fromString("fe010000-1234-5678-abcd-00805f9b34fb")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var targetAddress: String? = null

    var isHudDataConnected: Boolean = false
        private set
    var isHudAudioConnected: Boolean = false
        private set
    val isConnected: Boolean get() = isHudDataConnected

    @SuppressLint("MissingPermission")
    fun connectHudData(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            onResult?.invoke(false, "블루투스가 꺼져 있습니다.")
            return
        }
        scope.launch {
            try {
                val paired = adapter.bondedDevices.orEmpty()
                logPairedDevices(paired)
                val target = paired.firstOrNull(::looksLikeHud)
                if (target == null) {
                    withContext(Dispatchers.Main) { onResult?.invoke(false, "페어링 목록에서 Hudaudio/T900 HUD를 찾지 못했습니다.") }
                    return@launch
                }
                adapter.cancelDiscovery()
                closeTransport()
                targetAddress = target.address
                val advertised = target.uuids?.map { it.uuid }.orEmpty()
                val candidates = linkedSetOf<UUID>().apply {
                    if (observedHudSpp in advertised) add(observedHudSpp)
                    addAll(advertised.filter { it == standardSpp || it == observedHudSpp })
                    add(observedHudSpp)
                    add(standardSpp)
                }
                var lastError: Throwable? = null
                for (uuid in candidates) {
                    try {
                        DolphinLogger.i(TAG, "RFCOMM 연결 시험 name=${target.name} address=${maskedAddress(target.address)} uuid=$uuid")
                        val candidate = target.createRfcommSocketToServiceRecord(uuid)
                        candidate.connect()
                        socket = candidate
                        output = candidate.outputStream
                        isHudDataConnected = true
                        DolphinLogger.i(TAG, "HUDDATA 연결 성공 uuid=$uuid")
                        break
                    } catch (t: Throwable) {
                        lastError = t
                        DolphinLogger.w(TAG, "RFCOMM uuid=$uuid 실패: ${t.message}")
                        closeTransport(keepTarget = true)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (isHudDataConnected) onResult?.invoke(true, "HUDDATA 연결 성공 (${target.name}) · T900 브리지 송신 활성")
                    else onResult?.invoke(false, "HUDDATA 연결 실패: ${lastError?.message ?: "지원 UUID 없음"}")
                }
            } catch (e: SecurityException) {
                DolphinLogger.e(TAG, "블루투스 권한 없음", e)
                withContext(Dispatchers.Main) { onResult?.invoke(false, "근처 기기 권한이 필요합니다.") }
            } catch (e: Throwable) {
                DolphinLogger.e(TAG, "HUDDATA 연결 실패", e)
                closeTransport()
                withContext(Dispatchers.Main) { onResult?.invoke(false, "HUDDATA 연결 실패: ${e.message}") }
            }
        }
    }

    /**
     * Do not launch BYD's blocked Bluetooth settings activity. Query the two
     * public audio profiles directly and report whether the bonded HUD itself is connected.
     */
    @SuppressLint("MissingPermission")
    fun connectHudAudio(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            onResult?.invoke(false, "블루투스가 꺼져 있습니다.")
            return
        }
        val target = adapter.bondedDevices.orEmpty().firstOrNull(::looksLikeHud)
        if (target == null) {
            onResult?.invoke(false, "Hudaudio/T900 페어링 기기를 찾지 못했습니다.")
            return
        }
        var anyConnected = false
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val hit = proxy.connectedDevices.any { it.address == target.address }
                anyConnected = anyConnected || hit
                isHudAudioConnected = anyConnected
                DolphinLogger.i(TAG, "audio profile=$profile targetConnected=$hit names=${proxy.connectedDevices.map { it.name }}")
                adapter.closeProfileProxy(profile, proxy)
                onResult?.invoke(
                    anyConnected,
                    if (anyConnected) "HUDAUDIO 프로필 연결 확인" else "HUDAUDIO 페어링됨 · profile=$profile 미연결"
                )
            }
            override fun onServiceDisconnected(profile: Int) {
                DolphinLogger.i(TAG, "audio profile=$profile disconnected")
            }
        }
        val a2dp = adapter.getProfileProxy(context.applicationContext, listener, BluetoothProfile.A2DP)
        val headset = adapter.getProfileProxy(context.applicationContext, listener, BluetoothProfile.HEADSET)
        if (!a2dp && !headset) onResult?.invoke(false, "오디오 프로필 조회를 시작할 수 없습니다.")
        if (isHudDataConnected) DolphinLogger.i(TAG, "HUDDATA 연결 상태에서 HUD 내장 사운드 명령 사용 가능")
    }

    @Synchronized
    fun sendPacket(context: Context, packet: ByteArray): Boolean {
        if (!isHudDataConnected || output == null) {
            DolphinLogger.w(TAG, "HUDDATA 미연결로 송신 불가 bytes=${packet.size}")
            return false
        }
        return try {
            output!!.write(packet)
            output!!.flush()
            DolphinLogger.i(TAG, "T900 packet tx=${packet.joinToString("") { "%02X".format(it.toInt() and 0xFF) }}")
            true
        } catch (e: Throwable) {
            DolphinLogger.e(TAG, "HUD 패킷 송신 실패", e)
            closeTransport()
            false
        }
    }

    fun sendPacket(packet: ByteArray) {
        DolphinLogger.w(TAG, "Context 없는 패킷 송신 생략 bytes=${packet.size}")
    }

    @SuppressLint("MissingPermission")
    private fun logPairedDevices(devices: Set<BluetoothDevice>) {
        DolphinLogger.i(TAG, "페어링 기기 수=${devices.size}")
        devices.forEach { device ->
            val uuidText = device.uuids?.joinToString { it.uuid.toString() } ?: "none"
            DolphinLogger.i(TAG, "bonded name=${device.name} address=${maskedAddress(device.address)} type=${device.type} uuids=$uuidText")
        }
    }

    private fun maskedAddress(address: String?): String {
        val parts = address.orEmpty().split(':')
        return if (parts.size == 6) "XX:XX:XX:${parts.takeLast(3).joinToString(":")}" else "masked"
    }

    @SuppressLint("MissingPermission")
    private fun looksLikeHud(device: BluetoothDevice): Boolean {
        val name = device.name.orEmpty()
        return name.contains("Hudaudio", true) || name.contains("huddata", true) ||
            name.contains("T900", true) || name.contains("T800", true) ||
            name.contains("HUD", true) || name.contains("TMAP", true) ||
            name.contains("TMHP", true) || name.contains("JARVIS", true) ||
            name.contains("INFORA", true)
    }

    @Synchronized
    private fun closeTransport(keepTarget: Boolean = false) {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
        isHudDataConnected = false
        if (!keepTarget) targetAddress = null
    }

    fun disconnect() {
        closeTransport()
        isHudAudioConnected = false
    }
}
