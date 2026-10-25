"""World-setting motions, compiled per runtime model by contact-safe/author.py.

Each clip is a whole-body root transform sampled every `sample_seconds`: scale, rotation
(degrees, composed Rz*Ry*Rx as in the game), lift above the landed pose and an x/z shift,
both in model units. The caller lands every sample on the lowest visible point, so a lean
pivots on the floor instead of sinking into it. Forward is -Z (the face side); a negative
X rotation leans the head forward. Flourishes add rotation tracks to hand bones that carry
hair; the hat clips (hat.py) move Marisa's braid and hat as well: Marisa's braid waves, Reimu's sideburns flick, locks tremble in labor. Each clip
carries an expression timeline, resolved per model against its textures, and is compiled
only into the models its `applies` group names. See README.md and recipe.json.
"""
from fnmatch import fnmatch
from pathlib import Path
import json, math, runpy
import numpy as np

ROOT = Path(__file__).resolve().parents[5]
TEXTURES = ROOT/'src/main/resources/assets/mcyukkuri/textures/entity'
DISTRESS_CLIPS=('hat_stumble','hat_jostle','water_plunge','water_struggle','water_gasp','water_recover','water_held_breath','boat_reassure',
                'boat_alarm_call','boat_alarm_wait','boat_answer_call','boat_flee','boat_calm',
                'braid_rescue_reach','braid_rescue_hold','braid_rescue_settle',
                'water_hat_flip','water_hat_grip','water_hat_climb','water_hat_pull','water_hat_over',
                'hat_brim_offer','hat_hop_up','water_bob_lunge','hat_slide_over','water_mouth_grip','water_hat_shake','water_hat_push','boat_nudge_young',
                'braid_reach_low')
# Birth clips exported by --export-birth: the labour cycle and lead-in, both helpers, the aftermath, the newborn's wake and the mother's own hat.
BIRTH_CLIPS=('labor','boat_labor','midwife','postpartum','first_wake','hat_hold','hat_hold_aq','hat_off_self','hat_on_self','hat_off_self_aq','hat_on_self_aq')
# Badge social-arc clips and the clips refitted to the 12 degree tilt limit in motion round 2, exported together by --export-badge.
BADGE_CLIPS=('snatch_pull','stand_ground','offer_bite')
# Worried comfort lick for an injured mate or kin, exported alone by --export-comfort.
COMFORT_CLIPS=('comfort_lick',)
TILT_FIX_CLIPS=('eat','drink','first_toilet_assist','newborn_crawl','nest_harvest','nest_place','nest_spread','greet',
                'hat_off_aq','hat_on_aq','water_plunge','water_recover','water_hat_pull','water_hat_over')
# Body pitch -8..+6, roll <= 6 (hard cap 12): effort is squash, stretch, shift, yaw and the face, never a whole-body flip.
NEW_BRAID_CLIPS=('hat_brim_offer','hat_hop_up','water_bob_lunge','hat_slide_over','water_mouth_grip','water_hat_shake','water_hat_push','boat_nudge_young')
_BRAID_RESCUE=None
# Paddle stick, braid cradle, mouth bite, capsize and self-rescue clips (aquatic_tools.py), exported by --export-aquatic.
TOOLS=runpy.run_path(str(Path(__file__).resolve().parent/'aquatic_tools.py'))


def smooth(x):
    x = min(1.0, max(0.0, x)); return x*x*(3-2*x)


def bump(x):
    """0 -> 1 -> 0 over x in [0, 1]."""
    return math.sin(math.pi*min(1.0, max(0.0, x)))**2


def lerp(a, b, q): return a+(b-a)*q


def squash(a, wide=.06, low=.1, deep=.04): return [1+wide*a, 1-low*a, 1+deep*a]


def times(scale, other): return [x*y for x, y in zip(scale, other)]


