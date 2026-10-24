"""Floor mats: thin boxes with a tiled 1-texel-per-unit top. The ceramic and covered toilets are in placed.py."""
from kit import Prop, Planar, colour, fill, deterministic

C = lambda ramp, step: (ramp, step)
MAT_AT = (16, 0)          # 16x16 texel mat top


# ---- mats ----------------------------------------------------------------------------------------------
def mat(name, thickness, side, painter):
    p = Prop(name, wide=True)
    ox, oy = MAT_AT
    painter(p.img, ox, oy)
    top = Planar(ox + 8, oy + 8, ax=0, ay=2, sx=1, sy=1)
    part = p.part('mat')
    p.box(part, [-8, 0, -8], [8, thickness, 8], {'*': side, 'top': top, 'bottom': side}, grid=.5)
    return p


def paint_newspaper(img, ox, oy):
    fill(img, ox, oy, 16, 16, C('paper', 3))
    fill(img, ox + 1, oy + 1, 14, 2, C('wood', 1))                       # masthead
    fill(img, ox + 1, oy + 4, 6, 5, C('paper', 1))                        # photo
    for y in (4, 6, 8):
        fill(img, ox + 8, oy + y, 7, 1, C('wood', 2))
    for col in (1, 8):
        for y in range(10, 15, 2):
            fill(img, ox + col, oy + y, 6, 1, C('wood', 2))


def paint_soil(img, ox, oy):
    rng = deterministic(5150)
    fill(img, ox, oy, 16, 16, C('wood', 1))
    for _ in range(34):
        img.putpixel((ox + rng.randrange(16), oy + rng.randrange(16)), colour('wood', rng.choice([0, 0, 2])) + (255,))
    for _ in range(4):
        x, y = rng.randrange(14), rng.randrange(15)
        for dx, dy in ((0, 0), (1, 0)):
            img.putpixel((ox + x + dx, oy + y + dy), colour('wood', 3) + (255,))


def paint_sand(img, ox, oy):
    rng = deterministic(6061)
    fill(img, ox, oy, 16, 16, C('cardboard', 2))
    for _ in range(30):
        img.putpixel((ox + rng.randrange(16), oy + rng.randrange(16)), colour('cardboard', rng.choice([1, 3, 3])) + (255,))
    for _ in range(10):
        img.putpixel((ox + rng.randrange(16), oy + rng.randrange(16)), colour('wood', 0) + (255,))


def newspaper_mat():
    return mat('newspaper_mat', .5, C('paper', 0), paint_newspaper)


def soil_mat():
    return mat('soil_mat', 1, C('wood', 0), paint_soil)


def sandpaper_mat():
    return mat('sandpaper_mat', .5, C('cardboard', 0), paint_sand)
