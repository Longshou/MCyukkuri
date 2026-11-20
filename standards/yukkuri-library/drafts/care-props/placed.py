"""The placed pet beds in their dye-selected styles: the one-block pet bed in three grades (straw, cushion with seven
styles, velvet) with its shapes fitted against blocks, and the 2x2 woven family nest in four styles.

Rounded low-poly solids with painted 16 px-density textures: revolved circles or rounded rectangles with 24-40 segments
(shapes.py), no stepped cells. Each style is a set of painted regions on the same geometry, so one item carries every
style and the block keeps the style chosen with a dye (life/CareStyles.java holds the dye table). The other placed
goods (bowl, fills, waterer, toilet, carriers) are block-style props in block-props/devices.py.

Source evidence (wiki): an open soft-pad bed (EH32 p.5-9), a round raised-rim cushion (EH23 p.2). Colours, patterns,
the three grades and the woven 2x2 basket are project design. Palettes are local hue-shifted ramps (dark -> light);
nothing copied. Footprints follow life/CareBlock; the nests get a collision shell generated from their visible top
surface (collision()).
"""
import math
from shapes import (Build, Outline, SideOutline, revolve, cap, grid, canvas, put, grain, stamp, top_view, rng, smoothstep,
                    heightmap, collision_boxes)

# ---- ramps (dark -> light) ------------------------------------------------------------------------------------------
CREAM = ['#8c7a5c', '#b6a481', '#d7c8a6', '#ece2c7', '#f9f4e4']
WHITE = ['#8e95a3', '#b8bfcc', '#dde2ea', '#eff2f6', '#ffffff']
MINT = ['#2d6656', '#438a76', '#5fab94', '#86c9b2', '#b6e6d4']
PINK = ['#8c3b57', '#bf5b7c', '#e086a1', '#f2b0c2', '#fcd9e2']
RED = ['#6b1824', '#9b2432', '#c83a45', '#e46a62', '#f5a497']
SKY = ['#2a5a8a', '#3f7fb5', '#62a6d9', '#94c9ed', '#c9e6f9']
NAVY = ['#121a38', '#1d2952', '#2b3c73', '#435c98', '#6b86bd']
LEMON = ['#86650f', '#bf931c', '#e6bd34', '#f5da62', '#fff1a6']
BROWN = ['#3d2313', '#5a371f', '#7c5131', '#9f7149', '#c39a6d']
LAV = ['#47325f', '#664d87', '#8870aa', '#aa97ca', '#d1c5e6']
LILAC = ['#7c6a92', '#a291b8', '#c3b6d6', '#dcd3ea', '#f1ecf8']
TAN = ['#5d4120', '#86602f', '#ad8343', '#cba45f', '#e4c788']


# =====================================================================================================================
# Style tables (keep in step with life/CareStyles.java). '' is the default style carried by the plain mesh name.
STYLES = {
    'yukkuri_nest': ['', 'gingham', 'polka', 'floral', 'lavender', 'teddy', 'stars'],
    'large_yukkuri_nest': ['', 'wicker', 'mint', 'lavender'],
}
STAR = ['.x.', 'xxx', '.x.']


def model_name(base, style):
    return f'{base}_{style}' if style else base


# =====================================================================================================================
# Pet cushion nest (one block, overhanging to 26.6 across): a thick round bolster 5.25 high (EH23's round raised-rim
# cushion) with piping on the outer shoulder, round a flat quilted pad (y 1.6-1.8, radius 10.1) big enough for one adult
# up to 1.2x at full term: a level pad 23 square (the block and the bolster's inner slope, collision()) takes the square
# body box of a 1.2x adult whose term belly makes it 22.45 wide, and the bolster's steep inner side lies 11.1 out at body
# height, so every adult sits down on the pad with the bolster below its middle (a full-term 1.2x adult presses about 2
# into the bolster at the diagonals; NestResting.RIM_PRESS). Across the north (-z) entrance the bolster dips to a low
# rolled lip (U notch).
NEST_SEGS = 32
NEST_ROLL = [(12.8, 0.0, 0), (13.2, 1.0, 1), (13.3, 2.2, 2), (13.1, 3.4, 3), (12.75, 4.4, 4), (12.3, 5.05, 5), (11.85, 5.25, 6),
             (11.5, 4.85, 7), (11.3, 4.0, 8), (11.1, 3.0, 9), (10.75, 2.1, 10), (10.1, 1.6, 11)]
