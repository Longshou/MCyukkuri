"""Render the local review sheets for Alice's base-look revision (no browser, game or compile).

Draws the runtime meshes the game shows (placement fit spliced in) with the library's
software rasterizer: before (git HEAD or --before <rev>) and after, Reimu and Marisa for
scale, five views each; a pose sheet of the new model; and a reference strip next to the
Hakkei U slot-2 sprites (read from wiki/art-references, never copied into resources).
Writes .local/previews/alice/ (index.html, compare.png, actions.png, reference.png).
"""
from pathlib import Path
import argparse, base64, io, json, subprocess, sys
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[2]; ROOT = LIB.parents[1]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
sys.path.insert(0, str(ROOT/'scripts'))
import placement_fit  # noqa: E402

VIEWS = [('正面', 0, .12), ('斜前', .65, .18), ('侧面', 1.570796, .12), ('背面', 3.141593, .16), ('俯视', .5, .75)]
POSES = [('waddle', .5), ('stretch', 1.4), ('side_bend', .59), ('hop', .5), ('startle', .5), ('turn', .5), ('press', .5), ('puff', .5)]
BG = (241, 237, 227)


def font(size):
    return ImageFont.truetype('C:/Windows/Fonts/msyh.ttc' if Path('C:/Windows/Fonts/msyh.ttc').exists() else
                              '/System/Library/Fonts/STHeiti Medium.ttc', size)


def git_bytes(rev, path):
    return subprocess.run(['git', 'show', f'{rev}:{path}'], cwd=ROOT, capture_output=True, check=True).stdout


