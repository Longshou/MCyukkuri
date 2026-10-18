"""Author Alice's Hakkei-based base look: lemon-blonde bob, red lace headband.

Writes the cube-era sources that the frozen body-standard inputs are assembled from:
components/alice/hair.json, hand-left.json, hand-right.json and accessories.json.
Then run sync_inputs.py (re-assembles inputs/alice, repaints its palette cells) and
body-standard/build.py --character alice. See README.md for the full export order.

Reference (not copied): Hakkei U slot 2 sprites 7607 (hair), 7038 (headband), 6880
(side tips); Hakkei ver1.12 pict/arisu layers; wiki species page (blonde, red headband).
Coordinates are the cube-era library frame: 16 units per block, +Y up, -Z is the face.
Part names keep the runtime contracts: accessory prefixes alice_band_ / alice_lace_,
the badge part alice_band_red_2, and "hair" / "bang" in every hair part so the game's
side-by-side hair loss hides them (the previous alice_cap/alice_side_* names never hid).
"""
from pathlib import Path
import argparse, json

R = Path(__file__).resolve().parents[3]  # standards/yukkuri-library

# Existing 8 px palette cells of textures/alice/base.png row 64; sync_inputs.py repaints them.
CELLS = {'hair': (0, 64), 'hair_light': (8, 64), 'hair_dark': (16, 64),
         'red': (24, 64), 'red_dark': (32, 64), 'lace': (40, 64)}
# Hakkei U Alice is a pale lemon blonde (sprite 7607: e7e37b / d6d36b / ffff94), clearly
# cooler and lighter than Marisa's honey blonde; headband red ff3c42 with white lace.
PALETTE = {'hair': (246, 230, 124), 'hair_light': (255, 248, 172), 'hair_dark': (214, 194, 96),
           'red': (236, 50, 58), 'red_dark': (180, 30, 42), 'lace': (255, 253, 244)}
# Blue eyes (Hakkei ver1.12 pict/arisu/sei_new/6.png: iris 0000ff, lower glow 5353ff/8d8dff,
# dark outline), using the same blue/outline as the ver1.12 adult-state faces (0f28ee, 251f1f).
# Only open-iris eyes change: dark brown pixels on the eye's edge become the outline, enclosed
# ones the iris; the light-brown lower glow turns light blue. Highlights stay white; closed-eye
# lines, blank eyes and the grey dull/dead eyes keep their colours.
IRIS = {'outline': (0x25, 0x1f, 0x1f), 'iris': (0x0f, 0x28, 0xee)}
IRIS_DARK = {(0x32, 0x29, 0x23), (0x48, 0x35, 0x2e), (0x49, 0x36, 0x2d), (0x52, 0x3a, 0x2a), (0x3f, 0x33, 0x2e)}
IRIS_GLOW = {(0x76, 0x52, 0x39): (0x53, 0x6a, 0xff), (0x8b, 0x64, 0x3f): (0x8d, 0x8d, 0xff)}
IRIS_HIGHLIGHTS = {(0xff, 0xf8, 0xe9), (0xea, 0xd4, 0xad), (0xf7, 0xf0, 0xe0), (0xef, 0xe7, 0xd0), (0xff, 0xfa, 0xe6)}

# Fringe: seven locks, Hakkei 7607 rhythm (long temple locks, a long centre spike,
# a shorter parting just right of centre). Bottom of each block, then the point's sideways shift.
BANG_X = [-6.05, -4.32, -2.59, -0.86, 0.87, 2.60, 4.33]
BANG_W = 1.75
BANG_BOTTOM = [5.95, 6.85, 6.50, 6.20, 7.05, 6.70, 5.95]
BANG_SHIFT = [-.34, -.22, .08, -.14, .18, .24, .34]
BANG_MATERIAL = ['hair', 'hair_light', 'hair', 'hair_light', 'hair', 'hair_light', 'hair']
BANG_Z = (-5.08, -4.57)

# Back: seven spiky locks over a backing panel; ends tuck inward (Hakkei ver1.12 rear frames).
BACK_X = [-6.20, -4.42, -2.64, -0.86, 0.92, 2.70, 4.48]
BACK_W = 1.65
BACK_BOTTOM = [2.75, 2.25, 2.55, 2.05, 2.55, 2.25, 2.75]


