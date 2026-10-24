"""Placed care props in vanilla block style (voxel.py): food bowl and its grain / wheat / grass fills, the pet cushion nest,
the 2x2 woven pet nest and wild grass nest, the sand toilet (clean and dirty) and the ceramic and covered toilets.

Every prop is built from 1/16 cells and textured with 16x16 tiles drawn below (vanilla-like low-contrast clustered pixels,
light from above, hue-shifted ramps; nothing copied from Minecraft). The two fills that hold a vanilla item keep that item's
own sprite on top of the heap as a reference (minecraft:textures/item/wheat.png, minecraft:textures/block/short_grass.png
in the plains tint); they are referenced at runtime, not copied. Units are 1/16 block, origin at the footprint centre.

Footprints, heights and entrances follow CareBlock collision (life/CareBlock.java) and the documented 2x2 nest interior.
"""
import math
from voxel import Voxels, Mat, Atlas, T, canvas, put, rng_of, rgb, rows_tile
from PIL import Image

# ---- ramps (dark -> light) ------------------------------------------------------------------------------------------
GLAZE = ['#8a7a5c', '#b3a17f', '#d2c3a2', '#e8dcc0', '#f7f0dc']        # cream glazed ceramic
MINT = ['#2d6656', '#438a76', '#5fab94', '#86c9b2', '#b3e3d2']         # mint glaze band / hood
CLAY = ['#5c3d2b', '#7d5639', '#9c6f4b', '#b98c63']                    # unglazed foot ring
PELLET = ['#4a2610', '#6e3a14', '#a5602a', '#d18f45', '#efbf6e']       # regular oil-grain kibble (supplies.py family)
STRAW = ['#6b5420', '#957528', '#bf9a35', '#dcbd52', '#f0dc84']        # loose wheat stalks
CLIP = ['#3b5c22', '#4f772b', '#679437', '#82b048', '#a3c867']         # cut grass clippings (toward the plains grass tint)
BERRY = ['#6a2033', '#8f2d44', '#b83f58', '#d65f74', '#ec8e9a']        # strawberry wool
CREAM = ['#9d8c70', '#c4b393', '#ddd0b2', '#efe6cf', '#fbf6e8']        # cream quilted wool
LEAF = ['#24401a', '#36591f', '#4b7527', '#679534', '#86b246']         # fresh leaf litter
DRY = ['#5a3416', '#7d4c1e', '#a26a2a', '#c48c3c', '#dcb05a']          # dry leaves
MOSS = ['#2f4515', '#46621c', '#5d7d25', '#7a9a33']
HAY = ['#6e5718', '#9a7c22', '#c2a02e', '#dcc048', '#ecd878']
BARK = ['#3a2a18', '#54402a', '#6d5638', '#86704c']
DIRT = ['#4a3322', '#634630', '#7b5a3f']
TEAL = ['#25504c', '#356e68', '#4a8d85', '#68aba2', '#94cec5']         # sand-toilet plastic frame
SAND = ['#a88f60', '#c5ad7f', '#dbc79b', '#eadab5', '#f6ebcd']
STAIN = ['#7d6b4c', '#958161', '#ab9776']                              # damp, clumped sand
ANKO = ['#3a2316', '#54331f', '#70472b', '#8b5e3a', '#a97c55']         # anko-brown droppings


# ---- tile painters -----------------------------------------------------------------------------------------------------
def clustered(ramp, base, key, amount=.3, run=(1, 3), up=.5, spread=1, bias=None):
    """Vanilla-like surface: a base step with short horizontal runs one step darker or lighter."""
    img = canvas(ramp, base)
    r = rng_of(key)
    for y in range(T):
        x = 0
        while x < T:
            n = r.randint(*run)
            if r.random() < amount:
                d = r.randint(1, spread) * (1 if r.random() < up else -1)
                step = max(0, min(len(ramp) - 1, base + d))
                for k in range(n):
                    put(img, x + k, y, ramp[step])
            x += n
    return img


def glaze_tile(base, key):
    return clustered(GLAZE, base, key, amount=.22, run=(1, 2))


