"""The fields of the playground: open stretches of real ground, rebuilt from scratch by their own ``terrain`` function.

Ground is the flat world's own grass (top block y=63, standing level y=64). A field's ``terrain`` lines put its whole
box back into the starting state (grass, features, empty air above), so "clear this field" is: remove every mod entity
and dropped item in the box (``yukkuri clear``), then run the terrain again. Nothing else is ever left behind.
"""
import math
import random
import sys

sys.dont_write_bytecode = True

GROUND = 63            # top block of the flat world
FEET = GROUND + 1
SKY = 82               # highest layer that is cleared again
DECK_TOP = 68          # standing level of the observation deck (four blocks above the feet level)


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
    def __init__(self, fid, title, cx, cz, hx, hz, biome, ground='minecraft:grass_block', blurb=''):
        self.id, self.title, self.cx, self.cz, self.hx, self.hz = fid, title, cx, cz, hx, hz
        self.biome, self.ground, self.blurb = biome, ground, blurb
        self.x1, self.x2, self.z1, self.z2 = cx - hx, cx + hx, cz - hz, cz + hz
        self.scenarios = []
        self.extras = []          # (function name, caption, hint) of extra deck buttons beyond the scenarios
        self.anchor = (cx, cz)    # default block the scenarios stand around
        # observation deck: north of the field, looking south into it
        self.deck = (cx - 14, self.z1 - 13, cx + 14, self.z1 - 6)

    def rect(self):
        return (self.x1 - 2, self.z1 - 2, self.x2 + 2, self.z2 + 2)

    def deck_rect(self):
        x1, z1, x2, z2 = self.deck
        return (x1 - 1, z1 - 1, x2 + 1, z2 + 1)

    def chunks(self):
        out = set()
        for x1, z1, x2, z2 in (self.rect(), self.deck_rect()):
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


def tree(x, z, trunk='minecraft:oak_log', leaves='minecraft:oak_leaves', height=5):
    top = FEET + height
    return [fill(x - 2, top - 2, z - 2, x + 2, top - 1, z + 2, f'{leaves}[persistent=true]', 'keep'),
            fill(x - 1, top, z - 1, x + 1, top + 1, z + 1, f'{leaves}[persistent=true]', 'keep'),
            fill(x, FEET, z, x, top, z, trunk)]


def scatter_trees(field, count, seed, keep_out=0, trunk='minecraft:oak_log', leaves='minecraft:oak_leaves', margin=4):
    rng = random.Random(seed)
    out, placed = [], []
    while len(placed) < count:
        x = rng.randint(field.x1 + margin, field.x2 - margin)
        z = rng.randint(field.z1 + margin, field.z2 - margin)
        if math.hypot(x - field.cx, z - field.cz) < keep_out or any(abs(x - px) < 7 and abs(z - pz) < 7 for px, pz in placed):
            continue
        placed.append((x, z))
        out += tree(x, z, trunk, leaves, rng.randint(4, 6))
    return out


# ----------------------------------------------------------------------------------------------- the fields
class Meadow(Field):
    def features(self):
        return scatter_trees(self, 8, 11, keep_out=22)


class Town(Field):
    def features(self):
        out = [fill(self.cx - 1, GROUND, self.z1, self.cx + 1, GROUND, self.z2, 'minecraft:dirt_path'),
               fill(self.x1, GROUND, self.cz - 1, self.x2, GROUND, self.cz + 1, 'minecraft:dirt_path')]
        for px in (self.cx - 8, self.cx + 8):
            for pz in (self.cz - 8, self.cz + 8):
                out += [fill(px, FEET, pz, px, FEET + 2, pz, 'minecraft:oak_fence'), f'setblock {px} {FEET + 3} {pz} minecraft:lantern']
        return out


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
    """A plain human house: stone foundation, plank floor, walls with windows, a roof and one wide doorway to the south."""
    HX, HZ = 7, 5

    def features(self):
        cx, cz, hx, hz = self.cx, self.cz, self.HX, self.HZ
        x1, x2, z1, z2 = cx - hx, cx + hx, cz - hz, cz + hz
        out = [fill(x1 - 1, GROUND, z1 - 1, x2 + 1, GROUND, z2 + 1, 'minecraft:stone_bricks'),
               fill(x1, GROUND, z1, x2, GROUND, z2, 'minecraft:oak_planks'),
               fill(x1, FEET, z1, x2, FEET + 3, z2, 'minecraft:oak_planks', 'outline'),
               fill(x1 + 1, FEET, z1 + 1, x2 - 1, FEET + 3, z2 - 1, 'minecraft:air'),
               fill(x1 - 1, FEET + 4, z1 - 1, x2 + 1, FEET + 4, z2 + 1, 'minecraft:spruce_planks'),
               fill(cx - 1, FEET, z2, cx + 1, FEET + 2, z2, 'minecraft:air')]
        for wx in (cx - 4, cx + 4):
            out.append(fill(wx - 1, FEET + 1, z1, wx + 1, FEET + 2, z1, 'minecraft:glass'))
        for wz in (cz - 1, cz + 1):
            out += [fill(x1, FEET + 1, wz, x1, FEET + 2, wz, 'minecraft:glass'), fill(x2, FEET + 1, wz, x2, FEET + 2, wz, 'minecraft:glass')]
        out += [f'setblock {cx - 2} {FEET + 3} {cz} minecraft:lantern[hanging=true]', f'setblock {cx + 2} {FEET + 3} {cz} minecraft:lantern[hanging=true]',
                fill(x2 - 3, FEET, z1 + 1, x2 - 1, FEET, z1 + 1, 'minecraft:oak_stairs[facing=south]'),
                fill(x1 + 1, FEET, z1 + 1, x1 + 3, FEET, z1 + 1, 'minecraft:red_carpet')]
        out += scatter_trees(self, 4, 5, keep_out=11, margin=3)
        return out


