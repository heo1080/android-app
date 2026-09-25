package com.dolphin.launcher.v1;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

/** Creates/removes user-requested launcher shortcuts without silently faking success. */
public final class AppDrawerShortcutManager {
    private AppDrawerShortcutManager(){}

    public static boolean pin(Context context,String packageName,String label){
        if(Build.VERSION.SDK_INT<26) return false;
        ShortcutManager sm=context.getSystemService(ShortcutManager.class);
        if(sm==null||!sm.isRequestPinShortcutSupported()){
            VerificationEvidenceRuntime.recordPassiveEvent(context,"APP_SHORTCUT_BLOCKED","package="+packageName+";reason=pin-not-supported");
            return false;
        }
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(packageName);
        if(launch==null){
            VerificationEvidenceRuntime.recordPassiveEvent(context,"APP_SHORTCUT_BLOCKED","package="+packageName+";reason=no-launch-intent");
            return false;
        }
        Intent target=new Intent(context,LauncherActivity.class).setAction("com.dolphin.launcher.v1.OPEN_PACKAGE").putExtra("package",packageName);
        String id="app_"+Integer.toHexString(packageName.hashCode());
        ShortcutInfo info=new ShortcutInfo.Builder(context,id).setShortLabel(label).setLongLabel(label)
                .setIcon(Icon.createWithResource(context,android.R.drawable.sym_def_app_icon)).setIntent(target).build();
        Intent callback=new Intent(context,ShortcutPinReceiver.class).setAction("com.dolphin.launcher.v1.SHORTCUT_PIN_RESULT")
                .putExtra("shortcut_id",id).putExtra("package",packageName);
        PendingIntent pi=PendingIntent.getBroadcast(context,id.hashCode(),callback,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        boolean requested=sm.requestPinShortcut(info,pi.getIntentSender());
        VerificationEvidenceRuntime.recordPassiveEvent(context,"APP_SHORTCUT_PIN_REQUEST","package="+packageName+";id="+id+";requested="+requested);
        return requested;
    }

    public static boolean disable(Context context,String packageName){
        if(Build.VERSION.SDK_INT<25) return false;
        ShortcutManager sm=context.getSystemService(ShortcutManager.class);
        if(sm==null)return false;
        String id="app_"+Integer.toHexString(packageName.hashCode());
        try{ sm.disableShortcuts(java.util.Collections.singletonList(id),"Removed from Dolphin Launcher");
            VerificationEvidenceRuntime.recordPassiveEvent(context,"APP_SHORTCUT_DISABLED","package="+packageName+";id="+id); return true;
        }catch(Throwable t){ VerificationEvidenceRuntime.recordPassiveEvent(context,"APP_SHORTCUT_DISABLE_FAILED","package="+packageName+";error="+t.getClass().getSimpleName()); return false; }
    }
}
