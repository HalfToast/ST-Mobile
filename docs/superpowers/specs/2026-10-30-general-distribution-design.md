# ST Mobile for any setup — design

Date: 2026-10-30. Status: approved in chat by HalfToast. Builds on `main` after the back-menu change
(staged, not yet committed, at the time of writing).

## Intent

ST Mobile was built for exactly one setup: SillyTavern in Termux on HalfToast's tablet, reached over
Tailscale, managed with HalfToast's own `stpanel` script. HalfToast wants anyone to be able to use it with
their own SillyTavern server, on any device, and wants the repo safe to share. Today a stranger would
hit HalfToast's IP as the default address, "Tailscale is off" errors on home Wi-Fi, "tablet" wording,
and Control Center buttons that need a script that exists nowhere but HalfToast's tablet.

Success: someone with SillyTavern on Termux, Linux, macOS or Windows-through-WSL can install the APK,
enter their server address and chat; if they also set up SSH, Control Center, Stats and Logs work with
nothing else to install on the server. HalfToast's own setup keeps working exactly as now.

## Goals

1. A distributable `stpanel` in the repo (`server/stpanel`), linked from the README, safe and portable.
2. The app runs its own built-in copy of stpanel when the server has none installed.
3. The app's server-side scripts (dashboard, Stats, whitelist read, Logs) work on Termux, Linux,
   macOS and WSL, with SillyTavern in any folder.
4. The app has no built-in personal defaults, makes SSH optional, and describes connection problems
   correctly for non-Tailscale setups.
5. The README covers all four platforms, including a proper WSL guide.
6. No personal details (HalfToast's IP, Termux user name, phone serial) in the repo's files.

## Non-goals

- Native Windows (SillyTavern started from PowerShell/`Start.bat`, SSH landing in PowerShell). WSL only
  for now; the README says so.
- The app installing anything on the server. (Option C was chosen over "install it for them".)
- Rewriting git history. Removing personal details from history is a separate, destructive step only
  HalfToast can authorize (see Open questions).
- New features beyond what's listed; no redesign of the screens.

## Anti-goals (would make this a failure even if it works)

- **Anything changes on HalfToast's tablet.** Their `~/bin/stpanel`, `startst`, `stopst` stay
  untouched, and since they have `stpanel` on `PATH`, the app keeps calling theirs.
- HalfToast sees a first-run or setup screen again after updating. Their saved URL, SSH settings and
  host key must carry over.
- Regressions in what already works for HalfToast: connection-loss cover, Tailscale-off detection
  for their 100.x address, action marks, dashboard caching, keyboard performance.
- The built-in stpanel and an installed stpanel disagree about files, so one can't stop what the other
  started.

## Constraints

- Server scripts run under the server's own tools. They must work on: Termux (bash 5, Android
  `/proc`, `/sys/class/power_supply/battery`), Linux and WSL (bash 4+, GNU coreutils/procps), macOS
  (**bash 3.2**, BSD tools, no `/proc`, no `setsid`, no `free`, no `timeout`). Anything used must exist
  on all four or have a fallback. Bash 3.2 rules: no associative arrays, no `${var,,}`, no `read -t`
  with a fraction, no `&>>`, no `mapfile`.
- SSH lands in the user's login shell, which may be bash, zsh or fish. Every command the app sends must
  work regardless (e.g. by handing the work to `sh`/`bash` explicitly in a way all three shells parse
  identically). The current `wrapDetached` wrapper's behaviour (SIGPIPE safety, action marks, exit
  codes) must survive, including its existing tests in `DetachedActionTest`.
- In a non-interactive SSH session `PATH` is minimal: `node` installed via nvm, fnm or Homebrew is often
  missing, and so is `~/.local/bin`. stpanel must find node itself.
- Values from the app reach shell commands; they must be validated and quoted (as `Probes.build` does
  now for the user folder).
- Never log page content (existing project rule).

## The design, as decided

### 1. `server/stpanel` (one file)

- One bash script that merges HalfToast's `stpanel`, `startst` and `stopst` (copied read-only from
  the tablet into `/tmp/stpanel-src/` during brainstorming; the builder can re-copy them with
  `ssh -p 8022 <host> 'cat ~/bin/stpanel'` etc. — read-only). Same subcommands and output meaning:
  `status, urls, start, stop, restart, backup, clean, whitelist, stats, logs, access, update, help`,
  and the interactive panel with no argument. `start`/`stop`/`restart` call internal functions instead
  of external `startst`/`stopst`.
