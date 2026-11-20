"""Playground equipment as real, smooth shapes (smooth.py) in the inventory icons' colours, and the matching physics.

16 units = one block, +Y up; a facility is entered from -Z and fits its 3 x 2 x 3 (slide 3 x 2 x 5) block footprint.
Surfaces are swept, revolved or extruded with bevels and fillets and painted flat from the icon ramps of
item-icons/draw.py (a gradient strip where depth shades, a decal for printed detail). Collision is computed from the
same dimensions as the surfaces. Gameplay heights stay where PlayLife's routes expect them: tunnel floor 3 (route 2,
within its 0.6-block reach), slide steps 4/8/12/16 and deck 16, trampoline bed 8 and rim 9.

ceramic_tunnel: a glazed clay pipe lying along Z, round outside on a flat foot, a bell socket at the far (+Z) end and
rounded mouths; the bore is round over a flat cast floor at y 3 (14 units wide there, 24.5 high), darkening with depth.
yukkuri_slide: a toddler slide: four steps with rounded nosings between two side panels with handrails, a teal chute
whose bed is also the deck plate, high guard walls round the deck lowering along the chute, a curved run-out lip.
yukkuri_trampoline: a round mini trampoline: a gold pad over the springs with a skirt, a steel ring on six legs with
rubber feet, and a dark mat with a stitched border. The mat is part `bed` (unit 2, pivot at y 8) which the renderer
presses down by up to 2 units; under it a static cone of mat (`well`) slopes from the pad lip to 2.3 below the bed at
r 5, so a pressed bed shows a dish-shaped dip from the pad edge to the flat centre, with no gap.
yukkuri_pool (the orange-juice tub, id kept for saves): a low white-enamel oval washtub with a navy rolled rim and side
lugs, a bench step inside at the -X end for small bodies, and a wooden step stool outside at its front corner.
yukkuri_pool_juice: the juice alone, one opaque surface per level (juice_u1..juice_u4, units 1..4); drawing units 1..k
shows a tub k levels full. data/mcyukkuri/playgrounds/yukkuri_pool.json carries the tub numbers for gameplay.
--check verifies every export.
"""
import argparse, importlib.util, json, math, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from asset_export_checks import check_output, fingerprints, preserve_equivalent_png, preserve_negative_zero_mesh_json, provenance


def _load(name, path):
    if name not in sys.modules:
        spec = importlib.util.spec_from_file_location(name, path)
        sys.modules[name] = module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    return sys.modules[name]


KIT = HERE.parent / 'block-props/kit.py'
SMOOTH = HERE / 'smooth.py'
kit = _load('block_props_kit', KIT)          # loaded under its own name: care-props also has a `kit`
S = _load('playground_smooth', SMOOTH)
SNAPSHOT = ROOT / 'art/props/playgrounds'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
DATA = ROOT / 'src/main/resources/data/mcyukkuri'
TAU = math.tau


def atlas():
    return S.Atlas(kit.ramp)


def swatch(model, ramp, step):
    key = model.atlas.swatch(ramp, step)
    return lambda *_: (key,)


def boxes(cells):
    """Greedy merge of unit cells into [x0, y0, z0, x1, y1, z1] boxes (x runs, then z rows, then y slabs)."""
    left, result = set(cells), []
    for x, y, z in sorted(cells, key=lambda c: (c[1], c[2], c[0])):
        if (x, y, z) not in left:
            continue
        x1 = x
        while (x1 + 1, y, z) in left:
            x1 += 1
        z1 = z
        while all((i, y, z1 + 1) in left for i in range(x, x1 + 1)):
            z1 += 1
        y1 = y
        while all((i, y1 + 1, k) in left for i in range(x, x1 + 1) for k in range(z, z1 + 1)):
            y1 += 1
        for i in range(x, x1 + 1):
            for j in range(y, y1 + 1):
                for k in range(z, z1 + 1):
                    left.discard((i, j, k))
        result.append([x, y, z, x1 + 1, y1 + 1, z1 + 1])
    return result


def columns(heights):
    """Greedy merge of unit plan cells {(x, z): (y0, y1)} with equal spans into [x0, y0, z0, x1, y1, z1] boxes."""
    left, result = dict(heights), []
    for x, z in sorted(heights, key=lambda c: (c[1], c[0])):
        if (x, z) not in left:
            continue
        span = left[(x, z)]
        x1 = x
        while left.get((x1 + 1, z)) == span:
            x1 += 1
        z1 = z
        while all(left.get((i, z1 + 1)) == span for i in range(x, x1 + 1)):
            z1 += 1
        for i in range(x, x1 + 1):
            for k in range(z, z1 + 1):
                del left[(i, k)]
        result.append([x, round(span[0], 4), z, x1 + 1, round(span[1], 4), z1 + 1])
    return result


# ---- ceramic tunnel ---------------------------------------------------------------------------------------------------
CY, RO, RB, FLOOR = 14.5, 15.0, 13.0, 3.0      # axis height, outer and bore radius, cast floor top
RS = 16.3                                        # bell socket radius
TZ0, TZ1 = -22.0, 22.0
BELL = (14.2, 16.2)                              # z span of the bell's shoulder
ARC, CHORD = 48, 6


