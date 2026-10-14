"""Offline previews of the world-setting clips on the game models; writes GIFs, a sheet and a page.

Poses src/main/resources/assets/mcyukkuri/yukkuri/<id>.json with its two-bone weights, the
idle clip at t=0 under the clip from the motion corrections (as the game layers them),
and draws flat-shaded, textured triangles with a numpy depth buffer. Each clip uses the
expression texture the game shows during its behavior. No game, browser or build runs;
this is a visual aid, not motion acceptance. Needs numpy and Pillow.

  python preview.py [--corrections motion_corrections.json] [--out dir] [--models a,b] [--clips a,b]
"""
from pathlib import Path
import argparse, json, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
OUT = ROOT/'art/source/motion-corrections/previews/current/lore'
MODELS = ['marisa', 'reimu', 'house_marisa', 'marisa_bean', 'remilia', 'marisa_tsumuri']
FACE = {'eat': 'chewing', 'shiawase': 'satisfied', 'drink': 'chewing_closed', 'sleep': 'sleep', 'wake': 'yawn',
        'nuzzle': 'closed_smile', 'sing': 'open_smile', 'sway': 'closed_smile', 'push': 'angry', 'greet': 'happy',
        'tremble': 'surprise', 'droop': 'teary', 'look_around': 'neutral', 'shake': 'happy', 'boat_balance': 'neutral'}
BACK = (234, 231, 222)


def euler(r):
    x, y, z = np.radians(r)
    cx, sx, cy, sy, cz, sz = np.cos(x), np.sin(x), np.cos(y), np.sin(y), np.cos(z), np.sin(z)
    return np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])


def sample(keys, t):
    if t <= keys[0][0]: return keys[0][1:]
    for a, b in zip(keys, keys[1:]):
        if t <= b[0]:
            q = (t-a[0])/(b[0]-a[0]) if b[0] > a[0] else 0
            return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]
    return keys[-1][1:]


class Posed:
    def __init__(self, ident, corrections):
        self.model = m = json.loads((ASSETS/'yukkuri'/f'{ident}.json').read_text(encoding='utf-8'))
        rev = corrections['models'].get(ident, {}); index = {b['name']: i for i, b in enumerate(m['bones'])}
        remap = lambda clip: dict(clip, tracks=[dict(tr, bone=index[rev['bone_names'][tr['bone']]]) for tr in clip['tracks']])
        self.clips = {**m['clips'], **m.get('behavior_clips', {}), **{k: remap(v) for k, v in rev.get('clips', {}).items()}}
        V = np.array(m['vertices']).reshape(-1, 8); W = np.array(m['weights'])
        keep = [i for p in m['parts'] if not p.get('anatomy') for i in range(p['start'], p['start']+p['count'])]
        self.v, self.uv, self.n = V[keep, :3], V[keep, 3:5], V[keep, 5:8]
        self.a, self.b, self.q = W[keep, 0].astype(int), W[keep, 1].astype(int), W[keep, 2:3]
        self.ident = ident

    def frame(self, name, t):
        pose = {}
        for clip, at in ((self.clips['idle'], 0), (self.clips[name], t)):
            for tr in clip['tracks']:
                pose.setdefault(tr['bone'], {})[tr['channel']] = sample(tr['keys'], at)
        mats = []
        for i, b in enumerate(self.model['bones']):
            p = pose.get(i, {}); m = np.eye(4)
            m[:3, :3] = euler(p.get('rotation', [0, 0, 0]))*np.array(p.get('scale', [1, 1, 1]))
            pv = np.array(b['pivot']); m[:3, 3] = pv+np.array(p.get('position', [0, 0, 0]))-m[:3, :3]@pv
            mats.append(mats[b['parent']]@m if b['parent'] >= 0 else m)
        M = np.array(mats); hv = np.c_[self.v, np.ones(len(self.v))]
        pos = np.einsum('nij,nj->ni', M[self.a], hv)[:, :3]*(1-self.q)+np.einsum('nij,nj->ni', M[self.b], hv)[:, :3]*self.q
        nrm = np.einsum('nij,nj->ni', M[self.a][:, :3, :3], self.n)
        return pos.reshape(-1, 3, 3), self.uv.reshape(-1, 3, 2), nrm.reshape(-1, 3, 3)[:, 0]