- **Same files as HalfToast's scripts**, so either copy can stop what the other started:
  `<ST_DIR>/sillytavern.pid`, `<ST_DIR>/.stop_requested`, `<ST_DIR>/sillytavern.log`,
  `<ST_DIR>/.git/.last_fetch_check`, backups in `<ST_DIR>/backups/st_backup_<ts>.tar.gz`. The
  supervisor loop's log lines keep the text "Watchdog reviving in 2 seconds" (stop uses it to find a
  sleeping supervisor).
- **The whitelist menu keeps its stdin protocol**: `1` = toggle, `2` then an address = add. The app
  drives it that way and must keep working against HalfToast's copy. Inside, the fixes:
  - The address is passed to node as an argument or environment variable, never pasted into the
    `node -e` source (today's code-injection bug). It is also validated as an IPv4/IPv6 address or
    CIDR before writing.
  - New, non-interactive forms for terminal users: `stpanel whitelist mode on|off` (absolute, not a
    toggle) and `stpanel whitelist add <address>`.
- **Finding SillyTavern**: `ST_DIR` from the environment if set; else the Termux path
  `/data/data/com.termux/files/home/SillyTavern` if it exists; else `~/SillyTavern`. The user folder
  defaults to `default-user`, overridable with `ST_USER`. Backup includes that user folder (excluding
  its `backups/`) and `config.yaml`.
- **Finding node**: `PATH` first; then nvm (`$NVM_DIR` or `~/.nvm`, sourcing `nvm.sh`), fnm, Volta,
  `/opt/homebrew/bin`, `/usr/local/bin`. A clear error if none is found.
- **Platforms**:
  - Termux: wake lock as today; battery from `/sys/class/power_supply/battery`.
  - Linux/WSL: battery from the first `/sys/class/power_supply/BAT*` if any, else none.
  - macOS: battery from `pmset -g batt`; RAM from `sysctl`/`vm_stat`; process lookup with `pgrep -f`/
    `ps` instead of `/proc`; detaching with `nohup` and a subshell where `setsid` is missing.
  - LAN/VPN addresses: `ip`, then `ifconfig`, covering `wlan*`, `eth*`, `en*` (macOS), and
    `tailscale*`/`utun*`/`tun*`/`wg*` for the VPN line; missing values are simply not shown.
- **Terminal output**: colours and `clear` only when stdout is a terminal (so the app's captured
  output and pipes get plain text); works in narrow terminals without breaking the box drawing badly
  (truncate rather than wrap where practical).
- Behaviour kept: Node `--max-old-space-size=2048`, `NODE_ENV=production`, auto-restart supervisor,
  `git pull --rebase --autostash` for update, `clean` asks `[y/N]` (the app answers it as today).

### 2. The app runs a built-in copy when needed

- `server/stpanel` is also shipped as an app asset (one source of truth: the build copies it, or the
  asset is the file and `server/` points to it; builder's call, but there must be exactly one copy to
  edit).
- For actions and the whitelist write, the app runs: the installed `stpanel` if `command -v stpanel`
  finds one, else the built-in copy, passed to bash without writing it to disk permanently (a temp file
  removed afterwards is fine). The whitelist write's stdin answers must still reach the script.
- The existing detach wrapper, action marks and dashboard cache invalidation apply to both paths.
- Settings gains **"SillyTavern folder"** (optional, empty = auto-detect as above). It is passed as
  `ST_DIR` to stpanel (both paths), dashboard, Stats, whitelist read and Logs. Validated so it can be
  quoted safely (no quotes, no newlines; `~/` allowed and expanded on the server).

### 3. The app's own server scripts

- `dashboard.sh`, `stats.py`, `whitelist.sh` and the Logs `tail` commands use `ST_DIR` and the same
  platform fallbacks as stpanel (process lookup without `/proc`, battery, RAM, no `timeout` on macOS).
  The dashboard keeps its output format (`KEY=VALUE` lines) so the app's parser stays as is, except
  for new optional keys if needed.
- `whitelist.sh` finds node the same way stpanel does and doesn't paste paths into JS source.
- Stats needs `python3`; if it's missing, Stats says so in plain words (macOS without the Command Line
  Tools is the common case).

