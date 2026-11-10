"""Three motions for the manga Marisa (run inside Blender after build.py).

  exec(open(r'<this file>').read(), {'__file__': r'<this file>'}); animate_all()
Clips sit one after another on the scene timeline (24 fps); their frame ranges and face
spans are stored on the scene as the 'marisa_clips' JSON property; the face expression is keyed
on each face object's 'expr' property, which its material reads.

  hop    "ゆっ♪ ぽいんぽいん": two bouncy hops forward
  crawl  "ずりずり": belly-crawl forward with a side-to-side wiggle
  eat    "むーしゃむーしゃ … しあわせー": chewing, then a happy upward stretch

Body limits from the library rules: pitch + roll stay within ~12 deg; squash only on
physical contact (take-off, landing, pushing along the ground), emotion is carried by the
face and the upward stretch.
"""
import bpy, json, math, os
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
VARIANTS = {k: v for k, v in json.load(open(os.path.join(HERE, 'variants.json'), encoding='utf-8')).items() if not k.startswith('_')}
FPS = 24
GAP = 12  # frames of rest between clips on the timeline


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def keys(points, t):
    """Piecewise smoothstep through (time, value-tuple) points."""
    if t <= points[0][0]:
        return points[0][1]
    for (t0, a), (t1, b) in zip(points, points[1:]):
        if t <= t1:
            u = smooth((t - t0) / (t1 - t0))
            return tuple(x + (y - x) * u for x, y in zip(a, b))
    return points[-1][1]


def vol(sz):
    """Volume-keeping side scale for a vertical scale."""
    return 1 / math.sqrt(sz)


# ------------------------------------------------------------------ clips
# each returns dict(loc=(x,y,z) in H units, rot=(pitch,roll,yaw) deg, sz=vertical scale, sy=extra depth scale)
def hop(t):
    P, N = 0.62, 2
    k = min(int(t / P), N - 1) if t < N * P else N
    u = t - k * P if k < N else 0.0
    sz, z, pitch = 1.0, 0.0, 0.0
    if k < N:
        sz = keys([(0, (1,)), (0.12, (0.9,)), (0.19, (1.1,)), (0.3, (1.0,)), (0.45, (1.0,)), (0.5, (0.87,)), (0.56, (1.03,)), (0.62, (1.0,))], u)[0]
        if 0.16 <= u <= 0.47:
            s = (u - 0.16) / 0.31
            z = 0.34 * 4 * s * (1 - s)
        pitch = keys([(0, (0,)), (0.12, (-2,)), (0.22, (5,)), (0.36, (0,)), (0.46, (-3,)), (0.53, (2,)), (0.62, (0,))], u)[0]
    step = 0.32
    y = -step * (min(k, N) + (smooth((u - 0.16) / 0.31) if k < N else 0))
    return dict(loc=(0, y, z), rot=(pitch, 0, 0), sz=sz, sy=1.0)


def crawl(t):
    T, pulse = 2.4, 0.4
    u = (t % pulse) / pulse if t < T else 0.0
    push = math.sin(math.pi * u) ** 2 if t < T else 0.0        # 0 -> 1 -> 0 each pulse
    sy = 1 + 0.07 * push
    sz = 1 - 0.035 * push
    fade = smooth(t / 0.3) * (1 - smooth((t - T + 0.3) / 0.3))
    y = -0.75 * (t / T if t < T else 1.0) - 0.04 * push
    yaw = 7 * math.sin(2 * math.pi * t / (2 * pulse)) * fade
    roll = 2.5 * math.sin(2 * math.pi * t / (2 * pulse)) * fade
    return dict(loc=(0, y, 0), rot=(2 * fade, roll, yaw), sz=sz, sy=sy)


def eat(t):
    chew = 0.4
    if t < 1.6:
        u = (t % chew) / chew
        sz = 1 + 0.03 * math.sin(math.pi * u) ** 2
        roll = 1.5 * math.sin(2 * math.pi * t / (2 * chew))
        return dict(loc=(0, 0, 0), rot=(1, roll, 0), sz=sz, sy=1.0)
    sz, pitch = keys([(1.6, (1.0, 1)), (1.75, (1.0, 0)), (2.05, (1.18, -5)), (2.75, (1.18, -5)), (3.1, (1.0, 0)), (3.3, (1.0, 0))], t)
    if 2.05 < t < 2.75:
        sz += 0.008 * math.sin(2 * math.pi * 9 * t)
    return dict(loc=(0, 0, 0), rot=(pitch, 0, 0), sz=sz, sy=1.0)


CLIPS = [
    ('hop', 1.5, hop, [(0, 'neutral'), (0.1, 'yu'), (1.3, 'blink'), (1.38, 'neutral')]),
    ('crawl', 2.6, crawl, [(0, 'neutral')] + [(0.15 + 0.4 * i + d, e) for i in range(6) for d, e in ((0, 'push'), (0.25, 'content'))] + [(2.45, 'neutral')]),
    ('eat', 3.3, eat, [(0, 'chew_open')] + [(0.4 * i + d, e) for i in range(4) for d, e in ((0, 'chew_open'), (0.2, 'chew_closed'))]
     + [(1.6, 'content'), (1.8, 'happy'), (2.85, 'content'), (3.15, 'neutral')]),
]


def spring(drive, dt, omega, zeta):
    x = v = 0.0
    out = []
    for a in drive:
        acc = -omega * omega * x - 2 * zeta * omega * v + a
        v += acc * dt
        x += v * dt
        out.append(x)
    return out


