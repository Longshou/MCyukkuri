"""Pet-care shop goods drawn as 16x16 item icons: sweet candy, custard pudding, pet harness, swaddle cloth.

These four items use the plain item/generated sprite path (no PropItemRenderer registration), which is also what the mouth
slot draws for them, so the icon is the single art for GUI, hand, ground and mouth. Colours come only from art/props/palette.json
(4 steps per material, dark to light); light falls from the top left, contact edges take the darkest step of their own
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


# ---- swaddle_cloth: a soft cream cloth with one corner folded over along a diagonal, stitched hem and a strawberry heart ----------------
def swaddle_cloth():
    i = Icon('swaddle_cloth')
    cloth = lambda step: ('ceramic', step)
    inside = lambda x, y: 2 <= x <= 13 and 2 <= y <= 13 and x + y < 22 and (x, y) not in ((2, 2), (13, 2), (2, 13))
    flap = lambda x, y: x >= 8 and y >= 8 and x + y <= 21
    for y in range(SIZE):
        for x in range(SIZE):
            if not inside(x, y):
                continue
            edge = not inside(x + 1, y) or not inside(x, y + 1) or x + y == 21
            if flap(x, y):
                step = 1
                if x == 8 or y == 8:
                    step = 2
            else:
                step = 3 if not edge else 2
                if x == 7 and y >= 8 or y == 7 and x >= 8:
                    step = 2
            if not flap(x, y) and (not inside(x + 1, y) or not inside(x, y + 1)):
                step = 0 if (y == 13 or x + y >= 21) else 1
            i.put(x, y, cloth(step))
    for x in range(4, 12, 2):                               # stitched hem along the top and left edges
        i.put(x, 3, cloth(2))
    for y in range(5, 8, 2):
        i.put(3, y, cloth(2))
    for x, y, step in ((4, 5, 3), (6, 5, 2), (4, 6, 2), (5, 6, 2), (6, 6, 1), (5, 7, 1)):   # heart mark
        i.put(x, y, ('berry', step))
    return i


GOODS = {'sweet_candy': sweet_candy, 'custard_pudding': custard_pudding, 'pet_harness': pet_harness, 'swaddle_cloth': swaddle_cloth}
