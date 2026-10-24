"""Registry of the placed care props for the exporters (build.py, large_nests.py, care-expansion/build.py).

The placed pet-care goods (food bowl and fills, pet cushion nest, 2x2 woven nest, toilets and droppings, with their dye
styles) are rounded low-poly props with painted regions from placed.py; the wild 2x2 bedding comes from wild.py
(wild_kit atlas). Units are 1/16 block, origin at the footprint centre. Footprints, heights and entrances follow
CareBlock collision (life/CareBlock.java) and the documented 2x2 nest interior.
"""
import placed


def large_wild_nest():
    """Wild family bedding: a woven dry-grass roll and leaf-litter pad from the wild nest family (wild.py, wild_kit atlas)."""
    import wild
    return wild.large_wild_nest()


PROPS = {'food_bowl': placed.food_bowl, 'food_bowl_regular': placed.food_bowl_regular, 'food_bowl_wheat': placed.food_bowl_wheat,
         'food_bowl_forage': placed.food_bowl_forage, 'yukkuri_nest': placed.yukkuri_nest, 'yukkuri_toilet': placed.yukkuri_toilet,
         'yukkuri_toilet_dirty': placed.yukkuri_toilet_dirty, 'toilet_waste': placed.toilet_waste}
LARGE = {'large_yukkuri_nest': placed.large_yukkuri_nest, 'large_wild_nest': large_wild_nest}
TRAYS = {'ceramic_toilet': placed.ceramic_toilet, 'covered_toilet': placed.covered_toilet}
VARIANTS = placed.variants()          # dye-selected styles of the placed goods, shipped as runtime-peripheral attachments