def clip_frames():
    out, f = [], 1
    for name, dur, fn, faces in CLIPS:
        n = int(round(dur * FPS))
        out.append((name, f, f + n, fn, faces))
        f += n + 1 + GAP
    return out


def animate_look(name, v):
    H = v['H']
    body = bpy.data.objects['EMPTY_%s_body' % name]
    stretch = bpy.data.objects['EMPTY_%s_stretch' % name]
    hat = bpy.data.objects['EMPTY_%s_hat' % name]
    braid = bpy.data.objects['EMPTY_%s_braid' % name]
    hat_rest, braid_rest = Vector(hat.location), Vector(braid.location)
    for ob in (body, stretch, hat, braid):
        ob.animation_data_clear()
        ob.rotation_mode = 'XYZ'
    dt = 1 / FPS
    for clip, f0, f1, fn, _ in clip_frames():
        n = f1 - f0 + 1
        S = [fn(i * dt) for i in range(n)]
        z = [s['loc'][2] * H for s in S]
        y = [s['loc'][1] * H for s in S]
        sz = [s['sz'] for s in S]
        yaw = [s['rot'][2] for s in S]
        # vertical acceleration of the head top drives the hat bob and braid swing
        top = [z[i] + hat_rest.z * sz[i] for i in range(n)]
        acc = [0.0] + [(top[i + 1] - 2 * top[i] + top[i - 1]) / dt / dt for i in range(1, n - 1)] + [0.0]
        accy = [0.0] + [(y[i + 1] - 2 * y[i] + y[i - 1]) / dt / dt for i in range(1, n - 1)] + [0.0]
        accyaw = [0.0] + [(yaw[i + 1] - 2 * yaw[i] + yaw[i - 1]) / dt / dt for i in range(1, n - 1)] + [0.0]
        hat_z = spring([-a for a in acc], dt, 22, 0.35)
        hat_p = spring([a for a in accy], dt, 18, 0.4)
        br_sw = spring([a for a in acc], dt, 13, 0.25)
        br_yaw = spring([-a for a in accyaw], dt, 11, 0.3)
        for i, s in enumerate(S):
            f = f0 + i
            sx = vol(s['sz'] * s['sy']) if s['sy'] != 1.0 else vol(s['sz'])
            body.location = Vector(s['loc']) * H
            body.rotation_euler = [math.radians(a) for a in (s['rot'][0], s['rot'][1], s['rot'][2])]
            stretch.scale = (sx, sx * s['sy'], s['sz'])
            # spring outputs are displacements (accel / omega^2); gains map them to a few units / degrees
            hz = max(-0.025 * H, min(0.07 * H, hat_z[i] * 0.4))
            hat.location = Vector((hat_rest.x * sx, hat_rest.y * sx * s['sy'], hat_rest.z * s['sz'] + hz))
            hat.rotation_euler = (math.radians(max(-7, min(7, hat_p[i] * 6 - hz / H * 30))), 0, 0)
            braid.location = Vector((braid_rest.x * sx, braid_rest.y * sx * s['sy'], braid_rest.z * s['sz']))
            sw = max(-28, min(28, br_sw[i] * 5))
            braid.rotation_euler = (math.radians(max(-15, min(15, hat_p[i] * 8))), math.radians(-sw), math.radians(max(-12, min(12, br_yaw[i] * 1.5))))
            for ob, paths in ((body, ('location', 'rotation_euler')), (stretch, ('scale',)), (hat, ('location', 'rotation_euler')), (braid, ('location', 'rotation_euler'))):
                for p in paths:
                    ob.keyframe_insert(p, frame=f)
        # rest pose between clips
        for ob, loc in ((body, Vector()), (hat, hat_rest), (braid, braid_rest)):
            ob.location = loc
            ob.rotation_euler = (0, 0, 0)
            ob.keyframe_insert('location', frame=f1 + 1)
            ob.keyframe_insert('rotation_euler', frame=f1 + 1)
        stretch.scale = (1, 1, 1)
        stretch.keyframe_insert('scale', frame=f1 + 1)


def face_spans():
    return {name: {'start': f0, 'end': f1, 'faces': [(f0 + int(round(t * FPS)), e) for t, e in faces]}
            for name, f0, f1, _, faces in clip_frames()}


def key_faces(name):
    """Key the face object's 'expr' index (see MAT_<v>_face['expr_order']) on every clip frame."""
    face = bpy.data.objects['SM_%s_face' % name]
    order = list(bpy.data.materials['MAT_%s_face' % name]['expr_order'])
    for clip, f0, f1, _, faces in clip_frames():
        for f in range(f0, f1 + 2):
            cur = 'neutral'
            for t, e in faces:
                if f0 + int(round(t * FPS)) <= f <= f1:
                    cur = e
            face['expr'] = order.index(cur)
            face.keyframe_insert('["expr"]', frame=f)


def animate_all():
    sc = bpy.context.scene
    sc.render.fps = FPS
    for name, v in VARIANTS.items():
        animate_look(name, v)
        key_faces(name)
    spans = face_spans()
    sc['marisa_clips'] = json.dumps(spans)
    sc.frame_start, sc.frame_end = 1, max(c['end'] for c in spans.values())
    sc.frame_set(1)
    return {k: (c['start'], c['end']) for k, c in spans.items()}
