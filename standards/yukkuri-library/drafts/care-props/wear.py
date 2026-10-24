"""Worn swaddle textures for client/CareWearRenderer: the leaf-swaddle leaf sheet and the cloth-swaddle wool tile.

Both are drawn here in the vanilla item style of supplies.py (light from the top left, hue-shifted local ramps, the outline is
the darkest step of its own material, no black, no anti-aliasing, no partial alpha). Nothing is copied from vanilla files.

swaddle_leaf_wear.png, 32x64: two whole leaves lying horizontally, variant A in rows 0-15, variant B (yellower, lighter) in rows
16-31, and the same two leaves stool-stained in rows 32-47 and 48-63 (a muted brownish yellow laid over the blade, veins dulled,
the outline kept), which the renderer uses for the lowest grid cells of a soiled wrap. In each 32x16 cell the petiole stub is at x 0-2 on the midrib row (local y 8), the pointed tip at x 31; the top margin
(local y 0) is the rim of the wrap on the body, local y 15 lies near the ground. Outside the leaf alpha is 0 (cutout render type).

swaddle_wool_wear.png, 16x16: a seamless knit (stockinette V-stitch) wool tile, light and neutral, wrapped with GL_REPEAT and
multiplied by the renderer's vertex colours (cream cloth, darker fold, pink hem).

Run this file to rewrite the snapshots in art/props/pet-goods/; scripts/export_pet_goods_icons.py ships them to
textures/misc/. --check is read-only.
"""
import argparse, io, math, random, sys
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
SNAPSHOT = ROOT / 'art/props/pet-goods'

# ---- ramps (dark -> light) ------------------------------------------------------------------------------------------
LEAF_A = ['#2c5418', '#3f7a1f', '#4f9a2a', '#67b53a', '#86c94f', '#a6d86a']
LEAF_B = ['#3d5a16', '#56801f', '#6d9e29', '#88b93a', '#a5cc50', '#c3dd70']
STAIN = (172, 134, 68)    # muted brownish yellow of stool soaked into a leaf
WOOL = [(236, 234, 228), (244, 243, 238), (250, 249, 245), (255, 255, 253)]   # close, near-white steps like vanilla white wool


def rgba(c):
    if isinstance(c, str):
        return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) + (255,)
    return tuple(c) + (255,)


def png(img):
    out = io.BytesIO()
    img.save(out, 'PNG', optimize=True)
    return out.getvalue()


# ---- leaf -----------------------------------------------------------------------------------------------------------
MID = 8           # midrib row in a 32x16 cell
BLADE0 = 3        # first blade column; columns 0-2 carry the petiole stub


def half_width(x, top, nicks, tilt):
    """Blade half height above (top=True) or below the midrib at column x: rounded base, widest a little before the middle,
    tapering to a point at x 31; serrated every 3 columns, plus irregular nicks."""
    t = (x - BLADE0 + .5) / (32 - BLADE0)
    if t <= 0 or t >= 1:
        return -1
    shape = math.sin(math.pi * t ** .72) ** .62 * (1 - .35 * max(0, t - .7) / .3)
    full = 7.4 if not top else 7.0
    w = full * shape + (tilt * (t - .5) if top else -tilt * (t - .5))
    if (x + (0 if top else 1)) % 3 == 0 and .08 < t < .94:   # teeth: a notch every third column
        w -= .9
    w -= nicks.get((x, top), 0)
    return w


