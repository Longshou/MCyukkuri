"""Aquatic Marisa hull extras: the 3D paddle stick and the waterline wet band.

paddle_stick: the plain stick a yukkuri picks up (nothing carved or tied; yukkuri make no tools), as a small square
rod in the stick's brown pixel look, so it can be held at the braid tuft and stowed on the brim in 3D. Its origin is
the grip (`socket`), where the tuft wraps it; +Y runs to the short top end, -Y to the long end that goes in the water.
`anchors.stow_hull` places that socket on the left (non-braid) brim of `marisa_hat_turned` (hull prop units, rotation Rz*Ry*Rx in
degrees, `scale` relative to the hull's own scale).

marisa_hat_wetline: the outer hull surface of `marisa_hat_turned` between just under the floating waterline and the
brim, lifted a hair off the cloth and drawn translucent: darkest at the waterline, fading upward. It is derived from the
hull prop and hat_vessel.json (freeboard, afloat squash), so it follows any hull rebuild after re-running this script.

    python standards/yukkuri-library/interactions/aquatic-tools/build.py [--check]
"""
from pathlib import Path
import argparse, hashlib, io, json, math, random
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
PROPS = ASSETS/'yukkuri_props'
TEXTURES = ASSETS/'textures/props'
VESSEL = ASSETS/'yukkuri_presentation/hat_vessel.json'
HULL = PROPS/'marisa_hat_turned.json'
INDEX = PROPS/'subspecies-index.json'
RECIPE = HERE/'recipe.json'


def png(image):
    data = io.BytesIO(); image.save(data, format='PNG', optimize=False); return data.getvalue()


def stick_texture(cfg):
    """16 x 32: four 4-pixel side columns (rows run along the stick) and the two cut ends below."""
    rng = random.Random(cfg['seed']); img = Image.new('RGBA', (16, 32)); px = img.load()
    base, dark, light, end, ring = (tuple(cfg['colors'][k]) for k in ('bark', 'shadow', 'highlight', 'cut', 'ring'))
    shade = [1.0, .82, .7, .9]  # lit, side, under, side
    knots = {rng.randrange(3, 22) for _ in range(3)}
    for face in range(4):
        for y in range(24):
            for x in range(4):
                c = base
                if x == 0: c = dark
                elif x == 3 and face == 0: c = light
                elif rng.random() < .18: c = dark if rng.random() < .5 else light
                if y in knots and x in (1, 2): c = dark
                k = shade[face]; px[face*4+x, y] = tuple(round(v*k) for v in c)+(255,)
    for y in range(24, 32):
        for x in range(16):
            cx, cy = (x % 8)-3.5, (y-24)-3.5; r = math.hypot(cx, cy)
            px[x, y] = (ring if r > 2.6 or 1.1 < r < 1.6 else end)+(255,)
    return img


def box_faces(a, b, half, u0):
    """Quads of a square rod segment from centre a to centre b (y up), u0 the texture row range."""
    out = []
    corners = [(-1, -1), (1, -1), (1, 1), (-1, 1)]  # x, z
    normals = [(0, -1), (1, 0), (0, 1), (-1, 0)]
    for f in range(4):
        (x0, z0), (x1, z1) = corners[f], corners[(f+1) % 4]
        nx, nz = normals[f]
        p = [np.array([a[0]+x0*half[0], a[1], a[2]+z0*half[0]]), np.array([a[0]+x1*half[0], a[1], a[2]+z1*half[0]]),
             np.array([b[0]+x1*half[1], b[1], b[2]+z1*half[1]]), np.array([b[0]+x0*half[1], b[1], b[2]+z0*half[1]])]
        uv = [(f*4/16, u0[0]), ((f*4+4)/16, u0[0]), ((f*4+4)/16, u0[1]), (f*4/16, u0[1])]
        out.append((p, uv, (nx, 0, nz)))
    return out


def tris(quads):
    v = []
    for p, uv, n in quads:
        for i in (0, 1, 2, 0, 2, 3):
            v.extend([round(float(c), 5) for c in p[i]]+[uv[i][0], uv[i][1]]+list(n))
    return v


