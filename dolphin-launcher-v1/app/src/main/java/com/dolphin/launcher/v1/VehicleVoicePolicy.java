package com.dolphin.launcher.v1;

import android.content.Context;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * V1 vehicle voice policy. Every normalized vehicle state is bound to exactly one
 * prompt ID and spoken phrase in the catalog below. Callers never pass a prompt ID
 * and phrase independently, preventing cross-label regressions.
 *
 * This class is signal-source agnostic. BYD listeners may call these methods only
 * after raw->normalized mapping has evidence. UNKNOWN/raw values must not be spoken.
 */
public final class VehicleVoicePolicy {
    private static final String TAG = "V1_VEHICLE_VOICE";
    private static String lastGear, lastDrive, lastRegen, lastSnow, lastAutoHoldSwitch;
    private static String lastAutoHoldState, lastEpb, lastIcc, lastLeading;

    private static final class PromptSpec {
        final String id;
        final String phrase;

        PromptSpec(String id, String phrase) {
            this.id = id;
            this.phrase = phrase;
        }
    }

    private static final Map<String, PromptSpec> PROMPTS = new LinkedHashMap<>();

    static {
        register("GEAR","P","gear_p","P");
        register("GEAR","R","gear_r","R");
        register("GEAR","N","gear_n","N");
        register("GEAR","D","gear_d","D");
        register("DRIVE_MODE","ECO","drive_eco","에코");
        register("DRIVE_MODE","NORMAL","drive_normal","노멀");
        register("DRIVE_MODE","SPORT","drive_sport","스포츠");
        register("REGEN","STANDARD","regen_standard","회생제동 스탠다드");
        register("REGEN","HIGH","regen_high","회생제동 하이");
        register("SNOW","ON","snow_on","스노우 모드 켜짐");
        register("SNOW","OFF","snow_off","스노우 모드 꺼짐");
        register("AUTOHOLD_SWITCH","ON","autohold_on","오토홀드 켜짐");
        register("AUTOHOLD_SWITCH","OFF","autohold_off","오토홀드 꺼짐");
        register("AUTOHOLD_STATE","HELD","autohold_held","오토홀드 체결");
        register("AUTOHOLD_STATE","RELEASED","autohold_released","오토홀드 해제");
        register("EPB","HELD","epb_held","사이드 브레이크 체결");
        register("EPB","RELEASED","epb_released","사이드 브레이크 해제");
        register("ICC","ON","icc_on","ICC 켜짐");
        register("ICC","OFF","icc_off","ICC 꺼짐");
        register("LEADING_CAR","DEPARTED","leading_car_departure","전방 차량 출발");
        register("BSD","LEFT","bsd_left","왼쪽 주의");
        register("BSD","RIGHT","bsd_right","오른쪽 주의");
    }

    public interface Output {
        void playFixedPrompt(String promptId, String koreanPhrase);
    }

    private VehicleVoicePolicy() {}

    public static String[] promptIds() {
        String[] ids = new String[PROMPTS.size()];
        int i = 0;
        for (PromptSpec prompt : PROMPTS.values()) ids[i++] = prompt.id;
        return ids;
    }

    public static synchronized void gear(Context c, Output out, String value) {
        String v = upper(value);
        lastGear = emitChanged(c,out,"GEAR",lastGear,v);
    }

    public static synchronized void driveMode(Context c, Output out, String value) {
        String v=upper(value);
        lastDrive=emitChanged(c,out,"DRIVE_MODE",lastDrive,v);
    }

    public static synchronized void regen(Context c, Output out, String value) {
        String v=upper(value);
        lastRegen=emitChanged(c,out,"REGEN",lastRegen,v);
    }

    public static synchronized void snow(Context c, Output out, boolean on) {
        String v=on?"ON":"OFF";
        lastSnow=emitChanged(c,out,"SNOW",lastSnow,v);
    }

    public static synchronized void autoHoldSwitch(Context c, Output out, boolean on) {
        String v=on?"ON":"OFF";
        lastAutoHoldSwitch=emitChanged(c,out,"AUTOHOLD_SWITCH",lastAutoHoldSwitch,v);
    }

    public static synchronized void autoHoldState(Context c, Output out, boolean held) {
        String v=held?"HELD":"RELEASED";
        lastAutoHoldState=emitChanged(c,out,"AUTOHOLD_STATE",lastAutoHoldState,v);
    }

    public static synchronized void epb(Context c, Output out, boolean held) {
        String v=held?"HELD":"RELEASED";
        lastEpb=emitChanged(c,out,"EPB",lastEpb,v);
    }

    public static synchronized void icc(Context c, Output out, boolean on) {
        String v=on?"ON":"OFF";
        lastIcc=emitChanged(c,out,"ICC",lastIcc,v);
    }

    public static synchronized void leadingCarDeparture(Context c, Output out, boolean detected) {
        String v=detected?"DEPARTED":"IDLE";
        if (detected) lastLeading=emitChanged(c,out,"LEADING_CAR",lastLeading,v);
        else lastLeading=v;
    }

    public static synchronized void bsd(Context c, Output out, String side) {
        String v=upper(side);
        PromptSpec prompt=prompt("BSD",v);
        if (prompt==null) {
            unknown(c,"BSD",v);
            return;
        }
        evidence(c,"BSD",v,prompt.id);
        if(out!=null) out.playFixedPrompt(prompt.id,prompt.phrase);
    }

    private static String emitChanged(Context c, Output out, String event,
                                      String previous, String normalized) {
        if (normalized==null || normalized.length()==0) {
            unknown(c,event,normalized);
            return previous;
        }
        PromptSpec prompt=prompt(event,normalized);
        if (prompt==null) {
            unknown(c,event,normalized);
            return previous;
        }
        if (normalized.equals(previous)) return previous;
        evidence(c,event,normalized,prompt.id);
        if(out!=null) out.playFixedPrompt(prompt.id,prompt.phrase);
        return normalized;
    }

    private static void register(String event,String value,String id,String phrase) {
        String key=key(event,value);
        PromptSpec previous=PROMPTS.put(key,new PromptSpec(id,phrase));
        if(previous!=null) throw new IllegalStateException("duplicate voice state "+event+"/"+value);
    }

    private static PromptSpec prompt(String event,String value) {
        if(event==null || value==null) return null;
        return PROMPTS.get(key(event,value));
    }

    private static String key(String event,String value) {
        return event+"::"+value;
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
