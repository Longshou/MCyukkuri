"""Export town template pools after the human-world structure NBT has been exported."""
import gzip
import importlib
import json
from pathlib import Path
import sys


SOURCE = Path(__file__).resolve().parent
DATA = SOURCE.parents[2] / 'src/main/resources/data/mcyukkuri'
sys.path.insert(0, str(SOURCE))

# Fill, retaining edge, and total weight of the vanilla 1.21.1 houses pool (including its empty draws).
STYLES = {
    'plains': ('dirt', 'cobblestone', 87),
    'desert': ('sandstone', 'cut_sandstone', 72),
    'savanna': ('dirt', 'terracotta', 81),
    'snowy': ('dirt', 'stone_bricks', 68),
    'taiga': ('dirt', 'cobblestone', 76),
}
POOLS = ('frontage', 'main_market', 'main_civic', 'main_end', 'town_lots')
LEGACY = {
    'village/street_sites': [(2, 'village/street_stall'), (2, 'village/street_alley'), (3, None)],
    'village/street_east': [(4, 'village/oni_shop'), (1, 'village/street_stall'), (1, None)],
}
EMPTY = {'element_type': 'minecraft:empty_pool_element'}


def levelled(location):
    return {'element_type': 'mcyukkuri:levelled', 'location': f'mcyukkuri:{location}',
            'processors': 'minecraft:empty', 'projection': 'rigid'}


def write_pool(name, fallback, entries):
    path = DATA / 'worldgen/template_pool' / f'{name}.json'
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {'name': f'mcyukkuri:{name}', 'fallback': fallback,
            'elements': [{'weight': weight, 'element': element} for weight, element in entries]}
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8', newline='\n')


def export():
    entries = [entry for path in sorted(SOURCE.glob('author_*.py'))
               for entry in getattr(importlib.import_module(path.stem), 'POOL_ENTRIES', ())]
    structure_dir = DATA / 'structure'
    templates = {path.relative_to(structure_dir).with_suffix('').as_posix(): path for path in structure_dir.rglob('*.nbt')}
    for style, (fill, edge, vanilla_weight) in STYLES.items():
        village = f'village/{style}'
        by_pool = {pool: [] for pool in POOLS}
        for entry in entries:
            location = f'{village}/{entry["piece"]}'
            if location not in templates or (entry['styles'] is not None and style not in entry['styles']):
                continue
            pool = entry['pool']
            element = levelled(location)
            if entry['max_count'] is not None:
                element['max_count'] = entry['max_count']
            if pool == 'town_lots':
                # A fill-only band selects the centre check (8 cut / 5 fill) and ordinary box collision.
                element['max_fill'] = 5
            else:
                for field in ('max_cut', 'max_fill'):
                    if entry[field] is not None:
                        element[field] = entry[field]
            element.update(fill={'Name': f'minecraft:{fill}'}, edge={'Name': f'minecraft:{edge}'})
            by_pool[pool].append((entry['weight'], element))

        # Keep the current template and weights while the palette package still exports the shared frontage.
        if not by_pool['frontage'] and 'village/yukkuri_shop' in templates:
            by_pool['frontage'].append((1, levelled('village/yukkuri_shop')))
        for pool in ('main_market', 'main_civic', 'main_end'):
            if not by_pool[pool]:
                by_pool[pool].append((1, EMPTY))

        lots = by_pool['town_lots']
        total_weight = sum(weight for weight, _ in lots)
        by_pool['town_lots'] = [(max(1, round(weight * vanilla_weight / total_weight)), element) for weight, element in lots]

        fallbacks = {'frontage': f'minecraft:{village}/streets', 'main_market': f'mcyukkuri:{village}/main_end',
                     'main_civic': f'mcyukkuri:{village}/main_end', 'main_end': 'minecraft:empty', 'town_lots': 'minecraft:empty'}
        for pool, elements in by_pool.items():
            write_pool(f'{village}/{pool}', fallbacks[pool], elements)

    clean_legacy_pools(templates)


def clean_legacy_pools(templates):
    paths = {pool: DATA / 'worldgen/template_pool' / f'{pool}.json' for pool in LEGACY}
    worldgen = [path.read_bytes() for path in (DATA / 'worldgen').rglob('*.json') if path not in paths.values()]
    # Structure NBT stores jigsaw pool IDs as length-prefixed TAG_String values named "pool".
    structures = [gzip.decompress(path.read_bytes()) for path in templates.values()]
    for pool, entries in LEGACY.items():
        pool_id = f'mcyukkuri:{pool}'.encode('ascii')
        tag = b'\x08\x00\x04pool' + len(pool_id).to_bytes(2, 'big') + pool_id
        if any(tag in structure for structure in structures) or any(b'"' + pool_id + b'"' in data for data in worldgen):
            write_pool(pool, 'minecraft:empty', [(weight, EMPTY if location is None else levelled(location))
                                                 for weight, location in entries if location is None or location in templates])
        else:
            paths[pool].unlink(missing_ok=True)


if __name__ == '__main__':
    export()