def pipe_section(R, clip):
    """A lying pipe section [(x, y)]: the circle of radius R round (0, CY) above y = clip, from its right corner over
    the top to its left corner, then back along the flat chord. A circle that clears `clip` gathers the chord points at
    its lowest point, so every section has the same points."""
    s = (clip - CY) / R
    a0 = -math.pi / 2 if s <= -1 else math.asin(s)
    arc = [(R * math.cos(a), CY + R * math.sin(a)) for a in (a0 + (math.pi - 2 * a0) * k / ARC for k in range(ARC + 1))]
    (rx, ry), (lx, ly) = arc[0], arc[-1]
    return arc + [(S.lerp(lx, rx, k / CHORD), S.lerp(ly, ry, k / CHORD)) for k in range(1, CHORD)]


def quarter(z_face, r_face, z_full, r_full, steps=5):
    """(z, R) rows of a rounded mouth edge: from the face (z_face, r_face) to the barrel (z_full, r_full)."""
    out = []
    for k in range(steps + 1):
        a = math.pi / 2 * k / steps
        out.append((S.lerp(z_full, z_face, math.cos(a)), S.lerp(r_face, r_full, math.sin(a))))
    return out


def ceramic_tunnel():
    m = S.Model('ceramic_tunnel', atlas())
    pipe = m.part('pipe')
    a = m.atlas
    clay, mouth, foot = a.swatch('TERRA', 3), a.swatch('TERRA', 4), a.swatch('TERRA', 1)
    bore = a.gradient('bore', [(0, ('TERRA', 2)), (.14, ('TERRA', 1)), (.4, ('TERRA', 0)), (.75, ('ANKO', 1)), (1, ('ANKO', 1))])
    # Outside: rounded -Z mouth, barrel, bell shoulder, socket, rounded +Z mouth.
    shoulder = [(S.lerp(BELL[0], BELL[1], t), S.lerp(RO, RS, t * t * (3 - 2 * t))) for t in (k / 6 for k in range(7))]
    outer = (quarter(TZ0, RO - .8, TZ0 + .8, RO)[:-1] + [(TZ0 + .8, RO)] + shoulder
             + quarter(TZ1, RS - .8, TZ1 - .8, RS)[::-1])
    rows = [[(x, y, z) for x, y in pipe_section(R, 0.0)] for z, R in outer]
    m.strip(pipe, rows, lambda i, j, p: (foot if j >= ARC else clay,), lambda i, j, c: (c[0], c[1] - CY, 0.0),
            closed=True, crease=25)
    # Bore: rounded inner edges, the floor and wall darkening from either mouth to the middle.
    zs = [TZ0 + .6 * (1 - math.cos(math.pi / 2 * k / 4)) for k in range(5)]
    inner = ([(z, RB + .6 * (1 - math.sin(math.pi / 2 * k / 4))) for k, z in enumerate(zs)]
             + [(z, RB) for z in (-14.0, -7.0, 0.0, 7.0, 14.0)]
             + [(-z, RB + .6 * (1 - math.sin(math.pi / 2 * k / 4))) for k, z in reversed(list(enumerate(zs)))])
    rows = [[(x, y, z) for x, y in pipe_section(R, FLOOR)] for z, R in inner]
    m.strip(pipe, rows, lambda i, j, p: (bore, min(p[2] - TZ0, TZ1 - p[2]) / 22), lambda i, j, c: (-c[0], CY - c[1], 0.0),
            closed=True, crease=25)
    # Mouth faces: the -Z spigot ring and the thicker +Z socket ring.
    for z, ro, sign in ((TZ0, RO - .8, -1), (TZ1, RS - .8, 1)):
        ring = [[(x, y, z) for x, y in pipe_section(ro, 0.0)], [(x, y, z) for x, y in pipe_section(RB + .6, FLOOR)]]
        m.strip(pipe, ring, lambda *_: (mouth,), lambda *_: (0.0, 0.0, float(sign)), closed=True, smooth=False)
    cells = set()
    for z in range(int(TZ0), int(TZ1)):
        R = RS if z + .5 >= (BELL[0] + BELL[1]) / 2 else RO
        for x in range(-17, 17):
            for y in range(0, 32):
                d = (x + .5) ** 2 + (y + .5 - CY) ** 2
                if d <= R * R and not (d < RB * RB and y + .5 > FLOOR):
                    cells.add((x, y, z))
    return m, boxes(cells), {}


# ---- slide ------------------------------------------------------------------------------------------------------------
STEPS = ((-36, 4), (-28, 8), (-20, 12), (-12, 16))   # (front z, top) of each 8-deep step; the last runs to the deck
DECK = (-4.0, 16.0)                                   # deck front z and top: the chute bed starts here
K = 15 / 34                                           # chute drop per unit, 16 at z 2 to 1 at z 36 as before
RUN = (31.0, 38.0)                                    # the run-out curves from the slope to a flat lip
LIP_Y = 16 - (RUN[0] - 2) * K - K * (RUN[1] - RUN[0]) / 2
CHUTE_END = 40.0


