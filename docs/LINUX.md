# Linux 워크스테이션에서 실행

Windows PC는 SSH 연결과 결과 확인에 사용하고, 빌드·서버·에뮬레이터는 Linux에서 실행할 수 있습니다. [Pleos 공식 설치 안내](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)는 Windows/Linux용 x86_64 이미지를 제공합니다.

필요 환경: x86_64 Linux, Python 3.10 이상, Git, unzip, tar, `/dev/kvm` 읽기·쓰기 권한. 그래픽 사양은 [공식 가이드](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)를 따릅니다. `host` 렌더링은 로그인한 그래픽 세션이 필요합니다.

## 준비

Linux의 작업 폴더에서 실행합니다. 다운로드·SDK·캐시·가상 기기·서버 데이터는 모두 이 저장소의 `work/linux/` 아래에 생성됩니다.

```bash
git clone https://github.com/Daeho-AI/moa-trip.git
cd moa-trip
python3 tools/setup_linux.py
source tools/linux_env.sh
sdkmanager --licenses
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'emulator'
```

`setup_linux.py`는 공식 Android/Pleos/JDK 배포 파일을 내려받고 체크섬을 확인합니다. SDK 약관을 확인한 뒤 필요한 라이선스를 수락하세요. 셸을 새로 열면 `source tools/linux_env.sh`를 다시 실행합니다.

## 서버와 두 차량

첫 터미널에서 서버를 실행하고 유지합니다. Python 가상 환경과 의존성은 처음에 자동으로 준비됩니다.

```bash
source tools/linux_env.sh
python3 tools/start_linux_server.py
```

두 번째 터미널에서 에뮬레이터를 시작합니다. 기본은 차량당 4 GB이며 여유가 있으면 `--memory 8192`를 사용할 수 있습니다.

```bash
source tools/linux_env.sh
python3 tools/start_linux_emulators.py A B --memory 8192
adb devices
adb -s emulator-5556 install -r apk/모아_모아트립_release.apk
adb -s emulator-5558 install -r apk/모아_모아트립_release.apk
adb -s emulator-5556 shell am start --user current -n dev.moa.trip/.MainActivity
adb -s emulator-5558 shell am start --user current -n dev.moa.trip/.MainActivity
```

Intel 내장 GPU와 NVIDIA가 함께 있는 워크스테이션은 실행 명령에 `--nvidia`를 추가합니다. 검증 환경은 이 옵션으로 NVIDIA 렌더링을 사용했습니다. 기본 Intel EGL에서는 녹화 중 에뮬레이터가 종료되는 문제가 재현됐습니다. 실행기 로그의 `Graphics Adapter`에서 실제 선택된 GPU를 확인할 수 있습니다. 그래픽 로그인 세션이 없는 서버는 `--gpu swangle`을 사용할 수 있지만 이 방식의 성능은 별도 확인이 필요합니다.

```bash
python3 tools/start_linux_emulators.py A B --memory 8192 --nvidia
```

실행기는 부팅과 가상 Ethernet 초기화를 기다린 뒤 완료됩니다. 위치·알림 권한을 허용하고, P 상태에서 여행방을 만들고 초대 코드로 참여합니다. 앱의 기본 서버 주소 `http://10.0.2.2:8766`은 **Linux 호스트**의 서버를 가리킵니다. 공개 운영 서버를 만드는 명령은 아닙니다.

SSH에서 실행하면 기본적으로 에뮬레이터 창은 열리지 않습니다. 시작 명령에 `--window`를 추가하면 Linux의 로그인된 데스크톱에 창을 띄웁니다. 이미 실행 중인 기기는 scrcpy 등 ADB 화면 도구로 `emulator-5556`, `emulator-5558`을 각각 선택해 조작할 수 있습니다. 캡처와 녹화는 ADB로 가져올 수 있습니다.

```bash
mkdir -p outputs
adb -s emulator-5556 shell screencap -p /sdcard/moatrip.png
adb -s emulator-5556 pull /sdcard/moatrip.png outputs/vehicle-a.png
```

