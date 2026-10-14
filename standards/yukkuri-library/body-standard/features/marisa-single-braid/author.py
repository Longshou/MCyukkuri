"""Author ordinary Marisa's side hair: one thick braid and one loose side lock.

Writes the cube-era sources that the frozen body-standard inputs were assembled from:
components/marisa/hand-left.json, hand-right.json and the braid sub-bones in rigs/marisa.json.
Then run sync_inputs.py (re-assembles inputs/marisa and inputs/marisa_tsumuri) and
body-standard/build.py. Textures are untouched: only existing UV cells are used.

The braid hangs on Marisa's own left: +X, bone hand_right, which the library viewer and
Minecraft show on the right of the front view (hand_* names follow that front view).
Its woven body is thicker than Reimu's tied lock (1.00 x 1.30, cuff 1.24-1.50 x 1.60-1.82)
in both directions.
"""
from pathlib import Path
import argparse, json

R = Path(__file__).resolve().parents[3]  # standards/yukkuri-library
# Existing 16 px cells of textures/marisa/base.png (row 48); no new pixels are needed.
CELLS = {'hair': (16, 48), 'hair_light': (32, 48), 'hair_dark': (48, 48), 'ribbon': (112, 48)}

BRAID_X = 7.05          # |x| of the braid axis
BRAID_Z = -3.15         # z of the braid axis (front is -Z)
LOBE_SIZE = (2.36, 2.30)  # woven lobe width (x) and depth (z) at the top
LOBE_TAPER = 0.06       # per-lobe shrink toward the ribbon
LOBE_TOP, LOBE_PITCH, LOBE_HEIGHT, LOBES = 7.25, 0.80, 0.94, 6
LOBE_TILT = 17          # alternating weave tilt, degrees about Z
CORE = (1.78, 1.74)     # dark inner strands seen between lobes
RIBBON_Y = (2.02, 2.66)


def element(name, lo, hi, material, bone, rotation=(0, 0, 0), origin=None):
    u, v = CELLS[material]
    if origin is None:
        origin = [round((a+b)/2, 6) for a, b in zip(lo, hi)] if any(rotation) else [0.0, 0.0, 0.0]
    return {'name': name, 'type': 'cube', 'from': [round(x, 6) for x in lo], 'to': [round(x, 6) for x in hi],
            'origin': origin, 'rotation': list(rotation), 'box_uv': False, 'rescale': False, 'autouv': 0,
            'faces': {f: {'uv': [float(u), float(v), float(u+16), float(v+16)], 'texture': 0}
                      for f in ['north', 'east', 'south', 'west', 'up', 'down']},
            'bone': bone}


def box(name, sign, x, y, z, size, material, bone, tilt=0):
    """Centered box; x is the outward distance from the midline, sign selects the side."""
    w, h, d = size
    lo = [sign*x - w/2, y - h/2, z - d/2]; hi = [sign*x + w/2, y + h/2, z + d/2]
    lo[0], hi[0] = min(lo[0], hi[0]), max(lo[0], hi[0])
    return element(name, lo, hi, material, bone, (0, 0, sign*tilt))


