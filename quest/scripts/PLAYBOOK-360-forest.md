# 360° Forest Video Pipeline — Reproduction Playbook

Written 2026-08-25 so that ANY session (including smaller models) can reproduce
or extend this work without rediscovering the traps. Follow literally. The
chronological narrative lives in `/Volumes/Files/Projects/blender-360-video-openxr-pipeline.md`
(read its WORK LOG tail for current state); THIS file is the distilled how-to.

## What this pipeline produces
A seamlessly looping 8K (7680×3840) equirect 360 video of an animated forest
(wind, flowing creek) rendered in Blender Cycles, played as a video dome
behind the panels in OpenPanel VR (Quest 3S, Meta Spatial SDK 0.13.2).

## The assets
- Pristine original (NEVER modify): `…/pine_forest/polyhaven_pine_fir_forest.blend`
- Working copies (scratchpad, self-contained via pack_all):
  `~/blend_local/forest_animated_v008_packed.blend` ← CURRENT (also backed
  up on the share in Renders/forest360/). Lineage: v001 base → v003
  +wind/falloff/bark fixes → v005 +ecosystem → v006 calm wind → v008 =
  APPROVED LOOK (all generated trees removed after wear review — "birch
  trees just look out of place"; tree-scale wind OFF everywhere; understory
  shimmer 0.020 only). v007 (trees kept, becalmed) exists but was rejected.
- Tree libraries: `blend_local/trees_animated_v001.blend` (conifers),
  `blend_local/trees_broadleaf_v001.blend` (birch/aspen/maple).
- Scripts (this dir): `render-forest-360-video.py` (renderer),
  `add-wind-and-water-falloff.py`, `assemble-ecosystem-v3.py` (v2 is
  superseded — do not use), `generate-animated-trees.py`,
  `generate-broadleaf-trees.py`.

## Iron rules (violating any of these cost us hours to days)
1. **Never render from a bare copy of the .blend.** The scene links textures by
   relative path; a bare copy renders MAGENTA (looks purple in encodes).
   Always: open original → `bpy.ops.file.pack_all()` → save to LOCAL disk.
2. **Never render from the SMB volume** (`/Volumes/Files/...` as SOURCE):
   Blender mmap → `BLI_mmap SIGBUS` crash mid-sequence. Render FROM local
   packed blend; writing output JPGs TO the SMB volume is fine.
3. **Open the 2023 scene headless with `--factory-startup`** or it may not load.
   Exception: the generate-*-trees scripts need the sapling extension, so they
   run WITHOUT --factory-startup.
4. **Loop math:** every animated thing must complete INTEGER cycles over the
   240-frame loop (`sin(2*pi*frame*k/240 + phase)`, k integer). Non-integer =
   visible pop at the loop point.
5. **Verify frames before encoding, verify the encode tail before pushing**
   (see "Verification loop" below). The purple metric: ffprobe signalstats,
   frame is magenta-poisoned if UAVG>140 AND VAVG>140.
6. **waterscale only scales river materials.** The renderer's `--waterscale`
   multiplies `frame` in drivers of materials whose name contains "river".
   Wind drivers also read `frame` but are loop-locked — scaling them breaks
   rule 4. Never widen that filter.

## THE BIG ONE: the forest scatter is FROZEN
`terrain_main`'s geometry-nodes stack instances ~34k objects (trees, ferns,
moss, rocks). It ignores EVERYTHING about the source objects — proven
empirically, one test render each:
- object transforms (rotate a source → nothing changes)
- modifiers on sources (Simple Deform → nothing)
- even raw MESH-DATA edits (numpy-lean the vertices → nothing)

**The ONLY lever that reaches the instances is the MATERIAL** (Cycles
evaluates materials per instance at render time). Consequences:
- Wind = material displacement (see below), never object animation.
- You cannot delete/reshape scatter geometry; you CAN hide it by making its
  material Transparent-BSDF, or restyle it in the shader.
- Hand-PLACED objects (collection-instance empties, linked-dup meshes) render
  live and DO obey normal animation (armature pose drivers etc.).

