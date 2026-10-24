"""Pet-care shop goods drawn as 16x16 item icons: sweet candy, custard pudding, pet harness, swaddle cloth.

These four items use the plain item/generated sprite path (no PropItemRenderer registration), which is also what the mouth
slot draws for them, so the icon is the single art for GUI, hand, ground and mouth. Colours come only from art/props/palette.json
(4 steps per material, dark to light), except swaddle_cloth, which is drawn like supplies.py with local hue-shifted ramps (outline
= darkest step of its own material); light falls from the top left, contact edges take the darkest step of their own
material, no black outline. The on-body harness and swaddle shells are drawn procedurally by client/CareWearRenderer and use
the same ramps (see README).
"""
import io, json
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
PALETTE = json.loads((ROOT / 'art/props/palette.json').read_text(encoding='utf-8'))['ramps']
SIZE = 16


def rgb(ramp, step):
    v = PALETTE[ramp][step]
    return tuple(int(v[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


class Icon:
    def __init__(self, name):
        self.name = name
        self.px = {}

    def put(self, x, y, c):
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.px[(x, y)] = rgb(*c)

    def rows(self, legend, rows, dx=0, dy=0):
        """ASCII drawing: '.' is transparent, every other character maps to a (ramp, step) through `legend`."""
        for y, line in enumerate(rows):
            for x, ch in enumerate(line):
                if ch != '.':
                    self.put(x + dx, y + dy, legend[ch])

    def png(self):
        img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
        for xy, c in self.px.items():
            img.putpixel(xy, c)
        out = io.BytesIO()
        img.save(out, 'PNG', optimize=True)
        return out.getvalue()


# ---- sweet_candy: strawberry-pink round candy in a pale pink wrapper with twisted ends ------------------------------
CANDY = [
    "................",
    "................",
    "................",
    "................",
    "t.....aaao.....s",
    "tt...accbbo...ss",
    ".ts.acbbbbbo.sr.",
    ".ssrabbbbbborrq.",
    ".ssrabbbbbaorrq.",
    ".rr.abbbbaao.rq.",
    "rr...aabbao...qq",
    "q.....aooo.....q",
    "................",
    "................",
    "................",
    "................",
]


def sweet_candy():
    i = Icon('sweet_candy')
    i.rows({'a': ('berry', 1), 'b': ('berry', 2), 'c': ('berry', 3), 'o': ('berry', 0),
            'q': ('berry', 1), 'r': ('berry', 2), 's': ('berry', 3), 't': ('paper', 3)}, CANDY)
    return i


# ---- custard_pudding: caramel-topped custard on a cream dish, one cherry ------------------------------------------
PUDDING = [
    "................",
    "........kk......",
    ".......hRR......",
    ".......RRr......",
    ".......rrd......",
    "....LLpppppP....",
    "..LppppppppppP..",
    "..pyyypuuuuupP..",
    "...pyyuuuuuvP...",
    "...yyyuuuuuvv...",
    "....yyuuuuvv....",
    "....yyuuuuvv....",
    "..DDyuuuvvvwEE..",
    ".DDDDDCCCCCEEEE.",
    "...EEEEEEEEEE...",
    "................",
]


def custard_pudding():
    i = Icon('custard_pudding')
    i.rows({'L': ('pellet', 3), 'p': ('pellet', 2), 'P': ('pellet', 1), 'O': ('pellet', 0),
            'y': ('gold', 3), 'u': ('gold', 2), 'v': ('gold', 1), 'w': ('gold', 0),
            'h': ('chili', 3), 'R': ('chili', 2), 'r': ('chili', 1), 'd': ('chili', 0), 'k': ('leaf', 2),
            'D': ('ceramic', 3), 'C': ('ceramic', 2), 'E': ('ceramic', 1), 'F': ('ceramic', 0)}, PUDDING)
    return i


# ---- pet_harness: a flattened mint band loop with a stitched edge, a brass buckle, a small D-ring and a short lead with a clip ---------
HARNESS = [
    "............GGgf",
    "............G..f",
    "...........bgfee",
    "..........b.....",
    ".....GGggfa.....",
    ".....G...f......",
    "....nffeefkk....",
    "..nmmmmmmmmmkj..",
    ".nmkkkkkkkkkkkj.",
    "nm............kj",
    "nm............kj",
    ".nmmm......mmkj.",
    "..nmnmnmmGggkj..",
    "...mmmmmmgkfkj..",
    "....kkkkkkjj....",
    "................",
]


def pet_harness():
    i = Icon('pet_harness')
    i.rows({'n': ('mint', 3), 'm': ('mint', 2), 'k': ('mint', 1), 'j': ('mint', 0),
            'G': ('gold', 3), 'g': ('gold', 2), 'f': ('gold', 1), 'e': ('gold', 0),
            'b': ('berry', 2), 'a': ('berry', 1)}, HARNESS)
    return i


# ---- swaddle_cloth: a folded cream knit wrap, the top-right corner turned down onto the cloth to show its pink lining, -----------
# a pink hem along the bottom, a second layer peeking out underneath and a small strawberry heart. Vanilla item style with
# supplies.py-style local hue-shifted ramps (outline = darkest step of its own material), unlike the palette-only icons above.
CREAM = ['#9a8164', '#c7b393', '#e3d7bd', '#f4ede0', '#fffcf4']
LINING = ['#9e3b57', '#cc6a86', '#e99cb0', '#f8c9d4']
HEART = ['#8c1f3c', '#d23e5e', '#f27f95']


def _hex(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def swaddle_cloth():
    i = Icon('swaddle_cloth')
    colours = {}
    x0, x1, y0, y1 = 1, 14, 2, 11                   # top layer; rows y1-1..y1 are the pink hem
    crease = 8                                       # the top-right corner is turned down along x - y = crease
    top = {(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1) if x - y <= crease}
    flap = {(x, y) for (x, y) in top if x >= y0 + crease and y <= x1 - crease and x - y <= crease}
    under = {(x, y) for x in range(x0 + 1, x1 + 2) for y in (y1 + 1, y1 + 2)}   # the folded lower layer below the hem
    for x, y in under:
        colours[(x, y)] = CREAM[0] if y == y1 + 2 or x == x1 + 1 else CREAM[2] if x % 2 else CREAM[1]
    for x, y in top:
        knit = CREAM[4] if x % 2 == 1 and y % 2 == 0 else CREAM[2] if x % 2 == 0 and y % 2 == 1 else CREAM[3]
        if y >= y1 - 1:
            knit = LINING[3] if y == y1 - 1 and x % 2 else LINING[2]
        colours[(x, y)] = knit
    for x, y in top:                                 # outline: one lighter on the lit top/left, darkest on the shaded sides
        lit = (x - 1, y) not in top or (x, y - 1) not in top
        shaded = (x + 1, y) not in top or (x, y + 1) not in top
        hem = y >= y1 - 1
        if shaded:
            colours[(x, y)] = LINING[0] if hem else CREAM[0]
        elif lit:
            colours[(x, y)] = LINING[1] if hem else CREAM[1]
    for x, y in top - flap:                          # shadow the flap casts down-left onto the cloth
        if (x + 1, y) in flap or (x, y - 1) in flap:
            colours[(x, y)] = CREAM[2]
    for x, y in flap:                                # turned-down corner: pink lining, bright rolled crease
        free = (x - 1, y) not in flap or (x, y + 1) not in flap
        colours[(x, y)] = LINING[1] if x - y == crease else LINING[1] if free else LINING[3] if x - y == crease - 1 else LINING[2]
    colours[(x0 + crease, y0)] = CREAM[1]
    for (x, y), c in (((3, 5), HEART[1]), ((5, 5), HEART[1]), ((3, 6), HEART[1]), ((4, 6), HEART[1]), ((5, 6), HEART[0]),
                      ((4, 7), HEART[0]), ((4, 5), CREAM[3])):
        colours[(x, y)] = c
    colours[(3, 5)] = HEART[2]
    for xy, c in colours.items():
        i.px[xy] = _hex(c)
    return i


GOODS = {'sweet_candy': sweet_candy, 'custard_pudding': custard_pudding, 'pet_harness': pet_harness, 'swaddle_cloth': swaddle_cloth}
