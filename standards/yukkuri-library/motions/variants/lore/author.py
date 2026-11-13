"""World-setting motions, compiled per runtime model by contact-safe/author.py.

Each clip is a whole-body root transform sampled every `sample_seconds`: scale, rotation
(degrees, composed Rz*Ry*Rx as in the game), lift above the landed pose and an x/z shift,
both in model units. The caller lands every sample on the lowest visible point, so a lean
pivots on the floor instead of sinking into it. Forward is -Z (the face side); a negative
X rotation leans the head forward. Flourishes add rotation tracks to hand bones that carry the
braid (its strokes in the water and while straining); a hand that carries plain side hair only
follows the body. The hat clips (hat.py) move Marisa's braid and hat as well: Marisa's braid
waves, Reimu's sideburns flick, locks tremble in labor. Each clip
carries an expression timeline, resolved per model against its textures, and is compiled
only into the models its `applies` group names. See README.md and recipe.json.
"""
from fnmatch import fnmatch
from pathlib import Path
import json, math, runpy
import numpy as np

ROOT = Path(__file__).resolve().parents[5]
TEXTURES = ROOT/'src/main/resources/assets/mcyukkuri/textures/entity'
# Body pitch -8..+6, roll <= 6 (hard cap 12): effort is squash, stretch, shift, yaw and the face, never a whole-body flip.
NEW_BRAID_CLIPS=('hat_brim_offer','hat_hop_up','water_bob_lunge','hat_slide_over','water_mouth_grip','water_hat_shake','water_hat_push','boat_nudge_young')
# Full and family exports use the same recipe revision.
RUNTIME_REVISION=json.loads((Path(__file__).parent/'recipe.json').read_text(encoding='utf-8'))['runtime_revision']
_BRAID_RESCUE=None
# Aquatic clip compilation and contact gates.
TOOLS=runpy.run_path(str(Path(__file__).resolve().parent/'aquatic_tools.py'))
# Braid chains laid by key poses with their tuft track and clearance gate (braid_keys.py), selected in recipe export_families.
BRAID_KEYS=runpy.run_path(str(Path(__file__).resolve().parent/'braid_keys.py'))


def smooth(x):
    x = min(1.0, max(0.0, x)); return x*x*(3-2*x)


def bump(x):
    """0 -> 1 -> 0 over x in [0, 1]."""
    return math.sin(math.pi*min(1.0, max(0.0, x)))**2


def lerp(a, b, q): return a+(b-a)*q


def squash(a, wide=.06, low=.1, deep=.04): return [1+wide*a, 1-low*a, 1+deep*a]


def times(scale, other): return [x*y for x, y in zip(scale, other)]


def hop(t, start, takeoff, land, end, height, crouch=1.):
    """Crouch, jump and land. Returns (scale, lift, air) for a small hop; `crouch` scales the take-off crouch."""
    if t < start or t >= end:
        return [1., 1., 1.], 0., 0.
    if t < takeoff:
        return squash(crouch*smooth((t-start)/(takeoff-start))), 0., 0.
    if t < land:
        air = (t-takeoff)/(land-takeoff); e = math.sin(math.pi*air)
        release = 1-smooth(air/.25)  # the crouch springs open at take-off
        return times(squash(crouch*release), [1-.04*e, 1+.1*e, 1-.03*e]), height*4*air*(1-air), e
    return squash(bump((t-land)/(end-land)), .05, .08, .03), 0., 0.


def stroke_beats(c):
    """Sukkiri strokes laid end to end, per segment [from, to, period_from, period_to, push_from, push_to]:
    (start, peak, end, push). A stroke pushes out over its first 40% and eases back; only whole strokes fit."""
    out = []
    for a, b, pa, pb, xa, xb in c.get('strokes', []):
        t = a
        while True:
            q = (t-a)/(b-a); period = lerp(pa, pb, q)
            if t+period > b+1e-9: break
            out.append((t, t+.4*period, t+period, lerp(xa, xb, q)))
            t += period
    return out


def push_at(t, c):
    p = keyed(t, c['push_keys']) if 'push_keys' in c else 0.
    for s, peak, e, x in stroke_beats(c):
        if s <= t <= e:
            p = max(p, x*(smooth((t-s)/(peak-s)) if t <= peak else 1-smooth((t-peak)/(e-peak))))
    return p


PUSH_SLICES = 13  # the lower ~40% of the soft body carries the stroke; the top, face and hat stay put


def push_tracks(model, c, height):
    """Project design (sukkiri, user-approved proposal): the father's lower-body stroke (`push_role: drive`) shears the
    lower soft-body slices and the rump forward with a little front-to-back effort stretch, no whole-body pitch; the
    partner (`take`) answers each stroke `push_delay` later with a small forward shove and a pressed squash. Push is in
    model units for a standard 9.2-unit body; keyed at the stroke beats only, so long acts stay small."""
    if 'strokes' not in c and 'push_keys' not in c:
        return []
    bones = {b['name']: i for i, b in enumerate(model['bones'])}
    d, delay, k = c['duration'], c.get('push_delay', 0.), height/9.2
    beats = {0., d} | {x for beat in stroke_beats(c) for x in beat[:3]}
    if 'push_keys' in c:
        a, b = c['push_keys'][0][0], c['push_keys'][-1][0]
        beats |= {key[0] for key in c['push_keys']} | {a+i*.04 for i in range(int((b-a)/.04)+1)}
    times = sorted({round(min(d, t+delay), 6) for t in beats} | {0.})
    p = [push_at(max(0., t-delay), c) for t in times]
    rows = {}
    def put(name, channel, f):
        if name in bones:
            rows[(bones[name], channel)] = [[t, *[round(v, 5) for v in f(x)]] for t, x in zip(times, p)]
    if c.get('push_role', 'drive') == 'drive':
        for i in range(PUSH_SLICES):
            w = (1-i/PUSH_SLICES)**1.5
            put(f'soft_head_slice_{i}_bend', 'position', lambda x, w=w: [0, 0, -x*w*k])
        put('rump_sway', 'position', lambda x: [0, 0, -.7*x*k])
        put('body_stretch', 'scale', lambda x: [1, 1-.03*x/1.7, 1+.04*x/1.7])
    else:
        put('body_motion', 'position', lambda x: [0, 0, -.3*x*k])
        put('body_stretch', 'scale', lambda x: [1+.015*x/1.7, 1-.03*x/1.7, 1+.015*x/1.7])
    return [{'bone': bone, 'channel': channel, 'keys': keys} for (bone, channel), keys in rows.items()]


def curled(c):
    """Base pose of a body sitting low in the hat boat."""
    return [c['width'], c['height'], c['width']]


def keyed(t, keys):
    """Deliberate beats with eased holds, rather than one periodic wobble for every state."""
    if t <= keys[0][0]:return keys[0][1]
    for (a,x),(b,y) in zip(keys,keys[1:]):
        if t <= b:return lerp(x,y,smooth((t-a)/(b-a)))
    return keys[-1][1]


