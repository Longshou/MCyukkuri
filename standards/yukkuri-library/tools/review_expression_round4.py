"""Fourth review: semantic puff expressions, separate tear phases, reference eyes.

Candidate recipes remain in drafts; N026 is explicitly accepted and N024/N025
receive the requested tear differences. Does not export game assets.
"""
from pathlib import Path
from PIL import Image
import copy
import hashlib
import json

R = Path(__file__).resolve().parents[1]
D = R / 'drafts/expression-review/round4'
O = R / 'expressions/component-review'
DATE = '2026-09-12'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    content = json.dumps(data, ensure_ascii=False, indent=2)
    if not path.exists() or path.read_text(encoding='utf-8') != content:
        path.write_text(content, encoding='utf-8')


idx = read(R / 'expressions/index.json')
presets = {id: read(R / ref) for id, ref in idx['presets'].items()}
codes = {p['review_code']: p['id'] for p in presets.values()}
snapshot = O / 'before-n016-n040-round4.json'
if not snapshot.exists():
    save(snapshot, {codes[c]: presets[codes[c]] for c in ['N020','N024','N025','N026','N033']})
before = read(snapshot)
part_index = read(R / 'expressions/parts/index.json')
known = {}
for path in (R / 'expressions/parts').glob('*.png'):
    with Image.open(path) as im:
        rgba = im.convert('RGBA')
        known[(rgba.size, rgba.tobytes())] = path.relative_to(R).as_posix()


def asset(im, draft=True):
    sig = (im.size, im.tobytes())
    if not draft and sig in known:
        return known[sig]
    digest = hashlib.sha256(sig[1]).hexdigest()
    path = (D / 'parts' if draft else R / 'expressions/parts') / (digest[:20] + '.png')
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        im.save(path)
    ref = path.relative_to(R).as_posix()
    if not draft:
        known[sig] = ref
        part_index[digest[:20]] = dict(path=ref, size=list(im.size), sha256=digest)
    return ref


def grid(rows, colors, size=None, offset=(0,0)):
    if size is None:
        size = (max(map(len, rows)), len(rows))
    im = Image.new('RGBA', size)
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c != '.':
                im.putpixel((x+offset[0], y+offset[1]), colors[c])
    return im


def mirrored_parts(p, kind, im, draft=True):
    p['parts']['face_'+kind+'_left'] = asset(im, draft)
    p['parts']['face_'+kind+'_right'] = asset(im.transpose(Image.Transpose.FLIP_LEFT_RIGHT), draft)


line = (81, 54, 44, 255)
lip = (131, 80, 63, 255)
skin_shadow = (185, 133, 95, 220)
ink = {'x': line, 'l': lip, 's': skin_shadow, 'w': (252,244,225,255),
       'p': (50,40,34,255), 'b': (95,69,51,255)}
variants = {}

# Recompose an expression rather than repeating a literal W glyph: press the
# central lips together, pull the corners down and coordinate brow/eye tension.
mouth_a = grid([
    '....ss.....ss....',
    '....sll...lls....',
    '...sl..l.l..ls...',
    '..sl....x....ls..',
    '..l.....x.....l..',
    '.s......l......s.',
    '.......sss.......',
], ink, (28,24), (5,8))
eye_a = grid([
    '...............','...............','...............','...............',
    '...............','..xxxxxxx......','..xxwwppxxxx...',
    '...xwwpppwwxx..','...xwwpppwwx...', '....xwpppwx....',
    '.....xxxxx.....','...............',
], ink)
brow_a = grid(['.................','..........xx.....','.......xxx.......',
               '....xxx..........','...x.............','.................'], ink)
p = copy.deepcopy(before[codes['N020']])
p['parts']['face_mouth'] = asset(mouth_a)
mirrored_parts(p,'eye',eye_a)
mirrored_parts(p,'brow',brow_a)
# Carry forward the wet, lower-eyelid rim, positioned for this narrowed eye.
tear_a = grid(['...............']*4 + ['...c..wwww..c..','....cllllllc...',
              '.....cllllc....','......cccc.....'],
              {'c':(117,177,187,225),'l':(202,234,239,245),'w':(251,254,248,255)}, (15,16))
mirrored_parts(p,'tear',tear_a)
for side in ['left','right']:
    p['geometry_offsets']['face_tear_'+side]['position'][1] = 1.31
p['geometry_offsets']['face_mouth'] = dict(position=[0,.12,0], scale=[.95,.90,1])
p['recipe'].update(eyes='puff-held-in-glare', brows='hurt-and-tense', mouth='pursed-lips-downturned-corners', tears='held-at-lower-lid')
variants['N020'] = [dict(key='A',label='鼓脸憋泪',description='两颊鼓起、收紧眼神，嘴唇中央抿住而两角向下；表现委屈但仍憋着气。',preset=p)]

