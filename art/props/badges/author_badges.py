"""Single pixel source for the bronze/silver/gold pin badge (item icon, worn decal, handbook medallions).

16x16, domed metal pin, top-left light. Tiers differ by value AND outline shape:
bronze = darker value, plain single rim; silver = plain double rim; gold = scalloped (notched) rim.
Original design; the emblem is a simplified yukkuri head silhouette.
"""
import math
from PIL import Image

# Locked palettes: outline, shade (dark -> light), specular. Reuse these for GUI/handbook.
PALETTES = {
    'bronze': {'outline': '#3a1d0f', 'shade': ['#6a3a1c', '#8f5428', '#b6713a'], 'light': '#d08e52', 'spec': '#f0c493'},
    'silver': {'outline': '#2c3642', 'shade': ['#8c98a6', '#bec8d2', '#e8eef3'], 'light': '#f4f8fb', 'spec': '#ffffff'},
    'gold':   {'outline': '#5c340e', 'shade': ['#ba781e', '#e8aa2e', '#fad258'], 'light': '#ffe58a', 'spec': '#fff8d0'},
}
TIERS = ('bronze', 'silver', 'gold')

# Emblem: flat yukkuri head silhouette 6x4, engraved (dark) with a 1px lit emboss edge; eyes show the face metal.
EMBLEM = [
    '.XXXX.',
    'XeXXeX',
    'XXXXXX',
    '.XXXX.',
]
SIZE = 16
GROOVE = {'silver': '#667383'}  # silver inner groove between the two rims (named, locked with the palette)

def _radius(tier, a):
    if tier == 'gold':  # scalloped rim: 8 teeth reaching the full 16px, notches down to the 14px disc
        return 7.95 if math.cos(8 * a) > .2 else 6.95
    return 7.05 if tier == 'bronze' else 7.95  # bronze is a smaller, plain 14px disc

def badge(tier):
    p = PALETTES[tier]
    im = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    info = {}
    for y in range(SIZE):
        for x in range(SIZE):
            dx, dy = x + .5 - 8, y + .5 - 8
            r = math.hypot(dx, dy)
            info[x, y] = (r <= _radius(tier, math.atan2(dy, dx)), r, dx, dy)
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
        im.putpixel((x, y), _rgba(p['outline']) if ch == 'X' else _rgba(p['shade'][1]))
    for xy in SPEC[tier]:
        im.putpixel(xy, _rgba(p['spec']))
    return im

SPEC = {'bronze': ((3, 3), (4, 2)), 'silver': ((3, 2), (2, 3)), 'gold': ((3, 3), (4, 2))}

def _rgba(h):
    h = h.lstrip('#'); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)

def badges():
    return {t: badge(t) for t in TIERS}

if __name__ == '__main__':
    import sys
    out = sys.argv[1] if len(sys.argv) > 1 else '.'
    for t, im in badges().items():
        im.save(f'{out}/{t}.png')
