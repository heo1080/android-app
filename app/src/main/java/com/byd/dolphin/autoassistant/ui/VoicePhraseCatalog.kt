package com.byd.dolphin.autoassistant.ui

import android.content.Context
import com.byd.dolphin.autoassistant.manager.SettingsManager

data class VoicePhraseSpec(
    val id: String,
    val group: String,
    val label: String,
    val defaultPhrase: String,
    val recommendedPhrase: String,
    val current: () -> String,
    val save: (String) -> Unit
)

object VoicePhraseCatalog {
    fun all(context: Context): List<VoicePhraseSpec> = listOf(
        VoicePhraseSpec("gear_p","기어","P · 파킹","파킹","파킹 기어가 체결되었습니다.",
            { SettingsManager.getGearPhrase(context,"P") },
            { SettingsManager.setGearPhrase(context,"P",it) }),
        VoicePhraseSpec("gear_r","기어","R · 후진","후진","후진 기어가 체결되었습니다. 후방을 확인하세요.",
            { SettingsManager.getGearPhrase(context,"R") },
            { SettingsManager.setGearPhrase(context,"R",it) }),
        VoicePhraseSpec("gear_n","기어","N · 중립","중립","중립 기어 상태입니다.",
            { SettingsManager.getGearPhrase(context,"N") },
            { SettingsManager.setGearPhrase(context,"N",it) }),
        VoicePhraseSpec("gear_d","기어","D · 전진","전진","전진 기어가 체결되었습니다.",
            { SettingsManager.getGearPhrase(context,"D") },
            { SettingsManager.setGearPhrase(context,"D",it) }),

        VoicePhraseSpec("drive_eco","주행모드","ECO","에코 모드","에코 주행 모드로 전환되었습니다.",
            { SettingsManager.getDriveModePhrase(context,"ECO") },
            { SettingsManager.setDriveModePhrase(context,"ECO",it) }),
        VoicePhraseSpec("drive_normal","주행모드","NORMAL","노멀 모드","노멀 주행 모드로 전환되었습니다.",
            { SettingsManager.getDriveModePhrase(context,"NORMAL") },
            { SettingsManager.setDriveModePhrase(context,"NORMAL",it) }),
        VoicePhraseSpec("drive_sport","주행모드","SPORT","스포츠 모드","스포츠 주행 모드로 전환되었습니다.",
            { SettingsManager.getDriveModePhrase(context,"SPORT") },
            { SettingsManager.setDriveModePhrase(context,"SPORT",it) }),

        VoicePhraseSpec("regen_standard","회생제동","STANDARD","스탠다드","회생제동 스탠다드 모드입니다.",
            { SettingsManager.getRegenModePhrase(context,"STANDARD") },
            { SettingsManager.setRegenModePhrase(context,"STANDARD",it) }),
        VoicePhraseSpec("regen_high","회생제동","HIGH","하이","회생제동 하이 모드입니다.",
            { SettingsManager.getRegenModePhrase(context,"HIGH") },
            { SettingsManager.setRegenModePhrase(context,"HIGH",it) }),

        VoicePhraseSpec("snow","주행모드","SNOW","스노우모드","노면 미끄럼 방지 스노우 모드가 작동합니다.",
            { SettingsManager.getSnowModePhrase(context) },
            { SettingsManager.setSnowModePhrase(context,it) }),

        VoicePhraseSpec("autohold_switch_on","오토홀드","버튼 ON","오토홀드 ON","오토홀드 대기 모드가 활성화되었습니다.",
            { SettingsManager.getAutoHoldSwitchPhrase(context,true) },
            { SettingsManager.setAutoHoldSwitchPhrase(context,true,it) }),
        VoicePhraseSpec("autohold_switch_off","오토홀드","버튼 OFF","오토홀드 OFF","오토홀드 대기 모드가 해제되었습니다.",
            { SettingsManager.getAutoHoldSwitchPhrase(context,false) },
            { SettingsManager.setAutoHoldSwitchPhrase(context,false,it) }),
        VoicePhraseSpec("autohold_engaged","오토홀드","정차 체결","오토홀드 체결 유","정차 상태입니다. 오토홀드가 유지됩니다.",
            { SettingsManager.getAutoHoldBrakePhrase(context,true) },
            { SettingsManager.setAutoHoldBrakePhrase(context,true,it) }),
        VoicePhraseSpec("autohold_released","오토홀드","출발 해제","오토홀드 체결 무","오토홀드가 해제되었습니다. 출발합니다.",
            { SettingsManager.getAutoHoldBrakePhrase(context,false) },
            { SettingsManager.setAutoHoldBrakePhrase(context,false,it) }),

        VoicePhraseSpec("epb_on","주차브레이크","체결","사이드브레이크 체결 유","전자식 주차 브레이크가 체결되었습니다.",
            { SettingsManager.getEpbPhrase(context,true) },
            { SettingsManager.setEpbPhrase(context,true,it) }),
        VoicePhraseSpec("epb_off","주차브레이크","해제","사이드브레이크 체결 무","전자식 주차 브레이크가 해제되었습니다.",
            { SettingsManager.getEpbPhrase(context,false) },
            { SettingsManager.setEpbPhrase(context,false,it) }),

        VoicePhraseSpec("icc_on","ICC","ON","자율주행 ON","ICC 자율주행 보조가 켜졌습니다.",
            { SettingsManager.getIccPhrase(context,true) },
            { SettingsManager.setIccPhrase(context,true,it) }),
        VoicePhraseSpec("icc_off","ICC","OFF","자율주행 OFF","ICC 자율주행 보조가 꺼졌습니다.",
            { SettingsManager.getIccPhrase(context,false) },
            { SettingsManager.setIccPhrase(context,false,it) }),

        VoicePhraseSpec("leading_car","주행 알림","전방 차량 출발","전방 차량이 출발했습니다.","전방 차량이 출발했습니다. 주변을 확인하세요.",
            { SettingsManager.getLeadingCarPhrase(context) },
            { SettingsManager.setLeadingCarPhrase(context,it) }),

        VoicePhraseSpec("charging_start","충전","시작","충전이 시작되었습니다.","고전압 배터리 충전이 시작되었습니다.",
            { SettingsManager.getChargingStartPhrase(context) },
            { SettingsManager.setChargingStartPhrase(context,it) }),
        VoicePhraseSpec("charging_end","충전","완료","충전이 완료되었습니다.","배터리 충전이 정상 완료되었습니다.",
            { SettingsManager.getChargingEndPhrase(context) },
            { SettingsManager.setChargingEndPhrase(context,it) })
    )
}
