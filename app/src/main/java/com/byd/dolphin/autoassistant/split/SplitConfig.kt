package com.byd.dolphin.autoassistant.split

import java.io.Serializable

enum class SplitMode {
    TWO_APPS_HORIZONTAL,        // 2개 좌/우 분할 (예: 30% : 70%)
    THREE_APPS_LEFT_STACKED,    // 3개: 왼쪽 위/아래 2개 + 오른쪽 1개
    THREE_APPS_RIGHT_STACKED    // 3개: 왼쪽 1개 + 오른쪽 위/아래 2개
}

data class SplitConfig(
    val title: String,
    val mode: SplitMode,
    val pkg1: String,
    val pkg2: String,
    val pkg3: String = "",
    val ratioPrimary: Int = 50,    // 1단계 좌/우 비율 (1~99%)
    val ratioSecondary: Int = 50   // 2단계 위/아래 분할 비율 (1~99%)
) : Serializable
