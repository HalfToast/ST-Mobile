# ST Mobile

An Android app for using [SillyTavern](https://github.com/SillyTavern/SillyTavern) on your phone. It
shows your SillyTavern server's page full screen, and can add native screens for running the server
over SSH. It has four screens: Chat, Control Center, Stats and Logs.

The server can run anywhere the phone can reach: a PC on the same Wi-Fi, a home server behind a VPN
such as Tailscale, or another Android device running SillyTavern in Termux. Chat needs only the
server's address. Control Center, Stats and Logs also need SSH access to that machine (see
[Requirements](#requirements)).

- **Chat**: SillyTavern exactly as it is in a browser, in a full-screen WebView. Downloads work. Static
  files are cached on the phone, so reloads are faster than in a browser. The last screen is saved as a
  snapshot, so reopening the app shows your chat straight away while the page reloads. An optional
  keep-alive notification keeps the app loaded in the background. It shows SillyTavern's logo and the
  text "SillyTavern stays loaded".
- **Control Center**: status (online, uptime, CPU/RAM, battery, disk, cache, inventory), and the actions
  Start, Stop, Restart, Update, Backup and Clean cache. Stop, Clean cache and Update ask first. The
  whitelist manager lets you add an IP and switch whitelist mode; switching it asks for confirmation.
  Note: adding an IP is permanent through the app (stpanel cannot remove entries; edit `config.yaml` on
  the server to remove one), and it has only been tested by automated tests, not live. Actions keep
  running on the server even if the app gives up waiting (60 s, or 5 min for Update and Backup) or is
  closed; the result line then says so, and the dashboard shows the outcome.
- **Stats**: every character with its avatar from SillyTavern, message count and last-active time, most
  recent first. Pull to refresh.
- **Logs**: live server log or access log (last 1000 lines), in a monospace font. Leaving the screen or
  backgrounding the app stops the remote `tail`.

## Requirements

**Phone:** Android 10 or newer.

**For Chat:** a SillyTavern server the phone can reach, for example `http://192.168.1.20:8000`.
SillyTavern only accepts connections from its own machine by default, so set `listen: true` in its
`config.yaml`. Then either add the phone's IP to the whitelist, or turn on basic auth or user accounts.
See SillyTavern's documentation on remote connections.

**For Control Center, Stats and Logs**, the machine running SillyTavern also needs:

- an SSH server the phone can reach, and a user account with a password (needed once, during setup);
- SillyTavern installed in that user's home folder, at `~/SillyTavern`;
- `bash` for the dashboard, and `python3` for Stats;
- `stpanel`, a SillyTavern control-panel script, on that user's `PATH`. Start, Stop, Restart, Update,
  Backup, Clean cache and the whitelist manager all run through it. The server log is the file it
  writes, `~/SillyTavern/sillytavern.log`.

Termux on Android works: install `openssh` and run `sshd`, which listens on port 8022, and set a
password with `passwd`. A Linux or macOS machine with an SSH server works the same way, usually on port 22.

## Navigation

There is no tab bar and nothing floats over the chat. Back is the menu: it first closes the keyboard,
or whatever SillyTavern has open (a drawer, a popup, a dropdown's list). With nothing open, it dims the
chat and slides in a column of buttons, low down on the side you swiped from (the right with the
3-button navigation): Control Center, Stats, Logs, Reload, which reloads SillyTavern straight away, and
Quit, which sends the app to the background the way Home does (SillyTavern stays loaded). Back or a tap
outside closes the menu. Back never leaves the app by itself.

Those screens open full screen with a slim top bar. It has a back arrow, buttons for the other two
screens, and on the Control Center a Settings gear.

While the chat reconnects, a small "Reconnecting…" pill shows at the top. If the connection drops while
you're using it, the chat is covered with a message instead of breaking, and it comes back once the
server answers again. If SillyTavern can't be reached, the chat says why as far as the phone can tell:

- the phone's VPN is off (it offers to open Tailscale and retries by itself once a VPN is on);
- the machine answers but SillyTavern isn't running (it offers to start the server, which needs SSH set
  up);
- the machine doesn't answer at all (it may be asleep or offline).

These checks assume the server is reached through a VPN. Without one (for example on home Wi-Fi), see
[Known limits](#known-limits).

Movement is short and quick: those screens slide in over the chat and back out, the back menu and the
list sheet slide open and shut, dialogs fade in, and buttons light up softly when pressed.

## Look

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

The app icon is SillyTavern's logo, with a themed-icon version for Android 13 and up. On a cold start a
short pulse splash shows first.

## First-run setup

1. Make sure the phone can reach the server: open its address in the phone's browser once. If you use
   a VPN such as Tailscale, turn it on first.
2. Open the app, press back, and open the Control Center.
3. Chat only: tap the Settings gear, enter your SillyTavern URL and save. Chat works from then on.
   Settings currently won't save without the SSH host and user filled in, even if you never use SSH.
4. With SSH: the setup screen asks for the SillyTavern URL, the SSH host (the server's address), port
   and user, the SillyTavern user folder (`default-user` unless you use accounts), and the SSH
   password **once**. The app then creates a key, adds it to the user's `~/.ssh/authorized_keys` and
   uses it from then on. The password is not stored.
5. The first connection pins the server's host key. If it ever changes (for example after reinstalling
   Termux or the SSH server), the app says so; use "Trust new host key" in Settings only if you
   expect that.

You can re-run setup or change the server URL, SillyTavern user folder and keep-alive in Settings
(the gear icon on the Control Center).

## Known limits

The app was built for one setup (SillyTavern in Termux on a tablet, reached over Tailscale) and has
only been tested there. In other setups:

- Without a VPN on the phone, an unreachable server is always reported as "Tailscale is off", and
  some wording still says "tablet".
- Without a VPN, a server protected by basic auth or a login page counts as unreachable, so the
  connection-lost cover, once shown after a dropped connection, may not lift by itself.
- The default SillyTavern URL and SSH host are the author's own, so set yours during setup.

## Keeping the app alive in the background

Turn on keep-alive in Settings. If Android still closes the app in the background, or the notification
disappears, let the app run without battery restrictions. Settings has a Battery button that jumps
there. On Xiaomi phones (HyperOS/MIUI) also:

- Settings > Apps > Manage apps > ST Mobile > enable **Autostart**.
- In the same page, open **Battery** and choose **No restrictions**.
- Lock the app in the recent apps view.

Other brands (Samsung, OnePlus, Huawei…) have similar settings; [dontkillmyapp.com](https://dontkillmyapp.com)
lists them per brand.

## Build

You need JDK 17 and the Android SDK.

    ./gradlew :app:assembleRelease

The APK is `app/build/outputs/apk/release/app-release.apk`. Without signing keys it is
`app-release-unsigned.apk` and the build prints a warning. For debugging use `./gradlew :app:installDebug`.

These helpers live in `tools/`:

- `tools/web-test.sh <file.html>` runs a JavaScript test page in headless Chrome, for example
  `tools/web-test.sh app/src/test/web/select-sheet.test.html`.
- `tools/render-icons.sh` regenerates the icon PNGs from `tools/st-logo.svg`.
- `tools/bench-select-sheet.html` times how long a 1000-option list takes to open as a sheet in headless
  Chrome; the command is in the comment at its top.

### Signing

Put these four lines in `~/.gradle/gradle.properties` (never in the repo):

    STMOBILE_STORE_FILE=/path/to/your-release.jks
    STMOBILE_STORE_PASSWORD=...
    STMOBILE_KEY_ALIAS=...
    STMOBILE_KEY_PASSWORD=...

`STMOBILE_STORE_FILE` may start with `~/` (for example `~/keys/st-mobile.jks`); it is expanded to your home
directory. Other paths are used as written, so give an absolute path.

Create the keystore once with `keytool -genkeypair -keystore <file> -alias <alias> -keyalg RSA -keysize 4096 -validity 36500`
and keep a backup of the file and its password: updates must be signed with the same key. keytool makes a
PKCS12 keystore, which has a single password, so the store and key passwords are the same value.

## Install

    adb install -r app/build/outputs/apk/release/app-release.apk

or copy the APK to the phone and open it (allow installing from that source when Android asks). On
Xiaomi phones, `adb install` shows a prompt on the phone that must be accepted within a few seconds, or
the install fails with `INSTALL_FAILED_USER_RESTRICTED`.

A build signed with a different key cannot update an existing install; you would have to uninstall
first, which erases the app's data (setup and SillyTavern login).
