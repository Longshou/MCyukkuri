"""World-setting motions, compiled per runtime model by contact-safe/author.py.

Each clip is a whole-body root transform sampled every `sample_seconds`: scale, rotation
(degrees, composed Rz*Ry*Rx as in the game), lift above the landed pose and an x/z shift,
both in model units. The caller lands every sample on the lowest visible point, so a lean
pivots on the floor instead of sinking into it. Forward is -Z (the face side); a negative
X rotation leans the head forward. Flourishes add rotation tracks to hand bones that carry
hair: Marisa's braid waves, Reimu's sideburns flick, locks tremble in labor. Each clip
carries an expression timeline, resolved per model against its textures, and is compiled
only into the models its `applies` group names. See README.md and recipe.json.
"""
from fnmatch import fnmatch
from pathlib import Path
import math

ROOT = Path(__file__).resolve().parents[5]
TEXTURES = ROOT/'src/main/resources/assets/mcyukkuri/textures/entity'


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


def state(action, t, c, height):
    s, r, lift, shift = [1., 1., 1.], [0., 0., 0.], 0., [0., 0.]
    d = c['duration']
    if action == 'eat':
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
    elif action == 'nuzzle':
        w = math.sin(2*math.pi*t/d)
        r = [c['lean'], 0., c['rub']*w]; shift[0] = c['rub_shift']*height*w; s = [1., 1-.015*abs(w), 1.]
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
        # One 48-tick contraction: brace, strain at the peak (0.6 s), last push, the birth at
        # 1.8 s with the body back at rest (the server spawns the child at the rest outlet), relief.
        k = c['brace']
        if t < 1.8:
            a = smooth(t/.6) if t < .6 else 1-smooth((t-1.2)/.6) if t > 1.2 else 1.
            s = [1+.5*k*a, 1-k*a, 1+.5*k*a]
            u = (t-1.2)/.6  # last push: ease off the brace, lean in, back to rest by the birth
            r[0] = c['lean_back']*a if t < 1.2 else c['lean_back']*(1-smooth(u/.5))+c['push_lean']*bump(u)
            shiver = bump((t-.35)/1.05)*math.sin(2*math.pi*12*t)
            shift[0] = c['shiver_ratio']*height*shiver; r[2] = 1.2*shiver
        else:
            e = bump((t-1.8)/.6); s = [1-.01*e, 1+.02*e, 1-.01*e]
    elif action == 'midwife':
        k = c['crouch']
        s = [1+.5*k, 1-k, 1+.5*k]; r[0] = c['lean']
        if .3 <= t < 1.4: r[0] += c['nod']*bump(((t-.3)/.55) % 1)
        if t >= 1.8:  # the birth: lean in to meet the newborn, then nuzzle it
            e = bump((t-1.8)/.6); lift = .03*height*e; r[0] -= 4*e
        if t >= 2.0:
            w = math.sin(2*math.pi*(t-2.0)/.4); r[2] = c['rub']*w; shift[0] = .015*height*w
    elif action == 'postpartum':
        if t < .8:
            a = smooth(t/.8)
        elif t < 1.6:
            a = 1.
        else:
            a = 1-smooth((t-1.6)/1.0)
        h = lerp(1, c['slump'], a); s = [lerp(1, 1+(1-c['slump'])*.5, a), h, lerp(1, 1+(1-c['slump'])*.5, a)]
        r[0] = c['head_down']*a
        if t >= 1.6: r[0] += c['look_up']*bump((t-1.6)/1.4)
        if .8 <= t < 1.6: s[1] -= .01*bump((t-.8)/.8)
    elif action == 'first_wake':
        cu, st = c['curl'], c['stretch']
        if t < .5:
            s = [1+(1-cu)*.5, cu, 1+(1-cu)*.5]; r[2] = 6
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
        base = curled(c); k = c['brace']
        if t < 1.8:
            a = smooth(t/.6) if t < .6 else 1-smooth((t-1.2)/.6) if t > 1.2 else 1.
            s = [base[0]+.5*k*a, base[1]-k*a, base[2]+.5*k*a]
            shiver = bump((t-.35)/1.05)*math.sin(2*math.pi*12*t); shift[0] = c['shiver_ratio']*height*shiver
        else:
            # Gather the newborn into the hat: turn toward it, lean in, come back.
            e = bump((t-1.8)/.6); s = base; r = [c['tuck_lean']*e, c['tuck_turn']*e, 0.]
    elif action == 'doff_hat':
        # A deep bow: the hat comes off (or goes back on) at the bottom, then she straightens.
        a = smooth(t/.5) if t < .5 else 1. if t < .7 else 1-smooth((t-.7)/.5)
        r[0] = c['bow']*a; s = [1+.02*a, 1-.04*a, 1+.02*a]
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
    else:
        raise KeyError(action)
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
        def shake(t): return tremble['degrees']*bump((t-.35)/1.05)*math.sin(2*math.pi*tremble['hz']*t) if t < 1.8 else 0.
        for bone, side in (('hand_right', 1), ('hand_left', -1)):
            hand, parts = carried(model, bone)
            if parts:
                tracks.append({'bone': hand, 'channel': 'rotation', 'keys': [[round(t, 8), 0, 0, round(side*shake(t), 5)] for t in samples]})
    return tracks


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
    for name, base in cfg['clips'].items():
        if not applies(cfg, base['applies'], ident): continue
        c = variant(base, ident)
        d = c['duration']; n = math.ceil(d/cfg['sample_seconds']); samples = [d*i/n for i in range(n+1)]
        keys = {'position': [], 'rotation': [], 'scale': []}
        for t in samples:
            s, r, lift, shift = state(name, t, c, height)
            for channel, value in (('position', [shift[0], ground(s, r)+lift, shift[1]]), ('rotation', r), ('scale', s)):
                keys[channel].append([round(t, 8), *[round(x, 7) for x in value]])
        tracks = [{'bone': root, 'channel': ch, 'keys': k} for ch, k in keys.items()]
        out[name] = {'duration': d, 'loop': c['loop'], 'tracks': tracks+flourish_tracks(model, name, samples, cfg, c),
                     'expression_spans': faces(cfg, c, ident), 'expression_yield': cfg['expression_yield']}
    return out
