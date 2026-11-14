"""Install an isolated Linux toolchain from official, checksum-verified archives.

Run from a cloned repository with Python 3.10+. All downloads stay in work/linux.
The Pleos/Android SDK licenses apply to use of the downloaded SDKs.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "work/linux"
SDK = WORK / "android-sdk"
DOWNLOADS = WORK / "downloads"


def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def install_archive(name, url, checksum, algorithm, destination):
    marker = destination / (".moatrip-" + name)
    if marker.exists() and marker.read_text().strip() == checksum:
        print(name + ": already installed", flush=True)
        return
    archive = DOWNLOADS / url.rsplit("/", 1)[1]
    if not archive.exists() or digest(archive, algorithm) != checksum:
        print(name + ": downloading", flush=True)
        partial = archive.with_suffix(archive.suffix + ".partial")
        with urllib.request.urlopen(url, timeout=120) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output, 4 * 1024 * 1024)
        if digest(partial, algorithm) != checksum:
            raise RuntimeError(name + ": checksum mismatch")
        partial.replace(archive)
    destination.mkdir(parents=True, exist_ok=True)
    if archive.name.endswith(".zip"):
        subprocess.run(["unzip", "-q", "-o", str(archive), "-d", str(destination)], check=True)
    else:
        subprocess.run(["tar", "-xzf", str(archive), "-C", str(destination)], check=True)
    marker.write_text(checksum + "\n")
    print(name + ": checksum verified, extracted", flush=True)


def main():
    if sys.platform != "linux" or os.uname().machine != "x86_64":
        raise SystemExit("This setup requires x86_64 Linux.")
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    (WORK / "tmp").mkdir(exist_ok=True)
    os.environ["TMPDIR"] = str(WORK / "tmp")
    repository = ET.fromstring(urllib.request.urlopen(
        "https://dl.google.com/android/repository/repository2-1.xml").read())
    jobs = []
    for name in ("emulator", "platform-tools", "cmdline-tools;latest"):
        package = next(p for p in repository if p.tag.endswith("remotePackage")
                       and p.get("path") == name and p.find("channelRef").get("ref") == "channel-0")
        complete = next(a for a in package.findall("archives/archive") if a.findtext("host-os") == "linux").find("complete")
        target = SDK / "cmdline-tools" if name.startswith("cmdline-tools") else SDK
        jobs.append((name.split(";")[0], "https://dl.google.com/android/repository/" + complete.findtext("url"),
                     complete.findtext("checksum"), "sha1", target))
    pleos = ET.fromstring(urllib.request.urlopen(
        "https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml").read())
    complete = pleos.find("remotePackage/archives/archive/complete")
    jobs.append(("pleos", complete.findtext("url"), complete.findtext("checksum"), "sha1",
                 SDK / "system-images/android-34/android-automotive"))
    release = json.load(urllib.request.urlopen(
        "https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest"))
    jdk = next(a for a in release["assets"] if a["name"].startswith("OpenJDK17U-jdk_x64_linux_hotspot_")
               and a["name"].endswith(".tar.gz"))
    checksum_asset = next(a for a in release["assets"] if a["name"] == jdk["name"] + ".sha256.txt")
    checksum = urllib.request.urlopen(checksum_asset["browser_download_url"]).read().decode().split()[0]
    jobs.append(("jdk17", jdk["browser_download_url"], checksum, "sha256", WORK / "jdk"))
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
        futures = [executor.submit(install_archive, *job) for job in jobs]
        for future in futures:
            future.result()
    cmdline = SDK / "cmdline-tools/cmdline-tools"
    if cmdline.exists() and not (SDK / "cmdline-tools/latest").exists():
        cmdline.rename(SDK / "cmdline-tools/latest")
    # The zip's outer directory is a build output name, not an Android ABI.
    # A standard ABI path lets Gradle/AVD Manager generate valid package metadata.
    pleos_build = SDK / "system-images/android-34/android-automotive/crp_avd_out_x86_64"
    pleos_abi = pleos_build.with_name("x86_64")
    if pleos_build.exists() and not pleos_abi.exists():
        pleos_build.rename(pleos_abi)
    manifest = [{"name": j[0], "url": j[1], "checksum": j[2], "algorithm": j[3]} for j in jobs]
    (WORK / "toolchain-downloads.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Linux SDK downloads complete. Source tools/linux_env.sh before building or running.", flush=True)


if __name__ == "__main__":
    main()
