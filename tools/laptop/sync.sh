#!/bin/bash
# 서버의 작업 트리를 노트북 프로젝트 폴더의 repo\ 로 복사 (docs/contest, 빌드 산출물 제외)
set -e
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
tar -C "$ROOT" --exclude=.git --exclude=docs/contest --exclude='*/build' --exclude='*/.gradle' --exclude=local.properties -cf "$TMP/repo.tar" .
scp -q "$TMP/repo.tar" hmg-laptop:hmg-appdev-challenge/repo.tar
ssh hmg-laptop 'Set-Location $env:USERPROFILE\hmg-appdev-challenge; New-Item -ItemType Directory -Force repo | Out-Null; tar -xf repo.tar -C repo; Remove-Item repo.tar; "synced"'
rm -rf "$TMP"
