# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""The knife switch — the one fully rendered object in Zales.

Everything else in the app is stylised and dithered. This is the exception, on
purpose: a physically modelled, path-traced Soviet-era knife switch, so the only
thing you can touch is also the only thing that looks real (docs/DESIGN.md).

Mechanics follow real practice: the hinge is at the bottom and the jaws at the
top, so an open blade falls away by gravity and can never close on its own.
Closing means lifting the handle UP into the jaws.

Run headless with Blender 5.x:

    blender -b -P art/switch/switch.py -- --out <dir> [--frames 48] [--samples 256]
    blender -b -P art/switch/switch.py -- --preview <file.png> --angle 35

Units: geometry is written in centimetres through `cm()` and stored in metres,
so light falloff and depth of field behave physically.
"""

from __future__ import annotations

import argparse
import math
import os
import sys
import zlib

import bmesh
import bpy
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Matrix, Vector

# ── Palette (docs/DESIGN.md §3) ─────────────────────────────────────────────

SLATE = (0.035, 0.043, 0.048)
COPPER = (0.72, 0.36, 0.17)
VERDIGRIS = (0.18, 0.36, 0.30)
BRASS = (0.62, 0.43, 0.17)
CARBOLITE = (0.11, 0.035, 0.022)
STEEL = (0.42, 0.44, 0.46)
PAINT = (0.70, 0.66, 0.58)
PAPER = (0.70, 0.60, 0.40)
INK = (0.03, 0.03, 0.035)
CLOTH = (0.035, 0.025, 0.02)
EMBER = (1.0, 0.36, 0.09)

LAMP_COLOR = (1.0, 0.72, 0.46)   # warm key: the hut
RIM_COLOR = (0.40, 0.56, 0.68)   # cold rim: the forest

OPEN_ANGLE_DEG = 105.0

# How much further back the camera goes per unit of lens shift, so that the
# room the shift borrows from one side is given back on the other.
SHIFT_ROOM = 2.4

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
FONT_MONO = os.path.join(REPO, "core", "design", "src", "main", "res", "font", "jetbrains_mono.ttf")
FONT_HAND = os.path.join(REPO, "art", "fonts", "Caveat.ttf")


def cm(value: float) -> float:
    return value * 0.01


def v(x: float, y: float, z: float) -> Vector:
    return Vector((cm(x), cm(y), cm(z)))


# ── Arguments ───────────────────────────────────────────────────────────────


def parse_args() -> argparse.Namespace:
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(prog="switch.py")
    parser.add_argument("--out", help="directory for the frame sequence (WebP)")
    parser.add_argument("--frames", type=int, default=48)
    parser.add_argument("--samples", type=int, default=256)
    parser.add_argument("--width", type=int, default=1080)
    parser.add_argument("--height", type=int, default=1620)
    parser.add_argument("--preview", help="render one PNG at --angle instead of a sequence")
    parser.add_argument("--angle", type=float, default=0.0)
    parser.add_argument("--glow", action="store_true", help="preview with current flowing")
    parser.add_argument("--save-blend", help="also save the scene for inspection")
    return parser.parse_args(argv)


# ── Scene reset ─────────────────────────────────────────────────────────────


def reset_scene() -> None:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.length_unit = "CENTIMETERS"


# ── Node helpers ────────────────────────────────────────────────────────────


def set_input(node: bpy.types.Node, names: tuple[str, ...], value) -> None:
    """Principled BSDF socket names changed across Blender versions."""
    for name in names:
        if name in node.inputs:
            node.inputs[name].default_value = value
            return


def socket(node: bpy.types.Node, *names: str) -> bpy.types.NodeSocket:
    for name in names:
        if name in node.inputs:
            return node.inputs[name]
    raise KeyError(f"{node.bl_idname} has none of {names}")


def new_material(name: str) -> tuple[bpy.types.Material, bpy.types.NodeTree, bpy.types.Node]:
    mat = bpy.data.materials.new(name)
    try:
        mat.use_nodes = True
    except AttributeError:
        pass
    tree = mat.node_tree
    bsdf = next(n for n in tree.nodes if n.type == "BSDF_PRINCIPLED")
    return mat, tree, bsdf


def noise(tree, scale: float, detail: float = 8.0, roughness: float = 0.6, coords: str = "Object"):
    tex = tree.nodes.new("ShaderNodeTexNoise")
    tex.inputs["Scale"].default_value = scale
    tex.inputs["Detail"].default_value = detail
    tex.inputs["Roughness"].default_value = roughness
    coord = tree.nodes.new("ShaderNodeTexCoord")
    tree.links.new(coord.outputs[coords], tex.inputs["Vector"])
    return tex


def ramp(tree, source, stops: list[tuple[float, tuple]]):
    node = tree.nodes.new("ShaderNodeValToRGB")
    elements = node.color_ramp.elements
    elements[0].position, elements[0].color = stops[0][0], (*stops[0][1], 1.0) if len(stops[0][1]) == 3 else stops[0][1]
    elements[1].position, elements[1].color = stops[-1][0], (*stops[-1][1], 1.0) if len(stops[-1][1]) == 3 else stops[-1][1]
    for position, color in stops[1:-1]:
        e = elements.new(position)
        e.color = (*color, 1.0) if len(color) == 3 else color
    tree.links.new(source, node.inputs["Fac"])
    return node


def bump(tree, height_socket, strength: float, distance: float):
    node = tree.nodes.new("ShaderNodeBump")
    node.inputs["Strength"].default_value = strength
    node.inputs["Distance"].default_value = distance
    tree.links.new(height_socket, node.inputs["Height"])
    return node


def edge_mask(tree, low: float, high: float):
    """Worn edges from Cycles' pointiness — convex edges catch wear first."""
    geo = tree.nodes.new("ShaderNodeNewGeometry")
    return ramp(tree, geo.outputs["Pointiness"], [(low, (0, 0, 0)), (high, (1, 1, 1))])


