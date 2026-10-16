"""Authoring script for the shop GUI kit (one 256x256 atlas, vanilla-style bevel, light from the top left).

Writes art/props/ui/shop_ui.png and copies it to src/main/resources/assets/mcyukkuri/textures/gui/shop_ui.png,
plus manifest.json. Grade chips and the gold accents read PALETTES from art/props/badges/author_badges.py (locked values).

Atlas map (x, y, w, h):
  panel 9-slice   (0,0,24,24) corner 4      card (24,0,16,16) corner 3      card_selected (40,0,16,16) corner 3
  tab normal/hover/selected (56|72|88,0,16,16) corner 3                       star full/empty (104|113,0,9,9)
  bar track (0,24,8,6) caps 2                bar fills (8+8*i,24,8,4) i = manners, academics, wits, poise
  grade chips (0|9|18|27,48,9,9) bronze, silver, gold, expired
  icons 16x16 at (16*i,32): live, goods, buyback, consult, exam, profile, keeper
"""
import hashlib, json, shutil, sys
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / 'art' / 'props' / 'badges'))
from author_badges import PALETTES  # noqa: E402

def rgb(h):
    h = h.lstrip('#'); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)

# Parchment ramp (3-4 shades + outline), warm grey.
OUT, LIGHT, BASE, BASE2, SHADE, DEEP = map(rgb, ('#3a3126', '#fbf3de', '#e6d8b8', '#e0d1ae', '#b3a07c', '#8e7c5a'))
CARD, CARD2, CARD_IN = map(rgb, ('#f1e8d0', '#ece1c6', '#a8946f'))
TEAL, TEAL_L = rgb('#30484a'), rgb('#5f8a86')
atlas = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
px = atlas.load()

def put(x, y, c): px[x, y] = c

def rect(x, y, w, h, c):
    for j in range(h):
        for i in range(w): put(x + i, y + j, c)

def bevel(x, y, w, h, outline, hi, lo, fill, fill2=None, notch=True, inset=False):
    """Vanilla bevel box: 1px outline, light top-left inner edge, dark bottom-right inner edge."""
    if inset: hi, lo = lo, hi
    rect(x, y, w, h, fill)
    if fill2:  # sparse tileable speckle so large panels are not flat
        for j in range(1, h - 1):
            for i in range(1, w - 1):
                if (i * 5 + j * 3) % 11 == 0: put(x + i, y + j, fill2)
    for i in range(w): put(x + i, y, outline); put(x + i, y + h - 1, outline)
    for j in range(h): put(x, y + j, outline); put(x + w - 1, y + j, outline)
    for i in range(1, w - 1): put(x + i, y + 1, hi); put(x + i, y + h - 2, lo)
    for j in range(1, h - 1): put(x + 1, y + j, hi); put(x + w - 2, y + j, lo)
    put(x + w - 2, y + 1, fill); put(x + 1, y + h - 2, fill)  # corner meeting of the two bevels
    if notch:
        for cx, cy in ((x, y), (x + w - 1, y), (x, y + h - 1), (x + w - 1, y + h - 1)): put(cx, cy, (0, 0, 0, 0))

# --- panel (24x24): 2px thick outer light/shade so the 4px corner reads as vanilla
rect(0, 0, 24, 24, BASE)
bevel(0, 0, 24, 24, OUT, LIGHT, SHADE, BASE, BASE2)
for i in range(2, 22): put(i, 2, LIGHT); put(2, i, LIGHT); put(i, 21, SHADE); put(21, i, SHADE)  # 2nd bevel ring
for i in range(3, 21): put(i, 3, BASE); put(3, i, BASE); put(i, 20, BASE); put(20, i, BASE)
put(21, 2, BASE); put(2, 21, BASE)
for j in range(4, 20):  # restore the speckle on the centre tile only (tile = 16x16 at 4,4)
    for i in range(4, 20):
        put(i, j, BASE2 if (i * 5 + j * 3) % 11 == 0 else BASE)
