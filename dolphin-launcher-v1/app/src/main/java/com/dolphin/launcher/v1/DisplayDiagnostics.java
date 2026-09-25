package com.dolphin.launcher.v1;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/** Read-only display diagnostics. Never changes density, font scale, rotation or OEM properties. */
public final class DisplayDiagnostics {
    private DisplayDiagnostics(){}

    public static void capture(Activity activity){
        try{
            DisplayMetrics dm=activity.getResources().getDisplayMetrics();
            Configuration cfg=activity.getResources().getConfiguration();
            Rect bounds=null;
            if(android.os.Build.VERSION.SDK_INT>=30){
                bounds=activity.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
            }
            String forcedDensity=readSecure(activity,"display_density_forced");
            String accelerometerRotation=readSystem(activity,"accelerometer_rotation");
            String userRotation=readSystem(activity,"user_rotation");
            String fontScaleSetting=readSystem(activity,"font_scale");
            int requestedOrientation=activity.getRequestedOrientation();
            int displayRotation=activity.getDisplay()==null?-1:activity.getDisplay().getRotation();
            int windowingMode=-1;
            try { windowingMode=activity.getResources().getConfiguration().windowConfiguration.getWindowingMode(); }
            catch(Throwable ignored) { }
            String forcedSize=readGlobal(activity,"display_size_forced");
            String detail="densityDpi="+dm.densityDpi
                    +";density="+dm.density
                    +";scaledDensity="+dm.scaledDensity
                    +";px="+dm.widthPixels+"x"+dm.heightPixels
                    +";dp="+cfg.screenWidthDp+"x"+cfg.screenHeightDp
                    +";smallestWidthDp="+cfg.smallestScreenWidthDp
                    +";fontScale="+cfg.fontScale
                    +";orientation="+cfg.orientation
                    +";windowBounds="+(bounds==null?"unavailable":bounds.width()+"x"+bounds.height())
                    +";forcedDensity="+safe(forcedDensity)
                    +";forcedSize="+safe(forcedSize)
                    +";accelerometerRotation="+safe(accelerometerRotation)
                    +";userRotation="+safe(userRotation)
                    +";fontScaleSetting="+safe(fontScaleSetting)
                    +";requestedOrientation="+requestedOrientation
                    +";displayRotation="+displayRotation
                    +";windowingMode="+windowingMode
                    +";mode=read-only";
            VerificationEvidenceRuntime.recordPassiveEvent(activity,"DISPLAY_PROFILE",detail);
        }catch(Throwable t){
            VerificationEvidenceRuntime.recordPassiveEvent(
                    activity,"DISPLAY_PROFILE_ERROR","type="+t.getClass().getSimpleName()+";mode=read-only");
        }
    }

    private static String readSystem(Context c,String key){
        try{return Settings.System.getString(c.getContentResolver(),key);}
        catch(Throwable t){return null;}
    }
    private static String readSecure(Context c,String key){
        try{return Settings.Secure.getString(c.getContentResolver(),key);}
        catch(Throwable t){return null;}
    }
    private static String readGlobal(Context c,String key){
        try{return Settings.Global.getString(c.getContentResolver(),key);}
        catch(Throwable t){return null;}
    }
    private static String safe(String v){return v==null||v.isEmpty()?"unavailable":v;}
}
