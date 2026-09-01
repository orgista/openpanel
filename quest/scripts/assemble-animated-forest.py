# Stage 2: assemble forest_animated_v001.blend — the pine scene with animated
# Sapling trees (wind) swapped in NEAR the camera, static forest kept in the
# distance (motion is invisible far away; preserves the approved vista).
# Run WITH --factory-startup on the pine scene:
#   blender -b --factory-startup polyhaven_pine_fir_forest.blend \
#     -P assemble-animated-forest.py -- --trees trees_animated_v001.blend \
#     --out forest_animated_v001.blend
import math
import sys

import bpy

argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []


def arg(name, default=None):
    if name in argv:
        i = argv.index(name)
        return argv[i + 1] if i + 1 < len(argv) else default
    return default


trees_blend = arg("--trees")
out_blend = arg("--out")
NEAR_M = 45.0

cam = bpy.context.scene.camera
C = cam.location.copy()
C.z = max(C.z, 1.65)

# 1. Append animated tree collections.
with bpy.data.libraries.load(trees_blend, link=False) as (src, dst):
    dst.collections = [c for c in src.collections if c.startswith("TreeVar_")]
tree_colls = [c for c in bpy.data.collections if c.name.startswith("TreeVar_")]
for c in tree_colls:
    bpy.context.scene.collection.children.link(c)
    for o in c.objects:
        o.hide_render = True  # sources hidden; we place instances
        o.hide_viewport = True
print("appended:", [c.name for c in tree_colls])

# 2. The visible trees are ALL scatter instances (hero sources sit in a far
# staging area), so removal is impractical. Instead: ADD animated trees at
# clear spots near the camera — mixed still/swaying trees reads naturally.
from mathutils import Vector
deps = bpy.context.evaluated_depsgraph_get()
trunks = []
for inst in deps.object_instances:
    nm = (inst.instance_object or inst.object).name.lower()
    # whole-tree sources only — twig/needle/branch instances blanket the map
    if any(nm.startswith(k) for k in ("pine_proxy", "fir_proxy", "tree_trunk_proxy", "pine_0", "silver_fir_0")):
        pos = inst.matrix_world.translation
        if (Vector((pos.x, pos.y)) - Vector((C.x, C.y))).length < 60:
            trunks.append((pos.x, pos.y))
print("existing near trunk instances:", len(trunks))

terrain = bpy.data.objects["terrain_main"].evaluated_get(deps)
import mathutils.bvhtree as bvt
tb = bvt.BVHTree.FromObject(terrain, deps)
river = bpy.data.objects["river_water"].evaluated_get(deps)
rmesh = bpy.data.meshes.new_from_object(river, depsgraph=deps)
rmesh.transform(bpy.data.objects["river_water"].matrix_world)
river_xy = [(v.co.x, v.co.y) for i, v in enumerate(rmesh.vertices) if i % 12 == 0]

def ground_z(x, y):
    hit = tb.ray_cast(Vector((x - 0, y - 0, 60)) - Vector((0,0,0)) + Vector((0,0,0)), Vector((0, 0, -1)), 200)
    return hit[0].z if hit[0] is not None else None

import math as _m
spots = []
for radius in (9, 13, 18, 24, 31, 39):
    for ang_deg in range(0, 360, 24):
        if len(spots) >= 9:
            break
        a = _m.radians(ang_deg)
        x = C.x + radius * _m.cos(a)
        y = C.y + radius * _m.sin(a)
        gz = ground_z(x, y)
        if gz is None or gz < -0.35:  # too low = river bed
            continue
        if any((x - tx) ** 2 + (y - ty) ** 2 < 4.8 for tx, ty in trunks):
            continue
        if any((x - rx) ** 2 + (y - ry) ** 2 < 6.25 for rx, ry in river_xy):
            continue
        spots.append((Vector((x, y, gz)), a, radius, f"r{radius}a{ang_deg}"))
        trunks.append((x, y))
print("animated tree spots:", [(s[3], round(s[0].z, 2)) for s in spots])

# 3. Place animated collection instances at those spots (cycle variants).
for i, (loc, yaw, d, name) in enumerate(spots):
    coll = tree_colls[i % len(tree_colls)]
    inst = bpy.data.objects.new(f"AnimTree_{i:02d}_{coll.name}", None)
    inst.instance_type = "COLLECTION"
    inst.instance_collection = coll
    inst.location = loc
    inst.rotation_euler = (0, 0, yaw + i * 0.7)
    scale = 0.9 + (i % 3) * 0.12
    inst.scale = (scale, scale, scale)
    bpy.context.scene.collection.objects.link(inst)
print("placed", len(spots), "animated trees")

# 4. Ground-cover sway: gentle drivers on fern/moss/sapling SOURCE objects
# (propagates where instancing uses object transforms; partial coverage is
# expected and acceptable).
swayed = 0
for o in bpy.data.objects:
    n = o.name.lower()
    if o.hide_render:
        continue
    if any(k in n for k in ("fern", "moss_strand", "sapling", "cover")):
        for axis, speed, amp in ((0, 0.55, 0.02), (1, 0.42, 0.016)):
            fc = o.driver_add("rotation_euler", axis)
            fc.driver.expression = (
                f"{amp}*sin(2*pi*frame*3/240+{(hash(n) % 62) / 10:.2f})"
            )
        swayed += 1
print("ground sway on", swayed, "objects")

bpy.ops.wm.save_as_mainfile(filepath=out_blend)
print("SAVED", out_blend)
