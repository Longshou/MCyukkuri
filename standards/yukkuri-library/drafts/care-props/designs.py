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
# plate, always shown. Units 1..4: a lumpy dome of stored hay per stage, each with its own food on top (a later dome swallows
# the earlier one and most of its food): about 2/16 high for a handful, about 6/16 high and rounded when full. Food: rolled
# grass balls, red berries, acorns, a caterpillar, white flowers and, on the full pile, a tied sheaf of hay.
PILE_RADIUS = (3.0, 3.8, 4.4, 4.9)        # dome footprint radius per stage
PILE_PEAK = (1.7, 2.9, 4.1, 5.3)          # dome height above its base per stage
PILE_BASE = .3
DOME = ((1.0, 0.0), (.9, .3), (.72, .58), (.48, .82), (.22, .96))
LEAF_SCALE = .66                          # plate leaves at 2/3 size: the plate stays inside the block and the deepest hat cavity


def pile_surface(r, stage):
    """Height of the stage's dome surface at radius r."""
    R, H = PILE_RADIUS[stage - 1], PILE_PEAK[stage - 1]
    f = r / R
    if f >= 1:
        return PILE_BASE
    prof = list(DOME) + [(0.0, 1.0)]
    for (f0, y0), (f1, y1) in zip(prof, prof[1:]):
        if f1 <= f <= f0:
            return PILE_BASE + H * (y0 + (y1 - y0) * (f0 - f) / (f0 - f1))
    return PILE_BASE + H


def on_heap(stage, frac, ang, lift=0.0):
    R = PILE_RADIUS[stage - 1]
    r = R * frac
    return [r * math.cos(ang), pile_surface(r, stage) + lift, r * math.sin(ang)]


def berries(p, part, at, rng):
    for k in range(3):
        a = k * math.tau / 3 + rng.uniform(-.3, .3)
        p.cube(part, [at[0] + .6 * math.cos(a), at[1] + .45 + (.25 if k == 0 else 0), at[2] + .6 * math.sin(a)], (.5, .5, .5), 'berry',
               yaw=rng.uniform(0, 1.5), offset=(k * 3, k * 5))


def acorn(p, part, at, rng):
    yaw = rng.uniform(0, 1.5)
    p.cube(part, [at[0], at[1] + .55, at[2]], (.5, .55, .5), 'nut', yaw=yaw, offset=(rng.randrange(8), rng.randrange(8)))
    p.cube(part, [at[0], at[1] + 1.25, at[2]], (.62, .22, .62), 'cap', yaw=yaw)
    p.cube(part, [at[0], at[1] + 1.6, at[2]], (.12, .15, .12), 'cap', yaw=yaw)


def caterpillar(p, part, stage, frac, ang, rng):
    """Four segments crawling over the heap, the head a little higher."""
    for k in range(4):
        a = ang + k * .16
        f = frac + k * .02
        at = on_heap(stage, f, a)
        yaw = -a
        head = k == 3
        p.cube(part, [at[0], at[1] + .55 + (.15 if head else 0), at[2]], (.75, .7, .75), 'cap' if head else 'worm', yaw=yaw,
               offset=(k * 3, 0))


def forage_pile():
    p = WildProp('forage_pile')
    rng = rng_of('forage_pile')
    plate = p.mat('leaf_plate', 0)
    p.leaf(plate, 'leaf_dry', (-.6, .08, .9), 2.6, fold=.12, curl=.12, scale=LEAF_SCALE)
    p.leaf(plate, 'leaf_green', (1.0, .14, -.6), .6, fold=.14, curl=.12, scale=LEAF_SCALE)
    p.leaf(plate, 'leaf_old', (-.8, .2, -1.0), 4.5, fold=.1, curl=.1, scale=LEAF_SCALE)
    balls = (1, 1, 1, 2)
    clusters = (1, 0, 1, 1)
    acorns = (0, 1, 1, 2)
    flowers = (0, 1, 0, 1)
    worms = (0, 0, 1, 1)
    for stage in range(1, 5):
        R, H = PILE_RADIUS[stage - 1], PILE_PEAK[stage - 1]
        hay = p.mat(f'stored_hay_{stage}', stage)
        p.dome(hay, (0, PILE_BASE - .1, 0), R, R * .94, H + .1, 'hay', rng, seg=8, rings=DOME, phase=stage * .5, jitter=.1)
        if stage >= 3:                       # a second, lower lump slumped against the heap
            a = 2.4 + stage
            p.dome(hay, (math.cos(a) * R * .55, PILE_BASE - .1, math.sin(a) * R * .55), R * .55, R * .5, H * .55, 'hay', rng, seg=7,
                   rings=DOME, phase=stage, jitter=.12)
        food = p.mat(f'food_{stage}', stage)
        turn = stage * 1.7
        for k in range(balls[stage - 1]):
            ang = turn + k * 2.6
            at = on_heap(stage, .45 + .15 * k, ang)
            p.ball(food, [at[0], at[1] + .55, at[2]], 1.45 if k == 0 else 1.2, 'blades', rng)
        for k in range(clusters[stage - 1]):
            berries(p, food, on_heap(stage, .35 + .3 * k, turn + 1.3 + k * 2.9), rng)
        for k in range(acorns[stage - 1]):
            acorn(p, food, on_heap(stage, .62 - .2 * k, turn + 3.6 + k * 1.9), rng)
        for k in range(flowers[stage - 1]):
            at = on_heap(stage, .86, turn + 5.0 + k * 2.2, -.25)
            yaw = rng.uniform(0, 1.5)
            p.cross(food, 'daisy', at, yaw)
            p.flat(food, 'bloom', [at[0], at[1] + 5.5, at[2]], yaw)
        if worms[stage - 1]:
            caterpillar(p, food, stage, .72, turn + 4.3, rng)
        if stage == 4:                       # a tied sheaf of hay leaning on the full heap
            a, b = on_heap(4, .95, 2.2, .45), on_heap(4, .55, 2.0, .6)
            p.stick(food, a, b, .7, side='weave', end='hay', hw=.62, roll=.2)
            mid = [(a[k] + b[k]) / 2 for k in range(3)]
            d = [(b[k] - a[k]) for k in range(3)]
            ln = math.sqrt(sum(x * x for x in d))
            d = [x / ln * .4 for x in d]
            p.stick(food, [mid[k] - d[k] for k in range(3)], [mid[k] + d[k] for k in range(3)], .82, side='blades', end='blades', hw=.74, roll=.2)
    return p
