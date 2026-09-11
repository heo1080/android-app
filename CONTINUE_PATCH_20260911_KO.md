# DolphinAssistant v30 이어서 수정본 — 2026-09-11

## 진단에서 확인된 핵심 원인
- DiLink3.0 / Android 10(SDK 29)에서 앱 프로세스 UID 10086은 BYD 차량 API 권한을 받지 못하고 있음.
- 좌석 열선/핸들 열선은 `BYDAUTO_SETTING_COMMON`, 성에제거는 `BYDAUTO_AC_COMMON`, 시동은 `BYDAUTO_BODYWORK_COMMON`, 속도/기어/ADAS/계기판도 각각 BYD 권한에서 `SecurityException` 발생.
- 로컬 ADB 127.0.0.1:5555 연결 자체는 성공했지만, 기존 로그에는 첫 shell 실행 결과가 없어서 권한 자동부여 루틴이 shell 단계에서 멈췄을 가능성이 큼.

## 이번 수정
1. `NativeAdbClient.executeShell()`에 4초 타임아웃 추가.
   - shell 명령이 응답하지 않아 앱 권한 루틴 전체가 멈추는 현상 방지.
   - exit code와 최대 240자 출력 로그 기록.
2. `AdbPermissionManager` 권한 부여 흐름 개편.
   - 먼저 `id`로 shell 채널 자체를 검증.
   - WRITE_SECURE_SETTINGS 및 BYD 차량 권한들을 하나씩 `pm grant` 시도.
   - 하나가 실패해도 다음 권한 및 appops/알림 리스너 명령 계속 실행.
   - 성공/실패 개수와 실제 앱 프로세스의 BYD 권한 승인 개수를 로그에 남김.
3. Manifest에 누락돼 있던 `android.permission.BYDAUTO_INSTRUMENT_COMMON` 추가.

## 다음 차량 테스트에서 꼭 볼 로그
- `[LOCAL_ADB] shell exit=...: id`
- `[AdbPermissionManager] grant 성공:` 또는 `grant 실패:`
- `[AdbPermissionManager] ADB 권한 진단 완료: ... bydGranted=x/21`

### 판정
- `pm grant`가 BYD 권한에 대해 성공하고 `bydGranted`가 증가하면 기존 차량 API 기능을 그대로 살릴 수 있음.
- `not a changeable permission type`, `Operation not allowed`, `signature` 류로 전부 실패하면 BYD 권한은 플랫폼 서명/priv-app 계층이라 일반 설치 APK로 직접 취득 불가. 그 경우 다음 단계는 차량의 시스템앱/브로드캐스트/서비스 Binder 경로 또는 허용된 시스템 중계 API를 진단하는 방향으로 전환.

## 빌드 검증 상태
- Manifest XML 파싱 및 수정 Kotlin 파일의 괄호/중괄호 구조 검증 완료.
- 현재 작업 환경은 Gradle 8.7 다운로드를 위한 외부 네트워크가 차단되어 `assembleDebug` 전체 빌드 검증은 수행 불가.