def hop(t, start, takeoff, land, end, height):
    """Crouch, jump and land. Returns (scale, lift, air) for a small hop."""
    if t < start or t >= end:
        return [1., 1., 1.], 0., 0.
    if t < takeoff:
        return squash(smooth((t-start)/(takeoff-start))), 0., 0.
    if t < land:
        air = (t-takeoff)/(land-takeoff); e = math.sin(math.pi*air)
        release = 1-smooth(air/.25)  # the crouch springs open at take-off
        return times(squash(release), [1-.04*e, 1+.1*e, 1-.03*e]), height*4*air*(1-air), e
    return squash(bump((t-land)/(end-land)), .05, .08, .03), 0., 0.


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
        r=[keyed(t,[(0,0),(.18,3),(.34,-8),(.55,-3),(.8,-1)]),0,0]
        shift[1]=keyed(t,[(0,0),(.18,.3),(.34,-.9),(.6,-.3),(.8,0)])
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
    elif action in ('boat_paddle', 'boat_gather', 'boat_eat', 'boat_drink', 'boat_sleep', 'boat_wake', 'boat_board', 'boat_toilet',
                  'boat_feed_young', 'boat_nuzzle', 'boat_lullaby', 'boat_first_balance'):
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
        elif action == 'boat_lullaby':
            r = [2, 0, 2.5*math.sin(phase*2)]; s[1] += .008*(1-math.cos(phase*4))
        elif action == 'boat_gather':
            reach = bump(t/d); r = [-7*reach, -9*reach, -3*reach]
            s[1] -= .025*reach
        elif action == 'boat_eat':
            chew = bump((t % .4)/.4); r[0] = -5-2*chew; s[1] -= .025*chew
        elif action == 'boat_drink':
            sip = bump(t/d); r[0] = -9*sip; r[2] = -2*sip
        elif action == 'boat_sleep':
            s[1] += .012*(1-math.cos(phase))/2; r = [-3, 0, 2]
        elif action == 'boat_wake':
            a = bump(t/d); s[1] = .91+.09*a+.04*smooth(t/d); r[0] = 4*a
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
    elif action == 'wake':
        # Starts from the sleep pose so waking does not pop.
        rest, yawn = 1+(1-.94)*.6, c['yawn_height']
        if t < .7:
            a = smooth(t/.7); s = [lerp(rest, .96, a), lerp(.94, yawn, a), lerp(rest, .96, a)]
            r = [lerp(-3, c['lean_back'], a), 0., lerp(4, 0, a)]
        elif t < .9:
            a = smooth((t-.7)/.2); s = [lerp(.96, 1, a), lerp(yawn, 1, a), lerp(.96, 1, a)]
            r[0] = lerp(c['lean_back'], 0, a)
        else:
            r[1] = c['shake']*math.sin(2*math.pi*(t-.9)/.23)*(1-(t-.9)/(d-.9))**2
    elif action == 'aquatic_hat_care':
        shake = (1-smooth(t/.9))*math.sin(2*math.pi*t/.22)
        tidy = smooth((t-.6)/.5)*(1-smooth((t-1.8)/.6))
        r = [-2*tidy, 10*shake, -2*tidy]; s[1] = 1-.02*abs(shake)
    elif action in ('nuzzle', 'comfort_lick'):
        w = math.sin(2*math.pi*t/d)
        r = [c['lean'], 0., c['rub']*w]; shift[0] = c['rub_shift']*height*w; s = [1., 1-.015*abs(w), 1.]
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
        r[0] = c['tilt']*q
        s = [1+.04*q, 1-.10*q, 1+.04*q]
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
        s, lift, _ = hop(t, 0, .2, .6, .75, height*c['hop_ratio'])
        r[0] = c['bow']*bump((t-.8)/.5) if .8 <= t <= 1.3 else 0.
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
            # Gather the newborn into the hat: turn toward it, lean in, come back.
            e = bump((t-c['birth_seconds'])/(cycle-c['birth_seconds'])); s = base
            s[1]+=.015*bump(((t-c['birth_seconds'])/.8)%1)*e
            r = [keyed(t, c['pitch_keys'])+c['tuck_lean']*e, c['tuck_turn']*e, 0.]
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
        ph = 2*math.pi*t/d; wide = 1+(1-c['height'])*.5
        s = [c['width'], c['height']+.01*math.sin(ph), c['width']]; s[0] = wide
        r = [c['head_down'], c['turn'], c['tilt']]
        if 1.4 <= t < 1.9:
            w = bump((t-1.4)/.5)*math.sin(2*math.pi*9*t); r[2] += c['shudder']*w; shift[0] = .006*height*w
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
        if t < .45: a = smooth(t/.45)
        elif t < .65: a = 1.
        else: a = 1-smooth((t-.65)/.55)
        r[0] = c['bow']*a; k = c['squash']*a; s = [1+.5*k, 1-k, 1+.5*k]; shift[1] = -c['push']*height*a
    elif action == 'shy':
        # Face turned away with a small sway; once per cycle it peeks back and turns away again.
        ph = 2*math.pi*t/d; peek = max(0., math.sin(ph))**2
        r = [-1.5-2*peek, c['turn']*(1-.6*peek), c['sway']*math.sin(ph)]
        s = [1+.025*(1-peek), 1-.04*(1-peek), 1+.025*(1-peek)]; shift[0] = c['shift']*height*math.sin(ph)
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
    hand = names[bone]; group = {hand} | {i for i, b in enumerate(model['bones']) if b['parent'] == hand}
    parts = [p for p in model['parts'] if 'body_surface' not in p['name'] and not p.get('anatomy')
             and any(int(model['weights'][i][0]) in group for i in range(p['start'], p['start']+p['count']))]
    return hand, parts


