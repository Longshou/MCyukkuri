"""Offline sheet: boat_paddle key poses in a hull form (side and front) and the braid's fore-aft bend strip.

Reads the exported runtime clip (motion_corrections.json) and, with --against REV, the same clip from that git revision
for comparison. The captain is the placement-fitted marisa skinned the way the game composes bones
(aquatic_tools.palette), her hat hidden as aboard; the hull is the runtime prop at its hull_bounds scale with her on
its seat, the stick the props.paddle keys, the tinted band the hull's fixed waterline (aquatic_tools.hull_forms). The
strip shows each braid joint's fore-aft bend over one stroke (blue forward, red aft): a wave running down the braid reads
as diagonal bands. Flat-shaded orthographic Pillow render; nothing launches the game. Writes
.local/previews/aquatic-paddle/paddle.png (MCYUKKURI_PREVIEWS overrides the previews root).

    python standards/yukkuri-library/motions/variants/lore/aquatic_paddle_preview.py [--form aquatic_basin_hat_turned] [--against origin/main]
"""
from pathlib import Path
import argparse, json, os, runpy, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import preview as P
sys.path.insert(0, str(P.ROOT/'scripts'))
import placement_fit

OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', P.ROOT/'.local/previews'))/'aquatic-paddle'
RUNTIME = 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json'
TOOLS = runpy.run_path(str(HERE/'aquatic_tools.py'))
FORWARD, AFT, ZERO = np.array((42, 120, 214)), np.array((227, 73, 72)), np.array((240, 239, 236))
BG, INK, MUTED = (236, 245, 246), '#203e48', '#4b6b74'
COLOURS = {'braid': (236, 150, 40), 'hair': (226, 196, 92), 'face': (70, 60, 60), 'body': (240, 226, 204), 'hull': (58, 56, 66), 'twig': (122, 84, 46)}
LIGHT = np.array([.35, .8, -.45])/np.linalg.norm([.35, .8, -.45])


