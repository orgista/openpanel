# Adds WIND and distance-falloff WATER to the packed forest scene, entirely
# in MATERIALS — the tree scatter is frozen inside terrain_main's geometry
# nodes (object rotation, modifiers, even mesh-data edits do NOT propagate;
# each proven empirically 2026-08-25). Material displacement is evaluated
# per instance at render time, so it is the only wind mechanism that works.
#
# Wind (per tree material): world offset = frac * z_obj * t * (sin th1 + .5 sin th2)
#   z_obj = object-space height in meters (origins at base) -> amplitude
#   scales with real tree size even though materials are shared across LODs.
#   t = Generated.Z (0..1 bbox) -> crowns move, trunk bases stay planted.
#   th_i use INTEGER cycles per 240-frame loop (k=4 & 7 trees, 11 & 17 cover)
#   -> seamless loop. Phase = ObjectInfo.Random * 2pi -> per-instance phase.
#   Same formula in every material slot of an object -> bark/twigs/leaves
#   deform coherently.
# Water (river_flow): the three frame-driven flow scalars are multiplied by
#   a Camera Data->View Distance falloff (1.0 near, ~0.18 far; W-evolution
#   tapers only to 0.45) — Anthony 2026-08-25: near water is right, distant
#   water must slow ("the further the water goes the less we see it moving").
#
# Usage: blender -b --factory-startup forest_animated_v001_packed.blend \
#          -P add-wind-and-water-falloff.py -- --out forest_animated_v002_packed.blend
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


OUT = arg("--out", "forest_animated_v002_packed.blend")
LOOP = 240

TREE_MATS = [
    "proxy_bark", "proxy_leaves_fir", "proxy_leaves_pine", "leaves",
    "pine_bark", "pine_twig", "pine_dead_branches",
    "fir_bark", "fir_twig", "fir_dead_branches",
    "tree_trunk.001", "tree_trunk_proxy_lod1",
    "dead_tree", "dead_tree_proxy", "dead_tree_proxy_lod1",
]
COVER_MATS = ["fern_proxy", "grass_proxy", "grass_proxy_lod1", "pine_cover"]

TREE = dict(frac=0.020, k1=4, k2=7)
COVER = dict(frac=0.060, k1=11, k2=17)


def add_wind(mat, frac, k1, k2):
    nt = mat.node_tree
    if nt is None:
        return False
    if any(n.name.startswith("wind_") for n in nt.nodes):
        return False  # already wired
    out = next((n for n in nt.nodes if n.type == "OUTPUT_MATERIAL" and n.is_active_output), None)
    if out is None:
        return False

    def node(t, name, x, y):
        n = nt.nodes.new(t)
        n.name = name
        n.location = (x, y)
        return n

    frame = node("ShaderNodeValue", "wind_frame", -1400, -600)
    fc = frame.outputs[0].driver_add("default_value")
    fc.driver.expression = "frame"

    info = node("ShaderNodeObjectInfo", "wind_objinfo", -1400, -800)
    texco = node("ShaderNodeTexCoord", "wind_texco", -1400, -1000)
    sep_gen = node("ShaderNodeSeparateXYZ", "wind_sep_gen", -1200, -1000)
    nt.links.new(sep_gen.inputs[0], texco.outputs["Generated"])
    sep_obj = node("ShaderNodeSeparateXYZ", "wind_sep_obj", -1200, -1150)
    nt.links.new(sep_obj.inputs[0], texco.outputs["Object"])

    phase = node("ShaderNodeMath", "wind_phase", -1200, -800)
    phase.operation = "MULTIPLY"
    phase.inputs[1].default_value = 2 * math.pi
    nt.links.new(phase.inputs[0], info.outputs["Random"])

    def wave(k, name, y, phase_scale):
        w = node("ShaderNodeMath", f"wind_w_{name}", -1000, y)
        w.operation = "MULTIPLY"
        w.inputs[1].default_value = 2 * math.pi * k / LOOP
        nt.links.new(w.inputs[0], frame.outputs[0])
        ph = node("ShaderNodeMath", f"wind_ph_{name}", -1000, y - 120)
        ph.operation = "MULTIPLY"
        ph.inputs[1].default_value = phase_scale
        nt.links.new(ph.inputs[0], phase.outputs[0])
        add = node("ShaderNodeMath", f"wind_add_{name}", -850, y)
        add.operation = "ADD"
        nt.links.new(add.inputs[0], w.outputs[0])
        nt.links.new(add.inputs[1], ph.outputs[0])
        s = node("ShaderNodeMath", f"wind_sin_{name}", -700, y)
        s.operation = "SINE"
        nt.links.new(s.inputs[0], add.outputs[0])
        return s

    s1 = wave(k1, "a", -600, 1.0)
    s2 = wave(k2, "b", -850, 1.7)
    half = node("ShaderNodeMath", "wind_half", -550, -850)
    half.operation = "MULTIPLY"
    half.inputs[1].default_value = 0.5
    nt.links.new(half.inputs[0], s2.outputs[0])
    osc = node("ShaderNodeMath", "wind_osc", -400, -700)
    osc.operation = "ADD"
    nt.links.new(osc.inputs[0], s1.outputs[0])
    nt.links.new(osc.inputs[1], half.outputs[0])

    amp = node("ShaderNodeMath", "wind_amp", -400, -950)  # frac * z_obj
    amp.operation = "MULTIPLY"
    amp.inputs[1].default_value = frac
    nt.links.new(amp.inputs[0], sep_obj.outputs["Z"])
    amp2 = node("ShaderNodeMath", "wind_amp2", -250, -950)  # * t (bbox height frac)
    amp2.operation = "MULTIPLY"
    nt.links.new(amp2.inputs[0], amp.outputs[0])
    nt.links.new(amp2.inputs[1], sep_gen.outputs["Z"])

    dx = node("ShaderNodeMath", "wind_dx", -250, -700)
    dx.operation = "MULTIPLY"
    nt.links.new(dx.inputs[0], osc.outputs[0])
    nt.links.new(dx.inputs[1], amp2.outputs[0])

    vec = node("ShaderNodeCombineXYZ", "wind_vec", -100, -800)
    nt.links.new(vec.inputs["X"], dx.outputs[0])

    disp_in = out.inputs["Displacement"]
    if disp_in.is_linked:
        prev = disp_in.links[0].from_socket
        comb = node("ShaderNodeVectorMath", "wind_combine", -50, -950)
        comb.operation = "ADD"
        nt.links.new(comb.inputs[0], prev)
        nt.links.new(comb.inputs[1], vec.outputs[0])
        nt.links.new(disp_in, comb.outputs[0])
    else:
        nt.links.new(disp_in, vec.outputs[0])

    mat.displacement_method = "BOTH"
    return True


