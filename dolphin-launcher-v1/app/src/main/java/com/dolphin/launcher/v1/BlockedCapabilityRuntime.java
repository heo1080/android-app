package com.dolphin.launcher.v1;

import android.content.Context;

/** Explicit fail-closed surfaces for features that must not actuate without real-car evidence. */
public final class BlockedCapabilityRuntime {
    private BlockedCapabilityRuntime(){}

    public static void capture(Context context){
        blocked(context,"PER_APP_DRIVER_SPEAKER_ROUTING","audible driver-only route not verified");
        blocked(context,"FSD_OBJECT_LANE_MODEL","verified object/lane sensor source absent");
        blocked(context,"SEAT_MEMORY_M1_M3","verified seat getter/setter/readback pair absent");
        reverify(context,"REVERSE_DOWN_MIRROR","V1 mirror calibration and restore path absent");
        reverify(context,"INTERIOR_LIGHT","physical lamp response/readback not verified");
        beta(context,"AUDIO_EQUALIZER","BYD DSP EQ capability/readback mapping not verified");
        beta(context,"AUDIO_SOUND_POSITION","BYD physical sound-position routing/readback not verified");
    }

    private static void beta(Context c,String feature,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(c,"FEATURE_FAIL_CLOSED",
                "feature="+feature+";state=BETA;actuation=false;reason="+reason);
    }
    private static void blocked(Context c,String feature,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(c,"FEATURE_FAIL_CLOSED",
                "feature="+feature+";state=BLOCKED;actuation=false;reason="+reason);
    }
    private static void reverify(Context c,String feature,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(c,"FEATURE_FAIL_CLOSED",
                "feature="+feature+";state=REVERIFY_REQUIRED;actuation=false;reason="+reason);
    }
}
