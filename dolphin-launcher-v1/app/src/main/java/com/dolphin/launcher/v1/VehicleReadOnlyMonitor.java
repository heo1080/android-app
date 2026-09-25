package com.dolphin.launcher.v1;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Read-only BYD telemetry bridge for V1. Only mappings already backed by the
 * project's real-car evidence are allowed to emit normalized states.
 * Unknown values are evidence-only and never spoken.
 */
public final class VehicleReadOnlyMonitor {
    private static final String TAG="V1_VEHICLE_READ";
    private static final String GEARBOX="android.hardware.bydauto.gearbox.BYDAutoGearboxDevice";
    private static final String ENERGY="android.hardware.bydauto.energy.BYDAutoEnergyDevice";
    private static final String SETTING="android.hardware.bydauto.setting.BYDAutoSettingDevice";
    private static final String ADAS="android.hardware.bydauto.adas.BYDAutoADASDevice";
    private static final long PERIOD_MS=500L;

    public interface Listener {
        void onGear(String value);
        void onDriveMode(String value);
        void onRegen(String value);
        void onEpb(boolean held);
        void onRaw(String signal, Integer raw);
    }

    private final Context app;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Object> devices=new HashMap<>();
    private final Map<String,Method> methods=new HashMap<>();
    private final Map<String,Integer> lastRaw=new HashMap<>();
    private final Set<String> errors=new HashSet<>();
    private volatile boolean running;

    public VehicleReadOnlyMonitor(Context context, Listener listener) {
        this.app=context.getApplicationContext();
        this.listener=listener;
    }

    public synchronized void start() {
        if(running)return;
        running=true;
        new Thread(this::loop,"V1-VehicleRead").start();
    }

    public synchronized void stop(){running=false;}

    private void loop(){
        while(running){
            try{sample();}catch(Throwable t){once("sample",t);}
            try{Thread.sleep(PERIOD_MS);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
    }

    private void sample(){
        Integer gear=read(GEARBOX,"getCurrentGear");
        changed("gear.current",gear);
        String g=decodeGear(gear);
        if(g!=null && changedNormalized("gear.normalized",gear)) post(()->listener.onGear(g));

        Integer epb=read(GEARBOX,"getEPBState");
        changed("gear.epb",epb);
        if(epb!=null && (epb==1||epb==3) && changedNormalized("epb.normalized",epb))
            post(()->listener.onEpb(epb==3));

        // Real-car live-fix evidence: Energy operation mode candidate 0=NORMAL,1=ECO,2=SPORT.
        Integer drive=read(ENERGY,"getOperationMode");
        changed("energy.operationMode",drive);
        String dm=decodeDrive(drive);
        if(dm!=null && changedNormalized("drive.normalized",drive)) post(()->listener.onDriveMode(dm));

        // Real-car live-fix evidence: Setting energy feedback candidate 2=STANDARD,3=HIGH.
        Integer regen=read(SETTING,"getEnergyFeedback");
        changed("setting.energyFeedback",regen);
        String rm=decodeRegen(regen);
        if(rm!=null && changedNormalized("regen.normalized",regen)) post(()->listener.onRegen(rm));

        // Semantics still require physical-control correlation; evidence only.
        changed("adas.avh",read(ADAS,"getAVHState"));
        changed("adas.bsd",read(ADAS,"getBSDState"));
    }

    private void changed(String signal,Integer value){
        if(value==null)return;
        Integer old=lastRaw.put(signal,value);
        if(old==null||!old.equals(value)){
            String d=signal+" raw="+value+" previous="+String.valueOf(old);
            Log.i(TAG,d);
            VerificationEvidenceRuntime.recordPassiveEvent(app,"VEHICLE_RAW",d);
            if(listener!=null)post(()->listener.onRaw(signal,value));
        }
    }

    private boolean changedNormalized(String key,Integer value){
        Integer old=lastRaw.put(key,value);
        if(old==null){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"VEHICLE_BASELINE",key+" raw="+value+";voice=suppressed");
            return false;
        }
        return !old.equals(value);
    }

    private Integer read(String className,String methodName){
        String key=className+"#"+methodName;
        try{
            Object d=devices.get(className);
            if(d==null){
                Class<?> c=Class.forName(className);
                Context bydContext = BydPermissionContext.wrap(app);
                d=c.getMethod("getInstance",Context.class).invoke(null,bydContext);
                if(d==null)throw new IllegalStateException("getInstance null");
                devices.put(className,d);
            }
            Method m=methods.get(key);
            if(m==null){m=d.getClass().getMethod(methodName);methods.put(key,m);}
            Object v=m.invoke(d);
            return v instanceof Number?((Number)v).intValue():null;
        }catch(Throwable t){
            devices.remove(className);methods.remove(key);once(key,t);return null;
        }
    }

    private void once(String key,Throwable t){
        Throwable e=t.getCause()!=null?t.getCause():t;
        String sig=key+":"+e.getClass().getSimpleName()+":"+String.valueOf(e.getMessage());
        if(errors.add(sig)){
            Log.w(TAG,sig);
            VerificationEvidenceRuntime.recordPassiveEvent(app,"VEHICLE_READ_ERROR",sig);
        }
    }

    private void post(Runnable r){if(listener!=null)main.post(r);}

    private static String decodeGear(Integer r){
        if(r==null)return null;
        switch(r){case 0:return "N";case 1:return "R";case 2:return "D";case 3:return "P";default:return null;}
    }
    private static String decodeDrive(Integer r){
        if(r==null)return null;
        switch(r){case 0:return "NORMAL";case 1:return "ECO";case 2:return "SPORT";default:return null;}
    }
    private static String decodeRegen(Integer r){
        if(r==null)return null;
        switch(r){case 2:return "STANDARD";case 3:return "HIGH";default:return null;}
    }
}