def state(action, t, c, height):
    s, r, lift, shift = [1., 1., 1.], [0., 0., 0.], 0., [0., 0.]
    d = c['duration']
    owned = TOOLS['state'](action, t, c, height) if action in TOOLS['EXPORT_CLIPS'] else None
    if owned is not None:
        s, r, lift, shift = owned
    elif BRAID_KEYS['authored'](c):
        s, r, lift, shift = BRAID_KEYS['state'](action, t, c)
    elif action.startswith('braid_rescue_'):
        global _BRAID_RESCUE
        if _BRAID_RESCUE is None:_BRAID_RESCUE=json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json').read_text(encoding='utf-8'))
        r=list(_BRAID_RESCUE['root_rotation']);lift=_BRAID_RESCUE['root_lift']  # v4: root_pitch_degrees is 0, no whole-body tilt
    elif action=='braid_reach_low':
        # Project original (2026-10-18): the parent sinks her braid side toward an infant floating low beside the hull.
        # The pose is braid_rescue.json reach_lean (also the server's braid root), eased in over its ease_ticks and then held.
        if _BRAID_RESCUE is None:_BRAID_RESCUE=json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json').read_text(encoding='utf-8'))
        lean=_BRAID_RESCUE['reach_lean'];q=smooth(t/(lean['ease_ticks']/20))
        s=[1+(v-1)*q for v in lean['scale']];r=[lean['pitch']*q,lean['yaw']*q,lean['roll']*q]
        lift=(lean['shift'][1]+_BRAID_RESCUE['reach_lean_lift'])*q;shift=[lean['shift'][0]*q,lean['shift'][2]*q]
    elif action in ('boat_alarm_call','boat_answer_call'):
        q=keyed(t,[(0,.1),(.16,1),(.4,.55),(.75,.3),(d,.2)])
        s=[1+.03*q,1-.065*q,1+.025*q];r=[-3*q,keyed(t,[(0,0),(.25,-9),(.65,5),(d,0)]),0]
    elif action in ('boat_alarm_wait','boat_flee'):
        q=keyed(t,[(0,.3),(.4,.6),(.8,.25),(1.2,.5),(2,.3)])
        s=[1.02,.95+.015*q,1.02];r=[-3,keyed(t,[(0,-10),(.55,-16),(1.05,8),(1.5,10),(2,-10)]),0]
    elif action=='boat_calm':
        q=1-smooth(t/d);s=[1+.025*q,1-.055*q,1+.02*q]
        r=[-3*q,keyed(t,[(0,-13),(.6,8),(1.4,-5),(2.3,3),(4,0)]),0]
    elif action in ('water_hat_flip','water_held_breath'):
        # The held hat pivots at a world-space rim contact. Keep the body frame
        # used by SelfRescueHatGeometry stable while the real braid does the work.
        s=[1.,1.,1.];r=[0.,0.,0.]
    elif action=='water_hat_grip':
        # Mouth hold on the rim (project original): the head rides a few degrees low, braids only touch.
        q=min(1,max(0,t/d));s=[1,1,1];r=[-6*smooth(q),0,-3*math.sin(math.pi*q)**2]
    elif action=='water_hat_pull':
        q=min(1,max(0,t/d))
        progress=.58*smooth((q-.15)/.23)+.42*smooth((q-.62)/.23)
        pulse=lambda v:math.sin(math.pi*v)**2 if 0<v<1 else 0
        s=[1,1,1];r=[-7-3*progress,0,3*(pulse((q-.15)/.23)-pulse((q-.62)/.23))]
    elif action=='water_hat_over':
        q=min(1,max(0,t/d))
        pulse=lambda v:math.sin(math.pi*v)**2 if 0<v<1 else 0
        s=[1,1,1];r=[-10+10*smooth((q-.42)/.58),0,-3*pulse((q-.08)/.34)]
    elif action=='hat_brim_offer':
        # Project original: the captain settles her weight toward the near rim (the hull dip itself is the boat's).
        w=2*math.pi*t/d;lean=.72+.28*math.sin(w)
        s=[1+.04*lean,1-.06*lean,1+.03*lean];r=[-4.5*lean,3*math.sin(w+1),3*math.sin(w+.6)];shift[1]=-.3*lean
    elif action=='hat_hop_up':
        # Crouch, spring, land compressed; HatBoarding supplies the arc so there is no lift here.
        a=keyed(t,[(0,0),(.3,1),(.4,-.5),(.55,-.2),(.62,.7),(.8,0)])
        s=squash(a,.065,.12,.045)
        r=[keyed(t,[(0,0),(.3,-4),(.42,-6),(.55,3),(.68,1),(.8,0)]),keyed(t,[(0,0),(.3,2),(.55,-2),(.8,0)]),0]
    elif action=='hop_leg':
        # The Hop action's one leg (every species): hat_hop_up's crouch and spring at a readable pace. Crouch to takeoff, the
        # stretched air pose up to land (held by the presentation for the physical airtime; the server owns the arc, no lift),
        # landing squash and recovery to settled. Pitch -5..+2, roll 0.
        m=c['markers'];tk,ld,st=m['takeoff'],m['land'],m['settled']
        a=keyed(t,[(0,0),(tk,1),(tk+.08,-.5),(ld,-.2),(ld+.08,.7),(st,0)])
        s=squash(a,.065,.12,.045)
        r=[keyed(t,[(0,0),(tk,-4),(tk+.1,-5),(ld,2),(ld+.12,1),(st,0)]),0,0]
    elif action=='water_bob_lunge':
        # One darting surge up the dipped rim: squash, stretch, release; both ends match so it can repeat.
        a=keyed(t,[(0,.35),(.22,1),(.36,-.8),(.5,-.35),(.7,.35)])
        s=squash(a,.07,.14,.05)
        r=[keyed(t,[(0,1),(.22,-2),(.36,-8),(.52,-4),(.7,1)]),keyed(t,[(0,0),(.22,-3),(.4,4),(.7,0)]),keyed(t,[(0,0),(.22,-3),(.38,3),(.7,0)])]
    elif action=='hat_slide_over':
        # zuri-zuri: the belly presses on the rim and the body wriggles over it; HatBoarding keeps the lowest point supported.
        w=2*math.pi*t/.7;press=keyed(t,[(0,.3),(.2,1),(.9,1),(1.4,.1)])
        s=squash(press,.08,.15,.05)
        r=[-8*(1-smooth((t-.15)/1.2)),8*math.sin(w)*(1-smooth((t-.9)/.5)),3*math.sin(w+.8)*(1-smooth((t-1)/.4))]
    elif action=='water_mouth_grip':
        # Project original: the mouth holds the brim, so the head rides back a little; breathing and a small tremble only.
        w=2*math.pi*t/d
        s=[1.03,.95+.012*math.sin(w),1.03];r=[5+1*math.sin(2*w),2*math.sin(w+.5),2*math.sin(w)]
    elif action=='water_hat_shake':
        env=smooth(t/.15)*(1-smooth((t-1.0)/.2));sw=math.sin(2*math.pi*2.5*t);q=abs(sw)*env
        s=[1+.04*q,1-.05*q,1+.03*q];r=[keyed(t,[(0,0),(.15,2),(.6,-2),(1.2,0)]),14*env*sw,3*env*math.sin(2*math.pi*2.5*t+1.2)]
    elif action=='water_hat_push':
        a=keyed(t,[(0,.2),(.2,1),(.38,-.9),(.55,-.3),(.8,.2)])
        s=squash(a,.07,.13,.05)
        r=[keyed(t,[(0,0),(.2,2),(.38,-8),(.6,-3),(.8,0)]),keyed(t,[(0,0),(.2,-4),(.4,4),(.8,0)]),keyed(t,[(0,0),(.2,-3),(.4,4),(.8,0)])]
    elif action=='boat_nudge_young':
        # Project original: a short chest-first nudge at the child's back, never a lift.
        a=keyed(t,[(0,.2),(.18,.8),(.34,-.7),(.5,-.3),(.8,.2)])
        s=squash(a,.05,.1,.04)
        r=[keyed(t,[(0,0),(.18,1),(.34,-8),(.55,-3),(.8,-1)]),0,0]  # a small wind-up: at the stern seat her rear hair is at the lip
        shift[1]=keyed(t,[(0,0),(.18,0),(.34,-.9),(.6,-.3),(.8,0)])
    elif action == 'hat_stumble':
        press=keyed(t,[(0,.15),(.10,1),(.25,.45),(.40,.85),(.65,.35),(.83,.65),(1.2,.15)])
        s=[1+.065*press,1-.16*press,1+.07*press]
        r=[-6*press,keyed(t,[(0,0),(.25,-5),(.6,4),(1.2,0)]),keyed(t,[(0,0),(.12,-4),(.36,3),(.65,-2),(1.2,0)])]
    elif action == 'hat_jostle':
        press=keyed(t,[(0,.4),(.16,1),(.32,.65),(.52,.95),(.76,.45),(1,.4)])
        s=[1+.075*press,1-.15*press,1+.06*press]
        r=[-9*press,keyed(t,[(0,-3),(.32,5),(.62,-4),(1,-3)]),keyed(t,[(0,-2),(.18,4),(.5,-3),(1,-2)])]
    elif action == 'water_plunge':
        impact=keyed(t,[(0,.1),(.12,1),(.30,.55),(.6,.15)])
        s=[1+.10*impact,1-.22*impact,1+.08*impact]
        r=[keyed(t,[(0,-7),(.12,-10),(.3,3),(.48,8),(.6,5)]),0,keyed(t,[(0,-4),(.16,5),(.36,-3),(.6,0)])]
    elif action == 'water_struggle':
        effort=keyed(t,[(0,.1),(.07,.9),(.17,.35),(.28,1),(.4,.12),(1.6,.1)])
        s=[1+.045*effort,.92+.07*effort,1+.055*effort]
        r=[keyed(t,[(0,5),(.07,10),(.18,-3),(.28,9.5),(.4,4),(1.6,5)]),
           keyed(t,[(0,0),(.12,-5),(.3,4),(.45,0),(1.6,0)]),keyed(t,[(0,-2),(.12,6),(.29,-5),(.44,0),(1.6,-2)])]
    elif action == 'water_swim':
        # Purposeful swimming (Swim): a steady stroke instead of water_struggle's jerks. Its first frame is water_plunge's last
        # and starts at rest, so the shock flows straight into the first reach. Each loop the body glides while the braid reaches
        # forward, then surges on the pull (power_start..power_end: a little squash, the face dipping from +5 to +2 degrees, a
        # slight yaw and roll toward the stroking side). Pitch +2..+5, roll <= 2; no root track (the server owns the travel).
        drive=bump((t/d-.5)/.5)
        s=[1.015+.03*drive,.967-.045*drive,1.012+.035*drive]
        r=[5-3*drive,4*drive,-2*drive]
    elif action == 'water_gasp':
        breath=keyed(t,[(0,0),(.3,0),(.5,1),(.75,.1),(1.2,0),(1.6,0)])
        s=[1.035,.81+.035*breath,1.045]
        r=[keyed(t,[(0,2),(.32,2),(.52,9),(.8,2),(1.6,2)]),0,keyed(t,[(0,-3),(.55,-1),(1.1,-4),(1.6,-3)])]
    elif action == 'water_recover':
        regain=smooth((t-1.5)/1.3)
        breath=keyed(t,[(0,0),(.22,.7),(.5,0),(.85,1),(1.2,0),(2.8,0)])
        s=[lerp(1.07,1,regain),lerp(.80,1,regain)+.035*breath,lerp(1.06,1,regain)]
        r=[lerp(-8,0,regain)-2*breath,keyed(t,[(0,0),(1.3,0),(1.7,-17),(2.1,10),(2.8,0)]),0]
    elif action == 'boat_reassure':
        offer=keyed(t,[(0,0),(.4,.7),(.7,1),(1.25,.65),(1.7,.15),(2,0)])
        s=[1.025,.95-.025*offer,1.025];r=[-6*offer,3*offer,1.5*offer]
    elif action in ('boat_paddle', 'boat_gather', 'boat_eat', 'boat_drink', 'boat_board', 'boat_toilet',
                  'boat_feed_young', 'boat_nuzzle', 'boat_first_balance'):
        s = curled(c)
        phase = 2*math.pi*t/d
        if action == 'boat_first_balance':
            settle = 1-smooth(t/d)
            r = [-2, 12*bump((t-.7)/1.3), 7*settle*math.sin(phase*2)]
            s[1] -= .04*settle
        elif action == 'boat_feed_young':
            reach = smooth(t/.6)*(1-smooth((t-1.2)/1.2))
            r = [-6*reach, -2*reach, -1*reach]; s[1] -= .01*reach
        elif action == 'boat_toilet':
            # The boat has already selected the water-facing rear yaw.  This clip only lowers
            # the seated body there, keeping the face out of the child-care interaction.
            brace = .75+.25*math.sin(phase)
            r = [9*brace, 0, 0]; s = [1.02, c['height']-.035*brace, 1.05]
        elif action == 'boat_nuzzle':
            lean = smooth(t/.5)*(1-smooth((t-1.9)/.5))
            r = [-9*lean, 0, 3*math.sin(phase*2)*lean]; s[1] -= .025*lean
        elif action == 'boat_gather':
            reach = bump(t/d); r = [-7*reach, -9*reach, -3*reach]
            s[1] -= .025*reach
        elif action == 'boat_eat':
            chew = bump((t % .4)/.4); r[0] = -5-2*chew; s[1] -= .025*chew
        elif action == 'boat_drink':
            sip = bump(t/d); r[0] = -9*sip; r[2] = -2*sip
        else:
            s = [1.03, .94, 1.03]  # physical flight supplies lift; never double the arc
    elif action == 'eat':
        q = bump((t % c['chew_seconds'])/c['chew_seconds'])
        r[0] = c['lean']+c['chew_nod']*q
        k = c['chew_squash']; s = [1+.6*k*q, 1-k*q, 1+.6*k*q]
    elif action == 'shiawase':
        s, lift, e = hop(t, 0, .3, .75, .95, height*c['hop_ratio'])
        r[0] = c['look_up']*e
        if t >= .95:
            fade = (1-(t-.95)/(d-.95))**2
            r[2] = c['wiggle']*math.sin(2*math.pi*(t-.95)/.52)*fade
    elif action == 'drink':
        q = bump((t % c['sip_seconds'])/c['sip_seconds'])
        r[0] = c['lean']+c['sip']*q; s = [1+.01*q, 1-.02*q, 1+.01*q]
    elif action == 'sleep':
        b = (1-math.cos(2*math.pi*t/d))/2
        wide = 1+(1-c['height'])*.6-c['breath']*.3*b
        s = [wide, c['height']+c['breath']*b, wide]; r = [c['slump'], 0., c['tilt']]
    elif 'height_keys' in c:
        # Storyboarded life beats (wake stretch, call_child, meal_content): eased height keys with a volume-keeping width,
        # an optional quiver while held at the top, forward reach (share of the body height, toward the face) and angle keys.
        h = keyed(t, c['height_keys'])
        if 'quiver' in c:
            a, b, amount, hz = c['quiver']
            if a <= t <= b: h += amount*math.sin(2*math.pi*hz*(t-a))*bump((t-a)/(b-a))
        # swell_keys: a uniform enlargement on top (puku).
        g = keyed(t, c['swell_keys']) if 'swell_keys' in c else 1.
        w = h**-.5; s = [w*g, h*g, w*g]
        # hops: [start, take-off, land, end, height ratio] crouch-spring-land hops on top (a bounding run).
        for a, takeoff, land, end, ratio in c.get('hops', []):
            hs, hl, _ = hop(t, a, takeoff, land, end, height*ratio)
            s = times(s, hs); lift += hl
        r = [keyed(t, c[k]) if k in c else 0. for k in ('pitch_keys', 'yaw_keys', 'roll_keys')]
        if 'reach_keys' in c: shift[1] = -keyed(t, c['reach_keys'])*height
    elif action == 'aquatic_hat_care':
        shake = (1-smooth(t/.9))*math.sin(2*math.pi*t/.22)
        tidy = smooth((t-.6)/.5)*(1-smooth((t-1.8)/.6))
        r = [-2*tidy, 10*shake, -2*tidy]; s[1] = 1-.02*abs(shake)
    elif action in ('nuzzle', 'comfort_lick'):
        w = math.sin(2*math.pi*t/d)
        r = [c['lean'], c.get('rub_yaw', 0.)*w, c['rub']*w]; shift[0] = c['rub_shift']*height*w; s = [1., 1-.015*abs(w), 1.]
    elif action in ('feed_child', 'first_toilet_assist'):
        # Both are deliberate care approaches, but they stay separate clips: mouth-to-mouth
        # feeding faces the child, while first-toilet care is positioned by the server at its rear.
        # The brief crouch is neutral support, never a nuzzle, wash, or toilet exercise.
        settle = smooth(t/c['settle'])
        leave = 1-smooth((t-c['withdraw'])/(d-c['withdraw'])) if t >= c['withdraw'] else 1.
        reach = settle*leave
        r[0] = c['lean']*reach
        if 'crouch' in c:
            k = c['crouch']*reach; s = [1+.45*k, 1-k, 1+.45*k]
    elif action == 'boat_carry_young':
        r[0] = -6; s = [1.015, .97, c['depth']]
    elif action in ('boat_be_carried','boat_prepare_carry','boat_release_carry'):
        q = smooth(t/d) if action == 'boat_prepare_carry' else 1-smooth(t/d) if action == 'boat_release_carry' else 1
        # The tuck is a flatten with the hat leaning a little forward, away from a carrier biting the scruff behind it. The lean
        # stays small so the hold's own hang keeps the 12 degree budget; no yaw, which would swing the held scruff out of the bite.
        r[0] = c['tilt']*q
        k = c['tuck']*q; s = [1+.45*k, 1-k, 1+.45*k]
    elif action in ('newborn_crawl', 'hat_walk'):
        phase = 2*math.pi*t/d
        s = [1.04, c['height'], 1.07]
        r = [c['lean'], 0., c['wobble']*math.sin(phase)]
        shift[1] = -c['push']*height*(.5+.5*math.sin(phase))
    elif action in ('learn_jump',):
        # The server owns the actual launch and landing; do not add a second visual jump.
        tuck = bump(t/d)
        s = [1+.025*tuck, 1-.04*tuck, 1+.025*tuck]
        r[0] = c['look_down']*tuck
    elif action == 'learn_watch':
        r[0] = 4+2*math.sin(math.tau*t/d)
    elif action == 'care_finish':
        r[0] = -3; s[1] = .985+.006*math.sin(math.tau*t/d)
    elif action == 'nest_harvest':
        reach = smooth(t/.55)*(1-smooth((t-1.25)/.55))
        r[0] = c['bend']*reach; s = [1+.025*reach, 1-.06*reach, 1+.025*reach]
        shift[1] = -.018*height*reach
    elif action == 'nest_place':
        bend = bump(t/d)
        r[0] = c['bend']*bend; s[1] = 1-.08*bend
    elif action == 'nest_spread':
        sweep = math.sin(math.tau*t/d)
        r = [c['bend'], 8*sweep, 3*sweep]; s[1] = .94
        shift[0] = .025*height*sweep
    elif action == 'nest_press':
        press = .5-.5*math.cos(4*math.pi*t/d)
        s = [1+.055*press, 1-.11*press, 1+.055*press]
        r[0] = -5*press
    elif action == 'sing':
        q = bump((2*t/d) % 1)
        r = [c['chin_up'], 0., c['sway']*math.sin(2*math.pi*t/d)]
        lift = c['bounce_ratio']*height*q; s = [1-.01*q, 1+.02*q, 1-.01*q]
    elif action == 'sway':
        r = [c['chin_up'], 0., c['sway']*math.sin(2*math.pi*t/d)]
    elif action == 'push':
        back, out = c['windup_shift']*height, -c['lunge_shift']*height
        if t < .45:
            a = smooth(t/.45); shift[1] = back*a; r[0] = c['windup_lean']*a; s = [1+.03*a, 1-.06*a, 1+.02*a]
        elif t < .62:
            b = smooth((t-.45)/.17); shift[1] = lerp(back, out, b); r[0] = lerp(c['windup_lean'], c['lunge_lean'], b)
            s = [lerp(1.03, .98, b), lerp(.94, 1.02, b), lerp(1.02, 1.06, b)]
        else:
            q = smooth((t-.62)/(d-.62)); shift[1] = lerp(out, 0, q); r[0] = lerp(c['lunge_lean'], 0, q)
            s = [lerp(.98, 1, q), lerp(1.02, 1, q), lerp(1.06, 1, q)]
    elif action == 'greet' and c.get('style') == 'genki':
        # Marisa: a big hop, a second skip and a proud chin-up; the braid waves above the head.
        if t < .6:
            s, lift, e = hop(t, 0, .12, .5, .6, height*c['hop_ratio']); r[0] = c['look_up']*.6*e
        elif t < .95:
            s, lift, e = hop(t, .6, .62, .85, .95, height*c['second_hop_ratio'])
        else:
            r[0] = c['look_up']*bump((t-.95)/(d-.95))
    elif action == 'greet':
        # A readable anticipation: a deeper, slightly longer crouch springs into the hop, then the bow after landing.
        s, lift, _ = hop(t, 0, .28, .68, .83, height*c['hop_ratio'], c['crouch'])
        r[0] = c['bow']*bump((t-.86)/.5) if .86 <= t <= 1.36 else 0.
    elif action == 'tremble':
        w = math.sin(2*math.pi*c['shiver_hz']*t); k = c['shrink']
        r = [c['lean_back'], 0., 1.5*w]; s = [1-.6*k, 1-k, 1-.6*k]; shift[0] = c['shiver_ratio']*height*w
    elif action == 'droop':
        b = (1-math.cos(2*math.pi*t/d))/2; wide = 1+(1-c['height'])*.5
        s = [wide, c['height']-c['sigh']*b, wide]; r = [c['head_down'], 0., c['sway']*math.sin(2*math.pi*t/d)]
    elif action == 'look_around':
        T = c['turn']; keys = [(0, 0), (.6, -T), (1.6, -T), (2.4, T), (3.4, T), (d, 0)]
        for (t0, a0), (t1, a1) in zip(keys, keys[1:]):
            if t0 <= t <= t1:
                r[1] = lerp(a0, a1, smooth((t-t0)/(t1-t0))); break
        s = [1., c['stand'], 1.]
    elif action == 'shake':
        fade = (1-t/d)**2; w = math.sin(2*math.pi*t/c['shake_seconds'])
        r[1] = c['turn']*w*fade; s = [1+.03*abs(w)*fade, 1-.03*abs(w)*fade, 1+.03*abs(w)*fade]
    elif action in ('rain_huddle_left', 'rain_huddle_right'):
        # Project design (TODO 陆 14): pressed against a partner on its `side` (+1: toward +X, the body's right). A nestle opens each cycle (squash
        # low and wide, slide and lean into the partner), then the pressed hold with a shiver and a breath. The shiver is not
        # mirrored: two partners on one clip clock shiver in step. Pitch |lean| + roll + shiver roll stay under 12 degrees.
        # `shiver` scales the shiver (1 in the rain; the cling preset is the same side-press held calm with 0).
        side, nest = c['side'], c['nestle']
        press = c['hold']+(1-c['hold'])*bump(t/nest)
        hold = min(1., max(0., (t-nest)/(d-nest)))
        calm = 1-.5*smooth(hold)
        w = c.get('shiver', 1)*math.sin(2*math.pi*c['shiver_hz']*t)
        k, wide = c['squash']*press, c['widen']*press
        s = [1+wide, 1-k+c['breath']*math.sin(math.pi*hold)**2, 1+.4*wide]
        r = [c['lean'], 0., -side*c['roll']*press+c['shiver_roll']*w*calm]
        shift[0] = side*c['slide']*height*press+c['shiver_ratio']*height*w*calm
    elif action == 'thunder_cower':
        # Project design (TODO 陆 14 thunderstorm): flattened, leaning back from the open side and trembling; it rises a little to
        # peek out with a small look aside (yaw only), then a rumble flattens it again. Pitch back <= lean_back, roll <= 1.5.
        p0, p1 = c['peek_start'], c['peek_end']
        rise = keyed(t, [(0, 0), (p0, 0), (p0+.35, 1), (p1-.2, 1), (p1, 0), (d, 0)])
        flinch = bump((t-p1)/.35) if p1 <= t < p1+.35 else 0.
        k = c['flat']*(1-.6*rise)+.03*flinch
        trem = 1-.7*rise
        w = math.sin(2*math.pi*c['shiver_hz']*t)
        s = [1+.5*k, 1-k, 1+.4*k]
        glance = keyed(t, [(0, 0), (p0+.35, 0), (p0+.6, 1), (p1-.3, 1), (p1, 0), (d, 0)])
        r = [c['lean_back']*(1-.5*rise), c['glance']*glance, 1.5*w*trem]
        shift[0] = c['shiver_ratio']*height*w*trem
    elif action == 'sunbathe':
        # Project design (art-dir-life 晒太阳): settles flat in the winter sun, face a little up and eyes closed, slow breaths and a
        # soft sway, then rounds back up warm. Pitch up <= chin_up, roll <= sway.
        settle = keyed(t, [(0, 0), (c['settle'], 1), (d-c['rise'], 1), (d, 0)])
        k = c['flat']*settle
        b = math.sin(2*math.pi*t/c['breath_seconds'])
        s = [1+.55*k, 1-k+c['breath']*settle*b, 1+.45*k]
        r = [c['chin_up']*settle, 0., c['sway']*settle*math.sin(2*math.pi*t/c['sway_seconds'])]
    elif action == 'brood':
        b = (1-math.cos(2*math.pi*t/d))/2
        s = [1-c['breath']*.3*b, 1+c['breath']*b, 1-c['breath']*.3*b]
        r = [c['look_down'], 0., c['rock']*math.sin(2*math.pi*t/d)]
    elif action == 'labor':
        # The shared 140-tick cycle: brace, first push, a held half-emerged child while
        # catching breath, final push, then 48 ticks of relief. Retain the recoil at birth.
        # v26: pitch stays at or under 8 degrees; effort is squash, a small forward shift (-Z), shiver and the face.
        # Frames 0..7 s are one child's cycle, 7..12 s the lead-in of labour signs (restless shuffle, three pains).
        a = keyed(t, c['effort_keys']); k = c['brace']; cycle = c['cycle_seconds']
        s = [1+.5*k*a, 1-k*a, 1+.5*k*a]
        r[0] = keyed(t, c['pitch_keys'])
        shiver = a*math.sin(2*math.pi*9*t)
        shift[0] = c['shiver_ratio']*height*shiver; r[2] = 1.2*shiver
        shift[1] = -c['push_shift']*height*a
        if t >= c['omen_start']:
            fade = smooth((t-c['omen_start'])/.5)*(1-smooth((t-d+.6)/.6)); w = 2*math.pi*c['restless_hz']*(t-c['omen_start'])
            r[1] = c['restless_yaw']*math.sin(w)*fade; shift[0] += c['restless_shift']*height*math.sin(2*w)*fade
        elif t >= c['birth_seconds']:
            breath = bump(((t-c['birth_seconds'])/.8) % 1)*bump((t-c['birth_seconds'])/(cycle-c['birth_seconds']))
            s = [1-.012*breath, 1+.024*breath, 1-.012*breath]
    elif action == 'midwife':
        k = c['crouch']
        s = [1+.5*k, 1-k, 1+.5*k]; r[0] = c['lean']
        if .3 <= t < 4.6: r[0] += c['nod']*bump(((t-.3)/.8) % 1)
        if 4.3 <= t < 5.3:  # meet the actual newborn, with no invented entity movement
            e = bump((t-4.3)/1.0); lift = .02*height*e; r[0] -= 3*e
        if 5.3 <= t < 6.5:
            e=bump((t-5.3)/1.2);w = math.sin(2*math.pi*(t-5.3)/.8)*e
            r[2] = c['rub']*w; shift[0] = .015*height*w
    elif action == 'postpartum':
        a = keyed(t, [(0,0),(.8,1),(3.2,1),(5.8,.4),(d,0)])
        h = lerp(1, c['slump'], a); s = [lerp(1, 1+(1-c['slump'])*.5, a), h, lerp(1, 1+(1-c['slump'])*.5, a)]
        r[0] = c['start_pitch']*(1-smooth(t/.8))+c['head_down']*a
        if t >= 3.2:
            r[0] += c['look_up']*bump((t-3.2)/(d-3.2))
            r[1] = c['look_young']*bump((t-3.2)/(d-3.2))
        breath = bump((t/1.2) % 1)*a
        s[1] += .025*breath;s[0] -= .01*breath;s[2] -= .01*breath
        # Project original: she nuzzles the newborn (すーりすーり, wiki E06), then licks it once (extending EH08's lick-to-stimulate care).
        n = c['nuzzle']
        if n['start'] <= t < n['end']:
            q = (t-n['start'])/(n['end']-n['start']); e = bump(q); w = math.sin(2*math.pi*n['hz']*(t-n['start']))
            shift[0] = n['shift']*height*w*e; r[1] += n['yaw']*w*e; r[2] = n['roll']*w*e; s[1] += .01*e
        l = c['lick']
        if l['start'] <= t < l['end']:
            q = (t-l['start'])/(l['end']-l['start']); pulse = bump((q*l['licks']) % 1)*bump(q)
            shift[1] = -l['forward']*height*pulse; r[0] += l['pitch']*pulse
    elif action == 'first_wake':
        cu, st = c['curl'], c['stretch']
        if t < .5:
            s = [1+(1-cu)*.5, cu, 1+(1-cu)*.5]; r[2] = 6
            if t < c['land_seconds']:  # landed wet: a quick squash before curling up (project original)
                q = 1-smooth(t/c['land_seconds']); s = [s[0]*(1+.09*q), s[1]*(1-(1-c['land'])*q), s[2]*(1+.09*q)]
        elif t < .9:
            a = smooth((t-.5)/.4); s = [lerp(1+(1-cu)*.5, .97, a), lerp(cu, st, a), lerp(1+(1-cu)*.5, .97, a)]; r[2] = lerp(6, 0, a)
        elif t < 1.5:
            a = smooth((t-.9)/.25); s = [lerp(.97, 1, a), lerp(st, 1, a), lerp(.97, 1, a)]
            r[2] = c['wobble']*math.sin(2*math.pi*(t-.9)/.3)*(1-(t-.9)/.6)
        else:
            r[2] = 0.
    elif action == 'boat_drift':
        s = curled(c); lift = c['bob']*(1+math.sin(2*math.pi*t/d))/2  # bob up from the seat, never into it
        r = [c['pitch']*math.sin(4*math.pi*t/d+1), c['glance']*bump((t-2.2)/1.0) if 2.2 <= t <= 3.2 else 0., c['roll']*math.sin(2*math.pi*t/d)]
    elif action == 'boat_steady':
        s = curled(c); r = [c['pitch']*math.sin(2*math.pi*t/.75), 0., c['roll']*math.sin(2*math.pi*t/.5)]
    elif action == 'boat_call':
        q = bump((2*t/d) % 1)
        s = [1., c['rise']+.02*q, 1.]; r = [c['chin_up'], 0., c['sway']*math.sin(2*math.pi*t/d)]; lift = c['bounce_ratio']*height*q
    elif action == 'boat_brood':
        s = curled(c); lift = c['bob']*(1+math.sin(2*math.pi*t/d))/2; r[2] = c['rock']*math.sin(2*math.pi*t/d)
    elif action == 'boat_labor':
        base = curled(c); k = c['brace']; cycle = c['cycle_seconds']
        r[0] = keyed(t, c['pitch_keys'])
        if t < c['birth_seconds'] or t >= c['omen_start']:
            a = keyed(t,c['effort_keys'])
            s = [base[0]+.5*k*a, base[1]-k*a, base[2]+.5*k*a]
            shiver = a*math.sin(2*math.pi*9*t); shift[0] = c['shiver_ratio']*height*shiver
            shift[1] = -c['push_shift']*height*a
            if t >= c['omen_start']:  # lead-in afloat: a restless sway in the hat
                fade = smooth((t-c['omen_start'])/.5)*(1-smooth((t-d+.6)/.6)); w = 2*math.pi*c['restless_hz']*(t-c['omen_start'])
                r[1] = c['restless_yaw']*math.sin(w)*fade; r[2] = 2*math.sin(w+.5)*fade
        else:
            # Lean over the newborn that dropped into the basin straight before her, and come back (no turn).
            e = bump((t-c['birth_seconds'])/(cycle-c['birth_seconds'])); s = base
            s[1]+=.015*bump(((t-c['birth_seconds'])/.8)%1)*e
            r = [keyed(t, c['pitch_keys'])+c['tuck_lean']*e, 0., 0.]
    elif action == 'bereaved':
        if t < 1.6:  # frozen, sinking a little: the blank stare
            a = smooth(t/.4); s = [1+c['sink']*.5*a, 1-c['sink']*a, 1+c['sink']*.5*a]
        elif t < 2.4:  # the wail rises
            a = smooth((t-1.6)/.4); s = [lerp(1+c['sink']*.5, 1-.01, a), lerp(1-c['sink'], 1+c['wail_rise'], a), lerp(1+c['sink']*.5, 1-.01, a)]
            r[0] = c['wail_chin']*a
        else:  # sobbing, head down, settling back to rest at the end
            fade = 1-smooth((t-4.3)/.7); u = smooth((t-2.4)/.5)
            w = math.sin(2*math.pi*c['sob_hz']*(t-2.4))
            s = [1-.01*fade, 1+(c['wail_rise']*(1-u)-c['sob']*abs(w)*u)*fade, 1-.01*fade]
            r[0] = (c['wail_chin']*(1-u)+c['head_down']*u)*fade; r[2] = 1.5*w*u*fade
    elif action == 'hat_cradle':
        lift = c['bob']*(1+math.sin(2*math.pi*t/d))/2; r[2] = c['sway']*math.sin(2*math.pi*t/d)
    elif action == 'think':
        ph = 2*math.pi*t/d; k = c['squash']
        r = [c['look_up'], c['turn']*math.sin(ph), c['tilt']*math.sin(ph)]; s = [1+.5*k, 1-k, 1+.5*k]
        shift[0] = c['sway']*height*math.sin(ph)
    elif action == 'nervous':
        ph = 2*math.pi*t/c['hop_seconds']; k = c['shrink']
        s = [1-.5*k, 1-k, 1-.5*k]; shift[0] = c['tremble_units']*math.sin(2*math.pi*c['tremble_hz']*t)
        q = bump((t % c['hop_seconds'])/c['hop_seconds']); lift = c['hop_ratio']*height*q
        s = [s[0]*(1-.015*q), s[1]*(1+.03*q), s[2]*(1-.015*q)]; r = [-2, c['glance']*math.sin(2*math.pi*t/c['glance_seconds']), c['shudder']*math.sin(ph*2)]
    elif action == 'proud':
        # Chest puffed by a vertical stretch with only a slight lean back, hold, then settle with a small overshoot.
        if t < .5:
            a = smooth(t/.5)
        elif t < 1.5:
            a = 1.
        else:
            a = 1-smooth((t-1.5)/.5)
        k = c['stretch']*a; r = [c['lean_back']*a+c['chin_up']*bump((t-.5)/1.0), 0., 0.]
        s = [1-.3*k, 1+k, 1-.3*k]
        if t >= 1.5: e = bump((t-1.5)/.5); s = [s[0]*(1+.03*e), s[1]*(1-.04*e), s[2]*(1+.03*e)]
    elif action == 'joy_jump':
        def leap(t0, tk, tl, te, h):
            if t < t0 or t >= te: return None
            if t < tk:
                a = smooth((t-t0)/(tk-t0)); return squash(a, .09, .17, .06), 0., 0.
            if t < tl:
                air = (t-tk)/(tl-tk); e = math.sin(math.pi*air); release = 1-smooth(air/.2)
                return times(squash(release, .09, .17, .06), [1-.06*e, 1+.14*e, 1-.05*e]), h*4*air*(1-air), e
            return squash(bump((t-tl)/(te-tl)), .08, .14, .05), 0., 0.
        got = leap(0, .22, .62, .8, height*c['hop_ratio']) or leap(.8, 1.0, 1.4, 1.6, height*c['second_hop_ratio'])
        if got: s, lift, e = got; r[0] = c['look_up']*e
    elif action == 'sulk':
        # Turned away and a little sunk; one slow sob per cycle heaves the body up, dips it and turns it further away (no shudder).
        a, b = c['sob']; q = bump((t-a)/(b-a)) if a <= t <= b else 0.
        s = [c['width'], c['height'], c['width']]
        r = [c['head_down']+c['sob_dip']*q, c['turn']+c['sob_turn']*q, c['tilt']]
        lift = c['sob_lift']*height*q
    elif action == 'hide_face':
        # Crouched with the front pushed into the box: shorter in depth, wider, a gentle nose-down pitch
        # (the rear reads as raised from the squash), and a wiggle made of yaw, a touch of roll and a
        # small push-in pulse rather than a big pitch swing.
        w = 2*math.pi*c['wiggle_hz']*t; sw = math.sin(w)
        r = [c['lean']+c['wiggle']*sw, c['yaw']*sw, c['roll']*math.sin(w+1)]
        s = [c['width']*(1+.012*sw), c['height']*(1-.01*sw), c['depth']]
        shift = [c['sway']*height*sw, -c['push']*height*(1+.2*math.sin(w+.6))]
    elif action == 'beg':
        # Low eager crouch, body wide and flat: each bounce is a small forward hop with a slight rise
        # and a total pitch of only a few degrees; the pleading look comes from the face.
        q = bump((t % c['bounce_seconds'])/c['bounce_seconds']); k = c['crouch']*(1-.55*q)
        r = [c['lean']+c['lean_bounce']*q, 0., c['sway']*math.sin(2*math.pi*t/d)]
        s = [1+.5*k, 1-k, 1+.5*k]; lift = c['bounce_ratio']*height*q
        shift = [0., -c['hop_shift']*height*q]
    elif action == 'bow':
        # `repeat` dips share the clip evenly, each one the 1.2 s bow's down / hold / up beats scaled to its share (the nod preset).
        n = c.get('repeat', 1); p = d/n; u = (t-p*min(int(t/p), n-1))*(1.2/p)
        if u < .45: a = smooth(u/.45)
        elif u < .65: a = 1.
        else: a = 1-smooth((u-.65)/.55)
        r[0] = c['bow']*a; k = c['squash']*a; s = [1+.5*k, 1-k, 1+.5*k]; shift[1] = -c['push']*height*a
    elif action == 'lunge_ram':
        # Base ram (project design; wiki home invasion / defence pushes N1, F15, F07): crouch and draw back (wind-up), dart forward
        # stretched with a small hop (launch..impact), flatten on the near contact face (impact..hold: the front stays where the dart
        # left it, the body behind it squeezes forward, so nothing passes the surface), spring back with a small recoil hop, settle.
        # Travel is the motor's; the clip's forward shift stays within the body. Pitch -8..+4, roll 0.
        m = c['markers']; L, I, S = m['launch'], m['impact'], m['settled']; R = I+c['hold']; B = (R+S)/2
        cr, st, fl, F = c['crouch'], c['stretch'], c['flatten'], c['front_ratio']
        front = -c['lunge']-st*F  # the dart's farthest front, held through the flatten (fractions of the height, -Z forward)
        s = [keyed(t, [(0, 1), (L, 1+.5*cr), (I-.04, 1-.3*st), (I, 1+.6*fl), (R, 1+.6*fl), (B, .99), (S, 1)]),
             keyed(t, [(0, 1), (L, 1-cr), (I-.04, 1-.3*st), (I, 1+.4*fl), (R, 1+.4*fl), (B, 1.02), (S, 1)]),
             keyed(t, [(0, 1), (L, 1+.4*cr), (I-.04, 1+st), (I, 1-fl), (R, 1-fl), (B, 1+.3*st), (S, 1)])]
        r[0] = keyed(t, [(0, 0), (L, c['windup_lean']), (I-.04, c['dart_lean']), (I, c['impact_lean']), (R, c['impact_lean']), (B, c['recoil_lean']), (S, 0)])
        shift[1] = height*keyed(t, [(0, 0), (L, c['draw_back']), (I-.04, front+st*F), (I, front-fl*F), (R, front-fl*F), (B, c['recoil']), (S, 0)])
        lift = height*(c['dart_hop']*bump((t-L)/(I-.04-L)) + c['recoil_hop']*bump((t-R)/(S-R)))
    elif action == 'glass_press':
        # Face squashed on a pane (project design): an eager rise and small draw-back, lean in until the face meets the glass
        # (launch..contact), squash flat against it with one slow extra push (contact..impact: the front stays on the pane, the body
        # behind it squeezes in, a physical squash), then peel back with a small sticky stretch and settle (impact..settled).
        # Travel is the motor's (GlassPress, from the same RamContact front sweep); the clip's forward shift stays within the body.
        m = c['markers']; L, C, I, S = m['launch'], m['contact'], m['impact'], m['settled']
        fl, F, st = c['flatten'], c['front_ratio'], c['stretch']
        p0, p1 = c['push_window']
        q = keyed(t, [(0, 0), (C, 0), (C+.2, 1), (I, 1), (I+.3, 0), (S, 0)])*fl + c['push']*(bump((t-p0)/(p1-p0)) if p0 <= t <= p1 else 0.)
        up = keyed(t, [(0, 1), (L, 1+st), (C, 1), (I, 1), (I+.3, 1+.5*st), (S, 1)])
        s = [1+.6*q, (1+.25*q)*up, 1-q]
        r[0] = keyed(t, [(0, 0), (L, c['rise_lean']), (C, c['lean']), (I, c['lean']), (I+.3, c['peel_lean']), (S, 0)])
        a = keyed(t, [(0, 0), (L, -c['draw_back']), (C, c['reach']), (I, c['reach']), (S, 0)])
        shift[1] = -height*(a + q*F)
    elif action == 'shy':
        # Face turned away with a small sway; once per cycle it peeks back and turns away again.
        ph = 2*math.pi*t/d; peek = max(0., math.sin(ph))**2
        r = [-1.5-2*peek, c['turn']*(1-.6*peek), c['sway']*math.sin(ph)]
        shift[0] = c['shift']*height*math.sin(ph)
    elif action == 'doze_off':
        # Starts upright and ends in the exact sleep pose (height, slump, tilt) so the sleep clip takes over unseen.
        p = smooth(t/d); h = lerp(1., c['height'], p)
        nod = keyed(t, [(0, 0), (.6, 0), (1., -5), (1.25, -1.5), (1.9, -1.5), (2.3, -6), (2.55, -2.5), (3.2, -2.5), (3.7, c['slump']-.5), (d, c['slump'])])
        dip = bump((t-.75)/.7)+bump((t-2.05)/.7)
        wide = 1+(1-h)*.6
        s = [wide, h-.012*dip, wide]; r = [nod, 0., c['tilt']*p+1.2*math.sin(2*math.pi*t/1.5)*(1-p)]
        shift[0] = .004*height*math.sin(2*math.pi*t/1.5)*(1-p)
    elif action == 'struggle':
        ph = 2*math.pi*c['wiggle_hz']*t
        r = [-1., c['yaw']*math.sin(ph), c['roll']*math.sin(ph+1.2)]
        sq = math.sin(2*ph+.5); s = [1+.04*sq, 1-.06*sq, 1+.04*sq]
        q = bump((t % (d/2))/(d/2)); lift = c['hop_ratio']*height*q; shift[0] = c['shift']*height*math.sin(ph+.4)
    elif action == 'basket_in':
        s, lift, e = hop(t, 0, .3, .6, .8, height*c['hop_ratio'])
        shift[1] = -c['reach']*height*smooth((t-.3)/.3); r[0] = c['lean']*e
    elif action == 'basket_out':
        s, lift, e = hop(t, 0, .12, .45, .75, height*c['hop_ratio'])
        shift[1] = -c['reach']*height*bump(t/.6); r[0] = c['lean']*e
        r[1] = keyed(t, [(0, 0), (.75, 0), (1.0, -c['look']), (1.3, c['look']), (1.55, 0), (d, 0)])
    elif action == 'ignore':
        r = [c['chin_up']*smooth((t-.2)/.4)*(1-smooth((t-1.2)/.4)), keyed(t, [(0, 0), (.45, c['turn']), (1.1, c['turn']), (d, c['turn']*c['return'])]), 0.]
        up = smooth((t-.2)/.4)*(1-smooth((t-1.2)/.4)); s = [1-.01*up, 1+.025*up, 1-.01*up]
    elif action == 'restrain':
        # Holding back: compressed and tense, a fine shiver, a small lean back and a glance away.
        shiver = math.sin(2*math.pi*c['shiver_hz']*t); glance = .5-.5*math.cos(2*math.pi*t/d)
        s = [1+.5*c['squash'], 1-c['squash'], 1+.5*c['squash']]; r = [c['lean_back'], -c['glance']*glance, 1.0*shiver]
        shift[0] = c['shiver_ratio']*height*shiver
    elif action == 'snatch_pull':
        # Bite and yank (B8): draw back, dart forward-up with a small hop, then tug back hard with a squash and a quick
        # side-to-side shake of the yaw; pitch never passes 8 degrees, the force is squash, stretch and shift.
        k = keyed(t, [(0, 0), (.2, -.8), (.34, 1), (.48, .3), (.6, -1), (.72, .15), (.9, -.3), (d, 0)])  # + stretch, - squash
        s = [1-.4*c['stretch']*k, 1+c['stretch']*k, 1-.4*c['stretch']*k]
        if k < 0: s = [1-.5*c['squash']*k, 1+c['squash']*k, 1-.5*c['squash']*k]
        r = [keyed(t, [(0, 0), (.2, 3), (.34, c['lunge_pitch']), (.48, -2), (.6, c['tug_pitch']), (.8, 1), (d, 0)]),
             keyed(t, [(0, 0), (.2, 3), (.34, -4), (.52, 0), (.6, 7), (.7, -6), (.8, 3), (.95, 0), (d, 0)]),
             keyed(t, [(0, 0), (.6, 0), (.66, 3.5), (.74, -3.5), (.84, 1.5), (.95, 0), (d, 0)])]
        shift[1] = height*keyed(t, [(0, 0), (.2, c['draw_back']), (.34, -c['lunge']), (.48, -c['lunge']*.55), (.6, c['draw_back']*.8), (.8, 0), (d, 0)])
        lift = c['hop_ratio']*height*bump((t-.24)/.3)
    elif action == 'stand_ground':
        # Planted wide and low with the chest swollen a little (B5/B9): a firm little stomp-hop twice per cycle, a tiny
        # forward lean and a slow glare to either side; every term repeats each cycle so the loop has no seam.
        cyc = c['stomp_seconds']; p = (t % cyc)/cyc; w = 2*math.pi*t/d
        rise = bump(p/.3) if p < .3 else 0.; hit = bump((p-.3)/.28) if .3 <= p < .58 else 0.
        swell = c['swell']*(.7+.3*math.sin(2*w))
        base = c['crouch']
        s = [1+.5*base+swell-.012*hit+.03*hit, 1-base+.4*swell-.05*hit-.01*rise, 1+.5*base+swell-.012*hit+.03*hit]
        r = [c['lean']-1.5*hit, c['glare']*math.sin(w), c['rock']*math.sin(2*w+.6)*(1-.5*hit)]
        lift = c['stomp_ratio']*height*rise; shift[1] = -c['push']*height*(.4+.6*hit); shift[0] = c['sway']*height*math.sin(w)
    elif action == 'offer_bite':
        # Giving a mouthful (B4): lean in, push the mouth area forward with a squash, hold it out, then settle back.
        lean = keyed(t, [(0, 0), (.38, 1), (1.0, 1), (d, 0)]); push = bump((t-.3)/.62) if .3 <= t < .92 else 0.
        s = [1+c['squash']*.5*(lean+push), 1-c['squash']*(.5*lean+push), 1+c['squash']*.5*(lean+push)]
        r = [c['lean']*lean+c['push_lean']*push, c['yaw']*keyed(t, [(0, 0), (.4, 1), (.9, 1), (d, 0)]), 0.]
        shift[1] = -height*(c['lean_shift']*lean+c['push']*push)
        lift = c['hop_ratio']*height*bump((t-.88)/.3)
    elif action == 'hat_brim':
        r = [2, c['turn']*math.sin(2*math.pi*t/d), c['sway']*math.sin(4*math.pi*t/d)]
        s[1] = 1.025  # upright, but no idle floating above the supporting brim
    elif action in ('mouth_item_pick', 'mouth_item_place'):
        # A held item taken from or set on the ground by mouth (project design): bow and sink until the lips are at the
        # item (bite / release), then straighten. Pitch <= 8; the reach is squash and a forward shift.
        beat = c['markers'].get('bite', c['markers'].get('release'))
        lean = keyed(t, [(0, 0), (beat-.1, 1), (beat+.2, 1), (d, 0)])
        s = [1+.04*lean, 1-.08*lean, 1+.04*lean]
        r = [-8*lean, 0., 0.]
        shift[1] = -.06*height*lean
    elif action == 'mouth_release':
        # Reimu lets a young out of her mouth (rule 灵梦口含多只, project design): bow low and gape (open), the young slides out
        # over her lower lip (open..release), a small push as it drops free (release), then she rises and her mouth closes (closed).
        m = c['markers']
        bow = keyed(t, [(0, 0), (m['open'], 1), (m['release'], 1), (m['closed'], .3), (d, 0)])
        gape = keyed(t, [(0, 0), (m['open'], 1), (m['release']-.1, 1), (m['closed'], 0), (d, 0)])
        push = bump((t-(m['release']-.18))/.3)
        s = [1+.03*gape+.03*push, 1-.05*gape-.06*push, 1+.03*gape+.02*push]
        r = [-7*bow-1*push, 0., 0.]
        shift[1] = -.04*height*bow
        lift = .02*height*bump((t-m['closed'])/(d-m['closed']))
    else:
        raise KeyError(action)
    comp = c.get('mouth_comp')
    if comp:
        # A shallower lean must still bring the mouth to its target: trade the lost pitch for a forward
        # shift and a sink in height (keeping the volume), in proportion to how far the clip is leaning.
        env = min(1., max(0., -r[0]/comp['pitch']))
        shift[1] -= comp['forward']*height*env
        s = [s[0]*(1+.5*comp['sink']*env), s[1]*(1-comp['sink']*env), s[2]*(1+.5*comp['sink']*env)]
    return s, r, lift, shift


