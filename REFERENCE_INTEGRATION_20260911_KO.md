# BYD 공개 연구자료 통합 패치 (2026-09-11)

## 참고한 자료
- Maheidem/byd-seat-memory: BYD HAL, Seat comfort stage, Socket.IO/JNI/native fallback 구조
- AkeJakkrapong/BYD_dolphin_steering_display: 현재 GitHub 공개 페이지/Raw 소스가 조회되지 않아 직접 코드 반영은 보류
- wheregoes/byd-dolphin-hacking driver-display 연구: BYDAutoInstrumentDevice 계기판 API와 INSTRUMENT_* 권한 구조 교차 확인
- 사용자 BYD_Audit_Final.tar의 framework.jar 문자열: 이 차량에서 실제 존재하는 열선/계기판 메서드 교차 확인

## 이번 소스 변경
1. `ReferenceResearchProbe.kt` 추가
   - Bodywork/Setting/Speed/Instrument 클래스와 관심 메서드를 읽기 전용으로 검사
   - reference/Electro 패키지 설치 여부 검사
   - Maheidem POC에 기록된 localhost:8080 TCP 포트 존재 여부만 검사(데이터/명령 전송 없음)
2. 진단 ZIP에 `reference_capabilities.json` 추가
3. 시트 열선 API fallback
   - `getSeatHeatingState` → 없으면 `getSeatHeatingState1`
   - `setSeatHeatingState` → 없으면 `setSeatHeatingState1`
   - void/boolean/int 반환형을 안전하게 판별
4. 계기판 API capability summary 추가
5. Manifest package visibility에 Rory reference/Electro 패키지 조회 추가

## 차량 감사파일과의 중요한 대조
현재 차량 framework.jar 문자열에는 다음이 확인됨:
- getSeatHeatingState / getSeatHeatingState1
- setSeatHeatingState / setSeatHeatingState1
- getSteeringWheelHeatingState / setSteeringWheelHeatingState
- sendAutoNaviStatus / sendSimpleGuidanceInfo / sendNextPathName
- sendCameraGuidanceInfo / sendSafeGuidanceInfo
- sendMusicState / sendMusicInfo

반면 Maheidem 문서에서 추정/예시로 제시된 `setDriverComfortStage`, `setPassengerComfortStage`는 이 차량 framework.jar 문자열 검사에서 확인되지 않았으므로 실제 제어 기능으로 강제 추가하지 않았다. 진단 probe에서 런타임 존재 여부를 다시 확인한다.

## 다음 차량 테스트에서 볼 파일
`reference_capabilities.json`
- `referenceSocket8080Open=true` 이고 Rory/Electro 패키지가 설치되어 있으면 localhost bridge 연구 가치가 큼
- Bodywork comfort-stage methods가 `present=true`면 해당 차량 펌웨어에 시트 포지션 HAL이 실제 존재
- Instrument guidance methods가 존재하지만 호출이 SecurityException이면 API 부재가 아니라 권한 문제로 확정 가능
