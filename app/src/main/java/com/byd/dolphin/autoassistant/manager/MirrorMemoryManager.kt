package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * Calibrated reverse-down mirror automation.
 *
 * No angle range is guessed. NORMAL and REVERSE presets are captured from the
 * vehicle's own getters after the driver physically positions the mirrors. Only
 * those exact captured values are ever written back.
 *
 * Safety rules:
 * - R preset is applied only at <= 5 km/h with a finite speed sample.
 * - Leaving R restores NORMAL only if this automation still owns the mirrors.
 * - Physical/manual angle changes while R is active cancel ownership and block
 *   the forced restore, so the app never fights the driver.
 */
object MirrorMemoryManager {
    private const val TAG = "MIRROR_MEMORY"
    private const val PREF = "dolphin_mirror_memory_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NORMAL_LEFT = "normal_left"
    private const val KEY_NORMAL_RIGHT = "normal_right"
    private const val KEY_REVERSE_LEFT = "reverse_left"
    private const val KEY_REVERSE_RIGHT = "reverse_right"
    private const val KEY_HAS_NORMAL = "has_normal"
    private const val KEY_HAS_REVERSE = "has_reverse"
    private const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    private const val MAX_REVERSE_SPEED_KMH = 5.0f
    private const val RESTORE_DELAY_MS = 850L
    private const val MANUAL_CHECK_INTERVAL_MS = 700L
    private const val COMMAND_SETTLE_MS = 900L

    enum class State {
        NORMAL, SAVING, DOWN, REVERSE, RESTORE_PENDING, RESTORING, MANUAL_OVERRIDE, ERROR
    }

    data class Position(val left: Int, val right: Int)

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var state: State = State.NORMAL
    @Volatile private var autoOwnsMirrors = false
    @Volatile private var commandedPosition: Position? = null
    @Volatile private var lastCommandElapsed = 0L
    @Volatile private var lastManualCheckElapsed = 0L
    @Volatile private var currentGear = "P"