wired = []
for name in TREE_MATS:
    m = bpy.data.materials.get(name)
    if m and add_wind(m, **TREE):
        wired.append(name)
for name in COVER_MATS:
    m = bpy.data.materials.get(name)
    if m and add_wind(m, **COVER):
        wired.append(name)
print("WIND wired:", len(wired), wired)

# ---- water distance falloff ----
mat = bpy.data.materials["river_flow"]
nt = mat.node_tree

cam = nt.nodes.new("ShaderNodeCameraData")
cam.name = "falloff_cam"
cam.location = (-1600, 600)
mr = nt.nodes.new("ShaderNodeMapRange")
mr.name = "falloff_map"
mr.location = (-1400, 600)
mr.interpolation_type = "SMOOTHSTEP"
mr.inputs["From Min"].default_value = 2.2
mr.inputs["From Max"].default_value = 15.0
mr.inputs["To Min"].default_value = 1.0
mr.inputs["To Max"].default_value = 0.18
mr.clamp = True
nt.links.new(mr.inputs["Value"], cam.outputs["View Distance"])
# gentler taper for the W evolution (shimmer still lives far away)
mr2 = nt.nodes.new("ShaderNodeMapRange")
mr2.name = "falloff_map_w"
mr2.location = (-1400, 350)
mr2.interpolation_type = "SMOOTHSTEP"
mr2.inputs["From Min"].default_value = 2.2
mr2.inputs["From Max"].default_value = 15.0
mr2.inputs["To Min"].default_value = 1.0
mr2.inputs["To Max"].default_value = 0.45
mr2.clamp = True
nt.links.new(mr2.inputs["Value"], cam.outputs["View Distance"])

# rewire each frame-driven scalar: Value(driver) * falloff -> original socket
DRIVEN = [
    ("Vector Math", 1, 0, "frame/100", mr),    # x offset, slow layer
    ("Vector Math.001", 1, 0, "frame/50", mr), # x offset, fast layer
    ("Noise Texture", 1, None, "frame/150", mr2),  # W evolution (scalar socket)
]
# drop the old direct drivers first
for fc in list(nt.animation_data.drivers):
    nt.driver_remove(fc.data_path, fc.array_index)
for i, (nname, sock_i, comp, expr, falloff) in enumerate(DRIVEN):
    tgt = nt.nodes[nname]
    val = nt.nodes.new("ShaderNodeValue")
    val.name = f"flow_t_{i}"
    val.location = (-1600, 100 - 220 * i)
    fc = val.outputs[0].driver_add("default_value")
    fc.driver.expression = expr
    mul = nt.nodes.new("ShaderNodeMath")
    mul.name = f"flow_mul_{i}"
    mul.operation = "MULTIPLY"
    mul.location = (-1250, 100 - 220 * i)
    nt.links.new(mul.inputs[0], val.outputs[0])
    nt.links.new(mul.inputs[1], falloff.outputs[0])
    if comp is None:
        nt.links.new(tgt.inputs[sock_i], mul.outputs[0])
    else:
        cmb = nt.nodes.new("ShaderNodeCombineXYZ")
        cmb.name = f"flow_vec_{i}"
        cmb.location = (-1050, 100 - 220 * i)
        nt.links.new(cmb.inputs[comp], mul.outputs[0])
        nt.links.new(tgt.inputs[sock_i], cmb.outputs[0])
print("WATER falloff wired (3 driven scalars re-routed)")

bpy.ops.wm.save_as_mainfile(filepath=bpy.path.abspath("//" + OUT))
print("SAVED", OUT)
