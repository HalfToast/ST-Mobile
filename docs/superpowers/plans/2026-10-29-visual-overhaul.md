# ST Mobile visual overhaul — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ST Mobile look made for HalfToast's SillyTavern: no tab bar (floating button instead), native screens in ST's settings style using the live ST theme, ST's dropdowns as a themed bottom sheet, ST's icon, a proper notification and a short splash.

**Architecture:** A `ui` package holds the theme model (parsed from ST's CSS variables, persisted, exposed as a `StateFlow`), a Compose theme and a few ST-style components that every native screen uses. The WebView gets two origin-scoped page hooks: a theme reader/watcher that reports ST's colors to the app, and an in-page selection sheet (plain JS + CSS, using ST's own CSS variables). Navigation drops the Scaffold `NavigationBar` for a `Screen` state, a draggable floating button and a slim top bar.

**Tech Stack:** Kotlin 2.4, Jetpack Compose (BOM 2026.06.01, Material3), AndroidX WebKit 1.17 (`addDocumentStartJavaScript`, `addWebMessageListener`), `androidx.core:core-splashscreen`, JUnit 4 + org.json for JVM tests, headless Chrome for the in-page JS tests.

**Spec:** `docs/superpowers/specs/2026-10-29-visual-overhaul-design.md` (read it; it builds on `docs/superpowers/specs/2026-10-26-st-mobile-design.md`).

## Global Constraints

- Nothing on the tablet changes. Reading ST files over SSH/HTTP is fine; `assets/probes/*` ship in the app and may change.
- Device: an Android phone, Android 16, HyperOS, adb serial `<serial>`. targetSdk 36 (edge-to-edge enforced), minSdk 29.
- Never tap inside the ST page during device tests; ask HalfToast to do those taps. Native screens may be tapped via adb. Ask before running any stpanel action on the live server.
- Install only with `./gradlew :app:assembleRelease && adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk` (keeps data; HalfToast must tap Install on the phone within seconds). Never uninstall. Never install the debug build (different key → would need uninstall).
- Chat keeps `windowInsetsPadding(WindowInsets.imeAnimationTarget)`: one WebView resize per keyboard open/close. Baseline after the lag fix: `dumpsys gfxinfo` janky ≈ 1.1 %, p90 ≈ 17 ms over 3–4 keyboard cycles.
- Glimmer defaults (fresh install / unreadable value): text `rgba(195,195,195,1)`, muted `rgba(150,152,158,1)`, accent `rgba(170,219,255,1)`, background `rgba(24,24,27,1)`, border `rgba(255,255,255,0.08)`, user tint `rgba(255,255,255,0.07)`, bot tint `rgba(255,255,255,0.03)`, shadow `rgba(0,0,0,1)`, font scale `1.1`.
- ST logo red `#A11B1B`, white letters `#FFFFFF`. Splash background `#18181B`. Notification copy: title "SillyTavern stays loaded", text "Tap to open your chat". Launcher name stays "ST Mobile".
- Full JVM suite (`./gradlew :app:testDebugUnitTest`) stays green after every task (166 tests at plan time).
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Work on branch `build/app`.

## Review Focus

1. **ST theme values in unexpected shapes** (`transparent`, empty string, `#rrggbb`, `rgb()` without alpha, a value with near-zero alpha for text). Expected: per-field Glimmer fallback, never invisible text, never a crash. Pinned in Task 1 (`unreadableValuesFallBack`, `nearlyTransparentTextFallsBack`).
2. **ST repopulates a list while the sheet is open** (e.g. the model list refreshes after connecting). Expected: the sheet closes without changing the value. Pinned in Task 7 (`optionsMutateWhileOpenClosesWithoutChange`).
3. **Selects that are not dropdowns or not usable** (`size>1` list boxes, disabled selects). Expected: left alone, native behavior. Pinned in Task 7 (`listBoxAndDisabledAreNotIntercepted`).
4. **Floating button after the screen size changes** (rotation, split screen). Expected: stays on its edge at the same relative height, never off-screen. Pinned in Task 3 (`positionIsRelativeAndClamped`).
5. **Avatars while ST is down or logged out** (thumbnail request returns the login HTML or an error). Expected: initial-letter fallback, and a later visit retries. Pinned in Task 5 (`failureIsNotCached`, `nonImageResponseIsFailure`).

---

### Task 1: Theme model, persistence and Compose theme

**Files:**
- Create: `app/src/main/java/dev/halftoast/stmobile/ui/CssColor.kt`, `ui/StTheme.kt`, `ui/ThemeStore.kt`, `ui/StMobileTheme.kt`
- Create: `app/src/main/res/font/noto_sans.ttf`, `res/font/noto_sans_mono.ttf`, `app/src/main/assets/licenses/OFL-NotoSans.txt`
- Modify: `settings/AppSettings.kt` (add `stThemeJson`), `StMobileApp.kt` (own a `ThemeStore`)
- Test: `app/src/test/java/dev/halftoast/stmobile/ui/StThemeTest.kt`

**Interfaces:**
- Produces:
  - `fun parseCssColor(css: String): Int?`: ARGB int. Accepts `rgb(r, g, b)`, `rgba(r, g, b, a)` (a 0–1), `#rgb`, `#rrggbb`, `#rrggbbaa`; returns null for anything else, including `transparent` and blank.
  - `fun composite(top: Int, bottom: Int): Int`: source-over, result opaque if `bottom` is opaque.
  - `data class StTheme(val text: Int, val muted: Int, val accent: Int, val background: Int, val border: Int, val userTint: Int, val botTint: Int, val shadow: Int, val fontScale: Float)` with `companion object { val Glimmer: StTheme; val CSS_VARS: List<String>; fun fromCssVars(vars: Map<String, String>): StTheme; fun fromJson(json: String?): StTheme }` and `fun toJson(): String`.
  - `CSS_VARS` (in this order): `--SmartThemeBodyColor` (text), `--SmartThemeEmColor` (muted), `--SmartThemeQuoteColor` (accent), `--SmartThemeBlurTintColor` (background), `--SmartThemeBorderColor` (border), `--SmartThemeUserMesBlurTintColor` (userTint), `--SmartThemeBotMesBlurTintColor` (botTint), `--SmartThemeShadowColor` (shadow), `--fontScale` (fontScale).
  - `data class StPalette(val background: Int, val box: Int, val border: Int, val text: Int, val muted: Int, val accent: Int, val danger: Int, val dangerBorder: Int, val online: Int)` and `fun StTheme.palette(): StPalette`. All opaque.
  - `class ThemeStore(load: () -> String?, save: (String) -> Unit) { val theme: StateFlow<StTheme>; fun update(vars: Map<String, String>) }`. `update` saves only when the parsed theme differs.
  - `AppSettings.stThemeJson: String?` (pref key `st_theme_json`). `StMobileApp.themeStore: ThemeStore`.
  - `@Composable fun StMobileTheme(theme: StTheme, content: @Composable () -> Unit)` and `val LocalStPalette: ProvidableCompositionLocal<StPalette>`; `val StMono: FontFamily`.

- [ ] **Step 1: Write the failing tests** in `StThemeTest.kt`:

```kotlin
@Test fun parsesStFormats() {
    assertEquals(0xFFC3C3C3.toInt(), parseCssColor("rgba(195, 195, 195, 1)"))
    assertEquals(0x14FFFFFF, parseCssColor("rgba(255,255,255,0.08)"))   // 0.08*255 = 20.4 → 20
    assertEquals(0xFF18181B.toInt(), parseCssColor("rgb(24, 24, 27)"))
    assertEquals(0xFFAADBFF.toInt(), parseCssColor("#aadbff"))
    assertEquals(0x80AADBFF.toInt(), parseCssColor("#aadbff80"))
    assertNull(parseCssColor("transparent")); assertNull(parseCssColor("")); assertNull(parseCssColor("var(--x)"))
}
@Test fun glimmerMatchesSpec() {
    val g = StTheme.Glimmer
    assertEquals(0xFFC3C3C3.toInt(), g.text); assertEquals(0xFF96989E.toInt(), g.muted)
    assertEquals(0xFFAADBFF.toInt(), g.accent); assertEquals(0xFF18181B.toInt(), g.background)
    assertEquals(0x14FFFFFF, g.border); assertEquals(1.1f, g.fontScale)
}
@Test fun readsStVars() {
    val t = StTheme.fromCssVars(mapOf("--SmartThemeQuoteColor" to "rgba(255, 0, 0, 1)", "--fontScale" to "1.25"))
    assertEquals(0xFFFF0000.toInt(), t.accent); assertEquals(1.25f, t.fontScale)
    assertEquals(StTheme.Glimmer.text, t.text) // missing → Glimmer
}
@Test fun unreadableValuesFallBack() {
    val t = StTheme.fromCssVars(mapOf("--SmartThemeBodyColor" to "transparent", "--SmartThemeBlurTintColor" to "", "--fontScale" to "abc"))
    assertEquals(StTheme.Glimmer.text, t.text); assertEquals(StTheme.Glimmer.background, t.background); assertEquals(1.1f, t.fontScale)
}
@Test fun nearlyTransparentTextFallsBack() {
    assertEquals(StTheme.Glimmer.text, StTheme.fromCssVars(mapOf("--SmartThemeBodyColor" to "rgba(200,200,200,0.1)")).text)
}
@Test fun fontScaleIsClamped() {
    assertEquals(1.5f, StTheme.fromCssVars(mapOf("--fontScale" to "3")).fontScale)
    assertEquals(0.8f, StTheme.fromCssVars(mapOf("--fontScale" to "0.2")).fontScale)
}
@Test fun translucentBackgroundBecomesOpaqueOverBlack() {
    assertEquals(0xFF0C0C0E.toInt(), StTheme.fromCssVars(mapOf("--SmartThemeBlurTintColor" to "rgba(24, 24, 27, 0.5)")).palette().background)
}
@Test fun jsonRoundTripAndBadJson() {
    val t = StTheme.fromCssVars(mapOf("--SmartThemeQuoteColor" to "#ff0000"))
    assertEquals(t, StTheme.fromJson(t.toJson()))
    assertEquals(StTheme.Glimmer, StTheme.fromJson(null)); assertEquals(StTheme.Glimmer, StTheme.fromJson("{oops"))
}
@Test fun paletteIsOpaqueAndMatchesMockup() {
    val p = StTheme.Glimmer.palette()
    assertEquals(0xFF2A2A2D.toInt(), p.border)      // 8% white (alpha 20) over #18181B
    assertEquals(0xFF111113.toInt(), p.box)         // ST --black30a: rgba(0,0,0,0.3) over #18181B
    listOf(p.background, p.box, p.border, p.danger, p.dangerBorder).forEach { assertEquals(0xFF, it ushr 24) }
}
@Test fun storeSavesOnlyOnChange() {
    var saved = 0; var stored: String? = null
    val s = ThemeStore(load = { stored }, save = { stored = it; saved++ })
    assertEquals(StTheme.Glimmer, s.theme.value)
    s.update(mapOf("--SmartThemeQuoteColor" to "#ff0000")); s.update(mapOf("--SmartThemeQuoteColor" to "#ff0000"))
    assertEquals(1, saved); assertEquals(0xFFFF0000.toInt(), ThemeStore({ stored }, {}).theme.value.accent)
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*StThemeTest*'`. Expected: compile failure (unresolved `parseCssColor`, `StTheme`, `ThemeStore`).

- [ ] **Step 3: Implement** `CssColor.kt`, `StTheme.kt`, `ThemeStore.kt` to the interfaces.
  - Text fallback: if alpha < 0x4D (≈0.3), use Glimmer's.
  - Font scale: clamp to 0.8–1.5.
  - `palette()`: background = background over opaque black. box = `0x4D000000` over background. border = border over background. danger = `rgba(140,40,40,0.45)` over background. dangerBorder = `rgba(255,120,120,0.25)` over background. online = `0xFF82D296`. text, muted and accent are each composited over background.
  - JSON via `org.json.JSONObject` with keys `text, muted, accent, background, border, userTint, botTint, shadow, fontScale`.
  - Rounding: alpha = `round(a*255)`. Fix the expected values in the tests if your rounding is the standard one and they differ by 1, and say so in the commit.

- [ ] **Step 4: Run** the same test command. Expected: PASS.

- [ ] **Step 5: Wire persistence.**
  - Add `AppSettings.stThemeJson` (nullable string pref `st_theme_json`).
  - In `StMobileApp`, add `val themeStore by lazy { ThemeStore({ settings.stThemeJson }, { settings.stThemeJson = it }) }`.

- [ ] **Step 6: Bundle fonts.**
  - Download `https://github.com/google/fonts/raw/main/ofl/notosans/NotoSans%5Bwdth,wght%5D.ttf` → `res/font/noto_sans.ttf`.
  - Download `https://github.com/google/fonts/raw/main/ofl/notosansmono/NotoSansMono%5Bwdth,wght%5D.ttf` → `res/font/noto_sans_mono.ttf`.
  - Download `https://github.com/google/fonts/raw/main/ofl/notosans/OFL.txt` → `assets/licenses/OFL-NotoSans.txt`.
  - Check each with `file` (expect "TrueType Font data").

- [ ] **Step 7: Implement `StMobileTheme`.**
  - Color scheme: `darkColorScheme(primary = accent, onPrimary = background, background = background, onBackground = text, surface = background, onSurface = text, onSurfaceVariant = muted, outline = border, outlineVariant = border, surfaceContainer = box, surfaceContainerHigh = box, surfaceContainerHighest = box, secondaryContainer = box, onSecondaryContainer = text, error = 0xFFFF8C8C, errorContainer = danger, onErrorContainer = text)`.
  - Typography: Material3 default `Typography()` with every style's `fontFamily` set to Noto Sans and `fontSize`/`lineHeight` multiplied by `fontScale`.
  - Font weights 400, 500 and 600 via `Font(R.font.noto_sans, weight, variationSettings = FontVariation.Settings(FontVariation.weight(n)))`.
  - `StMono` is the same construction for weight 400 from `noto_sans_mono`.
  - Provide `LocalStPalette`.

- [ ] **Step 8: Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: all tests pass, build succeeds.

- [ ] **Step 9: Commit.** Message: `feat: ST theme model, persistence and Compose theme`.

---

### Task 2: Read and watch ST's theme from the page

**Files:**
- Create: `app/src/main/java/dev/halftoast/stmobile/chat/ThemeBridge.kt`
- Modify: `chat/WebViewHolder.kt` (install the bridge in `createWebView()` next to `downloads.install(this, serverOrigin)`; run the read on Ready), `StMobileApp.kt`/`MainActivity.kt` (pass `app.themeStore::update` into the holder)
- Test: `app/src/test/java/dev/halftoast/stmobile/chat/ThemeBridgeTest.kt`

**Interfaces:**
- Consumes: `StTheme.CSS_VARS`, `ThemeStore.update(vars)`.
- Produces:
  - `object ThemeBridge { const val NAME = "STMobileTheme"; fun parseMessage(json: String): Map<String, String>?; fun readAndWatchScript(): String; fun install(webView: WebView, origin: String?, onTheme: (Map<String, String>) -> Unit) }`.
  - `parseMessage` accepts only a JSON object of string values whose keys are in `CSS_VARS`. Unknown keys are dropped. Returns null for non-objects, invalid JSON, or input over 4096 chars.
  - `WebViewHolder` gets a constructor parameter `onTheme: (Map<String, String>) -> Unit = {}`.

- [ ] **Step 1: Write the failing tests**:

```kotlin
@Test fun keepsOnlyKnownVars() {
    assertEquals(mapOf("--SmartThemeQuoteColor" to "rgba(1, 2, 3, 1)"),
        ThemeBridge.parseMessage("""{"--SmartThemeQuoteColor":"rgba(1, 2, 3, 1)","evil":"x"}"""))
}
@Test fun rejectsJunk() {
    assertNull(ThemeBridge.parseMessage("[1]")); assertNull(ThemeBridge.parseMessage("nope"))
    assertNull(ThemeBridge.parseMessage("{\"--fontScale\":\"" + "1".repeat(5000) + "\"}"))
}
@Test fun scriptNamesEveryVarAndSkipsLogin() {
    val s = ThemeBridge.readAndWatchScript()
    StTheme.CSS_VARS.forEach { assertTrue(it, s.contains(it)) }
    assertTrue(s.contains("/login")); assertTrue(s.contains("MutationObserver"))
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ThemeBridgeTest*'`. Expected: compile failure.

- [ ] **Step 3: Implement `ThemeBridge`.**
  - `readAndWatchScript()` returns a JS IIFE that:
    1. Returns `null` when `location.pathname` starts with `/login`.
    2. Otherwise reads every var with `getComputedStyle(document.documentElement).getPropertyValue(k).trim()`.
    3. Installs, once per page (guard `window.__stmThemeWatch`), a `MutationObserver` on `document.documentElement`, watching the `style` and `class` attributes. The observer posts the same JSON via `STMobileTheme.postMessage` at most once per 300 ms.
    4. Returns the JSON string.
  - `install` registers `WebViewCompat.addWebMessageListener(webView, NAME, setOf(origin))`, main frame only, which calls `parseMessage` and then `onTheme`. Feature-check `WEB_MESSAGE_LISTENER` the way `Downloads.install` does.

- [ ] **Step 4: Run** the test. Expected: PASS.

- [ ] **Step 5: Run the read on Ready.**
  - In `WebViewHolder.startReadyPolling`, where `_state.value = ChatState.Ready` is set, call `view.evaluateJavascript(ThemeBridge.readAndWatchScript())`.
  - The callback's raw result is a JSON-encoded string: decode it with `JSONArray("[$raw]").optString(0)`, then pass it through `parseMessage` and on to `onTheme`.
  - Log `ST_THEME accent=<value>` once per Ready, at Log.i with `StMobileApp.LOG_TAG`. Never log anything else from the page.

- [ ] **Step 6: Wire it.** Pass `onTheme = app.themeStore::update` when `MainActivity` constructs `WebViewHolder`.

- [ ] **Step 7: Verify.**
  - Run `./gradlew :app:testDebugUnitTest`. Expected: PASS.
  - Install a release build (Global Constraints) and relaunch. Then run `adb -s <serial> logcat -d | grep ST_THEME`. Expected: `ST_THEME accent=rgba(170, 219, 255, 1)` after ST_READY.

- [ ] **Step 8: Commit.** Message: `feat: read and watch ST's theme colors`.

---

### Task 3: Floating-button navigation, top bar, insets

**Files:**
- Create: `app/src/main/java/dev/halftoast/stmobile/nav/FabPosition.kt`, `nav/FloatingNav.kt`, `ui/StComponents.kt` (only `StTopBar` in this task)
- Modify: `MainActivity.kt` (replace `Tab` + `NavigationBar` with `Screen`; theme the whole content), `settings/AppSettings.kt` (fab prefs), `res/values/themes.xml`
- Test: `app/src/test/java/dev/halftoast/stmobile/nav/FabPositionTest.kt`

**Interfaces:**
- Consumes: `StMobileTheme`, `LocalStPalette`, `StMobileApp.themeStore.theme`.
- Produces:
  - `enum class Screen(val title: String) { Chat("Chat"), Panel("Control Center"), Stats("Stats"), Logs("Logs") }`. It replaces `Tab`.
  - `enum class FabEdge { Left, Right }`.
  - `data class FabPosition(val edge: FabEdge, val fraction: Float) { companion object { val Default = FabPosition(FabEdge.Right, 0.5f) } }`.
  - `fun snapFab(centerX: Float, centerY: Float, width: Float, height: Float): FabPosition`.
  - `AppSettings.fabPosition: FabPosition`, stored under pref keys `fab_edge` and `fab_fraction`.
  - `@Composable fun FloatingNav(position: FabPosition, onMoved: (FabPosition) -> Unit, onOpen: (Screen) -> Unit, debugLongPress: (() -> Unit)?, modifier: Modifier)`.
  - `@Composable fun StTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit)`.

- [ ] **Step 1: Write the failing tests**:

```kotlin
@Test fun snapsToNearestEdge() {
    assertEquals(FabPosition(FabEdge.Left, 0.5f), snapFab(100f, 1000f, 1000f, 2000f))
    assertEquals(FabPosition(FabEdge.Right, 0.25f), snapFab(900f, 500f, 1000f, 2000f))
}
@Test fun positionIsRelativeAndClamped() {
    assertEquals(0.1f, snapFab(900f, -50f, 1000f, 2000f).fraction)
    assertEquals(0.9f, snapFab(900f, 5000f, 1000f, 2000f).fraction)
}
@Test fun defaultIsRightMiddle() = assertEquals(FabPosition(FabEdge.Right, 0.5f), FabPosition.Default)
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*FabPositionTest*'`. Expected: compile failure.

- [ ] **Step 3: Implement** `FabPosition.kt` (edge = Left when `centerX < width / 2`, fraction = `(centerY / height).coerceIn(0.1f, 0.9f)`) and `AppSettings.fabPosition`.

- [ ] **Step 4: Run** the test. Expected: PASS.

- [ ] **Step 5: Implement `FloatingNav`** (mockup `navbar.html`, option B).
  - **Look:** a 40 dp round button with an ellipsis glyph in accent color, fill `box` at 85 % alpha, a 1 dp border, idle alpha 0.75.
  - **Fan:** tapping opens three 40 dp buttons with 8 dp spacing, stacked toward the screen interior: Control Center (gauge), Stats (bars), Logs (terminal). Use the SVG shapes from the mockup as `ImageVector`s, not Material icons.
  - **Closing:** a full-screen transparent scrim behind the fan closes it on tap.
  - **Dragging:** drag moves the button; on release, call `snapFab` and then `onMoved`. Its vertical center sits at `fraction * height`, offset 8 dp from the edge.
  - **Keyboard:** hidden while `WindowInsets.isImeVisible`.
  - **Debug:** when `debugLongPress != null` (debug builds only), long-pressing the button calls it. This replaces the Chat-tab debug gesture, which loads `chrome://kill`.

- [ ] **Step 6: Rework `MainActivity`.**
  - In `onCreate`, before `setContent`, call `enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))`.
  - Wrap content in `StMobileTheme(theme)`, where `theme` comes from `app.themeStore.theme.collectAsState()`.
  - Replace `Tab`/`selected` with `var screen by rememberSaveable { mutableStateOf(Screen.Chat) }` and remove the `Scaffold` + `NavigationBar`.
  - Keep the `chatTabShown` semantics: true when `screen == Screen.Chat && overlay == Overlay.None`.
  - **Chat layout:** `Box(Modifier.fillMaxSize().background(Color(palette.background)))`. Inside it, `ChatScreen` gets `Modifier.windowInsetsPadding(WindowInsets.systemBars).windowInsetsPadding(WindowInsets.imeAnimationTarget)`. Draw `FloatingNav` over the Box. The dark Box background is what removes the light strip.
  - **Other screens:** a `Column` with `systemBars` padding.
    - It starts with `StTopBar(screen.title, onBack = { screen = Screen.Chat })`. Its actions are icons for the other two of Panel/Stats/Logs, plus the Settings gear on Panel only.
    - Then the existing screen content. Setup still replaces Panel/Stats/Logs while `!setupDone`.
  - **Back:** BackHandler order stays `Setup → Settings → None`, then `screen != Chat → Chat`, then `handleChatBack()`.

- [ ] **Step 7: Make the window dark.** Set `res/values/themes.xml` `Theme.StMobile` items `android:windowBackground` = `#18181B` and `android:statusBarColor`/`android:navigationBarColor` = `@android:color/transparent`.

- [ ] **Step 8: Verify.**
  - Run `./gradlew :app:testDebugUnitTest :app:assembleRelease`. Expected: PASS and a build. Install.
  - **Screenshots:** take Chat (no bar, button visible) and Control Center (top bar) via `adb exec-out screencap -p`. Tap only native UI (the floating button, top-bar icons); never the ST page.
  - **Position:** drag the button with `adb shell input swipe` from its spot to the left edge, then force-stop and relaunch. Expected: it's still on the left.
  - **Keyboard:** ask HalfToast to do 3–4 keyboard cycles after `dumpsys gfxinfo dev.halftoast.stmobile reset`. Expected: janky ≈ 1–2 %, p90 ≤ 20 ms, no light strip, button hidden while typing.

- [ ] **Step 9: Commit.** Message: `feat: floating navigation, slim top bar, dark edge-to-edge`.

---

### Task 4: Control Center and its dialogs in ST style

**Files:**
- Modify: `ui/StComponents.kt` (add components), `panel/PanelScreen.kt`, `panel/WhitelistDialog.kt`
- Test: existing `PanelViewModelTest` (must stay green); device screenshots

**Interfaces:**
- Consumes: `LocalStPalette`, `StMono`, `StTopBar` (Task 3).
- Produces (in `StComponents.kt`):
  - `@Composable fun StSectionTitle(text: String)`
  - `@Composable fun StBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun StKeyValue(key: String, value: String, last: Boolean = false)`
  - `enum class StTone { Normal, Danger }`
  - `@Composable fun StButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tone: StTone = StTone.Normal, busy: Boolean = false)`
  - `@Composable fun StDialog(title: String, onDismiss: () -> Unit, confirm: @Composable () -> Unit, dismiss: (@Composable () -> Unit)? = null, content: @Composable () -> Unit)`

- [ ] **Step 1: Implement the components** (mockup `panel-style.html`, option A). They are ST's `menu_button` look:
  - **StBox:** `box` fill, 1 dp `border`, 5 dp corners, 10 dp horizontal padding.
  - **StKeyValue:** a row with the key in `muted` and the value in `text`, 6 dp vertical padding, and a 1 dp `border` divider unless `last`.
  - **StButton:** `box` fill, 1 dp border, 5 dp corners, 7 dp vertical padding. Disabled at alpha 0.4. Danger uses `danger` fill and `dangerBorder`. Busy shows an 18 dp spinner in place of the text.
  - **StSectionTitle:** 13.5 sp × fontScale, weight 600, color `text`.
  - **StDialog:** a `Dialog` holding an StBox-styled card with background `background`, 12 dp corners, the title at weight 600, and StButtons.

- [ ] **Step 2: Restyle `PanelScreen`.**
  - Header row: "SillyTavern" with an online/offline chip (green dot + `online` color, or muted "Offline"), and the PID/branch line in muted below it.
  - One `StBox` with the rows Uptime, Health, CPU / RAM, Tablet battery, Disk free, Cache, Inventory and Last client.
  - Sections "Server" (Start/Stop/Restart, with Stop as `StTone.Danger`), "Maintenance" (Update/Backup/Clean cache) and "Whitelist" (Manage whitelist).
  - "Recent log" as an `StBox` in `StMono` with an "Open Logs" text button in accent.
  - Keep every existing state, callback and the "Last action" line (Failed in `error` color, others muted). Error card → `StBox` with the message and StButtons.
  - Confirm dialogs and the whitelist dialog → `StDialog`.
  - Remove the screen's own title and gear: the top bar has them now (Task 3).

- [ ] **Step 3: Verify.**
  - Run `./gradlew :app:testDebugUnitTest :app:assembleRelease`. Expected: PASS. Install.
  - Screenshot the Control Center top and bottom (scroll with `adb shell input swipe`), plus the Manage whitelist dialog (open via adb tap on the native button; close it with Back, change nothing). Compare with `panel-style.html` A.

- [ ] **Step 4: Commit.** Message: `feat: Control Center in ST style`.

---

### Task 5: Stats with avatars

**Files:**
- Modify: `assets/probes/stats.py`, `panel/Parsers.kt` (`parseStats`), `panel/PanelModels.kt` (`CharacterStat`), `stats/StatsScreen.kt`
- Create: `app/src/main/java/dev/halftoast/stmobile/stats/Avatars.kt`
- Test: `panel/ParsersTest.kt`, new `app/src/test/java/dev/halftoast/stmobile/stats/AvatarsTest.kt`

**Interfaces:**
- Produces:
  - `data class CharacterStat(val name: String, val messages: Int, val lastActiveEpochSec: Long, val avatarFile: String? = null)`.
  - `parseStats` accepts 3 or 4 tab-separated fields. An empty 4th field gives `avatarFile = null`.
  - `fun avatarUrl(serverUrl: String, file: String): String`.
  - `class AvatarCache<T : Any>(maxEntries: Int, fetch: suspend (String) -> T?) { suspend fun get(file: String): T? }`. Failures (null) are not cached; at most one fetch per file runs at a time.
  - `fun isImageResponse(code: Int, contentType: String?): Boolean`.

- [ ] **Step 1: Write the failing tests:**

```kotlin
// ParsersTest
@Test fun statsWithAvatarColumn() {
    val s = parseStats("Seraphina\t77\t1793250000\tSeraphina.png\nDante\t1\t1793000000\t\n")
    assertEquals(CharacterStat("Seraphina", 77, 1793250000, "Seraphina.png"), s[0]); assertNull(s[1].avatarFile)
    assertEquals(1, parseStats("Old\t3\t5\n").size) // 3-field lines still parse
}
// AvatarsTest
@Test fun urlEncodesFile() = assertEquals(
    "http://100.101.102.103:8000/thumbnail?type=avatar&file=Mia%20Vale.png", avatarUrl("http://100.101.102.103:8000/", "Mia Vale.png"))
@Test fun failureIsNotCached() = runTest {
    var calls = 0; val c = AvatarCache<String>(10) { calls++; if (calls == 1) null else "img" }
    assertNull(c.get("a.png")); assertEquals("img", c.get("a.png")); assertEquals("img", c.get("a.png")); assertEquals(2, calls)
}
@Test fun evictsOldest() = runTest {
    val seen = mutableListOf<String>(); val c = AvatarCache<String>(2) { seen += it; it }
    c.get("a"); c.get("b"); c.get("c"); c.get("a"); assertEquals(listOf("a", "b", "c", "a"), seen)
}
@Test fun nonImageResponseIsFailure() {
    assertTrue(isImageResponse(200, "image/jpeg")); assertFalse(isImageResponse(200, "text/html; charset=utf-8"))
    assertFalse(isImageResponse(302, "image/png")); assertFalse(isImageResponse(200, null))
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ParsersTest*' --tests '*AvatarsTest*'`. Expected: compile failure.

- [ ] **Step 3: Implement.**
  - `stats.py`: keep the real file name per character, preferring the `.png` when both `.png` and `.json` exist. Print it as a 4th field, or an empty string when there's no `.png`.
  - `avatarUrl`: `URLEncoder.encode(file, "UTF-8").replace("+", "%20")`.
  - `AvatarCache`: an access-ordered `LinkedHashMap` behind a `Mutex`, plus a per-key in-flight `Deferred`.

- [ ] **Step 4: Run** the tests. Expected: PASS.

- [ ] **Step 5: Implement the real fetch.** In `Avatars.kt`, add `fun httpAvatarFetch(serverUrl: () -> String): suspend (String) -> ImageBitmap?`.
  - On `Dispatchers.IO`: `HttpURLConnection` GET of `avatarUrl`, with the `Cookie` header from `CookieManager.getInstance().getCookie(serverUrl())`.
  - 5 s connect/read timeouts, `instanceFollowRedirects = false`.
  - Accept only `isImageResponse`, then decode with `BitmapFactory` and convert with `asImageBitmap()`.
  - Any exception → null.
  - Create one app-wide `AvatarCache(200, httpAvatarFetch { settings.serverUrl })` in `StMobileApp`.

- [ ] **Step 6: Restyle `StatsScreen`.**
  - Header: "Characters" in weight 600 with "110 characters · most recent first" in muted below. The count comes from the data.
  - Rows: a 40 dp round avatar, loaded via `produceState` from the cache. Fallback is the name's first letter on `box` with a `border` ring.
  - Each row shows the name in text color, "yyyy-MM-dd HH:mm" in muted, and "77 msgs" in muted on the right, with a 1 dp `border` divider.
  - Keep loading, error and host-key states, restyled with `StBox`/`StButton`.

- [ ] **Step 7: Verify.**
  - Run `./gradlew :app:testDebugUnitTest :app:assembleRelease`. Expected: PASS. Install.
  - Open Stats via the floating button (adb taps on native UI) and screenshot. Expected: real avatars for characters with a `.png`.
  - Stop nothing on the server. Avatars use the existing ST login.

- [ ] **Step 8: Commit.** Message: `feat: Stats in ST style with character avatars`.

---

### Task 6: Logs, Settings, Setup and Chat overlays in ST style

**Files:**
- Modify: `logs/LogsScreen.kt`, `settings/SettingsScreen.kt`, `settings/SetupScreen.kt`, `chat/ChatScreen.kt`
- Create: `app/src/main/java/dev/halftoast/stmobile/logs/LogTone.kt`
- Test: `app/src/test/java/dev/halftoast/stmobile/logs/LogToneTest.kt`

**Interfaces:**
- Consumes: Task 4 components, `StMono`.
- Produces:
  - `enum class LogTone { Normal, Warn, Error }`.
  - `fun logTone(line: String): LogTone`.
  - `@Composable fun StSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit)` (added to `StComponents.kt`).

- [ ] **Step 1: Write the failing test:**

```kotlin
@Test fun tonesByKeyword() {
    assertEquals(LogTone.Error, logTone("Error: connect ECONNREFUSED 127.0.0.1:5001"))
    assertEquals(LogTone.Error, logTone("  at Object.<anonymous> [ERROR] boom"))
    assertEquals(LogTone.Warn, logTone("Warning: config.yaml has unknown key"))
    assertEquals(LogTone.Warn, logTone("[WARN] slow request"))
    assertEquals(LogTone.Normal, logTone("'z-ai/glm-latest'"))
    assertEquals(LogTone.Normal, logTone("Terrorists are fictional")) // whole-word match only
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*LogToneTest*'`. Expected: compile failure.

- [ ] **Step 3: Implement `logTone`.** Use a case-insensitive whole-word regex: `\b(error|err|fatal)\b` → Error, else `\b(warn|warning)\b` → Warn, else Normal.

- [ ] **Step 4: Run** the test. Expected: PASS.

- [ ] **Step 5: Restyle the screens.**
  - **Logs:** `StSegmented(listOf("Server", "Access"))` in ST button style; the selected option gets `box` fill plus accent text. Lines in `StMono` 12 sp × fontScale. Warn lines in `0xFFE8C07A`, Error lines in `0xFFFF8C8C`, others in `text`.
  - **Settings and Setup:** ST-style text fields (`box` fill, 1 dp `border`, 5 dp corners, accent focus border), `StSectionTitle`s, `StButton`s and switches tinted accent. Keep `imePadding()` on these forms.
  - **ChatScreen overlays:**
    - Reconnecting pill: `box` at 92 % alpha, `border`, text color.
    - Progress bar: accent on `border`.
    - Still-loading bar: `box` with an accent "Retry".
    - Unreachable screen: `background` with an `StButton` "Retry" and an `StButton` "Open Control Center".

- [ ] **Step 6: Verify.**
  - Run `./gradlew :app:testDebugUnitTest :app:assembleRelease`. Expected: PASS. Install.
  - Screenshot Logs (both segments), Settings (via the gear) and Setup (via Settings → Re-run setup, then Back out without saving).
  - Don't trigger the Unreachable screen on the live server. Check it by reading the code plus the existing `ChatStateTest`.

- [ ] **Step 7: Commit.** Message: `feat: Logs, Settings, Setup and chat overlays in ST style`.

---

### Task 7: In-page selection sheet

**Files:**
- Create: `app/src/main/assets/web/select-sheet.js`, `app/src/main/java/dev/halftoast/stmobile/chat/SelectSheet.kt`, `tools/web-test.sh`, `app/src/test/web/select-sheet.test.html`
- Modify: `chat/WebViewHolder.kt` (install in `createWebView()`; extend `OPEN_UI_SELECTOR`)
- Test: `select-sheet.test.html` run by `tools/web-test.sh`; `app/src/test/java/dev/halftoast/stmobile/chat/SelectSheetTest.kt`

**Interfaces:**
- Produces:
  - `select-sheet.js` is a self-contained IIFE. It defines `window.__stmSelectSheet = { open(select): boolean, close(): void, isOpen(): boolean }` and injects its own `<style id="stm-select-sheet-css">` on `DOMContentLoaded`.
  - The sheet's root element is `#stm-select-sheet`, carrying class `stm-open` while open.
  - Rows are `.stm-row` with `data-index` (the option index). Classes: `stm-selected`, `stm-disabled`. Group markers are `.stm-divider` and `.stm-group`. The search field is `.stm-search`, the Done button `.stm-done`, the dimmer `.stm-dim`, and the empty-result text `.stm-empty` ("No matches").
  - `object SelectSheet { fun install(webView: WebView, origin: String?, script: String); fun load(context: Context): String }`. `load` reads `assets/web/select-sheet.js`.
  - `WebViewHolder.OPEN_UI_SELECTOR` gains `, #stm-select-sheet.stm-open`.
  - `tools/web-test.sh <file.html>`: exit 0 iff the dumped DOM contains `data-result="PASS"`; prints the result otherwise. Chrome path is env `CHROME`, default `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`. Flags: `--headless=new --disable-gpu --allow-file-access-from-files --virtual-time-budget=5000 --dump-dom`.

**Behavior** (mockup `picker-style.html`, option A; spec §1). The page's own CSS vars style it, with Glimmer fallbacks: `var(--SmartThemeBlurTintColor, rgb(24,24,27))` and so on.
- **Interception:** a capture-phase `mousedown` listener on `document`. It applies when the target is a `<select>` that isn't disabled, has `size <= 1` (or `multiple`), and isn't hidden by select2 (`.select2-hidden-accessible`). It calls `open(select)`, then `preventDefault()` only if `open` returned true. Any exception inside `open` returns false, so the native list still works.
- **Title:** `label[for=id]` text → `aria-label` → `title` → the text of the nearest preceding sibling `h3, h4, h5, label, small` in the same parent → none.
- **Rows:** a group without a label gives `.stm-divider` before its options (not before the first group). A labelled group gives `.stm-group` with its label. The selected option's row has `stm-selected`; disabled options get `stm-disabled` and ignore taps.
- **Search:** shown only when the option count is > 8. It filters by case-insensitive substring of the option text and shows `.stm-empty` "No matches" when nothing matches.
- **Single select:** tapping a row sets `select.selectedIndex`, dispatches `new Event('input', {bubbles: true})` then `new Event('change', {bubbles: true})`, and closes.
- **Multiple:** taps toggle `stm-selected`. `.stm-done` applies every `option.selected` and dispatches input + change once, then closes.
- **Closing without change:** a `.stm-dim` tap, an `Escape` keydown on `document`, a swipe down of more than 80 px on the header, or a `MutationObserver` (childList/subtree/attributes) on the open select firing.
- **Height:** content-sized, max 75vh. The current row is scrolled into view on open.
- **select2 reskin** (CSS only): `.select2-dropdown`, `.select2-results__option`, `.select2-results__option--highlighted`, `.select2-search__field` use the sheet's colors, rows and the accent tint for the highlight.

- [ ] **Step 1: Write the failing JS tests** in `select-sheet.test.html`.
  - It loads `../../main/assets/web/select-sheet.js` and builds the fixtures:
    - `#chat_completion_source`, with two unnamed optgroups: OpenAI, Custom (OpenAI-compatible) | AI21, AI/ML API, Azure OpenAI, Chutes, Claude, Cloudflare Workers AI, Cohere, DeepSeek.
    - A short select labelled "Yes/No".
    - A `multiple` select with options a/b/c.
    - A `size=4` select and a disabled select.
  - It records every `input`/`change` event and runs each test function.
  - It sets `document.body.dataset.result = 'PASS'` or `'FAIL: <test name>: <message>'`.
  - Tests (helper `down(el)` dispatches `new MouseEvent('mousedown', {bubbles: true, cancelable: true})` and returns `defaultPrevented`):
    - `opensSheetAndPreventsNative`: `down(source) === true`, `#stm-select-sheet.stm-open` exists, the title is the label text, there are 10 `.stm-row`s and exactly one `.stm-divider`.
    - `marksCurrent`: with `source.value = 'claude'`, after opening, the `.stm-row.stm-selected` text is "Claude".
    - `pickAppliesAndFiresEvents`: tapping the AI21 row → `source.value === 'ai21'`, events `['input', 'change']`, sheet closed.
    - `searchOnlyOnLongLists`: `.stm-search` present for source (10 options), absent for Yes/No. Typing "cl" leaves 2 rows (Claude, Cloudflare Workers AI). "zzz" shows `.stm-empty`.
    - `escapeAndDimCloseWithoutChange`: open → Escape → closed, value unchanged, no events. Same with a `.stm-dim` click.
    - `multipleUsesDone`: toggle a and c, click `.stm-done` → selected values `['a', 'c']`, events `['input', 'change']`.
    - `listBoxAndDisabledAreNotIntercepted`: `down(sizeFour) === false`, `down(disabled) === false`, no sheet.
    - `optionsMutateWhileOpenClosesWithoutChange`: open source → append an option → after a microtask + 50 ms the sheet is closed, the value is unchanged, no events.
    - `brokenOpenFallsBack`: stub `Element.prototype.appendChild` to throw once (rows are rebuilt on every open, so open must hit it), then `down(yesNo) === false` and no sheet; restore the stub.

- [ ] **Step 2: Write `tools/web-test.sh` and run it.**
  - Run `chmod +x tools/web-test.sh && tools/web-test.sh app/src/test/web/select-sheet.test.html`.
  - Expected: FAIL (`select-sheet.js` missing → the first test fails).

- [ ] **Step 3: Implement `select-sheet.js`** to the behavior above. Plain ES2017, no dependencies. All state is local to the IIFE, and nothing outside `#stm-select-sheet` and `#stm-select-sheet-css` is touched.

- [ ] **Step 4: Run** `tools/web-test.sh app/src/test/web/select-sheet.test.html`. Expected: `PASS`.

- [ ] **Step 5: Write `SelectSheetTest` (JVM):** `WebViewHolder.OPEN_UI_SELECTOR` (make it `internal`) contains `#stm-select-sheet.stm-open`, and `SelectSheet.load` reads the asset path `web/select-sheet.js` (assert the constant). Run it and watch it fail, then implement `SelectSheet.kt` (`addDocumentStartJavaScript` with `setOf(origin)`, feature-checked like `Downloads.install`). Install it in `createWebView()`. Run it: PASS.

- [ ] **Step 6: Device check without touching ST.**
  - Run `adb -s <serial> reverse tcp:8765 tcp:8765` and `python3 -m http.server 8765 --directory app/src` (in the background), then open `http://localhost:8765/test/web/select-sheet.test.html` in Chrome on the phone with `adb shell am start -a android.intent.action.VIEW -d <url>`.
  - Tap the source select with `adb shell input tap` and screenshot. Expected: the sheet, and no Android dialog.
  - If the native dialog still appears, also cancel `touchend` on selects (call `preventDefault()` after a successful open, then synthesize nothing). Re-run steps 4 and 6.
  - Stop the server and run `adb reverse --remove tcp:8765`.

- [ ] **Step 7: Check on the real ST** (HalfToast taps, inside ST).
  - Install the release build.
  - Ask HalfToast to:
    1. Open Chat Completion Source, pick the current source again (no real change), and confirm the field and the form below look right.
    2. Open a short list.
    3. Open a select2 list, e.g. the OpenRouter model list if they use OpenRouter; otherwise any searchable list.
    4. Press Back with a sheet open.
  - Expected: themed sheets, Back closes the sheet without change, select2 restyled.

- [ ] **Step 8: Commit.** Message: `feat: ST dropdowns open a themed bottom sheet`.

---

### Task 8: App icon, notification, splash

**Files:**
- Create: `tools/st-logo.svg` (copied from the tablet's `~/SillyTavern/public/img/logo.svg` with `scp -P 8022`), `tools/render-icons.html`, `tools/render-icons.sh`
- Create: `res/drawable/ic_launcher_foreground.xml`, `res/drawable/splash_pulse.xml` (animated-vector), `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/values/colors.xml`, `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_monochrome.png`, `res/drawable-{mdpi,…,xxxhdpi}/ic_stat_st.png`, legacy `res/mipmap-*/ic_launcher.png`
- Modify: `AndroidManifest.xml` (`android:icon`, `android:roundIcon`, activity theme), `res/values/themes.xml`, `gradle/libs.versions.toml` + `app/build.gradle.kts` (core-splashscreen), `MainActivity.kt` (`installSplashScreen()`), `keepalive/KeepAliveService.kt`
- Test: `app/src/test/java/dev/halftoast/stmobile/keepalive/KeepAliveCopyTest.kt`

**Interfaces:**
- Produces: `object KeepAliveCopy { const val TITLE = "SillyTavern stays loaded"; const val TEXT = "Tap to open your chat"; const val COLOR = 0xFFA11B1B.toInt() }` in `KeepAliveService.kt`.

**Logo geometry:** `logo.svg` has viewBox `0 0 329.35 307.64`. The paths are `path9` (red mark) and `path2` (white letters) under `translate(-779.42 -7.5519)` and then `translate(429.83 -2.2682)`, i.e. a net `translate(-349.59, -9.8201)` in SVG units. For a 108 dp adaptive layer with the logo 60 dp wide in the safe zone, use scale `60 / 329.35 = 0.18218` and an outer translate of `(24, 25.98)`. In a VectorDrawable, use an outer `<group scaleX/Y=0.18218 translateX=24 translateY=25.98>` around an inner `<group translateX=-349.59 translateY=-9.8201>`.

- [ ] **Step 1: Write the failing test:** assert `KeepAliveCopy.TITLE == "SillyTavern stays loaded"`, `TEXT == "Tap to open your chat"`, `COLOR == 0xFFA11B1B.toInt()`. Run `./gradlew :app:testDebugUnitTest --tests '*KeepAliveCopyTest*'`. Expected: compile failure.

- [ ] **Step 2: Update the notification.**
  - Add `KeepAliveCopy`. `setSmallIcon(R.drawable.ic_stat_st)`, `setContentTitle(TITLE)`, `setContentText(TEXT)`, `setColor(COLOR)`.
  - Run the test. Expected: PASS (resources come in step 4; until then point at a placeholder vector `res/drawable/ic_stat_st.xml` and delete it in step 4).

- [ ] **Step 3: Write the icon renderer.**
  - `tools/render-icons.html` reads the two path `d` strings from `st-logo.svg` (fetch with `--allow-file-access-from-files`) and draws onto canvases with `Path2D` using the geometry above.
  - **Monochrome:** white mark, then the letters with `globalCompositeOperation = 'destination-out'`, filled plus stroked at line width 8 (SVG units) so the letters are cut out with a gap. Output at 108 dp sizes 108/162/216/324/432 px.
  - **Notification:** the same knockout at 24 dp sizes 24/36/48/72/96 px, with the logo filling 22 dp wide.
  - **Legacy launcher:** the full-color logo on `#333333` in a circle, at 48 dp sizes 48/72/96/144/192 px.
  - It writes each PNG data URL into `<img data-out="mipmap-xxhdpi/ic_launcher_monochrome.png" src="…">` elements and sets `data-result="PASS"` when done.
  - `tools/render-icons.sh` runs headless Chrome `--dump-dom` (same flags as `web-test.sh`), extracts each `data-out`/`src` pair and base64-decodes it into `app/src/main/res/<data-out>`.

- [ ] **Step 4: Generate and check the icons.**
  - Run `tools/render-icons.sh`, then `file app/src/main/res/mipmap-xxxhdpi/ic_launcher_monochrome.png`. Expected: "PNG image data, 432 x 432".
  - View the generated PNGs with the Read tool and check the letters are cut out.
  - Delete the placeholder `ic_stat_st.xml`.

- [ ] **Step 5: Adaptive icon.**
  - `colors.xml`: `st_icon_background = #333333`. Confirm it by sampling a corner pixel of ST's `img/apple-icon-512x512.png` (render it in the same headless page). Use the sampled value if it differs.
  - `ic_launcher_foreground.xml`: a vector, 108 dp, with both paths in their colors and the geometry above.
  - `mipmap-anydpi-v26/ic_launcher.xml`: `<adaptive-icon>` with background `@color/st_icon_background`, foreground `@drawable/ic_launcher_foreground` and monochrome `@mipmap/ic_launcher_monochrome`.
  - Manifest: `android:icon="@mipmap/ic_launcher"`, `android:roundIcon="@mipmap/ic_launcher"`.

- [ ] **Step 6: Splash.**
  - Add `core-splashscreen = "1.2.0"` and the library `androidx-core-splashscreen = { module = "androidx.core:core-splashscreen", version.ref = "core-splashscreen" }`, then `implementation(libs.androidx.core.splashscreen)`. If 1.2.0 doesn't resolve, use the newest 1.x on Google Maven and note it in the commit.
  - **Animation** — `splash_pulse.xml`, an animated-vector over a 240 dp vector (`viewportWidth/Height` 240):
    - The logo is 120 dp wide and centered. Scale `120 / 329.35 = 0.36436`; translate `(60, 63.95)` plus the inner `(-349.59, -9.8201)`.
    - The root group fades alpha 0 → 1 over 0–350 ms.
    - The red path's `fillColor` goes `#A11B1B` → `#E8463F` → `#A11B1B` over 250–1000 ms (`valueType="colorType"`). This is the "glow" approximation the spec leaves to the builder.
  - **Themes:** `Theme.StMobile.Starting` (parent `Theme.SplashScreen`) with `windowSplashScreenBackground = #18181B`, `windowSplashScreenAnimatedIcon = @drawable/splash_pulse`, `windowSplashScreenAnimationDuration = 1000` and `postSplashScreenTheme = @style/Theme.StMobile`. The activity uses `Theme.StMobile.Starting`.
  - **Code:** call `installSplashScreen()` first in `MainActivity.onCreate`, before `super.onCreate`. No `setKeepOnScreenCondition`: the splash never delays the app.

- [ ] **Step 7: Verify.**
  - Run `./gradlew :app:testDebugUnitTest :app:assembleRelease`. Expected: PASS. Install.
  - Screenshot the home screen (`adb shell input keyevent KEYCODE_HOME`) showing the icon.
  - Screenshot the notification shade (`adb shell cmd statusbar expand-notifications`, then `collapse`) showing the ST logo in red and the new copy.
  - Record a cold start: `adb shell am force-stop dev.halftoast.stmobile`, then `adb shell screenrecord --time-limit 5 /sdcard/stm-splash.mp4 &` and `adb shell am start -W -n dev.halftoast.stmobile/.MainActivity`. Pull the video and check the pulse.
  - Compare `TotalTime` from `am start -W` with a run before this task's install. Expected: no worse than +100 ms.

- [ ] **Step 8: Commit.** Message: `feat: ST app icon, notification and pulse splash`.

---

### Task 9: Whole-app check and README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the README.** In the tabs section, describe the floating button (drag to move; Panel/Stats/Logs from it), the themed screens following ST's theme on the next chat load, the selection sheet (with a note that it's injected on the phone and falls back to the plain list), and the icon/splash. Keep the existing style: short sentences, no marketing.

- [ ] **Step 2: Run the full verification.**
  - `./gradlew :app:testDebugUnitTest` (all green, count reported) and `tools/web-test.sh app/src/test/web/select-sheet.test.html` (PASS).
  - Build and install the release.
  - Run every device check from the spec's Verification section. Ask HalfToast for the in-ST taps (lists, keyboard cycles). For the theme-follow check, ask HalfToast to switch ST to another theme and back. Native screens must change after the next chat load, and back again.

- [ ] **Step 3: Commit.** Message: `docs: README for the visual overhaul`.
