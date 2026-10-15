"""Write the world-setting preview, one static page (index.html): scripted birth and midwifery scenes on the game
models, and, as a second tab, each lore clip on the forms it applies to (page.py).

Couples, newborns, Marisa's own hat and the stem are posed per scene on the runtime models with
their motion corrections. The pregnant body and contractions follow LiveBirthGeometry (deform,
belly, the 48-tick contraction, births every 2.4 s); the hat and the braid stay rigid on the
swollen head as in the game. The hat is Marisa's own hat turned upside down with its soft crown pressed along its axis
(marisa_hat_turned, hat_vessel.json): flat on the ground where the braid clips set it, a little
afloat as the aquatic Marisa's larger boat, its inside kept dry (the water is masked over the
opening, as in the game). The stem
and its fruit follow FamilyGeometry / FamilyRenderer with family.json and the mother's root
motion. Newborns move as in the game (delivery.py, HatVessel, NewbornFlight in FamilyLife): parabolas
under Minecraft's gravity over the brim into their own places, packed by size; stem fruit drop straight
and bounce in from the brim or the ground; the young hop out over the brim when the hat is taken back.
A small shared-depth rasterizer draws every actor in one frame. The page opens from
disk; writing it does not start a browser or the game. Needs numpy and Pillow.

  python births.py [--out .local/previews/lore/index.html]
"""
from pathlib import Path
import argparse, base64, datetime, io, json, math, struct, sys
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[2]/'interactions/marisa-hat-vessel'))
import preview as P
import page as viewer
import delivery as DL

PRESENT = P.ASSETS/'yukkuri_presentation'
VESSEL = json.loads((PRESENT/'hat_vessel.json').read_text(encoding='utf-8'))
CLIPS = ['idle', 'waddle', 'hop', 'brood', 'labor', 'postpartum', 'first_wake', 'hat_cradle', 'sleep', 'wake', 'bereaved', 'midwife',
         'boat_drift', 'boat_brood', 'boat_labor'] + [f'{c}{s}' for c in ('hat_off', 'hat_hold', 'hat_on', 'hat_off_self', 'hat_on_self') for s in ('', '_aq')]


def b64(fmt, values): return base64.b64encode(struct.pack(f'<{len(values)}{fmt}', *values)).decode()


def png_url(array):
    buffer = io.BytesIO(); Image.fromarray(array).save(buffer, 'PNG', optimize=True)
    return 'data:image/png;base64,'+base64.b64encode(buffer.getvalue()).decode()


def pack(ident, corrections):
    p = P.Posed(ident, corrections); m = p.model
    rest = p.frame('idle', 0)[0]
    keep = np.repeat(np.linalg.norm(np.cross(rest[:, 1]-rest[:, 0], rest[:, 2]-rest[:, 0]), axis=1) > 1e-7, 3)
    prefixes = tuple(json.loads((PRESENT/'living.json').read_text(encoding='utf-8'))['variants'][ident]['accessory_prefixes'])
    # 1: accessory (hidden without it, rigid on the swollen head), 2: Marisa's braid (rigid from its root), 3: expression panel
    names = np.concatenate([[part['name']]*part['count'] for part in m['parts'] if not part.get('anatomy')])
    group = np.array([1 if n.startswith(prefixes) else 2 if n.startswith('braid_lock') else 3 if n.startswith('face_') else 0 for n in names])
    # The expression panels are a few large flat quads just in front of the face. On a swollen body they must follow the
    # bulge (the game projects them onto it, PregnancyFaces): keep only their front, cut finely so each point can be deformed.
    v_all, a_all, b_all, q_all, g_all = [], [], [], [], []
    V = np.c_[p.v, p.uv, p.n]
    for t in range(0, len(V), 3):
        if not keep[t]: continue
        tri = V[t:t+3]; g = group[t]
        if g == 3:
            if tri[0, 7] > -.15: continue  # backs and edges of the thin panels
            nsub = 4
            for i in range(nsub):
                for j in range(nsub-i):
                    cells = [((i, j), (i+1, j), (i, j+1))]
                    if i+j < nsub-1: cells.append(((i+1, j), (i+1, j+1), (i, j+1)))
                    for cell in cells:
                        for (ci, cj) in cell:
                            w1, w2 = ci/nsub, cj/nsub; w0 = 1-w1-w2
                            row = tri[0]*w0+tri[1]*w1+tri[2]*w2; near = int(np.argmax([w0, w1, w2]))
                            v_all.append(row); a_all.append(p.a[t+near]); b_all.append(p.b[t+near]); q_all.append(p.q[t+near, 0]); g_all.append(3)
        else:
            for k in range(3):
                v_all.append(tri[k]); a_all.append(p.a[t+k]); b_all.append(p.b[t+k]); q_all.append(p.q[t+k, 0]); g_all.append(g)
    v = np.array(v_all); group_k = np.array(g_all)
    packed = np.c_[np.round(v[:, :3]*1024), np.round(v[:, 3:5]*16384), np.round(v[:, 5:8]*1024)].clip(-32768, 32767).astype(int).ravel().tolist()
    base = {}
    for tr in p.clips['idle']['tracks']:
        base.setdefault(tr['bone'], {})[tr['channel']] = P.sample(tr['keys'], 0)
    bones = [[b['parent']]+[round(x, 4) for x in b['pivot']+base.get(i, {}).get('position', [0, 0, 0])+base.get(i, {}).get('rotation', [0, 0, 0])
              +base.get(i, {}).get('scale', [1, 1, 1])] for i, b in enumerate(m['bones'])]
    names = [b['name'] for b in m['bones']]
    clips, faces = {}, {'neutral', 'sleep', 'happy', 'surprise', 'teary'}
    for name in CLIPS:
        if name not in p.clips: continue
        c = p.clips[name]
        clips[name] = dict(d=c['duration'], loop=c['loop'], faces=c.get('expression_spans', []),
                           tracks=[[tr['bone'], {'position': 0, 'rotation': 3, 'scale': 6}[tr['channel']], [round(x, 4) for k in tr['keys'] for x in k]] for tr in c['tracks']])
        faces |= {f for _, _, f in c.get('expression_spans', [])}
    textures = {f: png_url(p.texture(f)) for f in sorted(faces)}
    special = {k: names.index(k) if k in names else -1 for k in ('root', 'hat_follow', 'hand_right')}
    return dict(vertices=b64('h', packed), weights=b64('H', np.c_[a_all, b_all].astype(int).ravel().tolist()), blend=b64('B', np.round(np.array(q_all)*255).astype(int).tolist()),
                group=b64('B', group_k.tolist()), bones=bones, clips=clips, textures=textures, textureSize=int(p.texture('neutral').shape[1]), special=special)


def hat_prop():
    prop = json.loads((P.ASSETS/f"yukkuri_props/{VESSEL['prop']}.json").read_text(encoding='utf-8'))
    V = np.array(prop['vertices']).reshape(-1, 8)
    packed = np.c_[np.round(V[:, :3]*1024), np.round(V[:, 3:5]*16384), np.round(V[:, 5:8]*1024)].clip(-32768, 32767).astype(int).ravel().tolist()
    tex = np.array(Image.open(P.ASSETS/'textures/entity/marisa/neutral.png').convert('RGBA'))
    return dict(vertices=b64('h', packed), texture=png_url(tex), textureSize=int(tex.shape[1]))


# ------------------------------------------------------------------ scenes (model units, 16 per block, y up)
# Rotations are scene angles about Y (Ry, x' = x cos + z sin); a model at angle a faces Ry(a)(0, 0, -1).
INTERVAL, EMERGE, TICK = 2.4, 1.8, 1/20
AQ = VESSEL['aquatic_scale']
D = VESSEL['delivery']; G = D['gravity']
BODY = json.loads((PRESENT/'body_geometry.json').read_text(encoding='utf-8'))['variants']['reimu']
WIDTH = BODY['bounds'][0][1]-BODY['bounds'][0][0]
FAMILY = json.loads((PRESENT/'family.json').read_text(encoding='utf-8'))
GROWTH = FAMILY['growth']  # the size standard (GrowthSize): a live newborn, a stem child at birth
BABY, FRUIT = GROWTH['birth']['live']['scale'], GROWTH['birth']['stem']['scale']


def key(*pairs): return [list(p) for p in pairs]


def ry(a, v):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a)); x, y, z = v
    return [x*c+z*s, y, -x*s+z*c]


def add(a, b): return [x+y for x, y in zip(a, b)]


def smoothstep(x): x = min(1, max(0, x)); return x*x*(3-2*x)


def deform(p, b):
    """LiveBirthGeometry.deform without a contraction."""
    x, y, z = p; s = 1+.26*b; low = smoothstep(y/1.5)*(1-smoothstep((y-1.5)/4.5)); front = smoothstep(-z/4) if z < 0 else 0
    return [x*s*(1+.16*b*low), y*s, z*s*(1+(.06+.28*front)*b*low)]


def radius(scale): return DL.radius(D, WIDTH*scale)


def live_birth(t_del, count): return [t_del+EMERGE+INTERVAL*i for i in range(count)]


def belly_at(i, count): return 1-.78*i/count


