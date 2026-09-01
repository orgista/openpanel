# Stage 3 (v3, supersedes assemble-ecosystem-v2): ecosystem variety with all
# the bugs found on 2026-08-25 fixed:
#  - collection MEMBERS must NOT be hide_render (that hides them inside the
#    instances too — the v1 AnimTrees never rendered because of this); the
#    source collections are UNLINKED from the scene instead.
#  - Sapling's duplicate CURVE 'tree' objects render un-materialed white on
#    top of the skinned treemesh — deleted.
#  - tree sizes normalized to realistic target heights via empty scale
#    (presets vary wildly: 5.5m..15.8m raw).
#  - variant cycling per species (v2's modulo always picked one variant).
#  - no proxy-fern placement (fern_proxy mesh is a scatter placeholder dome).
#  - traveling gust: world-position phase added to every wind material so
#    gusts sweep through different foliage in sequence (Anthony 2026-08-25).
# Run on the v003 packed scene:
#   blender -b --factory-startup forest_animated_v003_packed.blend \
#     -P assemble-ecosystem-v3.py -- --trees trees_broadleaf_v001.blend \
#     --out forest_animated_v005_packed.blend
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
sc = bpy.context.scene
cam = sc.camera
C = cam.location.copy()
C.z = max(C.z, 1.65)

# ---- 1. append broadleaf collections ----
before = set(bpy.data.collections)
with bpy.data.libraries.load(trees_blend, link=False) as (src, dst):
    dst.collections = [c for c in src.collections if c.startswith("TreeVar_")]
print("appended:", [c.name for c in bpy.data.collections if c not in before])

# ---- 2. sanitize ALL TreeVar collections (old conifer + new broadleaf) ----
def coll_height(coll):
    zmax = 0.0
    for o in coll.objects:
        if o.type == "MESH":
            for corner in o.bound_box:
                zmax = max(zmax, (o.matrix_world @ Vector(corner)).z)
    return zmax


lib_h = {}
for coll in list(bpy.data.collections):
    if not coll.name.startswith("TreeVar_"):
        continue
    for o in list(coll.objects):
        if o.type == "CURVE":
            bpy.data.objects.remove(o, do_unlink=True)
    for o in coll.objects:
        o.hide_render = False
        o.hide_viewport = False
        if o.type == "MESH" and not o.data.materials:
            fb = bpy.data.materials.get("tree_bark") or bpy.data.materials.get("birch_bark")
            if fb:
                o.data.materials.append(fb)
    if coll.name in sc.collection.children:
        sc.collection.children.unlink(coll)
    lib_h[coll.name] = coll_height(coll)
    print(f"sanitized {coll.name}: h={lib_h[coll.name]:.1f}")


def species(sub):
    return sorted(c for c in lib_h if c.startswith("TreeVar_" + sub))


birch = species("white_birch")
aspen = species("quaking_aspen")
maple = species("small_maple") + species("japanese_maple")
conifer = species("douglas_fir") + species("small_pine")

# target heights (m): (base, jitter)
TARGET = {"birch": (10.0, 1.5), "aspen": (9.0, 2.0), "maple": (4.0, 0.8), "conifer": (9.5, 2.0)}

# ---- 3. rescale the existing v1 AnimTrees to realistic conifer heights ----
for i, o in enumerate(sorted((o for o in bpy.data.objects if o.name.startswith("AnimTree_")), key=lambda o: o.name)):
    cname = o.instance_collection.name if o.instance_collection else None
    if cname in lib_h and lib_h[cname] > 0.1:
        base, jit = TARGET["conifer"]
        target = base + ((i * 37) % 100 / 100.0 - 0.5) * 2 * jit
        s = target / lib_h[cname]
        o.scale = (s, s, s)
        print(f"rescaled {o.name}: {lib_h[cname]:.1f}m -> {target:.1f}m")

