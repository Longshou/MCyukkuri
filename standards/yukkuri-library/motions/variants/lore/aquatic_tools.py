"""Aquatic Marisa tool clips: the paddle twig, the braid cradle, the mouth bite, capsize and self-rescue (project original).

Imported by author.py (compile_clips). Every number that the game or a preview needs at run time is written into the
exported clip itself, so both read the same data:

- ``props.paddle``: the twig prop ``paddle_twig`` placed in the frame of ``bone`` (the braid tuft, ``hand_right_tip``)
  at that bone's pivot. Keys are ``[t, px, py, pz, rx, ry, rz]`` in model units and degrees, rotation composed
  Rz*Ry*Rx like every bone track; the prop's own ``socket`` (its grip) is the origin that the keys place.
- ``markers``: named clip seconds (``power_start``/``power_end`` for the stroke, contact beats for the others).
- ``contacts``: where a body touches something, as a bone and a rest-space point on it (``mouth`` uses the game's
  mouth attachment instead of a bone point).

The braid is the only tool besides the mouth (no rope or knot): the tuft wraps the twig's grip, curls into a scoop
for a young, or hooks over a brim. Whole-body pitch plus roll stays within about 12 degrees; effort is squash,
stretch, small shifts, yaw and the face.
"""
from pathlib import Path
import json, math
import numpy as np

ROOT = Path(__file__).resolve().parents[5]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
VESSEL = ASSETS/'yukkuri_presentation/hat_vessel.json'
BRAID = ASSETS/'yukkuri_presentation/braid_rescue.json'

PADDLE_CLIPS = ('boat_paddle', 'hat_brim_offer', 'boat_nudge_young')
NEW_CLIPS = ('braid_cradle_lift', 'be_cradled', 'mouth_bite_hold', 'boat_thrown', 'water_hat_climb', 'water_hat_right',
             'boat_cover_young', 'boat_wedge_berth', 'boat_push_off', 'boat_cargo_stow', 'boat_cargo_fetch', 'boat_jettison')
# Beats were authored on these lengths; the recipe duration rescales them (boat_thrown 0.6 s, water_hat_right 1.6 s).
AUTHORED = {'boat_thrown': 1.2, 'water_hat_right': 2.0}
EXPORT_CLIPS = PADDLE_CLIPS+NEW_CLIPS


def smooth(x):
    x = min(1., max(0., x)); return x*x*(3-2*x)


def bump(x):
    return math.sin(math.pi*min(1., max(0., x)))**2


def lerp(a, b, q): return a+(b-a)*q


def keyed(t, keys):
    if t <= keys[0][0]: return keys[0][1]
    for (a, x), (b, y) in zip(keys, keys[1:]):
        if t <= b: return lerp(x, y, smooth((t-a)/(b-a)))
    return keys[-1][1]


def euler(r):
    x, y, z = np.radians(r)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    return np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])


def to_euler(R):
    """Degrees [x, y, z] with R = Rz Ry Rx."""
    y = math.asin(max(-1., min(1., -R[2, 0])))
    if abs(R[2, 0]) < .9999:
        x = math.atan2(R[2, 1], R[2, 2]); z = math.atan2(R[1, 0], R[0, 0])
    else:
        x = math.atan2(-R[1, 2], R[1, 1]); z = 0.
    return [math.degrees(x), math.degrees(y), math.degrees(z)]


def aim(v, d):
    """Smallest rotation turning direction v onto d."""
    v, d = v/np.linalg.norm(v), d/np.linalg.norm(d); axis = np.cross(v, d); s = np.linalg.norm(axis); c = float(v@d)
    if s < 1e-9: return np.eye(3)
    k = axis/s; K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3)+s*K+(1-c)*K@K


def polar(A):
    U, _, Vt = np.linalg.svd(A); R = U@Vt
    if np.linalg.det(R) < 0: U[:, -1] *= -1; R = U@Vt
    return R


def unwrap(rows):
    """Keep consecutive Euler samples continuous so linear key interpolation never spins the long way round."""
    out = []
    for row in rows:
        if out: row = [v+360*round((p-v)/360) for v, p in zip(row, out[-1])]
        out.append(row)
    return out


# ---- whole-body root poses -------------------------------------------------------------------------------------------

def paddle_phase(t, c):
    """Stroke phase u in [0, 1): the power half is u < .5, from power_start to power_end."""
    m = c['markers']; return ((t-m['power_start'])/c['duration']) % 1.


