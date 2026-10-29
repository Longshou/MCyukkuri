"""The aquatic hat boat's larder: real stored items pressed into the turned hat beside the captain (project design).

User 2026-10-26: no food on the brim ever; stored food must look plausible. Ecology design D 3.3: the store looks like
things that rolled into an upturned hat, not a mound set in a boat; no caterpillar or flower dressing (the land forage
pile's signs); the visible items are the actual stored kinds (at most four, HatBoatCargo.SLOTS), drawn with their own
item models, loosely pressed in the way a yukkuri leaves them with mouth and push (nothing tied or bundled); a leaf from
the forage pile's leaf plate under them. Lead decision 2026-10-26: young on the bow tread hid a store in the middle before
the owner's chest, so the store lies in the two inner side wells (the second-tier ledges along the side walls before her
shoulders, where her body and hair do not cover them), both sides, filled alternately, so each tier reads from either bank
at 3 and 6 blocks; never on the brim, nothing above the opening.

Outputs:
- hat_provisions (prop): unit n = tier n's leaf beds on the two side-well ledges.
- hat_cargo_layout.json: per tier the ordered item places (the first `count` are drawn), their cluster (0 = the most
  numerous kind) and the measured occupancy the deck reads (cells).

Frame: hull-local hat units afloat (HatVessel: x across, the owner's braid side +X; z along, bow -Z; one unit is
hullScale/16 blocks), y measured from the brim top downward negative, with the crown already pressed to the bowl squash.
Items are rigid: the renderer scales only their positions' y by (current squash / bowl squash), never their shape.
Every item stands on the ledge or the items wedged before it (dropped onto them), stays inside the opening (never on
the brim), and clears the owner's own mesh (yukkuri/marisa.json, hat removed, at her seat and every care station of the deck
table) by CLEAR, so nothing floats, cuts the hat or goes into her.
"""
import json
import math
from pathlib import Path
from wild_kit import WildProp

ROOT = Path(__file__).resolve().parents[4]
VESSEL_PATH = ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json'
LAYOUT_PATH = ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_cargo_layout.json'
DECK_PATH = ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_deck_layout.json'
OWNER_MODEL = ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri/marisa.json'

TIER_MAX = [0, 3, 7, 12]          # HatBoatCargo.tierFor: 1-3, 4-7, 8-12 portions
SIZE = 1.9                        # an item model's full 16-pixel square, hat units (an apple about a baby's width)
THICK = SIZE / 16                 # item models are one pixel thick
INSET = .36                       # the opaque part of a typical item sprite: +-0.36 of its square from the centre
GRID = .125
LEAF = .25                        # forage-pile leaf sprite to hat units (a leaf about 4 long, laid across the floor)

# The two side wells: the second-tier ledges along the side walls before the owner's shoulders (right x 3.16..4.37, left
# -4.37..-3.72, z about -3.5..-0.6), between the young on the bow tread and her chest. Beside her own flanks (z over -0.5)
# her body and side hair cover the ledge from every bank, so the store keeps to her front flanks. The items are pushed in
# one by one and leaned against their own side wall, the picture turned to the middle of the cavity; later ones lean on the
# earlier ones further in. Fill order alternates the wells. From a bank the near wall hides the near well but the far well
# shows across the opening, so either bank reads every tier (3 and 6 blocks); the deep middle stays free for the young.
TOP = {1: -.4, 2: -.28, 3: -.08}   # the highest item top per tier, under the brim plane (y 0): a fuller store stands taller
CLEAR = .12       # from the owner's body at rest (her seated sway and breathing), at every owner seat
HAIR_CLEAR = .03  # from her soft side hair at rest, at her own seat
WALL = .04        # inside the cavity wall
# Resting, her braid drapes over the right brim and swings down into the right well before her shoulder (boat_drift,
# measured on the exported clip: down to about -1.0 there), so the store keeps out of that stretch.
BRAID = dict(x=3.0, z=(-1.9, -.4), ceiling=-1.1)
# (side, z along the hull, tilt from flat in degrees, spin, cluster, size, inward from the wall) in fill order; the second
# row leans on the first, so it stands further in and a little lower.
ROW, SECOND, NEAR_BRAID = 1.45, 1.3, 1.3   # the right first row has only the stretch before the braid
FILL = {1: [(1, -2.38, 58, 4, 0, NEAR_BRAID, 0), (-1, -1.0, 58, -4, 1, ROW, 0), (1, -3.5, 58, -3, 0, NEAR_BRAID, 0)],
        2: [(1, -2.38, 58, 4, 0, NEAR_BRAID, 0), (-1, -1.0, 58, -4, 1, ROW, 0), (1, -3.5, 58, -3, 2, NEAR_BRAID, 0), (-1, -2.2, 58, 3, 0, ROW, 0),
            (-1, -1.3, 30, 6, 1, 1.15, .85), (-1, -3.4, 58, -5, 2, ROW, 0), (1, -2.95, 46, -6, 0, SECOND, .55)],
        3: [(1, -2.38, 60, 4, 0, NEAR_BRAID, 0), (-1, -1.0, 60, -4, 1, ROW, 0), (1, -3.5, 60, -3, 2, NEAR_BRAID, 0), (-1, -2.2, 60, 3, 0, ROW, 0),
            (-1, -1.3, 30, 6, 1, 1.15, .85), (-1, -3.4, 60, -5, 2, ROW, 0), (1, -2.95, 46, -6, 0, SECOND, .55), (-1, -1.6, 46, 6, 3, SECOND, .45),
            (-1, -3.0, 30, 4, 2, 1.1, .9), (-1, -2.8, 46, -4, 1, SECOND, .45), (-1, -3.6, 46, -8, 0, SECOND, .5), (1, -3.55, 40, 8, 1, 1.1, .55)]}
