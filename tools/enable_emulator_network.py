"""Initialize Connect 2.1.0 Ethernet on dedicated development AVDs only.

Uses Android's normal network request API under the existing adb shell UID.
No root, permission grants, image changes, or SDK data injection.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def prepare():
    sdk = Path(os.environ["ANDROID_HOME"])
    build = ROOT / "work/linux/network-helper"
    build.mkdir(parents=True, exist_ok=True)
    android = sdk / "platforms/android-35/android.jar"
    source = ROOT / "tools/emulator_network/EthernetKeepalive.java"
    subprocess.run(["javac", "--release", "8", "-classpath", str(android),
                    "-d", str(build), str(source)], check=True)
    subprocess.run([str(sdk / "build-tools/35.0.0/d8"), "--lib", str(android),
                    "--output", str(build), *map(str, build.glob("EthernetKeepalive*.class"))], check=True)
    return build


def enable(serial, build):
    adb = os.environ["MOATRIP_ADB"]
    def call(*args):
        return subprocess.check_output([adb, "-s", serial, *args], timeout=30, text=True).strip()
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        try:
            if call("shell", "getprop", "sys.boot_completed") == "1":
                break
        except subprocess.SubprocessError:
            pass
        time.sleep(2)
    else:
        raise RuntimeError(f"{serial}: boot did not complete")
    name = call("shell", "getprop", "ro.boot.qemu.avd_name")
    fingerprint = call("shell", "getprop", "ro.build.fingerprint")
    if name not in ("MoaTripA", "MoaTripB") or "crp_avd" not in fingerprint:
        raise RuntimeError(f"{serial}: not a dedicated Pleos MoaTrip AVD")
    if "inet 10.0.2.15/" in call("shell", "ip", "-4", "addr", "show", "eth0"):
        return {"serial": serial, "ethernet": "already ready"}
    remote = "/data/local/tmp/moatrip-network.dex"
    call("push", str(build / "classes.dex"), remote)
    with (build / f"{serial}.log").open("ab") as log:
        process = subprocess.Popen([adb, "-s", serial, "shell",
                                    f"CLASSPATH={remote} app_process /system/bin EthernetKeepalive"],
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    (build / f"{serial}.pid").write_text(str(process.pid) + "\n")
    for _ in range(30):
        if "inet 10.0.2.15/" in call("shell", "ip", "-4", "addr", "show", "eth0"):
            return {"serial": serial, "ethernet": "10.0.2.15 ready"}
        if process.poll() is not None:
            break
        time.sleep(1)
    raise RuntimeError(f"{serial}: Ethernet not ready; see {build / (serial + '.log')}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("serials", nargs="+", choices=["emulator-5556", "emulator-5558"])
    args = parser.parse_args()
    directory = prepare()
    with ThreadPoolExecutor(max_workers=2) as pool:
        for result in pool.map(lambda serial: enable(serial, directory), args.serials):
            print(result, flush=True)
