# Stage 3: ecosystem variety pass (Anthony 2026-08-25) — appends the
# broadleaf library into the packed forest and places birch/aspen/maple +
# extra conifer variants + near-field fern clusters at terrain-grounded
# clear spots. Placed objects render live (unlike the frozen scatter) and
# animate: trees via their armature pose drivers, ferns via the fern
# material's wind displacement.
#   blender -b --factory-startup forest_animated_v003_packed.blend \
#     -P assemble-ecosystem-v2.py -- --trees trees_broadleaf_v001.blend \
#     --out forest_animated_v004_packed.blend
import math
import sys

import bpy
import mathutils.bvhtree as bvt
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []


def arg(name, default=None):
    if name in argv:
        i = argv.index(name)
        return argv[i + 1] if i + 1 < len(argv) else default
    return default


trees_blend = arg("--trees")
out_blend = arg("--out")

cam = bpy.context.scene.camera
C = cam.location.copy()
C.z = max(C.z, 1.65)

before_colls = set(bpy.data.collections)
with bpy.data.libraries.load(trees_blend, link=False) as (src, dst):
    dst.collections = [c for c in src.collections if c.startswith("TreeVar_")]
new_colls = [c for c in bpy.data.collections if c not in before_colls and c.name.startswith("TreeVar_")]
for c in new_colls:
    bpy.context.scene.collection.children.link(c)
    for o in c.objects:
        o.hide_render = True
        o.hide_viewport = True
print("appended:", [c.name for c in new_colls])


def colls_named(sub):
    return [c for c in bpy.data.collections if c.name.startswith("TreeVar_" + sub)]


birch = colls_named("white_birch")
aspen = colls_named("quaking_aspen")
maple = colls_named("small_maple") + colls_named("japanese_maple")
conifer = colls_named("douglas_fir") + colls_named("small_pine")

deps = bpy.context.evaluated_depsgraph_get()
occupied = []
for inst in deps.object_instances:
    nm = inst.object.name
    if any(nm.startswith(k) for k in ("pine_proxy", "fir_proxy", "tree_trunk_proxy", "pine_0", "silver_fir_0")):
        p = inst.matrix_world.translation
        occupied.append((p.x, p.y))
for o in bpy.data.objects:
    if o.name.startswith(("AnimTree_", "EcoTree_")) and o.instance_collection:
        occupied.append((o.location.x, o.location.y))
print("occupied trunks:", len(occupied))

terrain = bpy.data.objects["terrain_main"]
tb = bvt.BVHTree.FromObject(terrain, deps)
river = bpy.data.objects["river_water"].evaluated_get(deps)
rmesh = bpy.data.meshes.new_from_object(river, depsgraph=deps)
rmesh.transform(bpy.data.objects["river_water"].matrix_world)
river_xy = [(v.co.x, v.co.y) for i, v in enumerate(rmesh.vertices) if i % 12 == 0]


def ground_z(x, y):
    hit = tb.ray_cast(Vector((x, y, 60)), Vector((0, 0, -1)), 200)
    return hit[0].z if hit[0] is not None else None


def find_spot(rmin, rmax, clearance, tries, ang_seed):
    for t in range(tries):
        a = (ang_seed + t * 2.399) % (2 * math.pi)  # golden-angle walk
        r = rmin + ((ang_seed * 7 + t * 3.7) % (rmax - rmin))
        x, y = C.x + r * math.cos(a), C.y + r * math.sin(a)
        gz = ground_z(x, y)
        if gz is None or gz < -0.35:
            continue
        if any((x - rx) ** 2 + (y - ry) ** 2 < 6.25 for rx, ry in river_xy):
            continue
        if any((x - ox) ** 2 + (y - oy) ** 2 < clearance ** 2 for ox, oy in occupied):
            continue
        return x, y, gz, a
    return None


# (collection cycle, count, rmin, rmax, clearance, scale range)
PLAN = [
    (birch, 4, 11.0, 24.0, 2.6, (0.85, 1.15)),   # birch stand mid-ground
    (aspen, 3, 14.0, 30.0, 2.6, (0.9, 1.2)),
    (maple, 3, 6.0, 13.0, 2.2, (0.8, 1.1)),      # understory nearer in
    (conifer, 4, 10.0, 28.0, 2.6, (0.85, 1.2)),  # extra conifer variety
]
placed = 0
for colls, count, rmin, rmax, clearance, (smin, smax) in PLAN:
    if not colls:
        continue
    for i in range(count):
        spot = find_spot(rmin, rmax, clearance, 400, ang_seed=placed * 1.7 + i)
        if spot is None:
            print("NO SPOT for", colls[0].name, i)
            continue
        x, y, gz, a = spot
        coll = colls[(placed + i) % len(colls)]
        inst = bpy.data.objects.new(f"EcoTree_{placed:02d}_{coll.name}", None)
        inst.instance_type = "COLLECTION"
        inst.instance_collection = coll
        inst.location = (x, y, gz)
        inst.rotation_euler = (0, 0, (placed * 2.1 + i) % (2 * math.pi))
        s = smin + ((placed * 13 + i * 7) % 100) / 100.0 * (smax - smin)
        inst.scale = (s, s, s)
        bpy.context.scene.collection.objects.link(inst)
        occupied.append((x, y))
        placed += 1
print("placed trees:", placed)

# Near-field fern clusters: linked duplicates of the fern source mesh
# (its material already carries the wind displacement).
fern_src = bpy.data.objects.get("fern_proxy")
ferns = 0
if fern_src is not None:
    for i in range(10):
        spot = find_spot(2.5, 9.0, 1.1, 400, ang_seed=100 + i * 2.3)
        if spot is None:
            continue
        x, y, gz, a = spot
        o = bpy.data.objects.new(f"EcoFern_{i:02d}", fern_src.data)
        o.location = (x, y, gz)
        o.rotation_euler = (0, 0, (i * 2.7) % (2 * math.pi))
        s = 0.8 + (i * 17 % 100) / 100.0 * 0.6
        o.scale = (s, s, s)
        bpy.context.scene.collection.objects.link(o)
        occupied.append((x, y))
        ferns += 1
print("placed ferns:", ferns)

bpy.ops.wm.save_as_mainfile(filepath=out_blend)
print("SAVED", out_blend)
