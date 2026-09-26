package com.dolphin.launcher.v1;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;

/**
 * Read-only planner for the requested reverse-parking hazard-light automation.
 *
 * Desired policy:
 *   first R -> hazard ON
 *   any parking gear changes -> keep hazard ON
 *   final P -> wait 10 seconds -> hazard OFF
 *
 * This class intentionally performs zero vehicle writes. The legacy DiLink surface
 * proves getDoubleFlashLightState() readback, but no hazard setter/readback pair has
 * been proven on the Korean Dolphin. The planner records what it would request so a
 * real-car session can validate gear timing and API capability before actuation.
 */
public final class ParkingHazardAutomationProbe {
    private static final String LIGHT =
            "android.hardware.bydauto.light.BYDAutoLightDevice";
    private static final long PARK_COMPLETE_DELAY_MS = 10_000L;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean parkingSession;
    private boolean parkOffArmed;
    private String lastGear;

    private final Runnable parkComplete = () -> {
        parkOffArmed = false;
        if (!parkingSession || !"P".equals(lastGear)) return;
        recordPlan("PARK_COMPLETE", "OFF", "final_p_stable_10s");
        parkingSession = false;
    };

    public ParkingHazardAutomationProbe(Context context) {
        this.app = context.getApplicationContext();
    }

    public static void captureCapability(Context context) {
        Context app = context.getApplicationContext();
        try {
            Class<?> clazz = Class.forName(LIGHT);
            int matching = 0;
            boolean getterPresent = false;
            boolean setterCandidatePresent = false;

            for (Method method : clazz.getMethods()) {
                String name = method.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.contains("doubleflash")
                        || lower.contains("hazard")
                        || lower.contains("danger")
                        || lower.contains("emergency"))) {
                    continue;
                }
                matching++;
                boolean readCandidate = (name.startsWith("get") || name.startsWith("is"))
                        && method.getParameterTypes().length == 0;
                boolean writeCandidate = !readCandidate;
                if ("getDoubleFlashLightState".equals(name)
                        && method.getParameterTypes().length == 0) {
                    getterPresent = true;
                }
                if (writeCandidate) setterCandidatePresent = true;

                VerificationEvidenceRuntime.recordPassiveEvent(
                        app, "PARK_HAZARD_CAPABILITY_METHOD",
                        "name=" + name
                                + ";params=" + Arrays.toString(method.getParameterTypes())
                                + ";return=" + method.getReturnType().getName()
                                + ";static=" + Modifier.isStatic(method.getModifiers())
                                + ";read_candidate=" + readCandidate
                                + ";write_candidate=" + writeCandidate
                                + ";invoked=false"
                                + ";vehicle_write=false");
            }

            Integer hazardRaw = readHazardRaw(app);
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app, "PARK_HAZARD_CAPABILITY",
                    "class_present=true"
                            + ";matching_methods=" + matching
                            + ";getter_present=" + getterPresent
                            + ";hazard_raw=" + String.valueOf(hazardRaw)
                            + ";setter_candidate_present=" + setterCandidatePresent
                            + ";setter_proven=false"
                            + ";independent_write_readback_proven=false"
                            + ";policy=R_ON_keep_until_final_P_plus_10s_OFF"
                            + ";actuation=false"
                            + ";vehicle_write=false");
        } catch (Throwable t) {
            Throwable e = t.getCause() != null ? t.getCause() : t;
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app, "PARK_HAZARD_CAPABILITY_FAILED",
                    "class=" + LIGHT
                            + ";error=" + e.getClass().getSimpleName()
                            + ":" + String.valueOf(e.getMessage())
                            + ";actuation=false;vehicle_write=false");
        }
    }

    public void onGear(String value) {
        String gear = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        if (gear == null || gear.isEmpty()) return;
        lastGear = gear;

        if ("R".equals(gear)) {
            cancelParkOff("reverse_reentered");
            if (!parkingSession) {
                parkingSession = true;
                recordPlan("REVERSE_START", "ON", "first_reverse_entry");
            } else {
                recordPlan("PARKING_CONTINUE", "ON", "reverse_reentered");
            }
            return;
        }

        if (!parkingSession) return;

        if ("P".equals(gear)) {
            if (!parkOffArmed) {
                parkOffArmed = true;
                main.postDelayed(parkComplete, PARK_COMPLETE_DELAY_MS);
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app, "PARK_HAZARD_PARK_ARMED",
                        "gear=P;delay_ms=" + PARK_COMPLETE_DELAY_MS
                                + ";desired_hazard=ON"
                                + ";final_off_pending=true"
                                + ";vehicle_write=false");
            }
            return;
        }

        cancelParkOff("gear_changed_to_" + gear);
        recordPlan("PARKING_CONTINUE", "ON", "gear=" + gear);
    }

    public void release() {
        main.removeCallbacks(parkComplete);
        parkOffArmed = false;
    }

    private void cancelParkOff(String reason) {
        if (!parkOffArmed) return;
        main.removeCallbacks(parkComplete);
        parkOffArmed = false;
        VerificationEvidenceRuntime.recordPassiveEvent(
                app, "PARK_HAZARD_PARK_CANCELLED",
                "reason=" + reason + ";desired_hazard=ON;vehicle_write=false");
    }

    private void recordPlan(String phase, String desired, String reason) {
        VerificationEvidenceRuntime.recordPassiveEvent(
                app, "PARK_HAZARD_PLAN",
                "phase=" + phase
                        + ";gear=" + String.valueOf(lastGear)
                        + ";desired_hazard=" + desired
                        + ";reason=" + reason
                        + ";hazard_raw=" + String.valueOf(readHazardRaw(app))
                        + ";setter_proven=false"
                        + ";would_request_only=true"
                        + ";vehicle_write=false");
    }

    private static Integer readHazardRaw(Context context) {
        try {
            Class<?> clazz = Class.forName(LIGHT);
            Method getter = clazz.getMethod("getDoubleFlashLightState");
            Context bydContext = BydPermissionContext.wrap(context.getApplicationContext());
            Object instance = clazz.getMethod("getInstance", Context.class)
                    .invoke(null, bydContext);
            if (instance == null) return null;
            Object raw = getter.invoke(instance);
            return raw instanceof Number ? ((Number) raw).intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
