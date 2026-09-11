# BYD Dolphin Auto Assistant v30 변경 기록

## 차량 연동 정확도

- 제공된 DiLink 3 `framework.jar`에서 확인한 클래스·메서드·상수만 차량 상태 경로에 남겼습니다.
- 시동 감지는 `BYDAutoBodyworkDevice.getPowerLevel()`을 연속 확인하도록 바꾸고, 한 시동 주기에 자동 실행이 중복되지 않도록 했습니다.
- 속도, 기어, EPB, 충전 상태를 각각 검증된 getter로 폴링하도록 통합했습니다.
- AVH, BSD, 차선 오프셋, TJA, 주행 인터페이스, 에너지 피드백은 원시값 전환만 진단 로그에 기록합니다. 실차 값의 의미가 확인되기 전에는 자동 동작이나 음성을 연결하지 않습니다.
- 속도 API 표본을 읽지 못한 경우 0 km/h로 간주하지 않고 실험적 주차센서 감지를 즉시 해제합니다.
- 비상등은 공개 getter만 확인되어 읽기 전용으로 변경했습니다. ON/OFF/토글 명령은 모두 차단합니다.
- 확인되지 않은 시트 위치, 미러, 창문 제어와 추정 브로드캐스트를 제거했습니다.

## 내비게이션·계기판·HUD

- 계기판 연동은 `sendAutoNaviStatus()`, `sendSimpleGuidanceInfo()`, `sendNextPathName()` 기반의 작은 TBT 정보만 제공합니다.
- 방향 코드를 DiLink 상수 범위로 제한하고 거리와 다음 도로명 입력을 검증합니다.
- 여러 내비 알림이 동시에 존재할 때 마지막 활성 안내가 사라지기 전에는 계기판을 지우지 않도록 했습니다.
- 전체화면 계기판 미러링은 범위에서 제외했습니다.
- 사용자 제공 설명서와 본체 라벨로 장비의 정식 표기와 모델을 `TMAP Plus HUD / T900`으로 확정했습니다. 제조사는 주식회사 인포라텍, KC 등록은 `R-R-9IT-JARVIS3000`, 라벨 문구는 `Connected to TMHP`입니다.
- USB-C 전원, 본체 스피커, 반사필름 및 속도·방향·단속구간 표시 지원은 확인했지만 Bluetooth UUID·펌웨어·패킷 형식은 아직 확인되지 않았습니다. 미확인 프레임은 모두 제거했으며 UUID/SPP 진단만 수행하고 송신은 잠급니다.
- 내비 음성은 Android의 내비 안내 용도로 출력하고, 존재하지 않는 물리 스피커 라우팅 성공을 표시하지 않습니다.

## 화면·자동화

- 3·4분할 관련 숨은 경로를 제거하고 서로 다른 두 앱의 2분할만 지원합니다.
- 분할 비율은 20~80%에서 저장·복원하며 AVM 종료 뒤 마지막 구성을 복원합니다.
- 앱별 시동 실행 지연과 앱별 미디어 재생 여부·지연을 각각 저장합니다.
- 플로팅 독은 실제 런처 앱과 실제 아이콘을 사용하고, 한 화면 8개 이후 가로 스크롤되도록 정리했습니다.
- 고정 바로가기에 설치별 임의 토큰 검증을 추가했습니다. v29에서 만든 고정 바로가기는 v30에서 다시 만들어야 합니다.

## 권한·진단·안전 기본값

