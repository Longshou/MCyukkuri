"""Operation and abuse tools drawn as vanilla-style 16x16 item sprites (same look as care-props/supplies.py: flour paste etc.).

These items use the plain item/generated path, so the sprite is the single art for GUI, hand (extruded), ground, item frames,
the shop shelf and NPC hands. The in-world attachments (pin in the skin, hook, rotor, fitted restraint board, sling and iron box)
stay meshes from build.py. Style: light from the top left, each material has its own hue-shifted ramp (dark -> light), the
outline is the darkest step of the material it bounds (no black), no anti-aliasing and no partial alpha. Tools lie on the
vanilla tool diagonal: grip at the bottom left, working end at the top right. Shapes keep the Hakkei references of build.py
(pink-headed pin, black drum hammer, wooden awl, red tailor shears, pink rotor, iron box); materials stay pre-modern.

Thin parts are hand-drawn ASCII (a light pixel with its shadow beside it, like vanilla sticks and rods); chunky parts are
rasterised primitives (bars, discs, polygons) at pixel centres, shaded lighter towards the top left and outlined in their own
darkest step. DRAFTS are drawn for review but not shipped until their item exists. Run this file to rewrite the snapshot PNGs
in art/props/operation-tools/icons/; build.py ships the shipped ones. --check is read-only.
"""
import argparse, io, math, sys
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
SNAPSHOT = ROOT / 'art/props/operation-tools/icons'
SIZE = 16

# ---- ramps (dark -> light); index 0 is the outline step ---------------------------------------------------------------
IRON = ['#3a3f4b', '#5f6878', '#8b95a5', '#b8c1cd', '#e6ecf2']        # blades, spikes, rings, box
DRUM = ['#1f222b', '#30343f', '#444a58', '#5d6475', '#848ca0']        # blue-black hammer drum (Hakkei mallet)
WOOD = ['#3f2913', '#664423', '#8c6232', '#b3854a', '#d3a96c']        # handles, restraint board
PINK = ['#7c2846', '#b5466b', '#e2718f', '#f7a6bb', '#ffe0e9']        # pin head, rotor
RED = ['#55121a', '#8a2329', '#b93a3a', '#dc625a', '#f39a8c']         # tailor-shear bows
LEATHER = ['#2e170c', '#4f2b18', '#714126', '#93603a']                # clipper grips
STRAP = ['#4a1a10', '#7c3020', '#a54a2e', '#c86c45']                  # reddish restraint straps
BRASS = ['#6a4710', '#a3781f', '#d4ac3a', '#f5dd7a']                  # buckles, pivot rivets, lamp collar
CLOTH = ['#5f6672', '#99a1ae', '#c8cfd9', '#e6ebf1', '#ffffff']       # cool white sling wrap (apart from flour paste)
STRING = ['#6f6556', '#a99f8a', '#d9d1bf']
HOT = ['#6e1708', '#b9320c', '#ec6417', '#ffaa33', '#fff0a0']         # heated spike, glowing to the tip
GLASS = ['#557fae', '#8bb0d4', '#b4cfe9', '#dbeaf7']                  # same glass as the orange-juice bottle
SYRUP = ['#9a6a17', '#cf9a33', '#efc861', '#fff0b0']                  # sugar water in the brood bottle
HAIR = ['#2a170d', '#462818', '#673f24', '#8a5a34', '#ad7d4f']        # cut lock
FLAME = ['#2b56c4', '#5a8ff0', '#a9d0ff', '#ffffff']                  # alcohol flame
SPIRIT = ['#5f86b3', '#93b8dd', '#c2dcf1']                            # lamp spirit (faintly blue)
PLANK = ['#4d3720', '#7d5c36', '#ad8650', '#caa56c', '#e3c68f']        # pale oak restraint plank
GREEN = ['#2f5130', '#4a7a3f', '#6f9e52', '#9cc477']                  # cut stem in the bottle
LIGHT = (-1.0, -1.0)                                                  # top-left


