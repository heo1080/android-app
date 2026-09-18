package com.byd.dolphin.autoassistant.core

enum class CapabilityLevel { VERIFIED, BETA, LAB, UNKNOWN }

data class TimedValue<T>(
    val value: T,
    val source: String,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val confidence: Float = 1f
) {
    fun isStale(now: Long = System.currentTimeMillis(), timeoutMs: Long = 2_500L): Boolean =
        now - updatedAtMs > timeoutMs
}

data class VehicleState(
    val driverHeat: TimedValue<Int>? = null,
    val passengerHeat: TimedValue<Int>? = null,
    val steeringHeat: TimedValue<Boolean>? = null,
    val acOn: TimedValue<Boolean>? = null,
    val driveRaw: TimedValue<Int>? = null,
    val driveLabel: TimedValue<String>? = null,
    val regenRaw: TimedValue<Int>? = null,
    val autoHoldRaw: TimedValue<Int>? = null,
    val bsdRaw: TimedValue<Int>? = null,
    val turnRaw: TimedValue<Int>? = null,
    val speedKph: TimedValue<Double>? = null,
    val lastProbeMs: Long = 0L
)

data class Capability(
    val id: String,
    val title: String,
    val level: CapabilityLevel,
    val note: String
)

object CapabilityCatalog {
    val entries = listOf(
        Capability("seat_heat", "앞좌석 열선", CapabilityLevel.VERIFIED, "실차 getter/setter 경로 이식"),
        Capability("steering_heat", "핸들 열선", CapabilityLevel.VERIFIED, "실차 getter/setter 경로 이식"),
        Capability("split2", "2분할", CapabilityLevel.VERIFIED, "Next 2단계 이식 대상"),
        Capability("bsd", "BSD", CapabilityLevel.BETA, "현재 반응 경로 보존 후 재검증"),
        Capability("autohold", "AutoHold", CapabilityLevel.BETA, "raw 상태와 물리 체결 상태 분리"),
        Capability("leading", "전방차량출발", CapabilityLevel.BETA, "확정 ADAS 신호 전까지 보조 감지"),
        Capability("inside_light", "실내등", CapabilityLevel.LAB, "기존 actuator 실차 미동작"),
        Capability("down_mirror", "다운미러", CapabilityLevel.LAB, "기존 angle setter 실차 미동작"),
        Capability("memory_seat", "메모리시트", CapabilityLevel.LAB, "절대 좌표 미확정"),
        Capability("cluster", "계기판/TBT", CapabilityLevel.LAB, "Qt cluster + CAN bridge 연구"),
        Capability("radar_track", "전방 radar track", CapabilityLevel.LAB, "read-only correlation"),
        Capability("third_party_audio", "타 앱 운전석 오디오", CapabilityLevel.LAB, "UID/output routing 미확정")
    )
}
