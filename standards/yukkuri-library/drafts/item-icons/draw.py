"""Inventory icons for the items whose world look is a 3D prop: toys, devices, medicines and care tools.

The prop mesh stays what is placed, held, carried in the mouth, dropped and framed; only the inventory slot shows this
16x16 sprite (client/ItemIcons swaps the GUI view to models/item/icon/<id>.json). Same look as the flour paste, oil grains
and oni tools (care-props/supplies.py, operation-tools/icons.py): light from the top left, one hue-shifted ramp per material
(dark -> light, index 0 is the outline step), the outline is the darkest step of the material it bounds (no black), no
anti-aliasing, no partial alpha. The Icon class, ramps and primitives are reused from operation-tools/icons.py; the spicy grain
reuses the oil-grain sack of supplies.py so the feeds read as one family. Medicines share one bottle (like vanilla potions)
and read apart by glass, cap and label; the two sprays share one trigger bottle.

Run this file to rewrite the snapshots in art/props/item-icons/; scripts/export_item_icons.py ships them. --check is read-only.
"""
import argparse, importlib.util, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SNAPSHOT = ROOT / 'art/props/item-icons'


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


T = _load('operation_tool_icons', HERE.parent / 'operation-tools/icons.py')
S = _load('care_supply_icons', HERE.parent / 'care-props/supplies.py')
Icon, legend = T.Icon, T.legend
IRON, WOOD, PINK, RED, BRASS, CLOTH, STRING, GLASS, GREEN, DRUM, PLANK = (T.IRON, T.WOOD, T.PINK, T.RED, T.BRASS, T.CLOTH,
                                                                         T.STRING, T.GLASS, T.GREEN, T.DRUM, T.PLANK)
KRAFT, GOLD, MINT = S.KRAFT, S.GOLD, S.MINT

# ---- ramps (dark -> light); index 0 is the outline step ---------------------------------------------------------------
PAPER = ['#7a7468', '#b3ad9e', '#d8d3c4', '#eeeadf', '#ffffff']        # labels, flour bag, white plastic
YOGURT = ['#8a8270', '#c9c2ae', '#e6e0cf', '#f6f2e8', '#ffffff']       # drinking yogurt seen through the glass
SKY = ['#1f4166', '#2f6496', '#4a8ac2', '#7ab3e0', '#b4d8f5']          # blue caps and labels
AMBER = ['#5e2c0c', '#93491a', '#c4702a', '#e59c4c', '#ffd08a']        # stimulant medicine glass
LILAC = ['#43284f', '#6c4985', '#9271ab', '#b99ccf', '#e0cdee']        # sitataran glass
GRAPE = ['#24132f', '#3d2252', '#5a3678', '#7d55a0']                   # sitataran cap and bubble
TEAL = ['#1b4a4f', '#2b6f72', '#459a98', '#74c2bb', '#b5e6dd']         # marble-soda spray glass, slide
LEAF = ['#24461f', '#3b6a2c', '#5a913c', '#84b85a', '#b7dc86']         # leaf prints, stems, grass
CHILI = ['#5a0f0c', '#8e1d15', '#c0321f', '#e45a37', '#ff9a6a']        # spicy bag and chili
EMBER = ['#5c1708', '#93280d', '#c94a17', '#f0802e', '#ffc06a']        # spicy kibble
SAND = ['#7a6440', '#a68c5e', '#c4ab7d', '#dac69a', '#f1e4bf']         # toilet sand, straw-light
STRAW = ['#55431a', '#86702e', '#b39a47', '#d6bd68', '#efda91']        # dry grass, wicker
TERRA = ['#5a2715', '#8c4225', '#bb663a', '#dc9160', '#f2bd8f']        # clay pipe
STONE = ['#2a3236', '#454f54', '#637078', '#8a979e', '#bccad0']        # pretty stone
ROSE = ['#6a2140', '#a03a60', '#c95c85', '#e98cad', '#ffc3d6']         # yarn
SKIN = ['#7a6249', '#b29977', '#d9c7a5', '#efe2c6', '#fff8ea']         # yukkuri bun (dolls)
HAIR = ['#2a1a12', '#4a2e1f', '#6c452d', '#8f6340']                    # Reimu doll hair
BLOND = ['#7a5a12', '#b38a20', '#e0b93c', '#f6dc74', '#fff3b0']        # Marisa doll hair
INK = ['#2a2036', '#3a3048', '#4b4260', '#625a7a', '#8a84a0']          # Marisa hat, trampoline bed, eyes
CREAM = ['#8c7a5c', '#b6a481', '#d7c8a6', '#ece2c7', '#f9f4e4']        # bowl lip (placed.py CREAM)
MINTC = ['#2d6656', '#438a76', '#5fab94', '#86c9b2', '#b6e6d4']        # bowl and toilet glaze (placed.py MINT)
ANKO = ['#2e1a10', '#4a2c1b', '#6a4129', '#8a5a3a', '#ad7d57']        # bean-paste filling (placed.py ANKO)
WHITE = ['#8e95a3', '#b8bfcc', '#dde2ea', '#eff2f6', '#ffffff']        # glazed ceramic (placed.py WHITE)
MARBLE = ['#2f5a86', '#5f8fbf', '#9cc3e4', '#cfe5f6', '#ffffff']       # marble glass, deeper than the bottle glass
BERRY = ['#5e1424', '#932338', '#c23d52', '#e46d7b', '#fba3aa']        # nest cushion (strawberry)
NAVYW = ['#273f5c', '#3d5f86', '#5a83ad', '#86abcf', '#b8d2ea']        # fine (blue) carrier wicker
SAGEW = ['#2b4a33', '#41704b', '#5f9467', '#8bb98d', '#bfe0bd']        # large (green) carrier wicker
DARKWOOD = ['#2b1d10', '#4a3219', '#6a4826', '#8c6336', '#ad8250']     # crate shelter
WATER = ['#1d4a7a', '#2f6fa8', '#4b93cc', '#7cb9e6', '#bfe0f7']        # pool water
TILE = ['#56747a', '#8aaab0', '#b5d0d3', '#d9ecec', '#ffffff']         # pool basin
WASH = ['#2f5677', '#4f7fa6', '#78a8cc', '#a6cde8', '#d6ecf9']         # wash cloth
WHISTLE = ['#5a2e0f', '#8f501d', '#bf7a35', '#e0a55b', '#f5cd8b']      # wooden whistle
ROPE = ['#3d2410', '#62401f', '#8a5e30', '#b08346', '#d4ab6e']         # companion cord
TWIG = ['#2e1d0f', '#4d331b', '#6e4c2a', '#90693d', '#b38a55']         # predator nest twigs
FLOWER = ['#8a7a3a', '#d8c25a', '#ffffff']                             # forage pile daisy (centre, petals)


