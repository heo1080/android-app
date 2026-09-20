package com.byd.dolphin.autoassistant

import android.Manifest
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.byd.dolphin.autoassistant.manager.AppUpdateManager
import com.byd.dolphin.autoassistant.manager.BootAppItem
import com.byd.dolphin.autoassistant.manager.DiagnosticCaptureManager
import com.byd.dolphin.autoassistant.manager.DiagnosticUploadManager
import com.byd.dolphin.autoassistant.manager.NativeAdbClient
import com.byd.dolphin.autoassistant.manager.SettingsManager
import com.byd.dolphin.autoassistant.service.DolphinService
import com.byd.dolphin.autoassistant.split.SplitConfig
import com.byd.dolphin.autoassistant.split.SplitMode
import com.byd.dolphin.autoassistant.split.SplitScreenManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fresh HOME shell for BYD Launcher Evolution.
 * The old assistant dashboard is intentionally not used as the launcher surface.
 */
class LauncherActivity : AppCompatActivity() {

    private data class LaunchApp(val packageName: String, val label: String, val icon: Drawable?)

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var bodyHost: FrameLayout
    private lateinit var timeView: TextView
    private lateinit var splitStateView: TextView
    private var drawerOverlay: View? = null
    private var allApps: List<LaunchApp> = emptyList()

    private val clockTick = object : Runnable {
        override fun run() {
            if (::timeView.isInitialized) {
                timeView.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            }
            handler.postDelayed(this, 15000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DolphinLogger.init(this)
        DiagnosticCaptureManager.recoverInterruptedSession(this)
        ensureNotificationPermission()
        startCoreService()
        allApps = queryLaunchableApps()
        seedFavoritesIfNeeded()
        buildLauncher()
        AppUpdateManager.checkForUpdates(this, manual = false)
    }

    override fun onResume() {
        super.onResume()
        allApps = queryLaunchableApps()
        if (::root.isInitialized) renderHome()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onBackPressed() {
        if (drawerOverlay != null) {
            hideDrawer()
        } else {
            moveTaskToBack(false)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3501)
        }
    }

    private fun startCoreService() {
        runCatching {
            val intent = Intent(this, DolphinService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            DolphinLogger.i(TAG, "launcher started existing DolphinService")
        }.onFailure {
            DolphinLogger.e(TAG, "failed to start core service from launcher", it)
        }
    }

    private fun buildLauncher() {
        root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#07141C"), Color.parseColor("#020609"))
            )
        }
        setContentView(root)
        renderHome()
    }

    private fun renderHome() {
        if (!::root.isInitialized) return
        root.removeAllViews()
        drawerOverlay = null

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(12), dp(28), dp(14))
        }
        root.addView(shell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shell.addView(buildTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))

        bodyHost = FrameLayout(this)
        shell.addView(bodyHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        showHomeContent()

        shell.addView(buildDock(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)))
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(this).apply {
            text = "BYD  /  LAUNCHER EVOLUTION"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        bar.addView(chip("CORE SERVICE"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            marginEnd = dp(10)
        })

        splitStateView = chip(splitSummary(true))
        bar.addView(splitStateView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            marginEnd = dp(14)
        })

