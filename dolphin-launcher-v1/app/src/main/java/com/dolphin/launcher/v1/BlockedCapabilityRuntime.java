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
        blocked(context,"AVM_DVR_CAPTURE","verified AVM/camera source absent");
        blocked(context,"CONTINUOUS_PARKING_RECORDING","camera/storage/power pipeline not verified");
        blocked(context,"SD_CARD_PRIMARY_STORAGE","removable SD identity and write/readback not verified");
        blocked(context,"PARKING_OBJECT_EVENT_DETECTION","verified camera/model provenance absent");
        blocked(context,"COMPANION_REMOTE_VIDEO","authenticated remote-video transport and camera source absent");
        blocked(context,"COMPANION_PARKING_LOCATION","location consent/transport path not implemented");
        blocked(context,"COMPANION_VEHICLE_STATUS","authenticated read-only remote status transport absent");
        blocked(context,"BVM_FISHEYE_CORRECTION","camera calibration evidence absent");
        blocked(context,"BLE_ONLY_AUTOLOCK","authorized BYD BLE key/protocol evidence absent");
        blocked(context,"LOCAL_REMOTE_CONTROL","verified command/readback pair absent");
        unsupported(context,"THIRD_PARTY_RESEARCH_CANDIDATES","reference apps do not prove Dolphin V1 API compatibility");
        blocked(context,"APP_HEALTH","runtime health controller not implemented");
        blocked(context,"SAFE_MODE","integrated safe-mode controller not implemented");
        blocked(context,"POLICY_CONTROL_PLANE","central sensitive-feature policy gate not implemented");
        blocked(context,"RECOVERY_BOOTSTRAP","V1 bootstrap/recovery manifest not implemented");
    }

    private static void beta(Context c,String feature,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(c,"FEATURE_FAIL_CLOSED",
                "feature="+feature+";state=BETA;actuation=false;reason="+reason);
    }
    private static void blocked(Context c,String feature,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(c,"FEATURE_FAIL_CLOSED",
                "feature="+feature+";state=BLOCKED;actuation=false;reason="+reason);
    }
    private static void unsupported(Context c,String feature,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(c,"FEATURE_FAIL_CLOSED",
                "feature="+feature+";state=UNSUPPORTED;actuation=false;reason="+reason);
    }
    private static void reverify(Context c,String feature,String reason){
        VerificationEvidenceRuntime.recordPassiveEvent(c,"FEATURE_FAIL_CLOSED",
                "feature="+feature+";state=REVERIFY_REQUIRED;actuation=false;reason="+reason);
    }
}
