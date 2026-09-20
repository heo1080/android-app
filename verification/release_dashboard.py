#!/usr/bin/env python3
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
registry = json.loads((ROOT / "verification/master_registry.json").read_text(encoding="utf-8"))
contracts = json.loads((ROOT / "verification/test_log_contracts.json").read_text(encoding="utf-8"))
features = registry["features"]
counts = Counter(f["state"] for f in features)
known_bad = registry.get("known_bad_library", [])
tests = [tid for f in features for tid in f["test_ids"]]
contract_ids = {c["test_id"] for c in contracts.get("contracts", [])}

print("# Dolphin Release Verification Dashboard")
print()
print(f"- Registry schema: **v{registry['schema_version']}**")
print(f"- Features: **{len(features)}**")
print(f"- Test IDs: **{len(tests)}**")
print(f"- Evidence contracts: **{len(contract_ids)}/{len(tests)}**")
for state in ["VERIFIED", "BETA", "REVERIFY_REQUIRED", "BLOCKED", "UNSUPPORTED"]:
    print(f"- {state}: **{counts.get(state, 0)}**")
print(f"- Non-closed Known-Bad: **{sum(1 for x in known_bad if x.get('status', 'OPEN') != 'CLOSED')}**")
print()

print("## Known-Bad lifecycle")
for item in known_bad:
    tests_text = ", ".join(item.get("required_test_ids", []))
    linked = ", ".join(item.get("linked", []))
    print(f"- **{item['id']} {item.get('severity', '')} · {item.get('status', 'OPEN')}** — {item['symptom']}  ")
    print(f"  Features: {linked} · Tests: {tests_text}")

print()
print("## Release blockers")
p0 = [x["id"] for x in known_bad if x.get("severity") == "P0" and x.get("status") != "CLOSED"]
reverify = [f["feature_id"] for f in features if f["state"] == "REVERIFY_REQUIRED"]
print("- P0 Known-Bad: " + (", ".join(p0) if p0 else "none"))
print("- REVERIFY_REQUIRED: " + (", ".join(reverify) if reverify else "none"))
