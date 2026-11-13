param([int]$Port=8766,[string]$Bind='127.0.0.1')
. (Join-Path $PSScriptRoot 'environment.ps1')
$python=Join-Path $TripRoot 'work\server-env\Scripts\python.exe'
if(-not (Test-Path -LiteralPath $python)) { throw 'Run tools\setup_server.ps1 first.' }
$env:MOATRIP_DATABASE=Join-Path $TripRoot 'work\server\moatrip.db'
Push-Location $TripRoot
try { & $python -m uvicorn server.app:app --host $Bind --port $Port --no-access-log } finally { Pop-Location }
