package com.byd.dolphin.autoassistant.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID

/**
 * TMAP Plus HUD discovery/SPP diagnostic transport.
 *
 * Connection probing is safe, but payload transmission remains locked until the
 * user's exact HUD protocol is confirmed from a Bluetooth capture. Merely opening
 * Android Bluetooth settings is never reported as an audio connection.
 */
object TmapPlusHudBluetoothManager {
    private const val TAG = "TMAP_PLUS_HUD_DIAGNOSTIC"
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    var isHudDataConnected: Boolean = false
        private set
    var isHudAudioConnected: Boolean = false
        private set
    val isConnected: Boolean get() = isHudDataConnected

    @SuppressLint("MissingPermission")
    fun connectHudData(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        val appContext = context.applicationContext
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
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(false, "페어링 목록에서 티맵 Plus HUD 후보를 찾지 못했습니다. 진단 로그를 보내주세요.")
                    }
                    return@launch
                }

                adapter.cancelDiscovery()
                closeTransport()
                val advertisedSpp = target.uuids?.firstOrNull { it.uuid == sppUuid }?.uuid ?: sppUuid
                DolphinLogger.i(
                    TAG,
                    "SPP 연결 시험: name=${target.name} address=${maskedAddress(target.address)} uuid=$advertisedSpp"
                )
                val candidate = target.createRfcommSocketToServiceRecord(advertisedSpp)
                candidate.connect()
                socket = candidate
                output = candidate.outputStream
                isHudDataConnected = true
                withContext(Dispatchers.Main) {
                    val suffix = if (SettingsManager.isHudProtocolConfirmed(appContext)) "송신 사용 가능" else "프로토콜 미확인 — 송신 잠금"
                    onResult?.invoke(true, "SPP 연결 성공 (${target.name}), $suffix")
                }
            } catch (e: SecurityException) {
                DolphinLogger.e(TAG, "블루투스 권한 없음", e)
                withContext(Dispatchers.Main) { onResult?.invoke(false, "근처 기기 권한이 필요합니다.") }
            } catch (e: Exception) {
                DolphinLogger.e(TAG, "SPP 연결 실패", e)
                closeTransport()
                withContext(Dispatchers.Main) { onResult?.invoke(false, "SPP 연결 실패: ${e.message}") }
            }
        }
    }

    fun connectHudAudio(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        isHudAudioConnected = false
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onSuccess {
            DolphinLogger.i(TAG, "블루투스 오디오 설정 화면 열림 — 연결로 간주하지 않음")
            onResult?.invoke(false, "설정 화면을 열었습니다. 티맵 Plus HUD 오디오 프로필 연결 후 진단 로그를 생성하세요.")
        }.onFailure {
            DolphinLogger.e(TAG, "블루투스 설정 열기 실패", it)
            onResult?.invoke(false, "설정 열기 실패: ${it.message}")
        }
    }

    @Synchronized
    fun sendPacket(context: Context, packet: ByteArray): Boolean {
        if (!SettingsManager.isHudProtocolConfirmed(context)) {
            DolphinLogger.w(TAG, "미확인 티맵 Plus HUD 프로토콜 송신 차단: bytes=${packet.size}")
            return false
        }
        if (!isHudDataConnected || output == null) {
            DolphinLogger.w(TAG, "티맵 Plus HUD SPP 미연결로 송신 불가")
            return false
        }
        return try {
            output?.write(packet)
            output?.flush()
            DolphinLogger.i(TAG, "패킷 송신 ${packet.size} bytes")
            true
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "패킷 송신 실패", e)
            closeTransport()
            false
        }
    }

    /** Compatibility overload; intentionally refuses unscoped payloads. */
    fun sendPacket(packet: ByteArray) {
        DolphinLogger.w(TAG, "Context 없는 미확인 패킷 송신 차단: bytes=${packet.size}")
    }

    @SuppressLint("MissingPermission")
    private fun logPairedDevices(devices: Set<BluetoothDevice>) {
        DolphinLogger.i(TAG, "페어링 기기 수=${devices.size}")
        devices.forEach { device ->
            val uuidText = device.uuids?.joinToString { it.uuid.toString() } ?: "none"
            DolphinLogger.i(
                TAG,
                "bonded name=${device.name} address=${maskedAddress(device.address)} " +
                    "type=${device.type} uuids=$uuidText"
            )
        }
    }

    private fun maskedAddress(address: String?): String {
        val parts = address.orEmpty().split(':')
        return if (parts.size == 6) "XX:XX:XX:${parts.takeLast(3).joinToString(":")}" else "masked"
    }

    @SuppressLint("MissingPermission")
    private fun looksLikeHud(device: BluetoothDevice): Boolean {
        val name = device.name.orEmpty()
        return name.contains("T900", true) || name.contains("T800", true) ||
            name.contains("HUD", true) || name.contains("TMAP", true) ||
            name.contains("TMHP", true) || name.contains("JARVIS", true) ||
            name.contains("INFORA", true)
    }

    @Synchronized
    private fun closeTransport() {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
        isHudDataConnected = false
    }

    fun disconnect() {
        closeTransport()
        isHudAudioConnected = false
    }
}
