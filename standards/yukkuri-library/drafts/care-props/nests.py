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
