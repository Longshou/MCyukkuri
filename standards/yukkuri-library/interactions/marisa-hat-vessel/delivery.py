"""How a newborn gets into Marisa's turned-over hat (hat_vessel.json 'delivery').

Shared by build.py (placements and the report) and the births preview, and mirrored in the game
(HatVessel.surface / HatVessel.pack, family/NewbornFlight). Newborns move on parabolas under
Minecraft's gravity: pushed out of the live exit over the near brim, or bouncing in from where a
falling stem fruit met the brim or the ground, and hopping out over the brim when the hat is taken
back. Each young sits in its own place, packed by size, so they do not sink into one another.

Frames: hat units are the prop's (unscaled; x across, z along the long axis, y up from the hat's
origin: the ground for a hat on land, the water line afloat). A pose places them in the scene,
world = at + Ry(rot)(local * k), Ry as in births.py (x' = x cos + z sin). World values are model
units, 16 per block.
"""
import itertools, math


def ry(a, v):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a)); x, y, z = v
    return [x*c+z*s, y, -x*s+z*c]


class Hat:
    """The top surface of the turned hat, on land ('nest') or afloat ('bowl'), in hat units."""

    def __init__(self, V, mode):
        sq = V['squash'][mode]
        self.mode = mode; self.top = V['nest']['height'] if mode == 'nest' else V['bowl']['freeboard']
        self.ledge = self.top-V['ledge']*sq; self.floor = self.top-V['floor']*sq
        self.brims, self.opening, self.well = V['brim_rects'], V['opening'], V['well']

    @staticmethod
    def inside(r, x, z): return r[0] <= x <= r[1] and r[2] <= z <= r[3]

    def surface(self, x, z):
        if self.inside(self.well, x, z): return self.floor
        if self.inside(self.opening, x, z): return self.ledge
        if any(self.inside(b, x, z) for b in self.brims): return self.top
        return None

    def footprint(self, x, z, r):
        """The highest surface under a round body of radius r centred at x, z; None off the hat."""
        best = None
        for i in range(9):
            h = self.surface(x, z) if i == 8 else self.surface(x+r*math.cos(i*math.pi/4), z+r*math.sin(i*math.pi/4))
            if h is not None and (best is None or h > best): best = h
        return best

    def extent(self, x, z, dx, dz, r):
        """How far from x, z along the unit direction dx, dz a body of radius r stays over the hat."""
        d = 0.
        while self.footprint(x+dx*d, z+dz*d, r) is not None and d < 64: d += .125
        return d


class Pose:
    def __init__(self, at, rot, k): self.at, self.rot, self.k = list(at), rot, k

    def world(self, p): return [a+b for a, b in zip(self.at, ry(self.rot, [v*self.k for v in p]))]

    def local(self, p): return [v/self.k for v in ry(-self.rot, [p[i]-self.at[i] for i in range(3)])]


def radius(D, width):
    """A young's round body radius from its collision width (same units)."""
    return D['young_radius']*width


def seg_dist(px, pz, a, b):
    """Distance from a point to the segment a-b (x, z)."""
    dx, dz = b[0]-a[0], b[1]-a[1]; L = dx*dx+dz*dz
    q = 0 if L < 1e-12 else max(0., min(1., ((px-a[0])*dx+(pz-a[1])*dz)/L))
    return math.hypot(px-a[0]-q*dx, pz-a[1]-q*dz)


def place(hat, D, r, region, prefer=None, taken=(), start=None):
    """A place (hat units: x, surface y, z) for a young of radius r (hat units) among those taken (x, y, z, r): the
    free spot nearest the preferred point (where it landed, else the region's), on the floor rather than the ledge,
    overlapping the others as little as possible and, coming from start (x, z), not across them."""
    R = D['regions'][region]; px, pz = prefer if prefer is not None else R['prefer']; zmin, zmax = R.get('z_min', -1e9), R.get('z_max', 1e9)
    x0, x1, z0, z1 = hat.opening; step = D['step']; best = None
    nx = int(math.floor((x1-x0-2*r)/step+1e-9)); nz = int(math.floor((z1-z0-2*r)/step+1e-9))
    for i in range(max(0, nx)+1):
        x = x0+r+i*step if nx >= 0 else (x0+x1)/2
        for j in range(max(0, nz)+1):
            z = z0+r+j*step if nz >= 0 else (z0+z1)/2
            if z < zmin or z > zmax: continue
            h = hat.footprint(x, z, r)
            over = sum(max(0., (r+s[3])*D['overlap']-math.hypot(x-s[0], z-s[2])) for s in taken)
            across = 0 if start is None else sum(max(0., (r+s[3])*D['overlap']-seg_dist(s[0], s[2], start, (x, z))) for s in taken)
            cost = 100*over+D['path_cost']*across+(D['ledge_cost'] if h > hat.floor+1e-6 else 0)+math.hypot(x-px, z-pz)
            if best is None or cost < best[0]-1e-9: best = (cost, x, h, z)
    return [best[1], best[2], best[3]] if best else [px, hat.floor, pz]


def pack(hat, D, radii, region):
    """Places for young of these radii taken one after another (HatBoatEntity: after a day/night change, or before
    the server has said where each sits)."""
    taken = []
    for r in radii: taken.append(place(hat, D, r, region, None, taken)+[r])
    return [t[:3] for t in taken]


