## Codex Task Traceability

**REQ ID:**  
**Feature ID:**  
**Test ID(s):**  
**Registry state before change:**  

## Goal

Describe the narrowly scoped implementation goal.

## Evidence used

- Real-car evidence:
- Source/CI evidence:
- Public/reference evidence:

## Code changes

- 

## Acceptance criteria addressed

- 

## Known-Bad / dependency impact

**Known-Bad IDs:**  
**Dependency impact:**  
**Cross-feature regression risk:**  

## Vehicle safety boundary

**Vehicle-write path changed:** NONE / READ_ONLY / WRITE_PATH_CHANGED  
**Private API / Feature ID changed:** YES / NO  
**Real-car validation still required:** YES / NO  

If YES, list the exact Test ID procedure/evidence needed:

- 

## Validation

- [ ] `python3 verification/validate_registry.py`
- [ ] `python3 verification/build_registry_asset.py --check`
- [ ] `python3 verification/evaluate_diagnostic.py --self-test`
- [ ] `python3 verification/impact_report.py --gate`
- [ ] Android PR build passed when runtime/build inputs changed
- [ ] Post-patch Registry Runtime Gate passed
- [ ] APK Registry surface validation passed when an APK was built

## Agent statement

I did not promote a real-car feature to VERIFIED, bypass a safety/release gate, invent a BYD API/FID, or publish a stable APK.