def chute_y(z):
    """Bed top of the chute (and deck): flat over the deck, a short round-over, the straight slope, the run-out."""
    if z <= 1:
        return 16.0
    if z <= 3:
        return 16 - K * (z - 1) ** 2 / 4
    if z <= RUN[0]:
        return 16 - (z - 2) * K
    if z <= RUN[1]:
        return LIP_Y + K / (2 * (RUN[1] - RUN[0])) * (z - RUN[1]) ** 2
    return LIP_Y


def guard(z):
    """Height of the chute's side walls above its bed: 6 round the deck, easing to 4 down the chute."""
    t = max(0.0, min(1.0, (z - 0.0) / 6.0))
    return 6.0 - 2.0 * t * t * (3 - 2 * t)


LIP = .75                     # radius of the chute's rolled lips and (a hair thicker) the handrails
RAIL = DECK[1] + guard(-10) - LIP   # handrail height over the top step: the centre of the deck guard's lip


def rail_path():
    """Handrail centre line (z, y): a post at the stair foot, along the stair line parallel to the nosings, level over the
    top step, then running into the deck guard's lip on the same axis."""
    up = RAIL - 16
    raw = [(-37.0, 0.4), (-37.0, 4 + up - 1.5), (-36.0, 4 + up), (-12.0, RAIL), (DECK[0] + .6, RAIL)]
    return S.fillet(raw, 2.2, steps=6)


def rail_y(z):
    path = rail_path()
    for (z0, y0), (z1, y1) in zip(path, path[1:]):
        if z0 <= z <= z1 and z1 > z0:
            return S.lerp(y0, y1, (z - z0) / (z1 - z0))
    return path[-1][1]


def extrude_x(model, part, profile, x0, x1, paint, caps=None, crease=30):
    """A (z, y) profile (closed, counter-clockwise) swept from x0 to x1; paint(edge index) -> uv spec; caps: uv spec."""
    rows = [[(x, y, z) for z, y in profile] for x in (x0, x1)]
    n = len(profile)

    def facing(i, j, c):
        (za, ya), (zb, yb) = profile[j], profile[(j + 1) % n]
        return (0.0, -(zb - za), yb - ya)
    model.strip(part, rows, lambda i, j, p: paint(j), facing, closed=True, crease=crease)
    if caps:
        for x, sign in ((x0, -1), (x1, 1)):
            model.polygon(part, [(x, y, z) for z, y in profile], (float(sign), 0.0, 0.0), lambda p: caps)


def yukkuri_slide():
    m = S.Model('yukkuri_slide', atlas())
    a = m.atlas
    iron = {k: a.swatch('IRON', k) for k in range(5)}
    teal = {k: a.swatch('TEAL', k) for k in range(5)}
    # Steps and the tower under the deck: one profile with rounded nosings, swept across the 18-unit width.
    raw = [(-36, 0), (1.6, 0), (1.6, 14.4), (-3.9, 14.4), (-3.9, 15.9), (-4.0, 16.0)]
    radii = [0, 0, 0, 0, 0, 0]
    for k, (z, top) in enumerate(reversed(STEPS)):
        raw += [(z, top), (z, top - 4)] if z != -36 else [(z, top)]
        radii += [1.2, 0] if z != -36 else [1.2]
    profile = S.fillet(raw, 0, steps=4, closed=True, radii=radii)
    n = len(profile)

    def stair_paint(j):
        (za, ya), (zb, yb) = profile[j], profile[(j + 1) % n]
        if ya == yb == 0:
            return (iron[0],)
        if abs(ya - yb) < 1e-6 and ya in (4, 8, 12, 16):
            return (iron[3],)
        if za == zb and za in (-36, -28, -20, -12):
            return (iron[2],)
        if za == zb == 1.6 or ya == yb == 14.4:
            return (iron[1],)
        return (iron[4],) if ya > 0 and yb > 0 and abs(za - zb) > 1e-6 and abs(ya - yb) > 1e-6 else (iron[1],)
    extrude_x(m, m.part('steps'), profile, -9.2, 9.2, stair_paint, caps=(iron[2],))
    # Side panels with the handrail along their top edge.
    path = rail_path()
    panels, rails = m.part('panels'), m.part('handrails')
    end = DECK[0] + .15                          # the panels reach just inside the deck guard
    top = [p for p in path if p[1] >= path[1][1] - .01 and p[0] < end]
    outline = [(-37.0, 0.0), (end, 0.0), (end, rail_y(end))] + top[::-1] + [(-37.0, 0.4)]
    for s in (-1, 1):
        x0, x1 = sorted((s * 9.1, s * 10.4))
        for x, sign in ((x0, -1), (x1, 1)):
            m.polygon(panels, [(x, y, z) for z, y in outline], (float(sign), 0.0, 0.0), lambda p: (iron[2],))
        m.tube(rails, [(s * 9.75, y, z) for z, y in path], LIP + .05, lambda *_: (iron[4],), seg=10, caps=(False, True))
    # Chute: a U trough (rounded lips and inner corners) swept down its bed line; the deck plate is its flat start.
    def section(h):
        pts = [(-10.5, -1.5), (10.5, -1.5), (10.5, h), (9.0, h), (9.0, 0.0), (-9.0, 0.0), (-9.0, h), (-10.5, h)]
        return S.fillet(pts, 0, steps=3, closed=True, radii=[.6, .6, LIP, LIP, 1.5, 1.5, LIP, LIP])
    zs = sorted({DECK[0], -2.0, *[k * .5 for k in range(0, 13)], *range(8, int(RUN[0]), 3),
                 *[RUN[0] + k * .5 for k in range(int((RUN[1] - RUN[0]) * 2) + 1)], 39.0, CHUTE_END})
    rows = [[(x, chute_y(z) + y, z) for x, y in section(guard(z))] for z in zs]
    sec = section(4.0)
    ns = len(sec)

    def chute_facing(i, j, c):
        (xa, ya), (xb, yb) = sec[j], sec[(j + 1) % ns]
        return (yb - ya, -(xb - xa), 0.0)

    def chute_paint(i, j, p):
        (xa, ya), (xb, yb) = sec[j], sec[(j + 1) % ns]
        if min(ya, yb) > -.01 and max(abs(xa), abs(xb)) < 9.0 + 1e-6 and max(ya, yb) < 1.6:   # bed and its fillets
            return (teal[3],)
        if min(ya, yb) > 4.0 - LIP - .01 and min(abs(xa), abs(xb)) > 9.0 - 1e-6:           # rounded lips
            return (teal[4],)
        if max(ya, yb) < -.9:
            return (teal[1],)                                                              # underside
        return (teal[2],)
    chute = m.part('chute')
    m.strip(chute, rows, chute_paint, chute_facing, closed=True, crease=35)
    m.polygon(chute, rows[-1], (0.0, 0.0, 1.0), lambda p: (teal[2],))
    # Collision: the steps and deck as before, the chute bed sampled along its line, its walls, the side panels.
    collision = [[-9, 0, z, 9, top, z + 8] for z, top in STEPS[:-1]] + [[-9, 0, -12, 9, 16, 1]]
    for z in range(1, int(CHUTE_END)):
        lo = max(0.0, chute_y(z + 1) - 1.5)
        collision.append([-9, round(lo, 4), z, 9, round(chute_y(z), 4), z + 1])
    for z in range(int(DECK[0]), int(CHUTE_END)):
        lo = round(max(0.0, chute_y(z + 1) - 1.5), 4)
        hi = round(chute_y(z) + guard(z), 4)
        collision += [[-10.5, lo, z, -9, hi, z + 1], [9, lo, z, 10.5, hi, z + 1]]
    for z in range(-37, int(DECK[0])):
        hi = round(max(rail_y(z), rail_y(z + 1)) + LIP, 4)
        collision += [[-10.5, 0, z, -9, hi, z + 1], [9, 0, z, 10.5, hi, z + 1]]
    return m, collision, {}


