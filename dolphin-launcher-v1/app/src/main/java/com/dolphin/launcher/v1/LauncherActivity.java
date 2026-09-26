package com.dolphin.launcher.v1;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final String EXTRA_SPLIT_LEFT = "split_left_package";
    private static final String EXTRA_SPLIT_RIGHT = "split_right_package";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout bodyHost;
    private TextView timeView;
    private TextView splitChip;
    private List<AppEntry> apps = new ArrayList<>();
    private VehicleReadOnlyMonitor vehicleMonitor;
    private VehiclePromptPlayer vehiclePromptPlayer;
    private OwnedAudioEqualizer ownedAudioEqualizer;
    private OwnedSoundPosition ownedSoundPosition;
    private BroadcastReceiver splitCameraEvidenceReceiver;
    private boolean splitCameraEvidenceReceiverRegistered;
    private final Runnable splitPostCameraReadback = this::captureSplitPostCameraReadback;
    private volatile Integer tpmsFlKpa, tpmsFrKpa, tpmsRlKpa, tpmsRrKpa;
    private volatile Integer vehicleGearRaw, vehicleSpeedRaw;
    private volatile Integer vehicleTurnLeftRaw, vehicleTurnRightRaw;
    private TextView homeMediaStatus, homeMediaSubtitle;
    private TextView homeSafetyStatus, homeSafetySubtitle;
    private TextView homeVehicleStatus, homeVehicleSubtitle;
    private CockpitPanelGraphicView homeMediaGraphic, homeSafetyGraphic, homeVehicleGraphic;
    private HomeHeroGraphicView homeHeroGraphic;
    private int lastHeroMediaState=-1, lastHeroNavState=-1, lastHeroVehicleState=-1;
    private final TextView[] homeTpmsPressure = new TextView[4];
    private final TextView[] homeTpmsState = new TextView[4];
    private final TyreGaugeView[] homeTpmsGauge = new TyreGaugeView[4];
    private final VehicleVoicePolicy.Output vehicleVoiceOutput = (promptId, phrase) -> {
        if (vehiclePromptPlayer == null) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "VOICE_PROMPT_NOT_READY", promptId + " phrase=" + phrase);
            return;
        }
        vehiclePromptPlayer.play(promptId, phrase);
    };

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (timeView != null) {
                timeView.setText(new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date()));
            }
            handler.postDelayed(this, 15000L);
        }
    };

    private final Runnable homeLiveTick = new Runnable() {
        @Override
        public void run() {
            refreshHomeLiveBindings();
            handler.postDelayed(this, 2000L);
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
        vehiclePromptPlayer = new VehiclePromptPlayer(this);
        ownedAudioEqualizer = new OwnedAudioEqualizer(this);
        ownedSoundPosition = new OwnedSoundPosition(this);
        vehiclePromptPlayer.preload(VehicleVoicePolicy.promptIds());
        getWindow().setStatusBarColor(Color.parseColor("#03080B"));
        getWindow().setNavigationBarColor(Color.parseColor("#03080B"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        reloadApps();
        seedFavorites();
        buildShell();
        VerificationEvidenceRuntime.ensureProcessSession(this, "launcher-process-start");
        boolean shortcutLaunch = isSplitShortcutIntent(getIntent()) || isAppShortcutIntent(getIntent());
        handleSplitShortcutIntent(getIntent());
        handleAppShortcutIntent(getIntent());
        if (!shortcutLaunch) runPendingAutostart();
        VerificationEvidenceRuntime.startAutomaticUploadRuntime(this);
        VerificationEvidenceRuntime.recordPassiveEvent(this, "APP_LAUNCH", "LauncherActivity created");
        DisplayDiagnostics.capture(this);
        DisplayDiagnostics.captureNotificationAccess(this);
        DisplayDiagnostics.captureDisplayInventory(this);
        InstrumentCapabilityProbe.capture(this);
        SurroundingVisionCapabilityProbe.capture(this);
        AudioCapabilityProbe.capture(this);
        DriverAudioCapabilityProbe.capture(this);
        InteriorLightCapabilityProbe.capture(this);
        BlockedCapabilityRuntime.capture(this);
        DisplayDiagnostics.captureLaunchableAppOrientations(this);
        VerificationEvidenceRuntime.retryPendingUploadsAsync(this);
        startVehicleReadOnlyRuntime();
        startSplitCameraEvidenceRuntime();
        handler.postDelayed(() -> {
            VerificationEvidenceRuntime.queueBundleAndUpload(this, "startup-snapshot");
            AppUpdateManager.checkForUpdates(this, false);
        }, 1800L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSplitShortcutIntent(intent);
        handleAppShortcutIntent(intent);
    }

    private boolean isAppShortcutIntent(Intent intent) {
        return intent != null && "com.dolphin.launcher.v1.OPEN_PACKAGE".equals(intent.getAction());
    }

    private void handleAppShortcutIntent(Intent intent) {
        if (!isAppShortcutIntent(intent)) return;
        String pkg = intent.getStringExtra("package");
        VerificationEvidenceRuntime.recordPassiveEvent(this, "APP_SHORTCUT_INVOKED", "package=" + pkg);
        if (pkg == null || pkg.trim().isEmpty()) {
            VerificationEvidenceRuntime.recordPassiveEvent(this, "APP_SHORTCUT_LAUNCH_FAILED", "reason=missing-package");
        } else {
            VerificationEvidenceRuntime.recordPassiveEvent(this, "APP_SHORTCUT_LAUNCH_DISPATCHED", "package=" + pkg + ";verification=required");
            launchPackage(pkg);
        }
        intent.setAction(Intent.ACTION_MAIN);
    }

    private boolean isSplitShortcutIntent(Intent intent) {
        return intent != null
                && "com.dolphin.launcher.v1.LAUNCH_SPLIT_SHORTCUT".equals(intent.getAction());
    }

    private void handleSplitShortcutIntent(Intent intent) {
        if (intent == null || !"com.dolphin.launcher.v1.LAUNCH_SPLIT_SHORTCUT".equals(intent.getAction())) return;
        String left = intent.getStringExtra(EXTRA_SPLIT_LEFT);
        String right = intent.getStringExtra(EXTRA_SPLIT_RIGHT);
        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "SPLIT_SHORTCUT_INVOKED",
                "captured_left=" + left + ";captured_right=" + right);
        // Execute the pair captured when this shortcut was created, not whatever pair
        // happens to be selected in preferences later.
        launchSplitPair(left, right);
        intent.setAction(Intent.ACTION_MAIN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadApps();
        if (bodyHost != null) showHome();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        handler.removeCallbacks(homeLiveTick);
        handler.post(homeLiveTick);
        if (!isSplitShortcutIntent(getIntent()) && !isAppShortcutIntent(getIntent())) runPendingAutostart();
        AppUpdateManager.resumePendingInstallPermission(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(homeLiveTick);
    }

    private void startVehicleReadOnlyRuntime() {
        if (vehicleMonitor != null) return;
        vehicleMonitor = new VehicleReadOnlyMonitor(this, new VehicleReadOnlyMonitor.Listener() {
            @Override public void onGear(String value) {
                VehicleVoicePolicy.gear(LauncherActivity.this, vehicleVoiceOutput, value);
            }
            @Override public void onSpeedRaw(Integer raw) {
                vehicleSpeedRaw = raw;
                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "LIVE_VEHICLE_SPEED_RAW",
                        "source=BYDAutoSpeedDevice.getCurrentSpeed;speed_raw=" + raw
                                + ";unit=unverified;ui=raw-only");
            }
            @Override public void onDriveMode(String value) {
                VehicleVoicePolicy.driveMode(LauncherActivity.this, vehicleVoiceOutput, value);
            }
            @Override public void onRegen(String value) {
                VehicleVoicePolicy.regen(LauncherActivity.this, vehicleVoiceOutput, value);
            }
            @Override public void onEpb(boolean held) {
                VehicleVoicePolicy.epb(LauncherActivity.this, vehicleVoiceOutput, held);
            }
            @Override public void onAvhRaw(Integer raw) {
                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "AUTOHOLD_RAW_TRANSITION",
                        "avh_raw=" + raw + ";voice=suppressed-pending-correlation");
            }
            @Override public void onAvhSwitchRaw(Integer raw) {
                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "AUTOHOLD_SWITCH_RAW_TRANSITION",
                        "avh_switch_raw=" + raw
                                + ";hold_state_source=adas.avh;voice=suppressed-pending-correlation");
            }
            @Override public void onBsdRaw(Integer raw) {
                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "BSD_RAW_TRANSITION",
                        "bsd_raw=" + raw + ";voice=suppressed-pending-side-correlation");
            }
            @Override public void onTurnRaw(Integer leftRaw, Integer rightRaw) {
                vehicleTurnLeftRaw = leftRaw;
                vehicleTurnRightRaw = rightRaw;
                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "TURN_SIGNAL_RAW_TRANSITION",
                        "left_raw=" + leftRaw + ";right_raw=" + rightRaw
                                + ";purpose=bsd-side-correlation;voice=suppressed");
            }
            @Override public void onSnowRaw(Integer raw) {
                String normalized = Integer.valueOf(2).equals(raw) ? "ON"
                        : Integer.valueOf(1).equals(raw) ? "OFF" : "UNMAPPED";
                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "SNOW_RAW_TRANSITION",
                        "road_surface_raw=" + raw + ";normalized_candidate=" + normalized);
                if ("ON".equals(normalized) || "OFF".equals(normalized)) {
                    VerificationEvidenceRuntime.recordPassiveEvent(
                            LauncherActivity.this, "SNOW_EVIDENCE_MAP",
                            "road_surface_raw=" + raw + ";normalized=" + normalized
                                    + ";source=real-car-20260917+20260919");
                    VehicleVoicePolicy.snow(
                            LauncherActivity.this, vehicleVoiceOutput, "ON".equals(normalized));
                } else {
                    VerificationEvidenceRuntime.recordPassiveEvent(
                            LauncherActivity.this, "SNOW_MODE_UNMAPPED",
                            "road_surface_raw=" + raw + ";voice=suppressed");
                }
            }
            @Override public void onIccCandidateRaw(Integer raw) {
                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "ICC_TJA_RAW_TRANSITION",
                        "tja_raw=" + raw + ";voice=suppressed-pending-icc-correlation");
            }
            @Override public void onFrontRadarRaw(Integer leftMid, Integer rightMid) {
                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "LEADING_OBJECT_RADAR_RAW",
                        "area7=" + leftMid + ";area8=" + rightMid
                                + ";voice=suppressed;classification=unknown-object");
            }
            @Override public void onTpmsRaw(
                    Integer fl, Integer fr, Integer rl, Integer rr,
                    Integer flState, Integer frState, Integer rlState, Integer rrState,
                    Integer flSignal, Integer frSignal, Integer rlSignal, Integer rrSignal) {
                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "TPMS_RAW",
                        "fl=" + fl + ";fr=" + fr + ";rl=" + rl + ";rr=" + rr
                                + ";unit=kPa-api-contract"
                                + ";pressure_state=" + flState + "," + frState + "," + rlState + "," + rrState
                                + ";signal_state=" + flSignal + "," + frSignal + "," + rlSignal + "," + rrSignal
                                + ";vehicle_unit_reverify=true");
                tpmsFlKpa = validTpmsKpa(fl); tpmsFrKpa = validTpmsKpa(fr);
                tpmsRlKpa = validTpmsKpa(rl); tpmsRrKpa = validTpmsKpa(rr);
            }
            @Override public void onRaw(String signal, Integer raw) {
                if ("gear.candidate.unmapped".equals(signal)) {
                    vehicleGearRaw = raw;
                    VerificationEvidenceRuntime.recordPassiveEvent(
                            LauncherActivity.this, "LIVE_VEHICLE_GEAR_RAW",
                            "source=BYDAutoGearboxDevice.getCurrentGear;gear_raw=" + raw
                                    + ";mapping=unverified;ui=raw-only");
                }
                // AVH/BSD and other not-yet-normalized signals remain evidence-only.
            }
        });
        vehicleMonitor.start();
        VerificationEvidenceRuntime.recordPassiveEvent(this, "VEHICLE_MONITOR", "read-only runtime started");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(splitPostCameraReadback);
        if (splitCameraEvidenceReceiverRegistered && splitCameraEvidenceReceiver != null) {
            try {
                unregisterReceiver(splitCameraEvidenceReceiver);
            } catch (Throwable ignored) {}
            splitCameraEvidenceReceiverRegistered = false;
            splitCameraEvidenceReceiver = null;
        }
        if (vehicleMonitor != null) {
            vehicleMonitor.stop();
            vehicleMonitor = null;
        }
        if (vehiclePromptPlayer != null) {
            vehiclePromptPlayer.release();
            vehiclePromptPlayer = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        showHome();
    }

    private void startSplitCameraEvidenceRuntime() {
        if (splitCameraEvidenceReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction("byd.intent.action.AUTO_VIDEO_ON");
        filter.addAction("byd.intent.action.pano");
        filter.addAction("byd.intent.action.AUTO_EXIT_PANO");

        splitCameraEvidenceReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                int autoVideo = intent.getIntExtra("autovideo_on", Integer.MIN_VALUE);
                int panoState = intent.getIntExtra("panoState", Integer.MIN_VALUE);
                boolean explicitExit = "byd.intent.action.AUTO_EXIT_PANO".equals(action);
                boolean stateExit = ("byd.intent.action.AUTO_VIDEO_ON".equals(action)
                        || "byd.intent.action.pano".equals(action))
                        && (autoVideo == 0 || panoState == 0);
                boolean exit = explicitExit || stateExit;

                VerificationEvidenceRuntime.recordPassiveEvent(
                        LauncherActivity.this, "SPLIT_CAMERA_EVENT",
                        "action=" + action
                                + ";autovideo_on=" + autoVideo
                                + ";panoState=" + panoState
                                + ";exit_candidate=" + exit
                                + ";actuation=false");

                if (exit) {
                    handler.removeCallbacks(splitPostCameraReadback);
                    handler.postDelayed(splitPostCameraReadback, 700L);
                }
            }
        };

        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(splitCameraEvidenceReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(splitCameraEvidenceReceiver, filter);
            }
            splitCameraEvidenceReceiverRegistered = true;
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "SPLIT_CAMERA_EVIDENCE_RUNTIME",
                    "registered=true;actions=3;restore_enabled=false;actuation=false");
        } catch (Throwable t) {
            splitCameraEvidenceReceiverRegistered = false;
            splitCameraEvidenceReceiver = null;
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "SPLIT_CAMERA_EVIDENCE_RUNTIME_FAILED",
                    "error=" + t.getClass().getSimpleName()
                            + ";restore_enabled=false;actuation=false");
        }
    }

    private void captureSplitPostCameraReadback() {
        String left = prefs == null ? null : prefs.getString(KEY_SPLIT_LEFT, null);
        String right = prefs == null ? null : prefs.getString(KEY_SPLIT_RIGHT, null);
        if (!isLaunchable(left) || !isLaunchable(right) || left.equals(right)) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "SPLIT_POST_CAMERA_READBACK",
                    "configured_pair=false;readback_verified=false;restore_attempted=false;actuation=false");
            return;
        }

        SplitExecutionBridge.Result result =
                SplitExecutionBridge.inspectCurrentPair(this, left, right);
        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "SPLIT_POST_CAMERA_READBACK",
                "left=" + left + ";right=" + right
                        + ";readback_verified=" + result.success
                        + ";" + result.detail
                        + ";restore_attempted=false;actuation=false");

        if ("WIN-SPLIT-001".equals(VerificationEvidenceRuntime.activeTestId(this))) {
            VerificationEvidenceRuntime.queueBundleAndUpload(
                    this, result.success
                            ? "split-post-camera-readback-preserved"
                            : "split-post-camera-readback-lost");
        }
    }

    private void buildShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(8), dp(22), dp(12));
        shell.setBackground(gradient("#06141B", "#010406"));

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

        LinearLayout brandBox = new LinearLayout(this);
        brandBox.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("DOLPHIN", 19f, Color.WHITE, true);
        brand.setLetterSpacing(0.14f);
        brandBox.addView(brand);
        TextView brandSub = text("DRIVE OS  ·  V1 EVOLUTION", 9.5f,
                Color.parseColor("#6F909B"), false);
        brandSub.setLetterSpacing(0.10f);
        brandBox.addView(brandSub);
        bar.addView(brandBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
        dock.setPadding(dp(10), dp(8), dp(10), dp(8));
        dock.setBackground(gradientRound(
                new String[]{"#10252F","#08151B","#050B0F"}, 26, "#234B5B"));

        dock.addView(dockButton("HOME", "⌂", this::showHome), weighted());
        dock.addView(dockButton("APPS", "▦", this::showAppDrawer), weighted());
        dock.addView(dockButton("SPLIT", "◫", this::launchSplitPair), weighted());
        dock.addView(dockButton("AUTO", "▶", this::showAutoStartManager), weighted());
        dock.addView(dockButton("SET", "⚙", this::showSettings), weighted());
        return dock;
    }

    private void showHome() {
        if (bodyHost == null) return;
        homeMediaStatus = null;
        homeMediaSubtitle = null;
        homeSafetyStatus = null;
        homeSafetySubtitle = null;
        homeVehicleStatus = null;
        homeMediaGraphic = null;
        homeSafetyGraphic = null;
        homeVehicleGraphic = null;
        homeHeroGraphic = null;
        homeVehicleSubtitle = null;
        for (int i = 0; i < 4; i++) {
            homeTpmsPressure[i] = null;
            homeTpmsState[i] = null;
            homeTpmsGauge[i] = null;
        }
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

        View hero = buildPremiumHero();
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(238));
        heroLp.bottomMargin = dp(12);
        content.addView(hero, heroLp);

        content.addView(sectionHeader("PRIMARY COCKPIT", "Media · Safety · Vehicle"));
        View cockpit = buildCockpitDeck();
        LinearLayout.LayoutParams cockpitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(168));
        cockpitLp.bottomMargin = dp(10);
        content.addView(cockpit, cockpitLp);

        content.addView(sectionHeader("QUICK CONTROL", "Launch · Split · Automation · Evidence"));

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

        content.addView(sectionHeader("SOUND LAB", "App-owned audio · safe preview"));

        LinearLayout audio = new LinearLayout(this);
        audio.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams audioLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88));
        audioLp.topMargin = dp(12);
        content.addView(audio, audioLp);
        audio.addView(actionCard("사운드 EQ", "저음 · 중음 · 고음", "≋", this::showEqualizer), weighted());
        audio.addView(actionCard("음장 위치", "BETA · 미리보기", "◎", this::showSoundPosition), weighted());

        content.addView(sectionHeader("TYRE MONITOR", "Live read-only pressure layer"));

        LinearLayout tpms = new LinearLayout(this);
        tpms.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tpmsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88));
        tpmsLp.topMargin = dp(12);
        content.addView(tpms, tpmsLp);
        tpms.addView(tpmsCard("FL","앞좌측",tpmsFlKpa), weighted());
        tpms.addView(tpmsCard("FR","앞우측",tpmsFrKpa), weighted());
        tpms.addView(tpmsCard("RL","뒤좌측",tpmsRlKpa), weighted());
        tpms.addView(tpmsCard("RR","뒤우측",tpmsRrKpa), weighted());

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

        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "PREMIUM_HMI_RENDER",
                "variant=glass-vector-v2;hero=canvas-vector;bitmap_assets=false"
                        + ";hero_copy_dp=" + heroCopyWidthDp()
                        + ";hero_source_rail=MEDIA,NAV,VEH"
                        + ";cockpit_panels=3;layout=media-safety-vehicle"
                        + ";cockpit_state_source=verification_registry"
                        + ";nav_source_status=notification-provenance-only"
                        + ";cockpit_graphic_state=registry-aware"
                        + ";live_binding_ms=2000"
                        + ";tpms_cards=4;tpms_visual=vector-wheel-gauge"
                        + ";quick_cards=4;dock_items=5");

        TextView footer = text(
                "앱 길게 누르기  →  홈 고정 · 2분할 좌/우 · 시동 자동실행 · 앱 정보",
                12f, Color.parseColor("#5E7681"), false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        footerLp.topMargin = dp(10);
        content.addView(footer, footerLp);

        UiFrameTimingRuntime.start(this, "home");
    }

    private LinearLayout hmiDialogHeader(String title, String subtitle, String glyph) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title,22f,Color.WHITE,true);
        titleView.setLetterSpacing(0.07f);
        copy.addView(titleView);
        copy.addView(text(subtitle,10f,Color.parseColor("#6E8C97"),false));
        head.addView(copy,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));

        HmiGlyphView icon = new HmiGlyphView(this,glyph);
        icon.setAccentColor(Color.parseColor("#8CFFE8"));
        icon.setBackground(gradientRound(
                new String[]{"#173F46","#0B252A"},17,"#2E615F"));
        head.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(58));
        lp.bottomMargin=dp(8);
        head.setLayoutParams(lp);
        return head;
    }

    private TextView hmiInfoStrip(String value) {
        TextView strip=text(value,10.5f,Color.parseColor("#A2BBC3"),false);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(12),0,dp(12),0);
        strip.setBackground(gradientRound(
                new String[]{"#0D2027","#071318"},14,"#254957"));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(38));
        lp.bottomMargin=dp(10);
        strip.setLayoutParams(lp);
        return strip;
    }

    private void showSoundPosition() {
        if(ownedSoundPosition==null) ownedSoundPosition=new OwnedSoundPosition(this);
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22),dp(18),dp(22),dp(18));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"},24,"#315A68"));
        LinearLayout soundHead = hmiDialogHeader(
                "SOUND POSITION","Balance · Fader · Driver preset","◎");
        panel.addView(soundHead);
        TextView guard = hmiInfoStrip("차량 DSP 쓰기 차단 · 앱 소유 오디오 미리보기");
        panel.addView(guard);
        addPositionAxis(panel,"좌  BALANCE  우",ownedSoundPosition.balance(),true);
        addPositionAxis(panel,"뒤  FADER  앞",ownedSoundPosition.fader(),false);
        Button driver=button("운전석 중심 프리셋");
        driver.setBackground(pressableGradientRound(
                new String[]{"#12352F","#0A211D"},
                new String[]{"#19493F","#0D302A"},
                15,"#3B8E75"));
        driver.setOnClickListener(v->{ ownedSoundPosition.driverCenter(); Toast.makeText(this,"운전석 중심 요청값을 저장했습니다. 차량 적용은 아직 차단됩니다.",Toast.LENGTH_SHORT).show(); });
        panel.addView(driver,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
        VerificationEvidenceRuntime.recordPassiveEvent(
                this,"SOUND_POSITION_HMI_RENDER","variant=glass-vector-v2");
        new AlertDialog.Builder(this).setView(panel).setPositiveButton("완료",null)
                .setNeutralButton("중앙 초기화",(d,w)->ownedSoundPosition.reset()).show();
    }

    private void addPositionAxis(LinearLayout panel,String label,int value,boolean balance) {
        TextView title=text(label+"   "+value,15f,Color.WHITE,true);
        panel.addView(title);
        SeekBar bar=new SeekBar(this); bar.setMax(20); bar.setProgress(value+10);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean fromUser){
                if(!fromUser)return; int v=p-10;
                int b=ownedSoundPosition.balance(), f=ownedSoundPosition.fader();
                ownedSoundPosition.set(balance?v:b,balance?f:v);
                title.setText(label+"   "+v);
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        panel.addView(bar,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)));
    }

    private void showEqualizer() {
        if (ownedAudioEqualizer == null) ownedAudioEqualizer = new OwnedAudioEqualizer(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22),dp(18),dp(22),dp(18));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"},24,"#315A68"));
        LinearLayout eqHead = hmiDialogHeader(
                "SOUND EQ","Bass · Mid · Treble","≋");
        panel.addView(eqHead);
        TextView guard = hmiInfoStrip("앱 소유 오디오 · BYD 차량 DSP 쓰기 차단");
        panel.addView(guard);
        addEqBand(panel,"저음  BASS",ownedAudioEqualizer.bass(),0);
        addEqBand(panel,"중음  MID",ownedAudioEqualizer.mid(),1);
        addEqBand(panel,"고음  TREBLE",ownedAudioEqualizer.treble(),2);
        Button test=button("EQ 테스트 · 100 Hz → 1 kHz → 8 kHz");
        test.setBackground(pressableGradientRound(
                new String[]{"#12352F","#0A211D"},
                new String[]{"#19493F","#0D302A"},
                15,"#3B8E75"));
        test.setOnClickListener(v->{
            VerificationEvidenceRuntime.recordPassiveEvent(this,"EQ_AUDIBLE_TEST_REQUESTED",ownedAudioEqualizer.snapshot());
            EqualizerAudibleTest.play(this,ownedAudioEqualizer);
        });
        LinearLayout.LayoutParams testLp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48));
        testLp.topMargin=dp(10);
        panel.addView(test,testLp);
        VerificationEvidenceRuntime.recordPassiveEvent(
                this,"SOUND_EQ_HMI_RENDER","variant=glass-vector-v2");
        new AlertDialog.Builder(this).setView(panel).setPositiveButton("완료",null)
                .setNeutralButton("초기화",(d,w)->{ ownedAudioEqualizer.reset(); Toast.makeText(this,"EQ를 0 / 0 / 0으로 초기화했습니다.",Toast.LENGTH_SHORT).show(); })
                .show();
    }

    private void addEqBand(LinearLayout panel,String label,int value,int band) {
        TextView title=text(label+"   "+(value>0?"+":"")+value+" dB",15f,Color.WHITE,true);
        panel.addView(title);
        SeekBar bar=new SeekBar(this);
        bar.setMax(OwnedAudioEqualizer.MAX_DB-OwnedAudioEqualizer.MIN_DB);
        bar.setProgress(value-OwnedAudioEqualizer.MIN_DB);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar seekBar,int progress,boolean fromUser){
                if(!fromUser) return;
                int db=progress+OwnedAudioEqualizer.MIN_DB;
                int b=ownedAudioEqualizer.bass(),m=ownedAudioEqualizer.mid(),t=ownedAudioEqualizer.treble();
                if(band==0)b=db; else if(band==1)m=db; else t=db;
                ownedAudioEqualizer.setBands(b,m,t);
                title.setText(label+"   "+(db>0?"+":"")+db+" dB");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar){}
            @Override public void onStopTrackingTouch(SeekBar seekBar){}
        });
        panel.addView(bar,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54)));
    }

    private void showAppDrawer() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(18), dp(24), dp(18));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"}, 24, "#315A68"));
        panel.setElevation(dp(4));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout drawerTitle = new LinearLayout(this);
        drawerTitle.setOrientation(LinearLayout.VERTICAL);
        TextView drawerHead = text("APP DRAWER", 23f, Color.WHITE, true);
        drawerHead.setLetterSpacing(0.06f);
        drawerTitle.addView(drawerHead);
        drawerTitle.addView(text("Launch · Pin · Split · Autostart", 10f,
                Color.parseColor("#6E8C97"), false));
        head.addView(drawerTitle,
                new LinearLayout.LayoutParams(0, dp(54), 1f));
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
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(gradientRound(
                new String[]{"#0B1D24","#061117"}, 17, "#274B58"));
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
                TextView split = text("◫   2분할 실행   " + splitDescription(),
                        14f, Color.WHITE, true);
                split.setGravity(Gravity.CENTER_VERTICAL);
                split.setPadding(dp(18), 0, dp(18), 0);
                split.setBackground(pressableGradientRound(
                        new String[]{"#103A37","#09241F"},
                        new String[]{"#175049","#0C312B"},
                        17, "#3BC9AF"));
                split.setOnClickListener(v -> {
                    dialog.dismiss();
                    launchSplitPair();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
                lp.bottomMargin = dp(10);
                shortcutHost.addView(split, lp);

                Button pin = button("순정 앱서랍에 2분할 바로가기 만들기");
                pin.setOnClickListener(v -> createPinnedSplitShortcut());
                LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
                pinLp.bottomMargin = dp(10);
                shortcutHost.addView(pin, pinLp);
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

        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "APP_DRAWER_HMI_RENDER",
                "variant=glass-vector-v2;glyphs=vector;app_icons=system-drawable"
                        + ";state_badges=HOME,AUTO,MEDIA,L,R"
                        + ";tile_height_dp=138");
        dialog.show();
    }

    private View appTile(AppEntry app, boolean compact) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(8), dp(10), dp(8), dp(8));
        tile.setBackground(pressableGradientRound(
                new String[]{"#0E222A","#07151B","#050C10"},
                new String[]{"#173440","#0A2028","#071419"},
                19, "#244753"));
        tile.setElevation(dp(1));
        tile.setClickable(true);
        tile.setFocusable(true);

        int iconSize = compact ? 48 : 56;
        FrameLayout iconWell = new FrameLayout(this);
        iconWell.setBackground(gradientRound(
                new String[]{"#17323A","#0A1A20"}, 17, "#2A515D"));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int innerIcon = compact ? 38 : 44;
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(
                dp(innerIcon), dp(innerIcon), Gravity.CENTER);
        iconWell.addView(icon, innerLp);
        tile.addView(iconWell, new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));

        TextView label = text(app.label, compact ? 12f : 13f, Color.WHITE, false);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setAutoSizeTextTypeUniformWithConfiguration(
                compact ? 10 : 11, compact ? 12 : 13, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        labelLp.topMargin = dp(7);
        tile.addView(label, labelLp);

        TextView stateStrip = text(appStateBadges(app), compact ? 8.5f : 9f,
                Color.parseColor("#74CFC1"), true);
        stateStrip.setGravity(Gravity.CENTER);
        stateStrip.setSingleLine(true);
        stateStrip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        stateStrip.setLetterSpacing(0.04f);
        stateStrip.setPadding(dp(4), 0, dp(4), 0);
        stateStrip.setBackground(gradientRound(
                new String[]{"#0B1F24","#071317"}, 10, "#1E4048"));
        tile.addView(stateStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        tile.setOnClickListener(v -> launchPackage(app.packageName));
        tile.setOnLongClickListener(v -> {
            showAppActions(app, () -> stateStrip.setText(appStateBadges(app)));
            return true;
        });
        return tile;
    }

    private String appStateBadges(AppEntry app) {
        if (app == null) return " ";
        List<String> states = new ArrayList<>();
        String pkg = app.packageName;
        if (favoritePackages().contains(pkg)) states.add("HOME");
        if (autoStartPackages().contains(pkg)) states.add("AUTO");
        if (AutoStartStore.mediaEnabled(prefs, pkg)) states.add("MEDIA");
        if (pkg.equals(prefs.getString(KEY_SPLIT_LEFT, null))) states.add("L");
        if (pkg.equals(prefs.getString(KEY_SPLIT_RIGHT, null))) states.add("R");
        if (states.isEmpty()) return " ";
        return android.text.TextUtils.join(" · ", states);
    }

    private void showAppActions(AppEntry app, Runnable afterChange) {
        boolean favorite = favoritePackages().contains(app.packageName);
        boolean auto = autoStartPackages().contains(app.packageName);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"}, 24, "#315A68"));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconWell = new FrameLayout(this);
        iconWell.setBackground(gradientRound(
                new String[]{"#17323A","#0A1A20"}, 18, "#2A515D"));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconWell.addView(icon, new FrameLayout.LayoutParams(
                dp(44), dp(44), Gravity.CENTER));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(58), dp(58));
        iconLp.rightMargin = dp(12);
        head.addView(iconWell, iconLp);

        LinearLayout appCopy = new LinearLayout(this);
        appCopy.setOrientation(LinearLayout.VERTICAL);
        TextView appName = text(app.label, 17f, Color.WHITE, true);
        fitSingleLine(appName, 12, 17);
        appCopy.addView(appName);
        TextView packageView = text(app.packageName, 9.5f,
                Color.parseColor("#6F8994"), false);
        fitSingleLine(packageView, 8, 10);
        appCopy.addView(packageView);
        TextView badges = text(appStateBadges(app), 9f,
                Color.parseColor("#74CFC1"), true);
        badges.setSingleLine(true);
        badges.setLetterSpacing(0.04f);
        appCopy.addView(badges);
        head.addView(appCopy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        panel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        final AlertDialog[] holder = new AlertDialog[1];
        Runnable refresh = () -> {
            badges.setText(appStateBadges(app));
            if (afterChange != null) afterChange.run();
        };

        panel.addView(appActionRow(
                favorite ? "홈 고정 해제" : "홈에 고정",
                favorite ? "HOME 즐겨찾기에서 제거" : "HOME 즐겨찾기 타일로 추가",
                "⌂", false, () -> {
                    toggleFavorite(app.packageName);
                    refresh.run();
                }, holder));

        panel.addView(appActionRow(
                "순정 앱서랍 바로가기 만들기",
                "Android pinned shortcut 요청",
                "▦", false, () -> {
                    boolean ok = AppDrawerShortcutManager.pin(
                            this, app.packageName, app.label);
                    Toast.makeText(this,
                            ok ? "바로가기 생성 요청을 보냈습니다."
                                    : "이 런처에서는 바로가기 생성 요청을 사용할 수 없습니다.",
                            Toast.LENGTH_LONG).show();
                }, holder));

        panel.addView(appActionRow(
                "순정 앱서랍 바로가기 비활성화",
                "해당 앱 바로가기만 비활성화",
                "×", true, () -> {
                    boolean ok = AppDrawerShortcutManager.disable(
                            this, app.packageName);
                    Toast.makeText(this,
                            ok ? "바로가기를 비활성화했습니다."
                                    : "바로가기를 비활성화할 수 없습니다.",
                            Toast.LENGTH_LONG).show();
                }, holder));

        panel.addView(appActionRow(
                "2분할 왼쪽 앱으로 지정",
                "현재 앱을 Split L 슬롯에 저장",
                "L", false, () -> {
                    setSplit(KEY_SPLIT_LEFT, app.packageName, "왼쪽");
                    refresh.run();
                }, holder));

        panel.addView(appActionRow(
                "2분할 오른쪽 앱으로 지정",
                "현재 앱을 Split R 슬롯에 저장",
                "R", false, () -> {
                    setSplit(KEY_SPLIT_RIGHT, app.packageName, "오른쪽");
                    refresh.run();
                }, holder));

        panel.addView(appActionRow(
                auto ? "시동 자동실행에서 제거" : "시동 자동실행에 추가",
                auto ? "BOOT sequence 등록 해제" : "BOOT sequence에 현재 앱 추가",
                "▶", false, () -> {
                    toggleAutoStart(app.packageName);
                    refresh.run();
                }, holder));

        panel.addView(appActionRow(
                "앱 정보",
                "Android 시스템 앱 상세 정보 열기",
                "i", false, () -> openAppInfo(app.packageName), holder));

        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "APP_ACTION_HMI_RENDER",
                "variant=glass-vector-v2;rows=7;touch_min_dp=48"
                        + ";state_badges=" + appStateBadges(app).replace(" · ", ","));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(panel);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setNegativeButton("닫기", null)
                .create();
        holder[0] = dialog;
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.72f),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.90f));
                w.setDimAmount(0.72f);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
        });
        dialog.show();
    }

    private View appActionRow(
            String title, String subtitle, String symbol, boolean danger,
            Runnable action, AlertDialog[] holder) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(pressableGradientRound(
                danger
                        ? new String[]{"#2A171C","#160D11"}
                        : new String[]{"#0E222A","#07151B"},
                danger
                        ? new String[]{"#402129","#241116"}
                        : new String[]{"#173440","#0A2028"},
                16, danger ? "#6D3843" : "#284B57"));

        HmiGlyphView glyph = new HmiGlyphView(this, symbol);
        glyph.setAccentColor(Color.parseColor(danger ? "#FF9EAA" : "#8CFFE8"));
        glyph.setBackground(gradientRound(
                danger
                        ? new String[]{"#3A2027","#1E1116"}
                        : new String[]{"#173F46","#0B252A"},
                15, danger ? "#72404B" : "#2E615F"));
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        glyphLp.rightMargin = dp(12);
        row.addView(glyph, glyphLp);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 13f, Color.WHITE, true);
        fitSingleLine(titleView, 10, 13);
        copy.addView(titleView);
        TextView subtitleView = text(subtitle, 9.5f,
                Color.parseColor(danger ? "#B8838B" : "#7895A0"), false);
        fitSingleLine(subtitleView, 8, 10);
        copy.addView(subtitleView);
        row.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = text("›", 22f,
                Color.parseColor(danger ? "#C27783" : "#5F8F9B"), false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(26), dp(44)));

        row.setOnClickListener(v -> {
            action.run();
            if (holder != null && holder.length > 0 && holder[0] != null) {
                holder[0].dismiss();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private void createPinnedSplitShortcut() {
        if (!splitReady()) {
            Toast.makeText(this, "서로 다른 좌/우 앱을 먼저 지정하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "이 Android 버전은 고정 바로가기를 지원하지 않습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        android.content.pm.ShortcutManager manager = getSystemService(android.content.pm.ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "SPLIT_SHORTCUT_UNSUPPORTED", "launcher does not support requestPinShortcut");
            Toast.makeText(this, "현재 차량 런처가 고정 바로가기 생성을 지원하지 않습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        String label = "2분할 · " + splitDescription();
        String left = prefs.getString(KEY_SPLIT_LEFT, null);
        String right = prefs.getString(KEY_SPLIT_RIGHT, null);
        Intent launch = new Intent(this, LauncherActivity.class)
                .setAction("com.dolphin.launcher.v1.LAUNCH_SPLIT_SHORTCUT")
                .putExtra(EXTRA_SPLIT_LEFT, left)
                .putExtra(EXTRA_SPLIT_RIGHT, right)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        String shortcutId = "dolphin_split_pair_" + Integer.toHexString((left + "|" + right).hashCode());
        android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(this, shortcutId)
                .setShortLabel("2분할")
                .setLongLabel(label)
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, getApplicationInfo().icon))
                .setIntent(launch)
                .build();
        Intent callback = new Intent(this, ShortcutPinReceiver.class)
                .setAction("com.dolphin.launcher.v1.SPLIT_SHORTCUT_PIN_RESULT")
                .putExtra("shortcut_kind", "split")
                .putExtra("shortcut_id", shortcutId)
                .putExtra(EXTRA_SPLIT_LEFT, left)
                .putExtra(EXTRA_SPLIT_RIGHT, right);
        android.app.PendingIntent pinResult = android.app.PendingIntent.getBroadcast(
                this, shortcutId.hashCode(), callback,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        boolean requested = manager.requestPinShortcut(shortcut, pinResult.getIntentSender());
        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "SPLIT_SHORTCUT_REQUEST",
                "shortcut_id=" + shortcutId + ";left=" + left + ";right=" + right + ";requested=" + requested);
    }

    private void launchSplitPair() {
        launchSplitPair(
                prefs.getString(KEY_SPLIT_LEFT, null),
                prefs.getString(KEY_SPLIT_RIGHT, null));
    }

    private void launchSplitPair(String left, String right) {
        boolean leftLaunchable = isLaunchable(left);
        boolean rightLaunchable = isLaunchable(right);
        boolean samePackage = left != null && left.equals(right);
        if (!leftLaunchable || !rightLaunchable || samePackage) {
            String reason = samePackage ? "same-package"
                    : (!leftLaunchable && !rightLaunchable) ? "both-unlaunchable"
                    : !leftLaunchable ? "left-unlaunchable" : "right-unlaunchable";
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "SPLIT_PAIR_INVALID",
                    "reason=" + reason + ";left=" + left + ";right=" + right);
            VerificationEvidenceRuntime.queueBundleAndUpload(this, "split-pair-invalid");
            Toast.makeText(this,
                    "2분할 바로가기의 앱이 삭제되었거나 실행할 수 없습니다. 좌/우 앱을 다시 지정하세요.",
                    Toast.LENGTH_LONG).show();
            showAppDrawer();
            return;
        }

        SplitCapabilityProbe.Result capability = SplitCapabilityProbe.inspect(this);
        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "SPLIT_CAPABILITY_PROBE", capability.evidence());

        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "SPLIT_EXECUTION_ATTEMPT",
                "left=" + left + ";right=" + right + ";" + capability.evidence());
        if (!capability.authorizedPathReady()) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "SPLIT_EXECUTION_BLOCKED",
                    "reason=localhost-adb-not-authorized;left=" + left + ";right=" + right);
            VerificationEvidenceRuntime.queueBundleAndUpload(this, "split-adb-authorization-required");
            new AlertDialog.Builder(this)
                    .setTitle("2분할 · ADB 승인 필요")
                    .setMessage("차량의 localhost ADB 인증이 아직 승인되지 않았습니다. 한쪽 앱만 실행하는 방식으로 대체하지 않습니다. " +
                            "차량에 ADB 승인 창이 표시되면 V1 키를 승인한 뒤 다시 실행하세요.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        SplitExecutionBridge.Result result = SplitExecutionBridge.launch(this, left, right);
        VerificationEvidenceRuntime.recordPassiveEvent(
                this, result.success ? "SPLIT_EXECUTION_SUCCESS" : "SPLIT_EXECUTION_FAILED",
                "left=" + left + ";right=" + right + ";" + result.detail);
        VerificationEvidenceRuntime.queueBundleAndUpload(
                this, result.success ? "split-execution-success-candidate" : "split-execution-failed");
        if (!result.success) {
            Toast.makeText(this, "2분할 실행에 실패했습니다. 검증 로그를 자동 저장했습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void showAutoStartManager() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"}, 24, "#315A68"));

        LinearLayout autoHead = new LinearLayout(this);
        autoHead.setOrientation(LinearLayout.HORIZONTAL);
        autoHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout autoLabels = new LinearLayout(this);
        autoLabels.setOrientation(LinearLayout.VERTICAL);
        TextView autoTitle = text("AUTO START", 22f, Color.WHITE, true);
        autoTitle.setLetterSpacing(0.08f);
        autoLabels.addView(autoTitle);
        autoLabels.addView(text("Ignition launch · delay · media session", 10f,
                Color.parseColor("#6E8C97"), false));
        autoHead.addView(autoLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        HmiGlyphView autoGlyph = new HmiGlyphView(this, "▶");
        autoGlyph.setAccentColor(Color.parseColor("#8CFFE8"));
        autoGlyph.setBackground(gradientRound(
                new String[]{"#173F46","#0B252A"}, 17, "#2E615F"));
        autoHead.addView(autoGlyph, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams autoHeadLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        autoHeadLp.bottomMargin = dp(8);
        panel.addView(autoHead, autoHeadLp);

        Switch master = new Switch(this);
        master.setText("시동 후 등록 앱 자동 실행");
        master.setTextColor(Color.WHITE);
        master.setChecked(prefs.getBoolean(KEY_AUTOSTART_ENABLED, true));
        master.setBackground(gradientRound(
                new String[]{"#0E222A","#07151B"}, 16, "#254A57"));
        master.setPadding(dp(14), 0, dp(14), 0);
        master.setOnCheckedChangeListener((buttonView, checked) ->
                prefs.edit().putBoolean(KEY_AUTOSTART_ENABLED, checked).apply());
        LinearLayout.LayoutParams masterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        masterLp.bottomMargin = dp(10);
        panel.addView(master, masterLp);

        Button addApp = button("+ 앱 추가");
        addApp.setOnClickListener(v -> showAutoStartAppPicker());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        addLp.bottomMargin = dp(10);
        panel.addView(addApp, addLp);

        List<AppEntry> selected = autoStartApps();
        if (selected.isEmpty()) {
            TextView empty = text("등록된 앱이 없습니다.\n앱서랍에서 앱을 길게 눌러 추가하세요.",
                    14f, Color.parseColor("#8AA0AA"), false);
            empty.setPadding(0, dp(16), 0, dp(18));
            panel.addView(empty);
        } else {
            int order = 1;
            for (AppEntry app : selected) {
                final int appOrderIndex = order - 1;
                final long delayMs = AutoStartStore.delayMs(prefs, app.packageName, appOrderIndex);
                final int delay = (int) Math.round(delayMs / 1000.0);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(9), dp(12), dp(9));
                card.setBackground(gradientRound(
                        new String[]{"#10262F","#09181E","#061014"}, 17, "#2A4F5C"));
                card.setElevation(dp(1));

                LinearLayout infoRow = new LinearLayout(this);
                infoRow.setOrientation(LinearLayout.HORIZONTAL);
                infoRow.setGravity(Gravity.CENTER_VERTICAL);

                ImageView icon = new ImageView(this);
                icon.setImageDrawable(app.icon);
                infoRow.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

                TextView label = text(order + ". " + app.label + "   ·   지연 " + delay + "초",
                        14f, Color.WHITE, true);
                label.setPadding(dp(12), 0, dp(8), 0);
                fitSingleLine(label,11,14);
                infoRow.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1f));

                Switch media = new Switch(this);
                media.setText("미디어 재생");
                media.setTextColor(Color.WHITE);
                media.setChecked(AutoStartStore.mediaEnabled(prefs, app.packageName));
                media.setOnCheckedChangeListener((b, checked) -> {
                    AutoStartStore.setMediaEnabled(prefs, app.packageName, checked);
                    VerificationEvidenceRuntime.recordPassiveEvent(this, "AUTOSTART_MEDIA_CHANGED", "package=" + app.packageName + ";enabled=" + checked);
                });
                infoRow.addView(media, new LinearLayout.LayoutParams(dp(132), dp(48)));
                card.addView(infoRow);

                LinearLayout controls = new LinearLayout(this);
                controls.setOrientation(LinearLayout.HORIZONTAL);
                controls.setGravity(Gravity.CENTER_VERTICAL);

                Button delayMinus = button("-1초");
                delayMinus.setOnClickListener(v -> {
                    long next = Math.max(0L, AutoStartStore.delayMs(prefs, app.packageName, appOrderIndex) - 1000L);
                    AutoStartStore.setDelayMs(prefs, app.packageName, next);
                    VerificationEvidenceRuntime.recordPassiveEvent(this, "AUTOSTART_DELAY_CHANGED", "package=" + app.packageName + ";delay_ms=" + next);
                    showAutoStartManager();
                });
                controls.addView(delayMinus, new LinearLayout.LayoutParams(0, dp(48), 1f));

                Button delayPlus = button("+1초");
                delayPlus.setOnClickListener(v -> {
                    long next = Math.min(30000L, AutoStartStore.delayMs(prefs, app.packageName, appOrderIndex) + 1000L);
                    AutoStartStore.setDelayMs(prefs, app.packageName, next);
                    VerificationEvidenceRuntime.recordPassiveEvent(this, "AUTOSTART_DELAY_CHANGED", "package=" + app.packageName + ";delay_ms=" + next);
                    showAutoStartManager();
                });
                controls.addView(delayPlus, new LinearLayout.LayoutParams(0, dp(48), 1f));

                Button up = button("↑ 위로");
                up.setOnClickListener(v -> {
                    if (AutoStartStore.move(prefs, app.packageName, -1)) {
                        VerificationEvidenceRuntime.recordPassiveEvent(this, "AUTOSTART_ORDER_CHANGED", "package=" + app.packageName + ";direction=up");
                        showAutoStartManager();
                    }
                });
                controls.addView(up, new LinearLayout.LayoutParams(0, dp(48), 1f));

                Button down = button("↓ 아래로");
                down.setOnClickListener(v -> {
                    if (AutoStartStore.move(prefs, app.packageName, 1)) {
                        VerificationEvidenceRuntime.recordPassiveEvent(this, "AUTOSTART_ORDER_CHANGED", "package=" + app.packageName + ";direction=down");
                        showAutoStartManager();
                    }
                });
                controls.addView(down, new LinearLayout.LayoutParams(0, dp(48), 1f));

                Button remove = new Button(this);
                remove.setText("삭제");
                remove.setTextColor(Color.WHITE);
                remove.setTextSize(12f);
                remove.setBackground(round("#33191D", 14, "#70343C"));
                remove.setOnClickListener(v -> {
                    toggleAutoStart(app.packageName);
                    Toast.makeText(this, app.label + " 제거", Toast.LENGTH_SHORT).show();
                });
                controls.addView(remove, new LinearLayout.LayoutParams(0, dp(48), 1f));
                LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
                controlsLp.topMargin = dp(4);
                card.addView(controls, controlsLp);

                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.bottomMargin = dp(8);
                panel.addView(card, cardLp);
                order++;
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);

        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "AUTOSTART_HMI_RENDER",
                "variant=glass-vector-v2;cards=" + selected.size()
                        + ";master=" + prefs.getBoolean(KEY_AUTOSTART_ENABLED, true));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setPositiveButton("닫기", null)
                .create();
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.82f),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.88f));
                w.setDimAmount(0.70f);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
        });
        dialog.show();
    }

    private void showAutoStartAppPicker() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        panel.setBackground(gradientRound(
                new String[]{"#0F232B","#07151B","#040A0E"}, 22, "#2A5260"));

        Set<String> selected = autoStartPackages();
        boolean hasCandidate = false;
        for (AppEntry app : apps) {
            if (selected.contains(app.packageName)) continue;
            hasCandidate = true;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(10), dp(6));
            row.setBackground(pressableGradientRound(
                    new String[]{"#0D2027","#071318"},
                    new String[]{"#15313B","#0A2027"},
                    15, "#213E49"));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            TextView label = text(app.label, 14f, Color.WHITE, false);
            label.setPadding(dp(12), 0, dp(8), 0);
            fitSingleLine(label,11,14);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(50), 1f));

            Button add = button("추가");
            add.setOnClickListener(v -> {
                if (AutoStartStore.add(prefs, app.packageName)) {
                    VerificationEvidenceRuntime.recordPassiveEvent(
                            this, "AUTOSTART_APP_ADDED", "package=" + app.packageName);
                    Toast.makeText(this, app.label + " · 시동 자동실행 추가", Toast.LENGTH_SHORT).show();
                    row.setVisibility(View.GONE);
                }
            });
            row.addView(add, new LinearLayout.LayoutParams(dp(76), dp(48)));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
            rowLp.bottomMargin = dp(6);
            panel.addView(row, rowLp);
        }

        if (!hasCandidate) {
            TextView empty = text("추가할 앱이 없습니다.", 14f,
                    Color.parseColor("#8AA0AA"), false);
            empty.setPadding(0, dp(18), 0, dp(18));
            panel.addView(empty);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle("시동 자동실행 · 앱 추가")
                .setView(scroll)
                .setPositiveButton("완료", (d, w) -> showAutoStartManager())
                .setNegativeButton("닫기", null)
                .create();
        picker.setOnShowListener(d -> {
            Window w = picker.getWindow();
            if (w != null) {
                w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.72f),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.86f));
            }
        });
        picker.show();
    }

    private void openVerificationCenter() {
        startActivity(new Intent(this, VerificationCenterActivity.class));
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"}, 24, "#315A68"));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("SYSTEM CONTROL", 22f, Color.WHITE, true);
        title.setLetterSpacing(0.08f);
        labels.addView(title);
        labels.addView(text("Launcher · OTA · Evidence · Layout", 10f,
                Color.parseColor("#6E8C97"), false));
        head.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        HmiGlyphView gear = new HmiGlyphView(this, "⚙");
        gear.setAccentColor(Color.parseColor("#8CFFE8"));
        gear.setBackground(gradientRound(
                new String[]{"#173F46","#0B252A"}, 17, "#2E615F"));
        head.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        headLp.bottomMargin = dp(8);
        panel.addView(head, headLp);

        final AlertDialog[] holder = new AlertDialog[1];

        panel.addView(settingsRow(
                "HOME 역할", "1.2.2 BETA · BYD 설치 호환성 재검증", "⌂", false,
                () -> {
                    VerificationEvidenceRuntime.recordPassiveEvent(
                            this, "HOME_ROLE_REVERIFY_REQUIRED",
                            "HOME/DEFAULT category intentionally disabled after BYD desktop-app install failure");
                    new AlertDialog.Builder(this)
                            .setTitle("HOME 역할 · 재검증 필요")
                            .setMessage("1.2.0은 HOME/DEFAULT 선언 상태에서 BYD 차량 설치기가 'desktop apps' 설치 실패를 반환했습니다. "
                                    + "1.2.1부터 설치 호환성 확인을 위해 HOME 역할을 임시 비활성화했습니다.\n\n"
                                    + "현재 빌드에서 Android 기본 HOME 선택 화면을 여는 것은 실제 역할과 맞지 않으므로 제공하지 않습니다. "
                                    + "설치 호환성과 HOME 복원 경로가 실차에서 확인될 때까지 BETA/REVERIFY_REQUIRED로 유지합니다.")
                            .setPositiveButton("확인", null)
                            .show();
                }));

        panel.addView(settingsRow(
                "앱 업데이트", "서명검증 OTA · 최신 릴리스 확인", "▶", false,
                () -> AppUpdateManager.checkForUpdates(this, true)));

        panel.addView(settingsRow(
                "실차 검증 센터", "Registry v3 · Test ID · Evidence", "✓", false,
                () -> {
                    if (holder[0] != null) holder[0].dismiss();
                    openVerificationCenter();
                }));

        panel.addView(settingsRow(
                "UI 검증 스냅샷", "HOME PNG · Evidence ZIP 자동 포함", "◎", false,
                () -> {
                    if (holder[0] != null) holder[0].dismiss();
                    showHome();
                    handler.postDelayed(() -> {
                        try {
                            java.io.File file=UiGoldenScreenshotRuntime.capture(this,"home");
                            VerificationEvidenceRuntime.queueBundleAndUpload(
                                    this,"ui-golden-home");
                            Toast.makeText(
                                    this,"HOME UI 스냅샷 저장 · "+file.getName(),
                                    Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            VerificationEvidenceRuntime.recordPassiveEvent(
                                    this,"UI_GOLDEN_SCREENSHOT_FAILED",
                                    "error="+e.getClass().getSimpleName());
                            Toast.makeText(
                                    this,"UI 스냅샷 실패: "+e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    },350L);
                }));

        panel.addView(settingsRow(
                "즐겨찾기 초기화", "HOME 즐겨찾기 기본값 복원", "◎", true,
                () -> {
                    prefs.edit().remove(KEY_FAVORITES).apply();
                    seedFavorites();
                    showHome();
                    if (holder[0] != null) holder[0].dismiss();
                }));

        panel.addView(settingsRow(
                "2분할 지정 초기화", "좌/우 앱 지정값 제거", "◫", true,
                () -> {
                    prefs.edit().remove(KEY_SPLIT_LEFT).remove(KEY_SPLIT_RIGHT).apply();
                    refreshSplitChip();
                    showHome();
                    if (holder[0] != null) holder[0].dismiss();
                }));

        panel.addView(settingsRow(
                "Dolphin Launcher V1", "빌드 · 패키지 · Registry 정보", "⚙", false,
                () -> new AlertDialog.Builder(this)
                        .setTitle("Dolphin Launcher V1 OTA")
                        .setMessage("독립 패키지: com.dolphin.launcher.v1\n"
                                + "버전: " + BuildConfig.VERSION_NAME + "\n\n"
                                + "Registry v3 + 앱내 서명검증 OTA 업데이트 통합 빌드입니다.")
                        .setPositiveButton("확인", null)
                        .show()));

        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "SETTINGS_HMI_RENDER",
                "variant=glass-vector-v2;rows=7;danger_rows=2;golden_screenshot=true");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel)
                .setNegativeButton("닫기", null)
                .create();
        holder[0] = dialog;
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.74f),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.90f));
                w.setDimAmount(0.72f);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }
        });
        dialog.show();
    }

    private View settingsRow(String title, String subtitle, String symbol,
                             boolean danger, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(pressableGradientRound(
                danger
                        ? new String[]{"#2A171C","#160D11"}
                        : new String[]{"#0E222A","#07151B"},
                danger
                        ? new String[]{"#402129","#241116"}
                        : new String[]{"#173440","#0A2028"},
                17,
                danger ? "#6D3843" : "#284B57"));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());

        HmiGlyphView icon = new HmiGlyphView(this, symbol);
        icon.setAccentColor(Color.parseColor(danger ? "#FF9EAA" : "#8CFFE8"));
        icon.setBackground(gradientRound(
                danger
                        ? new String[]{"#3A2027","#1E1116"}
                        : new String[]{"#173F46","#0B252A"},
                16,
                danger ? "#72404B" : "#2E615F"));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconLp.rightMargin = dp(12);
        row.addView(icon, iconLp);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(title, 14f, Color.WHITE, true));
        copy.addView(text(subtitle, 10f,
                Color.parseColor(danger ? "#B8838B" : "#7F99A4"), false));
        row.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = text("›", 24f,
                Color.parseColor(danger ? "#C27783" : "#5F8F9B"), false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(44)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        lp.bottomMargin = dp(7);
        row.setLayoutParams(lp);
        return row;
    }

    private void runPendingAutostart() {
        if (!prefs.getBoolean(KEY_PENDING_AUTOSTART, false)) return;
        prefs.edit().putBoolean(KEY_PENDING_AUTOSTART, false).apply();

        boolean enabled=prefs.getBoolean(KEY_AUTOSTART_ENABLED, true);
        List<AppEntry> list=autoStartApps();
        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "AUTOSTART_TRIGGER_CONSUMED",
                "source=boot-pending;enabled="+enabled+";registered="+list.size());
        if (!enabled) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "AUTOSTART_SKIPPED", "reason=master-disabled");
            return;
        }

        int index = 0;
        for (AppEntry app : list) {
            long delay = 1800L + (index * 3000L);
            final long scheduledDelay=delay;
            handler.postDelayed(() -> {
                VerificationEvidenceRuntime.recordPassiveEvent(
                        this, "AUTOSTART_LAUNCH_ATTEMPT",
                        "package="+app.packageName+";delay_ms="+scheduledDelay);
                launchPackage(app.packageName);
            }, delay);
            index++;
        }

        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "AUTOSTART_BATCH_SCHEDULED",
                "registered="+list.size()+";scheduled="+index);
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
        boolean added;
        if (autoStartPackages().contains(pkg)) {
            AutoStartStore.remove(prefs, pkg);
            added = false;
        } else {
            added = AutoStartStore.add(prefs, pkg);
        }
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
        if (pkg == null) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "APP_LAUNCH_FAILED", "reason=null-package");
            return;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "APP_LAUNCH_FAILED", "package="+pkg+";reason=no-launch-intent");
            Toast.makeText(this, "앱을 실행할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "APP_LAUNCH_DISPATCHED",
                    "package="+pkg+";component="+String.valueOf(intent.getComponent()));
        } catch (Exception e) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this, "APP_LAUNCH_FAILED",
                    "package="+pkg+";reason="+e.getClass().getSimpleName());
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
        return new LinkedHashSet<>(AutoStartStore.read(prefs));
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
        lp.height = dp(138);
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        return lp;
    }


    private Integer validTpmsKpa(Integer value) {
        return value != null && value >= 0 && value <= 4094 ? value : null;
    }

    private String tpmsPsi(Integer kpa) {
        if (kpa == null) return "-- psi";
        return String.format(java.util.Locale.US, "%.1f psi", kpa * 0.1450377377d);
    }

    private String tpmsDisplay(Integer kpa) {
        return kpa == null ? "-- psi · 연결 대기" : tpmsPsi(kpa) + " · BETA";
    }

    private View tpmsCard(String code,String position,Integer kpa) {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12),dp(8),dp(12),dp(8));
        card.setBackground(gradientRound(
                new String[]{"#102831","#09171D","#061014"},18,"#244C59"));

        TyreGaugeView gauge=new TyreGaugeView(this,kpa!=null);
        int tpmsIndex = tpmsIndex(code);
        if (tpmsIndex >= 0) homeTpmsGauge[tpmsIndex] = gauge;
        LinearLayout.LayoutParams gaugeLp=new LinearLayout.LayoutParams(dp(52),dp(52));
        gaugeLp.rightMargin=dp(8);
        card.addView(gauge,gaugeLp);

        LinearLayout copy=new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView head=text(code+"  ·  "+position,11f,Color.parseColor("#BFD2D8"),true);
        head.setLetterSpacing(0.04f);
        copy.addView(head);
        TextView pressure=text(kpa==null?"-- psi":tpmsPsi(kpa),15f,Color.WHITE,true);
        if (tpmsIndex >= 0) homeTpmsPressure[tpmsIndex] = pressure;
        copy.addView(pressure);
        TextView state=text(kpa==null?"● WAITING":"● LIVE · BETA",9.5f,
                Color.parseColor(kpa==null?"#738A94":"#72E8D0"),true);
        if (tpmsIndex >= 0) homeTpmsState[tpmsIndex] = state;
        copy.addView(state);
        card.addView(copy,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        return card;
    }

    private int tpmsIndex(String code) {
        if ("FL".equals(code)) return 0;
        if ("FR".equals(code)) return 1;
        if ("RL".equals(code)) return 2;
        if ("RR".equals(code)) return 3;
        return -1;
    }

    private void refreshHomeLiveBindings() {
        if (homeMediaStatus == null && homeSafetyStatus == null
                && homeVehicleSubtitle == null && homeTpmsPressure[0] == null) return;

        MediaNowPlayingRuntime.Snapshot mediaSource = null;
        NavSafetyStatusRuntime.Snapshot navSource = null;

        if (homeMediaStatus != null || homeMediaSubtitle != null) {
            MediaNowPlayingRuntime.Snapshot snapshot = mediaNowPlayingSnapshot(false);
            mediaSource = snapshot;
            String mediaState = registryFeatureState(
                    "BACKGROUND_MEDIA_AUTOPLAY","REVERIFY_REQUIRED");
            if (homeMediaStatus != null) {
                String status = mediaState + (snapshot.available
                        ? " · " + snapshot.playback : "");
                homeMediaStatus.setText(status);
                homeMediaStatus.setTextColor(cockpitStatusColor(status));
                if (homeMediaGraphic != null) {
                    homeMediaGraphic.setSignalState(cockpitSignalState(status));
                }
            }
            if (homeMediaSubtitle != null) {
                homeMediaSubtitle.setText(mediaPanelSummary(snapshot));
            }
        }

        if (homeSafetyStatus != null || homeSafetySubtitle != null) {
            NavSafetyStatusRuntime.Snapshot nav = NavSafetyStatusRuntime.read(this);
            navSource = nav;
            String safetyState = registryFeatureState("NAV_SAFETY_FEED","BETA");
            String fsdState = registryFeatureState("FSD_OBJECT_LANE_MODEL","BLOCKED");
            String status = safetyStatusLine(nav,safetyState,fsdState);
            if (homeSafetyStatus != null) {
                homeSafetyStatus.setText(status);
                homeSafetyStatus.setTextColor(cockpitStatusColor(status));
                if (homeSafetyGraphic != null) {
                    homeSafetyGraphic.setSignalState(cockpitSignalState(status));
                }
            }
            if (homeSafetySubtitle != null) {
                homeSafetySubtitle.setText(safetyPanelSummary(nav));
            }
        }

        if (homeVehicleStatus != null) {
            String state = registryFeatureState("LIVE_VEHICLE_INFO","BETA")
                    + " · READ ONLY";
            homeVehicleStatus.setText(state);
            homeVehicleStatus.setTextColor(cockpitStatusColor(state));
            if (homeVehicleGraphic != null) {
                boolean live = vehicleGearRaw != null || vehicleSpeedRaw != null
                        || tpmsFlKpa != null || tpmsFrKpa != null
                        || tpmsRlKpa != null || tpmsRrKpa != null;
                homeVehicleGraphic.setSignalState(live
                        ? CockpitPanelGraphicView.SIGNAL_LIVE
                        : CockpitPanelGraphicView.SIGNAL_WAITING);
            }
        }
        if (homeVehicleSubtitle != null) {
            homeVehicleSubtitle.setText(vehiclePanelSummary());
        }

        if (homeHeroGraphic != null) {
            int media = mediaSource != null && mediaSource.available
                    ? HomeHeroGraphicView.SOURCE_LIVE
                    : HomeHeroGraphicView.SOURCE_WAITING;
            int nav;
            if (navSource == null || !navSource.available) {
                nav = HomeHeroGraphicView.SOURCE_WAITING;
            } else if (navSource.fresh) {
                nav = HomeHeroGraphicView.SOURCE_LIVE;
            } else {
                nav = HomeHeroGraphicView.SOURCE_STALE;
            }
            boolean vehicleLive = vehicleGearRaw != null || vehicleSpeedRaw != null
                    || tpmsFlKpa != null || tpmsFrKpa != null
                    || tpmsRlKpa != null || tpmsRrKpa != null;
            int vehicle = vehicleLive
                    ? HomeHeroGraphicView.SOURCE_LIVE
                    : HomeHeroGraphicView.SOURCE_WAITING;
            homeHeroGraphic.setSourceStates(media,nav,vehicle);
            recordHeroSourceRailState(media,nav,vehicle);
        }

        Integer[] values = new Integer[]{tpmsFlKpa,tpmsFrKpa,tpmsRlKpa,tpmsRrKpa};
        for (int i = 0; i < values.length; i++) {
            Integer kpa = values[i];
            if (homeTpmsPressure[i] != null) {
                homeTpmsPressure[i].setText(kpa == null ? "-- psi" : tpmsPsi(kpa));
            }
            if (homeTpmsState[i] != null) {
                homeTpmsState[i].setText(kpa == null ? "● WAITING" : "● LIVE · BETA");
                homeTpmsState[i].setTextColor(Color.parseColor(
                        kpa == null ? "#738A94" : "#72E8D0"));
            }
            if (homeTpmsGauge[i] != null) homeTpmsGauge[i].setLive(kpa != null);
        }
    }

    private View buildCockpitDeck() {
        LinearLayout deck = new LinearLayout(this);
        deck.setOrientation(LinearLayout.HORIZONTAL);

        String mediaState = registryFeatureState(
                "BACKGROUND_MEDIA_AUTOPLAY","REVERIFY_REQUIRED");
        String safetyState = registryFeatureState(
                "NAV_SAFETY_FEED","BETA");
        String fsdState = registryFeatureState(
                "FSD_OBJECT_LANE_MODEL","BLOCKED");
        String vehicleState = registryFeatureState(
                "LIVE_VEHICLE_INFO","BETA");

        MediaNowPlayingRuntime.Snapshot nowPlaying = mediaNowPlayingSnapshot();
        deck.addView(cockpitPanel(
                "MEDIA CENTER",
                mediaPanelSummary(nowPlaying),
                mediaState + (nowPlaying.available ? " · " + nowPlaying.playback : ""),
                HmiGlyphView.class,
                CockpitPanelGraphicView.MEDIA,
                this::showMediaCenter), weighted());

        NavSafetyStatusRuntime.Snapshot navSource = NavSafetyStatusRuntime.read(this);
        deck.addView(cockpitPanel(
                "FSD · SAFETY",
                safetyPanelSummary(navSource),
                safetyStatusLine(navSource,safetyState,fsdState),
                HmiGlyphView.class,
                CockpitPanelGraphicView.SAFETY,
                this::showSafetySourcePanel), weighted());

        deck.addView(cockpitPanel(
                "VEHICLE INFO",
                vehiclePanelSummary(),
                vehicleState + " · READ ONLY",
                HmiGlyphView.class,
                CockpitPanelGraphicView.VEHICLE,
                this::showVehicleInfoPanel), weighted());

        return deck;
    }

    private View cockpitPanel(String title, String subtitle, String status,
                              Class<?> ignored, int mode, Runnable action) {
        FrameLayout card = new FrameLayout(this);
        card.setClickable(true);
        card.setFocusable(true);
        card.setElevation(dp(2));
        card.setBackground(pressableGradientRound(
                new String[]{"#102831","#09171D","#061014"},
                new String[]{"#173B47","#0D252E","#09181E"},
                22,"#244C59"));
        card.setOnClickListener(v -> action.run());

        CockpitPanelGraphicView graphic = new CockpitPanelGraphicView(this, mode);
        graphic.setSignalState(cockpitSignalState(status));
        if (mode == CockpitPanelGraphicView.MEDIA) homeMediaGraphic = graphic;
        if (mode == CockpitPanelGraphicView.SAFETY) homeSafetyGraphic = graphic;
        if (mode == CockpitPanelGraphicView.VEHICLE) homeVehicleGraphic = graphic;
        card.addView(graphic, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.BOTTOM);
        copy.setPadding(dp(16),dp(14),dp(12),dp(14));

        TextView eyebrow = text(status,9.5f,cockpitStatusColor(status),true);
        eyebrow.setLetterSpacing(0.08f);
        fitSingleLine(eyebrow,8,10);
        if (mode == CockpitPanelGraphicView.MEDIA) homeMediaStatus = eyebrow;
        if (mode == CockpitPanelGraphicView.SAFETY) homeSafetyStatus = eyebrow;
        if (mode == CockpitPanelGraphicView.VEHICLE) homeVehicleStatus = eyebrow;
        copy.addView(eyebrow);

        TextView head = text(title,16f,Color.WHITE,true);
        head.setLetterSpacing(0.04f);
        copy.addView(head);

        TextView sub = text(subtitle,10f,Color.parseColor("#8CA6AF"),false);
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setAutoSizeTextTypeUniformWithConfiguration(
                9,10,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        if (mode == CockpitPanelGraphicView.MEDIA) homeMediaSubtitle = sub;
        if (mode == CockpitPanelGraphicView.SAFETY) homeSafetySubtitle = sub;
        if (mode == CockpitPanelGraphicView.VEHICLE) homeVehicleSubtitle = sub;
        copy.addView(sub);

        FrameLayout.LayoutParams copyLp = new FrameLayout.LayoutParams(
                (int)(dp(190)), ViewGroup.LayoutParams.MATCH_PARENT);
        copyLp.gravity = Gravity.LEFT;
        card.addView(copy,copyLp);
        return card;
    }

    private String safetyStatusLine(
            NavSafetyStatusRuntime.Snapshot nav,String safetyState,String fsdState) {
        String source;
        if (nav == null || !nav.available) source = "SOURCE WAITING";
        else if (nav.fresh) source = "SOURCE LIVE";
        else if (nav.active) source = "SOURCE STALE";
        else source = "SOURCE REMOVED";
        return "SAFETY " + safetyState + " · " + source + " · FSD " + fsdState;
    }

    private String safetyPanelSummary(NavSafetyStatusRuntime.Snapshot nav) {
        if (nav == null || !nav.available) {
            return "지원 내비 source 대기 · parser pending";
        }
        return NavSafetyStatusRuntime.sourceLabel(nav.packageName)
                + " · " + formatSourceAge(nav.ageMs)
                + " · parser pending";
    }

    private String formatSourceAge(long ageMs) {
        if (ageMs == Long.MAX_VALUE) return "age --";
        long sec=Math.max(0L,ageMs/1000L);
        if(sec<60L)return sec+"s ago";
        return (sec/60L)+"m ago";
    }

    private void showSafetySourcePanel() {
        NavSafetyStatusRuntime.Snapshot nav=NavSafetyStatusRuntime.read(this);
        String safetyState=registryFeatureState("NAV_SAFETY_FEED","BETA");
        String fsdState=registryFeatureState("FSD_OBJECT_LANE_MODEL","BLOCKED");

        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20),dp(16),dp(20),dp(16));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"},24,"#315A68"));
        panel.addView(hmiDialogHeader(
                "SAFETY SOURCE","Navigation provenance · parser pending","✓"));
        panel.addView(hmiInfoStrip(
                "지원 내비 notification source만 표시 · 카메라/속도/거리 의미 추정 금지"));

        LinearLayout row1=new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(vehicleMetric("NAV STATE",safetyState),weighted());
        row1.addView(vehicleMetric("FSD MODEL",fsdState),weighted());
        panel.addView(row1,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(78)));

        LinearLayout row2=new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(vehicleMetric("SOURCE",
                nav.available?NavSafetyStatusRuntime.sourceLabel(nav.packageName):"WAITING"),weighted());
        row2.addView(vehicleMetric("AGE",formatSourceAge(nav.ageMs)),weighted());
        row2.addView(vehicleMetric("PAYLOAD",
                nav.available?nav.textLength+" chars":"--"),weighted());
        panel.addView(row2,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(78)));

        VerificationEvidenceRuntime.recordPassiveEvent(
                this,"SAFETY_SOURCE_HMI_RENDER",
                "available="+nav.available
                        +";active="+nav.active
                        +";fresh="+nav.fresh
                        +";package="+String.valueOf(nav.packageName)
                        +";age_ms="+nav.ageMs
                        +";text_length="+nav.textLength
                        +";parser=none;semantic_values=false");

        new AlertDialog.Builder(this)
                .setView(panel)
                .setPositiveButton("검증 센터",(d,w)->openVerificationCenter())
                .setNegativeButton("닫기",null)
                .show();
    }

    private void recordHeroSourceRailState(int media,int nav,int vehicle) {
        if(media==lastHeroMediaState && nav==lastHeroNavState
                && vehicle==lastHeroVehicleState)return;
        lastHeroMediaState=media;
        lastHeroNavState=nav;
        lastHeroVehicleState=vehicle;
        VerificationEvidenceRuntime.recordPassiveEvent(
                this,"HERO_SOURCE_RAIL_STATE",
                "media="+heroStateLabel(media)
                        +";nav="+heroStateLabel(nav)
                        +";vehicle="+heroStateLabel(vehicle)
                        +";feature_state_independent=true");
    }

    private String heroStateLabel(int state) {
        if(state==HomeHeroGraphicView.SOURCE_LIVE)return "LIVE";
        if(state==HomeHeroGraphicView.SOURCE_STALE)return "STALE";
        if(state==HomeHeroGraphicView.SOURCE_BLOCKED)return "BLOCKED";
        return "WAITING";
    }

    private int cockpitSignalState(String status) {
        String s=status==null?"":status.toUpperCase(Locale.ROOT);
        if(s.contains("BLOCKED")) return CockpitPanelGraphicView.SIGNAL_BLOCKED;
        if(s.contains("REVERIFY") || s.contains("STALE") || s.contains("REMOVED")) {
            return CockpitPanelGraphicView.SIGNAL_STALE;
        }
        if(s.contains("LIVE") || s.contains("PLAYING")) {
            return CockpitPanelGraphicView.SIGNAL_LIVE;
        }
        return CockpitPanelGraphicView.SIGNAL_WAITING;
    }

    private int cockpitStatusColor(String status) {
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (s.contains("BLOCKED")) return Color.parseColor("#FF8A80");
        if (s.contains("REVERIFY_REQUIRED")) return Color.parseColor("#FFD166");
        if (s.contains("BETA")) return Color.parseColor("#77D9FF");
        if (s.contains("VERIFIED")) return Color.parseColor("#72E6B1");
        return Color.parseColor("#72E8D0");
    }

    private void showMediaCenter() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20),dp(16),dp(20),dp(16));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"},24,"#315A68"));

        String state = registryFeatureState(
                "BACKGROUND_MEDIA_AUTOPLAY","REVERIFY_REQUIRED");
        panel.addView(hmiDialogHeader(
                "MEDIA CENTER","Target-scoped MediaSession · "+state,"▶"));
        panel.addView(hmiInfoStrip(
                "전역 media key 미사용 · 대상 MediaSession만 PLAY 요청 · 실차 재검증 필요"));

        MediaNowPlayingRuntime.Snapshot nowPlaying = mediaNowPlayingSnapshot();
        panel.addView(nowPlayingCard(nowPlaying),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,dp(92)));

        List<AppEntry> mediaApps = mediaAutoStartApps();
        if (mediaApps.isEmpty()) {
            TextView empty = text(
                    "미디어 자동재생으로 등록된 앱이 없습니다.\n"
                            + "시동 자동실행에서 앱별 ‘미디어 재생’을 켜세요.",
                    12f,Color.parseColor("#93AAB3"),false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12),dp(24),dp(12),dp(24));
            empty.setBackground(gradientRound(
                    new String[]{"#0D2027","#071318"},16,"#254957"));
            panel.addView(empty,new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,dp(94)));
        } else {
            List<String> ordered = AutoStartStore.read(prefs);
            for (AppEntry app : mediaApps) {
                int index = ordered.indexOf(app.packageName);
                long delay = AutoStartStore.delayMs(
                        prefs,app.packageName,Math.max(0,index));
                panel.addView(mediaCenterRow(app,delay),
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,dp(66)));
            }
        }

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button manage=button("자동실행 설정");
        manage.setOnClickListener(v->showAutoStartManager());
        actions.addView(manage,weighted());
        Button appsButton=button("앱 서랍");
        appsButton.setOnClickListener(v->showAppDrawer());
        actions.addView(appsButton,weighted());
        LinearLayout.LayoutParams actionLp=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(54));
        actionLp.topMargin=dp(10);
        panel.addView(actions,actionLp);

        VerificationEvidenceRuntime.recordPassiveEvent(
                this,"MEDIA_CENTER_HMI_RENDER",
                "variant=glass-vector-v2;state="+state
                        +";configured_media_apps="+mediaApps.size()
                        +";now_playing_available="+nowPlaying.available
                        +";now_playing_state="+nowPlaying.playback
                        +";playback_verified=false");

        new AlertDialog.Builder(this)
                .setView(panel)
                .setPositiveButton("완료",null)
                .show();
    }

    private MediaNowPlayingRuntime.Snapshot mediaNowPlayingSnapshot() {
        return mediaNowPlayingSnapshot(true);
    }

    private MediaNowPlayingRuntime.Snapshot mediaNowPlayingSnapshot(boolean recordEvidence) {
        LinkedHashSet<String> targets=new LinkedHashSet<>();
        for(AppEntry app:mediaAutoStartApps()) targets.add(app.packageName);
        MediaNowPlayingRuntime.Snapshot snapshot=
                MediaNowPlayingRuntime.read(this,targets);
        if (recordEvidence) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this,"NOW_PLAYING_SNAPSHOT",
                    "available="+snapshot.available
                            +";package="+String.valueOf(snapshot.packageName)
                            +";playback="+snapshot.playback
                            +";reason="+snapshot.reason
                            +";metadata_logged=false");
        }
        return snapshot;
    }

    private String mediaPanelSummary(MediaNowPlayingRuntime.Snapshot snapshot) {
        if(snapshot==null || !snapshot.available) {
            return "대상 MediaSession · " + (snapshot==null
                    ? "UNAVAILABLE" : snapshot.reason);
        }
        return snapshot.title + " · " + snapshot.artist;
    }

    private View nowPlayingCard(MediaNowPlayingRuntime.Snapshot snapshot) {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14),dp(10),dp(14),dp(10));
        row.setBackground(gradientRound(
                new String[]{"#11313A","#081A20","#051014"},18,"#2C5963"));

        HmiGlyphView icon=new HmiGlyphView(this,"▶");
        icon.setAccentColor(Color.parseColor(
                snapshot.available ? "#8CFFE8" : "#7B929B"));
        icon.setBackground(gradientRound(
                new String[]{"#173F46","#0B252A"},16,"#2E615F"));
        LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(48),dp(48));
        iconLp.rightMargin=dp(12);
        row.addView(icon,iconLp);

        LinearLayout copy=new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow=text(
                snapshot.available ? "NOW PLAYING · "+snapshot.playback
                        : "NOW PLAYING · UNAVAILABLE",
                9.5f,
                Color.parseColor(snapshot.available ? "#72E8D0" : "#8297A0"),
                true);
        eyebrow.setLetterSpacing(0.07f);
        copy.addView(eyebrow);
        copy.addView(text(
                snapshot.available ? snapshot.title : snapshot.reason,
                14f,Color.WHITE,true));
        copy.addView(text(
                snapshot.available
                        ? snapshot.artist+" · "+appLabel(snapshot.packageName)
                        : "활성 target MediaSession 또는 권한 확인 필요",
                10f,Color.parseColor("#7E99A4"),false));
        row.addView(copy,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        return row;
    }

    private List<AppEntry> mediaAutoStartApps() {
        List<AppEntry> result=new ArrayList<>();
        List<String> ordered=AutoStartStore.read(prefs);
        for(String pkg:ordered){
            if(!AutoStartStore.mediaEnabled(prefs,pkg))continue;
            for(AppEntry app:apps){
                if(app.packageName.equals(pkg)){
                    result.add(app);
                    break;
                }
            }
        }
        return result;
    }

    private View mediaCenterRow(AppEntry app,long delayMs) {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10),dp(7),dp(10),dp(7));
        row.setBackground(pressableGradientRound(
                new String[]{"#0E222A","#07151B"},
                new String[]{"#173440","#0A2028"},
                16,"#284B57"));
        row.setOnClickListener(v->launchPackage(app.packageName));

        FrameLayout iconWell=new FrameLayout(this);
        iconWell.setBackground(gradientRound(
                new String[]{"#17323A","#0A1A20"},15,"#2A515D"));
        ImageView icon=new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconWell.addView(icon,new FrameLayout.LayoutParams(
                dp(34),dp(34),Gravity.CENTER));
        LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(46),dp(46));
        iconLp.rightMargin=dp(10);
        row.addView(iconWell,iconLp);

        LinearLayout copy=new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(app.label,13f,Color.WHITE,true));
        copy.addView(text(
                "PLAY 요청 지연 "+String.format(Locale.US,"%.1f초",delayMs/1000f)
                        +" · "+app.packageName,
                9.5f,Color.parseColor("#7895A0"),false));
        row.addView(copy,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));

        TextView badge=text("REVERIFY",9f,Color.parseColor("#FFD166"),true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(gradientRound(
                new String[]{"#332A14","#1B160B"},13,"#665423"));
        row.addView(badge,new LinearLayout.LayoutParams(dp(72),dp(30)));
        return row;
    }

    private String registryFeatureState(String featureId,String fallback) {
        try {
            JSONObject root = VerificationEvidenceRuntime.loadRegistry(this);
            JSONArray features = root.optJSONArray("features");
            if (features == null) return fallback;
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                if (feature == null) continue;
                if (!featureId.equals(feature.optString("feature_id"))) continue;
                String state = feature.optString("state",fallback);
                return state == null || state.trim().isEmpty() ? fallback : state;
            }
        } catch (Exception e) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    this,"HMI_REGISTRY_STATE_FALLBACK",
                    "feature_id="+featureId+";fallback="+fallback
                            +";reason="+e.getClass().getSimpleName());
        }
        return fallback;
    }

    private String vehiclePanelSummary() {
        int live = 0;
        if (tpmsFlKpa != null) live++;
        if (tpmsFrKpa != null) live++;
        if (tpmsRlKpa != null) live++;
        if (tpmsRrKpa != null) live++;
        String speed = vehicleSpeedRaw == null ? "speed RAW --"
                : "speed RAW " + vehicleSpeedRaw;
        String gear = vehicleGearRaw == null ? "gear RAW --"
                : "gear RAW " + vehicleGearRaw;
        return gear + " · " + speed + " · TPMS " + live + "/4";
    }

    private void showVehicleInfoPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20),dp(16),dp(20),dp(16));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"},24,"#315A68"));

        panel.addView(hmiDialogHeader(
                "VEHICLE INFO","TPMS · read-only vehicle layer","◉"));
        panel.addView(hmiInfoStrip(
                "차량 제어 없음 · GEAR/SPEED/TURN은 의미·단위 확정 전 RAW로만 표시"));

        LinearLayout telemetry=new LinearLayout(this);
        telemetry.setOrientation(LinearLayout.HORIZONTAL);
        telemetry.addView(vehicleMetric("GEAR RAW",rawValue(vehicleGearRaw)),weighted());
        telemetry.addView(vehicleMetric("SPEED RAW",rawValue(vehicleSpeedRaw)),weighted());
        telemetry.addView(vehicleMetric("TURN RAW",
                "L "+rawValue(vehicleTurnLeftRaw)+" · R "+rawValue(vehicleTurnRightRaw)),weighted());
        panel.addView(telemetry,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(78)));

        LinearLayout row1=new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(vehicleMetric("FL",tpmsDisplay(tpmsFlKpa)),weighted());
        row1.addView(vehicleMetric("FR",tpmsDisplay(tpmsFrKpa)),weighted());
        panel.addView(row1,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(78)));

        LinearLayout row2=new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(vehicleMetric("RL",tpmsDisplay(tpmsRlKpa)),weighted());
        row2.addView(vehicleMetric("RR",tpmsDisplay(tpmsRrKpa)),weighted());
        panel.addView(row2,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(78)));

        VerificationEvidenceRuntime.recordPassiveEvent(
                this,"VEHICLE_INFO_HMI_RENDER",
                "variant=glass-vector-v2;tpms_live="
                        + (tpmsFlKpa!=null||tpmsFrKpa!=null||tpmsRlKpa!=null||tpmsRrKpa!=null)
                        +";gear_raw="+String.valueOf(vehicleGearRaw)
                        +";speed_raw="+String.valueOf(vehicleSpeedRaw)
                        +";turn_left_raw="+String.valueOf(vehicleTurnLeftRaw)
                        +";turn_right_raw="+String.valueOf(vehicleTurnRightRaw)
                        +";normalized=false");

        new AlertDialog.Builder(this)
                .setView(panel)
                .setPositiveButton("완료",null)
                .show();
    }

    private String rawValue(Integer value) {
        return value == null ? "--" : String.valueOf(value);
    }

    private View vehicleMetric(String label,String value) {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14),dp(10),dp(14),dp(10));
        card.setBackground(gradientRound(
                new String[]{"#0E222A","#07151B"},16,"#284B57"));
        TextView head=text(label,11f,Color.parseColor("#72E8D0"),true);
        head.setLetterSpacing(0.08f);
        card.addView(head);
        card.addView(text(value,13f,Color.WHITE,true));
        return card;
    }

    private View buildPremiumHero() {
        FrameLayout hero = new FrameLayout(this);
        hero.setClipToOutline(true);
        hero.setElevation(dp(3));
        hero.setBackground(gradientRound(
                new String[]{"#102A34","#07151C","#040A0E"}, 28, "#285565"));

        HomeHeroGraphicView graphic = new HomeHeroGraphicView(this);
        homeHeroGraphic = graphic;
        FrameLayout.LayoutParams graphicLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        hero.addView(graphic, graphicLp);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(28), dp(22), dp(12), dp(22));

        TextView eyebrow = text("DOLPHIN  ·  COCKPIT", 10f,
                Color.parseColor("#76C9C1"), true);
        eyebrow.setLetterSpacing(0.14f);
        copy.addView(eyebrow);

        TextView title = text("DRIVE\nHOME", 38f, Color.WHITE, true);
        title.setLineSpacing(0f, 0.88f);
        title.setLetterSpacing(0.02f);
        copy.addView(title);

        TextView subtitle = text(
                "미디어 · 안전운전 · 차량정보를\n한 화면에서 빠르게 제어",
                12.5f, Color.parseColor("#A6BCC4"), false);
        subtitle.setLineSpacing(dp(2), 1f);
        copy.addView(subtitle);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setPadding(0, dp(14), 0, 0);

        TextView live = chip("● LIVE");
        live.setTextColor(Color.parseColor("#77FDDC"));
        status.addView(live, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));

        TextView split = chip(splitReady() ? "SPLIT READY" : "SPLIT SETUP");
        LinearLayout.LayoutParams splitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        splitLp.leftMargin = dp(8);
        status.addView(split, splitLp);

        copy.addView(status);

        FrameLayout.LayoutParams copyLp = new FrameLayout.LayoutParams(
                dp(heroCopyWidthDp()), ViewGroup.LayoutParams.MATCH_PARENT);
        copyLp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        hero.addView(copy, copyLp);
        return hero;
    }

    private int heroCopyWidthDp() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / Math.max(1f, metrics.density);
        int target = Math.round(widthDp * 0.36f);
        return Math.max(276, Math.min(372, target));
    }

    private View sectionHeader(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), 0);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView head = text(title, 12f, Color.parseColor("#D8E9ED"), true);
        head.setLetterSpacing(0.12f);
        labels.addView(head);
        labels.addView(text(subtitle, 9.5f, Color.parseColor("#617E89"), false));
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView line = text("━━", 10f, Color.parseColor("#2C6C72"), true);
        line.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(line, new LinearLayout.LayoutParams(dp(60), dp(34)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lp.topMargin = dp(2);
        row.setLayoutParams(lp);
        return row;
    }

    private View actionCard(String title, String subtitle, String symbol, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(pressableGradientRound(
                new String[]{"#102831","#09171D","#061014"},
                new String[]{"#173B47","#0D252E","#09181E"},
                20, "#244C59"));
        card.setElevation(dp(2));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());

        HmiGlyphView icon = new HmiGlyphView(this, symbol);
        icon.setAccentColor(Color.parseColor("#88FFE5"));
        icon.setBackground(gradientRound(
                new String[]{"#163F46","#0A242A"}, 18, "#2B615F"));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        iconLp.rightMargin = dp(4);
        card.addView(icon, iconLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8), 0, 0, 0);
        labels.addView(text(title, 15f, Color.WHITE, true));
        TextView sub = text(subtitle, 10.5f, Color.parseColor("#829DA7"), false);
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
        button.setPadding(dp(4), dp(3), dp(4), dp(3));
        button.setBackground(pressableGradientRound(
                new String[]{"#0B1D24","#071218"},
                new String[]{"#163541","#0B2028"},
                17, "#173742"));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());

        HmiGlyphView icon = new HmiGlyphView(this, symbol);
        icon.setAccentColor(Color.parseColor("#8CFFE8"));
        button.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(32)));

        TextView title = text(label, 10f, Color.parseColor("#A8BBC4"), false);
        title.setGravity(Gravity.CENTER);
        fitSingleLine(title,9,10);
        button.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
        return button;
    }

    private void fitSingleLine(TextView view,int minSp,int maxSp) {
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setAutoSizeTextTypeUniformWithConfiguration(
                minSp,maxSp,1,android.util.TypedValue.COMPLEX_UNIT_SP);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setBackground(round("#10252E", 14, "#2A5362"));
        return b;
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

    private GradientDrawable gradientRound(String[] colors, int radiusDp, String stroke) {
        int[] parsed = new int[colors.length];
        for (int i = 0; i < colors.length; i++) parsed[i] = Color.parseColor(colors[i]);
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, parsed);
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != null) drawable.setStroke(dp(1), Color.parseColor(stroke));
        return drawable;
    }

    private Drawable pressableGradientRound(
            String[] normalColors, String[] pressedColors, int radiusDp, String stroke) {
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                gradientRound(pressedColors, radiusDp, stroke));
        states.addState(new int[]{},
                gradientRound(normalColors, radiusDp, stroke));
        return states;
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
