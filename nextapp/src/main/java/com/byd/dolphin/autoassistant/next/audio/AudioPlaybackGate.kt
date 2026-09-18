package com.byd.dolphin.autoassistant.next.audio

import kotlinx.coroutines.sync.Mutex

object AudioPlaybackGate {
    private val mutex = Mutex()

    suspend fun <T> serial(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