def mix_color(tree, fac, a, b):
    node = tree.nodes.new("ShaderNodeMix")
    node.data_type = "RGBA"
    tree.links.new(fac, node.inputs[0])
    if isinstance(a, bpy.types.NodeSocket):
        tree.links.new(a, node.inputs[6])
    else:
        node.inputs[6].default_value = (*a, 1.0)
    if isinstance(b, bpy.types.NodeSocket):
        tree.links.new(b, node.inputs[7])
    else:
        node.inputs[7].default_value = (*b, 1.0)
    return node.outputs[2]


# ── Materials ───────────────────────────────────────────────────────────────


def mat_slate() -> bpy.types.Material:
    mat, tree, bsdf = new_material("slate")
    grain = noise(tree, 180.0, detail=12, roughness=0.7)
    clouds = noise(tree, 9.0, detail=4, roughness=0.5)
    tone = ramp(tree, clouds.outputs["Fac"], [(0.35, (0.016, 0.023, 0.028)), (0.7, (0.036, 0.048, 0.057))])
    wear = edge_mask(tree, 0.50, 0.56)
    worn = mix_color(tree, wear.outputs["Color"], tone.outputs["Color"], (0.075, 0.092, 0.105))
    tree.links.new(worn, socket(bsdf, "Base Color"))
    rough = ramp(tree, grain.outputs["Fac"], [(0.3, (0.52, 0.52, 0.52)), (0.8, (0.78, 0.78, 0.78))])
    tree.links.new(rough.outputs["Color"], socket(bsdf, "Roughness"))
    # Long faint scratches: noise stretched along one axis.
    scratch_coords = tree.nodes.new("ShaderNodeMapping")
    scratch_coords.inputs["Scale"].default_value = (1.0, 1.0, 60.0)
    tc = tree.nodes.new("ShaderNodeTexCoord")
    tree.links.new(tc.outputs["Object"], scratch_coords.inputs["Vector"])
    scratch = tree.nodes.new("ShaderNodeTexNoise")
    scratch.inputs["Scale"].default_value = 220.0
    scratch.inputs["Detail"].default_value = 2.0
    tree.links.new(scratch_coords.outputs["Vector"], scratch.inputs["Vector"])
    scratch_line = ramp(tree, scratch.outputs["Fac"], [(0.72, (0, 0, 0)), (0.74, (1, 1, 1))])
    height = mix_color(tree, scratch_line.outputs["Color"], grain.outputs["Color"], (0.0, 0.0, 0.0))
    tree.links.new(bump(tree, height, 0.25, cm(0.02)).outputs["Normal"], socket(bsdf, "Normal"))
    return mat


def mat_metal(name: str, color, patina_color, patina_amount: float, roughness=(0.22, 0.48)) -> bpy.types.Material:
    mat, tree, bsdf = new_material(name)
    set_input(bsdf, ("Metallic",), 1.0)
    blotch = noise(tree, 260.0, detail=6, roughness=0.65)
    base = ramp(tree, blotch.outputs["Fac"], [(0.3, tuple(c * 0.72 for c in color)), (0.75, color)])
    # Patina settles into cavities: low pointiness, gated by noise.
    geo = tree.nodes.new("ShaderNodeNewGeometry")
    cavity = ramp(tree, geo.outputs["Pointiness"], [(0.44, (1, 1, 1)), (0.5, (0, 0, 0))])
    patchy = noise(tree, 90.0, detail=5, roughness=0.6)
    patch_mask = ramp(tree, patchy.outputs["Fac"], [(1.0 - patina_amount, (0, 0, 0)), (1.0 - patina_amount + 0.08, (1, 1, 1))])
    multiply = tree.nodes.new("ShaderNodeMath")
    multiply.operation = "MULTIPLY"
    tree.links.new(cavity.outputs["Color"], multiply.inputs[0])
    tree.links.new(patch_mask.outputs["Color"], multiply.inputs[1])
    colored = mix_color(tree, multiply.outputs["Value"], base.outputs["Color"], patina_color)
    tree.links.new(colored, socket(bsdf, "Base Color"))
    rough = ramp(tree, blotch.outputs["Fac"], [(0.2, (roughness[0],) * 3), (0.8, (roughness[1],) * 3)])
    tree.links.new(rough.outputs["Color"], socket(bsdf, "Roughness"))
    micro = noise(tree, 900.0, detail=3, roughness=0.5)
    tree.links.new(bump(tree, micro.outputs["Fac"], 0.08, cm(0.005)).outputs["Normal"], socket(bsdf, "Normal"))
    return mat


