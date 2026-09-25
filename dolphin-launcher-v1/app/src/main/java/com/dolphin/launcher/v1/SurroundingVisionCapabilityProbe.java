package com.dolphin.launcher.v1;

import android.content.Context;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class SurroundingVisionCapabilityProbe {
    private static final String[] CLASSES={
            "android.hardware.bydauto.radar.BYDAutoRadarDevice",
            "android.hardware.bydauto.adas.BYDAutoADASDevice"
    };
    private static final Set<String> ALLOW=new HashSet<>(Arrays.asList(
            "getRadarObstacleDistance","getBSDState","getLaneOffsetState","getTJAState"));

    private SurroundingVisionCapabilityProbe(){}

    public static void capture(Context context){
        for(String name:CLASSES){
            try{
                Class<?> clazz=Class.forName(name);
                int matched=0;
                for(Method m:clazz.getMethods()){
                    if(!Modifier.isPublic(m.getModifiers()) || !ALLOW.contains(m.getName())) continue;
                    VerificationEvidenceRuntime.recordPassiveEvent(context,"VISION_CAPABILITY",
                            "class="+name+";method="+m.getName()+";params="+m.getParameterCount()
                                    +";returnType="+m.getReturnType().getName()
                                    +";invoked=false;mode=read-only");
                    matched++;
                }
                VerificationEvidenceRuntime.recordPassiveEvent(context,"VISION_CAPABILITY_SCAN",
                        "class="+name+";classPresent=true;matched="+matched+";invoked=false;mode=read-only");
            }catch(Throwable t){
                VerificationEvidenceRuntime.recordPassiveEvent(context,"VISION_CAPABILITY_SCAN",
                        "class="+name+";classPresent=false;type="+t.getClass().getSimpleName()
                                +";invoked=false;mode=read-only");
            }
        }
    }
}
