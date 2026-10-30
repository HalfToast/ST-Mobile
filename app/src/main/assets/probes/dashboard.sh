#!/usr/bin/env bash
# Read-only dashboard probe for ST Mobile. Prints KEY=VALUE lines.
# Mirrors stpanel's get_sillytavern_pid / get_system_info / get_asset_counts / get_git_info.
# The only writes are the ones stpanel itself does (.git/.last_fetch_check and a background `git fetch`),
# and the app's own files in $TMPDIR: removing stale action marks, and a 60 s copy of the slow lines
# (see Probes.wrapDetached).
# Runs on Termux, Linux/WSL and macOS (bash 3.2 or newer, no /proc, no timeout).
# Environment: ST_DIR (a leading ~/ is expanded; default: Termux's SillyTavern if it exists, else
# ~/SillyTavern) and ST_USER (default default-user), as in stpanel.

# ---- folders: twin of server/stpanel's
TERMUX_HOME="/data/data/com.termux/files/home"
if [ -n "$ST_DIR" ]; then
    case "$ST_DIR" in
        "~") ST_DIR="$HOME" ;;
        "~/"*) ST_DIR="${HOME}/${ST_DIR#"~/"}" ;;
    esac
    while [ "${ST_DIR%/}" != "$ST_DIR" ] && [ "$ST_DIR" != "/" ]; do ST_DIR="${ST_DIR%/}"; done
elif [ -d "$TERMUX_HOME/SillyTavern" ]; then
    ST_DIR="$TERMUX_HOME/SillyTavern"
else
    ST_DIR="${HOME}/SillyTavern"
fi
ST_USER="${ST_USER:-default-user}"
# The same folder with symlinks resolved, as process working directories report it.
ST_DIR_REAL=$(cd "$ST_DIR" 2>/dev/null && pwd -P)
[ -z "$ST_DIR_REAL" ] && ST_DIR_REAL="$ST_DIR"

DATA_DIR="${ST_DIR}/data"
USER_DIR="${DATA_DIR}/${ST_USER}"
LOG_FILE="${ST_DIR}/sillytavern.log"
PID_FILE="${ST_DIR}/sillytavern.pid"
ACCESS_LOG="${DATA_DIR}/access.log"
CONFIG_FILE="${ST_DIR}/config.yaml"
BACKUP_DIR="${ST_DIR}/backups"
WATCHDOG_TEXT="Watchdog reviving in 2 seconds"

# ---- processes: twin of server/stpanel's (pid_alive … get_sillytavern_pid); keep them in step.
# Only this folder's server counts: the pid file's process if it is one, else a node process
# (argv[0] node) running <ST_DIR>/server.js or started from <ST_DIR>.

# Alive and not a zombie.
pid_alive() {
    local state
    [ -n "$1" ] && kill -0 "$1" 2>/dev/null || return 1
    if [ -r "/proc/$1/stat" ]; then
        # The state follows the parenthesised command name: "<pid> (<comm>) <state> ...".
        state=$(sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | cut -c 1)
    else
        state=$(ps -p "$1" -o stat= 2>/dev/null)
    fi
    case "$state" in
        *Z*) return 1 ;;
    esac
    return 0
}

proc_args() {
    if [ -r "/proc/$1/cmdline" ]; then
        tr '\0' ' ' < "/proc/$1/cmdline" 2>/dev/null
    else
        ps -p "$1" -ww -o args= 2>/dev/null
    fi
}

# The program name the process was started as (argv[0]).
proc_argv0() {
    if [ -r "/proc/$1/cmdline" ]; then
        tr '\0' '\n' < "/proc/$1/cmdline" 2>/dev/null | head -n 1
    else
        # macOS: comm is the whole argv[0], spaces in its path included (args can't tell them apart).
        ps -p "$1" -o comm= 2>/dev/null
    fi
}

proc_cwd() {
    local lsof_bin
    if [ -d "/proc/$1" ]; then
        readlink "/proc/$1/cwd" 2>/dev/null
        return
    fi
    # lsof is in /usr/sbin on macOS, often missing from a non-interactive PATH.
    lsof_bin=$(command -v lsof 2>/dev/null)
    [ -z "$lsof_bin" ] && [ -x /usr/sbin/lsof ] && lsof_bin=/usr/sbin/lsof
    [ -n "$lsof_bin" ] && "$lsof_bin" -a -d cwd -p "$1" -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
}

