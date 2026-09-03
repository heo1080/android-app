package com.byd.dolphin.autoassistant.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.UUID

object T900BluetoothManager {

    private const val TAG = "T900Bluetooth"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    var isConnected = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private var connectJob: Job? = null

    @SuppressLint("MissingPermission")
    fun connectToT900(context: Context, onStatusChanged: ((Boolean, String) -> Unit)? = null) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            onStatusChanged?.invoke(false, "블루투스 미지원 기기")
            return
        }

        if (!adapter.isEnabled) {
            onStatusChanged?.invoke(false, "블루투스가 꺼져 있습니다.")
            return
        }

        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                // 페어링된 기기 중 T900, T800, TMAP HUD 검색
                val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
                val targetDevice = pairedDevices.firstOrNull {
                    val name = it.name ?: ""
                    name.contains("T900", ignoreCase = true) ||
                    name.contains("T800", ignoreCase = true) ||
                    name.contains("TMAP", ignoreCase = true) ||
                    name.contains("HUD", ignoreCase = true)
                }

                if (targetDevice == null) {
                    onStatusChanged?.invoke(false, "페어링된 T900 HUD를 찾을 수 없음 (먼저 블루투스 페어링 필요)")
                    return@launch
                }

                onStatusChanged?.invoke(false, "${targetDevice.name} 연결 시도 중...")
                socket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                outputStream = socket?.outputStream
                isConnected = true
                onStatusChanged?.invoke(true, "${targetDevice.name} 연결 성공!")
                Log.i(TAG, "T900 HUD Connected successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "T900 Connection failed", e)
                isConnected = false
                socket?.close()
                socket = null
                outputStream = null
                onStatusChanged?.invoke(false, "연결 실패: ${e.localizedMessage}")
            }
        }
    }

    fun sendPacket(data: ByteArray) {
        if (!isConnected || outputStream == null) return
        scope.launch {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet to T900", e)
                isConnected = false
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
        socket = null
        outputStream = null
        isConnected = false
    }
}
