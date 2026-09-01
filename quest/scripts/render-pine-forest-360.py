# Renders the Fir & Pine Forest scene (blenderartists.org/t/1463567, the
# polyhaven_pine_fir_forest.blend in ../../pine_forest) as a mono equirect
# 360 panorama for the OpenPanel Quest skybox.
#
# Usage:
#   blender -b polyhaven_pine_fir_forest.blend -P render-pine-forest-360.py -- \
#       --out /path/out.jpg [--width 8192] [--samples 256] [--probe]
#
# The camera is placed at the scene's own camera position (the river-view
# shot Anthony supplied as the target), dropped to standing eye height above
# the terrain, so the river vista lands at the image center (in-app forward).
import bpy
import math
import sys

argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []


def arg(name, default=None):
    if name in argv:
        i = argv.index(name)
        if i + 1 < len(argv):
            return argv[i + 1]
    return default


out_path = arg("--out", "/tmp/pine_forest_360.jpg")
width = int(arg("--width", "8192"))
samples = int(arg("--samples", "256"))
probe = "--probe" in argv
if probe:
    width = int(arg("--width", "1536"))
    samples = int(arg("--samples", "24"))

scene = bpy.context.scene
scene.render.engine = "CYCLES"

prefs = bpy.context.preferences.addons["cycles"].preferences
try:
    prefs.compute_device_type = "METAL"
    prefs.get_devices()
    for device in prefs.devices:
        device.use = True
    scene.cycles.device = "GPU"
    print("Cycles device: Metal GPU")
except Exception as exc:  # noqa: BLE001
    print("Metal unavailable, CPU fallback:", exc)

# Keep the scene's look; only swap the camera for a panoramic one.
existing = scene.camera
base_loc = existing.location.copy() if existing else None
base_yaw = existing.rotation_euler.z if existing else 0.0

cam_data = bpy.data.cameras.new("OpenPanel360")
cam_data.type = "PANO"
cam_data.panorama_type = "EQUIRECTANGULAR"
cam_obj = bpy.data.objects.new("OpenPanel360", cam_data)
scene.collection.objects.link(cam_obj)
scene.camera = cam_obj

if base_loc is not None:
    # Stand where the reference shot was taken; equirect wants eye height that
    # reads standing (~1.65 m over local ground). The scene camera may hover;
    # keep its XY, bias Z toward eye height without going below it.
    cam_obj.location = (base_loc.x, base_loc.y, max(base_loc.z, 1.65))
else:
    cam_obj.location = (0.0, 0.0, 1.65)
# Equirect: rotation X=90 makes Z-up world render upright; yaw aligns the
# original camera's view direction to the image center (in-app forward).
cam_obj.rotation_euler = (math.radians(90), 0.0, base_yaw)

scene.render.resolution_x = width
scene.render.resolution_y = width // 2
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "JPEG"
scene.render.image_settings.quality = 95
scene.render.image_settings.color_mode = "RGB"
scene.render.filepath = out_path

scene.cycles.samples = samples
scene.cycles.use_denoising = True
try:
    scene.cycles.denoiser = "OPENIMAGEDENOISE"
except Exception:  # noqa: BLE001
    pass
# OIDN carries most of the quality at low sample counts; a loose adaptive
# threshold lets flat regions (half the equirect is sky) finish early.
scene.cycles.use_adaptive_sampling = True
scene.cycles.adaptive_threshold = 0.05
# The forest is mostly diffuse foliage; trimmed bounces keep render time sane.
scene.cycles.max_bounces = min(getattr(scene.cycles, "max_bounces", 12), 8)
scene.render.use_persistent_data = True

print(
    "Rendering %dx%d at %d samples (probe=%s) from %s yaw=%.1f"
    % (
        width,
        width // 2,
        samples,
        probe,
        tuple(round(v, 2) for v in cam_obj.location),
        math.degrees(base_yaw),
    )
)
bpy.ops.render.render(write_still=True)
print("Saved:", out_path)
