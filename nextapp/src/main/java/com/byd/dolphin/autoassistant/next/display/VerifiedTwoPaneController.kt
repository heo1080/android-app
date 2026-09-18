package com.byd.dolphin.autoassistant.next.display

import android.content.ComponentName
import android.content.Context
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.system.NextAdb
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

object VerifiedTwoPaneController {
    private const val MODE_PRIMARY = 3
    private const val MODE_SECONDARY = 4
    private const val POLL_ATTEMPTS = 12
    private const val POLL_MS = 250L
    private const val MIN_RATIO = 20
    private const val MAX_RATIO = 80
    private const val PREFS = "dolphin_next_split_verified"

    private data class TaskState(
        val taskId: Int = -1,
        val stackId: Int = -1,
        val mode: Int = -1
    )

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        override fun toString(): String =
            "[" + left + "," + top + "][" + right + "," + bottom + "]"
    }

    suspend fun launch(
        context: Context,
        firstPackage: String,
        secondPackage: String,
        requestedRatio: Int
    ): WindowLaunchResult {
        val app = context.applicationContext
        val primary = launchComponent(app, firstPackage)
            ?: return WindowLaunchResult(false, WindowLayoutMode.TWO, "첫 앱 실행 컴포넌트 없음: " + firstPackage)
        val secondary = launchComponent(app, secondPackage)
            ?: return WindowLaunchResult(false, WindowLayoutMode.TWO, "둘째 앱 실행 컴포넌트 없음: " + secondPackage)

        val ratio = requestedRatio.coerceIn(MIN_RATIO, MAX_RATIO)
        val trace = StringBuilder()
        var primaryState = findTaskState(app, primary.packageName)
        traceState("primary-initial", primaryState, trace)

        if (primaryState.taskId >= 0 && primaryState.mode != MODE_PRIMARY) {
            val ghost = ComponentName(app, NextSplitGhostActivity::class.java)
            if (!command(
                    app,
                    "am start --user 0 --windowingMode " + MODE_PRIMARY +
                        " -n " + quote(ghost.flattenToShortString()),
                    trace
                )
            ) {
                return fail("ghost primary 실패", trace)
            }

            val ghostState = waitForMode(
                app,
                app.packageName,
                MODE_PRIMARY,
                "ghost",
                trace,
                "NextSplitGhostActivity"
            ) ?: return fail("ghost mode3 미확인", trace)

            primaryState = findTaskState(app, primary.packageName)
                .takeIf { it.taskId >= 0 } ?: primaryState

            if (!command(
                    app,
                    "am stack move-task " + primaryState.taskId +
                        " " + ghostState.stackId + " true",
                    trace
                )
            ) {
                return fail("primary task move 실패", trace)
            }

            if (
                waitForMode(
                    app,
                    primary.packageName,
                    MODE_PRIMARY,
                    "primary-moved",
                    trace
                ) == null
            ) {
                return fail("primary mode3 미확인", trace)
            }
        } else if (primaryState.taskId < 0) {
            var ok = startInMode(
                app,
                primary,
                MODE_PRIMARY,
                "primary-cold",
                trace
            )
            if (!ok) {
                trace.append("primary-cold-retry\n")
                ok = startInMode(
                    app,
                    primary,
                    MODE_PRIMARY,
                    "primary-cold-retry",
                    trace
                )
            }
            if (!ok) return fail("primary 실행 실패", trace)
        }

        var secondaryReady = startInMode(
            app,
            secondary,
            MODE_SECONDARY,
            "secondary",
            trace
        )

        if (!secondaryReady) {
            val secondaryState = findTaskState(app, secondary.packageName)
            val secondaryStack = findStackForMode(app, MODE_SECONDARY)
            trace.append("secondary-move task=")
                .append(secondaryState.taskId)
                .append(" stack=")
                .append(secondaryStack)
                .append('\n')

            if (
                secondaryState.taskId >= 0 &&
                secondaryStack >= 0 &&
                command(
                    app,
                    "am stack move-task " + secondaryState.taskId +
                        " " + secondaryStack + " true",
                    trace
                )
            ) {
                secondaryReady = waitForMode(
                    app,
                    secondary.packageName,
                    MODE_SECONDARY,
                    "secondary-moved",
                    trace
                ) != null
            }
        }

        if (!secondaryReady) {
            trace.append("secondary-mode4-retry\n")
            secondaryReady = startInMode(
                app,
                secondary,
                MODE_SECONDARY,
                "secondary-retry",
                trace
            )
        }

        if (!secondaryReady) return fail("secondary mode4 실패", trace)

        val ratioResult = applyRatioInternal(app, ratio)
        trace.append("ratio success=")
            .append(ratioResult.first)
            .append(" detail=")
            .append(ratioResult.second)
            .append('\n')

        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pkg1", firstPackage)
            .putString("pkg2", secondPackage)
            .putInt("ratio", ratio)
            .apply()

        val detail = if (ratioResult.first) {
            "VERIFIED 2분할 " + ratio + ":" + (100 - ratio) + " · " + ratioResult.second
        } else {
            "2분할 mode3/4 성공 · 비율 실패 · " + ratioResult.second
        }
        NextLogger.i("SPLIT_VERIFIED", detail + " | " + trace.toString().replace("\n", "|").take(7000))
        return WindowLaunchResult(true, WindowLayoutMode.TWO, detail)
    }

    fun applyRatio(
        context: Context,
        requestedRatio: Int
    ): WindowLaunchResult {
        val ratio = requestedRatio.coerceIn(MIN_RATIO, MAX_RATIO)
        val result = applyRatioInternal(context.applicationContext, ratio)
        val detail = if (result.first) {
            "2분할 비율 " + ratio + ":" + (100 - ratio) + " 적용 완료 · " + result.second
        } else {
            "2분할 비율 적용 실패 · " + result.second
        }
        NextLogger.i("SPLIT_RATIO", detail)
        return WindowLaunchResult(result.first, WindowLayoutMode.TWO, detail)
    }

    suspend fun restoreLast(context: Context): WindowLaunchResult {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val first = p.getString("pkg1", null)
        val second = p.getString("pkg2", null)
        val ratio = p.getInt("ratio", 50)
        if (first.isNullOrBlank() || second.isNullOrBlank()) {
            return WindowLaunchResult(false, WindowLayoutMode.TWO, "저장된 2분할 구성 없음")
        }
        return launch(context, first, second, ratio)
    }

    private suspend fun startInMode(
        context: Context,
        component: ComponentName,
        mode: Int,
        label: String,
        trace: StringBuilder
    ): Boolean {
        val shell =
            "am start --user 0 --windowingMode " + mode +
                " -n " + quote(component.flattenToShortString())
        return command(context, shell, trace) &&
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
            traceState(label + " poll=" + (index + 1), state, trace)
            if (
                state.taskId >= 0 &&
                state.stackId >= 0 &&
                state.mode == mode
            ) return state
        }
        return null
    }

    private fun findTaskState(
        context: Context,
        packageName: String,
        requiredMarker: String? = null
    ): TaskState {
        val result = NextAdb.shell(context, "dumpsys activity activities")
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

            extractMode(line)
                .takeIf { it >= 0 }
                ?.let { mode = it }

            extractTaskId(line)?.let { taskId = it }

            val packageMatches =
                line.contains("A=" + packageName) ||
                    line.contains(packageName + "/")
            val markerMatches =
                requiredMarker == null ||
                    line.contains(requiredMarker)

            if (
                taskId >= 0 &&
                packageMatches &&
                markerMatches
            ) {
                return TaskState(taskId, stackId, mode)
            }
        }
        return TaskState()
    }

    private fun findStackForMode(
        context: Context,
        targetMode: Int
    ): Int {
        val result = NextAdb.shell(context, "dumpsys activity activities")
        if (!result.success) return -1
        var stackId = -1
        result.output.lineSequence().forEach { source ->
            val line = source.trim()
            extractStackId(line)?.let { stackId = it }
            if (
                stackId >= 0 &&
                extractMode(line) == targetMode
            ) return stackId
        }
        return -1
    }

    private fun extractStackId(line: String): Int? =
        Regex("Stack\\s+#(\\d+)", RegexOption.IGNORE_CASE)
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: Regex("ActivityStack[^#]*#(\\d+)", RegexOption.IGNORE_CASE)
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

    private fun extractTaskId(line: String): Int? {
        if (!line.contains("Task{") && !line.contains("TaskRecord{")) return null
        return Regex("#(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractMode(line: String): Int {
        val value = line.lowercase()
        return when {
            "split-screen-primary" in value ||
                "mwindowingmode=3" in value ||
                "windowingmode=3" in value -> MODE_PRIMARY
            "split-screen-secondary" in value ||
                "mwindowingmode=4" in value ||
                "windowingmode=4" in value -> MODE_SECONDARY
            else -> -1
        }
    }

    private fun command(
        context: Context,
        shell: String,
        trace: StringBuilder
    ): Boolean {
        val result = NextAdb.shell(context, shell)
        trace.append("cmd=")
            .append(shell)
            .append(" exit=")
            .append(result.exitCode)
            .append(" out=")
            .append(oneLine(result.output))
            .append(" msg=")
            .append(oneLine(result.message))
            .append('\n')
        return result.success
    }

    private fun applyRatioInternal(
        context: Context,
        requestedRatio: Int
    ): Pair<Boolean, String> {
        val stackList = NextAdb.shell(context, "am stack list")
        if (!stackList.success) {
            return false to (
                "stack-list exit=" +
                    stackList.exitCode +
                    " " +
                    oneLine(stackList.message)
                )
        }

        var pendingBounds: Bounds? = null
        var primary: Bounds? = null
        var secondary: Bounds? = null

        stackList.output.lineSequence().forEach { source ->
            val line = source.trim()
            if (line.startsWith("Stack id=")) {
                pendingBounds = parseBounds(line)
            }
            val bounds = pendingBounds
            if (bounds != null && "mActivityType=standard" in line) {
                if ("mWindowingMode=split-screen-primary" in line) primary = bounds
                if ("mWindowingMode=split-screen-secondary" in line) secondary = bounds
            }
        }

        val p = primary ?: return false to "standard primary bounds not found"
        val s = secondary ?: return false to "standard secondary bounds not found"
        val ratio = requestedRatio.coerceIn(MIN_RATIO, MAX_RATIO)

        val axis: String
        val divider: Int
        val target: Bounds

        if (
            p.left == s.left &&
            p.right == s.right &&
            p.bottom <= s.top
        ) {
            axis = "Y"
            divider = s.top - p.bottom
            val usable =
                (s.bottom - p.top - divider).coerceAtLeast(1)
            target = Bounds(
                p.left,
                p.top,
                p.right,
                p.top + (usable * ratio / 100f).roundToInt()
            )
        } else if (
            p.top == s.top &&
            p.bottom == s.bottom &&
            p.right <= s.left
        ) {
            axis = "X"
            divider = s.left - p.right
            val usable =
                (s.right - p.left - divider).coerceAtLeast(1)
            target = Bounds(
                p.left,
                p.top,
                p.left + (usable * ratio / 100f).roundToInt(),
                p.bottom
            )
        } else {
            return false to "unsupported bounds primary=" + p + " secondary=" + s
        }

        val result = NextAdb.shell(
            context,
            "am stack resize-docked-stack " +
                target.left + " " +
                target.top + " " +
                target.right + " " +
                target.bottom + " " +
                target.left + " " +
                target.top + " " +
                target.right + " " +
                target.bottom
        )

        return result.success to (
            "percent=" + ratio +
                " axis=" + axis +
                " divider=" + divider +
                " primary=" + p +
                " secondary=" + s +
                " target=" + target +
                " exit=" + result.exitCode +
                " out=" + oneLine(result.output)
            )
    }

    private fun parseBounds(line: String): Bounds? {
        val match = Regex(
            "bounds=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]"
        ).find(line) ?: return null

        return Bounds(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt()
        )
    }

    private fun launchComponent(
        context: Context,
        packageName: String
    ): ComponentName? =
        context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.component

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun oneLine(value: String?): String =
        value.orEmpty()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(500)

    private fun traceState(
        label: String,
        state: TaskState,
        trace: StringBuilder
    ) {
        trace.append(label)
            .append(" task=")
            .append(state.taskId)
            .append(" stack=")
            .append(state.stackId)
            .append(" mode=")
            .append(state.mode)
            .append('\n')
    }

    private fun fail(
        reason: String,
        trace: StringBuilder
    ): WindowLaunchResult {
        val detail = reason + " · " + trace.toString().replace("\n", " | ").takeLast(2200)
        NextLogger.w("SPLIT_VERIFIED", detail)
        return WindowLaunchResult(false, WindowLayoutMode.TWO, detail)
    }
}
