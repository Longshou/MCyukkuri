"""Original 16x16 pixel art and native block model for the exam-hall podium.

Light comes from the top left; every material uses 3-4 value steps; all pixels are drawn here (no external or vanilla pixels).
Sources go to products/, runtime copies to textures/block/; model, blockstate, loot table and language keys are written next to them.
"""
from pathlib import Path
import io, json, hashlib, sys
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from PIL import Image
from asset_export_checks import preserve_equivalent_png
ART = Path(__file__).parent / 'products'
ASSET = ROOT / 'src/main/resources/assets/mcyukkuri'

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

def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True); path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
def write_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.is_file() or path.read_bytes() != data: path.write_bytes(data)
def write_png(path, im):
    stream = io.BytesIO(); im.save(stream, format='PNG')
    data = preserve_equivalent_png(path, stream.getvalue()); write_bytes(path, data); return data

def language(lang):
    """lang: zh_cn, en_us or ja_jp; the podium uses the same name as item and block."""
    names = {'exam_podium': ('答题台', 'Exam Podium', '解答台')}
    i = ['zh_cn', 'en_us', 'ja_jp'].index(lang); keys = {}
    for id, tri in names.items():
        for prefix in ('item.', 'block.'): keys[prefix + 'mcyukkuri.' + id] = tri[i]
    return keys

def export():
    records = {}
    textures = {'exam_podium_top': podium_top(), 'exam_podium_side': podium_side()}
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
    write(models / 'item/exam_podium.json', {'parent': 'mcyukkuri:block/exam_podium'})
    write(ROOT / 'src/main/resources/data/mcyukkuri/loot_table/blocks/exam_podium.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'mcyukkuri:exam_podium'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    write(ART / 'exam-props-manifest.json', {'status': 'pending_visual_approval', 'author': 'Original procedural pixel art; no external source pixels.',
        'geometry': 'podium 7/16 slab', 'assets': records})
    return records

if __name__ == '__main__': export()
