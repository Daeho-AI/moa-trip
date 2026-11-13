$ErrorActionPreference = 'Stop'
$TripRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $TripRoot.StartsWith('D:\Codex\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Use D:\Codex for this project.' }
$ToolRoot = if($env:MOATRIP_TOOLCHAIN){$env:MOATRIP_TOOLCHAIN}else{'D:\Codex\PleosBossToolchain'}
$TripBuildRoot='D:\Codex\MoaTripToolchain\build'
$env:TEMP='D:\Codex\.tmp'; $env:TMP=$env:TEMP; $env:TMPDIR=$env:TEMP
if(-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $tripBundledJava=Join-Path $ToolRoot 'jdk\jdk-17.0.21+9'
    if(Test-Path -LiteralPath $tripBundledJava) { $env:JAVA_HOME=$tripBundledJava }
}
if(-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
    $env:ANDROID_HOME=if($env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $env:ANDROID_SDK_ROOT)){$env:ANDROID_SDK_ROOT}else{Join-Path $ToolRoot 'android-sdk'}
}
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_USER_HOME=Join-Path $TripRoot 'work\android-user'
$env:ANDROID_EMULATOR_HOME='D:\Codex\MoaTripEmulator'
$env:ANDROID_AVD_HOME=Join-Path $env:ANDROID_EMULATOR_HOME 'avd'
$env:GRADLE_USER_HOME=Join-Path $ToolRoot 'gradle-cache'
$env:PIP_CACHE_DIR=Join-Path $TripRoot 'work\pip-cache'
$env:PYTHONDONTWRITEBYTECODE='1'; $env:PYTHONIOENCODING='utf-8'
foreach ($path in @($env:TEMP,$env:ANDROID_USER_HOME,$env:ANDROID_EMULATOR_HOME,$env:ANDROID_AVD_HOME,$env:PIP_CACHE_DIR)) {
    New-Item -ItemType Directory -Path $path -Force | Out-Null
}
$Adb=Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if(-not (Test-Path -LiteralPath $Adb)) {
    $tripAdbCommand=Get-Command adb.exe -ErrorAction SilentlyContinue
    if($tripAdbCommand) { $Adb=$tripAdbCommand.Source }
}
