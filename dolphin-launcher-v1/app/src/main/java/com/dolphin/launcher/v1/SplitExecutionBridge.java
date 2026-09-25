package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
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
            // Resolve both launch components before starting either app. This prevents
            // a missing launcher activity on the second package from leaving a partial
            // one-app launch and avoids relying on am's package-only activity choice.
            ComponentName left=resolveLaunchComponent(context,leftPackage);
            ComponentName right=resolveLaunchComponent(context,rightPackage);
            if(left==null||right==null) return new Result(false,
                    "preflight-component-missing;left="+(left!=null)+";right="+(right!=null));
            String leftComponent=shellComponent(left);
            String rightComponent=shellComponent(right);
            String leftCmd="am start --windowingMode 3 -n "+leftComponent;
            String rightCmd="am start --windowingMode 4 -n "+rightComponent;
            AdbShellResponse l=adb.shell(leftCmd);
            if (l.getExitCode()!=0) return new Result(false,"left-exit="+l.getExitCode());
            AdbShellResponse r=adb.shell(rightCmd);
            boolean ok=r.getExitCode()==0;
            return new Result(ok,"left-exit="+l.getExitCode()+";right-exit="+r.getExitCode());
        } catch(Throwable t) {
            return new Result(false,"exception="+t.getClass().getSimpleName());
        }
    }
    private static ComponentName resolveLaunchComponent(Context context,String packageName) {
        shellArg(packageName);
        Intent intent=context.getPackageManager().getLaunchIntentForPackage(packageName);
        return intent==null?null:intent.getComponent();
    }
    private static String shellComponent(ComponentName component) {
        String value=component.flattenToShortString();
        if(value==null || !value.matches("[A-Za-z0-9._$]+/[A-Za-z0-9._$]+"))
            throw new IllegalArgumentException("invalid-component");
        return value;
    }
    private static String shellArg(String value) {
        if (value==null || !value.matches("[A-Za-z0-9._]+")) throw new IllegalArgumentException("invalid-package");
        return value;
    }
}
