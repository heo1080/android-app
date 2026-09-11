package com.byd.dolphin.autoassistant.manager

import android.content.Context
import com.byd.dolphin.autoassistant.util.DolphinLogger
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Persistent, standards-compliant ADB client for the vehicle's local 5555 endpoint. */
object NativeAdbClient {
    private const val TAG = "LOCAL_ADB"
    private val lock = Any()
    private var connection: Dadb? = null
    private var connectedHost: String? = null

    data class ShellResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val message: String
    )

    fun isPortOpen(host: String = "127.0.0.1", port: Int = 5555): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 400) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun executeShell(
        context: Context,
        command: String,
        host: String = "127.0.0.1",
        port: Int = 5555
    ): ShellResult = synchronized(lock) {
        try {
            val adb = getConnection(context.applicationContext, host, port)
            val result = AtomicReference<ShellResult?>()
            val error = AtomicReference<Throwable?>()
            val thread = Thread({
                try {
                    val response = adb.shell(command)
                    val output = response.allOutput
                    val success = response.exitCode == 0
                    result.set(
                        ShellResult(
                            success = success,
                            exitCode = response.exitCode,
                            output = output,
                            message = if (success) "ok" else output.trim().ifBlank { "exit=${response.exitCode}" }
                        )
                    )
                } catch (t: Throwable) {
                    error.set(t)
                }
            }, "dolphin-adb-shell").apply { isDaemon = true }

            thread.start()
            thread.join(SHELL_TIMEOUT_MS)
            if (thread.isAlive) {
                thread.interrupt()
                closeLocked()
                val message = "ADB shell ${SHELL_TIMEOUT_MS}ms 시간 초과"
                DolphinLogger.w(TAG, "$message: $command")
                return@synchronized ShellResult(false, -1, "", message)
            }

            error.get()?.let { throw if (it is Exception) it else Exception(it) }
            val shellResult = result.get() ?: ShellResult(false, -1, "", "ADB shell 결과 없음")
            val compactOutput = shellResult.output.replace('\n', ' ').trim().take(240)
            DolphinLogger.i(
                TAG,
                "shell exit=${shellResult.exitCode}: $command" +
                    if (compactOutput.isNotBlank()) " output=$compactOutput" else ""
            )
            shellResult
        } catch (e: Exception) {
            closeLocked()
            val message = e.message ?: e.javaClass.simpleName
            DolphinLogger.w(TAG, "shell 실패: $command ($message)")
            ShellResult(false, -1, "", message)
        }
    }

    fun connectAndExecute(
        context: Context,
        host: String = "127.0.0.1",
        port: Int = 5555,
        commands: List<String>,
        onStatus: (Boolean, String) -> Unit
    ) {
        if (!isPortOpen(host, port)) {
            onStatus(false, "$host:$port 포트가 닫혀 있습니다")
            return
        }
        var last = ShellResult(true, 0, "", "ok")
        for (command in commands) {
            last = executeShell(context, command, host, port)
            if (!last.success) break
        }
        onStatus(last.success, if (last.success) "ADB 명령 ${commands.size}개 완료" else last.message)
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun getConnection(context: Context, host: String, port: Int): Dadb {
        connection?.takeIf { connectedHost == "$host:$port" }?.let { return it }
        closeLocked()
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
            DolphinLogger.i(TAG, "차량 ADB용 영구 키 생성")
        }
        val keyPair = AdbKeyPair.read(privateKey, publicKey)
        return createWithTimeout(host, port, keyPair).also {
            connection = it
            connectedHost = "$host:$port"
            DolphinLogger.i(TAG, "ADB 연결 완료: $connectedHost")
        }
    }

    private fun createWithTimeout(host: String, port: Int, keyPair: AdbKeyPair): Dadb {
        val result = AtomicReference<Dadb?>()
        val error = AtomicReference<Throwable?>()
        val cancelled = AtomicBoolean(false)
        val thread = Thread({
            try {
                val candidate = Dadb.create(host, port, keyPair)
                if (cancelled.get()) {
                    runCatching { candidate.close() }
                } else {
                    result.set(candidate)
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }, "dolphin-adb-connect").apply { isDaemon = true }
        thread.start()
        thread.join(CONNECT_TIMEOUT_MS)
        if (thread.isAlive) {
            cancelled.set(true)
            thread.interrupt()
            runCatching { result.getAndSet(null)?.close() }
            throw IllegalStateException("ADB 인증 대기 또는 ${CONNECT_TIMEOUT_MS}ms 연결 시간 초과")
        }
        error.get()?.let { throw if (it is Exception) it else Exception(it) }
        return result.get() ?: throw IllegalStateException("ADB 연결 결과 없음")
    }

    private fun closeLocked() {
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        connectedHost = null
    }

    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SHELL_TIMEOUT_MS = 4_000L
}
