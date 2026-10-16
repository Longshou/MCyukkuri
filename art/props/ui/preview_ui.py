"""Offline mock-ups of the shop screens from shop_ui.png (layout constants mirror client/ShopScreen etc.).

Usage: python preview_ui.py [out_dir] [lang]   (default .local/previews/shop-ui, lang zh_cn or en_us; both when omitted).
Text uses the vanilla font through .local/tools/art-review/mcfont.py when present (ascii bitmaps + Unifont), otherwise a
system TTF stand-in. Strings come from the lang files; values are sample data. Also writes a 480x270 viewport (1080p GUI auto).
"""
import json, re, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
for cand in Path(__file__).resolve().parents:
    tools = cand / '.local' / 'tools' / 'art-review'
    if tools.is_dir(): sys.path.insert(0, str(tools)); break
try:
    from mcfont import MCFont
    MC = MCFont()
except Exception:
    MC = None

HERE = Path(__file__).resolve().parent
ATLAS = Image.open(HERE / 'shop_ui.png').convert('RGBA')
INK, SOFT, WARN = '#34453e', '#6a7a70', '#9a3b32'
GRADE = {1: '#8f5428', 2: '#5a6676', 3: '#9a6214'}
FONT_PATHS = ['/System/Library/Fonts/Supplemental/Arial.ttf', '/System/Library/Fonts/Helvetica.ttc', '/Library/Fonts/Arial.ttf']

