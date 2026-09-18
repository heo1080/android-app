package com.byd.dolphin.autoassistant.core.model

enum class CapabilityLevel { VERIFIED, BETA, LAB }

data class Capability(
    val id: String,
    val title: String,
    val level: CapabilityLevel,
    val note: String
)

object CapabilityCatalog {
    val entries = listOf(
        Capability("gear_speed", "기어 · 속도", CapabilityLevel.VERIFIED, "실차에서 반복 사용한 getter 경로"),
        Capability("seat_heat", "앞좌석 열선", CapabilityLevel.VERIFIED, "getter/setter + readback"),
        Capability("steering_heat", "핸들 열선", CapabilityLevel.VERIFIED, "getter/setter + readback"),
        Capability("split2", "2앱 분할", CapabilityLevel.VERIFIED, "DiLink 3 primary/secondary + ratio 경로"),
        Capability("drive_mode", "주행모드", CapabilityLevel.BETA, "ECO/SPORT 동작 확인, NORMAL raw 재확인 필요"),
        Capability("autohold", "AutoHold 체결/해제", CapabilityLevel.BETA, "AVH raw + pedal/speed 상태머신"),
        Capability("bsd", "BSD", CapabilityLevel.BETA, "실차 반응 징후 확인, 반복 로그 필요"),
        Capability("leading", "전방차량출발", CapabilityLevel.BETA, "확정 ADAS 이벤트 전까지 전방 7/8 센서 보조 감지"),
        Capability("app_audio", "앱 자체 오디오", CapabilityLevel.BETA, "stream14 출력 사용, 실제 zone 재확인"),
        Capability("inside_light", "실내등", CapabilityLevel.LAB, "기존 actuator 실차 미동작"),
        Capability("down_mirror", "다운미러", CapabilityLevel.LAB, "기존 mirror angle setter 실차 미동작"),
        Capability("memory_seat", "메모리 시트", CapabilityLevel.LAB, "절대 위치 getter/setter 미확정"),
        Capability("cluster_tbt", "계기판/TBT", CapabilityLevel.LAB, "AUTONAVI/CAN 경로 한국 5인치 미검증"),
        Capability("cluster_theme", "계기판 테마/UI", CapabilityLevel.LAB, "한국 정식 Dolphin 렌더 타깃 미확정"),
        Capability("radar_track", "전방 radar track", CapabilityLevel.LAB, "0x280~0x289 후보 한국 Dolphin 미검증"),
        Capability("third_party_audio", "타 앱 운전석 오디오", CapabilityLevel.LAB, "UID/output routing 미확정"),
        Capability("split34", "3·4분할", CapabilityLevel.LAB, "VirtualDisplay 후보 실차 미확인")
    )
}
