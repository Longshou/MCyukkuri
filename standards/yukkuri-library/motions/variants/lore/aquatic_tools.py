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
import json, math, sys
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'contact-safe'))
import braid_chain

ROOT = Path(__file__).resolve().parents[5]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
BRAID = ASSETS/'yukkuri_presentation/braid_rescue.json'
BOUNDS = json.loads((ASSETS/'yukkuri_presentation/hull_bounds/aquatic_basin_hat_turned.json').read_text(encoding='utf-8'))
RECIPE = json.loads((Path(__file__).resolve().parent/'recipe.json').read_text(encoding='utf-8'))
SHARED, BOAT_BRAID = RECIPE['paddle_shared'], RECIPE['boat_braid']
PULL = (0., 1.)  # the whole power half, as fractions of power_start..power_end, where the blade and the tuft must be under water
FOLLOW = json.loads((Path(__file__).resolve().parents[1]/'contact-safe/recipe.json').read_text(encoding='utf-8'))['braid_chain']

PADDLE_CLIPS = ('boat_paddle', 'hat_brim_offer', 'boat_nudge_young')
NEW_CLIPS = ('braid_cradle_lift', 'be_cradled', 'mouth_bite_hold', 'boat_thrown', 'water_hat_climb', 'water_hat_right',
             'boat_cargo_stow', 'boat_cargo_fetch', 'boat_jettison', 'boat_rim_peek', 'braid_rescue_reach', 'braid_rescue_hold',
             'braid_rescue_settle', 'boat_board', 'boat_feed_young', 'hat_hop_up', 'boat_drift', 'boat_steady', 'kid_brim_splash',
             'kid_mimic_paddle', 'kid_poke_stores', 'kid_hide_behind', 'bank_beckon')
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

