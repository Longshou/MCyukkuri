"""The turned hat's discrete deck (hat_deck_layout.json) and the one hat transform contract (hat_frames.json).

build.py calls export() with the hat measures it has just written to hat_vessel.json. Every place the game may give a
body aboard is a slot of this table, per hull mode: the owner's stern seat and her high seat for late pregnancy, the
cabin places inside the opening (bow perch, front well, nest hollow), the front brim, the toilet rims, the rescue rims,
the owner's care stations and the larder at the bottom of the crown cavity. Conflicts between slots, walking edges,
entry points round the brim, larder tiers and care pairs are measured here once, offline, from the body sizes the game
uses (body_geometry.json, family.json growth, HatVessel.bodySpace), so the runtime only enumerates a small table.

Coordinates are hull-local hat units (HatVessel: x across with the owner's braid side on +X, z along the long brim
axis, the owner's stern on +Z and the bow on -Z, y up from the water line afloat or the ground under a nest); one hat
unit is hullScale/16 blocks. A body's yaw r on the hull faces hull-local (sin r, -cos r). Project design throughout.
"""
import itertools, math
from delivery import Hat

ORDER = ['newborn', 'baby', 'small_child', 'child', 'large_child', 'juvenile', 'adult', 'gravid']
YOUNG = ORDER[:6]
EPS = 1e-4
CABIN = ('BOW_PERCH', 'WELL_FRONT', 'NEST_HOLLOW')
OWNER = ('CAPTAIN', 'CAPTAIN_HIGH', 'SERVICE')


def rank(c): return ORDER.index(c)


def r4(v): return round(float(v), 4)


def facing(yaw):
    a = math.radians(yaw); return (math.sin(a), -math.cos(a))


def yaw_of(f): return round(math.degrees(math.atan2(f[0], -f[1])), 1)


# ------------------------------------------------------------------ boxes seen from above
class Box:
    """An oriented rectangle (centre, unit forward f, half extents along f and across) with a height interval."""

    def __init__(self, cx, cz, f, along, side, y0, y1):
        self.c = (cx, cz); self.f = f; self.s = (-f[1], f[0]); self.a, self.b = along, side; self.y = (y0, y1)

    def corners(self):
        (cx, cz), f, s = self.c, self.f, self.s
        return [(cx+f[0]*i*self.a+s[0]*j*self.b, cz+f[1]*i*self.a+s[1]*j*self.b) for i, j in ((1, 1), (1, -1), (-1, -1), (-1, 1))]

    def half(self):
        f, s = self.f, self.s
        return abs(f[0])*self.a+abs(s[0])*self.b, abs(f[1])*self.a+abs(s[1])*self.b

    def dist(self, p):
        dx, dz = p[0]-self.c[0], p[1]-self.c[1]
        u, v = dx*self.f[0]+dz*self.f[1], dx*self.s[0]+dz*self.s[1]
        return math.hypot(max(0, abs(u)-self.a), max(0, abs(v)-self.b))


def y_overlap(a, b, tol=.05): return a[0] < b[1]-tol and b[0] < a[1]-tol


def boxes_meet(p, q, gap):
    if not y_overlap(p.y, q.y): return False
    for axis in (p.f, p.s, q.f, q.s):
        pp = [c[0]*axis[0]+c[1]*axis[1] for c in p.corners()]; qq = [c[0]*axis[0]+c[1]*axis[1] for c in q.corners()]
        if max(pp)+gap <= min(qq) or max(qq)+gap <= min(pp): return False
    return True


# ------------------------------------------------------------------ bodies
class Envelope:
    """One body size class in one hull mode (hat units): collision width, soft radius, the body's bounds and its worn
    hat (HatVessel.bodySpace: the young's own hat at its own hat multiplier; the owner has none, her hat is the hull).
    young_hat: the young's worn-hat multiplier (the deck recipe's young_hat_multiplier, at most the hull's own)."""

    def __init__(self, name, scale, ratio, body, V, pregnancy, young_hat=None):
        k = scale/ratio; bx, by, bz = body['bounds']; hk = k*min(ratio, ratio if young_hat is None else young_hat)
        sw = sd = sf = sb = 1.
        if name == 'gravid':  # LiveBirthGeometry.bodyDimensions at a full belly
            p = pregnancy; sw = (1+p['swell'])*(1+p['low_wide']); sd = 1+p['swell']
            sf, sb = sd*(1+p['low_front']), sd*(1+p['low_back'])
        self.name, self.scale = name, scale
        self.width = (bx[1]-bx[0])*k*sw; self.height = body['height']*k*sd; self.radius = .42*self.width
        self.side, self.front, self.back = self.width/2, -bz[0]*k*sf, bz[1]*k*sb
        xs = [v for r in V['brim_rects'] for v in r[:2]]; zs = [v for r in V['brim_rects'] for v in r[2:]]
        anchor = V['anchor_worn']
        self.hat = None if name in ('adult', 'gravid') else dict(
            front=max(xs)*hk, back=-min(xs)*hk, side=max(abs(v) for v in zs)*hk,
            base=anchor[1]*k, height=V['keel']*hk, behind=anchor[2]*k)

    def shapes(self, x, y, z, yaw, below=None):
        f = facing(yaw); off = (self.back-self.front)/2
        out = [Box(x-f[0]*off, z-f[1]*off, f, (self.front+self.back)/2, self.side, y if below is None else below, y+self.height)]
        h = self.hat
        if h:
            off = (h['back']-h['front'])/2+h['behind']
            out.append(Box(x-f[0]*off, z-f[1]*off, f, (h['front']+h['back'])/2, h['side'], y+h['base'], y+h['base']+h['height']))
        return out

    def export(self):
        out = dict(scale=r4(self.scale), width=r4(self.width), radius=r4(self.radius), height=r4(self.height),
                   body=dict(side=r4(self.side), front=r4(self.front), back=r4(self.back)))
        if self.hat: out['hat'] = {k: r4(v) for k, v in self.hat.items()}
        return out


