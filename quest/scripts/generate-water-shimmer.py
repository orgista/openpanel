#!/usr/bin/env python3
"""Generate OpenPanel's animated-water glint texture (v3).

Modeled on the tutorial's water (two noise fields mixed, animated): the
believable look is DAPPLED, cellular sparkle — thin irregular webs like sun
caustics on ripples — not smooth sine waves (v2's rings read as soap swirls
over the baked river, Anthony 2026-08-23). Contour bands of band-limited
periodic noise give exactly those webs, and FFT synthesis makes the tile
seamless, so the mesh-confined water surface can tile it without fades.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

REPO_ROOT = Path(__file__).resolve().parents[1]
OUTPUT = REPO_ROOT / "app/src/main/res/drawable-nodpi/water_shimmer_fade_v3.png"


def periodic_noise(size: int, low_cycles: float, high_cycles: float, seed: int) -> np.ndarray:
    """Band-limited, seamlessly tileable noise in [0,1] via random-phase FFT."""
    rng = np.random.default_rng(seed)
    fy = np.fft.fftfreq(size) * size
    fx = np.fft.fftfreq(size) * size
    radius = np.hypot(fy[:, None], fx[None, :])
    band = ((radius >= low_cycles) & (radius <= high_cycles)).astype(np.float64)
    # 1/f falloff inside the band keeps larger undulations dominant.
    with np.errstate(divide="ignore"):
        amp = band / np.maximum(radius, 1.0)
    phases = rng.uniform(0, 2 * np.pi, (size, size))
    spectrum = amp * np.exp(1j * phases)
    field = np.fft.ifft2(spectrum).real
    field -= field.min()
    field /= max(field.max(), 1e-9)
    return field


def contour_web(field: np.ndarray, level: float, width: float) -> np.ndarray:
    """Thin organic lines where the noise crosses a level — caustic-style webs."""
    return np.exp(-(((field - level) / width) ** 2))


def make_shimmer(size: int = 2048) -> Image.Image:
    # Two noise fields at different scales, like the tutorial's two noise
    # textures (the second with higher detail), mixed 50/50.
    coarse = periodic_noise(size, 6, 26, seed=11)
    fine = periodic_noise(size, 22, 70, seed=47)

    alpha = np.zeros((size, size), dtype=np.float64)
    # Primary dapple web from the coarse field: a few crossing levels so the
    # webs interlock instead of reading as one contour map.
    for level, width, strength in ((0.42, 0.020, 0.65), (0.58, 0.018, 0.55)):
        alpha += contour_web(coarse, level, width) * strength
    # Finer web modulated by the coarse field: sparkle concentrates where the
    # big undulation "crests" (coarse high), like sun catching ripple tops.
    crest = np.clip((coarse - 0.45) * 2.2, 0.0, 1.0)
    alpha += contour_web(fine, 0.5, 0.022) * crest * 0.75

    # Sparse pinpoint sparkles.
    rng = np.random.default_rng(7)
    sparkle = (rng.uniform(size=(size, size)) > 0.99985).astype(np.float64)
    sparkle = np.asarray(
        Image.fromarray((sparkle * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.4)),
        dtype=np.float64,
    ) / 255.0
    alpha += sparkle * 2.2

    alpha = np.clip(alpha, 0.0, 1.0)
    # Keep coverage sparse so the baked river reads through: soft-knee the
    # faint haze away, cap peak alpha.
    alpha = np.clip((alpha - 0.26) / 0.74, 0.0, 1.0) ** 1.6
    # Texture alpha IS the layer opacity now (the embedded GLB material
    # renders it directly; no runtime alpha multiplier exists on that path).
    alpha_u8 = np.uint8(np.round(alpha * 72.0))

    color = np.zeros((size, size, 4), dtype=np.uint8)
    color[..., 0] = 205
    color[..., 1] = 238
    color[..., 2] = 255
    color[..., 3] = alpha_u8

    return Image.fromarray(color, "RGBA").filter(ImageFilter.GaussianBlur(radius=0.6))


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image = make_shimmer()
    image.save(OUTPUT, optimize=True)
    coverage = (np.asarray(image)[..., 3] > 24).mean() * 100
    print(f"wrote {OUTPUT} ({OUTPUT.stat().st_size:,} bytes; lit coverage {coverage:.1f}%)")


if __name__ == "__main__":
    main()
