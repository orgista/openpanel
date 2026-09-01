#!/usr/bin/env python3
"""Generate a masked textured shoreline foreground GLB for OpenPanel.

This is a testable alternative to the rejected flat procedural terrain slab:
the geometry is still simple and Quest-safe, but the material is a real CC0
coastal texture with an irregular alpha mask so the foreground edge fades into
the existing lake panorama.
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import tempfile
from pathlib import Path
from typing import Iterable

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = REPO_ROOT / "scripts/sources/coast_sand_rocks_02_diff_4k.jpg"
DEFAULT_OUTPUT = REPO_ROOT / "app/src/main/assets/lakeside_foreground_v4.glb"


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


def smoothstep(edge0: float, edge1: float, value: np.ndarray) -> np.ndarray:
    t = np.clip((value - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def make_masked_texture(source: Path, output: Path, size: int = 4096) -> None:
    with Image.open(source) as image:
        texture = image.convert("RGB").resize((size, size), Image.Resampling.LANCZOS)
    texture = ImageEnhance.Color(texture).enhance(0.72)
    texture = ImageEnhance.Brightness(texture).enhance(0.88)
    texture = ImageEnhance.Contrast(texture).enhance(0.90)
    texture = texture.filter(ImageFilter.UnsharpMask(radius=0.85, percent=70, threshold=4))

    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    u = x / (size - 1)
    v = y / (size - 1)

    # v=0 is the far/upper edge, v=1 is near/lower edge. The shoreline is
    # deliberately irregular so it does not read as a straight rectangular
    # slab in headset captures.
    shoreline = 0.28 + 0.035 * np.sin(u * math.tau * 1.35) + 0.020 * np.sin(u * math.tau * 4.7)
    near = smoothstep(0.18, 0.58, v)
    far_cut = smoothstep(shoreline, shoreline + 0.13, v)
    bottom_fade = smoothstep(1.00, 0.84, v)
    side_fade = smoothstep(0.00, 0.10, u) * smoothstep(1.00, 0.90, u)

    alpha = np.clip(near * far_cut * bottom_fade * side_fade, 0.0, 1.0)
    # Subtle broken boundary detail without high-frequency sparkle.
    breakup = 0.86 + 0.10 * np.sin((u * 13.0 + v * 4.0) * math.tau) + 0.07 * np.sin((u * -6.0 + v * 9.0) * math.tau)
    alpha = np.clip(alpha * breakup, 0.0, 1.0)
    alpha_image = Image.fromarray(np.uint8(alpha * 135.0), "L").filter(ImageFilter.GaussianBlur(radius=1.35))
    rgba = texture.convert("RGBA")
    rgba.putalpha(alpha_image)
    rgba.save(output, optimize=True)


def create_geometry() -> tuple[list[float], list[float], list[float], list[int], list[float], list[float]]:
    # v4 keeps the same restrained footprint as v3 but uses enough vertices for
    # actual headset-rasterized depth and lighting. The geometry remains tiny
    # compared with the texture: 2,190 vertices / 12,180 indices.
    cols = 73
    rows = 30
    positions_by_vertex: list[tuple[float, float, float]] = []
    uvs: list[float] = []
    indices: list[int] = []
    for row in range(rows):
        v = row / (rows - 1)
        z = -3.05 - v * 9.65
        # Slight taper so side edges drift outside the lower FOV. Keeping the
        # center open avoids fighting the launcher panel.
        width = 16.4 - v * 1.9
        for col in range(cols):
            u = col / (cols - 1)
            x = (u - 0.5) * width
            center = abs(u - 0.5) * 2.0
            near_weight = smoothstep(0.10, 0.82, np.asarray(v, dtype=np.float32)).item()
            side_weight = smoothstep(0.12, 0.90, np.asarray(center, dtype=np.float32)).item()
            fine = 0.018 * math.sin(x * 2.0 - z * 0.75) + 0.010 * math.sin(x * 4.1 + z * 1.15)
            broad = 0.042 * math.sin(x * 0.55 + z * 0.28) + 0.020 * math.sin(x * -0.35 + z * 0.48)
            ripple = (broad + fine) * (0.45 + 0.55 * near_weight) * (0.72 + 0.28 * side_weight)
            y = -0.96 + v * 0.09 + ripple
            # Ease the far edge down so it blends into the photographic water
            # instead of creating a visible geometric seam.
            y -= (1.0 - near_weight) * 0.035
            positions_by_vertex.append((x, y, z))
            uvs.extend([u, v])
    for row in range(rows - 1):
        for col in range(cols - 1):
            a = row * cols + col
            b = a + 1
            c = a + cols
            d = c + 1
            indices.extend([a, b, c, b, d, c])

    normals_by_vertex = np.zeros((len(positions_by_vertex), 3), dtype=np.float64)
    arr = np.asarray(positions_by_vertex, dtype=np.float32)
    for i in range(0, len(indices), 3):
        ia, ib, ic = indices[i : i + 3]
        a = arr[ia].astype(np.float64)
        b = arr[ib].astype(np.float64)
        c = arr[ic].astype(np.float64)
        normal = np.cross(b - a, c - a)
        length = np.linalg.norm(normal)
        if length > 1e-8:
            normal /= length
            normals_by_vertex[ia] += normal
            normals_by_vertex[ib] += normal
            normals_by_vertex[ic] += normal
    lengths = np.linalg.norm(normals_by_vertex, axis=1)
    normals_by_vertex[lengths < 1e-8] = (0.0, 1.0, 0.0)
    lengths = np.linalg.norm(normals_by_vertex, axis=1)
    normals_by_vertex = normals_by_vertex / np.maximum(lengths[:, None], 1e-8)
    # Ensure the surface lights from above even if winding produces downward
    # normals on a viewer/runtime.
    normals_by_vertex[normals_by_vertex[:, 1] < 0.0] *= -1.0

    positions = [component for vertex in positions_by_vertex for component in vertex]
    normals = [float(component) for normal in normals_by_vertex for component in normal]
    return positions, normals, uvs, indices, arr.min(axis=0).tolist(), arr.max(axis=0).tolist()


def write_glb(output: Path, texture_bytes: bytes) -> None:
    positions, normals, uvs, indices, pos_min, pos_max = create_geometry()
    binary = bytearray()
    pos_offset, pos_length = append_buffer(binary, pack_floats(positions))
    normal_offset, normal_length = append_buffer(binary, pack_floats(normals))
    uv_offset, uv_length = append_buffer(binary, pack_floats(uvs))
    idx_offset, idx_length = append_buffer(binary, pack_uint16(indices))
    img_offset, img_length = append_buffer(binary, texture_bytes)

    vertex_count = len(positions) // 3
    gltf = {
        "asset": {"version": "2.0", "generator": "OpenPanel generate-textured-shoreline-glb.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "OpenPanel_Textured_Shoreline_Foreground"}],
        "meshes": [
            {
                "name": "TexturedShorelineForeground",
                "primitives": [
                    {
                        "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2},
                        "indices": 3,
                        "material": 0,
                        "mode": 4,
                    }
                ],
            }
        ],
        "materials": [
            {
                "name": "CoastSandRocks02_Masked",
                "doubleSided": True,
                "alphaMode": "BLEND",
                "alphaCutoff": 0.02,
                "pbrMetallicRoughness": {
                    "baseColorTexture": {"index": 0},
                    "baseColorFactor": [1.0, 1.0, 1.0, 1.0],
                    "metallicFactor": 0.0,
                    "roughnessFactor": 0.96,
                },
            }
        ],
        "textures": [{"sampler": 0, "source": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9729, "wrapS": 33071, "wrapT": 33071}],
        "images": [{"bufferView": 4, "mimeType": "image/png", "name": "coast_sand_rocks_02_masked.png"}],
        "buffers": [{"byteLength": len(binary)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": pos_offset, "byteLength": pos_length, "target": 34962},
            {"buffer": 0, "byteOffset": normal_offset, "byteLength": normal_length, "target": 34962},
            {"buffer": 0, "byteOffset": uv_offset, "byteLength": uv_length, "target": 34962},
            {"buffer": 0, "byteOffset": idx_offset, "byteLength": idx_length, "target": 34963},
            {"buffer": 0, "byteOffset": img_offset, "byteLength": img_length},
        ],
        "accessors": [
            {
                "bufferView": 0,
                "componentType": 5126,
                "count": vertex_count,
                "type": "VEC3",
                "min": pos_min,
                "max": pos_max,
            },
            {"bufferView": 1, "componentType": 5126, "count": vertex_count, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": vertex_count, "type": "VEC2"},
            {"bufferView": 3, "componentType": 5123, "count": len(indices), "type": "SCALAR"},
        ],
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
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--texture-size", type=int, default=4096)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    with tempfile.TemporaryDirectory(prefix="openpanel-shoreline-texture.") as temp:
        texture_path = Path(temp) / "coast_sand_rocks_02_masked.png"
        make_masked_texture(args.source, texture_path, args.texture_size)
        texture_bytes = texture_path.read_bytes()
    write_glb(args.output, texture_bytes)
    print(f"wrote {args.output} ({args.output.stat().st_size:,} bytes; embedded texture {len(texture_bytes):,} bytes)")


if __name__ == "__main__":
    main()
