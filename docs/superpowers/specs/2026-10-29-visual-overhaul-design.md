# ST Mobile: visual overhaul — design

Date: 2026-10-29
Owner: HalfToast
Builds on: `2026-10-26-st-mobile-design.md` (all its constraints still hold)

## What this is and why

ST Mobile works, but it looks like a generic Android app wrapped around HalfToast's SillyTavern: a tall light tab bar eats space under the chat, the Panel, Stats and Logs screens use Android's default light purple theme next to ST's dark one, and ST's own dropdowns (model, preset, source…) open Android's plain radio-list dialog, which HalfToast called "bland and ugly". Unnamed `<optgroup>`s in ST's markup even show up there as empty blank rows.

HalfToast wants three things, in their words: as much real estate as possible for SillyTavern; Panel, Stats and Logs that "represent the sillytavern design with the same color scheme", following what they set under theme colors in ST's User Settings; and selection popups that "look like it's made for sillytavern". Everything else is left to the builder's judgment.

HalfToast's current ST theme is **Glimmer — by Rivelle**: background `rgb(24,24,27)`, text `rgb(195,195,195)`, italics `rgb(150,152,158)`, quotes `rgb(170,219,255)`, borders `rgba(255,255,255,0.08)`, user/bot message tints `rgba(255,255,255,0.07)` / `0.03`, font scale 1.1, ST font Noto Sans.

## Goals

1. **Chat gets the whole screen.** The bottom tab bar is gone.
2. **One look across the app**, taken from HalfToast's live ST theme.
3. **ST's selection lists open as a themed bottom sheet** instead of Android's dialog.
4. **The app gets SillyTavern's icon, a proper notification and a short splash animation** (added by HalfToast after the first draft).
5. Fix the light strip that shows under the chat while the keyboard slides up (deferred from the keyboard-lag fix, `eff20e1`, to this overhaul by HalfToast).

## Non-goals

- Changing anything on the tablet (ST, its theme, extensions, config, `stpanel`). Everything here happens on the phone.
- Restyling ST's own page beyond the selection lists. The chat itself looks exactly as in Chrome. The earlier spec said nothing may be injected into the page that changes its look; the selection lists (goal 3) are the one narrow exception HalfToast asked for.
- A light theme or a theme picker inside the app. The app follows ST.

## Anti-goals (fails even if it technically works)

- Picking something in the new sheet doesn't take effect in ST exactly as it would with the native list (ST must see the same value and the same `change`/`input` events).
- The Chat tab gets slower, reloads, or loses a generation because of any of this. The keyboard-lag fix must not regress (see Verification).
- The floating button permanently covers an ST control with no way to move it.
- A future ST update leaves a list unusable. If the sheet can't attach, the plain native list must still work.

## Decisions HalfToast made

Each was shown as a mockup (kept in `.superpowers/brainstorm/83211-1793264355/content/`) or played back in words and approved.

### 1. Selection sheet (mockup `picker-style.html`, option A)

