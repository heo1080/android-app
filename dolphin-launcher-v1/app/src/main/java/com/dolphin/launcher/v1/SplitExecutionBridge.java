package com.dolphin.launcher.v1;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * Executes a split pair only through an already-authorized V1-owned localhost
 * ADB path. Shell exit code is not considered visual split success: Android 10
 * may accept a requested secondary windowing mode while falling back to
 * fullscreen when the display did not actually enter split mode.
 */
public final class SplitExecutionBridge {
    private static final long READBACK_SETTLE_MS=700L;
    private static final int CONTEXT_RADIUS=2200;

    public static final class Result {
        public final boolean success;
        public final String detail;
        Result(boolean success,String detail){this.success=success;this.detail=detail;}
    }

    private static final class Readback {
        final boolean leftSeen;
        final boolean rightSeen;
        final boolean leftPrimary;
        final boolean rightSecondary;
        final String leftContext;
        final String rightContext;

        Readback(boolean leftSeen,boolean rightSeen,boolean leftPrimary,boolean rightSecondary,
                 String leftContext,String rightContext){
            this.leftSeen=leftSeen;
            this.rightSeen=rightSeen;
            this.leftPrimary=leftPrimary;
            this.rightSecondary=rightSecondary;
            this.leftContext=leftContext;
            this.rightContext=rightContext;
        }

        boolean verified(){ return leftSeen && rightSeen && leftPrimary && rightSecondary; }

        String summary(){
            return "left_seen="+leftSeen
                    +";right_seen="+rightSeen
                    +";left_primary="+leftPrimary
                    +";right_secondary="+rightSecondary
                    +";readback_verified="+verified();
        }
    }

    private SplitExecutionBridge(){}

