param([ValidateSet('A','B')][string]$Vehicle='A',[switch]$Visible,[int]$MemoryMb=4096,[switch]$DisableVulkan,
    [ValidateSet('host','software','swiftshader','swangle')][string]$Gpu='swangle')
. (Join-Path $PSScriptRoot 'environment.ps1')
$name="MoaTrip$Vehicle"
$port=if($Vehicle -eq 'A'){5556}else{5558}
$avd=Join-Path $env:ANDROID_AVD_HOME "$name.avd"
$image=Join-Path $env:ANDROID_HOME 'system-images\android-34\pleos_car_emulator\crp_avd_out_x86_64'
if (-not (Test-Path -LiteralPath (Join-Path $image 'system.img'))) { throw 'Pleos image not installed.' }
if ((& $Adb devices | Out-String) -match "emulator-$port\s+device") { Write-Output "Already running: emulator-$port"; exit }
New-Item -ItemType Directory -Path $avd -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $env:ANDROID_AVD_HOME "$name.ini"),"avd.ini.encoding=UTF-8`npath=$avd`ntarget=android-34`n")
$config=@"
avd.ini.encoding=UTF-8
AvdId=$name
avd.ini.displayname=MoaTrip $Vehicle
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=$MemoryMb
hw.lcd.width=2560
hw.lcd.height=1440
hw.lcd.density=160
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=$Gpu
hw.gps=yes
hw.audioInput=yes
hw.device.name=Pleos_Connect_v2_0
hw.device.manufacturer=42dot
hw.mainKeys=no
disk.dataPartition.size=4G
image.sysdir.1=$image
tag.id=android-automotive
tag.display=Pleos Connect v2.1.0
target=android-34
fastboot.forceColdBoot=yes
"@
[IO.File]::WriteAllText((Join-Path $avd 'config.ini'),$config)
$arguments=@('-avd',$name,'-port',"$port",'-memory',"$MemoryMb",'-no-snapshot','-no-audio','-gpu',$Gpu,'-timezone','Asia/Seoul','-dns-server','8.8.8.8,8.8.4.4')
if ($DisableVulkan) { $arguments+=@('-feature','-Vulkan') }
if (-not $Visible) { $arguments+=@('-no-window','-no-boot-anim') }
$style=if($Visible){'Normal'}else{'Hidden'}
$process=Start-Process -FilePath (Join-Path $env:ANDROID_HOME 'emulator\emulator.exe') -ArgumentList $arguments -WindowStyle $style -PassThru -RedirectStandardOutput (Join-Path $env:ANDROID_EMULATOR_HOME "$name.log") -RedirectStandardError (Join-Path $env:ANDROID_EMULATOR_HOME "$name-error.log")
Write-Output "Started $name PID=$($process.Id) serial=emulator-$port"
$process.WaitForExit()
exit $process.ExitCode
