package com.byd.dolphin.autoassistant.manager

import android.content.Context

/**
 * Read-only capability audit for seat-memory research.
 *
 * Never moves a seat. It only reports whether a usable getter/setter pair exists
 * on the current DiLink firmware so the UI can avoid pretending M1/M2/M3 works.
 */
object SeatMemoryCapabilityManager {
    private const val SETTING =
        "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    private const val BODYWORK =
        "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
    private const val SEAT_DEVICE =
        "android.hardware.bydauto.seat.BYDAutoSeatDevice"
    private const val SEAT_MANAGER =
        "android.hardware.bydauto.seat.BYDAutoSeatManager"

    data class Snapshot(
        val driverSeatBackGetter: Boolean,
        val driverSeatBackSetter: Boolean,
        val driverAutoReturnGetter: Boolean,
        val driverAutoReturnSetter: Boolean,
        val driverComfortGetter: Boolean,
        val driverComfortSetter: Boolean,
        val passengerComfortGetter: Boolean,
        val passengerComfortSetter: Boolean,
        val seatDeviceClass: Boolean,
        val seatManagerClass: Boolean,
        val currentDriverSeatBackRaw: Int?,
        val currentAutoReturnRaw: Int?
    ) {
        val directPositionPairVerified: Boolean
            get() = driverSeatBackGetter && driverSeatBackSetter

        val comfortStagePairVerified: Boolean
            get() = driverComfortGetter && driverComfortSetter

        val anyWritableMemoryPathVerified: Boolean
            get() = directPositionPairVerified || comfortStagePairVerified
    }

    fun snapshot(context: Context): Snapshot {
        val settingClass = classOrNull(SETTING)
        val bodyClass = classOrNull(BODYWORK)

        return Snapshot(
            driverSeatBackGetter = settingClass.hasMethod("getDriverSeatBack"),
            driverSeatBackSetter = settingClass.hasIntSetter("setDriverSeatBack"),
            driverAutoReturnGetter = settingClass.hasMethod("getDriverSeatAutoReturn"),
            driverAutoReturnSetter = settingClass.hasIntSetter("setDriverSeatAutoReturn"),
            driverComfortGetter = bodyClass.hasMethod("getDriverComfortStage"),
            driverComfortSetter = bodyClass.hasIntSetter("setDriverComfortStage"),
            passengerComfortGetter = bodyClass.hasMethod("getPassengerComfortStage"),
            passengerComfortSetter = bodyClass.hasIntSetter("setPassengerComfortStage"),
            seatDeviceClass = classOrNull(SEAT_DEVICE) != null,
            seatManagerClass = classOrNull(SEAT_MANAGER) != null,
            currentDriverSeatBackRaw = readNoArgInt(context, SETTING, "getDriverSeatBack"),
            currentAutoReturnRaw = readNoArgInt(context, SETTING, "getDriverSeatAutoReturn")
        )
    }

    fun summary(context: Context): String {
        val s = snapshot(context)
        return buildString {
            appendLine("메모리 시트 실차 API 상태")
            appendLine("driverSeatBack GET=${s.driverSeatBackGetter} SET=${s.driverSeatBackSetter} raw=${s.currentDriverSeatBackRaw}")
            appendLine("driverAutoReturn GET=${s.driverAutoReturnGetter} SET=${s.driverAutoReturnSetter} raw=${s.currentAutoReturnRaw}")
            appendLine("driverComfortStage GET=${s.driverComfortGetter} SET=${s.driverComfortSetter}")
            appendLine("passengerComfortStage GET=${s.passengerComfortGetter} SET=${s.passengerComfortSetter}")
            appendLine("BYDAutoSeatDevice=${s.seatDeviceClass} BYDAutoSeatManager=${s.seatManagerClass}")
            appendLine("directPositionPairVerified=${s.directPositionPairVerified}")
            appendLine("comfortStagePairVerified=${s.comfortStagePairVerified}")
            appendLine("M1/M2/M3 motor recall=${if (s.anyWritableMemoryPathVerified) "CANDIDATE" else "LOCKED_UNTIL_SETTER_VERIFIED"}")
        }
    }

    private fun classOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name) }.getOrNull()

    private fun Class<*>?.hasMethod(name: String): Boolean =
        this?.methods?.any { it.name == name && it.parameterTypes.isEmpty() } == true

    private fun Class<*>?.hasIntSetter(name: String): Boolean =
        this?.methods?.any {
            it.name == name &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } == true

    private fun readNoArgInt(context: Context, className: String, methodName: String): Int? =
        runCatching {
            val clazz = Class.forName(className)
            val instance = clazz.getMethod("getInstance", Context::class.java)
                .invoke(null, BydPermissionContext.wrap(context)) ?: return@runCatching null
            (clazz.getMethod(methodName).invoke(instance) as? Number)?.toInt()
        }.getOrNull()
}