# Leaf beds on the ledges (sprite, centre x z, yaw, fold, curl, scale), under the items of each tier.
BEDS = [[], [('leaf_green', 3.95, -1.6, .04, .2, .02, .08), ('leaf_dry', -3.95, -1.5, -.04, .2, .02, .08)],
        [('leaf_green', 3.95, -1.4, .04, .2, .02, .08), ('leaf_dry', 3.95, -2.9, -.05, .2, .02, .08),
         ('leaf_dry', -3.95, -1.4, -.04, .2, .02, .08), ('leaf_green', -3.95, -2.9, .05, .2, .02, .08)],
        [('leaf_green', 3.95, -1.4, .04, .2, .02, .08), ('leaf_dry', 3.95, -2.9, -.05, .2, .02, .08),
         ('leaf_dry', -3.95, -1.4, -.04, .2, .02, .08), ('leaf_green', -3.95, -2.9, .05, .2, .02, .08)]]


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


def _samples(R, n=9, size=SIZE):
    out = []
    for i in range(n):
        for j in range(n):
            u, v = (i / (n - 1) * 2 - 1) * INSET * size, (j / (n - 1) * 2 - 1) * INSET * size
            out.append((_apply(R, [u, v, 0]), v))
    return out


class Owner:
    """The underside of the owner's own body over each grid column (y from the brim top): her body over her seat and every
    care station of the deck table, her soft side hair over her own seat (she leaves it only for a short feed); the mesh
    without the hat (the hull) or the braid (it lies on the brim), at growth 1 in her hull (hat units = model units / hull
    ratio). Items stay CLEAR under it."""

    _cache = None

    @classmethod
    def of(cls, V):
        if cls._cache is None: cls._cache = cls(V)
        return cls._cache

    def __init__(self, V):
        import numpy as np
        model = json.loads(OWNER_MODEL.read_text(encoding='utf-8'))
        raw = np.array(model['vertices'], float).reshape(-1, 8)[:, :3]
        skip = ('brim_', 'hat_', 'bow_', 'mamu', 'peni', 'braid_lock')
        body, hair = [], []
        for part in model['parts']:
            if part['name'].startswith(skip): continue
            (body if part['name'].startswith(('rounded_', 'face_')) else hair).append(raw[part['start']:part['start'] + part['count']])
        body = np.concatenate(body).reshape(-1, 3, 3) / V['scale']['aquatic_marisa']
        hair = np.concatenate(hair).reshape(-1, 3, 3) / V['scale']['aquatic_marisa']
        brim = V['bowl']['freeboard']
        seats = [(0., V['bowl']['owner_y'], None)]
        if DECK_PATH.is_file():
            deck = json.loads(DECK_PATH.read_text(encoding='utf-8'))['modes']['AQUATIC_FLOAT']['slots']
            seats = [tuple(s['pos']) for s in deck if s['kind'] in ('CAPTAIN', 'SERVICE')]
        self.low = {}  # column -> lowest body point less CLEAR, lowest hair point less HAIR_CLEAR
        w = np.linspace(0, 1, 9)
        a, b = np.meshgrid(w, w); keep = a + b <= 1; a, b = a[keep], b[keep]
        for n, (sx, sy, sz) in enumerate(seats):
            if sz is None: sz = V['opening'][3] - 3.6571 - .05
            for mesh, clear in ((body, CLEAR), (hair, HAIR_CLEAR)) if n == 0 else ((body, CLEAR),):
                p = mesh[:, 0, None, :] + a[None, :, None] * (mesh[:, 1, None, :] - mesh[:, 0, None, :]) + b[None, :, None] * (mesh[:, 2, None, :] - mesh[:, 0, None, :])
                p = p.reshape(-1, 3)
                xs, ys, zs = p[:, 0] + sx, p[:, 1] + sy - brim - clear, p[:, 2] + sz
                for i, j, y in zip(np.round(xs / GRID).astype(int), np.round(zs / GRID).astype(int), ys):
                    if y < self.low.get((i, j), 99.): self.low[(i, j)] = y

    def ceiling(self, x, z):
        k = (round(x / GRID), round(z / GRID)); best = 99.
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1): best = min(best, self.low.get((k[0] + dx, k[1] + dz), 99.))
        b = BRAID
        if b['x'] <= x and b['z'][0] <= z <= b['z'][1]: best = min(best, b['ceiling'])
        return best


