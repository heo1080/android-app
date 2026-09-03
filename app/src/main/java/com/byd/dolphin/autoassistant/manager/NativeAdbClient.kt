package com.byd.dolphin.autoassistant.manager

import android.util.Base64
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/**
 * Android 공식 ADB 바이너리 프로토콜 클라이언트
 * 127.0.0.1:5555로 A_CNXN 및 A_AUTH(RSA 공개키) 패킷을 전송하여
 * 안드로이드 시스템의 "USB 디버깅을 허용하시겠습니까?" 팝업창을 직접 트리거하고,
 * 사용자가 '허용'을 누르면 자체적으로 모든 쉘 명령어를 실행합니다.
 */
object NativeAdbClient {

    private const val TAG = "NativeAdbClient"

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257

    private const val ADB_AUTH_TOKEN = 1
    private const val ADB_AUTH_SIGNATURE = 2
    private const val ADB_AUTH_RSAPUBLICKEY = 3

    private var cachedKeyPair: KeyPair? = null

    private fun getOrCreateKeyPair(): KeyPair {
        cachedKeyPair?.let { return it }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        cachedKeyPair = kp
        return kp
    }

    private fun buildPacket(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val magic = cmd xor -0x1
        var crc = 0
        for (b in data) {
            crc = (crc + (b.toInt() and 0xFF)) and 0xFFFFFFFF.toInt()
        }

        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(data.size)
        buf.putInt(crc)
        buf.putInt(magic)
        if (data.isNotEmpty()) {
            buf.put(data)
        }
        return buf.array()
    }

    private data class AdbMessage(
        val cmd: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val data: ByteArray
    )

    private fun readMessage(input: InputStream): AdbMessage? {
        val header = ByteArray(24)
        var readTotal = 0
        while (readTotal < 24) {
            val r = input.read(header, readTotal, 24 - readTotal)
            if (r < 0) return null
            readTotal += r
        }

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val length = buf.int
        val crc = buf.int
        val magic = buf.int

        val payload = ByteArray(length)
        var payloadRead = 0
        while (payloadRead < length) {
            val r = input.read(payload, payloadRead, length - payloadRead)
            if (r < 0) break
            payloadRead += r
        }

        return AdbMessage(cmd, arg0, arg1, length, payload)
    }

    /**
     * 로컬 127.0.0.1:5555에 접속하여 RSA 인증을 수행하고 권한 부여 스크립트를 실행
     */
    fun connectAndExecute(
        host: String = "127.0.0.1",
        port: Int = 5555,
        commands: List<String>,
        onStatus: (Boolean, String) -> Unit
    ) {
        val socket = Socket()
        try {
            DolphinLogger.i(TAG, "ADB 로컬 데몬 접속 시도: $host:$port")
            socket.connect(InetSocketAddress(host, port), 3000)
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            // 1단계: A_CNXN 연결 패킷 전송 (호스트 정보 전달)
            val banner = "host::DolphinAutoAssistant\u0000".toByteArray()
            val cnxnPacket = buildPacket(A_CNXN, 0x01000000, 4096, banner)
            output.write(cnxnPacket)
            output.flush()

            // 2단계: 디바이스로부터 응답 수신 (A_AUTH 토큰 수신 대기)
            val resp1 = readMessage(input)
            if (resp1 == null) {
                onStatus(false, "ADB 데몬에서 응답이 없습니다.")
                socket.close()
                return
            }

            if (resp1.cmd == A_AUTH) {
                DolphinLogger.i(TAG, "A_AUTH 수신: 디바이스 인증 요청 확인")

                // 3단계: RSA 공개키 패킷(A_AUTH, arg0=3) 전송 -> 안드로이드 'USB 디버깅 허용' 팝업 발생 트리거!
                val kp = getOrCreateKeyPair()
                val pubKey = kp.public as RSAPublicKey
                val pubEncoded = Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)
                val pubData = "$pubEncoded DolphinAssistant@byd\u0000".toByteArray()

                val authPacket = buildPacket(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pubData)
                output.write(authPacket)
                output.flush()

                DolphinLogger.i(TAG, "A_AUTH(공개키) 전송 완료: 화면에 USB 디버깅 허용 팝업이 표출됩니다.")

                // 4단계: 사용자가 화면의 '허용'을 누를 때까지 대기 (최대 30초 대기)
                socket.soTimeout = 30_000
                val resp2 = readMessage(input)

                if (resp2 == null || resp2.cmd != A_CNXN) {
                    onStatus(false, "화면의 'USB 디버깅 항상 허용'을 눌러주셔야 승인됩니다.")
                    socket.close()
                    return
                }
                DolphinLogger.i(TAG, "A_CNXN 수신: USB 디버깅 허용 승인 확인됨!")
            }

            // 5단계: A_OPEN으로 각 명령어 쉘 실행
            for (cmd in commands) {
                val shellPayload = "shell:$cmd\u0000".toByteArray()
                val openPacket = buildPacket(A_OPEN, 1, 0, shellPayload)
                output.write(openPacket)
                output.flush()

                // OKAY 및 종료 대기
                readMessage(input)
                DolphinLogger.i(TAG, "ADB 쉘 실행: $cmd")
            }

            onStatus(true, "차량 ADB 권한이 자체적으로 모두 자동 승인되었습니다!")
            socket.close()
        } catch (e: Exception) {
            DolphinLogger.w(TAG, "ADB 연결 실패 (${e.message}): 포트 5555가 닫혀있거나 응답 대기 초과")
            try { socket.close() } catch (ignored: Exception) {}
            onStatus(false, "차량 로컬 ADB 포트(5555) 미개방: ${e.message}")
        }
    }
}
