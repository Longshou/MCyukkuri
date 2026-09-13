"""Author the third expression review, preserving the pre-review recipes.

Run from any directory. Candidate pixels stay in drafts; explicit user choices
and the requested tear revision update the shared presets. No game export.
"""
from pathlib import Path
from PIL import Image, ImageDraw
import copy
import hashlib
import json

R = Path(__file__).resolve().parents[1]
O = R / 'expressions/component-review'
D = R / 'drafts/expression-review/round3'
DATE = '2026-09-12'


def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))


def save(p, data):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')


index = read(R / 'expressions/index.json')
presets = {k: read(R / v) for k, v in index['presets'].items()}
codes = {p['review_code']: p['id'] for p in presets.values()}
snapshot = O / 'before-n016-n040-round3.json'
if not snapshot.exists():
    save(snapshot, {codes[c]: presets[codes[c]] for c in
                    ['N020', 'N024', 'N025', 'N026', 'N032', 'N033', 'N037', 'N039', 'N040']})
before = read(snapshot)
part_index = read(R / 'expressions/parts/index.json')
known_parts = {}
for path in (R / 'expressions/parts').glob('*.png'):
    with Image.open(path) as source:
        rgba = source.convert('RGBA')
        sig = (rgba.size, rgba.tobytes())
    # Legacy IDs need not be the first 20 chars of the raw RGBA digest.
    existing = known_parts.get(sig)
    if existing is None or path.stem != hashlib.sha256(sig[1]).hexdigest()[:20]:
        known_parts[sig] = path.relative_to(R).as_posix()


def asset(im, draft=False):
    sig = (im.size, im.tobytes())
    if not draft and sig in known_parts:
        return known_parts[sig]
    digest = hashlib.sha256(im.tobytes()).hexdigest()
    target = (D / 'parts' if draft else R / 'expressions/parts') / (digest[:20] + '.png')
    target.parent.mkdir(parents=True, exist_ok=True)
    im.save(target)
    ref = target.relative_to(R).as_posix()
    if not draft:
        part_index[digest[:20]] = dict(path=ref, size=list(im.size), sha256=digest)
        known_parts[sig] = ref
    return ref


def grid(rows, colors, size, offset):
    im = Image.new('RGBA', size)
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c != '.':
                im.putpixel((x + offset[0], y + offset[1]), colors[c])
    return im


# Restore the original N037 pixels from the preserved original/candidate page.
# That page predates both N016–N040 revisions; its before_scale is exactly 1.
original_page = (R / 'drafts/expression-review/pending-expressions.html').read_text(encoding='utf-8')
original_data = json.loads(original_page.split('const DATA=', 1)[1].split(';\nconst get=', 1)[0])
original = next(row for row in original_data['rows'] if row['code'] == 'N037')
assert original['before_scale'] == 1
p = copy.deepcopy(before[codes['N037']])
p['parts'] = {slot: asset(Image.frombytes('RGBA', tuple(part['size']), bytes(part['pixels'])))
              for slot, part in original['before'].items()}
p.pop('geometry_offsets', None)
p['recipe']['tears'] = 'original-before-component-trials'
presets[p['id']] = p

accepted = {'N032': '采用第二轮新版。', 'N037': '恢复部件试用前原版眼口和泪帧；撤销后加的泪光位移。',
            'N039': '采用第二轮横向惊惧嘴新版。', 'N040': '采用第二轮纵向惊叫嘴新版。'}
for code, note in accepted.items():
    p = presets[codes[code]]
    p['status'] = 'accepted'
    p['art'] = 'User selected this expression on ' + DATE
    p.pop('duplicate_of', None)
    p['review_decision'] = dict(status='accepted', note=note, date=DATE,
                                basis='Explicit user selection in review round 3')

# Stronger tear rim: translucent cyan outline, broad pale fill, near-white glint.
# Keep the existing eye-relative geometry anchor; the bright strip grows down.
tear_rows = ['...............', '...............', '...............',
             '..c........c...', '..cllwwwwllc...', '...cllllllc....',
             '....cllllc.....', '.....cccc......']
