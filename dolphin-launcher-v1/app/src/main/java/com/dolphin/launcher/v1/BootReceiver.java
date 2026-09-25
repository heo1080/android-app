package com.dolphin.launcher.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            context.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(LauncherActivity.KEY_PENDING_AUTOSTART, true)
                    .apply();
            VerificationEvidenceRuntime.ensureProcessSession(context, "vehicle-boot");
            VerificationEvidenceRuntime.recordPassiveEvent(context, "BOOT_COMPLETED", action);
            VerificationEvidenceRuntime.retryPendingUploadsAsync(context);
        }
    }
}
