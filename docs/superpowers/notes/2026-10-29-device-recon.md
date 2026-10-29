# Device recon (2026-10-29)

Gathered over SSH from the Mac against the live tablet (key login, BatchMode).

## Confirmed values

- SSH: host 100.101.102.103, port 8022, user u0_a123, key login works (no password prompt).
- Non-interactive env: `PREFIX=/data/data/com.termux/files/usr`, `PATH=/data/data/com.termux/files/usr/bin` (only that).
- stpanel diff: the live `$(command -v stpanel)` is byte-identical to /Users/adzin/Downloads/stpanel (diff empty).
- On the non-interactive PATH (all in /data/data/com.termux/files/usr/bin): stpanel, startst, stopst, setsid, nohup, python3, node, git, curl, tail.
- ST dir: /data/data/com.termux/files/home/SillyTavern; `config.yaml` has `enableUserAccounts: true`.
- ST user folder: `default-user` (data/ also holds _cache, _css, _errors, _storage, _uploads, _webpack, access.log, content.log, cookie-secret.txt). default-user/characters holds 111 entries (Agnes.png, Akiko.png, ...). Matches `DEFAULT_ST_USER_DIR = "default-user"`; no change needed.
- ST port: 8000. Git: staging, up to date.

## Restart survives SSH disconnect

`ssh ... 'stpanel restart'` returned on its own (stopst, then startst; daemon with auto-restart and wake-lock). 10 s later `stpanel status` reported ONLINE (new PID). **No detach wrapper (setsid/nohup) was needed.** Task 8's `Probes.wrapDetached` can be a pass-through for start/restart, though `setsid` and `nohup` exist if a wrapper is wanted defensively.

Quirks of output: the stop line prints PIDs with a newline inside, e.g. `Stopping SillyTavern gracefully (PID: 8340\n8348)...` (two PIDs, one per line). Output contains ANSI codes and UTF-8 symbols.

## stpanel status format (ANSI stripped)

```
SillyTavern: ONLINE (PID: 9824) | Port: 8000 | Uptime: 00:12 | CPU: 67.7% | RAM: 354 MB | Git: staging (ad29cbda6) [Up to date]
Access URLs: http://127.0.0.1:8000 | Wi-Fi: http://192.168.1.2:8000 | Tailscale: http://100.101.102.103:8000
```

## Fixtures (app/src/test/resources/fixtures)

- stpanel-update-uptodate.txt: captured live (status had no update badge, so `stpanel update` was safe). Contents: header, blank line, "Already up to date.".
- stpanel-whitelist-list.txt: captured live with `printf "\n" | stpanel whitelist` (lists and exits; contains the real whitelist IPs).
- stpanel-backup.txt, stpanel-clean.txt: hand-written from the `echo -e` lines in stpanel (backup 240-242 plus the header lines; clean 257-263), with ANSI codes as printed. Sample values (size 12M, timestamp, cache 48M) are made up. The `clear` call is omitted. Clean fixture is the confirm path (y), ending with the success line.

## Reload timings

- Task 3, cold app start, logged in, WebView HTTP cache warm (not cleared; no asset interceptor yet): `ST_READY ms=13341` (12853 and 13012 on later cold starts after the readiness fix; 12012 after a renderer-kill reload). Login page alone: ~900-990 ms. True empty-cache number not measured (would need clearing app data, which logs out).
Task 4 (asset cache; ST_READY now measured from onPageStarted, so the 13341 above is not like-for-like): PENDING - phone was locked/dozing, so the four numbers (2 cold starts with `debug_disable_asset_cache` on, 2 warm-cache cold starts with it off) were not measured.

Task 4 device check (on-device, c05287a installed): BLOCKED, no timings measured. The app crashes immediately on every cold start (logcat -b crash): `FATAL EXCEPTION: st-version`, `NullPointerException: Attempt to invoke virtual method 'boolean android.os.Handler.post(Runnable)' on a null object reference` at `WebViewHolder.loadUrlTimed$lambda$0(WebViewHolder.kt:168)`. Cause: `loadUrlTimed` is an extension on `WebView`, so the bare `handler` inside the `thread { }` resolves to `View.getHandler()` of the (unattached, hence null) WebView rather than `WebViewHolder.handler`. Fix: use `this@WebViewHolder.handler.post { ... }`. The same pattern is still present at HEAD ec5f1ef. Steps 2-5 (timings, error screens, help link, back-closes-popup) were not run.

## Device check 2 (HEAD da9d972, an Android phone / Android 16, 2026-10-29)

Like-for-like cold-start timings (force-stop, logcat -c, launch, wait for ST_READY; ST_READY measured from onPageStarted). No `settings.xml` existed beforehand, so defaults applied; it was removed again at the end.

| run | cache disabled: VERSION_WAIT / ST_READY (ms) | cache enabled: VERSION_WAIT / ST_READY (ms) |
|---|---|---|
| 1 | 1 / 12593 | 483 / 9543 |
| 2 | 1 / 13657 | 487 / 9262 |
| 3 | 0 / 12081 | 405 / 9592 |
| median | 1 / 12593 | 483 / 9543 |

