"""Python mirror of the settlement site planner (src/main/java/.../settlement/site) for previews and geometry checks.

It reproduces SiteRng, SiteGeom, SitePlanner and the block stamps on a flat world, runs the NaturalShelter.survey rules
over every planned burrow, and renders top-down PNG previews of every tier. It is a mirror, not the source: when the Java
planner changes, change this file with it. Nothing here touches the game.

Usage: python settlement_site_preview.py [--out DIR] [--check N]
  --out DIR   write band.png, village.png, demo.png and tiers.png into DIR (default .local/previews/settlement)
  --check N   survey/reachability check over N seeds x 4 rotations for the generated tiers (prints a summary)
"""
import argparse
import math
import os
import sys
from collections import deque

M = (1 << 64) - 1
DIRS = {'N': (0, -1), 'E': (1, 0), 'S': (0, 1), 'W': (-1, 0)}
CW = {'N': 'E', 'E': 'S', 'S': 'W', 'W': 'N'}
OPP = {'N': 'S', 'S': 'N', 'E': 'W', 'W': 'E'}


class Rng:
    def __init__(self, seed):
        self.s = seed & M

    def next(self):
        self.s = (self.s + 0x9E3779B97F4A7C15) & M
        z = self.s
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M
        return z ^ (z >> 31)

    def next_int(self, n):
        return (self.next() >> 33) % n

    def next_double(self):
        return (self.next() >> 11) * (1.0 / 9007199254740992.0)

    def range(self, lo, hi):
        return lo + (hi - lo) * self.next_double()


def hash_cell(seed, x, z, salt):
    h = (seed & M) ^ ((x & M) * 0x632BE59BD9B4E019 & M) ^ ((z & M) * 0x9E3779B97F4A7C15 & M) ^ ((salt & M) * 0xD1B54A32D192ED03 & M)
    h = ((h ^ (h >> 30)) * 0xBF58476D1CE4E5B9) & M
    h = ((h ^ (h >> 27)) * 0x94D049BB133111EB) & M
    h ^= h >> 31
    return (h >> 11) * (1.0 / 9007199254740992.0)


TIERS = {  # hedge, bound, homes, min/max households, min/max population, hall
    'BAND': dict(hedge=13.5, bound=19, homes=3, hall=False),
    'VILLAGE': dict(hedge=22.0, bound=29, homes=6, hall=True),
    'DEMO': dict(hedge=0.0, bound=11, homes=2, hall=True),
}


def floor(v):
    return int(math.floor(v))


def right_of(f):
    return CW[f]


# ---- SiteGeom
def dims(kind):
    return {'HALL': (3, 4, 2, 3), 'GRANARY': (2, 2, 1, 1)}.get(kind, (2, 4, 1, 3))


def fx(p, u, w):
    f = DIRS[p['facing']]
    r = DIRS[right_of(p['facing'])]
    return p['x'] - f[0] * w + r[0] * u


def fz(p, u, w):
    f = DIRS[p['facing']]
    r = DIRS[right_of(p['facing'])]
    return p['z'] - f[1] * w + r[1] * u


def is_mound(p):
    return p['kind'] in ('HOME', 'HALL', 'GRANARY')


def in_plaza(p, dx, dz):
    if p['b'] == 1:
        return abs(dx) <= p['a'] // 10 and abs(dz) <= p['a'] // 10
    base = p['a'] / 10.0
    r = base * (1 + .12 * math.sin(3 * math.atan2(dz, dx) + p['c'] / 100.0)) + .2
    return math.sqrt(dx * dx + dz * dz) <= r


def pool_water(p):
    a, b = p['a'] / 10.0, p['b'] / 10.0
    r = int(math.ceil(max(p['a'], p['b']) / 10.0))
    return [(p['x'] + dx, p['z'] + dz) for dx in range(-r, r + 1) for dz in range(-r, r + 1)
            if dx * dx / (a * a) + dz * dz / (b * b) <= 1.0001]


def pool_shelf(p):
    water = set(pool_water(p))
    shelf = set()
    for (x, z) in water:
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if (x + dx, z + dz) not in water:
                    shelf.add((x + dx, z + dz))
    return shelf


def latrine_depth(p):
    return 3 if p['b'] >= 3 else 2


def lane(p):
    out = set()
    if is_mound(p):
        for k in range(1, 4):
            for u in (-1, 0, 1):
                out.add((fx(p, u, -k), fz(p, u, -k)))
    return out