def element(name, lo, hi, material, bone, rotation=(0, 0, 0), origin=None):
    u, v = CELLS[material]
    if origin is None:
        origin = [round((a+b)/2, 6) for a, b in zip(lo, hi)] if any(rotation) else [0.0, 0.0, 0.0]
    return {'name': name, 'type': 'cube', 'from': [round(x, 6) for x in lo], 'to': [round(x, 6) for x in hi],
            'origin': origin, 'rotation': list(rotation), 'box_uv': False, 'rescale': False, 'autouv': 0,
            'faces': {f: {'uv': [float(u), float(v), float(u+8), float(v+8)], 'texture': 0}
                      for f in ['north', 'east', 'south', 'west', 'up', 'down']},
            'bone': bone}


def mirrored(name, x0, x1, y0, y1, z0, z1, material, bone_of_side):
    """Left (-X) and right (+X) copies; x0 < x1 are outward distances on the right side."""
    out = []
    for side, s in (('left', -1), ('right', 1)):
        a, b = sorted((s*x0, s*x1))
        out.append(element(f'{name}_{side}', [a, y0, z0], [b, y1, z1], material, bone_of_side(side)))
    return out


def hair():
    H = lambda side: 'hair'
    out = [
        # Cap split at the midline so each side disappears with that side's hair loss.
        element('alice_hair_cap_left', [-6.35, 8.18, -4.58], [0, 9.50, 6.85], 'hair', 'hair'),
        element('alice_hair_cap_right', [0, 8.18, -4.58], [6.35, 9.50, 6.85], 'hair', 'hair'),
        # Raised crown that the placement-fit dome rounds into the bob's crown.
        element('alice_hair_crown', [-5.35, 9.40, -3.60], [5.35, 9.78, 5.95], 'hair', 'crown'),
        element('alice_hair_back_left', [-6.20, 3.30, 6.02], [0, 8.90, 7.45], 'hair', 'hair'),
        element('alice_hair_back_right', [0, 3.30, 6.02], [6.20, 8.90, 7.45], 'hair', 'hair'),
    ]
    for i, (x, bottom) in enumerate(zip(BACK_X, BACK_BOTTOM)):
        mat = 'hair_light' if i % 2 else 'hair'
        out.append(element(f'alice_hair_back_lock_{i}', [x, bottom, 7.10], [x+BACK_W, 8.35, 7.80], mat, 'hair'))
        # Inward-curling end, then a narrow point (the bob tucks under at the back).
        out.append(element(f'alice_hair_back_hem_{i}', [x+.13, bottom-.42, 6.82], [x+BACK_W-.13, bottom+.06, 7.55], 'hair_dark', 'hair'))
        c = x+BACK_W/2+(.16 if i % 2 else -.16)
        out.append(element(f'alice_hair_back_tip_{i}', [c-.42, bottom-.78, 6.70], [c+.42, bottom-.38, 7.28], mat, 'hair'))
    # Side curtains: full bob walls, an outer swell at cheek height, and inward curling ends.
    out += mirrored('alice_hair_side', 5.87, 7.13, 3.40, 8.60, -2.50, 6.30, 'hair', H)
    # Outer strands at cheek height give the bob its swell without a flat panel.
    for i, (y0, y1, z0, z1) in enumerate([(3.80, 7.70, -1.95, -0.25), (3.45, 7.95, 0.35, 2.25), (3.85, 7.40, 2.85, 4.95)]):
        out += mirrored(f'alice_hair_side_swell{i}', 7.05, 7.30 if i == 1 else 7.36, y0, y1, z0, z1, 'hair', H)
    out += mirrored('alice_hair_side_curl0', 5.85, 7.15, 2.60, 3.65, -2.35, 6.20, 'hair', H)
    out += mirrored('alice_hair_side_curl1', 5.62, 6.80, 1.85, 2.80, -2.35, 5.95, 'hair', H)
    out += mirrored('alice_hair_side_curl2', 5.42, 6.25, 1.20, 2.00, -2.35, 5.70, 'hair_dark', H)
    for i, (x, bottom, shift, mat) in enumerate(zip(BANG_X, BANG_BOTTOM, BANG_SHIFT, BANG_MATERIAL)):
        z0, z1 = BANG_Z
        out.append(element(f'alice_bang_{i}', [x, bottom, z0], [x+BANG_W, 9.02, z1], mat, 'bangs'))
        out.append(element(f'alice_bang_tip_{i}', [x+.26, bottom-.40, z0+.02], [x+BANG_W-.26, bottom+.06, z1-.03], mat, 'bangs'))
        c = x+BANG_W/2+shift
        out.append(element(f'alice_bang_point_{i}', [c-.33, bottom-.74, z0+.04], [c+.33, bottom-.34, z1-.04], 'hair', 'bangs'))
    # Long centre spike between the eyes (Hakkei 7607), like Reimu's centre tip.
    out.append(element('alice_bang_centre_point', [-.42, 5.05, -5.04], [.08, 5.52, -4.61], 'hair', 'bangs'))
    return {'id': 'alice.hair', 'elements': out}


