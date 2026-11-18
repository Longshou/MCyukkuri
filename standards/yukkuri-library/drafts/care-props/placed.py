"""Collision envelope of the placed pet beds (life/CareBlock reads care_collision.json).

The placed care goods themselves are block-style props in block-props/devices.py and block-props/nests.py. The
collision shells stay the ones generated from the earlier rounded beds, whose top surface the block-style beds follow
within a unit (pad top 2 over the collision pad 1.75, cushion crest 5 under 5.25, woven wall 6): the one-block pet
bed's 16 fitted shapes (a revolved round roll round a level pad, sides flattened against blocks, an entrance dip toward
-z) and the 2x2 woven nest (a rounded-square wall dipping across its +z front round a pad). Only geometry is built here;
the textures are blank. Footprints follow life/CareBlock.
"""
import math
from shapes import Build, Outline, SideOutline, CIRCLE, revolve, cap, grid, canvas, smoothstep, heightmap, collision_boxes

BLANK = canvas(4, 4, '#808080')


# =====================================================================================================================
# Pet cushion nest (one block, overhanging to 26.6 across): a thick round bolster 5.25 high (EH23's round raised-rim
# cushion) with piping on the outer shoulder, round a flat quilted pad (y 1.6-1.8, radius 10.1) big enough for one adult
# up to 1.2x at full term: a level pad 23 square (the block and the bolster's inner slope, collision()) takes the square
# body box of a 1.2x adult whose term belly makes it 22.45 wide, and the bolster's steep inner side lies 11.1 out at body
# height, so every adult sits down on the pad with the bolster below its middle (a full-term 1.2x adult presses about 2
# into the bolster at the diagonals; NestResting.RIM_PRESS). Across the north (-z) entrance the bolster dips to a low
# rolled lip (U notch).
NEST_SEGS = 32
NEST_ROLL = [(12.8, 0.0, 0), (13.2, 1.0, 1), (13.3, 2.2, 2), (13.1, 3.4, 3), (12.75, 4.4, 4), (12.3, 5.05, 5), (11.85, 5.25, 6),
             (11.5, 4.85, 7), (11.3, 4.0, 8), (11.1, 3.0, 9), (10.75, 2.1, 10), (10.1, 1.6, 11)]
NEST_CUSHION = [(10.1, 1.6), (7.4, 1.7), (3.7, 1.78)]
NEST_CREST = 11.85
NEST_OUTER = 13.3          # outer radius of the round roll (NEST_ROLL's widest row)
NEST_PAD = 1.75            # collision top of the level pad over the whole block square
NEST_LEVEL = 11.5          # the level pad reaches this far on a round side (block and the roll's inner slope)

# Fitted against blocks (life/PetNestBlock): a side whose neighbouring block reaches into the overhang band is flattened so
# nothing pokes into that block. The roll keeps its height and order of rows but runs straight along the block face (outer
# edge at NEST_FLAT, the block face) with NEST_KEEP of its depth, pressed thin like a soft roll pushed against a wall (so an
# adult still lies between two walls of a one-block alcove);
# corners between a flat and a round side round off with radius 8, corners between two flat sides with radius 3. Masks are in
# the nest's own frame, entrance to -z: bit 1 north (entrance), 2 east, 4 south (back), 8 west; the block rotates them.
NEST_FLAT = 8.0
NEST_KEEP = .3
NEST_CORNER = {0: NEST_OUTER, 1: 8.0, 2: 3.0}


def nest_walls(mask):
    return {side: bool(mask >> i & 1) for i, side in enumerate('nesw')}


