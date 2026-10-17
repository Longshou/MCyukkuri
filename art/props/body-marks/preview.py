"""Layout preview of the dirt and scar marks on the real skin mesh (orthographic, no hat or hair).

It mirrors the shapes in client/WoundRenderer.java (kinds "dirt" and "scar"; material swatches from body_marks.png) on
the selected wound-surface mesh, from four sides, for several levels. It is a layout check, not the game renderer.
Output: .local/previews/body-marks/marks.png
"""
import gzip
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
RES = ROOT / "src/main/resources/assets/mcyukkuri"
OUT = ROOT / ".local/previews/body-marks"
SW = [(236, 208, 202), (196, 136, 132), (116, 90, 66), (78, 60, 48), (156, 134, 104)]
SCALE = 22
SIZE = 300


def load(asset):
    data = json.load(gzip.open(RES / "yukkuri_wounds" / f"{asset}.json.gz", "rt", encoding="utf8"))
    pts = np.array(data["points"], dtype=np.float32).reshape(-1, 5)
    tex = np.array(Image.open(RES / "textures/entity" / asset / "neutral.png").convert("RGB"))
    return pts, data["skinCount"], tex


def render(pts, skin, tex, yaw):
    c, s = math.cos(yaw), math.sin(yaw)
    rot = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], dtype=np.float32)
    pos = pts[:, :3] @ rot.T
    h, w = tex.shape[:2]
    color = np.zeros((SIZE, SIZE, 3), np.float32)
    depth = np.full((SIZE, SIZE), 1e9, np.float32)
    world = np.zeros((SIZE, SIZE, 3), np.float32)
    normal = np.zeros((SIZE, SIZE, 3), np.float32)
    owner = -np.ones((SIZE, SIZE), np.int32)
    cx, cy = SIZE / 2, SIZE * .82
    for t in range(0, (len(pts) if abs(yaw) < 1e-6 else skin) - 2, 3):
        a, b, d = pos[t], pos[t + 1], pos[t + 2]
        n = np.cross(b - a, d - a)
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            continue
        n /= ln
        if n[2] > 0:  # camera looks along +z from -z; faces toward -z are visible
            continue
        xs = np.array([a[0], b[0], d[0]]) * SCALE + cx
        ys = cy - np.array([a[1], b[1], d[1]]) * SCALE
        x0, x1 = int(max(0, math.floor(xs.min()))), int(min(SIZE - 1, math.ceil(xs.max())))
        y0, y1 = int(max(0, math.floor(ys.min()))), int(min(SIZE - 1, math.ceil(ys.max())))
        if x1 < x0 or y1 < y0:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
        den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
        if abs(den) < 1e-9:
            continue
        l1 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / den
        l2 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / den
        l3 = 1 - l1 - l2
        inside = (l1 >= -1e-4) & (l2 >= -1e-4) & (l3 >= -1e-4)
        if not inside.any():
            continue
        z = l1 * a[2] + l2 * b[2] + l3 * d[2]
        sub = depth[y0:y1 + 1, x0:x1 + 1]
        win = inside & (z < sub - 1e-5)
        if not win.any():
            continue
        u = l1 * pts[t, 3] + l2 * pts[t + 1, 3] + l3 * pts[t + 2, 3]
        v = l1 * pts[t, 4] + l2 * pts[t + 1, 4] + l3 * pts[t + 2, 4]
        px = np.clip((u * w).astype(int), 0, w - 1)
        py = np.clip((v * h).astype(int), 0, h - 1)
        shade = .55 + .45 * max(0.0, float(-n[2]) * .7 + float(n[1]) * .3 + .2)
        rows, cols = np.nonzero(win)
        color[y0 + rows, x0 + cols] = tex[py[rows, cols], px[rows, cols]] * shade
        depth[y0 + rows, x0 + cols] = z[rows, cols]
        # unrotated rest position and normal, for the mark masks
        rest = l1[..., None] * pts[t, :3] + l2[..., None] * pts[t + 1, :3] + l3[..., None] * pts[t + 2, :3]
        rn = np.cross(pts[t + 1, :3] - pts[t, :3], pts[t + 2, :3] - pts[t, :3])
        rn = rn / max(np.linalg.norm(rn), 1e-9)
        world[y0 + rows, x0 + cols] = rest[rows, cols]
        normal[y0 + rows, x0 + cols] = rn
        owner[y0 + rows, x0 + cols] = t
    return color, world, normal, owner, depth, rot


def basis(n):
    ref = np.array([0, 1, 0], np.float32) if abs(n[1]) < .9 else np.array([1, 0, 0], np.float32)
    t1 = np.cross(n, ref)
    t1 /= np.linalg.norm(t1)
    return t1, np.cross(n, t1)


