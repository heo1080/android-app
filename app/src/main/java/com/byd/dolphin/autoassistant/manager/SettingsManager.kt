package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class BootAppItem(
    val packageName: String,
    val appName: String,
    val delaySeconds: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appName", appName)
        put("delaySeconds", delaySeconds)
    }

    companion object {
        fun fromJson(json: JSONObject): BootAppItem = BootAppItem(
            packageName = json.optString("packageName", ""),
            appName = json.optString("appName", ""),
            delaySeconds = json.optDouble("delaySeconds", 5.0)
        )
    }
}

data class CustomScenario(
    val id: String,
    val name: String,
    val triggerType: String,
    val triggerName: String,
    val actionType: String,
    val actionName: String,
    val actionValue: String,
    var isEnabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("triggerType", triggerType)
        put("triggerName", triggerName)
        put("actionType", actionType)
        put("actionName", actionName)
        put("actionValue", actionValue)
        put("isEnabled", isEnabled)
    }

    companion object {
        fun fromJson(json: JSONObject): CustomScenario = CustomScenario(
            id = json.optString("id", System.currentTimeMillis().toString()),
            name = json.optString("name", "자동화 규칙"),
            triggerType = json.optString("triggerType", "READY_ON"),
            triggerName = json.optString("triggerName", "차량 시동 (READY)"),
            actionType = json.optString("actionType", "AC_FAN"),
            actionName = json.optString("actionName", "에어컨 풍량 3단"),
            actionValue = json.optString("actionValue", "3"),
            isEnabled = json.optBoolean("isEnabled", true)
        )
    }
}

object SettingsManager {

    private const val PREF_NAME = "dolphin_assistant_settings"

    // 음성 활성화 토글
    private const val KEY_GEAR_VOICE = "key_gear_voice"
    private const val KEY_AUTOHOLD_VOICE = "key_autohold_voice"
    private const val KEY_EPB_VOICE = "key_epb_voice"
    private const val KEY_ICC_VOICE = "key_icc_voice"
    private const val KEY_DRIVE_MODE_VOICE = "key_drive_mode_voice"
    private const val KEY_REGEN_MODE_VOICE = "key_regen_mode_voice"
    private const val KEY_SNOW_MODE_VOICE = "key_snow_mode_voice"
    private const val KEY_LEADING_CAR_VOICE = "key_leading_car_voice"
    private const val KEY_HAZARD_AUTO = "key_hazard_auto"
    private const val KEY_CHARGING_VOICE = "key_charging_voice"
    private const val KEY_SAFETY_ALERT = "key_safety_alert"

    // BSD / LDP 경고음 모드
    private const val KEY_BSD_ALERT_MODE = "key_bsd_alert_mode"
    private const val KEY_LDP_ALERT_MODE = "key_ldp_alert_mode"
    private const val KEY_BSD_CUSTOM_TEXT = "key_bsd_custom_text"
    private const val KEY_LDP_CUSTOM_TEXT = "key_ldp_custom_text"

    // 10대 차량 상태 멘트 키
    private const val KEY_PHRASE_GEAR_P = "key_phrase_gear_p"
    private const val KEY_PHRASE_GEAR_R = "key_phrase_gear_r"
    private const val KEY_PHRASE_GEAR_N = "key_phrase_gear_n"
    private const val KEY_PHRASE_GEAR_D = "key_phrase_gear_d"
    private const val KEY_PHRASE_DRIVE_ECO = "key_phrase_drive_eco"
    private const val KEY_PHRASE_DRIVE_NORMAL = "key_phrase_drive_normal"
    private const val KEY_PHRASE_DRIVE_SPORT = "key_phrase_drive_sport"
    private const val KEY_PHRASE_REGEN_ECO = "key_phrase_regen_eco"
    private const val KEY_PHRASE_REGEN_HIGH = "key_phrase_regen_high"
    private const val KEY_PHRASE_SNOW_MODE = "key_phrase_snow_mode"
    private const val KEY_PHRASE_AUTOHOLD_ENGAGED = "key_phrase_autohold_engaged"
    private const val KEY_PHRASE_AUTOHOLD_RELEASED = "key_phrase_autohold_released"
    private const val KEY_PHRASE_EPB_ON = "key_phrase_epb_on"
    private const val KEY_PHRASE_EPB_OFF = "key_phrase_epb_off"
    private const val KEY_PHRASE_ICC_ON = "key_phrase_icc_on"
    private const val KEY_PHRASE_LEADING_CAR = "key_phrase_leading_car"
    private const val KEY_PHRASE_CHARGING_ON = "key_phrase_charging_on"

