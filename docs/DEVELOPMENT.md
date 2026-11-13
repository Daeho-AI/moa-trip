# 모아트립 개발과 실행

README의 실행기는 Windows에서 `D:\Codex` 아래에 저장한 프로젝트를 기준으로 한다. 기존 `ANDROID_HOME`과 `JAVA_HOME`을 사용하며, 이 PC에 설치된 D 드라이브 도구가 있으면 기본값으로 사용한다.

## 직접 서버 실행

Python 3.10 이상으로 저장소 루트에서 실행한다. 한 worker를 사용한다.

```powershell
python -m venv work/server-env
.\work\server-env\Scripts\python.exe -m pip install -r server/requirements.txt
$env:MOATRIP_DATABASE='D:\Codex\moa-trip\work\server\moatrip.db'
.\work\server-env\Scripts\python.exe -m uvicorn server.app:app --host 127.0.0.1 --port 8766
```

`http://127.0.0.1:8766/health`에서 `source: MoaTrip`을 확인한다. 공식 에뮬레이터의 `10.0.2.2`는 호스트 PC를 가리킨다. 외부 네트워크용 서버는 HTTPS/WSS를 구성한 뒤 앱의 연결 설정에서 지정한다.

## 직접 Android 빌드

JDK 17, Android SDK platform 35와 Android Automotive 라이브러리를 준비한다. `JAVA_HOME`·`ANDROID_HOME`을 설정한다. `android/local.properties`는 로컬 SDK 경로를 넣는 선택 파일이며 Git에 포함하지 않는다.

```powershell
cd android
.\gradlew.bat :app:assembleDebug :core:test
```

기본 빌드 결과는 `work/build/`에 생성된다. 전용 PowerShell 도구는 한글 경로 호환성을 위해 `D:\Codex\MoaTripToolchain\build`를 사용한다. 자체 서명 release는 루트의 `tools/build_release.ps1`로 만든다.

## 실제 SDK 확인

Debug 전용 probe 명령은 공식 에뮬레이터에서만 동작하며 release APK에서는 무시된다. 최종 APK는 사용자의 실제 화면 선택을 사용한다.

```powershell
.\tools\install.ps1 -Serial emulator-5556 -Debug -GrantTestPermissions
.\tools\install.ps1 -Serial emulator-5558 -Debug -GrantTestPermissions
.\work\server-env\Scripts\python.exe tools/verify_emulators.py
```

이 검사는 전용 MoaTrip A/B의 시험 여행방·순정 목적지를 바꾼다. GPS·기어·속도는 에뮬레이터 센서에 입력하며 앱의 SDK 응답을 가짜로 바꾸지 않는다. 실행하지 않은 검사는 성공으로 간주하지 않는다. 한 대씩 실행하는 대안은 `tools/verify_confirm_sequential.py leader`와 `follower`다.

## 문제 해결

- **서버 연결 실패**: 서버 창을 유지하고 `/health`를 확인한다. `10.0.2.2`를 실제 외부 기기 주소로 사용하지 않는다.
- **내비 확인 중**: 순정 내비를 직접 열어 초기화를 기다린다. SDK 서비스가 CONNECTED여도 목적지·경로 콜백이 없으면 준비 완료가 아니다.
- **SDK 장소 호환성 대기**: 임의 POI ID나 입구 좌표를 만들지 않는다. 현재 검증된 공급자·식별자 규칙은 [구현 문서](IMPLEMENTATION.md)를 따른다.
- **INSTALL_FAILED_UPDATE_INCOMPATIBLE**: 기존 시험 앱과 서명이 다르다. 필요한 데이터를 확인한 뒤 기존 앱을 제거하고 설치한다.
- **느린 부팅·검은 화면**: 다른 에뮬레이터와 빌드를 함께 실행하지 않고 한 대부터 확인한다. 앱은 연결 실패 시 가짜 값으로 전환하지 않는다.

개인정보가 있는 신청서, 로컬 DB·토큰, 서명키, 작업 로그는 공개 저장소에 포함하지 않는다.
