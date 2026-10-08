"""Author selected whole-head motions and a compact, local cheek morph.

The source rigs remain frozen inputs. Puffing keeps the root fixed and uses a
closed tessellation of the original skin; other corrections share a root pose.
No clip reveals obsolete cheek meshes or accumulates slice rotations.
"""
import math
from functools import lru_cache


def smooth(x):
    x = max(0, min(1, x))
    return x*x*(3-2*x)


def swell(t, duration):
    return smooth(t/(duration*.22))*smooth((duration-t)/(duration*.22))


def cheek_delta(c, x, y, z):
    """Shared monotone field for skin and its attached hair; C1 at both rims."""
    yy = (y-c[1])/c[3]
    if abs(yy) >= 1 or z >= c[5]:
        return [0., 0.]
    yz = (1-yy*yy)**2*smooth((c[5]-z)/(c[5]-c[4]))
    dx = math.copysign(c[6], x)*smooth((abs(x)-c[0])/c[2])*yz
    q = smooth((abs(x+dx)-c[0])/c[2])*yz*smooth((c[9]-y)/(c[9]-c[8]))
    return [dx, -c[7]*q]


def skin_envelope(skin, cheeks, height):
    """Interpolate the actual tessellated boundary, including its transition rim.

    An analytic field alone misses the small chords between skin vertices and
    can let a tightly fitted hair panel enter the rendered skin.
    """
    step = height*.08
    cells, forward_edges = {}, {}
    front = min(skin[2::8])+height*.00001
    for k in range(0, len(skin), 24):
        tri = [skin[k+j*8:k+j*8+8] for j in range(3)]
        a, b, c = tri
        deltas = [cheek_delta(cheeks, *p[:3]) for p in tri]
        for j in range(3):
            n=(j+1)%3
            edge=tuple(sorted([(tri[j][1],deltas[j][1]),(tri[n][1],deltas[n][1])]))
            if edge[0][0]!=edge[1][0] and min(edge[0][1],edge[1][1])<0:
                forward_edges[edge]=edge
        den = (b[2]-c[2])*(a[1]-c[1])+(c[1]-b[1])*(a[2]-c[2])
        if abs(den)<1e-10:
            continue
        item = (tri, deltas, den)
        for y in range(math.floor(min(p[1] for p in tri)/step), math.floor(max(p[1] for p in tri)/step)+1):
            for z in range(math.floor(min(p[2] for p in tri)/step), math.floor(max(p[2] for p in tri)/step)+1):
                cells.setdefault((y, z), []).append(item)
    @lru_cache(maxsize=200000)
    def query(y, z):
        z = max(z, front)
        dx, dz = 0., 0.
        for tri, deltas, den in cells.get((math.floor(y/step), math.floor(z/step)), []):
            a, b, c = tri
            u = ((b[2]-c[2])*(y-c[1])+(c[1]-b[1])*(z-c[2]))/den
            v = ((c[2]-a[2])*(y-c[1])+(a[1]-c[1])*(z-c[2]))/den
            weights = [u, v, 1-u-v]
            if min(weights)<-1e-6:
                continue
            dx = max(dx, abs(sum(w*d[0] for w, d in zip(weights, deltas))))
            dz = min(dz, sum(w*d[1] for w, d in zip(weights, deltas)))
        return dx, dz
    @lru_cache(maxsize=200000)
    def query_front(y):
        dz=0.
        for (a,da),(b,db) in forward_edges:
            if a<=y<=b:
                dz=min(dz,da+(db-da)*(y-a)/(b-a))
        return dz
    query.front=query_front
    return query


def attachment_delta(c, p, clearance, contact):
    # Locks hang below the cheek instead of curling back under it. All pieces
    # of the lock, including its ribbon, share this root-anchored field.
    dx, dz = cheek_delta(c, p[0], max(p[1], c[1]), p[2])
    skin_dx, skin_dz = contact(max(p[1], c[1]), p[2])
    lateral = smooth((abs(p[0])-c[0])/c[2])
    # Only lateral motion is held constant below the lobe. The real lower
    # cheek can still advance beneath the eye guard, especially on house rigs.
    dz=min(dz,cheek_delta(c,*p[:3])[1])
    # Extend the front clearance across the whole side lock. A point-in-face
    # lookup would jump to zero just outside the resting silhouette, precisely
    # where an inflating cheek can newly reach the hair.
    front_dz=contact.front(p[1])*lateral*smooth((c[5]-p[2])/(c[5]-c[4]))
    return [math.copysign(max(abs(dx),skin_dx*lateral)*(1+clearance), p[0]), min(dz,skin_dz*lateral,front_dz)*(1+clearance)]


