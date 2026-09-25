package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Sound-position request model. Vehicle routing remains fail-closed until
 * real-car readback proves the BYD mapping.
 */
public final class OwnedSoundPosition {
    public static final int MIN=-10, MAX=10;
    private final Context app;
    private final SharedPreferences prefs;
    public OwnedSoundPosition(Context c){ app=c.getApplicationContext(); prefs=app.getSharedPreferences("v1_sound_position",Context.MODE_PRIVATE); }
    public int balance(){ return clamp(prefs.getInt("balance",0)); }
    public int fader(){ return clamp(prefs.getInt("fader",0)); }
    public void set(int balance,int fader){
        int b=clamp(balance), f=clamp(fader);
        prefs.edit().putInt("balance",b).putInt("fader",f).apply();
        VerificationEvidenceRuntime.recordPassiveEvent(app,"SOUND_POSITION_REQUEST",
                "balance="+b+";fader="+f+";target=preview-only;vehicle_route_write=false;state=BETA");
    }
    public void driverCenter(){ set(-6,-4); VerificationEvidenceRuntime.recordPassiveEvent(app,"SOUND_POSITION_PRESET","preset=driver-center;vehicle_route_write=false"); }
    public void reset(){ set(0,0); }
    private int clamp(int v){ return Math.max(MIN,Math.min(MAX,v)); }
}
