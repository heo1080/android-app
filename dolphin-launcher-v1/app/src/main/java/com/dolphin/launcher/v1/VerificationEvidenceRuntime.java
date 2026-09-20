package com.dolphin.launcher.v1;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal V1 verification runtime recovery surface. Append-only evidence; no vehicle writes. */
public final class VerificationEvidenceRuntime {
    private static final String TAG = "V1Verification";
    private static final String LEDGER = "verification_evidence.jsonl";

    private VerificationEvidenceRuntime() {}

    public static JSONObject loadRegistry(Context context) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("verification_registry.json"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        JSONObject registry = new JSONObject(out.toString());
        if (registry.optInt("schema_version", -1) != 3) {
            throw new IllegalStateException("Verification registry schema must be 3");
        }
        return registry;
    }

    public static String startTest(Context context, String testId) {
        String correlationId = UUID.randomUUID().toString();
        append(context, "TEST_START", testId, correlationId, "STARTED", null);
        return correlationId;
    }

    public static void operatorResult(Context context, String testId, String correlationId,
                                      String result, String note) {
        append(context, "OPERATOR_RESULT", testId, correlationId, result, note);
    }

    public static void endTest(Context context, String testId, String correlationId,
                               String result, String note) {
        append(context, "TEST_END", testId, correlationId, result, note);
    }

    private static synchronized void append(Context context, String event, String testId,
                                            String correlationId, String result, String note) {
        try {
            JSONObject row = new JSONObject();
            row.put("schema_version", 1);
            row.put("timestamp_ms", System.currentTimeMillis());
            row.put("event", event);
            row.put("test_id", testId == null ? JSONObject.NULL : testId);
            row.put("correlation_id", correlationId);
            row.put("result", result == null ? JSONObject.NULL : result);
            row.put("note", note == null ? JSONObject.NULL : note);
            row.put("package", context.getPackageName());
            File file = new File(context.getFilesDir(), LEDGER);
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(row.toString());
                writer.write("\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "evidence append failed", e);
        }
    }

    public static File evidenceLedger(Context context) {
        return new File(context.getFilesDir(), LEDGER);
    }
}
