"""The stray cardboard carton: a box lying on its side, opening toward +Z, in Minecraft block style.

Axis-aligned cuboids only, one texel per unit (16 per block) on a hand-painted pixel atlas, flat face normals, no decal
boxes and no overlapping volumes, so no two visible faces share a plane (no z-fighting or normal flicker up close).

Units are 1/16 block, origin at the centre of the shelter's core cell floor, +Z toward the entrance. The core cell and the
cell in front of it are the two sleeping cells (back and mouth); the walls stand outside both so the cells stay walkable.
Interior: x -11..11, z -8..24, y 0..23 (two adults one behind the other, a Marisa hat clears the roof).

Units of the exported mesh: 0 shell (always), 1 bedding (lined), 2 top flap folded up (dry and damp),
3 top flap hanging over the opening (soaked, before the collapse).
"""
import random, uuid, zlib
from PIL import Image

PAL = {'k0': (0x78, 0x5c, 0x3f), 'k1': (0x96, 0x73, 0x4e), 'k2': (0xb1, 0x8b, 0x58), 'k3': (0xd4, 0xbc, 0x84),
       'liner': (0xa8, 0x86, 0x58), 'tape': (0xd8, 0xc2, 0x8c), 'tape_hi': (0xe6, 0xd4, 0xa4), 'label': (0xe8, 0xe0, 0xc8),
       'ink': (0x5a, 0x45, 0x32), 'red': (0x9a, 0x3c, 0x2e), 'paper': (0xee, 0xea, 0xdc), 'news': (0xc4, 0xc2, 0xb8),
       'straw': (0xc8, 0xb0, 0x70), 'straw_dk': (0xa0, 0x8a, 0x52)}
SIZE = 128
OUTER_X, BACK, FRONT, TOP, WALL = 12, -9, 24, 24, 1


def clamp(c):
    return tuple(max(0, min(255, v)) for v in c)


class Atlas:
    """Faces are packed tallest first on shelves, then painted by a named style."""
    def __init__(self):
        self.img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))

    def pack(self, sizes):
        order = sorted(range(len(sizes)), key=lambda i: (-sizes[i][1], -sizes[i][0]))
        rects, x, y, row = [None] * len(sizes), 0, 0, 0
        for i in order:
            w, h = sizes[i]
            if x + w > SIZE: x, y, row = 0, y + row, 0
            if y + h > SIZE: raise ValueError('cardboard atlas full')
            rects[i] = (x, y, w, h); x += w; row = max(row, h)
        return rects