def paddle_spec(c):
    """The clip's paddle block over the shared one (`aim` merges one level)."""
    p = dict(SHARED, **c['paddle']); p['aim'] = dict(SHARED['aim'], **c['paddle']['aim']); return p


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
        # shoulder twist toward the braid side, the body leading the braid by `body_lead_seconds`; the recovery
        # straightens. Pitch -5..+.5, roll <= 1 (more would dip the held-out braid onto the gunwale). No aft shift and
        # little lean back: at the stern seat her rear hair is at the lily-pad hull's lip when she turns.
        u = paddle_phase(t+c['body_lead_seconds'], c); power = u < .5; q = smooth(u/.5) if power else smooth((u-.5)/.5)
        drive = bump(u/.5) if power else 0.
        s = [c['width']+.02*drive, c['height']-.03*drive, c['width']+.015*drive]
        r = [lerp(-5, .5, q) if power else lerp(.5, -5, q), lerp(-7, 6, q) if power else lerp(6, -7, q), -.5-.5*drive]
    elif action == 'bank_beckon':
        # Ashore at the water's edge (Beckon): she turns her braid side to the water before her (yaw is free), leans out over the
        # edge toward that side and flattens while the tuft is in the water (press: the stroke's depth share), so the braid
        # reaches under the surface; she rises as it lifts out, swaying a little with the stroke. Tilt <= 12; braid_path 'stroke'.
        u = paddle_phase(t, c); b = c['bank']; press = min(1., max(0., -stroke(u, b)[0]/b['depth']))
        s = [1.03+.05*press, .72-.14*press, 1.02+.05*press]
        r = to_euler(euler([0, b['turn']+4*math.sin(2*math.pi*u), 0])@euler([-2-1.5*press, 0, -8.5-1.5*press]))
        shift[1] = -b['lean_out']
    elif action == 'braid_cradle_lift':
        # Reach toward the young beside the hull (lean and look to the braid side), scoop, lift it in front of her and set it
        # down, watching it the whole way. Lean <= 6; the yaw follows the braid round to the front, the body edging forward
        # off the stern wall while she turns so her rear hair stays clear of the lip.
        lean = keyed(t, [(0, 0), (.5, 1), (.8, 1), (1.5, .4), (2.0, .6), (2.4, 0)])
        effort = bump((t-.8)/.7)
        s = [1+.03*lean+.03*effort, 1-.05*lean-.05*effort, 1+.02*lean+.03*effort]
        r = [-4*lean, keyed(t, [(0, 0), (.5, -14), (.8, -14), (1.3, -8), (1.8, -6), (2.4, 0)]), -5*lean*(1-smooth((t-.8)/.6))]
        shift[0] = .25*lean; shift[1] = -.6*lean
    elif action == 'be_cradled':
        # A young held in the curled tuft: sags into the scoop (wider, lower), a little limp, swaying with the carrier.
        w = 2*math.pi*t/d
        s = [1.07+.012*math.sin(w), .88-.015*math.sin(w), 1.06]; r = [4+1.5*math.sin(w+.7), 0, 3*math.sin(w)]
    elif action == 'mouth_bite_hold':
        # Bite and hold a brim or a young's headwear: head forward to the contact, then two hard backward yanks per loop
        # (quick heave back with the whole body squashed and widened, slower ease forward) with a yaw worry. Pitch -8..-3.
        u = (t % (d/2))/(d/2); tug = smooth(u/.3) if u < .3 else 1-smooth((u-.3)/.7)
        s = [1+.12*tug, .93-.17*tug, 1+.1*tug]; r = [-8+6*tug, 10*math.sin(2*math.pi*t/d), 2*math.sin(4*math.pi*t/d)]
        shift[1] = -1.6+4.2*tug
    elif action == 'boat_thrown':
        # Pitched out as the hat capsizes: a stretched startle with a half yaw spin and a wobble (pitch + roll <= 12),
        # then the splash squash. The server owns the arc; no lift is added.
        spin = smooth(t/.8); splash = bump((t-.82)/.38)
        s = [.94+.13*splash, 1.1-.08*smooth(t/.8)-.2*splash, .94+.11*splash]
        r = [-4*math.sin(math.pi*min(1, t/.8))+3*splash, 200*spin, 7*math.sin(2*math.pi*t/.55)*(1-smooth((t-.7)/.3))]
        shift[1] = -.71*(1-math.cos(math.radians(r[1])))  # the spin turns about the root pivot, .71 behind the body's middle
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
    elif action == 'boat_rim_peek':
        # A baby on the bow perch by day leaning out over the front rim to look at the water: stretched 4 % toward it,
        # tipped 7 degrees forward, scanning left and right, an excited little bob, then one glance back at its mother
        # over its shoulder (the body turns 60 degrees, the lean relaxes, it stays forward off the wall) and back to the water.
        look_back = keyed(t, [(0, 0), (3.5, 0), (3.8, 1), (4.3, 1), (4.7, 0)])
        lean = 1-look_back; bob = bump((t-1.0)/.35)+bump((t-1.45)/.35)
        s = [1-.01*lean+.03*bob, 1+.04*lean-.06*bob, 1-.01*lean+.02*bob]
        r = [-7*lean, keyed(t, [(0, 0), (.8, -14), (1.6, -14), (2.3, 12), (3.1, 12), (3.5, 0)])+60*look_back,
             1.5*math.sin(2*math.pi*t/d)*lean]
        lift = .04*height*(bump((t-1.1)/.3)+bump((t-1.55)/.3))  # two little hops of excitement
        shift[1] = -.35
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
    elif action == 'kid_brim_splash':
        # A child on the brim edge, braid side to the water (boat_rim_peek's lean and bob): it rolls out up to 10 degrees
        # (pitch -2, so <= 12) and turns a little toward the water while the braid (braid_path) rises and slaps down twice,
        # a delighted bounce after each slap, then it sits back up. Free of floor support: it leans about its bottom.
        lean = keyed(t, [(0, 0), (.35, 1), (1.85, 1), (2.3, 0)])
        bob = bump((t-.62)/.3)+.7*bump((t-1.42)/.28)
        s = [1+.04*bob, 1-.07*bob+.02*lean, 1+.03*bob]
        r = [-2*lean, -8*lean+3*math.sin(2*math.pi*t/d), -10*lean]
    elif action == 'kid_mimic_paddle':
        # boat_paddle's stroke copied by a child without a stick: lean into the catch, an overdone shoulder twist through
        # the power half, a clumsy wobble mid-stroke, then the recovery; it rolls out 7 degrees in the pull so its braid
        # tip skims the water. Pitch -2.5..+.5, roll <= 8.
        u = paddle_phase(t, c); power = u < .5; q = smooth(u/.5) if power else smooth((u-.5)/.5)
        drive = bump(u/.5) if power else 0.; wobble = bump((u-.28)/.16)
        s = [1+.03*drive, 1-.05*drive, 1+.02*drive]
        r = [lerp(-2.5, .5, q) if power else lerp(.5, -2.5, q), lerp(-10, 9, q) if power else lerp(9, -10, q),
             -7*drive+2.5*wobble*math.sin(2*math.pi*u*6)]
    elif action == 'kid_poke_stores':
        # A young pokes at the stores on the basin floor (boat_cargo_fetch's head-down dip, eat's peck): three pecks with the
        # mouth (markers peck_1..3), the braid tapping the floor between, then a pleased wiggle. Pitch to -9, roll <= 2.
        pecks = [.35, .8, 1.25]
        dip = max(bump((t-at+.18)/.36) for at in pecks)
        s = [1+.04*dip, 1-.09*dip, 1+.05*dip]
        r = [-9*dip, keyed(t, [(0, 0), (.6, -6), (1.05, 5), (1.5, 0), (d, 0)])+6*math.sin(2*math.pi*(t-1.5)/.25)*bump((t-1.5)/.5),
             2*math.sin(2*math.pi*t/.45)*bump((t-1.5)/.5)]
        shift[1] = -.3*dip  # mostly down: a large young sits with its front at the basin wall
    elif action == 'kid_hide_behind':
        # Scared: a quick crouch-spring scoot toward its mother (the server moves it; boat_alarm_wait / boat_flee), then it
        # cowers low and wide under her braid, trembling, peeking out once. Pitch <= 5, roll <= 3.
        crouch = keyed(t, [(0, 0), (.12, 1), (.2, -.4), (.42, .8), (.6, 1.2), (d, 1.2)])
        tremble = math.sin(2*math.pi*7*t)*smooth((t-.6)/.2)*(1-.6*bump((t-1.5)/.5))
        s = [1+.06*max(0, crouch), 1-.13*max(0, crouch)+.08*max(0, -crouch)+.008*tremble, 1+.05*max(0, crouch)]
        r = [-4*max(0, crouch)+3*bump((t-1.5)/.5), keyed(t, [(0, 0), (.2, -14), (.6, -10), (1.5, -10), (1.75, 6), (2.05, -10), (d, -10)]),
             2*tremble]
        lift = .05*height*bump((t-.2)/.22)  # the little scoot hop; the server owns the move across
    else:
        return None
    return s, r, lift, shift


# ---- braid tracks -----------------------------------------------------------------------------------------------------