# ---- trampoline -------------------------------------------------------------------------------------------------------
BED, RIM = 8.0, 9.0          # bed surface (PlayBlock.BED_HEIGHT) and pad top; nothing else in the collision tops at 8
BED_R, PAD_IN, PAD_OUT = 15.0, 14.6, 19.4
WELL = ((14.7, 7.96), (5.0, 5.66))        # static mat cone under the bed: shows when the bed is pressed (2 units max)
MAT_BANDS = (2.2, 3.0, 12.4, 12.9)        # centre spot, its ring, the stitch row and the bound edge start
LEGS = 6
LEG_R = 18.5
RING = 56                                 # segments round the trampoline


def mat_paint(model):
    ink = {k: model.atlas.swatch('INK', k) for k in range(5)}

    def paint(r, shade=0):
        """The mat's rings by radius; `shade` steps darker for the slope a pressed bed uncovers."""
        step = 2 if r < MAT_BANDS[0] else 3 if r < MAT_BANDS[1] else 2 if r < MAT_BANDS[2] else 4 if r < MAT_BANDS[3] else 3
        return (ink[max(0, step - shade)],)
    return paint


def yukkuri_trampoline():
    m = S.Model('yukkuri_trampoline', atlas())
    a = m.atlas
    gold = {k: a.swatch('GOLD', k) for k in range(4)}
    iron = {k: a.swatch('IRON', k) for k in range(5)}
    rubber = a.swatch('INK', 1)
    frame = m.part('frame')
    # Pad: lip resting on the mat edge, a flat top, a rounded outer piping edge and a skirt over the springs.
    raw = [(PAD_IN - .15, 7.7), (PAD_IN, 8.05), (15.6, RIM), (18.6, RIM), (PAD_OUT, 8.2), (PAD_OUT, 6.2), (19.0, 6.0),
           (18.7, 6.0), (18.7, 7.4), (PAD_IN - .15, 7.4)]
    pad = S.fillet(raw, 0, steps=3, closed=False, radii=[0, .6, 1.0, .9, .9, .3, 0, 0, 0, 0])
    pad.append(pad[0])
    bands = []
    for p, q in zip(pad, pad[1:]):
        if min(p[0], q[0]) > PAD_OUT - .05:
            bands.append(gold[1])                         # skirt
        elif min(p[1], q[1]) < 7.45:
            bands.append(gold[0])                         # underside and the skirt's hem
        elif min(p[0], q[0]) > 18.6 and max(p[1], q[1]) > 8.15:
            bands.append(gold[3])                         # piping round the outer top edge
        else:
            bands.append(gold[2])                         # top and inner lip
    S.revolve(m, frame, pad, lambda i, j, p: (bands[i],), seg=RING, crease=35)
    # Steel ring just under the skirt, six legs with rubber feet.
    ring = [(LEG_R * math.cos(TAU * k / RING), 5.45, LEG_R * math.sin(TAU * k / RING)) for k in range(RING + 1)]
    m.tube(frame, ring, .6, lambda *_: (iron[3],), seg=6, caps=(False, False))
    for k in range(LEGS):
        ang = TAU * (k + .5) / LEGS
        cx, cz = LEG_R * math.cos(ang), LEG_R * math.sin(ang)
        m.tube(frame, [(cx, 5.45, cz), (cx, 1.0, cz)], .55, lambda *_: (iron[2],), seg=8, caps=(False, False))
        foot = S.fillet([(0.0, 1.3), (.95, 1.3), (.95, 0.0), (0.0, 0.0)], 0, steps=2, radii=[0, .45, .2, 0])
        S.revolve(m, frame, foot, lambda *_: (rubber,), seg=8, centre=(cx, cz), crease=50)
    # The static mat cone under the bed, and the bed itself.
    paint = mat_paint(m)
    well = m.part('well')
    (r_out, y_out), (r_in, y_in) = WELL
    radii = sorted({r_in, r_out, *[r for r in MAT_BANDS if r_in < r < r_out]})          # inward edge first: solid below
    cone = [(r, S.lerp(y_in, y_out, (r - r_in) / (r_out - r_in))) for r in radii]
    S.revolve(m, well, cone, lambda i, j, p: paint((cone[i][0] + cone[i + 1][0]) / 2, 1), seg=RING)
    bed = m.part('bed', unit=2)
    top = [(0.0, BED)] + [(r, BED) for r in MAT_BANDS] + [(BED_R, BED), (BED_R, BED - .6), (0.0, BED - .6)]
    rings = [0.0, *MAT_BANDS, BED_R]
    cols = [paint((r0 + r1) / 2) for r0, r1 in zip(rings, rings[1:])] + [(a.swatch('INK', 1),), (a.swatch('INK', 0),)]
    S.revolve(m, bed, top, lambda i, j, p: cols[i], seg=RING, crease=40)
    # Collision: the bed disc tops at 8, the pad ring at 9 over 6, the legs up to the ring.
    cells = {}
    for x in range(-20, 20):
        for z in range(-20, 20):
            r = math.hypot(x + .5, z + .5)
            if r < PAD_IN:
                cells[(x, z)] = (BED - 1, BED)
            elif r <= PAD_OUT:
                cells[(x, z)] = (6.0, RIM)
    collision = columns(cells)
    legs = set()
    for k in range(LEGS):
        ang = TAU * (k + .5) / LEGS
        cx, cz = LEG_R * math.cos(ang), LEG_R * math.sin(ang)
        legs |= {(x, z) for x in range(-20, 20) for z in range(-20, 20) if math.hypot(x + .5 - cx, z + .5 - cz) < 1.0}
    collision += columns({c: (0.0, 6.0) for c in legs})
    return m, collision, {'origin': {'bed': [0, BED, 0]}}