def state(action, t, c, height):
    """Root (scale, rotation, lift, [x, z] shift) for the clips owned here; None when author.py keeps its own."""
    s, r, lift, shift = [1., 1., 1.], [0., 0., 0.], 0., [0., 0.]
    d = c['duration']
    if action in AUTHORED: t, d = t*AUTHORED[action]/d, AUTHORED[action]
    if action == 'boat_paddle':
        # Seated low in the hull: lean into the catch, drive back through the power half with a small squash and a
        # shoulder twist toward the braid side; the recovery straightens. Pitch -5..+2, roll <= 2.5.
        u = paddle_phase(t, c); power = u < .5; q = smooth(u/.5) if power else smooth((u-.5)/.5)
        drive = bump(u/.5) if power else 0.
        s = [c['width']+.02*drive, c['height']-.03*drive, c['width']+.015*drive]
        r = [lerp(-5, 1.5, q) if power else lerp(1.5, -5, q), lerp(-7, 6, q) if power else lerp(6, -7, q), -1-1.5*drive]
        shift[1] = .25*drive
    elif action == 'braid_cradle_lift':
        # Reach toward the young beside the hull (lean and look to the braid side), scoop, lift it in front of her and set it
        # down, watching it the whole way. Lean <= 6; the yaw follows the braid round to the front.
        lean = keyed(t, [(0, 0), (.5, 1), (.8, 1), (1.5, .4), (2.0, .6), (2.4, 0)])
        effort = bump((t-.8)/.7)
        s = [1+.03*lean+.03*effort, 1-.05*lean-.05*effort, 1+.02*lean+.03*effort]
        r = [-4*lean, keyed(t, [(0, 0), (.5, -18), (.8, -18), (1.3, -8), (1.8, -6), (2.4, 0)]), -5*lean*(1-smooth((t-.8)/.6))]
        shift[0] = .25*lean
    elif action == 'be_cradled':
        # A young held in the curled tuft: sags into the scoop (wider, lower), a little limp, swaying with the carrier.
        w = 2*math.pi*t/d
        s = [1.07+.012*math.sin(w), .88-.015*math.sin(w), 1.06]; r = [4+1.5*math.sin(w+.7), 0, 3*math.sin(w)]
    elif action == 'mouth_bite_hold':
        # Bite and hold a brim or a young's headwear: head forward to the contact, then short backward tugs (rear shift,
        # squash) twice per loop with a small yaw worry. Pitch -6..-3.
        tug = bump((t % (d/2))/(d/2))
        s = [1+.06*tug, .97-.08*tug, 1+.05*tug]; r = [-6+3*tug, 6*math.sin(2*math.pi*t/d), 1.5*math.sin(4*math.pi*t/d)]
        shift[1] = -.6+1.0*tug
    elif action == 'boat_thrown':
        # Pitched out as the hat capsizes: a stretched startle with a half yaw spin and a wobble (pitch + roll <= 12),
        # then the splash squash. The server owns the arc; no lift is added.
        spin = smooth(t/.8); splash = bump((t-.82)/.38)
        s = [.94+.13*splash, 1.1-.08*smooth(t/.8)-.2*splash, .94+.11*splash]
        r = [-4*math.sin(math.pi*min(1, t/.8))+3*splash, 200*spin, 7*math.sin(2*math.pi*t/.55)*(1-smooth((t-.7)/.3))]
    elif action == 'water_hat_climb':
        # Self-rescue: bite the brim, hook the braid over it, then pull up in two efforts and hold for the slide in.
        bite = smooth(t/.35); pull = keyed(t, [(0, 0), (.85, 0), (1.05, -1), (1.25, .7), (1.4, -.6), (1.55, .9), (1.8, .8)])
        s = [1+.06*max(0, -pull), 1-.13*max(0, -pull)+.08*max(0, pull), 1+.05*max(0, -pull)-.02*max(0, pull)]
        r = [-6*bite-3*max(0, pull), keyed(t, [(0, 0), (.4, -6), (.8, -4), (1.8, 0)]), 2*math.sin(math.pi*min(1, t/.8))]
        lift = height*.14*smooth((t-1.0)/.6); shift[1] = -.6*bite-height*.08*smooth((t-1.0)/.6)
    elif action == 'water_hat_right':
        # Righting the capsized hat: bite its brim, tug and twist it up (yaw is free), push the far side over with the braid,
        # let go and sit back. Pitch -6..+5, roll <= 4.
        bite = keyed(t, [(0, 0), (.3, 1), (1.5, 1), (1.8, 0)])
        heave = keyed(t, [(0, 0), (.6, 0), (.9, -1), (1.2, 1), (1.5, .4), (2.0, 0)])
        s = [1+.06*max(0, -heave)-.02*max(0, heave), 1-.12*max(0, -heave)+.07*max(0, heave), 1+.05*max(0, -heave)]
        r = [-6*bite+5*max(0, heave)*bite, keyed(t, [(0, 0), (.6, 0), (1.2, -24), (1.6, -18), (2.0, 0)]), 4*math.sin(math.pi*min(1, max(0, t-.6)/.9))]
        shift[1] = -.5*bite+.4*max(0, heave)
    elif action == 'boat_cover_young':
        # Bent over the young in the hollow before her (night, rain, a shadow overhead): forward 9 degrees, pressed low,
        # slow breathing; the braid drapes over them. Loops on boat_steady's seat.
        w = 2*math.pi*t/d
        s = [c['width']+.03, c['height']-.06+.01*math.sin(w), c['width']+.05]; r = [-9+.6*math.sin(w), 0, .8*math.sin(w/2)]
        shift[1] = -.35
    elif action == 'boat_wedge_berth':
        # Nose into a bank notch: two braid shoves at the bank ahead, then she sits back on the stern brim so the bow lifts
        # and catches. Pitch -4..+5.
        shove = bump((t-.15)/.4)+bump((t-.7)/.4); sit = smooth((t-1.05)/.4)
        s = [c['width']+.03*shove+.02*sit, c['height']-.04*shove-.03*sit, c['width']+.02*shove]
        r = [-4*shove+5*sit, -6*shove, 0]; shift[1] = -.3*shove+.5*sit
    elif action == 'boat_push_off':
        # Shove off from the bank with the braid: gather, push, ride the glide out. Pitch -5..+2.
        q = keyed(t, [(0, 0), (.25, -1), (.45, 1), (.8, 0)])
        s = [c['width']+.04*max(0, -q), c['height']-.06*max(0, -q)+.03*max(0, q), c['width']+.03*max(0, -q)]
        r = [2*max(0, -q)-5*max(0, q), 8*max(0, -q), 0]; shift[1] = .3*max(0, -q)-.4*max(0, q)
    elif action in ('boat_cargo_stow', 'boat_cargo_fetch'):
        # Head down into the opening before her to the crown larder: stow lets the mouthful go at the bottom, fetch bites one
        # there and brings it up. Pitch to -10, a squash and a small forward reach.
        dip = keyed(t, [(0, 0), (.35, 1), (.65, 1), (1.0, 0)])
        s = [c['width']+.05*dip, c['height']-.12*dip, c['width']+.07*dip]; r = [-10*dip, 0, 1.5*math.sin(math.pi*t/d)]
        shift[1] = -1.3*dip
    elif action == 'boat_jettison':
        # Shove spoiled food or dirt over the brim: twist to the braid side, the braid sweeps it over, a short look after it.
        sweep = keyed(t, [(0, 0), (.3, 1), (.6, 1), (1.0, 0)])
        s = [c['width']+.02*sweep, c['height']-.03*sweep, c['width']]; r = [-5*sweep, -14*sweep, -4*sweep]; shift[0] = .3*sweep
    else:
        return None
    return s, r, lift, shift