def braid_rotation(action, t, c, side):
    """Rotation of the braid root (hand_right side=1, hand_left side=-1) or None to leave that lock at rest."""
    d = c['duration']
    if action in AUTHORED: t, d = t*AUTHORED[action]/d, AUTHORED[action]
    if action == 'boat_paddle' and side > 0:
        # The root swings a little forward at the catch and aft through the pull (the lower braid does the rest, paddle_tracks).
        u = paddle_phase(t, c); power = u < .5; q = smooth(u/.5) if power else smooth((u-.5)/.5)
        sweep = lerp(c['braid_sweep'][0], c['braid_sweep'][1], q) if power else lerp(c['braid_sweep'][1], c['braid_sweep'][0], q)
        return [sweep, 0, c['braid_out']]
    if action == 'braid_cradle_lift' and side < 0:
        return [0, 0, -(8+4*bump(t/d))]  # the braid itself follows the clip's braid_path
    if action == 'be_cradled':
        return [0, 0, side*(14+3*math.sin(2*math.pi*t/d))]  # limp locks hanging off the scoop
    if action == 'mouth_bite_hold':
        u = (t % (d/2))/(d/2); tug = smooth(u/.3) if u < .3 else 1-smooth((u-.3)/.7); return [-5*tug, 0, side*(8+10*tug)]
    if action == 'boat_thrown':
        # flung up and out, high over the brim while she spins
        flail = math.sin(2*math.pi*t/.42)*(1-smooth((t-.8)/.3)); return [-20*flail*side, side*6, side*(64+14*abs(flail))]
    if action == 'water_hat_climb':
        if side < 0: return [0, 0, -(10+6*bump((t-.85)/.9))]
        # Forward and up over the brim, then hold the hook while the body pulls.
        return [keyed(t, [(0, 0), (.3, 30), (.75, 62), (1.8, 58)]), 0, keyed(t, [(0, 0), (.3, 20), (.75, 8), (1.8, 6)])]
    if action == 'water_hat_right':
        if side < 0: return [0, 0, -(10+6*bump((t-.6)/1.0))]
        # Rest, then swing forward-up under the lifting far brim and push it over.
        return [keyed(t, [(0, 0), (.8, 20), (1.0, 55), (1.4, 92), (1.7, 70), (2.0, 0)]), 0,
                keyed(t, [(0, 0), (.8, 18), (1.2, 10), (1.6, 8), (2.0, 0)])]
    if action == 'boat_jettison' and side > 0:
        return [keyed(t, [(0, 0), (.3, 30), (.6, 8), (1.0, 0)]), 0, keyed(t, [(0, 0), (.3, 14), (.6, 44), (1.0, 0)])]
    if action in ('boat_cargo_stow', 'boat_cargo_fetch'):
        return [0, 0, side*(10+6*bump(t/c['duration']))]  # locks swing aside as the head goes down
    return None


def tip_rotation(action, t, c):
    """Rotation of the braid tuft (hand_right_tip) for the curl, hook and push; paddle clips aim it along the stick instead."""
    if action in AUTHORED: t = t*AUTHORED[action]/c['duration']
    if action == 'boat_jettison':
        flat = keyed(t, [(0, 0), (.3, 1), (.6, 1), (1.0, 0)]); return [-30*flat, 0, 0]
    if action == 'water_hat_climb':
        hook = keyed(t, [(0, 0), (.55, .2), (.8, 1), (1.8, 1)]); return [70*hook, 0, -10*hook]  # tuft folds down over the rim
    if action == 'water_hat_right':
        flat = keyed(t, [(0, 0), (.9, 0), (1.1, 1), (1.6, 1), (2.0, 0)]); return [-35*flat, 0, 0]  # open tuft pressing like a palm
    return None


# ---- braid tip paths ---------------------------------------------------------------------------------------------------

def stroke(u, p):
    """A water stroke at phase u (paddle_phase): height over the water and fore-aft. The power half (power_start..power_end)
    runs under the water by `depth` from `catch_z` to `finish_z`; the recovery lifts out over its first `entry` of the
    phase to `air` plus an `arc`, swings forward and drops back in over its last `entry`, so the loop closes."""
    if u < .5: return -p['depth'], lerp(p['catch_z'], p['finish_z'], smooth(u/.5))
    q, e = (u-.5)/.5, p['entry']/.5; out = smooth(q/e)*(1-smooth((q-1+e)/e))
    return -p['depth']*(1-out)+(p['air']+p['arc']*bump(q))*out, lerp(p['finish_z'], p['catch_z'], smooth(q))


def path_target(t, c):
    """The braid_path tuft-end target at t (model units in the clip's seat frame): keys [t, x, y, z], or [t, 'rest'] for
    the shared boat_braid rest tuck, eased between; 'stroke' strokes the water before a bank (`bank`, in the bank's frame)."""
    if c['braid_path'] == 'stroke':
        b = c['bank']; y, z = stroke(paddle_phase(t, c), b); return np.array([b['x'], b['water']+y, z])
    keys = [(k[0], np.array(BOAT_BRAID['rest'] if k[1] == 'rest' else k[1:4], float)) for k in c['braid_path']]
    return keyed(t, keys)