# ------------------------------------------------------------------ the hull surface
class Hull:
    def __init__(self, V, surface):
        self.hat = Hat(V, surface)
        self.top = self.hat.top; self.sq = V['squash'][surface]; self.opening = V['opening']
        self.sections = [(s['rect'], self.top-s['depth']*self.sq) for s in V['cavity_sections']]
        self.levels = [lv for _, lv in self.sections]; self.floor = self.levels[-1]
        self.brims = V['brim_rects']
        self.bx = max(abs(v) for r in self.brims for v in r[:2]); self.bz = max(abs(v) for r in self.brims for v in r[2:])

    def surface(self, x, z): return self.hat.surface(x, z)

    def support(self, x, z, half):
        """HatVessel.travelSupport: the highest surface under the collision box corners; None if any is off the hat."""
        ys = [self.surface(x+i*half, z+j*half) for i in (-1, 0, 1) for j in (-1, 0, 1)]
        return None if any(v is None for v in ys) else max(ys)

    def contains(self, y, x, z, hx, hz, wall):
        """HatVessel.cavityContains: a box at height y lies inside the cavity section open at that height."""
        if y >= self.top-EPS: return True
        if y < self.floor-EPS: return False
        for rect, level in self.sections:
            if y < level-EPS: continue
            return rect[0] <= x-hx-wall and x+hx+wall <= rect[1] and rect[2] <= z-hz-wall and z+hz+wall <= rect[3]
        return False

    def on_brim(self, x, z):
        h = self.surface(x, z); return h is not None and abs(h-self.top) < 1e-6

    def extent(self, x, z, d):
        t = 0.
        while self.surface(x+d[0]*t, z+d[1]*t) is not None and t < 64: t += .0625
        return max(0., t-.0625)

    def zone(self, y):
        """Which cavity tier a settled place stands on: the front tread, the second tier, or the deep hollow."""
        if abs(y-self.levels[0]) < EPS: return 'BOW_PERCH'
        if abs(y-self.levels[1]) < EPS: return 'WELL_FRONT'
        return 'NEST_HOLLOW'


