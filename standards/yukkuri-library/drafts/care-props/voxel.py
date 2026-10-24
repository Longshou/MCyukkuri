"""Vanilla-style voxel kit for placed care props: cells on the 1/16 grid, faces textured with hand-drawn 16x16 tiles.

A prop is a set of cells (one unit = 1/16 block) carrying a material and a render unit. Only exposed faces are emitted,
merged into rectangles that never cross a 16-texel tile repeat, so every face shows its material at the vanilla density of
one texel per unit, world aligned (neighbouring cells continue the same pattern like vanilla blocks). Normals are the face
axes, so the game's entity lighting shades top, sides and underside like a block model. No bevels, no flat colour faces.

Tiles are drawn here from local hue-shifted ramps (dark -> light), never copied from Minecraft. A material maps faces to
tiles: keys 'top', 'bottom', 'side', 'inner' (side faces looking toward the prop's vertical axis, for bowls and trays), and
'*'. Layer heights default to whole units; `ys` may give custom layer boundaries (e.g. a 1.5 floor kept for collision).

Render units: a face of a cell is hidden only by a neighbour whose unit is not higher, so fill layers (units 1..8) can grow
over earlier layers without holes when later units are not drawn.
"""
import math, random, zlib
from PIL import Image
from kit import Prop

T = 16                                  # texels per tile = units per block


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


class Atlas:
    """Ordered 16x16 tiles packed into one texture, COLS tiles per row."""
    COLS = 8

    def __init__(self):
        self.tiles, self.order = {}, []

    def add(self, key, image):
        if key not in self.tiles:
            self.tiles[key] = image
            self.order.append(key)
        return key

    def origin(self, key):
        i = self.order.index(key)
        return (i % self.COLS) * T, (i // self.COLS) * T

    def size(self):
        rows = max(1, (len(self.order) + self.COLS - 1) // self.COLS)
        h = 16
        while h < rows * T:
            h *= 2
        return self.COLS * T, h

    def image(self):
        w, h = self.size()
        img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        for key in self.order:
            img.paste(self.tiles[key], self.origin(key))
        return img


class Mat:
    def __init__(self, name, **faces):
        self.name, self.faces = name, faces

    def tile(self, face):
        f = self.faces
        if face in f:
            return f[face]
        if face == 'inner' and 'side' in f:
            return f['side']
        return f.get(face if face in ('top', 'bottom') else 'side', f.get('*'))


DIRS = {'top': (0, 1, 0), 'bottom': (0, -1, 0), 'n': (0, 0, -1), 's': (0, 0, 1), 'w': (-1, 0, 0), 'e': (1, 0, 0)}


class Voxels:
    """cells[(i, j, k)] = (Mat, unit); cell (i, j, k) spans x = i + ox .. +1, layer j (ys), z = k + oz .. +1."""

    def __init__(self, name, ox=0.0, oz=0.0, ys=None, vtop=16.0, centre=(0.0, 0.0)):
        self.name, self.ox, self.oz, self.ys, self.vtop, self.centre = name, ox, oz, ys, vtop, centre
        self.cells = {}
        self.atlas = Atlas()
        self.extras = []            # callables(prop) adding sprites after the voxel faces
        self.diagonal = None        # optional f(px, pz) -> True where side faces form a 45-degree staircase

    def y(self, j):
        if self.ys is None:
            return float(j)
        f = math.floor(j)
        base = (lambda n: self.ys[n] if n < len(self.ys) else self.ys[-1] + (n - len(self.ys) + 1))
        return base(f) if f == j else base(f) + (base(f + 1) - base(f)) * (j - f)

    def set(self, i, j, k, mat, unit=0):
        if mat is None:
            self.cells.pop((i, j, k), None)
        else:
            self.cells[(i, j, k)] = (mat, unit)

    def fill(self, i0, j0, k0, i1, j1, k1, mat, unit=0):
        """Inclusive-exclusive cell box."""
        for i in range(i0, i1):
            for j in range(j0, j1):
                for k in range(k0, k1):
                    self.set(i, j, k, mat, unit)

    def get(self, i, j, k):
        return self.cells.get((i, j, k))

    # -- meshing ---------------------------------------------------------------------------------
    def faces(self):
        """Visible faces grouped by (unit, direction, plane index, tile) -> set of 2D cell positions."""
        groups = {}
        cx, cz = self.centre
        for (i, j, k), (mat, unit) in self.cells.items():
            for face, (dx, dy, dz) in DIRS.items():
                other = self.cells.get((i + dx, j + dy, k + dz))
                if other is not None and other[1] <= unit:
                    continue
                kind, normal = face, DIRS[face]
                if face in ('n', 's', 'w', 'e'):
                    px, pz = i + self.ox + .5 - cx, k + self.oz + .5 - cz
                    kind = 'inner' if px * dx + pz * dz < -.25 else 'side'
                    if self.diagonal and self.diagonal(px, pz):
                        # both faces of a staircase step take the diagonal's normal, so the step shades flat
                        sx, sz = math.copysign(1, px), math.copysign(1, pz)
                        sign = 1 if sx * dx + sz * dz > 0 else -1
                        normal = (round(sign * sx * math.sqrt(.5), 6), 0, round(sign * sz * math.sqrt(.5), 6))
                tile = mat.tile(kind)
                if callable(tile):
                    tile = tile(i, j, k, face)
                if tile is None:
                    continue
                if face in ('top', 'bottom'):
                    plane, a, b = j, i, k
                elif face in ('n', 's'):
                    plane, a, b = k, i, j
                else:
                    plane, a, b = i, k, j
                groups.setdefault((unit, face, plane, tile, mat.name, normal), set()).add((a, b))
        return groups

    def to_prop(self):
        p = Prop(self.name, noise=False)
        p.parts = []
        atlas_w, atlas_h = self.atlas.size()
        p.W, p.H = atlas_w, atlas_h
        p.img = self.atlas.image()
        parts = {}
        for (unit, face, plane, tile, mname, normal), cells in sorted(self.faces().items(), key=lambda kv: (kv[0][0], kv[0][4], kv[0][1], kv[0][2], kv[0][3], kv[0][5])):
            pname = f'{mname}_u{unit}' if unit else mname
            if pname not in parts:
                parts[pname] = p.part(pname, unit)
            for rect in self.greedy(face, plane, cells):
                self.emit(p, parts[pname], face, plane, rect, tile, normal)
        for extra in self.extras:
            extra(p)
        return p

    def coords(self, face, plane, a, b):
        """World point of the 2D lattice corner (a, b) on a face plane."""
        if face in ('top', 'bottom'):
            return [a + self.ox, self.y(plane + (1 if face == 'top' else 0)), b + self.oz]
        if face in ('n', 's'):
            return [a + self.ox, self.y(b), plane + (1 if face == 's' else 0) + self.oz]
        return [plane + (1 if face == 'e' else 0) + self.ox, self.y(b), a + self.oz]

    def texel(self, face, pos):
        """(s, t) texel of a world point in the face's tile, vanilla orientation, unwrapped."""
        x, y, z = pos
        if face == 'top':
            return x, z
        if face == 'bottom':
            return x, -z
        v = self.vtop - y
        return {'n': (-x, v), 's': (x, v), 'w': (z, v), 'e': (-z, v)}[face]

    def greedy(self, face, plane, cells):
        """Merge cells into rectangles inside one tile repeat."""
        left = set(cells)
        rects = []

        def block_of(a, b):
            mid = self.coords(face, plane, a + .5, b + .5)
            s, t = self.texel(face, mid)
            return math.floor(s / T), math.floor(t / T)
        for (a, b) in sorted(cells, key=lambda c: (c[1], c[0])):
            if (a, b) not in left:
                continue
            blk = block_of(a, b)
            a1 = a + 1
            while (a1, b) in left and block_of(a1, b) == blk:
                a1 += 1
            b1 = b + 1
            while all((x, b1) in left and block_of(x, b1) == blk for x in range(a, a1)):
                b1 += 1
            for x in range(a, a1):
                for yb in range(b, b1):
                    left.discard((x, yb))
            rects.append((a, b, a1, b1))
        return rects

    def emit(self, p, part, face, plane, rect, tile, normal=None):
        a0, b0, a1, b1 = rect
        self.quad(p, part, face, [self.coords(face, plane, a, b) for a, b in ((a0, b0), (a1, b0), (a1, b1), (a0, b1))], tile, normal)

    def quad(self, p, part, face, corners, tile, normal=None):
        """One axis-aligned face with world-aligned texels inside a single tile repeat."""
        ox, oy = self.atlas.origin(tile)
        st = [self.texel(face, c) for c in corners]
        cs = sum(s for s, _ in st) / 4
        ct = sum(t for _, t in st) / 4
        bs, bt = math.floor(cs / T) * T, math.floor(ct / T) * T
        uvs = [(ox + min(T, max(0.0, s - bs)), oy + min(T, max(0.0, t - bt))) for s, t in st]
        p.quad(part, corners, None, uvs=uvs, normal=list(normal or DIRS[face]))

    def box(self, p, part, lo, hi, tile, bottom=False):
        """Free box (any size up to one tile per face) outside the cell grid, e.g. a half-unit bump on a fill surface."""
        x0, y0, z0 = lo
        x1, y1, z1 = hi
        faces = {'top': [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]],
                 'n': [[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0]],
                 's': [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]],
                 'w': [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]],
                 'e': [[x1, y0, z0], [x1, y0, z1], [x1, y1, z1], [x1, y1, z0]]}
        if bottom:
            faces['bottom'] = [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]]
        for face, corners in faces.items():
            self.quad(p, part, face, corners, tile)


