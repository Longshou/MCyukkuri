"""The aquatic hat boat's crown larder: real stored items pressed into the bottom of the turned hat (project design).

User 2026-10-26: no food on the brim ever; stored food must look plausible. Ecology design D 3.3: the store looks like
things that rolled into the bottom of an upturned hat, not a mound set in a boat; no caterpillar or flower dressing (the
land forage pile's signs); the visible items are the actual stored kinds (at most four, HatBoatCargo.SLOTS), drawn with
their own item models, loosely pressed in the way a yukkuri leaves them with mouth and push (nothing tied or bundled);
one or two big leaves from the forage pile's leaf plate under them.

Outputs:
- hat_provisions (prop): only the leaf beds, unit n = the bed of tier n (1 leaf, then 2), in the hull frame below.
- hat_cargo_layout.json: per tier the ordered item places (the first `count` are drawn), their cluster (0 = the most
  numerous kind) and the measured occupancy the deck reads (cells).

Frame: hull-local hat units afloat (HatVessel: x across, the owner's braid side +X; z along, bow -Z; one unit is
hullScale/16 blocks), y measured from the brim top downward negative, with the crown already pressed to the bowl squash.
Items are rigid: the renderer scales only their positions' y by (current squash / bowl squash), never their shape.
Every place is settled offline on the cavity surface and the items placed before it (a vertical drop, then a slide
into its lean until it touches the wall or an item), so nothing floats or cuts the hat.
"""
import json
import math
from pathlib import Path
from wild_kit import WildProp

ROOT = Path(__file__).resolve().parents[4]
VESSEL_PATH = ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json'
LAYOUT_PATH = ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_cargo_layout.json'

TIER_MAX = [0, 3, 7, 12]          # HatBoatCargo.tierFor: 1-3, 4-7, 8-12 portions
SIZE = 1.9                        # an item model's full 16-pixel square, hat units (an apple about a baby's width)
THICK = SIZE / 16                 # item models are one pixel thick
INSET = .36                       # the opaque part of a typical item sprite: +-0.36 of its square from the centre
GRID = .125
LEAF = .25                        # forage-pile leaf sprite to hat units (a leaf about 4 long, laid across the floor)

# Every tier is a heap pushed together right before the owner's chest, where she lets each mouthful go
# (boat_cargo_stow) and where the sight line from the bank over the bow tread reaches deepest, so each tier reads from the
# shore (user 2026-10-26: dress only what a player sees; lead review: tiers must be told apart from the bank). The heap's
# body is a low dome of pressed dry leaves (the forage-pile litter, drawn a little under the items so it only shows dark
# in their gaps), held by the crown walls; the items lie on it ring by ring from the outside in, each with its top edge
# toward the peak and stood up a little more than the slope so its picture faces out, resting on the dome (nothing
# floats, nothing goes under the hat surface). rise: the dome's height over the crown floor. Tier 3 stays below the
# owner's feet and the opening (D 3.3).
MOUND = {1: dict(cx=-.6, cz=-1.8, rx=1.5, rz=1.25, rise=.85),
         2: dict(cx=-.6, cz=-1.9, rx=2.2, rz=1.7, rise=1.45),
         3: dict(cx=-.6, cz=-1.85, rx=2.4, rz=1.85, rise=1.85)}
DOME = ((1.0, 0.0), (.9, .3), (.72, .58), (.48, .82), (.22, .96))  # wild_kit dome rings: (radius, height) fractions
# (angle round the peak in degrees from the bow, distance as a fraction of the half extents, extra stand-up, spin, cluster)
RINGS = {1: [(200, .55, 14, 0, 0), (40, .5, 16, 25, 1), (300, .2, 26, -15, 0)],
         2: [(200, .8, 10, 0, 0), (330, .8, 12, 30, 1), (90, .82, 10, -20, 2), (270, .8, 12, 10, 0),
             (20, .62, 16, -15, 1), (150, .55, 14, 5, 3), (0, .15, 24, -35, 2)],
         3: [(200, .82, 10, 0, 0), (320, .82, 12, 40, 1), (90, .84, 10, -20, 2), (270, .84, 12, 12, 0),
             (30, .8, 12, -25, 3), (150, .8, 10, 8, 1), (240, .55, 16, -30, 0), (100, .55, 16, 20, 2),
             (0, .55, 20, -10, 0), (300, .5, 18, 35, 1), (60, .35, 22, -5, 3), (180, .12, 30, 15, 0)]}