# ------------------------------------------------------------------ the deck of one hull mode
class Deck:
    def __init__(self, name, spec, V, body, cargo, pile, D, pregnancy, stick=None):
        self.name, self.spec, self.V, self.D = name, spec, V, D
        self.ratio = V['aquatic_scale'] if spec['hull_ratio'] == 'aquatic_scale' else spec['hull_ratio']
        self.hull = Hull(V, spec['surface'])
        self.env = {c: Envelope(c, s, self.ratio, body, V, pregnancy, D.get('young_hat_multiplier')) for c, s in D['classes'].items()}
        self.block = 16/self.ratio  # hat units per block for a standard (growth 1) owner
        self.gap, self.wall = D['gap'], D['wall']
        self.slots, self.cargo, self.pile, self.stick = [], cargo, pile, stick
        self.heaps, self.tiers, self.fixtures = [], [], {}

    # ---------------------------------------------------------- validity of one body at one place
    def cabin_y(self, c, x, z, yaw):
        """A settled cabin place: feet on the highest surface under the collision box, at least D['support'] of the
        round body over that surface, the box and the worn hat inside the cavity open at their heights."""
        e, H = self.env[c], self.hull; half = e.width/2
        y = H.support(x, z, half+self.wall)
        if y is None or y >= H.top-EPS: return None
        n = hit = 0
        for i in range(-3, 4):
            for j in range(-3, 4):
                if i*i+j*j > 9: continue
                n += 1; s = H.surface(x+e.radius*i/3, z+e.radius*j/3); hit += s is not None and abs(s-y) < EPS
        if hit < self.D['support']*n: return None
        if not H.contains(y, x, z, half, half, self.wall): return None
        if e.hat:
            hb = e.shapes(x, y, z, yaw)[1]; hx, hz = hb.half()
            if not H.contains(y+e.hat['base'], hb.c[0], hb.c[1], hx, hz, self.wall): return None
        return y

    def brim_ok(self, c, x, z):
        """HatVessel.brimPlace: the round body with its margin and every collision box corner on the brim top."""
        e, H = self.env[c], self.hull; m = self.V['delivery']['brim_standing']['margin']; half = e.width/2
        pts = [(x, z)]+[(x+(e.radius+m)*math.cos(i*math.pi/8), z+(e.radius+m)*math.sin(i*math.pi/8)) for i in range(16)]
        pts += [(x+i*half, z+j*half) for i in (-1, 0, 1) for j in (-1, 0, 1)]
        return all(H.on_brim(px, pz) for px, pz in pts)

    def rim_ok(self, c, x, z, inward, rear_clear):
        """HatBoatEntity.frontEdgePlace / rearBrimPlace: a foot patch on the brim, the body leaning over the water or
        the opening; a toilet place also needs the body's rear past the hull outline."""
        e, H = self.env[c], self.hull; s = min(1.1, max(.45, e.radius*.25)); d = inward
        pts = [(x, z), (x+d[0]*s, z+d[1]*s), (x+d[0]*s*.15-d[1]*s*.45, z+d[1]*s*.15+d[0]*s*.45),
               (x+d[0]*s*.15+d[1]*s*.45, z+d[1]*s*.15-d[0]*s*.45)]
        if not all(H.on_brim(px, pz) for px, pz in pts): return False
        rear = e.width*.55+.06*self.block
        return not rear_clear or H.surface(x-d[0]*rear, z-d[1]*rear) is None

    # ---------------------------------------------------------- slots and contacts
    def add(self, sid, kind, pos, yaw, classes, night, jump, **extra):
        s = dict(id=sid, kind=kind, pos=list(pos), yaw=yaw, classes=sorted(classes, key=rank), night=night, requires_jump=jump)
        s.update(extra); self.slots.append(s); return s

    def owner(self, slot, c): return c in ('adult', 'gravid') and slot['kind'] in OWNER+('TOILET_RIM',)

    def shapes(self, slot, c):
        x, y, z = slot['pos']; return self.env[c].shapes(x, y, z, slot['yaw'])

    def collide(self, a, ca, b, cb):
        """Two bodies would touch: soft round bodies (HatVessel radius) where their heights meet, and the bodies' bounds
        and worn hats as boxes. A young may lie beneath the owner when it stays wholly below her (user 2026-10-14)."""
        if a is b: return True
        if self.owner(a, ca) and self.owner(b, cb): return True  # one owner
        sa, sb = self.shapes(a, ca), self.shapes(b, cb)
        ea, eb = self.env[ca], self.env[cb]
        (ax, _, az), (bx, _, bz) = a['pos'], b['pos']
        if y_overlap(sa[0].y, sb[0].y) and math.hypot(ax-bx, az-bz) < ea.radius+eb.radius+self.gap: return True
        # a young's worn hat keeps a wider berth from the owner: her hair, braid and seated sway reach past her bounds
        hat_gap = self.D.get('owner_hat_gap', self.gap) if ca in ('adult', 'gravid') or cb in ('adult', 'gravid') else self.gap
        for i, p in enumerate(sa):
            for j, q in enumerate(sb):
                if i == 0 and j == 0: continue
                if boxes_meet(p, q, hat_gap): return True
        return boxes_meet(sa[0], sb[0], -min(ea.width, eb.width)*.08)  # the bounds themselves, a little soft

    def pack(self, c, valid, cost, fixed):
        """Greedy packing of one class over valid (x, y, z, yaw) places in cost order, clear of the fixed bodies."""
        chosen = []; total = 0.
        for k, x, y, z, yaw in sorted((cost(x, y, z, yaw), x, y, z, yaw) for x, y, z, yaw in valid):
            probe = dict(id='?', kind='NEST_HOLLOW', pos=[x, y, z], yaw=yaw)
            if any(self.collide(o, oc, probe, c) for o, oc in fixed+chosen): continue
            chosen.append((probe, c)); total += k
        return chosen, total

    def build(self):
        D, H, V = self.D, self.hull, self.V
        op = H.opening; step = D['grid']; fixed = []
        captain = None
        if self.spec['captain']:
            e = self.env['adult']; back = max(e.width/2, e.back); z = max(0., op[3]-back-.05)  # HatBoatEntity.ownerSeat
            captain = self.add('captain', 'CAPTAIN', (0, H.support(0, z, e.width/2+self.wall), z), 0, ['adult'], True, False)
            fixed = [(captain, 'adult')]
            # late pregnancy: the back of her base pressed on the stern brim, its rear edge within the brim, the swollen
            # belly hanging over the opening so the front cavity is free for the young (D A5); she leans back to sit there
            g = self.env['gravid']; z = H.bz-g.back-D['high_seat_margin']
            rest = [z+g.back*f for f in (.35, .5, .65, .8, .95)]
            if all(H.on_brim(x, rz) for rz in rest for x in (-g.side*.3, 0, g.side*.3)):
                self.add('captain_high', 'CAPTAIN_HIGH', (0, H.top, z), 0, ['adult', 'gravid'], True, False, lean_back=6,
                         rests_on=[r4(rest[0]), r4(rest[-1])])
        if self.spec['cargo']:
            # the larder: the one store lies on the bottom of the crown cavity, toward the bow, where cargo.py settled
            # it (user 2026-10-26: no brim heaps); the owner's underside stays free for the young by night
            for t, pile in enumerate(self.pile):
                if pile is None: self.heaps.append(None); continue
                (x, z), (x0, x1, z0, z1) = pile['centre'], pile['bounds']
                y = H.top-V['floor']*H.sq  # the crown floor; cells are heights over it, unsquashed
                self.heaps.append(dict(x=x, y=y, z=z, height=pile['height']*H.sq, bounds=[x+x0, x+x1, z+z0, z+z1],
                                       cells=pile['cells'], grid=pile['grid']))
        # cabin places: each size class packed alone round the owner, by day from the bow perch, by night close round
        # and beneath her, every facing set tried; the fullest packings are merged into one set of places
        cells = [(op[0]+i*step, op[2]+j*step) for i in range(int((op[1]-op[0])/step)+1) for j in range(int((op[3]-op[2])/step)+1)]
        yaws = sorted({y for ys in D['yaw_sets'] for y in ys})
        cz = captain['pos'][2] if captain else 0.
        regions = [((px, cz if pz == 'owner' else pz), ledge) for px, pz, ledge in D['pack_from']]
        picks = []
        for c in D['cabin_classes']:
            valid = [(x, y, z, yaw) for x, z in cells for yaw in yaws for y in [self.cabin_y(c, x, z, yaw)] if y is not None]
            for h in self.heaps or [None]:  # fullest packings with the larder empty and at each tier
                room = [v for v in valid if h is None or not any(self.heap_hits(h, b) for b in self.env[c].shapes(*v))]
                found = []
                for (px, pz), ledge in regions:
                    for ys in D['yaw_sets']:
                        found.append(self.pack(c, [v for v in room if v[3] in ys],
                                               lambda x, y, z, yaw: ledge*(H.top-y)+math.hypot(x-px, z-pz)+D['yaw_cost']*ys.index(yaw), fixed))
                most = max(len(g[0]) for g in found)
                for chosen, _ in sorted((g for g in found if len(g[0]) == most), key=lambda g: g[1])[:D['packings']]:
                    picks += chosen
        merged = []
        for probe, c in picks:
            if not any(math.dist(m['pos'], probe['pos']) < D['merge'] and m['yaw'] == probe['yaw'] for m in merged): merged.append(probe)
        merged.sort(key=lambda p: (-p['pos'][1], p['pos'][2], abs(p['pos'][0]), -p['pos'][0]))
        count = {}
        for p in merged:
            x, y, z = p['pos']; kind = H.zone(y); count[kind] = count.get(kind, 0)+1
            ok = [c for c in D['cabin_classes'] if (lambda v: v is not None and abs(v-y) < EPS)(self.cabin_y(c, x, z, p['yaw']))]
            self.add(f'{kind.lower()}_{count[kind]}', kind, (x, y, z), p['yaw'], ok, True, False)
        if self.spec['brim']:
            # the front brim: jumpers stand on it by day facing the water, from the middle outward (brim_standing)
            ring = sorted((abs(x), -x, x, z) for i in range(int(2*H.bx/step)+1) for j in range(int((H.bz+op[2])/step)+1)
                          for x, z in [(-H.bx+i*step, -H.bz+j*step)] if z < op[2] and H.on_brim(x, z))
            picks = []
            for c in D['brim_classes']:
                valid = [(x, H.top, z, 0) for _, _, x, z in ring if self.brim_ok(c, x, z)]
                picks += self.pack(c, valid, lambda x, y, z, yaw: abs(x)+.25*abs(z-(-H.bz+op[2])/2), fixed)[0]
            merged = []
            for probe, c in picks:
                if not any(math.dist(m['pos'], probe['pos']) < D['merge'] for m in merged): merged.append(probe)
            merged.sort(key=lambda p: (abs(p['pos'][0]), -p['pos'][0]))
            for i, p in enumerate(merged):
                x, _, z = p['pos']
                self.add(f'brim_{i+1}', 'BRIM', (x, H.top, z), 0, [c for c in D['brim_classes'] if self.brim_ok(c, x, z)], False, True)
        if self.spec['service']:
            # toilet rims on the two rear side brims, the body's back out over the water (D 1.10.5)
            for side, sign in (('r', 1), ('l', -1)):
                best = None
                for i in range(int(H.bz/step)+1):
                    z = op[3]-i*step
                    for xi in range(int((H.bx-op[1])/step)+1):
                        x = sign*(op[1]+.2+xi*step)
                        ok = [c for c in D['toilet_classes'] if self.rim_ok(c, x, z, (-sign, 0), True)]
                        if ok and (best is None or len(ok) > len(best[2])): best = (x, z, ok)
                    if best and len(best[2]) == len(D['toilet_classes']): break
                if best:
                    x, z, ok = best
                    self.add(f'toilet_rim_{side}', 'TOILET_RIM', (x, H.top, z), yaw_of((-sign, 0)), ok, True, False, purpose='toilet')
            # rescue rims on the inner edge of the side brims, as near the middle as clears the owner (D A1)
            for side, sign in (('r', 1), ('l', -1)):
                best = None
                for i in range(int(2*op[3]/step)+1):
                    z = op[2]+i*step
                    for xi in range(int((H.bx-op[1])/step)+1):
                        x = sign*(op[1]+.2+xi*step)
                        ok = [c for c in D['rescue_classes'] if self.rim_ok(c, x, z, (-sign, 0), False)
                              and not (captain and self.collide(captain, 'adult', dict(id='?', kind='RESCUE_RIM', pos=[x, H.top, z], yaw=yaw_of((-sign, 0))), c))]
                        if D['rescue_classes'][-1] in ok and (best is None or abs(z) < abs(best[1])): best = (x, z, ok)
                if best:
                    x, z, ok = best
                    self.add(f'rescue_rim_{side}', 'RESCUE_RIM', (x, H.top, z), yaw_of((-sign, 0)), ok, True, False, purpose='rescue', lease_only=True)
            if captain:
                # the owner's care stations: her own seat, then forward along the opening's centre line
                e = self.env['adult']; z = captain['pos'][2]; found = []
                while z > op[2]:
                    z -= step; y = H.support(0, z, e.width/2+self.wall)
                    if y is None or not H.contains(y, 0, z, e.width/2, e.width/2, self.wall): break
                    found.append((z, y))
                chosen = found[D['care_step']-1::D['care_step']]
                if found and found[-1] not in chosen: chosen.append(found[-1])
                for n, (z, y) in enumerate(chosen):
                    self.add(f'captain_care_{n+1}', 'SERVICE', (0, y, z), 0, ['adult'], True, False, purpose='care')
        if self.heaps:
            last = self.heaps[-1]
            self.add('larder', 'LARDER', (last['x'], last['y'], last['z']), 0, [], True, False, cargo=True)
        self.rank_slots()

    def rank_slots(self):
        """Preference order (lower first): by day the bow perch and brim, by night the nest hollow beneath her."""
        cap = next((s for s in self.slots if s['kind'] == 'CAPTAIN'), None)
        order = {'day': ['BOW_PERCH', 'BRIM', 'WELL_FRONT', 'NEST_HOLLOW'], 'night': ['NEST_HOLLOW', 'WELL_FRONT', 'BOW_PERCH']}
        for when, kinds in order.items():
            rest = [s for s in self.slots if s['kind'] in kinds]
            # by day a young on the bow perch faces out over the bow (yaw 0), by night it turns to her (yaw 180)
            face = 0 if when == 'day' else 180
            key = lambda s: (kinds.index(s['kind']), s['kind'] in CABIN and s['yaw'] != face,
                             math.hypot(s['pos'][0], s['pos'][2]-(cap['pos'][2] if cap and when == 'night' else self.hull.opening[2])))
            for n, s in enumerate(sorted(rest, key=key)): s.setdefault('prefer', {})[when] = n+1

    # ---------------------------------------------------------- measured relations
    def body_slots(self): return [s for s in self.slots if s['classes']]

    def conflicts(self):
        """For each slot and class: the other slots, each with the smallest class there that would touch it."""
        for a in self.body_slots():
            table = {}
            for ca in a['classes']:
                row = {}
                for b in self.body_slots():
                    if b is a: continue
                    hit = [cb for cb in b['classes'] if self.collide(a, ca, b, cb)]
                    if hit: row[b['id']] = min(hit, key=rank)
                table[ca] = row
            a['conflicts'] = table

    def heap_hits(self, h, box):
        """A body or hat box meets the measured pile: some pile column under the box rises above the box's bottom."""
        if not y_overlap(box.y, (h['y'], h['y']+h['height']), .025): return False
        g = h['grid']
        for (i, j), top in h['cells'].items():
            if box.dist((h['x']+i*g, h['z']+j*g)) <= self.wall+g/2 and box.y[0] < h['y']+top*self.hull.sq-.025: return True
        return False

    def larder_tiers(self):
        out = []; lip = self.D['larder_lip']
        for t, h in enumerate(self.heaps):
            row = dict(tier=t, max_count=self.cargo['tier_max_counts'][t], occupies=[], zone=None, excludes={}, lip=[])
            if h is not None:
                for s in self.body_slots():
                    for c in s['classes']:
                        if any(self.heap_hits(h, b) for b in self.shapes(s, c)): row['excludes'][s['id']] = c; break
                top = h['y']+h['height']
                for s in self.body_slots():
                    if s['kind'] not in CABIN or s['id'] in row['excludes']: continue
                    c = s['classes'][0]; e = self.env[c]; x, y, z = s['pos']; b = h['bounds']
                    d = math.hypot(max(b[0]-x, 0, x-b[1]), max(b[2]-z, 0, z-b[3]))
                    if d <= e.radius+lip['reach']*self.block and y+e.height/2-top <= lip['height']*self.block: row['lip'].append(s['id'])
                row.update(occupies=['larder'], zone=dict(x=r4(h['x']), y=r4(h['y']), z=r4(h['z']), height=r4(h['height']),
                                                          bounds=[r4(v) for v in h['bounds']]))
            out.append(row)
        return out

    def paddle(self):
        """The stowed stick, where the stick prop's own stow anchor lays it (paddle_stick.json anchors.stow_hull: the left
        outer brim, away from the owner's braid, D A9); the recipe line is used only without that prop."""
        P = self.D['paddle']; H = self.hull; stick = self.stick
        if stick and 'stow_hull' in stick.get('anchors', {}):
            a = stick['anchors']['stow_hull']; sc = a['scale']; yaw = math.radians(a['rotation'][1]); axis = (math.sin(yaw), math.cos(yaw))
            (bx0, bx1), (y0, y1), (bz0, bz1) = stick['bounds']; px, py, pz = a['position']
            ends = [(px+axis[0]*v*sc, pz+axis[1]*v*sc) for v in (y0, y1)]
            radius = max(abs(bx0), abs(bx1), abs(bz0), abs(bz1))*sc; y = H.top+py; source = 'paddle_stick.json anchors.stow_hull'
        else:
            x = -(H.bx-P['inset']) if P['side'] == 'left' else H.bx-P['inset']
            while not H.on_brim(x, 0) and abs(x) > H.opening[1]: x += .05 if x < 0 else -.05
            ends = [(x, -P['length']/2), (x, P['length']/2)]; radius = P['radius']; y = H.top+radius; source = 'recipe deck.paddle'
        (x0, z0), (x1, z1) = ends
        ex = {}
        for s in self.body_slots():
            for c in s['classes']:
                if self.owner(s, c): continue
                hits = any(y_overlap(b.y, (y-radius, y+radius), 0) and
                           any(b.dist((x0+(x1-x0)*i/16, z0+(z1-z0)*i/16)) < radius+self.gap for i in range(17)) for b in self.shapes(s, c))
                if hits: ex[s['id']] = c; break
        return dict(kind='PADDLE_STOW', item='stick', segment=[[r4(x0), r4(y), r4(z0)], [r4(x1), r4(y), r4(z1)]], radius=r4(radius),
                    side=P['side'], source=source, excludes=ex)

    def corridor(self, c, a, b):
        """Walking from a to b: the body's box (and worn hat) clear at every step on travelSupport, as
        HatVessel.cabinVolumeClear; returns the largest rise, or None if the way is shut for this size."""
        e, H = self.env[c], self.hull; (ax, _, az), (bx, _, bz) = a['pos'], b['pos']
        n = max(2, int(math.hypot(bx-ax, bz-az)/.125)); prev = None; rise = 0.
        yaw = yaw_of((bx-ax, bz-az)) if (ax, az) != (bx, bz) else a['yaw']
        for i in range(n+1):
            x, z = ax+(bx-ax)*i/n, az+(bz-az)*i/n
            y = H.support(x, z, e.width/2+self.wall)
            if y is None: y = H.top if H.on_brim(x, z) else None
            if y is None: return None
            if not H.contains(y, x, z, e.width/2, e.width/2, self.wall): return None
            if e.hat:
                hb = e.shapes(x, y, z, yaw)[1]; hx, hz = hb.half()
                if not H.contains(y+e.hat['base'], hb.c[0], hb.c[1], hx, hz, self.wall): return None
            if prev is not None: rise = max(rise, abs(y-prev))
            prev = y
        return rise

    def edges(self):
        young = [s for s in self.body_slots() if any(c in YOUNG for c in s['classes']) and not s.get('lease_only')]
        owner = [s for s in self.body_slots() if 'adult' in s['classes']]
        out = []
        for group, classes in ((young, YOUNG), (owner, ['adult'])):
            found = {}
            for a, b in itertools.combinations(group, 2):
                if math.dist(a['pos'], b['pos']) > self.D['edge_max']: continue
                ok, rise = [], 0.
                for c in [c for c in classes if c in a['classes'] and c in b['classes']]:
                    r = self.corridor(c, a, b)
                    if r is not None: ok.append(c); rise = max(rise, r)
                if ok: found[(a['id'], b['id'])] = dict(a=a['id'], b=b['id'], length=r4(math.dist(a['pos'], b['pos'])), rise=r4(rise),
                                                        step=bool(rise > .01), classes=ok)
            pos = {s['id']: s['pos'] for s in group}
            get = lambda p, q: found.get((p, q)) or found.get((q, p))
            for (a, b), e in found.items():  # keep a sparse graph: drop an edge another place lies on the way of
                d = math.dist(pos[a], pos[b])
                if not any(c not in (a, b) and get(a, c) and get(c, b) and math.dist(pos[a], pos[c])+math.dist(pos[c], pos[b]) < self.D['detour']*d
                           and set(e['classes']) <= set(get(a, c)['classes']) & set(get(c, b)['classes']) for c in pos):
                    out.append(e)
        return out

    def entries(self):
        D, H = self.D, self.hull; out = []
        land = [s for s in self.body_slots() if s['kind'] in CABIN+('BRIM',)]
        for spec in D['entries']:
            ox, oz = spec['from']; d = spec['dir']; n = math.hypot(*d); d = (d[0]/n, d[1]/n)
            t = H.extent(ox, oz, d); ex, ez = ox+d[0]*t, oz+d[1]*t
            anchors = {}
            for c, e in self.env.items():
                if c == 'gravid': continue
                reach = e.width/2+self.V['delivery']['out_gap']/self.ratio
                p = [r4(ex+d[0]*reach), 0., r4(ez+d[1]*reach)]
                anchors[c] = dict(outside=p, bank=[p[0], r4(H.top), p[2]] if self.spec['afloat'] else None,
                                  curves=dict(D['curves_by_class'][c] if self.spec['afloat'] else D['curves_on_ground'][c]))
            near = sorted(land, key=lambda s: math.hypot(s['pos'][0]-ex, s['pos'][2]-ez))[:4]
            out.append(dict(id=spec['id'], edge=[r4(ex), r4(H.top), r4(ez)], normal=[r4(d[0]), r4(d[1])],
                            anchors=anchors, nearest=[s['id'] for s in near]))
        return out

    def care(self):
        """Mouth care pairs (replacing HatCareGeometry / HatCareAnchor): an owner station and a young place within
        FamilyFeedingContact reach, mouths level within MAX_MOUTH_HEIGHT_DELTA, the owner facing the young and the young
        turned to face her without touching her or leaving its place."""
        D = self.D; out = []; cap = self.env['adult']
        stations = [s for s in self.slots if s['kind'] == 'CAPTAIN' or s.get('purpose') == 'care']
        for st in stations:
            for y in self.body_slots():
                if y['kind'] not in CABIN+('BRIM',): continue
                ok, rear = [], []
                (sx, sy, sz), (yx, yy, yz) = st['pos'], y['pos']; d = math.hypot(yx-sx, yz-sz)
                if d < 1e-6: continue
                to = ((yx-sx)/d, (yz-sz)/d); f = facing(st['yaw'])
                if f[0]*to[0]+f[1]*to[1] < D['facing_dot']: continue
                turned = dict(y, yaw=yaw_of((-to[0], -to[1])))
                for c in y['classes']:
                    e = self.env[c]; mid = (cap.width+e.width)/2; tol = D['mouth_clearance']*self.block
                    if not mid-tol <= d <= mid+tol: continue
                    if abs(sy+cap.height/2-(yy+e.height/2)) > D['mouth_height']*self.block: continue
                    if y['kind'] in CABIN and (lambda v: v is None or abs(v-yy) > EPS)(self.cabin_y(c, yx, yz, turned['yaw'])): continue
                    if self.collide(st, 'adult', turned, c): continue
                    ok.append(c)
                    if abs(sy-yy) <= D['rear_care_height']*self.block: rear.append(c)
                if ok: out.append(dict(captain=st['id'], young=y['id'], classes=ok, rear=rear, young_yaw=turned['yaw']))
        return out

    # ---------------------------------------------------------- the runtime check, for verification
    def assign(self, bodies, food, paddle_stowed, night, tier=None, owner='adult'):
        """A legal set of places for these body classes with the owner in her seat (CAPTAIN, or CAPTAIN_HIGH when
        gravid): classes, night use, jumps, the larder tier and the stowed stick respected, no two touching. Lease-only
        rims and service places are not homes. None if there is none."""
        tier = tier if tier is not None else (0 if food <= 0 else next(t for t, m in enumerate(self.cargo['tier_max_counts']) if food <= m))
        blocked = {}
        if self.spec['cargo'] and tier: blocked.update(self.tiers[tier]['excludes'])
        if paddle_stowed and 'paddle_stow' in self.fixtures:
            for k, v in self.fixtures['paddle_stow']['excludes'].items(): blocked[k] = min(v, blocked.get(k, v), key=rank)
        seat = next((s for s in self.slots if s['kind'] == ('CAPTAIN_HIGH' if owner == 'gravid' else 'CAPTAIN')), None)
        placed0 = [(seat, owner)] if seat and self.spec['captain'] else []
        when = 'night' if night else 'day'  # homes in the slots' preference order for this time (rank_slots)
        homes = sorted((s for s in self.slots if s['kind'] in CABIN+('BRIM',)), key=lambda s: s.get('prefer', {}).get(when, 999))
        order = sorted(range(len(bodies)), key=lambda i: -rank(bodies[i]))
        result = [None]*len(bodies)

        def free(slot, c, placed):
            if c not in slot['classes'] or (night and not slot['night']): return False
            if slot['requires_jump'] and not self.D['jumps'][c]: return False
            if slot['id'] in blocked and rank(c) >= rank(blocked[slot['id']]): return False
            for o, oc in placed:
                if o is slot: return False
                limit = o['conflicts'].get(oc, {}).get(slot['id'])
                if limit is not None and rank(c) >= rank(limit): return False
            return True

        budget = [self.D.get('search_budget', 20000)]  # an offline bound: a search this long counts as no legal set

        def go(i, placed, last):
            if i == len(order): return True
            budget[0] -= 1
            if budget[0] < 0: return False
            k = order[i]; c = bodies[k]
            start = last+1 if i > 0 and bodies[order[i-1]] == c else 0  # same class: places in increasing order
            for n in range(start, len(homes)):
                s = homes[n]
                if free(s, c, placed):
                    result[k] = s['id']
                    if go(i+1, placed+[(s, c)], n): return True
            return False
        return result if go(0, placed0, -1) else None

    def capacity(self):
        """The most bodies of one class aboard with the owner in her seat and the stick stowed, per larder tier."""
        out = {}
        for c in self.D['cabin_classes']:
            row = {}
            for t in range(max(1, len(self.tiers))):
                for night in (False, True):
                    n = 0
                    while n < 12 and self.assign([c]*(n+1), 0, True, night, tier=t) is not None: n += 1
                    row[f"{'night' if night else 'day'}_{t}"] = n
            out[c] = row
        return out

    def relate(self):
        self.conflicts()
        self.tiers = self.larder_tiers() if self.spec['cargo'] else []
        self.fixtures = {'paddle_stow': self.paddle()} if self.spec['cargo'] else {}

    def witnesses(self, cases):
        """Places used by some fullest load: each class alone, a larger class with smaller ones, the documented loads;
        per larder tier, by day and by night, with the stick stowed."""
        used = set(); cls = self.D['cabin_classes']; tiers = range(max(1, len(self.tiers)))

        def most(base, c, t, night):
            n, best = 0, self.assign(base, 0, True, night, tier=t)
            while n < 12:
                got = self.assign(base+[c]*(n+1), 0, True, night, tier=t)
                if got is None: break
                n, best = n+1, got
            if best: used.update(best)
            return n
        for t in tiers:
            for night in (False, True):
                for c in cls: most([], c, t, night)
                for big, small in itertools.permutations(cls, 2):
                    if rank(big) <= rank(small): continue
                    for k in range(1, most([], big, t, night)+1): most([big]*k, small, t, night)
        for case in cases:
            if case.get('mode', 'AQUATIC_FLOAT') != self.name: continue
            for night in (False, True):
                got = self.assign(case['bodies'], case['food'], case['paddle'], night, owner=case.get('owner', 'adult'))
                if got: used.update(got)
        return used

    def prune(self, keep):
        """Drop home places no fullest load uses, then number each kind again and measure the relations afresh."""
        homes = CABIN+('BRIM',)
        self.slots = [s for s in self.slots if s['kind'] not in homes or s['id'] in keep]
        count = {}
        for s in self.slots:
            if s['kind'] in homes:
                count[s['kind']] = count.get(s['kind'], 0)+1; s['id'] = f"{s['kind'].lower()}_{count[s['kind']]}"
            s.pop('conflicts', None); s.pop('prefer', None)
        self.rank_slots(); self.relate()

    def export(self, cases):
        self.build(); self.relate()
        self.prune(self.witnesses(cases))
        care = self.care() if self.spec['captain'] else []
        serving = {c['captain'] for c in care}
        if any(s.get('purpose') == 'care' and s['id'] not in serving for s in self.slots):  # stations no young can be reached from
            self.slots = [s for s in self.slots if s.get('purpose') != 'care' or s['id'] in serving]
            for n, s in enumerate(t for t in self.slots if t.get('purpose') == 'care'):
                old = s['id']; s['id'] = f'captain_care_{n+1}'
                for c in care:
                    if c['captain'] == old: c['captain'] = s['id']
            for s in self.slots: s.pop('conflicts', None)
            self.relate()
        slots = [dict(s, pos=[r4(v) for v in s['pos']]) for s in self.slots]
        return dict(hull_ratio=self.ratio, surface=self.spec['surface'], top=r4(self.hull.top), squash=r4(self.hull.sq),
                    afloat=self.spec['afloat'], envelopes={c: e.export() for c, e in self.env.items()},
                    slots=slots, edges=self.edges(), entries=self.entries(), larder=self.tiers, fixtures=self.fixtures,
                    care=care, capacity=self.capacity())


