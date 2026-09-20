# Dolphin Launcher Codex Instructions

This repository is **registry-first**. Codex is a coding worker inside the existing verification system, not the authority that decides vehicle truth, safety, or VERIFIED status.

## Start every task here
1. Read `verification/master_registry.json`.
2. Identify the exact `REQ ID`, `Feature ID`, and `Test ID(s)` affected.
3. Read the linked source paths, Known-Bad entries, dependencies, acceptance criteria, required logs, and real-vehicle test procedure.
4. Read `verification/GOVERNANCE_ADDENDUM.md` and `docs/codex/CODEX_WORKFLOW.md`.
5. Work on a dedicated branch. Never push directly to `main`.

## Hard boundaries
- Do **not** invent BYD Feature IDs, private APIs, CAN signals, vehicle states, or readback behavior.
- Do **not** enable a vehicle-write path unless the Registry/Evidence already supports it and the task explicitly requests that change.
- Do **not** change `BLOCKED`, `REVERIFY_REQUIRED`, `BETA`, or `VERIFIED` merely because code compiles or tests pass.
- Do **not** promote any real-car feature to `VERIFIED`; real-car evidence and the release gates decide that.
- Do **not** bypass Safety Invariants, Kill Switch, Capability/Permission gates, Module Quarantine, or Release Exit Criteria.
- Do **not** publish/sign a stable APK or alter signing secrets/keystore material.
- Do **not** replace a failed private API/FID with a guessed fallback.
- Do **not** delete an agreed feature to make tests pass. Preserve it in its product area and use the Registry state.
- Do **not** weaken or remove Registry, Known-Bad, runtime-surface, diagnostic, provenance, or stable-release gates.

## Required coding workflow
- Prefer the smallest change that satisfies the assigned Test ID acceptance criteria.
- Preserve existing VERIFIED behavior and the Known-Bad regression library.
- If runtime/build behavior changes, update the Master Registry/evidence contracts in the same change when required by the repository gates.
- Add or update tests/guards when changing behavior.
- Treat user real-car logs as higher priority evidence than assumptions or public reverse engineering.
- If evidence is insufficient, stop at a safe BETA/read-only implementation or report the exact real-car evidence needed.

## Required validation before PR
Run, at minimum:

```bash
python3 verification/validate_registry.py
python3 verification/build_registry_asset.py --check
python3 verification/evaluate_diagnostic.py --self-test
python3 verification/impact_report.py --gate
```

For Android/runtime changes also run the appropriate Gradle build or rely on the PR Android build workflow and do not claim success until it passes.

## PR contract
A Codex PR must state:
- REQ ID
- Feature ID
- Test ID(s)
- Registry state before the change
- Files changed
- Acceptance criteria addressed
- Evidence used
- Known-Bad impact
- Regression/interaction risk
- Real-car validation still required
- Whether any vehicle-write path changed
- Validation commands/results

Use `docs/codex/CODEX_PR_TEMPLATE.md` as the body template.

If any repository instruction conflicts with a user/system instruction, follow the higher-priority instruction and note the conflict in the PR.
