# 순정 내비 위에서 사용하는 모아트립

2026-11-15, v0.4.3. 여행방 참여·공유 설정은 주차 후 마치고, 이동 중에는 순정 내비를 계속 사용한다.

## 목적지 변경

리더가 순정 내비에서 목적지를 바꾸면 시스템 알림에 장소명과 **일행에게 요청 / 나만 변경**이 나온다. 요청한 경우 참여 차량에도 **목적지 변경 / 현재 목적지 유지**가 표시된다. 두 차량 모두 알림에서 바로 선택하며 모아트립 화면을 열지 않는다. 응답하지 않거나 거절하면 현재 경로를 유지한다. 놓친 요청은 여행방에서 다시 확인할 수 있다.

`TripService`가 `IMPORTANCE_HIGH`의 `CATEGORY_NAVIGATION` 알림을 게시한다. Android 표준 알림 API를 사용하며, 별도 지도 오버레이 권한이나 전체 화면 인텐트는 사용하지 않는다. 순정 안내 앱의 경로 소유권을 가져오거나 두 번째 길 안내를 실행하지 않는다.

`DestinationActionReceiver`는 외부로 공개하지 않는다. 버튼은 변경 불가능한 `PendingIntent`이며 방·리더·revision·목적지·당시 개인 경로 전체에 묶인다. 이전 알림의 내용을 새 제안으로 갱신하지 않는다. 컨트롤러가 현재 제안과의 일치, 새 서버 계획, 순정 내비 상태, UX 제한을 다시 검사한 뒤 승인된 요청만 SDK에 한 번 전달한다. 두 번의 독립된 readback 일치가 있어야 완료다.

Pleos Connect 2.1.0에서 실제 시스템 알림 표시, 양쪽 버튼, Drive/30 km/h 상태의 승인, 내비 화면 유지, 목적지 좌표·식별자 일치, 배경 위치·ETA 전달을 확인했다. OEM 설정이나 알림 권한에 따라 팝업이 표시되지 않을 수 있으며, 이 경우에도 자동 승인하지 않는다. 다른 차량 버전의 표시나 마켓 심사 승인을 의미하지 않는다.

## 다른 차량의 지도 표시

FusedLocation으로 동의한 각 차량의 좌표를 받아 여행방으로 공유할 수 있다. 그러나 **좌표 수신과 순정 지도에 아이콘을 추가하는 권한은 서로 다른 기능**이다. 공개 NaviHelper 3.0.5 문서와 배포 SDK의 공개 메서드에서 임의 마커·레이어 추가 API는 확인되지 않았다. 현재는 위치 갱신 시각·직선 거리·ETA를 여행방 목록으로 보여준다.

자체 지도 화면은 별도 지도 SDK·지도 이용 조건을 검토해 개발할 수 있지만, 순정 지도 위 표시와 같지 않다. 순정 지도에 정확한 차량 아이콘을 표시하려면 OEM에서 지원하는 확장 API가 필요하다. 화면 좌표를 추측해 지도 위에 그림을 겹치는 방식은 사용하지 않는다.

## 공식 근거

- [NaviHelper 3.0.5 기능](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro): 목적지·경유지·경로 요청, 정보 조회와 내비 화면 요청.
- [AAOS 알림](https://developer.android.com/training/cars/platforms/automotive-os/notifications): 높은 중요도의 내비 알림과 시스템 heads-up 표시. 제조사별 동작 차이.
- [Pleos 내비 앱 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide): 단일 경로, 명시적 사용자 선택, 주행 중 조작 최소화와 전체 화면 팝업 금지.

## 재현

debug APK가 설치된 전용 MoaTripA/B에서 `source tools/linux_env.sh` 후 `python3 tools/verify_background.py`를 실행한다. 실제 알림 버튼을 UiAutomation으로 찾아 누르고 SDK 관측 파일과 비교한다. 결과는 `work/evidence/background-v043`에 저장된다. 앱 내부 가짜 내비 데이터로 대체하지 않는다.
