# Builds OpenPanel VR's native 3D near-field environment (Anthony 2026-08-24:
# "should be a native 3d env not a 360 photo").
#
# Approach: REAL geometry from the Blender scene (terrain patch + hand-placed
# rocks within a radius of the viewpoint), textured by REPROJECTING the
# existing 8K equirect render onto it. Every surface point samples the skybox
# pixel it occupies as seen from the origin, so the near geometry matches the
# far backdrop pixel-for-pixel by construction — no baking, no seams. Near
# field gains true stereo depth (and correct parallax silhouettes for rocks);
# the dome keeps carrying everything distant, where stereo can't tell anyway.
#
# Caveat by design: textures are projected from the viewpoint, so surfaces
# not visible from the origin stretch — fine for a launcher whose user stays
# near the origin.
#
# Usage:
#   blender -b --factory-startup polyhaven_pine_fir_forest.blend \
#       -P export-near-environment.py -- --out near_environment.glb
import bpy
import bmesh
import math
import sys

argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []


def arg(name, default=None):
    if name in argv:
        i = argv.index(name)
        if i + 1 < len(argv):
            return argv[i + 1]
    return default


out_path = arg("--out", "/tmp/near_environment.glb")
NEAR_RADIUS = float(arg("--radius", "25.0"))
SKYBOX_JPG = (
    "/Volumes/Files/Projects/Code, Apps & Websites/Apps/OpenPanel/quest/"
    "app/src/main/res/drawable-nodpi/skydome_pine_forest_v2.jpg"
)

# MUST match render-pine-forest-360.py: camera at scene camera XY, z >= 1.65.
scene_cam = bpy.context.scene.camera
C = scene_cam.location.copy()
C.z = max(C.z, 1.65)
yaw = scene_cam.rotation_euler.z
cy, sy = math.cos(yaw), math.sin(yaw)

deps = bpy.context.evaluated_depsgraph_get()

SOURCES = ["terrain_main"] + [
    o.name for o in bpy.data.objects if o.name.startswith("rock_moss_hand_placed")
]

bm = bmesh.new()
kept_sources = []
for name in SOURCES:
    src_obj = bpy.data.objects.get(name)
    if src_obj is None:
        continue
    ev = src_obj.evaluated_get(deps)
    mesh = bpy.data.meshes.new_from_object(ev, depsgraph=deps)
    mesh.transform(src_obj.matrix_world)
    # Quick reject: object bounding sphere vs near radius.
    import mathutils

    center = sum((mathutils.Vector(b) for b in src_obj.bound_box), mathutils.Vector()) / 8.0
    center = src_obj.matrix_world @ center
    if name != "terrain_main" and (center - C).length > NEAR_RADIUS + 6.0:
        bpy.data.meshes.remove(mesh)
        continue
    tmp = bmesh.new()
    tmp.from_mesh(mesh)
    # Crop to the near radius (terrain gets a margin so its rim tucks well
    # behind where stereo fades rather than cutting a visible cliff).
    limit = NEAR_RADIUS if name == "terrain_main" else NEAR_RADIUS + 6.0
    drop = [v for v in tmp.verts if (v.co.xy - C.xy).length > limit]
    bmesh.ops.delete(tmp, geom=drop, context="VERTS")
    if len(tmp.faces) > 0:
        tmp_mesh = bpy.data.meshes.new(f"crop_{name}")
        tmp.to_mesh(tmp_mesh)
        bm.from_mesh(tmp_mesh)
        bpy.data.meshes.remove(tmp_mesh)
        kept_sources.append((name, len(tmp.verts)))
    tmp.free()
    bpy.data.meshes.remove(mesh)

print("kept:", kept_sources)

# App-space transform (same convention as the water exporter).
for v in bm.verts:
    rel = v.co - C
    x_app = rel.x * cy + rel.y * sy
    y_app = rel.z
    z_app = rel.x * sy - rel.y * cy
    v.co = (x_app, -z_app, y_app)

# Equirect reprojection UVs. In app space the skybox image center faces -Z:
# u = 0.5 + atan2(x, -z) / 2pi, v = 0.5 - asin(y_norm) / pi, computed per
# LOOP so faces crossing the u-seam behind the viewer can be handled.
# The source meshes ship their own UV layers; glTF exports TEXCOORD_0, so
# the projection MUST land in the first layer (a fresh .new() would become
# TEXCOORD_1 and never render — cost one full debug loop to learn).
while len(bm.loops.layers.uv) > 0:
    bm.loops.layers.uv.remove(bm.loops.layers.uv[0])
uv_layer = bm.loops.layers.uv.new("UVMap")


def equirect_uv(co):
    x, y_bl, z_bl = co.x, co.y, co.z  # blender-space: y_bl = -z_app, z_bl = y_app
    x_app, y_app, z_app = x, z_bl, -y_bl
    r = math.sqrt(x_app * x_app + y_app * y_app + z_app * z_app)
    if r < 1e-6:
        return (0.5, 0.5)
    u = 0.5 + math.atan2(x_app, -z_app) / (2 * math.pi)
    # Blender UV space is bottom-up; the glTF exporter flips it, so author v
    # bottom-up here to land image-space top-down after export.
    v = 0.5 + math.asin(max(-1.0, min(1.0, y_app / r))) / math.pi
    return (u, v)


for f in bm.faces:
    uvs = [equirect_uv(loop.vert.co) for loop in f.loops]
    # Seam handling: if a face spans the u wrap, shift low-u loops up by 1.
    us = [uv[0] for uv in uvs]
    if max(us) - min(us) > 0.5:
        uvs = [(u + 1.0 if u < 0.5 else u, v) for (u, v) in uvs]
    for loop, uv in zip(f.loops, uvs):
        loop[uv_layer].uv = uv

out_mesh = bpy.data.meshes.new("near_environment")
bm.to_mesh(out_mesh)
bm.free()

obj = bpy.data.objects.new("near_environment", out_mesh)
mat = bpy.data.materials.new("near_env_projected")
mat.use_nodes = True
bsdf = mat.node_tree.nodes["Principled BSDF"]
# Unlit look: feed the projected photo through emission so scene lighting
# cannot double-shade the already-lit render.
bsdf.inputs["Emission Strength"].default_value = 1.0
tex_node = mat.node_tree.nodes.new("ShaderNodeTexImage")
tex_node.image = bpy.data.images.load(SKYBOX_JPG)
# Wrap horizontally for the seam-shifted UVs.
tex_node.extension = "REPEAT"
mat.node_tree.links.new(bsdf.inputs["Emission Color"], tex_node.outputs["Color"])
mat.node_tree.links.new(bsdf.inputs["Base Color"], tex_node.outputs["Color"])
out_mesh.materials.append(mat)

bpy.context.scene.collection.objects.link(obj)
bpy.context.view_layer.objects.active = obj
dec = obj.modifiers.new("dec", "DECIMATE")
dec.ratio = max(0.05, min(1.0, 30000.0 / max(len(out_mesh.polygons), 1)))
bpy.ops.object.modifier_apply(modifier="dec")
print("faces after decimate:", len(obj.data.polygons))

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
    export_image_format="JPEG",
)
print("exported:", out_path)
