# v30.3 수정 근거 및 실차 테스트

## 이번 실차 로그에서 확정된 내용
- 설치/실행 버전: `3.0.3-v30.2-driver-audio-probe (33)`
- TTS는 `status=-1`로 초기화 실패하여 모든 TTS 요청이 실제 재생 단계 전에 생략되었습니다.
- 기존 NAV 비프는 AudioFocus 획득과 AudioTrack.write() 자체는 성공했지만 사용자가 실제 소리를 듣지 못했습니다. 기존 경고 비프는 16 kHz mono였습니다.
- 차량이 노출한 출력 장치는 `type=1(Earpiece)`, `type=2(Built-in Speaker)`, `type=18(Telephony)` 세 종류였습니다.
- BYD 권한 wrapper는 읽기/일부 제어에 실제 효과가 있습니다. 로그에서 핸들 열선 상태 확인, 시트 열선 set 명령 성공 및 level=1 readback이 확인되었습니다.

## v30.3 변경
1. NAV 경고 비프를 16 kHz mono에서 **48 kHz stereo**로 변경.
2. 7경로 비교를 추가/교체:
   - 1: NAV 48k stereo 자동
   - 2: NAV 48k stereo + Earpiece(type=1) 직접 지정
   - 3: NAV 48k stereo + Telephony(type=18) 직접 지정
   - 4: NAV 48k stereo + Speaker(type=2) 직접 지정 (대조)
   - 5: MEDIA 48k stereo + Earpiece(type=1) 직접 지정
   - 6: SoundPool + NAV/SPEECH
   - 7: legacy stream 14
3. 각 AudioTrack에 대해 `setPreferredDevice()` 반환값과 실제 `routedDevice`를 로그에 남깁니다.
4. Android 기본 TTS가 실패하면 설치된 TTS service package를 열거해 명시적 엔진으로 순차 재시도합니다.
5. 모든 TTS 엔진이 실패하면 예전처럼 완전 무음으로 끝내지 않고 48k NAV 비프를 fallback으로 냅니다.
6. 메인 안전 경고 카드와 서브화면에 v30.3/7경로 표시를 넣어 설치 버전 혼선을 줄였습니다.

## 테스트 순서
정차 상태에서 `안전 경고 & 스피커` → `v30.3 운전석 오디오 7경로 비교 테스트` → `전체 7경로 순차 비교`를 실행합니다.
각 번호는 번호만큼 비프를 냅니다. 운전석에서만 들린 번호, 전체 스피커에서 들린 번호, 무음 번호를 기록한 뒤 새 진단 ZIP을 내보냅니다.
