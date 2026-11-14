"""Run the private local server; source tools/linux_env.sh before running."""
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "work/linux"
VENV = WORK / "server-env"
PYTHON = VENV / "bin/python"

if sys.platform != "linux" or os.environ.get("MOATRIP_ROOT") != str(ROOT):
    raise SystemExit("From bash, run: source tools/linux_env.sh")
if not PYTHON.exists():
    # Ubuntu can omit ensurepip. This official portable bootstrap avoids sudo
    # and keeps its environment, download, cache and temporary files in work/.
    bootstrap = WORK / "downloads/virtualenv.pyz"
    bootstrap.parent.mkdir(parents=True, exist_ok=True)
    if not bootstrap.exists():
        urllib.request.urlretrieve("https://bootstrap.pypa.io/virtualenv.pyz", bootstrap)
    subprocess.run([sys.executable, str(bootstrap), "--app-data", str(WORK / "virtualenv-cache"), str(VENV)], check=True)
subprocess.run([str(PYTHON), "-m", "pip", "install", "-r", str(ROOT / "server/requirements.txt")], check=True)
os.chdir(ROOT)
os.execv(str(PYTHON), [str(PYTHON), "-m", "uvicorn", "server.app:app",
                     "--host", "127.0.0.1", "--port", "8766", "--no-access-log"])
