# Horizon OS ↔ OpenPanel implementation map

Paired analysis of the Horizon OS v81 reference capture
(`com.oculus.vrshell-20260821-094317-0.mp4`, 13:55, Quest 3S `340YC10G8D180B`)
and its logcat, against OpenPanel VR as of 0.2.9. Observed Horizon data below
was measured on this exact headset (2026-08-21); OpenPanel mappings cite the
current source. Format per feature: interaction → components → intents →
tasks/windows → displays → XR lifecycle → focus → input → OpenPanel
equivalent → gaps → test signatures.

---

## 1. App Library window

**Visible interaction.** Meta button → Navigator → library: one large panel,
app grid (rounded icon + 12sp label), left utility rail, click a tile → app
launches. No hero, no horizontal dock of tiles.

**Component.** `com.oculus.panelapp.library/com.oculus.navigator.library.app.NavigatorLibraryActivity`

**Intent/launch.** Launched by `com.oculus.vrshell`; `LAUNCH_SINGLE_TASK`;
raw flags `0x10010100` (NEW_TASK | SINGLE_TOP | 0x100 (NO_ANIMATION?)); placed
under a fresh volumetric root task.

**Volumetric window.** `windowSize2d: 1840×1000`, `densityDpi: 200`,
`type: 1`, `shape: 1` (flat quad), `singlePassCompositionEnabled: true`,
`depthTestEnabled: false`, `visible/active: true`.

**Displays.** Its content renders through AndroidPanelLayer virtual displays
owned by the panel app: `FLAG_PRIVATE | FLAG_NEVER_BLANK | FLAG_OWN_CONTENT_ONLY`,
60 Hz. System mirror roots add `FLAG_SECURE | FLAG_SUPPORTS_PROTECTED_BUFFERS |
FLAG_DESTROY_CONTENT_ON_REMOVAL`.

**OpenPanel equivalent (0.2.9).** `R.id.library_panel` — one
`ComposeViewPanelRegistration` quad, `2.0 × 1.087 m` (Horizon's 1840:1000
aspect, `PanelLayout.LIBRARY_*`), dpPerMeter 500, resolutionScale 0.85 →
compositor virtual display `virtual:com.orgista.openpanel.quest,…,vr,N`.
Content: `OpenPanelLibrary` — header row (quad mark, "Library",
close-running pill, allowed-apps pill) + `LazyVerticalGrid`
(Adaptive 112dp) of `LibraryTile`s. **Tile click = launch** (Horizon parity;
hover/focus = white ring + 1.06 scale).

**Gaps / unknowns.**
- No left utility rail (search / list-view / recents / store). Search is the
  first candidate when app counts grow.
- Horizon's grid paginates horizontally with page dots; ours scrolls
  vertically. Irrelevant below ~24 apps.
- Spatial SDK quads don't expose Horizon's `shape: 1` metadata or
  singlePassComposition toggles; not controllable from app land.

**Test signatures.**
- `adb shell dumpsys display | grep "virtual:com.orgista.openpanel.quest"` →
  exactly **2** entries (library, system bar) after boot.
- Library display ≈ `1275×693` px at scale 0.85.
- Tap-inject: `adb shell input -d <libraryDisplayId> tap <x> <y>`.

## 2. System bar

**Visible interaction.** Detached pill-shaped bar floating below/in front of
the active window: round buttons, profile, quick settings (clock + battery +
Wi-Fi), camera. Persistent across panels.

**Component.** `com.oculus.panelapp.library/com.oculus.navigator.systembar.app.SystemBarActivity`,
`LAUNCH_SINGLE_INSTANCE`, own volumetric root task, `windowSize2d: 850×175`,
`densityDpi: 200`. Auxiliary layers: `system_bar_status` 900×75@60 Hz,
`system_bar_wayfinder_menu` 400×313@60 Hz (same owner, VIRTUAL,
`FLAG_PRIVATE | FLAG_NEVER_BLANK | FLAG_OWN_CONTENT_ONLY`).

