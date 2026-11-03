"""Offline preview of the multi-carry and held-item clips on the exported runtime data (no game, browser or compiler).

Rows: Reimu's mouth_release beat (open / release / closed, a young emerging from her mouth), Chen carrying two young on
her two curled tails (the CarryTailCurl cradle seats), and Marisa holding a vanilla stick in her braid on land (hold,
walking under the hold overlay, pick contact, place release). Reads motion_corrections.json and the fitted runtime
meshes; the young's path and the tail curl follow the code contract in the art report. Writes
.local/previews/art-a2-carry/{current.png,index.html}.
"""
from pathlib import Path
import json, math, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT/'scripts')); import placement_fit
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
MOTION = json.loads((ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))['models']
FITS = placement_fit.load()
SKIP = ('face_brow', 'face_blush', 'face_tear', 'face_effect', 'rounded_rump', 'mamu', 'peni', 'puff_')


def euler(r):
    x, y, z = np.radians(r); cx, sx, cy, sy, cz, sz = np.cos(x), np.sin(x), np.cos(y), np.sin(y), np.cos(z), np.sin(z)
    return np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])


def sample(keys, t):
    if t <= keys[0][0]: return np.array(keys[0][1:], float)
    for a, b in zip(keys, keys[1:]):
        if t <= b[0]:
            q = (t-a[0])/(b[0]-a[0]) if b[0] > a[0] else 0; return np.array(a[1:], float)*(1-q)+np.array(b[1:], float)*q
    return np.array(keys[-1][1:], float)


class Body:
    """A fitted runtime model posed by exported clips the way the game composes bones (idle at 0 under the clip)."""
    def __init__(self, ident):
        self.m = placement_fit.fitted(ASSETS/f'yukkuri/{ident}.json', FITS); self.id = ident
        rev = MOTION[ident]; names = {b['name']: i for i, b in enumerate(self.m['bones'])}
        self.clips = {k: [dict(t, bone=names[rev['bone_names'][t['bone']]]) for t in c['tracks'] if rev['bone_names'][t['bone']] in names]
                      for k, c in rev['clips'].items()}
        self.raw = rev['clips']; self.names = names
        self.V = np.array(self.m['vertices']).reshape(-1, 8)[:, :3]; self.W = np.array(self.m['weights'])

    def palette(self, layers):
        pose = {}
        for tracks, t in [(self.m['clips']['idle']['tracks'], 0)]+layers:
            for tr in tracks: pose.setdefault(tr['bone'], {})[tr['channel']] = sample(tr['keys'], t)
        out = []
        for i, b in enumerate(self.m['bones']):
            p = pose.get(i, {}); m = np.eye(4)
            m[:3, :3] = euler(p.get('rotation', [0, 0, 0]))*np.array(p.get('scale', [1, 1, 1]))
            pv = np.array(b['pivot']); m[:3, 3] = pv+np.array(p.get('position', [0, 0, 0]))-m[:3, :3]@pv
            out.append(out[b['parent']]@m if b['parent'] >= 0 and b['parent'] != i else m)
        return np.array(out)

    def posed(self, M, extra=None):
        h = np.c_[self.V, np.ones(len(self.V))]; a = self.W[:, 0].astype(int); b = self.W[:, 1].astype(int); w = self.W[:, 2:3]
        P = (np.einsum('nij,nj->ni', M[a], h)*(1-w)+np.einsum('nij,nj->ni', M[b], h)*w)[:, :3]
        if extra:
            for part, T in extra.items():
                p = self.part(part); P[p['start']:p['start']+p['count']] = (np.c_[P[p['start']:p['start']+p['count']], np.ones(p['count'])]@T.T)[:, :3]
        return P

    def part(self, name): return next(p for p in self.m['parts'] if p['name'] == name)


def expression(body, clip, t):
    """The face the exported clip's expression timeline shows at t (the texture the game swaps in)."""
    for a, b, face in (body.raw[clip]['expression_spans'] if clip else []):
        if a <= t < b: return face
    return 'neutral'


