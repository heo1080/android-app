package com.byd.dolphin.autoassistant.manager

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.byd.dolphin.autoassistant.floating.FloatingItem
import com.byd.dolphin.autoassistant.floating.FloatingItemManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlin.math.abs

/**
 * 플로팅 독 관리 매니저:
 * - 화면 어디든 자유로운 드래그 이동 및 위치 기억
 * - 메뉴에서 50%~150% 크기(Scale) 조절 연동
 * - 메뉴에서 30%~100% 투명도(Alpha) 조절 연동
 * - 선택한 앱의 실제 순정 고해상도 앱 아이콘(PackageManager Icon) 표출
 */
object FloatingOverlayManager {

    private const val TAG = "FloatingOverlayManager"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var defaultX = 0
    private var defaultY = 0

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (!SettingsManager.isFloatingOverlayEnabled(context)) {
            hide()
            return
        }

        if (overlayView != null) {
            refresh(context)
            return
        }

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getMetrics(metrics)
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            val overlayWidthPx = dpToPx(context, 260)
            defaultX = (screenWidth - overlayWidthPx) / 2
            defaultY = screenHeight - dpToPx(context, 160)

            val savedX = SettingsManager.getFloatingX(context, defaultX)
            val savedY = SettingsManager.getFloatingY(context, defaultY)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val opacity = SettingsManager.getFloatingOpacity(context).coerceIn(30, 100)

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX
                y = savedY
                alpha = opacity / 100f
            }

            val container = createOverlayContainer(context)
            setupTouchDragListener(context, container)

            windowManager?.addView(container, layoutParams)
            overlayView = container
            DolphinLogger.i(TAG, "Floating Dock displayed at ($savedX, $savedY) with alpha ${layoutParams?.alpha}")
        } catch (e: Exception) {
            DolphinLogger.e(TAG, "Failed to display floating overlay", e)
        }
    }

    fun hide() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                DolphinLogger.w(TAG, "Error removing overlayView: ${e.message}")
            }
            overlayView = null
            layoutParams = null
        }
    }

    fun refresh(context: Context) {
        hide()
        show(context)
    }

    fun resetToDefaultPosition(context: Context) {
        layoutParams?.let { params ->
            params.x = defaultX
            params.y = defaultY
            windowManager?.updateViewLayout(overlayView, params)
            SettingsManager.setFloatingX(context, defaultX)
            SettingsManager.setFloatingY(context, defaultY)
            Toast.makeText(context, "플로팅 버튼이 기본 위치로 복귀했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createOverlayContainer(context: Context): LinearLayout {
        val scalePercent = SettingsManager.getFloatingScale(context).coerceIn(50, 150)
        val scale = scalePercent / 100f

        val padH = (8 * scale).toInt()
        val padV = (6 * scale).toInt()
        val corner = (24 * scale).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(context, padH), dpToPx(context, padV), dpToPx(context, padH), dpToPx(context, padV))

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6181824"))
                cornerRadius = dpToPx(context, corner).toFloat()
                setStroke(dpToPx(context, 1), Color.parseColor("#8000E5FF"))
            }
        }

        // 1. 드래그 인디케이터 핸들
        val tvDragHandle = TextView(context).apply {
            text = "⋮⋮"
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 14f * scale
            setPadding(dpToPx(context, 4), 0, dpToPx(context, 6), 0)
        }
        container.addView(tvDragHandle)

        // 2. 사용자가 선택한 동적 아이템들
        val selectedIds = FloatingItemManager.getSelectedIds(context)
        val allItems = FloatingItemManager.getAllAvailableItems(context).associateBy { it.id }

        val btnSizeDp = (42 * scale).toInt()
        val btnSizePx = dpToPx(context, btnSizeDp)

        for (id in selectedIds) {
            val item = allItems[id] ?: continue

            if (item.isApp) {
                // 앱인 경우: 실제 앱의 고해상도 앱 아이콘 표시!
                val ivApp = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                        setMargins(dpToPx(context, 3), 0, dpToPx(context, 3), 0)
                    }
                    val iconDrawable = try {
                        context.packageManager.getApplicationIcon(item.packageName)
                    } catch (e: Exception) {
                        null
                    }
                    if (iconDrawable != null) {
                        setImageDrawable(iconDrawable)
                    } else {
                        setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#252538"))
                        cornerRadius = dpToPx(context, (12 * scale).toInt()).toFloat()
                    }
                    setPadding(dpToPx(context, 4), dpToPx(context, 4), dpToPx(context, 4), dpToPx(context, 4))
                    setOnClickListener {
                        launchApp(context, item.packageName, item.title)
                    }
                }
                container.addView(ivApp)
            } else {
                // 기능 버튼인 경우: 아이콘/텍스트 버튼 표시
                val btn = Button(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        btnSizePx
                    ).apply {
                        setMargins(dpToPx(context, 3), 0, dpToPx(context, 3), 0)
                    }
                    text = getFunctionDisplaySymbol(item.id, item.title)
                    textSize = 12f * scale
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(getItemColor(context, id))
                        cornerRadius = dpToPx(context, (14 * scale).toInt()).toFloat()
                    }
                    setPadding(dpToPx(context, (10 * scale).toInt()), 0, dpToPx(context, (10 * scale).toInt()), 0)
                    setOnClickListener {
                        onFunctionClicked(context, item, this)
                    }
                }
                container.addView(btn)
            }
        }

        // 3. 원위치 복귀 리셋 버튼 (⟲)
        val btnReset = TextView(context).apply {
            text = "⟲"
            textSize = 16f * scale
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(dpToPx(context, 6), dpToPx(context, 4), dpToPx(context, 6), dpToPx(context, 4))
            setOnClickListener {
                resetToDefaultPosition(context)
            }
        }
        container.addView(btnReset)

        return container
    }

    private fun getFunctionDisplaySymbol(id: String, title: String): String {
        return when (id) {
            FloatingItemManager.ID_DEFROST -> "♨ 성에"
            FloatingItemManager.ID_INSIDE_LIGHT -> "💡 실내등"
            FloatingItemManager.ID_STEERING_HEAT -> "♨ 핸들"
            FloatingItemManager.ID_SEAT_HEAT -> "💺 시트"
            FloatingItemManager.ID_AC_TOGGLE -> "❄ 공조"
            else -> title.take(4)
        }
    }

    private fun getItemColor(context: Context, id: String): Int {
        return when (id) {
            FloatingItemManager.ID_DEFROST -> if (DefrostManager.isDefrostOn(context)) Color.parseColor("#E53935") else Color.parseColor("#37474F")
            FloatingItemManager.ID_INSIDE_LIGHT -> if (InsideLightManager.isLightOn(context)) Color.parseColor("#FFB300") else Color.parseColor("#37474F")
            FloatingItemManager.ID_STEERING_HEAT -> Color.parseColor("#D84315")
            FloatingItemManager.ID_SEAT_HEAT -> Color.parseColor("#F4511E")
            FloatingItemManager.ID_AC_TOGGLE -> Color.parseColor("#0288D1")
            else -> Color.parseColor("#283593")
        }
    }

    private fun launchApp(context: Context, packageName: String, title: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (intent != null) {
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "$title 실행 불가", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "실행 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onFunctionClicked(context: Context, item: FloatingItem, btn: Button) {
        when (item.id) {
            FloatingItemManager.ID_DEFROST -> {
                val newState = DefrostManager.toggle(context, showToast = true)
                btn.background = GradientDrawable().apply {
                    setColor(if (newState) Color.parseColor("#E53935") else Color.parseColor("#37474F"))
                    cornerRadius = dpToPx(context, 14).toFloat()
                }
            }
            FloatingItemManager.ID_INSIDE_LIGHT -> {
                val newState = InsideLightManager.toggle(context, showToast = true)
                btn.background = GradientDrawable().apply {
                    setColor(if (newState) Color.parseColor("#FFB300") else Color.parseColor("#37474F"))
                    cornerRadius = dpToPx(context, 14).toFloat()
                }
            }
            FloatingItemManager.ID_STEERING_HEAT -> {
                Toast.makeText(context, "핸들 열선 작동", Toast.LENGTH_SHORT).show()
            }
            FloatingItemManager.ID_SEAT_HEAT -> {
                Toast.makeText(context, "시트 열선 작동", Toast.LENGTH_SHORT).show()
            }
            FloatingItemManager.ID_AC_TOGGLE -> {
                Toast.makeText(context, "공조 토글 실행", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchDragListener(context: Context, view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false

        view.setOnTouchListener { v, event ->
            val params = layoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoving = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isMoving = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoving) {
                        SettingsManager.setFloatingX(context, params.x)
                        SettingsManager.setFloatingY(context, params.y)
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
