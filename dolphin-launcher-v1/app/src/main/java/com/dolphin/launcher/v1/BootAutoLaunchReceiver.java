package com.dolphin.launcher.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Executes one process-independent boot autostart item scheduled by AlarmManager. */
public final class BootAutoLaunchReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(intent==null || !BootAutoLaunchRuntime.ACTION_EXECUTE.equals(intent.getAction())) return;
        String pkg=intent.getStringExtra(BootAutoLaunchRuntime.EXTRA_PACKAGE);
        long delay=intent.getLongExtra(BootAutoLaunchRuntime.EXTRA_DELAY_MS,0L);
        boolean media=intent.getBooleanExtra(BootAutoLaunchRuntime.EXTRA_MEDIA,false);
        int index=intent.getIntExtra(BootAutoLaunchRuntime.EXTRA_INDEX,-1);
        BootAutoLaunchRuntime.execute(context,pkg,delay,media,index);
    }
}
