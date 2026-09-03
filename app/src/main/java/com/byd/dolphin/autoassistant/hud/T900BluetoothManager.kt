package com.byd.dolphin.autoassistant.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.*

/**
 * TMAP T900 / T800 HUD 전용 듀얼 블루투스 매니저
 * - 데이터 채널(huddata SPP 시각 텔레메트리)
 * - 오디오 채널(hudaudio HUD 자체 스피커 안내음)
 * 두 채널을 완전히 분리하여 독립 페어링 및 연결 상태를 유지합니다.
 */
object T900BluetoothManager {

    private const val TAG = "T900Bluetooth"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    var isHudDataConnected: Boolean = false
        private set

    var isHudAudioConnected: Boolean = false
        private set

    // 하위 호환용 프로퍼티
    val isConnected: Boolean
        get() = isHudDataConnected

    /**
     * 1. huddata 시각 텔레메트리 SPP 데이터 채널 전용 페어링 및 연결
     */
    @SuppressLint("MissingPermission")
    fun connectHudData(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            onResult?.invoke(false, "차량 블루투스가 꺼져 있습니다.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
                var targetDevice: BluetoothDevice? = null

                // T900, T800, TMAP HUD 관련 페어링 기기 검색
                for (device in pairedDevices) {
                    val name = device.name ?: ""
                    if (name.contains("T900", ignoreCase = true) ||
                        name.contains("T800", ignoreCase = true) ||
                        name.contains("HUD", ignoreCase = true) ||
                        name.contains("TMAP", ignoreCase = true)) {
                        targetDevice = device
                        break
                    }
                }

                if (targetDevice == null) {
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(false, "페어링된 T900 HUD 기기를 찾을 수 없습니다. 블루투스 설정을 확인하세요.")
                    }
                    return@launch
                }

                DolphinLogger.i(TAG, "huddata SPP 연결 시도: ${targetDevice.name} (${targetDevice.address})")
                bluetoothSocket?.close()

                val socket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                bluetoothSocket = socket
                outputStream = socket.outputStream
                isHudDataConnected = true

                DolphinLogger.i(TAG, "huddata SPP 데이터 채널 연결 성공!")
                withContext(Dispatchers.Main) {
                    onResult?.invoke(true, "huddata 데이터 채널 연결 성공 (${targetDevice.name})")
                }
            } catch (e: Exception) {
                DolphinLogger.e(TAG, "huddata 연결 오류: ${e.message}", e)
                isHudDataConnected = false
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, "huddata 연결 실패: ${e.message}")
                }
            }
        }
    }

    /**
     * 2. hudaudio HUD 자체 스피커 전용 오디오 채널 페어링 및 연결
     */
    fun connectHudAudio(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        try {
            DolphinLogger.i(TAG, "hudaudio 오디오 스피커 채널 연결을 위한 블루투스 오디오 설정 호출")
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            isHudAudioConnected = true
            onResult?.invoke(true, "블루투스 설정 화면에서 T900 오디오 기기를 연결하세요.")
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "hudaudio 연결 실패", e)
            onResult?.invoke(false, "오디오 설정 호출 실패: ${e.message}")
        }
    }

    // 하위 호환용 연결 함수
    fun connectToT900(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        connectHudData(context, onResult)
    }

    fun sendPacket(packet: ByteArray) {
        if (!isHudDataConnected || outputStream == null) return
        try {
            outputStream?.write(packet)
            outputStream?.flush()
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "패킷 전송 실패", e)
            isHudDataConnected = false
            try { bluetoothSocket?.close() } catch (ignored: Exception) {}
        }
    }

    fun disconnect() {
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isHudDataConnected = false
            isHudAudioConnected = false
            outputStream = null
            bluetoothSocket = null
        }
    }
}
