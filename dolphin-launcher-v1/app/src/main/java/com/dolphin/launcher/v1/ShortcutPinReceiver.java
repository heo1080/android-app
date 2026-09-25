package com.dolphin.launcher.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ShortcutPinReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action=intent==null?"":String.valueOf(intent.getAction());
        String kind=intent==null?"":intent.getStringExtra("shortcut_kind");
        String id=intent==null?"":intent.getStringExtra("shortcut_id");

        if("split".equals(kind)
                || "com.dolphin.launcher.v1.SPLIT_SHORTCUT_PIN_RESULT".equals(action)){
            String left=intent==null?"":intent.getStringExtra("split_left");
            String right=intent==null?"":intent.getStringExtra("split_right");
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"SPLIT_SHORTCUT_PIN_CONFIRMED",
                    "shortcut_id="+id+";left="+left+";right="+right);
            return;
        }

        String pkg=intent==null?"":intent.getStringExtra("package");
        VerificationEvidenceRuntime.recordPassiveEvent(
                context,"APP_SHORTCUT_PIN_CONFIRMED",
                "package="+pkg+";id="+id);
    }
}
