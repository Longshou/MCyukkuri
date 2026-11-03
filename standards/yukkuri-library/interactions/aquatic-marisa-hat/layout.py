"""Where the riders of the lily-pad hat boat sit and what they reach (offline study; no game).

Rules (user 2026-11-01/11-04, docs/FEATURES.md 水生魔理沙): the captain sits at the stern wall facing the bow; before her in the
basin the young that fit with their worn hats clear of her face (cabin_hats: measured, project design); others go on her bare
head (head_cap) or the brim edge, where a young splashes the water with its braid. Every body is the runtime Marisa posed by an exported clip at its growth (scene.Shape) and rests by its
real underside; two bodies may not share a cell where their bodies' own columns overlap (hats, hair and braid are soft).

    python layout.py [--set key.path=value ...]   # prints the seat, capacity, reach and clip gate numbers

bounds() is the seat part of the runtime bounds contract (export.py writes it); checks() the numbers above.
"""
from pathlib import Path
import argparse, importlib.util, json, math, sys, time
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import scene as S
B = S.B

GAP = .03                 # model units between two bodies' columns
BABY, SMALL, CHILD = .20, .30, .45
BASIN_YOUNG = .38         # rest-pose bangs and braid tuft clear the mouth lip (aq-clips-b)
CHAIN = S.BRAID['braid_chain_length']                  # the braid's joints end to end (adult model units)
ROOT_AT = np.array(S.BRAID['braid_root'], float)       # its root on the body (adult rest pose)
MOUTH_AT = np.array(S.BRAID['mouth_root'], float)
YAW = 15.                                              # the paddle's captain_yaw (aquatic_tools SHARED)
LEAN = 10.                                             # degrees: a whole-body lean toward the water (limit about 12)
GATE_CLIPS = ('boat_rim_peek', 'boat_paddle', 'braid_rescue_reach', 'braid_rescue_hold', 'braid_rescue_settle',
              'braid_cradle_lift', 'hat_brim_offer', 'boat_nudge_young', 'boat_board', 'hat_hop_up', 'boat_thrown',
              'boat_feed_young', 'boat_drift', 'boat_steady', 'braid_head_set', 'braid_head_take')


def captain_shape(scale=1., yaw=0.): return S.Shape.posed('boat_drift', .3, 'closed_smile', scale, yaw)
def young_shape(scale, yaw=0.): return S.Shape.posed('boat_drift', 1.2, 'closed_smile', scale, yaw, hat=True)


def captain(hull, scale=1.):
    """Her seat (x, z, lift): from the middle toward the stern (+z) as far as she goes without rising off the floor, her
    hair clear of the hull, turned within the paddle's captain_yaw."""
    sh = captain_shape(scale); base = sh.rest(hull.field, 0, 0); seat = (0., 0., base)
    turns = [captain_shape(scale, yaw) for yaw in (-YAW, YAW)]
    for z in np.arange(.25, 8.01, .25):
        y = sh.rest(hull.field, 0, z)
        if y is None or y > base+.08 or not all(clear(t, hull.field, 0, z, y) for t in [sh]+turns): break
        seat = (0., float(z), y)
    return sh, seat


def clear(sh, field, x, z, y):
    """The firm underside (body and hair; the braid drapes) stays on or over the hull's top."""
    si, sj, _ = sh.window(x, z); f = field[si, sj]; u = sh.firm+y; both = np.isfinite(f) & np.isfinite(u)
    return not (both & (u < f-.05)).any()


def column(sh, x, z, y): return sh.full('low', x, z, y), sh.full('btop', x, z, y)


def free(sh, x, z, y, columns):
    si, sj, _ = sh.window(x, z); lo, hi = sh.low+y, sh.btop+y
    for cu, ct in columns:
        cw, tw = cu[si, sj], ct[si, sj]; both = np.isfinite(lo) & np.isfinite(cw)
        if (both & (lo < tw+GAP) & (hi > cw-GAP)).any(): return False
    return True