def belly_keys(births, end):
    keys = [[0, 1]]
    for i, b in enumerate(births):
        keys += [[b, belly_at(i, len(births))], [b+.05, belly_at(i+1, len(births))]]
    return keys+[[end+3.5, 0]]


def emerge(mother, rot_m, belly, boat=False):
    """A newborn of a live delivery (LiveBirthRenderer, LiveBirthGeometry.exitFront): where it starts, its front at the
    outlet and its body still inside her, and where it is born, ahead of her collision box (or, afloat in her hat, just
    out of her swollen front)."""
    o = deform(BODY['outlet'], belly); y = max(.24, o[1]-BODY['height']*BABY/2)
    inside = [0, y, o[2]-.04-BODY['bounds'][2][0]*BABY]
    front = -o[2]+WIDTH*BABY/2+.32 if boat else max(-o[2]+2.4, WIDTH/2*(1+.26*belly)*(1+.16*belly)+2.4)
    return add(mother, ry(rot_m, inside)), add(mother, ry(rot_m, [0, y, -front]))


def track(t0, f, T, step=.5):
    """A flight of T ticks sampled about every half tick from t0 (seconds), drawn by the page as a polyline in time."""
    n = max(1, math.ceil(T/step-1e-9)); pts = [round(v, 3) for i in range(n+1) for v in f(i*T/n)]
    return [round(t0, 4), round(T/n*TICK, 6), pts]


def flight(t0, a, b, pose, hat, r, tmin=None):
    """A flight from a to b over the hat (delivery.py): (arrival time, track)."""
    T = DL.solve(a, b, pose, hat, r, D, tmin); return t0+T*TICK, track(t0, DL.arc(a, b, T, G), T)


def set_down(mother, rot_m, variant, role):
    """The hat set down before a mother (hat_vessel.json placements) and, for a helper, where she stands and faces."""
    p = VESSEL['placements'][variant][role]; h = p['hat_in_mother']
    out = dict(hat=add(mother, ry(rot_m, h['origin'])), rot_hat=rot_m-h['yaw'])
    if role == 'helper': out.update(helper=add(mother, ry(rot_m, p['at_mother'])), rot_helper=rot_m+p['angle'])
    return out


def helper_for(hat, rot_hat):
    """Where the helper stands for a hat at this pose: the land helper placement relative to the hat (FamilyLife.stand)."""
    p = VESSEL['placements']['land']['helper']; h = p['hat_in_mother']
    rel = ry(h['yaw'], [a-b for a, b in zip(p['at_mother'], h['origin'])])
    return add(hat, ry(rot_hat, rel)), rot_hat+h['yaw']+p['angle']


def owner_seat(boat, rot_b, k, belly):
    """HatBoatEntity: the owner at the stern on the ledge, her back (LiveBirthGeometry.back) at the opening's rear edge."""
    back = BODY['bounds'][2][1]*(1+.26*belly)*(1+.06*belly)
    return add(boat, ry(rot_b, [0, VESSEL['bowl']['owner_y']*k, VESSEL['opening'][3]*k-back]))


def seats(pose, hat, radii, region, starts=None):
    """World places of young of these radii (world units) in boarding order (HatBoatEntity.reserve), each coming from
    its start (world) if given."""
    taken = []
    for i, r in enumerate(radii):
        st = None if starts is None else pose.local(starts[i]); taken.append(DL.place(hat, D, r/pose.k, region, None, taken, None if st is None else (st[0], st[2]))+[r/pose.k])
    return [pose.world(t[:3]) for t in taken]


def hop_out(pose, hat, babies, places, t_out, adults):
    """At t_out the young hop out over the brim, one after another, side by side on the side of the hat clear of the
    adults (x, z, radius: HatBoatEntity.releaseYoung). Returns when the last has landed."""
    spots = DL.out_spots(pose, hat, [b['r'] for b in babies], adults, D); last = t_out
    spots = [spots[j] for j in DL.match(places, spots)]  # each to the spot that keeps their ways apart
    # the one nearest its spot first, so none hops over a sibling still sitting (HatBoatEntity.releaseYoung, 4 ticks apart)
    order = sorted(range(len(babies)), key=lambda i: math.hypot(spots[i][0]-places[i][0], spots[i][2]-places[i][2]))
    for k, i in enumerate(order):
        b, place, spot = babies[i], places[i], spots[i]
        t0 = t_out+4*TICK*k; t1, tr = flight(t0, place, spot, pose, hat, b['r'], D['hop_tmin'])
        b['pos'] += [[t0, *place], [t1, *spot]]; b['tracks'].append(tr)
        b['clips'] += [[t0, 'hop', 0], [t1+.3, 'idle', 0]]; last = max(last, t1)
    return last


def baby(model, scale, t0, clips, sleep_until, rot=0, **extra):
    d = dict(model=model, scale=scale, visible=[t0, 99], pos=[], tracks=[], yaw=key([0, rot]), clips=clips, faces=[[t0, sleep_until, 'sleep']], r=radius(scale))
    d.update(extra); return d


def delivered(mother, rot_m, births, kinds, pose, hat, region='catch', boat=False, fail=None, ground=0.):
    """Newborns of a live delivery into a hat: each slides out over EMERGE, then is pushed over the near brim into its own
    place (packed in birth order), or, the one at fail, past the far brim to the ground or the water (ground: its
    height). Returns the babies and their places."""
    r = radius(BABY); exits = [emerge(mother, rot_m, belly_at(i, len(births)), boat)[1] for i in range(len(births))]
    places = seats(pose, hat, [r]*len(births), region, [e for i, e in enumerate(exits) if i != fail]+[exits[-1]]); out = []; caught = 0
    for i, (b, kind) in enumerate(zip(births, kinds)):
        inside, ex = emerge(mother, rot_m, belly_at(i, len(births)), boat)
        aim = places[caught]; start = DL.takeoff(ex, aim, pose, hat, r)  # the place it would take (HatBoatEntity.peekSeat)
        caught += i != fail
        if i == fail:
            land = DL.overshoot(start, aim, pose, hat, r, D, ground); t1, tr = flight(b, start, land, pose, hat, r)
            a = baby(kind, BABY, b-EMERGE, [[b-EMERGE, 'idle', 0]], b, rot_m, landed=t1)
            a['pos'] = key([b-EMERGE, *inside], [b, *start], [t1, *land]); a['tracks'].append(tr); a['faces'] = [[b-EMERGE, b, 'sleep'], [b, 99, 'surprise']]
        else:
            t1, tr = flight(b, start, aim, pose, hat, r)
            a = baby(kind, BABY, b-EMERGE, [[b-EMERGE, 'idle', 0], [t1, 'first_wake', 0], [t1+2.0, 'hat_cradle', 0]], t1, rot_m, landed=t1)
            a['pos'] = key([b-EMERGE, *inside], [b, *start], [t1, *aim]); a['tracks'].append(tr)
        out.append(a)
    return out, places[:caught]


def captions_live(t_off, t_del, births, end, t_out, t_on, who):
    return [[0, t_off, who[0]], [t_off, t_del, who[1]], [t_del, births[0]-.1, who[2]], [births[0]-.1, end+.6, '孩子一个个被推出来，划一道弧线越过帽檐，落进帽里各自的位置'],
            [end+.6, t_out, '产后休息；子油在帽里初醒'], [t_out, t_on, '子油一个个跳出帽子，落在帽子另一侧'], [t_on, t_on+2.6, who[3]]]


def scene_land_live():
    mother, rot_m = [0, 0, 0], 0
    f = set_down(mother, rot_m, 'land', 'helper'); nest, rot_n = f['hat'], f['rot_hat']; father, rot_f = f['helper'], f['rot_helper']
    pose, hat = DL.Pose(nest, rot_n, 1.), DL.Hat(VESSEL, 'nest')
    t_off = 2.4; t_del = t_off+1.6; births = live_birth(t_del, 2); end = births[-1]
    babies, places = delivered(mother, rot_m, births, ['reimu', 'marisa'], pose, hat)
    t_out = end+3.4; adults = [[father[0], father[2], WIDTH/2], [mother[0], mother[2], WIDTH/2]]
    t_on = hop_out(pose, hat, babies, places, t_out, adults)+.7
    return dict(id='land_live', title='陆生胎生：魔理沙父亲用辫子摘帽 接生', duration=t_on+2.6, span=24, center=6, focus=[6, -14], az=.45, el=.5,
                captions=captions_live(t_off, t_del, births, end, t_out, t_on, ['灵梦母亲临产，魔理沙父亲赶到帽子要放的地方旁边',
                                       '辫子勾住帽檐摘下帽子，倒过来按在出口前方，帽顶被压扁', '母亲随收缩用力，父亲用辫子扶着帽檐打气', '父亲用辫子把帽子拿起、翻回、戴好']),
                ground='land', actors=[
        dict(model='reimu', scale=1, pos=key([0, *mother]), yaw=key([0, rot_m]), belly=belly_keys(births, end), contraction=[t_del, end],
             clips=[[0, 'brood', 0], [t_del, 'labor', 0], [end+.6, 'postpartum', 0], [end+3.6, 'idle', 0]]),
        dict(model='marisa', scale=1, pos=key([0, *away_from(father, mother, 22)], [t_off-.2, *father]), yaw=key([0, rot_f], [t_off-.2, rot_f]),
             hat=[[0, 1], [t_del, 0], [t_on, 1]],
             clips=[[0, 'waddle', 0], [t_off-.2, 'idle', 0], [t_off, 'hat_off', 0], [t_del, 'hat_hold', 0], [t_on, 'hat_on', 0], [t_on+1.6, 'idle', 0]])]+babies,
                props=[dict(kind='nest', scale=1, visible=[t_del, t_on], pos=key([0, *nest]), yaw=key([0, rot_n]))])


