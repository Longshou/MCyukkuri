"""The round food bowl in its dye-selected styles and its three fills (oil-grain kibble, wheat, cut grass).

A glazed ceramic bowl revolved round a 16-sided circle (shapes.py), 14 across and 3 high, with a broad foot, an upright
belly that carries the style's pattern, a rolled lip and the floor of the well at y 1, where the fills start; the
mouth-contact height (rim 3) and the CareBlock footprint stay those of the bowl. One painted band runs from the floor
edge up the inside wall, over the rim and down the outside. The default style is the inventory icon's bowl: warm white
glaze with a red lip band. A style only repaints the band and the floor print (life/CareStyles.java holds the dye table).

Fills (food_bowl_regular / _wheat / _forage): one lumpy heap per portion (units 1-8). Heap k encloses heap k-1 (wider and
higher), so CareRenderer's drawing of units 1..k shows heap k; the last heaps rise over the rim. Each portion also lays
its own loose pieces on its heap: two kibble pellets, two wheat ears or two crossed tufts of grass.

Source evidence (wiki): a small shallow round floor bowl of kibble (EH32 p.5-9), a round pet bowl lettered with the pet's
name and hearts (EH23 p.2). Colours, patterns and the fills' look are project design; nothing copied.
"""
import math
from shapes import Build, CIRCLE, revolve, cap, box, sprite, canvas, put, grain, stamp, top_view, rng
from placed import CREAM, WHITE, MINT, PINK, RED, SKY, NAVY, LEMON, BROWN, model_name

TERRA = ['#5c2915', '#843f27', '#a85937', '#c77950', '#df9b72']
STEEL = ['#4b535e', '#707a86', '#98a2ad', '#c2cad2', '#e9eef3']
CLAY = ['#4e3222', '#6c4a33', '#8b6446', '#a87f5c']
PELLET = ['#3f200d', '#6e3a14', '#a5602a', '#d18f45', '#efbf6e']
STRAW = ['#6b5420', '#957528', '#c19c37', '#dcbd52', '#f2df8a']
CLIP = ['#22401a', '#335c22', '#4a7a2c', '#67993a', '#8fbb55']

STYLES = {'food_bowl': ['', 'hearts', 'polka', 'sailor', 'paws', 'terracotta', 'steel']}

# Band rows: 0-2 inside wall (floor upward), 3 rim top, 4 rolled lip, 5-7 belly (pattern), 8 foot.
GLAZES = {
    #             belly  step  lip    inside foot   floor motif
    '':           (CREAM, 4, RED, WHITE, CREAM, None),
    'hearts':     (WHITE, 3, RED, WHITE, CLAY, 'heart'),
    'polka':      (PINK, 2, WHITE, WHITE, CLAY, 'dots'),
    'sailor':     (WHITE, 3, NAVY, WHITE, NAVY, 'anchor'),
    'paws':       (LEMON, 2, BROWN, CREAM, BROWN, 'paw'),
    'terracotta': (TERRA, 2, CREAM, CREAM, TERRA, 'flower'),
    'steel':      (STEEL, 3, STEEL, STEEL, STEEL, 'brushed'),
}
GLAZE_ROWS = 9
MOTIFS = {      # 16 px floor prints (top view, -z at the top)
    'heart': (['.x.x.', 'xxxxx', 'xxxxx', '.xxx.', '..x..'], {'x': RED[3]}),
    'anchor': (['..x..', '.xxx.', '..x..', 'x.x.x', '.xxx.'], {'x': NAVY[3]}),
    'paw': (['..xx.xx..', '..xx.xx..', 'xx.....xx', 'xx.....xx', '...xxx...', '..xxxxx..', '..xxxxx..', '...xxx...'], {'x': BROWN[1]}),
    'flower': (['.p.', 'pcp', '.p.'], {'p': TERRA[3], 'c': LEMON[3]}),
}