# ---- tile painting ------------------------------------------------------------------------------------------------------
def rng_of(key):
    return random.Random(zlib.crc32(key.encode()))


def canvas(ramp, step):
    return Image.new('RGBA', (T, T), rgb(ramp[step]) + (255,))


def put(img, x, y, colour):
    img.putpixel((x % T, y % T), (rgb(colour) if isinstance(colour, str) else colour[:3]) + (255,))


def speckle(img, ramp, key, amount=.25, steps=(-1, 1), weights=None):
    """Vanilla-like single-pixel variation: each pixel may move one step darker or lighter in its ramp."""
    r = rng_of(key)
    base = [[None] * T for _ in range(T)]
    for y in range(T):
        for x in range(T):
            px = img.getpixel((x, y))[:3]
            idx = min(range(len(ramp)), key=lambda i: sum((a - b) ** 2 for a, b in zip(rgb(ramp[i]), px)))
            base[y][x] = idx
    for y in range(T):
        for x in range(T):
            if r.random() < amount:
                d = r.choices(steps, weights)[0] if weights else r.choice(steps)
                put(img, x, y, ramp[max(0, min(len(ramp) - 1, base[y][x] + d))])
    return img


def rows_tile(rows, legend):
    img = Image.new('RGBA', (T, T), (0, 0, 0, 0))
    for y, line in enumerate(rows):
        for x, ch in enumerate(line):
            if ch in legend:
                put(img, x, y, legend[ch])
    return img