class Captain:
    def __init__(self, data, label):
        self.model = m = placement_fit.fitted(P.ASSETS/'yukkuri/marisa.json', placement_fit.load()); self.label = label
        rev = data['models']['marisa']; index = {b['name']: i for i, b in enumerate(m['bones'])}
        clip = rev['clips']['boat_paddle']
        self.duration, self.markers = clip['duration'], clip['markers']
        self.clip = dict(clip, tracks=[dict(tr, bone=index[rev['bone_names'][tr['bone']]]) for tr in clip['tracks']])
        hidden = tuple(json.loads((P.ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants']['marisa']['accessory_prefixes'])
        V = np.array(m['vertices']).reshape(-1, 8)[:, :3]; W = np.array(m['weights']); self.parts = []
        for p in m['parts']:
            if p.get('anatomy') or p['name'].startswith(hidden) or p['name'].startswith('puff_'): continue
            idx = np.arange(p['start'], p['start']+p['count']); n = p['name']
            kind = 'braid' if n.startswith('braid_lock_') else 'face' if n.startswith('face_') else 'hair' if 'hair' in n or 'bang' in n or 'lock' in n else 'body'
            self.parts.append((kind, np.c_[V[idx], np.ones(len(idx))], W[idx, 0].astype(int), W[idx, 1].astype(int), W[idx, 2:3]))
        self.chain = [index[n] for n in ['hand_right']+[f'hand_right_braid_{k}' for k in range(1, 6)]+['hand_right_tip']]
        self.tuft = np.array(json.loads((P.ASSETS/'yukkuri_presentation/braid_rescue.json').read_text(encoding='utf-8'))['braid_tip'], float)
        self.tip = index['hand_right_tip']

    def triangles(self, t, length):
        M = TOOLS['palette'](self.model, self.clip['tracks'], t); out = []
        for kind, hv, a, b, w in self.parts:
            pts = (np.einsum('nij,nj->ni', M[a], hv)*(1-w)+np.einsum('nij,nj->ni', M[b], hv)*w)[:, :3]
            out += [(kind, pts[k:k+3]) for k in range(0, len(pts)-2, 3)]
        k = TOOLS['sample'](self.clip['props']['paddle']['keys'], t); A = np.eye(4); A[:3, 3] = np.array(self.model['bones'][self.tip]['pivot'])+k[:3]
        R = np.eye(4); R[:3, :3] = TOOLS['euler'](k[3:]); G = M[self.tip]@A@R
        top, end = (G@np.r_[0, length[0], 0, 1])[:3], (G@np.r_[0, -length[1], 0, 1])[:3]
        d = (end-top)/np.linalg.norm(end-top); u = np.cross(d, [0, 1, 0]); u = u/np.linalg.norm(u)*.35; v = np.cross(d, u)
        ring = [u+v, u-v, -u-v, -u+v]
        for i in range(4):
            q = [top+ring[i], end+ring[i], end+ring[(i+1) % 4], top+ring[(i+1) % 4]]; out += [('twig', np.array(q[:3])), ('twig', np.array([q[0], q[2], q[3]]))]
        return out

    def poses(self):
        """Key pose times from the clip's markers: catch, mid pull, finish, mid recovery."""
        a, b = self.markers['power_start'], self.markers['power_end']; back = b+(self.duration-(b-a))/2
        return [(t % self.duration, beat) for t, beat in ((a, '入水'), ((a+b)/2, '拉水'), (b, '出水'), (back, '回桨'))]

    def bends(self, times):
        """Fore-aft bend (degrees, + forward) at braid_1..tip for each time, less each joint's mean over the times (the rest shape)."""
        out = []
        for t in times:
            M = TOOLS['palette'](self.model, self.clip['tracks'], t)
            J = [(M[c]@np.r_[self.model['bones'][c]['pivot'], 1])[:3] for c in self.chain]+[(M[self.tip]@np.r_[self.tuft, 1])[:3]]
            seg = np.diff(np.array(J), axis=0); seg /= np.linalg.norm(seg, axis=1, keepdims=True)
            out.append(np.diff(np.degrees(np.arcsin(-seg[:, 2]))))
        return np.array(out)-np.mean(out, axis=0)


def hull_triangles(form):
    H = json.loads((P.ASSETS/f'yukkuri_presentation/hull_bounds/{form}.json').read_text(encoding='utf-8'))
    V = np.array(json.loads((P.ASSETS/f'yukkuri_props/{H["hull"]}.json').read_text(encoding='utf-8'))['vertices']).reshape(-1, 8)[:, :3]
    c = H['captain_seat']
    V = V*H['scale']-np.array([c['x'], c['y'], c['z']])*16
    return [('hull', V[k:k+3]) for k in range(0, len(V)-2, 3)]


def draw(tris, view, water, size=(600, 440), span=34.):
    """Orthographic flat-shaded painter's render; side looks from the braid side (+X, bow left), front from the bow."""
    centre = (-2., 2.5) if view == 'side' else (9., 2.5)
    s = 2; img = Image.new('RGB', (size[0]*s, size[1]*s), BG); d = ImageDraw.Draw(img); k = size[0]*s/span
    axes = (2, 0) if view == 'side' else (0, 2)  # screen x axis, depth axis (larger nearer)
    sign = (1, 1) if view == 'side' else (1, -1)
    def screen(p): return ((p[..., axes[0]]*sign[0]-centre[0])*k+size[0]*s/2, size[1]*s*.62-(p[..., 1]-centre[1])*k)
    order = sorted(tris, key=lambda e: float(e[1][:, axes[1]].mean()*sign[1]))
    for kind, P3 in order:
        n = np.cross(P3[1]-P3[0], P3[2]-P3[0]); norm = np.linalg.norm(n)
        if norm < 1e-12: continue
        shade = .45+.55*abs(float(n@LIGHT))/norm; x, y = screen(P3)
        d.polygon(list(zip(x, y)), fill=tuple(int(c*shade) for c in COLOURS[kind]))
    _, wy = screen(np.array([0., water, 0.]))
    band = Image.new('RGBA', img.size, (0, 0, 0, 0)); ImageDraw.Draw(band).rectangle((0, wy, img.size[0], img.size[1]), fill=(80, 150, 205, 92))
    img = Image.alpha_composite(img.convert('RGBA'), band).convert('RGB'); ImageDraw.Draw(img).line((0, wy, img.size[0], wy), fill=(60, 120, 180), width=2)
    return img.resize(size, Image.Resampling.LANCZOS)


def strip(values, width, row=20, scale=12.):
    cols = values.shape[0]; img = Image.new('RGB', (width, row*values.shape[1]), tuple(ZERO)); px = img.load()
    for x in range(width):
        for j, b in enumerate(values[min(cols-1, x*cols//width)]):
            q = max(-1., min(1., b/scale)); rgb = ZERO+(FORWARD-ZERO)*q if q > 0 else ZERO+(AFT-ZERO)*(-q)
            for y in range(j*row+1, (j+1)*row-1): px[x, y] = tuple(int(c) for c in rgb)
    return img


def labelled(image, title, sub):
    d = ImageDraw.Draw(image); d.rectangle((0, 0, image.width, 24), fill='white'); d.text((6, 3), title, font=P.preview_font(15), fill=INK)
    d.text((6, image.height-22), sub, font=P.preview_font(13), fill=MUTED); return image


def main():
    ap = argparse.ArgumentParser(description=__doc__); ap.add_argument('--form', default='aquatic_basin_hat_turned')
    ap.add_argument('--against', help='git revision whose runtime boat_paddle is drawn above the current one')
    args = ap.parse_args(); OUT.mkdir(parents=True, exist_ok=True)
    recipe = TOOLS['paddle_spec'](json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))['clips']['boat_paddle'])
    captains = [(Captain(json.loads((P.ROOT/RUNTIME).read_text(encoding='utf-8')), '当前'), (recipe['top_length'], recipe['blade_length']))]
    if args.against:
        old = json.loads(subprocess.run(['git', 'show', f'{args.against}:{RUNTIME}'], cwd=P.ROOT, capture_output=True, check=True).stdout)
        was = subprocess.run(['git', 'show', f'{args.against}:{(HERE/"recipe.json").relative_to(P.ROOT).as_posix()}'], cwd=P.ROOT, capture_output=True, check=True).stdout
        was = json.loads(was)['clips']['boat_paddle']['paddle']  # that revision's own stick lengths
        captains.insert(0, (Captain(old, f'旧 {args.against}'), (was['top_length'], was['blade_length'])))
    form = next(f for f in TOOLS['hull_forms'](captains[-1][0].model['units_per_block']) if f['name'] == args.form); hull = hull_triangles(args.form)
    cell = (360, 264); rows = []
    for captain, length in captains:
        for view, name in (('side', '侧视：辫子一侧，船头在左'), ('front', '正视：自船头，辫子在右')):
            cells = [labelled(draw(captain.triangles(t, length)+hull, view, form['water']).resize(cell, Image.Resampling.LANCZOS), f'{t:.1f}s {beat}', name)
                     for t, beat in captain.poses()]
            rows.append((f'{captain.label}·{name[:2]}', cells))
    now = captains[-1][0]; times = np.linspace(0, now.duration, round(now.duration*100)+1); width = 4*cell[0]-60
    board = Image.new('RGB', (4*cell[0], 20+len(captains)*176), BG); d = ImageDraw.Draw(board)
    for i, (captain, _) in enumerate(captains):
        b = captain.bends(times); y = 10+i*176; board.paste(strip(b, width), (30, y+24))
        d.text((30, y), f'{captain.label}：辫节前后弯曲，上 braid_1 → 下发尾；横轴一次划桨 0–{now.duration:g} s；减去各节平均弯曲；蓝向前、红向后（±12° 封顶）；白点为各节向前弯曲峰值',
               font=P.preview_font(14), fill=INK)
        for j in range(b.shape[1]):
            x = 30+int(times[int(np.argmax(b[:, j]))]/now.duration*width); d.ellipse((x-4, y+24+j*20+6, x+4, y+24+j*20+14), fill='white', outline=INK)
        for t0, beat in ((now.markers['power_start'], '入水'), (now.markers['power_end'], '出水')):
            x = 30+int(t0/now.duration*width); d.line((x, y+20, x, y+24+6*20), fill=INK, width=2); d.text((x+4, y+26+6*20), beat, font=P.preview_font(13), fill=MUTED)
    out = Image.new('RGB', (190+4*cell[0], 70+len(rows)*cell[1]+board.height), BG); d = ImageDraw.Draw(out)
    d.text((10, 6), f'划桨：辫子伸出越过帽檐、木棍入水（{args.form}，水线 {form["water"]:.2f}，离线渲染，非游戏实录）', font=P.preview_font(22), fill=INK)
    d.text((10, 40), '运行片段 boat_paddle 的骨骼轨道与 props.paddle 关键帧（游戏同读）；运行船体道具按 hull_bounds 缩放，船长坐在座位上；浅蓝为水线以下', font=P.preview_font(14), fill=MUTED)
    y = 70
    for name, cells in rows:
        d.text((8, y+cell[1]//2-10), name, font=P.preview_font(16), fill=INK)
        for j, image in enumerate(cells): out.paste(image, (190+j*cell[0], y))
        y += cell[1]
    d.text((8, y+40), '辫节弯曲带', font=P.preview_font(16), fill=INK); out.paste(board, (190, y))
    out.save(OUT/'paddle.png'); print(OUT/'paddle.png', flush=True)


if __name__ == '__main__':
    main()