def glaze_band(style, w, key):
    """Four steps per material: the rim top is the lit band, the inside wall just under the rim is shaded so the rim reads
    as an outline, the foot is the darkest step (contact line), belly and inside wall carry 8% one-step grain."""
    belly, step, lip, inside, foot, _ = GLAZES[style]
    img = canvas(w, GLAZE_ROWS, belly[step])
    for x in range(w):
        put(img, x, 0, inside[2])
        put(img, x, 1, inside[3])
        put(img, x, 2, inside[1])
        put(img, x, 3, lip[4])
        put(img, x, 4, lip[3] if x % 7 else lip[4])
        put(img, x, 7, belly[max(0, step - 1)])
        put(img, x, 8, foot[0])
    if style == '':                          # the icon's bowl: a white rim top over the red lip band, white below it
        for x in range(w):
            put(img, x, 3, WHITE[4])
            put(img, x, 4, RED[2] if x % 7 else RED[3])
            put(img, x, 8, CREAM[1])
    elif style == 'hearts':                  # hearts all round (EH23: a pet's bowl lettered with hearts)
        for x in range(0, w, 8):
            stamp(img, x + 1, 5, ['xx.xx', 'xxxxx', '.xxx.'], {'x': RED[2]})
            put(img, x + 1, 5, RED[3])
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
    grain(img, inside, key + 'in', .08, rows=range(0, 2))
    grain(img, belly, key, .08, rows=range(5, 8))
    if style == 'terracotta':
        grain(img, TERRA, key + 'clay', .1, rows=range(5, 8))
    return img


def glaze_floor(style, size=16):
    """Floor top view: inside glaze with 8% grain and the style's small print; the shaded contact is on the wall band."""
    inside, print_ = GLAZES[style][3], GLAZES[style][5]

    def paint(x, z, r):
        u, v = math.floor(x), math.floor(z)
        if print_ == 'dots' and (u * 2 + v * 3) % 7 == 0 and (u + v) % 2 == 0:
            return PINK[3]
        if print_ == 'brushed' and v % 4 == 0 and (u + v) % 5:
            return '#d4dbe1'
        return inside[3]
    img = grain(top_view(size, paint), inside, 'floor' + style, .08)
    if print_ in MOTIFS:
        rows, legend = MOTIFS[print_]
        stamp(img, (size - len(rows[0]) + 1) // 2, (size - len(rows)) // 2, rows, legend, wrap=False)
    return img


# =====================================================================================================================
# The bowl: profile rows (radius, y, band row) from the foot's outer edge over the rim and down the inside to the floor.
# The outside flares from a foot 10.6 across to the rolled lip 14 across, as the icon's bowl does; the rim top, the well
# and its floor are those every fill and contact height was made for.
BOWL_SEGS = 16
BOWL_FOOT = 5.3
BOWL = [(BOWL_FOOT, 0.0, 9), (5.65, .35, 8), (6.1, 1.0, 7), (6.65, 2.1, 5), (7.0, 2.65, 4.5), (6.85, 3.0, 4), (5.9, 3.0, 3),
        (5.55, 2.0, 2), (5.1, 1.25, 1), (4.6, 1.0, 0)]
BOWL_FLOOR_Y = 1.0
BOWL_FLOOR_R = 4.6


def food_bowl(style=''):
    b = Build(model_name('food_bowl', style))
    o = CIRCLE(BOWL_SEGS)
    part = b.part('bowl')
    revolve(b, part, o, BOWL, 'glaze', glaze_band(style, 48, 'bowl' + style), smooth=(2, 3, 4, 7, 8), ref=6.3, facet=True)
    cap(b, part, o, [(BOWL_FLOOR_R, BOWL_FLOOR_Y)], 'floor', glaze_floor(style), centre=BOWL_FLOOR_Y)
    cap(b, part, o, [(BOWL_FOOT, 0.0)], 'foot', canvas(4, 4, GLAZES[style][4][1]), down=True, centre=0.0)
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
        rings = [(R * f, heap_y(k, R * f)) for f in FILL_RINGS]
        jit = {}

        def ymap(x, z, y, k=k):
            key = (round(x, 3), round(z, 3))
            if key not in jit:
                edge = math.hypot(x, z) >= min(FILL_R[k - 1], FILL_CLIP) - 1e-6
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
    put(img, 0, 0, PELLET[4])
    put(img, 1, 1, PELLET[2])
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
    """Oil-grain kibble heap; each portion also drops loose pellets on its surface."""
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
    """Loose wheat heap; each portion lays two whole ears on its surface."""
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


PROPS = {'food_bowl': food_bowl, 'food_bowl_regular': food_bowl_regular, 'food_bowl_wheat': food_bowl_wheat,
         'food_bowl_forage': food_bowl_forage}


def variants():
    """Non-default dye styles, shipped as runtime-peripheral attachments."""
    return {model_name('food_bowl', style): (lambda s=style: food_bowl(s)) for style in STYLES['food_bowl'][1:]}
