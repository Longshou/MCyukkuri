"""Block-style nests and the forage store (kit.py), matching their inventory icons. Units are 1/16 block, +Y up.

Pet beds (life/PetNestBlock, three grades, entrance -z in the bed frame): one stepped round bed 26 across, built per
column from its depth inside the outline: an upright outer band of the roll and a one-step-lower inner band round a level
pad (top y 2) 20 across, so the top reads as one clean stepped ring. The grades share the geometry and the shared atlas layout (kit.share_atlas), so the
fitted shapes (pet_nest_fit_<mask>, geometry only) draw with any grade's or style's texture, as CareRenderer does:
- straw_nest (grade 1): the low coiled-straw ring (4 high) with dark binding wraps and loose stalk tips, round a pad
  of loose hay;
- yukkuri_nest (grade 2) and its six dye styles: the same ring raised by a soft crest (5 high) in printed cotton round a
  quilted cream pad;
- velvet_nest (grade 3): a deep wine velvet bolster (6 high) with a gold cord along its top edge and a white lace frill
  at its foot, round an ivory satin pad with gold buttons.
The crest, the bolster and the stalk tips are optional parts (kit solid=False): a grade that has none paints them clear,
so its silhouette is lower while the geometry stays the same for every look.
Fitted against blocks: a side whose neighbour reaches into the overhang runs straight along the block face (x or z 8)
with the roll's rows pressed to NEST_KEEP of their depth, a one-cell wall at full height; corners between a flat and a
round side round off with radius 8, between two flat sides with radius 3 (placed.py nest_outline, the collision
envelope). The entrance dips to a one-cell lip over the pad within about 35 degrees of -z on every shape.
Collision stays the envelope in placed.py (care_collision.json unchanged): the pad top is 2 over the collision pad
1.75, the cushion crest 5 under the collision crest 5.25.
"""
import functools, importlib.util, math, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if 'block_props_kit' not in sys.modules:   # loaded under its own name: care-props also has a `kit`
    _spec = importlib.util.spec_from_file_location('block_props_kit', HERE / 'kit.py')
    sys.modules['block_props_kit'] = _module = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(_module)
kit = sys.modules['block_props_kit']


def mix(*values):
    """A fixed per-cell hash in 0..15, so grain and strand patterns stay put between exports."""
    h = 0
    for v in values:
        h = (h * 31 + v * 17 + 7) & 0xffff
    return (h ^ (h >> 5)) & 15


def smoothstep(a, b, x):
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


def ring_u(cell):
    """Position along a ring round the vertical axis, in cells (for patterns that run round a roll)."""
    return int((math.atan2(cell[2] + .5, cell[0] + .5) + math.pi) * 13)


# =====================================================================================================================
# Pet beds.
NEST_OUTER = 13.0          # outer radius of the round bed (the envelope's 13.3 on whole cells)
NEST_FLAT = 8.0            # a flattened side's outer edge: the block face
NEST_KEEP = .3             # a flattened side keeps this share of the roll's depth
NEST_CORNER = {0: NEST_OUTER, 1: 8.0, 2: 3.0}
PAD_DEPTH = 3.3            # the roll's depth inside the outline; the pad lies beyond it
PAD_TOP = 2
ROLL_WALL = 2.4          # the roll's upright outer band; the inner band beyond it is one step lower


def nest_depth(mask, px, pz):
    """Depth of the point (px, pz) inside the fitted outline, in roll units (flattened sides count 1/NEST_KEEP)."""
    walled = {side: bool(mask >> i & 1) for i, side in enumerate('nesw')}
    sx, sz = (1 if px >= 0 else -1), (1 if pz >= 0 else -1)
    wx, wz = walled['e' if sx > 0 else 'w'], walled['s' if sz > 0 else 'n']
    radius = NEST_CORNER[wx + wz]
    ex, ez = (NEST_FLAT if wx else NEST_OUTER), (NEST_FLAT if wz else NEST_OUTER)
    qx, qz = sx * px - (ex - radius), sz * pz - (ez - radius)
    d = math.hypot(max(qx, 0), max(qz, 0)) + min(max(qx, qz), 0) - radius
    return -d / (NEST_KEEP if (wx if qx >= qz else wz) else 1.0)


def roll_tops(depth):
    """Absolute tops of the straw ring, the crest and the bolster at a roll depth."""
    outer = depth < ROLL_WALL
    base = 4 if outer else 3
    return base, base + 1, base + 2 if outer else base + 1


