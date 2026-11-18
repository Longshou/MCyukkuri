"""The stray cardboard carton: a wide box lying on its side, opening toward +Z, in Minecraft block style.

The shell is 32 by 16 by 23 texels, centred on the core floor with its opening at +Z. Abutting panels omit
one of the two contact faces. Shipping marks and folds are painted directly on the panel texture.
Only the dry shell gives collision. The soaked profile sags into the empty opening. Unit 1 is bedding.
"""
from hashlib import sha256
from itertools import combinations
import json, random, uuid, zlib
from PIL import Image

PAL = {'k0': (0x78, 0x5c, 0x3f), 'k1': (0x96, 0x73, 0x4e), 'k2': (0xb1, 0x8b, 0x58), 'k3': (0xd4, 0xbc, 0x84),
       'liner': (0xa8, 0x86, 0x58), 'tape': (0xd8, 0xc2, 0x8c), 'tape_hi': (0xe6, 0xd4, 0xa4), 'label': (0xe8, 0xe0, 0xc8),
       'ink': (0x5a, 0x45, 0x32), 'red': (0x9a, 0x3c, 0x2e), 'paper': (0xee, 0xea, 0xdc), 'news': (0xc4, 0xc2, 0xb8),
       'straw': (0xc8, 0xb0, 0x70), 'straw_dk': (0xa0, 0x8a, 0x52)}
SIZE = 128
WET = ((0x5c, 0x43, 0x2c), (0x46, 0x33, 0x22))   # soaked kraft, and the tide line at a stain's edge
FLOOR = .125                                     # floor sheet top above the ground the occupants stand on


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
    base = {'outer': PAL['k2'], 'inner': PAL['liner'], 'floor': PAL['liner'],
            'paper': PAL['paper'], 'news': PAL['news'], 'straw': PAL['straw']}[style.split(':')[0]]
    for y in range(h):
        for x in range(w):
            r = rng.random()
            c = base
            if r < .10: c = tuple(v - 10 for v in base)
            elif r < .17: c = tuple(v + 8 for v in base)
            px(x, y, c)
    kind = style.split(':')[0]
    if kind in ('outer', 'inner'):
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
        head = ['.##', '###', '.##']
        for cy in (ly + 10, ly + 14):
            for x in range(lx + 3, lx + 10): px(x, cy, PAL['red'])
            for dy, row in enumerate(head):
                for dx, ch in enumerate(row):
                    if ch == '#': px(lx + 1 + dx, cy - 1 + dy, PAL['red'])
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
        """Face names map to paint styles. Unlisted faces are omitted."""
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
        faces = [(name, face, self.faces_of(lo, hi)[face])
                 for name, lo, hi, styles, _ in self.cubes for face in styles]
        rects = self.atlas.pack([(max(1, round(spec[2])), max(1, round(spec[3]))) for _, _, spec in faces])
        rect_of = {(name, face): rect for (name, face, _), rect in zip(faces, rects)}
        verts, parts, elements = [], [], []
        for name, lo, hi, styles, unit in self.cubes:
            start = len(verts) // 8
            bb_faces = {}
            specs = self.faces_of(lo, hi)
            for face, style in styles.items():
                normal, corners, _, _ = specs[face]
                rect = rect_of[(name, face)]
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
    x, back, front, top = 16, -8, 8, 23
    dry = level == 0
    c.cube('back_panel', (-x, 0, back), (x, top, back + 1),
           {'north': 'outer:tape_v', 'south': 'inner', 'up': 'outer:tape_v', 'east': 'outer', 'west': 'outer'}, solid=dry)
    wall_top = top - (4 if level == 2 else 1)
    c.cube('left_panel', (-x, 0, back + 1), (-x + 1, wall_top, front),
           {'west': 'outer:stain', 'east': 'inner', 'south': 'cut'}, solid=dry)
    c.cube('right_panel', (x - 1, 0, back + 1), (x, wall_top, front),
           {'east': 'outer:label', 'west': 'inner', 'south': 'cut'}, solid=dry)
    roof = {'up': 'outer:tape_wrap', 'down': 'inner', 'south': 'cut'}
    if level < 2:
        c.cube('top_panel', (-x, top - 1, back + 1), (x, top, front),
               roof | {'west': 'outer', 'east': 'outer'}, solid=dry)
    else:
        c.cube('left_fold', (-x, wall_top, back + 1), (-x + 1, top, front),
               {'up': 'outer', 'south': 'cut', 'west': 'outer', 'east': 'inner'})
        c.cube('right_fold', (x - 1, wall_top, back + 1), (x, top, front),
               {'up': 'outer', 'south': 'cut', 'east': 'outer', 'west': 'inner'})
        c.cube('sagged_top', (-x + 1, top - 4, back + 1), (x - 1, top - 3, front - 1),
               {'up': 'outer:tape_wrap', 'down': 'inner'})
        c.cube('hanging_lip', (-x + 1, top - 8, front - 1), (x - 1, top - 3, front),
               {'north': 'inner', 'south': 'outer:stain', 'up': 'outer', 'down': 'cut'})
    # Bodies stand on the ground under the box, so the floor is a sheet lying on it and the bedding scraps lie flat on the
    # sheet: each is 1/8 texel (1/128 block) above the face below, enough to hold the depth test and too little to cover a
    # baby's 1.7-texel body (redstone dust lies 1/4 texel up).
    c.cube('bottom_panel', (-x + 1, 0, back + 1), (x - 1, FLOOR, front), {'up': 'floor', 'south': 'cut'})
    for i, (x0, z0, w, d, style) in enumerate([(-13, -6, 5, 3, 'news'), (-6, -4, 5, 3, 'straw'),
                                               (1, -6, 5, 3, 'paper'), (8, -5, 5, 3, 'straw'),
                                               (-11, 2, 6, 4, 'news'), (3, 1, 4, 5, 'straw')]):
        c.cube(f'bedding_{i}', (x0, FLOOR, z0), (x0 + w, 2 * FLOOR, z0 + d),
               {'up': style, 'north': style, 'south': style, 'east': style, 'west': style}, unit=1)
    return c


