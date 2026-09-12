# Anthony's consolidated Blender environment guide (2026-08-31)
Anthony compiled a master workflow from the tutorial series (GN scattering,
weight-paint masks, volumetric fog/god rays, rolling-mist material, fog-card
particles, compositor mist/glare, AgX). Full text lives in the session
transcript (2026-08-31) and the techniques are folded into
PLAYBOOK-360-forest.md. Key extracts applied to this project:
- M_FOG material: TexCoord(Object)+Mapping -> Noise(4D) [+Gradient falloff]
  -> Mix -> crushed ColorRamp -> Multiply (0.02-0.05) -> Principled Volume
  Density; anisotropy 0.3-0.7 for god rays; density 0.001-0.05 at scene
  scale; Volume output socket, never Surface.
- LOOP-SAFETY ADAPTATION (ours, not the guide's): `#frame/300` W-drift is
  linear and cannot loop; drive mist with integer-cycle drift over 240
  frames instead.
- Fog-card particle planes = fast fallback if true volumetrics are too slow
  at 8K (needs Transparency bounces 128).
- AgX view transform changes the whole grade — A/B only, current color is
  user-approved.
