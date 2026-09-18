package com.byd.dolphin.autoassistant.core.display

import android.content.Context
import com.byd.dolphin.autoassistant.core.NextLog
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LocalAdbShell(private val context: Context) {
    data class Result(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val message: String
    )

    private val lock = Any()
    private var connection: Dadb? = null

    fun isPortOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), 400) }
        true
    } catch (_: Exception) {
        false
    }

    fun exec(command: String): Result = synchronized(lock) {
        try {
            val adb = connection ?: connect().also { connection = it }
            val result = AtomicReference<Result?>()
            val error = AtomicReference<Throwable?>()
            val thread = Thread({
                try {
                    val response = adb.shell(command)
                    val ok = response.exitCode == 0
                    result.set(
                        Result(
                            ok,
                            response.exitCode,
                            response.allOutput,
                            if (ok) "ok" else response.allOutput.trim().ifBlank {
                                "exit=" + response.exitCode
                            }
                        )
                    )
                } catch (t: Throwable) {
                    error.set(t)
                }
            }, "next-adb-shell").apply { isDaemon = true }
            thread.start()
            thread.join(SHELL_TIMEOUT)
            if (thread.isAlive) {
                thread.interrupt()
                close()
                return@synchronized Result(false, -1, "", "ADB shell timeout")
            }
            error.get()?.let { throw it }
            val value = result.get() ?: Result(false, -1, "", "no result")
            NextLog.d("ADB", command + " exit=" + value.exitCode)
            value
        } catch (t: Throwable) {
            close()
            NextLog.w("ADB", command + " failed: " + (t.message ?: t.javaClass.simpleName))
            Result(false, -1, "", t.message ?: t.javaClass.simpleName)
        }
    }

    fun close() = synchronized(lock) {
        runCatching { connection?.close() }
        connection = null
    }

    private fun connect(): Dadb {
        require(isPortOpen()) { "127.0.0.1:5555 closed" }
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
            NextLog.i("ADB", "persistent ADB key generated")
        }
        val keyPair = AdbKeyPair.read(privateKey, publicKey)
        val result = AtomicReference<Dadb?>()
        val error = AtomicReference<Throwable?>()
        val cancelled = AtomicBoolean(false)
        val thread = Thread({
            try {
                val candidate = Dadb.create(HOST, PORT, keyPair)
                if (cancelled.get()) runCatching { candidate.close() }
                else result.set(candidate)
            } catch (t: Throwable) {
                error.set(t)
            }
        }, "next-adb-connect").apply { isDaemon = true }
        thread.start()
        thread.join(CONNECT_TIMEOUT)
        if (thread.isAlive) {
            cancelled.set(true)
            thread.interrupt()
            throw IllegalStateException("ADB connect timeout")
        }
        error.get()?.let { throw it }
        return result.get() ?: error("ADB connection missing")
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555
        private const val CONNECT_TIMEOUT = 10_000L
        private const val SHELL_TIMEOUT = 4_000L
    }
}
