#!/bin/bash
# 앱 성능 측정 (개발 서버에서 실행). 같은 조건에서 전후를 비교할 때 쓴다.
#   tools/laptop/measure-app.sh <이름>    결과: 표준 출력 한 블록
# 측정 전에 할 일: 에뮬레이터 준비(wait-emulator.sh), vc.sh up · gear Drive · speed 100,
#   tools/laptop/demo-smoke.sh holiday --split (앱을 새로 시작해 portrait 창으로 나눈다)
# 재는 것: 정차 후보 데이터 로딩 시간(앱 시작부터), 앱 CPU(5초 표본 4개 평균, 코어 1개 기준 %),
#   메모리(PSS·Java·Native, MB), 화면 프레임 30초(수, 지연 비율, p50/p90/p95), 내비 요청 60초(종류별 수)
set -u
ADB='C:\Users\User\hmg-appdev-challenge\sdk\platform-tools\adb.exe'
NAME="${1:-run}"
adb_() { timeout "${T:-40}" ssh -o ConnectTimeout=10 hmg-laptop "$ADB $*" 2>&1 | tr -d '\r'; }

pid=$(adb_ shell pidof dev.shimpyo.app)
[ -z "$pid" ] && { echo "app not running"; exit 1; }
echo "== $NAME (pid $pid)"

# 1. 데이터 로딩: 프로세스 시작 → 마지막 로딩 로그
log=$(T=60 adb_ logcat -d -v epoch -s ActivityManager:I Shimpyo:I)
start=$(echo "$log" | grep "Start proc $pid:dev.shimpyo.app" | tail -1 | awk '{print $1}')
loaded=$(echo "$log" | grep -E "stop (table|assets) loaded" | grep " $pid " | tail -1)
loadedAt=$(echo "$loaded" | awk '{print $1}')
if [ -n "$start" ] && [ -n "$loadedAt" ]; then
  echo "load: $(awk -v a="$start" -v b="$loadedAt" 'BEGIN{printf "%.1f", b-a}') s after process start | ${loaded#*Shimpyo : }"
else
  echo "load: ? | ${loaded#*Shimpyo : }"
fi

# 2. 내비 요청 60초 + 화면 프레임 30초 (같이 시작)
adb_ logcat -c >/dev/null
adb_ shell dumpsys gfxinfo dev.shimpyo.app reset >/dev/null
# 3. CPU: 5초 표본 4개 (20초)
cpu=$(T=60 adb_ shell top -b -d 5 -n 5 -p "$pid" | awk -v p="$pid" '$1==p {n++; if (n>1) {s+=$9; c++}} END {if (c) printf "%.1f", s/c; else print "?"}')
echo "cpu: ${cpu}% of one core (avg of 4 x 5 s)"
sleep 10
gfx=$(adb_ shell dumpsys gfxinfo dev.shimpyo.app | grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile" | sed 's/^ *//' | tr '\n' ';')
echo "frames (30 s): $gfx"
mem=$(adb_ shell dumpsys meminfo dev.shimpyo.app | grep -E "TOTAL PSS|Java Heap:|Native Heap:" | awk '/TOTAL PSS/{printf "PSS %.0f MB ", $3/1024} /Java Heap:/{printf "Java %.0f MB ", $3/1024} /Native Heap:/{printf "Native %.0f MB", $3/1024}')
echo "memory: $mem"
sleep 30
navi=$(T=60 adb_ logcat -d | grep "dev.shimpyo.app >> ai.umos" | sed -E 's/.*requestToNaviProvider\(([A-Za-z]+)\).*/\1/' | sort | uniq -c | awk '{printf "%s %s, ", $2, $1}')
echo "navi requests (60 s): ${navi:-none}"
