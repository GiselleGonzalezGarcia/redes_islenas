#!/bin/sh
# Full run of the OCR pipeline (see README.md). Usage: sh run_all.sh [pages]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
PY="$HERE/.venv/bin/python -I $HERE/ocrpipe.py"
PAGES=${1:-}
ARG=""
[ -n "$PAGES" ] && ARG="--pages $PAGES"
$PY extract $ARG
$PY layout $ARG --force
$PY ocr $ARG --force
$PY reconcile $ARG
$PY webp $ARG
$PY report
