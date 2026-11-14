param([string]$Serial='emulator-5556',[switch]$GrantTestPermissions,[switch]$Debug)
. (Join-Path $PSScriptRoot 'environment.ps1')
$apk=if($Debug){Join-Path $TripBuildRoot 'app\outputs\apk\debug\app-debug.apk'}else{Join-Path $TripRoot 'apk\모아_모아트립_release.apk'}
if(-not (Test-Path -LiteralPath $Adb)) { throw 'Android SDK not found. Set ANDROID_HOME to your Android SDK folder.' }
if(-not (Test-Path -LiteralPath $apk)) { throw 'Download the release APK into apk/ or build the requested APK first.' }
& $Adb -s $Serial install -r $apk
if($LASTEXITCODE -ne 0) { throw 'APK install failed. If a debug version is installed, remove that test app first; its local settings will be lost.' }
$androidUser=(& $Adb -s $Serial shell am get-current-user).Trim()
if($GrantTestPermissions) {
    & $Adb -s $Serial shell pm grant --user $androidUser dev.moa.trip pleos.car.permission.FUSED_LOCATION
    & $Adb -s $Serial shell pm grant --user $androidUser dev.moa.trip android.permission.POST_NOTIFICATIONS
}
& $Adb -s $Serial shell am start --user $androidUser -n dev.moa.trip/.MainActivity
