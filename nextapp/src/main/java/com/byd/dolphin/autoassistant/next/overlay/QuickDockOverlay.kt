package com.byd.dolphin.autoassistant.next.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.byd.dolphin.autoassistant.next.integrated.InstalledApps
import com.byd.dolphin.autoassistant.next.integrated.IntegratedSettings
import com.byd.dolphin.autoassistant.next.integrated.VehicleActionController
import com.byd.dolphin.autoassistant.next.core.NextLogger

object QuickDockOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private var quickView: View? = null
    private var floatingView: View? = null
    private var quickHide: Runnable? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun showQuickDock(context: Context) {
        val app = context.applicationContext
        if (!canDraw(app)) {
            Toast.makeText(app, "플로팅 표시 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            requestPermission(app)
            return
        }
        hideQuick(app)
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = buildDock(app, compact = false)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(app, 72),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(app, 76)
        }
        wm.addView(root, params)
        quickView = root
        val delay = IntegratedSettings.quickDockTimeoutSeconds(app) * 1000L
        quickHide = Runnable { hideQuick(app) }.also { handler.postDelayed(it, delay) }
        NextLogger.i("QUICK_DOCK", "shown timeoutMs=" + delay)
    }

    fun hideQuick(context: Context) {
        quickHide?.let(handler::removeCallbacks)
        quickHide = null
        val view = quickView ?: return
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(view) }
        quickView = null
        NextLogger.i("QUICK_DOCK", "hidden")
    }

    fun showFloating(context: Context) {
        val app = context.applicationContext
        if (!canDraw(app)) {
            requestPermission(app)
            return
        }
        hideFloating(app)
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = buildDock(app, compact = true)
        val params = WindowManager.LayoutParams(
            dp(app, 430),
            dp(app, 64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(app, 12)
            y = dp(app, 92)
        }
        wm.addView(root, params)
        floatingView = root
        NextLogger.i("FLOATING", "icon-only floating bar shown")
    }

    fun hideFloating(context: Context) {
        val view = floatingView ?: return
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(view) }
        floatingView = null
    }

    fun refreshFloating(context: Context) {
        if (IntegratedSettings.floatingEnabled(context)) showFloating(context)
        else hideFloating(context)
    }

    private fun buildDock(context: Context, compact: Boolean): View {
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            background = dockBackground(context)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
        }
        scroller.addView(row)

        addGlyph(row, context, "♨", "핸들 열선") {
            val on = com.byd.dolphin.autoassistant.next.vehicle.BydGateway(context)
                .readNumber(com.byd.dolphin.autoassistant.next.vehicle.BydGateway.SETTING, "getSteeringWheelHeatingState")
                ?.toInt() == 2
            toastResult(context, VehicleActionController.setSteeringHeat(context, !on), "핸들 열선")
        }
        addGlyph(row, context, "▤", "운전석 열선") {
            val raw = com.byd.dolphin.autoassistant.next.vehicle.BydGateway(context)
                .readIntFirst(
                    com.byd.dolphin.autoassistant.next.vehicle.BydGateway.SETTING,
                    listOf("getSeatHeatingState", "getSeatHeatingState1"), 1
                )
            val current = when (raw) { 2 -> 1; 3 -> 2; else -> 0 }
            toastResult(context, VehicleActionController.setSeatHeat(context, 1, (current + 1) % 3), "시트 열선")
        }
        addGlyph(row, context, "◫", "앞유리 성에제거") {
            toastResult(context, VehicleActionController.setDefrost(context, true, true), "앞유리 성에")
        }
        addGlyph(row, context, "▧", "뒷유리 성에제거") {
            toastResult(context, VehicleActionController.setDefrost(context, false, true), "뒷유리 성에")
        }
        addGlyph(row, context, "−", "바람 약하게") {
            val gateway = com.byd.dolphin.autoassistant.next.vehicle.BydGateway(context)
            val current = gateway.readNumber(com.byd.dolphin.autoassistant.next.vehicle.BydGateway.AC, "getAcWindLevel")?.toInt() ?: 3
            toastResult(context, VehicleActionController.setFanLevel(context, current - 1), "풍량")
        }
        addGlyph(row, context, "+", "바람 강하게") {
            val gateway = com.byd.dolphin.autoassistant.next.vehicle.BydGateway(context)
            val current = gateway.readNumber(com.byd.dolphin.autoassistant.next.vehicle.BydGateway.AC, "getAcWindLevel")?.toInt() ?: 3
            toastResult(context, VehicleActionController.setFanLevel(context, current + 1), "풍량")
        }
        addGlyph(row, context, "−°", "온도 낮춤") {
            val gateway = com.byd.dolphin.autoassistant.next.vehicle.BydGateway(context)
            val current = gateway.readNumber(com.byd.dolphin.autoassistant.next.vehicle.BydGateway.AC, "getAcTemperature", 1)?.toInt() ?: 24
            toastResult(context, VehicleActionController.setTemperature(context, 1, current - 1), "온도")
        }
        addGlyph(row, context, "+°", "온도 높임") {
            val gateway = com.byd.dolphin.autoassistant.next.vehicle.BydGateway(context)
            val current = gateway.readNumber(com.byd.dolphin.autoassistant.next.vehicle.BydGateway.AC, "getAcTemperature", 1)?.toInt() ?: 24
            toastResult(context, VehicleActionController.setTemperature(context, 1, current + 1), "온도")
        }
        addGlyph(row, context, "AC", "에어컨/히터") {
            val gateway = com.byd.dolphin.autoassistant.next.vehicle.BydGateway(context)
            val on = gateway.readNumber(com.byd.dolphin.autoassistant.next.vehicle.BydGateway.AC, "getAcStartState")?.toInt() == 1
            toastResult(context, VehicleActionController.setAcPower(context, !on), "공조")
        }
        addGlyph(row, context, "☼", "실내등 전체") {
            toastResult(context, VehicleActionController.toggleInsideLightLab(context, true), "실내등 LAB")
        }
        addGlyph(row, context, "▱", "트렁크") {
            val hits = VehicleActionController.discoverTrunk(context)
            Toast.makeText(context, if (hits.isEmpty()) "트렁크 setter 미발견 · 로그 저장" else "후보 " + hits.size + "개 · 로그 저장", Toast.LENGTH_SHORT).show()
        }
        addGlyph(row, context, "▰", "선쉐이드") {
            val hits = VehicleActionController.discoverSunshade(context)
            Toast.makeText(context, if (hits.isEmpty()) "선쉐이드 setter 미발견 · 로그 저장" else "후보 " + hits.size + "개 · 로그 저장", Toast.LENGTH_SHORT).show()
        }

        val selected = if (compact) IntegratedSettings.selectedFloatingApps(context)
        else IntegratedSettings.selectedDockApps(context)
        val apps = InstalledApps.launcherApps(context)
            .filter { selected.contains(it.packageName) }
        apps.forEach { app ->
            val icon = ImageView(context).apply {
                setImageDrawable(app.icon)
                contentDescription = app.label
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(context, 9), dp(context, 9), dp(context, 9), dp(context, 9))
                background = itemBackground(context)
                setOnClickListener {
                    context.packageManager.getLaunchIntentForPackage(app.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let(context::startActivity)
                }
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(context, 54), dp(context, 54)).apply {
                marginEnd = dp(context, 6)
            })
        }
        return scroller
    }

    private fun addGlyph(
        row: LinearLayout,
        context: Context,
        glyph: String,
        description: String,
        action: () -> Unit
    ) {
        val view = TextView(context).apply {
            text = glyph
            textSize = if (glyph.length > 1) 15f else 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = description
            background = itemBackground(context)
            setOnClickListener {
                action()
                quickView?.let {
                    quickHide?.let(handler::removeCallbacks)
                    val delay = IntegratedSettings.quickDockTimeoutSeconds(context) * 1000L
                    quickHide = Runnable { hideQuick(context) }.also { r -> handler.postDelayed(r, delay) }
                }
            }
        }
        row.addView(view, LinearLayout.LayoutParams(dp(context, 54), dp(context, 54)).apply {
            marginEnd = dp(context, 6)
        })
    }

    private fun toastResult(
        context: Context,
        result: VehicleActionController.ActionResult,
        title: String
    ) {
        Toast.makeText(
            context,
            title + " · " + if (result.accepted) "요청됨" else "실패/미확인",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dockBackground(context: Context) = GradientDrawable().apply {
        setColor(Color.argb(245, 10, 18, 25))
        cornerRadius = dp(context, 22).toFloat()
        setStroke(dp(context, 1), Color.argb(160, 57, 207, 245))
    }

    private fun itemBackground(context: Context) = GradientDrawable().apply {
        setColor(Color.argb(230, 20, 34, 45))
        cornerRadius = dp(context, 15).toFloat()
        setStroke(dp(context, 1), Color.argb(80, 110, 150, 175))
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