class Canvas:
    """Panels rendered with a depth buffer and the real runtime textures (orthographic, flat-shaded)."""
    def __init__(self, w, h):
        self.img = Image.new('RGB', (w, h), '#f6f4ec'); self.pen = ImageDraw.Draw(self.img)
        self.font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 19); self.small = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)
        self.textures = {}

    def texture(self, ident, face):
        key = (ident, face)
        if key not in self.textures:
            path = ASSETS/f'textures/entity/{ident}/{face}.png'
            self.textures[key] = np.asarray(Image.open(path if path.exists() else ASSETS/f'textures/entity/{ident}/neutral.png').convert('RGBA'))
        return self.textures[key]

    def scene(self, box, items, eye, scale, focus, sticks=()):
        """items: (body, posed points, face, size, offset): a size scales a body (a young) in the carrier's model units."""
        x0, y0, x1, y1 = box; w, h = x1-x0, y1-y0
        eye = np.array(eye, float); eye /= np.linalg.norm(eye); right = np.cross([0, 1, 0], eye); right /= np.linalg.norm(right); up = np.cross(eye, right)
        img = np.full((h, w, 3), (246, 244, 236), float); depth = np.full((h, w), -np.inf)
        to = lambda v: np.c_[w/2+(v-focus)@right*scale, h*.62-(v-focus)@up*scale, (v-focus)@eye]
        def raster(S, col, tex=None, uv=None):
            xa, ya = S[:, 0], S[:, 1]
            bx0, bx1 = max(int(xa.min()), 0), min(int(xa.max())+1, w-1); by0, by1 = max(int(ya.min()), 0), min(int(ya.max())+1, h-1)
            if bx0 > bx1 or by0 > by1: return
            area = (xa[1]-xa[0])*(ya[2]-ya[0])-(xa[2]-xa[0])*(ya[1]-ya[0])
            if abs(area) < 1e-9: return
            X, Y = np.meshgrid(np.arange(bx0, bx1+1)+.5, np.arange(by0, by1+1)+.5)
            w0 = ((xa[1]-X)*(ya[2]-Y)-(xa[2]-X)*(ya[1]-Y))/area; w1 = ((xa[2]-X)*(ya[0]-Y)-(xa[0]-X)*(ya[2]-Y))/area; w2 = 1-w0-w1
            m = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            z = w0*S[0, 2]+w1*S[1, 2]+w2*S[2, 2]; region = depth[by0:by1+1, bx0:bx1+1]; m &= z > region
            if tex is not None:
                u = w0*uv[0, 0]+w1*uv[1, 0]+w2*uv[2, 0]; v = w0*uv[0, 1]+w1*uv[1, 1]+w2*uv[2, 1]
                px = tex[np.clip((v*tex.shape[0]).astype(int), 0, tex.shape[0]-1), np.clip((u*tex.shape[1]).astype(int), 0, tex.shape[1]-1)]
                m &= px[..., 3] >= 128; c = px[..., :3]*col
            else: c = np.broadcast_to(np.array(col, float), X.shape+(3,))
            region[m] = z[m]; img[by0:by1+1, bx0:bx1+1][m] = c[m]
        g = np.array([[focus[0]+a, 0, focus[2]+b] for a, b in ((-60, -60), (60, -60), (60, 60), (-60, 60))], float)
        for tri in (g[[0, 1, 2]], g[[0, 2, 3]]): raster(to(tri)-[0, 0, 60], (221, 216, 198))
        for body, P, face, size, offset in items:
            tex = self.texture(body.id, face); UV = np.array(body.m['vertices']).reshape(-1, 8)[:, 3:5]
            for p in body.m['parts']:
                n = p['name']
                if p.get('anatomy') or n.startswith(SKIP): continue
                T = P[p['start']:p['start']+p['count']]*size+offset; U = UV[p['start']:p['start']+p['count']]
                for k in range(0, len(T)-2, 3):
                    tri = T[k:k+3]; nrm = np.cross(tri[1]-tri[0], tri[2]-tri[0]); L = np.linalg.norm(nrm)
                    S = to(tri)
                    if n.startswith('face_'): S[:, 2] += .08
                    raster(S, .72+.28*abs(nrm@eye)/L if L > 1e-9 else .8, tex, U[k:k+3])
        for G, d, top, blade, radius in sticks:
            a, b = G-d*top, G+d*blade; side = np.cross(d, eye); side = side/np.linalg.norm(side)*radius
            q = np.array([a-side, a+side, b+side, b-side])
            for tri in (q[[0, 1, 2]], q[[0, 2, 3]]): S = to(tri); S[:, 2] += .2; raster(S, (124, 86, 46))
        self.img.paste(Image.fromarray(img.astype(np.uint8)), (x0, y0))

    def text(self, xy, s, small=False, fill='#26343c'): self.pen.text(xy, s, font=self.small if small else self.font, fill=fill)


def mouth_front(body, M):
    p = body.part('face_mouth'); P = body.posed(M)[p['start']:p['start']+p['count']]
    return np.array([P[:, 0].mean(), P[:, 1].mean(), P[:, 2].min()]), np.ptp(P[:, 0])


def ease(q): q = min(1., max(0., q)); return q*q*(3-2*q)


