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
        return FLOOR + (.5 + .3 * k) * (1 - (r / R) ** 2) ** .75
    grid = [(i * 1.45 - 5.8 + (j % 2) * .72, j * 1.25 - 5.0) for j in range(9) for i in range(9)]
    for k in range(1, 9):
        spots = list(grid)
        rng.shuffle(spots)
        count = 0
        for (gx, gz) in spots:
            x, z = gx + rng.uniform(-.35, .35), gz + rng.uniform(-.35, .35)
            r = math.hypot(x, z)
            goal = target(k, r)
            if goal - surface(x, z) < .5:
                continue
            ry = .62 + rng.uniform(0, .12)
            y = max(FLOOR + ry * .8, goal - ry * .55)
            if r + .95 > wall_radius(y):
                continue
            placed.append((x, y, z, k, .98 + rng.uniform(-.1, .14), ry, .92 + rng.uniform(-.08, .14)))
            count += 1
            if count >= 8:
                break
    return placed


def add_kibble(p, placed):
    rng = deterministic(88)
    for index, (x, y, z, k, rx, ry, rz) in enumerate(placed):
        part = p.part(f'kibble_{index}', k)
        def pick(n):
            if n[1] > .72 and rng.random() < .35:
                return C('pellet', 3)
            if n[1] < -.3:
                return C('pellet', 0)
            return C('pellet', 2 if (n[0] + n[2]) > 0 else 1)
        p.blob(part, [x, y, z], [rx, ry, rz], pick, rng, .1, tilt=rng.uniform(-.3, .3))


def food_bowl():
    p = Prop('food_bowl')
    bowl_parts(p)
    return p


def food_bowl_regular():
    p = Prop('food_bowl_regular')
    bowl_parts(p)
    add_kibble(p, kibble_layout())
    return p


def food_bowl_wheat():
    """Laid wheat ears (vanilla item sprite, referenced not copied); one portion adds eight sprites."""
    p = Prop('food_bowl_wheat')
    bowl_parts(p)
    rng = deterministic(441)
    n = 0
    for k in range(1, 9):
        part = p.part(f'laid_wheat_{k}', k, texture='minecraft:textures/item/wheat.png', tint=[255, 255, 255])
        y = FLOOR + .25 + .4 * k
        for i in range(8):
            ang = n * 2.399963 + k * .7
            n += 1
            rr = min(wall_radius(y) - 1.7, 1.0 + math.sqrt((i + 1) / 8.0) * (2.4 + .1 * k))
            cx, cz = rr * math.cos(ang), rr * math.sin(ang)
            yaw = rng.uniform(0, math.tau)
            half = 1.3
            pts = []
            for sx, sz in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                px, pz = sx * half, sz * half
                pts.append([cx + px * math.cos(yaw) - pz * math.sin(yaw), y + rng.uniform(-.08, .08) + (px * .04), cz + px * math.sin(yaw) + pz * math.cos(yaw)])
            uvs = [(0, p.H), (p.W, p.H), (p.W, 0), (0, 0)]
            p.quad(part, pts, None, uvs=uvs, double=True, normal=[0, 1, 0])
    return p


def food_bowl_forage():
    """Cut grass tufts as crossed planes (vanilla short-grass sprite, tinted like grass); one portion adds six tufts."""
    p = Prop('food_bowl_forage')
    bowl_parts(p)
    rng = deterministic(552)
    n = 0
    for k in range(1, 9):
        part = p.part(f'cut_grass_{k}', k, texture='minecraft:textures/block/short_grass.png', tint=[145, 189, 89])
        y = FLOOR + .15 + .3 * k
        for i in range(6):
            ang = n * 2.399963 + k * .5
            n += 1
            rr = min(wall_radius(y) - 1.8, 0.8 + math.sqrt((i + 1) / 6.0) * (2.5 + .12 * k))
            cx, cz = rr * math.cos(ang), rr * math.sin(ang)
            yaw = rng.uniform(0, math.pi)
            width, height = 3.0, 1.8 + rng.uniform(0, .3)
            for off in (0, math.pi / 2):
                dx, dz = math.cos(yaw + off) * width / 2, math.sin(yaw + off) * width / 2
                pts = [[cx - dx, y, cz - dz], [cx + dx, y, cz + dz], [cx + dx, y + height, cz + dz], [cx - dx, y + height, cz - dz]]
                uvs = [(0, p.H), (p.W, p.H), (p.W, 0), (0, 0)]
                p.quad(part, pts, None, uvs=uvs, double=True, grid=.5)
    return p
