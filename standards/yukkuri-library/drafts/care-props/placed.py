"""Placed pet-care goods in several dye-selected styles: food bowl (and its oil-grain / wheat / grass fills), pet cushion
nest, 2x2 woven family nest, sand toilet (clean / dirty), ceramic and covered toilets, and the droppings drawn in them.

Shapes follow the vanilla block-model idea of a few large clean solids with carefully painted 16 px-density textures
(cauldron, composter, flower pot, bed), but the round goods are round: revolved circles or rounded rectangles with
24-40 segments (shapes.py), no stepped cells. Each style is a set of painted regions on the same geometry, so one item
carries every style and the block keeps the style chosen with a dye (life/CareStyles.java holds the dye table).

Source evidence (wiki): a small shallow round floor bowl of kibble beside an open soft-pad bed (EH32 p.5-9), a round
pet bowl lettered with the pet's name and hearts next to a round raised-rim cushion (EH23 p.2), a rectangular toilet
for babies (EH34 p.17), sand flooring for small breeds (L32). Colours, patterns, the woven 2x2 basket, the lidded
toilet and the fills' look are project design. Palettes are local hue-shifted ramps (dark -> light); nothing copied.
Footprints follow life/CareBlock; the two pet nests get a collision shell generated from their visible top surface (collision()).
"""
import math
from shapes import (Build, Outline, CIRCLE, revolve, cap, box, sprite, canvas, put, get, grain, stamp, top_view,
                    rng, smoothstep, heightmap, collision_boxes)

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
TERRA = ['#5c2915', '#843f27', '#a85937', '#c77950', '#df9b72']
STEEL = ['#4b535e', '#707a86', '#98a2ad', '#c2cad2', '#e9eef3']
LAV = ['#47325f', '#664d87', '#8870aa', '#aa97ca', '#d1c5e6']
LILAC = ['#7c6a92', '#a291b8', '#c3b6d6', '#dcd3ea', '#f1ecf8']
TAN = ['#5d4120', '#86602f', '#ad8343', '#cba45f', '#e4c788']
GREEN = ['#2c4a1c', '#3f6a25', '#568a31', '#73a940', '#9bc75e']
CLAY = ['#4e3222', '#6c4a33', '#8b6446', '#a87f5c']
PELLET = ['#3f200d', '#6e3a14', '#a5602a', '#d18f45', '#efbf6e']
STRAW = ['#6b5420', '#957528', '#c19c37', '#dcbd52', '#f2df8a']
CLIP = ['#22401a', '#335c22', '#4a7a2c', '#67993a', '#8fbb55']
HAY = ['#6a5a26', '#93803a', '#b8a452', '#d4c274']
SAND = ['#a68c5e', '#c4ab7d', '#dac69a', '#ebdcb6', '#f7eecf']
STAIN = ['#6f5c3e', '#8a7553', '#a48f6c']
ANKO = ['#2e1a10', '#4a2c1b', '#6a4129', '#8a5a3a', '#ad7d57']


# =====================================================================================================================
# Style tables (keep in step with life/CareStyles.java). '' is the default style carried by the plain mesh name.
STYLES = {
    'food_bowl': ['', 'hearts', 'polka', 'sailor', 'paws', 'terracotta', 'steel'],
    'yukkuri_nest': ['', 'gingham', 'polka', 'floral', 'lavender', 'teddy', 'stars'],
    'large_yukkuri_nest': ['', 'wicker', 'mint', 'lavender'],
    'yukkuri_toilet': ['', 'pink', 'lemon', 'sky'],
    'ceramic_toilet': ['', 'polka', 'sailor'],
    'covered_toilet': ['', 'pink', 'navy'],
}


def model_name(base, style):
    return f'{base}_{style}' if style else base


# =====================================================================================================================
# Glazed ceramics (bowl and ceramic / covered toilet trays): one band runs from the floor edge up the inside wall, over the
# rim and down the outside. Rows: 0-2 inside wall (floor upward), 3 rim top, 4 rolled lip, 5-7 belly (pattern), 8 foot.
GLAZES = {
    #             belly  step  lip    inside foot   floor motif
    '':           (MINT, 2, CREAM, CREAM, CLAY, 'clover'),
    'hearts':     (WHITE, 3, RED, WHITE, CLAY, 'heart'),
    'polka':      (PINK, 2, WHITE, WHITE, CLAY, 'dots'),
    'sailor':     (WHITE, 3, NAVY, WHITE, NAVY, 'anchor'),
    'paws':       (LEMON, 2, BROWN, CREAM, BROWN, 'paw'),
    'terracotta': (TERRA, 2, CREAM, CREAM, TERRA, 'flower'),
    'steel':      (STEEL, 3, STEEL, STEEL, STEEL, 'brushed'),
    'pink':       (PINK, 2, WHITE, WHITE, CLAY, 'heart'),
    'navy':       (NAVY, 2, LEMON, WHITE, NAVY, 'star'),
}
GLAZE_ROWS = 9

