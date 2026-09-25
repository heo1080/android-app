package com.dolphin.launcher.v1;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.os.SystemClock;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Map<String,Long> ttsRequestedAtMs=new ConcurrentHashMap<>();
    private final Map<String,Long> ttsStartedAtMs=new ConcurrentHashMap<>();
    private final Map<String,String> ttsPromptByUtterance=new ConcurrentHashMap<>();
    private final AtomicLong utteranceSequence=new AtomicLong();
    private TextToSpeech tts;
    private final OwnedAudioEqualizer equalizer;
    private boolean ttsReady;

    public VehiclePromptPlayer(Context context) {
        app=context.getApplicationContext();
        equalizer=new OwnedAudioEqualizer(app);
        AudioAttributes attrs=new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        pool=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
        VerificationEvidenceRuntime.recordPassiveEvent(app,"EQ_PROMPT_PIPELINE",
                "backend=SoundPool;audio_session_exposed=false;eq_binding=not_available;byd_dsp_write=false");
        pool.setOnLoadCompleteListener((sp,id,status)->{
            boolean ok=status==0;
            loaded.put(id,ok);
            String prompt=promptBySound.get(id);
            VerificationEvidenceRuntime.recordPassiveEvent(app,
                    ok?"VOICE_ASSET_READY":"VOICE_ASSET_LOAD_FAILED",
                    String.valueOf(prompt)+" status="+status);
        });
        tts=new TextToSpeech(app,status->{
            if(status!=TextToSpeech.SUCCESS) {
                VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_TTS_INIT_FAILED","status="+status);
                return;
            }
            int lang=tts.setLanguage(Locale.KOREAN);
            ttsReady=lang!=TextToSpeech.LANG_MISSING_DATA && lang!=TextToSpeech.LANG_NOT_SUPPORTED;
            VerificationEvidenceRuntime.recordPassiveEvent(app,
                    ttsReady?"VOICE_TTS_READY":"VOICE_TTS_KOREAN_UNAVAILABLE","lang="+lang);
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                long now=SystemClock.elapsedRealtime();
                Long requested=ttsRequestedAtMs.get(utteranceId);
                ttsStartedAtMs.put(utteranceId,now);
                String prompt=ttsPromptByUtterance.get(utteranceId);
                VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_TTS_START",
                        "prompt="+prompt+";request_to_start_ms="+elapsed(requested,now)
                                +";utterance="+utteranceId);
            }

            @Override public void onDone(String utteranceId) {
                long now=SystemClock.elapsedRealtime();
                Long started=ttsStartedAtMs.remove(utteranceId);
                Long requested=ttsRequestedAtMs.remove(utteranceId);
                String prompt=ttsPromptByUtterance.remove(utteranceId);
                VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_TTS_DONE",
                        "prompt="+prompt+";request_to_done_ms="+elapsed(requested,now)
                                +";start_to_done_ms="+elapsed(started,now)
                                +";utterance="+utteranceId);
            }

            @Override public void onError(String utteranceId) {
                finishError(utteranceId,TextToSpeech.ERROR);
            }

            @Override public void onError(String utteranceId,int errorCode) {
                finishError(utteranceId,errorCode);
            }

            @Override public void onStop(String utteranceId,boolean interrupted) {
                long now=SystemClock.elapsedRealtime();
                Long requested=ttsRequestedAtMs.remove(utteranceId);
                ttsStartedAtMs.remove(utteranceId);
                String prompt=ttsPromptByUtterance.remove(utteranceId);
                VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_TTS_STOPPED",
                        "prompt="+prompt+";interrupted="+interrupted
                                +";request_to_stop_ms="+elapsed(requested,now)
                                +";utterance="+utteranceId);
            }

            private void finishError(String utteranceId,int errorCode) {
                long now=SystemClock.elapsedRealtime();
                Long requested=ttsRequestedAtMs.remove(utteranceId);
                ttsStartedAtMs.remove(utteranceId);
                String prompt=ttsPromptByUtterance.remove(utteranceId);
                VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_TTS_ERROR",
                        "prompt="+prompt+";error="+errorCode
                                +";request_to_error_ms="+elapsed(requested,now)
                                +";utterance="+utteranceId);
            }
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
            if(ttsReady && phrase!=null && !phrase.isEmpty()) {
                String utteranceId="v1_"+promptId+"_"+utteranceSequence.incrementAndGet();
                long requestedAt=SystemClock.elapsedRealtime();
                ttsRequestedAtMs.put(utteranceId,requestedAt);
                ttsPromptByUtterance.put(utteranceId,promptId);
                int result=tts.speak(phrase,TextToSpeech.QUEUE_FLUSH,null,utteranceId);
                if(result!=TextToSpeech.SUCCESS) {
                    ttsRequestedAtMs.remove(utteranceId);
                    ttsPromptByUtterance.remove(utteranceId);
                }
                VerificationEvidenceRuntime.recordPassiveEvent(app,
                        result==TextToSpeech.SUCCESS?"VOICE_TTS_FALLBACK_REQUESTED":"VOICE_TTS_FALLBACK_FAILED",
                        promptId+" phrase="+phrase+" result="+result+" utterance="+utteranceId);
            } else {
                VerificationEvidenceRuntime.recordPassiveEvent(app,"VOICE_PROMPT_NOT_READY",
                        promptId+" phrase="+phrase);
            }
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
        equalizer.releaseEffect();
        pool.release();
        if(tts!=null) {
            tts.stop();
            tts.shutdown();
            tts=null;
        }
        ttsReady=false;
        sounds.clear();
        loaded.clear();
        promptBySound.clear();
        ttsRequestedAtMs.clear();
        ttsStartedAtMs.clear();
        ttsPromptByUtterance.clear();
    }

    private static long elapsed(Long start,long end) {
        return start==null?-1L:Math.max(0L,end-start);
    }
}
