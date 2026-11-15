"""Town notice board: a wall-mounted wooden board under a small lean-to roof with three flyers, as a native Minecraft block.

Run from the repository root: <py> art/props/notice-board/build.py [--check]
Writes the textures, block/item models, blockstate and loot table under src/main/resources and the export fingerprints in this
folder's manifest.json. The frame, roof and pen ledge reuse the village wood textures of the street bench and table
(art/structures/human-world/author_furniture.py); the board and the flyers are drawn here from the shared prop palette
(art/props/palette.json). Every pixel is drawn here.

The model is drawn facing north with its back against the block to the south (the wall); the blockstate turns it by `facing`,
the direction the board faces, like a vanilla wall sign. Flyers (seen from the front): a lost yukkuri with tear-off tabs, a
badge exam notice and an adoption notice; an old pin with a torn scrap where a fourth flyer was. Collision covers the board,
ledge and the bounds of the sloped roof (human/NoticeBoardBlock).
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
ramp = lambda name: [hexrgb(c) for c in RAMPS[name]]
W0, W1, W2, W3 = ramp('wood')
P0, P1, P2, P3 = ramp('paper')
STEEL, GOLD, CHILI, NAVY, BERRY = ramp('steel'), ramp('gold'), ramp('chili'), ramp('navy'), ramp('berry')
INK, TEXT, HOLE = (0x3e, 0x3c, 0x38), (0xa8, 0xa3, 0x96), (0x30, 0x24, 0x18)

# Flyers in front view (u from the viewer's left, v from the top, px): u, v, columns, rows of the sheet.
FLYERS = {
    'lost': (2, 4, [  # lost yukkuri: red header with a brass pin, a black-hat yukkuri portrait, a line of print, tear-off tabs
        'RRpRR',
        'wKKww',
        'KKKKK',
        'wFFFw',
        'wEFEw',
        'wtwtw',
        'wcwcw']),
    'exam': (8, 4, [  # badge exam notice: navy header with a red pin, a gold badge, print
        'NNrNN',
        'wHGtw',
        'wGgwt']),
    'adopt': (8, 8, [  # adoption notice, older paper: pink header with a navy pin, a heart, a dog-eared corner
        'BBBnBBB',
        'yhhyhhy',
        'yhhhhhy',
        'yyhhhyy',
        'yyyhyyd']),
}
FLYER_COLOURS = {'R': CHILI[1], 'p': GOLD[1], 'w': P3, 'K': INK, 'F': GOLD[3], 'E': INK, 't': TEXT, 'c': P1,
                 'N': NAVY[1], 'r': CHILI[2], 'G': GOLD[2], 'H': GOLD[3], 'g': GOLD[1],
                 'B': BERRY[2], 'n': NAVY[2], 'y': P2, 'h': BERRY[1], 'd': P0}
OLD_PIN, OLD_SCRAP = (14, 4), ((13, 5), (14, 5), (14, 6))


def noise(x, y, salt=0):
    n = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    n = (n ^ (n >> 13)) * 1274126177 & 0xffffffff
    return (n >> 8) & 255


def image(fn):
    im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16): im.putpixel((x, y), fn(x, y) + (255,))
    return im


def back(u, v):
    """The board behind the flyers: dark vertical boards with seams, the roof's shadow on the top row, old pin holes,
    and the pin and torn scrap of a flyer that was pulled off."""
    if (u, v) == OLD_PIN: return STEEL[2]
    if (u, v) in OLD_SCRAP: return P1 if (u, v) != OLD_SCRAP[-1] else P0
    if v <= 3: return HOLE if noise(u, v, 1) < 90 else W0                       # under the roof
    if u % 4 == 0: return HOLE                                                   # seam between boards
    if noise(u, v, 2) < 14: return HOLE                                          # old pin holes
    if u % 4 == 1: return W1 if noise(u, v // 2, 3) < 170 else W0               # lit edge of each board
    return W1 if noise(u, v // 3, 4) < 70 else W0


def flyers(u, v):
    """The three flyers at their front-view rectangles; everything else is the paper edge seen on the sheets' thin sides."""
    for u0, v0, rows in FLYERS.values():
        if v0 <= v < v0 + len(rows) and u0 <= u < u0 + len(rows[0]):
            return FLYER_COLOURS[rows[v - v0][u - u0]]
    return P1


