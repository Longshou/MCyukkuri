"""Runtime export of the aquatic Marisa's hat: the worn mesh (also the item's), the set-down lily-pad hull, the stores floor
texture and the hull's bounds contract (README: 帽船边界), with the art/source/aquatic-basin-hat snapshot (recipe, texture,
fingerprints of inputs and outputs).

The runtime hull is the bounds contract alone: the old oval and turned-land-hat hull profiles (hull_profiles/*.json) are gone,
and every seat, support and stores read uses the bounds.

    python export.py [--set key.path=value ...]   # writes the runtime files and the snapshot; with --set prints the
                                                  # bounds of the changed recipe and writes nothing
    python export.py --check                      # compares without writing
"""
from pathlib import Path
import argparse, hashlib, json, re, sys
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build as B

ROOT, ASSETS = B.ROOT, B.ASSETS
TEXTURE = 'mcyukkuri:textures/entity/marisa/neutral.png'
WORN_ID, HULL_ID = 'aquatic_basin_hat', 'aquatic_basin_hat_turned'
STORES_PNG = ASSETS/'textures/props/aquatic_basin_hat_stores.png'
INDEX = ASSETS/'yukkuri_props/subspecies-index.json'
BOUNDS = ASSETS/f'yukkuri_presentation/hull_bounds/{HULL_ID}.json'
SNAPSHOT = ROOT/'art/source/aquatic-basin-hat'


def sha(data): return hashlib.sha256(data).hexdigest()


def pretty(data):
    """Indented JSON with lists of plain values kept on one line."""
    flat = lambda m: re.sub(r'\s*\n\s*', ' ', m.group(0)).replace('[ ', '[').replace(' ]', ']')
    text = json.dumps(data, ensure_ascii=False, indent=1)
    for _ in range(2): text = re.sub(r'\[(?:[^\[\]{}]|\[[^\[\]{}\n]*\])*\]', flat, text)   # innermost lists, then lists of them
    return text+'\n'


def rel(p): return p.relative_to(ROOT).as_posix()


def prop(parts):
    """Prop JSON (format 1): parts is [(name, triangles)]; vertices x y z u v nx ny nz, hat units."""
    verts, entries, start = [], [], 0
    for name, tris in parts:
        flat = np.round(tris.reshape(-1, 8), 5)
        entries.append(dict(name=name, start=start, count=len(flat), texture=TEXTURE)); start += len(flat)
        verts.append(flat)
    v = np.concatenate(verts)
    bounds = [[round(float(v[:, k].min()), 5), round(float(v[:, k].max()), 5)] for k in range(3)]
    return json.dumps(dict(format=1, units_per_block=16, vertices=[float(x)+0. for x in v.reshape(-1)], parts=entries,
                           bounds=bounds), separators=(',', ':'))+'\n'


def hull_bounds(seats):
    """The bounds contract: plain sizes the game can place and draw by, instead of hydrostatics (blocks, hull frame)."""
    k = B.K/16; f = lambda v: round(float(v)*k, 4)
    return dict(
        version=1, hull=HULL_ID, scale=B.K,
        units='blocks at the drawn size (hat units x scale / 16); hull frame: origin the middle of the brim face, +Y out '
              'of the hollow, +X the worn front, the boat\'s bow toward -Z; round (radii from the middle)',
        waterline_y=f(-B.WATER),
        mouth=dict(radius=f(B.IN['opening'])),
        floor=dict(radius=f(B.IN['floor_radius']), y=f(-B.IN['floor_depth'])),
        brim=dict(level_radius=f(B.BRIM['droop_from']), outer_radius=f(B.BRIM['radius']),
                  y=0., edge_y=f(-(B.WATER+B.DIP))),
        keel_y=f(-B.depth_of_tier(B.tiers()[1][-1])),
        **seats,
        stores=B.R['stores'],
        note='waterline_y: the fixed presentation waterline (the hull rides there, the basin dry); floor: the level floor '
             '(a shallow dimple in the middle); brim: level from the mouth to level_radius at y, then falling to its '
             'edge at outer_radius with its top at edge_y; keel_y: the hull\'s lowest point. captain_seat: an adult '
             '(growth 1) underside at the stern wall facing the bow. brim_ring: where a sitting 子油 has its middle, braid '
             'side out, and its underside height by growth. stores: reused texture and coverage rule in stores.note.')


