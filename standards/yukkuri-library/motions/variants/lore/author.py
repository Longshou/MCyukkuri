"""World-setting motions, compiled per runtime model by contact-safe/author.py.

Each clip is a whole-body root transform sampled every `sample_seconds`: scale, rotation
(degrees, composed Rz*Ry*Rx as in the game), lift above the landed pose and an x/z shift,
both in model units. The caller lands every sample on the lowest visible point, so a lean
pivots on the floor instead of sinking into it. Forward is -Z (the face side); a negative
X rotation leans the head forward. Flourishes add rotation tracks to hand bones that carry
hair: Marisa's braid waves, Reimu's sideburns flick. See README.md and recipe.json.
"""
import math


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
    elif action == 'boat_balance':
        s = [c['width'], c['height'], c['width']]
        r = [c['pitch']*math.sin(2*math.pi*t/1.5+1), 0., c['roll']*math.sin(2*math.pi*t/d)]
    else:
        raise KeyError(action)
    return s, r, lift, shift


def carried(model, bone):
    """Vertex indices whose primary bone is `bone` or one of its children, outside the skin."""
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    if bone not in names:
        return set(), []
    hand = names[bone]; group = {hand} | {i for i, b in enumerate(model['bones']) if b['parent'] == hand}
    parts = [p for p in model['parts'] if 'body_surface' not in p['name'] and not p.get('anatomy')
             and any(int(model['weights'][i][0]) in group for i in range(p['start'], p['start']+p['count']))]
    return hand, parts


def flourish_tracks(model, action, samples, cfg):
    tracks = []
    wave = cfg['flourishes']['braid_wave']
    if action in wave['clips']:
        hand, parts = carried(model, 'hand_right')
        if any(p['name'].startswith('braid_lock_') for p in parts):
            R, W = wave['raise'], wave['wave']
            def z(t):
                if t < .2: return 0.
                if t < .55: return R*smooth((t-.2)/.35)
                if t < 1.15: return R+W*math.sin(2*math.pi*(t-.55)/.3)
                return R*(1-smooth((t-1.15)/.25))
            tracks.append({'bone': hand, 'channel': 'rotation', 'keys': [[round(t, 8), 0, 0, round(z(t), 5)] for t in samples]})
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
    return tracks


def compile_clips(model, cfg, root, ground, height):
    """ground(scale, rotation) -> y offset that keeps the lowest visible point on the floor."""
    out = {}
    for name, c in cfg['clips'].items():
        d = c['duration']; n = math.ceil(d/cfg['sample_seconds']); samples = [d*i/n for i in range(n+1)]
        keys = {'position': [], 'rotation': [], 'scale': []}
        for t in samples:
            s, r, lift, shift = state(name, t, c, height)
            for channel, value in (('position', [shift[0], ground(s, r)+lift, shift[1]]), ('rotation', r), ('scale', s)):
                keys[channel].append([round(t, 8), *[round(x, 7) for x in value]])
        tracks = [{'bone': root, 'channel': ch, 'keys': k} for ch, k in keys.items()]
        out[name] = {'duration': d, 'loop': c['loop'], 'tracks': tracks+flourish_tracks(model, name, samples, cfg)}
    return out
