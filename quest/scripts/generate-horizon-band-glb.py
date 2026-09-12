#!/usr/bin/env python3
"""Generate OpenPanel's high-density curved horizon band as an embedded GLB.

The normal skybox is still the 360° world fallback. This asset is a forward
curved mesh using a narrower angular crop, so the visible home direction gets a
higher pixel/degree budget without asking the Quest runtime to load a larger
monolithic skybox.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import struct
import subprocess
import tempfile
from pathlib import Path
from typing import Iterable

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LDR_SOURCE = REPO_ROOT / "scripts/sources/skydome_lakeside_dawn_polyhaven.jpg"
DEFAULT_HDR_SOURCE = REPO_ROOT / "scripts/sources/lakeside_dawn_16k.hdr"
DEFAULT_OUTPUT = REPO_ROOT / "app/src/main/assets/horizon_band_lakeside_v1.glb"


def angular_crop_box(width: int, height: int, h_fov: float, v_top: float, v_bottom: float) -> tuple[int, int, int, int]:
    crop_w = int(round(width * h_fov / 360.0))
    x0 = int(round((width - crop_w) / 2.0))
    y0 = int(round(height * (90.0 - v_top) / 180.0))
    y1 = int(round(height * (90.0 - v_bottom) / 180.0))
    y0 = max(0, min(height - 1, y0))
    y1 = max(y0 + 1, min(height, y1))
    return x0, y0, crop_w, y1 - y0


def load_reference_texture(source: Path, size: tuple[int, int], h_fov: float, v_top: float, v_bottom: float) -> Image.Image:
    with Image.open(source) as image:
        image = image.convert("RGB")
        x, y, w, h = angular_crop_box(image.width, image.height, h_fov, v_top, v_bottom)
        texture = image.crop((x, y, x + w, y + h)).resize(size, Image.Resampling.LANCZOS)

    # Keep the Poly Haven tonemapped grade, but compensate mildly for headset
    # lens/compositor softness. This is not used as a fake upscale substitute
    # when a 16K detail plate is available; it is just the color anchor.
    texture = ImageEnhance.Color(texture).enhance(1.04)
    texture = ImageEnhance.Contrast(texture).enhance(1.03)
    return texture


def ffmpeg_hdr_detail_plate(
    hdr_source: Path,
    output_path: Path,
    size: tuple[int, int],
    h_fov: float,
    v_top: float,
    v_bottom: float,
) -> bool:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None or not hdr_source.exists():
        return False

    # Poly Haven's 16K HDR is equirectangular 16384x8192. If another matching
    # Radiance source is supplied later, ffprobe can be added; this keeps the
    # current build path deterministic and avoids pulling in extra libraries.
    x, y, w, h = angular_crop_box(16384, 8192, h_fov, v_top, v_bottom)
    width, height = size
    vf = (
        f"crop={w}:{h}:{x}:{y},"
        "tonemap=hable:desat=0:peak=1,"
        "format=rgb24,"
        "lutrgb="
        "r='pow(val/255,0.4545)*255':"
        "g='pow(val/255,0.4545)*255':"
        "b='pow(val/255,0.4545)*255',"
        f"scale={width}:{height}:flags=lanczos"
    )
    command = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        str(hdr_source),
        "-vf",
        vf,
        "-frames:v",
        "1",
        str(output_path),
    ]
    subprocess.run(command, check=True)
    return output_path.exists()


def add_hdr_detail(reference: Image.Image, detail_plate: Image.Image, strength: float) -> Image.Image:
    ref = np.asarray(reference.convert("RGB")).astype(np.float32) / 255.0
    detail = np.asarray(detail_plate.convert("RGB").resize(reference.size, Image.Resampling.LANCZOS)).astype(np.float32) / 255.0

    weights = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    ref_luma = np.tensordot(ref, weights, axes=([-1], [0]))
    detail_luma = np.tensordot(detail, weights, axes=([-1], [0]))
    blurred_detail_luma = (
        np.asarray(
            Image.fromarray(np.uint8(np.clip(detail_luma * 255.0, 0, 255))).filter(
                ImageFilter.GaussianBlur(radius=4)
            )
        ).astype(np.float32)
        / 255.0
    )

    # Use the HDR only as local luminance detail. Bright-sky detail is damped
    # because the tonemap plate is weakest there and banding is more visible in
    # headset captures.
    high_pass = detail_luma - blurred_detail_luma
    sky_guard = np.clip((0.88 - ref_luma) / 0.50, 0.0, 1.0)
    target_luma = np.clip(ref_luma + np.clip(high_pass * strength * sky_guard, -0.09, 0.09), 0.0, 1.0)
    scale = target_luma / np.maximum(ref_luma, 1e-3)
    merged = np.clip(ref * scale[..., None], 0.0, 1.0)

    return Image.fromarray(np.uint8(np.round(merged * 255.0)))


def make_texture(args: argparse.Namespace, temp_dir: Path) -> bytes:
    texture_size = (args.texture_width, args.texture_height)
    texture = load_reference_texture(args.ldr_source, texture_size, args.horizontal_fov, args.vertical_top, args.vertical_bottom)

    if args.hdr_source:
        detail_path = temp_dir / "horizon_band_hdr_detail.jpg"
        if ffmpeg_hdr_detail_plate(args.hdr_source, detail_path, texture_size, args.horizontal_fov, args.vertical_top, args.vertical_bottom):
            texture = add_hdr_detail(texture, Image.open(detail_path), args.hdr_detail_strength)

    texture = texture.filter(ImageFilter.UnsharpMask(radius=args.sharpen_radius, percent=args.sharpen_percent, threshold=3))
    texture_path = temp_dir / "horizon_band_texture.jpg"
    texture.save(texture_path, quality=args.jpeg_quality, optimize=True, subsampling=0)
    return texture_path.read_bytes()


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


def create_geometry(args: argparse.Namespace) -> tuple[bytes, bytes, bytes, list[float], list[float], int, int]:
    cols = args.segments_x + 1
    rows = args.segments_y + 1
    positions: list[float] = []
    uvs: list[float] = []

    for row in range(rows):
        v_norm = row / args.segments_y
        phi_deg = args.vertical_top + (args.vertical_bottom - args.vertical_top) * v_norm
        phi = math.radians(phi_deg)
        y = args.radius * math.tan(phi)
        for col in range(cols):
            u_norm = col / args.segments_x
            theta_deg = -args.horizontal_fov / 2.0 + args.horizontal_fov * u_norm
            theta = math.radians(theta_deg)
            x = args.radius * math.sin(theta)
            z = -args.radius * math.cos(theta)
            positions.extend([x, y, z])
            uvs.extend([u_norm, v_norm])

    indices: list[int] = []
    for row in range(args.segments_y):
        for col in range(args.segments_x):
            a = row * cols + col
            b = a + 1
            c = a + cols
            d = c + 1
            # Double-sided material makes winding non-critical, but this order
            # faces inward for the camera at the origin in common glTF viewers.
            indices.extend([a, c, b, b, c, d])

    arr = np.asarray(positions, dtype=np.float32).reshape((-1, 3))
    return (
        pack_floats(positions),
        pack_floats(uvs),
        pack_uint16(indices),
        arr.min(axis=0).tolist(),
        arr.max(axis=0).tolist(),
        len(positions) // 3,
        len(indices),
    )


def write_glb(output: Path, texture_bytes: bytes, args: argparse.Namespace) -> None:
    positions, uvs, indices, pos_min, pos_max, vertex_count, index_count = create_geometry(args)

    binary = bytearray()
    pos_offset, pos_length = append_buffer(binary, positions)
    uv_offset, uv_length = append_buffer(binary, uvs)
    idx_offset, idx_length = append_buffer(binary, indices)
    img_offset, img_length = append_buffer(binary, texture_bytes)

    gltf = {
        "asset": {"version": "2.0", "generator": "OpenPanel generate-horizon-band-glb.py"},
        "extensionsUsed": ["KHR_materials_unlit"],
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "OpenPanel_Lakeside_Horizon_Band"}],
        "meshes": [
            {
                "name": "HorizonBand",
                "primitives": [
                    {
                        "attributes": {"POSITION": 0, "TEXCOORD_0": 1},
                        "indices": 2,
                        "material": 0,
                        "mode": 4,
                    }
                ],
            }
        ],
        "materials": [
            {
                "name": "HorizonBand_Lakeside_Dawn_HQ_Unlit",
                "doubleSided": True,
                "pbrMetallicRoughness": {
                    "baseColorTexture": {"index": 0},
                    "baseColorFactor": [1.0, 1.0, 1.0, 1.0],
                    "metallicFactor": 0.0,
                    "roughnessFactor": 1.0,
                },
                "extensions": {"KHR_materials_unlit": {}},
            }
        ],
        "textures": [{"sampler": 0, "source": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9729, "wrapS": 33071, "wrapT": 33071}],
        "images": [{"bufferView": 3, "mimeType": "image/jpeg", "name": "horizon_band_lakeside_v1.jpg"}],
        "buffers": [{"byteLength": len(binary)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": pos_offset, "byteLength": pos_length, "target": 34962},
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
            {"bufferView": 1, "componentType": 5126, "count": vertex_count, "type": "VEC2"},
            {"bufferView": 2, "componentType": 5123, "count": index_count, "type": "SCALAR"},
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
    parser.add_argument("--ldr-source", type=Path, default=DEFAULT_LDR_SOURCE)
    parser.add_argument("--hdr-source", type=Path, default=DEFAULT_HDR_SOURCE if DEFAULT_HDR_SOURCE.exists() else None)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--texture-width", type=int, default=8192)
    parser.add_argument("--texture-height", type=int, default=4096)
    parser.add_argument("--horizontal-fov", type=float, default=160.0)
    parser.add_argument("--vertical-top", type=float, default=42.0)
    parser.add_argument("--vertical-bottom", type=float, default=-34.0)
    parser.add_argument("--radius", type=float, default=38.0)
    parser.add_argument("--segments-x", type=int, default=128)
    parser.add_argument("--segments-y", type=int, default=48)
    parser.add_argument("--hdr-detail-strength", type=float, default=0.64)
    parser.add_argument("--sharpen-radius", type=float, default=0.65)
    parser.add_argument("--sharpen-percent", type=int, default=78)
    parser.add_argument("--jpeg-quality", type=int, default=98)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    with tempfile.TemporaryDirectory(prefix="openpanel-horizon-band.") as temp:
        texture_bytes = make_texture(args, Path(temp))
    write_glb(args.output, texture_bytes, args)
    print(f"wrote {args.output} ({args.output.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
