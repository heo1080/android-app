package com.dolphin.launcher.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Boot work must not depend on the receiver process remaining alive after
 * onReceive() returns. goAsync() gives the evidence retry a bounded receiver
 * lifetime; the durable on-disk queue remains the source of truth if Android
 * still terminates the process.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Context app = context.getApplicationContext();
        app.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(LauncherActivity.KEY_PENDING_AUTOSTART, false)
                .apply();
        VerificationEvidenceRuntime.ensureProcessSession(app, "vehicle-boot");
        VerificationEvidenceRuntime.recordPassiveEvent(app, "BOOT_COMPLETED", action);
        BootAutoLaunchRuntime.dispatch(app);

        PendingResult pending = goAsync();
        VerificationEvidenceRuntime.retryPendingUploadsAsync(app, () -> {
            try {
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app, "BOOT_UPLOAD_RETRY_FINISHED", "durable queue retry completed");
            } finally {
                pending.finish();
            }
        });
    }
}
