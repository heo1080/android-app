package com.dolphin.launcher.v1;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AppUpdateManager {
    private static final String TAG = "V1_APP_UPDATE";
    private static final String RELEASES_API =
            "https://api.github.com/repos/heo1080/android-app/releases?per_page=20";
    private static final String TAG_PREFIX = "dolphin-launcher-v1-v";
    private static final String APK_ASSET = "DolphinLauncherV1-Evolution.apk";
    private static final String SHA_ASSET = "DolphinLauncherV1-Evolution.sha256";
    private static final String PREFS = "v1_update";
    private static final String KEY_LAST_AUTO_CHECK = "last_auto_check_ms";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static volatile ReleaseInfo pendingPermissionRelease;

    private AppUpdateManager() {}

    public static void checkForUpdates(Activity activity, boolean manual) {
        if (activity == null || activity.isFinishing()) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual) {
            long last = prefs.getLong(KEY_LAST_AUTO_CHECK, 0L);
            if (now - last < AUTO_CHECK_INTERVAL_MS) return;
            prefs.edit().putLong(KEY_LAST_AUTO_CHECK, now).apply();
        }

        new Thread(() -> {
            try {
                ReleaseInfo release = fetchLatestV1Release();
                VerificationEvidenceRuntime.recordPassiveEvent(activity, "APP_UPDATE_RELEASE_CHECK",
                        "manual=" + manual + ";release=" + release.tagName
                                + ";current=" + BuildConfig.VERSION_NAME);
                int cmp = compareVersions(release.version, parseVersion(BuildConfig.VERSION_NAME));
                if (cmp <= 0) {
                    VerificationEvidenceRuntime.recordPassiveEvent(activity, "APP_UPDATE_LATEST",
                            "release=" + release.tagName + ";current=" + BuildConfig.VERSION_NAME);
                    if (manual) {
                        activity.runOnUiThread(() -> showMessage(activity,
                                "최신 버전입니다",
                                "현재: " + BuildConfig.VERSION_NAME + "\n최신 OTA: " + release.tagName));
                    }
                    return;
                }
                VerificationEvidenceRuntime.recordPassiveEvent(activity, "APP_UPDATE_AVAILABLE",
                        "release=" + release.tagName + ";current=" + BuildConfig.VERSION_NAME);
                activity.runOnUiThread(() -> showUpdateAvailable(activity, release));
            } catch (Exception e) {
                VerificationEvidenceRuntime.recordPassiveEvent(activity, "APP_UPDATE_CHECK_FAILED",
                        "manual=" + manual + ";error=" + e.getClass().getSimpleName());
                Log.e(TAG, "update check failed", e);
                if (manual) {
                    activity.runOnUiThread(() -> showMessage(activity,
                            "업데이트 확인 실패",
                            "V1 OTA 채널을 확인하지 못했습니다.\n\n" +
                                    safeMessage(e)));
                }
            }
        }, "V1-Update-Check").start();
    }

    public static void resumePendingInstallPermission(Activity activity) {
        ReleaseInfo pending = pendingPermissionRelease;
        if (pending == null || activity == null || activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 26 &&
                !activity.getPackageManager().canRequestPackageInstalls()) return;

        pendingPermissionRelease = null;
        VerificationEvidenceRuntime.recordPassiveEvent(activity, "APP_UPDATE_INSTALL_PERMISSION_GRANTED",
                "release=" + pending.tagName);
        beginUpdate(activity, pending);
    }

    private static void showUpdateAvailable(Activity activity, ReleaseInfo release) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle("Dolphin Launcher V1 업데이트")
                .setMessage("현재: " + BuildConfig.VERSION_NAME +
                        "\n새 버전: " + release.tagName +
                        "\n\nAPK와 SHA-256을 내려받아 패키지명·versionCode·서명을 검증한 뒤 Android 설치 확인 화면으로 연결합니다.")
                .setNegativeButton("나중에", null)
                .setPositiveButton("업데이트", (d, w) -> beginUpdate(activity, release))
                .show();
    }

    private static void beginUpdate(Activity activity, ReleaseInfo release) {
        if (Build.VERSION.SDK_INT >= 26 &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingPermissionRelease = release;
            VerificationEvidenceRuntime.recordPassiveEvent(activity, "APP_UPDATE_INSTALL_PERMISSION_REQUIRED",
                    "release=" + release.tagName);
            new AlertDialog.Builder(activity)
                    .setTitle("업데이트 설치 권한")
                    .setMessage("'이 출처 허용'을 한 번 켜주세요. 앱으로 돌아오면 다운로드를 이어갑니다.")
                    .setNegativeButton("취소", (d, w) -> pendingPermissionRelease = null)
                    .setPositiveButton("설정 열기", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            pendingPermissionRelease = null;
                            Toast.makeText(activity, "설치 권한 설정을 열지 못했습니다.",
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
            return;
        }

        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("V1 업데이트")
                .setMessage("APK 다운로드 및 무결성 검증 중…")
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            try {
                File apk = downloadAndVerify(activity, release);
                activity.runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    try {
                        stageInstaller(activity, apk);
                    } catch (Exception e) {
                        Log.e(TAG, "installer staging failed", e);
                        showMessage(activity, "설치 준비 실패", safeMessage(e));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "download/verify failed", e);
                activity.runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    showMessage(activity, "업데이트 검증 실패",
                            safeMessage(e) + "\n\n현재 버전은 그대로 유지됩니다.");
                });
            }
        }, "V1-Update-Download").start();
    }

    private static ReleaseInfo fetchLatestV1Release() throws Exception {
        JSONArray releases = new JSONArray(httpGetText(RELEASES_API));
        ReleaseInfo best = null;

        for (int i = 0; i < releases.length(); i++) {
            JSONObject root = releases.optJSONObject(i);
            if (root == null || root.optBoolean("draft", false)) continue;

            String tag = root.optString("tag_name", "");
            if (!tag.startsWith(TAG_PREFIX)) continue;

            List<Integer> version = parseVersion(tag.substring(TAG_PREFIX.length()));
            if (version == null) continue;

            String apkUrl = null;
            String shaUrl = null;
            JSONArray assets = root.optJSONArray("assets");
            if (assets == null) continue;

            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (APK_ASSET.equals(name)) apkUrl = url;
                if (SHA_ASSET.equals(name)) shaUrl = url;
            }

            if (apkUrl == null || shaUrl == null) continue;
            ReleaseInfo candidate = new ReleaseInfo(tag, version, apkUrl, shaUrl);
            if (best == null || compareVersions(candidate.version, best.version) > 0) best = candidate;
        }

        if (best == null) throw new IllegalStateException("V1 OTA Release가 아직 없습니다.");
        return best;
    }

    private static File downloadAndVerify(Activity activity, ReleaseInfo release) throws Exception {
        String expectedText = httpGetText(release.shaUrl);
        String expectedHash = extractSha256(expectedText);
        if (expectedHash == null) throw new IllegalStateException("SHA-256 파일 형식 오류");

        File updateDir = new File(activity.getFilesDir(), "updates");
        if (!updateDir.exists() && !updateDir.mkdirs()) {
            throw new IllegalStateException("업데이트 저장 폴더 생성 실패");
        }

        File apk = new File(updateDir, APK_ASSET);
        if (apk.exists() && !apk.delete()) {
            throw new IllegalStateException("이전 업데이트 파일 삭제 실패");
        }
        downloadToFile(release.apkUrl, apk);

        String actualHash = sha256(apk);
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new SecurityException("APK SHA-256 불일치");
        }

        VerifiedPackage verified = verifyPackageVersionAndSigner(activity, apk);
        VerificationEvidenceRuntime.recordPassiveEvent(activity, "APP_UPDATE_PACKAGE_VERIFIED",
                "release=" + release.tagName + ";sha256=" + actualHash
                        + ";installed_version_code=" + verified.installedCode
                        + ";archive_version_code=" + verified.archiveCode
                        + ";signer_sha256=" + verified.signerSha256
                        + ";signature_match=true");
        Log.i(TAG, "verified " + release.tagName + " sha256=" + actualHash);
        return apk;
    }

    @SuppressWarnings("deprecation")
    private static VerifiedPackage verifyPackageVersionAndSigner(Activity activity, File apk) throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null) throw new IllegalStateException("다운로드 APK metadata 읽기 실패");
        if (!activity.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("패키지명 불일치: " + archive.packageName);
        }

        PackageInfo installed = pm.getPackageInfo(
                activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);

        long archiveCode = archive.getLongVersionCode();
        long installedCode = installed.getLongVersionCode();
        if (archiveCode <= installedCode) {
            throw new SecurityException("versionCode가 현재 버전보다 높지 않습니다.");
        }

        String installedSigner = signerDigest(installed);
        String archiveSigner = signerDigest(archive);
        if (installedSigner == null || archiveSigner == null ||
                !installedSigner.equals(archiveSigner)) {
            throw new SecurityException("APK 서명이 현재 설치본과 다릅니다.");
        }
        return new VerifiedPackage(installedCode, archiveCode, installedSigner);
    }

    private static String signerDigest(PackageInfo info) throws Exception {
        if (info.signingInfo == null || info.signingInfo.getApkContentsSigners().length == 0) {
            return null;
        }
        byte[] bytes = info.signingInfo.getApkContentsSigners()[0].toByteArray();
        return sha256(bytes);
    }

    private static void stageInstaller(Activity activity, File apk) throws Exception {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());
        params.setSize(apk.length());

        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             InputStream in = new FileInputStream(apk);
             java.io.OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            session.fsync(out);

            Intent status = new Intent(activity, UpdateInstallReceiver.class)
                    .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= android.app.PendingIntent.FLAG_MUTABLE;
            android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(
                    activity, sessionId, status, flags);
            session.commit(pending.getIntentSender());
            VerificationEvidenceRuntime.recordPassiveEvent(activity, "APP_UPDATE_INSTALL_STAGED",
                    "session_id=" + sessionId + ";apk_bytes=" + apk.length());
        }
    }

    private static List<Integer> parseVersion(String text) {
        if (text == null) return null;
        StringBuilder cleaned = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isDigit(ch) || (started && ch == '.')) {
                cleaned.append(ch);
                started = true;
            } else if (started) {
                break;
            }
        }
        if (cleaned.length() == 0) return null;
        String[] parts = cleaned.toString().split("\\.");
        List<Integer> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) return null;
            out.add(Integer.parseInt(p));
        }
        return out;
    }

    private static int compareVersions(List<Integer> a, List<Integer> b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            int av = i < a.size() ? a.get(i) : 0;
            int bv = i < b.size() ? b.get(i) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static String extractSha256(String text) {
        if (text == null) return null;
        String[] tokens = text.trim().split("\\s+");
        for (String token : tokens) {
            if (token.matches("(?i)[0-9a-f]{64}")) return token.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String httpGetText(String url) throws Exception {
        HttpURLConnection c = openConnection(url);
        try (InputStream in = c.getInputStream()) {
            return readUtf8(in);
        } finally {
            c.disconnect();
        }
    }

    private static void downloadToFile(String url, File file) throws Exception {
        HttpURLConnection c = openConnection(url);
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } finally {
            c.disconnect();
        }
        if (!file.isFile() || file.length() == 0) {
            throw new IllegalStateException("다운로드 APK가 비어 있습니다.");
        }
    }

    private static HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(45000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "DolphinLauncherV1/" + BuildConfig.VERSION_NAME);
        c.connect();
        if (c.getResponseCode() < 200 || c.getResponseCode() > 299) {
            int code = c.getResponseCode();
            c.disconnect();
            throw new IllegalStateException("GitHub HTTP " + code);
        }
        return c;
    }

    private static String readUtf8(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toString("UTF-8");
    }

    private static String sha256(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            return hex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private static void showMessage(Activity activity, String title, String message) {
        if (activity == null || activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("확인", null)
                .show();
    }

    private static final class VerifiedPackage {
        final long installedCode;
        final long archiveCode;
        final String signerSha256;

        VerifiedPackage(long installedCode, long archiveCode, String signerSha256) {
            this.installedCode = installedCode;
            this.archiveCode = archiveCode;
            this.signerSha256 = signerSha256;
        }
    }

    private static final class ReleaseInfo {
        final String tagName;
        final List<Integer> version;
        final String apkUrl;
        final String shaUrl;

        ReleaseInfo(String tagName, List<Integer> version, String apkUrl, String shaUrl) {
            this.tagName = tagName;
            this.version = version;
            this.apkUrl = apkUrl;
            this.shaUrl = shaUrl;
        }
    }
}
