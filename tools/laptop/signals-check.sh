#!/bin/bash
# Pleos 차량 서비스가 죽거나 멈춘 뒤에도 차량 신호가 다시 오는지 본다 (개발 서버에서 실행).
#
# 회귀 방지 (2026-11-12 에뮬레이터, 서비스 감사 P12):
# - 서비스가 죽었다 살아나면(`am crash`) Vehicle SDK가 스스로 다시 붙고 리스너도 다시 걸어 준다 (약 1.4초).
# - 서비스가 멈추면(`am force-stop`) SDK는 다시 붙지 않는다: 이벤트가 끊기고 조회는 답이 없다. 예전에는 앱을 다시
#   시작할 때까지 마지막 값이 남았다(문이 열려도 모른다 = 화장실 정차를 못 알아본다). 지금은 SignalCollector가 30초마다
#   값을 다시 읽다가 '모두 실패 + 이벤트 없음'이면 VehicleKit.reconnect()로 새로 붙는다 (약 1분 안).
#
# 본다: 조향각 이벤트(에뮬레이터는 약 3초마다 온다)가 다시 오는지. force-stop은 'vehicle failed' → 'vehicle: connected' 로그도.
# 준비: 앱이 실행 중일 것. Virtual Console heartbeat가 살아 있을 것 (tools/vc/vc.sh up)
# 사용: tools/laptop/signals-check.sh
set -u
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
PKG=dev.shimpyo.app
SVC=ai.pleos.playground.service.vehicle
adb_() { timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB $*" 2>&1 | tr -d '\r'; }
ash_() { local c; c=$(printf '%s' "$*" | sed "s/'/''/g"); timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB shell '$c'" 2>&1 | tr -d '\r'; }

fail=0
[ "$(adb_ get-state)" = "device" ] || { echo "FAIL: emulator not connected"; exit 2; }
pid=$(ash_ "pidof $PKG")
[ -n "$pid" ] || { echo "FAIL: app not running"; exit 2; }
steering() { T=60 adb_ logcat -d | grep " $pid " | grep -c "PERF_STEERING_ANGLE"; }

adb_ logcat -c >/dev/null
sleep 8
[ "$(steering)" -ge 1 ] || { echo "FAIL: no steering events before the test (is the Virtual Console heartbeat alive?)"; exit 2; }

# 1. 죽었다 살아난다: SDK가 스스로 다시 붙는다
adb_ logcat -c >/dev/null
ash_ "am crash --user current $SVC" >/dev/null
sleep 15
n=$(steering)
if [ "$n" -ge 2 ]; then echo "OK  vehicle service crash: $n steering events in the 15 s after it"; else echo "FAIL vehicle service crash: $n steering events in 15 s"; fail=1; fi

# 2. 멈춘다: 앱이 알아채고 새로 붙는다
adb_ logcat -c >/dev/null
ash_ "am force-stop --user current $SVC" >/dev/null
ok=""
for _ in $(seq 1 30); do
  sleep 5
  log=$(T=60 adb_ logcat -d -s Shimpyo:V | grep " $pid ")
  if echo "$log" | grep -q "vehicle failed: all" && echo "$log" | sed -n '/vehicle failed: all/,$p' | grep -q "vehicle: connected"; then ok=1; break; fi
done
if [ -n "$ok" ]; then
  adb_ logcat -c >/dev/null
  sleep 12
  n=$(steering)
  if [ "$n" -ge 2 ]; then echo "OK  vehicle service force-stop: reconnected, $n steering events in the 12 s after"; else echo "FAIL vehicle service force-stop: reconnected but only $n steering events after"; fail=1; fi
else
  echo "FAIL vehicle service force-stop: no 'vehicle failed' -> 'vehicle: connected' within 150 s"
  T=60 adb_ logcat -d -s Shimpyo:V PleosKit:V | grep " $pid " | tail -6
  fail=1
fi
[ "$fail" -eq 0 ] && echo "PASS" || echo "FAILED"
exit $fail
