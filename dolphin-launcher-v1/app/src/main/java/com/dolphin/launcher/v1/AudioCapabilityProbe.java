package com.dolphin.launcher.v1;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Read-only discovery of BYD/Android audio EQ capabilities.
 * No instance setter or vehicle DSP write is invoked.
 */
public final class AudioCapabilityProbe {
    private static final String[] CLASS_CANDIDATES={
            "android.hardware.bydauto.audio.BYDAutoAudioDevice",
            "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice",
            "android.media.audiofx.Equalizer"
    };
    private static final String[] TOKENS={"equal","bass","treble","balance","fader","sound","dsp","audio"};

    private AudioCapabilityProbe(){}

    public static void capture(Context context){
        int classes=0, methods=0, fields=0;
        for(String name:CLASS_CANDIDATES){
            try{
                Class<?> cls=Class.forName(name);
                classes++;
                for(Method m:cls.getMethods()){
                    if(!matches(m.getName())) continue;
                    methods++;
                    VerificationEvidenceRuntime.recordPassiveEvent(context,"AUDIO_CAPABILITY",
                            "class="+name+";method="+m.getName()+";params="+m.getParameterTypes().length+
                            ";return="+m.getReturnType().getSimpleName()+";invoked=false;mode=read-only");
                }
                for(Field f:cls.getFields()){
                    if(!matches(f.getName())) continue;
                    fields++;
                    VerificationEvidenceRuntime.recordPassiveEvent(context,"AUDIO_CAPABILITY_CONSTANT",
                            "class="+name+";field="+f.getName()+";type="+f.getType().getSimpleName()+
                            ";read=false;invoked=false;mode=read-only");
                }
            }catch(Throwable t){
                VerificationEvidenceRuntime.recordPassiveEvent(context,"AUDIO_CAPABILITY_CLASS",
                        "class="+name+";available=false;error="+t.getClass().getSimpleName()+";mode=read-only");
            }
        }
        VerificationEvidenceRuntime.recordPassiveEvent(context,"AUDIO_CAPABILITY_SCAN",
                "classes="+classes+";methods="+methods+";fields="+fields+";vehicle_write=false");
    }

    private static boolean matches(String value){
        String s=value.toLowerCase(java.util.Locale.ROOT);
        for(String token:TOKENS) if(s.contains(token)) return true;
        return false;
    }
}
