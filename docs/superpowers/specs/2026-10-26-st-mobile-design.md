# ST Mobile: design

Date: 2026-10-26, revised 2026-10-29
Owner: HalfToast

## What this is and why

HalfToast runs SillyTavern 24/7 in Termux on a Samsung Galaxy Tab S7+. They use it from an Android phone over Tailscale, in Chrome, at `http://100.101.102.103:8000`. The phone is currently a an Android phone on HyperOS and may change.

Two things hurt:

1. **Reloads.** Switching to another app for a while and coming back makes Chrome reload the page. A full SillyTavern load takes about 15 seconds. HyperOS is aggressive about killing background apps and tabs, which makes this worse.
2. **Server management means SSH.** HalfToast manages the server with `stpanel`, a bash TUI in Termux that they reach through an SSH client over Tailscale. It covers start, stop, restart, update, backup, cache cleaning, the whitelist, logs and character stats.

The goal is one Android app that is the single place for both chatting and managing the server.

**Revision (2026-10-29), decided by HalfToast:** nothing on the tablet changes. SillyTavern, the theme, the extensions and `stpanel` all stay exactly as they are and keep working the same way. That rules out the slim mobile chat UI (and the extension that would have held it) and any `stpanel` changes, both of which were discussed earlier. The app shows SillyTavern as it is and adds stpanel's features as native screens.

## Goals

- An Android app that opens straight into HalfToast's SillyTavern on the tablet, with no browser chrome. It looks and behaves the same as SillyTavern in Chrome today.
- Coming back to the app after switching away is fast:
  - If the app is still alive, the chat is exactly where it was.
  - If Android killed it, it comes back noticeably faster than today's ~15 seconds.
- A native Control Center that does everything `stpanel` does, so HalfToast no longer needs an SSH client for it.
- Works with any Android phone, not tuned to phone or HyperOS specifically.

## Non-goals

- Changing anything on the tablet: SillyTavern core, config, the theme, the extensions, or `stpanel`. Installing an SSH public key into `~/.ssh/authorized_keys` is the only exception (see SSH auth).
- A mobile-optimized or slimmed chat UI. The chat is SillyTavern exactly as it renders today.
- iOS.
- Play Store distribution. The APK is sideloaded.
- Running SillyTavern on the phone, or any offline mode.
- Generalizing for other people's setups. Sensible configuration (server address, SSH host, port and user) is fine, but no more.

## Anti-goals (fails even if it technically works)

- Anything about SillyTavern behaves differently inside the app than in Chrome, apart from the missing browser bar.
- The Control Center can't start the server when SillyTavern is down.
- Having to type the SSH password every time.
- The Control Center changes how `stpanel` works for HalfToast's own SSH sessions.

## Current setup the builder needs to know

- **Server:** SillyTavern runs in Termux on the tablet.
  - Path `/data/data/com.termux/files/home/SillyTavern`, port 8000.
  - It's a git checkout on the `staging` branch. Screenshots show `1.19.0 'staging' (ad29cbda6)`.
  - It's started and stopped by `startst` and `stopst`, which are Termux helpers that `stpanel` calls.
- **Network:** Both devices are on Tailscale. The tablet's Tailscale IP is `100.101.102.103`.
- **SSH:** A normal SSH server runs in Termux, with password auth today. Termux's sshd is usually on port 8022; confirm the actual port. Tailscale's own SSH server isn't used, because it doesn't run on Android.
- **SillyTavern:** Multi-user accounts appear to be enabled (Account, Admin Panel and Logout buttons are visible). The WebView must keep the login session cookie across app restarts.
- **stpanel:** A 35 KB bash script. A copy is at `/Users/adzin/Downloads/stpanel`; the live copy is on the tablet. Confirm that the two match before relying on the copy.
  - It has one-shot subcommands: `status`, `urls`, `start`, `stop`, `restart`, `backup`, `clean`, `whitelist`, `stats`, `logs`, `access`, `update`.
  - Its output is colored text meant for humans.
  - `clean` asks y/N on stdin.
  - `whitelist` reads a menu choice, and for "add" an IP, from stdin.
  - `logs` and `access` follow forever (`tail -f`).
  - It hardcodes `data/default-user` for counts, stats and backups.
- **Screenshots** of today's UI on the phone are in `/Users/adzin/Downloads/WhatsApp Unknown 2026-10-26 at 11.49.40 AM/`.
- **Mockups:** HalfToast chose option A in `/Users/adzin/Developer/SillyTavern/.superpowers/brainstorm/52453-1792989132/content/control-panel.html`. The Chat, Panel, Stats and Logs tabs are as shown there. The slim chat mockups in the same folder are no longer in scope.

## Chat tab

- A full-screen WebView loading SillyTavern from the configured address, with no browser UI.
- The soft keyboard must not cover the input while typing.
- Keeps the session cookie and local storage across app restarts.
- File uploads (avatars, character cards, backgrounds) and downloads (exports) work. A WebView doesn't do these by default.
- Android back button: SillyTavern's own behavior where it has any. Otherwise it doesn't exit the app by accident mid-chat; for example, it asks first or needs a second press.
- Nothing is injected into the page that changes its look or behavior.

## Staying alive and coming back fast

