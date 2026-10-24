"""Care supplies drawn as vanilla-style 16x16 item sprites: orange juice, flour paste and the three oil-grain feed bags.

The five items use the plain item/generated path, so the sprite is the single art for GUI, hand (extruded), ground, item
frames and the shop display. Style follows vanilla items rather than the care-prop low-poly kit: light from the top left,
each material has its own hue-shifted ramp (shadows cooler/redder, lights warmer/yellower), the outline is the darkest
step of the material it bounds (no black), no anti-aliasing and no partial alpha. Ramps are local to this file because
the shared kit palette (art/props/palette.json) is laid out as a fixed swatch atlas for meshes.

Run this file to rewrite the snapshot PNGs in art/props/supply-icons/; scripts/export_supply_icons.py ships them.
--check is read-only.
"""
import argparse, io, sys
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
SNAPSHOT = ROOT / 'art/props/supply-icons'
SIZE = 16

# ---- ramps (dark -> light) ------------------------------------------------------------------------------------------
GLASS = ['#557fae', '#8bb0d4', '#b4cfe9', '#dbeaf7']       # bottle glass: outline, inner edge, body, glint
CORK = ['#7f3a1d', '#a65231', '#cf7752', '#e5a07c']
RIM = '#cdb2a6'                                            # glass rim over the cork
JUICE = ['#a5400f', '#d0601a', '#f2861f', '#ffaa36', '#ffd36a']   # last step: pulp flecks / glint
PEEL = ['#c4500f']                                         # orange rind ring of the slice
PITH = ['#f2d9a6', '#fff2cf']                              # slice centre and segment walls
WOOD = ['#5a3a1e', '#8a6038', '#b48a56', '#d2ac72']                   # thin wooden chip under the paste
PASTE = ['#695c42', '#b9ad8c', '#ddd3b6', '#efe9d8', '#ffffff']           # warm off-white wet flour paste, white wet glint
BRAN = '#bfa05a'                                                       # wheat flecks in the paste
KRAFT = ['#5e3d1e', '#8a5f33', '#b0844d', '#cfa86c', '#e6c98f']       # budget bag: plain brown paper
SACK = ['#6b4a26', '#97703f', '#c09a5f', '#dcc086', '#f0dcae']         # regular bag: light kraft with a printed band
MINT = ['#3f8070', '#5ea893', '#8fcdb6']
PLUM = ['#3a1630', '#5e2347', '#86345f', '#ac5683', '#cc84a9']        # premium bag: glossy plum foil
GOLD = ['#8a5413', '#c88a1e', '#f0c040', '#fff0a0']                   # premium label
PELLET_DULL = ['#4a3e2c', '#7a6c50', '#b0a078', '#cfc29c']            # budget feed: greyish-beige filler kibble
PELLET = ['#6e3a14', '#a5602a', '#d18f45', '#efbf6e']                  # regular feed
PELLET_RICH = ['#5e2a10', '#9a4a1a', '#cf7428', '#ffbe5c', '#fff3cf']  # premium feed: glossy amber, last step a glint


