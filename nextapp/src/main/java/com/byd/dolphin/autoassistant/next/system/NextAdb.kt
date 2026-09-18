package com.byd.dolphin.autoassistant.next.system

import android.content.Context
import com.byd.dolphin.autoassistant.next.core.NextLogger
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object NextAdb {
    private val lock = Any()
    private var connection: Dadb? = null
    private var connectedHost: String? = null

    data class Result(
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val message: String
    )

    fun isPortOpen(host: String = "127.0.0.1", port: Int = 5555): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 400) }
            true
        } catch (_: Throwable) {
            false
        }

    fun shell(
        context: Context,
        command: String,
        host: String = "127.0.0.1",
        port: Int = 5555
    ): Result = synchronized(lock) {
        try {
            val adb = connection(context.applicationContext, host, port)
            val result = AtomicReference<Result?>()
            val error = AtomicReference<Throwable?>()
            val thread = Thread({
                try {
                    val response = adb.shell(command)
                    val output = response.allOutput
                    result.set(
                        Result(
                            success = response.exitCode == 0,
                            exitCode = response.exitCode,
                            output = output,
                            message = if (response.exitCode == 0) "ok"
                            else output.trim().ifBlank { "exit=" + response.exitCode }
                        )
                    )
                } catch (t: Throwable) {
                    error.set(t)
                }
            }, "dolphin-next-adb-shell").apply { isDaemon = true }
            thread.start()
            thread.join(SHELL_TIMEOUT_MS)
            if (thread.isAlive) {
                thread.interrupt()
                closeLocked()
                return@synchronized Result(false, -1, "", "ADB shell timeout")
            }
            error.get()?.let { throw it }
            val r = result.get() ?: Result(false, -1, "", "no result")
            NextLogger.i(
                "ADB",
                "exit=" + r.exitCode + " cmd=" + command.take(260) +
                    " out=" + r.output.replace("\n", " ").take(360)
            )
            r
        } catch (t: Throwable) {
            closeLocked()
            NextLogger.e("ADB", "shell failed: " + command, t)
            Result(false, -1, "", t.message ?: t.javaClass.simpleName)
        }
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun connection(context: Context, host: String, port: Int): Dadb {
        connection?.takeIf { connectedHost == "$host:$port" }?.let { return it }
        closeLocked()
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
            NextLogger.i("ADB", "generated persistent vehicle ADB key")
        }
        val keyPair = AdbKeyPair.read(privateKey, publicKey)
        return createWithTimeout(host, port, keyPair).also {
            connection = it
            connectedHost = "$host:$port"
            NextLogger.i("ADB", "connected " + connectedHost)
        }
    }

    private fun createWithTimeout(host: String, port: Int, keyPair: AdbKeyPair): Dadb {
        val result = AtomicReference<Dadb?>()
        val error = AtomicReference<Throwable?>()
        val cancelled = AtomicBoolean(false)
        val thread = Thread({
            try {
                val candidate = Dadb.create(host, port, keyPair)
                if (cancelled.get()) runCatching { candidate.close() }
                else result.set(candidate)
            } catch (t: Throwable) {
                error.set(t)
            }
        }, "dolphin-next-adb-connect").apply { isDaemon = true }
        thread.start()
        thread.join(CONNECT_TIMEOUT_MS)
        if (thread.isAlive) {
            cancelled.set(true)
            thread.interrupt()
            throw IllegalStateException("ADB connect timeout")
        }
        error.get()?.let { throw it }
        return result.get() ?: throw IllegalStateException("ADB connection unavailable")
    }

    private fun closeLocked() {
        runCatching { connection?.close() }
        connection = null
        connectedHost = null
    }

    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SHELL_TIMEOUT_MS = 5_000L
}
