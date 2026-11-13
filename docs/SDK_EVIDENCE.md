# SDK 선택과 실제 데이터

**v0.4 우선 규칙:** 리더의 공유 확인과 각 차량의 목적지 변경 확인이 매번 필요하다. 이전 자동 따라가기 동의로 내비를 쓰지 않는다. 현재 화면·동작은 [REVIEW_02](REVIEW_02.md)와 [IMPLEMENTATION](IMPLEMENTATION.md)을 따른다. 나머지 확장 설계는 구현 완료를 뜻하지 않는다.

확인일: 2026-11-13. 공식 웹 문서, Pleos 공식 샘플과 이미 내려받은 SDK 소스를 대조했다. 아래 ‘명세 확인’은 이 여행 앱의 실제 실행 완료를 뜻하지 않는다.

0.3에서 NaviHelper/FusedLocation 실제 연결과 복수 에뮬레이터 목적지 전달을 진행했다. 런타임에서 확인한 null sub-ID, 완료 이벤트, 순정 안내 시작의 차이는 [구현 문서](IMPLEMENTATION.md), 실제 검증 결과는 [검증 기록](VERIFICATION.md)에 분리한다.

v0.2에서 목적지·경유지·주행 제한 공식 문서를 재확인하고, 아래 콜백의 실제 타입을 추가로 검토했다. 신규 기능을 제공한다고 가정한 SDK는 없다.

## 1. 사용할 SDK

| 구성 | 선택 | 실제 입력·출력 | 모아트립에서 사용 |
|---|---|---|---|
| NaviHelper 3.0.5 | 필수 | 순정 목적지·경유지, 남은 시간·거리, 안내 상태, 목적지·경유지 설정 요청 | 리더 목적지 공유, 각 차 내비 반영, 차량별 ETA |
| FusedLocation 1.1.3 | 필수 | latitude, longitude, altitude, heading, confidence, timestampMillis | 동의한 차량의 위치·방향, 갱신 시각 |
| TextToSpeech 3.3.1 | 후속 검토 · 현재 미사용 | 앱이 만든 짧은 문장을 차량 오디오로 재생 | 경유지 변경·적용 결과·출발 준비 안내 후보 |
| SpeechToText 3.3.1 | 핵심 연동 후 확장 | 차량 마이크 발화를 텍스트로 반환 | ‘쉬고 싶어’, ‘확인’ 등 고정 의도를 앱 코드가 해석 |
| Vehicle SDK | 초기 버전 불필요 | 이 앱의 필수 데이터에 추가 이점이 확인되지 않음 | 조명·가속·ADAS 기능을 억지로 넣지 않음 |
| AAOS 공개 차량 API | 필수 보조 | UX 제한, 기어 | 운전 중 허용 화면과 주차 후 조작 구분 |
| MapLibre Native Android | 후속 검토 · 현재 미사용 | 지도 렌더링, 마커·레이어 | 여행방 차량과 만남 장소 표시 후보 |
| 외부 자동차·보행 경로 API | 후속 픽업에 필요 · 현재 미사용 | 구간 시간·거리, 필요 시 경로 형상 | 원래 픽업과 대안 후보 비교 |

Pleos 패키지 저장소는 `https://nexus-playground.pleos.ai/repository/maven-releases/`다. NaviHelper와 FusedLocation의 공식 샘플은 각각 3.0.5와 1.1.3을 명시한다. 현재 영문 릴리즈 표 일부와 한국어 표의 버전 차이가 있어 **공식 샘플·한국어 최신 표·배포된 아티팩트를 대조한 버전**으로 고정한다. 무조건 최신 번호를 추측해 올리지 않는다.

## 2. NaviHelper 구체적 사용