FILL_INSET = .18  # the leaf dome under the items, this far under the surface they rest on
PLACES = [[], [], [], []]  # settled loose places (settle()); every tier currently lies on its dome
# Leaf beds: forage-pile plate leaves (sprite, centre x z, yaw, fold, curl), lying on the crown floor.
BEDS = [[], [('leaf_green', -.6, -2.45, 1.5, .12, .02)],
        [('leaf_green', -.6, -2.45, 1.5, .12, .02), ('leaf_dry', -.7, -1.35, 1.75, .1, .02)],
        [('leaf_green', -.6, -2.45, 1.5, .12, .02), ('leaf_dry', -.7, -1.35, 1.75, .1, .02)]]


def _vessel():
    return json.loads(VESSEL_PATH.read_text(encoding='utf-8'))


class Bowl:
    """The afloat cavity surface (bowl squash) with y from the brim top: deepest open section under (x, z); 0 on the
    brim and beyond the opening (the wall is solid below that)."""

    def __init__(self, V):
        sq = V['squash']['bowl']; self.sq = sq
        self.sections = [(s['rect'], -s['depth'] * sq) for s in V['cavity_sections']]
        self.floor = self.sections[-1][1]; self.floor_rect = self.sections[-1][0]
        self.levels = [h for _, h in self.sections]

    def surface(self, x, z):
        for rect, h in reversed(self.sections):
            if rect[0] <= x <= rect[1] and rect[2] <= z <= rect[3]: return h
        return 0.0


def _rot(yaw, tilt, spin):
    """Item frame (picture in x-y, front +z) to hull: Ry(-yaw) * Rx(tilt - 90) * Rz(spin), degrees. With yaw 0 the
    picture's top edge points toward the bow (-Z) and rises with tilt; yaw turns that toward +X."""
    def rx(a):
        c, s = math.cos(a), math.sin(a); return [[1, 0, 0], [0, c, -s], [0, s, c]]

    def ry(a):
        c, s = math.cos(a), math.sin(a); return [[c, 0, s], [0, 1, 0], [-s, 0, c]]

    def rz(a):
        c, s = math.cos(a), math.sin(a); return [[c, -s, 0], [s, c, 0], [0, 0, 1]]

    def mul(a, b): return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    return mul(mul(ry(math.radians(-yaw)), rx(math.radians(tilt - 90))), rz(math.radians(spin)))


def _apply(R, p): return [sum(R[i][k] * p[k] for k in range(3)) for i in range(3)]


def _quat(R):
    """Unit quaternion [x, y, z, w] of a rotation matrix (JOML Quaternionf order)."""
    t = R[0][0] + R[1][1] + R[2][2]
    if t > 0:
        s = math.sqrt(t + 1) * 2; w = s / 4; x = (R[2][1] - R[1][2]) / s; y = (R[0][2] - R[2][0]) / s; z = (R[1][0] - R[0][1]) / s
    elif R[0][0] > R[1][1] and R[0][0] > R[2][2]:
        s = math.sqrt(1 + R[0][0] - R[1][1] - R[2][2]) * 2; w = (R[2][1] - R[1][2]) / s; x = s / 4; y = (R[0][1] + R[1][0]) / s; z = (R[0][2] + R[2][0]) / s
    elif R[1][1] > R[2][2]:
        s = math.sqrt(1 + R[1][1] - R[0][0] - R[2][2]) * 2; w = (R[0][2] - R[2][0]) / s; x = (R[0][1] + R[1][0]) / s; y = s / 4; z = (R[1][2] + R[2][1]) / s
    else:
        s = math.sqrt(1 + R[2][2] - R[0][0] - R[1][1]) * 2; w = (R[1][0] - R[0][1]) / s; x = (R[0][2] + R[2][0]) / s; y = (R[1][2] + R[2][1]) / s; z = s / 4
    return [round(v, 6) for v in (x, y, z, w)]


class Store:
    """A heightfield over the cavity: the hat surface raised by everything placed so far (leaf beds, items)."""

    def __init__(self, bowl):
        self.bowl = bowl; self.top = {}

    def cell(self, x, z): return (round(x / GRID), round(z / GRID))

    def support(self, x, z): return max(self.bowl.surface(x, z), self.top.get(self.cell(x, z), -99.))

    def raise_to(self, x, z, y):
        k = self.cell(x, z)
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                c = (k[0] + dx, k[1] + dz); self.top[c] = max(self.top.get(c, -99.), y if dx == dz == 0 else y - .03)


