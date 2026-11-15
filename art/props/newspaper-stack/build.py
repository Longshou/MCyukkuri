"""Newspaper stack: one to three tied bundles of folded newspapers as a native Minecraft block (elements, 16 px textures).

Run from the repository root: <py> art/props/newspaper-stack/build.py [--check]
Writes the textures, block/item models, blockstate and loot table under src/main/resources and the export fingerprints in this
folder's manifest.json. Colours are the shared prop palette (art/props/palette.json: paper, straw for the twine, chili, sky,
berry and gold for the masthead seal and the advert inserts). Every pixel is drawn here.

A bundle is 10 x 4 x 8 px (x, y, z), tied crosswise with twine and a knot on top. Bundle 1 lies square in the cell, bundle 2 sits
on it turned 22.5 degrees, bundle 3 on top a little off-centre; the blockstate turns the whole stack by `facing`. The collision is
one box (human/NewspaperStackBlock) 10 x 4n x 10 px around the bundles, the same at every facing.
"""
from pathlib import Path
import hashlib, io, json, sys
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUN = ROOT / 'src/main/resources'
ASSETS = RUN / 'assets/mcyukkuri'
RAMPS = json.loads((ROOT / 'art/props/palette.json').read_text(encoding='utf-8'))['ramps']
hexrgb = lambda h: tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
P0, P1, P2, P3 = (hexrgb(c) for c in RAMPS['paper'])
T0, T1, T2, T3 = (hexrgb(c) for c in RAMPS['straw'])
RED, SKY, BERRY, GOLD = hexrgb(RAMPS['chili'][1]), hexrgb(RAMPS['sky'][1]), hexrgb(RAMPS['berry'][2]), hexrgb(RAMPS['gold'][2])
INK, TEXT, PRINT = (0x4a, 0x48, 0x43), (0xb4, 0xb0, 0xa2), (0xc6, 0xc1, 0xb2)
PHOTO, PHOTO_DARK = (0xa6, 0xa2, 0x94), (0x7d, 0x7a, 0x70)

BUNDLE = (10, 4, 8)                       # x, y, z size in px
# (centre x, centre z, y rotation in degrees, flip the printed page) per bundle, bottom first.
LAYOUT = [(8, 8, 0, False), (8.5, 7.5, 22.5, True), (7.5, 8.5, 0, False)]
TIE_X = 2                                 # the lengthwise string sits this far east of the bundle centre


def noise(x, y, salt=0):
    n = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    n = (n ^ (n >> 13)) * 1274126177 & 0xffffffff
    return (n >> 8) & 255


def image(fn):
    im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16): im.putpixel((x, y), fn(x, y) + (255,))
    return im


PAGE = [  # the front page of the top paper, 10 x 8 px: masthead and red seal, headline, photo, columns of small print, fold
    'MMMMM..RR.',
    '..........',
    'HHHHHH.HH.',
    'ppQp.t.tt.',
    'pQQp.tt.t.',
    'pppp.t.tt.',
    '.tt.t.tt..',
    'ffffffffff']
PAGE_COLOURS = {'M': INK, 'R': RED, 'H': PHOTO_DARK, 'p': PHOTO, 'Q': PHOTO_DARK, 't': TEXT, '.': P3, 'f': P2}


def top(x, y):
    """The page sits at u 3..13 / v 4..12, where the bundle's top face samples it."""
    i, j = x - 3, y - 4
    return PAGE_COLOURS[PAGE[j][i]] if 0 <= i < 10 and 0 <= j < 8 else P2


def fold(x, y):
    """The folded long side: pale rounded folds, a faint crease here and there, the bottom row of each bundle in shade."""
    row = y % 4
    if row == 3: return P1
    if noise(x, y, 4) < 30: return P2
    return P3 if row != 2 else P2


def edge(x, y):
    """Cut page edges (open long side and the ends): thin pale sheets with a darker line between bundles of pages, a few grey
    print edges and two coloured advert inserts sticking out."""
    row = y % 4
    inserts = {(4, 1): SKY, (5, 1): SKY, (11, 6): BERRY, (8, 13): GOLD, (9, 13): GOLD}
    if (x, y) in inserts: return inserts[(x, y)]
    if row == 3: return P1
    if noise(x, y, 5) < 25: return PRINT
    return P3 if row == 0 else P2


def twine(x, y):
    """Twisted string: a lit strand and a shaded strand winding along it, so it reads the same lengthwise and crosswise."""
    k = (x + y) % 3
    return T2 if k == 0 else T1 if k == 1 else (T3 if noise(x, y, 6) < 90 else T2)


TEXTURES = {'top': top, 'fold': fold, 'edge': edge, 'twine': twine}


def tex(name): return f'mcyukkuri:block/newspaper_stack_{name}'


def f(texture, uv, rotation=0, cull=None):
    face = {'texture': '#' + texture, 'uv': [round(v, 4) for v in uv]}
    if rotation: face['rotation'] = rotation
    if cull: face['cullface'] = cull
    return face


