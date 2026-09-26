#!/usr/bin/env python3
import argparse
import json
import re
import sys
import tempfile
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
CONTRACTS = ASSETS / "test_log_contracts.json"
REGISTRY = ASSETS / "verification_registry.json"
LEDGER_NAME = "verification_evidence.jsonl"

# These files define how evidence is interpreted. They are not evidence themselves.
# Searching their own success_any/failure_any strings would let metadata self-satisfy
# or self-fail a Test ID, so they must never enter the runtime marker blob.
METADATA_ONLY_BASENAMES = {
    "verification_registry.json",
    "master_registry.json",
    "test_log_contracts.json",
    "feature_dependencies.json",
    "runtime_surfaces.json",
    "known_bad_registry.json",
    "voice_prompt_manifest.json",
    "verification_evaluation.json",
}


def metadata_only(name: str) -> bool:
    return Path(name).name in METADATA_ONLY_BASENAMES


def read_input(path: Path):
    chunks = []
    ledger_lines = []

    def consume(name: str, text: str):
        base = Path(name).name
        if base == LEDGER_NAME:
            ledger_lines.extend(text.splitlines())
        if not metadata_only(name):
            chunks.append(text)

    if path.is_dir():
        for f in path.rglob("*"):
            if not f.is_file() or f.stat().st_size > 20_000_000:
                continue
            try:
                consume(str(f.relative_to(path)), f.read_text(encoding="utf-8", errors="ignore"))
            except Exception:
                pass
    elif path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if info.file_size > 20_000_000 or info.is_dir():
                    continue
                try:
                    consume(info.filename, z.read(info).decode("utf-8", errors="ignore"))
                except Exception:
                    pass
    else:
        text = path.read_text(encoding="utf-8", errors="ignore")
        consume(path.name, text)
    return "\n".join(chunks), parse_ledger(ledger_lines)


def parse_ledger(lines):
    events = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            item = json.loads(line)
            if isinstance(item, dict):
                events.append(item)
        except Exception:
            pass
    return events


def contains(blob: str, pattern: str) -> bool:
    if pattern.startswith("re:"):
        return re.search(pattern[3:], blob, re.I | re.M) is not None
    return pattern.lower() in blob.lower()


def structured_observations(events, test_id):
    grouped = defaultdict(list)
    for event in events:
        if event.get("test_id") != test_id:
            continue
        cid = event.get("correlation_id")
        if cid:
            grouped[cid].append(event)

    observations = []
    for cid, items in grouped.items():
        by_type = defaultdict(list)
        for item in items:
            by_type[item.get("event")].append(item)
        if not all(by_type.get(t) for t in ("TEST_START", "OPERATOR_RESULT", "TEST_END")):
            continue
        result = sorted(by_type["OPERATOR_RESULT"], key=lambda x: x.get("wall_time_ms", 0))[-1]
        start = sorted(by_type["TEST_START"], key=lambda x: x.get("wall_time_ms", 0))[0]
        end = sorted(by_type["TEST_END"], key=lambda x: x.get("wall_time_ms", 0))[-1]
        observations.append({
            "correlation_id": cid,
            "start": start,
            "result": result,
            "end": end,
            "wall_time_ms": result.get("wall_time_ms", 0),
        })
    return sorted(observations, key=lambda x: x["wall_time_ms"])


def has_build_fingerprint(obs):
    build = obs["result"].get("build")
    if not isinstance(build, dict):
        return False
    required = ("version_name", "version_code", "source_commit", "apk_sha256")
    return all(build.get(k) not in (None, "", "local-unknown") for k in required)


def has_preconditions(obs):
    pre = obs["result"].get("preconditions")
    return isinstance(pre, dict) and bool(pre)


def session_key(obs, requires_session):
    sid = obs["result"].get("diagnostic_session_id")
    active = obs["result"].get("diagnostic_session_active")
    if requires_session:
        if not sid or sid == "null" or active is not True:
            return None
        return str(sid)
    build = obs["result"].get("build") or {}
    return str(sid or build.get("source_commit") or obs["correlation_id"])


