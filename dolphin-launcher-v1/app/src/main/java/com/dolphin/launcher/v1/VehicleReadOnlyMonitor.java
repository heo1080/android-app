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
    private static final String RADAR="android.hardware.bydauto.radar.BYDAutoRadarDevice";
    private static final String TYRE="android.hardware.bydauto.tyre.BYDAutoTyreDevice";
    private static final long PERIOD_MS=500L;

    public interface Listener {
        void onGear(String value);
        void onDriveMode(String value);
        void onRegen(String value);
        void onEpb(boolean held);
        void onAvhRaw(Integer raw);
        void onAvhSwitchRaw(Integer raw);
        void onBsdRaw(Integer raw);
        void onTurnRaw(Integer leftRaw, Integer rightRaw);
        void onSnowRaw(Integer raw);
        void onIccCandidateRaw(Integer raw);
        void onFrontRadarRaw(Integer leftMid, Integer rightMid);
        void onTpmsRaw(Integer fl, Integer fr, Integer rl, Integer rr,
                       Integer flState, Integer frState, Integer rlState, Integer rrState,
                       Integer flSignal, Integer frSignal, Integer rlSignal, Integer rrSignal);
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

        // P0 regression guard: previous numeric mappings produced missing NORMAL and
        // STANDARD->ECO mis-announcements on the real car. Until raw transitions are
        // re-correlated against the OEM UI, collect evidence only; never speak guesses.
        Integer drive=read(ENERGY,"getOperationMode");
        if(changed("energy.operationMode",drive)) post(()->listener.onRaw("drive.candidate.unmapped",drive));

        Integer regen=read(SETTING,"getEnergyFeedback");
        if(changed("setting.energyFeedback",regen)) post(()->listener.onRaw("regen.candidate.unmapped",regen));

        // Snow and ICC/TJA remain candidates until V1 real-car correlation confirms
        // both directions. Record raw transitions without speaking guessed semantics.
        Integer roadSurface=read(ENERGY,"getRoadSurfaceMode");
        if(changed("energy.roadSurfaceMode",roadSurface)) post(()->listener.onSnowRaw(roadSurface));
        Integer tja=read(ADAS,"getTJAState");
        if(changed("adas.tja",tja)) post(()->listener.onIccCandidateRaw(tja));

        // Areas 7/8 are front-centre parking-radar candidates in the legacy
        // DiLink 3 evidence. V1 records only raw values; it does not call an
        // obstacle moving away a "leading vehicle departure" yet.
        Integer radarLeft=readIntArg(RADAR,"getRadarObstacleDistance",7);
        Integer radarRight=readIntArg(RADAR,"getRadarObstacleDistance",8);
        boolean radarChanged=changed("radar.frontLeftMid",radarLeft);
        radarChanged=changed("radar.frontRightMid",radarRight) || radarChanged;
        if(radarChanged) post(()->listener.onFrontRadarRaw(radarLeft,radarRight));

        // Public DiLink 3 OpenAPI exposes per-wheel TPMS pressure values.
        // Preserve the raw integers until the Korean Dolphin's pressure unit is
        // confirmed by real-car evidence; no guessed psi/bar conversion here.
        Integer tyreFl=readIntArg(TYRE,"getTyrePressureValue",staticInt(TYRE,"TYRE_COMMAND_AREA_LEFT_FRONT",-1));
        Integer tyreFr=readIntArg(TYRE,"getTyrePressureValue",staticInt(TYRE,"TYRE_COMMAND_AREA_RIGHT_FRONT",-1));
        Integer tyreRl=readIntArg(TYRE,"getTyrePressureValue",staticInt(TYRE,"TYRE_COMMAND_AREA_LEFT_REAR",-1));
        Integer tyreRr=readIntArg(TYRE,"getTyrePressureValue",staticInt(TYRE,"TYRE_COMMAND_AREA_RIGHT_REAR",-1));
        boolean tyreChanged=changed("tyre.fl.raw",tyreFl);
        tyreChanged=changed("tyre.fr.raw",tyreFr) || tyreChanged;
        tyreChanged=changed("tyre.rl.raw",tyreRl) || tyreChanged;
        tyreChanged=changed("tyre.rr.raw",tyreRr) || tyreChanged;
        Integer tyreFlState=readIntArg(TYRE,"getTyrePressureState",staticInt(TYRE,"TYRE_COMMAND_AREA_LEFT_FRONT",-1));
        Integer tyreFrState=readIntArg(TYRE,"getTyrePressureState",staticInt(TYRE,"TYRE_COMMAND_AREA_RIGHT_FRONT",-1));
        Integer tyreRlState=readIntArg(TYRE,"getTyrePressureState",staticInt(TYRE,"TYRE_COMMAND_AREA_LEFT_REAR",-1));
        Integer tyreRrState=readIntArg(TYRE,"getTyrePressureState",staticInt(TYRE,"TYRE_COMMAND_AREA_RIGHT_REAR",-1));
        Integer tyreFlSignal=readIntArg(TYRE,"getTyreSignalState",staticInt(TYRE,"TYRE_COMMAND_AREA_LEFT_FRONT",-1));
        Integer tyreFrSignal=readIntArg(TYRE,"getTyreSignalState",staticInt(TYRE,"TYRE_COMMAND_AREA_RIGHT_FRONT",-1));
        Integer tyreRlSignal=readIntArg(TYRE,"getTyreSignalState",staticInt(TYRE,"TYRE_COMMAND_AREA_LEFT_REAR",-1));
        Integer tyreRrSignal=readIntArg(TYRE,"getTyreSignalState",staticInt(TYRE,"TYRE_COMMAND_AREA_RIGHT_REAR",-1));
        tyreChanged=changed("tyre.fl.pressureState",tyreFlState)||tyreChanged;
        tyreChanged=changed("tyre.fr.pressureState",tyreFrState)||tyreChanged;
        tyreChanged=changed("tyre.rl.pressureState",tyreRlState)||tyreChanged;
        tyreChanged=changed("tyre.rr.pressureState",tyreRrState)||tyreChanged;
        tyreChanged=changed("tyre.fl.signalState",tyreFlSignal)||tyreChanged;
        tyreChanged=changed("tyre.fr.signalState",tyreFrSignal)||tyreChanged;
        tyreChanged=changed("tyre.rl.signalState",tyreRlSignal)||tyreChanged;
        tyreChanged=changed("tyre.rr.signalState",tyreRrSignal)||tyreChanged;
        if(tyreChanged) post(()->listener.onTpmsRaw(
                tyreFl,tyreFr,tyreRl,tyreRr,
                tyreFlState,tyreFrState,tyreRlState,tyreRrState,
                tyreFlSignal,tyreFrSignal,tyreRlSignal,tyreRrSignal));

        // Semantics still require physical-control correlation. Expose transitions to
        // the listener as raw evidence only; do not map them to spoken ON/OFF/side yet.
        // Keep the AVH hold state and the user-facing AutoHold switch candidate
        // separate. The real car previously produced delayed/paired speech when these
        // concepts were conflated; collect both timelines before mapping semantics.
        Integer avh=read(ADAS,"getAVHState");
        if(changed("adas.avh",avh)) post(()->listener.onAvhRaw(avh));
        Integer avhSwitch=read(SETTING,"getAVHEnable");
        if(changed("setting.avhEnable",avhSwitch)) post(()->listener.onAvhSwitchRaw(avhSwitch));
        Integer bsd=read(ADAS,"getBSDState");
        if(changed("adas.bsd",bsd)) post(()->listener.onBsdRaw(bsd));

        // Capture turn-signal candidates separately so BSD side correlation can be
        // established from real-car evidence before any left/right warning is spoken.
        Integer turnLeft=read(GEARBOX,"getLeftTurnLightState");
        Integer turnRight=read(GEARBOX,"getRightTurnLightState");
        boolean turnChanged=changed("gear.turn.left",turnLeft);
        turnChanged=changed("gear.turn.right",turnRight)||turnChanged;
        if(turnChanged) post(()->listener.onTurnRaw(turnLeft,turnRight));
    }

    private boolean changed(String signal,Integer value){
        if(value==null)return false;
        Integer old=lastRaw.put(signal,value);
        if(old==null||!old.equals(value)){
            String d=signal+" raw="+value+" previous="+String.valueOf(old);
            Log.i(TAG,d);
            VerificationEvidenceRuntime.recordPassiveEvent(app,"VEHICLE_RAW",d);
            if(listener!=null)post(()->listener.onRaw(signal,value));
            return true;
        }
        return false;
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

    private int staticInt(String className,String fieldName,int fallback){
        try { return Class.forName(className).getField(fieldName).getInt(null); }
        catch(Throwable t){ once("field:"+className+"#"+fieldName,t); return fallback; }
    }

    private Integer readIntArg(String className,String methodName,int arg){
        String key=className+"#"+methodName+"(int)";
        try{
            Object d=devices.get(className);
            if(d==null){
                Class<?> clazz=Class.forName(className);
                Context bydContext=BydPermissionContext.wrap(app);
                d=clazz.getMethod("getInstance",Context.class).invoke(null,bydContext);
                if(d==null)throw new IllegalStateException("getInstance null");
                devices.put(className,d);
            }
            Method m=methods.get(key);
            if(m==null){m=d.getClass().getMethod(methodName,int.class);methods.put(key,m);}
            Object v=m.invoke(d,arg);
            return v instanceof Number?((Number)v).intValue():null;
        }catch(Throwable t){
            devices.remove(className);methods.remove(key);once(key,t);return null;
        }
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
}