def subdivide_attachment(tri, cheeks, tolerance, clearance, contact, depth=0):
    """Bound curvature error, retaining unchanged planes and authored UV seams."""
    deltas = [attachment_delta(cheeks, p, clearance, contact) for p in tri]
    errors = []
    for i in range(3):
        j = (i+1)%3
        error = 0.
        for q in [.25, .5, .75]:
            p = [a+(b-a)*q for a, b in zip(tri[i], tri[j])]
            actual = attachment_delta(cheeks, p, clearance, contact)
            error = max(error, *(abs(actual[k]-(deltas[i][k]+(deltas[j][k]-deltas[i][k])*q)) for k in range(2)))
        errors.append(error)
    centre = [sum(p[k] for p in tri)/3 for k in range(3)]
    actual = attachment_delta(cheeks, centre, clearance, contact)
    centre_error = max(abs(actual[k]-sum(d[k] for d in deltas)/3) for k in range(2))
    i = max(range(3), key=errors.__getitem__)
    if max(errors[i], centre_error) <= tolerance or depth >= 24:
        yield tri
        return
    # Always bisect the longest edge once refinement is needed. Splitting the
    # largest-error edge repeatedly creates needle triangles that reach the
    # recursion cap while still crossing the skin's curvature.
    i = max(range(3), key=lambda j:sum((tri[j][k]-tri[(j+1)%3][k])**2 for k in range(3)))
    a, b, c = tri[i], tri[(i+1)%3], tri[(i+2)%3]
    mid = [(x+y)/2 for x, y in zip(a, b)]
    yield from subdivide_attachment([a, mid, c], cheeks, tolerance, clearance, contact, depth+1)
    yield from subdivide_attachment([mid, b, c], cheeks, tolerance, clearance, contact, depth+1)