def carried(model, bone):
    """Hand bone index and the non-skin parts it (or a child bone) carries."""
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    if bone not in names:
        return None, []
    hand = names[bone]; group = {hand}
    for i, b in enumerate(model['bones']):  # the whole chain under the hand (Marisa's braid hangs through several bones)
        if b['parent'] in group: group.add(i)
    parts = [p for p in model['parts'] if 'body_surface' not in p['name'] and not p.get('anatomy')
             and any(int(model['weights'][i][0]) in group for i in range(p['start'], p['start']+p['count']))]
    return hand, parts


def braid_carried(model, bone):
    """Hand bone index and the braid parts it carries. Only the braid is a tool: plain side hair rides the
    same bones on other variants (and on Marisa's other side), so it never gets a gesture of its own."""
    hand, parts = carried(model, bone)
    return hand, [p for p in parts if p['name'].startswith('braid_lock_')]


def braid_length(model, hand, parts):
    pivot = model['bones'][hand]['pivot']; V = model['vertices']
    return max(math.dist(V[i*8:i*8+3], pivot) for p in parts for i in range(p['start'], p['start']+p['count']))


def flourish_tracks(model, action, samples, cfg, c):
    """Rotation flourishes on the hand bones that carry a braid. Side hair, front locks and a ponytail sit on
    the same bones on other variants and only follow the body: a hair lock never paddles, lifts or flaps."""
    tracks = []
    if action in ('hat_stumble','hat_jostle','water_plunge','water_struggle','water_swim','water_gasp','water_recover','water_held_breath','boat_reassure'):
        for bone,side in (('hand_right',1),('hand_left',-1)):
            hand,braid=braid_carried(model,bone)
            if not braid:continue
            def gesture(t):
                if action=='water_struggle':
                    at=max(0,t-(.11 if side<0 else 0))
                    sweep=keyed(at,[(0,0),(.08,1),(.18,-.5),(.29,.7),(.42,0),(1.6,0)])
                    return [-28*sweep,side*9*sweep,side*(8+38*abs(sweep))]
                if action=='water_swim':
                    # The braid strokes (a carried braid only): from water_plunge's last braid pose (hanging, at rest) it swings
                    # forward to the catch tucked in, then is drawn back down and out through the pull (power_start..power_end).
                    u=t/c['duration'];pull=bump((u-.5)/.5)
                    return [-1.8+21*(1-math.cos(2*math.pi*u)),side*5*pull,side*(12.3+16*pull)]
                if action=='water_plunge':
                    q=keyed(t,[(0,.1),(.14,1),(.35,.3),(.6,.15)])
                    return [-12*q,0,side*(9+22*q)]
                if action=='water_gasp':
                    q=keyed(t,[(0,0),(.72,0),(.8,.3),(.94,0),(1.6,0)])
                    return [4-7*q,0,side*(8+9*q)]
                if action=='water_held_breath':
                    q=keyed(t,[(0,0),(.22,.6),(.48,0),(.88,.8),(1.16,0),(1.6,0)])
                    return [2-4*q,0,side*(6+5*q)]
                if action=='water_recover':
                    return [-5*(1-smooth(t/2.8)),0,side*9*(1-smooth(t/2.8))]
                if action=='boat_reassure':
                    q=keyed(t,[(0,0),(.45,1),(1.2,.7),(2,0)])
                    return [-8*q,side*3*q,side*15*q]
                q=keyed(t,[(0,.3),(.15,1),(.35,.5),(.58,.9),(c['duration'],.3)])
                return [10*q,side*5*q,side*(12+19*q)]
            tracks.append({'bone':hand,'channel':'rotation','keys':[[round(t,8),*[round(v,5) for v in gesture(t)]] for t in samples]})
    if action in NEW_BRAID_CLIPS and 'braid_path' not in c:  # a braid_path (aquatic_tools) lays the braid itself
        d=c['duration'];paddle=action in ('hat_brim_offer','boat_nudge_young')
        def lunge(t):return bump((t-.22)/.3)
        def shaker(t):return smooth(t/.15)*(1-smooth((t-1.0)/.2))*math.sin(2*math.pi*2.5*t)
        for bone,side in (('hand_right',1),('hand_left',-1)):
            hand,braid=braid_carried(model,bone)
            if not braid:continue
            def gesture(t):
                if paddle:  # the right braid keeps holding the paddle out over every hull form's gunwale (aquatic_tools.hull_gate); the other rests
                    return [4,0,c['braid_out']+2*math.sin(2*math.pi*t/d)] if side>0 else [0,0,side*(8+3*math.sin(2*math.pi*t/d))]
                if action=='water_mouth_grip':
                    q=.5+.5*math.sin(2*math.pi*t/d);return [2-3*q,0,side*(8+5*q)]
                if action=='water_bob_lunge':
                    q=lunge(t);return [-6*q,0,side*(8+16*q)]
                if action=='hat_slide_over':
                    w=math.sin(2*math.pi*t/.7);return [0,side*3*w,side*(8+5*(.5+.5*w))]
                if action=='water_hat_shake':
                    w=shaker(t);return [0,side*10*w,side*(10+14*abs(w))]
                if action=='water_hat_push':
                    q=bump((t-.2)/.3);return [-8*q,0,side*(8+14*q)]
                q=bump((t-.3)/.3);return [-8*q,0,side*(8+16*q)]  # hat_hop_up
            tracks.append({'bone':hand,'channel':'rotation','keys':[[round(t,8),*[round(v,5) for v in gesture(t)]] for t in samples]})
    if action in ('boat_gather', 'boat_drink'):
        hand, parts = carried(model, 'hand_right')
        if parts:
            def rowing(t):
                phase = 2*math.pi*t/c['duration']
                a = bump(t/c['duration']); return [-18*a, -8*a, 12+12*a]
            tracks.append({'bone': hand, 'channel': 'rotation', 'keys': [[round(t, 8), *[round(v, 5) for v in rowing(t)]] for t in samples]})
    wave = cfg['flourishes']['braid_wave']
    if action in wave['clips'] and c.get('style') == 'genki':
        hand, parts = carried(model, 'hand_right')
        braid = [p for p in parts if p['name'].startswith('braid_lock_')]
        if braid:
            L = braid_length(model, hand, braid); R, F, W, N = wave['raise'], wave['forward'], wave['wave'], wave['waves']
            def lift(t):
                if t < .1: return 0.
                if t < .35: return smooth((t-.1)/.25)
                if t < 1.1: return 1.
                return 1-smooth((t-1.1)/.3)
            def z(t):
                a = lift(t); w = W*math.sin(2*math.pi*N*(t-.35)/.75) if .35 <= t < 1.1 else 0.
                return R*a+w
            off = [x*L for x in wave['root_offset']]
            tracks.append({'bone': hand, 'channel': 'rotation', 'keys': [[round(t, 8), round(F*lift(t), 5), 0, round(z(t), 5)] for t in samples]})
            tracks.append({'bone': hand, 'channel': 'position', 'keys': [[round(t, 8), *[round(o*lift(t), 5) for o in off]] for t in samples]})
    piko = cfg['flourishes']['sideburn_piko']
    if action in piko['clips'] and 'reimu' in model['id']:
        start, end = {'greet': (.2, 1.2), 'shiawase': (.3, 1.5)}[action]
        def flick(t):
            if not start <= t <= end: return 0.
            return piko['flick']*bump(((t-start)/((end-start)/piko['flicks'])) % 1)
        for bone, side in (('hand_right', 1), ('hand_left', -1)):
            hand, parts = carried(model, bone)
            if parts:
                tracks.append({'bone': hand, 'channel': 'rotation', 'keys': [[round(t, 8), 0, 0, round(side*flick(t), 5)] for t in samples]})
    tremble = cfg['flourishes']['lock_tremble']
    if action in tremble['clips']:
        d = c['duration']
        def shake(t): return tremble['degrees']*keyed(t,c['effort_keys'])*math.sin(2*math.pi*tremble['hz']*t)
        for bone, side in (('hand_right', 1), ('hand_left', -1)):
            hand, parts = carried(model, bone)
            if parts:
                tracks.append({'bone': hand, 'channel': 'rotation', 'keys': [[round(t, 8), 0, 0, round(side*shake(t), 5)] for t in samples]})
    return tracks


