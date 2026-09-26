package com.dolphin.launcher.v1;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;

public final class BootAutoLaunchRuntime {
    static final String ACTION_EXECUTE="com.dolphin.launcher.v1.EXECUTE_BOOT_AUTOLAUNCH";
    static final String EXTRA_PACKAGE="package";
    static final String EXTRA_DELAY_MS="delay_ms";
    static final String EXTRA_MEDIA="media";
    static final String EXTRA_INDEX="index";

    private static final String KEY_LAST_BOOT_DISPATCH_ELAPSED =
            "boot_autostart_last_dispatch_elapsed";
    private static final String KEY_LAST_BOOT_DISPATCH_ACTION =
            "boot_autostart_last_dispatch_action";
    private static final long SAME_ACTION_DUPLICATE_WINDOW_MS = 10_000L;
    private static final long CROSS_ACTION_DUPLICATE_WINDOW_MS = 120_000L;
    private static long processLastDispatchElapsed = -1L;
    private static String processLastDispatchAction = "";

    private BootAutoLaunchRuntime() {}

    public static void dispatch(Context context) {
        dispatch(context, "unknown");
    }

    public static void dispatch(Context context,String sourceAction) {
        Context app=context.getApplicationContext();
        SharedPreferences p=app.getSharedPreferences(LauncherActivity.PREFS,Context.MODE_PRIVATE);
        if(!claimBootDispatch(app,p,sourceAction)) return;
        if(!p.getBoolean("autostart_enabled",true)){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"AUTOSTART_SKIPPED","source=boot-receiver;reason=master-disabled");
            return;
        }

        ArrayList<String> packages=new ArrayList<>(AutoStartStore.read(p));
        VerificationEvidenceRuntime.recordPassiveEvent(
                app,"AUTOSTART_BOOT_BATCH","registered="+packages.size());

        for(int i=0;i<packages.size();i++){
            String pkg=packages.get(i);
            long delay=AutoStartStore.delayMs(p,pkg,i);
            boolean media=AutoStartStore.mediaEnabled(p,pkg);
            schedule(app,pkg,delay,media,i);
        }
    }

    private static synchronized boolean claimBootDispatch(
            Context app,SharedPreferences p,String sourceAction){
        String action=sourceAction==null?"unknown":sourceAction;
        long now=SystemClock.elapsedRealtime();
        long storedElapsed=p.getLong(KEY_LAST_BOOT_DISPATCH_ELAPSED,-1L);
        String storedAction=p.getString(KEY_LAST_BOOT_DISPATCH_ACTION,"");
        long lastElapsed=processLastDispatchElapsed>=0L
                ? Math.max(processLastDispatchElapsed,storedElapsed)
                : storedElapsed;
        String lastAction=processLastDispatchElapsed>=storedElapsed
                ? processLastDispatchAction : storedAction;

        // elapsedRealtime resets after a kernel reboot. A lower value therefore
        // represents a new boot epoch and must never be suppressed.
        if(lastElapsed>=0L && now>=lastElapsed){
            long since=now-lastElapsed;
            long window=action.equals(lastAction)
                    ? SAME_ACTION_DUPLICATE_WINDOW_MS
                    : CROSS_ACTION_DUPLICATE_WINDOW_MS;
            if(since<window){
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"AUTOSTART_DUPLICATE_BOOT_SUPPRESSED",
                        "action="+action
                                +";last_action="+lastAction
                                +";since_ms="+since
                                +";window_ms="+window
                                +";duplicate_dispatch=false");
                return false;
            }
        }

        processLastDispatchElapsed=now;
        processLastDispatchAction=action;
        boolean persisted=p.edit()
                .putLong(KEY_LAST_BOOT_DISPATCH_ELAPSED,now)
                .putString(KEY_LAST_BOOT_DISPATCH_ACTION,action)
                .commit();
        if(!persisted){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"AUTOSTART_DEDUP_STATE_PERSIST_FAILED",
                    "action="+action+";process_guard=true");
        }
        return true;
    }

    private static void schedule(Context app,String pkg,long delay,boolean media,int index){
        AlarmManager alarms=(AlarmManager)app.getSystemService(Context.ALARM_SERVICE);
        if(alarms==null){
            scheduleFailed(app,pkg,delay,index,"no-alarm-manager");
            return;
        }

        Intent intent=new Intent(app,BootAutoLaunchReceiver.class)
                .setAction(ACTION_EXECUTE)
                .putExtra(EXTRA_PACKAGE,pkg)
                .putExtra(EXTRA_DELAY_MS,delay)
                .putExtra(EXTRA_MEDIA,media)
                .putExtra(EXTRA_INDEX,index);
        int requestCode=31*pkg.hashCode()+index;
        PendingIntent pending=PendingIntent.getBroadcast(
                app,requestCode,intent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        long triggerAt=SystemClock.elapsedRealtime()+delay;

        try{
            if(Build.VERSION.SDK_INT>=31 && !alarms.canScheduleExactAlarms()){
                scheduleFailed(app,pkg,delay,index,"exact-alarm-access-required");
                return;
            }

            if(Build.VERSION.SDK_INT>=23){
                alarms.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,triggerAt,pending);
            }else{
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,triggerAt,pending);
            }

            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"AUTOSTART_SCHEDULED",
                    "package="+pkg
                            +";delay_ms="+delay
                            +";media="+media
                            +";index="+index
                            +";scheduler=AlarmManager"
                            +";exact=true"
                            +";process_independent=true");
        }catch(SecurityException e){
            scheduleFailed(app,pkg,delay,index,"alarm-security-"+e.getClass().getSimpleName());
        }catch(Exception e){
            scheduleFailed(app,pkg,delay,index,"alarm-"+e.getClass().getSimpleName());
        }
    }

    static void execute(Context context,String pkg,long delay,boolean media,int index){
        Context app=context.getApplicationContext();
        if(pkg==null || pkg.trim().isEmpty()){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"AUTOSTART_LAUNCH_FAILED","reason=missing-package;index="+index);
            return;
        }

        VerificationEvidenceRuntime.recordPassiveEvent(
                app,"AUTOSTART_ALARM_FIRED",
                "package="+pkg
                        +";delay_ms="+delay
                        +";media="+media
                        +";index="+index
                        +";scheduler=AlarmManager");

        if(media){
            BackgroundMediaRuntime.requestPlay(app,pkg);
        }else{
            launch(app,pkg,delay,index);
        }
    }

    private static void launch(Context app,String pkg,long delay,int index){
        PackageManager pm=app.getPackageManager();
        Intent i=pm.getLaunchIntentForPackage(pkg);
        if(i==null){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"AUTOSTART_LAUNCH_FAILED",
                    "package="+pkg+";reason=no-launch-intent;index="+index);
            return;
        }
        try{
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            app.startActivity(i);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"AUTOSTART_LAUNCH_DISPATCHED",
                    "package="+pkg+";delay_ms="+delay+";index="+index);
        }catch(Exception e){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"AUTOSTART_LAUNCH_FAILED",
                    "package="+pkg+";reason="+e.getClass().getSimpleName()+";index="+index);
        }
    }

    private static void scheduleFailed(Context app,String pkg,long delay,int index,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(
                app,"AUTOSTART_SCHEDULE_FAILED",
                "package="+pkg+";delay_ms="+delay+";index="+index+";reason="+reason);
    }
}
