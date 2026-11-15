"""Village type palettes: one authored layout, exported once per vanilla village type in that type's own materials.

Every styled piece asks its Palette for a role (wall, post, roof, lane, ...) instead of naming a block. Roles follow the materials vanilla
uses in the village type (counted from the 1.21.1 village templates): plains oak, cobblestone and white terracotta; desert smooth and cut
sandstone with jungle doors; savanna acacia and yellow terracotta; snowy spruce, stripped spruce, diorite and snow; taiga spruce logs and
mossy cobblestone. The desert has flat roofs with a parapet instead of gables.

FIXED holds what never changes with the type: identity (shop signs, wool awnings, banners), display glass, iron doors and bars, pens and
the exam area, interior furnishing of the two shops, lights, and every mcyukkuri: block. `allowed_blocks(style)` is the material set a
styled export may use (check_town.py reads it); `fill`/`edge` are the levelling blocks of the type (town_pools.py writes them into the
pool elements).
"""
from dataclasses import dataclass

STYLES = ('plains', 'desert', 'savanna', 'snowy', 'taiga')


def mc(name):
    return name if ':' in name else 'minecraft:' + name


def stem(name):
    """Stair/slab family of a block: stone_bricks -> stone_brick, oak_planks -> oak, cut_sandstone stays."""
    name = name.split(':')[-1]
    for suffix in ('_planks', '_bricks'):
        if name.endswith(suffix): return name[:-len(suffix)] + ('_brick' if suffix == '_bricks' else '')
    return name


def pick(options, x, z, salt=0):
    """A deterministic weighted choice per cell: options = ((block, weight), ...)."""
    total = sum(w for _, w in options)
    roll = ((x * 73856093) ^ (z * 19349663) ^ (salt * 83492791)) % 1009 / 1009 * total
    for block, weight in options:
        roll -= weight
        if roll < 0: return block
    return options[-1][0]


LOGS = ('_log', '_wood')
SIDES = ('north', 'south', 'east', 'west')


def link(block, sides=()):
    """State of a fence or stone wall joined on the given sides, as vanilla would update it (a lone one is a post)."""
    if block.endswith('_wall'):
        straight = set(sides) in ({'north', 'south'}, {'east', 'west'})
        props = {d: 'low' if d in sides else 'none' for d in SIDES}
        props['up'] = 'false' if straight else 'true'
    else:
        props = {d: 'true' if d in sides else 'false' for d in SIDES}
    props['waterlogged'] = 'false'
    return props


def leaves(block):
    return {'persistent': 'true', 'distance': '7', 'waterlogged': 'false'}


@dataclass(frozen=True)
class Palette:
    style: str
    wood: str            # doors, fences, trapdoors, signs, slabs of furniture
    dark: str            # counters, sign boards, trim boards (a darker wood of the type)
    wall: str            # ordinary wall (sheds, the oni shop, stall back)
    wall_light: str      # pale wall of the pet shop and civic buildings
    band: str            # horizontal course: cornice, desert bands
    post: str            # vertical frame (a log stands on axis y; a pillar block has no axis)
    beam: str            # horizontal beam
    plinth: str          # foundation course and the levelling edge
    weathered: tuple     # plinth wear: ((block, weight), ...) mixed into the plinth
    step: str            # stair family of street ramps and doorsteps
    roof: str            # stair/slab family of roofs
    roof_trim: str       # eaves, ridge and verge
    roof_patch: str      # stair family of old repairs on a shabby roof (the oni shop)
    flat: bool           # flat roof with a parapet (desert) instead of a gable
    snow: bool           # snow on the roof ridge and gable tops (snowy)
    floor: str           # shop floor boards
    aisle: str           # hard floor of shop aisles
    lane: str            # the vanilla street block of the type
    lane_wear: tuple     # worn patches in the lane
    sidewalk: tuple      # sidewalk mix
    ground: str          # open ground of a lot
    ground_wear: tuple   # patches in the open ground
    fill: str            # levelling fill below the template
    lamp_post: str       # street lamp post (fence or wall)
    fence: str           # yard fences and rails (fence or wall)
    hedge: str           # planter leaves ('' = none: potted plants instead)
    pots: tuple          # potted plants
    plants: tuple        # ground plants of a yard ((block, weight), ...); '' = bare
    tree_log: str        # log for timber piles and chopping blocks

    @property
    def edge(self): return self.plinth

    def planks(self, wood=None): return f'{wood or self.wood}_planks'

    def door(self): return self.wood

    def state(self, block, axis='y'):
        """(name, props) of a frame block: logs and woods take an axis, pillars do not."""
        return block, ({'axis': axis} if block.endswith(LOGS) else None)

    def plinth_at(self, x, z, salt=0):
        return pick(((self.plinth, 100 - sum(w for _, w in self.weathered)),) + self.weathered, x, z, salt)

    def lane_at(self, x, z):
        return pick(((self.lane, 100 - sum(w for _, w in self.lane_wear)),) + self.lane_wear, x, z, 7)

    def sidewalk_at(self, x, z):
        return pick(self.sidewalk, x, z, 3)

    def ground_at(self, x, z):
        return pick(((self.ground, 100 - sum(w for _, w in self.ground_wear)),) + self.ground_wear, x, z, 11)

    def pot(self, i): return self.pots[i % len(self.pots)]

    def wall_post(self):
        """Fence-like connections for lamp posts and rails: a fence joins with booleans, a stone wall with none/low."""
        return self.fence.endswith('_wall')


