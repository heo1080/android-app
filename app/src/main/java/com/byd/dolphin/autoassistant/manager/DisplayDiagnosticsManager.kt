package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.io.File

/**
 * Display investigation for the 10-inch DiLink 3 unit.
 *
 * v30.6 deliberately separates logical resolution, density and fontScale. It
 * does not pretend to know the 12.8/15.6-inch stock profile yet; those profiles
 * will only be enabled after real firmware/display evidence is captured.
 */
object DisplayDiagnosticsManager {
    private const val TAG = "DISPLAY_LAB"

    data class Snapshot(
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val density: Float,
        val scaledDensity: Float,
        val fontScale: Float,
        val widthDp: Int,
        val heightDp: Int,
        val smallestWidthDp: Int,
        val rotation: Int,
        val modeSummary: String
    )

    fun snapshot(context: Context): Snapshot {
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display else @Suppress("DEPRECATION") wm.defaultDisplay
        val real = Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(real)
        val width = real.x.takeIf { it > 0 } ?: metrics.widthPixels
        val height = real.y.takeIf { it > 0 } ?: metrics.heightPixels
        val modes = display?.supportedModes?.joinToString(" | ") { mode ->
            "${mode.modeId}:${mode.physicalWidth}x${mode.physicalHeight}@${"%.1f".format(mode.refreshRate)}"
        }.orEmpty()
        return Snapshot(
            widthPx = width,
            heightPx = height,
            densityDpi = metrics.densityDpi,
            density = metrics.density,
            scaledDensity = metrics.scaledDensity,
            fontScale = config.fontScale,
            widthDp = config.screenWidthDp,
            heightDp = config.screenHeightDp,
            smallestWidthDp = config.smallestScreenWidthDp,
            rotation = display?.rotation ?: -1,
            modeSummary = modes
        )
    }

    fun summary(context: Context): String {
        val s = snapshot(context)
        return "${s.widthPx}x${s.heightPx}px · ${s.widthDp}x${s.heightDp}dp · sw${s.smallestWidthDp}dp · ${s.densityDpi}dpi · fontScale=${"%.2f".format(s.fontScale)}"
    }

    fun setFontScale(context: Context, scale: Float): Boolean {
        if (!scale.isFinite() || scale !in 0.85f..1.30f) return false
        val command = "settings put system font_scale ${"%.2f".format(java.util.Locale.US, scale)}"
        val result = NativeAdbClient.executeShell(context.applicationContext, command)
        DolphinLogger.i(TAG, "fontScale set=$scale success=${result.success} exit=${result.exitCode}")
        return result.success
    }

    fun resetFontScale(context: Context): Boolean = setFontScale(context, 1.0f)

    /** Manual research control only; no 12/15-inch guessed presets are exposed. */
    fun setLogicalSize(context: Context, widthPx: Int, heightPx: Int): Boolean {
        if (widthPx !in 800..4096 || heightPx !in 480..2160) return false
        val result = NativeAdbClient.executeShell(context.applicationContext, "wm size ${widthPx}x$heightPx")
        DolphinLogger.i(TAG, "logical size set=${widthPx}x$heightPx success=${result.success} exit=${result.exitCode}")
        return result.success
    }

    fun resetLogicalSize(context: Context): Boolean {
        val result = NativeAdbClient.executeShell(context.applicationContext, "wm size reset")
        DolphinLogger.i(TAG, "logical size reset success=${result.success} exit=${result.exitCode}")
        return result.success
    }

    fun writeSnapshot(context: Context, directory: File) {
        runCatching {
            val s = snapshot(context)
            File(directory, "display_api.txt").writeText(buildString {
                appendLine("widthPx=${s.widthPx}")
                appendLine("heightPx=${s.heightPx}")
                appendLine("densityDpi=${s.densityDpi}")
                appendLine("density=${s.density}")
                appendLine("scaledDensity=${s.scaledDensity}")
                appendLine("rotation=${s.rotation}")
                appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            })
            File(directory, "configuration.txt").writeText(buildString {
                appendLine("screenWidthDp=${s.widthDp}")
                appendLine("screenHeightDp=${s.heightDp}")
                appendLine("smallestScreenWidthDp=${s.smallestWidthDp}")
                appendLine("fontScale=${s.fontScale}")
                appendLine("orientation=${context.resources.configuration.orientation}")
                appendLine("uiMode=${context.resources.configuration.uiMode}")
            })
            File(directory, "display_modes.txt").writeText(s.modeSummary.ifBlank { "<none>" })
            val fontSetting = runCatching { Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE) }.getOrNull()
            File(directory, "font_scale.txt").writeText("configuration=${s.fontScale}\nsettingsSystem=$fontSetting\n")

            val commands = linkedMapOf(
                "wm_size" to "wm size",
                "wm_density" to "wm density",
                "font_scale_shell" to "settings get system font_scale",
                "display_size_forced" to "settings get global display_size_forced",
                "display_density_forced" to "settings get secure display_density_forced",
                "filtered_properties" to "getprop | grep -Ei 'display|screen|panel|lcd|dpi|density|resolution|inch|byd|dilink'"
            )
            val shellText = buildString {
                commands.forEach { (name, command) ->
                    val result = NativeAdbClient.executeShell(context.applicationContext, command)
                    appendLine("=== $name ===")
                    appendLine("command=$command")
                    appendLine("success=${result.success} exit=${result.exitCode}")
                    appendLine(result.output.trim())
                    appendLine()
                }
            }
            File(directory, "shell_probe.txt").writeText(shellText)
            File(directory, "display_compare.txt").writeText(
                "baseline=${summary(context)}\n" +
                    "12.8/15.6 stock profile=UNVERIFIED_DO_NOT_APPLY\n" +
                    "principle=logical size + density + fontScale must be evaluated separately\n"
            )
            DolphinLogger.i(TAG, "display snapshot written ${summary(context)}")
        }.onFailure { DolphinLogger.e(TAG, "display snapshot failed", it) }
    }
}