def reimu_row(cv, y):
    reimu = Body('reimu'); clip = reimu.raw['mouth_release']; mk = clip['markers']; young = .2
    cv.text((30, y), f'1  灵梦张口放出（mouth_release {clip["duration"]} s：open {mk["open"]} → release {mk["release"]} → closed {mk["closed"]}）')
    H = np.ptp(reimu.V[:, 1]); W = 12.8
    for col, (t, label) in enumerate([(0.0, '含着：腮鼓，嘴闭'), (mk['open'], 'open：低头张大嘴'),
                                      (.8, '张着嘴，幼油从下唇滑出'), (mk['release'], 'release：落地松口'), (1.5, 'closed：抬头闭嘴')]):
        M = reimu.palette([(reimu.clips['mouth_release'], t)]); P = reimu.posed(M)
        lip, _ = mouth_front(reimu, M)
        # The young: inside behind the lips until open, out over the lower lip until release, on the ground in front after.
        inside = lip+[0, -young*H*.5, young*W*.6]; out = lip+[0, -young*H*.55, -young*W*.5]; ground = np.array([lip[0], 0, lip[2]-young*W*.7])
        q = ease((t-mk['open'])/(mk['release']-mk['open'])); feet = inside*(1-q)+out*q
        if t >= mk['release']: feet = ground
        cheeks = .5 if t < mk['release'] else 0
        swell = np.diag([1+.2*cheeks, 1+.14*cheeks, 1+.2*cheeks])
        P = (P-[0, 0, 1.24])@swell.T+[0, 0, 1.24]
        kid = Body('reimu'); kP = kid.posed(kid.palette([]))   # the young comes out facing the way she faces
        items = [(reimu, P, expression(reimu, 'mouth_release', t), 1, 0)]
        if t >= mk['open']: items.append((kid, kP, 'teary' if t < mk['release'] else 'neutral', young, feet))
        cv.scene((30+col*330, y+30, 350+col*330, y+320), items, [.6, .15, -.8], 22, np.array([0, 3, -6]))
        cv.text((40+col*330, y+325), f't={t:.2f}  {label}', small=True)


def tail_seats(chen, M, curl=1.):
    """CarryTailCurl (client) for the default Chen spec: per tail the segment transforms and the cradle seat (model units)."""
    yaw, pitch, shelf = 52, [58, 36, 4, -6, 70, 140, 210], 2
    ry = lambda a: np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])
    rx = lambda a: np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])
    extra, seats = {}, []
    for side, name in ((0, 'chen_tail_left'), (1, 'chen_tail_right')):
        sign = -1 if side == 0 else 1; bone = chen.names[name]; w = M[bone]; inv = np.linalg.inv(w)
        parts = sorted([p for p in chen.m['parts'] if p['name'].startswith(name) and p['name'][len(name):].isdigit()], key=lambda p: int(p['name'][len(name):]))
        cs = [chen.V[p['start']:p['start']+p['count']].mean(0) for p in parts]; tops = [chen.V[p['start']:p['start']+p['count'], 1].max() for p in parts]
        n = len(parts); joints = [np.array(chen.m['bones'][bone]['pivot'], float)]+[(cs[k-1]+cs[k])/2 for k in range(1, n)]; joints.append(2*cs[-1]-joints[-1])
        joint = joints[0].copy(); segs = []
        for k in range(n):
            fr = joints[k]; d = joints[k+1]-fr; rp = math.degrees(math.atan2(d[1], math.hypot(d[0], d[2])))
            R = ry(math.radians(sign*yaw*curl))@rx(math.radians(-(pitch[min(k, len(pitch)-1)]-rp)*curl))
            S = np.eye(4); S[:3, :3] = R; S[:3, 3] = joint-R@fr; segs.append(S); joint = (S@np.r_[joints[k+1], 1])[:3]
            extra[parts[k]['name']] = w@S@inv
        seat = np.mean([(segs[k]@np.r_[cs[k][0], tops[k], cs[k][2], 1])[:3] for k in range(shelf, shelf+2)], axis=0)+[0, .15, 0]
        seats.append(((w@np.r_[seat, 1])[:3], sign))
    return extra, seats


