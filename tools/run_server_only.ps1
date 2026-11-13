. (Join-Path $PSScriptRoot 'environment.ps1')
if(-not (Test-Path -LiteralPath (Join-Path $TripRoot 'work\server-env\Scripts\python.exe'))) {
    & (Join-Path $PSScriptRoot 'setup_server.ps1')
}
try { $tripRunning=Invoke-RestMethod 'http://127.0.0.1:8766/health' -TimeoutSec 2 } catch { $tripRunning=$null }
if($tripRunning.source -eq 'MoaTrip') {
    Write-Host "MoaTrip server is already running (version $($tripRunning.version))."
    exit
}
& (Join-Path $PSScriptRoot 'start_server.ps1')