def rgba(hex_colour):
    return tuple(int(hex_colour[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def _norm(v):
    length = math.hypot(*v)
    return (v[0] / length, v[1] / length) if length else (0.0, 0.0)


class Icon:
    """Pixels are (ramp, step, part). Primitive parts are outlined by finish() against transparency and against later
    parts of another material; ASCII parts are drawn as final pixels and never re-outlined."""

    def __init__(self, name):
        self.name = name
        self.px = {}
        self.part = 0
        self.solid = set()        # parts that finish() outlines

    def _paint(self, cells, ramp, glint):
        self.part += 1
        self.solid.add(self.part)
        top = len(ramp) - 1 if glint else min(3, len(ramp) - 1)
        for (x, y), d in cells.items():
            if 0 <= x < SIZE and 0 <= y < SIZE:
                step = top if d > .6 else 2 if d > -.15 else 1
                self.px[(x, y)] = [ramp, step, self.part]
        return self

    def _shade(self, a, b):
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = _norm((-dy, dx))
        return n if n[0] * LIGHT[0] + n[1] * LIGHT[1] >= 0 else (-n[0], -n[1])

    def bar(self, a, b, r, ramp, glint=False, round_ends=False):
        """Bar from a to b (pixel-centre coordinates) of half-width r, shaded across its width like a cylinder."""
        (ax, ay), (bx, by) = a, b
        dx, dy = bx - ax, by - ay
        length2 = dx * dx + dy * dy
        n = self._shade(a, b)
        cells = {}
        for y in range(SIZE):
            for x in range(SIZE):
                t = ((x - ax) * dx + (y - ay) * dy) / length2
                if not round_ends and not -1e-6 <= t <= 1 + 1e-6:
                    continue
                t = max(0, min(1, t))
                px, py = ax + t * dx, ay + t * dy
                if math.hypot(x - px, y - py) <= r + 1e-6:
                    cells[(x, y)] = ((x - px) * n[0] + (y - py) * n[1]) / max(r, .5)
        return self._paint(cells, ramp, glint)

    def disc(self, c, rx, ramp, ry=None, glint=True):
        (cx, cy), ry = c, ry or rx
        cells = {}
        for y in range(SIZE):
            for x in range(SIZE):
                u, v = (x - cx) / rx, (y - cy) / ry
                if u * u + v * v <= 1 + 1e-6:
                    cells[(x, y)] = (u * LIGHT[0] + v * LIGHT[1]) * .9 - .05
        return self._paint(cells, ramp, glint)

    def poly(self, points, ramp, shade):
        """Filled convex polygon (clockwise on screen) at one shade: faces of boxes and boards."""
        cells = {}
        for y in range(SIZE):
            for x in range(SIZE):
                if all((points[(k + 1) % len(points)][0] - ax) * (y - ay) - (points[(k + 1) % len(points)][1] - ay) * (x - ax) >= -1e-6
                       for k, (ax, ay) in enumerate(points)):
                    cells[(x, y)] = shade
        return self._paint(cells, ramp, False)

    def rows(self, legend, rows, dx=0, dy=0):
        """ASCII drawing: characters in `legend` map to (ramp, step); anything else keeps what is below."""
        self.part += 1
        for y, line in enumerate(rows):
            for x, ch in enumerate(line):
                if ch in legend and 0 <= x + dx < SIZE and 0 <= y + dy < SIZE:
                    self.px[(x + dx, y + dy)] = [*legend[ch], self.part]
        return self

    def stroke(self, points, ramp, light=3, shadow=1):
        """A 1-pixel line through the points (Bresenham) with a shadow pixel on its lower-right side, like vanilla rods."""
        self.part += 1
        line = []
        for (ax, ay), (bx, by) in zip(points, points[1:]):
            dx, dy = abs(bx - ax), -abs(by - ay)
            sx, sy = (1 if bx > ax else -1), (1 if by > ay else -1)
            err, x, y = dx + dy, ax, ay
            while True:
                line.append((x, y))
                if (x, y) == (bx, by):
                    break
                e2 = 2 * err
                if e2 >= dy:
                    err += dy; x += sx
                if e2 <= dx:
                    err += dx; y += sy
        for x, y in line:
            if (x + 1, y) not in line and (x + 1, y) not in self.px and x + 1 < SIZE:
                self.px[(x + 1, y)] = [ramp, shadow, self.part]
        for x, y in line:
            if 0 <= x < SIZE and 0 <= y < SIZE:
                self.px[(x, y)] = [ramp, light, self.part]
        return self

    def erase(self, cells):
        for c in cells:
            self.px.pop(c, None)
        return self

    def finish(self):
        edge = []
        for (x, y), (ramp, step, part) in self.px.items():
            if part not in self.solid:
                continue
            for n in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                other = self.px.get(n)
                if other is None or (other[2] > part and other[0] is not ramp):
                    edge.append((x, y))
                    break
        for c in edge:
            self.px[c][1] = 0
        self.solid.clear()
        return self

    def image(self):
        img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
        for xy, (ramp, step, _) in self.px.items():
            img.putpixel(xy, rgba(ramp[step]))
        return img

    def png(self):
        out = io.BytesIO()
        self.image().save(out, 'PNG', optimize=True)
        return out.getvalue()


def legend(**ramps):
    """legend(a=(RAMP, step), ...) with the '.' left transparent."""
    return ramps


# ---- the tools --------------------------------------------------------------------------------------------------------
def pin_tool():
    """まち針: a short steel pin with a small pink ball head (sold four to a craft); shorter and thinner than the awl."""
    i = Icon('pin_tool')
    i.rows(legend(w=(IRON, 4), c=(IRON, 3), s=(IRON, 0)), [
        "", "", "",
        "............w...",
        "...........cs...",
        "..........cs....",
        ".........cs.....",
        "........cs......",
        ".......cs.......",
        "......cs........",
        ".....cs.........",
    ])
    i.disc((3.8, 11.2), 2.45, PINK)
    return i.finish()


def care_mallet():
    """ハンマー: Hakkei's dark drum mallet with steel face plates on both ends and a plain wooden handle."""
    i = Icon('care_mallet')
    i.rows(legend(a=(WOOD, 1), c=(WOOD, 3), b=(WOOD, 0)), [
        "", "", "", "", "", "", "", "",
        ".......acb......",
        "......acb.......",
        ".....acb........",
        "....acb.........",
        "...acb..........",
        "..acb...........",
        "..bb............",
    ])
    a, b = (6.0, 2.0), (13.0, 9.0)
    i.bar(a, b, 2.4, DRUM, glint=True)
    i.finish()
    for (x, y), v in i.px.items():          # the two end faces of the drum are steel plates, two pixels deep
        t = ((x - a[0]) + (y - a[1])) / ((b[0] - a[0]) + (b[1] - a[1]))
        if v[0] is DRUM and (t < .2 or t > .8):
            v[0], v[1] = IRON, 0 if v[1] == 0 else (4 if t < .2 and v[1] >= 2 else 3 if t < .2 else 2 if v[1] >= 2 else 1)
    return i


def _awl(name, spike):
    i = Icon(name)
    i.bar((2.4, 13.6), (6.2, 9.8), 1.4, WOOD, glint=True, round_ends=True)
    i.bar((6.4, 9.6), (8.0, 8.0), 1.3, IRON, glint=True)
    i.finish()
    i.rows(spike, [
        "................",
        "..............w.",
        ".............ocs",
        "............ocs.",
        "...........ocs..",
        "..........ocs...",
        ".........oCs....",
        "........oCs.....",
    ])
    return i


def care_awl():
    """キリ: Japanese gimlet. A long round wooden grip, a two-pixel iron ferrule and a thin steel spike."""
    return _awl('care_awl', legend(w=(IRON, 4), c=(IRON, 3), C=(IRON, 3), s=(IRON, 1), o=(IRON, 0)))


def heated_awl():
    """キリライター: the awl after holding it to a fire; the spike glows from dull red at the ferrule to yellow at the tip."""
    i = _awl('heated_awl', legend(w=(HOT, 4), c=(HOT, 3), C=(HOT, 2), s=(HOT, 1), o=(HOT, 0)))
    for xy, step in {(12, 3): 3, (11, 4): 2, (10, 5): 2}.items():
        i.px[xy][1] = step
    i.rows(legend(e=(HOT, 3)), ["", "............e...", "", "...............e"])   # two sparks off the tip
    return i


def restraint_frame():
    """お仕置き用固定具: a pale oak board seen slightly from above, two reddish leather straps arched over it, each with a
    2px brass buckle; the same board and bands the restrained body lies in."""
    i = Icon('restraint_frame')
    i.rows(legend(l=(STRAP, 3), m=(STRAP, 1), y=(BRASS, 3), Y=(BRASS, 1),
                  o=(PLANK, 0), e=(PLANK, 1), p=(PLANK, 3), H=(PLANK, 4), g=(PLANK, 2)), [
        "................",
        "................",
        "................",
        "....lll....lll..",
        "...l...m..l...m.",
        "...l...m..l...m.",
        "...yy..m..yy..m.",
        "...YY..m..YY..m.",
        "...l...m..l...m.",
        "..ol...moolooomo",
        "..oHHHHHHHHHHHHo",
        ".oppppgpppppgppo",
        ".oeeeeeeeeeeeeo.",
        ".oeegeeeeeegeeo.",
        ".oooooooooooooo.",
        "................",
    ])
    return i


def wrap_sling():
    """やさしく吊るす: a white cloth wrap gathered into a pouch, hung by two cords from an iron ring."""
    i = Icon('wrap_sling')
    i.rows(legend(o=(IRON, 0), c=(IRON, 3), d=(IRON, 1), w=(IRON, 4), S=(STRING, 2), s=(STRING, 1), t=(STRING, 0),
                  O=(CLOTH, 0), x=(CLOTH, 1), m=(CLOTH, 2), l=(CLOTH, 3), W=(CLOTH, 4)), [
        ".......oo.......",
        "......ow.o......",
        "......oc.d......",
        ".......od.......",
        "......St.s......",
        ".....St...s.....",
        "....St.....s....",
        "...OO.......OO..",
        "..OWlO.....OmxO.",
        ".OWllmOOOOOmmxO.",
        ".OllmmlllmmmmxO.",
        ".OlmmlmmmmmmxxO.",
        "..OmmlmmmmmxxO..",
        "..OxmmmmmmxxxO..",
        "...OOxxxxxxOO...",
        ".....OOOOOO.....",
    ])
    return i


def hanging_hook():
    """吊るす: a steel hook on a short cord: an eye, a straight shank on the right, one bowl curving left and up to a barbed
    point; nothing passes right of the shank, so it cannot read as an anchor."""
    i = Icon('hanging_hook')
    i.rows(legend(s=(STRING, 1), o=(IRON, 0), d=(IRON, 1), m=(IRON, 2), c=(IRON, 3), w=(IRON, 4)), [
        ".........s......",
        ".........s......",
        "........ooo.....",
        ".......oc.do....",
        "........odo.....",
        ".........cd.....",
        ".........cd.....",
        ".........cd.....",
        "...w.....cd.....",
        "...cw....cd.....",
        "...cd....cd.....",
        "...cdo...cd.....",
        "...ocmo.ocd.....",
        "....ocmmmdo.....",
        ".....oooo.......",
        "................",
    ])
    return i


def hair_clippers():
    """ばりかん: manual barber clippers on the tool diagonal: a steel head at the top right with one-pixel comb teeth set
    against its outer edge, a brass pivot, and two short leather grips spreading into a V at the bottom left."""
    i = Icon('hair_clippers')
    i.rows(legend(a=(LEATHER, 1), c=(LEATHER, 3), b=(LEATHER, 0)), [
        "", "", "", "", "", "", "", "",
        ".....acb........",
        "....acbacb......",
        "...acb.acb......",
        "..acb..acb......",
        ".acb....acb.....",
        ".bb.....acb.....",
        "........bb......",
    ])
    a, b = (4.8, 1.8), (11.6, 8.6)
    i.bar(a, b, 2.0, IRON, glint=True)
    i.finish()
    head = {c for c, v in i.px.items() if v[0] is IRON}
    edge = sorted((c for c in head if (c[0] + 1, c[1] - 1) not in head and (c[0] + 1, c[1]) not in head), key=lambda c: c[0] + c[1])
    for k, (x, y) in enumerate(edge):         # every other pixel of the outer edge carries a tooth right against it
        if k % 2 == 0:
            i.px[(x + 1, y - 1)] = [IRON, 3, i.part + 1]
    i.rows(legend(y=(BRASS, 3), Y=(BRASS, 1)), ["", "", "", "", "", "", "", "......yY........", "......Y........."])
    return i


def _scissors(name, bow, rows):
    i = Icon(name)
    i.rows(legend(w=(IRON, 4), c=(IRON, 3), m=(IRON, 2), s=(IRON, 1), o=(IRON, 0), B=(BRASS, 0), b=(BRASS, 3),
                  y=(BRASS, 3), r=(bow, 3), R=(bow, 1), q=(bow, 0), k=(bow, 2)), rows)
    return i


def cutting_knife():
    """裁ちばさみ: tailor shears, closed: two long blades with a dark seam between them, a large and a small hollow red bow."""
    return _scissors('cutting_knife', RED, [
        "................",
        "..............w.",
        ".............wc.",
        "............wco.",
        "...........wcos.",
        "..........wcos..",
        ".........wcos...",
        "........yBos....",
        "..qqqq.Rqs......",
        ".qrrrRqR.R......",
        ".qr..Rq.rr......",
        ".qr..Rqr..R.....",
        ".qRRRRqr..R.....",
        "..qqqq..RR......",
        "................",
        "................",
    ])


def sterilizing_shears():
    """ぺにきりばさみ: short all-steel surgical scissors with small round bows; reads smaller and colder than the tailor shears."""
    return _scissors('sterilizing_shears', IRON, [
        "................",
        "................",
        "................",
        "............w...",
        "...........wco..",
        "..........wcso..",
        ".........wcso...",
        "...ooo..bBso....",
        "..orrmoRmo......",
        "..or.mo.Ro......",
        "..ommmoRro......",
        "...ooomr.mo.....",
        "......om.mo.....",
        "......ommmo.....",
        ".......ooo......",
        "................",
    ])


def _secateurs(name, grip, extra=None):
    """茎きりばさみ: pruning shears with a curved cutting blade over a hooked anvil and two bowed grips."""
    i = Icon(name)
    i.rows(legend(a=(grip, 1), c=(grip, 3), b=(grip, 0)), [
        "", "", "", "", "", "", "",
        "........",
        "......acb.......",
        "....aacb.acb....",
        "...acb..acb.....",
        "..acb..acb......",
        "..bb..acb.......",
        "......acb.......",
        ".....acb........",
        ".....bb.........",
    ])
    i.rows(legend(w=(IRON, 4), c=(IRON, 3), m=(IRON, 2), s=(IRON, 1), o=(IRON, 0), B=(BRASS, 0), b=(BRASS, 3)), [
        "................",
        "............oo..",
        "...........ocwo.",
        "..........ocwmo.",
        ".........ocwmo..",
        "........ocwmo...",
        "........cwmos...",
        ".......bBos.o...",
        "........osmmo...",
        ".........ooo....",
    ])
    if extra:
        extra(i)
    return i


def stem_shears():
    return _secateurs('stem_shears', WOOD)


def bottle_stem_shears():
    """茎きりばさみ(瓶に入れる): the pruning shears with pink grips and a small corked bottle of sugar water at their foot."""
    def bottle(i):
        i.erase([(x, y) for x in range(9, 16) for y in range(8, 16)])
        i.rows(legend(o=(GLASS, 0), g=(GLASS, 2), h=(GLASS, 3), s=(SYRUP, 2), S=(SYRUP, 1), L=(SYRUP, 3),
                      k=(WOOD, 3), K=(WOOD, 1), e=(GREEN, 2), E=(GREEN, 1)), [
            "", "", "", "", "", "", "", "",
            "...........kK...",
            "...........KK...",
            "..........ohgo..",
            ".........ohgEgo.",
            ".........hLseSo.",
            ".........gsSeSo.",
            ".........osSSSo.",
            "..........oooo..",
        ])
    return _secateurs('bottle_stem_shears', PINK, bottle)


def care_vibrator():
    """ローター: a pink egg lying on the tool diagonal with a seam round its middle, a thin cord to a small pink switch box."""
    i = Icon('care_vibrator')
    i.rows(legend(P=(PINK, 0), m=(PINK, 1), p=(PINK, 2), l=(PINK, 3), w=(PINK, 4), d=(PINK, 0), s=(STRING, 1)), [
        "................",
        "................",
        "..........PPPP..",
        "........PPllppP.",
        ".......PwllpppP.",
        ".......PllPppmP.",
        "......PllppPmP..",
        "......PlpppmPP..",
        "......PpppmPP...",
        ".....s.PPPP.....",
        ".....s..........",
        "....s...........",
        "PPPPP...........",
        "PlwlP...........",
        "PpdpP...........",
        "PPPPP...........",
    ])
    return i


def iron_enclosure():
    """箱閉じ込め: a closed iron box in oblique view (square front, lid on top, side to the right): a lid lip with a brass
    hasp, riveted front corners. The barred observation slot stays on the in-world mesh."""
    i = Icon('iron_enclosure')
    i.rows(legend(o=(IRON, 0), w=(IRON, 4), T=(IRON, 3), F=(IRON, 2), S=(IRON, 1), d=(IRON, 1), r=(IRON, 4),
                  y=(BRASS, 3), Y=(BRASS, 1)), [
        "................",
        "....oooooooooo..",
        "...owTTTTTTTToo.",
        "..owTTTTTTTToSo.",
        ".owTTTTTTTToSSo.",
        ".oooooooooooSSo.",
        ".owFFFFyFFFoSSo.",
        ".odddddYddddoSo.",
        ".orFFFFFFFFroSo.",
        ".oFFFFFFFFFFoSo.",
        ".oFFFFFFFFFFoSo.",
        ".oFFFFFFFFFFoSo.",
        ".oFFFFFFFFFFoo..",
        ".orFFFFFFFFro...",
        ".oooooooooooo...",
        "................",
    ])
    return i


def trimmed_hair():
    """A loose lock of cut hair: four soft strands drawn as connected, kinked lines, gathered at staggered cut ends (bottom
    left) and parting towards lighter tips (top right); no tie and no binding."""
    i = Icon('trimmed_hair')
    strands = [
        [(3, 13), (3, 12), (3, 11), (4, 11), (4, 10), (4, 9), (4, 8), (5, 8), (5, 7), (5, 6), (6, 6), (6, 5), (6, 4), (7, 4),
         (7, 3), (8, 3), (8, 2)],
        [(5, 12), (5, 11), (6, 11), (6, 10), (7, 10), (7, 9), (7, 8), (8, 8), (8, 7), (9, 7), (9, 6), (10, 6), (10, 5),
         (10, 4), (11, 4), (11, 3), (12, 3), (12, 2)],
        [(6, 13), (7, 13), (7, 12), (8, 12), (8, 11), (9, 11), (9, 10), (10, 10), (10, 9), (11, 9), (11, 8), (12, 8), (12, 7),
         (13, 7), (13, 6), (14, 6), (14, 5)],
        [(8, 14), (9, 14), (9, 13), (10, 13), (11, 13), (11, 12), (12, 12), (13, 12), (13, 11), (14, 11), (14, 10)],
    ]
    i.part += 1
    for strand in strands:
        n = len(strand)
        for k, (x, y) in enumerate(strand):
            step = 1 if k < 2 else 4 if k >= n - 2 else 3 if k >= n - 5 else 2
            i.px[(x, y)] = [HAIR, step, i.part]
    return i


def alcohol_lamp():
    """DRAFT (not shipped until the item exists): アルコールランプ. A squat clear glass lamp with a visible spirit line, a
    brass collar, a cotton wick and a small flame: blue outside, white-yellow core."""
    i = Icon('alcohol_lamp')
    i.rows(legend(o=(GLASS, 0), g=(GLASS, 3), h=(CLOTH, 4), a=(SPIRIT, 2), A=(SPIRIT, 1), L=(GLASS, 1),
                  B=(BRASS, 0), b=(BRASS, 2), y=(BRASS, 3), W=(CLOTH, 4),
                  f=(FLAME, 1), q=(FLAME, 0), c=(HOT, 4), C=(HOT, 3)), [
        "................",
        ".......q........",
        "......qfq.......",
        "......fcq.......",
        ".....qfcCq......",
        "......fCq.......",
        ".......W........",
        "......ByBB......",
        "......BbbB......",
        "....oogggoo.....",
        "...ohgggggLo....",
        "..ohgggggggLo...",
        "..oLLLLLLLLLo...",
        "..oaaaaaaaaAo...",
        "...oaaaaaaAo....",
        "....ooooooo.....",
    ])
    return i


ICONS = {
    'pin_tool': pin_tool, 'care_mallet': care_mallet, 'care_awl': care_awl, 'heated_awl': heated_awl,
    'restraint_frame': restraint_frame, 'wrap_sling': wrap_sling, 'hanging_hook': hanging_hook,
    'hair_clippers': hair_clippers, 'cutting_knife': cutting_knife, 'care_vibrator': care_vibrator,
    'sterilizing_shears': sterilizing_shears, 'iron_enclosure': iron_enclosure, 'stem_shears': stem_shears,
    'bottle_stem_shears': bottle_stem_shears, 'trimmed_hair': trimmed_hair,
}
DRAFTS = {'alcohol_lamp': alcohol_lamp}
SHIPPED = tuple(ICONS)


def outputs():
    return {SNAPSHOT / f'{name}.png': factory().png() for name, factory in {**ICONS, **DRAFTS}.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    sys.path.insert(0, str(ROOT / 'scripts'))
    from asset_export_checks import check_output, preserve_equivalent_png
    changed = []
    for path, content in outputs().items():
        content = preserve_equivalent_png(path, content)
        if not check_output(path, content):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    if args.check and changed:
        raise SystemExit('Operation tool icon snapshot differs: ' + ', '.join(changed))
    print(f'Operation tool icons: {len(ICONS)} shipped, {len(DRAFTS)} draft; ' + ('match.' if args.check else f'{len(changed)} files written.'))


if __name__ == '__main__':
    main()
