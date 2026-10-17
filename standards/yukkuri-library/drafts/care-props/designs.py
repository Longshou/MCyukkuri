"""Care-prop designs: pet-shop goods (cream ceramic, mint plastic, strawberry fabric, kraft paper) and wild materials.

Every design returns a kit.Prop. Colours are (ramp, step) swatches of art/props/palette.json.
Units are 1/16 block; origin at the footprint centre; +Z is the side the shop icon faces by default is -Z.
"""
import math
from kit import (Prop, Band, Planar, RAMPS, colour, deterministic, fill, paint, ring_wall, rrect_points, unit, add, sub, mul, cross, dot)

C = lambda ramp, step: (ramp, step)

# ---------------------------------------------------------------------------------------------------------
# Food bowl: cream glazed ceramic, mint band, darker foot ring, darker inner glaze so the fill reads.
FLOOR = 1.5          # inside floor height
RIM = 3.0            # rim top height


def wall_radius(y):
    """Inner wall radius of the bowl at height y (used to keep fills inside)."""
    if y <= 1.5:
        return 4.8
    if y <= 2.0:
        return 4.8 + (y - 1.5) * 1.4
    return 5.5 + (y - 2.0) * .5


def bowl_parts(p):
    profile = [(0, .5), (3.5, .5), (3.5, 0), (5, 0), (5, 1), (6, 1.5), (6.5, 2), (7, 2.5), (7, 3), (6, 3), (5.5, 2), (5, 1.5), (0, 1.5)]
    colors = [C('ceramic', 0), C('ceramic', 0), C('ceramic', 0), C('ceramic', 0), C('ceramic', 1), C('mint', 2), C('mint', 2),
              C('ceramic', 3), C('ceramic', 3), C('ceramic', 1), C('ceramic', 0), C('ceramic', 1)]
    part = p.part('glazed_bowl')
    for i in range(len(profile) - 1):
        p.lathe(part, profile[i:i + 2], colors[i], seg=16, smooth='around', grid=.5)
    return part


def kibble_layout():
    """Chunky kibble clusters. Portion k raises a mound of radius R_k and peak P_k; each new cluster sits where the
    mound surface built so far is still lower than the target surface."""
    rng = deterministic(1207)
    placed = []                                       # (x, y, z, portion, rx, ry, rz)
    def surface(x, z):
        top = FLOOR
        for (px, py, pz, _, rx, ry, rz) in placed:
            d2 = ((x - px) / rx) ** 2 + ((z - pz) / rz) ** 2
            if d2 < 1:
                top = max(top, py + ry * math.sqrt(1 - d2))
        return top
    def target(k, r):
        R = 2.3 + .46 * k
        if r >= R:
            return FLOOR
        return FLOOR + (.6 + .33 * k) * (1 - (r / R) ** 2) ** .7
    grid = [(i * 1.0 - 5.6 + (j % 2) * .5, j * .9 - 5.0) for j in range(12) for i in range(12)]
    for k in range(1, 9):
        spots = list(grid)
        rng.shuffle(spots)
        count = 0
        for (gx, gz) in spots:
            x, z = gx + rng.uniform(-.25, .25), gz + rng.uniform(-.25, .25)
            r = math.hypot(x, z)
            goal = target(k, r)
            if goal - surface(x, z) < .4:
                continue
            ry = .4 + rng.uniform(0, .08)
            y = max(FLOOR + ry * .8, goal - ry * .5)
            if r + .7 > wall_radius(y):
                continue
            placed.append((x, y, z, k, .62 + rng.uniform(-.06, .1), ry, .56 + rng.uniform(-.05, .1)))
            count += 1
            if count >= 12:
                break
    return placed


def add_kibble(p, placed):
    """Small rounded nuggets (smooth-shaded low-poly ellipsoids): top highlight step, warm sides, dark underside."""
    rng = deterministic(88)
    for index, (x, y, z, k, rx, ry, rz) in enumerate(placed):
        part = p.part(f'kibble_{index}', k)
        def pick(n):
            if n[1] > .6:
                return C('pellet', 3)
            if n[1] < -.3:
                return C('pellet', 1)
            return C('pellet', 2)
        p.blob(part, [x, y, z], [rx, ry, rz], pick, rng, .06, tilt=rng.uniform(-.3, .3), smooth=True)


def food_bowl():
    p = Prop('food_bowl')
    bowl_parts(p)
    return p


def food_bowl_regular():
    p = Prop('food_bowl_regular')
    bowl_parts(p)
    add_kibble(p, kibble_layout())
    return p


VANILLA_UV = [(0, 1), (1, 1), (1, 0), (0, 0)]       # whole external texture, v down


def sprite_quad(p, part, centre, size, yaw, tilt_x, tilt_z, lift=0.0, up=False):
    """A square sprite of a vanilla texture, lying (or standing, up=True) at `centre`, turned by yaw and slightly tilted."""
    cx, cy, cz = centre
    half = size / 2
    pts = []
    for sx, sz in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        a, b = sx * half, sz * half                      # in-plane coordinates
        if up:
            x, y, z = a, (b + half), 0.0                  # stands on its lower edge
        else:
            x, y, z = a, a * tilt_x + b * tilt_z, b
        pts.append([cx + x * math.cos(yaw) - z * math.sin(yaw), cy + y + lift, cz + x * math.sin(yaw) + z * math.cos(yaw)])
    p.quad(part, pts, None, uvs=VANILLA_UV, double=True, normal=[0, 1, 0])


def food_bowl_wheat():
    """A heap of vanilla wheat items (minecraft:textures/item/wheat.png, referenced not copied), laid at 0.375 unit per
    texel, two sprites per portion, each portion higher than the last."""
    p = Prop('food_bowl_wheat')
    bowl_parts(p)
    rng = deterministic(441)
    n = 0
    for k in range(1, 9):
        part = p.part(f'laid_wheat_{k}', k, texture='minecraft:textures/item/wheat.png')
        for i in range(2):
            y = FLOOR + .1 + .2 * (2 * (k - 1) + i)
            ang = n * 2.399963
            n += 1
            rr = min(.55 + .25 * k, wall_radius(y) - 3.6) if k > 1 else .5
            sprite_quad(p, part, (rr * math.cos(ang), y, rr * math.sin(ang)), 6.0, rng.uniform(0, math.tau), rng.uniform(-.12, .12), rng.uniform(-.12, .12))
    return p


def food_bowl_forage():
    """Vanilla short-grass sprites (minecraft:textures/block/short_grass.png, grass tint) as small crossed tufts at 0.375 unit
    per texel; four tufts per portion."""
    p = Prop('food_bowl_forage')
    bowl_parts(p)
    rng = deterministic(552)
    n = 0
    for k in range(1, 9):
        part = p.part(f'cut_grass_{k}', k, texture='minecraft:textures/block/short_grass.png', tint=[145, 189, 89])
        for i in range(4):
            ang = n * 2.399963 + k * .3
            n += 1
            rr = min(.8 + math.sqrt((i + 1) / 4.0) * (1.0 + .16 * k), wall_radius(FLOOR + 1) - 3.4)
            cx, cz = rr * math.cos(ang), rr * math.sin(ang)
            yaw = rng.uniform(0, math.pi)
            y = FLOOR + .05 + .22 * k
            for off in (0, math.pi / 2):
                sprite_quad(p, part, (cx, y, cz), 4.8, yaw + off, 0, 0, up=True)
    return p