- Every native `<select>` dropdown in ST opens a **bottom sheet** instead of Android's dialog: grab handle, the field's label as title, the options as rows.
- Current choice: light-blue tint (ST quote color), a left accent bar and a ✓.
- Unnamed `<optgroup>`s become a thin divider line. Named ones get a small header *(builder's call: ST's main lists use unnamed groups, but named ones should not lose their label)*.
- Colors come from ST's live theme at the moment it opens, so switching ST themes restyles the sheet.
- A search field appears only on long lists (more than about 8 options).
- The sheet is as tall as its content, up to about 75% of the screen.
- Picking a row applies it and closes the sheet. Swipe down, tapping the dimmed area, or Back closes it without changing anything.
- If ST changes and the sheet can't attach, the plain native list still works.
- ST's own search-box dropdowns (select2, used for some long model lists) are restyled to match the sheet *(builder's call: a CSS reskin of select2's own dropdown, not a switch to the sheet)*.
- Back closes an open sheet without changing anything *(builder's call: the sheet must be visible to `WebViewHolder.probeBack`'s `OPEN_UI_SELECTOR` check, or close itself on Escape)*.
- *(Builder's call, not discussed)* `<select multiple>` (OpenRouter providers/quantizations, Horde models) also uses the sheet, with toggle rows and a Done button. Disabled options show dimmed and can't be picked. Disabled selects don't open.

### 2. Navigation (mockup `navbar.html`, option B)

- **No tab bar.** In Chat, SillyTavern fills the screen down to the gesture handle (above it, not underneath).
- A **small, round, semi-transparent floating button**, default position: right edge, about halfway up. Tapping it fans out three icons: Control Center, Stats, Logs. Tapping elsewhere closes the fan.
- The button can be dragged up/down and to the left edge, snaps to the nearest edge, and remembers its position across launches.
- It hides while the keyboard is open.
- Panel, Stats and Logs each open full screen with a slim top bar: back arrow (to Chat), the screen's name, small icons for the other two screens, and on the Control Center the Settings gear.
- Back on Panel, Stats or Logs returns to Chat. In Chat it behaves as today (`BackPressPolicy`).
- *(Builder's call)* The debug-only "long-press kills the renderer" moves from the Chat tab to a long-press on the floating button, debug builds only.

### 3. Native screens in ST's style (mockup `panel-style.html`, option A)

- Colors are read from ST's theme whenever the chat loads: text, muted text, accent, background and border. When ST changes theme, the native screens follow on the next chat load.
- The last colors are remembered, so the screens look right while the server is down. A fresh install starts with Glimmer.
- Font: Noto Sans, scaled by ST's font scale. Logs and the recent-log box use Noto Sans Mono.
- **Control Center:** like ST's settings drawers. Flat key/value rows in one bordered box, square-ish ST-style buttons (`menu_button` look), Stop tinted red, Start dimmed when online. "Last client", "Whitelist" and "Recent log" stay, in the same style.
- **Stats:** a compact list like ST's character list: name, last active, message count, **with each character's avatar from ST**. HalfToast approved the playback that proposed avatars ("Looks good").
- **Logs:** the Server/Access switch becomes ST-style buttons. Text is mono, with warnings and errors tinted.
- **Everything else** gets the same look: confirm dialogs, the whitelist manager, Settings, Setup, and the Chat overlays (Reconnecting pill, progress bar, Still-loading bar, Unreachable screen). Dialogs look like ST's own popups. The phone's status bar and gesture-bar area are dark, matching.

### 4. Icon, notification and splash (mockup `icon-splash.html`, splash option B)

- **App icon:** "just the sillytavern icon" (HalfToast). ST's own logo (red circuit brain `#a11b1b` with white "ST") on ST's dark grey, as in ST's `public/img/apple-icon-512x512.png`, as an adaptive icon. It includes a monochrome layer for Android's themed icons, where the "ST" letters are cut out of the brain. The source vector is ST's `public/img/logo.svg` (two paths: `path9` red mark, `path2` white letters). Copy it into the app; nothing on the tablet changes. The launcher name stays "ST Mobile" *(builder's call: HalfToast didn't object when asked)*.
- **Keep-alive notification:** the small icon is the monochrome ST logo, tinted ST red. The text is "SillyTavern stays loaded" / "Tap to open your chat" (played back, not objected to). Tapping opens the app, as today.
- **Splash, option B "Pulse":** on cold start only, the logo fades in on the Glimmer background (`rgb(24,24,27)`), then the red circuit glows brighter once, like powering on. Under one second in total. It never delays the app: the chat starts loading behind it. The splash is static resources, so it can't follow a changed ST theme; it stays Glimmer *(builder's call)*. Android 12+ SplashScreen with an animated vector icon is the expected route. A CSS-style glow isn't available in vector drawables, so approximate it (e.g. animate the red fill brighter and back, or a soft halo layer's alpha) *(builder's call)*.

## Constraints

- Nothing on the tablet changes. Reading ST's files over SSH or HTTP is fine.
- Android 16 (an Android phone, HyperOS), targetSdk 36, so edge-to-edge is enforced; minSdk 29.
- Chat performance: keep `WindowInsets.imeAnimationTarget` for the Chat padding (one WebView resize per keyboard open/close). Don't reintroduce per-frame WebView resizing.
- Release builds are signed with HalfToast's key (`~/keys/st-mobile.jks`) and installed with `adb install -r`, which keeps app data. Never uninstall during testing. HyperOS asks for an on-phone tap on every adb install.
- During device tests, never tap inside the ST page (HalfToast's rule). Native screens are fine. Ask before running stpanel actions on the live server.
- Existing tests (166) stay green. The project follows TDD.

## Facts the builder needs

- **Injection points already exist.** `Downloads.kt` registers a document-start script with `WebViewCompat.addDocumentStartJavaScript(webView, …, setOf(origin))` and a JS bridge. `WebViewHolder` runs `evaluateJavascript` for its readiness probe. The sheet script should be origin-scoped the same way.
- **ST theme variables** (set by ST on `:root`): `--SmartThemeBodyColor` (text), `--SmartThemeEmColor` (muted), `--SmartThemeQuoteColor` (accent), `--SmartThemeBlurTintColor` (background), `--SmartThemeBorderColor`, `--SmartThemeUserMesBlurTintColor`, `--SmartThemeBotMesBlurTintColor`, `--SmartThemeShadowColor`, `--SmartThemeChatTintColor`, plus `--fontScale` and `--mainFontFamily` (`"Noto Sans", sans-serif`). Values can be `rgba(...)` with alpha.
- **ST markup:** `#chat_completion_source` has two unnamed `<optgroup>`s. Multi-selects: `#horde_model`, `.openrouter_providers`, `.openrouter_quantizations`. ST uses select2 on some selects.
- **Stats data** comes from `assets/probes/stats.py`: `name\tmessages\tlast_active` per character, where `name` is the character file's basename in `data/default-user/characters`. ST serves avatar thumbnails at `/thumbnail?type=avatar&file=<file>`, which needs the ST login session (WebView cookies). `stats.py` strips the extension, but `file=` needs the real file name (e.g. `Agnes.png`). The script ships inside the app and runs over SSH, so it may be changed to also print the file name *(builder's call; nothing on the tablet changes)*.
- **Current layout code:** `MainActivity.kt` (Scaffold + `NavigationBar`, overlays), `chat/ChatScreen.kt` (overlays), `panel/PanelScreen.kt`, `panel/WhitelistDialog.kt`, `stats/StatsScreen.kt`, `logs/LogsScreen.kt`, `settings/*`. `res/values/themes.xml` is the window theme.
- **The light strip** under the keyboard is whatever sits behind the Chat content while the WebView is already at its final height and the keyboard is still sliding: today the Scaffold background and the NavigationBar.

## Left to the builder

- How to intercept native `<select>` popups in Android WebView reliably (event to cancel, focus and keyboard behavior, accessibility). The sheet should be rendered in-page, inside ST, so it uses ST's CSS variables directly *(builder's call: no native bridge needed, theme-following comes free)*. It also needs a fallback path.
- How to read and observe ST's theme from the app (on Ready, plus on theme change in ST if cheaply observable), how to persist it, and how to map alpha colors onto opaque Compose surfaces.
- Avatar loading and caching (HTTP thumbnail with WebView cookies is the expected route), and the fallback (initial in a circle) when there's no avatar or ST is down.
- Bundling Noto Sans / Noto Sans Mono (OFL) vs. downloadable fonts.
- The exact look of the floating button and fan (size, opacity, icons), within the approved mockup.
- Splitting into tasks and their order.

## Verification

- Unit tests for the pure parts (theme parsing and mapping, persistence, button position snapping, Stats/avatar mapping).
- On device:
  - **Every list kind:** open a long single list (Chat Completion Source), a short one, a multi-select and a select2 list, and confirm ST applied the choice (the field shows it and the dependent UI changes). HalfToast does these taps, or the builder asks first, since it's inside the ST page.
  - **Keyboard performance:** `dumpsys gfxinfo` with 3–4 keyboard open/close cycles stays near the post-fix baseline (janky ≈ 1%, p90 ≈ 17 ms), and no light strip is visible.
  - **Native screens:** screenshots of Panel, Stats, Logs, Settings and dialogs in Glimmer colors. Switch ST to another theme and confirm the native screens follow after the next chat load.
  - **Floating button:** drag, snap, persistence across restart, hidden with the keyboard.
  - **Icon, notification, splash:** home-screen and themed-icon screenshots, the notification with keep-alive on, and a screen recording of a cold start showing the pulse, with time to the first chat frame no worse than before.
