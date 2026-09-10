# BYD Dolphin Auto Assistant v30 검증 보고서

## 결론

v30 소스는 XML 구문, Kotlin 소스 구조, 리소스 ID 참조에 대한 정적 검사를 통과했습니다. 차량 제어 경로는 제공된 DiLink 3 자료에서 확인한 API로 한정했고, 공개 setter 또는 정확한 프로토콜이 없는 기능은 차단했습니다. 새 원터치 진단은 읽기 전용 BYD getter와 Bluetooth 공개 진단 API만 사용합니다.

현재 작업 환경에서는 Gradle 8.7 배포 파일 다운로드가 네트워크 정책에 의해 실패하여 Android 컴파일과 APK 생성까지 완료하지 못했습니다. 따라서 이 패키지에는 APK가 없으며, 컴파일 성공을 주장하지 않습니다.

## 수행한 검사

| 검사 | 결과 | 범위 |
|---|---|---|
| Android XML 파싱 | 통과 | 24개: Manifest, layout, values, drawable, xml 리소스 |
| Kotlin 문자열·주석·괄호 구조 검사 | 통과 | 37개: `app/src/main/java`의 모든 Kotlin 파일 |
| 리소스 ID 참조 대조 | 통과 | Kotlin 참조 120개, XML 선언 121개, 누락 0개 |
| 미확인 BYD 브로드캐스트 검색 | 통과 | AVM 관련 제공 자료 확인 액션만 남김 |
| 제거 대상 기능 검색 | 통과 | 3·4분할, 임의 HUD 프레임, 비상등 setter, 구형 SeatManager 실행 경로 없음 |
| GitHub Actions YAML | 통과 | 수동 실행 가능한 APK 빌드 워크플로 1개 |
| Gradle Android 빌드 | 미완료 | `:app:compileDebugKotlin --offline` 실행 시 Gradle 8.7 다운로드 단계에서 `java.net.SocketException: Network is unreachable` |

## 원터치 진단 구현 점검

- 세션은 사용자가 시작한 뒤 최대 15분으로 제한되고 이벤트 파일은 8 MiB에서 추가 기록을 중단합니다.
- 앱 내부 로그, BYD 원시값 전환, Bluetooth ACL/본딩/UUID 이벤트, 권한과 기기 스냅샷, 런타임 공개 메서드 목록을 ZIP에 포함합니다.
- T900 후보에는 SDP UUID 갱신만 요청하며 RFCOMM 데이터 전송과 BYD setter 호출은 하지 않습니다.
- Bluetooth 주소는 앞 3바이트를 가리고 Android ID와 일련번호는 수집하지 않습니다.
- 내비 원문은 기본 마스킹하며 사용자가 켠 경우에도 활성 세션 동안만 허용합니다.
- 비정상 종료된 미완료 세션과 이전 프로세스의 오류 보고서를 다음 실행에서 복구합니다.

## 빌드 재현 방법

인터넷 접속이 가능한 JDK 17 및 Android SDK 34 환경에서 프로젝트 루트에서 실행합니다.

```bash
./gradlew :app:assembleDebug --stacktrace
```

정상 완료 시 예상 APK는 다음 위치입니다.

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub 저장소에 올린 경우 **Actions → Build Android APK → Run workflow**로 실행할 수 있고, 결과 아티팩트 이름은 `DolphinAutoAssistant-v30-Debug`입니다.

## 빌드 후 확인할 항목

정적 검사는 Android Gradle Plugin의 타입 검사와 DEX 생성을 대체하지 않습니다. 첫 온라인 빌드에서는 다음을 확인해야 합니다.

1. 의존성 해석과 Kotlin/Java 컴파일 성공
2. Manifest 병합 및 Android 14 포그라운드 서비스 검증 성공
3. APK 설치와 앱 최초 실행
4. BYD 서명 권한/화이트리스트 승인 상태
5. 정차 상태의 계기판 TBT 시험과 차량 API 반환 코드

## 남은 실차 검증

- AVH, BSD, 차선 오프셋, TJA 원시값의 실제 의미
- `driveInterface`와 `energyFeedback` 값의 표시명 매핑
- 차량 펌웨어가 계기판 TBT setter 호출을 수용하는지 여부
- TMAP Plus HUD / T900의 펌웨어 버전과 Bluetooth 바이너리 패킷
- 특정 앱 조합에서 2분할과 AVM 종료 후 비율 복원이 정상인지 여부

수집 절차와 안전 제한은 `REAL_VEHICLE_TEST_CHECKLIST_v30_KO.md`에 정리했습니다.

## 의도적으로 제외하거나 잠근 항목

- 전체화면 계기판 미러링
- 3·4분할
- 비상등 자동 ON/OFF
- 확인되지 않은 시트 위치·미러·창문 제어
- 티맵 Plus HUD 데이터·오디오·밝기 패킷 송신
- 의미가 확정되지 않은 ADAS/주행모드 원시값 기반 자동 음성

이 제한은 기능 누락이 아니라 오작동과 차량 안전 위험을 피하기 위한 v30의 명시적 경계입니다.
