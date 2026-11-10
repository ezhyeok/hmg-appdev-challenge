# Pleos Connect 에뮬레이터를 SSH 세션과 분리된 프로세스로 실행 (창 없음, cold boot)
. "$PSScriptRoot\env.ps1"
if (-not $P) { throw "env.ps1 not loaded" }
$emuArgs = "-avd $AVD -no-window -no-snapshot -no-boot-anim -no-audio -gpu swiftshader_indirect"
$cmd = "cmd /c `"`"$EMU`" $emuArgs > `"$P\logs\emulator.log`" 2>&1`""
$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = $P }
"emulator launched pid=$($r.ProcessId) rc=$($r.ReturnValue)"