tear_colors = {'c': (103, 166, 184, 235), 'l': (183, 226, 239, 245), 'w': (249, 253, 255, 255)}
tear = grid(tear_rows, tear_colors, (15, 16), (0, 0))
for code in ['N024', 'N025', 'N026']:
    p = copy.deepcopy(before[codes[code]])
    for side in ['left', 'right']:
        p['parts']['face_tear_' + side] = asset(tear if side == 'left' else tear.transpose(Image.Transpose.FLIP_LEFT_RIGHT))
    p['recipe']['tears'] = 'prominent-lower-eyelid-tear-rim'
    p['review_decision'] = dict(status='redrawn-awaiting-review', date=DATE,
                                note='加厚下眼缘泪花，增加青蓝边缘、浅蓝水面及白色亮边。')
    p.pop('duplicate_of', None)
    presets[p['id']] = p

# Distinct W silhouettes; the central peak is never allowed to collapse into U.
mouths = [
    ('A', '尖折 W', ['x.....x.....x', 'x.....x.....x', '.x...x.x...x.',
                    '.x...x.x...x.', '..x.x...x.x..', '...x.....x...'], '细线双折，中央尖点清楚。'),
    ('B', '紧压 W', ['xx....xx....xx', '.x....xx....x.', '.xx..x..x..xx.',
                    '..x..x..x..x..', '...xx....xx...'], '双谷压短，线条加厚，强调闭口用力。'),
    ('C', '短宽 W', ['x.....x.....x', '.x...x.x...x.', '..xxx...xxx..'], '较扁的 W，嘴角不画成上挑弧线。'),
    ('D', '带唇褶 W', ['..x...x...x..', 'x.x...x...x.x', '.x.x.x.x.x.x.',
                     '.x..x...x..x.', '..xx.....xx..', '.....xxx.....'], '双折加短唇褶，突出憋住和抿紧感。'),
]
variants = {}
for letter, label, rows, description in mouths:
    p = copy.deepcopy(before[codes['N020']])
    im = grid(rows, {'x': (72, 53, 46, 255)}, (28, 24), ((28 - len(rows[0])) // 2, 9))
    p['parts']['face_mouth'] = asset(im, draft=True)
    p['recipe']['mouth'] = 'closed-w-' + letter.lower()
    variants.setdefault('N020', []).append(dict(key=letter, label=label, description=description, preset=p))

# 15 x 12 native pixel eyes: flat shade bands, no reflected white spots.
eye_shapes = {
    'A': ['...............', '....xxxxxxx....', '...xaaaaaaax...', '..xaaaaaaaaax..',
          '.xaaaaaaaaaaax.', '.xaaaaaaaaaaax.', '.xbbbbbbbbbbbx.', '..xbbbbbbbbbx..',
          '...xcccccccx...', '....xcccccx....', '.....xxxxx.....', '...............'],
    'B': ['...............', '....xxxxxxx....', '...xaaaaaaax...', '..xaaaaaaaaax..',
          '.xaaaaapaaaaax.', '.xbaaaappaaabx.', '.xbbbbpppbbbbx.', '..xbbbbpbbbbx..',
          '...xcccccccx...', '....xcccccx....', '.....xxxxx.....', '...............'],
    'C': ['...............', '...............', '..xxxxxxxxxxx..', '..xaaaaaaaaax..',
          '..xaaaaaaaaax..', '..xbbbbbbbbbx..', '..xbbbbbbbbbx..', '...xbbbbbbbx...',
          '....xcccccx....', '.....xxxxx.....', '...............', '...............'],
    'D': ['...............', '....xxxxxxx....', '...xaaaaaaax...', '..xaaaaaaaaax..',
          '.xaaaaaaaaaaax.', '.xaaaaaaaaaaax.', '.xbbbbbbbbbbbx.', '..xbbbbbbbbbx..',
          '...xbbbbbbbx...', '....xbbbbbx....', '.....xxxxx.....', '...............'],
}
eye_styles = [
    ('A', '灰褐渐层', {'x': (63, 51, 46, 255), 'a': (74, 67, 63, 255), 'b': (116, 107, 96, 255), 'c': (153, 143, 128, 255)}, '无瞳孔、无反光，上暗下浅的三档灰褐色。'),
    ('B', '弱瞳失焦', {'x': (63, 51, 46, 255), 'a': (80, 74, 68, 255), 'b': (119, 111, 101, 255), 'c': (150, 140, 124, 255), 'p': (94, 88, 81, 255)}, '保留低对比瞳影，去掉亮点和同心圆。'),
    ('C', '半垂无光', {'x': (63, 51, 46, 255), 'a': (66, 63, 61, 255), 'b': (103, 99, 93, 255), 'c': (136, 131, 119, 255)}, '上眼睑压低，眼内只留暗灰色阶。'),
    ('D', '深灰空眼', {'x': (54, 48, 44, 255), 'a': (63, 61, 59, 255), 'b': (91, 87, 80, 255)}, '接近墨灰块面的空洞眼，只保留两档暗色。'),
]
for letter, label, palette, description in eye_styles:
    p = copy.deepcopy(before[codes['N033']])
    im = grid(eye_shapes[letter], palette, (15, 12), (0, 0))
    for side in ['left', 'right']:
        p['parts']['face_eye_' + side] = asset(im if side == 'left' else im.transpose(Image.Transpose.FLIP_LEFT_RIGHT), draft=True)
    p['recipe']['eyes'] = 'highlightless-' + letter.lower()
    variants.setdefault('N033', []).append(dict(key=letter, label=label, description=description, preset=p))

save(D / 'candidates.json', dict(date=DATE, status='pending-review', variants=variants,
     note='候选像素仅在本草稿目录；正式 N020/N033 保留上轮版，等待用户选择。'))
changed = [codes[c] for c in list(accepted) + ['N024', 'N025', 'N026']]
seen = {}
for p in presets.values():
    sig = json.dumps({k:p.get(k,v) for k,v in [('parts',{}),('requires',[]),('cheek_scale',1),('geometry_offsets',{})]}, sort_keys=True)
    if p['id'] in changed:
        p.pop('duplicate_of', None)
        if sig in seen:
            p['duplicate_of'] = seen[sig]
    seen.setdefault(sig, p['id'])
for id in changed:
    save(R / index['presets'][id], presets[id])
save(R / 'expressions/parts/index.json', part_index)

# Fingerprints identify exactly which recipe and referenced pixels were selected.
decisions = read(O / 'n016-n040-decisions.json')
decisions.update(round=3, comparison_snapshot=snapshot.name,
                 redrawn=['N020', 'N024', 'N025', 'N026', 'N033'])
for code, note in accepted.items():
    dest = 'keep_original' if code == 'N037' else 'accepted_new'
    if code not in decisions[dest]:
        decisions[dest].append(code)
    decisions['notes'][code] = note
decisions['notes']['N020'] = 'W 型抿嘴四款候选，待选 A/B/C/D。'
decisions['notes']['N033'] = '网络参考后制作四款无高光眼，待选 A/B/C/D。'
for code in ['N024', 'N025', 'N026']:
    decisions['notes'][code] = '泪花加厚、提亮，待确认。'
decisions.setdefault('confirmed_versions', {})
for code in accepted:
    p = presets[codes[code]]
    paths = [index['presets'][p['id']], *p['parts'].values()]
    decisions['confirmed_versions'][code] = dict(date=DATE, selection=accepted[code],
        files={ref: hashlib.sha256((R / ref).read_bytes()).hexdigest() for ref in sorted(set(paths))})
save(O / 'n016-n040-decisions.json', decisions)
progress = read(O / 'review-progress.json')
for rec in progress['records']:
    if rec['code'] in accepted:
        rec['status'] = 'accepted'
pending = {r['id'] for r in progress['records'] if r['status'] in ['not-individually-confirmed', 'redrawn-awaiting-review']}
progress['pending_order'] = [id for id in progress['pending_order'] if id in pending]
progress['pending_count'] = len(progress['pending_order'])
progress['accepted_new_codes'] = sorted(r['code'] for r in progress['records'] if r['status'] == 'accepted')
save(O / 'review-progress.json', progress)
md = '# 待确认表情\n\n当前剩余 %d 项（包含 A/B 泪帧）；本轮先看 N020、N024、N025、N026、N033。已确认项以配方中的 review_decision 和批次指纹登记为准。\n\n| 编号 | 表情 |\n| --- | --- |\n' % progress['pending_count']
md += ''.join('| %s | %s |\n' % (presets[id]['review_code'], presets[id]['label']) for id in progress['pending_order'])
(O / 'pending-expressions.md').write_text(md, encoding='utf-8')
print('Saved 4 selections; revised 3 tear recipes; authored 8 draft variants.')