def bed_plan(mask):
    """{(x, z): (depth, (base, crest, bolster) tops)} over the bed's columns; the pad's tops are all PAD_TOP."""
    plan = {}
    for x in range(-13, 13):
        for z in range(-13, 13):
            px, pz = x + .5, z + .5
            depth = nest_depth(mask, px, pz)
            if depth < 0:
                continue
            if depth >= PAD_DEPTH:
                plan[(x, z)] = (depth, None)
                continue
            r = math.hypot(px, pz)
            door = smoothstep(.74, .89, -pz / r)                  # the entrance dip toward -z
            tops = tuple(round(3 + (t - 3) * (1 - door)) for t in roll_tops(depth))
            plan[(x, z)] = (depth, tops)
    return plan


# Looks: pad, roll (the ring, the crest and the bolster share it) and which optional parts it shows.
#                      roll   pad      crest  bolster tufts
LOOKS = {'straw_nest': ('STRAW', 'SAND', False, False, True),
         'yukkuri_nest': ('BERRY', 'CREAM', True, False, False),
         'yukkuri_nest_gingham': ('SKY', 'WHITE', True, False, False),
         'yukkuri_nest_polka': ('ROSE', 'CREAM', True, False, False),
         'yukkuri_nest_floral': ('MINTC', 'WHITE', True, False, False),
         'yukkuri_nest_lavender': ('LILAC', 'PAPER', True, False, False),
         'yukkuri_nest_teddy': ('ROPE', 'CREAM', True, False, False),
         'yukkuri_nest_stars': ('INK', 'NAVYW', True, False, False),
         'velvet_nest': ('WINE', 'PAPER', True, True, False)}


