#!/usr/bin/env python3
"""Evaluate Dolphin Launcher V1 live-correlation evidence conservatively.

Reads one or more DolphinV1_Verification_*.zip bundles or verification_evidence.jsonl
files. It never marks a vehicle mapping VERIFIED. A stable value observed at least
three operator markers in one session is only a single-session candidate; the same
value must repeat in at least two diagnostic sessions before it becomes a stronger
cross-session candidate.
"""
from __future__ import annotations

import argparse
import json
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

LEDGER_NAME = "verification_evidence.jsonl"
MIN_REPEAT = 3
MIN_SESSIONS = 2

MARKER_KEYS = {
    "GEAR_P_VISIBLE": ["gear_raw"],
    "GEAR_R_VISIBLE": ["gear_raw"],
    "GEAR_N_VISIBLE": ["gear_raw"],
    "GEAR_D_VISIBLE": ["gear_raw"],
    "OEM_NORMAL_VISIBLE": ["operation_raw"],
    "OEM_STANDARD_VISIBLE": ["energy_feedback_raw"],
    "SNOW_ON_VISIBLE": ["road_surface_raw"],
    "SNOW_OFF_VISIBLE": ["road_surface_raw"],
    "AUTOHOLD_SWITCH_ON_VISIBLE": ["avh_enable_raw", "avh_raw"],
    "AUTOHOLD_SWITCH_OFF_VISIBLE": ["avh_enable_raw", "avh_raw"],
    "AUTOHOLD_HELD_VISIBLE": [
        "avh_raw", "avh_enable_raw", "speed_raw", "brake_pedal_raw",
        "brake_depth_raw", "accel_depth_raw",
    ],
    "AUTOHOLD_RELEASE_VISIBLE": [
        "avh_raw", "avh_enable_raw", "speed_raw", "brake_pedal_raw",
        "brake_depth_raw", "accel_depth_raw",
    ],
    "EPB_HELD_VISIBLE": ["epb_raw"],
    "EPB_RELEASED_VISIBLE": ["epb_raw"],
    "ICC_ON_VISIBLE": ["tja_raw"],
    "ICC_OFF_VISIBLE": ["tja_raw"],
    "BSD_LEFT_CONTEXT_VISIBLE": ["bsd_raw", "turn_left_raw", "turn_right_raw"],
    "BSD_RIGHT_CONTEXT_VISIBLE": ["bsd_raw", "turn_left_raw", "turn_right_raw"],
    "LEADING_CAR_DEPARTURE_VISIBLE": ["radar_area7_raw", "radar_area8_raw"],
}

CANDIDATE_KEY = {
    "GEAR_P_VISIBLE": "gear_raw",
    "GEAR_R_VISIBLE": "gear_raw",
    "GEAR_N_VISIBLE": "gear_raw",
    "GEAR_D_VISIBLE": "gear_raw",
    "OEM_NORMAL_VISIBLE": "operation_raw",
    "OEM_STANDARD_VISIBLE": "energy_feedback_raw",
    "SNOW_ON_VISIBLE": "road_surface_raw",
    "SNOW_OFF_VISIBLE": "road_surface_raw",
    "AUTOHOLD_SWITCH_ON_VISIBLE": "avh_enable_raw",
    "AUTOHOLD_SWITCH_OFF_VISIBLE": "avh_enable_raw",
    "EPB_HELD_VISIBLE": "epb_raw",
    "EPB_RELEASED_VISIBLE": "epb_raw",
    "ICC_ON_VISIBLE": "tja_raw",
    "ICC_OFF_VISIBLE": "tja_raw",
}


def parse_kv(text: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for part in (text or "").split(";"):
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        out[key.strip()] = value.strip()
    nested = out.get("latest_raw")
    if nested and "=" in nested:
        key, value = nested.split("=", 1)
        out.setdefault(key.strip(), value.strip())
    return out


def row_time(row: Dict[str, Any]) -> int:
    value = row.get("elapsed_realtime_ms")
    if isinstance(value, (int, float)):
        return int(value)
    value = row.get("timestamp_ms")
    if isinstance(value, (int, float)):
        return int(value)
    return -1


def load_rows(path: Path) -> tuple[List[Dict[str, Any]], Dict[str, Any]]:
    identity: Dict[str, Any] = {}
    lines: Iterable[str]
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as zf:
            names = set(zf.namelist())
            if LEDGER_NAME not in names:
                raise SystemExit(f"{LEDGER_NAME} missing from {path}")
            lines = zf.read(LEDGER_NAME).decode("utf-8").splitlines()
            if "build_identity.json" in names:
                identity = json.loads(zf.read("build_identity.json").decode("utf-8"))
    else:
        lines = path.read_text(encoding="utf-8").splitlines()

    rows: List[Dict[str, Any]] = []
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"malformed ledger line {number}: {exc}")
        if isinstance(row, dict):
            rows.append(row)
    return rows, identity


