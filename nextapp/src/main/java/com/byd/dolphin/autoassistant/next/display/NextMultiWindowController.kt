package com.byd.dolphin.autoassistant.next.display

import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.system.NextAdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WindowLayoutMode { TWO, THREE, FOUR, POPUP }

data class WindowLaunchResult(
    val success: Boolean,
    val mode: WindowLayoutMode,
    val detail: String
)

object NextMultiWindowController {
    private const val MODE_PRIMARY = 3
    private const val MODE_SECONDARY = 4
    private const val MODE_FREEFORM = 5

    suspend fun launch(
        context: Context,
        packages: List<String>,
        mode: WindowLayoutMode,
        primaryRatio: Int = 50
    ): WindowLaunchResult = withContext(Dispatchers.IO) {
        val clean = packages.filter { it.isNotBlank() }.distinct()
        val required = when (mode) {
            WindowLayoutMode.TWO -> 2
            WindowLayoutMode.THREE -> 3
            WindowLayoutMode.FOUR -> 4
            WindowLayoutMode.POPUP -> 1
        }
        if (clean.size < required) {
            return@withContext WindowLaunchResult(false, mode, "앱 " + required + "개가 필요합니다.")
        }
        if (!NextAdb.isPortOpen()) {
            return@withContext WindowLaunchResult(false, mode, "LOCAL ADB 127.0.0.1:5555 unavailable")
        }
        val result = when (mode) {
            WindowLayoutMode.TWO -> VerifiedTwoPaneController.launch(
                context,
                clean[0],
                clean[1],
                primaryRatio
            )
            WindowLayoutMode.THREE -> launchFreeformGrid(context, clean.take(3), 3)
            WindowLayoutMode.FOUR -> launchFreeformGrid(context, clean.take(4), 4)
            WindowLayoutMode.POPUP -> launchPopup(context, clean[0])
        }
        NextLogger.i("MULTIWINDOW", mode.name + " success=" + result.success + " " + result.detail)
        result
    }

    private fun launchTwo(
        context: Context,
        first: String,
        second: String,
        primaryRatio: Int
    ): WindowLaunchResult {
        val firstCmp = launchComponent(context, first)
            ?: return WindowLaunchResult(false, WindowLayoutMode.TWO, "첫 앱 실행 컴포넌트 없음: " + first)
        val secondCmp = launchComponent(context, second)
            ?: return WindowLaunchResult(false, WindowLayoutMode.TWO, "둘째 앱 실행 컴포넌트 없음: " + second)

        val a = NextAdb.shell(
            context,
            "am start --user 0 --windowingMode " + MODE_PRIMARY + " -n " + quote(firstCmp.flattenToShortString())
        )
        if (!a.success) return WindowLaunchResult(false, WindowLayoutMode.TWO, "primary 실패: " + a.message)

        val b = NextAdb.shell(
            context,
            "am start --user 0 --windowingMode " + MODE_SECONDARY + " -n " + quote(secondCmp.flattenToShortString())
        )
        if (!b.success) return WindowLaunchResult(false, WindowLayoutMode.TWO, "secondary 실패: " + b.message)

        val ratio = primaryRatio.coerceIn(20, 80)
        val stack = NextAdb.shell(context, "am stack list")
        if (!stack.success) {
            return WindowLaunchResult(true, WindowLayoutMode.TWO, "2분할 실행됨 · 비율 진단 실패")
        }
        val bounds = parseSplitBounds(stack.output)
        if (bounds != null) {
            val primary = bounds.first
            val secondary = bounds.second
            val target = if (primary.width() >= primary.height()) {
                val total = maxOf(primary.right, secondary.right) - minOf(primary.left, secondary.left)
                val x = minOf(primary.left, secondary.left) + (total * ratio / 100f).toInt()
                Rect(minOf(primary.left, secondary.left), primary.top, x, primary.bottom)
            } else {
                val total = maxOf(primary.bottom, secondary.bottom) - minOf(primary.top, secondary.top)
                val y = minOf(primary.top, secondary.top) + (total * ratio / 100f).toInt()
                Rect(primary.left, minOf(primary.top, secondary.top), primary.right, y)
            }
            NextAdb.shell(
                context,
                "am stack resize-docked-stack " +
                    target.left + " " + target.top + " " + target.right + " " + target.bottom + " " +
                    target.left + " " + target.top + " " + target.right + " " + target.bottom
            )
        }
        return WindowLaunchResult(true, WindowLayoutMode.TWO, "2분할 " + ratio + ":" + (100-ratio) + " 요청 완료")
    }