def mat_carbolite() -> bpy.types.Material:
    """Moulded carbolite: deep, glassy and hard, the way a real handle is."""
    mat, tree, bsdf = new_material("carbolite")
    swirl = noise(tree, 40.0, detail=6, roughness=0.55)
    body = ramp(tree, swirl.outputs["Fac"], [(0.3, (0.06, 0.018, 0.012)), (0.7, CARBOLITE)])

    # Forty years of thumbs: the crown of the knob is polished lighter.
    tc = tree.nodes.new("ShaderNodeTexCoord")
    sep = tree.nodes.new("ShaderNodeSeparateXYZ")
    tree.links.new(tc.outputs["Object"], sep.inputs[0])
    # Local Z of the turned handle runs from the collar (0) to the knob (7.7 cm).
    polish = ramp(tree, sep.outputs["Z"], [(cm(4.8), (0, 0, 0)), (cm(7.4), (1, 1, 1))])
    colored = mix_color(tree, polish.outputs["Color"], body.outputs["Color"], (0.24, 0.085, 0.05))
    tree.links.new(colored, socket(bsdf, "Base Color"))

    set_input(bsdf, ("Roughness",), 0.22)
    set_input(bsdf, ("Coat Weight", "Clearcoat"), 0.7)
    set_input(bsdf, ("Coat Roughness", "Clearcoat Roughness"), 0.06)

    scratches = noise(tree, 520.0, detail=2, roughness=0.4)
    fine = ramp(tree, scratches.outputs["Fac"], [(0.68, (0, 0, 0)), (0.7, (1, 1, 1))])
    tree.links.new(bump(tree, fine.outputs["Color"], 0.12, cm(0.004)).outputs["Normal"], socket(bsdf, "Normal"))
    return mat


def mat_flat(name: str, color, roughness: float, metallic: float = 0.0) -> bpy.types.Material:
    mat, tree, bsdf = new_material(name)
    set_input(bsdf, ("Base Color",), (*color, 1.0))
    set_input(bsdf, ("Roughness",), roughness)
    set_input(bsdf, ("Metallic",), metallic)
    return mat


def mat_paint() -> bpy.types.Material:
    mat, tree, bsdf = new_material("paint")
    chips = noise(tree, 70.0, detail=10, roughness=0.75)
    alpha = ramp(tree, chips.outputs["Fac"], [(0.36, (0, 0, 0)), (0.42, (1, 1, 1))])
    set_input(bsdf, ("Base Color",), (*PAINT, 1.0))
    set_input(bsdf, ("Roughness",), 0.7)
    tree.links.new(alpha.outputs["Color"], socket(bsdf, "Alpha"))
    return mat


def mat_paper() -> bpy.types.Material:
    mat, tree, bsdf = new_material("paper")
    fibres = noise(tree, 140.0, detail=10, roughness=0.7)
    stain = noise(tree, 12.0, detail=3, roughness=0.5)
    base = ramp(tree, stain.outputs["Fac"], [(0.3, (0.52, 0.42, 0.25)), (0.7, PAPER)])
    tree.links.new(base.outputs["Color"], socket(bsdf, "Base Color"))
    set_input(bsdf, ("Roughness",), 0.92)
    set_input(bsdf, ("Subsurface Weight", "Subsurface"), 0.08)
    tree.links.new(bump(tree, fibres.outputs["Fac"], 0.15, cm(0.003)).outputs["Normal"], socket(bsdf, "Normal"))
    return mat


def mat_tape() -> bpy.types.Material:
    mat, tree, bsdf = new_material("tape")
    set_input(bsdf, ("Base Color",), (0.55, 0.42, 0.20, 1.0))
    set_input(bsdf, ("Roughness",), 0.35)
    set_input(bsdf, ("Alpha",), 0.55)
    return mat


def mat_cloth() -> bpy.types.Material:
    mat, tree, bsdf = new_material("cloth")
    weave = tree.nodes.new("ShaderNodeTexWave")
    weave.wave_type = "RINGS"
    weave.inputs["Scale"].default_value = 900.0
    weave.inputs["Distortion"].default_value = 2.0
    tc = tree.nodes.new("ShaderNodeTexCoord")
    tree.links.new(tc.outputs["Object"], weave.inputs["Vector"])
    tone = ramp(tree, weave.outputs["Fac"], [(0.2, (0.015, 0.012, 0.01)), (0.8, CLOTH)])
    tree.links.new(tone.outputs["Color"], socket(bsdf, "Base Color"))
    set_input(bsdf, ("Roughness",), 0.95)
    set_input(bsdf, ("Sheen Weight", "Sheen"), 0.4)
    tree.links.new(bump(tree, weave.outputs["Fac"], 0.35, cm(0.02)).outputs["Normal"], socket(bsdf, "Normal"))
    return mat


# ── Geometry helpers ────────────────────────────────────────────────────────


def link(obj: bpy.types.Object, parent: bpy.types.Object | None = None) -> bpy.types.Object:
    bpy.context.scene.collection.objects.link(obj)
    if parent is not None:
        obj.parent = parent
        obj.matrix_parent_inverse = parent.matrix_world.inverted()
    return obj


