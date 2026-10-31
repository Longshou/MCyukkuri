"""Who fits in the set-down aquatic hat, where each sits, and whether it stays upright (offline study; no game).

The riders are the capacity rule of AQUATIC_MARISA_IMPLEMENTATION 帽内空间与载重 (newborn and baby 2, small child up to 0.30
3, child up to 0.45 4, a pregnant owner +2, at most 12; bigger young are no regular riders; from 0.8 of her term the owner
takes the high seat and leaves the cavity before her for the birth). Each layout is solved by day and by night (the owner
leaning over the young: boat_cover_young) with 0, 12 and 24 stored portions (one level layer the riders sit on).

Rules used, all from the runtime unless noted:
- support: a body rests by its real underside on the hat's surface (the stores layer included): the proposed rule;
- room: foot circles (Occupancy.foot = half the body width + 0.02 block) may not overlap where the two bodies' heights
  overlap (PlacedHatEntity.room);
- the owner sits as far toward the stern as she can without rising (her seat), by night in the same seat leaning forward;
- the young sit in the hollow (never on the brim face): by day before her, by night just before her, under her lean;
- braid: from her seat, turning in place, braid_cradle_lift's own scoop envelope reaches the young (morning lift);
- upright: the static heel from the riders' and stores' real masses and centres on the outer hull (scene.hydro) against
  the heel at which the turned-up rim meets the water; steady when it stays within HullAfloat.STEADY (0.6) of it.

    MCY_LOCAL=<main checkout>/.local python layout.py [combo,...]   # writes previews/aquatic-hat-round/layouts.json, prints rows
"""
from pathlib import Path
import json, math, os, sys
import numpy as np

HERE = Path(__file__).resolve().parent
OUT = Path(os.environ.get('MCY_LOCAL', HERE.parents[3]/'.local'))/'previews/aquatic-hat-round'
sys.path.insert(0, str(HERE))
import scene as S

STEADY = .6
NEWBORN, BABY, SMALL, CHILD = .145, .20, .30, .45
KIND = {NEWBORN: ('新生', 2), BABY: ('赤油', 2), SMALL: ('小子油', 3), CHILD: ('子油', 4)}
BELLY = S.smooth(math.sqrt(.8))                  # LiveBirthGeometry.belly at 0.8 of her term, nothing born yet
COMBOS = [
    ('owner_1n', '帽主 + 1 新生', 1., [NEWBORN]),
    ('owner_2n', '帽主 + 2 新生', 1., [NEWBORN]*2),
    ('owner_3n', '帽主 + 3 新生', 1., [NEWBORN]*3),
    ('owner_4n', '帽主 + 4 新生', 1., [NEWBORN]*4),
    ('owner_5n', '帽主 + 5 新生', 1., [NEWBORN]*5),
    ('owner_6n', '帽主 + 6 新生（满 12）', 1., [NEWBORN]*6),
    ('owner_6b', '帽主 + 6 赤油 0.20（满 12）', 1., [BABY]*6),
    ('owner_s', '帽主 + 小子油 0.30', 1., [SMALL]),
    ('owner_c', '帽主 + 子油 0.45', 1., [CHILD]),
    ('owner_c2n', '帽主 + 子油 0.45 + 2 新生', 1., [CHILD, NEWBORN, NEWBORN]),
    ('owner_cs2n', '帽主 + 子油 + 小子油 + 2 新生（11）', 1., [CHILD, SMALL, NEWBORN, NEWBORN]),
    ('big_6n', '1.2 倍帽主 + 6 新生', 1.2, [NEWBORN]*6),
    ('big_c', '1.2 倍帽主 + 子油 0.45', 1.2, [CHILD]),
    ('pregnant', '临产帽主（孕程 0.8）坐高座', 1., []),
    ('pregnant_2n', '临产帽主 + 2 新生', 1., [NEWBORN]*2),
]
STORES = (0, 12, 24)
RULES = ('circle', 'body')   # room by foot circles (runtime), or by the bodies' own columns (proposed)
GAP = .03
PORTION_MASS = math.pi/6*(2*.09)**3*S.DENSITY      # HullLarder.MASS


def shapes(scale, day, belly=0., young=False):
    clip, t, face = (('boat_drift', 1.2, 'closed_smile') if day else ('boat_nest_curl', .5, 'sleep')) if young else \
        (('boat_drift', .3, 'closed_smile') if day else ('boat_cover_young', .9, 'sleep'))
    tris, mask = S.BODY.mesh(clip, t, face=face, hat=S.WORN if young else None, belly=belly)
    return tris, mask


_cache = {}