def _samples(R, n=9):
    out = []
    for i in range(n):
        for j in range(n):
            u, v = (i / (n - 1) * 2 - 1) * INSET * SIZE, (j / (n - 1) * 2 - 1) * INSET * SIZE
            out.append((_apply(R, [u, v, 0]), v))
    return out


def _hull(points):
    """Convex hull (x, z) by the monotone chain."""
    pts = sorted(set((round(a, 4), round(b, 4)) for a, b in points))
    if len(pts) < 3: return pts

    def cross(o, a, b): return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0: lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0: upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def _inside(poly, q, margin):
    """q lies inside the convex polygon by at least margin."""
    if len(poly) < 3: return False
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        ex, ez = b[0] - a[0], b[1] - a[1]; L = math.hypot(ex, ez)
        if L < 1e-9: continue
        if (ex * (q[1] - a[1]) - ez * (q[0] - a[0])) / L < margin: return False
    return True


def _place(store, spec, yaw, tilt):
    """Drop the item straight down onto the surface, then (slide) push it along its lean until its upper edge rests
    on a wall or an earlier item, and drop again. Stable when its centre lies over the hull of what it rests on:
    the points touching below and, for a lean, the upper points touching sideways."""
    R = _rot(yaw, tilt, spec['spin']); pts = _samples(R)
    lean = [math.sin(math.radians(yaw)), -math.cos(math.radians(yaw))]

    def rest(x, z):
        """Height of the centre resting at (x, z) and whether the point it rests on is in the upper half."""
        h, v = max((store.support(x + p[0], z + p[2]) - p[1], v) for p, v in pts); return h + THICK / 2, v > 0

    def drop(x, z): return rest(x, z)[0]

    x, z = spec['x'], spec['z']; y = drop(x, z)
    if spec['slide']:  # slide along the lean, resting at each step, until the upper edge meets something and is held
        for _ in range(60):
            nx, nz = x + lean[0] * .05, z + lean[1] * .05; ny, upper = rest(nx, nz)
            if ny > y + .04 and upper: break
            x, z, y = nx, nz, ny
    under = [(x + p[0], z + p[2]) for p, v in pts if y + p[1] - THICK / 2 - store.support(x + p[0], z + p[2]) < .06]
    side = [(x + p[0] + lean[0] * .3, z + p[2] + lean[1] * .3) for p, v in pts if v > 0 and any(
        y + p[1] - THICK / 2 < store.support(x + p[0] + lean[0] * .12 * k, z + p[2] + lean[1] * .12 * k) for k in (1, 2))]
    stable = _inside(_hull(under + side), (x, z), .05)
    return R, [x, y, z], dict(under=len(under), side=len(side), stable=stable, yaw=yaw, tilt=tilt)


def settle(store, spec, strict=True):
    """The authored pose if it rests stably, else the nearest stable lean (tilt first, then yaw)."""
    tries = [(0, 0)] + [(0, d) for d in (-8, 8, -16, 16, -24, 24, -32, 32)] +             [(dy, d) for dy in (-15, 15, -30, 30) for d in (0, -10, 10, -20, 20)]
    for dy, dt in tries:
        tilt = min(75, max(4, spec['tilt'] + dt))
        R, c, contact = _place(store, spec, spec['yaw'] + dy, tilt)
        if contact['stable']: return R, c, contact
    # no lean near the authored one rests: the stable lean whose picture normal is nearest the authored one
    want = _apply(_rot(spec['yaw'], spec['tilt'], 0), [0, 0, 1]); best = None
    for yaw in range(0, 360, 20):
        for tilt in (8, 15, 22, 30, 38, 46, 55, 64):
            R, c, contact = _place(store, spec, yaw, tilt)
            if not contact['stable']: continue
            n = _apply(_rot(yaw, tilt, 0), [0, 0, 1]); cost = -sum(a * b for a, b in zip(n, want))
            if best is None or cost < best[0]: best = (cost, R, c, contact)
    if best: return best[1:]
    if strict: raise SystemExit(f'larder item cannot rest: {spec}')
    return _place(store, spec, spec['yaw'], spec['tilt'])


def leaf_bed(prop, part, tier, bowl):
    for name, x, z, yaw, fold, curl in BEDS[tier]:
        prop.leaf(part, name, (x, bowl.floor + .03, z), yaw, fold=fold, curl=curl, scale=LEAF)


