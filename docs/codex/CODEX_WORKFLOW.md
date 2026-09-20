# Codex Workflow for Dolphin Launcher

Codex is intentionally positioned as a **subordinate implementation agent**. The authoritative system remains:

`Requirements → Master Verification Registry → Evidence → Test IDs → CI/Build Gates → Real-car Validation → Release Gate`

## 1. Task intake

Every coding task must be traceable to existing Registry entries before implementation begins.

Required task fields:

- REQ ID
- Feature ID
- Test ID(s)
- Goal
- Current Registry state
- Acceptance criteria
- Known-Bad links
- Evidence available
- Real-car evidence still missing
- Vehicle-write impact: NONE / READ_ONLY / WRITE_PATH_CHANGED

If the task cannot be mapped to a Registry entry, Codex should add a **registry proposal** rather than silently implementing an untracked feature.

## 2. Branch and scope

Use a dedicated branch, preferably:

`codex/<feature-id-lowercase>/<short-task-name>`

Examples:

- `codex/snow-mode-voice/snow-off-edge`
- `codex/split-screen/readback-diagnostics`

Codex must not push directly to `main`.

Keep the patch narrow. Large refactors and feature behavior changes should not be mixed unless the task explicitly requires both.

## 3. Vehicle/private API rules

For BYD private APIs, Feature IDs, hidden services, Binder paths, display/cluster paths, audio routing, seat/mirror/interior-light writes, and ADAS/FSD inputs:

- Never infer an unsupported call from a similar model or community post.
- Prefer passive/read-only probing before any write.
- Preserve raw input and readback evidence.
- A build success is not vehicle validation.
- If evidence is ambiguous, keep the feature BETA/BLOCKED/REVERIFY_REQUIRED as appropriate.
- No silent fallback to another Feature ID/API.

## 4. Registry-first change policy

A code change is incomplete when its Registry impact is missing.

Before PR:
- Verify linked source paths still exist.
- Update Test ID log contracts when event/result semantics change.
- Update runtime surface sentinels when stable implementation anchors legitimately change.
- Update dependency edges when a feature gains/loses a subsystem dependency.
- Do not manually mark a feature VERIFIED without qualifying real-car evidence.

## 5. Evidence policy

Codex may use:
1. User real-car diagnostics.
2. Reproduced same-car/same-firmware evidence.
3. Current project source and CI output.
4. Public source/reverse-engineering references.

Higher layers override lower-confidence assumptions.

When root cause is not confirmed, use the project confidence vocabulary rather than presenting a guess as fact.

## 6. Required validation

Always run or confirm:

- `python3 verification/validate_registry.py`
- `python3 verification/build_registry_asset.py --check`
- `python3 verification/evaluate_diagnostic.py --self-test`
- `python3 verification/impact_report.py --gate`

Runtime Android changes also require the Android PR build, including post-patch Registry Runtime Gate and APK surface validation.

## 7. PR boundary

Codex creates code and PRs. It does not decide:
- real-car VERIFIED promotion,
- P0 Known-Bad closure without evidence,
- stable release publication,
- bypass of release/safety gates.

PRs should be ready for the existing Registry/CI process to judge.
