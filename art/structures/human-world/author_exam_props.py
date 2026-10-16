"""Original 16x16 pixel art and native block models for the exam-hall props: podium, hiding box and floor marks.

Light comes from the top left; every material uses 3-4 value steps; all pixels are drawn here (no external or vanilla pixels).
Sources go to products/, runtime copies to textures/block/; models, blockstates, loot tables and language keys are written next to them.
"""
from pathlib import Path
import io, json, hashlib, math, sys
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from PIL import Image
from asset_export_checks import preserve_equivalent_png
ART = Path(__file__).parent / 'products'
ASSET = ROOT / 'src/main/resources/assets/mcyukkuri'
KINDS = ['room', 'promise', 'actor', 'gold', 'trainee', 'examiner']
# kind: (mat base colour, glyph colour)
MAT = {'room': ('#5b9060', '#f1e9c9'), 'promise': ('#4d86b9', '#f1e9c9'), 'actor': ('#b45a3c', '#f1e9c9'),
       'gold': ('#c8942a', '#fff3c4'), 'trainee': ('#7b5eaa', '#f1e9c9'), 'examiner': ('#4e6f73', '#f1e9c9')}

def rgb(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
def shade(c, f): return tuple(max(0, min(255, round(v * f))) for v in c)
def noise(x, y, salt=0):
    n = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    n = (n ^ (n >> 13)) * 1274126177 & 0xffffffff
    return (n >> 8) & 255
def image(fn):
    im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16): im.putpixel((x, y), fn(x, y) + (255,))
    return im

