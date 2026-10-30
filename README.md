# ST Mobile

An Android app for using [SillyTavern](https://github.com/SillyTavern/SillyTavern) on your phone. It
shows your SillyTavern page full screen, and adds native screens for looking after the server over SSH.
It has four screens: Chat, Control Center, Stats and Logs.

SillyTavern can run on any machine the phone can reach: a PC or Mac on the same Wi-Fi, a Linux box at
home, Windows through WSL, or another Android device with Termux. You can reach it over your home network
or through a VPN. Chat needs only the server's address. Control Center, Stats and Logs also need SSH
access to that machine. Nothing has to be installed on the server for them.

- **Chat**: SillyTavern exactly as it is in a browser, in a full-screen WebView. Downloads work. Static
  files are cached on the phone, so reloads are faster than in a browser. The last screen is saved as a
  snapshot, so reopening the app shows your chat straight away while the page reloads. An optional
  keep-alive notification keeps the app loaded in the background. It shows SillyTavern's logo and the
  text "SillyTavern stays loaded".
- **Control Center**: status (online, uptime, CPU/RAM, battery, disk, cache, inventory), and the actions
  Start, Stop, Restart, Update, Backup and Clean cache. Stop, Clean cache and Update ask first. The
  whitelist manager lets you add an IP address and switch whitelist mode; switching it asks first.
  Actions keep running on the server even if the app gives up waiting (60 s, or 5 min for Update and
  Backup) or is closed; the result line then says so, and the dashboard shows the outcome.
- **Stats**: every character with its avatar from SillyTavern, message count and last-active time, most
  recent first. Pull to refresh.
- **Logs**: the live server log or access log (the last 1000 lines), in a monospace font. Leaving the
  screen or backgrounding the app stops the remote `tail`.

## Contents

- [Get the app](#get-the-app)
- [What you need](#what-you-need)
- [Let SillyTavern accept the phone](#let-sillytavern-accept-the-phone)
- [First run and Settings](#first-run-and-settings)
- [SSH quick setup](#ssh-quick-setup): [Termux](#termux), [Linux](#linux), [macOS](#macos)
- [Windows: SillyTavern in WSL](#windows-sillytavern-in-wsl)
- [stpanel](#stpanel)
- [Using the app](#using-the-app)
- [Keeping the app alive in the background](#keeping-the-app-alive-in-the-background)
- [Known limits](#known-limits)
- [Build it yourself](#build-it-yourself)

## Get the app

Download the APK from the [Releases page](https://github.com/HalfToast/ST-Mobile/releases), open it on
the phone, and allow installing from that source when Android asks. To update, install the newer APK
over the old one; your settings stay.

You can also [build it yourself](#build-it-yourself). A build signed with a different key can't update
an install from the Releases page: you would have to uninstall first, which erases the app's data (setup
and SillyTavern login).

## What you need

**Phone:** Android 10 or newer.

**Server:** SillyTavern on one of these:

| Platform | Notes |
| --- | --- |
| Android with [Termux](https://termux.dev) | Install Termux from F-Droid or GitHub, not Google Play. SSH runs on port 8022. |
| Linux | Any distribution with bash. |
| macOS | The built-in bash 3.2 is enough. |
| Windows | Through WSL only; see [the WSL guide](#windows-sillytavern-in-wsl). |

**What each screen needs:**

| Screen | Needs |
| --- | --- |
| Chat | The server's address, and SillyTavern [accepting the phone](#let-sillytavern-accept-the-phone). |
| Control Center | SSH, and `bash` on the server. Start, Restart and the whitelist manager also need Node.js (the same one SillyTavern uses). The Health line uses `curl`. Update needs `git` and a SillyTavern folder cloned with git. |
| Stats | SSH, and `python3` on the server. On a Mac, `python3` comes with the Command Line Tools (`xcode-select --install`). |
| Logs | SSH. The server log is `sillytavern.log` in the SillyTavern folder, which stpanel writes, so it fills only when SillyTavern was started by stpanel or the app. The access log is SillyTavern's own `data/access.log`. |

For SSH you need an SSH server the phone can reach, and a user account with a password. The password is
needed once, during setup. SillyTavern should be in that user's home folder as `~/SillyTavern`, or you
tell the app where it is (see [Settings](#first-run-and-settings)).

The Control Center actions run [stpanel](#stpanel). If the server has `stpanel` installed, the app uses
that one; otherwise it brings its own copy and runs it, without installing anything. Commands work
whatever the SSH user's login shell is (bash, zsh or fish).

## Let SillyTavern accept the phone

By default SillyTavern only accepts connections from its own machine. Edit `config.yaml` in the
SillyTavern folder and set:

```yaml
listen: true
```

SillyTavern then also needs a way to decide who may connect, or it won't start. Pick one:

- **Whitelist:** keep `whitelistMode: true` and add the phone's address (or your network, like
  `192.168.1.0/24`) under `whitelist:`. Keep the `127.0.0.1` entry that is already there. Once SSH is set
  up, the app's whitelist manager can add the address for you.
- **A login:** set `basicAuthMode: true` and a `username` and `password` under `basicAuthUser:`, or turn
  on SillyTavern's user accounts.

Restart SillyTavern after changing `config.yaml`. SillyTavern's guide to
[remote connections](https://docs.sillytavern.app/usage/remoteconnections/) has the details. Never
expose SillyTavern to the open internet: keep it on your home network or behind a VPN.

## First run and Settings

1. Make sure the phone can reach the server: open the address, for example `http://192.168.1.20:8000`,
   in the phone's browser once. If you use a VPN, turn it on first.
2. Open the app. It shows **Connect to SillyTavern**: enter the address and tap Connect. The chat
   opens. That's all Chat needs.
3. For Control Center, Stats and Logs, press back and open the Control Center. It shows **Connect to the
   server**, with these fields:
   - **SillyTavern URL**: the address from step 2.
   - **SSH host**: leave it empty to use the URL's host.
   - **Port**: 22 unless you changed it. Termux uses 8022.
   - **SSH user**: the user that runs SillyTavern.
   - **ST user folder**: `default-user`, unless you use SillyTavern's user accounts.
   - **SillyTavern folder**: leave it empty to find it automatically. The app then uses `ST_DIR` if the
     server sets it, else Termux's `~/SillyTavern`, else `~/SillyTavern` in the SSH user's home. If it
     is somewhere else, enter its path, starting with `/` or `~/`.
   - **SSH password**: entered once. The app creates its own key, adds it to the user's
     `~/.ssh/authorized_keys` and uses the key from then on. The password is not stored.
4. The first connection pins the server's SSH host key. If it ever changes (for example after
   reinstalling Termux or the SSH server), the app says so. Use "Trust new host key" in Settings only if
   you expect that.

Settings (the gear on the Control Center) has the same fields, plus keep-alive, the pinned host key and
**Re-run setup**. SSH is optional there: with the SSH host and user empty, Settings saves just the
SillyTavern URL.

## SSH quick setup

### Termux

```sh
pkg install openssh
passwd          # set the password the app asks for once
whoami          # the SSH user, something like u0_a123
sshd            # start the SSH server, on port 8022
```

`sshd` stops when Termux is closed; run it again after a restart, or start it with Termux:Boot. Use
port 8022 in the app. The app finds SillyTavern in Termux's home folder by itself. SillyTavern's
[Termux guide](https://docs.sillytavern.app/installation/android-(termux)/) covers installing it.

### Linux

On Debian or Ubuntu:

```sh
sudo apt install openssh-server
sudo systemctl enable --now ssh
```

Other distributions have the same `openssh` package and `sshd` service under their own names. If a
firewall such as `ufw` is on, allow SSH and SillyTavern's port: `sudo ufw allow 22/tcp` and
`sudo ufw allow 8000/tcp`.

### macOS

Open System Settings > General > Sharing, turn on **Remote Login**, and click the info button next to it
to choose which users may log in. Use your Mac user name and password in the app. If the macOS firewall
is on, allow `node` to accept incoming connections when macOS asks.

## Windows: SillyTavern in WSL

Native Windows (SillyTavern started from PowerShell or `Start.bat`) isn't supported. Run SillyTavern in
WSL instead: it is then a Linux server, and everything above applies. These steps use Ubuntu, the
default distribution.

**1. Install WSL.** Open PowerShell as administrator, run this, and restart the PC:

```powershell
wsl --install
```

It needs Windows 10 version 2004 (build 19041) or newer, or Windows 11. When Ubuntu opens the first time,
it asks for a Linux user name and password: these are the SSH user and password for the app.

**2. Install git, Node.js and SillyTavern** inside Ubuntu. SillyTavern needs Node.js 20 or newer;
Ubuntu's own `nodejs` package may be older, so check with `node --version`, or install the current LTS
with [nvm](https://github.com/nvm-sh/nvm) (`nvm install --lts`). stpanel finds Node installed through
nvm, fnm or Volta by itself.

```sh
sudo apt update && sudo apt install git curl python3
cd ~
git clone https://github.com/SillyTavern/SillyTavern -b release
cd SillyTavern
./start.sh      # installs its packages and starts it once; stop it with Ctrl+C
```

Then set up `config.yaml` as in [Let SillyTavern accept the phone](#let-sillytavern-accept-the-phone).
Keep SillyTavern in the Linux home folder (`~/SillyTavern`), not under `/mnt/c`.

**3. Install and start the SSH server.**

```sh
sudo apt install openssh-server
```

To have it start with WSL, either turn on systemd, or add a start command. For systemd, put this in
`/etc/wsl.conf` (`sudo nano /etc/wsl.conf`):

```ini
[boot]
systemd=true
```

Run `wsl --shutdown` in PowerShell, open Ubuntu again, and run `sudo systemctl enable --now ssh`.
Without systemd, use this in `/etc/wsl.conf` instead; WSL runs it as root each time Ubuntu starts:

```ini
[boot]
command=service ssh start
```

The `[boot]` settings need Windows 11 (or Windows Server 2022). On Windows 10, start `sshd` from the
Task Scheduler task in step 5 instead, with the arguments `-d Ubuntu -u root service ssh start`.

**4. Make it reachable from the phone.** WSL has its own network behind the PC, so the phone can't reach
it by default. Choose one:

- **Mirrored networking (Windows 11 22H2 or newer).** WSL then shares the PC's network addresses, so the
  phone uses the PC's address, for example `http://192.168.1.20:8000`. Create or edit
  `%UserProfile%\.wslconfig` (or use the WSL Settings app):

  ```ini
  [wsl2]
  networkingMode=mirrored
  ```

  Run `wsl --shutdown`, then open Ubuntu again. Windows' Hyper-V firewall blocks incoming connections to
  WSL by default; allow SillyTavern's port and SSH from an administrator PowerShell:

  ```powershell
  New-NetFirewallHyperVRule -Name "SillyTavern" -DisplayName "SillyTavern" -Direction Inbound -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts 8000
  New-NetFirewallHyperVRule -Name "WSL-SSH" -DisplayName "WSL SSH" -Direction Inbound -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts 22
  ```

  If Windows itself runs an SSH server on port 22, give WSL's `sshd` another port (`Port 2222` in
  `/etc/ssh/sshd_config`) and use that in the rule and in the app.

- **A port proxy (older Windows, or the default NAT networking).** Windows forwards its ports to WSL.
  Find WSL's address with `wsl hostname -I` (capital I), then in an administrator PowerShell:

  ```powershell
  netsh interface portproxy add v4tov4 listenport=8000 listenaddress=0.0.0.0 connectport=8000 connectaddress=<WSL address>
  netsh interface portproxy add v4tov4 listenport=2222 listenaddress=0.0.0.0 connectport=22 connectaddress=<WSL address>
  New-NetFirewallRule -DisplayName "SillyTavern via WSL" -Direction Inbound -Protocol TCP -LocalPort 8000,2222 -Action Allow
  ```

  The phone then uses the PC's address, with port 2222 for SSH. WSL's address can change when WSL
  restarts; if the phone stops reaching it, check `wsl hostname -I` and add the proxy again. Through the
  proxy, SillyTavern sees every connection coming from the PC, not from the phone, so use a login
  (`basicAuthMode` or user accounts) rather than the whitelist.

- **Tailscale.** Install Tailscale on Windows (Tailscale recommends the Windows side, not inside WSL),
  then use mirrored networking or the port proxy as above, with the PC's Tailscale address.

**5. Keep WSL and SillyTavern running.** WSL shuts a distribution down shortly after its last window
closes, and SillyTavern and SSH stop with it. To keep it running, add this to `%UserProfile%\.wslconfig`
and run `wsl --shutdown` once:

```ini
[general]
instanceIdleTimeout=-1
```

To start it when you log in to Windows, create a task in Task Scheduler with the trigger **At log on**,
the program `wsl.exe`, and the arguments `-d Ubuntu true`. That starts Ubuntu, and with it `sshd` (step
3). Then press Start in the Control Center, or, with [stpanel installed](#stpanel), use the arguments
`-d Ubuntu bash -lc "stpanel start"` to start SillyTavern as well. The PC must stay awake: set Windows'
sleep setting to Never while you use it from the phone.

## stpanel

[`server/stpanel`](server/stpanel) is a control panel for SillyTavern in one bash script. The app's
Control Center actions run it: the installed one if the server has `stpanel` on its PATH, otherwise the
app's built-in copy of the same script. You only need to install it if you want it in a terminal, too.
Both copies use the same files in the SillyTavern folder, so either can stop what the other started.

It runs on Termux, Linux, WSL and macOS (bash 3.2 or newer), and is tested on macOS's `/bin/bash` 3.2,
Debian and Alpine (`tools/test-stpanel-linux.sh`).

To install it for terminal use, put it in a folder on your PATH, such as `~/.local/bin` or `~/bin`:

```sh
mkdir -p ~/.local/bin
curl -fsSL https://raw.githubusercontent.com/HalfToast/ST-Mobile/main/server/stpanel -o ~/.local/bin/stpanel
chmod +x ~/.local/bin/stpanel
```

If that folder isn't on your PATH yet, add it in your shell's startup file. The app uses the installed
copy only if an SSH command finds it; check with `ssh <user>@<host> 'command -v stpanel'`. If nothing
prints, the app uses its own copy.

Run `stpanel` with no argument for the interactive panel, or with a command:

| Command | What it does |
| --- | --- |
| `status` | One-line health and metrics summary |
| `urls` | Every address SillyTavern can be reached at (local, Wi-Fi, VPN) |
| `start` | Start SillyTavern in the background, restarting it if it crashes |
| `stop` | Stop SillyTavern |
| `restart` | Restart SillyTavern |
| `backup` | Save the user folder and `config.yaml` to `backups/st_backup_<date>_<time>.tar.gz` |
| `clean` | Delete cached thumbnails and temporary files (asks first) |
| `whitelist` | Open the whitelist manager |
| `whitelist mode on\|off` | Turn whitelist mode on or off |
| `whitelist add <address>` | Allow an IP address or CIDR subnet |
| `stats` | Message counts and activity per character |
| `logs` | Follow SillyTavern's log |
| `access` | Show incoming connections |
| `update` | Update SillyTavern with `git pull` |
| `help` | List the commands |

Environment variables:

- `ST_DIR`: the SillyTavern folder (a leading `~/` is expanded). Default: Termux's
  `/data/data/com.termux/files/home/SillyTavern` if it exists, else `~/SillyTavern`.
- `ST_USER`: the SillyTavern user folder under `data/`. Default: `default-user`.
- `STPANEL_NODE_DIRS`: colon-separated folders searched for `node` after PATH, nvm, fnm and Volta.
  Default: `/opt/homebrew/bin`, `/usr/local/bin` and Termux's bin folder. Set it empty to search none
  of them.
- `STPANEL_REFRESH`: seconds between refreshes of the interactive panel (default 5).

stpanel only ever looks up and stops the SillyTavern in its own folder: the process in its pid file, and
`node` processes running that folder's `server.js`. Other Node apps on the machine are left alone. It
starts SillyTavern as `node server.js`, without `npm install`: run `./start.sh` once after cloning, and
again after an update that changes SillyTavern's packages.

## Using the app

### Navigation

There is no tab bar and nothing floats over the chat. Back is the menu: it first closes the keyboard,
or whatever SillyTavern has open (a drawer, a popup, a dropdown's list). With nothing open, it dims the
chat and slides in a column of buttons, low down on the side you swiped from (the right with the
3-button navigation): Control Center, Stats, Logs, Reload, which reloads SillyTavern straight away, and
Quit, which sends the app to the background the way Home does (SillyTavern stays loaded). Back or a tap
outside closes the menu. Back never leaves the app by itself.

Those screens open full screen with a slim top bar. It has a back arrow, buttons for the other two
screens, and on the Control Center a Settings gear. Until SSH is set up, all three show the setup form
instead.

### When the server can't be reached

While the chat loads, a small "Connecting…" pill shows at the top ("Reconnecting…" once the page has
been up). If the connection drops while you're using it, the chat is covered with a message instead of
breaking, and it comes back once the server answers again. If SillyTavern can't be reached, the chat says
why as far as the phone can tell:

- **SillyTavern isn't running**: the machine answers, but nothing listens on SillyTavern's port. It
  offers Start server, which needs SSH set up.
- **Can't reach the server**: no answer at all. Check that the machine is on and awake, and that the
  phone is on the same network or VPN.
- **Tailscale is off on this phone**: only for a server at a Tailscale address (see
  [Known limits](#known-limits)). It offers Open Tailscale, and retries by itself once a VPN is on. With
  Tailscale on and no answer, the message is "The server isn't answering" instead.

### Look

The native screens use SillyTavern's own theme colors. The app reads them from SillyTavern when the chat
loads. If you change the theme in SillyTavern, the native screens follow after the next chat load. While
the server is down, the app uses the colors it remembered last. On a fresh install, before it has read
anything, it uses the Glimmer theme. Text uses the same font as SillyTavern (Noto Sans, bundled in the
app), scaled by SillyTavern's font scale. Logs use Noto Sans Mono.

SillyTavern's dropdowns open as a themed bottom sheet instead of Android's plain list. Long lists, such
as a provider's hundreds of models, open quickly and can be searched; very long option names are cut
short with "…". The sheet is added to the page on the phone only; nothing changes on the server. If it
cannot attach, the dropdown falls back to the plain list. SillyTavern's searchable (select2) dropdowns
are restyled to match.

Movement is short and quick: the other screens slide in over the chat and back out, the back menu and
the list sheet slide open and shut, dialogs fade in, and buttons light up softly when pressed.

The app icon is SillyTavern's logo, with a themed-icon version for Android 13 and up. On a cold start a
short pulse splash shows first.

## Keeping the app alive in the background

Turn on keep-alive in Settings. If Android still closes the app in the background, or the notification
disappears, let the app run without battery restrictions. Settings has a Battery button that jumps
there. Many phone makers add their own battery savers on top; [dontkillmyapp.com](https://dontkillmyapp.com)
lists the settings per brand. On Xiaomi phones (HyperOS/MIUI), for example:

- Settings > Apps > Manage apps > ST Mobile > enable **Autostart**.
- In the same page, open **Battery** and choose **No restrictions**.
- Lock the app in the recent apps view.

The same applies to Termux if SillyTavern runs on an Android device: stpanel holds a Termux wake lock
while SillyTavern runs, but the device's battery saver can still stop Termux.

## Known limits

- Control Center actions expect SillyTavern to run as plain `node server.js` on the SSH machine, started
  by stpanel (the app's or your own) or by hand, for example with `./start.sh`. If Docker, systemd, pm2
  or a launcher runs it, the dashboard may still show it online, but Start, Stop and Restart don't work
  with those.
- Native Windows isn't supported: SillyTavern started from PowerShell or `Start.bat`, or an SSH login
  that lands in PowerShell. Use [WSL](#windows-sillytavern-in-wsl).
- The Tailscale-specific messages and the Open Tailscale button appear only when the server's address is
  a Tailscale address: in `100.64.0.0/10` or `fd7a:115c:a1e0::/48`, or a name ending in `.ts.net`. A
  MagicDNS short name (just the machine name) or your own DNS name gets the general messages.
- The whitelist manager can add entries but not remove them. To remove one, edit `config.yaml` on the
  server.
- Update runs `git pull` only. If an update changes SillyTavern's packages, run `npm install` (or
  `./start.sh` once) in the SillyTavern folder.

## Build it yourself

You need JDK 17 and the Android SDK.

```sh
./gradlew :app:assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Without signing keys it is
`app-release-unsigned.apk` and the build prints a warning. For debugging use `./gradlew :app:installDebug`.
The unit tests run with `./gradlew :app:testDebugUnitTest`.

These helpers live in `tools/`:

- `tools/web-test.sh <file.html>` runs a JavaScript test page in headless Chrome, for example
  `tools/web-test.sh app/src/test/web/select-sheet.test.html`.
- `tools/test-stpanel-linux.sh` runs stpanel and the app's server scripts on Debian and Alpine in Docker.
- `tools/check-personal.sh` checks that no personal details are in the tracked files.
- `tools/render-icons.sh` regenerates the icon PNGs from `tools/st-logo.svg`.
- `tools/bench-select-sheet.html` times how long a 1000-option list takes to open as a sheet in headless
  Chrome; the command is in the comment at its top.

### Signing

Put these four lines in `~/.gradle/gradle.properties` (never in the repo):

```properties
STMOBILE_STORE_FILE=/path/to/your-release.jks
STMOBILE_STORE_PASSWORD=...
STMOBILE_KEY_ALIAS=...
STMOBILE_KEY_PASSWORD=...
```

`STMOBILE_STORE_FILE` may start with `~/` (for example `~/keys/st-mobile.jks`); it is expanded to your home
directory. Other paths are used as written, so give an absolute path.

Create the keystore once with `keytool -genkeypair -keystore <file> -alias <alias> -keyalg RSA -keysize 4096 -validity 36500`
and keep a backup of the file and its password: updates must be signed with the same key. keytool makes a
PKCS12 keystore, which has a single password, so the store and key passwords are the same value.

### Install

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

or copy the APK to the phone and open it (allow installing from that source when Android asks). On
Xiaomi phones, `adb install` shows a prompt on the phone that must be accepted within a few seconds, or
the install fails with `INSTALL_FAILED_USER_RESTRICTED`.
