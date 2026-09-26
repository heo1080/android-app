package com.dolphin.launcher.v1;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/** Captures only this app's rendered window for HMI golden-screen comparison. */
public final class UiGoldenScreenshotRuntime {
    private UiGoldenScreenshotRuntime(){}

    public static File screenshotDir(Context context){
        File dir=new File(context.getFilesDir(),"ui_golden_screenshots");
        if(!dir.exists() && !dir.mkdirs()){
            throw new IllegalStateException("Cannot create UI screenshot directory");
        }
        return dir;
    }

    public static File capture(Activity activity,String label) throws Exception {
        if(activity==null)throw new IllegalArgumentException("activity required");
        View root=activity.getWindow().getDecorView();
        int width=root.getWidth();
        int height=root.getHeight();
        if(width<=0 || height<=0){
            throw new IllegalStateException("UI root has no measured size");
        }

        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        root.draw(canvas);

        String session=VerificationEvidenceRuntime.currentSessionId(activity);
        String safeLabel=(label==null?"screen":label)
                .replaceAll("[^A-Za-z0-9_-]","_");
        File file=new File(
                screenshotDir(activity),
                "DolphinV1_UI_"+session+"_"+safeLabel+"_"+System.currentTimeMillis()+".png");
        try(FileOutputStream out=new FileOutputStream(file)){
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out)){
                throw new IllegalStateException("PNG compression failed");
            }
        } finally {
            bitmap.recycle();
        }

        VerificationEvidenceRuntime.recordPassiveEvent(
                activity,"UI_GOLDEN_SCREENSHOT",
                "label="+safeLabel
                        +";width="+width
                        +";height="+height
                        +";bytes="+file.length()
                        +";session="+session
                        +";app_window_only=true");
        trimOld(activity,12);
        return file;
    }

    private static void trimOld(Context context,int keep){
        File[] files=screenshotDir(context).listFiles((d,name)->name.endsWith(".png"));
        if(files==null || files.length<=keep)return;
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(int i=keep;i<files.length;i++){
            try{files[i].delete();}catch(Throwable ignored){}
        }
    }
}