def away_from(p, from_, dist):
    d = [p[0]-from_[0], 0, p[2]-from_[2]]; n = math.hypot(d[0], d[2]) or 1
    return [p[0]+d[0]/n*dist, p[1], p[2]+d[2]/n*dist]


def stem_bases(count, kinds, mother='reimu'):
    """FamilyGeometry.childBase for a mother (scale 1, variance 1, ripe) and these children, in model units."""
    fam = FAMILY; stem = fam['stem']
    units = stem['reference_units_to_model']/16; origin = stem['reference_origin'][1]; length = fam['lengthScale'][count-1]
    var = fam['variants'][mother]; root = [x/16 for x in var['root']]
    def point(z, y):
        rise = (y-origin)*units*length; clearance = var['depth_clearance']/16*min(1, max(0, rise/(var['clearance_rise']/16)))
        return [root[0], root[1]+rise, root[2]+z*units*length-clearance]
    pendant = stem['pendant_length_reference']*stem['reference_units_to_model']/16; out = []
    for i, (z, y) in enumerate(stem['socket_layouts'][str(count)]):
        shape = fam['variants'][kinds[i]]; crown = shape.get('hang_clearance_height', shape['crown_height'])/16*FRUIT; s = point(z, y)
        out.append([s[0]*16, (s[1]-pendant-crown)*16, (s[2]-fam['fruitForwardClearance']/16)*16])
    return out


def sample_flat(keys, t):
    n = len(keys)//4
    if t <= keys[0]: return keys[1:4]
    for i in range(1, n):
        if t <= keys[i*4]:
            a, b = (i-1)*4, i*4; q = (t-keys[a])/(keys[b]-keys[a]) if keys[b] > keys[a] else 0
            return [keys[a+1+k]+(keys[b+1+k]-keys[a+1+k])*q for k in range(3)]
    return keys[(n-1)*4+1:(n-1)*4+4]


def root_matrix(M, name, t):
    """The page's root pose (poseModel) of a packed model in a clip at a local time: 3x4 rows."""
    clip = M['clips'][name]; local = t % clip['d'] if clip['loop'] else min(t, clip['d'])
    vals = [list(b[4:13]) for b in M['bones']]
    for bone, off, keys in clip['tracks']: vals[bone][off:off+3] = sample_flat(keys, local)
    mats = []
    for b, v in zip(M['bones'], vals):
        x, y, z = [math.radians(a) for a in v[3:6]]; cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
        R = np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])*np.array(v[6:9])
        L = np.eye(4); L[:3, :3] = R; P = np.array(b[1:4]); L[:3, 3] = P+np.array(v[0:3])-R@P
        mats.append(mats[b[0]]@L if b[0] >= 0 else L)
    return mats[M['special']['root']]


def scene_land_stem(models):
    """A Marisa father sets his hat under the ripe fruit, its long axis along the stem; they drop straight down, onto
    the floor inside or off the brim and bounce in, each to its own place."""
    count = 3; mother, rot_m = [0, 0, 0], 0; kinds = ['reimu', 'marisa', 'reimu']; bases = stem_bases(count, kinds)
    cx = sum(b[0] for b in bases)/count; cz = sum(b[2] for b in bases)/count
    least = WIDTH/2+VESSEL['brim'][1]+1.6; at = [cx, 0, min(cz, -least)]  # FamilyLife.catchPose, stem
    best = None
    for turn in (0, 180):
        rot_n = rot_m-turn; spot, rot_f = helper_for(at, rot_n); d = math.hypot(spot[0]-mother[0], spot[2]-mother[2])
        if best is None or d > best[0]: best = (d, rot_n, spot, rot_f)
    _, rot_n, father, rot_f = best; nest = at
    pose, hat = DL.Pose(nest, rot_n, 1.), DL.Hat(VESSEL, 'nest')
    t_off = 2.4; t_hold = t_off+1.6; drops = [6.4+.4*i for i in range(count)]; r = radius(FRUIT)
    contacts = []
    for i, base in enumerate(bases):
        R = root_matrix(models['reimu'], 'brood', drops[i]); p0 = add(mother, ry(rot_m, list(R[:3, :3]@np.array(base)+R[:3, 3])))
        l = pose.local(p0); h = hat.footprint(l[0], l[2], r/pose.k); y_c = 0. if h is None else nest[1]+h*pose.k
        v0 = D['fall_speed']; tc = (-v0+math.sqrt(v0*v0+2*G*(p0[1]-y_c)))/G
        fall = lambda t, p0=p0, v0=v0: [p0[0], p0[1]-v0*t-G/2*t*t, p0[2]]
        contacts.append((drops[i]+tc*TICK, i, p0, [p0[0], y_c, p0[2]], h, track(drops[i], fall, tc)))
    order = sorted(contacts); taken = []; babies = [None]*count; landed = []
    for t_c, i, p0, contact, h, fall in order:  # each keeps where it came down if free, else the nearest free place
        lc = pose.local(contact); lp = DL.place(hat, D, r/pose.k, 'catch', [lc[0], lc[2]], taken, (lc[0], lc[2])); taken.append(lp+[r/pose.k]); place = pose.world(lp)
        settles = h is not None and h < hat.top-1e-6 and math.hypot(lp[0]-lc[0], lp[2]-lc[2]) <= D['settle']
        t1, hop = (t_c+TICK, None) if settles else flight(t_c+(TICK if h is None else 0), contact, place, pose, hat, r, D['hop_tmin'])
        b = baby(kinds[i], FRUIT, drops[i], [[drops[i], 'idle', 0], [t1, 'first_wake', 0], [t1+2.0, 'hat_cradle', 0]], t1, rot_m)
        b['pos'] = key([drops[i], *p0], [t_c, *contact], *([[t_c+TICK, *contact]] if h is None else []), [t1, *place]); b['tracks'] = [fall]+([hop] if hop else [])
        babies[i] = b; landed.append((i, place))
    t_out = max(t for t, *_ in order)+4.4; adults = [[father[0], father[2], WIDTH/2], [mother[0], mother[2], WIDTH/2]]
    t_on = hop_out(pose, hat, [babies[i] for i, _ in landed], [p for _, p in landed], t_out, adults)+.7
    return dict(id='land_stem', title='陆生茎生：实子直直落下，落进魔理沙倒放的帽子', duration=t_on+2.6, span=28, center=8, focus=[0, -20], az=.95, el=.45,
                captions=[[0, t_off, '灵梦头上的茎结满实子，魔理沙父亲赶来'], [t_off, t_hold, '辫子摘帽，倒过来顺着茎的方向按在实子正下方，帽顶压扁'],
                          [t_hold, drops[0], '实子成熟，父亲在帽子另一头用辫子扶着帽檐等着'],
                          [drops[0], order[-1][0]+.8, '实子一个个直直落下：落进帽里的就地坐好，碰到帽檐的弹一下跳进去'],
                          [order[-1][0]+.8, t_out, '母亲产后休息，子油在帽里各自的位置初醒'], [t_out, t_on, '子油一个个跳出帽子'], [t_on, t_on+2.6, '父亲用辫子戴回帽子']],
                ground='land', stem=dict(actor=0, count=count, progress=key([0, .62], [6.0, 1]), drops=drops, kinds=kinds),
                actors=[dict(model='reimu', scale=1, pos=key([0, *mother]), yaw=key([0, rot_m]), clips=[[0, 'brood', 0], [drops[-1]+.4, 'postpartum', 0], [drops[-1]+3.4, 'idle', 0]]),
                        dict(model='marisa', scale=1, pos=key([0, *away_from(father, mother, 22)], [t_off-.2, *father]), yaw=key([0, rot_f], [t_off-.2, rot_f]),
                             hat=[[0, 1], [t_hold, 0], [t_on, 1]],
                             clips=[[0, 'waddle', 0], [t_off-.2, 'idle', 0], [t_off, 'hat_off', 0], [t_hold, 'hat_hold', 0], [t_on, 'hat_on', 0], [t_on+1.6, 'idle', 0]])]+babies,
                props=[dict(kind='nest', scale=1, visible=[t_hold, t_on], pos=key([0, *nest]), yaw=key([0, rot_n]))])