def family_braid_tracks(model, action, samples, keys, root, hat, c):
    """Move the braid only when it performs actual hat care at the enlarged worn brim.

    Food exchange is deliberately excluded: family feeding is mouth-to-mouth and the renderer
    follows the server's single serving from the caregiver mouth to the child's mouth.
    """
    if action=='boat_carry_young' and model['id'].startswith('marisa'):
        return [{'bone':i,'channel':'rotation','keys':[[round(t,8),*c['braid_fold']] for t in samples]}
                for i,b in enumerate(model['bones']) if b['name'] in ('hand_left','hand_right')]
    if action != 'aquatic_hat_care': return []
    rig = hat['Rig'](model, root); metrics = json.loads(hat['METRICS'].read_text(encoding='utf-8'))
    anchor = np.array(metrics['anchor_worn'])
    brim = anchor+metrics['scale']['aquatic_marisa']*(np.array(metrics['placements']['aquatic']['self']['grip_worn'])-anchor)
    rows = {name: [] for name in ('position', 'rotation', 'scale', 'tip_rotation', 'tip_scale')}
    previous = None
    for i, t in enumerate(samples):
        a = smooth((t-.6)/.5)*(1-smooth((t-1.8)/.6))
        chain = rig.chain(tuple(keys[ch][i][1:] for ch in ('position', 'rotation', 'scale')))
        target = (chain@np.r_[brim, 1])[:3]
        offset = np.array(c.get('braid_root_offset', [0, 0, 0]))*a
        R, sy = hat['chain_to'](np.linalg.inv(chain), target, rig, offset)  # curved onto the brim, taken as far as the reach
        sy = 1+(sy-1)*a; rotation = [hat['to_euler'](hat['braid_chain'].partial(r, a)) for r in R]
        if previous is not None: rotation = [[v+360*round((p-v)/360) for v, p in zip(r, q)] for r, q in zip(rotation, previous)]
        previous = rotation
        values = {'position': offset, 'rotation': rotation[0], 'scale': [1, sy, 1],
                  'tip_rotation': [0, 0, 25*a], 'tip_scale': [1, 1/sy, 1], **{f'inner_{k}': r for k, r in enumerate(rotation[1:])}}
        for channel, values_at in values.items(): rows.setdefault(channel, []).append([round(t, 8), *[round(float(v), 6) for v in values_at]])
    out = [{'bone': rig.braid, 'channel': channel, 'keys': rows[channel]} for channel in ('position', 'rotation', 'scale')]
    out += [{'bone': b, 'channel': 'rotation', 'keys': rows[f'inner_{k}']} for k, b in enumerate(rig.inner)]
    for bone in rig.tips:
        for channel in ('rotation', 'scale'): out.append({'bone': bone, 'channel': channel, 'keys': rows['tip_'+channel]})
    return out