def observation_result(contract, obs, runtime_blob):
    outcome = str(obs["result"].get("outcome", "")).upper()
    if outcome not in contract.get("allowed_outcomes", []):
        return "NEED_MORE_DATA", "outcome_not_allowed"

    if contract.get("requires_build_fingerprint", True) and not has_build_fingerprint(obs):
        return "NEED_MORE_DATA", "missing_build_fingerprint"
    if contract.get("requires_preconditions", True) and not has_preconditions(obs):
        return "NEED_MORE_DATA", "missing_preconditions"
    if session_key(obs, contract.get("requires_diagnostic_session", True)) is None:
        return "NEED_MORE_DATA", "diagnostic_session_not_active"

    for pattern in contract.get("failure_any", []):
        if contains(runtime_blob, pattern):
            return "FAIL", "failure_pattern:" + pattern

    if outcome == "FAIL":
        return "FAIL", "operator"
    if outcome in {"INTERMITTENT", "DELAYED"}:
        return "INCONCLUSIVE", "operator:" + outcome
    if outcome == "NEED_MORE_DATA":
        return "NEED_MORE_DATA", "operator"
    if outcome != "PASS":
        return "NEED_MORE_DATA", "missing_operator_outcome"

    success = contract.get("success_any", [])
    if success and not any(contains(runtime_blob, pattern) for pattern in success):
        return "INCONCLUSIVE", "operator_pass_without_runtime_success_marker"
    return "PASS", "structured_operator+runtime" if success else "structured_operator"


def evaluate_contract(contract, runtime_blob, events):
    observations = structured_observations(events, contract["test_id"])
    if not observations:
        return {
            "test_id": contract["test_id"],
            "feature_id": contract["feature_id"],
            "result": "NEED_MORE_DATA",
            "reason": "structured_evidence_missing",
            "pass_sessions": [],
            "repeatability_required_sessions": contract.get("repeatability_required_sessions", 0),
            "promotion_ready": False,
            "known_bad_ids": contract.get("known_bad_ids", []),
        }

    evaluated = []
    pass_sessions = set()
    for obs in observations:
        result, reason = observation_result(contract, obs, runtime_blob)
        skey = session_key(obs, contract.get("requires_diagnostic_session", True))
        if result == "PASS" and skey:
            pass_sessions.add(skey)
        evaluated.append((obs, result, reason))

    latest_obs, latest_result, latest_reason = evaluated[-1]
    required = int(contract.get("repeatability_required_sessions", 0))
    promotion_ready = latest_result == "PASS" and len(pass_sessions) >= required
    return {
        "test_id": contract["test_id"],
        "feature_id": contract["feature_id"],
        "result": latest_result,
        "reason": latest_reason,
        "latest_correlation_id": latest_obs["correlation_id"],
        "pass_sessions": sorted(pass_sessions),
        "repeatability_required_sessions": required,
        "promotion_ready": promotion_ready,
        "known_bad_ids": contract.get("known_bad_ids", []),
    }


def known_bad_recommendations(registry, results):
    by_test = {r["test_id"]: r for r in results}
    out = []
    for kb in registry.get("known_bad_library", []):
        required = kb.get("required_test_ids", [])
        linked = [by_test.get(t) for t in required]
        if any(r and r["result"] == "FAIL" for r in linked):
            recommendation = "OPEN"
        elif required and all(r and r.get("promotion_ready") for r in linked):
            recommendation = "CLOSED_CANDIDATE"
        elif any(r and r["result"] in {"PASS", "INCONCLUSIVE"} for r in linked):
            recommendation = "REAL_CAR_RETEST"
        else:
            recommendation = kb.get("status", "OPEN")
        out.append({
            "id": kb["id"],
            "current_status": kb.get("status", "OPEN"),
            "recommendation": recommendation,
            "required_test_ids": required,
        })
    return out


def _sample_events(test_id, feature_id, outcome="PASS", session="S1"):
    cid = "CID-" + session
    base = {
        "schema_version": 3,
        "correlation_id": cid,
        "test_id": test_id,
        "feature_id": feature_id,
        "diagnostic_session_id": session,
        "diagnostic_session_active": True,
        "build": {
            "version_name": "x",
            "version_code": 1,
            "source_commit": "abc",
            "apk_sha256": "123",
        },
        "preconditions": {"safe_test": True},
        "wall_time_ms": 1,
    }
    return [
        dict(base, event="TEST_START"),
        dict(base, event="OPERATOR_RESULT", outcome=outcome),
        dict(base, event="TEST_END", outcome=outcome),
    ]


def _write_test_zip(path: Path, metadata_text: str, runtime_text: str = ""):
    events = _sample_events("META-SELF-001", "MASTER_VERIFICATION_REGISTRY")
    ledger = "\n".join(json.dumps(row, ensure_ascii=False) for row in events) + "\n"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("test_log_contracts.json", metadata_text)
        z.writestr("verification_registry.json", metadata_text)
        z.writestr("known_bad_registry.json", metadata_text)
        z.writestr("verification_evaluation.json", metadata_text)
        z.writestr(LEDGER_NAME, ledger)
        if runtime_text:
            z.writestr("runtime.log", runtime_text)


