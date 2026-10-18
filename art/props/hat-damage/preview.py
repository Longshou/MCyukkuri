#!/usr/bin/env python3
"""Preview of the final damage treatment and the shared shard.

Damage keeps the complete accessory outline.  Front/back/top/bottom box faces
are split into square cells; a few cells are omitted and filled with a close
colour sampled from the local cloth (green stays green, red stays red, black
becomes a lighter grey-lavender, pale cloth becomes a darker version of itself).
Thin side faces stay whole, so no triangular sawtooth or dangling whiskers.
All three shards use one shared torn-cloth model, tinted by the accessory's
colour family.

Reads the exported game assets and art/props/hat-damage/recipe.json.
Output: .local/previews/hat-damage/hat-damage.png and index.html.
"""
from __future__ import annotations

import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "src/main/resources/assets/mcyukkuri"
RECIPE = json.loads((Path(__file__).resolve().parent / "recipe.json").read_text(encoding="utf-8"))
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / ".local/previews/hat-damage"
SPECIES = [("reimu", "灵梦"), ("marisa", "魔理沙"), ("chen", "橙"), ("alice", "爱丽丝"),
           ("patchouli", "帕秋莉"), ("remilia", "蕾米莉亚"), ("myon", "妖梦"), ("flandre", "芙兰")]
TRIM = ("hem", "edge", "lace", "band", "frill", "scallop", "crescent", "ribbon", "knot")


@dataclass
class Mesh:
    pos: np.ndarray          # (T,3,3)
    uv: np.ndarray           # (T,3,2)
    shade: np.ndarray | None = None   # (T,) multiplier for the sampled texture
    colors: np.ndarray | None = None  # (T,3) absolute colour for flat cells
    flat: np.ndarray | None = None    # (T,) True = use colors[t] instead of the texture


def load_prop(name: str):
    data = json.loads((ASSETS / f"yukkuri_props/{name}.json").read_text(encoding="utf-8"))
    verts = np.asarray(data["vertices"], np.float32).reshape(-1, 8)
    parts = []
    for part in data["parts"]:
        texture = part.get("texture", f"mcyukkuri:textures/props/{name}.png")
        parts.append((part["name"], int(part["start"]), int(part["count"]), texture))
    tex_ref = parts[0][3].split(":", 1)[1]
    tex = np.asarray(Image.open(ASSETS / tex_ref).convert("RGBA"), np.uint8)
    lo, hi = verts[:, :3].min(0), verts[:, :3].max(0)
    return verts, parts, tex, lo, hi


def tri_normal(tri):
    n = np.cross(tri[1] - tri[0], tri[2] - tri[0])
    length = np.linalg.norm(n)
    return n / length if length > 1e-9 else np.array([0, 0, -1], np.float32)


def camera_rotation(yaw_deg, pitch_deg):
    yaw, pitch = math.radians(yaw_deg), math.radians(pitch_deg)
    ry = np.array([[math.cos(yaw), 0, math.sin(yaw)], [0, 1, 0],
                   [-math.sin(yaw), 0, math.cos(yaw)]], np.float32)
    rx = np.array([[1, 0, 0], [0, math.cos(pitch), -math.sin(pitch)],
                   [0, math.sin(pitch), math.cos(pitch)]], np.float32)
    return rx @ ry