def orient(v):
    """Wind every triangle so its geometric normal agrees with the stored normal."""
    for k in range(0, len(v), 24):
        a, b, c = (np.array(v[k+j*8:k+j*8+3]) for j in range(3))
        if np.cross(b-a, c-a)@np.array(v[k+5:k+8]) < 0: v[k+8:k+16], v[k+16:k+24] = v[k+16:k+24], v[k+8:k+16]
    return v


def stick(cfg):
    s = cfg['stick']; top, length = s['top'], s['length']; ys = np.linspace(top, top-length, s['segments']+1)
    rng = random.Random(s['seed']); bend = s['bend']
    centres = [np.array([bend*math.sin(math.pi*i/s['segments'])+rng.uniform(-s['wobble'], s['wobble']), y, rng.uniform(-s['wobble'], s['wobble'])])
               for i, y in enumerate(ys)]
    centres[0][[0, 2]] = 0  # the grip axis stays straight through the socket
    half = s['thickness']/2; quads = []
    for i in range(s['segments']):
        a, b = centres[i], centres[i+1]
        h0 = half*(1-s['taper']*i/s['segments']); h1 = half*(1-s['taper']*(i+1)/s['segments'])
        quads += box_faces(a, b, (h0, h1), (i/s['segments']*24/32, (i+1)/s['segments']*24/32))
    for end, sign, u in ((centres[0], 1, 0), (centres[-1], -1, .5)):
        h = half if sign > 0 else half*(1-s['taper'])
        p = [end+[-h, 0, -h], end+[h, 0, -h], end+[h, 0, h], end+[-h, 0, h]]
        quads.append((p, [(u, 24/32), (u+.5, 24/32), (u+.5, 1), (u, 1)], (0, sign, 0)))
    v = orient(tris(quads)); P = np.array(v).reshape(-1, 8)[:, :3]
    anchor = cfg['stow_hull']; mid = (top+(top-length))/2
    sc = round(1/json.loads(VESSEL.read_text(encoding='utf-8'))['aquatic_scale'], 4)  # the braid's stick, undoing the hull's size
    # Place the socket so the stick's middle sits at the anchor centre along the hull's Z.
    centre = np.array(anchor['centre'], float); yaw = math.radians(anchor['yaw'])
    axis = np.array([math.sin(yaw), 0, math.cos(yaw)])  # stick +Y after Rx(90) then Ry(yaw)
    socket_at = centre-axis*mid*sc
    return {'format': 1, 'units_per_block': 16, 'id': 'paddle_stick',
            'note': 'Plain picked-up stick held by the braid tuft (socket = grip). See standards/yukkuri-library/interactions/aquatic-tools.',
            'texture_size': [16, 32], 'vertices': v,
            'parts': [{'name': 'stick', 'start': 0, 'count': len(v)//8, 'texture': 'mcyukkuri:textures/props/paddle_stick.png'}],
            'bounds': [[round(float(P[:, i].min()), 4), round(float(P[:, i].max()), 4)] for i in range(3)],
            'socket': [0, 0, 0],
            'anchors': {'stow_hull': {'position': [round(float(x), 4) for x in socket_at], 'rotation': [90, anchor['yaw'], 0], 'scale': sc}}}


def clip_polygon(poly, y, keep_above):
    out = []
    for i, cur in enumerate(poly):
        nxt = poly[(i+1) % len(poly)]
        a_in = (cur[1] >= y) == keep_above; b_in = (nxt[1] >= y) == keep_above
        if a_in: out.append(cur)
        if a_in != b_in:
            q = (y-cur[1])/(nxt[1]-cur[1]); out.append(cur+(nxt-cur)*q)
    return out


def wetline(cfg):
    w = cfg['wetline']
    hull = json.loads(HULL.read_text(encoding='utf-8')); raw = np.array(hull['vertices']).reshape(-1, 8)
    low, high, water = wet_band_rows(cfg)  # prop y; the waterline afloat
    out = []
    for part in hull['parts']:
        if part['name'].endswith('_lining'): continue  # the dry inside of the hat
        for k in range(part['start'], part['start']+part['count'], 3):
            tri = raw[k:k+3]; n = tri[0, 5:8]
            if n[1] > .85 or (tri[:, 1].max() < low or tri[:, 1].min() > high): continue  # skip the brim's upper face
            poly = clip_polygon(clip_polygon([t[:3].copy() for t in tri], low, True), high, False)
            if len(poly) < 3: continue
            off = n/np.linalg.norm(n)*w['lift']
            for i in range(1, len(poly)-1):
                for p in (poly[0], poly[i], poly[i+1]):
                    q = p+off; v = (q[1]-low)/(high-low)
                    out.extend([round(float(c), 5) for c in q]+[.5, round(1-float(v), 5)]+[round(float(c), 5) for c in n])
    v = orient(out); P = np.array(v).reshape(-1, 8)[:, :3]
    return {'format': 1, 'units_per_block': 16, 'id': 'marisa_hat_wetline',
            'note': 'Translucent wet band on the outer hull from just below the afloat waterline toward the brim; drawn with marisa_hat_turned in the same transform.',
            'source_prop_sha256': hashlib.sha256(HULL.read_bytes()).hexdigest(), 'waterline_y': round(water, 4),
            'texture_size': [4, 32], 'vertices': v,
            'parts': [{'name': 'wet_band', 'start': 0, 'count': len(v)//8, 'texture': 'mcyukkuri:textures/props/marisa_hat_wetline.png', 'translucent': True}],
            'bounds': [[round(float(P[:, i].min()), 4), round(float(P[:, i].max()), 4)] for i in range(3)]}


def wet_band_rows(cfg):
    """Band height in prop units: (low, high, waterline) as wetline() lays it."""
    w = cfg['wetline']; V = json.loads(VESSEL.read_text(encoding='utf-8'))
    sq = V['squash']['bowl']; water = -V['bowl']['freeboard']/sq
    return water-w['below']/sq, min(-w['brim_gap'], water+w['above']/sq), water


def wet_texture(cfg):
    """4 x 32, v = 0 at the top of the band: the sheen fading up from the water, a bright thread at the waterline."""
    w = cfg['wetline']; img = Image.new('RGBA', (4, 32)); px = img.load(); low, high, water = wet_band_rows(cfg)
    line = round((1-(water-low)/(high-low))*31)-w.get('line_lift', 0)  # the thread sits just above the water surface
    for y in range(32):
        q = min(1., (y/31)/max(1e-6, line/31))  # 0 at the top, 1 at the waterline and below
        c, a = tuple(w['sheen']), w['alpha_low']*q**w['falloff']
        if line-w['line_rows']//2 <= y < line-w['line_rows']//2+w['line_rows']: c, a = tuple(w['line']), w['line_alpha']
        for x in range(4): px[x, y] = c+(round(255*a),)
    return img


def outputs():
    cfg = json.loads(RECIPE.read_text(encoding='utf-8'))
    files = {PROPS/'paddle_stick.json': (json.dumps(stick(cfg), separators=(',', ':'))+'\n').encode(),
             TEXTURES/'paddle_stick.png': png(stick_texture(cfg['stick'])),
             PROPS/'marisa_hat_wetline.json': (json.dumps(wetline(cfg), separators=(',', ':'))+'\n').encode(),
             TEXTURES/'marisa_hat_wetline.png': png(wet_texture(cfg))}
    index = json.loads(INDEX.read_text(encoding='utf-8'))
    for name in ('paddle_stick', 'marisa_hat_wetline'): index.setdefault(name, {})
    files[INDEX] = (json.dumps(index, separators=(',', ':'))).encode()
    manifest = {'recipe': RECIPE.relative_to(ROOT).as_posix(), 'recipe_sha256': hashlib.sha256(RECIPE.read_bytes()).hexdigest(),
                'builder_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'inputs': {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in (HULL, VESSEL)},
                'outputs': {p.relative_to(ROOT).as_posix(): hashlib.sha256(d).hexdigest() for p, d in files.items() if p != INDEX}}
    files[HERE/'manifest.json'] = (json.dumps(manifest, indent=2)+'\n').encode()
    return files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    for path, data in outputs().items():
        if args.check: assert path.is_file() and path.read_bytes() == data, 'Outdated: '+str(path)
        else: path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    print('paddle_stick and marisa_hat_wetline '+('verified' if args.check else 'written'))
