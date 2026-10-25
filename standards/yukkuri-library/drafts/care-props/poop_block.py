"""Droppings heap (嗯嗯, block yukkuri_poop): four tiered native block models and one hand-painted texture.

Each tier is a vanilla Java block model of stacked low boxes (y rotations of 0 / +-22.5 / 45 degrees), drawn like the
care-prop 便堆 swirl: every dropping is a soft-serve coil whose layers shrink and drift around the axis, with a glossy tip.
Tier 1 is one small dropping, tier 2 a pair, tier 3 a low pile under a swirl with two strays, tier 4 a heaped mound that
nearly fills the cell floor. Heights stay within the selection outlines of life/PoopBlock (2 / 4 / 6 / 8 units); the
block has no collision. Texture: 32 x 32 at 1 texel per unit, four tones of a bean-paste ramp hue-shifted from the
shared palette's anko (art/props/palette.json), lit from the upper left, darkest tone for grooves and contact, no black outline, no anti-aliasing.

Writes the runtime models, texture and blockstate, and the snapshot art/source/poop-block/ with a manifest of
fingerprints. --check is read-only.
"""
import argparse, hashlib, io, json, math, sys, zlib
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from asset_export_checks import check_output, preserve_equivalent_png

ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
SNAPSHOT = ROOT / 'art/source/poop-block'
GENERATOR = 'standards/yukkuri-library/drafts/care-props/poop_block.py'
NAME = 'yukkuri_poop'
TEX = f'mcyukkuri:block/{NAME}'
# bean-paste red-brown, hue-shifted from the shared palette's anko ramp (art/props/palette.json) toward red and lifted
# so it stands apart from dirt and grass and still reads in a shaded pit.
# dark (grooves, pits), mid (coil sides), light (tops), gloss (wet highlights)
DARK, MID, LIGHT, GLOSS = '#3a1f24', '#5e3236', '#84483f', '#b0705a'
# selection outlines of life/PoopBlock.OUTLINES (x0, z0, x1, z1, height)
OUTLINES = [(4, 4, 12, 12, 2), (2, 2, 14, 14, 4), (1, 1, 15, 15, 6), (0, 0, 16, 16, 8)]


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def noise(*k):
    return zlib.crc32(repr(k).encode()) % 1000 / 1000


# ---- texture -----------------------------------------------------------------------------------------------------
# row 0          coil sides (every layer is 1 unit high), u follows world x / z plus a per-element offset
# 0..15 x 8..23  tops, mapped by world x / z so neighbouring layers continue the grain
# 16..19 x 8..11 glossy tip
def texture():
    img = Image.new('RGBA', (32, 32), rgb(MID))
    px = img.load()
    for x in range(32):
        # coil side: twisted streaks of the lighter tone lean with the coil, a few dark pits
        streak = (x % 5 == 0) or (x % 5 == 1 and noise('s', x) < .45)
        px[x, 0] = rgb(LIGHT if streak else DARK if noise('p', x) < .12 else MID)
    for z in range(16):
        for x in range(16):
            # tops: light paste with darker coil grooves winding around the cell centre and wet gloss flecks
            a = math.atan2(z - 7.5, x - 7.5)
            r = math.hypot(x - 7.5, z - 7.5)
            groove = (r * 1.15 + a * 1.6 / math.pi) % 3.0 < .55
            n = noise('t', x, z)
            c = DARK if groove and n < .7 else MID if (groove or n < .16) else GLOSS if n > .93 else LIGHT
            px[x, 8 + z] = rgb(c)
    tip = [[LIGHT, GLOSS, LIGHT, MID], [GLOSS, GLOSS, LIGHT, MID], [LIGHT, LIGHT, MID, MID], [MID, MID, MID, DARK]]
    for z in range(4):
        for x in range(4):
            px[16 + x, 8 + z] = rgb(tip[z][x])
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    return buf.getvalue()


# ---- geometry ----------------------------------------------------------------------------------------------------
# (centre x, centre z, width x, depth z, bottom y, rotation about y); every layer is 1 unit high
def swirl(x, z, w, y, layers, turn=1):
    """A soft-serve coil: each layer narrower, drifting around the axis, alternating twist; last layer is the tip."""
    out = []
    for i in range(layers):
        k = 1 - i / max(layers - 1, 1) * .8
        drift = .5 * i
        a = turn * i * 1.9
        rot = (22.5, -22.5)[i % 2] * turn if i < layers - 1 else 45.0
        size = max(1.5, round(w * k * 2) / 2)
        out.append((x + drift * math.cos(a), z + drift * math.sin(a), size, size, y + i, rot))
    out[-1] = out[-1][:5] + (45.0,)
    return out


def cross(x, z, long, short, y):
    """Two crossed boxes: a chamfered, roughly round slab layer."""
    return [(x, z, long, short, y, 0.0), (x, z, short, long, y, 0.0)]


