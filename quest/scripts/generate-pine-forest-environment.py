#!/usr/bin/env python3
"""Generate OpenPanel's pine forest Quest environment assets.

The downloaded Poly Haven pine/fir Blender scene is too heavy to package or
render directly for the Quest launcher. This generator derives a Quest-safe
layered environment from its CC0 texture set and, when provided, a photoreal
forward creek render used as the panel-facing reference:

- an 8192x4096 equirectangular fallback dome;
- an 8192x4096 forward curved horizon GLB derived from the same dome crop;
- a local foreground GLB using a compact alpha atlas of ground, bark, boughs,
  and ferns;
- a subdued water surface texture retained for optional base-plane tests.
"""

from __future__ import annotations

import argparse
import json
import math
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageOps


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE_DIR = Path("/Users/macstudio/Downloads/pine_forest")
DEFAULT_REFERENCE_IMAGE = Path("/Users/macstudio/Downloads/43600e6a5e2d1b2de05000eb4bf89968e4613bc4.jpeg")
DEFAULT_DOME_OUTPUT = REPO_ROOT / "app/src/main/res/drawable-nodpi/skydome_pine_forest_fallback_v1.jpg"
DEFAULT_HORIZON_OUTPUT = REPO_ROOT / "app/src/main/assets/horizon_band_pine_forest_v1.glb"
DEFAULT_FOREGROUND_OUTPUT = REPO_ROOT / "app/src/main/assets/pine_forest_foreground_v1.glb"
DEFAULT_WATER_OUTPUT = REPO_ROOT / "app/src/main/res/drawable-nodpi/water_surface_pine_forest_v1.jpg"
DEFAULT_PREVIEW_OUTPUT = REPO_ROOT / "audits/pine-forest-generated/00-forward-preview.jpg"


@dataclass(frozen=True)
class RenderRange:
    vertical_top: float
    vertical_bottom: float
    horizontal_fov: float

    def row_for_angle(self, height: int, angle: float) -> float:
        return (self.vertical_top - angle) / (self.vertical_top - self.vertical_bottom) * (height - 1)


DOME_RANGE = RenderRange(vertical_top=90.0, vertical_bottom=-90.0, horizontal_fov=360.0)
HORIZON_RANGE = RenderRange(vertical_top=42.0, vertical_bottom=-38.0, horizontal_fov=170.0)


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


