#!/usr/bin/env python3
import json
from pathlib import Path

REGISTRY = Path("verification/master_registry.json")
ALLOWED_STATES = {"VERIFIED", "BETA", "REVERIFY_REQUIRED", "BLOCKED", "UNSUPPORTED"}
REQUIRED_FEATURE_KEYS = {
    "req_id", "feature_id", "area", "requirement", "test_ids", "state",
    "acceptance", "required_logs", "known_bad", "dependencies",
    "real_vehicle_test", "evidence", "next_action", "confidence"
}
CONFIDENCE_KEYS = {"behavior", "root_cause", "safety", "compatibility"}

def fail(msg: str) -> None:
    raise SystemExit("VERIFICATION_REGISTRY_ERROR: " + msg)

data = json.loads(REGISTRY.read_text(encoding="utf-8"))
if data.get("schema_version") != 1:
    fail(f"unsupported schema_version={data.get('schema_version')!r}")

features = data.get("features")
if not isinstance(features, list) or not features:
    fail("features must be a non-empty list")

req_ids = set()
feature_ids = set()
test_ids = set()

for index, feature in enumerate(features, start=1):
    missing = REQUIRED_FEATURE_KEYS - set(feature)
    if missing:
        fail(f"feature[{index}] missing keys: {sorted(missing)}")

    req_id = feature["req_id"]
    feature_id = feature["feature_id"]
    if req_id in req_ids:
        fail(f"duplicate req_id: {req_id}")
    if feature_id in feature_ids:
        fail(f"duplicate feature_id: {feature_id}")
    req_ids.add(req_id)
    feature_ids.add(feature_id)

    state = feature["state"]
    if state not in ALLOWED_STATES:
        fail(f"{feature_id}: invalid state {state}")

    tids = feature["test_ids"]
    if not isinstance(tids, list) or not tids:
        fail(f"{feature_id}: test_ids must be non-empty")
    for tid in tids:
        if tid in test_ids:
            fail(f"duplicate test_id: {tid}")
        test_ids.add(tid)

    confidence = feature["confidence"]
    if set(confidence) != CONFIDENCE_KEYS:
        fail(f"{feature_id}: confidence keys must be {sorted(CONFIDENCE_KEYS)}")

known_bad = data.get("known_bad_library", [])
known_bad_ids = set()
for item in known_bad:
    kid = item.get("id")
    if not kid:
        fail("Known-Bad item without id")
    if kid in known_bad_ids:
        fail(f"duplicate Known-Bad id: {kid}")
    known_bad_ids.add(kid)
    for linked in item.get("linked", []):
        if linked not in feature_ids:
            fail(f"{kid}: linked feature does not exist: {linked}")

print(
    "VERIFICATION_REGISTRY_OK "
    f"schema={data['schema_version']} "
    f"features={len(features)} "
    f"tests={len(test_ids)} "
    f"known_bad={len(known_bad)}"
)