def applies(cfg, group, ident):
    rule = cfg['groups'][group]
    only, exclude = (rule, []) if isinstance(rule, list) else (rule['only'], rule.get('except', []))
    return any(fnmatch(ident, p) for p in only) and not any(fnmatch(ident, p) for p in exclude)


def variant(c, ident):
    for v in c.get('variants', {}).values():
        if any(fnmatch(ident, p) for p in v['match']):
            return {**c, **{k: x for k, x in v.items() if k != 'match'}}
    return c


def faces(cfg, c, ident):
    """Expression timeline with every face resolved to a texture the model has."""
    folder = TEXTURES/ident; out = []
    def resolve(face):
        for f in [face]+cfg['face_fallback'].get(face, [])+['neutral']:
            if (folder/f'{f}.png').exists(): return f
        raise FileNotFoundError(f'{ident}: no texture for {face}')
    for start, end, face in c['faces']:
        out.append([start, end, resolve(face)])
    return out


def resolve(clips, name):
    """A preset: its `base` clip (itself possibly a preset) with its own overrides, authored once. `motion` names the clip whose
    motion code it plays (rain_huddle_left and cling_* play rain_huddle_right's, nod plays bow's)."""
    entry = clips[name]
    if 'base' not in entry: return entry
    base = resolve(clips, entry['base'])
    return {**base, 'motion': base.get('motion', entry['base']), **{k: v for k, v in entry.items() if k != 'base'}}


