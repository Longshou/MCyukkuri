"""Fit house walking facial anchors to their actual surface height.

Pure data adaptation: keep rest geometry, pivots, face controls and interaction
clips. Only the waddle tracks of the ten facial skin anchors are rewritten.
"""
import bisect
import math


def sample(keys, time):
    times = [key[0] for key in keys]
    index = max(0, min(len(keys)-2, bisect.bisect_right(times, time)-1))
    a, b = keys[index], keys[min(index+1, len(keys)-1)]
    q = max(0, min(1, (time-a[0])/(b[0]-a[0]))) if a[0] != b[0] else 0
    return [a[k]*(1-q)+b[k]*q for k in range(1, 4)]


def fit(model, recipe):
    names = {bone['name']: i for i, bone in enumerate(model['bones'])}
    clip = model['clips']['waddle']
    records = []
    for part in model['parts']:
        if not part['name'].startswith('face_'):
            continue
        child = model['weights'][part['start']][0]
        aid = model['bones'][child]['parent']
        anchor = model['bones'][aid]
        assert anchor['name'] == part['name']+'_skin_anchor'
        vertices = model['vertices'][part['start']*8:(part['start']+part['count'])*8]
        height = (min(vertices[1::8])+max(vertices[1::8]))/2
        q = max(0, min(31, height/recipe['body_height']*32-.5))
        low = int(q)
        weight = q-low
        a, b = [names[f'soft_head_slice_{index}_bend'] for index in (low, min(31, low+1))]
        # Waddle uses yaw about a shared front-side axis. Keeping the original
        # pivot is equivalent because only its Y differs, and preserves every
        # other clip's pivot compensation and the existing facial child controls.
        for bone in (model['bones'][a], model['bones'][b]):
            assert anchor['parent'] == bone['parent']
            assert all(abs(anchor['pivot'][k]-bone['pivot'][k]) < 1e-6 for k in (0, 2))
        tracks = {}
        for channel in ('position', 'rotation'):
            keys_a = next(t['keys'] for t in clip['tracks'] if t['bone']==a and t['channel']==channel)
            keys_b = next(t['keys'] for t in clip['tracks'] if t['bone']==b and t['channel']==channel)
            times = sorted({key[0] for key in keys_a+keys_b})
            tracks[channel] = [[time, *[u*(1-weight)+v*weight for u,v in
                zip(sample(keys_a, time), sample(keys_b, time))]] for time in times]
        for key in tracks['position']:
            rotation = sample(tracks['rotation'], key[0])
            assert abs(rotation[0])+abs(rotation[2]) < 1e-6
            angle = math.radians(rotation[1])
            # The face cards span several independently turning skin rows.
            # A small normal offset at sway peaks covers this interpolation
            # difference; it vanishes at rest and at the loop boundary.
            clearance = recipe['peak_clearance']*math.sin(math.tau*key[0]/clip['duration'])**2
            key[1] -= math.sin(angle)*clearance
            key[3] -= math.cos(angle)*clearance
        clip['tracks'] = [track for track in clip['tracks'] if track['bone'] != aid]
        clip['tracks'].extend({'bone':aid, 'channel':channel,
            'keys':[[key[0], *[round(v, 7) for v in key[1:]]] for key in keys]}
            for channel, keys in tracks.items())
        records.append({'part':part['name'], 'anchor':anchor['name'],
                        'surface_height':height, 'skin_rows':[low,min(31,low+1)], 'weight':weight})
    assert len(records) == 10
    return records
