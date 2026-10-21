"""Offline preview of the aquatic Marisa hat against the land hat: worn on Marisa, afloat as a boat, sections and plan.

Reads the exported runtime props and metrics only (run build.py first) and the selected marisa.json with its
runtime placement fit. A small numpy z-buffer rasterizer with the Marisa atlas; no game, browser or Java.
Writes .local/previews/aquatic-hat/aquatic-hat.png and index.html (not committed).

  python preview.py
"""
from pathlib import Path
import json, math, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import hull as H
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
OUT = ROOT/'.local/previews/aquatic-hat'
ATLAS = np.array(Image.open(ASSETS/'textures/entity/marisa/neutral.png').convert('RGBA'))
BODY_SKIP = ('face_blush', 'face_tear', 'face_effect', 'mamu', 'peni', 'brim_', 'hat_', 'bow_')


def read(path): return json.loads(Path(path).read_text(encoding='utf-8'))


def font(size):
    for name in ('/System/Library/Fonts/STHeiti Medium.ttc', '/System/Library/Fonts/PingFang.ttc', '/Library/Fonts/Arial Unicode.ttf'):
        if Path(name).exists(): return ImageFont.truetype(name, size)
    return ImageFont.load_default()


def tris(vertices, parts=None, keep=lambda name: True):
    V = np.array(vertices, float).reshape(-1, 8)
    if parts is None: return V.reshape(-1, 3, 8)
    out = [V[p['start']:p['start']+p['count']] for p in parts if keep(p['name'])]
    return np.concatenate(out).reshape(-1, 3, 8) if out else np.zeros((0, 3, 8))


def marisa_body():
    """marisa.json at rest with the runtime placement fit applied to its parts (hair and the hat move together)."""
    model = read(ASSETS/'yukkuri/marisa.json'); fit = read(ASSETS/'yukkuri_presentation/placement_fit.json')['models']['marisa']
    V = np.array(model['vertices'], float).reshape(-1, 8); fitted = {}
    for p in fit['parts']:
        src = np.array(p['vertices'], float).reshape(-1, 5); idx = p['indices']
        rows = np.zeros((len(idx), 8)); rows[:, :5] = src[idx]; fitted[p['name']] = rows
    body, hat = [], []
    for p in model['parts']:
        rows = fitted.get(p['name'], V[p['start']:p['start']+p['count']])
        if p['name'].startswith(('brim_', 'hat_', 'bow_')): hat.append(rows)
        elif not p['name'].startswith(BODY_SKIP): body.append(rows)
    return np.concatenate(body).reshape(-1, 3, 8), np.concatenate(hat).reshape(-1, 3, 8)


class Camera:
    def __init__(self, yaw, pitch, centre, scale, size):
        self.size, self.scale, self.c = size, scale, np.array(centre, float)
        y, p = math.radians(yaw), math.radians(pitch)
        Ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
        Rx = np.array([[1, 0, 0], [0, math.cos(p), -math.sin(p)], [0, math.sin(p), math.cos(p)]])
        self.R = Rx@Ry

    def project(self, P):
        Q = (np.asarray(P)-self.c)@self.R.T
        return np.c_[self.size[0]/2+Q[:, 0]*self.scale, self.size[1]/2-Q[:, 1]*self.scale, -Q[:, 2]]

    def view_dir(self): return self.R.T@np.array([0, 0, -1.])


