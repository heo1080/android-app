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