### 4. Making the app general

- **No personal defaults.** `DEFAULT_SERVER_URL` and `DEFAULT_SSH_HOST` go away.
- **Carry-over (builder's-check finding; a design call, not HalfToast's words):** existing installs
  that ran on the old default never saved `server_url` (`ConnectionForm.saveServerUrl` only writes a
  URL that differs from the current value, which was the default). So on first start after the update,
  if no `server_url` is saved and `setup_complete` is true, the app saves `http://<saved ssh_host>:8000`
  once. That reproduces HalfToast's address without it being in the code. It must have a unit test, and
  the first install of the new build on HalfToast's phone must open straight to Chat.
- **First launch without a saved URL** shows a simple, ST-styled "Connect to SillyTavern" screen: an
  address field (with an example like `http://192.168.1.20:8000`), a Connect button, and a short hint
  that SillyTavern must allow remote connections (`listen: true` plus whitelist or login). Chat opens
  once it's saved. The back menu works there too (Control Center reachable).
- **SSH is optional.** Settings saves the URL without SSH fields. When SSH fields are blank, the SSH
  host defaults to the URL's host, port 22, with a visible hint "Termux uses 8022". Control Center,
  Stats and Logs show Setup until SSH is set up, as now.
- **Connection problems, described correctly.**
  - The "Tailscale is off" case (and the Open Tailscale button, and retrying when a VPN comes up)
    applies only when the server's host is a Tailscale address: `100.64.0.0/10`, `fd7a:115c:a1e0::/48`,
    or a name ending in `.ts.net`.
  - For other hosts the VPN state is ignored: a 401/403 or ST's own login redirect counts as reachable,
    and an unreachable server reads "Can't reach the server" (with "SillyTavern isn't running" still
    told apart by a refused connection, and "not answering" for silence).
  - The connection-loss cover follows the same rule: VPN going down covers the chat only for a
    Tailscale host.
  - The CGNAT lesson stays: for 100.x hosts a TCP connect is never proof of reachability.
- **Wording.** "Tablet" becomes "server" in every user-visible string (Setup, Settings, Unreachable,
  Panel results, errors such as "stpanel was not found on the tablet" — which becomes moot for missing
  stpanel but applies to similar messages). Code comments that mention HalfToast's specific devices
  are generalised.

### 5. README

- Requirements per platform (Termux, Linux, macOS, WSL), what each screen needs, and SillyTavern's
  remote-access settings.
- `server/stpanel` linked, with how to install it for terminal use (`~/bin` or `~/.local/bin` on
  `PATH`), its commands, and that the app doesn't need it installed.
- **WSL guide**, step by step: `wsl --install`; installing git, Node (LTS) and SillyTavern inside WSL;
  `openssh-server` and starting it (systemd via `/etc/wsl.conf`, or a start command); making it
  reachable from the phone — WSL mirrored networking (`networkingMode=mirrored` in `.wslconfig`,
  Windows 11 22H2+) or a port proxy on older Windows, Windows Firewall rules for SillyTavern's port and
  SSH, or Tailscale on Windows; keeping WSL and SillyTavern running (WSL stops when idle; how to start
  it at login). The builder must check these steps against current Microsoft and SillyTavern docs
  rather than memory, and say which ones were checked.
- Termux, Linux and macOS SSH quick setup (`pkg install openssh`/`sshd` on 8022; `openssh-server` on
  Linux; Remote Login on macOS).
- The "Known limits" section is updated to whatever is still true afterwards.

### 6. Nothing in the repo describes or assumes HalfToast's setup

HalfToast asked for every default and every word that mentions or relies on their setup to go, not
only the obvious personal details.

- **Defaults:** no built-in server URL or SSH host (section 4); SSH port defaults to 22, not Termux's
  8022 (8022 is a hint only); nothing else defaults to their tablet, Tailscale, or Termux unless it is
  detected on the server at run time (the Termux SillyTavern path is detection, which stays).
