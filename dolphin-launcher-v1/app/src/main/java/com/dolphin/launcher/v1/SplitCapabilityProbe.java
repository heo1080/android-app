package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.io.File;
import dadb.AdbKeyPair;
import dadb.Dadb;

/** Read-only probe. It authenticates localhost ADB and runs only 'echo'. */
public final class SplitCapabilityProbe {
    public static final class Result {
        public final boolean shellBinary, appDebuggable, adbKeyPresent, localhostAdbAuthorized;
        public final String adbDetail;
        Result(boolean shellBinary, boolean appDebuggable, boolean adbKeyPresent,
               boolean localhostAdbAuthorized, String adbDetail) {
            this.shellBinary=shellBinary; this.appDebuggable=appDebuggable;
            this.adbKeyPresent=adbKeyPresent; this.localhostAdbAuthorized=localhostAdbAuthorized;
            this.adbDetail=adbDetail;
        }
        public boolean authorizedPathReady() { return localhostAdbAuthorized; }
        public String evidence() {
            return "shell_binary="+shellBinary+";app_debuggable="+appDebuggable+
                    ";adb_key_present="+adbKeyPresent+
                    ";localhost_adb_authorized="+localhostAdbAuthorized+
                    ";authorized_path_ready="+authorizedPathReady()+
                    ";adb_detail="+adbDetail;
        }
    }
    private SplitCapabilityProbe() {}

    public static Result inspect(Context context) {
        boolean shell = new File("/system/bin/sh").canExecute()
                || new File("/system/bin/toybox").canExecute();
        boolean debug=false;
        try {
            ApplicationInfo ai=context.getPackageManager().getApplicationInfo(context.getPackageName(),0);
            debug=(ai.flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        } catch (PackageManager.NameNotFoundException ignored) {}

        File keyDir=new File(context.getFilesDir(),"adb");
        File privateKey=new File(keyDir,"adbkey");
        File publicKey=new File(keyDir,"adbkey.pub");
        boolean authorized=false;
        String detail="not-probed";
        try {
            if (!privateKey.isFile() || !publicKey.isFile()) {
                AdbKeyPair.generate(privateKey, publicKey);
            }
            AdbKeyPair pair=AdbKeyPair.read(privateKey, publicKey);
            try (Dadb adb=Dadb.create("localhost",5555,pair,1500,2500,false)) {
                dadb.AdbShellResponse response=adb.shell("echo DOLPHIN_SPLIT_PROBE");
                authorized=response.getExitCode()==0 &&
                        response.getOutput().contains("DOLPHIN_SPLIT_PROBE");
                detail=authorized ? "echo-ok" : "echo-nonzero";
            }
        } catch (Throwable t) {
            detail=t.getClass().getSimpleName();
        }
        return new Result(shell,debug,privateKey.isFile() && publicKey.isFile(),authorized,detail);
    }
}
