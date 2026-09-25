package com.dolphin.launcher.v1;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LauncherActivity extends Activity {

    public static final String PREFS = "dolphin_launcher_v1";
    public static final String KEY_PENDING_AUTOSTART = "pending_autostart";

    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_SPLIT_LEFT = "split_left";
    private static final String KEY_SPLIT_RIGHT = "split_right";
    private static final String KEY_AUTOSTART_ENABLED = "autostart_enabled";
    private static final String KEY_AUTOSTART_SET = "autostart_set";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout bodyHost;
    private TextView timeView;
    private TextView splitChip;
    private List<AppEntry> apps = new ArrayList<>();

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (timeView != null) {
                timeView.setText(new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date()));
            }
            handler.postDelayed(this, 15000L);
        }
    };

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.parseColor("#03080B"));
        getWindow().setNavigationBarColor(Color.parseColor("#03080B"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        reloadApps();
        seedFavorites();
        buildShell();
        runPendingAutostart();
        VerificationEvidenceRuntime.ensureProcessSession(this, "launcher-process-start");
        VerificationEvidenceRuntime.startAutomaticUploadRuntime(this);
        VerificationEvidenceRuntime.recordPassiveEvent(this, "APP_LAUNCH", "LauncherActivity created");
        VerificationEvidenceRuntime.retryPendingUploadsAsync(this);
        handler.postDelayed(() -> {
            VerificationEvidenceRuntime.queueBundleAndUpload(this, "startup-snapshot");
            AppUpdateManager.checkForUpdates(this, false);
        }, 1800L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadApps();
        if (bodyHost != null) showHome();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        runPendingAutostart();
        AppUpdateManager.resumePendingInstallPermission(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override
    public void onBackPressed() {
        showHome();
    }

    private void buildShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(26), dp(10), dp(26), dp(12));
        shell.setBackground(gradient("#071821", "#02070A"));

        shell.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        bodyHost = new FrameLayout(this);
        shell.addView(bodyHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        shell.addView(buildDock(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));

        setContentView(shell);
        showHome();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = text("DOLPHIN  /  LAUNCHER", 18f, Color.WHITE, true);
        bar.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView version = chip("V1 OTA");
        version.setTextColor(Color.parseColor("#7FFFE0"));
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        versionLp.rightMargin = dp(10);
        bar.addView(version, versionLp);

        splitChip = chip(splitReady() ? "SPLIT READY" : "SPLIT SETUP");
        LinearLayout.LayoutParams splitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        splitLp.rightMargin = dp(14);
        bar.addView(splitChip, splitLp);

        timeView = text(new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date()),
                20f, Color.WHITE, false);
        timeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        bar.addView(timeView, new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.MATCH_PARENT));
        return bar;
    }

    private View buildDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(12), dp(8), dp(12), dp(8));
        dock.setBackground(round("#0E1D25", 24, "#1A3946"));

        dock.addView(dockButton("HOME", "⌂", this::showHome), weighted());
        dock.addView(dockButton("APPS", "▦", this::showAppDrawer), weighted());
        dock.addView(dockButton("SPLIT", "◫", this::launchSplitPair), weighted());
        dock.addView(dockButton("AUTO", "▶", this::showAutoStartManager), weighted());
        dock.addView(dockButton("SET", "⚙", this::showSettings), weighted());
        return dock;
    }

    private void showHome() {
        if (bodyHost == null) return;
        bodyHost.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        bodyHost.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(16), dp(4), dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.addView(text("DRIVE HOME", 38f, Color.WHITE, true));
        TextView subtitle = text("차량에서 앱을 빠르게 실행하고 조합하는 새 홈", 14f,
                Color.parseColor("#91A8B5"), false);
        heroText.addView(subtitle);
        hero.addView(heroText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = chip("CLEAN-SLATE V1");
        badge.setTextColor(Color.parseColor("#7FFFE0"));
        hero.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        content.addView(hero);

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams quickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(112));
        quickLp.topMargin = dp(16);
        content.addView(quick, quickLp);

        quick.addView(actionCard("앱 서랍", apps.size() + "개 앱", "▦", this::showAppDrawer), weighted());
        quick.addView(actionCard("2분할", splitDescription(), "◫", this::launchSplitPair), weighted());
        quick.addView(actionCard("시동 앱", autoStartCount() + "개 등록", "▶", this::showAutoStartManager), weighted());
        quick.addView(actionCard("검증 센터", "Registry v3 · Test ID", "✓", this::openVerificationCenter), weighted());

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView favTitle = text("즐겨찾기", 18f, Color.WHITE, true);
        titleRow.addView(favTitle, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView hint = text("길게 눌러 편집", 12f, Color.parseColor("#68808C"), false);
        hint.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        titleRow.addView(hint, new LinearLayout.LayoutParams(dp(160), dp(42)));

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        titleLp.topMargin = dp(12);
        content.addView(titleRow, titleLp);

        List<AppEntry> favorites = favoriteApps();
        if (favorites.isEmpty()) {
            TextView empty = text("앱서랍에서 앱을 길게 눌러 홈에 고정하세요.", 14f,
                    Color.parseColor("#849AA5"), false);
            empty.setPadding(dp(10), dp(20), 0, dp(20));
            content.addView(empty);
        } else {
            GridLayout grid = appGrid();
            for (AppEntry app : favorites) {
                grid.addView(appTile(app, false), gridParams());
            }
            content.addView(grid);
        }

        TextView footer = text(
                "앱 길게 누르기  →  홈 고정 · 2분할 좌/우 · 시동 자동실행 · 앱 정보",
                12f, Color.parseColor("#5E7681"), false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        footerLp.topMargin = dp(10);
        content.addView(footer, footerLp);
    }

    private void showAppDrawer() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(18), dp(24), dp(18));
        panel.setBackground(round("#0D1920", 22, "#294451"));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("APP DRAWER", 24f, Color.WHITE, true),
                new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView count = chip(apps.size() + " APPS");
        head.addView(count, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        panel.addView(head);

        EditText search = new EditText(this);
        search.setHint("앱 검색");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.parseColor("#718894"));
        search.setTextSize(15f);
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(round("#071116", 16, "#233E4B"));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        searchLp.bottomMargin = dp(12);
        panel.addView(search, searchLp);

        LinearLayout shortcutHost = new LinearLayout(this);
        shortcutHost.setOrientation(LinearLayout.VERTICAL);
        panel.addView(shortcutHost);

        ScrollView scroll = new ScrollView(this);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        GridLayout grid = appGrid();
        scroll.addView(grid);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel)
                .setNegativeButton("닫기", null)
                .create();

        Runnable rebuild = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
            grid.removeAllViews();
            for (AppEntry app : apps) {
                if (q.isEmpty() ||
                        app.label.toLowerCase(Locale.getDefault()).contains(q) ||
                        app.packageName.toLowerCase(Locale.getDefault()).contains(q)) {
                    grid.addView(appTile(app, true), gridParams());
                }
            }
            shortcutHost.removeAllViews();
            if (splitReady()) {
                TextView split = text("◫   2분할 바로가기   " + splitDescription(),
                        14f, Color.WHITE, true);
                split.setGravity(Gravity.CENTER_VERTICAL);
                split.setPadding(dp(18), 0, dp(18), 0);
                split.setBackground(round("#0B2928", 16, "#34BDA2"));
                split.setOnClickListener(v -> {
                    dialog.dismiss();
                    launchSplitPair();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
                lp.bottomMargin = dp(10);
                shortcutHost.addView(split, lp);
            }
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.92f));
                w.setDimAmount(0.72f);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
            rebuild.run();
        });

        dialog.show();
    }

    private View appTile(AppEntry app, boolean compact) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(8), dp(10), dp(8), dp(8));
        tile.setBackground(round("#0A171D", 18, "#1C3440"));
        tile.setClickable(true);
        tile.setFocusable(true);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = compact ? 48 : 56;
        tile.addView(icon, new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));

        TextView label = text(app.label, compact ? 12f : 13f, Color.WHITE, false);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        labelLp.topMargin = dp(7);
        tile.addView(label, labelLp);

        tile.setOnClickListener(v -> launchPackage(app.packageName));
        tile.setOnLongClickListener(v -> {
            showAppActions(app);
            return true;
        });
        return tile;
    }

    private void showAppActions(AppEntry app) {
        boolean favorite = favoritePackages().contains(app.packageName);
        boolean auto = autoStartPackages().contains(app.packageName);
        String[] actions = new String[] {
                favorite ? "홈 고정 해제" : "홈에 고정",
                "2분할 왼쪽 앱으로 지정",
                "2분할 오른쪽 앱으로 지정",
                auto ? "시동 자동실행에서 제거" : "시동 자동실행에 추가",
                "앱 정보"
        };

        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) toggleFavorite(app.packageName);
                    if (which == 1) setSplit(KEY_SPLIT_LEFT, app.packageName, "왼쪽");
                    if (which == 2) setSplit(KEY_SPLIT_RIGHT, app.packageName, "오른쪽");
                    if (which == 3) toggleAutoStart(app.packageName);
                    if (which == 4) openAppInfo(app.packageName);
                })
                .show();
    }

    private void launchSplitPair() {
        String left = prefs.getString(KEY_SPLIT_LEFT, null);
        String right = prefs.getString(KEY_SPLIT_RIGHT, null);

        if (!isLaunchable(left) || !isLaunchable(right) || left.equals(right)) {
            Toast.makeText(this, "앱서랍에서 서로 다른 2분할 좌/우 앱을 먼저 지정하세요.", Toast.LENGTH_LONG).show();
            showAppDrawer();
            return;
        }

        // 1.2.1 real-car evidence: launch bounds alone did not enter BYD split-screen;
        // only one selected app launched. Keep the pair and capture the attempt, but
        // never claim success until a vehicle-compatible split transition is verified.
        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "SPLIT_REVERIFY_REQUIRED",
                "left=" + left + ";right=" + right + ";reason=1.2.1-single-app-only");
        VerificationEvidenceRuntime.queueBundleAndUpload(this, "split-reverify-required");

        new AlertDialog.Builder(this)
                .setTitle("2분할 · 실차 재검증 필요")
                .setMessage("1.2.1 실차에서 기존 방식은 실제 분할 화면으로 전환되지 않고 앱 1개만 실행되는 회귀가 확인되었습니다.\n\n" +
                        "선택한 좌/우 조합은 그대로 보존합니다. 차량 호환 분할 진입 경로가 검증되기 전에는 성공으로 표시하거나 단일 앱을 대신 실행하지 않습니다.")
                .setPositiveButton("확인", null)
                .show();
    }

    private void showAutoStartManager() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(8), dp(18), dp(8));

        Switch master = new Switch(this);
        master.setText("시동 후 등록 앱 자동 실행");
        master.setTextColor(Color.WHITE);
        master.setChecked(prefs.getBoolean(KEY_AUTOSTART_ENABLED, true));
        master.setOnCheckedChangeListener((buttonView, checked) ->
                prefs.edit().putBoolean(KEY_AUTOSTART_ENABLED, checked).apply());
        panel.addView(master, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        List<AppEntry> selected = autoStartApps();
        if (selected.isEmpty()) {
            TextView empty = text("등록된 앱이 없습니다.\n앱서랍에서 앱을 길게 눌러 추가하세요.",
                    14f, Color.parseColor("#8AA0AA"), false);
            empty.setPadding(0, dp(16), 0, dp(18));
            panel.addView(empty);
        } else {
            int order = 1;
            for (AppEntry app : selected) {
                final int delay = 2 + (order - 1) * 3;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                ImageView icon = new ImageView(this);
                icon.setImageDrawable(app.icon);
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

                TextView label = text(app.label + "   +" + delay + "초",
                        14f, Color.WHITE, false);
                label.setPadding(dp(12), 0, dp(10), 0);
                row.addView(label, new LinearLayout.LayoutParams(0, dp(50), 1f));

                Button remove = new Button(this);
                remove.setText("삭제");
                remove.setTextColor(Color.WHITE);
                remove.setTextSize(12f);
                remove.setBackground(round("#33191D", 14, "#70343C"));
                remove.setOnClickListener(v -> {
                    toggleAutoStart(app.packageName);
                    Toast.makeText(this, app.label + " 제거", Toast.LENGTH_SHORT).show();
                });
                row.addView(remove, new LinearLayout.LayoutParams(dp(76), dp(38)));

                panel.addView(row);
                order++;
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);

        new AlertDialog.Builder(this)
                .setTitle("시동 자동실행")
                .setView(scroll)
                .setPositiveButton("앱서랍에서 추가", (d, w) -> showAppDrawer())
                .setNegativeButton("닫기", null)
                .show();
    }

    private void openVerificationCenter() {
        startActivity(new Intent(this, VerificationCenterActivity.class));
    }

    private void showSettings() {
        String[] actions = new String[] {
                "기본 HOME 런처 선택",
                "앱 업데이트 확인",
                "실차 검증 센터",
                "즐겨찾기 초기화",
                "2분할 지정 초기화",
                "Dolphin Launcher V1 정보"
        };

        new AlertDialog.Builder(this)
                .setTitle("Dolphin Launcher V1")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        try {
                            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                        } catch (Exception e) {
                            Toast.makeText(this, "HOME 설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                        }
                    }
                    if (which == 1) {
                        AppUpdateManager.checkForUpdates(this, true);
                    }
                    if (which == 2) {
                        openVerificationCenter();
                    }
                    if (which == 3) {
                        prefs.edit().remove(KEY_FAVORITES).apply();
                        seedFavorites();
                        showHome();
                    }
                    if (which == 4) {
                        prefs.edit().remove(KEY_SPLIT_LEFT).remove(KEY_SPLIT_RIGHT).apply();
                        refreshSplitChip();
                        showHome();
                    }
                    if (which == 5) {
                        new AlertDialog.Builder(this)
                                .setTitle("Dolphin Launcher V1 OTA")
                                .setMessage("독립 패키지: com.dolphin.launcher.v1\n" +
                                        "버전: " + BuildConfig.VERSION_NAME + "\n\n" +
                                        "Registry v3 + 앱내 서명검증 OTA 업데이트 통합 빌드입니다.")
                                .setPositiveButton("확인", null)
                                .show();
                    }
                })
                .show();
    }

    private void runPendingAutostart() {
        if (!prefs.getBoolean(KEY_PENDING_AUTOSTART, false)) return;
        prefs.edit().putBoolean(KEY_PENDING_AUTOSTART, false).apply();
        if (!prefs.getBoolean(KEY_AUTOSTART_ENABLED, true)) return;

        List<AppEntry> list = autoStartApps();
        int index = 0;
        for (AppEntry app : list) {
            long delay = 1800L + (index * 3000L);
            handler.postDelayed(() -> launchPackage(app.packageName), delay);
            index++;
        }

        if (!list.isEmpty()) {
            Toast.makeText(this, "시동 자동실행 " + list.size() + "개 예약", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFavorite(String pkg) {
        LinkedHashSet<String> set = new LinkedHashSet<>(favoritePackages());
        if (!set.add(pkg)) set.remove(pkg);
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply();
        showHome();
    }

    private void toggleAutoStart(String pkg) {
        LinkedHashSet<String> set = new LinkedHashSet<>(autoStartPackages());
        boolean added = set.add(pkg);
        if (!added) set.remove(pkg);
        prefs.edit().putStringSet(KEY_AUTOSTART_SET, set).apply();
        Toast.makeText(this, appLabel(pkg) + (added ? " · 시동 자동실행 추가" : " · 시동 자동실행 제거"),
                Toast.LENGTH_SHORT).show();
        showHome();
    }

    private void setSplit(String key, String pkg, String side) {
        prefs.edit().putString(key, pkg).apply();
        refreshSplitChip();
        Toast.makeText(this, side + " 2분할: " + appLabel(pkg), Toast.LENGTH_SHORT).show();
    }

    private void refreshSplitChip() {
        if (splitChip != null) splitChip.setText(splitReady() ? "SPLIT READY" : "SPLIT SETUP");
    }

    private boolean splitReady() {
        String left = prefs.getString(KEY_SPLIT_LEFT, null);
        String right = prefs.getString(KEY_SPLIT_RIGHT, null);
        return isLaunchable(left) && isLaunchable(right) && !left.equals(right);
    }

    private String splitDescription() {
        if (!splitReady()) return "앱 길게 눌러 좌/우 지정";
        return appLabel(prefs.getString(KEY_SPLIT_LEFT, "")) + " + " +
                appLabel(prefs.getString(KEY_SPLIT_RIGHT, ""));
    }

    private void launchPackage(String pkg) {
        if (pkg == null) return;
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Toast.makeText(this, "앱을 실행할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "앱 실행 실패", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppInfo(String pkg) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    private void reloadApps() {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> infos = getPackageManager().queryIntentActivities(
                launcherIntent, PackageManager.MATCH_ALL);
        List<AppEntry> next = new ArrayList<>();

        for (ResolveInfo info : infos) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (getPackageName().equals(pkg)) continue;

            String label;
            Drawable icon;
            try {
                label = info.loadLabel(getPackageManager()).toString();
            } catch (Exception e) {
                label = pkg;
            }
            try {
                icon = info.loadIcon(getPackageManager());
            } catch (Exception e) {
                icon = null;
            }

            boolean duplicate = false;
            for (AppEntry old : next) {
                if (old.packageName.equals(pkg)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) next.add(new AppEntry(pkg, label, icon));
        }

        Collections.sort(next, Comparator.comparing(a ->
                a.label.toLowerCase(Locale.getDefault())));
        apps = next;
    }

    private boolean isLaunchable(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    private void seedFavorites() {
        if (prefs.contains(KEY_FAVORITES)) return;

        String[] hints = new String[] {
                "tmap", "naver", "kakao", "spotify", "music", "youtube", "chrome"
        };
        LinkedHashSet<String> chosen = new LinkedHashSet<>();

        for (String hint : hints) {
            for (AppEntry app : apps) {
                if (app.packageName.toLowerCase(Locale.getDefault()).contains(hint) ||
                        app.label.toLowerCase(Locale.getDefault()).contains(hint)) {
                    chosen.add(app.packageName);
                    break;
                }
            }
        }

        for (AppEntry app : apps) {
            if (chosen.size() >= 8) break;
            chosen.add(app.packageName);
        }

        prefs.edit().putStringSet(KEY_FAVORITES, chosen).apply();
    }

    private Set<String> favoritePackages() {
        Set<String> raw = prefs.getStringSet(KEY_FAVORITES, Collections.emptySet());
        return raw == null ? Collections.emptySet() : new LinkedHashSet<>(raw);
    }

    private Set<String> autoStartPackages() {
        Set<String> raw = prefs.getStringSet(KEY_AUTOSTART_SET, Collections.emptySet());
        return raw == null ? Collections.emptySet() : new LinkedHashSet<>(raw);
    }

    private List<AppEntry> favoriteApps() {
        Set<String> set = favoritePackages();
        List<AppEntry> result = new ArrayList<>();
        for (AppEntry app : apps) {
            if (set.contains(app.packageName)) result.add(app);
        }
        return result;
    }

    private List<AppEntry> autoStartApps() {
        Set<String> set = autoStartPackages();
        List<AppEntry> result = new ArrayList<>();
        for (AppEntry app : apps) {
            if (set.contains(app.packageName)) result.add(app);
        }
        return result;
    }

    private int autoStartCount() {
        return autoStartApps().size();
    }

    private String appLabel(String pkg) {
        if (pkg == null) return "-";
        for (AppEntry app : apps) {
            if (app.packageName.equals(pkg)) return app.label;
        }
        int dot = pkg.lastIndexOf('.');
        return dot >= 0 ? pkg.substring(dot + 1) : pkg;
    }

    private GridLayout appGrid() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        return grid;
    }

    private GridLayout.LayoutParams gridParams() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(150);
        lp.height = dp(126);
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        return lp;
    }

    private View actionCard(String title, String subtitle, String symbol, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(round("#0A171D", 20, "#1B3743"));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());

        TextView icon = text(symbol, 28f, Color.parseColor("#7FFFE0"), true);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8), 0, 0, 0);
        labels.addView(text(title, 15f, Color.WHITE, true));
        TextView sub = text(subtitle, 11f, Color.parseColor("#7F98A4"), false);
        sub.setMaxLines(2);
        labels.addView(sub);
        card.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return card;
    }

    private View dockButton(String label, String symbol, Runnable action) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());

        TextView icon = text(symbol, 22f, Color.parseColor("#7FFFE0"), true);
        icon.setGravity(Gravity.CENTER);
        button.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(32)));

        TextView title = text(label, 10f, Color.parseColor("#A8BBC4"), false);
        title.setGravity(Gravity.CENTER);
        button.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
        return button;
    }

    private TextView chip(String value) {
        TextView view = text(value, 11f, Color.parseColor("#A8BBC4"), false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(round("#0B1A21", 17, "#24424F"));
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        return lp;
    }

    private GradientDrawable gradient(String start, String end) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor(start), Color.parseColor(end)});
        return drawable;
    }

    private GradientDrawable round(String fill, int radiusDp, String stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setColor(Color.parseColor(fill));
        if (stroke != null) {
            drawable.setStroke(dp(1), Color.parseColor(stroke));
        }
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