# ---- orange-juice tub -------------------------------------------------------------------------------------------------
TUB = (21.0, 14.5)            # inner half-axes at the top of the wall (plan offset 0)
POWER = 2.4                   # superellipse: a rounded oblong washtub
FLOOR_Y, WALL_TOP, RIM_Y = 1.0, 6.4, 7.0
WALL_FOOT = -1.85             # inner wall offset at the floor (the wall flares 1.85 outward to the top)
BENCH = (-6.5, 3.25)          # bench step along the front (-Z) wall: its edge z and top
LEVELS = (2.0, 3.0, 4.0, 5.0)  # juice surface heights, levels 1-4
STOOL = ((-4.5, -21.5), (4.5, -16.6), 3.5)    # wooden step outside the front, by the bench
SEG = 80


def plan(o, y, count=SEG):
    """Closed loop of the tub plan at offset o (both half-axes grown by o) at height y."""
    A, B = TUB[0] + o, TUB[1] + o
    e = 2 / POWER
    out = []
    for k in range(count):
        t = TAU * k / count
        c, s = math.cos(t), math.sin(t)
        out.append((A * math.copysign(abs(c) ** e, c), y, B * math.copysign(abs(s) ** e, s)))
    return out


def plan_offset(x, z):
    """Offset o at which the plan passes through (x, z)."""
    lo, hi = -TUB[1] + .01, 40.0
    for _ in range(50):
        mid = (lo + hi) / 2
        if abs(x / (TUB[0] + mid)) ** POWER + abs(z / (TUB[1] + mid)) ** POWER > 1:
            lo = mid
        else:
            hi = mid
    return hi


def wall_offset(y):
    """Inner wall offset at height y (straight flare from the floor to the wall top)."""
    return WALL_FOOT + (y - FLOOR_Y) * (-WALL_FOOT) / (WALL_TOP - FLOOR_Y)