def smooth(obj: bpy.types.Object, angle_deg: float = 35.0) -> None:
    mesh = obj.data
    try:
        mesh.shade_smooth()
        mesh.set_sharp_from_angle(angle=math.radians(angle_deg))
    except AttributeError:
        mesh.polygons.foreach_set("use_smooth", [True] * len(mesh.polygons))


def bevel(obj: bpy.types.Object, width_cm: float, segments: int = 3) -> None:
    mod = obj.modifiers.new("bevel", "BEVEL")
    mod.width = cm(width_cm)
    mod.segments = segments
    mod.limit_method = "ANGLE"
    mod.angle_limit = math.radians(40)
    mod.harden_normals = True


def mesh_object(name: str, bm: bmesh.types.BMesh, material, parent=None) -> bpy.types.Object:
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    obj = bpy.data.objects.new(name, mesh)
    if material is not None:
        mesh.materials.append(material)
    link(obj, parent)
    return obj


def box(name: str, size_cm, center_cm, material, bevel_cm: float = 0.06, parent=None,
        rotation_deg=(0.0, 0.0, 0.0)) -> bpy.types.Object:
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    bmesh.ops.scale(bm, vec=Vector((cm(size_cm[0]), cm(size_cm[1]), cm(size_cm[2]))), verts=bm.verts)
    if any(rotation_deg):
        # Rotate about the part's own centre; the translation is baked afterwards.
        spin = (
            Matrix.Rotation(math.radians(rotation_deg[2]), 4, "Z")
            @ Matrix.Rotation(math.radians(rotation_deg[1]), 4, "Y")
            @ Matrix.Rotation(math.radians(rotation_deg[0]), 4, "X")
        )
        bmesh.ops.transform(bm, matrix=spin, verts=bm.verts)
    bmesh.ops.translate(bm, vec=v(*center_cm), verts=bm.verts)
    obj = mesh_object(name, bm, material, parent)
    if bevel_cm > 0:
        bevel(obj, bevel_cm)
    smooth(obj)
    return obj


def cylinder(name: str, radius_cm: float, depth_cm: float, center_cm, axis: str, material,
             segments: int = 48, bevel_cm: float = 0.03, parent=None) -> bpy.types.Object:
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, segments=segments, radius1=cm(radius_cm), radius2=cm(radius_cm), depth=cm(depth_cm))
    rotation = {"X": Matrix.Rotation(math.radians(90), 4, "Y"), "Y": Matrix.Rotation(math.radians(90), 4, "X"), "Z": Matrix.Identity(4)}[axis]
    bmesh.ops.transform(bm, matrix=rotation, verts=bm.verts)
    bmesh.ops.translate(bm, vec=v(*center_cm), verts=bm.verts)
    obj = mesh_object(name, bm, material, parent)
    if bevel_cm > 0:
        bevel(obj, bevel_cm, 2)
    smooth(obj, 30)
    return obj


def hex_nut(name: str, across_cm: float, height_cm: float, center_cm, axis: str, material, parent=None) -> bpy.types.Object:
    radius = across_cm / math.sqrt(3)
    nut = cylinder(name, radius, height_cm, center_cm, axis, material, segments=6, bevel_cm=0.05, parent=parent)
    return nut


def slotted_screw(name: str, radius_cm: float, center_cm, material, parent=None) -> bpy.types.Object:
    """A domed screw head facing -Y with a real slot cut by a boolean."""
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=40, v_segments=20, radius=cm(radius_cm))
    for vert in bm.verts:
        vert.co.y = min(vert.co.y, 0.0) * 0.45   # dome towards the viewer, flat back
    bmesh.ops.translate(bm, vec=v(*center_cm), verts=bm.verts)
    head = mesh_object(name, bm, material, parent)
    smooth(head, 60)
    slot = box(name + "_slot", (radius_cm * 2.4, radius_cm * 0.9, radius_cm * 0.28), center_cm, None, bevel_cm=0)
    # Deterministic per screw, so every frame of a sequence and every re-render match.
    slot.rotation_euler = (0.0, math.radians(35 if zlib.crc32(name.encode()) % 2 else -20), 0.0)
    slot.location = v(center_cm[0], center_cm[1] - radius_cm * 0.42, center_cm[2])
    slot.data.transform(Matrix.Translation(-v(*center_cm)))
    boolean = head.modifiers.new("slot", "BOOLEAN")
    boolean.operation = "DIFFERENCE"
    boolean.object = slot
    slot.hide_render = True
    slot.hide_viewport = True
    return head


