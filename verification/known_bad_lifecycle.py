#!/usr/bin/env python3
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "verification/master_registry.json"
ASSET = ROOT / "app/src/main/assets/verification_registry.json"

def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def next_status(current, test_results, evidence_hash, previous_hash):
    if previous_hash == evidence_hash:
        return current, "same_evidence_no_transition"
    if any(r and r.get("result") == "FAIL" for r in test_results):
        return "OPEN", "fresh_failure_reopens"
    all_ready = bool(test_results) and all(r and r.get("promotion_ready") for r in test_results)
    any_signal = any(r and r.get("result") in {"PASS", "INCONCLUSIVE"} for r in test_results)
    if current == "OPEN" and any_signal:
        return "FIX_CANDIDATE", "fresh_evidence_after_open"
    if current == "FIX_CANDIDATE" and all_ready:
        return "REAL_CAR_RETEST", "repeatability_ready"
    if current == "REAL_CAR_RETEST" and all_ready:
        return "CLOSED", "fresh_retest_repeatability_ready"
    return current, "no_transition"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("evaluation_json")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    evaluation_path = Path(args.evaluation_json)
    evaluation = json.loads(evaluation_path.read_text(encoding="utf-8"))
    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    by_test = {r["test_id"]: r for r in evaluation.get("results", [])}
    evidence_hash = sha256(evaluation_path)
    transitions = []

    for kb in registry.get("known_bad_library", []):
        tests = [by_test.get(t) for t in kb.get("required_test_ids", [])]
        current = kb.get("status", "OPEN")
        target, reason = next_status(current, tests, evidence_hash, kb.get("last_evidence_sha256"))
        transitions.append({
            "id": kb["id"],
            "from": current,
            "to": target,
            "reason": reason,
            "required_test_ids": kb.get("required_test_ids", []),
        })
        if args.apply and target != current:
            kb["status"] = target
            kb["last_evidence_sha256"] = evidence_hash
            kb["last_transition_at"] = datetime.now(timezone.utc).isoformat()

    print(json.dumps({
        "schema_version": 1,
        "evaluation_sha256": evidence_hash,
        "transitions": transitions,
    }, ensure_ascii=False, indent=2))

    if args.apply:
        rendered = json.dumps(registry, ensure_ascii=False, indent=2) + "\n"
        REGISTRY.write_text(rendered, encoding="utf-8")
        ASSET.write_text(rendered, encoding="utf-8")

if __name__ == "__main__":
    main()
