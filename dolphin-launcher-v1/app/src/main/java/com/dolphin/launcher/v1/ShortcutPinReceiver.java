package com.dolphin.launcher.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ShortcutPinReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String pkg=intent==null?"":intent.getStringExtra("package");
        String id=intent==null?"":intent.getStringExtra("shortcut_id");
        VerificationEvidenceRuntime.recordPassiveEvent(context,"APP_SHORTCUT_PIN_CONFIRMED","package="+pkg+";id="+id);
    }
}
