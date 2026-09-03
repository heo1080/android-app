package com.byd.dolphin.autoassistant.floating

import android.content.Context
import android.content.SharedPreferences
import com.byd.dolphin.autoassistant.manager.AppRoutingManager
import org.json.JSONArray
import org.json.JSONObject

data class FloatingItem(
    val id: String,
    val title: String,
    val isApp: Boolean = false,
    val packageName: String = "",
    val category: String = "VEHICLE"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("isApp", isApp)
            put("packageName", packageName)
            put("category", category)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): FloatingItem {
            return FloatingItem(
                id = json.optString("id", ""),
                title = json.optString("title", ""),
                isApp = json.optBoolean("isApp", false),
                packageName = json.optString("packageName", ""),
                category = json.optString("category", "VEHICLE")
            )
        }
    }
}

object FloatingItemManager {

    private const val PREF_NAME = "dolphin_floating_items_pref"
    private const val KEY_SELECTED_IDS = "key_selected_floating_ids"

    const val ID_DEFROST = "FUNC_DEFROST"
    const val ID_INSIDE_LIGHT = "FUNC_INSIDE_LIGHT"
    const val ID_STEERING_HEAT = "FUNC_STEERING_HEAT"
    const val ID_SEAT_HEAT = "FUNC_SEAT_HEAT"
    const val ID_AC_TOGGLE = "FUNC_AC_TOGGLE"

    val DEFAULT_VEHICLE_FUNCTIONS = listOf(
        FloatingItem(ID_DEFROST, "♨️ 성에제거", category = "BOTTOM_BAR"),
        FloatingItem(ID_INSIDE_LIGHT, "💡 실내등", category = "LIGHT"),
        FloatingItem(ID_STEERING_HEAT, "♨️ 핸들열선", category = "BOTTOM_BAR"),
        FloatingItem(ID_SEAT_HEAT, "💺 시트열선", category = "BOTTOM_BAR"),
        FloatingItem(ID_AC_TOGGLE, "❄️ 공조 토글", category = "BOTTOM_BAR")
    )

    // 6-1. 상단바 퀵패널 버튼 항목들
    val QUICK_PANEL_ITEMS = listOf(
        FloatingItem("QP_ROTATION", "🔄 화면 회전", category = "QUICK_PANEL"),
        FloatingItem("QP_SCREEN_OFF", "🌙 화면 끄기", category = "QUICK_PANEL"),
        FloatingItem("QP_WIFI", "📶 와이파이", category = "QUICK_PANEL"),
        FloatingItem("QP_HOTSPOT", "📡 핫스팟", category = "QUICK_PANEL"),
        FloatingItem("QP_MUTE", "🔇 음소거", category = "QUICK_PANEL")
    )

    // 6-2. 하단바 버튼 항목들
    val BOTTOM_BAR_ITEMS = listOf(
        FloatingItem(ID_DEFROST, "♨️ 앞유리 성에", category = "BOTTOM_BAR"),
        FloatingItem("BB_DEFROST_REAR", "♨️ 뒷유리 열선", category = "BOTTOM_BAR"),
        FloatingItem(ID_AC_TOGGLE, "❄️ 공조 전원", category = "BOTTOM_BAR"),
        FloatingItem(ID_SEAT_HEAT, "💺 운전석 열선", category = "BOTTOM_BAR"),
        FloatingItem(ID_STEERING_HEAT, "♨️ 핸들 열선", category = "BOTTOM_BAR"),
        FloatingItem("BB_AUTOHOLD", "🅿️ 오토홀드", category = "BOTTOM_BAR")
    )

    // 6-3. 모든 실내등 켜기/끄기
    val LIGHT_ITEMS = listOf(
        FloatingItem(ID_INSIDE_LIGHT, "💡 실내등 토글", category = "LIGHT"),
        FloatingItem("LIGHT_ON", "💡 실내등 켜기", category = "LIGHT"),
        FloatingItem("LIGHT_OFF", "💡 실내등 끄기", category = "LIGHT"),
        FloatingItem("LIGHT_DOOR", "🚪 도어연동등", category = "LIGHT")
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedIds(context: Context): List<String> {
        val jsonStr = getPrefs(context).getString(KEY_SELECTED_IDS, null)
        if (jsonStr == null) {
            return listOf(ID_DEFROST, ID_INSIDE_LIGHT, ID_STEERING_HEAT)
        }
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        } catch (e: Exception) {
            return listOf(ID_DEFROST, ID_INSIDE_LIGHT, ID_STEERING_HEAT)
        }
        return list
    }

    fun setSelectedIds(context: Context, ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        getPrefs(context).edit().putString(KEY_SELECTED_IDS, arr.toString()).apply()
    }

    fun getAllAvailableItems(context: Context): List<FloatingItem> {
        val list = mutableListOf<FloatingItem>()
        list.addAll(DEFAULT_VEHICLE_FUNCTIONS)
        list.addAll(QUICK_PANEL_ITEMS)
        list.addAll(BOTTOM_BAR_ITEMS.filter { bb -> list.none { it.id == bb.id } })
        list.addAll(LIGHT_ITEMS.filter { li -> list.none { it.id == li.id } })

        val installedApps = AppRoutingManager.getInstalledApps(context)
        installedApps.forEach { app ->
            list.add(FloatingItem(
                id = "APP_${app.packageName}",
                title = "📱 " + app.name,
                isApp = true,
                packageName = app.packageName,
                category = "APP"
            ))
        }
        return list
    }

    fun addItem(context: Context, item: FloatingItem) {
        val ids = getSelectedIds(context).toMutableList()
        if (!ids.contains(item.id)) {
            ids.add(item.id)
            setSelectedIds(context, ids)
        }
    }
}
