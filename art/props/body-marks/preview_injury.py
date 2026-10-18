"""Layout preview of the lasting body signs on the real skin mesh (orthographic, no hat or hair).

Mirrors client/WoundRenderer.java for the kinds drawn by body/BodySigns.java: a deep tear with filling showing ("paste"),
the bare and the broken centre filling ("core"), the baked sole ("char"), the four mould tiers ("mold") and the sag of a
body with little filling (BodySigns.sag*). Colours come from body_marks.png (swatches 5-14) and body_wounds.png (tear rings).
It is a layout check, not the game renderer.

Output: .local/previews/injury/injury.png (run with the bundled Pillow/numpy python).
"""
import importlib.util
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
# Previews go to the main checkout's .local, also when run from a worktree under .worktrees/.
OUT = (ROOT.parents[1] if ROOT.parent.name == ".worktrees" else ROOT) / ".local/previews/injury"
spec = importlib.util.spec_from_file_location("marks_preview", HERE / "preview.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
RES = ROOT / "src/main/resources/assets/mcyukkuri/textures/props"
MARKS = [tuple(Image.open(RES / "body_marks.png").convert("RGB").getpixel((x, 0))) for x in range(16)]
WOUNDS = [tuple(Image.open(RES / "body_wounds.png").convert("RGB").getpixel((x, 0))) for x in range(16)]
SIZE = base.SIZE
BG = (232, 238, 232)


def m(material):
    """WoundRenderer material number to colour."""
    return MARKS[material - 16] if material >= 16 else WOUNDS[material]


def disk(img, ctx, site, cx, cy, rx, ry, material, inner=0.0):
    world, normal, valid = ctx
    anchor, n = site
    mask = base.disk_mask(world, normal, anchor, n, rx, ry, 1, (cx, cy)) & valid
    if inner > 0:
        mask &= ~base.disk_mask(world, normal, anchor, n, rx * inner, ry * inner, 1, (cx, cy))
    img[mask] = m(material)


def line(img, ctx, site, ax, ay, bx, by, width, material):
    world, normal, valid = ctx
    anchor, n = site
    img[base.line_mask(world, normal, anchor, n, (ax, ay), (bx, by), width) & valid] = m(material)


def tear(img, ctx, site, severity=3, size=1.0):
    s = size * (.6 + severity * .16)
    disk(img, ctx, site, 0, 0, 1.13 * s, .79 * s, 1, .88)
    disk(img, ctx, site, 0, 0, s, .7 * s, 3, .78)
    disk(img, ctx, site, 0, 0, .8 * s, .55 * s, 4)
    return s


def paste(img, ctx, site, s):
    q = s * .62
    disk(img, ctx, site, 0, 0, q, .7 * q, 24)
    for i in range(5):
        a, r = i * 2.3999, .45 * q
        disk(img, ctx, site, math.cos(a) * r, math.sin(a) * r * .7, .13 * q, .1 * q, 25)


def core(img, ctx, site, s, broken):
    q = s * .46
    disk(img, ctx, site, 0, 0, q, .8 * q, 26)
    disk(img, ctx, site, -.25 * q, .22 * q, .32 * q, .22 * q, 27)
    if broken:
        line(img, ctx, site, -.9 * q, -.5 * q, 0, .05 * q, .16, 28)
        line(img, ctx, site, 0, .05 * q, .85 * q, .6 * q, .16, 28)
        line(img, ctx, site, 0, .05 * q, .15 * q, -.85 * q, .14, 28)


def mold(img, ctx, site, tier, size, seed):
    q = size * (.5 + .22 * min(3, tier))
    sev = min(3, tier)
    disk(img, ctx, site, 0, 0, q, .82 * q, 21)
    for i in range(2 + sev):
        a, r = i * 2.3999 + (seed & 7), q * (.4 + .12 * (i % 3))
        disk(img, ctx, site, math.cos(a) * r, math.sin(a) * r, .26 * q, .21 * q, 22)
    for i in range(2 + sev * 2):
        a, r = i * 1.7 + (seed >> 3 & 7), q * .5 * math.sqrt((i + 1) / (3 + sev * 2))
        disk(img, ctx, site, math.cos(a) * r, math.sin(a) * r, .1 * q, .09 * q, 23)


def char(img, ctx, site):
    q = 1.7 * 1.1
    disk(img, ctx, site, 0, 0, 1.15 * q, .8 * q, 30)
    disk(img, ctx, site, 0, 0, .85 * q, .58 * q, 29)
    for i in range(4):
        a = i * 1.57 + .4
        line(img, ctx, site, 0, 0, math.cos(a) * 1.1 * q, math.sin(a) * .75 * q, .12, 29)


def tri_site(pts, t, u=.33, v=.33):
    a = (1 - u - v) * pts[t, :3] + u * pts[t + 1, :3] + v * pts[t + 2, :3]
    n = np.cross(pts[t + 1, :3] - pts[t, :3], pts[t + 2, :3] - pts[t, :3])
    return a.astype(np.float32), (n / max(np.linalg.norm(n), 1e-9)).astype(np.float32)


def facing_site(pts, skin, direction, height):
    """The skin triangle whose normal best faces a direction at a body height share (front cheek, side...)."""
    sk = pts[:skin, :3].reshape(-1, 3, 3)
    c = sk.mean(1)
    n = np.cross(sk[:, 1] - sk[:, 0], sk[:, 2] - sk[:, 0])
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
    lo, hi = pts[:skin, 1].min(), pts[:skin, 1].max()
    h = (c[:, 1] - lo) / (hi - lo)
    score = n @ np.array(direction, np.float32) - 2 * np.abs(h - height)
    return tri_site(pts, int(np.argmax(score)) * 3)


def sole_sites(pts, skin):
    """BodySigns.soleSites: six evenly spaced triangles on the lowest tenth of the rim."""
    sk = pts[:skin, :3].reshape(-1, 3, 3)
    c = sk.mean(1)
    n = np.cross(sk[:, 1] - sk[:, 0], sk[:, 2] - sk[:, 0])
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
    lo, hi = pts[:skin, 1].min(), pts[:skin, 1].max()
    mins, maxs = pts[:skin, :3].min(0), pts[:skin, :3].max(0)
    cx, cz = (mins[0] + maxs[0]) / 2, (mins[2] + maxs[2]) / 2
    ok = (c[:, 1] <= lo + (hi - lo) * .1) & (n[:, 1] >= -.85)
    ang = np.arctan2(c[:, 2] - cz, c[:, 0] - cx)
    radial = np.hypot(c[:, 0] - cx, c[:, 2] - cz)
    out = []
    for k in range(6):
        target = -math.pi + k * math.pi / 3 + math.pi / 6
        d = np.abs(np.arctan2(np.sin(ang - target), np.cos(ang - target))) - radial * .02 + c[:, 1] * .05
        d[~ok] = 1e9
        out.append(tri_site(pts, int(np.argmin(d)) * 3))
    return out


def mold_sites(pts, skin, count, seed):
    """Like BodySigns.moldSite (anywhere but the underside and the lowest rim), limited to the side the camera sees."""
    rng = np.random.default_rng(seed)
    lo, hi = pts[:skin, 1].min(), pts[:skin, 1].max()
    found = []
    while len(found) < count:
        t = int(rng.integers(0, skin // 3)) * 3
        a, n = tri_site(pts, t, rng.random() * .8 + .1, .1)
        if n[1] < -.45 or n[2] > -.25 or (a[1] - lo) / (hi - lo) < .12:
            continue
        found.append((a, n))
    return found


def panel(asset, yaw, label, paint, filling=12):
    pts, skin, tex = base.load(asset)
    pts = pts.copy()
    sag = min(1.0, max(0.0, (6 - filling) / 6))
    if sag > 0:  # BodySigns.sagWidth / sagHeight about the feet
        pts[:, 0] *= 1 + .06 * sag
        pts[:, 2] *= 1 + .06 * sag
        pts[:, 1] *= 1 - .14 * sag
    color, world, normal, owner, depth, rot = base.render(pts, skin, tex, yaw)
    valid = owner >= 0
    paint(color, (world, normal, valid), pts, skin)
    img = Image.fromarray(np.clip(color, 0, 255).astype(np.uint8))
    out = Image.new("RGB", img.size, BG)
    out.paste(img, (0, 0), Image.fromarray((valid * 255).astype(np.uint8)))
    ImageDraw.Draw(out).text((6, 4), f"{asset}: {label}", fill=(40, 50, 40))
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    rest_pts, rest_skin, _ = base.load("reimu")
    rest_site = facing_site(rest_pts, rest_skin, (.3, -.15, -.94), .3)

    def deep(filling_shows=False, broken=False, filling=12):
        sag = min(1.0, max(0.0, (6 - filling) / 6))
        anchor = rest_site[0] * np.array([1 + .06 * sag, 1 - .14 * sag, 1 + .06 * sag], np.float32)

        def paint(img, ctx, pts, skin):
            site = (anchor, rest_site[1])
            s = tear(img, ctx, site)
            paste(img, ctx, site, s)
            if filling_shows or broken:
                core(img, ctx, site, s, broken)
        return paint

    def sole(img, ctx, pts, skin):
        for site in sole_sites(pts, skin):
            char(img, ctx, site)

    def molds(tier):
        count = [0, 3, 4, 6, 9][tier]
        size = [0, .95, 1.1, 1.4, 1.75][tier]

        def paint(img, ctx, pts, skin):
            rng = np.random.default_rng(5)
            for i, site in enumerate(mold_sites(pts, skin, count, 21)):
                mold(img, ctx, site, tier, size * (.85 + .3 * rng.random()), 31 * i + 7)
        return paint

    rows = [
        [("reimu", 1e-3, "deep tear (front): filling shows", deep(), 12),
         ("reimu", 1e-3, "filling 4: core bare, body sags", deep(True, filling=4), 4),
         ("reimu", 1e-3, "core broken (dying / remains)", deep(True, True, 2), 2),
         ("reimu", math.pi / 2, "baked sole, side", sole, 12),
         ("reimu", 1e-3, "filling 0: full sag, no marks", lambda *a: None, 0)],
        [("marisa", 1e-3, "mould early (1-2)", molds(1), 12),
         ("marisa", 1e-3, "mould middle (3-5)", molds(2), 12),
         ("marisa", 1e-3, "mould late (6-7)", molds(3), 12),
         ("marisa", 1e-3, "mould terminal (8)", molds(4), 12),
         ("marisa", math.pi / 2, "baked sole, side", sole, 12)],
    ]
    sheet = Image.new("RGB", (SIZE * 5, SIZE * (len(rows) + 1)), BG)
    for r, row in enumerate(rows):
        for c, (asset, yaw, label, paint, filling) in enumerate(row):
            sheet.paste(panel(asset, yaw, label, paint, filling), (c * SIZE, r * SIZE))
    # Close-ups (x3) of the three deep-wound panels, centred on the wound.
    pts, skin, _ = base.load("reimu")
    anchor = facing_site(pts, skin, (.3, -.15, -.94), .3)[0]
    for c, filling in enumerate((12, 4, 2)):
        sag = min(1.0, max(0.0, (6 - filling) / 6))
        x = c * SIZE + anchor[0] * (1 + .06 * sag) * base.SCALE + SIZE / 2
        y = SIZE * .82 - anchor[1] * (1 - .14 * sag) * base.SCALE
        crop = sheet.crop((int(x) - 50, int(y) - 50, int(x) + 50, int(y) + 50)).resize((SIZE, SIZE), Image.NEAREST)
        sheet.paste(crop, (c * SIZE, len(rows) * SIZE))
        ImageDraw.Draw(sheet).text((c * SIZE + 6, len(rows) * SIZE + 4), ["x3: filling shows", "x3: centre filling bare", "x3: centre filling broken"][c], fill=(240, 240, 240))
    sheet.save(OUT / "injury.png")
    print("wrote", OUT / "injury.png")


if __name__ == "__main__":
    main()
