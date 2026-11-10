"""Worn swaddle textures for client/CareWearRenderer: the single leaf of the leaf swaddle, the cloth swaddle's fabric tiles and
its trim sheet.

Drawn here in the vanilla item style of supplies.py (hue-shifted local ramps, the outline is the darkest step of its own
material, no black, no anti-aliasing, no partial alpha). Nothing is copied from vanilla files.

swaddle_leaf_wear.png, 32x256: one broad leaf at the four stages of a neglected wrap (STAGES, 64 rows each: fresh, soiled,
dry yellow-brown, hard brown), each clean in its first 32 rows and stool-stained in the next 32 (the same pixels mixed towards the
stage's stool tone, outline kept; stage 1 only in one patch round the middle). u runs from the petiole (x 0-4, a two-pixel stalk on rows 15-16) to the pointed tip at
x 31; v runs across the blade, midrib on row 16. The renderer lays it as a diaper: petiole and blade base at the anus on the
back, the broadest part under the body, the tip up over the front outlet below the mouth (CareWearRenderer.leafWrap).

swaddle/<fabric>.png, 16x16 seamless tiles wrapped with GL_REPEAT at two texels per model unit: knit, cotton and fleece are
near-white and take the style's vertex colour; tiger, gingham and floral carry their own colours (tint white).

swaddle_trim.png, 64x64 cutout sheet of the non-repeating parts, each mapped whole onto one quad: lace frill scallop, ribbon
bow, pompom (near-white, tinted per style), rubber sole, strap, pouch, tiger mark (own colours) and a 5x7 capital alphabet
(white) for the letter style. TRIM records each rectangle; the renderer's UV constants follow it.

Run this file to rewrite the snapshots in art/props/pet-goods/; scripts/export_pet_goods_icons.py ships them to
textures/misc/. --check is read-only.
"""
import argparse, io, math, random, sys
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
SNAPSHOT = ROOT / 'art/props/pet-goods'

# ---- ramps (dark -> light) ------------------------------------------------------------------------------------------
LEAF = ['#24481a', '#33661f', '#447f27', '#579a30', '#74b443', '#98cc5e', '#bfe08a']
PETIOLE = ['#5a5a1e', '#7f8a2c', '#a3ad45']
STAIN = (168, 130, 64)    # muted brownish yellow of stool soaked into a leaf


def rgba(c):
    if isinstance(c, str):
        return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) + (255,)
    if len(c) == 4:
        return tuple(c)
    return tuple(c) + (255,)


def grey(v):
    return (v, v, v, 255)


def png(img):
    out = io.BytesIO()
    img.save(out, 'PNG', optimize=True)
    return out.getvalue()


# ---- leaf -----------------------------------------------------------------------------------------------------------
MID = 16          # midrib row
BLADE0 = 5        # first blade column; columns 0-4 carry the petiole
NICKS = {(12, True): 1.8, (13, True): 1.2, (23, False): 1.5}   # irregular bites in the margin


def half_width(x, top):
    """Blade half width above (top) or below the midrib at column x: a rounded base, broadest a little before the middle (the
    part that lies under the body), tapering to a point at x 31; a tooth standing a pixel proud every fourth column and a few irregular bites."""
    t = (x - BLADE0 + .5) / (32 - BLADE0)
    if t <= 0 or t >= 1:
        return -1
    shape = math.sin(math.pi * t ** .82) ** .7 * (1 - .4 * max(0, t - .66) / .34)
    w = (12.6 if top else 12.2) * shape + (.5 if top else -.4) * (t - .5)
    if (x + (0 if top else 2)) % 4 == 0 and .06 < t < .95:
        w += 1.0                                                # teeth stand one pixel proud of the margin
    return w - NICKS.get((x, top), 0)