# --- card (inset) and selected card (gold accents from the locked palette)
bevel(24, 0, 16, 16, CARD_IN, SHADE, LIGHT, CARD, inset=True)
rect(25, 1, 14, 14, CARD)
for i in range(1, 15): put(24 + i, 1, SHADE); put(24 + i, 14, LIGHT)
for j in range(1, 15): put(25, j, SHADE); put(38, j, LIGHT)
put(38, 1, CARD); put(25, 14, CARD)
G = PALETTES['gold']
gold0, gold2 = rgb('#7a4a10'), rgb('#f0c040')  # selected-card rim: dark outer, bright inner (brightness cue, not hue only)
sel_fill = rgb('#f8edc2')
rect(40, 0, 16, 16, sel_fill)
for i in range(16): put(40 + i, 0, gold0); put(40 + i, 15, gold0)
for j in range(16): put(40, j, gold0); put(55, j, gold0)
for i in range(1, 15): put(40 + i, 1, gold2); put(40 + i, 14, gold2)
for j in range(1, 15): put(41, j, gold2); put(54, j, gold2)
for cx, cy in ((40, 0), (55, 0), (40, 15), (55, 15)): put(cx, cy, (0, 0, 0, 0))
# --- tabs: raised key; hover brighter; selected is lit, inset-less, with a 2px teal underline (greyscale-safe)
bevel(56, 0, 16, 16, OUT, LIGHT, SHADE, rgb('#d8c8a2'))
bevel(72, 0, 16, 16, OUT, LIGHT, SHADE, rgb('#e2d5b2'))  # hover ~214, normal ~200, selected ~243 (luminance)
bevel(88, 0, 16, 16, OUT, LIGHT, SHADE, LIGHT, notch=True)
for i in range(1, 15): put(88 + i, 13, TEAL_L); put(88 + i, 14, TEAL)  # underline sits in the bottom border slice so it never stretches
# --- stars 9x9
STAR = ['....o....', '...oLo...', '...oMo...', 'ooooMoooo', 'oLMMMMMDo', '.oMMMMDo.', '.oMMMMDo.', '.oMDoMDo.', '.oo...oo.']
def star(x0, pal):
    for j, row in enumerate(STAR):
        for i, ch in enumerate(row):
            if ch != '.': put(x0 + i, j, pal[ch])
star(104, {'o': rgb(G['outline']), 'L': rgb(G['light']), 'M': rgb(G['shade'][2]), 'D': rgb(G['shade'][1])})
star(113, {'o': DEEP, 'L': rgb('#cdbf9d'), 'M': rgb('#bfb08c'), 'D': rgb('#a89a78')})
# --- ability bars: track + four fills, each a different pattern (solid / diagonal / dots / vertical stripes)
TRACK = rgb('#4a3f30'); TRACK_IN = rgb('#2c251c')
rect(0, 24, 8, 6, TRACK);
for i in range(8): put(i, 24, OUT); put(i, 29, LIGHT)
for j in range(6): put(0, 24 + j, OUT); put(7, 24 + j, OUT)
for i in range(1, 7): put(i, 25, TRACK_IN)
put(0, 29, OUT); put(7, 29, OUT)
FILLS = [('#c4503a', '#e4806a', '#8c2e22'), ('#4f9a3f', '#82c46a', '#2f6a28'), ('#3f78c4', '#78a8e8', '#264e8c'), ('#8a4fb8', '#b684de', '#5a2e84')]
def fill_pattern(i, x, y):  # y 0..3 ; returns shade index 0 light 1 base 2 dark
    if y == 0: return 0
    if y == 3: return 2
    if i == 0: return 1
    if i == 1: return 2 if (x + y) % 4 == 0 else 1       # diagonal
    if i == 2: return 0 if (x % 4 == 1 and y == 1) or (x % 4 == 3 and y == 2) else 1  # dots
    return 2 if x % 4 < 2 else 1                          # vertical stripes
for n, cols in enumerate(FILLS):
    for y in range(4):
        for x in range(8): put(8 + n * 8 + x, 24 + y, rgb(cols[fill_pattern(n, x, y)]))
# --- grade chips (9x9) from the locked palette: bronze plain disc, silver double ring, gold toothed disc
def chip(x0, tier):
    p = PALETTES[tier]; o, s0, s1, s2, li = rgb(p['outline']), rgb(p['shade'][0]), rgb(p['shade'][1]), rgb(p['shade'][2]), rgb(p['light'])
    for j in range(9):
        for i in range(9):
            d = ((i - 4) ** 2 + (j - 4) ** 2) ** .5
            if d <= 4.3: put(x0 + i, 48 + j, o)
    for j in range(9):
        for i in range(9):
            d = ((i - 4) ** 2 + (j - 4) ** 2) ** .5
            if d <= 3.3:
                put(x0 + i, 48 + j, s2 if (i + j) < 7 else s0)
            if d <= 2.2: put(x0 + i, 48 + j, li if (i + j) < 6 else s1)
    if tier == 'silver':
        for j in range(9):
            for i in range(9):
                d = ((i - 4) ** 2 + (j - 4) ** 2) ** .5
                if 2.2 < d <= 3.3: put(x0 + i, 48 + j, rgb(PALETTES['silver']['engrave']) if (i + j) >= 7 else s1)
    if tier == 'gold':
        for i, j in ((4, 0), (4, 8), (0, 4), (8, 4)): put(x0 + i, 48 + j, o)
    if tier == 'bronze':
        put(x0 + 3, 48 + 3, rgb(p['spec']))
