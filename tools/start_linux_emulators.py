"""Start dedicated MoaTrip A/B AVDs; source tools/linux_env.sh first."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
from concurrent.futures import ThreadPoolExecutor
from enable_emulator_network import prepare, enable

p = argparse.ArgumentParser()
p.add_argument("vehicles", nargs="*", choices=["A", "B"], default=["A", "B"])
p.add_argument("--gpu", default="host", choices=["host", "swangle", "swiftshader"])
p.add_argument("--memory", type=int, default=4096)
p.add_argument("--window", action="store_true", help="Show emulator windows in the Linux desktop session")
p.add_argument("--nvidia", action="store_true", help="Use NVIDIA PRIME/EGL on a Linux hybrid-GPU workstation")
p.add_argument("--kernel-log", action="store_true", help="Include guest kernel diagnostics in the private emulator log")
args = p.parse_args()
if args.nvidia:
    vendor = Path("/usr/share/glvnd/egl_vendor.d/10_nvidia.json")
    if args.gpu != "host" or not vendor.is_file():
        raise SystemExit("--nvidia requires --gpu host and the NVIDIA EGL driver.")
    os.environ.update(__NV_PRIME_RENDER_OFFLOAD="1", __GLX_VENDOR_LIBRARY_NAME="nvidia",
                      __EGL_VENDOR_LIBRARY_FILENAMES=str(vendor))
if args.gpu == "host" and not os.environ.get("DISPLAY"):
    # SSH does not inherit the user's graphical-session variables. Read only the
    # display connection fields; never execute the shell environment output.
    session = subprocess.run(["systemctl", "--user", "show-environment"], capture_output=True, text=True)
    for line in session.stdout.splitlines():
        key, separator, value = line.partition("=")
        if separator and key in ("DISPLAY", "XAUTHORITY"):
            os.environ[key] = value
    if not os.environ.get("DISPLAY"):
        raise SystemExit("Host GPU requires a graphical session. Use --gpu swangle for a headless host.")
sdk = Path(os.environ["ANDROID_HOME"])
avds = Path(os.environ["ANDROID_AVD_HOME"])
logs = Path(os.environ["ANDROID_EMULATOR_HOME"])
image = next((sdk / "system-images/android-34/android-automotive").glob("**/system.img")).parent
if not os.access("/dev/kvm", os.R_OK | os.W_OK):
    raise SystemExit("The current user needs read/write access to /dev/kvm.")
key = Path(os.environ["ADB_VENDOR_KEYS"])
key.parent.mkdir(parents=True, exist_ok=True)
if not key.exists():
    subprocess.run([os.environ["MOATRIP_ADB"], "keygen", str(key)], check=True)
key.chmod(0o600)
network_helper = prepare()
started = []
for vehicle in args.vehicles:
    port = 5556 if vehicle == "A" else 5558
    with socket.socket() as check:
        if check.connect_ex(("127.0.0.1", port)) == 0:
            raise SystemExit(f"Port {port} is in use. Existing emulator left unchanged.")
    name = "MoaTrip" + vehicle
    # A graphics initialization failure can leave QEMU alive without opening the
    # console port. Do not rewrite an AVD still owned by that process.
    for proc in Path("/proc").glob("[0-9]*/cmdline"):
        try:
            command_line = proc.read_bytes().split(b"\0")
            if b"-avd" in command_line and command_line[command_line.index(b"-avd") + 1] == name.encode():
                raise SystemExit(f"{name} still has process {proc.parent.name}; existing emulator left unchanged.")
        except (OSError, IndexError):
            continue
    avd = avds / (name + ".avd")
    avd.mkdir(parents=True, exist_ok=True)
    (avds / (name + ".ini")).write_text(f"avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-34\n")
    config = {
        "avd.ini.encoding": "UTF-8", "AvdId": name, "avd.ini.displayname": name,
        "abi.type": "x86_64", "hw.cpu.arch": "x86_64", "hw.cpu.ncore": 4,
        "hw.ramSize": args.memory, "hw.lcd.width": 2560, "hw.lcd.height": 1440,
        "hw.lcd.density": 160, "hw.keyboard": "yes", "hw.gpu.enabled": "yes",
        "hw.gpu.mode": args.gpu, "hw.gps": "yes", "hw.audioInput": "yes",
        "hw.device.name": "Pleos_Connect_v2_0", "hw.device.manufacturer": "42dot",
        "hw.mainKeys": "no", "hw.battery": "no", "hw.hotplug_multi_display": "no",
        "hw.multi_display_window": "no", "disk.dataPartition.size": "6G", "image.sysdir.1": str(image),
        "tag.id": "android-automotive", "tag.display": "Pleos Connect v2.1.0",
        "target": "android-34", "fastboot.forceColdBoot": "yes",
    }
    (avd / "config.ini").write_text("".join(f"{k}={v}\n" for k, v in config.items()))
    command = [str(sdk / "emulator/emulator"), "-avd", name, "-port", str(port),
               "-memory", str(args.memory), "-cores", "4", "-no-snapshot", "-no-audio",
               "-gpu", args.gpu, "-feature", "-Vulkan,-WiFiPacketStream", "-timezone", "Asia/Seoul",
               "-dns-server", "8.8.8.8,8.8.4.4", "-no-boot-anim"]
    if not args.window:
        command.append("-no-window")
    if args.kernel_log:
        command.append("-show-kernel")
    # Connect 2.1.0's bundled CycloneDDS config binds to 10.0.2.15. The default
    # Wi-Fi DHCP lease (.16) leaves its profile HAL in a restart loop. Give the
    # guest the required address using QEMU's network configuration; keep each
    # AVD on its isolated legacy NAT. No system image or SDK payload is modified.
    command.extend(["-qemu", "-set", "netdev.virtio-wifi.dhcpstart=10.0.2.15"])
    with (logs / (name + ".log")).open("ab") as log:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
    (logs / (name + ".pid")).write_text(str(process.pid) + "\n")
    print(json.dumps({"avd": name, "pid": process.pid, "serial": f"emulator-{port}"}), flush=True)
    started.append(f"emulator-{port}")

# The vehicle HAL additionally requires eth0 (not only wlan0) to have IPv4.
# Keep a normal Android Ethernet request alive until each AVD is stopped.
with ThreadPoolExecutor(max_workers=2) as pool:
    for result in pool.map(lambda serial: enable(serial, network_helper), started):
        print(json.dumps(result), flush=True)