    private val restoreRunnable = Runnable { pendingRestoreContext?.let { restoreNormalNow(it) } }
    @Volatile private var pendingRestoreContext: Context? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            handler.removeCallbacks(restoreRunnable)
            autoOwnsMirrors = false
            commandedPosition = null
            pendingRestoreContext = null
            state = State.NORMAL
        }
        DolphinLogger.i(TAG, "enabled=$enabled")
    }

    fun readCurrent(context: Context): Position? = runCatching {
        val target = setting(context)
        val left = invokeInt(target, "getLeftViewMirrorFlipAngle")
        val right = invokeInt(target, "getRightViewMirrorFlipAngle")
        if (left == null || right == null) null else Position(left, right)
    }.onFailure { DolphinLogger.e(TAG, "mirror getter failed", it.cause ?: it) }.getOrNull()

    fun captureNormal(context: Context): Position? {
        state = State.SAVING
        val pos = readCurrent(context)
        if (pos == null) {
            state = State.ERROR
            return null
        }
        prefs(context).edit()
            .putInt(KEY_NORMAL_LEFT, pos.left)
            .putInt(KEY_NORMAL_RIGHT, pos.right)
            .putBoolean(KEY_HAS_NORMAL, true)
            .apply()
        state = State.NORMAL
        DolphinLogger.i(TAG, "NORMAL preset captured left=${pos.left} right=${pos.right}")
        return pos
    }

    fun captureReverse(context: Context): Position? {
        state = State.SAVING
        val pos = readCurrent(context)
        if (pos == null) {
            state = State.ERROR
            return null
        }
        prefs(context).edit()
            .putInt(KEY_REVERSE_LEFT, pos.left)
            .putInt(KEY_REVERSE_RIGHT, pos.right)
            .putBoolean(KEY_HAS_REVERSE, true)
            .apply()
        state = State.NORMAL
        DolphinLogger.i(TAG, "REVERSE preset captured left=${pos.left} right=${pos.right}")
        return pos
    }

    fun normalPreset(context: Context): Position? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_HAS_NORMAL, false)) return null
        return Position(p.getInt(KEY_NORMAL_LEFT, 0), p.getInt(KEY_NORMAL_RIGHT, 0))
    }

    fun reversePreset(context: Context): Position? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_HAS_REVERSE, false)) return null
        return Position(p.getInt(KEY_REVERSE_LEFT, 0), p.getInt(KEY_REVERSE_RIGHT, 0))
    }

    fun statusSummary(context: Context): String {
        val now = readCurrent(context)
        return "enabled=${isEnabled(context)} state=$state current=$now normal=${normalPreset(context)} reverse=${reversePreset(context)} owns=$autoOwnsMirrors"
    }

    fun onGearChanged(context: Context, gear: String, speedKmH: Float) {
        val app = context.applicationContext
        val previous = currentGear
        currentGear = gear
        handler.removeCallbacks(restoreRunnable)
        pendingRestoreContext = null

        if (!isEnabled(app)) return
        if (normalPreset(app) == null || reversePreset(app) == null) {
            DolphinLogger.w(TAG, "automation skipped: NORMAL/REVERSE calibration incomplete")
            return
        }

        if (gear == "R") {
            if (!speedKmH.isFinite() || speedKmH > MAX_REVERSE_SPEED_KMH) {
                DolphinLogger.w(TAG, "R preset blocked speed=$speedKmH")
                return
            }
            applyReverseNow(app)
            return
        }

        if (previous == "R") {
            if (!autoOwnsMirrors || state == State.MANUAL_OVERRIDE) {
                DolphinLogger.i(TAG, "restore skipped: manual override/ownership released state=$state")
                autoOwnsMirrors = false
                commandedPosition = null
                state = State.NORMAL
                return
            }
            if (gear == "P") {
                restoreNormalNow(app)
            } else if (gear == "D" || gear == "N") {
                state = State.RESTORE_PENDING
                pendingRestoreContext = app
                handler.postDelayed(restoreRunnable, RESTORE_DELAY_MS)
                DolphinLogger.i(TAG, "NORMAL restore scheduled delayMs=$RESTORE_DELAY_MS gear=$gear")
            }
        }
    }

    /** Called from the existing 500 ms motion monitor to detect manual mirror movement. */
    fun onVehicleMotion(context: Context, speedKmH: Float, gear: String) {
        if (!isEnabled(context) || !autoOwnsMirrors || gear != "R" || state != State.REVERSE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCommandElapsed < COMMAND_SETTLE_MS) return
        if (now - lastManualCheckElapsed < MANUAL_CHECK_INTERVAL_MS) return
        lastManualCheckElapsed = now
        val expected = commandedPosition ?: return
        val actual = readCurrent(context) ?: return
        if (actual != expected) {
            state = State.MANUAL_OVERRIDE
            autoOwnsMirrors = false
            commandedPosition = null
            DolphinLogger.w(TAG, "manual mirror override detected expected=$expected actual=$actual; forced restore disabled")
        }
    }

    fun applyPresetForStationaryTest(context: Context, reverse: Boolean): Boolean {
        val target = (if (reverse) reversePreset(context) else normalPreset(context)) ?: return false
        return applyPosition(context.applicationContext, target, if (reverse) "TEST_REVERSE" else "TEST_NORMAL", takeOwnership = false)
    }

    private fun applyReverseNow(context: Context) {
        val target = reversePreset(context) ?: return
        state = State.DOWN
        if (applyPosition(context, target, "REVERSE", takeOwnership = true)) {
            state = State.REVERSE
        } else {
            state = State.ERROR
            autoOwnsMirrors = false
        }
    }

    private fun restoreNormalNow(context: Context) {
        val target = normalPreset(context) ?: return
        state = State.RESTORING
        if (applyPosition(context, target, "NORMAL_RESTORE", takeOwnership = false)) {
            state = State.NORMAL
        } else {
            state = State.ERROR
        }
        autoOwnsMirrors = false
        commandedPosition = null
        pendingRestoreContext = null
    }

    private fun applyPosition(context: Context, position: Position, reason: String, takeOwnership: Boolean): Boolean {
        return runCatching {
            val target = setting(context)
            val leftResult = invokeSet(target, "setLeftViewMirrorFlipAngle", position.left)
            val rightResult = invokeSet(target, "setRightViewMirrorFlipAngle", position.right)
            val success = leftResult == 0 && rightResult == 0
            DolphinLogger.i(TAG, "$reason write left=${position.left}/$leftResult right=${position.right}/$rightResult success=$success")
            if (success) {
                lastCommandElapsed = SystemClock.elapsedRealtime()
                commandedPosition = position
                autoOwnsMirrors = takeOwnership
                handler.postDelayed({
                    val actual = readCurrent(context)
                    DolphinLogger.i(TAG, "$reason readback expected=$position actual=$actual")
                }, 450L)
            }
            success
        }.onFailure { DolphinLogger.e(TAG, "$reason write failed", it.cause ?: it) }.getOrDefault(false)
    }

    private fun setting(context: Context): Any {
        val clazz = Class.forName(SETTING_CLASS)
        return clazz.getMethod("getInstance", Context::class.java)
            .invoke(null, BydPermissionContext.wrap(context))
            ?: error("BYDAutoSettingDevice.getInstance returned null")
    }

    private fun invokeInt(target: Any, method: String): Int? =
        (target.javaClass.getMethod(method).invoke(target) as? Number)?.toInt()

    private fun invokeSet(target: Any, method: String, value: Int): Int =
        (target.javaClass.getMethod(method, Int::class.javaPrimitiveType)
            .invoke(target, value) as? Number)?.toInt() ?: Int.MIN_VALUE
}