def render(mesh: Mesh, tex: np.ndarray, cell: int, yaw=30.0, pitch=-20.0, ss=2) -> Image.Image:
    if len(mesh.pos) == 0:
        return Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
    size = cell * ss
    rot = camera_rotation(yaw, pitch)
    pos = mesh.pos.reshape(-1, 3) @ rot.T
    lo, hi = pos.min(0), pos.max(0)
    span = max(float(hi[0] - lo[0]), float(hi[1] - lo[1]), 1e-4)
    scale = (size - 24 * ss) / span
    sx = (pos[:, 0] - (lo[0] + hi[0]) / 2) * scale + size / 2
    sy = size / 2 - (pos[:, 1] - (lo[1] + hi[1]) / 2) * scale
    depth = pos[:, 2].reshape(-1, 3)
    screen = np.stack([sx.reshape(-1, 3), sy.reshape(-1, 3)], 2)
    img = np.zeros((size, size, 4), np.float32)
    zbuf = np.full((size, size), np.inf)
    view_light = np.array([-.35, .62, -.70], np.float32)
    view_light /= np.linalg.norm(view_light)
    light = rot.T @ view_light
    th, tw = tex.shape[:2]
    for t in range(len(mesh.pos)):
        normal = tri_normal(mesh.pos[t])
        n_view = normal @ rot.T
        if n_view[2] > 0:
            normal = -normal
        shade = .70 + .30 * abs(float(np.dot(normal, light)))
        multi = 1.0 if mesh.shade is None else float(mesh.shade[t])
        x, y, z = screen[t, :, 0], screen[t, :, 1], depth[t]
        x0, x1 = int(max(0, math.floor(x.min()))), int(min(size - 1, math.ceil(x.max())))
        y0, y1 = int(max(0, math.floor(y.min()))), int(min(size - 1, math.ceil(y.max())))
        if x1 < x0 or y1 < y0:
            continue
        den = (y[1] - y[2]) * (x[0] - x[2]) + (x[2] - x[1]) * (y[0] - y[2])
        if abs(den) < 1e-9:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
        a = ((y[1] - y[2]) * (gx - x[2]) + (x[2] - x[1]) * (gy - y[2])) / den
        b = ((y[2] - y[0]) * (gx - x[2]) + (x[0] - x[2]) * (gy - y[2])) / den
        c = 1 - a - b
        m = (a >= -1e-4) & (b >= -1e-4) & (c >= -1e-4)
        if not m.any():
            continue
        zz = a * z[0] + b * z[1] + c * z[2]
        u = a * mesh.uv[t, 0, 0] + b * mesh.uv[t, 1, 0] + c * mesh.uv[t, 2, 0]
        v = a * mesh.uv[t, 0, 1] + b * mesh.uv[t, 1, 1] + c * mesh.uv[t, 2, 1]
        if mesh.flat is not None and mesh.flat[t] and mesh.colors is not None:
            col = np.broadcast_to(mesh.colors[t], (*u.shape, 3)).copy()
            alpha = np.full(u.shape, 255)
        else:
            tx = np.clip((u * tw).astype(int), 0, tw - 1)
            ty = np.clip((v * th).astype(int), 0, th - 1)
            col = tex[ty, tx, :3].astype(np.float32)
            alpha = tex[ty, tx, 3]
        sub = zbuf[y0:y1 + 1, x0:x1 + 1]
        draw = m & (zz < sub) & (alpha > 20)
        region = img[y0:y1 + 1, x0:x1 + 1]
        region[draw, :3] = np.clip(col[draw] * shade * multi, 0, 255)
        region[draw, 3] = 255
        sub[draw] = zz[draw]
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA")
    return out.resize((cell, cell), Image.LANCZOS) if ss > 1 else out


def font(size):
    for path in ("C:/Windows/Fonts/msyh.ttc", "/System/Library/Fonts/STHeiti Medium.ttc",
                 "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"):
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


# --------------------------------------------------------------------------- damage --------


def cell_noise(x, y, z, seed):
    ix, iy, iz = int(math.floor(x)), int(math.floor(y)), int(math.floor(z))
    h = (ix * 374761393 + iy * 668265263 + iz * 2147483647 + seed * 1274126177) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0x7FFFFFFF) / 0x7FFFFFFF


def damage_code(level, part_name, nx, ny, nz, margin=1.0):
    """Chunky bites from a few elliptical spots, not a field of tiny pinholes."""
    cfg = RECIPE["nibble"]
    if level <= 0:
        return 1
    if level >= 3:
        for a, b, c, d, width in cfg["seams"]:
            if abs(a * nx + b * ny + c * nz - d) < width:
                return 2
        return 1
    seed = int(cfg["seed"])
    for spot in cfg["spots"][str(level)]:
        cx, cy = spot["center"]
        rx, ry = spot["radius"]
        scale = cfg.get("trim_scale", 1.0) if any(key in part_name for key in TRIM) else 1.0
        if "crescent" in part_name:
            scale *= 0.45
        dx, dy = (nx - cx) / (rx * scale * margin), (ny - cy) / (ry * scale * margin)
        radius = math.hypot(dx, dy)
        angle = math.atan2(dy, dx)
        # Quantised angle + hash: the bite edge is a chunky pixel staircase.
        q = math.floor((angle + math.pi) / .24) * .24
        wave = .16 * math.sin(7.0 * q + spot.get("phase", 0.0)) + .10 * math.sin(4.0 * q + spot.get("phase", 0.0) * 1.7)
        wave += .05 * (cell_noise(nx * 42, ny * 42, nz * 42, seed) - .5)
        if radius < 1.0 + wave:
            return 0
        if radius < 1.0 + wave + cfg["edge_band"]:
            return 2
    return 1

