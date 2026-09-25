package com.dolphin.launcher.v1;

import android.content.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Read-only BYD interior-light capability inventory.
 * It never invokes turnOffInsideLight/setInsideLightDoorState or any unknown write.
 * Only the legacy-evidenced getInsideLightDoorState() getter is called.
 */
public final class InteriorLightCapabilityProbe {
    private static final String SETTING =
            "android.hardware.bydauto.setting.BYDAutoSettingDevice";

    private InteriorLightCapabilityProbe(){}

    public static void capture(Context context){
        try{
            Class<?> clazz=Class.forName(SETTING);
            int matching=0;
            boolean intLightCommand=false;
            boolean noArgLightCommand=false;
            boolean doorSetter=false;
            boolean doorGetter=false;
            boolean otherWholeLampGetter=false;

            for(Method m:clazz.getMethods()){
                String name=m.getName();
                String lower=name.toLowerCase();
                if(!(lower.contains("insidelight") || lower.contains("insightlight"))) continue;
                matching++;
                Class<?>[] params=m.getParameterTypes();
                boolean writeCandidate=!name.startsWith("get") && !name.startsWith("is");
                VerificationEvidenceRuntime.recordPassiveEvent(
                        context,"INTERIOR_LIGHT_CAPABILITY_METHOD",
                        "name="+name
                                +";params="+Arrays.toString(params)
                                +";return="+m.getReturnType().getName()
                                +";static="+Modifier.isStatic(m.getModifiers())
                                +";write_candidate="+writeCandidate
                                +";invoked=false"
                                +";vehicle_write=false");

                if("turnOffInsideLight".equals(name)){
                    if(params.length==0) noArgLightCommand=true;
                    if(params.length==1 && params[0]==int.class) intLightCommand=true;
                }
                if("setInsideLightDoorState".equals(name)
                        && params.length==1 && params[0]==int.class) doorSetter=true;
                if("getInsideLightDoorState".equals(name) && params.length==0) doorGetter=true;
                if(name.startsWith("get") && params.length==0
                        && !"getInsideLightDoorState".equals(name)) otherWholeLampGetter=true;
            }

            Integer doorRaw=null;
            String readbackError="none";
            if(doorGetter){
                try{
                    Context bydContext=BydPermissionContext.wrap(context.getApplicationContext());
                    Object instance=clazz.getMethod("getInstance",Context.class)
                            .invoke(null,bydContext);
                    if(instance==null) throw new IllegalStateException("getInstance null");
                    Object raw=clazz.getMethod("getInsideLightDoorState").invoke(instance);
                    if(raw instanceof Number) doorRaw=((Number)raw).intValue();
                }catch(Throwable t){
                    Throwable e=t.getCause()!=null?t.getCause():t;
                    readbackError=e.getClass().getSimpleName()+":"+String.valueOf(e.getMessage());
                }
            }

            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"INTERIOR_LIGHT_READBACK",
                    "door_state_raw="+String.valueOf(doorRaw)
                            +";known_mapping=1_ON_2_OFF"
                            +";getter=getInsideLightDoorState"
                            +";getter_present="+doorGetter
                            +";error="+readbackError
                            +";whole_lamp_physical_state=unavailable"
                            +";vehicle_write=false");

            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"INTERIOR_LIGHT_CAPABILITY",
                    "class_present=true"
                            +";matching_methods="+matching
                            +";turnOffInsideLight_int="+intLightCommand
                            +";turnOffInsideLight_noarg="+noArgLightCommand
                            +";door_setter="+doorSetter
                            +";door_getter="+doorGetter
                            +";other_whole_lamp_getter_candidate="+otherWholeLampGetter
                            +";whole_lamp_readback_proven=false"
                            +";actuation=false"
                            +";vehicle_write=false");
        }catch(Throwable t){
            Throwable e=t.getCause()!=null?t.getCause():t;
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"INTERIOR_LIGHT_CAPABILITY_FAILED",
                    "class="+SETTING
                            +";error="+e.getClass().getSimpleName()
                            +":"+String.valueOf(e.getMessage())
                            +";actuation=false;vehicle_write=false");
        }
    }
}
