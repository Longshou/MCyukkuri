"""The stray cardboard carton: a wide box lying on its side, opening toward +Z, in Minecraft block style.

Axis-aligned cuboids only, one texel per unit (16 per block) on a hand-painted pixel atlas, flat face normals, no decal
boxes and no overlapping volumes, so no two visible faces share a plane (no z-fighting or normal flicker up close).

Units are 1/16 block, origin at the centre of the shelter's core cell floor, +Z toward the entrance. Two adults lie side by
side at x = -9 and +9 (ShelterBlockEntity.restSpot, in the cells left and right of the core). Each spot has 12 units of
clear reach to its side wall and to the back panel, so an adult Marisa (hat brim reaching 11.4 from her axis at any heading)
can walk in sideways and turn without the brim meeting cardboard; it lies facing out. Interior x -21..21, z -12..9, y 0..23.

Three exports share one shape family: cardboard_shelter (dry), cardboard_shelter_damp (water stains spreading down from the
top, the top flap flopped forward) and cardboard_shelter_soaked (dark through, the roof sagging in steps, the side walls bowed
in, the top flap hanging down over the opening). Only the dry shell gives collision; the wet shapes stay inside it.
Unit 1 of each export is the bedding (lined shelters).
"""
import random, uuid, zlib
from PIL import Image

PAL = {'k0': (0x78, 0x5c, 0x3f), 'k1': (0x96, 0x73, 0x4e), 'k2': (0xb1, 0x8b, 0x58), 'k3': (0xd4, 0xbc, 0x84),
       'liner': (0xa8, 0x86, 0x58), 'tape': (0xd8, 0xc2, 0x8c), 'tape_hi': (0xe6, 0xd4, 0xa4), 'label': (0xe8, 0xe0, 0xc8),
       'ink': (0x5a, 0x45, 0x32), 'red': (0x9a, 0x3c, 0x2e), 'paper': (0xee, 0xea, 0xdc), 'news': (0xc4, 0xc2, 0xb8),
       'straw': (0xc8, 0xb0, 0x70), 'straw_dk': (0xa0, 0x8a, 0x52)}
SIZE = 128
WET = ((0x5c, 0x43, 0x2c), (0x46, 0x33, 0x22))   # soaked kraft, and the tide line at a stain's edge


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


def wet(img, rect, level, vertical, seed):
    """Water stains: on walls they creep down from the top edge with a ragged tide line and drips; on flat faces they
    are blotches. Level 1 (damp) reaches about a third down, level 2 (soaked) nearly to the floor and darker."""
    if not level: return
    x0, y0, w, h = rect
    rng = random.Random(zlib.crc32(f'wet{level}:{seed}'.encode()))
    mix = .55 if level == 1 else .82
    def soak(x, y, k=mix):
        r, g, b, a = img.getpixel((x0 + x, y0 + y))
        img.putpixel((x0 + x, y0 + y), clamp(tuple(int(c * (1 - k) + t * k) for c, t in zip((r, g, b), WET[0]))) + (255,))
    if vertical:
        depth, walk = (.38 if level == 1 else .82) * h, 0.0
        for x in range(w):
            walk = max(-.18 * h, min(.18 * h, walk + rng.uniform(-1.6, 1.6)))
            d = int(max(1, min(h, depth + walk)))
            for y in range(d): soak(x, y, mix * (1.0 if y < d - 2 else .7))
            if d < h: img.putpixel((x0 + x, y0 + d - 1), WET[1] + (255,))
            if rng.random() < (.12 if level == 1 else .25):          # a drip running below the line
                for y in range(d, min(h, d + rng.randint(2, 5))): soak(x, y, mix * .8)
    else:
        blots = max(1, int(w * h / (60 if level == 1 else 18)))
        for _ in range(blots):
            cx, cy, r = rng.uniform(0, w), rng.uniform(0, h), rng.uniform(1.5, 4.5 if level == 1 else 6)
            for y in range(max(0, int(cy - r)), min(h, int(cy + r) + 1)):
                for x in range(max(0, int(cx - r)), min(w, int(cx + r) + 1)):
                    if (x - cx) ** 2 + (y - cy) ** 2 <= r * r: soak(x, y)


class Carton:
    def __init__(self, name, level=0):
        self.name = name; self.level = level; self.atlas = Atlas(); self.cubes = []; self.collision = []

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
                style = styles.get(face, 'fold')
                paint(self.atlas.img, rect, style, f'{name}/{face}')
                if style.split(':')[0] not in ('paper', 'news', 'straw'):
                    wet(self.atlas.img, rect, self.level, face not in ('up', 'down'), f'{name}/{face}')
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