(Enabled runs came after one warm-up cold start: 557 / 9101. Initial cold start with defaults: 732 / 9321.) The asset cache saves about 3.0 s (about 24 %) of ST_READY at the cost of about 0.45 s VERSION_WAIT. Even with the cache, about 9.3 s remain, so the remaining time is not static-asset transfer.

Where the time goes: NOT MEASURED. The debug build does not call `setWebContentsDebuggingEnabled` (grep finds nothing), so no CDP/performance data was available.

Other checks:
- Crash buffer empty after install and cold starts.
- Error screens PASS. `/this-does-not-exist` shows "SillyTavern is unreachable / Server refused the connection (404)" with Retry and Open Control Center, no progress bar. `http://100.101.102.103:1` shows the same screen with `net::ERR_UNSAFE_PORT` (Chromium blocks port 1, so this is not a true refused connection). Restoring (no `server_url`) loads ST (ST_READY 9307).
- Help link PASS. Tapping "?" opened Firefox; `am start` of ST Mobile brought its task back to the front, still on ST. (An earlier tap during page load did nothing.)
- Back closes ST popup PASS. Opened "Manage chat files" (Chat History popup), which autofocuses its search field, so the first back only hid the IME, the second closed the popup and ST Mobile stayed in front (a third press would show "Press back again to leave" toast, not pressed).
- Snapshot with keyboard open PASS. After home + `am kill` + relaunch, the overlay showed (previous snapshot, not stretched) with "Reconnecting...", then live page (ST_READY 11102 after relaunch). Keyboard closed: home, kill, relaunch gave the last screen with "Reconnecting..." and live page.
- Side effect: a stray tap during the help-link check opened the Seraphina recent chat (character "Chise") in ST. Nothing was typed or sent.

## Control Center action checks (live server, HEAD 4942e4e, 2026-10-29)

Run on the an Android phone against the live tablet server. Baseline: pid 9824, online, staging @ ad29cbda6, 4 backups, cache 60M. Whitelist "add IP" was deliberately not tested (stpanel cannot remove entries).

- Backup PASS on the tablet. A new `st_backup_20261029_115759.tar.gz` (319775099 bytes, 305M) appeared and the inventory went from 4 to 5 backups. The "Backup created, <size>" snackbar was NOT captured: the first tap did nothing visible (no spinner, no new file), the second started it, and polling at 5 s intervals missed the snackbar. Needs a re-check with faster capture.
- Clean cache PASS. Confirm dialog "Clean cache? Deletes the files in data/_cache (60M)..." appeared; snackbar "Cache cleared"; `du` 60M to 3.5K.
- Update PASS. Confirm dialog "Update SillyTavern?" (staging, 0 behind) appeared; snackbar "Already up to date"; tablet HEAD still ad29cbda6.
- Whitelist toggle x2 PASS. Dialog showed a spinner and a greyed switch while loading, then mode ON with 6 addresses. Switch to OFF showed "Turn whitelist mode off?" naming the consequences (open access, ST refuses to start if accounts and basic auth are also off, applies after restart); confirmed, config.yaml had `whitelistMode: false`, a restart-needed banner appeared ("Restart SillyTavern to apply the changes.", Later / Restart now), snackbar "Whitelist mode off". Switch back to ON showed "Turn whitelist mode on?" with confirm; snackbar "Whitelist mode on"; `whitelistMode: true`. `diff` of config.yaml before/after: no differences (byte-identical). Note: the restart-needed banner stayed after switching back to ON until the restart.
- Restart PASS. No confirm. Snackbar "Restart requested"; the Restart button spinner cleared and the dashboard showed Online with the new pid 19241 (was 9824) by about 11 s after the tap (screenshot at t=11 s, uptime 00:07). Exactly one ST_READY (ms=18145, 12:01:52) with one VERSION_WAIT; Chat showed ST loaded, the restart banner was gone.
- Stop PASS. Confirm "Stop SillyTavern? The chat will be unavailable until you start it again."; snackbar "Server stopped"; dashboard Offline, Start enabled, Stop and Restart disabled; `pgrep -x node` empty, port 8000 refused.
- Chat while offline: FAIL against the spec. The Unreachable screen with "Open Control Center" did not appear; the Chat tab kept showing the stale loaded ST page for 80+ s, and a pull-down from the top did not change it. So "Open Control Center" could not be tapped; Panel was reached via the tab bar.
- Start PASS. Snackbar "Start requested"; Online with pid 23345 about 3 s after the tap, health HTTP 200 by about 15 s; exactly one ST_READY (ms=15358, 12:04:33). Chat then showed a freshly loaded ST (greeting stamped 12:04).
- Final state: online, pid 23345, staging @ ad29cbda6, 5 backups, cache 8.1M. config.yaml identical to baseline. App left on the Chat tab with ST loaded.