def provisions():
    """hat_provisions: unit n is tier n's leaf bed (forage-pile plate leaves, same wild atlas)."""
    V = _vessel(); bowl = Bowl(V)
    p = WildProp('hat_provisions')
    for tier in (1, 2, 3):
        leaf_bed(p, p.mat(f'larder_bed_tier{tier}', tier), tier, bowl)
        leaf_dome(p, p.mat(f'larder_heap_tier{tier}', tier), tier, bowl)
    return p


def _bed_points(tier, bowl):
    prop = WildProp('probe'); part = prop.mat('bed', 0); leaf_bed(prop, part, tier, bowl)
    return [pos for tri in part['tris'] for pos, _, _ in tri]


def mound(spec, bowl, inset=0.):
    """The heap's surface height at (x, z): the wild_kit dome profile over the crown floor, never below the hat."""
    rings = list(DOME)+[(0., 1.)]
    def frac(d):
        if d >= 1: return 0.
        for (r0, f0), (r1, f1) in zip(rings, rings[1:]):
            if r1 <= d <= r0: return f0+(f1-f0)*(r0-d)/(r0-r1)
        return 1.

    def h(x, z):
        d = math.hypot((x-spec['cx'])/spec['rx'], (z-spec['cz'])/spec['rz'])
        return max(bowl.surface(x, z), bowl.floor+spec['rise']*frac(d)-inset)
    return h


def on_mound(tier, bowl):
    """Items laid on the tier's dome, ring by ring from the outside in: (spec, rotation, centre)."""
    m = MOUND[tier]; h = mound(m, bowl); out = []
    for angle, frac, stand, spin, cluster in RINGS[tier]:
        a = math.radians(angle)
        while True:  # pulled in toward the peak until the hat wall does not hold it up off the heap
            x, z = m['cx']+math.sin(a)*frac*m['rx'], m['cz']-math.cos(a)*frac*m['rz']
            e = .05; gx, gz = (h(x+e, z)-h(x-e, z))/(2*e), (h(x, z+e)-h(x, z-e))/(2*e); g = math.hypot(gx, gz)
            yaw = math.degrees(math.atan2(gx, -gz)) if g > 1e-6 else angle+180  # the top edge toward the peak
            tilt = min(48, math.degrees(math.atan(g))+stand)
            R = _rot(yaw, tilt, spin); pts = _samples(R)
            on_heap = max(h(x+p[0], z+p[2])-p[1] for p, _ in pts)-.06
            on_hat = max(bowl.surface(x+p[0], z+p[2])-p[1] for p, _ in pts)+THICK/2
            if on_hat <= on_heap+.05 or frac < .05: break
            frac -= .05
        out.append((dict(cluster=cluster, yaw=round(yaw, 1), tilt=round(tilt, 1), spin=spin), R, [x, max(on_heap, on_hat), z]))
    return out


def leaf_dome(prop, part, tier, bowl):
    """The pressed dry-leaf body of the heap (hidden under the items but in their gaps)."""
    if tier not in MOUND: return
    import random
    m = MOUND[tier]; rng = random.Random(f'hat-larder-{tier}')
    prop.dome(part, (m['cx'], bowl.floor, m['cz']), m['rx'], m['rz'], m['rise']-FILL_INSET, 'litter_dry', rng, seg=10, jitter=.04)


def build_tier(tier, bowl, strict=True):
    store, occupied = Store(bowl), Store(bowl)
    for x, y, z in _bed_points(tier, bowl): store.raise_to(x, z, y + .02)
    items, report = [], []
    if tier in MOUND:
        h = mound(MOUND[tier], bowl, FILL_INSET)
        for i in range(int(-4.5/GRID), int(2.5/GRID)+1):  # the leaf dome is occupancy too
            for j in range(int(-4.5/GRID), int(1.0/GRID)+1):
                x, z = i*GRID, j*GRID; y = h(x, z)
                if y > bowl.surface(x, z)+.02: occupied.raise_to(x, z, y)
        for spec, R, c in on_mound(tier, bowl):
            for p, _ in _samples(R, 13): occupied.raise_to(c[0] + p[0], c[2] + p[2], c[1] + p[1] + THICK / 2)
            items.append(dict(cluster=spec['cluster'], pos=[round(v, 4) for v in c], quaternion=_quat(R),
                              euler=dict(yaw=spec['yaw'], tilt=spec['tilt'], spin=spec['spin']), size=SIZE,
                              top=round(max(c[1] + p[1] for p, _ in _samples(R)) + THICK / 2, 4)))
            report.append((len(items)-1, dict(on='mound', yaw=spec['yaw'], tilt=spec['tilt']), round(c[1], 3)))
        return occupied, items, report
    for n, spec in enumerate(PLACES[tier]):
        R, c, contact = settle(store, spec, strict)
        for p, _ in _samples(R, 13):
            for st in (store, occupied): st.raise_to(c[0] + p[0], c[2] + p[2], c[1] + p[1] + THICK / 2)
        items.append(dict(cluster=spec['cluster'], pos=[round(v, 4) for v in c], quaternion=_quat(R),
                          euler=dict(yaw=contact['yaw'], tilt=contact['tilt'], spin=spec['spin']), size=SIZE,
                          top=round(max(c[1] + p[1] for p, _ in _samples(R)) + THICK / 2, 4)))
        report.append((n, contact, round(c[1], 3)))
    return occupied, items, report


