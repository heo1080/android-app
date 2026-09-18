package com.byd.dolphin.autoassistant.core

import android.content.Context
import com.byd.dolphin.autoassistant.core.audio.AlertEngine
import com.byd.dolphin.autoassistant.core.event.VehicleEventEngine
import com.byd.dolphin.autoassistant.core.state.VehicleStateStore

object NextRuntime {
    private val lock = Any()

    @Volatile private var initialized = false
    lateinit var stateStore: VehicleStateStore
        private set
    lateinit var alerts: AlertEngine
        private set
    lateinit var events: VehicleEventEngine
        private set

    fun ensure(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext
            NextLog.init(app)
            stateStore = VehicleStateStore(app)
            alerts = AlertEngine(app)
            events = VehicleEventEngine(stateStore, alerts)
            initialized = true
            NextLog.i("RUNTIME", "clean runtime initialized")
        }
    }

    fun start(context: Context) {
        ensure(context)
        stateStore.start()
        events.start()
        alerts.ensureTtsModel()
        NextLog.i("RUNTIME", "vehicle runtime started")
    }

    fun stop() {
        if (!initialized) return
        events.stop()
        stateStore.stop()
        alerts.release()
        NextLog.i("RUNTIME", "vehicle runtime stopped")
    }
}
