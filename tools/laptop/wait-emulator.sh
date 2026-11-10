#!/bin/bash
# 에뮬레이터가 시험할 수 있는 상태가 될 때까지 기다린다 (개발 서버에서 실행).
# 조건: 부팅 완료 + 운전자 프로필(user 10) 활성 + 가동 4분 이상 + 1분 load average가 기준 미만.
# (부팅 직후에는 load average가 아직 오르기 전이라 낮게 나온다. 그래서 가동 시간도 함께 본다)
#
# 부팅 직후에는 시스템 서비스가 한꺼번에 떠서 CPU가 모자란다. 이때 빌드나 앱 실행을 겹치면 carwatchdogd가
# VHAL 응답 지연을 장애로 보고 차량 서비스를 재시작시키고, 시스템이 user 0으로 돌아가 앱 실행이 실패한다.
# 사용: wait-emulator.sh [load 기준, 기본 8] [최대 대기 초, 기본 1200]
set -u
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
MAX_LOAD="${1:-8}"
MIN_UPTIME=240
DEADLINE=$(( $(date +%s) + ${2:-1200} ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  boot=$(ssh hmg-laptop "$ADB shell getprop sys.boot_completed" 2>/dev/null | tr -d '\r\n')
  if [ "$boot" = "1" ]; then
    user=$(ssh hmg-laptop "$ADB shell am get-current-user" 2>/dev/null | tr -d '\r\n')
    load=$(ssh hmg-laptop "$ADB shell uptime" 2>/dev/null | sed 's/.*load average: \([0-9.]*\).*/\1/' | tr -d '\r\n')
    up=$(ssh hmg-laptop "$ADB shell cat /proc/uptime" 2>/dev/null | cut -d. -f1 | tr -d '\r\n')
    echo "$(date +%T) user=$user uptime=${up}s load1=$load"
    if [ "$user" = "10" ] && [ "${up:-0}" -ge "$MIN_UPTIME" ] && awk -v l="$load" -v m="$MAX_LOAD" 'BEGIN{exit !(l<m)}'; then
      echo READY
      exit 0
    fi
  fi
  sleep 20
done
echo "TIMEOUT: emulator not ready"
exit 1
