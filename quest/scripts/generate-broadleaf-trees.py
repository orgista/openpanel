# Generates the BROADLEAF/understory tree library for ecosystem variety
# (Anthony 2026-08-25: "add or change the tree types so there is variety and
# foliage"). Same loop-perfect bone-driver wind rig as
# generate-animated-trees.py; broadleaf leaf material gets translucency so
# canopies glow backlit.
# Run WITHOUT --factory-startup (needs the sapling_tree_gen extension):
#   blender -b -P generate-broadleaf-trees.py -- --out trees_broadleaf_v001.blend
import ast
import hashlib
import os
import sys

import bpy

argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
out_blend = argv[argv.index("--out") + 1] if "--out" in argv else "/tmp/trees_broadleaf_v001.blend"
LOOP_FRAMES = 240

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.preferences.addon_enable(module="bl_ext.blender_org.sapling_tree_gen")

import bl_ext.blender_org.sapling_tree_gen as sapling_mod

PRESET_DIR = os.path.join(os.path.dirname(sapling_mod.__file__), "presets")


def load_preset(name):
    with open(os.path.join(PRESET_DIR, name + ".py")) as f:
        body = f.read()
    return ast.literal_eval(body[body.index("{") :])


def bark(name, base, marks=None):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = base
    b.inputs["Roughness"].default_value = 0.9
    n = m.node_tree.nodes.new("ShaderNodeTexNoise")
    n.inputs["Scale"].default_value = 14
    if marks:  # birch: dark horizontal lenticel marks
        ramp = m.node_tree.nodes.new("ShaderNodeValToRGB")
        ramp.color_ramp.elements[0].position = 0.62
        ramp.color_ramp.elements[0].color = base
        ramp.color_ramp.elements[1].position = 0.72
        ramp.color_ramp.elements[1].color = marks
        m.node_tree.links.new(ramp.inputs["Fac"], n.outputs["Fac"])
        m.node_tree.links.new(b.inputs["Base Color"], ramp.outputs["Color"])
    bump = m.node_tree.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.3
    m.node_tree.links.new(bump.inputs["Height"], n.outputs["Fac"])
    m.node_tree.links.new(b.inputs["Normal"], bump.outputs["Normal"])
    return m


def leaves(name, dark, light):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    b = nt.nodes["Principled BSDF"]
    b.inputs["Roughness"].default_value = 0.6
    info = nt.nodes.new("ShaderNodeObjectInfo")
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = dark
    ramp.color_ramp.elements[1].color = light
    nt.links.new(ramp.inputs["Fac"], info.outputs["Random"])
    nt.links.new(b.inputs["Base Color"], ramp.outputs["Color"])
    # backlit glow — broadleaf canopies transmit light
    tr = nt.nodes.new("ShaderNodeBsdfTranslucent")
    nt.links.new(tr.inputs["Color"], ramp.outputs["Color"])
    mix = nt.nodes.new("ShaderNodeMixShader")
    mix.inputs["Fac"].default_value = 0.4
    out = next(n for n in nt.nodes if n.type == "OUTPUT_MATERIAL")
    nt.links.new(mix.inputs[1], out.inputs["Surface"].links[0].from_socket)
    nt.links.new(mix.inputs[2], tr.outputs[0])
    nt.links.new(out.inputs["Surface"], mix.outputs[0])
    return m


birch_bark = bark("birch_bark", (0.72, 0.70, 0.65, 1), marks=(0.08, 0.07, 0.06, 1))
aspen_bark = bark("aspen_bark", (0.55, 0.56, 0.48, 1))
maple_bark = bark("maple_bark", (0.16, 0.11, 0.08, 1))
birch_leaves = leaves("birch_leaves", (0.06, 0.14, 0.03, 1), (0.16, 0.26, 0.05, 1))
aspen_leaves = leaves("aspen_leaves", (0.08, 0.16, 0.03, 1), (0.20, 0.30, 0.06, 1))
maple_leaves = leaves("maple_leaves", (0.05, 0.12, 0.03, 1), (0.14, 0.20, 0.04, 1))

VARIANTS = [
    ("white_birch", 5, 1.00, birch_bark, birch_leaves),
    ("white_birch", 17, 0.80, birch_bark, birch_leaves),
    ("quaking_aspen", 9, 1.00, aspen_bark, aspen_leaves),
    ("quaking_aspen", 21, 0.75, aspen_bark, aspen_leaves),
    ("small_maple", 13, 0.90, maple_bark, maple_leaves),
    ("japanese_maple", 29, 0.65, maple_bark, maple_leaves),
]

for preset_name, seed, scale_mult, bark_m, leaf_m in VARIANTS:
    kwargs = load_preset(preset_name)
    kwargs.update(
        do_update=True, seed=seed, showLeaves=True, makeMesh=True,
        useArm=True, armAnim=False, leafAnim=False, armLevels=2,
        scale=kwargs.get("scale", 10.0) * scale_mult,
    )
    valid = set(bpy.ops.curve.tree_add.get_rna_type().properties.keys())
    kwargs = {k: v for k, v in kwargs.items() if k in valid}
    before = set(bpy.data.objects)
    bpy.ops.curve.tree_add(**kwargs)
    created = [o for o in bpy.data.objects if o not in before]
    coll = bpy.data.collections.new(f"TreeVar_{preset_name}_{seed}")
    bpy.context.scene.collection.children.link(coll)
    for o in created:
        for c in list(o.users_collection):
            c.objects.unlink(o)
        coll.objects.link(o)
        if o.type == "MESH":
            target = leaf_m if "leaf" in o.name.lower() else bark_m
            if not o.data.materials:
                o.data.materials.append(target)
            else:
                o.data.materials[0] = target
    print("VARIANT", preset_name, seed, [f"{o.name}({o.type})" for o in created])

driver_count = 0
for obj in bpy.data.objects:
    if obj.type != "ARMATURE":
        continue
    for pb in obj.pose.bones:
        depth = 0
        walker = pb
        while walker.parent is not None:
            depth += 1
            walker = walker.parent
        # broadleaf flutters a touch more than conifer
        amp = min(0.006 + 0.008 * depth, 0.04)
        h = int(hashlib.md5((obj.name + pb.name).encode()).hexdigest()[:6], 16)
        phase = (h % 628) / 100.0
        for axis in (0, 2):
            fc = obj.driver_add(f'pose.bones["{pb.name}"].rotation_euler', axis)
            k1, k2 = 2, 9
            fc.driver.expression = (
                f"{amp:.4f}*sin(2*pi*frame*{k1}/{LOOP_FRAMES}+{phase:.3f})"
                f"+{amp * 0.4:.4f}*sin(2*pi*frame*{k2}/{LOOP_FRAMES}+{phase * 1.7:.3f})"
            )
            driver_count += 1
print("WIND-DRIVERS:", driver_count)
bpy.ops.wm.save_as_mainfile(filepath=out_blend)
print("SAVED", out_blend)