# ---- braid tracks -----------------------------------------------------------------------------------------------------

def braid_rotation(action, t, c, side):
    """Rotation of the braid root (hand_right side=1, hand_left side=-1) or None to leave that lock at rest."""
    d = c['duration']
    if action in AUTHORED: t, d = t*AUTHORED[action]/d, AUTHORED[action]
    if action == 'boat_paddle' and side > 0:
        # The braid swings the twig forward-out at the catch, draws it aft through the water, lifts and returns.
        u = paddle_phase(t, c); power = u < .5; q = smooth(u/.5) if power else smooth((u-.5)/.5)
        sweep = lerp(c['braid_sweep'][0], c['braid_sweep'][1], q) if power else lerp(c['braid_sweep'][1], c['braid_sweep'][0], q)
        return [sweep, 0, c['braid_out']+(c['braid_raise']*bump((u-.5)/.5) if not power else 0)]
    if action in ('hat_brim_offer', 'boat_nudge_young'):
        if side > 0: return [4, 0, 31+2*math.sin(2*math.pi*t/d)]
        return [0, 0, -(8+3*math.sin(2*math.pi*t/d))]
    if action == 'braid_cradle_lift':
        if side < 0: return [0, 0, -(8+4*bump(t/d))]
        # Out and down to the young in the water beside the hull, then forward-up so the scooped tuft clears the rim, and in
        # to set it on the front-right inner rim (the braid reaches no further; the transfer walks it on to its seat).
        # A plain swing inward would drag it through the brim.
        return [keyed(t, [(0, 0), (.5, 10), (.8, 12), (1.3, 44), (1.8, 52), (2.0, 52), (2.4, 0)]), 0,
                keyed(t, [(0, 0), (.5, 14), (.8, 14), (1.3, 16), (1.8, -32), (2.0, -32), (2.4, 0)])]
    if action == 'be_cradled':
        return [0, 0, side*(14+3*math.sin(2*math.pi*t/d))]  # limp locks hanging off the scoop
    if action == 'mouth_bite_hold':
        tug = bump((t % (d/2))/(d/2)); return [-3*tug, 0, side*(8+6*tug)]
    if action == 'boat_thrown':
        flail = math.sin(2*math.pi*t/.42)*(1-smooth((t-.8)/.3)); return [-20*flail*side, side*6, side*(30+22*abs(flail))]
    if action == 'water_hat_climb':
        if side < 0: return [0, 0, -(10+6*bump((t-.85)/.9))]
        # Forward and up over the brim, then hold the hook while the body pulls.
        return [keyed(t, [(0, 0), (.3, 30), (.75, 62), (1.8, 58)]), 0, keyed(t, [(0, 0), (.3, 20), (.75, 8), (1.8, 6)])]
    if action == 'water_hat_right':
        if side < 0: return [0, 0, -(10+6*bump((t-.6)/1.0))]
        # Rest, then swing forward-up under the lifting far brim and push it over.
        return [keyed(t, [(0, 0), (.8, 20), (1.0, 55), (1.4, 92), (1.7, 70), (2.0, 0)]), 0,
                keyed(t, [(0, 0), (.8, 18), (1.2, 10), (1.6, 8), (2.0, 0)])]
    if action == 'boat_cover_young':
        return [44, 0, side*10] if side > 0 else [20, 0, side*12]  # draped forward over the hollow
    if action == 'boat_wedge_berth' and side > 0:
        shove = bump((t-.15)/.4)+bump((t-.7)/.4)
        return [lerp(30, 58, shove), 0, lerp(26, 14, shove)]  # out over the bow, then a shove into the bank
    if action == 'boat_push_off' and side > 0:
        return [keyed(t, [(0, 0), (.25, -10), (.45, -38), (.8, 0)]), 0, keyed(t, [(0, 0), (.25, 42), (.45, 30), (.8, 0)])]
    if action == 'boat_jettison' and side > 0:
        return [keyed(t, [(0, 0), (.3, 30), (.6, 8), (1.0, 0)]), 0, keyed(t, [(0, 0), (.3, 14), (.6, 44), (1.0, 0)])]
    if action in ('boat_cargo_stow', 'boat_cargo_fetch'):
        return [0, 0, side*(10+6*bump(t/c['duration']))]  # locks swing aside as the head goes down
    return None