def seat_young(hull, scales, cap, ahead=0.):
    """Young in the basin before the captain, largest first, each at the free spot nearest her front (`ahead` further
    toward the bow) and lowest:
    [(shape, x, z, lift)], or None when one does not fit."""
    osh, (ox, oz, oy) = cap; columns = [column(osh, ox, oz, oy)]
    front = oz+float(S.GRID.z[np.isfinite(osh.full('low', ox, oz, oy))].min())
    floor = float(hull.field[hull.cavity].min()); out = []
    for i, s in enumerate(sorted(scales, reverse=True)):
        side = (-1 if i % 2 else 1)*(i > 0 or len(scales) > 1); best = None
        for yaw in (-20., 0., 20.):
            sh = young_shape(s, yaw)
            for x in np.arange(-9, 9.01, .5):
                for z in np.arange(-9, oz+.01, .5):
                    y = sh.rest(hull.field, x, z)
                    if y is None: continue
                    si, sj, _ = sh.window(x, z)
                    if not hull.cavity[si, sj][np.isfinite(sh.low)].all() or not free(sh, x, z, y, columns): continue
                    score = math.hypot(x-2.6*side*s/BABY, z-(front-6.4*s-ahead))+.4*(y+sh.bottom-floor)
                    if best is None or score < best[0]: best = (score, sh, x, z, y)
        if best is None: return None
        _, sh, x, z, y = best; out.append((sh, x, z, y)); columns.append(column(sh, x, z, y))
    return out


def largest_young(hull, cap, lo=SMALL, hi=.7):
    """The largest single young that still fits in the basin with the captain (bisection on growth)."""
    if seat_young(hull, [lo], cap) is None: return None
    for _ in range(7):
        mid = round((lo+hi)/2, 4)
        if seat_young(hull, [mid], cap) is None: hi = mid
        else: lo = mid
    return lo


def brim_ring(scale=SMALL):
    """The brim-edge seat for a young of this growth: its middle half its own half width inside the brim's edge (the rest over the
    water), its braid side (+x) toward the water. The runtime bounds keep the 0.30 reference ring."""
    return B.BRIM["radius"]*B.K-6.4*scale*.5


def turned(p, yaw):
    a = math.radians(yaw); return np.array([p[0]*math.cos(a)-p[2]*math.sin(a), p[1], p[0]*math.sin(a)+p[2]*math.cos(a)])


def lean_out(p, scale, deg, forward=False):
    """A body point at this growth after leaning `deg` about its bottom toward +x (roll), or forward toward -z (pitch)."""
    a = math.radians(deg); x, y, z = np.asarray(p, float)*scale
    if forward: return np.array([x, y*math.cos(a)+z*math.sin(a), z*math.cos(a)-y*math.sin(a)])
    return np.array([x*math.cos(a)+y*math.sin(a), y*math.cos(a)-x*math.sin(a), z])


def splash(hull, scale):
    """A young on the brim ring: its rest height, and how far below the water its braid (hanging from the root at its
    full length toward the nearest water past the edge) and its mouth reach, upright and leaning LEAN toward the water."""
    r, edge, water = brim_ring(scale), B.BRIM['radius']*B.K, -B.WATER*B.K
    sh = young_shape(scale); y = sh.rest(hull.field, r, 0, overhang=True); bottom = y+sh.bottom
    out = dict(scale=scale, ring=round(r, 2), underside=round(bottom, 2))
    for deg in (0., LEAN):
        root = np.array([r, bottom, 0])+lean_out(ROOT_AT, scale, deg)
        reach = CHAIN*scale-math.hypot(max(0., edge+.1-root[0]), root[1]-water)
        mouth = np.array([r, bottom, 0])+turned(lean_out(MOUTH_AT, scale, deg, True), 90)   # turned to face the water
        out['lean' if deg else 'upright'] = dict(braid_below_water=round(reach, 2), mouth_over_water=round(mouth[1]-water, 2),
                                                 mouth_past_edge=round(mouth[0]-edge, 2))
    return out


# ---- with worn hats (user 2026-11-04: a baby's hat easily clips the captain's face): whole posed meshes as column intervals
HAT_GAP = .3              # model units between whole meshes (body, hair, hats)


def intervals(sh, x, z, y):
    """Lowest and highest surface of the whole mesh per cell (one where the other is missing)."""
    u, t = sh.full('under', x, z, y), sh.full('top', x, z, y)
    return np.where(np.isfinite(u), u, t), np.where(np.isfinite(t), t, u)


