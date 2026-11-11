#!/bin/bash
# 서비스 종료 순서 점검 (개발 서버에서 실행, debug 빌드가 설치되어 있고 앱이 떠 있어야 한다).
#   서비스가 스스로 끝날 때(stopSelf → onDestroy) ① 걸어 둔 Vehicle·내비 리스너를 모두 푼 뒤에 SDK를 놓고(release)
#   ② 여정 파일(trip.json)을 마지막으로 한 번 쓰고 ③ 예외 없이 끝나는지 본다. 프로세스는 화면과 함께 남는다.
#
# 회귀 방지: 서비스 감사 SV-D11 (끝날 때 놓으려고 SDK 객체를 새로 만들었고, 리스너를 풀기 전에 release 했다:
# CLAUDE.md '콜백이 끝나기 전에 release 금지'). 종료 순서는 JVM 테스트로 볼 수 없어서 기기에서 본다.
# 이 이미지에서는 `am stopservice`가 exported가 아닌 서비스에 닿지 않아 SdkProbe의 `stop-self`로 끝낸다.
#
# 사용: tools/laptop/shutdown-check.sh     (끝나면 서비스가 멈춰 있다. 다시 띄우려면 앱을 다시 시작한다)
set -u
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
PKG=dev.shimpyo.app
adb_() { timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB $*" 2>&1 | tr -d '\r'; }
ash_() { local c; c=$(printf '%s' "$*" | sed "s/'/''/g"); timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB shell '$c'" 2>&1 | tr -d '\r'; }

[ "$(adb_ get-state)" = "device" ] || { echo "FAIL: emulator not connected"; exit 2; }
ash_ "dumpsys package $PKG | grep pkgFlags" | grep -q DEBUGGABLE || { echo "SKIP: needs the debug build (SdkProbe stop-self)"; exit 2; }
pid=$(ash_ "pidof $PKG")
[ -n "$pid" ] || { echo "FAIL: $PKG is not running"; exit 2; }
ash_ "dumpsys activity services $PKG" | grep -q "ServiceRecord" || { echo "FAIL: the coach service is not running (start the app first)"; exit 2; }
user=$(ash_ "am get-current-user")
saved() { ash_ "run-as $PKG --user $user cat files/trip.json" | sed -n 's/.*"savedAt":\([0-9]*\).*/\1/p'; }
before=$(saved)

adb_ logcat -c >/dev/null
ash_ "am broadcast --user current -a $PKG.DEV -p $PKG --es probe stop-self" >/dev/null
sleep 10
log=$(T=60 adb_ logcat -d | grep -a " $pid ")
fail=0
line() { printf '%s\n' "$log" | grep -a -n -E "$1" | "$2" -1 | cut -d: -f1; }

printf '%s\n' "$log" | grep -a -q 'PROBE|stop-self|stopSelf()' || { echo "FAIL: the stop-self probe did not run"; exit 1; }

# ① 리스너를 모두 푼 뒤에 놓는다
lastVehicleListener=$(line '\[pleos-vehicle\]removeListener' tail)
vehicleRelease=$(line 'Vehicle: \[pleos-vehicle\]release' head)
if [ -n "$vehicleRelease" ] && [ -n "$lastVehicleListener" ] && [ "$lastVehicleListener" -lt "$vehicleRelease" ]; then
  echo "OK  Vehicle: $(printf '%s\n' "$log" | grep -a -c '\[pleos-vehicle\]removeListener') listeners removed, then release"
else
  echo "FAIL Vehicle: release (line ${vehicleRelease:-none}) must come after the last removeListener (line ${lastVehicleListener:-none})"; fail=1
fi
naviListener=$(line 'NaviHelper: \[pleos-navi\]removeListener' head)
naviRelease=$(line 'NaviHelper: \[pleos-navi\]release' head)
if [ -n "$naviRelease" ] && [ -n "$naviListener" ] && [ "$naviListener" -lt "$naviRelease" ]; then
  echo "OK  NaviHelper: listener removed, then release"
else
  echo "FAIL NaviHelper: release (line ${naviRelease:-none}) must come after removeListener (line ${naviListener:-none})"; fail=1
fi

# ② 마지막 저장 (실제 세계일 때만 쓴다: 시연 중이면 저장하지 않는다)
after=$(saved)
if [ -n "$after" ] && [ "$after" != "$before" ]; then
  echo "OK  trip.json written during shutdown (savedAt $before -> $after)"
else
  echo "FAIL trip.json was not written during shutdown (savedAt ${before:-none} -> ${after:-none}; a running demo does not save)"; fail=1
fi

# ③ 예외 없이 끝났고 프로세스는 남아 있다
if printf '%s\n' "$log" | grep -a -q -E "FATAL EXCEPTION|AndroidRuntime"; then
  echo "FAIL exception during shutdown:"; printf '%s\n' "$log" | grep -a -E "FATAL EXCEPTION|AndroidRuntime" | head -5 | cut -c1-200; fail=1
fi
now=$(ash_ "pidof $PKG")
if [ "$now" = "$pid" ]; then echo "OK  process $pid is still alive"; else echo "FAIL process $pid -> ${now:-gone}"; fail=1; fi
if ash_ "dumpsys activity services $PKG" | grep -q "ServiceRecord"; then echo "FAIL the service is still running"; fail=1; else echo "OK  service stopped"; fi

[ "$fail" -eq 0 ] && echo "PASS" || echo "FAILED"
exit $fail
