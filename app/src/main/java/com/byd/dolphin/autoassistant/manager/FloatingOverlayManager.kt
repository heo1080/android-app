package com.byd.dolphin.autoassistant.manager

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.byd.dolphin.autoassistant.floating.FloatingItem
import com.byd.dolphin.autoassistant.floating.FloatingItemManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Movable, horizontally scrollable, idle-collapsing vehicle dock. */
object FloatingOverlayManager {
    private const val TAG = "FLOATING_DOCK"
    private const val MAX_VISIBLE_ITEMS = 8

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var stripScroller: HorizontalScrollView? = null
    private var resetView: View? = null
    private var handleView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var defaultX = 0
    private var defaultY = 0
    private var collapsed = false

    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collapseRunnable: Runnable? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        val appContext = context.applicationContext
        if (!SettingsManager.isFloatingOverlayEnabled(appContext)) {
            hide()
            return
        }
        if (overlayView != null) {
            refresh(appContext)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            DolphinLogger.w(TAG, "SYSTEM_ALERT_WINDOW 권한 없음")
            return
        }

        try {
            windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getMetrics(metrics)
            defaultX = (metrics.widthPixels - dpToPx(appContext, 300)).coerceAtLeast(0) / 2
            defaultY = (metrics.heightPixels - dpToPx(appContext, 110)).coerceAtLeast(0)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = SettingsManager.getFloatingX(appContext, defaultX)
                y = SettingsManager.getFloatingY(appContext, defaultY)
                alpha = SettingsManager.getFloatingOpacity(appContext).coerceIn(30, 100) / 100f
            }

