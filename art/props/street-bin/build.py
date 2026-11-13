"""Street garbage bin: a galvanized lidded can as a native Minecraft block (elements, 16 px textures), upright or knocked over.

Run from the repository root: <py> art/props/street-bin/build.py [--check]
Writes the textures, block/item models, blockstate, loot table and recipe under src/main/resources and the snapshot
fingerprints in this folder's manifest.json. Colours are the shared care-prop palette (art/props/palette.json, ramp steel).
The tipped model lies with its mouth toward +Z (south): the blockstate turns it to the side it fell.
"""
from pathlib import Path
import hashlib, io, json, random, sys
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUN = ROOT / 'src/main/resources'
ASSETS = RUN / 'assets/mcyukkuri'
PALETTE = json.loads((ROOT / 'art/props/palette.json').read_text(encoding='utf-8'))['ramps']
hexrgb = lambda h: tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
S0, S1, S2, S3 = (hexrgb(c) for c in PALETTE['steel'])
DIRT, RUST = (0x6e, 0x60, 0x4c), (0x8a, 0x5e, 0x3c)
DARK, DARKER = (0x34, 0x33, 0x30), (0x24, 0x23, 0x21)
PAPER, NEWS, APPLE, PEEL, GLASS = (0xe8, 0xe4, 0xd8), (0xbc, 0xba, 0xb0), (0xa8, 0x3c, 0x2a), (0xd6, 0xbe, 0x48), (0x4e, 0x86, 0x52)


def image(painter, seed):
    img = Image.new('RGBA', (16, 16)); rng = random.Random(seed)
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), painter(x, y, rng) + (255,))
    return img


def side(x, y, rng):
    """Vertical corrugation of a galvanized can, a little street dirt toward the foot."""
    c = (S3, S2, S2, S1)[x % 4]
    if rng.random() < .03: c = S1 if c != S1 else S2
    if y >= 14 and rng.random() < .3: c = DIRT
    if (x, y) in ((6, 9), (7, 10)): c = RUST
    return c


def band(x, y, rng):
    """Rolled rib and lid rim: darker steel, a highlight row."""
    return S2 if y % 4 == 0 else (S1 if rng.random() < .92 else S0)


def lid(x, y, rng):
    """Lid seen from above: rim, a pressed ring, the centre under the handle."""
    d = max(abs(x - 7.5), abs(y - 7.5))
    if d > 6.5: c = S1
    elif d > 5.5: c = S3
    elif d > 4.5: c = S1
    elif d > 1.5: c = S2
    else: c = S1
    return S0 if rng.random() < .05 else c


def bottom(x, y, rng):
    d = max(abs(x - 7.5), abs(y - 7.5))
    return S1 if d > 4.5 else S0 if rng.random() < .3 else S1


def mouth(x, y, rng):
    """The open end of a fallen can: its rim around a dark inside with rubbish showing."""
    if x in (3, 12) or y in (3, 12): return S2
    c = DARK if (x + y) % 3 else DARKER
    bits = {(5, 9): PAPER, (6, 9): PAPER, (6, 10): PAPER, (5, 10): NEWS, (9, 10): APPLE, (10, 10): APPLE,
            (8, 6): PEEL, (9, 7): PEEL, (4, 6): GLASS, (4, 7): GLASS, (10, 5): NEWS, (11, 5): PAPER}
    return bits.get((x, y), c)


def litter(x, y, rng):
    """Crumpled paper and newsprint spilled at the mouth."""
    if x < 8: return PAPER if rng.random() < .8 else NEWS
    return NEWS if (y % 2 or rng.random() < .6) else PAPER


TEXTURES = {'side': (side, 11), 'band': (band, 12), 'lid': (lid, 13), 'bottom': (bottom, 14), 'mouth': (mouth, 15), 'litter': (litter, 16)}


def tex(name):
    return f'mcyukkuri:block/garbage_bin_{name}'


def cube(frm, to, faces, rotation=None):
    out = {'from': frm, 'to': to, 'faces': faces}
    if rotation: out['rotation'] = rotation
    return out


def f(texture, uv, rotation=0, cull=None):
    face = {'texture': '#' + texture, 'uv': uv}
    if rotation: face['rotation'] = rotation
    if cull: face['cullface'] = cull
    return face


