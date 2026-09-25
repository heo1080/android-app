package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.Arrays;

/**
 * Passive inventory for driver-only/per-app routing research.
 * It never changes AudioPolicy, UID affinity, routing, focus or BYD DSP.
 */
public final class DriverAudioCapabilityProbe {
    private static final String MODIFY_AUDIO_ROUTING="android.permission.MODIFY_AUDIO_ROUTING";
    private static final String MODIFY_AUDIO_SETTINGS="android.permission.MODIFY_AUDIO_SETTINGS";

    private DriverAudioCapabilityProbe(){}

    public static void capture(Context context){
        AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(am==null) return;
        try{
            boolean routingGranted=context.checkSelfPermission(MODIFY_AUDIO_ROUTING)
                    == PackageManager.PERMISSION_GRANTED;
            boolean settingsGranted=context.checkSelfPermission(MODIFY_AUDIO_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
            VerificationEvidenceRuntime.recordPassiveEvent(context,"DRIVER_AUDIO_PERMISSION",
                    "modify_audio_routing="+routingGranted
                            +";modify_audio_settings="+settingsGranted
                            +";uid_affinity_write=false;audio_policy_write=false");

            VerificationEvidenceRuntime.recordPassiveEvent(context,"DRIVER_AUDIO_DEFAULTS",
                    "mode="+am.getMode()
                            +";output_sample_rate="+safe(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE))
                            +";frames_per_buffer="+safe(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))
                            +";routing_write=false");

            AudioDeviceInfo[] devices=am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            int candidateCount=0;
            for(AudioDeviceInfo d:devices){
                String typeLabel=typeLabel(d.getType());
                boolean candidate=isPhysicalDriverRouteCandidate(d.getType());
                if(candidate) candidateCount++;
                VerificationEvidenceRuntime.recordPassiveEvent(context,"AUDIO_OUTPUT_DEVICE",
                        "id="+d.getId()
                                +";type="+d.getType()
                                +";type_label="+typeLabel
                                +";product="+safe(d.getProductName())
                                +";address="+safe(d.getAddress())
                                +";sink="+d.isSink()
                                +";channel_counts="+Arrays.toString(d.getChannelCounts())
                                +";channel_masks="+Arrays.toString(d.getChannelMasks())
                                +";channel_index_masks="+Arrays.toString(d.getChannelIndexMasks())
                                +";sample_rates="+Arrays.toString(d.getSampleRates())
                                +";encodings="+Arrays.toString(d.getEncodings())
                                +";driver_route_candidate="+candidate
                                +";physical_driver_only=unproven"
                                +";routing_write=false");
                if(candidate){
                    VerificationEvidenceRuntime.recordPassiveEvent(context,"DRIVER_AUDIO_DEVICE_CANDIDATE",
                            "id="+d.getId()
                                    +";type="+d.getType()
                                    +";type_label="+typeLabel
                                    +";product="+safe(d.getProductName())
                                    +";address="+safe(d.getAddress())
                                    +";candidate_only=true"
                                    +";physical_driver_only=unproven"
                                    +";routing_write=false");
                }
            }
            VerificationEvidenceRuntime.recordPassiveEvent(context,"DRIVER_AUDIO_CAPABILITY",
                    "output_devices="+devices.length
                            +";driver_route_candidates="+candidateCount
                            +";modify_audio_routing="+routingGranted
                            +";uid_affinity_write=false"
                            +";audio_policy_write=false"
                            +";byd_dsp_write=false"
                            +";physical_driver_only=unproven");
        }catch(Throwable t){
            VerificationEvidenceRuntime.recordPassiveEvent(context,"DRIVER_AUDIO_CAPABILITY_FAILED",
                    "error="+t.getClass().getSimpleName()+";routing_write=false");
        }
    }

    private static boolean isPhysicalDriverRouteCandidate(int type){
        return type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                || type==AudioDeviceInfo.TYPE_BUS
                || type==AudioDeviceInfo.TYPE_AUX_LINE;
    }

    private static String typeLabel(int type){
        switch(type){
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "BUILTIN_SPEAKER";
            case AudioDeviceInfo.TYPE_BUS: return "BUS";
            case AudioDeviceInfo.TYPE_AUX_LINE: return "AUX_LINE";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "BLUETOOTH_A2DP";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB_DEVICE";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB_HEADSET";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            default: return "TYPE_"+type;
        }
    }

    private static String safe(CharSequence s){
        if(s==null)return "";
        return s.toString().replace(";","_").replace("\n"," ").replace("\r"," ");
    }
}