NEST_CUSHION = [(10.1, 1.6), (7.4, 1.7), (3.7, 1.78)]
NEST_CREST = 11.85
NEST_OUTER = 13.3          # outer radius of the round roll (NEST_ROLL's widest row)
NEST_PAD = 1.75            # collision top of the level pad over the whole block square
NEST_LEVEL = 11.5          # the level pad reaches this far on a round side (block and the roll's inner slope)

# Fitted against blocks (life/PetNestBlock): a side whose neighbouring block reaches into the overhang band is flattened so
# nothing pokes into that block. The roll keeps its height and order of rows but runs straight along the block face (outer
# edge at NEST_FLAT, the block face) with NEST_KEEP of its depth, pressed thin like a soft roll pushed against a wall (so an
# adult still lies between two walls of a one-block alcove);
# corners between a flat and a round side round off with radius 8, corners between two flat sides with radius 3. Masks are in
# the nest's own frame, entrance to -z: bit 1 north (entrance), 2 east, 4 south (back), 8 west; the block rotates them.
NEST_FLAT = 8.0
NEST_KEEP = .3
NEST_CORNER = {0: NEST_OUTER, 1: 8.0, 2: 3.0}


def nest_walls(mask):
    return {side: bool(mask >> i & 1) for i, side in enumerate('nesw')}


