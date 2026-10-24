#!/usr/bin/env python3
"""Fit the stem to the number of young it carries: length per count and where each fruit hangs.

The stem keeps its reference arc (standards/yukkuri-v1/components/common/stem.json, from Hakkei U)
and is scaled per brood size. For each count the shortest stem is chosen on which every ripe
fruit (at the size standard's full fruit size, docs/BEHAVIOR.md) hangs:

- clear in front of the mother, so it drops straight to the ground without touching her face,
  hat or ears (the mother's runtime model, in the column the fruit falls through). At rest a fruit
  hangs plumb: its hanging anchor (crown top when none is registered) lies directly below its socket;
- apart from the other fruit front to back, so a fruit falling first never lands on one still
  hanging below it, and the hanging fruit read as separate in an oblique view;
- with neighbouring fruit on alternate short side spurs (socket_spur, nearest fruit to the right),
  so they do not bunch in an oblique view and the descending stalk passes beside the nearest one;
- above the ground.

Every stem-bearing mother and every child kind is checked, at the shortest individual variation
(brood.variation). The fruit sockets are spread evenly front to back along the outer part of the
arc, the farthest where Hakkei U hangs its farthest fruit. The canonical three sockets sat close
to the root on a long stem; shortening it moves the near ones outward (project adaptation).

Writes count_length_scale to recipe.json, and socket_layouts and capacity_scale (the old preview's
copy of the lengths) to the stem component; then run scripts/export_family_assets.py.

  python fit_stem.py [--check]
"""
from pathlib import Path
import argparse, json, math
import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RECIPE = HERE/'recipe.json'
STEM = ROOT/'standards/yukkuri-v1/components/common/stem.json'
GROWTH = ROOT/'standards/yukkuri-v1/profiles/growth.json'
MODELS = ROOT/'src/main/resources/assets/mcyukkuri/yukkuri'
MOTHERS = ['reimu', 'marisa', 'chen', 'alice', 'patchouli', 'remilia', 'flandre']
SPACING = ['reimu', 'marisa']  # the young measured for spacing; tails and wings of other kinds are soft
FIT = {'mother_clear': 0.8, 'fruit_gap': 1.0, 'ground_clear': 1.0, 'variation_min': 0.93, 'min_length': 0.7,
       'far_socket': [-1.28, 1.89]}


_MODELS = {}


def bounds(ident):
    """A runtime model's vertex positions (model units), read once."""
    if ident not in _MODELS:
        _MODELS[ident] = np.array(json.loads((MODELS/f'{ident}.json').read_text(encoding='utf-8'))['vertices']).reshape(-1, 8)[:, :3]
    return _MODELS[ident]


class Stem:
    def __init__(self, recipe, stem):
        self.r, self.s = recipe, stem
        line = np.array(stem['centerline'], float)
        seg = np.linalg.norm(np.diff(line, axis=0), axis=1); self.u = np.r_[0, np.cumsum(seg)]/seg.sum(); self.line = line

    def at(self, u):
        """Reference (z, y) on the centerline at arc fraction u."""
        return np.array([np.interp(u, self.u, self.line[:, k]) for k in (0, 1)])

    def fraction(self, zy):
        """Arc fraction of the centerline point nearest a reference point."""
        uu = np.linspace(0, 1, 2001); pts = np.array([self.at(u) for u in uu])
        return float(uu[np.argmin(np.linalg.norm(pts-np.array(zy), axis=1))])

    def point(self, zy, length, mother):
        """FamilyGeometry.point at mother scale 1, model units."""
        var = self.r['variants'][mother]; units = self.s['reference_units_to_model']
        rise = (zy[1]-self.s['reference_origin'][1])*units*length
        depth = var.get('depth_clearance', self.s['depth_clearance']); route = var.get('clearance_rise', .3*units)
        clearance = depth*min(1, max(0, rise/route))
        root = var['root']
        return np.array([root[0], root[1]+rise, root[2]+zy[0]*units*length-clearance])


def fruit_boxes(recipe, size):
    """Per child kind: (front z, back z, height) of the hanging fruit relative to its base, its crown offset and hanging anchor."""
    out = {}
    for kind in MOTHERS:
        V = bounds(kind); var = recipe['variants'][kind]
        out[kind] = dict(front=float(V[:, 2].min())*size, back=float(V[:, 2].max())*size, height=float(V[:, 1].max())*size,
                         half=float(np.abs(V[:, 0]).max())*size, crown=var.get('hang_clearance_height', var['crown_height'])*size,
                         anchor=[v*size for v in var.get('hanging_anchor', [0, 0, 0])])
    return out


_FRONT = {}


def mother_front(mother, x0, x1):
    """The mother's foremost point in the column a fruit falls through (x0..x1), model units."""
    key = (mother, round(x0, 3), round(x1, 3))
    if key not in _FRONT:
        V = bounds(mother); col = V[(V[:, 0] >= x0) & (V[:, 0] <= x1)]
        _FRONT[key] = float(col[:, 2].min()) if len(col) else float(V[:, 2].min())
    return _FRONT[key]