def load_many(paths: List[Path]) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    rows: List[Dict[str, Any]] = []
    identities: List[Dict[str, Any]] = []
    seen_rows = set()
    for path in paths:
        loaded, identity = load_rows(path)
        if identity:
            identities.append(identity)
        for row in loaded:
            dedupe = (
                row.get("diagnostic_session_id"),
                row.get("correlation_id"),
                row.get("event"),
                row.get("elapsed_realtime_ms"),
                row.get("timestamp_ms"),
                row.get("note"),
            )
            if dedupe in seen_rows:
                continue
            seen_rows.add(dedupe)
            rows.append(row)
    return rows, identities


def nearby_autohold(rows: List[Dict[str, Any]], marker: Dict[str, Any], window_ms: int = 1500) -> List[Dict[str, Any]]:
    center = row_time(marker)
    if center < 0:
        return []
    result = []
    for row in rows:
        if row.get("event") != "AUTOHOLD_LIVE_SAMPLE":
            continue
        delta = row_time(row) - center
        if abs(delta) > window_ms:
            continue
        parsed = parse_kv(str(row.get("note") or ""))
        result.append({
            "delta_ms": delta,
            "avh_raw": parsed.get("avh_raw"),
            "avh_enable_raw": parsed.get("avh_enable_raw"),
            "speed_raw": parsed.get("speed_raw"),
            "brake_pedal_raw": parsed.get("brake_pedal_raw"),
            "brake_depth_raw": parsed.get("brake_depth_raw"),
            "accel_depth_raw": parsed.get("accel_depth_raw"),
        })
    result.sort(key=lambda x: x["delta_ms"])
    return result


