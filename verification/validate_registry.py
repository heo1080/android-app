#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "verification/master_registry.json"
CONTRACTS = ROOT / "verification/test_log_contracts.json"
DEPENDENCIES = ROOT / "verification/feature_dependencies.json"
SURFACES = ROOT / "verification/runtime_surfaces.json"

ALLOWED_STATES = {"VERIFIED", "BETA", "REVERIFY_REQUIRED", "BLOCKED", "UNSUPPORTED"}
ALLOWED_KNOWN_BAD = {"OPEN", "FIX_CANDIDATE", "REAL_CAR_RETEST", "CLOSED"}
REQUIRED_FEATURE_KEYS = {
    "req_id", "feature_id", "area", "requirement", "source_paths", "test_ids", "state",
    "acceptance", "required_logs", "known_bad", "dependencies",
    "real_vehicle_test", "evidence", "next_action", "confidence"
}
REQUIRED_CONTRACT_KEYS = {
    "test_id", "feature_id", "feature_state", "required_events", "allowed_outcomes",
    "requires_build_fingerprint", "requires_preconditions", "requires_diagnostic_session",
    "manual_observation_required", "repeatability_required_sessions",
    "required_any", "success_any", "failure_any", "known_bad_ids",
    "acceptance", "required_logs"
}
CONFIDENCE_KEYS = {"behavior", "root_cause", "safety", "compatibility"}

def fail(msg: str) -> None:
    raise SystemExit("VERIFICATION_REGISTRY_ERROR: " + msg)

data = json.loads(REGISTRY.read_text(encoding="utf-8"))
if data.get("schema_version") != 3:
    fail(f"unsupported schema_version={data.get('schema_version')!r}; expected 3")
if not data.get("schema_history"):
    fail("schema_history is required")
if not data.get("release_exit_criteria"):
    fail("release_exit_criteria is required")
if not data.get("registry_runtime"):
    fail("registry_runtime is required")
if not data.get("evidence_policy"):
    fail("evidence_policy is required")
if not data.get("known_bad_lifecycle"):
    fail("known_bad_lifecycle is required")

features = data.get("features")
if not isinstance(features, list) or not features:
    fail("features must be a non-empty list")

req_ids = set()
feature_ids = set()
test_ids = set()
feature_by_id = {}

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
    feature_by_id[feature_id] = feature

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

    paths = feature["source_paths"]
    if not paths and state not in {"BLOCKED", "UNSUPPORTED"}:
        fail(f"{feature_id}: active/testable feature must have source_paths")
    for raw in paths:
        p = ROOT / raw.rstrip("/")
        if not p.exists():
            fail(f"{feature_id}: source path does not exist: {raw}")

contract_data = json.loads(CONTRACTS.read_text(encoding="utf-8"))
if contract_data.get("schema_version") != 2:
    fail("test_log_contracts schema_version must be 2")
contract_by_test = {}
for c in contract_data.get("contracts", []):
    missing = REQUIRED_CONTRACT_KEYS - set(c)
    if missing:
        fail(f"contract missing keys {sorted(missing)}: {c.get('test_id')}")
    tid = c["test_id"]
    fid = c["feature_id"]
    if tid in contract_by_test:
        fail(f"duplicate log contract: {tid}")
    if tid not in test_ids:
        fail(f"log contract references unknown test_id: {tid}")
    if fid not in feature_ids:
        fail(f"log contract references unknown feature_id: {fid}")
    feature = feature_by_id[fid]
    if tid not in feature["test_ids"]:
        fail(f"{tid}: contract feature mismatch: {fid}")
    if c["feature_state"] != feature["state"]:
        fail(f"{tid}: contract state drift: {c['feature_state']} != {feature['state']}")
    if c["required_events"] != ["TEST_START", "OPERATOR_RESULT", "TEST_END"]:
        fail(f"{tid}: structured event contract must require START/RESULT/END")
    if c["requires_build_fingerprint"] is not True or c["requires_preconditions"] is not True:
        fail(f"{tid}: build fingerprint and preconditions are mandatory")
    if feature["state"] in {"BLOCKED", "UNSUPPORTED"}:
        if set(c["allowed_outcomes"]) != {"NEED_MORE_DATA"}:
            fail(f"{tid}: blocked/unsupported test may only record NEED_MORE_DATA")
        if c["repeatability_required_sessions"] != 0:
            fail(f"{tid}: blocked/unsupported repeatability must be 0")
    else:
        if "PASS" not in c["allowed_outcomes"] or "FAIL" not in c["allowed_outcomes"]:
            fail(f"{tid}: active test must allow PASS and FAIL")
        if c["repeatability_required_sessions"] < 1:
            fail(f"{tid}: active test needs repeatability >= 1")
    contract_by_test[tid] = c

