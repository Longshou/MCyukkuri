"""Organ placement check: where the mamu and peni sit on every adult runtime body, the derived rear placement of the mamu
for the reproductive layer, and side views of the pairings that can occur.

Both organs share one front lower site whose height is measured from the ground contact up (project design, user
2026-11-13: the same rule for mamu and peni, bottom-up, so a long body such as the bean keeps it at the same height).
During courtship contact and the Alice act the mamu is shown on the rear surface at that same height; the transform that
puts it there is derived here from each body's real extended geometry, not hand-tuned per form.

    python standards/yukkuri-library/drafts/sukkiri/organs.py --table <json>      # measurements and rear transforms
    python standards/yukkuri-library/drafts/sukkiri/organs.py --render <dir>      # pairing side views (PNG)

Geometry comes from the runtime models through the offline pose server (scripts/pose_check.py --serve), sampled at the
end of `anatomy_peni_extend` / `anatomy_mamu_extend`, so it is what the game draws at full extension.
"""
from pathlib import Path
import argparse, json, math, subprocess, sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
PY = sys.executable
# Entity form scales the runtime applies on top of the model (SubspeciesLife.formScale: deibu 1.5).
FORM_SCALE = {'reimu_deibu': 1.5, 'reimu_wasa_deibu': 1.5}
# Forms that never court (SubspeciesLife.canReproduce: congenital forms) still meet a hunting Alice as the held body.
CONGENITAL = {'reimu_bean', 'marisa_bean', 'reimu_sparse', 'marisa_sparse', 'reimu_face', 'marisa_face'}
PREDATORS = {'flandre', 'remilia'}
# Courtship closes the bodies to this clearance (ReproductionPresentation.CONTACT_CLEARANCE, model units).
CLEARANCE = .8
# The organs are drawn as meeting when the peni exit and the mamu centre differ by at most this much in height
# (half the mamu mark's height on the standard body).
MEET = .4


class Poses:
    """One warm offline pose server, as preview/review/pose.js uses it."""
    def __init__(self):
        self.p = subprocess.Popen([PY, str(ROOT/'scripts/pose_check.py'), '--serve'], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, text=True, encoding='utf-8', env={**__import__('os').environ, 'PYTHONUTF8': '1'})
        self.revisions = read(ASSETS/'yukkuri_presentation/motion_corrections.json')['models']
        self.fits = read(ASSETS/'yukkuri_presentation/placement_fit.json')['models']

    def ask(self, request):
        self.p.stdin.write(json.dumps(request)+'\n'); self.p.stdin.flush()
        reply = json.loads(self.p.stdout.readline())
        if 'error' in reply: raise RuntimeError(reply['error'])
        return reply

    def frame(self, ident, action, t):
        model = read(ASSETS/'yukkuri'/f'{ident}.json')
        reg = self.ask(dict(op='register', model=model, revision=self.revisions.get(ident), fit=self.fits.get(ident)))
        out = self.ask(dict(op='render', id=reg['id'], action=action, time=t, adjustment=None, intensity=None,
                            expression='neutral', scale=1))
        self.ask(dict(op='release', id=reg['id']))
        return reg['model'], out['vertices']

    def close(self):
        self.p.stdin.close(); self.p.wait()


def read(path): return json.loads(Path(path).read_text(encoding='utf-8'))


def points(model, vertices, test):
    out = []
    for p in model['parts']:
        if test(p):
            for i in range(p['start'], p['start']+p['count']):
                out.append(vertices[i*8:i*8+3])
    return out


def section(skin):
    """The skin cut by the centre plane x = 0: segments [(y, z), (y, z)] of every triangle crossing it."""
    out = []
    for i in range(0, len(skin)-2, 3):
        tri = skin[i:i+3]; cut = []
        for a, b in ((tri[0], tri[1]), (tri[1], tri[2]), (tri[2], tri[0])):
            if (a[0] <= 0 < b[0]) or (b[0] <= 0 < a[0]):
                q = a[0]/(a[0]-b[0]); cut.append((a[1]+(b[1]-a[1])*q, a[2]+(b[2]-a[2])*q))
        if len(cut) == 2: out.append(cut)
    return out


def surface(skin, y, side):
    """Front (min z) or rear (max z) of the skin on the centre line at height y."""
    if not isinstance(skin, dict): raise TypeError('pass section_of(skin)')
    zs = []
    for (y0, z0), (y1, z1) in skin['section']:
        if min(y0, y1) <= y <= max(y0, y1):
            zs.append(z0 if y1 == y0 else z0+(z1-z0)*(y-y0)/(y1-y0))
    if not zs: raise ValueError(f'no skin at height {y:.2f}')
    return min(zs) if side == 'front' else max(zs)


