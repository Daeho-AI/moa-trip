param([switch]$Both)
. (Join-Path $PSScriptRoot 'environment.ps1')
$powershell=(Get-Process -Id $PID).Path
$logs=Join-Path $TripRoot 'work\launcher'
New-Item -ItemType Directory -Path $logs -Force | Out-Null
if(-not (Test-Path -LiteralPath (Join-Path $TripRoot 'work\server-env\Scripts\python.exe'))) {
    & (Join-Path $PSScriptRoot 'setup_server.ps1')
}
try { $tripHealth=Invoke-RestMethod 'http://127.0.0.1:8766/health' -TimeoutSec 2 } catch { $tripHealth=$null }
if($tripHealth.source -eq 'MoaTrip' -and $tripHealth.version -ne '0.4.0') {
    $tripListener=Get-NetTCPConnection -LocalPort 8766 -State Listen -ErrorAction SilentlyContinue
    $tripServer=Get-CimInstance Win32_Process -Filter "ProcessId=$($tripListener.OwningProcess)"
    if($tripServer.CommandLine -notmatch 'uvicorn server.app:app.*8766') { throw 'Port 8766 is not the expected MoaTrip process.' }
    Stop-Process -Id $tripServer.ProcessId
    $tripHealth=$null
}
$healthy=$tripHealth.source -eq 'MoaTrip' -and $tripHealth.version -eq '0.4.0'
if(-not $healthy) {
    $serverScript=Join-Path $PSScriptRoot 'start_server.ps1'
    Start-Process -FilePath $powershell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$serverScript+'"')) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'server.log') -RedirectStandardError (Join-Path $logs 'server-error.log') | Out-Null
}
$serverDeadline=(Get-Date).AddSeconds(20)
do {
    try { $tripHealth=Invoke-RestMethod 'http://127.0.0.1:8766/health' -TimeoutSec 2; $healthy=$tripHealth.source -eq 'MoaTrip' -and $tripHealth.version -eq '0.4.0' } catch { $healthy=$false }
    if(-not $healthy) { Start-Sleep -Seconds 1 }
} while(-not $healthy -and (Get-Date) -lt $serverDeadline)
if(-not $healthy) { throw "MoaTrip server did not start on port 8766. Check $logs\server-error.log" }
$vehicles=if($Both){@('A','B')}else{@('A')}
foreach($vehicle in $vehicles) {
    $serial=if($vehicle -eq 'A'){'emulator-5556'}else{'emulator-5558'}
    $headless=Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match 'emulator|qemu' -and $_.CommandLine -match "-avd\s+MoaTrip$vehicle(?:\s|$)" -and $_.CommandLine -match '-no-window'
    }
    if($headless -and ((& $Adb -s $serial shell getprop ro.boot.qemu.avd_name 2>$null) -join '').Trim() -eq "MoaTrip$vehicle") {
        Write-Host "Reopening dedicated test emulator $vehicle with a visible window."
        & $Adb -s $serial emu kill | Out-Null
        $stopDeadline=(Get-Date).AddSeconds(30)
        while((Get-Date) -lt $stopDeadline -and ((& $Adb devices | Out-String) -match $serial)) { Start-Sleep -Seconds 1 }
    }
    if((& $Adb devices | Out-String) -notmatch "$serial\s+device") {
        $emulatorScript=Join-Path $PSScriptRoot 'start_emulator.ps1'
        Start-Process -FilePath $powershell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$emulatorScript+'"'),'-Vehicle',$vehicle,'-Visible') -WindowStyle Hidden | Out-Null
    }
    Write-Host "Waiting for Pleos $vehicle ($serial). Cold boot can take several minutes."
    $deadline=(Get-Date).AddMinutes(10)
    $ready=$false
    do {
        $boot=(& $Adb -s $serial shell getprop sys.boot_completed 2>$null)
        $currentUser=(& $Adb -s $serial shell am get-current-user 2>$null)
        $ready=($boot -join '').Trim() -eq '1' -and ($currentUser -join '').Trim() -eq '10'
        if(-not $ready) { Start-Sleep -Seconds 3 }
    } while(-not $ready -and (Get-Date) -lt $deadline)
    if(-not $ready) { throw "Pleos $vehicle did not finish booting. Check D:\Codex\MoaTripEmulator logs." }
    & $Adb -s $serial shell am start --user 10 -n ai.umos.maps.android.navigation.app/.MainActivity | Out-Null
    & (Join-Path $PSScriptRoot 'install.ps1') -Serial $serial
}
Write-Host 'MoaTrip is open. The leader confirms sharing, then each vehicle confirms its own destination change.'
Write-Host 'The server runs locally on port 8766. Existing trip data is kept.'