def wall_height(o):
    return FLOOR_Y + (o - WALL_FOOT) * (WALL_TOP - FLOOR_Y) / (-WALL_FOOT)


def span(z, y):
    """Half length in x of the inner wall at height y along the line z."""
    A, B = TUB[0] + wall_offset(y), TUB[1] + wall_offset(y)
    return A * max(0.0, 1 - abs(z / B) ** POWER) ** (1 / POWER)


def yukkuri_pool():
    m = S.Model('yukkuri_pool', atlas())
    a = m.atlas
    white = {k: a.swatch('WHITE', k) for k in range(5)}
    navy = {k: a.swatch('SKY', k) for k in range(5)}
    tub = m.part('tub')
    # Profile (offset, y) from the floor's edge up the inner wall, over the rolled rim, down the outside to the foot.
    raw = [(-3.2, FLOOR_Y), (WALL_FOOT, FLOOR_Y), (0.0, WALL_TOP), (0.0, RIM_Y), (1.25, RIM_Y), (1.25, 6.2), (.95, 6.0),
           (-.9, .6), (-1.4, 0.0), (-3.2, 0.0)]
    prof = S.fillet(raw, 0, steps=3, radii=[0, .9, 0, .55, .55, .35, 0, .5, 0, 0])
    bands = []
    for p, q in zip(prof, prof[1:]):
        hi = max(p[1], q[1])
        if hi > WALL_TOP + .01 or p[0] > .9 and q[0] > .9 and hi > 5.9:
            bands.append(navy[1])                                       # the navy rolled rim
        elif p[0] > q[0] and hi <= .65:
            bands.append(navy[1])                                       # navy foot ring
        elif p[0] > q[0]:
            bands.append(white[3])                                      # outside
        else:
            bands.append(white[4] if hi > 4.0 else white[3] if hi > 1.2 else white[2])   # inside, lighter up the wall
    rows = [plan(o, y) for o, y in prof]

    def facing(i, j, c):          # the profile runs with the enamel on its right: face its left side
        (o0, y0), (o1, y1) = prof[i], prof[i + 1]
        r = (c[0] / (TUB[0] + o0 + 1e-6), 0.0, c[2] / (TUB[1] + o0 + 1e-6))
        return S.add(S.mul(S.unit(r), -(y1 - y0)), (0.0, o1 - o0, 0.0))
    m.strip(tub, rows, lambda i, j, p: (bands[i],), facing, closed=True, crease=35)
    m.polygon(tub, plan(-3.2, FLOOR_Y), (0.0, 1.0, 0.0), lambda p: (white[2],))
    # Bench step along the front wall, by the stool: a flat top, a rounded edge and a face down to the floor.
    bench = m.part('bench')
    zb, yb = BENCH
    edge = S.fillet([(zb - 3.0, yb), (zb, yb), (zb, FLOOR_Y)], .6, steps=4)        # (z, y) over the edge and down
    cuts = 16
    rows = [[(S.lerp(-span(z, y), span(z, y), k / cuts), y, z) for k in range(cuts + 1)] for z, y in edge[1:]]
    face = len(rows) - 2                                                           # the last band is the face
    m.strip(bench, rows, lambda i, j, p: (white[2] if i == face else white[4],),
            lambda i, j, c: (0.0, 0.0 if i == face else 1.0, 1.0), crease=35)
    start = edge[1][0]
    top = sorted((p for p in plan(wall_offset(yb), yb, 480) if p[2] < start), key=lambda p: p[0])
    m.polygon(bench, [(-span(start, yb), yb, start)] + top + [(span(start, yb), yb, start)], (0.0, 1.0, 0.0),
              lambda p: (white[3],))
    # Lug handles at both ends, buried in the wall below the rim.
    lugs = m.part('handles')
    loop = S.fillet([(21.2, -3.6), (22.9, -2.2), (22.9, 2.2), (21.2, 3.6)], 1.2, steps=5)   # ends inside the wall
    for s in (-1, 1):
        m.tube(lugs, [(s * x, 5.6, z) for x, z in loop], .45, lambda *_: (navy[1],), seg=8, caps=(False, False))
    # Wooden step stool before the front rim, for bodies too small to step over it.
    wood = {k: a.swatch('WOOD', k) for k in range(5)}
    (x0, z0), (x1, z1), h = STOOL
    stool = m.part('stool')
    box = [(x0, z0), (x1, z0), (x1, z1), (x0, z1)]
    outline = S.fillet(box, 1.0, steps=3, closed=True)
    sides = S.fillet([(0.0, 0.0), (0.0, h), (.7, h)], .7, steps=4)
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2

    def inset(d, y):
        loop = S.fillet([(x0 + d, z0 + d), (x1 - d, z0 + d), (x1 - d, z1 - d), (x0 + d, z1 - d)], max(.05, 1.0 - d), steps=3, closed=True)
        return [(x, y, z) for x, z in loop]
    rows = [inset(d, y) for d, y in sides]
    m.strip(stool, rows, lambda i, j, p: (wood[2] if i == 0 else wood[3],),
            lambda i, j, c: (c[0] - cx, 0.0 if i == 0 else .6, c[2] - cz), closed=True, crease=40)
    plank = kit.ramp('WOOD')
    seams = a.decal('stool_top', 8, 6, lambda put: [put(u, v, plank[2 if v in (1, 4) else 3]) for u in range(8) for v in range(6)])
    m.polygon(stool, inset(.7, h), (0.0, 1.0, 0.0), lambda p: (seams, (p[0] - x0) / (x1 - x0) * 8, (p[2] - z0) / (z1 - z0) * 6))
    # Collision columns: foot and floor 0-1, the inner wall rising to the rim, the rim wall 0-7, bench, stool.
    cells = {}
    for x in range(-24, 24):
        for z in range(-24, 24):
            xc, zc = x + .5, z + .5
            if x0 <= xc <= x1 and z0 <= zc <= z1:
                cells[(x, z)] = (0.0, h)
                continue
            o = plan_offset(xc, zc)
            if o > 1.25:
                continue
            top_y = RIM_Y if o >= 0 else max(FLOOR_Y, wall_height(o))
            if zc < zb and o < wall_offset(yb):
                top_y = max(top_y, yb)
            cells[(x, z)] = (0.0, round(top_y * 4) / 4)
    return m, columns(cells), {}


