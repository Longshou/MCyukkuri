"""Artificial pet bed: the shop-sold round cushion. Self-built nests of wild yukkuri (leaf nests, the predator twig bowl) are in wild.py."""
import math
from kit import Prop, Band, Planar, colour, fill, paint, deterministic, unit

C = lambda ramp, step: (ramp, step)
QUILT_AT = (16, 0)          # 16x16 texel quilted cushion top (1 texel per unit)

# Real body sizes (runtime models, BEHAVIOR.md): standard adult 12.8 wide x 10.7 deep x 9.6 high, individual target up
# to 1.2x (15.4 wide), deibu x1.5, Reimu with ribbon 17.4 wide. The pet bed is an artificial cushion, so it may overhang
# the placement block. The current shallow nest is 20 wide; its low profile and entrance also define CareBlock collision.
BED_IN = 8.0
BED_FLOOR = 1.5             # inner surface; low shell/entrance profile matches CareBlock.nestShape


def yukkuri_nest():
    """Soft shallow fabric nest with a lowered entrance and a cream quilted hollow; no tall rolled wall."""
    p = Prop('yukkuri_nest', wide=True)
    ox, oy = QUILT_AT
    fill(p.img, ox, oy, 16, 16, C('paper', 2))
    for y in range(16):
        for x in range(16):
            if (x + y) % 5 == 0 or (x - y) % 5 == 0:
                p.img.putpixel((ox + x, oy + y), colour('paper', 1) + (255,))
    for (x, y) in ((7, 7), (8, 7), (7, 8), (8, 8)):
        p.img.putpixel((ox + x, oy + y), colour('berry', 2) + (255,))
    top = Planar(ox + 8, oy + 8, ax=0, ay=2, sx=.96, sy=.96)    # 16 texels across the 16.4 interior
    cushion = p.part('quilted_cushion')
    # Lower bowl shell, with a strawberry exterior and broad cream interior.
    p.lathe(cushion, [(0,0),(9.5,0),(10,.75)], C('berry',1), seg=32, smooth='both', grid=.125)
    radii = [10, 9.5, 8.8, 7.8, 0]
    loops = []
    for radius in radii:
        loop = []
        for j in range(32):
            angle = math.tau*j/32
            x, z = radius*math.cos(angle), radius*math.sin(angle)
            # The front opening is lower; gentle fabric gathers remain inside the physical low step.
            entrance = max(0, -math.sin(angle))**6
            height = {10:.75,9.5:2.4,8.8:3,7.8:1.5,0:1.5}[radius]
            if radius in (9.5,8.8): height -= entrance*1.15
            loop.append([x,height,z])
        loops.append(loop)
    p.skin(cushion,loops,Band(lambda i,j,c: C('berry',2) if i<2 else top),smooth='both',grid=.125)
    # Short contrasting stitch dashes explain the cloth construction without a raised piping wall.
    stitches=p.part('soft_seam_stitches')
    for j in range(24):
        a=math.tau*j/24
        entrance=max(0,-math.sin(a))**6
        y=2.91-entrance*1.15
        p.rod(stitches,[8.9*math.cos(a-.016),y,8.9*math.sin(a-.016)],
              [8.9*math.cos(a+.016),y,8.9*math.sin(a+.016)],.055,.055,seg=4,spec=C('paper',2),grid=None)
    return p


# Family-size pet bed on a 2x2 block footprint (32 x 32 units, origin at the footprint centre, entrance on +z / south).
# Room for two adults (12.8 wide each) and four 0.32 newborns lying wherever they settle; the floor marks no places. A thin
# woven bolster (1.25 deep, at most 3.0 high, < 0.2 block) leaves a 28.5 interior and drops to the 1.0 cushion floor across a
# 14-unit front opening; the rounded outline stays 0.5 inside the footprint edge.
LARGE_OUT = 15.5            # outer half-extent of the squircle rim
LARGE_RIM = 1.25            # bolster depth from the outer edge to the floor seam
LARGE_FLOOR = 1.0           # cushion floor top; flush with the entrance lip
LARGE_CREST = 3.0


def cushion_height(x, z, inner):
    """Soft uneven fabric: at most 0.2 above the floor in broad lumps, settling to the floor at the bolster seam."""
    edge = 1-min(1, ((x/inner)**4+(z/inner)**4)**.25)**4
    lumps = math.sin(.47*x+.31*z+.6)+math.sin(.38*z-.56*x+1.9)+.6*math.sin(.9*x+.83*z+4.1)
    return LARGE_FLOOR+edge*max(0, .1+.038*lumps)


def squircle(half, t, n=4.0):
    c, s = math.cos(t), math.sin(t)
    return (half*math.copysign(abs(c)**(2/n), c), half*math.copysign(abs(s)**(2/n), s))


def large_yukkuri_nest():
    """2x2 woven cushion bed: strawberry basket-weave bolster around a softly lumpy cream quilted floor and a low front
    opening; the floor is one continuous fabric with no marked sleeping places."""
    p = Prop('large_yukkuri_nest', wide=True)
    seg = 64
    weave = p.part('woven_bolster')
    # (inset from the outer edge, normal height, entrance height): ramp up from the ground to the floor at the opening.
    rows = [(0, 0, 0), (.1, 1.3, .45), (.3, 2.5, .8), (.55, LARGE_CREST, LARGE_FLOOR), (.8, 2.6, LARGE_FLOOR),
            (1.05, 1.5, LARGE_FLOOR), (LARGE_RIM, LARGE_FLOOR, LARGE_FLOOR)]
    loops = []
    for inset, high, low in rows:
        loop = []
        for j in range(seg):
            t = math.tau*j/seg
            e = max(0, math.sin(t))**6
            x, z = squircle(LARGE_OUT-inset, t)
            loop.append([x, high+(low-high)*e, z])
        loops.append(loop)
    # Over-under strands: adjacent cells alternate two fabric steps, the crest row is the lightest.
    p.skin(weave, loops, Band(lambda i, j, c: C('berry', 3 if i == 2 else 2 if (i+j) % 2 else 1)), smooth='both')
    floor = p.part('quilted_floor')
    inner = LARGE_OUT-LARGE_RIM
    floor_loops = [[(lambda x, z: [x, cushion_height(x, z, inner), z])(*squircle(r, math.tau*j/seg)) for j in range(seg)]
                   for r in (inner, inner*.86, inner*.72, inner*.58, inner*.44, inner*.3, inner*.16, 0)]
    p.skin(floor, floor_loops, C('paper', 2), smooth='both')
    # Quilting: a stitched square grid over the floor, clipped to the rounded rim; short dashes follow the lumps.
    stitches = p.part('quilt_stitches')
    for k in range(-2, 3):
        c = k*5.6
        span = inner*(1-(abs(c)/inner)**4)**.25 - .6
        n = max(2, round(2*span/1.6))
        for d in range(n):
            u0, u1 = -span+2*span*d/n, -span+2*span*(d+.6)/n
            for x0, z0, x1, z1 in ((c, u0, c, u1), (u0, c, u1, c)):
                p.rod(stitches, [x0, cushion_height(x0, z0, inner)+.04, z0], [x1, cushion_height(x1, z1, inner)+.04, z1],
                      .06, .06, seg=4, spec=C('paper', 1), grid=None)
    return p