def braid_length(model, hand, parts):
    pivot = model['bones'][hand]['pivot']; V = model['vertices']
    return max(math.dist(V[i*8:i*8+3], pivot) for p in parts for i in range(p['start'], p['start']+p['count']))


def flourish_tracks(model, action, samples, cfg, c):
    tracks = []
    if action in ('hat_stumble','hat_jostle','water_plunge','water_struggle','water_gasp','water_recover','water_held_breath','boat_reassure'):
        for bone,side in (('hand_right',1),('hand_left',-1)):
            hand,parts=carried(model,bone)
            if not parts:continue
            def gesture(t):
                if action=='water_struggle':
                    at=max(0,t-(.11 if side<0 else 0))
                    sweep=keyed(at,[(0,0),(.08,1),(.18,-.5),(.29,.7),(.42,0),(1.6,0)])
                    return [-28*sweep,side*9*sweep,side*(8+38*abs(sweep))]
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
    if action in NEW_BRAID_CLIPS:
        d=c['duration'];paddle=action in ('hat_brim_offer','boat_nudge_young')
        def lunge(t):return bump((t-.22)/.3)
        def shaker(t):return smooth(t/.15)*(1-smooth((t-1.0)/.2))*math.sin(2*math.pi*2.5*t)
        for bone,side in (('hand_right',1),('hand_left',-1)):
            hand,parts=carried(model,bone)
            if not parts:continue
            def gesture(t):
                if paddle:  # the right braid keeps holding the paddle out over the brim (aquatic hull 1.75); the other rests
                    return [4,0,43+2*math.sin(2*math.pi*t/d)] if side>0 else [0,0,side*(8+3*math.sin(2*math.pi*t/d))]
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
    pivot = rig.pivot(rig.braid); tip = rig.pivot(rig.tips[0]); v0 = tip-pivot
    anchor = np.array(metrics['anchor_worn'])
    brim = anchor+metrics['aquatic_scale']*(np.array(metrics['placements']['aquatic']['self']['grip_worn'])-anchor)
    rows = {name: [] for name in ('position', 'rotation', 'scale', 'tip_rotation', 'tip_scale')}
    previous = None
    for i, t in enumerate(samples):
        a = smooth((t-.6)/.5)*(1-smooth((t-1.8)/.6))
        chain = rig.chain(tuple(keys[ch][i][1:] for ch in ('position', 'rotation', 'scale')))
        start = (chain@np.r_[tip, 1])[:3]
        target = (chain@np.r_[brim, 1])[:3]
        offset = np.array(c.get('braid_root_offset', [0, 0, 0]))*a
        R, sy = hat['braid_to'](np.linalg.inv(chain), start+(target-start)*a, pivot+offset, v0)
        rotation = hat['to_euler'](R)
        if previous is not None: rotation = [v+360*round((p-v)/360) for v, p in zip(rotation, previous)]
        previous = rotation
        values = {'position': offset, 'rotation': rotation, 'scale': [1, sy, 1],
                  'tip_rotation': [0, 0, 25*a], 'tip_scale': [1, 1/sy, 1]}
        for channel, values_at in values.items(): rows[channel].append([round(t, 8), *[round(float(v), 6) for v in values_at]])
    out = [{'bone': rig.braid, 'channel': channel, 'keys': rows[channel]} for channel in ('position', 'rotation', 'scale')]
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


