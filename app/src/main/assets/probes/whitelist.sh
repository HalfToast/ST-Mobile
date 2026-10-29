#!/usr/bin/env bash
# Read-only whitelist probe. Prints mode=true|false then one entry=<ip> per line.
if [ -d "/data/data/com.termux/files/home/SillyTavern" ]; then
    ST_DIR="/data/data/com.termux/files/home/SillyTavern"
else
    ST_DIR="${HOME}/SillyTavern"
fi
CONFIG_FILE="${ST_DIR}/config.yaml"
node -e '
const fs = require("fs");
const yaml = require("'"${ST_DIR}"'/node_modules/yaml");
const p = "'"$CONFIG_FILE"'";
const doc = yaml.parse(fs.readFileSync(p, "utf8"));
console.log("mode=" + (doc.whitelistMode ? "true" : "false"));
(doc.whitelist || []).forEach((ip) => {
    console.log("entry=" + ip);
});
'