# ---- medicines and consumables ----------------------------------------------------------------------------------------
TALL = [   # the medicine bottle: a cap over a short neck, square-shouldered body lit from the left
    "................",
    "......kkkk......",
    ".....kHccdk.....",
    ".....kccddk.....",
    "......oWso......",
    ".....oWwwso.....",
    "....oWwwwwso....",
    "....oWwwwwso....",
    "....oWwwwwso....",
    "....oWwwwwso....",
    "....oWwwwwso....",
    "....oWwwwwso....",
    "....oWwwwSso....",
    "....oWwwSSso....",
    ".....oooooo.....",
    "................",
]


def _tall(name, glass, cap, label_legend=None, label_rows=None):
    i = Icon(name)
    i.rows(legend(k=(cap, 0), H=(cap, 3), c=(cap, 2), d=(cap, 1),
                  o=(glass, 0), W=(glass, 4), w=(glass, 3), s=(glass, 2), S=(glass, 1)), TALL)
    if label_rows:
        i.rows(label_legend, label_rows)
    return i


def _paper(**extra):
    return legend(P=(PAPER, 4), p=(PAPER, 3), q=(PAPER, 2), Q=(PAPER, 1), **extra)


def stimulant():
    """Amber medicine glass, red cap, paper label with a red lightning stroke."""
    return _tall('stimulant', AMBER, RED, _paper(r=(RED, 2), R=(RED, 1)), [""] * 7 + [
        ".....PppprQ.....",
        ".....PpprRq.....",
        ".....PprRpq.....",
        ".....PrrrRq.....",
        ".....PpprRq.....",
        ".....PprRpQ.....",
    ])


def sitataran():
    """Lilac glass, dark grape cap, a paper label with a pink heart (the baby-talk medicine; the stimulant has a lightning stroke)."""
    return _tall('sitataran', LILAC, GRAPE, _paper(v=(PINK, 2), V=(PINK, 1)), [""] * 7 + [
        ".....PppppQ.....",
        ".....PvpvpQ.....",
        ".....vvvVVq.....",
        ".....PvvVpq.....",
        ".....PpVppq.....",
        ".....PppppQ.....",
    ])


def mold_remover():
    """White plastic bottle, blue cap, a green leaf on the front (it clears mould)."""
    return _tall('mold_remover', PAPER, SKY, legend(l=(LEAF, 3), L=(LEAF, 2), m=(LEAF, 1), n=(LEAF, 0)), [""] * 7 + [
        "........l.......",
        ".......lLm......",
        "......lLLm......",
        "......LLmn......",
        ".....m.n........",
    ])


GLASS_BOTTLE = [   # the orange-juice bottle (supplies.py BOTTLE), one pixel right: corked glass, contents seen through it
    "................",
    "................",
    ".......kkc......",
    "......hrrrg.....",
    "......gcKKg.....",
    ".......h.o......",
    ".......hJo......",
    "......hLJjo.....",
    ".....hpJJJjo....",
    "....hLpJJJjjo...",
    "....hLJJJJJjo...",
    "....gJJJJJjjo...",
    "....gJJJJjjDo...",
    ".....oJjjjDo....",
    "......ooooo.....",
    "................",
]


