"""Care-prop designs: the forage pile (wild nest stock). The placed
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
# The shape, its four stages and the mix shown are project design. Unit 0: three big leaves (dry, green, old) as the
# plate, always shown. Units 1..4: one low round hay mound per stage (a later mound swallows the earlier one), a few
# mouthfuls of loose grass pressed onto its top like the nest rims and stems lying across it (nothing tied: wild
# yukkuri have no hands, user ruling), a cluster of red berries on one side of the top; the front of the plate
# gathers grass balls and a caterpillar as in EH19, one more item per stage; the full pile adds one small white flower.
# Colours stay hay yellow, leaf green and the red accent. About 2/16 high for a handful, 5/16 when full.
PLATE_Y = .22
LEAF_SCALE = .66                          # plate leaves at 2/3 size: the plate stays inside the block and the deepest hat cavity
MOUND = ((2.6, 1.5), (3.3, 2.3), (3.9, 3.1), (4.3, 3.8))      # radius, height of the hay mound per stage
MOUND_Z = -.7                                                   # mound sits toward the back; the plate's front stays clear
# loose wads pressed onto each stage's mound: (angle across the top, offset from the top along z, length)
WADS = (((.4, 0.0, 3.2),),
           ((.2, .4, 3.8), (2.2, -.6, 3.4)),
           ((.5, .5, 4.4), (2.0, -.7, 4.0)),
           ((.1, .7, 4.8), (1.4, -.4, 4.6), (2.7, .2, 4.2)))


def mound_height(x, z, stage):
    R, H = MOUND[stage - 1]
    d = math.hypot(x / R, (z - MOUND_Z) / (R * .9))
    return PLATE_Y + H * math.sqrt(max(0.0, 1 - d * d)) ** 1.15 if d < 1 else PLATE_Y


def berries(p, part, pos, rng):
    for k in range(4):
        a = k * math.tau / 3 + rng.uniform(-.3, .3)
        r = 0 if k == 3 else .6
        p.cube(part, [pos[0] + r * math.cos(a), pos[1] + .45 + (.55 if k == 3 else 0), pos[2] + r * math.sin(a)], (.5, .5, .5), 'berry',
               yaw=rng.uniform(0, 1.5), offset=(k * 3, k * 5))


def caterpillar(p, part, x, z, heading):
    """Four pale segments crawling over the leaf plate in a gentle curve, the head a little raised."""
    for k in range(4):
        a = heading + k * .22
        px, pz = x + math.cos(heading) * k * 1.3, z + math.sin(heading) * k * 1.3 + .12 * k * k
        p.cube(part, [px, PLATE_Y + .55 + (.15 if k == 3 else 0), pz], (.72, .66, .72), 'worm', yaw=-a, offset=(k * 3, 0))


def top_wad(p, part, rng, stage, angle, offset, length):
    """A mouthful of loose grass pushed onto the mound and pressed down, ends sloping with it, a stem or two across it."""
    from wild_kit import PathLine, wad
    cx, cz = 0.0, MOUND_Z + offset
    dx, dz = math.cos(angle) * length / 2, math.sin(angle) * length / 2
    a, b = (cx - dx, cz - dz), (cx + dx, cz + dz)
    nx, nz = -math.sin(angle), math.cos(angle)
    line = PathLine([(a[0], a[1], nx, nz), (b[0], b[1], nx, nz)])
    base = min(mound_height(a[0], a[1], stage), mound_height(b[0], b[1], stage), mound_height(cx, cz, stage)) - .35
    wad(p, part, line, 0, line.length, base, base + 1.25, 1.1, 1.1, droop=-.25 if rng.random() < .5 else .25,
        tex0=rng.randrange(16), old=rng.random() < .33)
    a2 = angle + rng.uniform(.6, 1.2)
    sx, sz = cx - math.cos(a2) * length * .4, cz - math.sin(a2) * length * .4
    p.straw(part, [sx, mound_height(sx, sz, stage) + .3, sz], a2, rng.uniform(-.1, .15), length * .8, s0=rng.randrange(8))


def forage_pile():
    p = WildProp('forage_pile')
    rng = rng_of('forage_pile')
    plate = p.mat('leaf_plate', 0)
    p.leaf(plate, 'leaf_dry', (.2, .08, 1.5), .05, fold=.12, curl=.12, scale=LEAF_SCALE)
    p.leaf(plate, 'leaf_green', (-1.9, .14, 1.4), -.3, fold=.14, curl=.12, scale=LEAF_SCALE)
    p.leaf(plate, 'leaf_old', (2.0, .2, .7), .35, fold=.1, curl=.1, scale=LEAF_SCALE)
    front = {1: lambda part: p.ball(part, [1.9, PLATE_Y + .9, 3.6], 1.2, 'blades', rng),
             2: lambda part: caterpillar(p, part, -3.3, 2.8, .25),
             3: lambda part: p.ball(part, [-.3, PLATE_Y + .9, 4.6], 1.1, 'blades', rng),
             4: lambda part: p.ball(part, [3.3, PLATE_Y + .9, 2.0], 1.0, 'blades', rng)}
    for stage in range(1, 5):
        R, H = MOUND[stage - 1]
        hay = p.mat(f'stored_hay_{stage}', stage)
        p.dome(hay, (0, PLATE_Y - .15, MOUND_Z), R, R * .9, H + .15, 'hay', rng, seg=9, phase=stage * .6, jitter=.06)
        for angle, offset, length in WADS[stage - 1]:
            top_wad(p, hay, rng, stage, angle, offset, length)
        food = p.mat(f'food_{stage}', stage)
        bx, bz = 1.1, MOUND_Z - .9
        berries(p, food, [bx, mound_height(bx, bz, stage) + .2, bz], rng)
        front[stage](food)
        if stage == 4:                                   # the one white flower, on the near flank of the full mound
            fx, fz = -1.9, MOUND_Z + 2.4
            yaw = rng.uniform(0, 1.5)
            pos = [fx, mound_height(fx, fz, 4) - .3, fz]
            p.cross(food, 'daisy', pos, yaw)
            p.flat(food, 'bloom', [pos[0], pos[1] + 4.05, pos[2]], yaw)
    return p
