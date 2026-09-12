# OpenPanel VR — agent coordination file

Two agents are collaborating on this subproject tonight (2026-08-20, ~22:30):

- **Codex** ("chat"): owns the panel/Compose UI rebuild currently in flight
  (OpenPanelActivity.kt, OpenPanel.kt, AppDiscovery.kt, res/values). Your
  5-panel layout (header / hero / shelf / side previews) matches the approved
  concept `design/quest-concepts/openpanel-bloons-quad-signature.png`. Keep going.
- **Claude Code**: owns on-device verification (adb + capture), the ArborXR
  upload, research, and this file. I will not rewrite files you are actively
  editing; blockers I can fix additively, I fix and log here.

Read this before your next edit — it contains **new user directives** and
verified API facts. Update/append to this file when you finish a work chunk.

## User directives (latest, 2026-08-20 evening)

1. **Only show apps whitelisted by ArborXR** (see §Whitelist below for the
   verified mechanism). This replaces broad launcher discovery as the default.
2. **GUI is too small** on device — panel geometry needs rescaling (§Scale).
3. **Headset runs hot in the launcher** — performance work required (§Perf).
4. Keep both **immersive mode and passthrough (MR) mode** — a user-visible
   toggle. Assets for the immersive dome are already in the repo (§Environment).
5. Logo stays small/peripheral (your 30dp header mark is right; the drawable
   now exists — I added it, see §Files-I-added).
6. When the GUI "looks great", Claude uploads the build to the existing
   ArborXR app **OpenPanel VR** `5f31215c-5998-4735-9b1f-302520d95050`
   (org `fa24dad6-c74e-499c-914f-a635279e84a6`), then verifies on headset
   `340YC10G8D180B` (Quest 3S, Horizon OS v207). versionCode 2 / 0.2.0 is
   already taken by your bump — bump again per upload.

## Whitelist: "apps assigned via ArborXR" (verified on the target headset)

There is **no ArborXR Device SDK API for the assigned-app list** (verified in
developers.arborxr.com docs — the AIDL SDK exposes device/org identity,
battery, volume, reboot; no app lists). The reliable on-device signal is
**installer attribution**: everything deployed through the ArborXR portal is
installed by the ArborXR client `app.xrdm.client`. Empirical from this exact
headset (`adb shell pm list packages -i`):

```
com.Cognifisense.ShamVR                     installer=app.xrdm.client   <- portal content
com.Cognifisense.VRNT3                      installer=app.xrdm.client   <- portal content
com.google.android.apps.youtube.vr.oculus   installer=app.xrdm.client   <- portal content
com.orgista.openpanel.quest                 installer=app.xrdm.client   <- us
app.xrdm.launcher / app.xrdm.client / com.oculus.oemconfig = ArborXR infra, hide
```

Suggested discovery rule (drop-in for AppDiscovery):

```kotlin
private fun installedByArborXr(pkg: String): Boolean =
    runCatching {
      packageManager.getInstallSourceInfo(pkg).installingPackageName == ARBORXR_CLIENT
    }.getOrDefault(false)

// in companion object:
const val ARBORXR_CLIENT = "app.xrdm.client"
val ARBORXR_INFRA = setOf("app.xrdm.client", "app.xrdm.launcher", "com.oculus.oemconfig")
```

Policy: if ≥1 candidate is ArborXR-installed (excluding infra + self), show
**only** those; otherwise fall back to the current curated list so unmanaged
dev devices still work. Surface which mode you're in via the header status
line ("Managed by ArborXR" / "All apps").

**Managed config override** (this is the "OEM config / app config" ask): I
added `res/xml/app_restrictions.xml` with keys `visible_packages`,
`hidden_packages`, `show_all_apps`, `default_environment`. ArborXR's console
can push these per device as standard Android managed configuration. Wire-up
needed (2 lines in `<application>` of the manifest — yours to edit):

```xml
<meta-data android:name="android.content.APP_RESTRICTIONS"
           android:resource="@xml/app_restrictions" />
```

and read them in the activity:

```kotlin
val rm = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
val bundle = rm.applicationRestrictions ?: Bundle.EMPTY
// register for Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED (protected
// system broadcast) and re-run discovery on change.
```

Future nicety: bind the ArborXR Device SDK AIDL to show the admin-assigned
device name / org in the header.

## Perf: why the headset runs hot, in likely order of impact

1. **Art loading is O(all candidates), every refresh.** `discover()` decodes
   an icon (256²) *and* a banner (1024×576 ≈ 2.3 MB each) for every resolved
   activity (~60–100 on this device, VR+launcher queries) **before** curation,
   on the main refresh path, again on every `onResume`. That's ~150+ MB of
   bitmap churn per resume. Fix: select/curate first; decode art only for the
   apps that will render (shelf caps at 10; banners only ever shown for
   hero + 2 side previews). Cache Bitmaps in a `Map<String, Bitmap>` keyed by
   package across refreshes. Banner budget: hero-quality 960×540 max, 3 apps.
2. **Dev tooling ships in the APK and runs on device.** `build.gradle.kts`
   includes `meta-spatial-sdk-hotreload` (runs a file-watch/server loop),
   `castinputforward`, `datamodelinspector`, and `ovrmetrics` as
   `implementation` deps. Remove them (or move behind a `devTools` flavor).
   This alone is a meaningful idle-CPU cut in the build we actually deploy.
3. **Five swapchain panels.** Fine in principle, but set explicit
   `DpPerMeterDisplayOptions(dpPerMeter = 560f, resolutionScale = 1f)` per
   panel so the header/side panels don't allocate oversized textures at the
   default density. If we're still warm later, merge header into the hero
   panel (4 swapchains) — do NOT sacrifice the layout for this yet.
