#!/bin/bash
# 기획서 docx를 노트북의 Microsoft Word로 PDF로 내보내 쪽 PNG를 만든다 (개발 서버에서 실행).
# LibreOffice 미리보기(build_docx.py --preview)는 '맑은 고딕'이 없어 Noto로 바꿔 그려 쪽 수가 다르다
# (2026-11-15: LibreOffice 23쪽, Word 27쪽, 그림이 다음 쪽으로 밀려 빈 공간). 심사위원은 Word로 연다.
# 사용: tools/laptop/word-preview.sh [docx] [출력 폴더]
set -euo pipefail
DOCX="${1:-contest-application-consent.docx}"
OUT="${2:-build/word-preview}"
L='C:\Users\User\hmg-appdev-challenge\logs'
name=$(basename "$DOCX" .docx)-$(date +%H%M%S)
mkdir -p "$OUT"
scp -q tools/laptop/word2pdf.ps1 "hmg-laptop:C:/Users/User/hmg-appdev-challenge/logs/word2pdf.ps1"
scp -q "$DOCX" "hmg-laptop:C:/Users/User/hmg-appdev-challenge/logs/$name.docx"
ssh hmg-laptop "powershell -ExecutionPolicy Bypass -File $L\\word2pdf.ps1 -In $L\\$name.docx -Out $L\\$name.pdf" | tr -d '\r'
scp -q "hmg-laptop:C:/Users/User/hmg-appdev-challenge/logs/$name.pdf" "$OUT/word.pdf"
rm -f "$OUT"/p-*.png
pdftoppm -r 70 -png "$OUT/word.pdf" "$OUT/p"
echo "$OUT/word.pdf ($(ls "$OUT"/p-*.png | wc -l) pages)"