def scene_boat():
    boat, rot_b = [0, 0, 0], 0; t_del = 2.0; births = live_birth(t_del, 3); end = births[-1]; dusk = end+4.0; t_tuck = dusk+1.4
    pose, hat = DL.Pose(boat, rot_b, AQ), DL.Hat(VESSEL, 'bowl')
    mother_pos = owner_seat(boat, rot_b, AQ, 1)
    babies, days = delivered(mother_pos, rot_b, births, ['marisa']*3, pose, hat, region='day', boat=True)
    nights = seats(pose, hat, [b['r'] for b in babies], 'night'); nights = [nights[j] for j in DL.match(days, nights)]
    for i, (b, day, night) in enumerate(zip(babies, days, nights)):  # they shuffle back together, not across one another
        b['pos'] += [[t_tuck, *day], [t_tuck+.8, *night]]; b['clips'].append([dusk+2.4, 'sleep', i*.7]); b['bob'] = True
    return dict(id='boat', title='水生帽船分娩：子油落进帽内的干燥空间，夜里睡在亲油身下', duration=dusk+6, span=24, center=2, focus=[0, -2],
                night=key([0, 0], [dusk, 0], [dusk+1.5, 1]),
                captions=[[0, t_del, '水生魔理沙坐在自己的大帽船船尾待产'], [t_del, end+.5, '孩子从出口出来，轻轻落进身前帽内的干燥空间，各占一处'],
                          [end+.5, dusk, '子油在帽里初醒；帽内不进水，像小船一样'], [dusk, dusk+6, '入夜，子油挪到亲油身下，在帽里睡；帽船仍漂在水上']],
                ground='water', actors=[dict(model='marisa', scale=1, bob=True, pos=key([0, *mother_pos], [end+3.5, *owner_seat(boat, rot_b, AQ, 0)]), yaw=key([0, rot_b]), hat=[[0, 0]],
                                             belly=belly_keys(births, end), contraction=[t_del, end],
                                             clips=[[0, 'boat_brood', 0], [t_del, 'boat_labor', 0], [end+.6, 'boat_drift', 0], [dusk+1.6, 'sleep', 0]])]+babies,
                props=[dict(kind='boat', scale=AQ, bob=True, visible=[0, 99], pos=key([0, *boat]), yaw=key([0, rot_b]))])


def scene_dock_miss():
    """Aquatic couple at a bank: the father floats his larger hat at the water's edge; the second baby overshoots into the river."""
    mother, rot_m = [18, 0, 0], 90  # at the bank edge, facing the river (-X)
    edge = mother[0]-VESSEL['placements']['land']['mother_half']; drop = VESSEL['float']['bank_drop']
    f = set_down(mother, rot_m, 'aquatic', 'helper'); hat_at, rot_hat = f['hat'], f['rot_hat']; father, rot_f = f['helper'], f['rot_helper']
    pose, hat = DL.Pose(hat_at, rot_hat, AQ), DL.Hat(VESSEL, 'bowl')
    t_shore = 3.0; t_off = 5.2; t_del = t_off+1.6; births = live_birth(t_del, 2); fail = births[1]
    babies, _ = delivered(mother, rot_m, births, ['marisa']*2, pose, hat, fail=1, ground=hat_at[1])
    lost = babies[1]; splash = lost['pos'][-1][1:]; lost['pos'].append([lost['landed']+1.2, *add(splash, [0, -7, 0])]); lost['visible'] = [fail-EMERGE, lost['landed']+1.2]
    boat_path = key([0, -30, -drop, 30], [t_shore-.4, edge-16, -drop, 4])
    return dict(id='dock_miss', title='水生靠岸分娩与接生失误：帽子浮在岸边接，用力过猛的孩子落水', duration=fail+9, span=30, center=2, focus=[edge-8, 4], az=-1.0, el=.5,
                captions=[[0, t_shore, '临产的水生魔理沙让帽船靠岸'], [t_shore, t_off, '上岸戴回帽子，面朝河在岸边待产；水生父亲赶来'],
                          [t_off, t_del, '父亲用辫子摘下自己的大帽，倒过来按在出口前的水边'], [t_del, fail-.1, '父亲用辫子扶住帽檐；第一个孩子划一道小弧线落进帽里'],
                          [fail-.1, lost['landed'], '用力过猛，第二个孩子沿同一方向飞过整顶帽子……'], [lost['landed'], fail+2.3, '落进河里。父母愣住，呆滞'],
                          [fail+2.3, fail+9, '「ゆっくり……ゆっくりぃ！」（油库里……油库里——！）父母哭泣']],
                ground='bank', bank=edge, water_y=-drop,
                actors=[dict(model='marisa', scale=1, visible=[t_shore, 99], pos=key([0, *mother]), yaw=key([0, rot_m]), hat=[[0, 1]], hat_scale=AQ,
                             belly=key([0, 1], [births[0], 1], [births[0]+.05, .61], [fail, .61], [fail+.05, .22], [fail+3.5, 0]), contraction=[t_del, fail],
                             clips=[[0, 'hop', 0], [t_shore+.6, 'brood', 0], [t_del, 'labor', 0], [fail+.7, 'bereaved', 0]]),
                        dict(model='marisa', scale=1, visible=[0, t_shore], bob=True, pos=key(*[[t, *add(add(p, [0, VESSEL['bowl']['owner_y']*AQ, 0]), ry(140, [0, 0, VESSEL['opening'][3]*AQ-BODY['bounds'][2][1]*1.26*1.06]))] for t, *p in boat_path]),
                             yaw=key([0, 140]), hat=[[0, 0]], belly=key([0, 1]), clips=[[0, 'boat_brood', 0]]),
                        dict(model='marisa', scale=1, pos=key([0, *away_from(father, mother, 24)], [t_off-.3, *father]), yaw=key([0, rot_f], [t_off-.3, rot_f]),
                             hat=[[0, 1], [t_del, 0]], hat_scale=AQ,
                             clips=[[0, 'waddle', 0], [t_off-.3, 'idle', 0], [t_off, 'hat_off_aq', 0], [t_del, 'hat_hold_aq', 0], [fail+.7, 'bereaved', 0]])]+babies,
                props=[dict(kind='boat', scale=AQ, bob=True, visible=[0, t_shore], pos=boat_path, yaw=key([0, 140])),
                       dict(kind='boat', scale=AQ, visible=[t_del, 99], pos=key([0, *hat_at]), yaw=key([0, rot_hat]))])


def scene_night_boat():
    boat, rot_b = [0, 0, 0], 30; dawn = 6.5
    pose, hat = DL.Pose(boat, rot_b, AQ), DL.Hat(VESSEL, 'bowl'); mother_pos = owner_seat(boat, rot_b, AQ, 0); r = radius(BABY)
    nights, days = seats(pose, hat, [r]*3, 'night'), seats(pose, hat, [r]*3, 'day'); days = [days[j] for j in DL.match(nights, days)]
    babies = [baby('marisa', BABY, 0, [[0, 'sleep', i*.7], [dawn, 'wake', 0], [dawn+2.4, 'hat_cradle', 0]], -1, rot_b, bob=True, faces=[]) for i in range(3)]
    for i, b in enumerate(babies): b['pos'] = key([0, *nights[i]], [dawn+1.6, *nights[i]], [dawn+2.4, *days[i]])
    return dict(id='night', title='水生子油夜里睡在帽内，亲油在上方，帽船漂在水上', duration=dawn+6, span=22, center=1, focus=[0, 0], night=key([0, 1], [dawn-1.5, 1], [dawn, 0]),
                captions=[[0, dawn-1, '夜里，帽船漂在水上；子油睡在帽内的干燥空间，大亲油在上方护着'], [dawn-1, dawn+1.6, '天亮，大家醒来'], [dawn+1.6, dawn+6, '子油挪到亲油身前，在帽里晒太阳']],
                ground='water', actors=[dict(model='marisa', scale=1, bob=True, pos=key([0, *mother_pos]), yaw=key([0, rot_b]), hat=[[0, 0]],
                                             clips=[[0, 'sleep', 1.3], [dawn, 'wake', 0], [dawn+1.6, 'boat_drift', 0]])]+babies,
                props=[dict(kind='boat', scale=AQ, bob=True, visible=[0, 99], pos=key([0, *boat]), yaw=key([0, rot_b]))])


def scene_self():
    """A Marisa mother with no Marisa to catch for her sets her own hat before her outlet, as far out as her braid reaches."""
    mother, rot_m = [0, 0, 0], 0; f = set_down(mother, rot_m, 'land', 'self'); nest, rot_n = f['hat'], f['rot_hat']
    pose, hat = DL.Pose(nest, rot_n, 1.), DL.Hat(VESSEL, 'nest')
    t_off = 2.0; t_del = t_off+1.8; births = live_birth(t_del, 2); end = births[-1]
    babies, places = delivered(mother, rot_m, births, ['marisa', 'marisa'], pose, hat)
    t_out = end+3.4; adults = [[mother[0], mother[2], WIDTH/2]]
    t_on = hop_out(pose, hat, babies, places, t_out, adults)+.7
    return dict(id='self', title='单亲魔理沙：自己用辫子把帽子倒过来放在出口前', duration=t_on+2.6, span=22, center=6, focus=[0, -14], az=.6, el=.45,
                captions=captions_live(t_off, t_del, births, end, t_out, t_on, ['没有伴侣接生的魔理沙母亲临产', '俯身用辫子摘帽，倒过来按在自己出口前，帽顶压扁',
                                       '她把帽子放在辫子够得到的地方，再随收缩用力', '她俯身用辫子拿回帽子戴好']),
                ground='land', actors=[dict(model='marisa', scale=1, pos=key([0, *mother]), yaw=key([0, rot_m]), belly=belly_keys(births, end), contraction=[t_del, end],
                                            hat=[[0, 1], [t_del, 0], [t_on, 1]],
                                            clips=[[0, 'brood', 0], [t_off, 'hat_off_self', 0], [t_del, 'labor', 0], [end+.6, 'postpartum', 0], [end+3.6, 'idle', 0], [t_on, 'hat_on_self', 0], [t_on+1.8, 'idle', 0]])]+babies,
                props=[dict(kind='nest', scale=1, visible=[t_del, t_on], pos=key([0, *nest]), yaw=key([0, rot_n]))])


