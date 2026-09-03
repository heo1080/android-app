package com.byd.dolphin.autoassistant.rule.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable
import java.util.UUID

enum class TriggerType(val displayName: String) {
    ON_READY_POWER("시동(READY) / 전원 연결 시"),
    TEMPERATURE("외부 온도 조건 감지 시"),
    GEAR_CHANGED("기어 변속 시 (P/R/N/D)"),
    AC_DEFROST_TRIGGERED("성에제거 버튼 작동 시"),
    AC_HEATER_TRIGGERED("히터(난방) 작동 시")
}

data class Trigger(
    val type: TriggerType,
    val tempOperator: String = ">=", // ">=", "<="
    val tempValue: Float = 30f,      // 30도
    val gearTarget: String = "P"     // P, R, N, D
) : Serializable {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("tempOperator", tempOperator)
        put("tempValue", tempValue.toDouble())
        put("gearTarget", gearTarget)
    }

    companion object {
        fun fromJson(json: JSONObject): Trigger {
            return Trigger(
                type = TriggerType.valueOf(json.optString("type", TriggerType.ON_READY_POWER.name)),
                tempOperator = json.optString("tempOperator", ">="),
                tempValue = json.optDouble("tempValue", 30.0).toFloat(),
                gearTarget = json.optString("gearTarget", "P")
            )
        }
    }
}

enum class ActionType(val displayName: String) {
    DELAY("지연 대기 (0.x초)"),
    LAUNCH_APP("앱 실행"),
    MEDIA_CONTROL("미디어 재생 / 일시정지"),
    AC_CONTROL("에어컨 온도 및 풍량 제어"),
    HEAT_CONTROL("시트 및 핸들 열선 제어"),
    INSIDE_LIGHT("전체 실내등 제어"),
    DEFROST_CONTROL("통합 성에제거 제어")
}

data class ActionStep(
    val type: ActionType,
    val delaySeconds: Double = 0.5,       // 0.1초, 0.5초, 2.3초 등 소수점 초 단위
    val appPackage: String = "",          // 패키지명
    val appName: String = "",             // 앱 이름
    val mediaCmd: String = "PLAY",        // PLAY, PAUSE, TOGGLE
    val acTemp: Int = 22,                 // 에어컨 설정 온도
    val acWind: Int = 3,                  // 풍량 세기
    val heatSteering: Boolean = false,    // 핸들 열선
    val heatDriverSeat: Boolean = false,  // 운전석 열선
    val heatPassengerSeat: Boolean = false,// 동승석 열선
    val lightOn: Boolean = true,          // 실내등 ON/OFF
    val defrostOn: Boolean = true         // 성에제거 ON/OFF
) : Serializable {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("delaySeconds", delaySeconds)
        put("appPackage", appPackage)
        put("appName", appName)
        put("mediaCmd", mediaCmd)
        put("acTemp", acTemp)
        put("acWind", acWind)
        put("heatSteering", heatSteering)
        put("heatDriverSeat", heatDriverSeat)
        put("heatPassengerSeat", heatPassengerSeat)
        put("lightOn", lightOn)
        put("defrostOn", defrostOn)
    }

    companion object {
        fun fromJson(json: JSONObject): ActionStep {
            return ActionStep(
                type = ActionType.valueOf(json.optString("type", ActionType.DELAY.name)),
                delaySeconds = json.optDouble("delaySeconds", 0.5),
                appPackage = json.optString("appPackage", ""),
                appName = json.optString("appName", ""),
                mediaCmd = json.optString("mediaCmd", "PLAY"),
                acTemp = json.optInt("acTemp", 22),
                acWind = json.optInt("acWind", 3),
                heatSteering = json.optBoolean("heatSteering", false),
                heatDriverSeat = json.optBoolean("heatDriverSeat", false),
                heatPassengerSeat = json.optBoolean("heatPassengerSeat", false),
                lightOn = json.optBoolean("lightOn", true),
                defrostOn = json.optBoolean("defrostOn", true)
            )
        }
    }
}

data class RoutineRule(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var isEnabled: Boolean = true,
    var trigger: Trigger,
    val actions: MutableList<ActionStep> = mutableListOf()
) : Serializable {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("isEnabled", isEnabled)
        put("trigger", trigger.toJson())
        val arr = JSONArray()
        actions.forEach { arr.put(it.toJson()) }
        put("actions", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): RoutineRule {
            val rule = RoutineRule(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "새 자동화 규칙"),
                isEnabled = json.optBoolean("isEnabled", true),
                trigger = Trigger.fromJson(json.getJSONObject("trigger"))
            )
            val arr = json.optJSONArray("actions")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    rule.actions.add(ActionStep.fromJson(arr.getJSONObject(i)))
                }
            }
            return rule
        }
    }
}