- Android 13 이상의 알림 권한 흐름과 Android 14의 포그라운드 서비스 유형을 반영했습니다.
- AGP 8에서도 진단 보고서의 버전 정보가 생성되도록 `BuildConfig` 생성을 명시했습니다.
- 첫 GitHub Actions 빌드에서 확인된 권한 플래그 컴파일 오류 2건을 수정했습니다. nullable `IntArray`는 빈 배열로 안전하게 처리하고, 승인 플래그 상수는 실제 선언 클래스인 `PackageInfo.REQUESTED_PERMISSION_GRANTED`를 사용합니다.
- 두 번째 GitHub Actions 빌드에서 Kotlin·Java 컴파일과 DEX 생성이 통과한 것을 확인했습니다. 이후 `dadb`의 전이 JUnit 모듈 9개가 동일한 `META-INF/LICENSE.md`를 포함해 자원 병합이 실패한 문제를 `packaging.resources.excludes`로 해결했으며, 연속 충돌을 막기 위해 `META-INF/LICENSE-notice.md`도 함께 처리했습니다.
- 차량 권한·API probe·원시 상태를 하나의 `DolphinAssistant_v30_Diagnostic.txt`로 내보내도록 확장했습니다.
- 15분 원터치 진단 세션을 추가했습니다. 앱 로그, 차량 원시값 전환, Bluetooth 본딩·ACL·UUID 이벤트, 페어링 기기 스냅샷, 런타임 BYD 메서드 목록을 구조화해 ZIP 하나로 내보냅니다.
- 세션 도중 **문제 순간 표시**를 남길 수 있으며 앱 프로세스가 종료된 세션도 다음 실행에서 복구합니다.
- 내비 알림 원문은 기본 마스킹하고, 사용자가 허용해도 활성 진단 세션 동안만 기록합니다.
- Bluetooth 진단 로그의 장치 주소는 앞 3바이트를 자동 마스킹합니다.
- DADB는 사용자가 승인한 로컬 ADB에만 연결하며 임의 IP 검색을 제거했습니다.
- 계기판 TBT, 시동 자동 실행, 실험적 전방 출발 후보 감지는 기본 OFF입니다.
- 접근성 서비스는 알림 텍스트 보조 수집에 필요한 최소 설정으로 축소했습니다.

## 호환성 주의

- BYD 차량 권한은 일반 Android 권한 대화상자만으로 허용되지 않을 수 있으며, 차량 펌웨어의 서명·화이트리스트 정책에 좌우됩니다.
- 두 번째 GitHub Actions 실행으로 첫 오류 2건의 해결과 Kotlin·Java·DEX 단계 통과가 확인됐습니다. 현재 소스에는 이후 발견된 Java 리소스 충돌 수정까지 반영되어 정적 검사를 다시 통과했습니다. 현재 작업 환경에서는 Gradle 배포 파일을 내려받을 수 없으므로 최종 APK 조립 여부는 GitHub Actions 재실행으로 확인해야 합니다. 자세한 내용은 `VERIFICATION_REPORT_v30_KO.md`를 확인하십시오.

## 2026-09-12 v30.1.1 build fix
- `BydPermissionContext.kt`의 Android Context permission override 시그니처 수정
- `permission: String?` → `permission: String` 4개 메서드
- GitHub Actions `compileDebugKotlin`의 `overrides nothing` / nullable type mismatch 오류 대응
- versionCode 32, versionName `3.0.2-v30.1.1-neon-buildfix`
## 2026-09-12 v30.2 driver audio probe
- 운전석 전용 오디오 경로를 실차에서 한 번에 비교할 수 있는 4경로 진단 추가
- 1번: `AudioTrack` + `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + SONIFICATION, 44.1 kHz
- 2번: 커뮤니티 `BydAudioFeedback` 방식과 동일한 `SoundPool` + NAVIGATION_GUIDANCE + SPEECH
- 3번: BYD 커스텀 프레임워크 가능성을 확인하기 위한 `AudioAttributes.setLegacyStreamType(14)` 후보
- 4번: 레거시 `AudioTrack(streamType=14)` 후보
- 각 경로 번호만큼 비프를 출력해 화면을 보지 않고 경로를 구분하고, 출력 장치·세션·버퍼·stream 14 인식 여부를 `AUDIO_PROBE` 로그에 기록
- 일반 TTS/BSD/차선 경고의 기본 경로는 실차 결과가 확인되기 전까지 표준 NAVIGATION_GUIDANCE로 유지
- versionCode 33, versionName `3.0.3-v30.2-driver-audio-probe`