def measure_pile(cargo):
    """The crown larder of each tier as exported (hat_cargo_layout.json larder[t].occupancy: the leaf bed and the items
    settled on it, drops/care-props/cargo.py): its centre, footprint and per grid column the height over the crown floor;
    unsquashed hat units, as the hull's depths."""
    out = [None]
    for t in cargo['larder'][1:]:
        o = t['occupancy']; g = o['grid']; cells = {(round(dx/g), round(dz/g)): h for dx, dz, h in o['cells']}
        xs = [dx for dx, _, _ in o['cells']]; zs = [dz for _, dz, _ in o['cells']]
        out.append(dict(bounds=[min(xs)-g/2, max(xs)+g/2, min(zs)-g/2, max(zs)+g/2], height=max(cells.values()),
                        floor=o['floor'], cells=cells, grid=g, centre=o['centre']))
    return out


def export(V, body, cargo, D, pregnancy, stick=None):
    pile = measure_pile(cargo)
    decks = {name: Deck(name, spec, V, body, cargo, pile, D, pregnancy, stick) for name, spec in D['modes'].items()}
    return decks, {name: d.export(D['verify']) for name, d in decks.items()}


def verify(decks, D):
    """The documented loads: the load budget first, then a legal set of places by day and by night."""
    lines = []; L = D['load']; wrong = []
    for case in D['verify']:
        deck = decks[case.get('mode', 'AQUATIC_FLOAT')]; bodies = case['bodies']
        load = sum(L[c] for c in bodies)+case['food']*L['food']+(L['paddle'] if case['paddle'] else 0)+(L['gravid'] if case.get('owner') == 'gravid' else 0)
        got = {w: deck.assign(bodies, case['food'], case['paddle'], w == 'night', owner=case.get('owner', 'adult')) for w in ('day', 'night')}
        if load > L['max']: state = 'UNITS_FULL'
        elif case['food'] > L['larder_max']: state = 'LARDER_FULL'
        else: state = 'OK' if all(v is not None for v in got.values()) else 'DAY_ONLY' if got['day'] is not None else 'NO_SLOT_FOR_CLASS'
        lines.append((case['name'], load, state, got))
        if state != case.get('expect', 'OK'): wrong.append(f"verify {case['name']}: {state} (load {load}) {got}")
    if wrong: raise SystemExit('\n'.join(wrong))
    return lines


