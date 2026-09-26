package com.dolphin.launcher.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Process-independent receiver for boot autostart items and background-media
 * continuation stages scheduled through AlarmManager.
 */
public final class BootAutoLaunchReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(intent==null) return;
        String action=intent.getAction();

        if(BootAutoLaunchRuntime.ACTION_EXECUTE.equals(action)){
            String pkg=intent.getStringExtra(BootAutoLaunchRuntime.EXTRA_PACKAGE);
            long delay=intent.getLongExtra(BootAutoLaunchRuntime.EXTRA_DELAY_MS,0L);
            boolean media=intent.getBooleanExtra(BootAutoLaunchRuntime.EXTRA_MEDIA,false);
            int index=intent.getIntExtra(BootAutoLaunchRuntime.EXTRA_INDEX,-1);
            BootAutoLaunchRuntime.execute(context,pkg,delay,media,index);
            return;
        }

        if(BackgroundMediaRuntime.ACTION_MEDIA_PLAY_STAGE.equals(action)){
            String pkg=intent.getStringExtra(BackgroundMediaRuntime.EXTRA_PACKAGE);
            BackgroundMediaRuntime.executeTargetSessionPlay(context,pkg);
            return;
        }

        if(BackgroundMediaRuntime.ACTION_MEDIA_READBACK_STAGE.equals(action)){
            String pkg=intent.getStringExtra(BackgroundMediaRuntime.EXTRA_PACKAGE);
            BackgroundMediaRuntime.recordTargetReadback(context,pkg);
        }
    }
}
