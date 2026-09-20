#!/usr/bin/env python3
import argparse
import json
from collections import defaultdict, deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = json.loads((ROOT / "verification/master_registry.json").read_text(encoding="utf-8"))
DEPS = json.loads((ROOT / "verification/feature_dependencies.json").read_text(encoding="utf-8"))
states = {f["feature_id"]: f["state"] for f in REGISTRY["features"]}
reverse = defaultdict(list)
for e in DEPS["edges"]:
    reverse[e["depends_on"]].append(e["feature_id"])

risky = {"BLOCKED","UNSUPPORTED","REVERIFY_REQUIRED"}
impacts = {}
for source,state in states.items():
    if state not in risky:
        continue
    seen=set()
    q=deque(reverse.get(source,[]))
    while q:
        x=q.popleft()
        if x in seen: continue
        seen.add(x)
        q.extend(reverse.get(x,[]))
    if seen:
        impacts[source]=sorted(seen)

gate_errors=[]
for dependency,children in impacts.items():
    for child in children:
        if states.get(child) == "VERIFIED":
            gate_errors.append(f"{child} is VERIFIED but depends on {dependency}={states[dependency]}")

print("DEPENDENCY_IMPACT_REPORT")
for src,children in sorted(impacts.items()):
    print(f"- {src} [{states[src]}] -> " + ", ".join(children))
if not impacts:
    print("- none")

ap=argparse.ArgumentParser()
ap.add_argument("--gate",action="store_true")
args=ap.parse_args()
if args.gate and gate_errors:
    raise SystemExit("DEPENDENCY_GATE_ERROR: " + " | ".join(gate_errors))