# ---- 4. occupancy + terrain helpers ----
deps = bpy.context.evaluated_depsgraph_get()
occupied = []
for inst in deps.object_instances:
    nm = inst.object.name
    if any(nm.startswith(k) for k in ("pine_proxy", "fir_proxy", "tree_trunk_proxy", "pine_0", "silver_fir_0")):
        p = inst.matrix_world.translation
        occupied.append((p.x, p.y))
for o in bpy.data.objects:
    if o.name.startswith("AnimTree_") and o.instance_collection:
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
        a = (ang_seed + t * 2.399) % (2 * math.pi)
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


# ---- 5. place the ecosystem ----
PLAN = [
    ("birch", birch, 4, 11.0, 24.0, 2.6),
    ("aspen", aspen, 3, 14.0, 30.0, 2.6),
    ("maple", maple, 3, 6.0, 13.0, 2.2),
    ("conifer", conifer, 4, 10.0, 28.0, 2.6),
]
placed = 0
for label, colls, count, rmin, rmax, clearance in PLAN:
    if not colls:
        continue
    base, jit = TARGET[label]
    for i in range(count):
        spot = find_spot(rmin, rmax, clearance, 400, ang_seed=placed * 1.7 + i)
        if spot is None:
            print("NO SPOT for", label, i)
            continue
        x, y, gz, a = spot
        cname = colls[i % len(colls)]
        target = base + ((placed * 29 + i * 13) % 100 / 100.0 - 0.5) * 2 * jit
        s = target / max(lib_h[cname], 0.1)
        inst = bpy.data.objects.new(f"EcoTree_{placed:02d}_{cname}", None)
        inst.instance_type = "COLLECTION"
        inst.instance_collection = bpy.data.collections[cname]
        inst.location = (x, y, gz)
        inst.rotation_euler = (0, 0, (placed * 2.1 + i) % (2 * math.pi))
        inst.scale = (s, s, s)
        sc.collection.objects.link(inst)
        occupied.append((x, y))
        placed += 1
        print(f"placed {label}: {cname} at r={math.dist((x, y), (C.x, C.y)):.0f}m h={target:.1f}m")
print("placed trees:", placed)

# ---- 6. traveling gust sweep on every wind material ----
edited = 0
for mat in bpy.data.materials:
    nt = mat.node_tree
    if nt is None:
        continue
    phase = nt.nodes.get("wind_phase")
    if phase is None or nt.nodes.get("wind_sweep_add"):
        continue
    info = nt.nodes.get("wind_objinfo")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    sep.name = "wind_sweep_sep"
    nt.links.new(sep.inputs[0], info.outputs["Location"])
    mx = nt.nodes.new("ShaderNodeMath"); mx.name = "wind_sweep_x"
    mx.operation = "MULTIPLY"; mx.inputs[1].default_value = 0.22
    nt.links.new(mx.inputs[0], sep.outputs["X"])
    my = nt.nodes.new("ShaderNodeMath"); my.name = "wind_sweep_y"
    my.operation = "MULTIPLY"; my.inputs[1].default_value = 0.13
    nt.links.new(my.inputs[0], sep.outputs["Y"])
    mxy = nt.nodes.new("ShaderNodeMath"); mxy.name = "wind_sweep_xy"
    mxy.operation = "ADD"
    nt.links.new(mxy.inputs[0], mx.outputs[0])
    nt.links.new(mxy.inputs[1], my.outputs[0])
    total = nt.nodes.new("ShaderNodeMath"); total.name = "wind_sweep_add"
    total.operation = "ADD"
    nt.links.new(total.inputs[0], phase.outputs[0])
    nt.links.new(total.inputs[1], mxy.outputs[0])
    for cname in ("wind_ph_a", "wind_ph_b"):
        cn = nt.nodes.get(cname)
        if cn is not None:
            nt.links.new(cn.inputs[0], total.outputs[0])
    edited += 1
print("gust sweep on", edited, "materials")

bpy.ops.wm.save_as_mainfile(filepath=out_blend)
print("SAVED", out_blend)
