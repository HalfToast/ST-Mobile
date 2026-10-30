# ST Mobile for any setup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ST Mobile and a distributable `stpanel` work with anyone's SillyTavern server on Termux, Linux, macOS or WSL, with nothing in the repo describing or assuming the author's own setup.

**Architecture:** `server/stpanel` is a single portable bash script that the app also ships as an asset; every remote command becomes `env NAME='value'… <interpreter> -s` with the real work on stdin, so it parses the same in bash, zsh and fish. The app drops its personal defaults, makes SSH optional, adds a first-run Connect screen and a "SillyTavern folder" setting, and applies the Tailscale-specific connection rules only to Tailscale addresses.

**Tech Stack:** Kotlin, Jetpack Compose, sshj, JUnit 4 (scripts are tested by running them from JUnit with `ProcessBuilder`, as `DetachedActionTest` does), bash/POSIX sh, Python 3, Node.

**Spec:** `docs/superpowers/specs/2026-10-30-general-distribution-design.md`

## Global Constraints

- Server scripts must run on Termux (bash 5), Linux/WSL (bash 4+, GNU tools) and macOS (**`/bin/bash` 3.2**, BSD tools, no `/proc`, no `setsid`, no `free`, no `timeout`). Bash 3.2 rules: no associative arrays, no `${var,,}`/`${var^^}`, no fractional `read -t`, no `&>>`, no `mapfile`/`readarray`, no `declare -n`.
- Every command string the app sends over SSH matches `^env( [A-Z_]+='[^'\\\n]*')* (bash|sh|python3) (-s|-)$` (or is exactly `echo ok`). All other content goes on stdin. Values in it are validated first.
- Nothing on HalfToast's tablet changes. Read-only SSH is allowed (cat, ls, running a script in `status`/`help`/`urls` mode only against a throwaway `ST_DIR` under `$TMPDIR` — ask HalfToast first). Never run actions there without asking.
- The installed `stpanel` wins over the built-in copy whenever `command -v stpanel` finds one.
- Same file names as the original scripts: `<ST_DIR>/sillytavern.pid`, `<ST_DIR>/.stop_requested`, `<ST_DIR>/sillytavern.log`, `<ST_DIR>/.git/.last_fetch_check`, `<ST_DIR>/backups/st_backup_<YYYYmmdd_HHMMSS>.tar.gz`; supervisor log text "Watchdog reviving in 2 seconds".
- The whitelist menu's stdin protocol stays: `1` = toggle, `2` then an address = add.
- ST folder resolution everywhere: `ST_DIR` if set (a leading `~/` expanded to `$HOME/`); else `/data/data/com.termux/files/home/SillyTavern` if it exists; else `$HOME/SillyTavern`. User folder: `ST_USER`, default `default-user`.
- Never log page content, server addresses or user names.
- Placeholders when an example address or user is needed: `100.101.102.103` (Tailscale), `192.168.1.20` (LAN), `u0_a123` (Termux user).
- Copy rules for UI text: "server", never "tablet"; plain sentences in the app's existing voice.
- Commits: conventional prefixes as in `git log` (`feat:`, `fix:`, `docs:`, `test:`), each ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Test command: `./gradlew :app:testDebugUnitTest` (all must pass); JS tests `tools/web-test.sh <page>` if a task touches `assets/web`.

## Review Focus

1. **Login shell is fish or zsh** — every command still parses; pinned by the command-shape test in Task 3 run over every `Probes`/`KeyInstaller` command.
2. **macOS `/bin/bash` 3.2** — `stpanel` and `dashboard.sh` tests in Tasks 1, 2 and 4 run them with `/bin/bash` explicitly, not `bash` from PATH.
3. **SillyTavern folder with spaces or `~/`** (`~/My Apps/SillyTavern`) — tests in Tasks 1 and 4 use such a folder.
4. **An existing install updating** (saved SSH host, no saved URL, setup complete) must open straight to Chat — `legacyServerUrl` tests in Task 5 and the device check in Task 10.
5. **A channel that closes while an action still runs** — cleanup and marks still happen with the script on stdin; `DetachedActionTest.commandFinishesAfterChannelCloses` kept and passing in Task 3.

---

### Task 1: `server/stpanel` — paths, node, start/stop/restart, status

**Files:**
- Create: `server/stpanel` (executable, `#!/usr/bin/env bash`)
- Create: `app/src/test/java/dev/halftoast/stmobile/server/StpanelTest.kt`
- Read-only sources: `/tmp/stpanel-src/{stpanel,startst,stopst}` (re-copy with `ssh -p 8022 <host from AppSettings on the phone or ask> 'cat ~/bin/stpanel'` if `/tmp` was cleared; never write there)

