#!/bin/bash
# Greps the tracked tree for details of one person's setup (the word "tablet", names from a chat,
# notes from an old process ledger, plus the private patterns below) and for words that are
# allowed only in some places (Tailscale, HyperOS/MIUI).
#
# Private patterns: .superpowers/personal-patterns (git-ignored, so it never ships), one extended
# regex per line for details that must not be written in a tracked file, not even here: addresses,
# a Termux user, a phone serial or model. Blank lines and lines starting with # are ignored. When
# the file is absent the check says so and runs with the generic words only.
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
private=.superpowers/personal-patterns
# Names from a chat, the old process ledger, then words allowed only in places.
pattern='HyperOS|MIUI|HalfToast'"'"'s|Ruling [0-9]|Tailscale'
exclude=(':!tools/check-personal.sh' ":!$allow")

personal=
if [ -f "$private" ]; then
    personal=$(grep -v -E '^[[:space:]]*(#|$)' "$private" | paste -s -d '|' - || true)
fi
if [ -z "$personal" ]; then
    echo "Personal-pattern check skipped: no patterns in $private."
fi

hits=$( {
    git grep -n -I -E "$pattern" -- . "${exclude[@]}" || true
    git grep -n -I -i -E 'tablet' -- . "${exclude[@]}" || true
    # The private patterns cover this script and its allowlist too: neither may hold them.
    if [ -n "$personal" ]; then git grep -n -I -E "$personal" -- . || true; fi
} | sort -u -t: -k1,1 -k2,2n )

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
        n++; path[n] = substr(entry, 1, c - 1); re[n] = substr(entry, c + 1); why[n] = reason; used[n] = 0
    }
    close(allowfile)
}
$0 == "" { next }
{
    c1 = index($0, ":"); file = substr($0, 1, c1 - 1)
    rest = substr($0, c1 + 1); c2 = index(rest, ":"); text = substr(rest, c2 + 1)
    ok = 0
    for (i = 1; i <= n; i++) if (path[i] == file && text ~ re[i]) { ok = i; break }
    if (ok) { allowed++; used[ok] = 1; print $0; print "    allowed: " why[ok] }
    else { denied++; print $0; print "    NOT ALLOWED" }
}
END {
    for (i = 1; i <= n; i++) if (!used[i]) printf "warning: allowlist entry matched nothing: %s:%s\n", path[i], re[i]
    printf "\n%d hit(s): %d allowed, %d not allowed.\n", allowed + denied, allowed, denied
    exit (denied > 0 || bad_entries > 0) ? 1 : 0
}'
