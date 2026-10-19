"""The fields of the playground: open stretches of real ground, rebuilt from scratch by their own ``terrain`` function.

Ground is the flat world's own grass (top block y=63, standing level y=64). A field's ``terrain`` lines put its whole
box back into the starting state (grass, features, empty air above), so "clear this field" is: remove every mod entity
and dropped item in the box (``yukkuri clear``), then run the terrain again. Nothing else is ever left behind.

Every field belongs to a theme group (``GROUPS``); the lobby only lists the fields by group, and each field has its own
observation deck with the detailed controls.
"""
import math
import random
import sys

sys.dont_write_bytecode = True

GROUND = 63            # top block of the flat world
FEET = GROUND + 1
SKY = 82               # highest layer that is cleared again
DECK_TOP = 68          # standing level of the observation deck (four blocks above the feet level)
DECK_DEPTH = 16        # blocks between the deck's north and south edge (up to six rows of buttons)

# theme groups, in lobby order: (id, title)
GROUPS = [('wild', '野生与住所'), ('biome', '群系聚落'), ('family', '家庭与成长'), ('react', '天气、伤病与头饰'),
          ('aqua', '水生魔理沙'), ('house', '家油'), ('human', '人类社会'), ('misc', '物种与动作')]


def fill(x1, y1, z1, x2, y2, z2, block, mode=''):
    return f'fill {x1} {y1} {z1} {x2} {y2} {z2} {block}' + (' ' + mode if mode else '')