def disk_mask(world, normal, anchor, n, rx, ry, rough=0.0, offset=(0, 0)):
    t1, t2 = basis(n)
    d = world - anchor
    x = d @ t1 - offset[0]
    y = d @ t2 - offset[1]
    ang = np.arctan2(y, x)
    k = 1 + rough * (.09 * np.sin(ang * 7) + .04 * np.cos(ang * 13))
    return ((x / (rx * k)) ** 2 + (y / (ry * k)) ** 2 <= 1) & (normal @ n > .5) & (np.abs(d @ n) < 1.6)


def line_mask(world, normal, anchor, n, a, b, width):
    t1, t2 = basis(n)
    d = world - anchor
    p = np.stack([d @ t1, d @ t2], -1)
    a, b = np.array(a), np.array(b)
    ab = b - a
    tt = np.clip(((p - a) @ ab) / max(ab @ ab, 1e-9), 0, 1)
    dist = np.linalg.norm(p - (a + tt[..., None] * ab), axis=-1)
    return (dist <= width / 2) & (normal @ n > .5) & (np.abs(d @ n) < 1.6)


def apply(img, world, normal, valid, sites, kind, level, rng):
    for i, (anchor, n) in enumerate(sites):
        size = 1.1 + (i % 3) * .35
        if kind == "dirt":
            q = size * (.45 + .2 * level)
            img[disk_mask(world, normal, anchor, n, q, .8 * q, 1) & valid] = SW[2]
            for j in range(3):
                a = j * 2.1 + size * 5
                r = .55 * q
                img[disk_mask(world, normal, anchor, n, .42 * q, .34 * q, 1, (math.cos(a) * r, math.sin(a) * r)) & valid] = SW[3]
            img[disk_mask(world, normal, anchor, n, .3 * q, .24 * q, 1, (-.25 * q, .15 * q)) & valid] = SW[4]
        else:
            q = (.8 + rng.random() * .6) * (.8 + .15 * level)
            segs = [((-1.7 * q, -.45 * q), (-.35 * q, .1 * q)), ((-.35 * q, .1 * q), (1.6 * q, .5 * q))]
            for sa, sb in segs:
                img[line_mask(world, normal, anchor, n, sa, sb, .5) & valid] = SW[1]
            for k in (-1, 0, 1):
                x = k * .85 * q
                img[line_mask(world, normal, anchor, n, (x - .12, -.75 * q + .18 * k), (x + .12, .9 * q + .1 * k), .2) & valid] = SW[1]
            for sa, sb in segs:
                img[line_mask(world, normal, anchor, n, sa, sb, .26) & valid] = SW[0]


def pick_sites(pts, skin, count, seed, yaw=0.0):
    rng = np.random.default_rng(seed)
    found = []
    tries = 0
    while len(found) < count and tries < 500:
        tries += 1
        t = int(rng.integers(0, skin // 3)) * 3
        n = np.cross(pts[t + 1, :3] - pts[t, :3], pts[t + 2, :3] - pts[t, :3])
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            continue
        n /= ln
        if n[2] < -.6 or n[1] < -.5:
            continue
        if yaw and (-n[0] * math.sin(yaw) + n[2] * math.cos(yaw)) > -.45:   # must face the preview camera
            continue
        u, v = rng.random(), rng.random()
        v *= 1 - u
        a = (1 - u - v) * pts[t, :3] + u * pts[t + 1, :3] + v * pts[t + 2, :3]
        found.append((a.astype(np.float32), n.astype(np.float32)))
    return found


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (SIZE * 5, SIZE * 2 + 24), (232, 238, 232))
    draw = ImageDraw.Draw(sheet)
    rng = np.random.default_rng(3)
    for row, asset in enumerate(["reimu", "marisa"]):
        pts, skin, tex = load(asset)
        cases = [("clean, front", 0, 0, "none", 0), ("grime 1, back", math.pi, 1, "dirt", 1),
                 ("grime 2, side", math.pi / 2, 2, "dirt", 2), ("grime 3, side", -math.pi / 2, 3, "dirt", 3),
                 ("two scars, back", math.pi, 2, "scar", 2)]
        for col, (label, yaw, level, kind, lv) in enumerate(cases):
            color, world, normal, owner, depth, rot = render(pts, skin, tex, yaw)
            valid = owner >= 0
            if kind != "none":
                sites = pick_sites(pts, skin, 2 * level if kind == "dirt" else 2, 11 + col, yaw)
                # sites are in rest space; the render's rest-position buffer is also rest space
                apply(color, world, normal, valid, sites, kind, lv, rng)
            img = Image.fromarray(np.clip(color, 0, 255).astype(np.uint8))
            bg = Image.new("RGB", img.size, (232, 238, 232))
            mask = Image.fromarray(((owner >= 0) * 255).astype(np.uint8))
            bg.paste(img, (0, 0), mask)
            sheet.paste(bg, (col * SIZE, row * SIZE + 24))
            draw.text((col * SIZE + 6, 4 if row == 0 else row * SIZE + 28), f"{asset}: {label}", fill=(40, 50, 40))
    sheet.save(OUT / "marks.png")
    print("wrote", OUT / "marks.png")


if __name__ == "__main__":
    main()