def yogurt_drink():
    """Drinking yogurt in a corked glass bottle (the bottle is handed back), a blue paper band round the middle."""
    i = Icon('yogurt_drink')
    i.rows(legend(k=(S.CORK, 2), K=(S.CORK, 0), c=(S.CORK, 1), r=(PAPER, 2), h=(GLASS, 3), g=(GLASS, 2), o=(GLASS, 0),
                  L=(YOGURT, 4), J=(YOGURT, 3), j=(YOGURT, 2), D=(YOGURT, 1), p=(YOGURT, 4)), GLASS_BOTTLE)
    i.rows(legend(b=(SKY, 2), B=(SKY, 1), h=(SKY, 3)), [""] * 11 + [
        "....hbbbbbbB....",
    ])
    return i


def sleep_spray():
    """Marble-soda (ramune) spray: teal glass pinched under the shoulder round its glass marble, a white trigger head, a pale z."""
    i = _spray('sleep_spray', TEAL, PAPER, legend(m=(PAPER, 4), M=(PAPER, 3), g=(GLASS, 3), G=(GLASS, 1), w=(PAPER, 4)), [""] * 8 + [
        "......gw........",
        "......Gg........",
        "................",
        "......mmm.......",
        ".......M........",
        "......mmm.......",
    ])
    i.erase([(4, 10), (12, 10)])
    i.rows(legend(o=(TEAL, 0)), [""] * 10 + [".....o.....o...."])
    return i


def household_guard():
    """Repellent spray: sky-blue bottle, grey trigger head, a red-bordered warning label with a cross."""
    return _spray('household_guard', SKY, IRON, legend(r=(RED, 2), R=(RED, 1), P=(PAPER, 4), p=(PAPER, 3)), [""] * 8 + [
        ".....rrrrrR.....",
        ".....rPpPpR.....",
        ".....rpPppR.....",
        ".....rPpPpR.....",
        ".....rrrrRR.....",
    ])


SPRAY = [   # trigger sprayer: head with the nozzle to the left, trigger under it, screw collar, square bottle
    "................",
    ".....aaaaaa.....",
    "..aaaBbbbbca....",
    "..abbbbbbbca....",
    "..aaaaabbcca....",
    "...aTa.aKKa.....",
    "...aTa.kccd.....",
    "....a.okccdo....",
    "....oWwwwwwso...",
    "....oWwwwwwso...",
    "....oWwwwwwso...",
    "....oWwwwwwso...",
    "....oWwwwwSso...",
    "....oWwwwSSso...",
    ".....ooooooo....",
    "................",
]


def _spray(name, glass, head, label_legend, label_rows):
    i = Icon(name)
    i.rows(legend(a=(head, 0), B=(head, 4), b=(head, 3), c=(head, 2), T=(head, 2), K=(head, 1),
                  k=(head, 0), d=(head, 1),
                  o=(glass, 0), W=(glass, 4), w=(glass, 3), s=(glass, 2), S=(glass, 1)), SPRAY)
    i.rows(label_legend, label_rows)
    return i


def spicy_grain():
    """The oil-grain sack (supplies.py) in chili-red paper with ember-red kibble and a white chili label."""
    label = ({'o': PAPER[0], 'w': PAPER[4], 'W': PAPER[3], 'r': CHILI[2], 'R': CHILI[1], 'g': LEAF[2]},
             [""] * 9 + [
             "....oooooo......",
             "....owwrgo......",
             "....owrRWo......",
             "....orRwWo......",
             "....oooooo......"])
    return S._sack('spicy_grain', CHILI, EMBER, label)


def reproductive_filling():
    """A piping bag of bean-paste filling, point down: a twisted, clipped top, the paste showing in the long tip, a pointed metal nozzle."""
    i = Icon('reproductive_filling')
    i.rows(legend(o=(PAPER, 0), W=(PAPER, 4), w=(PAPER, 3), s=(PAPER, 2), S=(PAPER, 1),
                  b=(SKY, 2), B=(SKY, 1), c=(SKY, 3), n=(SKY, 0), f=(ANKO, 3), F=(ANKO, 2), A=(ANKO, 1), a=(ANKO, 4),
                  i=(IRON, 0), I=(IRON, 4), j=(IRON, 2)), [
        "......oWo.......",
        ".......osso.....",
        "......ncbBn.....",
        "....ooWwwsoo....",
        "...oWwwwwwsso...",
        "...oWwwwwwsso...",
        "....oWwwwwso....",
        "....oWwwwSso....",
        ".....oWafFo.....",
        ".....oaffAo.....",
        "......oaFo......",
        "......oFAo......",
        ".......oo.......",
        ".......iI.......",
        ".......Ij.......",
        "........i.......",
    ])
    return i


def wheat_flour():
    """A folded-top paper flour bag with an orange band and a green wheat-ear print."""
    i = Icon('wheat_flour')
    i.rows(legend(o=(PAPER, 0), W=(PAPER, 4), w=(PAPER, 3), s=(PAPER, 2), S=(PAPER, 1),
                  a=(AMBER, 3), A=(AMBER, 2), e=(AMBER, 1), g=(LEAF, 3), G=(LEAF, 2), y=(STRAW, 3), Y=(STRAW, 2)), [
        "................",
        "....oooooooo....",
        "....oWwwwwso....",
        "...oWwwwwwwso...",
        "...ooooooooSo...",
        "...oWwwwwwwso...",
        "...oaaaaaaAeo...",
        "...oWwwywwwso...",
        "...oWwyYywwso...",
        "...oWwwYywwso...",
        "...oWwyYwwwso...",
        "...oWwwGwwwso...",
        "...oWwwGwwSso...",
        "...oWwwwwwSso...",
        "...ooooooooooo..",
        "................",
    ])
    return i