def tip_rotation(action, t, c):
    """Rotation of the braid tuft (hand_right_tip) for the curl, hook and push; paddle clips aim it along the stick instead."""
    if action in AUTHORED: t = t*AUTHORED[action]/c['duration']
    if action == 'braid_cradle_lift':
        curl = keyed(t, [(0, 0), (.5, .3), (.8, .5), (1.05, 1), (2.0, 1), (2.3, 0)])
        return [-48*curl, 0, 62*curl]  # rolls up toward the body into a scoop
    if action == 'boat_cover_young':
        return [-30, 0, 20]  # the tuft curls in round the young
    if action == 'boat_jettison':
        flat = keyed(t, [(0, 0), (.3, 1), (.6, 1), (1.0, 0)]); return [-30*flat, 0, 0]
    if action == 'water_hat_climb':
        hook = keyed(t, [(0, 0), (.55, .2), (.8, 1), (1.8, 1)]); return [70*hook, 0, -10*hook]  # tuft folds down over the rim
    if action == 'water_hat_right':
        flat = keyed(t, [(0, 0), (.9, 0), (1.1, 1), (1.6, 1), (2.0, 0)]); return [-35*flat, 0, 0]  # open tuft pressing like a palm
    return None


# ---- skeleton -----------------------------------------------------------------------------------------------------------

