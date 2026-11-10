"""Build the manga-reference Marisa (variants.json looks; currently the M01 child look) in a Blender scene.

Run inside Blender (MCP or Text editor):
  exec(open(r'<this file>').read(), {'__file__': r'<this file>'}); build_all()
Reads variants.json and textures/<variant>_<expression>.png (draw them with faces.py first).
Each look gets its own collection COL_Marisa_<variant>; rebuilding replaces only those collections.
Units: 1 = model pixel. X = Marisa's own left (braid side), face at -Y, Z up.

Rig (object hierarchy, animated by animate.py):
  EMPTY_Marisa_<v>          placement
    EMPTY_<v>_body          ground pivot: travel, hop, lean, yaw
      EMPTY_<v>_stretch     squash / stretch; body, face, hair
      EMPTY_<v>_hat         hat seat (follows the stretched head top): lag and lift; hat, hat bow
      EMPTY_<v>_braid       braid root at the temple (follows the stretch): swing; braid, bow, tuft
"""
import bpy, bmesh, json, math, os, random
from mathutils import Euler, Matrix, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
VARIANTS = {k: v for k, v in json.load(open(os.path.join(HERE, 'variants.json'), encoding='utf-8')).items() if not k.startswith('_')}


def srgb(h):
    c = [int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    return tuple(x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c)


def spow(v, e):
    return math.copysign(abs(v) ** e, v)


def loft(rings, cap_start=True, cap_end=True):
    n = len(rings[0])
    verts = [v for r in rings for v in r]
    faces = [(i * n + j, i * n + (j + 1) % n, (i + 1) * n + (j + 1) % n, (i + 1) * n + j)
             for i in range(len(rings) - 1) for j in range(n)]
    if cap_start:
        verts.append(sum(rings[0], Vector()) / n)
        c = len(verts) - 1
        faces += [(c, (j + 1) % n, j) for j in range(n)]
    if cap_end:
        verts.append(sum(rings[-1], Vector()) / n)
        c = len(verts) - 1
        b = (len(rings) - 1) * n
        faces += [(c, b + j, b + (j + 1) % n) for j in range(n)]
    return verts, faces


def ellipsoid(center, radii, seg=12, rings=8, rot=None, pinch=None):
    R = rot.to_matrix() if rot else Matrix.Identity(3)
    C = Vector(center)
    verts, faces = [], []
    for i in range(1, rings):
        phi = math.pi * i / rings
        for j in range(seg):
            th = 2 * math.pi * j / seg
            q = Vector((math.sin(phi) * math.cos(th) * radii[0], math.sin(phi) * math.sin(th) * radii[1], math.cos(phi) * radii[2]))
            verts.append(R @ (pinch(q) if pinch else q) + C)
    top = len(verts); verts.append(R @ Vector((0, 0, radii[2])) + C)
    bot = len(verts); verts.append(R @ Vector((0, 0, -radii[2])) + C)
    for i in range(rings - 2):
        for j in range(seg):
            faces.append((i * seg + j, i * seg + (j + 1) % seg, (i + 1) * seg + (j + 1) % seg, (i + 1) * seg + j))
    faces += [(top, (j + 1) % seg, j) for j in range(seg)]
    b = (rings - 2) * seg
    faces += [(bot, b + j, b + (j + 1) % seg) for j in range(seg)]
    return verts, faces


def catmull(pts, samples):
    P = [pts[0]] + pts + [pts[-1]]
    out = []
    for i in range(1, len(P) - 2):
        for s in range(samples):
            t = s / samples
            out.append(tuple(0.5 * ((2 * P[i][k]) + (-P[i - 1][k] + P[i + 1][k]) * t +
                                    (2 * P[i - 1][k] - 5 * P[i][k] + 4 * P[i + 1][k] - P[i + 2][k]) * t * t +
                                    (-P[i - 1][k] + 3 * P[i][k] - 3 * P[i + 1][k] + P[i + 2][k]) * t ** 3)
                           for k in range(len(pts[0]))))
    out.append(tuple(pts[-1]))
    return out


def ribbon(center, normals, widths, thicks, seg=8, inner=0.3):
    """Lens-section hair bundle: flat-ish on the inner (scalp) side, domed outside, given per-point width/thickness."""
    n = len(center)
    rings = []
    for i in range(n):
        T = (center[min(i + 1, n - 1)] - center[max(i - 1, 0)])
        T = T.normalized() if T.length > 1e-9 else Vector((0, 0, -1))
        N = normals[i] - T * normals[i].dot(T)
        N = N.normalized() if N.length > 1e-9 else Vector((0, -1, 0))
        S = T.cross(N).normalized()
        w, th = widths[i], thicks[i]
        ring = []
        for j in range(seg):
            a = 2 * math.pi * j / seg
            c, s = math.cos(a), math.sin(a)
            ring.append(center[i] + S * (w * c) + N * (th * (s if s > 0 else inner * s)))
        rings.append(ring)
    verts, faces = loft(rings)
    uvs = [(0.5 + 0.5 * math.cos(2 * math.pi * j / seg), i / (n - 1)) for i in range(n) for j in range(seg)] + [(0.5, 0.0), (0.5, 1.0)]
    return verts, faces, uvs


class Look:
    def __init__(self, name, v):
        self.name, self.v = name, v
        self.H = v['H']
        self.prof = v['profile']
        self.rng = random.Random(name)
        self.coll = self.make_collection()
        self.M = {k: self.flat(k, srgb(c)) for k, c in v['colors'].items()}
        for k in ('hair', 'hair_light', 'hair_shade'):
            self.M['s_' + k] = self.strand_material(k)
        self.root = self.empty('EMPTY_Marisa_%s' % name, None, (0, 0, 0), 3)
        self.body_rig = self.empty('EMPTY_%s_body' % name, self.root, (0, 0, 0), 2)
        self.stretch_rig = self.empty('EMPTY_%s_stretch' % name, self.body_rig, (0, 0, 0), 1.8)
        h = v['hat']
        self.hat_pivot = Vector((0, 0.05 * self.H, h['seat']))
        tilt = Euler([math.radians(a) for a in h['tilt']]).to_matrix().to_4x4()
        self.hat_xf = Matrix.Translation(self.hat_pivot) @ tilt @ Matrix.Translation(-self.hat_pivot)
        self.hat_inv = self.hat_xf.inverted()
        self.hat_rig = self.empty('EMPTY_%s_hat' % name, self.body_rig, self.hat_pivot, 1.5)
        self.braid_path = [self.surface(math.radians(th), z) + self.normal(math.radians(th), z) * off for th, z, off in v['braid']['track']]
        self.braid_pivot = Vector(self.braid_path[0])
        self.braid_rig = self.empty('EMPTY_%s_braid' % name, self.body_rig, self.braid_pivot, 1)

    # ---------------------------------------------------------- scene plumbing
    def make_collection(self):
        cname = 'COL_Marisa_%s' % self.name
        old = bpy.data.collections.get(cname)
        if old:
            for ob in list(old.objects):
                bpy.data.objects.remove(ob, do_unlink=True)
            bpy.data.collections.remove(old)
        for m in [m for m in bpy.data.materials if m.name.startswith('MAT_%s_' % self.name)]:
            bpy.data.materials.remove(m)
        for me in [me for me in bpy.data.meshes if me.users == 0]:
            bpy.data.meshes.remove(me)
        for a in [a for a in bpy.data.actions if a.users == 0]:
            bpy.data.actions.remove(a)
        coll = bpy.data.collections.new(cname)
        bpy.context.scene.collection.children.link(coll)
        return coll

    def empty(self, name, parent, loc, size):
        ob = bpy.data.objects.new(name, None)
        ob.empty_display_size = size
        ob.parent = parent
        ob.location = loc
        self.coll.objects.link(ob)
        return ob

    def flat(self, key, rgb):
        mat = bpy.data.materials.new('MAT_%s_%s' % (self.name, key))
        mat.use_nodes = True
        bsdf = next(n for n in mat.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
        bsdf.inputs['Base Color'].default_value = (*rgb, 1)
        bsdf.inputs['Roughness'].default_value = 0.85 if key.startswith('hair') else 0.75
        if key == 'skin':
            bsdf.inputs['Subsurface Weight'].default_value = 0.15
            bsdf.inputs['Subsurface Radius'].default_value = (1.0, 0.45, 0.3)
        return mat

    def strand_material(self, key):
        """Hair colour with manga strand lines along each bundle and a darker root (needs ribbon UVs)."""
        mat = bpy.data.materials.new('MAT_%s_s_%s' % (self.name, key))
        mat.use_nodes = True
        nt = mat.node_tree
        bsdf = next(n for n in nt.nodes if n.type == 'BSDF_PRINCIPLED')
        bsdf.inputs['Roughness'].default_value = 0.85
        base, shade = srgb(self.v['colors'][key]), srgb(self.v['colors']['hair_shade'])
        dark = tuple(c * 0.55 for c in shade)
        uv = nt.nodes.new('ShaderNodeUVMap')
        sep = nt.nodes.new('ShaderNodeSeparateXYZ')
        nt.links.new(uv.outputs['UV'], sep.inputs[0])

        def math_node(op, a, b=None):
            m = nt.nodes.new('ShaderNodeMath')
            m.operation = op
            for i, x in enumerate((a, b)):
                if x is None:
                    continue
                if isinstance(x, (int, float)):
                    m.inputs[i].default_value = x
                else:
                    nt.links.new(x, m.inputs[i])
            return m.outputs[0]

        # one thin line down the middle of each bundle: 1 - min(|sin(2 pi u)| * 7, 1)
        line = math_node('SUBTRACT', 1.0, math_node('MINIMUM', math_node('MULTIPLY', math_node('ABSOLUTE', math_node('SINE', math_node('MULTIPLY', sep.outputs['X'], 2 * math.pi))), 7.0), 1.0))
        root = math_node('MAXIMUM', math_node('SUBTRACT', 1.0, math_node('MULTIPLY', sep.outputs['Y'], 4.0)), 0.0)

        def mix(fac, a, b):
            m = nt.nodes.new('ShaderNodeMix')
            m.data_type = 'RGBA'
            nt.links.new(fac, m.inputs[0])
            for i, c in ((6, a), (7, b)):
                if isinstance(c, tuple):
                    m.inputs[i].default_value = (*c, 1)
                else:
                    nt.links.new(c, m.inputs[i])
            return m.outputs[2]

        col = mix(math_node('MULTIPLY', line, 0.4), base, dark)
        col = mix(math_node('MULTIPLY', root, 0.5), col, shade)
        nt.links.new(col, bsdf.inputs['Base Color'])
        return mat

    def face_material(self):
        mat = bpy.data.materials.new('MAT_%s_face' % self.name)
        mat.use_nodes = True
        nt = mat.node_tree
        bsdf = next(n for n in nt.nodes if n.type == 'BSDF_PRINCIPLED')
        bsdf.inputs['Roughness'].default_value = 0.6
        # every expression texture in one material; the face object's integer property 'expr'
        # (index into mat['expr_order'], keyed by animate.py) selects which one shows
        names = sorted(f[len(self.name) + 1:-4] for f in os.listdir(os.path.join(HERE, 'textures'))
                       if f.startswith(self.name + '_') and f.endswith('.png'))
        names.remove('neutral')
        names.insert(0, 'neutral')
        mat['expr_order'] = names
        attr = nt.nodes.new('ShaderNodeAttribute')
        attr.attribute_type = 'OBJECT'
        attr.attribute_name = 'expr'
        color = alpha = None
        for i, e in enumerate(names):
            tex = nt.nodes.new('ShaderNodeTexImage')
            tex.name = 'face_' + e
            img = bpy.data.images.load(os.path.join(HERE, 'textures', '%s_%s.png' % (self.name, e)), check_existing=True)
            img.reload()
            tex.image = img
            if color is None:
                color, alpha = tex.outputs['Color'], tex.outputs['Alpha']
                continue
            cmp = nt.nodes.new('ShaderNodeMath')
            cmp.operation = 'COMPARE'
            cmp.inputs[1].default_value = i
            cmp.inputs[2].default_value = 0.5
            nt.links.new(attr.outputs['Fac'], cmp.inputs[0])
            mc = nt.nodes.new('ShaderNodeMix')
            mc.data_type = 'RGBA'
            # Mix sockets share names across data types: float A/B = 2/3, colour A/B = 6/7; results 0 / 2
            nt.links.new(cmp.outputs[0], mc.inputs[0])
            nt.links.new(color, mc.inputs[6])
            nt.links.new(tex.outputs['Color'], mc.inputs[7])
            ma = nt.nodes.new('ShaderNodeMix')
            ma.data_type = 'FLOAT'
            nt.links.new(cmp.outputs[0], ma.inputs[0])
            nt.links.new(alpha, ma.inputs[2])
            nt.links.new(tex.outputs['Alpha'], ma.inputs[3])
            color, alpha = mc.outputs[2], ma.outputs[0]
        nt.links.new(color, bsdf.inputs['Base Color'])
        nt.links.new(alpha, bsdf.inputs['Alpha'])
        # a printed face, not a glossy one: no specular, or the sky shows in the black eyes
        bsdf.inputs['Roughness'].default_value = 1.0
        bsdf.inputs['Specular IOR Level'].default_value = 0.0
        mat.surface_render_method = 'DITHERED'
        return mat

    def obj(self, name, verts, faces, keys, uvs=None, recalc=True, rig=None, ink=1.0):
        """keys: material key, or one key per face. Mesh coordinates are body space; rig = parent empty."""
        me = bpy.data.meshes.new('SM_%s_%s' % (self.name, name))
        me.from_pydata([tuple(p) for p in verts], [], faces)
        me.update()
        if uvs is not None:
            layer = me.uv_layers.new(name='UV')
            for poly in me.polygons:
                for li in poly.loop_indices:
                    layer.data[li].uv = uvs[me.loops[li].vertex_index]
        if recalc:
            bm = bmesh.new()
            bm.from_mesh(me)
            bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
            bm.to_mesh(me)
            bm.free()
        for p in me.polygons:
            p.use_smooth = True
        per_face = [keys] * len(faces) if isinstance(keys, (str, bpy.types.Material)) else keys
        slots = []
        for k in per_face:
            if k not in slots:
                slots.append(k)
        for k in slots:
            me.materials.append(k if isinstance(k, bpy.types.Material) else self.M[k])
        for p in me.polygons:
            p.material_index = slots.index(per_face[p.index])
        ob = bpy.data.objects.new('SM_%s_%s' % (self.name, name), me)
        rig = rig or self.stretch_rig
        ob.parent = rig
        rest = {self.stretch_rig: Vector(), self.hat_rig: self.hat_pivot, self.braid_rig: self.braid_pivot}[rig]
        ob.matrix_parent_inverse = Matrix.Translation(-rest)
        ob['ink'] = ink
        self.coll.objects.link(ob)
        return ob

    def parts(self, name, parts, rig=None, ink=1.0):
        """parts: (verts, faces, key) or (verts, faces, uvs, key); UV'd hair bundles get the strand-line materials."""
        verts, faces, keys, uvs = [], [], [], []
        for p in parts:
            v, f, k = (p[0], p[1], p[-1])
            uv = p[2] if len(p) == 4 else None
            if uv is not None and isinstance(k, str) and k.startswith('hair'):
                k = 's_' + k
            o = len(verts)
            verts += v
            uvs += uv if uv is not None else [(0.5, 0.5)] * len(v)
            faces += [tuple(i + o for i in face) for face in f]
            keys += [k] * len(f) if isinstance(k, str) else k
        return self.obj(name, verts, faces, keys, uvs=uvs, rig=rig, ink=ink)

    # ---------------------------------------------------------- body surface
    def profile(self, y):
        ys = [p[0] for p in self.prof]
        out = []
        for k in (1, 2, 3):
            vs = [p[k] for p in self.prof]
            n = len(ys)
            d = [(vs[i + 1] - vs[i]) / (ys[i + 1] - ys[i]) for i in range(n - 1)]
            m = [d[0]] + [0 if d[i - 1] * d[i] <= 0 else (d[i - 1] + d[i]) / 2 for i in range(1, n - 1)] + [d[-1]]
            yy = min(max(y, ys[0]), ys[-1])
            i = max(j for j in range(n - 1) if ys[j] <= yy) if yy < ys[-1] else n - 2
            h = ys[i + 1] - ys[i]
            t = (yy - ys[i]) / h
            out.append((2 * t ** 3 - 3 * t ** 2 + 1) * vs[i] + (t ** 3 - 2 * t ** 2 + t) * h * m[i] +
                       (-2 * t ** 3 + 3 * t ** 2) * vs[i + 1] + (t ** 3 - t ** 2) * h * m[i + 1])
        return out

    def surface(self, theta, y):
        hx, rear, front = self.profile(y)
        s, c = math.sin(theta), math.cos(theta)
        n = self.v['n_front'] if c > 0 else self.v['n_rear']
        return Vector((hx * spow(s, 2 / n), -(front if c > 0 else rear) * spow(c, 2 / n), y))

    def normal(self, theta, y):
        e = 1e-3
        a = self.surface(theta + e, y) - self.surface(theta - e, y)
        b = self.surface(theta, min(y + e, self.H)) - self.surface(theta, max(y - e, 0))
        n = a.cross(b)
        if n.length < 1e-9:
            return Vector((0, 0, 1))
        n.normalize()
        p = self.surface(theta, y)
        return -n if Vector((p.x, p.y, 0)).dot(n) < 0 else n

    # ---------------------------------------------------------- shared shapes
    @staticmethod
    def bow(center, L, yaw=0.0, tilt=0.0, tails=1.0):
        """Ribbon bow facing -Y: two creased loops, a knot and two tails. L = loop length."""
        R = Euler((math.radians(tilt), 0, yaw)).to_matrix()
        C = Vector(center)
        out = []

        def place(vf, key):
            return ([R @ p + C for p in vf[0]], vf[1], key)

        for sd in (-1, 1):
            def loop_shape(q, sd=sd):
                u = max(0.0, min(1.0, (q.x * sd + L) / (2 * L)))
                z = q.z * (0.3 + 0.7 * u ** 0.6)
                y = q.y * (0.35 + 0.65 * u ** 0.5)
                if y < 0:  # fold crease along the middle of the loop face
                    y *= 1 - 0.85 * math.exp(-(q.z / (0.22 * L)) ** 2) * math.sin(math.pi * min(1, u * 1.15))
                return Vector((q.x, y, z))
            out.append(place(ellipsoid((sd * 0.95 * L, 0.05 * L, 0.1 * L), (L, 0.17 * L, 0.62 * L), 28, 16,
                                       Euler((0, math.radians(-sd * 12), 0)), loop_shape), 'white'))
            out.append(place(ellipsoid((sd * 0.42 * L, -0.02 * L, -0.78 * L * tails), (0.26 * L, 0.08 * L, 0.72 * L * tails), 12, 8,
                                       Euler((0, math.radians(sd * 24), 0)),
                                       lambda q: Vector((q.x * (1.0 - 0.35 * (q.z < 0) * abs(q.z) / (0.72 * L)), q.y, q.z))),
                             'white_shade'))
        out.append(place(ellipsoid((0, -0.1 * L, 0.02 * L), (0.26 * L, 0.18 * L, 0.32 * L), 14, 8), 'white'))
        return out

    def strand(self, root, tip, width, thick, off=0.14, bulge=0.25, curl=0.0, sweep=0.0, wave=0.0, phase=0.0, n=18):
        """Pointed hair bundle laid on the scalp from root (theta deg, z) to tip (theta deg, z); wave = soft S-curve (deg)."""
        (th0, z0), (th1, z1) = root, tip
        center, normals, widths, thicks = [], [], [], []
        for i in range(n + 1):
            t = i / n
            e = t * t * (3 - 2 * t)
            th = math.radians(th0 + (th1 - th0) * e + sweep * math.sin(math.pi * t) + wave * math.sin(2 * math.pi * t + phase) * t)
            z = min(max(z0 + (z1 - z0) * t, 0.03 * self.H), 0.993 * self.H)
            p, nn = self.surface(th, z), self.normal(th, z)
            center.append(p + nn * (off + bulge * math.sin(math.pi * t) + curl * t ** 3))
            normals.append(nn)
            widths.append(width * (0.72 + 0.45 * math.sin(math.pi * min(1.0, t * 1.5))) * (1 - t) ** 0.85 + 0.005)
            thicks.append(thick * (0.45 + 0.55 * (1 - t)) + 0.01)
        for i in range(len(center)):
            center[i] = self.under_hat(center[i], thicks[i] + widths[i] * 0.3)
        return ribbon(center, normals, widths, thicks, seg=10)

    def under_hat(self, p, pad):
        """The hat presses the hair: above the brim plane, keep a point inside the crown of the hat."""
        h = self.v['hat']
        q = self.hat_inv @ p
        if q.z <= h['seat'] - 0.25:
            return p
        d = Vector((q.x, q.y - self.hat_pivot.y, 0))
        up = max(0.0, q.z - h['seat'] - 0.2)          # the crown narrows with height
        lim = max(0.2, h['rb'] * max(0.0, 1 - up / h['hc']) ** 1.2 * 0.8 - pad)
        if d.length <= lim:
            return p
        q = Vector((0, self.hat_pivot.y, q.z)) + d.normalized() * lim
        return self.hat_xf @ q

    # ---------------------------------------------------------- parts
    def body(self):
        H = self.H
        ys = [H * t for t in (0, 0.012, 0.035, 0.07, 0.12, 0.19, 0.26, 0.34, 0.42, 0.51, 0.6, 0.69, 0.77, 0.84, 0.9, 0.94, 0.97, 0.99)]
        rings = [[self.surface(2 * math.pi * j / 40, y) for j in range(40)] for y in ys]
        self.obj('body', *loft(rings), 'skin')

    def face(self):
        x0, x1, y0, y1 = self.v['face']['frame']
        GU, GV = 32, 26
        verts, uvs = [], []
        for i in range(GU + 1):
            th = math.radians(-80 + 160 * i / GU)
            for j in range(GV + 1):
                y = self.H * (0.04 + 0.86 * j / GV)
                p = self.surface(th, y) + self.normal(th, y) * 0.04
                verts.append(p)
                uvs.append(((p.x - x0) / (x1 - x0), (p.z - y0) / (y1 - y0)))
        faces = [(i * (GV + 1) + j, (i + 1) * (GV + 1) + j, (i + 1) * (GV + 1) + j + 1, i * (GV + 1) + j + 1)
                 for i in range(GU) for j in range(GV)]
        ob = self.obj('face', verts, faces, self.face_material(), uvs, recalc=False, ink=0)
        ob['expr'] = 0

    def shell(self):
        """Smooth scalp cap under the strands; its hem sits a little above the strand tips, and in front it
        can be tucked up under the brim (hair.cap_front) so only the bundles frame the forehead."""
        h = self.v['hair']
        H = self.H
        front = h.get('cap_front', h['bangs'] + 0.1)
        knots = [(0, front), (48, max(front, h['bangs'] + 0.2)), (68, h['side'] + 0.8), (115, (h['side'] + h['back']) / 2 + 0.5), (180, h['back'] + 0.35)]

        def hem(deg):
            d = abs(deg)
            for (a, ya), (b, yb) in zip(knots, knots[1:]):
                if d <= b:
                    t = (d - a) / (b - a)
                    return ya + (yb - ya) * t * t * (3 - 2 * t)
            return knots[-1][1]

        NT, ROWS, TOP = 120, 10, H * 0.995
        outer, inner = [], []
        for i in range(NT):
            deg = 360.0 * i / NT
            deg = deg - 360 if deg > 180 else deg
            th = math.radians(deg)
            y0 = hem(deg)
            o, n_ = [], []
            for k in range(ROWS):
                t = k / (ROWS - 1)
                y = y0 + (TOP - y0) * (1 - (1 - t) ** 1.6)
                p, nn = self.surface(th, y), self.normal(th, y)
                o.append(p + nn * (0.06 + h['thick'] * 0.55 * min(1, 0.4 + t * 2)))
                n_.append(p + nn * 0.03)
            outer.append(o)
            inner.append(n_)
        verts = [p for r in outer for p in r] + [p for r in inner for p in r]
        io = lambda i, k: (i % NT) * ROWS + k
        ii = lambda i, k: NT * ROWS + (i % NT) * ROWS + k
        faces, keys = [], []
        for i in range(NT):
            for k in range(ROWS - 1):
                faces.append((io(i, k), io(i + 1, k), io(i + 1, k + 1), io(i, k + 1))); keys.append('hair')
                faces.append((ii(i, k), ii(i, k + 1), ii(i + 1, k + 1), ii(i + 1, k))); keys.append('hair_shade')
            faces.append((io(i, 0), ii(i, 0), ii(i + 1, 0), io(i + 1, 0))); keys.append('hair_shade')
        self.obj('hair_cap', [self.under_hat(p, 0.08) for p in verts], faces, keys)
        cv, cf = ellipsoid((0, 0.05 * H, H * 0.985), (self.profile(H * 0.9)[0] * 1.05, self.profile(H * 0.9)[1], H * 0.09), 18, 6)
        self.obj('hair_crown', [self.under_hat(p, 0.08) for p in cv], cf, 'hair')

    def hair(self):
        """M01 hair as separate soft, wavy, pointed bundles: bangs stopping at the eye tops with a lock
        between the eyes, long side locks on Marisa's right, shorter ones behind the braid, fluffy back."""
        h, f = self.v['hair'], self.v['face']
        H, R = self.H, self.rng
        W, T, messy = h['strand_w'], h['thick'], h['messy']
        eye_top = f['eye_y'] + f['eye_h'] * 0.5
        eye_lo, eye_hi = f['eye_x'] - f['eye_w'] * 0.6, f['eye_x'] + f['eye_w'] * 0.62
        parts = []
        nb = h['n_bangs']
        for k in range(nb):
            u = k / (nb - 1)
            th = -62 + 124 * u + R.uniform(-2.5, 2.5)
            out = math.copysign(1, th) if abs(th) > 3 else (1 if k % 2 else -1)
            tip_th = th + out * (3 + 5 * abs(th) / 62) * (0.7 + messy)
            z = h['bangs'] - h['bang_amp'] * (0.4 + 0.6 * R.random())
            x = abs(self.surface(math.radians(tip_th), z).x)
            if eye_lo - 0.1 < x < eye_hi:
                z = max(z, eye_top + 0.08 + 0.2 * R.random())
            elif x <= eye_lo - 0.1:
                z = f['eye_y'] + 0.35 + 0.35 * R.random()      # lock hanging between the eyes
            else:
                z = min(z, f['eye_y'] + 0.2 + 0.5 * R.random())  # outer bangs frame the face
            off, wave, phase = 0.08 + 0.1 * (k % 2) + 0.04 * R.random(), R.uniform(3, 7) * out, R.uniform(0, 1.5)
            parts.append(self.strand((th * 0.3, H * 0.988), (tip_th, z), W * (0.9 + 0.25 * R.random()), T,
                                     off=off, bulge=0.16 + 0.1 * R.random(),
                                     curl=0.12 * messy, wave=wave, phase=phase)
                         + ('hair_light' if k % 2 else 'hair',))
            # finer children riding on the lock: same flow, tips splayed and a little shorter
            for c in range(h.get('cluster', 0)):
                sgn = -1 if c % 2 else 1
                cz = min(h['bangs'], z + R.uniform(0.1, 0.35))
                parts.append(self.strand((th * 0.3 + sgn * 1.2, H * 0.985), (tip_th + sgn * R.uniform(1.5, 3.0), cz),
                                         W * R.uniform(0.58, 0.72), T * 0.7, off=off + 0.07 + 0.03 * c, bulge=0.14,
                                         curl=0.18 * messy, wave=wave * R.uniform(0.8, 1.3), phase=phase + R.uniform(-0.4, 0.4), n=16)
                             + ('hair' if k % 2 else 'hair_light',))
        # outer layer of short, finer wisps over the bangs (breaks up the fringe edge)
        for k in range(h.get('wisps', 0)):
            th = R.uniform(-56, 56)
            out = math.copysign(1, th) if abs(th) > 3 else (1 if k % 2 else -1)
            tip_th = th + out * R.uniform(4, 9)
            z = h['bangs'] - R.uniform(-0.2, 0.5) * h['bang_amp']
            x = abs(self.surface(math.radians(tip_th), z).x)
            if x < eye_hi:
                z = max(z, eye_top + 0.25 + 0.2 * R.random())
            parts.append(self.strand((th * 0.45, H * 0.965), (tip_th, z), W * R.uniform(0.45, 0.65), T * 0.6,
                                     off=0.24 + 0.08 * R.random(), bulge=0.12, curl=0.22 + 0.2 * messy,
                                     wave=R.uniform(4, 9) * out, phase=R.uniform(0, 2), n=14)
                         + ('hair_light' if k % 3 else 'hair',))
        # side locks: long and wavy on Marisa's right, shorter behind the braid on her left
        ns = h.get('n_side')
        sets = ((-1, (64, 72, 81, 91, 101), 0.0), (1, (80, 89, 98, 107), 0.6)) if not ns else \
            ((-1, tuple(62 + 42 * i / (ns - 1) for i in range(ns)), 0.0), (1, tuple(78 + 32 * i / (ns - 2) for i in range(ns - 1)), 0.6))
        for side, angles, drop in sets:
            for k, a in enumerate(angles):
                z = h['side'] + drop + (-0.5 if k == 0 else 0) + R.uniform(-0.35, 0.45)
                off, wave, phase = 0.12 + 0.07 * (k % 2), side * R.uniform(4, 8), R.uniform(0, 1.2)
                parts.append(self.strand((side * a * 0.55, H * 0.975), (side * (a + 5 + 2 * k), z), W * 1.12 * R.uniform(0.85, 1.1), T * 1.05,
                                         off=off, bulge=0.24, curl=0.12 + 0.12 * messy, wave=wave, phase=phase)
                             + ('hair' if k % 2 else 'hair_light',))
                if h.get('cluster', 0):
                    parts.append(self.strand((side * a * 0.55 + 1.5, H * 0.972), (side * (a + 8 + 2 * k), z + R.uniform(0.3, 0.9)),
                                             W * R.uniform(0.4, 0.55), T * 0.7, off=off + 0.08, bulge=0.2,
                                             curl=0.2 + 0.15 * messy, wave=wave * 1.2, phase=phase + 0.3, n=16)
                                 + ('hair_light' if k % 2 else 'hair',))
        # back hair: fluffy, alternating lengths, tips curling outward
        nk = h['n_back']
        for k in range(nk):
            a = 110 + 140 * k / (nk - 1)
            z = h['back'] + R.uniform(-0.45, 0.55) - (0.35 if k % 2 else 0)
            parts.append(self.strand((a * 0.7 + 0.3 * 180, H * 0.98), (a + R.uniform(-4, 4), z), W * 2.0, T * 1.1,
                                     off=0.1 + 0.1 * (k % 2), bulge=0.32, curl=0.4 + 0.25 * messy,
                                     wave=R.uniform(6, 11) * (1 if k % 2 else -1), phase=R.uniform(0, 2))
                         + ('hair' if k % 2 else 'hair_shade',))
        self.parts('hair_strands', parts, ink=h.get('ink', 0.6))

    def braid(self):
        """Three-strand plait on Marisa's left: temple bundle, chevron plait, ribbon bow, fanned pointed tuft."""
        b = self.v['braid']
        r, s = b['r'], b['bow']
        spl = [Vector(p) for p in catmull([tuple(p) for p in self.braid_path], 8)]
        lens = [0.0]
        for a, c in zip(spl, spl[1:]):
            lens.append(lens[-1] + (c - a).length)

        def at(d):
            d = max(0.0, min(d, lens[-1]))
            i = max(j for j in range(len(lens) - 1) if lens[j] <= d) if d < lens[-1] else len(lens) - 2
            t = (d - lens[i]) / max(1e-9, lens[i + 1] - lens[i])
            p = spl[i].lerp(spl[i + 1], t)
            T = (spl[i + 1] - spl[i]).normalized()
            th = math.atan2(p.x, -p.y)
            N = self.normal(th, max(0.05, min(p.z, self.H * 0.98)))
            N = (N - T * N.dot(T)).normalized()
            return p, T, N, T.cross(N).normalized()

        def d_of_z(z):
            for i in range(len(spl) - 1):
                if spl[i + 1].z <= z <= spl[i].z:
                    t = (spl[i].z - z) / max(1e-9, spl[i].z - spl[i + 1].z)
                    return lens[i] + t * (lens[i + 1] - lens[i])
            return lens[-1]

        d_bow = d_of_z(b['bow_z'])
        parts = []
        # temple bundle feeding the braid
        th0 = b['track'][0][0]
        parts.append(self.strand((th0 * 0.5, self.H * 0.975), (th0 + 2, b['track'][0][1] - 0.4), r * 1.35, r * 0.7,
                                 off=0.2, bulge=0.25) + ('hair_light',))
        # core
        rings = []
        for k in range(14):
            p, T, N, S = at(d_bow * k / 13)
            rr = r * 0.62
            rings.append([p + S * (rr * math.cos(2 * math.pi * j / 10)) + N * (rr * math.sin(2 * math.pi * j / 10)) for j in range(10)])
        parts.append(loft(rings) + ('hair_shade',))
        # chevron plait: alternating diagonal lens bundles crossing the core
        step = r * 0.78
        k = 0
        d = r * 0.35
        while d < d_bow - r * 0.35:
            p, T, N, S = at(d)
            sg = 1 if k % 2 else -1
            taper = 1 - 0.18 * d / max(d_bow, 1e-6)
            cen, nor, wid, thi = [], [], [], []
            for i in range(9):
                u = i / 8
                cen.append(p + S * (sg * (-0.95 + 1.9 * u) * r * taper) + T * ((-0.55 + 1.0 * u) * r) + N * (0.42 * r * math.sin(math.pi * u)))
                nor.append(N)
                wid.append(0.5 * r * taper * math.sin(math.pi * (0.08 + 0.84 * u)) ** 0.6 + 0.01)
                thi.append(0.4 * r * taper * math.sin(math.pi * (0.05 + 0.9 * u)) ** 0.5 + 0.01)
            parts.append(ribbon(cen, nor, wid, thi, seg=8, inner=0.6) + ('hair_light' if k % 2 else 'hair',))
            d += step
            k += 1
        self.parts('braid', parts, rig=self.braid_rig, ink=0.6)
        # ribbon band and bow
        p, T, N, S = at(d_bow)
        band = loft([[p + T * dz + S * (rad * math.cos(2 * math.pi * j / 12)) + N * (rad * math.sin(2 * math.pi * j / 12)) for j in range(12)]
                     for (rad, dz) in ((r * 0.95, -0.3 * s), (r * 1.08, -0.22 * s), (r * 1.08, 0.22 * s), (r * 0.95, 0.3 * s))])
        yaw = math.atan2(N.x, -N.y)
        bow = [band + ('white',)] + self.bow(p + N * (r * 1.0) + Vector((0, 0, 0.1 * s)), 0.85 * s, yaw=yaw)
        self.parts('braid_bow', bow, rig=self.braid_rig, ink=0.6)
        # tuft: five pointed hairs fanning out below the bow
        L = b['tuft'] * r * 2.3
        tuft = []
        for k, (fan, ln) in enumerate(((-40, 0.8), (-18, 1.0), (0, 1.1), (20, 0.95), (42, 0.75))):
            a = math.radians(fan)
            cen, nor, wid, thi = [], [], [], []
            for i in range(11):
                u = i / 10
                dirv = (T * math.cos(a) + S * math.sin(a)).normalized()
                cen.append(p + T * (0.25 * s) + dirv * (L * ln * u) + N * (0.25 * r * math.sin(math.pi * u) + 0.15 * r * u * u) + S * (0.25 * r * math.sin(a) * u * u))
                nor.append(N)
                wid.append(0.42 * r * (0.8 + 0.4 * math.sin(math.pi * min(1, u * 1.6))) * (1 - u) ** 0.8 + 0.005)
                thi.append(0.3 * r * (1 - 0.6 * u) + 0.01)
            tuft.append(ribbon(cen, nor, wid, thi, seg=8) + ('hair_light' if k % 2 else 'hair',))
        self.parts('braid_tuft', tuft, rig=self.braid_rig, ink=0.6)

    def hat(self):
        h = self.v['hat']
        seat, rb, hc = h['seat'], h['rb'], h['hc']
        pivot = self.hat_pivot
        xf = lambda vs: [self.hat_xf @ p for p in vs]
        base_y = pivot.y
        # brim: elliptical, wavy, drooping at the rim, back edge flips up a little
        bseg = 96
        prof = [(0.0, 0.55), (0.5, 0.5), (0.7, 0.35), (0.9, 0.15), (1.0, 0.02), (0.97, -0.12), (0.9, -0.16), (0.5, -0.05), (0.0, -0.05)]

        def brim_point(a, r, dz):
            s, c = math.sin(a), math.cos(a)
            k = rb * 0.92 / h['brim_rx'] + (1 - rb * 0.92 / h['brim_rx']) * r
            wave = h['brim_wave'] * math.sin(5 * a + 0.7) * r ** 2
            droop = -h['brim_droop'] * r ** 3 + (0.45 * h['brim_droop'] * r ** 3 * abs(c) if c < 0 else 0)
            return Vector((h['brim_rx'] * s * k, base_y - h['brim_ry'] * c * k, seat + dz + wave + droop))

        verts, faces, keys = [], [], []
        for j in range(bseg):
            for (r, dz) in prof:
                verts.append(brim_point(2 * math.pi * j / bseg, r, dz))
        nb = len(prof)
        for j in range(bseg):
            for k in range(nb - 1):
                a, b = j * nb + k, ((j + 1) % bseg) * nb + k
                faces.append((a, a + 1, b + 1, b))
                keys.append('hat' if k < 4 else 'hat_inner')
        parts = [(xf(verts), faces, keys)]
        # white frill under the rim: a pleated, scalloped skirt hanging just inside the brim edge
        fr = h.get('frill', 0.0)
        if fr:
            fseg, rows = 240, [(0.93, -0.15, 0.0), (0.92, -0.15, 0.45), (0.905, -0.15, 1.0)]
            fv, ff = [], []
            for j in range(fseg):
                a = 2 * math.pi * j / fseg
                pleat = 0.035 * math.sin(a * 48)
                scallop = 0.65 + 0.35 * abs(math.sin(a * 24))
                for (r, dz, t) in rows:
                    p = brim_point(a, r + pleat * t, dz)
                    fv.append(p - Vector((0, 0, fr * scallop * t)))
            nr = len(rows)
            for j in range(fseg):
                for k in range(nr - 1):
                    a, b = j * nr + k, ((j + 1) % fseg) * nr + k
                    ff.append((a, a + 1, b + 1, b))
            parts.append((xf(fv), ff, 'white'))
        # cone: smooth cubic spine leaning back toward Marisa's left, parallel-transported rings, soft wrinkles
        bend, droop = h['bend'], h.get('droop', 0.0)
        P = [Vector((0, 0, 0)), Vector((0, 0.04 * hc, 0.45 * hc)),
             Vector((0.12 * bend * hc, 0.2 * bend * hc, (0.86 - 0.3 * droop) * hc)),
             Vector((0.42 * bend * hc, 0.3 * bend * hc, (0.96 - droop) * hc))]

        def bez(t):
            m = 1 - t
            return P[0] * m ** 3 + P[1] * 3 * m * m * t + P[2] * 3 * m * t * t + P[3] * t ** 3

        def dbez(t):
            m = 1 - t
            return (P[1] - P[0]) * 3 * m * m + (P[2] - P[1]) * 6 * m * t + (P[3] - P[2]) * 3 * t * t

        cseg, NR = 32, 44
        base = Vector((0, base_y, seat + 0.2))
        # parallel-transported frames sampled finely along the spine; band and bow reuse them
        frames = []
        Tp, S, D = Vector((0, 0, 1)), Vector((1, 0, 0)), Vector((0, 1, 0))
        for i in range(401):
            T = dbez(i / 400).normalized()
            q = Tp.rotation_difference(T)
            S, D, Tp = q @ S, q @ D, T
            frames.append((S.copy(), D.copy()))

        def ring_point(t, th, grow=0.0, wrinkle=True):
            S, D = frames[min(400, int(round(t * 400)))]
            ra = rb * (1 - t) ** 1.2 + 0.015 + grow
            wr = 1 + (h['crumple'] * math.sin(3 * th + 5 * t + 0.6) * math.sin(math.pi * min(1, t * 1.25)) if wrinkle else 0)
            return base + bez(t) + S * (ra * wr * math.sin(th)) - D * (ra * 0.93 * wr * math.cos(th))

        rings = [[ring_point(1 - (1 - i / NR) ** 1.35, 2 * math.pi * j / cseg) for j in range(cseg)] for i in range(NR + 1)]
        v, f = loft(rings)
        parts.append((xf(v), f, 'hat'))
        self.parts('hat', parts, rig=self.hat_rig)
        # white band + front bow
        t0, t1 = 0.03, 0.13
        band = [[ring_point(t, 2 * math.pi * j / cseg, g, False) for j in range(cseg)]
                for (t, g) in ((t0 - 0.005, 0.02), (t0, 0.16), (t1, 0.16), (t1 + 0.005, 0.02))]
        bv, bf = loft(band, False, False)
        bp = [(xf(bv), bf, 'white')]
        s = h['bow']
        a = math.asin(max(-0.95, min(0.95, h['bow_x'] / rb)))
        K = ring_point((t0 + t1) / 2, a, 0.2 * s, False) + Vector((0, 0, 0.15 * s))
        bp += [(xf(v), f, k) for v, f, k in self.bow(K, 1.75 * s, yaw=a * 0.8, tilt=-8, tails=0.45)]
        self.parts('hat_bow', bp, rig=self.hat_rig, ink=0.7)

    def outline(self, width=0.09):
        """Manga ink line: inverted hull (flipped solidify, backface-culled dark material); per-object scale in ob['ink']."""
        mat = bpy.data.materials.new('MAT_%s_ink' % self.name)
        mat.use_nodes = True
        nt = mat.node_tree
        for n in list(nt.nodes):
            if n.type != 'OUTPUT_MATERIAL':
                nt.nodes.remove(n)
        em = nt.nodes.new('ShaderNodeEmission')
        em.inputs['Color'].default_value = (*srgb('#2a1a12'), 1)
        # the hull must not shade the body it encloses: transparent to shadow rays
        lp = nt.nodes.new('ShaderNodeLightPath')
        tr = nt.nodes.new('ShaderNodeBsdfTransparent')
        mix = nt.nodes.new('ShaderNodeMixShader')
        nt.links.new(lp.outputs['Is Shadow Ray'], mix.inputs['Fac'])
        nt.links.new(em.outputs['Emission'], mix.inputs[1])
        nt.links.new(tr.outputs['BSDF'], mix.inputs[2])
        nt.links.new(mix.outputs['Shader'], next(n for n in nt.nodes if n.type == 'OUTPUT_MATERIAL').inputs['Surface'])
        mat.use_backface_culling = True
        mat.use_backface_culling_shadow = True
        mat.use_transparent_shadow = True
        for ob in self.coll.objects:
            if ob.type != 'MESH' or not ob.get('ink', 1.0):
                continue
            ob.data.materials.append(mat)
            mod = ob.modifiers.new('ink', 'SOLIDIFY')
            mod.thickness = width * ob['ink']
            mod.offset = 1.0
            mod.use_flip_normals = True
            mod.use_rim = False
            mod.material_offset = len(ob.data.materials) - 1

    def build(self):
        self.body()
        self.face()
        self.shell()
        self.hair()
        self.braid()
        self.hat()
        if self.v.get('outline'):
            self.outline(self.v['outline'])
        self.root.location = Vector(self.v['offset'])
        return {'objects': len(self.coll.objects),
                'tris': sum(sum(len(p.vertices) - 2 for p in o.data.polygons) for o in self.coll.objects if o.type == 'MESH')}


def build_all(names=None):
    return {n: Look(n, VARIANTS[n]).build() for n in (names or VARIANTS)}
