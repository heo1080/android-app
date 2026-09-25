package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Set;

public final class BootAutoLaunchRuntime {
    private BootAutoLaunchRuntime() {}

    public static void dispatch(Context context) {
        Context app=context.getApplicationContext();
        SharedPreferences p=app.getSharedPreferences(LauncherActivity.PREFS,Context.MODE_PRIVATE);
        if(!p.getBoolean("autostart_enabled",true)){
            VerificationEvidenceRuntime.recordPassiveEvent(app,"AUTOSTART_SKIPPED","source=boot-receiver;reason=master-disabled");
            return;
        }
        ArrayList<String> packages=new ArrayList<>(AutoStartStore.read(p));
        VerificationEvidenceRuntime.recordPassiveEvent(app,"AUTOSTART_BOOT_BATCH","registered="+packages.size());
        Handler h=new Handler(Looper.getMainLooper());
        for(int i=0;i<packages.size();i++){
            String pkg=packages.get(i);
            long delay=AutoStartStore.delayMs(p,pkg,i);
            boolean media=AutoStartStore.mediaEnabled(p,pkg);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"AUTOSTART_SCHEDULED",
                    "package="+pkg+";delay_ms="+delay+";media="+media+";index="+i);
            h.postDelayed(()->{
                if(media) BackgroundMediaRuntime.requestPlay(app,pkg);
                else launch(app,pkg,delay);
            },delay);
        }
    }

    private static void launch(Context app,String pkg,long delay){
        PackageManager pm=app.getPackageManager();
        Intent i=pm.getLaunchIntentForPackage(pkg);
        if(i==null){
            VerificationEvidenceRuntime.recordPassiveEvent(app,"AUTOSTART_LAUNCH_FAILED","package="+pkg+";reason=no-launch-intent");
            return;
        }
        try{
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            app.startActivity(i);
            VerificationEvidenceRuntime.recordPassiveEvent(app,"AUTOSTART_LAUNCH_DISPATCHED","package="+pkg+";delay_ms="+delay);
        }catch(Exception e){
            VerificationEvidenceRuntime.recordPassiveEvent(app,"AUTOSTART_LAUNCH_FAILED","package="+pkg+";reason="+e.getClass().getSimpleName());
        }
    }
}
