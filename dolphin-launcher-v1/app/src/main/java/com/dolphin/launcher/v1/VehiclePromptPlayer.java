package com.dolphin.launcher.v1;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;

/**
 * Owned fixed-prompt playback core. It intentionally does not use raw TTS.
 * Prompt audio is loaded only from V1 package resources; missing assets remain
 * explicit evidence instead of silently substituting another sound.
 */
public final class VehiclePromptPlayer {
    private static final String TAG="V1_PROMPT_AUDIO";
    private final Context app;
    private final SoundPool pool;
    private final Map<String,Integer> sounds=new HashMap<>();
    private final Map<Integer,Boolean> loaded=new HashMap<>();
    private final Map<Integer,String> promptBySound=new HashMap<>();

    public VehiclePromptPlayer(Context context) {
        app=context.getApplicationContext();
        AudioAttributes attrs=new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        pool=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
        pool.setOnLoadCompleteListener((sp,id,status)->{
            boolean ok=status==0;
            loaded.put(id,ok);
            String prompt=promptBySound.get(id);
            VerificationEvidenceRuntime.recordPassiveEvent(app,
                    ok?"VOICE_ASSET_READY":"VOICE_ASSET_LOAD_FAILED",
                    String.valueOf(prompt)+" status="+status);
        });
    }

    public void preload(String... promptIds) {
        for(String id:promptIds) {
            int res=app.getResources().getIdentifier("voice_"+id,"raw",app.getPackageName());
            if(res==0) {
                VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_ASSET_MISSING",id);
                continue;
            }
            int soundId=pool.load(app,res,1);
            sounds.put(id,soundId);
            promptBySound.put(soundId,id);
        }
    }

    public void play(String promptId,String phrase) {
        Integer sound=sounds.get(promptId);
        if(sound==null || !Boolean.TRUE.equals(loaded.get(sound))) {
            VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_PROMPT_NOT_READY",
                    promptId+" phrase="+phrase);
            return;
        }
        int stream=pool.play(sound,1f,1f,1,0,1f);
        if(stream==0) {
            VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_PLAY_FAILED",promptId);
        } else {
            Log.i(TAG,"played "+promptId);
            VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_PLAY_REQUESTED",promptId);
        }
    }

    public void release() {
        pool.release();
        sounds.clear();
        loaded.clear();
        promptBySound.clear();
    }
}