def path_tracks(model, action, samples, tracks, c):
    """Rotation tracks of every braid chain bone that lay the tuft's end (its farthest vertex from the root) on the clip's braid_path, bent as the game bends it (braid_chain.arc). The target turns with the
    body's yaw about the root pivot and moves with its x/z shift, so a tuck stays where she sits; height is the seat's.
    The tuft keeps the clip's own tip_rotation (curl, hook) on top. A bank stroke's target stays in the bank's frame, and its
    root lock keeps its natural size on her squashed body (the inverse of her scale, unturned), the braid below it reaching
    as far as it really is long."""
    bones = model['bones']; names = {b['name']: i for i, b in enumerate(bones)}; chain = braid_chain.chain(names)
    body = names['root']; pivot = np.array(bones[body]['pivot'], float)
    own = {tr['channel']: tr['keys'] for tr in tracks if tr['bone'] == body}; lock = []
    if 'bank' in c:
        lock = [{'bone': chain[0], 'channel': 'scale', 'keys': [[k[0], *[round(1/v, 5) for v in k[1:]]] for k in own['scale']]}]
        chain = chain[1:]; tracks = tracks+lock
    V = np.array(model['vertices']).reshape(-1, 8)[:, :3]
    tuft = np.concatenate([V[p['start']:p['start']+p['count']] for p in model['parts'] if p['name'].startswith('braid_lock_tuft')])
    top = np.array(bones[chain[0]]['pivot'], float); end = tuft[np.argmax(np.linalg.norm(tuft-top, axis=1))]
    rest = np.array([bones[i]['pivot'] for i in chain]+[end.tolist()], float)
    rows, last = {i: [] for i in chain}, {}
    for t in samples:
        yaw = sample(own['rotation'], t)[1] if 'rotation' in own else 0.
        shift = sample(own['position'], t) if 'position' in own else [0., 0., 0.]
        x, y, z = path_target(t, c); flat = euler([0, yaw, 0])@(np.array([x, 0., z])-[pivot[0], 0., pivot[2]])
        world = np.array([x, y, z]) if 'bank' in c else np.array([flat[0]+pivot[0]+shift[0], y, flat[2]+pivot[2]+shift[2]])
        M = palette(model, tracks, t); local = (np.linalg.inv(M[bones[chain[0]]['parent']])@np.r_[world, 1])[:3]
        solved = braid_chain.rotations(rest, braid_chain.arc(rest, local))
        curl = tip_rotation(action, t, c)
        if curl is not None: solved[-1] = solved[-1]@euler(curl)
        for i, R in zip(chain, solved):
            last[i] = braid_chain.to_euler(R, last.get(i)); rows[i].append([round(t, 8), *[round(v, 5) for v in last[i]]])
    return lock+[{'bone': i, 'channel': 'rotation', 'keys': rows[i]} for i in chain]


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


_FORMS = {}


def hull_forms(units, profiles=None):
    """Hull sections and fixed presentation waterline, viewed from the captain's seat (model units)."""
    if profiles is None and units in _FORMS: return _FORMS[units]
    forms = []
    if profiles is None:
        b = BOUNDS; f = b['floor']; m = b['mouth']['radius']; r = b['brim']; c = b['captain_seat']
        profiles = [dict(name=b['hull'], waterline_y=b['waterline_y'], seat=[c['x'], c['y'], c['z']],
                         section=[[0, b['keel_y']], [f['radius'], b['keel_y']], [r['outer_radius'], r['edge_y']],
                                  [r['level_radius'], r['y']], [m, r['y']], [f['radius'], f['y']], [0, f['y']]])]
    for h in profiles:
        sx, seat, sz = h['seat']; ring = (np.array(h['section'], float)-[0, seat])*units
        forms.append(dict(name=h['name'], azimuths=np.radians(np.arange(0., 360., 45.)), rings=[ring]*8,
                          water=(h['waterline_y']-seat)*units, seat=(sx*units, sz*units)))
    if profiles is None: _FORMS[units] = forms
    return forms


def clearance(form, P, yaw):
    """Signed distance (model units) of the points P from the hull solid with the captain turned `yaw` degrees on her
    seat (the form's seat offset from the hull's middle); negative inside. `yaw` may be several angles: the result then
    holds the points once per angle."""
    a = np.radians(np.atleast_1d(np.asarray(yaw, float)))[:, None]; sx, sz = form['seat']
    x = (P[:, 0]*np.cos(a)-P[:, 2]*np.sin(a)+sx).ravel(); z = (P[:, 0]*np.sin(a)+P[:, 2]*np.cos(a)+sz).ravel()
    if 'edges' not in form:   # each ray's ring edges, once per form
        form['edges'] = []
        for A in form['rings']:
            AB = np.roll(A, -1, 0)-A; rise = np.where(AB[:, 1] == 0, 1e-12, AB[:, 1])
            form['edges'].append((A, AB, A+AB, 1/np.maximum((AB*AB).sum(-1), 1e-12), AB[:, 0]/rise))
    ray = np.argmin(np.abs((np.arctan2(z, x)[:, None]-form['azimuths'][None]+math.pi) % (2*math.pi)-math.pi), axis=1)
    Q = np.c_[np.hypot(x, z), np.tile(P[:, 1], len(a))]; out = np.empty(len(Q))
    for k in np.unique(ray):
        sel = ray == k; q = Q[sel][:, None]; A, AB, B, inv, slope = form['edges'][k]; d = q-A
        s = np.clip((d*AB).sum(-1)*inv, 0, 1)
        gap = np.sqrt((((d-s[..., None]*AB))**2).sum(-1).min(1))
        crosses = ((A[:, 1] > q[..., 1]) != (B[:, 1] > q[..., 1])) & (q[..., 0] < A[:, 0]+(q[..., 1]-A[:, 1])*slope)
        out[sel] = np.where(crosses.sum(1) % 2 == 1, -gap, gap)
    return out


def paddle_targets(action, t, c, water):
    """Blade-end target (y, z) in the captain's model space and the twig's feather angle about its own axis."""
    p = paddle_spec(c)
    if action != 'boat_paddle':
        return water+p['rest_height'], p['rest_z'], p['rest_feather']
    u = paddle_phase(t, c); y, z = stroke(u, p)
    return water+y, z, p['feather']*smooth((u-.5)/.12)*(1-smooth((u-.9)/.1)) if u >= .5 else 0.