    private fun launchFreeformGrid(
        context: Context,
        packages: List<String>,
        count: Int
    ): WindowLaunchResult {
        val dm: DisplayMetrics = context.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val topInset = (height * 0.07f).toInt()
        val bottomInset = (height * 0.12f).toInt()
        val usableH = (height - topInset - bottomInset).coerceAtLeast(400)

        val rects = if (count == 3) {
            listOf(
                Rect(0, topInset, width / 2, topInset + usableH),
                Rect(width / 2, topInset, width, topInset + usableH / 2),
                Rect(width / 2, topInset + usableH / 2, width, topInset + usableH)
            )
        } else {
            listOf(
                Rect(0, topInset, width / 2, topInset + usableH / 2),
                Rect(width / 2, topInset, width, topInset + usableH / 2),
                Rect(0, topInset + usableH / 2, width / 2, topInset + usableH),
                Rect(width / 2, topInset + usableH / 2, width, topInset + usableH)
            )
        }

        val details = mutableListOf<String>()
        var all = true
        packages.zip(rects).forEachIndexed { index, pair ->
            val pkg = pair.first
            val rect = pair.second
            val cmp = launchComponent(context, pkg)
            if (cmp == null) {
                all = false
                details += "slot" + (index + 1) + ": no component"
            } else {
                val cmd =
                    "am start --user 0 --windowingMode " + MODE_FREEFORM +
                    " --bounds " + rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom +
                    " -n " + quote(cmp.flattenToShortString())
                val r = NextAdb.shell(context, cmd)
                all = all && r.success
                details += "slot" + (index + 1) + ":" + (if (r.success) "OK" else "FAIL")
            }
        }
        val verify = NextAdb.shell(context, "dumpsys activity activities")
        val freeformHits = Regex("windowingMode=5|mWindowingMode=5|freeform", RegexOption.IGNORE_CASE)
            .findAll(verify.output).count()
        val verified = all && freeformHits >= count
        val status = if (verified) "FREEFORM VERIFIED IN RUNTIME"
        else "LAB request sent; runtime freeformHits=" + freeformHits
        return WindowLaunchResult(
            verified,
            if (count == 3) WindowLayoutMode.THREE else WindowLayoutMode.FOUR,
            details.joinToString(" · ") + " · " + status
        )
    }

    private fun launchPopup(context: Context, pkg: String): WindowLaunchResult {
        val cmp = launchComponent(context, pkg)
            ?: return WindowLaunchResult(false, WindowLayoutMode.POPUP, "실행 컴포넌트 없음")
        val dm = context.resources.displayMetrics
        val w = (dm.widthPixels * 0.58f).toInt()
        val h = (dm.heightPixels * 0.62f).toInt()
        val left = (dm.widthPixels - w) / 2
        val top = (dm.heightPixels - h) / 2
        val r = NextAdb.shell(
            context,
            "am start --user 0 --windowingMode " + MODE_FREEFORM +
                " --bounds " + left + "," + top + "," + (left+w) + "," + (top+h) +
                " -n " + quote(cmp.flattenToShortString())
        )
        val verify = NextAdb.shell(context, "dumpsys activity activities")
        val hit = verify.output.contains(pkg) &&
            Regex("windowingMode=5|mWindowingMode=5|freeform", RegexOption.IGNORE_CASE)
                .containsMatchIn(verify.output)
        return WindowLaunchResult(
            hit,
            WindowLayoutMode.POPUP,
            if (hit) "팝업 windowingMode=5 확인"
            else "LAB 요청 결과=" + r.success + "; freeform 미확인"
        )
    }

    private fun launchComponent(context: Context, packageName: String): ComponentName? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.component

    private fun parseSplitBounds(text: String): Pair<Rect, Rect>? {
        val rects = mutableListOf<Rect>()
        text.lineSequence().forEach { line ->
            if (!line.contains("split-screen", true)) return@forEach
            val m = Regex("bounds=\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").find(line)
                ?: return@forEach
            rects += Rect(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
                m.groupValues[4].toInt()
            )
        }
        return if (rects.size >= 2) rects[0] to rects[1] else null
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