def cells(p):
    out = set()
    k = p['kind']
    if is_mound(p):
        d = dims(k)
        for u in range(-d[0], d[0] + 1):
            for w in range(0, d[1] + 1):
                out.add((fx(p, u, w), fz(p, u, w)))
        out |= lane(p)
    elif k == 'PLAZA':
        r = p['a'] // 10 + 2
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                if in_plaza(p, dx, dz):
                    out.add((p['x'] + dx, p['z'] + dz))
    elif k == 'POOL':
        out |= set(pool_water(p)) | pool_shelf(p)
    elif k == 'LATRINE':
        for u in range(-p['b'], p['b'] + 1):
            for w in range(0, latrine_depth(p) + 1):
                out.add((fx(p, u, w), fz(p, u, w)))
    elif k == 'NURSERY':
        for dx in range(-p['a'], p['a'] + 1):
            for dz in range(-p['b'], p['b'] + 1):
                out.add((p['x'] + dx, p['z'] + dz))
    elif k == 'KNOLL':
        r = int(math.ceil(p['a'] / 10.0))
        radius = p['a'] / 10.0
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                if dx * dx + dz * dz <= radius * radius:
                    out.add((p['x'] + dx, p['z'] + dz))
    return out


def part(kind, x, z, facing, a=0, b=0, c=0):
    return dict(kind=kind, x=x, z=z, facing=facing, a=a, b=b, c=c)


# ---- SitePlanner
def toward(vx, vz):
    if abs(vx) >= abs(vz):
        return 'E' if vx > 0 else 'W'
    return 'S' if vz > 0 else 'N'


class Draft:
    def __init__(self, tier, rng, rp):
        self.tier, self.rng, self.rp = tier, rng, rp
        self.limit = 1e9 if tier == 'DEMO' else TIERS[tier]['hedge'] - 1.5
        self.parts, self.taken, self.avoid = [], set(), []

    def place(self, p):
        cs = cells(p)
        ln = lane(p)
        for (x, z) in cs:
            if math.hypot(x, z) > self.limit:
                return False
            if (x, z) not in ln and math.hypot(x, z) <= self.rp + 1.3:
                return False
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    if (x + dx, z + dz) in self.taken:
                        return False
        for a in self.avoid:
            if math.hypot(p['x'] - a[0], p['z'] - a[1]) < a[2]:
                return False
        self.parts.append(p)
        self.taken |= cs
        return True

    def force(self, p):
        self.parts.append(p)
        self.taken |= cells(p)


def mound(kind, angle, r, rng):
    x, z = floor(r * math.cos(angle) + .5), floor(r * math.sin(angle) + .5)
    return part(kind, x, z, toward(-x, -z), rng.next_int(1 << 16))


def walk(out, rng, x, z, blocked):
    m = rng.next_int(100)
    if (x, z) not in blocked and (x, z) not in out:
        out[(x, z)] = 0 if m < 50 else 1 if m < 90 else 2


def curve(out, rng, x0, z0, x1, z1, blocked):
    dx, dz = x1 - x0, z1 - z0
    ln = math.hypot(dx, dz)
    if ln < 1:
        return
    bend = (rng.next_double() - .5) * .45 * ln
    cx, cz = (x0 + x1) / 2 - dz / ln * bend, (z0 + z1) / 2 + dx / ln * bend
    steps = int(math.ceil(ln * 2.5))
    for i in range(steps + 1):
        t = i / steps
        mt = 1 - t
        x = mt * mt * x0 + 2 * mt * t * cx + t * t * x1
        z = mt * mt * z0 + 2 * mt * t * cz + t * t * z1
        tx = 2 * mt * (cx - x0) + 2 * t * (x1 - cx)
        tz = 2 * mt * (cz - z0) + 2 * t * (z1 - cz)
        ix, iz = floor(x + .5), floor(z + .5)
        walk(out, rng, ix, iz, blocked)
        tl = math.hypot(tx, tz)
        if rng.next_double() < .35 and tl > 0:
            walk(out, rng, ix + floor(-tz / tl + .5), iz + floor(tx / tl + .5), blocked)


def connect(d, paths, blocked):
    rp = d.rp
    for p in d.parts:
        len0 = max(math.hypot(p['x'], p['z']), 1)
        k = p['kind']
        if k in ('HOME', 'HALL', 'GRANARY'):
            f = DIRS[p['facing']]
            tx, tz = p['x'] + f[0] * 4, p['z'] + f[1] * 4
        elif k == 'POOL':
            reach = max(p['a'], p['b']) / 10.0 + 2.2
            tx, tz = p['x'] - p['x'] / len0 * reach, p['z'] - p['z'] / len0 * reach
        elif k == 'LATRINE':
            f = DIRS[p['facing']]
            tx, tz = p['x'] + f[0] * 1.5, p['z'] + f[1] * 1.5
        elif k == 'KNOLL':
            reach = p['a'] / 10.0 + 1.5
            tx, tz = p['x'] - p['x'] / len0 * reach, p['z'] - p['z'] / len0 * reach
        elif k == 'NURSERY':
            reach = max(p['a'], p['b']) + 1.8
            tx, tz = p['x'] - p['x'] / len0 * reach, p['z'] - p['z'] / len0 * reach
        else:
            continue
        ln = math.hypot(tx, tz)
        if ln <= rp + .8:
            continue
        curve(paths, d.rng, tx, tz, tx / ln * rp, tz / ln * rp, blocked)


