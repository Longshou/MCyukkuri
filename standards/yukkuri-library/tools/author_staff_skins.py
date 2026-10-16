"""Original pixel skins for the pet-shop staff (shopkeeper, examiner, keeper), standard 64x64 player-skin UV layout.

HumanRenderer uses vanilla HumanoidModel: head + hat layer + body + limbs (there is no jacket/sleeve overlay), so only
the hat layer carries volume (hair fringe, cap).  Every tile is drawn on purpose: ramp fills give 4 shade steps lit from
the top-left, ASCII grids give hair, ears, faces and clothing details.  No external image inputs.
"""
from PIL import Image


def hx(s):
    if not isinstance(s, str):
        return tuple(s)
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
    if not rows:
        return
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


def head_tiles(im, skin, hair, hair_hat, legend, base, hat, top_overlay=None, bias_hat=0.0, hat_top=True, base_top=None):
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
    if base_top:
        art(im, 8, 0, base_top, legend)
    # hat layer
    art(im, 40, 8, hat['front'], legend)
    art(im, 32, 8, hat['side'], legend)
    art(im, 56, 8, hat['back'], legend)
    flip(im, (32, 8, 39, 15), (48, 8))
    if hat_top:
        fill(im, (40, 0, 47, 7), hair_hat, 6)
    if top_overlay:
        art(im, 40, 0, top_overlay, legend)


def put(im, x, y, c):
    im.putpixel((x, y), hx(c))


def band(im, x0, r0, r1, ramp, seed, y0=20, jit=.2):
    """Rows r0..r1 (0..11) on the four 4-wide tiles of a limb whose first tile starts at x0 (right, front, left, back)."""
    for k, (off, b) in enumerate(((0, .05), (4, 0), (8, .3), (12, .2))):
        fill(im, (x0 + off, y0 + r0, x0 + off + 3, y0 + r1), ramp, seed + k, b, jit)


def body_fill(im, ramp, seed, jit=.2):
    fill(im, (16, 16, 39, 19), ramp, seed, .3, jit)
    fill(im, (20, 20, 27, 31), ramp, seed + 1, 0, jit)
    fill(im, (32, 20, 39, 31), ramp, seed + 2, .15, jit)
    fill(im, (16, 20, 19, 31), ramp, seed + 3, .1, jit)
    fill(im, (28, 20, 31, 31), ramp, seed + 4, .25, jit)


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
    L['m'] = hx('#b46f5c')
    base = {
        'front': ["MMMMMMMM", "MM     M", "MDD  DDM", " ll  ll ", " wi  iw ", " b NN b ", "   mm   ", "        "],
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
        "        ",   # skirt: logo below
        "        ",
        "        ",
        "        ",
        "        "], C)
    # shop emblem (matches the shop sign): a Reimu yukkuri head, 7x5, centred as near as 8 columns allow
    art(im, 20, 27, [" RRDRR ", " KKKKK ", "KKSSSKK", "SESSSES", " SSMSS "],
        lg({'R': '#c8302c', 'D': '#7d1a1c', 'K': '#1b1b22', 'S': '#f3d3b2', 'E': '#121218', 'M': '#a8443c'}))
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
    cv = ('#6e8a3c', '#6e8a3c', '#587230', '#587230')
    roll = ('#a3bd62', '#8aa64e', '#6e8a3c', '#587230')
    yel = ('#f6dc72', '#e8c446', '#c9a42e', '#a07f22')
    C = lg({'G': '#6e8a3c', 'g': '#86a04c', 'k': '#587230', 'K': '#425624', 'z': '#c8cfd4', 'T': '#eef0ea', 't': '#c9d4dc',
            'U': '#4d86b8', 's': '#d9a478', 'o': '#6a4a2c', 'Y': '#e3c35a', 'r': '#c8453c', 'd': '#4a3a28', 'p': '#4d6628',
            'q': '#7b9640', 'K': '#425624'})
    body_fill(im, cv, 140, jit=.03)
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
    for xx, yy in ((21, 31), (26, 31), (35, 31)):
        put(im, xx, yy, '#4a3a28')
    art(im, 21, 29, ["KK"], C); art(im, 25, 29, ["KK"], C)   # hip folds
    for t in (40, 44, 48, 52):
        put(im, t + 1, 24, '#425624'); put(im, t + 2, 24, '#425624')   # elbow crease
    # arms: green sleeves, rolled cuffs, bare forearms, yellow rubber gloves
    band(im, 40, 0, 4, cv, 150, jit=.03); band(im, 40, 5, 6, roll, 154); band(im, 40, 7, 8, skin, 158)
    band(im, 40, 9, 9, yel, 162); band(im, 40, 10, 11, ('#fbe988', '#eecb4c', '#d0a734', '#a98524'), 166)
    for t in (40, 44, 48, 52):
        for xx in range(t, t + 4):
            put(im, xx, 26, '#587230')
    fill(im, (44, 16, 47, 19), cv, 170, .2); fill(im, (48, 16, 51, 19), cv, 171, .4)
    boot = ('#59647a', '#3a4357', '#2a3141', '#1b202b')
    band(im, 0, 0, 4, cv, 180, jit=.03); band(im, 0, 5, 5, roll, 184); band(im, 0, 6, 6, yel, 188)
    band(im, 0, 7, 10, boot, 192); band(im, 0, 11, 11, ('#a7a396', '#8d897c', '#6d6a5f', '#55524a'), 196)
    art(im, 4, 23, ["dd", "dd"], C)   # knee patch
    for t in (0, 4, 8, 12):
        put(im, t + 1, 21, '#425624'); put(im, t + 2, 21, '#425624')   # thigh fold
    for yy in range(27, 31):
        put(im, 4, yy, '#59647a')     # rubber sheen
    fill(im, (4, 16, 7, 19), cv, 200); fill(im, (8, 16, 11, 19), ('#2a3141',) * 4, 201)
    return im



