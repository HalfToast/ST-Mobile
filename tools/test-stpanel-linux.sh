#!/usr/bin/env bash
# Smoke tests for server/stpanel and the app's probe scripts (app/src/main/assets/probes) on Linux,
# in Docker containers:
#   debian:stable-slim (GNU userland, procps) and alpine (busybox userland, with bash).
#
#   tools/test-stpanel-linux.sh            run every image
#   tools/test-stpanel-linux.sh alpine     run the images given
#
# The repo is mounted read-only. Before the containers start, the app's own commands for status,
# start, the dashboard and stop are exported to app/build/linux-smoke (the ActionScriptExport unit
# test, through Gradle); inside, they run under dash as the app sends them (SKIP if the export failed).
# A fake node (argv[0] "node", idles until TERM) stands in for
# SillyTavern; the whitelist checks use the container's real node with a yaml package mounted
# 2.x from the host (~/Developer/*/node_modules/yaml or app/build/test-node/node_modules/yaml) and
# are skipped, with a SKIP line, when either is missing. Prints PASS/FAIL/SKIP per check and exits
# non-zero if any check fails or a container can't be set up.

if [ "$1" != "--inside" ]; then
    repo=$(cd "$(dirname "$0")/.." && pwd)
    images=("$@")
    [ ${#images[@]} -gt 0 ] || images=(debian:stable-slim alpine:latest)
    yaml=""
    for d in "$HOME"/Developer/*/node_modules/yaml "$repo/app/build/test-node/node_modules/yaml"; do
        # yaml 2.x (SillyTavern's; stpanel uses its parseDocument API)
        grep -Eq '"version"[[:space:]]*:[[:space:]]*"2\.' "$d/package.json" 2>/dev/null && { yaml=$(cd "$d" && pwd -P); break; }
    done
    rm -rf "$repo/app/build/linux-smoke"
    echo "Exporting the app's commands (Gradle)..."
    if ! (cd "$repo" && ./gradlew -q :app:testDebugUnitTest --tests 'dev.halftoast.stmobile.panel.ActionScriptExport' --rerun >/dev/null 2>&1); then
        echo "warning: the export failed; the dash checks will be skipped" >&2
    fi
    status=0
    for image in "${images[@]}"; do
        case "$image" in
            debian*|ubuntu*)
                setup='apt-get update -qq >/dev/null && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends procps iproute2 nodejs python3 >/dev/null 2>&1' ;;
            alpine*)
                setup='apk add -q --no-cache bash dash nodejs python3 >/dev/null 2>&1' ;;
            *)
                echo "unknown image $image (debian*, ubuntu* or alpine*)" >&2; exit 2 ;;
        esac
        mounts=(-v "$repo:/repo:ro")
        [ -n "$yaml" ] && mounts+=(-v "$yaml:/yaml:ro")
        echo "=== $image ==="
        if ! docker run --rm --init "${mounts[@]}" "$image" sh -c "$setup || { echo 'FAIL  container setup' >&2; exit 3; }; exec bash /repo/tools/test-stpanel-linux.sh --inside"; then
            status=1
        fi
        echo ""
    done
    [ "$status" -eq 0 ] && echo "ALL PASSED" || echo "SOME CHECKS FAILED"
    exit $status
fi

# ---------------------------------------------------------------- inside the container
STPANEL=/repo/server/stpanel
PROBES=/repo/app/src/main/assets/probes
W=/tmp/w
ST="$W/My Apps/SillyTavern"
OTHER="$W/Other/SillyTavern"
FAKEBIN="$W/bin"
WATCHDOG="Watchdog reviving in 2 seconds"
FAILS=0
ESC=$(printf '\033')

pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1${2:+  ($2)}"; FAILS=$((FAILS + 1)); }
skip() { echo "SKIP  $1${2:+  ($2)}"; }
check() { # check <name> <command...>
    local name=$1
    shift
    if "$@"; then pass "$name"; else fail "$name"; fi
}

echo "$(bash --version | head -n 1); ps: $(readlink -f "$(command -v ps)" 2>/dev/null)"

make_tree() {
    mkdir -p "$1/data/default-user/characters"
    : > "$1/server.js"
    printf 'port: 8123\nwhitelistMode: false\n' > "$1/config.yaml"
}

mkdir -p "$FAKEBIN"
cat > "$FAKEBIN/node" <<'EOF'
#!/bin/bash
case "$*" in *server.js*) ;; *) exit 0 ;; esac
for a in "$@"; do last=$a; done
for a in "$@"; do printf '%s\n' "$a"; done > "$(dirname "$last")/node.args"
${NODE_START_DELAY:+sleep "$NODE_START_DELAY"}
# Its own path (under $W) stays in its args, so the final pkill -f "$W" finds it.
exec -a node /bin/bash -c 'trap "kill \$s 2>/dev/null; exit 0" TERM INT; while :; do sleep 1 & s=$!; wait $s; done' "$0" "$@"
EOF
chmod +x "$FAKEBIN/node"
make_tree "$ST"
make_tree "$OTHER"

# st [VAR=value ...] <args...>: stpanel with a clean environment and the fake node first on PATH.
st() {
    local envs=()
    while [ $# -gt 0 ]; do
        case "$1" in [A-Z_]*=*) envs+=("$1"); shift ;; *) break ;; esac
    done
    env -i PATH="$FAKEBIN:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" HOME="$W" ST_DIR="$ST" \
        STPANEL_NODE_DIRS="" "${envs[@]}" bash "$STPANEL" "$@"
}

# Pids of live, non-zombie processes whose args contain every argument.
procs_with() {
    local f pid args n state ok
    for f in /proc/[0-9]*; do
        pid=${f#/proc/}
        args=$(tr '\0' ' ' < "$f/cmdline" 2>/dev/null) || continue
        [ -n "$args" ] || continue
        state=$(sed 's/.*) //' "$f/stat" 2>/dev/null | cut -c 1)
        [ "$state" = "Z" ] && continue
        ok=1
        for n in "$@"; do
            case "$args" in *"$n"*) ;; *) ok=0 ;; esac
        done
        [ "$ok" -eq 1 ] && echo "$pid"
    done
}
count() { procs_with "$@" | wc -l | tr -d ' '; }
alive() { [ -d "/proc/$1" ] && [ "$(sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | cut -c 1)" != "Z" ]; }
no_ansi() { case "$1" in *"$ESC"*) return 1 ;; esac; return 0; }

# ---- start / status / stop
out=$(st start); rc=$?
check "start exits 0" [ "$rc" -eq 0 ]
pid=$(tr -dc '0-9' < "$ST/sillytavern.pid" 2>/dev/null)
check "start writes the pid file" [ -n "$pid" ]
check "the pid file's server is alive" alive "$pid"
check "start writes the log" grep -q "Starting SillyTavern" "$ST/sillytavern.log"
sleep 0.3
check "node runs \$ST_DIR/server.js with --max-old-space-size" \
    grep -q -- "--max-old-space-size=2048" "$ST/node.args"
check "node is given the absolute server.js" grep -qx "$ST/server.js" "$ST/node.args"
out=$(st status)
check "status says ONLINE with the pid" grep -q "ONLINE (PID: $pid)" <<< "$out"
check "status has no ANSI when not a terminal" no_ansi "$out"
check "status prints no empty battery" eval '! grep -Eq "Battery: *%|N/A%" <<< "$out"'
out=$(st urls)
if command -v ip >/dev/null 2>&1 || [ -x /sbin/ifconfig ]; then
    check "urls finds the container's eth0 address" grep -Eq "Local Wi-Fi: +http://[0-9.]+:8123" <<< "$out"
else
    skip "urls finds the container's eth0 address" "no ip or ifconfig"
fi
if [ -x /sbin/ifconfig ]; then
    out=$(env -i PATH="$FAKEBIN:/usr/bin:/bin" HOME="$W" ST_DIR="$ST" bash "$STPANEL" urls)
    check "urls falls back to ifconfig without ip on PATH" grep -Eq "Local Wi-Fi: +http://[0-9.]+:8123" <<< "$out"
else
    skip "urls falls back to ifconfig without ip on PATH" "no /sbin/ifconfig"
fi
out=$(st stop); rc=$?
check "stop exits 0" [ "$rc" -eq 0 ]
check "stop ends the server" eval '! alive "$pid"'
check "stop removes the pid file" [ ! -e "$ST/sillytavern.pid" ]
check "stop removes the stop marker" [ ! -e "$ST/.stop_requested" ]
check "stop ends the supervisor" [ "$(count "$WATCHDOG" "$ST")" -eq 0 ]

# ---- restart
st start >/dev/null
first=$(tr -dc '0-9' < "$ST/sillytavern.pid")
out=$(st restart); rc=$?
second=$(tr -dc '0-9' < "$ST/sillytavern.pid" 2>/dev/null)
check "restart exits 0" [ "$rc" -eq 0 ]
check "restart starts a new server" eval '[ -n "$second" ] && [ "$first" != "$second" ]'
check "restart leaves one server" [ "$(count "$ST/server.js")" -eq 1 ]
check "restart leaves one supervisor" [ "$(count "$WATCHDOG" "$ST")" -eq 1 ]
st stop >/dev/null

# ---- a server started by hand from the folder, no pid file
(cd "$ST" && exec "$FAKEBIN/node" --max-old-space-size=2048 server.js) >/dev/null 2>&1 &
byhand=$!
sleep 0.5
check "status finds a server by its working folder" grep -q "PID: $byhand" <<< "$(st status)"
st stop >/dev/null
sleep 0.2
check "stop ends it" eval '! alive "$byhand"'

# ---- startst's supervisor loop, its server still starting (not yet node) when stop runs
loop='trap "" HUP
cd "$ST_DIR" || exit 1
while [ ! -f .stop_requested ]; do
    node --max-old-space-size=2048 server.js >> sillytavern.log 2>&1 &
    echo $! > sillytavern.pid
    wait $!
    if [ -f .stop_requested ]; then rm -f .stop_requested sillytavern.pid; break; fi
    echo "Watchdog reviving in 2 seconds..." >> sillytavern.log
    sleep 2
done'
rm -f "$ST/sillytavern.pid"
(env NODE_START_DELAY=1 PATH="$FAKEBIN:/usr/bin:/bin" bash -c "ST_DIR=\"$ST\"; $loop") >/dev/null 2>&1 &
sup=$!
for i in 1 2 3 4 5 6 7 8 9 10; do [ -s "$ST/sillytavern.pid" ] && break; sleep 0.1; done
starting=$(tr -dc '0-9' < "$ST/sillytavern.pid" 2>/dev/null)
st stop >/dev/null
sleep 1.5
check "stop ends a server its supervisor is still starting" eval '[ -n "$starting" ] && ! alive "$starting"'
check "stop ends startst's supervisor loop" eval '! alive "$sup"'

# ---- other folders and processes that only mention node are left alone
(cd "$OTHER" && exec "$FAKEBIN/node" --max-old-space-size=2048 server.js) >/dev/null 2>&1 &
other1=$!
# Started from this folder, but given another folder's absolute server.js: still not this one.
(cd "$ST" && exec "$FAKEBIN/node" --max-old-space-size=2048 "$OTHER/server.js") >/dev/null 2>&1 &
other2=$!
(cd "$ST" && exec sh -c 'while :; do sleep 1; done' nodemon server.js) >/dev/null 2>&1 &
mention=$!
sleep 0.5
check "status ignores other folders' servers" grep -q "OFFLINE" <<< "$(st status)"
st stop >/dev/null
check "stop leaves another folder's server (by cwd)" alive "$other1"
check "stop leaves another folder's server (by args)" alive "$other2"
check "stop leaves a non-node process that mentions node" alive "$mention"
kill "$other1" "$other2" "$mention" 2>/dev/null

# ---- backup
echo png > "$ST/data/default-user/characters/Seraphina.png"
mkdir -p "$ST/data/default-user/backups" && echo '{}' > "$ST/data/default-user/backups/chat_old.jsonl"
out=$(st backup); rc=$?
check "backup exits 0" [ "$rc" -eq 0 ]
check "backup reports success and size" grep -q "Archive Size:" <<< "$out"
archive=$(ls "$ST/backups" 2>/dev/null | head -n 1)
check "backup is named st_backup_YYYYmmdd_HHMMSS.tar.gz" eval '[[ "$archive" =~ ^st_backup_[0-9]{8}_[0-9]{6}\.tar\.gz$ ]]'
list=$(tar -tzf "$ST/backups/$archive" 2>/dev/null)
check "backup holds the user folder" grep -q "^data/default-user/characters/Seraphina.png" <<< "$list"
check "backup holds config.yaml" grep -qx "config.yaml" <<< "$list"
check "backup leaves out the user's backups/" eval '! grep -q "^data/default-user/backups" <<< "$list"'
rm -rf "$ST/backups"
mkdir -p "$ST/data/alice/chats" && echo '{}' > "$ST/data/alice/chats/x.jsonl"
st ST_USER=alice backup >/dev/null
list=$(tar -tzf "$ST/backups/$(ls "$ST/backups" | head -n 1)" 2>/dev/null)
check "backup with ST_USER=alice holds data/alice" grep -q "^data/alice/chats/x.jsonl" <<< "$list"
check "backup with ST_USER=alice leaves out default-user" eval '! grep -q "^data/default-user" <<< "$list"'
st ST_USER=nobody backup >/dev/null 2>&1
check "backup fails without the user folder" [ $? -ne 0 ]

# ---- clean
mkdir -p "$ST/data/_cache" && echo x > "$ST/data/_cache/x"
printf 'n\n' | st clean >/dev/null
check "clean n keeps the cache" [ -e "$ST/data/_cache/x" ]
out=$(printf 'y\n' | st clean)
check "clean y empties the cache" eval '[ -d "$ST/data/_cache" ] && [ -z "$(ls -A "$ST/data/_cache")" ]'
check "clean y says so" grep -q "Cache successfully cleared" <<< "$out"
check "clean has no ANSI when not a terminal" no_ansi "$out"

# ---- update
st update >/dev/null 2>&1
check "update fails outside a git checkout" [ $? -ne 0 ]

# ---- stats
if command -v python3 >/dev/null 2>&1; then
    out=$(st stats); rc=$?
    check "stats exits 0" [ "$rc" -eq 0 ]
    check "stats lists the character" grep -q "Seraphina" <<< "$out"
    check "stats has no ANSI when not a terminal" no_ansi "$out"
else
    skip "stats" "no python3"
fi

# ---- the app's probe scripts, run as the app does: `env ST_DIR='~/…' ST_USER=… bash -s` (python3 -)
probe() { # probe <interpreter> <script> [VAR=value ...]
    local interp=$1 script=$2
    shift 2
    # shellcheck disable=SC2086
    env -i PATH="/usr/local/bin:/usr/bin:/bin" HOME="$W" TMPDIR="$W/tmp" "$@" $interp < "$PROBES/$script"
}
mkdir -p "$W/tmp"
ls_count=$(ls "$ST/data/default-user/characters" | wc -l | tr -d ' ')
out=$(probe "bash -s" dashboard.sh ST_DIR="~/My Apps/SillyTavern" ST_USER=default-user 2>&1)
check "dashboard: offline in a folder with a space" grep -qx "online=0" <<< "$out"
check "dashboard: counts that folder's characters" grep -qx "chars=$ls_count" <<< "$out"
check "dashboard: disk_free" grep -Eq "^disk_free=." <<< "$out"
if ls /sys/class/power_supply/*/capacity >/dev/null 2>&1; then
    check "dashboard: battery level" grep -Eq "^bat_level=[0-9]+$" <<< "$out"
else
    check "dashboard: no battery line without a battery" eval '! grep -q "^bat_" <<< "$out"'
fi
check "dashboard: KEY=VALUE lines only" eval '! grep -Ev "^[a-z_]+=" <<< "$out" | grep -q .'
(cd "$ST" && exec "$FAKEBIN/node" --max-old-space-size=2048 server.js) >/dev/null 2>&1 &
byhand=$!
(cd "$OTHER" && exec "$FAKEBIN/node" --max-old-space-size=2048 server.js) >/dev/null 2>&1 &
other1=$!
sleep 0.5
out=$(probe "bash -s" dashboard.sh ST_DIR="~/My Apps/SillyTavern" ST_USER=default-user 2>&1)
check "dashboard: online with the folder's server" grep -qx "online=1" <<< "$out"
check "dashboard: its pid" grep -qx "pid=$byhand" <<< "$out"
if [ -n "$(ps -p 1 -o etime= 2>/dev/null)" ]; then
    check "dashboard: its uptime" grep -Eq "^etime=.+" <<< "$out"
else
    skip "dashboard: its uptime" "ps has no -p"
fi
kill "$byhand" 2>/dev/null
sleep 0.3
out=$(probe "bash -s" dashboard.sh ST_DIR="~/My Apps/SillyTavern" ST_USER=default-user 2>&1)
check "dashboard: another folder's server is not this one" grep -qx "online=0" <<< "$out"
kill "$other1" 2>/dev/null
if command -v python3 >/dev/null 2>&1; then
    out=$(probe "python3 -" stats.py ST_DIR="~/My Apps/SillyTavern" ST_USER=default-user); rc=$?
    check "stats.py exits 0" [ "$rc" -eq 0 ]
    check "stats.py lists the character" grep -q "^Seraphina	" <<< "$out"
else
    skip "stats.py" "no python3"
fi

# ---- whitelist (the container's real node)
real_node=""
for d in /usr/bin/node /usr/local/bin/node; do [ -x "$d" ] && { real_node=$d; break; }; done
if [ -z "$real_node" ]; then
    skip "whitelist" "no node in the container"
elif [ ! -f /yaml/package.json ]; then
    skip "whitelist" "no yaml package mounted"
else
    mkdir -p "$ST/node_modules" && ln -s /yaml "$ST/node_modules/yaml"
    wl() { (cd "$W" && env -i PATH="/usr/local/bin:/usr/bin:/bin" HOME="$W" ST_DIR="$ST" bash "$STPANEL" whitelist "$@"); }
    cfg=$(printf '# settings\nport: 8123\nwhitelistMode: true\nwhitelist:\n  - 127.0.0.1\n# keep this comment\n')
    printf '%s\n' "$cfg" > "$ST/config.yaml"
    out=$(printf '2\n192.168.1.20\n' | wl); rc=$?
    check "whitelist menu 2 adds an address" eval '[ "$rc" -eq 0 ] && grep -q -- "- 192.168.1.20" "$ST/config.yaml"'
    check "whitelist add keeps comments" grep -q "# keep this comment" "$ST/config.yaml"
    check "whitelist output has no ANSI when not a terminal" no_ansi "$out"
    printf '%s\n' "$cfg" > "$ST/config.yaml"
    out=$(printf '2\n1.2.3.4"); require("fs").writeFileSync("pwned","x"); ("\n' | wl 2>&1); rc=$?
    check "whitelist rejects an injection" [ "$rc" -ne 0 ]
    check "the injection changed nothing" [ "$(cat "$ST/config.yaml")" = "$cfg" ]
    check "the injection wrote no file" [ -z "$(find "$W" -name pwned 2>/dev/null)" ]
    out=$(printf '1\n' | wl)
    check "whitelist menu 1 toggles the mode" grep -q "whitelistMode: false" "$ST/config.yaml"
    wl mode on >/dev/null && wl mode on >/dev/null
    check "whitelist mode on is absolute" grep -q "whitelistMode: true" "$ST/config.yaml"
    wl add 10.0.0.0/8 >/dev/null && wl add fd00::1 >/dev/null
    check "whitelist add takes CIDR and IPv6" eval 'grep -q -- "- 10.0.0.0/8" "$ST/config.yaml" && grep -q "fd00::1" "$ST/config.yaml"'
    wl add 256.1.1.1 >/dev/null 2>&1
    check "whitelist add rejects 256.1.1.1" [ $? -ne 0 ]
    printf '%s\n' "$cfg" > "$ST/config.yaml"
    out=$(probe "bash -s" whitelist.sh ST_DIR="~/My Apps/SillyTavern" 2>&1); rc=$?
    check "whitelist.sh reads the mode and entries in a folder with a space" \
        eval '[ "$rc" -eq 0 ] && [ "$out" = "$(printf "mode=true\nentry=127.0.0.1")" ]'
fi

# ---- the app's own commands under dash: `<command line>` run by dash -c, its script on stdin,
# with sh being dash (Debian's /bin/sh is dash, Termux's sh too; Alpine's is busybox, so a link).
EXPORT=/repo/app/build/linux-smoke
if ! command -v dash >/dev/null 2>&1; then
    skip "dash: the app's commands" "no dash"
elif [ ! -f "$EXPORT/action-stop.cmd" ]; then
    skip "dash: the app's commands" "not exported (see the warning before the containers)"
else
    rm -f "$ST/sillytavern.pid"
    mkdir -p "$W/dashbin" && ln -sf "$(command -v dash)" "$W/dashbin/sh"
    app() { # app <name>
        env -i PATH="$W/dashbin:$FAKEBIN:/usr/local/bin:/usr/bin:/bin" HOME="$W" TMPDIR="$W/tmp" \
            dash -c "$(cat "$EXPORT/$1.cmd")" < "$EXPORT/$1.stdin"
    }
    out=$(app action-status 2>&1); rc=$?
    check "dash: status exits 0 and says OFFLINE" eval '[ "$rc" -eq 0 ] && grep -q "OFFLINE" <<< "$out"'
    out=$(app action-start 2>&1); rc=$?
    check "dash: start exits 0" [ "$rc" -eq 0 ]
    pid=$(tr -dc '0-9' < "$ST/sillytavern.pid" 2>/dev/null)
    check "dash: start's server is alive" eval '[ -n "$pid" ] && alive "$pid"'
    sleep 0.3
    out=$(app dashboard 2>&1); rc=$?
    check "dash: dashboard says online with that pid" \
        eval '[ "$rc" -eq 0 ] && grep -qx "online=1" <<< "$out" && grep -qx "pid=$pid" <<< "$out"'
    out=$(app action-stop 2>&1); rc=$?
    check "dash: stop exits 0" [ "$rc" -eq 0 ]
    check "dash: stop ends the server" eval '[ -n "$pid" ] && ! alive "$pid"'
    check "dash: stop ends the supervisor" [ "$(count "$WATCHDOG" "$ST")" -eq 0 ]
fi

pkill -f "$W" 2>/dev/null
echo ""
if [ "$FAILS" -eq 0 ]; then echo "all checks passed"; else echo "$FAILS check(s) failed"; fi
[ "$FAILS" -eq 0 ]
