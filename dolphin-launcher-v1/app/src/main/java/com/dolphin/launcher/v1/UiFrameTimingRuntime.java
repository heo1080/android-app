package com.dolphin.launcher.v1;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Display;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lightweight real-device HMI frame interval sampler. */
public final class UiFrameTimingRuntime {
    private static final String PREFS="v1_ui_frame_timing";
    private static final String KEY_LATEST="latest_json";
    private static final String KEY_LAST_START_MS="last_start_ms";
    private static final int TARGET_INTERVALS=120;
    private static final long MIN_INTERVAL_MS=60_000L;
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);

    private UiFrameTimingRuntime(){}

    public static void start(Context context,String label){
        if(context==null)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){
            new Handler(Looper.getMainLooper()).post(() -> start(context,label));
            return;
        }

        Context app=context.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        if(now-prefs.getLong(KEY_LAST_START_MS,0L)<MIN_INTERVAL_MS)return;
        if(!RUNNING.compareAndSet(false,true))return;
        prefs.edit().putLong(KEY_LAST_START_MS,now).apply();

        float refreshRate=0f;
        try{
            if(context instanceof Activity){
                Display display=((Activity)context).getWindowManager().getDefaultDisplay();
                if(display!=null)refreshRate=display.getRefreshRate();
            }
        }catch(Throwable ignored){}

        Choreographer choreographer=Choreographer.getInstance();
        new Sampler(app,label==null?"screen":label,refreshRate,choreographer).begin();
    }

    public static File latestFile(Context context){
        return new File(context.getApplicationContext().getFilesDir(),
                "ui_frame_timing_latest.json");
    }

    public static JSONObject latest(Context context){
        if(context==null)return null;
        String raw=context.getApplicationContext()
                .getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getString(KEY_LATEST,null);
        if(raw==null || raw.trim().isEmpty())return null;
        try{return new JSONObject(raw);}catch(Exception ignored){return null;}
    }

    private static final class Sampler implements Choreographer.FrameCallback {
        private final Context app;
        private final String label;
        private final float refreshRate;
        private final Choreographer choreographer;
        private final List<Double> intervals=new ArrayList<>();
        private long lastFrameNs;

        Sampler(Context app,String label,float refreshRate,Choreographer choreographer){
            this.app=app;
            this.label=label;
            this.refreshRate=refreshRate;
            this.choreographer=choreographer;
        }

        void begin(){
            choreographer.postFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos){
            try{
                if(lastFrameNs>0L){
                    double ms=(frameTimeNanos-lastFrameNs)/1_000_000.0;
                    if(ms>0.0 && ms<1000.0)intervals.add(ms);
                }
                lastFrameNs=frameTimeNanos;
                if(intervals.size()<TARGET_INTERVALS){
                    choreographer.postFrameCallback(this);
                }else{
                    finish();
                }
            }catch(Throwable t){
                RUNNING.set(false);
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app,"UI_FRAME_TIMING_FAILED",
                        "error="+t.getClass().getSimpleName());
            }
        }

        private void finish() throws Exception {
            if(intervals.isEmpty()){
                RUNNING.set(false);
                return;
            }
            List<Double> sorted=new ArrayList<>(intervals);
            Collections.sort(sorted);
            double sum=0.0;
            int over32=0;
            int over50=0;
            for(double ms:intervals){
                sum+=ms;
                if(ms>32.0)over32++;
                if(ms>50.0)over50++;
            }
            double avg=sum/intervals.size();
            double p50=percentile(sorted,0.50);
            double p95=percentile(sorted,0.95);
            double max=sorted.get(sorted.size()-1);

            JSONObject out=new JSONObject();
            out.put("schema_version",1);
            out.put("label",label);
            out.put("captured_at_ms",System.currentTimeMillis());
            out.put("frame_intervals",intervals.size());
            out.put("refresh_rate_hz",refreshRate);
            out.put("avg_ms",round2(avg));
            out.put("p50_ms",round2(p50));
            out.put("p95_ms",round2(p95));
            out.put("max_ms",round2(max));
            out.put("over_32ms",over32);
            out.put("over_50ms",over50);
            out.put("thresholds_are_diagnostic",true);

            app.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                    .edit().putString(KEY_LATEST,out.toString()).apply();
            try(FileOutputStream fileOut=new FileOutputStream(latestFile(app))){
                fileOut.write(out.toString(2).getBytes(StandardCharsets.UTF_8));
            }

            VerificationEvidenceRuntime.recordPassiveEvent(
                    app,"UI_FRAME_TIMING",
                    "label="+label
                            +";frames="+intervals.size()
                            +";refresh_hz="+String.format(Locale.US,"%.2f",refreshRate)
                            +";avg_ms="+String.format(Locale.US,"%.2f",avg)
                            +";p50_ms="+String.format(Locale.US,"%.2f",p50)
                            +";p95_ms="+String.format(Locale.US,"%.2f",p95)
                            +";max_ms="+String.format(Locale.US,"%.2f",max)
                            +";over_32ms="+over32
                            +";over_50ms="+over50
                            +";thresholds=diagnostic-only");
            RUNNING.set(false);
        }

        private double percentile(List<Double> sorted,double q){
            if(sorted.isEmpty())return 0.0;
            int index=(int)Math.ceil(q*sorted.size())-1;
            index=Math.max(0,Math.min(sorted.size()-1,index));
            return sorted.get(index);
        }

        private double round2(double value){
            return Math.round(value*100.0)/100.0;
        }
    }
}
