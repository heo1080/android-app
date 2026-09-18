package com.byd.dolphin.autoassistant.next.launcher

import com.byd.dolphin.autoassistant.next.core.NextLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object LauncherNavigationBus {
    private val _homeEpoch = MutableStateFlow(0L)
    val homeEpoch: StateFlow<Long> = _homeEpoch

    fun requestHome(reason: String) {
        val next = System.currentTimeMillis().coerceAtLeast(_homeEpoch.value + 1L)
        _homeEpoch.value = next
        NextLogger.i("LAUNCHER_NAV", "HOME requested reason=" + reason + " epoch=" + next)
    }
}
