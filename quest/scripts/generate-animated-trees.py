# Generates the animated Sapling tree library for the 360-video forest
# (guide video review 4: Sapling Tree Gen with built-in armature wind).
# Run WITHOUT --factory-startup (needs the sapling_tree_gen extension):
#   blender -b -P generate-animated-trees.py -- --out trees_animated_v001.blend
import ast
import os
import sys

import bpy

argv = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
out_blend = argv[argv.index("--out") + 1] if "--out" in argv else "/tmp/trees_animated_v001.blend"
LOOP_FRAMES = 240  # matches the hyper-HD clip length so sway loops seamlessly

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.preferences.addon_enable(module="bl_ext.blender_org.sapling_tree_gen")

import bl_ext.blender_org.sapling_tree_gen as sapling_mod

PRESET_DIR = os.path.join(os.path.dirname(sapling_mod.__file__), "presets")


def load_preset(name):
    with open(os.path.join(PRESET_DIR, name + ".py")) as f:
        body = f.read()
    return ast.literal_eval(body[body.index("{") :])


def bark_material():
    m = bpy.data.materials.new("tree_bark")
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (0.11, 0.075, 0.05, 1)
    b.inputs["Roughness"].default_value = 0.95
    n = m.node_tree.nodes.new("ShaderNodeTexNoise")
    n.inputs["Scale"].default_value = 18
    bump = m.node_tree.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.35
    m.node_tree.links.new(bump.inputs["Height"], n.outputs["Fac"])
    m.node_tree.links.new(b.inputs["Normal"], bump.outputs["Normal"])
    return m


def needle_material():
    m = bpy.data.materials.new("tree_needles")
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (0.045, 0.11, 0.045, 1)
    b.inputs["Roughness"].default_value = 0.7
    # subtle per-leaf variation
    obj_info = m.node_tree.nodes.new("ShaderNodeObjectInfo")
    ramp = m.node_tree.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (0.04, 0.10, 0.04, 1)
    ramp.color_ramp.elements[1].color = (0.10, 0.16, 0.05, 1)
    m.node_tree.links.new(ramp.inputs["Fac"], obj_info.outputs["Random"])
    m.node_tree.links.new(b.inputs["Base Color"], ramp.outputs["Color"])
    return m


bark = bark_material()
needles = needle_material()

VARIANTS = [
    ("douglas_fir", 1, 1.00), ("douglas_fir", 7, 0.85), ("douglas_fir", 23, 1.15),
    ("small_pine", 3, 1.00), ("small_pine", 11, 1.25), ("small_pine", 31, 0.8),
]

for preset_name, seed, scale_mult in VARIANTS:
    kwargs = load_preset(preset_name)
    kwargs.update(
        do_update=True, seed=seed, showLeaves=True, makeMesh=True,
        useArm=True, armAnim=False, leafAnim=False, armLevels=2,  # Sapling's own
        # anim uses legacy Action.fcurves (removed in Blender 5.x) and crashes;
        # we add loop-perfect bone-driver wind ourselves below.
        wind=0.3, gust=0.4, gustF=0.08,
        scale=kwargs.get("scale", 13.0) * scale_mult,
    )
    # operator only accepts known props; filter against rna
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
            target = needles if "leaf" in o.name.lower() else bark
            if not o.data.materials:
                o.data.materials.append(target)
            else:
                o.data.materials[0] = target
    print("VARIANT", preset_name, seed, [f"{o.name}({o.type})" for o in created])

# Loop-perfect wind: sine drivers on every pose bone, phased per bone,
# amplitude growing with branch depth, frequencies = integer cycles per
# LOOP_FRAMES so the clip loops seamlessly.
import hashlib
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
        amp = min(0.005 + 0.007 * depth, 0.035)
        h = int(hashlib.md5((obj.name + pb.name).encode()).hexdigest()[:6], 16)
        phase = (h % 628) / 100.0
        for axis in (0, 2):
            fc = obj.driver_add(f'pose.bones["{pb.name}"].rotation_euler', axis)
            k1, k2 = 2, 7  # gust + flutter cycles per loop
            fc.driver.expression = (
                f"{amp:.4f}*sin(2*pi*frame*{k1}/{LOOP_FRAMES}+{phase:.3f})"
                f"+{amp * 0.4:.4f}*sin(2*pi*frame*{k2}/{LOOP_FRAMES}+{phase * 1.7:.3f})"
            )
            driver_count += 1
print("WIND-DRIVERS:", driver_count)
bpy.ops.wm.save_as_mainfile(filepath=out_blend)
print("SAVED", out_blend)
