# BYD Dolphin Auto Assistant v30.2 — 운전석 오디오 경로 실차 비교

버전: `3.0.3-v30.2-driver-audio-probe` / versionCode `33`
기준 소스: v30.1.1 neon build-fix

## 이번 판단

기존 v30.1.1을 먼저 설치해서 한 번 더 확인하기보다는 **지금까지 확인한 오디오 경로를 비교할 수 있도록 소스를 한 번 더 수정한 뒤, 그 빌드 하나로 실차 테스트하는 편이 낫다**고 판단했습니다.

이유는 기존 빌드가 Android 표준 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 한 경로 중심이라, 소리가 전체 스피커에서 나왔을 때 "usage가 틀린 것인지 / BYD 전용 stream이 필요한지 / 재생 엔진 차이인지"를 한 번의 설치로 구분하기 어렵기 때문입니다.

## 조사 결과를 반영한 4개 후보

1. **표준 NAV AudioTrack / SONIFICATION / 44.1 kHz**
   - Android `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 사용.
   - BYD DiLink 3 추출 오디오 설정에서 NAV guidance 출력은 일반 주 오디오 I2S와 별도의 `QUAT_MI2S_RX` 경로가 확인된 공개 역공학 자료를 기준으로 함.

2. **커뮤니티 NAV SoundPool / SPEECH**
   - `PoorGrammerA/BydAudioFeedback`의 실제 구현과 같은 방식인 `SoundPool + USAGE_ASSISTANCE_NAVIGATION_GUIDANCE + CONTENT_TYPE_SPEECH`를 재현.
   - 단순히 AudioTrack의 content type만 바꾸는 대신, 해당 프로젝트와 동일한 재생 엔진까지 비교하도록 구성.

3. **BYD 후보 legacy-attribute stream 14**
   - `AudioAttributes.Builder.setLegacyStreamType(14)`가 차량 커스텀 프레임워크에서 유효한지 런타임에 직접 확인.
   - Android 공식 NAV usage 값은 12이므로 stream 14는 **공식 Android NAV 상수가 아니라 BYD/커스텀 후보**로만 취급.

4. **레거시 AudioTrack stream 14**
   - 구형 `AudioTrack(streamType, ...)` 생성자에 streamType 14를 직접 넣어 차량 프레임워크가 별도 스트림으로 받아들이는지 확인.

## 테스트 방법

차량은 **정차(P) 상태**에서 테스트하는 것을 권장합니다. 앱의 안전/안내음 화면에서 **`🔊 운전석 오디오 4경로 비교 테스트`**를 누른 뒤 **`전체 4경로 순차 비교 (추천)`**을 선택합니다.

- 1번 경로 = 비프 1회
- 2번 경로 = 비프 2회
- 3번 경로 = 비프 3회
- 4번 경로 = 비프 4회

각 그룹 사이에는 구분용 간격이 있습니다. **운전석에서만 들리는 그룹이 있는지, 아니면 전 좌석에서 들리는지, 혹은 아예 무음인 경로가 있는지**를 기록해 주세요.

테스트 직후 앱의 원터치 진단 기능으로 ZIP을 내보내면 `AUDIO_PROBE` 로그에 다음 정보가 남습니다.

- 후보 경로 시작/종료 및 비프 횟수
- AudioTrack 세션/상태/샘플레이트/버퍼
- Android가 보고하는 실제 `routedDevice` / `preferredDevice`
- 장치의 출력 디바이스 목록
- stream 14의 볼륨 스트림 인식 여부
- legacy attribute 14 생성 성공/실패 및 예외
- SoundPool NAV/SPEECH 로드/재생 성공 여부

## 중요한 점

이번 버전은 **진단 빌드**입니다. 운전석 전용으로 확인된 경로가 나오기 전까지 일반 TTS, BSD, 차선 경고의 기본 출력을 stream 14로 강제 변경하지 않았습니다. 따라서 잘못된 후보 때문에 기존 안내음 전체가 사라지는 위험을 줄였습니다.

실차 결과가 나오면 그 번호를 기본 라우팅으로 고정하고, TTS·BSD·차선이탈·전방차량출발 등 원하는 음성/경고 출력 전체에 같은 경로를 적용하는 다음 버전을 만들 수 있습니다.

## 참고한 공개 구현/자료

- `PoorGrammerA/BydAudioFeedback` — `BydAutoService.initSoundPool()`에서 NAVIGATION_GUIDANCE + SPEECH SoundPool 사용
- `wheregoes/byd-dolphin-hacking` — DiLink 3 오디오 설정/테스트 자료에서 NAV guidance용 `QUAT_MI2S_RX`와 표준 Android navigation guidance AudioTrack 실험 확인

## 빌드 검증 상태

정적 검증은 통과했습니다. XML 파싱, Kotlin 기본 구조, `R.id`/`R.raw` 참조, v30.1.1 permission override 회귀 여부를 점검했습니다.

이 작업 환경에서는 Gradle wrapper가 `services.gradle.org`에 접근할 수 없어 전체 `assembleDebug`는 실행되지 않았습니다. 따라서 실제 APK 생성은 기존과 마찬가지로 인터넷 가능한 Android 빌드 환경/GitHub Actions에서 최종 확인해야 합니다.
