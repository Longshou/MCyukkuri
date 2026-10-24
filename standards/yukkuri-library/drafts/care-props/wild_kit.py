"""Shared look of everything wild yukkuri gather themselves: the self-built nests (wild.py), the 2x2 wild nest and the forage
pile with its hat-boat copy (designs.py, cargo.py).

One hand-drawn 128x128 atlas of 16x16 tiles and sprites in the manner of supplies.py: light from the top left, each material
its own hue-shifted ramp (shadows toward olive / red-brown, lights toward pale yellow), the darkest step of a material as its
own outline, no black, no anti-aliasing, alpha only 0 or 255. Nothing is copied from Minecraft.

Shapes follow vanilla's way of building organic props (campfire logs, composter, hay bale, beehive): a few large rounded
pieces instead of many small blocks. Nest rims are tied bundles of dry grass (short rolls swept along the rim path with
tapered, cut ends) overlapping into a ring, the straw texture running along each bundle like a bird's nest; stored hay is
a lumpy low-poly dome; twigs and loose stems are square sticks; leaves and flowers are cut-out sprites. Every texture is mapped at the vanilla density of
one texel per unit (16 per block); tiling surfaces stay world/path continuous. Units are 1/16 block, +Y up.
"""
import math, random, zlib
from PIL import Image
from kit import Prop, unit as norm, sub, cross, dot, add, mul

T = 16
ATLAS = 128


def hexrgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


# ---- ramps (dark -> light) --------------------------------------------------------------------------------------------
STRAW = ['#463a1e', '#77653b', '#8f7c48', '#a5915a', '#bfab70', '#d9ca94']     # dry woven grass, hay-bale beige
OLD = ['#3a3020', '#57482e', '#76643f', '#958052', '#b19c68']                  # weathered inner straw
SAGE = ['#38431f', '#53602a', '#717d36', '#929b47', '#b4b866']                 # cut grass drying to hay
DRYLEAF = ['#43200f', '#6b3516', '#934f1f', '#b96e2a', '#d8933d', '#ecbb68']   # fallen leaf, orange-brown
GREEN = ['#1d3818', '#2d5421', '#41722b', '#5a9135', '#7aae45', '#a2cc62']     # fresh leaf
GRASS = ['#21441a', '#33621f', '#478226', '#62a031', '#88c04a', '#b2dc72']     # fresh grass clump
MOSS = ['#263b17', '#38551d', '#4f7024', '#698c2e', '#8aa93e']
BARK = ['#291c12', '#3f2c1c', '#584028', '#735637', '#8f6f48']
WOOD = ['#7a5a34', '#a37d4c', '#c49d64', '#ddbd84']
SOIL = ['#2e2117', '#432f20', '#5a412c', '#71553a']
BERRY = ['#470c18', '#781424', '#a82230', '#d2443a', '#f08668']
NUT = ['#482810', '#6f421c', '#97602c', '#bb8444', '#d8a862']
CAP = ['#33261a', '#4f3c28', '#6c5638', '#8b744e']
WORM = ['#4f6a1e', '#78962c', '#9cba3c', '#bed65a', '#dcec8a']     # pale yellow-green caterpillar
PETAL = ['#8f9890', '#c3c9bd', '#e6eade', '#fbfcf4']
POLLEN = ['#a8721a', '#e0b02c', '#f6dc5e']


def rng_of(key):
    return random.Random(zlib.crc32(key.encode()))


class Canvas:
    """16x16 pixel grid; coordinates wrap so tiling painters stay seamless."""

    def __init__(self, fill=None):
        self.px = [[fill] * T for _ in range(T)]

    def put(self, x, y, c, wrap=True):
        if wrap:
            x, y = x % T, y % T
        elif not (0 <= x < T and 0 <= y < T):
            return
        self.px[y][x] = c

    def get(self, x, y):
        return self.px[y % T][x % T]

    def rows(self, rows, legend, dx=0, dy=0, wrap=False):
        for y, line in enumerate(rows):
            for x, ch in enumerate(line):
                if ch in legend:
                    self.put(x + dx, y + dy, legend[ch], wrap)
        return self

    def image(self):
        img = Image.new('RGBA', (T, T), (0, 0, 0, 0))
        for y in range(T):
            for x in range(T):
                c = self.px[y][x]
                if c is not None:
                    img.putpixel((x, y), hexrgb(c) + (255,))
        return img


