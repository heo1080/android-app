package com.byd.dolphin.autoassistant.floating

import android.content.Context
import android.content.SharedPreferences
import com.byd.dolphin.autoassistant.manager.AppRoutingManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 6번 요구사항:
 * 앱서랍 바로가기 및 플로팅 버튼 항목 관리자 (생성 버튼 클릭 시에만 생성)
 * 6-1. 상단바 퀵패널 내 모든 버튼 플로팅/바로가기
 * 6-2. 하단바 내 모든 버튼 플로팅/바로가기
 * 6-3. 모든 실내등 켜기/끄기 플로팅/바로가기
 * 6-4. 앱서랍 내 모든 앱 플로팅 만들기
 */
data class FloatingItem(
    val id: String,
    val title: String,
    val isApp: Boolean = false,
    val packageName: String = "",
    val category: String = "VEHICLE" // QUICK_PANEL, BOTTOM_BAR, LIGHT, APP
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
    private const val KEY_SELECTED_ITEMS = "key_selected_floating_items_v17"

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
        FloatingItem("BB_DEFROST_FRONT", "♨️ 앞유리 성에", category = "BOTTOM_BAR"),
        FloatingItem("BB_DEFROST_REAR", "♨️ 뒷유리 열선", category = "BOTTOM_BAR"),
        FloatingItem("BB_AC_TOGGLE", "❄️ 공조 전원", category = "BOTTOM_BAR"),
        FloatingItem("BB_SEAT_HEAT_D", "💺 운전석 열선", category = "BOTTOM_BAR"),
        FloatingItem("BB_SEAT_HEAT_P", "💺 조수석 열선", category = "BOTTOM_BAR"),
        FloatingItem("BB_STEERING_HEAT", "♨️ 핸들 열선", category = "BOTTOM_BAR"),
        FloatingItem("BB_AUTOHOLD", "🅿️ 오토홀드", category = "BOTTOM_BAR")
    )

    // 6-3. 모든 실내등 켜기/끄기
    val LIGHT_ITEMS = listOf(
        FloatingItem("LIGHT_TOGGLE", "💡 실내등 토글", category = "LIGHT"),
        FloatingItem("LIGHT_ON", "💡 실내등 켜기", category = "LIGHT"),
        FloatingItem("LIGHT_OFF", "💡 실내등 끄기", category = "LIGHT"),
        FloatingItem("LIGHT_DOOR", "🚪 도어연동등", category = "LIGHT")
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedItems(context: Context): MutableList<FloatingItem> {
        val jsonStr = getPrefs(context).getString(KEY_SELECTED_ITEMS, null)
        if (jsonStr == null) {
            return mutableListOf(
                FloatingItem("LIGHT_TOGGLE", "💡 실내등", category = "LIGHT"),
                FloatingItem("BB_DEFROST_FRONT", "♨️ 성에제거", category = "BOTTOM_BAR"),
                FloatingItem("BB_STEERING_HEAT", "♨️ 핸들열선", category = "BOTTOM_BAR")
            )
        }
        val list = mutableListOf<FloatingItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(FloatingItem.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveSelectedItems(context: Context, items: List<FloatingItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        getPrefs(context).edit().putString(KEY_SELECTED_ITEMS, arr.toString()).apply()
    }

    fun addItem(context: Context, item: FloatingItem) {
        val list = getSelectedItems(context)
        if (list.none { it.id == item.id }) {
            list.add(item)
            saveSelectedItems(context, list)
        }
    }

    fun removeItem(context: Context, id: String) {
        val list = getSelectedItems(context)
        list.removeAll { it.id == id }
        saveSelectedItems(context, list)
    }

    // 6-4. 앱서랍 내의 모든 앱을 플로팅 아이템으로 반환
    fun getInstalledAppItems(context: Context): List<FloatingItem> {
        return AppRoutingManager.getInstalledApps(context).map { app ->
            FloatingItem(
                id = "APP_${app.packageName}",
                title = "📱 " + app.name,
                isApp = true,
                packageName = app.packageName,
                category = "APP"
            )
        }
    }
}
