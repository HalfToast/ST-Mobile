#!/usr/bin/env bash
# Read-only whitelist probe. Prints mode=true|false then one entry=<ip> per line.
# Runs on Termux, Linux/WSL and macOS (bash 3.2 or newer). Environment: ST_DIR (a leading ~/ is
# expanded; default: Termux's SillyTavern if it exists, else ~/SillyTavern), as in stpanel.

# ---- folder: twin of server/stpanel's
TERMUX_HOME="/data/data/com.termux/files/home"
TERMUX_BIN="/data/data/com.termux/files/usr/bin"
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
# require() takes a relative path for a package name: make the folder absolute.
case "$ST_DIR" in
    /*) ;;
    *) ST_DIR="$(pwd)/$ST_DIR" ;;
esac

# ---- finding node: twin of server/stpanel's found_node and find_node; keep them in step
# (only the "node not found" message differs: no "stpanel:" prefix).
# PATH, then nvm, fnm, Volta and the usual install folders. Sets NODE_BIN and puts its folder first on PATH.
found_node() {
    NODE_BIN=$(command -v node 2>/dev/null) || return 1
    [ -n "$NODE_BIN" ] || return 1
    case "$NODE_BIN" in
        /*) ;;
        *) return 1 ;;
    esac
    PATH="$(dirname "$NODE_BIN"):$PATH"
    export PATH
}

find_node() {
    local nvm_dir fnm_bin volta_home d newest old_ifs
    found_node && return 0

    nvm_dir="${NVM_DIR:-$HOME/.nvm}"
    if [ -s "$nvm_dir/nvm.sh" ]; then
        export NVM_DIR="$nvm_dir"
        # shellcheck disable=SC1091
        . "$nvm_dir/nvm.sh" >/dev/null 2>&1
        found_node && return 0
        if type nvm >/dev/null 2>&1; then
            nvm use default >/dev/null 2>&1
            found_node && return 0
        fi
    fi
    if [ -d "$nvm_dir/versions/node" ]; then
        newest=$(ls -1 "$nvm_dir/versions/node" 2>/dev/null | sed -n 's/^v\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$/\1/p' \
            | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)
        if [ -n "$newest" ] && [ -x "$nvm_dir/versions/node/v$newest/bin/node" ]; then
            PATH="$nvm_dir/versions/node/v$newest/bin:$PATH"
            found_node && return 0
        fi
    fi

    fnm_bin=$(command -v fnm 2>/dev/null)
    for d in "$HOME/.local/share/fnm" "$HOME/.fnm"; do
        [ -z "$fnm_bin" ] && [ -x "$d/fnm" ] && fnm_bin="$d/fnm"
    done
    if [ -n "$fnm_bin" ]; then
        eval "$("$fnm_bin" env 2>/dev/null)" >/dev/null 2>&1
        found_node && return 0
    fi

    volta_home="${VOLTA_HOME:-$HOME/.volta}"
    if [ -x "$volta_home/bin/node" ]; then
        export VOLTA_HOME="$volta_home"
        PATH="$volta_home/bin:$PATH"
        found_node && return 0
    fi

    local searched="PATH, nvm, fnm, Volta"
    old_ifs=$IFS
    IFS=:
    for d in ${STPANEL_NODE_DIRS-/opt/homebrew/bin:/usr/local/bin:$TERMUX_BIN}; do
        IFS=$old_ifs
        [ -n "$d" ] || continue
        searched="$searched, $d"
        if [ -x "$d/node" ]; then
            PATH="$d:$PATH"
            found_node && return 0
        fi
    done
    IFS=$old_ifs

    echo "node not found (looked in $searched); install Node.js or add it to PATH" >&2
    return 1
}

find_node || exit 127

# The paths reach node through its environment, never through its source.
STMOBILE_YAML="$ST_DIR/node_modules/yaml" STMOBILE_CONFIG="$ST_DIR/config.yaml" "$NODE_BIN" -e '
const fs = require("fs");
const env = process.env;
const fail = (msg) => { console.error(msg); process.exit(1); };
let yaml;
try {
    yaml = require(env.STMOBILE_YAML);
} catch (e) {
    fail("the yaml package was not found in " + env.STMOBILE_YAML + " (run npm install in the SillyTavern folder)");
}
let raw;
try {
    raw = fs.readFileSync(env.STMOBILE_CONFIG, "utf8");
} catch (e) {
    fail("cannot read " + env.STMOBILE_CONFIG);
}
const doc = yaml.parse(raw) || {};
console.log("mode=" + (doc.whitelistMode ? "true" : "false"));
(Array.isArray(doc.whitelist) ? doc.whitelist : []).forEach((ip) => {
    console.log("entry=" + ip);
});
'
