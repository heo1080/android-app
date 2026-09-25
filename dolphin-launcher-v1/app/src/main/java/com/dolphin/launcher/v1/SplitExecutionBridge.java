package com.dolphin.launcher.v1;

import android.content.Context;
import dadb.AdbShellResponse;
import dadb.Dadb;

/** Executes a split pair only through an already-authorized V1-owned localhost ADB path. */
public final class SplitExecutionBridge {
    public static final class Result {
        public final boolean success;
        public final String detail;
        Result(boolean success,String detail){this.success=success;this.detail=detail;}
    }
    private SplitExecutionBridge(){}

    public static Result launch(Context context,String leftPackage,String rightPackage) {
        SplitCapabilityProbe.Result probe=SplitCapabilityProbe.inspect(context);
        if (!probe.authorizedPathReady()) return new Result(false,"blocked;"+probe.evidence());
        try (Dadb adb=SplitCapabilityProbe.connectAuthorized(context)) {
            String leftCmd="am start --windowingMode 3 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "+shellArg(leftPackage);
            String rightCmd="am start --windowingMode 4 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "+shellArg(rightPackage);
            AdbShellResponse l=adb.shell(leftCmd);
            if (l.getExitCode()!=0) return new Result(false,"left-exit="+l.getExitCode());
            AdbShellResponse r=adb.shell(rightCmd);
            boolean ok=r.getExitCode()==0;
            return new Result(ok,"left-exit="+l.getExitCode()+";right-exit="+r.getExitCode());
        } catch(Throwable t) {
            return new Result(false,"exception="+t.getClass().getSimpleName());
        }
    }
    private static String shellArg(String value) {
        if (value==null || !value.matches("[A-Za-z0-9._]+")) throw new IllegalArgumentException("invalid-package");
        return value;
    }
}
