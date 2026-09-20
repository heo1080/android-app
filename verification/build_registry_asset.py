#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "verification/master_registry.json"
ASSET = ROOT / "app/src/main/assets/verification_registry.json"

def normalized(path):
    return json.dumps(json.loads(path.read_text(encoding="utf-8")), ensure_ascii=False, sort_keys=True, separators=(",", ":"))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.check:
        if not ASSET.exists():
            raise SystemExit("REGISTRY_ASSET_ERROR: app asset is missing")
        if normalized(SOURCE) != normalized(ASSET):
            raise SystemExit("REGISTRY_ASSET_ERROR: verification_registry.json is stale; run verification/build_registry_asset.py")
        print("REGISTRY_ASSET_OK")
        return
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    data = json.loads(SOURCE.read_text(encoding="utf-8"))
    ASSET.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"REGISTRY_ASSET_WRITTEN {ASSET}")

if __name__ == "__main__":
    main()