    // HUD 관련 키
    private const val KEY_HUD_BRIDGE = "key_hud_bridge"
    private const val KEY_HUD_DATA_ENABLED = "key_hud_data_enabled"
    private const val KEY_HUD_AUDIO_ENABLED = "key_hud_audio_enabled"
    private const val KEY_HUD_AUDIO_VOLUME = "key_hud_audio_volume"
    private const val KEY_HUD_BRIGHTNESS_AUTO = "key_hud_brightness_auto"
    private const val KEY_HUD_BRIGHTNESS_MANUAL = "key_hud_brightness_manual"
    private const val KEY_HUD_BRIGHTNESS_MIN = "key_hud_brightness_min"
    private const val KEY_HUD_BRIGHTNESS_MAX = "key_hud_brightness_max"

    // 부팅 시 다중 앱 자동 실행 목록
    private const val KEY_BOOT_AUTO_ENABLED = "key_boot_auto_enabled"
    private const val KEY_BOOT_APP_LIST_JSON = "key_boot_app_list_json"
    private const val KEY_BOOT_MEDIA_PLAY_ENABLED = "key_boot_media_play_enabled"
    private const val KEY_BOOT_MEDIA_DELAY = "key_boot_media_delay"

    // 커스텀 시나리오 규칙 목록
    private const val KEY_CUSTOM_SCENARIOS_JSON = "key_custom_scenarios_json"

