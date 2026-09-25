package com.dolphin.launcher.v1;

import android.content.Context;
import android.util.Log;

import java.util.Locale;

/**
 * V1 vehicle voice policy. UI labels and spoken phrases are deliberately separated:
 * fixed driving feedback must never feed raw "ON"/"OFF" strings into TTS.
 *
 * This class is signal-source agnostic. BYD listeners may call onState only after
 * their raw->normalized mapping has evidence. UNKNOWN/raw values must not be spoken.
 */
public final class VehicleVoicePolicy {
    private static final String TAG = "V1_VEHICLE_VOICE";
    private static String lastGear, lastDrive, lastRegen, lastSnow, lastAutoHoldSwitch;
    private static String lastAutoHoldState, lastEpb, lastIcc, lastLeading;

    public interface Output {
        void playFixedPrompt(String promptId, String koreanPhrase);
    }

    private VehicleVoicePolicy() {}

    public static synchronized void gear(Context c, Output out, String value) {
        String v = upper(value);
        String phrase = null, id = null;
        if ("P".equals(v)) { id="gear_p"; phrase="P"; }
        else if ("R".equals(v)) { id="gear_r"; phrase="R"; }
        else if ("N".equals(v)) { id="gear_n"; phrase="N"; }
        else if ("D".equals(v)) { id="gear_d"; phrase="D"; }
        lastGear = emitChanged(c,out,"GEAR",lastGear,v,id,phrase);
    }

    public static synchronized void driveMode(Context c, Output out, String value) {
        String v=upper(value), id=null, phrase=null;
        if ("ECO".equals(v)) { id="drive_eco"; phrase="에코"; }
        else if ("NORMAL".equals(v)) { id="drive_normal"; phrase="노멀"; }
        else if ("SPORT".equals(v)) { id="drive_sport"; phrase="스포츠"; }
        lastDrive=emitChanged(c,out,"DRIVE_MODE",lastDrive,v,id,phrase);
    }

    public static synchronized void regen(Context c, Output out, String value) {
        String v=upper(value), id=null, phrase=null;
        if ("STANDARD".equals(v)) { id="regen_standard"; phrase="회생제동 스탠다드"; }
        else if ("HIGH".equals(v)) { id="regen_high"; phrase="회생제동 하이"; }
        lastRegen=emitChanged(c,out,"REGEN",lastRegen,v,id,phrase);
    }

    public static synchronized void snow(Context c, Output out, boolean on) {
        String v=on?"ON":"OFF";
        lastSnow=emitChanged(c,out,"SNOW",lastSnow,v,on?"snow_on":"snow_off",
                on?"스노우 모드 켜짐":"스노우 모드 꺼짐");
    }

    public static synchronized void autoHoldSwitch(Context c, Output out, boolean on) {
        String v=on?"ON":"OFF";
        lastAutoHoldSwitch=emitChanged(c,out,"AUTOHOLD_SWITCH",lastAutoHoldSwitch,v,
                on?"autohold_on":"autohold_off",on?"오토홀드 켜짐":"오토홀드 꺼짐");
    }

    public static synchronized void autoHoldState(Context c, Output out, boolean held) {
        String v=held?"HELD":"RELEASED";
        lastAutoHoldState=emitChanged(c,out,"AUTOHOLD_STATE",lastAutoHoldState,v,
                held?"autohold_held":"autohold_released",held?"오토홀드 체결":"오토홀드 해제");
    }

    public static synchronized void epb(Context c, Output out, boolean held) {
        String v=held?"HELD":"RELEASED";
        lastEpb=emitChanged(c,out,"EPB",lastEpb,v,held?"epb_held":"epb_released",
                held?"사이드 브레이크 체결":"사이드 브레이크 해제");
    }

    public static synchronized void icc(Context c, Output out, boolean on) {
        String v=on?"ON":"OFF";
        lastIcc=emitChanged(c,out,"ICC",lastIcc,v,on?"icc_on":"icc_off",
                on?"ICC 켜짐":"ICC 꺼짐");
    }

    public static synchronized void leadingCarDeparture(Context c, Output out, boolean detected) {
        String v=detected?"DEPARTED":"IDLE";
        if (detected) lastLeading=emitChanged(c,out,"LEADING_CAR",lastLeading,v,
                "leading_car_departure","전방 차량 출발");
        else lastLeading=v;
    }

    public static synchronized void bsd(Context c, Output out, String side) {
        String v=upper(side), id=null, phrase=null;
        if ("LEFT".equals(v)) { id="bsd_left"; phrase="왼쪽 주의"; }
        else if ("RIGHT".equals(v)) { id="bsd_right"; phrase="오른쪽 주의"; }
        if (id==null) unknown(c,"BSD",v); else {
            evidence(c,"BSD",v,id);
            if(out!=null) out.playFixedPrompt(id,phrase);
        }
    }

    private static String emitChanged(Context c, Output out, String event, String previous,
                                      String normalized, String promptId, String phrase) {
        if (normalized==null || normalized.length()==0 || promptId==null) {
            unknown(c,event,normalized);
            return previous;
        }
        if (normalized.equals(previous)) return previous;
        evidence(c,event,normalized,promptId);
        if(out!=null) out.playFixedPrompt(promptId,phrase);
        return normalized;
    }

    private static void evidence(Context c,String event,String value,String promptId) {
        String d=event+" normalized="+value+" prompt="+promptId;
        Log.i(TAG,d);
        VerificationEvidenceRuntime.recordPassiveEvent(c,"VEHICLE_VOICE",d);
    }

    private static void unknown(Context c,String event,String value) {
        String d=event+" unknown="+String.valueOf(value)+" suppressed";
        Log.w(TAG,d);
        VerificationEvidenceRuntime.recordPassiveEvent(c,"VEHICLE_VOICE_UNKNOWN",d);
    }

    private static String upper(String v) {
        return v==null?null:v.trim().toUpperCase(Locale.ROOT);
    }
}