# ------------------------------------------------------------------ the transform contract
def frames(V, F):
    """hat_frames.json: the worn hat, the hat turned over (the prop), each hull mode, and the freeboard by load."""
    a = V['anchor_worn']; fb = V['bowl']['freeboard']; L = F['load']
    by_load = [[x/2, r4(fb*(1-L['sink']*(x/2)/L['max']))] for x in range(0, 2*L['max']+1)]
    roll = [[x/2, r4(1+L['roll']*(x/2)/L['max'])] for x in range(0, 2*L['max']+1)]
    afloat = lambda name, ratio: {'hull_mode': name, 'squash': V['squash']['bowl'], 'top': fb, 'freeboard': fb, 'hull_ratio': ratio,
                                  'water_y': 0.0, 'mask': V['bowl']['mask'], 'owner_y': V['bowl']['owner_y'],
                                  'freeboard_by_load': by_load, 'roll_by_load': roll}
    return {
        'note': 'One transform contract for the worn hat, the turned hat prop and the hull modes: the hull renderer, the '
                'water mask, the self-rescue hat matrix and the Python previews read it instead of deriving it again.',
        'prop': V['prop'], 'units_per_block': 16,
        'worn': {'frame': 'body model units at growth 1: +Y up, the face toward -Z, the braid on +X',
                 'anchor': a, 'multiplier': {'land': 1.0, 'aquatic': V['aquatic_scale']},
                 'rule': 'a worn hat point p sits at anchor + multiplier * (p - anchor); the body growth then scales the model'},
        'turned': {'frame': 'prop and hull: origin at the opening centre on the brim face, +Y out of the opening, the long brim axis on Z, the worn front (bow) on +X',
                   'from_worn': [[0, 0, -1, a[2]], [0, -1, 0, a[1]], [-1, 0, 0, a[0]], [0, 0, 0, 1]],
                   'from_worn_order': 'rotateY(90 deg) * rotateX(180 deg) * translate(-anchor)',
                   'keel': V['keel'], 'brim': V['brim'], 'opening': V['opening']},
        'modes': {
            'nest': {'hull_mode': 'NEST', 'squash': V['squash']['nest'], 'top': V['nest']['height'], 'hull_ratio': 1.0, 'ground_y': 0.0},
            'float': afloat('FLOAT', 1.0),
            'aquatic': afloat('AQUATIC_FLOAT', V['aquatic_scale'])},
        'hull': {'unit': 'blocks per hat unit = hullScale / 16, hullScale = hat fit * hull_ratio',
                 'prop_to_hull': 'hull = (x, top + y * squash, z) for a prop point (x, y, z); afloat, top is the freeboard at the current load',
                 'hull_to_world': 'world = entity position + rotateY(180 - entity yaw) * (hull * unit)',
                 'sink': 'afloat every hull point, seat and the water mask move down by freeboard - freeboard_by_load(load); the mask stays at the water line',
                 'yaw': 'a body with seat yaw r faces hull-local (sin r, -cos r); its world yaw is the entity yaw + r'},
        'load': {'rule': 'freeboard(load) = freeboard * (1 - sink * load / max); roll and flooding sensitivity * (1 + roll * load / max)',
                 'sink': L['sink'], 'roll': L['roll'], 'max': L['max']},
    }
