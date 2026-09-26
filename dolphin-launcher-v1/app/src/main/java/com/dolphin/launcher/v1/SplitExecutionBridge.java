package com.dolphin.launcher.v1;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * DiLink 3 two-app split execution bridge.
 *
 * This restores the task/stack recovery path already present in the repository's
 * earlier real-car implementation. Shell exit codes are never treated as visual
 * split success; final primary/secondary readback remains mandatory.
 */
public final class SplitExecutionBridge {
    private static final int MODE_PRIMARY = 3;
    private static final int MODE_SECONDARY = 4;
    private static final int POLL_ATTEMPTS = 12;
    private static final long POLL_MS = 250L;
    private static final int CONTEXT_RADIUS = 2200;

    private static final Pattern STACK_HASH =
            Pattern.compile("Stack\\s+#(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVITY_STACK_HASH =
            Pattern.compile("ActivityStack[^#]*#(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_HASH = Pattern.compile("#(\\d+)");

    public static final class Result {
        public final boolean success;
        public final String detail;

        Result(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }
    }

    private static final class TaskState {
        final int taskId;
        final int stackId;
        final int mode;

        TaskState(int taskId, int stackId, int mode) {
            this.taskId = taskId;
            this.stackId = stackId;
            this.mode = mode;
        }

        static TaskState missing() {
            return new TaskState(-1, -1, -1);
        }

        String summary() {
            return "task=" + taskId + ";stack=" + stackId + ";mode=" + mode;
        }
    }

    private static final class Readback {
        final boolean leftSeen;
        final boolean rightSeen;
        final boolean leftPrimary;
        final boolean rightSecondary;
        final String leftContext;
        final String rightContext;

        Readback(
                boolean leftSeen,
                boolean rightSeen,
                boolean leftPrimary,
                boolean rightSecondary,
                String leftContext,
                String rightContext) {
            this.leftSeen = leftSeen;
            this.rightSeen = rightSeen;
            this.leftPrimary = leftPrimary;
            this.rightSecondary = rightSecondary;
            this.leftContext = leftContext;
            this.rightContext = rightContext;
        }

        boolean verified() {
            return leftSeen && rightSeen && leftPrimary && rightSecondary;
        }

        String summary() {
            return "left_seen=" + leftSeen
                    + ";right_seen=" + rightSeen
                    + ";left_primary=" + leftPrimary
                    + ";right_secondary=" + rightSecondary
                    + ";readback_verified=" + verified();
        }
    }

    private SplitExecutionBridge() {}

    public static Result inspectCurrentPair(
            Context context, String leftPackage, String rightPackage) {
        SplitCapabilityProbe.Result probe = SplitCapabilityProbe.inspect(context);
        if (!probe.authorizedPathReady()) {
            return new Result(false, "blocked;actuation=false;" + probe.evidence());
        }

        try (Dadb adb = SplitCapabilityProbe.connectAuthorized(context)) {
            return finalReadback(context, adb, leftPackage, rightPackage, false);
        } catch (Throwable t) {
            return new Result(
                    false,
                    "exception=" + t.getClass().getSimpleName()
                            + ";readback_verified=false;actuation=false");
        }
    }

    public static Result launch(
            Context context, String leftPackage, String rightPackage) {
        SplitCapabilityProbe.Result probe = SplitCapabilityProbe.inspect(context);
        if (!probe.authorizedPathReady()) {
            return new Result(false, "blocked;" + probe.evidence());
        }

        try (Dadb adb = SplitCapabilityProbe.connectAuthorized(context)) {
            ComponentName left = resolveLaunchComponent(context, leftPackage);
            ComponentName right = resolveLaunchComponent(context, rightPackage);
            if (left == null || right == null) {
                return new Result(
                        false,
                        "preflight-component-missing;left=" + (left != null)
                                + ";right=" + (right != null)
                                + ";readback_verified=false");
            }

            TaskState primary = findTaskState(adb, leftPackage, null);
            recordRecovery(
                    context,
                    "primary-initial",
                    "left=" + leftPackage + ";" + primary.summary());

            if (primary.taskId >= 0 && primary.mode != MODE_PRIMARY) {
                recordRecovery(
                        context,
                        "primary-existing-task",
                        "left=" + leftPackage + ";" + primary.summary());

                ComponentName ghost = new ComponentName(context, SplitGhostActivity.class);
                AdbShellResponse ghostStart = command(
                        context,
                        adb,
                        "ghost-primary-start",
                        "am start --user 0 --windowingMode " + MODE_PRIMARY
                                + " -n " + shellComponent(ghost));
                if (ghostStart.getExitCode() != 0) {
                    return failed("ghost-primary-start", ghostStart);
                }

                TaskState ghostState = waitForMode(
                        context,
                        adb,
                        context.getPackageName(),
                        MODE_PRIMARY,
                        "ghost-primary-wait",
                        "SplitGhostActivity");
                if (ghostState == null) {
                    return new Result(
                            false,
                            "stage=ghost-primary-wait;mode3-not-observed"
                                    + ";readback_verified=false");
                }

                TaskState refreshedPrimary = findTaskState(adb, leftPackage, null);
                if (refreshedPrimary.taskId >= 0) primary = refreshedPrimary;
                if (primary.taskId < 0) {
                    return new Result(
                            false,
                            "stage=primary-existing-refresh;task-missing"
                                    + ";readback_verified=false");
                }

                AdbShellResponse movePrimary = command(
                        context,
                        adb,
                        "primary-move-task",
                        "am stack move-task " + primary.taskId
                                + " " + ghostState.stackId + " true");
                if (movePrimary.getExitCode() != 0) {
                    return failed("primary-move-task", movePrimary);
                }
                if (waitForMode(
                        context,
                        adb,
                        leftPackage,
                        MODE_PRIMARY,
                        "primary-moved-wait",
                        null) == null) {
                    return new Result(
                            false,
                            "stage=primary-moved-wait;mode3-not-observed"
                                    + ";readback_verified=false");
                }
            } else if (primary.taskId < 0) {
                recordRecovery(
                        context,
                        "primary-cold-start",
                        "left=" + leftPackage);
                if (!startInMode(
                        context, adb, left, MODE_PRIMARY, "primary-cold")) {
                    recordRecovery(
                            context,
                            "primary-cold-retry",
                            "left=" + leftPackage);
                    if (!startInMode(
                            context, adb, left, MODE_PRIMARY, "primary-cold-retry")) {
                        return new Result(
                                false,
                                "stage=primary-cold-retry;mode3-not-observed"
                                        + ";readback_verified=false");
                    }
                }
            } else {
                recordRecovery(
                        context,
                        "primary-already-mode3",
                        "left=" + leftPackage + ";" + primary.summary());
            }

            boolean secondaryReady = startInMode(
                    context, adb, right, MODE_SECONDARY, "secondary-start");
            if (!secondaryReady) {
                TaskState secondary = findTaskState(adb, rightPackage, null);
                int secondaryStack = findStackForMode(adb, MODE_SECONDARY);
                recordRecovery(
                        context,
                        "secondary-move-probe",
                        "right=" + rightPackage + ";" + secondary.summary()
                                + ";target_stack=" + secondaryStack);

                if (secondary.taskId >= 0 && secondaryStack >= 0) {
                    AdbShellResponse moveSecondary = command(
                            context,
                            adb,
                            "secondary-move-task",
                            "am stack move-task " + secondary.taskId
                                    + " " + secondaryStack + " true");
                    if (moveSecondary.getExitCode() == 0) {
                        secondaryReady = waitForMode(
                                context,
                                adb,
                                rightPackage,
                                MODE_SECONDARY,
                                "secondary-moved-wait",
                                null) != null;
                    }
                }
            }

            if (!secondaryReady) {
                recordRecovery(
                        context,
                        "secondary-mode4-retry",
                        "right=" + rightPackage);
                secondaryReady = startInMode(
                        context, adb, right, MODE_SECONDARY, "secondary-retry");
            }

            if (!secondaryReady) {
                return new Result(
                        false,
                        "stage=secondary-retry;mode4-not-observed"
                                + ";readback_verified=false");
            }

            return finalReadback(context, adb, leftPackage, rightPackage, true);
        } catch (Throwable t) {
            return new Result(
                    false,
                    "exception=" + t.getClass().getSimpleName()
                            + ";readback_verified=false");
        }
    }

    private static boolean startInMode(
            Context context,
            Dadb adb,
            ComponentName component,
            int mode,
            String stage) throws Exception {
        String packageName = component.getPackageName();
        AdbShellResponse response = command(
                context,
                adb,
                stage,
                "am start --user 0 --windowingMode " + mode
                        + " -n " + shellComponent(component));
        if (response.getExitCode() != 0) return false;
        return waitForMode(
                context, adb, packageName, mode, stage + "-wait", null) != null;
    }

    private static TaskState waitForMode(
            Context context,
            Dadb adb,
            String packageName,
            int mode,
            String stage,
            String requiredMarker) throws Exception {
        for (int attempt = 1; attempt <= POLL_ATTEMPTS; attempt++) {
            sleep(POLL_MS);
            TaskState state = findTaskState(adb, packageName, requiredMarker);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    context,
                    "SPLIT_MODE_POLL",
                    "stage=" + stage + ";attempt=" + attempt
                            + ";package=" + packageName
                            + ";expected_mode=" + mode
                            + ";" + state.summary());
            if (state.taskId >= 0 && state.stackId >= 0 && state.mode == mode) {
                return state;
            }
        }
        return null;
    }

    private static TaskState findTaskState(
            Dadb adb, String packageName, String requiredMarker) throws Exception {
        shellArg(packageName);
        AdbShellResponse result = adb.shell("dumpsys activity activities");
        if (result.getExitCode() != 0 || result.getOutput() == null) {
            return TaskState.missing();
        }

        int stackId = -1;
        int mode = -1;
        int taskId = -1;
        for (String source : result.getOutput().split("\\r?\\n")) {
            String line = source.trim();
            Integer parsedStack = extractStackId(line);
            if (parsedStack != null) {
                stackId = parsedStack;
                int parsedMode = extractMode(line);
                mode = parsedMode >= 0 ? parsedMode : -1;
                taskId = -1;
            }

            int parsedMode = extractMode(line);
            if (parsedMode >= 0) mode = parsedMode;

            Integer parsedTask = extractTaskId(line);
            if (parsedTask != null) taskId = parsedTask;

            boolean packageMatches =
                    line.contains("A=" + packageName) || line.contains(packageName + "/");
            boolean markerMatches =
                    requiredMarker == null || line.contains(requiredMarker);
            if (taskId >= 0 && packageMatches && markerMatches) {
                return new TaskState(taskId, stackId, mode);
            }
        }
        return TaskState.missing();
    }

    private static int findStackForMode(Dadb adb, int targetMode) throws Exception {
        AdbShellResponse result = adb.shell("dumpsys activity activities");
        if (result.getExitCode() != 0 || result.getOutput() == null) return -1;

        int stackId = -1;
        for (String source : result.getOutput().split("\\r?\\n")) {
            String line = source.trim();
            Integer parsedStack = extractStackId(line);
            if (parsedStack != null) stackId = parsedStack;
            if (stackId >= 0 && extractMode(line) == targetMode) return stackId;
        }
        return -1;
    }

    private static Integer extractStackId(String line) {
        Matcher first = STACK_HASH.matcher(line);
        if (first.find()) return parseInt(first.group(1));
        Matcher second = ACTIVITY_STACK_HASH.matcher(line);
        if (second.find()) return parseInt(second.group(1));
        return null;
    }

    private static Integer extractTaskId(String line) {
        if (!line.contains("Task{") && !line.contains("TaskRecord{")) return null;
        Matcher matcher = TASK_HASH.matcher(line);
        return matcher.find() ? parseInt(matcher.group(1)) : null;
    }

    private static int extractMode(String line) {
        String value = line == null ? "" : line.toLowerCase();
        if (value.contains("split-screen-primary")
                || value.contains("mwindowingmode=3")
                || value.contains("windowingmode=3")) {
            return MODE_PRIMARY;
        }
        if (value.contains("split-screen-secondary")
                || value.contains("mwindowingmode=4")
                || value.contains("windowingmode=4")) {
            return MODE_SECONDARY;
        }
        return -1;
    }

    private static Result finalReadback(
            Context context,
            Dadb adb,
            String leftPackage,
            String rightPackage,
            boolean actuation) throws Exception {
        AdbShellResponse activities = adb.shell("dumpsys activity activities");
        AdbShellResponse stacks = adb.shell("am stack list");
        String activityOutput =
                activities.getOutput() == null ? "" : activities.getOutput();
        String stackOutput = stacks.getOutput() == null ? "" : stacks.getOutput();
        Readback readback =
                readback(activityOutput + "\n" + stackOutput, leftPackage, rightPackage);

        String detail = "dumpsys-exit=" + activities.getExitCode()
                + ";stack-exit=" + stacks.getExitCode()
                + ";" + readback.summary()
                + ";actuation=" + actuation;

        VerificationEvidenceRuntime.recordPassiveEvent(
                context,
                actuation ? "SPLIT_READBACK" : "SPLIT_READBACK_PASSIVE",
                "left=" + leftPackage + ";right=" + rightPackage + ";" + detail);

        VerificationEvidenceRuntime.recordPassiveEvent(
                context,
                "SPLIT_READBACK_LEFT_CONTEXT",
                "package=" + leftPackage + ";context=" + readback.leftContext);
        VerificationEvidenceRuntime.recordPassiveEvent(
                context,
                "SPLIT_READBACK_RIGHT_CONTEXT",
                "package=" + rightPackage + ";context=" + readback.rightContext);

        return new Result(readback.verified(), detail);
    }

    private static AdbShellResponse command(
            Context context,
            Dadb adb,
            String stage,
            String command) throws Exception {
        VerificationEvidenceRuntime.recordPassiveEvent(
                context,
                "SPLIT_ADB_COMMAND",
                "stage=" + stage + ";command=" + compact(command, 1000));
        AdbShellResponse response = adb.shell(command);
        VerificationEvidenceRuntime.recordPassiveEvent(
                context,
                "SPLIT_ADB_RESULT",
                "stage=" + stage
                        + ";exit=" + response.getExitCode()
                        + ";output=" + compact(response.getOutput(), 700));
        return response;
    }

    private static void recordRecovery(
            Context context, String stage, String detail) {
        VerificationEvidenceRuntime.recordPassiveEvent(
                context,
                "SPLIT_RECOVERY_PATH",
                "stage=" + stage + ";" + detail);
    }

    private static Result failed(String stage, AdbShellResponse response) {
        return new Result(
                false,
                "stage=" + stage + ";exit=" + response.getExitCode()
                        + ";readback_verified=false");
    }

    private static Readback readback(
            String output, String leftPackage, String rightPackage) {
        String leftContext = contextForPackage(output, leftPackage);
        String rightContext = contextForPackage(output, rightPackage);
        boolean leftSeen = !leftContext.isEmpty();
        boolean rightSeen = !rightContext.isEmpty();
        boolean leftPrimary =
                containsMode(leftContext, MODE_PRIMARY, "split-screen-primary");
        boolean rightSecondary =
                containsMode(rightContext, MODE_SECONDARY, "split-screen-secondary");
        return new Readback(
                leftSeen,
                rightSeen,
                leftPrimary,
                rightSecondary,
                leftContext,
                rightContext);
    }

    private static boolean containsMode(String text, int mode, String label) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("mWindowingMode=" + mode)
                || text.contains("windowingMode=" + mode)
                || text.contains("windowingMode=" + label)
                || text.contains("mWindowingMode=" + label)
                || text.contains(label);
    }