def demo_layout(rng):
    d = Draft('DEMO', rng, 2.5)
    d.force(part('PLAZA', 0, 0, 'S', 20, 1, rng.next_int(628)))
    d.force(part('HALL', 0, -4, 'S', rng.next_int(1 << 16)))
    d.force(part('HOME', -3, 0, 'E', rng.next_int(1 << 16)))
    d.force(part('HOME', 3, 0, 'W', rng.next_int(1 << 16)))
    d.force(part('GRANARY', 0, 4, 'N', rng.next_int(1 << 16)))
    d.force(part('POOL', -5, 5, 'S', 13, 10))
    d.force(part('LATRINE', 6, 6, 'N', 0, 2))
    d.force(part('NURSERY', 6, -6, 'S', 1, 1))
    d.force(part('KNOLL', -6, -6, 'S', 15))
    paths = {}
    connect(d, paths, set(d.taken))
    return d.parts, paths, {}


def layout(tier, rng):
    cfg = TIERS[tier]
    village = tier == 'VILLAGE'
    rp = 3.5 if village else 2.5
    hedge = cfg['hedge']
    d = Draft(tier, rng, rp)
    d.parts.append(part('PLAZA', 0, 0, 'S', int(round(rp * 10)), 0, rng.next_int(628)))
    theta_l = math.pi / 2 + rng.range(-.5, .5)
    latrine = None
    for _ in range(30):
        if latrine:
            break
        angle = theta_l + rng.range(-.35, .35)
        r = hedge - (5.5 if village else 4.8) + rng.range(-1, 1)
        x, z = floor(r * math.cos(angle) + .5), floor(r * math.sin(angle) + .5)
        p = part('LATRINE', x, z, toward(-x, -z), 1 if village else 0, 3 if village else 2)
        if d.place(p):
            latrine = p
    if not latrine:
        return None
    d.avoid.append((latrine['x'], latrine['z'], 10 if village else 8))
    if cfg['hall']:
        hall = None
        for tries in range(30):
            if hall:
                break
            angle = -math.pi / 2 + rng.range(-.45, .45)
            r = rp + 3.2 + tries * .15
            p = mound('HALL', angle, r, rng)
            if d.place(p):
                hall = p
        if not hall:
            return None
    for _ in range(2 if village else 1):
        granary = None
        for _t in range(120):
            if granary:
                break
            angle = theta_l + math.pi + rng.range(-1.5, 1.5)
            r = rp + 3.2 + rng.range(0, 7 if village else 5)
            p = mound('GRANARY', angle, r, rng)
            if d.place(p):
                granary = p
        if not granary:
            return None
    pool = (19, 14) if village else (14, 11)
    water = None
    d.avoid = [(latrine['x'], latrine['z'], 11 if village else 8)]
    for _t in range(100):
        if water:
            break
        angle = theta_l + math.pi + rng.range(-1.6, 1.6)
        r = hedge * rng.range(.4, .75)
        x, z = floor(r * math.cos(angle) + .5), floor(r * math.sin(angle) + .5)
        p = part('POOL', x, z, 'S', pool[0], pool[1])
        if d.place(p):
            water = p
    if not water:
        return None
    d.avoid = [(latrine['x'], latrine['z'], 8 if village else 6)]
    for _ in range(cfg['homes']):
        home = None
        for _t in range(160):
            if home:
                break
            angle = rng.range(0, math.pi * 2)
            r = rp + 3.2 + rng.next_double() * rng.next_double() * (12 if village else 8)
            p = mound('HOME', angle, r, rng)
            if d.place(p):
                home = p
        if not home:
            return None
    half = 2 if village else 1
    nursery = None
    for _t in range(60):
        if nursery:
            break
        angle = rng.range(0, math.pi * 2)
        r = rp + 4.5 + rng.range(0, 7 if village else 4)
        x, z = floor(r * math.cos(angle) + .5), floor(r * math.sin(angle) + .5)
        p = part('NURSERY', x, z, 'S', half, half)
        if d.place(p):
            nursery = p
    if not nursery:
        return None
    if village:
        knoll = None
        for _t in range(40):
            if knoll:
                break
            angle = theta_l + 1.75 + rng.range(-.7, .7)
            r = hedge - 5
            x, z = floor(r * math.cos(angle) + .5), floor(r * math.sin(angle) + .5)
            p = part('KNOLL', x, z, 'S', 30)
            if d.place(p):
                knoll = p
        if not knoll:
            return None
    paths = {}
    blocked = set(d.taken) | cells(d.parts[0])
    hedge_cells = {}
    phi1, phi2, g0 = rng.range(0, math.pi * 2), rng.range(0, math.pi * 2), rng.range(0, math.pi * 2)
    gaps = [g0, g0 + 2.1 + rng.range(-.3, .3), g0 + 4.2 + rng.range(-.3, .3)]
    gap_half = 2.4 / hedge
    n = int(math.ceil(math.pi * 2 * hedge / .45))
    for i in range(n):
        theta = math.pi * 2 * i / n
        gap = False
        for g in gaps:
            diff = abs(math.atan2(math.sin(theta - g), math.cos(theta - g)))
            if diff < gap_half:
                gap = True
        r = hedge * (1 + .06 * math.sin(3 * theta + phi1) + .03 * math.sin(5 * theta + phi2)) + rng.range(-.7, .7)
        x, z = floor(r * math.cos(theta) + .5), floor(r * math.sin(theta) + .5)
        thick = rng.next_double() < .22
        loose = rng.next_double() < .12
        if gap or loose or (x, z) in blocked:
            continue
        hedge_cells.setdefault((x, z), 0)
        if thick:
            ex, ez = x + floor(math.cos(theta) + .5), z + floor(math.sin(theta) + .5)
            if (ex, ez) not in blocked:
                hedge_cells.setdefault((ex, ez), 1)
    connect(d, paths, blocked)
    for g in gaps:
        ex, ez = math.cos(g) * (hedge + 3), math.sin(g) * (hedge + 3)
        curve(paths, rng, ex, ez, math.cos(g) * rp, math.sin(g) * rp, blocked)
    return d.parts, paths, hedge_cells