def lathe(name: str, profile_cm: list[tuple[float, float]], axis_origin_cm, material, parent=None) -> bpy.types.Object:
    """Turned part: profile as (radius, height) pairs revolved around local Y (pointing at the viewer)."""
    bm = bmesh.new()
    verts = [bm.verts.new((cm(r), 0.0, cm(h))) for r, h in profile_cm]
    for a, b in zip(verts, verts[1:]):
        bm.edges.new((a, b))
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    obj = bpy.data.objects.new(name, mesh)
    mesh.materials.append(material)
    screw = obj.modifiers.new("lathe", "SCREW")
    screw.axis = "Z"
    screw.steps = 64
    screw.render_steps = 96
    screw.use_merge_vertices = True
    # The default threshold is one centimetre, which is thicker than the whole
    # handle: it collapses every revolved ring of the shaft into a single point
    # and leaves the knob floating free of its stem.
    screw.merge_threshold = cm(0.005)
    screw.use_smooth_shade = True
    # Local Z of the profile becomes world -Y: the handle points out of the panel.
    obj.rotation_euler = (math.radians(90), 0.0, 0.0)
    obj.location = v(*axis_origin_cm)
    link(obj, parent)
    return obj


def text(name: str, body: str, font_path: str, size_cm: float, center_cm, material, rotation_deg=(90, 0, 0),
         extrude_cm: float = 0.0, parent=None) -> bpy.types.Object:
    curve = bpy.data.curves.new(name, "FONT")
    curve.body = body
    if os.path.exists(font_path):
        curve.font = bpy.data.fonts.load(font_path, check_existing=True)
    curve.size = cm(size_cm)
    curve.align_x = "CENTER"
    curve.align_y = "CENTER"
    curve.extrude = cm(extrude_cm)
    obj = bpy.data.objects.new(name, curve)
    obj.data.materials.append(material)
    obj.rotation_euler = tuple(math.radians(a) for a in rotation_deg)
    obj.location = v(*center_cm)
    link(obj, parent)
    return obj


def wire(name: str, points_cm: list[tuple[float, float, float]], radius_cm: float, material) -> bpy.types.Object:
    curve = bpy.data.curves.new(name, "CURVE")
    curve.dimensions = "3D"
    curve.bevel_depth = cm(radius_cm)
    curve.bevel_resolution = 6
    curve.use_fill_caps = True
    spline = curve.splines.new("BEZIER")
    spline.bezier_points.add(len(points_cm) - 1)
    for bp, p in zip(spline.bezier_points, points_cm):
        bp.co = v(*p)
        bp.handle_left_type = "AUTO"
        bp.handle_right_type = "AUTO"
    obj = bpy.data.objects.new(name, curve)
    obj.data.materials.append(material)
    link(obj)
    return obj


# ── The switch ──────────────────────────────────────────────────────────────

POLES_X = (-2.7, 2.7)
HINGE_Z = -9.0
JAW_Z = 9.4
BLADE_Y = -2.1
BLADE_LENGTH = 19.4
PANEL_SIZE = (15.0, 1.8, 26.0)