- **Personal details:** HalfToast's IP, Termux user name, adb serial and phone model appear nowhere in
  tracked files. Tests use neutral placeholders (`100.101.102.103` where a Tailscale address is the
  point of the test, `192.168.1.20` for a LAN server, `u0_a123`).
- **Words in code and UI:** comments, strings, log messages and test names that refer to HalfToast,
  "the tablet", their phone, their carrier, their characters or chats, or their habits (e.g.
  "HalfToast's VPN", "HalfToast uses Firefox", "the tablet's TMPDIR") are rewritten in general terms
  ("the server", "a VPN", "a carrier that…"). Comments that point to the old SDD ledger ("Ruling 18")
  are rewritten to state the reason itself.
- **README:** no "built for one setup" framing; Known limits lists only real limits.
- **Process docs:** `docs/superpowers/` (the old specs, plans and device notes, all about HalfToast's
  setup) leaves the tracked tree. It is moved, not deleted, into the git-ignored `.superpowers/archive/`
  so HalfToast keeps it locally. This spec and its plan are written there too at the end of the work
  (they live in `docs/superpowers/` only while the work runs).
- **Stays:** the GitHub handle `HalfToast` as the author/repo owner, the package name
  `dev.halftoast.stmobile`, and the signing certificate (it identifies the publisher, as intended).
- Local notes outside the repo (Claude memory, `.superpowers/`) are unaffected.
- A final check greps the tracked tree for the IP, user name, serial, "tablet", "HalfToast's",
  "phone", "HyperOS" (allowed only in the general Xiaomi battery tips), "Tailscale" (allowed only where
  it is one VPN option or the Tailscale-address rule) and reports every remaining hit with a reason.

## Scope note: how SillyTavern is started (design call)

Control Center actions assume SillyTavern runs as plain `node server.js` on the SSH machine, started by
stpanel (installed or built-in) or by hand. SillyTavern run by Docker, systemd, pm2 or a launcher is
out of scope: the dashboard may still show it online if `node server.js` is visible, but Start/Stop/
Restart don't coordinate with those managers. The README's Known limits says this plainly.

## Left to the builder

- How the asset and `server/stpanel` stay one file.
- How commands are made login-shell-proof.
- Exact copy of the first-run screen and hints, in the app's existing voice.
- Test approach per platform. Available locally: macOS (this Mac: real bash 3.2 at `/bin/bash`, zsh),
  the Termux tablet read-only (no writes, no actions without asking HalfToast), and Linux via Docker
  if it is installed. Pure Kotlin logic (Tailscale-address check, defaults, validation) gets unit tests
  as usual; scripts get tests that run them under `/bin/bash` 3.2 and, where possible, Linux bash.

## Decided by HalfToast after the builder check

- **Git history is rewritten** so the personal details are gone from every commit, not only the
  current files. Done last, after everything else is merged and verified:
  1. Back up first: a `git clone --mirror` of the repo to `~/Developer/st-mobile-history-backup.git`
     (outside the working tree), and keep it.
  2. Rewrite with `git filter-repo` (`--replace-text` for file contents, `--replace-message` for
     commit messages) replacing the IP, Termux user name and adb serial with the same placeholders as
     section 6. The `docs/superpowers/` files that were moved to the archive stay in history, scrubbed.
  3. Before force-pushing, show HalfToast a grep of the rewritten history proving no hits, and the
     commit author address (all commits use a personal Gmail address; GitHub shows it on public
     repos). Ask whether to also rewrite the author address to their GitHub noreply address.
  4. Force-push `main` and tags. The local clone's other branches (`build/app`) are rewritten or
     deleted, not left holding the old history.
  - **Design call:** the v0.1.0 tag and GitHub release are deleted, not recreated. That APK has the old
    default address compiled in, so it can't be part of a clean public repo; 0.2.0 replaces it.
- **0.2.0 is published** as a GitHub release: version bumped (`versionName` 0.2.0, `versionCode` 2),
  signed with the existing release key, installed on HalfToast's phone first (it must open straight to
  Chat), then released with the APK and its SHA-256 and the signing certificate's fingerprint in the
  notes. The README's install section points to the Releases page as the easy way.
- **Commits:** the staged back-menu change is committed to `main` first, on its own. This work happens
  on a branch from there and is merged back once HalfToast has tried it.
