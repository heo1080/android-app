package com.dolphin.launcher.v1;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import org.json.JSONObject;

/**
 * BYD stock-launcher app-drawer shortcut bridge.
 *
 * V1 owns a fixed pool of disabled MAIN+LAUNCHER activities and enables a slot
 * only after an explicit user assignment. Component enable success is never
 * treated as proof that the OEM launcher rendered the icon.
 */
public final class AppDrawerShortcutManager {
    private static final String PREFS = "v1_app_drawer_slots";
    private static final String KEY_PREFIX = "slot_";
    private static final String KIND_APP = "app";
    private static final String KIND_SPLIT = "split";
    public static final int SLOT_COUNT = 16;

    private AppDrawerShortcutManager() {}

    public static boolean pin(Context context, String packageName, String label) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        if (context.getPackageManager().getLaunchIntentForPackage(packageName) == null) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, "APP_SHORTCUT_SLOT_ASSIGN_FAILED",
                    "package=" + packageName
                            + ";reason=no-launch-intent;mechanism=launcher-component-slot");
            return false;
        }
        return assign(context, SlotData.app(packageName, label));
    }

    public static boolean pinSplit(
            Context context, String leftPackage, String rightPackage, String label) {
        if (leftPackage == null || rightPackage == null || leftPackage.equals(rightPackage)) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, "SPLIT_SHORTCUT_SLOT_ASSIGN_FAILED",
                    "left=" + leftPackage + ";right=" + rightPackage
                            + ";reason=invalid-pair;mechanism=launcher-component-slot");
            return false;
        }
        PackageManager pm = context.getPackageManager();
        if (pm.getLaunchIntentForPackage(leftPackage) == null
                || pm.getLaunchIntentForPackage(rightPackage) == null) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, "SPLIT_SHORTCUT_SLOT_ASSIGN_FAILED",
                    "left=" + leftPackage + ";right=" + rightPackage
                            + ";reason=no-launch-intent;mechanism=launcher-component-slot");
            return false;
        }
        return assign(context, SlotData.split(leftPackage, rightPackage, label));
    }

    public static boolean disable(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        int disabled = 0;
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            SlotData data = read(context, slot);
            if (data != null && KIND_APP.equals(data.kind)
                    && packageName.equals(data.packageName) && clearSlot(context, slot)) {
                disabled++;
            }
        }
        VerificationEvidenceRuntime.recordPassiveEvent(
                context,
                disabled > 0 ? "APP_SHORTCUT_DISABLED" : "APP_SHORTCUT_DISABLE_FAILED",
                "package=" + packageName + ";disabled_slots=" + disabled
                        + ";mechanism=launcher-component-slot");
        return disabled > 0;
    }

    public static boolean disableSplit(
            Context context, String leftPackage, String rightPackage) {
        int disabled = 0;
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            SlotData data = read(context, slot);
            if (data != null && KIND_SPLIT.equals(data.kind)
                    && safeEquals(leftPackage, data.leftPackage)
                    && safeEquals(rightPackage, data.rightPackage)
                    && clearSlot(context, slot)) {
                disabled++;
            }
        }
        VerificationEvidenceRuntime.recordPassiveEvent(
                context,
                disabled > 0 ? "SPLIT_SHORTCUT_DISABLED" : "SPLIT_SHORTCUT_DISABLE_FAILED",
                "left=" + leftPackage + ";right=" + rightPackage
                        + ";disabled_slots=" + disabled
                        + ";mechanism=launcher-component-slot");
        return disabled > 0;
    }

    public static void launchSlot(Context context, int slot) {
        SlotData data = read(context, slot);
        String slotId = slotId(slot);
        if (data == null) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, "APP_SHORTCUT_LAUNCH_FAILED",
                    "slot_id=" + slotId
                            + ";reason=empty-slot;mechanism=launcher-component-slot");
            return;
        }

        try {
            Intent launch = new Intent(context, LauncherActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            if (data.isSplit()) {
                VerificationEvidenceRuntime.recordPassiveEvent(
                        context, "SPLIT_SHORTCUT_INVOKED",
                        "slot_id=" + slotId + ";captured_left=" + data.leftPackage
                                + ";captured_right=" + data.rightPackage
                                + ";mechanism=launcher-component-slot");
                launch.setAction("com.dolphin.launcher.v1.LAUNCH_SPLIT_SHORTCUT")
                        .putExtra("split_left_package", data.leftPackage)
                        .putExtra("split_right_package", data.rightPackage);
                context.startActivity(launch);
                VerificationEvidenceRuntime.recordPassiveEvent(
                        context, "SPLIT_SHORTCUT_LAUNCH_DISPATCHED",
                        "slot_id=" + slotId + ";captured_left=" + data.leftPackage
                                + ";captured_right=" + data.rightPackage
                                + ";verification=required");
                return;
            }

            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, "APP_SHORTCUT_INVOKED",
                    "slot_id=" + slotId + ";package=" + data.packageName
                            + ";mechanism=launcher-component-slot");
            launch.setAction("com.dolphin.launcher.v1.OPEN_PACKAGE")
                    .putExtra("package", data.packageName);
            context.startActivity(launch);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, "APP_SHORTCUT_LAUNCH_DISPATCHED",
                    "slot_id=" + slotId + ";package=" + data.packageName
                            + ";verification=required");
        } catch (Throwable t) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,
                    data.isSplit()
                            ? "SPLIT_SHORTCUT_LAUNCH_FAILED"
                            : "APP_SHORTCUT_LAUNCH_FAILED",
                    "slot_id=" + slotId + ";error=" + t.getClass().getSimpleName()
                            + ";mechanism=launcher-component-slot");
        }
    }

    private static boolean assign(Context context, SlotData data) {
        int slot = findExisting(context, data);
        if (slot < 0) slot = findEmpty(context);
        String successEvent = data.isSplit()
                ? "SPLIT_SHORTCUT_SLOT_ASSIGNED" : "APP_SHORTCUT_SLOT_ASSIGNED";
        String failureEvent = data.isSplit()
                ? "SPLIT_SHORTCUT_SLOT_ASSIGN_FAILED" : "APP_SHORTCUT_SLOT_ASSIGN_FAILED";

        if (slot < 0) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, failureEvent,
                    data.describe()
                            + ";reason=no-free-slot;mechanism=launcher-component-slot");
            return false;
        }

        String encoded = data.encode();
        if (encoded == null
                || !prefs(context).edit().putString(key(slot), encoded).commit()) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, failureEvent,
                    data.describe() + ";slot_id=" + slotId(slot)
                            + ";reason=persist-failed;mechanism=launcher-component-slot");
            return false;
        }

        ComponentName component = component(context, slot);
        try {
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            int state = pm.getComponentEnabledSetting(component);
            boolean enabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            if (!enabled) {
                prefs(context).edit().remove(key(slot)).commit();
                VerificationEvidenceRuntime.recordPassiveEvent(
                        context, failureEvent,
                        data.describe() + ";slot_id=" + slotId(slot)
                                + ";reason=component-not-enabled;state=" + state
                                + ";mechanism=launcher-component-slot");
                return false;
            }

            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, successEvent,
                    data.describe() + ";slot_id=" + slotId(slot)
                            + ";component=" + component.flattenToShortString()
                            + ";component_enabled=true"
                            + ";launcher_visibility_verified=false"
                            + ";mechanism=launcher-component-slot");
            return true;
        } catch (Throwable t) {
            prefs(context).edit().remove(key(slot)).commit();
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, failureEvent,
                    data.describe() + ";slot_id=" + slotId(slot)
                            + ";error=" + t.getClass().getSimpleName()
                            + ";mechanism=launcher-component-slot");
            return false;
        }
    }

    private static boolean clearSlot(Context context, int slot) {
        ComponentName component = component(context, slot);
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            return prefs(context).edit().remove(key(slot)).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int findExisting(Context context, SlotData target) {
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            SlotData data = read(context, slot);
            if (data != null && data.sameTarget(target)) return slot;
        }
        return -1;
    }

    private static int findEmpty(Context context) {
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            if (read(context, slot) == null) return slot;
        }
        return -1;
    }

    private static SlotData read(Context context, int slot) {
        if (slot < 1 || slot > SLOT_COUNT) return null;
        String raw = prefs(context).getString(key(slot), null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return SlotData.from(new JSONObject(raw));
        } catch (Throwable t) {
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context, "APP_SHORTCUT_SLOT_CORRUPT",
                    "slot_id=" + slotId(slot)
                            + ";error=" + t.getClass().getSimpleName());
            return null;
        }
    }

    private static ComponentName component(Context context, int slot) {
        String suffix = slot < 10 ? "0" + slot : String.valueOf(slot);
        return new ComponentName(
                context.getPackageName(),
                context.getPackageName() + ".DrawerSlotActivities$Slot" + suffix);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(int slot) {
        return KEY_PREFIX + slot;
    }

    private static String slotId(int slot) {
        return "drawer_slot_" + (slot < 10 ? "0" + slot : String.valueOf(slot));
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static final class SlotData {
        final String kind;
        final String packageName;
        final String leftPackage;
        final String rightPackage;
        final String label;

        private SlotData(
                String kind,
                String packageName,
                String leftPackage,
                String rightPackage,
                String label) {
            this.kind = kind;
            this.packageName = packageName == null ? "" : packageName;
            this.leftPackage = leftPackage == null ? "" : leftPackage;
            this.rightPackage = rightPackage == null ? "" : rightPackage;
            this.label = label == null ? "" : label;
        }

        static SlotData app(String packageName, String label) {
            return new SlotData(KIND_APP, packageName, "", "", label);
        }

        static SlotData split(String left, String right, String label) {
            return new SlotData(KIND_SPLIT, "", left, right, label);
        }

        static SlotData from(JSONObject o) {
            return new SlotData(
                    o.optString("kind", ""),
                    o.optString("package", ""),
                    o.optString("left", ""),
                    o.optString("right", ""),
                    o.optString("label", ""));
        }

        boolean isSplit() {
            return KIND_SPLIT.equals(kind);
        }

        boolean sameTarget(SlotData other) {
            if (other == null || !safeEquals(kind, other.kind)) return false;
            if (isSplit()) {
                return safeEquals(leftPackage, other.leftPackage)
                        && safeEquals(rightPackage, other.rightPackage);
            }
            return safeEquals(packageName, other.packageName);
        }

        String describe() {
            return isSplit()
                    ? "left=" + leftPackage + ";right=" + rightPackage
                    : "package=" + packageName;
        }

        String encode() {
            try {
                JSONObject o = new JSONObject();
                o.put("kind", kind);
                o.put("package", packageName);
                o.put("left", leftPackage);
                o.put("right", rightPackage);
                o.put("label", label);
                return o.toString();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
