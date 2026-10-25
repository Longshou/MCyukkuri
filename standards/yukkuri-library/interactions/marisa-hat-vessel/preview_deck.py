"""Offline top views of the turned hat's deck (hat_deck_layout.json) for review; no game or Java.

Reads the exported runtime data (hat_deck_layout.json, hat_frames.json, hat_vessel.json) and draws the aquatic deck:
the brim, the cavity tiers, every seat with its kind, the entry points, the stowed stick, the larder tiers and one
conflict set; the documented loads seated as the exporter's own check places them; two sections through the middle
showing what stands above the rim; the nest and the land hat afloat; capacities and the load check.
Writes <previews>/hat-deck/hat-deck.png and index.html.

  python preview_deck.py        (MCYUKKURI_PREVIEWS overrides <repo>/.local/previews)
"""
from pathlib import Path
import json, os, sys
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation'
OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT/'.local/previews'))/'hat-deck'
sys.path.insert(0, str(HERE))
import deck as DK

LAYOUT = json.loads((ASSETS/'hat_deck_layout.json').read_text(encoding='utf-8'))
FRAMES = json.loads((ASSETS/'hat_frames.json').read_text(encoding='utf-8'))
VESSEL = json.loads((ASSETS/'hat_vessel.json').read_text(encoding='utf-8'))
RECIPE = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
KIND = {'CAPTAIN': ((150, 60, 40), '船长'), 'CAPTAIN_HIGH': ((200, 110, 60), '临产高座'), 'BOW_PERCH': ((40, 120, 200), '船头浅台'),
        'WELL_FRONT': ((60, 170, 170), '前井'), 'NEST_HOLLOW': ((110, 70, 170), '窝凹'), 'BRIM': ((30, 150, 70), '前帽檐'),
        'TOILET_RIM': ((170, 130, 20), '如厕檐'), 'RESCUE_RIM': ((210, 50, 120), '救援檐'), 'SERVICE': ((120, 120, 120), '船长照护位'),
        'LARDER': ((150, 110, 50), '粮袋')}
CLASS = {'newborn': '新生', 'baby': '赤油', 'small_child': '小子油', 'child': '子油', 'large_child': '大子油', 'juvenile': '亚成',
         'adult': '成体', 'gravid': '孕晚期'}


def font(size, bold=False):
    for name in ('C:/Windows/Fonts/msyhbd.ttc' if bold else 'C:/Windows/Fonts/msyh.ttc', '/System/Library/Fonts/STHeiti Medium.ttc',
                 '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'):
        if Path(name).exists(): return ImageFont.truetype(name, size)
    return ImageFont.load_default()


F12, F14, F16, F26 = font(12), font(14), font(16), font(26, True)