def section_of(skin): return {'section': section(skin), 'points': skin}


def measure(poses, ident):
    model, peni_v = poses.frame(ident, 'anatomy_peni_extend', .24)
    _, mamu_v = poses.frame(ident, 'anatomy_mamu_extend', .24)
    skin_test = lambda p: p['name'].endswith('body_surface')
    skin_points = points(model, peni_v, skin_test); skin = section_of(skin_points)
    peni = points(model, peni_v, lambda p: p.get('anatomy') == 'peni')
    mamu = points(model, mamu_v, lambda p: p.get('anatomy') == 'mamu')
    ground = min(p[1] for p in skin_points)
    exit_y = meet_y = None
    if peni:
        # The peni axis (principal direction of its points, pointing forward), followed to where it leaves the front skin
        # (exit) and on by the courtship clearance to where a partner's rear skin stands (meet).
        import numpy as np
        P = np.array(peni, float); c0 = P.mean(axis=0)
        u = np.linalg.svd(P-c0)[2][0]
        if u[2] > 0: u = -u
        s = 0.
        for _ in range(60):
            p = c0+s*u; s += (p[2]-surface(skin, p[1], 'front'))/max(.2, -u[2])*.5
        p = c0+s*u; exit_y = float(p[1])-ground
        q = p+u*(CLEARANCE/max(.2, -u[2])); meet_y = float(q[1])-ground
    tip = min(peni, key=lambda p: p[2]) if peni else None
    c = [sum(p[k] for p in mamu)/len(mamu) for k in range(3)] if mamu else None
    facing = None if c is None else ('front' if c[2] < 0 else 'rear')
    out = dict(model=ident, ground=round(ground, 3), body_height=round(max(p[1] for p in skin_points)-ground, 3),
               scale=FORM_SCALE.get(ident, 1), peni_exit=None if exit_y is None else round(exit_y, 3),
               peni_meet=None if meet_y is None else round(meet_y, 3),
               peni_tip=None if tip is None else [round(v, 3) for v in tip],
               mamu_center=None if c is None else [round(v, 3) for v in c], mamu_authored=facing)
    if c is not None:
        # The record the reproductive layer reads (adult_states.json organ_sites): the authored mamu at full extension,
        # turned half round the vertical axis when it was authored on the front (x -> -x, z -> -z), how far its mark
        # stands proud of the skin there, and the rear skin depth by height, so it can be put on the rear skin at the
        # height where the partner's peni meets it.
        turn = facing == 'front'
        proud = (surface(skin, c[1], 'front')-min(p[2] for p in mamu)) if turn else (max(p[2] for p in mamu)-surface(skin, c[1], 'rear'))
        outer = max(-p[2] for p in mamu) if turn else max(p[2] for p in mamu)
        top = max(p[1] for p in skin_points)
        rear = [[round(y-ground, 3), round(surface(skin, y, 'rear'), 3)]
                for y in [ground+.25*i for i in range(int((min(top, ground+6)-ground)/.25)+1)]]
        out['front_at_exit'] = round(surface(skin, (exit_y or 0)+ground, 'front'), 3)
        out['site'] = dict(turn=turn, x=round(-c[0] if turn else c[0], 3)+0., y=round(c[1]-ground, 3), outer=round(outer, 3),
                           proud=round(proud, 3), meet=out['peni_meet'], rear=rear)
    out['_frames'] = (model, peni_v, mamu_v)
    return out


def ids():
    return sorted(p.stem for p in (ASSETS/'yukkuri').glob('*.json')
                  if 'parts' in read(p) and any(q.get('anatomy') for q in read(p)['parts']))


def pairings(table):
    """Who meets whom: courtship (fertile forms, house only with house, predators only with predators) and the Alice act
    (Alice behind any adult that is not a predator)."""
    fertile = [m for m in table if m not in CONGENITAL]
    out = []
    for f in fertile:
        for m in fertile:
            if f == m: continue
            if (f in PREDATORS) != (m in PREDATORS) or f.startswith('house_') != m.startswith('house_'): continue
            out.append((f, m, 'court'))
    if 'alice' in table:
        for m in table:
            if m not in PREDATORS and m != 'alice': out.append(('alice', m, 'alice'))
    return out