def scene_stems():
    """The stem fitted to the brood (art/props/family-life/fit_stem.py): Reimu and Marisa mothers with one to five ripe
    fruit, mixed Reimu and Marisa young."""
    actors = []
    for row, mother in enumerate(('reimu', 'marisa')):
        for count in range(1, 6):
            kinds = [['reimu', 'marisa'][(i+row) % 2] for i in range(count)]
            actors.append(dict(model=mother, scale=1, pos=key([0, (count-3)*34, 0, row*50-25]), yaw=key([0, 0]), clips=[[0, 'brood', 0]],
                               stem=dict(count=count, progress=key([0, 1]), drops=[99]*count, kinds=kinds)))
    return dict(id='stems', title='茎长随子油数量：一至五只（上排灵梦、下排魔理沙母体）', duration=8, span=60, center=10, focus=[0, 0], az=.5, el=.35,
                captions=[[0, 8, '茎按子油数量取最短长度：每只实子挂在母体前方、直着落下碰不到母体，前后错开，先落的不会砸到还挂着的']],
                ground='land', actors=actors, props=[])


def scene_sizes():
    """The size standard (GrowthSize, profiles/growth.json) side by side, and a turned hat holding five live newborns."""
    G = GROWTH; st = {s['id']: s for s in G['stages']}
    row = [('reimu', G['fruit']['from'], 'sleep'), ('reimu', G['birth']['live']['scale'], 'sleep'), ('reimu', G['birth']['stem']['scale'], None),
           ('marisa', .5, None), ('reimu', st['juvenile']['max_scale'], None), ('marisa', 1.0, None), ('reimu', st['adult']['max_scale'], None)]
    actors, x = [], 0.
    for model, scale, face in row:
        w = WIDTH*scale; x += w/2
        a = dict(model=model, scale=scale, pos=key([0, x, 0, 0]), yaw=key([0, 0]), clips=[[0, 'idle', 0]])
        if face: a['faces'] = [[0, 99, face]]
        actors.append(a); x += w/2+(5.0 if scale < .3 else 2.0)
    x += 4; mother = [x+WIDTH*.73, 0, 0]
    actors.append(dict(model='reimu', scale=1, pos=key([0, *mother]), yaw=key([0, 0]), belly=key([0, 1]), clips=[[0, 'brood', 0]]))
    hat_at = [mother[0]+WIDTH*.73+4+VESSEL['brim'][1], 0, 0]; pose, hat = DL.Pose(hat_at, 90, 1.), DL.Hat(VESSEL, 'nest')
    for i, place in enumerate(seats(pose, hat, [radius(BABY)]*5, 'catch')):
        actors.append(dict(model=['reimu', 'marisa'][i % 2], scale=BABY, pos=key([0, *place]), yaw=key([0, 0]), clips=[[0, 'hat_cradle', i*.4]]))
    shift = -(hat_at[0]+VESSEL['brim'][1])/2  # centred on the ground
    for a in actors: a['pos'] = [[k[0], k[1]+shift, *k[2:]] for k in a['pos']]
    hat_at = [hat_at[0]+shift, 0, 0]
    return dict(id='sizes', title='体型规范：各成长阶段大小对照', duration=6, span=34, center=6, focus=[0, 0], az=0., el=.25,
                captions=[[0, 6, '从左到右：茎上实子 0.10、胎生新生 0.14、茎生落地 0.20、子体 0.50、子体长足 0.80、成体 1.00、大个体 1.20、满孕母体；最右是倒放的帽子，坐着 5 只胎生新生']],
                ground='land', actors=actors, props=[dict(kind='nest', scale=1, visible=[0, 99], pos=key([0, *hat_at]), yaw=key([0, 90]))])


SCENES = [scene_land_live, scene_land_stem, scene_boat, scene_dock_miss, scene_night_boat, scene_self, scene_sizes, scene_stems]  # scene_land_stem takes the packed models