**Interfaces:**
- Produces: `stpanel <status|urls|start|stop|restart|logs|access|help>` and no-argument interactive panel; env `ST_DIR`, `ST_USER`; exit 0 on success, non-zero with a one-line reason on stderr for "node not found" and "SillyTavern folder not found".

- [ ] **Step 1: Write failing tests** in `StpanelTest.kt`. A helper builds a fake tree under a temp dir named with a space (`.../My Apps/SillyTavern`): `server.js` present, `config.yaml` with `port: 8123`, and a `bin/` put first on `PATH` holding a fake `node` script that, when given `server.js`, writes its args to `<ST_DIR>/node.args` and sleeps until killed. Run stpanel with `/bin/bash server/stpanel …`, `ST_DIR` set to the fake path, `HOME` to a temp home. Tests:
  - `startWritesPidAndLogAndStopRemovesThem`: `start` exits 0; `sillytavern.pid` holds a live pid; `sillytavern.log` contains `=== [` and `Starting SillyTavern`; `node.args` contains `--max-old-space-size=2048` and `server.js`; `stop` exits 0, the process is gone, pid and `.stop_requested` files are absent.
  - `stopFindsAServerStartedByTheOriginalScripts`: start the original `/tmp/stpanel-src/startst` logic is not runnable here, so instead launch the fake node directly and write its pid to `sillytavern.pid`; `stop` kills it.
  - `restartLeavesExactlyOneServer`.
  - `findsNodeFromNvmWhenNotOnPath`: `PATH=/usr/bin:/bin`, fake `node` placed at `$HOME/.nvm/versions/node/v20.0.0/bin/node` with `$HOME/.nvm/nvm.sh` that prepends that dir to PATH; `start` succeeds.
  - `saysWhenNodeIsMissing`: no node anywhere → non-zero exit, stderr mentions `node`.
  - `tildeStDirIsExpanded`: `ST_DIR='~/SillyTavern'` with the tree at `$HOME/SillyTavern` → `status` reports the port `8123`.
  - `plainOutputWhenNotATerminal`: `status` and `help` output contain no `\u001b[`.
  - `statusOfflineThenOnline`: contains `OFFLINE` before start and `ONLINE` with the pid after.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*StpanelTest*'` — expect failures (file missing).
- [ ] **Step 3: Implement `server/stpanel`** from the three original scripts: one file; `start_server`/`stop_server`/`restart_server` inline the `startst`/`stopst` logic (supervisor loop, `.stop_requested`, pid file, "Watchdog reviving in 2 seconds", Termux wake lock when `termux-wake-lock` exists). Detach with `setsid` when present, else `nohup` plus a backgrounded subshell with `trap '' HUP`. Process lookup: pid file first; else `pgrep -f 'node.*server\.js'` (works on macOS and Linux without `/proc`). `find_node`: PATH; `$NVM_DIR`/`~/.nvm/nvm.sh` (source it); `fnm env` if `fnm` exists; `~/.volta/bin`; `/opt/homebrew/bin`; `/usr/local/bin`. Colours and `clear` only when `[ -t 1 ]`; in the interactive panel, when `tput cols` (or `COLUMNS`) is under 76, lines are cut to the width instead of wrapping. Keep every other subcommand from the original working (Task 2 hardens them). Bash 3.2 only.
- [ ] **Step 4: Run the tests** — expect PASS; also run `/bin/bash -n server/stpanel`.
- [ ] **Step 5: Commit** `feat: portable stpanel in server/ (start, stop, restart, status)`.

### Task 2: `server/stpanel` — whitelist fix, backup, clean, update, stats, system info per platform

**Files:**
- Modify: `server/stpanel`
- Modify: `app/src/test/java/dev/halftoast/stmobile/server/StpanelTest.kt`

**Interfaces:**
- Consumes: Task 1's script and test helper.
- Produces: `stpanel whitelist` (menu on stdin: `1` toggle, `2` + address), `stpanel whitelist mode on|off`, `stpanel whitelist add <address>`; `backup`, `clean` (asks `[y/N]` on stdin), `update`, `stats`.

