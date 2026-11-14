"""Read live accessibility nodes and tap visible controls on the two test AVDs.

Uses Android UiAutomation as the adb shell test user. No application fixtures,
private application-state writes, or driving-policy overrides.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = os.environ.get("MOATRIP_ADB") or shutil.which("adb")


def adb(serial, *args):
    return subprocess.check_output([ADB, "-s", serial, *args], timeout=30).decode("utf-8", errors="replace").strip()


def prepare(serials):
    sdk = Path(os.environ["ANDROID_HOME"])
    build = ROOT / "work/ui-helper"
    build.mkdir(parents=True, exist_ok=True)
    android = sdk / "platforms/android-35/android.jar"
    subprocess.run(["javac", "--release", "8", "-classpath", str(android), "-d", str(build),
                    str(ROOT / "tools/emulator_ui/UiDump.java")], check=True)
    d8 = sdk / "build-tools/35.0.0" / ("d8.bat" if os.name == "nt" else "d8")
    subprocess.run([str(d8), "--lib", str(android), "--output", str(build), str(build / "UiDump.class")], check=True)
    for serial in serials:
        if adb(serial, "shell", "getprop", "ro.boot.qemu.avd_name") not in ("MoaTripA", "MoaTripB"):
            raise RuntimeError("Only dedicated MoaTrip test AVDs are allowed")
        adb(serial, "push", str(build / "classes.dex"), "/data/local/tmp/moatrip-ui.dex")


def tree(serial, all_windows=False):
    return ET.fromstring(adb(serial, "shell", "CLASSPATH=/data/local/tmp/moatrip-ui.dex app_process /system/bin UiDump" + (" all" if all_windows else "")))


def tap_node(serial, node):
    bounds = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
    if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
        raise RuntimeError("Visible control has invalid bounds")
    adb(serial, "shell", "input", "tap", str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))


def tap(serial, text, timeout=20, all_windows=False):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        nodes = [n for n in tree(serial, all_windows).iter("node") if n.get("text") == text and n.get("enabled") == "true"]
        if nodes:
            tap_node(serial, nodes[-1])
            time.sleep(.5)
            return
        time.sleep(.5)
    raise RuntimeError(f"{serial}: visible button missing: {text}")


def native_search_42dot(serial, query="42dot", name="42dot", settle=8):
    """Choose a fresh native search result, never the bundled stale recent item."""
    adb(serial, "shell", "am", "start", "--user", "current", "-n", "ai.umos.maps.android.navigation.app/.MainActivity")
    time.sleep(1)
    if not any(n.get("text") == "어디로 갈까요?" for n in tree(serial).iter("node")):
        # Search icon observed on the official 2560x1440 Connect 2.1.0 layout.
        if "2560x1440" not in adb(serial, "shell", "wm", "size"):
            raise RuntimeError("Native search test requires the documented 2560x1440 AVD")
        adb(serial, "shell", "input", "tap", "925", "1225")
    tap(serial, "어디로 갈까요?")
    fields = [n for n in tree(serial).iter("node") if n.get("class") == "android.widget.EditText"]
    tap_node(serial, fields[0])
    adb(serial, "shell", "input", "text", query)
    adb(serial, "shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(3)
    results = [n for n in tree(serial).iter("node") if n.get("text") == name and n.get("class") == "android.widget.TextView"]
    tap_node(serial, results[-1])
    tap(serial, "목적지로")
    time.sleep(settle)
