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


# ---- pet_harness: a soft mint band with an upright brass ring for the lead -----------------------------------------
HARNESS = [
    "......GGgf......",
    ".....GGgggf.....",
    ".....Gg..fe.....",
    ".....Gg..fe.....",
    ".....gffffe.....",
    "......feee......",
    "....nnmmmmkk....",
    "..nmmmmmmmmmkj..",
    ".nmkkkkkkkkkkkj.",
    "nm............kj",
    "nm............kj",
    ".nm..........kj.",
    "..nnnnmmmmmmkj..",
    "...mnmnmnGggj...",
    ".....kkkkjj.....",
    "................",
]


def pet_harness():
    i = Icon('pet_harness')
    i.rows({'n': ('mint', 3), 'm': ('mint', 2), 'k': ('mint', 1), 'j': ('mint', 0),
            'G': ('gold', 3), 'g': ('gold', 2), 'f': ('gold', 1), 'e': ('gold', 0)}, HARNESS)
    return i


# ---- swaddle_cloth: folded cream cloth with stitched seam and a small strawberry heart ---------------------------
def swaddle_cloth():
    """Folded layers in a three-quarter view: lit top, front with a fold crease, shaded side."""
    i = Icon('swaddle_cloth')
    cloth = lambda step: ('ceramic', step)
    for y in range(3, 8):                                   # top face, leaning right towards the back
        left = 5 - (y - 3)
        for x in range(left, left + 10):
            if (x, y) not in ((5, 3), (14, 3)):
                i.put(x, y, cloth(3))
    for x in range(1, 11):                                  # front face: two folded layers and a contact edge
        for y, step in ((8, 2), (9, 2), (10, 1), (11, 2), (12, 1)):
            i.put(x, y, cloth(step))
    for x in range(11, 15):                                 # side face under the right edge of the top face
        edge = 3 + (14 - x)
        for y in range(edge + 1, edge + 6):
            i.put(x, y, cloth(0) if y == edge + 5 else cloth(1))
    for x in (2, 4, 6, 8):                                     # stitching along the front seam of the top face
        i.put(x, 7, cloth(2))
    for x, y, step in ((7, 4, 3), (9, 4, 2), (7, 5, 2), (8, 5, 2), (9, 5, 1), (8, 6, 1)):   # heart mark
        i.put(x, y, ('berry', step))
    return i


GOODS = {'sweet_candy': sweet_candy, 'custard_pudding': custard_pudding, 'pet_harness': pet_harness, 'swaddle_cloth': swaddle_cloth}
