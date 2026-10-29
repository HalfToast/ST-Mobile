# ST Mobile

An Android app that shows your SillyTavern (running on your tablet in Termux) on your phone, and adds
native screens that manage the server over SSH. It has four screens: Chat, Control Center, Stats and Logs.

- **Chat**: SillyTavern exactly as it is in a browser, in a full-screen WebView. Downloads work. Static
  files are cached on the phone, which cut a cold reload from about 12.6 s to 9.5 s when first measured;
  how long a reload takes depends on how busy the tablet is (15–19 s on a slower day). The last screen is
  saved as a snapshot, so reopening the app shows your chat straight away while the page reloads. An
  optional keep-alive notification keeps the app loaded in the background. It shows SillyTavern's logo and
  the text "SillyTavern stays loaded".
- **Control Center**: status (online, uptime, CPU/RAM, battery, disk, cache, inventory), and the actions
  Start, Stop, Restart, Update, Backup and Clean cache. Stop, Clean cache and Update ask first. The
  whitelist manager lets you add an IP and switch whitelist mode; switching it asks for confirmation.
  Note: adding an IP is permanent through the app (stpanel cannot remove entries; edit `config.yaml` on
  the tablet to remove one), and it has only been tested by automated tests, not live. Actions keep
  running on the tablet even if the app gives up waiting (60 s, or 5 min for Update and Backup) or is
  closed; the result line then says so, and the dashboard shows the outcome.
- **Stats**: every character with its avatar from SillyTavern, message count and last-active time, most
  recent first. Pull to refresh.
- **Logs**: live server log or access log (last 1000 lines), in a monospace font. Leaving the screen or
  backgrounding the app stops the remote `tail`.

## Navigation

There is no tab bar. A small floating button sits over the chat. Drag it anywhere; it snaps to the left
or right edge and remembers where you left it. It hides while the keyboard is open. Tap it and it fans
out three buttons: Control Center, Stats and Logs.

Those screens open full screen with a slim top bar. It has a back arrow, buttons for the other two
screens, and on the Control Center a Settings gear.

## Look

The native screens use SillyTavern's own theme colors. The app reads them from SillyTavern when the chat
loads. If you change the theme in SillyTavern, the native screens follow after the next chat load. While
the server is down, the app uses the colors it remembered last. On a fresh install, before it has read
anything, it uses the Glimmer theme. Text uses the same font as SillyTavern (Noto Sans, bundled in the
app), scaled by SillyTavern's font scale. Logs use Noto Sans Mono.

SillyTavern's dropdowns open as a themed bottom sheet instead of Android's plain list. The sheet is
injected into the page on the phone only; nothing changes on the tablet. If it cannot attach, the
dropdown falls back to the plain list. SillyTavern's searchable (select2) dropdowns are restyled to match.

The app icon is SillyTavern's logo, with a themed-icon version for Android 13 and up. On a cold start a
short pulse splash shows first.

## First-run setup

1. Turn Tailscale on, on the phone and on the tablet.
2. Open the app. Chat works right away once the server URL is right.
3. Open the Control Center (or Stats, Logs) from the floating button. The setup screen asks for the
   tablet's SSH port (Termux uses 8022) and user name, and your Termux password **once**. The app
   then creates a key, installs it on the tablet and uses it from then on. The password is not stored.
4. The first connection pins the tablet's host key. If it ever changes (for example Termux was
   reinstalled), the app says so; use "Trust new host key" in Settings only if you expect that.

You can re-run setup or change the server URL, SillyTavern user folder and keep-alive in Settings
(the gear icon on the Control Center).

## Xiaomi / HyperOS tips

If the app gets killed in the background or the keep-alive notification disappears:

- Settings > Apps > Manage apps > ST Mobile > enable **Autostart**.
- In the same page, open **Battery** and choose **No restrictions**. (Settings has a Battery button that
  jumps there.)
- Lock the app in the recent apps view.

## Build

You need JDK 17 and the Android SDK.

    ./gradlew :app:assembleRelease

The APK is `app/build/outputs/apk/release/app-release.apk`. Without signing keys it is
`app-release-unsigned.apk` and the build prints a warning. For debugging use `./gradlew :app:installDebug`.

Two helper scripts live in `tools/`:

- `tools/web-test.sh <file.html>` runs a JavaScript test page in headless Chrome, for example
  `tools/web-test.sh app/src/test/web/select-sheet.test.html`.
- `tools/render-icons.sh` regenerates the icon PNGs from `tools/st-logo.svg`.

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

or copy the APK to the phone and open it. On HyperOS/MIUI, `adb install` shows a prompt on the phone that
must be accepted within a few seconds, or the install fails with `INSTALL_FAILED_USER_RESTRICTED`.
A build signed with a different key cannot update an existing
install; you would have to uninstall first, which erases the app's data (setup and ST login).
