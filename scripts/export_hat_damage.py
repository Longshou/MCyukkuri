#!/usr/bin/env python3
"""Export the accessory damage presentation and the shared cloth shard.

Damage is procedural: the whole accessory mesh is kept.  Front/back/top/bottom
box faces are divided into square cells and a few cells are omitted; the holes get
a close-colour inner cap sampled from the local cloth.  Thin side faces stay whole,
so the outline stays complete and there are no triangular sawteeth or whiskers.

Shards are deliberately identical for piece 0/1/2: one small torn cloth patch,
tinted by the source accessory's colour family.  That keeps the item stack and the
inventory icon simple while still showing which hat a shard belongs to.

Outputs
  assets/mcyukkuri/yukkuri_presentation/hat_damage.json  (nibble + tints)
  assets/mcyukkuri/yukkuri_props/accessory_shard.json    (shared prop)
  assets/mcyukkuri/yukkuri_props/hat-damage-index.json   (prop index)
  assets/mcyukkuri/textures/props/accessory_shard.png    (grayscale cloth)
  assets/mcyukkuri/textures/props/hat_damage_inner.png   (white cap/rim carrier)
  assets/mcyukkuri/models/item/accessory_shard.json      (item model)

--check is read-only and fails when an export differs from the recipe.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RECIPE = ROOT / "art/props/hat-damage/recipe.json"
ASSETS = ROOT / "src/main/resources/assets/mcyukkuri"


def dump(data) -> bytes:
    return (json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def stable_hash(x: int, y: int, seed: int) -> float:
    v = math.sin(x * 12.9898 + y * 78.233 + seed * 0.1) * 43758.5453
    return v - math.floor(v)


# --------------------------------------------------------------------------- shard texture --


def shard_texture(recipe: dict) -> Image.Image:
    spec = recipe["shard"]
    size = int(spec["texture_size"])
    seed = int(spec["seed"])
    rgba = np.zeros((size, size, 4), np.uint8)
    block = 2
    n = size // block
    # A chunky cloth rectangle, leaving a 1-block margin.  The torn edge is made of
    # whole 2x2 blocks, so it reads as a pixel-art tear instead of a field of 1px nicks.
    inside = np.zeros((n, n), bool)
    inside[1:n - 1, 1:n - 1] = True
    for bx, by in ((1, 1), (n - 2, n - 2), (1, n - 2), (n - 2, 1), (3, n - 2), (n - 3, 1)):
        if 0 <= bx < n and 0 <= by < n:
            inside[by, bx] = False
    # 2x2 woven blocks with a broad fold band.
    for by in range(n):
        for bx in range(n):
            if not inside[by, bx]:
                continue
            base = 228 + 12 * ((bx + by) & 1)
            fold = 10 * math.sin((bx + by) * .75 + 1.0) + 5 * math.cos((bx - by) * .45)
            value = int(max(176, min(255, base + fold)))
            rgba[by * block:(by + 1) * block, bx * block:(bx + 1) * block, :3] = value
            rgba[by * block:(by + 1) * block, bx * block:(bx + 1) * block, 3] = 255
    # A few large worn blocks in the cloth.
    for bx, by in ((5, 5), (10, 8), (7, 11)):
        rgba[by * block:(by + 1) * block, bx * block:(bx + 1) * block, 3] = 0
    # Broad crease and a lighter folded edge.
    for k in range(8):
        bx, by = 3 + k, 2 + k // 2
        rgba[by * block:(by + 1) * block, bx * block:(bx + 1) * block, :3] = 178
    for by in (n - 3,):
        rgba[by * block:(by + 1) * block, 1 * block:(n - 1) * block, :3] = 250
    for by in (n - 4,):
        rgba[by * block:(by + 1) * block, 1 * block:(n - 1) * block, :3] = 205
    # Two thick loose threads from the torn left edge.
    for by, length in ((4, 2), (9, 3)):
        for k in range(length):
            bx = 1 - k
            if 0 <= bx < n:
                rgba[by * block:(by + 1) * block, bx * block:(bx + 1) * block, :3] = 240
                rgba[by * block:(by + 1) * block, bx * block:(bx + 1) * block, 3] = 255
    _ = stable_hash(seed, size, seed)
    return Image.fromarray(rgba, "RGBA")


# --------------------------------------------------------------------------- shard mesh -----


def shard_mesh(recipe: dict) -> tuple[list[float], list[dict], list[list[float]], int]:
    spec = recipe["shard"]
    n = int(spec["cells"])
    size = float(spec["size"])
    depth = float(spec["depth"])

    def point(u: float, v: float):
        x = (u - .5) * size
        y = (v - .5) * size * .9
        z = (depth * math.sin(math.pi * u) * math.sin(math.pi * v)
             + depth * .35 * math.sin(4.0 * u + .4))
        return x, y, z

    def normal(u: float, v: float):
        du = (point(min(1.0, u + 1e-3), v)[2] - point(max(0.0, u - 1e-3), v)[2]) / 2e-3
        dv = (point(u, min(1.0, v + 1e-3))[2] - point(u, max(0.0, v - 1e-3))[2]) / 2e-3
        nx, ny, nz = -du * size * .9, -dv * size, size * size * .9
        length = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
        return nx / length, ny / length, nz / length

    vertices = []
    for j in range(n + 1):
        for i in range(n + 1):
            u, v = i / n, j / n
            x, y, z = point(u, v)
            nx, ny, nz = normal(u, v)
            vertices.append([round(x, 6), round(y, 6), round(z, 6), round(u, 6), round(1 - v, 6),
                             round(nx, 6), round(ny, 6), round(nz, 6)])
    triangles = []
    for j in range(n):
        for i in range(n):
            a, b = j * (n + 1) + i, j * (n + 1) + i + 1
            c, d = a + n + 1, b + n + 1
            triangles.extend([[a, b, d], [a, d, c]])
    # Duplicate vertices into the game's triangle soup layout.
    soup = []
    for tri in triangles:
        for index in tri:
            soup.extend(vertices[index])
    bounds = [[min(v[k] for v in vertices), max(v[k] for v in vertices)] for k in range(3)]
    return soup, [{"name": "cloth", "start": 0, "count": len(soup) // 8,
                   "texture": "mcyukkuri:textures/props/accessory_shard.png", "tint": [255, 255, 255]}], bounds, len(triangles)


# --------------------------------------------------------------------------- outputs --------


def outputs() -> dict[Path, bytes]:
    recipe = json.loads(RECIPE.read_text(encoding="utf-8"))
    spec = recipe["shard"]
    name = spec["name"]

    soup, parts, bounds, triangles = shard_mesh(recipe)
    prop = {"format": 1, "units_per_block": 16, "vertices": soup, "parts": parts, "bounds": bounds}
    index = {name: {"bounds": bounds, "triangles": triangles,
                    "source": "art/props/hat-damage/recipe.json#shard"}}

    runtime = {
        "format": recipe["format"],
        "source": RECIPE.relative_to(ROOT).as_posix(),
        "nibble": recipe["nibble"],
        "shard": name,
        "tints": spec["tints"],
    }

    item = {
        "parent": "minecraft:builtin/entity",
        "gui_light": "front",
        "textures": {"particle": f"mcyukkuri:props/{name}"},
        "display": {
            "gui": {"rotation": [25, 225, 0], "translation": [0, 0, 0], "scale": [.95, .95, .95]},
            "ground": {"translation": [0, 3, 0], "scale": [.5, .5, .5]},
            "fixed": {"scale": [.78, .78, .78]},
            "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [.5, .5, .5]},
            "firstperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [.6, .6, .6]},
        },
    }

    texture = shard_texture(recipe)
    import io
    buffer = io.BytesIO()
    texture.save(buffer, "PNG", optimize=True)
    inner = Image.new("RGBA", (16, 16), (255, 255, 255, 255))
    inner_buffer = io.BytesIO()
    inner.save(inner_buffer, "PNG", optimize=True)
    return {
        ASSETS / "yukkuri_presentation/hat_damage.json": dump(runtime),
        ASSETS / f"yukkuri_props/{name}.json": dump(prop),
        ASSETS / "yukkuri_props/hat-damage-index.json": dump(index),
        ASSETS / "models/item/accessory_shard.json": dump(item),
        ASSETS / "textures/props/accessory_shard.png": buffer.getvalue(),
        ASSETS / "textures/props/hat_damage_inner.png": inner_buffer.getvalue(),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for path, content in outputs().items():
        if args.check:
            if not path.exists() or path.read_bytes() != content:
                sys.exit(f"Hat damage export differs: {path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print("hat damage exports " + ("match." if args.check else "written."))


if __name__ == "__main__":
    main()