def paddle_tracks(model, action, samples, tracks, c):
    """Lay the twig where the braid really carries it. The braid stroke runs down its chain first (braid_chain.follow,
    loaded through the power half by `paddle.braid_load`); then the last joints (`paddle.aim.bones`) share the turn that
    heads the tuft (in the sweep plane, about the vertical) toward the twig's blade target (`shares`, tail last), each
    `lag_seconds` after the joint above and none turning faster than `degrees_per_second`. The twig hangs from the wrap at the tuft's grip at its own pitch, so the
    braid stays held out over the gunwale. The blade aims under the lowest waterline of the hull forms.
    Returns (rotation tracks of every chain joint below the root, prop block, per-sample twig (grip, direction))."""
    p = paddle_spec(c); water = min(f['water'] for f in hull_forms(model['units_per_block']))
    names = {b['name']: i for i, b in enumerate(model['bones'])}; bones = model['bones']; chain = braid_chain.chain(names)
    load = p.get('braid_load')  # only boat_paddle pulls against the water; the held-out clips have no load window
    spec = dict(FOLLOW, load=dict(load, start=c['markers']['power_start'], end=c['markers']['power_end'])) if load else FOLLOW
    followed = braid_chain.follow(chain, {'duration': c['duration'], 'loop': c['loop'], 'tracks': tracks}, spec)
    # The braid hangs from its root and its lower joints bend outboard (`curl`, degrees per joint below the root), so it
    # reaches over the gunwale tip first instead of lifting stiff from the head; boat_paddle's recovery curls the tail
    # further (`braid_raise`, a share of the curl), lifting the tip out of the water as the stick swings forward.
    lift = lambda t: 1+c['braid_raise']*bump((paddle_phase(t, c)-.5)/.5) if action == 'boat_paddle' else 1.
    own = {t['bone']: dict(t, keys=[[k[0], *braid_chain.to_euler(braid_chain.euler(k[1:])@braid_chain.euler([0, 0, a*lift(k[0])]))] for k in t['keys']])
           for t, a in zip(sorted((t for t in followed['tracks'] if t['bone'] in chain[1:]), key=lambda t: chain.index(t['bone'])), p['curl'])}
    tip = chain[-1]; aim = [names[n] for n in p['aim']['bones']]; share = p['aim']['shares']
    base = tracks+[own[b] for b in chain[1:] if b not in aim]
    joint_rest = np.array(bones[tip]['pivot'], float)
    tuft = np.array(json.loads(BRAID.read_text(encoding='utf-8'))['braid_tip'], float)-joint_rest; tuft /= np.linalg.norm(tuft)
    flat = lambda v: np.array([v[0], 0., v[2]])
    held = lambda local: [{'bone': b, 'channel': 'rotation', 'keys': [[0., *braid_chain.to_euler(local[b])]]} for b in aim]

    def twig(M, y, z):
        """Tuft direction, grip and twig direction (blade end on the target) for the bone matrices M."""
        u = polar(M[tip][:3, :3])@tuft; G = (M[bones[tip]['parent']]@np.r_[joint_rest, 1])[:3]+u*p['grip_offset']
        dy, dz, reach = y-G[1], z-G[2], p['blade_length']
        if dy*dy+dz*dz > (reach*.97)**2:
            scale = reach*.97/math.hypot(dy, dz); dy *= scale; dz *= scale
        return u, G, np.array([math.sqrt(max(0., reach*reach-dy*dy-dz*dz)), dy, dz])/reach
    rows = []
    for t in samples:
        local = {b: braid_chain.euler(sample(own[b]['keys'], t)) for b in aim}
        y, z, _ = paddle_targets(action, t, c, water)
        for _ in range(4):  # the grip moves as the joints above it turn
            M = palette(model, base+held(local), t); u, _, d = twig(M, y, z)
            Q = braid_chain.aim(flat(u), flat(d))
            for b, s in zip(aim, share):
                Rp = polar(M[bones[b]['parent']][:3, :3]); local[b] = Rp.T@braid_chain.partial(Q, s)@Rp@local[b]
        rows.append(local)
    # Each aimed joint takes its share `lag_seconds` after the one above it, so the turn also runs down the tail.
    dt = samples[1]-samples[0]; lag, n = round(p['aim']['lag_seconds']/dt), len(samples)
    for j, b in enumerate(aim):
        rest = [braid_chain.euler(sample(own[b]['keys'], t)) for t in samples]; turn = [r[b]@q.T for r, q in zip(rows, rest)]
        at = (lambda i: (i-j*lag) % (n-1)) if c['loop'] else (lambda i: max(0, i-j*lag))
        for i in range(n): rows[i][b] = turn[at(i)]@rest[i]
    limited = {b: braid_chain.rate_limited([r[b] for r in rows], p['aim']['degrees_per_second']*dt, c['loop']) for b in aim}
    keys, prop_rows, sticks, last = {b: [] for b in aim}, [], [], {}
    for n, t in enumerate(samples):
        local = {b: limited[b][n] for b in aim}
        y, z, feather = paddle_targets(action, t, c, water)
        M = palette(model, base+held(local), t); _, G, d = twig(M, y, z)
        for b in aim:
            last[b] = braid_chain.to_euler(local[b], last.get(b)); keys[b].append([round(t, 8), *[round(v, 5) for v in last[b]]])
        sticks.append((G, d))
        # Twig frame: +Y toward its cut top (away from the blade), Z the blade normal facing the stroke, then feathered.
        y_axis = -d; z_axis = np.array([0., 0., 1.])-y_axis*y_axis[2]; z_axis /= np.linalg.norm(z_axis)
        x_axis = np.cross(y_axis, z_axis); Rw = np.c_[x_axis, y_axis, z_axis]@euler([0, feather, 0])
        A = M[tip]@np.r_[np.c_[np.eye(3), joint_rest], [[0, 0, 0, 1]]]
        key = np.linalg.inv(A)@np.r_[np.c_[Rw, G], [[0, 0, 0, 1]]]
        prop_rows.append([*key[:3, 3], *to_euler(polar(key[:3, :3]))])
    prop_rows = [r[:3]+e for r, e in zip(prop_rows, unwrap([r[3:] for r in prop_rows]))]
    prop = {'prop': 'paddle_stick', 'bone': 'hand_right_tip',
            'keys': [[round(t, 8), *[round(float(v), 5) for v in row]] for t, row in zip(samples, prop_rows)]}
    out = [own[b] for b in chain[1:] if b not in aim]+[{'bone': b, 'channel': 'rotation', 'keys': keys[b]} for b in aim]
    return out, prop, sticks