def sample(keys, t):
    if t <= keys[0][0]: return keys[0][1:]
    for a, b in zip(keys, keys[1:]):
        if t <= b[0]:
            q = (t-a[0])/(b[0]-a[0]) if b[0] > a[0] else 0
            return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]
    return keys[-1][1:]


def palette(model, tracks, t):
    """World matrices exactly as the game composes them: idle at 0 under this clip's tracks."""
    pose = {}
    idle = model['clips']['idle']
    for clip_tracks, at in ((idle['tracks'], 0), (tracks, t)):
        for tr in clip_tracks: pose.setdefault(tr['bone'], {})[tr['channel']] = sample(tr['keys'], at)
    out = []
    for i, b in enumerate(model['bones']):
        p = pose.get(i, {}); m = np.eye(4)
        m[:3, :3] = euler(p.get('rotation', [0, 0, 0]))*np.array(p.get('scale', [1, 1, 1]))
        pv = np.array(b['pivot']); m[:3, 3] = pv+np.array(p.get('position', [0, 0, 0]))-m[:3, :3]@pv
        out.append(out[b['parent']]@m if b['parent'] >= 0 and b['parent'] != i else m)
    return np.array(out)


def bone_index(model, name):
    return next(i for i, b in enumerate(model['bones']) if b['name'] == name)


def hull_frame():
    """The captain's model space around her seat in the floating aquatic hull (water plane, brim top, outer side edge)."""
    V = json.loads(VESSEL.read_text(encoding='utf-8')); k = V['aquatic_scale']; bowl = V['bowl']
    seat_z = V['opening'][3]*k-5.9
    outer = max(r[1] for r in V['brim_rects'])
    return dict(water=-bowl['owner_y']*k, brim_top=(bowl['freeboard']-bowl['owner_y'])*k, brim_x=outer*k,
                hull_z=(-seat_z-max(abs(r[3]) for r in V['brim_rects'])*k, -seat_z+max(abs(r[3]) for r in V['brim_rects'])*k))


def paddle_targets(action, t, c, env):
    """Blade-end target (y, z) in the captain's model space and the twig's feather angle about its own axis."""
    p = c['paddle']; water = env['water']
    if action != 'boat_paddle':
        return water+p['rest_height'], p['rest_z'], p['rest_feather']
    u = paddle_phase(t, c)
    if u < .5:
        q = smooth(u/.5); wet = smooth(u/.09)*(1-smooth((u-.4)/.1))
        y = water+p['air']*(1-wet)-p['depth']*wet; z = lerp(p['catch_z'], p['finish_z'], q); feather = 0.
    else:
        q = smooth((u-.5)/.5); y = water+p['air']+p['arc']*bump(q); z = lerp(p['finish_z'], p['catch_z'], q)
        feather = p['feather']*smooth((u-.5)/.12)*(1-smooth((u-.9)/.1))
    return y, z, feather


