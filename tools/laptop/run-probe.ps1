# sdk-probe 빌드 → 설치 → 실행 → 결과(logcat) 출력
# 실행: powershell -ExecutionPolicy Bypass -File run-probe.ps1   (이 PC는 실행 정책이 Restricted)
param([int]$WaitSeconds = 25)
. "$PSScriptRoot\env.ps1"
if (-not $P) { throw "env.ps1 not loaded" }
$proj = "$P\repo\tools\sdk-probe"
Set-Location -LiteralPath $proj -ErrorAction Stop
"sdk.dir=$($env:ANDROID_HOME.Replace('\','\\'))" | Set-Content -Encoding ASCII "$proj\local.properties"
# PowerShell 5.1은 네이티브 stderr를 오류로 감싸므로 cmd로 합쳐서 로그에 남긴다
cmd /c "gradlew.bat --no-daemon assembleDebug > `"$P\logs\probe-build.log`" 2>&1"
if ($LASTEXITCODE -ne 0) { "BUILD FAILED (exit $LASTEXITCODE)"; Get-Content "$P\logs\probe-build.log" -Tail 40; exit 1 }
"BUILD OK"
$apk = "$proj\app\build\outputs\apk\debug\app-debug.apk"
cmd /c "`"$ADB`" install -r `"$apk`" 2>&1"
cmd /c "`"$ADB`" shell pm grant dev.hmg.sdkprobe android.car.permission.CAR_SPEED 2>&1"
cmd /c "`"$ADB`" logcat -c"
cmd /c "`"$ADB`" shell am start -n dev.hmg.sdkprobe/.MainActivity 2>&1"
Start-Sleep -Seconds $WaitSeconds
cmd /c "`"$ADB`" logcat -d -s PleosProbe:I > `"$P\logs\probe-result.log`" 2>&1"
Get-Content "$P\logs\probe-result.log"