def turn(x, z, quarter):
    for _ in range(quarter):
        x, z = -z, x
    return x, z


def plan(tier, seed, rotation=0, ox=0, oz=0, palette=0, level=64):
    rotation %= 4
    for attempt in range(8):
        rng = Rng(seed * 31 + attempt * 7919 + 17)
        local = demo_layout(rng) if tier == 'DEMO' else layout(tier, rng)
        if local is None:
            continue
        parts_l, paths_l, hedge_l = local
        parts = []
        for p in parts_l:
            x, z = turn(p['x'], p['z'], rotation)
            f = p['facing']
            for _ in range(rotation):
                f = CW[f]
            q = dict(p)
            q.update(x=ox + x, z=oz + z, facing=f)
            parts.append(q)
        tp = lambda src: {(ox + turn(k[0], k[1], rotation)[0], oz + turn(k[0], k[1], rotation)[1]): v for k, v in src.items()}
        return dict(tier=tier, seed=seed, rotation=rotation, ox=ox, oz=oz, level=level, palette=palette,
                    parts=parts, paths=tp(paths_l), hedge=tp(hedge_l), bound=TIERS[tier]['bound'])
    return None


def flat_cells(pl):
    out = set()
    for p in pl['parts']:
        out |= cells(p)
    return out


def slots(pl):
    G = pl['level']
    out = []
    for p in pl['parts']:
        k = p['kind']
        if k in ('HOME', 'HALL'):
            out.append(dict(kind=k, anchor=(fx(p, 0, 1), G, fz(p, 0, 1)), facing=p['facing']))
        elif k == 'GRANARY':
            out.append(dict(kind='GRANARY', anchor=(p['x'], G, p['z']), facing=p['facing']))
            r = right_of(p['facing'])
            out.append(dict(kind='STORE', anchor=(fx(p, -1, 1), G, fz(p, -1, 1)), facing=r))
            out.append(dict(kind='STORE', anchor=(fx(p, 1, 1), G, fz(p, 1, 1)), facing=OPP[r]))
        elif k == 'PLAZA':
            out.append(dict(kind='PLAZA', anchor=(p['x'], G, p['z'] + 1), facing='S'))
        elif k == 'KNOLL':
            out.append(dict(kind='LOOKOUT', anchor=(p['x'] + (1 if p['a'] >= 30 else 0), G + 2, p['z']), facing='U'))
    return out


# ---- block stamps (mirror of SiteBuilder for the parts that matter to the shelter survey)
EARTH = {'dirt', 'coarse_dirt', 'rooted_dirt', 'podzol', 'grass_block', 'moss_block', 'mud', 'clay_dirt',
         'stripped_log', 'log'}