def raster(pos, uv, nrm, tex, size, yaw, pitch, scale, center_y, K=8):
    W, H = size; img = np.full((H, W, 3), BACK, np.uint8)
    cy, sy, cp, sp = math.cos(math.radians(yaw)), math.sin(math.radians(yaw)), math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    def view(p):
        x, y, z = p[..., 0], p[..., 1], p[..., 2]
        x, z = x*cy+z*sy, -x*sy+z*cy
        return np.stack([x, y*cp-z*sp, y*sp+z*cp], -1)
    P = view(pos-np.array([0, center_y, 0])); N = view(nrm)
    keep = N[:, 2] <= 1e-6
    sx = W/2+P[..., 0]*scale; sy_ = H/2-P[..., 1]*scale
    d = (sx[:, 1]-sx[:, 0])*(sy_[:, 2]-sy_[:, 0])-(sx[:, 2]-sx[:, 0])*(sy_[:, 1]-sy_[:, 0])
    keep &= np.abs(d) > 1e-9
    light = np.array([-.35, .55, -.75]); light /= np.linalg.norm(light)
    nn = nrm/(np.linalg.norm(nrm, axis=1, keepdims=True)+1e-9); shade = .72+.28*np.maximum(0, nn@light)
    x0 = np.floor(sx.min(1)).astype(int); y0 = np.floor(sy_.min(1)).astype(int)
    x1 = np.ceil(sx.max(1)).astype(int); y1 = np.ceil(sy_.max(1)).astype(int)
    keep &= (x1 >= 0) & (y1 >= 0) & (x0 < W) & (y0 < H)
    th, tw = tex.shape[:2]; cand = []
    def emit(ids, k):
        o = np.arange(k); ox, oy = np.meshgrid(o, o); ox, oy = ox.ravel(), oy.ravel()
        px = x0[ids, None]+ox; py = y0[ids, None]+oy; qx, qy = px+.5, py+.5
        X, Y, D = sx[ids], sy_[ids], d[ids, None]
        a = ((X[:, 1:2]-qx)*(Y[:, 2:3]-qy)-(X[:, 2:3]-qx)*(Y[:, 1:2]-qy))/D
        b = ((X[:, 2:3]-qx)*(Y[:, 0:1]-qy)-(X[:, 0:1]-qx)*(Y[:, 2:3]-qy))/D; c = 1-a-b
        ok = (np.minimum(np.minimum(a, b), c) >= -1e-6) & (px >= 0) & (py >= 0) & (px < W) & (py < H)
        z = a*P[ids, 0, 2, None]+b*P[ids, 1, 2, None]+c*P[ids, 2, 2, None]
        u = a*uv[ids, 0, 0, None]+b*uv[ids, 1, 0, None]+c*uv[ids, 2, 0, None]
        v = a*uv[ids, 0, 1, None]+b*uv[ids, 1, 1, None]+c*uv[ids, 2, 1, None]
        texel = tex[np.clip((v*th).astype(int), 0, th-1), np.clip((u*tw).astype(int), 0, tw-1)]
        ok &= texel[..., 3] >= 128
        rgb = texel[..., :3]*shade[ids, None, None]
        cand.append((py[ok]*W+px[ok], z[ok], rgb[ok]))
    span = np.maximum(x1-x0, y1-y0)+1
    small = np.nonzero(keep & (span <= K))[0]
    for i in range(0, len(small), 20000): emit(small[i:i+20000], K)
    for i in np.nonzero(keep & (span > K))[0]: emit(np.array([i]), int(span[i]))
    idx = np.concatenate([c[0] for c in cand]); z = np.concatenate([c[1] for c in cand]); rgb = np.concatenate([c[2] for c in cand])
    order = np.lexsort((z, idx)); first = np.unique(idx[order], return_index=True)[1]
    sel = order[first]; flat = img.reshape(-1, 3); flat[idx[sel]] = rgb[sel].astype(np.uint8)
    g = int(H/2+center_y*scale)
    if 0 <= g < H:
        row = img[g]; row[(row == BACK).all(1)] = (190, 182, 165)
    return img


