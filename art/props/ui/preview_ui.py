"""Offline mock-ups of the shop screens from shop_ui.png (layout constants mirror client/ShopScreen etc.).

Usage: python preview_ui.py [out_dir] [lang]   (default .local/previews/shop-ui, lang zh_cn, en_us or ja_jp; all three when omitted).
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
FONT_PATHS = ['C:/Windows/Fonts/msyh.ttc', '/System/Library/Fonts/STHeiti Light.ttc',
              '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
              '/System/Library/Fonts/Supplemental/Arial.ttf', '/Library/Fonts/Arial.ttf']

NARROW = {' ': 4, '!': 2, '"': 4, "'": 2, '(': 4, ')': 4, '*': 4, ',': 2, '.': 2, ':': 2, ';': 2, '<': 5, '>': 5, '@': 7, 'I': 4, '[': 4, ']': 4,
          'f': 5, 'i': 2, 'k': 5, 'l': 3, 't': 4, '`': 3, '{': 4, '|': 2, '}': 4, '~': 7}
def est_width(t):
    """Vanilla font advance estimate without the real font: ASCII bitmap widths, 9 px for CJK/kana, 6 px for other symbols."""
    w = 0
    for ch in t:
        o = ord(ch)
        w += NARROW.get(ch, 6) if o < 128 else 6 if ch in '\u00b7\u00d7\u2713\u2717\u2192' else 9
    return w

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
    def width(self, t): return MC.width(t) if MC else est_width(t)
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
def bars(c, x, y, vals, cell=60, nums=False):
    for i, v in enumerate(vals):
        cx = x + i * cell; lab = tr(f'shop.mcyukkuri.ab.{i}'); c.text(cx, y, lab)
        if nums: c.text(cx + cell - 6 - c.width(str(v)), y, str(v))
        c.bar(cx, y + 10, cell - 6, i, v)
        if c.width(lab) + (c.width(str(v)) + 2 if nums else 0) > cell - 6: print('OVER ability', lab, v)
def button(c, x, y, w, h, label, active=True):
    if c.width(label) > w - 6: print('OVER button', int(c.width(label)), '>', w - 6, label)
    c.button(x, y, w, h, label, active)

def level(v): return tr('shop.mcyukkuri.level.' + str(0 if v < 30 else 1 if v < 60 else 2))
ROWS = [dict(g=3, sp='reimu', st=2, price=41, ped=95, ab=[88, 72, 65, 90], tags=(1, 2), hp=10, food=80, zone='display'),
        dict(g=2, sp='marisa', st=2, price=22, ped=70, ab=[60, 71, 48, 55], tags=(3,), hp=4, food=20, zone='back'),
        dict(g=2, exp=True, sp='house_marisa', st=3, price=120, ped=55, ab=[100, 100, 100, 100], tags=(5, 6), hp=10, food=90, zone='sold', factory=True)]
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
        x = 25
        txt = tr('shop.mcyukkuri.row.head2', gn, species(r['sp']), tr(f'human.mcyukkuri.stage.{r["st"]}'))
        fit(c, x, y + 5, txt, SOFT if r.get('exp') else INK, 256 - 6 - 49 - x - 3 + 8, 'row.head2')
        c.stars(264 - 6 - 49, y + 4, r['ped']); bars(c, 14, y + 17, r['ab'])
        tx = 14; t = tagtext(r['tags']); c.text(tx, y + 35, t, SOFT); tx += c.width(t) + 10
        if r['hp'] < 6: w = tr('shop.mcyukkuri.warn.hurt'); c.text(tx, y + 35, w, WARN); tx += c.width(w) + 8
        if r['food'] < 30: c.text(tx, y + 35, tr('shop.mcyukkuri.warn.hungry'), WARN)
        zone = [tr('shop.mcyukkuri.zone.' + r['zone'])] if r['zone'] in ('sold', 'display') else []
        if r.get('factory'): zone.append(tr('shop.mcyukkuri.origin.factory'))
        if zone: fit(c, 14, y + 44, '  '.join(zone), WARN if sold else SOFT, 244, 'zone')
        button(c, 270, y + 3, 62, 16, tr('shop.mcyukkuri.buy_price', r['price']), not sold); button(c, 270, y + 20, 62, 16, tr('shop.mcyukkuri.locate')); button(c, 270, y + 37, 62, 16, tr('shop.mcyukkuri.about'))
    footer(c); c.text(100, 230, tr('shop.mcyukkuri.page', 1, 3), SOFT); return c

def subject_block(c, grade, sp, ab, ped, tags, hp, food, hyg):
    fit(c, 16, 38, tr('shop.mcyukkuri.subject', 1, 3, species(sp), tr(f'badge.mcyukkuri.profile.grade.{grade}'), tr('human.mcyukkuri.stage.2')), INK, 308, 'subject')
    bars(c, 16, 52, ab, cell=78, nums=True); c.chip(16, 75, grade); c.stars(29, 75, ped); c.text(86, 76, tagtext(tags), SOFT)
    fit(c, 16, 90, tr('shop.mcyukkuri.vitals3', level(min(100, hp * 10)), level(food), level(hyg)), SOFT, 308, 'vitals3')

def shop_consult(s):
    c = new_canvas(340, 250, s); tabs(c, 3); c.card(8, 32, 324, 178)
    subject_block(c, 3, 'reimu', [88, 72, 65, 90], 86, (1, 2), 10, 80, 90)
    fit(c, 16, 106, tr('badge.mcyukkuri.profile.routines', 5, 4, 6), INK, 308, 'routines'); fit(c, 16, 118, tr('badge.mcyukkuri.profile.growth', level(12), level(45)), INK, 308, 'growth')
    fit(c, 16, 134, tr('shop.mcyukkuri.forecast_result', tr('shop.mcyukkuri.forecast.1'), tr('shop.mcyukkuri.forecast.2'), tr('shop.mcyukkuri.forecast.3')), INK, 308, 'forecast')
    c.text(16, 154, tr('shop.mcyukkuri.advice_title')); c.text(130, 154, tr('shop.mcyukkuri.title', tr('title.mcyukkuri.pet.1')))
    for n, k in enumerate((12, 1, 5)): fit(c, 16, 166 + n * 12, '- ' + tr(f'shop.mcyukkuri.advice.{k}'), INK, 308, f'advice.{k}')
    button(c, 100, 224, 160, 20, tr('shop.mcyukkuri.forecast')); footer(c); return c

def shop_buyback(s):
    c = new_canvas(340, 250, s); tabs(c, 2); c.card(8, 32, 324, 120)
    subject_block(c, 2, 'marisa', [60, 71, 48, 55], 70, (3,), 10, 74, 90)
    fit(c, 16, 108, tr('shop.mcyukkuri.offer', 11), INK, 308, 'offer'); fit(c, 16, 122, tr('shop.mcyukkuri.sell_state.0', 6, 12), SOFT, 308, 'sell_state')
    fit(c, 16, 136, tr('shop.mcyukkuri.sell_hint'), SOFT, 308, 'sell_hint'); button(c, 100, 224, 160, 20, tr('shop.mcyukkuri.sell')); footer(c); return c

def shop_goods(s):
    c = new_canvas(340, 250, s); tabs(c, 1); c.text(16, 36, tr('shop.mcyukkuri.category.0'))
    for i in range(4): c.card(8, 46 + i * 34, 324, 30); c.icon(14, 53 + i * 34, 1); button(c, 36, 52 + i * 34, 290, 20, tr('human.mcyukkuri.buy_supply', tr('item.mcyukkuri.oil_grain_regular'), 8, 1))
    c.text(16, 184, tr('shop.mcyukkuri.trades_left', 8), SOFT); footer(c); c.text(100, 230, tr('shop.mcyukkuri.page', 1, 6), SOFT); return c

def wrap(c, t, w):
    out, cur = [], ''
    for ch in t:
        if c.width(cur + ch) > w and cur: out.append(cur); cur = ch
        else: cur += ch
    return out + [cur]

def exam(s, refuse=True):
    c = new_canvas(340, 250, s); c.icon(316, 6, 4); button(c, 8, 6, 40, 20, '<'); button(c, 52, 6, 40, 20, '>')
    fit(c, 100, 12, tr('exam.mcyukkuri.ui.subject', 1, 2, species('reimu'), tr('exam.mcyukkuri.ui.relation.1')), INK, 210, 'subject')
    c.card(8, 28, 324, 80); c.chip(16, 32, 2)
    fit(c, 27, 32, tr('exam.mcyukkuri.ui.grade', tr('badge.mcyukkuri.profile.grade.2'), tr('exam.mcyukkuri.ui.valid_days', 12)), INK, 300, 'grade')
    fit(c, 16, 42, tr('shop.mcyukkuri.abilities', 80, 60, 70, 75), INK, 308, 'abil')
    ped = tr('badge.mcyukkuri.profile.pedigree'); c.text(16, 52, ped, SOFT); px = 16 + c.width(ped) + 4; c.stars(px, 51, 62)
    fit(c, px + 49 + 8, 52, tr('exam.mcyukkuri.ui.vitals', level(12)) + '  ' + tr('badge.mcyukkuri.profile.unauthorized', 1), SOFT, 308 - px - 57, 'vitals')
    fit(c, 16, 64, tr('exam.mcyukkuri.ui.hall') + '  ' + '  '.join(tr(f'shop.mcyukkuri.ability.{i}') + ' ✓' for i in range(4)), '#2f6b3f', 308, 'hall')
    fit(c, 16, 77, tr('exam.mcyukkuri.ui.last', tr('exam.mcyukkuri.type.4'), tr('exam.mcyukkuri.outcome.0', tr('badge.mcyukkuri.profile.grade.2'))), INK, 308, 'last')
    fit(c, 16, 87, tr('exam.mcyukkuri.ui.last_scores', 80, 70, '-', '-'), SOFT, 308, 'last_scores')
    for i in range(5):
        c.button(8, 112 + i * 22, 110, 20, tr(f'exam.mcyukkuri.btn.{i + 3}')); c.card(122, 111 + i * 22, 210, 22)
        if c.width(tr(f'exam.mcyukkuri.btn.{i + 3}')) > 104: print('OVER exam button', tr(f'exam.mcyukkuri.btn.{i + 3}'))
        fee = [0, 9, 4, 2, 1][i]
        t = (tr('exam.mcyukkuri.ui.free') if fee == 0 else tr('exam.mcyukkuri.ui.fee', fee)) + '  ' + tr(f'exam.mcyukkuri.hint.{i + 3}')
        lines = wrap(c, t, 198)
        if len(lines) > 2: print('OVER exam hint (>2 lines)', t)
        top = 111 + i * 22 + (7 if len(lines) == 1 else 2)
        for n, ln in enumerate(lines[:2]): c.text(128, top + n * 9, ln, SOFT)
    c.text(16, 230, tr('exam.mcyukkuri.ui.emeralds', 12), SOFT); button(c, 150, 224, 116, 20, tr('exam.mcyukkuri.btn.log')); button(c, 272, 224, 60, 20, tr('gui.done')); return c

def keeper(s):
    c = Canvas(300, 220, s); c.panel(0, 0, 300, 220)
    fit(c, 12, 10, tr('shop.mcyukkuri.keeper.status', 14, 16, 2, 2), INK, 276, 'status'); c.text(12, 30, tr('shop.mcyukkuri.keeper.studs', 2))
    c.card(8, 40, 284, 54); c.card(10, 42, 280, 12, True)
    for i, (g, sp, ped) in enumerate([(2, 'reimu', 64), (2, 'marisa', 41), (0, 'alice', 12)]):
        c.chip(14, 43 + i * 12, g); c.text(25, 44 + i * 12, tr(f'badge.mcyukkuri.profile.grade.{g}') + ' ' + species(sp), INK); c.stars(292 - 6 - 49, 43 + i * 12, ped)
    c.icon(8, 96, 6); c.text(28, 100, tr('shop.mcyukkuri.keeper.omiai')); c.card(8, 114, 284, 28)
    c.text(14, 118, tr('shop.mcyukkuri.keeper.subject', species('reimu'), 1, 2)); c.stars(292 - 6 - 49, 117, 70)
    fit(c, 14, 130, tr('shop.mcyukkuri.keeper.state.0', 6), INK, 270, 'state')
    button(c, 8, 144, 30, 20, '<'); button(c, 42, 144, 30, 20, '>'); button(c, 78, 144, 214, 20, tr('shop.mcyukkuri.keeper.book', 6)); button(c, 230, 192, 60, 20, tr('gui.done')); return c

def badge(s):
    c = new_canvas(360, 250, s); c.card(6, 6, 348, 116); c.icon(334, 8, 5)
    head = tr('badge.mcyukkuri.subject', 'Reimu', 2, 1); c.text(14, 10, head)
    title = tr('badge.mcyukkuri.profile.title', tr('title.mcyukkuri.pet.1')); c.text(max(14 + c.width(head) + 8, 328 - c.width(title)), 10, title)
    c.chip(14, 21, 3)
    fit(c, 25, 21, tr('badge.mcyukkuri.profile.grade', tr('badge.mcyukkuri.profile.grade.3'), tr('badge.mcyukkuri.profile.validity.days', 10), tr('badge.mcyukkuri.wear.1')), INK, 300, 'grade')
    fit(c, 14, 31, tr('badge.mcyukkuri.profile.source_no', tr('badge.mcyukkuri.profile.source.4'), 'No.3FA2'), INK, 320, 'source')
    fit(c, 14, 41, tr('badge.mcyukkuri.profile.abilities', 88, 72, 65, 90), INK, 320, 'abilities')
    fit(c, 14, 51, tr('badge.mcyukkuri.profile.routines', 5, 4, 6), INK, 320, 'routines')
    ped = tr('badge.mcyukkuri.profile.pedigree'); c.text(14, 61, ped); px = 14 + c.width(ped) + 4; c.stars(px, 60, 86)
    fit(c, px + 57, 61, tr('badge.mcyukkuri.profile.growth', level(12), level(45)), INK, 340 - px - 57, 'growth')
    fit(c, 14, 71, tr('badge.mcyukkuri.profile.exam', tr('badge.mcyukkuri.profile.type.3'), tr('badge.mcyukkuri.profile.outcome.0'), tr('badge.mcyukkuri.profile.grade.2')), INK, 320, 'exam')
    fit(c, 14, 81, tr('badge.mcyukkuri.profile.scores', 80, 70, '-', '-'), INK, 320, 'scores')
    fit(c, 14, 91, tr('badge.mcyukkuri.profile.status', tr('pet.mcyukkuri.ui.state.running', tr('pet.mcyukkuri.ui.lesson.0'))) + ' · ' + tr('pet.mcyukkuri.ui.rest', 8) + ' · ' + tr('pet.mcyukkuri.ui.today', 3), INK, 320, 'status')
    fit(c, 14, 101, tr('badge.mcyukkuri.profile.hint'), INK, 320, 'hint')
    fit(c, 14, 111, tr('badge.mcyukkuri.profile.owner_line', level(35), tr('badge.mcyukkuri.profile.owner_state.0')), INK, 320, 'owner_line')
    for n in range(6): button(c, 10 + n % 3 * 115, 126 + n // 3 * 22, 110, 20, tr(f'badge.mcyukkuri.action.{[5, 10, 8, 11, 12, 13][n]}'))
    for k in range(4): button(c, 10 + k * 58, 172, 55, 20, tr(f'pet.mcyukkuri.ui.lesson.{k}'))
    button(c, 240, 172, 110, 20, tr('gui.done')); c.card(6, 196, 348, 26)
    fit(c, 14, 200, tr('badge.mcyukkuri.profile.breed_partner', 'Marisa', 1, 2), INK, 330, 'partner')
    fit(c, 14, 211, tr('badge.mcyukkuri.profile.permit', 'Marisa', tr('badge.mcyukkuri.profile.permit_left.hours', 18), 0), INK, 330, 'permit')
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
    langs = [sys.argv[2]] if len(sys.argv) > 2 else ['zh_cn', 'en_us', 'ja_jp']
    for lang in langs:
        L.clear(); L.update(json.loads((HERE.parents[2] / f'src/main/resources/assets/mcyukkuri/lang/{lang}.json').read_text(encoding='utf-8'))); L.setdefault('gui.done', {'zh_cn': '完成', 'ja_jp': '完了'}.get(lang, 'Done'))
        print('==', lang)
        for name, make, w, h in [('shop_live', shop_live, 340, 250), ('shop_goods', shop_goods, 340, 250), ('shop_buyback', shop_buyback, 340, 250), ('shop_consult', shop_consult, 340, 250), ('exam', exam, 340, 250), ('keeper', keeper, 300, 220), ('badge', badge, 360, 250)]:
            print('-', name)
            c = make(2); c.im.convert('RGB').save(out / f'{name}_{lang}_x2.png')
            viewport(make(2), w, h, 2).convert('RGB').save(out / f'{name}_{lang}_1080p-auto.png')
    print('ok', out)
