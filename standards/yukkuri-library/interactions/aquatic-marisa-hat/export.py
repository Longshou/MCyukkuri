"""Runtime export of the aquatic Marisa's second hat form (cone tiers outside, round basin inside) and the hull profiles.

Writes the worn mesh (also the item's), the set-down hull, the stores-layer texture and their subspecies-index rows; one
hull profile per aquatic form in the same schema (README: 帽船剖面), each measured on the triangles the game draws:
this make-source's hull for the new form, and the current runtime hull (marisa_hat_turned at hat_vessel.json's aquatic
scale, pressed by the squash PlacedHatRenderer applies) for the current form, whose own assets are only read. The
art/source/aquatic-basin-hat snapshot keeps the recipe, the texture and the fingerprints of inputs and outputs.

    python export.py           # writes the runtime files, the profiles and the snapshot
    python export.py --check   # compares without writing
"""
from pathlib import Path
import argparse, hashlib, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build as B
import scene as S
import layout as L

ROOT, ASSETS = B.ROOT, B.ASSETS
TEXTURE = 'mcyukkuri:textures/entity/marisa/neutral.png'
WORN_ID, HULL_ID, OLD_HULL_ID = 'aquatic_basin_hat', 'aquatic_basin_hat_turned', 'marisa_hat_turned'
STORES_PNG = ASSETS/'textures/props/aquatic_basin_hat_stores.png'
INDEX = ASSETS/'yukkuri_props/subspecies-index.json'
PROFILES = ASSETS/'yukkuri_presentation/hull_profiles'
VESSEL_PATH = HERE.parent/'marisa-hat-vessel/geometry.json'
SNAPSHOT = ROOT/'art/source/aquatic-basin-hat'
AZIMUTHS = list(range(0, 360, 45))   # degrees in the hull frame, from +X toward +Z
STEP = .25                           # radial sample spacing, adult model units (1/64 block)
TOL = .25                            # a floor or keel is level within this (model units)
FILLS = range(1, 25)                 # stored portions with a layer level


def sha(data): return hashlib.sha256(data).hexdigest()


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


