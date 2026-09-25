package com.dolphin.launcher.v1;

import android.content.Context;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class InstrumentCapabilityProbe {
    private static final String CLASS_NAME="android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";
    private static final Set<String> ALLOW=new HashSet<>(Arrays.asList(
            "sendAutoNaviStatus","sendSimpleGuidanceInfo","sendNextPathName",
            "sendCameraGuidanceInfo","sendSafeGuidanceInfo","sendMusicState","sendMusicInfo"));

    private InstrumentCapabilityProbe(){}

    public static void capture(Context context){
        try{
            Class<?> clazz=Class.forName(CLASS_NAME);
            int matched=0;
            for(Method m:clazz.getMethods()){
                if(!Modifier.isPublic(m.getModifiers()) || !ALLOW.contains(m.getName())) continue;
                VerificationEvidenceRuntime.recordPassiveEvent(
                        context,"INSTRUMENT_CAPABILITY",
                        "class="+CLASS_NAME+";method="+m.getName()
                                +";params="+m.getParameterCount()
                                +";returnType="+m.getReturnType().getName()
                                +";invoked=false;mode=read-only");
                matched++;
            }
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"INSTRUMENT_CAPABILITY_SCAN",
                    "classPresent=true;matched="+matched+";invoked=false;mode=read-only");
        }catch(Throwable t){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"INSTRUMENT_CAPABILITY_SCAN",
                    "classPresent=false;type="+t.getClass().getSimpleName()
                            +";invoked=false;mode=read-only");
        }
    }
}
