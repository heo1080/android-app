package com.dolphin.launcher.v1;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.lang.reflect.Method;

/**
 * Read-only capability inventory for the planned popup/freeform multi-window path.
 *
 * This class intentionally performs no windowing-mode launch, shell command, hidden-API
 * invocation, or vehicle write. It only records whether public/system capability hints
 * are visible on the current head unit so a later implementation can be evidence-backed.
 */
final class PopupMultiWindowCapabilityProbe {
    private static final String FREEFORM_FEATURE =
            "android.software.freeform_window_management";
    private static final String FREEFORM_SETTING =
            "enable_freeform_support";

    private PopupMultiWindowCapabilityProbe() {}

    static String capture(Context context, String targetPackage) {
        if (context == null) return "context_missing";

        PackageManager pm = context.getPackageManager();
        boolean freeformFeature = false;
        boolean launchWindowingMethodPresent = false;
        boolean targetLaunchable = false;
        String globalFreeformSetting = "unavailable";
        String packageName = targetPackage == null ? "" : targetPackage.trim();

        try {
            freeformFeature = pm.hasSystemFeature(FREEFORM_FEATURE);
        } catch (Throwable ignored) {
        }

        try {
            for (Method method : ActivityOptions.class.getDeclaredMethods()) {
                if ("setLaunchWindowingMode".equals(method.getName())) {
                    launchWindowingMethodPresent = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            int value = Settings.Global.getInt(
                    context.getContentResolver(), FREEFORM_SETTING, -1);
            globalFreeformSetting = Integer.toString(value);
        } catch (Throwable t) {
            globalFreeformSetting = "read_failed:" + t.getClass().getSimpleName();
        }

        if (!packageName.isEmpty()) {
            try {
                Intent launch = pm.getLaunchIntentForPackage(packageName);
                targetLaunchable = launch != null && launch.getComponent() != null;
            } catch (Throwable ignored) {
            }
        }

        String note = "feature_id=POPUP_MULTIWINDOW_APP"
                + ";target_package=" + safe(packageName)
                + ";target_launchable=" + targetLaunchable
                + ";freeform_feature=" + freeformFeature
                + ";launch_windowing_api_present=" + launchWindowingMethodPresent
                + ";global_enable_freeform_support=" + safe(globalFreeformSetting)
                + ";popup_mode=blocked"
                + ";windowing_mode_request=false"
                + ";shell_command=false"
                + ";hidden_api_invocation=false"
                + ";vehicle_write=false"
                + ";actuation=false";

        VerificationEvidenceRuntime.recordPassiveEvent(
                context, "POPUP_MULTIWINDOW_CAPABILITY", note);
        return note;
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace(';', '_').replace('\n', '_').replace('\r', '_');
    }
}
