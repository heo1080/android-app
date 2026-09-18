package com.byd.dolphin.autoassistant.next.cluster

import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.byd.dolphin.autoassistant.next.NextRuntime
import com.byd.dolphin.autoassistant.next.core.BydPermissionContext
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.hud.NextHudBridge
import com.byd.dolphin.autoassistant.next.system.NextAdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ClusterDisplayInfo(
    val id: Int,
    val name: String,
    val state: Int,
    val flags: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    val isDefault: Boolean
)

data class ClusterCapabilityReport(
    val displays: List<ClusterDisplayInfo>,
    val instrumentMethods: List<String>,
    val surfaceMethods: List<String>,
    val shellSummary: String,
    val themeCandidates: List<String>
)

data class ClusterActionResult(
    val success: Boolean,
    val detail: String
)

object ClusterHubLab {
    suspend fun scan(context: Context): ClusterCapabilityReport = withContext(Dispatchers.IO) {
        val displays = scanDisplays(context)
        val instrument = scanInstrumentMethods(context)
        val surface = scanSurfaceControl()
        val shell = scanShell(context)
        val themes = scanThemeCandidates(context)

        NextLogger.i(
            "CLUSTER_SCAN",
            "displays=" + displays.joinToString { it.id.toString() + ":" + it.name + " " + it.width + "x" + it.height } +
                " instrument=" + instrument.joinToString("|") +
                " surface=" + surface.joinToString("|") +
                " themes=" + themes.joinToString("|")
        )

        ClusterCapabilityReport(displays, instrument, surface, shell, themes)
    }

    fun nativeTbtCapability(context: Context): String =
        NextHudBridge.clusterCapability(context)

    fun sendNativeTbtTest(context: Context): ClusterActionResult {
        val detail = NextHudBridge.sendClusterTest(context)
        return ClusterActionResult(detail.first, detail.second)
    }

    suspend fun projectInstalledApp(
        context: Context,
        packageName: String
    ): ClusterActionResult = withContext(Dispatchers.IO) {
        if (!safeToProject()) {
            return@withContext ClusterActionResult(
                false,
                "안전 차단: P 또는 정차 상태에서만 계기판 투사 LAB 실행 가능"
            )
        }

        val display = bestClusterDisplay(context)
            ?: return@withContext ClusterActionResult(
                false,
                "보조/계기판 Display ID 미발견 · DISPLAY/SURFACE SCAN 로그 확인"
            )

        val launch = context.packageManager.getLaunchIntentForPackage(packageName)?.component
            ?: return@withContext ClusterActionResult(false, "앱 실행 컴포넌트 없음: " + packageName)

        if (!NextAdb.isPortOpen()) {
            return@withContext ClusterActionResult(false, "LOCAL ADB unavailable")
        }

        val cmd = "am start --user 0 --display " + display.id +
            " -n '" + launch.flattenToShortString().replace("'", "'\\''") + "'"
        val result = NextAdb.shell(context, cmd)
        val verify = NextAdb.shell(context, "dumpsys activity activities")
        val hit = verify.output.contains(packageName) &&
            (
                verify.output.contains("displayId=" + display.id) ||
                    verify.output.contains("mDisplayId=" + display.id) ||
                    verify.output.contains("display=" + display.id)
            )

        val detail = "display=" + display.id + " " + display.name +
            " request=" + result.success + " verify=" + hit +
            " · " + result.output.replace("\n", " ").take(220)
        NextLogger.i("CLUSTER_PROJECTION", packageName + " " + detail)
        ClusterActionResult(hit, detail)
    }

    suspend fun launchCustomClusterUi(context: Context): ClusterActionResult =
        withContext(Dispatchers.IO) {
            if (!safeToProject()) {
                return@withContext ClusterActionResult(
                    false,
                    "안전 차단: P 또는 정차 상태에서만 Custom Cluster UI LAB 가능"
                )
            }

            val display = bestClusterDisplay(context)
                ?: return@withContext ClusterActionResult(false, "보조/계기판 Display ID 미발견")
            if (!NextAdb.isPortOpen()) {
                return@withContext ClusterActionResult(false, "LOCAL ADB unavailable")
            }

            val component = ComponentName(
                context.packageName,
                "com.byd.dolphin.autoassistant.next.cluster.ClusterProjectionActivity"
            )
            val r = NextAdb.shell(
                context,
                "am start --user 0 --display " + display.id +
                    " -n '" + component.flattenToShortString() + "'"
            )
            val verify = NextAdb.shell(context, "dumpsys activity activities")
            val hit = verify.output.contains("ClusterProjectionActivity") &&
                (
                    verify.output.contains("displayId=" + display.id) ||
                        verify.output.contains("mDisplayId=" + display.id) ||
                        verify.output.contains("display=" + display.id)
                )
            val detail = "customUI display=" + display.id +
                " request=" + r.success + " verify=" + hit
            NextLogger.i("CLUSTER_CUSTOM_UI", detail)
            ClusterActionResult(hit, detail)
        }

    suspend fun scanThemeCandidates(context: Context): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()