def paint(img, rect, style, seed):
    x0, y0, w, h = rect
    rng = random.Random(zlib.crc32(f'{style}:{seed}'.encode()))
    px = lambda x, y, c: img.putpixel((x0 + x, y0 + y), clamp(c) + (255,))
    if style == 'cut':            # corrugated cut edge: liner, flute, liner in a wave
        for y in range(h):
            for x in range(w):
                n = x if w >= h else y
                px(x, y, PAL['k3'] if n % 2 == 0 else PAL['k1'])
        return
    if style == 'fold':           # a folded corner edge
        for y in range(h):
            for x in range(w):
                px(x, y, PAL['k1'] if rng.random() < .8 else PAL['k0'])
        return
    base = {'outer': PAL['k2'], 'inner': PAL['liner'], 'floor': PAL['liner'], 'flap_in': PAL['liner'],
            'paper': PAL['paper'], 'news': PAL['news'], 'straw': PAL['straw']}[style.split(':')[0]]
    for y in range(h):
        for x in range(w):
            r = rng.random()
            c = base
            if r < .10: c = tuple(v - 10 for v in base)
            elif r < .17: c = tuple(v + 8 for v in base)
            px(x, y, c)
    kind = style.split(':')[0]
    if kind in ('outer', 'inner', 'flap_in'):
        # One-texel crease along every outer border, vanilla-style block edges.
        for x in range(w):
            px(x, 0, PAL['k1'] if kind == 'outer' else tuple(v - 18 for v in base))
            px(x, h - 1, PAL['k1'] if kind == 'outer' else tuple(v - 18 for v in base))
        for y in range(h):
            px(0, y, PAL['k1'] if kind == 'outer' else tuple(v - 18 for v in base))
            px(w - 1, y, PAL['k1'] if kind == 'outer' else tuple(v - 18 for v in base))
    if kind == 'news':
        for y in range(1, h, 2):
            for x in range(1, w - 1):
                if rng.random() < .6: px(x, y, PAL['ink'])
    if kind == 'straw':
        for x in range(0, w, 2):
            for y in range(h): px(x, y, PAL['straw_dk'])
    marks = style.split(':')[1:]
    if 'tape_v' in marks:         # a vertical strip of packing tape down the middle
        c = w // 2
        for y in range(h):
            for x in range(c - 2, c + 2): px(x, y, PAL['tape'])
            px(c - 1, y, PAL['tape_hi'])
    if 'tape_wrap' in marks:      # the back panel's tape folds over the edge for a few texels (rows run from the back)
        c = w // 2
        for y in range(6):
            for x in range(c - 2, c + 2): px(x, y, PAL['tape'])
            px(c - 1, y, PAL['tape_hi'])
    if 'label' in marks:          # shipping label plus a pair of "this side up" arrows, now pointing sideways
        lx, ly = 4, 3
        for y in range(ly, ly + 7):
            for x in range(lx, lx + 11): px(x, y, PAL['label'])
        for y in (ly + 2, ly + 4):
            for x in range(lx + 1, lx + 10):
                if (x + y) % 3: px(x, y, PAL['ink'])
        head = ['..#', '.##', '###', '.##', '..#']
        for cy in (ly + 11, ly + 17):
            for x in range(lx + 3, lx + 10): px(x, cy, PAL['red'])
            for dy, row in enumerate(head):
                for dx, ch in enumerate(row):
                    if ch == '#': px(lx + 1 + dx, cy - 2 + dy, PAL['red'])
    if 'stain' in marks:          # a scuffed, damp-dark patch at the foot of the wall
        for y in range(h - 5, h - 1):
            for x in range(3, min(w - 2, 14)):
                if rng.random() < .55: px(x, y, tuple(v - 22 for v in PAL['k2']))


