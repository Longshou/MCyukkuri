"""Numeric rig sampling and long-hair contact fitting for the runtime exporter.

Uses the client's T(p + position) Rz Ry Rx S T(-p) convention. The selected
editable model stays untouched; extra bones only compress the lower hair when
the shared body motion would put it below the ground plane.
"""
import bisect
import math


def compile_tracks(clip):
    return [(bone, channel, sorted((float(t), value) for t, value in keys.items()))
            for bone, channels in clip['tracks'].items() for channel, keys in channels.items()]


def matrices(bones, defaults, tracks, time):
    poses = {b['name']: [0, 0, 0, 0, 0, 0, 1, 1, 1] for b in bones}
    for layer, at in ((defaults, 0), (tracks, time)):
        for name, channel, keys in layer:
            i = max(0, min(len(keys)-2, bisect.bisect_right(keys, (at, [math.inf]*3))-1))
            ta, a = keys[i]; tb, b = keys[min(i+1, len(keys)-1)]
            q = max(0, min(1, (at-ta)/(tb-ta))) if tb != ta else 0
            offset = {'position': 0, 'rotation': 3, 'scale': 6}[channel]
            poses[name][offset:offset+3] = [x+(y-x)*q for x, y in zip(a, b)]
    world = {}
    for bone in bones:
        v = poses[bone['name']]; p = bone['pivot']
        x, y, z = [math.radians(a) for a in v[3:6]]
        cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
        a, b, d = v[6:9]
        m = [cz*cy*a, (cz*sy*sx-sz*cx)*b, (cz*sy*cx+sz*sx)*d, 0,
             sz*cy*a, (sz*sy*sx+cz*cx)*b, (sz*sy*cx-cz*sx)*d, 0,
             -sy*a, cy*sx*b, cy*cx*d, 0]
        for r in range(3):
            m[r*4+3] = p[r]+v[r]-sum(m[r*4+k]*p[k] for k in range(3))
        if bone['parent'] is not None:
            w = world[bone['parent']]
            m = [sum(w[r*4+j]*m[j*4+k] for j in range(3)) + (w[r*4+3] if k == 3 else 0)
                 for r in range(3) for k in range(4)]
        world[bone['name']] = m
    return world


def local_vertical(m, rise):
    # Solve the parent's linear transform for a world-space vertical offset.
    a,b,c,_,d,e,f,_,g,h,i,_ = m
    det = a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g)
    if abs(det) < 1e-10:
        raise ValueError('Singular long-hair parent transform')
    return [(c*h-b*i)*rise/det, (a*i-c*g)*rise/det, (b*g-a*h)*rise/det]


def fit_grounded_hair(rig, vertices, weights):
    original_bones = list(rig['bones'])
    names = {b['name']: i for i,b in enumerate(original_bones)}
    candidates = {}
    for parent in ('hair', 'hand_left', 'hand_right'):
        name = 'runtime_contact_' + parent
        target = len(rig['bones'])
        rig['bones'].append({'name': name, 'parent': parent, 'pivot': [0, 0, 0]})
        points = set()
        for vertex, weight in enumerate(weights):
            if weight[0] != names[parent]:
                continue
            point = tuple(vertices[vertex*8:vertex*8+3])
            # The upper attachment remains exact; lower points share a continuous
            # lift, including the ties and tips, so no rigid strand floats away.
            q = round(max(0, min(1, 1-point[1]/8.48)), 7)
            if q > 0:
                weights[vertex] = [names[parent], target, q]
                points.add((*point, q))
        candidates[parent] = (name, points)
    defaults = compile_tracks(rig['clips']['idle'])
    affected = {}
    for action, clip in rig['clips'].items():
        compiled = compile_tracks(clip)
        times = {0., clip['duration']}
        times.update(round(i*.02, 8) for i in range(math.ceil(clip['duration']/.02)) if i*.02 < clip['duration'])
        times.update(t for _,_,keys in compiled for t,_ in keys)
        keys = {name: {} for name,_ in candidates.values()}; maximum = 0
        for time in sorted(times):
            world = matrices(original_bones, defaults, compiled, time)
            for parent, (name, points) in candidates.items():
                m = world[parent]
                # A small clearance covers interpolation between baked samples.
                rise = max((max(0, -(m[4]*x+m[5]*y+m[6]*z+m[7]))/q for x,y,z,q in points), default=0)
                if rise > 1e-5:
                    rise += .015
                else:
                    rise = 0
                maximum = max(maximum, rise)
                keys[name][str(round(time, 8))] = [round(v, 7) for v in local_vertical(m, rise)]
        if maximum > 0:
            affected[action] = round(maximum, 6)
            for name, values in keys.items():
                clip['tracks'][name] = {'position': values}
    return {'method': 'weighted_lower_hair_ground_contact', 'sample_seconds': .02,
            'fixed_attachment_height': 8.48, 'max_contact_lift_by_clip': affected}
