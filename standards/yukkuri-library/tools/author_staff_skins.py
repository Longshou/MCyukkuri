"""Original pixel skins for the pet-shop staff (shopkeeper, examiner, keeper), standard 64x64 player-skin UV layout.

HumanRenderer uses vanilla HumanoidModel: head + hat layer + body + limbs (there is no jacket/sleeve overlay), so only
the hat layer carries volume (hair fringe, cap).  Every tile is drawn on purpose: ramp fills give 4 shade steps lit from
the top-left, ASCII grids give hair, ears, faces and clothing details.  No external image inputs.
"""
from PIL import Image


def hx(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def noise(x, y, seed):
    n = (x * 73856093 ^ y * 19349663 ^ seed * 83492791) & 0xFFFFFFFF
    n = (n * 2654435761) & 0xFFFFFFFF
    return ((n >> 11) & 255) / 255.0


def fill(im, box, ramp, seed=0, bias=0.0, jit=0.2, grad=1.0):
    """Four-step ramp (hi, base, mid, lo); light from the top-left, with a little deterministic grain."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0 + 1, y1 - y0 + 1
    r = [hx(c) for c in ramp]
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            t = grad * ((x - x0) / max(w - 1, 1) * .5 + (y - y0) / max(h - 1, 1) * .5) + bias + (noise(x, y, seed) - .5) * jit
            i = 0 if t < .14 else 1 if t < .62 else 2 if t < .92 else 3
            im.putpixel((x, y), r[i])


def art(im, x, y, rows, legend):
    assert len({len(r) for r in rows}) == 1, rows
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch != ' ':
                im.putpixel((x + i, y + j), legend[ch])


def flip(im, box, dst):
    x0, y0, x1, y1 = box
    tile = im.crop((x0, y0, x1 + 1, y1 + 1)).transpose(Image.FLIP_LEFT_RIGHT)
    im.paste(tile, dst)


def lg(d):
    return {k: hx(v) for k, v in d.items()}


def head_tiles(im, skin, hair, hair_hat, legend, base, hat, top_overlay=None, bias_hat=0.0):
    """Lay out all head and hat tiles.  base/hat: dict front/side/back (right-side tile; left is its mirror)."""
    fill(im, (8, 8, 15, 15), skin, 1)
    fill(im, (0, 8, 7, 15), skin, 2, .08)
    fill(im, (24, 8, 31, 15), skin, 3, .22)
    fill(im, (16, 0, 23, 7), skin, 4, .3)
    art(im, 8, 8, base['front'], legend)
    art(im, 0, 8, base['side'], legend)
    art(im, 24, 8, base['back'], legend)
    flip(im, (0, 8, 7, 15), (16, 8))
    fill(im, (8, 0, 15, 7), hair, 5)
    # hat layer
    art(im, 40, 8, hat['front'], legend)
    art(im, 32, 8, hat['side'], legend)
    art(im, 56, 8, hat['back'], legend)
    flip(im, (32, 8, 39, 15), (48, 8))
    fill(im, (40, 0, 47, 7), hair_hat, 6)
    if top_overlay:
        art(im, 40, 0, top_overlay, legend)


def put(im, x, y, c):
    im.putpixel((x, y), hx(c))


def band(im, x0, r0, r1, ramp, seed, y0=20):
    """Rows r0..r1 (0..11) on the four 4-wide tiles of a limb whose first tile starts at x0 (right, front, left, back)."""
    for k, (off, b) in enumerate(((0, .05), (4, 0), (8, .3), (12, .2))):
        fill(im, (x0 + off, y0 + r0, x0 + off + 3, y0 + r1), ramp, seed + k, b)


def body_fill(im, ramp, seed):
    fill(im, (16, 16, 39, 19), ramp, seed, .3)
    fill(im, (20, 20, 27, 31), ramp, seed + 1)
    fill(im, (32, 20, 39, 31), ramp, seed + 2, .15)
    fill(im, (16, 20, 19, 31), ramp, seed + 3, .1)
    fill(im, (28, 20, 31, 31), ramp, seed + 4, .25)


def side_pair(im, rows, legend):
    """Draw the body's right side tile (front edge on its right) and mirror it into the left tile."""
    art(im, 16, 20, rows, legend)
    flip(im, (16, 20, 19, 31), (28, 20))


# --------------------------------------------------------------------------------------------------------------
def shopkeeper():
    """Cream polo, coral apron with a round yukkuri logo, name tag, neat chestnut hair, khaki chinos, white sneakers."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    skin = ('#f7d5b6', '#ebbc98', '#d29e7c', '#a8765b')
    hair = ('#7a5436', '#5b3d27', '#432c1c', '#2e1d12')
    hair_hat = ('#92694a', '#74502f', '#58391f', '#432b19')
    L = lg({'M': '#5b3d27', 'D': '#432c1c', 'H': '#8a6444', 'h': '#74502f', 'd': '#58391f',
            'w': '#f7f2e8', 'i': '#34241a', 'l': '#6e4a38', 'b': '#e8a590', 'm': '#a65a4c', 'n': '#c07564', 'N': '#d29e7c',
            'E': '#d29e7c', 'e': '#a8765b'})
    base = {
        'front': ["MMMMMMMM", "MM     M", "MDD  DDM", " ll  ll ", " wi  iw ", " b NN b ", "  nmmn  ", "        "],
        'side': ["MMMMMMMM", "MMMMMMMM", "MMMMMM M", "MMMM    ", "MMMEe   ", "MMMEE   ", "MM      ", "        "],
        'back': ["MMMMMMMM", "MMDMMMDM", "MMMMMDMM", "MDMMMMMM", "MMMMDMMM", "MMMMMMMM", "MM    MM", "        "]}
    hat = {
        'front': ["HHHHHHHh", "HHhHhd  ", "d      d", "        ", "        ", "        ", "        ", "        "],
        'side': ["HHHHHHHh", "HHhHHhhh", "HhhhH   ", "Hhh     ", "hh      ", "h       ", "        ", "        "],
        'back': ["HHHHHHHh", "HhHHhHHh", "HHhHHhHH", "hHHhHHhH", "hhHhhHhh", "hhH  Hhh", "        ", "        "]}
    top = ["        ", "        ", "     d  ", "     d  ", "     d  ", "     dd ", "     d  ", "     d  "]
    head_tiles(im, skin, hair, hair_hat, L, base, hat, top)

    polo = ('#f8f2e0', '#eae1c9', '#cfc5a9', '#a8a089')
    apr = ('#ee8e66', '#dc6f48', '#b6533a', '#8e3f2e')
    chino = ('#b89c70', '#9e845a', '#806945', '#5f4e33')
    C = lg({'q': '#cfc5a9', 's': '#d29e7c', 'S': '#a8765b', 'a': '#ee8e66', 'A': '#dc6f48', 'c': '#b6533a', 'C': '#8e3f2e',
            'W': '#f7f2e8', 'g': '#a8a089', 'k': '#3a2a24', 'y': '#f7e3b0', 'Y': '#e0c58a', 'm': '#c9694a', 't': '#9e845a'})
    body_fill(im, polo, 10)
    fill(im, (21, 22, 26, 25), apr, 20)       # bib
    fill(im, (20, 27, 27, 31), apr, 21, .1)   # skirt
    art(im, 20, 20, [
        " qssssq ",
        " aqSSqa ",
        "        ",
        "     WW ",   # name tag (white plate)
        "     gg ",   # printed name line
        "        ",
        "cccccccc",   # waist tie
        "  yyyY  ",   # round yukkuri-silhouette logo, two dot eyes and a mouth
        " ykyYkY ",
        " yymmYY ",
        "  yyYY  ",
        "cc    cc"], C)
        # back: crossed straps, waist band, bow and tails over a polo hem, trousers below
    art(im, 32, 20, ["  qssq  "], C)
    for k in range(6):
        put(im, 33 + k, 20 + k, '#dc6f48'); put(im, 38 - k, 20 + k, '#dc6f48')
    art(im, 32, 26, ["cccccccc", " aAccAa ", "  AccA  ", "  c  c  ", " cc  cc "], C)
    fill(im, (32, 30, 39, 31), chino, 22, .15)
    put(im, 33, 20, '#ee8e66'); put(im, 38, 20, '#ee8e66')
    # sides: apron wraps around the front edge; tie band; trousers hem
    side_pair(im, ["    ", "    ", "   A", "   A", "   A", "   A", "cccc", "  aA", "  AA", "  AA", "ttcA", "ttcc"], C)
    fill(im, (16, 30, 17, 31), chino, 23); fill(im, (30, 30, 31, 31), chino, 24)
    # arms: short polo sleeves with a coral cuff stripe, bare forearms
    fill(im, (44, 16, 47, 19), polo, 30, .2); fill(im, (48, 16, 51, 19), skin, 31, .5)
    band(im, 40, 0, 3, polo, 32); band(im, 40, 4, 11, skin, 36)
    band(im, 40, 3, 3, apr, 40)
    # legs: chinos with a crease, white sneakers with grey soles
    band(im, 0, 0, 9, chino, 50)
    band(im, 0, 10, 10, ('#fbf8f0', '#e9e5d9', '#c2beb1', '#95918a'), 60)
    band(im, 0, 11, 11, ('#8f8b82', '#7b776d', '#615e55', '#4a4841'), 64)
    for yy in range(21, 30):
        if yy % 3: put(im, 5, yy, '#b89c70')
    fill(im, (4, 16, 7, 19), chino, 70); fill(im, (8, 16, 11, 19), ('#7b776d',) * 4, 71)
    return im


# --------------------------------------------------------------------------------------------------------------
def examiner():
    """Graphite jacket, white shirt, burgundy tie, lanyard ID card, round glasses, slicked-back hair; slacks and black shoes."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    skin = ('#f3d8c2', '#e3bda1', '#c99d82', '#9f7561')
    hair = ('#5b5c68', '#3b3b46', '#2a2a33', '#1b1b22')
    hair_hat = ('#6c6d7a', '#4b4c58', '#363640', '#26262e')
    L = lg({'M': '#3b3b46', 'D': '#272730', 'G': '#9a9ca6', 'H': '#6c6d7a', 'h': '#4b4c58', 'd': '#363640',
            'w': '#f7f4ee', 'i': '#26303f', 'f': '#a7afbd', 'm': '#a8665c', 'E': '#c99d82', 'e': '#9f7561', 'N': '#c99d82'})
    base = {
        'front': ["MMMMMMMM", "MM    MM", "GDD  DDG", " ff  ff ", "fwi  iwf", " ff  ff ", "  mmmm  ", "        "],
        'side': ["MMMMMMMM", "MMMMMM M", "MMMMM  G", "MMM     ", "MMMEefff", "MMMEE   ", "MM      ", "        "],
        'back': ["MMMMMMMM", "MMMDMMMM", "MMMMMMDM", "MDMMMMMM", "MMMMDMMM", "MMMMMMMM", "MM    MM", "        "]}
    hat = {
        'front': ["HhHHHHhH", "hhh  hhh", "G      G", "        ", "        ", "        ", "        ", "        "],
        'side': ["HHHHHHHh", "HHhHHhh ", "HhhhH   ", "Hhh     ", "hh      ", "h       ", "        ", "        "],
        'back': ["HHHHHHHh", "HhHHhHHh", "HHhHHhHH", "hHHhHHhH", "hhHhhHhh", "hhH  Hhh", "        ", "        "]}
    head_tiles(im, skin, hair, hair_hat, L, base, hat)
    for y in range(8):   # slicked-back comb lines on the crown
        for x in range(8):
            col = '#6c6d7a' if x in (2, 5) else '#26262e' if x in (1, 4, 6) else '#4b4c58'
            if y > 0 and noise(x, y, 9) > .72:
                col = '#363640'
            put(im, 40 + x, y, col)
    jk = ('#4b4e59', '#393c46', '#2b2d36', '#1e2027')
    C = lg({'j': '#4f5361', 'k': '#23252d', 'L': '#555966', 'W': '#f2f0ea', 'w': '#cfccc4', 'T': '#9c3340', 't': '#6f202b',
            'u': '#b4505c', 's': '#c99d82', 'c': '#3f93b3', 'Y': '#d8b44a', 'I': '#e9ecef', 'P': '#5a7fa8'})
    body_fill(im, jk, 80)
    art(im, 20, 20, [
        " jWssWj ",   # shirt collar
        "jjWTTWcj",   # tie knot, lanyard cord on the lapel
        " jjTTjcj",
        "  jTTjc ",
        "  jtTjc ",
        "   ttII ",
        "   kYIP ",
        "   k    ",
        " LLk LL ",
        " kkkYkk ",
        "   k    ",
        "   k    "], C)
    art(im, 32, 20, [" jWssWj ", " jjWWjj "], C)
    for yy in range(26, 32):
        put(im, 35, yy, '#23252d'); put(im, 36, yy, '#4f5361')
    side_pair(im, ["   j", "   j", "   j", "   j", "   j", "   j", "   k", "   k", "   k", "   k", "   k", "   k"], C)
    # arms: long sleeves, white cuff, hands
    band(im, 40, 0, 8, jk, 90)
    band(im, 40, 9, 9, ('#fbf9f4', '#f0eee8', '#cfccc4', '#a9a69e'), 100)
    band(im, 40, 10, 11, skin, 104)
    fill(im, (44, 16, 47, 19), jk, 108, .2); fill(im, (48, 16, 51, 19), jk, 109, .4)
    put(im, 45, 28, '#23252d')
    sl = ('#6a6d78', '#545863', '#41444e', '#2f313a')
    band(im, 0, 0, 9, sl, 110)
    band(im, 0, 10, 10, ('#4a4b55', '#2a2a31', '#1d1d23', '#121216'), 120)
    band(im, 0, 11, 11, ('#3a3a42', '#202026', '#17171b', '#0e0e11'), 124)
    for yy in range(20, 30):
        put(im, 5, yy, '#6f727d')
    put(im, 5, 30, '#4a4b55')
    fill(im, (4, 16, 7, 19), sl, 130); fill(im, (8, 16, 11, 19), ('#202026',) * 4, 131)
    return im


# --------------------------------------------------------------------------------------------------------------
def keeper():
    """Moss-green coverall, towel round the neck, tan cap, rolled sleeves, yellow rubber gloves, tall rubber boots."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    skin = ('#eebf93', '#d9a478', '#bd855d', '#935f40')
    hair = ('#54433a', '#3a2d26', '#261d19', '#17110e')
    cap = ('#d1b273', '#b2935a', '#8c6f3f', '#654e2c')
    L = lg({'M': '#3a2d26', 'D': '#261d19', 'H': '#54433a', 'w': '#f7f2e8', 'i': '#2e211a', 'b': '#dc9980', 'm': '#8f4f43',
            'n': '#b06a58', 't': '#f3ece0', 'N': '#bd855d', 'E': '#bd855d', 'e': '#935f40',
            'C': '#d1b273', 'c': '#bfa062', 'B': '#8c6f3f', 'a': '#654e2c', 'o': '#ecd9a4'})
    base = {
        'front': ["MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MDD  DDM", " wi  iw ", " b NN b ", " nmttmn ", "        "],
        'side': ["MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MMMMM  M", "MMMEe   ", "MMMEE   ", "MM      ", "        "],
        'back': ["MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MMDMMMDM", "MMMMMDMM", "MDMMMMMM", "MM    MM", "        "]}
    hat = {
        'front': ["CCCCCCCc", "CCCooCcc", "BBBBBBBB", "        ", "        ", "        ", "        ", "        "],
        'side': ["CCCCCCCc", "CCCCCCcc", "aBBBBBBB", "        ", "        ", "        ", "        ", "        "],
        'back': ["CCCCCCCc", "CCCooCcc", "aBBBBBBa", "        ", "        ", "        ", "        ", "        "]}
    head_tiles(im, skin, hair, cap, L, base, hat)
    for y in range(8):   # cap crown: four panels meeting at a button
        for x in range(8):
            cx, cy = x in (3, 4), y in (3, 4)
            if cx and cy:
                put(im, 40 + x, y, '#654e2c' if (x, y) != (3, 3) else '#e8d39d')
            elif cx or cy:
                put(im, 40 + x, y, '#8c6f3f')
    cv = ('#86a04c', '#6e8a3c', '#587230', '#425624')
    roll = ('#a3bd62', '#8aa64e', '#6e8a3c', '#587230')
    yel = ('#f6dc72', '#e8c446', '#c9a42e', '#a07f22')
    C = lg({'G': '#6e8a3c', 'g': '#86a04c', 'k': '#587230', 'K': '#425624', 'z': '#c8cfd4', 'T': '#eef0ea', 't': '#c9d4dc',
            'U': '#4d86b8', 's': '#d9a478', 'o': '#6a4a2c', 'Y': '#e3c35a', 'r': '#c8453c', 'd': '#4a3a28', 'p': '#4d6628',
            'q': '#7b9640'})
    body_fill(im, cv, 140)
    art(im, 20, 20, [
        "TTTssTTT",   # towel across the neck
        "tTTkzTTt",   # zip placket
        " TTkz   ",   # towel end hanging on the viewer's left
        " TTkz  r",
        " UUkz qq",   # chest pocket, a pen
        " TTkz pp",
        " UUkz pp",
        " ttkz   ",
        "oooYYooo",   # belt with buckle
        " kK  Kk ",
        "        ",
        "        "], C)
    art(im, 32, 20, ["TTTssTTT", "TTTTTTTT", "tttttttt", "   kk   ", "   kk   ", "   kk   ", "   kk   ", "   kk   ",
                     "oooooooo", " pp  pp ", " pp  pp ", "        "], C)
    side_pair(im, ["TTTT", "tttt", "    ", "    ", "    ", "    ", "    ", "    ", "oooo", "    ", "    ", "    "], C)
    for xx, yy in ((21, 30), (26, 31), (23, 31), (34, 31), (37, 30), (38, 31), (25, 29)):
        put(im, xx, yy, '#4a3a28')
    # arms: green sleeves, rolled cuffs, bare forearms, yellow rubber gloves
    band(im, 40, 0, 4, cv, 150); band(im, 40, 5, 6, roll, 154); band(im, 40, 7, 8, skin, 158)
    band(im, 40, 9, 9, yel, 162); band(im, 40, 10, 11, ('#fbe988', '#eecb4c', '#d0a734', '#a98524'), 166)
    for t in (40, 44, 48, 52):
        for xx in range(t, t + 4):
            put(im, xx, 26, '#587230')
    fill(im, (44, 16, 47, 19), cv, 170, .2); fill(im, (48, 16, 51, 19), cv, 171, .4)
    boot = ('#59647a', '#3a4357', '#2a3141', '#1b202b')
    band(im, 0, 0, 4, cv, 180); band(im, 0, 5, 5, roll, 184); band(im, 0, 6, 6, yel, 188)
    band(im, 0, 7, 10, boot, 192); band(im, 0, 11, 11, ('#a7a396', '#8d897c', '#6d6a5f', '#55524a'), 196)
    art(im, 4, 23, ["dd", "dd"], C)   # knee patch
    for yy in range(27, 31):
        put(im, 4, yy, '#59647a')     # rubber sheen
    fill(im, (4, 16, 7, 19), cv, 200); fill(im, (8, 16, 11, 19), ('#2a3141',) * 4, 201)
    return im


ROLES = {'shopkeeper': shopkeeper, 'examiner': examiner, 'keeper': keeper}
