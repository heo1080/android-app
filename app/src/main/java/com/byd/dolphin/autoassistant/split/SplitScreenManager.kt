package com.byd.dolphin.autoassistant.split

import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import com.byd.dolphin.autoassistant.activity.SplitGhostActivity
import com.byd.dolphin.autoassistant.manager.NativeAdbClient
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * DiLink 3 Android 10 two-app split controller.
 *
 * The commands and windowing-mode values mirror the split flow captured from the
 * vehicle. DiLink 3 only exposed a reliable two-pane primary/secondary path;
 * three/four-pane requests are deliberately rejected instead of launching extra
 * full-screen activities and reporting a false success.
 */
object SplitScreenManager {
    private const val TAG = "SPLIT"
    private const val PREF_NAME = "dolphin_split_prefs"
    private const val MODE_PRIMARY = 3
    private const val MODE_SECONDARY = 4
    private const val POLL_ATTEMPTS = 12
    private const val POLL_MS = 250L
    private const val MIN_RATIO = 20
    private const val MAX_RATIO = 80

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class TaskState(
        val taskId: Int = -1,
        val stackId: Int = -1,
        val mode: Int = -1
    )

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        override fun toString(): String = "[$left,$top][$right,$bottom]"
    }

    private data class RatioResult(val success: Boolean, val detail: String)

    fun launchSplitScreen(context: Context, config: SplitConfig) {
        val appContext = context.applicationContext
        if (config.pkg1.isBlank() || config.pkg2.isBlank() || config.pkg1 == config.pkg2) {
            Toast.makeText(context, "서로 다른 앱 두 개를 선택하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val primary = launchComponent(appContext, config.pkg1)
        val secondary = launchComponent(appContext, config.pkg2)
        if (primary == null || secondary == null) {
            val missing = if (primary == null) config.pkg1 else config.pkg2
            DolphinLogger.w(TAG, "실행 컴포넌트를 찾지 못함: $missing")
            Toast.makeText(context, "선택한 앱을 실행할 수 없습니다: $missing", Toast.LENGTH_LONG).show()
            return
        }

        val ratio = config.ratioPrimary.coerceIn(MIN_RATIO, MAX_RATIO)
        Toast.makeText(context, "2분할 구성 중…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val trace = StringBuilder()
            val splitOk = launchTwoApps(appContext, primary, secondary, trace)
            val ratioResult = if (splitOk) applyRatio(appContext, ratio) else RatioResult(false, "split launch failed")
            trace.append("ratio result=").append(ratioResult.success).append(' ').append(ratioResult.detail)
            DolphinLogger.i(TAG, trace.toString().replace('\n', '|').take(8_000))

            if (splitOk) saveLastConfig(appContext, config.copy(ratioPrimary = ratio))
            withContext(Dispatchers.Main) {
                val message = when {
                    splitOk && ratioResult.success -> "2분할 ${ratio}:${100 - ratio} 적용 완료"
                    splitOk -> "2분할은 완료됐지만 비율 적용에 실패했습니다. 진단 로그를 확인하세요."
                    else -> "2분할 실행에 실패했습니다. 로컬 ADB 진단 로그를 확인하세요."
                }
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun launchTwoApps(
        context: Context,
        primary: ComponentName,
        secondary: ComponentName,
        trace: StringBuilder
    ): Boolean {
        var primaryState = findTaskState(context, primary.packageName)
        traceState("primary-initial", primaryState, trace)

        if (primaryState.taskId >= 0 && primaryState.mode != MODE_PRIMARY) {
            val ghost = ComponentName(context, SplitGhostActivity::class.java)
            if (!command(context, "am start --user 0 --windowingMode $MODE_PRIMARY -n ${quote(ghost.flattenToShortString())}", trace)) {
                return false
            }
            val ghostState = waitForMode(
                context,
                context.packageName,
                MODE_PRIMARY,
                "ghost",
                trace,
                "SplitGhostActivity"
            )
                ?: return false
            primaryState = findTaskState(context, primary.packageName).takeIf { it.taskId >= 0 } ?: primaryState
            if (!command(context, "am stack move-task ${primaryState.taskId} ${ghostState.stackId} true", trace)) {
                return false
            }
            if (waitForMode(context, primary.packageName, MODE_PRIMARY, "primary-moved", trace) == null) {
                return false
            }
        } else if (primaryState.taskId < 0) {
            if (!startInMode(context, primary, MODE_PRIMARY, "primary-cold", trace)) {
                trace.append("primary-cold-retry\n")
                if (!startInMode(context, primary, MODE_PRIMARY, "primary-cold-retry", trace)) return false
            }
        }

        var secondaryReady = startInMode(context, secondary, MODE_SECONDARY, "secondary", trace)
        if (!secondaryReady) {
            val secondaryState = findTaskState(context, secondary.packageName)
            val secondaryStack = findStackForMode(context, MODE_SECONDARY)
            trace.append("secondary-move task=").append(secondaryState.taskId)
                .append(" stack=").append(secondaryStack).append('\n')
            if (secondaryState.taskId >= 0 && secondaryStack >= 0 &&
                command(context, "am stack move-task ${secondaryState.taskId} $secondaryStack true", trace)
            ) {
                secondaryReady = waitForMode(
                    context,
                    secondary.packageName,
                    MODE_SECONDARY,
                    "secondary-moved",
                    trace
                ) != null
            }
        }
        if (!secondaryReady) {
            trace.append("secondary-mode4-retry\n")
            secondaryReady = startInMode(context, secondary, MODE_SECONDARY, "secondary-retry", trace)
        }
        return secondaryReady
    }

    private suspend fun startInMode(
        context: Context,
        component: ComponentName,
        mode: Int,
        label: String,
        trace: StringBuilder
    ): Boolean {
        val cmd = "am start --user 0 --windowingMode $mode -n ${quote(component.flattenToShortString())}"
        return command(context, cmd, trace) &&
            waitForMode(context, component.packageName, mode, label, trace) != null
    }

    private suspend fun waitForMode(
        context: Context,
        packageName: String,
        mode: Int,
        label: String,
        trace: StringBuilder,
        requiredMarker: String? = null
    ): TaskState? {
        repeat(POLL_ATTEMPTS) { index ->
            delay(POLL_MS)
            val state = findTaskState(context, packageName, requiredMarker)
            traceState("$label poll=${index + 1}", state, trace)
            if (state.taskId >= 0 && state.stackId >= 0 && state.mode == mode) return state
        }
        return null
    }

    private fun findTaskState(context: Context, packageName: String, requiredMarker: String? = null): TaskState {
        val result = NativeAdbClient.executeShell(context, "dumpsys activity activities")
        if (!result.success) return TaskState()

        var stackId = -1
        var mode = -1
        var taskId = -1
        result.output.lineSequence().forEach { source ->
            val line = source.trim()
            extractStackId(line)?.let {
                stackId = it
                mode = extractMode(line).takeIf { value -> value >= 0 } ?: -1
                taskId = -1
            }
            extractMode(line).takeIf { it >= 0 }?.let { mode = it }
            extractTaskId(line)?.let { taskId = it }
            val packageMatches = line.contains("A=$packageName") || line.contains("$packageName/")
            val markerMatches = requiredMarker == null || line.contains(requiredMarker)
            if (taskId >= 0 && packageMatches && markerMatches) {
                return TaskState(taskId, stackId, mode)
            }
        }
        return TaskState()
    }

    private fun findStackForMode(context: Context, targetMode: Int): Int {
        val result = NativeAdbClient.executeShell(context, "dumpsys activity activities")
        if (!result.success) return -1
        var stackId = -1
        result.output.lineSequence().forEach { source ->
            val line = source.trim()
            extractStackId(line)?.let { stackId = it }
            if (stackId >= 0 && extractMode(line) == targetMode) return stackId
        }
        return -1
    }

    private fun extractStackId(line: String): Int? {
        return Regex("Stack\\s+#(\\d+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("ActivityStack[^#]*#(\\d+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractTaskId(line: String): Int? {
        if (!line.contains("Task{") && !line.contains("TaskRecord{")) return null
        return Regex("#(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractMode(line: String): Int {
        val value = line.lowercase()
        return when {
            "split-screen-primary" in value || "mwindowingmode=3" in value || "windowingmode=3" in value -> MODE_PRIMARY
            "split-screen-secondary" in value || "mwindowingmode=4" in value || "windowingmode=4" in value -> MODE_SECONDARY
            else -> -1
        }
    }

    private fun command(context: Context, command: String, trace: StringBuilder): Boolean {
        val result = NativeAdbClient.executeShell(context, command)
        trace.append("cmd=").append(command).append(" exit=").append(result.exitCode)
            .append(" out=").append(oneLine(result.output))
            .append(" msg=").append(oneLine(result.message)).append('\n')
        return result.success
    }

    private fun applyRatio(context: Context, requestedRatio: Int): RatioResult {
        val stackList = NativeAdbClient.executeShell(context, "am stack list")
        if (!stackList.success) return RatioResult(false, "stack-list exit=${stackList.exitCode} ${oneLine(stackList.message)}")

        var pendingBounds: Rect? = null
        var primary: Rect? = null
        var secondary: Rect? = null
        stackList.output.lineSequence().forEach { source ->
            val line = source.trim()
            if (line.startsWith("Stack id=")) pendingBounds = parseBounds(line)
            val bounds = pendingBounds
            if (bounds != null && "mActivityType=standard" in line) {
                if ("mWindowingMode=split-screen-primary" in line) primary = bounds
                if ("mWindowingMode=split-screen-secondary" in line) secondary = bounds
            }
        }
        val p = primary ?: return RatioResult(false, "standard primary bounds not found")
        val s = secondary ?: return RatioResult(false, "standard secondary bounds not found")
        val ratio = requestedRatio.coerceIn(MIN_RATIO, MAX_RATIO)

        val axis: String
        val divider: Int
        val target: Rect
        if (p.left == s.left && p.right == s.right && p.bottom <= s.top) {
            axis = "Y"
            divider = s.top - p.bottom
            val usable = (s.bottom - p.top - divider).coerceAtLeast(1)
            target = Rect(p.left, p.top, p.right, p.top + (usable * ratio / 100f).roundToInt())
        } else if (p.top == s.top && p.bottom == s.bottom && p.right <= s.left) {
            axis = "X"
            divider = s.left - p.right
            val usable = (s.right - p.left - divider).coerceAtLeast(1)
            target = Rect(p.left, p.top, p.left + (usable * ratio / 100f).roundToInt(), p.bottom)
        } else {
            return RatioResult(false, "unsupported bounds primary=$p secondary=$s")
        }

        val result = NativeAdbClient.executeShell(
            context,
            "am stack resize-docked-stack ${target.left} ${target.top} ${target.right} ${target.bottom} " +
                "${target.left} ${target.top} ${target.right} ${target.bottom}"
        )
        return RatioResult(
            result.success,
            "percent=$ratio axis=$axis divider=$divider primary=$p secondary=$s target=$target " +
                "exit=${result.exitCode} out=${oneLine(result.output)} msg=${oneLine(result.message)}"
        )
    }

    private fun parseBounds(line: String): Rect? {
        val match = Regex("bounds=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").find(line) ?: return null
        return Rect(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt()
        )
    }

    private fun launchComponent(context: Context, packageName: String): ComponentName? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.component

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun oneLine(value: String?): String = value.orEmpty().replace('\r', ' ').replace('\n', ' ').trim().take(500)

    private fun traceState(label: String, state: TaskState, trace: StringBuilder) {
        trace.append(label).append(" task=").append(state.taskId)
            .append(" stack=").append(state.stackId).append(" mode=").append(state.mode).append('\n')
    }

    /** Restore the last verified two-pane configuration after the OEM AVM closes. */
    fun onSurroundViewDismissed(context: Context) {
        getLastConfig(context)?.let {
            DolphinLogger.i(TAG, "AVM 종료 후 저장된 2분할 복원 요청: ${it.ratioPrimary}%")
            launchSplitScreen(context, it)
        }
    }

    fun restoreLastSplitScreen(context: Context) = onSurroundViewDismissed(context)

    fun saveLastConfig(context: Context, config: SplitConfig) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString("title", config.title)
            .putString("mode", SplitMode.TWO_APPS_HORIZONTAL.name)
            .putString("pkg1", config.pkg1)
            .putString("pkg2", config.pkg2)
            .putInt("ratioPrimary", config.ratioPrimary.coerceIn(MIN_RATIO, MAX_RATIO))
            .apply()
    }

    fun getLastConfig(context: Context): SplitConfig? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains("pkg1") || !prefs.contains("pkg2")) return null
        return SplitConfig(
            title = prefs.getString("title", "2분할") ?: "2분할",
            mode = SplitMode.TWO_APPS_HORIZONTAL,
            pkg1 = prefs.getString("pkg1", "com.skt.tmap.ku") ?: "com.skt.tmap.ku",
            pkg2 = prefs.getString("pkg2", "com.android.music") ?: "com.android.music",
            ratioPrimary = prefs.getInt("ratioPrimary", 30).coerceIn(MIN_RATIO, MAX_RATIO)
        )
    }
}