SOLID = EARTH | {'gravel', 'clay', 'mossy_cobblestone', 'leaves'}
FREE = {'air', 'short_grass', 'fern', 'flower', 'lily_pad', 'forage_pile', 'wild_latrine', 'dead_bush'}


def stamp(pl):
    G = pl['level']
    blocks = {}
    flat = flat_cells(pl)
    seed = pl['seed']

    def H(x, z, s):
        return hash_cell(seed, x, z, s)

    def put(x, y, z, b):
        blocks[(x, y, z)] = b

    for (x, z) in flat:
        put(x, G - 1, z, 'grass_block')
    for p in pl['parts']:
        k = p['kind']
        if is_mound(p):
            halfw, depth, cavh, cavd = dims(k)
            granary, hall = k == 'GRANARY', k == 'HALL'
            for u in range(-halfw, halfw + 1):
                for w in range(0, depth + 1):
                    x, z = fx(p, u, w), fz(p, u, w)
                    h = H(x, z, 11)
                    door = w == 0 and u == 0
                    cav = abs(u) <= cavh and 1 <= w <= cavd
                    corner = abs(u) == halfw and w in (0, depth)
                    if door:
                        put(x, G - 1, z, 'gravel')
                        for y in range(3):
                            put(x, G + y, z, 'air')
                        continue
                    if cav:
                        put(x, G - 1, z, 'moss_block')
                        put(x, G, z, 'air')
                        put(x, G + 1, z, 'air')
                        put(x, G + 2, z, 'grass_block')
                    else:
                        wall = lambda hv: 'dirt' if hv < .34 else 'coarse_dirt' if hv < .6 else 'rooted_dirt' if hv < .82 else 'podzol'
                        put(x, G - 1, z, wall(h))
                        put(x, G, z, wall(H(x, z, 12)))
                        put(x, G + 1, z, wall(H(x, z, 13)))
                        if not corner:
                            put(x, G + 2, z, 'leaves' if abs(u) == halfw and H(x, z, 14) < .3 else 'grass_block')
                        else:
                            put(x, G + 2, z, 'air')
                    crown = abs(u) <= cavh and 1 <= w <= depth - 1
                    if crown:
                        put(x, G + 3, z, 'leaves' if granary and H(x, z, 15) < .6 else ('rooted_dirt' if H(x, z, 16) < .5 else 'moss_block'))
                        top = (not granary) and ((abs(u) <= 1 and w == 2) if hall else (u == 0 and w == 2))
                        if top:
                            put(x, G + 4, z, 'moss_block')
            if p['a'] & 1:
                for side in (-1, 1):
                    x, z = fx(p, side, 0), fz(p, side, 0)
                    put(x, G, z, 'stripped_log')
                    put(x, G + 1, z, 'stripped_log')
            if p['a'] & 2 and not granary:
                side = 1 if p['a'] & 4 else -1
                put(fx(p, side * halfw, 2), G, fz(p, side * halfw, 2), 'stripped_log')
            for kk in range(1, 4):
                for u in (-1, 0, 1):
                    x, z = fx(p, u, -kk), fz(p, u, -kk)
                    h = H(x, z, 17)
                    put(x, G - 1, z, 'coarse_dirt' if h < .45 else 'gravel' if (h < .8 or u == 0) else 'dirt_path')
                    for y in range(3):
                        put(x, G + y, z, 'air')
            if granary:
                for side in (-1, 1):
                    put(fx(p, side, 1), G, fz(p, side, 1), 'forage_pile')
        elif k == 'PLAZA':
            for (x, z) in cells(p):
                dx, dz = x - p['x'], z - p['z']
                h = H(x, z, 21)
                if abs(dx) <= 1 and abs(dz) <= 1:
                    top = 'gravel' if h < .55 else 'coarse_dirt'
                else:
                    top = 'coarse_dirt' if h < .45 else 'podzol' if h < .62 else 'dirt_path' if h < .88 else 'gravel'
                put(x, G - 1, z, top)
                put(x, G, z, 'air')
            put(p['x'], G, p['z'], 'slab')
            rp = p['a'] / 10.0
            seat = max(1.6, rp - .9)
            phase = p['c'] / 100.0
            for i in range(8):
                x = p['x'] + floor(seat * math.cos(phase + i * math.pi / 4) + .5)
                z = p['z'] + floor(seat * math.sin(phase + i * math.pi / 4) + .5)
                if abs(x - p['x']) + abs(z - p['z']) <= 1 or not in_plaza(p, x - p['x'], z - p['z']) or H(x, z, 22) < .22:
                    continue
                put(x, G, z, 'slab')
        elif k == 'POOL':
            for (x, z) in pool_water(p):
                put(x, G - 3, z, 'mud')
                put(x, G - 2, z, 'water')
                put(x, G - 1, z, 'lily_pad' if H(x, z, 31) < .22 else 'air')
                put(x, G, z, 'air')
            for (x, z) in pool_shelf(p):
                put(x, G - 2, z, 'clay' if H(x, z, 32) < .25 else 'mud')
                put(x, G - 1, z, 'air')
                put(x, G, z, 'air')
        elif k == 'LATRINE':
            b, depth = p['b'], latrine_depth(p)
            for u in range(-b, b + 1):
                for w in range(depth + 1):
                    x, z = fx(p, u, w), fz(p, u, w)
                    hedge = w == depth or abs(u) == b
                    put(x, G - 1, z, 'podzol')
                    put(x, G, z, 'leaves' if hedge else 'air')
                    put(x, G + 1, z, 'leaves' if hedge and H(x, z, 43) < .3 else 'air')
            for side in (-1, 1):
                put(fx(p, side, 1), G, fz(p, side, 1), 'wild_latrine')
            if p['a'] > 0 and depth == 3:
                put(fx(p, 0, 2), G, fz(p, 0, 2), 'wild_latrine')
        elif k == 'NURSERY':
            for dx in range(-p['a'], p['a'] + 1):
                for dz in range(-p['b'], p['b'] + 1):
                    x, z = p['x'] + dx, p['z'] + dz
                    put(x, G - 1, z, 'moss_block' if H(x, z, 51) < .55 else 'grass_block')
                    g = H(x, z, 52)
                    put(x, G, z, 'short_grass' if g < .38 else 'flower' if g < .62 else 'fern' if g < .74 else 'air')
            put(p['x'] - 1, G, p['z'] + p['b'], 'log')
            put(p['x'], G, p['z'] + p['b'], 'log')
            put(p['x'] + p['a'], G, p['z'] - p['b'], 'mossy_cobblestone')
        elif k == 'KNOLL':
            r = p['a'] / 10.0
            r2 = r * .5 + .2
            for (x, z) in cells(p):
                dx, dz = x - p['x'], z - p['z']
                upper = dx * dx + dz * dz <= r2 * r2
                put(x, G - 1, z, 'dirt')
                if upper:
                    put(x, G, z, 'dirt')
                    put(x, G + 1, z, 'grass_block')
                    put(x, G + 2, z, 'log' if p['a'] >= 30 and dx == 0 and dz == 0 else 'air')
                else:
                    put(x, G, z, 'grass_block')
                    put(x, G + 1, z, 'air')
    return blocks