def evaluate(rows: List[Dict[str, Any]], identities: Optional[List[Dict[str, Any]]] = None) -> Dict[str, Any]:
    observations: List[Dict[str, Any]] = []
    values: Dict[str, Dict[str, List[str]]] = defaultdict(lambda: defaultdict(list))
    values_by_session: Dict[str, Dict[str, Dict[str, List[str]]]] = defaultdict(
        lambda: defaultdict(lambda: defaultdict(list))
    )

    for row in rows:
        if row.get("event") != "OPERATOR_OBSERVATION":
            continue
        parsed = parse_kv(str(row.get("note") or ""))
        marker = parsed.get("observation")
        if marker not in MARKER_KEYS:
            continue

        snapshot = {key: parsed.get(key) for key in MARKER_KEYS[marker]}
        observation = {
            "test_id": row.get("test_id"),
            "correlation_id": row.get("correlation_id"),
            "diagnostic_session_id": row.get("diagnostic_session_id"),
            "elapsed_realtime_ms": row.get("elapsed_realtime_ms"),
            "timestamp_ms": row.get("timestamp_ms"),
            "observation": marker,
            "snapshot": snapshot,
        }
        if marker.startswith("AUTOHOLD_"):
            observation["nearby_live_samples"] = nearby_autohold(rows, row)
        observations.append(observation)

        candidate_key = CANDIDATE_KEY.get(marker)
        value = snapshot.get(candidate_key) if candidate_key else None
        if candidate_key and value not in (None, "", "null", "None"):
            values[marker][candidate_key].append(value)
            session = str(row.get("diagnostic_session_id") or "unknown")
            values_by_session[marker][candidate_key][session].append(value)

    candidates: List[Dict[str, Any]] = []
    for marker, by_key in values.items():
        for key, seen in by_key.items():
            counts = Counter(seen)
            value, count = counts.most_common(1)[0]
            unique = sorted(counts)
            stable = count >= MIN_REPEAT and len(unique) == 1

            session_evidence = []
            matching_sessions = 0
            for session, session_seen in sorted(values_by_session[marker][key].items()):
                session_counts = Counter(session_seen)
                session_value, session_count = session_counts.most_common(1)[0]
                session_unique = sorted(session_counts)
                session_stable = session_count >= MIN_REPEAT and len(session_unique) == 1
                if session_stable and session_value == value:
                    matching_sessions += 1
                session_evidence.append({
                    "diagnostic_session_id": session,
                    "raw_value": session_value,
                    "repeat_count": session_count,
                    "all_values": session_unique,
                    "stable": session_stable,
                })

            if matching_sessions >= MIN_SESSIONS:
                status = "cross_session_candidate_not_verified"
            elif matching_sessions >= 1:
                status = "single_session_candidate_not_verified"
            else:
                status = "need_more_data"

            candidates.append({
                "observation": marker,
                "raw_key": key,
                "raw_value": value,
                "repeat_count": count,
                "all_values": unique,
                "matching_stable_sessions": matching_sessions,
                "session_evidence": session_evidence,
                "status": status,
                "verified": False,
            })

    sessions = sorted({
        str(row.get("diagnostic_session_id"))
        for row in rows if row.get("diagnostic_session_id") not in (None, "")
    })
    return {
        "schema_version": 1,
        "policy": {
            "minimum_matching_operator_markers_per_session": MIN_REPEAT,
            "minimum_matching_diagnostic_sessions": MIN_SESSIONS,
            "automatic_verified_transition_allowed": False,
        },
        "identities": identities or [],
        "diagnostic_sessions": sessions,
        "operator_observations": observations,
        "mapping_candidates": candidates,
        "summary": {
            "ledger_rows": len(rows),
            "operator_observations": len(observations),
            "single_session_candidate_not_verified": sum(
                c["status"] == "single_session_candidate_not_verified" for c in candidates
            ),
            "cross_session_candidate_not_verified": sum(
                c["status"] == "cross_session_candidate_not_verified" for c in candidates
            ),
        },
    }


def self_test() -> None:
    rows = []
    for session in ("S1", "S2"):
        for i in range(3):
            rows.append({
                "schema_version": 3,
                "timestamp_ms": 1000 + i * 1000,
                "elapsed_realtime_ms": 5000 + i * 1000,
                "event": "OPERATOR_OBSERVATION",
                "test_id": "AUD-DRV-002",
                "correlation_id": f"{session}-C{i}",
                "diagnostic_session_id": session,
                "note": "observation=OEM_NORMAL_VISIBLE;latest_raw=operation_raw=3;energy_feedback_raw=1",
            })

    single = evaluate([r for r in rows if r["diagnostic_session_id"] == "S1"])
    candidate = single["mapping_candidates"][0]
    assert candidate["status"] == "single_session_candidate_not_verified", candidate
    assert candidate["matching_stable_sessions"] == 1, candidate
    assert candidate["verified"] is False, candidate

    result = evaluate(rows)
    candidates = result["mapping_candidates"]
    assert len(candidates) == 1, candidates
    candidate = candidates[0]
    assert candidate["raw_key"] == "operation_raw", candidate
    assert candidate["raw_value"] == "3", candidate
    assert candidate["repeat_count"] == 6, candidate
    assert candidate["matching_stable_sessions"] == 2, candidate
    assert candidate["status"] == "cross_session_candidate_not_verified", candidate
    assert candidate["verified"] is False, candidate

    rows[-1]["note"] = "observation=OEM_NORMAL_VISIBLE;latest_raw=operation_raw=4"
    result = evaluate(rows)
    candidate = result["mapping_candidates"][0]
    assert candidate["status"] == "single_session_candidate_not_verified", candidate
    assert candidate["matching_stable_sessions"] == 1, candidate
    assert candidate["verified"] is False, candidate
    print("V1_CORRELATION_EVALUATOR_OK sessions=2 auto_verified=false")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "input", nargs="*",
        help="one or more verification_evidence.jsonl or DolphinV1_Verification_*.zip inputs"
    )
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return
    if not args.input:
        parser.error("at least one input is required unless --self-test is used")

    rows, identities = load_many([Path(p) for p in args.input])
    json.dump(evaluate(rows, identities), sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