def compile_clips(model, cfg, root, ground, height):
    """ground(scale, rotation) -> y offset that keeps the lowest visible point on the floor."""
    out = {}; ident = model['id']
    hat = runpy.run_path(str(Path(__file__).resolve().parent/'hat.py'))
    for name, base in cfg['clips'].items():
        if not applies(cfg, base['applies'], ident): continue
        c = variant(base, ident)
        if 'hat' in c:  # the braid works the hat (hat.py): its own root, hat and braid tracks
            tracks, _ = (hat['compile_hold'](model, cfg, root, ground, height, c) if 'phases' not in c or not c['phases']
                         else hat['compile_hat'](model, cfg, root, ground, height, name, c))
            out[name] = {'duration': c['duration'], 'loop': c['loop'], 'tracks': tracks,
                         'expression_spans': faces(cfg, c, ident), 'expression_yield': cfg['expression_yield']}
            continue
        carry_pose=name in ('boat_carry_young','boat_be_carried','boat_prepare_carry','boat_release_carry')
        # Contact preparation uses exact server-tick samples; extra keys also bound sub-tick floor drift.
        d = c['duration']; n = math.ceil(d/(min(.01,cfg['sample_seconds']) if carry_pose else cfg['sample_seconds'])); samples = [d*i/n for i in range(n+1)]
        carry_floor=min(model['vertices'][(p['start']+i)*8+1] for p in model['parts'] if not p.get('anatomy') and not p['name'].startswith('puff_') for i in range(p['count'])) if carry_pose else 0
        keys = {'position': [], 'rotation': [], 'scale': []}
        for t in samples:
            s, r, lift, shift = state(name, t, c, height)
            support=0 if c.get('root_support') in ('free_water','shared_braid') else ground(s,r)-carry_floor
            for channel, value in (('position', [shift[0], support+lift, shift[1]]), ('rotation', r), ('scale', s)):
                keys[channel].append([round(t, 8), *[round(x, 7) for x in value]])
        tracks = [{'bone': root, 'channel': ch, 'keys': k} for ch, k in keys.items()]
        tracks += flourish_tracks(model, name, samples, cfg, c)+family_braid_tracks(model, name, samples, keys, root, hat, c)
        fields = {}
        if name in TOOLS['EXPORT_CLIPS']:
            if name not in NEW_BRAID_CLIPS: tracks += TOOLS['braid_tracks'](model, name, samples, c)
            more, fields = TOOLS['extras'](model, name, samples, tracks, c); tracks += more
        out[name] = {'duration': d, 'loop': c['loop'], 'tracks': tracks,
                     'expression_spans': faces(cfg, c, ident), 'expression_yield': cfg['expression_yield'], **fields}
        if 'hat_transfer' in c:out[name]['hat_transfer']=c['hat_transfer']
    return out