class Canvas:
    def __init__(self, w, h, s):
        self.s, self.w, self.h = s, w, h
        self.im = Image.new('RGBA', (w * s, h * s), (58, 70, 74, 255))
        self.d = ImageDraw.Draw(self.im)
        path = next((p for p in FONT_PATHS if Path(p).exists()), None)
        self.font = ImageFont.truetype(path, 8 * s) if path else ImageFont.load_default()
    def blit(self, x, y, u, v, w, h):
        piece = ATLAS.crop((u, v, u + w, v + h)).resize((w * self.s, h * self.s), Image.NEAREST)
        self.im.alpha_composite(piece, (x * self.s, y * self.s))
    def tile(self, x, y, w, h, u, v, tw, th):
        for j in range(0, h, th):
            for i in range(0, w, tw): self.blit(x + i, y + j, u, v, min(tw, w - i), min(th, h - j))
    def nine(self, x, y, w, h, u, v, size, c):
        m = size - 2 * c
        self.blit(x, y, u, v, c, c); self.blit(x + w - c, y, u + size - c, v, c, c)
        self.blit(x, y + h - c, u, v + size - c, c, c); self.blit(x + w - c, y + h - c, u + size - c, v + size - c, c, c)
        self.tile(x + c, y, w - 2 * c, c, u + c, v, m, c); self.tile(x + c, y + h - c, w - 2 * c, c, u + c, v + size - c, m, c)
        self.tile(x, y + c, c, h - 2 * c, u, v + c, c, m); self.tile(x + w - c, y + c, c, h - 2 * c, u + size - c, v + c, c, m)
        self.tile(x + c, y + c, w - 2 * c, h - 2 * c, u + c, v + c, m, m)
    def panel(self, x, y, w, h): self.nine(x, y, w, h, 0, 0, 24, 4)
    def card(self, x, y, w, h, sel=False): self.nine(x, y, w, h, 40 if sel else 24, 0, 16, 3)
    def icon(self, x, y, n): self.blit(x, y, n * 16, 32, 16, 16)
    def stars(self, x, y, ped):
        n = min(5, ped // 20 + 1)
        for i in range(5): self.blit(x + i * 10, y, 104 if i < n else 113, 0, 9, 9)
    def bar(self, x, y, w, a, v):
        self.blit(x, y, 0, 24, 2, 6); self.tile(x + 2, y, w - 4, 6, 2, 24, 4, 6); self.blit(x + w - 2, y, 6, 24, 2, 6)
        f = 0 if v <= 0 else max(1, min(w - 2, (w - 2) * min(v, 100) // 100))
        if f: self.tile(x + 1, y + 1, f, 4, 8 + a * 8, 24, 8, 4)
    def chip(self, x, y, g):
        if 1 <= g <= 3: self.blit(x, y, (g - 1) * 9, 48, 9, 9)
    def text(self, x, y, t, color=INK):
        if MC:
            rgb = tuple(int(color.lstrip('#')[i:i + 2], 16) for i in (0, 2, 4)); MC.draw(self.im, x, y, t, rgb, self.s)
        else: self.d.text((x * self.s, y * self.s - self.s), t, font=self.font, fill=color)
    def width(self, t): return MC.width(t) if MC else self.d.textlength(t, font=self.font) / self.s
    def button(self, x, y, w, h, label, active=True):
        s = self.s; base = '#6c6c6c' if active else '#4a4a4a'
        self.d.rectangle([x * s, y * s, (x + w) * s - 1, (y + h) * s - 1], fill='#000000')
        self.d.rectangle([(x + 1) * s, (y + 1) * s, (x + w - 1) * s - 1, (y + h - 1) * s - 1], fill='#a0a0a0' if active else '#707070')
        self.d.rectangle([(x + 2) * s, (y + 2) * s, (x + w - 1) * s - 1, (y + h - 1) * s - 1], fill='#555555' if active else '#3c3c3c')
        self.d.rectangle([(x + 2) * s, (y + 2) * s, (x + w - 2) * s - 1, (y + h - 2) * s - 1], fill=base)
        self.text(x + (w - self.width(label)) / 2, y + 6, label, '#ffffff' if active else '#a0a0a0')
    def tab(self, x, y, w, h, label, icon, selected):
        self.nine(x, y, w, h, 88 if selected else 56, 0, 16, 3)
        o = -1 if selected else 0
        self.icon(x + 4, y + (h - 16) // 2 + o, icon); self.text(x + 23, y + (h - 8) // 2 + o + 1, label, INK if selected else SOFT)

L = {}
def tr(key, *a):
    it = iter(a)
    return re.sub(r'%(\d+\$)?s', lambda m: str(next(it, '?')), L.get(key, key))
def grade_chip(c, x, y, g, expired=False):
    if expired: c.blit(x, y, 27, 48, 9, 9)
    else: c.chip(x, y, g)
def fit(c, x, y, t, color, maxw, tag):
    if c.width(t) > maxw: print('OVER', tag, int(c.width(t)), '>', maxw, t)
    c.text(x, y, t, color)
def bars(c, x, y, vals):
    for i, v in enumerate(vals):
        cx = x + i * 60; lab = tr(f'shop.mcyukkuri.ab.{i}'); c.text(cx, y, lab); c.text(cx + 54 - c.width(str(v)), y, str(v)); c.bar(cx, y + 10, 54, i, v)
        if c.width(lab) + c.width(str(v)) + 2 > 54: print('OVER ability', lab, v)
def button(c, x, y, w, h, label, active=True): c.button(x, y, w, h, label, active)

ROWS = [dict(g=3, sp='reimu', st=2, price=41, ped=95, ab=[88, 72, 65, 90], tags=(1, 2), hp=20, food=80, zone='display'),
        dict(g=2, sp='marisa', st=2, price=22, ped=70, ab=[60, 71, 48, 55], tags=(3,), hp=20, food=74, zone='display'),
        dict(g=2, exp=True, sp='house_marisa', st=3, price=120, ped=55, ab=[100, 100, 100, 100], tags=(5, 6), hp=20, food=90, zone='sold', factory=True)]
def species(sp): return L.get('shop.mcyukkuri.sp.' + sp) or L.get('entity.mcyukkuri.' + sp, sp)
def tagtext(t): return ' / '.join(tr(f'shop.mcyukkuri.tag.{k}') for k in t)

def new_canvas(w, h, s): c = Canvas(w, h, s); c.panel(0, 0, w, h); return c
def tabs(c, sel):
    for i in range(4): c.tab(8 + i * 82, 6, 78, 22, tr(f'shop.mcyukkuri.tab.{i}'), i, i == sel)
def footer(c, left=True): 
    if left: button(c, 8, 224, 40, 20, '<'); button(c, 52, 224, 40, 20, '>')
    button(c, 272, 224, 60, 20, tr('gui.done'))

def shop_live(s, focus=2):
    c = new_canvas(340, 250, s); tabs(c, 0)
    for i, r in enumerate(ROWS):
        y = 32 + i * 57; c.card(8, y, 256, 56, focus == i + 1); sold = r['zone'] == 'sold'
        grade_chip(c, 14, y + 4, r['g'], r.get('exp')); gn = tr(f'badge.mcyukkuri.profile.grade.{r["g"]}')
        head_pre = ''; x = 25
        txt = tr('shop.mcyukkuri.row.head2', gn, species(r['sp']), tr(f'human.mcyukkuri.stage.{r["st"]}'))
        fit(c, x, y + 5, txt, SOFT if r.get('exp') else INK, 256 - 6 - 49 - x - 3 + 8, 'row.head2')
        c.stars(264 - 6 - 49, y + 4, r['ped']); bars(c, 14, y + 17, r['ab'])
        fit(c, 14, y + 35, tagtext(r['tags']) + '  ' + tr('shop.mcyukkuri.vitals', r['hp'], r['food']), SOFT, 244, 'tags')
        z = tr('shop.mcyukkuri.zone.' + r['zone']) + ('  ' + tr('shop.mcyukkuri.origin.factory') if r.get('factory') else '')
        fit(c, 14, y + 44, z, WARN if sold else SOFT, 244, 'zone')
        button(c, 270, y + 4, 62, 20, tr('shop.mcyukkuri.buy_price', r['price']), not sold); button(c, 270, y + 28, 62, 20, tr('shop.mcyukkuri.locate'))
    footer(c); c.text(100, 230, tr('shop.mcyukkuri.page', 1, 3), SOFT); return c

def shop_consult(s):
    c = new_canvas(340, 250, s); tabs(c, 3); c.card(8, 32, 324, 178)
    c.text(16, 38, tr('shop.mcyukkuri.subject', 1, 3, species('reimu'), tr('badge.mcyukkuri.profile.grade.3'), tr('human.mcyukkuri.stage.2')))
    bars(c, 16, 52, [88, 72, 65, 90]); c.chip(16, 75, 3); c.stars(29, 75, 86); c.text(86, 76, tagtext((1, 2)), SOFT)
    c.text(16, 90, tr('shop.mcyukkuri.vitals3', 20, 80, 90), SOFT)
    fit(c, 16, 108, tr('badge.mcyukkuri.profile.routines', 5, 4, 6), INK, 308, 'routines'); fit(c, 16, 120, tr('badge.mcyukkuri.profile.growth', 86, 12, 30, 0), INK, 308, 'growth')
    c.text(16, 132, tr('shop.mcyukkuri.weakest', tr('shop.mcyukkuri.ability.2'))); c.text(16, 160, tr('shop.mcyukkuri.advice_title'))
    button(c, 100, 224, 160, 20, tr('shop.mcyukkuri.forecast')); footer(c); return c

def shop_goods(s):
    c = new_canvas(340, 250, s); tabs(c, 1); c.text(16, 36, tr('shop.mcyukkuri.category.0'))
    for i in range(4): c.card(8, 46 + i * 34, 324, 30); c.icon(14, 53 + i * 34, 1); button(c, 36, 52 + i * 34, 290, 20, tr('human.mcyukkuri.buy_supply', 'Regular grain', 8, 1))
    c.text(16, 184, tr('shop.mcyukkuri.trades_left', 8), SOFT); footer(c); c.text(100, 230, tr('shop.mcyukkuri.page', 1, 6), SOFT); return c

def exam(s, refuse=True):
    c = new_canvas(340, 250, s); c.icon(316, 6, 4); button(c, 8, 6, 40, 20, '<'); button(c, 52, 6, 40, 20, '>')
    fit(c, 100, 12, tr('exam.mcyukkuri.ui.subject', 1, 2, species('reimu'), tr('exam.mcyukkuri.ui.relation.0')), INK, 210, 'subject')
    c.card(8, 28, 324, 80); c.chip(16, 32, 2)
    fit(c, 27, 32, tr('exam.mcyukkuri.ui.grade', tr('badge.mcyukkuri.profile.grade.2'), tr('exam.mcyukkuri.ui.valid_days', 12)), INK, 300, 'grade')
    fit(c, 16, 42, tr('shop.mcyukkuri.abilities', 80, 60, 70, 75), INK, 308, 'abil'); fit(c, 16, 52, tr('exam.mcyukkuri.ui.vitals', 55, 12, 0, 0), SOFT, 308, 'vitals')
    fit(c, 16, 64, tr('exam.mcyukkuri.ui.hall') + '  ' + '  '.join(tr(f'shop.mcyukkuri.ability.{i}') + ' ✓' for i in range(4)), '#2f6b3f', 308, 'hall')
    for i in range(5):
        c.button(8, 112 + i * 22, 110, 20, tr(f'exam.mcyukkuri.btn.{i + 3}')); c.card(122, 111 + i * 22, 210, 22)
        t = tr(f'exam.mcyukkuri.hint.{i + 3}') ; t = tr('exam.mcyukkuri.ui.fee', 5) + '  ' + t
        words = t; lines = wrap(c, words, 198)
        if len(lines) > 2: print('OVER exam hint (>2 lines)', t)
        top = 111 + i * 22 + (7 if len(lines) == 1 else 3)
        for n, ln in enumerate(lines[:2]): c.text(128, top + n * 8, ln, SOFT)
    c.text(16, 230, tr('exam.mcyukkuri.ui.emeralds', 12), SOFT); button(c, 150, 224, 116, 20, tr('exam.mcyukkuri.btn.log')); button(c, 272, 224, 60, 20, tr('gui.done')); return c

def wrap(c, t, w):
    out, cur = [], ''
    for ch in t:
        if c.width(cur + ch) > w and cur: out.append(cur); cur = ch
        else: cur += ch
    return out + [cur]

def keeper(s):
    c = Canvas(300, 220, s); c.panel(0, 0, 300, 220)
    fit(c, 12, 10, tr('shop.mcyukkuri.keeper.status', 14, 16, 2, 2), INK, 276, 'status'); c.text(12, 30, tr('shop.mcyukkuri.keeper.studs', 2))
    c.card(8, 40, 284, 54); c.card(10, 42, 280, 12, True)
    for i, (g, sp, ped) in enumerate([(2, 'reimu', 64), (2, 'marisa', 41), (0, 'alice', 12)]):
        c.chip(14, 43 + i * 12, g); c.text(25, 44 + i * 12, tr(f'badge.mcyukkuri.profile.grade.{g}') + ' ' + species(sp) + f' {ped}', INK); c.stars(292 - 6 - 49, 43 + i * 12, ped)
    c.icon(8, 96, 6); c.text(28, 100, tr('shop.mcyukkuri.keeper.omiai')); c.card(8, 114, 284, 28)
    c.text(14, 118, tr('shop.mcyukkuri.keeper.subject', species('reimu'), 70, 1, 2)); c.text(14, 130, tr('shop.mcyukkuri.keeper.state.0', 6))
    button(c, 8, 144, 30, 20, '<'); button(c, 42, 144, 30, 20, '>'); button(c, 78, 144, 214, 20, tr('shop.mcyukkuri.keeper.book', 6)); button(c, 230, 192, 60, 20, tr('gui.done')); return c

def badge(s):
    c = new_canvas(360, 250, s); c.card(6, 6, 348, 116); c.icon(334, 8, 5)
    c.text(14, 10, tr('badge.mcyukkuri.subject', 'Reimu', 2, 1)); c.chip(14, 21, 3)
    fit(c, 25, 21, tr('badge.mcyukkuri.profile.grade', tr('badge.mcyukkuri.profile.grade.3'), tr('badge.mcyukkuri.profile.validity.days', 10), tr('badge.mcyukkuri.wear.1')), INK, 320, 'grade')
    rows = [('source', tr('badge.mcyukkuri.profile.source.1'), '3fa2'), ('abilities', 88, 72, 65, 90), ('routines', 5, 4, 6), ('growth', 86, 12, 30, 0)]
    for i, (k, *a) in enumerate(rows): fit(c, 14, 31 + i * 10, tr('badge.mcyukkuri.profile.' + k, *a), INK, 320, k)
    fit(c, 14, 71, tr('badge.mcyukkuri.profile.exam', tr('badge.mcyukkuri.profile.type.3'), tr('badge.mcyukkuri.profile.outcome.0'), tr('badge.mcyukkuri.profile.grade.2')), INK, 320, 'exam')
    fit(c, 14, 81, tr('badge.mcyukkuri.profile.scores', 80, 70, '-', '-'), INK, 320, 'scores'); c.text(14, 91, tr('badge.mcyukkuri.profile.idle'))
    fit(c, 14, 101, tr('badge.mcyukkuri.profile.hint'), INK, 320, 'hint')
    for n in range(6): button(c, 10 + n % 3 * 115, 126 + n // 3 * 22, 110, 20, tr(f'badge.mcyukkuri.action.{[5, 10, 8, 11, 12, 13][n]}'))
    for k in range(4): button(c, 10 + k * 58, 172, 55, 20, tr(f'pet.mcyukkuri.ui.lesson.{k}'))
    button(c, 240, 172, 110, 20, tr('gui.done')); c.card(6, 196, 348, 26)
    fit(c, 14, 200, tr('badge.mcyukkuri.profile.breed_partner', 'Marisa', 1, 2), INK, 330, 'partner'); fit(c, 14, 211, tr('badge.mcyukkuri.profile.permit_none', 0), INK, 330, 'permit')
    for k in range(3): button(c, 10 + k * 115, 224, 110, 20, tr(f'badge.mcyukkuri.action.{30 + k}'))
    return c

def viewport(c, w, h, s):
    """Centre the screen in a 480x270 GUI-px viewport (1080p, GUI scale 4) and flag overflow."""
    vp = Image.new('RGBA', (480 * s, 270 * s), (70, 100, 70, 255)); x, y = (480 - w) // 2, (270 - h) // 2
    vp.alpha_composite(c.im, (x * s, y * s)); d = ImageDraw.Draw(vp)
    d.rectangle((0, 0, 480 * s - 1, 270 * s - 1), outline=(255, 0, 0), width=2)
    if y < 0 or h > 270: d.text((6, 6), f'OVERFLOW: {h} > 270', fill=(255, 255, 0))
    return vp

if __name__ == '__main__':
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parents[2] / '.local' / 'previews' / 'shop-ui'
    out.mkdir(parents=True, exist_ok=True)
    langs = [sys.argv[2]] if len(sys.argv) > 2 else ['zh_cn', 'en_us']
    for lang in langs:
        L.clear(); L.update(json.loads((HERE.parents[2] / f'src/main/resources/assets/mcyukkuri/lang/{lang}.json').read_text(encoding='utf-8'))); L.setdefault('gui.done', {'zh_cn': '完成'}.get(lang, 'Done'))
        print('==', lang)
        for name, make, w, h in [('shop_live', shop_live, 340, 250), ('shop_goods', shop_goods, 340, 250), ('shop_consult', shop_consult, 340, 250), ('exam', exam, 340, 250), ('keeper', keeper, 300, 220), ('badge', badge, 360, 250)]:
            print('-', name)
            c = make(2); c.im.convert('RGB').save(out / f'{name}_{lang}_x2.png')
            viewport(make(2), w, h, 2).convert('RGB').save(out / f'{name}_{lang}_1080p-auto.png')
    print('ok', out)
