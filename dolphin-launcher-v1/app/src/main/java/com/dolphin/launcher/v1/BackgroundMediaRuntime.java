package com.dolphin.launcher.v1;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import java.util.List;

/**
 * Target-scoped background media autoplay.
 *
 * V1 never treats a global media key as a package-targeted command. The configured
 * app is launched first, then process-independent AlarmManager stages re-enter
 * BootAutoLaunchReceiver to discover the exact target MediaSession, request PLAY,
 * and later re-discover the target session for PlaybackState readback.
 *
 * If exact alarm access or target-session access is unavailable, the flow fails
 * closed. PLAY dispatch is evidence only and is never treated as audible success.
 */
public final class BackgroundMediaRuntime {
    static final String ACTION_MEDIA_PLAY_STAGE =
            "com.dolphin.launcher.v1.EXECUTE_BACKGROUND_MEDIA_PLAY";
    static final String ACTION_MEDIA_READBACK_STAGE =
            "com.dolphin.launcher.v1.READBACK_BACKGROUND_MEDIA_PLAY";
    static final String EXTRA_PACKAGE = "background_media_package";

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
                    "package="+pkg+";session_targeting=required;continuation=alarm-receiver");

            if(!scheduleStage(
                    app,pkg,ACTION_MEDIA_PLAY_STAGE,SESSION_DISCOVERY_DELAY_MS,
                    "session-discovery")){
                fail(app,pkg,"session-discovery-stage-schedule-failed");
            }
        }catch(Exception e){
            fail(app,pkg,"launch-"+e.getClass().getSimpleName());
        }
    }

    static void executeTargetSessionPlay(Context context,String pkg){
        Context app=context.getApplicationContext();
        if(pkg==null || pkg.trim().isEmpty()){
            fail(app,pkg,"play-stage-missing-package");
            return;
        }
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
                            +";global_media_key=false"
                            +";stage=alarm-receiver");

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

            if(!scheduleStage(
                    app,pkg,ACTION_MEDIA_READBACK_STAGE,PLAYBACK_READBACK_DELAY_MS,
                    "playback-readback")){
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"BACKGROUND_MEDIA_PLAYBACK_READBACK_FAILED",
                        "package="+pkg+";reason=readback-stage-schedule-failed");
            }
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

    static void recordTargetReadback(Context context,String pkg){
        Context app=context.getApplicationContext();
        if(pkg==null || pkg.trim().isEmpty()){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_PLAYBACK_READBACK_FAILED",
                    "package="+pkg+";reason=readback-stage-missing-package");
            return;
        }
        try{
            MediaSessionManager manager=
                    (MediaSessionManager)app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if(manager==null){
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"BACKGROUND_MEDIA_PLAYBACK_READBACK_FAILED",
                        "package="+pkg+";reason=no-media-session-manager");
                return;
            }

            ComponentName listener=new ComponentName(app,NavNotificationEvidenceService.class);
            List<MediaController> sessions=manager.getActiveSessions(listener);
            MediaController target=null;
            if(sessions!=null){
                for(MediaController controller:sessions){
                    if(controller!=null && pkg.equals(controller.getPackageName())){
                        target=controller;
                        break;
                    }
                }
            }
            if(target==null){
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"BACKGROUND_MEDIA_PLAYBACK_READBACK_FAILED",
                        "package="+pkg+";reason=target-session-unavailable");
                return;
            }

            PlaybackState after=target.getPlaybackState();
            int state=after==null?-1:after.getState();
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_PLAYBACK_READBACK",
                    "package="+pkg
                            +";session_package="+target.getPackageName()
                            +";state_after="+state
                            +";audible_success=operator-verification-required"
                            +";stage=alarm-receiver");
        }catch(SecurityException e){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_SESSION_ACCESS_REQUIRED",
                    "package="+pkg
                            +";listener="+NavNotificationEvidenceService.class.getName()
                            +";reason="+e.getClass().getSimpleName()
                            +";stage=readback"
                            +";global_fallback=false");
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_PLAYBACK_READBACK_FAILED",
                    "package="+pkg+";reason=media-session-access-required");
        }catch(Exception e){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_PLAYBACK_READBACK_FAILED",
                    "package="+pkg+";reason="+e.getClass().getSimpleName());
        }
    }

    private static boolean scheduleStage(
            Context app,String pkg,String action,long delayMs,String stage){
        AlarmManager alarms=(AlarmManager)app.getSystemService(Context.ALARM_SERVICE);
        if(alarms==null){
            stageScheduleFailed(app,pkg,stage,delayMs,"no-alarm-manager");
            return false;
        }

        Intent intent=new Intent(app,BootAutoLaunchReceiver.class)
                .setAction(action)
                .putExtra(EXTRA_PACKAGE,pkg)
                .setData(Uri.parse("dolphin-v1://media-stage/"
                        +Uri.encode(pkg)+"/"+Uri.encode(stage)));
        int requestCode=31*pkg.hashCode()+action.hashCode();
        PendingIntent pending=PendingIntent.getBroadcast(
                app,requestCode,intent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        long triggerAt=SystemClock.elapsedRealtime()+Math.max(0L,delayMs);

        try{
            if(Build.VERSION.SDK_INT>=31 && !alarms.canScheduleExactAlarms()){
                stageScheduleFailed(app,pkg,stage,delayMs,"exact-alarm-access-required");
                return false;
            }
            if(Build.VERSION.SDK_INT>=23){
                alarms.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,triggerAt,pending);
            }else{
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,triggerAt,pending);
            }
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"BACKGROUND_MEDIA_STAGE_SCHEDULED",
                    "package="+pkg
                            +";stage="+stage
                            +";delay_ms="+delayMs
                            +";pending_identity=package-stage"
                            +";pending_data_unique=true"
                            +";scheduler=AlarmManager"
                            +";exact=true"
                            +";process_independent=true");
            return true;
        }catch(SecurityException e){
            stageScheduleFailed(app,pkg,stage,delayMs,
                    "alarm-security-"+e.getClass().getSimpleName());
            return false;
        }catch(Exception e){
            stageScheduleFailed(app,pkg,stage,delayMs,
                    "alarm-"+e.getClass().getSimpleName());
            return false;
        }
    }

    private static void stageScheduleFailed(
            Context app,String pkg,String stage,long delayMs,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(
                app,"BACKGROUND_MEDIA_STAGE_SCHEDULE_FAILED",
                "package="+pkg
                        +";stage="+stage
                        +";delay_ms="+delayMs
                        +";reason="+reason
                        +";process_independent=true");
    }

    private static void fail(Context app,String pkg,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(
                app,"BACKGROUND_MEDIA_PLAY_FAILED",
                "package="+pkg+";reason="+reason+";global_fallback=false");
    }
}