def compile_clips(model, cfg, root, ground, height, cache=None):
    """ground(scale, rotation) -> y offset that keeps the lowest visible point on the floor."""
    out = {}; ident = model['id']
    hat = runpy.run_path(str(Path(__file__).resolve().parent/'hat.py'))
    for name in cfg['clips']:
        base = resolve(cfg['clips'], name)
        if not applies(cfg, base['applies'], ident): continue
        c = variant(base, ident)
        if cache:
            from motion_compile_cache import lore_inputs
            key = cache.key('lore', name, lore_inputs(cfg, name))
            saved = cache.get(key)
            if saved is not None:
                out[name] = saved
                continue
        if 'hat' in c:  # the braid works the hat (hat.py): its own root, hat and braid tracks
            tracks, worst = (hat['compile_hold'](model, cfg, root, ground, height, c) if 'phases' not in c or not c['phases']
                             else hat['compile_hat'](model, cfg, root, ground, height, name, c))
            if isinstance(worst, dict) and 'ground' in worst:
                print(f"  {name} ({ident}): hat lowest {worst['ground']:.2f} above her feet while it swings (carry phase)", flush=True)
            out[name] = {'duration': c['duration'], 'loop': c['loop'], 'tracks': tracks,
                         'expression_spans': faces(cfg, c, ident), 'expression_yield': cfg['expression_yield'],
                         **({'markers': dict(c['markers'])} if 'markers' in c else {})}
            if 'stick' in c['hat'] and 'hand_right_tip' in {b['name'] for b in model['bones']}:  # the held stack stays in the tuft on the brim
                n = math.ceil(c['duration']/cfg['sample_seconds'])
                out[name]['props'] = {'item': TOOLS['stow_tracks'](model, [c['duration']*i/n for i in range(n+1)], tracks, c)}
            if cache:
                cache.put(key, out[name])
            continue
        carry_pose=name in ('boat_carry_young','boat_be_carried','boat_prepare_carry','boat_release_carry')
        # Contact preparation uses exact server-tick samples; extra keys also bound sub-tick floor drift.
        d = c['duration']; n = math.ceil(d/(min(.01,cfg['sample_seconds']) if carry_pose else cfg['sample_seconds'])); samples = [d*i/n for i in range(n+1)]
        carry_floor=min(model['vertices'][(p['start']+i)*8+1] for p in model['parts'] if not p.get('anatomy') and not p['name'].startswith('puff_') for i in range(p['count'])) if carry_pose else 0
        keys = {'position': [], 'rotation': [], 'scale': []}
        for t in samples:
            s, r, lift, shift = state(c.get('motion', name), t, c, height)
            support=0 if c.get('root_support') in ('free_water','shared_braid') else TOOLS['bank_support'](model,s,r,shift,c['stroke']['edge']) if c.get('root_support')=='bank' else ground(s,r)-carry_floor
            for channel, value in (('position', [shift[0], support+lift, shift[1]]), ('rotation', r), ('scale', s)):
                keys[channel].append([round(t, 8), *[round(x, 7) for x in value]])
        tracks = [{'bone': root, 'channel': ch, 'keys': k} for ch, k in keys.items()]
        tracks += flourish_tracks(model, name, samples, cfg, c)+family_braid_tracks(model, name, samples, keys, root, hat, c)
        tracks += push_tracks(model, c, height)
        fields = {}
        if BRAID_KEYS['authored'](c):
            tracks += BRAID_KEYS['tracks'](model, name, c, samples, tracks, TOOLS['palette'])
            tip = BRAID_KEYS['tip_track'](model, samples, tracks, TOOLS['palette'])
            BRAID_KEYS['gate'](model, name, samples, tracks, tip, c, TOOLS['palette'])
            fields = {'markers': dict(c['markers']), 'tip': tip}
        elif name in TOOLS['EXPORT_CLIPS']:
            if name not in NEW_BRAID_CLIPS: tracks += TOOLS['braid_tracks'](model, name, samples, c)
            more, fields = TOOLS['extras'](model, name, samples, tracks, c); tracks += more
        elif 'markers' in c: fields = {'markers': dict(c['markers'])}  # beats the game reads (ClipMarkers), e.g. hop_leg
        out[name] = {'duration': d, 'loop': c['loop'], 'tracks': tracks,
                     'expression_spans': faces(cfg, c, ident), 'expression_yield': cfg['expression_yield'], **fields}
        if 'hat_transfer' in c:out[name]['hat_transfer']=c['hat_transfer']
        if cache:
            cache.put(key, out[name])
    for name in cfg.get('braid_use', {}).get('clips', ()):
        if name in out: out[name]['braid_use'] = True  # its own braid grips, carries or strokes (recipe braid_use)
    for name in ('boat_paddle', 'boat_paddle_braid'):
        if name in out:
            source = cfg['clips'][name]
            out[name].setdefault('parameters', {})['stroke'] = dict(source['capture_stroke'] if 'capture_stroke' in source else source['stroke'])
    holds = {where: out[clip] for where, clip in (('ashore', 'braid_item_hold'), ('aboard', 'boat_paddle')) if clip in out}
    for name, clip in out.items():
        if 'hat' in cfg['clips'][name] and 'props' in clip:  # the hat clips that keep the held stick (hat.stick), from either hold
            if len(holds) == 2:
                def gate():
                    TOOLS['stow_gate'](model, name, clip['tracks'], clip['props']['item'], holds, variant(cfg['clips'][name], ident))
                    return True
                if cache:
                    # stow_gate also measures the stick from HOLD (top, blade, twig radius),
                    # which the exported clip and hold payloads do not carry.
                    cache.memo('stow-gate', name, [clip, holds, variant(cfg['clips'][name], ident), TOOLS['HOLD']], gate)
                else:
                    gate()
            else: print(f'  {name}: stick gate skipped (the hold clips are not in this export)', flush=True)
    return out