def surface_blush(model, part, skin, cheeks, gap):
    """Clip pigment to the skin's triangles so every phase has the same surface.

    Stored offsets are interpolated from skin corners, rather than recomputed
    at clipped points. This keeps the pigment coplanar with the actual mesh.
    """
    vertices, offsets = [], []
    for k in range(part['start']*8, (part['start']+part['count'])*8, 24):
        ink = [model['vertices'][k+j*8:k+j*8+8] for j in range(3)]
        if ink[0][7] >= -.2:
            continue
        a, b, c = ink
        den = (b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
        if abs(den) < 1e-10:
            continue
        def bary(p):
            u = ((b[1]-c[1])*(p[0]-c[0])+(c[0]-b[0])*(p[1]-c[1]))/den
            v = ((c[1]-a[1])*(p[0]-c[0])+(a[0]-c[0])*(p[1]-c[1]))/den
            return [u, v, 1-u-v]
        for s in range(0, len(skin), 24):
            tri = [skin[s+j*8:s+j*8+8] for j in range(3)]
            if tri[0][7] >= -.2:
                continue
            if any(max(p[axis] for p in tri)<min(p[axis] for p in ink) or
                   min(p[axis] for p in tri)>max(p[axis] for p in ink) for axis in [0, 1]):
                continue
            polygon = [p+cheek_delta(cheeks, *p[:3]) for p in tri]
            for axis in range(3):
                clipped = []
                for j, p in enumerate(polygon):
                    q = polygon[(j+1)%len(polygon)]
                    bp, bq = bary(p)[axis], bary(q)[axis]
                    if bp >= 0:
                        clipped.append(p)
                    if (bp >= 0) != (bq >= 0):
                        t = bp/(bp-bq)
                        clipped.append([x+(y-x)*t for x, y in zip(p, q)])
                polygon = clipped
            for j in range(1, len(polygon)-1):
                for p in [polygon[0], polygon[j], polygon[j+1]]:
                    weights = bary(p)
                    uv = [sum(weights[n]*ink[n][axis] for n in range(3)) for axis in [3, 4]]
                    vertices.extend([p[0], p[1], p[2]-gap, *uv, *p[5:8]])
                    offsets.extend(p[8:10])
    return vertices, offsets


def indexed_part(part):
    """Store shared corners once; readers expand only the selected model."""
    source = part['vertices']; offsets = part.get('offsets')
    unique, vertices, indices, packed_offsets = {}, [], [], []
    for k in range(0, len(source), 8):
        key = tuple(source[k:k+8]) + (tuple(offsets[k//4:k//4+2]) if offsets is not None else ())
        if key not in unique:
            unique[key] = len(vertices)//8
            vertices.extend(source[k:k+8])
            if offsets is not None:
                packed_offsets.extend(offsets[k//4:k//4+2])
        indices.append(unique[key])
    result = dict(part, vertices=vertices, indices=indices)
    if offsets is not None:
        result['offsets'] = packed_offsets
    return result


def puff_attachments(model, skin, cheeks, root, height, cfg):
    output = []
    contact = skin_envelope(skin, cheeks, height)
    for index, part in enumerate(model['parts']):
        name = part['name']
        if part.get('anatomy') or name.startswith('puff_') or 'body_surface' in name:
            continue
        if name.startswith('face_blush_'):
            vertices, offsets = surface_blush(model, part, skin, cheeks, height*cfg['blush_surface_gap_height_ratio'])
        elif name.startswith('face_'):
            continue
        else:
            source = model['vertices'][part['start']*8:(part['start']+part['count'])*8]
            # Include triangles spanning the field even when all corners are outside.
            if min(source[1::8])>=cheeks[1]+cheeks[3] or min(source[2::8])>=cheeks[5]:
                continue
            vertices, offsets = [], []
            for k in range(0, len(source), 24):
                tri = [source[k+j*8:k+j*8+8] for j in range(3)]
                for refined in subdivide_attachment(tri, cheeks, height*cfg['attachment_surface_error_height_ratio'], cfg['attachment_clearance_ratio'], contact):
                    for p in refined:
                        vertices.extend(p)
                        offsets.extend(attachment_delta(cheeks, p, cfg['attachment_clearance_ratio'], contact))
            if not any(abs(x)>1e-8 for x in offsets):
                continue
        bindings = model['weights'][part['start']:part['start']+part['count']]
        rigid = {a for a, b, q in bindings if a==b}
        bone = next(iter(rigid)) if len(rigid)==1 and all(a==b for a,b,q in bindings) and not name.startswith('face_') else root
        output.append(indexed_part({'part_index':index, 'bone':bone,
                       'vertices':[round(x,7) for x in vertices], 'offsets':[round(x,7) for x in offsets]}))
    return output


def state(action, t, duration, cfg, height, angle):
    scale, rotation, lift = [1., 1., 1.], [0., 0., 0.], 0.
    if action == 'puff':
        pass  # Only the local cheek morph changes; the body root stays at rest.
    elif action in ('side_bend', 'exercise_sides'):
        # Ease through the centre without elongating the top of the head.
        rotation[2] = angle*math.sin(2*math.pi*t/duration)**3
    elif action in ('stretch', 'stretch_shape', 'exercise_vertical'):
        q = math.sin(math.pi*t/duration)**2
        scale = [1-.045*q, 1+cfg['stretch_height']*q, 1-.025*q]
    elif action == 'hop':
        q = t/duration
        if q < .2:
            s = math.sin(math.pi*q/.2)**2
            scale = [1+.075*s, 1-.12*s, 1+.045*s]
        elif q < .8:
            air = (q-.2)/.6
            lift = height*cfg['hop_height_body_ratio']*4*air*(1-air)
            s = math.sin(math.pi*air)
            scale = [1-.025*s, 1+.055*s, 1-.02*s]
        else:
            s = math.sin(math.pi*(q-.8)/.2)**2
            scale = [1+.06*s, 1-.09*s, 1+.035*s]
    elif action == 'taiso':
        if t < 2.4:
            return state('exercise_vertical', t, 2.4, cfg, height, angle)
        if t < 4.8:
            return state('side_bend', t-2.4, 2.4, cfg, height, angle)
        if t < 7.2:
            rotation[1] = 18*math.sin(2*math.pi*(t-4.8)/2.4)**3
        elif t < 10.4:
            return state('hop', (t-7.2) % cfg['hop_seconds'], cfg['hop_seconds'], cfg, height, angle)
        elif t < 13.6:
            return state('puff', t-10.4, 3.2, cfg, height, angle)
    return scale, rotation, lift


def compile_revision(model, cfg, puff_expression, eye_bounds):
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    root = names['root']; pivot = model['bones'][root]['pivot']
    assert model['bones'][root]['parent'] == names['world_motion']
    points, body = [], []
    for part in model['parts']:
        if part.get('anatomy') or part['name'].startswith('puff_'):
            continue
        vertices = model['vertices'][part['start']*8:(part['start']+part['count'])*8]
        vv = [vertices[i:i+3] for i in range(0, len(vertices), 8)]
        points.extend(vv)
        if 'body_surface' in part['name']:
            body.extend(vv)
    assert body, model['id']
    height = max(v[1] for v in body)-min(v[1] for v in body)
    blush = next(p for p in model['parts'] if p['name']=='face_blush_left')
    vv = model['vertices'][blush['start']*8:(blush['start']+blush['count'])*8]
    cx = abs((min(vv[0::8])+max(vv[0::8]))/2)*cfg['cheek_center_from_blush']
    cy = (min(vv[1::8])+max(vv[1::8]))/2+cx*cfg['cheek_raise']
    half_width = max(abs(v[0]) for v in body)
    front = min(v[2] for v in body); depth = max(v[2] for v in body)-front
    # Compact, symmetric cheek lobes. Their support does not reach the base or crown.
    # Threat display: two large side lobes. A monotone lateral ramp permits
    # comic-sized cheeks without the inward fold of a large Gaussian bulge.
    cheeks = [half_width*cfg['cheek_inner_width'], cy, half_width*cfg['cheek_ramp_width'], min(cx*cfg['cheek_radius_y'],cy*.86),
              front+depth*.28, front+depth*.66, half_width*cfg['cheek_outward'], half_width*cfg['cheek_forward']]
    # Subdivide the existing closed skin only for puffing: new rest points stay
    # on the original triangles, but local cheek lobes can be round instead of
    # stretching one large face across the entire mouth. Original indices remain.
    body_index=next(i for i,p in enumerate(model['parts']) if 'body_surface' in p['name'])
    part=model['parts'][body_index];refined=[];steps=cfg['cheek_subdivisions']
    for start in range(part['start']*8,(part['start']+part['count'])*8,24):
        tri=[model['vertices'][start+j*8:start+j*8+8] for j in range(3)]
        def point(i,j):
            a=i/steps;b=j/steps
            return [round(tri[0][k]*(1-a-b)+tri[1][k]*a+tri[2][k]*b,7) for k in range(8)]
        for i in range(steps):
            for j in range(steps-i):
                for v in [point(i,j),point(i+1,j),point(i,j+1)]:refined.extend(v)
                if i+j<steps-1:
                    for v in [point(i+1,j),point(i+1,j+1),point(i,j+1)]:refined.extend(v)
    # Keep a whole triangle-height guard below the eyes, not merely the eye
    # decal vertices. Thus interpolated skin beneath the fixed eyes cannot swell
    # through them even on the coarsest selected body. The fade is C1 continuous.
    eye_x,eye_bottom=eye_bounds
    triangle_height=max(max(refined[k+1],refined[k+9],refined[k+17])-
                        min(refined[k+1],refined[k+9],refined[k+17]) for k in range(0,len(refined),24))
    eye_stop=eye_bottom-triangle_height-height*.002
    # Only the depth pass is masked below the eyes. The lateral pass remains
    # smooth across the cheek, avoiding the old double-lobe / wing silhouette.
    cheeks.extend([eye_stop-cheeks[3]*.5,eye_stop,2])
    attachments = puff_attachments(model, refined, cheeks, root, height, cfg)
    floor = min(v[1] for v in points)
    # Only the convex hull can support the floor. Deduplicate source triangles.
    points = list(set(tuple(v) for v in points))
    angle = cfg['shell_side_degrees'] if model['id']=='marisa_tsumuri' else cfg['side_degrees']
    result = {}
    for action in cfg['actions']:
        duration = cfg['hop_seconds'] if action=='hop' else model['clips'][action]['duration']
        count = math.ceil(duration/cfg['sample_seconds'])
        keys = {c: [] for c in ['position','rotation','scale']}
        for i in range(count+1):
            t = duration*i/count
            scale, rot, lift = state(action, t, duration, cfg, height, angle)
            # Rx is zero. With Rz*Ry*S the resulting y row is enough for contact.
            ry, rz = math.radians(rot[1]), math.radians(rot[2])
            row = [math.sin(rz)*math.cos(ry)*scale[0], math.cos(rz)*scale[1], math.sin(rz)*math.sin(ry)*scale[2]]
            lowest = min(sum(row[k]*(v[k]-pivot[k]) for k in range(3))+pivot[1] for v in points)
            pos = [0, floor-lowest+lift, 0]
            for c, value in [('position',pos),('rotation',rot),('scale',scale)]:
                keys[c].append([round(t,8), *[round(x,7) for x in value]])
        result[action] = {'duration':duration,'loop':model['clips'][action]['loop'],
            'tracks':[{'bone':root,'channel':c,'keys':k} for c,k in keys.items()]}
        if action=='puff':
            result[action]['expression_spans']=[[0,duration,puff_expression]]
            result[action]['puff_envelope']=[0,duration,.22]
        if action=='taiso':
            result[action]['expression_spans']=[[10.4,13.6,puff_expression]]
            result[action]['puff_envelope']=[10.4,13.6,.22]
    return {'bone_names':[b['name'] for b in model['bones']], 'clips':result,
            'cheek_puff':cheeks, 'puff_surface':indexed_part({'part_index':body_index,'bone':root,'vertices':refined}),
            'puff_attachments':attachments,
            'body_height':height, 'eye_protection_y':eye_bottom, 'eye_protection_x':eye_x,
            'hop_height':height*cfg['hop_height_body_ratio']}
