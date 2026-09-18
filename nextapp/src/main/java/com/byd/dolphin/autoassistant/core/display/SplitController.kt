package com.byd.dolphin.autoassistant.core.display

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.byd.dolphin.autoassistant.core.NextLog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

data class SplitConfig(
    val primaryPackage: String,
    val secondaryPackage: String,
    val ratioPrimary: Int = 50
)

data class LaunchableApp(
    val label: String,
    val packageName: String
)

class SplitController(context: Context) {
    private val app = context.applicationContext
    private val adb = LocalAdbShell(app)

    private data class TaskState(
        val taskId: Int = -1,
        val stackId: Int = -1,
        val mode: Int = -1
    )

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class RatioResult(val success: Boolean, val detail: String)

    fun installedApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return app.packageManager.queryIntentActivities(intent, 0)
            .map {
                LaunchableApp(
                    label = it.loadLabel(app.packageManager)?.toString().orEmpty()
                        .ifBlank { it.activityInfo.packageName },
                    packageName = it.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun getConfig(): SplitConfig {
        val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val installed = installedApps()
        val primaryFallback = installed.firstOrNull {
            it.packageName == "com.skt.tmap.ku"
        }?.packageName ?: installed.firstOrNull()?.packageName.orEmpty()
        val secondaryFallback = installed.firstOrNull {
            it.packageName == "com.google.android.apps.youtube.music"
        }?.packageName ?: installed.drop(1).firstOrNull()?.packageName.orEmpty()

        return SplitConfig(
            primaryPackage = prefs.getString("primary", primaryFallback) ?: primaryFallback,
            secondaryPackage = prefs.getString("secondary", secondaryFallback) ?: secondaryFallback,
            ratioPrimary = prefs.getInt("ratio", 50).coerceIn(20, 80)
        )
    }

    fun saveConfig(config: SplitConfig) {
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("primary", config.primaryPackage)
            .putString("secondary", config.secondaryPackage)
            .putInt("ratio", config.ratioPrimary.coerceIn(20, 80))
            .apply()
    }

    fun isAdbReady(): Boolean = adb.isPortOpen()

    suspend fun launch(config: SplitConfig): String {
        if (config.primaryPackage.isBlank() || config.secondaryPackage.isBlank()) {
            return "분할할 앱 두 개를 선택하세요."
        }
        if (config.primaryPackage == config.secondaryPackage) {
            return "서로 다른 앱 두 개를 선택하세요."
        }
        if (!adb.isPortOpen()) return "차량 로컬 ADB 127.0.0.1:5555가 열려 있지 않습니다."

        val primary = app.packageManager.getLaunchIntentForPackage(config.primaryPackage)?.component
            ?: return "첫 번째 앱을 실행할 수 없습니다."
        val secondary = app.packageManager.getLaunchIntentForPackage(config.secondaryPackage)?.component
            ?: return "두 번째 앱을 실행할 수 없습니다."

        val trace = StringBuilder()
        val splitOk = launchTwo(primary, secondary, trace)
        val ratio = config.ratioPrimary.coerceIn(20, 80)
        val ratioResult = if (splitOk) applyRatio(ratio) else RatioResult(false, "split launch failed")
        trace.append(" ratio=").append(ratioResult.detail)
        NextLog.i("SPLIT", trace.toString().replace("\n", " | ").take(8_000))

        if (splitOk) saveConfig(config.copy(ratioPrimary = ratio))
        return when {
            splitOk && ratioResult.success -> "2분할 " + ratio + ":" + (100 - ratio) + " 적용 완료"
            splitOk -> "2분할은 실행됐지만 비율 적용은 실패했습니다."
            else -> "2분할 실행 실패. 진단 로그를 확인하세요."
        }
    }

    suspend fun restore(): String = launch(getConfig())

    private suspend fun launchTwo(
        primary: ComponentName,
        secondary: ComponentName,
        trace: StringBuilder
    ): Boolean {
        var primaryState = findTask(primary.packageName)
        traceState("primary-initial", primaryState, trace)

        if (primaryState.taskId >= 0 && primaryState.mode != PRIMARY_MODE) {
            val ghost = ComponentName(app, SplitGhostActivity::class.java)
            if (!command(
                    "am start --user 0 --windowingMode " + PRIMARY_MODE +
                        " -n " + quote(ghost.flattenToShortString()),
                    trace
                )
            ) return false

            val ghostState = waitForMode(
                app.packageName,
                PRIMARY_MODE,
                "ghost",
                trace,
                "SplitGhostActivity"
            ) ?: return false

            primaryState = findTask(primary.packageName)
            if (primaryState.taskId < 0) return false
            if (!command(
                    "am stack move-task " + primaryState.taskId + " " +
                        ghostState.stackId + " true",
                    trace
                )
            ) return false

            if (waitForMode(primary.packageName, PRIMARY_MODE, "primary-moved", trace) == null) {
                return false
            }
        } else if (primaryState.taskId < 0) {
            if (!startInMode(primary, PRIMARY_MODE, "primary", trace)) {
                if (!startInMode(primary, PRIMARY_MODE, "primary-retry", trace)) return false
            }
        }

        var secondaryReady = startInMode(secondary, SECONDARY_MODE, "secondary", trace)
        if (!secondaryReady) {
            val state = findTask(secondary.packageName)
            val stack = findStackForMode(SECONDARY_MODE)
            if (state.taskId >= 0 && stack >= 0 &&
                command("am stack move-task " + state.taskId + " " + stack + " true", trace)
            ) {
                secondaryReady =
                    waitForMode(secondary.packageName, SECONDARY_MODE, "secondary-moved", trace) != null
            }
        }
        if (!secondaryReady) {
            secondaryReady = startInMode(secondary, SECONDARY_MODE, "secondary-retry", trace)
        }
        return secondaryReady
    }

    private suspend fun startInMode(
        component: ComponentName,
        mode: Int,
        label: String,
        trace: StringBuilder
    ): Boolean {
        val command = "am start --user 0 --windowingMode " + mode +
            " -n " + quote(component.flattenToShortString())
        return command(command, trace) &&
            waitForMode(component.packageName, mode, label, trace) != null
    }

    private suspend fun waitForMode(
        packageName: String,
        mode: Int,
        label: String,
        trace: StringBuilder,
        marker: String? = null
    ): TaskState? {
        repeat(12) { index ->
            delay(250L)
            val state = findTask(packageName, marker)
            traceState(label + " poll=" + (index + 1), state, trace)
            if (state.taskId >= 0 && state.stackId >= 0 && state.mode == mode) return state
        }
        return null
    }

    private fun findTask(packageName: String, marker: String? = null): TaskState {
        val result = adb.exec("dumpsys activity activities")
        if (!result.success) return TaskState()

        var stackId = -1
        var mode = -1
        var taskId = -1
        result.output.lineSequence().forEach { source ->
            val line = source.trim()
            extractStackId(line)?.let {
                stackId = it
                mode = extractMode(line)
                taskId = -1
            }
            val lineMode = extractMode(line)
            if (lineMode >= 0) mode = lineMode
            extractTaskId(line)?.let { taskId = it }
            val packageMatch = line.contains("A=" + packageName) || line.contains(packageName + "/")
            val markerMatch = marker == null || line.contains(marker)
            if (taskId >= 0 && packageMatch && markerMatch) return TaskState(taskId, stackId, mode)
        }
        return TaskState()
    }

    private fun findStackForMode(targetMode: Int): Int {
        val result = adb.exec("dumpsys activity activities")
        if (!result.success) return -1
        var stackId = -1
        result.output.lineSequence().forEach { source ->
            val line = source.trim()
            extractStackId(line)?.let { stackId = it }
            if (stackId >= 0 && extractMode(line) == targetMode) return stackId
        }
        return -1
    }

    private fun applyRatio(requested: Int): RatioResult {
        val result = adb.exec("am stack list")
        if (!result.success) return RatioResult(false, "stack list failed")

        var pending: Rect? = null
        var primary: Rect? = null
        var secondary: Rect? = null
        result.output.lineSequence().forEach { source ->
            val line = source.trim()
            if (line.startsWith("Stack id=")) pending = parseBounds(line)
            val bounds = pending
            if (bounds != null && "mActivityType=standard" in line) {
                if ("mWindowingMode=split-screen-primary" in line) primary = bounds
                if ("mWindowingMode=split-screen-secondary" in line) secondary = bounds
            }
        }

        val p = primary ?: return RatioResult(false, "primary bounds missing")
        val s = secondary ?: return RatioResult(false, "secondary bounds missing")
        val ratio = requested.coerceIn(20, 80)

        val target = if (p.left == s.left && p.right == s.right && p.bottom <= s.top) {
            val divider = s.top - p.bottom
            val usable = (s.bottom - p.top - divider).coerceAtLeast(1)
            Rect(p.left, p.top, p.right, p.top + (usable * ratio / 100f).roundToInt())
        } else if (p.top == s.top && p.bottom == s.bottom && p.right <= s.left) {
            val divider = s.left - p.right
            val usable = (s.right - p.left - divider).coerceAtLeast(1)
            Rect(p.left, p.top, p.left + (usable * ratio / 100f).roundToInt(), p.bottom)
        } else {
            return RatioResult(false, "unsupported bounds")
        }

        val command = "am stack resize-docked-stack " +
            target.left + " " + target.top + " " + target.right + " " + target.bottom + " " +
            target.left + " " + target.top + " " + target.right + " " + target.bottom
        val applied = adb.exec(command)
        return RatioResult(applied.success, "ratio=" + ratio + " exit=" + applied.exitCode)
    }

    private fun command(command: String, trace: StringBuilder): Boolean {
        val result = adb.exec(command)
        trace.append("cmd=").append(command)
            .append(" exit=").append(result.exitCode)
            .append(" out=").append(result.output.replace("\n", " ").take(260))
            .append("\n")
        return result.success
    }

    private fun extractStackId(line: String): Int? =
        Regex("Stack\\s+#(\\d+)", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("ActivityStack[^#]*#(\\d+)", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractTaskId(line: String): Int? {
        if (!line.contains("Task{") && !line.contains("TaskRecord{")) return null
        return Regex("#(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractMode(line: String): Int {
        val value = line.lowercase()
        return when {
            "split-screen-primary" in value ||
                "mwindowingmode=3" in value ||
                "windowingmode=3" in value -> PRIMARY_MODE
            "split-screen-secondary" in value ||
                "mwindowingmode=4" in value ||
                "windowingmode=4" in value -> SECONDARY_MODE
            else -> -1
        }
    }

    private fun parseBounds(line: String): Rect? {
        val match = Regex("bounds=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
            .find(line) ?: return null
        return Rect(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt()
        )
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun traceState(label: String, state: TaskState, trace: StringBuilder) {
        trace.append(label)
            .append(" task=").append(state.taskId)
            .append(" stack=").append(state.stackId)
            .append(" mode=").append(state.mode)
            .append("\n")
    }

    companion object {
        private const val PREF = "dolphin_next_split_v1"
        private const val PRIMARY_MODE = 3
        private const val SECONDARY_MODE = 4
    }
}
