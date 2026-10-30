#!/bin/bash
# Greps the tracked tree for details of one person's setup (an address, a Termux user, a phone
# serial or model, the word "tablet", names from a chat, notes from an old process ledger) and
# for words that are allowed only in some places (Tailscale, HyperOS/MIUI).
# Prints every hit, marked "allowed: <reason>" or "NOT ALLOWED". Exit 0 iff every hit is allowed.
#
# Allowlist: tools/check-personal.allow, one entry per line:
#     <path>:<regex>   # <reason>
# <path> is a tracked file's path, exactly; <regex> (extended) must match the hit's line text.
# The reason is required. Blank lines and lines starting with # are ignored.
# Runs under bash 3.2 (macOS) and later; needs git and awk.
set -eu
cd "$(dirname "$0")/.."

allow=tools/check-personal.allow
pattern='100\.101\.102\.103|u0_a123|<serial>|phone|HyperOS|MIUI|HalfToast'"'"'s|Seraphina|Aria|Ezra|Ruling [0-9]|Tailscale'
exclude=(':!tools/check-personal.sh' ":!$allow")

hits=$( {
    git grep -n -I -E "$pattern" -- . "${exclude[@]}" || true
    git grep -n -I -i -E 'tablet' -- . "${exclude[@]}" || true
} | sort -u -t: -k1,1 -k2,2n )

if [ -z "$hits" ]; then
    echo "No hits."
    exit 0
fi

[ -f "$allow" ] || : > "$allow"

printf '%s\n' "$hits" | awk -v allowfile="$allow" '
BEGIN {
    n = 0; bad_entries = 0
    while ((getline line < allowfile) > 0) {
        if (line ~ /^[ \t]*(#|$)/) continue
        r = index(line, " # ")
        if (r == 0) { print "allowlist entry without a \" # reason\": " line > "/dev/stderr"; bad_entries++; continue }
        entry = substr(line, 1, r - 1); reason = substr(line, r + 3)
        sub(/[ \t]+$/, "", entry); sub(/^[ \t]+/, "", reason)
        c = index(entry, ":")
        if (c == 0) { print "allowlist entry without \"path:regex\": " line > "/dev/stderr"; bad_entries++; continue }
        n++; path[n] = substr(entry, 1, c - 1); re[n] = substr(entry, c + 1); why[n] = reason
    }
    close(allowfile)
}
{
    c1 = index($0, ":"); file = substr($0, 1, c1 - 1)
    rest = substr($0, c1 + 1); c2 = index(rest, ":"); text = substr(rest, c2 + 1)
    ok = 0
    for (i = 1; i <= n; i++) if (path[i] == file && text ~ re[i]) { ok = i; break }
    if (ok) { allowed++; print $0; print "    allowed: " why[ok] }
    else { denied++; print $0; print "    NOT ALLOWED" }
}
END {
    printf "\n%d hit(s): %d allowed, %d not allowed.\n", allowed + denied, allowed, denied
    exit (denied > 0 || bad_entries > 0) ? 1 : 0
}'