class View:
    """Top view of one hull mode: hull x to the right, the bow (-z) up; window (x0, x1, z0, z1) in hat units."""

    def __init__(self, draw, ox, oy, scale, mode, window=None):
        self.d, self.ox, self.oy, self.s = draw, ox, oy, scale; self.m = LAYOUT['modes'][mode]
        b = VESSEL['brim_rects']; bx = max(abs(v) for r in b for v in r[:2]); bz = max(abs(v) for r in b for v in r[2:])
        self.w = window or (-bx, bx, -bz, bz)

    def p(self, x, z): return (self.ox+(x-self.w[0])*self.s, self.oy+(z-self.w[2])*self.s)

    def size(self): return ((self.w[1]-self.w[0])*self.s, (self.w[3]-self.w[2])*self.s)

    def rect(self, r, **kw):
        a, b = self.p(r[0], r[2]), self.p(r[1], r[3])
        self.d.rectangle([min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1])], **kw)

    def poly(self, pts, **kw): self.d.polygon([self.p(x, z) for x, z in pts], **kw)

    def hull(self):
        for r in VESSEL['brim_rects']: self.rect(r, fill=(232, 214, 176))
        shades = [(214, 196, 160), (196, 176, 140), (172, 150, 118), (146, 124, 96)]
        for sec, shade in zip(VESSEL['cavity_sections'], shades): self.rect(sec['rect'], fill=shade)
        self.rect(VESSEL['opening'], outline=(90, 70, 50), width=2)

    def box(self, b, **kw): self.poly(b.corners(), **kw)

    def body(self, slot, cls, colour, hat=True, width=2):
        e = self.m['envelopes'][cls]; x, y, z = slot['pos']; f = DK.facing(slot['yaw'])
        off = (e['body']['back']-e['body']['front'])/2
        body = DK.Box(x-f[0]*off, z-f[1]*off, f, (e['body']['front']+e['body']['back'])/2, e['body']['side'], 0, 0)
        if hat and 'hat' in e:
            h = e['hat']; off = (h['back']-h['front'])/2+h['behind']
            self.box(DK.Box(x-f[0]*off, z-f[1]*off, f, (h['front']+h['back'])/2, h['side'], 0, 0), outline=colour+(255,), width=1)
        self.box(body, fill=colour+(80,), outline=colour+(255,), width=width)
        self.d.line([self.p(x, z), self.p(x+f[0]*e['body']['front'], z+f[1]*e['body']['front'])], fill=colour+(255,), width=2)

    def slots(self, labels=True, skip=()):
        seen = {}
        for s in self.m['slots']:
            colour = KIND[s['kind']][0]; x, y, z = s['pos']; c = self.p(x, z); r = 4
            self.d.ellipse([c[0]-r, c[1]-r, c[0]+r, c[1]+r], fill=colour, outline=(255, 255, 255))
            if not labels or s['kind'] in skip: continue
            key = (round(c[0]/40), round(c[1]/12)); n = seen.get(key, 0); seen[key] = n+1
            t = short(s['id']); at = (c[0]+6+n*30, c[1]-8); w = self.d.textlength(t, font=F12)
            self.d.rectangle([at[0]-1, at[1]+3, at[0]+w+1, at[1]+17], fill=(255, 255, 255, 190))
            self.d.text(at, t, fill=colour, font=F12)

    def larder(self, tiers=(1, 2, 3), fill=False):
        cols = {1: (120, 160, 60), 2: (200, 140, 40), 3: (190, 60, 40)}
        for t in tiers:
            z = self.m['larder'][t]['zone'] if self.m['larder'] else None
            if not z: continue
            if fill:
                self.rect(z['bounds'], fill=(160, 120, 60, 170), outline=(110, 80, 30)); a = self.p(z['bounds'][0], z['bounds'][2])
                self.d.rectangle([a[0]+1, a[1]+1, a[0]+46, a[1]+17], fill=(110, 80, 30)); self.d.text((a[0]+3, a[1]), f'粮袋{t}档', fill=(255, 255, 255), font=F12)
            else:
                self.rect(z['bounds'], outline=cols[t]+(150,), width=1); a = self.p(z['bounds'][1], z['bounds'][3])
                self.d.text((a[0]+3, a[1]-14), f'粮{t}', fill=cols[t], font=F12)

    def extras(self, labels=True):
        st = self.m['fixtures'].get('paddle_stow')
        if st:
            self.d.line([self.p(st['segment'][0][0], st['segment'][0][2]), self.p(st['segment'][1][0], st['segment'][1][2])], fill=(110, 70, 30), width=5)
        for e in self.m['entries']:
            a = self.p(e['edge'][0], e['edge'][2]); n = e['normal']; b = (a[0]+n[0]*26, a[1]+n[1]*26)
            self.d.line([b, a], fill=(20, 90, 160), width=3)
            self.d.polygon([a, (a[0]+n[0]*9-n[1]*6, a[1]+n[1]*9+n[0]*6), (a[0]+n[0]*9+n[1]*6, a[1]+n[1]*9-n[0]*6)], fill=(20, 90, 160))
            if labels:
                w = self.d.textlength(e['id'], font=F12)
                x = a[0]+6 if n[0] == 0 else a[0]-w if n[0] > 0 else a[0]
                self.d.text((x, b[1]-(20 if n[0] else 4)), e['id'], fill=(20, 90, 160), font=F12)


def short(sid):
    for k, v in (('bow_perch_', 'B'), ('nest_hollow_', 'N'), ('well_front_', 'W'), ('brim_', 'F'), ('captain_care_', 'S'),
                 ('toilet_rim_', 'T'), ('rescue_rim_', 'R'), ('captain_high', 'CH'), ('captain', 'C'), ('larder', 'L')):
        if sid.startswith(k): return v+sid[len(k):].upper()
    return sid


def gravid(view, slot, fill=True):
    e = view.m['envelopes']['gravid']; x, y, z = slot['pos']; off = (e['body']['back']-e['body']['front'])/2
    colour = KIND['CAPTAIN_HIGH'][0]
    view.box(DK.Box(x, z+off, (0, -1), (e['body']['front']+e['body']['back'])/2, e['body']['side'], 0, 0),
             fill=colour+(70,) if fill else None, outline=colour+(255,), width=2 if fill else 1)


