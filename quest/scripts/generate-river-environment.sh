#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
source_panorama="$script_dir/sources/skydome_lakeside_dawn_polyhaven.jpg"
target_dir="$project_dir/app/src/main/res/drawable-nodpi"
water_size=4096
sky_size=8192
water_surface_width=4096
water_surface_height=2048

if ! command -v magick >/dev/null 2>&1; then
  echo "ImageMagick 7 is required (brew install imagemagick)." >&2
  exit 1
fi

if ! command -v rsvg-convert >/dev/null 2>&1; then
  echo "rsvg-convert is required (brew install librsvg)." >&2
  exit 1
fi

if [[ ! -f "$source_panorama" ]]; then
  echo "Missing source panorama: $source_panorama" >&2
  exit 1
fi

# Runtime uses the full 8K tonemapped Lakeside Dawn source for the 360° fallback
# skybox plus a narrower high-density forward GLB horizon band. The fallback
# costs more decoded texture memory than the old 4K target, but it removes the
# visible softness when the headset view drifts outside the forward band.
rm -f "$target_dir/skydome_lakeside_dawn_v11.jpg"
magick "$source_panorama" \
  -filter Lanczos \
  -resize "${sky_size}x$((sky_size / 2))"\! \
  -unsharp 0x0.28+0.18+0.006 \
  -strip \
  -sampling-factor 4:2:0 \
  -quality 94 \
  "$target_dir/skydome_lakeside_dawn_fallback_v1.jpg"

# Local foreground detail layer. This is not another skybox: it crops an
# open-water band from the same real panorama and maps it onto a broad, low
# opacity scene plane. Avoiding shoreline/branch pixels matters because this
# layer is rendered close enough that perspective-mismatched ground detail reads
# as a floating platform in-headset.
water_surface_base="$(mktemp /tmp/openpanel-water-surface-base.XXXXXX.jpg)"
water_surface_row="$(mktemp /tmp/openpanel-water-surface-row.XXXXXX.jpg)"
trap 'rm -f "$water_surface_base" "$water_surface_row"' EXIT

magick "$source_panorama" \
  -crop 2048x720+2000+2180 +repage \
  "$water_surface_base"

magick "$water_surface_base" \
  \( "$water_surface_base" -flop \) \
  +append \
  "$water_surface_row"

magick "$water_surface_row" \
  \( "$water_surface_row" -flip \) \
  -append \
  -filter Lanczos \
  -resize "${water_surface_width}x${water_surface_height}"\! \
  -unsharp 0x0.30+0.24+0.006 \
  -strip \
  -sampling-factor 4:4:4 \
  -quality 94 \
  "$target_dir/water_surface_lakeside_v1.jpg"

# Rasterize the authored ripple vector directly so the transparent areas stay
# transparent on device. The runtime now draws a translucent base water plane
# under these animated highlight layers.
rsvg-convert \
  -w "$water_size" \
  -h "$water_size" \
  "$script_dir/water-ripples.svg" \
  -o "$target_dir/river_ripples_v3.png"

identify -format '%f %wx%h %m %b\n' \
  "$target_dir/skydome_lakeside_dawn_fallback_v1.jpg"
identify -format '%f %wx%h %m %b\n' \
  "$target_dir/water_surface_lakeside_v1.jpg"
identify -format '%f %wx%h %b\n' "$target_dir/river_ripples_v3.png"
