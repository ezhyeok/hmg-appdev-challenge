# 질문 카드가 뜨면 몇 초 기다렸다 '들르기'를 누른다 (시연 영상 녹화용, 노트북에서 실행)
param([int]$DelayMs = 9000, [int]$WaitS = 170)
$adb = "$env:USERPROFILE\hmg-appdev-challenge\sdk\platform-tools\adb.exe"
$deadline = (Get-Date).AddSeconds($WaitS)
while ((Get-Date) -lt $deadline) {
  $line = cmd /c "`"$adb`" logcat -d -s Shimpyo:I" | Select-String "prompt shown: LAST_CHANCE" | Select-Object -Last 1
  if ($line) {
    "shown: $($line.Line.Trim())"
    Start-Sleep -Milliseconds $DelayMs
    cmd /c "`"$adb`" shell input tap 1075 1065" | Out-Null
    "tapped at $(Get-Date -Format HH:mm:ss.fff)"
    for ($i = 0; $i -lt 40; $i++) {
      $r = cmd /c "`"$adb`" logcat -d -s Shimpyo:I" | Select-String "goTo result" | Select-Object -Last 1
      if ($r) { "result: $($r.Line.Trim())"; exit 0 }
      Start-Sleep -Seconds 1
    }
    exit 0
  }
  Start-Sleep -Milliseconds 500
}
"no prompt within $WaitS s"
exit 1
