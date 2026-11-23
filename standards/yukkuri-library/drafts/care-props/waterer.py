"""The round waterer and its water, the bowl's sibling for drinking.

A glazed ceramic saucer revolved round a 16-sided circle (shapes.py), 14 across and 2 high so the `drink` clip's low
mouth meets the rim (the bowl is 3), with a blue outer wall where the bowl has its red lip band. A cork collar stands on
the floor, 6 across, and carries an inverted round glass jar, 6 across and 9 high, its top just under y 12: lit bottom
and shoulder frames, clear panes with vertical glints and nothing drawn on its inside. waterer_water is the water alone,
drawn inside the waterer like a bowl fill: one round tank layer per portion (tank_u1 at the bottom to tank_u8, units 1-8)
and the saucer's water surface (dish_water, unit 1), so drawing units 1..k shows a jar k layers full over a wet saucer
and drawing none shows it dry. Footprint, height, rim contact y 2 and the block shape stay those of the waterer.
"""
from shapes import Build, CIRCLE, revolve, cap, canvas, put, grain, top_view
from placed import CREAM, WHITE, SKY

# The icon ramps of the bottles and the shop sign (item-icons/draw.py, supplies.py).
GLASS = ['#557fae', '#8bb0d4', '#b4cfe9', '#dbeaf7']
CORK = ['#7f3a1d', '#a65231', '#cf7752', '#e5a07c']
WATER = ['#1d4a7a', '#2f6fa8', '#4b93cc', '#7cb9e6', '#bfe0f7']

SEGS = 16
SAUCER_FLOOR_Y = 1.0
SAUCER_FLOOR_R = 4.65
SAUCER_RIM_Y = 2.0
SAUCER = [(5.3, 0.0, 7), (5.7, .3, 6), (6.2, .9, 5), (6.7, 1.5, 4.5), (7.0, 1.8, 4.5),
          (6.9, 2.0, 3), (5.85, 2.0, 2), (5.5, 1.55, 1), (5.0, 1.05, .6), (SAUCER_FLOOR_R, 1.0, 0)]
COLLAR_R = 2.9
JAR_R = 2.9
JAR_Y, JAR_ROWS = 3, 8
WATER_R = 2.0


def saucer_band():
    """Blue outside, warm-white inside and a bright rim top; the foot is the darkest contact line."""
    img = canvas(48, 8)
    rows = (WHITE[2], WHITE[3], WHITE[3], WHITE[4], SKY[2], SKY[2], CREAM[3], CREAM[1])
    for y, colour in enumerate(rows):
        for x in range(48):
            put(img, x, y, colour)
    grain(img, WHITE, 'saucer-in', .08, rows=range(0, 2))
    grain(img, SKY, 'saucer-sky', .1, rows=range(4, 6))
    grain(img, CREAM, 'saucer-foot', .1, rows=range(6, 8))
    return img


def saucer_floor():
    img = top_view(16, lambda x, z, r: CREAM[4] if r < SAUCER_FLOOR_R else CREAM[2])
    return grain(img, CREAM, 'saucer-floor', .08)


def cork_band():
    img = canvas(16, 4)
    for x in range(16):
        put(img, x, 0, CORK[1])
        put(img, x, 1, CORK[1] if x % 5 == 2 else CORK[2])
        put(img, x, 2, CORK[2])
        put(img, x, 3, CORK[3])
    grain(img, CORK, 'cork', .18, rows=range(1, 3))
    return img


def cork_top():
    img = top_view(8, lambda x, z, r: CORK[3] if (x * 3 + z * 5) % 7 else CORK[2])
    return grain(img, CORK, 'cork-top', .14)


def glass_band():
    """Bottom frame and lit shoulder, clear panes between with three vertical glints."""
    img = canvas(48, 5)
    for y, colour in enumerate((GLASS[1], None, GLASS[3], GLASS[2], GLASS[3])):
        if colour is None:
            continue
        for x in range(48):
            put(img, x, y, colour)
    for x in (4, 5, 6, 29, 30, 31):
        put(img, x, 1, WHITE[4])
    for x in (3, 32):
        put(img, x, 1, GLASS[3])
    grain(img, GLASS, 'jar-shoulder', .12, rows=range(3, 4))
    return img


def glass_roof():
    img = top_view(6, lambda x, z, r: GLASS[3] if r < 2.3 else GLASS[1])
    put(img, 2, 2, WHITE[4])
    return grain(img, GLASS, 'jar-roof', .12)


def water_side():
    img = canvas(16, 2)
    for x in range(16):
        put(img, x, 0, WATER[1])
        put(img, x, 1, WATER[3] if x % 7 == 3 else WATER[2])
    return img


def water_surface(size, key, ripples):
    img = top_view(size, lambda x, z, r: WATER[4] if ripples(x, z) else WATER[3])
    return grain(img, WATER, key, .1)


def waterer():
    b = Build('waterer', square=True)
    o = CIRCLE(SEGS)
    dish = b.part('dish')
    revolve(b, dish, o, SAUCER, 'saucer', saucer_band(), smooth=(2, 3, 4, 7, 8), ref=6.3, facet=True)
    cap(b, dish, o, [(SAUCER_FLOOR_R, SAUCER_FLOOR_Y)], 'saucer_floor', saucer_floor(), centre=SAUCER_FLOOR_Y)
    cork = b.part('collar')
    revolve(b, cork, o, [(COLLAR_R, 1.0, 0), (COLLAR_R, 2.6, 2), (COLLAR_R - .05, 3.0, 3)], 'cork', cork_band(),
            smooth=(1,), ref=COLLAR_R)
    cap(b, cork, o, [(COLLAR_R - .05, 3.0)], 'cork_top', cork_top(), centre=3.0)
    jar = b.part('jar')
    revolve(b, jar, o, [(JAR_R, 3.0, 0), (JAR_R, 3.35, 1.5), (JAR_R, 10.65, 1.5), (JAR_R, 11.0, 2),
                        (2.8, 11.35, 3), (2.3, 11.7, 4)], 'glass', glass_band(), smooth=(1, 2, 3, 4), ref=JAR_R)
    cap(b, jar, o, [(2.3, 11.7)], 'glass_roof', glass_roof(), centre=11.9)
    return b.finish()


def waterer_water():
    b = Build('waterer_water', square=True)
    o = CIRCLE(SEGS)
    side, surface = water_side(), water_surface(10, 'dish-top', lambda x, z: (x * 3 + z * 7) % 9 == 0)
    revolve(b, b.part('dish_water', 1), o, [(SAUCER_FLOOR_R - .05, 1.0, 0), (SAUCER_FLOOR_R - .05, 2.0, 1)],
            'water_side', side, ref=SAUCER_FLOOR_R)
    cap(b, b.part('dish_water', 1), o, [(SAUCER_FLOOR_R - .05, 2.0)], 'dish_top', surface, centre=2.0)
    tank_side = water_side()
    tank_top = water_surface(5, 'tank_top', lambda x, z: (x * 5 + z * 3) % 4 == 0)
    for k in range(1, JAR_ROWS + 1):
        part = b.part(f'tank_u{k}', k)
        y = JAR_Y + k - 1
        revolve(b, part, o, [(WATER_R, y, 0), (WATER_R, y + 1, 1)], 'water_side', tank_side, ref=WATER_R)
        cap(b, part, o, [(WATER_R, y + 1)], 'tank_top', tank_top, centre=y + 1)
    return b.finish()


PROPS = {'waterer': waterer, 'waterer_water': waterer_water}