    // 플로팅 오버레이 & 분할 화면
    private const val KEY_FLOATING_OVERLAY = "key_floating_overlay_enabled"
    private const val KEY_FLOATING_X = "key_floating_x"
    private const val KEY_FLOATING_Y = "key_floating_y"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // Voice 토글들
    fun isGearVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_GEAR_VOICE, true)
    fun setGearVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_GEAR_VOICE, enabled).apply()

    fun isAutoHoldVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_AUTOHOLD_VOICE, true)
    fun setAutoHoldVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_AUTOHOLD_VOICE, enabled).apply()

    fun isEpbVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_EPB_VOICE, true)
    fun setEpbVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_EPB_VOICE, enabled).apply()

    fun isIccVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_ICC_VOICE, true)
    fun setIccVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_ICC_VOICE, enabled).apply()

    fun isDriveModeVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DRIVE_MODE_VOICE, true)
    fun setDriveModeVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_DRIVE_MODE_VOICE, enabled).apply()

    fun isRegenModeVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_REGEN_MODE_VOICE, true)
    fun setRegenModeVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_REGEN_MODE_VOICE, enabled).apply()

    fun isSnowModeVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SNOW_MODE_VOICE, true)
    fun setSnowModeVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_SNOW_MODE_VOICE, enabled).apply()

    fun isLeadingCarVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_LEADING_CAR_VOICE, true)
    fun setLeadingCarVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_LEADING_CAR_VOICE, enabled).apply()

    fun isHazardAutoEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HAZARD_AUTO, true)
    fun setHazardAutoEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_HAZARD_AUTO, enabled).apply()

    fun isChargingVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_CHARGING_VOICE, true)
    fun setChargingVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_CHARGING_VOICE, enabled).apply()

    fun isSafetyAlertEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SAFETY_ALERT, true)
    fun setSafetyAlertEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_SAFETY_ALERT, enabled).apply()

    fun getBsdAlertMode(context: Context): String = getPrefs(context).getString(KEY_BSD_ALERT_MODE, "BEEP") ?: "BEEP"
    fun setBsdAlertMode(context: Context, mode: String) = getPrefs(context).edit().putString(KEY_BSD_ALERT_MODE, mode).apply()

    fun getLdpAlertMode(context: Context): String = getPrefs(context).getString(KEY_LDP_ALERT_MODE, "BEEP") ?: "BEEP"
    fun setLdpAlertMode(context: Context, mode: String) = getPrefs(context).edit().putString(KEY_LDP_ALERT_MODE, mode).apply()

    fun getBsdCustomText(context: Context): String = getPrefs(context).getString(KEY_BSD_CUSTOM_TEXT, "후측방 차량 접근 경고") ?: "후측방 차량 접근 경고"
    fun setBsdCustomText(context: Context, text: String) = getPrefs(context).edit().putString(KEY_BSD_CUSTOM_TEXT, text).apply()

    fun getLdpCustomText(context: Context): String = getPrefs(context).getString(KEY_LDP_CUSTOM_TEXT, "차선 이탈 조향 보조") ?: "차선 이탈 조향 보조"
    fun setLdpCustomText(context: Context, text: String) = getPrefs(context).edit().putString(KEY_LDP_CUSTOM_TEXT, text).apply()

    // 10대 차량 상태 음성 멘트
    fun getGearPhrase(context: Context, gear: String): String {
        val prefs = getPrefs(context)
        return when (gear.uppercase()) {
            "P" -> prefs.getString(KEY_PHRASE_GEAR_P, "파킹") ?: "파킹"
            "R" -> prefs.getString(KEY_PHRASE_GEAR_R, "후진") ?: "후진"
            "N" -> prefs.getString(KEY_PHRASE_GEAR_N, "중립") ?: "중립"
            "D" -> prefs.getString(KEY_PHRASE_GEAR_D, "전진") ?: "전진"
            else -> gear
        }
    }
    fun setGearPhrase(context: Context, gear: String, phrase: String) {
        val key = when (gear.uppercase()) {
            "P" -> KEY_PHRASE_GEAR_P
            "R" -> KEY_PHRASE_GEAR_R
            "N" -> KEY_PHRASE_GEAR_N
            "D" -> KEY_PHRASE_GEAR_D
            else -> return
        }
        getPrefs(context).edit().putString(key, phrase).apply()
    }

    fun getDriveModePhrase(context: Context, mode: String): String {
        val prefs = getPrefs(context)
        return when (mode.uppercase()) {
            "ECO" -> prefs.getString(KEY_PHRASE_DRIVE_ECO, "에코 모드") ?: "에코 모드"
            "SPORT" -> prefs.getString(KEY_PHRASE_DRIVE_SPORT, "스포츠 모드") ?: "스포츠 모드"
            else -> prefs.getString(KEY_PHRASE_DRIVE_NORMAL, "노멀 모드") ?: "노멀 모드"
        }
    }
    fun setDriveModePhrase(context: Context, mode: String, phrase: String) {
        val key = when (mode.uppercase()) {
            "ECO" -> KEY_PHRASE_DRIVE_ECO
            "SPORT" -> KEY_PHRASE_DRIVE_SPORT
            else -> KEY_PHRASE_DRIVE_NORMAL
        }
        getPrefs(context).edit().putString(key, phrase).apply()
    }

    fun getRegenModePhrase(context: Context, regen: String): String {
        val prefs = getPrefs(context)
        return if (regen.contains("HIGH", ignoreCase = true)) {
            prefs.getString(KEY_PHRASE_REGEN_HIGH, "회생제동 하이") ?: "회생제동 하이"
        } else {
            prefs.getString(KEY_PHRASE_REGEN_ECO, "회생제동 에코") ?: "회생제동 에코"
        }
    }
    fun setRegenModePhrase(context: Context, regen: String, phrase: String) {
        val key = if (regen.contains("HIGH", ignoreCase = true)) KEY_PHRASE_REGEN_HIGH else KEY_PHRASE_REGEN_ECO
        getPrefs(context).edit().putString(key, phrase).apply()
    }

    fun getSnowModePhrase(context: Context): String = getPrefs(context).getString(KEY_PHRASE_SNOW_MODE, "스노우 모드가 켜졌습니다.") ?: "스노우 모드가 켜졌습니다."
    fun setSnowModePhrase(context: Context, phrase: String) = getPrefs(context).edit().putString(KEY_PHRASE_SNOW_MODE, phrase).apply()

    fun getAutoHoldPhrase(context: Context, isActive: Boolean): String {
        val prefs = getPrefs(context)
        return if (isActive) {
            prefs.getString(KEY_PHRASE_AUTOHOLD_ENGAGED, "오토홀드가 체결되었습니다.") ?: "오토홀드가 체결되었습니다."
        } else {
            prefs.getString(KEY_PHRASE_AUTOHOLD_RELEASED, "오토홀드가 해제되었습니다.") ?: "오토홀드가 해제되었습니다."
        }
    }
    fun setAutoHoldPhrase(context: Context, isActive: Boolean, phrase: String) {
        val key = if (isActive) KEY_PHRASE_AUTOHOLD_ENGAGED else KEY_PHRASE_AUTOHOLD_RELEASED
        getPrefs(context).edit().putString(key, phrase).apply()
    }

    fun getEpbPhrase(context: Context, isEngaged: Boolean): String {
        val prefs = getPrefs(context)
        return if (isEngaged) {
            prefs.getString(KEY_PHRASE_EPB_ON, "사이드브레이크가 체결되었습니다.") ?: "사이드브레이크가 체결되었습니다."
        } else {
            prefs.getString(KEY_PHRASE_EPB_OFF, "사이드브레이크 해제되었습니다.") ?: "사이드브레이크 해제되었습니다."
        }
    }
    fun setEpbPhrase(context: Context, isEngaged: Boolean, phrase: String) {
        val key = if (isEngaged) KEY_PHRASE_EPB_ON else KEY_PHRASE_EPB_OFF
        getPrefs(context).edit().putString(key, phrase).apply()
    }

    fun getIccPhrase(context: Context): String = getPrefs(context).getString(KEY_PHRASE_ICC_ON, "자율주행이 켜졌습니다.") ?: "자율주행이 켜졌습니다."
    fun setIccPhrase(context: Context, phrase: String) = getPrefs(context).edit().putString(KEY_PHRASE_ICC_ON, phrase).apply()

    fun getLeadingCarPhrase(context: Context): String = getPrefs(context).getString(KEY_PHRASE_LEADING_CAR, "전방 차량이 출발했습니다.") ?: "전방 차량이 출발했습니다."
    fun setLeadingCarPhrase(context: Context, phrase: String) = getPrefs(context).edit().putString(KEY_PHRASE_LEADING_CAR, phrase).apply()

    fun getChargingPhrase(context: Context): String = getPrefs(context).getString(KEY_PHRASE_CHARGING_ON, "충전이 시작되었습니다.") ?: "충전이 시작되었습니다."
    fun setChargingPhrase(context: Context, phrase: String) = getPrefs(context).edit().putString(KEY_PHRASE_CHARGING_ON, phrase).apply()

    // HUD 설정
    fun isHudBridgeEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HUD_BRIDGE, true)
    fun setHudBridgeEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_HUD_BRIDGE, enabled).apply()

    fun isHudDataEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HUD_DATA_ENABLED, true)
    fun setHudDataEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_HUD_DATA_ENABLED, enabled).apply()

    fun isHudAudioEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HUD_AUDIO_ENABLED, true)
    fun setHudAudioEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_HUD_AUDIO_ENABLED, enabled).apply()

    fun getHudAudioVolume(context: Context): Int = getPrefs(context).getInt(KEY_HUD_AUDIO_VOLUME, 10)
    fun setHudAudioVolume(context: Context, volume: Int) = getPrefs(context).edit().putInt(KEY_HUD_AUDIO_VOLUME, volume).apply()

    fun isHudBrightnessAuto(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HUD_BRIGHTNESS_AUTO, true)
    fun setHudBrightnessAuto(context: Context, auto: Boolean) = getPrefs(context).edit().putBoolean(KEY_HUD_BRIGHTNESS_AUTO, auto).apply()

    fun getHudBrightnessManual(context: Context): Int = getPrefs(context).getInt(KEY_HUD_BRIGHTNESS_MANUAL, 10)
    fun setHudBrightnessManual(context: Context, level: Int) = getPrefs(context).edit().putInt(KEY_HUD_BRIGHTNESS_MANUAL, level).apply()

    fun getHudBrightnessMin(context: Context): Int = getPrefs(context).getInt(KEY_HUD_BRIGHTNESS_MIN, 2)
    fun setHudBrightnessMin(context: Context, level: Int) = getPrefs(context).edit().putInt(KEY_HUD_BRIGHTNESS_MIN, level).apply()

    fun getHudBrightnessMax(context: Context): Int = getPrefs(context).getInt(KEY_HUD_BRIGHTNESS_MAX, 15)
    fun setHudBrightnessMax(context: Context, level: Int) = getPrefs(context).edit().putInt(KEY_HUD_BRIGHTNESS_MAX, level).apply()

    // 부팅 시 다중 앱 자동 실행 관리 (0.1초 단위 지연 시간)
    fun isBootAutoEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_BOOT_AUTO_ENABLED, true)
    fun setBootAutoEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_BOOT_AUTO_ENABLED, enabled).apply()

    fun isBootMediaPlayEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_BOOT_MEDIA_PLAY_ENABLED, true)
    fun setBootMediaPlayEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_BOOT_MEDIA_PLAY_ENABLED, enabled).apply()

    fun getBootMediaDelay(context: Context): Double = getPrefs(context).getFloat(KEY_BOOT_MEDIA_DELAY, 3.5f).toDouble()
    fun setBootMediaDelay(context: Context, delay: Double) = getPrefs(context).edit().putFloat(KEY_BOOT_MEDIA_DELAY, delay.toFloat()).apply()

    fun getBootAppList(context: Context): MutableList<BootAppItem> {
        val jsonStr = getPrefs(context).getString(KEY_BOOT_APP_LIST_JSON, null) ?: return mutableListOf(
            BootAppItem("com.skt.tmap.ku", "티맵", 3.0),
            BootAppItem("com.android.music", "기본 미디어", 4.5)
        )
        val list = mutableListOf<BootAppItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(BootAppItem.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveBootAppList(context: Context, list: List<BootAppItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        getPrefs(context).edit().putString(KEY_BOOT_APP_LIST_JSON, arr.toString()).apply()
    }

    fun addBootApp(context: Context, item: BootAppItem) {
        val list = getBootAppList(context)
        list.removeAll { it.packageName == item.packageName }
        list.add(item)
        saveBootAppList(context, list)
    }

    fun removeBootApp(context: Context, packageName: String) {
        val list = getBootAppList(context)
        list.removeAll { it.packageName == packageName }
        saveBootAppList(context, list)
    }

    // 커스텀 시나리오 규칙 목록
    fun getCustomScenarios(context: Context): MutableList<CustomScenario> {
        val jsonStr = getPrefs(context).getString(KEY_CUSTOM_SCENARIOS_JSON, null)
        if (jsonStr == null) {
            return mutableListOf(
                CustomScenario("1", "폭염 시 에어컨 급속 냉방", "TEMP_HIGH", "외부온도 32°C 이상", "AC_FAN", "에어컨 풍량 5단", "5"),
                CustomScenario("2", "겨울철 앞뒤 성에제거 동시가동", "TEMP_LOW", "외부온도 3°C 이하", "DEFROST_ALL", "앞뒤 성에제거 ON", "1"),
                CustomScenario("3", "시동 후 출퇴근 포지션 정렬", "READY_ON", "시동 (READY) 감지", "SEAT_STAGE", "운전석 포지션 1번", "1")
            )
        }
        val list = mutableListOf<CustomScenario>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(CustomScenario.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveCustomScenarios(context: Context, list: List<CustomScenario>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        getPrefs(context).edit().putString(KEY_CUSTOM_SCENARIOS_JSON, arr.toString()).apply()
    }

    fun addCustomScenario(context: Context, scenario: CustomScenario) {
        val list = getCustomScenarios(context)
        list.add(scenario)
        saveCustomScenarios(context, list)
    }

    fun removeCustomScenario(context: Context, id: String) {
        val list = getCustomScenarios(context)
        list.removeAll { it.id == id }
        saveCustomScenarios(context, list)
    }

    // 플로팅 오버레이 위치
    fun isFloatingOverlayEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FLOATING_OVERLAY, true)
    fun setFloatingOverlayEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_FLOATING_OVERLAY, enabled).apply()

    fun getFloatingX(context: Context, defaultVal: Int): Int = getPrefs(context).getInt(KEY_FLOATING_X, defaultVal)
    fun setFloatingX(context: Context, x: Int) = getPrefs(context).edit().putInt(KEY_FLOATING_X, x).apply()

    fun getFloatingY(context: Context, defaultVal: Int): Int = getPrefs(context).getInt(KEY_FLOATING_Y, defaultVal)
    fun setFloatingY(context: Context, y: Int) = getPrefs(context).edit().putInt(KEY_FLOATING_Y, y).apply()
}
