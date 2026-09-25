package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.io.File;

/**
 * Read-only capability probe for the BYD-compatible split path.
 * It never starts a shell, opens a socket, or changes windowing state.
 */
public final class SplitCapabilityProbe {
    public static final class Result {
        public final boolean shellBinary;
        public final boolean appDebuggable;
        public final boolean adbKeyPresent;
        Result(boolean shellBinary, boolean appDebuggable, boolean adbKeyPresent) {
            this.shellBinary=shellBinary; this.appDebuggable=appDebuggable; this.adbKeyPresent=adbKeyPresent;
        }
        public boolean authorizedPathReady() {
            // Presence alone is not authorization. Keep execution gated until an
            // independently implemented authenticated bridge proves command access.
            return false;
        }
        public String evidence() {
            return "shell_binary="+shellBinary+";app_debuggable="+appDebuggable+
                    ";adb_key_present="+adbKeyPresent+";authorized_path_ready=false";
        }
    }

    private SplitCapabilityProbe() {}

    public static Result inspect(Context context) {
        boolean shell = new File("/system/bin/sh").canExecute()
                || new File("/system/bin/toybox").canExecute();
        boolean debug = false;
        try {
            ApplicationInfo ai=context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(),0);
            debug=(ai.flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        } catch (PackageManager.NameNotFoundException ignored) {}
        File home=context.getFilesDir().getParentFile();
        boolean adbKey = home != null && new File(home, ".android/adbkey").isFile();
        return new Result(shell,debug,adbKey);
    }
}