# ==============================================================================================================
# Civilians and factory staff (same standard as the staff above).
def brother():
    """爱派大哥哥: warm teal cardigan over a cream tee, jeans, brown sneakers; messy chestnut hair, gentle smile."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    skin = ('#f6cba2', '#ebb68c', '#d29a72', '#a8704f')
    hair = ('#7a5238', '#5a3d2b', '#432c1f', '#2e1d14')
    hair_hat = ('#92654a', '#6e4a34', '#523625', '#3a251a')
    L = lg({'M': '#5a3d2b', 'D': '#432c1f', 'H': '#92654a', 'h': '#6e4a34', 'd': '#523625', 'w': '#f7f2e8', 'i': '#3b2616',
            'l': '#6e4a38', 'n': '#c07564', 'm': '#a65a4c', 'N': '#d29a72', 'E': '#d29a72', 'e': '#a8704f'})
    base = {
        'front': ["MMMMMMMM", "MM    MM", "MDD  DDM", "        ", " wi  iw ", "   NN   ", "  mmmm  ", "        "],
        'side': ["MMMMMMMM", "MMMMMMMM", "MMMMMM M", "MMMM    ", "MMMEe   ", "MMMEE   ", "MM      ", "        "],
        'back': ["MMMMMMMM", "MMDMMMDM", "MMMMMDMM", "MDMMMMMM", "MMMMDMMM", "MMMMMMMM", "MM    MM", "        "]}
    base['front'][5] = " n NN n "
    hat = {
        'front': ["HHHHHHHh", "HHhHd  H", "d  d   d", "        ", "        ", "        ", "        ", "        "],
        'side': ["HHHHHHHh", "HHhHHhhh", "HhhhH   ", "Hhh     ", "hh      ", "h       ", "        ", "        "],
        'back': ["HHHHHHHh", "HhHHhHHh", "HHhHHhHH", "hHHhHHhH", "hhHhhHhh", "hhH  Hhh", "        ", "        "]}
    top = ["   d    ", "  d  d  ", "    d   ", " d   d  ", "   d  d ", "  d     ", " d  d   ", "        "]
    head_tiles(im, skin, hair, hair_hat, L, base, hat, top)
    card = ('#6fa396', '#4f857a', '#3d6a62', '#2b4f49')
    tee = ('#faf0cf', '#f0e2b4', '#d6c692', '#b3a470')
    C = lg({'T': '#6fa396', 'K': '#2b4f49', 'p': '#f0e2b4', 'q': '#d6c692', 's': '#d29a72', 'S': '#a8704f', 'B': '#3d6a62',
            'y': '#faf0cf', 'k': '#3b2616'})
    body_fill(im, card, 300, jit=.08)
    fill(im, (22, 20, 25, 31), tee, 301, .05, .08)
    art(im, 20, 20, [
        "  qssq  ",
        "KT    TK",
        "KT    TK",
        "KT    TK",
        "KT    TK",
        "KT    TK",
        "KT    TK",
        "KT    TK",
        "BB    BB",
        "KT    TK",
        "KT    TK",
        "KKKKKKKK"], C)
    art(im, 32, 20, ["  qssq  "], C)
    for yy in range(21, 31):
        put(im, 35, yy, '#3d6a62')
    art(im, 32, 31, ["KKKKKKKK"], C)
    side_pair(im, ["   B", "    ", "    ", "    ", "    ", "    ", "    ", "    ", "    ", "    ", "    ", "KKKK"], C)
    # sleeves with ribbed cuffs, hands
    band(im, 40, 0, 8, card, 310, jit=.08)
    band(im, 40, 9, 9, ('#3d6a62',) * 2 + ('#2b4f49',) * 2, 320)
    band(im, 40, 10, 11, skin, 324)
    fill(im, (44, 16, 47, 19), card, 330, .2); fill(im, (48, 16, 51, 19), card, 331, .4)
    for t in (40, 44, 48, 52):
        put(im, t + 1, 24, '#3d6a62'); put(im, t + 2, 24, '#3d6a62')
    jean = ('#6a88b0', '#587698', '#445d7c', '#33465f')
    band(im, 0, 0, 9, jean, 340, jit=.1)
    band(im, 0, 10, 10, ('#a87a52', '#8a5c3a', '#6e4829', '#53361d'), 350)
    band(im, 0, 11, 11, ('#f3ead2', '#e2d8bc', '#b9af92', '#8d8570'), 354)
    for yy in range(21, 30, 2):
        put(im, 4, yy, '#7f9bc0'); put(im, 7, yy, '#33465f')
    put(im, 5, 21, '#33465f'); put(im, 6, 21, '#33465f')
    fill(im, (4, 16, 7, 19), jean, 360); fill(im, (8, 16, 11, 19), ('#8a5c3a',) * 4, 361)
    return im


def sister():
    """爱派大姐姐: rose sweater with a cream collar and bow, plum skirt, dark tights, brown boots; long auburn hair (part at 0,36)."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    skin = ('#f8d8c0', '#eebf9e', '#d6a283', '#ac7a5d')
    hair = ('#74402f', '#5b3226', '#432319', '#2f160f')
    hair_hat = ('#8d5440', '#6f3d2e', '#543024', '#3d2018')
    L = lg({'M': '#5b3226', 'D': '#432319', 'H': '#8d5440', 'h': '#6f3d2e', 'd': '#543024', 'w': '#f7f2e8', 'i': '#3e2518',
            'l': '#43281f', 'b': '#eca594', 'n': '#c97a6a', 'm': '#b0604f', 'N': '#d6a283', 'E': '#d6a283', 'e': '#ac7a5d',
            'Y': '#eac35c'})
    base = {
        'front': ["MMMMMMMM", "MMMMMMMM", "MDD  DDM", "Mll  llM", "Mwi  iwM", "Mb NN bM", "M nmmn M", "        "],
        'side': ["MMMMMMMM", "MMDMMMMM", "MMMMMMMM", "MMMMDMMM", "MMMMMMMM", "MMMDMMMM", "MMMMMMMM", "MMMM    "],
        'back': ["MMMMMMMM", "MDMMMMDM", "MMMDMMMM", "MMMMMDMM", "MDMMMMMM", "MMMMDMMM", "MMMMMMMM", "MMMMMMMM"]}
    hat = {
        'front': ["HHHHHHHh", "HHhHHhYH", "H      H", "        ", "        ", "        ", "        ", "        "],
        'side': ["HHHHHHHH", "HHhHHhHH", "HhHHhHH ", "HHhHHhH ", "hHHhHHh ", "HhHHhHh ", "hHhhHh  ", "        "],
        'back': ["HHHHHHHh", "HhHHhHHh", "HHhHHhHH", "hHHhHHhH", "hhHhhHhh", "HhHHhHhH", "hHhHHhHh", "hhHhhHhh"]}
    top = ["        ", "        ", "  d  d  ", "  d  d  ", "   d    ", "   d    ", "   d    ", "        "]
    head_tiles(im, skin, hair, hair_hat, L, base, hat, top)
    # long hair part (head-bound): UV (0,36); back face (10,38) is the visible one
    fill(im, (0, 38, 15, 48), hair, 400, .0, .1)
    fill(im, (10, 38, 15, 48), hair, 401, .1, .1)
    for xx in (11, 13, 15):
        for yy in range(38, 47):
            put(im, xx, yy, '#432319' if yy % 4 else '#74402f')
    for xx in range(10, 16):
        put(im, xx, 48, '#432319'); put(im, xx, 47, '#5b3226' if xx % 2 else '#432319')
    fill(im, (2, 36, 7, 37), hair, 402); fill(im, (8, 36, 13, 37), hair, 403, .4)
    swe = ('#e08a9a', '#cc6f82', '#a9566a', '#843f52')
    sk = ('#9a5a78', '#82476a', '#68375a', '#4e2944')
    C = lg({'R': '#cc6f82', 'r': '#a9566a', 'c': '#fbf2e0', 'C': '#dcd0b8', 's': '#d6a283', 'S': '#ac7a5d', 'B': '#c8454f',
            'b': '#e8737f', 'P': '#82476a', 'p': '#4e2944', 'h': '#b28ab0'})
    body_fill(im, swe, 410, jit=.08)
    fill(im, (20, 28, 27, 31), sk, 411, .1, .06); fill(im, (32, 28, 39, 31), sk, 412, .2, .06)
    fill(im, (16, 28, 19, 31), sk, 413, .1, .06); fill(im, (28, 28, 31, 31), sk, 414, .25, .06)
    art(im, 20, 20, [" ccssscc"[:8], "RccbBcRR"[:8], "RRRBbRRR", "        ", "        ", "        ", "        ", "        ",
                     "pppppppp", "        ", "        ", "CCCCCCCC"], C)
    art(im, 20, 20, [" cCssCc "], C)
    art(im, 20, 21, ["RcCBBCcR"], C)
    for xx in (21, 23, 24, 26):    # skirt pleats
        for yy in range(29, 31):
            put(im, xx, yy, '#68375a')
    art(im, 32, 20, [" cCssCc ", "RRRRRRRR"], C)
    art(im, 32, 28, ["pppppppp"], C); art(im, 32, 31, ["CCCCCCCC"], C)
    art(im, 16, 28, ["pppp"], C); art(im, 16, 31, ["CCCC"], C); art(im, 28, 28, ["pppp"], C); art(im, 28, 31, ["CCCC"], C)
    band(im, 40, 0, 8, swe, 420, jit=.08)
    band(im, 40, 9, 9, ('#fbf2e0', '#fbf2e0', '#dcd0b8', '#dcd0b8'), 430)
    band(im, 40, 10, 11, skin, 434)
    fill(im, (44, 16, 47, 19), swe, 440, .2); fill(im, (48, 16, 51, 19), swe, 441, .4)
    for t in (40, 44, 48, 52):
        put(im, t + 1, 23, '#a9566a'); put(im, t + 2, 23, '#a9566a')
    # legs: skirt hem, dark rose tights, brown ankle boots
    band(im, 0, 0, 3, sk, 450, jit=.06)
    band(im, 0, 3, 3, ('#b886a8', '#a06f90', '#82476a', '#68375a'), 455)
    tig = ('#7c5062', '#664050', '#503241', '#3d2531')
    band(im, 0, 4, 9, tig, 460, jit=.06)
    band(im, 0, 10, 10, ('#9a6a46', '#7d5233', '#623e26', '#49301c'), 470)
    band(im, 0, 11, 11, ('#5a4234', '#463226', '#33241b', '#241911'), 474)
    fill(im, (4, 16, 7, 19), sk, 480); fill(im, (8, 16, 11, 19), ('#463226',) * 4, 481)
    return im


