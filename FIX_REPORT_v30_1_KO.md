# BYD Dolphin Auto Assistant v30.1 기능 복구 수정 보고서

버전: `3.0.1-v30.1-neon` / versionCode 31  
기준 진단: `DolphinAssistant_v30_DiagnosticSession_20260912_013813_600.zip`

## 이번 진단에서 확인된 핵심 원인

- 시트 열선, 핸들 열선, 성에 제거, 실내등, 계기판 TBT 등 BYD API가 `BYDAUTO_*_GET/SET` 권한 검사에서 `SecurityException`으로 중단되고 있었습니다. 해당 GET/SET 권한은 차량에서 `pm grant`로 변경 가능한 런타임 권한이 아닙니다.
- HUD는 `huddata` SPP 연결 자체는 성립했지만, 이전 v30 소스에서 HUD 프로토콜 확인 함수가 항상 false였고 데이터/오디오 기본값도 꺼져 있어 밝기·길안내·사운드 송신 경로가 실제로 잠겨 있었습니다.
- 기존 바로가기 빌더는 BYD 순정 런처가 막는 `requestPinShortcut()` 기반 홈 화면 바로가기를 계속 사용했습니다.
- 시동 자동실행은 BYD power getter 권한 문제와 Android 10 백그라운드 Activity 실행 제한 영향을 함께 받고 있었습니다.
- 차량 기본 density는 160이며, density 값을 180/200으로 올리면 UI가 커지는 것이 정상 동작입니다.

## 적용한 수정

### 1. BYD 차량 제어 브리지
- `BydPermissionContext` 추가.
- BYD 프레임워크의 `getInstance(Context)`에 전달되는 Context에서 `android.permission.BYDAUTO_*` 클라이언트 권한 검사만 좁게 우회하도록 변경.
- 시트/핸들 열선, 공조·성에 제거, 실내등, 비상등, 시동 상태, 레이더, 계기판, 차량 텔레메트리, 진단 getter 경로를 동일 브리지로 통일.
- `pm grant`는 실제 변경 가능한 COMMON 계열만 시도하도록 정리하고 GET/SET 반복 실패를 제거.

### 2. 앱서랍 바로가기
- 홈 화면 pin shortcut 생성 경로 제거.
- `MAIN + LAUNCHER` Activity 16개를 실제 앱서랍 슬롯으로 추가.
- 사용자가 빌더에서 **앱서랍 바로가기 만들기**를 선택하면 빈 슬롯을 활성화하고 선택한 앱/차량 동작을 저장하도록 변경.
- BYD 런처 특성상 런타임으로 앱 이름/아이콘을 임의 변경할 수 없어 커스텀 슬롯 라벨은 `커스텀 바로가기 01~16`으로 고정되며, 실행 동작은 사용자가 고른 항목으로 연결됩니다.

### 3. TMAP Plus HUD / T900
- 이전의 프로토콜 송신 잠금 제거.
- 진단에서 관찰된 `Hudaudio`와 UUID `fe010000-1234-5678-abcd-00805f9b34fb`, 표준 SPP UUID를 모두 연결 후보로 사용.
- 프로젝트에 저장되어 있던 16바이트 T900 프레임 규격을 **실험 브리지**로 구현:
  - Header `AA 55`
  - Cmd `01` 길안내, `02` 오디오, `03` 밝기
  - byte 15 = byte 0~14 XOR checksum
- HUD 연결 시 시험 길안내 프레임을 보낼 수 있고 모든 송신 패킷을 HEX로 진단 로그에 남김.
- 밝기/오디오 UI를 더 이상 비활성화하지 않음.

> 중요: 이 프레임의 명령/필드 정의는 프로젝트 기록에 근거하지만 사용 중인 T900 펌웨어에서 엔디언/세부 응답 규칙까지 실차 확정된 것은 아닙니다. 이번 빌드에서는 “아예 송신하지 않던 상태”를 끝내고 실제 패킷을 전송하도록 했습니다. HUD가 여전히 표시하지 않으면 다음 진단 ZIP의 `T900 packet tx=...`를 기준으로 바이트 순서/명령을 조정할 수 있습니다.

### 4. 계기판 TBT / TMAP 파서
- 계기판 BYD API도 `BydPermissionContext`를 사용하도록 변경.
- TMAP 알림에서 title/text만 보던 방식을 버리고 notification extras의 모든 CharSequence/배열/목록 텍스트를 수집하도록 확대.
- `좌회전/좌측/왼쪽`, `우회전/우측/오른쪽`, `직진`, `U턴` 표현을 모두 인식하도록 파서 확대.
- 알림 extras 키/타입도 진단 로그에 남겨 다음 실차 테스트에서 파싱 누락 원인을 확인 가능.