def section(d, ox, oy, s, bodies, tier, title):
    """A section through x = 0: water line, brim, cavity tiers, the larder, and the seated bodies with their hats."""
    m = LAYOUT['modes']['AQUATIC_FLOAT']; fr = FRAMES['modes']['aquatic']; top = m['top']; sq = m['squash']
    bz = max(abs(v) for r in VESSEL['brim_rects'] for v in r[2:])
    P = lambda z, y: (ox+(z+bz+1.5)*s, oy-(y+5.0)*s)
    d.text((ox, P(0, 8.6)[1]), title, fill=(40, 30, 30), font=F16)
    w0, w1 = P(-bz-1.5, 0), P(bz+1.5, 0); d.rectangle([w0[0], w0[1], w1[0], P(0, -5.3)[1]], fill=(170, 205, 235))
    d.line([w0, w1], fill=(40, 100, 180), width=2)
    keel = top-VESSEL['keel']*sq
    outer = [P(-bz, top), P(bz, top), P(bz, top-.3), P(6.51, top-.3), P(5.0, keel+1.2), P(-5.0, keel+1.2), P(-6.51, top-.3), P(-bz, top-.3)]
    d.polygon(outer, fill=(205, 185, 150), outline=(90, 70, 50))
    for sec in VESSEL['cavity_sections']:
        r = sec['rect']; a, b = P(r[2], top), P(r[3], top-sec['depth']*sq); d.rectangle([a[0], a[1], b[0], b[1]], fill=(150, 128, 100))
    for sec in VESSEL['cavity_sections']:
        r = sec['rect']; y = top-sec['depth']*sq; d.line([P(r[2], y), P(r[3], y)], fill=(80, 60, 40), width=2)
    if tier:
        z = m['larder'][tier]['zone']; a, b = P(z['bounds'][2], z['y']+z['height']), P(z['bounds'][3], z['y'])
        d.rectangle([a[0], a[1], b[0], b[1]], fill=(160, 120, 60), outline=(110, 80, 30))
        d.text((P(-bz, 0)[0], P(0, -5.3)[1]+20), f'粮袋 {tier} 档：顶面低于帽口 {top-z["y"]-z["height"]:.1f} 帽单位', fill=(110, 75, 30), font=F12)
    for slot, cls, colour in bodies:
        e = m['envelopes'][cls]; x, y, zc = slot['pos']; f = DK.facing(slot['yaw'])
        front, back = (e['body']['front'], e['body']['back']) if abs(f[1]) > .5 else (e['body']['side'], e['body']['side'])
        z0, z1 = (zc-front, zc+back) if f[1] < 0 else (zc-back, zc+front)
        a, b = P(z0, y+e['height']), P(z1, y); d.rounded_rectangle([a[0], a[1], b[0], b[1]], radius=6, fill=colour+(150,), outline=colour)
        if 'hat' in e:
            h = e['hat']; hz = (h['front'], h['back']) if abs(f[1]) > .5 else (h['side'], h['side'])
            z0, z1 = (zc-hz[0], zc+hz[1]) if f[1] < 0 else (zc-hz[1], zc+hz[0]); yb = y+h['base']
            d.rectangle([P(z0, yb+.25)[0], P(z0, yb+.25)[1], P(z1, yb)[0], P(z1, yb)[1]], fill=(60, 50, 60))
            cz = (z0+z1)/2; cw = (z1-z0)*.3
            d.polygon([P(cz-cw, yb+.25), P(cz+cw, yb+.25), P(cz+cw*.4, yb+h['height']), P(cz-cw*.4, yb+h['height'])], fill=(60, 50, 60))
    d.line([P(-bz-1.5, top), P(bz+1.5, top)], fill=(220, 40, 40), width=3)
    d.text((P(-bz-1.4, top)[0], P(0, top)[1]-18), f'帽口平面 y={top}', fill=(220, 40, 40), font=F12)
    d.text((P(bz-3.6, 0)[0], P(0, -4.6)[1]), f'水线 y=0；干舷 {fr["freeboard"]} → {fr["freeboard_by_load"][-1][1]}（载重 0 → 12）', fill=(30, 80, 150), font=F12)
    d.text((P(-bz, 0)[0], P(0, -5.3)[1]+4), '船头 −Z', fill=(60, 60, 60), font=F12)
    d.text((P(bz, 0)[0]-50, P(0, -5.3)[1]+4), '船尾 +Z', fill=(60, 60, 60), font=F12)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases_by_name = {c['name']: c for c in RECIPE['deck']['verify']}
    verify = verification()
    W, H = 1960, 1720
    img = Image.new('RGB', (W, H), (250, 248, 242)); d = ImageDraw.Draw(img, 'RGBA')
    d.text((24, 16), '水生魔理沙帽船 · 离散席位表（hat_deck_layout.json）当前预览', fill=(40, 30, 30), font=F26)
    d.text((24, 52), '帽单位俯视：x 向右（船长辫子一侧 +X），船头 −Z 朝上；圆点颜色＝席位类型；蓝箭头＝入舷点；棕色粗线＝左舷外沿存放的木棍；'
                     '彩色细框＝粮袋三档。数据全部来自导出资源，未运行游戏。', fill=(80, 70, 60), font=F14)
    # A: every slot of the aquatic deck; one conflict set highlighted
    A = View(d, 40, 110, 30, 'AQUATIC_FLOAT'); A.hull(); A.larder(); A.extras()
    cap = next(s for s in A.m['slots'] if s['kind'] == 'CAPTAIN'); A.body(cap, 'adult', KIND['CAPTAIN'][0])
    hi = next((s for s in A.m['slots'] if s['kind'] == 'CAPTAIN_HIGH'), None)
    if hi: gravid(A, hi, fill=False)
    focus = next(s for s in A.m['slots'] if s['kind'] == 'BOW_PERCH' and 'child' in s['classes'])
    for sid in focus['conflicts']['child']:
        o = next(s for s in A.m['slots'] if s['id'] == sid)
        d.line([A.p(focus['pos'][0], focus['pos'][2]), A.p(o['pos'][0], o['pos'][2])], fill=(220, 40, 40, 90), width=1)
    A.body(focus, 'child', (220, 40, 40))
    A.slots(skip=('BOW_PERCH',))
    w, h = A.size()
    d.text((40, 118+h), f'AQUATIC_FLOAT：{len(A.m["slots"])} 席位、{len(A.m["edges"])} 步行边、{len(A.m["care"])} 组照护配对', fill=(40, 30, 30), font=F14)
    d.text((40, 138+h), f'细橙框＝临产高座上的满孕体；红框与红线＝{short(focus["id"])} 坐子油时排除的席位', fill=(40, 30, 30), font=F12)
    lx, ly = 40, 162+h
    for i, (k, (colour, name)) in enumerate(KIND.items()):
        x = lx+(i % 3)*175; y = ly+(i//3)*20
        d.ellipse([x, y+4, x+10, y+14], fill=colour); d.text((x+14, y), f'{name} {k}', fill=(60, 50, 40), font=F12)
    # A2: the bow perch enlarged, with every label
    zy = int(ly+112)
    d.text((40, zy-22), '船头浅台放大（同一位置的不同朝向各算一个席位）', fill=(40, 30, 30), font=F14)
    window = (-4.6, 4.6, -6.3, -3.6); zw, zh = int((window[1]-window[0])*52), int((window[3]-window[2])*52)
    sub = Image.new('RGB', (zw, zh), (250, 248, 242)); Z = View(ImageDraw.Draw(sub, 'RGBA'), 0, 0, 52, 'AQUATIC_FLOAT', window=window)
    Z.hull(); Z.slots(); img.paste(sub, (40, zy)); d.rectangle([40, zy, 40+zw, zy+zh], outline=(90, 70, 50))
    # B: the documented loads, seated as the exporter's check places them
    cases = [('high', '4 赤油＋3 粮＋桨（夜）'), ('cargo_stow', '子油 0.40＋7 粮＋桨（夜）'),
             ('two_babies_nine_food', '2 赤油＋9 粮＋桨（夜）'), ('labour', '临产：3 新生＋3 粮＋桨')]
    for n, (name, title) in enumerate(cases):
        ox = 560+n*350; V = View(d, ox, 124, 16, 'AQUATIC_FLOAT'); V.hull()
        case = cases_by_name[name]; got = verify[name]['night'] if name != 'labour' else verify[name]['day']
        tier = next(t for t, mx in enumerate([0, 3, 7, 12]) if case['food'] <= mx)
        if tier: V.larder((tier,), fill=True)
        owner = next(s for s in V.m['slots'] if s['kind'] == ('CAPTAIN_HIGH' if case.get('owner') == 'gravid' else 'CAPTAIN'))
        if case.get('owner') == 'gravid': gravid(V, owner)
        else: V.body(owner, 'adult', KIND['CAPTAIN'][0])
        for sid, cls in zip(got, case['bodies']):
            s = next(t for t in V.m['slots'] if t['id'] == sid); V.body(s, cls, KIND[s['kind']][0])
        st = V.m['fixtures']['paddle_stow']['segment']
        d.line([V.p(st[0][0], st[0][2]), V.p(st[1][0], st[1][2])], fill=(110, 70, 30), width=4)
        d.text((ox, 98), title, fill=(40, 30, 30), font=F16)
        vw, vh = V.size()
        d.text((ox, 132+vh), f'载重 {verify[name]["load"]} / 12', fill=(70, 60, 50), font=F12)
        d.text((ox, 148+vh), '、'.join(f'{CLASS[c]}@{short(s)}' for s, c in zip(got, case['bodies'])), fill=(70, 60, 50), font=F12)
    # C: sections through the middle
    m = LAYOUT['modes']['AQUATIC_FLOAT']
    child = next(s for s in m['slots'] if s['id'] == verify['cargo_stow']['night'][0])
    under = [s for s in m['slots'] if s['id'] in verify['high']['night'] and s['kind'] == 'NEST_HOLLOW']
    section(d, 560, 960, 23, [(cap, 'adult', KIND['CAPTAIN'][0]), (child, 'child', KIND['BOW_PERCH'][0])], 3,
            '中线剖面：子油 0.40 坐船头浅台、背对船长，满粮 3 档在冠底')
    section(d, 1260, 960, 23, [(cap, 'adult', KIND['CAPTAIN'][0])]+[(s, 'baby', KIND['NEST_HOLLOW'][0]) for s in under], 1,
            '夜里赤油在船长身下的窝凹，1 档粮袋在其前方')
    # D: the nest and the land hat afloat
    for n, mode in enumerate(('NEST', 'FLOAT')):
        V = View(d, 40+n*250, 1500, 9, mode); V.hull(); V.larder()
        if mode == 'FLOAT': V.extras(labels=False)
        for s in V.m['slots']:
            if s['kind'] == 'CAPTAIN': V.body(s, 'adult', KIND['CAPTAIN'][0], hat=False)
        V.slots(labels=False)
        d.text((40+n*250, 1440), f'{mode}：{len(V.m["slots"])} 席位', fill=(40, 30, 30), font=F14)
    # E: capacity and the load check
    tx, ty = 560, 1050
    d.text((tx, ty), '单一体型最多几只（船长在座、木棍存放；昼-夜，粮袋 0 / 1 / 2 / 3 档）', fill=(40, 30, 30), font=F16); ty += 26
    for c, row in m['capacity'].items():
        d.text((tx, ty), f'{CLASS[c]}（≤{LAYOUT["classes"][c]}）：' + '    '.join(f"{row[f'day_{t}']}-{row[f'night_{t}']}" for t in range(4)),
               fill=(60, 50, 40), font=F14); ty += 20
    ty += 10
    d.text((tx, ty), '载荷核对（载重：粮 0.5/份、桨 1、新生与赤油 2、小子油 3、子油 4、孕体 +2，上限 12）：预期 → 实际', fill=(40, 30, 30), font=F16)
    ty += 26
    for name, v in verify.items():
        case = cases_by_name[name]; expect = case.get('expect', 'OK'); ok = expect == v['state']
        bodies = '＋'.join(CLASS[c] for c in case['bodies']) or '无幼体'
        d.text((tx, ty), f'{"√" if ok else "×"} {name}（{bodies}，{case["food"]} 粮{"，桨" if case["paddle"] else ""}）：载重 {v["load"]}，'
                         f'预期 {expect} → 实际 {v["state"]}', fill=(50, 110, 50) if ok else (190, 40, 40), font=F14)
        ty += 20
    img.save(OUT/'hat-deck.png')
    (OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>帽船甲板席位</title>'
                                  '<body style="margin:0;background:#faf8f2"><img src="hat-deck.png" style="max-width:100%"></body>\n', encoding='utf-8')
    print(OUT/'hat-deck.png')


def verification():
    """Re-run the exporter's own load check (the same code path as build.py)."""
    body = json.loads((ASSETS/'body_geometry.json').read_text(encoding='utf-8'))['variants']['marisa']
    cargo = json.loads((ASSETS/'hat_cargo_layout.json').read_text(encoding='utf-8'))
    stick = ASSETS.parent/'yukkuri_props/paddle_stick.json'
    decks, _ = DK.export(VESSEL, body, cargo, RECIPE['deck'], RECIPE['pregnancy'], json.loads(stick.read_text(encoding='utf-8')) if stick.is_file() else None)
    return {name: dict(load=load, state=state, **got) for name, load, state, got in DK.verify(decks, RECIPE['deck'])}


if __name__ == '__main__':
    main()
