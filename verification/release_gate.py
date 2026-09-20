#!/usr/bin/env python3
import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "verification/master_registry.json"

def fail(msg):
    raise SystemExit("STABLE_RELEASE_GATE_ERROR: " + msg)

ap = argparse.ArgumentParser()
ap.add_argument("--source-commit")
args = ap.parse_args()

data = json.loads(REGISTRY.read_text(encoding="utf-8"))
source_commit = args.source_commit or subprocess.check_output(
    ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
).strip()

if data.get("schema_version") != 3:
    fail("Master Registry schema v3 is required")

p0_open = [
    kb["id"] for kb in data.get("known_bad_library", [])
    if kb.get("severity") == "P0" and kb.get("status") != "CLOSED"
]
if p0_open:
    fail("P0 Known-Bad not CLOSED: " + ", ".join(p0_open))

reverify = [f["feature_id"] for f in data.get("features", []) if f.get("state") == "REVERIFY_REQUIRED"]
if reverify:
    fail("REVERIFY_REQUIRED features remain: " + ", ".join(reverify))

build_identity = next((f for f in data["features"] if f["feature_id"] == "BUILD_IDENTITY"), None)
if not build_identity or build_identity.get("state") != "VERIFIED":
    fail("BUILD_IDENTITY must be VERIFIED before stable publishing")

diag = next((f for f in data["features"] if f["feature_id"] == "DIAGNOSTIC_CAPTURE_UPLOAD"), None)
if not diag or diag.get("state") != "VERIFIED":
    fail("DIAGNOSTIC_CAPTURE_UPLOAD must be VERIFIED")

base = data.get("source", {}).get("base_commit")
if not base:
    fail("source.base_commit missing")
ancestor = subprocess.run(
    ["git", "merge-base", "--is-ancestor", base, source_commit],
    cwd=ROOT,
    stdout=subprocess.DEVNULL,
    stderr=subprocess.DEVNULL,
).returncode == 0
if not ancestor:
    fail(f"registry base {base} is not ancestor of release source {source_commit}")

print(
    "STABLE_RELEASE_GATE_OK "
    f"source={source_commit} registry_base={base} p0_open=0 reverify=0"
)