TEXTURES = {'back': back, 'flyers': flyers}


def tex(name): return 'mcyukkuri:block/' + name


def f(texture, uv=None):
    face = {'texture': '#' + texture}
    if uv: face['uv'] = uv
    return face


def box(a, b, faces, turn=None):
    e = {'from': list(a), 'to': list(b), 'faces': faces}
    if turn: e['rotation'] = turn
    return e


def board():
    """Faces without a uv take Minecraft's automatic uv from the element position (front faces then read in front view)."""
    sides = ('north', 'east', 'west', 'up', 'down')
    backing = box((1, 3, 15), (15, 13, 16), {'north': f('back'), 'south': f('planks')})
    posts = [box((x, 1, 14), (x + 1, 14, 16), {'north': f('post'), 'south': f('post'),
                                               'east': f('post', [0, 2, 2, 15]), 'west': f('post', [0, 2, 2, 15]),
                                               'down': f('post', [0, 14, 1, 16])}) for x in (0, 15)]
    rail = box((1, 13, 14), (15, 14, 16), {'north': f('side', [1, 0, 15, 1]), 'south': f('side'), 'down': f('side')})
    ledge = box((1, 2, 13), (15, 3, 16), {'up': f('planks'), 'north': f('side', [1, 0, 15, 1]), 'down': f('side'),
                                         'south': f('side'), 'east': f('side', [0, 0, 3, 1]), 'west': f('side', [0, 0, 3, 1])})
    # A lean-to roof hinged at the wall, its front edge dropped by 22.5 degrees over the board.
    roof = box((0, 14.5, 11), (16, 16, 16), {'up': f('planks'), 'down': f('planks'), 'north': f('side', [0, 0, 16, 1.5]),
                                            'south': f('side'), 'east': f('side', [0, 0, 5, 1.5]), 'west': f('side', [11, 0, 16, 1.5])},
               {'origin': [8, 14.5, 16], 'axis': 'x', 'angle': -22.5})
    sheets = [box((16 - u0 - len(rows[0]), 16 - v0 - len(rows), 14.75), (16 - u0, 16 - v0, 15), {s: f('flyers') for s in sides})
              for u0, v0, rows in FLYERS.values()]
    return {'parent': 'minecraft:block/block', 'textures': {'particle': tex('village_wood_planks'), 'planks': tex('village_wood_planks'),
            'side': tex('village_wood_side'), 'post': tex('village_wood_post'), 'back': tex('notice_board_back'),
            'flyers': tex('notice_board_flyers')}, 'elements': [backing, *posts, rail, ledge, roof, *sheets]}


def outputs():
    files = {}
    js = lambda d: (json.dumps(d, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    for name, painter in TEXTURES.items():
        buf = io.BytesIO(); image(painter).save(buf, format='PNG')
        files[ASSETS / f'textures/block/notice_board_{name}.png'] = buf.getvalue()
    files[ASSETS / 'models/block/notice_board.json'] = js(board())
    turn = {'north': 0, 'east': 90, 'south': 180, 'west': 270}
    files[ASSETS / 'blockstates/notice_board.json'] = js({'variants': {
        f'facing={d}': {'model': 'mcyukkuri:block/notice_board', **({'y': y} if y else {})} for d, y in turn.items()}})
    files[ASSETS / 'models/item/notice_board.json'] = js({'parent': 'mcyukkuri:block/notice_board'})
    files[RUN / 'data/mcyukkuri/loot_table/blocks/notice_board.json'] = js({'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [
        {'type': 'minecraft:item', 'name': 'mcyukkuri:notice_board'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
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
    manifest = {'authoring': 'art/props/notice-board/build.py', 'status': 'pending_user_confirmation',
                'exports': {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(d).hexdigest() for p, d in sorted(files.items())}}
    (HERE / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('Wrote', len(stale), 'of', len(files), 'files')
    return 0


if __name__ == '__main__':
    sys.exit(main())
