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

(filled in by Task 3/4)
