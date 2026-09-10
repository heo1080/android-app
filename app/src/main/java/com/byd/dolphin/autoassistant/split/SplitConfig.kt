package com.byd.dolphin.autoassistant.split

import java.io.Serializable

enum class SplitMode {
    TWO_APPS_HORIZONTAL
}

data class SplitConfig(
    val title: String,
    val mode: SplitMode,
    val pkg1: String,
    val pkg2: String,
    val ratioPrimary: Int = 30
) : Serializable