| 기능 | API·콜백 | 데이터와 처리 |
|---|---|---|
| 리더 목적지 읽기 | `getDestinationInfo()` → `onDestinationInfo()` | name, address, latitude/longitude, display 좌표, poiId, poiSubId, poiProvider |
| 변경 감지 | `onDestinationChanged()` | 이벤트 후 목적지·안내 상태 재조회. 이벤트 자체만으로 새 계획을 만들지 않음 |
| 다른 차량 목적지 설정 | `requestRoute(RouteInfo)` | 각 차량 앱이 자신의 순정 내비에 요청. 서버가 차량 내비를 직접 제어하는 API가 아님 |
| 목적지+경유지 반영 | `requestRouteWithWaypoints(RouteWithWaypointsRequest)` | 최종 목적지와 개인·공동 경유지를 합성한 순서 있는 요청 |
| 경유지 읽기 | `getWaypointInfo()` → `onWayPointInfo()` | 장소와 remainTime, remainDistance. `WayPoint` 철자 주의 |
| 경유지 수정 | `addWaypoint()`, `removeWaypoint()` | API는 있으나 앱의 기본 쓰기는 전체 유효 계획을 한 번 요청하는 방식. 중복 추가 방지 |
| 적용 완료 확인 | `onRoutingCompleted()`, `onError()` 후 목적지·경유지 재조회 | 함수 호출 반환이나 탐색 완료 이벤트만으로 APPLIED 처리하지 않음 |
| 목적지 ETA | `DestinationInfo.remainTime` | 초. remainDistance는 m. 새 표본 시각+남은 시간으로 예정 시각 계산 |
| 공동 경유지 ETA | `WaypointInfo.remainTime` | 해당 공동 장소가 실제 경유지에 반영된 차만 비교 |
| 안내 여부 | `getRouteStateInfo()` → `onRouteStateInfo()` | naviMode, isRouting, routeOption 등. 안내·탐색 상태 의미를 런타임에서 확인 |
| 도착 | `onDestinationArrived()`, `onWayPointArrived()` | 목적지/경유지의 도착 상태. 탑승 완료는 사람이 확인 |
| 순정 화면 열기 | `requestNavigationScreen()` | 기존 순정 화면을 요청하는 기능. 사용자 정의 마커 API가 아님 |

### 장소 데이터가 핵심인 이유

`RouteInfo`에는 latitude, longitude, poiName, poiId, address, poiSubId가 필요하다. `poiProvider`도 보존한다. 조회 모델의 poiSubId는 nullable인데 요청 모델은 String이다. null을 임의로 `0` 또는 빈 문자열로 바꿔 항상 성공한다고 가정하지 않는다.

초기 장소 입력은 리더의 현재 순정 목적지·경유지, 순정 즐겨찾기·최근 목적지로 시작한다. 이 정보의 POI 식별자를 그대로 보존하고 각 내비 공급원에서 읽기→쓰기 호환성을 시험한다. 외부 지도에서 받은 TMAP POI ID를 현대 POI ID로 쓰지 않는다.

좌표만 있는 장소의 등록 허용 여부는 M0에서 시험한다. 미검증이면 `좌표만으로 자동 등록 가능`이라고 표시하지 않는다. 픽업 추천 역시 순정 내비 연결까지 확인된 후보만 자동 적용 대상으로 둔다.

### 공개 API로 확인되지 않은 기능

- 순정 지도 위에 다른 차량 아이콘·스티커를 직접 그리는 기능.
- 다른 사람의 차량 위치나 내비 설정을 서버에서 일괄 읽는 기능.
- 순정 내비의 전체 예정 도로 경로선을 추출해 그대로 공유·주입하는 기능.
- `requestTrip`을 임의 출발지와 보행 모드가 있는 경로 계산기로 쓰는 기능.

`TripSummary.routePoints`를 전체 도로 형상으로 해석하지 않는다. 여행방은 ‘같은 장소’와 ‘각자의 ETA’를 공유하고, 도로 선택은 각 차량의 순정 내비에 맡긴다.

## 3. 위치와 음성

