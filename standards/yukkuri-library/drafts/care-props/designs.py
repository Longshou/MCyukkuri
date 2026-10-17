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


# ---------------------------------------------------------------------------------------------------------
# Forage pile (储粮堆): the wild stock of a nest. A leaf bed always shows; heaps of gathered grass, wheat, berries and
# nuts grow in four stages (unit 1..4) as the real stock grows. Grass and wheat reuse the vanilla sprites (referenced,
# not copied, tinted like grass); berries and nuts sample the shared care-prop palette.
PILE_RADIUS = (3.4, 4.4, 5.2, 5.8)        # mound footprint radius per stage
PILE_PEAK = (2.4, 4.0, 5.6, 7.2)          # mound height per stage
PILE_BED_TOP = .9


def pile_surface(r, stage):
    """Height of the heap surface at radius r for a stage (1..4), above the leaf bed."""
    R, H = PILE_RADIUS[stage - 1], PILE_PEAK[stage - 1]
    return PILE_BED_TOP + (H * (1 - (r / R) ** 2) ** .8 if r < R else 0.0)


def forage_pile():
    p = Prop('forage_pile')
    rng = deterministic(731)
    # -- leaf bed: shallow dish of overlapping leaves in the confirmed wild-shelter leaf ramp --------------
    bed = p.part('leaf_bed')
    seg = 12
    for k in range(seg):
        a0, a1 = math.tau * k / seg, math.tau * (k + 1) / seg
        tone = C('leaf', 1 if k % 2 else 2)
        inner = [[0, PILE_BED_TOP, 0], [5.6 * math.cos(a0), .55, 5.6 * math.sin(a0)], [5.6 * math.cos(a1), .55, 5.6 * math.sin(a1)]]
        p.tri(bed, inner, [[0, 1, 0]] * 3, tone, None)
        rim = [[5.6 * math.cos(a0), .55, 5.6 * math.sin(a0)], [6.9 * math.cos(a0), .2, 6.9 * math.sin(a0)],
               [6.9 * math.cos(a1), .2, 6.9 * math.sin(a1)], [5.6 * math.cos(a1), .55, 5.6 * math.sin(a1)]]
        p.quad(bed, rim, C('leaf', 0 if k % 3 == 0 else 1), None, normal=[0, 1, 0])
    leaves = p.part('leaf_fans')
    for k in range(9):
        ang = math.tau * k / 9 + .2
        reach = 4.4 + (k % 3) * .5
        cx, cz = reach * math.cos(ang), reach * math.sin(ang)
        length, width = 4.2, 2.3
        ux, uz = math.cos(ang), math.sin(ang)
        vx, vz = -uz, ux
        base_y = .62 + (k % 2) * .06
        pts = [[cx - ux * length / 2, base_y, cz - uz * length / 2],
               [cx + vx * width / 2, base_y + .08, cz + vz * width / 2],
               [cx + ux * length / 2, base_y + .28, cz + uz * length / 2],
               [cx - vx * width / 2, base_y + .08, cz - vz * width / 2]]
        p.quad(leaves, pts, C('leaf', 3 if k % 3 == 0 else 2), None, double=True, normal=[0, 1, 0])
    # -- heap, stage by stage ------------------------------------------------------------------------------
    tufts = (7, 8, 9, 10)
    wheat = (0, 4, 5, 5)
    berries = (3, 3, 3, 3)
    nuts = (0, 2, 2, 2)
    for stage in range(1, 5):
        R = PILE_RADIUS[stage - 1]
        inner_r = 0 if stage == 1 else PILE_RADIUS[stage - 2] * .35
        grass = p.part(f'cut_grass_{stage}', stage, texture='minecraft:textures/block/short_grass.png', tint=[145, 189, 89])
        for i in range(tufts[stage - 1]):
            r = inner_r + math.sqrt((i + .5) / tufts[stage - 1]) * (R - inner_r)
            ang = (i * 2.399963 + stage * .9)
            cx, cz = r * math.cos(ang), r * math.sin(ang)
            y = pile_surface(r, stage) - .1
            yaw = rng.uniform(0, math.pi)
            width, height = 3.2, 2.0 + rng.uniform(0, .5) + stage * .15
            lean = (.5 + .5 * r / R) * .7
            for off in (0, math.pi / 2):
                dx, dz = math.cos(yaw + off) * width / 2, math.sin(yaw + off) * width / 2
                tx, tz = math.cos(ang) * lean, math.sin(ang) * lean
                pts = [[cx - dx, y, cz - dz], [cx + dx, y, cz + dz], [cx + dx + tx, y + height, cz + dz + tz], [cx - dx + tx, y + height, cz - dz + tz]]
                uvs = [(0, p.H), (p.W, p.H), (p.W, 0), (0, 0)]
                p.quad(grass, pts, None, uvs=uvs, double=True, grid=.5)
        if wheat[stage - 1]:
            ears = p.part(f'laid_wheat_{stage}', stage, texture='minecraft:textures/item/wheat.png', tint=[255, 255, 255])
            for i in range(wheat[stage - 1]):
                r = (.15 + .75 * (i + .5) / wheat[stage - 1]) * R
                ang = i * 2.399963 + stage * 1.7
                cx, cz = r * math.cos(ang), r * math.sin(ang)
                y = pile_surface(r, stage) + .35
                yaw = rng.uniform(0, math.tau)
                half = 1.35
                pts = []
                for sx, sz in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                    px, pz = sx * half, sz * half
                    pts.append([cx + px * math.cos(yaw) - pz * math.sin(yaw), y + rng.uniform(-.06, .06) + px * .05,
                                cz + px * math.sin(yaw) + pz * math.cos(yaw)])
                uvs = [(0, p.H), (p.W, p.H), (p.W, 0), (0, 0)]
                p.quad(ears, pts, None, uvs=uvs, double=True, normal=[0, 1, 0])
        fruit = p.part(f'berries_{stage}', stage)
        for i in range(berries[stage - 1]):
            r = (.1 + .85 * ((i * .618 + stage * .27) % 1)) * R
            ang = i * 2.399963 + stage * 2.1 + 1.0
            cx, cz = r * math.cos(ang), r * math.sin(ang)
            y = pile_surface(r, stage) + .35
            def berry(n):
                return C('berry', 2 if n[1] > .55 else 1 if n[1] > -.2 else 0)
            p.blob(fruit, [cx, y, cz], [.58, .52, .58], berry, rng, .08, tilt=0, smooth=True)
        if nuts[stage - 1]:
            kernels = p.part(f'nuts_{stage}', stage)
            for i in range(nuts[stage - 1]):
                r = (.2 + .7 * ((i * .41 + stage * .33) % 1)) * R
                ang = i * 2.399963 + stage * 0.7 + 2.4
                cx, cz = r * math.cos(ang), r * math.sin(ang)
                y = pile_surface(r, stage) + .3
                def nut(n):
                    return C('wood', 3 if n[1] > .55 else 2 if n[1] > -.2 else 0)
                p.blob(kernels, [cx, y, cz], [.5, .55, .5], nut, rng, .1, tilt=rng.uniform(-.4, .4), smooth=True)
    return p
