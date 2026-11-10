# 루트 프로젝트(app + pleos-kit) 빌드 → 설치 → 실행 → 결과(logcat) 출력
# 실행: powershell -ExecutionPolicy Bypass -File run-app.ps1 [-SkipBuild] [-BuildOnly] [-SelfTest] [-Route] [-WaitSeconds 60]
#   -SelfTest: pleos-kit 점검(KitSelfTest)을 함께 돌린다. -Route: 길안내 시작·경유지 추가까지 시험한다(에뮬레이터 부하 큼)
param([int]$WaitSeconds = 30, [switch]$SkipBuild, [switch]$BuildOnly, [switch]$SelfTest, [switch]$Route)
. "$PSScriptRoot\env.ps1"
if (-not $P) { throw "env.ps1 not loaded" }
$proj = "$P\repo"
$pkg = "dev.hmg.pleosapp"
Set-Location -LiteralPath $proj -ErrorAction Stop
"sdk.dir=$($env:ANDROID_HOME.Replace('\','\\'))" | Set-Content -Encoding ASCII "$proj\local.properties"
# PowerShell 5.1은 네이티브 stderr를 오류로 감싸므로 cmd로 합쳐서 로그에 남긴다
if (-not $SkipBuild) {
  cmd /c "gradlew.bat --no-daemon :app:assembleDebug > `"$P\logs\app-build.log`" 2>&1"
  if ($LASTEXITCODE -ne 0) { "BUILD FAILED (exit $LASTEXITCODE)"; Select-String -Path "$P\logs\app-build.log" -Pattern "^e: |error:|FAILED|What went wrong" -Context 0,3 | Select-Object -First 40 | ForEach-Object { $_.ToString() }; exit 1 }
  "BUILD OK"
}
# -BuildOnly: 에뮬레이터를 띄우기 전에 빌드만 해 둘 때 쓴다 (빌드와 에뮬레이터가 CPU를 다투면 둘 다 느려진다)
if ($BuildOnly) { exit 0 }
$apk = "$proj\app\build\outputs\apk\debug\app-debug.apk"
cmd /c "`"$ADB`" install -r `"$apk`" 2>&1"
cmd /c "`"$ADB`" shell am force-stop $pkg 2>&1"
# 위험(dangerous) 권한은 AAOS 포그라운드 사용자(user 10)에게 부여해야 한다. 실제 사용자는 앱의 권한 창에서 허용한다
foreach ($perm in "android.car.permission.CAR_SPEED", "pleos.car.permission.FUSED_LOCATION") {
  cmd /c "`"$ADB`" shell pm grant --user current $pkg $perm 2>&1"
}
cmd /c "`"$ADB`" logcat -c"
$extras = ""
if ($SelfTest) { $extras += " --ez selftest true" }
if ($Route) { $extras += " --ez route true" }
cmd /c "`"$ADB`" shell am start -n $pkg/.MainActivity$extras 2>&1"
Start-Sleep -Seconds $WaitSeconds
cmd /c "`"$ADB`" logcat -d -s PleosApp:I PleosKit:D > `"$P\logs\app-result.log`" 2>&1"
cmd /c "`"$ADB`" logcat -d > `"$P\logs\app-full.log`" 2>&1"
Get-Content "$P\logs\app-result.log"