def apart(sh, x, z, y, others, gap=HAT_GAP):
    si, sj, _ = sh.window(x, z); u, t = sh.under, sh.top
    lo, hi = np.where(np.isfinite(u), u, t)+y, np.where(np.isfinite(t), t, u)+y
    for cu, ct in others:
        cw, tw = cu[si, sj], ct[si, sj]; both = np.isfinite(lo) & np.isfinite(hi) & np.isfinite(cw) & np.isfinite(tw)
        if (both & (lo < tw+gap) & (hi > cw-gap)).any(): return False
    return True


def in_hull(hull, sh, x, z, y):
    """The whole mesh (hat brim included) stays on or over the hull's cloth, the body inside the opening."""
    si, sj, _ = sh.window(x, z); f = hull.field[si, sj]; u = sh.under+y; both = np.isfinite(f) & np.isfinite(u)
    return hull.cavity[si, sj][np.isfinite(sh.low)].all() and not (both & (u < f-.05)).any()


def cabin_hats(hull, scales, mirror=False):
    """Young wearing their hats in the basin before the captain, clear of her face over her paddle turn (-YAW..YAW), of the hull
    and of each other: [(growth, x, z)] or None. mirror: two equal young placed as a mirrored pair."""
    osh, (ox, oz, oy) = captain(hull); others = []
    for yaw in (-YAW, 0., YAW):
        t = captain_shape(1., yaw); others.append(intervals(t, ox, oz, t.rest(hull.field, ox, oz)))
    if mirror:
        s = scales[0]
        for yaw in (-20., 0., 20.):
            a, b = young_shape(s, yaw), young_shape(s, -yaw)
            for z in np.arange(-10, oz+.01, .25):
                for x in np.arange(1., 9.01, .25):
                    ya, yb = a.rest(hull.field, x, z), b.rest(hull.field, -x, z)
                    if ya is None or yb is None or not (in_hull(hull, a, x, z, ya) and in_hull(hull, b, -x, z, yb)): continue
                    if apart(a, x, z, ya, others) and apart(b, -x, z, yb, others) and apart(a, x, z, ya, [intervals(b, -x, z, yb)]):
                        return [(s, float(x), float(z)), (s, float(-x), float(z))]
        return None
    out = []
    for s in sorted(scales, reverse=True):
        best = None
        for yaw in (-20., 0., 20.):
            sh = young_shape(s, yaw)
            for x in np.arange(-9, 9.01, .5):
                for z in np.arange(-10, oz+.01, .25):
                    y = sh.rest(hull.field, x, z)
                    if y is None or not in_hull(hull, sh, x, z, y) or not apart(sh, x, z, y, others): continue
                    score = abs(z+6)+.2*abs(x)
                    if best is None or score < best[0]: best = (score, sh, x, z, y)
        if best is None: return None
        _, sh, x, z, y = best; out.append((s, float(x), float(z))); others.append(intervals(sh, x, z, y))
    return out


def cabin_limit(hull, n):
    """The largest growth at which n equal hatted young fit the basin (bisection; a pair is placed mirrored)."""
    lo, hi = .08, .45
    for _ in range(6):
        mid = round((lo+hi)/2, 3)
        if cabin_hats(hull, [mid]*n, mirror=n == 2) is None: hi = mid
        else: lo = mid
    return lo


HEAD_BAND = .5            # model units under the head's peak that still count as its flat top (export_hat_interactions HEAD_BAND)


def head_cap(hull):
    """The captain's bare head top: its peak above the brim face and the extent within HEAD_BAND of it, and where a hatted
    young of each growth rests on it (underside, fully supported)."""
    osh, (ox, oz, oy) = captain(hull); top = osh.full('top', ox, oz, oy)
    peak = float(top[np.isfinite(top)].max()); m = np.isfinite(top) & (top > peak-HEAD_BAND)
    out = dict(peak=round(peak, 2), x=[round(float(S.GRID.x[m].min()), 1), round(float(S.GRID.x[m].max()), 1)],
               z=[round(float(S.GRID.z[m].min()-oz), 1), round(float(S.GRID.z[m].max()-oz), 1)])
    for s in (.15, .20, .30):
        sh = young_shape(s); y = sh.rest(top, ox, oz+.5, overhang=True); si, sj, _ = sh.window(ox, oz+.5)
        ok = np.isfinite(sh.low) & np.isfinite(top[si, sj])
        out[str(s)] = dict(underside=round(y+sh.bottom, 2), supported=int(ok.sum()) == int(np.isfinite(sh.low).sum()))
    return out


