#!/bin/sh
# Runs a self-checking HTML test page in headless Chrome.
# Exit 0 iff the final DOM carries data-result="PASS"; otherwise prints the result and exits 1.
set -eu
file=${1:?usage: tools/web-test.sh <file.html>}
CHROME=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
abs="$(cd "$(dirname "$file")" && pwd)/$(basename "$file")"
dom=$("$CHROME" --headless=new --disable-gpu --allow-file-access-from-files \
    --virtual-time-budget=5000 --dump-dom "file://$abs" 2>/dev/null) || true
if printf '%s' "$dom" | grep -q 'data-result="PASS"'; then
    echo "PASS"
    exit 0
fi
result=$(printf '%s' "$dom" | grep -o 'data-result="[^"]*"' || true)
echo "${result:-no result (page did not finish)}"
exit 1