## Connect 2.1.0 네트워크 설정

검사한 공식 이미지의 `/vendor/etc/automotive/vhalconfig/dds/vhal-cyclonedds.xml`은 내부 통신 주소를 `10.0.2.15`로 지정합니다. 기본 Wi-Fi 주소 `.16`으로 실행하면 프로필 HAL 초기화 실패와 재연결 반복으로 시스템이 종료됐습니다. 앱을 설치하지 않은 기기에서도 재현했습니다.

실행기는 `-feature -WiFiPacketStream`으로 차량별 NAT를 분리하고, QEMU 옵션 `-set netdev.virtio-wifi.dhcpstart=10.0.2.15`로 필요한 DHCP 주소를 배정합니다. 시스템 이미지·SDK 데이터·보안 정책을 수정하지 않습니다. 이 보정은 **검사한 Connect 2.1.0 개발 환경용**이며 실차 앱 코드에는 들어가지 않습니다. [Android의 기존 네트워크 방식 선택 안내](https://developer.android.com/studio/run/emulator-networking-interconnect)

차량 HAL은 추가로 `eth0`의 IPv4를 기다립니다. 이미지의 Ethernet capability와 기본 네트워크 요청이 맞지 않아 DHCP가 시작되지 않는 현상도 확인했습니다. 실행기에 포함된 `EthernetKeepalive`는 기존 ADB shell 권한으로 Android의 Ethernet 연결을 요청합니다. `eth0`가 `10.0.2.15`를 받으면 실제 HAL이 초기화되어 가상 콘솔의 기어·속도가 AAOS에 전달됩니다. 이 도구는 개발용 전용 AVD에만 실행되며 APK에 포함되지 않습니다. root·새 권한 부여·SDK 값 대체를 사용하지 않습니다.

직접 재부팅한 전용 기기의 네트워크만 다시 준비하려면 아래 명령을 실행합니다. helper는 에뮬레이터 종료와 함께 종료됩니다.

```bash
python3 tools/enable_emulator_network.py emulator-5556 emulator-5558
```

## 소스 빌드와 검사

```bash
source tools/linux_env.sh
(cd android && bash gradlew :app:assembleDebug :core:test --no-daemon)
work/linux/server-env/bin/python -m pytest server/tests -q
```

debug APK는 `work/build/app/outputs/apk/debug/app-debug.apk`에 생성됩니다. debug와 배포 APK는 서명이 달라 시험 기기에서 기존 앱을 제거해야 교체할 수 있습니다. 제거하면 그 기기의 방 참여·설정도 초기화됩니다.

두 전용 기기에 debug APK를 설치한 뒤 `python3 tools/verify_emulators.py`로 실제 SDK 연결 검사를 실행할 수 있습니다. 이 검사는 시험용 방을 만들고 목적지·기어·속도·GPS를 바꿉니다. 실제 성공 범위는 [검증 기록](VERIFICATION.md)을 확인하세요.

확인·거절은 UiAutomation으로 읽은 실제 화면 버튼을 ADB로 누릅니다. 최초 목적지는 순정 내비의 새 검색 결과에서 선택하며, 오래된 확인키 재전송만 debug 진단 명령을 사용합니다. UI 검사 도구는 APK에 포함되지 않습니다.

Connect 2.1.0에 기본 제공된 최근 목적지의 42dot은 현재 검색 결과와 좌표가 다릅니다. 시연할 때는 **최근 목록 대신 새 검색 결과에서 장소를 선택**하세요. 조회한 위치와 실제 등록 결과가 다르면 앱은 성공으로 처리하거나 ETA를 합산하지 않고 순정 내비 확인을 안내합니다.

종료할 때는 전용 기기만 지정합니다.

```bash
adb -s emulator-5556 emu kill
adb -s emulator-5558 emu kill
```

서명키·ADB 키·원시 로그·서버 DB·신청서 개인정보가 있는 `work/`, `outputs/`는 Git에 올리지 않습니다.