def rescue_reach(cap):
    """From the captain's seat, turning in place, her braid root to the water just outside the brim: the distance as a
    share of the braid's length (best bearing, and over the beam)."""
    osh, (ox, oz, oy) = cap; water, ring = -B.WATER*B.K, B.BRIM['radius']*B.K+.5; best, beam = 9e9, 9e9
    for yaw in range(0, 360, 5):
        root = np.array([ox, oy, oz])+turned(ROOT_AT, yaw)
        for bearing in range(0, 360, 5):
            a = math.radians(bearing); d = float(np.linalg.norm(root-[ring*math.cos(a), water, ring*math.sin(a)]))
            best = min(best, d)
            if bearing in (0, 180) and abs(((yaw+90) % 360)-180) <= 90: beam = min(beam, d)
    return dict(best=round(best, 2), best_share=round(best/CHAIN, 3), beam=round(beam, 2), beam_share=round(beam/CHAIN, 3),
                chain=round(CHAIN, 2), cradle_reach=round(S.braid_envelope()[0], 2))


def bounds(hull=None):
    """The captain's stern seat and the brim ring (blocks, hull frame) for the runtime bounds contract."""
    hull = hull or S.Hull(); cap = captain(hull); osh, (ox, oz, oy) = cap
    return dict(boarding_yaw=120.,
                load=dict(weight_coefficient=1., capacity_over_seat=1.05),
                captain_seat=dict(growth=1., x=round(ox/16, 4), y=round((oy+osh.bottom)/16, 4), z=round(oz/16, 4)),
                brim_ring=dict(radius=round(brim_ring()/16, 4)),
                seating=dict(young_max_growth=BASIN_YOUNG))


def tools():
    spec = importlib.util.spec_from_file_location('aquatic_tools', S.LORE/'aquatic_tools.py')
    T = importlib.util.module_from_spec(spec); spec.loader.exec_module(T); return T


def checks(gates=True):
    hull = S.Hull(); cap = captain(hull); osh, (ox, oz, oy) = cap; out = {}
    out['captain'] = dict(z=oz, underside=round(oy+osh.bottom, 2), top=round(oy+osh.bottom+osh.height, 2))
    babies = {}
    for s in (.15, BABY):
        placed = seat_young(hull, [s, s], cap)
        babies[s] = None if placed is None else [dict(x=x, z=z, underside=round(y+sh.bottom, 2), top=round(y+sh.bottom+sh.height, 2),
                                                      body_top=round(y+sh.bottom+sh.tall, 2)) for sh, x, z, y in placed]
    out['babies_below_mouth'] = babies
    out['body_column_limit'] = largest_young(hull, cap)
    out['basin_young_limit'] = BASIN_YOUNG
    out['brim_young'] = [splash(hull, s) for s in (.12, .15, BABY, SMALL, CHILD)]
    out['cabin_hats'] = dict(single=cabin_limit(hull, 1), pair=cabin_limit(hull, 2),
                             triple_010=cabin_hats(hull, [.1, .1, .1]) is not None, baby_020=cabin_hats(hull, [BABY]) is not None)
    out['head'] = head_cap(hull)
    out['rescue'] = rescue_reach(cap)
    if gates:
        import export as E
        T = tools(); lily = [E.gate_profile(bounds(hull))]; start = time.time()
        out['paddle'] = {n: {k: [v[0], round(float(v[1]), 2), round(float(v[2]), 2), round(float(v[3]), 2)] for k, v in T.gate_exported(n, lily).items()}
                         for n in ('boat_paddle', 'hat_brim_offer', 'boat_nudge_young')}
        out['clips'] = {n: T.body_clearance(n, lily, (-YAW, 0., YAW))[E.HULL_ID] for n in GATE_CLIPS}
        out['gate_seconds'] = round(time.time()-start, 1)
    return out


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--set', action='append', metavar='KEY=VALUE')
    ap.add_argument('--no-gates', action='store_true'); args = ap.parse_args(); start = time.time()
    with B.override(B.parse_sets(args.set)):
        data = checks(not args.no_gates)
    for k, v in data.items(): print(k, json.dumps(v, ensure_ascii=False, separators=(',', ':'), default=float))
    print(f'{time.time()-start:.1f}s')


if __name__ == '__main__':
    main()
