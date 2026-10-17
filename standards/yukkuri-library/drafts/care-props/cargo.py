"""Hat-boat provisions (帽船载粮): one thin picture layer lying on the hat's side brims, richer the more is stored.

The layer does not distinguish foods. HatCargoRenderer picks one of three tiers from the stored count (1-2 a little,
3-5 half full, 6-8 full) and draws only that tier (parts unit 1, 2, 3, rendered as an exclusive layer mask), flat on the
brim top: a few leaf pads with small coloured heaps in them, more pads and heaps, then every pad full with heaps stacked
two high. Nothing here is a seat or a collision shape; the server does not read it. Authored in HAT units (1 unit =
1/16 block at hull scale 1) in the hat's local frame (+z stern, brim top at y = 0). The right strip of side brim is
x 4.45..6.4 and the left x -6.0..-4.45; the captain's body fills |x| < 4.27 and the young stand on the front half
(z < -2), so the layer lies on z 0.2..5.7.
"""
import math
from kit import Prop, Band

C = lambda ramp, step: (ramp, step)

RIGHT_X, LEFT_X = 5.42, -5.22          # strip centres
RIGHT_R, LEFT_R = .97, .76             # leaf pad radii that stay inside the strips
LIFT = .04                             # the pad lies just above the brim top

# (side, z) of the pads of each tier
TIERS = {
    1: [(1, 1.6)],
    2: [(1, 1.2), (1, 3.5), (-1, 1.2), (-1, 3.5)],
    3: [(1, .9), (1, 2.9), (1, 4.9), (-1, .9), (-1, 2.9), (-1, 4.9)],
}
# goods lying in a pad: (dx, dz, radius, colour ramp); tier 3 stacks a second, smaller heap on the first
GOODS = [(-.3, -.2, .42, 'chili'), (.32, -.1, .38, 'gold'), (.0, .38, .36, 'wood'), (-.38, .3, .3, 'berry'), (.4, .38, .3, 'straw')]
COUNT = {1: 3, 2: 4, 3: 5}


def _pad(p, part, x, z, r, seed):
    p.lathe(part, [(0, LIFT), (r, LIFT), (r * 1.02, LIFT + .1), (r * .85, LIFT + .17), (0, LIFT + .19)],
            Band(lambda i, j, c, s=seed: C('leaf', (1, 2, 1, 3)[(s + j) % 4] if i >= 2 else 0)),
            center=(x, z), seg=10, squash=.9, phase=seed * .5, grid=None, smooth='both')


def _heap(p, part, x, z, r, ramp, base):
    p.lathe(part, [(0, base), (r, base), (r * .95, base + r * .45), (r * .6, base + r * .85), (0, base + r)],
            Band(lambda i, j, c: C(ramp, 3 if (i >= 3 and j % 3 == 0) else 2 if i >= 2 else 1 if i >= 1 else 0)),
            center=(x, z), seg=8, grid=None, smooth='both')


def provisions():
    p = Prop('hat_provisions', noise=False)
    for tier, pads in TIERS.items():
        mats = p.part(f'tier{tier}_pads', tier)
        goods = p.part(f'tier{tier}_goods', tier)
        for n, (side, z) in enumerate(pads):
            x, r = (RIGHT_X, RIGHT_R) if side > 0 else (LEFT_X, LEFT_R)
            _pad(p, mats, x, z, r, n + tier)
            scale = r / RIGHT_R
            for k in range(COUNT[tier]):
                dx, dz, gr, ramp = GOODS[(k + n) % len(GOODS)]
                _heap(p, goods, x + dx * scale, z + dz * scale, gr * scale, ramp, LIFT + .17)
                if tier == 3 and k < 3:   # full: a second heap sits on top of the first
                    dx2, dz2, gr2, ramp2 = GOODS[(k + n + 2) % len(GOODS)]
                    _heap(p, goods, x + dx * scale * .6, z + dz * scale * .6, gr2 * scale * .75, ramp2, LIFT + .17 + gr * scale * .6)
    return p


CARGO = {'hat_provisions': provisions}