FusedLocation은 `isSupported()` 확인, `initialize()`, `registerFusedLocationCallback()` 순서로 사용한다. 마지막 위치를 읽는 `getLastKnownLocation()`은 새 측위를 강제하지 않는다. null, 오래된 timestamp, `onError`를 처리한다. `confidence`는 소스에 Float로 정의돼 있으나 단위·판정 범위가 확정되지 않았으므로 정확도 미터나 퍼센트로 표시하지 않는다. LocationData에 속도 필드는 없다.

TTS는 `TextToSpeech(context, Mode.ON_DEVICE)`와 `speak(text)`를 우선 사용한다. 공식 문서상 ON_DEVICE 모드는 서버 앱 등록이 필요 없다. 장치 엔진·언어·오디오 포커스는 따로 검증한다. HYBRID/SERVER로 확장할 때는 프로젝트 등록과 연결 절차를 추가한다.

STT는 `SpeechToText(..., Mode.ON_DEVICE)`, `initialize()`, `addListener()`, `request()/stop()` 흐름을 사용한다. STT는 글로 바꾸는 SDK다. 명령 실행은 모아트립이 제한된 프리셋 의도로 매핑하고, 경로 변경처럼 영향이 큰 요청은 확인을 거친다. 상시 호출어 감지나 운전대 버튼 소유권을 제공한다고 가정하지 않는다.

## 4. 권한과 환경

| 권한/API | 용도 |
|---|---|
| `pleos.car.permission.NAVI_ROUTE` | 순정 내비 정보 조회 |
| `pleos.car.permission.NAVI_ROUTE_SEARCH` | 목적지·경유지 변경 요청 |
| `pleos.car.permission.FUSED_LOCATION` | 위치 조회·구독, 런타임 허용 처리 |
| `pleos.car.permission.TTS_SERVICE` | TTS 초기화·음성 합성 |
| `pleos.car.permission.STT_SERVICE` | STT를 추가하는 단계의 마이크 인식 |
| `CarUxRestrictionsManager` | 제한 상태 변경 구독, 기능별 UI 제어 |
| `GEAR_SELECTION` | 주차 여부 읽기. 지원·권한을 SDK 연동 단계에서 검사 |
| Android INTERNET 및 해당 실행 방식의 서비스 권한 | 여행방 연결·필요한 활성 여행 서비스 |

초기 검증 기기는 이미 준비된 **Pleos Connect Emulator 2.1.0 / API 34 / x86_64 / 2560×1440 / 160dpi**다. 화면을 이 크기로 고정하지 않고 앱 윈도우의 실제 dp에 맞춘다. 기존 JDK17 도구 환경은 재사용하되 RPG의 앱 ID·저장 데이터·서명 키를 복사하지 않는다.

Maps/Navigation 보조 경험으로 설계하되 앱 분류나 DO 선언 자체가 주행 허가를 보장하지 않는다. CarUxRestrictions의 실제 제한을 따른다. `SDK에 메서드 있음`, `에뮬레이터에서 호출 성공`, `자동 동기화 전체 흐름 검증`은 서로 다른 상태로 기록한다.

## 5. 지도·픽업의 외부 기반과 비용

지도 렌더러는 MapLibre Native를 선택한다. 실제 도로·지명 타일은 별도 공급이며 첫 지도 연결 시험은 MapTiler 계열 스타일을 후보로 두고 AAOS 표시·한국 지도 품질·이용 조건을 확인한 뒤 고정한다. 지도 공급을 아직 확보했다고 표시하지 않는다. 예제용 데모 타일을 상용 서비스의 무제한 지도 서버로 사용하지 않는다.

픽업 비교는 TMAP `POST /tmap/routes`와 `POST /tmap/routes/pedestrian`을 첫 공급자 어댑터로 선택한다. 반환된 totalTime(초), totalDistance(m)를 정규화한다. 비밀 appKey는 서버 환경에만 둔다. 외부 경로와 순정 내비의 실제 선택 경로는 다를 수 있다.