def gate_profile(bounds):
    """This hull for the lore hull gate (aquatic_tools.hull_forms, in memory): its cut, the fixed waterline and the
    captain's stern seat (blocks)."""
    s = bounds['captain_seat']
    return dict(name=HULL_ID, section=(B.section()/16).round(6).tolist(), waterline_y=round(-B.WATER*B.K/16, 6),
                seat=[s['x'], s['y'], s['z']])


def outputs():
    import layout as L
    stores = (HERE/'food_layer.png').read_bytes()
    bounds = hull_bounds(L.bounds())
    texts = {ASSETS/f'yukkuri_props/{WORN_ID}.json': prop(B.worn_parts()).encode(),
             ASSETS/f'yukkuri_props/{HULL_ID}.json': prop(B.placed_parts()).encode(), STORES_PNG: stores,
             BOUNDS: pretty(bounds).encode()}
    index = json.loads(INDEX.read_text(encoding='utf-8'))
    for name in (WORN_ID, HULL_ID): index.setdefault(name, {})
    texts[INDEX] = json.dumps(index, separators=(',', ':')).encode()
    texts[SNAPSHOT/'recipe.json'] = (HERE/'recipe.json').read_bytes()
    texts[SNAPSHOT/'aquatic_basin_hat_stores.png'] = stores
    seat_inputs = [ASSETS/n for n in ('yukkuri/marisa.json', 'yukkuri_presentation/motion_corrections.json',
                                      'yukkuri_presentation/placement_fit.json', 'yukkuri_presentation/braid_rescue.json')]
    manifest = dict(note='Runtime outputs of interactions/aquatic-marisa-hat/export.py', status=B.R['status'],
                    inputs={rel(p): sha(p.read_bytes()) for p in [HERE/n for n in ('recipe.json', 'food_layer.png', 'build.py', 'scene.py', 'layout.py', 'export.py')]+seat_inputs},
                    outputs={rel(p): sha(d) for p, d in texts.items()})
    texts[SNAPSHOT/'manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2)+'\n').encode()
    registration = ROOT/'art/runtime-sources.json'; text = registration.read_text(encoding='utf-8')
    scope = json.loads(text)[WORN_ID]['scope']
    before, stores_scope = scope.split(' The stores texture ', 1)
    updated = before+' The stores texture '+B.R['stores']['note']+' Project design; visual review remains pending.'
    texts[registration] = text.replace(json.dumps(scope, ensure_ascii=False), json.dumps(updated, ensure_ascii=False)).encode()
    return texts, bounds


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true')
    ap.add_argument('--set', action='append', metavar='KEY=VALUE', help='recipe change in memory (prints, writes nothing)')
    args = ap.parse_args()
    if args.set:
        with B.override(B.parse_sets(args.set)):
            import layout as L
            print(pretty(hull_bounds(L.bounds())), end=''); return 0
    if B.check_inside(): raise SystemExit(f'basin outside the hull: {B.check_inside()}')
    texts, bounds = outputs(); stale = []
    for path, data in texts.items():
        if args.check:
            have = path.read_bytes() if path.is_file() else None
            if have is not None and path.suffix == '.json': have = have.replace(b'\r\n', b'\n')
            if have != data: stale.append(rel(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    print(HULL_ID, 'waterline', bounds['waterline_y'], 'seat', bounds['captain_seat'], 'ring', bounds['brim_ring'])
    if stale: print('stale:', *stale, sep='\n  '); return 1
    print('checked' if args.check else 'wrote', len(texts), 'files'); return 0


if __name__ == '__main__':
    sys.exit(main())