        runCatching {
            val clazz = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
            clazz.methods
                .filter {
                    listOf("theme", "skin", "style", "ui", "display", "cluster")
                        .any { key -> it.name.contains(key, true) }
                }
                .mapTo(result) {
                    "API " + it.name + "(" +
                        it.parameterTypes.joinToString { p -> p.simpleName } + "):" +
                        it.returnType.simpleName
                }
        }

        if (NextAdb.isPortOpen()) {
            val shell = NextAdb.shell(
                context,
                "sh -c \"find /system /vendor /product /data -maxdepth 6 -type f " +
                    "\\( -iname '*cluster*' -o -iname '*theme*.rcc' -o -iname '*.rcc' \\) " +
                    "2>/dev/null | head -n 180\""
            )
            shell.output.lineSequence()
                .filter { it.isNotBlank() }
                .mapTo(result) { "FILE " + it.trim() }
        }

        val unique = result.distinct().take(220)
        NextLogger.i("CLUSTER_THEME_SCAN", unique.joinToString(" | "))
        unique
    }

    suspend fun scanProjectionInternals(context: Context): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()

        runCatching {
            val clazz = Class.forName("android.view.SurfaceControl")
            clazz.declaredMethods
                .filter {
                    listOf("mirror", "capture", "display", "surface")
                        .any { key -> it.name.contains(key, true) }
                }
                .take(100)
                .forEach {
                    lines += "SurfaceControl." + it.name + "(" +
                        it.parameterTypes.joinToString { p -> p.simpleName } + ")"
                }
        }

        if (NextAdb.isPortOpen()) {
            listOf(
                "dumpsys display",
                "dumpsys SurfaceFlinger --list",
                "pm list packages | grep -Ei 'cluster|instrument|amap|autonavi|fission|autocontainer|cbox'"
            ).forEach { command ->
                val escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
                val r = NextAdb.shell(context, "sh -c \"" + escaped + "\"")
                lines += "### " + command
                lines += r.output.lineSequence().take(140).toList()
            }
        }

        val text = lines.joinToString("\n").take(24000)
        NextLogger.i("CLUSTER_SURFACE_SCAN", text)
        text
    }

    fun restoreStock(context: Context): ClusterActionResult {
        NextHudBridge.clearCluster(context)
        val detail = "Native TBT clear 요청 · DolphinAssistant는 테마 파일/순정 안전 레이어를 영구 수정하지 않음"
        NextLogger.i("CLUSTER_RESTORE", detail)
        return ClusterActionResult(true, detail)
    }

    private fun scanDisplays(context: Context): List<ClusterDisplayInfo> {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return manager.displays.map { d ->
            val mode = d.mode
            ClusterDisplayInfo(
                id = d.displayId,
                name = d.name,
                state = d.state,
                flags = d.flags,
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                refreshRate = mode.refreshRate,
                isDefault = d.displayId == Display.DEFAULT_DISPLAY
            )
        }
    }

    private fun bestClusterDisplay(context: Context): ClusterDisplayInfo? {
        val displays = scanDisplays(context).filter { !it.isDefault }
        return displays.firstOrNull {
            listOf("cluster", "instrument", "virtual", "auto", "presentation")
                .any { key -> it.name.contains(key, true) }
        } ?: displays.firstOrNull()
    }

    private fun scanInstrumentMethods(context: Context): List<String> = runCatching {
        val clazz = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        val target = clazz.getMethod("getInstance", Context::class.java)
            .invoke(null, BydPermissionContext.wrap(context))
        val source = target?.javaClass ?: clazz
        source.methods
            .filter {
                listOf(
                    "Navi", "Guidance", "Path", "Camera", "Safe", "Address",
                    "Theme", "Display", "Cluster", "Screen", "Ui"
                ).any { key -> it.name.contains(key, true) }
            }
            .map {
                it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + "):" +
                    it.returnType.simpleName
            }
            .distinct()
            .sorted()
    }.getOrElse { listOf("UNAVAILABLE " + (it.cause ?: it).message) }

    private fun scanSurfaceControl(): List<String> = runCatching {
        val clazz = Class.forName("android.view.SurfaceControl")
        clazz.declaredMethods
            .filter {
                listOf("mirror", "capture", "display")
                    .any { key -> it.name.contains(key, true) }
            }
            .map {
                it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")"
            }
            .distinct()
            .sorted()
            .take(100)
    }.getOrDefault(emptyList())

    private fun scanShell(context: Context): String {
        if (!NextAdb.isPortOpen()) return "LOCAL ADB unavailable"
        return NextAdb.shell(
            context,
            "sh -c \"dumpsys display | head -n 240; echo ---SF---; dumpsys SurfaceFlinger --list | head -n 200\""
        ).output.take(20000)
    }

    private fun safeToProject(): Boolean {
        if (!NextRuntime.isStarted()) return false
        val state = NextRuntime.repository.state.value
        val gear = state.gear.value
        val speed = state.speedKph.value
        return gear == "P" || (speed != null && speed <= 0.5)
    }
}