def braid(side):
    s = -1 if side == 'left' else 1; bone = 'hand_'+side
    out = [
        # Side hair gathered under the hat into the braid root.
        box('braid_lock_root', s, 6.80, 7.92, BRAID_Z-.05, (1.80, 1.12, 1.96), 'hair', bone),
        box('braid_lock_root_outer', s, 7.12, 7.42, BRAID_Z, (1.92, .74, 2.12), 'hair', bone),
        # Dark inner strands; only visible in the notches between lobes.
        box('braid_lock_core', s, BRAID_X, (LOBE_TOP+RIBBON_Y[1])/2, BRAID_Z,
            (CORE[0], LOBE_TOP-RIBBON_Y[1]+.30, CORE[1]), 'hair_dark', bone),
    ]
    for i in range(LOBES):
        y = LOBE_TOP - LOBE_HEIGHT/2 - i*LOBE_PITCH
        w, d = LOBE_SIZE[0]-i*LOBE_TAPER, LOBE_SIZE[1]-i*LOBE_TAPER
        sway = .14 if i % 2 else -.14
        out.append(box(f'braid_lock_lobe_{i}', s, BRAID_X+sway, y, BRAID_Z, (w, LOBE_HEIGHT, d),
                       'hair_light' if i % 2 else 'hair', bone, LOBE_TILT if i % 2 else -LOBE_TILT))
    ribbon = 'hand_'+side+'_ribbon'; tip = 'hand_'+side+'_tip'
    y0, y1 = RIBBON_Y; ym = (y0+y1)/2; zf = BRAID_Z-LOBE_SIZE[1]/2
    out += [
        # White tie wraps the braid; the bow faces forward on the outer half so it reads from the front.
        box('braid_lock_ribbon_band', s, BRAID_X, ym, BRAID_Z, (2.24, y1-y0, 2.20), 'ribbon', ribbon),
        box('braid_lock_bow_knot', s, BRAID_X+.18, ym+.04, zf-.12, (.62, .70, .44), 'ribbon', ribbon),
        box('braid_lock_bow_loop_out', s, BRAID_X+.86, ym+.26, zf-.06, (1.06, .78, .34), 'ribbon', ribbon, -22),
        box('braid_lock_bow_loop_in', s, BRAID_X-.50, ym+.26, zf-.06, (1.06, .78, .34), 'ribbon', ribbon, 22),
        box('braid_lock_bow_tail_out', s, BRAID_X+.52, ym-.62, zf-.04, (.34, .74, .26), 'ribbon', ribbon, -16),
        box('braid_lock_bow_tail_in', s, BRAID_X-.16, ym-.62, zf-.04, (.34, .74, .26), 'ribbon', ribbon, 16),
        # Loose end below the tie: flares, then splits into two soft points.
        box('braid_lock_tuft_0', s, BRAID_X, 1.62, BRAID_Z, (2.06, .84, 2.06), 'hair', tip),
        box('braid_lock_tuft_1', s, BRAID_X, .98, BRAID_Z, (2.26, .62, 2.20), 'hair_light', tip),
        box('braid_lock_tuft_point_out', s, BRAID_X+.50, .52, BRAID_Z, (.96, .50, 1.62), 'hair', tip, -10),
        box('braid_lock_tuft_point_in', s, BRAID_X-.46, .56, BRAID_Z-.12, (.90, .44, 1.40), 'hair_dark', tip, 10),
    ]
    return out


def loose_lock(side):
    """Plain un-braided side lock: thinner than the braid, no tie."""
    s = -1 if side == 'left' else 1; bone = 'hand_'+side
    return [
        box('side_hair_loose', s, 6.48, 5.95, -3.10, (1.06, 5.06, 1.10), 'hair', bone),
        box('side_hair_loose_tip_0', s, 6.50, 3.08, -3.12, (.86, .78, .98), 'hair_light', bone, 6),
        box('side_hair_loose_tip_1', s, 6.56, 2.47, -3.14, (.50, .56, .80), 'hair', bone, 10),
    ]


def component(side, elements, bones):
    return {'id': 'marisa.hand-'+side, 'elements': elements, 'owned_bones': bones,
            'attachment': 'see skeleton attachments; independent hands are not bound to skin slices'}


def update_rig(braid_side, loose):
    """Only the braid side keeps tie/tuft sub-bones; pivots follow the new tie and tuft."""
    p = R/'rigs/marisa.json'; rig = json.loads(p.read_text(encoding='utf-8'))
    s = 1 if braid_side == 'right' else -1; bones = []
    for b in rig['bones']:
        if b['name'] in ('hand_'+loose+'_tip', 'hand_'+loose+'_ribbon'): continue
        bones.append(b)
        if b['name'] == 'hand_'+braid_side:
            hand = b
    subs = {'hand_'+braid_side+'_tip': [s*BRAID_X, 2.02, BRAID_Z], 'hand_'+braid_side+'_ribbon': [s*BRAID_X, 2.34, BRAID_Z]}
    for name, pivot in subs.items():
        b = next((b for b in bones if b['name'] == name), None)
        if b is None:
            b = {'name': name}; bones.insert(next(i for i, x in enumerate(bones) if x['name'].startswith('puff_left_')), b)
        b.update(parent=hand['name'], pivot=pivot)
    rig['bones'] = bones
    p.write_text(json.dumps(rig, indent=2), encoding='utf-8')
    print('updated', p)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--braid-side', choices=['left', 'right'], default='right',
                    help="hand bone for the single braid; right = +X = Marisa's own left")
    ap.add_argument('--out', type=Path, default=R/'components/marisa', help='target directory (default: library source)')
    a = ap.parse_args()
    loose = 'right' if a.braid_side == 'left' else 'left'
    if a.out == R/'components/marisa':
        update_rig(a.braid_side, loose)
    a.out.mkdir(parents=True, exist_ok=True)
    for side, data in [(a.braid_side, component(a.braid_side, braid(a.braid_side),
                                                ['hand_'+a.braid_side, 'hand_'+a.braid_side+'_ribbon', 'hand_'+a.braid_side+'_tip'])),
                       (loose, component(loose, loose_lock(loose), ['hand_'+loose]))]:
        p = a.out/f'hand-{side}.json'
        p.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
        print('wrote', p, len(data['elements']), 'cubes')


if __name__ == '__main__':
    main()