def build(level=0):
    """level 0 dry, 1 damp, 2 soaked (ShelterBlockEntity.rainStage)."""
    c = Carton(('cardboard_shelter', 'cardboard_shelter_damp', 'cardboard_shelter_soaked')[level], level)
    X, B, F, T = 22, -13, 9, 24         # outer half width, back face, open front, roof top
    dry = level == 0
    c.cube('back_panel', (-X, 0, B), (X, T, B + 1), {'north': 'outer:tape_v', 'south': 'inner'}, solid=dry)
    if level < 2:
        c.cube('left_panel', (-X, 0, B + 1), (-X + 1, T - 1, F), {'west': 'outer:stain', 'east': 'inner', 'south': 'cut'}, solid=dry)
        c.cube('right_panel', (X - 1, 0, B + 1), (X, T - 1, F), {'east': 'outer:label', 'west': 'inner', 'south': 'cut'}, solid=dry)
        c.cube('top_panel', (-X, T - 1, B + 1), (X, T, F), {'up': 'outer:tape_wrap', 'down': 'inner', 'south': 'cut'}, solid=dry)
    else:
        # Soaked through: each side wall bows in by two texels over its middle (stepped), the roof sags in steps toward the middle.
        for side, sx in (('left', -1), ('right', 1)):
            look = {('west' if sx < 0 else 'east'): 'outer:stain' if sx < 0 else 'outer:label', ('east' if sx < 0 else 'west'): 'inner'}
            for j, (z0, z1, inset) in enumerate([(B + 1, -4, 0), (-4, -1, 1), (-1, 3, 2), (3, 6, 1), (6, F, 0)]):
                lo = -X + inset if sx < 0 else X - 1 - inset
                c.cube(f'{side}_panel_{j}', (lo, 0, z0), (lo + 1, T - 1, z1), look | ({'south': 'cut'} if z1 == F else {}))
        for i, (x0, x1, top) in enumerate([(-X, -16, T), (-16, -11, T - 1), (-11, -6, T - 2), (-6, 6, T - 3),
                                           (6, 11, T - 2), (11, 16, T - 1), (16, X, T)]):
            c.cube(f'top_panel_{i}', (x0, top - 1, B + 1), (x1, top, F), {'up': 'outer:tape_wrap' if i == 3 else 'outer',
                                                                            'down': 'inner', 'south': 'cut'})
    c.cube('bottom_panel', (-X + 1, 0, B + 1), (X - 1, .5, F), {'up': 'floor', 'south': 'cut'})
    # Side flaps swung right back against the outside of the side walls; the bottom flap is a doorstep on the ground.
    c.cube('left_flap', (-X - 1, 0, 1), (-X, T - 2, F), {'west': 'flap_in', 'east': 'outer', 'south': 'cut'}, solid=dry)
    c.cube('right_flap', (X, 0, 1), (X + 1, T - 2, F), {'east': 'flap_in', 'west': 'outer', 'south': 'cut'}, solid=dry)
    c.cube('bottom_flap', (-X + 1, 0, F), (X - 1, .5, F + 7), {'up': 'flap_in', 'south': 'cut'})
    # Top flap: folded straight up while dry; flopped forward like a limp awning once damp; hanging over the opening when soaked.
    if level == 0:
        c.cube('top_flap', (-X, T, F - 1), (X, T + 7, F), {'north': 'outer', 'south': 'flap_in', 'up': 'cut'})
    elif level == 1:
        c.cube('top_flap', (-X, T - 1, F), (X, T, F + 7), {'up': 'flap_in', 'down': 'outer', 'south': 'cut'})
    else:
        c.cube('top_flap', (-X, T - 16, F), (X, T - 3, F + 1), {'north': 'flap_in', 'south': 'outer:stain', 'down': 'cut'})
    # Bedding round the two lying spots: shredded newspaper, paper scraps and dry grass pushed into the floor.
    for i, (x0, z0, w, d, style) in enumerate([(-17, -6, 5, 3, 'news'), (-11, -7, 4, 5, 'paper'), (-6, -4, 5, 3, 'straw'),
                                               (-16, 0, 3, 6, 'straw'), (-12, 3, 6, 4, 'news'), (-4, 2, 4, 4, 'paper'),
                                               (1, -6, 5, 3, 'paper'), (7, -7, 4, 5, 'news'), (12, -5, 5, 3, 'straw'),
                                               (3, 1, 4, 5, 'straw'), (9, 2, 6, 4, 'paper'), (16, 0, 3, 5, 'news')]):
        c.cube(f'bedding_{i}', (x0, .5, z0), (x0 + w, 1, z0 + d), {'up': style, 'north': style, 'south': style, 'east': style, 'west': style}, unit=1)
    return c
