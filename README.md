# ST Mobile

An Android app that shows your SillyTavern (running on your tablet in Termux) on your phone, and adds a
native Control Center that manages the server over SSH. It has four tabs.

- **Chat**: SillyTavern exactly as it is in a browser, in a full-screen WebView. Downloads work. Static
  files are cached on the phone, which cut cold reloads from about 12.6 s to 9.5 s. The last screen is
  saved as a snapshot, so reopening the app shows your chat straight away while the page reloads. An
  optional keep-alive notification keeps the app loaded in the background.
- **Panel**: status (online, uptime, CPU/RAM, battery, disk, cache, inventory), and the actions Start,
  Stop, Restart, Update, Backup and Clean cache. Stop, Clean cache and Update ask first. The whitelist
  manager lets you add an IP and switch whitelist mode; switching it asks for confirmation. Note: adding
  an IP is permanent through the app (stpanel cannot remove entries; edit `config.yaml` on the tablet to
  remove one), and it has only been tested by automated tests, not live.
- **Stats**: every character with message count and last-active time, most recent first. Pull to refresh.
- **Logs**: live server log or access log (last 1000 lines). Leaving the tab or backgrounding the app stops
  the remote `tail`.

## First-run setup

1. Turn Tailscale on, on the phone and on the tablet.
2. Open the app. Chat works right away once the server URL is right.
3. Open Panel (or Stats, Logs). The setup screen asks for the tablet's SSH port (Termux uses 8022) and
   user name, and your Termux password **once**. The app then creates a key, installs it on the tablet and
   uses it from then on. The password is not stored.
4. The first connection pins the tablet's host key. If it ever changes (for example Termux was
   reinstalled), the app says so; use "Trust new host key" in Settings only if you expect that.

You can re-run setup or change the server URL, SillyTavern user folder and keep-alive in Settings
(the gear icon on the Panel).

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

### Signing

Put these four lines in `~/.gradle/gradle.properties` (never in the repo):

    STMOBILE_STORE_FILE=/path/to/your-release.jks
    STMOBILE_STORE_PASSWORD=...
    STMOBILE_KEY_ALIAS=...
    STMOBILE_KEY_PASSWORD=...

Create the keystore once with `keytool -genkeypair -keystore <file> -alias <alias> -keyalg RSA -keysize 4096 -validity 36500`
and keep a backup: updates must be signed with the same key.

## Install

    adb install -r app/build/outputs/apk/release/app-release.apk

or copy the APK to the phone and open it. A build signed with a different key cannot update an existing
install; you would have to uninstall first, which erases the app's data (setup and ST login).