def podium_top():
    wood = [rgb(h) for h in ('#e0c690', '#d3b47a', '#c0a066', '#a98a52')]
    dark = rgb('#3c5a5f')
    def px(x, y):
        # painted standing frame (2px stroke, inset 3px) and a 4px-wide chevron on the north edge = FACING side
        frame = 3 <= x <= 12 and 3 <= y <= 12 and not (5 <= x <= 10 and 5 <= y <= 10)
        chevron = (y == 0 and x in (7, 8)) or (y == 1 and x in (6, 7, 8, 9)) or (y == 2 and x in (5, 6, 9, 10))
        if frame or chevron: return dark if (x + y) % 5 else shade(dark, 1.2)
        if 5 <= x <= 10 and 5 <= y <= 10: return rgb('#ecdfb4') if (x + y) % 4 else rgb('#e3d3a0')
        i = 1 if y % 4 < 3 else 2                      # plank face / groove
        if noise(x, y, 3 + y // 4) < 40 and i == 1: i = 0
        if x == 0 or y == 0: i = 0                     # lit top-left rim
        if x == 15 or y == 15: i = 3                   # shaded bottom-right rim
        return wood[i]
    return image(px)

def podium_side():
    teal = [rgb(h) for h in ('#527a80', '#3c5a5f', '#2f484c', '#223539')]
    def px(x, y):
        # side faces are 7/16 high: rows 9..15 are visible (auto uv); lit top edge, dark foot
        i = 0 if y == 9 else 3 if y == 15 else 1 if y < 14 else 2
        if noise(x, y, 11) < 36 and 9 < y < 15: i = 2
        return teal[i]
    return image(px)

def cardboard():
    base = [rgb(h) for h in ('#cf9f68', '#bd8c56', '#a87a47', '#8c6338')]
    tape = [rgb(h) for h in ('#e6cf9f', '#d6bb88', '#bfa070')]
    def px(x, y):
        # vertical corrugation (2px ribs), a seam flap near the top and a tape band across the middle
        rib = (x // 2) % 2
        i = 0 if rib == 0 else 1
        if noise(x, y, 5) < 36: i = min(3, i + 1)
        if y in (0, 1): i = 0 if y == 0 else 1
        if y == 15: i = 3
        if y == 3: i = 2                                  # flap crease
        if 6 <= y <= 8:
            t = 0 if y == 6 else 1 if y == 7 else 2
            return tape[t]
        if x == 0: i = max(0, i - 1)
        if x == 15: i = 3
        return base[i]
    return image(px)

def mat(kind):
    base_hex, glyph_hex = MAT[kind]
    base, glyph = rgb(base_hex), rgb(glyph_hex)
    light, dark, edge = shade(base, 1.18), shade(base, .82), shade(base, .45)
    g = set()
    cx = cy = 7.5
    for y in range(16):
        for x in range(16):
            dx, dy = x - cx, y - cy
            if kind == 'room':
                roof = 3 <= y <= 6 and abs(dx) <= y - 2.5
                body = 7 <= y <= 11 and 5 <= x <= 10 and not (7 <= x <= 8 and y >= 9)
                hit = roof or body
            elif kind == 'promise':
                d = math.hypot(dx, dy); hit = 3.2 <= d <= 5.2 or d < 1.5
            elif kind == 'actor':
                hit = 3 <= x <= 12 and 3 <= y <= 12 and (abs(x - y) <= 1 or abs(x + y - 15) <= 1)
            elif kind == 'gold':
                hit = abs(dx) + abs(dy) <= 5.2 and abs(dx) + abs(dy) >= 1.6
            elif kind == 'trainee':
                hit = 3 <= y <= 12 and abs(dx) <= (y - 2.5) * .62 + .2
            else:
                d = math.hypot(dx / 5.8, dy / 3.6); hit = (.62 <= d <= 1.0 and 3 <= y <= 12) or math.hypot(dx, dy) < 1.9
            if hit: g.add((x, y))
    def px(x, y):
        if x in (0, 15) or y in (0, 15): return edge
        if x == 1 or y == 1: return light
        if x == 14 or y == 14: return dark
        if (x, y) in g: return glyph
        if (x - 1, y - 1) in g and kind != 'gold': return shade(base, .66)   # soft drop shadow, down-right
        n = noise(x, y, 17 + KINDS.index(kind))
        return light if n < 22 else dark if n > 232 else base
    return image(px)

def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True); path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
def write_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.is_file() or path.read_bytes() != data: path.write_bytes(data)
def write_png(path, im):
    stream = io.BytesIO(); im.save(stream, format='PNG')
    data = preserve_equivalent_png(path, stream.getvalue()); write_bytes(path, data); return data

def cube(a, b, tex, faces=('north', 'south', 'east', 'west', 'up', 'down')):
    """Element with explicit uv (needed when the element leaves the 0..16 block range)."""
    dx, dy, dz = b[0] - a[0], b[1] - a[1], b[2] - a[2]; size = {'north': (dx, dy), 'south': (dx, dy), 'east': (dz, dy), 'west': (dz, dy), 'up': (dx, dz), 'down': (dx, dz)}
    return {'from': a, 'to': b, 'faces': {f: {'texture': '#' + tex, 'uv': [0, 0, min(16, size[f][0]), min(16, size[f][1])]} for f in faces}}

def language(lang):
    """lang: zh_cn, en_us or ja_jp; the three exam props use the same names as item and block."""
    names = {'exam_podium': ('答题台', 'Exam Podium', '解答台'), 'exam_hide_box': ('躲藏箱', 'Exam Hiding Box', 'かくれ箱'),
             'exam_mark': ('考位标记', 'Exam Floor Mark', '試験位置マーク')}
    i = ['zh_cn', 'en_us', 'ja_jp'].index(lang); keys = {}
    for id, tri in names.items():
        for prefix in ('item.', 'block.'): keys[prefix + 'mcyukkuri.' + id] = tri[i]
    return keys

def export():
    records = {}
    textures = {'exam_podium_top': podium_top(), 'exam_podium_side': podium_side(), 'exam_hide_box': cardboard()}
    for k in KINDS: textures['exam_mark_' + k] = mat(k)
    for name, im in textures.items():
        source = ART / (name + '.png'); data = write_png(source, im)
        target = ASSET / 'textures/block' / source.name; write_bytes(target, data)
        records[name] = {'source': source.relative_to(ROOT).as_posix(), 'target': target.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(data).hexdigest()}
    models = ASSET / 'models'
    rotations = [('north', 0), ('east', 90), ('south', 180), ('west', 270)]
    # Podium: 3/16 slab, painted top (arrow tab on the north edge = FACING), wood sides.
    write(models / 'block/exam_podium.json', {'parent': 'minecraft:block/block', 'textures': {'particle': 'mcyukkuri:block/exam_podium_side', 'top': 'mcyukkuri:block/exam_podium_top', 'side': 'mcyukkuri:block/exam_podium_side'},
        'elements': [{'from': [0, 0, 0], 'to': [16, 7, 16], 'faces': {'up': {'texture': '#top'}, 'down': {'texture': '#side'}, **{f: {'texture': '#side'} for f in ('north', 'south', 'east', 'west')}}}]})
    write(ASSET / 'blockstates/exam_podium.json', {'variants': {f'facing={f}': {'model': 'mcyukkuri:block/exam_podium', 'y': y} for f, y in rotations}})
    # Hide box: open north side; two 1/16 side walls, back wall and roof, matching ExamHideBoxBlock collision.
    # Visible mesh is wider than the collision (-3..19 = 22/16) so Reimu (17.4 units) and Marisa with hat (20.3) stay inside the walls.
    parts = [cube([-3, 0, 0], [-2, 18, 19], 'box'), cube([18, 0, 0], [19, 18, 19], 'box'), cube([-2, 0, 18], [18, 18, 19], 'box', ('north', 'south', 'east', 'west')),
             cube([-3, 17, 0], [19, 18, 19], 'box')]
    write(models / 'block/exam_hide_box.json', {'parent': 'minecraft:block/block', 'textures': {'particle': 'mcyukkuri:block/exam_hide_box', 'box': 'mcyukkuri:block/exam_hide_box'}, 'elements': parts})
    write(ASSET / 'blockstates/exam_hide_box.json', {'variants': {f'facing={f}': {'model': 'mcyukkuri:block/exam_hide_box', 'y': y} for f, y in rotations}})
    # Marks: one 1/16 mat model per kind.
    for k in KINDS:
        write(models / f'block/exam_mark_{k}.json', {'parent': 'minecraft:block/block', 'textures': {'particle': f'mcyukkuri:block/exam_mark_{k}', 'top': f'mcyukkuri:block/exam_mark_{k}'},
            'elements': [{'from': [0, 0, 0], 'to': [16, 1, 16], 'faces': {f: {'texture': '#top'} for f in ('north', 'south', 'east', 'west', 'up')}}]})
    write(ASSET / 'blockstates/exam_mark.json', {'variants': {f'kind={k}': {'model': f'mcyukkuri:block/exam_mark_{k}'} for k in KINDS}})
    write(models / 'item/exam_podium.json', {'parent': 'mcyukkuri:block/exam_podium'})
    write(models / 'item/exam_hide_box.json', {'parent': 'mcyukkuri:block/exam_hide_box'})
    write(models / 'item/exam_mark.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'mcyukkuri:block/exam_mark_room'}})   # flat icon: a 1/16 slab is unreadable in the GUI
    for name in ('exam_podium', 'exam_hide_box', 'exam_mark'):
        write(ROOT / 'src/main/resources/data/mcyukkuri/loot_table/blocks' / f'{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'mcyukkuri:' + name}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    write(ART / 'exam-props-manifest.json', {'status': 'pending_visual_approval', 'author': 'Original procedural pixel art; no external source pixels.',
        'geometry': 'podium 7/16 slab; hide box 1/16 walls + roof, interior 14/16 wide and 15/16 high, open side = FACING; marks 1/16 mats, kind in ' + ','.join(KINDS), 'assets': records})
    return records

if __name__ == '__main__': export()
