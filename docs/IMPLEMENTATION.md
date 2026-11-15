# 모아트립 구현 구조

여행방에서 목적지·경유지·위치·도착 시간을 공유합니다. 내비 변경은 방장의 공유 요청과 참여 차량의 동의를 거쳐 실행합니다. 주간·야간 화면은 Pleos 공식 배경·버튼·팝업 가이드를 참고했습니다.

공식 에뮬레이터는 호스트의 `10.0.2.2:8766`에 연결합니다. 로컬 예약 주소를 제외한 서버에는 HTTPS를 사용합니다. [경유지 처리와 SDK 호환 범위](WAYPOINTS.md)

## 목적지 변경

1. NaviHelper의 실제 목적지를 두 번의 안정된 조회로 감지한다.
2. 방장은 **일행에게 요청 / 나만 변경**을 선택한다. 요청을 선택해야 서버 계획 revision이 증가한다.
3. 각 참여자는 요청 장소를 보고 **목적지 변경 / 현재 목적지 유지**를 선택한다. 서버 `destination-decision`에 해당 revision의 선택을 저장한다.
4. 승인한 차량에서만 `requestRoute` 또는 보존할 경유지가 있는 경우 `requestRouteWithWaypoints`를 한 번 요청한다.
5. 실제 목적지·경유지·상태의 새 조회가 두 번 일치해야 등록 완료로 표시한다. 안내 자동 시작을 뜻하지 않는다.

`TripController.tick()`에는 목적지 쓰기가 없다. 쓰기는 `respondDestination()`의 현재 요청 확인 경로에만 있다. 이전 localFollow/grants 값, WebSocket 재전달, 재연결, 저장된 accepted 값은 쓰기 권한이 아니다. 서버도 방장의 `leaderConfirmed=true`와 차량별 현재 revision 승인을 확인한다.

확인 창의 방·hostEpoch·revision·목적지·개인 경로가 바뀌면 적용을 중지한다. 거절은 서버에 저장하며, ‘목적지 다시 확인’으로 재선택할 수 있다. 무응답·뒤로 가기·바깥 터치는 현재 경로를 유지한다. 배경의 시스템 알림에 동의·거절 버튼을 제공해 순정 내비를 벗어나지 않고 처리한다. [구현과 SDK 경계](NATIVE_NAVIGATION.md)

진행 중 요청은 한 번씩만 처리한다. 15초 안에 결과를 확인하지 못하면 `WRITE_UNCERTAIN`으로 추가 쓰기를 차단한다. 저장한 진행 요청은 앱 재시작 후에도 확인 장벽으로 유지한다. 이미 승인한 요청 처리 중 새 제안이 와도 새 revision의 성공으로 기록하지 않는다.

## 실제 연결

| 기능 | 연결 | 구현 |
|---|---|---|
| 목적지·경유지·경로 옵션 | NaviHelper 3.0.5 조회/콜백 | `PleosGateway.kt` |
| 승인한 목적지 등록 | NaviHelper `requestRoute` / `requestRouteWithWaypoints` | `TripController.respondDestination` |
| 위치·방향·원본 표본 시각 | FusedLocation 1.1.3 | `PleosGateway.kt` |
| ETA·남은 거리 | `DestinationInfo.remainTime`, `remainDistance` | 목적지 일치·등록 확인 뒤 공유 |
| 주차 설정/간단한 주행 확인 | AAOS `CarUxRestrictionsManager`, `GEAR_SELECTION` | 제한 길이·콘텐츠 수를 받고 설정/입력 제한 |
| 방·제안·개별 결정·전달 | FastAPI + SQLite WAL + WebSocket | `server/app.py`, `RoomApi.kt` |

위치와 ETA 공유는 별도 선택이다. 위치는 15초부터 오래된 표본 표시, 60초부터 숨긴다. ETA는 15초부터 집계에서 뺀다. 실제 SDK 원본 표본의 나이를 사용한다. 같은 목적지로 가는 모든 차량의 도착 시간이 확인되면, 가장 늦게 도착할 차량을 기준으로 ‘모두 모일 예상 시각’을 보여준다. 개인 종료는 해당 차량만 끝내며 순정 내비를 취소하지 않는다.

## SDK 호환성 경계

Connect 2.1.0 / Navi Service 3.0.4 / Navigation 3.3.2-rc5-hotfix.3의 정확한 에뮬레이터 fingerprint와 빈 provider에만 목적지 null sub-ID를 요청 시 `0`으로 전달한다. 서버·조회 모델에는 null을 보존한다. 다른 플랫폼·provider로 확대하지 않는다. 경유지 추가에 대한 별도 검증 범위는 [WAYPOINTS](WAYPOINTS.md)를 따른다.

공식 예제·기본 최근 목록과 현재 검색 결과의 좌표가 다른 사례가 있다. 방장 readback의 ID·provider·sub-ID·진입 좌표를 보존하고, 실제 등록 결과가 다르면 성공으로 처리하지 않는다. 새 순정 검색 결과에서 선택한 목적지의 차량 간 일치를 검증했다. [사례와 범위](VERIFICATION.md) 단일 경유지 추가·개인 경유지 보존은 별도 POI ID 호환 경로로 검증한다. 경유지를 묶어 최종 목적지까지 다시 쓰는 ID 전용 호환성과 자동 방문 완료 판정은 포함하지 않는다. 검증되지 않은 경유지는 지우지 않고 요청을 보류한다.

에뮬레이터는 `onRoutingCompleted`를 전달하지 않아 두 번의 독립된 전체 조회로 등록을 확인한다. `requestRoute` 뒤 순정 화면에 ‘안내 시작’이 남을 수 있다. [공식 목적지 요청](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)

지도, 휴게소 추천, 픽업 최적화, 스티커·음성, 방장 양도, 외부 HTTPS 운영 배포, 마켓 제출은 후속 범위다. 실제 SDK 검증과 합성 입력 테스트는 [VERIFICATION](VERIFICATION.md)에 구분한다.
