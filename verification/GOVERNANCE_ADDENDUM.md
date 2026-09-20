# Dolphin Launcher Governance Addendum — Registry-first

이 파일은 기존 개발 원칙을 대체하지 않는다. Master Verification Registry를 실제 개발 흐름의 단일 검증 기준으로 사용하기 위한 추가 규칙이다.

## Registry-first

모든 신규 기능/버그 수정은 먼저 `verification/master_registry.json`에 REQ/Feature/Test ID를 등록한다. 코드 변경 후에는 source path, Acceptance Criteria, required logs, Known-Bad, dependencies, 실차 절차와 상태를 갱신한다. 자동 연구와 빌드는 이 파일과 최신 실차 진단을 대조해야 한다.

## 추가 보호 규칙

- Secret은 Android Keystore 등 안전한 저장소에 두고 평문 로그/백업에 넣지 않는다.
- APK/중요 설정/Registry/Evidence snapshot 변조를 감지하면 민감 차량 write를 차단한다.
- VERIFIED source/tag/artifact는 저장소 장애에도 복구할 수 있게 별도 archive와 Bootstrap Manifest로 보존한다.
- Feature Registry/UI/코드/릴리스 노트가 어긋나는 Documentation Drift를 검사한다.
- Diagnostic/Test/Evidence 데이터는 schema version을 가진다.
- 전원 차단/storage full/DB 손상에 대한 corruption recovery를 시험한다.
- Camera/Microphone은 필요한 순간만 사용하며 불필요한 원본 저장을 금지한다.
- 원격 update server 신뢰와 APK/signature 신뢰는 분리한다.
- 외부 telemetry는 local-first, explicit opt-in을 기본으로 한다.
- BYD private API/역공학/타사 코드/OSS는 기술 근거와 코드 사용권을 분리한다.
- hardware revision이 처음 보는 값이면 passive/read-only가 기본이며 write/BETA는 quarantine한다.
- Cross-feature interaction은 위험기반/pairwise로 시험한다.
- Field failure는 당시 APK fingerprint/환경/trace를 묶어 replay package로 남긴다.
- Critical safety/signing/update/data-corruption/repeated-crash 발견 시 RC는 kill하고 last-known-good를 유지한다.
- 실패한 실험/BETA는 계속 연구/BLOCKED/DEPRECATED/제거 후보로 정기 정리한다.

## 빌드 판단

근거가 부족하면 코드를 더 바꾸는 것이 성공이 아니다. 필요한 Test ID, 로그 필드, 재현 절차, 기대 관찰값을 생성하고 `REAL_VEHICLE_DATA_REQUIRED`로 넘기는 것도 정상적인 완료 결과다.


## Runtime registry integration

- `verification/master_registry.json`이 canonical source다.
- `app/src/main/assets/verification_registry.json`은 차량 Verification Center용 동기화 사본이며 CI가 drift를 차단한다.
- `verification/test_log_contracts.json`은 Test ID별 runtime evidence와 자동 판정 계약이다.
- `verification/runtime_surfaces.json`은 앱서랍, 자동실행 Add 버튼, 분할화면, TTS 등 핵심 runtime/UI surface가 사라지는 회귀를 차단한다.
- `VerificationCenterActivity`에서 기록한 PASS/FAIL/INTERMITTENT/DELAYED는 `VERIFY_RESULT` 로그로 진단 ZIP에 포함된다.
- `verification/evaluate_diagnostic.py`는 진단 ZIP을 PASS/FAIL/INCONCLUSIVE/NEED_MORE_DATA로 보수적으로 판정한다.
- `verification/feature_dependencies.json`의 하위 기능이 BLOCKED/REVERIFY_REQUIRED인 경우 상위 VERIFIED 승격을 dependency gate에서 차단한다.
- Registry schema가 바뀌면 `schema_history`와 app asset을 함께 갱신한다.