# ---- toys -------------------------------------------------------------------------------------------------------------
def yarn_ball():
    """A rose yarn ball: wound strands crossing the lit sphere, a loose end trailing to the lower right."""
    i = Icon('yarn_ball')
    i.rows(legend(o=(ROSE, 0), d=(ROSE, 1), m=(ROSE, 2), h=(ROSE, 3), H=(ROSE, 4), T=(ROSE, 0)), [
        "................",
        ".....oooooo.....",
        "...ooHHhhdhmo...",
        "..oHHhdddhdmmo..",
        "..oHddhhhddmmo..",
        ".oHdhhhhhhdddmo.",
        ".ohhhdddddhdmmo.",
        ".ohhdhhhhhddmso.",
        ".ohdhhmmmmmdsdo.",
        ".odhmmddddmdsdo.",
        ".ohmdmmmmmddsso.",
        "..ommmmmmmmdso..",
        "..ommsssssssso..",
        "...oosssssoo....",
        ".....oooooomo...",
        "............om..",
        ".............oo.",
    ])
    return i


def glass_marble():
    """A clear glass marble (11 px) in a deeper glass ramp so it holds at 1x: an orange twist inside, a white glint at the top left."""
    i = Icon('glass_marble')
    i.disc((7.5, 8.5), 4.9, MARBLE, glint=True)
    i.finish()
    i.rows(legend(r=(AMBER, 3), R=(AMBER, 2), d=(AMBER, 1), w=(PAPER, 4)), [""] * 5 + [
        "....ww..........",
        "....w...........",
        "........rR......",
        ".......rRd......",
        "........Rd......",
        ".......rd.......",
    ])
    return i
def pretty_stone():
    """A smooth blue-grey river pebble with a pale quartz vein and a glint: the kind a yukkuri keeps as treasure."""
    i = Icon('pretty_stone')
    i.disc((7.5, 9.0), 6.6, STONE, ry=4.6, glint=True)
    i.finish()
    i.rows(legend(v=(PAPER, 3), V=(PAPER, 2), w=(PAPER, 4)), [""] * 6 + [
        "....w...........",
        "...ww......v....",
        ".........vV.....",
        ".......vV.......",
        ".....vV.........",
        "...vV...........",
    ])
    return i


def _doll(name, hair, top_legend, top_rows):
    """A plush yukkuri doll: a round bun face with hair over the top, two dot eyes and a small smile."""
    i = Icon(name)
    i.disc((7.5, 9.6), 6.8, SKIN, ry=5.6, glint=False)
    i.finish()
    i.rows(legend(e=(INK, 1), m=(BERRY, 1), b=(BERRY, 3)), [""] * 9 + [
        "................",
        ".....e....e.....",
        ".....e....e.....",
        "...b........b...",
        "......mmm.......",
    ])
    i.rows(top_legend, top_rows)
    return i


def reimu_doll():
    """Reimu: dark brown hair with a fringe, the big red bow on top and red-and-white side tubes."""
    return _doll('reimu_doll', HAIR, legend(R=(RED, 0), r=(RED, 2), q=(RED, 3), Q=(RED, 1), w=(PAPER, 4),
                                            H=(HAIR, 0), h=(HAIR, 2), j=(HAIR, 3), g=(HAIR, 1)), [
        "..RRR.....RRR...",
        ".RqrrR...RrrQR..",
        ".RqrrrRwRrrrQR..",
        "..RRrQRRRQrRR...",
        "...HHHHHHHHHH...",
        "..HjhhhhhhhhgH..",
        ".HjhhghhhhghhgH.",
        ".Hjh.g....g.hgH.",
        ".Hh...........H.",
        ".rH...........Hr",
        ".w.............w",
        ".r.............r",
    ])


def marisa_doll():
    """Marisa: blond hair, the black witch hat with a white band, the tip bent to the right."""
    return _doll('marisa_doll', BLOND, legend(K=(INK, 0), k=(INK, 2), l=(INK, 3), d=(INK, 1), w=(PAPER, 4), W=(PAPER, 2),
                                              Y=(BLOND, 0), y=(BLOND, 3), z=(BLOND, 4), x=(BLOND, 2)), [
        "..........KK....",
        ".........KlkK...",
        "........KlkK....",
        ".......KlkdK....",
        "......KlkkdK....",
        "..KKKKwwwwWKKK..",
        ".KllkkkkkkkkddK.",
        "..KKKKKKKKKKKK..",
        ".YzyyyyyyyyyyxY.",
        ".Yzyx.x....x.xY.",
        ".Yy..........Y..",
        ".Yy..........xY.",
        ".Yx...........Y.",
    ])


