#!/usr/bin/env python3
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "verification/master_registry.json"

def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()

head = run("git", "rev-parse", "HEAD")
try:
    parent = run("git", "rev-parse", "HEAD^")
except subprocess.CalledProcessError:
    print("REGISTRY_SYNC_SKIP no parent commit")
    raise SystemExit(0)

changed = [x for x in run("git", "diff", "--name-only", parent, head).splitlines() if x]
runtime_prefixes = ("app/", "ci/", "gradle/")
runtime_names = {
    "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat", ".github/workflows/build.yml"
}
runtime_changed = [p for p in changed if p.startswith(runtime_prefixes) or p in runtime_names]
registry_changed = any(p in changed for p in {
    "verification/master_registry.json",
    "verification/test_log_contracts.json",
    "verification/runtime_surfaces.json",
    "verification/feature_dependencies.json",
})

if runtime_changed and not registry_changed:
    raise SystemExit(
        "REGISTRY_SYNC_ERROR: runtime/build changed without registry evidence update: "
        + ", ".join(runtime_changed[:20])
    )

data = json.loads(REGISTRY.read_text(encoding="utf-8"))
base_commit = data.get("source", {}).get("base_commit")
if runtime_changed and base_commit != parent:
    raise SystemExit(
        f"REGISTRY_FRESHNESS_ERROR: source.base_commit={base_commit} expected parent={parent}"
    )

if base_commit:
    ok = subprocess.run(
        ["git", "merge-base", "--is-ancestor", base_commit, head],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0
    if not ok:
        raise SystemExit(f"REGISTRY_FRESHNESS_ERROR: base_commit is not an ancestor: {base_commit}")

print(
    "REGISTRY_CHANGE_SYNC_OK "
    f"head={head} parent={parent} runtime_changes={len(runtime_changed)} "
    f"registry_changed={registry_changed}"
)