class Hill(Field):
    """One earth mound with two south-facing caves eight blocks apart (the independence scenario needs both mouths intact)."""
    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        self.home = (self.cx - 6, FEET, self.cz - 7)          # mouth cell of the old cave; the new one is eight blocks east
        self.anchor = (self.home[0], self.home[2])
        self.family_anchor = (self.cx, self.cz + 8)

    def features(self):
        hx, _, hz = self.home
        out = []
        for mouth in (hx, hx + 8):
            for dx in range(-2, 3):
                for depth in range(5):
                    for dy in range(3):
                        solid = abs(dx) == 2 or depth == 4 or dy == 2 or depth == 0 and dx != 0
                        if solid:
                            out.append(f'setblock {mouth + dx} {FEET + dy} {hz - depth} minecraft:' + ('grass_block' if dy == 2 else 'dirt'))
        return out


class Woods(Field):
    def features(self):
        return scatter_trees(self, 34, 21, keep_out=24, margin=3)


class Snow(Field):
    def features(self):
        out = scatter_trees(self, 26, 33, keep_out=24, trunk='minecraft:spruce_log', leaves='minecraft:spruce_leaves', margin=3)
        return out


class Settle(Field):
    def features(self):
        return self._rim()

    def _rim(self):
        out = []
        rng = random.Random(7)
        for _ in range(16):
            side = rng.choice('nsew')
            x = rng.randint(self.x1 + 3, self.x2 - 3)
            z = rng.randint(self.z1 + 3, self.z2 - 3)
            if side == 'n':
                z = self.z1 + 3
            elif side == 's':
                z = self.z2 - 3
            elif side == 'w':
                x = self.x1 + 3
            else:
                x = self.x2 - 3
            out += tree(x, z, height=rng.randint(4, 6))
        return out


class Seasons(Field):
    """One pad on the 4x4 biome grid whose biome can be switched; ``Seasons.at`` reads the biome tags."""
    def __init__(self, *args, **kw):
        super().__init__(*args, **kw)
        self.pad = (self.cx // 4 * 4, self.cz // 4 * 4)

    def features(self):
        px, pz = self.pad     # a cleared field is a plains pad again, whatever season was pressed last
        return [f'fillbiome {px} {FEET - 8} {pz} {px + 3} {FEET + 30} {pz + 3} minecraft:plains']


class Clips(Field):
    def base(self):
        return (big_fill(self.x1, GROUND, self.z1, self.x2, GROUND, self.z2, 'minecraft:smooth_quartz')
                + big_fill(self.x1, GROUND + 1, self.z1, self.x2, SKY, self.z2, 'minecraft:air'))


FIELDS = [
    Meadow('meadow', '草地', -600, 300, 28, 28, 'minecraft:plains', blurb='日常、相遇、照料、嚼糜、起跳、陆地接生'),
    Town('town', '人类社会', -450, 300, 28, 28, 'minecraft:plains', blurb='野良应对、徽章、商店、考试、相亲、捕食种、运输'),
    Pond('pond', '池塘与河湾', -300, 300, 36, 28, 'minecraft:plains', blurb='水生魔理沙：帽船、出航、落水救援、船上接生'),
    House('house', '人类房屋', -150, 300, 22, 18, 'minecraft:plains', blurb='家油：棉絮、食屑、筑巢、卵'),
    Hill('hill', '山坡洞穴', 0, 300, 28, 24, 'minecraft:plains', blurb='长大与独立（两处洞口）'),
    Woods('woods', '林地', -600, 500, 36, 36, 'minecraft:forest', blurb='秋：采集与储粮'),
    Snow('snow', '雪原', -450, 500, 36, 36, 'minecraft:snowy_plains', ground='minecraft:snow_block', blurb='冬：居巢与存粮'),
    Settle('settle', '聚落草地', -300, 500, 44, 40, 'minecraft:forest', blurb='聚落、族长、派工、威胁、迁居'),
    Seasons('seasons', '季节', -150, 500, 12, 12, 'minecraft:plains', blurb='季节由群系决定'),
    Clips('clips', '动作片段', 0, 500, 18, 10, 'minecraft:plains', ground='minecraft:smooth_quartz', blurb='纯动作片段：身体、表情、体操、合唱'),
]
BY_ID = {f.id: f for f in FIELDS}
