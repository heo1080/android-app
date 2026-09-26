package com.dolphin.launcher.v1;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Privacy-safe visual QA companion for Golden Screenshots.
 * It records geometry and layout symptoms only; no TextView contents are stored.
 */
public final class UiLayoutAuditRuntime {
    private static final int MAX_EXAMPLES=24;

    private UiLayoutAuditRuntime(){}

    public static File auditDir(Context context){
        File dir=new File(context.getFilesDir(),"ui_layout_audits");
        if(!dir.exists() && !dir.mkdirs()){
            throw new IllegalStateException("Cannot create UI layout audit directory");
        }
        return dir;
    }

    public static JSONObject latestAudit(Context context){
        File[] files=auditDir(context).listFiles((d,name)->name.endsWith(".json"));
        if(files==null || files.length==0)return null;
        Arrays.sort(files,(x,y)->Long.compare(y.lastModified(),x.lastModified()));
        for(File file:files){
            JSONObject parsed=readJson(file);
            if(parsed!=null)return parsed;
        }
        return null;
    }

    public static File capture(Activity activity,String label,long capturedAt) throws Exception {
        if(activity==null)throw new IllegalArgumentException("activity required");
        View root=activity.getWindow().getDecorView();
        DisplayMetrics dm=activity.getResources().getDisplayMetrics();
        Configuration cfg=activity.getResources().getConfiguration();
        String session=VerificationEvidenceRuntime.currentSessionId(activity);
        String safeLabel=(label==null?"screen":label)
                .replaceAll("[^A-Za-z0-9_-]","_");

        JSONObject out=new JSONObject();
        out.put("schema_version",1);
        out.put("session_id",session);
        out.put("label",safeLabel);
        out.put("captured_at_ms",capturedAt);
        out.put("width_px",root.getWidth());
        out.put("height_px",root.getHeight());
        out.put("density",dm.density);
        out.put("density_dpi",dm.densityDpi);
        out.put("scaled_density",dm.scaledDensity);
        out.put("font_scale",cfg.fontScale);
        out.put("width_dp",root.getWidth()/Math.max(0.01f,dm.density));
        out.put("height_dp",root.getHeight()/Math.max(0.01f,dm.density));
        out.put("orientation",cfg.orientation);
        out.put("night_mode",cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK);

        Rect visibleFrame=new Rect();
        root.getWindowVisibleDisplayFrame(visibleFrame);
        JSONObject frame=new JSONObject();
        frame.put("left",visibleFrame.left);
        frame.put("top",visibleFrame.top);
        frame.put("right",visibleFrame.right);
        frame.put("bottom",visibleFrame.bottom);
        frame.put("width",visibleFrame.width());
        frame.put("height",visibleFrame.height());
        out.put("window_visible_frame",frame);

        WindowInsets insets=root.getRootWindowInsets();
        if(insets!=null){
            JSONObject insetJson=new JSONObject();
            insetJson.put("left",insets.getSystemWindowInsetLeft());
            insetJson.put("top",insets.getSystemWindowInsetTop());
            insetJson.put("right",insets.getSystemWindowInsetRight());
            insetJson.put("bottom",insets.getSystemWindowInsetBottom());
            out.put("system_window_insets",insetJson);
        }

        int visibleViews=0;
        int clickableViews=0;
        int touchViolations=0;
        int ellipsizedTexts=0;
        int partialClips=0;
        JSONArray touchExamples=new JSONArray();
        JSONArray ellipsisExamples=new JSONArray();
        JSONArray clipExamples=new JSONArray();

        Deque<View> queue=new ArrayDeque<>();
        queue.add(root);
        while(!queue.isEmpty()){
            View view=queue.removeFirst();
            if(view.getVisibility()!=View.VISIBLE || view.getAlpha()<=0f)continue;

            Rect visible=new Rect();
            boolean hasVisible=view.getGlobalVisibleRect(visible);
            if(hasVisible && visible.width()>0 && visible.height()>0){
                visibleViews++;
                if(view.isClickable()){
                    clickableViews++;
                    float widthDp=visible.width()/Math.max(0.01f,dm.density);
                    float heightDp=visible.height()/Math.max(0.01f,dm.density);
                    if(widthDp<48f || heightDp<48f){
                        touchViolations++;
                        addExample(touchExamples,view,
                                "width_dp",Math.round(widthDp*10f)/10f,
                                "height_dp",Math.round(heightDp*10f)/10f);
                    }
                }

                if(visible.width()+1<view.getWidth() || visible.height()+1<view.getHeight()){
                    partialClips++;
                    addExample(clipExamples,view,
                            "visible_width_px",visible.width(),
                            "visible_height_px",visible.height());
                }

                if(view instanceof TextView){
                    TextView tv=(TextView)view;
                    if(tv.getLayout()!=null){
                        boolean ellipsized=false;
                        for(int line=0;line<tv.getLayout().getLineCount();line++){
                            if(tv.getLayout().getEllipsisCount(line)>0){
                                ellipsized=true;
                                break;
                            }
                        }
                        if(ellipsized){
                            ellipsizedTexts++;
                            addExample(ellipsisExamples,view,
                                    "line_count",tv.getLayout().getLineCount(),
                                    "text_length",tv.getText()==null?0:tv.getText().length());
                        }
                    }
                }
            }

            if(view instanceof ViewGroup){
                ViewGroup group=(ViewGroup)view;
                for(int i=0;i<group.getChildCount();i++){
                    View child=group.getChildAt(i);
                    if(child!=null)queue.addLast(child);
                }
            }
        }

        JSONObject summary=new JSONObject();
        summary.put("visible_views",visibleViews);
        summary.put("clickable_views",clickableViews);
        summary.put("touch_target_violations",touchViolations);
        summary.put("ellipsized_texts",ellipsizedTexts);
        summary.put("partial_clips",partialClips);
        summary.put("touch_target_min_dp",48);
        out.put("summary",summary);
        out.put("touch_target_examples",touchExamples);
        out.put("ellipsis_examples",ellipsisExamples);
        out.put("partial_clip_examples",clipExamples);
        out.put("text_content_logged",false);

        JSONObject comparison=findComparablePrevious(
                activity,safeLabel,root.getWidth(),root.getHeight(),
                dm.densityDpi,cfg.fontScale,cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK,
                touchViolations,ellipsizedTexts,partialClips);
        out.put("comparison",comparison);

        File file=new File(
                auditDir(activity),
                "DolphinV1_UI_Audit_"+session+"_"+safeLabel+"_"+capturedAt+".json");
        try(FileOutputStream fos=new FileOutputStream(file)){
            fos.write(out.toString(2).getBytes(StandardCharsets.UTF_8));
        }

        VerificationEvidenceRuntime.recordPassiveEvent(
                activity,"UI_LAYOUT_AUDIT",
                "label="+safeLabel
                        +";density_dpi="+dm.densityDpi
                        +";font_scale="+cfg.fontScale
                        +";visible_views="+visibleViews
                        +";clickable_views="+clickableViews
                        +";touch_target_violations="+touchViolations
                        +";ellipsized_texts="+ellipsizedTexts
                        +";partial_clips="+partialClips
                        +";text_content_logged=false");

        if(comparison.optBoolean("baseline_available",false)){
            boolean regression=comparison.optBoolean("regression",false);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    activity,
                    regression ? "UI_LAYOUT_REGRESSION" : "UI_LAYOUT_BASELINE_COMPARE",
                    "label="+safeLabel
                            +";delta_touch="+comparison.optInt("delta_touch",0)
                            +";delta_ellipsis="+comparison.optInt("delta_ellipsis",0)
                            +";delta_clips="+comparison.optInt("delta_clips",0)
                            +";regression="+regression);
        }
        trimOld(activity,12);
        return file;
    }

    private static JSONObject findComparablePrevious(
            Context context,String label,int widthPx,int heightPx,int densityDpi,
            float fontScale,int nightMode,int touch,int ellipsis,int clips){
        JSONObject result=new JSONObject();
        try{
            result.put("baseline_available",false);
            File[] files=auditDir(context).listFiles((d,name)->name.endsWith(".json"));
            if(files==null || files.length==0)return result;
            Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
            for(File file:files){
                JSONObject previous=readJson(file);
                if(previous==null)continue;
                if(!label.equals(previous.optString("label","")))continue;
                if(previous.optInt("width_px",-1)!=widthPx)continue;
                if(previous.optInt("height_px",-1)!=heightPx)continue;
                if(previous.optInt("density_dpi",-1)!=densityDpi)continue;
                if(Math.abs((float)previous.optDouble("font_scale",-1)-fontScale)>0.001f)continue;
                if(previous.optInt("night_mode",-1)!=nightMode)continue;

                JSONObject summary=previous.optJSONObject("summary");
                if(summary==null)continue;
                int prevTouch=summary.optInt("touch_target_violations",0);
                int prevEllipsis=summary.optInt("ellipsized_texts",0);
                int prevClips=summary.optInt("partial_clips",0);
                int dTouch=touch-prevTouch;
                int dEllipsis=ellipsis-prevEllipsis;
                int dClips=clips-prevClips;

                result.put("baseline_available",true);
                result.put("baseline_file",file.getName());
                result.put("same_display_profile",true);
                result.put("delta_touch",dTouch);
                result.put("delta_ellipsis",dEllipsis);
                result.put("delta_clips",dClips);
                result.put("regression",dTouch>0 || dEllipsis>0 || dClips>0);
                return result;
            }
        }catch(Exception ignored){}
        return result;
    }

    private static JSONObject readJson(File file){
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                new FileInputStream(file),StandardCharsets.UTF_8))){
            StringBuilder b=new StringBuilder();
            String line;
            while((line=reader.readLine())!=null)b.append(line);
            return new JSONObject(b.toString());
        }catch(Exception ignored){
            return null;
        }
    }

    private static void addExample(
            JSONArray array, View view, String k1, Object v1, String k2, Object v2){
        if(array.length()>=MAX_EXAMPLES)return;
        try{
            JSONObject row=new JSONObject();
            row.put("class",view.getClass().getSimpleName());
            row.put("id",safeId(view));
            row.put(k1,v1);
            row.put(k2,v2);
            array.put(row);
        }catch(Exception ignored){}
    }

    private static String safeId(View view){
        int id=view.getId();
        if(id==View.NO_ID)return "no-id";
        try{
            return view.getResources().getResourceEntryName(id);
        }catch(Throwable ignored){
            return "id-"+id;
        }
    }

    private static void trimOld(Context context,int keep){
        File[] files=auditDir(context).listFiles((d,name)->name.endsWith(".json"));
        if(files==null || files.length<=keep)return;
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(int i=keep;i<files.length;i++){
            try{files[i].delete();}catch(Throwable ignored){}
        }
    }
}