# ---- tiling surfaces ----------------------------------------------------------------------------------------------------
def weave(key, ramp=STRAW, base=2, gaps=True):
    """Woven dry grass seen from the side of a roll: long strands run along the tile x axis (= along the nest rim) in
    bundles two or three rows deep, the top row of each bundle catching the light with a few glints, the bottom row in
    shade with dark gaps under it, two strands crossing over at a slant."""
    r = rng_of(key)
    top = len(ramp) - 1
    c = Canvas(ramp[base])
    y = 0
    for h in (2, 3, 2, 2, 3, 2, 2):
        for row in range(h):
            shade = base + 1 if row == 0 else base - 1 if row == h - 1 else base
            x = r.randrange(T)
            done = 0
            while done < T:
                n = r.randint(3, 8)
                run = shade if r.random() < (.5 if row == 0 else .3) else base  # broken bands, not stripes
                step = max(0, min(top, run + r.choice((0, 0, 0, 1, -1))))
                for k in range(n):
                    c.put(x + k, y + row, ramp[step])
                if row == 0 and r.random() < .6:                              # glint on a lit strand
                    g = r.randrange(n)
                    c.put(x + g, y, ramp[min(top, step + 1)])
                    c.put(x + g + 1, y, ramp[min(top, step + 1)])
                x += n
                done += n
        if gaps:
            for _ in range(r.randint(1, 2)):                                  # deep gaps under a bundle
                gx = r.randrange(T)
                for k in range(r.randint(1, 3)):
                    c.put(gx + k, y + h - 1, ramp[max(0, base - 2)])
        y += h
    for _ in range(2):                                                      # strands crossing over the bundles
        x, y0 = r.randrange(T), r.randrange(T)
        sgn = r.choice((1, -1))
        for k in range(6):
            c.put(x + k, y0 + sgn * (k // 3), ramp[min(top, base + 2)])
            c.put(x + k, y0 + sgn * (k // 3) + 1, ramp[max(0, base - 1)])
    if gaps:
        for _ in range(2):                                                  # a twig and a fleck of moss caught in the weave
            x, y0 = r.randrange(T), r.randrange(T)
            for k in range(4):
                c.put(x + k, y0, BARK[2 if k else 3])
            mx, my = r.randrange(T), r.randrange(T)
            c.put(mx, my, MOSS[3])
            c.put(mx + 1, my, MOSS[2])
    return c.image()


LEAFLETS = (["lm", "md"], ["llm", "mmd"], [".lm", "lmd", "md."], ["lm.", "mmm", ".md"], ["lmm", ".md"])


def litter(key, ground=SOIL, mix=((DRYLEAF, 8), (OLD, 2)), straws=4, density=24):
    """Bed surface from above: small fallen leaves overlapping on dark earth, short straw strands across them."""
    r = rng_of(key)
    c = Canvas(ground[1])
    for _ in range(18):
        c.put(r.randrange(T), r.randrange(T), ground[r.choice((0, 2))])
    ramps = [m for m, w in mix for _ in range(w)]
    for _ in range(density):
        ramp = r.choice(ramps)
        shape = r.choice(LEAFLETS)
        if r.random() < .5:
            shape = [row[::-1] for row in shape]
        b = r.randint(2, len(ramp) - 3)
        legend = {'l': ramp[b + 1], 'm': ramp[b], 'd': ramp[b - 1]}
        x, y = r.randrange(T), r.randrange(T)
        for dy, row in enumerate(shape):
            for dx, ch in enumerate(row):
                if ch in legend:
                    c.put(x + dx, y + dy, legend[ch])
        c.put(x + len(shape[0]), y + len(shape), ramp[max(0, b - 2)])            # a little shadow under the leaf
    for _ in range(straws):
        x, y = r.randrange(T), r.randrange(T)
        sx, sy = r.choice(((1, 0), (1, 1), (1, -1), (2, 1)))
        for k in range(r.randint(3, 5)):
            c.put(x + k * sx // max(1, abs(sx)) if sx != 2 else x + k, y + (k * sy if sx != 2 else k // 2 * sy),
                  STRAW[4] if k == 0 else STRAW[3])
    return c.image()


def hay(key):
    """Stored hay seen on a heap: strands running down the slope (tile y), each column a run of stems with a lit tip,
    shaded columns and dark gaps between bundles, a few greener stems of grass still drying."""
    r = rng_of(key)
    c = Canvas(STRAW[2])
    for x in range(T):
        col = r.choice((1, 2, 2, 3, 3, 4))
        y = r.randrange(T)
        done = 0
        while done < T:
            n = r.randint(3, 7)
            ramp = SAGE if r.random() < .05 else STRAW
            step = max(1, min(len(ramp) - 2, col + r.choice((0, 0, 1, -1))))
            for k in range(n):
                c.put(x, y + k, ramp[step])
            c.put(x, y, ramp[step + 1])                                       # lit stem tip
            if r.random() < .45:
                c.put(x, y + n - 1, STRAW[0])                                 # gap under the bundle
            y += n
            done += n
    for _ in range(4):                                                      # stems lying across
        x, y = r.randrange(T), r.randrange(T)
        for k in range(r.randint(3, 5)):
            c.put(x + k // 2, y + k, STRAW[4])
    return c.image().transpose(Image.Transpose.TRANSPOSE)                  # strands run round the heap


def clumps(key, ramp, base=2, count=9, dark=8):
    """Moss / fresh grass clump: rounded 3x3 tufts lit on the upper left over a darker bed."""
    r = rng_of(key)
    c = Canvas(ramp[base - 1])
    for _ in range(count):
        x, y = r.randrange(T), r.randrange(T)
        c.rows([".lm", "lmm", "mmd"], {'l': ramp[base + 2], 'm': ramp[base + 1], 'd': ramp[base]}, x, y, wrap=True)
    for _ in range(dark):
        c.put(r.randrange(T), r.randrange(T), ramp[0])
    return c.image()


def straw_end(key):
    """Cut end of a tied grass bundle: packed stem ends as light dots with dark hollows."""
    r = rng_of(key)
    c = Canvas(STRAW[1])
    for y in range(0, T, 2):
        for x in range(T):
            if (x + y // 2) % 2 == 0:
                c.put(x, y + r.randint(0, 1), STRAW[r.choice((3, 3, 4, 2))])
    for _ in range(14):
        c.put(r.randrange(T), r.randrange(T), STRAW[0])
    return c.image()


def blades(key):
    """A rolled grass ball (草团): short upright blades with light tips, packed tight."""
    r = rng_of(key)
    c = Canvas(GRASS[2])
    for _ in range(30):
        x, y = r.randrange(T), r.randrange(T)
        b = r.randint(3, 4)
        n = r.randint(2, 4)
        for k in range(n):
            c.put(x, y + k, GRASS[b + 1] if k == 0 else GRASS[b])
        c.put(x, y + n, GRASS[0])
    return c.image()


def bark(key):
    """Twig side: grain along the tile x axis (along the twig), dark cracks, a light ridge here and there."""
    r = rng_of(key)
    c = Canvas(BARK[2])
    for y in range(T):
        x = r.randrange(T)
        for _ in range(4):
            n = r.randint(2, 5)
            step = r.choice((1, 2, 2, 3))
            for k in range(n):
                c.put(x + k, y, BARK[step])
            x += n
    for _ in range(6):
        x, y = r.randrange(T), r.randrange(T)
        for k in range(r.randint(3, 5)):
            c.put(x + k, y, BARK[0])
        c.put(x, y - 1, BARK[4])
    return c.image()


def wood_end():
    """Cut twig end: light wood with a darker ring and pith, centred on texel (8, 8)."""
    c = Canvas(BARK[1])
    for y in range(T):
        for x in range(T):
            d = math.hypot(x - 7.5, y - 7.5)
            if d < 1.0:
                c.put(x, y, WOOD[0])
            elif d < 2.2:
                c.put(x, y, WOOD[2] if (x + y) % 3 else WOOD[3])
            elif d < 3.0:
                c.put(x, y, WOOD[1])
            elif d < 4.6:
                c.put(x, y, WOOD[2])
            elif d < 5.6:
                c.put(x, y, BARK[3])
    return c.image()


def solid(ramp, base, key, amount=.3):
    """Small food surfaces (berries, nuts, caterpillar): one step with a few lighter and darker pixels."""
    r = rng_of(key)
    c = Canvas(ramp[base])
    for y in range(T):
        for x in range(T):
            v = r.random()
            if v < amount / 2:
                c.put(x, y, ramp[min(len(ramp) - 1, base + 1)])
            elif v < amount:
                c.put(x, y, ramp[max(0, base - 1)])
    return c.image()


def caterpillar():
    """Caterpillar skin: segments as dark rings every two texels along x, a yellow spot on alternate segments."""
    c = Canvas(WORM[2])
    for y in range(T):
        for x in range(T):
            if x % 3 == 2:
                c.put(x, y, WORM[1])
            elif x % 3 == 0 and y % 4 in (0, 1):
                c.put(x, y, WORM[3])
    for x in range(0, T, 6):
        c.put(x + 1, 6, POLLEN[1])
        c.put(x + 1, 13, POLLEN[1])
    return c.image()


# ---- sprites (cut out) ------------------------------------------------------------------------------------------------
def leaf_sprite(ramp, broad=True, nibbled=False):
    """Single leaf about two thirds of a yukkuri wide, tip up, midrib down texel column 7, lit on the left, veins in the
    light step, the ramp's darkest step outlining it."""
    shape = ([
        "................",
        "................",
        ".......o........",
        "......omo.......",
        ".....olrmo......",
        "....olvrmdo.....",
        "....llmrvmo.....",
        "...ollvrmmdo....",
        "...olmmrvmdo....",
        "...olvmrmmdo....",
        "....olmrvdo.....",
        "....olvrmdo.....",
        ".....oormdo.....",
        ".......oo.......",
        ".......r........",
        "................",
    ] if broad else [
        "................",
        "................",
        "................",
        ".......o........",
        "......olo.......",
        "......lrmo......",
        "......lrvo......",
        ".....olrmdo.....",
        ".....lvrmdo.....",
        "......lrmo......",
        "......orvo......",
        ".......rdo......",
        ".......r........",
        "................",
        "................",
        "................",
    ])
    top = len(ramp) - 1
    legend = {'o': ramp[0], 'd': ramp[1], 'm': ramp[2], 'l': ramp[min(top, 3)], 'v': ramp[min(top, 4)],
              'r': ramp[min(top, 4)] if broad else ramp[min(top, 3)]}
    c = Canvas()
    c.rows(shape, legend, wrap=False)
    if nibbled:                                                     # a dry, nibbled edge
        for x, y in ((10, 8), (10, 9), (4, 10)):
            c.put(x, y, None, wrap=False)
        c.put(9, 9, ramp[0], wrap=False)
        c.put(5, 10, ramp[0], wrap=False)
    return c.image()


def daisy():
    """Small white wild flower with a yellow eye on its stem, for crossed planes like a vanilla flower."""
    c = Canvas()
    c.rows([
        ".lwl.",
        "lwYwm",
        ".mwm.",
        "..g..",
        ".Gg..",
        "..g..",
    ], {'l': PETAL[2], 'w': PETAL[3], 'm': PETAL[1], 'Y': POLLEN[1], 'g': GREEN[2], 'G': GREEN[3]}, 5, 10)
    return c.image()


def bloom():
    """The flower head seen from above, laid on top of the crossed stem so it reads from every side."""
    c = Canvas()
    c.rows([".w.", "wYw", ".m."], {'w': PETAL[3], 'm': PETAL[1], 'Y': POLLEN[1]}, 6, 6)
    return c.image()


# ---- atlas --------------------------------------------------------------------------------------------------------------
# Tiling tiles are stored 2x2 (32x32) so a face may cross one tile repeat; sprites are stored once (16x16).
TILES = {
    'weave': (0, 0), 'weave_in': (32, 0), 'litter': (64, 0), 'hay': (96, 0),
    'moss': (0, 32), 'bark': (32, 32), 'blades': (64, 32), 'worm': (96, 32),
    'berry': (0, 64), 'nut': (32, 64), 'cap': (64, 64), 'litter_dry': (96, 64),
    'wood_end': (0, 96), 'straw_end': (96, 96),
}
SPRITES = {
    'leaf_green': (32, 96), 'leaf_dry': (48, 96), 'leaf_old': (64, 96), 'leaf_slim': (32, 112),
    'leaf_slim_dry': (48, 112), 'daisy': (80, 96), 'bloom': (64, 112),
}


def tile_images():
    return {
        'weave': weave('weave'), 'weave_in': weave('weave_in', OLD, 2), 'litter': litter('litter'), 'hay': hay('hay'),
        'moss': clumps('moss', MOSS), 'bark': bark('bark'), 'blades': blades('blades'), 'worm': caterpillar(),
        'berry': solid(BERRY, 2, 'berry', .35), 'nut': solid(NUT, 2, 'nut', .3), 'cap': solid(CAP, 1, 'cap', .5),
        'litter_dry': litter('litter_dry', mix=((DRYLEAF, 5), (STRAW, 3), (OLD, 2)), straws=10),
        'wood_end': wood_end(), 'straw_end': straw_end('se'),
    }


def sprite_images():
    return {
        'leaf_green': leaf_sprite(GREEN), 'leaf_dry': leaf_sprite(DRYLEAF), 'leaf_old': leaf_sprite(OLD, nibbled=True),
        'leaf_slim': leaf_sprite(GREEN, broad=False), 'leaf_slim_dry': leaf_sprite(DRYLEAF, broad=False),
        'daisy': daisy(), 'bloom': bloom(),
    }


_atlas = None


def atlas():
    global _atlas
    if _atlas is None:
        img = Image.new('RGBA', (ATLAS, ATLAS), (0, 0, 0, 0))
        boxes = {}
        for key, tile in tile_images().items():
            ox, oy = TILES[key]
            for dy in (0, T):
                for dx in (0, T):
                    img.paste(tile, (ox + dx, oy + dy))
        for key, sprite in sprite_images().items():
            ox, oy = SPRITES[key]
            img.paste(sprite, (ox, oy))
            boxes[key] = sprite.getbbox()
        _atlas = (img, boxes)
    return _atlas


# ---- mesh helpers -------------------------------------------------------------------------------------------------------
class WildProp(Prop):
    """kit.Prop textured with the wild atlas; every face is given explicit texel coordinates."""

    def __init__(self, name):
        super().__init__(name, noise=False)
        self.parts = []
        self.img, _ = atlas()
        self.W = self.H = ATLAS

    def mat(self, name, unit=0):
        """Part for one (material group, render unit); parts with the same name and unit are shared."""
        for part in self.parts:
            if part['name'] == name and part['unit'] == unit:
                return part
        return self.part(name, unit)

    # -- texel placement --------------------------------------------------------------------------------------------------
    @staticmethod
    def fit(tile, st):
        """Continuous texel coordinates -> atlas uv inside the stored 2x2 copy of a tile (span must stay <= 16)."""
        ox, oy = TILES[tile]
        bs = math.floor(min(s for s, _ in st) / T) * T
        bt = math.floor(min(t for _, t in st) / T) * T
        out = []
        for s, t in st:
            u, v = s - bs, t - bt
            if u > 2 * T + 1e-6 or v > 2 * T + 1e-6:
                raise ValueError(f'face spans more than one tile repeat on {tile}: {st}')
            out.append((ox + min(u, 2 * T), oy + min(v, 2 * T)))
        return out

    def face(self, part, pts, normals, tile, st):
        uv = self.fit(tile, st)
        for k in range(1, len(pts) - 1):
            idx = (0, k, k + 1)
            self.tri(part, [pts[i] for i in idx], [normals[i] for i in idx], None, uvs=[uv[i] for i in idx])

    def planar(self, part, pts, normals, tile, offset=(0.0, 0.0)):
        """Planar texels on the dominant axis of the face (top: x, z; sides: horizontal, -y), world continuous."""
        g = cross(sub(pts[1], pts[0]), sub(pts[2], pts[0]))
        if math.sqrt(dot(g, g)) < 1e-9:
            return
        ax = max(range(3), key=lambda k: abs(g[k]))
        if ax == 1:
            st = [(p[0] + offset[0], p[2] + offset[1]) for p in pts]
        elif ax == 0:
            st = [(p[2] + offset[0], -p[1] + offset[1]) for p in pts]
        else:
            st = [(p[0] + offset[0], -p[1] + offset[1]) for p in pts]
        self.face(part, pts, normals, tile, st)

    # -- swept roll ---------------------------------------------------------------------------------------------------------
    def roll(self, part, path, profile, tile_out='weave', tile_in='weave_in', cap_start=False, cap_end=False, inner_split=None,
             tile_cap=None):
        """Sweep a cross-section along a path.
        path: [(x, z, nx, nz, s)] with the outward horizontal normal (nx, nz) and the texel coordinate s along the path.
        profile(i) -> [(t, y)] from the inner foot over the crest to the outer foot (t = outward offset).
        Faces before profile index inner_split take tile_in (the shaded inner side), the rest tile_out."""
        rows = []
        for i, (x, z, nx, nz, s) in enumerate(path):
            rows.append([[x + nx * t, y, z + nz * t] for t, y in profile(i)])
        n_i, n_j = len(rows), len(rows[0])
        # outward reference: the cross-section core
        cores = []
        for i, (x, z, nx, nz, s) in enumerate(path):
            prof = profile(i)
            tc = sum(t for t, _ in prof) / len(prof)
            yc = min(y for _, y in prof) + .35 * (max(y for _, y in prof) - min(y for _, y in prof))
            cores.append([x + nx * tc, yc, z + nz * tc])
        fn = {}
        for i in range(n_i - 1):
            for j in range(n_j - 1):
                a, b, c, d = rows[i][j], rows[i + 1][j], rows[i + 1][j + 1], rows[i][j + 1]
                n = cross(sub(c, a), sub(d, b))
                if math.sqrt(dot(n, n)) < 1e-9:
                    n = cross(sub(b, a), sub(d, a))
                n = norm(n)
                centre = [(a[k] + b[k] + c[k] + d[k]) / 4 for k in range(3)]
                core = [(cores[i][k] + cores[i + 1][k]) / 2 for k in range(3)]
                if dot(n, sub(centre, core)) < 0:
                    n = mul(n, -1)
                fn[i, j] = n
        vn = {}
        for i in range(n_i):
            for j in range(n_j):
                acc = [0.0, 0.0, 0.0]
                for di in (-1, 0):
                    for dj in (-1, 0):
                        f = fn.get((i + di, j + dj))
                        if f:
                            acc = add(acc, f)
                vn[i, j] = norm(acc)
        # texel t along the profile: cumulative length
        tcoord = []
        for i in range(n_i):
            acc, col = 0.0, [0.0]
            for j in range(1, n_j):
                acc += math.dist(rows[i][j], rows[i][j - 1])
                col.append(acc)
            tcoord.append(col)
        for i in range(n_i - 1):
            for j in range(n_j - 1):
                tile = tile_in if inner_split is not None and j < inner_split else tile_out
                pts = [rows[i][j], rows[i + 1][j], rows[i + 1][j + 1], rows[i][j + 1]]
                nrm = [vn[i, j], vn[i + 1, j], vn[i + 1, j + 1], vn[i, j + 1]]
                st = [(path[i][4], tcoord[i][j]), (path[i + 1][4], tcoord[i + 1][j]), (path[i + 1][4], tcoord[i + 1][j + 1]),
                      (path[i][4], tcoord[i][j + 1])]
                self.face(part, pts, nrm, tile, st)
        for flag, i, sign in ((cap_start, 0, -1), (cap_end, n_i - 1, 1)):
            if not flag:
                continue
            ring = rows[i]
            # tangent: the path direction, taken from the neighbouring path point
            j2 = 1 if i == 0 else n_i - 2
            d = norm(sub([path[i][0], 0, path[i][1]], [path[j2][0], 0, path[j2][1]]))
            centre = [sum(p[k] for p in ring) / n_j for k in range(3)]
            for j in range(n_j - 1):
                tri = [centre, ring[j], ring[j + 1]]
                st = [(8 + (p[0] - centre[0]) * d[2] - (p[2] - centre[2]) * d[0], 8 - (p[1] - centre[1])) for p in tri]
                self.face(part, tri, [d] * 3, tile_cap or tile_out, st)
            ring_close = [ring[-1], ring[0]]
            if math.dist(*ring_close) > 1e-6:
                tri = [centre, ring[-1], ring[0]]
                st = [(8 + (p[0] - centre[0]) * d[2] - (p[2] - centre[2]) * d[0], 8 - (p[1] - centre[1])) for p in tri]
                self.face(part, tri, [d] * 3, tile_cap or tile_out, st)

    # -- lumpy dome / ball ------------------------------------------------------------------------------------------------
    def dome(self, part, centre, rx, rz, h, tile, rng, seg=10, rings=((1.0, 0.0), (.9, .3), (.72, .58), (.48, .82), (.22, .96)),
             jitter=.07, phase=0.0, bottom=False):
        """Rounded heap: rings of (radius fraction, height fraction) closing at a top point; vertices jittered a little."""
        cx, cy, cz = centre
        loops = []
        for ri, (fr, fy) in enumerate(rings):
            loop = []
            for k in range(seg):
                a = phase + math.tau * k / seg + (rng.uniform(-.12, .12) if ri else 0)
                j = 1 + (rng.uniform(-jitter, jitter) if ri else rng.uniform(-jitter, jitter) * .5)
                y = cy + h * fy * (1 + (rng.uniform(-jitter, jitter) * .6 if ri else 0))
                loop.append([cx + math.cos(a) * rx * fr * j, y, cz + math.sin(a) * rz * fr * j])
            loops.append(loop)
        top = [cx + rng.uniform(-.2, .2), cy + h, cz + rng.uniform(-.2, .2)]

        def vnormal(p):
            return norm([(p[0] - cx) / (rx * rx), (p[1] - cy) / (h * h) * .8 + .1, (p[2] - cz) / (rz * rz)])
        # texels: s round the heap (a whole number of repeats), t up the slope, so strands run continuously
        around = max(1, round(math.pi * (rx + rz) / 16)) * 16
        slope = [0.0]
        for ri in range(1, len(loops)):
            slope.append(slope[-1] + math.dist(loops[ri][0], loops[ri - 1][0]))
        peak = slope[-1] + math.dist(loops[-1][0], top)
        for ri in range(len(loops) - 1):
            for k in range(seg):
                a, b = loops[ri][k], loops[ri][(k + 1) % seg]
                c, d = loops[ri + 1][(k + 1) % seg], loops[ri + 1][k]
                s0, s1 = around * k / seg, around * (k + 1) / seg
                st = [(s0, -slope[ri]), (s1, -slope[ri]), (s1, -slope[ri + 1]), (s0, -slope[ri + 1])]
                self.face(part, [a, b, c, d], [vnormal(p) for p in (a, b, c, d)], tile, st)
        for k in range(seg):
            a, b = loops[-1][k], loops[-1][(k + 1) % seg]
            s0, s1 = around * k / seg, around * (k + 1) / seg
            self.face(part, [a, b, top], [vnormal(p) for p in (a, b, top)], tile, [(s0, -slope[-1]), (s1, -slope[-1]), ((s0 + s1) / 2, -peak)])
        if bottom:
            ring = loops[0]
            for k in range(seg):
                tri = [[cx, cy, cz], ring[(k + 1) % seg], ring[k]]
                self.planar(part, tri, [[0, -1, 0]] * 3, tile)
        return loops

    def ball(self, part, centre, r, tile, rng, seg=7, squash=.8, jitter=.1):
        """Small rolled ball, flattened a little where it rests."""
        cx, cy, cz = centre
        lats = (-.5, -.2, .25, .62)
        loops = []
        for la in lats:
            ang = la * math.pi / 2
            rr = math.cos(ang) * r
            loop = []
            for k in range(seg):
                a = math.tau * (k + .5 * (lats.index(la) % 2)) / seg
                j = 1 + rng.uniform(-jitter, jitter)
                loop.append([cx + math.cos(a) * rr * j, cy + math.sin(ang) * r * squash * j, cz + math.sin(a) * rr * j])
            loops.append(loop)
        top = [cx, cy + r * squash, cz]
        bot = [cx, cy - r * squash * .7, cz]

        def vn(p):
            return norm(sub(p, [cx, cy, cz]))
        for ri in range(len(loops) - 1):
            for k in range(seg):
                a, b = loops[ri][k], loops[ri][(k + 1) % seg]
                c, d = loops[ri + 1][(k + 1) % seg], loops[ri + 1][k]
                for tri in ((a, b, c), (a, c, d)):
                    self.planar(part, list(tri), [vn(p) for p in tri], tile)
        for k in range(seg):
            a, b = loops[-1][k], loops[-1][(k + 1) % seg]
            self.planar(part, [a, b, top], [vn(a), vn(b), [0, 1, 0]], tile)
            a, b = loops[0][k], loops[0][(k + 1) % seg]
            self.planar(part, [b, a, bot], [vn(b), vn(a), [0, -1, 0]], tile)

    # -- sticks and small boxes -------------------------------------------------------------------------------------------
    def stick(self, part, a, b, w, side='bark', end='wood_end', roll=0.0, s0=0, hw=None):
        """Square-section stick from a to b (half width w, half height hw), bark along its length, cut ends."""
        hw = w if hw is None else hw
        axis = norm(sub(b, a))
        length = math.dist(a, b)
        ref = [0, 1, 0] if abs(axis[1]) < .95 else [1, 0, 0]
        u = norm(cross(axis, ref))
        v = cross(u, axis)
        c, s = math.cos(roll), math.sin(roll)
        u, v = add(mul(u, c), mul(v, s)), add(mul(u, -s), mul(v, c))
        corners = [(-1, -1), (1, -1), (1, 1), (-1, 1)]

        def at(p, cu, cv):
            return add(p, add(mul(u, cu * w), mul(v, cv * hw)))
        perim = 0.0
        for k in range(4):
            c0, c1 = corners[k], corners[(k + 1) % 4]
            n = norm(add(mul(u, c0[0] + c1[0]), mul(v, c0[1] + c1[1])))
            width = 2 * (w if c0[0] == c1[0] else hw)
            pts = [at(a, *c0), at(b, *c0), at(b, *c1), at(a, *c1)]
            st = [(s0, perim), (s0 + length, perim), (s0 + length, perim + width), (s0, perim + width)]
            self.face(part, pts, [n] * 4, side, st)
            perim += width
        for p, sign in ((a, -1), (b, 1)):
            pts = [at(p, *cc) for cc in (corners if sign > 0 else corners[::-1])]
            st = [(8 + dot(sub(q, p), u), 8 + dot(sub(q, p), v)) for q in pts]
            self.face(part, pts, [mul(axis, sign)] * 4, end, st)

    def cube(self, part, centre, half, tile, yaw=0.0, top_tile=None, offset=(0, 0)):
        """Small yawed box resting on its centre (berries, nuts, caterpillar segments); local texel mapping."""
        hx, hy, hz = half
        cy, sy = math.cos(yaw), math.sin(yaw)

        def w(x, y, z):
            return [centre[0] + x * cy + z * sy, centre[1] + y, centre[2] - x * sy + z * cy]

        def r(n):
            return [n[0] * cy + n[2] * sy, n[1], -n[0] * sy + n[2] * cy]
        ox, oy = offset
        faces = [
            ([(-hx, hy, -hz), (hx, hy, -hz), (hx, hy, hz), (-hx, hy, hz)], (0, 1, 0), lambda x, y, z: (x, z), top_tile or tile),
            ([(-hx, -hy, hz), (hx, -hy, hz), (hx, hy, hz), (-hx, hy, hz)], (0, 0, 1), lambda x, y, z: (x, -y), tile),
            ([(hx, -hy, -hz), (-hx, -hy, -hz), (-hx, hy, -hz), (hx, hy, -hz)], (0, 0, -1), lambda x, y, z: (-x, -y), tile),
            ([(hx, -hy, hz), (hx, -hy, -hz), (hx, hy, -hz), (hx, hy, hz)], (1, 0, 0), lambda x, y, z: (-z, -y), tile),
            ([(-hx, -hy, -hz), (-hx, -hy, hz), (-hx, hy, hz), (-hx, hy, -hz)], (-1, 0, 0), lambda x, y, z: (z, -y), tile),
        ]
        for corners, n, tex, tl in faces:
            pts = [w(*c) for c in corners]
            st = [(8 + ox + tex(*c)[0], 8 + oy + tex(*c)[1]) for c in corners]
            self.face(part, pts, [r(n)] * 4, tl, st)

    # -- sprites ------------------------------------------------------------------------------------------------------------
    def sprite_box(self, name):
        _, boxes = atlas()
        x0, y0, x1, y1 = boxes[name]
        ox, oy = SPRITES[name]
        return ox + x0, oy + y0, ox + x1, oy + y1, x0, y0, x1, y1

    def leaf(self, part, name, centre, yaw, fold=.35, curl=.12, tilt=0.0, scale=1.0):
        """Leaf sprite lying on a surface, cupped along its midrib (texel column 7.5) and the tip curling up a little."""
        ax0, ay0, ax1, ay1, x0, y0, x1, y1 = self.sprite_box(name)
        mid = 7.5
        cols = [x0, mid, x1]
        rows = [y0, (y0 + y1) / 2, y1]
        cy_, sy_ = math.cos(yaw), math.sin(yaw)
        vmid = (y0 + y1) / 2

        def place(px, py):
            u = (px - mid) * scale                      # across the leaf
            v = (vmid - py) * scale                     # along the leaf, + toward the tip
            y = abs(u) * math.sin(fold) + max(0.0, v) * curl + v * tilt
            x = u * math.cos(fold)
            return [centre[0] + x * cy_ + v * sy_, centre[1] + y, centre[2] - x * sy_ + v * cy_]
        sox, soy = SPRITES[name]
        for i in range(2):
            for j in range(2):
                quad = [(cols[i], rows[j]), (cols[i + 1], rows[j]), (cols[i + 1], rows[j + 1]), (cols[i], rows[j + 1])]
                pts = [place(*q) for q in quad]
                n = norm(cross(sub(pts[1], pts[0]), sub(pts[3], pts[0])))
                if n[1] < 0:
                    n = mul(n, -1)
                uvs = [(sox + q[0], soy + q[1]) for q in quad]
                self.quad(part, pts, None, uvs=uvs, normal=n)

    def straw(self, part, base, yaw, rise, length, s0=0):
        """One loose grass stem poking out of a roll: a thin square stem with the straw texture along it."""
        d = [math.cos(yaw) * math.cos(rise), math.sin(rise), math.sin(yaw) * math.cos(rise)]
        tip = [base[k] + d[k] * length for k in range(3)]
        self.stick(part, base, tip, .2, side='weave', end='weave', s0=s0)

    def cross(self, part, name, base, yaw, scale=1.0):
        """Upright crossed planes anchored at the bottom centre of the sprite's opaque box (a vanilla flower)."""
        ax0, ay0, ax1, ay1, x0, y0, x1, y1 = self.sprite_box(name)
        sox, soy = SPRITES[name]
        cx = (x0 + x1) / 2
        for k in (0, 1):
            a = yaw + k * math.pi / 2
            ca, sa = math.cos(a), math.sin(a)
            quad = ((x0, y1), (x1, y1), (x1, y0), (x0, y0))
            pts = [[base[0] + (px - cx) * scale * ca, base[1] + (y1 - py) * scale, base[2] + (px - cx) * scale * sa] for px, py in quad]
            self.quad(part, pts, None, uvs=[(sox + px, soy + py) for px, py in quad], normal=[0, 1, 0])

    def flat(self, part, name, centre, yaw, tilt=(0.0, 0.0), scale=1.0):
        """Sprite lying flat (a flower on a heap, a fallen wisp), optionally tilted."""
        ax0, ay0, ax1, ay1, x0, y0, x1, y1 = self.sprite_box(name)
        sox, soy = SPRITES[name]
        cy_, sy_ = math.cos(yaw), math.sin(yaw)
        mx, my = (x0 + x1) / 2, (y0 + y1) / 2
        pts = []
        quad = [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
        for px, py in quad:
            u, v = (px - mx) * scale, (my - py) * scale
            y = u * tilt[0] + v * tilt[1]
            pts.append([centre[0] + u * cy_ + v * sy_, centre[1] + y, centre[2] - u * sy_ + v * cy_])
        self.quad(part, pts, None, uvs=[(sox + px, soy + py) for px, py in quad], normal=[0, 1, 0])

    # -- flat polygons ------------------------------------------------------------------------------------------------------
    def patch(self, part, centre, radii, y, tile, rng, seg=9, wobble=.22, offset=(0.0, 0.0)):
        """Irregular flat patch (moss, hay, litter) as a fan; planar texels."""
        cx, cz = centre
        ring = []
        for k in range(seg):
            a = math.tau * k / seg
            j = 1 + rng.uniform(-wobble, wobble)
            ring.append([cx + math.cos(a) * radii[0] * j, y, cz + math.sin(a) * radii[1] * j])
        c = [cx, y, cz]
        for k in range(seg):
            self.planar(part, [c, ring[(k + 1) % seg], ring[k]], [[0, 1, 0]] * 3, tile, offset)


class PathLine:
    """Polyline of (x, z, nx, nz) with the outward normal, sampled by length."""

    def __init__(self, pts, closed=False):
        self.pts = list(pts) + ([pts[0]] if closed else [])
        self.cum = [0.0]
        for a, b in zip(self.pts, self.pts[1:]):
            self.cum.append(self.cum[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
        self.length = self.cum[-1]
        self.closed = closed

    def at(self, s):
        if self.closed:
            s %= self.length
        s = max(0.0, min(self.length, s))
        for i in range(1, len(self.cum)):
            if s <= self.cum[i] or i == len(self.cum) - 1:
                a, b = self.pts[i - 1], self.pts[i]
                seg = self.cum[i] - self.cum[i - 1] or 1
                f = (s - self.cum[i - 1]) / seg
                nx, nz = a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f
                d = math.hypot(nx, nz) or 1
                return a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, nx / d, nz / d


def bundle(prop, part, line, s0, s1, h, inner, outer, foot=0.0, inner_foot=None, shift=0.0, droop=0.0, tex0=0.0, steps=4,
           inner_split=2):
    """One tied bundle of dry grass laid along a path from length s0 to s1: a short roll with blunt, tapered ends, strands
    running along it. droop lowers one end (signed) so neighbouring bundles sit at slightly different angles."""
    path, scale = [], []
    for k in range(steps + 1):
        u = k / steps
        x, z, nx, nz = line.at(s0 + (s1 - s0) * u)
        path.append((x, z, nx, nz, tex0 + (s1 - s0) * u))
        scale.append(.5 + .5 * math.sin(math.pi * u) ** .6)
    fi = foot if inner_foot is None else inner_foot

    def profile(i):
        f = scale[i]
        hh = fi + (h * (1 + droop * (i / steps - .5)) - fi) * f
        return [(t * (.6 + .4 * f) + shift, y) for t, y in rim_profile(max(hh, fi + .3), inner, outer, foot, inner_foot)]
    prop.roll(part, path, profile, inner_split=inner_split, cap_start=True, cap_end=True, tile_cap='straw_end')


def smooth(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def rim_profile(h, inner, outer, foot=0.0, inner_foot=None, lean=0.0):
    """Cross-section of a woven roll: inner foot -> inner shoulder -> crest -> outer shoulder -> outer foot.
    t offsets relative to the crest line (negative = inward); `inner`/`outer` are the half widths at the foot."""
    fi = foot if inner_foot is None else inner_foot
    return [(-inner, fi), (-inner * .72, fi + (h - fi) * .55), (-inner * .38, h * .93), (lean, h),
            (outer * .4, h * .9), (outer * .78, foot + (h - foot) * .5), (outer, foot)]