- [ ] **Step 1: Write failing tests.** Whitelist tests need real `node` and the `yaml` package: look for one under `~/Developer/*/node_modules/yaml`, else `npm install --prefix build/test-node yaml` once; `Assume` skip (with a message) if neither works. Link it as `<ST_DIR>/node_modules/yaml`. `config.yaml` starts with `whitelistMode: true` and `whitelist:\n  - 127.0.0.1`.
  - `menuAddsAnAddress`: stdin `2\n192.168.1.20\n` → config whitelist contains `192.168.1.20`; comments elsewhere in the file survive.
  - `menuTogglesMode`: stdin `1\n` → `whitelistMode: false`.
  - `injectionIsRejected`: stdin `2\n1.2.3.4"); require("fs").writeFileSync("pwned","x"); ("\n` → non-zero or a rejection line, config unchanged, no `pwned` file anywhere under the temp dirs.
  - `addAcceptsCidrAndIpv6`: `whitelist add 10.0.0.0/8` and `whitelist add fd00::1` succeed.
  - `modeIsAbsolute`: `whitelist mode on` twice leaves `true`.
  - `backupNamesAndContents`: `backup` creates `backups/st_backup_<14 digits with _>.tar.gz` containing `data/default-user/…` and `config.yaml` and not `data/default-user/backups/…`; with `ST_USER=alice` it holds `data/alice`.
  - `cleanAsksFirst`: stdin `n\n` keeps `data/_cache/x`; stdin `y\n` empties it.
  - `statusShowsBatteryOnlyWhenKnown`: `status` never prints `N/A%` (no battery on a Mac mini/CI) — assert no line matches `Battery: %`.
- [ ] **Step 2: Run** — expect failures.
- [ ] **Step 3: Implement.** Whitelist: pass the address and paths to node through environment variables read with `process.env`, never inside the `-e` source; validate the address with a bash pattern for IPv4 (four 0–255 octets) or IPv6 (hex and colons), optional `/prefix`, before writing. `mode on|off` sets an absolute value with `yaml.parseDocument`. `node_modules/yaml` resolved relative to `ST_DIR`. Backup uses `ST_USER`. System info: Termux `/sys/class/power_supply/battery`; Linux/WSL first `/sys/class/power_supply/BAT*`; macOS `pmset -g batt`; RAM via `free` or `sysctl hw.memsize` + `vm_stat`; disk `df -h "$HOME"`; LAN/VPN addresses via `ip` then `ifconfig` covering `wlan*|eth*|en*` and `tailscale*|utun*|tun*|wg*`; any missing value is omitted, not printed as `N/A%`. `stats` passes `ST_DIR`/`ST_USER` to Python through the environment.
- [ ] **Step 4: Run all `StpanelTest`** — PASS; `/bin/bash -n server/stpanel`.
- [ ] **Step 5: Commit** `fix: stpanel whitelist takes addresses safely; per-platform system info`.

### Task 3: Shell-proof remote commands and the built-in stpanel

**Files:**
- Modify: `app/build.gradle.kts` (assets also from `../server`)
- Modify: `app/src/main/java/dev/halftoast/stmobile/panel/Probes.kt`
- Modify: `app/src/main/java/dev/halftoast/stmobile/panel/PanelViewModel.kt` (ProbeSource, action and whitelist calls)
- Modify: `app/src/main/java/dev/halftoast/stmobile/ssh/RemoteShell.kt`, `SshRemoteShell.kt` (`stream` with stdin)
- Modify: `app/src/main/java/dev/halftoast/stmobile/ssh/KeyInstaller.kt`
- Modify: `app/src/main/java/dev/halftoast/stmobile/logs/LogsScreen.kt`, `stats/StatsViewModel.kt`, `MainActivity.kt` (call sites)
- Modify: `app/src/test/java/dev/halftoast/stmobile/panel/DetachedActionTest.kt`, `PanelViewModelTest.kt`, `ssh/KeyInstallerTest.kt`
- Create: `app/src/test/java/dev/halftoast/stmobile/panel/CommandShapeTest.kt`

**Interfaces:**
- Produces:
  - `data class RemoteCommand(val command: String, val stdin: String?)` in `Probes.kt`.
  - `Probes.dashboard(ctx: Context, stDir: String, userDir: String): RemoteCommand`
  - `Probes.stats(ctx: Context, stDir: String, userDir: String): RemoteCommand`
  - `Probes.whitelist(ctx: Context, stDir: String): RemoteCommand`
  - `Probes.action(ctx: Context, sub: String, stDir: String, input: String?): RemoteCommand`, delegating to `Probes.actionWith(stpanel: String, sub: String, stDir: String, input: String?): RemoteCommand` (no Context; tests pass `File("../server/stpanel").readText()`)
  - `Probes.tail(kind: LogKind, stDir: String): RemoteCommand` with `enum class LogKind { Server, Access }` (replaces `TAIL_SERVER_LOG`/`TAIL_ACCESS_LOG`)
  - `Probes.wrapDetached(cmd: String, marker: String?, input: String?, files: Map<String, String> = emptyMap()): String` — returns a **stdin script** for `sh -s`
  - `interface ProbeSource { fun dashboard(): RemoteCommand; fun whitelist(): RemoteCommand }`
  - `RemoteShell.stream(command: String, stdin: String? = null): Flow<String>`
  - `Probes.isSafeCommand(command: String): Boolean` (the Global Constraints regex; used by tests and asserted in `Probes`).