def layout():
    V = _vessel(); bowl = Bowl(V); sq = bowl.sq
    larder = [dict(tier=0, max_count=0, items=[], bed_unit=0)]
    for tier in (1, 2, 3):
        cut = [p for p in _bed_points(tier, bowl) if p[1] < bowl.surface(p[0], p[2]) - 1e-3]
        if cut: raise SystemExit(f'leaf bed of tier {tier} cuts the hat at {cut[0]}')
        store, items, _ = build_tier(tier, bowl)
        # occupancy: every cell an item raised, as height over the floor (unsquashed, like hat depths); the flat leaf bed
        # is sat on like the floor
        cells = {k: v for k, v in store.top.items() if v > bowl.surface(k[0] * GRID, k[1] * GRID) + 1e-6}
        xs = [k[0] * GRID for k in cells]; zs = [k[1] * GRID for k in cells]
        cx, cz = round((min(xs) + max(xs)) / 2, 4), round((min(zs) + max(zs)) / 2, 4)
        top = max(cells.values())
        larder.append(dict(tier=tier, max_count=TIER_MAX[tier], bed_unit=tier, items=items,
                           top=round(top, 4), bounds=[round(min(xs) - GRID / 2, 4), round(max(xs) + GRID / 2, 4),
                                                      round(min(zs) - GRID / 2, 4), round(max(zs) + GRID / 2, 4)],
                           occupancy=dict(grid=GRID, floor=round(bowl.floor, 4), centre=[cx, cz],
                                          cells=sorted([round(k[0] * GRID - cx, 4), round(k[1] * GRID - cz, 4),
                                                        round((v - bowl.floor) / sq, 4)] for k, v in cells.items()))))
    return dict(version=2, status='pending_user_review', units='hull_hat_units_bowl',
                source='standards/yukkuri-library/drafts/care-props/cargo.py', bed_prop='hat_provisions',
                vessel_source=VESSEL_PATH.relative_to(ROOT).as_posix(), tier_max_counts=TIER_MAX,
                frame='hull-local hat units afloat: x across (owner braid +X), z along (bow -Z), y from the brim top '
                      '(negative down) with the crown at the bowl squash; scale positions\' y by squash/bowl_squash on '
                      'another squash, never the items',
                bowl_squash=sq,
                item_rule='draw the first min(count, max_count) items of the tier for the total stored portions; '
                          'stored kinds ranked by count (ties: first stored) map to clusters 0..3; an item whose cluster '
                          'kind has no portion left takes the kind with the most left; the item model is drawn centred on '
                          'pos, rotated by quaternion [x, y, z, w] (picture in its x-y plane, front +z), size hat units '
                          'across its full 16-pixel square',
                note='Crown larder only (user 2026-10-26: no food on the brim). The server reads the larder occupancy '
                     'from hat_deck_layout.json, which interactions/marisa-hat-vessel/deck.py measures from these cells.',
                larder=larder)


def report():
    bowl = Bowl(_vessel())
    for tier in (1, 2, 3):
        _, items, rep = build_tier(tier, bowl, False)
        print(f'tier {tier}: top {max(i["top"] for i in items):.3f} (floor {bowl.floor:.3f}, levels {[round(h, 3) for h in bowl.levels]})')
        for (n, contact, y), i in zip(rep, items): print('  item', n, 'y', y, 'top', i['top'], contact)


CARGO = {'hat_provisions': provisions}

if __name__ == '__main__':
    report()