def nest_outline(mask):
    walled = nest_walls(mask)
    extents = {side: NEST_FLAT if walled[side] else NEST_OUTER for side in 'nesw'}

    def corner(sx, sz):
        wx, wz = walled['e' if sx > 0 else 'w'], walled['s' if sz > 0 else 'n']
        return NEST_CORNER[wx + wz], NEST_KEEP if wx else 1.0, NEST_KEEP if wz else 1.0
    return SideOutline(extents, corner, arc=NEST_SEGS // 4)


def nest_notch(x, z, y, o, floor=1.6, lip=2.8, top=5.25):
    """U-shaped entrance: within about 35 degrees of north the bolster sinks to a low rolled lip; the outer half (rows from
    the crest outward) scales toward the ground, the inner half toward the pad, meeting at the crest."""
    r = math.hypot(x, z)
    c = max(0.0, -z / r) if r > 1e-6 else 0.0
    w = smoothstep(.74, .89, c)
    if o >= NEST_CREST - NEST_OUTER:
        return y * (1 - (1 - lip / top) * w)
    return floor + (y - floor) * (1 - (1 - (lip - floor) / (top - floor)) * w) if y > floor else y


# 2x2 woven family nest (large_nests.py exports it): a rounded-square basket 32 across (two blocks, centred on its block)
# with a wicker wall 1.1 thick and 6 high at the back and sides, braided top, darker inside; the +z front opens down to
# the floor for |x| < 6 and climbs back to full height by |x| 12. Inside lies a quilted pad (29.8 across) with a stitched,
# rolled hem: two adults up to 1.1x lie side by side with four newborns, the wall below their middles.
# Collision follows the visible wall and pad (see collision()).
LARGE = Outline(16.0, 16.0, 7.0, arc=6, step=4.0)
LARGE_WALL = [(0.0, 0.0, 0), (0.0, 1.3, 1.5), (0.0, 2.6, 3), (0.0, 3.9, 4.5), (0.0, 5.2, 6), (-.2, 5.75, 6.6),
              (-.65, 6.0, 7.3), (-1.0, 5.75, 8), (-1.1, 5.2, 8.7), (-1.1, 3.1, 9.8), (-1.1, 1.0, 11)]
LARGE_HEM = [(-1.25, 1.0, 0), (-1.4, 1.45, 1), (-1.75, 1.72, 2), (-2.3, 1.8, 3)]
LARGE_PAD = [(-2.3, 1.8), (-3.5, 1.76), (-6.0, 1.72)]


def large_front(x, z, y, floor=1.0):
    if z <= 0 or y <= floor:
        return y
    return floor + (y - floor) * smoothstep(6.0, 12.0, abs(x))


def pet_nest(mask=0):
    """The one-block bed's envelope with the sides in `mask` flattened against neighbouring blocks (nest frame, entrance -z)."""
    b = Build(f'pet_nest_envelope_{mask}')
    o = nest_outline(mask)
    part = b.part('nest')
    profile = [(r - NEST_OUTER, y, v) for r, y, v in NEST_ROLL]
    revolve(b, part, o, profile, 'roll', canvas(72, 11, '#808080'), smooth=range(1, 11), ymap=nest_notch, offset_ymap=True,
            ref=12.5 - NEST_OUTER)
    nest_pad(b, part, o, canvas(22, 22, '#808080'))
    cap(b, part, o, [(12.8 - NEST_OUTER, 0.0)], 'under', BLANK, down=True, centre=0.0)
    return b.finish()


def nest_pad(b, part, outline, img, centre=1.8):
    """The pad: its edge ring where the roll tucks in, inner rings shrunk toward the middle (a faint dome)."""
    w, h = img.size
    origin = b.region('cushion', img)
    edge = [outline.at(s, NEST_CUSHION[0][0] - NEST_OUTER) for s in outline.samples]
    P = [[[x * r / NEST_CUSHION[0][0], y, z * r / NEST_CUSHION[0][0]] for x, z in edge] for r, y in NEST_CUSHION]
    P.append([[0.0, centre, 0.0] for _ in edge])
    UV = [[(p[0] + w / 2, p[2] + h / 2) for p in row] + [(row[0][0] + w / 2, row[0][2] + h / 2)] for row in P]
    grid(b, part, P, UV, origin, True, lambda i, j: [0, 1, 0], smooth_rows=set(range(1, len(P) - 1)))


def large_yukkuri_nest():
    """The 2x2 woven nest's envelope."""
    b = Build('large_yukkuri_nest_envelope')
    part = b.part('large_nest')
    revolve(b, part, LARGE, LARGE_WALL, 'wall', canvas(120, 11, '#808080'), smooth=(5, 6, 7, 8), ymap=large_front, ref=-.6)
    revolve(b, part, LARGE, LARGE_HEM, 'hem', canvas(108, 3, '#808080'), smooth=(1, 2), ref=-1.7)
    cap(b, part, LARGE, LARGE_PAD, 'pad', canvas(32, 32, '#808080'), centre=1.7)
    cap(b, part, LARGE, [(-1.1, 1.0)], 'floor', BLANK, centre=1.0)
    cap(b, part, LARGE, [(0.0, 0.0)], 'under', BLANK, down=True, centre=0.0)
    return b.finish()


# Collision shells that follow the visible nests (life/CareBlock reads them): top-surface heightmaps of the meshes over
# square cells, rounded up so a body rests on or just above the visible surface, merged into equal-height boxes.
# The one-block bed's level pad reaches NEST_LEVEL (11.5) on a round side: a 23 square on the round bed (the block and 3.5
# beyond each side, clipped to the outline grown by 0.45, radius 13.75 there, so no pad reaches past the visible roll at the
# diagonals): a body box resting anywhere inside it touches only the pad, never the roll, so an adult (to 1.2x at full term)
# sits down in the nest instead of hanging on the rim; the roll keeps its collision outside the pad (NestResting keeps
# resting bodies inside it). A flattened side keeps NEST_KEEP of that reach inside its flat roll (7.46 from the centre).
# Every grade has the same shape, so the shells are per fitted mask: pet_nest_<mask> (mask 0 = round).
NEST_SHELL = (13.5, .5, .5)                 # half extent, cell, step


def nest_level(mask):
    """Per side, how far the level pad reaches from the block centre (nest frame)."""
    walled = nest_walls(mask)
    return {side: NEST_FLAT - (NEST_OUTER - NEST_LEVEL) * NEST_KEEP if walled[side] else NEST_LEVEL for side in 'nesw'}


def nest_inside(mask, x, z, grow):
    """Whether (x, z) lies inside the fitted outline grown by `grow` (scaled like the roll on flattened sides)."""
    walled = nest_walls(mask)
    sx, sz = (1 if x >= 0 else -1), (1 if z >= 0 else -1)
    wx, wz = walled['e' if sx > 0 else 'w'], walled['s' if sz > 0 else 'n']
    radius = NEST_CORNER[wx + wz]
    ex, ez = (NEST_FLAT if wx else NEST_OUTER), (NEST_FLAT if wz else NEST_OUTER)
    qx, qz = sx * x - (ex - radius), sz * z - (ez - radius)
    d = math.hypot(max(qx, 0), max(qz, 0)) + min(max(qx, qz), 0) - radius
    return d <= grow * (NEST_KEEP if (wx if qx >= qz else wz) else 1.0)


def collision():
    out = {'format': 1, 'generator': 'standards/yukkuri-library/drafts/care-props/placed.py',
           'units': 'boxes [x0, z0, x1, z1, top] in 1/16 block from the footprint centre (block centre), bottom 0; '
                    'pet_nest_<mask>: the one-block beds, sides flattened per mask (nest frame, entrance -z; bit 1 n, 2 e, 4 s, 8 w)'}
    half, cell, step = NEST_SHELL
    for mask in range(16):
        hm = heightmap(pet_nest(mask), half, cell, step=step)
        level = nest_level(mask)
        for k, row in enumerate(hm):
            for i in range(len(row)):
                x0, z0 = -half + i * cell, -half + k * cell
                if (-level['w'] <= x0 and x0 + cell <= level['e'] and -level['n'] <= z0 and z0 + cell <= level['s']
                        and nest_inside(mask, x0 + cell / 2, z0 + cell / 2, .45)):
                    row[i] = NEST_PAD
        boxes = collision_boxes(hm, half, cell)
        out[f'pet_nest_{mask}'] = {'cell': cell, 'top': max(b[4] for b in boxes), 'boxes': boxes}
    hm = heightmap(large_yukkuri_nest(), 16.0, .25, step=.25)
    boxes = collision_boxes(hm, 16.0, .25)
    out['large_yukkuri_nest'] = {'cell': .25, 'top': max(b[4] for b in boxes), 'boxes': boxes}
    return out