# ---- playground -------------------------------------------------------------------------------------------------------
def ceramic_tunnel():
    """土管: a clay pipe lying on its side, the open end toward the viewer showing the dark bore."""
    i = Icon('ceramic_tunnel')
    i.rows(legend(o=(TERRA, 0), H=(TERRA, 4), h=(TERRA, 3), m=(TERRA, 2), s=(TERRA, 1), x=(INK, 1), X=(INK, 2)), [
        "................",
        "................",
        "................",
        "..ooooo.........",
        ".oHHHHhoooooooo.",
        "oHhooooHHHHHHhmo",
        "oHoxxxxohhhhhhmo",
        "oHoXxxxohhhhhhmo",
        "oHoxxxxohhhhhmmo",
        "ohoxxxxomhhhmmso",
        "ohoxxxxommmmmsso",
        "omsoooomssssssso",
        ".ommmmsoooooooo.",
        "..ooooo.........",
        "................",
        "................",
    ])
    return i


def yukkuri_slide():
    """Slide: an iron ladder up the left to a small deck, the teal chute running down to a flat run-out at the right."""
    i = Icon('yukkuri_slide')
    i.rows(legend(o=(TEAL, 0), H=(TEAL, 4), h=(TEAL, 3), m=(TEAL, 2), s=(TEAL, 1), i=(IRON, 0), I=(IRON, 3), j=(IRON, 2)), [
        "................",
        "................",
        "..iooooo........",
        "..jHHHHhoo......",
        "..joooomHho.....",
        "..jIIIj.omHho...",
        "..j...j..omHho..",
        "..jIIIj...omHho.",
        "..j...j....omHo.",
        "..jIIIj.....omho",
        "..j...j......oHo",
        "..jIIIj......omo",
        "..j...j.....ohHo",
        "..j...j.....ihmo",
        "..i...i.....i.oo",
        "................",
    ])
    return i


def yukkuri_trampoline():
    """Trampoline seen from above at an angle: a flat dark oval bed inside a gold ring, short iron legs under the ring."""
    i = Icon('yukkuri_trampoline')
    i.rows(legend(g=(GOLD, 0), G=(GOLD, 2), y=(GOLD, 3), Y=(GOLD, 1), k=(INK, 2), K=(INK, 1), l=(INK, 3), i=(IRON, 0),
                  I=(IRON, 2)), [
        "................",
        "................",
        "................",
        "................",
        "....gggggggg....",
        "..ggyyyyyyyygg..",
        ".gyykkkkkkkkGGg.",
        ".gykllkkkkkkKGg.",
        ".gGkkkkkkkkKKYg.",
        "..gGGkkkkKKGYg..",
        "...ggGGGGGGYgg..",
        "...igggggggggi..",
        "...iI.......iI..",
        "...iI.......iI..",
        "...ii.......ii..",
        "................",
    ])
    return i


def yukkuri_pool():
    """Paddling pool seen from the front and above: a pale tiled basin, blue water with white ripples, a tiled front wall."""
    i = Icon('yukkuri_pool')
    i.rows(legend(o=(TILE, 0), W=(TILE, 4), w=(TILE, 3), t=(TILE, 2), T=(TILE, 1),
                  a=(WATER, 1), b=(WATER, 2), c=(WATER, 3), r=(WATER, 4), A=(WATER, 0)), [
        "................",
        "................",
        "................",
        "....oooooooo....",
        "...oWwwwwwwwto..",
        "..oWwAAAAAAwwto.",
        ".oWwAbbrbbbAwto.",
        ".oWAbbbbbrrbAto.",
        ".oWAabbbbbbaATo.",
        ".oWwAAAAAAAAwTo.",
        ".oWwwwwwwwwwtTo.",
        ".oTtTtTtTtTtTTo.",
        ".oTTTTTTTTTTTTo.",
        "..oooooooooooo..",
        "................",
        "................",
    ])
    return i


# ---- care devices -----------------------------------------------------------------------------------------------------
def food_bowl():
    """Pet food bowl: white glazed ceramic with a red rim band, heaped with oil-grain kibble (the toilet is the teal tray)."""
    i = Icon('food_bowl')
    i.rows(legend(c=(WHITE, 0), L=(WHITE, 4), l=(WHITE, 3), f=(WHITE, 2), F=(WHITE, 1), r=(RED, 2), R=(RED, 1), q=(RED, 3),
                  a=(S.PELLET, 0), d=(S.PELLET, 1), b=(S.PELLET, 2), k=(S.PELLET, 3)), [
        "................",
        "................",
        "................",
        "................",
        "................",
        "......akba......",
        "....akbdbkba....",
        "...akbdakbdba...",
        "..ccbdkbadbkcc..",
        ".cqrkbdakbdbrRc.",
        ".cqrrrrrrrrrrRc.",
        ".cLllllllllllFc.",
        "..cLllllllllFc..",
        "...cLlllllFFc...",
        "....ccffffcc....",
        ".....cccccc.....",
    ])
    return i