- **Primary aim:** Android shouldn't kill the app in the background as readily as it kills a Chrome tab.
  - A real app can be exempted from battery optimization ("no restrictions"). The app should walk the user through that on first run.
  - On top of that, a foreground service with a persistent "ST Mobile is running" notification keeps the app alive. HalfToast approved this.
- **Secondary aim:** when the app *was* killed, the reload should be much faster.
  - Ideas: aggressive HTTP caching of SillyTavern's static assets (it loads a large number of JS modules), and showing the last screen while it loads.
  - Caching must not serve stale code after a SillyTavern update. After an Update from the Control Center, the cache is cleared or revalidated.
  - The approach and the measured improvement are the builder's call. Measure before and after on the real setup.
- **Server unreachable** (Tailscale off, tablet down, SillyTavern stopped): show a clear state with a shortcut to the Control Center, not a WebView error page.

## Control Center (real app screens, mockup option A)

The Control Center uses native screens, not a terminal. It talks to the tablet over **SSH via Tailscale**. It has to be SSH and not SillyTavern's HTTP API, because Start must work while SillyTavern is down.

**Working with stpanel unchanged** (*my call, not discussed with HalfToast*):

- For actions, the app calls `stpanel`'s existing subcommands: `start`, `stop`, `restart`, `backup`, `update`, `clean` and `whitelist`. For the interactive ones it feeds the answers on stdin. For example, `clean` gets `y`, and `whitelist` gets the menu choice and the IP.
- For dashboard data that stpanel only shows in its interactive screen (battery, disk, cache size, inventory counts, last client, behind/ahead), the app runs the same read-only commands stpanel uses, directly over SSH.
- For logs, it tails the same files.
- The app parses text and strips ANSI color codes.
- The app validates IP and CIDR input before passing it on. Today's `whitelist add` interpolates input into a `node -e` string, so bad input could break the config.
- If `stpanel` is missing or its output can't be parsed, show a clear message rather than wrong numbers.

**Dashboard**, refreshed periodically while visible (stpanel's TUI uses 5 seconds):

- online/offline status, PID, uptime, and HTTP health with latency
- CPU and RAM of the server process
- tablet battery (level and charging), free disk space, and cache size
- git branch, commit, and the number of commits behind/ahead
- inventory counts (characters, chats, worlds, personas, backups)
- last client connection
- the last few log lines

An update banner with an **Update** button appears when the server is behind. On staging that may be often, which is fine.

**Actions:** Start, Stop, Restart, Update, Backup, Clean cache, and Whitelist. Whitelist shows the mode and the list, and can toggle the mode or add an IP or subnet.

- Stop, Clean cache and Update ask for confirmation first.
- Each action shows a short readable result, for example "Backup created, 48 MB", not raw terminal output.
- When a change needs a restart (whitelist changes do), the app says so and offers Restart.

**Stats:** the character activity table (name, messages, last active), sorted by most recent, with all characters available.

**Logs:** the live server log and the access log, as scrolling views that follow new lines. The stream stops when the user leaves the screen.

**SSH auth:**

- On first setup the user enters the host, port, username and password once.
- The app generates a key pair and keeps the private key in the Android Keystore.
- It installs the public key into `~/.ssh/authorized_keys` on the tablet using that one password login, like `ssh-copy-id` does.
- After that, logins use the key only and need no input. The password is not stored.
- Password auth on the tablet stays enabled for HalfToast's other SSH clients.
- On a new phone, the user repeats setup.

## Navigation

Bottom tabs: Chat, Panel, Stats, Logs, as in mockup A.

## How it gets made

*Builder's call unless HalfToast objects:*

- Native Android in Kotlin with Jetpack Compose.
- The system WebView for the chat.
- A maintained SSH library for the Control Center, for example sshj.
- Native Kotlin gives the most control over the WebView lifecycle, background survival, the Keystore and file pickers, which are exactly the problem areas.
- Sideloaded APK. GitHub Releases is a reasonable distribution channel.
- The repo is `~/Developer/st-mobile`, which also holds this document.

## Success looks like

- HalfToast stops using Chrome and the SSH client for SillyTavern.
- After switching to other apps and back during normal use, the chat is usually just there. When it isn't, the reload is clearly faster than ~15 seconds.
- Everything stpanel does can be done from the Control Center, and stpanel itself is untouched.

## Answered by HalfToast (2026-10-29)

1. **Device access during development:** yes.
   - The builder can SSH to the tablet from the Mac using key auth (HalfToast's Mac key, `~/.ssh/id_ed25519`).
   - The phone is connected over USB with adb: an Android phone, model `<model>`, serial `<serial>`.
   - Early tasks in the plan: confirm the SSH port, check that the live `stpanel` matches the copy in Downloads, check which ST user directory is in use, and measure today's reload time.
2. **Persistent notification:** acceptable. Use a foreground service to keep the app alive.
   - It needs a declared service type on Android 14+; `specialUse` fits a sideloaded app.
   - Ask for notification permission on first run.
   - Add a setting to turn it off.

## Left to the builder

- The WebView caching strategy and how to measure reload time.
- Control Center polling, SSH connection reuse, and log streaming.
- Parsing details for stpanel's output.
- Visual details within mockup A.