def place(M, target):
    """Where the mother's mamu goes on her rear skin for a peni meeting her at `target` (her model units above ground):
    (dx, dy, dz) added after the optional half turn. Clamped to her rear skin samples."""
    site = M['site']; rear = site['rear']
    y = max(rear[0][0], min(rear[-1][0], target))
    for (y0, z0), (y1, z1) in zip(rear, rear[1:]):
        if y0 <= y <= y1: z = z0+(z1-z0)*(y-y0)/max(1e-6, y1-y0); break
    else: z = rear[-1][1]
    return -site['x'], y-site['y'], z+site['proud']-site['outer']


def meet(table, f, m):
    """Height of the father's peni where it meets her rear skin, in her model units, and the static difference to her own
    site (world units: what a fixed placement would miss by)."""
    F, M = table[f], table[m]
    target = F['peni_meet']*F['scale']/M['scale']
    return target, round(F['peni_meet']*F['scale']-M['peni_meet']*M['scale'], 3)


LABEL = dict(reimu='灵梦', marisa='魔理沙', alice='爱丽丝', chen='橙', patchouli='帕秋莉', reimu_bean='豆形灵梦',
             marisa_bean='豆形魔理沙', reimu_deibu='肥灵梦', house_reimu='家油灵梦', house_marisa='家油魔理沙',
             marisa_tsumuri='螺壳魔理沙', reimu_wasa='多毛灵梦', myon='妖梦')


def scene(table, f, m):
    """Both bodies' triangles in one side view (z forward to the left, y up), the father closed to the clearance."""
    F, M = table[f], table[m]; sf, sm = F['scale'], M['scale']
    target, static = meet(table, f, m)
    dx, dy, dz = place(M, target)
    rear = M['site']['rear']; y = max(rear[0][0], min(rear[-1][0], target))
    rear_z = next((z0+(z1-z0)*(y-y0)/max(1e-6, y1-y0) for (y0, z0), (y1, z1) in zip(rear, rear[1:]) if y0 <= y <= y1), rear[-1][1])
    gap = rear_z*sm-F['front_at_exit']*sf+CLEARANCE
    tris = []
    def add(entry, verts, sc, shift, test, colour, ground, turn=None, layer=0):
        model = entry
        for p in model['parts']:
            if not test(p): continue
            for i in range(p['start'], p['start']+p['count'], 3):
                tri = [list(verts[(i+k)*8:(i+k)*8+3]) for k in range(3)]
                if not any(any(v) for v in tri): continue
                if turn is not None:
                    for v in tri:
                        if turn: v[0], v[2] = -v[0], -v[2]
                        v[0] += dx; v[1] += dy; v[2] += dz
                tris.append((layer, max(v[0] for v in tri), [(v[2]*sc+shift, (v[1]-ground)*sc) for v in tri], colour(p)))
    body = lambda p: not p.get('anatomy') and not p['name'].startswith('puff_')
    shade = lambda base: (lambda p: base if p['name'].endswith('body_surface') else tuple(int(c*.82) for c in base))
    mm, _, mv = M['_frames']; fm, fv, _ = F['_frames']
    add(mm, mv, sm, 0, body, shade((214, 190, 160)), M['ground'])
    add(fm, fv, sf, gap, body, shade((170, 192, 214)), F['ground'])
    add(fm, fv, sf, gap, lambda p: p.get('anatomy') == 'peni', lambda p: (40, 80, 210), F['ground'], layer=1)
    add(mm, mv, sm, 0, lambda p: p.get('anatomy') == 'mamu', lambda p: (215, 25, 60), M['ground'], M['site']['turn'], layer=2)
    contact = (rear_z*sm, target*sm)
    return tris, contact, static