def hull_gate(model, action, samples, tracks, sticks, c, profiles=None):
    """Fail the clip if the skinned braid (braid_lock_*) or the twig axis (less `twig_radius`) enters any hull form with
    the captain on its seat facing its bow within `captain_yaw` degrees (Paddle.FACING; every `captain_yaw_step`); boat_paddle's blade must also be under every
    form's waterline by half its `depth` through the middle of the pull. Prints per form the worst braid and twig clearance.
    `profiles`: hull dicts in memory instead of the exported files (hull_forms)."""
    p = paddle_spec(c); forms = hull_forms(model['units_per_block'], profiles); yaws = range(-SHARED['captain_yaw'], SHARED['captain_yaw']+1, p['captain_yaw_step'])
    V = np.array(model['vertices']).reshape(-1, 8)[:, :3]; W = np.array(model['weights'])
    braid = sorted({i for q in model['parts'] if q['name'].startswith('braid_lock_') for i in range(q['start'], q['start']+q['count'])})
    braid = np.unique(np.c_[V[braid], W[braid]].round(5), axis=0)
    hv = np.c_[braid[:, :3], np.ones(len(braid))]; a, b, w = braid[:, 3].astype(int), braid[:, 4].astype(int), braid[:, 5:6]
    along = np.linspace(-p['top_length'], p['blade_length'], 25)
    worst = {f['name']: [0, math.inf, math.inf, -math.inf] for f in forms}  # penetrating samples, braid, twig, blade under water
    if action == 'boat_paddle': m = c['markers']; core = [m['power_start']+q*(m['power_end']-m['power_start']) for q in PULL]
    for t, (G, d) in zip(samples, sticks):
        M = palette(model, tracks, t)
        P = (np.einsum('nij,nj->ni', M[a], hv)*(1-w)+np.einsum('nij,nj->ni', M[b], hv)*w)[:, :3]; S = G+np.outer(along, d)
        pull = action == 'boat_paddle' and core[0] <= t <= core[1]
        for f in forms:
            r = worst[f['name']]
            hair = clearance(f, P, yaws).min(); twig = clearance(f, S, yaws).min()-p['twig_radius']
            r[0] += hair < 0 or twig < 0; r[1] = min(r[1], hair); r[2] = min(r[2], twig)
            if pull: r[3] = f['water']-S[-1, 1] if r[3] == -math.inf else min(r[3], f['water']-S[-1, 1])
    failed = [name for name, (bad, _, _, wet) in worst.items() if bad or (action == 'boat_paddle' and not wet >= p['depth']/2)]
    for name, (bad, hair, twig, wet) in worst.items():
        print(f'  {action} in {name}: {bad}/{len(samples)} samples penetrate; braid clears by {hair:.2f}, twig by {twig:.2f} model units'
              + (f'; blade {wet:.2f} under water' if action == 'boat_paddle' else ''), flush=True)
    if failed:
        raise ValueError(f'{model["id"]} {action}: the braid or twig enters, or the blade misses the water of, {failed}')
    return worst