def build(materials: dict) -> dict:
    parts: dict = {}

    # Slate panel, face at y = 0.
    parts["panel"] = box("panel", PANEL_SIZE, (0.0, 0.9, 0.0), materials["slate"], bevel_cm=0.25)

    # Mounting screws in the corners.
    for i, (x, z) in enumerate(((-6.1, 11.6), (6.1, 11.6), (-6.1, -11.6), (6.1, -11.6))):
        slotted_screw(f"mount_screw_{i}", 0.62, (x, -0.02, z), materials["brass"])

    for pole, x in enumerate(POLES_X):
        # Jaw: two copper cheeks with flared mouths, on a base strap.
        box(f"jaw_base_{pole}", (2.2, 0.35, 3.6), (x, -0.18, JAW_Z), materials["copper"], bevel_cm=0.05)
        for side in (-1, 1):
            cheek = box(f"jaw_cheek_{pole}_{side}", (0.26, 2.4, 3.2), (x + side * 0.31, BLADE_Y + 0.2, JAW_Z + 0.4), materials["copper"], bevel_cm=0.05)
            flare(cheek, side, top_cm=JAW_Z + 2.0, spread_cm=0.22)
        cylinder(f"jaw_rivet_{pole}", 0.22, 1.1, (x, BLADE_Y + 0.2, JAW_Z - 0.6), "X", materials["brass"], segments=24)

        # Hinge: cheeks, pivot bolt and nut.
        box(f"hinge_base_{pole}", (2.2, 0.35, 3.4), (x, -0.18, HINGE_Z), materials["copper"], bevel_cm=0.05)
        for side in (-1, 1):
            box(f"hinge_cheek_{pole}_{side}", (0.26, 2.6, 2.6), (x + side * 0.31, BLADE_Y + 0.1, HINGE_Z), materials["copper"], bevel_cm=0.05)
        cylinder(f"pivot_{pole}", 0.28, 1.5, (x, BLADE_Y, HINGE_Z), "X", materials["steel"], segments=32)
        hex_nut(f"pivot_nut_{pole}", 0.9, 0.35, (x + 0.72, BLADE_Y, HINGE_Z), "X", materials["brass"])

        # Terminals with copper straps, washers and nuts.
        for end, z in (("top", 12.2), ("bottom", -12.2)):
            strap_z = (z + (JAW_Z if end == "top" else HINGE_Z)) / 2
            box(f"strap_{end}_{pole}", (1.3, 0.28, abs(z - strap_z) * 2 + 0.6), (x, -0.16, strap_z), materials["copper"], bevel_cm=0.04)
            cylinder(f"washer_{end}_{pole}", 0.85, 0.12, (x, -0.38, z), "Y", materials["brass"], segments=40)
            hex_nut(f"nut_{end}_{pole}", 1.2, 0.55, (x, -0.72, z), "Y", materials["brass"])
            cylinder(f"stud_{end}_{pole}", 0.3, 0.5, (x, -1.2, z), "Y", materials["steel"], segments=24)

    # Moving assembly, pivoting at the hinge axis.
    pivot = bpy.data.objects.new("blade_pivot", None)
    pivot.location = v(0.0, BLADE_Y, HINGE_Z)
    link(pivot)
    # Children bake world coordinates, so the pivot's matrix_world must be real
    # before matrix_parent_inverse is taken from it - otherwise every moving
    # part is displaced by the hinge offset.
    bpy.context.view_layer.update()
    parts["pivot"] = pivot

    top = HINGE_Z + BLADE_LENGTH
    for pole, x in enumerate(POLES_X):
        blade = box(f"blade_{pole}", (0.34, 1.5, BLADE_LENGTH), (x, BLADE_Y, HINGE_Z + BLADE_LENGTH / 2 - 0.4), materials["copper_bright"], bevel_cm=0.04, parent=pivot)
        taper_tip(blade, top_cm=top - 0.4)

    crossbar_z = HINGE_Z + BLADE_LENGTH * 0.72
    box("crossbar", (9.6, 1.3, 1.5), (0.0, BLADE_Y - 1.4, crossbar_z), materials["carbolite"], bevel_cm=0.18, parent=pivot)
    for x in POLES_X:
        cylinder(f"crossbar_rivet_{x}", 0.24, 3.0, (x, BLADE_Y - 0.7, crossbar_z), "Y", materials["brass"], segments=24, parent=pivot)

    # Turned carbolite handle: collar, waist, grip and a hand-worn knob.
    profile = [
        (0.0, 0.0), (1.35, 0.0), (1.35, 0.45), (1.18, 0.62), (0.95, 0.85), (0.84, 1.3),
        (0.80, 2.0), (0.84, 2.8), (0.86, 3.4), (0.80, 3.9), (0.92, 4.25), (1.16, 4.65),
        (1.42, 5.1), (1.60, 5.55), (1.70, 6.0), (1.72, 6.35), (1.66, 6.7), (1.52, 7.0),
        (1.28, 7.3), (0.92, 7.55), (0.48, 7.7), (0.0, 7.74),
    ]
    parts["handle"] = lathe("handle", profile, (0.0, BLADE_Y - 2.05, crossbar_z), materials["carbolite"], parent=pivot)

    # Painted legend and the brass nameplate.
    text("legend_on", "ВКЛ", FONT_MONO, 1.15, (0.0, -0.02, 10.9), materials["paint"])
    text("legend_off", "ВЫКЛ", FONT_MONO, 1.15, (0.0, -0.02, -10.6), materials["paint"])
    box("nameplate", (6.4, 0.14, 1.7), (0.0, -0.08, -12.3), materials["brass"], bevel_cm=0.05)
    text("nameplate_text", "ZALES  Р-25", FONT_MONO, 0.5, (0.0, -0.17, -12.3), materials["engraving"])

    # The warm human detail: a handwritten paper note under yellowed tape.
    # The note lies ON the panel, so it tilts about the panel's normal (Y).
    # Rotating it about Z would twist it out of the plane and bury it in the slate.
    label_tilt = -5.0
    label_x, label_z = -5.15, -1.7
    box("label", (4.0, 0.05, 2.2), (label_x, -0.07, label_z), materials["paper"], bevel_cm=0,
        rotation_deg=(0.0, label_tilt, 0.0))
    text("label_text", "ИНТЕРНЕТ", FONT_HAND, 0.50, (label_x, -0.13, label_z - 0.05), materials["ink"],
         rotation_deg=(90.0, label_tilt, 0.0))
    # A strip of tape across the top edge, torn off at a slightly different angle.
    box("tape", (1.9, 0.02, 0.70), (label_x + 0.20, -0.15, label_z + 1.00), materials["tape"], bevel_cm=0,
        rotation_deg=(0.0, label_tilt + 6.0, 0.0))

    # Cloth-insulated wires leaving the frame.
    for pole, x in enumerate(POLES_X):
        wire(f"wire_top_{pole}", [(x, -1.3, 12.2), (x * 1.4, -2.6, 14.2), (x * 2.6, -2.9, 17.5)],
             0.26, materials["cloth"])
        wire(f"wire_bottom_{pole}", [(x, -1.3, -12.2), (x * 1.4, -2.4, -14.4), (x * 2.6, -2.7, -17.8)],
             0.26, materials["cloth"])

    return parts


def flare(obj: bpy.types.Object, side: int, top_cm: float, spread_cm: float) -> None:
    """Bends the upper vertices of a jaw cheek outwards to form the mouth."""
    for vert in obj.data.vertices:
        if vert.co.z > cm(top_cm - 0.6):
            vert.co.x += cm(spread_cm) * side


def taper_tip(obj: bpy.types.Object, top_cm: float) -> None:
    for vert in obj.data.vertices:
        if vert.co.z > cm(top_cm - 0.3):
            vert.co.y *= 0.82


# ── Light, camera, render ───────────────────────────────────────────────────


