"""Care-prop designs: the forage pile (wild nest stock, reused whole by the hat-boat provisions in cargo.py). The placed
food bowl and its fills are vanilla-style voxel props in vanilla_props.py.

The forage pile shares the hand-drawn wild_kit atlas and shapes with the self-built nests (wild.py), so a nest and its
store read as one family. Units are 1/16 block; origin at the footprint centre, ground y = 0.
"""
import math
from wild_kit import WildProp, rng_of

# ---------------------------------------------------------------------------------------------------------
# Forage pile (储粮堆): what a wild family has carried home, laid on big leaves as on a plate. Wiki: food set out on leaves
# beside the nest - a caterpillar, a white flower, rolled clumps of green grass (EH19); hay and "bug dumplings" kept in the
# nest (N71); dry grass and nuts kept for winter (L06); flowers, caterpillars and fallen fruit gathered (N93, N38, EH35).
# The heap shape, its four stages and the mix shown are project design. Unit 0: three big leaves (dry, green, old) as the
# plate, always shown. Units 1..4 add yellow hay clumps that stack into a heap of five (about 2/16 high for a handful,
# about 6/16 when full) with loose stems across their tops, and food: some on the hay, some straight on the leaf plate
# beside it as in EH19. Food: rolled grass balls, red berries, acorns, a caterpillar, small white flowers and, on the full
# pile, a tied sheaf of hay.
PLATE_Y = .22
LEAF_SCALE = .66                          # plate leaves at 2/3 size: the plate stays inside the block and the deepest hat cavity
# hay clumps: (stage, centre x, centre z, radius x, radius z, height, base y)
CLUMPS = ((1, 0.0, 0.2, 2.7, 2.4, 2.4, .1),
          (2, 1.8, -1.0, 2.4, 2.1, 2.4, .1), (2, -1.7, 1.0, 2.3, 2.2, 2.2, .1),
          (3, .2, -.1, 2.5, 2.3, 2.5, 1.8),
          (4, -.1, .2, 1.9, 1.8, 2.1, 3.7))


def heap_height(x, z, stage):
    """Top of the hay heap (or the plate) at (x, z) once the given stage is shown."""
    top = PLATE_Y
    for st, cx, cz, rx, rz, h, base in CLUMPS:
        if st > stage:
            continue
        d = math.hypot((x - cx) / rx, (z - cz) / rz)
        if d < 1:
            top = max(top, base + h * math.sqrt(1 - d * d) * .95)
    return top


def at(x, z, stage, lift=0.0):
    return [x, heap_height(x, z, stage) + lift, z]


def berries(p, part, pos, rng):
    for k in range(3):
        a = k * math.tau / 3 + rng.uniform(-.3, .3)
        p.cube(part, [pos[0] + .6 * math.cos(a), pos[1] + .45 + (.25 if k == 0 else 0), pos[2] + .6 * math.sin(a)], (.5, .5, .5), 'berry',
               yaw=rng.uniform(0, 1.5), offset=(k * 3, k * 5))


def acorn(p, part, pos, rng):
    yaw = rng.uniform(0, 1.5)
    p.cube(part, [pos[0], pos[1] + .55, pos[2]], (.5, .55, .5), 'nut', yaw=yaw, offset=(rng.randrange(8), rng.randrange(8)))
    p.cube(part, [pos[0], pos[1] + 1.25, pos[2]], (.62, .22, .62), 'cap', yaw=yaw)
    p.cube(part, [pos[0], pos[1] + 1.6, pos[2]], (.12, .15, .12), 'cap', yaw=yaw)


def caterpillar(p, part, x, z, heading, stage):
    """Four pale segments crawling in a gentle curve, the head a little raised and darker."""
    for k in range(4):
        a = heading + k * .25
        px, pz = x + math.cos(heading) * k * 1.3, z + math.sin(heading) * k * 1.3 + .2 * k * k * .3
        y = heap_height(px, pz, stage)
        head = k == 3
        p.cube(part, [px, y + .55 + (.15 if head else 0), pz], (.72, .66, .72), 'cap' if head else 'worm', yaw=-a, offset=(k * 3, 0))


