"""Original wooden furniture for streets, shops and houses: a joining bench, a joining table and an open produce crate as native
block models, each in the four woods of the village palettes (block property `wood`, human/VillageWood).

Both pieces join neighbours of the same kind (block properties north/east/south/west, see human/JoinedFurnitureBlock): a run of benches
or a group of tables reads as one piece with legs only at its outer ends and corners. The bench is a backless village bench (seat top
at 8/16, the same height as a slab, so the street rest spot keeps its climb); a single bench is a stool. The table top is at 16/16.

The crate (human/ProduceCrateBlock) is a slatted box on four corner posts, 9/16 high, its front (model north) one slat lower. The goods
it holds are drawn by client/ProduceCrateRenderer from its real contents, standing on the floor board at 2/16.

Light from the top left, every pixel drawn here (no vanilla or external pixels). Oak is the shared `wood` ramp of
art/props/palette.json and keeps the original texture and model names; spruce, acacia and jungle follow the tone of those vanilla planks.
Sources go to products/, runtime copies to textures/block/; models, blockstates, loot tables, recipes and language keys are written
next to them. Run through `<py> scripts/export.py --family human-world` (scripts/export_human_world.py).
"""
from pathlib import Path
import io, json, hashlib, sys
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from PIL import Image
from asset_export_checks import preserve_equivalent_png
ART = Path(__file__).parent / 'products'
ASSET = ROOT / 'src/main/resources/assets/mcyukkuri'
DATA = ROOT / 'src/main/resources/data/mcyukkuri'
rgb = lambda h: tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
# Four steps per wood, dark to light, in the order of the `wood` property (oak first: the default, and the look of old worlds).
WOODS = {
    'oak': json.loads((ROOT / 'art/props/palette.json').read_text(encoding='utf-8'))['ramps']['wood'],
    'spruce': ['#35271a', '#523c26', '#705335', '#886846'],
    'acacia': ['#5c2c1c', '#87452a', '#ab5c34', '#c47a4c'],
    'jungle': ['#523527', '#7a513a', '#a37355', '#bd8f70'],
}
GRIME = (0x3b, 0x2e, 0x22)
NAMES = {'wooden_bench': ('长椅', 'Wooden Bench', 'ベンチ'), 'wooden_table': ('木桌', 'Wooden Table', '木のテーブル'),
         'produce_crate': ('货箱', 'Produce Crate', '野菜箱')}


def noise(x, y, salt=0):
    n = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    n = (n ^ (n >> 13)) * 1274126177 & 0xffffffff
    return (n >> 8) & 255


def image(fn, wood):
    ramp = [rgb(c) for c in WOODS[wood]]
    im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16): im.putpixel((x, y), fn(x, y, *ramp) + (255,))
    return im