def leaf(ramp, nicks, tilt, seed):
    cell = Image.new('RGBA', (32, 16), (0, 0, 0, 0))
    inside = set()
    for x in range(32):
        for top in (True, False):
            w = half_width(x, top, nicks, tilt)
            if w < 0:
                continue
            for d in range(0, 8):
                y = MID - d if top else MID + d
                if 0 <= y < 16 and d <= w + .35:
                    inside.add((x, y))
    stem = {(0, MID), (1, MID), (2, MID), (0, MID + 1)}
    rng = random.Random(seed)
    colour = {}
    for x, y in inside:
        upper = y < MID
        step = 3 if upper else 2
        if upper and (x + (MID - y)) < 15 and y <= MID - 2:
            step = 4                                            # light from the top left
        if not upper and y >= MID + 4:
            step = 2 if x < 20 else 1
        if rng.random() < .06:
            step += rng.choice((-1, 1))                         # sparse speckle like vanilla foliage
        colour[(x, y)] = max(1, min(4, step))
    # side veins: pairs angled toward the tip, alternating above and below; one step lighter than the blade
    for x0 in (6, 11, 16, 21, 26):
        for k in range(1, 7):
            for (x, y) in ((x0 + k, MID - k), (x0 + 1 + k, MID + k)):
                if (x, y) in inside and (x + 1, y) in inside and (x, y - 1 if y < MID else y + 1) in inside:
                    colour[(x, y)] = 5 if y < MID else 4
    for x in range(BLADE0 - 1, 30):                             # midrib, lightest; fades to a darker step near the tip
        if (x, MID) in inside or x < BLADE0:
            colour[(x, MID)] = 5 if x < 24 else 4
    # outline: darkest step on the shaded bottom/right, one lighter on the lit top/left
    for x, y in inside:
        out = [(x + dx, y + dy) not in inside for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if any(out):
            lit = out[1] or out[3]
            shaded = out[0] or out[2]
            colour[(x, y)] = 0 if shaded or not lit else 1
    for x, y in stem:
        colour[(x, y)] = 1 if y == MID else 0
    for (x, y), step in colour.items():
        cell.putpixel((x, y), rgba(ramp[step]))
    return cell


def stained(cell, seed):
    """The same leaf soaked through: every blade pixel mixed 60-75% towards STAIN, keeping its own light and dark steps."""
    out = cell.copy()
    rng = random.Random(seed)
    for x in range(cell.width):
        for y in range(cell.height):
            r, g, b, a = cell.getpixel((x, y))
            if a == 0:
                continue
            k = .6 + .15 * rng.random()
            lum = (r + g + b) / 3 / 105
            out.putpixel((x, y), tuple(min(255, round((c * (1 - k) + s * k * lum))) for c, s in zip((r, g, b), STAIN)) + (255,))
    return out


def leaf_sheet():
    sheet = Image.new('RGBA', (32, 64), (0, 0, 0, 0))
    a = leaf(LEAF_A, {(13, True): 1.6, (14, True): 1.2, (22, False): 1.4}, .6, 37)
    b = leaf(LEAF_B, {(9, False): 1.5, (19, True): 1.6, (20, True): 1.0}, -.5, 91)
    sheet.paste(a, (0, 0)); sheet.paste(b, (0, 16))
    sheet.paste(stained(a, 5), (0, 32)); sheet.paste(stained(b, 6), (0, 48))
    return sheet


# ---- wool -----------------------------------------------------------------------------------------------------------
# One stockinette stitch, 4 wide x 4 high: two light legs converging into a V, a dim gap below (0 dark .. 3 light).
STITCH = [
    "3113",
    "2332",
    "0220",
    "1001",
]


def wool_tile():
    img = Image.new('RGBA', (16, 16))
    rng = random.Random(7)
    for y in range(16):
        for x in range(16):
            column = x // 4
            sy = y % 4
            step = int(STITCH[sy][x % 4])
            step = max(0, min(3, step + (1 if step < 3 and rng.random() < .12 else 0) - (1 if step > 0 and rng.random() < .10 else 0)))
            img.putpixel((x, y), rgba(WOOL[step]))
    return img


def outputs():
    return {SNAPSHOT / 'swaddle_leaf_wear.png': png(leaf_sheet()), SNAPSHOT / 'swaddle_wool_wear.png': png(wool_tile())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    sys.path.insert(0, str(ROOT / 'scripts'))
    from asset_export_checks import check_output, preserve_equivalent_png
    changed = []
    for path, content in outputs().items():
        content = preserve_equivalent_png(path, content)
        if not check_output(path, content):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    if args.check and changed:
        raise SystemExit('Wear texture snapshot differs: ' + ', '.join(changed))
    print('Wear textures: 2; ' + ('match.' if args.check else f'{len(changed)} files written.'))


if __name__ == '__main__':
    main()