mouth_b = grid([
    '.......sss.......','.....sll.lls.....','....sl..x..ls....',
    '...sl...x...ls...','..sl....l....ls..','..l.....s.....l..',
    '.......sss.......',
], ink, (28,24), (5,8))
eye_b = grid(['...............']*5+['..x............','...xx..........',
                '.....xxx.......','.......xxx.....','....xxxx.......','...xx..........','...............'],ink)
p = copy.deepcopy(variants['N020'][0]['preset'])
p['parts']['face_mouth'] = asset(mouth_b)
mirrored_parts(p,'eye',eye_b)
p['recipe'].update(eyes='puff-squeezed-holding-back-tears',mouth='trembling-pursed-lips')
variants['N020'].append(dict(key='B',label='赌气忍哭',description='挤紧双眼、泪花停在下眼缘，嘴角下坠而中央仍闭紧；表现快哭出来也不肯泄气。',preset=p))

# N024/N025 share the cry pose but must differ in the phase of flowing tears.
tear_colors = {'c':(105,166,183,230),'l':(185,227,239,245),'w':(249,253,255,255)}
pooled = grid(['...............']*3+[
    '..c.........c..','..clwwwwwwwlc..','...clllllllc...',
    '....clllllc....','.....ccccc.....',
],tear_colors,(15,16))
flow_left = grid(['...............']*3+[
    '..c.........c..','..clwwwwwlllc..','...clllllllc...',
    '....ccllllc....','......cllc.....','......cwlc.....',
    '......cwlc.....','......cwlc.....','.....clwllc....',
    '.....cllllc....','......cllc.....','.......cc......',
],tear_colors,(15,16))
flow_right = grid(['...............']*3+[
    '..c.........c..','..cllwwwwwllc..','...clllllllc...',
    '....ccllllc....','.....cllc......','.....cwlc......',
    '.....cllc......','......cc.......','...............',
    '.....cllc......','.....clwc......','......cc.......',
],tear_colors,(15,16))
for code,label in [('N024','蓄在眼眶'),('N025','沿脸颊滑落')]:
    p = copy.deepcopy(before[codes[code]])
    if code=='N024':
        mirrored_parts(p,'tear',pooled,draft=False)
    else:
        p['parts']['face_tear_left'] = asset(flow_left,draft=False)
        p['parts']['face_tear_right'] = asset(flow_right,draft=False)
    p['recipe']['tears'] = 'pooled-at-lid' if code=='N024' else 'running-trail-and-falling-drop'
    p['review_decision'] = dict(status='redrawn-awaiting-review',date=DATE,note=label+'；眼、嘴和哭喊姿态共用，泪帧明显区分。')
    p.pop('duplicate_of',None)
    presets[p['id']] = p

# Reference-led eyes: sclera surrounds a vertical iris, which touches the upper
# eyelid and curves at the bottom. Multiple per-row shades, no glint in the iris.
round_eye = [
    '.....xxxxx.....', '...xxwkkkwxx...', '..xwwkkkkkwwx..',
    '.xwwwkkkkkwwwx.','.xwwaaaaaaawwx.','.xwwbbbbbbbwwx.',
    '.xwwcccccccwwx.','.xwwdddddddwwx.','..xwweeeeewwx..',
    '...xwwfffwwx...','....xxxxxxx....','...............',
]
red = {'x':(42,31,31,255),'w':(247,240,224,255),'k':(30,17,20,255),
       'a':(59,17,22,255),'b':(101,18,25,255),'c':(154,22,30,255),
       'd':(200,29,36,255),'e':(219,44,47,255),'f':(214,53,50,255)}
half_eye = [
    '...............','...............','...xxxxxxxxx...',
    '.xxxxkkkkkxxxx.','xwwwkkkkkkkwwwx','.xwwaaaaaaawwx.',
    '.xwwbbbbbbbwwx.','..xwcccccccwx..','...xwdddddwx...',
    '....xxxxxxx....','...............','...............',
]
taupe = {'x':(43,34,38,255),'w':(245,239,225,255),'k':(43,34,48,255),
         'a':(61,45,60,255),'b':(87,64,76,255),'c':(118,87,95,255),
         'd':(151,113,116,255),'e':(171,133,132,255)}
eyes = [('A','红瞳无反光',grid(round_eye,red),
         '对应附图一上排：保留眼白，竖椭圆虹膜由上黑到下红，无白色反光点。'),
        ('B','红瞳带暗瞳孔',grid(round_eye,red),
         '对应附图一带瞳孔版：黑色竖瞳融入上方暗部，外围仍保留红色虹膜和眼白。'),
        ('C','灰紫半垂眼',grid(half_eye,taupe),
         '对应附图二：上眼睑压住虹膜，上深紫灰、下浅灰粉，眼白从两侧包住虹膜。')]