def export_family(family, prune_only=False, check=False):
    """Export only this new clip family, preserving unrelated selected meshes, cheek morphs and existing clips."""
    import hashlib,sys
    sys.path.insert(0,str(ROOT/'scripts'))
    import placement_fit
    def write(path, text):
        if check:
            assert path.read_text(encoding='utf-8') == text, f'Stale export: {path.relative_to(ROOT)}'
        else:
            path.write_text(text,encoding='utf-8',newline='\n')
    here=Path(__file__).resolve().parent
    resource=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json'
    manifest=ROOT/'art/source/motion-corrections/manifest.json'
    cfg=json.loads((here/'recipe.json').read_text(encoding='utf-8'))
    contact_cfg=json.loads((here.parent/'contact-safe/recipe.json').read_text(encoding='utf-8'))
    authored_names=set(cfg['clips']) | set(contact_cfg['actions']) | set(contact_cfg['braid_follow']['actions'])
    selection=cfg['export_families'][family]
    follows=selection.get('braid_follow',False)
    include_birth=selection.get('birth_pose',False)
    include_weather=selection.get('side_profile',False)
    contact_author=runpy.run_path(str(here.parent/'contact-safe/author.py'))
    names=selection['clips'] if 'clips' in selection else runpy.run_path(str(here/selection['clip_source']))[selection['clip_field']]
    cfg['clips']={name:resolve(cfg['clips'],name) for name in names}
    payload=json.loads(resource.read_text(encoding='utf-8'));fits=placement_fit.load()
    payload['revision']=RUNTIME_REVISION
    birth_poses={'format':1,'units':'model_pixels','ticks_per_second':20,'variants':{}}
    for ident,revision in payload['models'].items():
        revision['clips']={name:clip for name,clip in revision['clips'].items() if name in authored_names}
        if prune_only: continue
        model=placement_fit.fitted(ROOT/f'src/main/resources/assets/mcyukkuri/yukkuri/{ident}.json',fits)
        root=next(i for i,bone in enumerate(model['bones']) if bone['name']=='root')
        pivot=np.array(model['bones'][root]['pivot']);raw=np.array(model['vertices']).reshape(-1,8)
        points=np.concatenate([raw[p['start']:p['start']+p['count'],:3] for p in model['parts']
                               if not p.get('anatomy') and not p['name'].startswith('puff_')])
        points=np.unique(points,axis=0);floor=points[:,1].min()
        body=np.concatenate([raw[p['start']:p['start']+p['count'],:3] for p in model['parts'] if 'body_surface' in p['name']])
        height=body[:,1].max()-body[:,1].min()
        def ground(scale,rot):
            x,y,z=map(math.radians,rot);cx,sx,cy,sy,cz,sz=math.cos(x),math.sin(x),math.cos(y),math.sin(y),math.cos(z),math.sin(z)
            row=np.array([sz*cy,(sz*sy*sx+cz*cx),(sz*sy*cx-cz*sx)])*scale
            return floor-((points-pivot)@row+pivot[1]).min()
        authored=compile_clips(model,cfg,root,ground,height)
        if follows:
            follow=dict(contact_cfg['braid_follow'],actions=[name for name in cfg['braid_follow'] if name in names and name in authored])
            authored.update(contact_author['braid_follow'](model,dict(contact_cfg,braid_follow=follow),authored))
        authored=contact_author['finish_chain'](model,contact_cfg,authored,set(TOOLS['EXPORT_CLIPS']))
        if include_birth:
            clips={}
            for name in ('labor','boat_labor'):
                if name not in authored:continue
                clip=authored[name]
                clips[name]=dict(duration=clip['duration'],loop=clip['loop'],**{tr['channel']:tr['keys'] for tr in clip['tracks'] if tr['bone']==root})
            if clips:birth_poses['variants'][ident]=dict(pivot=pivot.tolist(),clips=clips)
        for clip in authored.values():
            for track in clip['tracks']:
                name=model['bones'][track['bone']]['name']
                if name not in revision['bone_names']:revision['bone_names'].append(name)
                track['bone']=revision['bone_names'].index(name)
        revision['clips'].update(authored)
        print('Exported selected lore clips:',ident,flush=True)
    if include_birth:
        write(resource.parent/'live_birth_pose.json',json.dumps(birth_poses,ensure_ascii=False,separators=(',',':'))+'\n')
    write(resource,json.dumps(payload,ensure_ascii=False,separators=(',',':'))+'\n')
    if include_weather:  # the side reach of every model through its huddle loop, for the server's side-by-side stand
        write(resource.parent/'side_profile.json',runpy.run_path(str(here/'side_profile.py'))['build']())
    entry=json.loads(manifest.read_text(encoding='utf-8'))
    for field,path in (('recipe',here.parent/'contact-safe/recipe.json'),('author',here.parent/'contact-safe/author.py'),
                       ('lore_recipe',here/'recipe.json'),('lore_author',here/'author.py'),('lore_aquatic_tools',here/'aquatic_tools.py'),('runtime',resource),
                       ('braid_rescue_recipe',ROOT/'standards/yukkuri-library/interactions/braid-water-rescue/recipe.json'),
                       ('braid_rescue_author',ROOT/'standards/yukkuri-library/interactions/braid-water-rescue/author.py'),
                       ('braid_rescue_geometry',ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json')):
        entry[field]=path.relative_to(ROOT).as_posix();entry[field+'_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
    if include_weather:
        profile_path=resource.parent/'side_profile.json'
        entry['side_profile']=profile_path.relative_to(ROOT).as_posix()
        entry['side_profile_sha256']=hashlib.sha256(profile_path.read_bytes()).hexdigest()
    if include_birth:
        pose_path=resource.parent/'live_birth_pose.json'
        entry['birth_pose']=pose_path.relative_to(ROOT).as_posix()
        entry['birth_pose_sha256']=hashlib.sha256(pose_path.read_bytes()).hexdigest()
    write(manifest,json.dumps(entry,ensure_ascii=False,indent=2)+'\n')


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description='Export recipe-selected lore motion families.')
    parser.add_argument('--check',action='store_true',help='Compare selected exports without writing files.')
    parser.add_argument('--prune',action='store_true',help='Remove clips absent from both authoring recipes, preserving every retained clip.')
    parser.add_argument('--family',required=True,choices=json.loads((Path(__file__).parent/'recipe.json').read_text(encoding='utf-8'))['export_families'])
    args=parser.parse_args()
    export_family(args.family,prune_only=args.prune,check=args.check)