def shape(scale, day, belly=0., young=False, yaw=0.):
    key = (scale, day, belly, young, yaw)
    if key not in _cache:
        tris, mask = shapes(scale, day, belly, young); _cache[key] = S.Shape(tris, mask, yaw, scale)
    return _cache[key]


def seats(field, sh):
    """The owner's seats from the middle toward the stern (+z), as long as she does not rise above her middle seat."""
    base = sh.rest(field, 0, 0)
    if base is None: return []
    out = [(0., 0., base)]
    for e in np.arange(.5, 5.01, .5):
        y = sh.rest(field, 0, e)
        if y is None or y > base+.08: break
        out.append((0., float(e), y))
    return out


def solve(combo, period, stores, rule='circle'):
    """The owner sits in the middle, or the least way toward the stern that lets every young fit."""
    key, title, oscale, young = combo
    hull = HULL; day = period == 'day'
    level = hull.stores(stores); field = hull.surface(level)
    pregnant = key.startswith('pregnant')
    osh = shape(oscale, day, BELLY if pregnant else 0.)
    options = [(0., 0., osh.rest(field, 0, 0))] if pregnant else seats(field, osh)
    best = None
    for at in options:
        r = place_all(combo, period, stores, at, rule)
        if r['fits'] or best is None: best = r
        if r['fits']: break
    return best