### 5. 맞춤 음성·안전 경고 오디오
- TTS와 경고 비프를 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 오디오 usage로 통일.
- ToneGenerator 대신 AudioTrack PCM 비프를 사용해 내비게이션 오디오 정책을 타도록 변경.
- 오디오 포커스 획득 결과, TTS 시작/완료/오류, 출력 장치 정보를 진단 로그에 기록.
- 설정 화면에 **운전석 / 내비게이션 오디오 경로 테스트** 버튼 추가.

> 차량 오디오 정책이 이 usage를 운전석 전용 채널로 매핑하는지는 실차 정책에 달려 있으므로, “운전석 전용” 물리 채널까지 소스만으로 보장하지는 않습니다. 이번 수정은 일반 알림/미디어 스트림이 아니라 BYD 순정 내비게이션이 사용할 수 있는 navigation-guidance 경로를 선택하도록 바꾼 것입니다.

### 6. 부팅/시동 자동 실행
- 시동 power getter를 BYD 권한 브리지로 수정.
- Android 10 백그라운드 실행 제한을 피하기 위해 차량의 로컬 ADB `127.0.0.1:5555`가 열려 있으면 `monkey -p <package> ...`로 먼저 실행하고, 실패 시 기존 startActivity로 fallback.
- BOOT_COMPLETED/QUICKBOOT/MY_PACKAGE_REPLACED 외 SCREEN_ON/USER_PRESENT도 새 시동 주기 재확인 대상으로 반영.
- 앱별 지연 및 미디어 재생은 기존 설정을 유지.

### 7. DPI
- 프리셋을 `120 / 140 / 160(순정)`으로 변경.
- 수동 입력 120~640 추가.
- 160보다 낮은 값은 화면 요소를 작게 만들어 더 많은 내용을 표시한다는 안내 추가.

### 8. 네온 UI
- 메인 타이틀을 `NEON DRIVE // DOLPHIN`으로 변경.
- 메인 배경을 블랙/네이비/퍼플 그라데이션으로 재구성.
- 카드에 2dp 시안 네온 테두리, 헤더에 시안→퍼플→핑크 네온 라인 적용.
- 버튼/배지/동적 행 배경도 기존 회색 계열에서 시안·딥블루 네온 계열로 교체.

## 정적 검증

- Android XML: 26/26 파싱 성공 (Manifest 포함)
- Kotlin 소스: 41/41 문자열·주석·괄호 구조 검증 성공
- `R.id` 참조: 127개 / XML 선언 128개 / 누락 0개
- 앱서랍 슬롯: Manifest 16개 / Activity 클래스 16개 일치
- 예전 `180/200 DPI` 버튼 참조: 0개
- 예전 홈 화면 바로가기 문구/경로: 제거

## APK 빌드 상태

현재 작업 환경에는 Gradle 8.7 배포본 캐시가 없고 외부 네트워크가 차단되어 `./gradlew :app:assembleDebug`가 Android 컴파일 전에 `services.gradle.org` DNS 단계에서 중단됐습니다. 따라서 이 패키지에는 새 APK를 거짓으로 포함하지 않았습니다.

소스 안의 `.github/workflows/build.yml`은 그대로 포함되어 있으므로 인터넷 가능한 GitHub Actions 또는 JDK 17 + Android SDK 34 환경에서 `./gradlew :app:assembleDebug`로 APK를 만들 수 있습니다.

## 실차에서 우선 확인할 순서

1. 앱 실행 후 차량 제어에서 핸들 열선 → 운전석 열선 → 성에 제거 → 실내등 ON을 P단 정차 상태에서 각각 시험.
2. HUDDATA 연결 후 밝기 값을 변경하고 HUD 시험 데이터를 송신.
3. TMAP 순정/Android TMAP의 실제 길안내를 1회 시작해 HUD와 계기판 TBT 확인.
4. 안전 경고 화면의 **운전석 / 내비게이션 오디오 경로 테스트** 실행.
5. 앱서랍 바로가기 하나를 만든 뒤 BYD 앱서랍에서 `커스텀 바로가기 01`이 나타나고 선택 동작이 실행되는지 확인.
6. DPI 140 → 120 순서로 확인하고 필요하면 수동 입력.
7. 시동 자동 실행 앱 하나를 활성화한 상태에서 전원 주기를 다시 시작.
8. 실패한 기능이 있으면 즉시 **문제 순간 표시** 후 진단 ZIP을 내보내면 이번 버전은 BYD 호출 결과, T900 HEX 송신, 오디오 경로, TMAP extras, 부팅 실행 방식을 더 구체적으로 기록합니다.
