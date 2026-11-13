"""Capture the app's debug-only observations, without credentials or session tokens."""
import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(r'D:\Codex\PleosBossToolchain\android-sdk\platform-tools\adb.exe')
p = argparse.ArgumentParser()
p.add_argument('name')
p.add_argument('--serial', default='emulator-5556')
p.add_argument('--trip', action='store_true')
args = p.parse_args()
assert args.name.replace('-', '').replace('_', '').isalnum()
user = subprocess.check_output([str(ADB), '-s', args.serial, 'shell', 'am', 'get-current-user'], text=True).strip()
filename = 'trip-state.json' if args.trip else 'sdk-probe.json'
data = subprocess.check_output([str(ADB), '-s', args.serial, 'shell', 'run-as', 'dev.moa.trip', '--user', user, 'cat', f'files/{filename}'])
obj = json.loads(data.decode('utf-8'))
folder = ROOT / 'work' / 'evidence'
folder.mkdir(parents=True, exist_ok=True)
(folder / f'{args.name}.json').write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(obj, ensure_ascii=False, indent=2))