class World:
    """Flat world: dirt below the site level, air above, plus the stamped blocks."""

    def __init__(self, pl):
        self.G = pl['level']
        self.blocks = stamp(pl)

    def get(self, x, y, z):
        b = self.blocks.get((x, y, z))
        if b is not None:
            return b
        return 'grass_block' if y == self.G - 1 else 'dirt' if y < self.G - 1 else 'air'


def earth(w, x, y, z):
    return w.get(x, y, z) in EARTH


def stand(w, x, y, z):
    return w.get(x, y, z) in FREE and w.get(x, y - 1, z) in SOLID - {'leaves'} and w.get(x, y - 1, z) != 'water'


def roof(w, x, y, z):
    return any(earth(w, x, y + k, z) for k in (1, 2, 3))


def enclosed(w, x, y, z):
    if not stand(w, x, y, z):
        return False
    banks = 0
    for (dx, dz) in DIRS.values():
        for dist in (1, 2, 3):
            if earth(w, x + dx * dist, y, z + dz * dist):
                banks += 1
                break
    return banks >= 2 if roof(w, x, y, z) else banks >= 3


def survey(w, center, facing):
    x, y, z = center
    if not enclosed(w, x, y, z):
        return None
    f = DIRS[facing]
    ex, ez = x + f[0], z + f[1]
    entrance = (ex, y, ez)
    if not stand(w, ex, y, ez) or enclosed(w, ex, y, ez):
        entrance = (ex, y + 1, ez)
        if roof(w, x, y, z) or not stand(w, *entrance) or enclosed(w, *entrance):
            return None
    queue, seen, beds = deque([center]), {center}, []
    while queue and len(beds) < 16:
        pos = queue.popleft()
        if not enclosed(w, *pos):
            continue
        beds.append(pos)
        for (dx, dz) in DIRS.values():
            nxt = (pos[0] + dx, pos[1], pos[2] + dz)
            if abs(nxt[0] - x) <= 3 and abs(nxt[2] - z) <= 3 and nxt not in seen:
                seen.add(nxt)
                queue.append(nxt)
    return entrance, beds


