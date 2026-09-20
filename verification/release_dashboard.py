#!/usr/bin/env python3
import json
from collections import Counter
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
d=json.loads((ROOT/"verification/master_registry.json").read_text(encoding="utf-8"))
features=d["features"]
counts=Counter(f["state"] for f in features)
kb=d.get("known_bad_library",[])
print("# Dolphin Release Verification Dashboard")
print()
print(f"- Registry schema: **v{d['schema_version']}**")
print(f"- Features: **{len(features)}**")
for state in ["VERIFIED","BETA","REVERIFY_REQUIRED","BLOCKED","UNSUPPORTED"]:
    print(f"- {state}: **{counts.get(state,0)}**")
print(f"- Open Known-Bad: **{sum(1 for x in kb if x.get('status','OPEN')=='OPEN')}**")
print()
print("## Open Known-Bad")
for item in kb:
    if item.get("status","OPEN")!="OPEN":
        continue
    tests=", ".join(item.get("required_test_ids",[]))
    linked=", ".join(item.get("linked",[]))
    print(f"- **{item['id']} {item.get('severity','')}** — {item['symptom']}  ")
    print(f"  Features: {linked} · Tests: {tests}")
