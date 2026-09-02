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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.byd.dolphin.autoassistant.floating.FloatingItemManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlin.math.abs

/**
 * 사용자가 선택한 퀵패널/하단바 기능 및 모든 설치 앱을 자유롭게 배치할 수 있는 이동식 플로팅 독
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
            }

            val container = createOverlayContainer(context)
            setupTouchDragListener(context, container)

            windowManager?.addView(container, layoutParams)
            overlayView = container
            DolphinLogger.i(TAG, "Dynamic Floating Overlay displayed at ($savedX, $savedY)")
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
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(context, 8), dpToPx(context, 6), dpToPx(context, 8), dpToPx(context, 6))

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD1E1E2C"))
                cornerRadius = dpToPx(context, 24).toFloat()
                setStroke(dpToPx(context, 1), Color.parseColor("#66FFFFFF"))
            }
        }

        // 1. 드래그 핸들
        val tvDragHandle = TextView(context).apply {
            text = "⋮⋮"
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 14f
            setPadding(dpToPx(context, 4), 0, dpToPx(context, 6), 0)
        }
        container.addView(tvDragHandle)

        // 2. 사용자가 선택한 동적 아이템들 추가
        val selectedIds = FloatingItemManager.getSelectedIds(context)
        val allItems = FloatingItemManager.getAllAvailableItems(context).associateBy { it.id }

        for (id in selectedIds) {
            val item = allItems[id] ?: continue

            val btn = Button(context).apply {
                text = item.title.replace("📱 ", "").replace("♨️ ", "♨ ").replace("💡 ", "💡 ").take(7)
                textSize = 12f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(getItemColor(context, id))
                    cornerRadius = dpToPx(context, 16).toFloat()
                }
                setPadding(dpToPx(context, 8), dpToPx(context, 4), dpToPx(context, 8), dpToPx(context, 4))
                setOnClickListener {
                    onItemClicked(context, item, this)
                }
            }
            container.addView(btn)

            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(context, 4), 1)
            }
            container.addView(spacer)
        }

        // 3. 원위치 복귀 리셋 버튼 (⟲)
        val btnReset = TextView(context).apply {
            text = "⟲"
            textSize = 16f
            setTextColor(Color.parseColor("#80D8FF"))
            setPadding(dpToPx(context, 6), dpToPx(context, 4), dpToPx(context, 6), dpToPx(context, 4))
            setOnClickListener {
                resetToDefaultPosition(context)
            }
        }
        container.addView(btnReset)

        return container
    }

    private fun getItemColor(context: Context, id: String): Int {
        return when (id) {
            FloatingItemManager.ID_DEFROST -> if (DefrostManager.isDefrostOn(context)) Color.parseColor("#E53935") else Color.parseColor("#37474F")
            FloatingItemManager.ID_INSIDE_LIGHT -> if (InsideLightManager.isLightOn(context)) Color.parseColor("#FFB300") else Color.parseColor("#37474F")
            FloatingItemManager.ID_STEERING_HEAT -> Color.parseColor("#D84315")
            FloatingItemManager.ID_SEAT_HEAT -> Color.parseColor("#F4511E")
            FloatingItemManager.ID_AC_TOGGLE -> Color.parseColor("#0288D1")
            else -> Color.parseColor("#283593") // 일반 앱 버튼 색상
        }
    }

    private fun onItemClicked(context: Context, item: com.byd.dolphin.autoassistant.floating.FloatingItem, btn: Button) {
        if (item.isApp) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(item.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                if (intent != null) {
                    context.startActivity(intent)
                } else {
                    Toast.makeText(context, "${item.title} 실행 불가", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "실행 오류: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            when (item.id) {
                FloatingItemManager.ID_DEFROST -> {
                    val newState = DefrostManager.toggle(context, showToast = true)
                    btn.background = GradientDrawable().apply {
                        setColor(if (newState) Color.parseColor("#E53935") else Color.parseColor("#37474F"))
                        cornerRadius = dpToPx(context, 16).toFloat()
                    }
                }
                FloatingItemManager.ID_INSIDE_LIGHT -> {
                    val newState = InsideLightManager.toggle(context, showToast = true)
                    btn.background = GradientDrawable().apply {
                        setColor(if (newState) Color.parseColor("#FFB300") else Color.parseColor("#37474F"))
                        cornerRadius = dpToPx(context, 16).toFloat()
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