def entry(ident, rev=None):
    rel = f'src/main/resources/assets/mcyukkuri/yukkuri/{ident}.json'
    raw = git_bytes(rev, rel) if rev else (ROOT/rel).read_bytes()
    fits = json.loads(git_bytes(rev, 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json'))['models'] if rev \
        else placement_fit.load()
    model = json.loads(raw); fit = fits.get(ident)
    import hashlib
    if fit and fit['base_sha256'] in (hashlib.sha256(raw).hexdigest(), hashlib.sha256(raw.replace(b'\r\n', b'\n')).hexdigest()):
        model = placement_fit.splice(model, fit)
    tex_rel = f'src/main/resources/assets/mcyukkuri/textures/entity/{ident}/neutral.png'
    texture = Image.open(io.BytesIO(git_bytes(rev, tex_rel) if rev else (ROOT/tex_rel).read_bytes())).convert('RGBA')
    return model, texture


def pose_core():
    js = (ROOT/'art/source/standard-runtime/preview.js').read_text(encoding='utf-8').replace('textureSize:128', 'textureSize:model.texture_size||128')
    return js[js.index('const clamp='):js.index('function groundVertices')]


def render(models, jobs, size, tmp):
    tmp.mkdir(parents=True, exist_ok=True)
    for key, (model, texture) in models.items():
        (tmp/f'{key}.json').write_text(json.dumps(model), encoding='utf-8'); (tmp/f'{key}.rgba').write_bytes(texture.tobytes())
    w, h = size
    program = "const fs=require('fs');\n"+(LIB/'runtime/render.js').read_text(encoding='utf-8')+'\n'+pose_core()+'\n'
    program += 'const jobs='+json.dumps(jobs)+';const dir='+json.dumps(str(tmp))+';const cache={};\n'
    program += ("for(const j of jobs){const m=cache[j.key]||(cache[j.key]=JSON.parse(fs.readFileSync(dir+'/'+j.key+'.json')));"
                "const tex=fs.readFileSync(dir+'/'+j.key+'.rgba');"
                f"const px=renderModel(posed(m,j.action||'idle',j.t||0),tex,{w},{h},j.az,j.el,j.span,{{centerY:j.cy,textureSize:m.texture_size||128}});"
                "fs.writeFileSync(dir+'/'+j.file+'.rgba',px);}")
    (tmp/'render.cjs').write_text(program, encoding='utf-8')
    subprocess.run(['node', str(tmp/'render.cjs')], check=True)
    return {j['file']: Image.frombytes('RGBA', size, (tmp/f"{j['file']}.rgba").read_bytes()) for j in jobs}


def sheet(rows, columns, tiles, size, title):
    w, h = size; top = 70; label = 34
    out = Image.new('RGB', (w*len(columns)+160, top+(h+label)*len(rows)), BG); d = ImageDraw.Draw(out)
    d.text((20, 18), title, font=font(28), fill='#292a25')
    for r, (key, name) in enumerate(rows):
        y = top+r*(h+label)
        d.text((14, y+label+h//2-14), name, font=font(22), fill='#292a25')
        for c, col in enumerate(columns):
            x = 160+c*w
            if r == 0:
                d.text((x+12, y+4), col, font=font(20), fill='#555')
            tile = tiles[f'{key}-{c}']; out.paste(tile, (x, y+label), tile)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--before', default=None, help='git revision of the previous look (default: merge base with origin/main)')
    ap.add_argument('--out', type=Path, default=ROOT.parent.parent/'.local/previews/alice' if ROOT.parent.name == '.worktrees' else ROOT/'.local/previews/alice')
    a = ap.parse_args()
    if a.before is None:
        a.before = subprocess.run(['git', 'merge-base', 'HEAD', 'origin/main'], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    out = a.out; out.mkdir(parents=True, exist_ok=True); tmp = out/'_work'
    models = {'before': entry('alice', a.before), 'after': entry('alice'), 'reimu': entry('reimu'), 'marisa': entry('marisa')}
    rows = [('before', '旧版爱丽丝'), ('after', '新版爱丽丝'), ('reimu', '灵梦（对照）'), ('marisa', '魔理沙（对照）')]
    size = (360, 360)
    jobs = [dict(key=k, file=f'{k}-{c}', az=az, el=el, span=11.8, cy=8.4) for k, _ in rows for c, (_, az, el) in enumerate(VIEWS)]
    tiles = render(models, jobs, size, tmp)
    sheet(rows, [v[0] for v in VIEWS], tiles, size, '爱丽丝基础造型重做（待确认）· 游戏内网格（含位置适配）· 同一比例').save(out/'compare.png')
    clips = models['after'][0]['clips']
    jobs = [dict(key='after', file=f'pose-{i}', action=n, t=min(t if n in ('stretch', 'side_bend') else clips[n]['duration']*t, clips[n]['duration']),
                 az=.55, el=.15, span=12.5, cy=9) for i, (n, t) in enumerate(POSES) if n in clips]
    tiles = render(models, jobs, size, tmp)
    poses = Image.new('RGB', (size[0]*4, 70+(size[1]+34)*((len(jobs)+3)//4)), BG); d = ImageDraw.Draw(poses)
    d.text((20, 18), '新版爱丽丝 · 常用动作关键帧（未叠加游戏动作修正）', font=font(28), fill='#292a25')
    for i, j in enumerate(jobs):
        x, y = (i % 4)*size[0], 70+(i//4)*(size[1]+34)
        d.text((x+12, y+4), j['action'], font=font(20), fill='#555'); poses.paste(tiles[j['file']], (x, y+34), tiles[j['file']])
    poses.save(out/'actions.png')
    # Reference strip: Hakkei U slot-2 composite and, when the user-supplied ver1.12 package is
    # present, its pict/arisu layers stacked on pict/base, next to the new front and elevated views.
    contact = ROOT/'wiki/art-references/game/standard-species/hakkei-contact.png'
    refs = [('八景U slot 2', Image.open(contact).convert('RGB').crop((600, 0, 900, 220)).resize((450, 330), Image.NEAREST))]
    pack = next((p for p in [ROOT/'油库里八景ver1.12/油库里八景ver1.12', ROOT.parent.parent/'油库里八景ver1.12/油库里八景ver1.12'] if p.is_dir()), None)
    if pack:
        frame = Image.new('RGBA', (240, 240), (231, 228, 220, 255))
        for layer in [pack/'pict/base/sei/3.png'] + [pack/'pict/arisu/sei_new'/f'{n}.png' for n in (2, 6, 7, 4, 10, 5)]:
            frame.alpha_composite(Image.open(layer).convert('RGBA').crop((0, 0, 240, 240)))
        frame.putdata([(231, 228, 220, 255) if p[:3] == (0, 0, 0) else p for p in frame.getdata()])  # base layer's black matte
        refs.append(('八景 ver1.12 pict/arisu', frame.convert('RGB').crop((40, 110, 200, 240)).resize((450, 366), Image.NEAREST)))
    width = 470*len(refs)+size[0]*2+20
    strip = Image.new('RGB', (width, 430), BG); d = ImageDraw.Draw(strip)
    d.text((20, 14), '参照（仅作参考，不进入资源）  对照  新版正面 / 俯视', font=font(22), fill='#292a25')
    for k, (label, image) in enumerate(refs):
        strip.paste(image, (10+470*k, 80)); d.text((14+470*k, 52), label, font=font(18), fill='#555')
    for k, c in enumerate((0, 4)):
        t = render(models, [dict(key='after', file=f'ref-{c}', az=VIEWS[c][1], el=VIEWS[c][2], span=10.8, cy=7.6)], size, tmp)[f'ref-{c}']
        strip.paste(t, (470*len(refs)+k*size[0], 60), t)
    strip.save(out/'reference.png')
    (out/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>爱丽丝基础造型重做</title>'
                                  '<body style="background:#f1ede3;font-family:sans-serif"><h1>爱丽丝基础造型重做（待确认）</h1>'
                                  '<p>旧版与新版对照（含灵梦、魔理沙同比例）</p><img src="compare.png" style="max-width:100%">'
                                  '<p>八景U参照</p><img src="reference.png" style="max-width:100%">'
                                  '<p>动作关键帧</p><img src="actions.png" style="max-width:100%"></body>', encoding='utf-8')
    for f in tmp.glob('*'):
        f.unlink()
    tmp.rmdir()
    print(out/'compare.png')


if __name__ == '__main__':
    main()