def yukkuri_pool_juice():
    m = S.Model('yukkuri_pool_juice', atlas())
    juice = kit.ramp('JUICE')
    for k, y in enumerate(LEVELS, 1):
        o = wall_offset(y) - .05
        A, B = TUB[0] + o, TUB[1] + o
        W, H = int(math.ceil(2 * A)) + 1, int(math.ceil(2 * B)) + 1

        def paint(put, A=A, B=B, W=W, H=H):
            for v in range(H):
                for u in range(W):
                    x, z = u + .5 - W / 2, v + .5 - H / 2
                    inside = abs(x / A) ** POWER + abs(z / B) ** POWER
                    rim = inside > (1 - 1.1 / B) ** POWER                     # a lighter meniscus along the wall
                    glint = ((x + A * .38) / (A * .22)) ** 2 + ((z - B * .32) / (B * .12)) ** 2 < 1   # one soft sheen
                    put(u, v, juice[3] if rim or glint else juice[2])
        key = m.atlas.decal(f'level_{k}', W, H, paint)
        part = m.part(f'juice_u{k}', unit=k)
        m.polygon(part, plan(o, y), (0.0, 1.0, 0.0), lambda p, key=key, W=W, H=H: (key, p[0] + W / 2, p[2] + H / 2))
    return m, None, {}


# ---- exports ----------------------------------------------------------------------------------------------------------
PROPS = {'ceramic_tunnel': ceramic_tunnel, 'yukkuri_slide': yukkuri_slide, 'yukkuri_trampoline': yukkuri_trampoline,
         'yukkuri_pool': yukkuri_pool, 'yukkuri_pool_juice': yukkuri_pool_juice}
PLACED = ('ceramic_tunnel', 'yukkuri_slide', 'yukkuri_trampoline', 'yukkuri_pool')
LABELS = {'ceramic_tunnel': ['陶管', 'Ceramic tunnel'], 'yukkuri_slide': ['油库里滑梯', 'Yukkuri slide'],
          'yukkuri_trampoline': ['油库里蹦床', 'Yukkuri trampoline'], 'yukkuri_pool': ['橙汁澡盆', 'Orange juice tub']}
INGREDIENTS = {'ceramic_tunnel': ['brick'] * 6, 'yukkuri_slide': ['iron_ingot'] * 4 + ['stick'] * 2,
               'yukkuri_trampoline': ['string'] * 4 + ['slime_ball', 'iron_ingot'], 'yukkuri_pool': ['brick'] * 4 + ['cyan_dye']}


def tub_data():
    """The tub numbers the soak gameplay reads, in model units (1/16 block) from the facility's root block floor."""
    seats = [{'id': 'adult_left', 'size': 'adult', 'x': -9.0, 'z': 3.0, 'y': FLOOR_Y},
             {'id': 'adult_right', 'size': 'adult', 'x': 9.0, 'z': 3.0, 'y': FLOOR_Y},
             {'id': 'adult_alone', 'size': 'adult', 'x': 0.0, 'z': 3.0, 'y': FLOOR_Y},
             {'id': 'small_bench_left', 'size': 'small', 'x': -7.0, 'z': -9.6, 'y': BENCH[1]},
             {'id': 'small_bench_middle', 'size': 'small', 'x': 0.0, 'z': -9.6, 'y': BENCH[1]},
             {'id': 'small_bench_right', 'size': 'small', 'x': 7.0, 'z': -9.6, 'y': BENCH[1]}]
    for seat in seats:
        seat['soak'] = [round(max(0.0, y - seat['y']), 4) for y in LEVELS]
    return {'fill_mesh': 'yukkuri_pool_juice', 'levels': list(LEVELS), 'floor_top': FLOOR_Y, 'bench_top': BENCH[1],
            'bench_edge_z': BENCH[0], 'rim_top': RIM_Y, 'stool_top': STOOL[2],
            'stool': [STOOL[0][0], STOOL[0][1], STOOL[1][0], STOOL[1][1]], 'seats': seats}