def yukkuri_nest():
    """Pet cushion nest (strawberry): a low, wide red bolster round a cream pad, darker toward the lower right, the dipped entrance at the front."""
    i = Icon('yukkuri_nest')
    i.rows(legend(o=(BERRY, 0), H=(BERRY, 4), h=(BERRY, 3), m=(BERRY, 2), s=(BERRY, 1),
                  c=(CREAM, 0), p=(CREAM, 3), P=(CREAM, 2), q=(CREAM, 1), w=(PAPER, 4)), [
        "................",
        "................",
        "................",
        "................",
        "...oooooooooo...",
        ".ooHHhhhhhhmmoo.",
        "oHhhmmmmmmmmmsso",
        "oHmoqqqqqqqqomso",
        "ohmcPpwpppppcmso",
        "ohmcPpppppppcsso",
        "ohmocPpppPqcomso",
        "ohhmocccccccmsso",
        ".ohmmmo..ommsso.",
        "..oohmoooomsoo..",
        "....ooossssoo...",
        "......oooo......",
    ])
    return i


def large_yukkuri_nest():
    """2x2 woven nest: a wide, deep basket of natural wicker (weave on the rim and the front wall) round a red cushion."""
    i = Icon('large_yukkuri_nest')
    i.rows(legend(o=(STRAW, 0), H=(STRAW, 4), h=(STRAW, 3), m=(STRAW, 2), s=(STRAW, 1),
                  c=(BERRY, 0), p=(BERRY, 3), P=(BERRY, 2), q=(BERRY, 1), w=(BERRY, 4)), [
        "................",
        "................",
        "................",
        "..oooooooooooo..",
        ".oHhHhHhHhHhHmo.",
        "oHmsmsmsmsmsmsmo",
        "ohsqqqqqqqqqqsmo",
        "oHcPpwpppppppcso",
        "ohcPppppppppPcmo",
        "oHcPpppppppPqcso",
        "ohmcccccccccccmo",
        "oHhmHhmHhmHhmhso",
        "ohmhHmhHmhHmhmso",
        "oHmhmHhmHhmhmsso",
        ".osssssssssssso.",
        "..oooooooooooo..",
    ])
    return i


def large_wild_nest():
    """Wild family bedding: a wide ring of woven dry grass round a pad of green leaves."""
    i = Icon('large_wild_nest')
    i.rows(legend(o=(STRAW, 0), H=(STRAW, 4), h=(STRAW, 3), m=(STRAW, 2), s=(STRAW, 1),
                  g=(LEAF, 0), G=(LEAF, 2), L=(LEAF, 3), l=(LEAF, 1), k=(LEAF, 4)), [
        "................",
        "................",
        "................",
        "................",
        "...oooooooooo...",
        ".ooHhmHhmHhmhoo.",
        "oHhmglLGlGLlgmso",
        "ohmgLkGlLGkLlgmo",
        "oHgGLlGLkGlLGgso",
        "ohglGLlGlLGLlgmo",
        "oHmgGlLGLGlGgmso",
        "ohhmgggggggggmso",
        ".omHhmHhmHhmhso.",
        "..oossmssmssoo..",
        "....oooooooo....",
        "................",
    ])
    return i


def predator_nest():
    """Predator twig nest: a low wide bowl of crossed brown sticks with a ragged rim, the brown hollow seen from above."""
    i = Icon('predator_nest')
    i.rows(legend(o=(TWIG, 0), H=(TWIG, 4), h=(TWIG, 3), m=(TWIG, 2), s=(TWIG, 1), y=(STRAW, 3)), [
        "................",
        "................",
        "................",
        "................",
        "......o...o.....",
        "...ooHoooHooo.o.",
        "..oHhhmhhmhmmoh.",
        ".ohmooooooooomso",
        ".oHosssssssssoso",
        "oHhmossmsssmohmo",
        ".ohmHoooooooHmso",
        ".oHhmHhmsHhmhsso",
        "oohmsmhymmsmhmo.",
        "..ossmssmsmsso..",
        "...oooooooooo...",
        "................",
    ])
    return i


def yukkuri_toilet():
    """Sand toilet: a mint tray seen from the front and above, filled with pale sand."""
    i = Icon('yukkuri_toilet')
    i.rows(legend(m=(MINTC, 0), H=(MINTC, 4), h=(MINTC, 3), g=(MINTC, 2), s=(MINTC, 1),
                  a=(SAND, 0), d=(SAND, 1), D=(SAND, 2), e=(SAND, 3), E=(SAND, 4)), [
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "...mmmmmmmmmm...",
        "..mHhhhhhhhhgm..",
        ".mHmdDDDDDDDmgm.",
        ".mhDeEeeeeeeDgm.",
        "mHmDeeeedeeeDmgm",
        "mhmmmmmmmmmmmmsm",
        "mHHhhhhhhhhhhggm",
        "mhhhhhhhhhhhggsm",
        ".mssssssssssssm.",
        "..mmmmmmmmmmmm..",
    ])
    return i


def cardboard_shelter():
    """Stray cardboard box lying on its side, the open end toward the viewer with a flap folded out, tape over the top."""
    i = Icon('cardboard_shelter')
    i.rows(legend(o=(KRAFT, 0), d=(KRAFT, 1), m=(KRAFT, 2), L=(KRAFT, 3), H=(KRAFT, 4), t=(PAPER, 3), T=(PAPER, 2),
                  x=(KRAFT, 1), X=(KRAFT, 2)), [
        "................",
        "................",
        "................",
        "......oooooooooo",
        ".....oHHHtHHHHLo",
        "....oHLLLtLLLLmo",
        "...oHLLLtLLLLmmo",
        "..ooooooToooommo",
        "o.oHHLLLLLLLomdo",
        "oooLdddddddmomdo",
        "oHoLdoooooodmomo",
        "oLoLdoXxxxodmomo",
        "oLoLdoxxxxodmomo",
        "omoLdoxxxxodmoo.",
        "oooLmddddddmoo..",
        "..ooooooooooo...",
    ])
    return i