# ------------------------------------------------------------------ hull profile (one schema for every form)
def heights(tris, x, z):
    """Exact surfaces over the points (x, z): the highest up-facing (top) and lowest down-facing (bottom); nan where none."""
    p = tris[:, :, :3]; ny = tris[:, 0, 6]
    (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = (p[:, k].T for k in range(3))
    det = (z1-z2)*(x0-x2)+(x2-x1)*(z0-z2); ok = np.abs(det) > 1e-12
    det = np.where(ok, det, 1.)
    X, Z = np.asarray(x, float)[:, None], np.asarray(z, float)[:, None]
    a = ((z1-z2)*(X-x2)+(x2-x1)*(Z-z2))/det; b = ((z2-z0)*(X-x2)+(x0-x2)*(Z-z2))/det; g = 1-a-b
    inside = ok & (a >= -1e-9) & (b >= -1e-9) & (g >= -1e-9)
    y = a*y0+b*y1+g*y2
    top = np.where(inside & (ny > 1e-4), y, -np.inf).max(1); bottom = np.where(inside & (ny < -1e-4), y, np.inf).min(1)
    return np.where(np.isfinite(top), top, np.nan), np.where(np.isfinite(bottom), bottom, np.nan)


def edge(tris, az, test, r0, r1):
    """Bisect the radius between r0 (test true) and r1 (false) along the azimuth."""
    c, s = math.cos(math.radians(az)), math.sin(math.radians(az))
    for _ in range(40):
        m = (r0+r1)/2; top, bottom = heights(tris, [m*c], [m*s])
        if test(top[0], bottom[0]): r0 = m
        else: r1 = m
    return (r0+r1)/2


def ray(tris, az, test, rs):
    """The outermost radius from the middle out to which test holds without a break, refined by bisection."""
    c, s = math.cos(math.radians(az)), math.sin(math.radians(az))
    top, bottom = heights(tris, rs*c, rs*s)
    ok = [test(t, b) for t, b in zip(top, bottom)]
    n = ok.index(False) if False in ok else len(rs)
    if n == 0: return 0.
    return edge(tris, az, test, rs[n-1], rs[n]) if n < len(rs) else float(rs[-1])


def edge_inside(inside, c, s, reach):
    """Where the ray from the middle leaves the opening."""
    r0, r1 = 0., reach
    for _ in range(40):
        m = (r0+r1)/2
        if inside(np.array(m*c), np.array(m*s)): r0 = m
        else: r1 = m
    return (r0+r1)/2


def outermost(tris, az, test, rs):
    """The outermost radius along the azimuth at which test holds (not only from the middle), refined; 0 for none."""
    c, s = math.cos(math.radians(az)), math.sin(math.radians(az))
    top, bottom = heights(tris, rs*c, rs*s)
    hit = [i for i, (t, b) in enumerate(zip(top, bottom)) if test(t, b)]
    if not hit: return 0.
    n = hit[-1]
    return edge(tris, az, test, rs[n], rs[n+1]) if n+1 < len(rs) else float(rs[n])


def profile(form, hull_id, tris, scale, squash, inside, sources, note):
    """tris: the set-down hull as drawn (adult model units: hat units x scale, y also x squash), origin at the brim face;
    inside(x, z): within its opening."""
    blk = lambda v: round(float(v)/16, 4)
    reach = float(np.hypot(tris[:, :, 0], tris[:, :, 2]).max())+1
    rs = np.arange(0, reach+STEP, STEP)
    rows = dict(brim=[], opening=[], floor=[], keel=[], inner=[], outer=[]); crest, level, weight = [], 0., 0.
    # Every projected mesh corner is a ray: chords retain rectangular steps and the authored brim exactly.
    azimuths = sorted(set(AZIMUTHS + [round(math.degrees(math.atan2(z, x)) % 360, 5)
                                    for x, _, z in tris[:, :, :3].reshape(-1, 3) if x or z]))
    for az in azimuths:   # the opening, the brim's edge and the lowest crest water must pass to flood the hollow
        c, s = math.cos(math.radians(az)), math.sin(math.radians(az))
        rows['opening'].append(edge_inside(inside, c, s, reach))
        rows['brim'].append(ray(tris, az, lambda t, b: not (math.isnan(t) and math.isnan(b)), rs))
        out_r = np.r_[rs[(rs > rows['opening'][-1]) & (rs < rows['brim'][-1])], rows['brim'][-1]-1e-4]
        top, _ = heights(tris, out_r*c, out_r*s); crest.append(float(np.nanmax(top)))
    dam = min(crest)
    hull = S.Hull(tris, dam, inside)
    keel = float(np.nanmin(np.where(np.isfinite(hull.low), hull.low, np.nan)))
    hollow = float(np.nanmin(np.where(hull.cavity, hull.field, np.nan)))
    for az, opening, brim in zip(azimuths, rows['opening'], rows['brim']):
        c, s = math.cos(math.radians(az)), math.sin(math.radians(az))
        floor = ray(tris, az, lambda t, b: t <= hollow+TOL, rs)
        keel_r = outermost(tris, az, lambda t, b: b <= keel+TOL, rs)
        inner_r = np.r_[rs[rs < opening-1e-6], opening-1e-4]
        top, _ = heights(tris, inner_r*c, inner_r*s)
        inner = [[blk(r), blk(y)] for r, y in zip(inner_r, top)]+[[blk(opening), 0.]]
        on = inner_r < floor; level += float((top[on]*inner_r[on]).sum()); weight += float(inner_r[on].sum())
        outer_r = np.r_[rs[rs < brim-1e-6], brim-1e-4]
        top, bottom = heights(tris, outer_r*c, outer_r*s)
        dry = np.where(outer_r < opening, dam, top)
        outer = [[blk(r), blk(b), blk(t)] for r, b, t in zip(outer_r, bottom, dry)]
        for k, v in (('floor', floor), ('keel', keel_r), ('inner', inner), ('outer', outer)): rows[k].append(v)
    for k in ('brim', 'opening', 'floor', 'keel'): rows[k] = [round(float(v)/16, 6) for v in rows[k]]
    # the empty hull upright on calm water: its cloth's weight (hat_vessel.json afloat.hull_mass per scale²) displaced
    mass = S.VESSEL['afloat']['hull_mass']*scale*scale; cell = (S.GRID.step/16)**2
    ok = np.isfinite(hull.low) & np.isfinite(hull.top); bot, top = hull.low[ok]/16, hull.top[ok]/16
    lo, hi = float(bot.min()), float(top.max())
    for _ in range(60):
        w = (lo+hi)/2
        if np.clip(np.minimum(w, top)-bot, 0, None).sum()*cell < mass: lo = w
        else: hi = w
    owner = L.shape(1., True)
    portion = B.portion_volume()
    return dict(
        version=1, form=form, hull=hull_id, note=note,
        units='blocks; hull frame: origin at the middle of the brim face (the face that sat on the head), +Y out of the '
              'hollow, +X the worn front (where the bow is), the boat\'s bow toward -Z; as the game draws the hull at '
              'scale, the mesh\'s y pressed by squash',
        scale=scale, squash=squash, azimuths=azimuths, sample_step=blk(STEP), level_tolerance=blk(TOL),
        surface_triangles=np.round(tris[tris[:, 0, 6] > 1e-4, :, :3] / 16, 6).tolist(),
        rim=dict(y=blk(dam), note='the lowest crest round the opening: water over it floods the hollow'),
        brim=dict(radius=rows['brim']), opening=dict(radius=rows['opening']),
        floor=dict(y=blk(level/weight), radius=rows['floor']), hollow=dict(y=blk(hollow)),
        keel=dict(y=blk(keel), radius=rows['keel']),
        inner=rows['inner'], outer=rows['outer'],
        volume=dict(hollow=round(float(np.clip(-hull.field[hull.cavity], 0, None).sum())*cell/16, 6),
                    to_rim=round(float(np.clip(dam/16-bot, 0, None).clip(max=top-bot).sum())*cell, 6),
                    hull_mass=round(mass, 6), empty_waterline_y=round((lo+hi)/2, 4)),
        anchors=dict(owner_y=blk(owner.rest(hull.field, 0, 0)+owner.bottom),
                     stores=dict(portion_volume=round(portion/4096, 6),
                                 level_y={str(n): blk(hull.stores(n)) for n in FILLS})),
        sources=sources)


def old_hull():
    """The current aquatic hull as drawn (marisa_hat_turned at the aquatic scale, pressed by HatGeometry.squash()) and its
    opening (hat_vessel.json opening: x0 x1 z0 z1)."""
    vessel = json.loads(VESSEL_PATH.read_text(encoding='utf-8'))
    m = json.loads((ASSETS/f'yukkuri_props/{OLD_HULL_ID}.json').read_text(encoding='utf-8'))
    t = np.array(m['vertices'], float).reshape(-1, 3, 8)
    scale, squash = vessel['scale']['aquatic_marisa'], vessel['squash']['nest']
    t[:, :, 1] *= squash; t[:, :, :3] *= scale
    n = np.cross(t[:, 1, :3]-t[:, 0, :3], t[:, 2, :3]-t[:, 0, :3]); n /= np.maximum(1e-12, np.linalg.norm(n, axis=1))[:, None]
    flip = np.einsum('ij,ij->i', n, t[:, 0, 5:8]) < 0; n[flip] *= -1   # keep the authored facing, renormalised after the squash
    t[:, :, 5:8] = n[:, None, :]
    return t, scale, squash, [v*scale for v in vessel['opening']]


def rel(p): return p.relative_to(ROOT).as_posix()


def outputs():
    worn, hull = prop(B.worn_parts()), prop(B.placed_parts())
    stores = (HERE/'food_layer.png').read_bytes()
    texts = {ASSETS/f'yukkuri_props/{WORN_ID}.json': worn.encode(), ASSETS/f'yukkuri_props/{HULL_ID}.json': hull.encode(),
             STORES_PNG: stores}
    index = json.loads(INDEX.read_text(encoding='utf-8'))
    for name in (WORN_ID, HULL_ID): index.setdefault(name, {})
    texts[INDEX] = json.dumps(index, separators=(',', ':')).encode()
    model = ASSETS/'yukkuri/marisa.json'
    # the owner anchor rests the posed runtime Marisa on each hull: her model, clips and placement fit
    body = {rel(p): sha(p.read_bytes()) for p in (model, ASSETS/'yukkuri_presentation/motion_corrections.json',
                                                  ASSETS/'yukkuri_presentation/placement_fit.json')}
    new = profile('cone_basin', HULL_ID, B.world(B.placed_mesh()), B.K, 1., lambda x, z: np.hypot(x, z) < B.IN['opening']*B.K,
                  {rel(HERE/'recipe.json'): sha((HERE/'recipe.json').read_bytes()),
                   rel(ASSETS/f'yukkuri_props/{HULL_ID}.json'): sha(texts[ASSETS/f'yukkuri_props/{HULL_ID}.json']), **body},
                  'Second aquatic form: the land hat\'s cone tiers and bent tip outside, the lower tiers pressed into a keel; '
                  'a round basin inside (this make-source). Mesh already pressed: draw with squash 1.')
    old_tris, scale, squash, (x0, x1, z0, z1) = old_hull()
    old_prop = ASSETS/f'yukkuri_props/{OLD_HULL_ID}.json'
    old = profile('turned_land_hat', OLD_HULL_ID, old_tris, scale, squash, lambda x, z: (x0 < x) & (x < x1) & (z0 < z) & (z < z1),
                  {rel(old_prop): sha(old_prop.read_bytes()), rel(VESSEL_PATH): sha(VESSEL_PATH.read_bytes()), **body},
                  'Current aquatic form: the land hat turned over (interactions/marisa-hat-vessel), drawn at the aquatic '
                  'scale with the crown pressed by HatGeometry.squash(); flat brim, stepped rectangular well.')
    for p in (new, old):
        texts[PROFILES/f"{p['hull']}.json"] = (json.dumps(p, ensure_ascii=False, separators=(',', ':'))+'\n').encode()
    texts[SNAPSHOT/'recipe.json'] = (HERE/'recipe.json').read_bytes()
    texts[SNAPSHOT/'aquatic_basin_hat_stores.png'] = stores
    manifest = dict(note='Runtime outputs of interactions/aquatic-marisa-hat/export.py', status=B.R['status'],
                    inputs={**{rel(p): sha(p.read_bytes()) for p in (HERE/'recipe.json', HERE/'build.py', HERE/'scene.py',
                                                                      HERE/'export.py', old_prop, VESSEL_PATH)}, **body},
                    outputs={rel(p): sha(d) for p, d in texts.items()})
    texts[SNAPSHOT/'manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2)+'\n').encode()
    return texts, new, old


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true'); args = ap.parse_args()
    if B.check_inside(): raise SystemExit(f'basin outside the hull: {B.check_inside()}')
    texts, new, old = outputs(); stale = []
    for path, data in texts.items():
        if args.check:
            have = path.read_bytes() if path.is_file() else None
            if have is not None and path.suffix == '.json': have = have.replace(b'\r\n', b'\n')
            if have != data: stale.append(rel(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    for p in (new, old):
        print(p['hull'], 'brim', p['brim']['radius'][0], 'opening', p['opening']['radius'][0], 'floor', p['floor']['y'],
              'keel', p['keel']['y'], 'owner', p['anchors']['owner_y'], 'waterline', p['volume']['empty_waterline_y'])
    if stale: print('stale:', *stale, sep='\n  '); return 1
    print('checked' if args.check else 'wrote', len(texts), 'files'); return 0


if __name__ == '__main__':
    sys.exit(main())
