package com.byd.dolphin.autoassistant.manager

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.byd.dolphin.autoassistant.floating.FloatingItem
import com.byd.dolphin.autoassistant.util.DolphinLogger
import org.json.JSONObject

/**
 * BYD's stock launcher blocks requestPinShortcut/home-screen shortcuts.
 * This manager instead exposes a pool of real MAIN+LAUNCHER activities, so
 * chosen actions appear in the app drawer. Android does not allow a third-party
 * app to change a launcher activity label/icon dynamically, therefore slots use
 * stable "커스텀 바로가기 NN" labels while the selected action is stored here.
 */
object AppDrawerShortcutManager {
    private const val PREFS = "app_drawer_shortcut_slots"
    private const val KEY_PREFIX = "slot_"
    const val SLOT_COUNT = 16

    fun assign(context: Context, item: FloatingItem): Int? {
        val prefs = prefs(context)
        for (slot in 1..SLOT_COUNT) {
            val existing = read(context, slot)
            if (existing?.id == item.id && existing.packageName == item.packageName) {
                enable(context, slot, true)
                return slot
            }
        }
        val slot = (1..SLOT_COUNT).firstOrNull { read(context, it) == null } ?: return null
        prefs.edit().putString(KEY_PREFIX + slot, item.toJson().toString()).apply()
        enable(context, slot, true)
        DolphinLogger.i("APP_DRAWER", "앱서랍 슬롯 생성 slot=$slot id=${item.id} package=${item.packageName}")
        return slot
    }

    fun read(context: Context, slot: Int): FloatingItem? {
        if (slot !in 1..SLOT_COUNT) return null
        val raw = prefs(context).getString(KEY_PREFIX + slot, null) ?: return null
        return runCatching { FloatingItem.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun clear(context: Context, slot: Int) {
        if (slot !in 1..SLOT_COUNT) return
        enable(context, slot, false)
        prefs(context).edit().remove(KEY_PREFIX + slot).apply()
        DolphinLogger.i("APP_DRAWER", "앱서랍 슬롯 제거 slot=$slot")
    }

    fun clearAll(context: Context) {
        for (slot in 1..SLOT_COUNT) enable(context, slot, false)
        prefs(context).edit().clear().apply()
        DolphinLogger.i("APP_DRAWER", "커스텀 앱서랍 슬롯 전체 초기화")
    }

    fun summary(context: Context): List<Pair<Int, FloatingItem>> =
        (1..SLOT_COUNT).mapNotNull { slot -> read(context, slot)?.let { slot to it } }

    private fun enable(context: Context, slot: Int, enabled: Boolean) {
        val component = ComponentName(context.packageName, className(context, slot))
        context.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun className(context: Context, slot: Int): String =
        context.packageName + ".activity.DrawerSlotActivity" + slot.toString().padStart(2, '0')

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