            val root = createOverlay(appContext)
            setupDragListener(appContext, root, metrics)
            windowManager?.addView(root, layoutParams)
            overlayView = root
            scheduleCollapse(appContext)
            DolphinLogger.i(TAG, "표시 완료 x=${layoutParams?.x} y=${layoutParams?.y} items=${FloatingItemManager.getSelectedIds(appContext).size}")
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "플로팅 독 표시 실패", e)
            hide()
        }
    }

    fun hide() {
        collapseRunnable?.let(handler::removeCallbacks)
        collapseRunnable = null
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                DolphinLogger.w(TAG, "플로팅 독 제거 실패: ${e.message}")
            }
        }
        overlayView = null
        stripScroller = null
        resetView = null
        handleView = null
        layoutParams = null
        windowManager = null
        collapsed = false
    }

    fun refresh(context: Context) {
        hide()
        show(context.applicationContext)
    }

    fun resetToDefaultPosition(context: Context) {
        val params = layoutParams ?: return
        params.x = defaultX
        params.y = defaultY
        overlayView?.let { windowManager?.updateViewLayout(it, params) }
        SettingsManager.setFloatingX(context, defaultX)
        SettingsManager.setFloatingY(context, defaultY)
        touchActivity(context)
        Toast.makeText(context, "플로팅 독 위치를 초기화했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun createOverlay(context: Context): LinearLayout {
        val scale = SettingsManager.getFloatingScale(context).coerceIn(50, 150) / 100f
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(context, (5 * scale).toInt()), dpToPx(context, (5 * scale).toInt()), dpToPx(context, (5 * scale).toInt()), dpToPx(context, (5 * scale).toInt()))
            background = dockBackground(context, scale)
            setOnTouchListener { _, _ ->
                touchActivity(context)
                false
            }
        }

        val handle = TextView(context).apply {
            text = "⋮⋮"
            contentDescription = "플로팅 독 이동 손잡이"
            gravity = Gravity.CENTER
            textSize = 17f * scale
            setTextColor(Color.parseColor("#80D8FF"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(context, (36 * scale).toInt()), dpToPx(context, (42 * scale).toInt()))
        }
        root.addView(handle)
        handleView = handle

        val allItems = FloatingItemManager.getAllAvailableItems(context).associateBy { it.id }
        val items = FloatingItemManager.getSelectedIds(context).mapNotNull(allItems::get)
        val buttonCellDp = (48 * scale).toInt().coerceAtLeast(28)
        val visibleCount = items.size.coerceIn(1, MAX_VISIBLE_ITEMS)
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        items.forEach { item -> strip.addView(createItemView(context, item, scale)) }
        if (items.isEmpty()) {
            strip.addView(TextView(context).apply {
                text = "버튼 추가"
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
                setPadding(dpToPx(context, 10), 0, dpToPx(context, 10), 0)
            })
        }

        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(strip)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(context, buttonCellDp * visibleCount),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(scroller)
        stripScroller = scroller

        val reset = TextView(context).apply {
            text = "⟲"
            contentDescription = "플로팅 독 위치 초기화"
            gravity = Gravity.CENTER
            textSize = 17f * scale
            setTextColor(Color.parseColor("#00E5FF"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(context, (36 * scale).toInt()), dpToPx(context, (42 * scale).toInt()))
            setOnClickListener { resetToDefaultPosition(context) }
        }
        root.addView(reset)
        resetView = reset
        return root
    }

    private fun createItemView(context: Context, item: FloatingItem, scale: Float): View {
        val size = dpToPx(context, (42 * scale).toInt().coerceAtLeast(26))
        val margins = dpToPx(context, (3 * scale).toInt().coerceAtLeast(1))
        val params = LinearLayout.LayoutParams(size, size).apply { setMargins(margins, 0, margins, 0) }

        val view: View = if (item.isApp) {
            ImageView(context).apply {
                layoutParams = params
                contentDescription = item.title
                setImageDrawable(runCatching { context.packageManager.getApplicationIcon(item.packageName) }.getOrNull())
                background = itemBackground(context, Color.parseColor("#252538"), scale)
                val inset = dpToPx(context, (4 * scale).toInt())
                setPadding(inset, inset, inset, inset)
                setOnClickListener {
                    touchActivity(context)
                    launchApp(context, item)
                }
            }
        } else {
            TextView(context).apply {
                layoutParams = params
                contentDescription = item.title
                text = displaySymbol(item.id)
                gravity = Gravity.CENTER
                textSize = 13f * scale
                setTextColor(Color.WHITE)
                background = itemBackground(context, itemColor(context, item.id), scale)
                setOnClickListener {
                    touchActivity(context)
                    executeItem(context, item)
                    handler.postDelayed({
                        background = itemBackground(
                            context,
                            itemColor(context, item.id),
                            SettingsManager.getFloatingScale(context).coerceIn(50, 150) / 100f
                        )
                    }, 450L)
                }
            }
        }

        view.setOnLongClickListener {
            FloatingItemManager.removeItem(context, item.id)
            Toast.makeText(context, "${item.title} 버튼을 제거했습니다.", Toast.LENGTH_SHORT).show()
            handler.post { refresh(context) }
            true
        }
        return view
    }

    private fun displaySymbol(id: String): String = when (id) {
        FloatingItemManager.ID_DEFROST -> "앞♨"
        FloatingItemManager.ID_REAR_DEFROST -> "뒤♨"
        FloatingItemManager.ID_INSIDE_LIGHT -> "등"
        FloatingItemManager.ID_LIGHT_ON -> "등+"
        FloatingItemManager.ID_LIGHT_OFF -> "등−"
        FloatingItemManager.ID_LIGHT_DOOR -> "문등"
        FloatingItemManager.ID_STEERING_HEAT -> "핸♨"
        FloatingItemManager.ID_SEAT_HEAT -> "석♨"
        FloatingItemManager.ID_AC_TOGGLE -> "A/C"
        FloatingItemManager.ID_MEDIA_PREVIOUS -> "◀|"
        FloatingItemManager.ID_MEDIA_PLAY_PAUSE -> "▶Ⅱ"
        FloatingItemManager.ID_MEDIA_NEXT -> "|▶"
        FloatingItemManager.ID_ROTATION -> "회전"
        FloatingItemManager.ID_SCREEN_OFF -> "화면"
        FloatingItemManager.ID_WIFI -> "WiFi"
        FloatingItemManager.ID_HOTSPOT -> "AP"
        FloatingItemManager.ID_MUTE -> "음소"
        else -> "•"
    }

    private fun itemColor(context: Context, id: String): Int = when (id) {
        FloatingItemManager.ID_DEFROST -> if (DefrostManager.isDefrostOn(context)) Color.parseColor("#E53935") else Color.parseColor("#37474F")
        FloatingItemManager.ID_REAR_DEFROST -> if (DefrostManager.isRearDefrostOn(context) == true) Color.parseColor("#E53935") else Color.parseColor("#37474F")
        FloatingItemManager.ID_INSIDE_LIGHT, FloatingItemManager.ID_LIGHT_ON, FloatingItemManager.ID_LIGHT_OFF -> if (InsideLightManager.isLightOn(context)) Color.parseColor("#FFB300") else Color.parseColor("#37474F")
        FloatingItemManager.ID_LIGHT_DOOR -> if (InsideLightManager.isDoorInterlockEnabled(context) == true) Color.parseColor("#FFB300") else Color.parseColor("#37474F")
        FloatingItemManager.ID_STEERING_HEAT, FloatingItemManager.ID_SEAT_HEAT -> Color.parseColor("#D84315")
        FloatingItemManager.ID_AC_TOGGLE -> if (VehicleComfortManager.isAcOn(context) == true) Color.parseColor("#0288D1") else Color.parseColor("#37474F")
        else -> Color.parseColor("#283593")
    }

    fun executeItem(context: Context, item: FloatingItem) {
        if (item.isApp) {
            launchApp(context.applicationContext, item)
            return
        }
        when (item.id) {
            FloatingItemManager.ID_DEFROST -> DefrostManager.toggle(context, showToast = true)
            FloatingItemManager.ID_REAR_DEFROST -> DefrostManager.toggleRear(context, showToast = true)
            FloatingItemManager.ID_INSIDE_LIGHT -> InsideLightManager.toggle(context, showToast = true)
            FloatingItemManager.ID_LIGHT_ON -> InsideLightManager.turnOn(context, showToast = true)
            FloatingItemManager.ID_LIGHT_OFF -> InsideLightManager.turnOff(context, showToast = true)
            FloatingItemManager.ID_LIGHT_DOOR -> {
                val enabled = InsideLightManager.toggleDoorInterlock(context)
                Toast.makeText(context, "도어 연동등 ${if (enabled) "켜짐" else "꺼짐"}", Toast.LENGTH_SHORT).show()
            }
            FloatingItemManager.ID_STEERING_HEAT -> {
                val enabled = VehicleComfortManager.toggleSteeringWheelHeating(context)
                Toast.makeText(context, "핸들 열선 ${if (enabled) "켜기" else "끄기"} 명령", Toast.LENGTH_SHORT).show()
            }
            FloatingItemManager.ID_SEAT_HEAT -> {
                val level = VehicleComfortManager.cycleSeatHeating(context, VehicleComfortManager.SEAT_DRIVER)
                Toast.makeText(context, "운전석 열선 ${heatName(level)} 명령", Toast.LENGTH_SHORT).show()
            }
            FloatingItemManager.ID_AC_TOGGLE -> {
                val enabled = VehicleComfortManager.toggleAcPower(context)
                Toast.makeText(context, "공조 ${if (enabled) "켜기" else "끄기"} 명령", Toast.LENGTH_SHORT).show()
            }
            FloatingItemManager.ID_MEDIA_PREVIOUS -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            FloatingItemManager.ID_MEDIA_PLAY_PAUSE -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            FloatingItemManager.ID_MEDIA_NEXT -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            FloatingItemManager.ID_ROTATION -> toggleRotation(context)
            FloatingItemManager.ID_SCREEN_OFF -> runAdb(context, "input keyevent 26", "화면 끄기")
            FloatingItemManager.ID_WIFI -> openSettings(context, Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
            FloatingItemManager.ID_HOTSPOT -> openSettings(context, "android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)
            FloatingItemManager.ID_MUTE -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            }
        }
    }

    private fun heatName(level: Int): String = when (level) {
        VehicleComfortManager.HEAT_LOW -> "1단"
        VehicleComfortManager.HEAT_HIGH -> "2단"
        else -> "꺼짐"
    }

    private fun sendMediaKey(context: Context, keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun toggleRotation(context: Context) {
        ioScope.launch {
            val current = NativeAdbClient.executeShell(context, "settings get system user_rotation")
                .output.trim().toIntOrNull() ?: 0
            val next = if (current == 0 || current == 2) 1 else 0
            val autoResult = NativeAdbClient.executeShell(context, "settings put system accelerometer_rotation 0")
            val rotateResult = NativeAdbClient.executeShell(context, "settings put system user_rotation $next")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (autoResult.success && rotateResult.success) "화면 방향 전환 완료" else "화면 회전 실패 — ADB 로그 확인",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun runAdb(context: Context, command: String, label: String) {
        ioScope.launch {
            val result = NativeAdbClient.executeShell(context, command)
            if (!result.success) withContext(Dispatchers.Main) {
                Toast.makeText(context, "$label 실패 — 로컬 ADB를 확인하세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSettings(context: Context, primaryAction: String, fallbackAction: String) {
        val primary = Intent(primaryAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(fallbackAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(primary) }.recoverCatching { context.startActivity(fallback) }
            .onFailure { Toast.makeText(context, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show() }
    }

    private fun launchApp(context: Context, item: FloatingItem) {
        val intent = context.packageManager.getLaunchIntentForPackage(item.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (intent == null) {
            Toast.makeText(context, "${item.title} 실행 불가", Toast.LENGTH_SHORT).show()
        } else {
            runCatching { context.startActivity(intent) }
                .onFailure { Toast.makeText(context, "앱 실행 오류", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun touchActivity(context: Context) {
        if (collapsed) expand(context)
        scheduleCollapse(context)
    }

    private fun scheduleCollapse(context: Context) {
        collapseRunnable?.let(handler::removeCallbacks)
        collapseRunnable = Runnable { collapse() }.also {
            handler.postDelayed(it, SettingsManager.getFloatingCollapseDelaySeconds(context) * 1_000L)
        }
    }

    private fun collapse() {
        if (collapsed || overlayView == null) return
        collapsed = true
        stripScroller?.visibility = View.GONE
        resetView?.visibility = View.GONE
        handleView?.apply {
            text = "◆"
            contentDescription = "플로팅 독 펼치기"
        }
        val params = layoutParams
        if (params != null) overlayView?.let { runCatching { windowManager?.updateViewLayout(it, params) } }
    }

    private fun expand(context: Context) {
        if (!collapsed) return
        collapsed = false
        stripScroller?.visibility = View.VISIBLE
        resetView?.visibility = View.VISIBLE
        handleView?.apply {
            text = "⋮⋮"
            contentDescription = "플로팅 독 이동 손잡이"
        }
        val params = layoutParams
        if (params != null) overlayView?.let { runCatching { windowManager?.updateViewLayout(it, params) } }
        scheduleCollapse(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener(context: Context, root: View, metrics: DisplayMetrics) {
        val handle = handleView ?: return
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moving = false
        handle.setOnTouchListener { view, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    collapseRunnable?.let(handler::removeCallbacks)
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
                        params.x = (initialX + dx).coerceIn(0, (metrics.widthPixels - dpToPx(context, 36)).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (metrics.heightPixels - dpToPx(context, 36)).coerceAtLeast(0))
                        windowManager?.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving) {
                        SettingsManager.setFloatingX(context, params.x)
                        SettingsManager.setFloatingY(context, params.y)
                    } else if (collapsed) {
                        expand(context)
                        view.performClick()
                    } else {
                        collapse()
                        view.performClick()
                    }
                    scheduleCollapse(context)
                    true
                }
                else -> false
            }
        }
    }

    private fun dockBackground(context: Context, scale: Float) = GradientDrawable().apply {
        setColor(Color.parseColor("#E6181824"))
        cornerRadius = dpToPx(context, (22 * scale).toInt()).toFloat()
        setStroke(dpToPx(context, 1), Color.parseColor("#8000E5FF"))
    }

    private fun itemBackground(context: Context, color: Int, scale: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpToPx(context, (12 * scale).toInt()).toFloat()
    }

    private fun dpToPx(context: Context, dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        context.resources.displayMetrics
    ).toInt()
}
