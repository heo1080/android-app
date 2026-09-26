package com.dolphin.launcher.v1;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Read-only Now Playing snapshot. Only configured target packages are eligible.
 * Missing notification-listener/session access fails closed and never invents metadata.
 */
public final class MediaNowPlayingRuntime {
    private MediaNowPlayingRuntime(){}

    public static final class Snapshot {
        public final boolean available;
        public final String packageName;
        public final String title;
        public final String artist;
        public final String playback;
        public final String reason;

        Snapshot(boolean available, String packageName, String title,
                 String artist, String playback, String reason) {
            this.available=available;
            this.packageName=packageName;
            this.title=title;
            this.artist=artist;
            this.playback=playback;
            this.reason=reason;
        }
    }

    public static Snapshot read(Context context, Set<String> targetPackages) {
        if (targetPackages == null || targetPackages.isEmpty()) {
            return unavailable("no-configured-media-app");
        }
        Context app=context.getApplicationContext();
        try {
            MediaSessionManager manager=(MediaSessionManager)
                    app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if(manager==null) return unavailable("media-session-service-unavailable");

            ComponentName listener=new ComponentName(
                    app,NavNotificationEvidenceService.class);
            List<MediaController> controllers=manager.getActiveSessions(listener);
            if(controllers==null) controllers= Collections.emptyList();

            MediaController best=null;
            int bestRank=-1;
            for(MediaController controller:controllers){
                if(controller==null)continue;
                String pkg=controller.getPackageName();
                if(pkg==null || !targetPackages.contains(pkg))continue;
                PlaybackState state=controller.getPlaybackState();
                int rank=playbackRank(state);
                if(best==null || rank>bestRank){
                    best=controller;
                    bestRank=rank;
                }
            }
            if(best==null) return unavailable("target-session-unavailable");

            MediaMetadata metadata=best.getMetadata();
            String title=firstNonEmpty(
                    metadataString(metadata,MediaMetadata.METADATA_KEY_TITLE),
                    metadataString(metadata,MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    "제목 정보 없음");
            String artist=firstNonEmpty(
                    metadataString(metadata,MediaMetadata.METADATA_KEY_ARTIST),
                    metadataString(metadata,MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    "아티스트 정보 없음");
            return new Snapshot(
                    true,
                    best.getPackageName(),
                    title,
                    artist,
                    playbackLabel(best.getPlaybackState()),
                    "target-session");
        } catch (SecurityException e) {
            return unavailable("notification-listener-access-required");
        } catch (Throwable t) {
            return unavailable("snapshot-failed-"+t.getClass().getSimpleName());
        }
    }

    private static int playbackRank(PlaybackState state){
        if(state==null)return 0;
        if(state.getState()==PlaybackState.STATE_PLAYING)return 3;
        if(state.getState()==PlaybackState.STATE_BUFFERING)return 2;
        if(state.getState()==PlaybackState.STATE_PAUSED)return 1;
        return 0;
    }

    private static String playbackLabel(PlaybackState state){
        if(state==null)return "UNKNOWN";
        switch(state.getState()){
            case PlaybackState.STATE_PLAYING:return "PLAYING";
            case PlaybackState.STATE_PAUSED:return "PAUSED";
            case PlaybackState.STATE_BUFFERING:return "BUFFERING";
            case PlaybackState.STATE_STOPPED:return "STOPPED";
            default:return "STATE_"+state.getState();
        }
    }

    private static String metadataString(MediaMetadata metadata,String key){
        if(metadata==null)return null;
        CharSequence value=metadata.getText(key);
        return value==null?null:value.toString();
    }

    private static String firstNonEmpty(String a,String b,String fallback){
        if(a!=null&&!a.trim().isEmpty())return a.trim();
        if(b!=null&&!b.trim().isEmpty())return b.trim();
        return fallback;
    }

    private static Snapshot unavailable(String reason){
        return new Snapshot(false,null,null,null,"UNAVAILABLE",reason);
    }
}