def nest_outline(mask):
    walled = nest_walls(mask)
    extents = {side: NEST_FLAT if walled[side] else NEST_OUTER for side in 'nesw'}

    def corner(sx, sz):
        wx, wz = walled['e' if sx > 0 else 'w'], walled['s' if sz > 0 else 'n']
        return NEST_CORNER[wx + wz], NEST_KEEP if wx else 1.0, NEST_KEEP if wz else 1.0
    return SideOutline(extents, corner, arc=NEST_SEGS // 4)


def nest_notch(x, z, y, o, floor=1.6, lip=2.8, top=5.25):
    """U-shaped entrance: within about 35 degrees of north the bolster sinks to a low rolled lip; the outer half (rows from
    the crest outward) scales toward the ground, the inner half toward the pad, meeting at the crest."""
    r = math.hypot(x, z)
    c = max(0.0, -z / r) if r > 1e-6 else 0.0
    w = smoothstep(.74, .89, c)
    if o >= NEST_CREST - NEST_OUTER:
        return y * (1 - (1 - lip / top) * w)
    return floor + (y - floor) * (1 - (1 - (lip - floor) / (top - floor)) * w) if y > floor else y


NESTS = {
    #           roll ramp, cushion ramp
    '':         (RED, CREAM),
    'gingham':  (SKY, WHITE),
    'polka':    (PINK, CREAM),
    'floral':   (MINT, WHITE),
    'lavender': (LAV, LILAC),
    'teddy':    (BROWN, CREAM),
    'stars':    (NAVY, NAVY),
}
PIPING = {'': CREAM[4], 'gingham': SKY[1], 'polka': WHITE[4], 'floral': WHITE[4], 'lavender': LILAC[4], 'teddy': CREAM[3],
          'stars': LEMON[3]}
ROLL_SHADE = [0, 2, 2, 3, None, 3, 4, 3, 2, 1, 0]     # contact, outer side, piping, crest (lit), inner side, tuck under


def fabric_band(style, w, key):
    """Cushion roll cover, 11 rows from the outer foot over the crest to the tuck at the pad: four steps of the fabric,
    darkest at the ground and the tuck, lit along the crest, a piping cord on the outer shoulder, tufting dimples."""
    ramp, _ = NESTS[style]
    h = len(ROLL_SHADE)
    img = canvas(w, h)
    for y in range(h):
        for x in range(w):
            put(img, x, y, ramp[ROLL_SHADE[y] if ROLL_SHADE[y] is not None else 3])
    body = [y for y in range(1, h - 1) if y != 4]
    if style == '':                          # strawberry: sparse two-texel seeds, soft yellow
        for i, x in enumerate(range(0, w, 6)):
            for y in ((2, 6), (8, 5))[i % 2]:
                put(img, x + (i % 2) * 3, y, LEMON[2])
                put(img, x + (i % 2) * 3, y + 1, RED[1])
    elif style == 'gingham':                 # blue gingham: two-texel checks, darker where the bands cross
        for y in body:
            s = ROLL_SHADE[y]
            for x in range(w):
                a, c = (x // 2) % 2, (y // 2) % 2
                put(img, x, y, SKY[max(1, s)] if a and c else SKY[min(4, s + 1)] if a or c else WHITE[max(1, s)])
    elif style == 'polka':
        for y in body:
            for x in range(w):
                if (x + 3 * ((y // 2) % 2)) % 6 == 0 and y % 2 == 1:
                    put(img, x, y, WHITE[4] if ROLL_SHADE[y] >= 3 else WHITE[2])
    elif style == 'floral':                  # small white flowers with lemon centres on mint
        for i, x in enumerate(range(0, w, 6)):
            y = (2, 6, 8)[i % 3]
            stamp(img, x, y - 1, ['.f.', 'fcf', '.f.'], {'f': WHITE[4] if y < 8 else WHITE[2], 'c': LEMON[3]})
    elif style == 'lavender':                # diamond quilting stitches
        for y in body:
            s = ROLL_SHADE[y]
            for x in range(w):
                if (x + y) % 6 == 0 or (x - y) % 6 == 0:
                    put(img, x, y, LAV[max(0, s - 1)])
    elif style == 'teddy':                   # plush: soft tufts
        grain(img, BROWN, key, .4, up=.55, rows=body)
    elif style == 'stars':                   # navy velvet with a few small stars and dim twinkles
        for i, x in enumerate(range(0, w, 12)):
            stamp(img, x + 2, (2, 6)[i % 2] - 1, STAR, {'x': LEMON[3]})
            put(img, x + 8, (7, 2)[i % 2], NAVY[4])
    if style not in ('teddy', 'lavender', 'gingham'):     # tufting dimples: a dark stitch with a lit puff above it
        for x in range(4, w, 8):
            for y in (3, 7):
                put(img, x, y, ramp[1])
                put(img, x, y - 1, ramp[4])
    grain(img, ramp, key + 'g', .03, rows=body)
    for x in range(w):                       # piping cord on the outer shoulder
        put(img, x, 4, PIPING[style] if x % 3 else ramp[1])
    return img


def cushion_top(style, size=22, radius=10.1):
    """Quilted pad seen from above: stitched diamonds with lit puffs, a dark seam ring where the pad meets the roll."""
    _, ramp = NESTS[style]
    seam = {'': PINK[2], 'gingham': SKY[2], 'polka': PINK[2], 'floral': MINT[2], 'lavender': LAV[2], 'teddy': BROWN[2], 'stars': LEMON[2]}[style]

    def paint(x, z, r):
        if r > radius + .6:
            return None
        u, v = math.floor(x), math.floor(z)
        if r > radius - .7:
            return ramp[1]
        if r > radius - 1.4:
            return seam if (u + v) % 2 else ramp[2]
        if (u + v) % 6 == 3 or (u - v) % 6 == 3:
            return seam if (u * 3 + v) % 4 == 0 else ramp[2]
        if (u + v) % 6 == 0 and (u - v) % 6 == 0:
            return ramp[4]
        return ramp[3]
    return grain(top_view(size, paint), ramp, 'cushion' + style, .07)


# ---- three grades of the one-block pet bed (life/PetNestBlock): one geometry, three looks ------------------------------
# Grade 1 straw nest: a coiled straw roll bound with straw wraps round a pad of loose hay (cheap, sold by the pet shop).
# Grade 2 cushion nest: the quilted cotton cushion above with its seven dye styles.
# Grade 3 velvet nest: wine velvet roll with a gold cord on the shoulder and a lace frill at its foot round a buttoned ivory
# satin pad. Every look paints the same regions in the same order (roll band 72 x 11, pad 22 x 22, underside 4 x 4), so a
# fitted shape (pet_nest_fit_<mask>) draws with any look's texture.
WINE = ['#3a0d1f', '#5c1530', '#7e2042', '#a23458', '#c7577a']
GOLD = ['#6b4a12', '#9c7220', '#c99a32', '#e8c25a', '#f8e49a']
IVORY = ['#9c8f78', '#c8bba0', '#e6dcc4', '#f4eedd', '#fffaf0']
RUSH = ['#5a4a28', '#7f6b3f', '#a38f5d', '#c2ae7c', '#ddcda0']       # dry straw, duller than the woven basket's STRAW


def straw_band(w=72, key='rollstraw'):
    """Coiled straw: strands run along the roll in the roll's shading rows, a binding wrap every twelve texels, a twisted
    straw cord on the outer shoulder."""
    h = len(ROLL_SHADE)
    img = canvas(w, h)
    r = rng(key)
    for y in range(h):
        s = ROLL_SHADE[y] if ROLL_SHADE[y] is not None else 3
        for x in range(w):
            put(img, x, y, RUSH[s])
        if y in (0, 4, h - 1):
            continue
        x = 0
        while x < w:                      # strand runs one step lighter or darker
            run = r.randint(3, 7)
            step = r.choice((-1, 1, 0))
            for k in range(run):
                if step:
                    put(img, x + k, y, RUSH[max(0, min(4, s + step))])
            x += run
    for x in range(0, w, 12):              # binding wraps: a darker band with a lit edge
        for y in range(1, h - 1):
            if y == 4:
                continue
            s = ROLL_SHADE[y] if ROLL_SHADE[y] is not None else 3
            put(img, x, y, RUSH[max(0, s - 2)])
            put(img, x + 1, y, RUSH[min(4, s + 1)])
    for x in range(w):                    # twisted cord on the shoulder
        put(img, x, 4, RUSH[3] if x % 3 else RUSH[1])
    return img


def straw_top(size=22, radius=10.1):
    """Loose hay pad from above: short strands in every direction over a straw-brown bed, a dark tuck ring at the roll."""
    r = rng('hay-pad')
    strands = []
    for _ in range(45):
        x, z = r.uniform(-radius, radius), r.uniform(-radius, radius)
        a = r.uniform(0, math.pi)
        strands.append((x, z, math.cos(a), math.sin(a), r.uniform(1.5, 3.0), r.choice((1, 3, 4))))

    def paint(x, z, rr):
        if rr > radius + .6:
            return None
        if rr > radius - .7:
            return RUSH[0]
        for sx, sz, dx, dz, length, shade in strands:
            px, pz = x - sx, z - sz
            t = px * dx + pz * dz
            if 0 <= t <= length and abs(px * dz - pz * dx) < .5:
                return RUSH[shade]
        return RUSH[1] if (math.floor(x) * 7 + math.floor(z) * 3) % 5 == 0 else RUSH[2]
    return top_view(size, paint)


def velvet_band(w=72, key='rollvelvet'):
    """Wine velvet roll: soft sheen streaks along the crest, a gold cord on the outer shoulder, a white lace frill at the foot."""
    h = len(ROLL_SHADE)
    img = canvas(w, h)
    for y in range(h):
        s = ROLL_SHADE[y] if ROLL_SHADE[y] is not None else 3
        for x in range(w):
            put(img, x, y, WINE[s])
    grain(img, WINE, key, .05, rows=[5, 6, 7, 8])
    for x in range(w):
        put(img, x, 4, GOLD[3] if x % 3 else GOLD[1])           # gold cord
        put(img, x, 2, IVORY[3])                                # lace band ...
        put(img, x, 1, IVORY[2] if x % 4 in (1, 2) else WINE[1])  # ... scalloped at its lower edge
        if x % 4 == 0:
            put(img, x, 2, IVORY[1])
    for x in range(3, w, 12):                                   # sheen on the crest
        for k in range(4):
            put(img, x + k, 6, WINE[4])
    return img


def velvet_top(size=22, radius=10.1):
    """Buttoned ivory satin pad: diamond tufting seams, a gold button at every crossing, a gold-and-wine edge."""
    def paint(x, z, r):
        if r > radius + .6:
            return None
        u, v = math.floor(x), math.floor(z)
        if r > radius - .7:
            return WINE[1]
        if r > radius - 1.4:
            return GOLD[2] if (u + v) % 2 else GOLD[3]
        if (u + v) % 6 == 0 and (u - v) % 6 == 0:
            return GOLD[3]
        if (u + v) % 6 == 3 or (u - v) % 6 == 3:
            return IVORY[1]
        if (u + v) % 6 in (1, 5) and (u - v) % 6 in (1, 5):
            return IVORY[4]
        return IVORY[3]
    return grain(top_view(size, paint), IVORY, 'velvetpad', .05)


# Looks: name -> (roll band, pad, underside colour). The cushion's dye styles keep their mesh names (yukkuri_nest[_style]).
def nest_look(look):
    if look == 'straw_nest':
        return straw_band(), straw_top(), RUSH[0]
    if look == 'velvet_nest':
        return velvet_band(), velvet_top(), WINE[0]
    style = look[len('yukkuri_nest_'):] if look.startswith('yukkuri_nest_') else ''
    return fabric_band(style, 72, 'roll' + style), cushion_top(style), NESTS[style][0][0]


def pet_nest(look='yukkuri_nest', mask=0, name=None):
    """One-block pet bed in `look`, with the sides in `mask` flattened against neighbouring blocks (nest frame, entrance -z)."""
    b = Build(name or look)
    o = nest_outline(mask)
    part = b.part('nest')
    roll, pad, under = nest_look(look)
    profile = [(r - NEST_OUTER, y, v) for r, y, v in NEST_ROLL]
    revolve(b, part, o, profile, 'roll', roll, smooth=range(1, 11), ymap=nest_notch, offset_ymap=True, ref=12.5 - NEST_OUTER)
    nest_pad(b, part, o, pad)
    cap(b, part, o, [(12.8 - NEST_OUTER, 0.0)], 'under', canvas(4, 4, under), down=True, centre=0.0)
    return b.finish()


def nest_pad(b, part, outline, img, centre=1.8):
    """The quilted pad: its edge ring where the roll tucks in, inner rings shrunk toward the middle (a faint dome)."""
    w, h = img.size
    origin = b.region('cushion', img)
    edge = [outline.at(s, NEST_CUSHION[0][0] - NEST_OUTER) for s in outline.samples]
    P = [[[x * r / NEST_CUSHION[0][0], y, z * r / NEST_CUSHION[0][0]] for x, z in edge] for r, y in NEST_CUSHION]
    P.append([[0.0, centre, 0.0] for _ in edge])
    UV = [[(p[0] + w / 2, p[2] + h / 2) for p in row] + [(row[0][0] + w / 2, row[0][2] + h / 2)] for row in P]
    grid(b, part, P, UV, origin, True, lambda i, j: [0, 1, 0], smooth_rows=set(range(1, len(P) - 1)))


def yukkuri_nest(style=''):
    return pet_nest(model_name('yukkuri_nest', style))


def straw_nest():
    return pet_nest('straw_nest')


def velvet_nest():
    return pet_nest('velvet_nest')


def fitted_nests():
    """Shapes flattened against blocks, masks 1..15 (nest_walls bits in the nest frame). Geometry only: the renderer draws
    them with the placed bed's own look (its texture)."""
    return {f'pet_nest_fit_{m}': (lambda m=m: pet_nest('yukkuri_nest', m, f'pet_nest_fit_{m}')) for m in range(1, 16)}


# =====================================================================================================================
# 2x2 woven family nest (large_nests.py exports it): a rounded-square basket 32 across (two blocks, centred on its block)
# with a wicker wall 1.1 thick and 6 high at the back and sides, braided top, darker inside; the +z front opens down to
# the floor for |x| < 6 and climbs back to full height by |x| 12. Inside lies a quilted pad (29.8 across) with a stitched,
# rolled hem: two adults up to 1.1x lie side by side with four newborns, the wall below their middles.
# Collision follows the visible wall and pad (see collision()).
LARGE = Outline(16.0, 16.0, 7.0, arc=6, step=4.0)
LARGE_WALL = [(0.0, 0.0, 0), (0.0, 1.3, 1.5), (0.0, 2.6, 3), (0.0, 3.9, 4.5), (0.0, 5.2, 6), (-.2, 5.75, 6.6),
              (-.65, 6.0, 7.3), (-1.0, 5.75, 8), (-1.1, 5.2, 8.7), (-1.1, 3.1, 9.8), (-1.1, 1.0, 11)]
LARGE_HEM = [(-1.25, 1.0, 0), (-1.4, 1.45, 1), (-1.75, 1.72, 2), (-2.3, 1.8, 3)]
LARGE_PAD = [(-2.3, 1.8), (-3.5, 1.76), (-6.0, 1.72)]


def large_front(x, z, y, floor=1.0):
    if z <= 0 or y <= floor:
        return y
    return floor + (y - floor) * smoothstep(6.0, 12.0, abs(x))


LARGES = {
    #           weave ramp, braid ramp, pad ramp, seam
    '':         (RED, PINK, CREAM, PINK[2]),
    'wicker':   (TAN, TAN, CREAM, TAN[2]),
    'mint':     (MINT, WHITE, WHITE, MINT[2]),
    'lavender': (LAV, LILAC, LILAC, LAV[2]),
}


def weave_band(style, w):
    """Wicker wall, 11 rows: 0 contact (darkest), 1-5 outer over-under weave (three lit texels and a shaded crossing,
    offset by two each row), 6-8 braided rim (slanted strokes, lit on top), 9-10 inside one step darker."""
    weave, braid, _, _ = LARGES[style]
    img = canvas(w, 11, weave[2])
    for y in range(11):
        for x in range(w):
            if 1 <= y <= 5 or y >= 9:
                k = (x + 2 * y) % 4
                v = weave[1] if k == 3 else weave[3] if k == 0 else weave[2]
                if y >= 9:
                    v = weave[0] if k == 3 else weave[2] if k == 0 else weave[1]
                put(img, x, y, v)
            elif y in (6, 7, 8):
                k = (x + y) % 3
                put(img, x, y, braid[4 if y == 7 else 3] if k == 0 else braid[2] if k == 1 else braid[1])
    for x in range(w):
        put(img, x, 0, weave[0])
        put(img, x, 10, weave[0])
    return grain(img, weave, 'wicker' + style, .07, rows=range(1, 6))


def hem_band(style, w):
    _, _, ramp, seam = LARGES[style]
    img = canvas(w, 3, ramp[2])
    for x in range(w):
        put(img, x, 0, ramp[0])
        put(img, x, 1, ramp[2])
        put(img, x, 2, seam if x % 2 else ramp[3])
    return img


def large_floor(style, size=32):
    """Quilted pad top, low contrast so the basket reads first."""
    _, _, ramp, seam = LARGES[style]

    def paint(x, z, r):
        u, v = math.floor(x), math.floor(z)
        if style == 'mint':                   # soft mint gingham
            a, c = (u // 2) % 2, (v // 2) % 2
            return MINT[3] if a and c else MINT[4] if a or c else WHITE[4]
        if (u + v) % 8 == 0 or (u - v) % 8 == 0:
            return ramp[2]
        if (u + v) % 8 == 4 and (u - v) % 8 == 4:
            return ramp[4]
        return ramp[3]
    return grain(top_view(size, paint), ramp, 'largefloor' + style, .07)


def large_yukkuri_nest(style=''):
    b = Build(model_name('large_yukkuri_nest', style))
    part = b.part('large_nest')
    revolve(b, part, LARGE, LARGE_WALL, 'wall', weave_band(style, 120), smooth=(5, 6, 7, 8), ymap=large_front, ref=-.6)
    revolve(b, part, LARGE, LARGE_HEM, 'hem', hem_band(style, 108), smooth=(1, 2), ref=-1.7)
    cap(b, part, LARGE, LARGE_PAD, 'pad', large_floor(style), centre=1.7)
    cap(b, part, LARGE, [(-1.1, 1.0)], 'floor', canvas(4, 4, LARGES[style][2][1]), centre=1.0)
    cap(b, part, LARGE, [(0.0, 0.0)], 'under', canvas(4, 4, LARGES[style][0][1]), down=True, centre=0.0)
    return b.finish()


# =====================================================================================================================
FACTORIES = {'yukkuri_nest': yukkuri_nest, 'large_yukkuri_nest': large_yukkuri_nest}


def variants():
    """Non-default style meshes; shipped as attachments."""
    return {model_name(base, style): (lambda f=FACTORIES[base], s=style: f(s))
            for base, styles in STYLES.items() for style in styles[1:]}


# Collision shells that follow the visible nests (life/CareBlock reads them): top-surface heightmaps of the meshes over
# square cells, rounded up so a body rests on or just above the visible surface, merged into equal-height boxes.
# The one-block bed's level pad reaches NEST_LEVEL (11.5) on a round side: a 23 square on the round bed (the block and 3.5
# beyond each side, clipped to the outline grown by 0.45, radius 13.75 there, so no pad reaches past the visible roll at the
# diagonals): a body box resting anywhere inside it touches only the pad, never the roll, so an adult (to 1.2x at full term)
# sits down in the nest instead of hanging on the rim; the roll keeps its collision outside the pad (NestResting keeps
# resting bodies inside it). A flattened side keeps NEST_KEEP of that reach inside its flat roll (7.46 from the centre).
# Every grade has the same shape, so the shells are per fitted mask: pet_nest_<mask> (mask 0 = round).
NEST_SHELL = (13.5, .5, .5)                 # half extent, cell, step


def nest_level(mask):
    """Per side, how far the level pad reaches from the block centre (nest frame)."""
    walled = nest_walls(mask)
    return {side: NEST_FLAT - (NEST_OUTER - NEST_LEVEL) * NEST_KEEP if walled[side] else NEST_LEVEL for side in 'nesw'}


def nest_inside(mask, x, z, grow):
    """Whether (x, z) lies inside the fitted outline grown by `grow` (scaled like the roll on flattened sides)."""
    walled = nest_walls(mask)
    sx, sz = (1 if x >= 0 else -1), (1 if z >= 0 else -1)
    wx, wz = walled['e' if sx > 0 else 'w'], walled['s' if sz > 0 else 'n']
    radius = NEST_CORNER[wx + wz]
    ex, ez = (NEST_FLAT if wx else NEST_OUTER), (NEST_FLAT if wz else NEST_OUTER)
    qx, qz = sx * x - (ex - radius), sz * z - (ez - radius)
    d = math.hypot(max(qx, 0), max(qz, 0)) + min(max(qx, qz), 0) - radius
    return d <= grow * (NEST_KEEP if (wx if qx >= qz else wz) else 1.0)


def collision():
    out = {'format': 1, 'generator': 'standards/yukkuri-library/drafts/care-props/placed.py',
           'units': 'boxes [x0, z0, x1, z1, top] in 1/16 block from the footprint centre (block centre), bottom 0; '
                    'pet_nest_<mask>: the one-block beds, sides flattened per mask (nest frame, entrance -z; bit 1 n, 2 e, 4 s, 8 w)'}
    half, cell, step = NEST_SHELL
    for mask in range(16):
        hm = heightmap(pet_nest('yukkuri_nest', mask), half, cell, step=step)
        level = nest_level(mask)
        for k, row in enumerate(hm):
            for i in range(len(row)):
                x0, z0 = -half + i * cell, -half + k * cell
                if (-level['w'] <= x0 and x0 + cell <= level['e'] and -level['n'] <= z0 and z0 + cell <= level['s']
                        and nest_inside(mask, x0 + cell / 2, z0 + cell / 2, .45)):
                    row[i] = NEST_PAD
        boxes = collision_boxes(hm, half, cell)
        out[f'pet_nest_{mask}'] = {'cell': cell, 'top': max(b[4] for b in boxes), 'boxes': boxes}
    hm = heightmap(large_yukkuri_nest(), 16.0, .25, step=.25)
    boxes = collision_boxes(hm, 16.0, .25)
    out['large_yukkuri_nest'] = {'cell': .25, 'top': max(b[4] for b in boxes), 'boxes': boxes}
    return out
