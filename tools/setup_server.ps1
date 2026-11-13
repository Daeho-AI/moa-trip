. (Join-Path $PSScriptRoot 'environment.ps1')
$python=Join-Path $TripRoot 'work\server-env\Scripts\python.exe'
if(-not (Test-Path -LiteralPath $python)) { & python -m venv (Join-Path $TripRoot 'work\server-env') }
& $python -m pip install -r (Join-Path $TripRoot 'server\requirements.txt')
if($LASTEXITCODE -ne 0) { throw 'Server setup failed.' }