def place_all(combo, period, stores, at, rule='circle'):
    key, title, oscale, young = combo
    hull = HULL; day = period == 'day'
    level = hull.stores(stores); field = hull.surface(level)
    pregnant = key.startswith('pregnant')
    belly = BELLY if pregnant else 0.
    osh = shape(oscale, day, belly)
    out = dict(combo=key, title=title, period=period, stores=stores, rule=rule, level=None if level is None else round(-level, 2), placed=[])
    if at is None or at[2] is None:
        out.update(fits=False, why='owner_unsupported'); return out
    ox, oz, oy = at
    o_bottom = oy+osh.bottom
    o_under = osh.full('under', ox, oz, oy)
    o_low = osh.full('low', ox, oz, oy)
    discs = [(ox, oz, S.foot(oscale, belly), o_bottom, o_bottom+osh.tall, 'owner')]
    columns = [(o_low, osh.full('btop', ox, oz, oy))]   # each placed body's own column (body cloth: no hat, hair or braid)
    rows = np.isfinite(o_low) & (np.abs(S.GRID.x-ox) < 3)
    z_front = float(S.GRID.z[rows].min())
    out['owner'] = dict(x=ox, z=oz, bottom=round(-o_bottom, 2), foot=round(S.foot(oscale, belly), 2), front=round(z_front, 2))
    out['_draw'] = [(oscale, day, belly, False, 0., ox, oz, oy, S.foot(oscale, belly))]
    loads = [(S.mass(oscale, belly)+(3*S.mass(NEWBORN) if pregnant else 0), ox+osh.cg[0], oy+osh.cg[1], oz+osh.cg[2])]
    if level is not None:
        loads.append((stores*PORTION_MASS, 0., (level+float(hull.field[hull.cavity].min()))/2, 0.))
    fits = True
    for i, ys in enumerate(sorted(young, reverse=True)):
        name, load = KIND[ys]
        side = (-1 if i % 2 else 1)*(1+i//2)
        if day: prefer = (ox+2.6*side, z_front-.5-S.foot(ys))
        else: prefer = (ox+2.2*side, z_front-.2-S.foot(ys)*.5)
        best = None
        for yaw in ((-20, 20) if day else (-35, 35)):
            sh = shape(ys, day, young=True, yaw=yaw)
            fy = S.foot(ys)
            for x in np.arange(-11, 11.01, .5):
                for z in np.arange(-11, 11.01, .5):
                    y = sh.rest(field, x, z)
                    if y is None: continue
                    si, sj, _ = sh.window(x, z)
                    if not hull.cavity[si, sj][np.isfinite(sh.low)].all(): continue  # in the hollow
                    bottom, topy = y+sh.bottom, y+sh.bottom+sh.tall
                    clash = False
                    if rule == 'circle':
                        for dx, dz, r, b0, b1, who in discs:
                            if math.hypot(x-dx, z-dz) < fy+r-GAP and bottom < b1 and topy > b0: clash = True; break
                    else:
                        lo, hi = sh.low+y, sh.btop+y
                        for cu, ct in columns:
                            cu_w, ct_w = cu[si, sj], ct[si, sj]
                            both = np.isfinite(lo) & np.isfinite(cu_w)
                            if (both & (lo < ct_w+GAP) & (hi > cu_w-GAP)).any(): clash = True; break
                    if clash: continue
                    score = math.hypot(x-prefer[0], z-prefer[1])+.4*(bottom-FLOOR)
                    if best is None or score < best[0]: best = (score, x, z, y, sh, yaw)
        if best is None:
            fits = False; out['placed'].append(dict(kind=name, scale=ys, load=load, placed=False)); continue
        _, x, z, y, sh, yaw = best
        bottom = y+sh.bottom
        out['_draw'].append((ys, day, 0., True, yaw, x, z, y, S.foot(ys)))
        top = sh.full('top', x, z, y); over = np.isfinite(o_under) & np.isfinite(top)
        covered = bool(over.any()); clearance = float((o_under-top)[over].min()) if covered else None
        centre = np.array([x, bottom+sh.height/2, z])
        reaches, dist = S.braid_reaches((ox, oz), oscale, o_bottom, centre) if not pregnant else (None, None)
        gaps = [math.hypot(x-dx, z-dz)-(S.foot(ys)+r) for dx, dz, r, b0, b1, who in discs if bottom < b1 and bottom+sh.tall > b0]
        discs.append((x, z, S.foot(ys), bottom, bottom+sh.tall, name))
        columns.append((sh.full('low', x, z, y), sh.full('btop', x, z, y)))
        loads.append((S.mass(ys), x+sh.cg[0], y+sh.cg[1], z+sh.cg[2]))
        out['placed'].append(dict(kind=name, scale=ys, load=load, placed=True, x=round(x, 2), z=round(z, 2), yaw=yaw,
                                  bottom=round(-bottom, 2), gap=round(min(gaps), 2) if gaps else None,
                                  braid=reaches, braid_dist=None if dist is None else round(dist, 2),
                                  covered=covered, clearance=None if clearance is None else round(clearance, 2)))
    h = S.hydro(hull, loads)
    direction = math.atan2(math.tan(math.radians(h['pitch'])), math.tan(math.radians(h['roll']))) if h['heel'] > .05 else 0.
    flood = S.flood_angle(hull, loads, direction)
    load = sum(p['load'] for p in out['placed'])+(2 if pregnant else 0)+.5*stores
    out.update(fits=fits, load=load, hydro=h, flood=flood, ratio=round(h['heel']/max(flood, 1e-6), 3),
               upright=not h['floods'] and h['heel'] < STEADY*flood)
    if pregnant:
        out['owner']['high'] = round(-o_bottom, 2)
    return out


HULL = S.Hull()
FLOOR = float(HULL.field[HULL.cavity].min())   # the hollow's lowest point (world y)


def climb(scale=1., share=1.):
    """A body hanging on the rim from the water, {@code share} of its weight on the brim's edge (the rest still borne by
    the water it is half in): the heel and the least freeboard."""
    loads = [(S.mass(scale)*share, 0., S.B.BRIM['droop']*S.K, -S.B.BRIM['radius']*S.K)]
    h = S.hydro(HULL, loads)
    return dict(scale=scale, share=share, heel=h['heel'], freeboard=h['freeboard'], floods=bool(h['floods']))


def main():
    results = []
    only = sys.argv[1].split(',') if len(sys.argv) > 1 else None
    for rule in RULES:
        for combo in COMBOS:
            if only and combo[0] not in only: continue
            for period in ('day', 'night'):
                for stores in STORES:
                    r = solve(combo, period, stores, rule); results.append(r)
    extra = dict(climb=[climb(s, f) for s in (1., CHILD) for f in (.25, .5, .75, 1.)], braid_reach=round(S.REACH, 2),
                 braid_low=round(S.REACH_LOW, 2), geometry=json.loads((HERE/'geometry.json').read_text(encoding='utf-8')))
    OUT.mkdir(parents=True, exist_ok=True)
    clean = [{k: v for k, v in r.items() if not k.startswith('_')} for r in results]
    (OUT/'layouts.json').write_text(json.dumps(dict(results=clean, extra=extra), ensure_ascii=False, indent=1, default=lambda o: bool(o) if isinstance(o, np.bool_) else float(o)), encoding='utf-8')
    for r in results:
        young = ' '.join(f"{p['kind']}{'@'+str(p['bottom']) if p['placed'] else '✗'}{'' if p.get('braid') in (None, True) else '(辫✗)'}" for p in r['placed'])
        print(f"{r['rule']:6s} {r['combo']:12s} {r['period']:5s} s{r['stores']:2d} fits={r['fits']} owner={r['owner'].get('bottom') if 'owner' in r else '-'} "
              f"heel={r['hydro']['heel']}/{r['flood']} fb={r['hydro']['freeboard']} upright={r['upright']} {young}")
    print(extra)


if __name__ == '__main__':
    main()
