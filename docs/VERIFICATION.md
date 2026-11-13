# 0.4.1 배포본과 확인 흐름 검증

## 0.4.1 배포 검사 — 2026-11-13

- release APK 생성, APK Signature Scheme v2 서명 검증 통과. 버전 `0.4.1` / versionCode `3`.
- Kotlin core 14개 통과(해당 소스에 대한 Gradle 캐시 재사용), FastAPI 16개 재실행 통과.
- 배포본은 debug probe를 노출하지 않는다. 공식 Pleos 에뮬레이터에서만 로컬 서버 HTTP를 허용하며 외부 서버는 HTTPS를 요구한다.
- 신청서의 테스트 환경은 Pleos Connect 2.1.0 / Android 14 API 34 / 2560×1440, 160 dpi다.
- 배포 파일은 `apk/모아_모아트립_release.apk`, 해시는 같은 폴더의 `SHA256.txt`가 기준이다.
- 영상은 실제 v0.4.0 시작 화면과 예시 데이터 프로토타입을 구분한다. 0.4.1의 새 설치·실제 내비 변경 전체 흐름은 이번 배포 검사만으로 검증되지 않았다.

## 0.4 기능 검사 기록

2026-11-13. 여행 앱 `dev.moa.trip`. 이전 0.3 자동 따라가기 시험을 변경별 승인 시험으로 표시하지 않는다. 이전 기록은 `work/VERIFICATION_v03.md`에 보관한다.

| 검사 | 결과 |
|---|---|
| Android APK 빌드 | 통과 |
| Kotlin core | 14개 통과: 방·epoch·revision·목적지·개인 경로에 묶인 확인, 실제 두 번의 재조회 조건 |
| FastAPI | 16개 통과: 리더 확인 필수, 포괄 동의로 변경 불가, 변경별 차량 승인, 구버전 승인 거부, 거절 저장, 인증·위치/ETA·철회 |
| 실제 SDK 및 주행 상태 입력 | NaviHelper 목적지 readback, FusedLocation 표본, 공식 가상 콘솔의 Drive/30 km/h → CarUxRestrictions 80 수신 확인 |
| 최종 APK 설치·화면 | 전용 A/B 설치 성공. A에서 새 주간 시작 화면 확인. `outputs/MoaTrip-0.4-home.png` |
| 변경별 실제 내비 반영 | 미검증. 새 A와 기존 B 모두 순정 내비 준비 단계에서 제한 시간 초과. 확인 버튼 → 실제 내비 반영 전체 흐름 통과로 표시하지 않음 |

`tools/verify_emulators.py`는 두 독립 SDK 앱의 동시 수용 시험이다. 이 PC에서 이번 실행은 에뮬레이터 준비 단계에서 중단되었으며 통과 처리하지 않았다. `tools/verify_confirm_sequential.py leader`와 `follower`는 같은 방을 사용해 한 대씩 실행하는 대안이다. 이번 대안 실행도 leader 사전 준비에서 중단됐다. follower 확인·주행 중 승인 시나리오는 실행 완료하지 못했다. 두 대의 동시 ETA 재검증도 하지 못했다.

두 번째 인자로 전용 에뮬레이터 serial을 지정할 수 있다. 같은 에뮬레이터에 별도 시험 계정을 사용한 경우에는 그 사실을 기록해야 하며, 두 차량 동시 시험으로 해석하지 않는다.

증거: `work/evidence/confirm-sequential/leader-results.json`, `final-sdk-preflight.json`, `native-preflight.log`. 최종 SDK 사전 점검에서 서비스 바인딩은 CONNECTED였지만 내비 모드 UNKNOWN, 목적지·경로 상태 콜백 시각 0이었다. 위치/주차 상태 수신과 내비 공급자의 실제 준비 완료는 다르다. 초기화를 마치지 않은 상태에서도 목적지를 썼다고 주장하지 않는다. 당시 PC 가용 물리 메모리는 약 577 MiB였으나 이것이 유일한 원인인지는 확정하지 않았다.

실행 검사에 사용한 MoaTrip 전용 에뮬레이터는 종료했다. 다른 프로젝트의 에뮬레이터·빌드는 변경하지 않았다. A의 재생성 전 데이터는 `D:\Codex\MoaTripEmulator\avd\MoaTripA-preconfirm-20261113-151456.avd`에 보존했다.

단위·서버 검사는 합성 입력이다. 에뮬레이터 시험 스크립트는 실제 NaviHelper/FusedLocation 입력과 목적지 쓰기를 검사하도록 작성했으나, 이번 최종 실행은 위 사전 준비 단계에서 중단됐다. 가상 콘솔의 속도·기어는 시험용 센서 입력이며 실제 도로 교통이나 실차 주행 정확도 검증은 아니다.

이전 검사 APK(로컬 작업 자료): `outputs/MoaTrip-0.4.0-debug.apk`
SHA-256: `148CF74FC89CC771CF8E2F23F8DFA385F2A85EFD0602BE9A5336BA61B2844310`