def check(root):
    """Check the three generated shapes, texture pixels, collision and copies against the authoring source."""
    source = root / 'standards/yukkuri-library/ecology/wild-shelters'
    snapshot = root / 'art/props/wild-shelters'
    runtime = root / 'src/main/resources'
    assets = runtime / 'assets/mcyukkuri'
    manifest = json.loads((source / 'manifest.json').read_bytes())['exports']
    index = json.loads((assets / 'yukkuri_props/shelters-index.json').read_bytes())
    errors = []

    def expect(ok, path):
        if not ok:
            errors.append(str(path))

    for level in range(3):
        carton = build(level)
        mesh, elements, image = carton.export()
        name = carton.name
        expect(mesh['bounds'] == [[-16, 16], [0, 23], [-8, 8]], name + ' dimensions')
        faces = []
        for part, low, high, styles, _ in carton.cubes:
            for face in styles:
                normal, corners, _, _ = carton.faces_of(low, high)[face]
                axis = next(i for i, n in enumerate(normal) if n)
                faces.append((axis, corners[0][axis], part + '/' + face, low, high))
        for (axis, plane, part, low, high), (other_axis, other_plane, other, start, end) in combinations(faces, 2):
            if axis == other_axis and plane == other_plane:
                expect(not all(max(low[i], start[i]) < min(high[i], end[i]) for i in range(3) if i != axis),
                       f'{name} overlapping faces {part} and {other}')
        expect(name in index, 'shelters-index.json: ' + name)
        for suffix, key, directory in (('json', 'mesh', assets / 'yukkuri_props'),
                                       ('bbmodel', 'editable', None), ('png', 'texture', assets / 'textures/props')):
            file = f'{name}.{suffix}'
            data = (source / 'assembled' / file).read_bytes()
            expect(sha256(data).hexdigest() == manifest[name][key + '_sha256'], file + ' fingerprint')
            expect(data == (snapshot / file).read_bytes(), snapshot / file)
            if directory is not None:
                expect(data == (directory / file).read_bytes(), directory / file)
            if suffix == 'json':
                expect(json.loads(data) == mesh, file)
            elif suffix == 'bbmodel':
                expect(json.loads(data)['elements'] == elements, file)
            else:
                with Image.open(source / 'assembled' / file) as saved:
                    expect(saved.size == image.size and saved.convert('RGBA').tobytes() == image.tobytes(), file)
        if level == 0:
            collision = runtime / 'data/mcyukkuri/shelters/cardboard_shelter.json'
            expect(json.loads(collision.read_bytes()) == {'units_per_block': 16, 'collision': carton.collision}, collision)
    if errors:
        raise SystemExit('Carton exports differ: ' + ', '.join(errors))
    print('Carton exports match all three stages and dry collision.')