def render(table, pairs, out, size=(300, 260), zoom=4.):
    """Per pairing: the whole side view and a close-up of the contact (peni outline over the mamu)."""
    from PIL import Image, ImageDraw
    out.mkdir(parents=True, exist_ok=True)
    tiles = []
    for f, m, kind in pairs:
        tris, (cz, cy), static = scene(table, f, m)
        img = Image.new('RGB', (size[0]*2, size[1]+40), (240, 234, 222)); d = ImageDraw.Draw(img)
        xs = [x for t in tris for x, _ in t[2]]; ys = [y for t in tris for _, y in t[2]]
        k = min(size[0]*.92/(max(xs)-min(xs)), size[1]*.9/(max(ys)-min(ys)))
        views = ((k, (max(xs)+min(xs))/2, (max(ys)+min(ys))/2), (k*zoom, cz, cy))
        for panel, (scale, cx, cyy) in enumerate(views):
            tile = Image.new('RGB', size, (240, 234, 222)); t = ImageDraw.Draw(tile)
            ox, oy = size[0]/2, size[1]/2
            for layer, _, poly, col in sorted(tris, key=lambda q: (q[0], q[1])):
                pts = [(ox+(x-cx)*scale, oy-(y-cyy)*scale) for x, y in poly]
                if layer == 1: t.line(pts+[pts[0]], fill=col)
                else: t.polygon(pts, fill=col)
            if panel == 0:
                gy = oy+cyy*scale; t.line([(0, gy), (size[0]-1, gy)], fill=(120, 110, 100))
                bx, by = ox+(cz-cx)*scale, oy-(cy-cyy)*scale; w, h = size[0]/zoom/2, size[1]/zoom/2
                t.rectangle([bx-w, by-h, bx+w, by+h], outline=(0, 0, 0))
            else:
                t.line([(0, oy), (size[0]-1, oy)], fill=(40, 80, 210))
            t.rectangle([0, 0, size[0]-1, size[1]-1], outline=(200, 190, 175))
            img.paste(tile, (panel*size[0], 40))
        title = f'{"普通配对" if kind == "court" else "爱丽丝雷普魔"}：{LABEL.get(f, f)}（后）→ {LABEL.get(m, m)}（前）'
        d.text((6, 2), title, fill=(0, 0, 0), font=font())
        d.text((6, 21), f'佩尼佩尼进入她身后的高度 {cy:.2f}（她自己的挂点 {cy-static:.2f}）；麻姆麻姆放在进入点；右图放大 {zoom:g} 倍',
               fill=(60, 60, 60), font=font(12))
        name = f'{kind}-{f}-{m}.png'; img.save(out/name); tiles.append(name)
    return tiles


def font(size=14):
    from PIL import ImageFont
    for path in ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/simhei.ttf'):
        try: return ImageFont.truetype(path, size)
        except OSError: pass
    return ImageFont.load_default()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--table', type=Path, help='full measurement report (JSON)')
    parser.add_argument('--sites', type=Path, help='runtime organ site records (JSON) for author_cues.py')
    parser.add_argument('--render', type=Path); parser.add_argument('--models', nargs='*')
    parser.add_argument('--pairs', nargs='*', help='father:mother[:alice]')
    args = parser.parse_args()
    poses = Poses()
    try:
        table = {i: measure(poses, i) for i in (args.models or ids())}
    finally:
        poses.close()
    rows = []
    for f, m, k in pairings(table):
        target, static = meet(table, f, m)
        y = place(table[m], target)[1]+table[m]['site']['y']
        rows.append(dict(father=f, mother=m, kind=k, static_diff=static, placed_miss=round((target-y)*table[m]['scale'], 3)))
    report = dict(rule='Both organs share one front site measured from the ground contact up; during contact the mamu is '
                       'shown on the rear skin where the partner peni meets it.', clearance=CLEARANCE,
                  models={k: {x: y for x, y in v.items() if x != '_frames'} for k, v in table.items()}, pairings=rows)
    if args.table:
        args.table.parent.mkdir(parents=True, exist_ok=True)
        args.table.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
    if args.sites:
        sites = {k: v['site'] for k, v in table.items() if 'site' in v and k not in PREDATORS}
        args.sites.write_text(json.dumps(sites, ensure_ascii=False, separators=(',', ':'))+chr(10), encoding='utf-8', newline=chr(10))
    worst = max(rows, key=lambda r: abs(r['static_diff']), default=None)
    miss = max(rows, key=lambda r: abs(r['placed_miss']), default=None)
    print(f"{len(table)} bodies, {len(rows)} pairings"+(f"; widest static gap {worst['father']} -> {worst['mother']} "
          f"{worst['static_diff']:+.2f}, worst placed miss {miss['placed_miss']:+.2f}" if rows else ''))
    for k, v in report['models'].items():
        print(k, 'exit', v['peni_exit'], 'meet', v['peni_meet'], 'site', {x: y for x, y in v.get('site', {}).items() if x != 'rear'})
    if args.render:
        pairs = [(p.split(':')[0], p.split(':')[1], 'alice' if p.endswith(':alice') else 'court') for p in args.pairs] if args.pairs else pairings(table)
        render(table, pairs, args.render)


if __name__ == '__main__':
    main()