def chen_row(cv, y):
    chen = Body('chen'); young = .2
    cv.text((30, y), f'2  橙两条尾巴同时托两只（尾巴卷起的托座，每只赤油 {young:.2f} 倍体型）')
    M = chen.palette([]); extra, seats = tail_seats(chen, M); P = chen.posed(M, extra)
    for col, (eye, label) in enumerate([([.35, .5, 1], '后方'), ([.8, .45, .6], '后方斜看'), ([.05, 1, .35], '俯视')]):
        items = [(chen, P, 'happy', 1, 0)]
        for seat, sign in seats:
            kid = Body('chen'); kP = kid.posed(kid.palette([]))@euler([0, sign*-26, 0]).T
            items.append((kid, kP, 'happy', young, seat))
        cv.scene((30+col*440, y+30, 470+col*440, y+320), items, eye, 24, np.array([0, 6, 7]))
        cv.text((40+col*440, y+325), label, small=True)
    gap = np.linalg.norm((seats[0][0]-seats[1][0])[[0, 2]])
    cv.text((1360, y+60), f'两托座水平间距 {gap:.2f} 模型单位', small=True)
    cv.text((1360, y+85), f'赤油宽 {12.8*young:.2f}，互不相碰', small=True)
    cv.text((1360, y+110), '托座离身体表面足够远', small=True)


def marisa_row(cv, y):
    mar = Body('marisa'); hold = mar.raw['braid_item_hold']; pick = mar.raw['braid_item_pick']; place = mar.raw['braid_item_place']
    cv.text((30, y), '3  魔理沙在岸上用辫子攥着原版木棍（braid_item_hold / pick / place，与划桨同一握法）')
    tip = mar.names['hand_right_tip']; rest = np.array(mar.m['bones'][tip]['pivot'], float)
    def stick(M, clip, t):
        keys = clip['props']['item']['keys']
        if t > keys[-1][0]+1e-6:   # let go: the stick lies where the braid released it (a ground item)
            G, d, *_ = stick(mar.palette([(mar.clips['braid_item_place'], keys[-1][0])]), clip, keys[-1][0])[0]
            return [(np.array([G[0], .3, G[2]]), d*[1, 0, 1]/np.linalg.norm(d*[1, 0, 1]), 1.6, 10.4, .3)]
        if t < keys[0][0]-1e-6: return []
        k = sample(keys, t); A = M[tip]@np.r_[np.c_[np.eye(3), rest], [[0, 0, 0, 1]]]
        G = (A@np.r_[k[:3], 1])[:3]; R = A[:3, :3]@euler(k[3:6]); d = -R[:, 1]; d /= np.linalg.norm(d)
        return [(G, d, 1.6, 10.4, .3)]
    waddle = mar.clips['waddle']; braid = {mar.names[n] for n in mar.names if n.startswith('hand_right')}
    overlay = [tr for tr in mar.clips['braid_item_hold'] if tr['bone'] in braid]
    frames = [('站着攥住', [(mar.clips['braid_item_hold'], .4)], hold, .4),
              ('走路：走路身体 + 攥持辫子', [(waddle, .3), (overlay, .3)], hold, .3),
              (f'拾起 contact {pick["markers"]["contact"]}', [(mar.clips['braid_item_pick'], pick['markers']['contact'])], pick, pick['markers']['contact']),
              (f'放下：release {place["markers"]["release"]} 后木棍留在地上', [(mar.clips['braid_item_place'], 1.15)], place, 1.15)]
    for col, (label, layers, clip, t) in enumerate(frames):
        M = mar.palette(layers); P = mar.posed(M)
        face = expression(mar, 'braid_item_pick' if clip is pick else 'braid_item_place' if clip is place else None, t)
        cv.scene((30+col*410, y+30, 430+col*410, y+340), [(mar, P, face, 1, 0)], [.75, .3, -.6] if col < 2 else [.75, .2, .62] if col == 2 else [.9, .12, -.45], 11 if col < 2 else 14, np.array([5, 7, -3]) if col < 2 else np.array([10, 2.5, -6]), sticks=stick(M, clip, t))
        cv.text((40+col*410, y+345), label, small=True)


def main():
    cv = Canvas(1700, 1200)
    cv.text((30, 12), '多只运输、灵梦放出与辫子持物 — 导出数据的离线渲染（不是游戏截图）')
    reimu_row(cv, 50); chen_row(cv, 430); marisa_row(cv, 790)
    cv.text((30, 1170), '形体、贴图、表情与姿势取自运行模型和导出片段；赤油出口路线、腮鼓和尾巴卷曲按报告中的代码约定绘制。', small=True, fill='#4a5862')
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT/'.local/previews/art-a2-carry'; out.mkdir(parents=True, exist_ok=True)
    cv.img.save(out/'current.png')
    (out/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>多只运输、灵梦放出与辫子持物</title>'
                                  '<style>body{background:#f6f4ec;font-family:system-ui;margin:2rem}img{max-width:100%}</style>'
                                  '<h1>多只运输、灵梦放出与辫子持物</h1><p>导出数据的离线渲染，待代码接入与试玩。</p><img src="current.png">', encoding='utf-8')
    print('Preview:', out/'current.png')


if __name__ == '__main__':
    main()
