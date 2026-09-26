package com.dolphin.launcher.v1;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Read-only discovery of BYD/Android audio, exterior AVAS, and trigger candidate capabilities.
 * No instance setter or vehicle DSP write is invoked.
 */
public final class AudioCapabilityProbe {
    private static final String[] CLASS_CANDIDATES={
            "android.hardware.bydauto.audio.BYDAutoAudioDevice",
            "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice",
            "android.media.audiofx.Equalizer"
    };
    private static final String[] TOKENS={"equal","bass","treble","balance","fader","sound","dsp","audio"};
    private static final String[] EXTERIOR_TOKENS={
            "avas","exterior","external","engine","simulator","prompt","buffer"
    };
    private static final String[] BODYWORK_CLASS_CANDIDATES={
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener"
    };
    private static final String[] TRIGGER_TOKENS={
            "autosystem","door","lock","secure","listener"
    };
    private static final String[] GENERIC_BRIDGE_METHODS={
            "getInt","setInt","getBuffer","setBuffer"
    };

    private AudioCapabilityProbe(){}

    public static void capture(Context context){
        String activeTestId=VerificationEvidenceRuntime.activeTestId(context);
        String activeCorrelationId=VerificationEvidenceRuntime.activeTestCorrelation(context);
        if("AUD-AVAS-001".equals(activeTestId)
                && activeCorrelationId!=null && !activeCorrelationId.trim().isEmpty()){
            capture(context, activeTestId, activeCorrelationId);
        }else{
            capture(context, null, null);
        }
    }

