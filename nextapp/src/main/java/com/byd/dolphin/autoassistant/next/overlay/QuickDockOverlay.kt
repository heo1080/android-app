package com.byd.dolphin.autoassistant.next.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.integrated.InstalledApps
import com.byd.dolphin.autoassistant.next.integrated.IntegratedSettings
import com.byd.dolphin.autoassistant.next.integrated.VehicleActionController
import com.byd.dolphin.autoassistant.next.vehicle.BydGateway
import kotlin.math.abs

object QuickDockOverlay {
    private const val MAX_VISIBLE_ITEMS = 8

    private val handler = Handler(Looper.getMainLooper())
    private var quickView: View? = null
    private var quickHide: Runnable? = null

    private var floatingRoot: LinearLayout? = null
    private var floatingScroller: HorizontalScrollView? = null
    private var floatingHandle: TextView? = null
    private var floatingReset: TextView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var floatingWindowManager: WindowManager? = null
    private var floatingCollapseRunnable: Runnable? = null
    private var floatingCollapsed = false
    private var defaultX = 0
    private var defaultY = 0

    fun canDraw(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun requestPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(
                    context,
                    "플로팅 권한 화면을 열 수 없습니다. 기존 오버레이 권한 상태를 확인하세요.",
                    Toast.LENGTH_LONG
                ).show()
            }
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
        val root = buildDockContent(app, compact = false, scale = 1f)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(app, 72),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(app, 76)
        }

        runCatching { wm.addView(root, params) }
            .onFailure {
                NextLogger.e("QUICK_DOCK", "show failed", it)
                return
            }

        quickView = root
        rescheduleQuickHide(app)
        NextLogger.i(
            "QUICK_DOCK",
            "shown timeout=" + IntegratedSettings.quickDockTimeoutSeconds(app) + "s"
        )
    }

    fun hideQuick(context: Context) {
        quickHide?.let(handler::removeCallbacks)
        quickHide = null
        val view = quickView ?: return
        val wm = context.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(view) }
        quickView = null
        NextLogger.i("QUICK_DOCK", "hidden")
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showFloating(context: Context) {
        val app = context.applicationContext
        if (!IntegratedSettings.floatingEnabled(app)) {
            hideFloating(app)
            return
        }
        if (!canDraw(app)) {
            requestPermission(app)
            return
        }

        hideFloating(app)

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingWindowManager = wm

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        val scale = IntegratedSettings
            .floatingScalePercent(app)
            .coerceIn(50, 150) / 100f

        defaultX =
            ((metrics.widthPixels - dp(app, (330 * scale).toInt()))
                .coerceAtLeast(0)) / 2
        defaultY =
            (metrics.heightPixels - dp(app, (130 * scale).toInt()))
                .coerceAtLeast(0)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = IntegratedSettings.floatingX(app, defaultX)
            y = IntegratedSettings.floatingY(app, defaultY)
            alpha = IntegratedSettings
                .floatingOpacityPercent(app)
                .coerceIn(30, 100) / 100f
        }

        val root = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(app, (5 * scale).toInt()),
                dp(app, (5 * scale).toInt()),
                dp(app, (5 * scale).toInt()),
                dp(app, (5 * scale).toInt())
            )
            background = dockBackground(app, scale)
        }

        val handle = TextView(app).apply {
            text = "⋮⋮"
            contentDescription = "플로팅 이동 / 최소화"
            gravity = Gravity.CENTER
            textSize = 17f * scale
            setTextColor(Color.rgb(75, 217, 255))
            layoutParams = LinearLayout.LayoutParams(
                dp(app, (38 * scale).toInt()),
                dp(app, (46 * scale).toInt())
            )
        }
        root.addView(handle)
        floatingHandle = handle

        val scroller = buildDockContent(app, compact = true, scale = scale)
        root.addView(scroller)
        floatingScroller = scroller

        val reset = TextView(app).apply {
            text = "⟲"
            contentDescription = "플로팅 위치 초기화"
            gravity = Gravity.CENTER
            textSize = 17f * scale
            setTextColor(Color.rgb(54, 215, 255))
            layoutParams = LinearLayout.LayoutParams(
                dp(app, (38 * scale).toInt()),
                dp(app, (46 * scale).toInt())
            )
            setOnClickListener { resetFloatingPosition(app) }
        }
        root.addView(reset)
        floatingReset = reset

        setupFloatingDrag(app, root, handle, metrics)

        runCatching { wm.addView(root, params) }
            .onFailure {
                NextLogger.e("FLOATING", "show failed", it)
                hideFloating(app)
                return
            }

        floatingRoot = root
        floatingParams = params
        floatingCollapsed = false
        scheduleFloatingCollapse(app)

        NextLogger.i(
            "FLOATING",
            "shown x=" + params.x +
                " y=" + params.y +
                " scale=" + IntegratedSettings.floatingScalePercent(app) +
                " opacity=" + IntegratedSettings.floatingOpacityPercent(app) +
                " collapse=" + IntegratedSettings.floatingCollapseDelaySeconds(app)
        )
    }

    fun hideFloating(context: Context) {
        floatingCollapseRunnable?.let(handler::removeCallbacks)
        floatingCollapseRunnable = null

        val view = floatingRoot
        val wm = floatingWindowManager
        if (view != null && wm != null) {
            runCatching { wm.removeView(view) }
        }

        floatingRoot = null
        floatingScroller = null
        floatingHandle = null
        floatingReset = null
        floatingParams = null
        floatingWindowManager = null
        floatingCollapsed = false
    }

    fun refreshFloating(context: Context) {
        if (IntegratedSettings.floatingEnabled(context)) {
            showFloating(context)
        } else {
            hideFloating(context)
        }
    }

    fun resetFloatingPosition(context: Context) {
        val params = floatingParams
        if (params != null) {
            params.x = defaultX
            params.y = defaultY
            floatingRoot?.let {
                runCatching {
                    floatingWindowManager?.updateViewLayout(it, params)
                }
            }
            IntegratedSettings.setFloatingPosition(
                context,
                defaultX,
                defaultY
            )
            scheduleFloatingCollapse(context)
            Toast.makeText(
                context,
                "플로팅 위치 초기화",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            IntegratedSettings.setFloatingPosition(
                context,
                defaultX,
                defaultY
            )
        }
    }

    private fun buildDockContent(
        context: Context,
        compact: Boolean,
        scale: Float
    ): HorizontalScrollView {
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            background = if (compact) null else dockBackground(context, scale)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(context, (6 * scale).toInt()),
                dp(context, (5 * scale).toInt()),
                dp(context, (6 * scale).toInt()),
                dp(context, (5 * scale).toInt())
            )
        }

        scroller.addView(row)

        addVehicleButtons(row, context, scale)

        val selected = if (compact) {
            IntegratedSettings.selectedFloatingApps(context)
        } else {
            IntegratedSettings.selectedDockApps(context)
        }

        val apps = InstalledApps.launcherApps(context)
            .filter { selected.contains(it.packageName) }

        apps.forEach { app ->
            val icon = ImageView(context).apply {
                setImageDrawable(app.icon)
                contentDescription = app.label
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    dp(context, (6 * scale).toInt()),
                    dp(context, (6 * scale).toInt()),
                    dp(context, (6 * scale).toInt()),
                    dp(context, (6 * scale).toInt())
                )
                background = itemBackground(context, scale)
                setOnClickListener {
                    floatingActivity(context)
                    context.packageManager
                        .getLaunchIntentForPackage(app.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let(context::startActivity)
                }
            }
            row.addView(
                icon,
                LinearLayout.LayoutParams(
                    dp(context, (48 * scale).toInt()),
                    dp(context, (48 * scale).toInt())
                ).apply {
                    marginEnd = dp(context, (4 * scale).toInt())
                }
            )
        }

        val totalItems = 12 + apps.size
        val visibleCount = totalItems.coerceIn(1, MAX_VISIBLE_ITEMS)
        scroller.layoutParams = LinearLayout.LayoutParams(
            dp(context, ((52 * visibleCount) * scale).toInt()),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        return scroller
    }

    private fun addVehicleButtons(
        row: LinearLayout,
        context: Context,
        scale: Float
    ) {
        addGlyph(row, context, "♨", "핸들 열선", scale) {
            val gateway = BydGateway(context)
            val on = gateway.readNumber(
                BydGateway.SETTING,
                "getSteeringWheelHeatingState"
            )?.toInt() == 2
            toastResult(
                context,
                VehicleActionController.setSteeringHeat(context, !on),
                "핸들 열선"
            )
        }

        addGlyph(row, context, "▤", "운전석 열선", scale) {
            val gateway = BydGateway(context)
            val raw = gateway.readIntFirst(
                BydGateway.SETTING,
                listOf("getSeatHeatingState", "getSeatHeatingState1"),
                1
            )
            val current = when (raw) {
                2 -> 1
                3 -> 2
                else -> 0
            }
            toastResult(
                context,
                VehicleActionController.setSeatHeat(
                    context,
                    1,
                    (current + 1) % 3
                ),
                "시트 열선"
            )
        }

        addGlyph(row, context, "◫", "앞유리 성에", scale) {
            toastResult(
                context,
                VehicleActionController.setDefrost(context, true, true),
                "앞유리 성에"
            )
        }

        addGlyph(row, context, "▧", "뒷유리 성에", scale) {
            toastResult(
                context,
                VehicleActionController.setDefrost(context, false, true),
                "뒷유리 성에"
            )
        }

        addGlyph(row, context, "−", "풍량 감소", scale) {
            val gateway = BydGateway(context)
            val current = gateway
                .readNumber(BydGateway.AC, "getAcWindLevel")
                ?.toInt() ?: 3
            toastResult(
                context,
                VehicleActionController.setFanLevel(context, current - 1),
                "풍량"
            )
        }

        addGlyph(row, context, "+", "풍량 증가", scale) {
            val gateway = BydGateway(context)
            val current = gateway
                .readNumber(BydGateway.AC, "getAcWindLevel")
                ?.toInt() ?: 3
            toastResult(
                context,
                VehicleActionController.setFanLevel(context, current + 1),
                "풍량"
            )
        }

        addGlyph(row, context, "−°", "온도 감소", scale) {
            val gateway = BydGateway(context)
            val current = gateway
                .readNumber(BydGateway.AC, "getAcTemperature", 1)
                ?.toInt() ?: 24
            toastResult(
                context,
                VehicleActionController.setTemperature(
                    context,
                    1,
                    current - 1
                ),
                "온도"
            )
        }

        addGlyph(row, context, "+°", "온도 증가", scale) {
            val gateway = BydGateway(context)
            val current = gateway
                .readNumber(BydGateway.AC, "getAcTemperature", 1)
                ?.toInt() ?: 24
            toastResult(
                context,
                VehicleActionController.setTemperature(
                    context,
                    1,
                    current + 1
                ),
                "온도"
            )
        }

        addGlyph(row, context, "AC", "공조 ON/OFF", scale) {
            val gateway = BydGateway(context)
            val on = gateway
                .readNumber(BydGateway.AC, "getAcStartState")
                ?.toInt() == 1
            toastResult(
                context,
                VehicleActionController.setAcPower(context, !on),
                "공조"
            )
        }

        addGlyph(row, context, "☼", "실내등 LAB", scale) {
            toastResult(
                context,
                VehicleActionController.toggleInsideLightLab(context, true),
                "실내등 LAB"
            )
        }

        addGlyph(row, context, "▱", "트렁크 LAB", scale) {
            val hits = VehicleActionController.discoverTrunk(context)
            Toast.makeText(
                context,
                "트렁크 후보 " + hits.size + "개 · 로그 저장",
                Toast.LENGTH_SHORT
            ).show()
        }

        addGlyph(row, context, "▰", "선쉐이드 LAB", scale) {
            val hits = VehicleActionController.discoverSunshade(context)
            Toast.makeText(
                context,
                "선쉐이드 후보 " + hits.size + "개 · 로그 저장",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun addGlyph(
        row: LinearLayout,
        context: Context,
        glyph: String,
        description: String,
        scale: Float,
        action: () -> Unit
    ) {
        val view = TextView(context).apply {
            text = glyph
            textSize = (if (glyph.length > 1) 13f else 21f) * scale
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = description
            background = itemBackground(context, scale)
            setOnClickListener {
                floatingActivity(context)
                action()
                if (quickView != null) rescheduleQuickHide(context)
            }
        }

        row.addView(
            view,
            LinearLayout.LayoutParams(
                dp(context, (48 * scale).toInt()),
                dp(context, (48 * scale).toInt())
            ).apply {
                marginEnd = dp(context, (4 * scale).toInt())
            }
        )
    }

    private fun rescheduleQuickHide(context: Context) {
        quickHide?.let(handler::removeCallbacks)
        val delay =
            IntegratedSettings.quickDockTimeoutSeconds(context) * 1000L
        quickHide = Runnable { hideQuick(context) }.also {
            handler.postDelayed(it, delay)
        }
    }

    private fun floatingActivity(context: Context) {
        if (floatingCollapsed) expandFloating(context)
        scheduleFloatingCollapse(context)
    }

    private fun scheduleFloatingCollapse(context: Context) {
        floatingCollapseRunnable?.let(handler::removeCallbacks)
        val delay =
            IntegratedSettings.floatingCollapseDelaySeconds(context) * 1000L
        floatingCollapseRunnable = Runnable {
            collapseFloating()
        }.also {
            handler.postDelayed(it, delay)
        }
    }

    private fun collapseFloating() {
        if (floatingCollapsed || floatingRoot == null) return
        floatingCollapsed = true
        floatingScroller?.visibility = View.GONE
        floatingReset?.visibility = View.GONE
        floatingHandle?.apply {
            text = "◆"
            contentDescription = "플로팅 펼치기"
        }
        floatingRoot?.let { root ->
            floatingParams?.let { params ->
                runCatching {
                    floatingWindowManager?.updateViewLayout(root, params)
                }
            }
        }
        NextLogger.i("FLOATING", "collapsed to handle")
    }

    private fun expandFloating(context: Context) {
        if (!floatingCollapsed) return
        floatingCollapsed = false
        floatingScroller?.visibility = View.VISIBLE
        floatingReset?.visibility = View.VISIBLE
        floatingHandle?.apply {
            text = "⋮⋮"
            contentDescription = "플로팅 이동 / 최소화"
        }
        floatingRoot?.let { root ->
            floatingParams?.let { params ->
                runCatching {
                    floatingWindowManager?.updateViewLayout(root, params)
                }
            }
        }
        scheduleFloatingCollapse(context)
        NextLogger.i("FLOATING", "expanded")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingDrag(
        context: Context,
        root: View,
        handle: TextView,
        metrics: DisplayMetrics
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moving = false

        handle.setOnTouchListener { view, event ->
            val params = floatingParams ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    floatingCollapseRunnable?.let(handler::removeCallbacks)
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moving = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moving = true

                    if (moving) {
                        params.x =
                            (initialX + dx).coerceIn(
                                0,
                                (metrics.widthPixels - dp(context, 36))
                                    .coerceAtLeast(0)
                            )
                        params.y =
                            (initialY + dy).coerceIn(
                                0,
                                (metrics.heightPixels - dp(context, 36))
                                    .coerceAtLeast(0)
                            )
                        runCatching {
                            floatingWindowManager?.updateViewLayout(
                                root,
                                params
                            )
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (moving) {
                        IntegratedSettings.setFloatingPosition(
                            context,
                            params.x,
                            params.y
                        )
                    } else if (floatingCollapsed) {
                        expandFloating(context)
                        view.performClick()
                    } else {
                        collapseFloating()
                        view.performClick()
                    }

                    scheduleFloatingCollapse(context)
                    true
                }

                else -> false
            }
        }
    }

    private fun toastResult(
        context: Context,
        result: VehicleActionController.ActionResult,
        title: String
    ) {
        Toast.makeText(
            context,
            title + " · " +
                if (result.accepted) "요청됨" else "실패/미확인",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun dockBackground(
        context: Context,
        scale: Float
    ) = GradientDrawable().apply {
        setColor(Color.argb(235, 8, 17, 24))
        cornerRadius = dp(context, (21 * scale).toInt()).toFloat()
        setStroke(
            dp(context, 1),
            Color.argb(150, 54, 215, 255)
        )
    }

    private fun itemBackground(
        context: Context,
        scale: Float
    ) = GradientDrawable().apply {
        setColor(Color.argb(225, 19, 34, 45))
        cornerRadius = dp(context, (13 * scale).toInt()).toFloat()
        setStroke(
            dp(context, 1),
            Color.argb(75, 125, 165, 188)
        )
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