def body_part(model):
    """Her own body for standing ashore: no braid, cheek puffs or worn accessories (the hat is afloat while she beckons)."""
    hidden = tuple(json.loads((ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants'][model['id']]['accessory_prefixes'])
    return lambda n: not n.startswith(('braid_lock_', 'puff_')+hidden)


def bank_support(model, s, r, shift, edge):
    """Root lift (model units) that sets her lowest body point over the bank (no farther than `edge` toward the water, -z)
    on its top (y 0) under the root scale `s`, rotation `r` and [x, z] `shift`: she stands on the bank, leaning out over its
    edge, while the braid hanging below is laid into the water afterwards (path_tracks)."""
    pick = body_part(model); V = np.array(model['vertices']).reshape(-1, 8)[:, :3]
    P = np.unique(np.concatenate([V[p['start']:p['start']+p['count']] for p in model['parts'] if not p.get('anatomy') and pick(p['name'])]), axis=0)
    pivot = np.array(model['bones'][bone_index(model, 'root')]['pivot'], float)
    Q = (P-pivot)@(euler(r)*np.array(s)).T+pivot+[shift[0], 0, shift[1]]
    return -float(Q[-Q[:, 2] <= edge, 1].min())


def skinned(model, pick):
    """Unique skinned vertices of the non-anatomy parts whose names `pick` accepts: (homogeneous rest points, bone a, bone b, weight)."""
    V = np.array(model['vertices']).reshape(-1, 8)[:, :3]; W = np.array(model['weights'])
    keep = sorted({i for q in model['parts'] if not q.get('anatomy') and pick(q['name']) for i in range(q['start'], q['start']+q['count'])})
    rows = np.unique(np.c_[V[keep], W[keep]].round(5), axis=0)
    return np.c_[rows[:, :3], np.ones(len(rows))], rows[:, 3].astype(int), rows[:, 4].astype(int), rows[:, 5:6]


def posed(M, points):
    hv, a, b, w = points; return (np.einsum('nij,nj->ni', M[a], hv)*(1-w)+np.einsum('nij,nj->ni', M[b], hv)*w)[:, :3]


def prop_sticks(model, tracks, prop):
    """The twig as the game places it from the props.paddle keys: per key its grip and unit axis toward the blade."""
    tip = bone_index(model, prop['bone']); rest = np.array(model['bones'][tip]['pivot'], float); out = []
    for k in prop['keys']:   # the tuft joint's frame times the key
        M = palette(model, tracks, k[0]); A = M[tip]@np.r_[np.c_[np.eye(3), rest], [[0, 0, 0, 1]]]
        G = (A@np.r_[k[1:4], 1])[:3]; d = -(polar(A[:3, :3])@euler(k[4:7]))[:, 1]; out.append((G, d/np.linalg.norm(d)))
    return out


def stroke_gate(model, action, samples, tracks, c, prop=None):
    """Fail a water stroke (boat_paddle, bank_beckon) whose loop does not close (every track's and the props.paddle keys' last
    key on its first), whose braid below its root lock sinks more than TOUCH deeper into her body than at rest (its upper
    lobes already lie into the body surface there; along the outward normal at the nearest body point) or whose rendered
    twig (prop keys, less `twig_radius`) enters her body. bank_beckon also fails unless her lowest body point over the bank
    rests on its top (within TOUCH), no body point sinks into the bank or reaches the water, the braid stays out of the
    bank (below its top short of `edge` ahead) and its deepest point is under the water by half the stroke's `depth`
    through the whole power phase (PULL). Prints the worst values."""
    for tr in tracks+([dict(prop, bone=prop['bone'], channel='prop')] if prop else []):
        a, z = np.array(tr['keys'][0][1:]), np.array(tr['keys'][-1][1:])
        gap = abs(z-a) if tr['channel'] in ('position', 'scale') else np.r_[abs(z-a)[:3], abs((z-a+180) % 360-180)[3:]] if tr['channel'] == 'prop' else abs((z-a+180) % 360-180)
        if gap.max() > 1e-3: raise ValueError(f'{model["id"]} {action}: the loop does not close on {tr["bone"]} {tr["channel"]}')
    names = {b['name']: i for i, b in enumerate(model['bones'])}; lock = names['hand_right']
    braid = skinned(model, lambda n: n.startswith('braid_lock_')); hanging = ~((braid[1] == lock) & (braid[2] == lock))
    body = skinned(model, lambda n: 'body_surface' in n); whole = skinned(model, body_part(model))
    m = c['markers']; core = [m['power_start']+q*(m['power_end']-m['power_start']) for q in PULL]
    p = paddle_spec(c) if prop else None; along = np.linspace(-p['top_length'], p['blade_length'], 25) if prop else None
    sticks = prop_sticks(model, tracks, prop) if prop else [None]*len(samples)

    def outside(points, B):   # least signed distance of points out of the body B along its outward normal
        near = B[np.argmin(((points[:, None]-B[None])**2).sum(-1), axis=1)]
        out = (near-B.mean(0))/np.linalg.norm(near-B.mean(0), axis=1, keepdims=True); return float(((points-near)*out).sum(1).min())
    M = palette(model, [], 0); rest = outside(posed(M, braid)[hanging], posed(M, body))
    inside, twig, bank, wet, rests, sinks = math.inf, math.inf, math.inf, math.inf, [], math.inf
    for t, stick in zip(samples, sticks):
        M = palette(model, tracks, t); P = posed(M, braid); B = posed(M, body); inside = min(inside, outside(P[hanging], B)-rest)
        if stick: twig = min(twig, outside(stick[0]+np.outer(along, stick[1]), B)-p['twig_radius'])
        if 'bank' in c:
            b = c['bank']; W = posed(M, whole); over = -W[:, 2] <= b['edge']; rests.append(float(W[over, 1].min()))
            sinks = min(sinks, float(W[over, 1].min()), float(W[:, 1].min()-b['water']))
            below = P[:, 1] < 0
            if below.any(): bank = min(bank, float((-P[below, 2]).min()-b['edge']))
            if core[0] <= t <= core[1]: wet = min(wet, float(b['water']-P[:, 1].min()))
    print(f'  {action}: loop closes; braid clears her body by {inside:.2f} beyond rest'+(f', twig by {twig:.2f}' if prop else '')
          + (f'; body on the bank {min(rests):.2f}..{max(rests):.2f}, the braid clears the bank by {bank:.2f}; tuft {wet:.2f} under water' if 'bank' in c else ''), flush=True)
    if inside < -TOUCH or twig < -TOUCH or 'bank' in c and (max(abs(r) for r in rests) > TOUCH or sinks < -TOUCH or bank < 0 or wet < c['bank']['depth']/2):
        raise ValueError(f'{model["id"]} {action}: the braid or twig enters her body, she does not stand on the bank, or the braid enters the bank or misses the water')


def extras(model, action, samples, tracks, c):
    """Extra bone tracks and clip fields (props, markers, contacts) for the clips owned here."""
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    more, fields = [], {}
    if 'markers' in c: fields['markers'] = dict(c['markers'])
    if action == 'boat_paddle': fields['parameters'] = {'captain_yaw': SHARED['captain_yaw']}
    if 'base_pose' in c: fields['base_pose'] = c['base_pose']  # where the presentation watchdog falls back to
    if 'contacts' in c: fields['contacts'] = json.loads(json.dumps(c['contacts']))
    if action in PADDLE_CLIPS and 'hand_right_tip' in names and 'paddle' in c:
        braid, prop, sticks = paddle_tracks(model, action, samples, tracks, c)
        more += braid; fields['props'] = {'paddle': prop}
        fields.setdefault('markers', {})
        hull_gate(model, action, samples, tracks+braid, sticks, c)
    if 'braid_path' in c and braid_chain.chain(names):
        more += path_tracks(model, action, samples, tracks, c)
    if action in ('boat_paddle', 'bank_beckon') and 'hand_right_tip' in names:
        stroke_gate(model, action, samples, tracks+more, c, fields.get('props', {}).get('paddle'))
    rotation = None if action in PADDLE_CLIPS or 'braid_path' in c else [tip_rotation(action, t, c) for t in samples]
    if rotation and rotation[0] is not None and 'hand_right_tip' in names:
        more.append({'bone': names['hand_right_tip'], 'channel': 'rotation',
                     'keys': [[round(t, 8), *[round(v, 5) for v in row]] for t, row in zip(samples, rotation)]})
    return more, fields


def braid_tracks(model, action, samples, c):
    names = {b['name']: i for i, b in enumerate(model['bones'])}; out = []
    for bone, side in (('hand_right', 1), ('hand_left', -1)):
        if bone not in names or (side > 0 and 'braid_path' in c and braid_chain.chain(names)): continue
        rows = [braid_rotation(action, t, c, side) for t in samples]
        if rows[0] is None: continue
        out.append({'bone': names[bone], 'channel': 'rotation', 'keys': [[round(t, 8), *[round(v, 5) for v in row]] for t, row in zip(samples, rows)]})
    return out


# ---- gates on the exported clips: no re-authoring, any hull passed in -------------------------------------------------
_MODEL = {}
TOUCH = .1  # model units: a body resting on a hull surface may sink this far between the cut and the drawn mesh


def runtime_model():
    """The fitted runtime Marisa (placement_fit) the clips are authored on."""
    if not _MODEL:
        sys.path.insert(0, str(ROOT/'scripts')); import placement_fit
        _MODEL['m'] = placement_fit.fitted(ASSETS/'yukkuri/marisa.json', placement_fit.load())
    return _MODEL['m']


def exported(action):
    """An exported clip as the game reads it (motion_corrections.json): the model, its tracks on the model's bones, its
    samples (the paddle keys' times, else every recipe sample_seconds), the twig's grip and direction per sample from its
    paddle keys (None without) and its recipe entry."""
    model = runtime_model(); names = {b['name']: i for i, b in enumerate(model['bones'])}
    if 'clips' not in _MODEL: _MODEL['clips'] = json.loads((ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))['models']
    rev = _MODEL['clips'][model['id']]
    clip = rev['clips'][action]; cfg = json.loads((Path(__file__).resolve().parent/'recipe.json').read_text(encoding='utf-8'))
    tracks = [dict(t, bone=names[rev['bone_names'][t['bone']]]) for t in clip['tracks'] if rev['bone_names'][t['bone']] in names]
    prop = clip.get('props', {}).get('paddle'); sticks = None
    if prop:
        samples = [k[0] for k in prop['keys']]; sticks = prop_sticks(model, tracks, prop)
    else:
        n = math.ceil(clip['duration']/cfg['sample_seconds']); samples = [clip['duration']*i/n for i in range(n+1)]
    return model, tracks, samples, sticks, cfg['clips'][action]


def gate_exported(action, profiles=None):
    """hull_gate on the exported clip (same samples, tracks and twig as the game reads), against the exported hull
    profiles or the `profiles` passed in."""
    model, tracks, samples, sticks, c = exported(action)
    return hull_gate(model, action, samples, tracks, sticks, c, profiles)


def body_clearance(action, profiles=None, yaws=None):
    """The whole posed Marisa of an exported clip against each hull form, on the captain's seat turned within `yaws`
    degrees (default the paddle captain_yaw sweep): per form and part (`braid`: braid_lock_*; `body`: body and hair, no
    hat or accessories) the samples that sink more than TOUCH into the hull and the least clearance (model units)."""
    model, tracks, samples, _, _ = exported(action)
    hidden = tuple(json.loads((ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants'][model['id']]['accessory_prefixes'])
    V = np.array(model['vertices']).reshape(-1, 8)[:, :3]; Wt = np.array(model['weights']); sets = {}
    for part, pick in (('body', lambda n: not n.startswith(('braid_lock_',)+hidden)), ('braid', lambda n: n.startswith('braid_lock_'))):
        keep = sorted({i for q in model['parts'] if not q.get('anatomy') and pick(q['name']) for i in range(q['start'], q['start']+q['count'])})
        rows = np.unique(np.c_[V[keep], Wt[keep]].round(3), axis=0)
        sets[part] = (np.c_[rows[:, :3], np.ones(len(rows))], rows[:, 3].astype(int), rows[:, 4].astype(int), rows[:, 5:6])
    yaws = range(-SHARED['captain_yaw'], SHARED['captain_yaw']+1, SHARED['captain_yaw_step']) if yaws is None else yaws
    forms = hull_forms(model['units_per_block'], profiles)
    out = {f['name']: {part: [0, math.inf] for part in sets} for f in forms}
    for t in samples:
        M = palette(model, tracks, t)
        for part, (hv, a, b, w) in sets.items():
            P = (np.einsum('nij,nj->ni', M[a], hv)*(1-w)+np.einsum('nij,nj->ni', M[b], hv)*w)[:, :3]
            for f in forms:
                gap = clearance(f, P, yaws).min(); r = out[f['name']][part]; r[0] += gap < -TOUCH; r[1] = min(r[1], gap)
    return {k: {part: dict(penetrating=v[0], clearance=round(float(v[1]), 2)) for part, v in parts.items()} | {'samples': len(samples)}
            for k, parts in out.items()}


if __name__ == '__main__':
    import argparse, time
    ap = argparse.ArgumentParser(description='Gate exported aquatic clips against the exported hull profiles (no re-authoring).')
    ap.add_argument('clips', help='comma-separated clip names')
    ap.add_argument('--body', action='store_true', help='whole-body clearance instead of the paddle braid and twig gate')
    args = ap.parse_args(); start = time.time()
    for name in args.clips.split(','):
        if args.body: print(name, body_clearance(name), flush=True)
        else: gate_exported(name)
    print(f'{time.time()-start:.1f}s')