def leaf_bed(prop, part, tier, bowl):
    for name, x, z, yaw, fold, curl, scale in BEDS[tier]:
        prop.leaf(part, name, (x, bowl.surface(x, z) + .03, z), yaw, fold=fold, curl=curl, scale=scale)


def provisions():
    """hat_provisions: unit n is tier n's leaf beds on the two side-well ledges (forage-pile plate leaves, same wild atlas)."""
    V = _vessel(); bowl = Bowl(V)
    p = WildProp('hat_provisions')
    for tier in (1, 2, 3):
        leaf_bed(p, p.mat(f'larder_bed_tier{tier}', tier), tier, bowl)
    return p


def _bed_points(tier, bowl):
    prop = WildProp('probe'); part = prop.mat('bed', 0); leaf_bed(prop, part, tier, bowl)
    return [pos for tri in part['tris'] for pos, _, _ in tri]


def place_items(tier, bowl, owner, store):
    """The tier's items in fill order, each leaned against its own side wall (picture to the middle of the cavity, top edge
    on the wall) or, further in, on the items before it, dropped onto the ledge and those items; tilted more or less, moved
    in, then taken a little smaller, then nudged along the wall, until it clears the owner, the wall and its tier's top."""
    out = []; edge = bowl.sections[0][0][1]
    for side, authored_z, authored_tilt, spin, cluster, authored, inward in FILL[tier]:
        yaw = 90 if side > 0 else -90  # front +z turned to the middle, the top edge toward this well's wall
        best = None
        for size, step, z in ((authored - .1 * k, step, authored_z + dz) for dz in (0, .15, -.15, .3, -.3) for k in range(4) for step in range(4)):
            for more in (0, -6, 6, -12, 12, -18, 18):
                tilt = max(8, min(85, authored_tilt + more))
                R = _rot(yaw, tilt, spin); pts = _samples(R, size=size)
                x = side * (edge - WALL - max(side * p[0] for p, _ in pts) - inward - .05 * step)
                y = max(store.support(x + p[0], z + p[2]) - p[1] for p, _ in pts) + THICK / 2
                top = max(y + p[1] for p, _ in pts) + THICK / 2
                inside = all(abs(x + p[0]) <= edge - WALL for p, _ in pts)
                clear = all(y + p[1] + THICK / 2 <= owner.ceiling(x + p[0], z + p[2]) for p, _ in pts)
                if inside and clear and top <= TOP[tier]:
                    best = (R, [x, y, z], dict(side=side, yaw=yaw, tilt=round(tilt, 1), spin=spin, size=round(size, 3)), top); break
            if best: break
        if best is None: raise SystemExit(f'larder item {len(out)} of tier {tier} does not fit beside the owner: side {side} z {authored_z}')
        R, c, spec, top = best
        for p, _ in _samples(R, 13, spec['size']): store.raise_to(c[0] + p[0], c[2] + p[2], c[1] + p[1] + THICK / 2)
        out.append((dict(cluster=cluster, **spec), R, c, top))
    return out


def build_tier(tier, bowl, owner=None):
    owner = owner or Owner.of(_vessel())
    store = Store(bowl)
    for x, y, z in _bed_points(tier, bowl): store.raise_to(x, z, y + .02)
    items, report = [], []
    for spec, R, c, top in place_items(tier, bowl, owner, store):
        items.append(dict(cluster=spec['cluster'], pos=[round(v, 4) for v in c], quaternion=_quat(R),
                          euler=dict(yaw=spec['yaw'], tilt=spec['tilt'], spin=spec['spin']), size=spec['size'], side=spec['side'], top=round(top, 4)))
        report.append((len(items) - 1, spec, round(c[1], 3)))
    return store, items, report


def layout():
    V = _vessel(); bowl = Bowl(V); sq = bowl.sq; owner = Owner.of(V)
    larder = [dict(tier=0, max_count=0, items=[], bed_unit=0)]
    for tier in (1, 2, 3):
        cut = [p for p in _bed_points(tier, bowl) if p[1] < bowl.surface(p[0], p[2]) - 1e-3]
        if cut: raise SystemExit(f'leaf bed of tier {tier} cuts the hat at {cut[0]}')
        store, items, _ = build_tier(tier, bowl, owner)
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
                note='Larder in the two side wells beside the owner, filled alternately (lead decision 2026-10-26; user '
                     '2026-10-26: no food on the brim). The server reads the larder occupancy from hat_deck_layout.json, '
                     'which interactions/marisa-hat-vessel/deck.py measures from these cells.',
                larder=larder)


def report():
    bowl = Bowl(_vessel()); owner = Owner.of(_vessel())
    for tier in (1, 2, 3):
        _, items, rep = build_tier(tier, bowl, owner)
        print(f'tier {tier}: top {max(i["top"] for i in items):.3f} (floor {bowl.floor:.3f}, levels {[round(h, 3) for h in bowl.levels]})')
        for (n, contact, y), i in zip(rep, items): print('  item', n, 'y', y, 'top', i['top'], contact)


CARGO = {'hat_provisions': provisions}

if __name__ == '__main__':
    report()