    public static void capture(Context context, String testId, String correlationId){
        int classes=0, methods=0, fields=0;
        int exteriorMethods=0, exteriorFields=0;
        for(String name:CLASS_CANDIDATES){
            try{
                Class<?> cls=Class.forName(name);
                classes++;
                for(Method m:cls.getMethods()){
                    if(matches(m.getName())){
                        methods++;
                        record(context, testId, correlationId,"AUDIO_CAPABILITY",
                                "class="+name+";method="+m.getName()+";params="+m.getParameterTypes().length+
                                ";return="+m.getReturnType().getSimpleName()+";invoked=false;mode=read-only");
                    }
                    if(matchesExterior(m.getName())){
                        exteriorMethods++;
                        record(context, testId, correlationId,"EXTERNAL_AVAS_CAPABILITY_METHOD",
                                "class="+name+";method="+m.getName()+";params="+m.getParameterTypes().length+
                                ";return="+m.getReturnType().getSimpleName()+
                                ";invoked=false;candidate_only=true;mode=read-only");
                    }
                }
                for(Field f:cls.getFields()){
                    if(matches(f.getName())){
                        fields++;
                        record(context, testId, correlationId,"AUDIO_CAPABILITY_CONSTANT",
                                "class="+name+";field="+f.getName()+";type="+f.getType().getSimpleName()+
                                ";read=false;invoked=false;mode=read-only");
                    }
                    if(matchesExterior(f.getName())){
                        exteriorFields++;
                        record(context, testId, correlationId,"EXTERNAL_AVAS_CAPABILITY_CONSTANT",
                                "class="+name+";field="+f.getName()+";type="+f.getType().getSimpleName()+
                                ";read=false;invoked=false;candidate_only=true;mode=read-only");
                    }
                }
            }catch(Throwable t){
                record(context, testId, correlationId,"AUDIO_CAPABILITY_CLASS",
                        "class="+name+";available=false;error="+t.getClass().getSimpleName()+";mode=read-only");
            }
        }
        int triggerClasses=0, triggerMethods=0, triggerFields=0;
        for(String name:BODYWORK_CLASS_CANDIDATES){
            try{
                Class<?> cls=Class.forName(name);
                triggerClasses++;
                for(Method m:cls.getMethods()){
                    if(!matchesTrigger(m.getName())) continue;
                    triggerMethods++;
                    record(context, testId, correlationId,"AVAS_TRIGGER_CAPABILITY_METHOD",
                            "class="+name+";method="+m.getName()+";params="+m.getParameterTypes().length+
                                    ";return="+m.getReturnType().getSimpleName()+
                                    ";invoked=false;candidate_only=true;trigger_semantic=unproven");
                }
                for(Field field:cls.getFields()){
                    if(!matchesTrigger(field.getName())) continue;
                    triggerFields++;
                    record(context, testId, correlationId,"AVAS_TRIGGER_CAPABILITY_CONSTANT",
                            "class="+name+";field="+field.getName()+";type="+field.getType().getSimpleName()+
                                    ";read=false;candidate_only=true;trigger_semantic=unproven");
                }
            }catch(Throwable t){
                record(context, testId, correlationId,"AVAS_TRIGGER_CAPABILITY_CLASS",
                        "class="+name+";available=false;error="+t.getClass().getSimpleName()+
                                ";mode=read-only");
            }
        }

        boolean genericServiceAvailable=false;
        int genericBridgeMethods=0;
        try{
            Object autoService=context.getSystemService("auto");
            genericServiceAvailable=autoService!=null;
            if(autoService!=null){
                Class<?> autoClass=autoService.getClass();
                for(Method m:autoClass.getMethods()){
                    if(!matchesGenericBridge(m.getName())) continue;
                    genericBridgeMethods++;
                    record(context, testId, correlationId,"EXTERNAL_AVAS_GENERIC_SERVICE_METHOD",
                            "service=auto;class="+autoClass.getName()+";method="+m.getName()+
                                    ";params="+m.getParameterTypes().length+
                                    ";return="+m.getReturnType().getSimpleName()+
                                    ";invoked=false;candidate_only=true;feature_mapping=unproven");
                }
            }
        }catch(Throwable t){
            record(context, testId, correlationId,"EXTERNAL_AVAS_GENERIC_SERVICE_ERROR",
                    "service=auto;error="+t.getClass().getSimpleName()+";invoked=false");
        }
        record(context, testId, correlationId,"EXTERNAL_AVAS_GENERIC_SERVICE",
                "service=auto;available="+genericServiceAvailable+
                        ";generic_bridge_methods="+genericBridgeMethods+
                        ";feature_mapping=unproven;invoked=false;vehicle_write=false");

        record(context, testId, correlationId,"AUDIO_CAPABILITY_SCAN",
                "classes="+classes+";methods="+methods+";fields="+fields+";vehicle_write=false");
        record(context, testId, correlationId,"EXTERNAL_AVAS_CAPABILITY_SCAN",
                "classes="+classes+";methods="+exteriorMethods+";fields="+exteriorFields+
                        ";trigger_classes="+triggerClasses+";trigger_methods="+triggerMethods+
                        ";trigger_fields="+triggerFields+
                        ";generic_service_available="+genericServiceAvailable+
                        ";generic_bridge_methods="+genericBridgeMethods+
                        ";custom_audio_path=unproven;builtin_tone_path=unproven"+
                        ";lock_trigger_path=unproven;vehicle_write=false;actuation=false");
    }

    private static void record(Context context, String testId, String correlationId,
                               String event, String note){
        if(testId != null && !testId.trim().isEmpty()
                && correlationId != null && !correlationId.trim().isEmpty()){
            VerificationEvidenceRuntime.recordTestEvent(
                    context, testId, correlationId, event, note);
        }else{
            VerificationEvidenceRuntime.recordPassiveEvent(context, event, note);
        }
    }

    private static boolean matchesGenericBridge(String value){
        for(String method:GENERIC_BRIDGE_METHODS) if(method.equals(value)) return true;
        return false;
    }

    private static boolean matchesTrigger(String value){
        String s=value.toLowerCase(java.util.Locale.ROOT);
        for(String token:TRIGGER_TOKENS) if(s.contains(token)) return true;
        return false;
    }

    private static boolean matchesExterior(String value){
        String s=value.toLowerCase(java.util.Locale.ROOT);
        for(String token:EXTERIOR_TOKENS) if(s.contains(token)) return true;
        return false;
    }

    private static boolean matches(String value){
        String s=value.toLowerCase(java.util.Locale.ROOT);
        for(String token:TOKENS) if(s.contains(token)) return true;
        return false;
    }
}
