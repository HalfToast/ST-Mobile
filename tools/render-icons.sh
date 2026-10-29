#!/bin/sh
# Renders tools/st-logo.svg into the launcher, notification and monochrome PNGs under app/src/main/res.
# Check images (data-out starting with "check/") go to ${CHECK_DIR:-/tmp/st-icon-check}, not into the app.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
res="$here/../app/src/main/res"
check=${CHECK_DIR:-/tmp/st-icon-check}
CHROME=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
dom=$("$CHROME" --headless=new --disable-gpu --allow-file-access-from-files \
    --virtual-time-budget=5000 --dump-dom "file://$here/render-icons.html" 2>/dev/null) || true
if ! printf '%s' "$dom" | grep -q 'data-result="PASS"'; then
    echo "render failed: $(printf '%s' "$dom" | grep -o 'data-result="[^"]*"' || echo 'page did not finish')"
    exit 1
fi
printf '%s' "$dom" | grep -o '<img data-out="[^"]*" src="data:image/png;base64,[^"]*"' |
while IFS= read -r tag; do
    name=${tag#*data-out=\"}; name=${name%%\"*}
    data=${tag#*base64,}; data=${data%\"}
    case "$name" in
        check/*) out="$check/${name#check/}" ;;
        *) out="$res/$name" ;;
    esac
    mkdir -p "$(dirname "$out")"
    printf '%s' "$data" | base64 -d > "$out"
done
echo "PASS"
