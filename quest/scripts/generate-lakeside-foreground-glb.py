#!/usr/bin/env python3
"""Generate OpenPanel's Quest-safe procedural lakeside foreground GLB.

The high-density horizon band is still the distant background. This asset adds
real local geometry in the lower field of view so the scene has crisp,
headset-rasterized foreground detail instead of relying only on a stretched
panorama.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = REPO_ROOT / "app/src/main/assets/lakeside_foreground_v1.glb"


@dataclass
class Primitive:
    name: str
    positions: list[float]
    normals: list[float]
    colors: list[float]
    indices: list[int]
    material: int

    @property
    def vertex_count(self) -> int:
        return len(self.positions) // 3


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def pack_floats(values: Iterable[float]) -> bytes:
    values = list(values)
    return struct.pack("<" + ("f" * len(values)), *values)


def pack_uint16(values: Iterable[int]) -> bytes:
    values = list(values)
    return struct.pack("<" + ("H" * len(values)), *values)


def pad_to_4(data: bytes, pad_byte: bytes = b"\x00") -> bytes:
    padding = (-len(data)) % 4
    if padding:
        data += pad_byte * padding
    return data


def append_buffer(buffer: bytearray, payload: bytes) -> tuple[int, int]:
    while len(buffer) % 4:
        buffer.append(0)
    offset = len(buffer)
    buffer.extend(payload)
    while len(buffer) % 4:
        buffer.append(0)
    return offset, len(payload)


def terrain_height(x: float, z: float) -> float:
    ridge = 0.035 * math.sin(x * 1.15 + z * 0.42) + 0.022 * math.sin(x * 2.4 - z * 0.28)
    shore_slope = clamp((-z - 4.2) / 10.0, 0.0, 1.0) * 0.11
    micro = 0.012 * math.sin(x * 6.0 + z * 1.8) * math.sin(x * 1.7)
    return -0.46 + shore_slope + ridge + micro


def terrain_color(x: float, z: float, y: float) -> tuple[float, float, float, float]:
    wet = clamp((-z - 9.0) / 4.2, 0.0, 1.0)
    moss = clamp((math.sin(x * 0.75 + z * 0.35) + 1.0) * 0.5, 0.0, 1.0)
    speckle = clamp((math.sin(x * 7.0) * math.sin(z * 3.1) + 1.0) * 0.5, 0.0, 1.0)
    r = 0.72 - wet * 0.18 - moss * 0.10 + speckle * 0.05
    g = 0.58 - wet * 0.12 + moss * 0.10 + speckle * 0.04
    b = 0.39 - wet * 0.09 + moss * 0.03
    return (clamp(r, 0.25, 0.95), clamp(g, 0.24, 0.88), clamp(b, 0.18, 0.72), 1.0)


def make_terrain() -> Primitive:
    cols = 65
    rows = 35
    positions: list[float] = []
    normals_acc = np.zeros((cols * rows, 3), dtype=np.float32)
    colors: list[float] = []
    indices: list[int] = []

    for row in range(rows):
        v = row / (rows - 1)
        z = -3.15 - v * 11.4
        width = 15.4 - v * 1.8
        for col in range(cols):
            u = col / (cols - 1)
            x = (u - 0.5) * width
            # Push the side/near edges low so any mesh boundary falls out of the
            # comfortable view and reads as shoreline rather than a rectangle.
            edge_drop = 0.055 * (1.0 - math.sin(math.pi * u)) + 0.045 * (1.0 - v)
            y = terrain_height(x, z) - edge_drop
            positions.extend([x, y, z])
            colors.extend(terrain_color(x, z, y))

    for row in range(rows - 1):
        for col in range(cols - 1):
            a = row * cols + col
            b = a + 1
            c = a + cols
            d = c + 1
            indices.extend([a, c, b, b, c, d])

    pos_arr = np.asarray(positions, dtype=np.float32).reshape((-1, 3))
    for tri in range(0, len(indices), 3):
        ia, ib, ic = indices[tri : tri + 3]
        normal = np.cross(pos_arr[ib] - pos_arr[ia], pos_arr[ic] - pos_arr[ia])
        length = float(np.linalg.norm(normal))
        if length > 1e-6:
            normal /= length
        normals_acc[ia] += normal
        normals_acc[ib] += normal
        normals_acc[ic] += normal
    norm = np.linalg.norm(normals_acc, axis=1)
    norm[norm < 1e-6] = 1.0
    normals = (normals_acc / norm[:, None]).reshape((-1,)).tolist()

    return Primitive("shore_terrain", positions, normals, colors, indices, 0)


def add_ellipsoid(
    primitive: Primitive,
    center: tuple[float, float, float],
    scale: tuple[float, float, float],
    color: tuple[float, float, float, float],
    rng: random.Random,
    rings: int = 5,
    segments: int = 9,
) -> None:
    base = primitive.vertex_count
    cx, cy, cz = center
    sx, sy, sz = scale
    for r in range(rings + 1):
        phi = -math.pi / 2 + math.pi * r / rings
        cp = math.cos(phi)
        sp = math.sin(phi)
        for s in range(segments):
            theta = 2 * math.pi * s / segments
            ct = math.cos(theta)
            st = math.sin(theta)
            jitter = 0.90 + rng.random() * 0.18
            nx = cp * ct
            ny = sp
            nz = cp * st
            primitive.positions.extend([cx + nx * sx * jitter, cy + ny * sy * jitter, cz + nz * sz * jitter])
            primitive.normals.extend([nx, ny, nz])
            shade = 0.78 + rng.random() * 0.20
            primitive.colors.extend([
                clamp(color[0] * shade, 0.0, 1.0),
                clamp(color[1] * shade, 0.0, 1.0),
                clamp(color[2] * shade, 0.0, 1.0),
                color[3],
            ])

    for r in range(rings):
        for s in range(segments):
            a = base + r * segments + s
            b = base + r * segments + (s + 1) % segments
            c = base + (r + 1) * segments + s
            d = base + (r + 1) * segments + (s + 1) % segments
            primitive.indices.extend([a, c, b, b, c, d])


def make_rocks(rng: random.Random) -> Primitive:
    primitive = Primitive("foreground_rocks", [], [], [], [], 1)
    placements = [
        (-4.8, -0.30, -4.2, 0.46, 0.20, 0.32),
        (-3.5, -0.33, -6.0, 0.28, 0.16, 0.24),
        (4.4, -0.31, -4.8, 0.40, 0.18, 0.30),
        (5.7, -0.35, -6.9, 0.24, 0.14, 0.20),
        (-6.0, -0.38, -8.0, 0.32, 0.14, 0.26),
        (2.2, -0.32, -7.6, 0.22, 0.12, 0.20),
        (6.8, -0.42, -10.3, 0.28, 0.13, 0.22),
        (-2.2, -0.39, -10.7, 0.20, 0.10, 0.18),
    ]
    for x, y, z, sx, sy, sz in placements:
        base_color = (0.62 + rng.random() * 0.08, 0.58 + rng.random() * 0.07, 0.50 + rng.random() * 0.06, 1.0)
        add_ellipsoid(primitive, (x, y, z), (sx, sy, sz), base_color, rng)
    return primitive


def add_blade(
    primitive: Primitive,
    root: tuple[float, float, float],
    height: float,
    width: float,
    bend: float,
    yaw: float,
    color: tuple[float, float, float, float],
) -> None:
    base = primitive.vertex_count
    rx, ry, rz = root
    dx = math.sin(yaw)
    dz = math.cos(yaw)
    side_x = math.cos(yaw) * width
    side_z = -math.sin(yaw) * width
    tip = (rx + dx * bend, ry + height, rz + dz * bend)
    left = (rx - side_x, ry, rz - side_z)
    right = (rx + side_x, ry, rz + side_z)
    mid = (rx + dx * bend * 0.45, ry + height * 0.55, rz + dz * bend * 0.45)
    positions = [left, right, mid, tip]
    normal = (math.sin(yaw + math.pi / 2), 0.18, math.cos(yaw + math.pi / 2))
    length = math.sqrt(sum(n * n for n in normal))
    normal = tuple(n / length for n in normal)
    for p in positions:
        primitive.positions.extend(p)
        primitive.normals.extend(normal)
        primitive.colors.extend(color)
    primitive.indices.extend([base, base + 1, base + 2, base + 1, base + 3, base + 2])


def make_grass(rng: random.Random) -> Primitive:
    primitive = Primitive("shore_grass_reeds", [], [], [], [], 2)
    clusters = [
        (-6.2, -4.4), (-5.1, -5.1), (-4.2, -8.5), (-2.9, -6.7),
        (3.1, -4.6), (4.2, -5.4), (5.5, -7.2), (6.2, -9.6),
        (-1.1, -11.1), (1.2, -10.0), (-6.7, -10.7), (6.8, -12.2),
    ]
    for cx, cz in clusters:
        for _ in range(rng.randint(16, 26)):
            x = cx + rng.uniform(-0.42, 0.42)
            z = cz + rng.uniform(-0.52, 0.52)
            y = terrain_height(x, z) + rng.uniform(0.00, 0.04)
            h = rng.uniform(0.18, 0.52)
            w = rng.uniform(0.008, 0.022)
            bend = rng.uniform(-0.12, 0.18)
            yaw = rng.uniform(0.0, math.tau)
            yellow = rng.random()
            color = (
                0.55 + yellow * 0.16,
                0.64 + yellow * 0.13,
                0.31 + yellow * 0.05,
                1.0,
            )
            add_blade(primitive, (x, y, z), h, w, bend, yaw, color)
    return primitive


def add_branch_segment(
    primitive: Primitive,
    start: tuple[float, float, float],
    end: tuple[float, float, float],
    radius: float,
    color: tuple[float, float, float, float],
) -> None:
    base = primitive.vertex_count
    sx, sy, sz = start
    ex, ey, ez = end
    vx, vy, vz = ex - sx, ey - sy, ez - sz
    length = math.sqrt(vx * vx + vy * vy + vz * vz)
    if length < 1e-6:
        return
    vx, vy, vz = vx / length, vy / length, vz / length
    # Simple camera-facing-ish ribbon cross-section.
    side = np.asarray([vz, 0.0, -vx], dtype=np.float32)
    slen = float(np.linalg.norm(side))
    if slen < 1e-6:
        side = np.asarray([1.0, 0.0, 0.0], dtype=np.float32)
    else:
        side /= slen
    up = np.asarray([0.0, 1.0, 0.0], dtype=np.float32)
    offsets = [side * radius, -side * radius, up * radius * 0.65, -up * radius * 0.65]
    for point in (np.asarray(start), np.asarray(end)):
        for off in offsets:
            p = point + off
            primitive.positions.extend(p.tolist())
            n = off / max(float(np.linalg.norm(off)), 1e-6)
            primitive.normals.extend(n.tolist())
            primitive.colors.extend(color)
    primitive.indices.extend([
        base, base + 4, base + 1, base + 1, base + 4, base + 5,
        base + 2, base + 6, base + 3, base + 3, base + 6, base + 7,
    ])


def make_branches(rng: random.Random) -> Primitive:
    primitive = Primitive("dry_reed_silhouettes", [], [], [], [], 3)
    roots = [(-5.8, -0.36, -4.0), (5.8, -0.35, -4.4), (6.6, -0.42, -7.4), (-6.7, -0.40, -7.8)]
    for root in roots:
        x, y, z = root
        trunk_top = (x + rng.uniform(-0.10, 0.10), y + rng.uniform(0.62, 1.05), z + rng.uniform(-0.08, 0.08))
        add_branch_segment(primitive, root, trunk_top, rng.uniform(0.012, 0.020), (0.58, 0.43, 0.30, 1.0))
        for _ in range(rng.randint(3, 5)):
            t = rng.uniform(0.35, 0.92)
            sx = x + (trunk_top[0] - x) * t
            sy = y + (trunk_top[1] - y) * t
            sz = z + (trunk_top[2] - z) * t
            length = rng.uniform(0.20, 0.48)
            yaw = rng.uniform(-1.8, 1.8)
            end = (sx + math.sin(yaw) * length, sy + rng.uniform(-0.03, 0.12), sz - abs(math.cos(yaw)) * length * 0.4)
            add_branch_segment(primitive, (sx, sy, sz), end, rng.uniform(0.005, 0.010), (0.60, 0.46, 0.32, 1.0))
    return primitive


def primitive_bounds(positions: list[float]) -> tuple[list[float], list[float]]:
    arr = np.asarray(positions, dtype=np.float32).reshape((-1, 3))
    return arr.min(axis=0).tolist(), arr.max(axis=0).tolist()


def material(name: str, base_color: list[float], double_sided: bool = False) -> dict:
    return {
        "name": name,
        "doubleSided": double_sided,
        "pbrMetallicRoughness": {
            "baseColorFactor": base_color,
            "metallicFactor": 0.0,
            "roughnessFactor": 0.96,
        },
    }


def write_glb(output: Path, primitives: list[Primitive]) -> None:
    binary = bytearray()
    accessors: list[dict] = []
    buffer_views: list[dict] = []
    gltf_primitives: list[dict] = []

    def add_accessor(payload: bytes, target: int, component_type: int, count: int, type_name: str, minimum=None, maximum=None) -> int:
        offset, length = append_buffer(binary, payload)
        view_index = len(buffer_views)
        buffer_views.append({"buffer": 0, "byteOffset": offset, "byteLength": length, "target": target})
        accessor = {"bufferView": view_index, "componentType": component_type, "count": count, "type": type_name}
        if minimum is not None:
            accessor["min"] = minimum
        if maximum is not None:
            accessor["max"] = maximum
        accessors.append(accessor)
        return len(accessors) - 1

    for primitive in primitives:
        if primitive.vertex_count >= 65535:
            raise ValueError(f"{primitive.name} has too many vertices for uint16 indices")
        pos_min, pos_max = primitive_bounds(primitive.positions)
        position_accessor = add_accessor(
            pack_floats(primitive.positions),
            34962,
            5126,
            primitive.vertex_count,
            "VEC3",
            minimum=pos_min,
            maximum=pos_max,
        )
        normal_accessor = add_accessor(pack_floats(primitive.normals), 34962, 5126, primitive.vertex_count, "VEC3")
        color_accessor = add_accessor(pack_floats(primitive.colors), 34962, 5126, primitive.vertex_count, "VEC4")
        index_accessor = add_accessor(pack_uint16(primitive.indices), 34963, 5123, len(primitive.indices), "SCALAR")
        gltf_primitives.append(
            {
                "attributes": {"POSITION": position_accessor, "NORMAL": normal_accessor, "COLOR_0": color_accessor},
                "indices": index_accessor,
                "material": primitive.material,
                "mode": 4,
            }
        )

    gltf = {
        "asset": {"version": "2.0", "generator": "OpenPanel generate-lakeside-foreground-glb.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "OpenPanel_Lakeside_Foreground"}],
        "meshes": [{"name": "LakesideForeground", "primitives": gltf_primitives}],
        "materials": [
            material("wet_shore_vertex_color", [0.58, 0.50, 0.36, 1.0], double_sided=True),
            material("shore_rocks_vertex_color", [0.66, 0.62, 0.54, 1.0]),
            material("shore_grass_vertex_color", [0.58, 0.72, 0.34, 1.0], double_sided=True),
            material("dry_reeds_vertex_color", [0.62, 0.47, 0.31, 1.0], double_sided=True),
        ],
        "buffers": [{"byteLength": len(binary)}],
        "bufferViews": buffer_views,
        "accessors": accessors,
    }

    json_chunk = pad_to_4(json.dumps(gltf, separators=(",", ":")).encode("utf-8"), b" ")
    bin_chunk = bytes(binary)
    total_length = 12 + 8 + len(json_chunk) + 8 + len(bin_chunk)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as file:
        file.write(struct.pack("<III", 0x46546C67, 2, total_length))
        file.write(struct.pack("<I4s", len(json_chunk), b"JSON"))
        file.write(json_chunk)
        file.write(struct.pack("<I4s", len(bin_chunk), b"BIN\x00"))
        file.write(bin_chunk)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--seed", type=int, default=20260821)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    rng = random.Random(args.seed)
    primitives = [make_terrain(), make_rocks(rng), make_grass(rng), make_branches(rng)]
    write_glb(args.output, primitives)
    vertex_count = sum(p.vertex_count for p in primitives)
    index_count = sum(len(p.indices) for p in primitives)
    print(f"wrote {args.output} ({args.output.stat().st_size:,} bytes)")
    print(f"vertices={vertex_count:,} indices={index_count:,}")


if __name__ == "__main__":
    main()
