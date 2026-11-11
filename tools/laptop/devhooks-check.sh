#!/bin/bash
# 개발용 요청(인텐트 extras) 점검 (개발 서버에서 실행). 설치된 앱이 제출용(release) 빌드면
#   `--ez selftest true --ez route true`, `--ez verify true`가 아무것도 하지 않고 (점검 로그 없음, Virtual Console의
#   좌석·창문·조명·공조 값 그대로, 내비 요청 없음) `--es demo holiday`는 된다.
# debug 빌드면 점검이 도는지만 본다 (점검은 좌석·창문을 움직인다).
#
# 회귀 방지: 2026-11-12 닫힘 검증 N1. release 빌드에서 `am start -n dev.shimpyo.app/.MainActivity --ez selftest true`가
# KitSelfTest를 돌려 운전석 등받이·창문·조명·설정 온도를 움직였고 `--ez verify true`는 부산역으로 길안내를 새로 시작했다.
# 판단 규칙은 DevHooks(app, DevHooksTest)에 있고, 여기는 설치된 APK로 끝까지 확인한다. 제출용 APK를 만들면 돌린다.
#
# 준비: tools/vc/vc.sh up (시뮬레이터가 켜져 있어야 차량 값이 바뀐다), 기어 P
# 사용: tools/laptop/devhooks-check.sh [점검을 지켜보는 초, 기본 45]
set -u
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
WATCH="${1:-45}"
PKG=dev.shimpyo.app
VC="$(cd "$(dirname "$0")/../vc" && pwd)/vc.sh"
adb_() { timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB $*" 2>&1 | tr -d '\r'; }
ash_() { local c; c=$(printf '%s' "$*" | sed "s/'/''/g"); timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB shell '$c'" 2>&1 | tr -d '\r'; }

# 점검이 건드리는 차량 값 (KitSelfTest: 내기순환, 공조 전원·설정 온도, 창문, 등받이, 실내등, 앰비언트)
vc_snapshot() {
  "$VC" state 2>/dev/null | python3 -I -c '
import json, sys
try:
    s = json.load(sys.stdin)
except Exception:
    print("?"); sys.exit(0)
seat, body, hvac = s.get("seat", {}), s.get("body", {}), s.get("hvac", {})
print(json.dumps({
    "recline": seat.get("positions", {}).get("recline"), "reclineMove": seat.get("moves", {}).get("recline"),
    "windows": body.get("windows"), "cabinLight": body.get("cabin_light"), "ambient": body.get("ambient_light", {}).get("brightness_pct"),
    "hvacPower": hvac.get("power_on"), "recirc": hvac.get("recirc_on"), "temp": hvac.get("driver", {}).get("temp_c"),
}, sort_keys=True))'
}

fail=0
[ "$(adb_ get-state)" = "device" ] || { echo "FAIL: emulator not connected"; exit 2; }
before=$(vc_snapshot)
[ "$before" = "?" ] && { echo "FAIL: Virtual Console state not readable (tools/vc/vc.sh up)"; exit 2; }
if ash_ "dumpsys package $PKG | grep -E 'pkgFlags|flags=\['" | grep -q DEBUGGABLE; then build=debug; else build=release; fi
echo "installed build: $build"

run_tool() {  # run_tool <이름> <extras>
  local name="$1" extras="$2" changed="" cur ran ignored navi
  adb_ logcat -c >/dev/null
  T=60 adb_ shell am start -S -n $PKG/.MainActivity $extras | tail -1
  local end=$(( $(date +%s) + WATCH ))
  while [ "$(date +%s)" -lt "$end" ]; do
    cur=$(vc_snapshot)
    if [ "$cur" != "$before" ] && [ "$cur" != "?" ] && [ -z "$changed" ]; then changed="$cur"; fi
    sleep 2
  done
  ran=$(T=60 adb_ logcat -d -s PleosApp:I | grep -c -E "TEST\||VERIFY\|")
  ignored=$(T=60 adb_ logcat -d -s Shimpyo:W | grep -c "dev tool request ignored")
  # 점검이 보내는 경로 요청 (requestRoute·addWaypoint·cancelRoute). 코치의 조회(getXxx)는 센 것에 넣지 않는다
  navi=$(T=60 adb_ logcat -d | grep "$PKG >> ai.umos" | grep -c -E "requestToNaviProvider\((RequestRoute|AddWaypoint|CancelRoute|RequestRouteWithWaypoints)")
  if [ "$build" = "release" ]; then
    # 화면(MainActivity, LaunchExtras.pick)이 release에서는 이 옵션을 서비스로 넘기지 않으므로 서비스의 'ignored' 로그는
    # 없는 것이 정상이다 (0). 1 이상이면 화면은 넘겼고 서비스(DevHooks)가 막은 것이다. 어느 쪽이든 통과
    if [ "$ran" -eq 0 ] && [ -z "$changed" ] && [ "$navi" -eq 0 ]; then
      echo "OK  $name ignored: no dev tool log, vehicle values unchanged for ${WATCH}s, no route command (reached the service: $ignored)"
    else
      echo "FAIL $name on a release build: dev tool log lines=$ran, route commands=$navi, ignored logged=$ignored, vehicle changed: ${changed:-no}"
      echo "     before: $before"
      fail=1
    fi
  else
    if [ "$ran" -gt 0 ]; then echo "OK  $name ran on the debug build ($ran log lines)"; else echo "FAIL $name did not run on the debug build"; fail=1; fi
  fi
}

run_tool selftest "--ez selftest true"
# 길안내를 바꾸는 요청(route, verify)은 위가 통과했을 때만 보낸다: 막히지 않은 빌드에서 돌리면 지금 경로를 지운다
if [ "$build" = "release" ] && [ "$fail" -eq 0 ]; then
  run_tool "selftest+route" "--ez selftest true --ez route true"
  run_tool verify "--ez verify true"
fi

# 시연 전환은 어느 빌드에서나 된다 (2차 PT 시연)
for name in holiday off; do
  adb_ logcat -c >/dev/null
  T=60 adb_ shell am start -S -n $PKG/.MainActivity --es demo $name | tail -1
  line=""
  for _ in $(seq 1 20); do
    line=$(adb_ logcat -d -s Shimpyo:I | grep "demo started:" | tail -1)
    [ -n "$line" ] && break
    sleep 2
  done
  case "$line" in
    *"demo started: $name"*) echo "OK  --es demo $name: ${line#*Shimpyo : }" ;;
    *) echo "FAIL --es demo $name: ${line:-no 'demo started' line within 40 s}"; fail=1 ;;
  esac
done
[ "$fail" -eq 0 ] && echo "PASS" || echo "FAILED"
exit $fail
