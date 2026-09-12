# Renders the animated pine-forest scene as an equirect 360 image SEQUENCE
# (restartable: existing frames are skipped — safe to kill and relaunch).
#
# The scene's own `river_flow` material is already frame-driven (frame/100,
# /150, /50 → flowing, evolving water). This script adds gentle test sway to
# the hero source trees; full wind is a future pass (see the pipeline doc).
#
# Usage (test clip):
#   blender -b --factory-startup polyhaven_pine_fir_forest.blend \
#     -P render-forest-360-video.py -- --outdir /path/frames --start 1 --end 48 \
#     --width 1280 --samples 20
# Hyper-HD (only after the test clip validates on-device):
#   ... --start 1 --end 240 --width 5760 --samples 56
#
# Assemble: ffmpeg -framerate 24 -i f_%04d.jpg -c:v libx265 -tag:v hvc1 \
#   -pix_fmt yuv420p -crf 22 out_360.mp4
import bpy
import math
import os
import sys

argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []


def arg(name, default=None):
    if name in argv:
        i = argv.index(name)
        if i + 1 < len(argv):
            return argv[i + 1]
    return default


outdir = arg("--outdir", "/tmp/forest360")
start = int(arg("--start", "1"))
end = int(arg("--end", "48"))
width = int(arg("--width", "1280"))
samples = int(arg("--samples", "20"))
# Time-scale for the scene's frame-driven WATER materials (river_flow et al).
# 1.0 = original design speed at 24 fps. The 12fps test re-time felt half
# speed; Anthony wanted "a bit more" → 0.35 for the HD pass (2026-08-24).
waterscale = float(arg("--waterscale", "1.0"))
# Loop length the sway/water cycles are designed against (240 for HD).
looplen = int(arg("--looplen", "240"))
# Frame stride: 2 = odd frames only (12 fps first-pass; evens can be filled
# in later — existing frames are skipped, so the passes compose).
step = int(arg("--step", "1"))
os.makedirs(outdir, exist_ok=True)

# Bake the water slowdown into every material node-tree driver that reads
# `frame` (render-time only; never saved into the .blend). Object/pose-bone
# drivers (tree + fern sway) are left alone — those are loop-locked already.
if waterscale != 1.0:
    n_scaled = 0
    for mat in bpy.data.materials:
        # Only the river flow drivers — tree/cover WIND drivers (v002 blend)
        # also read `frame` but are loop-locked integer-cycle sines that
        # must NOT be time-scaled.
        if "river" not in mat.name:
            continue
        nt = mat.node_tree
        if nt is None or nt.animation_data is None:
            continue
        for fc in nt.animation_data.drivers:
            expr = fc.driver.expression
            if "frame" in expr:
                fc.driver.expression = expr.replace("frame", f"(frame*{waterscale})")
                n_scaled += 1
    print(f"waterscale {waterscale}: scaled {n_scaled} material drivers")

# NOTE: object-level tree sway was removed 2026-08-25 — the tree scatter is
# frozen inside terrain_main's geometry nodes and ignores source-object
# transforms/modifiers/mesh edits. Wind now lives in the MATERIALS
# (scripts/add-wind-and-water-falloff.py), which instancing cannot bypass.

sc = bpy.context.scene
src_cam = sc.camera
C = src_cam.location.copy()
C.z = max(C.z, 1.65)
yaw = src_cam.rotation_euler.z

cam_data = bpy.data.cameras.new("v360")
cam_data.type = "PANO"
cam_data.panorama_type = "EQUIRECTANGULAR"
cam = bpy.data.objects.new("v360", cam_data)
sc.collection.objects.link(cam)
cam.location = C
cam.rotation_euler = (math.radians(90), 0, yaw)
sc.camera = cam

sc.render.engine = "CYCLES"
prefs = bpy.context.preferences.addons["cycles"].preferences
prefs.compute_device_type = "METAL"
prefs.get_devices()
for d in prefs.devices:
    d.use = True
sc.cycles.device = "GPU"
sc.cycles.samples = samples
sc.cycles.use_denoising = True
sc.cycles.use_adaptive_sampling = True
sc.cycles.adaptive_threshold = 0.06
sc.cycles.max_bounces = min(getattr(sc.cycles, "max_bounces", 12), 8)
sc.render.use_persistent_data = True  # big win for animation: keep BVH between frames

sc.render.resolution_x = width
sc.render.resolution_y = width // 2
sc.render.resolution_percentage = 100
sc.render.image_settings.file_format = "JPEG"
sc.render.image_settings.quality = 92

for frame in range(start, end + 1, step):
    path = os.path.join(outdir, f"f_{frame:04d}.jpg")
    if os.path.exists(path) and os.path.getsize(path) > 10000:
        print(f"SKIP {frame} (exists)")
        continue
    sc.frame_set(frame)
    sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
    print(f"FRAME-DONE {frame}/{end}")

print("SEQUENCE-COMPLETE", start, end, width, samples)