**OpenPanel equivalent (0.2.9).** `R.id.system_bar_panel` — separate quad
`0.924 × 0.190 m` (Horizon's 850:175 aspect), resolutionScale 0.75, below the
library at `(0, 0.72, −1.52)`. Content: `OpenPanelSystemBar` — full-round
graphite pill: quad mark · Immersive/Passthrough pill · round Settings button
(`Icons.Outlined.Settings`) · `h:mm a · N%` clock+battery (30 s tick,
`BatteryManager.BATTERY_PROPERTY_CAPACITY`).

**Gaps.** No profile chip, camera, notifications, or wayfinder; no status
sub-layer. The bar is per-launcher, not system-persistent — it exists only
while OpenPanel is foreground (launcher-scoped by design; ArborXR kiosk use
never shows another launcher).

## 3. Panel/window state tracking

**Horizon.** `ShellSpatialWindowManagerService` tracks
`SpatialPanelState { componentName, panelId, volumetricWindowToken,
state: Focused|Foreground|Background, shouldBePrePinned }` plus a
`mostRecent` component list for recency ordering.

**OpenPanel equivalent.** Spatial SDK owns panel entities; app-side state is
Compose (`apps`, `runningApp`, `showAllowedApps`, `passthroughEnabled`).
Recency: none — single `runningApp` (last launch) only.

**Gaps.** No multi-window tracking (launcher launches into the shell's world
and loses ownership — by design on Horizon: third-party apps cannot manage
other apps' volumetric windows). `mostRecent`-style ordering is feasible
app-side (persist launch history) if "Recents" is wanted in the library.

## 4. Immersive launch pipeline (measured, YouTube @ video ~02:16)

Horizon's observable state machine:

```
Resolved → PlacementRequested → VolumetricWindowCreated → ImmersiveUiMode
        → OpenXrSessionStarting → RenderingEnabled → FirstFrameReported
```

1. `YouTubeVrPanel2Activity` invokes `InternalYouTubeVrActivity`;
   shell resolves the presented activity as `YouTubeVrActivity`.
2. Persistable volumetric-window token allocated; shell emits a pending
   launch with a `launchId`.
3. Immersive activity gets a new volumetric root; placement response:
   `bounds: Infinity³`, `surfaceSize: 4128×2208`, `visible: true`.
4. `UiModeController`: `uiModeFlags = 8` (IMMERSIVE),
   `immersiveAppPackageName = com.google.android.apps.youtube.vr.oculus`.
5. Compositor creates stereo swapchain `1680×1760 ×2 views ×3 buffers`
   (Vulkan). `xrBeginSession` 09:45:34.872 → complete .909 (~37 ms).
6. Runtime associates the client PID; `renderingEnabled 0→1`;
   `VolumetricWindowManagerService.notifyAppStartedRendering` at
   09:45:35.153 with the original launchId (~280 ms launch→first frame).

**OpenPanel side.** We are the *initiator* only: explicit
`ComponentName(pkg, activity)`, `ACTION_MAIN`, category
`com.oculus.intent.category.VR` (or `CATEGORY_LAUNCHER` for 2D),
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`
(`OpenPanelActivity.launchApp`). Everything after `startActivity` is the
shell's pipeline above — correct and unavoidable; a launcher must not try to
own it.

**Gaps.** None actionable. Optional telemetry: watch
`notifyAppStartedRendering` / `renderingEnabled` in logcat to show a
per-app "starting…" state in the library; deferred (log-read permissions
not worth it).

**Test signatures** (all verified callable via adb):
- `ActivityTaskManager: START u0 {…cmp=<pkg>/<activity>} … result code=0`
- `UiModeController` transition to `uiModeFlags = 8` + package name.
- `xrBeginSession` + `renderingEnabled` flip in the runtime log.
- Locked-device gotcha: `result code=-92` ("Error type 3 … does not exist")
  for *any* healthy app means user 0 is `RUNNING_LOCKED` (direct-boot
  filtering) — check `dumpsys user`, don't debug the APK.

## 5. Panel (2D) launch entries and the "many YouTube icons"

YouTube VR declares ≥5 exported MAIN entry activities on this device:
`YouTubeVrActivity`, `InternalYouTubeVrActivity` (`…category.VR` + INFO), and
`YouTubeVrPanelActivity`, `InternalYouTubeVrPanelActivity`,
`YouTubeVrPanel2Activity` (`…category.2D` + `OVERLAY_LAUNCHER`).

Because the ArborXR-deployed APK is not store-entitled, Horizon's own library
files it under **Unknown Sources and renders one row per entry activity** —
that is the "YouTube icon a bunch of times" in the Meta panel GUI, and it is
Horizon's behavior, not ArborXR's or ours. OpenPanel groups candidates by
package and keeps one best entry (VR > launcher, then label quality —
`selectLaunchableApps`), so the managed library shows exactly one YouTube.

**Gap turned feature.** If a 2D/panel presentation is ever wanted, the
`…category.2D` + `OVERLAY_LAUNCHER` entries are the documented hook — launch
those instead of the VR entry.

## 6. Closing a running app

**Horizon.** Window chrome (⋯ / − / ✕) on each panel; immersive apps close
from the library/dock or Meta-button quick actions. The shell owns the
volumetric window and tears the task down.

**OpenPanel (0.2.9).** `runningApp` is recorded at launch; the library header
shows a **"Stop <app>" pill**. **Measured on-device:** API 34 restricts
`ActivityManager.killBackgroundProcesses` to the *caller's own* packages — the
call is a logged no-op against another app, so a third-party launcher cannot
force-stop anything. The pill therefore stops what the user actually
perceives as "still open": it dispatches `KEYCODE_MEDIA_STOP` through
`AudioManager.dispatchMediaKeyEvent` (halts the active media session —
YouTube's lingering audio/video), makes the best-effort background kill
(harmless no-op on 34, useful on older/OEM builds), and clears `runningApp`.
The backgrounded process is then LMK fodder.

**Gaps.** True task teardown (`forceStopPackage`/`removeTask`) is
system/shell-only — Horizon's ✕ works because vrshell owns the volumetric
window. Nothing more is available to a sideloaded launcher on API 34; if
hard-close becomes a requirement it has to come from the MDM side
(ArborXR command) rather than the launcher.

**Test signatures.** After launch: `dumpsys media_session` lists the app's
session as active when playing → tap Stop → session goes
paused/stopped/none. `pidof <pkg>` may stay non-empty on API 34 (expected).

## 7. Environment (home world vs passthrough)

**Horizon.** Home environments are shell-rendered scenes; passthrough is a
system toggle in quick settings.

**OpenPanel.** `mesh://skybox` dome entity (`skydome_lakeside_dawn_v10`,
unlit) + translucent animated water planes + `scene.enablePassthrough(Boolean)`;
user toggle in the system bar; persisted (`SharedPreferences`), managed-config
`default_environment` honored until the user overrides; live re-read on
`ACTION_APPLICATION_RESTRICTIONS_CHANGED`. Verified both directions on-device
(0.2.6 regression + 0.2.8 tap-pass).

**Measured background budget.** The Quest headset captures and unpacked Meta OS
home assets point to an `8192x4096`-class panorama for the home/world
background, with many shell materials authored as `4096x4096` ASTC-class
textures. That is the practical fidelity floor for the OpenPanel skydome if we
want the environment to stay crisp after headset lens sampling, compositor
rescale, and stereo screenshot inspection. OpenPanel now follows that budget
with `skydome_lakeside_dawn_v10.jpg` (`8192x4096`) generated from a real 8K
Poly Haven equirectangular panorama instead of the earlier upscaled concept
image. The previous high-poly landscape reference is treated as art direction:
Quest receives a baked 8K dome and native water surface animation instead of
attempting to ship an 8M-poly Blender scene.

## 8. Input routing

**Horizon.** Controller ray / hands routed by the shell to the focused
volumetric window; hover states on tiles; system bar always hittable.

**OpenPanel.** Spatial SDK routes ray input per panel; Compose focus drives
hover visuals (`onFocusChanged` ring/scale). Headless verification injects
per-display taps (`input -d <displayId> tap`) — display ids enumerate as
`virtual:com.orgista.openpanel.quest,<uid>,vr,<n>` in `dumpsys display`.

## 9. Discovery & policy (OpenPanel-only, no Horizon analogue)

- Two `queryIntentActivities` sweeps: `ACTION_MAIN`+`category.VR`,
  `ACTION_MAIN`+`CATEGORY_LAUNCHER`; `MATCH_ALL`.
- Installer attribution `getInstallSourceInfo().installingPackageName`;
  ArborXR content reports `app.xrdm.client`; infra hidden
  (`app.xrdm.client|launcher`, `com.oculus.oemconfig`); self hidden.
- Managed config (`app_restrictions.xml`): `visible_packages`,
  `hidden_packages`, `show_all_apps`, `default_environment`.
- One-app reference state (current device):
  `visible = [com.google.android.apps.youtube.vr.oculus]`, isVr = true,
  managedByArborXr = true, "1 allowed".
- Flat-dark art (Unity auto-banners) discarded by `FlatArtDetector`
  (32×18 luma sample; mean < 0.10, lit fraction < 2%).
- MDM interplay (learned 2026-08-21): a sideload with `-i app.xrdm.client`
  gets removed at the client's next sync — never spoof the installer; plain
  sideloads (installer=null) are left alone. Unassigning an app in the portal
  uninstalls it at sync; OpenPanel reflects that within one `refreshApps()`.

## 10. Verification runbook (device `340YC10G8D180B`)

1. `pm list packages -i | grep <pkg>` — attribution as expected.
2. `dumpsys user | grep Started` — must be `RUNNING_UNLOCKED` before any
   launch-path testing.
3. Launch from grid via display-injected tap; observe
   `ActivityTaskManager START … result code=0`, `UiModeController → 8`,
   `renderingEnabled 0→1`.
4. Return (`am start …OpenPanelActivity`), verify `onResume` re-discovery
   (same package set, environment preference intact, "Close <app>" pill up).
5. Close pill → `pidof <pkg>` empty.
6. Capture: `am startservice -n com.oculus.metacam/.capture.CaptureService
   -a TAKE_SCREENSHOT` → `/sdcard/Oculus/Screenshots/` (plain `screencap`
   returns black for compositor content).
7. Heat floor: `top` — expect ≈ high-30s % of one core idle with 2 panels
   (was ~42% at 4 panels, ~47% at 5).
