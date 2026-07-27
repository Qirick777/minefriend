#!/usr/bin/env python3
"""Generate the phase-1 placeholder texture for the WardenGirl model.

Design doc Part 3.7 says the phase-1 texture may be a flat colour or a stand-in, and that a
human replaces it later. A flat colour would technically satisfy that, but it would be useless
for the one job T1 actually has to do: letting a human read the model's orientation off a
screenshot while verifying axis signs (Part 4.0 / P1-T1).

So instead of one colour, every cube face is coloured by the direction it points:

    -X (the mob's RIGHT)   red
    +X (the mob's LEFT)    blue
    -Z (FRONT)             pale, plus eye marks on the head
    +Z (BACK)              dark brown
    +Y (TOP)               green
    -Y (BOTTOM)            purple

That makes "which way did this bone actually turn" readable from any camera angle.

This is a throwaway asset. Regenerate with:
    python3 tools/gen_temp_texture.py
"""

import struct
import zlib

W = H = 64

RIGHT = (192, 57, 43, 255)     # -X
LEFT = (46, 111, 192, 255)     # +X
FRONT = (242, 226, 200, 255)   # -Z
BACK = (74, 63, 53, 255)       # +Z
TOP = (143, 209, 143, 255)     # +Y
BOTTOM = (91, 58, 120, 255)    # -Y

INK = (32, 28, 26, 255)        # eye marks

# name -> (u, v, w, h, d), matching assets/wardengirl/geo/warden_girl.geo.json exactly.
CUBES = {
    "head":      (0, 0, 8, 8, 8),
    "body":      (16, 16, 8, 12, 4),
    "arm_right": (40, 16, 3, 12, 4),
    "arm_left":  (32, 48, 3, 12, 4),
    "leg_right": (0, 16, 4, 12, 4),
    "leg_left":  (16, 48, 4, 12, 4),
    "headgear_r": (56, 16, 2, 5, 2),
    "headgear_l": (56, 24, 2, 5, 2),
}

pixels = [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]


def fill(x0, y0, w, h, colour):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            if 0 <= x < W and 0 <= y < H:
                pixels[y][x] = colour


def box_uv(u, v, w, h, d):
    """Standard Minecraft box-UV unwrap.

    Verified against the vanilla head (uv 0,0, 8x8x8): top (8,0), bottom (16,0),
    right (0,8), front (8,8), left (16,8), back (24,8).
    """
    fill(u + d, v, w, d, TOP)
    fill(u + d + w, v, w, d, BOTTOM)
    fill(u, v + d, d, h, RIGHT)
    fill(u + d, v + d, w, h, FRONT)
    fill(u + d + w, v + d, d, h, LEFT)
    fill(u + d + w + d, v + d, w, h, BACK)


for u, v, w, h, d in CUBES.values():
    box_uv(u, v, w, h, d)

# Eye marks on the head's front face so "front" is unmistakable even in greyscale.
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