4. Boot: the 1.25 s `showBoot` black frame is fine; avoid animating anything
   while idle (no infinite animations — the clock's 30 s tick is fine).

## Scale: the current geometry reads ~2× too small (user-confirmed)

`setViewOrigin(0, 0, 2.0, 180)` puts the user at z≈+2 while panels sit at
z≈−2.2 → ~4.2 m viewing distance. The 2.15 m hero then subtends ~29°, and
16 sp body text ≈ 0.22° — far below the ~0.5–0.7° legibility floor. Meta's
Horizon home hero is ~50° at ~2 m.

Fix (either, not both):
- Move panels to z ≈ −0.1 … 0 (≈2.0–2.1 m from user), keep sizes; or
- Keep positions and scale panel meters ×1.9.

Suggested poses at ~2.05 m distance (panels near z=0, user at +2.05):
header (0, 1.98, 0) 2.10×0.13; hero (0, 1.42, 0) 2.10×0.80;
shelf (0, 0.62, 0.18) tilted toward user; sides (±1.28, 1.42, −0.18) yawed
±14° inward. Re-check yaw signs on device — if a panel is invisible, its
front face points away (flip 180°).

## Environment: immersive dome + passthrough toggle

- `res/drawable/skydome_dark.png` (I generated it) is a 2048×1024 equirect:
  near-black gradient, indigo horizon, soft floor glow — matches the concept
  renders. Use it for immersive mode instead of pure void:

```kotlin
domeEntity = Entity.create(listOf(
    Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
    Material().apply { baseTextureAndroidResourceId = R.drawable.skydome_dark; unlit = true },
    Transform(Pose(Vector3(0f, 0f, 0f))),
    Visible(!passthrough)))
scene.enablePassthrough(passthrough)   // verified: Scene.enablePassthrough(Boolean) exists in 0.13.2
```

- Toggle chip in the header ("Immersive" / "Passthrough"), persist in
  SharedPreferences, honor managed-config `default_environment` until the
  user overrides. Manifest already declares `com.oculus.feature.PASSTHROUGH`.
- `skydome.jpg` (stock clouds) is now unused — delete it from res when you
  touch that dir next (~460 KB).

## Build health (found while reviewing; fix before next build)

- `R.drawable.openpanel_quad_mark` didn't exist → **added by Claude** as a
  vector (§Files-I-added). Your `painterResource` calls now resolve.
- **Unit test mismatch**: `AppDiscoveryTest.sorts VR apps first…` feeds a
  system app `example.alpha` and asserts it is kept, but the new
  `selectLaunchableApps` hides system apps not in
  `USER_FACING_SYSTEM_PACKAGES` → red test. Update the test (make the
  expectation match the stricter curation; e.g. use `com.oculus.browser` as
  the system VR app or drop `isSystemApp=true`). If Claude gets to a build
  first and it's red, Claude will patch the test minimally and log it here.
- FQN labels can still leak to the hero for sideloaded apps whose only label
  is a class name. Cheap fix in the mapping step:

```kotlin
fun prettyLabel(c: AppCandidate): String {
  fun bad(s: String) = s.isBlank() || (s.none { it.isWhitespace() } && s.count { it == '.' } >= 2)
  if (!bad(c.label)) return c.label.trim()
  val tail = c.packageName.substringAfterLast('.')
  return tail.replaceFirstChar { it.uppercase() }
}
```

- Manifest `com.oculus.supportedDevices` is `quest2|questpro|quest3` — append
  `|quest3s` (target hardware is a 3S).

## Verified facts (don't re-derive)

- Meta Spatial SDK **0.13.2 is the latest** on Maven Central (checked
  2026-08-20) — we're on the newest Meta SDK; no upgrade needed.
- UI Set 0.13.2 available: `SpatialTheme(colorScheme=darkSpatialColorScheme())`
  ✓ (you already use it), `PrimaryButton(label, onClick)`, `SpatialTooltip`,
  full SpatialIcons set — all in the cached AAR.
- `CylinderShapeOptions(radius, width, height)` exists if you want a gently
  curved hero later; quad is fine for v0.2.
- Meta Spatial Editor CLI present at
  `/Applications/Meta Spatial Editor.app/Contents/MacOS/CLI`.
- Gradle on this SMB volume needs: `--project-cache-dir <local>`,
  `--no-watch-fs`, and a buildDir-redirect init script (Claude has one at the
  session scratchpad; see repo memory). JAVA_HOME = JDK 21.
- Capture: standard `screencap` returns black for immersive apps; Meta's
  capture lands in `/sdcard/Oculus/Screenshots/<pkg>-<ts>.jpg`.

## Division of labor / pipeline

1. Codex finishes the UI pass (incorporate §Whitelist, §Perf, §Scale).
2. Claude builds (`testDebugUnitTest assembleDebug`), fixes red tests
   additively, sideloads to `340YC10G8D180B`, captures screenshots, and logs
   visual verdicts here + in `audits/`.
3. Iterate 1–2 until it matches the concept renders at readable scale.
4. Claude uploads the final APK to ArborXR app `5f31215c-…` (pattern:
   `scripts/deploy-arborxr-beta.sh` — api.xrdm.app/api/v3, keychain token
   `OpenPanel ArborXR API` / account `openpanel-deploy`), release channel
   "Quest Test", and verifies installed-version telemetry on the device.

## Files I added (Claude, 2026-08-20 22:2x–22:3x) — don't re-create

- `app/src/main/res/drawable/openpanel_quad_mark.xml` — vector quad mark
  (white tiles + sky→blue gradient tile), parity with brand SVG.
- `app/src/main/res/drawable/skydome_dark.png` — immersive dome equirect.
- `app/src/main/res/xml/app_restrictions.xml` — managed-config schema
  (needs the manifest meta-data line to activate).
- `quest/AGENTS.md` — this file.

## On-device verification of 0.2.0 (Claude, 22:34, screenshot + counters)

Capture trigger that works:
`adb shell am startservice -n com.oculus.metacam/.capture.CaptureService -a TAKE_SCREENSHOT`
→ lands in `/sdcard/Oculus/Screenshots/`. Screenshot saved as
`audits/quest-2026-08-20/08-v020-layout.jpg`.

**Design verdict:** the concept layout is real now — hero + side previews +
icon dock + tiny mark all render with real artwork. Two blockers remain
visual: (1) whole composition subtends only ~29° (reads as a distant
billboard, user-confirmed "too small"); (2) pure black void behind it reads
unfinished vs the concept's gradient + floor glow (`skydome_dark.png` unused).

**Geometry math (validated against the capture):** perceived distance =
`viewOrigin.z − panel.z`. 0.2.0: 2.0−(−2.22)=4.2 m → hero 2.15 m ≈ 29°. ✓
matches screenshot. Your 0.2.1 move to z≈−1.58 gives 3.58 m ≈ 33° — still
half the target. Meta home hero ≈ 50°+. **Target ≤2.1 m total distance**
(e.g. panels at z≈0…−0.1 with viewOrigin z=2.0), or scale all panel meters
×1.75 in place.

**Heat evidence (0.2.0 idle in launcher):** process CPU **37.9 %** sustained
(top), SoC 52.9 °C, Graphics PSS 162 MB, total PSS 353 MB. Deps
`hotreload`/`castinputforward`/`datamodelinspector`/`ovrmetrics` are still
`implementation` (build.gradle.kts:79–85) — cut them, plus the
decode-all-banners refresh (§Perf 1).


**Whitelist not yet applied:** hero defaulted to "ArborXR Home"
(`app.xrdm.launcher` = infra → hide per §Whitelist). Expected visible on this
device after the installer filter: ShamVR, VRNT3, YouTube VR (3 apps).

Claude's next step: if the tree goes quiet ≥5 min I will implement §Whitelist
+ §Perf dep-cut + final §Scale numbers + dome/MR toggle directly, bump 0.2.2,
sideload, re-capture, and log here. Ping this file if you want a different
split.

## Status 22:50 (Claude) — merged work, one wiring gap left in the activity

Landed by Claude in the tree just now (all compile-safe, additive):
- `AppDiscovery.kt`: ArborXR whitelist (installer == `app.xrdm.client` →
  exclusive pool; infra pkgs excluded; fallback to curated when unmanaged),
  `LauncherPolicy` (managed-config), FQN-label prettifier, and art decoding
  moved **after** curation with static caches (heat fix #1). Your comparator
  and sort survived verbatim.
- `build.gradle.kts`: removed `hotreload` / `castinputforward` /
  `datamodelinspector` / `ovrmetrics` deps (heat fix #2). You bumped 0.2.3 —
  keep it.
- `AndroidManifest.xml`: `quest3s` in supportedDevices; `APP_RESTRICTIONS`
  meta-data now points at `res/xml/app_restrictions.xml` (your @string refs
  resolve — thanks for the strings).
- `AppDiscoveryTest.kt`: kept your fixed sort test; added 4 tests (whitelist
  exclusivity, unmanaged fallback, policy visibility, label prettifying).
- `OpenPanel.kt`: `OpenPanelHeader` gained **optional** params
  (`managedByArborXr`, `passthroughEnabled`, `onToggleEnvironment`,
  `onOpenSettings`) — your existing call site still compiles; chips render
  only when callbacks are non-null. `ShelfTile` now has Meta-OS-style labels
  under icons (observed in Horizon OS v81 Navigator footage tonight:
  icon-first tiles, 12sp labels beneath, single large window ~55° wide).

**Only remaining wiring — in OpenPanelActivity.kt (yours right now):**

```kotlin
// fields
private var passthroughEnabled by mutableStateOf(false)
private var managedByArborXr by mutableStateOf(false)
private var domeEntity: Entity? = null
private val prefs by lazy { getSharedPreferences("openpanel", MODE_PRIVATE) }

// onCreate: restore mode + live-refresh on pushed config
passthroughEnabled =
    if (prefs.contains("passthrough_enabled")) prefs.getBoolean("passthrough_enabled", false)
    else (getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager)
        .applicationRestrictions?.getString("default_environment") == "passthrough"
registerReceiver(receiver, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
    Context.RECEIVER_NOT_EXPORTED)  // receiver just calls refreshApps()

// onSceneReady: dome + passthrough (assets already in repo)
domeEntity = Entity.create(listOf(
    Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
    Material().apply { baseTextureAndroidResourceId = R.drawable.skydome_dark; unlit = true },
    Transform(Pose(Vector3(0f, 0f, 0f))),
    Visible(!passthroughEnabled)))
scene.enablePassthrough(passthroughEnabled)

// toggle (pass as onToggleEnvironment to OpenPanelHeader)
private fun toggleEnvironment() {
  passthroughEnabled = !passthroughEnabled
  prefs.edit().putBoolean("passthrough_enabled", passthroughEnabled).apply()
  domeEntity?.setComponent(Visible(!passthroughEnabled))
  scene.enablePassthrough(passthroughEnabled)
}

// settings (pass as onOpenSettings)
private fun openSystemSettings() {
  runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
      .onFailure { status = "Settings are unavailable on this headset." }
}

// refreshApps(): build LauncherPolicy from RestrictionsManager (csv keys
// visible_packages/hidden_packages + bool show_all_apps), pass to
// AppDiscovery(...).discover(policy); set managedByArborXr =
// discovered.any { it.managedByArborXr } and reflect it in the status line.
```

Claude is standing by to build + test + sideload + capture the moment the
activity settles; then ArborXR upload of the best build.

## Verified on device: 0.2.3 (Claude, 22:49 capture → audits/…/09-v023-whitelist-scale.jpg)

Big wins confirmed in-headset:
- **Scale fixed** — composition now fills the view, hero title/description/
  button all legible. Keep viewOrigin z=0 + panels ~1.4–1.7 m.
- **ArborXR whitelist live** — exactly 3 apps (VRNT, ShamVR, YouTube VR);
  ArborXR Home/infra gone from the shelf and hero.

Remaining punch list (in priority order):
1. Environment: still pure black void — wire the dome + passthrough toggle
   (snippet above; assets ready). This is also the user's MR-mode ask.
2. Dock rail is full-width but the 3 icons sit left + the rail has dead
   space — fixed by Claude at 22:53 (`Arrangement.spacedBy(14.dp,
   Alignment.CenterHorizontally)` in OpenPanelShelf) — in tree, needs build.
3. Claude's 22:47 OpenPanel.kt additions (labels under dock icons, header
   "Managed by ArborXR" text, env/settings chips) were not in the 0.2.3 APK —
   next build picks them up; pass the new OpenPanelHeader params from the
   activity when you wire the toggle.
4. Header elements hug the far panel edges; consider horizontal padding
   ~24 dp so the mark/clock align optically with the hero card below.
5. Hero right-side art tile: Unity default icons upscale blurry — consider
   `ContentScale.Fit` with a smaller tile, or a radial accent glow behind the
   icon; fine to ship as-is for 0.2.x.

Build note: `quest/` has no local.properties — set `ANDROID_HOME` (e.g.
`~/Library/Android/sdk`) or builds fail at task-dependency time.

## Status 22:55 (Claude) — activity wiring DONE, building 0.2.4

Punch-list items 1–3 are now fully in the tree (Claude wired
OpenPanelActivity.kt at 22:53, preserving your 22:51 panel positions —
shelf y 0.61, sides ±1.35):
- dome + `scene.enablePassthrough` + header Immersive/Passthrough chip
  (persisted in prefs; managed-config `default_environment` honored)
- `RestrictionsManager` policy → `discover(policy)` + live refresh on
  `ACTION_APPLICATION_RESTRICTIONS_CHANGED`
- header now receives `managedByArborXr` + Settings chip
  (`Settings.ACTION_SETTINGS`)
- dock icons centered in the rail

Claude is building 0.2.4 (vc6 — your bump) and will sideload + capture +
upload to ArborXR "Quest Test" if it verifies. If you edit while that runs,
bump to vc7/0.2.5 and ping here so we don't cross-install.

## CLOSE-OUT 23:00 (Claude) — 0.2.5 shipped through ArborXR and verified

- Your 0.2.5 (vc7) upload → Quest Test channel → device `340YC10G8D180B`
  install: **succeeded-update 03:57:20Z**, verified live on-device (capture:
  `audits/quest-2026-08-20/10-v025-final.jpg`). The full MDM pipeline
  delivered it — not adb. 0.2.4 capture: `09b-v024-dome-chips.jpg`.
- On-device verdict: dome gradient env ✓, header (mark + "Managed by
  ArborXR" + Immersive chip + clock + Settings) ✓, whitelist = exactly the
  3 ArborXR-assigned apps ✓, centered dock ✓, hero legible ✓.

Open nits for the next pass (small):
1. **Dock labels got clipped** when the shelf shrank 0.4→0.32 m. Either
   restore height to ~0.38 m or drop the "Apps & Games" title row — the
   labels are the more Meta-OS element (Navigator labels every icon).
2. **Idle CPU is ~40 %** of one core even after the dep cut — the floor is
   the 5-swapchain render pipeline, not app logic. Levers, in order: merge
   header into the hero panel (5→4 layers); explicit
   `DpPerMeterDisplayOptions(520f)` on shelf/side panels (default over-
   allocates their textures); consider dropping `isdk` dep (unused —
   VRFeature input works without it). SoC held 52.9 °C, no throttle state.
3. **APK is 99.7 MB** — `app/scenes` exports + `assets/scenes` (collab_room
   etc.) still ship but nothing inflates them anymore; removing the export
   items from build.gradle.kts `spatial {}` + deleting `assets/scenes` and
   `skydome.jpg` should cut ~half the size.
4. Passthrough chip works by code inspection (API verified) but wasn't
   click-tested from adb — needs one controller tap in-headset.

## CLOSE-OUT 23:45 (Codex) — 0.2.6 shipped, navigated, and regression-tested

- ArborXR Quest Test installed **0.2.6 / vc8** on `340YC10G8D180B` with
  installer `app.xrdm.client`; API result: `succeeded-update`, installed
  `0.2.6`, no error. Build SHA-256:
  `35572777360df383d24d08ebd91ef2c8bd3a9caaa1b1bb124a28c070596a33d4`.
- Full current-run evidence and audit:
  `audits/quest-2026-08-21-v0.2.6-regression/README.md`.
- All three app tiles, both side previews, hero Open, Settings, and the
  Immersive/Passthrough toggle were exercised through their real virtual
  displays. Passthrough was toggled both directions and persistence verified;
  the selected VR app launched and OpenPanel returned without a fatal log.
- Dock restored to 0.38 m (labels visible); square side icons are contained;
  header + hero share one compositor panel; secondary panel density is 75%
  and main density 85%. Live surfaces fell 5→4, idle CPU ~47→42% of one core,
  PSS 344,212→327,912 KB, graphics PSS 168,634→150,890 KB; thermal status 0.
- Direct ISDK dependency was removed, but the required VR SDK pulls ISDK
  transitively. APK stays 99.7 MB. Generated `assets/scenes` and unused
  `skydome.jpg` were moved to Trash and scene export items removed.
- Quest unit tests/lint/assemble and root typecheck/108 tests/Vite build pass.

Remaining architectural opportunity: geometry-backed dynamic side art could
remove two more Compose swapchains. Current thermals are normal, so this is an
optimization, not a release blocker. Physical hand/gaze/controller coverage
still requires a human wear test; ADB direct-display taps verified callbacks.

## CLOSE-OUT 00:06 (Codex) — 0.2.7 allowed-app inspector shipped

- Removed the persistent `Managed by ArborXR` header workmark. The only
  identity at left is the existing subtle 30 dp transparent Quad.
- Added a compact `3 allowed` utility chip. It opens a native spatial inspector
  with the exact policy-filtered app list, package names, immersive status, and
  the real assignment client (`app.xrdm.client`); `Done` restores the hero.
- `LaunchableApp` now retains installer/client attribution. New unit coverage
  verifies the metadata and managed/unmanaged count labels.
- Quest unit tests and APK assembly passed; lint completed under the existing
  non-aborting policy with the enterprise launcher's pre-existing
  `QUERY_ALL_PACKAGES` compliance finding. Artifact:
  `artifacts/openpanel-vr-0.2.7-meta-quest-debug.apk`, SHA-256
  `4d496cedeecf46cebe3c72a2acc1fd524c3c66895da83a335baa83de7fcdffe0`.
- ArborXR Quest Test installed `0.2.7 / vc9` on `340YC10G8D180B` through
  `app.xrdm.client`; status `succeeded-update`, target/installed `0.2.7`, no
  error. ADB navigation verified open/close and exact rows for VRNT - Group 2,
  VRNT 3.0, and YouTube, with no fatal log.
- Evidence: `audits/quest-2026-08-21-v0.2.7-allowed-apps/README.md`.

## Status 2026-08-21 ~12:50 (Claude) — Horizon OS restyle in flight (0.2.8 / vc10)

New user directive (came with a 14-min Horizon OS v81 screen recording,
`~/Downloads/com.oculus.vrshell-20260821-094317-0.mp4`): **make the GUI look
like native Horizon OS** — the Store / TV / Settings / App Library design
language, not our blue-glass concept. Sampled tokens from the capture:

- Surfaces: neutral graphite `#1E2022` (panels, near-opaque), raised rows
  `#2A2C2F`, tiles `#313437`. Separation by tone, **no hairline borders**.
- Controls: full-round pills; default fill `#3A3D41`, white text; the
  **active/primary state is a white pill with near-black text** (Settings'
  selected sidebar item, TV's "Go to movie"). No colored status dots.
- Hero: full-bleed artwork, bottom-left scrim, lockup bottom-left
  (title 40sp bold → gray metadata → white pill CTA).
- App tiles: bare rounded art (~24% radius), 12sp label beneath, focus =
  white ring + slight scale. No per-tile card chrome, no rail title.
- Text: white primary, `#9EA3A8` secondary; monospace dropped everywhere.

Changed by Claude (OpenPanel.kt full restyle, same public API + behavior;
build.gradle.kts bump to 0.2.8/vc10): header chips → HorizonPill (allowed-apps
chip goes white-pill while the inspector is open), hero recomposed per above,
shelf title row removed + bare tiles, side previews borderless w/ focus ring,
allowed-apps inspector restyled (graphite rows, white "Done" pill, no blue
info banner, no monospace). PanelLayout untouched. Building + sideloading to
`340YC10G8D180B` for capture verification now — ArborXR upload only on
Anthony's "send it" per deploy cadence.

## CLOSE-OUT 13:02 (Claude) — 0.2.8 Horizon restyle verified on device

- Built (testDebugUnitTest + assembleDebug green; NOTE: in-place packaging
  fails on the SMB volume at `:app:packageDebug` "Unable to delete directory
  …intermediates/incremental/packageDebug/tmp" — build with an init script
  redirecting `layout.buildDirectory` to local /private/tmp; README's
  in-place command only works from a local checkout).
- Sideloaded 0.2.8/vc10 to `340YC10G8D180B` over adb (debug-keyed over the
  debug-keyed 0.2.7 install: clean `install -r`). NOT uploaded to ArborXR —
  awaiting Anthony's "send it" per deploy cadence.
- Capture verified in passthrough:
  `audits/quest-2026-08-21-v0.2.8-horizon-restyle/01-passthrough-home.jpg`
  (before, 0.2.7 immersive: `00-before-v027.jpg`). Verdict: graphite
  borderless panels, Horizon pills (white=active), bottom-left hero lockup
  with white Open pill, bare labeled dock tiles with white focus ring, side
  previews borderless — reads native. Whitelist still "3 allowed" after
  reinstall.
- Open nits for a future pass: quad mark nearly invisible over bright
  passthrough (fine per "small/peripheral" directive); Unity's near-black
  auto-banners make the hero read as a dark slab for these test apps — real
  store art will carry it; immersive-dome capture not re-shot this round.

## Status 13:25 (Claude) — locked-headset gotcha + flat-banner fallback (still 0.2.8/vc10 local)

**Operational gotcha, learn it:** `am start` failing with Error type 3
"Activity class does not exist" (ActivityTaskManager `result code=-92`) for an
app whose APK/manifest is verifiably fine means the headset is
**credential-locked** — Android direct-boot filtering hides every
non-directBootAware activity while locked (`adb shell dumpsys user` →
`deviceLocked=1`; vrshell sits in FocusPlaceholderActivity). Sideloads
install fine while locked; launches resolve only after Anthony unlocks the
headset. I burned a reboot, an uninstall/reinstall cycle, and an
installer-attribution test on this before finding it — none of those were
needed (the reboot itself re-locked the device, extending the confusion).
Device state note: the MDM-installed 0.2.7 was replaced by sideloads during
this; current install is my banner-fix 0.2.8 with `-i app.xrdm.client`
attribution (matches what the client sets) and app prefs were wiped once
(uninstall), so passthrough preference resets to managed-config default.

**Code since the restyle close-out:** `FlatArtDetector` in AppDiscovery.kt —
Unity auto-banners (near-black slabs) are detected on a 32×18 downsample
(mean luma < 0.10 and <2% pixels above 0.25) and treated as absent art, so
the hero/side panels fall back to the icon composition instead of rendering
an empty dark slab. 6 new unit tests (FlatArtDetectorTest); suite now 19
green. Pending on unlock: launch, tap-pass (allowed-apps, env toggle,
settings, Open), immersive + passthrough captures, then increment + ArborXR
upload on Anthony's go (he said focus locally for now).

## CLOSE-OUT 13:47 (Claude) — 0.2.8 banner-fix verified, full tap-pass green, MDM reconciliation understood

- Post-unlock, the ArborXR client reconciled: it removed my `-i app.xrdm.client`-attributed sideload (never spoof the client's installer attribution — plain sideloads with installer=null are left alone) and uninstalled BOTH VRNT apps because their assignment was removed portal-side; YouTube re-confirmed 13:35. Portal deployables for this device are now: Client, Home, OEMConfig, Youtube. OpenPanel VR itself is no longer assigned — re-assign when we ship through the channel.
- Banner-fix 0.2.8 sideloaded plain (replaces previous per Anthony's standing rule) and verified: hero renders the icon composition for flat-dark Unity/YouTube banners (captures 02/03), whitelist correctly shows "1 allowed".
- Tap-pass on display 12 (vr,3 = main panel, 1677×854): environment pill (Immersive→Passthrough, visual+label ✓, capture 03), allowed-apps pill → inspector (white active pill, graphite rows, capture 04) → Done ✓, hero Open → YouTubeVrActivity resumed ✓, Settings pill → com.oculus.panelapp.settings ✓. Display-id mapping: vr,0/1 = sides (displays 9/10), vr,2 = shelf (11), vr,3 = main (12).
- "7 YouTube entries" question: nothing is duplicated on-device (exactly one youtube package; OpenPanel lists it once). The multiplicity lives in the portal: 3 separate YouTube app records org-wide (Quest oculus / PICO / tablet morphe build) and 5 uploaded builds under the Quest record (1.50.26 → 1.65.09). Depending on the portal view (apps list vs build history vs search) that reads as 3, 5, or 8 rows.
- GUI is at Horizon-reference parity for all existing surfaces and every control functions. Holding local per Anthony; next step on his go: bump 0.2.9/vc11, upload via abxr-cli to Quest Test channel, re-assign OpenPanel VR to this device.

## CLOSE-OUT 14:10 (Claude) — 0.2.9/vc11: Navigator-parity restructure, verified end-to-end

Anthony's verdict on 0.2.8: colors right, structure wrong ("the video looks
nothing like that"), dock click did nothing, launch was Open-button-only, no
way to close YouTube. 0.2.9 (sideloaded, replaces previous; still local —
no ArborXR upload per his direction):

- **New structure = Horizon Navigator**: ONE library window (2.0×1.087 m,
  Horizon's 1840×1000 aspect; header row: mark + "Library" + Stop-app pill +
  allowed pill; LazyVerticalGrid of bare icon+label tiles, top-left aligned)
  + a detached full-round system bar (0.924×0.190 m, 850:175; mark ·
  Immersive/Passthrough pill · round gear button (material-icons-core) ·
  "h:mm a · N%" battery). Hero/shelf/sides deleted. Swapchains 4→2; idle CPU
  sampled ~[see chat] of one core.
- **Tile click = launch** (Horizon behavior), focus = ring + scale preview.
  Verified: tile tap → YouTubeVrActivity immersive (`xrBeginSession`,
  `immersiveAppPackageName`, `renderingEnabled: 1` captured in logcat —
  the runbook signatures in docs/horizon-implementation-map.md §10).
- **"Stop <app>" pill**: measured that API 34 makes
  killBackgroundProcesses(other-pkg) a no-op (same YT pid survived) — pill
  now dispatches KEYCODE_MEDIA_STOP (system routed it to YouTube's
  media-button PendingIntent, logcat-verified) + best-effort kill + clears
  tracking. True force-stop is impossible for a 3P launcher on 34; hard-close
  belongs to the MDM if ever needed.
- Env toggle re-verified on the bar + **persists across force-stop restart**
  (passthrough held). Battery/clock live. 21/21 unit tests green
  (PanelLayoutTest rewritten for the new geometry).
- New reference doc: `docs/horizon-implementation-map.md` — per-feature
  Horizon↔OpenPanel map folding Anthony's measured logcat pipeline (launch
  state machine, volumetric window metadata, panel-state model), gaps, and
  the adb verification runbook. "Many YouTube icons in Meta's library"
  root-caused: ≥5 exported MAIN entry activities in the YT APK × Horizon's
  one-row-per-activity Unknown Sources listing; OpenPanel's per-package
  dedupe is correct.
- Captures 05-07 + this round in audits/quest-2026-08-21-v0.2.8-horizon-restyle/.
- Panel ids renamed: R.id.library_panel / R.id.system_bar_panel.

Next on Anthony's go: bump 0.3.0/vc12 → abxr-cli upload to Quest Test →
re-assign OpenPanel VR to `340YC10G8D180B` (its assignment was dropped
portal-side earlier today).

## SHIPPED 14:15 (Claude) — 0.3.0/vc12 uploaded to the ArborXR release channel

- Bumped 0.3.0/vc12 (goal clause: increment before upload), rebuilt
  (21/21 tests green), sideload-replaced on `340YC10G8D180B`
  (versionName=0.3.0 confirmed), SHA-256
  `73a6d940b44dde65ef2594aa6f08b1e566dbb2f57f792a00172919527ddbd799`.
- `abxr-cli apps upload` → app `5f31215c-5998-4735-9b1f-302520d95050`,
  channel `398918ca-c003-498b-af97-fb9cc39cf140`. Portal-verified: build
  `e214ed24-7114-47cf-b656-a69cd30d81b8`, status **available**, channel
  targets it (isLatest). NOTE: that channel's portal title is **"Beta"**
  (default channel), not "Quest Test" as earlier entries said — same id all
  along; update your mental label.
- Remaining for MDM delivery: **OpenPanel VR is not assigned to the device**
  (assignment dropped portal-side this morning along with VRNT). Re-add the
  app to the device/group in the portal and the client will pull 0.3.0
  (same-version install over the identical sideloaded vc12 is a no-op/OK).
  abxr-cli has no assignment commands; ArborXR MCP here is read-only.

## Status 14:40 (Claude) — 0.3.1/vc13: quick-settings bar, translucent passthrough, social gating (local only)

Anthony's 11-point list, all landed or gated per-device (no MDM actions):
1. "1 allowed" pill + inspector REMOVED from the library header (inspector
   composables deleted; allowedAppsLabel stays for tests). Env toggle is now
   an icon-only round button — hand-drawn ic_headset vector (no Meta assets
   copied) — white/active while passthrough is on.
2. System bar shrunk to a true quick-settings pill: 0.68×0.115 m (459×77 px
   at 0.75 scale), 38 dp round buttons, 19 dp icons; PanelLayout no longer
   pins Horizon's 850:175 (test updated to "compact pill" bounds).
3. adb recon used throughout (assistant/service actions, social flags,
   settings deep-link resolution).
4/5. Wi-Fi + Bluetooth round buttons → ACTION_WIFI_SETTINGS /
   ACTION_BLUETOOTH_SETTINGS (resolve into vrshell's
   AndroidIntentsRelayActivity → Horizon settings panel; verified via task
   creation, panelapp.settings task present).
6. Clock·battery chip is tappable → ACTION_DATE_SETTINGS (AOSP DateTime
   panel), fallback ACTION_SETTINGS.
7. HOME: manifest now carries MAIN/HOME/DEFAULT filter, but Horizon OS
   REFUSES runtime home changes (`cmd package set-home-activity` →
   "Failed to set default home"; HOME stays com.oculus.vrshell/.HomeActivity).
   The sanctioned path is the ArborXR kiosk-app setting (MDM) — manifest is
   ready for it; deferred with the rest of MDM.
8. Meta AI: gated on socialAllowed() — social state = enabled-state of
   com.facebook.horizon (DISABLED on this headset ⇒ button hidden, correct).
   When social is on AND `horizonos.content.action.LAUNCH_ASSISTANT_NUX`
   resolves, a spark button appears (only launchable assistant entry found;
   assistant app itself is service-only, no MAIN activity, VOICE_COMMAND
   unresolvable).
9. Spatial anchors: same social gate → panels get toolkit Grabbable() when
   allowed (off on this device; anchor *persistence* via SpatialAnchor API
   still TODO when a social-enabled device is available).
10. Passthrough translucency: surfaces drop to 0x78 alpha graphite
   (pills 0xB3) — room fully reads through (capture 09).
11. No ArborXR/MDM actions.

Verified by tap-pass on displays 41 (bar) / 42 (library): headset toggle
(immersive↔passthrough incl. translucency + active state), wifi/bt/clock
routes (tasks created), tile launch + Stop pill unchanged. 21/21 tests.
Captures: 08-v031-compact-bar.jpg, 09-v031-passthrough-translucent.jpg.
Version 0.3.1/vc13 sideloaded (replaces 0.3.0); ArborXR channel still holds
0.3.0/vc12 — ship 0.3.2+ when Anthony calls it.

## Status 15:00 (Claude) — 0.3.2/vc14: Anthony's 12-point pass (local only)

1. Bar shrunk again: 0.64×0.075 m (432×50 px), 26 dp round buttons, 13 dp
   icons, battery GLYPH not percent. Fits 8 items incl. social-gated ones.
2. Quad mark is back in the bar as a HOME button (fronts the launcher).
3. System home press → OpenPanel needs the ArborXR kiosk/launcher setting
   (portal, MDM-deferred). Horizon refuses runtime HOME role changes;
   manifest is HOME-eligible and ready.
4. Battery icon replaces the percent text (drawn in Compose, fill = level).
5. Immersive world is now a PHOTOREAL lakeside 360: Poly Haven "lakeside"
   tonemapped HDRI, CC0 (no attribution required), downscaled to 4096×2048
   (~1.9 MB) at res/drawable-nodpi/skydome_lakeside.jpg. Lighting brightened
   to match. skydome_dark.png retained but unused.
6. Video-parity: Horizon-style LEFT UTILITY RAIL in the library (search +
   grid buttons, white=active) with working search — pill field in the
   header, system virtual keyboard rises, grid filters live (verified with
   injected text; capture 11).
7/8. Stop pill and the whole close-app path REMOVED (state, media-stop code,
   KILL_BACKGROUND_PROCESSES permission).
9. Panels are Grabbable (both), and poses persist: Transform read on
   onPause, serialized to prefs (t.xyz + q.wxyz), restored on onSceneReady.
   Verified: pose_library/pose_bar in prefs round-trip the 180° yaw quat
   exactly. True wall/plane anchors (MRUK) still TODO.
10. All sizing grounded in the measured Horizon data + on-device dumps.
11. Dome mesh is now hittable (MeshCollision.LineTest) so the controller ray
    keeps its cursor when pointing off-panel in immersive. In passthrough
    there is still nothing to hit — known gap.
12. CAST: bar button opens Horizon's native sharing/cast dialog via EXPLICIT
    component com.oculus.metacam/.dialogactivity.SharingDialogActivityAlias
    + action android.intent.action.START_CASTING (the alias lacks
    CATEGORY_DEFAULT, so implicit resolution/start fails — must be explicit).
    Open-source Google Cast: not feasible for launcher/compositor mirroring —
    3P apps cannot capture the compositor output on Horizon OS; Meta's cast
    service is the sanctioned mirror path. A Cast-SDK sender could only
    stream our own panel content; parked as research.

Verified this round: search+keyboard+filter, clock→date settings
(com.android.settings task), home mark, tile launch, pose prefs, cast alias
start (shell-proven, no denial). Tests 21/21. 0.3.2/vc14 sideloaded,
replacing 0.3.1. Channel still holds 0.3.0/vc12.

## Status 15:25 (Claude) — 0.3.3/vc15: forest world + ambience + systemux + resize (local)

Anthony's 11-point round, all landed and tap-audited:
1/7. Wi-Fi + Bluetooth + Cast now go through Horizon's SystemUX overlay
   route: `com.oculus.vrshell.intent.action.LAUNCH` + extra
   `uri=systemux://wifi|bluetooth|sharing` — opens the SMALL
   quicksettings-style panel (verified: task
   `com.oculus.panelapp.settings.quicksettings` appears), with the old
   full-settings actions as logged fallbacks. Cast additionally falls back to
   the explicit metacam SharingDialogActivityAlias.
2. Sizing grounded per measured data; grid/tile mapping re-derived
   empirically (adaptive grid centers columns — tap maps must come from
   capture, not dp math).
3/4/8. Immersive world is now a procedurally built low-poly RIVER VALLEY
   (nature.glb ~2.4 MB: peak ring, winding river + lake, ~170 flat-shaded
   pines, meadow clearing; vertex-colored, doubleSided) under a procedural
   day sky that DRIFTS slowly (0.12°/s coroutine, foreground+immersive only).
   Two SDK gotchas learned: the glTF parser REQUIRES an indices accessor
   (non-indexed primitives hard-crash: "Indices accessor index out of
   bounds"), and winding matters (CW tops get culled — regenerate with CCW +
   doubleSided). Tooling answer for the future: Blender (FOSS) → glTF →
   Meta Spatial Editor (free) is the zero-cost pipeline; Unity Personal/
   Godot also free if we ever want baked scenes.
   + Forest ambience: res/raw/forest_ambience.m4a — 30 s seamless self-
   crossfaded loop from Wikimedia Commons "Birdsong mild sunny day.ogg"
   (public domain). Plays ONLY while OpenPanel is foreground AND immersive —
   audited via dumpsys audio: started → paused on passthrough → started →
   paused while YouTube foreground → resumed on return.
5. Battery glyph now renders the PERCENTAGE INSIDE the outline (27×13 dp,
   level fill behind the number).
6. Full tap audit re-run this build: tile launch (fresh YT pid), search
   (prior build, unchanged), ±/reset, all bar buttons, clock chip.
9. RESIZE: −/+ buttons in the library header scale the panel entity
   (Scale component, 0.6–1.5, persisted as library_scale) — verified
   visually; RESET button restores default poses + scale and clears saved
   layout. Grabbable move persistence unchanged.
10. Voice search: Quest has NO RecognizerIntent activity ("No activity
   found") — voice input is the system keyboard's mic key, which appears
   with our search field (visible in capture 11). Documented as the path.
11. Icons swapped to Material outlined set (Apache-2.0, fetched from
   google/material-design-icons; ic_cast needed fillType=evenOdd), custom
   headset/quad kept.
Captures 12-14. Version 0.3.3/vc15 sideloaded (replaces 0.3.2). Channel
still 0.3.0/vc12.

## Status 16:19 (Codex) — 0.3.5/vc17: Meta-style cleanup + river panorama (local)

Reviewed the supplied 13:55 Horizon OS capture and compared the native App
Library/Quick Settings states against fresh MetaCam captures from this app.
The existing 0.3.3 build was stable but visually read as a large empty black
slab with duplicate branding, visible −/+ controls, and placeholder low-poly
terrain.

1. Replaced the visible procedural `nature.glb` world with
   `drawable-nodpi/skydome_river_valley.jpg`: a 4096×2048, 1.3 MB baked
   equirectangular river/conifer/mountain environment generated from the
   supplied visual direction. `nature.glb` remains in the project but is not
   instantiated. The result tracks the CGTrader reference without attempting
   to run its 8M-poly/1.5 GB Blender scene on Quest.
2. Removed the duplicate OpenPanel logo from the library header; the UI now
   uses only the `Library` title plus the subtle transparent quad in the
   compact system bar. Boot mark reduced from 82 dp to 48 dp.
3. Removed header −/+ reset buttons. Added a 36 dp bottom-right diagonal
   corner grip using Compose drag gestures. Scaling is continuous, clamped
   0.70–1.35, and written to preferences only at drag completion.
4. Tightened the physical library size to 1.40 m at the preserved 1840:1000
   aspect. This is the visually accepted on-headset size for the current
   one-app ArborXR catalog while the adaptive grid still supports growth.
5. Version bumped through 0.3.4/vc16 during the first hardware pass, then to
   final 0.3.5/vc17 after the on-device size correction.

Verification on Quest 3S `340YC10G8D180B`: 23/23 tests, lint, and debug APK
assembly passed; MetaCam captures show both passthrough and immersive states;
display-91 corner drag persisted 1.0012→0.8992→1.0019; YouTube tile launched
`com.google.android.apps.youtube.vr.oculus/...YouTubeVrActivity`; runtime held
89–91 FPS after resume with no fatal exception. Final evidence is in
`audits/quest-2026-08-21-v0.3.5-river-valley/`. Debug artifact:
`../artifacts/openpanel-vr-0.3.5-meta-quest-debug.apk`, SHA-256
`9bc99f1b618247d69559dddd6e8c90e3818ce803c5242f7c8bbd6e26b5dee58f`.
Installed locally only; ArborXR channel/policy was not changed.

## Status 16:31 (Codex) — 0.3.6/vc18: repaired native casting route (local)

Reproduced why the 0.3.5 Cast control appeared inert. The
`com.oculus.vrshell.intent.action.LAUNCH` systemux route is a receiver rather
than an activity on this headset, and the explicit MetaCam
`SharingDialogActivityAlias` created a dialog with a null action, logged
`Unexpected null action received`, and closed. Replaced both cast paths with
the exported native entry point
`com.oculus.metacam/com.oculus.panelapp.sharing.SharingPanelActivity` using
`MAIN` + `LAUNCHER`. Capability visibility now reflects actual component
availability and launch failure is user-visible.

Verified from OpenPanel on Quest 3S `340YC10G8D180B`: the native Sharing panel
opened, its Cast tile reached “Cast from this headset,” MetaCam initialized
receiver discovery, and Android registered `_googlecast._tcp.local`. This
proves Horizon's native implementation already discovers compatible Google
Cast endpoints; no Google Cast SDK was added. OpenXR has no casting API. A
future custom full-headset streamer would use Meta's supported
MediaProjection compositor Surface plus MediaCodec/audio capture and a real
transport/receiver pipeline. Tests, lint, and assembly passed. Evidence:
`audits/quest-2026-08-21-v0.3.6-casting/`. Artifact:
`../artifacts/openpanel-vr-0.3.6-meta-quest-debug.apk`, SHA-256
`9c17a6ed1660f7704a3638d74190f4281fc3cef3a1efc8c41305c3889a0d3352`.
Installed locally only; ArborXR channel/policy unchanged.

## Status 22:33 (Codex) — 0.3.16/vc28: water/panel sharpness pass (local)

Built/tested `versionCode=28`, `versionName=0.3.16`; installed to headset
`340YC10G8D180B` via ADB only (`installerPackageName=null`). APK artifact:
`../artifacts/openpanel-vr-0.3.16-meta-quest-debug.apk`, SHA-256
`2f5cc92fd1e3577bfd1cfb25410442211510e9f5edc418fffd46de0c19fcec8e`.
Audit evidence: `../audits/quest-2026-08-21-v0.3.16-water-panels-30hz/`.

Environment: kept stable 8192x4096 Poly Haven Lakeside Dawn skybox; 12K
single-skybox path remains rejected due Quest 3S launch instability. Added a
generated 4096x2048 mirrored open-water tile mapped to oversized low-opacity
water planes so edges are outside the lower field. Earlier close-shore crop
was rejected because rocks/branches plus plane edge read as a floating
platform.

UI: immersive panels are now near-opaque graphite to reduce compositor
dither/moire from translucent dark sheets over the bright skybox. Water UV
updates are 30 Hz (`WaterMotion.UPDATE_INTERVAL_MS=33L`).

Perf caveat: thermal status was 0 during the sample, but `top` still showed
~59–64% CPU and `meminfo` showed `TOTAL PSS: 642700 KB`,
`Graphics: 444794 KB`. Treat heat/perf as still open before ArborXR promotion.

## Status 22:08 (Codex) — 0.3.11/vc23: Horizon-density panel + stable 8K lakeside dome (local)

Compared the OpenPanel headset capture against the measured Meta/Horizon
references from Quest 3S `340YC10G8D180B`. Device display is 3664×1920 at
200 density / ~320 physical dpi; OpenPanel's previous main panel was far below
Meta's observed 1840×1000 app-library texture budget. Raised the compact
library and bar virtual-display budgets to 1.45 and 1.35 resolution scale:
verified on device as `1824×991` for the library and `776×90` for the bar.

Shifted the panel palette from near-black opaque graphite to lighter translucent
graphite to reduce capture/compositor dot-grid contrast. The large flat panel
still shows the Quest capture/display sampling pattern, but text and tile art
now render at Horizon-class density while the panel remains compact.

Regenerated the active environment as `skydome_lakeside_dawn_v11.jpg` from
Poly Haven Lakeside Dawn at 8192×4096, 4:4:4 JPEG, quality 95 with mild
unsharp. Tested a 12288×6144 package target and the 16384×8192 raw HDR source;
12K made the Spatial SDK launch path unstable/ANR-prone on the target headset,
and local HDR tonemapping produced worse LDR color than Poly Haven's official
tonemapped JPG. Removed obsolete packaged fallback sky resources so the APK
ships only the active lakeside dome plus the ripple texture. `nature.glb`
remains outside packaged Android assets.

Fixed test drift: casting tests now cover the direct MetaCam cast dialog plus
native sharing-panel fallback, and `WaterMotion` now actually clamps offsets
to its declared max. Verified `testDebugUnitTest` and `assembleDebug`, installed
locally to Quest 3S `340YC10G8D180B`, confirmed package `0.3.11` / vc23,
no packaged `nature.glb`, active panel displays present, and no post-launch
fatal/ANR in the checked log tail. Artifact:
`../artifacts/openpanel-vr-0.3.11-meta-quest-debug.apk`, SHA-256
`0862c4dda2860fdb769a6eed3f59b4bf18d4ebc9c672ca8fdc00b46`.

Installed locally only with `adb install -r`; ArborXR channel/policy unchanged.

## Status 16:47 (Codex) — 0.3.7/vc19: full ADB function audit + settings fix (local)

Ran a complete pre/post-fix hardware matrix on Quest 3S `340YC10G8D180B`.
The single reproducible implementation defect beyond the already repaired
Cast route was Wi-Fi/Bluetooth's dead first hop: both tried to start
`com.oculus.vrshell.intent.action.LAUNCH` as an activity, but that action is
owned by vrshell's broadcast receiver on this OS. Every tap threw
`ActivityNotFoundException` before the working Android-settings fallback.
Removed the dead SystemUX path; introduced tested `HorizonSystemSettings`
routes for Wi-Fi, Bluetooth, settings root, and date/time. Post-fix Wi-Fi and
Bluetooth reach Horizon `SettingsActivity` directly with no routing error.

Retested cold start, home, immersive/passthrough round-trip, Wi-Fi,
Bluetooth, Cast panel + “Cast from this headset” + `_googlecast._tcp.local`
discovery, settings, date/time, live battery, search keyboard/no-match/reset,
one-app ArborXR catalog, YouTube VR launch, corner resize persistence, pose
state restoration, and ambience pause/resume. All passed. Meta AI is
correctly hidden because social is disabled. A receiver was not selected, so
no external cast session was started. ADB cannot validate physical grabbing,
hand/controller ray comfort, or binocular comfort.

28/28 unit tests, lint, and assembly pass. Cold launch: 862 ms. No OpenPanel
fatal exception or ANR. Evidence:
`audits/quest-2026-08-21-v0.3.7-full-function/`. Artifact:
`../artifacts/openpanel-vr-0.3.7-meta-quest-debug.apk`, SHA-256
`fd6e9cbbc9fec07a6cc28165dbd530c6ae4b7ac38403e41d9c005bffd808e81b`.
Installed locally only; ArborXR channel/policy unchanged.

## Status 17:33 (Codex) — 0.3.8/vc20: lossless 6K valley + animated river (local)

Traced the visible background crawl to the original asset pipeline: the
1774×887 lossless ImageGen source had been enlarged to a 4096×2048,
quality-88, 4:2:0 JPEG. Rebuilt the preserved river-valley composition as a
6144×3072 lossless WebP after restrained micro-noise suppression and Lanczos
resampling. Added `scripts/generate-river-environment.sh`, retained the source
under `scripts/sources/`, and documented the pipeline.

Implemented Quest-safe water movement with two fixed translucent scene planes
and an authored 1024×1024 alpha highlight map. Two independent UV animations
run at 20 Hz with offsets bounded to 0.065 texture units; the world, horizon,
and river geometry do not slide. Motion pauses outside immersive foreground
state. A runtime sample confirmed independent nonzero near/far UV values.

Verified on Quest 3S `340YC10G8D180B`: 33/33 tests, lint, and assembly pass;
0.3.8/vc20 installed in place; no fatal exception, ANR, or OOM; steady
90–91/90 FPS, 0 stale frames, 1.47–1.76 ms app time, and ~381–386 MiB settled
PSS. User panel poses, 1.188734 scale, one-app ArborXR assignment, and
immersive preference remained intact. Evidence:
`audits/quest-2026-08-21-v0.3.8-river-motion/`. Artifact:
`../artifacts/openpanel-vr-0.3.8-meta-quest-debug.apk`, SHA-256
`f1082c71971fe2ab1a92714e8aa9dfa23f31ad7f9756f473d1fc8c8a0cd2c519`.
Installed locally only; ArborXR channel/policy unchanged.

## Latest status pointer (Codex, 2026-08-21 22:33)

The current local headset build is `0.3.16` / vc28. See the full handoff block
above titled `Status 22:33 (Codex) — 0.3.16/vc28: water/panel sharpness pass
(local)` and audit folder
`../audits/quest-2026-08-21-v0.3.16-water-panels-30hz/`.

## Status 23:04 (Codex) — 0.3.21/vc33: high-density horizon band + 4K skybox fallback (local)

Built/tested `versionCode=33`, `versionName=0.3.21`; installed to Quest
`340YC10G8D180B` via ADB only (`installerPackageName=null`). APK artifact:
`../artifacts/openpanel-vr-0.3.21-meta-quest-debug.apk`, SHA-256
`db4e4daf5be2372bd3f8b8f67349126669b070ff235ed2ab61ef634aea5cf82f`.
Audit evidence:
`../audits/quest-2026-08-21-v0.3.21-hq-horizon-4k-skybox/`.

Environment changed from one full 8K skybox plus animated rectangular water
planes to a layered path:
- active forward world detail is `app/src/main/assets/horizon_band_lakeside_v1.glb`,
  a 170° curved unlit GLB mesh with a 6144x3072 embedded JPEG;
- the GLB generator is `scripts/generate-horizon-band-glb.py`; when the local
  16K Poly Haven HDR exists, it uses that only as a luminance-detail plate and
  preserves the official 8K tonemapped JPG as the color reference;
- the packaged skybox is now `skydome_lakeside_dawn_fallback_v1.jpg`
  at 4096x2048, used as fallback outside the forward band;
- the old packaged `skydome_lakeside_dawn_v11.jpg` was removed from
  `res/drawable-nodpi`; the full official 8K source remains at
  `scripts/sources/skydome_lakeside_dawn_polyhaven.jpg`;
- `ENABLE_ANIMATED_WATER_OVERLAY=false` because captures showed the flat water
  planes either filled the foreground with stretched texture or exposed a slab
  edge. The active water view is static until a proper faded mesh/shader water
  layer is implemented.

Verified on-device: GLB loaded from `apk:///horizon_band_lakeside_v1.glb`;
no filtered fatal/ANR/OOM/crash lines. Display metrics still match target:
built-in 3664x1920 at 90 Hz, ~319.8x320.8 physical dpi; OpenPanel library
panel 1824x991 density 417; system bar 776x90 density 388.

Perf improved versus 0.3.16 and the failed 0.3.18/0.3.19 water-plane passes:
`top` samples were ~44–46% CPU, `TOTAL PSS: 466310 KB`, `TOTAL RSS:
617840 KB`, thermal status 0. This is still not ArborXR-release-ready if the
bar is high sustained-idle efficiency, but it is the current best visual/perf
checkpoint. Installed locally only; ArborXR channel/policy unchanged.

## Latest status pointer (Codex, 2026-08-21 23:04)

The current local headset build is `0.3.21` / vc33. See the full handoff block
above titled `Status 23:04 (Codex) — 0.3.21/vc33: high-density horizon band +
4K skybox fallback (local)` and audit folder
`../audits/quest-2026-08-21-v0.3.21-hq-horizon-4k-skybox/`.

## Status 23:15 (Codex) — 0.3.22/vc34: 8K/160° horizon-band pixel-density audit (local)

Built/tested `versionCode=34`, `versionName=0.3.22`; installed to Quest
`340YC10G8D180B` via ADB only (`installerPackageName=null`). APK artifact:
`../artifacts/openpanel-vr-0.3.22-meta-quest-debug.apk`, SHA-256
`b1b1a62899af29501a3981c0ff7a02cb3a0e5f8e66f6a596ece0a0f4e6cc4055`.
Audit evidence:
`../audits/quest-2026-08-21-v0.3.22-hq8k-band-160/`.

Environment update: regenerated `app/src/main/assets/horizon_band_lakeside_v1.glb`
as an 8192x4096 embedded JPEG over a 160° forward curved mesh. This raises
the theoretical forward angular density to ~51.2 px/degree versus 0.3.21's
6144/170° (~36.1 px/degree), the original 8K/360° skybox (~22.8 px/degree),
and the packaged 4K fallback skybox (~11.4 px/degree). Source remains Poly
Haven Lakeside Dawn (CC0/public-domain); the local 16K HDR was used only as a
luminance-detail plate, not as a committed/package raw asset.

MetaOS comparison: captured live `com.oculus.vrshell/.HomeActivity` raw stereo
at 3664x1920 and package/display metadata for `com.oculus.panelapp.library`
and `com.oculus.panelapp.settings`. Do not copy or extract proprietary Meta
assets. This pass used screenshot output plus package/display metadata only.
The current Meta panel activity launch attempts did not expose a useful
library panel state, so the audit retains the prior high-detail forest/river
reference screenshot for visual direction.

Verdict: 0.3.22 is stable and is the highest texture-density checkpoint, but
it is not a decisive visual jump over 0.3.21 in MetaCam. Raw stereo screencap
is sharper than the 1440 MetaCam JPEG, but the world still reads as mostly a
flat panorama. The next quality jump should be a real 3D foreground/water pass
(licensed/CC0 terrain mesh, faded water mesh or shader with animated
normals/UVs), not simply a larger monolithic skybox. Keep the 12K/16K skybox
path rejected for Quest 3S launch stability unless a new engine path changes
the memory behavior.

Runtime: no filtered fatal/ANR/OOM/crash lines; GLB loaded from
`apk:///horizon_band_lakeside_v1.glb`; thermal status 0. CPU samples rose to
~45–55%, `TOTAL PSS: 538527 KB`, `TOTAL RSS: 695772 KB`, which is worse than
0.3.21's ~44–46% CPU and 466310 KB PSS. Treat 0.3.21 as the better efficiency
baseline and 0.3.22 as the high-res visual checkpoint. Installed locally only;
ArborXR channel/policy unchanged.

## Latest status pointer (Codex, 2026-08-21 23:15)

The current local headset build is `0.3.22` / vc34. See the full handoff block
above titled `Status 23:15 (Codex) — 0.3.22/vc34: 8K/160° horizon-band
pixel-density audit (local)` and audit folder
`../audits/quest-2026-08-21-v0.3.22-hq8k-band-160/`.

## Status 23:30 (Codex) — 0.3.24/vc36: subtle 15 Hz water shimmer (local)

Built/tested `versionCode=36`, `versionName=0.3.24`; installed to Quest
`340YC10G8D180B` via ADB only (`installerPackageName=null`). APK artifact:
`../artifacts/openpanel-vr-0.3.24-meta-quest-debug.apk`, SHA-256
`f2019da9555986f3a83318c70ba8bddc2a5b4d3229e5055188732513bd5e7ac7`.
Audit evidence:
`../audits/quest-2026-08-21-v0.3.24-water-shimmer-15hz/`.

This keeps the 0.3.22 8192x4096/160° horizon-band background and restores
water motion without the rejected JPG base-water slab. Added active
`res/drawable-nodpi/water_shimmer_fade_v1.png`: a 1024x1024 procedural RGBA
texture, edge-faded, alpha capped at 52/255, ~12% nonzero pixels. Runtime
uses two low-opacity shimmer planes, `ENABLE_ANIMATED_WATER_OVERLAY=true`,
`ENABLE_WATER_BASE_LAYER=false`, and `WaterMotion.UPDATE_INTERVAL_MS=66L`
(15 Hz).

Rejected intermediate: 0.3.23 proved the shimmer concept but sampled CPU at
~63–70% and PSS ~562 MB with 30 Hz updates and a 2048 texture. 0.3.24 reduced
that cost with a 1024 texture and 15 Hz updates. On-device evidence: clean
launch log emitted `River water motion active at 15 Hz`, a nonzero UV sample,
and GLB load for `apk:///horizon_band_lakeside_v1.glb`. MetaCam still did not
show a visible slab; raw-frame diff over four seconds showed subtle water-region
change. No OpenPanel fatal/ANR/OOM/crash in the checked launch window.

Perf caveat: 0.3.24 remains heavier than the static background checkpoints.
`top` samples were ~51.8–59.2% CPU, `TOTAL PSS: 548148 KB`, `TOTAL RSS:
705020 KB`, thermal status 0. 0.3.21/0.3.22 remain the better efficiency
baselines. 0.3.24 is the current installed checkpoint only because it addresses
the user's “water is not animated” complaint without an obvious slab.

Next real visual-quality step is not more panorama pixels; it is a Quest-safe
3D foreground/water scene: licensed/CC0 shoreline rocks/grass/terrain, baked
mobile textures, proper faded water mesh/shader, and low material count.
Installed locally only; ArborXR channel/policy unchanged.

## Latest status pointer (Codex, 2026-08-21 23:30)

The current local headset build is `0.3.24` / vc36. See the full handoff block
above titled `Status 23:30 (Codex) — 0.3.24/vc36: subtle 15 Hz water shimmer
(local)` and audit folder
`../audits/quest-2026-08-21-v0.3.24-water-shimmer-15hz/`.

## Status 23:58 (Codex) — 0.3.30/vc42: lowered alpha-masked shoreline foreground (local)

Built/tested `versionCode=42`, `versionName=0.3.30`; installed to Quest
`340YC10G8D180B` via ADB only (`installerPackageName=null`). APK artifact:
`../artifacts/openpanel-vr-0.3.30-meta-quest-debug.apk`, SHA-256
`fdbd9918cef9d22887183c2a3149bffe6db3c04eba141e778457ab72b598ce97`.
Audit evidence:
`../audits/quest-2026-08-21-v0.3.30-textured-shoreline-lowered/`.

This keeps the 0.3.22 8192x4096/160° high-density horizon band and the 0.3.24
15 Hz transparent water shimmer. It adds the first headset-accepted foreground
detail layer: `app/src/main/assets/lakeside_foreground_v2.glb`, generated by
`scripts/generate-textured-shoreline-glb.py` from Poly Haven Coast Sand Rocks
02 (CC0/public-domain). The v2 GLB embeds an edge-masked 2K PNG on a small
112-vertex uneven shore mesh and is lowered at runtime by
`FOREGROUND_Y_OFFSET_METERS = -0.58f`.

Rejected intermediate notes: 0.3.25/0.3.26 v1 procedural foreground looked like
floating rocks/reeds; 0.3.27 v1 double-sided terrain read as a large flat slab;
0.3.29 v2 alpha blending worked but the texture was too high/dark under the
panel. 0.3.30 lightened/reduced alpha and lowered the foreground, removing the
hard band in MetaCam and raw stereo captures.

On-device evidence: clean launch log emitted `River water motion active at
15 Hz`, a nonzero UV motion sample, GLB load for
`apk:///lakeside_foreground_v2.glb`, and GLB load for
`apk:///horizon_band_lakeside_v1.glb`. No filtered OpenPanel fatal/ANR/OOM/crash
lines in the checked launch window. Raw stereo capture is 3664x1920; MetaCam
capture is 1440x1440.

Perf caveat: this visual checkpoint remains heavier than the static-only
baselines. `top` samples were ~51.8–62.9% CPU, `TOTAL PSS: 570698 KB`,
`TOTAL RSS: 727808 KB`, thermal status 0. Use 0.3.21/0.3.22 as efficiency
references and 0.3.30 as the current best visual checkpoint.

Installed locally only; ArborXR channel/policy unchanged.

ArborXR read-only check after the ADB install: the organization has an
`OpenPanel VR` app entry (`5f31215c-5998-4735-9b1f-302520d95050`) for
`com.orgista.openpanel.quest`, but its Beta release channel is still pinned to
uploaded build `0.3.0` / vc12 and install count is 0. The device-reported
installed package inventory sees local `0.3.30` / vc42 and the device record
shows OpenPanel VR Local as the running app, but `list_deployables_for_device`
does not include OpenPanel VR. The only non-system managed app deployment on
this headset is YouTube. Resolved `headset_experience` is `device-default`
with Store/Social/Add Accounts enabled, so the visible app list is coming from
managed content deployment, not a custom locked headset-experience whitelist.

## Latest status pointer (Codex, 2026-08-21 23:58)

The current local headset build is `0.3.30` / vc42. See the full handoff block
above titled `Status 23:58 (Codex) — 0.3.30/vc42: lowered alpha-masked
shoreline foreground (local)` and audit folder
`../audits/quest-2026-08-21-v0.3.30-textured-shoreline-lowered/`.

## Status 00:15 (Codex) — 0.3.31/vc43: 4K shoreline + sharpened 8K horizon (local)

Built/tested `versionCode=43`, `versionName=0.3.31`; installed to Quest
`340YC10G8D180B` via ADB only (`installerPackageName=null`). APK artifact:
`../artifacts/openpanel-vr-0.3.31-meta-quest-debug.apk`, SHA-256
`e79c2d745fa8432cce493f0106f0bda0370d8355cc5bedee046abc40ae90105a`.
Audit evidence:
`../audits/quest-2026-08-22-v0.3.31-4k-shoreline-sharpened-horizon/`.

Pixel-density audit: fresh `dumpsys display` still reports raw stereo
`3664x1920`, nominal per-eye `1832x1920`, physical display density
`319.813 x 320.842 dpi`, active 90 Hz. OpenPanel virtual displays remain
`1824x991` density 417 for the library panel and `776x90` density 388 for the
bar. The active 8192x4096/160° forward horizon band is about 51.2 texels/degree
versus 22.8 texels/degree for a full 8K equirectangular skybox and 11.4
texels/degree for the packaged 4K fallback. Current evidence says the remaining
perceived sharpness gap is mostly source/detail/geometry, not just raw skybox
pixel count.

Asset changes: regenerated `horizon_band_lakeside_v1.glb` with JPEG quality 98
and mild unsharp mask; regenerated the foreground as
`lakeside_foreground_v3.glb` from Poly Haven Coast Sand Rocks 02 4K diffuse.
`OpenPanelActivity` now loads `lakeside_foreground_v3.glb` at
`FOREGROUND_Y_OFFSET_METERS = -0.58f`. Removed the unused packaged
`lakeside_foreground_v2.glb`; 0.3.30 artifact/audit preserves that checkpoint.
Water shimmer remains active at 15 Hz and the base water slab remains disabled.

On-device evidence: clean launch log emitted `River water motion active at
15 Hz`, a nonzero UV motion sample, GLB load for
`apk:///lakeside_foreground_v3.glb`, and GLB load for
`apk:///horizon_band_lakeside_v1.glb`. Visual check: no hard foreground slab;
shoreline/branch detail stays under the panel and does not dominate the UI.
Raw stereo capture is 3664x1920; MetaCam capture is 1440x1440.

Perf caveat: APK grew to 127 MB and memory rose to `TOTAL PSS: 635337 KB`,
`TOTAL RSS: 793020 KB`. CPU samples stayed similar at ~51.8–59.2% and thermal
status remained 0. Treat 0.3.31 as the current best visual/sharpness checkpoint
and 0.3.30 as the lower-memory visual checkpoint.

ArborXR read-only check after the ADB install: device-reported package inventory
sees local `0.3.31` / vc43 for `com.orgista.openpanel.quest`, but this is still
not an ArborXR-managed deployment. The connector available here cannot upload
the APK, repin release channels, or assign managed content.

Important next step: do not keep chasing monolithic skybox size. The next real
MetaOS/Apple Vision Pro-style quality jump should be a purpose-built Quest-safe
3D environment/foreground: authored shoreline/floor/water geometry, mobile-baked
textures, low material count, and no proprietary Meta assets. A prior local
asset inventory found `/Volumes/Files/Projects/Code, Apps & Websites/pine_forest`
with Poly Haven forest `.blend`/`.glb` sources and textures, but those raw GLBs
are ~1.6 GB and are not Quest-ready without a separate optimization/baking pass.

## Latest status pointer (Codex, 2026-08-22 00:15)

The current local headset build is `0.3.31` / vc43. See the full handoff block
above titled `Status 00:15 (Codex) — 0.3.31/vc43: 4K shoreline + sharpened 8K
horizon (local)` and audit folder
`../audits/quest-2026-08-22-v0.3.31-4k-shoreline-sharpened-horizon/`.

## Status 00:25 (Codex) — 0.3.32/vc44: displaced 4K shoreline foreground (local)

Built/tested `versionCode=44`, `versionName=0.3.32`; installed to Quest
`340YC10G8D180B` via ADB only (`installerPackageName=null`). APK artifact:
`../artifacts/openpanel-vr-0.3.32-meta-quest-debug.apk`, SHA-256
`5572c5b54376220b911cb213b474c4ba7fb871e25ecef1672b6dedb3b43e0763`.
Audit evidence:
`../audits/quest-2026-08-22-v0.3.32-v4-displaced-shore-final/`.

Asset changes: `OpenPanelActivity` now loads `lakeside_foreground_v4.glb`.
The v4 foreground keeps the 4K Poly Haven Coast Sand Rocks 02 texture from
0.3.31, but replaces the 112-vertex mostly flat mesh with a 73x30 displaced
grid: 2,190 vertices / 12,528 indices, subtle height variation, and
triangle-averaged normals. This is a geometry/depth pass, not another skybox
resolution bump. Removed unused packaged `lakeside_foreground_v3.glb`; 0.3.31
artifact/audit preserves that checkpoint.

Visual verdict: accepted as a conservative improvement. It does not reintroduce
the rejected hard foreground slab/band, remains below the launcher panel, and
gives the foreground a better base for real lighting/material work. The visible
change is subtle in MetaCam because the source is still a photographic
water/shore composite; further quality gains need authored scene geometry.

On-device evidence: clean launch log emitted `River water motion active at
15 Hz`, a nonzero UV motion sample, GLB load for
`apk:///lakeside_foreground_v4.glb`, and GLB load for
`apk:///horizon_band_lakeside_v1.glb`. No filtered OpenPanel fatal/ANR/OOM/crash
lines in the checked launch window. Raw stereo capture is 3664x1920; MetaCam
capture is 1440x1440.

Perf: final optimized APK is 133,318,835 bytes (~127 MB). `TOTAL PSS: 635316
KB`, `TOTAL RSS: 792904 KB`, CPU samples ~50.0–62.9%, thermal status 0. This
is effectively the same runtime memory class as 0.3.31 and heavier than 0.3.30.
Treat 0.3.32 as the current geometry/sharpness checkpoint and 0.3.30 as the
lower-memory visual checkpoint.

ArborXR read-only check after the ADB install: device-reported package inventory
sees local `0.3.32` / vc44 for `com.orgista.openpanel.quest`, but this is still
not an ArborXR-managed deployment. The connector available here cannot upload
the APK, repin release channels, or assign managed content.

Next major step remains a proper authored/baked Quest environment: real
near-field floor/shore/water geometry, mobile-compressed textures, low material
count, and no proprietary Meta assets. The local
`/Volumes/Files/Projects/Code, Apps & Websites/pine_forest` Poly Haven asset
set is a useful reference/source inventory, but its raw GLBs are not
Quest-ready without a dedicated optimization pass.

## Latest status pointer (Codex, 2026-08-22 00:25)

The current local headset build is `0.3.32` / vc44. See the full handoff block
above titled `Status 00:25 (Codex) — 0.3.32/vc44: displaced 4K shoreline
foreground (local)` and audit folder
`../audits/quest-2026-08-22-v0.3.32-v4-displaced-shore-final/`.
## 2026-08-22 00:39 — 0.3.34/vc46 installed on Quest 3S 340YC10G8D180B

Built/tested `versionCode=46`, `versionName=0.3.34`; installed to Quest
`340YC10G8D180B` via ADB. Final artifact:
`/Volumes/Files/Projects/Code, Apps & Websites/Apps/OpenPanel/artifacts/openpanel-vr-0.3.34-meta-quest-debug.apk`
with SHA-256
`557d0786a649e370d4df6311d823d6d3a9b16350032a0538203d9d34add250f9`.

Visual/background changes: runtime fallback skybox is now 8192x4096 instead of
4096x2048. `horizon_band_lakeside_v1.glb` was regenerated after downloading
Poly Haven's local `scripts/sources/lakeside_dawn_16k.hdr` input; the generator
uses that 16K HDR as a luminance-detail plate and reduced artificial sharpening
to avoid the dot/line artifacts from previous over-sharpening. The water
shimmer texture is now deterministic 2048x2048 procedural RGBA, with smoother
wide highlights and modestly higher material alpha. The opaque JPG water base
plane remains disabled to avoid the rectangular slab.

Casting fix: `HorizonCasting.preferredRoutes` now uses the stable native Sharing
panel first (`com.oculus.metacam/com.oculus.panelapp.sharing.SharingPanelActivity`).
The direct `START_CASTING` alias was tested on this firmware and starts, but
immediately closes with `Unexpected null action received`, so it is kept only as
a fallback if the native sharing panel disappears on another firmware.

Verification archive:
`/Volumes/Files/Projects/Code, Apps & Websites/Apps/OpenPanel/audits/quest-2026-08-22-v0.3.34-16k-hdr-horizon-8k-skybox-water-castfix/`.
Gradle `testDebugUnitTest assembleDebug` passed; ADB install succeeded; package
dump confirms 0.3.34/vc46. Logcat confirms water loop at 15 Hz, UV sample, and
GLB loads for `lakeside_foreground_v4.glb` and `horizon_band_lakeside_v1.glb`.
No filtered OpenPanel fatal/ANR/OOM/app crash in the checked launch window.
Thermal status 0. PSS is ~770,962 KB, RSS ~928,716 KB; this is intentionally
higher than 0.3.32 because of the 8K full skybox decode. CPU samples stayed
around 48.1–55.5 in `top`.

ArborXR read-only check after sideload: `list_installed_packages` reports
`com.orgista.openpanel.quest` on device `e7789c53-59bd-4209-9a06-f33549a8b177`
(`Quest 3S`, serial `340YC10G8D180B`) as `versionName=0.3.34`,
`versionCode=46`, `isLaunchable=true`, updated at `2026-08-22T05:37:34Z`.
This is still NOT an ArborXR-managed deployment: OpenPanel VR app
`5f31215c-5998-4735-9b1f-302520d95050` only has uploaded build `0.3.0`/vc12
pinned to Beta, and `list_deployables_for_device` for this headset includes
Client, Home, OEMConfig, and YouTube but not OpenPanel VR. Need a write-capable
ArborXR upload/deploy path or portal action to publish `0.3.34` through the
channel.

## CLAIM 22:20 (Claude) — Meta-native panel fidelity + SDK adoption in flight

I see fresh edits (build.gradle.kts bumped 0.3.36/vc48 at 22:17; panel files
21:38–22:08) with no close-out yet — assuming Codex is mid-cycle. Claiming the
following so we don't cross-edit; ping here if you want a different split.

Approved by Anthony this evening (full transcript in my session):
1. **Panel fidelity**: compositor-layer panels (`UIPanelRenderOptions(
   PanelRenderMode.Layer(ALPHA_BLEND, feathered, LayerFilters.AUTO_FILTER))`),
   Meta token colors from uiset (panel = opaque #414141→#272727 vertical
   gradient via `SpatialTheme.colorScheme.panel`; pills = white10/hover
   white20; active = RLDSWhite100 #F1F4F7 w/ #272727 content), and icon swap
   to licensed `SpatialIcons.Regular.*` (WifiOn/Bluetooth/HeadsetCasting/
   Settings/Search/Environment/CategoryAll). Files: OpenPanel.kt,
   OpenPanelActivity.kt (composePanel + feature list), build.gradle.kts.
   Verified facts: tokens/API extracted from cached 0.13.2 AARs — dark scheme
   maps panel→RLDSpanelBlackGradient(Top #FF414141/Bottom #FF272727),
   secondaryButton→white10, hover/pressed→white20, primaryButton→RLDSWhite100,
   active→b50 #47A5FA. LayerFilters exposes AUTO_FILTER/…_SHARPENING/
   …_SUPER_SAMPLING (OpenXR composition-layer-settings). The visible dot grid
   = mesh-mode double sampling + dither on translucent darks; fix = layers +
   opaque Meta surfaces.
2. **ISDK in-place resize** (0.13.2): IsdkFeature(this, spatial, systemManager)
   + IsdkPanelResize(Simple, preserveAspectRatio, min/max 0.7–1.35×) on the
   library panel; persist resulting Scale on pause. isdk becomes a direct dep.
3. **MRUK**: dep + USE_ANCHOR_API/USE_SCENE permissions + MRUKFeature +
   loadSceneFromDevice on grant (one-shot permission ask, pref-tracked).
4. **Recency sort**: AppDiscovery.selectLaunchableApps gains
   recentLaunches: Map<String,Long>; launcher records launches; recent-first
   then existing VR/label order. + unit tests. (AppDiscovery.kt lane is mine
   this round.)
5. **devTools gate**: hotreload/castinputforward/datamodelinspector/ovrmetrics
   return behind `-PdevTools` (never in default builds).
6. **quest/scripts/gradle-local.sh**: permanent SMB-safe build wrapper
   (JDK 21 check, local GRADLE_USER_HOME + project cache, --no-watch-fs,
   init-script buildDir redirect, copies APK back to app/build/outputs).
7. Version: I will bump to **0.3.37/vc49** for my build after the tree goes
   quiet ≥5 min. If you are mid-sideload of vc48, finish; I install -r over it.

Non-goals this round: ArborXR upload/portal actions (still awaiting Anthony's
"send it"), pak packaging (evaluating only), Canvas Panel (superseded by layer
rendering for 2D panels).

## CLOSE-OUT 22:55 (Claude) — 0.3.38/vc50 shipped locally: Meta-native panels + SDK adoption, verified

Everything from my 22:20 claim landed, built green (tests + assembleDebug via
the new `scripts/gradle-local.sh`), sideloaded to `340YC10G8D180B`, and
verified by tap-pass + captures. Full evidence:
`audits/quest-2026-08-22-v0.3.38-meta-native-panels/README.md`. Artifact:
`../artifacts/openpanel-vr-0.3.38-meta-quest-debug.apk`, SHA-256
`0678a25d43e6888a050a3ad1d676b0db27e1e34505833a54deb1677f50fb8f7f`.

Highlights + new verified facts (don't re-derive):
- Panels are compositor quad layers now (`UIPanelRenderOptions(PanelRenderMode
  .Layer(ALPHA_BLEND, feathered, LayerFilters.AUTO_FILTER))`). Input through
  layer panels works (tile tap → YouTube xrBeginSession). Budgets unchanged
  (library 1824×991@417, bar 776×90@388).
- Colors are Meta's real RLDS tokens via `SpatialTheme.colorScheme` (panel =
  opaque #414141→#272727 gradient; white10 pills, white20 hover, white pill
  active). The old warm-gray + dot grid is gone (see before/after captures).
- Icons are Meta's licensed UI Set icons (`SpatialIcons.Regular.*`).
  material-icons-core dep dropped.
- **0.13.2 deprecates manual IsdkFeature registration — VRFeature auto-registers
  ISDK.** Only the `IsdkPanelResize` component is needed for native corner
  resize (attached to the library panel, 0.70–1.35×). Corner-pinch needs a
  human wear test; adb can't pinch.
- MRUK is live: dep + permissions + MRUKFeature + loadSceneFromDevice. This
  headset has NO Space Setup data ("0 anchors", rooms=0) so the graceful
  fallback path is what's verified. Run Space Setup on the headset to light up
  scene understanding; USE_SCENE can be pre-granted headless with `pm grant`.
- Recency-first library sort (RecentLaunchStore in prefs, cap 12) + tests.
- Dev tooling returns only with `-PdevTools` (never default).
- New op gotchas: launches get CACHED behind "Reprojected OS dialog" /
  "Guardian dialog" (check `dumpsys activity activities | grep
  topResumedActivity`); shutdown-confirm cleared with KEYCODE_BACK; guardian
  cleared with `setprop debug.oculus.guardian_pause 1` + force-stop guardian
  (I RESTORED it to 0 after verification). `pm grant` works for USE_SCENE.
- Perf idle: 51.8% one core, PSS 716 MB, thermal 0 — 0.3.34 class, no
  regression with MRUK aboard. APK 133→163 MB (MRUK + transitively packaged
  physics .so) — pak/asset diet is now the top size lever.

Deferred with rationale: pak packaging (spatial plugin's pak path needs a
dedicated pass; asset diet first), Canvas Panel API (for stereo/canvas
content — layer rendering supersedes it for our 2D panels), anchor-persisted
poses (needs Space Setup data on this device + observing which component ISDK
resize mutates), horizontal pagination + side rail expansion (catalog is 1–3
apps; revisit >24 per the earlier recommendation).

To Codex: your vc48/vc50 bumps landed mid-session with no AGENTS note — this
build ships as YOUR 0.3.38/vc50 with both streams merged (your pine-forest
env + my panel/SDK work). Note: capture 01 shows the pine-forest foreground
GLB posts intersecting the library panel + floating corrupted patches at the
sides — your lane, flagging for your next environment pass.

## Status 23:45 (Claude) — Anthony's 7-task environment/cast/audio round, mid-flight (0.3.39/vc51 local)

Landed + device-verified so far (sideloaded vc51 with a PROBE-quality placeholder
skybox; final 8K Cycles render in flight):
1. **Cast**: root-caused on-firmware — direct `START_CASTING` alias CRASHES
   Meta's SharingDialogActivity (ACRA in onDestroy); `systemux://sharing|casting`
   vrshell broadcasts deliver but show no 3P-visible UI; SharingPanelActivity
   ignores a START_CASTING action hint. Fix shipped: single reliable route
   (exported SharingPanelActivity) + **volumetric Toast feedback** ("Opening
   Horizon sharing — choose Cast from this headset."). Verified: in-app bar tap
   → metacam task appears + VolumetricToast window (TYPE_TOAST) renders
   over everything. The old status-field-only feedback was invisible outside
   the library empty state — that's why Cast read as dead. One-tap-starts-cast
   is not exposed to 3P on this firmware; wear test still recommended.
2. **Ambience**: now plays whenever OpenPanel is FOREGROUND (immersive AND
   passthrough; Anthony's ask). Verified via dumpsys audio: OpenSL player
   state=started, stays started across the env toggle. The Downloads m4a is
   byte-identical to res/raw/forest_river_birdsong_background_v1.m4a (SHA
   19e038e2…) — no file change needed. Dead `forest_ambience.m4a` deleted.
3. **Passthrough translucency (Meta OS behavior)**: panel + bar surfaces are
   the RLDS gradient at 0.72 alpha in passthrough, fully opaque in immersive.
   Verified toggle + capture (dark room, so judged by active-state + code;
   lit-room wear check pending).
4. **Panel edge leak**: `enableLayerFeatheredEdge = false` on both panels —
   the feathered layer edge softened the whole cutout boundary over the bright
   skybox. Corners stay AA'd in the Compose texture.
5. **Environment rebuild**: dome now points at `skydome_pine_forest_v2.jpg` —
   a Cycles 360 equirect of the actual Fir & Pine Forest .blend
   (../../pine_forest/polyhaven_pine_fir_forest.blend), camera standing at the
   river from Anthony's reference shot. DELETED: both fallback skydomes,
   horizon-band GLBs (lakeside + pine), all foreground GLBs (incl. the posts
   that cut through the panel), water base texture/code, river_ripples_v3,
   forest_ambience.m4a — all reference-checked first. Water shimmer planes
   repositioned onto the new river (near 9×12 m @ y −0.42 z −7.5; far 14×16 m
   @ y −0.38 z −17), still 15 Hz + foreground/immersive-gated.
6. Blender ops facts: Blender 5.1.2 SIGABRTs loading this 2023 .blend unless
   `--factory-startup` (addon/prefs versioning crash); render script at
   scripts/render-pine-forest-360.py (equirect PANO cam at the scene camera's
   spot, Metal GPU, OIDN denoise, adaptive 0.05); batch stdout is buffered —
   wrap in `script -q /dev/null` for live progress. Probe (1536×768@24smp)
   validated framing: river vista centered, ~6 min wall.

Pending in this round: final 8192×4096 render lands → replace placeholder,
rebuild (Codex: tree holds YOUR vc51 bump — I ship it), sideload, immersive
captures + perf snapshot, close-out. Tests: 27/27 green, assembleDebug green.

## CLOSE-OUT 00:45 (Claude) — 0.3.39/vc51 final: 8K Blender forest skybox live, all 7 tasks verified

The 23:45 mid-flight entry's pending half is done. Final 8192×4096 Cycles
render of the Fir & Pine Forest .blend (56smp adaptive + OIDN, ~50 min on
Metal) is in as `skydome_pine_forest_v2.jpg` and verified in-headset: river
underfoot flowing forward, sun through the pines, panels floating over the
water — matches Anthony's reference shot. Captures + full detail:
`audits/quest-2026-08-22-v0.3.39-env-cast-audio/README.md`. Artifact:
`../artifacts/openpanel-vr-0.3.39-meta-quest-debug.apk`
(SHA 5829de9d…, **115 MB — down 48 MB** after the asset sweep).

Final perf with the 8K decode: CPU 44.4%, PSS 513 MB (night started at
~771 MB), locked 90–91 FPS, ambience playing. vc51 reused for the
drawable-only reinstall; bump before any ArborXR upload.

New ops gotchas recorded in the audit README: --factory-startup for the 2023
.blend, script-wrapped stdout for Blender batch, logical-vs-SurfaceFlinger
display ids (input -d vs screencap -d), panel display ids change every app
restart.

Wear-test list for Anthony: corner-pinch resize, lit-room passthrough
translucency, cast panel visibility in-headset, ambience volume. Space Setup
has never been run on this headset — running it lights up MRUK.

## SHIPPED 01:25 (Claude) — 0.3.40/vc52: subtle reset-size control + ArborXR release

Anthony's go: "ship it to ArborXR app entry OpenPanelVR as the new version."

1. **Reset-size control**: a subtle round `ScaleDown` (Meta UI Set icon) button
   in the library header, composed ONLY while the window's scale has drifted
   >2% from authored size (`PanelLayout.isResizedScale`, unit-tested). A 700 ms
   foreground-only watcher syncs state with ISDK pinch-resize AND persists the
   latest scale, so pinch results now survive restarts; tapping resets scale
   to 1 and clears the pref. Verified on device with the pref as oracle: the
   headset carried a real legacy scale (1.188734) → launch → tap at the
   button's position on the library display → `library_scale` pref GONE
   (proves compose-gating, hit-target, handler, and clear all work). Visual
   check pending wear (headset is sensor-locked overnight; MetaCam shows the
   lock env, and screencap -d ignores virtual-display ids on this build).
2. **ArborXR**: uploaded via scripts/arborxr-cli-keychain.sh → app
   `5f31215c-5998-4735-9b1f-302520d95050` (OpenPanel VR), build
   `8fe5848b-3e63-4168-a096-b54c8a48d41c` = **0.3.40/vc52, status available,
   Beta (default) channel targets it, isLatest: true** (portal-verified via
   read-only MCP). Artifact: `../artifacts/openpanel-vr-0.3.40-meta-quest-debug.apk`,
   SHA-256 `ba1cb6c077e88a6f112a7bb25e178d52b0733c3c1c1f2eb172a90336e31f509e`
   (114 MB). Device already runs the same bits sideloaded (vc52).
3. **REMAINING PORTAL ACTION (Anthony)**: OpenPanel VR is still NOT assigned
   to the Quest 3S (deployables = Client/Home/OEMConfig/YouTube only — its
   assignment was dropped portal-side on 08-21). One portal click: assign app
   OpenPanel VR to the device/group so the MDM manages it. abxr-cli has no
   assignment commands and the MCP connector is read-only — verified again.

Tests 28/28 + assembleDebug green. Env/pose prefs preserved; recents intact.

## Status 02:05 (Claude) — 0.3.41/vc53 built: SystemUX overlay panels (cast/wifi/bt), device offline

Anthony: cast opens the CAMERA app (MetaCam sharing panel), not casting; ArborXR
Home opens cast/wifi/bt as small overlay panels without leaving the app.

Root cause + fix (evidence-based, official): Meta's documented **System Deep
Linking** contract — `packageManager.getLaunchIntentForPackage("com.oculus.vrshell")`
+ `putExtra("intent_data", "systemux://…")` (+ optional `uri` extra) opens
SystemUX panels as overlays OVER the running app. Our earlier attempts failed
because we used broadcasts/activity-starts with the wrong extra (`uri` instead
of `intent_data`) — and overlay panels are vrshell WINDOWS, not tasks, so
task-list checks can't see them. Verified URI inventory (community research +
Meta docs): systemux://sharing (system share sheet, Cast lives here — NOT
metacam), wifi, bluetooth, datetime, settings.

Shipped in-tree as 0.3.41/vc53 (tests 30/30, assembleDebug green):
- `SystemUx` constants + `overlayCommand` on SystemSettingsRoute;
  `openSystemUxOverlay()` in the activity.
- Cast: overlay sharing sheet FIRST, MetaCam SharingPanelActivity fallback,
  toast either way. Wi-Fi/BT/date-time/settings: overlay first, previous
  Android-settings activity fallback — worst case identical to 0.3.40.

BLOCKED on device: Quest 3S dropped off adb ~01:45 (unplugged/powered down —
possibly picked up for a wear test). Pending on reconnect: sideload vc53,
probe overlays via `scratchpad/probe-systemux.sh` pattern (check WINDOW dumps
+ SUI logs, not tasks), captures, then ArborXR upload on Anthony's go.
ArborXR still holds 0.3.40 (available/isLatest on Beta); assignment to the
device remains Anthony's portal click.

## CLOSE-OUT 09:40 (Claude) — 0.3.43/vc55: REAL water motion + SystemUX overlay panels, device-verified

Anthony's morning asks: (a) water doesn't move like the Blender tutorial,
(b) mine a second video (realistic-environments) for enhancements, (c) yt-dlp
them. Both transcripts pulled (video streams 403'd; full auto-captions got
the techniques verbatim → scratchpad yt/*.txt).

**Water (0.3.42)**: tutorial technique = two 4D-noise fields animated via W →
in-place undulation. Old impl was invisible by construction: offsets clamped
±0.14 w/ snap-wrap, texture alpha cap 64/255 × material 0.30 ≈ 7% effective.
New: WaterMotion = CONTINUOUS counter-drifting layers (near +u, far −u,
different temporal frequencies → interference undulation, modulo-aware no-snap,
~2× speed), texture regenerated at 112/255 (water_shimmer_fade_v2, v1 deleted),
material alpha 0.42. Live log shows near.u climbing while far.u wraps backward
✓. 36/36 tests incl. new no-snap + drift-rate assertions. MetaCam visual diff
BLOCKED by wear-standby (unworn headset = no immersive rendering; prox_close
did not bypass this morning) — Anthony's next wear is the 30-second check.

**Video 2 learnings** ("secret to realistic environments" = 3 layers:
scatter variation, shapes/sizes, MOVEMENT): movement is the make-or-break
layer — exactly converges with the water fix. Other applicable note: our
render already bakes layers 1-2; if we ever revisit near-field geometry, use
scatter-with-variation, never uniform props (the old floating-rocks failure).

**SystemUX overlays (0.3.43)**: firmware-verified via VrShell.apk dex dump —
this build has NO wifi/bluetooth/sharing/datetime systemux routes. Valid:
quick_settings, settings (+16 more, list in scratchpad). Cast/Wi-Fi/BT now
deep-link `systemux://quick_settings` → SEO router starts QuickSettingsActivity
with OVERLAY_LAUNCHER (true overlay over the app — the ArborXR Home behavior;
Wi-Fi + BT + Cast all live there). Settings gear → `systemux://settings` →
Horizon SettingsActivity via shell relay. Clock keeps the Android-action chain
(no datetime route). Exact verified intent shape: ACTION_VIEW + explicit
com.oculus.vrshell/.MainActivity + `intent_data` extra (do NOT send an
unverified `uri` extra — it poisons the request; "LegacySystemUXRoutes:
Invalid route" lines are legacy-router noise, the modern router still routes).
ALL verified from in-app bar taps: cast→QuickSettings window, wifi→same,
settings→SettingsActivity. Fallback chains intact.

Perf: 51.8% CPU / PSS 515 MB (water cadence unchanged at 15 Hz; same class
as 0.3.39). Artifact: openpanel-vr-0.3.43-meta-quest-debug.apk, SHA-256
6b983724369dd4c3cfccef96f220c6aac6dc47e903b0b99d2e4012a5e71f2ec9. vc53/54
were intermediate (overlay first cut, water); vc55 sideloaded on device.
ArborXR still holds 0.3.40 — 0.3.43 upload ready on Anthony's go.
Wear-check list: water motion visible, quick-settings overlay UX from cast/
wifi/bt buttons, reset-size button, passthrough smoke, ambience level.

## CLOSE-OUT 17:50 (Claude) — 0.3.44/vc56: water glints on the REAL river surface + search autofocus

Anthony's wear feedback on 0.3.43: water visible but the "fake waves" were an
overlay washing over ALL textures (and faintly bleeding onto the panel) — not
the tutorial's approach. Correct fix shipped:
- **The scene's actual water surface** — the `river_water` curve+GN ribbon in
  polyhaven_pine_fir_forest.blend — is now exported to app space
  (scripts/export-river-water-glb.py: evaluated to mesh, camera-space
  transform matching the 360 render, 55 m radius crop, decimated 100k→15.6k
  faces, world-planar UVs at 1 tile/9 m, shimmer texture embedded;
  assets/river_water_surface.glb, 578 KB). Two stacked copies (lift 0/0.035 m,
  repeats 1.0/1.45, alpha 0.42/0.30) drift oppositely via WaterMotion —
  in-place undulation CONFINED to the river. The old scene-wide Plane overlays
  and their constants are gone. Capture: glints follow the riverbed around
  the rocks, banks clean, panel body clean →
  audits/quest-2026-08-23-v0.3.44-river-glints/01-river-glints.jpg.
- Layer-panel occlusion note: compositor layers composite OVER the eye buffer,
  so scene water can never draw onto panels — the 0.3.43 bleed was the giant
  overlay planes' bright glints leaking through ALPHA_BLEND; gone with them.
- **Search**: the field now takes focus the moment the Search pill opens
  (FocusRequester + LaunchedEffect) — one tap → keyboard up; the system
  keyboard's mic key is Quest's voice-input path (no RecognizerIntent on
  Horizon OS, re-verified 0.3.3 finding).
- Tree also carries the runner session's polish (Role.Button ×4,
  environment.env deleted).
Artifact: openpanel-vr-0.3.44-meta-quest-debug.apk. Tuning dial if Anthony
finds the glints too bold: createWaterMaterial alphas (0.42/0.30) and the
112/255 cap in scripts/generate-water-shimmer.py.
Panel-anchoring ask: answered in chat — poses already persist per-headset;
true wall/world anchors need Space Setup run once + the MRUK anchor pass
(planned); a pose-lock toggle is a cheap alternative if wanted.

## Status 21:05 (Claude) — 0.3.45/vc57: dappled caustic-web water texture (tutorial-matched), visual loop armed

Anthony on 0.3.44: worse — the sine-ring texture read as soap swirls over the
baked river. Got the ACTUAL tutorial video this time (yt-dlp android client
beat the 403) and extracted frames: the target look is fine DAPPLED cellular
ripple glints (two mixed noise fields), never smooth rings. Texture generator
rewritten (v3): FFT-periodic band-limited noise → narrow contour bands =
organic caustic webs (two scales, crest-gated fine layer, pinpoint sparkles,
6.6% coverage, cap 96/255, seamless tile — edge fade dropped since the river
MESH confines the water now). Offline composite over the real skybox river
(audits/...02-v45-glint-sim-compare.jpg vs 03-tutorial-reference-frames.jpg):
webs sit UNDER the baked sun-glints, dappled character matches.

0.3.45/vc57 built (36/36) + sideloaded. LIVE VISUAL LOOP BLOCKED: headset
lost 6dof tracking ("Finding position in room" — dark room, set down);
immersive scenes cannot render without tracking, so MetaCam shows black.
A watcher monitor captures every 40 s and pings when the scene renders again
(brightness gate) — the compare loop resumes automatically then. Tree still
carries the runner's perf fixes (write-on-change scale watcher, single
package scan). vc57 artifact archived.

## CLOSE-OUT 23:25 (Claude) — 0.3.46/vc58: water animation ACTUALLY works now (root cause found)

THE finding of the night: **a toolkit Material component does NOT override a
GLB's embedded materials** — verified with a red-tint test (tint never
rendered). Every "water" iteration since the GLB switch was showing the GLB's
embedded static texture; the 15 Hz offset loop was writing to a Material
component nothing rendered. That is why the water never moved.

Fix chain (0.3.46/vc58, sideloaded + capture-verified):
1. Runtime animation path: `SceneObjectSystem.getSceneObject(entity)` →
   `SceneObject.mesh.getMaterial("river_glint")` → **`SceneMaterial.setCropUV`**
   panned every tick by WaterMotion (near tile 1.0, far 1.45). Visual change
   between builds proves crop applies live.
2. The two layers load the SAME mesh as TWO asset files
   (river_water_surface_a/b.glb) so mesh-cache sharing can't collapse their
   materials into one.
3. Mesh cleanup: terrain-BVH clip (water below terrain = under the banks,
   deleted), radius 18 m, app-space height gate −4.0…−0.15 m (elevated
   upstream stretches rendered as floating sheets across the panel).
4. Texture v3 final: FFT-periodic contour webs (tutorial's dappled character,
   frames extracted from the actual video), knee 0.26 / cap 72, 1.4% coverage —
   texture alpha IS layer opacity on this path (no runtime multiplier).
Capture evidence: audits/quest-2026-08-23-v0.3.44-river-glints/ (01 broad
overlay → 03b unclipped GLB spill → 04 final subtle webs on clean banks).
MQDH-style keep-awake for unworn captures: `am broadcast
com.oculus.vrpowermanager.automation_disable` THEN `prox_close` (both needed;
guardian_pause as before). Tests 36/36. Anthony wear-check: drift speed/
intensity; dials = texture knee/cap + WaterMotion speeds.

## CLOSE-OUT 09:00 (Claude) — 0.3.47/vc59: NATIVE 3D ENVIRONMENT (Anthony's architecture call)

Anthony (mid-iteration): the env "should be a native 3d env not a 360 photo" —
glints on a photo can never sit at the right stereo depth. Shipped exactly
that, via skybox reprojection onto real scene geometry:

- `scripts/export-near-environment.py`: terrain patch (25 m radius) + the 6
  hand-placed rock meshes from the .blend, cropped, app-space transformed,
  decimated to 28.5k faces, and textured by REPROJECTING the existing 8K
  equirect render (per-loop equirect UVs, u-seam handling) — near field
  matches the dome pixel-for-pixel by construction, no baking, no seams. The
  8K JPEG rides embedded in the GLB (13.5 MB).
- Water (river_water GLB, animated via setCropUV) now sits at TRUE depth
  inside a REAL 3D riverbed — the "floating on top" percept is structurally
  gone. Dome unchanged as far backdrop (stereo can't tell past ~50 m).
- TWO hard-won mapping facts: (1) glTF exports TEXCOORD_0 = the mesh's FIRST
  UV layer — writing a new layer silently does nothing (cost a full device
  loop; fixed by clearing existing layers first). (2) Correct equirect
  mapping for our pipeline is u = 0.5 + atan2(x, −z)/2π, v = 0.5 + asin(y/r)/π
  authored bottom-up — settled empirically with a LOCAL ground-truth loop
  (render the GLB from origin as equirect in Blender, compare to the skybox:
  scratchpad check-reprojection.py pattern; 4-variant grid in audit 06).
- Perf: CPU 34.6% (best all night), 90–91 FPS locked; PSS 714 MB (the env
  GLB decodes a second 8K copy — optimization noted: crop embedded texture to
  the below-horizon band, would roughly halve it back).
- Known cosmetic: a faint terrain-rim seam ~25 m out on the right bank; a fog
  band or larger radius would hide it. Rocks at arm's length use projected
  photo texture (volume ✓, texture stretch on backsides by design).
Captures: audits/quest-2026-08-23-v0.3.44-river-glints/05-v47-native-3d-env.jpg
(+ 06 orientation grid). Artifact vc59 archived. ArborXR still holds 0.3.40.

## ROLLBACK 09:20 (Claude) — 0.3.47/vc59 pulled: "completely broken" on wear (Anthony)

Rolled the headset back to 0.3.46/vc58 (adb install -r -d, verified running).
0.3.47's native-3D env is implicated but root cause NOT yet known — logcat
retained no FATAL/ANR/OOM lines by the time I looked. Candidate suspects for
the wear-state break (unworn captures looked correct): PSS 714 MB + wear
pipelines → memory pressure; the 13.5 MB env GLB's embedded 8K decode; the
emission/base-color double material under real 6dof movement; 28.5k-face
alpha-sorted geometry interacting with layer panels. DO NOT re-ship vc59.
Tree still holds the 0.3.47 code — next env attempt must: halve the embedded
texture (crop to below-horizon band), use emission-only (unlit) material,
and be wear-tested immediately. Awaiting Anthony's symptom description
(black world / frozen / double vision / crash?) to pin it.

## CLOSE-OUT 09:40 (Claude) — 0.3.48/vc60: water OFF per Anthony, learnings preserved

Anthony: "remove the water texture and increment; save what we learned; leave
it off." Shipped 0.3.48/vc60 (sideloaded, verified): ENABLE_ANIMATED_WATER_
OVERLAY=false and ENABLE_NATIVE_NEAR_ENV=false (the quarantined vc59 env).
The scene is the clean 8K skybox with the baked still river — Anthony's
approved 0.3.46 baseline minus the moving glints. Zero water entities load
(log-verified), CPU 46%, PSS 491 MB, no crashes.

Everything learned stays in the tree, dormant and re-enableable by flag:
WaterMotion (continuous counter-drift), SceneMaterial.setCropUV runtime
animation (the ONLY way to animate GLB materials — Material components don't
touch them), the terrain-clipped river_water_surface_a/b.glb exporters, the
FFT contour-web texture generator, the reprojection env exporter + local
equirect ground-truth check loop, and all the glTF/UV/stereo-depth gotchas
(AGENTS 23:25 + 09:00 + 09:20 entries; audits/quest-2026-08-23-v0.3.44-*).
Assets still packaged: river GLBs + near_environment.glb (~16 MB total) —
candidates for removal if size matters before the next water attempt.

## SHIPPED 09:48 (Claude) — 0.3.48/vc60 → ArborXR Beta (latest)

Anthony's go. Uploaded via keychain wrapper: build
`f940e223-e987-4986-9040-ee49e306b8c6` = 0.3.48/vc60, status **available**,
Beta (default) channel targets it, **isLatest: true** (portal-verified,
read-only MCP). 0.3.40 relegated off the channel. REMINDER: OpenPanel VR is
still not ASSIGNED to the Quest 3S portal-side — Anthony's one portal click
for MDM delivery; the headset meanwhile runs the identical vc60 sideloaded.
