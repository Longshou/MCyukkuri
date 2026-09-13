"""Apply round-5 choices; fit tears to accepted closed eyes and widen irises."""
from pathlib import Path
from PIL import Image
import copy
import hashlib
import json

R=Path(__file__).resolve().parents[1]
O=R/'expressions/component-review'
D=R/'drafts/expression-review/round5'
DATE='2026-09-12'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))


def save(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    text=json.dumps(data,ensure_ascii=False,indent=2)
    if not path.exists() or path.read_text(encoding='utf-8')!=text:path.write_text(text,encoding='utf-8')


index=read(R/'expressions/index.json')
presets={id:read(R/ref) for id,ref in index['presets'].items()}
previous=read(R/'drafts/expression-review/round4/candidates.json')['variants']
snapshot=O/'before-n016-n040-round5.json'
if not snapshot.exists():save(snapshot,{id:presets[id] for id in ['puff_teary','tongue_cry','tongue_cry_b','dull']})
part_index=read(R/'expressions/parts/index.json')
known={}
for path in (R/'expressions/parts').glob('*.png'):
    with Image.open(path) as source:
        im=source.convert('RGBA');known[(im.size,im.tobytes())]=path.relative_to(R).as_posix()


def asset(im,draft=False):
    sig=(im.size,im.tobytes())
    if not draft and sig in known:
        ref=known[sig]
        part_index.setdefault(Path(ref).stem,dict(path=ref,size=list(im.size),sha256=hashlib.sha256(sig[1]).hexdigest()))
        return ref
    digest=hashlib.sha256(sig[1]).hexdigest()
    path=(D/'parts' if draft else R/'expressions/parts')/(digest[:20]+'.png')
    path.parent.mkdir(parents=True,exist_ok=True)
    if not path.exists():im.save(path)
    ref=path.relative_to(R).as_posix()
    if not draft:
        known[sig]=ref;part_index[digest[:20]]=dict(path=ref,size=list(im.size),sha256=digest)
    return ref


# User selected B's composition, with the previously accepted E3-PUFF eye lines.
p=copy.deepcopy(next(v['preset'] for v in previous['N020'] if v['key']=='B'))
accepted_eyes=read(O/'accepted-components.json')['accepted']['E3-PUFF']['parts']
geometry={e['name']:e for e in read(R/'components/common/face.json')['elements']}
for side in ['left','right']:
    eye_slot='face_eye_'+side;tear_slot='face_tear_'+side
    p['parts'][eye_slot]=presets['puff']['parts'][eye_slot]
    eye=Image.open(R/p['parts'][eye_slot]).convert('RGBA')
    assert list(eye.tobytes())==accepted_eyes[eye_slot]['pixels']
    tears=Image.new('RGBA',(15,16))
    # Eye and tear planes both have 0.265 model units per vertical source pixel.
    # Trace the lower edge of each eye independently (the two approved lines
    # are not exact mirrors), placing water immediately below the black line.
    for x in range(4,12):
        occupied=[y for y in range(eye.height) if eye.getpixel((x,y))[3]]
        if not occupied or max(occupied)<7:continue
        y=max(occupied)+1
        colors=[(245,253,252,250),(192,229,237,240),(108,172,189,225)]
        if x in [4,11]:colors=colors[1:]
        for dy,color in enumerate(colors):
            if y+dy<16:tears.putpixel((x,y+dy),color)
    p['parts'][tear_slot]=asset(tears)
    eye_top=geometry[eye_slot]['to'][1]+p['geometry_offsets'][eye_slot]['position'][1]
    p['geometry_offsets'][tear_slot]=dict(position=[0,round(eye_top-geometry[tear_slot]['to'][1],5),0])
# Promote B's chosen draft components into the shared asset store.
for slot,ref in list(p['parts'].items()):
    if ref.startswith('drafts/'):
        with Image.open(R/ref) as source:p['parts'][slot]=asset(source.convert('RGBA'))
p['recipe'].update(eyes='accepted-E3-PUFF',tears='fitted-to-approved-closed-eye-edge')
p['review_decision']=dict(status='redrawn-awaiting-review',date=DATE,
    selected_base='round4/N020-B',accepted_eye_components='E3-PUFF',
    note='采用用户选定 B 版构图，恢复已确认闭眼线；泪花按左右下眼缘重新适配，适配后效果待查看。')
p['art']='Selected round-4 B composition; approved closed eyes; revised tear fitting awaits visual review.'
p.pop('duplicate_of',None)
presets[p['id']]=p
save(R/index['presets'][p['id']],p)

# Expand iris width into the sclera by one pixel per side, preserving a visible
# outer white margin. B's dark pupil widens from 3 to 5 pixels at its center.
new_variants=[]
for v in previous['N033']:
    candidate=copy.deepcopy(v['preset']);letter=v['key']
    for side in ['left','right']:
        slot='face_eye_'+side
        im=Image.open(R/candidate['parts'][slot]).convert('RGBA');source=im.copy()
        outline=(42,31,31,255) if letter in ['A','B'] else (43,34,38,255)
        white=(247,240,224,255) if letter in ['A','B'] else (245,239,225,255)
        for y in range(im.height):
            iris=[x for x in range(im.width) if (color:=source.getpixel((x,y)))[3] and color not in [outline,white]]
            if not iris:continue
            lo,hi=min(iris),max(iris)
            if lo>=2 and source.getpixel((lo-1,y))==white and source.getpixel((lo-2,y))==white:
                im.putpixel((lo-1,y),source.getpixel((lo,y)))
            if hi+2<im.width and source.getpixel((hi+1,y))==white and source.getpixel((hi+2,y))==white:
                im.putpixel((hi+1,y),source.getpixel((hi,y)))
        if letter=='B':
            pupil=(30,18,20,255)
            for y in range(4,9):
                xs=[x for x in range(source.width) if source.getpixel((x,y))==pupil]
                for x in xs:
                    for dx in [-1,1]:
                        if 0<=x+dx<im.width and im.getpixel((x+dx,y)) not in [white,outline,(0,0,0,0)]:
                            im.putpixel((x+dx,y),pupil)
        candidate['parts'][slot]=asset(im,draft=True)
    candidate['recipe']['eyes']+='-broader-iris'
    new_variants.append(dict(key=letter,label=v['label']+' · 加宽',preset=candidate,
        description='虹膜中段由约 7 像素加宽到 9 像素，保留两侧眼白。'+('暗瞳孔中段由 3 像素加宽到 5 像素。' if letter=='B' else '')))
save(D/'candidates.json',dict(date=DATE,status='pending-review',variants={
    'N020':[dict(key='B',label='B 版 · 恢复已确认闭眼线',preset=p,description='B 版嘴、眉及鼓脸保留；直接复用此前确认的挤眼线，泪花按左右眼线下缘贴合。')],
    'N033':new_variants}))

decisions=read(O/'n016-n040-decisions.json')
for code,id in [('N024','tongue_cry'),('N025','tongue_cry_b')]:
    p=presets[id];p['status']='accepted';p['art']='User accepted round-4 distinct tear frame on '+DATE
    p['review_decision']=dict(status='accepted',date=DATE,note='用户确认第四轮蓄泪/流泪差分。')
    save(R/index['presets'][id],p)
    if code not in decisions['accepted_new']:decisions['accepted_new'].append(code)
    paths=[index['presets'][id],*p['parts'].values()]
    decisions['confirmed_versions'][code]=dict(date=DATE,selection='第四轮蓄泪帧' if code=='N024' else '第四轮滑落泪帧',
        files={ref:hashlib.sha256((R/ref).read_bytes()).hexdigest() for ref in sorted(set(paths))})
    decisions['notes'][code]='已接受第四轮泪帧。'
decisions.update(round=5,comparison_snapshot=snapshot.name,redrawn=['N020','N033'])
decisions['notes'].update(N020='选定 B 构图；恢复 E3-PUFF 闭眼线，泪花适配待看。',N033='三版虹膜加宽；B 的暗瞳孔同步加宽，待选。')
decisions['selected_components_N020']=dict(date=DATE,base='drafts/expression-review/round4/candidates.json / N020-B',
    eye_donor='expressions/component-review/accepted-components.json / E3-PUFF',
    status='base and eyes selected; fitted tears awaiting review')
save(O/'n016-n040-decisions.json',decisions)
save(R/'expressions/parts/index.json',part_index)
progress=read(O/'review-progress.json')
for row in progress['records']:
    if row['code'] in ['N024','N025']:row['status']='accepted'
progress['pending_order']=[id for id in progress['pending_order'] if id not in ['tongue_cry','tongue_cry_b']]
progress['pending_count']=len(progress['pending_order'])
progress['accepted_new_codes']=sorted(row['code'] for row in progress['records'] if row['status']=='accepted')
save(O/'review-progress.json',progress)
md='# 待确认表情\n\n剩余 %d 项；本轮先看 N020 闭眼泪花适配与 N033 加宽虹膜。N024/N025 已确认第四轮差分。\n\n| 编号 | 表情 |\n| --- | --- |\n'%progress['pending_count']
md+=''.join('| %s | %s |\n'%(presets[id]['review_code'],presets[id]['label']) for id in progress['pending_order'])
(O/'pending-expressions.md').write_text(md,encoding='utf-8')
print('N024/N025 accepted; N020 B uses approved eye pixels; 3 iris widths revised.')
