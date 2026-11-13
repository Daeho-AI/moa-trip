param([string]$Serial)
. (Join-Path $PSScriptRoot 'environment.ps1')
if(-not (Test-Path -LiteralPath $Adb)) { throw 'Set ANDROID_HOME to your Android SDK folder, or add adb to PATH.' }
if(-not $Serial) {
    $tripDevices=@((& $Adb devices) | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if($tripDevices.Count -eq 0) { throw 'Start your Pleos Connect emulator first.' }
    if($tripDevices.Count -gt 1) { throw 'Multiple devices found. Run tools\install_existing_device.ps1 -Serial emulator-5556 (choose the intended serial).' }
    $Serial=$tripDevices[0]
}
& (Join-Path $PSScriptRoot 'install.ps1') -Serial $Serial
