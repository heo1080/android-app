# Dolphin Launcher Master Verification Registry v1

기준 소스: `main@1c60bec51a3983ab505b3d068bd3fbdacc7aa9ca`  
진단 기준: `heo1080/dolphin-diagnostics@d75939f6ed2daacbdf0b6fbabd5d27c0ca18ee31` (2026-09-19 세션)  
기계 판독 원본: [master_registry.json](./master_registry.json)

## 목적

이 문서는 Dolphin Launcher의 단일 검증 기준이다. 모든 기능은 다음 연결을 가져야 한다.

`REQ ID → Feature ID → Test ID → Acceptance Criteria → Required Logs → State → Known-Bad → Dependencies → Real-vehicle Procedure → Evidence → Confidence`

상태는 `VERIFIED / BETA / REVERIFY_REQUIRED / BLOCKED / UNSUPPORTED`만 사용한다. UI가 존재하거나 API가 return success를 반환했다는 이유만으로 VERIFIED로 승격하지 않는다.

## 현재 최우선 결론

| 우선 | Feature | 상태 | 현재 판단 |
|---|---|---|---|
| P0 | SNOW_MODE_VOICE | BETA | 현재 source가 Snow 진입만 음성 callback하고 Snow 이탈은 로그만 남긴다. 사용자 요구사항과 직접 불일치. |
| P0 | BUILD_IDENTITY | REVERIFY_REQUIRED | main의 build.gradle은 3.0.7/versionCode 37인데 최근 release line은 v34.3.1. CI patch chain과 실제 APK identity를 분리 추적해야 한다. |
| P0 | DRIVE_MODE_VOICE | BETA | 실차 NORMAL 음성 누락. raw correlation이 아직 확정되지 않았다. |
| P0 | REGEN_MODE_VOICE | REVERIFY_REQUIRED | STANDARD→ECO 오안내 수정 코드가 있으나 최신 실차 재검증 필요. |
| P0 | SPLIT_SCREEN_TWO_APP | BETA | readback 로직은 존재하지만 실차에서 미작동 보고. dumpsys 기반 실패 trace 필요. |
| P1 | APP_DRAWER_SHORTCUTS | REVERIFY_REQUIRED | 16개 실 launcher slot 구현과 regression guard는 존재. 최신 APK 실차 확인 필요. |
| P1 | BOOT_MULTI_APP_AUTOLAUNCH | REVERIFY_REQUIRED | multi-app loop/Add UI 코드는 존재. 최신 APK에서 Add 버튼 누락 회귀 확인 필요. |
| P1 | IN_APP_TTS_CORE | BETA | 시스템 TTS 지연 queue 회피 구조는 존재. cold-start/연속 미리듣기 실차 latency 검증 필요. |
| P1 | DRIVER_ONLY_APP_OWNED_AUDIO | REVERIFY_REQUIRED | stream14 근거가 있으나 최신 반복 검증 필요. |
| P1 | PER_APP_DRIVER_SPEAKER_ROUTING | BLOCKED | 외부 앱 UID 라우팅은 권한/출력 device/OEM safety arbitration 증거가 부족해 production path 차단. |
| P1 | INTERIOR_LIGHT | BETA | API 성공 반환과 실제 램프 반응이 불일치한 실차 사례가 있어 physical readback 필요. |
| P2 | CLUSTER_TBT | BETA | compact TBT bridge는 존재하나 실차 arbitration/restore 검증 필요. |
| P2 | CLUSTER_FULL_MIRROR_THEME | BLOCKED | display target 및 순정 ADAS 복원 경로 확인 전 write 차단. |
| P2 | SEAT_MEMORY_M1_M3 | BLOCKED | position getter/setter pair가 확인되기 전 모터 구동 금지. |
| P2 | FSD_OBJECT_LANE_MODEL | BLOCKED | 실제 camera/object pipeline 근거 확인 전 객체 생성 금지. |

## Known-Bad Regression Library

1. `KB-001` Drive NORMAL 음성 누락
2. `KB-002` Regen STANDARD를 ECO로 오안내
3. `KB-003` Snow OFF 음성 누락
4. `KB-004` 분할화면 미작동
5. `KB-005` 앱서랍 바로가기 생성 기능 누락
6. `KB-006` 시동 자동실행 여러 앱 Add 버튼 누락
7. `KB-007` TTS 미리듣기 지연/비프 뒤 이전 utterance 재생
8. `KB-008` 실내등 API 성공 반환과 실제 램프 반응 불일치
9. `KB-009` 오디오 API/write 성공과 실제 청취 결과 불일치 가능
10. `KB-010` package/versionCode/build identity 불일치에 따른 업데이트 실패

## 다음 실차 시험 묶음

### AUD-SNOW-001 / AUD-SNOW-002
- 사전조건: 정차, 진단 캡처 ON.
- SNOW OFF baseline → SNOW ON → OFF를 3회 반복.
- `roadSurface`, drive-mode 후보 raw, normalized event, 실제 TTS phrase를 같은 correlation ID로 기록.
- 성공: ON과 OFF가 각각 정확히 한 번 발화.
- 현재 source상 OFF callback이 없으므로 수정 전에는 실패가 예상되는 Known-Bad 재현 시험이다.

### AUD-DRV-001
- ECO → NORMAL → SPORT → NORMAL을 반복.
- Byd_AutoFeedBack/Submit AI가 반응하는 순간과 Dolphin raw 후보를 시간축으로 비교.
- 성공: NORMAL edge를 포함한 모든 모드가 독립 세션에서도 정확히 재현.

### AUD-REG-001
- STANDARD ↔ HIGH 반복.
- 성공: STANDARD를 ECO라고 발화하는 경우 0회.

### WIN-SPLIT-001
- split 지원 후보 앱 2개 선택.
- 명령 결과만 보지 말고 `dumpsys activity activities`와 `am stack list`에서 primary/secondary mode를 확인.
- 실패 시 command/exit/task/stack/windowingMode trace를 보존.

### LCH-DRW-001 / LCH-BOOT-001
- 앱서랍 shortcut 생성 및 시동 자동실행 Add 버튼을 최신 release APK에서 먼저 UI 회귀 확인.
- UI가 존재하면 실제 생성/시동 cycle 시험으로 진행.

## Release Gate 연결

RC/VERIFIED 승격 전에 최소한 다음을 검사한다.

- critical regression = 0
- unresolved contradiction = 0
- 필수 Known-Bad 재시험 통과
- Safety Invariant 통과
- release APK package/version/signature/provenance 확인
- 영향을 받는 Feature/Test ID가 Change Impact Graph에 포함
- 실차 증거가 부족한 기능은 VERIFIED로 올리지 않고 BETA/REVERIFY_REQUIRED/BLOCKED 유지

## 갱신 규칙

1. 최신 GitHub source commit이 바뀌면 source identity를 갱신한다.
2. 새 실차 DiagnosticSession이 오면 연결된 Test ID 결과를 갱신한다.
3. firmware/BYD service/external app 핵심 버전이 바뀌면 관련 VERIFIED 기능만 `REVERIFY_REQUIRED`로 내린다.
4. 요구사항이 새로 추가되면 코드부터 만들지 말고 REQ/Feature/Test ID부터 등록한다.
5. Known-Bad가 수정되면 같은 Test ID의 Negative Test까지 통과해야 닫는다.
6. 실패한 BETA는 무기한 방치하지 않고 계속 연구/BLOCKED/DEPRECATED/제거 후보로 분류한다.