missing_contracts = sorted(test_ids - set(contract_by_test))
extra_contracts = sorted(set(contract_by_test) - test_ids)
if missing_contracts or extra_contracts:
    fail(f"1:1 Test ID contract coverage failed missing={missing_contracts} extra={extra_contracts}")

known_bad = data.get("known_bad_library", [])
known_bad_ids = set()
for item in known_bad:
    for key in ("id", "symptom", "linked", "severity", "status", "required_test_ids"):
        if key not in item:
            fail(f"Known-Bad missing {key}: {item}")
    kid = item["id"]
    if kid in known_bad_ids:
        fail(f"duplicate Known-Bad id: {kid}")
    if item["status"] not in ALLOWED_KNOWN_BAD:
        fail(f"{kid}: invalid lifecycle status {item['status']}")
    known_bad_ids.add(kid)
    linked = item["linked"]
    if not linked:
        fail(f"{kid}: linked features must not be empty")
    for fid in linked:
        if fid not in feature_ids:
            fail(f"{kid}: linked feature does not exist: {fid}")
        if feature_by_id[fid]["state"] == "VERIFIED" and item["status"] != "CLOSED":
            fail(f"{kid}: non-closed Known-Bad cannot link VERIFIED feature {fid}")
    for tid in item["required_test_ids"]:
        if tid not in test_ids:
            fail(f"{kid}: unknown required_test_id {tid}")
        contract = contract_by_test.get(tid)
        if not contract:
            fail(f"{kid}: required_test_id lacks log contract: {tid}")
        if kid not in contract["known_bad_ids"]:
            fail(f"{kid}: contract {tid} does not link back to Known-Bad")

for tid, contract in contract_by_test.items():
    for kid in contract["known_bad_ids"]:
        if kid not in known_bad_ids:
            fail(f"{tid}: contract references unknown Known-Bad {kid}")

surface_data = json.loads(SURFACES.read_text(encoding="utf-8"))
for surface in surface_data.get("surfaces", []):
    fid = surface.get("feature_id")
    raw_path = surface.get("path")
    tokens = surface.get("tokens", [])
    if fid not in feature_ids:
        fail(f"runtime surface references unknown feature: {fid}")
    if not raw_path or not tokens:
        fail(f"{fid}: runtime surface needs path and tokens")
    p = ROOT / raw_path
    if not p.is_file():
        fail(f"{fid}: runtime surface path missing: {raw_path}")
    body = p.read_text(encoding="utf-8", errors="ignore")
    for token in tokens:
        if token not in body:
            fail(f"{fid}: runtime surface token missing in {raw_path}: {token}")

dep_data = json.loads(DEPENDENCIES.read_text(encoding="utf-8"))
edges = set()
for e in dep_data.get("edges", []):
    child = e.get("feature_id")
    parent = e.get("depends_on")
    if child not in feature_ids or parent not in feature_ids:
        fail(f"dependency edge references unknown feature: {e}")
    if child == parent:
        fail(f"self dependency: {child}")
    key = (child, parent)
    if key in edges:
        fail(f"duplicate dependency edge: {child}->{parent}")
    edges.add(key)

print(
    "VERIFICATION_REGISTRY_OK "
    f"schema={data['schema_version']} "
    f"features={len(features)} "
    f"tests={len(test_ids)} "
    f"contracts={len(contract_by_test)} "
    f"coverage={len(contract_by_test)}/{len(test_ids)} "
    f"known_bad={len(known_bad)} "
    f"runtime_surfaces={len(surface_data.get('surfaces', []))} "
    f"dependency_edges={len(edges)}"
)