def roll_paint(look, plan, tops):
    ramp = LOOKS[look][0]

    def paint(cell, face):
        x, y, z = cell
        u = ring_u(cell)
        top = tops.get((x, z), 0)
        inside = kit.inward(cell, face)
        if face == 'down':
            return (ramp, 0)
        # shading of the roll: lit along the top, the outer side mid, the inner side and the contact row darker
        base = 3 if face == 'up' else 1 if y == 0 else (1 if inside else 2)
        if look == 'straw_nest':
            if u % 6 == 0:
                return (ramp, max(0, base - 2))                    # binding wraps of the coiled straw
            return (ramp, base + (1 if mix(x, y, z) < 4 else -1 if mix(x, y, z) > 12 else 0))
        if look == 'velvet_nest':
            if face != 'up' and not inside and y == top - 1 and top > 4:
                return ('GOLDC', 3 if u % 3 else 1)                # gold cord along the bolster's top edge
            if face != 'up' and not inside and y == 0:
                return ('PAPER', 4 if u % 2 else 2)                # white lace frill at the foot
            if face == 'up' and u % 7 == 0:
                return (ramp, 4)                                   # velvet sheen on the crest
            return (ramp, base)
        if look == 'yukkuri_nest':
            if mix(x, y, z) == 0 and y > 0:
                return ('BLOND', 3)                                # strawberry seeds
        elif look == 'yukkuri_nest_gingham':
            check = (u // 2 + y) % 2 if face != 'up' else (x // 2 + z // 2) % 2
            return ('WHITE', base + 1) if check else (ramp, base)
        elif look == 'yukkuri_nest_polka':
            if (u + 3 * y) % 4 == 0 and y > 0 or face == 'up' and (x + 2 * z) % 4 == 0:
                return ('WHITE', 4)
        elif look == 'yukkuri_nest_floral':
            if mix(x, y, z) < 2 and y > 0:
                return ('WHITE', 4)
            if mix(x, y, z) == 2 and y > 0:
                return ('BLOND', 3)
        elif look == 'yukkuri_nest_lavender':
            if (u + y) % 4 == 0 or face == 'up' and (x + z) % 4 == 0:
                return (ramp, base - 1)                            # quilting stitches
        elif look == 'yukkuri_nest_teddy':
            return (ramp, base + (1 if mix(x, y, z) < 5 else 0))   # plush tufts
        elif look == 'yukkuri_nest_stars':
            if mix(x, y, z) == 0 and y > 0:
                return ('BLOND', 3)
        return (ramp, base)
    return kit.Material(ramp, pattern=paint, bevel=False)


def pad_paint(look, plan):
    ramp = LOOKS[look][1]
    rim = {c for c, (_, t) in plan.items() if t is None and any(plan.get((c[0] + dx, c[1] + dz), (0, 1))[1] is not None
                                                                   for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))}

    def paint(cell, face):
        x, y, z = cell
        if face != 'up':
            return (ramp, 1)
        if (x, z) in rim:
            return (ramp, 1)                                       # tuck where the pad meets the roll
        if look == 'straw_nest':
            strand = (x - 2 * z) % 5 == 0 or (2 * x + z) % 7 == 0
            return ('STRAW', 3) if strand and mix(x, z) < 9 else (ramp, 2 if mix(x, z, 1) < 6 else 3)
        if look == 'velvet_nest':
            if x % 4 == 0 and z % 4 == 0:
                return ('GOLDC', 3)                                # gold buttons
            return (ramp, 2) if (x + z) % 4 == 2 and (x - z) % 4 == 2 else (ramp, 3)
        if look == 'yukkuri_nest_gingham':
            return (ramp, 4) if (x // 2 + z // 2) % 2 else ('SKY', 4)
        if (x + z) % 6 == 3 or (x - z) % 6 == 3:
            return (ramp, 2)                                       # quilting seams
        return (ramp, 4) if (x + z) % 6 == 0 and (x - z) % 6 == 0 else (ramp, 3)
    return kit.Material(ramp, pattern=paint, bevel=False)


def bed(look, mask=0, name=None):
    b = kit.Block(name or look)
    plan = bed_plan(mask)
    tops = {c: t[-1] for c, (_, t) in plan.items() if t is not None}
    roll = roll_paint(look, plan, tops)
    cushion = pad_paint(look, plan)
    clear = kit.Clear()
    _, _, crest_on, bolster_on, tufts_on = LOOKS[look]
    pad = b.part('pad')
    ring = b.part('roll')
    crest = b.part('crest', solid=False)
    bolster = b.part('bolster', solid=False)
    tufts = b.part('tufts', solid=False)
    # The straw ring's own crest top (4) when the crest is clear, the crest's top when it is shown, and so on.
    shown = {'crest': crest_on, 'bolster': bolster_on}
    for (x, z), (depth, t) in sorted(plan.items()):
        if t is None:
            pad.box((x, 0, z), (x + 1, PAD_TOP, z + 1), cushion)
            continue
        base, top_crest, top_bolster = t
        ring.box((x, 0, z), (x + 1, base, z + 1), roll)
        # A few crest cells on the outer band are the loose stalk tips of the straw ring; the other looks paint them
        # as crest, so every look keeps one geometry.
        tip = depth < .5 and mix(x, z, 9) < 2 and top_crest > base
        (tufts if tip else crest).box((x, base, z), (x + 1, top_crest, z + 1),
                                      roll if (tufts_on if tip else False) or shown['crest'] else clear)
        bolster.box((x, top_crest, z), (x + 1, top_bolster, z + 1), roll if shown['bolster'] else clear)
    return b


@functools.lru_cache(maxsize=None)
def bed_family(look):
    """The round bed in `look` and its 15 fitted shapes on one atlas layout (the same for every look)."""
    blocks = [bed(look)] + [bed(look, m, f'pet_nest_fit_{m}') for m in range(1, 16)]
    kit.share_atlas(blocks)
    return blocks


def pet_bed(look):
    return bed_family(look)[0]


def fitted_bed(mask):
    """Geometry of a fitted shape; the game draws it with the placed bed's texture (PropModels.renderAs)."""
    return bed_family('yukkuri_nest')[mask]


# =====================================================================================================================
# 2x2 woven family nest (large_nests.py exports it; life/LargeNestBlock): a rounded-square basket 32 across centred on the
# shared corner of its four blocks, a one-cell woven wall 6 high with a braided top row, dipping to the cushion across the
# +z entrance (|x| < 6, back to full height by |x| 12), round a cushion floor (top y 2) 30 across with a stitched hem: two
# adults and four newborns lie anywhere inside. Collision stays the envelope in placed.py.
def rounded(half, radius):
    """Plan cells of a square 2*half wide with corners rounded to `radius` (cell centres)."""
    out = set()
    for x in range(-half, half):
        for z in range(-half, half):
            qx, qz = abs(x + .5) - (half - radius), abs(z + .5) - (half - radius)
            if math.hypot(max(qx, 0), max(qz, 0)) <= radius:
                out.add((x, z))
    return out


LARGE = rounded(16, 7)
LARGE_FLOOR = kit.inset(LARGE)
LARGE_HEM = LARGE_FLOOR - kit.inset(LARGE_FLOOR)
LARGE_TOP = 6
#              weave    cushion
WOVEN = {'': ('STRAW', 'BERRY'), 'wicker': ('WHISTLE', 'CREAM'), 'mint': ('MINTC', 'WHITE'), 'lavender': ('LILAC', 'PAPER')}


def large_wall_top(x, z):
    if z < 0:
        return LARGE_TOP
    return PAD_TOP + round((LARGE_TOP - PAD_TOP) * smoothstep(6, 12, abs(x + .5)))


def large_yukkuri_nest(style=''):
    weave, cushion = WOVEN[style]
    b = kit.Block(f'large_yukkuri_nest_{style}' if style else 'large_yukkuri_nest')

    def wicker(cell, face):
        x, y, z = cell
        top = large_wall_top(x, z)
        if face == 'down':
            return (weave, 0)
        if face == 'up':
            return (weave, 4 if (x + z) % 2 else 3)                # braided top
        u = x + z
        if y == top - 1 and top > PAD_TOP:
            return (weave, (4, 3, 2)[(u + y) % 3])                 # braid: slanted strokes
        k = (u + 2 * y) % 4                                        # over-under weave, rows offset by two
        step = 3 if k == 0 else 1 if k == 3 else 2
        return (weave, step - 1 if kit.inward(cell, face) else step)

    def pad(cell, face):
        x, y, z = cell
        if face != 'up':
            return (cushion, 1)
        if (x, z) in LARGE_HEM:
            return (cushion, 2 if (x + z) % 2 else 1)              # stitched hem
        if style == 'mint':
            return ('MINTC', 4) if (x // 2 + z // 2) % 2 else ('WHITE', 4)
        if (x + z) % 8 == 0 or (x - z) % 8 == 0:
            return (cushion, 2)                                    # quilting
        return (cushion, 4) if (x + z) % 8 == 4 and (x - z) % 8 == 4 else (cushion, 3)
    nest = b.part('large_nest')
    wall = kit.Material(weave, pattern=wicker, bevel=False)
    for x, z in sorted(LARGE - LARGE_FLOOR):
        nest.box((x, 0, z), (x + 1, large_wall_top(x, z), z + 1), wall)
    nest.layer(LARGE_FLOOR, 0, PAD_TOP, kit.Material(cushion, pattern=pad, bevel=False))
    return b


# =====================================================================================================================
# 2x2 wild family bedding (large_nests.py; LargeNestBlock.wildShape is its collision): a ragged ring of heaped dry grass
# round a hollow lined with green leaves, open across the +z front. The rim follows the collision's squircle band
# (|x|^4 + |z|^4 between 14.75^4 and 16^4), 3 high with a few 4-high tufts at the back and sides, 2 across the front
# shoulders and down to the litter for |x| < 7, with a ragged bank of loose grass one or two high just inside it and loose
# blades sticking up. The litter's top is y 1 (collision 0.5).
WILD = kit.disc(16, squircle=4)
WILD_HOLLOW = kit.disc(14.75, squircle=4)
WILD_BANK = WILD_HOLLOW - kit.disc(13.6, squircle=4)       # loose grass banked against the inside of the rim


def wild_rim_top(x, z):
    a = abs(x + .5)
    if z >= 0 and a < 7:
        return 1
    if z >= 0 and a < 12:
        return 2
    return 3 if mix(x, z, 4) < 12 else 4


def large_wild_nest():
    b = kit.Block('large_wild_nest')

    def grass(cell, face):
        x, y, z = cell
        h = mix(x, y, z)
        if face == 'down':
            return ('STRAW', 0)
        if h < 2:
            return ('LEAF', 2)                                     # green blades in the dry grass
        if face == 'up':
            return ('STRAW', 4 if h < 6 else 3 if h < 12 else 2)
        return ('STRAW', (2 if h < 5 else 1 if h < 12 else 0) - (1 if kit.inward(cell, face) else 0) + (1 if y else 0))

    def litter(cell, face):
        x, y, z = cell
        if face != 'up':
            return ('STRAW', 1)
        leaf = (x * 3 + z * 5) // 4 % 3                            # big leaves overlapping in patches
        vein = (x + z) % 4 == 0 and mix(x, z) < 10
        if (x, z) not in WILD_HOLLOW or mix(x, z, 2) < 2:
            return ('STRAW', 2 if mix(x, z) < 8 else 1)            # dry litter at the edge
        return ('LEAF', (1 if vein else 3) if leaf == 0 else (2 if vein else 3) if leaf == 1 else (1 if vein else 2))
    leaves = kit.Material('LEAF', pattern=litter, bevel=False)
    blade = kit.Material('STRAW', step=3, bevel=False)
    grass_m = kit.Material('STRAW', pattern=grass, bevel=False)
    floor = b.part('leaf_floor')
    floor.layer(WILD_HOLLOW, 0, 1, leaves)                         # the litter runs under the bank
    rim = b.part('grass_rim')
    for x, z in sorted(WILD - WILD_HOLLOW):
        top = wild_rim_top(x, z)
        if top <= 1:
            floor.box((x, 0, z), (x + 1, 1, z + 1), leaves)
            continue
        rim.box((x, 0, z), (x + 1, top, z + 1), grass_m)
        if mix(x, z, 7) < 2:                                       # a loose blade standing up out of the rim
            rim.box((x, top, z), (x + 1, top + 1 + (mix(x, z) & 1), z + 1), blade)
    for x, z in sorted(WILD_BANK):                                 # the bank: one cell of loose grass, two where the rim is high
        if wild_rim_top(x, z) <= 1 or mix(x, z, 5) < 3:
            continue
        rim.box((x, 1, z), (x + 1, 2 + (wild_rim_top(x, z) > 2 and mix(x, z, 6) < 6), z + 1), grass_m)
    return b


# =====================================================================================================================
# Predator twig nest (art/props/predators/build.py; CareBlock NEST, standing height 2.4): a stepped bowl of crossed brown
# twigs about 21 across with a few straight sticks pushed out through the weave, a dark hollow 15 across lined with straw,
# moss and white feathers. The nest sits 0.6 into its ledge so the lining's top is exactly the block's standing height.
PRED_SINK = .6
PRED_FLOOR = 3                 # lining top before the sink: 2.4
PRED_HOLLOW = kit.disc(7.6)
STICKS = (((1, 0), 4, 2), ((-1, 0), 4, -1), ((0, 1), 3, -2), ((0, -1), 4, 1), ((1, 0), 3, -4), ((0, -1), 4, -5))   # within the 1.5-block render box
FEATHERS = {(-4, 2), (-3, 2), (-2, 2), (3, -3), (3, -2), (4, -1), (1, 4), (2, 4)}


def predator_nest():
    b = kit.Block('predator_nest', offset=(0, -PRED_SINK, 0))

    def twig(cell, face):
        x, y, z = cell
        if face == 'down':
            return ('TWIG', 0)
        if (x, z) in PRED_HOLLOW and face == 'up':                 # the lining, dark in the hollow
            if (x, z) in FEATHERS:
                return ('PAPER', 4 if (x + z) % 2 else 3)
            h = mix(x, z)
            return ('STRAW', 2) if h == 0 else ('LEAF', 1) if h < 3 else ('TWIG', 1 if h < 11 else 0)
        if face == 'up':
            step = 3 if (x + y) % 3 == 0 or (z - y) % 4 == 0 else 2   # sticks crossing on the rim top
        else:
            course = (x + z + 3 * (y % 2)) % 5                     # twigs laid in courses, joints offset per course
            step = 3 if course == 0 else 1 if course == 4 else 2
        if kit.inward(cell, face):
            step -= 1                                              # the hollow's inner wall is dark
        return ('TWIG', step)
    wood = kit.Material('TWIG', pattern=twig, bevel=False)
    nest = b.part('twig_bowl')
    for y, radius in enumerate((8.6, 9.6, 10.5, 10.5, 10.2)):     # a bowl: narrow foot, widest at the middle courses
        plan = kit.disc(radius)
        nest.layer(plan if y < PRED_FLOOR else plan - PRED_HOLLOW, y, y + 1, wood)
    for x, z in sorted(kit.disc(9.4) - PRED_HOLLOW):               # ragged top course
        if mix(x, z, 3) < 11:
            nest.cell((x, 5, z), wood)
    for (dx, dz), length, offset in STICKS:                        # straight sticks pushed out through the weave
        for k in range(length):
            r = 8 + k
            x, z = (r if dx > 0 else -r - 1, offset) if dx else (offset, r if dz > 0 else -r - 1)
            nest.cell((x, 4 if length > 3 else 3, z), wood)
    plume = b.part('feathers')
    white = kit.Material('PAPER', step=3, bevel=False)
    for cell in ((-6, 5, 3), (5, 5, -4), (2, 5, 8)):               # feathers caught on the rim
        nest.cells.pop(cell, None)
        plume.cell(cell, white)
    return b


# =====================================================================================================================
# Forage store (life/ForagePileBlock; ForagePileRenderer draws units 0..stage): unit 0 is a bed of three big leaves,
# always drawn; units 1-4 are a stepped heap of gathered grass that grows per stage (each contains the last), with red
# berries on its left flank and brown nuts on its top; the full heap carries one white daisy.
LEAVES = (((-2, 1), 4.2, 2.6, 'LEAF'), ((2, 2), 3.8, 2.4, 'LEAF'), ((0, -2), 4.4, 2.8, 'STRAW'))
MOUND = ((2.6, 1.6), (3.3, 2.4), (3.9, 3.1), (4.4, 3.9))      # radius, height of the heap per stage
MOUND_Z = -1


def forage_pile():
    b = kit.Block('forage_pile')
    plate = b.part('leaf_plate', 0)
    for (cx, cz), a, c, ramp in LEAVES:
        def leaf(cell, face, cx=cx, cz=cz, ramp=ramp):
            x, y, z = cell
            if face != 'up':
                return (ramp, 1)
            return (ramp, 1) if x == cx or (x - cx) == (z - cz) else (ramp, 3 if mix(x, z) < 6 else 2)   # midrib and vein
        material = kit.Material(ramp, pattern=leaf, bevel=False)
        for x in range(-7, 7):
            for z in range(-7, 7):
                if ((x + .5 - cx) / a) ** 2 + ((z + .5 - cz) / c) ** 2 <= 1 and (x, 0, z) not in plate.cells:
                    plate.cell((x, 0, z), material)

    def stock(cell, face):
        x, y, z = cell
        h = mix(x, y, z)
        if face == 'down':
            return ('LEAF', 0)
        if x <= -1 and h < 4:
            return ('RED', 3 if face == 'up' else 2)               # berries on the left flank
        if x >= 0 and face == 'up' and h > 11:
            return ('WOOD', 3 if h > 13 else 2)                    # nuts on the right of the top
        if h < 3:
            return ('STRAW', 3)                                    # dry stalks in the green
        return ('LEAF', (3 if face == 'up' else 2) + (1 if h > 13 else 0))
    grass = kit.Material('LEAF', pattern=stock, bevel=False)
    previous = set()
    for stage, (R, H) in enumerate(MOUND, 1):
        cells = {(x, y, z) for x in range(-6, 6) for z in range(-7, 5) for y in range(1, 6)
                 if ((x + .5) / R) ** 2 + ((z + .5 - MOUND_Z) / (R * .9)) ** 2 + ((y + .5 - 1) / H) ** 2 <= 1}
        part = b.part(f'stock_{stage}', stage)
        for cell in sorted(cells - previous):
            part.cell(cell, grass)
        previous |= cells
    top = max(y for x, y, z in previous if (x, z) == (0, MOUND_Z))
    daisy = b.part('daisy', 4)
    daisy.cell((0, top + 1, MOUND_Z), kit.Material('LEAF', step=2, bevel=False))
    flower = kit.Material('FLOWER', step=2, bevel=False,
                          pattern=lambda c, f: ('FLOWER', 1) if (c[0], c[2]) == (0, MOUND_Z) else 0)
    for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        daisy.cell((dx, top + 2, MOUND_Z + dz), flower)
    return b


LARGE_NESTS = {'large_yukkuri_nest': large_yukkuri_nest, 'large_wild_nest': large_wild_nest}
STYLES = {'large_yukkuri_nest': list(WOVEN)}


def variants():
    """Non-default styles of the 2x2 woven nest, shipped as runtime-peripheral attachments."""
    return {f'large_yukkuri_nest_{s}': (lambda s=s: large_yukkuri_nest(s)) for s in list(WOVEN)[1:]}


BEDS = {look: (lambda look=look: pet_bed(look)) for look in LOOKS}
FITTED = {f'pet_nest_fit_{m}': (lambda m=m: fitted_bed(m)) for m in range(1, 16)}
ALL = {**BEDS, **FITTED, **LARGE_NESTS, **variants(), 'predator_nest': predator_nest, 'forage_pile': forage_pile}