def big_fill(x1, y1, z1, x2, y2, z2, block, mode='', limit=30000):
    """Split along z so that every fill stays below the vanilla 32768 block limit."""
    per_slice = (x2 - x1 + 1) * (y2 - y1 + 1)
    step = max(1, limit // per_slice)
    out, z = [], z1
    while z <= z2:
        zz = min(z2, z + step - 1)
        out.append(fill(x1, y1, z, x2, y2, zz, block, mode))
        z = zz + 1
    return out


def fillbiome_boxes(x1, z1, x2, z2, biome, y1=56, y2=100, limit=30000):
    per_slice = (x2 - x1 + 1) * (y2 - y1 + 1)
    step = max(1, limit // per_slice)
    out, z = [], z1
    while z <= z2:
        zz = min(z2, z + step - 1)
        out.append(f'fillbiome {x1} {y1} {z} {x2} {y2} {zz} {biome}')
        z = zz + 1
    return out


class Field:
    def __init__(self, fid, title, group, cx, cz, hx, hz, biome, ground='minecraft:grass_block', blurb=''):
        self.id, self.title, self.group, self.cx, self.cz, self.hx, self.hz = fid, title, group, cx, cz, hx, hz
        self.biome, self.ground, self.blurb = biome, ground, blurb
        self.x1, self.x2, self.z1, self.z2 = cx - hx, cx + hx, cz - hz, cz + hz
        self.scenarios = []
        self.extras = []          # (function name, caption, hint) of extra deck buttons beyond the scenarios
        self.readouts = []        # (caption, hint, command) of read-only buttons whose answer is relayed to the observer
        self.anchor = (cx, cz)    # default block the scenarios stand around
        # observation deck: north of the field, looking south into it
        self.deck = (cx - 14, self.z1 - 6 - DECK_DEPTH + 1, cx + 14, self.z1 - 6)
        self.gate = (cx, self.z2 + 4)   # return posts just outside the field (never cleared)

    def rect(self):
        return (self.x1 - 2, self.z1 - 2, self.x2 + 2, self.z2 + 2)

    def deck_rect(self):
        x1, z1, x2, z2 = self.deck
        return (x1 - 1, z1 - 1, x2 + 1, z2 + 1)

    def gate_rect(self):
        x, z = self.gate
        return (x - 3, z - 2, x + 3, z + 2)

    def chunks(self):
        out = set()
        for x1, z1, x2, z2 in (self.rect(), self.deck_rect(), self.gate_rect()):
            for x in range(x1 // 16 * 16, x2 // 16 * 16 + 1, 16):
                for z in range(z1 // 16 * 16, z2 // 16 * 16 + 1, 16):
                    out.add((x, z))
        return out

    def clear_box(self):
        """Region that ``yukkuri clear`` sweeps: the field and a margin (never the deck)."""
        return (self.x1 - 4, GROUND - 6, self.z1 - 4, self.x2 + 4, SKY + 8, self.z2 + 4)

    def base(self):
        out = big_fill(self.x1, GROUND, self.z1, self.x2, GROUND, self.z2, self.ground)
        out += big_fill(self.x1, GROUND + 1, self.z1, self.x2, SKY, self.z2, 'minecraft:air')
        return out

    def terrain(self):
        return self.base() + self.features()

    def features(self):
        return []

    def biome_commands(self):
        """Biome of the whole field; run once when the map is built (a cleared field keeps its biome)."""
        return fillbiome_boxes(self.x1 - 2, self.z1 - 2, self.x2 + 2, self.z2 + 2, self.biome)

    def spot(self, name):
        """Named places of a field: name -> (x, z) of the block a scenario stands around."""
        return self.spots[name]


# ----------------------------------------------------------------------------------------------- shared pieces
def tree(x, z, trunk='minecraft:oak_log', leaves='minecraft:oak_leaves', height=5):
    top = FEET + height
    return [fill(x - 2, top - 2, z - 2, x + 2, top - 1, z + 2, f'{leaves}[persistent=true]', 'keep'),
            fill(x - 1, top, z - 1, x + 1, top + 1, z + 1, f'{leaves}[persistent=true]', 'keep'),
            fill(x, FEET, z, x, top, z, trunk)]


def scatter_trees(field, count, seed, keep_out=0, trunk='minecraft:oak_log', leaves='minecraft:oak_leaves', margin=4, avoid=(), rect=None):
    rng = random.Random(seed)
    x1, z1, x2, z2 = rect or (field.x1, field.z1, field.x2, field.z2)
    out, placed = [], []
    tries = 0
    while len(placed) < count and tries < 4000:
        tries += 1
        x = rng.randint(x1 + margin, x2 - margin)
        z = rng.randint(z1 + margin, z2 - margin)
        if math.hypot(x - (x1 + x2) / 2, z - (z1 + z2) / 2) < keep_out or any(abs(x - px) < 7 and abs(z - pz) < 7 for px, pz in placed):
            continue
        if any(ax1 - 3 <= x <= ax2 + 3 and az1 - 3 <= z <= az2 + 3 for ax1, az1, ax2, az2 in avoid):
            continue
        placed.append((x, z))
        out += tree(x, z, trunk, leaves, rng.randint(4, 6))
    return out


def grass_cover(x1, z1, x2, z2, block='minecraft:short_grass'):
    """A carpet of plants over bare grass (keep: only air cells are filled)."""
    return big_fill(x1, FEET, z1, x2, FEET, z2, block, 'keep')


def den(x0, door_z, half=2, length=5, wall='minecraft:dirt', top='minecraft:grass_block', wall_half=None):
    """A south-facing earth mound with a one-cell open notch at its south edge and a cavity behind it (the recipe the wild shelters
    recognise): the mound is ``2*wall_half+1`` wide and ``length+4`` deep, 4 high; the cavity ``2*half+1`` wide, ``length`` deep, 2 high,
    under a two-block roof. The doorway (first cavity cell, covered) is ``(x0, FEET, door_z - 1)``; the notch ``(x0, door_z)`` is open to the sky.
    Returns (commands, doorway)."""
    wall_half = half + 2 if wall_half is None else wall_half
    z1 = door_z - length - 3
    out = [fill(x0 - wall_half, FEET, z1, x0 + wall_half, FEET + 2, door_z, wall),
           fill(x0 - wall_half, FEET + 3, z1, x0 + wall_half, FEET + 3, door_z, top),
           fill(x0, FEET, door_z, x0, FEET + 3, door_z, 'minecraft:air'),
           fill(x0 - half, FEET, door_z - length, x0 + half, FEET + 1, door_z - 1, 'minecraft:air')]
    return out, (x0, FEET, door_z - 1)


def solid_mound(x0, z0, w=9, d=9, h=4, wall='minecraft:dirt', top='minecraft:grass_block'):
    """A plain earth mound without any hollow (stray yukkuri dig into it). (x0, z0) is its middle."""
    return [fill(x0 - w // 2, FEET, z0 - d // 2, x0 + w // 2, FEET + h - 2, z0 + d // 2, wall),
            fill(x0 - w // 2, FEET + h - 1, z0 - d // 2, x0 + w // 2, FEET + h - 1, z0 + d // 2, top)]


def roof(x0, z0, half=2, height=2, block='minecraft:stone_bricks'):
    """A (2*half+1) square roof on four pillars, open on all sides (shelter from rain, not a home)."""
    out = [fill(x0 - half, FEET + height, z0 - half, x0 + half, FEET + height, z0 + half, block)]
    for px in (x0 - half, x0 + half):
        for pz in (z0 - half, z0 + half):
            out.append(fill(px, FEET, pz, px, FEET + height - 1, pz, 'minecraft:stone_brick_wall'))
    return out


# ----------------------------------------------------------------------------------------------- wild shelters and foraging
class Dens(Field):
    """Wild shelters: four south-facing mounds side by side. Spot x is the notch column, z the notch row."""
    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        z = self.cz + 6
        self.spots = {'family': (self.cx - 32, z), 'indep': (self.cx - 14, z), 'small': (self.cx + 4, z), 'big': (self.cx + 18, z)}
        self.doorways = {}
        self.cave_cmds = []
        x, dz = self.spots['family']
        cmds, self.doorways['family'] = den(x, dz)
        self.cave_cmds += cmds
        x, dz = self.spots['indep']
        cmds, self.doorways['indep'] = den(x, dz)
        self.cave_cmds += cmds
        x, dz = self.spots['small']          # five cells in a row, walled with stone: nothing diggable around the corridor
        cmds, self.doorways['small'] = den(x, dz, half=0, length=5, wall='minecraft:stone', top='minecraft:stone', wall_half=1)
        self.cave_cmds += cmds
        x, dz = self.spots['big']            # 25 empty cells
        cmds, self.doorways['big'] = den(x, dz)
        self.cave_cmds += cmds
        self.anchor = self.spots['family']

    def features(self):
        return self.cave_cmds + scatter_trees(self, 10, 61, margin=3, avoid=[(self.cx - 40, self.cz - 10, self.cx + 28, self.cz + 12)])


class Forage(Field):
    """Stray digging and foraging: an uncut mound, a family mound in a wide grassland, and open grass for grazing."""
    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        cx, cz = self.cx, self.cz
        self.dig_mound = solid_mound(cx - 28, cz)                       # x -32..-24, z -4..+4, 4 high
        self.forage_den, self.forage_door = den(cx + 4, cz + 6)         # a 5x5 hollow; its doorway is the first cavity cell
        self.spots = {'dig': (cx - 28, cz + 10), 'forage': (cx + 4, cz + 6), 'graze': (cx + 26, cz + 8)}
        self.anchor = self.spots['dig']

    def features(self):
        cx, cz = self.cx, self.cz
        # The nearby ground is already grazed; a family must walk to the outer meadows for a load.
        out = grass_cover(self.x1 + 1, self.z1 + 1, self.x2 - 1, self.z2 - 1)
        out += [fill(cx - 36, FEET, cz - 36, cx + 36, FEET, cz + 36, 'minecraft:air')]
        out += grass_cover(cx + 21, cz + 3, cx + 34, cz + 13)
        out += self.dig_mound + self.forage_den
        return out + scatter_trees(self, 8, 71, margin=3, avoid=[(cx - 36, cz - 8, cx + 10, cz + 14)])


# ----------------------------------------------------------------------------------------------- biome settlements
BIOME_PLOTS = [  # id, biome, ground block, trunk, leaves, title
    ('forest', 'minecraft:forest', 'minecraft:grass_block', 'minecraft:oak_log', 'minecraft:oak_leaves', '森林'),
    ('plains', 'minecraft:plains', 'minecraft:grass_block', None, None, '平原'),
    ('snow', 'minecraft:snowy_plains', 'minecraft:snow_block', 'minecraft:spruce_log', 'minecraft:spruce_leaves', '雪原'),
    ('taiga', 'minecraft:taiga', 'minecraft:grass_block', 'minecraft:spruce_log', 'minecraft:spruce_leaves', '针叶林'),
    ('swamp', 'minecraft:swamp', 'minecraft:grass_block', 'minecraft:oak_log', 'minecraft:oak_leaves', '沼泽'),
    ('savanna', 'minecraft:savanna', 'minecraft:grass_block', 'minecraft:acacia_log', 'minecraft:acacia_leaves', '热带草原'),
    ('desert', 'minecraft:desert', 'minecraft:sand', None, None, '沙漠'),
    ('badlands', 'minecraft:badlands', 'minecraft:red_sand', None, None, '恶地'),
]


class Biomes(Field):
    """Eight 44x44 plots in a 4x2 grid, one biome each. A plot's scenario sets its biome again, then spawns a natural settlement."""
    PITCH = 48

    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        self.plots = []
        for i, plot in enumerate(BIOME_PLOTS):
            col, row = i % 4, i // 4
            px = self.cx - 3 * self.PITCH // 2 + col * self.PITCH
            pz = self.cz - self.PITCH // 2 + row * self.PITCH
            self.plots.append((plot, px, pz))
        self.spots = {plot[0]: (px, pz) for plot, px, pz in self.plots}

    def plot_rect(self, px, pz):
        return px - 22, pz - 22, px + 22, pz + 22

    def plot_base(self, plot, px, pz):
        x1, z1, x2, z2 = self.plot_rect(px, pz)
        out = big_fill(x1, GROUND - 3, z1, x2, GROUND - 1, z2, 'minecraft:sandstone' if 'sand' in plot[2] else 'minecraft:dirt')
        out += big_fill(x1, GROUND, z1, x2, GROUND, z2, plot[2])
        return out + big_fill(x1, GROUND + 1, z1, x2, SKY, z2, 'minecraft:air')

    def plot_features(self, plot, px, pz):
        _, _, _, trunk, leaves, _ = plot
        if not trunk:
            return []
        return scatter_trees(self, 7, 91 + px + pz, margin=3, keep_out=20, trunk=trunk, leaves=leaves, rect=self.plot_rect(px, pz))

    def plot_terrain(self, plot, px, pz):
        return self.plot_base(plot, px, pz) + self.plot_features(plot, px, pz)

    def base(self):
        return [c for plot, px, pz in self.plots for c in self.plot_base(plot, px, pz)]

    def features(self):
        return [c for plot, px, pz in self.plots for c in self.plot_features(plot, px, pz)]

    def biome_commands(self):
        out = []
        for plot, px, pz in self.plots:
            x1, z1, x2, z2 = self.plot_rect(px, pz)
            out += fillbiome_boxes(x1, z1, x2, z2, plot[1])
        return out


# ----------------------------------------------------------------------------------------------- day life, family, weather
class Meadow(Field):
    """Daily life: a plain meadow with a few trees."""
    def features(self):
        return scatter_trees(self, 8, 11, keep_out=22)


class Family(Field):
    """Birth and child care: a meadow with flowers; every scenario has its own corner."""
    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        cx, cz = self.cx, self.cz
        self.spots = {'land_caught': (cx - 24, cz - 8), 'land_missed': (cx - 8, cz - 8), 'pet_birth': (cx + 8, cz - 8), 'feed': (cx + 24, cz - 8),
                      'first_toilet': (cx - 24, cz + 8), 'hop': (cx - 8, cz + 8), 'hurt_kin': (cx + 8, cz + 8), 'growth': (cx + 24, cz + 8)}
        self.anchor = self.spots['land_caught']

    def features(self):
        rng = random.Random(3)
        out = []
        for _ in range(50):
            x, z = rng.randint(self.x1 + 2, self.x2 - 2), rng.randint(self.z1 + 2, self.z2 - 2)
            if all(abs(x - sx) > 5 or abs(z - sz) > 5 for sx, sz in self.spots.values()):
                out.append(f'setblock {x} {FEET} {z} ' + rng.choice(['minecraft:dandelion', 'minecraft:poppy', 'minecraft:short_grass', 'minecraft:short_grass']))
        return out + scatter_trees(self, 8, 13, keep_out=0, margin=3, avoid=[(sx - 5, sz - 5, sx + 5, sz + 5) for sx, sz in self.spots.values()])


class Weather(Field):
    """Weather and predators: bare plains (a clear line of sight), a stone roof on four pillars at the west and, far to the east, open ground."""
    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        cx, cz = self.cx, self.cz
        self.roof_at = (cx - 22, cz)
        self.spots = {'shelter': (cx - 22 + 10, cz - 6), 'open': (cx + 26, cz - 6), 'thunder': (cx - 22 + 10, cz + 8), 'pred': (cx + 2, cz + 14), 'marks': (cx + 24, cz + 14),
                      'orange': (cx + 2, cz - 16)}
        self.anchor = self.spots['shelter']

    def features(self):
        return roof(*self.roof_at)


class Open(Field):
    """Bare ground with a few trees along the edges: nothing to eat, nothing to hide behind (injuries and remains, headwear)."""
    def features(self):
        return scatter_trees(self, 6, 23, keep_out=min(self.hx, self.hz) - 2, margin=3)


class Species(Field):
    """Species traits and rare forms: open ground; the kotatsu plot in the south-east is put back to plains by every clear
    (its scenarios turn it snowy or desert)."""
    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        cx, cz = self.cx, self.cz
        self.spots = {'lineup': (cx - 12, cz - 12), 'patchouli': (cx + 14, cz - 12), 'race': (cx - 22, cz + 10), 'sumo': (cx - 2, cz + 10),
                      'kotatsu': (cx + 22, cz + 12)}
        self.anchor = (cx, cz)

    def features(self):
        kx, kz = self.spots['kotatsu']
        return (scatter_trees(self, 6, 29, margin=3, avoid=[(x - 8, z - 6, x + 8, z + 6) for x, z in self.spots.values()])
                + fillbiome_boxes(kx - 12, kz - 12, kx + 12, kz + 12, 'minecraft:plains'))


class Pond(Field):
    """A pond with a gently sloping shore on its west side. Water blocks are source blocks on the ground layer (y=63) or deeper,
    so a standing yukkuri on the bank (feet y=64) is exactly one block above the water surface cell."""
    RX, RZ = 15, 11

    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        self.px, self.pz = self.cx + 4, self.cz
        # first water cell on the row z=pz, scanned from the west
        x = self.px - self.RX - 3
        while self.depth(x, self.pz) == 0:
            x += 1
        self.water = (x, GROUND, self.pz)
        self.bank = (x - 1, FEET, self.pz)
        self.anchor = (self.bank[0], self.bank[2])

    def depth(self, x, z):
        d = ((x - self.px) / self.RX) ** 2 + ((z - self.pz) / self.RZ) ** 2
        if d >= 1:
            return 0
        return 1 + int(3 * (1 - d) ** .5)      # 1 at the rim .. 4 in the middle

    def features(self):
        out = []
        for x in range(self.px - self.RX - 2, self.px + self.RX + 3):
            run = None
            for z in range(self.pz - self.RZ - 2, self.pz + self.RZ + 3):
                depth = self.depth(x, z)
                if run and run[0] == depth:
                    run[2] = z
                    continue
                if run:
                    out += self._column(x, *run)
                run = [depth, z, z]
            if run:
                out += self._column(x, *run)
        return out

    def _column(self, x, depth, z1, z2):
        if depth == 0:
            return []
        return [fill(x, GROUND - depth + 1, z1, x, GROUND, z2, 'minecraft:water')]


class House(Field):
    """A human house with life in it: a bed, kitchen, table, a sheep pen and two hidden corners (walls on two sides, furniture on the
    third, open on the fourth). The whole interior lies inside one aligned 16x16x8 activity cell (see HOUSE_ECOLOGY): x and z are chosen
    so that cx = 8 (mod 16) and cz = 8 (mod 16)."""
    HX, HZ = 6, 5

    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        assert self.cx % 16 == 8 and self.cz % 16 == 8, (self.cx, self.cz)
        self.corners = [(self.cx - self.HX + 1, self.cz - self.HZ + 1), (self.cx + self.HX - 1, self.cz - self.HZ + 1)]
        self.anchor = (self.cx, self.cz - 2)     # a free floor cell inside, between the table and the north wall

    def features(self):
        cx, cz, hx, hz = self.cx, self.cz, self.HX, self.HZ
        x1, x2, z1, z2 = cx - hx, cx + hx, cz - hz, cz + hz
        out = [fill(x1 - 1, GROUND, z1 - 1, x2 + 1, GROUND, z2 + 1, 'minecraft:stone_bricks'),
               fill(x1, GROUND, z1, x2, GROUND, z2, 'minecraft:oak_planks'),
               fill(x1, FEET, z1, x2, FEET + 3, z2, 'minecraft:oak_planks', 'outline'),
               fill(x1 + 1, FEET, z1 + 1, x2 - 1, FEET + 3, z2 - 1, 'minecraft:air'),
               fill(x1 - 1, FEET + 4, z1 - 1, x2 + 1, FEET + 4, z2 + 1, 'minecraft:spruce_planks'),
               fill(cx - 1, FEET, z2, cx + 1, FEET + 2, z2, 'minecraft:air')]
        for wx in (cx - 3, cx + 3):
            out.append(fill(wx - 1, FEET + 1, z1, wx + 1, FEET + 2, z1, 'minecraft:glass'))
        for wz in (cz - 1, cz + 2):
            out += [fill(x1, FEET + 1, wz, x1, FEET + 2, wz, 'minecraft:glass'), fill(x2, FEET + 1, wz, x2, FEET + 2, wz, 'minecraft:glass')]
        out += [f'setblock {cx - 2} {FEET + 3} {cz} minecraft:lantern[hanging=true]', f'setblock {cx + 2} {FEET + 3} {cz + 1} minecraft:lantern[hanging=true]']
        # hidden corner 1 (north-west): wall north + west, a bookshelf east of the corner cell, open to the south
        (c1x, c1z), (c2x, c2z) = self.corners
        out += [fill(c1x + 1, FEET, c1z, c1x + 1, FEET + 1, c1z, 'minecraft:bookshelf'),
                # corner 2 (north-east): wall north + east, a chest west of the corner cell, open to the south
                f'setblock {c2x - 1} {FEET} {c2z} minecraft:chest[facing=south]', f'setblock {c2x - 1} {FEET + 1} {c2z} minecraft:barrel[facing=up]']
        # bed against the west wall (south part), kitchen along the south-east, table in the middle
        out += [f'setblock {x1 + 1} {FEET} {cz + 2} minecraft:red_bed[facing=north,part=head]', f'setblock {x1 + 1} {FEET} {cz + 3} minecraft:red_bed[facing=north,part=foot]']
        out += [f'setblock {x2 - 1} {FEET} {cz + 3} minecraft:furnace[facing=west]', f'setblock {x2 - 1} {FEET} {cz + 2} minecraft:smoker[facing=west]',
                f'setblock {x2 - 1} {FEET} {cz + 1} minecraft:crafting_table', f'setblock {x2 - 1} {FEET} {cz} minecraft:cauldron']
        out += [f'setblock {cx} {FEET} {cz} minecraft:oak_fence', f'setblock {cx} {FEET + 1} {cz} minecraft:oak_pressure_plate',
                f'setblock {cx - 1} {FEET} {cz} minecraft:oak_stairs[facing=east]', f'setblock {cx + 1} {FEET} {cz} minecraft:oak_stairs[facing=west]']
        # a small fenced pen with a sheep to shear, south-west of the house
        out += [fill(x1 - 8, FEET, cz - 1, x1 - 4, FEET, cz - 1, 'minecraft:oak_fence'),
                fill(x1 - 8, FEET, cz + 3, x1 - 4, FEET, cz + 3, 'minecraft:oak_fence'),
                fill(x1 - 8, FEET, cz, x1 - 8, FEET, cz + 2, 'minecraft:oak_fence'),
                fill(x1 - 4, FEET, cz, x1 - 4, FEET, cz + 2, 'minecraft:oak_fence'),
                f'setblock {x1 - 6} {FEET} {cz + 3} minecraft:oak_fence_gate[facing=south]']
        out += [f'kill @e[type=minecraft:sheep,tag=scn_prop,x={x1 - 6},y={FEET},z={cz + 1},distance=..4]',
                f'summon minecraft:sheep {x1 - 5.5} {FEET} {cz + 1.5} {{Tags:["scn_prop"],PersistenceRequired:1b}}']
        out += scatter_trees(self, 4, 5, keep_out=0, margin=3, avoid=[(x1 - 10, z1 - 3, x2 + 3, z2 + 3)])
        return out


# ----------------------------------------------------------------------------------------------- human society
class Town(Field):
    def features(self):
        out = [fill(self.cx - 1, GROUND, self.z1, self.cx + 1, GROUND, self.z2, 'minecraft:dirt_path'),
               fill(self.x1, GROUND, self.cz - 1, self.x2, GROUND, self.cz + 1, 'minecraft:dirt_path')]
        for px in (self.cx - 8, self.cx + 8):
            for pz in (self.cz - 8, self.cz + 8):
                out += [fill(px, FEET, pz, px, FEET + 2, pz, 'minecraft:oak_fence'), f'setblock {px} {FEET + 3} {pz} minecraft:lantern']
        for bx in (self.cx - 12, self.cx + 12):
            out += [f'setblock {bx} {FEET} {self.cz + 3} minecraft:oak_stairs[facing=north]', f'setblock {bx + 1} {FEET} {self.cz + 3} minecraft:oak_stairs[facing=north]']
        out += tree(self.cx - 16, self.cz - 16) + tree(self.cx + 16, self.cz + 16) + tree(self.cx - 16, self.cz + 16, height=4) + tree(self.cx + 16, self.cz - 16, height=4)
        return out


class Lot(Field):
    """An empty building lot with a street along its south edge; a ``human build`` button puts the whole building on it and clears the lot first.
    ``pads`` are the entrance x offsets from the centre (one building per pad); the entrance row is 17 blocks south of the centre."""
    def __init__(self, *args, pads=(0,), **kw):
        super().__init__(*args, **kw)
        self.pads = pads
        self.entrance_z = self.cz + 17
        self.spots = {}

    def features(self):
        return [fill(self.x1, GROUND, self.entrance_z + 1, self.x2, GROUND, self.entrance_z + 3, 'minecraft:dirt_path')]


# ----------------------------------------------------------------------------------------------- clips
class Clips(Field):
    def base(self):
        return (big_fill(self.x1, GROUND, self.z1, self.x2, GROUND, self.z2, 'minecraft:smooth_quartz')
                + big_fill(self.x1, GROUND + 1, self.z1, self.x2, SKY, self.z2, 'minecraft:air'))


FIELDS = [
    Dens('den', '野外住所', 'wild', -900, 300, 44, 30, 'minecraft:plains', blurb='山坡洞穴一家三口、独立、搬往大洞'),
    Forage('forage', '流浪与觅食', 'wild', -750, 300, 96, 96, 'minecraft:plains', blurb='流浪油挖穴、家旁稀疏与远处草场、吃草'),
    Biomes('biomes', '群系聚落', 'biome', -760, 500, 96, 48, 'minecraft:plains', blurb='八个群系各一处自然聚落'),
    Meadow('meadow', '日常草地', 'family', -600, 300, 28, 28, 'minecraft:plains', blurb='回窝、相遇、受伤照料、饿了取食、厕所'),
    Family('family', '出生与成长', 'family', -450, 300, 36, 24, 'minecraft:plains', blurb='陆地接生、育儿、亲子舔舐、成长、宠物生产窝'),
    Weather('weather', '天气与捕食种', 'react', -300, 300, 44, 28, 'minecraft:plains', blurb='雨天躲雨、捕食种反应、挡捕食种、妖梦拔刀、脏污伤痕、橙汁'),
    Open('body', '伤病与遗体', 'react', -150, 500, 36, 28, 'minecraft:plains', blurb='受击部位、中枢外露与濒死、遗体腐败、食尸、临终遗言、请吃了我'),
    Open('headwear', '头饰与认知', 'react', 0, 500, 28, 24, 'minecraft:plains', blurb='头饰损坏自愈、碎裂拼回、失饰者认不出、鄙视、怪物、欺负'),
    Pond('pond', '池塘与河湾', 'aqua', -150, 300, 36, 28, 'minecraft:plains', blurb='水生魔理沙：帽船、出航、落水救援、船上接生'),
    House('house', '家油人类屋', 'house', 8, 296, 22, 18, 'minecraft:plains', blurb='有人类活动的屋子：隐蔽墙角、搬入、筑巢、迁卵'),
    Town('town', '街道', 'human', -900, 700, 36, 28, 'minecraft:plains', blurb='牵宠散步、喂野生油、鬼威惨、徽章、考试、相亲、运输'),
    Lot('shop', '商店', 'human', -750, 700, 26, 22, 'minecraft:plains', blurb='整座商店：店主、货架、银章爱丽丝'),
    Lot('factory', '工厂', 'human', -540, 700, 68, 22, 'minecraft:plains', pads=(-44, 0, 44), blurb='劳动、馒头、袋子三座工厂'),
    Species('species', '物种与稀有形态', 'misc', 150, 500, 36, 28, 'minecraft:plains', blurb='妖梦与楼观剑、稀有形态、暖桌魔理沙、帕琪呕吐、橙速度、魔理沙相扑'),
    Clips('clips', '动作片段', 'misc', -300, 500, 18, 10, 'minecraft:plains', ground='minecraft:smooth_quartz', blurb='纯动作片段：身体、表情、体操、合唱'),
]
BY_ID = {f.id: f for f in FIELDS}
