param([string[]]$Tasks=@(':app:assembleDebug',':core:test'))
. (Join-Path $PSScriptRoot 'environment.ps1')
Push-Location (Join-Path $TripRoot 'android')
try {
    & .\gradlew.bat @Tasks "-PtripBuildRoot=$TripBuildRoot" --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