HEART = ['x.x', 'xxx', '.x.']
PAW = ['x.x', '.x.', 'xxx']
STAR = ['.x.', 'xxx', '.x.']
MOTIFS = {      # 16 px floor prints (top view, -z at the top)
    'heart': (['.x.x.', 'xxxxx', 'xxxxx', '.xxx.', '..x..'], {'x': RED[3]}),
    'anchor': (['..x..', '.xxx.', '..x..', 'x.x.x', '.xxx.'], {'x': NAVY[3]}),
    'paw': (['..xx.xx..', '..xx.xx..', 'xx.....xx', 'xx.....xx', '...xxx...', '..xxxxx..', '..xxxxx..', '...xxx...'], {'x': BROWN[1]}),
    'clover': (['x.x', '.s.', 'x.x'], {'x': MINT[3], 's': MINT[2]}),
    'flower': (['.p.', 'pcp', '.p.'], {'p': TERRA[3], 'c': LEMON[3]}),
    'star': (['..x..', 'xxxxx', '.xxx.', '.x.x.'], {'x': LEMON[3]}),
}


def glaze_band(style, w, key):
    """Four steps per material: the rim top is the lit band, the inside wall just under the rim is shaded so the rim reads
    as an outline, the foot is the darkest step (contact line), belly and inside wall carry 8% one-step grain."""
    belly, step, lip, inside, foot, _ = GLAZES[style]
    rim = lip if lip is not belly else belly
    img = canvas(w, GLAZE_ROWS, belly[step])
    for x in range(w):
        put(img, x, 0, inside[2])
        put(img, x, 1, inside[3])
        put(img, x, 2, inside[1])
        put(img, x, 3, rim[4])
        put(img, x, 4, rim[3] if x % 7 else rim[4])
        put(img, x, 7, belly[max(0, step - 1)])
        put(img, x, 8, foot[0])
    if style == '':                          # classic: cream shoulder above a mint belly
        for x in range(w):
            put(img, x, 5, CREAM[3])
            put(img, x, 6, MINT[3] if x % 6 == 0 else MINT[2])
    elif style in ('hearts', 'pink'):        # hearts all round (EH23: a pet's bowl lettered with hearts)
        for x in range(0, w, 8):
            stamp(img, x + 1, 5, ['xx.xx', 'xxxxx', '.xxx.'], {'x': RED[2] if style == 'hearts' else WHITE[4]})
            put(img, x + 1, 5, RED[3] if style == 'hearts' else WHITE[4])
    elif style == 'polka':                   # white dots, staggered
        for x in range(w):
            for y, phase in ((5, 0), (6, 3), (7, 0)):
                if x % 6 == phase:
                    put(img, x, y, WHITE[4] if y < 7 else WHITE[3])
    elif style == 'sailor':                  # sky stripe between white bands under a navy lip
        for x in range(w):
            put(img, x, 6, SKY[2] if x % 8 else SKY[3])
    elif style == 'paws':                    # a brown dotted band (the paw print is on the floor)
        for x in range(w):
            put(img, x, 5, BROWN[2] if x % 2 else LEMON[3])
            if x % 4 == 2:
                put(img, x, 7, BROWN[3])
    elif style == 'terracotta':              # cream glaze runs down from the lip over bare clay
        r = rng(key + 'drip')
        for x in range(w):
            run = r.choice((1, 1, 1, 1, 2, 3)) if x % 3 else 1
            for y in range(5, 5 + run):
                put(img, x, y, CREAM[3] if y < 4 + run else CREAM[2])
    elif style == 'steel':                   # brushed steel: bright vertical streaks
        r = rng(key + 'st')
        for x in range(w):
            if r.random() < .25:
                for y in range(5, 7):
                    put(img, x, y, STEEL[4])
    elif style == 'navy':                    # pin-point yellow stars on navy
        for x in range(w):
            if x % 5 == 1:
                put(img, x, 5 + (x // 5) % 3, LEMON[4])
    grain(img, inside, key + 'in', .08, rows=range(0, 2))
    grain(img, belly, key, .08, rows=range(5, 8))
    if style == 'terracotta':
        grain(img, TERRA, key + 'clay', .1, rows=range(5, 8))
    return img


def glaze_floor(style, size=16, motif=True, radius=4.6):
    """Floor top view: inside glaze with 8% grain and a small print (bowls); the shaded contact is on the wall band."""
    inside, print_ = GLAZES[style][3], GLAZES[style][5]

    def paint(x, z, r):
        u, v = math.floor(x), math.floor(z)
        if motif and print_ == 'dots' and (u * 2 + v * 3) % 7 == 0 and (u + v) % 2 == 0:
            return PINK[3]
        if motif and print_ == 'brushed' and v % 4 == 0 and (u + v) % 5:
            return '#d4dbe1'
        return inside[3]
    img = grain(top_view(size, paint), inside, 'floor' + style, .08)
    if motif and print_ in MOTIFS:
        rows, legend = MOTIFS[print_]
        stamp(img, (size - len(rows[0]) + 1) // 2, (size - len(rows)) // 2, rows, legend, wrap=False)
    return img


# =====================================================================================================================
# Food bowl: 14 across, 3 high, interior floor at y 1 (fills start there). Round like the wiki bowls, with an upright
# outer wall so the pattern shows, a rolled lip and a broad foot.
BOWL_SEGS = 16
BOWL = [(6.1, 0.0, 9), (6.45, .4, 8), (6.6, 1.0, 7), (6.85, 2.3, 5), (7.0, 2.65, 4.5), (6.85, 3.0, 4), (5.9, 3.0, 3),
        (5.55, 2.0, 2), (5.1, 1.25, 1), (4.6, 1.0, 0)]
BOWL_FLOOR_Y = 1.0
BOWL_FLOOR_R = 4.6


def bowl_shell(b, style, key):
    o = CIRCLE(BOWL_SEGS)
    part = b.part('bowl')
    revolve(b, part, o, BOWL, 'glaze', glaze_band(style, 48, key), smooth=(2, 3, 4, 7, 8), ref=6.3, facet=True)
    cap(b, part, o, [(BOWL_FLOOR_R, BOWL_FLOOR_Y)], 'floor', glaze_floor(style), centre=BOWL_FLOOR_Y)
    cap(b, part, o, [(6.1, 0.0)], 'foot', canvas(4, 4, GLAZES[style][4][1]), down=True, centre=0.0)


def food_bowl(style=''):
    b = Build(model_name('food_bowl', style))
    bowl_shell(b, style, 'bowl' + style)
    return b.finish()


# ---- fills: a lumpy heap per portion inside the bowl ----------------------------------------------------------------
FILL_R = [2.2, 3.0, 3.8, 4.6, 5.4, 6.2, 7.2, 8.6]          # heap footprint radius after k portions
FILL_H = [.8, 1.05, 1.3, 1.55, 1.8, 2.05, 2.3, 2.6]          # heap height at the centre after k portions
FILL_CLIP = 6.0                                             # heaps end inside the glaze wall (between inner and outer)
FILL_RINGS = [1.0, .9, .75, .55, .3]


def heap_y(k, r, lump=0.0):
    R, H = FILL_R[k - 1], FILL_H[k - 1]
    return BOWL_FLOOR_Y + .03 + max(0.0, H * (1 - (r / R) ** 2)) + lump


def fill_heaps(b, kind, tile, lump=.12):
    """Portion k's heap encloses portion k-1's (radius and height both grow), so drawing units 1..k shows heap k."""
    o = CIRCLE(16)
    r_ = rng(kind)
    for k in range(1, 9):
        part = b.part(f'{kind}_u{k}', k)
        R = min(FILL_R[k - 1], FILL_CLIP)
        rings = []
        jit = {}
        for f in FILL_RINGS:
            r = R * f
            rings.append((r, heap_y(k, r)))
        def ymap(x, z, y, k=k):
            key = (round(x, 3), round(z, 3))
            if key not in jit:
                r = math.hypot(x, z)
                edge = r >= min(FILL_R[k - 1], FILL_CLIP) - 1e-6
                jit[key] = 0.0 if edge else r_.uniform(0, lump)        # only upward, below the .25 step between heaps
            return y + jit[key]
        cap(b, part, o, rings, kind, tile, ymap=ymap, centre=heap_y(k, 0) + r_.uniform(0, lump))


def kibble_tile():
    """Oil-grain kibble seen from above: separate round grains (3 x 3 with dark corners), lit top left and shaded bottom
    right, in staggered rows over dark gaps (the regular oil-grain icon's pellets at block scale)."""
    img = canvas(16, 16, PELLET[1])
    r = rng('kibble')
    grain(img, PELLET, 'kibblegaps', .3, up=0)
    for gy in range(0, 16, 3):
        for gx in range((gy // 3) % 2 * 2, 16, 4):
            t = 1 if r.random() < .3 else 0
            stamp(img, gx, gy, ['.a.', 'abc', '.c.'], {'a': PELLET[4 - t], 'b': PELLET[3 - t], 'c': PELLET[2 - t]})
    return img


def strands(img, ramps, key, count, length, dirs, tip=True):
    """Lit strands: a short line in a chosen direction, the far end one step lighter, a dark pixel at the root."""
    r = rng(key)
    for _ in range(count):
        ramp, tone = r.choice(ramps)
        x, y = r.randrange(16), r.randrange(16)
        dx, dy = r.choice(dirs)
        n = r.randint(*length)
        put(img, x - dx, y - dy, ramp[0])
        for i in range(n):
            put(img, x + i * dx, (y + i * dy) % 16, ramp[min(len(ramp) - 1, tone + (1 if tip and i == n - 1 else 0))])
    return img


def straw_tile():
    """Loose wheat: golden stalks crossing over a shaded bed, a few plump three-grain ears."""
    img = canvas(16, 16, STRAW[1])
    grain(img, STRAW, 'strawbed', .25, up=.2)
    strands(img, [(STRAW, 2), (STRAW, 2), (STRAW, 3)], 'straw', 30, (4, 7), ((1, 1), (1, -1), (1, 0), (2, 1), (2, -1)))
    r = rng('ears')
    for _ in range(5):
        x, y = r.randrange(16), r.randrange(16)
        stamp(img, x, y, ['ab', 'ba', 'ab'], {'a': STRAW[4], 'b': STRAW[3]})
    return img


def grass_tile():
    """A bowl of fresh cut grass: long blades lying mostly one way, dark at the cut root and light toward the tip."""
    img = canvas(16, 16, CLIP[1])
    r = rng('clippings')
    for _ in range(60):
        x, y = r.randrange(16), r.randrange(16)
        dx, dy = r.choice(((1, 1), (1, 1), (2, 1), (1, 2), (1, 0), (2, -1)))
        n = r.randint(5, 9)
        put(img, x - 1, y, CLIP[0])
        for i in range(n):
            step = i * 3 // n
            px, py = x + (i * dx) // max(dx, dy, 1), y + (i * dy) // max(dx, dy, 1)
            put(img, px, py % 16, CLIP[2 + step if step < 2 else 4])
    return img


def pellet_face():
    img = canvas(2, 2, PELLET[3])
    put(img, 0, 0, PELLET[4]); put(img, 1, 1, PELLET[2])
    return img


def wheat_ear():
    """A laid wheat ear (cut-out): stalk on the left, plump grain head with short awns."""
    img = canvas(9, 4)
    stamp(img, 0, 0, ['.....a.a.',
                      'sssshGhGa',
                      '....ghgh.',
                      '.....a.a.'], {'s': STRAW[2], 'h': STRAW[4], 'G': STRAW[3], 'g': STRAW[2], 'a': STRAW[1]}, wrap=False)
    return img


def food_bowl_regular():
    """Oil-grain kibble heap; each portion also drops two loose pellets on its surface."""
    b = Build('food_bowl_regular')
    fill_heaps(b, 'kibble', kibble_tile(), lump=.08)
    r = rng('loose-pellets')
    for k in range(1, 9):
        part = b.part(f'kibble_bits_u{k}', k)
        R = min(FILL_R[k - 1], FILL_CLIP) * .7
        for _ in range(5):
            a, rr = r.uniform(0, 2 * math.pi), r.uniform(.2, 1) * R
            x, z = rr * math.cos(a), rr * math.sin(a)
            y = heap_y(k, rr) - .15
            box(b, part, (x - .45, y, z - .45), (x + .45, y + .7, z + .45), 'pellet', pellet_face())
    return b.finish()


def laid(b, part, key, img, k, count, length, width, seed, lift):
    """Cut-out strips lying on heap k, one end lifted (`lift` units), turned at random."""
    r = rng(seed + str(k))
    R = min(FILL_R[k - 1], FILL_CLIP) * .6
    for _ in range(count):
        a, rr = r.uniform(0, 2 * math.pi), r.uniform(.1, 1) * R
        x, z = rr * math.cos(a), rr * math.sin(a)
        yaw = r.uniform(0, 2 * math.pi)
        dx, dz = math.cos(yaw), math.sin(yaw)
        px, pz = -dz * width / 2, dx * width / 2
        y0 = heap_y(k, rr) + .05
        ax, az = x - dx * length / 2, z - dz * length / 2
        bx, bz = x + dx * length / 2, z + dz * length / 2
        ya, yb = max(y0, heap_y(k, math.hypot(ax, az)) + .05), max(y0, heap_y(k, math.hypot(bx, bz)) + .05) + lift
        sprite(b, part, [[ax + px, ya, az + pz], [bx + px, yb, bz + pz], [bx - px, yb, bz - pz], [ax - px, ya, az - pz]], key, img)


def food_bowl_wheat():
    """Loose wheat heap; each portion lays two whole ears (drawn here) on its surface."""
    b = Build('food_bowl_wheat')
    fill_heaps(b, 'straw', straw_tile(), lump=.15)
    for k in range(1, 9):
        laid(b, b.part(f'wheat_ears_u{k}', k), 'ear', wheat_ear(), k, 2, 6.3, 2.8, 'ears', .7)
    return b.finish()


def grass_tuft():
    """A tuft of cut blades (cut-out): pale cut ends at the root, darker green toward the tips."""
    img = canvas(4, 5)
    stamp(img, 0, 0, ['d..d', 'd.dm', 'mdm.', '.mml', '.ll.'], {'d': CLIP[2], 'm': CLIP[3], 'l': CLIP[4]}, wrap=False)
    return img


def food_bowl_forage():
    """Cut grass heap; each portion adds two small crossed tufts of blades standing out of the heap."""
    b = Build('food_bowl_forage')
    fill_heaps(b, 'clippings', grass_tile(), lump=.2)
    tuft = grass_tuft()
    for k in range(1, 9):
        part = b.part(f'grass_tufts_u{k}', k)
        r = rng('tufts' + str(k))
        R = min(FILL_R[k - 1], FILL_CLIP) * .6
        for _ in range(2):
            a, rr = r.uniform(0, 2 * math.pi), r.uniform(.1, 1) * R
            x, z = rr * math.cos(a), rr * math.sin(a)
            y = heap_y(k, rr) - .4
            for yaw in (a + .4, a + .4 + math.pi / 2):
                dx, dz = math.cos(yaw) * 1.2, math.sin(yaw) * 1.2
                sprite(b, part, [[x - dx, y + 2.6, z - dz], [x + dx, y + 2.6, z + dz], [x + dx, y, z + dz], [x - dx, y, z - dz]],
                       'tuft', tuft)
    return b.finish()


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
NEST_PAD = 1.75            # collision top of the level pad over the whole block square


def nest_notch(x, z, y, floor=1.6, lip=2.8, top=5.25):
    """U-shaped entrance: within about 35 degrees of north the bolster sinks to a low rolled lip; the outer half scales
    toward the ground, the inner half toward the pad, meeting at the crest."""
    r = math.hypot(x, z)
    c = max(0.0, -z / r) if r > 1e-6 else 0.0
    w = smoothstep(.74, .89, c)
    if r >= NEST_CREST:
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


def yukkuri_nest(style=''):
    b = Build(model_name('yukkuri_nest', style))
    o = CIRCLE(NEST_SEGS)
    part = b.part('nest')
    revolve(b, part, o, NEST_ROLL, 'roll', fabric_band(style, 72, 'roll' + style), smooth=range(1, 11), ymap=nest_notch, ref=12.5)
    cap(b, part, o, NEST_CUSHION, 'cushion', cushion_top(style), centre=1.8)
    cap(b, part, o, [(12.8, 0.0)], 'under', canvas(4, 4, NESTS[style][0][0]), down=True, centre=0.0)
    return b.finish()


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
# Sand toilet (CareBlock sand tray box 14.46 x 11.58, 2.35 high): a rounded plastic tray 14.4 x 12, 3 high, with a rolled
# lip and sand at y 2 (EH34's rectangular toilet; L32's sand floor), wide enough that an adult up to 1.2x sits in it.
TRAY = Outline(7.2, 6.0, 2.0, arc=4, step=3.0)
TRAY_WALL = [(0.0, 0.0, 6), (0.0, 1.25, 4.5), (0.0, 2.5, 3), (-.25, 2.95, 2), (-.75, 3.0, 1.5), (-1.0, 2.7, 1), (-1.0, 2.0, 0)]
PLASTICS = {
    #        ramp, emblem rows, emblem colour
    '':      (MINT, PAW, WHITE[4]),
    'pink':  (PINK, HEART, WHITE[4]),
    'lemon': (LEMON, ['x.x', '.x.', 'x.x'], WHITE[4]),
    'sky':   (SKY, ['.x.', 'xxx', 'x.x'], WHITE[4]),
}


def plastic_band(style, w, h):
    ramp, emblem, colour = PLASTICS[style]
    rows = [1, 4, 3, 3, 2, 0]                 # inner wall at the sand (shaded), lit lip top, outer wall, contact line
    img = canvas(w, h)
    for y in range(h):
        for x in range(w):
            put(img, x, y, ramp[rows[y]])
    return grain(img, ramp, 'plastic' + style, .08, rows=range(2, 5))


def sand_top(dirty, size=16):
    r = rng('sand' + ('d' if dirty else ''))

    def paint(x, z, _):
        if abs(x) > 7.2 or abs(z) > 6:
            return None
        if abs(x) > 5.8 or abs(z) > 4.6:                     # shaded where the sand meets the wall
            return SAND[1]
        k = r.random()
        return SAND[3] if k < .22 else SAND[1] if k < .3 else SAND[4] if k < .33 else SAND[2]
    img = top_view(size, paint)
    if dirty:
        for _ in range(8):                   # damp clumps
            x, y = r.randrange(2, 14), r.randrange(3, 13)
            stamp(img, x, y, ['ab', 'bc'], {'a': STAIN[1], 'b': STAIN[0], 'c': ANKO[1]}, wrap=False)
    return img


def toilet_tray(b, style, dirty):
    part = b.part('tray')
    ramp = PLASTICS[style][0]
    revolve(b, part, TRAY, TRAY_WALL, 'wall', plastic_band(style, 48, 6), smooth=(3, 4), ref=-.5)
    cap(b, part, TRAY, [(-1.0, 2.0), (-1.4, 2.0)], 'sand', sand_top(dirty), centre=2.0)
    cap(b, part, TRAY, [(0.0, 0.0)], 'under', canvas(4, 4, ramp[1]), down=True, centre=0.0)


def yukkuri_toilet(style=''):
    b = Build(model_name('yukkuri_toilet', style))
    toilet_tray(b, style, False)
    return b.finish()


def yukkuri_toilet_dirty(style=''):
    b = Build(model_name('yukkuri_toilet', style) + '_dirty')
    toilet_tray(b, style, True)
    swirl(b, b.part('droppings'), (1.2, 2.0, -1.2), .62, 'swirl')
    swirl(b, b.part('droppings'), (-3.8, 2.0, 1.9), .38, 'swirl', tiers=1)
    return b.finish()


# ---- droppings: a soft round swirl ----------------------------------------------------------------------------------
SWIRL = [(2.6, 0.0, 0), (2.95, .55, 1), (2.55, 1.15, 2), (2.0, 1.35, 3), (2.15, 1.85, 4), (1.65, 2.45, 5), (1.1, 2.65, 6),
         (1.2, 3.05, 7), (.65, 3.6, 8), (.15, 4.0, 9)]


def swirl_band():
    img = canvas(24, 10, ANKO[2])
    for y in range(10):
        for x in range(24):
            if (x + 2 * y) % 8 == 0:
                put(img, x, y, ANKO[3])
            if y in (3, 6):
                put(img, x, y, ANKO[1])
            if y in (1, 4, 7) and x % 6 == 1:
                put(img, x, y, ANKO[4])
    return img


def swirl(b, part, at, scale, key, tiers=3):
    o = CIRCLE(12)
    prof = SWIRL[:{1: 4, 3: len(SWIRL)}[tiers]]
    x0, y0, z0 = at
    ox, oz = x0 / scale, z0 / scale
    # shapes revolve about the origin: scale and offset the profile through ymap/positions afterwards
    start = len(part['tris'])
    revolve(b, part, o, prof, key, swirl_band(), smooth=range(1, len(prof) - 1), ref=1.8)
    top = prof[-1]
    cap(b, part, o, [(top[0], top[1])], key + '_top', canvas(4, 4, ANKO[3]), centre=top[1] + .2)
    for tri in part['tris'][start:]:
        for i, (p, n, uv) in enumerate(tri):
            tri[i] = ([p[0] * scale + x0, p[1] * scale + y0, p[2] * scale + z0], n, uv)


def toilet_waste():
    """One dropping; CareRenderer draws it at half scale once per stored portion in the ceramic and covered toilets."""
    b = Build('toilet_waste', width=64)
    swirl(b, b.part('swirl'), (0.0, 0.0, 0.0), 1.0, 'swirl')
    return b.finish()


# =====================================================================================================================
# Ceramic and covered toilets: rounded-square glazed trays 14 x 14 with a notch down to the floor at the front (-z).
# Floors stay where CareRenderer draws the droppings: ceramic y 1, covered y 1.5. The covered toilet adds a domed hood
# over the back half resting on the rim, so the front stays open.
POTTY = Outline(7.0, 7.0, 3.0, arc=6, step=3.0)


def potty_profile(floor, rim):
    return [(-.4, 0.0, 9), (0.0, .45, 8), (0.0, rim - .5, 5), (-.12, rim - .1, 4.4), (-.35, rim, 4), (-.9, rim, 3),
            (-1.15, rim - .45, 2), (-1.2, floor + .3, 1), (-1.35, floor, 0)]


def potty_notch(floor):
    def ymap(x, z, y):
        if z >= 0 or y <= floor:
            return y
        return floor + (y - floor) * smoothstep(2.6, 6.2, abs(x))
    return ymap


def potty(b, style, floor, rim, glaze=None):
    glaze = style if glaze is None else glaze
    part = b.part('tray')
    notch = potty_notch(floor)
    revolve(b, part, POTTY, potty_profile(floor, rim), 'glaze', glaze_band(glaze, 48, 'potty' + glaze), smooth=(3, 4, 6, 7),
            ymap=notch, ref=-.6, facet=True)
    cap(b, part, POTTY, [(-1.35, floor)], 'floor', glaze_floor(glaze, motif=False, radius=5.6), centre=floor)
    cap(b, part, POTTY, [(-.4, 0.0)], 'foot', canvas(4, 4, GLAZES[glaze][4][1]), down=True, centre=0.0)


def ceramic_toilet(style=''):
    b = Build(model_name('ceramic_toilet', style))
    potty(b, style, 1.0, 3.0)
    return b.finish()


HOODS = {'': (MINT, CREAM, ''), 'pink': (PINK, WHITE, 'pink'), 'navy': (NAVY, LEMON, 'navy')}
HOOD_OUT = [(.35, 3.0, 0), (.35, 3.9, 1), (-.4, 4.8, 2.5), (-1.7, 5.3, 4), (-3.0, 5.5, 6)]


def hood_band(style, w, h):
    """Hood: row 0 the lower edge resting on the rim (shaded), rows up to the crown lighter, with the style's print."""
    ramp, trim, _ = HOODS[style]
    img = canvas(w, h, ramp[2])
    for x in range(w):
        put(img, x, 0, ramp[1])
        put(img, x, 1, ramp[2])
        for y in range(3, h):
            put(img, x, y, ramp[3])
    if style == 'pink':
        for y in range(2, h):
            for x in range(w):
                if (x + 2 * (y % 2)) % 4 == 0 and y % 2 == 0:
                    put(img, x, y, WHITE[4])
    elif style == 'navy':
        r = rng('hoodstars')
        for y in range(1, h):
            for x in range(w):
                if r.random() < .06:
                    put(img, x, y, LEMON[4])
    return grain(img, ramp, 'hood' + style, .08)


def covered_toilet(style=''):
    """Glazed tray (floor y 1.5, rim 3.5) and a hood: outer and inner dome over the back half, a lit front edge."""
    b = Build(model_name('covered_toilet', style))
    ramp, trim, glaze = HOODS[style]
    potty(b, style, 1.5, 3.5, glaze='' if style == '' else ('pink' if style == 'pink' else 'navy'))
    part = b.part('hood')
    cols = POTTY.select(lambda x, z: z >= -1e-6)
    revolve(b, part, POTTY, HOOD_OUT, 'hood', hood_band(style, 24, 6), smooth=(1, 2, 3), cols=cols, ref=-.5)
    inner = [(o - .45, y - .45, v) for o, y, v in HOOD_OUT]
    revolve(b, part, POTTY, inner, 'hood_in', canvas(8, 5, ramp[1]), cols=cols, ref=-.9, inward=True)
    def hood_paint(x, z, r):
        u, v = math.floor(x), math.floor(z)
        if style == 'pink' and (u + 3 * (v % 2)) % 6 == 0 and v % 2:
            return WHITE[4]
        if style == 'navy' and (u * 7 + v * 5) % 13 == 0:
            return LEMON[4]
        return ramp[3]
    hood_top = grain(top_view(16, hood_paint), ramp, 'hoodtop' + style, .05)
    cap(b, part, POTTY, [(-3.0, 5.5)], 'hood_top', hood_top, cols=cols, centre=5.5)
    cap(b, part, POTTY, [(-3.45, 5.05)], 'hood_in_top', canvas(4, 4, ramp[1]), down=True, cols=cols, centre=5.05)
    # front edge where the hood is cut at z = 0: a strip from the outer to the inner profile on both sides and the top
    edge = b.part('hood')
    for sx in (-1, 1):
        for (o0, y0, _), (o1, y1, _) in zip(HOOD_OUT, HOOD_OUT[1:]):
            xa, xb = sx * (7.0 + o0), sx * (7.0 + o1)
            pts = [[xa, y0, 0.0], [xb, y1, 0.0], [xb - sx * .45, y1 - .45, 0.0], [xa - sx * .45, y0 - .45, 0.0]]
            sprite(b, edge, pts, 'hood_edge', canvas(2, 2, ramp[1]), normal=[0, 0, -1])
    sprite(b, edge, [[-4.0, 5.5, 0.0], [4.0, 5.5, 0.0], [4.0, 5.05, 0.0], [-4.0, 5.05, 0.0]], 'hood_edge', canvas(2, 2, ramp[1]),
           normal=[0, 0, -1])
    return b.finish()


# =====================================================================================================================
FACTORIES = {'food_bowl': food_bowl, 'yukkuri_nest': yukkuri_nest, 'large_yukkuri_nest': large_yukkuri_nest,
             'yukkuri_toilet': yukkuri_toilet, 'ceramic_toilet': ceramic_toilet, 'covered_toilet': covered_toilet}


def variants():
    """Non-default style meshes (and the sand toilet's dirty state per style); shipped as attachments."""
    out = {}
    for base, styles in STYLES.items():
        for style in styles[1:]:
            out[model_name(base, style)] = (lambda f=FACTORIES[base], s=style: f(s))
            if base == 'yukkuri_toilet':
                out[model_name(base, style) + '_dirty'] = (lambda s=style: yukkuri_toilet_dirty(s))
    return out


# Collision shells that follow the visible nests (life/CareBlock reads them): top-surface heightmaps of the meshes over
# square cells, rounded up so a body rests on or just above the visible surface, merged into equal-height boxes.
# The cushion nest's level pad is a 23 square (the block and 3.5 beyond each side, clipped to the round nest at radius
# 13.75 so that no pad reaches past the visible bolster at the diagonals): a body box resting anywhere inside it touches
# only the pad, never the bolster, so an adult (to 1.2x at full term) sits down in the nest instead of hanging on the rim;
# the bolster keeps its collision outside the square (NestResting keeps resting bodies inside it).
# name: (factory, half extent, cell, step, (level half extent, level top, level radius) or None)
COLLISION = {'yukkuri_nest': (yukkuri_nest, 13.5, .5, .5, (11.5, NEST_PAD, 13.75)),
             'large_yukkuri_nest': (large_yukkuri_nest, 16.0, .25, .25, None)}


def collision():
    out = {'format': 1, 'generator': 'standards/yukkuri-library/drafts/care-props/placed.py',
           'units': 'boxes [x0, z0, x1, z1, top] in 1/16 block from the footprint centre (block centre), bottom 0'}
    for name, (factory, half, cell, step, level) in COLLISION.items():
        hm = heightmap(factory(), half, cell, step=step)
        if level:
            inner, top, reach = level
            for k, row in enumerate(hm):
                for i in range(len(row)):
                    x0, z0 = -half + i * cell, -half + k * cell
                    if -inner <= x0 and x0 + cell <= inner and -inner <= z0 and z0 + cell <= inner                             and math.hypot(x0 + cell / 2, z0 + cell / 2) <= reach:
                        row[i] = top
        boxes = collision_boxes(hm, half, cell)
        out[name] = {'cell': cell, 'top': max(b[4] for b in boxes), 'boxes': boxes}
    return out

