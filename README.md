# ST Mobile

An Android app that shows your SillyTavern (running on your tablet in Termux) on your phone, and adds a
native Control Center that manages the server over SSH. It has four tabs.

- **Chat**: SillyTavern exactly as it is in a browser, in a full-screen WebView. Downloads work. Static
  files are cached on the phone, which cut a cold reload from about 12.6 s to 9.5 s when first measured;
  how long a reload takes depends on how busy the tablet is (15–19 s on a slower day). The last screen is
  saved as a snapshot, so reopening the app shows your chat straight away while the page reloads. An
  optional keep-alive notification keeps the app loaded in the background.
- **Panel**: status (online, uptime, CPU/RAM, battery, disk, cache, inventory), and the actions Start,
  Stop, Restart, Update, Backup and Clean cache. Stop, Clean cache and Update ask first. The whitelist
  manager lets you add an IP and switch whitelist mode; switching it asks for confirmation. Note: adding
  an IP is permanent through the app (stpanel cannot remove entries; edit `config.yaml` on the tablet to
  remove one), and it has only been tested by automated tests, not live. Actions keep running on the
  tablet even if the app gives up waiting (60 s, or 5 min for Update and Backup) or is closed; the
  result line then says so, and the dashboard shows the outcome.
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
