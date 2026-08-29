package com.byd.dolphin.autoassistant.split

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.byd.dolphin.autoassistant.MainActivity

object SplitScreenManager {

    private const val TAG = "SplitScreenManager"
    private const val PREF_NAME = "dolphin_split_prefs"

    private var activeConfig: SplitConfig? = null

    // 1. 화면 해상도 기반 각 앱의 Bounds Rect 계산 (1% 정밀도)
    fun calculateAppBounds(context: Context, config: SplitConfig): List<Rect> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val boundsList = mutableListOf<Rect>()

        when (config.mode) {
            SplitMode.TWO_APPS_HORIZONTAL -> {
                val splitX = (screenW * (config.ratioPrimary / 100.0)).toInt()
                boundsList.add(Rect(0, 0, splitX, screenH))             // 앱 1 (좌측)
                boundsList.add(Rect(splitX, 0, screenW, screenH))       // 앱 2 (우측)
            }
            SplitMode.THREE_APPS_LEFT_STACKED -> {
                val splitX = (screenW * (config.ratioPrimary / 100.0)).toInt()
                val splitY = (screenH * (config.ratioSecondary / 100.0)).toInt()
                boundsList.add(Rect(0, 0, splitX, splitY))              // 앱 1 (좌측 상단)
                boundsList.add(Rect(0, splitY, splitX, screenH))        // 앱 2 (좌측 하단)
                boundsList.add(Rect(splitX, 0, screenW, screenH))       // 앱 3 (우측 전체)
            }
            SplitMode.THREE_APPS_RIGHT_STACKED -> {
                val splitX = (screenW * (config.ratioPrimary / 100.0)).toInt()
                val splitY = (screenH * (config.ratioSecondary / 100.0)).toInt()
                boundsList.add(Rect(0, 0, splitX, screenH))             // 앱 1 (좌측 전체)
                boundsList.add(Rect(splitX, 0, screenW, splitY))        // 앱 2 (우측 상단)
                boundsList.add(Rect(splitX, splitY, screenW, screenH))  // 앱 3 (우측 하단)
            }
        }
        return boundsList
    }

    // 2. 다중 앱 커스텀 비율 동시 실행
    fun launchSplitScreen(context: Context, config: SplitConfig) {
        activeConfig = config
        saveLastConfig(context, config)

        val bounds = calculateAppBounds(context, config)
        val pm = context.packageManager

        val pkgs = if (config.mode == SplitMode.TWO_APPS_HORIZONTAL) {
            listOf(config.pkg1, config.pkg2)
        } else {
            listOf(config.pkg1, config.pkg2, config.pkg3)
        }

        for (i in pkgs.indices) {
            val pkg = pkgs[i]
            val rect = bounds[i]

            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

                    val options = ActivityOptions.makeBasic()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        options.setLaunchBounds(rect)
                    }

                    context.startActivity(launchIntent, options.toBundle())

                    // DiLink 윈도잉 쉘 보조 실행
                    try {
                        val cmd = "am start -n ${launchIntent.component?.flattenToString()} --windowingMode 5 --bounds ${rect.left},${rect.top},${rect.right},${rect.bottom}"
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    } catch (e: Exception) {
                        Log.w(TAG, "Shell launch note: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch split app: $pkg", e)
            }
        }
        Toast.makeText(context, "${config.title} (${config.ratioPrimary}% : ${100 - config.ratioPrimary}%) 분할 실행됨", Toast.LENGTH_SHORT).show()
    }

    // 3. 후진 360 카메라(AVM) 종료 시 순정 50:50 리셋 방지 및 커스텀 비율 자동 복원
    fun restoreLastSplitScreen(context: Context) {
        activeConfig?.let {
            Log.i(TAG, "후진 카메라 종료: 커스텀 화면 분할 비율 자동 복구 실행 (${it.ratioPrimary}% : ${100 - it.ratioPrimary}%)")
            launchSplitScreen(context, it)
        }
    }

    // 4. 바탕화면 바로가기 아이콘 생성
    fun createHomeScreenShortcut(context: Context, config: SplitConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {

                val shortcutIntent = Intent(context, MainActivity::class.java).apply {
                    action = "ACTION_LAUNCH_CUSTOM_SPLIT"
                    putExtra("EXTRA_SPLIT_CONFIG", config)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

                val iconBitmap = generateShortcutIcon(config.title)
                val pinShortcutInfo = ShortcutInfo.Builder(context, "split_shortcut_${System.currentTimeMillis()}")
                    .setIcon(Icon.createWithBitmap(iconBitmap))
                    .setShortLabel(config.title)
                    .setLongLabel("${config.title} (${config.ratioPrimary}% 커스텀 분할)")
                    .setIntent(shortcutIntent)
                    .build()

                val successCallback = PendingIntent.getActivity(
                    context,
                    0,
                    shortcutIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                shortcutManager.requestPinShortcut(pinShortcutInfo, successCallback.intentSender)
                Toast.makeText(context, "바탕화면에 '${config.title}' 바로가기 아이콘이 생성되었습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun generateShortcutIcon(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7C4DFF")
        }
        canvas.drawCircle(64f, 64f, 60f, paint)

        paint.color = Color.WHITE
        paint.textSize = 42f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(title.take(2), 64f, 78f, paint)
        return bitmap
    }

    private fun saveLastConfig(context: Context, config: SplitConfig) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("mode", config.mode.ordinal)
            .putString("pkg1", config.pkg1)
            .putString("pkg2", config.pkg2)
            .putString("pkg3", config.pkg3)
            .putInt("r1", config.ratioPrimary)
            .putInt("r2", config.ratioSecondary)
            .apply()
    }
}