def crate_shelter():
    """Windbreak crate on its side: dark boards with seams on the top and side, the open front showing the inside planks."""
    i = Icon('crate_shelter')
    i.rows(legend(o=(DARKWOOD, 0), d=(DARKWOOD, 1), m=(DARKWOOD, 2), L=(DARKWOOD, 3), H=(DARKWOOD, 4)), [
        "................",
        "................",
        "................",
        "......oooooooooo",
        ".....oHHoHHHoHLo",
        "....oHHoHHHoHLmo",
        "...oLLoLLLoLLmdo",
        "..ooooooooooomdo",
        "..oHHHHHHHHHLodo",
        "..oLooooooooLomo",
        "..oHoLLLLLLoHodo",
        "..oLoddddddoLomo",
        "..oHooooooooHodo",
        "..oLommmmmmoLoo.",
        "..oHHLLHHLLHLo..",
        "..ooooooooooooo.",
    ])
    return i


def forage_pile():
    """Forage store: a mound of gathered leaves and grass with a clump of red berries, brown nuts and a white daisy on top."""
    i = Icon('forage_pile')
    i.rows(legend(o=(LEAF, 0), L=(LEAF, 3), l=(LEAF, 2), G=(LEAF, 1), y=(STRAW, 3),
                  r=(RED, 2), R=(RED, 1), q=(RED, 3), w=(FLOWER, 2), c=(FLOWER, 1), n=(WOOD, 3), N=(WOOD, 1), b=(WOOD, 0)), [
        "................",
        "................",
        "..........w.....",
        ".........wcw....",
        "..........wl....",
        ".....l....oGl...",
        "....lGoo.oLlGo..",
        "...oLlLGooLlGlo.",
        "..oLqrlLlGnNlGo.",
        "..oqrRqlGnNbnlGo",
        ".oLrRRlLlnbNlGGo",
        ".oLlRlLGlylLlGGo",
        "oLlGlLlGlLlGGlGo",
        "olGGlGGlGGlGGGGo",
        ".oooooooooooooo.",
        "................",
    ])
    return i


BASKET = [   # carrier: an open woven basket with a high arched handle (supplies-style 3/4 front view)
    "................",
    ".....oooooo.....",
    "....oHmmmmso....",
    "...oH......so...",
    "...om......so...",
    "..ooooooooooooo.",
    ".oHhHhHhHhHhHmso",
    ".oqqqqqqqqqqqqqo",
    ".ooHhmHhmHhmhsoo",
    "..oHmhHmhHmhmso.",
    "..ohmHhmHhmhsso.",
    "..oHmhHmhHmhmso.",
    "..ohmHhmHhmhsso.",
    "...ohmhmhmhsso..",
    "...osssssssssoo.",
    "....ooooooooo...",
]


def _carrier(name, wicker, lining):
    i = Icon(name)
    i.rows(legend(o=(wicker, 0), H=(wicker, 4), h=(wicker, 3), m=(wicker, 2), s=(wicker, 1), q=(lining, 2)), BASKET)
    return i


def yukkuri_carrier():
    """Carrier (4 places): natural wicker basket with a cream lining."""
    return _carrier('yukkuri_carrier', STRAW, CREAM)


def yukkuri_carrier_fine():
    """Fine carrier (6 places): blue-dyed wicker."""
    return _carrier('yukkuri_carrier_fine', NAVYW, PAPER)


def yukkuri_carrier_large():
    """Large carrier (12 places): green-dyed wicker, a wider basket."""
    i = Icon('yukkuri_carrier_large')
    i.rows(legend(o=(SAGEW, 0), H=(SAGEW, 4), h=(SAGEW, 3), m=(SAGEW, 2), s=(SAGEW, 1), q=(PAPER, 2)), [
        "................",
        "....oooooooo....",
        "...oHmmmmmmso...",
        "..oH........so..",
        "..om........so..",
        "oooooooooooooooo",
        "oHhHhHhHhHhHhmso",
        "oqqqqqqqqqqqqqqo",
        "oHhmHhmHhmHhmhso",
        "ohmHhmHhmHhmhsso",
        "oHmhHmhHmhHmhmso",
        "ohmHhmHhmHhmhsso",
        "oHmhHmhHmhHmhmso",
        ".ohmhmhmhmhmhso.",
        ".osssssssssssso.",
        "..oooooooooooo..",
    ])
    return i