def smoothstep(edge0: float, edge1: float, value: np.ndarray | float) -> np.ndarray | float:
    t = np.clip((value - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def texture_path(source_dir: Path, name: str) -> Path:
    path = source_dir / "textures" / name
    if not path.exists():
        raise FileNotFoundError(f"Missing pine forest texture: {path}")
    return path


def load_rgb(source_dir: Path, name: str, size: tuple[int, int] | None = None) -> Image.Image:
    image = Image.open(texture_path(source_dir, name)).convert("RGB")
    if size is not None:
        image = image.resize(size, Image.Resampling.LANCZOS)
    return image


def load_alpha(source_dir: Path, name: str, size: tuple[int, int] | None = None) -> Image.Image:
    image = Image.open(texture_path(source_dir, name)).convert("L")
    if size is not None:
        image = image.resize(size, Image.Resampling.LANCZOS)
    return image


def tile_texture(tile: Image.Image, size: tuple[int, int]) -> Image.Image:
    output = Image.new(tile.mode, size)
    for y in range(0, size[1], tile.height):
        for x in range(0, size[0], tile.width):
            output.paste(tile, (x, y))
    return output


def grade_texture(image: Image.Image, *, color: float, brightness: float, contrast: float) -> Image.Image:
    image = ImageEnhance.Color(image).enhance(color)
    image = ImageEnhance.Brightness(image).enhance(brightness)
    image = ImageEnhance.Contrast(image).enhance(contrast)
    return image


def vertical_gradient(width: int, height: int, render_range: RenderRange) -> Image.Image:
    horizon = render_range.row_for_angle(height, 0.0) / (height - 1)
    stops = np.array(
        [
            0.00,
            max(0.0, horizon - 0.34),
            max(0.0, horizon - 0.13),
            max(0.0, horizon - 0.035),
            min(1.0, horizon + 0.035),
            1.00,
        ],
        dtype=np.float32,
    )
    colors = np.array(
        [
            (30, 45, 64),
            (60, 78, 86),
            (121, 139, 128),
            (184, 180, 151),
            (86, 104, 73),
            (35, 40, 31),
        ],
        dtype=np.float32,
    )
    y = np.linspace(0.0, 1.0, height, dtype=np.float32)
    channels = [np.interp(y, stops, colors[:, channel]) for channel in range(3)]
    base = np.stack(channels, axis=1).astype(np.int16)

    x = np.linspace(0.0, math.tau, width, dtype=np.float32)
    warm_gap = 10.0 * np.exp(-((x - math.pi) ** 2) / 0.85)
    cool_sides = -5.0 * (np.sin(x * 1.7) * 0.5 + 0.5)
    horizontal = (warm_gap + cool_sides).astype(np.int16)
    arr = np.repeat(base[:, None, :], width, axis=1)
    arr[:, :, 0] += horizontal[None, :]
    arr[:, :, 1] += (horizontal * 0.55)[None, :].astype(np.int16)
    arr[:, :, 2] -= (horizontal * 0.20)[None, :].astype(np.int16)

    return Image.fromarray(np.uint8(np.clip(arr, 0, 255)), "RGB")


def add_soft_noise(image: Image.Image, amount: float, seed: int) -> Image.Image:
    rng = np.random.default_rng(seed)
    small = rng.normal(128.0, 34.0, size=(max(8, image.height // 16), max(8, image.width // 16))).astype(np.uint8)
    noise = Image.fromarray(small, "L").resize(image.size, Image.Resampling.BICUBIC)
    noise = ImageOps.colorize(noise, black=(0, 0, 0), white=(255, 255, 255)).convert("RGB")
    return Image.blend(image, ImageChops.multiply(image, noise), amount)


def make_ground_plate(source_dir: Path, size: tuple[int, int]) -> Image.Image:
    base = load_rgb(source_dir, "forest_ground_04_diff.png", (1024, 1024))
    leaves = load_rgb(source_dir, "forest_leaves_04_diff.png", (1024, 1024))
    trail = load_rgb(source_dir, "rocky_trail_diff.png", (1024, 1024))
    base = Image.blend(base, leaves, 0.34)
    base = Image.blend(base, trail, 0.18)
    base = grade_texture(base, color=0.78, brightness=0.68, contrast=0.88)
    base = base.filter(ImageFilter.GaussianBlur(radius=0.35))
    return tile_texture(base, size)


def alpha_composite_layer(image: Image.Image, layer: Image.Image) -> Image.Image:
    return Image.alpha_composite(image.convert("RGBA"), layer).convert("RGB")


def add_ground(image: Image.Image, source_dir: Path, render_range: RenderRange) -> Image.Image:
    width, height = image.size
    horizon_y = int(render_range.row_for_angle(height, 0.0))
    start_y = max(0, horizon_y - int(height * 0.015))
    ground_h = height - start_y
    ground = make_ground_plate(source_dir, (width, ground_h)).convert("RGBA")

    y = np.linspace(0.0, 1.0, ground_h, dtype=np.float32)
    alpha = (smoothstep(0.00, 0.20, y) * 205.0).astype(np.uint8)
    side = np.linspace(0.0, 1.0, width, dtype=np.float32)
    side_fade = smoothstep(0.00, 0.035, side) * smoothstep(1.00, 0.965, side)
    alpha_2d = np.uint8(alpha[:, None] * side_fade[None, :])
    ground.putalpha(Image.fromarray(alpha_2d, "L"))

    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    layer.paste(ground, (0, start_y))
    return alpha_composite_layer(image, layer)


def conifer_points(x: float, base_y: float, height: float, width: float, level: int, levels: int) -> list[tuple[float, float]]:
    top = base_y - height
    level_t = level / max(1, levels - 1)
    band_top = top + height * (0.10 + level_t * 0.60)
    band_base = top + height * (0.30 + level_t * 0.66)
    half_width = width * (0.18 + level_t * 0.82) * 0.5
    return [(x, band_top), (x - half_width, band_base), (x + half_width, band_base)]


def draw_conifer(draw: ImageDraw.ImageDraw, x: float, base_y: float, height: float, fill: tuple[int, int, int, int], seed: int) -> None:
    rng = np.random.default_rng(seed)
    width = height * rng.uniform(0.30, 0.46)
    trunk_width = max(2.0, height * 0.028)
    trunk = (54, 45, 36, min(220, fill[3] + 28))
    draw.rectangle((x - trunk_width, base_y - height * 0.58, x + trunk_width, base_y + height * 0.05), fill=trunk)
    levels = int(rng.integers(8, 12))
    for level in range(levels):
        jitter = rng.uniform(-0.05, 0.05) * width
        color = (
            max(0, min(255, fill[0] + int(rng.integers(-8, 8)))),
            max(0, min(255, fill[1] + int(rng.integers(-7, 7)))),
            max(0, min(255, fill[2] + int(rng.integers(-6, 6)))),
            fill[3],
        )
        pts = conifer_points(x + jitter, base_y, height, width, level, levels)
        draw.polygon(pts, fill=color)


def multiply_alpha(image: Image.Image, alpha: int) -> Image.Image:
    image = image.copy()
    channel = image.getchannel("A").point(lambda value: int(value * alpha / 255))
    image.putalpha(channel)
    return image


def make_branch_cutout(source_dir: Path, kind: str, size: tuple[int, int]) -> Image.Image:
    if kind == "fir":
        diff_name = "fir_twig_diff.png"
        mask_name = "fir_twig_mask01.png"
        threshold = 12
        grade = dict(color=0.86, brightness=0.62, contrast=1.05)
    else:
        diff_name = "pine_twig_diff.png"
        mask_name = "pine_twig_mask.png"
        threshold = 18
        grade = dict(color=0.88, brightness=0.60, contrast=1.06)
    return make_cutout(grade_texture(load_rgb(source_dir, diff_name), **grade), load_alpha(source_dir, mask_name), size, threshold=threshold, soften=0.55)


def make_tree_sprite(source_dir: Path, kind: str) -> Image.Image:
    width = 768
    height = 1600
    sprite = Image.new("RGBA", (width, height), (0, 0, 0, 0))

    bark = load_rgb(source_dir, "pine_bark_diff.png", (96, 1160))
    bark = grade_texture(bark, color=0.68, brightness=0.55, contrast=1.10).convert("RGBA")
    trunk_alpha = Image.new("L", bark.size, 255)
    edge = np.linspace(0.0, 1.0, bark.width, dtype=np.float32)
    edge_alpha = (smoothstep(0.0, 0.18, edge) * smoothstep(1.0, 0.82, edge) * 255.0).astype(np.uint8)
    trunk_alpha = Image.fromarray(np.repeat(edge_alpha[None, :], bark.height, axis=0), "L")
    bark.putalpha(trunk_alpha)
    sprite.paste(bark, ((width - bark.width) // 2, height - bark.height + 10), bark)

    branch = make_branch_cutout(source_dir, kind, (620, 430))
    tiers = 9 if kind == "pine" else 10
    for tier in range(tiers):
        t = tier / max(1, tiers - 1)
        tier_w = int(width * (0.92 - 0.48 * t))
        tier_h = int(340 * (0.92 - 0.42 * t))
        y = int(height - 330 - tier * (118 if kind == "pine" else 108))
        x = int((width - tier_w) / 2 + math.sin(tier * 1.73) * 22)
        bough = branch.resize((tier_w, tier_h), Image.Resampling.LANCZOS)
        if tier % 2:
            bough = ImageOps.mirror(bough)
        sprite.paste(bough, (x, y), bough)

    top = branch.resize((int(width * 0.34), 310), Image.Resampling.LANCZOS)
    sprite.paste(top, ((width - top.width) // 2, 55), top)
    return sprite.filter(ImageFilter.UnsharpMask(radius=0.35, percent=45, threshold=5))


def add_tree_layer(
    image: Image.Image,
    *,
    sprites: tuple[Image.Image, Image.Image],
    count: int,
    base_y: float,
    base_jitter: float,
    height_range: tuple[float, float],
    color: tuple[int, int, int],
    alpha: int,
    blur: float,
    seed: int,
    avoid_center: bool,
) -> Image.Image:
    width, height = image.size
    rng = np.random.default_rng(seed)
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    drawn = 0
    attempts = 0
    while drawn < count and attempts < count * 8:
        attempts += 1
        x = float(rng.uniform(-width * 0.03, width * 1.03))
        if avoid_center and width * 0.39 < x < width * 0.61:
            continue
        tree_h = float(rng.uniform(*height_range))
        tree_base = float(base_y + rng.normal(0.0, base_jitter))
        shade = int(rng.integers(-14, 15))
        fill = (
            max(0, min(255, color[0] + shade)),
            max(0, min(255, color[1] + shade)),
            max(0, min(255, color[2] + shade)),
            alpha,
        )
        draw = ImageDraw.Draw(layer)
        for wrapped_x in (x - width, x, x + width):
            if -tree_h < wrapped_x < width + tree_h:
                draw_conifer(draw, wrapped_x, tree_base, tree_h, fill, seed + drawn * 17)
        drawn += 1
    if blur > 0:
        layer = layer.filter(ImageFilter.GaussianBlur(radius=blur))
    return alpha_composite_layer(image, layer)


def add_forest(image: Image.Image, source_dir: Path, render_range: RenderRange, *, detailed: bool) -> Image.Image:
    width, height = image.size
    horizon_y = render_range.row_for_angle(height, 0.0)
    scale = width / 8192.0
    sprites: tuple[Image.Image, Image.Image] = ()
    if detailed:
        layers = [
            (760, horizon_y + 95 * scale, 24 * scale, (190 * scale, 500 * scale), (62, 82, 55), 116, 1.45, 7101, False),
            (420, horizon_y + 245 * scale, 44 * scale, (500 * scale, 1040 * scale), (39, 66, 45), 166, 0.75, 7127, False),
            (92, horizon_y + 555 * scale, 65 * scale, (1050 * scale, 1980 * scale), (28, 48, 36), 213, 0.22, 7169, True),
        ]
    else:
        layers = [
            (560, horizon_y + 55 * scale, 18 * scale, (120 * scale, 330 * scale), (68, 84, 55), 102, 1.55, 5101, False),
            (290, horizon_y + 175 * scale, 34 * scale, (300 * scale, 740 * scale), (44, 65, 45), 154, 0.95, 5129, False),
            (58, horizon_y + 450 * scale, 58 * scale, (760 * scale, 1460 * scale), (29, 47, 35), 200, 0.45, 5167, True),
        ]
    for args in layers:
        image = add_tree_layer(
            image,
            sprites=sprites,
            count=args[0],
            base_y=args[1],
            base_jitter=args[2],
            height_range=args[3],
            color=args[4],
            alpha=args[5],
            blur=args[6],
            seed=args[7],
            avoid_center=args[8],
        )
    return image


def add_stream(image: Image.Image, render_range: RenderRange, *, detailed: bool) -> Image.Image:
    width, height = image.size
    horizon_y = int(render_range.row_for_angle(height, 0.0))
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    start_y = horizon_y + int(height * 0.015)
    ys = np.linspace(start_y, height - 1, 150, dtype=np.float32)
    t = (ys - start_y) / max(1.0, height - start_y)
    center = width * (0.50 + 0.020 * np.sin(t * math.tau * 1.25 + 0.25))
    half = width * (0.012 + (0.162 if detailed else 0.145) * (t**1.72))
    left = list(zip((center - half).tolist(), ys.tolist()))
    right = list(zip((center + half).tolist(), ys.tolist()))[::-1]
    water_poly = left + right

    bank_expand = width * (0.010 + 0.030 * (t**1.2))
    left_bank = list(zip((center - half - bank_expand).tolist(), ys.tolist())) + left[::-1]
    right_bank = list(zip((center + half + bank_expand).tolist(), ys.tolist())) + right[::-1]
    draw.polygon(left_bank, fill=(47, 53, 38, 116))
    draw.polygon(right_bank, fill=(45, 51, 37, 116))
    draw.polygon(water_poly, fill=(50, 77, 82, 172 if detailed else 146))

    line_count = 34 if detailed else 22
    for i in range(line_count):
        yy = start_y + (height - start_y) * (i + 0.5) / line_count
        tt = (yy - start_y) / max(1.0, height - start_y)
        cx = width * (0.50 + 0.020 * math.sin(tt * math.tau * 1.25 + 0.25))
        hw = width * (0.012 + (0.162 if detailed else 0.145) * (tt**1.72))
        line_w = hw * 1.45
        alpha = int(28 + 50 * tt)
        draw.arc((cx - line_w, yy - 18, cx + line_w, yy + 18), 190, 350, fill=(159, 184, 174, alpha), width=max(1, int(2 * width / 8192)))

    layer = layer.filter(ImageFilter.GaussianBlur(radius=0.35 if detailed else 0.55))
    return alpha_composite_layer(image, layer)


def add_haze(image: Image.Image, render_range: RenderRange, *, detailed: bool) -> Image.Image:
    width, height = image.size
    horizon_y = int(render_range.row_for_angle(height, 0.0))
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    bands = 26 if detailed else 20
    for i in range(bands):
        offset = (i - bands * 0.42) / bands
        yy = horizon_y + offset * height * 0.20
        alpha = int((36 if detailed else 44) * (1.0 - abs(offset) * 1.45))
        if alpha <= 0:
            continue
        draw.rectangle((0, yy - height * 0.012, width, yy + height * 0.012), fill=(176, 184, 158, alpha))
    layer = layer.filter(ImageFilter.GaussianBlur(radius=height * (0.010 if detailed else 0.014)))
    return alpha_composite_layer(image, layer)


def render_environment(width: int, height: int, source_dir: Path, render_range: RenderRange, *, detailed: bool) -> Image.Image:
    image = vertical_gradient(width, height, render_range)
    image = add_soft_noise(image, amount=0.045 if detailed else 0.035, seed=9001 if detailed else 8081)
    image = add_ground(image, source_dir, render_range)
    image = add_stream(image, render_range, detailed=detailed)
    image = add_forest(image, source_dir, render_range, detailed=detailed)
    image = add_haze(image, render_range, detailed=detailed)
    image = ImageEnhance.Color(image).enhance(0.94)
    image = ImageEnhance.Contrast(image).enhance(0.97)
    image = image.filter(ImageFilter.UnsharpMask(radius=0.55 if detailed else 0.35, percent=42 if detailed else 20, threshold=6))
    return image


def make_water_surface(width: int, height: int) -> Image.Image:
    y = np.linspace(0.0, 1.0, height, dtype=np.float32)
    stops = np.array([0.0, 0.45, 1.0], dtype=np.float32)
    colors = np.array([(35, 58, 61), (54, 78, 76), (31, 48, 45)], dtype=np.float32)
    channels = [np.interp(y, stops, colors[:, channel]) for channel in range(3)]
    arr = np.repeat(np.stack(channels, axis=1)[:, None, :], width, axis=1)
    x = np.linspace(0.0, math.tau, width, dtype=np.float32)
    ripple = 10.0 * np.sin(x * 8.0)[None, :] + 5.0 * np.sin((x * 17.0)[None, :] + y[:, None] * 15.0)
    arr[:, :, 1] += ripple * 0.45
    arr[:, :, 2] += ripple * 0.70
    return Image.fromarray(np.uint8(np.clip(arr, 0, 255)), "RGB").filter(ImageFilter.GaussianBlur(radius=0.35))


def save_jpeg(image: Image.Image, path: Path, *, quality: int, subsampling: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, quality=quality, optimize=True, progressive=True, subsampling=subsampling)


def cover_resize(image: Image.Image, size: tuple[int, int], *, center_y: float = 0.50) -> Image.Image:
    target_w, target_h = size
    scale = max(target_w / image.width, target_h / image.height)
    resized = image.resize((int(round(image.width * scale)), int(round(image.height * scale))), Image.Resampling.LANCZOS)
    left = max(0, (resized.width - target_w) // 2)
    top = max(0, int(round((resized.height - target_h) * center_y)))
    top = min(top, max(0, resized.height - target_h))
    return resized.crop((left, top, left + target_w, top + target_h))


def horizon_box(width: int, height: int) -> tuple[int, int, int, int]:
    x0 = int(round(width * (180.0 - HORIZON_RANGE.horizontal_fov / 2.0) / 360.0))
    x1 = int(round(width * (180.0 + HORIZON_RANGE.horizontal_fov / 2.0) / 360.0))
    y0 = int(round(height * (90.0 - HORIZON_RANGE.vertical_top) / 180.0))
    y1 = int(round(height * (90.0 - HORIZON_RANGE.vertical_bottom) / 180.0))
    return x0, y0, x1, y1


def make_reference_dome(reference_image: Path, size: tuple[int, int]) -> Image.Image:
    reference = Image.open(reference_image).convert("RGB")
    base = cover_resize(reference, size, center_y=0.42)
    base = base.filter(ImageFilter.GaussianBlur(radius=max(18.0, size[0] / 220.0)))
    base = grade_texture(base, color=0.86, brightness=0.80, contrast=0.88)

    x0, y0, x1, y1 = horizon_box(*size)
    plate = cover_resize(reference, (x1 - x0, y1 - y0), center_y=0.46)
    plate = ImageEnhance.Color(plate).enhance(1.04)
    plate = ImageEnhance.Contrast(plate).enhance(1.03)
    plate = plate.filter(ImageFilter.UnsharpMask(radius=0.55, percent=35, threshold=5))

    x = np.linspace(0.0, 1.0, plate.width, dtype=np.float32)
    side_alpha = smoothstep(0.00, 0.045, x) * smoothstep(1.00, 0.955, x)
    alpha = np.uint8(np.repeat((side_alpha * 255.0)[None, :], plate.height, axis=0))
    plate_rgba = plate.convert("RGBA")
    plate_rgba.putalpha(Image.fromarray(alpha, "L").filter(ImageFilter.GaussianBlur(radius=0.6)))

    dome = base.convert("RGBA")
    dome.alpha_composite(plate_rgba, (x0, y0))
    return dome.convert("RGB")


def make_reference_water_surface(reference_image: Path, width: int, height: int) -> Image.Image:
    reference = Image.open(reference_image).convert("RGB")
    crop = reference.crop(
        (
            int(reference.width * 0.18),
            int(reference.height * 0.52),
            int(reference.width * 0.82),
            int(reference.height * 0.96),
        )
    )
    row = Image.new("RGB", (crop.width * 2, crop.height))
    row.paste(crop, (0, 0))
    row.paste(ImageOps.mirror(crop), (crop.width, 0))
    tile = Image.new("RGB", (row.width, row.height * 2))
    tile.paste(row, (0, 0))
    tile.paste(ImageOps.flip(row), (0, row.height))
    tile = tile.resize((width, height), Image.Resampling.LANCZOS)
    tile = ImageEnhance.Color(tile).enhance(0.92)
    tile = ImageEnhance.Contrast(tile).enhance(0.96)
    return tile.filter(ImageFilter.GaussianBlur(radius=0.25))


def crop_aligned_horizon_from_dome(dome: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Crop the exact equirectangular angle window used by the GLB mesh.

    The forward band must not be independently rendered from the fallback dome:
    any independent random tree placement causes the Quest overlay to look
    chopped or misaligned where the band crosses the dome.
    """

    x0, y0, x1, y1 = horizon_box(dome.width, dome.height)
    crop = dome.crop((x0, y0, x1, y1)).resize(size, Image.Resampling.LANCZOS)
    # Keep this conservative: the main objective is alignment, not fake detail.
    crop = ImageEnhance.Contrast(crop).enhance(1.01)
    crop = crop.filter(ImageFilter.UnsharpMask(radius=0.45, percent=22, threshold=6))
    return crop


def horizon_geometry(
    *,
    h_fov: float,
    vertical_top: float,
    vertical_bottom: float,
    radius: float,
    segments_x: int,
    segments_y: int,
) -> tuple[bytes, bytes, bytes, list[float], list[float], int, int]:
    cols = segments_x + 1
    rows = segments_y + 1
    positions: list[float] = []
    uvs: list[float] = []
    for row in range(rows):
        v_norm = row / segments_y
        phi_deg = vertical_top + (vertical_bottom - vertical_top) * v_norm
        phi = math.radians(phi_deg)
        y = radius * math.tan(phi)
        for col in range(cols):
            u_norm = col / segments_x
            theta_deg = -h_fov / 2.0 + h_fov * u_norm
            theta = math.radians(theta_deg)
            x = radius * math.sin(theta)
            z = -radius * math.cos(theta)
            positions.extend([x, y, z])
            uvs.extend([u_norm, v_norm])

    indices: list[int] = []
    for row in range(segments_y):
        for col in range(segments_x):
            a = row * cols + col
            b = a + 1
            c = a + cols
            d = c + 1
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


def write_horizon_glb(texture_bytes: bytes, output: Path) -> None:
    positions, uvs, indices, pos_min, pos_max, vertex_count, index_count = horizon_geometry(
        h_fov=HORIZON_RANGE.horizontal_fov,
        vertical_top=HORIZON_RANGE.vertical_top,
        vertical_bottom=HORIZON_RANGE.vertical_bottom,
        radius=38.0,
        segments_x=144,
        segments_y=56,
    )
    binary = bytearray()
    pos_offset, pos_length = append_buffer(binary, positions)
    uv_offset, uv_length = append_buffer(binary, uvs)
    idx_offset, idx_length = append_buffer(binary, indices)
    img_offset, img_length = append_buffer(binary, texture_bytes)

    gltf = {
        "asset": {"version": "2.0", "generator": "OpenPanel generate-pine-forest-environment.py"},
        "extensionsUsed": ["KHR_materials_unlit"],
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "OpenPanel_Pine_Forest_Horizon_Band"}],
        "meshes": [
            {
                "name": "PineForestHorizonBand",
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
                "name": "Pine_Forest_Horizon_Unlit",
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
        "images": [{"bufferView": 3, "mimeType": "image/jpeg", "name": "horizon_band_pine_forest_v1.jpg"}],
        "buffers": [{"byteLength": len(binary)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": pos_offset, "byteLength": pos_length, "target": 34962},
            {"buffer": 0, "byteOffset": uv_offset, "byteLength": uv_length, "target": 34962},
            {"buffer": 0, "byteOffset": idx_offset, "byteLength": idx_length, "target": 34963},
            {"buffer": 0, "byteOffset": img_offset, "byteLength": img_length},
        ],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": vertex_count, "type": "VEC3", "min": pos_min, "max": pos_max},
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


def atlas_rect(x0: int, y0: int, x1: int, y1: int, size: int = 2048) -> tuple[float, float, float, float]:
    return x0 / size, y0 / size, x1 / size, y1 / size


def make_cutout(diff: Image.Image, alpha: Image.Image, size: tuple[int, int], *, threshold: int = 8, soften: float = 0.9) -> Image.Image:
    diff = diff.resize(size, Image.Resampling.LANCZOS).convert("RGBA")
    alpha = ImageOps.autocontrast(alpha.resize(size, Image.Resampling.LANCZOS).convert("L"))
    alpha = alpha.point(lambda v: 255 if v > threshold else 0)
    if soften > 0:
        alpha = alpha.filter(ImageFilter.GaussianBlur(radius=soften))
    diff.putalpha(alpha)
    return diff


def make_foreground_atlas(source_dir: Path) -> Image.Image:
    atlas = Image.new("RGBA", (2048, 2048), (0, 0, 0, 0))

    ground = make_ground_plate(source_dir, (1024, 1024)).convert("RGBA")
    ground.putalpha(255)
    atlas.paste(ground, (0, 0))

    roots = load_rgb(source_dir, "tree_roots_01_diff.png", (1024, 1024))
    roots = grade_texture(roots, color=0.72, brightness=0.62, contrast=0.96).convert("RGBA")
    roots.putalpha(245)
    atlas.paste(roots, (0, 1024))

    bark = load_rgb(source_dir, "pine_bark_diff.png", (512, 2048))
    bark = grade_texture(bark, color=0.70, brightness=0.58, contrast=1.08).convert("RGBA")
    bark.putalpha(255)
    atlas.paste(bark, (1024, 0))

    pine = make_cutout(
        grade_texture(load_rgb(source_dir, "pine_twig_diff.png"), color=0.85, brightness=0.58, contrast=1.03),
        load_alpha(source_dir, "pine_twig_mask.png"),
        (512, 1024),
        threshold=20,
        soften=0.75,
    )
    atlas.paste(pine, (1536, 0), pine)

    fern = make_cutout(
        grade_texture(load_rgb(source_dir, "fern_02_diff.png"), color=0.78, brightness=0.56, contrast=0.96),
        load_alpha(source_dir, "fern_02_alpha.png"),
        (512, 512),
        threshold=24,
        soften=0.65,
    )
    atlas.paste(fern, (1536, 1024), fern)

    grass = make_cutout(
        grade_texture(load_rgb(source_dir, "grass_medium_01_dry_diff.png"), color=0.74, brightness=0.58, contrast=0.96),
        load_alpha(source_dir, "grass_medium_01_alpha.png"),
        (512, 512),
        threshold=18,
        soften=0.65,
    )
    atlas.paste(grass, (1536, 1536), grass)
    return atlas


class MeshBuilder:
    def __init__(self) -> None:
        self.positions: list[float] = []
        self.normals: list[float] = []
        self.uvs: list[float] = []
        self.indices: list[int] = []

    def add_vertex(self, position: tuple[float, float, float], normal: tuple[float, float, float], uv: tuple[float, float]) -> int:
        index = len(self.positions) // 3
        self.positions.extend(position)
        self.normals.extend(normal)
        self.uvs.extend(uv)
        return index

    def add_quad(
        self,
        corners: list[tuple[float, float, float]],
        normal: tuple[float, float, float],
        uv_rect: tuple[float, float, float, float],
    ) -> None:
        u0, v0, u1, v1 = uv_rect
        ids = [
            self.add_vertex(corners[0], normal, (u0, v1)),
            self.add_vertex(corners[1], normal, (u1, v1)),
            self.add_vertex(corners[2], normal, (u1, v0)),
            self.add_vertex(corners[3], normal, (u0, v0)),
        ]
        self.indices.extend([ids[0], ids[1], ids[2], ids[0], ids[2], ids[3]])


def facing_basis(x: float, z: float) -> tuple[np.ndarray, np.ndarray]:
    normal = np.array([-x, 0.0, -z], dtype=np.float64)
    length = np.linalg.norm(normal)
    if length < 1e-6:
        normal = np.array([0.0, 0.0, 1.0], dtype=np.float64)
    else:
        normal /= length
    up = np.array([0.0, 1.0, 0.0], dtype=np.float64)
    right = np.cross(up, normal)
    right /= max(np.linalg.norm(right), 1e-6)
    return right, normal


def add_vertical_card(
    mesh: MeshBuilder,
    *,
    x: float,
    z: float,
    bottom_y: float,
    width: float,
    height: float,
    uv_rect: tuple[float, float, float, float],
    lean: float = 0.0,
) -> None:
    right, normal = facing_basis(x, z)
    base = np.array([x, bottom_y, z], dtype=np.float64)
    top = base + np.array([lean, height, 0.0], dtype=np.float64)
    corners = [
        tuple(base - right * width * 0.5),
        tuple(base + right * width * 0.5),
        tuple(top + right * width * 0.5),
        tuple(top - right * width * 0.5),
    ]
    mesh.add_quad(corners, tuple(normal.tolist()), uv_rect)


def add_ground_grid(
    mesh: MeshBuilder,
    *,
    side: int,
    x_inner: float,
    x_outer: float,
    z_near: float,
    z_far: float,
    base_y: float,
    uv_rect: tuple[float, float, float, float],
) -> None:
    cols = 13
    rows = 18
    ids: list[list[int]] = []
    u0, v0, u1, v1 = uv_rect
    for row in range(rows):
        t = row / (rows - 1)
        z = z_near + (z_far - z_near) * t
        width_ease = smoothstep(0.0, 1.0, t)
        inner = x_inner + side * 0.25 * math.sin(t * math.tau * 0.65)
        outer = x_outer + side * (0.35 + 0.90 * width_ease)
        row_ids: list[int] = []
        for col in range(cols):
            u = col / (cols - 1)
            x = inner + (outer - inner) * u
            y = base_y + 0.035 * math.sin(x * 1.35 + z * 0.6) + 0.018 * math.sin(x * 3.2 - z * 0.7)
            uv = (u0 + (u1 - u0) * u, v0 + (v1 - v0) * t)
            row_ids.append(mesh.add_vertex((x, y, z), (0.0, 1.0, 0.0), uv))
        ids.append(row_ids)
    for row in range(rows - 1):
        for col in range(cols - 1):
            a = ids[row][col]
            b = ids[row][col + 1]
            c = ids[row + 1][col]
            d = ids[row + 1][col + 1]
            mesh.indices.extend([a, b, c, b, d, c])


def build_foreground_mesh() -> MeshBuilder:
    rng = np.random.default_rng(20260823)
    mesh = MeshBuilder()
    ground_uv = atlas_rect(0, 0, 1024, 1024)
    roots_uv = atlas_rect(0, 1024, 1024, 2048)
    bark_uv = atlas_rect(1024, 0, 1536, 2048)
    pine_uv = atlas_rect(1536, 0, 2048, 1024)
    fern_uv = atlas_rect(1536, 1024, 2048, 1536)
    grass_uv = atlas_rect(1536, 1536, 2048, 2048)

    add_ground_grid(mesh, side=-1, x_inner=-1.85, x_outer=-5.4, z_near=-2.4, z_far=-10.2, base_y=-0.93, uv_rect=ground_uv)
    add_ground_grid(mesh, side=1, x_inner=1.85, x_outer=5.4, z_near=-2.4, z_far=-10.2, base_y=-0.93, uv_rect=ground_uv)

    for side in (-1, 1):
        add_vertical_card(mesh, x=side * 2.7, z=-2.55, bottom_y=-0.96, width=2.05, height=0.62, uv_rect=roots_uv, lean=side * 0.18)
        add_vertical_card(mesh, x=side * 4.45, z=-4.45, bottom_y=-0.96, width=2.40, height=0.72, uv_rect=roots_uv, lean=side * 0.10)

    trunk_positions = [
        (-6.6, -3.1, 5.8, 0.42),
        (-4.7, -4.8, 6.6, 0.36),
        (-7.7, -7.4, 7.2, 0.52),
        (-3.3, -8.8, 5.2, 0.32),
        (6.5, -3.4, 6.2, 0.45),
        (4.55, -5.1, 5.9, 0.36),
        (7.65, -7.8, 7.0, 0.50),
        (3.35, -9.2, 5.0, 0.32),
    ]
    for index, (x, z, height, width) in enumerate(trunk_positions):
        add_vertical_card(mesh, x=x, z=z, bottom_y=-0.98, width=width, height=height, uv_rect=bark_uv, lean=float(rng.normal(0.0, 0.08)))
        for _ in range(3):
            bough_y = -0.98 + height * float(rng.uniform(0.40, 0.82))
            bough_h = float(rng.uniform(0.95, 1.55))
            bough_w = float(rng.uniform(1.20, 2.15))
            bx = x + float(rng.normal(0.0, 0.45))
            bz = z + float(rng.normal(0.0, 0.35))
            add_vertical_card(mesh, x=bx, z=bz, bottom_y=bough_y, width=bough_w, height=bough_h, uv_rect=pine_uv, lean=float(rng.normal(0.0, 0.16)))

    for side in (-1, 1):
        for i in range(15):
            z = float(rng.uniform(-8.5, -2.25))
            x = side * float(rng.uniform(2.15, 6.6))
            bottom = -0.99 + float(rng.uniform(-0.02, 0.05))
            height = float(rng.uniform(0.45, 0.95))
            width = float(rng.uniform(0.35, 0.80))
            add_vertical_card(mesh, x=x, z=z, bottom_y=bottom, width=width, height=height, uv_rect=fern_uv if i % 2 else grass_uv)
    return mesh


def write_foreground_glb(texture_bytes: bytes, output: Path) -> None:
    mesh = build_foreground_mesh()
    if len(mesh.positions) // 3 > 65535:
        raise ValueError("Foreground mesh exceeded uint16 index budget")

    arr = np.asarray(mesh.positions, dtype=np.float32).reshape((-1, 3))
    binary = bytearray()
    pos_offset, pos_length = append_buffer(binary, pack_floats(mesh.positions))
    normal_offset, normal_length = append_buffer(binary, pack_floats(mesh.normals))
    uv_offset, uv_length = append_buffer(binary, pack_floats(mesh.uvs))
    idx_offset, idx_length = append_buffer(binary, pack_uint16(mesh.indices))
    img_offset, img_length = append_buffer(binary, texture_bytes)

    gltf = {
        "asset": {"version": "2.0", "generator": "OpenPanel generate-pine-forest-environment.py"},
        "extensionsUsed": ["KHR_materials_unlit"],
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "OpenPanel_Pine_Forest_Foreground"}],
        "meshes": [
            {
                "name": "PineForestForeground",
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
                "name": "Pine_Forest_Foreground_Atlas_Unlit",
                "doubleSided": True,
                "alphaMode": "BLEND",
                "alphaCutoff": 0.02,
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
        "images": [{"bufferView": 4, "mimeType": "image/png", "name": "pine_forest_foreground_atlas_v1.png"}],
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
                "count": len(mesh.positions) // 3,
                "type": "VEC3",
                "min": arr.min(axis=0).tolist(),
                "max": arr.max(axis=0).tolist(),
            },
            {"bufferView": 1, "componentType": 5126, "count": len(mesh.normals) // 3, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": len(mesh.uvs) // 2, "type": "VEC2"},
            {"bufferView": 3, "componentType": 5123, "count": len(mesh.indices), "type": "SCALAR"},
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


def make_preview(dome: Image.Image, horizon: Image.Image, output: Path) -> None:
    dome_crop = crop_aligned_horizon_from_dome(dome, (1600, 720))
    horizon_crop = horizon.resize((1600, 720), Image.Resampling.LANCZOS)
    preview = Image.new("RGB", (1600, 1440), (0, 0, 0))
    preview.paste(dome_crop, (0, 0))
    preview.paste(horizon_crop, (0, 720))
    draw = ImageDraw.Draw(preview)
    draw.text((24, 24), "8K fallback dome forward crop", fill=(230, 230, 220))
    draw.text((24, 744), "aligned forward horizon band texture", fill=(230, 230, 220))
    output.parent.mkdir(parents=True, exist_ok=True)
    save_jpeg(preview, output, quality=92, subsampling=1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=DEFAULT_SOURCE_DIR)
    parser.add_argument("--reference-image", type=Path, default=DEFAULT_REFERENCE_IMAGE)
    parser.add_argument("--dome-output", type=Path, default=DEFAULT_DOME_OUTPUT)
    parser.add_argument("--horizon-output", type=Path, default=DEFAULT_HORIZON_OUTPUT)
    parser.add_argument("--foreground-output", type=Path, default=DEFAULT_FOREGROUND_OUTPUT)
    parser.add_argument("--water-output", type=Path, default=DEFAULT_WATER_OUTPUT)
    parser.add_argument("--preview-output", type=Path, default=DEFAULT_PREVIEW_OUTPUT)
    parser.add_argument("--dome-width", type=int, default=8192)
    parser.add_argument("--dome-height", type=int, default=4096)
    parser.add_argument("--horizon-width", type=int, default=8192)
    parser.add_argument("--horizon-height", type=int, default=4096)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source_dir = args.source_dir.expanduser().resolve()
    if not source_dir.exists():
        raise FileNotFoundError(f"Missing pine forest source directory: {source_dir}")

    reference_image = args.reference_image.expanduser().resolve() if args.reference_image else None
    if reference_image and reference_image.exists():
        dome = make_reference_dome(reference_image, (args.dome_width, args.dome_height))
    else:
        dome = render_environment(args.dome_width, args.dome_height, source_dir, DOME_RANGE, detailed=False)
    save_jpeg(dome, args.dome_output, quality=92, subsampling=1)

    horizon = crop_aligned_horizon_from_dome(dome, (args.horizon_width, args.horizon_height))
    horizon_texture_path = args.horizon_output.with_suffix(".texture.jpg")
    save_jpeg(horizon, horizon_texture_path, quality=95, subsampling=0)
    write_horizon_glb(horizon_texture_path.read_bytes(), args.horizon_output)
    horizon_texture_path.unlink(missing_ok=True)

    atlas = make_foreground_atlas(source_dir)
    atlas_path = args.foreground_output.with_suffix(".atlas.png")
    atlas.save(atlas_path, optimize=True)
    write_foreground_glb(atlas_path.read_bytes(), args.foreground_output)
    atlas_path.unlink(missing_ok=True)

    if reference_image and reference_image.exists():
        water = make_reference_water_surface(reference_image, 4096, 2048)
    else:
        water = make_water_surface(4096, 2048)
    save_jpeg(water, args.water_output, quality=91, subsampling=1)
    make_preview(dome, horizon, args.preview_output)

    for path in (args.dome_output, args.horizon_output, args.foreground_output, args.water_output, args.preview_output):
        print(f"wrote {path} ({path.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