def leaf_steps():
    """The fresh leaf as ramp steps: blade pixels -> step (0 outline .. 6 midrib), the margin band, and the petiole pixels -> petiole step."""
    inside = set()
    for x in range(32):
        for top in (True, False):
            w = half_width(x, top)
            if w < 0:
                continue
            for d in range(0, 16):
                y = MID - d if top else MID + d
                if 0 <= y < 32 and d <= w + .3:
                    inside.add((x, y))
    rng = random.Random(41)
    colour, margin = {}, set()
    for x, y in inside:
        d = abs(y - MID)
        w = half_width(x, y < MID)
        step = 3
        if w - d < 2.2:
            step = 2                                            # a darker band along the margin rounds the blade
            margin.add((x, y))
        if y < MID and d < 5 and x < 20:
            step = 4                                            # soft sheen on one side of the midrib
        if rng.random() < .05:
            step += rng.choice((-1, 1))
        colour[(x, y)] = max(1, min(4, step))
    # side veins, alternating, angled towards the tip and fading before the margin
    for x0, top in ((8, True), (10, False), (14, True), (16, False), (20, True), (22, False), (25, True), (27, False)):
        for k in range(1, 12):
            x, y = x0 + round(k * 1.4), MID - k if top else MID + k
            if (x, y) not in inside or half_width(x, top) - k < 2:
                break
            colour[(x, y)] = 5
    for x in range(BLADE0 - 1, 31):                             # midrib, lightest, a darker step towards the tip
        if (x, MID) in inside or x < BLADE0:
            colour[(x, MID)] = 6 if x < 22 else 5
    # outline: the darkest step on the outer margin, one lighter where the blade meets the light (upper side)
    outline = set()
    for x, y in inside:
        out = [(x + dx, y + dy) not in inside for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if any(out):
            colour[(x, y)] = 1 if (out[3] and y < MID and not out[0]) else 0
            outline.add((x, y))
    petiole = {}
    for x in range(0, BLADE0):                                  # the petiole: a two-pixel stalk running into the midrib
        petiole[(x, MID - 1)] = 2 if x < 4 else 1
        petiole[(x, MID)] = 1
    petiole[(0, MID - 1)] = 1; petiole[(0, MID)] = 0
    return inside, colour, margin - outline, outline, petiole


def crease(colour, inside, outline, rng, count):
    """Dry crinkles: short dark folds running across the blade, each with a lit pixel on its tip side, kept off the outline and midrib."""
    blade = sorted(p for p in inside if p not in outline and p[1] != MID and p[0] > BLADE0 + 1)
    for _ in range(count):
        x, y = rng.choice(blade)
        dy = 1 if y > MID else -1
        for k in range(rng.choice((2, 3, 3, 4))):
            p, lit = (x + (k // 2) * rng.choice((0, 1)), y + dy * k), None
            if p not in inside or p in outline or p[1] == MID:
                break
            colour[p] = 1
            lit = (p[0] + 1, p[1])
            if lit in inside and lit not in outline and lit[1] != MID:
                colour[lit] = min(6, colour[lit] + 1)


# Leaf stages of a neglected wrap (FEATURES 树叶襁褓; art decision swaddle-neglect, palette A): 0 fresh, 1 soiled (yellowing margin, the
# stool soaked in as one patch), 2 dry yellow-brown with a darker dry edge and crinkles, 3 hard brown, crinkled, a taut sheen under the body.
DRY = ['#3e2c10', '#5a4318', '#77601f', '#94802a', '#ac9a36', '#c4b24c', '#dacb76']
HARD = ['#2a170a', '#3f2410', '#563217', '#6c401d', '#835226', '#9a6634', '#b4834c']
PETIOLE_DRY = ['#4a3c16', '#6b5a22', '#8c7a34']
PETIOLE_HARD = ['#2e1c0c', '#4a2e14', '#664222']
TIP = 19                                                        # first column of the front tip (CareWearRenderer: the upper front blade)
STAGES = (  # ramp, petiole ramp, margin mix towards DRY, crinkles, margin darker by, sheen, stain colour, stain amount, patch only
    (LEAF, PETIOLE, 0, 0, 0, False, STAIN, (.6, .75), False),
    (LEAF, PETIOLE, .45, 0, 0, False, STAIN, (.78, .9), True),
    (DRY, PETIOLE_DRY, 0, 9, 1, False, (128, 92, 40), (.4, .5), False),
    (HARD, PETIOLE_HARD, 0, 15, 1, True, (74, 46, 24), (.12, .18), False),
)


def mix(a, b, k):
    return tuple(round(x * (1 - k) + y * k) for x, y in zip(a[:3], b[:3])) + (255,)


def leaf_cell(stage):
    ramp, petiole_ramp, yellow, creases, edge, sheen = STAGES[stage][:6]
    inside, colour, margin, outline, petiole = leaf_steps()
    rng = random.Random(70 + stage)
    if creases:
        crease(colour, inside, outline, rng, creases)
    for p in margin:
        colour[p] = max(1, colour[p] - edge)                    # the drying edge goes first
    if sheen:                                                   # stretched taut over the swollen bottom: one step lighter under the body
        for x, y in inside:
            if 11 <= x <= 17 and 2 <= abs(y - MID) <= 7 and (x, y) not in outline and colour[(x, y)] != 1:
                colour[(x, y)] = min(5, colour[(x, y)] + 1)
        # the tip lies on the face under the mouth: the taut step, no darkest crinkle or edge pixels, so it reads as leaf, not a moustache
        for x, y in inside:
            if x >= TIP:
                colour[(x, y)] = 4 if (x, y) in outline else max(5, colour[(x, y)])
    cell = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    for xy, step in colour.items():
        c = rgba(ramp[step])
        if yellow and (xy in margin or xy in outline):
            c = mix(c, rgba(DRY[step]), yellow)
        cell.putpixel(xy, c)
    for xy, step in petiole.items():
        cell.putpixel(xy, rgba(petiole_ramp[step]))
    return cell


def stained(cell, stage):
    """The same leaf with the stool soaked in, keeping its own light and dark steps: through the whole blade, or (stage 1) one patch round
    the middle of the blade, so the first soiling reads as a stain rather than a different leaf."""
    tone, (lo, hi), patch = STAGES[stage][6:9]
    out = cell.copy()
    rng = random.Random(5 + stage)
    for x in range(cell.width):
        for y in range(cell.height):
            r, g, b, a = cell.getpixel((x, y))
            if a == 0:
                continue
            if patch:
                reach = 7 + 1.5 * math.sin(x * 1.3) + rng.random()
                if abs(y - MID) > reach or x < BLADE0 + 3:
                    continue
            k = lo + (hi - lo) * rng.random()
            lum = (r + g + b) / 3 / 105
            out.putpixel((x, y), tuple(min(255, round((c * (1 - k) + s * k * lum))) for c, s in zip((r, g, b), tone)) + (255,))
    return out


def leaf_sheet():
    sheet = Image.new('RGBA', (32, 64 * len(STAGES)), (0, 0, 0, 0))
    for stage in range(len(STAGES)):
        leaf = leaf_cell(stage)
        sheet.paste(leaf, (0, 64 * stage)); sheet.paste(stained(leaf, stage), (0, 64 * stage + 32))
    return sheet


# ---- fabric tiles (16x16, seamless) ----------------------------------------------------------------------------------
def tile(draw):
    img = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), rgba(draw(x, y)))
    return img


KNIT = ["3434", "2323", "4343", "1212"]           # fine garter knit: soft horizontal ridges with small offset stitch bumps


def knit(x, y):
    return grey((226, 236, 244, 250, 255)[int(KNIT[y % 4][x % 4])])


def cotton(x, y):
    """Plain fine weave: a faint diagonal twill."""
    return grey(240 if (x + y) % 4 == 0 else 248 if (x - y) % 8 == 3 else 253)


def fleece(x, y):
    """Soft pile: two-texel tufts, mostly light, a few shaded ones."""
    rng = random.Random((x // 2) * 31 + (y // 2) * 7 + 3)
    tuft = rng.randrange(6)
    return grey((236, 244, 248, 252, 255, 255)[tuft] - (4 if (x + y) % 2 else 0))


TIGER = ['#7a3a12', '#b0561a', '#e08a2a', '#f2aa3c', '#fbc865']
TIGER_STRIPES = [  # two broad tapered stripes per tile, slanting; '.' fur
    ".......aAa......",
    "......aAAa......",
    "......AAAa......",
    ".....aAAa.......",
    ".....AAAa.......",
    ".....AAa........",
    ".....AAa........",
    ".....Aa.........",
    ".....a.....aAa..",
    "..........aAAa..",
    "..........AAAa..",
    ".........aAAa...",
    ".........AAAa...",
    ".........AAa....",
    ".........Aa.....",
    "........aAa.....",
]


def tiger(x, y):
    ch = TIGER_STRIPES[y][x]
    if ch == 'A':
        return TIGER[0]
    if ch == 'a':
        return TIGER[1]
    return TIGER[3] if (x * 3 + y * 5) % 11 else TIGER[4] if y % 2 else TIGER[2]


GINGHAM = {'none': '#f8f8f4', 'one': '#a9c1e6', 'both': '#5f82c6'}


def gingham(x, y):
    a, b = (x // 4) % 2 == 0, (y // 4) % 2 == 0
    c = GINGHAM['both'] if a and b else GINGHAM['one'] if a or b else GINGHAM['none']
    if c == GINGHAM['none'] and (x + y) % 2 == 0:
        return '#f0f0ea'
    return c


FLORAL_BASE = ['#f2ebd8', '#fbf6e8']
FLORAL = [  # small scattered flowers: pink and blue five-petal heads with a yellow centre, a few green leaves
    "................",
    "..p.............",
    ".pyp.........g..",
    "..p.........gg..",
    "...g............",
    "..........b.....",
    ".........byb....",
    "..........b.....",
    ".....gg...g.....",
    "................",
    "..............p.",
    ".b...........pyp",
    "byb...........p.",
    ".b....p.........",
    "...g.pyp........",
    "......p.........",
]
FLORAL_COLOURS = {'p': '#e889a6', 'b': '#7fa6e0', 'y': '#f4cc4a', 'g': '#78ae5a'}


def floral(x, y):
    ch = FLORAL[y][x]
    return FLORAL_COLOURS[ch] if ch in FLORAL_COLOURS else FLORAL_BASE[(x + y * 3) % 5 != 0]


FABRICS = {'knit': knit, 'cotton': cotton, 'fleece': fleece, 'tiger': tiger, 'gingham': gingham, 'floral': floral}


# ---- trim sheet (64x64 cutout) ---------------------------------------------------------------------------------------
# name: (x, y, w, h). The renderer's TRIM constants are these rectangles divided by 64.
TRIM = {'frill': (0, 0, 16, 8), 'bow': (16, 0, 16, 12), 'mark': (32, 0, 16, 16), 'pouch': (48, 0, 16, 16),
        'pompom': (0, 8, 8, 8), 'sole': (8, 8, 8, 8), 'strap': (0, 16, 8, 8), 'letters': (0, 32, 64, 32)}

FRILL = [  # gathered band on top, lace body with eyelets, a scalloped lower edge ('.' transparent)
    "2222222222222222",
    "4343434343434343",
    "4444444444444444",
    "44.4444.4444.444",
    "4444444444444444",
    "3444444434444443",
    ".34444443344443.",
    "..333333..3333..",
]
BOW = [  # ribbon bow: two loops, a knot, two tails
    ".11..........11.",
    "1441........1441",
    "14431......13441",
    "144331....133441",
    "1443321111233441",
    "1443332442333441",
    "1433332442333341",
    ".133321441233331",
    "..11113443111111",
    "......14331.....",
    ".....1431.131...",
    ".....11.....11..",
]
POMPOM = [
    "..1221..",
    ".134431.",
    "13444431",
    "24444442",
    "23444432",
    "13344331",
    ".123321.",
    "..1111..",
]
GREYS = {'1': 150, '2': 190, '3': 222, '4': 250}
SOLE = ['#2e3036', '#41444b', '#575b63']
STRAP = ['#6a4a2a', '#94693c', '#b68a54']
POUCH = [  # a canvas pouch with a rounded flap and a button
    "................",
    "................",
    "..oooooooooooo..",
    ".oLLLLLLLLLLLLo.",
    ".oLmmmmmmmmmmdo.",
    ".oLmmmmmmmmmmdo.",
    ".ooddmmmmmmddoo.",
    ".oLooddbbddoodo.",
    ".oLmmmooBoommdo.",
    ".oLmmmmmmmmmmdo.",
    ".oLmmmmmmmmmmdo.",
    ".oLmmmmmmmmmmdo.",
    ".oLmmmmmmmmmmdo.",
    ".oddmmmmmmmmddo.",
    "..oooooooooooo..",
    "................",
]
POUCH_COLOURS = {'o': '#6b5232', 'd': '#9a7a4c', 'm': '#c8a874', 'L': '#e0c696', 'b': '#e85a4a', 'B': '#a8322a'}
MARK = [  # the tiger's forehead mark of EH34 (a vertical stroke crossed by three bars), dark brown on transparent
    "................",
    ".......aa.......",
    ".......AA.......",
    "..aAAAAAAAAAAa..",
    "...aaaaAAaaaa...",
    ".......AA.......",
    "...aAAAAAAAAa...",
    "....aaaAAaaa....",
    ".......AA.......",
    "..aAAAAAAAAAAa..",
    "...aaaaAAaaaa...",
    ".......AA.......",
    ".......Aa.......",
    ".......a........",
    "................",
    "................",
]
FONT = {  # 5x7 bold capitals for the letter style (EH34's initial-lettered clothes); one glyph per 8x8 cell, A..Z
    'A': ["01110", "11011", "11011", "11111", "11011", "11011", "11011"],
    'B': ["11110", "11011", "11011", "11110", "11011", "11011", "11110"],
    'C': ["01111", "11000", "11000", "11000", "11000", "11000", "01111"],
    'D': ["11110", "11011", "11011", "11011", "11011", "11011", "11110"],
    'E': ["11111", "11000", "11000", "11110", "11000", "11000", "11111"],
    'F': ["11111", "11000", "11000", "11110", "11000", "11000", "11000"],
    'G': ["01111", "11000", "11000", "11011", "11011", "11011", "01111"],
    'H': ["11011", "11011", "11011", "11111", "11011", "11011", "11011"],
    'I': ["11111", "01110", "01110", "01110", "01110", "01110", "11111"],
    'J': ["00111", "00011", "00011", "00011", "11011", "11011", "01110"],
    'K': ["11011", "11011", "11110", "11100", "11110", "11011", "11011"],
    'L': ["11000", "11000", "11000", "11000", "11000", "11000", "11111"],
    'M': ["10001", "11011", "11111", "10101", "10001", "10001", "10001"],
    'N': ["10001", "11001", "11101", "10111", "10011", "10001", "10001"],
    'O': ["01110", "11011", "11011", "11011", "11011", "11011", "01110"],
    'P': ["11110", "11011", "11011", "11110", "11000", "11000", "11000"],
    'Q': ["01110", "11011", "11011", "11011", "11011", "11110", "01111"],
    'R': ["11110", "11011", "11011", "11110", "11100", "11110", "11011"],
    'S': ["01111", "11000", "11000", "01110", "00011", "00011", "11110"],
    'T': ["11111", "01110", "01110", "01110", "01110", "01110", "01110"],
    'U': ["11011", "11011", "11011", "11011", "11011", "11011", "01110"],
    'V': ["11011", "11011", "11011", "11011", "11011", "01110", "00100"],
    'W': ["10001", "10001", "10001", "10101", "11111", "11011", "10001"],
    'X': ["11011", "11011", "01110", "00100", "01110", "11011", "11011"],
    'Y': ["11011", "11011", "11011", "01110", "01110", "01110", "01110"],
    'Z': ["11111", "00011", "00110", "01100", "11000", "11000", "11111"],
}


def trim_sheet():
    sheet = Image.new('RGBA', (64, 64), (0, 0, 0, 0))

    def put(name, rows, colour):
        x0, y0, _, _ = TRIM[name]
        for y, line in enumerate(rows):
            for x, ch in enumerate(line):
                c = colour(ch, x, y)
                if c is not None:
                    sheet.putpixel((x0 + x, y0 + y), rgba(c))
    put('frill', FRILL, lambda ch, x, y: grey({'2': 205, '3': 228, '4': 252}[ch]) if ch != '.' else None)
    put('bow', BOW, lambda ch, x, y: grey(GREYS[ch]) if ch != '.' else None)
    put('pompom', POMPOM, lambda ch, x, y: grey(GREYS[ch]) if ch != '.' else None)
    put('sole', ["0" * 8] + ["2" * 8] + ["1" * 8] + ["11011011"] * 2 + ["1" * 8] * 2 + ["0" * 8],
        lambda ch, x, y: SOLE[int(ch)])
    put('strap', ["01111110"] * 8, lambda ch, x, y: STRAP[0] if ch == '0' else STRAP[2] if y % 3 == 0 else STRAP[1])
    put('pouch', POUCH, lambda ch, x, y: POUCH_COLOURS.get(ch))
    put('mark', MARK, lambda ch, x, y: TIGER[0] if ch == 'A' else TIGER[1] if ch == 'a' else None)
    mx, my, _, _ = TRIM['mark']                                 # a cream rim keeps the mark readable over the striped fur
    for y in range(16):
        for x in range(16):
            if MARK[y][x] == '.' and any(0 <= x + dx < 16 and 0 <= y + dy < 16 and MARK[y + dy][x + dx] != '.'
                                         for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                sheet.putpixel((mx + x, my + y), rgba('#fbe3b8'))
    x0, y0, _, _ = TRIM['letters']
    for n, (letter, rows) in enumerate(sorted(FONT.items())):
        cx, cy = x0 + n % 8 * 8 + 1, y0 + n // 8 * 8
        for y, line in enumerate(rows):
            for x, ch in enumerate(line):
                if ch == '1':
                    sheet.putpixel((cx + x, cy + y), grey(255))
                    for dx, dy in ((1, 0), (0, 1)):     # a shaded rim on the lower right gives the stitched letter some depth
                        nx, ny = x + dx, y + dy
                        inside = 0 <= ny < 7 and 0 <= nx < 5 and rows[ny][nx] == '1'
                        if not inside and cx + nx < x0 + 64 and cy + ny < y0 + 32 and sheet.getpixel((cx + nx, cy + ny))[3] == 0:
                            sheet.putpixel((cx + nx, cy + ny), grey(178))
    return sheet


def outputs():
    out = {SNAPSHOT / 'swaddle_leaf_wear.png': png(leaf_sheet()), SNAPSHOT / 'swaddle_trim.png': png(trim_sheet())}
    for name, draw in FABRICS.items():
        out[SNAPSHOT / 'swaddle' / f'{name}.png'] = png(tile(draw))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    sys.path.insert(0, str(ROOT / 'scripts'))
    from asset_export_checks import check_output, preserve_equivalent_png
    changed = []
    files = outputs()
    for path, content in files.items():
        content = preserve_equivalent_png(path, content)
        if not check_output(path, content):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    if args.check and changed:
        raise SystemExit('Wear texture snapshot differs: ' + ', '.join(changed))
    print(f'Wear textures: {len(files)}; ' + ('match.' if args.check else f'{len(changed)} files written.'))


if __name__ == '__main__':
    main()
