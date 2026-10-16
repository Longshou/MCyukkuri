"""Offline mock-ups of the shop screens from shop_ui.png (layout constants mirror client/ShopScreen etc.).

Usage: python preview_ui.py [out_dir]   (default .local/previews/shop-ui under the repo's main checkout).
Text uses a system TTF as a stand-in for the Minecraft font; this is a layout check, not a pixel-exact capture.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ATLAS = Image.open(HERE / 'shop_ui.png').convert('RGBA')
INK, SOFT, WARN = '#34453e', '#6a7a70', '#9a3b32'
GRADE = {1: '#8f5428', 2: '#5a6676', 3: '#ba781e'}
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
        self.d.text((x * self.s, y * self.s - self.s), t, font=self.font, fill=color)
    def width(self, t): return self.d.textlength(t, font=self.font) / self.s
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

def gtext(c, x, y, grade_name, rest, grade):
    c.text(x, y, grade_name, GRADE.get(grade, SOFT)); c.text(x + c.width(grade_name), y, rest)

ABIL = ['Man', 'Aca', 'Wit', 'Poi']
def bars(c, x, y, vals):
    for i, v in enumerate(vals):
        cx = x + i * 60; c.text(cx, y, ABIL[i]); c.text(cx + 54 - c.width(str(v)), y, str(v)); c.bar(cx, y + 10, 54, i, v)

ROWS = [(3, 'Gold', 'Reimu', 'Adult', 41, 86, [88, 72, 65, 90], 'Polite / Curious', 20, 80, 'On display', False),
        (2, 'Silver', 'Marisa', 'Adult', 22, 64, [60, 71, 48, 55], 'Selfish', 20, 74, 'On display', False),
        (1, 'Bronze', 'Orange', 'Child', 9, 31, [45, 30, 52, 12], 'Timid', 18, 60, 'In back', False),
        (0, 'No badge', 'Alice', 'Adult', 4, 12, [20, 33, 5, 0], 'Loves owner', 20, 90, 'Sold', True)]

def shop(tab, s, focus=2):
    c = Canvas(340, 294, s); c.panel(0, 0, 340, 294)
    names = ['Live', 'Goods', 'Buyback', 'Consult']
    for i in range(4): c.tab(8 + i * 82, 6, 78, 22, names[i], i, i == tab)
    if tab == 0:
        for i, r in enumerate(ROWS):
            y = 34 + i * 58; c.card(8, y, 256, 55, focus == i + 1)
            g, gn, sp, st, price, ped, ab, tags, hp, food, zone, sold = r
            c.chip(14, y + 4, g); gtext(c, 25, y + 5, gn, f' {sp} ({st}) - {price} E', g)
            c.stars(264 - 6 - 49, y + 4, ped); bars(c, 14, y + 18, ab)
            c.text(14, y + 33, f'{tags}  HP {hp}  Food {food}', SOFT); c.text(14, y + 44, zone, WARN if sold else SOFT)
            c.button(270, y + 4, 62, 20, 'Buy', not sold); c.button(270, y + 27, 62, 20, 'Locate')
        c.button(8, 268, 40, 20, '<'); c.button(52, 268, 40, 20, '>'); c.text(100, 274, 'Page 1 / 3', SOFT)
    elif tab == 1:
        c.text(16, 36, 'Food')
        items = [('Regular grain x8 - 1 E'), ('Budget grain x16 - 2 E'), ('Premium grain x8 - 3 E'), ('Orange juice x1 - 2 E')]
        for i, t in enumerate(items):
            c.card(8, 48 + i * 36, 324, 30); c.icon(14, 55 + i * 36, 1); c.button(36, 54 + i * 36, 290, 20, t)
        c.text(16, 198, 'Trades left today: 8', SOFT)
        c.button(8, 268, 40, 20, '<'); c.button(52, 268, 40, 20, '>'); c.text(100, 274, 'Page 1 / 6', SOFT)
    else:
        buy = tab == 2
        c.card(8, 32, 324, 120 if buy else 178)
        c.text(16, 38, '1/3 Reimu (Gold, Adult)'); bars(c, 16, 52, [88, 72, 65, 90]); c.chip(16, 75, 3); c.stars(29, 75, 86); c.text(86, 76, 'Polite', SOFT)
        c.text(16, 90, 'HP 20  Food 80  Hygiene 90', SOFT)
        if buy:
            c.text(16, 108, 'The shop offers 28 E'); c.text(16, 122, 'Stock 5/12, free pen available', SOFT); c.text(16, 136, 'Sold animals belong to the shop.', SOFT)
            c.button(100, 268, 160, 20, 'Sell to shop')
        else:
            c.text(16, 108, 'Bowl 5  Toilet 4  Sleep 6'); c.text(16, 120, 'Stress 12  Indulgence 30  Pedigree 86  Unauthorized 0')
            c.text(16, 132, 'Weakest: Wits'); c.text(16, 144, 'Forecast: bronze high, silver mid, gold low'); c.text(16, 160, 'Advice')
            for i, a in enumerate(['- Rest first', '- Wit practice below 80']): c.text(16, 172 + i * 12, a)
            c.button(100, 268, 160, 20, 'Exam forecast (1 E)')
        c.button(8, 268, 40, 20, '<'); c.button(52, 268, 40, 20, '>')
    c.button(272, 268, 60, 20, 'Done'); return c

def exam(s):
    c = Canvas(340, 282, s); c.panel(0, 0, 340, 282); c.icon(316, 6, 4)
    c.button(8, 6, 40, 20, '<'); c.button(52, 6, 40, 20, '>'); c.text(100, 12, '1/2 Reimu (yours)')
    c.card(8, 28, 324, 98); c.chip(16, 35, 2); gtext(c, 27, 35, 'Silver', '  valid 12 days', 2)
    c.text(16, 47, 'Man 80  Aca 60  Wit 70  Poi 75'); c.text(16, 59, 'HP 20  Food 80  Hygiene 90  Stress 10', SOFT)
    c.text(16, 75, 'Hall supports: Man ok  Aca ok  Wit ok  Poi ok', '#2f6b3f'); c.text(16, 93, 'Last: Oral exam - passed (Silver)'); c.text(16, 105, 'Scores 80 70 -- --', SOFT)
    for i, (n, hint) in enumerate([('Oral', 'Fee 3 E  Social skills'), ('Home', 'Fee 3 E  Routines'), ('Hide', 'Fee 4 E  Calm'), ('Care', 'Fee 5 E  Ability'), ('Gold', 'Not enough hall support')]):
        c.button(8, 128 + i * 22, 110, 20, n); c.card(122, 127 + i * 22, 210, 22); c.text(128, 134 + i * 22, hint, WARN if i == 4 else SOFT)
    c.text(16, 260, '12 emeralds', SOFT); c.button(100, 254, 130, 20, 'Exam log'); c.button(272, 254, 60, 20, 'Done'); return c

def keeper(s):
    c = Canvas(300, 220, s); c.panel(0, 0, 300, 220)
    c.text(12, 10, 'Shop 14/16  Nursery 2  Next restock in 2 day(s)'); c.text(12, 30, 'Studs: 2')
    c.button(236, 24, 24, 20, '<'); c.button(264, 24, 24, 20, '>')
    c.card(8, 40, 284, 54); c.card(10, 42, 280, 12, True)
    for i, (g, gn, sp, ped) in enumerate([(2, 'Silver', 'Reimu', 64), (2, 'Silver', 'Marisa', 41), (0, 'No badge', 'Alice', 12)]):
        c.chip(14, 43 + i * 12, g); gtext(c, 25, 44 + i * 12, gn, f' {sp} {ped}', g); c.stars(292 - 6 - 49, 43 + i * 12, ped)
    c.icon(8, 96, 6); c.text(28, 100, 'Matchmaking (omiai)'); c.card(8, 114, 284, 28)
    c.text(14, 118, 'Yours: Reimu, pedigree 70 (1/2)'); c.text(14, 130, 'Ready. Fee: 6 emeralds.')
    c.button(8, 144, 30, 20, '<'); c.button(42, 144, 30, 20, '>'); c.button(78, 144, 214, 20, 'Arrange a match (6 emeralds)'); c.button(230, 192, 60, 20, 'Done'); return c

def badge(s):
    c = Canvas(360, 304, s); c.panel(0, 0, 360, 304); c.card(6, 6, 348, 168); c.card(6, 246, 348, 28); c.icon(334, 8, 5)
    c.text(14, 10, 'Reimu #12 (Lv 2)'); c.chip(14, 26, 3); gtext(c, 25, 26, 'Gold', ' valid for 10 days, worn', 3)
    for i, t in enumerate(['Source: bred 3fa2', 'Man 88  Aca 72  Wit 65  Poi 90', 'Bowl 5  Toilet 4  Sleep 6', 'Pedigree 86  Stress 12  Indulgence 30 ', 'Last exam: Oral - passed (Silver)', 'Scores 80 70 -- --']): c.text(14, 43 + i * 16, t)
    c.text(14, 140, 'Idle'); c.text(190, 140, 'Lessons: idle'); c.text(14, 156, 'Hint: ...'); 
    for n in range(6): c.button(10 + n % 3 * 115, 176 + n // 3 * 24, 110, 20, ['Badge', 'Clip', 'Cut', 'Order', 'Sit', 'Follow'][n])
    for k in range(4): c.button(10 + k * 58, 224, 55, 20, ['Bowl', 'Toilet', 'Sleep', 'Bow'][k])
    c.button(240, 224, 110, 20, 'Done')
    c.text(14, 251, 'Breeding partner: Marisa (1/2)'); c.text(14, 262, 'Permit: none (0 births)')
    for k in range(3): c.button(10 + k * 115, 278, 110, 20, ['Breed', 'Permit', 'Revoke'][k])
    return c

if __name__ == '__main__':
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parents[2] / '.local' / 'previews' / 'shop-ui'
    out.mkdir(parents=True, exist_ok=True)
    for s in (2, 3):
        for name, c in [('shop_live', shop(0, s)), ('shop_goods', shop(1, s)), ('shop_buyback', shop(2, s)), ('shop_consult', shop(3, s)), ('exam', exam(s)), ('keeper', keeper(s)), ('badge', badge(s))]:
            c.im.convert('RGB').save(out / f'{name}_x{s}.png')
    print('ok', out)
