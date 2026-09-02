package com.byd.dolphin.autoassistant.floating

import android.content.Context
import android.content.SharedPreferences
import com.byd.dolphin.autoassistant.manager.AppRoutingManager
import org.json.JSONArray

data class FloatingItem(
    val id: String,
    val title: String,
    val isApp: Boolean = false,
    val packageName: String = ""
)

object FloatingItemManager {

    private const val PREF_NAME = "dolphin_floating_items_pref"
    private const val KEY_SELECTED_IDS = "key_selected_floating_ids"

    const val ID_DEFROST = "FUNC_DEFROST"
    const val ID_INSIDE_LIGHT = "FUNC_INSIDE_LIGHT"
    const val ID_STEERING_HEAT = "FUNC_STEERING_HEAT"
    const val ID_SEAT_HEAT = "FUNC_SEAT_HEAT"
    const val ID_AC_TOGGLE = "FUNC_AC_TOGGLE"

    val DEFAULT_VEHICLE_FUNCTIONS = listOf(
        FloatingItem(ID_DEFROST, "♨️ 성에제거"),
        FloatingItem(ID_INSIDE_LIGHT, "💡 실내등"),
        FloatingItem(ID_STEERING_HEAT, "♨️ 핸들열선"),
        FloatingItem(ID_SEAT_HEAT, "💺 시트열선"),
        FloatingItem(ID_AC_TOGGLE, "❄️ 공조 토글")
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedIds(context: Context): List<String> {
        val jsonStr = getPrefs(context).getString(KEY_SELECTED_IDS, null)
        if (jsonStr == null) {
            return listOf(ID_DEFROST, ID_INSIDE_LIGHT) // 기본 선택값
        }
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        } catch (e: Exception) {
            return listOf(ID_DEFROST, ID_INSIDE_LIGHT)
        }
        return list
    }

    fun setSelectedIds(context: Context, ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        getPrefs(context).edit().putString(KEY_SELECTED_IDS, arr.toString()).apply()
    }

    /**
     * 선택 가능한 모든 차량 기능 + 설치된 모든 앱 목록 반환
     */
    fun getAllAvailableItems(context: Context): List<FloatingItem> {
        val list = mutableListOf<FloatingItem>()
        list.addAll(DEFAULT_VEHICLE_FUNCTIONS)

        val installedApps = AppRoutingManager.getInstalledApps(context)
        installedApps.forEach { app ->
            list.add(FloatingItem(app.packageName, "📱 " + app.name, isApp = true, packageName = app.packageName))
        }
        return list
    }
}
