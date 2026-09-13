"""Build cute N041/N042 draft eyes while preserving approved facial components."""
from pathlib import Path
from PIL import Image
import copy
import hashlib
import json
import shutil

R = Path(__file__).resolve().parents[1]
D = R / 'drafts/expression-review/reuse-batch'
S = D / 'round4'
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()

def save(p, data):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

if (S / 'approvals-applied.json').exists():
    raise SystemExit('This presentation is accepted; do not overwrite it with draft generation.')
if not (S / 'before.json').exists():
    manifest = read(D / 'reuse-manifest.json')
    save(S / 'before.json', {e['id']: read(R / e['preset']) for e in manifest['entries']})
    save(S / 'before-manifest.json', manifest)
    shutil.copy2(R / 'previews/current/pending-batch.png', S / 'before-overview.png')
    shutil.copy2(R / 'reports/current/expression-component-reuse.json', S / 'before-report.json')

before = read(S / 'before.json')
manifest = read(S / 'before-manifest.json')
face = {e['name']: e for e in read(R / 'components/common/face.json')['elements']}
palette = {'.': (0, 0, 0, 0), 'x': (72, 53, 46, 255),
           'w': (243, 235, 218, 255), 'b': (139, 100, 63, 255),
           'p': (82, 58, 42, 255), 'h': (255, 250, 230, 255)}
# Round pupil, a small highlight and one simple contour. Inner corner is higher.
rows = [
    '...............',
    '...............',
    '...............',
    '.......xxxxx...',
    '....xxxpppppx..',
    '...xwpphhpppx..',
    '...xwpphhpppx..',
    '...xwpppppppx..',
    '....xppbbppx...',
    '.....xbbbbx....',
    '......xxxx.....',
    '...............',
]
assert all(len(row) == 15 for row in rows)
left = Image.new('RGBA', (15, 12))
for y, row in enumerate(rows):
    for x, c in enumerate(row):
        left.putpixel((x, y), palette[c])
eyes = {}
for side, im in [('left', left), ('right', left.transpose(Image.Transpose.FLIP_LEFT_RIGHT))]:
    path = S / 'parts' / (hashlib.sha256(im.tobytes()).hexdigest()[:20] + '.png')
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path)
    eyes[side] = path.relative_to(R).as_posix()

def visible_bounds(p, slot):
    e = face[slot]
    d = p.get('geometry_offsets', {}).get(slot, {})
    cy = (e['from'][1] + e['to'][1]) / 2
    scale = d.get('scale', [1] * 3)[1]
    shift = d.get('position', [0] * 3)[1]
    with Image.open(R / p['parts'][slot]) as im:
        box, h = im.getbbox(), im.height
    return {name: cy + (e['to'][1] - (e['to'][1] - e['from'][1]) * row / h - cy) * scale + shift
            for name, row in [('top', box[1]), ('bottom', box[3])]}

for entry in manifest['entries']:
    id = entry['id']
    if id not in ['side_teary', 'side_teary_b']:
        continue
    p = copy.deepcopy(before[id])
    for side in ['left', 'right']:
        slot = 'face_eye_' + side
        p['parts'][slot] = eyes[side]
        tear = 'face_tear_' + side
        p['geometry_offsets'][tear]['position'][1] = 0
        p['geometry_offsets'][tear]['position'][1] = round(
            visible_bounds(p, slot)['bottom'] + .12 - visible_bounds(p, tear)['top'], 5)
    p['recipe'].update(source='authored-cute-lament-with-approved-mouth-brows-and-tears',
                       eyes='round-large-pupil-simple-outline-small-highlight',
                       emotion='可爱的委屈欲哭；抬眉头、垂眼角，大瞳孔保持清醒感')
    p['status'] = 'pending-review'
    p['art'] = 'Project-authored cute pixel eyes; approved mouth, brows and tear pixels retained; awaiting user review'
    p['review_decision'] = dict(status='pending-review', note='按用户“偏可爱、不要太写实”反馈简化眼型，保留微张嘴与两种泪帧。')
    save(R / entry['preset'], p)
    entry['authored_parts'] = {slot: dict(path=p['parts'][slot], sha256=sha(R / p['parts'][slot]),
                                        source_script='tools/stylize_lament_expressions.py')
                               for slot in ['face_eye_left', 'face_eye_right']}
    entry['authored_description'] = '圆润大瞳孔、小高光和简洁眼线，保留委屈的眉形与泪花'
    entry['changed_slots'] = [slot for slot in p['parts'] if p['parts'][slot] != before[id]['parts'][slot]
        or p.get('geometry_offsets', {}).get(slot, {}) != before[id].get('geometry_offsets', {}).get(slot, {})]
    entry['adjustments'] = ['缩小眼白、扩大瞳孔，去掉双层厚眼睑与眼袋感。',
                            'N017 微张嘴、N012 眉毛、N024/N025 泪花原像素保留，泪片贴合新下眼缘。']
    entry['note'] = '本轮按可爱方向调整；上轮原图和使用场景仍作为情绪依据，画法为项目自己的卡通化设计。'
    for cp in (R / 'characters').glob('*.json'):
        c = read(cp)
        uv = read(R / c['expression_binding'])['uv']
        atlas = Image.open(R / c['base_texture']).convert('RGBA')
        for slot, ref in p['parts'].items():
            x, y, X, Y = uv[slot]
            with Image.open(R / ref) as im:
                atlas.paste(im.convert('RGBA').resize((X-x, Y-y), Image.Resampling.NEAREST), (x, y))
        atlas.save(D / 'textures' / c['id'] / f'{id}.png')
manifest['before'] = (S / 'before.json').relative_to(R).as_posix()
manifest['status'] = 'cute-lament-awaiting-review'
save(D / 'reuse-manifest.json', manifest)
save(S / 'eye-design.json', dict(palette=palette, left_rows=rows, right='horizontal mirror', parts=eyes,
     style='Rounded cartoon lament eyes requested by user; not a literal source sprite reconstruction.',
     emotional_reference='../round3/REFERENCE.md'))
print('Updated cute N041/N042 drafts and 12 character atlases')