def look_at(obj: bpy.types.Object, target: Vector) -> None:
    direction = target - obj.location
    obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


def setup_world() -> None:
    world = bpy.data.worlds.new("night")
    bpy.context.scene.world = world
    try:
        world.use_nodes = True
    except AttributeError:
        pass
    background = next(n for n in world.node_tree.nodes if n.type == "BACKGROUND")
    background.inputs["Color"].default_value = (0.004, 0.006, 0.007, 1.0)
    background.inputs["Strength"].default_value = 1.0


def setup_lights(glow: bool) -> None:
    scene = bpy.context.scene

    key_data = bpy.data.lights.new("lamp", "SPOT")
    key_data.energy = 6.5
    key_data.color = LAMP_COLOR
    key_data.spot_size = math.radians(40)
    key_data.spot_blend = 0.65
    key_data.shadow_soft_size = cm(3.0)
    key = bpy.data.objects.new("lamp", key_data)
    key.location = v(-26.0, -34.0, 27.0)
    scene.collection.objects.link(key)
    look_at(key, v(0.0, -2.0, 3.0))

    rim_data = bpy.data.lights.new("forest", "AREA")
    rim_data.energy = 1.1
    rim_data.color = RIM_COLOR
    rim_data.size = cm(24.0)
    rim = bpy.data.objects.new("forest", rim_data)
    rim.location = v(34.0, 12.0, 14.0)
    scene.collection.objects.link(rim)
    look_at(rim, v(0.0, -2.0, 0.0))

    # A weak fill from the viewer's side. Without it the knob - the one thing
    # a hand reaches for - turns its back on the lamp and loses all form.
    fill_data = bpy.data.lights.new("fill", "AREA")
    fill_data.energy = 0.34
    fill_data.color = LAMP_COLOR
    fill_data.size = cm(18.0)
    fill = bpy.data.objects.new("fill", fill_data)
    fill.location = v(-14.0, -38.0, 4.0)
    scene.collection.objects.link(fill)
    look_at(fill, v(0.0, -6.0, 3.0))

    # The handle swings a hundred degrees out of the key light's reach, and an
    # open switch rendered as a black silhouette says nothing. This one lives
    # under the arc and only catches the turned carbolite on its way down.
    arc_data = bpy.data.lights.new("arc_fill", "AREA")
    arc_data.energy = 0.30
    arc_data.color = LAMP_COLOR
    arc_data.size = cm(22.0)
    arc = bpy.data.objects.new("arc_fill", arc_data)
    arc.location = v(-18.0, -30.0, -18.0)
    scene.collection.objects.link(arc)
    look_at(arc, v(0.0, -14.0, -7.0))

    if glow:
        for x in POLES_X:
            ember_data = bpy.data.lights.new(f"ember_{x}", "POINT")
            ember_data.energy = 0.12
            ember_data.color = EMBER
            ember_data.shadow_soft_size = cm(0.4)
            ember = bpy.data.objects.new(f"ember_{x}", ember_data)
            ember.location = v(x, BLADE_Y - 0.7, JAW_Z - 1.4)
            scene.collection.objects.link(ember)