    private static String contextForPackage(String output, String packageName) {
        if (output == null || output.isEmpty() || packageName == null) return "";
        StringBuilder out = new StringBuilder();
        int from = 0;
        int matches = 0;
        while (matches < 3) {
            int hit = output.indexOf(packageName, from);
            if (hit < 0) break;
            int start = Math.max(0, hit - CONTEXT_RADIUS);
            int end = Math.min(
                    output.length(),
                    hit + packageName.length() + CONTEXT_RADIUS);
            if (out.length() > 0) out.append(" || ");
            out.append(output, start, end);
            from = hit + packageName.length();
            matches++;
        }
        return compact(out.toString(), 5000);
    }

    private static String compact(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return compact.length() <= max
                ? compact : compact.substring(0, max);
    }

    private static ComponentName resolveLaunchComponent(
            Context context, String packageName) {
        shellArg(packageName);
        Intent intent =
                context.getPackageManager().getLaunchIntentForPackage(packageName);
        return intent == null ? null : intent.getComponent();
    }

    private static String shellComponent(ComponentName component) {
        String value = component.flattenToShortString();
        if (value == null
                || !value.matches("[A-Za-z0-9._$]+/[A-Za-z0-9._$]+")) {
            throw new IllegalArgumentException("invalid-component");
        }
        return value;
    }

    private static String shellArg(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._]+")) {
            throw new IllegalArgumentException("invalid-package");
        }
        return value;
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