        timeView = TextView(this).apply {
            text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(timeView, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.MATCH_PARENT))
        return bar
    }

    private fun showHomeContent() {
        bodyHost.removeAllViews()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        bodyHost.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(18), dp(4), dp(18))
        }
        scroll.addView(content, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroText.addView(TextView(this).apply {
            text = "DRIVE HOME"
            setTextColor(Color.WHITE)
            textSize = 38f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        heroText.addView(TextView(this).apply {
            text = "어시스트 메뉴가 아닌, 앱과 주행 도구가 중심인 새 홈"
            setTextColor(Color.parseColor("#91A7B5"))
            textSize = 14f
        })
        hero.addView(heroText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(chip("ALPHA 1 · NEW SHELL").apply {
            setTextColor(Color.parseColor("#78F6D3"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        content.addView(hero)

        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)).apply {
            topMargin = dp(18)
        })
        quick.addView(actionCard("앱 서랍", allApps.size.toString() + "개 앱", "▦") { showAppDrawer() }, weighted())
        quick.addView(actionCard("2분할", splitSummary(false), "◫") { launchConfiguredSplit() }, weighted())
        quick.addView(actionCard("시동 앱", SettingsManager.getBootAppList(this).count { it.enabled }.toString() + "개 자동 실행", "▶") { showBootManager() }, weighted())
        quick.addView(actionCard("업데이트", "자동 확인 유지", "↻") { showCorePanel() }, weighted())

        content.addView(TextView(this).apply {
            text = "즐겨찾기"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
            bottomMargin = dp(8)
        })

        val favorites = favoriteApps()
        if (favorites.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "앱을 길게 눌러 홈에 고정할 수 있습니다."
                setTextColor(Color.parseColor("#8CA0AD"))
                setPadding(dp(10), dp(16), 0, dp(16))
            })
        } else {
            val grid = GridLayout(this).apply { columnCount = 6 }
            favorites.forEach { grid.addView(appTile(it, false), gridParams()) }
            content.addView(grid)
        }

        content.addView(TextView(this).apply {
            text = "앱 길게 누르기 → 홈 고정 · 2분할 좌/우 · 팝업 · 시동 자동실행"
            setTextColor(Color.parseColor("#607985"))
            textSize = 12f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(8)
        })
    }

    private fun buildDock(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded("#0D1B23", 24, "#17313C")
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        dock.addView(dockButton("HOME", "⌂") { showHomeContent() }, weighted())
        dock.addView(dockButton("APPS", "▦") { showAppDrawer() }, weighted())
        dock.addView(dockButton("SPLIT", "◫") { launchConfiguredSplit() }, weighted())
        dock.addView(dockButton("AUTO", "▶") { showBootManager() }, weighted())
        dock.addView(dockButton("CORE", "●") { showCorePanel() }, weighted())
        return dock
    }

    private fun showAppDrawer() {
        hideDrawer()
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#F2050B10")) }
        drawerOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(34), dp(26), dp(34), dp(26))
            background = rounded("#101C23", 26, "#29424E")
        }
        overlay.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(44), dp(38), dp(44), dp(38))
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        header.addView(TextView(this).apply {
            text = "APP DRAWER"
            setTextColor(Color.WHITE)
            textSize = 25f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(Button(this).apply {
            text = "닫기"
            setTextColor(Color.WHITE)
            background = rounded("#162832", 18, "#36505D")
            setOnClickListener { hideDrawer() }
        }, LinearLayout.LayoutParams(dp(96), dp(42)))

        val search = EditText(this).apply {
            hint = "앱 검색"
            setHintTextColor(Color.parseColor("#718894"))
            setTextColor(Color.WHITE)
            singleLine = true
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded("#091218", 18, "#203945")
        }
        panel.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })

        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)

        fun rebuild(filter: String) {
            container.removeAllViews()
            val left = splitPackage(KEY_SPLIT_LEFT)
            val right = splitPackage(KEY_SPLIT_RIGHT)
            if (left != null && right != null) {
                val shortcut = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                    background = rounded("#0E2728", 18, "#2FB69B")
                    setOnClickListener { hideDrawer(); launchConfiguredSplit() }
                }
                shortcut.addView(TextView(this).apply {
                    text = "◫"
                    textSize = 28f
                    setTextColor(Color.parseColor("#78F6D3"))
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(54), dp(54)))
                shortcut.addView(TextView(this).apply {
                    text = "2분할 바로가기\n" + appLabel(left) + "  +  " + appLabel(right)
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                container.addView(shortcut, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)).apply {
                    bottomMargin = dp(12)
                })
            }
            val grid = GridLayout(this).apply { columnCount = 6 }
            val q = filter.trim().lowercase(Locale.getDefault())
            allApps.filter {
                q.isBlank() || it.label.lowercase(Locale.getDefault()).contains(q) || it.packageName.lowercase(Locale.getDefault()).contains(q)
            }.forEach { grid.addView(appTile(it, true), gridParams()) }
            container.addView(grid)
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = rebuild(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        rebuild("")
    }

    private fun hideDrawer() {
        drawerOverlay?.let { root.removeView(it) }
        drawerOverlay = null
    }

    private fun appTile(app: LaunchApp, compact: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(8))
            background = rounded("#0B171E", 18, "#1C313C")
            setOnClickListener { launchApp(app.packageName) }
            setOnLongClickListener {
                showAppActions(app)
                true
            }
            addView(ImageView(this@LauncherActivity).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(if (compact) 48 else 56), dp(if (compact) 48 else 56)))
            addView(TextView(this@LauncherActivity).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = if (compact) 12f else 13f
                gravity = Gravity.CENTER
                maxLines = 2
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(7)
            })
        }
    }

    private fun showAppActions(app: LaunchApp) {
        val pinned = favoritePackages().contains(app.packageName)
        val auto = SettingsManager.getBootAppList(this).any { it.packageName == app.packageName }
        val choices = arrayOf(
            if (pinned) "홈 고정 해제" else "홈에 고정",
            "2분할 왼쪽으로 지정",
            "2분할 오른쪽으로 지정",
            "팝업 창으로 실행",
            if (auto) "시동 자동실행에서 제거" else "시동 자동실행에 추가",
            "앱 정보"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> toggleFavorite(app.packageName)
                    1 -> setSplitPackage(KEY_SPLIT_LEFT, app.packageName)
                    2 -> setSplitPackage(KEY_SPLIT_RIGHT, app.packageName)
                    3 -> launchPopup(app.packageName)
                    4 -> toggleBootApp(app)
                    5 -> openAppInfo(app.packageName)
                }
            }.show()
    }

    private fun toggleFavorite(packageName: String) {
        val set = favoritePackages().toMutableSet()
        if (!set.add(packageName)) set.remove(packageName)
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply()
        hideDrawer()
        renderHome()
    }

    private fun setSplitPackage(key: String, packageName: String) {
        prefs.edit().putString(key, packageName).apply()
        val side = if (key == KEY_SPLIT_LEFT) "왼쪽" else "오른쪽"
        Toast.makeText(this, side + " 2분할 앱: " + appLabel(packageName), Toast.LENGTH_SHORT).show()
        if (::splitStateView.isInitialized) splitStateView.text = splitSummary(true)
    }

    private fun launchConfiguredSplit() {
        val left = splitPackage(KEY_SPLIT_LEFT)
        val right = splitPackage(KEY_SPLIT_RIGHT)
        if (left == null || right == null) {
            Toast.makeText(this, "앱을 길게 눌러 2분할 왼쪽/오른쪽 앱을 먼저 지정하세요.", Toast.LENGTH_LONG).show()
            showAppDrawer()
            return
        }
        if (left == right) {
            Toast.makeText(this, "서로 다른 앱을 지정하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        SplitScreenManager.launchSplitScreen(
            this,
            SplitConfig(
                title = appLabel(left) + " + " + appLabel(right),
                mode = SplitMode.TWO_APPS_HORIZONTAL,
                pkg1 = left,
                pkg2 = right,
                ratioPrimary = prefs.getInt(KEY_SPLIT_RATIO, 50)
            )
        )
    }

    private fun launchPopup(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val component = launchIntent?.component
        if (launchIntent == null || component == null) {
            Toast.makeText(this, "실행할 수 없는 앱입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val metrics = resources.displayMetrics
        val left = (metrics.widthPixels * 0.20f).toInt()
        val top = (metrics.heightPixels * 0.14f).toInt()
        val right = (metrics.widthPixels * 0.88f).toInt()
        val bottom = (metrics.heightPixels * 0.86f).toInt()
        val bounds = Rect(left, top, right, bottom)

        lifecycleScope.launch(Dispatchers.IO) {
            val adb = if (NativeAdbClient.isPortOpen()) {
                NativeAdbClient.executeShell(
                    this@LauncherActivity,
                    "am start --user 0 --windowingMode 5 --bounds " +
                        left + "," + top + "," + right + "," + bottom + " -n " + component.flattenToShortString()
                )
            } else null
            withContext(Dispatchers.Main) {
                if (adb?.success == true) {
                    Toast.makeText(this@LauncherActivity, "팝업 실행 요청 완료", Toast.LENGTH_SHORT).show()
                } else {
                    runCatching {
                        val options = ActivityOptions.makeBasic().apply { launchBounds = bounds }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent, options.toBundle())
                    }.onFailure {
                        launchApp(packageName)
                        Toast.makeText(this@LauncherActivity, "팝업 모드 미지원 · 일반 실행으로 전환", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun toggleBootApp(app: LaunchApp) {
        val exists = SettingsManager.getBootAppList(this).any { it.packageName == app.packageName }
        if (exists) {
            SettingsManager.removeBootApp(this, app.packageName)
            Toast.makeText(this, app.label + ": 시동 자동실행 제거", Toast.LENGTH_SHORT).show()
        } else {
            SettingsManager.addBootApp(
                this,
                BootAppItem(
                    packageName = app.packageName,
                    appName = app.label,
                    delaySeconds = 2.0,
                    enabled = true,
                    mediaPlayEnabled = false,
                    mediaDelaySeconds = 2.0
                )
            )
            SettingsManager.setBootAutoEnabled(this, true)
            Toast.makeText(this, app.label + ": 시동 +2초 자동실행 추가", Toast.LENGTH_SHORT).show()
        }
        renderHome()
    }

    private fun showBootManager() {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        wrap.addView(SwitchCompat(this).apply {
            text = "시동 시 등록 앱 자동 실행"
            isChecked = SettingsManager.isBootAutoEnabled(this@LauncherActivity)
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> SettingsManager.setBootAutoEnabled(this@LauncherActivity, checked) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val items = SettingsManager.getBootAppList(this)
        if (items.isEmpty()) {
            wrap.addView(TextView(this).apply {
                text = "등록된 앱이 없습니다.\n앱서랍에서 앱을 길게 눌러 추가하세요."
                setTextColor(Color.parseColor("#8CA0AD"))
                setPadding(0, dp(16), 0, dp(16))
            })
        } else {
            items.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(SwitchCompat(this).apply {
                    text = item.appName + "  ·  +" + item.delaySeconds + "s"
                    isChecked = item.enabled
                    setTextColor(Color.WHITE)
                    setOnCheckedChangeListener { _, checked ->
                        val changed = SettingsManager.getBootAppList(this@LauncherActivity).map {
                            if (it.packageName == item.packageName) it.copy(enabled = checked) else it
                        }
                        SettingsManager.saveBootAppList(this@LauncherActivity, changed)
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                row.addView(Button(this).apply {
                    text = "삭제"
                    setTextColor(Color.WHITE)
                    background = rounded("#31181C", 15, "#6D313A")
                    setOnClickListener {
                        SettingsManager.removeBootApp(this@LauncherActivity, item.packageName)
                        Toast.makeText(this@LauncherActivity, item.appName + " 제거", Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(dp(78), dp(40)))
                wrap.addView(row)
            }
        }
        val scroll = ScrollView(this).apply { addView(wrap) }
        AlertDialog.Builder(this)
            .setTitle("시동 자동실행 앱")
            .setView(scroll)
            .setPositiveButton("앱서랍에서 추가") { _, _ -> showAppDrawer() }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showCorePanel() {
        val autoUpload = DiagnosticUploadManager.isAutoUploadEnabled(this)
        val configured = DiagnosticUploadManager.isConfigured(this)
        val message = when {
            autoUpload && configured -> "자동 로그 업로드: 켜짐 · 기존 설정 유지"
            configured -> "자동 로그 업로드: 설정됨 · 현재 꺼짐"
            else -> "자동 로그 업로드: 대상 설정 필요 · 로컬 로그는 계속 기록"
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        listOf(
            "백그라운드 차량 서비스\n기존 DolphinService 유지 · 런처에서 자동 시작",
            "자동 업데이트\n런처 시작 시 기존 GitHub Release 확인 엔진 실행",
            message
        ).forEach { line ->
            wrap.addView(TextView(this).apply {
                text = line
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded("#0B171E", 14, "#1B313B")
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        wrap.addView(Button(this).apply {
            text = "지금 업데이트 확인"
            setTextColor(Color.WHITE)
            background = rounded("#12322B", 16, "#2FB69B")
            setOnClickListener { AppUpdateManager.checkForUpdates(this@LauncherActivity, manual = true) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        AlertDialog.Builder(this).setTitle("CORE ENGINE").setView(wrap).setPositiveButton("닫기", null).show()
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, "실행 인텐트를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
        }.onFailure {
            DolphinLogger.e(TAG, "app launch failed: " + packageName, it)
            Toast.makeText(this, "앱 실행 실패", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo(packageName: String) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
        }
    }

    private fun queryLaunchableApps(): List<LaunchApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .filter { it.activityInfo?.packageName != packageName }
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(pkg)
                LaunchApp(pkg, label, runCatching { info.loadIcon(packageManager) }.getOrNull())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }

    private fun seedFavoritesIfNeeded() {
        if (prefs.contains(KEY_FAVORITES)) return
        val hints = listOf("tmap", "nmap", "naver", "kakao", "spotify", "music", "youtube", "chrome")
        val selected = mutableListOf<LaunchApp>()
        hints.forEach { hint ->
            allApps.firstOrNull { it.packageName.contains(hint, true) || it.label.contains(hint, true) }?.let { found ->
                if (selected.none { old -> old.packageName == found.packageName }) selected += found
            }
        }
        allApps.forEach { found ->
            if (selected.size < 8 && selected.none { old -> old.packageName == found.packageName }) selected += found
        }
        prefs.edit().putStringSet(KEY_FAVORITES, selected.take(8).map { it.packageName }.toSet()).apply()
    }

    private fun favoritePackages(): Set<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet().orEmpty()

    private fun favoriteApps(): List<LaunchApp> {
        val set = favoritePackages()
        return allApps.filter { set.contains(it.packageName) }.take(12)
    }

    private fun splitPackage(key: String): String? =
        prefs.getString(key, null)?.takeIf { pkg -> allApps.any { it.packageName == pkg } }

    private fun splitSummary(short: Boolean): String {
        val left = splitPackage(KEY_SPLIT_LEFT)
        val right = splitPackage(KEY_SPLIT_RIGHT)
        return if (left != null && right != null) {
            if (short) "SPLIT READY" else appLabel(left) + " + " + appLabel(right)
        } else {
            if (short) "SPLIT SETUP" else "앱 길게 눌러 좌/우 지정"
        }
    }

    private fun appLabel(pkg: String): String =
        allApps.firstOrNull { it.packageName == pkg }?.label ?: pkg.substringAfterLast('.')

    private fun actionCard(title: String, subtitle: String, symbol: String, action: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded("#0B171E", 20, "#1D3540")
            setOnClickListener { action() }
        }
        card.addView(TextView(this).apply {
            text = symbol
            textSize = 28f
            setTextColor(Color.parseColor("#78F6D3"))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(50), dp(50)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#7F96A2"))
            textSize = 11f
            maxLines = 2
        })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun dockButton(label: String, symbol: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { action() }
            addView(TextView(this@LauncherActivity).apply {
                text = symbol
                textSize = 22f
                setTextColor(Color.parseColor("#78F6D3"))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(34), dp(32)))
            addView(TextView(this@LauncherActivity).apply {
                text = label
                textSize = 10f
                setTextColor(Color.parseColor("#A9BBC4"))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        }

    private fun chip(value: String): TextView = TextView(this).apply {
        text = value
        setTextColor(Color.parseColor("#A9BBC4"))
        textSize = 11f
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = rounded("#0C1A21", 17, "#24404C")
    }

    private fun gridParams(): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = dp(150)
            height = dp(126)
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }

    private fun rounded(fill: String, radiusDp: Int, stroke: String? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fill))
            if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "LAUNCHER_EVOLUTION"
        private const val PREFS = "byd_launcher_evolution"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_SPLIT_LEFT = "split_left"
        private const val KEY_SPLIT_RIGHT = "split_right"
        private const val KEY_SPLIT_RATIO = "split_ratio"
    }
}
