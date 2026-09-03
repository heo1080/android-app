package com.byd.dolphin.autoassistant.split

import java.io.Serializable

enum class SplitMode {
    TWO_APPS_HORIZONTAL,        // 2개 좌/우 분할 (1% 단위 정밀 조절, 예: 30% : 70%)
    THREE_APPS_LEFT_STACKED,    // 3개: 왼쪽 위/아래 2개 + 오른쪽 1개
    THREE_APPS_RIGHT_STACKED,   // 3개: 왼쪽 1개 + 오른쪽 위/아래 2개
    FOUR_APPS_GRID              // 4개: 좌상/좌하/우상/우하 4분할
}

data class SplitConfig(
    val title: String,
    val mode: SplitMode,
    val pkg1: String,
    val pkg2: String,
    val pkg3: String = "",
    val pkg4: String = "",
    val ratioPrimary: Int = 30,    // 좌/우 분할 비율 (1~99%, 기본 30%)
    val ratioSecondary: Int = 50   // 위/아래 분할 비율 (1~99%, 기본 50%)
) : Serializable