PALETTES = {
    'plains': Palette(
        'plains', wood='oak', dark='dark_oak', wall='oak_planks', wall_light='white_terracotta', band='stripped_oak_log',
        post='stripped_oak_log', beam='oak_log', plinth='cobblestone', weathered=(('mossy_cobblestone', 15),), step='cobblestone',
        roof='oak', roof_trim='dark_oak', roof_patch='mossy_cobblestone', flat=False, snow=False, floor='birch_planks', aisle='smooth_stone',
        lane='dirt_path', lane_wear=(('coarse_dirt', 8), ('gravel', 6)),
        sidewalk=(('cobblestone', 4), ('stone', 3), ('andesite', 3)),
        ground='grass_block', ground_wear=(), fill='dirt', lamp_post='oak_fence', fence='oak_fence',
        hedge='flowering_azalea_leaves', pots=('potted_azalea_bush', 'potted_poppy', 'potted_dandelion', 'potted_fern'),
        plants=(('dandelion', 1), ('poppy', 1), ('cornflower', 1), ('oxeye_daisy', 1), ('allium', 1)), tree_log='oak_log'),
    'desert': Palette(
        'desert', wood='jungle', dark='jungle', wall='smooth_sandstone', wall_light='smooth_sandstone', band='cut_sandstone',
        post='sandstone', beam='cut_sandstone', plinth='cut_sandstone', weathered=(('sandstone', 20),), step='sandstone',
        roof='smooth_sandstone', roof_trim='cut_sandstone', roof_patch='sandstone', flat=True, snow=False, floor='smooth_sandstone', aisle='cut_sandstone',
        lane='smooth_sandstone', lane_wear=(('sandstone', 10),),
        sidewalk=(('sandstone', 5), ('cut_sandstone', 5)),
        ground='sand', ground_wear=(), fill='sandstone', lamp_post='sandstone_wall', fence='sandstone_wall',
        hedge='', pots=('potted_cactus', 'potted_dead_bush'),
        plants=(('dead_bush', 1), ('', 3)), tree_log='jungle_log'),
    'savanna': Palette(
        'savanna', wood='acacia', dark='dark_oak', wall='acacia_planks', wall_light='yellow_terracotta', band='stripped_acacia_log',
        post='acacia_log', beam='stripped_acacia_log', plinth='terracotta', weathered=(('orange_terracotta', 10),), step='acacia',
        roof='acacia', roof_trim='dark_oak', roof_patch='oak', flat=False, snow=False, floor='stripped_acacia_wood', aisle='smooth_stone',
        lane='dirt_path', lane_wear=(('coarse_dirt', 12),),
        sidewalk=(('terracotta', 4), ('coarse_dirt', 3), ('packed_mud', 3)),
        ground='grass_block', ground_wear=(('coarse_dirt', 8),), fill='dirt', lamp_post='acacia_fence', fence='acacia_fence',
        hedge='acacia_leaves', pots=('potted_acacia_sapling', 'potted_dandelion', 'potted_dead_bush'),
        plants=(('short_grass', 3), ('dandelion', 1), ('poppy', 1)), tree_log='acacia_log'),
    'snowy': Palette(
        'snowy', wood='spruce', dark='dark_oak', wall='spruce_planks', wall_light='polished_diorite', band='stripped_spruce_wood',
        post='stripped_spruce_log', beam='stripped_spruce_log', plinth='stone_bricks', weathered=(('cracked_stone_bricks', 10),),
        step='stone_brick', roof='spruce', roof_trim='dark_oak', roof_patch='cobblestone', flat=False, snow=True, floor='spruce_planks', aisle='polished_diorite',
        lane='dirt_path', lane_wear=(('gravel', 8),),
        sidewalk=(('stone', 5), ('polished_diorite', 4)),
        ground='grass_block', ground_wear=(), fill='dirt', lamp_post='spruce_fence', fence='spruce_fence',
        hedge='spruce_leaves', pots=('potted_spruce_sapling', 'potted_fern'),
        plants=(('fern', 2), ('', 3)), tree_log='spruce_log'),
    'taiga': Palette(
        'taiga', wood='spruce', dark='dark_oak', wall='spruce_planks', wall_light='stripped_spruce_wood', band='spruce_log',
        post='spruce_log', beam='spruce_log', plinth='cobblestone', weathered=(('mossy_cobblestone', 25),), step='cobblestone',
        roof='spruce', roof_trim='dark_oak', roof_patch='mossy_cobblestone', flat=False, snow=False, floor='spruce_planks', aisle='stone',
        lane='dirt_path', lane_wear=(('coarse_dirt', 8), ('podzol', 4)),
        sidewalk=(('cobblestone', 4), ('mossy_cobblestone', 3), ('stone', 3)),
        ground='grass_block', ground_wear=(('podzol', 15),), fill='dirt', lamp_post='spruce_fence', fence='spruce_fence',
        hedge='spruce_leaves', pots=('potted_fern', 'potted_spruce_sapling'),
        plants=(('fern', 2), ('large_fern', 1), ('', 2)), tree_log='spruce_log'),
}
PLAINS = PALETTES['plains']


