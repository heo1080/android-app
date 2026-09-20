#!/usr/bin/env python3
import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "verification/test_log_contracts.json"

def read_texts(path: Path):
    chunks = []
    if path.is_dir():
        for f in path.rglob("*"):
            if f.is_file() and f.stat().st_size <= 20_000_000:
                try:
                    chunks.append(f.read_text(encoding="utf-8", errors="ignore"))
                except Exception:
                    pass
    elif path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if info.file_size > 20_000_000 or info.is_dir():
                    continue
                try:
                    chunks.append(z.read(info).decode("utf-8", errors="ignore"))
                except Exception:
                    pass
    else:
        chunks.append(path.read_text(encoding="utf-8", errors="ignore"))
    return "\n".join(chunks)

def contains(blob: str, pattern: str) -> bool:
    if pattern.startswith("re:"):
        return re.search(pattern[3:], blob, re.I | re.M) is not None
    return pattern.lower() in blob.lower()

def operator_outcome(blob: str, test_id: str):
    pattern = re.compile(r"VERIFY_RESULT[^\n]*test=" + re.escape(test_id) + r"[^\n]*outcome=(PASS|FAIL|INTERMITTENT|DELAYED)", re.I)
    matches = pattern.findall(blob)
    return matches[-1].upper() if matches else None

def evaluate(contract, blob):
    tid = contract["test_id"]
    op = operator_outcome(blob, tid)
    if op == "FAIL":
        return "FAIL", "operator"
    if op in {"INTERMITTENT", "DELAYED"}:
        return "INCONCLUSIVE", "operator:" + op

    for p in contract.get("failure_any", []):
        if contains(blob, p):
            return "FAIL", "failure_pattern:" + p

    required = contract.get("required_any", [])
    evidence = (not required) or any(contains(blob, p) for p in required)
    success = contract.get("success_any", [])
    success_seen = (not success) or any(contains(blob, p) for p in success)

    if op == "PASS":
        if evidence:
            return "PASS", "operator+runtime"
        return "NEED_MORE_DATA", "operator_pass_without_runtime_evidence"

    if evidence and success_seen and not contract.get("manual_observation_required", False):
        return "PASS", "runtime"
    if evidence:
        return "INCONCLUSIVE", "runtime_evidence_without_confirmed_outcome"
    return "NEED_MORE_DATA", "required_markers_missing"

def self_test(contracts):
    sample = """
VERIFY_RESULT test=AUD-TTS-001 feature=IN_APP_TTS_CORE outcome=PASS source=operator
stream14 voice played rate=44100 bytes=1000 mode=0
VERIFY_RESULT test=WIN-SPLIT-001 feature=SPLIT_SCREEN_TWO_APP outcome=FAIL source=operator
"""
    by_id = {c["test_id"]: c for c in contracts}
    assert evaluate(by_id["AUD-TTS-001"], sample)[0] == "PASS"
    assert evaluate(by_id["WIN-SPLIT-001"], sample)[0] == "FAIL"
    empty = evaluate(by_id["LCH-BOOT-001"], "")[0]
    assert empty == "NEED_MORE_DATA"
    print("DIAGNOSTIC_EVALUATOR_SELF_TEST_OK")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input", nargs="?")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--json-out")
    args = ap.parse_args()

    data = json.loads(CONTRACTS.read_text(encoding="utf-8"))
    contracts = data["contracts"]
    if args.self_test:
        self_test(contracts)
        return
    if not args.input:
        ap.error("input ZIP/file/directory is required unless --self-test is used")

    path = Path(args.input)
    if not path.exists():
        raise SystemExit(f"input not found: {path}")
    blob = read_texts(path)
    results = []
    for c in contracts:
        state, reason = evaluate(c, blob)
        results.append({
            "test_id": c["test_id"],
            "feature_id": c["feature_id"],
            "result": state,
            "reason": reason,
            "known_bad_ids": c.get("known_bad_ids", [])
        })
    summary = {k: sum(1 for r in results if r["result"] == k) for k in ["PASS","FAIL","INCONCLUSIVE","NEED_MORE_DATA"]}
    output = {"schema_version":1,"input":str(path),"summary":summary,"results":results}
    rendered = json.dumps(output, ensure_ascii=False, indent=2)
    if args.json_out:
        Path(args.json_out).write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    if summary["FAIL"]:
        sys.exit(2)

if __name__ == "__main__":
    main()
