package com.byd.dolphin.autoassistant.next

import android.content.Context
import com.byd.dolphin.autoassistant.next.audio.NextAudioEngine
import com.byd.dolphin.autoassistant.next.capability.CapabilityRouter
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.diagnostics.DolphinDiagnostics
import com.byd.dolphin.autoassistant.next.diagnostics.RecentDriveRecorder
import com.byd.dolphin.autoassistant.next.events.NextEventEngine
import com.byd.dolphin.autoassistant.next.launcher.LauncherMediaRepository
import com.byd.dolphin.autoassistant.next.launcher.LauncherVehicleInfoRepository
import com.byd.dolphin.autoassistant.next.vehicle.VehicleRepository

object NextRuntime {
    @Volatile private var started = false

    lateinit var repository: VehicleRepository
        private set
    lateinit var audio: NextAudioEngine
        private set
    lateinit var events: NextEventEngine
        private set
    lateinit var recentDrive: RecentDriveRecorder
        private set
    lateinit var diagnostics: DolphinDiagnostics
        private set
    lateinit var launcherMedia: LauncherMediaRepository
        private set
    lateinit var launcherVehicleInfo: LauncherVehicleInfoRepository
        private set

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        NextLogger.init(app)
        repository = VehicleRepository(app)
        audio = NextAudioEngine(app)
        events = NextEventEngine(repository, audio)
        recentDrive = RecentDriveRecorder(repository, audio)
        diagnostics = DolphinDiagnostics(app, repository, audio, recentDrive)
        launcherMedia = LauncherMediaRepository(app)
        launcherVehicleInfo = LauncherVehicleInfoRepository(app)

        repository.start()
        CapabilityRouter.start(app)
        audio.warmup()
        events.start()
        recentDrive.start()
        launcherMedia.start()
        launcherVehicleInfo.start()
        started = true
        NextLogger.i("RUNTIME", "shared Next runtime started")
    }

    fun isStarted(): Boolean = started
}