- Consumes: `ConnectionForm.isValidStDir` from Task 5 does not exist yet: in this task validate `stDir` with a private check of the same rule (empty, or starts with `/` or `~/` and uses only `[A-Za-z0-9 ._/+-]`), and Task 5 moves it to `ConnectionForm`.

- [ ] **Step 1: Write failing tests.**
  - `CommandShapeTest`: every `RemoteCommand.command` from `dashboard`, `stats`, `whitelist`, `action` (each subcommand) and `tail` (both kinds), with `stDir` values `""`, `"~/SillyTavern"`, `"/srv/My Apps/SillyTavern"`, and `KeyInstaller`'s command, satisfies `Probes.isSafeCommand`. `stDir` values containing `'`, `\`, `$`, `` ` ``, `"` or a newline throw `IllegalArgumentException`.
  - `DetachedActionTest` now runs `ProcessBuilder("sh", "-s")` with `wrapDetached(...)` written to stdin and then stdin closed; existing assertions kept (stdin input reaches the command via the `input` parameter, stdout/stderr/exit code passed through, exit 127, marks, dashboard `action=`, stale marks, slow-line cache, `commandFinishesAfterChannelCloses`). Add `runsUnderZshToo`: the `RemoteCommand.command` of an `actionWith(...)` is run as `/bin/zsh -c "<command>"` (as sshd would with a zsh login shell) with its stdin; output and exit code match the `sh` run. Add `builtInStpanelRunsWhenNoneInstalled`: `actionWith(<server/stpanel text>, "status", stDir, null)` with a PATH lacking `stpanel` prints `OFFLINE`; with a fake `stpanel` first on PATH that echoes `installed`, it prints `installed`.
  - `KeyInstallerTest`: the install script appends the key once (run twice → one line) using `bash -s` with stdin; a key not matching `^ecdsa-sha2-nistp256 [A-Za-z0-9+/=]+ st-mobile$` is rejected before anything runs.
- [ ] **Step 2: Run** — expect failures.
- [ ] **Step 3: Implement.**
  - `app/build.gradle.kts`: `android.sourceSets["main"].assets.srcDirs("src/main/assets", "../server")`, so `server/stpanel` is the asset `stpanel` (one file to edit). `server/` holds only `stpanel`.
  - Commands: `env ST_DIR='…' ST_USER='…' bash -s` (dashboard, whitelist read), `env … python3 -` (stats), `env ST_DIR='…' sh -s` (tail, actions). Omit `ST_DIR` when empty.
  - `wrapDetached` returns the whole wrapper as **one compound command** (`{ … }` followed by a newline) so the shell parses all of it before running anything — a channel closing mid-run must not cut the cleanup lines. Input goes into `$d/in` from a quoted heredoc inside the script; `files` likewise (`$d/<name>`), each with a delimiter that does not occur in its content (pick `STM_EOF_<n>` and check). Exit-code, SIGPIPE, HUP and mark behaviour unchanged.
  - `action`: `files = mapOf("stpanel" to <asset>)` and `cmd = if command -v stpanel >/dev/null 2>&1; then stpanel <sub>; else bash "$d/stpanel" <sub>; fi`, with `ST_DIR` exported inside the script when set.
  - `tail`: a small sh script on stdin resolving the folder by the Global Constraints rule, then `exec tail -n 200 -F "<folder>/sillytavern.log"` or `"<folder>/data/access.log"`.
  - `SshRemoteShell.stream`: write `stdin` (if any) then close the output stream, as `exec` does.
  - `KeyInstaller`: `bash -s` with the script and the validated key embedded.
  - `AndroidProbeSource`, `StatsViewModel`, `LogsScreen`, `PanelViewModel` use the new signatures; `stDir` comes from `settings.stDir` (Task 5 adds it; until then pass `""` from a local val and leave a `// Task 5` marker that Task 5 removes).
- [ ] **Step 4: Run all tests** — PASS.
- [ ] **Step 5: Commit** `feat: remote commands parse in any login shell; built-in stpanel when none is installed`.

