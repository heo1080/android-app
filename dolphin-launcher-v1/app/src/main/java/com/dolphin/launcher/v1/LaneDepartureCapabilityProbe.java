package com.dolphin.launcher.v1;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;

/**
 * Read-only inventory for directional lane-departure voice prerequisites.
 *
 * Public BYD SDK research exposes lane-assistance/LKS state callbacks, but the
 * Korean Dolphin still lacks proven left/right departure semantics in V1. This
 * probe inventories the actual runtime ADAS device/listener surfaces without
 * invoking unknown methods and without enabling voice from uncorrelated values.
 */
public final class LaneDepartureCapabilityProbe {
    private static final String ADAS =
            "android.hardware.bydauto.adas.BYDAutoADASDevice";
    private static final String ADAS_LISTENER =
            "android.hardware.bydauto.adas.AbsBYDAutoADASListener";

    private LaneDepartureCapabilityProbe() {}

    public static void capture(Context context) {
        Context app = context.getApplicationContext();
        ScanSummary device = scanClass(app, ADAS, "DEVICE");
        ScanSummary listener = scanClass(app, ADAS_LISTENER, "LISTENER");

        boolean lksSurface = device.lksSurface || listener.lksSurface;
        boolean directionalCandidate = device.directionalCandidate || listener.directionalCandidate;
        boolean assistContext = device.assistContext || listener.assistContext;

        VerificationEvidenceRuntime.recordPassiveEvent(
                app, "LANE_DEPARTURE_CAPABILITY",
                "adas_class_present=" + device.classPresent
                        + ";listener_class_present=" + listener.classPresent
                        + ";matching_methods=" + (device.matchingMethods + listener.matchingMethods)
                        + ";matching_constants=" + (device.matchingFields + listener.matchingFields)
                        + ";lks_surface_present=" + lksSurface
                        + ";directional_candidate_present=" + directionalCandidate
                        + ";assist_context_surface_present=" + assistContext
                        + ";directional_signal_proven=false"
                        + ";normal_drive_voice_enabled=false"
                        + ";icc_acc_voice_enabled=false"
                        + ";fixed_voice_asset_added=false"
                        + ";vehicle_write=false");
    }

    private static ScanSummary scanClass(Context app, String className, String scope) {
        ScanSummary summary = new ScanSummary();
        try {
            Class<?> clazz = Class.forName(className);
            summary.classPresent = true;

            for (Method method : clazz.getMethods()) {
                String name = method.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!matches(lower)) continue;
                summary.matchingMethods++;
                updateFlags(summary, lower);

                boolean readCandidate = (name.startsWith("get") || name.startsWith("is"))
                        && method.getParameterTypes().length == 0;
                VerificationEvidenceRuntime.recordPassiveEvent(
                        app, "LANE_DEPARTURE_CAPABILITY_METHOD",
                        "scope=" + scope
                                + ";class=" + className
                                + ";name=" + name
                                + ";params=" + Arrays.toString(method.getParameterTypes())
                                + ";return=" + method.getReturnType().getName()
                                + ";static=" + Modifier.isStatic(method.getModifiers())
                                + ";read_candidate=" + readCandidate
                                + ";invoked=false"
                                + ";voice_enabled=false"
                                + ";vehicle_write=false");
            }

            for (Field field : clazz.getFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                String name = field.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!matches(lower)) continue;
                summary.matchingFields++;
                updateFlags(summary, lower);

                String value = "unread";
                try {
                    if (field.getType() == int.class) value = String.valueOf(field.getInt(null));
                } catch (Throwable ignored) {}

                VerificationEvidenceRuntime.recordPassiveEvent(
                        app, "LANE_DEPARTURE_CAPABILITY_CONSTANT",
                        "scope=" + scope
                                + ";class=" + className
                                + ";name=" + name
                                + ";type=" + field.getType().getName()
                                + ";value=" + value
                                + ";voice_enabled=false"
                                + ";vehicle_write=false");
            }
        } catch (Throwable t) {
            Throwable e = t.getCause() != null ? t.getCause() : t;
            VerificationEvidenceRuntime.recordPassiveEvent(
                    app, "LANE_DEPARTURE_CAPABILITY_CLASS_FAILED",
                    "scope=" + scope
                            + ";class=" + className
                            + ";error=" + e.getClass().getSimpleName()
                            + ":" + String.valueOf(e.getMessage())
                            + ";voice_enabled=false;vehicle_write=false");
        }
        return summary;
    }

    private static boolean matches(String lower) {
        return lower.contains("lks")
                || lower.contains("ldw")
                || lower.contains("ldp")
                || lower.contains("lane")
                || lower.contains("elk")
                || lower.contains("acc")
                || lower.contains("tja")
                || lower.contains("icc");
    }

    private static void updateFlags(ScanSummary summary, String lower) {
        boolean lane = lower.contains("lks")
                || lower.contains("ldw")
                || lower.contains("ldp")
                || lower.contains("lane")
                || lower.contains("elk");
        if (lane) summary.lksSurface = true;
        if (lane && (lower.contains("left") || lower.contains("right")
                || lower.contains("direction") || lower.contains("side"))) {
            summary.directionalCandidate = true;
        }
        if (lower.contains("acc") || lower.contains("tja") || lower.contains("icc")) {
            summary.assistContext = true;
        }
    }

    private static final class ScanSummary {
        boolean classPresent;
        int matchingMethods;
        int matchingFields;
        boolean lksSurface;
        boolean directionalCandidate;
        boolean assistContext;
    }
}
