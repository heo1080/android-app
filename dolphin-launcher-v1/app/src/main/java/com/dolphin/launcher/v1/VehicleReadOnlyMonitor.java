package com.dolphin.launcher.v1;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    private static final String SPEED="android.hardware.bydauto.speed.BYDAutoSpeedDevice";
    private static final long PERIOD_MS=500L;

    public interface Listener {
        void onGear(String value);
        void onSpeedRaw(Integer raw);
        void onDriveMode(String value);
        void onRegen(String value);
        void onEpb(boolean held);
        void onAvhRaw(Integer raw);
        void onAvhSwitchRaw(Integer raw);
        void onBsdRaw(Integer raw);
        void onTurnRaw(Integer leftRaw, Integer rightRaw);
        void onSnowRaw(Integer raw);
        void onIccCandidateRaw(Integer raw);
        void onLaneOffsetRaw(Integer laneOffsetRaw, Integer lksModeRaw,
                             Integer ldswTypeRaw, Integer tjaRaw);
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
        new Thread(() -> {
            captureGearboxConstants();
            captureStateConstants(ADAS,"ADAS_RUNTIME_CONSTANT",new String[]{"AVH","AUTOHOLD","BSD","TJA","ICC","ACC","LKS","LDW","LDSW","LANE","OFFSET","LEAD","FRONT","VEHICLE","OBJECT"});
            captureStateConstants(SETTING,"SETTING_RUNTIME_CONSTANT",new String[]{"AVH","AUTOHOLD"});
            captureStateConstants(ENERGY,"ENERGY_RUNTIME_CONSTANT",new String[]{"SNOW","ROAD","SURFACE"});
            captureStateConstants(RADAR,"RADAR_RUNTIME_CONSTANT",new String[]{"FRONT","AREA","OBJECT","DISTANCE","VEHICLE"});
            loop();
        },"V1-VehicleRead").start();
    }

    public synchronized void stop(){running=false;}

    private void loop(){
        while(running){
            try{sample();}catch(Throwable t){once("sample",t);}
            try{Thread.sleep(PERIOD_MS);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
    }

    private void sample(){
        // Gear and EPB numeric meanings are not yet proven against Korean Dolphin
        // runtime constants. Capture transitions only; guessed speech is fail-closed.
        Integer gear=read(GEARBOX,"getCurrentGear");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("gear_raw",gear);
        if(changed("gear.current",gear)) post(()->listener.onRaw("gear.candidate.unmapped",gear));

        Integer speed=read(SPEED,"getCurrentSpeed");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("speed_raw",speed);
        if(changed("speed.current",speed)) post(()->listener.onSpeedRaw(speed));

        Integer epb=read(GEARBOX,"getEPBState");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("epb_raw",epb);
        if(changed("gear.epb",epb)) post(()->listener.onRaw("epb.candidate.unmapped",epb));

        // Real-car evidence from 2026-09-18/19 repeatedly correlates
        // getOperationMode 1=ECO and 2=SPORT on the Korean Dolphin. NORMAL is still
        // deliberately unmapped. Baseline is silent; only subsequent transitions speak.
        Integer drive=read(ENERGY,"getOperationMode");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("operation_raw",drive);
        boolean driveChanged=changed("energy.operationMode",drive);
        if(driveChanged && changedNormalized("voice.energy.operationMode",drive)){
            final String normalizedDrive=Integer.valueOf(1).equals(drive)?"ECO"
                    :Integer.valueOf(2).equals(drive)?"SPORT":null;
            if(normalizedDrive!=null){
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"DRIVE_MODE_EVIDENCE_MAP",
                        "operation_raw="+drive+";normalized="+normalizedDrive
                                +";source=real-car-20260918-20260919;normal_unmapped=true");
                post(()->listener.onDriveMode(normalizedDrive));
            }else{
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"DRIVE_MODE_UNMAPPED",
                        "operation_raw="+drive+";voice=suppressed;reason=normal-correlation-pending");
                post(()->listener.onRaw("drive.candidate.unmapped",drive));
            }
        }

        // Real-car evidence repeatedly correlates getEnergyFeedback raw 2 with HIGH.
        // STANDARD remains unmapped so the historical STANDARD cross-label regression
        // cannot recur. Baseline remains silent.
        Integer regen=read(SETTING,"getEnergyFeedback");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("energy_feedback_raw",regen);
        boolean regenChanged=changed("setting.energyFeedback",regen);
        if(regenChanged && changedNormalized("voice.setting.energyFeedback",regen)){
            if(Integer.valueOf(2).equals(regen)){
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"REGEN_MODE_EVIDENCE_MAP",
                        "energy_feedback_raw="+regen
                                +";normalized=HIGH;source=real-car-20260918-20260919"
                                +";standard_unmapped=true");
                post(()->listener.onRegen("HIGH"));
            }else{
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"REGEN_MODE_UNMAPPED",
                        "energy_feedback_raw="+regen
                                +";voice=suppressed;reason=standard-correlation-pending");
                post(()->listener.onRaw("regen.candidate.unmapped",regen));
            }
        }

        // 2026-09-17 real-car evidence captured 1->2 snow=true and 2->1 snow=false;
        // 2026-09-19 independently captured Snow OFF raw=1. Baseline is silent.
        Integer roadSurface=read(ENERGY,"getRoadSurfaceMode");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("road_surface_raw",roadSurface);
        boolean roadSurfaceChanged=changed("energy.roadSurfaceMode",roadSurface);
        if(roadSurfaceChanged && changedNormalized("voice.energy.roadSurfaceMode",roadSurface))
            post(()->listener.onSnowRaw(roadSurface));
        Integer tja=read(ADAS,"getTJAState");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("tja_raw",tja);
        boolean tjaChanged=changed("adas.tja",tja);
        if(tjaChanged) post(()->listener.onIccCandidateRaw(tja));

        // Public DiLink SDK evidence exposes getLaneOffsetState(), getLKSMode(),
        // getLDSWType() and onLaneOffsetStateChanged(). Their Korean-Dolphin
        // raw semantics are not assumed here: capture values only so real-car
        // left/right observations can be correlated before any voice mapping.
        Integer laneOffset=read(ADAS,"getLaneOffsetState");
        Integer lksMode=read(ADAS,"getLKSMode");
        Integer ldswType=read(ADAS,"getLDSWType");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("lane_offset_raw",laneOffset);
        VerificationEvidenceRuntime.updateLiveCorrelationValue("lks_mode_raw",lksMode);
        VerificationEvidenceRuntime.updateLiveCorrelationValue("ldsw_type_raw",ldswType);
        boolean laneContextChanged=changed("adas.laneOffset",laneOffset);
        laneContextChanged=changed("adas.lksMode",lksMode) || laneContextChanged;
        laneContextChanged=changed("adas.ldswType",ldswType) || laneContextChanged;
        if(laneContextChanged || tjaChanged) {
            post(()->listener.onLaneOffsetRaw(laneOffset,lksMode,ldswType,tja));
        }

        // Areas 7/8 are front-centre parking-radar candidates in the legacy
        // DiLink 3 evidence. V1 records only raw values; it does not call an
        // obstacle moving away a "leading vehicle departure" yet.
        Integer radarLeft=readIntArg(RADAR,"getRadarObstacleDistance",7);
        Integer radarRight=readIntArg(RADAR,"getRadarObstacleDistance",8);
        VerificationEvidenceRuntime.updateLiveCorrelationValue("radar_area7_raw",radarLeft);
        VerificationEvidenceRuntime.updateLiveCorrelationValue("radar_area8_raw",radarRight);
        boolean radarChanged=changed("radar.frontLeftMid",radarLeft);
        radarChanged=changed("radar.frontRightMid",radarRight) || radarChanged;
        if(radarChanged) post(()->listener.onFrontRadarRaw(radarLeft,radarRight));

        // Resolve BYD wheel-area constants before any TPMS API call. Never pass
        // the -1 fallback into a vehicle API when a runtime constant is absent.
        int areaFl=staticInt(TYRE,"TYRE_COMMAND_AREA_LEFT_FRONT",-1);
        int areaFr=staticInt(TYRE,"TYRE_COMMAND_AREA_RIGHT_FRONT",-1);
        int areaRl=staticInt(TYRE,"TYRE_COMMAND_AREA_LEFT_REAR",-1);
        int areaRr=staticInt(TYRE,"TYRE_COMMAND_AREA_RIGHT_REAR",-1);
        boolean tpmsAreasReady=areaFl>=0 && areaFr>=0 && areaRl>=0 && areaRr>=0;
        if(!tpmsAreasReady) {
            String detail="TPMS_AREA_UNRESOLVED fl="+areaFl+";fr="+areaFr+";rl="+areaRl+";rr="+areaRr;
            if(errors.add(detail)) {
                Log.w(TAG,detail);
                VerificationEvidenceRuntime.recordPassiveEvent(app,"TPMS_AREA_UNRESOLVED",detail);
            }
        }

        Integer tyreFl=tpmsAreasReady?readIntArg(TYRE,"getTyrePressureValue",areaFl):null;
        Integer tyreFr=tpmsAreasReady?readIntArg(TYRE,"getTyrePressureValue",areaFr):null;
        Integer tyreRl=tpmsAreasReady?readIntArg(TYRE,"getTyrePressureValue",areaRl):null;
        Integer tyreRr=tpmsAreasReady?readIntArg(TYRE,"getTyrePressureValue",areaRr):null;
        boolean tyreChanged=changed("tyre.fl.raw",tyreFl);
        tyreChanged=changed("tyre.fr.raw",tyreFr) || tyreChanged;
        tyreChanged=changed("tyre.rl.raw",tyreRl) || tyreChanged;
        tyreChanged=changed("tyre.rr.raw",tyreRr) || tyreChanged;
        Integer tyreFlState=tpmsAreasReady?readIntArg(TYRE,"getTyrePressureState",areaFl):null;
        Integer tyreFrState=tpmsAreasReady?readIntArg(TYRE,"getTyrePressureState",areaFr):null;
        Integer tyreRlState=tpmsAreasReady?readIntArg(TYRE,"getTyrePressureState",areaRl):null;
        Integer tyreRrState=tpmsAreasReady?readIntArg(TYRE,"getTyrePressureState",areaRr):null;
        Integer tyreFlSignal=tpmsAreasReady?readIntArg(TYRE,"getTyreSignalState",areaFl):null;
        Integer tyreFrSignal=tpmsAreasReady?readIntArg(TYRE,"getTyreSignalState",areaFr):null;
        Integer tyreRlSignal=tpmsAreasReady?readIntArg(TYRE,"getTyreSignalState",areaRl):null;
        Integer tyreRrSignal=tpmsAreasReady?readIntArg(TYRE,"getTyreSignalState",areaRr):null;
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
        VerificationEvidenceRuntime.updateLiveCorrelationValue("avh_raw",avh);
        boolean avhChanged=changed("adas.avh",avh);
        Integer avhSwitch=read(SETTING,"getAVHEnable");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("avh_enable_raw",avhSwitch);
        boolean avhSwitchChanged=changed("setting.avhEnable",avhSwitch);

        String activeTestId=VerificationEvidenceRuntime.activeTestId(app);
        boolean autoHoldTest="AUD-AVH-001".equals(activeTestId) || "AUD-AVH-002".equals(activeTestId);
        boolean liveAutoHold=autoHoldTest
                && VerificationEvidenceRuntime.activeTestWithin(app,10L*60L*1000L);
        if(liveAutoHold || avhChanged || avhSwitchChanged){
            Integer brakeDepth=read(SPEED,"getBrakeDeepness");
            Integer accelDepth=read(SPEED,"getAccelerateDeepness");
            Integer brakePedal=read(GEARBOX,"getBrakePedalState");
            VerificationEvidenceRuntime.updateLiveCorrelationValue("speed_raw",speed);
            VerificationEvidenceRuntime.updateLiveCorrelationValue("brake_pedal_raw",brakePedal);
            VerificationEvidenceRuntime.updateLiveCorrelationValue("brake_depth_raw",brakeDepth);
            VerificationEvidenceRuntime.updateLiveCorrelationValue("accel_depth_raw",accelDepth);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,liveAutoHold?"AUTOHOLD_LIVE_SAMPLE":"AUTOHOLD_CORRELATION_SNAPSHOT",
                    "active_test="+String.valueOf(activeTestId)
                            +";avh_raw="+avh
                            +";avh_enable_raw="+avhSwitch
                            +";gear_raw="+gear
                            +";epb_raw="+epb
                            +";speed_raw="+speed
                            +";brake_pedal_raw="+brakePedal
                            +";brake_depth_raw="+brakeDepth
                            +";accel_depth_raw="+accelDepth
                            +";voice=suppressed;purpose=direct-hold-correlation");
        }
        if(avhChanged) post(()->listener.onAvhRaw(avh));
        if(avhSwitchChanged) post(()->listener.onAvhSwitchRaw(avhSwitch));
        Integer bsd=read(ADAS,"getBSDState");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("bsd_raw",bsd);
        if(changed("adas.bsd",bsd)) post(()->listener.onBsdRaw(bsd));

        // Capture turn-signal candidates separately so BSD side correlation can be
        // established from real-car evidence before any left/right warning is spoken.
        Integer turnLeft=read(GEARBOX,"getLeftTurnLightState");
        Integer turnRight=read(GEARBOX,"getRightTurnLightState");
        VerificationEvidenceRuntime.updateLiveCorrelationValue("turn_left_raw",turnLeft);
        VerificationEvidenceRuntime.updateLiveCorrelationValue("turn_right_raw",turnRight);
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

    private void captureStateConstants(String className,String event,String[] filters){
        try {
            Class<?> clazz=Class.forName(className);
            int count=0;
            for(Field field:clazz.getFields()){
                int mods=field.getModifiers();
                if(!Modifier.isStatic(mods) || field.getType()!=int.class) continue;
                String name=field.getName();
                String upper=name.toUpperCase();
                boolean matched=false;
                for(String filter:filters) if(upper.contains(filter)){matched=true;break;}
                if(!matched) continue;
                int value=field.getInt(null);
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,event,"name="+name+";value="+value+";source=reflection;voice=suppressed");
                count++;
            }
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,event+"_SCAN","count="+count+";class="+className+";voice=suppressed");
        } catch(Throwable t){
            once("constant.scan:"+className,t);
        }
    }

    private void captureGearboxConstants(){
        try {
            Class<?> clazz=Class.forName(GEARBOX);
            int count=0;
            for(Field field:clazz.getFields()){
                int mods=field.getModifiers();
                if(!Modifier.isStatic(mods) || field.getType()!=int.class) continue;
                String name=field.getName();
                String upper=name.toUpperCase();
                if(!(upper.contains("GEAR") || upper.contains("EPB") || upper.contains("PARK"))) continue;
                int value=field.getInt(null);
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"GEARBOX_RUNTIME_CONSTANT",
                        "name="+name+";value="+value+";source=reflection;voice=suppressed");
                count++;
            }
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"GEARBOX_RUNTIME_CONSTANT_SCAN",
                    "count="+count+";class="+GEARBOX+";voice=suppressed");
        } catch(Throwable t){
            once("gearbox.constant.scan",t);
        }
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

}
