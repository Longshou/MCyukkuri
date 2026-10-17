"""Oil-grain bags: one product line, four silhouettes (kraft sack, mint pouch, navy gusset bag, chili pouch).

Labels sit on the -Z and +Z faces from one 11x15 texel decal (2 texels per unit): a big-shape face icon and stars.
"""
import math
from kit import Prop, Band, colour, fill, paint, rrect_points

C = lambda ramp, step: (ramp, step)
LABEL_AT = (16, 0)
LABEL = (11, 15)          # default width; wider labels pass width=
STAR = ['..x..',
        'xxxxx',
        '.xxx.',
        '.x.x.',
        'x...x']
CHILI = ['...gg....',
         '...grr...',
         '..rrrrr..',
         '..rHrrr..',
         '..rHrrr..',
         '...rrrr..',
         '...rrrr..',
         '....rrr..',
         '....rr...',
         '.....r...']


def head(img, x0, y0, ribbon=C('chili', 2)):
    """Head-only manju icon, 9x8 texels: round head, dark fringe, two eyes, small mouth, a bow at the upper left."""
    inside = lambda i, j: ((i - 4) / 4.6) ** 2 + ((j - 3.5) / 4.1) ** 2 <= 1
    for j in range(8):
        for i in range(9):
            if not inside(i, j):
                continue
            edge = any(not inside(i + a, j + b) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                spec = C('wood', 1)
            elif j <= 2:
                spec = C('wood', 0)
            else:
                spec = C('straw', 2)
            img.putpixel((x0 + i, y0 + j), colour(*spec) + (255,))
    for (i, j, spec) in ((2, 4, C('wood', 0)), (2, 5, C('wood', 0)), (6, 4, C('wood', 0)), (6, 5, C('wood', 0)),
                         (4, 6, C('berry', 1)), (3, 6, C('berry', 1)), (5, 6, C('berry', 1)), (1, 6, C('berry', 2)), (7, 6, C('berry', 2))):
        img.putpixel((x0 + i, y0 + j), colour(*spec) + (255,))
    for (i, j, spec) in ((0, 0, ribbon), (1, 0, ribbon), (2, 0, ribbon), (0, 1, ribbon), (1, 1, C('chili', 0)), (2, 1, ribbon), (3, 1, ribbon)):
        img.putpixel((x0 + i, y0 + j), colour(*spec) + (255,))


def mini_head(img, x0, y0):
    paint(img, x0, y0, ['rh.hh', 'hhhhh', 'fefef', 'ffmff', '.fff.'],
          {'r': C('chili', 2), 'h': C('wood', 0), 'f': C('straw', 2), 'e': C('wood', 0), 'm': C('berry', 1)})


def label_art(p, ground, stars=0, star_spec=None, chili=False, width=11, sticker=False):
    x0, y0 = LABEL_AT
    fill(p.img, x0, y0, width, LABEL[1], ground)
    if chili:
        paint(p.img, x0, y0 + 1, CHILI, {'g': C('leaf', 1), 'r': C('chili', 2), 'H': C('chili', 3)})
        mini_head(p.img, x0 + 6, y0 + 8)
        return
    head(p.img, x0 + (width - 9) // 2, y0 + 1)
    if sticker:
        paint(p.img, x0 + width - 4, y0 + 1, ['.gg.', 'gGGg', 'gGGg', '.gg.'], {'g': C('gold', 1), 'G': C('gold', 3)})
    gap = 1
    total = stars * 5 + max(0, stars - 1) * gap
    sx = x0 + (width - total) // 2
    for s in range(stars):
        paint(p.img, sx + s * (5 + gap), y0 + 10, STAR, {'x': star_spec})


def label_quads(p, part, hx, y0, y1, z, width=11):
    """Sticker quads on both thin faces; the same texels read left to right from either side."""
    ox, oy = LABEL_AT
    w, h = width, LABEL[1]
    uv = [(ox, oy + h), (ox + w, oy + h), (ox + w, oy), (ox, oy)]
    p.quad(part, [[hx, y0, -z], [-hx, y0, -z], [-hx, y1, -z], [hx, y1, -z]], None, uvs=uv, normal=[0, 0, -1])
    p.quad(part, [[-hx, y0, z], [hx, y0, z], [hx, y1, z], [-hx, y1, z]], None, uvs=uv, normal=[0, 0, 1])


def loops(rows, notch=0.0):
    out = []
    for (y, hx, hz, c) in rows:
        pts = rrect_points(hx, hz, c)
        if notch:
            pts = pts[:1] + [(hx - notch, 0)] + pts[1:5] + [(-(hx - notch), 0)] + pts[5:]
        out.append([[x, y, z] for (x, z) in pts])
    return out


def medallion(p, part, cx, cy, z, sgn, r, top, edge):
    ring = lambda zz: [[cx + r * math.cos(math.tau * j / 8), cy + r * math.sin(math.tau * j / 8), zz] for j in range(8)]
    p.skin(part, [ring(z), ring(z + sgn * .5)], Band(lambda i, j, c: edge), smooth='flat', caps=(False, True), grid=.25, auto_orient=True)


def oil_grain_budget():
    """Budget: kraft paper sack with a rolled top and one stamped star."""
    p = Prop('oil_grain_budget', wide=True)
    rows = [(0, 3.5, 1.5, .5), (.5, 4, 2, .5), (9, 4, 2, .5), (9.5, 3.5, 1.5, .5)]
    body = p.part('kraft_sack')
    p.skin(body, loops(rows), Band(lambda i, j, c: C('cardboard', 0) if i == 0 else C('cardboard', 1) if j in (2, 7) else C('cardboard', 2) if j in (0, 1, 4, 5) else C('cardboard', 1)),
           smooth='along', caps=(True, True), grid=.5)
    roll = p.part('rolled_top')
    p.rod(roll, [-3.5, 10.5, 0], [3.5, 10.5, 0], 1.5, 1.5, seg=8,
          spec=Band(lambda i, j, c: C('cardboard', 3) if j == 2 else C('cardboard', 2) if j in (1, 3) else C('cardboard', 1)),
          caps=(True, True), grid=.5, phase=math.pi / 8)
    fold = p.part('fold_ends')
    for x in (-3.75, 3.75):
        p.box(fold, [x - .25, 9.5, -1.5], [x + .25, 10, 1.5], C('cardboard', 0), grid=.25)
    label_art(p, C('cardboard', 3), 1, C('chili', 1))
    label_quads(p, p.part('label'), 2.75, 1.5, 9, 2 + .125)
    return p


def pouch(p, ramp, tone, stars, star_spec, chili=False, top_tone=None):
    rows = [(0, 3, 1.5, .5), (.5, 3.5, 2, .75), (8, 3.5, 2, .75), (10, 3.5, .75, .5), (10.5, 3.5, .5, .25), (11.5, 3.5, .5, .25)]
    body = p.part('stand_up_pouch')
    p.skin(body, loops(rows), Band(lambda i, j, c: C(ramp, max(0, tone - 2)) if i == 0 else C(ramp, tone if i < 3 or top_tone is None else top_tone)),
           smooth='both', caps=(True, True), grid=.5)
    seal = p.part('seal_teeth')
    for k in range(7):
        p.box(seal, [-3.5 + k, 10.5, -.75], [-3 + k, 11, .75], C(ramp, 0), grid=.5)
    label_art(p, C('paper', 2) if chili else C('paper', 3), stars, star_spec, chili=chili)
    label_quads(p, p.part('label'), 2.4, 1.5, 8, 2 + .125)


def oil_grain_regular():
    """Regular: mint stand-up pouch with a cream label window and two stars."""
    p = Prop('oil_grain_regular', wide=True)
    pouch(p, 'mint', 3, 2, C('gold', 1))
    return p


def spicy_grain():
    """Spicy grain: the regular pouch shape in chili red with a chili icon."""
    p = Prop('spicy_grain', wide=True)
    pouch(p, 'chili', 1, 0, None, chili=True, top_tone=0)
    return p


def oil_grain_premium():
    """Premium: tall gusseted navy bag, flat heat-sealed top edge, gold band, 3 gold stars and a small gold seal sticker."""
    p = Prop('oil_grain_premium', wide=True)
    body = p.part('gusset_bag')
    rows = [(0, 4.25, 1.5, .5), (.5, 4.75, 1.5, .5), (11.5, 4.75, 1.5, .5), (12.5, 4.75, .5, .25), (14, 4.75, .5, .25)]
    p.skin(body, loops(rows, notch=.75), Band(lambda i, j, c: C('navy', 0) if i < 3 else C('navy', 1)), smooth='along', caps=(True, True), grid=.25)
    crimp = p.part('heat_seal_lines')
    for y in (12.75, 13.25, 13.75):
        p.box(crimp, [-4.5, y, -.75], [4.5, y + .25, .75], C('navy', 0), grid=.25)
    band = p.part('gold_band')
    p.box(band, [-5, 9.5, -1.75], [5, 10.5, 1.75], {'*': C('gold', 1), 'top': C('gold', 2), 'bottom': C('gold', 0)}, grid=.25)
    label_art(p, C('navy', 1), 3, C('gold', 3), width=17, sticker=True)
    label_quads(p, p.part('label'), 4.25, 1.5, 9, 1.5 + .125, width=17)
    return p