# ---- care tools -------------------------------------------------------------------------------------------------------
def wash_cloth():
    """Wash cloth: a folded light-blue towel, the folded edge lit, a darker stripe near the hem."""
    i = Icon('wash_cloth')
    i.rows(legend(o=(WASH, 0), H=(WASH, 4), h=(WASH, 3), m=(WASH, 2), s=(WASH, 1), w=(PAPER, 4)), [
        "................",
        "................",
        "................",
        "................",
        "......oooooooo..",
        "....ooHHHHHHhho.",
        "..ooHHhhhhhhhmo.",
        ".oHhhhhhhhhhmmo.",
        ".oHhwhhhhhhmmso.",
        ".ooooooooooooso.",
        ".oHhhhhhhhhhmso.",
        ".oHsssssssssmso.",
        ".oHhhhhhhhhhmo..",
        ".ommmmmmmmmmso..",
        ".oooooooooooo...",
        "................",
    ])
    return i


def teaching_whistle():
    """Teaching whistle (pea whistle, side view): a flat wooden mouthpiece to the left running into the round chamber, the sound
    notch cut into the top edge, an iron ring at the back."""
    i = Icon('teaching_whistle')
    i.rows(legend(o=(WHISTLE, 0), H=(WHISTLE, 4), h=(WHISTLE, 3), m=(WHISTLE, 2), s=(WHISTLE, 1), K=(INK, 1),
                  c=(IRON, 1), C=(IRON, 3)), [
        "................",
        "................",
        ".............CC.",
        "............C..c",
        "oooooooo.ooooCcc",
        "oHHHhhhhoKoHhhmo",
        "ohhhhhhhhhhhhhmo",
        "ommmmmmmhhhhhmmo",
        "oooooooohhhhhmso",
        ".......ohhhhmmso",
        ".......ohhhmmsso",
        "........ossssso.",
        ".........ooooo..",
        "................",
        "................",
        "................",
    ])
    return i


def companion_cord():
    """Companion cord: a loop of soft brown rope tied off with a red bow (it introduces a pair; nothing is bound)."""
    i = Icon('companion_cord')
    i.rows(legend(o=(ROPE, 0), H=(ROPE, 4), h=(ROPE, 3), m=(ROPE, 2), s=(ROPE, 1), R=(RED, 0), r=(RED, 2), q=(RED, 3), Q=(RED, 1)), [
        "................",
        "................",
        ".....oooooo.....",
        "....oHhhhhmo....",
        "...oHoooooomo...",
        "..oHo......omo..",
        "..oho......omo..",
        "..oho......oso..",
        "..omo......oso..",
        "...omo....oso...",
        "....omRR.RRso...",
        ".....RqrRrqR....",
        ".....RrrRrrQ....",
        "......RRrRR.....",
        ".....ohR.Ro.....",
        "....oho...oho...",
    ])
    return i
def yukkuri_stem():
    """A yukkuri stem: a green curved stalk with two small leaves and the round knob where it grew from the head."""
    i = Icon('yukkuri_stem')
    i.rows(legend(o=(LEAF, 0), H=(LEAF, 4), h=(LEAF, 3), m=(LEAF, 2), s=(LEAF, 1)), [
        "................",
        "............ho..",
        "...........Hho..",
        "..........Hho...",
        "......oo..hmo...",
        ".....oHhmohmo...",
        "......ommhmo....",
        ".......ohmo.....",
        "......ohmo.oo...",
        "......hmo.oHho..",
        ".....hmoohhmo...",
        ".....hmmhmoo....",
        "....ohmoo.......",
        "...oHhmso.......",
        "...ohmmso.......",
        "....osso........",
    ])
    return i


ICONS = {
    'yogurt_drink': yogurt_drink, 'stimulant': stimulant, 'sitataran': sitataran, 'sleep_spray': sleep_spray,
    'spicy_grain': spicy_grain, 'reproductive_filling': reproductive_filling, 'wheat_flour': wheat_flour,
    'mold_remover': mold_remover, 'household_guard': household_guard,
    'yarn_ball': yarn_ball, 'glass_marble': glass_marble, 'pretty_stone': pretty_stone, 'reimu_doll': reimu_doll,
    'marisa_doll': marisa_doll,
    'ceramic_tunnel': ceramic_tunnel, 'yukkuri_slide': yukkuri_slide, 'yukkuri_trampoline': yukkuri_trampoline,
    'yukkuri_pool': yukkuri_pool,
    'food_bowl': food_bowl, 'yukkuri_nest': yukkuri_nest, 'large_yukkuri_nest': large_yukkuri_nest,
    'large_wild_nest': large_wild_nest, 'predator_nest': predator_nest, 'yukkuri_toilet': yukkuri_toilet,
    'cardboard_shelter': cardboard_shelter, 'crate_shelter': crate_shelter, 'forage_pile': forage_pile,
    'yukkuri_carrier': yukkuri_carrier, 'yukkuri_carrier_fine': yukkuri_carrier_fine,
    'yukkuri_carrier_large': yukkuri_carrier_large,
    'wash_cloth': wash_cloth, 'teaching_whistle': teaching_whistle, 'companion_cord': companion_cord,
    'yukkuri_stem': yukkuri_stem,
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
        raise SystemExit('Item icon snapshot differs: ' + ', '.join(changed))
    print(f'Item icons: {len(ICONS)}; ' + ('match.' if args.check else f'{len(changed)} files written.'))


if __name__ == '__main__':
    main()