def _pkey(v):
    return (round(float(v[0]), 3), round(float(v[1]), 3), round(float(v[2]), 3))


def _face_quad(tri0, tri1):
    """Return the four perimeter corners of a two-triangle face, or None."""
    unique, seen = [], set()
    for v in list(tri0) + list(tri1):
        key = (round(float(v[0]), 4), round(float(v[1]), 4), round(float(v[2]), 4),
               round(float(v[3]), 4), round(float(v[4]), 4))
        if key not in seen:
            seen.add(key)
            unique.append(v)
    if len(unique) != 4:
        return None
    shared = {_pkey(v) for v in tri0} & {_pkey(v) for v in tri1}
    if len(shared) != 2:
        return None
    order = []
    for v in tri0:
        key = _pkey(v)
        if key in shared and key not in order:
            order.append(key)
    if len(order) != 2:
        return None
    by_key = {_pkey(v): v for v in unique}
    if len(by_key) != 4:
        return None
    s0, s1 = by_key[order[0]], by_key[order[1]]
    opposite = [v for v in unique if _pkey(v) not in shared]
    if len(opposite) != 2:
        return None
    # s0 -> opposite0 -> s1 -> opposite1 walks the perimeter.
    return [s0, opposite[0], s1, opposite[1]]


def nibble_mesh(verts, parts, lo, hi, level, tex=None):
    """Square-cell damage: each two-triangle face is a regular grid of blocks.

    A bite removes whole blocks from every face (front, sides, bottom), so the hole
    edge is a stair-step of square pixels, never a triangular sawtooth and never a
    thin whisker left behind by a side face.
    """
    if level == 0:
        tris, uvs = [], []
        for _, start, count, _ in parts:
            block = verts[start:start + count].reshape(-1, 3, 8)
            for tri in block:
                tris.append(tri[:, :3])
                uvs.append(tri[:, 3:5])
        return Mesh(np.asarray(tris, np.float32), np.asarray(uvs, np.float32))
    cfg = RECIPE["nibble"]
    target = float(cfg["target_edge"])
    lo_n, hi_n = int(cfg["min_subdivision"]), int(cfg["max_subdivision"])
    span = np.maximum(hi - lo, 1e-5)
    tris, uvs, shades, colors, flat = [], [], [], [], []
    edge_shade = float(cfg["edge_shade"])
    for part_name, start, count, _ in parts:
        block = verts[start:start + count].reshape(-1, 3, 8)
        t = 0
        while t < len(block):
            if t + 1 < len(block):
                quad = _face_quad(block[t], block[t + 1])
            else:
                quad = None
            if quad is None:
                # Not a clean box face: keep the source triangle untouched.
                for tri in block[t:t + 2]:
                    tris.append(tri[:, :3])
                    uvs.append(tri[:, 3:5])
                    shades.append(1.0)
                    colors.append([0, 0, 0])
                    flat.append(False)
                t += 2
                continue
            c0, c1, c2, c3 = quad
            face_normal = np.cross(block[t, 1, :3] - block[t, 0, :3], block[t, 2, :3] - block[t, 0, :3])
            face_normal /= np.linalg.norm(face_normal) + 1e-9

            def bilerp(a, b):
                return (c0 + (c1 - c0) * a + (c3 - c0) * b
                        + (c0 - c1 + c2 - c3) * a * b)

            normal = face_normal
            if abs(normal[0]) > 0.5:
                # Keep only the thin x-facing side/thickness faces intact.  Front,
                # back, top and bottom surfaces all receive square cell damage, so
                # large empty tops (Chen, Patchouli, Marisa) are not left untouched.
                tris.append([c0[:3], c1[:3], c2[:3]])
                uvs.append([c0[3:5], c1[3:5], c2[3:5]])
                shades.append(1.0)
                colors.append([0, 0, 0]); flat.append(False)
                tris.append([c0[:3], c2[:3], c3[:3]])
                uvs.append([c0[3:5], c2[3:5], c3[3:5]])
                shades.append(1.0)
                colors.append([0, 0, 0]); flat.append(False)
                t += 2
                continue
            margin = 1.0
            lx = float(np.linalg.norm(c1[:3] - c0[:3]))
            ly = float(np.linalg.norm(c3[:3] - c0[:3]))
            n = max(1, min(hi_n, int(round(lx / target))))
            m = max(1, min(hi_n, int(round(ly / target))))
            codes = [[1 for _ in range(m)] for _ in range(n)]
            for i in range(n):
                for j in range(m):
                    centre = bilerp((i + .5) / n, (j + .5) / m)
                    codes[i][j] = damage_code(level, part_name, *((centre[:3] - lo) / span), margin=margin)
            if level < 3:
                # Remove interior one-cell islands left between two bites.
                original = [row[:] for row in codes]
                for i in range(1, n - 1):
                    for j in range(1, m - 1):
                        if original[i][j] == 0:
                            continue
                        if (original[i - 1][j] == 0 and original[i + 1][j] == 0
                                and original[i][j - 1] == 0 and original[i][j + 1] == 0):
                            codes[i][j] = 0
            for i in range(n):
                for j in range(m):
                    code = codes[i][j]
                    p00 = bilerp(i / n, j / m)
                    p10 = bilerp((i + 1) / n, j / m)
                    p11 = bilerp((i + 1) / n, (j + 1) / m)
                    p01 = bilerp(i / n, (j + 1) / m)
                    centre = bilerp((i + .5) / n, (j + .5) / m)
                    if tex is not None:
                        th, tw = tex.shape[:2]
                        px = min(tw - 1, max(0, int(centre[3] * tw)))
                        py = min(th - 1, max(0, int(centre[4] * th)))
                        c = tex[py, px, :3].astype(np.float32)
                        luma = float(c.mean())
                    else:
                        c = np.array([200, 200, 200], np.float32)
                        luma = 200.0
                    white = np.array([255, 255, 255], np.float32)
                    muted = np.array([110, 95, 105], np.float32)
                    if luma < 70:
                        cap = np.clip(c * 2.00, 0, 255)
                        rim = np.clip(c * 1.20, 0, 255)
                    elif luma < 170:
                        cap = np.clip(c * 1.25, 0, 255)
                        rim = np.clip(c * .62, 0, 255)
                    else:
                        mean = float(c.mean())
                        chroma = c - mean
                        # Pale cloth: keep the hue, boost it a little and darken.
                        tinted = np.clip(mean + chroma * 2.2, 0, 255)
                        cap = np.clip(tinted * .78, 0, 255)
                        rim = np.clip(tinted * .55, 0, 255)
                    if not code:
                        # A close-colour inner cap: green cloth gets pale green, red
                        # cloth gets pale red, black cloth gets light grey, and pale
                        # cloth gets a muted darker version of itself.
                        inset = face_normal * 0.16
                        q00, q10, q11, q01 = p00[:3] - inset, p10[:3] - inset, p11[:3] - inset, p01[:3] - inset
                        tris.append([q00, q10, q11])
                        uvs.append([p00[3:5], p10[3:5], p11[3:5]])
                        shades.append(1.0); colors.append(cap); flat.append(True)
                        tris.append([q00, q11, q01])
                        uvs.append([p00[3:5], p11[3:5], p01[3:5]])
                        shades.append(1.0); colors.append(cap); flat.append(True)
                        continue
                    if code == 2:
                        tris.append([p00[:3], p10[:3], p11[:3]])
                        uvs.append([p00[3:5], p10[3:5], p11[3:5]])
                        shades.append(1.0); colors.append(rim); flat.append(True)
                        tris.append([p00[:3], p11[:3], p01[:3]])
                        uvs.append([p00[3:5], p11[3:5], p01[3:5]])
                        shades.append(1.0); colors.append(rim); flat.append(True)
                        continue
                    tris.append([p00[:3], p10[:3], p11[:3]])
                    uvs.append([p00[3:5], p10[3:5], p11[3:5]])
                    shades.append(1.0); colors.append([0, 0, 0]); flat.append(False)
                    tris.append([p00[:3], p11[:3], p01[:3]])
                    uvs.append([p00[3:5], p11[3:5], p01[3:5]])
                    shades.append(1.0); colors.append([0, 0, 0]); flat.append(False)
            t += 2
    return Mesh(np.asarray(tris, np.float32), np.asarray(uvs, np.float32), np.asarray(shades, np.float32),
                np.asarray(colors, np.float32), np.asarray(flat, bool))


