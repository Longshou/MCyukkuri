"""Pet-care shop goods drawn as 16x16 item icons: sweet candy, custard pudding, pet harness and the eight swaddle cloth styles.

These items use the plain item/generated sprite path (no PropItemRenderer registration), which is also what the mouth slot draws
for them, so the icon is the single art for GUI, hand, ground and mouth; the swaddle cloth's item model picks the style's icon
through its swaddle_style override. Colours come only from art/props/palette.json (4 steps per material, dark to light), except
the swaddle cloth, which is drawn like supplies.py with local hue-shifted ramps (outline = darkest step of its own material); light
falls from the top left, contact edges take the darkest step of their own material, no black outline. The on-body harness and
swaddle shells are drawn procedurally by client/CareWearRenderer (see README).
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


# ---- swaddle_cloth: one item, eight styles (item.SwaddleStyle). Each icon is the same folded wrap: the top layer with its top-right
# corner turned down to show the lining, a hem along the bottom and the folded second layer peeking out underneath; the style changes
# the cloth, lining and hem and adds its mark (bow, lace, initial, stripes, sole and pouch, pompoms). Vanilla item style with
# supplies.py-style local hue-shifted ramps (outline = darkest step of its own material). The knit is the plain item (swaddle_cloth.png);
# the others are swaddle_cloth_<style>.png, picked by the item model's swaddle_style overrides.
CREAM = ['#9a8164', '#c7b393', '#e3d7bd', '#f4ede0', '#fffcf4']
LINING = ['#9e3b57', '#cc6a86', '#e99cb0', '#f8c9d4']
HEART = ['#8c1f3c', '#d23e5e', '#f27f95']
PINK = ['#9e3b57', '#cc6a86', '#e99cb0', '#f8c9d4', '#fde6ec']
RED = ['#6e1a1c', '#9e2a28', '#c8443c', '#e06a5a', '#f29a88']
LACE = ['#9a9aa6', '#c9cad2', '#f2f2f5', '#ffffff']
TIGER = ['#7a3a12', '#b0561a', '#e08a2a', '#f2aa3c', '#fbc865']
STRIPE = '#4a220a'
SKY = ['#3f6688', '#6c94b8', '#9cc2e0', '#b4d4ec', '#cfe4f4']
NAVY = ['#24304e', '#3a4c7a', '#5a70a8', '#7a8ec0']
RUBBER = ['#25272c', '#3a3d44', '#55585f', '#6a6e76']
CANVAS = ['#6b5232', '#9a7a4c', '#c8a874', '#e0c696']
OAT = ['#7e664a', '#a88c6a', '#cdb38f', '#ddc7a5', '#ecdcc2']
FLUFF = ['#a89276', '#d2c2a6', '#f2e8d6', '#fffaf0']
BROWN = ['#7a3424', '#a8503a', '#c6604e', '#e08a72']
GREEN = ['#3e6e34', '#5f9a4c', '#86bf6c', '#b4dc98']
GINGHAM = ('#f8f8f4', '#a9c1e6', '#5f82c6')
FLOWERS = {(3, 4): '#e889a6', (7, 6): '#7fa6e0', (4, 8): '#7fa6e0', (10, 8): '#e889a6', (12, 5): '#7fa6e0'}
GLYPH_M = ["10001", "11011", "10101", "10001", "10001"]      # the letter style's sample initial (EH34's M)
X0, X1, Y0, Y1, CREASE = 1, 14, 2, 11, 8                     # top layer; rows Y1-1..Y1 are the hem; corner turned down along x - y = CREASE


def _hex(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def _folded(body, lining, hem, under, weave=None):
    """The folded wrap. body(x, y, step) colours the top layer: step 0 shaded outline, 1 lit outline, 2-4 the fabric's own texture
    steps (weave(x, y) picks them; the default is the knit's V rows); lining and hem are 4-step ramps (0 shaded edge, 1 lit edge, 2 base,
    3 light); under colours the lower layer (0 edge, 1, 2).
    Returns the colour map and the top-layer cells outside the turned-down flap."""
    colours = {}
    top = {(x, y) for x in range(X0, X1 + 1) for y in range(Y0, Y1 + 1) if x - y <= CREASE}
    flap = {(x, y) for (x, y) in top if x >= Y0 + CREASE and y <= X1 - CREASE and x - y <= CREASE}
    for x, y in {(x, y) for x in range(X0 + 1, X1 + 2) for y in (Y1 + 1, Y1 + 2)}:
        colours[(x, y)] = under(x, y, 0 if y == Y1 + 2 or x == X1 + 1 else 2 if x % 2 else 1)
    for x, y in top:
        step = weave(x, y) if weave else 4 if x % 2 == 1 and y % 2 == 0 else 2 if x % 2 == 0 and y % 2 == 1 else 3
        colours[(x, y)] = (hem[3] if y == Y1 - 1 and x % 2 else hem[2]) if y >= Y1 - 1 else body(x, y, step)
    for x, y in top:                                 # outline: one lighter on the lit top/left, darkest on the shaded sides
        lit = (x - 1, y) not in top or (x, y - 1) not in top
        shaded = (x + 1, y) not in top or (x, y + 1) not in top
        if shaded:
            colours[(x, y)] = hem[0] if y >= Y1 - 1 else body(x, y, 0)
        elif lit:
            colours[(x, y)] = hem[1] if y >= Y1 - 1 else body(x, y, 1)
    for x, y in top - flap:                          # shadow the flap casts down-left onto the cloth
        if (x + 1, y) in flap or (x, y - 1) in flap:
            colours[(x, y)] = body(x, y, 2)
    for x, y in flap:                                # turned-down corner: the lining, a bright rolled crease
        free = (x - 1, y) not in flap or (x, y + 1) not in flap
        colours[(x, y)] = lining[1] if x - y == CREASE or free else lining[3] if x - y == CREASE - 1 else lining[2]
    colours[(X0 + CREASE, Y0)] = body(X0 + CREASE, Y0, 1)
    return colours, top - flap


def _cotton(x, y):
    """A smooth woven cloth: the base step with a sparse diagonal sheen."""
    return 4 if (x * 2 + y) % 7 == 0 else 3


def _ramp(ramp):
    return lambda x, y, step: ramp[step]


def _bow(colours, x0, y0, ramp):
    """A 5x3 ribbon bow centred at (x0, y0): two loops, a dark knot and two short tails."""
    for dx, dy, s in ((-2, -1, 2), (-1, -1, 3), (1, -1, 3), (2, -1, 2), (-2, 0, 1), (-1, 0, 2), (0, 0, 0), (1, 0, 2), (2, 0, 1),
                      (-1, 1, 1), (1, 1, 1)):
        colours[(x0 + dx, y0 + dy)] = ramp[s]


def _lace(colours):
    """White lace scallops along the hem: alternate bright and shaded pixels, a scalloped lower edge."""
    for x in range(X0, X1 + 1):
        colours[(x, Y1 - 1)] = LACE[3] if x % 2 else LACE[2]
        colours[(x, Y1)] = LACE[2] if x % 3 else LACE[0]
        if x % 3 == 1 and X0 < x < X1:
            colours[(x, Y1 + 1)] = LACE[1]


def swaddle_icon(style):
    i = Icon('swaddle_cloth' if style == 'knit' else f'swaddle_cloth_{style}')
    if style == 'knit':           # cream knit, pink lining and hem, a small strawberry heart (the plain item)
        colours, cloth = _folded(_ramp(CREAM), LINING, LINING, _ramp(CREAM))
        for (x, y), c in (((3, 5), HEART[2]), ((5, 5), HEART[1]), ((3, 6), HEART[1]), ((4, 6), HEART[1]), ((5, 6), HEART[0]),
                          ((4, 7), HEART[0]), ((4, 5), CREAM[3])):
            colours[(x, y)] = c
    elif style == 'frill':        # pink, white lace hem, red bow on the fold (EH30)
        colours, cloth = _folded(_ramp(PINK), LACE, LACE, _ramp(PINK), _cotton)
        _lace(colours)
        _bow(colours, 8, 4, RED)
    elif style == 'letter':       # red with a lace hem and a white initial (EH34)
        colours, cloth = _folded(_ramp(RED), LACE, LACE, _ramp(RED), _cotton)
        _lace(colours)
        for r, line in enumerate(GLYPH_M):
            for c, ch in enumerate(line):
                if ch == '1':
                    colours[(3 + c, 4 + r)] = LACE[3] if r < 3 else LACE[2]
    elif style == 'tiger':        # orange fur with dark stripes, a fluffy cream lining and hem (EH34)
        stripes = {(3, 3), (3, 4), (4, 5), (6, 4), (6, 5), (7, 6), (7, 7), (3, 7), (4, 8), (9, 7), (10, 8), (10, 9), (12, 8)}
        colours, cloth = _folded(lambda x, y, s: STRIPE if s > 1 and (x, y) in stripes else TIGER[s], FLUFF, FLUFF, _ramp(TIGER), _cotton)
    elif style == 'outing':       # light blue cloth, dark rubber sole as the hem, a strap and a pouch (EH31)
        colours, cloth = _folded(_ramp(SKY), NAVY, RUBBER, _ramp(SKY), _cotton)
        for k in range(7):
            xy = (2 + k, 3 + k // 2)
            if xy in cloth:
                colours[xy] = CANVAS[1]
        for x in range(8, 13):
            for y in range(6, 10):
                if (x, y) in cloth or (x, y) in colours:
                    colours[(x, y)] = CANVAS[0] if x in (8, 12) or y == 9 else CANVAS[3] if y == 6 else CANVAS[2]
        colours[(10, 7)] = RED[2]
    elif style == 'fleece':       # oatmeal fleece, a thick fluffy hem and two brown pompoms (N91's cold-season wear)
        colours, cloth = _folded(_ramp(OAT), FLUFF, FLUFF, _ramp(OAT), lambda x, y: (3, 4, 3, 2)[(x * 3 + y * 5) % 4])
        for x0 in (4, 7):
            for dx, dy, s in ((0, 0, 3), (1, 0, 2), (0, 1, 1), (1, 1, 0)):
                colours[(x0 + dx, 4 + dy)] = BROWN[s]
    elif style == 'gingham':      # blue checks, white lining, navy hem and bow
        def check(x, y, s):
            if s < 2:
                return NAVY[s]
            return GINGHAM[((x // 2) % 2 == 0) + ((y // 2) % 2 == 0)]
        colours, cloth = _folded(check, LACE, NAVY, _ramp(NAVY), _cotton)
        _bow(colours, 8, 4, NAVY)
    else:                         # floral: cream with small flowers, pale green lining, green hem and bow
        colours, cloth = _folded(lambda x, y, s: FLOWERS.get((x, y), CREAM[s]) if s > 1 else CREAM[s], GREEN, GREEN, _ramp(CREAM), _cotton)
        _bow(colours, 8, 4, GREEN)
    for xy, c in colours.items():
        i.px[xy] = _hex(c)
    return i


SWADDLE_STYLES = ('knit', 'frill', 'letter', 'tiger', 'outing', 'fleece', 'gingham', 'floral')
GOODS = {'sweet_candy': sweet_candy, 'custard_pudding': custard_pudding, 'pet_harness': pet_harness,
         **{('swaddle_cloth' if s == 'knit' else f'swaddle_cloth_{s}'): (lambda s=s: swaddle_icon(s)) for s in SWADDLE_STYLES}}
