param([switch]$IncludeDebug)
. (Join-Path $PSScriptRoot 'environment.ps1')
$tripKeys=Join-Path $TripRoot 'work\signing'
New-Item -ItemType Directory -Path $tripKeys -Force | Out-Null
$tripCredentials=Join-Path $tripKeys 'release-signing.json'
if(-not (Test-Path -LiteralPath $tripCredentials)) {
    $random=[byte[]]::new(32)
    $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($random); $rng.Dispose()
    $secret=[Convert]::ToBase64String($random)
    $keystore=Join-Path $tripKeys 'moatrip-release.jks'
    $passFile=Join-Path $tripKeys 'keytool-password.txt'
    [IO.File]::WriteAllText($passFile,$secret)
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -noprompt -keystore $keystore -storetype JKS -alias moatrip -keyalg RSA -keysize 3072 -validity 10000 -storepass:file $passFile -keypass:file $passFile -dname 'CN=MoaTrip, OU=Moa, O=Daeho-AI, C=KR'
    if($LASTEXITCODE -ne 0) { throw 'Release key generation failed.' }
    @{storeFile=$keystore;password=$secret;alias='moatrip'} | ConvertTo-Json | Set-Content -LiteralPath $tripCredentials -Encoding utf8
    Remove-Item -LiteralPath $passFile
}
$signing=Get-Content -LiteralPath $tripCredentials -Raw | ConvertFrom-Json
$env:MOATRIP_STORE_FILE=$signing.storeFile
$env:MOATRIP_STORE_PASSWORD=$signing.password
$env:MOATRIP_KEY_ALIAS=$signing.alias
$env:MOATRIP_KEY_PASSWORD=$signing.password
try {
    $tasks=@(':app:assembleRelease',':core:test')
    if($IncludeDebug) { $tasks+=':app:assembleDebug' }
    & (Join-Path $PSScriptRoot 'build.ps1') -Tasks $tasks
    $apkDir=Join-Path $TripRoot 'apk'
    New-Item -ItemType Directory -Path $apkDir -Force | Out-Null
    $target=Join-Path $apkDir '모아_모아트립_release.apk'
    Copy-Item -LiteralPath (Join-Path $TripBuildRoot 'app\outputs\apk\release\app-release.apk') -Destination $target -Force
    $digest=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    [IO.File]::WriteAllText((Join-Path $apkDir 'SHA256.txt'),"$digest  모아_모아트립_release.apk`n")
    Write-Output "Release APK: $target"
} finally {
    Remove-Item Env:MOATRIP_STORE_PASSWORD,Env:MOATRIP_KEY_PASSWORD,Env:MOATRIP_STORE_FILE,Env:MOATRIP_KEY_ALIAS -ErrorAction SilentlyContinue
}
