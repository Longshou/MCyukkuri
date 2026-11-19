"""Registry of the placed care props for the exporters (build.py, large_nests.py).

Every placed pet-care good is block-style (block-props/kit.py): the food bowl and its fills, the waterer and its water, the sand toilet and the carriers in block-props/devices.py; the three grades of one-block pet bed with their fitted shapes, the 2x2
woven nest and the 2x2 wild bedding in block-props/nests.py. Units are 1/16 block, origin at the footprint centre.
Footprints, heights and entrances follow CareBlock collision (life/CareBlock.java); the nest collision envelope is
placed.py. STYLES is the dye-style table (keep in step with life/CareStyles.java); '' is the default style carried by the
plain mesh name.
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "block-props"))
import devices, nests      # block-style props (block-props/kit.py)

BED_STYLES = ['', 'gingham', 'polka', 'floral', 'lavender', 'teddy', 'stars']
STYLES = {**devices.STYLES, 'yukkuri_nest': BED_STYLES, **nests.STYLES}


def model_name(base, style):
    return f'{base}_{style}' if style else base


PROPS = {**{name: devices.PROPS[name] for name in ('food_bowl', 'food_bowl_regular', 'food_bowl_wheat', 'food_bowl_forage',
                                                   'waterer', 'waterer_water', 'yukkuri_toilet', 'yukkuri_toilet_dirty')},
         'yukkuri_nest': nests.BEDS['yukkuri_nest'], 'straw_nest': nests.BEDS['straw_nest'], 'velvet_nest': nests.BEDS['velvet_nest']}
LARGE = nests.LARGE_NESTS
VARIANTS = {**devices.variants(),                                    # dye-selected styles, shipped as runtime-peripheral attachments
            **{model_name('yukkuri_nest', s): nests.BEDS[model_name('yukkuri_nest', s)] for s in BED_STYLES[1:]},
            **nests.variants(),
            **nests.FITTED}       # one-block bed shapes flattened against blocks (drawn with the placed bed's texture)