# --------------------------------------------------------------------------- shard ----------


def shard_tint(species):
    tints = RECIPE["shard"]["tints"]
    if species in tints:
        return np.array(tints[species], np.float32)
    for key, value in tints.items():
        if key != "fallback" and species.startswith(key):
            return np.array(value, np.float32)
    return np.array(tints["fallback"], np.float32)


def tinted_shard(species):
    verts, parts, tex, lo, hi = load_prop(RECIPE["shard"]["name"])
    tris, uvs = [], []
    for _, start, count, _ in parts:
        block = verts[start:start + count].reshape(-1, 3, 8)
        tris.extend(block[:, :, :3])
        uvs.extend(block[:, :, 3:5])
    tex = tex.astype(np.float32)
    tex[..., :3] = (tex[..., :3] * shard_tint(species) / 255).clip(0, 255)
    return Mesh(np.asarray(tris, np.float32), np.asarray(uvs, np.float32)), tex.astype(np.uint8)


# --------------------------------------------------------------------------- main ------------


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cell, gap, head, label_w = 250, 10, 88, 96
    columns = [("完好", "intact"), ("轻损 1", "d1"), ("撕裂 2", "d2"),
               ("拼回 3", "d3"), ("碎片（三块同款）", "shard")]
    sheet = Image.new("RGBA", (label_w + len(columns) * (cell + gap) + gap,
                               head + len(SPECIES) * (cell + gap) + gap), (242, 238, 230, 255))
    d = ImageDraw.Draw(sheet)
    f22, f16, f13 = font(22), font(16), font(13)
    d.text((14, 10), "头饰损坏与碎片 · 完整头饰，少量像素缺损", fill=(40, 36, 32, 255), font=f22)
    d.text((14, 40), "完整轮廓保留；前/后/上/下方格去面，孔内用局部布料相近色，边缘同色系深色。碎片三块共用同一外观，只按头饰颜色着色。",
           fill=(96, 86, 76, 255), font=f13)
    for ci, (title, _) in enumerate(columns):
        d.text((label_w + ci * (cell + gap) + 8, head - 24), title, fill=(96, 86, 76, 255), font=f16)
    for ri, (sid, name) in enumerate(SPECIES):
        y = head + ri * (cell + gap)
        d.text((14, y + cell // 2 - 10), name, fill=(40, 36, 32, 255), font=f16)
        verts, parts, tex, lo, hi = load_prop(f"accessory_{sid}")
        for ci, (_, key) in enumerate(columns):
            if key == "shard":
                mesh, shard_tex = tinted_shard(sid)
                img = render(mesh, shard_tex, cell, 30, -20, ss=2)
            else:
                level = 0 if key == "intact" else int(key[1])
                mesh = nibble_mesh(verts, parts, lo, hi, level, tex)
                img = render(mesh, tex, cell, 30, -20, ss=2)
            x = label_w + ci * (cell + gap)
            sheet.alpha_composite(img, (x, y))
            d.rectangle([x + 2, y + 2, x + cell - 3, y + cell - 3], outline=(222, 214, 202, 255))
    sheet.save(OUT / "hat-damage.png")

    # close-up for the two hats where the pixel damage is easiest to judge
    close = Image.new("RGBA", (3 * 480, 3 * 500 + 40), (242, 238, 230, 255))
    cd = ImageDraw.Draw(close)
    cd.text((14, 8), "近看：完整 / 轻损 / 撕裂", fill=(40, 36, 32, 255), font=f22)
    for row, (sid, name) in enumerate((("reimu", "灵梦"), ("marisa", "魔理沙"), ("patchouli", "帕秋莉"))):
        verts, parts, tex, lo, hi = load_prop(f"accessory_{sid}")
        cd.text((14, 46 + row * 500), name, fill=(40, 36, 32, 255), font=f16)
        for ci, level in enumerate((0, 1, 2)):
            mesh = nibble_mesh(verts, parts, lo, hi, level, tex)
            img = render(mesh, tex, 470, 25, -22, ss=2)
            close.alpha_composite(img, (10 + ci * 480, 40 + row * 500))
    close.save(OUT / "hat-damage-close.png")

    (OUT / "index.html").write_text(
        '<!doctype html><meta charset="utf-8"><title>头饰损坏与碎片</title>'
        '<body style="background:#f2eee6;color:#2a2622;font-family:system-ui,sans-serif">'
        '<h1>头饰损坏与碎片</h1>'
        '<p>损坏保留完整头饰，只去掉小块面片；碎片三块同款，按头饰颜色着色。'
        '拼回状态使用完整外形，只沿两道很窄的缝加深（不挖空）。</p>'
        '<img src="hat-damage.png" style="max-width:100%">'
        '<h2>近看</h2><img src="hat-damage-close.png" style="max-width:100%"></body>',
        encoding="utf-8")
    print(OUT / "hat-damage.png")
    print(OUT / "hat-damage-close.png")


if __name__ == "__main__":
    sys.exit(main())