def render(cam, layers, water=None, background=(236, 240, 244)):
    """layers: (triangles n x 3 x 8 in world, tint or None). Front of the camera is -z. water: (y, mask polygon world xz)."""
    W, Hh = cam.size; img = np.zeros((Hh, W, 3)); img[:] = background; zb = np.full((Hh, W), np.inf)
    light = np.array([.35, .8, .45]); light /= np.linalg.norm(light); eye = -cam.view_dir()
    for T, tint in layers:
        if not len(T): continue
        S = cam.project(T[:, :, :3].reshape(-1, 3)).reshape(-1, 3, 3)
        for t, s in zip(T, S):
            x0, x1 = int(max(0, np.floor(s[:, 0].min()))), int(min(W-1, np.ceil(s[:, 0].max())))
            y0, y1 = int(max(0, np.floor(s[:, 1].min()))), int(min(Hh-1, np.ceil(s[:, 1].max())))
            if x0 > x1 or y0 > y1: continue
            a, b, c = s[0, :2], s[1, :2], s[2, :2]; den = (b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
            if abs(den) < 1e-9: continue
            xs, ys = np.meshgrid(np.arange(x0, x1+1)+.5, np.arange(y0, y1+1)+.5)
            w0 = ((b[1]-c[1])*(xs-c[0])+(c[0]-b[0])*(ys-c[1]))/den; w1 = ((c[1]-a[1])*(xs-c[0])+(a[0]-c[0])*(ys-c[1]))/den; w2 = 1-w0-w1
            m = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not m.any(): continue
            z = w0*s[0, 2]+w1*s[1, 2]+w2*s[2, 2]
            sub = zb[y0:y1+1, x0:x1+1]; m &= z < sub
            if not m.any(): continue
            n = np.cross(t[1, :3]-t[0, :3], t[2, :3]-t[0, :3]); ln = np.linalg.norm(n)
            n = n/ln if ln > 1e-12 else np.array([0, 1., 0])
            if np.dot(n, eye) < 0: n = -n
            shade = .5+.5*max(0., float(np.dot(n, light)))
            if tint is None:
                u = w0*t[0, 3]+w1*t[1, 3]+w2*t[2, 3]; v = w0*t[0, 4]+w1*t[1, 4]+w2*t[2, 4]
                px = ATLAS[np.clip((v*ATLAS.shape[0]).astype(int), 0, ATLAS.shape[0]-1), np.clip((u*ATLAS.shape[1]).astype(int), 0, ATLAS.shape[1]-1)]
                col = px[..., :3].astype(float); m &= px[..., 3] > 20
            else: col = np.broadcast_to(np.array(tint, float), z.shape+(3,))
            region = img[y0:y1+1, x0:x1+1]; region[m] = col[m]*shade; sub[m] = z[m]
    if water is not None:
        level, mask = water; ys, xs = np.mgrid[0:Hh, 0:W]+.5
        # ray through each pixel against the plane y = level (orthographic camera)
        Rt = cam.R.T; d = Rt@np.array([0, 0, -1.])
        p0 = (np.stack([(xs-W/2)/cam.scale, -(ys-Hh/2)/cam.scale, np.zeros_like(xs)], -1))@Rt.T+cam.c
        t = (level-p0[..., 1])/d[1]; hit = p0+t[..., None]*d; depth = -((hit-cam.c)@cam.R.T)
        under = zb > depth[..., 2]
        dry = np.zeros_like(under)
        if mask is not None:
            poly = np.asarray(mask); a = poly; b = np.roll(poly, -1, 0); inside = np.ones_like(under)
            for (ax, az), (bx, bz) in zip(a, b): inside &= (bx-ax)*(hit[..., 2]-az)-(bz-az)*(hit[..., 0]-ax) >= 0
            dry = inside
        blend = under & ~dry
        img[blend] = img[blend]*.45+np.array([70, 130, 190])*.55
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def hat_worn(prop, anchor, k, lift):
    """A worn-frame prop drawn as the model's hat parts are: placement fit offset, then scaled about anchor_worn."""
    T = tris(prop['vertices']).copy(); a = np.array(anchor)
    T[:, :, :3] = a+k*(T[:, :, :3]+np.array(lift)-a); return T


def hat_afloat(prop, k, sq, fb, at=(0, 0, 0)):
    T = tris(prop['vertices']).copy(); T[:, :, :3] *= [k, k*sq, k]; T[:, :, 1] += fb*k; T[:, :, :3] += at; return T


def section(T, axis, value):
    """Segments where the plane coordinate[axis] = value cuts the triangles (2D: the other horizontal axis, y)."""
    other = 2 if axis == 0 else 0; segs = []
    for t in T[:, :, :3]:
        d = t[:, axis]-value; pts = []
        for i in range(3):
            j = (i+1) % 3
            if (d[i] < 0) != (d[j] < 0) and abs(d[i]-d[j]) > 1e-12:
                q = t[i]+(t[j]-t[i])*(d[i]/(d[i]-d[j])); pts.append((q[other], q[1]))
        if len(pts) == 2: segs.append(pts)
    return segs


def draw_section(draw, box, items, title, F, water=None, scale=9.):
    x0, y0, x1, y1 = box; cx = (x0+x1)/2; cy = y0+110
    draw.rectangle(box, outline=(180, 180, 180)); draw.text((x0+8, y0+6), title, fill=(30, 30, 30), font=F)
    to = lambda p: (cx+p[0]*scale, cy-p[1]*scale)
    if water is not None:
        wy = to((0, water))[1]; draw.rectangle((x0+1, wy, x1-1, y1-1), fill=(214, 230, 244))
        draw.line((x0+1, wy, x1-1, wy), fill=(70, 130, 190), width=2)
    for segs, colour, width in items:
        for a, b in segs: draw.line((*to(a), *to(b)), fill=colour, width=width)
    return to


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    land = read(ASSETS/'yukkuri_presentation/hat_vessel.json'); aq = read(ASSETS/'yukkuri_presentation/marisa_aquatic_hat_vessel.json')
    old_turned = read(ASSETS/'yukkuri_props/marisa_hat_turned.json'); new_turned = read(ASSETS/'yukkuri_props/marisa_aquatic_hat_turned.json')
    old_worn = read(ASSETS/'yukkuri_props/accessory_marisa.json'); new_worn = read(ASSETS/'yukkuri_props/accessory_marisa_aquatic.json')
    layout = read(ASSETS/'yukkuri_presentation/marisa_aquatic_hat_cargo_layout.json'); provisions = read(ASSETS/'yukkuri_props/marisa_aquatic_hat_provisions.json')
    k = aq['aquatic_scale']; sq = aq['squash']['bowl']; fb = aq['bowl']['freeboard']; anchor = aq['anchor_worn']; lift = aq['worn']['placement_fit_offset']
    body, _ = marisa_body()
    F, Fs = font(22), font(16)
    panels = []
    # 1-2: worn on the head (old aquatic = land hat x1.5; new), front three-quarter and side
    for yaw, pitch, label in ((145, 12, '正面 3/4'), (-90, 6, '侧面（右为正面）')):
        row = []
        for name, prop in (('现用：普通帽 ×1.5', old_worn), ('新：水生帽 ×1.5', new_worn)):
            cam = Camera(yaw, pitch, (0, 13, 0), 13, (480, 520))
            img = render(cam, [(body, None), (hat_worn(prop, anchor, k, lift), None)])
            ImageDraw.Draw(img).text((10, 8), f'佩戴 {label} · {name}', fill=(20, 20, 20), font=Fs); row.append(img)
        panels.append(row)
    # 2b: the bow close up, front three-quarter (the land bow's own boxes, wrapped onto the round band)
    row = []
    for name, prop in (('现用：普通帽蝴蝶结', old_worn), ('新：水生帽蝴蝶结（同一组方块包到圆帽带上）', new_worn)):
        cam = Camera(150, 8, (0, 12.6, -7.5), 30, (480, 520))
        img = render(cam, [(body, None), (hat_worn(prop, anchor, k, lift), None)]); draw = ImageDraw.Draw(img)
        draw.rectangle((0, 0, 479, 32), fill=(236, 240, 244)); draw.text((10, 8), f'近看 · {name}', fill=(20, 20, 20), font=Fs); row.append(img)
    panels.append(row)
    # 3: afloat, three-quarter from above, old and new boats at the same scale
    row = []
    for name, prop, V in (('现用帽船', old_turned, land), ('新水生帽船', new_turned, aq)):
        cam = Camera(-30, 38, (0, -2, 0), 15.5, (480, 520))
        layers = [(hat_afloat(prop, k, sq, fb), None)]
        if V is aq:  # full provisions, as the owner sees them below her
            P = tris(provisions['vertices'], provisions['parts'], lambda n: n.startswith('inside_tier3')).copy()
            P[:, :, :3] *= [k, k*sq, k]; P[:, :, 1] += fb*k; layers.append((P, (196, 160, 90)))
        mask = np.array(V['bowl'].get('mask_outline', V['bowl']['mask']))*k
        img = render(cam, layers, water=(0, mask))
        ImageDraw.Draw(img).text((10, 8), f'水上 · {name}' + (' (满载粮堆)' if V is aq else ''), fill=(20, 20, 20), font=Fs); row.append(img)
    panels.append(row)
    # 4: sections afloat (hat units x scale), long axis and across; owner seat and water line
    sec = Image.new('RGB', (960, 520), (255, 255, 255)); d = ImageDraw.Draw(sec)
    for i, (axis, title) in enumerate(((0, '纵剖 x=0（左船头 -z，右船尾 +z）'), (2, '横剖 z=0（右为蝴蝶结一侧 +x）'))):
        box = (10+i*475, 10, 475+i*475, 510)
        items = []
        for prop, colour, w in ((old_turned, (170, 120, 120), 2), (new_turned, (30, 30, 50), 3)):
            T = hat_afloat(prop, 1, sq, fb); items.append((section(T, axis, 0.), colour, w))
        to = draw_section(d, box, items, title, Fs, water=0., scale=13. if axis == 2 else 10.)
        for V, colour in ((land, (190, 70, 70)), (aq, (40, 90, 200))):
            oy = V['bowl']['owner_y']; d.line((*to((-6, oy)), *to((6, oy))), fill=colour, width=1)
            fy = fb-V['floor']*sq; d.line((*to((-2.5, fy)), *to((2.5, fy))), fill=colour, width=1)
        d.text((box[0]+8, box[3]-48), '灰红=现用  深色=新  蓝=水面；细线：船主座高 / 最深底', fill=(60, 60, 60), font=Fs)
        d.text((box[0]+8, box[3]-26), f"最深底 现用 {land['floor']:.2f} → 新 {aq['floor']:.2f}（未压扁帽单位）", fill=(60, 60, 60), font=Fs)
    # 5: plan of the new hat from above, with the server polygons, grips and cargo
    plan = Image.new('RGB', (960, 520), (255, 255, 255)); d = ImageDraw.Draw(plan)
    for i, (V, title) in enumerate(((land, '俯视·现用（矩形段）'), (aq, '俯视·新（多边形段）'))):
        cx, cy, s = 240+i*480, 270, 21.
        to = lambda x, z: (cx+z*s, cy+x*s)
        d.text((cx-220, 12), title, fill=(20, 20, 20), font=Fs)
        if 'brim_outline' in V: d.polygon([to(x, z) for x, z in V['brim_outline']], outline=(60, 60, 60), fill=(225, 225, 232))
        else:
            for x0, x1, z0, z1 in V['brim_rects']: d.rectangle((*to(x0, z0), *to(x1, z1)), fill=(225, 225, 232), outline=(60, 60, 60))
        for j, sct in enumerate(V['cavity_sections']):
            shade = int(205-150*j/max(1, len(V['cavity_sections'])-1)); col = (shade, shade, shade+15)
            if 'outline' in sct: d.polygon([to(x, z) for x, z in sct['outline']], fill=col, outline=(30, 30, 30))
            else: x0, x1, z0, z1 = sct['rect']; d.rectangle((*to(x0, z0), *to(x1, z1)), fill=col, outline=(30, 30, 30))
        ox = V['placements']['aquatic']
        for role, colour in (('helper', (220, 60, 60)), ('self', (40, 150, 60))):
            g = np.array(ox[role]['grip_worn']); a = np.array(V['anchor_worn']); v = (-(g[2]-a[2]), -(g[0]-a[0]))
            d.ellipse((*[c-5 for c in to(*v)], *[c+5 for c in to(*v)]), fill=colour)
        if V is aq:
            for tier in (layout['tiers'][3], layout['aside_tiers'][3]):
                for z in tier:
                    r = z['radius']; d.ellipse((*to(z['x']-r, z['z']-r), *to(z['x']+r, z['z']+r)), outline=(196, 140, 40), width=2)
        d.text((cx-220, 470), '左=船头 下=蝴蝶结侧；点=辫子抓点', fill=(60, 60, 60), font=Fs)
        if V is aq: d.text((cx-220, 492), '黄圈=满载粮堆占位（帽内/侧檐）', fill=(60, 60, 60), font=Fs)
    W = 960; rows = [Image.new('RGB', (W, 520), (255, 255, 255)) for _ in panels]
    for r, pair in zip(rows, panels):
        for i, img in enumerate(pair): r.paste(img, (i*480, 0))
    sheet = Image.new('RGB', (W, 520*(len(rows)+2)+60), (255, 255, 255)); dd = ImageDraw.Draw(sheet)
    dd.text((12, 14), '水生魔理沙专用帽（marisa-aquatic-hat，待用户确认）· 离线预览，未启动游戏', fill=(10, 10, 10), font=F)
    for i, img in enumerate(rows+[sec, plan]): sheet.paste(img, (0, 60+i*520))
    sheet.save(OUT/'aquatic-hat.png')
    (OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>水生魔理沙帽</title>'
                                  '<body style="margin:0;background:#eee"><img src="aquatic-hat.png" style="max-width:100%"></body>\n', encoding='utf-8')
    print('preview', OUT/'aquatic-hat.png')


if __name__ == '__main__':
    main()