for n, t in enumerate(('bronze', 'silver', 'gold')): chip(n * 9, t)

# expired grade chip: desaturated disc with a 2px dark red cross (no 1px-only cue)
def expired_chip(x0):
    o, hi, mid, lo, red = rgb('#4a4a46'), rgb('#cfcfc8'), rgb('#a8a8a0'), rgb('#7e7e78'), rgb('#9a3b32')
    for j in range(9):
        for i in range(9):
            d = ((i - 4) ** 2 + (j - 4) ** 2) ** .5
            if d <= 4.3: put(x0 + i, 48 + j, o)
            if d <= 3.3: put(x0 + i, 48 + j, hi if i + j < 6 else (lo if i + j > 9 else mid))
    for k in range(2, 7):
        for dx in (0, 1):
            put(x0 + k + dx - (1 if k > 4 else 0), 48 + k, red); put(x0 + 8 - k - dx + (1 if k > 4 else 0), 48 + k, red)
expired_chip(27)

# --- 16x16 tab icons
ICONS = {}
ICONS['live'] = ([
 "................", ".ooooo.oo.ooooo.", "oRhhRWoDDoWhhRRo", "oRRRRroDDorRRRRo", ".ooooooKKoooooo.", "..oKKKKKKKKKKo..",
 ".oKkKKKKKKKKKKo.", ".oKSSSSSSSSSSKo.", ".oKSeESSSSeESKo.", ".oKSEESSSSEESKo.", ".oSpSSSSSSSSpSo.", ".osSSSSMMSSSSso.",
 "..osssssssssso..", "...oooooooooo...", "................", "................"],
 {'o': '#1f1b18', 'K': '#3c3632', 'k': '#6a625c', 'R': '#c8402e', 'r': '#8e2a20', 'h': '#ee6f56', 'W': '#fbf3de', 'D': '#6e1c16', 'S': '#f6e2c8',
  's': '#d4b090', 'e': '#ffffff', 'E': '#2a2024', 'p': '#ee9c98', 'M': '#c8402e'})
ICONS['goods'] = ([
 "................", "................", "................", "......oooo......", "....ooYYYYoo....", "...oYYYgYYYYo...", "..oYYgYYYYgYYo..",
 ".oYYYYYgYYYYYYo.", ".oLLLLLLLLLLLLo.", ".oBBBBBBBBBBDDo.", "..oBBBBBBBBDDo..", "...oBBBBBBDDo...", "....oDDDDDDo....", "....oooooooo....", "................", "................"],
 {'o': '#3a2410', 'Y': '#e8c040', 'g': '#b88a20', 'L': '#e8806a', 'B': '#c0502e', 'D': '#8c2e22'})
ICONS['buyback'] = ([
 "................", ".........ooooo..", "........oLLMMMo.", ".......oLMMMMMDo", ".......oMMdddMDo", ".......oMMdMdMDo", ".......oMMdddMDo",
 ".......oMMMMMDDo", "........oMDDDDo.", "...k.....ooooo..", "..kTkkkkkkkkkkk.", ".kTTTTTTTTTTTTk.", "kTTtttttttttttk.", ".kTnnnnnnnnnnk..", "..knnkkkkkkkkk..", "...k............"],
 {'o': '#3a2410', 'L': '#ffe58a', 'M': '#e8aa2e', 'D': '#ba781e', 'd': '#ba781e', 'k': '#1c2c2e', 'T': '#8fb8b2', 't': '#5f8a86', 'n': '#30484a'})