def palette(style): return PALETTES[style]


def levelling(style):
    """(fill, edge) block ids of the type, for the levelled pool elements."""
    p = PALETTES[style]
    return mc(p.fill), mc(p.edge)


# ------------------------------------------------------------------------------------------------ material sets
FIXED = {
    # structure mechanics, light and air
    'air', 'jigsaw', 'light', 'lantern', 'chain', 'ochre_froglight',
    # display glass, iron doors and bars
    'glass', 'glass_pane', 'iron_door', 'iron_bars',
    # identity: wool awnings and canopies, banners, the two shop signs
    'red_wool', 'white_wool', 'brown_wool', 'orange_wool', 'green_wool', 'blue_wool', 'cyan_wall_banner', 'red_wall_banner',
    'green_terracotta', 'cyan_terracotta', 'white_terracotta', 'red_terracotta', 'pink_terracotta', 'white_concrete', 'red_concrete',
    'black_concrete', 'yellow_concrete', 'red_nether_bricks',
    # pens and the exam area: pen posts, cushion floors, rugs, the records corner
    'stripped_birch_log', 'pink_wool', 'purple_wool', 'coarse_dirt', 'red_carpet', 'light_blue_carpet', 'green_carpet', 'brown_carpet',
    'bookshelf',
    # existing shop and market furnishings (staff areas are behind iron doors; the oni shop's treatment bench)
    'barrel', 'crafting_table', 'lectern', 'brewing_stand', 'water_cauldron', 'grindstone', 'composter', 'cobweb', 'hay_block',
    'pumpkin', 'melon', 'potted_dead_bush', 'water',
    # the pet shop's backyard planting is part of the yard pen
    'flowering_azalea',
}


def allowed_blocks(style):
    """Block ids a piece exported in this style may use (mcyukkuri: blocks are always allowed)."""
    p = PALETTES[style]
    out = set(FIXED)
    for wood in {p.wood, p.dark}:
        out |= {f'{wood}_{s}' for s in ('planks', 'slab', 'stairs', 'fence', 'fence_gate', 'door', 'trapdoor', 'sign', 'wall_sign',
                                         'pressure_plate', 'button', 'log', 'wood')}
        out |= {f'stripped_{wood}_log', f'stripped_{wood}_wood'}
    for family in {stem(p.roof), stem(p.roof_trim), stem(p.roof_patch), stem(p.step), stem(p.wall), stem(p.plinth)}:
        out |= {family, f'{family}_stairs', f'{family}_slab', f'{family}s'}
    out |= {p.wall, p.wall_light, p.band, p.post, p.beam, p.plinth, p.floor, p.aisle, p.lane, p.ground, p.fill, p.lamp_post, p.fence,
            p.tree_log}
    out |= {b for b, _ in p.weathered + p.lane_wear + p.sidewalk + p.ground_wear + p.plants if b}
    out |= set(p.pots)
    if p.hedge: out.add(p.hedge)
    if p.flat: out |= {'smooth_sandstone_slab', 'cut_sandstone_slab', 'sandstone_slab', 'sandstone_wall', 'chiseled_sandstone'}
    if p.snow: out |= {'snow', 'snow_block'}
    if p.ground == 'grass_block': out.add('dirt')
    return {mc(b) for b in out}


def violations(building, style):
    """Blocks of a Building outside its style's material set (empty when clean)."""
    allowed = allowed_blocks(style)
    used = {name for name, _, _ in building.blocks.values()}
    return sorted(n for n in used if n not in allowed and not n.startswith('mcyukkuri:'))
