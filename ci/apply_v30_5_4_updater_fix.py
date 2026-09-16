#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
build_path = ROOT / "app/build.gradle.kts"
update_path = ROOT / "app/src/main/java/com/byd/dolphin/autoassistant/manager/AppUpdateManager.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# v30.5.3 -> v30.5.4
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "versionCode = 41", "versionCode = 42", "versionCode 42")
build = replace_once(
    build,
    'versionName = "3.0.11-v30.5.3-korean-tts-engine"',
    'versionName = "3.0.12-v30.5.4-api29-updater-fix"',
    "versionName v30.5.4",
)
build_path.write_text(build, encoding="utf-8")

update = update_path.read_text(encoding="utf-8")
update = update.replace("* v30.5.2 self-update client.", "* v30.5.4 self-update client (Android 10 archive-signature compatibility).", 1)

old = '''    @Suppress("DEPRECATION")
    private fun verifyPackageAndSigner(activity: AppCompatActivity, apkFile: File) {
        val pm = activity.packageManager
        val archive = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: error("다운로드 APK의 패키지 정보를 읽을 수 없습니다.")

        require(archive.packageName == activity.packageName) {
            "패키지명이 다릅니다: ${archive.packageName}"
        }

        val installed = pm.getPackageInfo(
            activity.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )

        val installedDigests = installed.signingInfo?.apkContentsSigners
            ?.map { signature -> sha256(signature.toByteArray()) }
            ?.toSet()
            .orEmpty()
        val archiveDigests = archive.signingInfo?.apkContentsSigners
            ?.map { signature -> sha256(signature.toByteArray()) }
            ?.toSet()
            .orEmpty()

        require(installedDigests.isNotEmpty()) { "현재 앱의 서명 인증서를 읽지 못했습니다." }
        require(archiveDigests == installedDigests) {
            "다운로드 APK의 서명이 현재 설치된 DolphinAssistant와 다릅니다."
        }
    }
'''

new = '''    @Suppress("DEPRECATION")
    private fun verifyPackageAndSigner(activity: AppCompatActivity, apkFile: File) {
        val pm = activity.packageManager

        // Android 9/10 (including the DiLink 3 Android 10 build) can return a valid
        // PackageInfo while leaving signingInfo=null for an APK archive when only
        // GET_SIGNING_CERTIFICATES is requested. Ask for both representations and
        // fall back to PackageInfo.signatures. Android's PackageInstaller still
        // performs its own cryptographic signer verification before replacement.
        val signerFlags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apkFile.absolutePath, signerFlags)
            ?: error("다운로드 APK의 패키지 정보를 읽을 수 없습니다.")

        require(archive.packageName == activity.packageName) {
            "패키지명이 다릅니다: ${archive.packageName}"
        }

        val installed = pm.getPackageInfo(activity.packageName, signerFlags)
        val installedDigests = signerDigests(installed)
        val archiveDigests = signerDigests(archive)

        DolphinLogger.i(
            TAG,
            "signer check sdk=${android.os.Build.VERSION.SDK_INT} " +
                "installed=${installedDigests.sorted()} archive=${archiveDigests.sorted()} " +
                "archiveSigningInfo=${archive.signingInfo != null} " +
                "archiveLegacySignatures=${archive.signatures?.size ?: 0}"
        )

        require(installedDigests.isNotEmpty()) {
            "현재 앱의 서명 인증서를 읽지 못했습니다."
        }
        require(archiveDigests.isNotEmpty()) {
            "다운로드 APK의 서명 인증서를 읽지 못했습니다. (Android ${android.os.Build.VERSION.RELEASE})"
        }
        require(archiveDigests == installedDigests) {
            "다운로드 APK의 서명이 현재 설치된 DolphinAssistant와 다릅니다. " +
                "installed=${installedDigests.joinToString(",")} " +
                "download=${archiveDigests.joinToString(",")}"
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val modern = info.signingInfo?.apkContentsSigners
        val signers = if (!modern.isNullOrEmpty()) modern else info.signatures
        return signers
            ?.map { signature -> sha256(signature.toByteArray()) }
            ?.toSet()
            .orEmpty()
    }
'''

update = replace_once(update, old, new, "API29 signer verifier")
update_path.write_text(update, encoding="utf-8")

checks = {
    "version 42": 'versionCode = 42' in build,
    "v30.5.4 name": '3.0.12-v30.5.4-api29-updater-fix' in build,
    "combined signer flags": 'GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES' in update,
    "legacy signature fallback": 'else info.signatures' in update,
    "signer diagnostics": 'archiveLegacySignatures=' in update,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("v30.5.4 updater sanity check failed: " + ", ".join(failed))

print("v30.5.4 Android 10 updater compatibility patch applied")
