#!/bin/bash
# 서버의 작업 트리를 노트북 프로젝트 폴더의 repo\ 로 복사 (docs, 빌드 산출물 제외)
# 루트의 .md와 .docx는 뺀다: Windows tar가 한글 파일명을 풀지 못하고, 빌드에도 필요 없다
set -e
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
# 노트북 PowerShell 5.1은 BOM 없는 .ps1을 CP949로 읽어 한글 주석이 다음 줄을 삼킨다(2026-11-11 gradle.ps1의
# env.ps1 불러오기가 주석으로 먹혔다). 모든 .ps1은 UTF-8 BOM으로 저장해야 하므로 복사 전에 확인한다
for f in "$ROOT"/tools/*/*.ps1 "$ROOT"/tools/*/*/*.ps1; do
  [ -f "$f" ] || continue
  if [ "$(head -c3 "$f" | od -An -tx1 | tr -d ' ')" != "efbbbf" ]; then echo "BOM 없음: $f (UTF-8 BOM으로 저장할 것)" >&2; exit 1; fi
done
TMP="$(mktemp -d)"
tar -C "$ROOT" --exclude=.git --exclude=./docs --exclude='*/build' --exclude='*/.gradle' --exclude=local.properties --exclude='./*.md' --exclude='./*.docx' -cf "$TMP/repo.tar" .
scp -q "$TMP/repo.tar" hmg-laptop:hmg-appdev-challenge/repo.tar
ssh hmg-laptop 'Set-Location $env:USERPROFILE\hmg-appdev-challenge; New-Item -ItemType Directory -Force repo | Out-Null; tar -xf repo.tar -C repo; Remove-Item repo.tar; "synced"'
rm -rf "$TMP"
