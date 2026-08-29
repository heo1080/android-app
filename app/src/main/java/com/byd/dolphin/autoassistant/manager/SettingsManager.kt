package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREF_NAME = "dolphin_assistant_settings"

    private const val KEY_GEAR_VOICE = "key_gear_voice"
    private const val KEY_AUTOHOLD_VOICE = "key_autohold_voice"
    private const val KEY_EPB_VOICE = "key_epb_voice"
    private const val KEY_ICC_VOICE = "key_icc_voice"
    private const val KEY_DRIVE_MODE_VOICE = "key_drive_mode_voice"
    private const val KEY_REGEN_MODE_VOICE = "key_regen_mode_voice"
    private const val KEY_HAZARD_AUTO = "key_hazard_auto"
    private const val KEY_CHARGING_VOICE = "key_charging_voice"
    private const val KEY_SAFETY_ALERT = "key_safety_alert"
    private const val KEY_HUD_BRIDGE = "key_hud_bridge"
    private const val KEY_CLUSTER_TBT = "key_cluster_tbt"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

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

    fun isHazardAutoEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HAZARD_AUTO, true)
    fun setHazardAutoEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_HAZARD_AUTO, enabled).apply()

    fun isChargingVoiceEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_CHARGING_VOICE, true)
    fun setChargingVoiceEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_CHARGING_VOICE, enabled).apply()

    fun isSafetyAlertEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SAFETY_ALERT, true)
    fun setSafetyAlertEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_SAFETY_ALERT, enabled).apply()

    fun isHudBridgeEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HUD_BRIDGE, true)
    fun setHudBridgeEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_HUD_BRIDGE, enabled).apply()

    fun isClusterTbtEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_CLUSTER_TBT, true)
    fun setClusterTbtEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_CLUSTER_TBT, enabled).apply()
}
