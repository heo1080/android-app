package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Registry-first evidence runtime for Dolphin Launcher V1.
 * This class never sends vehicle commands.
 */
public final class VerificationEvidenceRuntime {
    private static final String TAG = "V1Verification";
    private static final String LEDGER = "verification_evidence.jsonl";
    private static final String SESSION_ID = UUID.randomUUID().toString();

    private VerificationEvidenceRuntime() {}

    public static JSONObject loadRegistry(Context context) throws Exception {
        JSONObject registry = new JSONObject(readAsset(context, "verification_registry.json"));
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

    public static void markProblem(Context context, String note) {
        append(context, "FIELD_PROBLEM_MARKER", null, UUID.randomUUID().toString(),
                "NEED_MORE_DATA", note);
    }

    private static synchronized void append(Context context, String event, String testId,
                                            String correlationId, String result, String note) {
        try {
            JSONObject row = new JSONObject();
            row.put("schema_version", 2);
            row.put("timestamp_ms", System.currentTimeMillis());
            row.put("event", event);
            row.put("test_id", testId == null ? JSONObject.NULL : testId);
            row.put("correlation_id", correlationId);
            row.put("result", result == null ? JSONObject.NULL : result);
            row.put("note", note == null ? JSONObject.NULL : note);
            row.put("diagnostic_session_id", SESSION_ID);

            JSONObject build = new JSONObject();
            build.put("package", context.getPackageName());
            build.put("version_name", versionName(context));
            build.put("version_code", versionCode(context));
            build.put("source_commit", BuildConfig.SOURCE_COMMIT);
            build.put("apk_sha256", apkSha256(context));
            row.put("build", build);

            JSONObject vehicle = new JSONObject();
            vehicle.put("device_model", Build.MODEL);
            vehicle.put("device_product", Build.PRODUCT);
            vehicle.put("android_release", Build.VERSION.RELEASE);
            vehicle.put("sdk", Build.VERSION.SDK_INT);
            vehicle.put("build_fingerprint", Build.FINGERPRINT);
            row.put("vehicle", vehicle);

            try (FileWriter writer = new FileWriter(evidenceLedger(context), true)) {
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

    public static int ledgerLineCount(Context context) {
        File file = evidenceLedger(context);
        if (!file.exists()) return 0;
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) count++;
        } catch (Exception e) {
            Log.e(TAG, "ledger count failed", e);
        }
        return count;
    }

    public static File createEvidenceBundle(Context context) throws Exception {
        File base = context.getExternalFilesDir("verification");
        if (base == null) base = new File(context.getFilesDir(), "verification");
        if (!base.exists() && !base.mkdirs()) {
            throw new IllegalStateException("Cannot create verification export directory");
        }
        File zip = new File(base, "DolphinV1_Verification_" + System.currentTimeMillis() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            addAsset(context, out, "verification_registry.json");
            addAsset(context, out, "test_log_contracts.json");
            addAsset(context, out, "feature_dependencies.json");
            addAsset(context, out, "runtime_surfaces.json");
            File ledger = evidenceLedger(context);
            if (ledger.exists()) addFile(out, ledger, LEDGER);

            JSONObject identity = new JSONObject();
            identity.put("package", context.getPackageName());
            identity.put("version_name", versionName(context));
            identity.put("version_code", versionCode(context));
            identity.put("source_commit", BuildConfig.SOURCE_COMMIT);
            identity.put("apk_sha256", apkSha256(context));
            identity.put("device_model", Build.MODEL);
            identity.put("build_fingerprint", Build.FINGERPRINT);
            identity.put("session_id", SESSION_ID);

            out.putNextEntry(new ZipEntry("build_identity.json"));
            out.write(identity.toString(2).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    private static String readAsset(Context context, String name) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void addAsset(Context context, ZipOutputStream out, String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            out.putNextEntry(new ZipEntry(name));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.closeEntry();
        }
    }

    private static void addFile(ZipOutputStream out, File file, String name) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            out.putNextEntry(new ZipEntry(name));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.closeEntry();
        }
    }

    private static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static long versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String apkSha256(Context context) {
        try (InputStream in = new FileInputStream(context.getApplicationInfo().sourceDir)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