def setup_camera(parts: dict, width: int, height: int) -> bpy.types.Object:
    scene = bpy.context.scene
    cam_data = bpy.data.cameras.new("camera")
    cam_data.lens = 85.0
    cam_data.sensor_width = 36.0
    cam = bpy.data.objects.new("camera", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    scene.render.resolution_x = width
    scene.render.resolution_y = height

    target = v(0.0, -3.2, -1.0)
    cam.location = v(26.0, -62.0, 2.0)
    look_at(cam, target)

    # Fit the whole arc of the blade, closed and open, then leave breathing room.
    coords: list[float] = []
    for angle in (0.0, OPEN_ANGLE_DEG * 0.5, OPEN_ANGLE_DEG):
        parts["pivot"].rotation_euler = (math.radians(angle), 0.0, 0.0)
        bpy.context.view_layer.update()
        for obj in scene.objects:
            if obj.type in {"MESH", "CURVE", "FONT"} and not obj.hide_render and not obj.name.startswith("wire"):
                for corner in obj.bound_box:
                    coords.extend(obj.matrix_world @ Vector(corner))
    depsgraph = bpy.context.evaluated_depsgraph_get()
    location, _ = cam.camera_fit_coords(depsgraph, coords)
    cam.location = target + (Vector(location) - target) * 1.03

    # The handle sweeps out to one side, so a frame fitted to every pose puts
    # the panel off-centre in the pose the switch is in almost all of the time.
    #
    # Fixed with a lens shift rather than by moving the camera: the shift slides
    # the frame in image space without changing the angle the object is seen
    # from, which is the whole character of the render. The camera is then
    # pulled back to pay for the room the shift borrowed.
    parts["pivot"].rotation_euler = (0.0, 0.0, 0.0)
    bpy.context.view_layer.update()
    panel = world_to_camera_view(scene, cam, parts["panel"].matrix_world.translation)
    # Blender measures shift against the longer side of the frame, which here is
    # the height, so a horizontal shift has to be scaled by the aspect.
    shift = (panel.x - 0.5) * (width / height)
    cam_data.shift_x = shift
    cam.location = target + (cam.location - target) * (1.0 + SHIFT_ROOM * abs(shift))

    cam_data.dof.use_dof = True
    cam_data.dof.focus_object = parts["handle"]
    cam_data.dof.aperture_fstop = 14.0
    parts["pivot"].rotation_euler = (0.0, 0.0, 0.0)
    return cam


def setup_render(samples: int) -> None:
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = samples
    scene.cycles.use_adaptive_sampling = True
    scene.cycles.adaptive_threshold = 0.01
    scene.cycles.use_denoising = True
    scene.render.film_transparent = True
    scene.cycles.max_bounces = 10
    scene.cycles.glossy_bounces = 6
    scene.cycles.transparent_max_bounces = 8

    prefs = bpy.context.preferences.addons["cycles"].preferences
    for backend in ("OPTIX", "CUDA"):
        try:
            prefs.compute_device_type = backend
            prefs.get_devices()
            gpus = [d for d in prefs.devices if d.type == backend]
            if gpus:
                for device in prefs.devices:
                    device.use = device.type == backend
                scene.cycles.device = "GPU"
                try:
                    scene.cycles.denoiser = "OPTIX" if backend == "OPTIX" else "OPENIMAGEDENOISE"
                except TypeError:
                    pass
                print(f"[switch] rendering on {backend}: {[d.name for d in gpus]}")
                break
        except TypeError:
            continue
    else:
        print("[switch] no GPU backend, rendering on CPU")

    view = scene.view_settings
    view.view_transform = "AgX"
    for look in ("AgX - Medium High Contrast", "Medium High Contrast", "AgX - High Contrast", "None"):
        try:
            view.look = look
            break
        except TypeError:
            continue
    view.exposure = 0.0


def set_glow(materials: dict, on: bool) -> None:
    """Current flowing: the jaws and blade tips glow faintly ember."""
    for name in ("copper_bright",):
        bsdf = next(n for n in materials[name].node_tree.nodes if n.type == "BSDF_PRINCIPLED")
        set_input(bsdf, ("Emission Color", "Emission"), (*EMBER, 1.0))
        set_input(bsdf, ("Emission Strength",), 0.35 if on else 0.0)


def main() -> None:
    args = parse_args()
    reset_scene()
    setup_world()

    materials = {
        "slate": mat_slate(),
        "copper": mat_metal("copper", COPPER, VERDIGRIS, patina_amount=0.45),
        "copper_bright": mat_metal("copper_bright", tuple(min(1.0, c * 1.12) for c in COPPER), VERDIGRIS, patina_amount=0.12, roughness=(0.14, 0.3)),
        "brass": mat_metal("brass", BRASS, (0.20, 0.17, 0.10), patina_amount=0.35, roughness=(0.25, 0.5)),
        "steel": mat_metal("steel", STEEL, (0.18, 0.12, 0.08), patina_amount=0.3, roughness=(0.3, 0.5)),
        "carbolite": mat_carbolite(),
        "paint": mat_paint(),
        "engraving": mat_flat("engraving", INK, 0.6),
        "paper": mat_paper(),
        "ink": mat_flat("ink", INK, 0.8),
        "tape": mat_tape(),
        "cloth": mat_cloth(),
    }

    parts = build(materials)
    setup_render(args.samples)
    setup_camera(parts, args.width, args.height)
    scene = bpy.context.scene

    if args.preview:
        setup_lights(glow=args.glow)
        set_glow(materials, args.glow)
        parts["pivot"].rotation_euler = (math.radians(args.angle), 0.0, 0.0)
        scene.render.image_settings.file_format = "PNG"
        scene.render.image_settings.color_mode = "RGBA"
        scene.render.filepath = os.path.abspath(args.preview)
        if args.save_blend:
            bpy.ops.wm.save_as_mainfile(filepath=os.path.abspath(args.save_blend))
        bpy.ops.render.render(write_still=True)
        print(f"[switch] preview written to {scene.render.filepath}")
        return

    if not args.out:
        raise SystemExit("--out is required for a sequence render")
    os.makedirs(args.out, exist_ok=True)
    setup_lights(glow=False)
    scene.render.image_settings.file_format = "WEBP"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.image_settings.quality = 90

    for index in range(args.frames):
        angle = OPEN_ANGLE_DEG * index / (args.frames - 1)
        parts["pivot"].rotation_euler = (math.radians(angle), 0.0, 0.0)
        scene.render.filepath = os.path.join(os.path.abspath(args.out), f"frame_{index:02d}.webp")
        bpy.ops.render.render(write_still=True)
        print(f"[switch] frame {index + 1}/{args.frames} at {angle:.1f}°")

    # The closed switch with current flowing, crossfaded in by the app.
    for obj in list(scene.objects):
        if obj.type == "LIGHT":
            bpy.data.objects.remove(obj)
    setup_lights(glow=True)
    set_glow(materials, True)
    parts["pivot"].rotation_euler = (0.0, 0.0, 0.0)
    scene.render.filepath = os.path.join(os.path.abspath(args.out), "frame_00_on.webp")
    bpy.ops.render.render(write_still=True)
    print("[switch] sequence complete")


if __name__ == "__main__":
    main()