for letter,label,im,description in eyes:
    # Shape the iris shading continuously at native resolution. Curved value
    # changes avoid the previous stack of flat horizontal color strips.
    palette = red if letter in ['A','B'] else taupe
    iris_colors = {value for key,value in palette.items() if key not in ['x','w']}
    top,bottom = ((24,15,20),(223,37,43)) if letter in ['A','B'] else ((40,32,46),(175,135,137))
    y0,y1 = (1,9) if letter in ['A','B'] else (3,8)
    for y in range(im.height):
        for x in range(im.width):
            if im.getpixel((x,y)) not in iris_colors:
                continue
            t = max(0,min(1,(y-y0)/(y1-y0)-.13*((x-7)/3.5)**2))
            t = t*t*(3-2*t)
            im.putpixel((x,y),tuple(round(a+(b-a)*t) for a,b in zip(top,bottom))+(255,))
    if letter=='B':
        # Continuous dark pupil, not the previous concentric ring treatment.
        for y,xs in [(4,[7]),(5,[6,7,8]),(6,[6,7,8]),(7,[6,7,8]),(8,[7])]:
            for x in xs:im.putpixel((x,y),(30,18,20,255))
    p = copy.deepcopy(before[codes['N033']])
    mirrored_parts(p,'eye',im)
    if letter in ['A','B']:
        for side in ['left','right']:
            p.setdefault('geometry_offsets',{})['face_eye_'+side] = dict(scale=[.82,1.04,1])
    p['recipe']['eyes'] = 'reference-sclera-iris-no-glint-'+letter.lower()
    variants.setdefault('N033',[]).append(dict(key=letter,label=label,description=description,preset=p))

save(D/'candidates.json',dict(date=DATE,status='pending-review',variants=variants,
    number_assumption='User wrote 27; supplied no-highlight eye images and preceding variants identify N033. N027 is not reselected.',
    reference_images=['user-reference-eyes.png','user-reference-half-lid.png']))

# N026 acceptance freezes the previously shown brighter tear rim.
p = copy.deepcopy(before[codes['N026']])
p['status'] = 'accepted'
p['art'] = 'User selected the round-3 brighter tear version on '+DATE
p['review_decision'] = dict(status='accepted',date=DATE,note='用户确认“26可以”：保存第三轮加强泪花的版本。')
presets[p['id']] = p
for id in [codes[c] for c in ['N024','N025','N026']]:
    save(R/idx['presets'][id],presets[id])
save(R/'expressions/parts/index.json',part_index)
decisions = read(O/'n016-n040-decisions.json')
decisions.update(round=4,comparison_snapshot=snapshot.name,redrawn=['N020','N024','N025','N033'])
if 'N026' not in decisions['accepted_new']:decisions['accepted_new'].append('N026')
decisions['notes'].update(N020='按委屈憋气重组眼口，两款候选。',N024='蓄泪帧，待确认。',
    N025='长泪痕和滑落泪滴，与 N024 直接对比，待确认。',N026='接受第三轮加强泪花版。',
    N033='按用户附图保留眼白与虹膜结构，三款候选。')
decisions['number_assumption_round4'] = '用户末项写27，依据附图及上下文按N033处理；待用户纠正。'
paths = [idx['presets'][p['id']],*p['parts'].values()]
decisions['confirmed_versions']['N026'] = dict(date=DATE,selection='第三轮加强泪花版',
    files={ref:hashlib.sha256((R/ref).read_bytes()).hexdigest() for ref in sorted(set(paths))})
save(O/'n016-n040-decisions.json',decisions)
progress = read(O/'review-progress.json')
for row in progress['records']:
    if row['code']=='N026':row['status']='accepted'
progress['pending_order'] = [id for id in progress['pending_order'] if id!=codes['N026']]
progress['pending_count'] = len(progress['pending_order'])
progress['accepted_new_codes'] = sorted(row['code'] for row in progress['records'] if row['status']=='accepted')
save(O/'review-progress.json',progress)
md = '# 待确认表情\n\n剩余 %d 项；本轮先看 N020、N024/N025 泪帧对比、N033。N026 已确认第三轮版。\n\n| 编号 | 表情 |\n| --- | --- |\n' % progress['pending_count']
md += ''.join('| %s | %s |\n' % (presets[id]['review_code'],presets[id]['label']) for id in progress['pending_order'])
(O/'pending-expressions.md').write_text(md,encoding='utf-8')
print('Round 4: N026 accepted; distinct N024/N025 tears; 2 puff and 3 reference eye candidates.')
