#!/usr/bin/env python3
"""Generate the phase-1 placeholder texture for the WardenGirl model.

Design doc Part 3.7 allows a flat colour or a stand-in here, and says a human replaces it later.
A flat colour would satisfy the letter of that and be useless for the job T1 actually has: letting
a human read the rig's orientation off a screenshot while verifying axis signs (Part 4.0, P1-T1).

Two independent channels carry the information:

  HUE  = which part it is, and which side of the body it is on.
         arm_right RED     vs arm_left  BLUE      -- warm/cool pairing, unmistakable
         leg_right ORANGE  vs leg_left  CYAN
         head cream, body grey, headgear magenta
  VALUE = which way the face points.
         front brightest -> back darkest, so a rotation reads even within one part.

An earlier version coloured by face direction only. Every part's front face was the same pale
colour, so the arms merged into the torso and arm rotations could not be judged. Hence the hue
channel.

Throwaway asset. Regenerate with:  python3 tools/gen_temp_texture.py
"""

import struct
import zlib

W = H = 64

# Per-face brightness multipliers. Front is brightest, back darkest.
FACE_SHADE = {
    "front": 1.00,
    "top": 0.86,
    "right": 0.70,
    "left": 0.70,
    "back": 0.42,
    "bottom": 0.34,
}

# name -> (u, v, w, h, d, base colour), matching warden_girl.geo.json exactly.
CUBES = {
    "head":       (0, 0, 8, 8, 8, (226, 205, 168)),   # cream
    "body":       (16, 16, 8, 12, 4, (150, 150, 158)),  # neutral grey
    "arm_right":  (40, 16, 3, 12, 4, (214, 54, 44)),    # RED   - right
    "arm_left":   (32, 48, 3, 12, 4, (48, 108, 214)),   # BLUE  - left
    "leg_right":  (0, 16, 4, 12, 4, (232, 138, 36)),    # ORANGE- right
    "leg_left":   (16, 48, 4, 12, 4, (40, 186, 178)),   # CYAN  - left
    "headgear_r": (56, 16, 2, 5, 2, (188, 62, 176)),    # magenta
    "headgear_l": (56, 24, 2, 5, 2, (188, 62, 176)),
}

INK = (24, 20, 18, 255)

pixels = [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]


def shade(base, face):
    m = FACE_SHADE[face]
    return (int(base[0] * m), int(base[1] * m), int(base[2] * m), 255)


def fill(x0, y0, w, h, colour):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            if 0 <= x < W and 0 <= y < H:
                pixels[y][x] = colour


def box_uv(u, v, w, h, d, base):
    """Standard Minecraft box-UV unwrap.

    Verified against the vanilla head (uv 0,0, 8x8x8): top (8,0), bottom (16,0),
    right (0,8), front (8,8), left (16,8), back (24,8).
    """
    fill(u + d, v, w, d, shade(base, "top"))
    fill(u + d + w, v, w, d, shade(base, "bottom"))
    fill(u, v + d, d, h, shade(base, "right"))
    fill(u + d, v + d, w, h, shade(base, "front"))
    fill(u + d + w, v + d, d, h, shade(base, "left"))
    fill(u + d + w + d, v + d, w, h, shade(base, "back"))


for u, v, w, h, d, base in CUBES.values():
    box_uv(u, v, w, h, d, base)

# Eyes on the head's front face, so "which way is forward" needs no colour reasoning at all.
# Head front face starts at (u+d, v+d) = (8, 8) and is 8x8.
fill(10, 11, 2, 2, INK)
fill(14, 11, 2, 2, INK)

raw = b"".join(
    b"\x00" + b"".join(struct.pack("4B", *pixels[y][x]) for x in range(W))
    for y in range(H)
)


def chunk(tag, data):
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


png = (
    b"\x89PNG\r\n\x1a\n"
    + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
    + chunk(b"IDAT", zlib.compress(raw, 9))
    + chunk(b"IEND", b"")
)

out = "src/main/resources/assets/wardengirl/textures/entity/warden_girl.png"
with open(out, "wb") as fh:
    fh.write(png)
print(f"wrote {out} ({len(png)} bytes, {W}x{H} RGBA)")
print("  arm_right RED / arm_left BLUE / leg_right ORANGE / leg_left CYAN")
