package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Registry-first evidence runtime for Dolphin Launcher V1.
 * Passive evidence only: this class never sends vehicle commands.
 */
public final class VerificationEvidenceRuntime {
    private static final String TAG = "V1Verification";
    private static final String LEDGER = "verification_evidence.jsonl";
    private static final String PREFS = "verification_runtime";
    private static final String KEY_SESSION = "active_session_id";
    private static final String KEY_LAST_UPLOAD = "last_upload_receipt";
    private static final String UPLOAD_URL = "https://dolphin-v1-evidence.heo1080.workers.dev/upload";
    private static final long MAX_UPLOAD_BYTES = 20L * 1024L * 1024L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static boolean PROCESS_SESSION_STARTED = false;
    private static boolean NETWORK_CALLBACK_REGISTERED = false;
    private static ConnectivityManager.NetworkCallback NETWORK_CALLBACK;

    private VerificationEvidenceRuntime() {}

    public static JSONObject loadRegistry(Context context) throws Exception {
        JSONObject registry = new JSONObject(readAsset(context, "verification_registry.json"));
        if (registry.optInt("schema_version", -1) != 3) {
            throw new IllegalStateException("Verification registry schema must be 3");
        }
        return registry;
    }

    public static synchronized String beginSession(Context context, String reason) {
        String session = UUID.randomUUID().toString();
        prefs(context).edit().putString(KEY_SESSION, session).apply();
        append(context, "SESSION_START", null, UUID.randomUUID().toString(), "STARTED", reason);
        PROCESS_SESSION_STARTED = true;
        return session;
    }

    public static synchronized String ensureProcessSession(Context context, String reason) {
        if (!PROCESS_SESSION_STARTED) return beginSession(context, reason);
        return currentSessionId(context);
    }

    public static synchronized String currentSessionId(Context context) {
        String session = prefs(context).getString(KEY_SESSION, null);
        if (session == null || session.isEmpty()) {
            session = UUID.randomUUID().toString();
            prefs(context).edit().putString(KEY_SESSION, session).apply();
        }
        return session;
    }

    public static void recordPassiveEvent(Context context, String event, String note) {
        append(context, event, null, UUID.randomUUID().toString(), "OBSERVED", note);
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
        queueBundleAndUpload(context, "problem-marker");
    }