class Carton:
    def __init__(self, name):
        self.name = name; self.atlas = Atlas(); self.cubes = []; self.collision = []

    def cube(self, name, lo, hi, faces, unit=0, solid=False):
        """faces: per Blockbench face name a paint style; missing faces use 'fold'."""
        self.cubes.append((name, lo, hi, faces, unit))
        if solid: self.collision.append([*lo, *hi])

    @staticmethod
    def faces_of(lo, hi):
        """Blockbench face -> (normal, corners top-left, top-right, bottom-right, bottom-left as seen from outside, width, height)."""
        (x0, y0, z0), (x1, y1, z1) = lo, hi
        return {
            'north': ((0, 0, -1), [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)], x1 - x0, y1 - y0),
            'south': ((0, 0, 1), [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)], x1 - x0, y1 - y0),
            'east': ((1, 0, 0), [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)], z1 - z0, y1 - y0),
            'west': ((-1, 0, 0), [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)], z1 - z0, y1 - y0),
            'up': ((0, 1, 0), [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], x1 - x0, z1 - z0),
            'down': ((0, -1, 0), [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], x1 - x0, z1 - z0),
        }

    def export(self):
        faces = [(name, face, spec) for name, lo, hi, _, _ in self.cubes for face, spec in self.faces_of(lo, hi).items()]
        rects = self.atlas.pack([(max(1, round(spec[2])), max(1, round(spec[3]))) for _, _, spec in faces])
        rect_of = {(name, face): rect for (name, face, _), rect in zip(faces, rects)}
        verts, parts, elements = [], [], []
        for name, lo, hi, styles, unit in self.cubes:
            start = len(verts) // 8
            bb_faces = {}
            for face, (normal, corners, _, _) in self.faces_of(lo, hi).items():
                rect = rect_of[(name, face)]
                paint(self.atlas.img, rect, styles.get(face, 'fold'), f'{name}/{face}')
                u0, v0, w, h = rect
                uv = [(u0, v0), (u0 + w, v0), (u0 + w, v0 + h), (u0, v0 + h)]
                for tri in ((0, 1, 2), (0, 2, 3)):
                    for k in tri:
                        verts.extend([*corners[k], uv[k][0] / SIZE, uv[k][1] / SIZE, *normal])
                bb_faces[face] = {'uv': [u0, v0, u0 + w, v0 + h], 'texture': 0}
            parts.append({'name': name, 'start': start, 'count': len(verts) // 8 - start, 'unit': unit})
            elements.append({'name': name, 'type': 'cube', 'box_uv': False, 'from': list(lo), 'to': list(hi), 'origin': [0, 0, 0],
                             'faces': bb_faces, 'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL, self.name + '/' + name))})
        bounds = [[min(verts[k::8]), max(verts[k::8])] for k in range(3)]
        mesh = {'format': 1, 'units_per_block': 16, 'vertices': verts, 'parts': parts, 'bounds': bounds}
        return mesh, elements, self.atlas.img


def build():
    c = Carton('cardboard_shelter')
    X, B, F, T = OUTER_X, BACK, FRONT, TOP
    # Shell: back panel, two side panels, the top panel; the bottom panel lies on the ground (no collision, the cells stay walkable).
    c.cube('back_panel', (-X, 0, B), (X, T, B + WALL), {'north': 'outer:tape_v', 'south': 'inner'}, solid=True)
    c.cube('left_panel', (-X, 0, B + WALL), (-X + WALL, T - WALL, F), {'west': 'outer:stain', 'east': 'inner', 'south': 'cut'}, solid=True)
    c.cube('right_panel', (X - WALL, 0, B + WALL), (X, T - WALL, F), {'east': 'outer:label', 'west': 'inner', 'south': 'cut'}, solid=True)
    c.cube('top_panel', (-X, T - WALL, B + WALL), (X, T, F), {'up': 'outer:tape_wrap', 'down': 'inner', 'south': 'cut'}, solid=True)
    c.cube('bottom_panel', (-X + WALL, 0, B + WALL), (X - WALL, .5, F), {'up': 'floor', 'south': 'cut'})
    # Flaps of the open end: the side flaps swing out flat to the sides, the bottom flap lies on the ground as a doorstep.
    c.cube('left_flap', (-X - 8, 0, F - WALL), (-X, T - WALL, F), {'north': 'outer', 'south': 'flap_in', 'west': 'cut'}, solid=True)
    c.cube('right_flap', (X, 0, F - WALL), (X + 8, T - WALL, F), {'north': 'outer', 'south': 'flap_in', 'east': 'cut'}, solid=True)
    c.cube('bottom_flap', (-X + WALL, 0, F), (X - WALL, .5, F + 8), {'up': 'flap_in', 'south': 'cut'})
    # Top flap: folded straight up while dry or damp; hanging limp over the upper opening once soaked.
    c.cube('top_flap_up', (-X, T, F - WALL), (X, T + 8, F), {'north': 'outer', 'south': 'flap_in', 'up': 'cut'}, unit=2)
    c.cube('top_flap_hanging', (-X, T - 8, F), (X, T, F + WALL), {'north': 'flap_in', 'south': 'outer:stain', 'down': 'cut'}, unit=3)
    # Bedding: shredded newspaper, paper scraps and dry grass pushed into the floor (lined shelters only).
    for i, (x0, z0, w, d, style) in enumerate([(-9, -6, 5, 3, 'news'), (-3, -7, 4, 5, 'paper'), (2, -5, 6, 3, 'straw'),
                                               (-8, 0, 3, 6, 'straw'), (-4, 3, 6, 4, 'news'), (3, 1, 5, 5, 'paper'),
                                               (-7, 9, 5, 4, 'paper'), (0, 10, 4, 3, 'straw'), (5, 8, 4, 6, 'news')]):
        c.cube(f'bedding_{i}', (x0, .5, z0), (x0 + w, 1, z0 + d), {'up': style, 'north': style, 'south': style, 'east': style, 'west': style}, unit=1)
    return c