def reachable(w, pl):
    """Plaza stand cell to every dwelling door and granary door over standable cells (dirt path counts as walkable)."""
    G = pl['level']
    start = next(s for s in slots(pl) if s['kind'] == 'PLAZA')['anchor']

    def walkable(x, y, z):
        # a slab (stump, seat stone) is a half-block step, not a wall
        if w.get(x, y, z) not in FREE | {'slab'} or w.get(x, y + 1, z) not in FREE:
            return False
        below = w.get(x, y - 1, z)
        return below in SOLID - {'leaves'} or below == 'dirt_path'

    seen, queue = {start}, deque([start])
    while queue and len(seen) < 9000:
        x, y, z = queue.popleft()
        for (dx, dz) in DIRS.values():
            for dy in (1, 0, -1, -2):
                nxt = (x + dx, y + dy, z + dz)
                if abs(nxt[0] - pl['ox']) > pl['bound'] or abs(nxt[2] - pl['oz']) > pl['bound']:
                    continue
                if walkable(*nxt) and nxt not in seen:
                    seen.add(nxt)
                    queue.append(nxt)
                    break
    bad = []
    for s in slots(pl):
        if s['kind'] in ('HOME', 'HALL'):
            f = DIRS[s['facing']]
            door = (s['anchor'][0] + f[0], s['anchor'][1], s['anchor'][2] + f[1])
        elif s['kind'] == 'GRANARY':
            door = s['anchor']
        else:
            continue
        if door not in seen:
            bad.append((s['kind'], door))
    return bad


def check(pl):
    """Returns a list of problems for one plan (empty list = fine)."""
    w = World(pl)
    problems = []
    for s in slots(pl):
        if s['kind'] in ('HOME', 'HALL'):
            res = survey(w, s['anchor'], s['facing'])
            want = 9 if s['kind'] == 'HOME' else 15
            if res is None:
                problems.append(f"{s['kind']} {s['anchor']} survey failed")
            elif len(res[1]) < want:
                problems.append(f"{s['kind']} {s['anchor']} beds {len(res[1])}<{want}")
    problems += [f'{k} door {d} unreachable' for k, d in reachable(w, pl)]
    return problems