공식 TMAP 약관에는 경로안내 일 1,000건 등 무료 한도가 안내되어 있지만, 실제 계정 상품·적용 API·승인 조건을 확인해야 한다. 이 설계는 무료·무제한을 전제하지 않는다. 후보 6개와 기준 1개를 비교하면 자동차 2구간+보행 1구간씩 최대 21회, 최종 선택 재검사에 최대 6회의 정상 경로 요청이 필요하다. 재시도·일일 사용량 상한을 서버에 둔다. 지도 타일과 여행방 서버 비용은 별도다.

기본 동행 기능은 매 위치 갱신마다 외부 길찾기를 호출하지 않는다. 차량별 ETA는 각 차의 NaviHelper 결과를 사용한다. TMAP은 사용자가 픽업 비교를 요청했을 때만 호출한다.

## 6. 출처

- [공식 NaviHelper 샘플](https://github.com/Pleos-Playground/pleos-sample-navi-app)
- [목적지 요청·조회·결과](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations)
- [목적지와 경유지 함께 요청](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints)
- [공식 FusedLocation 샘플](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app)
- [FusedLocation 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro)
- [SDK 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)
- [TTS 모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [speak·stop](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop)
- [STT와 모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)
- [주행 UX 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)
- [주행 제한 구현](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- [앱 윈도우·생명주기](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)
- [앱 카테고리](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category)
- [MapLibre Android](https://maplibre.org/maplibre-native/android/examples/getting-started/)
- [TMAP 자동차](https://tmap-skopenapi.readme.io/reference/자동차-경로안내), [보행](https://tmap-skopenapi.readme.io/reference/보행자-경로안내), [응답 단위](https://tmap-skopenapi.readme.io/reference/경로안내-샘플예제)
- [TMAP 한도·이용 조건](https://tmapapi.tmapmobility.com/terms.html)

소스 파일의 기준은 상위 `pleos-api-audit-2026-10-29/download-manifest.json`이다. NaviHelper, navi-data, FusedLocation 소스의 공개 메서드와 모델을 직접 대조했다. 프로젝트 내부 문서의 이전 추정보다 실제 모델 타입을 우선한다.

## 7. 사용자 관점 보정에서 확인한 기술 경계

- 로컬 공개 소스의 WaypointArrivedInfo는 duration, distance, index, name, address다. DestinationArrivedInfo는 duration, distance, name, address다. 방의 stopId·planRevision·우리 요청 ID가 없다. 경유지 도착 콜백을 현재 경로의 방문 참조에 매핑해야 하며 FIRST가 늘 공동 휴게소를 뜻하지 않는다.
- onRoutingCompleted()에도 앱의 요청 ID가 없다. 앱에서 15초 타이머가 끝나도 내비의 비동기 쓰기가 취소됐다는 근거는 없다. 시간 초과 후 다음 요청을 자동으로 이어 보내는 정책을 제거했다. 실제 종료 확인 방법은 M0에서 검증한다.
- SDK 공개 경로 요청은 임의 출발지의 미리보기로 모든 차량의 ‘이미 지난 휴게소/올바른 진입 방향’을 검증해 주는 기능으로 사용하지 않는다. 주행 중 새 공동 경유지의 차량별 참여 확인은 **모아트립의 제품 정책**이다. 공식 가이드가 같은 버튼 구성을 요구하는 것은 아니다.
- CarUxRestrictions를 따라 UI를 제한하는 것은 공식 가이드 요구다. 지도보다 차량 카드를 앞세우는 구성, 무음 스티커, 10분 준비 확인, 보행 시간 대비 절감 기준은 **자체 UX 선택**이며 현대의 의무 기준이라고 적지 않는다.
- M0는 정상 도착·수동 경유지 삭제·경로 옵션 변경·안내 취소·늦은 콜백을 각각 관찰해야 한다. SDK가 이 원인들을 완벽히 식별해 준다는 전제로 구현하지 않는다.