PAGE = r'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>世界观动作 · 预览</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#eae7de;color:#51463e;font:15px/1.6 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
main{max-width:1100px;margin:auto;padding:18px}h1{font-size:22px;margin:0 0 4px}p{margin:4px 0 8px}
.bar{display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin:6px 0}button{font:inherit;font-size:14px;border:1px solid #b9ad99;background:#f7f4ec;color:#51463e;border-radius:6px;padding:3px 10px;cursor:pointer}
button[aria-pressed=true]{background:#51463e;color:#f7f4ec;border-color:#51463e}label{display:inline-flex;gap:6px;align-items:center;font-size:14px}
canvas{display:block;width:100%;height:540px;background:#f4f1e9;border:1px solid #d8d0c1;border-radius:10px;touch-action:none;cursor:grab}
#caption{min-height:2.2em;font-size:16px;margin:8px 0 4px;color:#3d342c}footer{margin-top:12px;font-size:12.5px;color:#7b6f60}
.tabs{display:flex;gap:8px;margin:6px 0 10px}.tabs button{font-size:15px;padding:5px 14px}#clipsFrame{width:100%;height:1500px;border:0}
</style>
<main><h1>世界观动作 · 预览（待复查）</h1>
<div class="tabs"><button type="button" id="tabScenes" aria-pressed="true">出生与接生场景</button><button type="button" id="tabClips" aria-pressed="false">单个动作</button></div>
<section id="scenesView">
<p>灵梦×魔理沙夫妻灵梦为母、魔理沙为父；只有魔理沙接生：用辫子摘下自己的帽子，倒过来按在出口前，帽冠压扁，用帽内接。水生魔理沙的帽子更大，当船漂在水上，帽内不进水。孕体整体变大、下部前方鼓起。最后两个场景是体型规范和茎长对照。游戏模型与动作修正资源（__REVISION__）。拖动旋转，滚轮缩放，直接双击打开。</p>
<div class="bar" id="scenes"></div>
<div class="bar"><button type="button" id="play" aria-pressed="true">暂停</button>
<label>速度 <select id="speed"><option value=".5">0.5×</option><option value="1" selected>1×</option><option value="2">2×</option></select></label>
<label>时间 <input id="time" type="range" min="0" max="1" step=".01" value="0" style="width:320px"> <span id="clock">0.0 秒</span></label></div>
<div id="caption"></div><canvas id="view"></canvas>
<footer>生成于 __DATE__。离线页面只供查看出生与接生的编排，不代表游戏运行验收。失误是 12% 的原创几率事件；台词沿用已有原生台词。</footer></section>
<section id="clipsView" hidden><iframe id="clipsFrame" title="单个动作"></iframe></section></main>
<script>
const D=__DATA__;
// ---- engine (also run offline by the checks) ----
function decode(b,T){const s=atob(b),u=new Uint8Array(s.length);for(let i=0;i<s.length;i++)u[i]=s.charCodeAt(i);return new T(u.buffer);}
function unpack(src){const raw=decode(src.vertices,Int16Array),out=new Float32Array(raw.length);for(let i=0;i<raw.length;i+=8){for(let k=0;k<3;k++){out[i+k]=raw[i+k]/1024;out[i+5+k]=raw[i+5+k]/1024;}out[i+3]=raw[i+3]/16384;out[i+4]=raw[i+4]/16384;}return out;}
const MODELS={};for(const [id,m] of Object.entries(D.models))MODELS[id]={m,rest:unpack(m),w:decode(m.weights,Uint16Array),q:decode(m.blend,Uint8Array),grp:decode(m.group,Uint8Array),mats:m.bones.map(()=>new Float64Array(12)),tex:{},size:m.textureSize,cache:new Map()};
const HAT={rest:unpack(D.hat),size:D.hat.textureSize,tex:null},V=D.metrics;
function smooth(x){x=Math.min(1,Math.max(0,x));return x*x*(3-2*x);}
function sample(keys,t){const n=keys.length/4;if(t<=keys[0])return [keys[1],keys[2],keys[3]];
  for(let i=1;i<n;i++)if(t<=keys[i*4]){const a=(i-1)*4,b=i*4,q=keys[b]>keys[a]?(t-keys[a])/(keys[b]-keys[a]):0;return [0,1,2].map(k=>keys[a+1+k]+(keys[b+1+k]-keys[a+1+k])*q);}
  const a=(n-1)*4;return [keys[a+1],keys[a+2],keys[a+3]];}
// LiveBirthGeometry.deform: the whole body grows, the lower front fills out; y up from the floor, -Z the face.
function deform(x,y,z,b,c){if(b<=0)return [x,y,z];const s=1+.26*b,low=smooth(y/1.5)*(1-smooth((y-1.5)/4.5)),front=z<0?smooth(-z/4):0;
  return [x*s*(1+.16*b*low)*(1+.035*c),y*s*(1-.04*c),z*s*(1+(.06+.28*front)*b*low)*(1+.035*c)];}
function poseModel(M,name,t,hatScale){
  const ck=name+'@'+t.toFixed(3)+'@'+hatScale;if(M.cache.has(ck))return M.cache.get(ck);
  const clip=M.m.clips[name]||M.m.clips.idle,vals=M.m.bones.map(b=>b.slice(4,13));
  const local=clip.loop?t%clip.d:Math.min(t,clip.d);for(const [bone,off,keys] of clip.tracks){const v=sample(keys,local);vals[bone][off]=v[0];vals[bone][off+1]=v[1];vals[bone][off+2]=v[2];}
  const hf=M.m.special.hat_follow,A=V.anchor_worn;
  M.m.bones.forEach((b,i)=>{const v=vals[i],P=[b[1],b[2],b[3]];const [x,y,z]=[v[3],v[4],v[5]].map(a=>a*Math.PI/180),cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
    const R=[cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx,sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx,-sy,cy*sx,cy*cx],L=new Float64Array(12);
    for(let r=0;r<3;r++){for(let c=0;c<3;c++)L[r*4+c]=R[r*3+c]*v[6+c];L[r*4+3]=P[r]+v[r]-(L[r*4]*P[0]+L[r*4+1]*P[1]+L[r*4+2]*P[2]);}
    // The aquatic Marisa's larger hat: the hat bone scaled about the hat's seat (YukkuriModel.setHatScale).
    if(i===hf&&hatScale!==1)for(let r=0;r<3;r++){L[r*4+3]+=(L[r*4]*A[0]+L[r*4+1]*A[1]+L[r*4+2]*A[2])*(1-hatScale);for(let c=0;c<3;c++)L[r*4+c]*=hatScale;}
    if(b[0]<0)M.mats[i]=L;else{const Q=M.mats[b[0]],W=new Float64Array(12);for(let r=0;r<3;r++)for(let c=0;c<4;c++)W[r*4+c]=Q[r*4]*L[c]+Q[r*4+1]*L[4+c]+Q[r*4+2]*L[8+c]+(c===3?Q[r*4+3]:0);M.mats[i]=W;}});
  const Rv=M.rest,O=new Float32Array(Rv.length);
  for(let i=0;i<Rv.length/8;i++){const o=i*8,Aa=M.mats[M.w[i*2]],B=M.mats[M.w[i*2+1]],q=M.q[i]/255,x=Rv[o],y=Rv[o+1],z=Rv[o+2];
    for(let r=0;r<3;r++){const a=Aa[r*4]*x+Aa[r*4+1]*y+Aa[r*4+2]*z+Aa[r*4+3],b=B[r*4]*x+B[r*4+1]*y+B[r*4+2]*z+B[r*4+3];O[o+r]=a+(b-a)*q;}
    O[o+3]=Rv[o+3];O[o+4]=Rv[o+4];const nx=Rv[o+5],ny=Rv[o+6],nz=Rv[o+7];let mx=Aa[0]*nx+Aa[1]*ny+Aa[2]*nz,my=Aa[4]*nx+Aa[5]*ny+Aa[6]*nz,mz=Aa[8]*nx+Aa[9]*ny+Aa[10]*nz;const l=Math.hypot(mx,my,mz)||1;O[o+5]=mx/l;O[o+6]=my/l;O[o+7]=mz/l;}
  // How far the hat and braid are off their rest pose: rigid lift on a swollen head only while they sit on it.
  const hv=hf>=0?vals[hf]:null,bv=M.m.special.hand_right>=0?vals[M.m.special.hand_right]:null;
  const out={v:O,root:M.mats[M.m.special.root].slice(),hatW:hv?Math.max(0,1-Math.hypot(hv[0],hv[1],hv[2])/3-Math.max(...hv.slice(3,6).map(Math.abs))/30):1,
    braidW:bv?Math.max(0,1-Math.max(...bv.slice(3,6).map(Math.abs))/30):1};
  if(M.cache.size>400)M.cache.clear();M.cache.set(ck,out);return out;}
// PregnancyFaces: each expression-panel point sits just in front of the swollen body's actual front surface.
function fitPanels(src,grp){const G=.25,X0=-12,Y0=-1,NX=96,NY=64,front=new Float32Array(NX*NY).fill(Infinity);
  for(let i=0;i<src.length;i+=24){if(grp[i/8]!==0)continue;const a=[src[i],src[i+1],src[i+2]],b=[src[i+8],src[i+9],src[i+10]],c=[src[i+16],src[i+17],src[i+18]];
    if(a[2]>-2||b[2]>-2||c[2]>-2)continue;const d=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1]);if(Math.abs(d)<1e-9)continue;
    const gx0=Math.max(0,Math.floor((Math.min(a[0],b[0],c[0])-X0)/G)),gx1=Math.min(NX-1,Math.ceil((Math.max(a[0],b[0],c[0])-X0)/G));
    const gy0=Math.max(0,Math.floor((Math.min(a[1],b[1],c[1])-Y0)/G)),gy1=Math.min(NY-1,Math.ceil((Math.max(a[1],b[1],c[1])-Y0)/G));
    for(let gy=gy0;gy<=gy1;gy++)for(let gx=gx0;gx<=gx1;gx++){const x=X0+gx*G,y=Y0+gy*G;
      const wa=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/d,wb=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/d,wc=1-wa-wb;
      if(wa<-.02||wb<-.02||wc<-.02)continue;const z=wa*a[2]+wb*b[2]+wc*c[2],k=gy*NX+gx;if(z<front[k])front[k]=z;}}
  for(let i=0,vi=0;i<src.length;i+=8,vi++){if(grp[vi]!==3)continue;const gx=Math.round((src[i]-X0)/G),gy=Math.round((src[i+1]-Y0)/G);
    if(gx<0||gy<0||gx>=NX||gy>=NY)continue;let f=Infinity;for(let dy=-1;dy<=1;dy++)for(let dx=-1;dx<=1;dx++){const x=gx+dx,y=gy+dy;if(x>=0&&y>=0&&x<NX&&y<NY)f=Math.min(f,front[y*NX+x]);}
    if(isFinite(f))src[i+2]=Math.min(src[i+2],f-.035);}}
function keyAt(keys,t){if(t<=keys[0][0])return keys[0].slice(1);for(let i=1;i<keys.length;i++)if(t<=keys[i][0]){const a=keys[i-1],b=keys[i],q=smooth((t-a[0])/Math.max(1e-6,b[0]-a[0]));return a.slice(1).map((v,k)=>v+(b[k+1]-v)*q);}return keys[keys.length-1].slice(1);}
function stepAt(list,t,def){let v=def;for(const e of list)if(t>=e[0])v=e;return v;}
function contraction(a,t){if(!a.contraction||t<a.contraction[0]||t>a.contraction[1]+.3)return 0;const w=.5+.5*Math.sin((t-a.contraction[0])*20*Math.PI*2/48);return .22+.78*Math.pow(w,4);}
function bobAt(t){return .5*Math.sin(2*Math.PI*t/3);}
function place(src,out,T,skip){ // T: {s, yaw, pre: [3x3, offset] applied after the scale, pos}
  const cy=Math.cos(T.yaw*Math.PI/180),sy=Math.sin(T.yaw*Math.PI/180);let n=0;
  for(let i=0;i<src.length;i+=24){if(skip&&skip(i/8))continue;for(let j=0;j<3;j++){const o=i+j*8;let x=src[o]*T.s,y=src[o+1]*T.s,z=src[o+2]*T.s,nx=src[o+5],ny=src[o+6],nz=src[o+7];
      if(T.pre){const [m,t]=T.pre;[x,y,z]=[m[0]*x+m[1]*y+m[2]*z+t[0],m[3]*x+m[4]*y+m[5]*z+t[1],m[6]*x+m[7]*y+m[8]*z+t[2]];[nx,ny,nz]=[m[0]*nx+m[1]*ny+m[2]*nz,m[3]*nx+m[4]*ny+m[5]*nz,m[6]*nx+m[7]*ny+m[8]*nz];}
      out[n]=x*cy+z*sy+T.pos[0];out[n+1]=y+T.pos[1];out[n+2]=-x*sy+z*cy+T.pos[2];out[n+3]=src[o+3];out[n+4]=src[o+4];out[n+5]=nx*cy+nz*sy;out[n+6]=ny;out[n+7]=-nx*sy+nz*cy;n+=8;}}
  return out.subarray(0,n);}
function faceAt(M,a,clip,local,t){if(a.faces)for(const [t0,t1,f] of a.faces)if(t>=t0&&t<t1)return f;const c=M.m.clips[clip];if(!c)return 'neutral';const u=c.loop?local%c.d:Math.min(local,c.d);
  for(const [s,e,f] of c.faces)if(u>=s&&u<e)return f;return 'neutral';}
function quad(x0,z0,x1,z1,y,color,mask){const v=new Float32Array(48);const P=[[x0,z0],[x1,z0],[x1,z1],[x0,z0],[x1,z1],[x0,z1]];P.forEach((p,i)=>{v.set([p[0],y,p[1],-1,0,0,1,0],i*8);});return {v,color,mask};}
function tube(out,a,b,r0,r1){const ax=[b[0]-a[0],b[1]-a[1],b[2]-a[2]],l=Math.hypot(...ax)||1;ax.forEach((v,i)=>ax[i]=v/l);const ref=Math.abs(ax[2])<.9?[0,0,1]:[1,0,0];
  const cr=(p,q)=>[p[1]*q[2]-p[2]*q[1],p[2]*q[0]-p[0]*q[2],p[0]*q[1]-p[1]*q[0]];let u=cr(ax,ref);const ul=Math.hypot(...u);u=u.map(v=>v/ul);const w=cr(ax,u);
  for(let i=0;i<8;i++){const A=i*Math.PI/4,B=(i+1)*Math.PI/4,na=[0,1,2].map(k=>u[k]*Math.cos(A)+w[k]*Math.sin(A)),nb=[0,1,2].map(k=>u[k]*Math.cos(B)+w[k]*Math.sin(B));
    const p=[a.map((v,k)=>v+na[k]*r0),b.map((v,k)=>v+na[k]*r1),b.map((v,k)=>v+nb[k]*r1),a.map((v,k)=>v+nb[k]*r0)];
    for(const [i0,i1,i2] of [[0,1,2],[0,2,3]])for(const k of [i0,i1,i2])out.push(...p[k],-1,0,...na);}}
function rootApply(R,v){const out=new Float32Array(v);for(let i=0;i<v.length;i+=8){const x=v[i],y=v[i+1],z=v[i+2],nx=v[i+5],ny=v[i+6],nz=v[i+7];
  for(let r=0;r<3;r++){out[i+r]=R[r*4]*x+R[r*4+1]*y+R[r*4+2]*z+R[r*4+3];out[i+5+r]=R[r*4]*nx+R[r*4+1]*ny+R[r*4+2]*nz;}}return out;}
function stemItems(st,actor,t,root){  // the stem on this mother (FamilyGeometry with her own root and clearance)
  const F=D.family,S=F.stem,count=st.count,prog=keyAt(st.progress,t)[0],grow=smooth(Math.min(1,prog/.15)),units=S.reference_units_to_model/16,len=F.lengthScale[count-1],Vr=F.variants[actor.model];
  const rt=Vr.root.map(v=>v/16);const point=(z,y)=>{const rise=(y-S.reference_origin[1])*units*len,cl=Vr.depth_clearance/16*Math.min(1,Math.max(0,rise/(Vr.clearance_rise/16)));return [rt[0],rt[1]+rise,rt[2]+z*units*len-cl];};
  const spent=t>=st.drops[st.drops.length-1],col=spent?[157,135,79]:[66,186,57],tri=[];const arc=S.centerline.map(([z,y])=>{const p=point(z,y);return rt.map((r,k)=>(r+(p[k]-r)*grow)*16);});
  for(let i=1;i<arc.length;i++){const r0=(S.radius[0]+(S.radius[1]-S.radius[0])*(i-1)/(arc.length-1))*F.radiusScale[count-1],r1=(S.radius[0]+(S.radius[1]-S.radius[0])*i/(arc.length-1))*F.radiusScale[count-1];tube(tri,arc[i-1],arc[i],r0,r1);}
  // GrowthSize.fruit and FamilyGeometry.childBase / FamilyRenderer: each fruit hangs by its own crown (or hanging anchor)
  const items=[],Fr=F.growth.fruit,fs=Fr.from+(Fr.to-Fr.from)*Math.min(1,Math.max(0,(prog-Fr.visible_from)/(Fr.full_at-Fr.visible_from)));
  S.socket_layouts[String(count)].forEach(([z,y],i)=>{if(t>=st.drops[i]||prog<Fr.visible_from)return;const s=point(z,y).map(v=>v*16),shape=F.variants[st.kinds[i]],an=shape.hanging_anchor;
    const base=[s[0],s[1]-S.pendant_length_reference*S.reference_units_to_model-(shape.hang_clearance_height??shape.crown_height)*fs,s[2]-F.fruitForwardClearance];
    tube(tri,s,an?[base[0]+an[0]*fs,base[1]+an[1]*fs,base[2]+an[2]*fs]:[base[0],base[1]+shape.crown_height*fs,base[2]],.12,.09);const M=MODELS[st.kinds[i]],posed=poseModel(M,'idle',0,1).v;
    items.push({v:place(posed,new Float32Array(posed.length),{s:fs,yaw:0,pos:base}),tex:M.tex.sleep,size:M.size});});
  items.push({v:new Float32Array(tri),color:col});
  // The stem grows from the forehead and moves with the mother's root motion (FamilyRenderer follows rootPose).
  const T=actorT(actor,t);return items.map(it=>{const v=rootApply(root,it.v);it.v=place(v,new Float32Array(v.length),T);return it;});}
// Flights (delivery.py: parabolas under gravity) are sampled tracks; between them an actor eases from key to key.
function trackAt(a,t){if(a.tracks)for(const [t0,dt,p] of a.tracks){const n=p.length/3-1;if(t>=t0&&t<=t0+n*dt){const u=(t-t0)/dt,i=Math.min(n-1,Math.floor(u)),q=u-i;return [0,1,2].map(k=>p[i*3+k]+(p[i*3+3+k]-p[i*3+k])*q);}}return null;}
function actorT(a,t){const p=trackAt(a,t)||keyAt(a.pos,t);if(a.bob)p[1]+=bobAt(t);return {s:a.scale,yaw:keyAt(a.yaw,t)[0],pos:p};}
function vesselT(p,t){const pos=keyAt(p.pos,t),k=p.scale;if(p.bob)pos[1]+=bobAt(t);const yaw=keyAt(p.yaw,t)[0];
  // the hat upside down, its crown pressed along the hat's axis (HatBoatRenderer): on the ground the brim stands on the squashed
  // crown; afloat the brim is a freeboard over the water
  const boat=p.kind==='boat',sq=boat?V.squash.bowl:V.squash.nest;
  return {s:k,yaw,pre:[[1,0,0,0,sq,0,0,0,1],[0,(boat?V.bowl.freeboard:V.nest.height)*k,0]],pos};}
function frameItems(scene,t){
  const items=[],water=[];
  if(scene.ground==='land')items.push(quad(-80,-80,80,80,-.02,[214,204,178]));
  if(scene.ground==='water')water.push(quad(-80,-80,80,80,0,[96,148,196]));
  if(scene.ground==='bank'){const e=scene.bank??-6;items.push(quad(e,-80,80,80,-.02,[214,204,178]));const w=quad(-80,-80,80,80,0,[176,160,128]);for(let i=0;i<48;i+=8){const z=w.v[i+2],top=w.v[i]>0;w.v[i]=e;w.v[i+1]=top?-.02:(scene.water_y??-1.2)-2;w.v[i+2]=z;w.v[i+3]=1;w.v[i+4]=0;}items.push(w);water.push(quad(-90,-80,e,80,scene.water_y??-1.2,[96,148,196]));}
  scene.actors.forEach((a,idx)=>{
    if(a.visible&&(t<a.visible[0]||t>=a.visible[1]))return;const M=MODELS[a.model];
    const [t0,clip,off]=stepAt(a.clips,t,a.clips[0]);let local=t-t0+off;
    const posed=poseModel(M,clip,local,a.hat_scale||1),belly=a.belly?keyAt(a.belly,t)[0]:0,c=contraction(a,t),hat=a.hat?stepAt(a.hat,t,[0,1])[1]:1;
    let src=posed.v;
    if(belly>0){src=new Float32Array(posed.v);const s=1+.26*belly,lift=9.6*(s-1)*posed.hatW,br=[6.93,9.33,-2.52],bd=deform(...br,belly,0).map((v,k)=>(v-br[k])*posed.braidW);
      for(let i=0,vi=0;i<src.length;i+=8,vi++){const g=M.grp[vi];
        if(g===1)src[i+1]+=lift;else if(g===2){src[i]+=bd[0];src[i+1]+=bd[1];src[i+2]+=bd[2];}
        else{const d=deform(src[i],src[i+1],src[i+2],belly,c);src[i]=d[0];src[i+1]=d[1];src[i+2]=d[2];}}
      fitPanels(src,M.grp);}
    const T=actorT(a,t);
    const v=place(src,new Float32Array(src.length),T,hat?null:(vi=>M.grp[vi]===1));
    items.push({v,tex:M.tex[faceAt(M,a,clip,local,t)]||M.tex.neutral,size:M.size});
    const st=a.stem||(scene.stem&&scene.stem.actor===idx?scene.stem:null);if(st)items.push(...stemItems(st,a,t,posed.root));});
  for(const p of scene.props){if(t<p.visible[0]||t>=p.visible[1])continue;const T=vesselT(p,t),pos0=T.pos;
    items.push({v:place(HAT.rest,new Float32Array(HAT.rest.length),T),tex:HAT.tex,size:HAT.size});
    if(p.kind==='boat'){const ring=V.bowl.mask,still=keyAt(p.pos,t),v=new Float32Array(ring.length*24);  // a fan at the water line, which does not bob
      ring.forEach((a,i)=>{const b=ring[(i+1)%ring.length];v.set([0,0,0,0,0,0,1,0,a[0]*p.scale,0,a[1]*p.scale,0,0,0,1,0,b[0]*p.scale,0,b[1]*p.scale,0,0,0,1,0],i*24);});
      items.push({v:place(v,new Float32Array(v.length),{s:1,yaw:T.yaw,pos:[pos0[0],still[1]+.05,pos0[2]]}),mask:true});}}
  return items.concat(water);}
function renderScene(items,width,height,az,el,span,centerY,night,focus){
  const fx=focus?focus[0]:0,fz=focus?focus[1]:0;
  const px=new Uint8ClampedArray(width*height*4),depth=new Float64Array(width*height).fill(-Infinity);
  const s=Math.sin(az),c=Math.cos(az),se=Math.sin(el),ce=Math.cos(el),right=[c,0,s],up=[-s*se,ce,c*se],eye=[s*ce,se,-c*ce],scale=height/(2*span);
  const tint=[1-.55*night,1-.5*night,1-.25*night];
  for(const it of items){const V2=it.v,tex=it.tex,size=it.size;if(!it.color&&!tex&&!it.mask)continue;
    for(let i=0;i<V2.length;i+=24){
      const a=[0,1,2].map(k=>V2[i+8+k]-V2[i+k]),b=[0,1,2].map(k=>V2[i+16+k]-V2[i+k]);const nx=a[1]*b[2]-a[2]*b[1],ny=a[2]*b[0]-a[0]*b[2],nz=a[0]*b[1]-a[1]*b[0];
      if(!it.color&&!it.mask&&nx*eye[0]+ny*eye[1]+nz*eye[2]<=0)continue;
      const nl=Math.hypot(nx,ny,nz)||1,light=.78+.22*Math.max(0,(V2[i+5]*-.35+V2[i+6]*.8+V2[i+7]*-.48));const lit=it.color?(.86+.14*Math.abs(ny/nl)):light;
      const tri=[];for(let j=0;j<3;j++){const k=i+j*8,x=V2[k]-fx,y=V2[k+1]-centerY,z=V2[k+2]-fz;tri.push([width/2+(x*right[0]+z*right[2])*scale,height/2-(x*up[0]+y*up[1]+z*up[2])*scale,x*eye[0]+y*eye[1]+z*eye[2],V2[k+3]*size,V2[k+4]*size]);}
      const [A,B,C]=tri,det=(B[1]-C[1])*(A[0]-C[0])+(C[0]-B[0])*(A[1]-C[1]);if(Math.abs(det)<1e-8)continue;
      const x0=Math.max(0,Math.floor(Math.min(A[0],B[0],C[0]))),x1=Math.min(width-1,Math.ceil(Math.max(A[0],B[0],C[0]))),y0=Math.max(0,Math.floor(Math.min(A[1],B[1],C[1]))),y1=Math.min(height-1,Math.ceil(Math.max(A[1],B[1],C[1])));
      for(let y=y0;y<=y1;y++)for(let x=x0;x<=x1;x++){const wa=((B[1]-C[1])*(x+.5-C[0])+(C[0]-B[0])*(y+.5-C[1]))/det,wb=((C[1]-A[1])*(x+.5-C[0])+(A[0]-C[0])*(y+.5-C[1]))/det,wc=1-wa-wb;
        if(wa<-1e-7||wb<-1e-7||wc<-1e-7)continue;const z=wa*A[2]+wb*B[2]+wc*C[2],idx=y*width+x;
        if(it.mask){if(z>depth[idx])depth[idx]=z;continue;}  // the dry well: depth only, so the water is not drawn inside the hat
        if(z<=depth[idx]+1e-7)continue;
        let r,g,bl;if(it.color){[r,g,bl]=it.color;}else{const u=Math.max(0,Math.min(size-1,Math.floor(wa*A[3]+wb*B[3]+wc*C[3]))),v=Math.max(0,Math.min(size-1,Math.floor(wa*A[4]+wb*B[4]+wc*C[4]))),t=(v*size+u)*4;if(tex[t+3]<128)continue;r=tex[t];g=tex[t+1];bl=tex[t+2];}
        depth[idx]=z;const p=idx*4;px[p]=r*lit*tint[0];px[p+1]=g*lit*tint[1];px[p+2]=bl*lit*tint[2];px[p+3]=255;}}}
  return px;}
// ---- end engine ----
const $=id=>document.getElementById(id),canvas=$('view');let scene=D.scenes[0],time=0,playing=true,last=performance.now(),az=-.55,el=.42,zoom=1,ready=0;
function load(src,set){const img=new Image();img.onload=()=>{const c=document.createElement('canvas');c.width=img.width;c.height=img.height;const x=c.getContext('2d');x.drawImage(img,0,0);set(x.getImageData(0,0,c.width,c.height).data);ready--;};ready++;img.src=src;}
for(const M of Object.values(MODELS))for(const [f,src] of Object.entries(M.m.textures))load(src,d=>M.tex[f]=d);load(D.hat.texture,d=>HAT.tex=d);
D.scenes.forEach((s,i)=>{const b=document.createElement('button');b.type='button';b.textContent=s.title;b.onclick=()=>{scene=s;time=0;az=s.az??-.55;el=s.el??.42;$('time').max=s.duration;document.querySelectorAll('#scenes button').forEach(o=>o.setAttribute('aria-pressed',String(o===b)));};$('scenes').append(b);if(!i)b.click();});
$('play').onclick=()=>{playing=!playing;$('play').textContent=playing?'暂停':'播放';$('play').setAttribute('aria-pressed',String(playing));};
$('time').oninput=()=>{time=Number($('time').value);playing=false;$('play').textContent='播放';};
const ptr=new Map();canvas.addEventListener('pointerdown',e=>{ptr.set(e.pointerId,[e.clientX,e.clientY]);canvas.setPointerCapture(e.pointerId);});
canvas.addEventListener('pointermove',e=>{if(!ptr.has(e.pointerId))return;const o=ptr.get(e.pointerId);ptr.set(e.pointerId,[e.clientX,e.clientY]);az-=(e.clientX-o[0])*.008;el=Math.max(-.2,Math.min(1.3,el+(e.clientY-o[1])*.008));});
for(const n of ['pointerup','pointercancel'])canvas.addEventListener(n,e=>ptr.delete(e.pointerId));
canvas.addEventListener('wheel',e=>{e.preventDefault();zoom=Math.max(.5,Math.min(3,zoom*Math.exp(-e.deltaY*.001)));},{passive:false});
function tick(now){const dt=Math.min(.1,(now-last)/1000);last=now;if(playing){time+=dt*Number($('speed').value);if(time>scene.duration)time=0;$('time').value=time;}
  $('clock').textContent=time.toFixed(1)+' 秒';const cap=scene.captions.find(([a,b])=>time>=a&&time<b);$('caption').textContent=cap?cap[2]:'';
  if(ready===0&&!$('scenesView').hidden){const w=Math.max(1,Math.round(canvas.clientWidth)),h=Math.max(1,Math.round(canvas.clientHeight));if(canvas.width!==w)canvas.width=w;if(canvas.height!==h)canvas.height=h;
    const night=scene.night?keyAt(scene.night,time)[0]:0;canvas.getContext('2d').putImageData(new ImageData(renderScene(frameItems(scene,time),w,h,az,el,scene.span/zoom,scene.center,night,scene.focus),w,h),0,0);}
  requestAnimationFrame(tick);}
requestAnimationFrame(tick);
// The single-clip viewer (page.py) lives in the second tab, loaded when first opened.
for(const [id,show] of [['tabScenes',false],['tabClips',true]])$(id).onclick=()=>{
  $('scenesView').hidden=show;$('clipsView').hidden=!show;$('tabScenes').setAttribute('aria-pressed',String(!show));$('tabClips').setAttribute('aria-pressed',String(show));
  if(show&&!$('clipsFrame').srcdoc)$('clipsFrame').srcdoc=CLIPS_PAGE;};
</script>
<script>const CLIPS_PAGE=__CLIPS__;</script></html>
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0]); ap.add_argument('--out', default=str(P.OUT/'index.html'))
    ap.add_argument('--corrections', default=str(PRESENT/'motion_corrections.json')); a = ap.parse_args()
    corrections = json.loads(Path(a.corrections).read_text(encoding='utf-8'))
    family = json.loads((PRESENT/'family.json').read_text(encoding='utf-8'))
    data = dict(models={ident: pack(ident, corrections) for ident in ('reimu', 'marisa')}, hat=hat_prop(), metrics=VESSEL,
                family=dict(stem=family['stem'], growth=family['growth'], lengthScale=family['lengthScale'], radiusScale=family['radiusScale'],
                            fruitForwardClearance=family['fruitForwardClearance'], variants={k: family['variants'][k] for k in ('reimu', 'marisa')}),
                scenes=[])
    data['scenes'] = [s(data['models']) if s is scene_land_stem else s() for s in SCENES]
    for scene in data['scenes']:
        for actor in scene['actors']: actor.pop('r', None); actor.pop('landed', None)
    html = (PAGE.replace('__REVISION__', corrections['revision']).replace('__DATE__', datetime.date.today().isoformat())
            .replace('__DATA__', json.dumps(data, ensure_ascii=False, separators=(',', ':')))
            .replace('__CLIPS__', json.dumps(viewer.build(corrections), ensure_ascii=False).replace('</', '<\\/')))
    Path(a.out).parent.mkdir(parents=True, exist_ok=True); Path(a.out).write_text(html, encoding='utf-8')
    print('wrote', a.out, f'{len(html)/1e6:.2f} MB', len(data['scenes']), 'scenes')


if __name__ == '__main__':
    main()