### Task 4: Portable dashboard, Stats and whitelist probes

**Files:**
- Modify: `app/src/main/assets/probes/dashboard.sh`, `stats.py`, `whitelist.sh`
- Modify: `app/src/test/java/dev/halftoast/stmobile/panel/DetachedActionTest.kt` (its `dashboard()` helper) or create `app/src/test/java/dev/halftoast/stmobile/panel/ProbeScriptsTest.kt`
- Modify: `app/src/main/java/dev/halftoast/stmobile/stats/StatsViewModel.kt` (python3 missing message)

**Interfaces:**
- Consumes: Task 3's `RemoteCommand` env names `ST_DIR`, `ST_USER`.
- Produces: unchanged `KEY=VALUE` dashboard output; `stats.py` output unchanged; `whitelist.sh` output unchanged (`mode=`, `entry=`).

- [ ] **Step 1: Write failing tests** (`ProbeScriptsTest`, scripts run with `/bin/bash` and `python3`): fake tree at `$HOME/My Apps/SillyTavern` with `ST_DIR='~/My Apps/SillyTavern'`; dashboard prints `online=0`, `chars=` count, no `bat_level=` line when there is no battery source, `disk_free=` present; with the fake node from Task 1 running `server.js`, prints `online=1` and `pid=`; `stats.py` lists a character from `characters/Alice.png`; `whitelist.sh` reads `mode=true` and `entry=127.0.0.1` (node + yaml as in Task 2, skip if unavailable); `whitelist.sh` works with the folder containing a space (paths reach node only through `process.env`, never pasted into JS source).
- [ ] **Step 2: Run** — expect failures.
- [ ] **Step 3: Implement** the Global Constraints folder resolution in all three; process lookup and system info as in Task 2 (no `/proc`, no `timeout` — use a background `git fetch` with a sleep-kill watchdog when `timeout` is missing); `whitelist.sh` finds node like stpanel (copy the `find_node` function; both files carry a comment naming the other as its twin) and passes paths via `process.env`. `StatsViewModel`: exit code 127 with `python3` in stderr → message "Stats needs Python 3 on the server (on a Mac: install the Command Line Tools)".
- [ ] **Step 4: Run all tests** — PASS.
- [ ] **Step 5: Commit** `fix: dashboard, Stats and whitelist probes work on Linux, macOS and WSL, in any folder`.

### Task 5: Settings without personal defaults; SSH optional; SillyTavern folder

**Files:**
- Modify: `app/src/main/java/dev/halftoast/stmobile/settings/AppSettings.kt`, `ConnectionForm.kt`, `SettingsScreen.kt`, `SetupScreen.kt`
- Modify: `app/src/main/java/dev/halftoast/stmobile/StMobileApp.kt` (run the migration once at startup)
- Modify: call sites from Task 3's `// Task 5` markers
- Test: `app/src/test/java/dev/halftoast/stmobile/settings/AppSettingsTest.kt`, `ConnectionFormTest.kt`

