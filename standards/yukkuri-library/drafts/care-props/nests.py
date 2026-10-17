"""Artificial pet bed: the shop-sold round cushion. Self-built nests of wild yukkuri (leaf nests, the predator twig bowl) are in wild.py."""
import math
from kit import Prop, Band, Planar, colour, fill, paint, deterministic, unit

C = lambda ramp, step: (ramp, step)
QUILT_AT = (16, 0)          # 16x16 texel quilted cushion top (1 texel per unit)

# Real body sizes (runtime models, GROWTH_BALANCE.md): standard adult 12.8 wide x 10.7 deep x 9.6 high, individual target up
# to 1.2x (15.4 wide), deibu x1.5, Reimu with ribbon 17.4 wide. The pet bed is an artificial cushion, so it may overhang
# the placement block: interior 16.4 across (R 8.2), outer 24 across (R 12).
BED_IN = 8.2
BED_FLOOR = 2.0             # standing surface; matches the CareBlock collision height for yukkuri_nest


def yukkuri_nest():
    """Round pet cushion bed sized for adults: a thick rolled strawberry rim (about 4.5 high, outer diameter 24) around a
    quilted cream cushion with an interior 16.4 across, a seam ring and a cream piping."""
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
    profile = [(0, 0), (10.8, 0), (10.8, 1.3), (BED_IN, 2.2), (5, BED_FLOOR + .1), (2.5, BED_FLOOR), (0, BED_FLOOR - .05)]
    specs = [C('paper', 0), C('paper', 1), C('paper', 1), top, top, top]
    for i in range(len(profile) - 1):
        p.lathe(cushion, profile[i:i + 2], specs[i], seg=20, smooth='both' if i >= 3 else 'around', grid=.25)
    rim = p.part('rolled_rim')
    tone = [C('berry', 0), C('berry', 1), C('berry', 2), C('berry', 3), C('berry', 2), C('berry', 1)]
    p.torus(rim, [0, 2.55, 0], BED_IN + 1.75, 1.95, seg=20, tube=6, spec=Band(lambda i, j, c: tone[i]), grid=.25, smooth='both')
    seam = p.part('seam_ring')
    p.torus(seam, [0, BED_FLOOR + .1, 0], 6.4, .5, seg=20, tube=3, spec=C('berry', 1), grid=.25, smooth='both')
    piping = p.part('cream_piping')
    p.torus(piping, [0, 4.4, 0], BED_IN + 1.75, .5, seg=20, tube=3, spec=C('paper', 3), grid=.25, smooth='both')
    return p