def upright():
    body = cube([3, 0, 3], [13, 12, 13], {d: f('side', [3, 4, 13, 16]) for d in ('north', 'south', 'east', 'west')}
                | {'down': f('bottom', [3, 3, 13, 13], cull='down')})
    ribs = [cube([2, y, 2], [14, y + 1, 14], {d: f('band', [2, 0, 14, 1]) for d in ('north', 'south', 'east', 'west')}
                 | {'up': f('band', [2, 2, 14, 14]), 'down': f('band', [2, 2, 14, 14])}) for y in (2, 8)]
    cover = cube([2, 12, 2], [14, 14, 14], {d: f('band', [2, 0, 14, 2]) for d in ('north', 'south', 'east', 'west')}
                 | {'up': f('lid', [2, 2, 14, 14]), 'down': f('band', [2, 2, 14, 14])})
    handle = cube([6, 14, 7], [10, 15, 9], {d: f('band', [6, 0, 10, 1]) for d in ('north', 'south')}
                  | {d: f('band', [7, 0, 9, 1]) for d in ('east', 'west')} | {'up': f('band', [6, 7, 10, 9])})
    return {'parent': 'minecraft:block/block', 'textures': {k: tex(k) for k in ('side', 'band', 'lid', 'bottom')} | {'particle': tex('side')},
            'elements': [body, *ribs, cover, handle]}


def tipped():
    # The can lies along Z, its base at z=1 and its open end at z=13; the corrugation runs along the can, so side faces turn 90°.
    body = cube([3, 0, 1], [13, 10, 13], {'up': f('side', [3, 1, 13, 13]), 'down': f('side', [3, 1, 13, 13], cull='down'),
                                          'east': f('side', [3, 2, 13, 14], 90), 'west': f('side', [3, 2, 13, 14], 90),
                                          'north': f('bottom', [3, 3, 13, 13]), 'south': f('mouth', [3, 3, 13, 13])})
    ribs = [cube([2, 0, z], [14, 11, z + 1], {d: f('band', [2, 0, 14, 1]) for d in ('up', 'down')}
                 | {d: f('band', [0, 5, 11, 6], 90) for d in ('east', 'west')}
                 | {d: f('band', [2, 2, 14, 13]) for d in ('north', 'south')}) for z in (3, 9)]
    # The lid came off and stands on its rim against the can.
    cover = cube([14, 0, 2], [16, 12, 14], {'east': f('lid', [2, 2, 14, 14]), 'west': f('band', [2, 2, 14, 14]),
                                            'north': f('band', [0, 2, 2, 14]), 'south': f('band', [0, 2, 2, 14]),
                                            'up': f('band', [2, 0, 14, 2], 90), 'down': f('band', [2, 0, 14, 2], 90, 'down')})
    spill = [cube([5, 0, 13], [8, 1.5, 15.5], {d: f('litter', [0, 0, 3, 2]) for d in ('north', 'south', 'east', 'west')}
                  | {'up': f('litter', [0, 4, 3, 6.5])}),
             cube([9, 0, 13.5], [11.5, 1, 16], {d: f('litter', [9, 0, 11.5, 1]) for d in ('north', 'south', 'east', 'west')}
                  | {'up': f('litter', [9, 2, 11.5, 4.5])})]
    return {'parent': 'minecraft:block/block', 'textures': {k: tex(k) for k in ('side', 'band', 'lid', 'bottom', 'mouth', 'litter')}
            | {'particle': tex('side')}, 'elements': [body, *ribs, cover, *spill]}


def outputs():
    files = {}
    for name, (painter, seed) in TEXTURES.items():
        buf = io.BytesIO(); image(painter, seed).save(buf, format='PNG'); files[ASSETS / f'textures/block/garbage_bin_{name}.png'] = buf.getvalue()
    js = lambda d: (json.dumps(d, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    files[ASSETS / 'models/block/garbage_bin.json'] = js(upright())
    files[ASSETS / 'models/block/garbage_bin_tipped.json'] = js(tipped())
    turn = {'south': 0, 'west': 90, 'north': 180, 'east': 270}
    files[ASSETS / 'blockstates/garbage_bin.json'] = js({'variants': {
        f'facing={d},tipped={str(t).lower()}': {'model': 'mcyukkuri:block/garbage_bin' + ('_tipped' if t else ''), **({'y': y} if y else {})}
        for d, y in turn.items() for t in (False, True)}})
    files[ASSETS / 'models/item/garbage_bin.json'] = js({'parent': 'mcyukkuri:block/garbage_bin'})
    files[RUN / 'data/mcyukkuri/loot_table/blocks/garbage_bin.json'] = js({'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [
        {'type': 'minecraft:item', 'name': 'mcyukkuri:garbage_bin'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    files[RUN / 'data/mcyukkuri/recipe/garbage_bin.json'] = js({'type': 'minecraft:crafting_shaped', 'category': 'misc',
        'pattern': ['ini', 'i i', 'iii'], 'key': {'i': {'item': 'minecraft:iron_ingot'}, 'n': {'item': 'minecraft:iron_nugget'}},
        'result': {'id': 'mcyukkuri:garbage_bin', 'count': 1}})
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
    manifest = {'authoring': 'art/props/street-bin/build.py', 'status': 'pending_user_confirmation',
                'exports': {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(d).hexdigest() for p, d in sorted(files.items())}}
    (HERE / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('Wrote', len(stale), 'of', len(files), 'files')
    return 0


if __name__ == '__main__':
    sys.exit(main())