def brim_place(hat, D, r, taken=()):
    """HatVessel.brimPlace, including whole-footprint support and room for siblings' hats."""
    margin = D['brim_standing']['margin']; step = D['step']
    half = max(abs(b[i]) for b in hat.brims for i in (0, 1)); front = min(b[2] for b in hat.brims)
    best = None; x = -half+r+margin
    while x <= half-r-margin:
        z = front+r+margin
        while z <= hat.opening[2]-r-margin:
            samples = [(x, z)]+[(x+(r+margin)*math.cos(i*math.pi/8), z+(r+margin)*math.sin(i*math.pi/8)) for i in range(16)]
            supported = all(hat.surface(px, pz) is not None and abs(hat.surface(px, pz)-hat.top) < 1e-6 for px, pz in samples)
            free = all(math.hypot(x-s[0], z-s[2]) >= (r+s[3])*1.55 for s in taken)
            cost = abs(x)+.25*abs(z-hat.opening[2])
            if supported and free and (best is None or cost < best[0]): best = (cost, x, hat.top, z)
            z += step
        x += step
    return list(best[1:]) if best else None


def arc(a, b, T, g):
    """Minecraft gravity between two points reached in T ticks: straight across, a parabola up and down."""
    return lambda t: [a[i]+(b[i]-a[i])*t/T+(g/2*t*(T-t) if i == 1 else 0) for i in range(3)]


def takeoff(a, b, pose, hat, r):
    """Where a body of radius r at a, bound for b, can leave from: moved toward b, at its height, until it is not
    inside the hat (a newborn coming out over the boat's front brim)."""
    for i in range(41):
        p = [a[0]+(b[0]-a[0])*i/40, a[1], a[2]+(b[2]-a[2])*i/40]; l = pose.local(p); h = hat.footprint(l[0], l[2], r/pose.k)
        if h is None or l[1] >= h-1e-6: return p
    return list(a)


def solve(a, b, pose, hat, r, D, tmin=None):
    """The fewest ticks (from tmin, and no faster across than max_speed) for which a body of radius r (world units)
    flying from a to b keeps clear above the hat: the whole body over any part higher than both ends by D['clear'],
    and never with its centre below the surface right under it."""
    g = D['gravity']; la, lb = pose.local(a), pose.local(b); rr = r/pose.k
    ends = [h for h in (hat.footprint(la[0], la[2], rr), hat.footprint(lb[0], lb[2], rr)) if h is not None]
    tmin = max(tmin or D['tmin'], math.ceil(math.hypot(b[0]-a[0], b[2]-a[2])/D['max_speed']))
    top = max(ends) if ends else None
    for T in range(tmin, D['tmax']+1):
        f = arc(a, b, T, g); ok = True
        for i in range(1, 4*T):
            p = pose.local(f(i/4)); h = hat.footprint(p[0], p[2], rr)
            if h is None: continue
            if top is None or h > top+1e-6: need = h+D['clear']  # a brim or ledge higher than both ends: the whole body over it
            else:  # stepping off the ledge into the well, or onto it: what is right under it
                need = hat.surface(p[0], p[2])
                if need is None: continue
            if p[1] < need-1e-6: ok = False; break
        if ok: return T
    return D['tmax']


def overshoot(a, b, pose, hat, r, D, ground):
    """Where an overstrained push sends a newborn aimed at b: on along the same line, past the far brim, down at
    ground height (the ground, or the water line)."""
    dx, dz = b[0]-a[0], b[2]-a[2]; n = math.hypot(dx, dz) or 1; dx, dz = dx/n, dz/n
    lb = pose.local(b); ld = ry(-pose.rot, [dx, 0, dz])
    beyond = hat.extent(lb[0], lb[2], ld[0], ld[2], r/pose.k)*pose.k+r+D['overshoot']
    return [b[0]+dx*beyond, ground, b[2]+dz*beyond]


def out_spots(pose, hat, radii, others, D, toward=None):
    """Ground spots (world) just past the brim, side by side, on whichever of the hat's four sides keeps them farthest
    from the others there (x, z, radius: the one taking the hat back, the parents); only toward a world direction if
    given (a floated hat: the bank)."""
    cx, cz = (hat.opening[0]+hat.opening[1])/2, (hat.opening[2]+hat.opening[3])/2; r = max(radii)/pose.k; best = None
    sides = [(1, 0), (-1, 0), (0, 1), (0, -1)]
    if toward is not None:
        lt = ry(-pose.rot, [toward[0], 0, toward[2]]); n = math.hypot(lt[0], lt[2]) or 1; sides = [(lt[0]/n, lt[2]/n)]
    for dx, dz in sides:
        reach = hat.extent(cx, cz, dx, dz, r)+r+D['out_gap']/pose.k; width = 2*r*D['out_spread']
        spots = [pose.world([cx+dx*reach-dz*(i-(len(radii)-1)/2)*width, 0, cz+dz*reach+dx*(i-(len(radii)-1)/2)*width]) for i in range(len(radii))]
        score = min((math.hypot(s[0]-o[0], s[2]-o[1])-o[2] for s in spots for o in others), default=0)
        if best is None or score > best[0]+1e-9: best = (score, spots)
    return best[1]


def match(froms, tos):
    """Which of tos each of froms goes to (indices), the pairing with the least total squared distance across, so
    young moving together do not cross one another's way."""
    best = min(itertools.permutations(range(len(tos)), len(froms)),
               key=lambda p: sum((f[0]-tos[j][0])**2+(f[2]-tos[j][2])**2 for f, j in zip(froms, p)))
    return list(best)
