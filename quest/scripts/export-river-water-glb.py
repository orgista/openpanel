# Exports the pine-forest scene's ACTUAL river water surface (the
# `river_water` curve + geometry-nodes ribbon) as a Quest-ready GLB in
# OpenPanel's world space: origin = the 360-render camera, -Z = the skybox
# image center. The app overlays its animated glint material on THIS mesh, so
# water sparkle exists only where the river actually is — no more full-scene
# shimmer planes (Anthony: "fake waves ... an overlay on all the textures").
#
# Usage:
#   blender -b --factory-startup polyhaven_pine_fir_forest.blend \
#       -P export-river-water-glb.py -- --out river_water_surface.glb
import bpy
import bmesh
import math
import sys

argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
out_path = argv[argv.index("--out") + 1] if "--out" in argv else "/tmp/river_water_surface.glb"

# MUST match render-pine-forest-360.py: camera at scene camera XY, z >= 1.65.
scene_cam = bpy.context.scene.camera
C = scene_cam.location.copy()
C.z = max(C.z, 1.65)
yaw = scene_cam.rotation_euler.z
cy, sy = math.cos(yaw), math.sin(yaw)

MAX_RADIUS = 18.0   # glints matter only near the viewer; distant/elevated
# upstream stretches have no terrain to occlude them and float over panels
UV_METERS = 9.0     # planar UV scale: one texture tile per this many meters
LIFT = 0.02         # sit glints just above the baked water surface

deps = bpy.context.evaluated_depsgraph_get()
src = bpy.data.objects["river_water"].evaluated_get(deps)
mesh = bpy.data.meshes.new_from_object(src, depsgraph=deps)
mesh.transform(bpy.data.objects["river_water"].matrix_world)
print("evaluated river_water:", len(mesh.vertices), "verts,", len(mesh.polygons), "polys")

bm = bmesh.new()
bm.from_mesh(mesh)

# The GN ribbon is far wider than the visible channel — in the scene the
# terrain hides the margins, but the app has no terrain mesh, so unclipped
# glints spill over banks and rocks (Anthony's capture, 0.3.45). Keep only
# water that is ABOVE the terrain surface at its own XY: that IS the visible
# river.
from mathutils.bvhtree import BVHTree
from mathutils import Vector

terrain_src = bpy.data.objects["terrain_main"].evaluated_get(deps)
terrain_bvh = BVHTree.FromObject(terrain_src, deps)
DROP_MARGIN = 0.04  # water this far below terrain = hidden under the bank

hidden = []
for v in bm.verts:
    origin = Vector((v.co.x, v.co.y, v.co.z + 80.0))
    hit = terrain_bvh.ray_cast(origin, Vector((0, 0, -1)), 200.0)
    terrain_z = hit[0].z if hit[0] is not None else None
    if terrain_z is not None and v.co.z < terrain_z - DROP_MARGIN:
        hidden.append(v)
bmesh.ops.delete(bm, geom=hidden, context="VERTS")
bm.verts.ensure_lookup_table()
print("after terrain clip:", len(bm.verts), "verts,", len(bm.faces), "faces")

# App-space transform baked into vertices. glTF export converts Z-up to Y-up
# as (x, y, z)_blender -> (x, z, -y)_gltf, so we pre-place vertices at
# (x_app, -z_app, y_app) in Blender space to land at (x_app, y_app, z_app).
for v in bm.verts:
    rel = v.co - C
    x_app = rel.x * cy + rel.y * sy
    y_app = rel.z + LIFT
    z_app = rel.x * sy - rel.y * cy
    v.co = (x_app, -z_app, y_app)

kill = [
    v
    for v in bm.verts
    if (v.co.x * v.co.x + v.co.y * v.co.y) > MAX_RADIUS * MAX_RADIUS
    # height gate in app space (stored as z pre-export): water must sit below
    # the viewer's chest — elevated upstream water reads as floating sheets.
    or v.co.z > -0.15
    or v.co.z < -4.0
]
bmesh.ops.delete(bm, geom=kill, context="VERTS")
bm.verts.ensure_lookup_table()
print("after radius filter:", len(bm.verts), "verts,", len(bm.faces), "faces")

# World-planar UVs so the app's repeat/offset animation scrolls in meters.
# Computed from TRUE water positions, BEFORE dome projection, so the pattern's
# angular scale from the viewpoint matches real near-field water.
uv_layer = bm.loops.layers.uv.new("UVMap")
for f in bm.faces:
    for loop in f.loops:
        loop[uv_layer].uv = (loop.vert.co.x / UV_METERS, -loop.vert.co.y / UV_METERS)

# Optional radial projection to a far sphere (--dome-radius R). Default OFF:
# with the real 3D near-field environment (export-near-environment.py) the
# water belongs at its TRUE depth, matching the terrain around it.
DOME_PROJECT_RADIUS = float(arg("--dome-radius", "0"))
if DOME_PROJECT_RADIUS > 0:
    for v in bm.verts:
        d = v.co.length
        if d > 1e-6:
            v.co = v.co * (DOME_PROJECT_RADIUS / d)

out_mesh = bpy.data.meshes.new("river_water_surface")
bm.to_mesh(out_mesh)
bm.free()

obj = bpy.data.objects.new("river_water_surface", out_mesh)
# Embed the app's glint texture so the surface renders correctly even before
# the runtime Material override (which adds the UV animation) kicks in.
mat = bpy.data.materials.new("river_glint")
mat.use_nodes = True
mat.blend_method = "BLEND"
bsdf = mat.node_tree.nodes["Principled BSDF"]
tex_node = mat.node_tree.nodes.new("ShaderNodeTexImage")
tex_path = (
    "/Volumes/Files/Projects/Code, Apps & Websites/Apps/OpenPanel/quest/"
    "app/src/main/res/drawable-nodpi/water_shimmer_fade_v3.png"
)
tex_node.image = bpy.data.images.load(tex_path)
mat.node_tree.links.new(bsdf.inputs["Base Color"], tex_node.outputs["Color"])
mat.node_tree.links.new(bsdf.inputs["Alpha"], tex_node.outputs["Alpha"])
out_mesh.materials.append(mat)

# The GN ribbon is render-dense; a translucent glint overlay needs nowhere
# near that. Decimate to a Quest-friendly budget (~8k faces).
bpy.context.scene.collection.objects.link(obj)
bpy.context.view_layer.objects.active = obj
dec = obj.modifiers.new("dec", "DECIMATE")
dec.ratio = max(0.02, min(1.0, 8000.0 / max(len(out_mesh.polygons), 1)))
bpy.ops.object.modifier_apply(modifier="dec")
print("after decimate:", len(obj.data.polygons), "faces")

bpy.ops.object.select_all(action="DESELECT")
obj.select_set(True)
bpy.context.view_layer.objects.active = obj

bpy.ops.export_scene.gltf(
    filepath=out_path,
    use_selection=True,
    export_format="GLB",
    export_materials="EXPORT",
    export_yup=True,
    export_apply=True,
)
print("exported:", out_path)