def spur(recipe, count, index):
    """FamilyGeometry.spur at mother scale 1: lateral model units of a socket's side spur (none for a single fruit)."""
    return 0 if count < 2 else (1 if (count-1-index) % 2 == 0 else -1)*recipe['socket_spur']


def fits(stem, fruits, length, layout, mothers, spacing_kinds):
    """Whether fruit at these reference sockets (far to near) hang clear on every mother for every child kind, and
    apart for the spacing kinds; the worst margins."""
    worst = dict(mother=9e9, gap=9e9, ground=9e9)
    pend = stem.s['pendant_length_reference']*stem.s['reference_units_to_model']
    back = max(fruits[k]['back'] for k in spacing_kinds); front = max(-fruits[k]['front'] for k in spacing_kinds)
    for mother in mothers:
        sockets = [stem.point(zy, length, mother) for zy in layout]
        for kind, f in fruits.items():
            for index, sk in enumerate(sockets):  # the pedicel hangs plumb: the anchor sits right under its spur
                base = sk+[spur(stem.r, len(sockets), index)-f['anchor'][0], -pend-f['crown'], -f['anchor'][2]]
                front_z = mother_front(mother, base[0]-f['half'], base[0]+f['half'])
                worst['mother'] = min(worst['mother'], front_z-(base[2]+f['back']))
                worst['ground'] = min(worst['ground'], base[1])
        for a, b in zip(sockets, sockets[1:]):  # far to near: the nearer fruit's front behind the farther one's back
            worst['gap'] = min(worst['gap'], (b[2]-a[2])-back-front)
    ok = worst['mother'] >= FIT['mother_clear'] and worst['gap'] >= FIT['fruit_gap'] and worst['ground'] >= FIT['ground_clear']
    return ok, worst


def on_arc(stem, z_ref):
    """The centerline point whose reference z is z_ref (the arc runs steadily forward)."""
    uu = np.linspace(0, 1, 4001); pts = np.array([stem.at(u) for u in uu])
    i = int(np.argmin(np.abs(pts[:, 0]-z_ref))); return pts[i]


def solve(stem, fruits, count, mothers, spacing_kinds):
    """The shortest stem on which count fruit hang clear: the farthest where Hakkei U hangs it, the rest evenly spaced
    front to back toward the mother."""
    back = max(fruits[k]['back'] for k in spacing_kinds); front = max(-fruits[k]['front'] for k in spacing_kinds)
    far = FIT['far_socket']
    for length in np.arange(FIT['min_length'], 4.0, .01):
        L = length*FIT['variation_min']
        step = (back+front+FIT['fruit_gap']+.05)/(stem.s['reference_units_to_model']*L)  # reference z between sockets
        layout = [on_arc(stem, far[0]+i*step) for i in range(count)]
        if count > 1 and far[0]+(count-1)*step > 0: continue
        ok, worst = fits(stem, fruits, L, layout, mothers, spacing_kinds)
        if ok: return round(float(length), 2), layout, worst
    raise SystemExit(f'no stem fits {count} fruit')


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true'); args = ap.parse_args()
    recipe = json.loads(RECIPE.read_text(encoding='utf-8')); stem_json = json.loads(STEM.read_text(encoding='utf-8'))
    size = json.loads(GROWTH.read_text(encoding='utf-8'))['fruit']['to']
    stem = Stem(recipe, stem_json); fruits = fruit_boxes(recipe, size)
    lengths, layouts = [], {}
    for count in range(1, 6):
        length, layout, worst = solve(stem, fruits, count, MOTHERS, SPACING)
        lengths.append(length); layouts[str(count)] = [[round(float(v), 4) for v in zy] for zy in layout]
        print(count, 'length', length, 'sockets', layouts[str(count)], {k: round(v, 2) for k, v in worst.items()})
    new_recipe = dict(recipe, count_length_scale=lengths)
    text = STEM.read_text(encoding='utf-8')  # keep the component's hand layout: replace only these two entries
    i = text.index('  "socket_layouts": {'); j = text.index('  },', i)+4
    rows = ',\n'.join(f'    "{k}": [' + ', '.join(f'[{z}, {y}]' for z, y in v) + ']' for k, v in layouts.items())
    text = text[:i]+'  "socket_layouts": {\n'+rows+'\n  },'+text[j:]
    i = text.index('  "capacity_scale": {'); j = text.index('},', i)+2
    text = text[:i]+'  "capacity_scale": {'+', '.join(f'"{k+1}": {v}' for k, v in enumerate(lengths))+'},'+text[j:]
    assert json.loads(text)['socket_layouts'] == layouts
    texts = {RECIPE: json.dumps(new_recipe, ensure_ascii=False, indent=2)+'\n', STEM: text}
    for path, text in texts.items():
        if args.check: assert path.read_text(encoding='utf-8') == text, f'stale {path}'
        else: path.write_text(text, encoding='utf-8')
    print('stem fit', 'checked' if args.check else 'written', lengths)


if __name__ == '__main__':
    main()
