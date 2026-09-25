package com.dolphin.launcher.v1;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * Target-scoped background media autoplay.
 *
 * V1 must never treat a global media key as a package-targeted command. After the
 * configured app is launched, this runtime asks MediaSessionManager for active
 * sessions through the already-declared notification-listener component and
 * sends play() only to a controller whose package exactly matches the configured
 * target. If access/session discovery is unavailable, playback fails closed.
 */
public final class BackgroundMediaRuntime {
    private static final long SESSION_DISCOVERY_DELAY_MS=1200L;
    private static final long PLAYBACK_READBACK_DELAY_MS=900L;

    private BackgroundMediaRuntime(){}

    public static void requestPlay(Context context,String pkg){
        Context app=context.getApplicationContext();
        try{
            Intent launch=app.getPackageManager().getLaunchIntentForPackage(pkg);
            if(launch==null){
                fail(app,pkg,"no-launch-intent");
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            app.startActivity(launch);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_APP_DISPATCHED",
                    "package="+pkg+";session_targeting=required");
            new Handler(Looper.getMainLooper()).postDelayed(
                    ()->requestTargetSessionPlay(app,pkg),SESSION_DISCOVERY_DELAY_MS);
        }catch(Exception e){
            fail(app,pkg,"launch-"+e.getClass().getSimpleName());
        }
    }

    private static void requestTargetSessionPlay(Context app,String pkg){
        try{
            MediaSessionManager manager=
                    (MediaSessionManager)app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if(manager==null){
                fail(app,pkg,"no-media-session-manager");
                return;
            }

            ComponentName listener=new ComponentName(app,NavNotificationEvidenceService.class);
            List<MediaController> sessions=manager.getActiveSessions(listener);
            MediaController target=null;
            int activeCount=sessions==null?0:sessions.size();
            int packageMatches=0;

            if(sessions!=null){
                for(MediaController controller:sessions){
                    if(controller!=null && pkg.equals(controller.getPackageName())){
                        packageMatches++;
                        if(target==null) target=controller;
                    }
                }
            }

            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_SESSION_SCAN",
                    "target_package="+pkg
                            +";active_sessions="+activeCount
                            +";target_matches="+packageMatches
                            +";global_media_key=false");

            if(target==null){
                fail(app,pkg,"target-session-unavailable");
                return;
            }

            PlaybackState before=target.getPlaybackState();
            int beforeState=before==null?-1:before.getState();
            String sessionPackage=target.getPackageName();
            target.getTransportControls().play();

            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_TARGET_SESSION_FOUND",
                    "target_package="+pkg
                            +";session_package="+sessionPackage
                            +";state_before="+beforeState);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_PLAY_REQUESTED",
                    "package="+pkg
                            +";session_package="+sessionPackage
                            +";method=target-media-session"
                            +";global_media_key=false"
                            +";verification=required");

            MediaController selected=target;
            new Handler(Looper.getMainLooper()).postDelayed(
                    ()->recordReadback(app,pkg,selected),PLAYBACK_READBACK_DELAY_MS);
        }catch(SecurityException e){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_SESSION_ACCESS_REQUIRED",
                    "package="+pkg
                            +";listener="+NavNotificationEvidenceService.class.getName()
                            +";reason="+e.getClass().getSimpleName()
                            +";global_fallback=false");
            fail(app,pkg,"media-session-access-required");
        }catch(Exception e){
            fail(app,pkg,"session-"+e.getClass().getSimpleName());
        }
    }

    private static void recordReadback(Context app,String pkg,MediaController controller){
        try{
            PlaybackState after=controller.getPlaybackState();
            int state=after==null?-1:after.getState();
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_PLAYBACK_READBACK",
                    "package="+pkg
                            +";session_package="+controller.getPackageName()
                            +";state_after="+state
                            +";audible_success=operator-verification-required");
        }catch(Exception e){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_PLAYBACK_READBACK_FAILED",
                    "package="+pkg+";reason="+e.getClass().getSimpleName());
        }
    }

    private static void fail(Context app,String pkg,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(
                app,"BACKGROUND_MEDIA_PLAY_FAILED",
                "package="+pkg+";reason="+reason+";global_fallback=false");
    }
}