# Pids of processes whose args match the extended regex $1 (to be checked further).
candidate_pids() {
    local f
    if command -v pgrep >/dev/null 2>&1; then
        pgrep -f "$1" 2>/dev/null
    elif [ -d /proc/self ]; then
        for f in /proc/[0-9]*; do
            tr '\0' ' ' < "$f/cmdline" 2>/dev/null | grep -qE -- "$1" && echo "${f#/proc/}"
        done
    else
        ps -A -o pid= -o args= 2>/dev/null | grep -E -- "$1" | awk '{print $1}'
    fi
}

# A node process serving this folder's server.js (never a supervisor loop).
is_st_server() {
    local args argv0 cwd d
    argv0=$(proc_argv0 "$1")
    case "${argv0##*/}" in
        node|nodejs) ;;
        *) return 1 ;;
    esac
    args=" $(proc_args "$1") "
    case "$args" in
        *"$WATCHDOG_TEXT"*) return 1 ;;
        *server.js*) ;;
        *) return 1 ;;
    esac
    for d in "$ST_DIR" "$ST_DIR_REAL"; do
        case "$args" in
            *" $d/server.js "*) return 0 ;;
        esac
    done
    cwd=$(proc_cwd "$1")
    [ -n "$cwd" ] && { [ "$cwd" = "$ST_DIR" ] || [ "$cwd" = "$ST_DIR_REAL" ]; }
}

pidfile_pid() {
    local p
    [ -f "$PID_FILE" ] || return 0
    p=$(tr -dc '0-9' < "$PID_FILE" 2>/dev/null)
    [ -n "$p" ] || return 0
    pid_alive "$p" || return 0
    # A recycled pid now belonging to something else is not the server.
    is_st_server "$p" && echo "$p"
    return 0
}

get_sillytavern_pid() {
    local p
    p=$(pidfile_pid)
    if [ -n "$p" ]; then
        echo "$p"
        return
    fi
    for p in $(candidate_pids 'server\.js'); do
        [ "$p" = "$$" ] && continue
        pid_alive "$p" || continue
        if is_st_server "$p"; then
            echo "$p"
            return
        fi
    done
}

get_port() {
    local port
    port=$(grep -E '^port:[[:space:]]*[0-9]+' "$CONFIG_FILE" 2>/dev/null | head -n 1 | awk '{print $2}' | tr -dc '0-9')
    [ -z "$port" ] && port="8000"
    echo "$port"
}

# ---- system info: twin of server/stpanel's find_tool and battery_info.

# A command from PATH or, on macOS, from the sbin folders a minimal PATH leaves out.
find_tool() {
    local d
    command -v "$1" 2>/dev/null && return 0
    for d in /usr/sbin /sbin /usr/bin; do
        [ -x "$d/$1" ] && { echo "$d/$1"; return 0; }
    done
    return 1
}

# "level|status" of the battery, nothing when there is none (or it can't be read).
# Termux: /sys/class/power_supply/battery; Linux/WSL: the first BAT*; macOS: pmset.
battery_info() {
    local d level status line pmset_bin
    for d in /sys/class/power_supply/battery /sys/class/power_supply/BAT*; do
        [ -r "$d/capacity" ] || continue
        level=$(tr -dc '0-9' < "$d/capacity" 2>/dev/null)
        [ -n "$level" ] || continue
        status=$(head -n 1 "$d/status" 2>/dev/null)
        echo "${level}|${status}"
        return 0
    done
    pmset_bin=$(find_tool pmset) || return 0
    # " -InternalBattery-0 (id=...)	80%; discharging; 4:10 remaining present: true"
    line=$("$pmset_bin" -g batt 2>/dev/null | sed -n 's/.*[[:space:]]\([0-9][0-9]*\)%; *\([^;]*\);.*/\1|\2/p' | head -n 1)
    [ -n "$line" ] || return 0
    level=${line%%|*}
    case "${line#*|}" in
        charged) status="Full" ;;
        charging|"finishing charge") status="Charging" ;;
        discharging) status="Discharging" ;;
        "AC attached") status="Not charging" ;;
        *) status=${line#*|} ;;
    esac
    echo "${level}|${status}"
}

