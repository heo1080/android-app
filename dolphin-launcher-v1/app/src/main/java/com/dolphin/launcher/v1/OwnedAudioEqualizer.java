package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.Equalizer;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Persistent three-band EQ settings for V1-owned audio.
 * This class deliberately performs no BYD DSP or speaker-routing writes.
 */
public final class OwnedAudioEqualizer {
    public static final int MIN_DB=-6;
    public static final int MAX_DB=6;
    private static final String PREFS="v1_owned_audio_eq";
    private final Context app;
    private final SharedPreferences prefs;
    private Equalizer effect;
    private int boundSession=-1;

    public OwnedAudioEqualizer(Context context){
        app=context.getApplicationContext();
        prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    public int bass(){ return clamp(prefs.getInt("bass_db",0)); }
    public int mid(){ return clamp(prefs.getInt("mid_db",0)); }
    public int treble(){ return clamp(prefs.getInt("treble_db",0)); }

    public void setBands(int bassDb,int midDb,int trebleDb){
        int b=clamp(bassDb), m=clamp(midDb), t=clamp(trebleDb);
        prefs.edit().putInt("bass_db",b).putInt("mid_db",m).putInt("treble_db",t).apply();
        evidence("EQ_BANDS_CHANGED","bass="+b+";mid="+m+";treble="+t);
        applyStoredBands();
    }

    public void reset(){
        prefs.edit().putInt("bass_db",0).putInt("mid_db",0).putInt("treble_db",0).apply();
        evidence("EQ_RESET","bass=0;mid=0;treble=0");
        applyStoredBands();
    }

    public AudioTrack createOwnedPcmTrack(int sampleRate){
        try{
            int min=AudioTrack.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0){ evidence("EQ_TRACK_CREATE_FAILED","min_buffer="+min); return null; }
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFormat format=new AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
            AudioTrack track=new AudioTrack(attrs,format,min,AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
            if(track.getState()!=AudioTrack.STATE_INITIALIZED){ track.release(); evidence("EQ_TRACK_CREATE_FAILED","state=uninitialized"); return null; }
            boolean bound=bindToAudioSession(track.getAudioSessionId());
            evidence(bound?"EQ_TRACK_READY":"EQ_TRACK_EQ_UNAVAILABLE","session="+track.getAudioSessionId()+";sample_rate="+sampleRate);
            return track;
        }catch(Throwable t){ evidence("EQ_TRACK_CREATE_FAILED","error="+t.getClass().getSimpleName()); return null; }
    }

    public boolean bindToAudioSession(int audioSessionId){
        releaseEffect();
        if(audioSessionId<=0){ evidence("EQ_SESSION_REJECTED","session="+audioSessionId); return false; }
        try{
            effect=new Equalizer(0,audioSessionId);
            boundSession=audioSessionId;
            effect.setEnabled(true);
            applyStoredBands();
            evidence("EQ_SESSION_BOUND","session="+audioSessionId+";bands="+effect.getNumberOfBands());
            return true;
        }catch(Throwable t){
            effect=null; boundSession=-1;
            evidence("EQ_SESSION_BIND_FAILED","session="+audioSessionId+";error="+t.getClass().getSimpleName());
            return false;
        }
    }

    public boolean applyStoredBands(){
        if(effect==null) return false;
        try{
            short bands=effect.getNumberOfBands();
            if(bands<=0) return false;
            for(short band=0;band<bands;band++){
                int group=Math.min(2,(band*3)/Math.max(1,bands));
                int db=group==0?bass():(group==1?mid():treble());
                short[] range=effect.getBandLevelRange();
                int mb=Math.max(range[0],Math.min(range[1],db*100));
                effect.setBandLevel(band,(short)mb);
            }
            evidence("EQ_APPLIED","session="+boundSession+";"+snapshot());
            return true;
        }catch(Throwable t){ evidence("EQ_APPLY_FAILED","error="+t.getClass().getSimpleName()); return false; }
    }

    public void releaseEffect(){
        if(effect!=null){ try{ effect.setEnabled(false); }catch(Throwable ignored){} effect.release(); effect=null; }
        boundSession=-1;
    }

    public float gainForBand(int db){
        return (float)Math.pow(10.0,clamp(db)/20.0);
    }

    public String snapshot(){
        return "bass="+bass()+";mid="+mid()+";treble="+treble()+";target=app-owned;byd_dsp_write=false";
    }

    private int clamp(int value){ return Math.max(MIN_DB,Math.min(MAX_DB,value)); }
    private void evidence(String event,String detail){
        VerificationEvidenceRuntime.recordPassiveEvent(app,event,detail+";target=app-owned;byd_dsp_write=false");
    }
}