def texture(ident, expression):
    folder = ASSETS/'textures/entity'/ident
    path = folder/f'{expression}.png'
    return np.array(Image.open(path if path.exists() else folder/'neutral.png').convert('RGBA'))


def view_for(posed):
    pos = posed.frame('idle', 0)[0].reshape(-1, 3); h = pos[:, 1].max(); w = np.abs(pos[:, [0, 2]]).max()
    return dict(scale=min(150/(h*1.55), 150/(w*2.4)), center_y=h*.45)


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--corrections', default=str(ASSETS/'yukkuri_presentation/motion_corrections.json'))
    ap.add_argument('--out', default=str(OUT)); ap.add_argument('--models', default=','.join(MODELS)); ap.add_argument('--clips')
    ap.add_argument('--fps', type=float, default=10)
    args = ap.parse_args()
    recipe = json.loads((HERE/'recipe.json').read_text(encoding='utf-8')); corrections = json.loads(Path(args.corrections).read_text(encoding='utf-8'))
    clips = args.clips.split(',') if args.clips else list(recipe['clips'])
    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    posed = [Posed(m, corrections) for m in args.models.split(',')]
    views = [view_for(p) for p in posed]
    size = (190, 200); font = ImageFont.load_default()
    for name in clips:
        c = recipe['clips'][name]; n = max(2, round(c['duration']*args.fps)); frames = []
        textures = [texture(p.ident, FACE[name]) for p in posed]
        for k in range(n+(0 if c['loop'] else 1)):
            t = c['duration']*k/n; row = []
            for p, v, tex in zip(posed, views, textures):
                pos, uv, nrm = p.frame(name, t)
                row.append(raster(pos, uv, nrm, tex, size, 28, -12, v['scale'], v['center_y']))
            img = Image.fromarray(np.concatenate(row, 1)); ImageDraw.Draw(img).text((6, 4), f"{name}  {t:.1f}s", fill=(60, 55, 50), font=font)
            frames.append(img)
        if not c['loop']: frames += [frames[-1]]*int(args.fps*.6)  # hold the end pose before restarting
        frames[0].save(out/f'{name}.gif', save_all=True, append_images=frames[1:], duration=int(1000/args.fps), loop=0, optimize=True)
        # Key frames for the sheet: five evenly spaced samples of the first model pair.
        print('wrote', name, len(frames), 'frames')
    labels = ' / '.join(p.ident for p in posed)
    rows = ''.join(f"<section><h2>{name} · {recipe['clips'][name]['label']}</h2><p>{recipe['clips'][name]['lore']} — 触发：{'、'.join(recipe['clips'][name]['triggers'])}"
                   f" — {recipe['clips'][name]['duration']} 秒{'循环' if recipe['clips'][name]['loop'] else '单次'}</p><img src='{name}.gif' alt='{name}'></section>" for name in recipe['clips'])
    (out/'index.html').write_text(f"""<!doctype html><meta charset="utf-8"><title>世界观动作预览</title>
<style>body{{font:14px system-ui,sans-serif;background:#f3f1ec;color:#333;margin:24px}}section{{margin:0 0 28px}}h2{{font-size:16px;margin:0 0 4px}}p{{margin:0 0 6px;color:#666}}img{{image-rendering:pixelated;border:1px solid #d8d2c6}}</style>
<h1>世界观动作预览（第一批，待复查）</h1><p><a href='motions.html'>交互预览</a>：可选动作、旋转、缩放、调速。</p><p>每张动图从左到右：{labels}。离线渲染，使用游戏模型与动作修正资源，表情为该行为在游戏中的表情；不代表实机效果。直接双击打开，不需要服务器。</p>{rows}
""", encoding='utf-8')
    print('wrote', out/'index.html')


if __name__ == '__main__':
    main()
