"""Single pixel source for the bronze/silver/gold/platinum pin badge (item icon, worn decal, handbook medallions).

16x16, domed metal pin, top-left light. Tiers differ by value AND outline shape:
bronze = darker value, plain single rim; silver = plain double rim; gold = scalloped (notched) rim;
platinum = cold white octagon with a deep blue-grey edge and an engraved inner ring (a double line border).
Original design; bronze to gold carry a simplified yukkuri head silhouette, platinum a pair of opposing
dialogue arcs (two speech waves meeting) for its explanation-and-consideration exam.
"""
import math
from PIL import Image

# Locked palettes: outline, shade (dark -> light), specular. Reuse these for GUI/handbook.
PALETTES = {
    'bronze': {'outline': '#3a1d0f', 'shade': ['#8f5428', '#b6713a', '#d08e52'], 'light': '#e8a868', 'spec': '#f6d2a8', 'engrave': '#4a2814'},
    'silver': {'outline': '#2c3642', 'shade': ['#8c98a6', '#bec8d2', '#e8eef3'], 'light': '#f4f8fb', 'spec': '#ffffff', 'engrave': '#5a6676'},
    'gold':   {'outline': '#5c340e', 'shade': ['#ba781e', '#e8aa2e', '#fad258'], 'light': '#ffe58a', 'spec': '#fff8d0', 'engrave': '#8a5414'},
    'platinum': {'outline': '#283150', 'shade': ['#a9b3cb', '#d6dcea', '#f1f4fa'], 'light': '#ffffff', 'spec': '#ffffff', 'engrave': '#3c4a70'},
}
TIERS = ('bronze', 'silver', 'gold', 'platinum')

# Emblem: flat yukkuri head silhouette 6x4, engraved (dark) with a 1px lit emboss edge; eyes show the face metal.
EMBLEM = [
    '.XXXX.',
    'XeXXeX',
    'XXXXXX',
    '.XXXX.',
]
SIZE = 16
GROOVE = {'silver': '#667383'}  # silver inner groove between the two rims (named, locked with the palette)

# Gold teeth: 4 cardinal teeth 2x1 and 4 diagonal teeth stepping 1px (no 2x2 lumps), on the 14px disc.
GOLD_TEETH = {(7, 0), (8, 0), (7, 15), (8, 15), (0, 7), (0, 8), (15, 7), (15, 8),
              (3, 2), (2, 3), (12, 2), (13, 3), (3, 13), (2, 12), (12, 13), (13, 12)}

def _radius(tier, a):
    return 7.05 if tier in ('bronze', 'gold') else 7.95  # bronze/gold: 14px disc; silver: 16px circle

def badge(tier):
    if tier == 'platinum':
        return _platinum()
    p = PALETTES[tier]
    im = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    info = {}
    for y in range(SIZE):
        for x in range(SIZE):
            dx, dy = x + .5 - 8, y + .5 - 8
            r = math.hypot(dx, dy)
            info[x, y] = (r <= _radius(tier, math.atan2(dy, dx)) or (tier == 'gold' and (x, y) in GOLD_TEETH), r, dx, dy)
    inside = lambda x, y: info.get((x, y), (False,))[0]
    base = {'bronze': 7.05, 'silver': 7.95, 'gold': 6.95}[tier]
    face_r = {'bronze': 5.2, 'silver': 4.1, 'gold': 5.1}[tier]
    for (x, y), (ins, r, dx, dy) in info.items():
        if not ins: continue
        d = (dx + dy) * -.7071  # + toward the top-left light
        if any(not inside(x + i, y + j) for i, j in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            c = p['outline']
        elif tier == 'silver' and 5.0 <= r < 6.0:
            c = GROOVE['silver']
        elif r > face_r:  # raised rim
            lit = d / max(r, .01)
            c = p['shade'][2] if lit > .3 else p['shade'][1] if lit > -.4 else p['shade'][0]
        else:  # domed face
            c = p['shade'][2] if d > 1.6 else p['shade'][1] if d > -1.2 else p['shade'][0]
        im.putpixel((x, y), _rgba(c))
    ox, oy = 5, 6
    body = {(ox + i, oy + j): ch for j, row in enumerate(EMBLEM) for i, ch in enumerate(row) if ch != '.'}
    for (x, y) in body:
        for ex, ey in ((x, y + 1), (x + 1, y + 1), (x + 1, y)):
            if (ex, ey) not in body:
                im.putpixel((ex, ey), _rgba(p['light']))
    for (x, y), ch in body.items():
        im.putpixel((x, y), _rgba(p['engrave']) if ch == 'X' else _rgba(p['shade'][1]))
    for xy in SPEC[tier]:
        im.putpixel(xy, _rgba(p['spec']))
    return im

SPEC = {'bronze': ((3, 3), (4, 2)), 'silver': ((3, 2), (2, 3)), 'gold': ((3, 3), (4, 2)), 'platinum': ((4, 4), (5, 4), (4, 5))}

# Platinum emblem: two 5-pixel arcs bulging toward each other, ")(" with a 2-pixel gap.
DIALOGUE_ARCS = ((5, 5), (6, 6), (6, 7), (6, 8), (5, 9), (10, 5), (9, 6), (9, 7), (9, 8), (10, 9))


def octagon(level, x, y, size=SIZE):
    """Octagon inset by `level` pixels: straight edges half the width, 45-degree corner cuts (8-wide top on 16)."""
    half = size / 2
    dx, dy = abs(x + .5 - half), abs(y + .5 - half)
    return max(dx, dy) <= half - .5 - level and dx + dy <= size * 11 / 16 - level


def _platinum():
    p = PALETTES['platinum']
    im = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    for y in range(SIZE):
        for x in range(SIZE):
            if not octagon(0, x, y): continue
            d = -(x + .5 - 8) - (y + .5 - 8)  # + toward the top-left light
            if not octagon(1, x, y):
                c = p['outline']
            elif not octagon(2, x, y):  # bright rim
                c = p['shade'][2] if d > 2 else p['shade'][1] if d > -3 else p['shade'][0]
            elif not octagon(3, x, y):  # engraved inner ring
                c = p['engrave']
            else:  # domed face
                c = p['shade'][2] if d > 2 else p['shade'][1] if d > -2.5 else p['shade'][0]
            im.putpixel((x, y), _rgba(c))
    arcs = set(DIALOGUE_ARCS)
    for x, y in arcs:  # lit lower-right emboss edge, as on the other tiers' emblem
        for ex, ey in ((x + 1, y), (x, y + 1), (x + 1, y + 1)):
            if (ex, ey) not in arcs and octagon(3, ex, ey):
                im.putpixel((ex, ey), _rgba(p['light']))
    for xy in arcs:
        im.putpixel(xy, _rgba(p['engrave']))
    for xy in SPEC['platinum']:
        im.putpixel(xy, _rgba(p['spec']))
    return im

def _rgba(h):
    h = h.lstrip('#'); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)

def badges():
    return {t: badge(t) for t in TIERS}

if __name__ == '__main__':
    import sys
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    for t, im in badges().items():
        im.save(f'{out}/{t}.png')
