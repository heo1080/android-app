package com.dolphin.launcher.v1;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

/**
 * Passive inventory for driver-only/per-app routing research.
 * It never changes AudioPolicy, UID affinity, routing, focus or BYD DSP.
 */
public final class DriverAudioCapabilityProbe {
    private DriverAudioCapabilityProbe(){}

    public static void capture(Context context){
        AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(am==null) return;
        try{
            AudioDeviceInfo[] devices=am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for(AudioDeviceInfo d:devices){
                VerificationEvidenceRuntime.recordPassiveEvent(context,"AUDIO_OUTPUT_DEVICE",
                        "id="+d.getId()+";type="+d.getType()+";product="+safe(d.getProductName())+
                        ";channels="+d.getChannelCounts().length+";sample_rates="+d.getSampleRates().length+
                        ";routing_write=false");
            }
            VerificationEvidenceRuntime.recordPassiveEvent(context,"DRIVER_AUDIO_CAPABILITY",
                    "output_devices="+devices.length+";uid_affinity_write=false;audio_policy_write=false;byd_dsp_write=false");
        }catch(Throwable t){
            VerificationEvidenceRuntime.recordPassiveEvent(context,"DRIVER_AUDIO_CAPABILITY_FAILED",
                    "error="+t.getClass().getSimpleName()+";routing_write=false");
        }
    }
    private static String safe(CharSequence s){
        if(s==null)return "";
        return s.toString().replace(";","_").replace("\n"," ").replace("\r"," ");
    }
}