# ---- rendering
def render(pl, scale=14, margin=3):
    from PIL import Image, ImageDraw
    b = pl['bound'] + margin
    ox, oz = pl['ox'], pl['oz']
    size = (2 * b + 1) * scale
    img = Image.new('RGB', (size, size), (108, 150, 82))
    dr = ImageDraw.Draw(img)

    def box(x, z, col, inset=0, shrink=0):
        px, pz = (x - ox + b) * scale, (z - oz + b) * scale
        dr.rectangle([px + inset, pz + inset, px + scale - 1 - inset, pz + scale - 1 - inset], fill=col)

    for x in range(ox - b, ox + b + 1):
        for z in range(oz - b, oz + b + 1):
            n = hash_cell(pl['seed'], x, z, 99)
            tint = int(n * 14) - 7
            box(x, z, (108 + tint, 150 + tint, 82 + tint))
    # meadow tufts
    rng_cells = []
    for x in range(ox - pl['bound'], ox + pl['bound'] + 1):
        for z in range(oz - pl['bound'], oz + pl['bound'] + 1):
            dist = math.hypot(x - ox, z - oz)
            if dist > pl['bound'] - 1.5:
                continue
            dens = .26 if TIERS[pl['tier']]['hedge'] == 0 else (.14 if dist <= TIERS[pl['tier']]['hedge'] else .36)
            if hash_cell(pl['seed'], x, z, 81) < dens:
                rng_cells.append((x, z))
    flat = flat_cells(pl)
    for (x, z) in rng_cells:
        if (x, z) in flat or (x, z) in pl['paths'] or (x, z) in pl['hedge']:
            continue
        pick = hash_cell(pl['seed'], x, z, 82)
        col = (70, 120, 55) if pick < .65 else (50, 100, 60) if pick < .9 else (225, 190, 70)
        box(x, z, col, inset=4)
    for (x, z), v in pl['paths'].items():
        if (x, z) in flat:
            continue
        box(x, z, {0: (150, 118, 80), 1: (170, 140, 90), 2: (110, 90, 60)}[v], inset=1)
    for (x, z), v in pl['hedge'].items():
        box(x, z, (38, 92, 46), inset=0)
    # footprints
    for p in pl['parts']:
        k = p['kind']
        if is_mound(p):
            halfw, depth, cavh, cavd = dims(k)
            for kk in range(1, 4):
                for u in (-1, 0, 1):
                    box(fx(p, u, -kk), fz(p, u, -kk), (176, 160, 130), inset=1)
            for u in range(-halfw, halfw + 1):
                for w in range(0, depth + 1):
                    cav = abs(u) <= cavh and 1 <= w <= cavd
                    corner = abs(u) == halfw and w in (0, depth)
                    col = (92, 66, 44) if not cav else (60, 44, 34)
                    if not cav and not corner and w not in (0, depth) and abs(u) < halfw:
                        col = (86, 120, 62)
                    if corner:
                        col = (104, 78, 52)
                    box(fx(p, u, w), fz(p, u, w), col)
            box(p['x'], p['z'], (214, 206, 190))
            if k == 'GRANARY':
                for side in (-1, 1):
                    box(fx(p, side, 1), fz(p, side, 1), (236, 200, 70), inset=3)
        elif k == 'PLAZA':
            for (x, z) in cells(p):
                h = hash_cell(pl['seed'], x, z, 21)
                box(x, z, (196, 170, 120) if h < .5 else (178, 150, 104) if h < .8 else (160, 160, 158))
            box(p['x'], p['z'], (150, 100, 56), inset=2)
        elif k == 'POOL':
            for (x, z) in pool_shelf(p):
                box(x, z, (120, 98, 70))
            for (x, z) in pool_water(p):
                box(x, z, (60, 110, 190))
        elif k == 'LATRINE':
            b2, depth = p['b'], latrine_depth(p)
            for u in range(-b2, b2 + 1):
                for w in range(depth + 1):
                    hedge = w == depth or abs(u) == b2
                    box(fx(p, u, w), fz(p, u, w), (38, 92, 46) if hedge else (120, 96, 70))
            for side in (-1, 1):
                box(fx(p, side, 1), fz(p, side, 1), (196, 196, 190), inset=3)
        elif k == 'NURSERY':
            for dx in range(-p['a'], p['a'] + 1):
                for dz in range(-p['b'], p['b'] + 1):
                    h = hash_cell(pl['seed'], p['x'] + dx, p['z'] + dz, 52)
                    box(p['x'] + dx, p['z'] + dz, (110, 150, 90))
                    if .38 <= h < .62:
                        box(p['x'] + dx, p['z'] + dz, (230, 120, 160), inset=5)
        elif k == 'KNOLL':
            r = p['a'] / 10.0
            r2 = r * .5 + .2
            for (x, z) in cells(p):
                dx, dz = x - p['x'], z - p['z']
                box(x, z, (150, 130, 76) if dx * dx + dz * dz <= r2 * r2 else (120, 140, 70))
    # facing ticks on doors
    for p in pl['parts']:
        if is_mound(p):
            f = DIRS[p['facing']]
            cx, cz = (p['x'] - ox + b) * scale + scale // 2, (p['z'] - oz + b) * scale + scale // 2
            dr.line([cx, cz, cx + f[0] * scale, cz + f[1] * scale], fill=(250, 250, 250), width=2)
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '.local', 'previews', 'settlement'))
    ap.add_argument('--check', type=int, default=0)
    args = ap.parse_args()
    if args.check:
        for tier in ('BAND', 'VILLAGE', 'DEMO'):
            ok = bad = none = 0
            first = None
            for seed in range(args.check):
                for rot in range(4):
                    pl = plan(tier, seed * 7919 + 13, rot)
                    if pl is None:
                        none += 1
                        continue
                    problems = check(pl)
                    if problems:
                        bad += 1
                        first = first or (seed, rot, problems[:3])
                    else:
                        ok += 1
            print(f'{tier}: ok={ok} problems={bad} no-layout={none} first={first}')
        return 0
    from PIL import Image
    os.makedirs(args.out, exist_ok=True)
    picks = {'band': ('BAND', 4242, 1), 'village': ('VILLAGE', 777, 0), 'demo': ('DEMO', 2101, 0)}
    images = {}
    for name, (tier, seed, rot) in picks.items():
        pl = plan(tier, seed, rot)
        if pl is None:
            print('no layout for', name)
            continue
        problems = check(pl)
        print(name, 'parts', len(pl['parts']), 'paths', len(pl['paths']), 'hedge', len(pl['hedge']), 'check', problems or 'ok')
        images[name] = render(pl)
        images[name].save(os.path.join(args.out, f'{name}.png'))
    if images:
        total_w = sum(i.width for i in images.values()) + 20 * (len(images) + 1)
        sheet = Image.new('RGB', (total_w, max(i.height for i in images.values()) + 40), (30, 30, 34))
        x = 20
        for name, im in images.items():
            sheet.paste(im, (x, 20))
            x += im.width + 20
        sheet.save(os.path.join(args.out, 'tiers.png'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