    private static synchronized void append(Context context, String event, String testId,
                                            String correlationId, String result, String note) {
        try {
            JSONObject row = new JSONObject();
            row.put("schema_version", 3);
            row.put("timestamp_ms", System.currentTimeMillis());
            row.put("event", event);
            row.put("test_id", testId == null ? JSONObject.NULL : testId);
            row.put("correlation_id", correlationId);
            row.put("result", result == null ? JSONObject.NULL : result);
            row.put("note", note == null ? JSONObject.NULL : note);
            row.put("diagnostic_session_id", currentSessionId(context));

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

    private static File queueDir(Context context) {
        File dir = new File(context.getFilesDir(), "evidence_upload_queue");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static int pendingUploadCount(Context context) {
        File[] files = queueDir(context).listFiles((dir, name) -> name.endsWith(".zip"));
        return files == null ? 0 : files.length;
    }

    public static String lastUploadStatus(Context context) {
        String raw = prefs(context).getString(KEY_LAST_UPLOAD, null);
        if (raw == null) return "아직 없음";
        try {
            JSONObject receipt = new JSONObject(raw);
            return "성공 · " + receipt.optString("session_id", "-");
        } catch (Exception ignored) {
            return "성공";
        }
    }

    public static File createEvidenceBundle(Context context) throws Exception {
        return createEvidenceBundle(context, queueDir(context));
    }

    private static File createEvidenceBundle(Context context, File base) throws Exception {
        if (!base.exists() && !base.mkdirs()) {
            throw new IllegalStateException("Cannot create verification export directory");
        }
        String sessionId = currentSessionId(context);
        File zip = new File(base, "DolphinV1_Verification_" + sessionId + "_" +
                System.currentTimeMillis() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            addAsset(context, out, "verification_registry.json");
            addAsset(context, out, "test_log_contracts.json");
            addAsset(context, out, "feature_dependencies.json");
            addAsset(context, out, "runtime_surfaces.json");
            addSessionLedger(context, out, sessionId);

            JSONObject identity = new JSONObject();
            identity.put("package", context.getPackageName());
            identity.put("version_name", versionName(context));
            identity.put("version_code", versionCode(context));
            identity.put("source_commit", BuildConfig.SOURCE_COMMIT);
            identity.put("apk_sha256", apkSha256(context));
            identity.put("device_model", Build.MODEL);
            identity.put("build_fingerprint", Build.FINGERPRINT);
            identity.put("session_id", sessionId);

            out.putNextEntry(new ZipEntry("build_identity.json"));
            out.write(identity.toString(2).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    public static void queueBundleAndUpload(Context context, String reason) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                recordPassiveEvent(app, "AUTO_BUNDLE", reason);
                createEvidenceBundle(app);
                retryPendingUploads(app);
            } catch (Exception e) {
                Log.e(TAG, "auto bundle failed", e);
            }
        });
    }

    /** Retry queued evidence as soon as Android reports network availability. */
    public static synchronized void startAutomaticUploadRuntime(Context context) {
        if (NETWORK_CALLBACK_REGISTERED) return;
        Context app = context.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        NETWORK_CALLBACK = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                Log.i(TAG, "network available; retrying queued evidence");
                retryPendingUploadsAsync(app);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(NETWORK_CALLBACK);
            NETWORK_CALLBACK_REGISTERED = true;
            retryPendingUploadsAsync(app);
        } catch (Exception e) {
            NETWORK_CALLBACK = null;
            Log.w(TAG, "network callback registration failed; startup retry retained", e);
            retryPendingUploadsAsync(app);
        }
    }

    public static void retryPendingUploadsAsync(Context context) {
        Context app = context.getApplicationContext();
        IO.execute(() -> retryPendingUploads(app));
    }

    private static void retryPendingUploads(Context context) {
        if (!networkAvailable(context)) return;
        String key = BuildConfig.EVIDENCE_UPLOAD_KEY;
        if (key == null || key.isEmpty()) {
            Log.w(TAG, "evidence upload key unavailable; queue retained");
            return;
        }
        File[] files = queueDir(context).listFiles((dir, name) -> name.endsWith(".zip"));
        if (files == null) return;
        for (File zip : files) {
            try {
                uploadOne(context, zip, key);
            } catch (Exception e) {
                Log.w(TAG, "upload retained for retry: " + zip.getName(), e);
            }
        }
    }

    private static void uploadOne(Context context, File zip, String key) throws Exception {
        if (zip.length() <= 0 || zip.length() > MAX_UPLOAD_BYTES) {
            throw new IllegalStateException("invalid bundle size " + zip.length());
        }
        String sha = sha256(zip);
        String sessionId = sessionFromBundleName(zip.getName());
        HttpURLConnection conn = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/zip");
        conn.setRequestProperty("X-Dolphin-Upload-Key", key);
        conn.setRequestProperty("X-Dolphin-Session-Id", sessionId);
        conn.setRequestProperty("X-Dolphin-Package", context.getPackageName());
        conn.setRequestProperty("X-Dolphin-Version", versionName(context));
        conn.setRequestProperty("X-Dolphin-SHA256", sha);
        conn.setFixedLengthStreamingMode(zip.length());

        try (InputStream in = new FileInputStream(zip); OutputStream out = conn.getOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }

        int code = conn.getResponseCode();
        InputStream responseStream = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        String response = responseStream == null ? "" : readStream(responseStream);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("upload HTTP " + code + " " + response);
        }

        JSONObject body = new JSONObject(response);
        if (!body.optBoolean("ok", false)) throw new IllegalStateException("upload not acknowledged");
        JSONObject receipt = body.optJSONObject("receipt");
        if (receipt == null) receipt = body;
        String receiptSha = receipt.optString("sha256", body.optString("sha256", ""));
        String receiptSession = receipt.optString("session_id", body.optString("session_id", ""));
        if (!sha.equalsIgnoreCase(receiptSha) || !sessionId.equals(receiptSession)) {
            throw new IllegalStateException("receipt identity mismatch");
        }
        prefs(context).edit().putString(KEY_LAST_UPLOAD, receipt.toString()).apply();
        if (!zip.delete()) Log.w(TAG, "uploaded bundle could not be deleted " + zip.getName());
    }

    private static String sessionFromBundleName(String name) {
        String prefix = "DolphinV1_Verification_";
        if (!name.startsWith(prefix)) return currentFallbackSession(name);
        String rest = name.substring(prefix.length());
        int split = rest.indexOf('_');
        return split > 0 ? rest.substring(0, split) : currentFallbackSession(name);
    }

    private static String currentFallbackSession(String ignored) {
        return "unknown";
    }

    private static boolean networkAvailable(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            return network != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    private static String readStream(InputStream in) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
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

    private static void addSessionLedger(Context context, ZipOutputStream out, String sessionId) throws Exception {
        out.putNextEntry(new ZipEntry(LEDGER));
        File ledger = evidenceLedger(context);
        if (ledger.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(ledger), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JSONObject row = new JSONObject(line);
                        if (sessionId.equals(row.optString("diagnostic_session_id", ""))) {
                            out.write(line.getBytes(StandardCharsets.UTF_8));
                            out.write('\n');
                        }
                    } catch (Exception malformed) {
                        Log.w(TAG, "Skipping malformed evidence row");
                    }
                }
            }
        }
        out.closeEntry();
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
        try {
            return sha256(new File(context.getApplicationInfo().sourceDir));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String sha256(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        }
    }
}