    public static Result inspectCurrentPair(Context context,String leftPackage,String rightPackage) {
        SplitCapabilityProbe.Result probe=SplitCapabilityProbe.inspect(context);
        if (!probe.authorizedPathReady()) {
            return new Result(false,"blocked;actuation=false;"+probe.evidence());
        }
        try (Dadb adb=SplitCapabilityProbe.connectAuthorized(context)) {
            AdbShellResponse activities=adb.shell("dumpsys activity activities");
            AdbShellResponse stacks=adb.shell("am stack list");
            String activityOutput=activities.getOutput()==null?"":activities.getOutput();
            String stackOutput=stacks.getOutput()==null?"":stacks.getOutput();
            Readback readback=readback(activityOutput+"\n"+stackOutput,leftPackage,rightPackage);
            String detail="dumpsys-exit="+activities.getExitCode()
                    +";stack-exit="+stacks.getExitCode()
                    +";"+readback.summary()
                    +";actuation=false";
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"SPLIT_READBACK_PASSIVE",
                    "left="+leftPackage+";right="+rightPackage+";"+detail);
            return new Result(readback.verified(),detail);
        } catch(Throwable t) {
            return new Result(false,"exception="+t.getClass().getSimpleName()
                    +";readback_verified=false;actuation=false");
        }
    }

    public static Result launch(Context context,String leftPackage,String rightPackage) {
        SplitCapabilityProbe.Result probe=SplitCapabilityProbe.inspect(context);
        if (!probe.authorizedPathReady()) return new Result(false,"blocked;"+probe.evidence());

        try (Dadb adb=SplitCapabilityProbe.connectAuthorized(context)) {
            ComponentName left=resolveLaunchComponent(context,leftPackage);
            ComponentName right=resolveLaunchComponent(context,rightPackage);
            if(left==null||right==null) return new Result(false,
                    "preflight-component-missing;left="+(left!=null)+";right="+(right!=null));

            String leftComponent=shellComponent(left);
            String rightComponent=shellComponent(right);
            String leftCmd="am start --windowingMode 3 -n "+leftComponent;
            String rightCmd="am start --windowingMode 4 -n "+rightComponent;

            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"SPLIT_ADB_COMMAND",
                    "side=left;windowing_mode=3;component="+leftComponent);
            AdbShellResponse l=adb.shell(leftCmd);
            if (l.getExitCode()!=0) {
                return new Result(false,"left-exit="+l.getExitCode()+";readback_verified=false");
            }

            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"SPLIT_ADB_COMMAND",
                    "side=right;windowing_mode=4;component="+rightComponent);
            AdbShellResponse r=adb.shell(rightCmd);
            if (r.getExitCode()!=0) {
                return new Result(false,
                        "left-exit="+l.getExitCode()+";right-exit="+r.getExitCode()
                                +";readback_verified=false");
            }

            try{
                Thread.sleep(READBACK_SETTLE_MS);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }

            AdbShellResponse activities=adb.shell("dumpsys activity activities");
            AdbShellResponse stacks=adb.shell("am stack list");
            String activityOutput=activities.getOutput()==null?"":activities.getOutput();
            String stackOutput=stacks.getOutput()==null?"":stacks.getOutput();
            String combined=activityOutput+"\n"+stackOutput;
            Readback readback=readback(combined,leftPackage,rightPackage);

            String detail="left-exit="+l.getExitCode()
                    +";right-exit="+r.getExitCode()
                    +";dumpsys-exit="+activities.getExitCode()
                    +";stack-exit="+stacks.getExitCode()
                    +";"+readback.summary();

            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"SPLIT_READBACK",
                    "left="+leftPackage+";right="+rightPackage+";"+detail);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"SPLIT_READBACK_LEFT_CONTEXT",
                    "package="+leftPackage+";context="+readback.leftContext);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,"SPLIT_READBACK_RIGHT_CONTEXT",
                    "package="+rightPackage+";context="+readback.rightContext);

            return new Result(readback.verified(),detail);
        } catch(Throwable t) {
            return new Result(false,"exception="+t.getClass().getSimpleName()+";readback_verified=false");
        }
    }

    private static Readback readback(String output,String leftPackage,String rightPackage){
        String leftContext=contextForPackage(output,leftPackage);
        String rightContext=contextForPackage(output,rightPackage);
        boolean leftSeen=!leftContext.isEmpty();
        boolean rightSeen=!rightContext.isEmpty();
        boolean leftPrimary=containsMode(leftContext,3,"split-screen-primary");
        boolean rightSecondary=containsMode(rightContext,4,"split-screen-secondary");
        return new Readback(leftSeen,rightSeen,leftPrimary,rightSecondary,leftContext,rightContext);
    }

    private static boolean containsMode(String text,int mode,String label){
        if(text==null || text.isEmpty()) return false;
        return text.contains("mWindowingMode="+mode)
                || text.contains("windowingMode="+mode)
                || text.contains("windowingMode="+label)
                || text.contains("mWindowingMode="+label)
                || text.contains(label);
    }

    private static String contextForPackage(String output,String packageName){
        if(output==null || output.isEmpty() || packageName==null) return "";
        StringBuilder out=new StringBuilder();
        int from=0;
        int matches=0;
        while(matches<3){
            int hit=output.indexOf(packageName,from);
            if(hit<0) break;
            int start=Math.max(0,hit-CONTEXT_RADIUS);
            int end=Math.min(output.length(),hit+packageName.length()+CONTEXT_RADIUS);
            if(out.length()>0) out.append(" || ");
            out.append(output,start,end);
            from=hit+packageName.length();
            matches++;
        }
        return compact(out.toString(),5000);
    }

    private static String compact(String value,int max){
        if(value==null) return "";
        String compact=value.replace('\r',' ').replace('\n',' ').replaceAll("\\s+"," ").trim();
        return compact.length()<=max?compact:compact.substring(0,max);
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
        if (value==null || !value.matches("[A-Za-z0-9._]+"))
            throw new IllegalArgumentException("invalid-package");
        return value;
    }
}
