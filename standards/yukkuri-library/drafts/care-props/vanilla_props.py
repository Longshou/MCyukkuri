"""Registry of the placed care props for the exporters (build.py, large_nests.py).

The food bowl and its fills are round glazed ceramic (bowl.py), and so is the waterer with its water (waterer.py). The
sand toilet is block-style (block-props/devices.py). The three grades of one-block pet bed with their fitted shapes and
the 2x2 woven nest are rounded low-poly props with painted regions from placed.py; the 2x2 wild bedding comes from wild.py
(wild_kit atlas). Units are 1/16 block, origin at the footprint centre. Footprints, heights and entrances follow
CareBlock collision (life/CareBlock.java). STYLES is the dye-style table (keep in step with life/CareStyles.java); '' is
the default style carried by the plain mesh name.
"""
import sys
from pathlib import Path
import bowl, waterer, placed
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "block-props"))
import devices      # block-style props (block-props/kit.py)

STYLES = {**bowl.STYLES, **devices.STYLES, **placed.STYLES}
model_name = placed.model_name


def large_wild_nest():
    """Wild family bedding: a pressed dry-grass roll and leaf-litter pad from the wild nest family (wild.py, wild_kit atlas)."""
    import wild
    return wild.large_wild_nest()


PROPS = {**bowl.PROPS, **waterer.PROPS,
         **{name: devices.PROPS[name] for name in ('yukkuri_toilet', 'yukkuri_toilet_dirty')},
         'yukkuri_nest': placed.yukkuri_nest, 'straw_nest': placed.straw_nest, 'velvet_nest': placed.velvet_nest}
LARGE = {'large_yukkuri_nest': placed.large_yukkuri_nest, 'large_wild_nest': large_wild_nest}
VARIANTS = {**bowl.variants(),        # dye-selected styles, shipped as runtime-peripheral attachments
            **devices.variants(),
            **placed.variants(),
            **placed.fitted_nests()}   # one-block bed shapes flattened against blocks (drawn with the placed bed's texture)
