"""Numeric rig sampling and long-hair contact fitting for the runtime exporter.

Uses the client's T(p + position) Rz Ry Rx S T(-p) convention. The selected
editable model stays untouched; extra bones only compress the lower hair when
the shared body motion would put it below the ground plane.
"""
import math
from pose_client import compose


def compile_tracks(clip):
    return [(bone, channel, sorted((float(t), value) for t, value in keys.items()))
            for bone, channels in clip['tracks'].items() for channel, keys in channels.items()]


def matrices(bones, defaults, tracks, time):
    def rows(layer):
        return [dict(bone=name, channel=channel, keys=[[t, *v] for t, v in keys])
                for name, channel, keys in layer]
    world = compose(bones, rows(tracks), rows(defaults), time)
    return {b['name']: [x for row in m for x in row] for b, m in zip(bones, world)}


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