# Single-line, control characters removed.
clean() { printf '%s' "$1" | tr -d '\000-\037\177'; }

port=$(get_port)

pid=$(get_sillytavern_pid)
if [ -n "$pid" ]; then
    echo "online=1"
    echo "pid=$pid"
    metrics=$(ps -p "$pid" -o %cpu=,%mem=,rss=,etime= 2>/dev/null)
    read -r cpu mem rss etime <<< "$metrics"
    echo "etime=$etime"
    echo "cpu=$cpu"
    echo "rss_kb=$rss"
    http_res=$(curl -o /dev/null -I -s -L -w "%{http_code}:%{time_total}" --max-time 1 "http://127.0.0.1:${port}/" 2>/dev/null || echo "FAIL:0")
    echo "http_code=$(echo "$http_res" | cut -d: -f1)"
    echo "latency_s=$(echo "$http_res" | cut -d: -f2)"
else
    echo "online=0"
fi

# System info
bat=$(battery_info)
if [ -n "$bat" ]; then
    echo "bat_level=${bat%%|*}"
    echo "bat_status=$(clean "${bat#*|}")"
fi
# -P: one line per filesystem, even with a long device name.
disk=$(df -Ph "$HOME" 2>/dev/null | awk 'NR == 2 { print $4 "|" $5 }')
disk_free=${disk%%|*}
disk_used=${disk#*|}
[ -n "$disk_free" ] && echo "disk_free=$disk_free"
[ -n "$disk_used" ] && echo "disk_used_pct=$disk_used"

# An action the app started that is still running (e.g. after the app stopped waiting for it).
# A mark is stale if its process is gone, or older than 3 h (its pid may have been reused).
action_marks="${TMPDIR:-/tmp}/stmobile-actions"
running_action=""
if [ -d "$action_marks" ]; then
    for f in "$action_marks"/*; do
        case "$f" in *.w) continue ;; esac
        [ -f "$f" ] || continue
        apid=""
        asub=""
        read -r apid asub < "$f" 2>/dev/null
        if [ -n "$apid" ] && kill -0 "$apid" 2>/dev/null && [ -z "$(find "$f" -mmin +180 2>/dev/null)" ]; then
            running_action="$asub"
        else
            rm -f "$f"
        fi
    done
fi
[ -n "$running_action" ] && echo "action=$(clean "$running_action")"

# `git fetch` in the background, given up after 60 s: with timeout where there is one (not on macOS),
# else a watchdog loop. Detached from this script's output, so the SSH channel can close.
background_fetch() {
    if command -v timeout >/dev/null 2>&1; then
        (timeout 60 git -C "$ST_DIR" fetch -q </dev/null >/dev/null 2>&1 &)
        return
    fi
    (
        git -C "$ST_DIR" fetch -q </dev/null >/dev/null 2>&1 &
        g=$!
        i=0
        while kill -0 "$g" 2>/dev/null; do
            if [ "$i" -ge 60 ]; then
                kill "$g" 2>/dev/null
                break
            fi
            sleep 1
            i=$((i + 1))
        done
    ) </dev/null >/dev/null 2>&1 &
}

# Shared 300 s background fetch with stpanel; never under a running action (e.g. git pull)
now=$(date +%s)
has_git=0
[ -e "$ST_DIR/.git" ] && command -v git >/dev/null 2>&1 && has_git=1
last_fetch_file="${ST_DIR}/.git/.last_fetch_check"
last_fetch=""
[ -f "$last_fetch_file" ] && last_fetch=$(tr -dc '0-9' < "$last_fetch_file" 2>/dev/null)
[ -z "$last_fetch" ] && last_fetch=0
if [ -z "$running_action" ] && [ "$has_git" = 1 ] && [ -d "${ST_DIR}/.git" ] && [ $(( now - last_fetch )) -ge 300 ] 2>/dev/null; then
    { echo "$now" > "$last_fetch_file"; } 2>/dev/null
    background_fetch
fi

# Counts, cache size and git state: slow (du walks the cache, git) and slow to change, so reused
# for up to SLOW_TTL s. Never while an action runs; the action wrapper deletes the copy when one ends.
slow_info() {
    # Asset counts (a missing user folder would otherwise just read as zero of everything)
    echo "user_dir=$(clean "$ST_USER")"
    [ -d "$USER_DIR" ] || echo "user_dir_missing=1"
    echo "chars=$(ls "${USER_DIR}/characters" 2>/dev/null | wc -l | tr -d ' ')"
    echo "chats=$(ls "${USER_DIR}/chats" 2>/dev/null | wc -l | tr -d ' ')"
    echo "worlds=$(ls "${USER_DIR}/worlds" 2>/dev/null | wc -l | tr -d ' ')"
    echo "personas=$(ls "${USER_DIR}/User Avatars" 2>/dev/null | wc -l | tr -d ' ')"
    echo "backups=$(ls "${BACKUP_DIR}" 2>/dev/null | grep -E '\.(tar\.gz|zip)$' | wc -l | tr -d ' ')"
    local cache_size
    cache_size=$(du -sh "${DATA_DIR}/_cache" 2>/dev/null | awk '{print $1}')
    [ -z "$cache_size" ] && cache_size="0M"
    echo "cache_size=$cache_size"

    # Only the folder's own checkout (never one it happens to sit in, e.g. a git-managed home).
    local branch=unknown commit=unknown upstream behind=0 ahead=0
    if [ "$has_git" = 1 ]; then
        branch=$(git -C "$ST_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
        commit=$(git -C "$ST_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")
        upstream=$(git -C "$ST_DIR" rev-parse --abbrev-ref --symbolic-full-name "@{u}" 2>/dev/null)
        if [ -n "$upstream" ]; then
            behind=$(git -C "$ST_DIR" rev-list --count "HEAD..@{u}" 2>/dev/null || echo 0)
            ahead=$(git -C "$ST_DIR" rev-list --count "@{u}..HEAD" 2>/dev/null || echo 0)
        fi
    fi
    echo "branch=$(clean "$branch")"
    echo "commit=$(clean "$commit")"
    echo "behind=$behind"
    echo "ahead=$ahead"
}
SLOW_TTL=60
slow_file="${TMPDIR:-/tmp}/stmobile-dashboard.slow"
slow_key="$ST_DIR/$ST_USER"
cached_at=""
cached_key=""
[ -z "$running_action" ] && [ -f "$slow_file" ] && { IFS= read -r cached_at; IFS= read -r cached_key; } < "$slow_file" 2>/dev/null
if [ -z "$running_action" ] && [ "$cached_key" = "$slow_key" ] && [ $(( now - cached_at )) -ge 0 ] 2>/dev/null &&
    [ $(( now - cached_at )) -lt "$SLOW_TTL" ] 2>/dev/null; then
    tail -n +3 "$slow_file"
else
    slow=$(slow_info)
    printf '%s\n' "$slow"
    if [ -z "$running_action" ]; then
        { printf '%s\n%s\n' "$now" "$slow_key"; printf '%s\n' "$slow"; } > "$slow_file.$$" 2>/dev/null &&
            mv "$slow_file.$$" "$slow_file" 2>/dev/null || rm -f "$slow_file.$$"
    fi
fi

# Last client
if [ -f "$ACCESS_LOG" ] && [ -s "$ACCESS_LOG" ]; then
    last_log=$(tail -n 1 "$ACCESS_LOG" 2>/dev/null)
    log_ts=$(echo "$last_log" | awk '{print $1}' | cut -d'T' -f2 | cut -d'.' -f1)
    log_ip=$(echo "$last_log" | awk '{print $2}')
    log_ua=$(echo "$last_log" | cut -d' ' -f3- | cut -c 1-36)
    echo "last_client=$(clean "${log_ip} (${log_ua}) at ${log_ts} UTC")"
fi

# Recent server logs
if [ -f "$LOG_FILE" ] && [ -s "$LOG_FILE" ]; then
    tail -n 3 "$LOG_FILE" 2>/dev/null | while IFS= read -r line; do
        echo "log=$(clean "$(echo "$line" | cut -c 1-70)")"
    done
fi