def export_distress_clips(include_pet=False, include_birth=False, include_carry=False, include_badge=False, include_comfort=False, include_aquatic=False):
    """Export only this new clip family, preserving unrelated selected meshes, cheek morphs and existing clips."""
    import hashlib,sys
    sys.path.insert(0,str(ROOT/'scripts'))
    import placement_fit
    here=Path(__file__).resolve().parent
    resource=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json'
    manifest=ROOT/'art/source/motion-corrections/manifest.json'
    cfg=json.loads((here/'recipe.json').read_text(encoding='utf-8'))
    contact_cfg=json.loads((here.parent/'contact-safe/recipe.json').read_text(encoding='utf-8')) if include_pet or include_birth or include_badge or include_comfort else None
    contact_author=runpy.run_path(str(here.parent/'contact-safe/author.py')) if include_pet or include_birth or include_badge or include_comfort else None
    names=TOOLS['EXPORT_CLIPS'] if include_aquatic else COMFORT_CLIPS if include_comfort else ('boat_carry_young','boat_be_carried','boat_prepare_carry','boat_release_carry') if include_carry else BIRTH_CLIPS if include_birth else BADGE_CLIPS+TILT_FIX_CLIPS if include_badge else DISTRESS_CLIPS
    if include_pet:
        names+=('think','nervous','proud','joy_jump','sulk','hide_face','beg','bow','shy','doze_off','struggle','basket_in','basket_out','ignore','restrain')
    cfg['clips']={name:cfg['clips'][name] for name in names}
    payload=json.loads(resource.read_text(encoding='utf-8'));fits=placement_fit.load()
    birth_poses={'format':1,'units':'model_pixels','ticks_per_second':20,'variants':{}}
    for ident,revision in payload['models'].items():
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
        if include_pet or include_birth or include_badge or include_comfort:
            follow=dict(contact_cfg['braid_follow'],actions=[name for name in cfg['braid_follow'] if name in names and name in authored])
            authored.update(contact_author['braid_follow'](model,dict(contact_cfg,braid_follow=follow),authored))
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
        (resource.parent/'live_birth_pose.json').write_text(json.dumps(birth_poses,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8',newline='\n')
    payload['revision']='aquatic-home-20261026-v32' if include_aquatic else 'comfort-lick-20261017-v29' if include_comfort else 'hat-care-contact-20261017-v26' if include_carry else 'birth-staging-20261017-v27' if include_birth else 'badge-motion-20261017-v28' if include_badge else 'braid-reach-low-20261018-v30'
    resource.write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8',newline='\n')
    entry=json.loads(manifest.read_text(encoding='utf-8'))
    for field,path in (('lore_recipe',here/'recipe.json'),('lore_author',here/'author.py'),('lore_aquatic_tools',here/'aquatic_tools.py'),('runtime',resource),
                       ('braid_rescue_recipe',ROOT/'standards/yukkuri-library/interactions/braid-water-rescue/recipe.json'),
                       ('braid_rescue_author',ROOT/'standards/yukkuri-library/interactions/braid-water-rescue/author.py'),
                       ('braid_rescue_geometry',ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json')):
        entry[field]=path.relative_to(ROOT).as_posix();entry[field+'_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
    if include_birth:
        pose_path=resource.parent/'live_birth_pose.json'
        entry['birth_pose']=pose_path.relative_to(ROOT).as_posix()
        entry['birth_pose_sha256']=hashlib.sha256(pose_path.read_bytes()).hexdigest()
    if include_pet:
        entry['exporter_sha256']=hashlib.sha256((ROOT/entry['exporter']).read_bytes()).hexdigest()
    manifest.write_text(json.dumps(entry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description='Generate the new water-distress and hat-balance runtime clip family.')
    parser.add_argument('--export-distress',action='store_true')
    parser.add_argument('--export-pet-and-distress',action='store_true',help='Re-export both merged clip families without rebuilding unrelated cheek morphs.')
    parser.add_argument('--export-birth',action='store_true',help='Export the birth clips (BIRTH_CLIPS) and live_birth_pose.json only, preserving other runtime clips.')
    parser.add_argument('--export-carry',action='store_true',help='Export the supported prepare, hold and release carry poses only.')
    parser.add_argument('--export-badge',action='store_true',help='Export the badge-arc clips (BADGE_CLIPS) and the tilt-refitted clips (TILT_FIX_CLIPS) only.')
    parser.add_argument('--export-comfort',action='store_true',help='Export the comfort_lick clip only, preserving other runtime clips.')
    parser.add_argument('--export-aquatic',action='store_true',help='Export the paddle, braid cradle, mouth bite, capsize and self-rescue clips (aquatic_tools.py) only.')
    args=parser.parse_args()
    if args.export_distress or args.export_pet_and_distress:export_distress_clips(args.export_pet_and_distress)
    if args.export_birth:export_distress_clips(include_birth=True)
    if args.export_carry:export_distress_clips(include_carry=True)
    if args.export_badge:export_distress_clips(include_badge=True)
    if args.export_comfort:export_distress_clips(include_comfort=True)
    if args.export_aquatic:export_distress_clips(include_aquatic=True)
