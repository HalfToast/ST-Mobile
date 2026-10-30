#!/usr/bin/env bash
# Read-only dashboard probe for ST Mobile. Prints KEY=VALUE lines.
# Mirrors stpanel's get_sillytavern_pid / get_system_info / get_asset_counts / get_git_info.
# The only writes are the ones stpanel itself does (.git/.last_fetch_check and a background `git fetch`),
# and the app's own files in $TMPDIR: removing stale action marks, and a 60 s copy of the slow lines
# (see Probes.wrapDetached).
if [ -d "/data/data/com.termux/files/home/SillyTavern" ]; then
    ST_DIR="/data/data/com.termux/files/home/SillyTavern"
else
    ST_DIR="${HOME}/SillyTavern"
fi
DATA_DIR="${ST_DIR}/data"
USER_DIR="${DATA_DIR}/${ST_USER:-default-user}"
LOG_FILE="${ST_DIR}/sillytavern.log"
ACCESS_LOG="${DATA_DIR}/access.log"
CONFIG_FILE="${ST_DIR}/config.yaml"
BACKUP_DIR="${ST_DIR}/backups"

get_sillytavern_pid() {
    if [ -f "${ST_DIR}/sillytavern.pid" ]; then
        local p
        p=$(cat "${ST_DIR}/sillytavern.pid" 2>/dev/null)
        if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
            echo "$p"
            return
        fi
    fi
    for p in $(pgrep -x "node" 2>/dev/null); do
        if tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q "server\.js"; then
            echo "$p"
            return
        fi
    done
}

# Single-line, control characters removed.
clean() { printf '%s' "$1" | tr -d '\000-\037\177'; }

port=$(grep -E '^port:\s*[0-9]+' "$CONFIG_FILE" 2>/dev/null | awk '{print $2}')
[ -z "$port" ] && port="8000"

pid=$(get_sillytavern_pid | head -n 1)
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
if [ -f /sys/class/power_supply/battery/capacity ]; then
    echo "bat_level=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)"
    echo "bat_status=$(cat /sys/class/power_supply/battery/status 2>/dev/null)"
fi
disk_free=$(df -h "$HOME" 2>/dev/null | awk 'NR==2 {print $4}')
disk_used=$(df -h "$HOME" 2>/dev/null | awk 'NR==2 {print $5}')
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

# Shared 300 s background fetch with stpanel; never under a running action (e.g. git pull)
now=$(date +%s)
last_fetch_file="${ST_DIR}/.git/.last_fetch_check"
last_fetch=0
[ -f "$last_fetch_file" ] && last_fetch=$(cat "$last_fetch_file" 2>/dev/null || echo 0)
if [ -z "$running_action" ] && [ $(( now - last_fetch )) -ge 300 ] 2>/dev/null; then
    echo "$now" > "$last_fetch_file" 2>/dev/null || true
    (timeout 60 git -C "$ST_DIR" fetch -q </dev/null >/dev/null 2>&1 &)
fi

# Counts, cache size and git state: slow (du walks the cache, git) and slow to change, so reused
# for up to SLOW_TTL s. Never while an action runs; the action wrapper deletes the copy when one ends.
slow_info() {
    # Asset counts (a missing user folder would otherwise just read as zero of everything)
    echo "user_dir=$(clean "${ST_USER:-default-user}")"
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

    local branch commit upstream behind ahead
    branch=$(git -C "$ST_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
    commit=$(git -C "$ST_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")
    upstream=$(git -C "$ST_DIR" rev-parse --abbrev-ref --symbolic-full-name @{u} 2>/dev/null)
    if [ -n "$upstream" ]; then
        behind=$(git -C "$ST_DIR" rev-list --count "HEAD..@{u}" 2>/dev/null || echo 0)
        ahead=$(git -C "$ST_DIR" rev-list --count "@{u}..HEAD" 2>/dev/null || echo 0)
    else
        behind=0
        ahead=0
    fi
    echo "branch=$(clean "$branch")"
    echo "commit=$(clean "$commit")"
    echo "behind=$behind"
    echo "ahead=$ahead"
}
SLOW_TTL=60
slow_file="${TMPDIR:-/tmp}/stmobile-dashboard.slow"
slow_key="$ST_DIR/${ST_USER:-default-user}"
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