def flower(p, part, pos, rng):
    yaw = rng.uniform(0, 1.5)
    p.cross(part, 'daisy', pos, yaw)
    p.flat(part, 'bloom', [pos[0], pos[1] + 4.05, pos[2]], yaw)


def hay_clump(p, part, rng, clump, k):
    st, cx, cz, rx, rz, h, base = clump
    p.dome(part, (cx, base, cz), rx, rz, h, 'hay', rng, seg=7, phase=k * .9, jitter=.12)
    for j in range(2):                                   # loose stems lying across the top of the clump
        a = rng.uniform(0, math.pi)
        r = rng.uniform(.2, .5)
        x0, z0 = cx - math.cos(a) * rx * r - math.cos(a) * 1.6, cz - math.sin(a) * rz * r - math.sin(a) * 1.6
        p.straw(part, [x0, base + h * .8, z0], a, rng.uniform(-.25, .05), rng.uniform(2.8, 3.8), s0=j * 4)


def forage_pile():
    p = WildProp('forage_pile')
    rng = rng_of('forage_pile')
    plate = p.mat('leaf_plate', 0)
    p.leaf(plate, 'leaf_dry', (-.6, .08, .9), 2.6, fold=.12, curl=.12, scale=LEAF_SCALE)
    p.leaf(plate, 'leaf_green', (1.0, .14, -.6), .6, fold=.14, curl=.12, scale=LEAF_SCALE)
    p.leaf(plate, 'leaf_old', (-.8, .2, -1.0), 4.5, fold=.1, curl=.1, scale=LEAF_SCALE)
    for stage in range(1, 5):
        hay = p.mat(f'stored_hay_{stage}', stage)
        for k, clump in enumerate(CLUMPS):
            if clump[0] == stage:
                hay_clump(p, hay, rng, clump, k)
        food = p.mat(f'food_{stage}', stage)
        if stage == 1:
            berries(p, food, at(.3, .3, 1), rng)
            p.ball(food, [3.7, PLATE_Y + .9, 2.0], 1.2, 'blades', rng)          # a grass ball set on the plate
        elif stage == 2:
            acorn(p, food, at(2.1, -1.2, 2), rng)
            flower(p, food, at(-1.9, 1.3, 2, -.25), rng)
            caterpillar(p, food, -4.6, -2.0, .35, 2)                              # crawling on the plate
        elif stage == 3:
            p.ball(food, at(-.3, -.6, 3, 1.0), 1.3, 'blades', rng)
            acorn(p, food, at(1.2, .9, 3), rng)
            berries(p, food, [-3.1, PLATE_Y, 3.3], rng)                         # berries on the plate
        else:
            berries(p, food, at(.4, -.3, 4), rng)
            flower(p, food, at(-2.6, 1.9, 4, -.25), rng)
            acorn(p, food, at(-1.2, -1.5, 4), rng)
            p.ball(food, [3.2, PLATE_Y + .9, -3.0], 1.15, 'blades', rng)
            a, b = at(2.9, 2.2, 4, .5), at(1.0, 1.0, 4, .7)                       # a tied sheaf of hay leaning on the heap
            p.stick(food, a, b, .7, side='weave', end='straw_end', hw=.62, roll=.2)
            mid = [(a[k] + b[k]) / 2 for k in range(3)]
            d = [(b[k] - a[k]) for k in range(3)]
            ln = math.sqrt(sum(x * x for x in d))
            d = [x / ln * .4 for x in d]
            p.stick(food, [mid[k] - d[k] for k in range(3)], [mid[k] + d[k] for k in range(3)], .82, side='blades', end='blades', hw=.74, roll=.2)
    return p