ICONS['consult'] = ([
 "................", "................", "..ooooooooooooo.", ".oWWWWWWWWWWWWo.", ".oWWWWWWWWWWWDo.", ".oWWgWWgWWgWWDo.", ".oWWgWWgWWgWWDo.",
 ".oWWWWWWWWWWWDo.", ".oWWWWWWWWWWDDo.", "..oDDDDDDooooo..", "...ooWWDo.......", "....oWDo........", ".....oo.........", "................", "................", "................"],
 {'o': '#30484a', 'W': '#fbf3de', 'D': '#b3a07c', 'g': '#30484a'})
ICONS['exam'] = ([
 "................", ".....ooooo......", "....oLMMMDo.....", "....oMMdMMDo....", "....oMdddMDo....", "....oMMdMMDo....", ".....oMMMDo.....",
 "......oDDo......", ".....ooooooo....", "...oPPPPPPPPo...", "...oPppppppDo...", "...oPpwwwwpDo...", "...oPpppppDDo...", "...oPPPPPDDDo...", "...ooooooooo....", "................"],
 {'o': '#2a1c0c', 'L': '#ffe58a', 'M': '#fad258', 'D': '#ba781e', 'd': '#e8aa2e', 'P': '#a8784a', 'p': '#8a5c34', 'w': '#fbf3de'})
ICONS['profile'] = ([
 "................", "..ooooooooooo...", ".oBBBBBBBBBBBo..", ".oBbLLLLLLLbBo..", ".oBbLMMMMMLbBo..", ".oBbLMdMdMLbBo..", ".oBbLMMdMMLbBo..",
 ".oBbLMMMMMLbBo..", ".oBbLLLLLLLbBo..", ".oBBBBBBBBBBBo..", ".oWBBBBBBBBBBo..", ".oWWoooooooooo..", ".ooWWWWWWWWWo...", "..oooooooooo....", "................", "................"],
 {'o': '#1e2c3c', 'B': '#3f78c4', 'b': '#264e8c', 'L': '#fbf3de', 'M': '#fad258', 'd': '#ba781e', 'W': '#e6d8b8'})
ICONS['keeper'] = ([
 "................", "...ooo...ooo....", "..oRhRo.oRRRo...", "..oRRRRoRRRro...", "..oRRRRRRRRro...", "...oRRRRRRro....", "....oRRRRro.....",
 ".....oRRro......", "......oro.......", ".oooooooooooo...", "oYYyYYyYYyYYYo..", "oyYYyyYYyYYyYo..", ".oyYYyYyYYyyo...", "..ooyyyyyyoo....", "....oooooo......", "................"],
 {'o': '#3a2410', 'R': '#d04a3a', 'r': '#8e2a20', 'h': '#f07a64', 'Y': '#e8c870', 'y': '#b88a40'})
HANDBOOK = Image.open(ROOT / 'art' / 'props' / 'badges' / 'badge_handbook.png').convert('RGBA')
assert HANDBOOK.size == (16, 16)
ORDER = ['live', 'goods', 'buyback', 'consult', 'exam', 'profile', 'keeper']
for n, name in enumerate(ORDER):
    if name == 'profile':  # same pixels as the item texture (checklist 14)
        atlas.paste(HANDBOOK, (16 * n, 32)); continue
    rows, pal = ICONS[name]
    assert len(rows) == 16, (name, len(rows))
    for j, row in enumerate(rows):
        assert len(row) <= 16, (name, j, len(row), row)
        row = row.ljust(16, '.')
        for i, ch in enumerate(row):
            if ch != '.': put(16 * n + i, 32 + j, rgb(pal[ch]))

OUTPUTS = {
    'art/props/ui/shop_ui.png': HERE / 'shop_ui.png',
    'src/main/resources/assets/mcyukkuri/textures/gui/shop_ui.png': ROOT / 'src/main/resources/assets/mcyukkuri/textures/gui/shop_ui.png',
}
if __name__ == '__main__':
    HERE.joinpath('shop_ui.png').parent.mkdir(exist_ok=True)
    atlas.save(HERE / 'shop_ui.png')
    runtime = OUTPUTS['src/main/resources/assets/mcyukkuri/textures/gui/shop_ui.png']
    runtime.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(HERE / 'shop_ui.png', runtime)
    manifest = {'status': 'pending_user_confirmation', 'source': 'art/props/ui/author_shop_ui.py',
                'palette_source': 'art/props/badges/author_badges.py PALETTES (gold accents, grade chips)',
                'outputs': {k: hashlib.sha256(v.read_bytes()).hexdigest() for k, v in OUTPUTS.items()}}
    (HERE / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('ok')
