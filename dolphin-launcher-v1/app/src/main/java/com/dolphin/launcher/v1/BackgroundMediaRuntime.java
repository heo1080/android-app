package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

public final class BackgroundMediaRuntime {
    private BackgroundMediaRuntime(){}

    public static void requestPlay(Context context,String pkg){
        Context app=context.getApplicationContext();
        try{
            Intent launch=app.getPackageManager().getLaunchIntentForPackage(pkg);
            if(launch==null){
                VerificationEvidenceRuntime.recordPassiveEvent(app,"BACKGROUND_MEDIA_PLAY_FAILED","package="+pkg+";reason=no-launch-intent");
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            app.startActivity(launch);
            VerificationEvidenceRuntime.recordPassiveEvent(app,"BACKGROUND_MEDIA_APP_DISPATCHED","package="+pkg);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->sendPlay(app,pkg),1200L);
        }catch(Exception e){
            VerificationEvidenceRuntime.recordPassiveEvent(app,"BACKGROUND_MEDIA_PLAY_FAILED","package="+pkg+";reason="+e.getClass().getSimpleName());
        }
    }

    private static void sendPlay(Context app,String pkg){
        try{
            AudioManager am=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
            if(am==null) throw new IllegalStateException("no-audio-manager");
            long now=SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY,0));
            am.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_PLAY,0));
            VerificationEvidenceRuntime.recordPassiveEvent(app,"BACKGROUND_MEDIA_PLAY_REQUESTED","package="+pkg+";verification=required");
        }catch(Exception e){
            VerificationEvidenceRuntime.recordPassiveEvent(app,"BACKGROUND_MEDIA_PLAY_FAILED","package="+pkg+";reason="+e.getClass().getSimpleName());
        }
    }
}