TIERS = {
    1: [(8.0, 8.3, 6.0, 5.0, 0, 22.5), (7.7, 7.9, 4.0, 3.0, 1, -22.5)],
    2: swirl(6.6, 6.8, 7.0, 0, 4) + [(10.8, 10.8, 5.0, 4.0, 0, -22.5), (10.6, 10.5, 3.0, 2.5, 1, 22.5)],
    3: cross(7.8, 8.0, 11.0, 8.0, 0) + swirl(7.6, 7.8, 8.0, 1, 5, -1)
       + [(3.2, 12.8, 2.5, 2.0, 0, -22.5), (12.6, 3.6, 3.0, 2.5, 0, 22.5), (12.5, 3.8, 1.5, 1.5, 1, 45.0)],
    4: cross(8.0, 8.0, 15.0, 11.0, 0) + cross(7.8, 8.2, 12.0, 9.0, 1) + swirl(7.9, 7.8, 9.0, 2, 6)
       + [(12.6, 11.6, 3.0, 2.5, 2, 22.5), (12.4, 11.3, 1.5, 1.5, 3, 45.0),
          (3.6, 4.2, 3.0, 3.0, 1, -22.5), (3.8, 4.0, 1.5, 1.5, 2, 45.0), (12.4, 3.6, 2.5, 2.0, 1, 22.5)],
}


def r(v):
    return round(v * 1000) / 1000


def corners(box):
    x, z, w, d, y, rot = box
    a = math.radians(rot)
    c, s = math.cos(a), math.sin(a)
    for sx in (-1, 1):
        for sz in (-1, 1):
            dx, dz = sx * w / 2, sz * d / 2
            yield x + dx * c + dz * s, z - dx * s + dz * c


def element(box, tip, salt):
    x, z, w, d, y, rot = box
    x0, x1, z0, z1 = x - w / 2, x + w / 2, z - d / 2, z + d / 2
    off = int(noise('o', salt) * 14)
    row = (0, 1)

    def side(lo, hi):
        u0 = (lo + off) % 16
        return [r(u0 / 2), row[0] / 2, r((u0 + hi - lo) / 2), row[1] / 2]
    faces = {'north': side(x0, x1), 'south': side(x0, x1), 'west': side(z0, z1), 'east': side(z0, z1)}
    faces['up'] = ([8.0, 4.0, r(8 + w / 2), r(4 + d / 2)] if tip else
                   [r(max(x0, 0) / 2), r((8 + max(z0, 0)) / 2), r(min(x1, 16) / 2), r((8 + min(z1, 16)) / 2)])
    e = {'from': [r(x0), y, r(z0)], 'to': [r(x1), y + 1, r(z1)],
         'faces': {k: {'uv': v, 'texture': '#poop'} for k, v in faces.items()}}
    if rot:
        e['rotation'] = {'angle': rot, 'axis': 'y', 'origin': [r(x), y, r(z)]}
    return e


def model(tier):
    boxes = TIERS[tier]
    x0, z0, x1, z1, h = OUTLINES[tier - 1]
    top = max(b[4] + 1 for b in boxes)
    assert top <= h, (tier, top)
    for b in boxes:
        for cx, cz in corners(b):
            assert x0 - 1e-6 <= cx <= x1 + 1e-6 and z0 - 1e-6 <= cz <= z1 + 1e-6, (tier, b, cx, cz)
    # a box is a tip when nothing sits on it
    def covered(b):
        return any(o[4] == b[4] + 1 and math.hypot(o[0] - b[0], o[1] - b[1]) < b[2] / 2 for o in boxes)
    return {'textures': {'particle': TEX, 'poop': TEX},
            'elements': [element(b, not covered(b) and b[2] <= 4, (tier, i)) for i, b in enumerate(boxes)]}


def outputs():
    def dumps(data):
        return (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    result = {}
    png = preserve_equivalent_png(ASSETS / f'textures/block/{NAME}.png', texture())
    result[ASSETS / f'textures/block/{NAME}.png'] = png
    result[SNAPSHOT / f'{NAME}.png'] = png
    for tier in TIERS:
        data = dumps(model(tier))
        result[ASSETS / f'models/block/{NAME}_tier{tier}.json'] = data
        result[SNAPSHOT / f'{NAME}_tier{tier}.json'] = data
    result[ASSETS / f'blockstates/{NAME}.json'] = dumps(
        {'variants': {f'tier={t}': {'model': f'mcyukkuri:block/{NAME}_tier{t}'} for t in TIERS}})
    files = {p.relative_to(ROOT).as_posix(): hashlib.sha256(v).hexdigest() for p, v in result.items()}
    result[SNAPSHOT / 'manifest.json'] = (json.dumps({
        'id': NAME, 'status': 'pending_user_confirmation', 'generator': GENERATOR,
        'tiers': {str(t): {'portions': f'{2 * t - 1}-{2 * t}', 'elements': len(TIERS[t]),
                           'outline': OUTLINES[t - 1]} for t in TIERS},
        'texture': '32x32, 1 texel per unit, anko ramp of art/props/palette.json (4 tones)',
        'files': files}, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    changed = []
    for path, content in outputs().items():
        if not check_output(path, content):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    if args.check and changed:
        raise SystemExit('Poop block exports differ: ' + ', '.join(changed))
    print('Poop block: ' + ('match.' if args.check else f'{len(changed)} files written.'))
    for t in TIERS:
        print(f'  tier {t}: {len(TIERS[t])} elements, top {max(b[4] + 1 for b in TIERS[t])}')


if __name__ == '__main__':
    main()
