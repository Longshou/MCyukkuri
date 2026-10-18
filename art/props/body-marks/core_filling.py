"""Builds the centre-filling prop (item and dropped portion): a compact, glossy lump, darker and redder than leaked filling.

Komicolle 11518802: the centre filling is the darker part in the middle of the body and the sweetest. Project-original shape
and colours; no external reference image. Outputs (format 1 prop, like leaked_filling):
  src/main/resources/assets/mcyukkuri/yukkuri_props/core_filling.json
  src/main/resources/assets/mcyukkuri/yukkuri_props/body-index.json
  src/main/resources/assets/mcyukkuri/textures/props/core_filling.png
Run with the bundled Pillow:  python art/props/body-marks/core_filling.py [--check]
"""
import hashlib
import io
import json
import math
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "src/main/resources/assets/mcyukkuri"
SWATCHES = [
    (60, 12, 30, 255),    # 0 centre filling, deep
    (104, 28, 50, 255),   # 1 centre filling, lit
    (196, 96, 120, 255),  # 2 gloss
    (86, 46, 58, 255),    # 3 thin coat of ordinary filling at the base
]
RINGS, SEGMENTS = 9, 16
RX, RY, RZ = 1.9, 1.35, 1.7


def bump(theta, phi):
    return 1 + .07 * math.sin(3 * phi + 1.1) * math.sin(2 * theta) + .05 * math.cos(5 * phi) * math.sin(theta)


def point(i, j):
    theta = math.pi * i / RINGS            # 0 = top, pi = bottom
    phi = 2 * math.pi * j / SEGMENTS
    r = bump(theta, phi)
    y = RY * math.cos(theta) * r
    flat = .55 if y < 0 else 1             # sits on a flattened base
    return (RX * math.sin(theta) * math.cos(phi) * r, (y * flat) + RY * .55, RZ * math.sin(theta) * math.sin(phi) * r)


def build():
    vertices = []
    for i in range(RINGS):
        for j in range(SEGMENTS):
            a, b, c, d = point(i, j), point(i + 1, j), point(i + 1, j + 1), point(i, j + 1)
            for tri in ((a, c, b), (a, d, c)):  # counter-clockwise from outside: normals face out
                ux, uy, uz = (tri[1][k] - tri[0][k] for k in range(3))
                vx, vy, vz = (tri[2][k] - tri[0][k] for k in range(3))
                n = (uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
                length = math.sqrt(sum(x * x for x in n))
                if length < 1e-9:
                    continue
                n = tuple(x / length for x in n)
                cy = sum(p[1] for p in tri) / 3
                lit = n[1] * .7 - n[2] * .3 - n[0] * .2
                swatch = 3 if cy < .25 else 2 if lit > .6 else 1 if lit > .1 else 0
                u = (swatch + .5) / 16
                for p in tri:
                    vertices += [round(p[0], 4), round(p[1], 4), round(p[2], 4), u, .5, round(n[0], 4), round(n[1], 4), round(n[2], 4)]
    bounds = [[round(min(vertices[k::8]), 4), round(max(vertices[k::8]), 4)] for k in range(3)]
    mesh = {"format": 1, "units_per_block": 16, "id": "core_filling", "vertices": vertices,
            "parts": [{"name": "core", "start": 0, "count": len(vertices) // 8}], "bounds": bounds}
    image = Image.new("RGBA", (16, 1), SWATCHES[0])
    for index, colour in enumerate(SWATCHES):
        image.putpixel((index, 0), colour)
    png = io.BytesIO()
    image.save(png, format="PNG")
    index = {"core_filling": {"bounds": bounds, "triangles": len(vertices) // 24}}
    return {
        ASSETS / "yukkuri_props/core_filling.json": (json.dumps(mesh, separators=(",", ":")) + "\n").encode(),
        ASSETS / "yukkuri_props/body-index.json": (json.dumps(index, separators=(",", ":")) + "\n").encode(),
        ASSETS / "textures/props/core_filling.png": png.getvalue(),
    }


def same(path, data):
    if not path.exists():
        return False
    if path.suffix == ".png":
        return list(Image.open(path).convert("RGBA").getdata()) == list(Image.open(io.BytesIO(data)).convert("RGBA").getdata())
    return path.read_bytes().replace(b"\r\n", b"\n") == data


def preview(mesh_bytes, png_bytes):
    """Flat-shaded three-quarter view, next to a leaked-filling swatch strip, into .local/previews/injury/core_filling.png."""
    from PIL import ImageDraw
    mesh = json.loads(mesh_bytes)
    strip = Image.open(io.BytesIO(png_bytes)).convert("RGB")
    v = mesh["vertices"]
    size, scale = 240, 40
    img = Image.new("RGB", (size, size), (232, 238, 232))
    draw = ImageDraw.Draw(img)
    yaw, pitch = math.radians(35), math.radians(25)
    def project(x, y, z):
        x, z = x * math.cos(yaw) - z * math.sin(yaw), x * math.sin(yaw) + z * math.cos(yaw)
        y, z = y * math.cos(pitch) - z * math.sin(pitch), y * math.sin(pitch) + z * math.cos(pitch)
        return size / 2 + x * scale, size * .62 - y * scale, z
    tris = []
    for i in range(0, len(v), 24):
        pts = [project(*v[i + k * 8:i + k * 8 + 3]) for k in range(3)]
        n = v[i + 5:i + 8]
        light = .55 + .45 * max(0, n[1] * .8 + .2)
        colour = strip.getpixel((int(v[i + 3] * 16), 0))
        tris.append((sum(p[2] for p in pts) / 3, [(p[0], p[1]) for p in pts], tuple(int(c * light) for c in colour)))
    for _, pts, colour in sorted(tris, key=lambda t: -t[0]):
        draw.polygon(pts, fill=colour)
    draw.text((6, 4), "core_filling (item / dropped)", fill=(40, 50, 40))
    out = (ROOT.parents[1] if ROOT.parent.name == ".worktrees" else ROOT) / ".local/previews/injury/core_filling.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print("wrote", out)


def main():
    outputs = build()
    if "--preview" in sys.argv:
        preview(outputs[ASSETS / "yukkuri_props/core_filling.json"], outputs[ASSETS / "textures/props/core_filling.png"])
        return 0
    if "--check" in sys.argv:
        stale = [str(p.relative_to(ROOT)) for p, d in outputs.items() if not same(p, d)]
        print("core_filling:", "matches" if not stale else "DIFFERS " + ", ".join(stale))
        return 1 if stale else 0
    for path, data in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        print("wrote", path.relative_to(ROOT), hashlib.sha256(data).hexdigest()[:12])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