def paddle_tracks(model, action, samples, tracks, c):
    """Aim the tuft along the twig and key the twig in the tuft's frame. Returns (tip track, prop block, clearance report)."""
    p = c['paddle']; env = hull_frame()
    braid, tip = bone_index(model, 'hand_right'), bone_index(model, 'hand_right_tip')
    geometry = json.loads(BRAID.read_text(encoding='utf-8'))
    joint_rest = np.array(model['bones'][tip]['pivot'], float); tip_rest = np.array(geometry['braid_tip'], float)
    reach = p['grip_offset']+p['blade_length']
    tip_rows, prop_rows, worst, where = [], [], 1e9, None
    for t in samples:
        M = palette(model, tracks, t)
        J = (M[braid]@np.r_[joint_rest, 1])[:3]
        y, z, feather = paddle_targets(action, t, c, env)
        dy, dz = y-J[1], z-J[2]
        if dy*dy+dz*dz > (reach*.97)**2:
            scale = reach*.97/math.hypot(dy, dz); dy *= scale; dz *= scale
        dx = math.sqrt(max(0., reach*reach-dy*dy-dz*dz))
        d = np.array([dx, dy, dz])/reach
        # Tuft: the rotation (about its own pivot, in the braid's frame) that lays it along the twig.
        Rb = polar(M[braid][:3, :3]); Rt = aim(tip_rest-joint_rest, Rb.T@d)
        tip_euler = to_euler(Rt); tip_rows.append(tip_euler)
        local = np.eye(4); local[:3, :3] = euler(tip_euler); local[:3, 3] = joint_rest-local[:3, :3]@joint_rest
        Mt = M[braid]@local
        G = J+d*p['grip_offset']
        # Twig frame: +Y toward its cut top (away from the blade), Z the blade normal facing the stroke, then feathered.
        y_axis = -d; z_axis = np.array([0., 0., 1.])-y_axis*y_axis[2]; z_axis /= np.linalg.norm(z_axis)
        x_axis = np.cross(y_axis, z_axis); Rw = np.c_[x_axis, y_axis, z_axis]@euler([0, feather, 0])
        A = Mt@np.r_[np.c_[np.eye(3), joint_rest], [[0, 0, 0, 1]]]
        local = np.linalg.inv(A)@np.r_[np.c_[Rw, G], [[0, 0, 0, 1]]]
        prop_rows.append([*local[:3, 3], *to_euler(polar(local[:3, :3]))])
        # Clearance of the stick axis from the hull's outer brim edge (only where it is beside the hull).
        for q in np.linspace(-p['top_length'], p['blade_length'], 24):
            P = G+d*q
            if env['hull_z'][0] < P[2] < env['hull_z'][1]:
                gap = max(P[0]-env['brim_x'], P[1]-env['brim_top'])
                if gap < worst: worst = gap; where = (round(t, 2), round(float(q), 1), P.round(2), J.round(2))
    tip_rows, prop_rows = unwrap(tip_rows), [r[:3]+e for r, e in zip(prop_rows, unwrap([r[3:] for r in prop_rows]))]
    track = {'bone': tip, 'channel': 'rotation', 'keys': [[round(t, 8), *[round(v, 5) for v in row]] for t, row in zip(samples, tip_rows)]}
    prop = {'prop': 'paddle_stick', 'bone': 'hand_right_tip',
            'keys': [[round(t, 8), *[round(float(v), 5) for v in row]] for t, row in zip(samples, prop_rows)]}
    return track, prop, (worst, where)


def extras(model, action, samples, tracks, c):
    """Extra bone tracks and clip fields (props, markers, contacts) for the clips owned here."""
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    more, fields = [], {}
    if 'markers' in c: fields['markers'] = dict(c['markers'])
    if 'base_pose' in c: fields['base_pose'] = c['base_pose']  # where the presentation watchdog falls back to
    if 'contacts' in c: fields['contacts'] = json.loads(json.dumps(c['contacts']))
    if action in PADDLE_CLIPS and 'hand_right_tip' in names and 'paddle' in c:
        track, prop, (worst, where) = paddle_tracks(model, action, samples, tracks, c)
        more.append(track); fields['props'] = {'paddle': prop}
        fields.setdefault('markers', {})
        if worst < c['paddle']['min_clearance']:
            raise ValueError(f'{model["id"]} {action}: the stick passes {-worst:.2f} units inside the brim (t, along, point, joint) {where}')
        print(f'  {action}: stick clears the brim edge by {worst:.2f} model units', flush=True)
    rotation = None if action in PADDLE_CLIPS else [tip_rotation(action, t, c) for t in samples]
    if rotation and rotation[0] is not None and 'hand_right_tip' in names:
        more.append({'bone': names['hand_right_tip'], 'channel': 'rotation',
                     'keys': [[round(t, 8), *[round(v, 5) for v in row]] for t, row in zip(samples, rotation)]})
    return more, fields


def braid_tracks(model, action, samples, c):
    names = {b['name']: i for i, b in enumerate(model['bones'])}; out = []
    for bone, side in (('hand_right', 1), ('hand_left', -1)):
        if bone not in names: continue
        rows = [braid_rotation(action, t, c, side) for t in samples]
        if rows[0] is None: continue
        out.append({'bone': names[bone], 'channel': 'rotation', 'keys': [[round(t, 8), *[round(v, 5) for v in row]] for t, row in zip(samples, rows)]})
    return out