## Wind (material displacement recipe)
In every foliage/bark material (script: add-wind-and-water-falloff.py):
`offset_x = frac * z_obj * generated_z * (sin(w1*frame+P) + 0.5*sin(w2*frame+1.7P))`
- `z_obj` = Object-space Z (meters above base; origins are at the base) →
  amplitude auto-scales with real plant size even for shared materials.
- `generated_z` = Generated coords Z (0..1 of bbox) → base planted, crown moves.
- `P = ObjectInfo.Random * 2π` per-instance phase `+ (0.22*loc.x + 0.13*loc.y)`
  world-position term = gusts TRAVEL through the forest (~25 m wavelength).
- k trees 4&7, ground cover 11&17 (integer/240). frac (calibrated by wear
  test 2026-08-26 — 0.020/0.060 read as a STORM): trees 0.009, cover 0.028,
  placed trees 0.010.
- Set `material.displacement_method = "BOTH"`. Feed CombineXYZ into Material
  Output Displacement (ADD to any existing displacement link).
- PINES ARE EXEMPT (pine_bark, pine_twig have no displacement link): their
  flat ribbon branch-cards look like floating debris when moving. Firs sway,
  pines stand stiff — reads naturally.

## Water (creek realism)
- 3 drivers in `river_flow` node tree: offsets frame/100, frame/50, noise-W
  frame/150 — routed through Value nodes × a Camera-Data View-Distance
  falloff: MapRange smoothstep 2.2m→15m mapping 1.0→0.18 (offsets) and
  1.0→0.45 (W shimmer). Near water full speed, distant water nearly still —
  real-creek behavior (Anthony's spec + kazephoto 円原川 reference).
- Global speed: render with `--waterscale 0.33` (≈ half design speed minus 35%).

## Adding trees/foliage that actually work
1. Generate with Sapling (see generate-broadleaf-trees.py). GOTCHAS:
   - Do NOT use armature/pose-bone wind at all: Sapling leaf cards are not
     weight-bound to the armature, so bones move wood but leaves stay put
     ("detached leaves", wear-tested). Give placed trees the SAME
     material-displacement wind as the scatter instead — trunk+leaves share
     the object so they move coherently by construction. (Also:
     `armAnim=True` crashes Blender 5.x — legacy Action.fcurves.)
   - Sapling leaves are objects named `leaves*`. **`"leaf" in "leaves" is
     FALSE in Python** — match with `startswith(("leaves","leaf"))` or your
     foliage silently wears the BARK material (white birch bark = white tree).
   - With `makeMesh=True` Sapling ALSO keeps the un-materialed CURVE 'tree'
     object → renders WHITE on top of the skinned mesh. DELETE curve objects.
   - Materials with 0 users are DROPPED on save — assign them or set fake user.
   - Preset raw heights are wild (2.5m…54.5m). Normalize: empty.scale =
     target_height / measured_collection_height.
2. Place as COLLECTION-INSTANCE empties at BVH-verified clear spots
   (assemble-ecosystem-v3.py: terrain ray ground-snap, ≥2.2–2.6 m from any
   scatter trunk, ≥2.5 m from river verts).
   - **Do NOT set hide_render on collection members** — that hides them inside
     every instance (the v1 trees rendered NOTHING for weeks because of this).
     Instead: members visible, UNLINK the source collection from the scene.
   - Do NOT place `*_proxy` meshes directly (fern_proxy etc.) — they are GN
     placeholder DOMES; the real geometry only exists inside the frozen GN.

## Render
```
cd <scratchpad>/blend_local
blender -b --factory-startup forest_animated_v005_packed.blend \
  -P <quest>/scripts/render-forest-360-video.py -- \
  --outdir <renders>/hd_8k_v002 --start 1 --end 240 --width 7680 \
  --samples 48 --waterscale 0.33 --step 2
```
- Restartable: existing frames are skipped. `--step 2` = odd frames only →
  encode at 12 fps (same felt speed as 24 fps full set; user-approved). Run
  again without --step later to fill evens for 24 fps.
- Camera is built by the script: equirect PANO at scene-camera XY, z≥1.65,
  scene-camera yaw. NEVER change this — every shipped asset shares it.
- Pace on M1 Max Metal: ~27 min/frame @5760, ~40 min @7680. Cycles Metal GPU,
  persistent_data on. Unraid offload NOT worth it (mobile CPU, no usable GPU).

## Encode (every rule earned)
```
ffmpeg -framerate 12 -i f_%04d.jpg -c:v libx265 -preset slow -crf 20 \
  -tag:v hvc1 -pix_fmt yuv420p -x265-params "keyint=12:min-keyint=12:open-gop=0" \
  -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv \
  -movflags +faststart out.mp4
```
- H.265/hvc1 for >4096 wide (H.264 hw decode caps at 4096; Quest 3S decodes
  7680×3840 HEVC fine — verified; startup `QC2V4l2 Allocation failed` warns
  are harmless).
- Closed GOP + BT.709 tags mandatory (chroma misread + loop-wrap artifacts
  otherwise).

## Verification loop (headless, no human needed)
1. All frames: purple metric (UAVG/VAVG < 140) + count == expected.
2. Encode, then decode the LAST 6 frames and re-run the metric (tail check).
3. Push: `adb push out.mp4 /sdcard/Android/data/com.orgista.openpanel.quest/files/forest360.mp4`
   then `am force-stop com.orgista.openpanel.quest; monkey -p com.orgista.openpanel.quest 1`
   (app loads the file at startup — a restart is REQUIRED after every push).
4. Keep-awake before captures: broadcast `com.oculus.vrpowermanager.automation_disable`
   then `.prox_close`. Captures: `am startservice -n
   com.oculus.metacam/.capture.CaptureService -a TAKE_SCREENSHOT` →
   `/sdcard/Oculus/Screenshots/`. Take 6 spaced over ≥2 loop lengths, metric each,
   plus a frame-diff (>2% = video actually playing).
5. If captures are black + "PT is: ON" in logcat → headset is in passthrough
   (cameras covered / tracking lost): the app HIDES the dome by design. Not a
   bug. Needs lenses uncovered in light.
6. Blue floating "+" crosses in captures = OS no-boundary void grid, not ours.

## App side (OpenPanelActivity.kt facts)
- Video dome = VideoSurfacePanelRegistration + Equirect360ShapeOptions(300f) +
  ExoPlayer (REPEAT_MODE_ONE, volume 0) + MediaPanelRenderOptions(zIndex=-1).
- **Dome seam fix**: `SeamlessDomeShape` = stock Equirect360 applyTo THEN
  `config.includeGlass = false`. The SDK's "glass" is a translucent MESH
  sphere over the layer; its meridian edges are the V-seam converging at the
  nadir. (Flag `ENABLE_SEAMLESS_DOME`.)
- Do NOT try the scene-mesh path (`layerConfig=null` / `forceSceneTexture`):
  video panels render BLACK there — no Surface-backed SceneTexture in 0.13.2.
  The compositor layer is the ONLY video path.
- Diagnosing dome geometry: push a labeled calibration grid as forest360.mp4
  (see pipeline doc 2026-08-25); rotate content ±90° (`ffmpeg -vf v360=e:e:yaw=90`)
  to tell content-fixed vs dome-fixed artifacts apart.

## Scheduling renders via launchd (the 3pm-7am window)
- launchd KILLS the job's whole process group when the scheduled script
  exits — nohup does NOT save children. Two nights died silently mid
  scene-load (no crash report) before this was found. The plist MUST set
  AbandonProcessGroup=true. Symptom signature: process dies ~10s after
  the tick, log shows only "Read blend", manual starts work fine.
- Keep a supervisor loop around Blender anyway (relaunch on death inside
  the window, log exit code + free memory): SMB drops and memory pressure
  have each killed a night. Never run a second Blender scene-load on the
  32GB Studio while a render runs (deep swap; black windows).
- caffeinate -s tied to the supervisor pid = system awake, display asleep;
  verify via `pmset -g assertions | grep caffeinate` and log it.

## Wear-review lessons (2026-08-30/31, cost one full render round)
- Whole-frame diff %% hides look-breaking bugs. Before committing render-days
  to ANY scene change, render 4 frames spanning a motion cycle and build
  CROP STRIPS (same region side-by-side) of every new/changed element, at
  or near final res — then look at them.
- Generated Sapling trees with flat-color leaf cards read as PLASTIC next
  to the scene's PBR scatter at 8K; procedural pale bark reads as PAPER
  up close (the ghost-birch). Do not ship generated trees without
  photo-textured leaf/bark cards. v008 removed them all; variety = v2.
- Tree-scale displacement wind reads as rigid crown-WAG at full res even
  at frac 0.009-0.010 (and per-object Generated spaces desync trunk vs
  leaves on multi-object trees). Final look: trees STILL, understory
  shimmer (cover frac 0.020) + water only — matches real forest footage.
- The user's headset casting recordings (MQDH/odh device-media) are ideal
  ground truth; frame-sample them (ffmpeg fps=2) and inspect.

## Debug method that worked every time
Binary-search by elimination with CHEAP renders: 960–1280 px, 8–16 samples,
denoise off, one variable per render, compare with amplified diff maps
(PIL ImageChops, ×4 gain). Structural silhouettes in the diff = real change;
uniform speckle = noise floor (~1–2%), meaning your change did NOT reach the
render — do not proceed on hope.

## Hiding ONE scatter tree (learned 2026-09-11, cost ~a day — read before trying)
- **What the scatter really renders:** `terrain_main`'s `terrain` GN has ~50
  `Is Viewport` → Switch nodes: viewport (and Python's evaluated depsgraph!)
  see 56-poly `*_proxy` cylinders; RENDER swaps in hero collections
  (`pine_trees`, `fir_trees_l/m`, `*_sapling_*` → objects pine_01..05,
  silver_fir_01..06, pine_sapling_*, fir_sapling_*; materials pine_bark,
  pine_twig, pine_trunk_01, pine_dead_branches, fir_bark, fir_twig,
  fir_sapling_branches, ...). Editing `proxy_bark`/`leaves`/`proxy_leaves_*`
  does NOTHING to the render. To enumerate render-time instances from Python:
  unlink every `GeometryNodeIsViewport` output and set the Switch sockets
  False in memory (scratchpad script `build_v009.py`, pass 1), then read
  `depsgraph.object_instances` (~1.1M rows).
- **Per-instance key:** Cycles `Object Info → Random` = `random_id / 0xFFFFFFFF`
  (plain, NO hash; `inst.random_id & 0xFFFFFFFF`). A tree shows as an outer
  instance `(id,)` plus one nested `(0,id)` — cull BOTH (2 values per tree).
  Mask = Math COMPARE(Random, r, eps 4e-8) chain → MAXIMUM → Mix Shader
  fac → Transparent BSDF, inserted before Material Output in every hero
  material. Verified: saplings (`pine_sapling_medium_b`, `fir_sapling_medium_c`,
  `fir_sapling_small_01`) vanish cleanly, neighbours untouched.
- **Big hero pines (pine_0N) do NOT fully vanish this way:** crown twigs are
  deeper-nested instances Python never sees, with their own Random; after
  culling trunk+crown a swarm of dark specks stays in the sky, and those
  bits are NOT reachable by any material (no emission under a
  material-wide override, Position pass sees through them). Do not spend
  time on it — leave the tree, or fix at scene level (GN density mask /
  reseed) in the next scene version.
- **Diagnostic gotchas:** (1) emission-encoded "measurement" renders are
  CORRUPTED on alpha-cut leaf cards (transparent bounces sum layers; values
  even >1) — only opaque trunk pixels are trustworthy; (2) the material
  named `Material` is the FOG VOLUME (no Surface link) — never give it a
  surface or the god-rays vanish; (3) `render-forest-360-video.py` SKIPS
  existing frames — delete the outdir before a diagnostic re-render;
  (4) Blender 5.x: `scene.compositing_node_group` (no `scene.node_tree`),
  compositor output = `NodeGroupOutput`, File Output node uses
  `directory`/`file_name`; (5) emissive-everything renders need
  `material.cycles.emission_sampling='NONE'` or Cycles aborts on the
  emissive-triangle limit.