def rgba(hex_colour):
    return tuple(int(hex_colour[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


class Icon:
    def __init__(self, name):
        self.name = name
        self.px = {}

    def rows(self, legend, rows, dx=0, dy=0):
        """ASCII drawing: '.' (or a character missing from the legend) keeps what is below; others map to a hex colour."""
        for y, line in enumerate(rows):
            for x, ch in enumerate(line):
                if ch in legend and 0 <= x + dx < SIZE and 0 <= y + dy < SIZE:
                    self.px[(x + dx, y + dy)] = rgba(legend[ch])
        return self

    def image(self):
        img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
        for xy, c in self.px.items():
            img.putpixel(xy, c)
        return img

    def png(self):
        out = io.BytesIO()
        self.image().save(out, 'PNG', optimize=True)
        return out.getvalue()


# ---- orange_juice: a corked glass bottle (the empty bottle is returned) full of juice, an orange slice in front -------
BOTTLE = [
    "................",
    "................",
    "......kkc.......",
    ".....hrrrg......",
    ".....gcKKg......",
    "......h.o.......",
    "......hJo.......",
    ".....hLJjo......",
    "....hpJJJjo.....",
    "...hLpJJJjjo....",
    "...hLJJJJJjo....",
    "...gJJJJJjjo....",
    "...gJJJJjjDo....",
    "....oJjjjDo.....",
    ".....ooooo......",
    "................",
]
WHEEL = [  # rind ring, light flesh, four short pale segment walls meeting at the core (stopping short of the rind)
    "..RRR..",
    ".RFFFR.",
    "RFFwFFR",
    "RFwWwFR",
    "RFFwFFR",
    ".RFFFR.",
    "..RRR..",
]
WHEEL_AT = (9, 9)


def orange_juice():
    i = Icon('orange_juice')
    i.rows({'k': CORK[2], 'K': CORK[0], 'c': CORK[1], 'r': RIM, 'h': GLASS[3], 'g': GLASS[2], 'o': GLASS[0],
            'L': JUICE[3], 'J': JUICE[2], 'j': JUICE[1], 'D': JUICE[0], 'p': JUICE[4]}, BOTTLE)
    bottle = set(i.px)
    i.rows({'R': PEEL[0], 'F': JUICE[3], 'w': PITH[1], 'W': PITH[0]}, WHEEL, *WHEEL_AT)
    wheel = {(x + WHEEL_AT[0], y + WHEEL_AT[1]) for y, line in enumerate(WHEEL) for x, ch in enumerate(line) if ch != '.'}
    for x, y in bottle - wheel:   # the bottle's own outline continues where the slice covers it, so the two never merge
        if any((x + dx, y + dy) in wheel for dx, dy in ((1, 0), (0, 1), (1, 1), (1, -1))):
            i.px[(x, y)] = rgba(GLASS[0])
    return i


# ---- flour_paste: a peaked dab of wet wheat-flour paste on a thin wooden chip; nothing is returned ------------------
CHIP = [
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    ".AAAAAAAAAAAAAA.",
    "AhbbbbbbbbbbbbBA",
    "ABBBBBBBBBBBBBBA",
    ".AAAAAAAAAAAAAA.",
    "................",
]
PASTE_ROWS = [  # soft rounded dollop with a raised tip and one viscous fold, white wet glint, wheat flecks f, two short drips
    "................",
    "................",
    "........P.......",
    ".......PwP......",
    ".....PPwlcPP....",
    "....PwwwlccdP...",
    "...PwwllcccddP..",
    "...PwlcccccddP..",
    "...PdddPPcccdP..",
    "..PlwwlccdfcddP.",
    "..PlcccccccdddP.",
    "...PddcfdddddP..",
    "....PPdPPPPdPP..",
]


def flour_paste():
    i = Icon('flour_paste')
    i.rows({'A': WOOD[0], 'B': WOOD[1], 'b': WOOD[2], 'h': WOOD[3]}, CHIP)
    i.rows({'P': PASTE[0], 'd': PASTE[1], 'c': PASTE[2], 'l': PASTE[3], 'w': PASTE[4], 'f': BRAN}, PASTE_ROWS)
    return i


# ---- oil grain feed: one family of open paper sacks heaped with kibble; tiers read from bag finish and feed colour ---
SACK_ROWS = [
    "................",
    "................",
    "................",
    "................",
    "................",
    "...o........o...",
    "...oHLLLLLLmo...",
    "...oddddddddo...",
    "..oLLmmmmmmddo..",
    "..oLmmmmmmmddo..",
    ".oLLmmmmmmmmddo.",
    ".oLmmmmmmmmmddo.",
    ".oLmmmmmmmmmddo.",
    ".oLmmmmmmmmmddo.",
    "..odmmmmmmmddo..",
    "...oooooooooo...",
]
HEAP = [  # a dome of scattered round kibble: lit top-left, shaded bottom-right, gaps alternate shade and dark
    "................",
    ".......cba......",
    ".....cbbdacb....",
    "....abdacbbda...",
    "...acbdabdacb...",
    "...abdcbacbbd...",
]
GLINT = (7, 1)   # premium only: top-left texel of the top kibble


def _sack(name, bag, feed, *prints):
    i = Icon(name)
    i.rows({'a': feed[0], 'd': feed[1], 'b': feed[2], 'c': feed[3]}, HEAP)
    if len(feed) > 4:
        i.px[GLINT] = rgba(feed[4])
    i.rows({'o': bag[0], 'd': bag[1], 'm': bag[2], 'L': bag[3], 'H': bag[4]}, SACK_ROWS)
    for legend, rows in prints:
        i.rows(legend, rows)
    return i


def oil_grain_budget():
    """Plain brown paper, grey-beige filler kibble."""
    return _sack('oil_grain_budget', KRAFT, PELLET_DULL)


def oil_grain_regular():
    """Light kraft with a printed mint band, golden kibble."""
    band = ({'o': SACK[0], 'M': MINT[0], 'm': MINT[1], 'l': MINT[2]}, [""] * 10 + [
            ".ollllllllllMMo.",
            ".oMmmmmmmmmmMMo."])
    return _sack('oil_grain_regular', SACK, PELLET, band)


def oil_grain_premium():
    """Glossy plum foil with a gold label, glossy amber kibble with a glint."""
    label = ({'o': GOLD[0], 'G': GOLD[1], 'g': GOLD[2], 'y': GOLD[3]}, [""] * 10 + [
             "...oooooooo.....",
             "...oyyggggGo....",
             "...ogggggGGo....",
             "...oooooooo....."])
    return _sack('oil_grain_premium', PLUM, PELLET_RICH, label)


ICONS = {
    'orange_juice': orange_juice,
    'flour_paste': flour_paste,
    'oil_grain_budget': oil_grain_budget,
    'oil_grain_regular': oil_grain_regular,
    'oil_grain_premium': oil_grain_premium,
}


def outputs():
    return {SNAPSHOT / f'{name}.png': factory().png() for name, factory in ICONS.items()}


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
        raise SystemExit('Supply icon snapshot differs: ' + ', '.join(changed))
    print(f'Supply icons: {len(ICONS)}; ' + ('match.' if args.check else f'{len(changed)} files written.'))


if __name__ == '__main__':
    main()
