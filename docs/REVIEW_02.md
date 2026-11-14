# v0.4 화면과 목적지 변경 보정

2026-11-13 사용자 요청. 이 문서가 v0.2의 포괄적인 자동 따라가기 동의보다 우선한다.

- 순정 화면과 연결되는 흰색·중립 회색 중심의 주간 테마, 시스템 설정을 따르는 야간 테마. 과도한 민트 강조와 큰 카드 반복을 없앴다.
- 넓은 창에서는 목적지·집결 요약과 차량 목록을 나란히, 좁은 창에서는 세로로 배치한다. 차량 순서는 고정한다.
- 차량별 이름, 목적지 상태, 위치 나이·직선 거리, 예상 도착 시간만 우선 표시한다. 좌표·SDK 진단은 기본 화면에서 뺐다.
- 리더가 순정 목적지를 바꾸면 **일행에게도 변경을 요청할까요?**: 일행에게 요청 / 나만 변경.
- 참여 차량에는 장소명과 **목적지를 변경할까요?**: 목적지 변경 / 현재 목적지 유지. 개인 경유지는 보존한다.
- 최초 합류·늦은 합류·주행 중 재변경 모두 같은 확인 계약을 사용한다. 기존 따라가기 설정은 권한이 아니다.
- 닫기·바깥 터치·뒤로 가기는 보류다. 목적지를 바꾸지 않고 화면의 ‘변경 요청 확인’에서 다시 연다. 거절한 요청은 재접속·프로세스 재시작으로 반복하지 않는다.
- v0.4.3: 앱이 배경에 있으면 순정 내비 위 시스템 알림의 두 버튼으로 직접 선택한다. 앱을 강제로 전면 실행하지 않는다. 알림 권한이 없거나 사용자가 응답하지 않아도 목적지는 그대로 유지한다.
- 확인은 방 ID·리더 epoch·계획 revision·목적지·당시 개인 경로에 묶인다. 창을 띄운 뒤 바뀐 요청이나 개인 경로에는 이전 확인을 적용하지 않는다.
- 설정·텍스트 입력은 주차 상태에서만 사용한다. 주행 중 목적지 확인은 짧은 안내와 두 버튼으로 제공한다. CarUxRestrictions의 콘텐츠/문자 길이 제한을 적용하며 제한 상태를 모르면 적용을 보류한다.

## 참고한 공식 문서

- [Pleos UI 원칙](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro): 기본 화면과의 일관성, 절제된 색상, 주야간 대응.
- [공식 색상](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors): 중립 배경, 회색 filled 버튼, 텍스트 위계.
- [System Popup](https://document.pleos.ai/docs/connect/guide/docs-design/components/popups/system-popup): 제목·설명·확인/취소 구성.
- [주행 제한 연동](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide): 차량에서 전달한 제한값을 사용.
- [CarUxRestrictions](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions): 문자 길이·콘텐츠 수 제한, 주행 중 설정과 키보드 금지.

Compose로 위 원칙을 반영한 앱 화면이다. 현대의 공식 컴포넌트 라이브러리 사용이나 마켓 심사 승인을 받은 상태라는 뜻은 아니다.