def box(a, b, faces, turn=None):
    e = {'from': [round(v, 4) for v in a], 'to': [round(v, 4) for v in b], 'faces': faces}
    if turn: e['rotation'] = turn
    return e


def bundle(index, cx, cz, angle, flip):
    """One tied bundle: the paper block, a string around it lengthwise and crosswise, and the knot where they cross."""
    w, h, d = BUNDLE
    x0, x1, z0, z1, y0, y1 = cx - w / 2, cx + w / 2, cz - d / 2, cz + d / 2, index * h, index * h + h
    turn = {'origin': [cx, y0, cz], 'axis': 'y', 'angle': angle} if angle else None
    v = 4 * index                                                    # each bundle shows its own rows of the side textures
    body = box((x0, y0, z0), (x1, y1, z1), {
        'up': f('top', [3, 4, 13, 12], 180 if flip else 0),
        'down': f('fold', [3, 4, 13, 12], cull='down' if index == 0 and not angle else None),
        'south': f('fold', [3, v, 13, v + 4]), 'north': f('edge', [3, v, 13, v + 4]),
        'east': f('edge', [4, v, 12, v + 4]), 'west': f('edge', [2, v, 10, v + 4])}, turn)
    sx = cx + TIE_X
    across = box((x0 - .1, y0, cz - .5), (x1 + .1, y1 + .1, cz + .5), {
        'up': f('twine', [0, 0, 10.2, 1]), 'east': f('twine', [0, 0, 1, 4.1]), 'west': f('twine', [1, 0, 2, 4.1]),
        'north': f('twine', [0, 2, 10.2, 2.1]), 'south': f('twine', [0, 3, 10.2, 3.1])}, turn)
    along = box((sx - .5, y0, z0 - .1), (sx + .5, y1 + .15, z1 + .1), {
        'up': f('twine', [4, 0, 5, 8.2]), 'north': f('twine', [5, 0, 6, 4.15]), 'south': f('twine', [6, 0, 7, 4.15]),
        'east': f('twine', [0, 5, 8.2, 5.15]), 'west': f('twine', [0, 6, 8.2, 6.15])}, turn)
    knot = box((sx - .75, y1 + .15, cz - .75), (sx + .75, y1 + .55, cz + .75), {
        'up': f('twine', [8, 8, 9.5, 9.5]), **{s: f('twine', [8, 10, 9.5, 10.4]) for s in ('north', 'south', 'east', 'west')}}, turn)
    return [body, across, along, knot]


def stack(count):
    elements = [e for i, layout in enumerate(LAYOUT[:count]) for e in bundle(i, *layout)]
    return {'parent': 'minecraft:block/block', 'textures': {k: tex(k) for k in TEXTURES} | {'particle': tex('edge')},
            'elements': elements}


def outputs():
    files = {}
    js = lambda d: (json.dumps(d, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    for name, painter in TEXTURES.items():
        buf = io.BytesIO(); image(painter).save(buf, format='PNG')
        files[ASSETS / f'textures/block/newspaper_stack_{name}.png'] = buf.getvalue()
    for n in (1, 2, 3):
        files[ASSETS / f'models/block/newspaper_stack_{n}.json'] = js(stack(n))
    turn = {'north': 0, 'east': 90, 'south': 180, 'west': 270}
    files[ASSETS / 'blockstates/newspaper_stack.json'] = js({'variants': {
        f'bundles={n},facing={d}': {'model': f'mcyukkuri:block/newspaper_stack_{n}', **({'y': y} if y else {})}
        for n in (1, 2, 3) for d, y in turn.items()}})
    files[ASSETS / 'models/item/newspaper_stack.json'] = js({'parent': 'mcyukkuri:block/newspaper_stack_2'})
    counts = [{'function': 'minecraft:set_count', 'count': n, 'add': False, 'conditions': [{'condition': 'minecraft:block_state_property',
               'block': 'mcyukkuri:newspaper_stack', 'properties': {'bundles': str(n)}}]} for n in (2, 3)]
    files[RUN / 'data/mcyukkuri/loot_table/blocks/newspaper_stack.json'] = js({'type': 'minecraft:block', 'pools': [{'rolls': 1,
        'bonus_rolls': 0, 'entries': [{'type': 'minecraft:item', 'name': 'mcyukkuri:newspaper_stack',
        'functions': [*counts, {'function': 'minecraft:explosion_decay'}]}]}]})
    return files


def main():
    check = '--check' in sys.argv
    files = outputs()
    stale = [p for p, data in files.items() if not p.exists() or p.read_bytes() != data]
    if check:
        for p in stale: print('STALE', p.relative_to(ROOT))
        return 1 if stale else 0
    for p in stale:
        p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(files[p])
    manifest = {'authoring': 'art/props/newspaper-stack/build.py', 'status': 'pending_user_confirmation',
                'exports': {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(d).hexdigest() for p, d in sorted(files.items())}}
    (HERE / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('Wrote', len(stale), 'of', len(files), 'files')
    return 0


if __name__ == '__main__':
    sys.exit(main())