def inner_wall_tile(key):
    """Inner glaze: lit lip row under the rim, then a darker body with sparse glaze pits so the wall is not flat."""
    img = clustered(GLAZE, 2, key, amount=.3, run=(1, 2), up=.35)
    r = rng_of(key + 'p')
    for y in range(T):
        for x in range(T):
            if y == 0:
                put(img, x, y, GLAZE[3] if r.random() < .8 else GLAZE[4])
            elif r.random() < .07:
                put(img, x, y, GLAZE[1])
    return img


def band_tile():
    """Mint band: every column alternates two steps with a light dot every fourth texel, readable in a one-row strip."""
    img = clustered(MINT, 2, 'band', amount=.25, run=(1, 2))
    for y in range(T):
        for x in range(T):
            if (x + 2 * (y % 2)) % 4 == 0:
                put(img, x, y, MINT[4] if y % 2 == 0 else MINT[3])
    return img


def kibble_tile(ramp, key, light=0):
    """Feed pellets seen from above at one texel per unit: a mid-tone bed of pellets, each marked by one highlight dot
    with a shadow texel below right (the composter-contents way of drawing many small things)."""
    img = clustered(ramp, 1, key + 'b', amount=.35, run=(1, 2), up=.5)
    r = rng_of(key)
    for gy in range(0, T, 3):
        for gx in range(0, T, 3):
            if r.random() < .12 - light:
                continue
            x, y = gx + (gy // 3) % 2 + r.randint(0, 1), gy + r.randint(0, 1)
            put(img, x, y, ramp[4])
            put(img, x + 1, y, ramp[3])
            put(img, x, y + 1, ramp[3])
            put(img, x + 1, y + 1, ramp[0])
    return img


def straw_tile(key):
    """Loose wheat: short stalk strokes lying crossways on a shaded bed, each ending in a light two-texel ear tip."""
    img = clustered(STRAW, 1, key + 'b', amount=.35, run=(1, 2))
    r = rng_of(key)
    for _ in range(30):
        x, y = r.randrange(T), r.randrange(T)
        dx, dy = r.choice(((1, 1), (1, -1), (-1, 1), (1, 1), (1, -1), (1, 0)))
        n = r.randint(2, 4)
        for k in range(n):
            put(img, x + k * dx, y + k * dy, STRAW[3] if k % 2 == 0 else STRAW[2])
        put(img, x + n * dx, y + n * dy, STRAW[4])
        put(img, x + (n + 1) * dx, y + (n + 1) * dy, STRAW[4])
        put(img, x - dx, y - dy + 1, STRAW[0])
    return img


def clippings_tile(key):
    """Short cut grass blades in mixed greens over darker gaps."""
    img = clustered(CLIP, 1, key + 'b', amount=.35)
    r = rng_of(key)
    for _ in range(30):
        x, y = r.randrange(T), r.randrange(T)
        length = r.randint(2, 4)
        dx = r.choice((0, 1, -1))
        shade = r.choice((2, 3, 3, 4))
        for k in range(length):
            put(img, x + (k * dx) // 2, y - k, CLIP[shade if k else max(2, shade - 1)])
    return img


def wool_tile(ramp, base, key):
    """Soft wool: two-texel runs, low contrast, no strong pattern (vanilla wool density)."""
    return clustered(ramp, base, key, amount=.42, run=(2, 3), up=.45)


def quilt_tile(key, period=8):
    """Cream quilted wool with darker stitched seams every `period` texels and a lighter puff in each square."""
    img = wool_tile(CREAM, 3, key)
    for y in range(T):
        for x in range(T):
            if (x + period // 2) % period == 0 or (y + period // 2) % period == 0:
                if (x + y) % 2 == 0:
                    put(img, x, y, CREAM[1])
                else:
                    put(img, x, y, CREAM[2])
            elif ((x + period // 2) % period, (y + period // 2) % period) in ((2, 2), (3, 2), (2, 3)):
                put(img, x, y, CREAM[4])
    return img


def weave_tile(ramp, key):
    """Basket weave: 4x4 cells alternate horizontal and vertical strands, lit top / left, shaded bottom / right."""
    img = canvas(ramp, 2)
    for cy in range(0, T, 4):
        for cx in range(0, T, 4):
            horizontal = ((cx + cy) // 4) % 2 == 0
            for y in range(4):
                for x in range(4):
                    a, b = (y, x) if horizontal else (x, y)
                    step = 3 if a == 0 else 1 if a == 3 else 2
                    if b == 0 and step == 2:
                        step = 1
                    put(img, cx + x, cy + y, ramp[step])
    r = rng_of(key)
    for _ in range(14):
        x, y = r.randrange(T), r.randrange(T)
        put(img, x, y, ramp[4] if r.random() < .5 else ramp[1])
    return img


def leaf_tile(ramps, key, base=2):
    """Leaf litter: small 2-texel leaves one step lighter or darker than the base, a few dark gaps (vanilla leaf contrast)."""
    img = clustered(ramps[0], base, key + 'g', amount=.3, run=(1, 2), up=.3)
    r = rng_of(key)
    for _ in range(34):
        ramp = r.choice(ramps)
        x, y = r.randrange(T), r.randrange(T)
        b = base + r.choice((0, 1, 1))
        put(img, x, y, ramp[min(len(ramp) - 1, b + 1)])
        put(img, x + 1, y, ramp[b])
        put(img, x + r.choice((0, 1)), y + 1, ramp[max(0, b - 1)])
    for _ in range(6):
        put(img, r.randrange(T), r.randrange(T), ramps[0][1])
    return img


def hay_tile(key, side=False):
    img = clustered(HAY, 2, key, amount=.45, run=(1, 2))
    r = rng_of(key + 's')
    for _ in range(20):
        x, y = r.randrange(T), r.randrange(T)
        for k in range(r.randint(2, 4)):
            put(img, x + (k if side else 0), y + (0 if side else k), HAY[4] if k == 0 else HAY[3])
    return img


def bark_tile(key):
    img = clustered(BARK, 2, key, amount=.45, run=(1, 3), up=.4)
    r = rng_of(key + 'k')
    for _ in range(10):
        x, y = r.randrange(T), r.randrange(T)
        for k in range(r.randint(2, 4)):
            put(img, x + k, y, BARK[0])
    return img


def plastic_tile(base, key, amount=.12):
    return clustered(TEAL, base, key, amount=amount, run=(1, 2), up=.7)


def sand_tile(key, dirty=False):
    img = clustered(SAND, 2, key, amount=.5, run=(1, 1), spread=1)
    if dirty:
        r = rng_of(key + 'd')
        for _ in range(7):                      # damp clumps: little dark lumps with a lit top edge
            x, y = r.randrange(T), r.randrange(T)
            put(img, x, y, STAIN[2])
            put(img, x + 1, y, STAIN[1])
            put(img, x, y + 1, STAIN[1])
            put(img, x + 1, y + 1, STAIN[0])
        for _ in range(10):
            put(img, r.randrange(T), r.randrange(T), STAIN[r.randrange(3)])
    return img


def anko_tile():
    """Glossy anko: diagonal swirl stripes with a light glint."""
    img = canvas(ANKO, 2)
    for y in range(T):
        for x in range(T):
            d = (x + y) % 6
            put(img, x, y, ANKO[3] if d == 0 else ANKO[1] if d == 3 else ANKO[2])
    for x, y in ((2, 2), (9, 5), (13, 11), (5, 12)):
        put(img, x, y, ANKO[4])
    return img


# ---- shared helpers ------------------------------------------------------------------------------------------------------
def tiles(v, spec):
    """spec: key -> image; registers tiles and returns Mat(name, faces -> key)."""
    for key, image in spec.items():
        v.atlas.add(key, image)


def disc(r):
    return lambda cx, cz: math.hypot(cx, cz) <= r


def cells_in(lo, hi):
    for i in range(lo, hi):
        for k in range(lo, hi):
            yield i, k, i + .5, k + .5


def ring_of(inside, cells, four=False):
    """Cells of a footprint with any of their 8 (or 4) neighbours outside it."""
    s = set(cells)
    near = ((1, 0), (-1, 0), (0, 1), (0, -1)) if four else [(a, b) for a in (-1, 0, 1) for b in (-1, 0, 1)]
    return {(i, k) for (i, k) in s if any((i + a, k + b) not in s for a, b in near)}


VANILLA_UV = [(0, 1), (1, 1), (1, 0), (0, 0)]


def sprite(p, part, centre, size, yaw, up=False, tilt=(0.0, 0.0)):
    """Square vanilla sprite lying (or standing, up=True) at centre: one quad lit from above (props render unculled)."""
    cx, cy, cz = centre
    h = size / 2
    pts = []
    for sx, sz in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        a, b = sx * h, sz * h
        x, y, z = (a, b + h, 0.0) if up else (a, a * tilt[0] + b * tilt[1], b)
        pts.append([cx + x * math.cos(yaw) - z * math.sin(yaw), cy + y, cz + x * math.sin(yaw) + z * math.cos(yaw)])
    p.quad(part, pts, None, uvs=VANILLA_UV, normal=[0, 1, 0])


# =====================================================================================================================
# Food bowl: a glazed dish 14 across and 3 high on an octagonal plan (square with three-cell corner cuts), like a vanilla
# block seen from above. Clay foot (y 0-1, under the floor), mint band (y 1-2), cream rim (y 2-3); the interior floor is at
# y 1. Fills are a textured heap on a quarter-unit layer grid: a centre patch after one portion, about half the floor after
# four, the whole floor slightly domed above the rim after eight, with a few loose bits on each portion's surface.
FLOOR_Y = 1
FILL_STEP = .25
CUT = 3


def octagon(lo, hi, cut):
    """Cells of the square lo..hi-1 with `cut`-cell stepped corner cuts."""
    return [(i, k) for i in range(lo, hi) for k in range(lo, hi)
            if min(i - lo, hi - 1 - i) + min(k - lo, hi - 1 - k) >= cut]


def glaze_mats(v):
    tiles(v, {'glaze_rim': glaze_tile(4, 'rim'), 'glaze_side': glaze_tile(3, 'side'), 'glaze_in': inner_wall_tile('in'),
              'glaze_floor': glaze_tile(2, 'floor'), 'band': band_tile(),
              'clay': clustered(CLAY, 2, 'clay', .35), 'clay_under': clustered(CLAY, 1, 'clayu', .3)})
    rim = Mat('glazed_rim', top='glaze_rim', side='glaze_side', inner='glaze_in', bottom='glaze_in')
    band = Mat('mint_band', top='glaze_rim', side='band', inner='glaze_in', bottom='clay_under')
    base = Mat('glazed_floor', top='glaze_floor', side='glaze_side', bottom='clay_under')
    foot = Mat('clay_foot', top='glaze_floor', side='clay', bottom='clay_under')
    return rim, band, base, foot


def fill_radius(k):
    """Heap footprint radius after k portions: a centre patch at one, about half the floor at four, the whole floor at eight."""
    return 1.9 + .63 * k


def fill_top(k, r=0.0):
    """Heap surface after k portions at radius r, on the quarter-unit layer grid; None outside the footprint. The edge
    rises with the portions and the centre stays domed (8 portions: edge about y 2.5, centre y 3.5)."""
    R = fill_radius(k)
    if r >= R:
        return None
    h = .25 + .18 * (k - 1) + (.15 + .11 * k) * (1 - (r / R) ** 2)
    return FLOOR_Y + max(FILL_STEP, math.ceil(h / FILL_STEP - 1e-6) * FILL_STEP)


def bowl_voxels(name, filled=False):
    ys = sorted({0.0, 1.0, 2.0, 3.0} | ({FLOOR_Y + FILL_STEP * n for n in range(1, 13)} if filled else set()))
    v = Voxels(name, ys=ys, vtop=3)
    rim, band, base, foot = glaze_mats(v)
    cells = octagon(-7, 7, CUT)
    wall = ring_of(None, cells)
    for i, k in cells:
        if (i, k) not in wall:
            v.set(i, 0, k, foot)
            continue
        for j, y in enumerate(ys[:-1]):
            if 1 <= y < 2:
                v.set(i, j, k, band)
            elif 2 <= y < 3:
                v.set(i, j, k, rim)
    return v, [c for c in cells if c not in wall]


def food_bowl():
    v, _ = bowl_voxels('food_bowl')
    return v.to_prop()


def fill_bowl(name, tile_key, tile_image, bits_tile=None, bits=3, seed='bits'):
    """Heap fill: a cell of a layer belongs to the first portion whose heap covers it at that column, so each portion
    widens and raises the heap; `bits` half-unit loose bits sit on each portion's surface (buried by later portions)."""
    v, inner = bowl_voxels(name, filled=True)
    v.atlas.add(tile_key, tile_image)
    mat = Mat(name.split('_')[-1] + '_fill', **{'*': tile_key})
    for i, k in inner:
        r = math.hypot(i + .5, k + .5)
        for j, y in enumerate(v.ys[:-1]):
            if y < FLOOR_Y:
                continue
            unit = next((n for n in range(1, 9) if (fill_top(n, r) or 0) >= v.ys[j + 1] - 1e-6), None)
            if unit is None:
                break
            v.set(i, j, k, mat, unit)
    if bits_tile:
        v.atlas.add(tile_key + '_bits', bits_tile)
        r = rng_of(seed)

        def add_bits(p):
            for n in range(1, 9):
                part = p.part(f'{name.split("_")[-1]}_bits_u{n}', n)
                spots = [c for c in inner if math.hypot(c[0] + .5, c[1] + .5) < fill_radius(n) * .75]
                for i, k in r.sample(spots, min(bits, len(spots))):
                    y = fill_top(n, math.hypot(i + .5, k + .5))
                    v.box(p, part, (i, y, k), (i + 1, y + .5, k + 1), tile_key + '_bits')
        v.extras.append(add_bits)
    return v


def surface(k, x, z):
    """Heap top under (x, z) after k portions (cell-centred like the voxels)."""
    i, kk = math.floor(x), math.floor(z)
    return fill_top(k, math.hypot(i + .5, kk + .5)) or FLOOR_Y


def food_bowl_regular():
    """Oil-grain kibble: a flat pellet layer with three loose pellets on each portion."""
    return fill_bowl('food_bowl_regular', 'kibble', kibble_tile(PELLET, 'kibble'),
                     clustered(PELLET, 3, 'loose', .5, run=(1, 1)), seed='loose-pellets').to_prop()


def food_bowl_wheat():
    """Loose wheat: a heap of crossed stalks with two whole wheat items (the vanilla item sprite, referenced) laid on each
    portion's surface."""
    v = fill_bowl('food_bowl_wheat', 'straw', straw_tile('straw'))

    def ears(p):
        for k in range(1, 9):
            part = p.part(f'laid_wheat_{k}', k, texture='minecraft:textures/item/wheat.png')
            for e in range(2):
                ang = k * 2.399963 + e * math.pi
                rr = min(fill_radius(k) * .45, 2.4) * (.5 + .5 * e)
                x, z = rr * math.cos(ang), rr * math.sin(ang)
                # rests on the highest point of the heap under it, so the dome never swallows the ear
                top = max(surface(k, x + a * 2.6, z + c * 2.6) for a in (-1, 0, 1) for c in (-1, 0, 1))
                sprite(p, part, (x, top + .08, z), 5.5, ang * 1.7 + e)
    v.extras.append(ears)
    return v.to_prop()


def food_bowl_forage():
    """Cut grass: a heap of clippings with one short-grass tuft (vanilla sprite, plains tint, referenced) per portion."""
    v = fill_bowl('food_bowl_forage', 'clippings', clippings_tile('clip'))

    def tufts(p):
        for k in range(1, 9):
            part = p.part(f'cut_grass_{k}', k, texture='minecraft:textures/block/short_grass.png', tint=[145, 189, 89])
            ang = k * 2.399963 + .6
            rr = min(fill_radius(k) * .4, 3.4)
            x, z = rr * math.cos(ang), rr * math.sin(ang)
            for off in (0, math.pi / 2):
                sprite(p, part, (x, surface(k, x, z) - .3, z), 3.6, k * 1.1 + off, up=True)
    v.extras.append(tufts)
    return v.to_prop()


# =====================================================================================================================
# Pet cushion nest (1x1 block, overhangs to 20 across like the old cushion; CareBlock.nestShape): an octagonal strawberry
# wool bolster two cells wide and 3 high with straight runs, round a cream quilted cushion at y 2; across the north (-z)
# entrance the bolster drops to the cushion.
NEST_CUT = 5


def depth_map(cells):
    """Ring depth of each cell: 1 = touches the outside (4-neighbour, one cell per diagonal step), 2 = next ring in, ..."""
    left, depth, d = set(cells), {}, 1
    while left:
        ring = ring_of(None, left, four=True)
        for c in ring:
            depth[c] = d
        left -= ring
        d += 1
    return depth


def yukkuri_nest():
    v = Voxels('yukkuri_nest', vtop=3)
    v.diagonal = lambda px, pz: min(abs(px), abs(pz)) > 10 - NEST_CUT - 1
    tiles(v, {'berry_top': wool_tile(BERRY, 3, 'bt'), 'berry_side': wool_tile(BERRY, 3, 'bs'), 'berry_under': wool_tile(BERRY, 2, 'bu'),
              'quilt': quilt_tile('nq')})
    wool = Mat('strawberry_wool', top='berry_top', side='berry_side', bottom='berry_under')
    cushion = Mat('quilted_cushion', top='quilt', side='berry_side', bottom='berry_under')
    for (i, k), d in depth_map(octagon(-10, 10, NEST_CUT)).items():
        v.set(i, 0, k, wool)
        if d > 2:
            v.set(i, 1, k, cushion)
            continue
        v.set(i, 1, k, wool)
        if not (k < 0 and -4 <= i < 4):          # north entrance: the bolster drops to the cushion
            v.set(i, 2, k, wool)
    return v.to_prop()


# =====================================================================================================================
# 2x2 family nests. 31 cells across (outline 15.5 from the centre), entrance on +z. One-cell rim on a rounded square
# (squircle) leaves a 29-cell interior (about 1.8 blocks); the rim drops to the floor across 14 cells of the front.
LARGE_HALF = 15.0           # squircle radius through the outermost cell centres


def squircle_norm(x, z):
    return (abs(x) ** 4 + abs(z) ** 4) ** .25


def large_cells():
    cells = [(i, k) for i in range(-15, 16) for k in range(-15, 16) if squircle_norm(i, k) <= LARGE_HALF + .05]
    return cells, ring_of(None, cells)


def front_height(x, full):
    """Rim height across the +z front: open (floor) for |x| < 7, low shoulders to |x| < 11, then full."""
    a = abs(x)
    return 1 if a < 7 else 2 if a < 11 else full


def large_yukkuri_nest():
    """Woven strawberry basket rim (3 high) around a cream quilted wool floor (y 1); no marked sleeping places."""
    v = Voxels('large_yukkuri_nest', ox=-.5, oz=-.5, vtop=3)
    tiles(v, {'weave': weave_tile(BERRY, 'w'), 'weave_top': wool_tile(BERRY, 3, 'wt'), 'berry_under': wool_tile(BERRY, 1, 'bu'),
              'quilt': quilt_tile('lq')})
    weave = Mat('woven_rim', top='weave_top', side='weave', bottom='berry_under')
    floor = Mat('quilted_floor', top='quilt', side='weave', bottom='berry_under')
    cells, ring = large_cells()
    shoulder = ring_of(None, [c for c in cells if c not in ring])
    for i, k in cells:
        front = k > 0 and abs(i) < 11
        if (i, k) not in ring:
            v.set(i, 0, k, floor)
            if (i, k) in shoulder and not front:          # one-high inner shoulder so the bolster reads two cells thick
                v.set(i, 1, k, weave)
            continue
        h = front_height(i, 3) if front else 3
        for j in range(h):
            v.set(i, j, k, weave if h > 1 else floor)
    return v.to_prop()


def large_wild_nest():
    """Wild family bedding: a woven dry-grass roll and leaf-litter pad from the wild nest family (wild.py, wild_kit atlas)."""
    import wild
    return wild.large_wild_nest()


# =====================================================================================================================
# Droppings: one stepped anko-brown swirl (3 x 3, 2 x 2, tip), shared by the dirty sand toilet (drawn in place) and the
# styled toilets (toilet_waste, which CareRenderer draws once per stored dropping at half scale).
SWIRL = ((0, 0, 3), (0, 0, 2), (1, 1, 1))


def anko_mat(v):
    v.atlas.add('anko', anko_tile())
    v.atlas.add('anko_top', clustered(ANKO, 3, 'at', .4, run=(1, 2)))
    return Mat('anko_swirl', top='anko_top', side='anko', bottom='anko')


def add_swirl(v, x0, j0, z0, mat, tiers=SWIRL):
    for j, (dx, dz, n) in enumerate(tiers):
        for i in range(n):
            for k in range(n):
                v.set(x0 + dx + i, j0 + j, z0 + dz + k, mat)


def toilet_waste():
    v = Voxels('toilet_waste', ox=-.5, oz=-.5, vtop=3)
    add_swirl(v, -1, 0, -1, anko_mat(v))
    return v.to_prop()


# Sand toilet (CareBlock TOILET box 12.05 x 9.65, 2.35 high): a mint-teal plastic tray 12 x 10 with a 3-high rim and sand at
# y 2. The dirty state stains the sand with damp clumps and shows one swirl and a low lump.
def toilet_voxels(name, dirty):
    v = Voxels(name, vtop=3, centre=(0.0, 0.0))
    tiles(v, {'plastic_top': plastic_tile(4, 'pt'), 'plastic_side': plastic_tile(2, 'ps'), 'plastic_in': plastic_tile(1, 'pi'),
              'plastic_under': plastic_tile(0, 'pu'), 'sand': sand_tile('sand'), 'sand_dirty': sand_tile('sandd', dirty=True)})
    frame = Mat('plastic_tray', top='plastic_top', side='plastic_side', inner='plastic_in', bottom='plastic_under')
    sand = Mat('sand', top='sand_dirty' if dirty else 'sand', side='plastic_in', bottom='plastic_under')
    for i in range(-6, 6):
        for k in range(-5, 5):
            edge = i in (-6, 5) or k in (-5, 4)
            v.set(i, 0, k, frame)
            v.set(i, 1, k, frame if edge else sand)
            if edge:
                v.set(i, 2, k, frame)
    if dirty:
        swirl = anko_mat(v)
        add_swirl(v, 0, 2, -2, swirl)
        add_swirl(v, -4, 2, 1, swirl, ((0, 0, 2),))
    return v


def yukkuri_toilet():
    return toilet_voxels('yukkuri_toilet', False).to_prop()


def yukkuri_toilet_dirty():
    return toilet_voxels('yukkuri_toilet_dirty', True).to_prop()


# =====================================================================================================================
# Ceramic and covered toilets: the bowl's octagon, cream glaze and mint band as a 14 x 14 tray with a front (-z) opening.
# Floors stay where CareRenderer draws the droppings: ceramic y 1, covered y 1.5. The covered lid is two mint tiers (the
# band's mint) resting directly on the rim over the back half, the lower tier overhanging the tray by one cell.
def ceramic_tray(name, ys=None, floor_layers=1):
    top = 3 if ys is None else ys[floor_layers + 2]
    v = Voxels(name, ys=ys, vtop=top)
    rim, band, base, foot = glaze_mats(v)
    cells = octagon(-7, 7, CUT)
    ring = ring_of(None, cells)
    for i, k in cells:
        v.set(i, 0, k, foot if (i, k) in ring else base)
        for j in range(1, floor_layers):
            v.set(i, j, k, base)
        if (i, k) in ring:
            if k == -7 and -3 <= i < 3:            # front opening down to the floor
                continue
            v.set(i, floor_layers, k, band)
            v.set(i, floor_layers + 1, k, rim)
    return v


def ceramic_toilet():
    return ceramic_tray('ceramic_toilet').to_prop()


def covered_toilet():
    ys = [0, 1, 1.5, 2.5, 3.5, 4.5, 5.5]
    v = ceramic_tray('covered_toilet', ys=ys, floor_layers=2)
    v.atlas.add('mint_lid', clustered(MINT, 2, 'lid', .25, run=(1, 2)))
    lid = Mat('mint_lid', top='mint_lid', side='band', bottom='band')
    for i, k in octagon(-8, 8, 4):                      # lower tier on the rim over the back half, one cell of overhang
        if k >= 0:
            v.set(i, 4, k, lid)
    for i, k in octagon(-6, 6, 3):                      # upper tier, set back
        if k >= 2:
            v.set(i, 5, k, lid)
    return v.to_prop()


PROPS = {'food_bowl': food_bowl, 'food_bowl_regular': food_bowl_regular, 'food_bowl_wheat': food_bowl_wheat,
         'food_bowl_forage': food_bowl_forage, 'yukkuri_nest': yukkuri_nest, 'yukkuri_toilet': yukkuri_toilet,
         'yukkuri_toilet_dirty': yukkuri_toilet_dirty, 'toilet_waste': toilet_waste}
LARGE = {'large_yukkuri_nest': large_yukkuri_nest, 'large_wild_nest': large_wild_nest}
TRAYS = {'ceramic_toilet': ceramic_toilet, 'covered_toilet': covered_toilet}
