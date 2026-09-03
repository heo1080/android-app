package com.byd.dolphin.autoassistant.rule.engine

import android.content.Context
import android.content.SharedPreferences
import com.byd.dolphin.autoassistant.rule.model.RoutineRule
import org.json.JSONArray

object RuleStorage {

    private const val PREF_NAME = "dolphin_routine_rules_pref"
    private const val KEY_RULES_JSON = "key_rules_json_v14"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun loadRules(context: Context): MutableList<RoutineRule> {
        val jsonStr = getPrefs(context).getString(KEY_RULES_JSON, null) ?: return mutableListOf()
        val list = mutableListOf<RoutineRule>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(RoutineRule.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveRules(context: Context, rules: List<RoutineRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        getPrefs(context).edit().putString(KEY_RULES_JSON, arr.toString()).apply()
    }

    fun addRule(context: Context, rule: RoutineRule) {
        val rules = loadRules(context)
        rules.add(rule)
        saveRules(context, rules)
    }

    fun updateRule(context: Context, rule: RoutineRule) {
        val rules = loadRules(context)
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index != -1) {
            rules[index] = rule
            saveRules(context, rules)
        }
    }

    fun deleteRule(context: Context, ruleId: String) {
        val rules = loadRules(context)
        rules.removeAll { it.id == ruleId }
        saveRules(context, rules)
    }
}
