#!/bin/bash
# 앱 프로세스가 죽은 뒤 시스템이 서비스를 다시 만들었을 때 코치가 다시 도는지 본다 (개발 서버에서 실행).
#
# 회귀 방지 (2026-11-12 에뮬레이터): 앱이 화면에 떠 있을 때 죽으면(`am crash`) 시스템이 화면을 닫고 태스크를 지운 뒤
# START_STICKY 서비스를 다시 만드는데, 다시 만든 서비스에는 onStartCommand 없이 onTaskRemoved만 온다. 서비스가
# onStartCommand에서만 일을 시작해서, 알림만 떠 있고 코치는 4분 넘게 돌지 않았다(앱 화면을 열어야 시작).
# 지금은 PleosForegroundService가 onTaskRemoved에서도 시작하고, startForeground가 거절돼도 죽지 않는다.
#
# 본다: 1) `am crash` 뒤 새 프로세스가 'coach started: trip RESUMED'를 남긴다 (화면을 열지 않고)
#       2) debug 빌드면 크래시가 아닌 죽음(`run-as … kill -9`: 화면·태스크는 남는다)에서도 같다
#       3) 다시 뜬 프로세스에 FATAL EXCEPTION이 없다 (ForegroundServiceStartNotAllowedException 등)
# 준비: 앱이 실행 중이고 1분 넘게 돌았을 것(여정 맥박이 저장돼 있어야 RESUMED가 된다)
# 사용: tools/laptop/restart-check.sh
set -u
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
PKG=dev.shimpyo.app
adb_() { timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB $*" 2>&1 | tr -d '\r'; }
ash_() { local c; c=$(printf '%s' "$*" | sed "s/'/''/g"); timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB shell '$c'" 2>&1 | tr -d '\r'; }

fail=0
[ "$(adb_ get-state)" = "device" ] || { echo "FAIL: emulator not connected"; exit 2; }
if ash_ "dumpsys package $PKG | grep -E 'pkgFlags|flags=\['" | grep -q DEBUGGABLE; then build=debug; else build=release; fi
echo "installed build: $build"

# restarted <이름> <죽이는 명령 (PID는 지금 프로세스 번호로 바뀐다)>
restarted() {
  local name="$1" kill="$2" old new line="" fatal
  old=$(ash_ "pidof $PKG")
  [ -n "$old" ] || { echo "FAIL $name: app not running (start it and let it run for a minute first)"; fail=1; return; }
  adb_ logcat -c >/dev/null
  ash_ "${kill//PID/$old}" >/dev/null
  for _ in $(seq 1 30); do
    sleep 2
    new=$(ash_ "pidof $PKG")
    [ -n "$new" ] && [ "$new" != "$old" ] || continue
    line=$(T=60 adb_ logcat -d -s Shimpyo:I | grep " $new " | grep "coach started" | tail -1)
    [ -n "$line" ] && break
  done
  fatal=$(T=60 adb_ logcat -d -s AndroidRuntime:E | grep " ${new:-none} " | grep -c "FATAL EXCEPTION")
  if [ -n "$line" ] && [ "$fatal" -eq 0 ]; then
    echo "OK  $name: pid $old -> $new, ${line#*Shimpyo : }"
  else
    echo "FAIL $name: pid $old -> ${new:-none}; coach started: ${line:-not logged within 60 s}; fatal exceptions in the new process: $fatal"
    T=60 adb_ logcat -d -s PleosKit:W AndroidRuntime:E | tail -8
    fail=1
  fi
}

restarted "crash while on screen (am crash)" "am crash $PKG"
if [ "$build" = "debug" ]; then
  sleep 75  # 다음 맥박(1분)을 기다린다
  # AAOS의 앱 프로세스는 포그라운드 사용자(user 10)의 것이다: run-as에 --user를 줘야 같은 uid가 된다
  restarted "killed without a crash (kill -9)" "run-as $PKG --user $(ash_ 'am get-current-user') kill -9 PID"
fi
fg=$(ash_ "dumpsys activity services $PKG" | grep -c "isForeground=true")
[ "$fg" -ge 1 ] && echo "OK  the service is a foreground service" || { echo "WARN the service is not in the foreground (it retries every 10 s; open the app to promote it)"; }
[ "$fail" -eq 0 ] && echo "PASS" || echo "FAILED"
exit $fail