def planks(x, y, W0, W1, W2, W3):
    """Top boards 4 px wide running along u: a lit upper edge, grain streaks, a dark seam, one butt joint per board."""
    row, board = y % 4, y // 4
    joint = (3 + board * 5) % 16
    if row == 3: return W0                                   # seam between boards
    if x == joint: return W0 if row else W1                  # butt joint
    if x == (joint + 1) % 16: return W3 if row == 0 else W2  # lit end of the next board
    if row == 0: return W3 if noise(x, y, 1) < 200 else W2
    streak = noise(x // 3, y, 2 + board) < 70                # grain runs in short streaks along the board
    if streak: return W1
    return W2 if noise(x, y, 3) < 220 else W3


def side(x, y, W0, W1, W2, W3):
    """Edge grain for board edges, rails and aprons: 4 px bands with a lit top row and a shaded bottom row."""
    row = y % 4
    if row == 0: return W3
    if row == 3: return W1
    return W1 if noise(x // 2, y, 5) < 60 else W2


def post(x, y, W0, W1, W2, W3):
    """Legs: vertical grain, lit left column, shaded right column of each 2 px leg, street grime on the bottom row."""
    if y == 15: return GRIME if noise(x, y, 7) < 150 else W0
    col = x % 2
    if col == 0: return W3 if noise(x, y // 2, 8) < 210 else W2
    return W1 if noise(x, y // 3, 9) < 120 else W2


TEXTURES = {'planks': planks, 'side': side, 'post': post}


def tex(name): return 'mcyukkuri:block/' + name


def suffix(wood): return '' if wood == 'oak' else '_' + wood


def texture_name(part, wood): return f'village_wood{suffix(wood)}_{part}'


def textures(wood='oak'):
    """The texture slots every model here uses, in one wood."""
    return {'particle': tex(texture_name('planks', wood)), **{part: tex(texture_name(part, wood)) for part in TEXTURES}}


def in_wood(model, wood):
    """The model `block/<model>` in a wood: oak is the drawn model itself, the others are children that swap its textures."""
    return {'parent': tex(model), 'textures': textures(wood)}


def box(a, b, faces):
    return {'from': list(a), 'to': list(b), 'faces': faces}


def faces(a, b, top='planks', edge='side', bottom='side', v_top=0):
    """All six faces of a board from a to b. Side faces sample edge rows from v_top, so a 2-3 px edge shows the lit row on top."""
    (x0, y0, z0), (x1, y1, z1) = a, b
    h = y1 - y0
    return {'up': {'texture': '#' + top, 'uv': [x0, z0, x1, z1]}, 'down': {'texture': '#' + bottom, 'uv': [x0, z0, x1, z1]},
            'north': {'texture': '#' + edge, 'uv': [16 - x1, v_top, 16 - x0, v_top + h]},
            'south': {'texture': '#' + edge, 'uv': [x0, v_top, x1, v_top + h]},
            'west': {'texture': '#' + edge, 'uv': [z0, v_top, z1, v_top + h]},
            'east': {'texture': '#' + edge, 'uv': [16 - z1, v_top, 16 - z0, v_top + h]}}


def leg(x0, z0, height):
    """A 2x2 px leg standing on the floor: post grain, grime row at the foot."""
    a, b, u = (x0, 0, z0), (x0 + 2, height, z0 + 2), (x0 - x0 % 2) % 14      # an even column: lit left, shaded right
    f = {d: {'texture': '#post', 'uv': [u, 16 - height, u + 2, 16]} for d in ('north', 'south', 'east', 'west')}
    f['up'] = {'texture': '#side', 'uv': [0, 0, 2, 2]}
    f['down'] = {'texture': '#post', 'uv': [0, 14, 2, 16]}
    return box(a, b, f)


def rail(a, b):
    """A rail or bar: edge grain all round."""
    return box(a, b, faces(a, b, top='side', edge='side', bottom='side'))


# ---------------------------------------------------------------------------------------------------------- bench
SEAT_Y = (5, 8)          # seat board, 3/16 thick; its top is 8/16 (slab height)
SEAT_Z = (1, 15)         # 14/16 deep: an adult's four soles (0.42 x width from the centre) stay on it up to a 1.04-wide body


def trestle(x):
    """Two legs across the seat with a crossbar between them, at model x..x+2."""
    return [leg(x, 2, SEAT_Y[0]), leg(x, 12, SEAT_Y[0]), rail((x, 2, 4), (x + 2, 4, 12))]


def bench(west, east):
    """Model along x. west/east: joined to the neighbouring bench on that side."""
    x0, x1 = (0 if west else 1), (16 if east else 15)
    seat = box((x0, SEAT_Y[0], SEAT_Z[0]), (x1, SEAT_Y[1], SEAT_Z[1]),
               faces((x0, SEAT_Y[0], SEAT_Z[0]), (x1, SEAT_Y[1], SEAT_Z[1]), v_top=0))
    parts = [seat]
    legs = ([2] if not west else []) + ([12] if not east else []) + ([7] if west and east else [])
    for x in legs: parts += trestle(x)
    # Stretcher under the seat centre from trestle to trestle (or to the joined neighbour's stretcher).
    anchors = sorted([(0, 0)] * west + [(16, 16)] * east + [(x, x + 2) for x in legs])
    for (_, a), (b, _) in zip(anchors, anchors[1:]):
        if b > a: parts.append(rail((a, 2, 7), (b, 4, 9)))
    return {'parent': 'minecraft:block/block', 'textures': textures(), 'elements': parts}


BENCH_MODELS = {(False, False): 'single', (False, True): 'west_end', (True, True): 'middle', (True, False): 'east_end'}


def bench_blockstate():
    """Axis x uses the model as drawn; axis z turns it 90°, so the model's west end becomes the north end."""
    variants = {}
    for wood in WOODS:
        for (west, east), name in BENCH_MODELS.items():
            model = tex('wooden_bench_' + name + suffix(wood))
            variants[f'axis=x,east={str(east).lower()},west={str(west).lower()},wood={wood}'] = {'model': model}
            variants[f'axis=z,north={str(west).lower()},south={str(east).lower()},wood={wood}'] = {'model': model, 'y': 90}
    return {'variants': variants}


# ---------------------------------------------------------------------------------------------------------- table
TOP_Y = (13, 16)
APRON_Y = (10, 13)


def table_part(name, elements):
    return name, {'parent': 'minecraft:block/block', 'textures': textures(), 'elements': elements}


def table_parts():
    """Pieces drawn for the north-west quarter; the blockstate turns them to the other sides and corners."""
    a, b = (0, TOP_Y[0], 0), (16, TOP_Y[1], 16)
    return dict([
        table_part('top', [box(a, b, faces(a, b, v_top=0))]),
        table_part('leg', [leg(1, 1, TOP_Y[0])]),
        table_part('apron', [rail((3, APRON_Y[0], 1), (13, APRON_Y[1], 2))]),            # north side between the corner legs
        table_part('apron_west', [rail((0, APRON_Y[0], 1), (3, APRON_Y[1], 2))]),       # ...continued west into a joined table
        table_part('apron_east', [rail((13, APRON_Y[0], 1), (16, APRON_Y[1], 2))]),     # ...continued east into a joined table
    ])


SIDES = ['north', 'east', 'south', 'west']        # clockwise; the model's north turned by y = 90 * index


def table_blockstate():
    parts = []
    for wood in WOODS:
        piece = lambda name: tex('wooden_table_' + name + suffix(wood))
        parts.append({'when': {'wood': wood}, 'apply': {'model': piece('top')}})
        for i, side in enumerate(SIDES):
            left, right = SIDES[(i - 1) % 4], SIDES[(i + 1) % 4]       # the model's west and east, seen from this side
            turn = {'y': 90 * i} if i else {}
            parts.append({'when': {'wood': wood, side: 'false', left: 'false'}, 'apply': {'model': piece('leg'), **turn}})
            parts.append({'when': {'wood': wood, side: 'false'}, 'apply': {'model': piece('apron'), **turn}})
            parts.append({'when': {'wood': wood, side: 'false', left: 'true'}, 'apply': {'model': piece('apron_west'), **turn}})
            parts.append({'when': {'wood': wood, side: 'false', right: 'true'}, 'apply': {'model': piece('apron_east'), **turn}})
    return {'multipart': parts}


def table_item():
    """The lone table: top, four legs and four aprons in one model (multipart has no item form)."""
    parts = table_parts()
    elements = list(parts['top']['elements'])
    for i in range(4):
        for piece in ('leg', 'apron'):
            elements += [turned(e, i) for e in parts[piece]['elements']]
    return {'parent': 'minecraft:block/block', 'textures': parts['top']['textures'], 'elements': elements}


def turned(element, quarter):
    """An element turned clockwise (seen from above) by quarter * 90° about the block centre, as a blockstate y rotation does."""
    e = json.loads(json.dumps(element))
    for _ in range(quarter):
        (x0, y0, z0), (x1, y1, z1) = e['from'], e['to']
        e['from'], e['to'] = [16 - z1, y0, x0], [16 - z0, y1, x1]
        f = e['faces']
        e['faces'] = {'east': f['north'], 'south': f['east'], 'west': f['south'], 'north': f['west'], 'up': f['up'], 'down': f['down']}
    return e


# ---------------------------------------------------------------------------------------------------------- crate
CRATE_TOP = 9            # corner posts; the slats end one pixel lower
CRATE_FLOOR = 2          # top of the floor board: the goods stand here (client/ProduceCrateRenderer)


def crate():
    """Front at model north (the blockstate turns it to `facing`). Posts stand proud of the slats by one pixel; the floor board is
    lifted off the counter by the posts' feet. Sides and back carry two slats with a gap, the front only the lower one."""
    parts = [box((2, 1, 2), (14, CRATE_FLOOR, 14), faces((2, 1, 2), (14, CRATE_FLOOR, 14)))]
    parts += [leg(x, z, CRATE_TOP) for x in (0, 14) for z in (0, 14)]
    for y in (1, 5):
        parts += [rail((1, y, 2), (2, y + 3, 14)), rail((14, y, 2), (15, y + 3, 14)), rail((2, y, 14), (14, y + 3, 15))]
    parts.append(rail((2, 1, 1), (14, 4, 2)))
    return {'parent': 'minecraft:block/block', 'textures': textures(), 'elements': parts}


def crate_blockstate():
    turns = {'north': 0, 'east': 90, 'south': 180, 'west': 270}
    return {'variants': {f'facing={facing},wood={wood}': {'model': tex('produce_crate' + suffix(wood)), **({'y': turn} if turn else {})}
                         for wood in WOODS for facing, turn in turns.items()}}


# ---------------------------------------------------------------------------------------------------------- export
def language(lang):
    i = ['zh_cn', 'en_us', 'ja_jp'].index(lang)
    return {prefix + 'mcyukkuri.' + id: tri[i] for id, tri in NAMES.items() for prefix in ('item.', 'block.')}


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def write_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.is_file() or path.read_bytes() != data: path.write_bytes(data)


def write_png(path, im):
    stream = io.BytesIO(); im.save(stream, format='PNG')
    data = preserve_equivalent_png(path, stream.getvalue()); write_bytes(path, data); return data


def export():
    records = {}
    for wood in WOODS:
        for part, painter in TEXTURES.items():
            name = texture_name(part, wood)
            source = ART / (name + '.png'); data = write_png(source, image(painter, wood))
            target = ASSET / 'textures/block' / source.name; write_bytes(target, data)
            records[name] = {'source': source.relative_to(ROOT).as_posix(), 'target': target.relative_to(ROOT).as_posix(),
                             'sha256': hashlib.sha256(data).hexdigest()}
    models = ASSET / 'models'
    drawn = {'wooden_bench_' + name: bench(west, east) for (west, east), name in BENCH_MODELS.items()}
    drawn.update({'wooden_table_' + name: model for name, model in table_parts().items()})
    drawn['produce_crate'] = crate()
    for name, model in drawn.items():
        write(models / f'block/{name}.json', model)
        for wood in list(WOODS)[1:]: write(models / f'block/{name}_{wood}.json', in_wood(name, wood))
    write(ASSET / 'blockstates/wooden_bench.json', bench_blockstate())
    write(models / 'item/wooden_bench.json', {'parent': tex('wooden_bench_single')})
    write(models / 'block/wooden_table.json', table_item())
    write(ASSET / 'blockstates/wooden_table.json', table_blockstate())
    write(models / 'item/wooden_table.json', {'parent': tex('wooden_table')})
    write(ASSET / 'blockstates/produce_crate.json', crate_blockstate())
    write(models / 'item/produce_crate.json', {'parent': tex('produce_crate')})
    for name in NAMES:
        write(DATA / 'loot_table/blocks' / f'{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [
            {'type': 'minecraft:item', 'name': 'mcyukkuri:' + name}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    stick = {'item': 'minecraft:stick'}
    write(DATA / 'recipe/wooden_bench.json', {'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': ['sss', 't t'],
          'key': {'s': {'tag': 'minecraft:wooden_slabs'}, 't': stick}, 'result': {'id': 'mcyukkuri:wooden_bench', 'count': 2}})
    write(DATA / 'recipe/wooden_table.json', {'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': ['ppp', 't t'],
          'key': {'p': {'tag': 'minecraft:planks'}, 't': stick}, 'result': {'id': 'mcyukkuri:wooden_table', 'count': 1}})
    write(DATA / 'recipe/produce_crate.json', {'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': ['s s', 'sss'],
          'key': {'s': {'tag': 'minecraft:wooden_slabs'}}, 'result': {'id': 'mcyukkuri:produce_crate', 'count': 2}})
    write(ROOT / 'src/main/resources/data/minecraft/tags/block/mineable/axe.json',
          {'replace': False, 'values': ['mcyukkuri:' + name for name in (*NAMES, 'notice_board')]})
    write(ART / 'furniture-manifest.json', {'status': 'pending_user_confirmation', 'author': 'Original procedural pixel art; no external source pixels.',
        'geometry': 'bench: seat 5..8/16 high, 1..15 deep, trestles at the open ends (and mid-span in a middle piece); '
                    'table: top 13..16/16, 2 px legs at open corners, aprons on open sides; both join same-kind neighbours of the '
                    'same wood; crate: posts 9/16 high, floor board top at 2/16, two slats on the sides and back, one on the front',
        'woods': WOODS,
        'models': sorted(p.relative_to(ROOT).as_posix() for pattern in ('block/wooden_*.json', 'block/produce_crate*.json')
                         for p in models.glob(pattern)), 'assets': records})
    return records


if __name__ == '__main__': export()