def metadata_isolation_self_test():
    contract = {
        "test_id": "META-SELF-001",
        "feature_id": "MASTER_VERIFICATION_REGISTRY",
        "allowed_outcomes": ["PASS", "FAIL", "NEED_MORE_DATA"],
        "requires_build_fingerprint": True,
        "requires_preconditions": True,
        "requires_diagnostic_session": True,
        "repeatability_required_sessions": 1,
        "success_any": ["RUNTIME_SUCCESS_MARKER"],
        "failure_any": ["RUNTIME_FAILURE_MARKER"],
        "known_bad_ids": [],
    }
    metadata = json.dumps({
        "success_any": ["RUNTIME_SUCCESS_MARKER"],
        "failure_any": ["RUNTIME_FAILURE_MARKER"],
    })

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)

        metadata_only_zip = root / "metadata-only.zip"
        _write_test_zip(metadata_only_zip, metadata)
        runtime_blob, events = read_input(metadata_only_zip)
        assert "RUNTIME_SUCCESS_MARKER" not in runtime_blob
        assert "RUNTIME_FAILURE_MARKER" not in runtime_blob
        result = evaluate_contract(contract, runtime_blob, events)
        assert result["result"] == "INCONCLUSIVE", result

        success_zip = root / "runtime-success.zip"
        _write_test_zip(success_zip, metadata, "RUNTIME_SUCCESS_MARKER")
        runtime_blob, events = read_input(success_zip)
        result = evaluate_contract(contract, runtime_blob, events)
        assert result["result"] == "PASS", result
        assert result["promotion_ready"] is True, result

        failure_zip = root / "runtime-failure.zip"
        _write_test_zip(failure_zip, metadata, "RUNTIME_FAILURE_MARKER")
        runtime_blob, events = read_input(failure_zip)
        result = evaluate_contract(contract, runtime_blob, events)
        assert result["result"] == "FAIL", result

    print("DIAGNOSTIC_EVALUATOR_METADATA_ISOLATION_OK metadata_self_match=false")


def self_test(contracts):
    by_id = {c["test_id"]: c for c in contracts}
    contract = dict(by_id["AUD-TTS-001"])
    contract["success_any"] = ["stream14 voice played"]
    sample_events = []
    for session in ("S1", "S2"):
        sample_events += _sample_events(
            "AUD-TTS-001", "IN_APP_TTS_CORE", outcome="PASS", session=session
        )
    result = evaluate_contract(contract, "stream14 voice played", sample_events)
    assert result["result"] == "PASS"
    assert result["promotion_ready"] is True

    blocked = dict(by_id["DSP-ORI-001"])
    blocked_result = evaluate_contract(
        blocked,
        "",
        _sample_events("DSP-ORI-001", "FORCED_APP_ORIENTATION", outcome="PASS"),
    )
    assert blocked_result["result"] == "NEED_MORE_DATA"

    metadata_isolation_self_test()
    print("DIAGNOSTIC_EVALUATOR_SELF_TEST_OK runtime_evidence_only=true")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input", nargs="?")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--json-out")
    args = ap.parse_args()

    contract_data = json.loads(CONTRACTS.read_text(encoding="utf-8"))
    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    contracts = contract_data["contracts"]

    if args.self_test:
        self_test(contracts)
        return
    if not args.input:
        ap.error("input ZIP/file/directory is required unless --self-test is used")

    path = Path(args.input)
    if not path.exists():
        raise SystemExit(f"input not found: {path}")
    runtime_blob, events = read_input(path)
    results = [evaluate_contract(c, runtime_blob, events) for c in contracts]
    summary = {
        key: sum(1 for r in results if r["result"] == key)
        for key in ["PASS", "FAIL", "INCONCLUSIVE", "NEED_MORE_DATA"]
    }
    output = {
        "schema_version": 3,
        "input": str(path),
        "evidence_scope": "runtime-only; canonical metadata excluded",
        "structured_event_count": len(events),
        "summary": summary,
        "promotion_ready_tests": sorted(
            r["test_id"] for r in results if r.get("promotion_ready")
        ),
        "known_bad": known_bad_recommendations(registry, results),
        "results": results,
    }
    rendered = json.dumps(output, ensure_ascii=False, indent=2)
    if args.json_out:
        Path(args.json_out).write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    if summary["FAIL"]:
        sys.exit(2)


if __name__ == "__main__":
    main()