def stub(seed, rows, _a, _b, strip=None):
    """Sparse irregular scalp stubble on shaved head tiles; optional red strip columns; ear rows are overwritten below."""
    out = []
    for y in range(8):
        row = ''
        for x in range(8):
            if strip and y < 5 and x in strip:
                row += 'R'
            elif y in rows or y == 6 and not strip:
                row += 'x' if noise(x, y, seed) > .88 else ' '
            else:
                row += ' '
        out.append(row)
    if not strip:
        out[4] = out[4][:3] + 'Ee' + out[4][5:]
        out[5] = out[5][:3] + 'Eg' + out[5][5:]
    return out


def oniisan():
    """鬼威惨: shaved sides and a red mohawk (separate part at 18,36), black tank top, studded belt, ripped jeans, boots."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    skin = ('#e9b992', '#d6a077', '#b97f5c', '#8e5f44')
    L = lg({'x': '#a8714f', 'R': '#a8402f', 'D': '#2f1d17', 'w': '#f4efe6', 'i': '#22170f', 'm': '#7a4538', 'N': '#b97f5c',
            'E': '#b97f5c', 'e': '#8e5f44', 'g': '#c9ced4', 'G': '#4a3027', 'z': (46, 30, 24, 100), 'u': '#8e5f44'})
    base = {
        'front': [" x RR x ", "  xRRx  ", " DD  DD ", "  D  D  ", " wi  iw ", " x NN x ", "  mmmm  ", " x GG x "],
        'side': stub(7, [0, 1, 2, 3], 0, 0),
        'back': stub(11, [0, 1, 2, 3, 4, 5], 0, 0, strip=(3, 4))}
    hat = {
        'front': ["        ", "        ", "        ", "        ", "        ", "z      z", "zz    zz", "zzzzzzzz"],
        'side': ["        ", "        ", "        ", "        ", "        ", "      zz", "     zzz", "    zzzz"],
        'back': ["        "] * 8}
    top = ["   RR   ", "   RR   ", "   RR   ", "   RR   ", "   RR   ", "   RR   ", "   RR   ", "   RR   "]
    head_tiles(im, skin, skin, skin, L, base, hat, base_top=top, hat_top=False)
    # mohawk part: box (2x3x7) at (18,36) and crest (2x1x3) at (18,47); tips orange, bases dark red
    tip, mid, low, dk = '#e8873f', '#cf5232', '#a8402f', '#7c2e24'
    for y in range(36, 53):
        for x in range(18, 38):
            put(im, x, y, mid)
    for x in range(18, 36):           # side faces: tip -> base per row (43..45), streaks
        for y, col in ((43, tip), (44, mid), (45, low)):
            put(im, x, y, col if (x + y) % 3 else dk if y == 45 else col)
    for x in (25, 26):
        for y in range(36, 43):
            put(im, x, y, tip if x == 25 else mid)
    for y in range(36, 43):
        for x in (27, 28):
            put(im, x, y, low)
    for x in range(21, 23):
        for y in range(47, 50):
            put(im, x, y, tip)
    for x in range(18, 28):
        put(im, x, 50, mid if x % 2 else low)
    tank = ('#4a4d57', '#33353d', '#25272d', '#18191d')
    C = lg({'t': '#33353d', 'T': '#4a4d57', 's': '#d6a077', 'S': '#b97f5c', 'r': '#b8402f', 'b': '#1c1b1e', 'g': '#c9ced4',
            'j': '#3e4658', 'J': '#2d3442'})
    body_fill(im, tank, 500, jit=.08)
    art(im, 20, 20, [
        "tTssssTt", "ttTssTtt", "tttssttt", "        ", "        ", "        ", "        ", "        ",
        "bgbggbgb", "        ", "rrrrrrrr", "        "], C)
    fill(im, (20, 28, 27, 31), ('#4a5468', '#3e4658', '#2d3442', '#222834'), 501, .1, .1)
    art(im, 20, 28, ["bgbggbgb"], C)
    for yy in range(20, 28):
        put(im, 23, yy, '#25272d') if yy > 22 and yy % 3 == 0 else None
    art(im, 32, 20, ["tTssssTt", "tttttttt", "        ", "        ", "        ", "        ", "        ", "        ",
                     "bbbbbbbb", "        ", "rrrrrrrr", "        "], C)
    fill(im, (32, 28, 39, 31), ('#4a5468', '#3e4658', '#2d3442', '#222834'), 502, .15, .1)
    art(im, 32, 28, ["bbbbbbbb"], C)
    side_pair(im, ["   t", "    ", "    ", "    ", "    ", "    ", "    ", "    ", "bbbb", "    ", "rrrr", "    "], C)
    fill(im, (16, 29, 19, 31), ('#4a5468', '#3e4658', '#2d3442', '#222834'), 503, .1, .1)
    fill(im, (28, 29, 31, 31), ('#4a5468', '#3e4658', '#2d3442', '#222834'), 504, .25, .1)
    # bare arms: red tape on the upper arm, black wrist wraps
    band(im, 40, 0, 11, skin, 510, jit=.12)
    fill(im, (44, 16, 47, 19), skin, 514, .1); fill(im, (48, 16, 51, 19), skin, 515, .4)
    band(im, 40, 2, 3, ('#d4573f', '#b8402f', '#8f3024', '#6b2219'), 520)
    band(im, 40, 8, 9, ('#3a3c44', '#25272d', '#1c1d22', '#121316'), 524)
    for t in (40, 44, 48, 52):
        put(im, t + 1, 6, '#8e5f44'); put(im, t + 2, 6, '#8e5f44')
    jeans = ('#4a5468', '#3e4658', '#2d3442', '#222834')
    band(im, 0, 0, 8, jeans, 530, jit=.1)
    band(im, 0, 9, 10, ('#4a4a52', '#2b2b31', '#1d1d22', '#121215'), 540)
    band(im, 0, 11, 11, ('#6a6a72', '#52525a', '#3a3a41', '#2a2a30'), 544)
    art(im, 4, 23, ["ss", "ss"], C)   # ripped knee showing skin
    put(im, 4, 24, '#b97f5c'); put(im, 7, 22, '#3e4658')
    put(im, 4, 30, '#c9ced4'); put(im, 7, 30, '#c9ced4')   # boot studs
    fill(im, (4, 16, 7, 19), jeans, 550); fill(im, (8, 16, 11, 19), ('#2b2b31',) * 4, 551)
    return im


def factory_worker():
    """Factory uniform: white cap and mask, slate work jacket under a white apron, blue nitrile gloves, white clogs."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    skin = ('#f3d1b2', '#e6b992', '#cc9d78', '#a37556')
    hair = ('#5a4a3e', '#463b31', '#32291f', '#211a14')
    cap = ('#ffffff', '#f0eee4', '#d3d0c3', '#aaa89b')
    L = lg({'M': '#463b31', 'D': '#32291f', 'w': '#f7f2e8', 'i': '#2f2a26', 'l': '#6e5a48', 'N': '#cc9d78', 'E': '#cc9d78', 'e': '#a37556',
            'W': '#f4f1e8', 'c': '#e2dfd2', 'b': '#c5ccd0', 'g': '#9aa5ab', 'Q': '#cfe3ea', 'q': '#9cc3d0', 'p': '#79a8b8', 't': '#3f8a86'})
    base = {
        'front': ["MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MDD  DDM", " wi  iw ", "        ", "        ", "        "],
        'side': ["MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MMMMM  M", "MMMEe   ", "MMMEE   ", "MM      ", "        "],
        'back': ["MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MM    MM", "        "]}
    hat = {
        'front': ["WWWWWWWc", "WWWttWcc", "bbbbbbbb", "        ", "        ", " QQQQQQ ", " qQQQQq ", "  qppq  "],
        'side': ["WWWWWWWc", "WWWWWWcc", "bbbbbbbb", "WWWWWW  ", "cccccc  ", "   qqQQQ", "     QQQ", "     qqq"],
        'back': ["WWWWWWWc", "WWWWWWcc", "bbbbbbbb", "WWWWWWWc", "ccccccc ", "gggggggg", "        ", "        "]}
    head_tiles(im, skin, hair, cap, L, base, hat)
    for y in range(8):    # cap crown with a centre seam
        for x in range(8):
            put(im, 40 + x, y, '#d3d0c3' if x in (3, 4) else '#f0eee4' if x < 3 else '#e2dfd2')
    jk = ('#a3b4b9', '#8a9ea4', '#6e838a', '#556870')
    wh = ('#ffffff', '#f0eee4', '#d3d0c3', '#aaa89b')
    C = lg({'K': '#556870', 'k': '#6e838a', 's': '#cc9d78', 'S': '#a37556', 'W': '#f0eee4', 'w': '#ffffff', 'c': '#d3d0c3',
            'g': '#aaa89b', 't': '#3f8a86', 'T': '#2d6a67', 'p': '#8a9ea4', 'y': '#e3c35a'})
    body_fill(im, jk, 600, jit=.08)
    fill(im, (21, 22, 26, 31), wh, 601, .05, .06)
    art(im, 20, 20, [
        "KKssssKK", "KWKssKWK", "KWK  KWK", "        ", "        ", "        ", "        ", "        ",
        "        ", "        ", " ttttttt"[:8], "gggggggg"], C)
    art(im, 25, 24, ["pp", "pp"], C); art(im, 26, 23, ["y"], C)    # chest pocket and pen
    art(im, 21, 22, ["cccccc"], C)
    art(im, 32, 20, ["KKssssKK", "KWKKKKWK", "KWKKKKWK", "KWWWWWWK", "KWKKKKWK"], C)
    fill(im, (32, 25, 39, 31), jk, 602, .2, .06)
    art(im, 32, 30, ["KKKKKKKK"], C)
    side_pair(im, ["   K", "   W", "   W", "   c", "   c", "   c", "   c", "   c", "   c", "   c", "   c", "   g"], C)
    band(im, 40, 0, 8, jk, 610, jit=.08)
    band(im, 40, 8, 8, ('#556870', '#556870', '#43545b', '#43545b'), 620)
    gl = ('#a8d4ec', '#86b9d8', '#6a9bbb', '#4f7e9c')
    band(im, 40, 9, 11, gl, 624)
    fill(im, (44, 16, 47, 19), jk, 630, .2); fill(im, (48, 16, 51, 19), jk, 631, .4)
    for t in (40, 44, 48, 52):
        put(im, t + 1, 24, '#6e838a'); put(im, t + 2, 24, '#6e838a')
    tr = ('#5c6975', '#48525d', '#363e48', '#262c34')
    band(im, 0, 0, 9, tr, 640, jit=.06)
    band(im, 0, 10, 10, ('#ffffff', '#f0eee4', '#d3d0c3', '#aaa89b'), 650)
    band(im, 0, 11, 11, ('#bfbcae', '#a7a495', '#85836f', '#66644f'), 654)
    for yy in range(21, 30):
        put(im, 5, yy, '#5c6975') if yy % 3 else None
    fill(im, (4, 16, 7, 19), tr, 660); fill(im, (8, 16, 11, 19), ('#a7a495',) * 4, 661)
    return im


ROLES = {'oniisan': oniisan, 'brother': brother, 'sister': sister, 'shopkeeper': shopkeeper,
         'factory_worker': factory_worker, 'examiner': examiner, 'keeper': keeper}
