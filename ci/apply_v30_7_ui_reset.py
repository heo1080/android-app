from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(rel):
    p = ROOT / rel
    return p, p.read_text(encoding="utf-8")


def write(p, text):
    p.write_text(text, encoding="utf-8")


# 1) Attach the new automotive shell after v30.6.x has finished creating its
# integrated LAB entry. This preserves all legacy listeners/controllers.
main_path, main = read("app/src/main/java/com/byd/dolphin/autoassistant/MainActivity.kt")
anchor = "        IntegratedBetaUi.attachDashboardEntry(this, layoutMainDashboard, audioManager)\n"
hook = anchor + "        V307UiShell.attach(this, audioManager)\n"
if "V307UiShell.attach(this, audioManager)" not in main:
    if anchor not in main:
        raise SystemExit("v30.7: IntegratedBetaUi dashboard hook not found")
    main = main.replace(anchor, hook, 1)
write(main_path, main)


# 2) Expose the already-existing integrated research dialog to the new LAB hub.
lab_path, lab = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/IntegratedBetaUi.kt")
if "fun openLab(activity: AppCompatActivity, audioManager: VoiceAndSoundManager)" not in lab:
    needle = "    private fun showIntegratedDialog(activity: AppCompatActivity, audioManager: VoiceAndSoundManager) {\n"
    if needle not in lab:
        raise SystemExit("v30.7: showIntegratedDialog signature not found")
    wrapper = (
        "    fun openLab(activity: AppCompatActivity, audioManager: VoiceAndSoundManager) {\n"
        "        showIntegratedDialog(activity, audioManager)\n"
        "    }\n\n"
        + needle
    )
    lab = lab.replace(needle, wrapper, 1)
write(lab_path, lab)


# 3) Make the checked-in v30.7 shell compile regardless of package wildcard imports.
shell_path, shell = read("app/src/main/java/com/byd/dolphin/autoassistant/manager/V307UiShell.kt")
if "import com.byd.dolphin.autoassistant.util.DolphinLogger" not in shell:
    shell = shell.replace(
        "import com.byd.dolphin.autoassistant.R\n",
        "import com.byd.dolphin.autoassistant.R\nimport com.byd.dolphin.autoassistant.util.DolphinLogger\n",
        1,
    )
write(shell_path, shell)


# 4) v30.7.0 must be a real Android upgrade from v30.6.3 (versionCode 46).
# Keep vehicle-control semantics unchanged; this is an architecture/UI reset.
gradle_path, gradle = read("app/build.gradle.kts")
gradle, count_code = re.subn(r"versionCode\s*=\s*\d+", "versionCode = 47", gradle, count=1)
gradle, count_name = re.subn(r'versionName\s*=\s*"[^"]+"', 'versionName = "3.1.4-v30.7.0-ui-reset"', gradle, count=1)
if count_code != 1 or count_name != 1:
    raise SystemExit(f"v30.7: version replacement failed code={count_code} name={count_name}")
write(gradle_path, gradle)


# 5) Guard rails: these checks intentionally fail CI if a later v30.6 patch
# changes the expected integration points.
final_gradle = gradle_path.read_text(encoding="utf-8")
checks = {
    "shell-hook": "V307UiShell.attach(this, audioManager)" in main_path.read_text(encoding="utf-8"),
    "lab-public-entry": "fun openLab(activity: AppCompatActivity, audioManager: VoiceAndSoundManager)" in lab_path.read_text(encoding="utf-8"),
    "v30.7-version-name": "3.1.4-v30.7.0-ui-reset" in final_gradle,
    "v30.7-version-code": "versionCode = 47" in final_gradle,
    "research-status-model": all(x in shell_path.read_text(encoding="utf-8") for x in ("VERIFIED", "BETA", "LAB")),
}
failed = [k for k, ok in checks.items() if not ok]
if failed:
    raise SystemExit("v30.7 verification failed: " + ", ".join(failed))

print("v30.7 UI Architecture Reset applied")
for key in checks:
    print(f"  OK {key}")
