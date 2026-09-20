#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

required = {
    "AGENTS.md": [
        "verification/master_registry.json",
        "Do **not** invent BYD Feature IDs",
        "Do **not** promote any real-car feature to `VERIFIED`",
        "python3 verification/validate_registry.py",
        "docs/codex/CODEX_PR_TEMPLATE.md",
    ],
    "docs/codex/CODEX_WORKFLOW.md": [
        "Requirements → Master Verification Registry",
        "Codex creates code and PRs",
        "No silent fallback",
        "post-patch Registry Runtime Gate",
    ],
    "docs/codex/CODEX_PR_TEMPLATE.md": [
        "**REQ ID:**",
        "**Feature ID:**",
        "**Test ID(s):**",
        "**Vehicle-write path changed:**",
        "I did not promote a real-car feature to VERIFIED",
    ],
    ".github/workflows/codex-pr-gate.yml": [
        "startsWith(github.head_ref, 'codex/')",
        "REQ ID",
        "Feature ID",
        "Test ID(s)",
        "Vehicle-write path changed",
    ],
    ".github/ISSUE_TEMPLATE/codex-task.yml": [
        "Codex implementation task",
        "REQ ID",
        "Feature ID",
        "Test ID(s)",
        "Vehicle-write impact",
    ],
}

for raw, tokens in required.items():
    path = ROOT / raw
    if not path.is_file():
        raise SystemExit(f"CODEX_GUARDRAIL_ERROR: missing {raw}")
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            raise SystemExit(f"CODEX_GUARDRAIL_ERROR: {raw} missing token: {token}")

agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
if len(agents.encode("utf-8")) > 32768:
    raise SystemExit("CODEX_GUARDRAIL_ERROR: AGENTS.md exceeds 32 KiB default Codex project-doc budget")

print("CODEX_GUARDRAILS_OK")