def hand(side):
    """Face-framing front lock on the hand bone: falls past the cheek and points in at the jaw."""
    s = -1 if side == 'left' else 1; bone = 'hand_'+side
    def box(name, x0, x1, y0, y1, z0, z1, mat):
        a, b = sorted((s*x0, s*x1))
        return element(f'{name}_{side}', [a, y0, z0], [b, y1, z1], mat, bone)
    return {'id': 'alice.hand-'+side, 'elements': [
        box('alice_hair_front', 5.80, 7.20, 3.65, 8.45, -3.98, -2.44, 'hair'),
        # A lighter strand on the outer front face keeps the lock readable against the face.
        box('alice_hair_front_strand', 6.25, 6.98, 4.20, 8.30, -4.12, -3.90, 'hair_light'),
        box('alice_hair_front_tip0', 5.84, 7.12, 2.82, 3.90, -3.95, -2.52, 'hair'),
        box('alice_hair_front_tip1', 5.62, 6.80, 2.06, 3.00, -3.93, -2.56, 'hair_light'),
        box('alice_hair_front_tip2', 5.42, 6.34, 1.46, 2.22, -3.90, -2.62, 'hair'),
        box('alice_hair_front_tip3', 5.34, 5.86, 1.02, 1.58, -3.85, -2.72, 'hair_dark'),
    ]}


def headband():
    """Red band over the crown, worn right at the hairline: the fringe falls from under it.

    A white lace strip hangs on its front edge over the fringe roots and ends in small
    scallops on the fringe (Hakkei U 7038: red band, white lace on the lower edge). The ends
    run down the cap sides onto the face-framing locks. The placement-fit dome later bends
    band, lace and hair together. alice_band_red_2 stays the centre front face for the badge.
    """
    B = 'alice_headband'
    rz, lz, sz = (-4.62, -3.36), (-4.82, -4.58), (-5.16, -5.00)   # red, lace strip, scallops
    segments = [  # x range, red y range
        ((-6.60, -5.60), (9.20, 9.86)),
        ((-5.60, -4.40), (9.36, 10.00)),
        ((-4.40, 4.40), (9.36, 10.22)),
        ((4.40, 5.60), (9.36, 10.00)),
        ((5.60, 6.60), (9.20, 9.86)),
    ]
    out, k = [], 0
    for i, ((x0, x1), ry) in enumerate(segments):
        out.append(element(f'alice_band_red_{i}', [x0, ry[0], rz[0]], [x1, ry[1], rz[1]], 'red', B))
        out.append(element(f'alice_band_white_{i}', [x0, 8.96, lz[0]], [x1, min(9.44, ry[0]+.08), lz[1]], 'lace', B))
        # Scalloped lace edge resting on the fringe roots.
        n = max(1, round((x1-x0)/.62)); step = (x1-x0)/n
        for j in range(n):
            c = x0+step*(j+.5)
            out.append(element(f'alice_lace_{k}', [c-.19, 8.74, sz[0]], [c+.19, 9.04, sz[1]], 'lace', B)); k += 1
        # Smaller frill along the back edge, just peeking over the red (7038 has lace on both edges).
        for j in range(n):
            c = x0+step*(j+.5)
            out.append(element(f'alice_lace_{k}', [c-.15, ry[1]-.22, rz[1]-.02], [c+.15, ry[1]+.12, rz[1]+.22], 'lace', B)); k += 1
    # The ends drop down the cap sides and stop on top of the face-framing locks (y 8.45).
    for side, s in (('left', -1), ('right', 1)):
        a, b = sorted((s*6.32, s*6.68))
        out.append(element(f'alice_band_side_{side}', [a, 8.30, -4.36], [b, 9.50, -3.36], 'red_dark', B))
        out.append(element(f'alice_band_side_lace_{side}', [a, 8.30, -4.60], [b, 9.30, -4.34], 'lace', B))
    return {'id': 'alice.accessories', 'elements': out}


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--out', type=Path, default=R/'components/alice', help='target directory (default: library source)')
    a = ap.parse_args(); a.out.mkdir(parents=True, exist_ok=True)
    for name, data in [('hair', hair()), ('hand-left', hand('left')), ('hand-right', hand('right')), ('accessories', headband())]:
        p = a.out/f'{name}.json'
        p.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        print('wrote', p, len(data['elements']), 'cubes')


if __name__ == '__main__':
    main()