**Interfaces:**
- Produces:
  - `AppSettings.serverUrl: String` (`""` when unset), `AppSettings.hasServerUrl: Boolean`, `AppSettings.stDir: String` (`""` = auto), `AppSettings.DEFAULT_SSH_PORT = 22`; `DEFAULT_SERVER_URL`, `DEFAULT_SSH_HOST`, `SUGGESTED_SSH_USER` removed.
  - `fun legacyServerUrl(hasSavedUrl: Boolean, setupComplete: Boolean, sshHost: String): String?` (top level in `AppSettings.kt`).
  - `ConnectionForm(serverUrl, host, port, user, stUserDir, stDir)`; `ConnectionForm.validate(): String?` where SSH fields are checked only if any of host/user is non-blank; `ConnectionForm.isValidStDir(path: String): Boolean`; `ConnectionForm.hasSsh: Boolean`.
  - `fun defaultSshHost(serverUrl: String): String` (the URL's host, brackets stripped for IPv6; `""` if invalid).

- [ ] **Step 1: Write failing tests:**
  - `legacyServerUrl(false, true, "100.101.102.103") == "http://100.101.102.103:8000"`; `legacyServerUrl(true, true, "100.101.102.103") == null`; `legacyServerUrl(false, false, "100.101.102.103") == null`; `legacyServerUrl(false, true, "") == null`; `legacyServerUrl(false, true, "fd7a:115c:a1e0::1") == "http://[fd7a:115c:a1e0::1]:8000"`.
  - `DEFAULT_SSH_PORT == 22`.
  - `ConnectionForm("http://192.168.1.20:8000", "", "22", "", "default-user", "").validate() == null` (URL only).
  - Host filled but user blank → `"Enter the SSH user"`; user filled, host blank → `"Enter the server's SSH host"`.
  - `isValidStDir`: accepts `""`, `"~/SillyTavern"`, `"/srv/My Apps/SillyTavern"`; rejects `"SillyTavern"`, `"~/a'b"`, `"/a\\b"`, `"/a\$b"`, `"/a\nb"`, `"/a\"b"`.
  - `defaultSshHost("http://192.168.1.20:8000") == "192.168.1.20"`, `defaultSshHost("http://[fd00::1]:8000") == "fd00::1"`, `defaultSshHost("nope") == ""`.
  - Existing tests that assert the old defaults are deleted or rewritten with placeholders.
- [ ] **Step 2: Run** — expect failures.
- [ ] **Step 3: Implement.** `StMobileApp.onCreate`: `legacyServerUrl(settings.hasServerUrl, settings.setupComplete, settings.sshHost)?.let { settings.serverUrl = it }`. Settings and Setup: a "SillyTavern folder" field (optional; supporting text "Leave empty to find it automatically"), the SSH host field shows `defaultSshHost(url)` as placeholder and uses it when left blank, the port field's supporting text "Termux uses 8022". Settings saves the URL alone; the "Re-run setup" hint appears only when SSH fields changed. Remove the Task 3 markers; pass `settings.stDir` everywhere.
- [ ] **Step 4: Run all tests** — PASS.
- [ ] **Step 5: Commit** `feat: no built-in server address; SSH optional; SillyTavern folder setting`.

### Task 6: First-run "Connect to SillyTavern" screen

**Files:**
- Create: `app/src/main/java/dev/halftoast/stmobile/chat/ConnectScreen.kt`
- Modify: `app/src/main/java/dev/halftoast/stmobile/MainActivity.kt`, `chat/WebViewHolder.kt` (don't load while the URL is empty)
- Test: `app/src/test/java/dev/halftoast/stmobile/chat/ChatStateTest.kt` or a new `ConnectScreenTest.kt` for any pure logic

**Interfaces:**
- Consumes: `AppSettings.hasServerUrl`, `AppSettings.isValidServerUrl`, `WebViewHolder.onServerUrlChanged()`.
- Produces: `@Composable fun ConnectScreen(initialUrl: String, onConnect: (String) -> Unit, modifier: Modifier)`; `fun connectError(url: String): String?` (null when valid, else "Enter an address starting with http:// or https://").

- [ ] **Step 1: Write failing tests** for `connectError`: `""` and `"192.168.1.20:8000"` give the message; `"http://192.168.1.20:8000"` gives null; surrounding spaces are trimmed.
- [ ] **Step 2: Run** — expect failure.
- [ ] **Step 3: Implement.** Shown in the Chat slot when `!settings.hasServerUrl`: ST-styled (`StBox`, `StButton`, field colours from `StComponents.kt`), title "Connect to SillyTavern", an address field with placeholder `http://192.168.1.20:8000`, a Connect button, and a hint: "SillyTavern must accept remote connections: set listen: true in its config.yaml, then whitelist this phone or turn on a login." Connect saves via `ConnectionForm.saveServerUrl` semantics and calls `holder.onServerUrlChanged()`. The WebView loads nothing while no URL is saved. Back menu works (Control Center reachable).
- [ ] **Step 4: Run all tests**; build `./gradlew :app:assembleDebug` succeeds.
- [ ] **Step 5: Commit** `feat: first launch asks for the SillyTavern address`.

### Task 7: Connection rules only for Tailscale addresses

**Files:**
- Modify: `app/src/main/java/dev/halftoast/stmobile/chat/UnreachableCase.kt`, `ConnectionWatch.kt`, `ConnectionMonitor.kt`, `UnreachableScreen.kt`, `MainActivity.kt` (passing the host rule)
- Test: `app/src/test/java/dev/halftoast/stmobile/chat/UnreachableCaseTest.kt`, `ConnectionWatchTest.kt`

**Interfaces:**
- Produces:
  - `fun isTailscaleHost(host: String): Boolean` — true for IPv4 in `100.64.0.0/10`, IPv6 in `fd7a:115c:a1e0::/48` (brackets allowed), names ending `.ts.net` (case-insensitive); false otherwise.
  - `fun connectionVerdict(vpnUp: Boolean, answer: ServerAnswer, connect: ConnectFailure?, tailscaleHost: Boolean): UnreachableCase?`
  - `UnreachableCase.TabletNotAnswering` renamed `ServerNotAnswering`.
- Consumes: `serverEndpoint(serverUrl)` for the host.

- [ ] **Step 1: Write failing tests:**
  - `isTailscaleHost`: `100.64.0.1`, `100.127.255.254`, `100.101.102.103`, `[fd7a:115c:a1e0::1]`, `mypc.tail1234.ts.net`, `MYPC.TAIL1234.TS.NET` → true; `100.63.255.255`, `100.128.0.1`, `192.168.1.20`, `fd00::1`, `ts.net.example.com`, `localhost` → false.
  - Tailscale host: existing verdict tests keep their results (VPN down → `TailscaleOff`; 401 with VPN down → `TailscaleOff`; refused with VPN up → `ServerNotRunning`; silence → `ServerNotAnswering`).
  - Non-Tailscale host: VPN state never yields `TailscaleOff`; `AuthRequired`/`LoginRedirect` → null with VPN down; `Refused` → `ServerNotRunning`; `Timeout` → `ServerNotAnswering`.
  - `ConnectionWatch`: for a non-Tailscale host a VPN-down report never covers the chat (the monitor doesn't watch the VPN; test the decision function the monitor uses, adding a `tailscaleHost` input if the watch needs it).
- [ ] **Step 2: Run** — expect failures.
- [ ] **Step 3: Implement.** `UnreachableProbe.check` does the TCP connect whenever the VPN is up **or** the host isn't a Tailscale address (the CGNAT problem exists only for 100.x). `ConnectionMonitor` registers the VPN watch only for a Tailscale host and re-evaluates when the URL changes. Unreachable texts: `TailscaleOff` → "Tailscale is off on this phone" / "Turn it on to reach the server."; `ServerNotRunning` → "SillyTavern isn't running" / "The server answers, but nothing is listening on SillyTavern's port."; `ServerNotAnswering` → Tailscale host: "The server isn't answering" / "It may be asleep, offline or signed out of Tailscale."; other hosts: "Can't reach the server" / "Check that it's on and that this phone is on the same network or VPN." The Open Tailscale button only for `TailscaleOff`.
- [ ] **Step 4: Run all tests** — PASS.
- [ ] **Step 5: Commit** `fix: Tailscale-specific connection messages only for Tailscale addresses`.

### Task 8: Nothing in the repo describes the author's setup

**Files:**
- Modify: every tracked file the final grep reports (code comments, UI strings, log text, tests, `README.md`, `app/src/test/web/*.html`)
- Move: `docs/superpowers/` → `.superpowers/archive/docs-superpowers/` (`git mv` is not possible into an ignored folder: `mkdir -p` + `cp -R` + `git rm -r`), **except** this plan and its spec, which move at the end of Task 11
- Modify: `.gitignore` only if needed (`.superpowers/` must already be ignored — verify)

**Interfaces:**
- Consumes: Tasks 1–7 merged into the branch.

- [ ] **Step 1: Write the check** as `tools/check-personal.sh`: greps the tracked tree (`git grep -n -I`) for the author's IP (`100\.101\.102\.103`), Termux user (`u0_a123`), adb serial (`<serial>`), `phone`, `HyperOS`, `MIUI`, `tablet` (case-insensitive), `HalfToast's`, `Seraphina`, `Aria`, `Ezra`, `Ruling [0-9]`, `Tailscale`; prints each hit; exits 1 if any hit is not in an allowlist file `tools/check-personal.allow` (one `path:regex` per line, each with a `# reason` comment). Allowed by the spec: `Tailscale` where it is one VPN option or the Tailscale-address rule; `HyperOS`/`MIUI` only in the README's Xiaomi battery tips; nothing else by default. The script itself and its allowlist are excluded from the grep. Run it — expect many hits.
- [ ] **Step 2: Fix every hit** in general terms (see the spec's section 6 and Global Constraints placeholders); rewrite "Ruling N" comments to state the reason; rename tests whose names mention the tablet.
- [ ] **Step 3: Move `docs/superpowers/`** (except this plan and spec) to `.superpowers/archive/docs-superpowers/`, verify the copy is complete (`diff -r`), then `git rm -r` the tracked copies.
- [ ] **Step 4: Run** `tools/check-personal.sh` (exit 0) and all tests (PASS).
- [ ] **Step 5: Commit** `chore: no defaults, names or notes from the author's own setup`.

### Task 9: README for four platforms, with a WSL guide

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: final behaviour from Tasks 1–8 (commands, settings names, messages).

- [ ] **Step 1: Verify the WSL facts against current sources** (WebSearch/WebFetch; record the URLs in the commit message body): `wsl --install`; enabling systemd in `/etc/wsl.conf` (`[boot] systemd=true`); mirrored networking (`[wsl2] networkingMode=mirrored` in `%UserProfile%\.wslconfig`, minimum Windows version); Hyper-V firewall / Windows Firewall rules for inbound ports in mirrored mode; `netsh interface portproxy` for NAT mode; how WSL idles out and how to keep a distro running or start it at login (Task Scheduler with `wsl.exe -d <distro> …`); SillyTavern's Linux install steps and its `listen`/whitelist/`basicAuthMode` settings. Note anything that could not be verified.
- [ ] **Step 2: Rewrite the README:**
  - Requirements per platform, and what each screen needs.
  - `server/stpanel` linked: its commands, how to install it for terminal use (`~/bin` or `~/.local/bin` on `PATH`), and that the app doesn't need it installed.
  - SSH quick setup for Termux (`pkg install openssh`, `passwd`, `sshd`, port 8022), Linux (`openssh-server`), macOS (Remote Login).
  - The WSL guide from Step 1.
  - Install from the Releases page, or build it yourself.
  - The first-run Connect screen, Settings (SSH optional, SillyTavern folder).
  - Known limits, updated: Docker/systemd/pm2/launchers out of scope for actions; native Windows not supported; Tailscale detection only for `100.64.0.0/10`, `fd7a:115c:a1e0::/48` and `*.ts.net` addresses (a MagicDNS short name gets the general messages).
- [ ] **Step 3: Run** `tools/check-personal.sh` — exit 0.
- [ ] **Step 4: Commit** `docs: README for Termux, Linux, macOS and WSL, linking server/stpanel`.

### Task 10 (controller, with HalfToast): 0.2.0 on the phone

Not for a subagent: needs the phone, the signing key and HalfToast.

- [ ] **Step 1:** `versionName = "0.2.0"`, `versionCode = 2` in `app/build.gradle.kts`; all tests pass; `./gradlew :app:assembleRelease`; verify the signature with `apksigner verify --print-certs` (cert SHA-256 must be the existing release cert).
- [ ] **Step 2:** `adb install -r` the release (no uninstall, no debug build). Launch. Expected: opens straight to Chat (the migration), logcat shows the page reached ready, no first-run screen.
- [ ] **Step 3:** Read-only checks without touching the page: back key opens the menu; Control Center dashboard shows online (installed stpanel path, unchanged tablet). Ask HalfToast to try: Stats, Logs, the menu, and (with their OK) one harmless action such as Backup.
- [ ] **Step 4:** Commit `chore: version 0.2.0`.

### Task 11 (controller, with HalfToast): merge, history rewrite, release

Destructive steps; each needs the confirmation the spec names.

- [ ] **Step 1:** Final whole-branch review done (SDD). Merge the branch into `main` (fast-forward if possible); full tests on `main`.
- [ ] **Step 2:** Move this plan and its spec to `.superpowers/archive/docs-superpowers/` and `git rm` them; `tools/check-personal.sh` exit 0; commit `chore: archive the working notes`.
- [ ] **Step 3:** Backup: `git clone --mirror . ~/Developer/st-mobile-history-backup.git`; verify it has all refs (`git -C … show-ref | wc -l` equals the original's).
- [ ] **Step 4:** Rewrite with `git filter-repo` (install with `brew install git-filter-repo` only after asking HalfToast) using `--replace-text` and `--replace-message` files mapping the IP, Termux user and serial to the placeholders. Delete the local `build/app` branch first if it is fully merged, else include it.
- [ ] **Step 5:** Prove it: `git log --all -p | grep -E '100\.101\.102\.103|u0_a123|<serial>'` prints nothing; tests pass on the rewritten `main`. Show HalfToast the proof and the author address; ask whether to rewrite the author address to their GitHub noreply (`--mailmap`) before pushing.
- [ ] **Step 6:** With HalfToast's go-ahead: delete the `v0.1.0` GitHub release and tag (`gh release delete v0.1.0 --cleanup-tag`), re-add `origin` if filter-repo removed it, force-push `main` (`git push --force origin main`), and delete any other remote branches holding old history.
- [ ] **Step 7:** Tag `v0.2.0` (annotated) on `main`, rebuild the release APK from the tag, verify its signature, `gh release create v0.2.0` with the APK as `ST-Mobile-0.2.0.apk`, notes listing what's new, install steps, the APK SHA-256 and the signing certificate SHA-256. Verify the release (asset size, not draft). Install that exact APK on the phone.
- [ ] **Step 8:** Update memory with the new state.
