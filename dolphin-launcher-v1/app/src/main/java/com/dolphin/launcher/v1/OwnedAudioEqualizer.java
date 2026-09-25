package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.SharedPreferences;

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
    }

    public void reset(){
        prefs.edit().putInt("bass_db",0).putInt("mid_db",0).putInt("treble_db",0).apply();
        evidence("EQ_RESET","bass=0;mid=0;treble=0");
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
