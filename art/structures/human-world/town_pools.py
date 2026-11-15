"""Export town template pools after the human-world structure NBT has been exported."""
import importlib
import json
from pathlib import Path
import sys


SOURCE = Path(__file__).resolve().parent
DATA = SOURCE.parents[2] / 'src/main/resources/data/mcyukkuri'
sys.path.insert(0, str(SOURCE))
from palette import PALETTES

# Total weight of the vanilla 1.21.1 houses pool per village type (including its empty draws).
VANILLA_HOUSES = {'plains': 87, 'desert': 72, 'savanna': 81, 'snowy': 68, 'taiga': 76}
# Fill, retaining edge (the type's levelling blocks, palette.py) and the vanilla houses weight.
STYLES = {style: (PALETTES[style].fill, PALETTES[style].edge, weight) for style, weight in VANILLA_HOUSES.items()}
POOLS = ('frontage', 'main_market', 'main_civic', 'main_end', 'town_lots')
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


if __name__ == '__main__':
    export()