def build(name):
    """(mesh, bbmodel, png, collision, parts summary) of one prop."""
    model, collision, extra = PROPS[name]()
    mesh, bb, png = model.export(extra.get('origin'))
    summary = [{'name': p['name'], 'unit': p['unit'], 'triangles': len(p['tris'])} for p in model.parts]
    return mesh, bb, png, collision, summary


def outputs():
    previous = provenance(SNAPSHOT / 'manifest.json')
    result, index, recipe = {}, {}, {}

    def emit(path, data):
        result[path] = (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode()

    def emit_mesh(path, data):
        result[path] = preserve_negative_zero_mesh_json(path, (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode())
    for name in PROPS:
        mesh, bb, png, collision, summary = build(name)
        recipe[name] = {'parts': summary} | ({'collision': collision} if collision is not None else {})
        texture = preserve_equivalent_png(ASSETS / f'textures/props/{name}.png', png)
        for folder in (HERE / 'assembled' / name, SNAPSHOT / 'selected' / name):
            emit(folder / f'{name}.bbmodel', bb)
            emit_mesh(folder / f'{name}.mesh.json', mesh)
            result[folder / 'texture.png'] = texture
        emit_mesh(ASSETS / f'yukkuri_props/{name}.json', mesh)
        result[ASSETS / f'textures/props/{name}.png'] = texture
        index[name] = {'bounds': mesh['bounds'], 'triangles': len(mesh['vertices']) // 24}
        if name not in PLACED:
            continue
        emit(ASSETS / f'models/item/{name}.json', {'parent': 'minecraft:builtin/entity', 'gui_light': 'front', 'textures': {'particle': f'mcyukkuri:props/{name}'}, 'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]}}})
        emit(ASSETS / f'blockstates/{name}.json', {'variants': {'': {'model': f'mcyukkuri:block/{name}'}}})
        emit(ASSETS / f'models/block/{name}.json', {'textures': {'particle': f'mcyukkuri:props/{name}'}, 'elements': []})
        emit(DATA / f'loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': []})
        emit(DATA / f'playgrounds/{name}.json', {'collision': collision} | ({'tub': tub_data()} if name == 'yukkuri_pool' else {}))
        emit(DATA / f'recipe/{name}.json', {'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'ingredients': [{'item': 'minecraft:' + x} for x in INGREDIENTS[name]], 'result': {'id': 'mcyukkuri:' + name, 'count': 1}})
    emit(HERE / 'recipe.json', {'status': 'pending-review', 'units_per_block': 16, 'style': 'smooth shapes (smooth.py)', 'props': recipe})
    emit(ASSETS / 'yukkuri_props/playgrounds-index.json', index)
    for i, locale in enumerate(('zh_cn', 'en_us')):
        path = ASSETS / f'lang/{locale}.json'
        existing = json.loads(path.read_text(encoding='utf8'))
        strings = {'item.mcyukkuri.' + name: labels[i] for name, labels in LABELS.items()}
        strings.update({'block.mcyukkuri.' + name: labels[i] for name, labels in LABELS.items()})
        strings.update({'activity.mcyukkuri.' + key: values[i] for key, values in {'tunnel': ['穿过管道', 'Passing through a tunnel'], 'slide_climb': ['登上滑梯', 'Climbing the slide'],
            'slide_down': ['顺坡滑下', 'Sliding down'], 'trampoline': ['玩蹦床', 'Bouncing on a trampoline'], 'tub_climb': ['爬进澡盆', 'Climbing into the tub'], 'juice_soak': ['泡橙汁澡', 'Soaking in orange juice']}.items()})
        strings.update({'play.mcyukkuri.no_space': ['需要平坦、空旷的 %s×%s×%s 空间', 'Needs a clear, supported %s×%s×%s space'][i],
                        'play.mcyukkuri.pool': ['橙汁澡盆：%s／%s 份橙汁。右键用橙汁一瓶补一份；油库会自己爬进去泡澡。', 'Orange juice tub: %s/%s portions of juice. Use orange juice on it, one bottle per portion; yukkuri climb in to soak on their own.'][i],
                        'play.mcyukkuri.pool_full': ['澡盆已经满了', 'The tub is full'][i]})
        changed = any(existing.get(k) != v for k, v in strings.items())
        existing.update(strings)
        if changed:
            result[path] = (json.dumps(existing, ensure_ascii=False, indent=2) + '\n').encode()
    emit(SNAPSHOT / 'manifest.json', {'status': 'pending-review-game-adaptation', 'source': HERE.relative_to(ROOT).as_posix() + '/build.py',
        'inputs': fingerprints(ROOT, {p: p.read_bytes() for p in (Path(__file__).resolve(), SMOOTH, KIT, kit.DRAW)}, previous.get('inputs', {})),
        'files': fingerprints(ROOT, {p: v for p, v in result.items() if p.parent.name != 'lang'}, previous.get('files', {}))})
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    changed = []
    for path, data in outputs().items():
        if not check_output(path, data):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    if args.check and changed:
        raise SystemExit('Stale exports: ' + ', '.join(changed))
    print(f'Playgrounds: {len(PROPS)} models; {len(changed)} files ' + ('differ' if args.check else 'written'))
